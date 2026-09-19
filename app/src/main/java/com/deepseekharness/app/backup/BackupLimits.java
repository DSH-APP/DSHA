package com.deepseekharness.app.backup;

import java.io.IOException;
import java.text.Normalizer;
import java.util.Locale;

/** 所有限额针对实际读取量，不能信任 ZIP 或历史 tar 的声明。 */
public final class BackupLimits {
    public static final long BYTES = 16L * 1024 * 1024 * 1024;
    public static final int ENTRIES = 100_000, DEPTH = 64, LINKS = 40;
    public static final int RECORD = 16 * 1024, MANIFEST = 2 * 1024 * 1024, METADATA = 32 * 1024 * 1024;
    private BackupLimits() { }
    public static String path(String name) throws IOException {
        if (name == null || name.length() > 2048 || name.startsWith("/") || name.indexOf('\\') >= 0
                || name.indexOf('\0') >= 0 || name.matches("^[A-Za-z]:.*")) throw new IOException("ARCHIVE_PATH");
        if (name.isEmpty()) return name;
        String[] parts = name.split("/",-1);
        if (parts.length > DEPTH) throw new IOException("PATH_DEPTH");
        for (String part:parts) if (part.isEmpty() || part.equals(".") || part.equals("..")
                || part.indexOf('\n') >= 0 || part.indexOf('\r') >= 0) throw new IOException("ARCHIVE_PATH");
        return name;
    }
    public static String collisionKey(String root,String path) throws IOException {
        root(root); path(path);
        return root + (path.isEmpty()?"":"/" + Normalizer.normalize(path,Normalizer.Form.NFC).toLowerCase(Locale.ROOT));
    }
    public static String root(String value) throws IOException {
        if (value == null || !value.matches("[a-z][a-z0-9_-]{0,79}")) throw new IOException("ROOT_ID");
        return value;
    }
    public static long add(long current,long next,long maximum) throws IOException {
        if (next < 0 || current < 0 || next > maximum-current) throw new IOException("ARCHIVE_LIMIT");
        return current+next;
    }
}
