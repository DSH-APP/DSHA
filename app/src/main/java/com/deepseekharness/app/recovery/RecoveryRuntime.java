package com.deepseekharness.app.recovery;

import android.content.Context;
import android.system.ErrnoException;
import android.system.Os;
import android.system.OsConstants;
import com.deepseekharness.app.BuildConfig;
import com.deepseekharness.app.runtime.TarGzipExtractor;
import com.deepseekharness.app.util.Compat;
import com.deepseekharness.app.util.ProcessTermination;
import com.deepseekharness.app.util.ProcessIdentity;
import com.deepseekharness.app.util.RuntimeInstanceRegistry;
import com.deepseekharness.app.util.RecoveryArchiveSource;
import com.deepseekharness.app.util.ShellQuote;
import com.deepseekharness.app.util.WebPidIdentity;
import com.deepseekharness.app.util.UiText;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

/** 只从独立锁定的 APK 资产准备应急舱，不调用正式环境的迁移、绑定或维护门禁。 */
public final class RecoveryRuntime {
  /** /proc 暂不可读不否定已交换成功的网页，但不能解除正式数据的停止屏障。 */
  public static final class IdentityUnavailable extends IOException {
    IdentityUnavailable(Throwable cause) {
      super("RECOVERY_PROCESS_INSPECTION_DENIED", cause);
    }
  }

  private static final java.util.Set<String> PREPARING =
      java.util.concurrent.ConcurrentHashMap.newKeySet();
  private static final long RESERVE_BYTES = 128L * 1024 * 1024;
  private static final String[] OVERLAY_PATHS = {
    "usr/local/lib/node_modules/@deepseek-ai/dsh/node_modules/@deepseek-ai/dsh-web-frontend/dist/index.html",
    "usr/local/lib/node_modules/@deepseek-ai/dsh/node_modules/@deepseek-ai/dsh-client-ui-sidebar-documentpreview/lib/client.pdf.js",
    "usr/local/lib/node_modules/@deepseek-ai/dsh/node_modules/@deepseek-ai/dsh-client-resources/lib/client.js",
    "usr/local/lib/node_modules/@deepseek-ai/dsh/node_modules/@deepseek-ai/dsh-client-locale/lib/client.js"
  };
  private static final String[] OVERLAY_ASSETS = {
    "recovery-overlay/index.html",
    "recovery-overlay/client.pdf.js",
    "recovery-overlay/client-resources.js",
    "recovery-overlay/client-locale.js"
  };
  private static final String[] OVERLAY_INPUTS = {
    "web-integration/es-compat.js",
    "web-integration/startup.js",
    "pdf-compat-patch.json",
    "language-patch.json",
    "web-integration/language.js"
  };
  private final Context context;
  private final File files;
  private final JSONObject descriptor;
  private final Map<String, String> archiveSources;
  private final BooleanSupplier cancelled;
  private final Consumer<String> progress;
  public final String runtimeId, instanceId, profile;
  public final File session, home;
  private File rootfs;

  public RecoveryRuntime(
      Context context, String instanceId, BooleanSupplier cancelled, Consumer<String> progress)
      throws IOException {
    if (!RuntimeInstanceRegistry.validId(instanceId)) throw new IOException("RECOVERY_INSTANCE_ID");
    this.context = context.getApplicationContext();
    this.instanceId = instanceId;
    this.cancelled = cancelled;
    this.progress = progress;
    files = privateFiles(this.context);
    try {
      descriptor =
          new JSONObject(
              new String(asset("recovery-runtime.json", 8 * 1024 * 1024), StandardCharsets.UTF_8));
      runtimeId = descriptor.getString("id");
      if (descriptor.getInt("schema") != 1 || !runtimeId.matches("[a-f0-9]{64}"))
        throw new IOException("RECOVERY_DESCRIPTOR_INVALID");
    } catch (JSONException error) {
      throw new IOException("RECOVERY_DESCRIPTOR_INVALID", error);
    }
    archiveSources = readArchiveSources();
    profile = "dsha-emergency-" + instanceId;
    File sessions = directory(files, "recovery-sessions");
    session = new File(sessions, instanceId);
    if (session.exists()) throw new IOException("RECOVERY_INSTANCE_REUSED");
    mkdir(session);
    home = directory(session, "home");
    write(
        new File(session, "intent.json"),
        ("{\"schema\":1,\"instanceId\":\""
                + instanceId
                + "\",\"runtimeId\":\""
                + runtimeId
                + "\"}\n")
            .getBytes(StandardCharsets.UTF_8));
  }

