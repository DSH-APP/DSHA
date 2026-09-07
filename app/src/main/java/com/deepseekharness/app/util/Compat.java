package com.deepseekharness.app.util;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

/**
 * {@code java.nio.file}（API 26 才引入）在 minSdk 23 上的兼容替身。
 *
 * <p>core library desugaring 能回退 {@code java.util.function} / {@code java.time}，
 * 但覆盖不到 {@code java.nio.file} —— Files/Path/toPath 在 Android 6 上运行时会
 * {@code NoSuchMethodError}。这里用纯 {@code java.io} 提供等价能力，替换全树的
 * Files 调用（readAllBytes/write/append），保持读 UTF-8 的语义不变。
 */
public final class Compat {

    private Compat() {
    }

    public static byte[] readAllBytes(File f) throws IOException {
        try (FileInputStream in = new FileInputStream(f);
             ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) != -1) out.write(buf, 0, n);
            return out.toByteArray();
        }
    }

    public static String readAll(File f) throws IOException {
        return new String(readAllBytes(f), StandardCharsets.UTF_8);
    }

    public static void write(File f, byte[] data) throws IOException {
        try (FileOutputStream out = new FileOutputStream(f)) {
            out.write(data);
        }
    }

    public static void write(File f, String s) throws IOException {
        write(f, s.getBytes(StandardCharsets.UTF_8));
    }

    public static void append(File f, byte[] data) throws IOException {
        try (FileOutputStream out = new FileOutputStream(f, true)) {
            out.write(data);
        }
    }

    public static void append(File f, String s) throws IOException {
        append(f, s.getBytes(StandardCharsets.UTF_8));
    }

    /** 复制文件（可覆盖）。 */
    public static void copy(File src, File dst, boolean replace) throws IOException {
        if (replace && dst.exists() && !dst.delete()) {
            // 删不掉继续覆盖写（FileOutputStream 默认覆盖）
        }
        try (FileInputStream in = new FileInputStream(src);
             FileOutputStream out = new FileOutputStream(dst)) {
            byte[] b = new byte[65536];
            int n;
            while ((n = in.read(b)) != -1) out.write(b, 0, n);
        }
    }

    /** 建符号链接（android.system.Os 自 API 21 可用，替代 API 26 的 Files.createSymbolicLink）。 */
    public static void symlink(String target, File link) throws IOException {
        try {
            android.system.Os.symlink(target, link.getAbsolutePath());
        } catch (android.system.ErrnoException e) {
            throw new IOException("symlink 失败: " + link + " -> " + target + ": " + e.getMessage());
        }
    }

    /** 硬链接（替代 API 26 的 Files.createLink）。 */
    public static void link(File existing, File newLink) throws IOException {
        try {
            android.system.Os.link(existing.getAbsolutePath(), newLink.getAbsolutePath());
        } catch (android.system.ErrnoException e) {
            throw new IOException("link 失败: " + existing + " -> " + newLink + ": " + e.getMessage());
        }
    }

    /** 判断是否符号链接（替代 API 26 的 Files.isSymbolicLink）。 */
    public static boolean isSymbolicLink(File f) {
        try {
            android.system.StructStat st = android.system.Os.lstat(f.getAbsolutePath());
            return android.system.OsConstants.S_ISLNK(st.st_mode);
        } catch (Throwable e) {
            return false;
        }
    }

    /** 按 POSIX 权限串（如 "rwxr-xr-x"）chmod（替代 API 26 的 Files.setPosixFilePermissions）。 */
    public static void chmod(File f, String perms) throws IOException {
        int mode = 0;
        for (int i = 0; i < perms.length() && i < 9; i++) {
            char c = perms.charAt(i);
            if ("rwxrwxrwx".charAt(i) == c) mode |= (1 << (8 - i));
        }
        try {
            android.system.Os.chmod(f.getAbsolutePath(), mode);
        } catch (android.system.ErrnoException e) {
            throw new IOException("chmod 失败: " + f + ": " + e.getMessage());
        }
    }

    // ============ POSIX 路径（替代 API 26 的 java.nio.file.Path） ============

    /** 规范化 POSIX 路径（处理 ./ 和 ../，保留前导 /，不解析符号链接）。 */
    public static String normalizePosix(String path) {
        if (path == null || path.isEmpty()) return path;
        String p = path.replace('\\', '/');
        boolean abs = p.startsWith("/");
        String[] parts = p.split("/");
        java.util.ArrayList<String> stack = new java.util.ArrayList<>();
        for (String seg : parts) {
            if (seg.isEmpty() || seg.equals(".")) continue;
            if (seg.equals("..")) {
                if (!stack.isEmpty() && !stack.get(stack.size() - 1).equals("..")) {
                    stack.remove(stack.size() - 1);
                } else if (!abs) {
                    stack.add("..");
                }
            } else {
                stack.add(seg);
            }
        }
        StringBuilder sb = new StringBuilder();
        if (abs) sb.append('/');
        for (int i = 0; i < stack.size(); i++) {
            if (i > 0) sb.append('/');
            sb.append(stack.get(i));
        }
        return sb.length() == 0 ? (abs ? "/" : "") : sb.toString();
    }

    /** 计算 target 相对 base 的 POSIX 相对路径（等效 java.nio.file.Path.relativize）。 */
    public static String relativizePosix(String base, String target) {
        String b = normalizePosix(base);
        String t = normalizePosix(target);
        String[] bp = b.equals("/") ? new String[0] : b.split("/");
        String[] tp = t.equals("/") ? new String[0] : t.split("/");
        int common = 0;
        while (common < bp.length && common < tp.length && bp[common].equals(tp[common])) common++;
        StringBuilder sb = new StringBuilder();
        for (int i = common; i < bp.length; i++) {
            if (sb.length() > 0) sb.append('/');
            sb.append("..");
        }
        for (int i = common; i < tp.length; i++) {
            if (sb.length() > 0) sb.append('/');
            sb.append(tp[i]);
        }
        return sb.length() == 0 ? "." : sb.toString();
    }

    // ============ Process（Process.isAlive/destroyForcibly/waitFor 是 API 26） ============

    /** 仅终止传入进程并等待退出；Android 的 destroyForcibly 可能仍只发 SIGTERM。 */
    public static void destroy(Process p) {
        ProcessIdentity target = ownedProcess(p);
        if (target != null) {
            boolean proot = false;
            try { proot = ProcessIdentity.isProot(android.system.Os.readlink("/proc/" + target.pid + "/exe")); }
            catch (android.system.ErrnoException ignored) { }
            // proot 忽略 SIGTERM；SIGQUIT 会杀掉它自己登记的 tracee，再由事件循环退出。
            // 先给它完成清理的机会，不能直接 KILL 启动器而留下原有 tracee。
            Runnable graceful = proot ? () -> signalOwned(p, target, android.system.OsConstants.SIGQUIT) : null;
            ProcessTermination.stop(p, graceful, () -> signalOwned(p, target, android.system.OsConstants.SIGKILL), 750, 2000);
            return;
        }
        // 未能核验 PID 时只使用该对象的公开销毁方法，不猜 PID、不扫描或按名称杀进程。
        ProcessTermination.stop(p, null, () -> {
            if (android.os.Build.VERSION.SDK_INT >= 26) p.destroyForcibly(); else p.destroy();
        }, 0, 2000);
    }

    private static ProcessIdentity ownedProcess(Process process) {
        synchronized (process) {
            if (ProcessTermination.exited(process)) return null;
            int pid = ProcessIdentity.androidPid(process.getClass().getName(), process.toString());
            return readProcessIdentity(pid);
        }
    }
    private static ProcessIdentity readProcessIdentity(int pid) {
        if (pid <= 1 || pid == android.os.Process.myPid()) return null;
        try {
            return ProcessIdentity.fromStat(readAll(new File("/proc/" + pid + "/stat")), pid, android.os.Process.myPid());
        } catch (IOException | RuntimeException ignored) { return null; }
    }
    private static void signalOwned(Process process, ProcessIdentity target, int signal) {
        synchronized (process) {
            if (ProcessTermination.exited(process)) return;
            if (!target.sameProcess(readProcessIdentity(target.pid)))
                throw new IllegalStateException("本次进程身份已变化或不可确认，未发送信号");
            try { android.system.Os.kill(target.pid, signal); }
            catch (android.system.ErrnoException error) {
                if (error.errno != android.system.OsConstants.ESRCH)
                    throw new IllegalStateException("无法终止本次进程", error);
            }
        }
    }

    /** 判断进程是否存活：API 26+ isAlive，旧版用 exitValue 探活。 */
    public static boolean isAlive(Process p) {
        if (android.os.Build.VERSION.SDK_INT >= 26) return p.isAlive();
        try {
            p.exitValue();
            return false;
        } catch (IllegalThreadStateException e) {
            return true;
        }
    }

    /** 等待进程结束，返回是否在超时前退出（API 26+ 才有 waitFor(long, TimeUnit)）。 */
    public static boolean waitFor(Process p, long timeoutMs) {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            if (!isAlive(p)) return true;
            try {
                Thread.sleep(50);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return !isAlive(p);
            }
        }
        return !isAlive(p);
    }

    /** 给 ProcessBuilder 设 stdin=/dev/null（API 26+ Redirect.from；旧版不设，继承即可）。 */
    public static void redirectStdinDevNull(ProcessBuilder pb) {
        if (android.os.Build.VERSION.SDK_INT >= 26) {
            try {
                pb.redirectInput(ProcessBuilder.Redirect.from(new java.io.File("/dev/null")));
            } catch (Throwable ignored) {
            }
        }
    }
}
