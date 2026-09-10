package com.deepseekharness.app.util;

/**
 * 「哪些进程算 Web 进程、怎么把它们找出来」的<b>唯一定义</b>（纯逻辑，不碰 Android API ——
 * 断言在 JVM 单元测试里）。
 *
 * <p><b>为什么要单独一个类</b>：停止功能已经改坏过三轮，每一轮的病根都在「判据」上，
 * 而判据当时散在四个地方各写一遍 —— 容器内的停止脚本、看门狗脚本、兜底杀、Android 侧扫
 * {@code /proc}。改一处漏三处，症状还都长得一样（点了停止没反应 / 停了又复活），
 * 极难分辨是哪一层没生效。收成一份之后，「该匹配什么」变成可以写断言钉住的东西：
 *
 * <ul>
 *   <li>真机上 dsh 的实际 cmdline（{@code node --expose-internals
 *       /usr/local/lib/node_modules/@deepseek-ai/dsh/lib/bin.js web}）必须被认出来；</li>
 *   <li>容器启动器（{@code libproot.so} / {@code libproroot}）<b>绝不能</b>被认成目标：
 *       杀到它等于把整个环境连 App 一起带走；</li>
 *   <li>用户自己或 agent 跑的 node 进程<b>不能</b>被误杀（曾经用 {@code pkill -f node}）。</li>
 * </ul>
 */
public final class WebProcSel {

    private WebProcSel() {
    }

    /**
     * 「用户已停止」哨兵（容器内路径）。存在即表示：<b>任何容器内的自动拉起路径都必须放弃</b>。
     *
     * <p>App 侧的判据管不到容器里的拉起者 —— 看门狗和它写的重启脚本是独立的 bash 进程。
     * 停止时只要漏杀一个，它下一轮就把 WebUI 拽回来，用户看到的正是<b>「停止之后 dsh 秒复活」</b>。
     * 哨兵不赌：拉起者每轮自己检查，看见就退出。删除点只有一处 —— 用户明确要启动的时候。
     */
    public static final String STOP_SENTINEL = "/root/.dsha-stopped";

    /** 端口的 {@code /proc/net/tcp} 表示（大写十六进制、补到 4 位）。 */
    public static String portHex(int port) {
        return String.format(java.util.Locale.ROOT, "%04X", port & 0xFFFF);
    }

    /**
     * Web 进程自己写下的 pid 文件（容器内路径）。
     *
     * <p><b>为什么必须有它</b>：前两版停止都在「按 cmdline 找进程」这条路上打转，而那条路
     * 有两个环境性的硬限制，在这台机器上实测确认过：
     * <ul>
     *   <li>{@code /proc/net/tcp} 对非 root App <b>读不到</b>（Permission denied，
     *       Android 10+ 收紧了 /proc/net）—— 所以 {@code ss}/{@code netstat}/端口反查
     *       全都只能拿到空结果，而且是<b>静默</b>的空；</li>
     *   <li>{@code /proc} 只看得到<b>同 uid</b> 的进程（Android 的 hidepid）。</li>
     * </ul>
     *
     * <p>pid 文件绕开这两条：启动方就是我们自己，{@code exec} 之前把 {@code $$} 写下来 ——
     * 因为 {@code exec} 用 node 顶替当前 shell、<b>pid 不变</b>，这个数就是 node 的 pid。
     * 停止时直接按 pid 杀，不猜命令行长相、不依赖任何工具。
     */
    public static final String PID_WEB = "/root/.dsha-web.pid";
    public static final String IDENTITY_WEB = "/root/.dsha-web.identity";

    /** 看门狗自己写下的 pid 文件（容器内路径）。 */
    public static final String PID_WATCHDOG = "/root/.dsha-watchdog.pid";

    /** PID 文件只接受单个正整数；拒绝 PID 1、进程组及任何 shell 语法。 */
    public static int parsePid(String value) {
        if (value == null || value.length() > 32) return -1;
        String digits = value.trim();
        if (!digits.matches("[1-9][0-9]{0,9}")) return -1;
        try { int pid = Integer.parseInt(digits); return pid > 1 ? pid : -1; }
        catch (NumberFormatException ignored) { return -1; }
    }

