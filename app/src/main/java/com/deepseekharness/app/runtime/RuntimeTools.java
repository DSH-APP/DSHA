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

    static void prepare(Context context, File rootfs) throws IOException {
        synchronized (LOCK) {
            install(context, rootfs, "ca-certificates.crt", CERT_PATH.substring(1), false);
            install(context, rootfs, "plugin-manager.py", "root/.dsh/plugin-manager.py", false);
            install(context, rootfs, "plugin-lifecycle.py", "root/.dsh/plugin-lifecycle.py", false);
            install(context, rootfs, "plugin-semver.cjs", "root/.dsh/plugin-semver.cjs", false);
            install(context, rootfs, "register-builtin-plugins.py", "root/.dsh/register-builtin-plugins.py", false);
            for (String file : new String[]{"package.json", "cordis.patch.yml", "index.js", "activity.js", "client.js"})
                install(context, rootfs, "app-integration/" + file, "root/dsha-app-integration/" + file, false);
            install(context, rootfs, "dsha-plugin.sh", "root/dsh-bin/dsha-plugin", true);
            for (String command : new String[]{"npm", "npx"}) {
                File cli = new File(rootfs, "usr/local/lib/node_modules/npm/bin/" + command + "-cli.js");
                if (!cli.isFile()) throw new IOException("内置 npm 文件缺失：" + command + "-cli.js");
                File wrapper = new File(rootfs, "root/dsh-bin/" + command);
                writeIfChanged(wrapper, ("#!/bin/sh\nexec /usr/local/bin/node /usr/local/lib/node_modules/npm/bin/"
                        + command + "-cli.js \"$@\"\n").getBytes(java.nio.charset.StandardCharsets.UTF_8), true);
            }
            install(context, rootfs, "dsha-runtime-env.sh", "etc/profile.d/dsha-runtime-env.sh", false);
        }
    }

    static void applyEnvironment(Map<String, String> environment) {
        for (String key : new String[]{"SSL_CERT_FILE", "REQUESTS_CA_BUNDLE", "CURL_CA_BUNDLE", "GIT_SSL_CAINFO"})
            environment.putIfAbsent(key, CERT_PATH);
        environment.putIfAbsent("NODE_EXTRA_CA_CERTS", CERT_PATH);
        environment.putIfAbsent("npm_config_cafile", CERT_PATH);
        environment.putIfAbsent("npm_config_prefix", "/usr/local");
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
        File parent = file.getParentFile();
        if (!parent.isDirectory() && !parent.mkdirs()) throw new IOException("无法创建运行工具目录");
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
        } catch (android.system.ErrnoException error) { throw new IOException("更新运行工具失败：" + file.getName(), error); }
        finally { staged.delete(); }
    }
}