  public void prepare() throws IOException {
    check();
    progress.accept(UiText.choose("正在核验独立应急运行时…", "Verifying the independent recovery runtime…"));
    File capsules = directory(files, "recovery-capsules");
    File capsule = new File(capsules, runtimeId);
    rootfs = new File(capsule, "linux/ubuntu");
    verifyNativeLibraries();
    if (capsule.exists()) {
      try {
        verifyRoot(rootfs);
        return;
      } catch (IOException damaged) {
        check();
        // 留存受损原件，新的候选在另一目录中完成；不覆盖用户或旧会话。
        progress.accept(
            UiText.choose(
                "应急运行时副本校验失败，正在从签名安装包重建…",
                "The recovery runtime copy failed verification. Rebuilding from the signed APK…"));
      }
    }
    File selected = null;
    try {
      var fs = new com.deepseekharness.app.backup.AndroidBackupFileSystem();
      selected = RecoveryStoragePlan.reusable(fs, files, capsules, runtimeId, PREPARING);
      if (selected != null && !PREPARING.add(selected.getAbsolutePath())) selected = null;
      if (selected != null) {
        progress.accept(
            UiText.choose("正在复用已完整核验的应急候选…", "Reusing a fully verified recovery candidate…"));
        verifyRoot(new File(selected, "linux/ubuntu"));
        publishCapsule(selected, capsule, capsules);
        return;
      }
      long bytes = descriptor.getLong("expandedBytes");
      if (bytes <= 0
          || bytes > Long.MAX_VALUE - RESERVE_BYTES
          || files.getUsableSpace() < bytes + RESERVE_BYTES)
        throw new IOException(
            "RECOVERY_SPACE_INSUFFICIENT: " + storageDetail(RecoveryStoragePlan.usage(fs, files)));
      File staging = new File(capsules, runtimeId + ".pending-" + instanceId);
      if (staging.exists()) throw new IOException("RECOVERY_PREPARATION_PENDING");
      mkdir(staging);
      selected = staging;
      PREPARING.add(staging.getAbsolutePath());
      File candidate = directory(directory(staging, "linux"), "ubuntu");
      JSONArray archives = descriptor.getJSONArray("archives");
      if (archives.length() < 1 || archives.length() > 4)
        throw new IOException("RECOVERY_ARCHIVE_COUNT");
      for (int i = 0; i < archives.length(); i++) {
        check();
        JSONObject archive = archives.getJSONObject(i);
        String name = archive.getString("asset"), expected = archive.getString("sha256");
        String source = archiveSources.get(name);
        if (source == null) throw new IOException("RECOVERY_ARCHIVE_SOURCE_MISSING");
        progress.accept(UiText.format("正在校验应急安装包 (%s/%s)…", i + 1, archives.length()));
        try (InputStream input = context.getAssets().open(source)) {
          verifyDigest(input, expected);
        }
        progress.accept(UiText.format("正在准备应急环境 (%s/%s)…", i + 1, archives.length()));
        try (InputStream input = context.getAssets().open(source)) {
          TarGzipExtractor.extractAuto(new CheckedInput(input), candidate, 0);
        }
      }
      applyBrowserOverlay(candidate);
      verifyRoot(candidate);
      check();
      RecoveryStoragePlan.prepared(fs, staging, runtimeId, instanceId);
      publishCapsule(staging, capsule, capsules);
    } catch (JSONException error) {
      throw new IOException("RECOVERY_DESCRIPTOR_INVALID", error);
    } finally {
      if (selected != null) PREPARING.remove(selected.getAbsolutePath());
    }
  }

  private void publishCapsule(File staging, File capsule, File capsules) throws IOException {
    RecoveryStoragePlan.publish(
        new com.deepseekharness.app.backup.AndroidBackupFileSystem(),
        capsules,
        staging,
        runtimeId,
        instanceId);
  }

  private static File privateFiles(Context context) throws IOException {
    String data = context.getApplicationInfo().dataDir;
    if (data == null) throw new IOException("RECOVERY_PRIVATE_AUTHORITY");
    return com.deepseekharness.app.util.ColdInstallPackages.bindPrivateFiles(
            new com.deepseekharness.app.backup.AndroidBackupFileSystem(),
            new File(data),
            context.getFilesDir())
        .files;
  }

  public static Map<String, Object> storageUsage(Context context) throws IOException {
    return RecoveryStoragePlan.usage(
        new com.deepseekharness.app.backup.AndroidBackupFileSystem(), privateFiles(context));
  }

  private static String storageDetail(Map<String, Object> usage) {
    java.util.function.Function<Object, String> size =
        value ->
            value instanceof Number
                ? com.deepseekharness.app.util.Fmt.bytes(((Number) value).longValue())
                : UiText.choose("未知", "unknown");
    return UiText.format(
        "已保留的应急运行时约 %s；应急会话约 %s。原件保留，未自动删除。",
        size.apply(usage.get("recovery-capsules")), size.apply(usage.get("recovery-sessions")));
  }

  /** 存储映射独立于运行时身份；相同归档在 APK 只存一份，解压目标仍完全独立。 */
  private Map<String, String> readArchiveSources() throws IOException {
    try {
      JSONObject locations =
          new JSONObject(
              new String(asset("recovery-asset-locations.json", 8192), StandardCharsets.UTF_8));
      if (locations.getInt("schema") != 1 || !runtimeId.equals(locations.getString("runtimeId")))
        throw new IOException("RECOVERY_ARCHIVE_SOURCE_ID");
      JSONArray pinned = descriptor.getJSONArray("archives"),
          mapped = locations.getJSONArray("archives");
      if (pinned.length() != 2 || mapped.length() != pinned.length())
        throw new IOException("RECOVERY_ARCHIVE_SOURCE_COUNT");
      Map<String, String> hashes = new HashMap<>(), sources = new HashMap<>();
      for (int i = 0; i < pinned.length(); i++) {
        JSONObject row = pinned.getJSONObject(i);
        if (hashes.put(row.getString("asset"), row.getString("sha256")) != null)
          throw new IOException("RECOVERY_ARCHIVE_SOURCE_DUPLICATE");
      }
      for (int i = 0; i < mapped.length(); i++) {
        JSONObject row = mapped.getJSONObject(i);
        String name = row.getString("asset");
        String source =
            RecoveryArchiveSource.validate(
                name, row.getString("source"), hashes.get(name), row.getString("sha256"));
        if (sources.put(name, source) != null)
          throw new IOException("RECOVERY_ARCHIVE_SOURCE_DUPLICATE");
      }
      if (!sources.keySet().equals(hashes.keySet()))
        throw new IOException("RECOVERY_ARCHIVE_SOURCE_MISSING");
      return Collections.unmodifiableMap(sources);
    } catch (JSONException | IllegalArgumentException error) {
      throw new IOException("RECOVERY_ARCHIVE_SOURCE_INVALID", error);
    }
  }

