package com.deepseekharness.app.backup;

import android.content.Context;
import android.os.Environment;
import com.deepseekharness.app.core.ConfigStore;
import com.deepseekharness.app.core.HarnessController;
import com.deepseekharness.app.runtime.ProfileSettingsTrial;
import com.deepseekharness.app.util.ProfileConfigPath;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** profile 普通设置的预检、选择、事务与真实服务读回；导入代码继续保留在隔离区。 */
public final class ProfileSettingsTransaction implements HostDataTransaction.Targets {
  private static final String RECORD = "profile-settings.json";
  private final Context context;
  private final BackupFileSystem fs;
  private final File files;
  private File task;
  private final File home;
  private final String profile;
  private final Map<String, Object> record;

  private ProfileSettingsTransaction(Context context, File task) throws IOException {
    this.context = context.getApplicationContext();
    fs = new AndroidBackupFileSystem();
    files = context.getFilesDir().getCanonicalFile();
    this.task = task;
    if (!task.getParentFile().equals(HostOperationArchive.root(files))
            && !task.getParentFile().equals(HostOperationArchive.completedRoot(files))
        || !com.deepseekharness.app.util.Ids.uuid(task.getName()))
      throw new IOException("SETTINGS_TRANSACTION_PATH");
    record =
        BackupJson.read(
            fs.small(new File(task, RECORD), BackupLimits.MANIFEST), BackupLimits.MANIFEST);
    profile = BackupJson.string(record, "profile");
    if (!ProfileConfigPath.profile(profile) || !task.getName().equals(record.get("id")))
      throw new IOException("SETTINGS_TRANSACTION_RECORD");
    UserDataLayout.Home chosen;
    try {
      chosen = UserDataLayout.Home.valueOf(BackupJson.string(record, "home"));
    } catch (IllegalArgumentException error) {
      throw new IOException("SETTINGS_TRANSACTION_RECORD");
    }
    home = resolveHome(context, fs, files, new UserDataLayout(fs, files).root(chosen));
  }

  public static File resolveHome(Context context, BackupFileSystem fs, File files, File home)
      throws IOException {
    File publicRoot = Environment.getExternalStorageDirectory().getCanonicalFile();
    return new GuestDataResolver(
            fs,
            com.deepseekharness.app.util.GuestPaths.rootfs(files),
            publicRoot,
            List.of(new File(files, "user-data-v5"), new File(publicRoot, "Documents/dshdata")))
        .resolve(home)
        .file;
  }

  @Override
  public File resolve(String id) throws IOException {
    if (id.equals("environment") && record.get("workspace") instanceof String) {
      String workspace =
          NativeConfigurationReset.normalizeWorkspace((String) record.get("workspace"));
      return fs.child(
          files, com.deepseekharness.app.util.GuestPaths.ROOT_RELATIVE + "/" + workspace + "/.env");
    }
    if (!id.equals("profile-patch")) throw new IOException("SETTINGS_TRANSACTION_TARGET");
    return fs.child(home, "profiles/" + profile + "/cordis.patch.yml");
  }

  private HostDataTransaction.Settings nativeSettings() {
    return new HostDataTransaction.Settings() {
      public Map<String, Object> current() {
        return new ConfigStore(context).hostSettingsState();
      }

      public void apply(Map<String, Object> values) throws IOException {
        new ConfigStore(context).applyHostSettings(values);
      }
    };
  }

  private byte[] patch() throws IOException {
    File path = resolve("profile-patch");
    return fs.stat(path).type.equals("MISSING")
        ? "[]\n".getBytes(StandardCharsets.UTF_8)
        : fs.small(path, BackupLimits.MANIFEST);
  }

  private String profileDigest(BackupControl control) throws IOException {
    String identity =
        BackupTree.digest(fs, fs.child(home, "profiles/" + profile), control)
            + BackupTree.digest(fs, new File(home, "cordis.patch.yml"), control);
    var runtime = HarnessController.get(context).proot().installedRuntimeDescriptor();
    if (runtime == null) throw new IOException("RUNTIME_DESCRIPTOR_MISSING");
    return NativeDataLocations.hash(identity + runtime.id());
  }

  public static Map<String, Object> preview(
      Context context, String retainedKey, BackupControl control) throws Exception {
    return com.deepseekharness.app.core.MaintenanceCoordinator.exclusive(
        HarnessController.get(context),
        () -> {
          var fs = new AndroidBackupFileSystem();
          File files = context.getFilesDir().getCanonicalFile();
          RetainedCatalogue catalog =
              new RetainedCatalogue(fs, files, new UserDataLayout(fs, files).current());
          var entry = catalog.resolve(retainedKey);
          if (entry.kind != RetainedCatalogue.Kind.SETTINGS || entry.source == null)
            throw new IOException("SETTINGS_RETAINED_SOURCE");
          File patch = new File(entry.source, "cordis.patch.yml");
          byte[] incoming =
              fs.stat(patch).type.equals("FILE")
                  ? fs.small(patch, BackupLimits.MANIFEST)
                  : "[]\n".getBytes(StandardCharsets.UTF_8);
          return prepare(context, entry.displayName, incoming, retainedKey, "preview", control);
        });
  }

