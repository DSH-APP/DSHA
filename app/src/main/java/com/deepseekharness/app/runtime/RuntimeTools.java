package com.deepseekharness.app.runtime;

import android.content.Context;
import com.deepseekharness.app.util.Compat;
import com.deepseekharness.app.util.GhHostRoot;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.Arrays;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/** 统一准备插件与终端的证书和命令入口，无需先手动运行安装第 2 步。 */
final class RuntimeTools {
  static final String CERT_PATH = "/usr/local/share/dsha/ca-certificates.crt";
  private static final String MANAGED_MARKER = "usr/local/share/dsha/managed-assets-v2";

  /** 跨进程的受管准备完成戳：只证明受管文件集合已由当前 APK 写入，不授权跳过内容核验。 */
  private static final String PREPARED_PROOF = ".runtime-tools-prepared";

  /** 覆盖安装代次；guest 内置插件默认禁用策略用它区分「本安装内显式启用」与旧版本状态。 */
  private static final String APK_GENERATION = "usr/local/share/dsha/apk-generation";

  private static final Object LOCK = new Object();

  /** Host mirror 字节契约在 rc5 与 0.2.2-rc.1 之间不变，但代次门仍须精确识别。 */
  private static final Map<String, String> LAN_SETTINGS_CONTRACT_VERSIONS =
      Map.of("0.2.1-rc.5", "0.2.1-rc.5", "0.2.2-rc.1", "0.2.1-rc.5");

  private static String preparedRoot;
  private static String preparedApk;
  private static String preparedStamp;
  private static String preparedInvalidation = "not-prepared";
  private static boolean preparedWithNpm;
  private static final java.util.Set<File> preparedFiles = new java.util.LinkedHashSet<>();

  static void prepare(Context context, File rootfs) throws IOException {
    prepare(context, rootfs, true);
  }

  static void stage(Context context, File rootfs) throws IOException {
    prepare(context, rootfs, false);
  }

  private static void prepare(Context context, File rootfs, boolean requireNpm) throws IOException {
    RuntimeToolsTiming timing = new RuntimeToolsTiming();
    try {
      GuestScriptBudget.checkpoint();
      rootfs = RuntimeAssetFiles.root(context, rootfs);
      timing.mark(0);
      try (RuntimeHostPorts.Scope scope =
          RuntimeHostPorts.fromOwner(context.getApplicationContext()).open()) {
        // 设备 DNS 查询与网络回调注册都可能跨 Binder；不能在资产准备 monitor 内调用系统服务。
        ResolverRefresher.start(context);
        NetworkEpochPublisher.start(context);
        timing.mark(1);
        java.util.List<String> deviceDns = deviceDns(context);
        timing.mark(2);
        RuntimeHostPorts.Settings settings =
            RuntimeHostPorts.fromOwner(context.getApplicationContext()).settings();
        synchronized (LOCK) {
          timing.mark(3);
          GuestScriptBudget.checkpoint();
          // 预配置工具归档不运行旧 install-ubuntu-tools 的 mkdir /tmp；普通启动也修复缺失目录。
          RuntimeTemporaryDirectories.prepare(
              new com.deepseekharness.app.backup.AndroidBackupFileSystem(), rootfs);
          try {
            prepareResolver(context, rootfs, settings, deviceDns);
          } catch (IOException error) {
            android.util.Log.w("DSHA", "DNS configuration unchanged", error);
          }
          timing.mark(4);
          File apk = new File(context.getPackageCodePath());
          String identity = apk.getPath() + ":" + apk.length() + ":" + apk.lastModified();
          String root = rootfs.getCanonicalPath();
          File installedDescriptor = new File(rootfs.getParentFile(), ".runtime-descriptor.json");
          if (requireNpm
              && installedDescriptor.isFile()
              && !com.deepseekharness.app.util.MaintenanceGate.shared().isOwner()) {
            // 已登记运行时的主体只能由维护事务切换。身份完全一致时仍允许修复 APK 自有
            // 脚本/内置插件覆盖层，解决旧版提前 return 后长期沿用旧文件的问题。
            String expected = assetRuntimeId(context);
            if (expected.equals(descriptorRuntimeId(installedDescriptor))) {
              String miss = cacheMiss(rootfs, root, identity, true);
              timing.mark(5);
              if (miss == null) return;
              if (reusePreparedProof(context, rootfs, root, identity)) {
                timing.mark(6);
                return;
              }
              reportCacheMiss(miss);
              preparedInvalidation = "preparation-incomplete";
              preparedStamp = null;
              prepareManagedOverlay(context, rootfs, expected);
              writeApkGeneration(context, rootfs);
              preparedFiles.add(installedDescriptor);
              preparedFiles.add(new File(rootfs, MANAGED_MARKER));
              preparedRoot = root;
              preparedApk = identity;
              preparedWithNpm = true;
              preparedStamp = stamp(rootfs);
              persistPreparedProof(context, rootfs, identity);
              timing.mark(6);
            }
            return;
          }
          String miss = cacheMiss(rootfs, root, identity, requireNpm);
          timing.mark(5);
          if (miss == null) return;
          if (requireNpm && reusePreparedProof(context, rootfs, root, identity)) {
            timing.mark(6);
            return;
          }
          reportCacheMiss(miss);
          preparedInvalidation = "preparation-incomplete";
          preparedStamp = null;
          preparedFiles.clear();
          String managedIdentity = assetRuntimeId(context);
          installManagedAssets(context, rootfs);
          preparePackageCommands(context, rootfs, requireNpm);
          applyPatchChain(context, rootfs);
          writeIfChanged(
              context,
              new File(rootfs, MANAGED_MARKER),
              com.deepseekharness.app.util.ManagedAssetVersion.bytes(managedIdentity),
              false);
          writeApkGeneration(context, rootfs);
          preparedRoot = root;
          preparedApk = identity;
          preparedWithNpm = requireNpm;
          preparedStamp = stamp(rootfs);
          if (requireNpm) persistPreparedProof(context, rootfs, identity);
          timing.mark(6);
        }
      }
    } finally {
      timing.finish(line -> android.util.Log.i("DSHA", line));
    }
  }

  /** The Web host and every child process need these before any profile/package command. */
  static void preparePackageCommands(Context context, File rootfs, boolean requireNpm)
      throws IOException {
    RootfsInstaller.pnpm(context, rootfs);
    for (String path :
        new String[] {
          "usr/local/lib/dsha-pnpm/bin/pnpm.cjs",
          "usr/local/lib/dsha-pnpm/dist/pnpm.cjs",
          "usr/local/lib/dsha-pnpm/package.json",
          "root/.dsha-pnpm-version",
          "root/dsh-bin/pnpm"
        }) preparedFiles.add(new File(rootfs, path));
    for (String command : new String[] {"npm", "npx"}) {
      File cli = new File(rootfs, "usr/local/lib/node_modules/npm/bin/" + command + "-cli.js");
      if (requireNpm && !cli.isFile())
        throw new IOException(
            com.deepseekharness.app.util.UiText.format("内置 npm 文件缺失：%s-cli.js", command));
      if (requireNpm) preparedFiles.add(cli);
      File wrapper = new File(rootfs, "root/dsh-bin/" + command);
      writeIfChanged(
          context,
          wrapper,
          ("#!/bin/sh\nexec /usr/local/bin/node /usr/local/lib/node_modules/npm/bin/"
                  + command
                  + "-cli.js \"$@\"\n")
              .getBytes(java.nio.charset.StandardCharsets.UTF_8),
          true);
    }
    File dsh = new File(rootfs, "root/dsh-bin/dsh");
    writeIfChanged(
        context,
        dsh,
        com.deepseekharness.app.util.DshEntrypoint.wrapper()
            .getBytes(java.nio.charset.StandardCharsets.UTF_8),
        true);
  }

