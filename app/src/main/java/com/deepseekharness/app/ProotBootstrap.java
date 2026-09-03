package com.deepseekharness.app;

import android.content.Context;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.IntConsumer;

/**
 * ProotBootstrap — 一体式 Linux 环境管理（PRoot 方案）。
 *
 * 关键设计（参考 openclaw-termux）：
 * proot、loader、libtalloc 伪装成 lib*.so 放入 jniLibs，Android 安装时
 * 自动解压到 nativeLibraryDir（可执行目录，绕过 App 私有目录的 noexec）。
 * 运行时通过 PROOT_LOADER / PROOT_TMP_DIR / LD_LIBRARY_PATH 环境变量
 * 引导 proot 找到 loader 与依赖库，直接 exec nativeLibraryDir/libproot.so。
 */
public class ProotBootstrap {

    public static final String[] ROOTFS_URLS = {
            // 多镜像源（安装时并行测速，弹窗让你自选；全部实测可用）
            "https://mirror.nju.edu.cn/ubuntu-cdimage/ubuntu-base/releases/24.04.4/release/ubuntu-base-24.04.4-base-arm64.tar.gz",
            "https://mirrors.hit.edu.cn/ubuntu-cdimage/ubuntu-base/releases/24.04.4/release/ubuntu-base-24.04.4-base-arm64.tar.gz",
            "https://mirrors.aliyun.com/ubuntu-cdimage/ubuntu-base/releases/24.04.4/release/ubuntu-base-24.04.4-base-arm64.tar.gz",
            "https://mirrors.tuna.tsinghua.edu.cn/ubuntu-cdimage/ubuntu-base/releases/24.04.4/release/ubuntu-base-24.04.4-base-arm64.tar.gz",
            "https://mirrors.huaweicloud.com/ubuntu-cdimage/ubuntu-base/releases/24.04.4/release/ubuntu-base-24.04.4-base-arm64.tar.gz",
            "https://mirrors.bfsu.edu.cn/ubuntu-cdimage/ubuntu-base/releases/24.04.4/release/ubuntu-base-24.04.4-base-arm64.tar.gz",
            "https://cdimage.ubuntu.com/ubuntu-base/releases/24.04.4/release/ubuntu-base-24.04.4-base-arm64.tar.gz",
            "https://cdimage.ubuntu.com/ubuntu-base/releases/24.04.3/release/ubuntu-base-24.04.3-base-arm64.tar.gz"
    };

    /** Node.js arm64 镜像（多源，并行测速 + 自选；全部实测可用） */
    public static final String[] NODE_URLS = {
            "https://mirrors.huaweicloud.com/nodejs/v24.19.0/node-v24.19.0-linux-arm64.tar.xz",
            "https://npmmirror.com/mirrors/node/v24.19.0/node-v24.19.0-linux-arm64.tar.xz",
            "https://mirrors.aliyun.com/nodejs-release/v24.19.0/node-v24.19.0-linux-arm64.tar.xz",
            "https://cdn.npmmirror.com/binaries/node/v24.19.0/node-v24.19.0-linux-arm64.tar.xz",
            "https://mirror.nju.edu.cn/nodejs-release/v24.19.0/node-v24.19.0-linux-arm64.tar.xz",
            "https://mirrors.cloud.tencent.com/nodejs-release/v24.19.0/node-v24.19.0-linux-arm64.tar.xz",
            "https://mirror.sjtu.edu.cn/nodejs-release/v24.19.0/node-v24.19.0-linux-arm64.tar.xz",
            "https://nodejs.org/dist/v24.19.0/node-v24.19.0-linux-arm64.tar.xz"
    };

    /** deepseek-harness 安装源：预构建包 + 直连 GitHub 源码构建（特殊项 git://） */
    public static final String[] HARNESS_URLS = {
            // 预构建包源已暂停：catbox 匿名站包体被污染(损坏/含 WSL 脚本)，不再信任
            // 一律走「直连 GitHub 源码构建」保证可靠
            "git://github.com/deepseek-ai/deepseek-harness",
    };

    private final Context ctx;
    private final File baseDir;
    private final File rootfsDir;
    private final File libDir;
    private final File tmpDir;
    private final String nativeLibDir;
    private final File markerFile;

    public ProotBootstrap(Context c) {
        ctx = c.getApplicationContext();
        baseDir = new File(ctx.getFilesDir(), "linux");
        rootfsDir = new File(baseDir, "ubuntu");
        libDir = new File(baseDir, "lib");
        tmpDir = new File(baseDir, "tmp");
        nativeLibDir = ctx.getApplicationInfo().nativeLibraryDir;
        markerFile = new File(baseDir, ".installed");
    }

    public File getRootfsDir() { return rootfsDir; }

    /** 硬链接探测结果缓存（null=未探测）。见 {@link #hardlinkSupported()} */
    private static volatile Boolean hardlinkOk = null;

    /**
     * rootfs 所在文件系统是否支持真实硬链接。
     *
     * 支持时 proot 不加 {@code --link2symlink}：该扩展会把 {@code link()} 的目标改写成
     * 「指向临时目录内中间文件（.l2s.*）的符号链接」，而 dsh 新建文件正是用
     * {@code link(临时文件, 目标)} 发布、随后立刻递归删除临时目录 —— 于是新建的文件
     * 100% 变成悬空链接（write 工具报成功但文件读不出来，edit 走 rename 所以不受影响）。
     * Android app 私有目录（/data/…，ext4/f2fs）本来就支持硬链接，扩展纯属多余。
     *
     * 探测失败（少数 ROM/文件系统真的不支持）时保留扩展，行为与旧版一致。
     */
    private boolean hardlinkSupported() {
        Boolean cached = hardlinkOk;
        if (cached != null) return cached;
        synchronized (ProotBootstrap.class) {
            if (hardlinkOk != null) return hardlinkOk;
            boolean ok = false;
            String detail = "";
            File dir = rootfsDir.isDirectory() ? rootfsDir : baseDir;
            File src = new File(dir, ".dsha-linkprobe");
            File dst = new File(dir, ".dsha-linkprobe.hl");
            try {
                dir.mkdirs();
                src.delete();
                dst.delete();
                java.nio.file.Files.write(src.toPath(), new byte[] { 'o', 'k' });
                java.nio.file.Files.createLink(dst.toPath(), src.toPath());
                ok = dst.isFile() && dst.length() == 2;
                if (!ok) detail = "link 成功但目标不可读";
            } catch (Throwable e) {
                ok = false;
                detail = e.getClass().getSimpleName() + ": " + e.getMessage();
                android.util.Log.w("DSHA", "硬链接探测失败，保留 --link2symlink: " + e);
            } finally {
                src.delete();
                dst.delete();
            }
            hardlinkOk = ok;
            android.util.Log.i("DSHA", "硬链接支持=" + ok + (detail.isEmpty() ? "" : "（" + detail + "）"));
            // 结果落盘，容器里 cat /root/.dsha-hardlink 就能看到判定依据（否则只能抓 logcat）
            try {
                File mark = new File(rootfsDir, "root/.dsha-hardlink");
                if (mark.getParentFile() != null) mark.getParentFile().mkdirs();
                java.nio.file.Files.write(mark.toPath(),
                        ((ok ? "ok" : "no") + (detail.isEmpty() ? "" : " " + detail) + "\n")
                                .getBytes(java.nio.charset.StandardCharsets.UTF_8));
            } catch (Throwable ignored) {
            }
            return ok;
        }
    }

    /** 不支持真实硬链接时，把 proot 的 l2s 中间文件集中到 rootfs 内固定目录。
     *  默认行为是「就近存放」——存在临时目录里的中间文件会随临时目录被删掉，
     *  正是 dsh write 新建文件变悬空的直接原因。 */
    private void applyL2sEnv(ProcessBuilder pb) {
        if (hardlinkSupported()) return;
        try {
            File l2s = new File(rootfsDir, ".l2s");
            //noinspection ResultOfMethodCallIgnored
            l2s.mkdirs();
            pb.environment().put("PROOT_L2S_DIR", l2s.getAbsolutePath());
        } catch (Throwable ignored) {
        }
    }