  private static Map<String, Object> prepare(
      Context context,
      String profile,
      byte[] incoming,
      String source,
      String mode,
      BackupControl control)
      throws IOException {
    var fs = new AndroidBackupFileSystem();
    File files = context.getFilesDir().getCanonicalFile(),
        parent = HostOperationArchive.reserve(fs, files);
    String id = UUID.randomUUID().toString();
    File task = new File(parent, id);
    fs.directory(task);
    Map<String, Object> record = new LinkedHashMap<>();
    record.put("version", 1L);
    record.put("id", id);
    record.put("profile", profile);
    record.put("home", new UserDataLayout(fs, files).selected().name());
    record.put("source", source);
    record.put("mode", mode);
    fs.atomic(task, "incoming.yml", incoming);
    record.put("incomingHash", BackupTree.digest(fs, new File(task, "incoming.yml"), control));
    fs.atomic(task, RECORD, BackupJson.write(record, BackupLimits.MANIFEST));
    ProfileSettingsTransaction operation = new ProfileSettingsTransaction(context, task);
    String before = operation.profileDigest(control);
    record.put("profileBefore", before);
    record.put("patchBefore", BackupTree.digest(fs, operation.resolve("profile-patch"), control));
    ProfileSettingsTrial.Result trial =
        ProfileSettingsTrial.run(
            context,
            HarnessController.get(context).proot(),
            operation.home,
            profile,
            operation.patch(),
            incoming,
            Map.of("mode", mode),
            control);
    if (!before.equals(operation.profileDigest(control))) throw new IOException("TARGET_CHANGED");
    record.put("review", trial.review);
    fs.atomic(task, RECORD, BackupJson.write(record, BackupLimits.MANIFEST));
    Map<String, Object> output = new LinkedHashMap<>(trial.review);
    output.put("operation", id);
    output.put("profile", profile);
    return output;
  }

  public static Map<String, Object> apply(
      Context context, String operation, Set<String> selected, BackupControl control)
      throws Exception {
    if (selected.isEmpty()) throw new IOException("SETTINGS_SELECTION_EMPTY");
    return com.deepseekharness.app.core.MaintenanceCoordinator.exclusive(
        HarnessController.get(context), () -> open(context, operation).commit(selected, control));
  }

  /** 应急原生确认后的普通设置修复，仍使用当前 schema 预检、相同停止屏障和读回事务。 */
  public static Map<String, Object> repair(
      Context context, String profile, byte[] incoming, BackupControl control) throws IOException {
    if (!com.deepseekharness.app.core.MaintenanceCoordinator.isOwner())
      throw new IOException("SETTINGS_REQUIRES_MAINTENANCE");
    if (!ProfileConfigPath.profile(profile) || incoming == null || incoming.length > 262144)
      throw new IOException("SETTINGS_REPAIR_INPUT");
    if (com.deepseekharness.app.core.MaintenanceCoordinator.pending(context.getFilesDir()))
      throw new IOException("SETTINGS_RECOVERY_REQUIRED");
    Map<String, Object> reviewed =
        prepare(context, profile, incoming, "native-emergency-confirmation", "preview", control);
    if (!"VERIFIED".equals(reviewed.get("status"))
        || reviewed.get("warnings") instanceof List
            && !((List<?>) reviewed.get("warnings")).isEmpty())
      throw new IOException("SETTINGS_REPAIR_REQUIRES_REVIEW");
    Object rows = reviewed.get("items");
    if (!(rows instanceof List)) throw new IOException("SETTINGS_REPAIR_REVIEW");
    Set<String> selected = new LinkedHashSet<>();
    for (Object value : (List<?>) rows) {
      if (!(value instanceof Map)) throw new IOException("SETTINGS_REPAIR_REVIEW");
      @SuppressWarnings("unchecked")
      Map<String, Object> item = (Map<String, Object>) value;
      // 只确认当前 schema 可表达的普通设置，任何代码/缺失命名空间警告都在上方拒绝。
      selected.add(BackupJson.string(item, "id"));
    }
    if (selected.isEmpty()) throw new IOException("SETTINGS_REPAIR_NO_DECLARATIVE_CHANGES");
    return open(context, (String) reviewed.get("operation")).commit(selected, control);
  }