  static void invalidate() {
    synchronized (LOCK) {
      preparedInvalidation = "explicit-repair";
      preparedStamp = null;
    }
  }

  /** 供注册预算读取当前 APK 的受管资产身份；未知时返回 null（按首启预算）。 */
  static String managedAssetId(Context context) {
    try {
      return assetRuntimeId(context);
    } catch (IOException unavailable) {
      return null;
    }
  }

  /** 上一次成功注册写下的身份；按受管根映射后读取，未知时返回 null（按首启预算）。 */
  static String lastRegisteredAssetId(Context context, File requestedRoot) {
    try {
      return GuestPluginScripts.readRegisterIdentity(
          RuntimeAssetFiles.root(context, requestedRoot));
    } catch (IOException | RuntimeException unavailable) {
      return null;
    }
  }

  /**
   * 受管覆盖层的跨进程完成戳：只有戳记里的 APK 身份一致、已登记运行时身份一致，
   * 且同一文件集合的元数据戳仍完全相等时才复用；任何不一致都退回完整准备。
   */
  private static boolean reusePreparedProof(
      Context context, File rootfs, String root, String apkIdentity) {
    try {
      File target = preparedProofFile(rootfs);
      if (!target.isFile()
          || Compat.isSymbolicLink(target)
          || target.length() <= 0
          || target.length() > com.deepseekharness.app.util.RuntimePreparedProof.MAX_BYTES)
        return false;
      byte[] bytes = java.nio.file.Files.readAllBytes(target.toPath());
      com.deepseekharness.app.util.RuntimePreparedProof.Proof proof =
          com.deepseekharness.app.util.RuntimePreparedProof.parse(
              new String(bytes, java.nio.charset.StandardCharsets.UTF_8));
      if (proof == null || !proof.apk.equals(apkIdentity)) return false;
      File descriptor = new File(rootfs.getParentFile(), ".runtime-descriptor.json");
      if (!descriptor.isFile() || !assetRuntimeId(context).equals(descriptorRuntimeId(descriptor)))
        return false;
      java.util.List<File> files = new java.util.ArrayList<>(proof.files.size());
      for (String relative : proof.files) files.add(new File(rootfs, relative));
      RuntimeAssetStamp.Snapshot observed = RuntimeAssetStamp.inspect(rootfs, files);
      if (observed.value == null || !observed.value.equals(proof.stamp)) return false;
      preparedRoot = root;
      preparedApk = apkIdentity;
      preparedWithNpm = true;
      preparedFiles.clear();
      preparedFiles.addAll(files);
      preparedStamp = proof.stamp;
      android.util.Log.i("DSHA", "RUNTIME_TOOLS_PREPARED_PROOF_HIT entries=" + files.size());
      return true;
    } catch (IOException | RuntimeException unavailable) {
      return false;
    }
  }

  /** 只记录 rootfs 内的固定受管文件；外部描述符身份由下一次启动单独复核。 */
  private static void persistPreparedProof(Context context, File rootfs, String apkIdentity) {
    try {
      String root = rootfs.getCanonicalPath();
      String prefix = root + File.separator;
      java.util.LinkedHashSet<String> relative = new java.util.LinkedHashSet<>();
      for (File file : preparedFiles) {
        String path = file.getCanonicalPath();
        if (!path.startsWith(prefix)) continue;
        String value = path.substring(prefix.length()).replace(File.separatorChar, '/');
        if (com.deepseekharness.app.util.RuntimePreparedProof.validRelative(value))
          relative.add(value);
      }
      if (relative.isEmpty()) return;
      java.util.List<File> files = new java.util.ArrayList<>(relative.size());
      for (String value : relative) files.add(new File(rootfs, value));
      String proofStamp = RuntimeAssetStamp.read(rootfs, files);
      if (proofStamp == null) return;
      byte[] bytes =
          com.deepseekharness.app.util.RuntimePreparedProof.format(
                  apkIdentity, proofStamp, new java.util.ArrayList<>(relative))
              .getBytes(java.nio.charset.StandardCharsets.UTF_8);
      RuntimeAssetFiles.write(context, preparedProofFile(rootfs), bytes, false);
    } catch (IOException | RuntimeException unavailable) {
      android.util.Log.w("DSHA", "RUNTIME_TOOLS_PREPARED_PROOF_WRITE");
    }
  }

  private static File preparedProofFile(File rootfs) {
    return new File(rootfs.getParentFile(), PREPARED_PROOF);
  }

  /** Same reuse predicate as preparation; names and indices explain work without authorizing it. */
  private static String cacheMiss(File rootfs, String root, String apk, boolean requireNpm)
      throws IOException {
    if (preparedRoot == null) return "reason=unprepared";
    if (!root.equals(preparedRoot)) return "reason=root";
    if (!apk.equals(preparedApk)) return "reason=apk";
    if (requireNpm && !preparedWithNpm) return "reason=npm";
    if (com.deepseekharness.app.util.MaintenanceGate.shared().isOwner())
      return "reason=maintenance";
    if (preparedStamp == null) return "reason=invalidated source=" + preparedInvalidation;
    RuntimeAssetStamp.Snapshot observed = RuntimeAssetStamp.inspect(rootfs, preparedFiles);
    if (preparedStamp.equals(observed.value)) return null;
    int changed =
        observed.value == null
            ? observed.unavailableEntry
            : RuntimeAssetStamp.firstDifference(preparedStamp, observed.value);
    String result =
        "reason="
            + (observed.value == null ? "unavailable" : "metadata")
            + " entry="
            + changed
            + " entries="
            + preparedFiles.size();
    if (changed >= 0) {
      int at = 0;
      for (File file : preparedFiles)
        if (at++ == changed) {
          // These are the fixed managed inputs, never session, user-plugin or workspace files.
          try {
            String relative =
                rootfs.toPath().relativize(file.toPath()).toString().replace('\\', '/');
            if (relative.matches("[A-Za-z0-9_@./+-]{1,240}")) result += " asset=" + relative;
          } catch (RuntimeException diagnosticUnavailable) {
            // Diagnostic spelling cannot prevent the required full verification.
          }
          break;
        }
    }
    return result;
  }

  private static void reportCacheMiss(String reason) {
    try {
      // Logcat only: writing a durable diagnostic file here would add synchronous IO
      // to the very preparation path being measured.
      android.util.Log.i("DSHA", "RUNTIME_TOOLS_CACHE_MISS " + reason);
    } catch (RuntimeException diagnosticUnavailable) {
      // Diagnostics never authorize reuse or prevent the full verification.
    }
  }

  /** Read-only query readiness; never prepares, repairs, migrates or registers anything. */
  static boolean queryReady(Context context, File requested) {
    String failure = queryFailure(context, requested);
    reportQueryReadiness(context, failure);
    return failure == null;
  }

  private static final String[] QUERY_FILES = {
    "usr/bin/python3.12",
    "usr/lib/python3.12/encodings/__init__.py",
    "usr/lib/aarch64-linux-gnu/libsqlite3.so.0",
    "usr/lib/aarch64-linux-gnu/libreadline.so.8",
    "root/.dsh/plugin-manager.py",
    "root/.dsh/register-builtin-plugins.py",
    "root/.dsh/builtin-plugins.json",
    "root/.dsh/plugin-lifecycle.py",
    "root/.dsh/plugin-capabilities.py",
    "root/.dsh/plugin-transactions.py",
    "root/.dsh/plugin-network.py",
    "root/.dsh/plugin-dependencies.py"
  };
  private static final Object QUERY_REPORT_LOCK = new Object();
  private static String lastQueryFailure;

