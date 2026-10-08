package com.deepseekharness.app.runtime;

import android.content.Context;
import com.deepseekharness.app.util.Compat;
import java.io.ByteArrayOutputStream;
import java.io.File;
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
  private static final Object LOCK = new Object();
  private static String preparedRoot;
  private static String preparedApk;
  private static String preparedStamp;
  private static boolean preparedWithNpm;
  private static final java.util.Set<File> preparedFiles = new java.util.LinkedHashSet<>();

  static void prepare(Context context, File rootfs) throws IOException {
    prepare(context, rootfs, true);
  }

  static void stage(Context context, File rootfs) throws IOException {
    prepare(context, rootfs, false);
  }

  private static void prepare(Context context, File rootfs, boolean requireNpm) throws IOException {
    rootfs = RuntimeAssetFiles.root(context, rootfs);
    try (RuntimeHostPorts.Scope scope =
        RuntimeHostPorts.fromOwner(context.getApplicationContext()).open()) {
      // 设备 DNS 查询可能跨 Binder；不能在资产准备 monitor 内查询系统服务。
      java.util.List<String> deviceDns = deviceDns(context);
      RuntimeHostPorts.Settings settings =
          RuntimeHostPorts.fromOwner(context.getApplicationContext()).settings();
      synchronized (LOCK) {
        // 预配置工具归档不运行旧 install-ubuntu-tools 的 mkdir /tmp；普通启动也修复缺失目录。
        RuntimeTemporaryDirectories.prepare(
            new com.deepseekharness.app.backup.AndroidBackupFileSystem(), rootfs);
        try {
          prepareResolver(context, rootfs, settings, deviceDns);
        } catch (IOException error) {
          android.util.Log.w("DSHA", "DNS configuration unchanged", error);
        }
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
            if (root.equals(preparedRoot)
                && identity.equals(preparedApk)
                && preparedWithNpm
                && preparedStamp != null
                && preparedStamp.equals(stamp(rootfs))) return;
            preparedStamp = null;
            prepareManagedOverlay(context, rootfs, expected);
            preparedFiles.add(installedDescriptor);
            preparedFiles.add(new File(rootfs, MANAGED_MARKER));
            preparedRoot = root;
            preparedApk = identity;
            preparedWithNpm = true;
            preparedStamp = stamp(rootfs);
          }
          return;
        }
        if (root.equals(preparedRoot)
            && identity.equals(preparedApk)
            && (!requireNpm || preparedWithNpm)
            && !com.deepseekharness.app.util.MaintenanceGate.shared().isOwner()
            && preparedStamp != null
            && preparedStamp.equals(stamp(rootfs))) return;
        preparedStamp = null;
        preparedFiles.clear();
        String managedIdentity = assetRuntimeId(context);
        installManagedAssets(context, rootfs);
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
        applyPatchChain(context, rootfs);
        writeIfChanged(
            context,
            new File(rootfs, MANAGED_MARKER),
            com.deepseekharness.app.util.ManagedAssetVersion.bytes(managedIdentity),
            false);
        preparedRoot = root;
        preparedApk = identity;
        preparedWithNpm = requireNpm;
        preparedStamp = stamp(rootfs);
      }
    }
  }

  static void invalidate() {
    synchronized (LOCK) {
      preparedStamp = null;
    }
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

  /** All preparation paths use this registry; retired recipes are never executed. */
  private static void applyPatchChain(Context context, File rootfs) throws IOException {
    try {
      org.json.JSONObject registry = assetObject(context, "runtime-patches.json");
      if (registry.getInt("schema") != 1) throw new IOException("PATCH_REGISTRY_SCHEMA");
      File pkg = new File(rootfs, "usr/local/lib/node_modules/@deepseek-ai/dsh/package.json");
      if (!pkg.isFile()) return;
      preparedFiles.add(pkg);
      String version = fileObject(pkg).getString("version");
      if (!com.deepseekharness.app.util.Constants.DSH_VERSION.equals(
          registry.getString("dshVersion"))) throw new IOException("PATCH_REGISTRY_VERSION");
      if (!version.equals(registry.getString("dshVersion"))) {
        RuntimeHostPorts.fromOwner(context.getApplicationContext())
            .record("PATCH_RUNTIME_VERSION_MISMATCH", version);
        return;
      }
      removeLegacyPluginReviewPatches(context, rootfs);
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
          throw new IOException("PATCH_REGISTRY_VERSION");
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
        groups
            .computeIfAbsent(module, key -> new java.util.ArrayList<>())
            .add(
                new com.deepseekharness.app.util.ManagedPatchChain.Recipe(asset, patches, markers));
      }
      var retired = registry.getJSONArray("retired");
      for (int i = 0; i < retired.length(); i++)
        if (ids.contains(retired.getJSONObject(i).getString("asset")))
          throw new IOException("PATCH_RETIRED_ACTIVE");
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
      }
      for (int i = 0; i < entries.length(); i++) {
        org.json.JSONObject entry = entries.getJSONObject(i);
        install(
            context,
            rootfs,
            entry.getString("asset"),
            entry.getString("target"),
            entry.getBoolean("executable"));
      }
      for (String name :
          new String[] {"builtin-plugins.json", "runtime-tools.json", "bridge-token-compat.cjs"})
        install(context, rootfs, name, "root/.dsh/" + name, false);
    } catch (org.json.JSONException error) {
      throw new IOException("MANAGED_INPUT_MANIFEST", error);
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
    String source = Compat.readAll(module);
    try {
      String patched =
          com.deepseekharness.app.util.ExactTextPatch.apply(
              source,
              "const persistence = ctx.remote.$host.isLoopback ? \"host\" : \"memory\";",
              "const persistence = \"host\"; // DSHA patch: LAN 代理场景强制 host 持久化");
      preparedFiles.add(module);
      if (!source.equals(patched))
        writeIfChanged(
            context, module, patched.getBytes(java.nio.charset.StandardCharsets.UTF_8), false);
    } catch (IllegalArgumentException error) {
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
    try {
      StringBuilder value = new StringBuilder();
      android.system.StructStat root = android.system.Os.lstat(rootfs.getAbsolutePath());
      value.append(root.st_dev).append(':').append(root.st_ino);
      for (File file : preparedFiles) {
        if (!file.isFile() || Compat.isSymbolicLink(file)) return null;
        android.system.StructStat stat = android.system.Os.lstat(file.getAbsolutePath());
        value
            .append('|')
            .append(stat.st_ino)
            .append(':')
            .append(stat.st_size)
            .append(':')
            .append(file.lastModified())
            .append(':')
            .append(stat.st_ctime)
            .append(':')
            .append(stat.st_mode);
      }
      return value.toString();
    } catch (android.system.ErrnoException error) {
      return null;
    }
  }

  static void prepareResolver(Context context, File rootfs) throws IOException {
    prepareResolver(
        context, rootfs, RuntimeHostPorts.fromOwner(context.getApplicationContext()).settings());
  }

  static void prepareResolver(Context context, File rootfs, RuntimeHostPorts.Settings settings)
      throws IOException {
    prepareResolver(context, rootfs, settings, deviceDns(context));
  }

  private static void prepareResolver(
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
    if (!old.equals(updated))
      writeIfChanged(
          context, target, updated.getBytes(java.nio.charset.StandardCharsets.UTF_8), false);
  }

  private static java.util.List<String> deviceDns(Context context) {
    java.util.List<String> deviceDns = new java.util.ArrayList<>();
    try {
      android.net.ConnectivityManager manager =
          (android.net.ConnectivityManager) context.getSystemService(Context.CONNECTIVITY_SERVICE);
      if (manager != null) {
        android.net.Network active = manager.getActiveNetwork();
        android.net.LinkProperties links =
            active == null ? null : manager.getLinkProperties(active);
        if (links != null)
          for (java.net.InetAddress address : links.getDnsServers())
            deviceDns.add(address.getHostAddress());
      }
    } catch (RuntimeException unavailable) {
      /* Keep the original file; do not require network access at boot. */
    }
    return deviceDns;
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
    environment.put("DSHA_DNS_MODE", settings.dnsMode);
    String preload = "--require=/usr/local/share/dsha/dns-compat.cjs";
    if (new File(rootfs, "usr/local/share/dsha/dns-compat.cjs").isFile()) {
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
    try (InputStream input = context.getAssets().open(name);
        ByteArrayOutputStream out = new ByteArrayOutputStream()) {
      byte[] buffer = new byte[8192];
      int count;
      while ((count = input.read(buffer)) != -1) {
        if (out.size() + count > 2 * 1024 * 1024) throw new IOException("RUNTIME_TEXT_ASSET_LIMIT");
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
    try (InputStream input = context.getAssets().open(asset);
        ByteArrayOutputStream bytes = new ByteArrayOutputStream()) {
      byte[] buffer = new byte[8192];
      int count;
      while ((count = input.read(buffer)) != -1) {
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
    preparedFiles.add(file);
    RuntimeAssetFiles.write(context, file, content, executable);
  }
}
