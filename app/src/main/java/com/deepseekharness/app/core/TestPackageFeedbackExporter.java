package com.deepseekharness.app.core;

import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.os.SystemClock;
import androidx.core.content.FileProvider;
import com.deepseekharness.app.BuildConfig;
import com.deepseekharness.app.backup.AndroidBackupFileSystem;
import com.deepseekharness.app.backup.BackupControl;
import com.deepseekharness.app.backup.BackupJson;
import com.deepseekharness.app.backup.DiagnosticExportArchive;
import com.deepseekharness.app.backup.DocumentStreams;
import com.deepseekharness.app.backup.HostMaintenancePending;
import com.deepseekharness.app.backup.UserDataLayout;
import com.deepseekharness.app.util.BoundedByteTail;
import com.deepseekharness.app.util.Compat;
import com.deepseekharness.app.util.FeedbackCapture;
import com.deepseekharness.app.util.FileIntegrity;
import com.deepseekharness.app.util.Ids;
import com.deepseekharness.app.util.PluginJournalNames;
import com.deepseekharness.app.util.SensitiveData;
import com.deepseekharness.app.util.TextLogTail;
import com.deepseekharness.app.util.UiText;
import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 测试包「反馈与诊断」一键导出：一次性采集本进程 logcat、本应用与 DSH 日志、启动 / 维护 /
 * 运行时健康 / 插件事务记录与设备环境信息，有界脱敏后写入 tar.gz。
 *
 * <p>只在 {@code BuildConfig.TEST_PACKAGE} 构建里提供入口；正式包不显示、不采集。采集全部在本地
 * 完成，不自动上传、不读取 IMEI / 序列号 / 账号 / API key，也不抓全量 logcat（只按自己的 pid）。
 * 单文件 ≤256 KB、总量 ≤8 MB、最多 48 条；任何来源失败只降低完整度，不阻断导出。
 */
public final class TestPackageFeedbackExporter {
  private static final int LOGCAT_MAX_BYTES = FeedbackCapture.MAX_ENTRY_BYTES;
  private static final int FILE_TAIL_BYTES = 256 * 1024;
  private static final int SMALL_TAIL_BYTES = 64 * 1024;
  private static final long LOGCAT_TIMEOUT_MILLIS = 20_000;
  private static final int MAX_STARTUP_RECORDS = 5;
  private static final int MAX_MEND_RECORDS = 3;
  private static final int MAX_HEALTH_RECORDS = 3;
  private static final int MAX_PLUGIN_JOURNALS = 3;
  private static final long STAGED_MAX_AGE_MILLIS = 24L * 60 * 60 * 1000;
  private static final DiagnosticExportArchive.Limits LIMITS =
      new DiagnosticExportArchive.Limits(
          FeedbackCapture.MAX_ENTRIES,
          FeedbackCapture.MAX_ENTRY_BYTES,
          FeedbackCapture.MAX_TOTAL_BYTES);

  private TestPackageFeedbackExporter() {}

  public static String filename() {
    return "DSHA-feedback-"
        + new java.text.SimpleDateFormat("yyyyMMdd-HHmmss", java.util.Locale.ROOT)
            .format(new java.util.Date())
        + "-"
        + java.util.UUID.randomUUID()
        + ".tar.gz";
  }

