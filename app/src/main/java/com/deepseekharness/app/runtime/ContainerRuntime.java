package com.deepseekharness.app.runtime;

import android.content.Context;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

/**
 * 容器运行时抽象：把「怎么进 rootfs 执行命令」独立出来，让 proot 与 proroot 并存、可切换。
 *
 * <p>两条实现：
 * <ul>
 *   <li>{@link Proot} —— Termux 的 proot（APK 内置 libproot.so），默认稳定路径；</li>
 *   <li>{@link Proroot} —— coderredlab/proroot，LD_PRELOAD + 二进制补丁，零 ptrace 开销。</li>
 * </ul>
 *
 * <p>proroot 的 argv 里<b>没有</b> {@code --kill-on-exit}：{@code Process.destroy()} 只杀启动器，
 * 容器里的 node 会变孤儿继续跑 —— 所以「停止」必须按 pid 精确杀（见 {@code WebProcSel}），
 * 不能指望杀启动器传播信号。
 */
public interface ContainerRuntime {

    String id();
    String displayName();
    boolean available();
    String unavailableReason();

    /** 组装进入 rootfs 的命令前缀（不含最终要跑的 /bin/bash …）。 */
    List<String> baseArgv(File rootfsDir, boolean hardlinkSupported);

    /** 设置进程环境（LD_LIBRARY_PATH、TMPDIR 之类）。 */
    void applyEnv(ProcessBuilder pb, File baseDir, File libDir, File tmpDir);

    /** 首次使用前的准备。抛异常表示失败，调用方应回退。 */
    void prepare() throws Exception;

    // ==================================================================

    /** Termux proot，APK 内置。 */
    class Proot implements ContainerRuntime {
        private final File nativeLibProot;

        public Proot(Context ctx, File nativeLibProot) {
            this.nativeLibProot = nativeLibProot;
        }

        @Override public String id() { return "proot"; }

        @Override public String displayName() { return com.deepseekharness.app.util.UiText.text("proot（内置，稳定）"); }

        @Override public boolean available() {
            return nativeLibProot != null && nativeLibProot.exists();
        }

        @Override public String unavailableReason() {
            return available() ? "" : com.deepseekharness.app.util.UiText.text("APK 内的 libproot.so 缺失（安装包可能损坏，建议重装）");
        }

        @Override public List<String> baseArgv(File rootfsDir, boolean hardlinkSupported) {
            List<String> argv = new ArrayList<>();
            argv.add(nativeLibProot.getAbsolutePath());
            // 只有文件系统不支持硬链接时才需要 link2symlink 模拟（会破坏 dsh write 工具）。
            // Android app 私有目录（/data/…，ext4/f2fs）本来就支持硬链接，扩展纯属多余。
            if (!hardlinkSupported) {
                argv.add("--link2symlink");
                // L2S 链保存宿主绝对路径，容器必须能按同一路径访问，否则 dpkg 的 chown/stat 报 ENOENT。
                File l2s = new File(rootfsDir, ".l2s");
                l2s.mkdirs();
                argv.add("-b");
                argv.add(l2s.getAbsolutePath() + ":" + l2s.getAbsolutePath());
            }
            argv.add("-L");
            argv.add("--kill-on-exit");
            argv.add("-0");
            argv.add("--rootfs=" + rootfsDir.getAbsolutePath());
            argv.add("--cwd=/root");
            for (String[] b : BINDS) {
                if (!new File(b[0]).exists()) continue;
                argv.add("-b");
                argv.add(b.length == 1 ? b[0] : b[0] + ":" + b[1]);
            }
            UserDataBindings.append(argv,rootfsDir);return argv;
        }

        @Override public void applyEnv(ProcessBuilder pb, File baseDir, File libDir, File tmpDir) {
            // proot 专用变量由 ProotBootstrap.applyProotEnv 统一处理
        }

        @Override public void prepare() {
            // 内置库由 ProotBootstrap.ensureRuntimeFiles 复制
        }
    }

    /** coderredlab/proroot，LD_PRELOAD 路径翻译，零 ptrace。 */
    class Proroot implements ContainerRuntime {
        /** 五个 .so 都得在同一目录，启动器靠 /proc/self/exe 的 dirname 找同伴。 */
        public static final String[] LIBS = {
                "libproroot.so",
                "libproroot-runtime.so",
                "libproroot-linker.so",
                "libproroot-stub-loader.so",
                "libproroot-bridge.so",
        };

        private final Context ctx;
        private final File dir;
        private final boolean staticLoader;