  private void verifyRoot(File root) throws IOException {
    check();
    rejectLink(root);
    if (!root.getCanonicalFile().equals(root.getAbsoluteFile()))
      throw new IOException("RECOVERY_ROOT_ALIAS");
    if (!root.isDirectory()) throw new IOException("RECOVERY_ROOT_MISSING");
    try {
      JSONArray proof = descriptor.getJSONArray("files");
      if (proof.length() < 6 || proof.length() > 100_000)
        throw new IOException("RECOVERY_FILE_PROOF_MISSING");
      for (int i = 0; i < proof.length(); i++) {
        check();
        JSONObject row = proof.getJSONObject(i);
        File file = inside(root, row.getString("path"));
        if (!file.isFile()) throw new IOException("RECOVERY_FILE_MISSING");
        try (InputStream input = new FileInputStream(file)) {
          verifyDigest(input, row.getString("sha256"));
        }
        if (i % 512 == 0) progress.accept(UiText.format("正在核验应急文件 (%s/%s)…", i, proof.length()));
      }
      JSONArray links = descriptor.optJSONArray("links");
      if (links != null)
        for (int i = 0; i < links.length(); i++) {
          check();
          JSONObject row = links.getJSONObject(i);
          String name = safeRelative(row.getString("path")), target = row.getString("target");
          File link = new File(root, name);
          String actual = Os.readlink(link.getAbsolutePath());
          String expectedPath =
              Compat.normalizePosix(root.getCanonicalPath() + "/" + safeRelative(target));
          if (!expectedPath.equals(resolvedLink(root, link, actual)))
            throw new IOException("RECOVERY_LINK_MISMATCH");
        }
      File dsh = inside(root, "usr/local/lib/node_modules/@deepseek-ai/dsh/package.json");
      JSONObject pkg = new JSONObject(new String(read(dsh, 1024 * 1024), StandardCharsets.UTF_8));
      if (!descriptor.getString("dshVersion").equals(pkg.getString("version")))
        throw new IOException("RECOVERY_VERSION_MISMATCH");
      File bash = inside(root, "bin/bash");
      if ((Os.stat(bash.getAbsolutePath()).st_mode & 0111) == 0)
        throw new IOException("RECOVERY_BASH_MODE");
      inside(root, "usr/local/bin/node");
    } catch (JSONException | ErrnoException error) {
      throw new IOException("RECOVERY_FILE_PROOF_FAILED", error);
    }
  }

  /** Raw locked archive bytes are checked first; signed overlay bytes then become the capsule's proved files. */
  private void applyBrowserOverlay(File root) throws IOException {
    try {
      JSONObject inputHashes = descriptor.getJSONObject("overlayInputs");
      if (inputHashes.length() != OVERLAY_INPUTS.length)
        throw new IOException("RECOVERY_OVERLAY_INPUT_COUNT");
      for (String name : OVERLAY_INPUTS)
        verifyDigest(
            new ByteArrayInputStream(asset(name, 1024 * 1024)), inputHashes.getString(name));
      JSONArray rows = descriptor.getJSONArray("overlays");
      if (rows.length() != OVERLAY_PATHS.length) throw new IOException("RECOVERY_OVERLAY_COUNT");
      for (int i = 0; i < OVERLAY_PATHS.length; i++) {
        check();
        JSONObject row = rows.getJSONObject(i);
        if (!OVERLAY_PATHS[i].equals(row.getString("path"))
            || !OVERLAY_ASSETS[i].equals(row.getString("asset")))
          throw new IOException("RECOVERY_OVERLAY_PATH");
        long length = row.getLong("bytes");
        if (length < 1 || length > 12L * 1024 * 1024)
          throw new IOException("RECOVERY_OVERLAY_SIZE");
        File target = inside(root, OVERLAY_PATHS[i]);
        rejectLink(target);
        if (!target.isFile()) throw new IOException("RECOVERY_OVERLAY_SOURCE_MISSING");
        try (InputStream input = new FileInputStream(target)) {
          verifyDigest(input, row.getString("originalSha256"));
        }
        byte[] patched = asset(OVERLAY_ASSETS[i], (int) length);
        if (patched.length != length) throw new IOException("RECOVERY_OVERLAY_SIZE");
        verifyDigest(new ByteArrayInputStream(patched), row.getString("sha256"));
        writeAtomic(target, patched);
      }
    } catch (JSONException error) {
      throw new IOException("RECOVERY_OVERLAY_INVALID", error);
    }
  }