    /** pid 文件相对 rootfs 根的路径 —— Android 侧要用 {@code File} 直接读它。 */
    public static String pidFileRel(String guestPath) {
        return guestPath.startsWith("/") ? guestPath.substring(1) : guestPath;
    }

    /**
     * Android 侧判据：这条 cmdline 该不该被当作「Web 进程」杀掉。
     *
     * <p>顺序有意义：<b>先排除容器启动器</b>，再匹配目标。反过来写的话，
     * proot 的命令行里带着 rootfs 路径与待执行命令，{@code bin.js}、{@code web}
     * 都可能出现在里面 —— 于是第一个被杀的就是承载整个环境的那个进程。
     */
    public static boolean looksLikeWeb(String cmdline) {
        if (cmdline == null || cmdline.isEmpty()) return false;
        // proroot 的 bridge 是 guest 可执行程序本身，父进程 libproroot.so 才是启动器。
        // 只接受已知的 Node + dsh web 参数序列，不能因命令中带有 bridge 名称就放行。
        String[] argv = cmdline.indexOf('\0') >= 0 ? cmdline.split("\u0000") : cmdline.trim().split("\\s+");
        if (argv.length > 0 && basename(argv[0]).equals("libproroot-bridge.so"))
            return isProrootWebPayload(argv);
        if (cmdline.contains("libproot.so") || cmdline.contains("libproroot")
                || cmdline.contains("proot")) {
            return false;
        }
        cmdline = cmdline.replace('\0', ' ');
        return maySignalWeb(cmdline) || (cmdline.contains("bin.js") && cmdline.contains("web"))
                || cmdline.contains("dsh web")
                || cmdline.contains("dsh-app-boot")
                || cmdline.contains("dsh-cli")
                || cmdline.contains("dsh-watchdog.sh")
                // 重启脚本：它正在跑意味着「马上会有一个新的 Web 进程」，
                // 停止时漏掉它，等于停完一两秒后又冒出来一个（「秒复活」）
                || cmdline.contains("dsh-cmd.txt")
                || cmdline.contains("dsh-web-restart.sh");
    }

    private static boolean isProrootWebPayload(String[] argv) {
        if (argv.length < 9 || !basename(argv[1]).equals("libproroot-linker.so")
                || !argv[2].equals("--argv0") || !argv[3].equals("node")
                || !argv[4].equals("--preload") || !basename(argv[5]).equals("libproroot-runtime.so")
                || !argv[6].endsWith("/usr/local/bin/node")) return false;
        int entry = 7;
        if (argv[entry].equals("--expose-internals")) entry++;
        if (entry + 1 >= argv.length) return false;
        return (argv[entry].equals("/usr/local/bin/dsh")
                || argv[entry].equals("/usr/local/lib/node_modules/@deepseek-ai/dsh/lib/bin.js"))
                && webArguments(argv, entry + 1);
    }

    private static String basename(String path) {
        return path.substring(path.lastIndexOf('/') + 1);
    }

    /** 发信号须是直接运行 dsh web 的 Node，不能命中 shell 参数中的文本。 */
    public static boolean maySignalWeb(String cmdline) {
        if (cmdline == null || cmdline.isEmpty()) return false;
        String[] args = cmdline.indexOf('\0') >= 0 ? cmdline.split("\u0000") : cmdline.trim().split("\\s+");
        if (args.length > 0 && basename(args[0]).equals("libproroot-bridge.so")) return isProrootWebPayload(args);
        if (args.length < 3 || !(basename(args[0]).equals("node") || basename(args[0]).equals("nodejs"))) return false;
        int entry = args[1].equals("--expose-internals") ? 2 : 1;
        if (entry + 1 >= args.length) return false;
        return (args[entry].equals("/usr/local/bin/dsh") || args[entry].endsWith("/node_modules/@deepseek-ai/dsh/lib/bin.js"))
                && webArguments(args, entry + 1);
    }

    private static boolean webArguments(String[] args, int index) {
        return args[index].equals("web") || (index + 1 < args.length && args[index].equals("--profile")
                && args[index + 1].matches("dsha-recovery-[0-9a-f]{16}"));
    }
}