        public Proroot(Context ctx, File dir) {
            this.ctx = ctx;
            this.dir = dir;
            this.staticLoader = new com.deepseekharness.app.core.ConfigStore(ctx).isProrootStaticLoader();
        }

        /**
         * 存放目录：APK 的 jniLibs 提取目录（nativeLibraryDir）。
         * <b>不能放 filesDir</b>：Android 10+ 的 W^X 策略不允许从应用可写目录执行代码。
         */
        public static File defaultDir(Context ctx) {
            return new File(ctx.getApplicationInfo().nativeLibraryDir);
        }

        @Override public String id() { return "proroot"; }

        @Override public String displayName() { return com.deepseekharness.app.util.UiText.text("proroot（实验，零 ptrace 开销）"); }

        @Override public boolean available() {
            for (String n : LIBS) {
                File f = new File(dir, n);
                if (!f.isFile() || f.length() == 0) return false;
            }
            return true;
        }

        @Override public String unavailableReason() {
            List<String> missing = new ArrayList<>();
            for (String n : LIBS) {
                File f = new File(dir, n);
                if (!f.isFile() || f.length() == 0) missing.add(n);
            }
            if (missing.isEmpty()) return "";
            return com.deepseekharness.app.util.UiText.text("缺 ") + missing.size() + com.deepseekharness.app.util.UiText.text(" 个运行时文件（") + missing.get(0) + com.deepseekharness.app.util.UiText.text(" 等）");
        }

        @Override public List<String> baseArgv(File rootfsDir, boolean hardlinkSupported) {
            List<String> argv = new ArrayList<>();
            argv.add(new File(dir, "libproroot.so").getAbsolutePath());
            argv.add(staticLoader
                    ? "--static-loader" : "--no-static-loader");
            argv.add("-r");
            argv.add(rootfsDir.getAbsolutePath());
            argv.add("-0");
            argv.add("-w");
            argv.add("/root");
            for (String[] b : BINDS) {
                if (!new File(b[0]).exists()) continue;
                argv.add("-b");
                argv.add(b.length == 1 ? b[0] + ":" + b[0] : b[0] + ":" + b[1]);
            }
            File shm = shmDir();
            //noinspection ResultOfMethodCallIgnored
            shm.mkdirs();
            argv.add("-b");
            argv.add(shm.getAbsolutePath() + ":/dev/shm");
            argv.add("--link2symlink");
            UserDataBindings.append(argv,rootfsDir);return argv;
        }

        File shmDir() {
            return new File(ctx.getCacheDir(), "shm");
        }

        @Override public void applyEnv(ProcessBuilder pb, File baseDir, File libDir, File tmpDir) {
            pb.environment().put("PROROOT_TMP_DIR", tmpDir.getAbsolutePath());
            pb.environment().put("PROROOT_LIB_PATH",
                    new File(dir, "libproroot-runtime.so").getAbsolutePath());
            pb.environment().put("PROROOT_LINKER_PATH",
                    new File(dir, "libproroot-linker.so").getAbsolutePath());
            if(staticLoader)
                pb.environment().put("PROROOT_STUB_LOADER",new File(dir,"libproroot-stub-loader.so").getAbsolutePath());
            else pb.environment().remove("PROROOT_STUB_LOADER");
        }

        @Override public void prepare() throws Exception {
            for (String n : LIBS) {
                if (!new File(dir, n).isFile()) {
                    throw new IllegalStateException(com.deepseekharness.app.util.UiText.text("proroot 运行时缺 ") + n);
                }
            }
            //noinspection ResultOfMethodCallIgnored
            shmDir().mkdirs();
        }
    }

    /**
     * 两个运行时共用的 bind 列表，写在一处（避免「改了 proot 忘了改 proroot」）。
     * Bundled Termux Python 要用 Android 的 linker 和 APEX 库，所以映射 /system、/apex。
     *
     * <p>注意：<b>不</b>映射 /linkerconfig —— app 进程对它无权限（selinux），
     * proot 每次启动都会打 {@code can't sanitize binding "/linkerconfig": Permission denied}，
     * 污染终端/日志；而且 bind 失败 = 从没绑上，映射它是纯负收益。
     */
    String[][] BINDS = {
            {"/dev"},
            {"/dev/urandom", "/dev/random"},
            {"/proc"},
            {"/sys"},
            {"/system"},
            {"/apex"},
            {"/proc/self/fd", "/dev/fd"},
            {"/storage/emulated/0", "/sdcard"},
            {"/storage/emulated/0", "/storage/emulated/0"},
    };
}