    /** proot 运行环境（两个 exec 入口共用，避免两处漂移） */
    private void applyProotEnv(ProcessBuilder pb) {
        ContainerRuntime rt;
        try {
            rt = runtime();
        } catch (Throwable e) {
            rt = new ContainerRuntime.Proot(ctx, findNativeLib("libproot.so"));
        }
        // ── proot 专用变量：**只在 proot 下设** ──
        // 之所以要分开：proroot 是 LD_PRELOAD 方案，对 LD_LIBRARY_PATH 尤其敏感 ——
        // 把它指向 proot 的库目录有可能干扰 proroot 自己的注入链。
        // PROOT_L2S_DIR 更直接：proroot 有自己的 anchor + symlink group 机制，
        // 塞一个 proot 语义的 l2s 目录进去只会添乱。
        // 这几个变量 proroot 压根不读，但「不读」和「不该出现」是两件事 ——
        // 这个项目已经在「两套机制的配置互相污染」上栽过（npmrc 覆盖冲掉 pnpm 修复）。
        if ("proot".equals(rt.id())) {
            pb.environment().put("PROOT_TMP_DIR", tmpDir.getAbsolutePath());
            applyL2sEnv(pb);
            pb.environment().put("PROOT_LOADER",
                    findNativeLib("libprootloader.so").getAbsolutePath());
            pb.environment().put("PROOT_LOADER_32",
                    findNativeLib("libprootloader32.so").getAbsolutePath());
            pb.environment().put("LD_LIBRARY_PATH",
                    libDir.getAbsolutePath() + ":" + findNativeLib("libproot.so").getParent());
        }
        // ── 运行时自己的变量（放在专用变量之后，允许覆盖）──
        try {
            rt.applyEnv(pb, baseDir, libDir, tmpDir);
        } catch (Throwable ignored) {
        }
        // ── 以下与运行时无关，是 guest 侧的环境 ──
        pb.environment().put("HOME", "/root");
        // guest 的 PATH（否则继承 Android 的 /system/bin，找不到 tail/apt 等）；
        // 前置 /root/dsh-bin = 危险命令确认包装器（DSH_CONFIRM=1 时拦截）
        pb.environment().put("PATH", "/root/dsh-bin:/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin");
        // TMPDIR 必须指向 guest 的 /tmp（否则 mktemp 会用 Android 的 cache 目录而失败）
        pb.environment().put("TMPDIR", "/tmp");
        pb.environment().put("DEBIAN_FRONTEND", "noninteractive");
    }

    /** 组装 proot 公共参数（两个 exec 入口共用，避免两处漂移） */
    /** 连续失败到这个次数就强制切回 proot 并清掉用户的选择。
     *  实验功能允许失败，但不能让人卡在一个起不来的环境里反复试。 */
    private static final int PROROOT_FAIL_LIMIT = 3;

    /** 记一次 proroot 启动失败；达到上限就自动切回 proot。返回是否触发了强制回退。 */
    public boolean noteProrootFailure(String why) {
        try {
            android.content.SharedPreferences sp = ctx.getSharedPreferences(
                    "deepseekharness", Context.MODE_PRIVATE);
            if (!"proroot".equals(sp.getString("container_runtime", "proroot"))) return false;
            int n = sp.getInt("proroot_fail_streak", 0) + 1;
            if (n >= PROROOT_FAIL_LIMIT) {
                sp.edit().putString("container_runtime", "proot")
                        .putInt("proroot_fail_streak", 0)
                        .putString("proroot_last_error", why == null ? "" : why)
                        .apply();
                android.util.Log.w("DSHA", "proroot 连续失败 " + n + " 次，已强制切回 proot：" + why);
                HarnessController.get(ctx).logActivity(
                        "运行时强制回退：proroot 连续失败 " + n + " 次已切回 proot —— " + why);
                return true;
            }
            sp.edit().putInt("proroot_fail_streak", n)
                    .putString("proroot_last_error", why == null ? "" : why).apply();
        } catch (Throwable ignored) {
        }
        return false;
    }

    /** 启动成功后清零计数 —— 否则偶发失败累积起来也会触发回退。 */
    public void noteProrootSuccess() {
        try {
            android.content.SharedPreferences sp = ctx.getSharedPreferences(
                    "deepseekharness", Context.MODE_PRIVATE);
            if (sp.getInt("proroot_fail_streak", 0) != 0) {
                sp.edit().putInt("proroot_fail_streak", 0).apply();
            }
        } catch (Throwable ignored) {
        }
    }

    /** 当前容器运行时。**默认 proroot**（真机实测启动快 5~6 倍）；
     *  它不可用（.so 缺失）时自动降回 proot，连续 3 次启动失败也会强制切回。
     *  也就是说默认值只影响「先试哪个」，不影响「能不能用」。 */
    public ContainerRuntime runtime() {
        try {
            android.content.SharedPreferences sp = ctx.getSharedPreferences(
                    "deepseekharness", Context.MODE_PRIVATE);
            if ("proroot".equals(sp.getString("container_runtime", "proroot"))) {
                ContainerRuntime pr = new ContainerRuntime.Proroot(
                        ctx, ContainerRuntime.Proroot.defaultDir(ctx));
                if (pr.available()) {
                    pr.prepare();
                    return pr;
                }
                android.util.Log.w("DSHA", "proroot 不可用，本次降回 proot: "
                        + pr.unavailableReason());
                HarnessController.get(ctx).logActivity(
                        "运行时降级：proroot 不可用，本次用 proot —— " + pr.unavailableReason());
            }
        } catch (Throwable e) {
            android.util.Log.w("DSHA", "选择运行时失败，降回 proot: " + e);
        }
        return new ContainerRuntime.Proot(ctx, findNativeLib("libproot.so"));
    }

    /** 只读地看一眼配置里选的是哪个（不做可用性判断，供 UI 显示）。 */
    public String preferredRuntimeId() {
        try {
            return ctx.getSharedPreferences("deepseekharness", Context.MODE_PRIVATE)
                    .getString("container_runtime", "proroot");
        } catch (Throwable e) {
            return "proroot";
        }
    }

    private java.util.List<String> baseProotArgv() {
        return runtime().baseArgv(rootfsDir, hardlinkSupported());
    }

    /**
     * PTY 会话的启动参数：{@code argv[0]} 是容器运行时的可执行文件，末尾接 guest 侧要跑的命令。
     *
     * <p>为什么单独开一个方法而不复用 {@link #execRootfs}：JNI 的 {@code createSubprocess}
     * 要的是 {@code (cmd, argv[], env[])} 三件套，而 execRootfs 是把同样的东西塞进
     * ProcessBuilder。两条路必须共用同一份 argv/env 构造逻辑 —— 各写一份的话，PTY 里的
     * shell 就会跑在和普通命令不一样的环境里，而这个项目已经在「同一份判断散落两处」上
     * 栽过四次。
     *
     * <p>argv[0] 保留成可执行文件自身的路径：查过 termux.c，它把 Java 数组原样转成
     * {@code argv} 后直接 {@code execvp(cmd, argv)}，没有任何加工 —— 和 ProcessBuilder 一致。
     */
    public String[] ptyArgv(String... guestCmd) {
        java.util.List<String> argv = baseProotArgv();
        if (guestCmd == null || guestCmd.length == 0) {
            // 某些 Android/容器运行时组合创建出来的 PTY 会保留 -echo，表现为输入时
            // 什么都看不到、回车后命令却照常执行。先在同一个 PTY 上恢复标准模式，
            // 再 exec 登录 shell；这条准备命令本身不会留下额外的中间进程。
            argv.add("/bin/bash");
            argv.add("-c");
            argv.add("stty sane 2>/dev/null || stty echo icanon 2>/dev/null || true; "
                    + "exec /bin/bash -l");
        } else {
            java.util.Collections.addAll(argv, guestCmd);
        }
        return argv.toArray(new String[0]);
    }