  /** null 表示就绪；否则返回不含文件内容的失败位置与原因码，例如 {@code 路径:MISSING}。 */
  private static String queryFailure(Context context, File requested) {
    String stage = "ROOT";
    try {
      File rootfs = RuntimeAssetFiles.root(context, requested);
      var fs = new com.deepseekharness.app.backup.AndroidBackupFileSystem();
      stage = "DESCRIPTOR";
      File descriptor = new File(rootfs.getParentFile(), ".runtime-descriptor.json");
      var descriptorNode = fs.stat(descriptor);
      if (!descriptorNode.type.equals("FILE")) return "DESCRIPTOR:" + descriptorNode.type;
      String identity = assetRuntimeId(context);
      if (!identity.equals(descriptorRuntimeId(descriptor))) return "DESCRIPTOR:IDENTITY";
      stage = "MARKER";
      File marker = new File(rootfs, MANAGED_MARKER);
      var markerNode = fs.stat(marker);
      if (!markerNode.type.equals("FILE")) return "MARKER:" + markerNode.type;
      byte[] expected = com.deepseekharness.app.util.ManagedAssetVersion.bytes(identity);
      if (!Arrays.equals(expected, fs.small(marker, expected.length + 1))) return "MARKER:IDENTITY";
      for (String path : QUERY_FILES) {
        stage = path;
        // apt/dpkg 或外部 root 会把 soname 换成链接；按容器内实际可见的目标判断，不再只认 lstat 普通文件。
        var file = guestFile(fs, rootfs, path);
        if (!file.regular()) return path + ":" + file.failure;
        // 外部 su root 写入的 0600 或其它 SELinux 标签文件，本 UID 下的 proot 同样读不到。
        if (!file.file.canRead()) return path + ":UNREADABLE";
        if (path.equals("usr/bin/python3.12") && (file.node.mode & 0111) != 0111)
          return path + ":MODE";
      }
      return null;
    } catch (IOException | RuntimeException unavailable) {
      String message = String.valueOf(unavailable.getMessage());
      return stage
          + ":"
          + unavailable.getClass().getSimpleName()
          + ":"
          + message.substring(0, Math.min(160, message.length()));
    }
  }

  /** 只在结论变化时记一条诊断，避免每次自动刷新重复刷屏；记录失败不影响查询结论。 */
  private static void reportQueryReadiness(Context context, String failure) {
    synchronized (QUERY_REPORT_LOCK) {
      if (java.util.Objects.equals(failure, lastQueryFailure)) return;
      lastQueryFailure = failure;
    }
    try {
      RuntimeHostPorts.fromOwner(context.getApplicationContext())
          .record("PLUGIN_QUERY_READINESS", failure == null ? "READY" : "NOT_READY " + failure);
    } catch (RuntimeException unavailable) {
      android.util.Log.w("DSHA", "PLUGIN_QUERY_READINESS " + failure, unavailable);
    }
  }

  /** 查询就绪与 RootfsInstaller 修复共用同一判据：rootfs 内按 guest 语义解析出的普通文件。 */
  static com.deepseekharness.app.util.GuestRegularFile.Result guestFile(
      com.deepseekharness.app.backup.BackupFileSystem fs, File rootfs, String relative)
      throws IOException {
    String root = rootfs.getAbsolutePath();
    return com.deepseekharness.app.util.GuestRegularFile.check(
        fs,
        rootfs,
        relative,
        target ->
            com.deepseekharness.app.backup.GuestDataResolver.hostMirrorRelative(target, root));
  }

  /** Explicit ADB repair writes signed host files, including when guest STABLE uses file binds. */
  static void installDeviceScripts(Context context, File requested) throws IOException {
    synchronized (LOCK) {
      File root = RuntimeAssetFiles.root(context, requested);
      var fs = new com.deepseekharness.app.backup.AndroidBackupFileSystem();
      var host =
          com.deepseekharness.app.util.ColdInstallPackages.bindPrivateFiles(
              fs, new File(context.getApplicationInfo().dataDir), context.getFilesDir());
      if (!fs.child(host.files, "linux/ubuntu").equals(root))
        throw new IOException("ADB_RUNTIME_ROOT");
      File data = new com.deepseekharness.app.backup.UserDataLayout(fs, host.files).current();
      if (!fs.stat(data).type.equals("DIRECTORY")) throw new IOException("ADB_DATA_LOCATION");
      for (String name :
          new String[] {"adb-pair.py", "adb-shell.py", "adb-setup.sh", "device-shell-policy.py"}) {
        byte[] bytes = GuestScripts.read(context.getAssets().open(name));
        if (bytes.length == 0) throw new IOException("ADB_SCRIPT_EMPTY:" + name);
        RuntimeAssetFiles.write(context, new File(root, "root/.dsh/" + name), bytes, true);
      }
      byte[] version = GuestScripts.read(context.getAssets().open("adb-script-version"));
      if (!new String(version, java.nio.charset.StandardCharsets.US_ASCII)
          .trim()
          .matches("[1-9][0-9]{0,8}")) throw new IOException("ADB_SCRIPT_VERSION");
      RuntimeAssetFiles.write(context, new File(root, "root/.dsh/script-version"), version, false);
      RuntimeAssetFiles.write(
          context,
          new File(root, "root/dsh-bin/adb-shell"),
          com.deepseekharness.app.util.DeviceScriptProof.wrapper(
              GuestScripts.read(context.getAssets().open("adb-setup.sh"))),
          true);
      host.verify(fs);
      preparedInvalidation = "adb-scripts";
      preparedStamp = null;
    }
  }

  /** Uses signed bytes and stable file identities, including the installed wrapper's executable mode. */
  static boolean deviceScriptsMatch(Context context, File requested) {
    try {
      File root = RuntimeAssetFiles.root(context, requested);
      var fs = new com.deepseekharness.app.backup.AndroidBackupFileSystem();
      var host =
          com.deepseekharness.app.util.ColdInstallPackages.bindPrivateFiles(
              fs, new File(context.getApplicationInfo().dataDir), context.getFilesDir());
      if (!fs.child(host.files, "linux/ubuntu").equals(root)) return false;
      var layout = new com.deepseekharness.app.backup.UserDataLayout(fs, host.files);
      File data = layout.current();
      if (!fs.stat(data).type.equals("DIRECTORY")) return false;
      for (String name :
          new String[] {
            "adb-pair.py",
            "adb-shell.py",
            "adb-setup.sh",
            "device-shell-policy.py",
            "adb-script-version"
          }) {
        byte[] bytes = GuestScripts.read(context.getAssets().open(name));
        String path = "root/.dsh/" + (name.equals("adb-script-version") ? "script-version" : name);
        if (!matchesSignedFile(fs, fs.child(root, path), bytes, !name.equals("adb-script-version")))
          return false;
      }
      byte[] wrapper =
          com.deepseekharness.app.util.DeviceScriptProof.wrapper(
              GuestScripts.read(context.getAssets().open("adb-setup.sh")));
      boolean matches =
          matchesSignedFile(fs, fs.child(root, "root/dsh-bin/adb-shell"), wrapper, true);
      host.verify(fs);
      return matches;
    } catch (IOException | RuntimeException error) {
      RuntimeHostPorts.fromOwner(context.getApplicationContext())
          .record("ADB_SCRIPT_PROOF_UNAVAILABLE", error.getClass().getSimpleName());
      return false;
    }
  }

  private static boolean matchesSignedFile(
      com.deepseekharness.app.backup.BackupFileSystem fs,
      File file,
      byte[] bytes,
      boolean executable)
      throws IOException {
    var before = fs.stat(file);
    if (!before.type.equals("FILE")
        || before.size != bytes.length
        || executable && (before.mode & 0111) == 0) return false;
    return java.util.Arrays.equals(fs.small(file, bytes.length), bytes)
        && before.same(fs.stat(file));
  }