  /**
   * 采集并生成私有缓存里的归档文件。调用方负责在 SAF 写完后删除；分享时文件保留在 cache，
   * 由系统缓存回收。任何单个来源失败都会写进 manifest 的 capture 段，不抛给用户。
   */
  public static File stage(Context context) throws IOException {
    long capturedAt = System.currentTimeMillis();
    FeedbackCapture capture = new FeedbackCapture();
    List<Map<String, Object>> logcat = new ArrayList<>(1);
    collectDevice(context, capture, capturedAt);
    collectLogcat(capture, logcat);
    collectPrivateFiles(context, capture);
    collectGuestFiles(context, capture);
    capture.addText(
        "README",
        "README.txt",
        "DSHA 测试包反馈与诊断导出\n"
            + "这不是数据备份，也没有自动上传；文件由本机生成，是否发送由你决定。\n\n"
            + "包含：本进程 logcat（仅自己的 pid）、本应用与 DSH 日志尾部、最近启动记录、"
            + "维护与运行时健康记录、插件安装事务记录、设备与运行环境信息。\n"
            + "边界：单文件 ≤256 KB、总量 ≤8 MB、最多 48 条；超出时保留最近输出；"
            + "所有文本经 SensitiveData 规则脱敏（API key / token / 私钥 / Authorization 等）。\n"
            + "不包含：IMEI、序列号、账号、API key、全量 logcat、业务正文的主动采集。\n"
            + "注意：自由格式日志仍可能包含个人信息或业务内容，脱敏规则不可能覆盖所有自定义格式；"
            + "请检查内容后再决定是否分享。manifest.json 记录每条文件的大小与 sha256。\n");
    Map<String, Object> status = capture.status(capturedAt);
    Map<String, Object> captureExtra = new LinkedHashMap<>();
    captureExtra.put("logcatPidOnly", true);
    captureExtra.put("fullLogcat", false);
    captureExtra.put("deviceIdentifiers", "NOT_COLLECTED");
    captureExtra.put("redaction", "SensitiveData.RULES");
    captureExtra.put("logcat", new ArrayList<>(logcat));
    status.put("testPackageDiagnostics", captureExtra);
    // 缓存目录也按 canonical 形态创建，避免 staged 文件带 /data/user/0 系统别名。
    File folder = new File(context.getCacheDir().getCanonicalFile(), "diagnostic-exports");
    if (!folder.isDirectory() && !folder.mkdirs())
      throw new IOException("FEEDBACK_EXPORT_DIRECTORY");
    pruneStaged(folder);
    File archive = new File(folder, java.util.UUID.randomUUID() + ".tar.gz");
    try (OutputStream output = new BufferedOutputStream(new FileOutputStream(archive), 65536)) {
      DiagnosticExportArchive.write(
          output, capture.entries(), status, new BackupControl(null), LIMITS);
    }
    return archive;
  }

  /** 把已生成的归档写入用户选择的 SAF 位置，并回读校验字节。 */
  public static void writeTo(Context context, Uri destination, File staged) throws IOException {
    if (destination == null || staged == null) throw new IOException("FEEDBACK_EXPORT_DESTINATION");
    // Android 16 的 getCacheDir() 返回 /data/user/0 别名（系统符号链接，指向 /data/data）。
    // AndroidBackupFileSystem 逐段 NOFOLLOW 打开，会把合法别名判成 PARENT_LINK；读取前先规范化。
    // 只规范化本进程刚生成的缓存归档，用户文件与 guest 路径的防逃逸检查保持不变。
    File source = staged.getCanonicalFile();
    AndroidBackupFileSystem fs = new AndroidBackupFileSystem();
    var identity = fs.stat(source);
    if (!"FILE".equals(identity.type)) throw new IOException("FEEDBACK_EXPORT_STAGED");
    BackupControl control = new BackupControl(null);
    FileIntegrity.Result written;
    try (InputStream input = fs.read(source, identity);
        OutputStream output =
            DocumentStreams.output(context.getContentResolver(), destination, control)) {
      if (output == null) throw new IOException(UiText.text("无法写入所选位置"));
      written = FileIntegrity.copy(input, output, identity.size, control::check);
    }
    try (InputStream verify =
        DocumentStreams.input(context.getContentResolver(), destination, control)) {
      if (!written.matches(FileIntegrity.copy(verify, null, identity.size, control::check)))
        throw new IOException(UiText.text("保存后的反馈日志校验失败"));
    }
  }

  /** 本地分享入口：只把本次生成的缓存归档交给用户选择的系统分享目标。 */
  public static Intent shareIntent(Context context, File staged) {
    Uri uri = FileProvider.getUriForFile(context, context.getPackageName() + ".updates", staged);
    Intent send =
        new Intent(Intent.ACTION_SEND)
            .setType("application/gzip")
            .putExtra(Intent.EXTRA_STREAM, uri)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
    return Intent.createChooser(send, UiText.text("分享反馈日志"));
  }

  // ---------- 设备 / 环境 ----------