  private static String resolvedLink(File root, File link, String target) throws IOException {
    String base = root.getCanonicalPath();
    String joined = target.startsWith("/") ? base + target : link.getParent() + "/" + target;
    String resolved = Compat.normalizePosix(joined);
    if (!resolved.equals(base) && !resolved.startsWith(base + "/"))
      throw new IOException("RECOVERY_LINK_ESCAPE");
    return resolved;
  }

  private void verifyNativeLibraries() throws IOException {
    try {
      JSONObject hashes =
          descriptor
              .getJSONObject("launcherHashes")
              .getJSONObject(BuildConfig.LOW_ANDROID ? "low" : "standard");
      for (String required :
          new String[] {
            BuildConfig.LOW_ANDROID ? "libproot_legacy.so" : "libproot.so",
            BuildConfig.LOW_ANDROID ? "libprootloader_legacy.so" : "libprootloader.so",
            "libtalloc.so",
            "libandroidshmem.so"
          }) if (!hashes.has(required)) throw new IOException("RECOVERY_LAUNCHER_PROOF_MISSING");
      Iterator<String> names = hashes.keys();
      while (names.hasNext()) {
        String name = names.next();
        if (!name.matches("lib[a-zA-Z0-9_-]+\\.so"))
          throw new IOException("RECOVERY_LAUNCHER_NAME");
        try (InputStream input =
            new FileInputStream(new File(context.getApplicationInfo().nativeLibraryDir, name))) {
          verifyDigest(input, hashes.getString(name));
        }
      }
    } catch (JSONException error) {
      throw new IOException("RECOVERY_LAUNCHER_PROOF_MISSING", error);
    }
  }

  /** 新鲜 profile 的完整 bundle/补丁由受审查的随包资产提供。 */
  public void prepareProfile() throws IOException {
    check();
    File dshHome = directory(home, ".dsh");
    File profileRoot = directory(directory(dshHome, "profiles"), profile);
    File agent = directory(home, "recovery-agent");
    byte[] source = asset("recovery-agent.js", 1024 * 1024);
    try {
      verifyDigest(new ByteArrayInputStream(source), descriptor.getString("agentSha256"));
      write(new File(agent, "recovery-agent.js"), source);
      // rc2 的 dsh_plugin_packages 在每轮模型请求前读取活动插件的 name/version；
      // 缺少 version 会在 HTTP 前抛 REQUEST_EXTENSION，令空白应急对话无法工作。
      write(
          new File(agent, "package.json"),
          "{\"name\":\"dsha-recovery-agent\",\"version\":\"0.1.0\",\"private\":true,\"type\":\"module\"}\n"
              .getBytes(StandardCharsets.UTF_8));
      String profileJson =
          new String(profileAsset("recovery-profile-package.json"), StandardCharsets.UTF_8)
              .replace("@PROFILE@", profile);
      new JSONObject(profileJson); // 只接受完整、随包且解析成功的 profile。
      write(new File(profileRoot, "package.json"), profileJson.getBytes(StandardCharsets.UTF_8));
      String patch =
          new String(profileAsset("recovery-profile.patch.yml"), StandardCharsets.UTF_8)
              .replace("@PLUGIN_ROOT@", "/root/recovery-agent")
              .replace("@RECOVERY_HOME@", "/root");
      write(new File(profileRoot, "cordis.patch.yml"), patch.getBytes(StandardCharsets.UTF_8));
      write(new File(dshHome, "settings.yaml"), "{}\n".getBytes(StandardCharsets.UTF_8));
      File modules = directory(profileRoot, "node_modules");
      Os.symlink(
          "/root/recovery-agent", new File(modules, "dsha-recovery-agent").getAbsolutePath());
      Os.symlink(
          "/usr/local/lib/node_modules/@deepseek-ai/dsh/node_modules/@deepseek-ai",
          new File(modules, "@deepseek-ai").getAbsolutePath());
      Os.symlink(
          "/usr/local/lib/node_modules/@deepseek-ai/dsh/node_modules",
          new File(agent, "node_modules").getAbsolutePath());
      write(
          new File(profileRoot, "pnpm-workspace.yaml"),
          "packages:\n  - .\nnodeLinker: hoisted\nautoInstallPeers: false\n"
              .getBytes(StandardCharsets.UTF_8));
    } catch (JSONException | ErrnoException error) {
      throw new IOException("RECOVERY_PROFILE_INVALID", error);
    }
  }

  private byte[] profileAsset(String name) throws IOException, JSONException {
    byte[] content = asset(name, 128 * 1024);
    verifyDigest(
        new ByteArrayInputStream(content),
        descriptor.getJSONObject("profileHashes").getString(name));
    return content;
  }

