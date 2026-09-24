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
    private static final java.util.Set<File> preparedFiles = new java.util.LinkedHashSet<>();

    static void prepare(Context context, File rootfs) throws IOException {
        prepare(context, rootfs, true);
    }

    static void stage(Context context, File rootfs) throws IOException { prepare(context, rootfs, false); }

    private static void prepare(Context context, File rootfs, boolean requireNpm) throws IOException {
        synchronized (LOCK) {
            try { prepareResolver(context, rootfs); }
            catch(IOException error) { android.util.Log.w("DSHA","DNS configuration unchanged",error); }
            File installedDescriptor = new File(rootfs.getParentFile(), ".runtime-descriptor.json");
            if (requireNpm && installedDescriptor.isFile()
                    && !com.deepseekharness.app.BackupManager.isDataTaskOwner()) {
                // 已登记运行时的主体只能由维护事务切换。身份完全一致时仍允许修复 APK 自有
                // 脚本/内置插件覆盖层，解决旧版提前 return 后长期沿用旧文件的问题。
                String expected = assetRuntimeId(context);
                if (expected.equals(descriptorRuntimeId(installedDescriptor)))
                    prepareManagedOverlay(context, rootfs, expected);
                return;
            }
            File apk = new File(context.getPackageCodePath());
            String identity = apk.getPath() + ":" + apk.length() + ":" + apk.lastModified();
            String root = rootfs.getCanonicalPath();
            if (root.equals(preparedRoot) && identity.equals(preparedApk)
                    && preparedStamp != null && preparedStamp.equals(stamp(rootfs))) return;
            preparedStamp = null;
            preparedFiles.clear();
            String managedIdentity = assetRuntimeId(context);
            installManagedAssets(context, rootfs);
            for (String command : new String[]{"npm", "npx"}) {
                File cli = new File(rootfs, "usr/local/lib/node_modules/npm/bin/" + command + "-cli.js");
                if (requireNpm && !cli.isFile()) throw new IOException(com.deepseekharness.app.util.UiText.text("内置 npm 文件缺失：") + command + "-cli.js");
                if (requireNpm) preparedFiles.add(cli);
                File wrapper = new File(rootfs, "root/dsh-bin/" + command);
                writeIfChanged(wrapper, ("#!/bin/sh\nexec /usr/local/bin/node /usr/local/lib/node_modules/npm/bin/"
                        + command + "-cli.js \"$@\"\n").getBytes(java.nio.charset.StandardCharsets.UTF_8), true);
            }
            patchComposerInput(context, rootfs);
            patchSessionNavigation(context, rootfs);
            patchPdfCompatibility(context, rootfs);
            patchAgentPresets(context, rootfs);
            patchClientModule(context, rootfs, "persona-compat-patch.json", "旧版 persona 预设");
            patchClientModule(context, rootfs, "models-navigation-patch.json", "模型配置入口");
            patchClientModule(context, rootfs, "subagent-navigation-patch.json", "子代理触摸导航");
            patchClientModule(context, rootfs, "plugin-manager-policy-patch.json", "插件审阅");
            patchClientModule(context, rootfs, "plugin-manager-navigation-patch.json", "插件原生入口");
            patchClientModule(context, rootfs, "office-fonts-patch.json", "Office 字体");
            patchClientModule(context, rootfs, "deepseek-messages-compat-patch.json", "DeepSeek Messages 会话兼容");
            patchClientModule(context, rootfs, "rc1-settings-migration-patch.json", "rc1 设置迁移");
            patchClientLanguage(context, rootfs);
            patchTooltips(context, rootfs);
            patchBrowserBootstrap(context, rootfs);
            patchClientCombos(context, rootfs);
            patchLanSettingsPersistence(rootfs);
            writeIfChanged(new File(rootfs, MANAGED_MARKER),
                    com.deepseekharness.app.util.ManagedAssetVersion.bytes(managedIdentity), false);
            preparedRoot = root;
            preparedApk = identity;
            preparedStamp = stamp(rootfs);
        }
    }

    static void invalidate() { synchronized (LOCK) { preparedStamp = null; } }

    private static void prepareManagedOverlay(Context context, File rootfs, String identity) throws IOException {
        File marker = new File(rootfs, MANAGED_MARKER);
        String current = marker.isFile() && !Compat.isSymbolicLink(marker) ? Compat.readAll(marker) : "";
        // 标记只说明上次完整写入时的身份，不能证明脚本或插件实体后来没有被删改。
        // 每次只核对并按需重写固定数量的 APK 自有文件；不会遍历会话、项目或第三方插件。
        boolean markerCurrent = com.deepseekharness.app.util.ManagedAssetVersion.current(current, identity);
        preparedStamp = null;
        preparedFiles.clear();
        installManagedAssets(context, rootfs);
        // 覆盖升级保留同一运行时身份时，不能只更新消息兼容层。
        // Web UI 的会话抽屉、预设标题和移动端插件都属于 APK 自有覆盖层，
        // 旧版本在这里提前 return 会让 rootfs 继续使用旧 bundle。
        // 两个补丁本身带有稳定 marker，重复启动时会安全跳过。
        patchSessionNavigation(context, rootfs);
        patchAgentPresets(context, rootfs);
        patchClientModule(context, rootfs, "deepseek-messages-compat-patch.json", "DeepSeek Messages 会话兼容");
            patchClientModule(context, rootfs, "rc1-settings-migration-patch.json", "rc1 设置迁移");
        prepareBuiltinDependencies(rootfs);
        if (!markerCurrent || !marker.isFile() || Compat.isSymbolicLink(marker))
            writeIfChanged(marker, com.deepseekharness.app.util.ManagedAssetVersion.bytes(identity), false);
    }

    private static String assetRuntimeId(Context context) throws IOException {
        try {
            String value = new org.json.JSONObject(assetText(context, "runtime-descriptor.json")).getString("runtimeId");
            if (!com.deepseekharness.app.util.ManagedAssetVersion.validRuntimeId(value)) throw new IOException("RUNTIME_DESCRIPTOR_ASSET");
            return value;
        }
        catch (org.json.JSONException error) { throw new IOException("RUNTIME_DESCRIPTOR_ASSET", error); }
    }

    private static String descriptorRuntimeId(File descriptor) throws IOException {
        try {
            String value = new org.json.JSONObject(Compat.readAll(descriptor)).getString("runtimeId");
            if (!com.deepseekharness.app.util.ManagedAssetVersion.validRuntimeId(value)) throw new IOException("RUNTIME_DESCRIPTOR_INSTALLED");
            return value;
        }
        catch (org.json.JSONException error) { throw new IOException("RUNTIME_DESCRIPTOR_INSTALLED", error); }
    }

    /** 仅覆盖 DSHA 自有脚本和内置实体；用户插件、profile、配置、会话与凭据不在清单内。 */
    private static void installManagedAssets(Context context, File rootfs) throws IOException {
        install(context, rootfs, "ca-certificates.crt", CERT_PATH.substring(1), false);
        install(context, rootfs, "dns-compat.cjs", "usr/local/share/dsha/dns-compat.cjs", false);
        install(context, rootfs, "dsha-builtin.txt", "root/dsha-builtin.txt", false);
        for (String name : new String[]{"plugin-manager.py", "plugin-lifecycle.py", "plugin-dependencies.py",
                "plugin-transactions.py", "backup-plugin-graph.py", "rc1-migration.py", "rc1-settings-migration.cjs", "plugin-semver.cjs",
                "register-builtin-plugins.py", "startup-observer.cjs", "startup-recovery.py",
                "startup-checkpoints.py", "device-shell-policy.py", "adb-shell.py"})
            install(context, rootfs, name, "root/.dsh/" + name, false);
        install(context, rootfs, "dsha-device-shell.sh", "root/dsh-bin/adb-shell", true);
        for (String file : new String[]{"package.json", "cordis.patch.yml", "index.js", "activity.js", "runtime-plugins.js", "client.js"})
            install(context, rootfs, "app-integration/" + file, "root/dsha-app-integration/" + file, false);
        for (String name : com.deepseekharness.app.util.BuiltinPlugins.DEFAULT_BUILTINS) {
            String destination = com.deepseekharness.app.util.BuiltinPlugins.entityDir(name).substring(1) + "/";
            for (String file : new String[]{"package.json", "cordis.patch.yml", "lib/index.js"})
                install(context, rootfs, "builtin-plugins/" + name + "/" + file, destination + file, false);
            if (name.equals("dsh-computer-use-android"))
                install(context,rootfs,"builtin-plugins/"+name+"/lib/server.cjs",destination+"lib/server.cjs",false);
            if (name.equals("dsh-tool-vscreen"))
                install(context,rootfs,"builtin-plugins/"+name+"/lib/server.cjs",destination+"lib/server.cjs",false);
            if (name.equals("dsh-web-mobile")) for (String file : new String[]{"lib/client.js", "lib/compress.js", "lib/delete-session.js", "LICENSE"})
                install(context, rootfs, "builtin-plugins/" + name + "/" + file, destination + file, false);
        }
        install(context, rootfs, "dsha-plugin.sh", "root/dsh-bin/dsha-plugin", true);
        install(context, rootfs, "install-ubuntu-tools.sh", "root/dsh-bin/install-ubuntu-tools", true);
        install(context, rootfs, "dsha-runtime-env.sh", "etc/profile.d/dsha-runtime-env.sh", false);
    }

    /** 局域网代理仍由宿主鉴权；冷安装与候选树必须在计算健康摘要前应用同一设置补丁。 */
    static void patchLanSettingsPersistence(File rootfs) throws IOException {
        synchronized (LOCK) { prepareLanSettingsPersistence(rootfs); }
    }
    private static void prepareLanSettingsPersistence(File rootfs) throws IOException {
        File module = new File(rootfs, "usr/local/lib/node_modules/@deepseek-ai/dsh/node_modules/"
                + "@deepseek-ai/dsh-client-ui-settings/lib/client.js");
        if (!module.isFile()) throw new IOException("LAN_SETTINGS_MODULE_MISSING");
        if (Compat.isSymbolicLink(module) || !module.getCanonicalPath().startsWith(rootfs.getCanonicalPath() + File.separator))
            throw new IOException("LAN_SETTINGS_MODULE_PATH");
        String source = Compat.readAll(module);
        try {
            String patched = com.deepseekharness.app.util.ExactTextPatch.apply(source,
                    "const persistence = ctx.remote.$host.isLoopback ? \"host\" : \"memory\";",
                    "const persistence = \"host\"; // DSHA patch: LAN 代理场景强制 host 持久化");
            preparedFiles.add(module);
            if (!source.equals(patched)) writeIfChanged(module, patched.getBytes(java.nio.charset.StandardCharsets.UTF_8), false);
        } catch (IllegalArgumentException error) { throw new IOException("LAN_SETTINGS_PATCH_MISMATCH", error); }
    }

    /** 冷安装与受管候选共用；只准备内置实体依赖，不注册或改写用户 web profile。 */
    static void prepareBuiltinDependencies(File rootfs)throws IOException{
        var fs=new com.deepseekharness.app.backup.AndroidBackupFileSystem();File root=rootfs.getCanonicalFile();
        String target="../../usr/local/lib/node_modules/@deepseek-ai/dsh/node_modules";
        if(!fs.stat(new File(root,"usr/local/lib/node_modules/@deepseek-ai/dsh/node_modules")).type.equals("DIRECTORY"))throw new IOException("BUNDLED_MODULES_MISSING");
        var names=new java.util.ArrayList<>(com.deepseekharness.app.util.BuiltinPlugins.DEFAULT_BUILTINS);names.add("dsh-app-integration");
        for(String name:names){
            File link=fs.child(root,com.deepseekharness.app.util.BuiltinPlugins.entityDir(name).substring(1)+"/node_modules");var node=fs.stat(link);
            if(node.type.equals("LINK")&&target.equals(fs.readLink(link)))continue;
            if(node.type.equals("DIRECTORY")&&fs.list(link).isEmpty())fs.delete(link);
            else if(!node.type.equals("MISSING"))throw new IOException("BUNDLED_MODULES_CONFLICT:"+name);
            fs.symlink(target,link);fs.syncDirectory(link.getParentFile());
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
                value.append('|').append(stat.st_ino).append(':').append(stat.st_size).append(':')
                        .append(file.lastModified());
            }
            return value.toString();
        } catch (android.system.ErrnoException error) { return null; }
    }

    /** 界面修订覆盖安装后直接更新既有 dsh；不重建环境、不触碰会话和插件配置。 */
    private static void patchComposerInput(Context context, File rootfs) throws IOException {
        File packageFile = new File(rootfs, "usr/local/lib/node_modules/@deepseek-ai/dsh/package.json");
        File client = new File(rootfs, "usr/local/lib/node_modules/@deepseek-ai/dsh/node_modules/@deepseek-ai/dsh-client-ui-conversation/lib/client.js");
        if (!packageFile.isFile() || !client.isFile()) return;
        preparedFiles.add(packageFile);
        preparedFiles.add(client);
        try {
            org.json.JSONObject metadata = new org.json.JSONObject(Compat.readAll(packageFile));
            if (!com.deepseekharness.app.util.Constants.DSH_VERSION.equals(metadata.optString("version"))) return;
            if (Compat.isSymbolicLink(client) || !client.getCanonicalPath().startsWith(rootfs.getCanonicalPath() + File.separator))
                throw new IOException(com.deepseekharness.app.util.UiText.text("输入适配的模块路径不安全，原文件保留"));
            String source = Compat.readAll(client), updated = source;
            org.json.JSONObject specification;
            try (InputStream input = context.getAssets().open("composer-enter-patch.json"); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
                byte[] bytes = new byte[4096]; int n; while ((n = input.read(bytes)) != -1) out.write(bytes, 0, n);
                specification = new org.json.JSONObject(out.toString("UTF-8"));
            }
            org.json.JSONArray patches = specification.getJSONArray("patches");
            for (int i = 0; i < patches.length(); i++) {
                org.json.JSONObject patch = patches.getJSONObject(i);
                updated = com.deepseekharness.app.util.ExactTextPatch.apply(updated, patch.getString("before"), patch.getString("after"));
            }
            if (!updated.equals(source)) writeIfChanged(client, updated.getBytes(java.nio.charset.StandardCharsets.UTF_8), false);
        } catch (org.json.JSONException | IllegalArgumentException error) {
            throw new IOException(com.deepseekharness.app.util.UiText.text("对话输入适配未应用，原文件保留：") + error.getMessage(), error);
        }
    }

    static void prepareResolver(Context context, File rootfs) throws IOException {
        File target=new File(rootfs,"etc/resolv.conf");
        if (!target.getParentFile().isDirectory())return;
        // 特殊文件或外部软链接保持原位；不能跟随 guest 绝对链接写到宿主。
        if (Compat.isSymbolicLink(target) || target.exists() && !target.isFile())return;
        String old=target.isFile()?Compat.readAll(target):"";
        String updated=com.deepseekharness.app.util.ResolverConfig.reconcile(old,new com.deepseekharness.app.core.ConfigStore(context).getDnsMode());
        if(!old.equals(updated))writeIfChanged(target,updated.getBytes(java.nio.charset.StandardCharsets.UTF_8),false);
    }

    static void applyEnvironment(Context context, File rootfs, Map<String, String> environment) {
        environment.put("DSHA_NATIVE_PLUGIN_MANAGER","1");
        environment.put("DSHA_ANDROID_RUNTIME","1");
        environment.put("DSHA_DNS_MODE",new com.deepseekharness.app.core.ConfigStore(context).getDnsMode());
        String preload="--require=/usr/local/share/dsha/dns-compat.cjs";
        if(new File(rootfs,"usr/local/share/dsha/dns-compat.cjs").isFile()) {
            String previous=environment.getOrDefault("NODE_OPTIONS","");
            if(!Arrays.asList(previous.split("\\s+")).contains(preload))environment.put("NODE_OPTIONS",preload+(previous.isEmpty()?"":" "+previous));
        }
        // 原生扩展已在私有运行时中；复制缓存使用 link+unlink，在 link2symlink 下首次变成悬链。
        environment.putIfAbsent("NARB_DISABLE_NATIVE_CACHE", "1");
        for (String key : new String[]{"SSL_CERT_FILE", "REQUESTS_CA_BUNDLE", "CURL_CA_BUNDLE", "GIT_SSL_CAINFO"})
            environment.putIfAbsent(key, CERT_PATH);
        environment.putIfAbsent("NODE_EXTRA_CA_CERTS", CERT_PATH);
        environment.putIfAbsent("npm_config_cafile", CERT_PATH);
        environment.putIfAbsent("npm_config_prefix", "/usr/local");
    }

    private static void patchSessionNavigation(Context context, File rootfs) throws IOException {
        File pkg = new File(rootfs, "usr/local/lib/node_modules/@deepseek-ai/dsh/package.json");
        File client = new File(rootfs, "usr/local/lib/node_modules/@deepseek-ai/dsh/node_modules/@deepseek-ai/dsh-client-ui-workspace/lib/client.js");
        if (!pkg.isFile() || !client.isFile()) return;
        try {
            org.json.JSONObject spec = new org.json.JSONObject(assetText(context, "session-interaction-patch.json"));
            if (!spec.getString("dshVersion").equals(new org.json.JSONObject(Compat.readAll(pkg)).optString("version"))) return;
            if (Compat.isSymbolicLink(client) || !client.getCanonicalPath().startsWith(rootfs.getCanonicalPath() + File.separator))
                throw new IOException(com.deepseekharness.app.util.UiText.text("会话交互适配的模块路径不安全"));
            preparedFiles.add(client);
            String source = Compat.readAll(client), updated = source;
            // 受管运行时在上一次启动已经完成这组补丁时保持幂等；否则同一
            // before 文本仍可能存在于已插入的代码前缀中，造成重复注入。
            if (source.contains("DSHA_SESSION_INTERACTION_V2") && source.contains("dsha-session-open")) return;
            if (source.contains("DSHA_SESSION_INTERACTION_V1")) {
                String canonical = restoreBundledClientModule(context, rootfs, "@deepseek-ai/dsh-client-ui-workspace/lib/client.js");
                if (canonical == null) throw new IOException("SESSION_PATCH_SOURCE_UNAVAILABLE");
                updated = canonical;
            }
            org.json.JSONArray patches = spec.getJSONArray("patches");
            for (int i = 0; i < patches.length(); i++) {
                org.json.JSONObject patch = patches.getJSONObject(i);
                String after = patch.getString("after");
                if (patch.has("prependAsset")) after = assetText(context, patch.getString("prependAsset")) + "\n" + after;
                updated = com.deepseekharness.app.util.ExactTextPatch.apply(updated, patch.getString("before"), after);
            }
            if (!updated.equals(source)) writeIfChanged(client, updated.getBytes(java.nio.charset.StandardCharsets.UTF_8), false);
        } catch (org.json.JSONException | IllegalArgumentException error) {
            throw new IOException(com.deepseekharness.app.util.UiText.text("会话交互适配未应用，原文件保留：") + error.getMessage(), error);
        }
    }

    /** 预设选择仍通过上游 Host 的真实组装接口，失败时保持原会话和标签。 */
    private static void patchClientLanguage(Context context, File rootfs) throws IOException {
        patchClientModule(context, rootfs, "language-patch.json", "界面语言");
    }
    private static void patchAgentPresets(Context context, File rootfs) throws IOException {
        patchClientModule(context, rootfs, "agent-preset-patch.json", "Agent 预设");
    }
    private static void patchClientModule(Context context, File rootfs, String asset, String description) throws IOException {
        File pkg = new File(rootfs, "usr/local/lib/node_modules/@deepseek-ai/dsh/package.json");
        if (!pkg.isFile()) return;
        try {
            org.json.JSONObject spec = new org.json.JSONObject(assetText(context, asset));
            if (!spec.getString("dshVersion").equals(new org.json.JSONObject(Compat.readAll(pkg)).optString("version"))) return;
            File client = new File(rootfs, "usr/local/lib/node_modules/@deepseek-ai/dsh/node_modules/" + spec.getString("module"));
            if (!client.isFile() || Compat.isSymbolicLink(client)
                    || !client.getCanonicalPath().startsWith(rootfs.getCanonicalPath() + File.separator))
                throw new IOException(description + com.deepseekharness.app.util.UiText.text("适配的模块路径不安全或缺失"));
            preparedFiles.add(client);
            String source = Compat.readAll(client), updated = source;
            if ("agent-preset-patch.json".equals(asset)
                    && source.contains("DSHA_AGENT_PRESET_SWITCH_V1")
                    && source.contains("dsha-preset-header-anchor")) return;
            org.json.JSONArray patches = spec.getJSONArray("patches");
            try {
                updated = applyClientPatches(context, patches, updated);
            } catch (IllegalArgumentException mismatch) {
                // 受管 dsh 模块不是用户插件。旧版本曾把另一版补丁留在环境中，
                // 此时不能让整个环境重建永久卡在首个前端适配步骤；从当前 APK
                // 的 dsh-runtime.bin 恢复同一模块，再按当前补丁链一次性重做。
                String canonical = restoreBundledClientModule(context, rootfs, spec.getString("module"));
                if (canonical == null) throw mismatch;
                updated = applyClientPatches(context, patches, canonical);
            }
            if (!updated.equals(source)) writeIfChanged(client, updated.getBytes(java.nio.charset.StandardCharsets.UTF_8), false);
        } catch (org.json.JSONException | IllegalArgumentException error) {
            throw new IOException(description + com.deepseekharness.app.util.UiText.text("适配未应用，原文件保留：") + error.getMessage(), error);
        }
    }

    private static String applyClientPatches(Context context, org.json.JSONArray patches, String source)
            throws org.json.JSONException, IOException {
        String updated = source;
        for (int i = 0; i < patches.length(); i++) {
            org.json.JSONObject patch = patches.getJSONObject(i);
            String after = patch.getString("after");
            if (patch.has("prependAsset")) after = assetText(context, patch.getString("prependAsset")) + "\n" + after;
            updated = com.deepseekharness.app.util.ExactTextPatch.apply(updated, patch.getString("before"), after);
        }
        return updated;
    }

    /** 从当前 APK 的分包 dsh 运行时恢复一个受管前端模块；找不到时交回原始补丁错误。 */
    private static String restoreBundledClientModule(Context context, File rootfs, String module) throws IOException {
        String relative = "usr/local/lib/node_modules/@deepseek-ai/dsh/node_modules/" + module;
        File target = new File(rootfs, relative);
        String[] candidate = {relative, "./" + relative};
        try (ZipFile apk = new ZipFile(context.getPackageCodePath())) {
            ZipEntry entry = apk.getEntry("assets/dsh-runtime.bin");
            if (entry == null) return null;
            File staging = new File(rootfs, ".dsha-managed-module-" + Integer.toHexString(relative.hashCode()));
            if (staging.exists()) deleteTemporary(staging);
            staging.mkdirs();
            final boolean[] found = {false};
            try (InputStream input = apk.getInputStream(entry)) {
                TarGzipExtractor.extractSelected(input, staging, 0, name -> {
                    for (String value : candidate) if (value.equals(name)) { found[0] = true; return true; }
                    return false;
                });
            }
            if (!found[0]) { deleteTemporary(staging); return null; }
            File extracted = new File(staging, relative);
            if (!extracted.isFile() || Compat.isSymbolicLink(extracted)) { deleteTemporary(staging); return null; }
            byte[] bytes = Compat.readAll(extracted).getBytes(java.nio.charset.StandardCharsets.UTF_8);
            if (bytes.length == 0) { deleteTemporary(staging); return null; }
            writeIfChanged(target, bytes, false);
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
            org.json.JSONObject spec = new org.json.JSONObject(assetText(context, "pdf-compat-patch.json"));
            if (!spec.getString("dshVersion").equals(new org.json.JSONObject(Compat.readAll(pkg)).optString("version"))) return;
            File client = new File(rootfs, "usr/local/lib/node_modules/@deepseek-ai/dsh/node_modules/" + spec.getString("module"));
            if (!client.isFile() || Compat.isSymbolicLink(client)
                    || !client.getCanonicalPath().startsWith(rootfs.getCanonicalPath() + File.separator))
                throw new IOException(com.deepseekharness.app.util.UiText.text("PDF 兼容适配的模块路径不安全或文件缺失"));
            preparedFiles.add(client);
            // 构建器生成单行 IIFE；保持上游代码的行号，Worker 有独立全局对象，必须单独注入。
            String compatibility = assetText(context, spec.getString("asset")).trim().replace("\n", " ");
            String source = Compat.readAll(client), before = spec.getString("mainBefore");
            String updated = com.deepseekharness.app.util.ExactTextPatch.apply(source, before,
                    "/* DSHA_PDF_COMPAT_V1 */ " + compatibility + " " + before);
            before = spec.getString("workerBefore");
            updated = com.deepseekharness.app.util.ExactTextPatch.apply(updated, before,
                    "new Blob([" + org.json.JSONObject.quote(compatibility + "\n") + ", _dsh_pdf_worker_default,");
            File resource = new File(rootfs, "usr/local/lib/node_modules/@deepseek-ai/dsh/node_modules/" + spec.getString("resourceModule"));
            if (!resource.isFile() || Compat.isSymbolicLink(resource)
                    || !resource.getCanonicalPath().startsWith(rootfs.getCanonicalPath() + File.separator))
                throw new IOException(com.deepseekharness.app.util.UiText.text("文件资源协议适配的模块路径不安全或文件缺失"));
            preparedFiles.add(resource);
            String registry = Compat.readAll(resource);
            String corrected = com.deepseekharness.app.util.ExactTextPatch.apply(registry,
                    spec.getString("resourceBefore"), spec.getString("resourceAfter"));
            if (!updated.equals(source)) writeIfChanged(client, updated.getBytes(java.nio.charset.StandardCharsets.UTF_8), false);
            if (!corrected.equals(registry)) writeIfChanged(resource, corrected.getBytes(java.nio.charset.StandardCharsets.UTF_8), false);
        } catch (org.json.JSONException | IllegalArgumentException error) {
            throw new IOException(com.deepseekharness.app.util.UiText.text("PDF 兼容适配未应用，原文件保留：") + error.getMessage(), error);
        }
    }

    /** 修正触摸设备上的提示状态；更新入口查询标记，让已有浏览缓存取得本次修订。 */
    private static void patchTooltips(Context context, File rootfs) throws IOException {
        File packageFile = new File(rootfs, "usr/local/lib/node_modules/@deepseek-ai/dsh/package.json");
        if (!packageFile.isFile()) return;
        try {
            org.json.JSONObject metadata = new org.json.JSONObject(Compat.readAll(packageFile));
            org.json.JSONObject patch = new org.json.JSONObject(assetText(context, "web-integration/tooltip-patch.json"));
            if (!patch.getString("dshVersion").equals(metadata.optString("version"))) return;
            File frontend = new File(rootfs, "usr/local/lib/node_modules/@deepseek-ai/dsh/node_modules/@deepseek-ai/dsh-web-frontend/dist");
            File bundle = new File(frontend, patch.getString("bundle")), index = new File(frontend, "index.html");
            preparedFiles.add(bundle);
            preparedFiles.add(index);
            String boundary = rootfs.getCanonicalPath() + File.separator;
            for (File file : new File[]{bundle, index}) {
                if (!file.isFile() || Compat.isSymbolicLink(file) || !file.getCanonicalPath().startsWith(boundary))
                    throw new IOException(com.deepseekharness.app.util.UiText.text("对话提示适配的文件缺失或路径不安全"));
            }
            String replacement = assetText(context, "web-integration/tooltip-interactions.js")
                    + "\nconst dshaTooltipRuntime = createDshaTooltipRuntime({ document, window });\n"
                    + assetText(context, "web-integration/tooltip-component.js");
            String source = Compat.readAll(bundle), html = Compat.readAll(index);
            String updated = com.deepseekharness.app.util.ExactTextPatch.apply(source, patch.getString("before"), replacement);
            String entry = com.deepseekharness.app.util.ExactTextPatch.apply(html, patch.getString("indexBefore"), patch.getString("indexAfter"));
            if (!updated.equals(source)) writeIfChanged(bundle, updated.getBytes(java.nio.charset.StandardCharsets.UTF_8), false);
            if (!entry.equals(html)) writeIfChanged(index, entry.getBytes(java.nio.charset.StandardCharsets.UTF_8), false);
        } catch (org.json.JSONException | IllegalArgumentException error) {
            throw new IOException(com.deepseekharness.app.util.UiText.text("对话提示适配未应用，原文件保留：") + error.getMessage(), error);
        }
    }

    private static String assetText(Context context, String name) throws IOException {
        try (InputStream input = context.getAssets().open(name); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[8192]; int count;
            while ((count = input.read(buffer)) != -1) out.write(buffer, 0, count);
            return out.toString("UTF-8").replace("\r\n", "\n");
        }
    }

    /** 类似 1.1.10：浏览器自身收到的页面就含补丁，不依赖厂商的文档起始注入接口。 */
    private static void patchBrowserBootstrap(Context context, File rootfs) throws IOException {
        File pkg=new File(rootfs,"usr/local/lib/node_modules/@deepseek-ai/dsh/package.json");
        if(!pkg.isFile())return;
        try {
            if(!com.deepseekharness.app.util.Constants.DSH_VERSION.equals(new org.json.JSONObject(Compat.readAll(pkg)).optString("version")))return;
            File index=new File(rootfs,"usr/local/lib/node_modules/@deepseek-ai/dsh/node_modules/@deepseek-ai/dsh-web-frontend/dist/index.html");
            if(!index.isFile()||Compat.isSymbolicLink(index)||!index.getCanonicalPath().startsWith(rootfs.getCanonicalPath()+File.separator))throw new IOException("Browser bootstrap path is invalid");
            preparedFiles.add(index);
            String html=Compat.readAll(index);
            String patched=com.deepseekharness.app.util.HtmlBootstrapPatch.apply(html,assetText(context,"web-integration/es-compat.js")
                    +"\n"+assetText(context,"web-integration/startup.js"));
            if(!html.equals(patched))writeIfChanged(index,patched.getBytes(java.nio.charset.StandardCharsets.UTF_8),false);
        }catch(org.json.JSONException|IllegalArgumentException error){throw new IOException("Browser compatibility bootstrap was not applied",error);}
    }

    private static void patchClientCombos(Context context, File rootfs) throws IOException {
        File pkg = new File(rootfs, "usr/local/lib/node_modules/@deepseek-ai/dsh/package.json");
        File module = new File(rootfs, "usr/local/lib/node_modules/@deepseek-ai/dsh/node_modules/@deepseek-ai/dsh-client-modules/lib/index.js");
        if (!pkg.isFile() || !module.isFile()) return;
        try {
            org.json.JSONObject spec = new org.json.JSONObject(assetText(context, "client-combo-patch.json"));
            if (!spec.getString("dshVersion").equals(new org.json.JSONObject(Compat.readAll(pkg)).optString("version"))) return;
            if (Compat.isSymbolicLink(module) || !module.getCanonicalPath().startsWith(rootfs.getCanonicalPath() + File.separator))
                throw new IOException(com.deepseekharness.app.util.UiText.text("网页脚本模块路径不安全，原文件保留"));
            String source = Compat.readAll(module), patched = source;
            org.json.JSONArray patches = spec.getJSONArray("patches");
            for (int i = 0; i < patches.length(); i++) {
                org.json.JSONObject patch = patches.getJSONObject(i);
                patched = com.deepseekharness.app.util.ExactTextPatch.apply(patched, patch.getString("before"), patch.getString("after"));
            }
            for (String file : new String[]{"package.json", "index.js"})
                install(context, rootfs, "client-combo-cache/" + file,
                        "usr/local/lib/node_modules/@deepseek-ai/dsh/node_modules/dsha-client-combo-cache/" + file, false);
            preparedFiles.add(module);
            if (!source.equals(patched)) writeIfChanged(module, patched.getBytes(java.nio.charset.StandardCharsets.UTF_8), false);
        } catch (org.json.JSONException | IllegalArgumentException error) {
            throw new IOException(com.deepseekharness.app.util.UiText.text("网页脚本拼接优化未应用，原文件保留：") + error.getMessage(), error);
        }
    }

    private static void install(Context context, File rootfs, String asset, String path, boolean executable) throws IOException {
        try (InputStream input = context.getAssets().open(asset); ByteArrayOutputStream bytes = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[8192]; int count;
            while ((count = input.read(buffer)) != -1) bytes.write(buffer, 0, count);
            byte[] content = bytes.toByteArray();
            if (asset.endsWith(".sh") || asset.endsWith(".py"))
                content = new String(content, java.nio.charset.StandardCharsets.UTF_8).replace("\r\n", "\n")
                        .getBytes(java.nio.charset.StandardCharsets.UTF_8);
            writeIfChanged(new File(rootfs, path), content, executable);
        }
    }

    private static void writeIfChanged(File file, byte[] content, boolean executable) throws IOException {
        preparedFiles.add(file);
        File parent = file.getParentFile();
        if (!parent.isDirectory() && !parent.mkdirs()) throw new IOException(com.deepseekharness.app.util.UiText.text("无法创建运行工具目录"));
        if (file.isFile() && !Compat.isSymbolicLink(file) && Arrays.equals(Compat.readAllBytes(file), content)) {
            if (executable) file.setExecutable(true, false);
            return;
        }
        // 替换文件本身，不追随软链，不让并发执行读到半份脚本。
        File staged = new File(parent, file.getName() + ".dsha-tmp");
        try {
            Compat.write(staged, content);
            if (executable) staged.setExecutable(true, false);
            android.system.Os.rename(staged.getAbsolutePath(), file.getAbsolutePath());
        } catch (android.system.ErrnoException error) { throw new IOException(com.deepseekharness.app.util.UiText.text("更新运行工具失败：") + file.getName(), error); }
        finally { staged.delete(); }
    }
}