    /**
     * PTY 会话的环境变量（{@code KEY=VALUE} 形式）。
     *
     * <p>借一个临时 ProcessBuilder 来收集：环境变量的构造分散在 {@link #applyProotEnv}
     * 与 {@link ContainerRuntime#applyEnv} 两处，还随运行时（proot / proroot）分叉。
     * 重抄一份必然漏，而漏掉 PROOT_LOADER 这种就是直接启动失败。
     */
    public String[] ptyEnv() {
        ProcessBuilder probe = new ProcessBuilder("/system/bin/true");
        applyProotEnv(probe);
        java.util.Map<String, String> m = probe.environment();
        // PTY 会话必须有 TERM。没有它：clear / tput / reset 直接报
        // "TERM environment variable not set."，vim htop less 退化甚至拒绝启动，
        // 而 codex 这类 TUI 程序拿不到终端能力描述，光标控制与宽度判断全靠猜 ——
        // 真机上表现为排版错乱、欢迎横幅重复画两遍。
        // 这里原先只把 applyProotEnv 给 proot 自己准备的那几个变量原样导出，
        // 从来没有人设过 TERM。
        //
        // 值取 xterm-256color：rootfs 的 /usr/share/terminfo/x/ 里有这一项
        //（ncurses-base 自带，已在容器里实测 clear 能用），而且 Termux 的 TerminalView
        // 实现的转义序列基本按 xterm 那套来。
        // 下面几个都只在缺失时补，不覆盖 applyProotEnv 已经定好的值。
        if (!m.containsKey("TERM")) m.put("TERM", "xterm-256color");
        if (!m.containsKey("COLORTERM")) m.put("COLORTERM", "truecolor");
        if (!m.containsKey("LANG")) m.put("LANG", "C.UTF-8");
        if (!m.containsKey("HOME")) m.put("HOME", "/root");
        java.util.List<String> out = new java.util.ArrayList<>(m.size());
        for (java.util.Map.Entry<String, String> e : m.entrySet()) {
            if (e.getKey() == null || e.getValue() == null) continue;
            out.add(e.getKey() + "=" + e.getValue());
        }
        return out.toArray(new String[0]);
    }
    public boolean isInstalled() {
        return hasBash();
    }

    public boolean hasBash() {
        return new File(rootfsDir, "usr/bin/bash").isFile()
                || new File(rootfsDir, "bin/bash").isFile();
    }

    /** 内置包是否已经解压成功过（和「网上分步装了一半」区分开） */
    public boolean isOfflineExtracted() {
        return new File(baseDir, ".offline-extracted").isFile() && hasBash();
    }

    public void markOfflineExtracted() {
        markInstalled();
        File f = new File(baseDir, ".offline-extracted");
        try (FileOutputStream o = new FileOutputStream(f)) {
            o.write(("ok=" + System.currentTimeMillis() + "\n").getBytes());
        } catch (IOException ignored) {
        }
    }

    /** 把 APK 里找包的过程摊开，解压页可以直接显示，避免再猜。 */
    public String diagnoseBundle() {
        StringBuilder sb = new StringBuilder();
        sb.append("version=").append(versionName()).append('\n');
        sb.append("apk=").append(ctx.getPackageCodePath()).append('\n');
        File apk = new File(ctx.getPackageCodePath());
        sb.append("apkSize=").append(apk.isFile() ? apk.length() : -1).append('\n');
        try (java.util.zip.ZipFile z = new java.util.zip.ZipFile(apk)) {
            java.util.zip.ZipEntry hit = findBundleEntry(z);
            sb.append("zipHit=").append(hit == null ? "null" : hit.getName())
                    .append(" size=").append(hit == null ? -1 : hit.getSize()).append('\n');
            int n = 0;
            java.util.Enumeration<? extends java.util.zip.ZipEntry> en = z.entries();
            while (en.hasMoreElements() && n < 12) {
                java.util.zip.ZipEntry e = en.nextElement();
                String name = e.getName();
                if (name.contains("asset") || name.contains("offline") || name.contains("rootfs")
                        || name.endsWith(".gz")) {
                    sb.append("  ").append(name).append(" ").append(e.getSize()).append('\n');
                    n++;
                }
            }
        } catch (Exception e) {
            sb.append("zipErr=").append(e.getClass().getSimpleName())
                    .append(": ").append(e.getMessage()).append('\n');
        }
        try {
            String[] names = ctx.getAssets().list("");
            sb.append("assets.list=");
            if (names == null) sb.append("null\n");
            else {
                sb.append(names.length).append('\n');
                for (String s : names) sb.append("  ").append(s).append('\n');
            }
        } catch (Exception e) {
            sb.append("assetsErr=").append(e.getMessage()).append('\n');
        }
        return sb.toString();
    }

    private String versionName() {
        try {
            return ctx.getPackageManager().getPackageInfo(ctx.getPackageName(), 0).versionName;
        } catch (Exception e) {
            return "?";
        }
    }

    public boolean isHarnessInstalled(String workdir) {
        return new File(rootfsDir, "root/" + workdir + "/lib/bin.js").exists()
                || new File(rootfsDir, "root/" + workdir + "/apps/cli/lib/bin.js").exists();
    }

    /** 定位 native 库：nativeLibraryDir 优先，找不到则扫描 lib 根目录下各 ABI 子目录 */
    private File findNativeLib(String name) {
        File direct = new File(nativeLibDir, name);
        if (direct.isFile()) return direct;
        File libRoot = new File(nativeLibDir).getParentFile();
        if (libRoot != null && libRoot.isDirectory()) {
            File[] subs = libRoot.listFiles();
            if (subs != null) {
                for (File sub : subs) {
                    if (sub.isDirectory()) {
                        File f = new File(sub, name);
                        if (f.isFile()) return f;
                    }
                }
            }
        }
        return direct;
    }

    private String prootPath() {
        return findNativeLib("libproot.so").getAbsolutePath();
    }

    private void chmod(File f, int mode) {
        f.setReadable(true, false);
        f.setExecutable(true, false);
        try {
            android.system.Os.chmod(f.getAbsolutePath(), mode);
        } catch (Throwable ignored) {
        }
    }

    private void copyExec(File src, File dst) {
        if (src.isFile() && !dst.exists()) {
            try (InputStream in = new FileInputStream(src);
                 FileOutputStream out = new FileOutputStream(dst)) {
                byte[] buf = new byte[8192];
                int n;
                while ((n = in.read(buf)) != -1) out.write(buf, 0, n);
            } catch (IOException ignored) {
            }
            chmod(dst, 0755);
        }
    }