  public Process launch(String apiKey, long generation, RecoveryRepairBroker broker)
      throws IOException {
    check();
    verifyNativeLibraries();
    File tmp = directory(session, "tmp"),
        lib = directory(session, "lib"),
        l2s = directory(rootfs, ".l2s");
    copyNative("libtalloc.so", new File(lib, "libtalloc.so.2"));
    copyNative("libandroidshmem.so", new File(lib, "libandroid-shmem.so"));
    File nativeDir = new File(context.getApplicationInfo().nativeLibraryDir);
    String proot = BuildConfig.LOW_ANDROID ? "libproot_legacy.so" : "libproot.so";
    String loader = BuildConfig.LOW_ANDROID ? "libprootloader_legacy.so" : "libprootloader.so";
    List<String> argv = new ArrayList<>();
    Collections.addAll(
        argv,
        new File(nativeDir, proot).getAbsolutePath(),
        "--link2symlink",
        "-L",
        "--kill-on-exit",
        "-0",
        "--rootfs=" + rootfs.getAbsolutePath(),
        "--cwd=/root");
    for (String path : new String[] {"/dev", "/proc", "/sys", "/system", "/apex"})
      if (new File(path).exists()) bind(argv, path, path);
    bind(argv, "/dev/urandom", "/dev/random");
    bind(argv, "/proc/self/fd", "/dev/fd");
    bind(argv, l2s.getAbsolutePath(), l2s.getAbsolutePath());
    bind(argv, home.getAbsolutePath(), "/root");
    bind(argv, tmp.getAbsolutePath(), "/tmp");
    String command =
        "umask 077; [ ! -e /root/.recovery-stop ] || exit 0; "
            + "printf '%s\\n' $$ > /root/.recovery-pid; IFS= read -r DSHA_STAT < /proc/$$/stat; "
            + "DSHA_FIELDS=${DSHA_STAT##*) }; set -- $DSHA_FIELDS; printf '%s %s\\n' $$ ${20} > /root/.recovery-identity; "
            + "exec /usr/local/bin/node --expose-internals /usr/local/lib/node_modules/@deepseek-ai/dsh/lib/bin.js --profile "
            + ShellQuote.arg(profile)
            + " --no-open --host 127.0.0.1 --port 0";
    Collections.addAll(argv, "/bin/bash", "-c", command);
    // launcher 自报出生身份后才 exec；应用在任意一条 Java 指令间被回收也留下可恢复身份。
    StringBuilder host =
        new StringBuilder(
                "umask 077; IFS= read -r DSHA_STAT < /proc/$$/stat || exit 125; "
                    + "DSHA_FIELDS=${DSHA_STAT##*) }; set -- $DSHA_FIELDS; printf '%s %s\\n' $$ ${20} > ")
            .append(ShellQuote.arg(new File(session, "launcher.identity").getAbsolutePath()))
            .append(" || exit 125; [ ! -e ")
            .append(ShellQuote.arg(new File(home, ".recovery-stop").getAbsolutePath()))
            .append(" ] || exit 0; exec ");
    for (String argument : argv) host.append(ShellQuote.arg(argument)).append(' ');
    ProcessBuilder builder =
        new ProcessBuilder("/system/bin/sh", "-c", host.toString()).redirectErrorStream(true);
    Compat.redirectStdinDevNull(builder);
    Map<String, String> env = builder.environment();
    env.clear();
    env.put("PATH", "/usr/local/bin:/usr/bin:/bin");
    env.put("HOME", "/root");
    env.put("DSH_HOME", "/root/.dsh");
    env.put("LANG", "C.UTF-8");
    env.put("TMPDIR", "/tmp");
    env.put("BROWSER", "true");
    env.put("DSH_CONFIRM", "1");
    env.put("PROOT_LOADER", new File(nativeDir, loader).getAbsolutePath());
    env.put("PROOT_NO_SECCOMP", "1");
    env.put("PROOT_TMP_DIR", tmp.getAbsolutePath());
    env.put("PROOT_L2S_DIR", l2s.getAbsolutePath());
    env.put("LD_LIBRARY_PATH", lib.getAbsolutePath() + ":" + nativeDir.getAbsolutePath());
    env.put("NARB_DISABLE_NATIVE_CACHE", "1");
    env.put("DSHA_ANDROID_RUNTIME", "1");
    env.put("DSHA_NATIVE_PLUGIN_MANAGER", "1");
    env.put("DSHA_RECOVERY_INSTANCE_ID", instanceId);
    env.put("DSHA_RECOVERY_GENERATION", Long.toString(generation));
    env.put("DSHA_RECOVERY_BROKER_PORT", Integer.toString(broker.port()));
    env.put("DSHA_RECOVERY_BROKER_TOKEN", broker.token());
    if (apiKey != null && !apiKey.trim().isEmpty()) env.put("DEEPSEEK_API_KEY", apiKey.trim());
    writeAtomic(new File(files, "recovery-active"), instanceId.getBytes(StandardCharsets.US_ASCII));
    write(new File(session, "launched"), instanceId.getBytes(StandardCharsets.US_ASCII));
    try {
      return builder.start();
    } catch (IOException failure) {
      // start 未返回 Process 时 Java 未创建可运行的目标；本地记录不能把它误当活进程。
      markClosed(session, instanceId);
      throw failure;
    }
  }

