package com.deepseekharness.app.util;

import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;

/** 检查实际启动路径和 ELF 加载器；不以另一条 Bash 路径存在或旧标记代替可启动性。 */
public final class RootfsBootstrap {
    private RootfsBootstrap() { }
    public interface Resolver { File resolve(String guestPath) throws IOException; }
    public static boolean recoveryAsset(String name) {
        while (name.startsWith("./")) name = name.substring(2);
        if (name.endsWith("/")) name = name.substring(0, name.length() - 1);
        return name.equals("bin") || name.equals("lib") || name.equals("lib64")
                || name.startsWith("lib/") || name.startsWith("lib64/") || name.startsWith("usr/lib/")
                || name.equals("usr/bin/bash") || name.equals("usr/bin/sh")
                || name.equals("bin/bash") || name.equals("bin/sh");
    }
    public static boolean ready(Resolver resolver) {
        try {
            File shell = resolver.resolve("/bin/bash");
            if (!shell.isFile() || !shell.canRead()) return false;
            try (RandomAccessFile file = new RandomAccessFile(shell, "r")) {
                byte[] header = new byte[64]; file.readFully(header);
                ByteBuffer elf = ByteBuffer.wrap(header).order(ByteOrder.LITTLE_ENDIAN);
                if (elf.getInt(0) != 0x464c457f || header[4] != 2 || header[5] != 1 || elf.getShort(18) != 183) return false;
                long offset = elf.getLong(32); int size = elf.getShort(54) & 65535, count = elf.getShort(56) & 65535;
                if (offset < 64 || size < 56 || size > 256 || count < 1 || count > 64
                        || offset > file.length() - (long)size * count) return false;
                for (int i = 0; i < count; i++) {
                    file.seek(offset + (long)i * size); byte[] ph = new byte[56]; file.readFully(ph);
                    ByteBuffer entry = ByteBuffer.wrap(ph).order(ByteOrder.LITTLE_ENDIAN);
                    if (entry.getInt(0) != 3) continue;
                    long at = entry.getLong(8), length = entry.getLong(32);
                    if (at < 0 || length < 2 || length > 512 || at > file.length() - length) return false;
                    byte[] name = new byte[(int)length]; file.seek(at); file.readFully(name);
                    if (name[name.length - 1] != 0) return false;
                    String path = new String(name, 0, name.length - 1, StandardCharsets.UTF_8);
                    if (!path.startsWith("/") || path.indexOf('\0') >= 0) return false;
                    File loader = resolver.resolve(path);
                    return loader.isFile() && loader.canRead()
                            && resolver.resolve("/root").isDirectory();
                }
                return false;
            }
        } catch (IOException | RuntimeException error) { return false; }
    }
}