  /** Load the actual APK AssetManager input and enforce its native startup generation. */
  static org.json.JSONObject readPatchRegistry(Context context) throws IOException {
    try {
      org.json.JSONObject registry = assetObject(context, "runtime-patches.json");
      if (registry.getInt("schema") != 1) throw new IOException("PATCH_REGISTRY_SCHEMA");
      String version = registry.getString("dshVersion");
      if (!com.deepseekharness.app.util.Constants.DSH_VERSION.equals(version))
        throw new IOException(
            "PATCH_REGISTRY_VERSION: APK="
                + com.deepseekharness.app.util.Constants.DSH_VERSION
                + " registry="
                + version);
      return registry;
    } catch (org.json.JSONException error) {
      throw new IOException("PATCH_REGISTRY_FORMAT", error);
    }
  }

  static java.util.Map<
          String, java.util.List<com.deepseekharness.app.util.ManagedPatchChain.Recipe>>
      readPatchChains(Context context, org.json.JSONObject registry, String version)
          throws IOException {
    try {
      java.util.Map<String, java.util.List<com.deepseekharness.app.util.ManagedPatchChain.Recipe>>
          groups = new java.util.LinkedHashMap<>();
      java.util.Set<String> ids = new java.util.HashSet<>();
      var active = registry.getJSONArray("active");
      for (int i = 0; i < active.length(); i++) {
        var entry = active.getJSONObject(i);
        String asset = entry.getString("asset");
        if (!ids.add(asset)) throw new IOException("PATCH_REGISTRY_DUPLICATE");
        var spec = assetObject(context, asset);
        if (!version.equals(spec.getString("dshVersion")))
          throw new IOException(
              "PATCH_REGISTRY_VERSION: recipe="
                  + asset
                  + " expected="
                  + version
                  + " actual="
                  + spec.getString("dshVersion"));
        String module = entry.has("module") ? entry.getString("module") : spec.getString("module");
        if (!com.deepseekharness.app.util.ManagedInstallPath.valid(module))
          throw new IOException("PATCH_MODULE_PATH");
        var patches =
            new java.util.ArrayList<com.deepseekharness.app.util.ManagedPatchChain.Patch>();
        var parts = spec.getJSONArray("patches");
        for (int at = 0; at < parts.length(); at++) {
          var patch = parts.getJSONObject(at);
          String after = patch.getString("after");
          if (patch.has("prependAsset"))
            after = assetText(context, patch.getString("prependAsset")) + "\n" + after;
          patches.add(
              new com.deepseekharness.app.util.ManagedPatchChain.Patch(
                  patch.getString("before"), after));
        }
        var markers = new java.util.ArrayList<String>();
        var values = entry.optJSONArray("markers");
        if (values != null)
          for (int at = 0; at < values.length(); at++) markers.add(values.getString(at));
        var previous =
            new java.util.ArrayList<com.deepseekharness.app.util.ManagedPatchChain.Patch>();
        var predecessors = spec.optJSONArray("previousPatches");
        if (predecessors != null)
          for (int at = 0; at < predecessors.length(); at++) {
            var patch = predecessors.getJSONObject(at);
            previous.add(
                new com.deepseekharness.app.util.ManagedPatchChain.Patch(
                    patch.getString("before"), patch.getString("after")));
          }
        groups
            .computeIfAbsent(module, key -> new java.util.ArrayList<>())
            .add(
                new com.deepseekharness.app.util.ManagedPatchChain.Recipe(
                    asset, patches, markers, previous));
      }
      var retired = registry.getJSONArray("retired");
      for (int i = 0; i < retired.length(); i++)
        if (ids.contains(retired.getJSONObject(i).getString("asset")))
          throw new IOException("PATCH_RETIRED_ACTIVE");
      return groups;
    } catch (org.json.JSONException error) {
      throw new IOException("PATCH_REGISTRY_FORMAT", error);
    }
  }

  /** All preparation paths use this registry; retired recipes are never executed. */
  private static void applyPatchChain(Context context, File rootfs) throws IOException {
    try {
      org.json.JSONObject registry = readPatchRegistry(context);
      File pkg = new File(rootfs, "usr/local/lib/node_modules/@deepseek-ai/dsh/package.json");
      if (!pkg.isFile()) return;
      preparedFiles.add(pkg);
      String version = fileObject(pkg).getString("version");
      String registryVersion = registry.getString("dshVersion");
      if (!version.equals(registryVersion)) {
        RuntimeHostPorts.fromOwner(context.getApplicationContext())
            .record("PATCH_RUNTIME_VERSION_MISMATCH", version);
        return;
      }
      removeLegacyPluginReviewPatches(context, rootfs);
      var groups = readPatchChains(context, registry, version);
      for (var entry : groups.entrySet()) {
        String module = entry.getKey();
        File target =
            new File(rootfs, "usr/local/lib/node_modules/@deepseek-ai/dsh/node_modules/" + module);
        if (!target.isFile()
            || Compat.isSymbolicLink(target)
            || !target.getCanonicalPath().startsWith(rootfs.getCanonicalPath() + File.separator))
          throw new IOException("PATCH_MODULE_UNSAFE");
        preparedFiles.add(target);
        String source = Compat.readAll(target);
        String patched =
            com.deepseekharness.app.util.ManagedPatchChain.apply(
                source,
                entry.getValue(),
                () -> restoreBundledClientModule(context, rootfs, module));
        if (!source.equals(patched))
          writeIfChanged(
              context, target, patched.getBytes(java.nio.charset.StandardCharsets.UTF_8), false);
      }
      patchPdfCompatibility(context, rootfs);
      patchBrowserBootstrap(context, rootfs);
      patchClientCombos(context, rootfs);
      patchLanSettingsPersistence(context, rootfs);
    } catch (org.json.JSONException error) {
      throw new IOException("PATCH_REGISTRY_FORMAT", error);
    }
  }

  private static void prepareManagedOverlay(Context context, File rootfs, String identity)
      throws IOException {
    File marker = new File(rootfs, MANAGED_MARKER);
    String current =
        marker.isFile() && !Compat.isSymbolicLink(marker) ? Compat.readAll(marker) : "";
    // 标记只说明上次完整写入时的身份，不能证明脚本或插件实体后来没有被删改。
    // APK 或固定受管文件的 inode/大小/mtime 变化时重新核对；普通插件命令复用本进程
    // 已准备结果，不重复读取大 JS。维护候选仍完整准备，不遍历第三方插件或个人数据。
    boolean markerCurrent =
        com.deepseekharness.app.util.ManagedAssetVersion.current(current, identity);
    preparedStamp = null;
    preparedFiles.clear();
    installManagedAssets(context, rootfs);
    preparePackageCommands(context, rootfs, true);
    // 覆盖升级保留同一运行时身份时，不能只更新消息兼容层。
    // Web UI 的会话抽屉、预设标题和移动端插件都属于 APK 自有覆盖层，
    // 旧版本在这里提前 return 会让 rootfs 继续使用旧 bundle。
    // 两个补丁本身带有稳定 marker，重复启动时会安全跳过。
    applyPatchChain(context, rootfs);
    prepareBuiltinDependencies(rootfs);
    if (!markerCurrent || !marker.isFile() || Compat.isSymbolicLink(marker))
      writeIfChanged(
          context, marker, com.deepseekharness.app.util.ManagedAssetVersion.bytes(identity), false);
  }

  private static String assetRuntimeId(Context context) throws IOException {
    try {
      String value = assetObject(context, "runtime-descriptor.json").getString("runtimeId");
      if (!com.deepseekharness.app.util.ManagedAssetVersion.validRuntimeId(value))
        throw new IOException("RUNTIME_DESCRIPTOR_ASSET");
      return value;
    } catch (org.json.JSONException error) {
      throw new IOException("RUNTIME_DESCRIPTOR_ASSET", error);
    }
  }