  /** 主线程进程句柄已经交给控制器后登记，失败仍由控制器保留句柄并停止。 */
  public void recordLauncher(Process process) throws IOException {
    File record = new File(session, "launcher.identity");
    rejectLink(record);
    long deadline = android.os.SystemClock.elapsedRealtime() + 2500;
    String saved = "";
    while (!ProcessTermination.exited(process)
        && android.os.SystemClock.elapsedRealtime() < deadline) {
      if (record.isFile()) saved = new String(read(record, 100), StandardCharsets.US_ASCII).trim();
      if (saved.matches("[1-9][0-9]* [1-9][0-9]*")) break;
      try {
        Thread.sleep(25);
      } catch (InterruptedException error) {
        Thread.currentThread().interrupt();
        throw new InterruptedIOException("RECOVERY_CANCELLED");
      }
    }
    if (!saved.matches("[1-9][0-9]* [1-9][0-9]*"))
      throw new IOException("RECOVERY_LAUNCHER_IDENTITY_UNCONFIRMED");
    int pid = com.deepseekharness.app.util.WebProcSel.parsePid(saved.split(" ")[0]);
    WebPidIdentity identity = inspect(pid);
    String parentStat;
    try {
      parentStat =
          new String(read(new File("/proc/" + pid + "/stat"), 8192), StandardCharsets.UTF_8);
    } catch (IOException unreadable) {
      throw new IdentityUnavailable(unreadable);
    }
    ProcessIdentity owned = ProcessIdentity.fromStat(parentStat, pid, android.os.Process.myPid());
    if (identity == null
        || identity.exited()
        || !identity.matches(saved)
        || owned == null
        || identity.started != owned.started
        || ProcessTermination.exited(process))
      throw new IOException("RECOVERY_LAUNCHER_IDENTITY_UNCONFIRMED");
  }

  /** 应用被系统回收后的旧应急会话仍是独立写者；核验退出前不复用其运行时树。 */
  public static void recoverInterrupted(Context context, BooleanSupplier cancelled)
      throws IOException {
    File files = privateFiles(context),
        sessions = new File(files, "recovery-sessions"),
        active = new File(files, "recovery-active");
    rejectLink(sessions);
    rejectLink(active);
    if (!exists(active)) return;
    String id = new String(read(active, 100), StandardCharsets.US_ASCII).trim();
    if (id.isEmpty()) return;
    if (!RuntimeInstanceRegistry.validId(id))
      throw new IOException("RECOVERY_ACTIVE_RECORD_INVALID");
    File entry = new File(sessions, id);
    if (cancelled.getAsBoolean()) throw new InterruptedIOException("RECOVERY_CANCELLED");
    rejectLink(entry);
    if (!entry.isDirectory()) throw new IOException("RECOVERY_ACTIVE_SESSION_MISSING");
    File launched = new File(entry, "launched"), closed = new File(entry, "closed");
    rejectLink(launched);
    rejectLink(closed);
    if (!exists(launched)) {
      retireActive(entry, id);
      return;
    }
    if (exists(closed)) {
      if (!entry.getName().equals(new String(read(closed, 100), StandardCharsets.US_ASCII)))
        throw new IOException("RECOVERY_CLOSED_RECORD_INVALID");
      retireActive(entry, id);
      return;
    }
    if (!entry.getName().equals(new String(read(launched, 100), StandardCharsets.US_ASCII)))
      throw new IOException("RECOVERY_LAUNCH_RECORD_INVALID");
    try {
      JSONObject intent =
          new JSONObject(
              new String(read(new File(entry, "intent.json"), 8192), StandardCharsets.UTF_8));
      if (!entry.getName().equals(intent.getString("instanceId")))
        throw new IOException("RECOVERY_INTENT_INVALID");
    } catch (JSONException error) {
      throw new IOException("RECOVERY_INTENT_INVALID", error);
    }
    File home = new File(entry, "home");
    rejectLink(home);
    if (!stopRecorded(entry, home, entry.getName(), null))
      throw new IOException("RECOVERY_PREVIOUS_PROCESS_UNCONFIRMED");
  }

  private void copyNative(String name, File output) throws IOException {
    File input = new File(context.getApplicationInfo().nativeLibraryDir, name);
    try (InputStream stream = new CheckedInput(new FileInputStream(input))) {
      write(output, bytes(stream, 8 * 1024 * 1024));
    }
  }

  public WebPidIdentity verifiedIdentity() throws IOException {
    File pid = new File(home, ".recovery-pid"), identity = new File(home, ".recovery-identity");
    rejectLink(pid);
    rejectLink(identity);
    String saved = new String(read(identity, 100), StandardCharsets.US_ASCII).trim();
    int number =
        com.deepseekharness.app.util.WebProcSel.parsePid(
            new String(read(pid, 40), StandardCharsets.US_ASCII));
    WebPidIdentity actual = inspect(number);
    if (actual == null
        || actual.exited()
        || !actual.matches(saved)
        || !RuntimeInstanceRegistry.matchesCommand(command(number), profile))
      throw new IOException("RECOVERY_PROCESS_IDENTITY_UNCONFIRMED");
    return actual;
  }

  public String command(int pid) throws IOException {
    try {
      return new String(read(new File("/proc/" + pid + "/cmdline"), 65536), StandardCharsets.UTF_8);
    } catch (IOException unreadable) {
      if (inspect(pid) == null) throw new IOException("RECOVERY_PROCESS_EXITED", unreadable);
      throw new IdentityUnavailable(unreadable);
    }
  }

  /** 只向本轮保存且两次出生身份一致的 guest 发 SIGTERM；不终止 proot 启动器。 */
  public boolean stop(Process process) throws IOException {
    return stopRecorded(session, home, instanceId, process);
  }

