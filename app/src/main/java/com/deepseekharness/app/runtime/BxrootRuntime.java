package com.deepseekharness.app.runtime;

import android.content.Context;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

/**
 * bxroot 运行时（MIT，LD_PRELOAD 进程内路径翻译，零 ptrace）。
 *
 * <p>与 {@link Proroot} 同为五件套结构，但启动形态不同：
 * bxroot 的 {@code libbxroot.so} 是一个<b>静态链接的启动器二进制</b>
 * （伪装成 .so 以便放进 jniLibs），CLI 与 proot 同构
 * （{@code -r <rootfs> -w /root -0 --link2symlink -b host:guest …}），
 * 由它自行派生全部 {@code BXROOT_*} 环境变量并以
 * {@code LD_PRELOAD=libbxroot-runtime.so} 启动 guest。
 *
 * <p>因此本实现类只需要设置 launcher 无法从 argv 派生的少量变量
 * （契约见 bxroot 仓库 {@code docs/DSHA-适配说明.md}）：
 * {@code BXROOT_LIB_PATH}（显式 runtime 库路径，优先于 launcher 的
 * {@code dirname(/proc/self/exe)} 自动探测）、{@code BXROOT_TMP_DIR}、
 * {@code BXROOT_L2S_DIR}。
 *
 * <p><b>绝不能</b>设置 {@code BXROOT_GUEST_EXE}：launcher 以 argv 为准，
 * 两边都设会使「以 argv 为准」失效（bxroot 侧文档明确点名）。
 * 也<b>不要</b>设置任何 {@code PROROOT_*} 变量——bxroot 只读
 * {@code BXROOT_*}，proroot 专有名对它是无效输入。
 */
public class BxrootRuntime implements ContainerRuntime {
    /** 五件套：启动器（静态二进制）+ 运行时钩子 + 自研加载器三件。 */
    public static final String[] LIBS = {
            "libbxroot.so",
            "libbxroot-runtime.so",
            "libbxroot-linker.so",
            "libbxroot-bridge.so",
            "libbxroot-stub-loader.so",
    };

    private final Context ctx;
    private final File dir;

    public BxrootRuntime(Context ctx, File dir) {
        this.ctx = ctx;
        this.dir = dir;
    }

    /** 存放目录：APK 的 jniLibs 提取目录（nativeLibraryDir）。 */
    public static File defaultDir(Context ctx) {
        return new File(ctx.getApplicationInfo().nativeLibraryDir);
    }

    @Override public String id() { return "bxroot"; }

    @Override public String displayName() { return com.deepseekharness.app.util.UiText.text("bxroot（实验，LD_PRELOAD 零 ptrace）"); }

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
        argv.add(new File(dir, "libbxroot.so").getAbsolutePath());
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
        // 与 proroot 同款：Android app 私有目录禁 link(2)（SELinux），无条件开硬链接模拟。
        argv.add("--link2symlink");
        return argv;
    }

    File shmDir() {
        return new File(ctx.getCacheDir(), "shm");
    }

    @Override public void applyEnv(ProcessBuilder pb, File baseDir, File libDir, File tmpDir) {
        // 显式 runtime 库路径：优先于 launcher 的 dirname(/proc/self/exe) 自动探测。
        // jniLibs 目录里没有其它 app 的同名库，路径无歧义。
        pb.environment().put("BXROOT_LIB_PATH",
                new File(dir, "libbxroot-runtime.so").getAbsolutePath());
        // linker / stub-loader 可选：launcher 只在文件存在时启用，缺失打印警告不致命。
        File linker = new File(dir, "libbxroot-linker.so");
        if (linker.isFile()) {
            pb.environment().put("BXROOT_LINKER_PATH", linker.getAbsolutePath());
        }
        File stub = new File(dir, "libbxroot-stub-loader.so");
        if (stub.isFile()) {
            pb.environment().put("BXROOT_STUB_LOADER", stub.getAbsolutePath());
        }
        pb.environment().put("BXROOT_TMP_DIR", tmpDir.getAbsolutePath());
        // 与 DSHA 的 PROOT_L2S_DIR 约定对齐：集中目录 <rootfs>/.l2s。
        // launcher 未设时也会兜底到同一路径，这里显式设置是 bxroot 适配说明的要求。
        // rootfsDir = <filesDir>/linux/ubuntu = baseDir/ubuntu（见 ProotBootstrap 构造）。
        File rootfs = new File(baseDir, "ubuntu");
        File l2s = new File(rootfs, ".l2s");
        //noinspection ResultOfMethodCallIgnored
        l2s.mkdirs();
        pb.environment().put("BXROOT_L2S_DIR", l2s.getAbsolutePath());
    }

    @Override public void prepare() throws Exception {
        for (String n : LIBS) {
            if (!new File(dir, n).isFile()) {
                throw new IllegalStateException(com.deepseekharness.app.util.UiText.text("bxroot 运行时缺 ") + n);
            }
        }
        //noinspection ResultOfMethodCallIgnored
        shmDir().mkdirs();
    }
}