  private static void collectDevice(Context context, FeedbackCapture capture, long capturedAt) {
    Map<String, Object> value = new LinkedHashMap<>();
    value.put("schema", 1L);
    value.put("capturedAtMillis", capturedAt);
    Map<String, Object> pkg = new LinkedHashMap<>();
    pkg.put("applicationId", context.getPackageName());
    pkg.put("versionCode", (long) BuildConfig.VERSION_CODE);
    pkg.put("versionName", BuildConfig.VERSION_NAME);
    pkg.put("testPackage", BuildConfig.TEST_PACKAGE);
    pkg.put("exporter", "TEST_PACKAGE_FEEDBACK");
    value.put("package", pkg);
    Map<String, Object> build = new LinkedHashMap<>();
    build.put("manufacturer", Build.MANUFACTURER);
    build.put("brand", Build.BRAND);
    build.put("model", Build.MODEL);
    build.put("device", Build.DEVICE);
    build.put("product", Build.PRODUCT);
    build.put("display", Build.DISPLAY);
    build.put("androidRelease", Build.VERSION.RELEASE);
    build.put("sdk", (long) Build.VERSION.SDK_INT);
    build.put("securityPatch", Build.VERSION.SECURITY_PATCH);
    List<String> abis = new ArrayList<>();
    for (String abi : Build.SUPPORTED_ABIS) abis.add(abi);
    build.put("abis", abis);
    value.put("build", build);
    value.put("locale", currentLocale(context));
    Map<String, Object> process = new LinkedHashMap<>();
    process.put("pid", (long) android.os.Process.myPid());
    process.put("uptimeSeconds", SystemClock.elapsedRealtime() / 1000);
    value.put("process", process);
    value.put("runtime", runtimeStatus(context, capture));
    value.put("startup", startupStatus(context));
    value.put("maintenance", maintenanceStatus(context.getFilesDir()));
    value.put("recovery", recoveryStatus(context));
    try {
      capture.addBytes("device", "device.json", BackupJson.write(value, SMALL_TAIL_BYTES));
    } catch (IOException | RuntimeException unavailable) {
      capture.fail("device", stableError(unavailable));
    }
  }

  private static String currentLocale(Context context) {
    try {
      return context.getResources().getConfiguration().getLocales().get(0).toString();
    } catch (RuntimeException unavailable) {
      return java.util.Locale.getDefault().toString();
    }
  }

  /** 已装运行时的 descriptor 优先；环境未就绪时回退到随包资产，并如实标注来源。 */
  private static Map<String, Object> runtimeStatus(Context context, FeedbackCapture capture) {
    Map<String, Object> value = new LinkedHashMap<>();
    String configured = new ConfigStore(context).isProroot() ? "proroot" : "proot";
    value.put("configured", configured);
    Map<String, Object> descriptor = null;
    String source = "UNAVAILABLE";
    try {
      File installed =
          new File(new File(context.getFilesDir(), "linux"), ".runtime-descriptor.json");
      if (installed.isFile() && !java.nio.file.Files.isSymbolicLink(installed.toPath())) {
        descriptor =
            BackupJson.read(
                TextLogTail.read(installed, 2 * 1024 * 1024).getBytes(StandardCharsets.UTF_8),
                2 * 1024 * 1024);
        source = "INSTALLED";
      }
    } catch (IOException | RuntimeException unavailable) {
      value.put("installedDescriptorError", stableError(unavailable));
    }
    if (descriptor == null) {
      try {
        byte[] asset;
        try (InputStream input = context.getAssets().open("runtime-descriptor.json")) {
          asset = readFully(input, 2 * 1024 * 1024);
        }
        descriptor = BackupJson.read(asset, 2 * 1024 * 1024);
        source = "BUNDLED";
      } catch (IOException | RuntimeException unavailable) {
        capture.fail("runtime-descriptor", stableError(unavailable));
      }
    }
    value.put("descriptorSource", source);
    if (descriptor != null) {
      value.put("runtimeId", stringValue(descriptor.get("runtimeId")));
      value.put("dshVersion", stringValue(descriptor.get("dshVersion")));
      value.put("baseVersion", stringValue(descriptor.get("baseVersion")));
    }
    return value;
  }