  private static boolean stopRecorded(File session, File home, String instanceId, Process process)
      throws IOException {
    write(new File(home, ".recovery-stop"), instanceId.getBytes(StandardCharsets.US_ASCII));
    String profile = "dsha-emergency-" + instanceId;
    File identity = new File(home, ".recovery-identity");
    rejectLink(identity);
    long identityDeadline = android.os.SystemClock.elapsedRealtime() + 1800;
    while (!exists(identity)
        && !launcherExited(session, process)
        && android.os.SystemClock.elapsedRealtime() < identityDeadline) {
      try {
        Thread.sleep(50);
      } catch (InterruptedException error) {
        Thread.currentThread().interrupt();
        return false;
      }
    }
    if (!exists(identity)) {
      if (launcherExited(session, process)) {
        markClosed(session, instanceId);
        return true;
      }
      return false;
    }
    String saved = new String(read(identity, 100), StandardCharsets.US_ASCII).trim();
    String[] fields = saved.split(" ");
    if (fields.length != 2) throw new IOException("RECOVERY_PROCESS_IDENTITY_UNCONFIRMED");
    int pid = com.deepseekharness.app.util.WebProcSel.parsePid(fields[0]);
    // OEM 可能拒绝宿主 SIGTERM。先让签名插件读取固定哨兵，主动正常退出。
    long cooperativeDeadline = android.os.SystemClock.elapsedRealtime() + 1800;
    while (android.os.SystemClock.elapsedRealtime() < cooperativeDeadline) {
      WebPidIdentity cooperative = inspect(pid);
      if ((cooperative == null || cooperative.exited() || !cooperative.matches(saved))
          && launcherExited(session, process)) {
        markClosed(session, instanceId);
        return true;
      }
      try {
        Thread.sleep(100);
      } catch (InterruptedException error) {
        Thread.currentThread().interrupt();
        return false;
      }
    }
    WebPidIdentity actual = inspect(pid);
    if (actual != null && !actual.exited() && actual.matches(saved)) {
      String command =
          new String(read(new File("/proc/" + pid + "/cmdline"), 65536), StandardCharsets.UTF_8);
      if (!RuntimeInstanceRegistry.matchesCommand(command, profile))
        throw new IOException("RECOVERY_PROCESS_IDENTITY_UNCONFIRMED");
      WebPidIdentity again = inspect(pid);
      if (again == null || !actual.sameProcess(again))
        throw new IOException("RECOVERY_PROCESS_IDENTITY_CHANGED");
      try {
        Os.kill(pid, OsConstants.SIGTERM);
      } catch (ErrnoException error) {
        if (error.errno != OsConstants.ESRCH) throw new IOException("RECOVERY_STOP_DENIED", error);
      }
    }
    long deadline = android.os.SystemClock.elapsedRealtime() + 5000;
    do {
      actual = inspect(pid);
      boolean guestGone = actual == null || actual.exited() || !actual.matches(saved);
      if (guestGone && launcherExited(session, process)) {
        markClosed(session, instanceId);
        return true;
      }
      try {
        Thread.sleep(100);
      } catch (InterruptedException error) {
        Thread.currentThread().interrupt();
        return false;
      }
    } while (android.os.SystemClock.elapsedRealtime() < deadline);
    return false;
  }

  private static void markClosed(File session, String id) throws IOException {
    write(new File(session, "closed"), id.getBytes(StandardCharsets.US_ASCII));
    retireActive(session, id);
  }

  private static void retireActive(File session, String id) throws IOException {
    File active = new File(session.getParentFile().getParentFile(), "recovery-active");
    rejectLink(active);
    if (exists(active)
        && id.equals(new String(read(active, 100), StandardCharsets.US_ASCII).trim()))
      writeAtomic(active, new byte[0]);
  }

  private static boolean launcherExited(File session, Process process) throws IOException {
    if (process != null) return ProcessTermination.exited(process);
    File record = new File(session, "launcher.identity");
    rejectLink(record);
    if (!exists(record)) {
      // launcher 必须先写身份，再读停止哨兵，最后才 exec proot。
      // 身份尚不存在时，已写好的哨兵保证后来获得调度的旧 launcher 也不能开始 guest。
      File stop = new File(session, "home/.recovery-stop");
      rejectLink(stop);
      return session
          .getName()
          .equals(new String(read(stop, 100), StandardCharsets.US_ASCII).trim());
    }
    String saved = new String(read(record, 100), StandardCharsets.US_ASCII).trim();
    if (!saved.matches("[1-9][0-9]* [1-9][0-9]*"))
      throw new IOException("RECOVERY_LAUNCHER_IDENTITY_UNCONFIRMED");
    int pid = com.deepseekharness.app.util.WebProcSel.parsePid(saved.split(" ")[0]);
    WebPidIdentity current = inspect(pid);
    return current == null || current.exited() || !current.matches(saved);
  }

  private static WebPidIdentity inspect(int pid) throws IOException {
    if (pid < 2) throw new IOException("RECOVERY_PROCESS_IDENTITY_INVALID");
    File stat = new File("/proc/" + pid + "/stat");
    try {
      if (Os.stat(stat.getAbsolutePath()).st_uid != android.os.Process.myUid()) return null;
      String content;
      try {
        content = new String(read(stat, 8192), StandardCharsets.UTF_8);
      } catch (IOException unreadable) {
        throw new IdentityUnavailable(unreadable);
      }
      WebPidIdentity result = WebPidIdentity.parse(content, pid);
      if (result == null) throw new IOException("RECOVERY_PROCESS_IDENTITY_UNCONFIRMED");
      return result;
    } catch (ErrnoException error) {
      if (error.errno == OsConstants.ENOENT || error.errno == OsConstants.ESRCH) return null;
      throw new IdentityUnavailable(error);
    }
  }