  private static String descriptorRuntimeId(File descriptor) throws IOException {
    try {
      String value = fileObject(descriptor).getString("runtimeId");
      if (!com.deepseekharness.app.util.ManagedAssetVersion.validRuntimeId(value))
        throw new IOException("RUNTIME_DESCRIPTOR_INSTALLED");
      return value;
    } catch (org.json.JSONException error) {
      throw new IOException("RUNTIME_DESCRIPTOR_INSTALLED", error);
    }
  }

  /** 仅覆盖 DSHA 自有脚本和内置实体；用户插件、profile、配置、会话与凭据不在清单内。 */
  private static void installManagedAssets(Context context, File rootfs) throws IOException {
    try {
      org.json.JSONObject manifest = assetObject(context, "managed-runtime-inputs.json");
      if (manifest.getInt("schema") != 1) throw new IOException("MANAGED_INPUT_SCHEMA");
      org.json.JSONArray entries = manifest.getJSONArray("installs");
      // This table is also consumed by the descriptor and Gradle. Validate it in full
      // before installing any bytes, so an invalid signed recipe cannot partly apply.
      java.util.Set<String> targets = new java.util.HashSet<>();
      for (int i = 0; i < entries.length(); i++) {
        org.json.JSONObject entry = entries.getJSONObject(i);
        String asset = entry.getString("asset"), target = entry.getString("target");
        if (!com.deepseekharness.app.util.ManagedInstallPath.valid(asset)
            || !com.deepseekharness.app.util.ManagedInstallPath.valid(target)
            || !targets.add(target)
            || !(entry.get("executable") instanceof Boolean))
          throw new IOException("MANAGED_INPUT_PATH");
        org.json.JSONObject extract = entry.optJSONObject("extract");
        if (extract != null) validateExtract(asset, extract);
      }
      for (int i = 0; i < entries.length(); i++) {
        org.json.JSONObject entry = entries.getJSONObject(i);
        install(
            context,
            rootfs,
            entry.getString("asset"),
            entry.getString("target"),
            entry.getBoolean("executable"),
            entry.optJSONObject("extract"));
      }
      for (String name :
          new String[] {"builtin-plugins.json", "runtime-tools.json", "bridge-token-compat.cjs"})
        install(context, rootfs, name, "root/.dsh/" + name, false);
    } catch (org.json.JSONException error) {
      throw new IOException("MANAGED_INPUT_MANIFEST", error);
    }
  }

  /**
   * Managed release archive: exactly one pinned member with an exact digest, published
   * atomically below the runtime root. The archive bytes are already covered by the signed
   * descriptor; this second check keeps a mismatched member from ever being executed.
   */
  private static void validateExtract(String asset, org.json.JSONObject extract)
      throws IOException {
    String member = extract.optString("member", ""), sha256 = extract.optString("sha256", "");
    if (!asset.endsWith(".tar.gz")
        || !com.deepseekharness.app.util.ManagedInstallPath.valid(member)
        || !sha256.matches("[a-f0-9]{64}")) throw new IOException("MANAGED_INPUT_EXTRACT");
  }

  /**
   * AGP 9.1.1 的资产合并会对 .gz 自动 gunzip 并去掉扩展名，所以受管归档在 APK 内按
   * tools/asset-deployment.json 的 sourceTransforms 改用 .bin 打包名。旧构建入口与
   * Robolectric 夹具仍只有源名，打不开打包名时回退，避免把兼容读法一起删掉。
   */
  private static InputStream openManagedArchive(Context context, String asset) throws IOException {
    if ("gh-cli.tar.gz".equals(asset)) {
      try {
        return context.getAssets().open("gh-cli.bin");
      } catch (IOException missingPackagedName) {
        return context.getAssets().open(asset);
      }
    }
    return context.getAssets().open(asset);
  }

