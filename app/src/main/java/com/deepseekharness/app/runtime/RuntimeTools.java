package com.deepseekharness.app.runtime;

import android.content.Context;
import com.deepseekharness.app.util.Compat;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.util.Arrays;
import java.util.Map;

/** 统一准备插件与终端的证书和命令入口，无需先手动运行安装第 2 步。 */
final class RuntimeTools {
    static final String CERT_PATH = "/usr/local/share/dsha/ca-certificates.crt";
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
            File apk = new File(context.getPackageCodePath());
            String identity = apk.getPath() + ":" + apk.length() + ":" + apk.lastModified();
            String root = rootfs.getCanonicalPath();
            if (root.equals(preparedRoot) && identity.equals(preparedApk)
                    && preparedStamp != null && preparedStamp.equals(stamp(rootfs))) return;
            preparedStamp = null;
            preparedFiles.clear();
            install(context, rootfs, "ca-certificates.crt", CERT_PATH.substring(1), false);
            install(context, rootfs, "dns-compat.cjs", "usr/local/share/dsha/dns-compat.cjs", false);
            install(context, rootfs, "plugin-manager.py", "root/.dsh/plugin-manager.py", false);
            install(context, rootfs, "plugin-lifecycle.py", "root/.dsh/plugin-lifecycle.py", false);
            install(context, rootfs, "plugin-semver.cjs", "root/.dsh/plugin-semver.cjs", false);
            install(context, rootfs, "register-builtin-plugins.py", "root/.dsh/register-builtin-plugins.py", false);
            install(context, rootfs, "startup-observer.cjs", "root/.dsh/startup-observer.cjs", false);
            install(context, rootfs, "startup-recovery.py", "root/.dsh/startup-recovery.py", false);
            install(context, rootfs, "startup-checkpoints.py", "root/.dsh/startup-checkpoints.py", false);
            install(context, rootfs, "device-shell-policy.py", "root/.dsh/device-shell-policy.py", false);
            install(context, rootfs, "adb-shell.py", "root/.dsh/adb-shell.py", false);
            install(context, rootfs, "dsha-device-shell.sh", "root/dsh-bin/adb-shell", true);
            for (String file : new String[]{"package.json", "cordis.patch.yml", "index.js", "activity.js", "runtime-plugins.js", "client.js"})
                install(context, rootfs, "app-integration/" + file, "root/dsha-app-integration/" + file, false);
            for (String name : com.deepseekharness.app.util.BuiltinPlugins.DEFAULT_BUILTINS) {
                String destination = com.deepseekharness.app.util.BuiltinPlugins.entityDir(name).substring(1) + "/";
                for (String file : new String[]{"package.json", "cordis.patch.yml", "lib/index.js"})
                    install(context, rootfs, "builtin-plugins/" + name + "/" + file, destination + file, false);
                if (name.equals("dsh-web-mobile")) for (String file : new String[]{"lib/client.js", "lib/compress.js", "lib/delete-session.js", "LICENSE"})
                    install(context, rootfs, "builtin-plugins/" + name + "/" + file, destination + file, false);
            }
            install(context, rootfs, "dsha-plugin.sh", "root/dsh-bin/dsha-plugin", true);
            install(context, rootfs, "install-ubuntu-tools.sh", "root/dsh-bin/install-ubuntu-tools", true);
            for (String command : new String[]{"npm", "npx"}) {
                File cli = new File(rootfs, "usr/local/lib/node_modules/npm/bin/" + command + "-cli.js");
                if (requireNpm && !cli.isFile()) throw new IOException(com.deepseekharness.app.util.UiText.text("内置 npm 文件缺失：") + command + "-cli.js");
                if (requireNpm) preparedFiles.add(cli);
                File wrapper = new File(rootfs, "root/dsh-bin/" + command);
                writeIfChanged(wrapper, ("#!/bin/sh\nexec /usr/local/bin/node /usr/local/lib/node_modules/npm/bin/"
                        + command + "-cli.js \"$@\"\n").getBytes(java.nio.charset.StandardCharsets.UTF_8), true);
            }
            install(context, rootfs, "dsha-runtime-env.sh", "etc/profile.d/dsha-runtime-env.sh", false);
            patchComposerInput(context, rootfs);
            patchSessionNavigation(context, rootfs);
            patchPdfCompatibility(context, rootfs);
            patchAgentPresets(context, rootfs);
            patchClientLanguage(context, rootfs);
            patchTooltips(context, rootfs);
            patchBrowserBootstrap(context, rootfs);
            patchClientCombos(context, rootfs);
            preparedRoot = root;
            preparedApk = identity;
            preparedStamp = stamp(rootfs);
        }
    }

    static void invalidate() { synchronized (LOCK) { preparedStamp = null; } }

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
        environment.put("DSHA_DNS_MODE",new com.deepseekharness.app.core.ConfigStore(context).getDnsMode());
        String preload="--require=/usr/local/share/dsha/dns-compat.cjs";
        if(new File(rootfs,"usr/local/share/dsha/dns-compat.cjs").isFile()) {
            String previous=environment.getOrDefault("NODE_OPTIONS","");
            if(!Arrays.asList(previous.split("\\s+")).contains(preload))environment.put("NODE_OPTIONS",preload+(previous.isEmpty()?"":" "+previous));
        }
        // 原生扩展已在私有运行时中；复制缓存使用 link+unlink，在 link2symlink 下首次变成悬链。
        environment.putIfAbsent("NARB_DISABLE_NATIVE_CACHE", "1");
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
            org.json.JSONArray patches = spec.getJSONArray("patches");
            for (int i = 0; i < patches.length(); i++) {
                org.json.JSONObject patch = patches.getJSONObject(i);
                String after = patch.getString("after");
                if (patch.has("prependAsset")) after = assetText(context, patch.getString("prependAsset")) + "\n" + after;
                updated = com.deepseekharness.app.util.ExactTextPatch.apply(updated, patch.getString("before"), after);
            }
            if (!updated.equals(source)) writeIfChanged(client, updated.getBytes(java.nio.charset.StandardCharsets.UTF_8), false);
        } catch (org.json.JSONException | IllegalArgumentException error) {
            throw new IOException(description + com.deepseekharness.app.util.UiText.text("适配未应用，原文件保留：") + error.getMessage(), error);
        }
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
