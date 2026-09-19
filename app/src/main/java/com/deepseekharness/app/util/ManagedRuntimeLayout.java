package com.deepseekharness.app.util;

import java.util.ArrayList;
import java.util.List;

/** APK 受管文件边界；会话、配置、项目和第三方插件从不属于局部更新目标。 */
public final class ManagedRuntimeLayout {
    private ManagedRuntimeLayout() { }
    public static final String ROOT = "linux/ubuntu/";
    public static final String DSH = "usr/local/lib/node_modules/@deepseek-ai/dsh";

    public static List<String> paths() {
        List<String> out = new ArrayList<>();
        out.add(DSH);
        out.add("usr/local/share/dsha/dsh-runtime.version");
        out.add("usr/local/share/dsha/ca-certificates.crt");
        out.add("usr/local/share/dsha/dns-compat.cjs");
        out.add("usr/local/share/dsha/managed-assets-v2");
        out.add("etc/profile.d/dsha-runtime-env.sh");
        out.add("root/dsha-builtin.txt");
        out.add("root/dsha-app-integration");
        for (String name : BuiltinPlugins.DEFAULT_BUILTINS) out.add(BuiltinPlugins.entityDir(name).substring(1));
        for (String name : new String[]{"plugin-manager.py", "plugin-lifecycle.py", "plugin-dependencies.py", "plugin-transactions.py", "backup-plugin-graph.py", "plugin-semver.cjs",
                "register-builtin-plugins.py", "startup-observer.cjs", "startup-recovery.py", "startup-checkpoints.py", "device-shell-policy.py", "adb-shell.py"}) out.add("root/.dsh/" + name);
        for (String name : new String[]{"npm", "npx", "dsha-plugin", "adb-shell", "install-ubuntu-tools"}) out.add("root/dsh-bin/" + name);
        return out;
    }

    public static boolean alias(String path) {
        if (path.equals("usr/local/bin/dsh") || path.equals("usr/local/bin/tsc") || path.equals("usr/local/bin/tsserver")) return true;
        String prefix = "usr/local/lib/node_modules/";
        if (!path.startsWith(prefix)) return false;
        String name = path.substring(prefix.length());
        return !name.equals("npm") && !path.equals(DSH)
                && name.matches("(?:@[a-z0-9][a-z0-9._-]*/)?[a-z0-9][a-z0-9._-]*");
    }

    public static boolean allowed(String path) {
        if (path == null || path.contains("..") || path.contains("\\") || path.startsWith("/")) return false;
        if (path.equals("linux/.offline-identity") || path.equals("linux/.offline-version") || path.equals("linux/.offline-extracted")) return true;
        if(path.equals("linux/.runtime-descriptor.json")||path.equals("linux/.runtime-health.json"))return true;
        if (!path.startsWith(ROOT)) return false;
        String relative = path.substring(ROOT.length());
        return paths().contains(relative) || alias(relative);
    }

    public static boolean sameBase(String expected, String installed) {
        if (expected == null || installed == null) return false;
        String[] want = expected.split(":", -1), old = installed.split(":", -1);
        return want.length == 3 && old.length == 3 && want[1].equals(old[1])
                && want[1].matches("[0-9]{1,9}") && old[0].matches("[0-9]{1,9}")
                && old[2].matches("[A-Za-z0-9._+-]{1,80}");
    }
}