    /** 手机存储里那个挂给工作区用的目录（对应 {@code BINDS} 的 {@code /root/手机存储}）。
     *
     *  <p>proot bind 一个不存在的源目录会失败，所以必须先把它建出来。顺手放一份说明：
     *  这是 FUSE，pnpm 和 git 在上面用不了 —— 用户看到目录的那一刻就该知道边界，
     *  等他装依赖失败、对着 {@code EPERM: operation not permitted, symlink} 发愁再去查
     *  就太晚了，那个报错离根因隔着好几层。
     *
     *  <p>文件名里刻意不用冒号：FUSE 不接受 {@code : * ? " < > | \}。 */
    private void ensurePhoneWorkspaceDir() {
        try {
            File d = new File("/storage/emulated/0/Download/DSHA/工作区");
            if (!d.isDirectory() && !d.mkdirs()) return;
            // rootfs 里也必须有这个目录。proot 的 bind 只对**容器内的进程**可见，而 GUI 的
            // 工作区选择器是 App 侧的 Java 在扫 rootfs/root/ 下的真实目录 —— 不把挂载点
            // 建出来，界面里根本看不到「手机存储」这一项。第一版就漏了这步：挂载是通的、
            // 容器里 cd 得进去，但用户在选择器里什么变化都没有。
            new File(rootfsDir, "root/手机存储").mkdirs();
            new File(rootfsDir, "root/外部工作区").mkdirs();
            File readme = new File(d, "读我-这个目录能做什么.txt");
            // 已经有、但是旧版（缺权限那一节）时重写。老用户碰到的第一个问题往往就是
            // 「目录怎么是空的」，而那一节正是答案 —— 不重写等于把答案锁在新装用户那儿。
            if (readme.isFile()) {
                try {
                    String cur = new String(java.nio.file.Files.readAllBytes(readme.toPath()),
                            java.nio.charset.StandardCharsets.UTF_8);
                    if (cur.contains("看不到文件？")) return;
                } catch (Throwable ignored) {
                    return;
                }
            }
            String text = "容器里的 /root/手机存储 就是手机内部存储的根（整个 /storage/emulated/0），\n"
                    + "在 DSHA 的工作区选择里能看到「手机存储」这一项。这个文件夹是推荐的落脚点。\n"
                    + "\n"
                    + "能做什么\n"
                    + "· 让 agent 读写这里的文档、脚本、图片 —— 手机上任何文件管理器都能直接打开，不用导出\n"
                    + "· 把资料丢进来给 agent 处理，产物也留在这里\n"
                    + "\n"
                    + "不能做什么（重要）\n"
                    + "· 不能在这里 pnpm install / npm i。手机存储是 FUSE 文件系统，不支持符号链接，\n"
                    + "  而 pnpm 的 node_modules 本身就是一片符号链接，装必失败\n"
                    + "· 不能 git clone，同样是符号链接与权限位的问题\n"
                    + "· chmod +x 无效，脚本设不了可执行位（用 bash 脚本名 来跑）\n"
                    + "· 文件名里不能有 : * ? \" < > | \\\n"
                    + "· 大量小文件时明显比容器内慢（每次读写都要过一遍 FUSE）\n"
                    + "\n"
                    + "要跑 Node 项目或者用 git，请把工作区选在容器内的目录\n"
                    + "（工作区列表里除「手机存储」以外的那些都在容器内）。\n"
                    + "\n"
                    + "看不到文件？那是权限的事，不是挂载坏了\n"
                    + "· Android 11 及以上：要在 DSHA 里授予「所有文件访问」（会跳系统设置拉开关）\n"
                    + "· Android 8~10：第一次进 App 会弹一次存储权限，拒绝过的话去系统设置里给回来\n"
                    + "没权限时这个目录照样挂得上、进得去，但里面看起来是空的，写入报 Permission denied。\n";
            java.nio.file.Files.write(readme.toPath(),
                    text.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        } catch (Throwable ignored) {
        }
    }

    /** 准备运行时：复制依赖库（匹配 SONAME）、创建目录 */
    public void ensureRuntimeFiles() {
        baseDir.mkdirs();
        tmpDir.mkdirs();
        libDir.mkdirs();
        ensurePhoneWorkspaceDir();

        // 这两个是 **proot 的 NEEDED 依赖**，proroot 用不到
        // （它只链 libdl/libc，共享内存靠挂真实目录当 /dev/shm）。
        // 切到 proroot 时就不必复制了 —— 少一份没人用的文件，也少一处将来的困惑来源。
        boolean isProot = true;
        try {
            isProot = "proot".equals(runtime().id());
        } catch (Throwable ignored) {
        }
        if (isProot) {
            // libtalloc.so.2（proot 的 NEEDED），jniLibs 里叫 libtalloc.so
            copyExec(findNativeLib("libtalloc.so"), new File(libDir, "libtalloc.so.2"));
            // libandroid-shmem.so（旧版 proot 的 NEEDED）
            copyExec(findNativeLib("libandroidshmem.so"), new File(libDir, "libandroid-shmem.so"));
        }
    }
    /** 在 rootfs 内执行 bash 命令 */
    public Process execRootfs(String bashCommand) throws IOException {
        java.util.List<String> argv = baseProotArgv();
        argv.add("/bin/bash");
        argv.add("-c");
        argv.add(bashCommand);
        ProcessBuilder pb = new ProcessBuilder(argv).redirectErrorStream(true);
        pb.redirectInput(ProcessBuilder.Redirect.from(new File("/dev/null")));
        applyProotEnv(pb);
        return pb.start();
    }

    /** 启动交互式 bash 会话（持久进程，可读写 stdin/stdout；cd/export 状态保持，供内置终端使用） */
    public Process execRootfsInteractive() throws IOException {
        java.util.List<String> argv = baseProotArgv();
        argv.add("/bin/bash");
        ProcessBuilder pb = new ProcessBuilder(argv).redirectErrorStream(true);
        applyProotEnv(pb);
        // 交互终端：危险命令启用确认（App 弹窗优先，交互输入兜底）
        pb.environment().put("DSH_CONFIRM", "1");
        pb.environment().put("DSH_INTERACTIVE", "1");
        return pb.start();
    }

    /** 同步执行 rootfs 命令并返回输出 */
    /** 执行 rootfs 命令并读回输出（execRootfs 已 redirectErrorStream 合并 stderr）。带超时防卡死（默认 60s）。 */
    public String execAndRead(String bashCommand) {
        return execAndRead(bashCommand, 60_000);
    }

    /** 执行 rootfs 命令并读回输出。timeoutMs 超时强杀防挂起。
     *  输出读在线程里，避免管道写满（>256KB）死锁 + 超时无法中断。 */
    public String execAndRead(String bashCommand, long timeoutMs) {
        try {
            Process p = execRootfs(bashCommand);
            java.util.concurrent.FutureTask<String> task = new java.util.concurrent.FutureTask<>(
                    () -> readStream(p.getInputStream()));
            Thread t = new Thread(task, "exec-read");
            t.setDaemon(true);
            t.start();
            String out;
            try {
                out = task.get(timeoutMs, java.util.concurrent.TimeUnit.MILLISECONDS);
            } catch (Exception te) {
                p.destroyForcibly();
                return "ERROR: 命令执行超时(>" + (timeoutMs / 1000) + "s)，已强杀";
            }
            if (!p.waitFor(3000, java.util.concurrent.TimeUnit.MILLISECONDS)) {
                p.destroyForcibly();
            }
            return out;
        } catch (Throwable e) {
            return "ERROR: " + e.getClass().getSimpleName() + ": " + e.getMessage();
        }
    }

    /** 同步执行 rootfs 命令，退出码非 0 时抛异常 */
    public String execChecked(String bashCommand) throws IOException {
        Process p = execRootfs(bashCommand);
        String out = readStream(p.getInputStream());
        int code;
        try {
            code = p.waitFor();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("命令被中断", e);
        }
        if (code != 0) {
            String tail = out.length() > 600 ? out.substring(out.length() - 600) : out;
            // 退出码本身就带信息，但用户只看到一个裸数字。127 尤其常见且极易误诊成
            // 网络问题（用户实测：第五步报 127，实际是 npm 不在 PATH 里）。
            String hint = "";
            if (code == 127) {
                hint = "（127 = 命令找不到，通常是某个工具没装成或不在 PATH 里，"
                        + "不是网络问题）";
            } else if (code == 137) {
                hint = "（137 = 被系统 KILL，通常是内存不足）";
            } else if (code == 126) {
                hint = "（126 = 文件在但不可执行，多为权限或架构不匹配）";
            }
            throw new IOException("退出码 " + code + hint + "：\n" + tail);
        }
        return out;
    }

    /** 带「静默超时」的执行：不设总时长上限，只判「多久一个字都不吐」。
     *
     *  为什么不用普通超时：慢设备上 pnpm install 跑二十分钟是正常的，按总时长掐
     *  会把还在正常干活的安装杀掉、然后报「安装失败」—— 这比卡死更糟，因为它把
     *  「慢」误诊成「坏」，用户还会白白重装一遍。真正卡死的特征是长时间没有任何
     *  输出（网络挂起、等一个永远不会来的响应）。
     *
     *  只适用于**会持续打印进度**的命令（pnpm / apt / npm）。tar、xz 这类正常
     *  也会长时间静默，用这个包就是误杀 —— 它们继续走 execChecked。
     *
     *  @param stallMs 允许的最长静默时间；期间只要有输出就重新计时。
     */
    public String execCheckedStall(String bashCommand, long stallMs) throws IOException {
        final Process p = execRootfs(bashCommand);
        final long[] lastTs = {System.currentTimeMillis()};
        final boolean[] killed = {false};
        Thread watchdog = new Thread(() -> {
            while (p.isAlive()) {
                try {
                    Thread.sleep(5000);
                } catch (InterruptedException e) {
                    return;
                }
                if (System.currentTimeMillis() - lastTs[0] > stallMs) {
                    killed[0] = true;
                    p.destroyForcibly();
                    return;
                }
            }
        }, "dsha-stall-watchdog");
        watchdog.setDaemon(true);
        watchdog.start();

        String out;
        try {
            out = readStreamTracking(p.getInputStream(), lastTs);
        } finally {
            watchdog.interrupt();
        }
        int code;
        try {
            code = p.waitFor();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("命令被中断", e);
        }
        String tail = out.length() > 600 ? out.substring(out.length() - 600) : out;
        if (killed[0]) {
            throw new IOException("已超过 " + (stallMs / 1000)
                    + " 秒没有任何输出，判定卡死并终止（多为网络挂起）。最后的输出：\n" + tail);
        }
        if (code != 0) {
            throw new IOException("退出码 " + code + "：\n" + tail);
        }
        return out;
    }

    /** 同 readStream，但每收到一批数据就更新时间戳，供静默看门狗判断死活 */
    private String readStreamTracking(InputStream in, long[] lastTs) throws IOException {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int n;
        int kept = 0;
        final int MAX = 256 * 1024;
        try {
            while ((n = in.read(buf)) != -1) {
                lastTs[0] = System.currentTimeMillis();
                if (kept < MAX) {
                    int w = Math.min(n, MAX - kept);
                    bos.write(buf, 0, w);
                    kept += w;
                }
            }
        } catch (IOException e) {
            // 看门狗强杀进程会让读操作抛异常：此时已收到的输出仍有用（含失败现场）
            return bos.toString("UTF-8");
        }
        return bos.toString("UTF-8");
    }

    /** 读取进程输出，最多保留 256KB 防止内存暴涨 */
    private String readStream(InputStream in) throws IOException {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int n;
        int kept = 0;
        final int MAX = 256 * 1024;
        while ((n = in.read(buf)) != -1) {
            if (kept < MAX) {
                int w = Math.min(n, MAX - kept);
                bos.write(buf, 0, w);
                kept += w;
            }
        }
        return bos.toString("UTF-8");
    }

    /** 阻塞读取进程输出，保持长驻进程存活；进程退出时返回最后一段输出 */
    public String drainOutput(Process p) throws IOException {
        InputStream in = p.getInputStream();
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int n;
        int kept = 0;
        final int MAX = 64 * 1024;
        while ((n = in.read(buf)) != -1) {
            if (kept < MAX) {
                int w = Math.min(n, MAX - kept);
                bos.write(buf, 0, w);
                kept += w;
            }
        }
        return bos.toString("UTF-8");
    }

    /** 冒烟测试：proot 能否直接 exec + 进 rootfs */
    public String smokeTest() {
        ensureRuntimeFiles();
        StringBuilder diag = new StringBuilder();
        diag.append("proot 路径: ").append(prootPath()).append("\n");
        diag.append("nativeLibDir: ").append(nativeLibDir).append("\n");
        try {
            ProcessBuilder pb = new ProcessBuilder(prootPath(), "--version")
                    .redirectErrorStream(true);
            pb.environment().put("LD_LIBRARY_PATH", libDir.getAbsolutePath() + ":" + findNativeLib("libproot.so").getParent());
            Process p = pb.start();
            String v = readStream(p.getInputStream());
            p.waitFor();
            diag.append("[1] proot --version: ").append(v == null ? "" : v.trim().split("\n")[0]).append("\n");
        } catch (Throwable e) {
            return "PROOT_FAIL: " + e.getClass().getSimpleName() + ": " + e.getMessage();
        }
        String out = execAndRead("/bin/echo SMOKE_OK");
        diag.append("[2] rootfs exec: ").append(out == null ? "" : out.trim()).append("\n");
        return diag.toString();
    }

    /** HEAD 请求测下载源延迟；可用返回耗时毫秒，失败返回 -1 */
    public long probeLatency(String url, int timeoutMs) {
        long start = System.currentTimeMillis();
        try {
            HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
            conn.setConnectTimeout(timeoutMs);
            conn.setReadTimeout(timeoutMs);
            conn.setRequestMethod("HEAD");
            conn.setRequestProperty("User-Agent", "DSHA/1.0.0");
            int code = conn.getResponseCode();
            conn.disconnect();
            return (code == 200 || code == 206)
                    ? System.currentTimeMillis() - start : -1;
        } catch (Throwable e) {
            return -1;
        }
    }

    /** 并行测速全部源，返回延迟毫秒数组（-1 表示不可用） */
    public long[] probeAll(String[] urls, int timeoutMs) {
        final long[] lat = new long[urls.length];
        final java.util.concurrent.CountDownLatch latch =
                new java.util.concurrent.CountDownLatch(urls.length);
        ExecutorService pool = Executors.newFixedThreadPool(Math.min(8, Math.max(1, urls.length)));
        for (int i = 0; i < urls.length; i++) {
            final int idx = i;
            pool.execute(() -> {
                try {
                    lat[idx] = probeLatency(urls[idx], timeoutMs);
                } finally {
                    latch.countDown();
                }
            });
        }
        try {
            latch.await(timeoutMs + 3000L, java.util.concurrent.TimeUnit.MILLISECONDS);
        } catch (InterruptedException ignored) {
        }
        pool.shutdownNow();
        return lat;
    }

    /** 多源测速排序（并行）：延迟短的在前，测速失败（-1）排最后（仍作 fallback） */
    public String[] orderBySpeed(String[] urls) {
        long[] t = probeAll(urls, 6000);
        String[] out = urls.clone();
        for (int i = 0; i < out.length - 1; i++) {
            for (int j = i + 1; j < out.length; j++) {
                if (t[j] >= 0 && (t[i] < 0 || t[j] < t[i])) {
                    String su = out[i]; out[i] = out[j]; out[j] = su;
                    long st = t[i]; t[i] = t[j]; t[j] = st;
                }
            }
        }
        return out;
    }

    /** 下载进度回调：已下载字节 / 总字节（total<=0 表示源未提供大小） */
    public interface DownloadProgress {
        void onProgress(long downloaded, long total);
    }

    /** 下载 rootfs（带进度回调，支持断点续传；完成后写 .done 标记） */
    public void downloadRootfs(String url, File dest, DownloadProgress progress) throws IOException {
        long existing = dest.exists() ? dest.length() : 0L;
        HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
        conn.setConnectTimeout(45000);
        conn.setReadTimeout(300000);
        conn.setInstanceFollowRedirects(true);
        conn.setRequestProperty("User-Agent", "DSHA/1.0.0");
        if (existing > 0) {
            conn.setRequestProperty("Range", "bytes=" + existing + "-");
        }
        conn.connect();
        int code = conn.getResponseCode();
        if (code != 200 && code != 206) throw new IOException("HTTP " + code);
        boolean resume = code == 206;
        long contentLen = conn.getContentLengthLong();
        long totalBytes = resume && contentLen > 0 ? existing + contentLen : contentLen;
        try (InputStream in = conn.getInputStream();
             java.io.RandomAccessFile raf = new java.io.RandomAccessFile(dest, "rw")) {
            if (resume) raf.seek(existing); else raf.setLength(0);
            byte[] buf = new byte[65536];
            long downloaded = resume ? existing : 0L;
            int n;
            int lastPct = -1;
            long lastCbAt = 0;
            while ((n = in.read(buf)) != -1) {
                raf.write(buf, 0, n);
                downloaded += n;
                // 节流：百分比变化或每 500ms 回调一次。
                // （每 64KB 回调会把 UI 线程塞爆；只按百分比回调则大文件几秒才更新一次，
                //  速率/剩余时间显示会很迟钝，所以加时间兜底。）
                if (progress != null) {
                    long now = System.currentTimeMillis();
                    if (totalBytes > 0) {
                        int pct = (int) (downloaded * 100 / totalBytes);
                        if (pct != lastPct || now - lastCbAt >= 500) {
                            lastPct = pct;
                            lastCbAt = now;
                            progress.onProgress(downloaded, totalBytes);
                        }
                    } else if (now - lastCbAt >= 500) {
                        lastCbAt = now; // 源未提供大小：定期回调，界面才能显示已下大小与速率
                        progress.onProgress(downloaded, -1);
                    }
                }
            }
            try (FileInputStream fis = new FileInputStream(dest)) {
                int b0 = fis.read(), b1 = fis.read();
                // 按格式校验魔数：.xz 校验 xz 魔数（FD 37），其余按 gzip（1F 8B）
                boolean xz = url.toLowerCase().contains(".xz") || dest.getName().endsWith(".xz");
                boolean okMagic = xz
                        ? (b0 == 0xfd && b1 == 0x37)
                        : (b0 == 0x1f && b1 == 0x8b);
                if (!okMagic) {
                    dest.delete();
                    throw new IOException("下载内容不是有效的压缩包（可能是错误页面），已清除");
                }
            }
            try (FileOutputStream fo = new FileOutputStream(dest.getAbsolutePath() + ".done")) {
                fo.write(String.valueOf(downloaded).getBytes());
            } catch (IOException ignored) {
            }
        } finally {
            conn.disconnect();
        }
    }

    /** 解压 rootfs（纯 Java 流式） */
    public void extractRootfs(File tarball) throws IOException {
        if (rootfsDir.exists()) {
            deleteRecursively(rootfsDir);
        }
        rootfsDir.mkdirs();
        TarGzipExtractor.extract(tarball, rootfsDir);
        boolean hasBash = new File(rootfsDir, "usr/bin/bash").exists()
                || new File(rootfsDir, "bin/bash").exists();
        if (!hasBash) {
            throw new IOException("解压后 rootfs 不完整（缺少 bash），请清除环境后重试");
        }
    }

    /** 解压预构建包（去掉顶层目录）到 rootfs 的指定目录 */
    public void extractHarness(File tarball, File target) throws IOException {
        if (target.exists()) deleteRecursively(target);
        target.mkdirs();
        TarGzipExtractor.extract(tarball, target, 1);
    }

    public void setupResolvConf() {
        File rc = new File(rootfsDir, "etc/resolv.conf");
        rc.getParentFile().mkdirs();
        if (rc.exists()) rc.delete();
        try (FileOutputStream o = new FileOutputStream(rc)) {
            // 国内 DNS 优先保证基础解析（墙内 8.8.8.8/1.1.1.1 常被污染/不可达）
            o.write("nameserver 223.5.5.5\nnameserver 119.29.29.29\nnameserver 8.8.8.8\nnameserver 1.1.1.1\n".getBytes());
        } catch (IOException ignored) {
        }
    }

    public void markInstalled() {
        markerFile.getParentFile().mkdirs();
        try (FileOutputStream o = new FileOutputStream(markerFile)) {
            o.write(("installed=" + System.currentTimeMillis() + "\n").getBytes());
        } catch (IOException ignored) {
        }
    }

    /** 内置离线包 asset 名称（GitHub Actions 构建时预置的预装 rootfs 整包） */
    public static final String OFFLINE_BUNDLE_ASSET = "offline-rootfs.tar.gz";

    /** 离线包版本标记 asset（与离线包同步 bump；App 对比 rootfs 里的已解压版本，
     *  发现新版 → 提示用户可升级重解压。缺失=老包，按 0 处理不提示）。 */
    public static final String OFFLINE_VERSION_ASSET = "offline-rootfs.version";

    /** APK 内置离线包版本（读 asset；失败/缺失返回 "0"） */
    public String bundledOfflineVersion() {
        try (java.io.InputStream in = ctx.getAssets().open(OFFLINE_VERSION_ASSET)) {
            byte[] buf = new byte[32];
            int n = in.read(buf);
            String s = n > 0 ? new String(buf, 0, n, java.nio.charset.StandardCharsets.UTF_8).trim() : "";
            return s.isEmpty() ? "0" : s;
        } catch (Exception e) {
            return "0";
        }
    }

    /** rootfs 已解压的离线包版本（写于解压完成；缺失返回 "0"） */
    public String installedOfflineVersion() {
        try {
            java.io.File f = new java.io.File(rootfsDir, "root/.dsh/offline-rootfs.version");
            if (!f.isFile()) return "0";
            String s = new String(java.nio.file.Files.readAllBytes(f.toPath()),
                    java.nio.charset.StandardCharsets.UTF_8).trim();
            return s.isEmpty() ? "0" : s;
        } catch (Exception e) {
            return "0";
        }
    }

    /** 解压完成后记录离线包版本标记（供启动时对比，发现新包可提示升级） */
    private void writeOfflineVersion() {
        try {
            java.io.File f = new java.io.File(rootfsDir, "root/.dsh/offline-rootfs.version");
            if (f.getParentFile() != null) f.getParentFile().mkdirs();
            java.nio.file.Files.write(f.toPath(), bundledOfflineVersion().getBytes(
                    java.nio.charset.StandardCharsets.UTF_8));
        } catch (Throwable e) {
            android.util.Log.w("DSHA", "离线包版本标记写入失败，升级提示可能重复出现: " + e);
        }
    }

    /** aapt 会把 .tar.gz 自动解成 .tar，所以这些名字都算内置包。 */
    private static final String[] BUNDLE_NAMES = {
            "offline-rootfs.tar.gz",
            "offline-rootfs.tar",
            "offline-rootfs.bin",
            "offline-rootfs.tgz",
    };

    public boolean hasOfflineBundle() {
        try (java.util.zip.ZipFile z = new java.util.zip.ZipFile(ctx.getPackageCodePath())) {
            if (findBundleEntry(z) != null) return true;
        } catch (Exception ignored) {
        }
        for (String n : BUNDLE_NAMES) {
            try {
                ctx.getAssets().open(n).close();
                return true;
            } catch (IOException ignored) {
            }
        }
        return false;
    }

    private java.util.zip.ZipEntry findBundleEntry(java.util.zip.ZipFile z) {
        for (String n : BUNDLE_NAMES) {
            java.util.zip.ZipEntry e = z.getEntry("assets/" + n);
            if (e != null && !e.isDirectory()) return e;
            e = z.getEntry(n);
            if (e != null && !e.isDirectory()) return e;
        }
        java.util.zip.ZipEntry best = null;
        java.util.Enumeration<? extends java.util.zip.ZipEntry> en = z.entries();
        while (en.hasMoreElements()) {
            java.util.zip.ZipEntry e = en.nextElement();
            String name = e.getName();
            if (e.isDirectory()) continue;
            if (name.contains("offline-rootfs") || name.contains("offline_rootfs")) {
                if (best == null || e.getSize() > best.getSize()) best = e;
            }
        }
        return best;
    }

    /**
     * 从 APK 内置包解压预装 rootfs。优先按 zip 条目流式解压（不经 AssetManager，
     * 也不先拷 300MB 到 tmp），失败再回退 assets。
     */
    public void extractOfflineBundle(java.util.function.BiConsumer<Long, Long> onProgress) throws IOException {
        ensureRuntimeFiles();
        java.util.zip.ZipFile apk = null;
        InputStream raw = null;
        long total = 0;
        try {
            apk = new java.util.zip.ZipFile(ctx.getPackageCodePath());
            java.util.zip.ZipEntry e = findBundleEntry(apk);
            if (e != null) {
                raw = apk.getInputStream(e);
                total = e.getSize() > 0 ? e.getSize() : 0;
            }
        } catch (IOException ignored) {
            if (apk != null) {
                try { apk.close(); } catch (IOException ignored2) {}
                apk = null;
            }
        }
        if (raw == null) {
            IOException last = null;
            for (String n : BUNDLE_NAMES) {
                try {
                    raw = ctx.getAssets().open(n);
                    try {
                        total = ctx.getAssets().openFd(n).getLength();
                    } catch (IOException ignored) {
                    }
                    break;
                } catch (IOException e) {
                    last = e;
                }
            }
            if (raw == null) {
                throw last != null ? last : new IOException("assets 里也没有离线包");
            }
        }

        final java.util.function.BiConsumer<Long, Long> cb = onProgress;
        final long tot = total;
        InputStream counted = new java.io.FilterInputStream(raw) {
            long done = 0;
            @Override
            public int read(byte[] b, int off, int len) throws IOException {
                int n = super.read(b, off, len);
                if (n > 0 && cb != null) {
                    done += n;
                    cb.accept(done, tot);
                }
                return n;
            }
        };

        try {
            // ===== 数据保护：重解压前备份用户数据（.dsh 配置/对话 + 所有工作目录 .env），解压后自动还原 =====
            // 旧 rootfs 存在但 isOfflineExtracted() 判定失败（标记丢失/bash 路径变化）会走到这里，
            // 直接删整个 rootfs 会连对话记录一起丢掉（issue#9 第1条）——必须先备份再删。
            // .env 遍历 /root 下所有子目录（兼容用户自定义 workdir，不只默认 deepseek-harness）。
            java.io.File dataBak = null;
            if (rootfsDir.exists()) {
                // 用户数据保护：把 /root 下的东西**整体挪走**，而不是挑几样复制。
                //
                // 原先只保 .dsh、plugin-src 和各工作区的 .env —— 于是工作区目录里其它所有
                // 东西（用户写的代码、agent 的产出、下载的文件）随 rootfs 一起被删。
                // 真机反馈的原话是「确实，工作区域的内容全部丢失了」。
                // **「挑哪些文件要保」这个判断只要存在，就一定会漏掉某样东西**；
                // 整目录挪走之后这个问题从根上没有了。
                //
                // 为什么用 rename 而不是复制：dataBak 与 rootfs 在同一个文件系统里
                //（都在 files/linux 下），rename 是 O(1)、不占额外空间、瞬间完成；
                // 复制要付「数据大小 ×2」的空间和好几分钟 —— 对话几个 GB 的用户正是在
                // 这一步空间不够，而那时 rootfs 已经删了。rename 失败才退回复制（保留软链）。
                java.io.File rootHome = new java.io.File(rootfsDir, "root");
                java.io.File[] kids = rootHome.isDirectory() ? rootHome.listFiles() : null;
                if (kids != null && kids.length > 0) {
                    dataBak = new java.io.File(baseDir, ".data-preserve-" + System.currentTimeMillis());
                    //noinspection ResultOfMethodCallIgnored
                    dataBak.mkdirs();
                    // 怎么挪、失败怎么回滚，全在 DataPreserve（纯 java.io，
                    // tools/data-preserve-test.sh 能真造目录/软链/失败场景跑一遍）。
                    // 这里只负责起名字和写日志。
                    DataPreserve.Result pr = DataPreserve.preserve(rootHome, dataBak);
                    android.util.Log.i("DSHA", "用户数据已保护：" + pr
                            + " → " + dataBak.getName());
                }
            }
            // 空间预检：**必须在删 rootfs 之前**。删完才发现装不回来是这条路上最坏的结果
            //（用户环境没了，而且他手上没有别的办法恢复）。解出来大约是包的 3.5 倍，
            // 这里按 4 倍加 200MB 余量估；估不出包大小（total=0）时不拦，避免误伤。
            if (tot > 0) {
                long need = tot * 4 + 200L * 1024 * 1024;
                long free = baseDir.getUsableSpace();
                if (free > 0 && free < need) {
                    throw new IOException("空间不够，已中止升级（rootfs 未改动）\n"
                            + "需要约 " + (need >> 20) + " MB，当前可用 " + (free >> 20) + " MB。\n"
                            + "清点一下空间再试 —— 现在停下来比删掉环境又装不回来好。");
                }
            }
            if (rootfsDir.exists()) deleteRecursively(rootfsDir);
            rootfsDir.mkdirs();
            TarGzipExtractor.extractAuto(counted, rootfsDir, 0);
            if (!hasBash()) {
                throw new IOException("解压后 rootfs 不完整（缺少 bash）\n" + diagnoseRootfs());
            }
            setupResolvConf();
            // 解压完成后还原用户数据（.dsh + 所有工作目录 .env）
            if (dataBak != null) {
                // 还原逻辑与「事后兜底恢复」共用同一份实现（restorePreservedData）——
                // 「解压完还原」和「上次没走完、事后补救」是同一件事，分成两份写必然分家。
                String rr = restorePreservedData(dataBak, true);
                android.util.Log.i("DSHA", "重解压还原用户数据：" + rr);
            }
            markOfflineExtracted();
            // 记录离线包版本（启动时对比，发现新版可提示升级）
            writeOfflineVersion();
        } finally {
            try { counted.close(); } catch (Exception ignored) {}
            if (apk != null) {
                try { apk.close(); } catch (Exception ignored) {}
            }
        }
    }

    /**
     * 把 {@code .data-preserve-*} 里的用户数据还原回 rootfs。
     *
     * <p>两个调用方共用这一份：重解压流程（解压完立刻还原）与<b>事后兜底恢复</b>
     * （上次升级没走完，残留目录还在）。分成两份写的话，迟早只改一边 ——
     * 这个项目在「什么算用户数据」上已经分家过好几次（备份 vs 数据保护、
     * 桥 token 的处置），所以这里从一开始就收成一处。
     *
     * @param deleteAfter 还原完是否删掉保护目录（正常都删；只有用户明确说「先留着」时才不删）
     * @return 人话结果，直接进活动日志 / 对话框
     */
    String restorePreservedData(java.io.File dataBak, boolean deleteAfter) {
        StringBuilder log = new StringBuilder();
        try {
            java.io.File rootHome = new java.io.File(rootfsDir, "root");
            if (!rootHome.isDirectory()) {
                //noinspection ResultOfMethodCallIgnored
                rootHome.mkdirs();
            }
            java.io.File[] items = dataBak.listFiles();
            if (items == null || items.length == 0) return "保护目录里没有可还原的东西";

            // 当前 .dsh 里已经有用起来的数据（事后恢复的典型情形）→ 先挪开，不覆盖。
            // 与 restoreFromBackup 的做法一致：宁可留两份让用户自己挑，也不悄悄盖掉。
            boolean bringsDsh = false;
            for (java.io.File it : items) {
                String n = it.getName();
                if (".dsh".equals(n) || "dsh".equals(n)) { bringsDsh = true; break; }
            }
            if (bringsDsh && hasLiveDshData()) {
                java.io.File cur = new java.io.File(rootHome, ".dsh");
                java.io.File aside = new java.io.File(rootHome,
                        ".dsh.pre-recover-" + System.currentTimeMillis());
                if (cur.renameTo(aside)) {
                    log.append("原有 .dsh 已挪到 ").append(aside.getName()).append("；");
                } else {
                    return "当前 .dsh 里已经有数据，又挪不开它 —— 没有动任何东西（怕覆盖掉你正在用的）";
                }
            }

            int moved = 0, copied = 0, failed = 0;
            for (java.io.File it : items) {
                String n = it.getName();
                java.io.File dst;
                // 命名兼容：1.1.9 及更早的保护目录用的是挑选式命名（dsh / plugin-src / env-<工作区>），
                // 1.1.9.1 起是「/root 下的原名」整目录挪走。两种都要认 ——
                // 用户手机上完全可能存着上一版留下的保护目录。
                if ("dsh".equals(n)) {
                    dst = new java.io.File(rootHome, ".dsh");
                } else if (n.startsWith("env-")) {
                    String dir = n.substring("env-".length());
                    dst = new java.io.File(rootHome, dir + "/.env");
                } else {
                    dst = new java.io.File(rootHome, n);
                }
                try {
                    if (dst.getParentFile() != null && !dst.getParentFile().isDirectory()) {
                        //noinspection ResultOfMethodCallIgnored
                        dst.getParentFile().mkdirs();
                    }
                    // 新解压的 rootfs 在 /root 下带着默认文件（.bashrc 之类）。同名时
                    // **用户那份优先** —— 用户改过的 .bashrc 也是他的数据，而默认文件
                    // 任何时候都能从 APK 里再拿一份。
                    if (FileCopy.existsNoFollow(dst)) deleteRecursively(dst);
                    if (it.renameTo(dst)) {
                        moved++;
                    } else {
                        copyRecursively(it, dst);
                        copied++;
                    }
                } catch (Throwable e) {
                    failed++;
                    android.util.Log.w("DSHA", "还原 " + n + " 失败: " + e);
                }
            }
            log.append("挪回 ").append(moved).append(" 项");
            if (copied > 0) log.append("、复制 ").append(copied).append(" 项");
            if (failed > 0) log.append("、失败 ").append(failed).append(" 项");
            log.append("；");

            // 清掉本机专属的东西（桥 token、内置插件版本标记…）。清单来自 UserDataPolicy，
            // 与备份的 --exclude 同源。数据保护是把整个 /root 挪回来的，所以必须再清一遍：
            // 否则旧桥 token 会活过这次还原，而 App 内存里那个已经换了 → agent 一律 401，
            // 表现是「悬浮条不显示 + 工具调用失败」，光看现象根本想不到是 token。
            for (String rel : UserDataPolicy.purgeAfterRestore()) {
                java.io.File stale = new java.io.File(rootfsDir, rel);
                if (stale.isFile()) {
                    //noinspection ResultOfMethodCallIgnored
                    stale.delete();
                    android.util.Log.i("DSHA", "还原后清掉本机专属文件: " + rel);
                }
            }
            HttpShellService.resetTokenAfterRestore();
            if (deleteAfter && failed == 0) {
                deleteRecursively(dataBak);
                log.append("保护目录已清理");
            } else if (failed > 0) {
                log.append("有失败项，保护目录保留在 ").append(dataBak.getName()).append("（可再试一次）");
            } else {
                log.append("保护目录保留在 ").append(dataBak.getName());
            }
            return log.toString();
        } catch (Throwable e) {
            android.util.Log.w("DSHA", "还原用户数据失败: " + e);
            return "还原过程出错：" + e + "（保护目录没有删，可以再试一次）";
        }
    }

    /** 当前 rootfs 里是不是已经有「用起来了」的 .dsh（有会话或设置就算）。 */
    boolean hasLiveDshData() {
        java.io.File dsh = new java.io.File(rootfsDir, "root/.dsh");
        if (!dsh.isDirectory()) return false;
        String[] signs = {"sessions", "settings.yaml", "profiles", "credentials.yaml"};
        for (String n : signs) {
            java.io.File f = new java.io.File(dsh, n);
            if (FileCopy.existsNoFollow(f)) return true;
        }
        return false;
    }

    /**
     * 找出上次升级没走完留下的数据保护目录（{@code .data-preserve-<时间戳>}）。
     *
     * <p>正常流程结束时它会被删掉，所以还在 = 上一次重解压中途失败了（空间不够、
     * 被系统杀、断电…）。里面是用户的对话与插件源码，而 App 原先<b>不会</b>再看它一眼 ——
     * 数据就在磁盘上，用户却拿不回来，这才是最难受的那种丢。
     *
     * <p>有多份时取时间戳最大的那份（最近一次），其余留着不动。
     *
     * @return 目录，或 null（没有残留 / 残留是空的）
     */
    public java.io.File findPreservedData() {
        try {
            java.io.File[] all = baseDir.listFiles((d, n) -> n.startsWith(".data-preserve-"));
            if (all == null || all.length == 0) return null;
            java.io.File best = null;
            for (java.io.File f : all) {
                if (!f.isDirectory()) continue;
                String[] kids = f.list();
                if (kids == null || kids.length == 0) continue; // 空壳，不值得提示
                if (best == null || f.getName().compareTo(best.getName()) > 0) best = f;
            }
            return best;
        } catch (Throwable e) {
            return null;
        }
    }

    /** 还有几份残留（提示里告诉用户，别让他以为只有一份）。 */
    public int preservedDataCount() {
        java.io.File[] all = baseDir.listFiles((d, n) -> n.startsWith(".data-preserve-"));
        if (all == null) return 0;
        int n = 0;
        for (java.io.File f : all) {
            String[] kids = f.isDirectory() ? f.list() : null;
            if (kids != null && kids.length > 0) n++;
        }
        return n;
    }

    /** 残留数据的人话摘要：什么时候留下的、里面有多少会话、有没有插件源码。 */
    public String preservedDataSummary(java.io.File d) {
        if (d == null) return "";
        StringBuilder sb = new StringBuilder();
        try {
            String ts = d.getName().substring(".data-preserve-".length());
            try {
                long ms = Long.parseLong(ts);
                sb.append("留下的时间：")
                  .append(new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", java.util.Locale.US)
                          .format(new java.util.Date(ms)))
                  .append('\n');
            } catch (NumberFormatException ignored) {
            }
            java.io.File sessions = new java.io.File(d, "dsh/sessions");
            // sessions 多半是一根指向公开目录的软链（那是「卸载不丢数据」的实现方式），
            // 所以这里数的是链接指向的那个目录 —— 用 list() 会跟随链接，正好。
            String[] kids = sessions.list();
            if (kids != null) {
                sb.append("对话文件：").append(kids.length).append(" 个");
                if (FileCopy.isLink(sessions)) sb.append("（存放在公开目录，链接原样保留）");
                sb.append('\n');
            }
            if (new java.io.File(d, "dsh/settings.yaml").exists()) sb.append("设置：有\n");
            if (new java.io.File(d, "plugin-src").isDirectory()) {
                String[] ps = new java.io.File(d, "plugin-src").list();
                sb.append("插件源码：").append(ps == null ? 0 : ps.length).append(" 个\n");
            }
            java.io.File[] envs = d.listFiles((dd, n) -> n.startsWith("env-"));
            if (envs != null && envs.length > 0) sb.append("工作区配置：").append(envs.length).append(" 份\n");
        } catch (Throwable ignored) {
        }
        return sb.toString().trim();
    }

    /** 用户选择「不要了」：把残留整个删掉（只有用户明确点删才走这里）。 */
    public boolean dropPreservedData(java.io.File d) {
        try {
            if (d == null || !d.isDirectory()) return false;
            deleteRecursively(d);
            return !d.exists();
        } catch (Throwable e) {
            return false;
        }
    }

    /** 诊断 rootfs 关键路径状态 */
    public String diagnoseRootfs() {
        StringBuilder sb = new StringBuilder();
        sb.append("rootfs 路径: ").append(rootfsDir.getAbsolutePath()).append("\n");
        File bash = new File(rootfsDir, "usr/bin/bash");
        sb.append("usr/bin/bash 存在=").append(bash.exists())
          .append(bash.exists() ? " 大小=" + bash.length() : "").append("\n");
        File ld = new File(rootfsDir, "usr/lib/aarch64-linux-gnu/ld-linux-aarch64.so.1");
        sb.append("ld-linux 存在=").append(ld.exists()).append("\n");
        File etc = new File(rootfsDir, "etc/os-release");
        sb.append("etc/os-release 存在=").append(etc.exists()).append("\n");
        sb.append("已安装标记=").append(markerFile.exists());
        return sb.toString();
    }

    public void uninstall() {
        try {
            new ProcessBuilder("/system/bin/rm", "-rf", baseDir.getAbsolutePath())
                    .redirectErrorStream(true).start().waitFor();
        } catch (Exception e) {
            deleteRecursively(baseDir);
        }
    }

    /**
     * 递归删除，<b>不跟随符号链接</b>；判据与实现都在 {@link DataPreserve}（那边有断言）。
     *
     * <p>原来这份跟随链接：{@code File.isDirectory()} 对「指向目录的软链」返回 true，
     * 于是会递归进去删掉链接目标里的东西。而 {@code /root/.dsh/sessions} 正指向
     * {@code Documents/dshdata} —— {@link #uninstall()} 里 {@code rm -rf} 起不来时的
     * 兜底就走这里，跟随一次就把用户对话删了，而「卸载不带走公开目录数据」是这个项目
     * 的设计前提。{@code HarnessController} 那份早就防住了，只有这份漏着。
     */
    private void deleteRecursively(File f) {
        DataPreserve.deleteRecursively(f);
    }

    /** 递归拷贝目录/文件（重解压前数据保护用） */
    /**
     * 递归复制，**符号链接原样保留、绝不跟随** —— 实现与它防的那个坑见 {@link FileCopy}。
     *
     * <p>简版理由：{@code .dsh/sessions} 这些是指向 {@code /sdcard/Documents/dshdata} 的软链，
     * 跟随复制会把几 GB 对话搬进 {@code .data-preserve-*}，还原后软链变真目录，
     * 下次启动 migrate 脚本又在公开侧留一份 {@code *.conflict-<ts>}。
     */
    private void copyRecursively(File src, File dst) throws IOException {
        FileCopy.copyPreservingLinks(src, dst);
    }


    /** 拷贝单个文件 */
    private void copyFile(File src, File dst) throws IOException {
        if (dst.getParentFile() != null && !dst.getParentFile().exists() && !dst.getParentFile().mkdirs()) {
            throw new IOException("无法创建父目录: " + dst.getParentFile());
        }
        try (java.io.InputStream in = new java.io.FileInputStream(src);
             java.io.OutputStream out = new java.io.FileOutputStream(dst)) {
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) != -1) out.write(buf, 0, n);
        }
    }

}
