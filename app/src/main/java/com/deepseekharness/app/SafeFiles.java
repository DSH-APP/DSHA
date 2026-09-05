package com.deepseekharness.app;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;

/** 文件边界与发布规则；无 Android 依赖，真实文件往返测试覆盖。 */
final class SafeFiles {
    private SafeFiles() { }

    static File inside(File root, String relative) throws IOException {
        File base = root.getCanonicalFile();
        File file = new File(base, relative).getCanonicalFile();
        if (!isInside(base, file)) {
            throw new IOException("路径超出允许目录");
        }
        return file;
    }

    /** 路径边界必须认完整目录段，{@code /sdcard-old} 不能算在 {@code /sdcard} 里。 */
    static boolean isInside(File root, File child) {
        String base = root.getPath();
        String path = child.getPath();
        return path.equals(base) || path.startsWith(base + File.separator);
    }

    /** 预留唯一文件必须原子创建，不能先 exists 再覆盖写。包含悬空链接的名字也不复用。 */
    static File reserve(File dir, String name) throws IOException {
        String safe = cleanName(name);
        if (safe.isEmpty() || safe.equals(".") || safe.equals("..")) safe = "文件";
        // 给 UTF-8 文件名与递增后缀留出 NAME_MAX 余量。
        if (safe.length() > 96) safe = safe.substring(0, 96);
        int dot = safe.lastIndexOf('.');
        String stem = dot > 0 ? safe.substring(0, dot) : safe;
        String ext = dot > 0 ? safe.substring(dot) : "";
        for (int i = 0; i < 1000; i++) {
            File f = new File(dir, i == 0 ? safe : stem + "-" + (i + 1) + ext);
            try {
                Files.createFile(f.toPath());
                return f;
            } catch (java.nio.file.FileAlreadyExistsException occupied) {
                // 包括目录、普通文件与悬空软链；换名字，不打开已有对象。
            }
        }
        throw new IOException("同名文件过多");
    }

    static String cleanName(String name) {
        String raw = name == null ? "" : name.trim();
        StringBuilder out = new StringBuilder(Math.min(raw.length(), 96));
        for (int i = 0; i < raw.length() && out.length() < 96; i++) {
            char c = raw.charAt(i);
            out.append(c == '/' || c == '\\' || c <= 0x1f || c == 0x7f ? '_' : c);
        }
        return out.toString().trim();
    }

    /** 不支持原子替换就失败并保留旧文件；绝不 delete(dst) 后赌第二次 rename。 */
    static void replace(File temporary, File destination) throws IOException {
        Files.move(temporary.toPath(), destination.toPath(),
                StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
    }

    static boolean isPrimaryExternalPath(String canonicalPath) {
        return canonicalPath != null && (canonicalPath.equals("/sdcard")
                || canonicalPath.startsWith("/sdcard/")
                || canonicalPath.equals("/storage/emulated/0")
                || canonicalPath.startsWith("/storage/emulated/0/"));
    }

    static boolean shareSourceAllowed(String scheme, String authority, String ownPackage, boolean granted) {
        return granted && "content".equals(scheme) && authority != null && !authority.isEmpty()
                && !authority.equals(ownPackage) && !authority.startsWith(ownPackage + ".");
    }
}
