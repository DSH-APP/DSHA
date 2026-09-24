package com.deepseekharness.app.util;

/** profile 设置逻辑路径；归档不能指定任意文件或越出 profile。 */
public final class ProfileConfigPath {
    private ProfileConfigPath() { }
    public static boolean profile(String name) {
        return name != null && name.matches("[A-Za-z0-9][A-Za-z0-9._-]{0,127}") && !name.contains("..");
    }
    public static boolean accepts(String path) {
        if (path == null) return false;
        String[] parts = path.split("/", -1);
        return parts.length == 3 && parts[0].equals("profiles") && profile(parts[1])
                && (parts[2].equals("package.json") || parts[2].equals("cordis.patch.yml"));
    }
}