  private static void installExtracted(
      Context context,
      File rootfs,
      String asset,
      String path,
      boolean executable,
      org.json.JSONObject extract)
      throws IOException {
    String member = extract.optString("member", ""), expected = extract.optString("sha256", "");
    validateExtract(asset, extract);
    File staging =
        new File(
            rootfs,
            ".dsha-managed-extract-" + Integer.toHexString((asset + ":" + path).hashCode()));
    if (staging.exists()) deleteTemporary(staging);
    if (!staging.mkdirs()) throw new IOException("MANAGED_INPUT_EXTRACT_STAGE");
    try {
      final boolean[] found = {false};
      try (InputStream input = openManagedArchive(context, asset)) {
        // ArchiveFileBoundary over the app-private filesystem: descendant writes never follow
        // links, and the extracted member is digest-checked before publication. The signed-asset
        // root-anchor variant is Android-only and cannot run in the host Robolectric tests.
        TarGzipExtractor.extractSelected(
            input,
            staging,
            0,
            name -> {
              if (!name.equals(member)) return false;
              found[0] = true;
              return true;
            },
            new com.deepseekharness.app.backup.AndroidBackupFileSystem());
      }
      if (!found[0]) throw new IOException("MANAGED_INPUT_EXTRACT_MEMBER");
      File extracted = new File(staging, member);
      if (!extracted.isFile() || Compat.isSymbolicLink(extracted))
        throw new IOException("MANAGED_INPUT_EXTRACT_TYPE");
      com.deepseekharness.app.util.FileIntegrity.Result proof;
      try (InputStream input = new FileInputStream(extracted)) {
        proof = com.deepseekharness.app.util.FileIntegrity.copy(input, null, 64L * 1024 * 1024);
      }
      if (!proof.sha256.equals(expected)) throw new IOException("MANAGED_INPUT_EXTRACT_DIGEST");
      var fs = new com.deepseekharness.app.backup.AndroidBackupFileSystem();
      fs.parents(rootfs, path);
      File target = fs.child(rootfs, path);
      var current = fs.stat(target);
      if (!current.type.equals("MISSING") && !current.type.equals("FILE"))
        throw new IOException("MANAGED_INPUT_EXTRACT_TARGET");
      try {
        java.nio.file.Files.move(
            extracted.toPath(),
            target.toPath(),
            java.nio.file.StandardCopyOption.ATOMIC_MOVE,
            java.nio.file.StandardCopyOption.REPLACE_EXISTING);
      } catch (java.nio.file.AtomicMoveNotSupportedException unsupported) {
        java.nio.file.Files.move(
            extracted.toPath(), target.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
      }
      fs.mode(target, executable ? 0755 : 0600);
      fs.syncDirectory(target.getParentFile());
      preparedFiles.add(target);
      GuestScriptBudget.checkpoint();
    } finally {
      deleteTemporary(staging);
    }
  }

  /** 局域网代理仍由宿主鉴权；冷安装与候选树必须在计算健康摘要前应用同一设置补丁。 */
  static void patchLanSettingsPersistence(Context context, File rootfs) throws IOException {
    synchronized (LOCK) {
      prepareLanSettingsPersistence(context, rootfs);
    }
  }

  private static void prepareLanSettingsPersistence(Context context, File rootfs)
      throws IOException {
    File module =
        new File(
            rootfs,
            "usr/local/lib/node_modules/@deepseek-ai/dsh/node_modules/"
                + "@deepseek-ai/dsh-client-ui-settings/lib/client.js");
    if (!module.isFile()) throw new IOException("LAN_SETTINGS_MODULE_MISSING");
    if (Compat.isSymbolicLink(module)
        || !module.getCanonicalPath().startsWith(rootfs.getCanonicalPath() + File.separator))
      throw new IOException("LAN_SETTINGS_MODULE_PATH");
    byte[] bytes = Compat.readAllBytes(module);
    String source = new String(bytes, java.nio.charset.StandardCharsets.UTF_8);
    try {
      File pkg = new File(rootfs, "usr/local/lib/node_modules/@deepseek-ai/dsh/package.json");
      String version = fileObject(pkg).getString("version");
      String contractVersion = LAN_SETTINGS_CONTRACT_VERSIONS.get(version);
      if (contractVersion != null) {
        // rc5 与 0.2.2-rc.1 暴露相同 Host mirror 字节；保留签名的精确契约，
        // 但按运行时版本表选择对应的契约族，不能让未知版本复用旧摘要。
        var contract = assetObject(context, "lan-settings-upstream-contract.json");
        if (contract.getInt("schema") != 1
            || !contractVersion.equals(contract.getString("dshVersion"))
            || !"@deepseek-ai/dsh-client-ui-settings/lib/client.js"
                .equals(contract.getString("module")))
          throw new IOException("LAN_SETTINGS_CONTRACT");
        String digest =
            com.deepseekharness.app.util.FileIntegrity.copy(
                    new java.io.ByteArrayInputStream(bytes), null, bytes.length)
                .sha256;
        if (!digest.equals(contract.getString("sourceSha256")))
          throw new IOException("LAN_SETTINGS_SOURCE_MISMATCH");
        var required = contract.getJSONArray("requiredAnchors");
        if (required.length() != 4) throw new IOException("LAN_SETTINGS_CONTRACT");
        for (int i = 0; i < required.length(); i++) {
          String anchor = required.getString(i);
          int position = source.indexOf(anchor);
          if (anchor.isEmpty()
              || position < 0
              || source.indexOf(anchor, position + anchor.length()) >= 0)
            throw new IOException("LAN_SETTINGS_HOST_SEMANTICS");
        }
        var forbidden = contract.getJSONArray("forbiddenAnchors");
        if (forbidden.length() != 2) throw new IOException("LAN_SETTINGS_CONTRACT");
        for (int i = 0; i < forbidden.length(); i++)
          if (source.contains(forbidden.getString(i)))
            throw new IOException("LAN_SETTINGS_HOST_SEMANTICS");
        preparedFiles.add(module);
        return;
      }
      if (!"0.2.0-rc.2".equals(version)) throw new IOException("LAN_SETTINGS_VERSION");
      String patched =
          com.deepseekharness.app.util.ExactTextPatch.apply(
              source,
              "const persistence = ctx.remote.$host.isLoopback ? \"host\" : \"memory\";",
              "const persistence = \"host\"; // DSHA patch: LAN 代理场景强制 host 持久化");
      preparedFiles.add(module);
      if (!source.equals(patched))
        writeIfChanged(
            context, module, patched.getBytes(java.nio.charset.StandardCharsets.UTF_8), false);
    } catch (IllegalArgumentException | org.json.JSONException error) {
      throw new IOException("LAN_SETTINGS_PATCH_MISMATCH", error);
    }
  }

  /** 冷安装与受管候选共用；只准备内置实体依赖，不注册或改写用户 web profile。 */
  static void prepareBuiltinDependencies(File rootfs) throws IOException {
    var fs = new com.deepseekharness.app.backup.AndroidBackupFileSystem();
    File root = rootfs.getCanonicalFile();
    String target = "../../usr/local/lib/node_modules/@deepseek-ai/dsh/node_modules";
    if (!fs.stat(new File(root, "usr/local/lib/node_modules/@deepseek-ai/dsh/node_modules"))
        .type
        .equals("DIRECTORY")) throw new IOException("BUNDLED_MODULES_MISSING");
    var names =
        new java.util.ArrayList<>(com.deepseekharness.app.util.BuiltinPlugins.DEFAULT_BUILTINS);
    names.add("dsh-app-integration");
    for (String name : names) {
      File link =
          fs.child(
              root,
              com.deepseekharness.app.util.BuiltinPlugins.entityDir(name).substring(1)
                  + "/node_modules");
      var node = fs.stat(link);
      if (node.type.equals("LINK") && target.equals(fs.readLink(link))) continue;
      if (node.type.equals("DIRECTORY") && fs.list(link).isEmpty()) fs.delete(link);
      else if (!node.type.equals("MISSING"))
        throw new IOException("BUNDLED_MODULES_CONFLICT:" + name);
      fs.symlink(target, link);
      fs.syncDirectory(link.getParentFile());
    }
  }

  /** 只 stat 固定数量的受管文件；不读取大 JS，不遍历会话、附件和项目依赖。 */
  private static String stamp(File rootfs) throws IOException {
    return RuntimeAssetStamp.read(rootfs, preparedFiles);
  }

  static void prepareResolver(Context context, File rootfs) throws IOException {
    prepareResolver(
        context, rootfs, RuntimeHostPorts.fromOwner(context.getApplicationContext()).settings());
  }

  static void prepareResolver(Context context, File rootfs, RuntimeHostPorts.Settings settings)
      throws IOException {
    ResolverRefresher.start(context);
    NetworkEpochPublisher.start(context);
    prepareResolver(context, rootfs, settings, deviceDns(context));
  }

  private static void prepareResolver(
      Context context,
      File rootfs,
      RuntimeHostPorts.Settings settings,
      java.util.List<String> deviceDns)
      throws IOException {
    try {
      prepareDnsServers(context, rootfs, settings, deviceDns);
    } finally {
      prepareAddressPrecedence(context, rootfs, settings.dnsMode);
      // 启动只是第一次投影；此后切网由 ResolverRefresher 按同一规则持续更新。
      ResolverRefresher.register(context, rootfs, deviceDns);
    }
  }

  /**
   * 切网后的刷新入口：与启动时同一套 reconcile 规则，在资产准备锁内写入，避免与并发准备交错。
   * 设备 DNS 由调用方在锁外取得（跨 Binder）。
   */
  static void refreshResolver(Context context, File rootfs, java.util.List<String> deviceDns)
      throws IOException {
    RuntimeHostPorts.Settings settings =
        RuntimeHostPorts.fromOwner(context.getApplicationContext()).settings();
    synchronized (LOCK) {
      prepareDnsServers(context, rootfs, settings, deviceDns);
    }
  }

  private static void prepareDnsServers(
      Context context,
      File rootfs,
      RuntimeHostPorts.Settings settings,
      java.util.List<String> deviceDns)
      throws IOException {
    File target = new File(rootfs, "etc/resolv.conf");
    if (!target.getParentFile().isDirectory()) return;
    // 特殊文件或外部软链接保持原位；不能跟随 guest 绝对链接写到宿主。
    if (Compat.isSymbolicLink(target) || target.exists() && !target.isFile()) return;
    String old = target.isFile() ? Compat.readAll(target) : "";
    String updated =
        com.deepseekharness.app.util.ResolverConfig.reconcile(old, settings.dnsMode, deviceDns);
    // resolv.conf 随网络变化，不计入受管资产戳：否则每次切网都会让下次启动重做整套准备。
    if (!old.equals(updated)) {
      GuestScriptBudget.checkpoint();
      RuntimeAssetFiles.write(
          context, target, updated.getBytes(java.nio.charset.StandardCharsets.UTF_8), false);
    }
  }

  private static void prepareAddressPrecedence(Context context, File rootfs, String mode)
      throws IOException {
    File target = new File(rootfs, "etc/gai.conf");
    if (!target.getParentFile().isDirectory()) return;
    // Preserve links and special files; never follow guest links into host paths.
    if (Compat.isSymbolicLink(target) || target.exists() && !target.isFile()) return;
    String old = target.isFile() ? Compat.readAll(target) : "";
    String updated = com.deepseekharness.app.util.GaiConfig.reconcile(old, mode);
    if (!old.equals(updated))
      writeIfChanged(
          context, target, updated.getBytes(java.nio.charset.StandardCharsets.UTF_8), false);
  }

  private static java.util.List<String> deviceDns(Context context) {
    // 与网络监听共用字段提取；查询失败返回空列表，保留原文件，不要求启动时联网。
    if (context == null) return new java.util.ArrayList<>();
    return new java.util.ArrayList<>(
        com.deepseekharness.app.device.NetworkStateMonitor.query(context).dnsServers);
  }

  static void applyEnvironment(Context context, File rootfs, Map<String, String> environment) {
    applyEnvironment(
        rootfs,
        environment,
        RuntimeHostPorts.fromOwner(context.getApplicationContext()).settings());
  }

  static void applyEnvironment(
      File rootfs, Map<String, String> environment, RuntimeHostPorts.Settings settings) {
    environment.put("DSHA_NATIVE_PLUGIN_MANAGER", "1");
    // 0.2.0-rc2 maintenance mode: the user explicitly requested an
    // unrestricted repair shell and unlocked plugin dependency installs.
    // The transaction/path checks remain in place, but DSH/pnpm must not
    // silently add confirmation, frozen-lockfile, or lifecycle blocking.
    environment.put("DSHA_PLUGIN_UNLOCKED", "1");
    environment.put("DSHA_ANDROID_RUNTIME", "1");
    // The Go gh binary bypasses proroot path translation. Keep the host root explicit
    // so its config/CA and file arguments resolve to the same private runtime tree.
    // 必须用 canonical 路径：/data/user/0 别名视图在 guest 内不可达，包装脚本的 CA 检查会失败。
    if (rootfs != null) environment.put("DSHA_GH_HOST_ROOT", GhHostRoot.resolve(rootfs));
    environment.put("DSHA_GH_PROXY_SCRIPT", "/usr/local/share/dsha/gh-proxy.mjs");
    environment.put("DSHA_DNS_MODE", settings.dnsMode);
    // DNS 兼容与切网后回收外网连接（net-epoch.cjs，T37）；文件缺失（旧环境）时不加。
    for (String name : new String[] {"net-epoch.cjs", "dns-compat.cjs"}) {
      String preload = "--require=/usr/local/share/dsha/" + name;
      if (!new File(rootfs, "usr/local/share/dsha/" + name).isFile()) continue;
      String previous = environment.getOrDefault("NODE_OPTIONS", "");
      if (!Arrays.asList(previous.split("\\s+")).contains(preload))
        environment.put("NODE_OPTIONS", preload + (previous.isEmpty() ? "" : " " + previous));
    }
    // 原生扩展已在私有运行时中；复制缓存使用 link+unlink，在 link2symlink 下首次变成悬链。
    environment.putIfAbsent("NARB_DISABLE_NATIVE_CACHE", "1");
    for (String key :
        new String[] {"SSL_CERT_FILE", "REQUESTS_CA_BUNDLE", "CURL_CA_BUNDLE", "GIT_SSL_CAINFO"})
      environment.putIfAbsent(key, CERT_PATH);
    environment.putIfAbsent("NODE_EXTRA_CA_CERTS", CERT_PATH);
    environment.putIfAbsent("npm_config_cafile", CERT_PATH);
    environment.putIfAbsent("npm_config_prefix", "/usr/local");
  }

  private static void removeLegacyPluginReviewPatches(Context context, File rootfs)
      throws IOException {
    String[][] modules = {
      {"@deepseek-ai/dsh-plugin-manager/lib/index.js", "DSHA_NATIVE_PLUGIN_POLICY_V1"},
      {"@deepseek-ai/dsh-client-ui-plugin-manager/lib/client.js", "dshaNativeReviewRoute"}
    };
    for (String[] entry : modules) {
      File file =
          new File(rootfs, "usr/local/lib/node_modules/@deepseek-ai/dsh/node_modules/" + entry[0]);
      if (!file.isFile() || !Compat.readAll(file).contains(entry[1])) continue;
      String canonical = restoreBundledClientModule(context, rootfs, entry[0]);
      if (canonical == null)
        throw new IOException("Cannot remove legacy review patch: " + entry[0]);
      writeIfChanged(
          context, file, canonical.getBytes(java.nio.charset.StandardCharsets.UTF_8), false);
    }
  }

  /** 从当前 APK 的分包 dsh 运行时恢复一个受管前端模块；找不到时交回原始补丁错误。 */
  private static String restoreBundledClientModule(Context context, File rootfs, String module)
      throws IOException {
    String relative = "usr/local/lib/node_modules/@deepseek-ai/dsh/node_modules/" + module;
    File target = new File(rootfs, relative);
    String[] candidate = {relative, "./" + relative};
    try (ZipFile apk = new ZipFile(context.getPackageCodePath())) {
      ZipEntry entry = apk.getEntry("assets/dsh-runtime.bin");
      if (entry == null) return null;
      File staging =
          new File(rootfs, ".dsha-managed-module-" + Integer.toHexString(relative.hashCode()));
      if (staging.exists()) deleteTemporary(staging);
      staging.mkdirs();
      final boolean[] found = {false};
      try (InputStream input = apk.getInputStream(entry)) {
        TarGzipExtractor.extractSelected(
            input,
            staging,
            0,
            name -> {
              for (String value : candidate)
                if (value.equals(name)) {
                  found[0] = true;
                  return true;
                }
              return false;
            });
      }
      if (!found[0]) {
        deleteTemporary(staging);
        return null;
      }
      File extracted = new File(staging, relative);
      if (!extracted.isFile() || Compat.isSymbolicLink(extracted)) {
        deleteTemporary(staging);
        return null;
      }
      byte[] bytes = Compat.readAll(extracted).getBytes(java.nio.charset.StandardCharsets.UTF_8);
      if (bytes.length == 0) {
        deleteTemporary(staging);
        return null;
      }
      deleteTemporary(staging);
      return new String(bytes, java.nio.charset.StandardCharsets.UTF_8);
    }
  }

  private static void deleteTemporary(File file) {
    if (file.isDirectory()) {
      File[] children = file.listFiles();
      if (children != null) for (File child : children) deleteTemporary(child);
    }
    //noinspection ResultOfMethodCallIgnored
    file.delete();
  }

  /** 锁定的文件预览模块：网页与独立 PDF Worker 共用兼容实现，旧内核的文件协议只做窄适配。 */
  private static void patchPdfCompatibility(Context context, File rootfs) throws IOException {
    File pkg = new File(rootfs, "usr/local/lib/node_modules/@deepseek-ai/dsh/package.json");
    if (!pkg.isFile()) return;
    try {
      org.json.JSONObject spec = assetObject(context, "pdf-compat-patch.json");
      if (!spec.getString("dshVersion").equals(fileObject(pkg).optString("version"))) return;
      File client =
          new File(
              rootfs,
              "usr/local/lib/node_modules/@deepseek-ai/dsh/node_modules/"
                  + spec.getString("module"));
      if (!client.isFile()
          || Compat.isSymbolicLink(client)
          || !client.getCanonicalPath().startsWith(rootfs.getCanonicalPath() + File.separator))
        throw new IOException(com.deepseekharness.app.util.UiText.text("PDF 兼容适配的模块路径不安全或文件缺失"));
      preparedFiles.add(client);
      // 构建器生成单行 IIFE；保持上游代码的行号，Worker 有独立全局对象，必须单独注入。
      String compatibility = assetText(context, spec.getString("asset")).trim().replace("\n", " ");
      String source = Compat.readAll(client), before = spec.getString("mainBefore");
      String updated =
          com.deepseekharness.app.util.ExactTextPatch.apply(
              source, before, "/* DSHA_PDF_COMPAT_V1 */ " + compatibility + " " + before);
      before = spec.getString("workerBefore");
      updated =
          com.deepseekharness.app.util.ExactTextPatch.apply(
              updated,
              before,
              "new Blob(["
                  + org.json.JSONObject.quote(compatibility + "\n")
                  + ", _dsh_pdf_worker_default,");
      File resource =
          new File(
              rootfs,
              "usr/local/lib/node_modules/@deepseek-ai/dsh/node_modules/"
                  + spec.getString("resourceModule"));
      if (!resource.isFile()
          || Compat.isSymbolicLink(resource)
          || !resource.getCanonicalPath().startsWith(rootfs.getCanonicalPath() + File.separator))
        throw new IOException(com.deepseekharness.app.util.UiText.text("文件资源协议适配的模块路径不安全或文件缺失"));
      preparedFiles.add(resource);
      String registry = Compat.readAll(resource);
      String corrected =
          com.deepseekharness.app.util.ExactTextPatch.apply(
              registry, spec.getString("resourceBefore"), spec.getString("resourceAfter"));
      if (!updated.equals(source))
        writeIfChanged(
            context, client, updated.getBytes(java.nio.charset.StandardCharsets.UTF_8), false);
      if (!corrected.equals(registry))
        writeIfChanged(
            context, resource, corrected.getBytes(java.nio.charset.StandardCharsets.UTF_8), false);
    } catch (org.json.JSONException | IllegalArgumentException error) {
      throw new IOException(
          com.deepseekharness.app.util.UiText.format("PDF 兼容适配未应用，原文件保留：%s", error.getMessage()),
          error);
    }
  }

  private static org.json.JSONObject assetObject(Context context, String name) throws IOException {
    return new org.json.JSONObject(
        com.deepseekharness.app.backup.BackupJson.read(
            assetText(context, name).getBytes(java.nio.charset.StandardCharsets.UTF_8),
            2 * 1024 * 1024));
  }

  private static org.json.JSONObject fileObject(File file) throws IOException {
    var fs = new com.deepseekharness.app.backup.AndroidBackupFileSystem();
    return new org.json.JSONObject(
        com.deepseekharness.app.backup.BackupJson.read(
            fs.small(file, 2 * 1024 * 1024), 2 * 1024 * 1024));
  }

  private static String assetText(Context context, String name) throws IOException {
    int limit = name.equals("web-integration/language.js") ? 8 * 1024 * 1024 : 2 * 1024 * 1024;
    try (InputStream input = context.getAssets().open(name);
        ByteArrayOutputStream out = new ByteArrayOutputStream()) {
      byte[] buffer = new byte[8192];
      int count;
      while ((count = input.read(buffer)) != -1) {
        GuestScriptBudget.checkpoint();
        if (out.size() + count > limit) throw new IOException("RUNTIME_TEXT_ASSET_LIMIT");
        out.write(buffer, 0, count);
      }
      return out.toString("UTF-8").replace("\r\n", "\n");
    }
  }

  /** 类似 1.1.10：浏览器自身收到的页面就含补丁，不依赖厂商的文档起始注入接口。 */
  private static void patchBrowserBootstrap(Context context, File rootfs) throws IOException {
    File pkg = new File(rootfs, "usr/local/lib/node_modules/@deepseek-ai/dsh/package.json");
    if (!pkg.isFile()) return;
    try {
      if (!com.deepseekharness.app.util.Constants.DSH_VERSION.equals(
          fileObject(pkg).optString("version"))) return;
      File index =
          new File(
              rootfs,
              "usr/local/lib/node_modules/@deepseek-ai/dsh/node_modules/@deepseek-ai/dsh-web-frontend/dist/index.html");
      if (!index.isFile()
          || Compat.isSymbolicLink(index)
          || !index.getCanonicalPath().startsWith(rootfs.getCanonicalPath() + File.separator))
        throw new IOException("Browser bootstrap path is invalid");
      preparedFiles.add(index);
      String html = Compat.readAll(index);
      String patched =
          com.deepseekharness.app.util.HtmlBootstrapPatch.apply(
              html,
              assetText(context, "web-integration/es-compat.js")
                  + "\n"
                  + assetText(context, "web-integration/startup.js"));
      if (!html.equals(patched))
        writeIfChanged(
            context, index, patched.getBytes(java.nio.charset.StandardCharsets.UTF_8), false);
    } catch (IllegalArgumentException error) {
      throw new IOException("Browser compatibility bootstrap was not applied", error);
    }
  }

  private static void patchClientCombos(Context context, File rootfs) throws IOException {
    File pkg = new File(rootfs, "usr/local/lib/node_modules/@deepseek-ai/dsh/package.json");
    File module =
        new File(
            rootfs,
            "usr/local/lib/node_modules/@deepseek-ai/dsh/node_modules/@deepseek-ai/dsh-client-modules/lib/index.js");
    if (!pkg.isFile() || !module.isFile()) return;
    try {
      org.json.JSONObject spec = assetObject(context, "client-combo-patch.json");
      if (!spec.getString("dshVersion").equals(fileObject(pkg).optString("version"))) return;
      if (Compat.isSymbolicLink(module)
          || !module.getCanonicalPath().startsWith(rootfs.getCanonicalPath() + File.separator))
        throw new IOException(com.deepseekharness.app.util.UiText.text("网页脚本模块路径不安全，原文件保留"));
      String source = Compat.readAll(module), patched = source;
      org.json.JSONArray patches = spec.getJSONArray("patches");
      for (int i = 0; i < patches.length(); i++) {
        org.json.JSONObject patch = patches.getJSONObject(i);
        patched =
            com.deepseekharness.app.util.ExactTextPatch.apply(
                patched, patch.getString("before"), patch.getString("after"));
      }
      for (String file : new String[] {"package.json", "index.js"})
        install(
            context,
            rootfs,
            "client-combo-cache/" + file,
            "usr/local/lib/node_modules/@deepseek-ai/dsh/node_modules/dsha-client-combo-cache/"
                + file,
            false);
      preparedFiles.add(module);
      if (!source.equals(patched))
        writeIfChanged(
            context, module, patched.getBytes(java.nio.charset.StandardCharsets.UTF_8), false);
    } catch (org.json.JSONException | IllegalArgumentException error) {
      throw new IOException(
          com.deepseekharness.app.util.UiText.format("网页脚本拼接优化未应用，原文件保留：%s", error.getMessage()),
          error);
    }
  }

  private static void install(
      Context context, File rootfs, String asset, String path, boolean executable)
      throws IOException {
    install(context, rootfs, asset, path, executable, null);
  }

  private static void install(
      Context context,
      File rootfs,
      String asset,
      String path,
      boolean executable,
      org.json.JSONObject extract)
      throws IOException {
    if (extract != null) {
      installExtracted(context, rootfs, asset, path, executable, extract);
      return;
    }
    try (InputStream input = context.getAssets().open(asset);
        ByteArrayOutputStream bytes = new ByteArrayOutputStream()) {
      byte[] buffer = new byte[8192];
      int count;
      while ((count = input.read(buffer)) != -1) {
        GuestScriptBudget.checkpoint();
        if (bytes.size() + count > 32 * 1024 * 1024) throw new IOException("RUNTIME_ASSET_LIMIT");
        bytes.write(buffer, 0, count);
      }
      byte[] content = bytes.toByteArray();
      if (asset.endsWith(".sh") || asset.endsWith(".py"))
        content =
            new String(content, java.nio.charset.StandardCharsets.UTF_8)
                .replace("\r\n", "\n")
                .getBytes(java.nio.charset.StandardCharsets.UTF_8);
      writeIfChanged(context, new File(rootfs, path), content, executable);
    }
  }

  private static void writeIfChanged(Context context, File file, byte[] content, boolean executable)
      throws IOException {
    GuestScriptBudget.checkpoint();
    preparedFiles.add(file);
    RuntimeAssetFiles.write(context, file, content, executable);
  }

  /** 每次 APK 覆盖更新更新代次；只重建受管资产的维护不改变代次。 */
  private static void writeApkGeneration(Context context, File rootfs) throws IOException {
    writeIfChanged(
        context,
        new File(rootfs, APK_GENERATION),
        com.deepseekharness.app.util.ApkGeneration.bytes(
            com.deepseekharness.app.BuildConfig.VERSION_CODE),
        false);
  }
}
