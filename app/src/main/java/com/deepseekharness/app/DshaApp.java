package com.deepseekharness.app;

import android.app.Application;
import android.content.Context;

import androidx.appcompat.app.AppCompatDelegate;

/** 跟随系统亮色 / 暗色。 */
public class DshaApp extends Application {

    private static final java.util.concurrent.atomic.AtomicBoolean CRASH_HOOK_INSTALLED =
            new java.util.concurrent.atomic.AtomicBoolean(false);

    /** 崩溃写入（公共入口，供 Activity 复用，避免每次 onCreate 重新包 handler 造成重复记录）。 */
    public static void writeCrashLog(Context ctx, Throwable t) {
        try {
            java.io.File f = new java.io.File(ctx.getFilesDir(), "crash.log");
            if (f.exists() && f.length() > 1024 * 1024) {
                // 轮转：超 1MB 移到 .prev（只保留最近一份，防止撑爆私有空间）
                java.io.File prev = new java.io.File(ctx.getFilesDir(), "crash.log.prev");
                //noinspection ResultOfMethodCallIgnored
                prev.delete();
                //noinspection ResultOfMethodCallIgnored
                f.renameTo(prev);
            }
            try (java.io.FileOutputStream fos = new java.io.FileOutputStream(f, true)) {
                fos.write(("\n===== " + new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.US)
                        .format(new java.util.Date()) + " =====\n"
                        + android.util.Log.getStackTraceString(t) + "\n").getBytes(java.nio.charset.StandardCharsets.UTF_8));
            }
        } catch (Exception ignored) {
        }
        mirrorCrashLog(ctx, t);
    }

    /** 把崩溃堆栈同时落到用户拿得到的两个地方。
     *
     *  起因：有用户反馈闪退，我们让他取 crash.log —— 但它在 App 私有目录里，
     *  没有 root 的文件管理器读不到，内置终端也读不到（容器只 bind 了
     *  /dev、/proc、/sys、/storage/emulated/0，App 私有目录不在其中，
     *  而 rootfs 本身就在私有目录里，proot 看不到它的上层）。
     *  结果就是「知道有日志，但拿不出来」，只能靠猜 —— 这一轮就卡在这里。
     *
     *  两个镜像各解决一种取法：
     *   · /sdcard/Documents/dshdata/crash.log —— 文件管理器直接可见、可分享
     *   · rootfs 内 /root/.dsh/crash.log      —— 内置终端 cat 就能看，
     *     容器里的 agent 也能自己读到并帮忙分析
     *
     *  两处都是**尽力而为**：写不进去（缺权限 / rootfs 还没解压）就算了，
     *  绝不能影响主记录，更不能在崩溃路径上再抛一次异常。 */
    private static void mirrorCrashLog(Context ctx, Throwable t) {
        String text = null;
        try {
            text = "\n===== " + new java.text.SimpleDateFormat(
                    "yyyy-MM-dd HH:mm:ss", java.util.Locale.US).format(new java.util.Date())
                    + " =====\n" + android.util.Log.getStackTraceString(t) + "\n";
        } catch (Throwable ignored) {
            return;
        }
        // ① 公开目录：文件管理器可见（需要「所有文件访问」，没给就静默跳过）
        try {
            java.io.File pub = new java.io.File(
                    "/storage/emulated/0/Documents/dshdata");
            if (pub.isDirectory() || pub.mkdirs()) {
                appendCapped(new java.io.File(pub, "crash.log"), text);
            }
        } catch (Throwable ignored) {
        }
        // ② rootfs 内：内置终端与容器里的 agent 都能读
        try {
            java.io.File dsh = new java.io.File(ctx.getFilesDir(),
                    "linux/ubuntu/root/.dsh");
            if (dsh.isDirectory()) {
                appendCapped(new java.io.File(dsh, "crash.log"), text);
            }
        } catch (Throwable ignored) {
        }
    }

    /** 追加写，超 512KB 先清空（镜像只为取证，不需要长期历史）。 */
    private static void appendCapped(java.io.File f, String text) throws java.io.IOException {
        if (f.isFile() && f.length() > 512 * 1024) {
            //noinspection ResultOfMethodCallIgnored
            f.delete();
        }
        try (java.io.FileOutputStream fos = new java.io.FileOutputStream(f, true)) {
            fos.write(text.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        }
    }

    /**
     * 给自检用：crash.log 尾部的摘要。
     *
     * <p>「没有记录」本身也是一条结论：说明闪退不是 Java 层的未捕获异常，而是内存不足被
     * 系统杀（解压 300MB 内置环境时最容易碰上）、native 崩溃（proot / node / 终端的
     * libtermux）或 ANR 后强杀 —— 这三种都不留 Java 堆栈，排查方向完全不同。
     */
    public static String recentCrashSummary(Context ctx, int maxEntries) {
        StringBuilder sb = new StringBuilder("\n===== 崩溃记录 =====\n");
        java.io.File f = new java.io.File(ctx.getFilesDir(), "crash.log");
        if (!f.isFile() || f.length() == 0) {
            sb.append("OK   crash.log 空的 —— 没发生过 Java 层未捕获异常。\n");
            sb.append("     若确实闪退过，方向在别处：①内存不足被系统杀（解压内置环境时最常见）"
                    + "②native 崩溃（proot / node / 终端的 libtermux）③ANR 后强杀。"
                    + "这三种都不留 Java 堆栈。\n");
            return sb.toString();
        }
        try {
            // 只读尾部：这文件是追加写的，可能接近 1MB
            int tail = (int) Math.min(f.length(), 32 * 1024);
            byte[] buf = new byte[tail];
            try (java.io.RandomAccessFile raf = new java.io.RandomAccessFile(f, "r")) {
                raf.seek(f.length() - tail);
                raf.readFully(buf);
            }
            String text = new String(buf, java.nio.charset.StandardCharsets.UTF_8);
            String[] parts = text.split("\n===== ");
            int from = Math.max(1, parts.length - maxEntries);   // parts[0] 可能是被截断的半段
            sb.append("WARN crash.log 有内容（").append(f.length() >> 10).append("KB），最近 ")
                    .append(parts.length - from).append(" 段：\n");
            for (int i = from; i < parts.length; i++) {
                String[] lines = parts[i].split("\n");
                int shown = 0;
                for (String ln : lines) {
                    if (ln.trim().isEmpty()) continue;
                    sb.append("  ").append(ln.trim()).append('\n');
                    // 时间行 + 异常类型 + 前两个栈帧，够定位到文件行号
                    if (++shown >= 4) break;
                }
                sb.append('\n');
            }
        } catch (Throwable e) {
            sb.append("WARN crash.log 存在但读不出来：").append(e).append('\n');
        }
        sb.append("     完整日志：/sdcard/Documents/dshdata/crash.log"
                + "（文件管理器可直接打开、分享给我）\n");
        return sb.toString();
    }

    @Override
    public void onCreate() {
        super.onCreate();
        // 公开目录名要在任何人用到它之前定下来：内测变体（-PdshaBeta）走 DSHA-beta，
        // 否则它与正式版装在同一台机器上时会往同一个公开目录里写备份与会话镜像。
        PublicDirs.ROOT = BuildConfig.PUBLIC_ROOT;
        PublicDirs.DATA_DIR = BuildConfig.PUBLIC_DATA_DIR;
        AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM);
        if (CRASH_HOOK_INSTALLED.compareAndSet(false, true)) {
            Thread.UncaughtExceptionHandler prev = Thread.getDefaultUncaughtExceptionHandler();
            Thread.setDefaultUncaughtExceptionHandler((thread, t) -> {
                writeCrashLog(this, t);
                if (prev != null) {
                    prev.uncaughtException(thread, t);
                } else {
                    android.os.Process.killProcess(android.os.Process.myPid());
                }
            });
        }
    }
}