  @SuppressWarnings("unchecked")
  private Map<String, Object> commit(Set<String> selected, BackupControl control)
      throws IOException {
    boolean reset = "reset".equals(record.get("mode"));
    if (selected.isEmpty() && !reset) throw new IOException("SETTINGS_SELECTION_EMPTY");
    if (!record.get("profileBefore").equals(profileDigest(control)))
      throw new IOException("TARGET_CHANGED");
    Map<String, Object> preview = (Map<String, Object>) record.get("review");
    Set<String> allowed = new HashSet<>();
    for (Object value : (List<?>) preview.get("items"))
      allowed.add(BackupJson.string((Map<String, Object>) value, "id"));
    if (!allowed.containsAll(selected)) throw new IOException("SETTINGS_SELECTION_CHANGED");
    if (task.getParentFile().equals(HostOperationArchive.completedRoot(files)))
      task = HostOperationArchive.activateReviewedProfile(fs, files, task.getName());
    byte[] incoming = fs.small(new File(task, "incoming.yml"), BackupLimits.MANIFEST);
    if (!record
        .get("incomingHash")
        .equals(BackupTree.digest(fs, new File(task, "incoming.yml"), control)))
      throw new IOException("INPUT_CHANGED");
    String mode = "reset".equals(record.get("mode")) ? "reset" : "apply";
    ProfileSettingsTrial.Result prepared =
        ProfileSettingsTrial.run(
            context,
            HarnessController.get(context).proot(),
            home,
            profile,
            patch(),
            incoming,
            Map.of("mode", mode, "selected", new ArrayList<>(selected)),
            control);
    if (!record.get("profileBefore").equals(profileDigest(control)))
      throw new IOException("TARGET_CHANGED");
    File candidate = new File(task, "candidate");
    fs.directory(candidate);
    try (OutputStream out = fs.create(new File(candidate, "profile-patch"))) {
      out.write(prepared.patch);
    }
    var settings = nativeSettings();
    HostDataTransaction transaction = new HostDataTransaction(fs, task, this, settings, null);
    List<String> roots = new ArrayList<>(List.of("profile-patch"));
    Map<String, String> before = new LinkedHashMap<>();
    before.put("profile-patch", (String) record.get("patchBefore"));
    if (record.containsKey("workspace")) {
      roots.add("environment");
      before.put("environment", (String) record.get("environmentBefore"));
      try (OutputStream out = fs.create(new File(candidate, "environment"))) {
        out.write(fs.small(new File(task, "environment-input"), 1024 * 1024));
      }
    }
    transaction.prepare(roots, before, settings.current(), control);
    transaction.commit(
        control,
        new HostDataTransaction.CommitCheck() {
          public void verify() throws IOException {
            ProfileSettingsTrial.run(
                context,
                HarnessController.get(context).proot(),
                home,
                profile,
                patch(),
                incoming,
                Map.of("mode", "verify", "expected", prepared.review.get("items")),
                control);
          }

          public boolean mayRecover() {
            return !com.deepseekharness.app.core.RuntimeTasks.hasOtherTasks();
          }
        });
    fs.atomic(
        task, "settings-readback.json", BackupJson.write(prepared.review, BackupLimits.MANIFEST));
    try {
      HostOperationArchive.archiveIfTerminal(fs, files, task);
    } catch (IOException ignored) {
    }
    return prepared.review;
  }

  private static ProfileSettingsTransaction open(Context context, String id) throws IOException {
    if (id == null || !id.matches(com.deepseekharness.app.util.Ids.UUID_PATTERN))
      throw new IOException("SETTINGS_TRANSACTION_ID");
    var fs = new AndroidBackupFileSystem();
    File files = context.getFilesDir().getCanonicalFile();
    return new ProfileSettingsTransaction(
        context, HostOperationArchive.locate(fs, HostOperationArchive.root(files), id));
  }

  @SuppressWarnings("unchecked")
  public static File reset(
      Context context, String workspace, byte[] environment, BackupControl control)
      throws IOException {
    ConfigStore store = new ConfigStore(context);
    HostDataTransaction.Settings settings =
        new HostDataTransaction.Settings() {
          public Map<String, Object> current() {
            return store.hostSettingsState();
          }

          public void apply(Map<String, Object> values) throws IOException {
            store.applyHostSettings(values);
          }
        };
    return NativeConfigurationReset.reset(
        new AndroidBackupFileSystem(),
        context.getFilesDir().getCanonicalFile(),
        workspace,
        environment,
        settings,
        null,
        control);
  }

  public static boolean owns(BackupFileSystem fs, File directory) throws IOException {
    return fs.stat(new File(directory, RECORD)).type.equals("FILE");
  }

  public static void recover(Context context, File task) throws IOException {
    var operation = new ProfileSettingsTransaction(context, task);
    new HostDataTransaction(operation.fs, task, operation, operation.nativeSettings(), null)
        .recover();
  }
}