  public void recordOutcome(String state, String detail) throws IOException {
    String safe = com.deepseekharness.app.util.SensitiveData.redact(detail == null ? "" : detail);
    if (safe.length() > 8192) safe = safe.substring(safe.length() - 8192);
    JSONObject outcome =
        new JSONObject(
            Map.of(
                "schema",
                1,
                "instanceId",
                instanceId,
                "runtimeId",
                runtimeId,
                "state",
                state,
                "detail",
                safe,
                "updatedAt",
                System.currentTimeMillis()));
    write(
        new File(session, "outcome.json"),
        (outcome.toString() + "\n").getBytes(StandardCharsets.UTF_8));
  }

  private static void bind(List<String> argv, String source, String target) {
    argv.add("-b");
    argv.add(source + ":" + target);
  }

  private void check() throws IOException {
    if (cancelled.getAsBoolean() || Thread.currentThread().isInterrupted())
      throw new InterruptedIOException("RECOVERY_CANCELLED");
  }

  private byte[] asset(String name, int limit) throws IOException {
    try (InputStream input = context.getAssets().open(name)) {
      return bytes(input, limit);
    }
  }

  private static byte[] read(File file, int limit) throws IOException {
    try (InputStream input = new FileInputStream(file)) {
      return bytes(input, limit);
    }
  }

  private static byte[] bytes(InputStream input, int limit) throws IOException {
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    byte[] bytes = new byte[8192];
    int n;
    while ((n = input.read(bytes)) >= 0) {
      if (out.size() > limit - n) throw new IOException("RECOVERY_INPUT_LIMIT");
      out.write(bytes, 0, n);
    }
    return out.toByteArray();
  }

  private void verifyDigest(InputStream input, String expected) throws IOException {
    if (!expected.matches("[a-f0-9]{64}")) throw new IOException("RECOVERY_DIGEST_INVALID");
    try {
      MessageDigest digest = MessageDigest.getInstance("SHA-256");
      byte[] bytes = new byte[131072];
      int n;
      while ((n = input.read(bytes)) >= 0) {
        check();
        digest.update(bytes, 0, n);
      }
      StringBuilder actual = new StringBuilder();
      for (byte b : digest.digest()) actual.append(String.format(Locale.ROOT, "%02x", b & 255));
      if (!expected.contentEquals(actual)) throw new IOException("RECOVERY_ASSET_MISMATCH");
    } catch (java.security.NoSuchAlgorithmException impossible) {
      throw new IOException(impossible);
    }
  }

  private static String safeRelative(String path) throws IOException {
    if (path == null
        || path.isEmpty()
        || path.startsWith("/")
        || path.contains("\\")
        || path.contains("\0")) throw new IOException("RECOVERY_PATH_INVALID");
    for (String component : path.split("/", -1))
      if (component.isEmpty() || component.equals(".") || component.equals(".."))
        throw new IOException("RECOVERY_PATH_INVALID");
    return path;
  }

  private static File inside(File root, String path) throws IOException {
    File file = new File(root, safeRelative(path));
    if (!file.getCanonicalPath().startsWith(root.getCanonicalPath() + File.separator))
      throw new IOException("RECOVERY_PATH_ESCAPE");
    return file;
  }

  private static void rejectLink(File file) throws IOException {
    if (Compat.isSymbolicLink(file)) throw new IOException("RECOVERY_LINK_REJECTED");
  }

  private static boolean exists(File file) throws IOException {
    try {
      Os.lstat(file.getAbsolutePath());
      return true;
    } catch (ErrnoException error) {
      if (error.errno == OsConstants.ENOENT) return false;
      throw new IOException("RECOVERY_RECORD_INSPECTION_DENIED", error);
    }
  }

  private static void mkdir(File file) throws IOException {
    var fs = new com.deepseekharness.app.backup.AndroidBackupFileSystem();
    var node = fs.stat(file);
    if (node.type.equals("MISSING")) fs.directory(file);
    else if (!node.type.equals("DIRECTORY"))
      throw new IOException("RECOVERY_DIRECTORY_UNAVAILABLE");
  }

  private static File directory(File parent, String name) throws IOException {
    rejectLink(parent);
    File child = new File(parent, name);
    mkdir(child);
    return child;
  }

  private static void write(File file, byte[] content) throws IOException {
    var fs = new com.deepseekharness.app.backup.AndroidBackupFileSystem();
    fs.atomic(file.getAbsoluteFile().getParentFile(), file.getName(), content);
  }

  private static void writeAtomic(File file, byte[] content) throws IOException {
    write(file, content);
  }

  private final class CheckedInput extends FilterInputStream {
    CheckedInput(InputStream input) {
      super(input);
    }

    @Override
    public int read() throws IOException {
      check();
      return super.read();
    }

    @Override
    public int read(byte[] bytes, int offset, int length) throws IOException {
      check();
      return in.read(bytes, offset, length);
    }
  }
}