  private static Map<String, Object> startupStatus(Context context) {
    Map<String, Object> value = new LinkedHashMap<>();
    try {
      var snapshot = HarnessController.get(context).startupDiagnostics().snapshot();
      value.put("stage", SensitiveData.redact(snapshot.stage));
      value.put("browserReady", snapshot.browserReady);
      value.put("backendReady", snapshot.backendReady);
      value.put("safe", snapshot.safe);
      value.put("ended", snapshot.ended);
      value.put("issueCount", (long) snapshot.issues.size());
    } catch (RuntimeException unavailable) {
      value.put("error", stableError(unavailable));
    }
    return value;
  }

  private static Map<String, Object> maintenanceStatus(File files) {
    Map<String, Object> value = new LinkedHashMap<>();
    try {
      List<Map<String, Object>> checks = new ArrayList<>();
      for (HostMaintenancePending.Check check : HostMaintenancePending.inspectAll(files)) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("domain", check.domain);
        row.put("operation", check.operation);
        row.put("state", check.state.name());
        row.put("code", check.code);
        if (!check.error.isEmpty()) row.put("error", SensitiveData.redact(check.error));
        checks.add(row);
      }
      value.put("checks", checks);
    } catch (RuntimeException unavailable) {
      value.put("error", stableError(unavailable));
    }
    return value;
  }

  private static Map<String, Object> recoveryStatus(Context context) {
    Map<String, Object> value = new LinkedHashMap<>();
    try {
      var recovery = com.deepseekharness.app.recovery.RecoveryController.get(context).snapshot();
      value.put("state", recovery.state);
      value.put("generation", recovery.generation);
      value.put("errorCode", SensitiveData.redact(recovery.errorCode));
    } catch (RuntimeException unavailable) {
      value.put("error", stableError(unavailable));
    }
    return value;
  }

  // ---------- logcat（只读自己的 pid） ----------

  private static void collectLogcat(FeedbackCapture capture, List<Map<String, Object>> status) {
    Map<String, Object> row = new LinkedHashMap<>();
    row.put("scope", "SELF_PID_ONLY");
    row.put("pid", (long) android.os.Process.myPid());
    int exit = -1;
    BoundedByteTail tail = new BoundedByteTail(LOGCAT_MAX_BYTES);
    String failure = "";
    Process process = null;
    Thread killer = null;
    try {
      process =
          new ProcessBuilder(
                  "logcat",
                  "-d",
                  "--pid=" + android.os.Process.myPid(),
                  "-v",
                  "threadtime",
                  "-t",
                  "4000")
              .redirectErrorStream(true)
              .start();
      final Process running = process;
      AtomicBoolean killed = new AtomicBoolean();
      killer =
          new Thread(
              () -> {
                try {
                  TimeUnit.MILLISECONDS.sleep(LOGCAT_TIMEOUT_MILLIS);
                  if (running.isAlive()) {
                    killed.set(true);
                    Compat.destroy(running);
                  }
                } catch (InterruptedException stopped) {
                  // 正常结束时由 finally 中断，不升级为超时。
                }
              },
              "feedback-logcat-timeout");
      killer.setDaemon(true);
      killer.start();
      try (InputStream input = process.getInputStream()) {
        byte[] buffer = new byte[8192];
        int read;
        while ((read = input.read(buffer)) > 0) tail.write(buffer, 0, read);
      }
      if (!process.waitFor(10, TimeUnit.SECONDS)) {
        Compat.destroy(process);
        failure = "TIMEOUT";
      } else {
        exit = process.exitValue();
        if (killed.get()) failure = "TIMEOUT";
      }
    } catch (IOException | InterruptedException error) {
      failure = stableError(error);
    } finally {
      if (killer != null) killer.interrupt();
      if (process != null && process.isAlive()) Compat.destroy(process);
    }
    row.put("exitCode", (long) exit);
    row.put("truncated", tail.truncated());
    row.put("bytes", (long) tail.size());
    if (failure.isEmpty() && exit != 0) failure = "EXIT_" + exit;
    if (!failure.isEmpty()) {
      row.put("failure", failure);
      capture.fail("logcat", failure);
      // 失败时 logcat 本身降级为空；保留一小段错误输出（通常就是 Permission denied）供定位。
      if (tail.size() > 0) capture.addText("logcat", "logcat-error.txt", tail.text());
    } else if (tail.size() > 0) {
      capture.addText("logcat", "logcat.txt", tail.text());
    }
    status.add(row);
  }

  // ---------- 本应用私有记录 ----------

  private static void collectPrivateFiles(Context context, FeedbackCapture capture) {
    File files = context.getFilesDir();
    capture.addFile(
        "last-startup-failure",
        "last-startup-failure.log",
        new File(files, "last-startup-failure.log"),
        files,
        FILE_TAIL_BYTES);
    for (String name :
        List.of("diagnostic-events.txt", "diagnostic-events.1.txt", "cold-install-diagnostics.txt"))
      capture.addFile(name, name, new File(files, name), files, FILE_TAIL_BYTES);
    addRecentFiles(
        capture,
        "startup-history",
        new File(files, "startup-history"),
        files,
        MAX_STARTUP_RECORDS,
        SMALL_TAIL_BYTES,
        name -> name.endsWith(".properties") || name.endsWith(".properties.previous"));
    addMaintenanceRecords(capture, new File(files, "maintenance"));
    addRecentFiles(
        capture,
        "runtime-health",
        new File(files, "runtime-health"),
        files,
        MAX_HEALTH_RECORDS,
        SMALL_TAIL_BYTES,
        name -> name.endsWith(".json"));
  }

  private static void addMaintenanceRecords(FeedbackCapture capture, File home) {
    List<File> directories = recent(home, MAX_MEND_RECORDS, File::isDirectory);
    for (File directory : directories) {
      String id = directory.getName();
      if (java.nio.file.Files.isSymbolicLink(directory.toPath()) || !Ids.uuid(id)) continue;
      StringBuilder text = new StringBuilder();
      for (String marker :
          List.of(
              "intent.properties",
              "verified",
              "data-preserved",
              "committed",
              "rolled-back",
              "cleaned")) {
        File file = new File(directory, marker);
        try {
          if (!file.isFile() || java.nio.file.Files.isSymbolicLink(file.toPath())) continue;
          text.append("# ").append(marker).append('\n');
          text.append(TextLogTail.read(file, SMALL_TAIL_BYTES));
          text.append('\n');
        } catch (IOException | RuntimeException unreadable) {
          text.append("# ").append(marker).append(" UNREADABLE\n");
        }
      }
      if (text.length() > 0)
        capture.addText("maintenance", "maintenance/" + id + ".txt", text.toString());
    }
  }

  private static void addRecentFiles(
      FeedbackCapture capture,
      String source,
      File directory,
      File base,
      int count,
      int maxBytes,
      java.util.function.Predicate<String> accepted) {
    for (File file : recent(directory, count, File::isFile)) {
      String name = file.getName();
      if (!accepted.test(name)) continue;
      String archiveName = source + "/" + name;
      capture.addFile(source, archiveName, file, base, maxBytes);
    }
  }

  private static List<File> recent(
      File directory, int count, java.util.function.Predicate<File> accepted) {
    File[] entries =
        java.nio.file.Files.isSymbolicLink(directory.toPath()) ? null : directory.listFiles();
    if (entries == null) return List.of();
    List<File> matched = new ArrayList<>();
    for (File entry : entries) if (entry != null && accepted.test(entry)) matched.add(entry);
    matched.sort(
        Comparator.comparingLong(File::lastModified).reversed().thenComparing(File::getName));
    return matched.size() <= count ? matched : new ArrayList<>(matched.subList(0, count));
  }

  // ---------- 宿主（DSH / rootfs）记录 ----------

  private static void collectGuestFiles(Context context, FeedbackCapture capture) {
    File rootfs = null;
    try {
      rootfs = HarnessController.get(context).proot().getRootfsDir();
    } catch (RuntimeException unavailable) {
      capture.fail("rootfs", stableError(unavailable));
    }
    if (rootfs != null) {
      File root = new File(rootfs, "root");
      for (String name : List.of("dsh-web.log", "dsh-lan.log"))
        capture.addFile(name, name, new File(root, name), rootfs, FILE_TAIL_BYTES);
    }
    File dshHome = null;
    try {
      // getFilesDir() 同样可能返回 /data/user/0 别名；UserDataLayout 会用 NOFOLLOW 文件系统
      // 读数据位置记录，不规范化会把 dsh-home 采集降级成 PARENT_LINK 失败。
      dshHome =
          new UserDataLayout(
                  new AndroidBackupFileSystem(), context.getFilesDir().getCanonicalFile())
              .current();
    } catch (IOException | RuntimeException unavailable) {
      capture.fail("dsh-home", stableError(unavailable));
    }
    if (dshHome == null) return;
    capture.addFile(
        "repair-builtin",
        "repair-builtin.log",
        new File(dshHome, "repair-builtin.log"),
        dshHome,
        FILE_TAIL_BYTES);
    for (String name : List.of("plugin-activations.json", "plugin-safe-mode.json"))
      capture.addFile("plugin", name, new File(dshHome, name), dshHome, SMALL_TAIL_BYTES);
    File journals = new File(dshHome, "plugin-install-operations");
    capture.addFile(
        "plugin",
        "plugin-install-completed.json",
        new File(dshHome, "plugin-install-completed-v1.json"),
        dshHome,
        FILE_TAIL_BYTES);
    addPluginJournals(capture, journals);
  }

  private static void addPluginJournals(FeedbackCapture capture, File journals) {
    int added = 0;
    for (File directory : recent(journals, MAX_PLUGIN_JOURNALS, File::isDirectory)) {
      String id = directory.getName();
      if (!Ids.uuid(id) || PluginJournalNames.kind(id) != PluginJournalNames.Kind.TRANSACTION)
        continue;
      StringBuilder text = new StringBuilder();
      for (String name : List.of("plan.json", "committed", "rolled-back")) {
        File file = new File(directory, name);
        try {
          if (!file.isFile() || java.nio.file.Files.isSymbolicLink(file.toPath())) continue;
          text.append("# ").append(name).append('\n');
          text.append(TextLogTail.read(file, SMALL_TAIL_BYTES));
          text.append('\n');
        } catch (IOException | RuntimeException unreadable) {
          text.append("# ").append(name).append(" UNREADABLE\n");
        }
      }
      if (text.length() > 0) {
        capture.addText("plugin-journal", "plugin-journal/" + id + ".txt", text.toString());
        added++;
      }
    }
    if (added == 0) capture.fail("plugin-journal", "NO_TRANSACTION_RECORD");
  }

  // ---------- 工具 ----------

  private static void pruneStaged(File folder) {
    File[] entries = folder.listFiles();
    if (entries == null) return;
    long cutoff = System.currentTimeMillis() - STAGED_MAX_AGE_MILLIS;
    for (File entry : entries) {
      if (entry == null || !entry.getName().endsWith(".tar.gz")) continue;
      if (entry.lastModified() < cutoff
          && entry.isFile()
          && !java.nio.file.Files.isSymbolicLink(entry.toPath())) {
        // 只清理本目录里超过一天、确为普通文件的旧缓存归档；失败不影响本次导出。
        entry.delete();
      }
    }
  }

  private static byte[] readFully(InputStream input, int limit) throws IOException {
    java.io.ByteArrayOutputStream output = new java.io.ByteArrayOutputStream();
    byte[] buffer = new byte[8192];
    int read;
    while ((read = input.read(buffer)) > 0) {
      if (output.size() + read > limit) throw new IOException("STREAM_LIMIT");
      output.write(buffer, 0, read);
    }
    return output.toByteArray();
  }

  private static String stableError(Throwable error) {
    String message = error == null ? "" : error.getMessage();
    return SensitiveData.redact(
        (error == null ? "UNKNOWN" : error.getClass().getSimpleName())
            + (message == null || message.isEmpty() ? "" : ":" + message));
  }

  private static String stringValue(Object value) {
    return value instanceof String ? (String) value : "";
  }
}
