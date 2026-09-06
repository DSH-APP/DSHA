package com.deepseekharness.app;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;

import java.io.BufferedReader;
import java.io.File;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 核心控制器：分步安装（rootfs / 基础工具 / Node / deepseek-harness，每步可单独
 * 重装或更新，多镜像自动测速选最快源）、一键补装、Web UI 启停。
 *
 * 进度/错误状态保存在单例中，通过 {@link StateListener} 通知 UI；
 * Fragment 切换时状态不丢失，避免异步回调打到已销毁视图上。
 */
public class HarnessController {

    // ================= 分步安装步骤 =================
    public static final int STEP_ROOTFS = 1;
    public static final int STEP_TOOLS = 2;
    public static final int STEP_NODE = 3;
    public static final int STEP_PNPM = 4;
    public static final int STEP_HARNESS = 5;
    public static final int STEP_GUARD = 6;

    // ================= 下载任务（用于源选择记忆） =================
    public static final int TASK_ROOTFS = 1;
    public static final int TASK_NODE = 2;
    public static final int TASK_HARNESS = 3;

    private static final String PREFS = "deepseekharness";

    // ================= 统一 GitHub 加速代理 =================
    /** 用户指定的 GitHub 反向代理前缀（前缀式拼接，如: <代理>/https://github.com/...）。 */
    public static final String GH_PROXY = "https://gh.fplj123580.qzz.io/";
    private static final String[] GH_PROXY_HOSTS = {
            "https://github.com/",
            "https://raw.githubusercontent.com/",
            "https://api.github.com/",
            "https://codeload.github.com/",
    };

    /** 给 github 系链接加统一代理前缀；非 github / 已带代理前缀的保持原样。 */
    public static String gitHubProxy(String u) {
        if (u == null) return u;
        if (u.startsWith(GH_PROXY)) return u;
        for (String host : GH_PROXY_HOSTS) {
            if (u.startsWith(host)) return GH_PROXY + u;
        }
        return u;
    }

    /** 市场索引本地缓存新鲜期（命中直接秒开，不请求网络）。
     *  包级可见：{@link PluginController} 要用（插件市场那块已搬出去）。 */
    static final long MARKET_CACHE_TTL_MS = 6L * 3600 * 1000;

    private static HarnessController instance;
    private static final ExecutorService IO = Executors.newSingleThreadExecutor();

    private final Context appContext;
    private final SharedPreferences prefs;
    private final ProotBootstrap proot;
    /** 插件与插件市场那一整块职责（原先内联在本类里，1662 行）。 */
    private final PluginController plugins;

    // ================= 进度/错误状态（跨 Fragment 保持） =================
    public interface StateListener {
        void onStateChanged();
    }

    private final List<StateListener> stateListeners = new CopyOnWriteArrayList<>();
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private volatile String stage = "";
    private volatile int percent = 0;
    private volatile String message = "";
    private volatile String error = "";
    private volatile boolean busy = false;
    /** busy 置位时间戳：超时自愈用（任务卡死 >10 分钟强制释放，防 App 假死） */
    private volatile long busySince = 0;
    /** busy 超时阈值：安装/启动单步不应超过 10 分钟 */
    private static final long BUSY_STALE_MS = 10 * 60 * 1000L;
    private volatile int currentStep = 0;
    private volatile Process webProcess;
    /** Web 进程“代际”/硬重启计数：让启动页感知重启并刷新预览（拿到最新 manifest/插件） */
    private volatile long webEpoch = System.currentTimeMillis();
    private volatile long hardRestartEpoch = 0;
    /** 强重启/深停机配套 */
    private final java.util.concurrent.atomic.AtomicBoolean webRestartLock = new java.util.concurrent.atomic.AtomicBoolean(false);
    private final Object webStartLock = new Object();
    private boolean webStarting = false;
    private final java.util.Set<Process> webProcesses = java.util.concurrent.ConcurrentHashMap.newKeySet();

    /** 会话自愈进行中：期间禁止 prewarm/keepAlive 拉起 web，防边修边写。 */
    private static volatile boolean healingSession = false;
    public static boolean isHealingSession() { return healingSession; }

    public long getWebEpoch() { return webEpoch; }
    public void bumpWebEpoch() { webEpoch = System.currentTimeMillis(); }
    public long getHardRestartEpoch() { return hardRestartEpoch; }
    public void bumpHardRestart() { hardRestartEpoch = System.currentTimeMillis(); }
    public boolean isWebRestartLocked() { return webRestartLock.get(); }
    public boolean tryAcquireWebRestartLock() { return webRestartLock.compareAndSet(false, true); }
    public void releaseWebRestartLock() { webRestartLock.set(false); }

    /** 端口探测：ms 超时内是否可连接 Web 端口 */
    /** 端口探测的三态结果：UP / DOWN / UNKNOWN。
     *
     *  以前一律 catch 成 false，把「不知道」当成了「没起来」：连接被拒确实说明
     *  端口上没人听，但超时可能只是启动慢或系统正忙 —— 两者混在一起，UI 就会
     *  在服务其实正在起的时候报「启动失败」，用户白白重试。 */
    private String probeWebPort(int timeoutMs) {
        try (java.net.Socket s = new java.net.Socket()) {
            s.connect(new java.net.InetSocketAddress("127.0.0.1", parsePort()), timeoutMs);
            return "UP";
        } catch (java.net.ConnectException e) {
            return "DOWN";     // 明确被拒 = 这个端口上确实没有人在监听
        } catch (java.net.SocketTimeoutException e) {
            return "UNKNOWN";  // 超时：可能还在启动，也可能系统正忙
        } catch (Exception e) {
            return "UNKNOWN";  // 其它异常同样不构成「没起来」的证据
        }
    }

    private boolean isWebPortUp(int timeoutMs) {
        return "UP".equals(probeWebPort(timeoutMs));
    }

    /** 轮询等待 Web 端口彻底关闭；maxMs 内仍被占用返回 false */
    private boolean waitPortClosed(long maxMs) {
        int port = parsePort();
        long deadline = System.currentTimeMillis() + maxMs;
        while (System.currentTimeMillis() < deadline) {
            try (java.net.Socket s = new java.net.Socket()) {
                s.connect(new java.net.InetSocketAddress("127.0.0.1", port), 200);
                try { Thread.sleep(250); } catch (InterruptedException ignored) { }
            } catch (Exception e) {
                return true; // 端口已不可达
            }
        }
        return false;
    }

    /** 轮询等待 Web 端口就绪；maxMs 内仍不可达返回 false（启动超时） */
    private boolean waitWebPortUp(long maxMs) {
        long deadline = System.currentTimeMillis() + maxMs;
        while (System.currentTimeMillis() < deadline) {
            if (isWebPortUp(300)) return true;
            try { Thread.sleep(500); } catch (InterruptedException ignored) { }
        }
        return false;
    }

    private void destroyAllWebProcesses() {
        for (Process p : webProcesses) {
            try { p.destroy(); } catch (Throwable ignored) {
            }
        }
        try { webProcesses.clear(); } catch (Throwable ignored) {
        }
        synchronized (webStartLock) {
            webProcess = null;
        }
    }

    /** 同步停止（等端口关透）：供强重启/插件变更使用；常规杀不净则宽杀 node */
    /** 在 **Android 侧**扫 /proc 找 web 进程并精确杀掉，返回杀了几个。
     *
     *  为什么不在容器内 pkill：proot 不隔离 PID，容器里 /proc 看到的是宿主全部进程，
     *  pkill -f 的信号会落到 proot 乃至 App 自己身上 —— 实测就这么被杀过，
     *  现象是「点重启约十秒后闪退，通知栏一起消失」。
     *
     *  做法参考 andClaw（proroot 作者自己的 App，同样是 Android→容器→Node→AI agent
     *  这套结构）：记 pid、读 /proc/<pid>/cmdline 核对身份、用
     *  android.os.Process.killProcess 精确杀。好处是不依赖容器内命令，
     *  也就**不受容器运行时切换影响** —— 换 proroot 后不用改这里。
     *
     *  非 root 应用只能杀同 uid 的进程，而容器内进程都是本 App fork 出来的，
     *  正好够用；顺带天然不会误伤系统或其它应用。 */
    private int killWebProcessesFromAndroid() {
        int killed = 0;
        try {
            String[] pids = new java.io.File("/proc").list();
            if (pids == null) return 0;
            int self = android.os.Process.myPid();
            for (String name : pids) {
                if (name.isEmpty() || name.charAt(0) < '0' || name.charAt(0) > '9') continue;
                int pid;
                try {
                    pid = Integer.parseInt(name);
                } catch (NumberFormatException e) {
                    continue;
                }
                if (pid == self) continue;
                String cmd = readProcCmdline(pid);
                if (cmd == null || cmd.isEmpty()) continue;
                // 判据（含「绝不碰容器启动器」那一条）统一在 WebProcSel.looksLikeWeb ——
                // 与容器内那份 shell 判据同源，改一处不会漏另一处；断言见 pure-logic-test
                if (!WebProcSel.looksLikeWeb(cmd)) continue;
                try {
                    android.os.Process.killProcess(pid);
                    killed++;
                    android.util.Log.w("DSHA", "已杀 web 进程 pid=" + pid + " cmd=" + tail(cmd, 80));
                } catch (Throwable e) {
                    android.util.Log.w("DSHA", "杀 pid=" + pid + " 失败: " + e);
                }
            }
        } catch (Throwable e) {
            android.util.Log.w("DSHA", "Android 侧扫进程失败: " + e);
        }
        return killed;
    }

    /**
     * 按 pid 文件在 <b>Android 侧</b>杀 Web 与看门狗 —— 不依赖容器内的 /proc 视图，
     * 也不依赖 cmdline 长相。这是停止链路上最可靠的一环。
     *
     * <p>为什么需要它：容器内那套判据有两个环境性硬限制（都实测过）——
     * {@code /proc/net/tcp} 非 root 读不到，{@code /proc} 只看得到同 uid 的进程。
     * 而 pid 是我们启动时自己写下的（{@code exec} 前的 {@code $$}），
     * App 侧直接按 pid 送信号就行。
     *
     * <p><b>信号别乱选</b>：常规停止必须先送 {@code SIGTERM}（15），让 dsh 有机会 flush
     * SQLite —— 直接 {@code SIGKILL} 会把会话写坏（用户报过
     * {@code SessionPersistenceCorruptionError}）。{@code Process.killProcess()} 送的正是
     * KILL，所以这里改成显式传信号，只有兜底那一轮才用 9。
     *
     * <p>返回的文字直接进活动日志，真机首验时它能一次性定性三件事：pid 文件在不在、
     * App 侧看不看得见那个 pid（看不见 = dsh 不在本 App uid 下，那是另一类问题）、
     * 有没有送成功。
     */
    private String killByPidFiles(int signal) {
        StringBuilder log = new StringBuilder();
        String[][] targets = {
                {WebProcSel.pidFileRel(WebProcSel.PID_WEB), "node", "Web"},
                {WebProcSel.pidFileRel(WebProcSel.PID_WATCHDOG), "dsh-watchdog", "看门狗"},
        };
        for (String[] it : targets) {
            java.io.File f = new java.io.File(proot.getRootfsDir(), it[0]);
            if (!f.isFile()) {
                log.append(it[2]).append("：没有 pid 文件（旧版本启动的，或从没起过）\n");
                continue;
            }
            String raw = "";
            try {
                raw = new String(java.nio.file.Files.readAllBytes(f.toPath()),
                        StandardCharsets.UTF_8).trim();
            } catch (Throwable ignored) {
            }
            int pid = -1;
            try {
                pid = Integer.parseInt(raw);
            } catch (Throwable ignored) {
            }
            if (pid <= 1) {
                log.append(it[2]).append("：pid 文件内容不是 pid（").append(raw).append("）\n");
                continue;
            }
            String cmd = readProcCmdline(pid);
            if (cmd == null || cmd.isEmpty()) {
                // 关键诊断：App 自己都看不到这个 pid。要么它已经退了，要么它不属于本 App 的
                // uid（例如经 ADB/shell 起的）—— 后一种情况 killProcess 也会无声失败，
                // 得换一整套思路，所以这行必须留在日志里。
                log.append(it[2]).append("：pid ").append(pid)
                   .append(" 在 App 侧 /proc 里看不到（已退出，或不在本 App uid 下）\n");
                continue;
            }
            if (!cmd.contains(it[1])) {
                // pid 会回卷复用，长相对不上就当这个文件过期了 —— 宁可少杀，不能杀错
                log.append(it[2]).append("：pid ").append(pid).append(" 长相对不上（")
                   .append(tail(cmd, 60)).append("），当过期忽略\n");
                continue;
            }
            try {
                android.os.Process.sendSignal(pid, signal);
                log.append(it[2]).append("：已送信号 ").append(signal)
                   .append(" 给 pid ").append(pid).append("\n");
            } catch (Throwable e) {
                log.append(it[2]).append("：送信号给 pid ").append(pid)
                   .append(" 失败 ").append(e).append("\n");
            }
        }
        return log.toString().trim();
    }

    private String readProcCmdline(int pid) {
        try {
            byte[] b = java.nio.file.Files.readAllBytes(
                    new java.io.File("/proc/" + pid + "/cmdline").toPath());
            // cmdline 用 \0 分隔参数
            return new String(b, StandardCharsets.UTF_8).replace('\0', ' ').trim();
        } catch (Throwable e) {
            return null;
        }
    }

    /** 安全杀 web 进程：判据与停止那条路<b>共用同一份</b>（{@link WebProcSel#pidsDsh}）。
     *
     *  <p>这里原来是 {@code pgrep -f 'bin.js web'} 那一套，有两个毛病：
     *  <ul>
     *    <li><b>会自杀</b>：这段是通过 {@code bash -c "<整段脚本>"} 跑的，那条 shell 的
     *        cmdline 里<b>含着模式串本身</b>，pgrep 于是把执行它的 shell 也列了出来；
     *        随后的 {@code grep libproot} 只挡容器启动器、挡不住自己 —— 脚本在第一条
     *        kill 就死了，后面的 SIGKILL 兜底永远不执行。这正是「点了停止没反应」那一类
     *        症状的成因，停止主路径修过一次，这条兜底路径漏了。</li>
     *    <li>判据与主路径分家：主路径认 {@code dsh-app-boot} / 重启脚本，这里不认。</li>
     *  </ul>
     *
     *  <p>{@code pids_dsh} 自带两层排除：含 {@code pids_dsh} 的（也就是本脚本自己）与
     *  proot/proroot（杀了它等于把整个环境连 App 一起带走，这个坑踩过）。 */
    private String safeKillWebCmd(String sig) {
        return WebProcSel.pidsDsh(true) + WebProcSel.pidsFile()
                + "for _p in $(pids_file; pids_dsh); do kill -" + sig + " \"$_p\" 2>/dev/null; done; ";
    }

    public void stopWebAndWait() {
        try {
            destroyAllWebProcesses();
            // 与 stopWeb 同一条链路：先按 pid 文件在 App 侧精确送 TERM（不受容器 /proc 视图
            // 限制）。这里同样**不能**用 KILL —— 重启也要让 dsh 有机会 flush SQLite，
            // 后面 stopWebCommand 自带 TERM → sleep 3 → KILL 的兜底。
            killByPidFiles(15);
            proot.execAndRead(stopWebCommand());
            if (!waitPortClosed(5000)) {
                // 只杀 dsh web 相关进程（bin.js web / dsh web），不裸杀 node
                // （裸 pkill -f node 会误杀 agent/用户跑的其他 node 进程！）
                // 先在 Android 侧按 pid 精确杀（不受容器运行时影响），
                // 仍没关透再用容器内那条兜底
                killWebProcessesFromAndroid();
                proot.execAndRead(safeKillWebCmd("TERM") + "sleep 3; "
                        + safeKillWebCmd("9") + "sleep 1; echo done");
                waitPortClosed(5000);
            }
            // Web 停了桥也没用：停桥（幂等，HarnessService.onDestroy 也会停）
            LanProxyService.stop();
        } catch (Throwable ignored) {
        }
    }

    /** 强重启（进程级，杀干净）：先深停 web → 杀 App 进程 → Alarm 拉起全新进程 */
    public void restartAppProcess(final android.content.Context ctx) {
        new Thread(() -> {
            try {
                destroyAllWebProcesses();
                proot.execAndRead(stopWebCommand());
                waitPortClosed(6000);
            } catch (Throwable ignored) {
            }
            try { Thread.sleep(300); } catch (InterruptedException ignored) {
            }
            try {
                android.app.AlarmManager am = (android.app.AlarmManager)
                        ctx.getSystemService(android.content.Context.ALARM_SERVICE);
                android.content.Intent i = new android.content.Intent(ctx, MainActivity.class);
                i.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK | android.content.Intent.FLAG_ACTIVITY_CLEAR_TASK);
                android.app.PendingIntent pi = android.app.PendingIntent.getActivity(
                        ctx, 0, i,
                        android.app.PendingIntent.FLAG_UPDATE_CURRENT | android.app.PendingIntent.FLAG_IMMUTABLE);
                if (am != null) am.set(android.app.AlarmManager.RTC, System.currentTimeMillis() + 350, pi);
            } catch (Throwable ignored) {
            }
            new android.os.Handler(android.os.Looper.getMainLooper()).postDelayed(() -> {
                try {
                    android.os.Process.killProcess(android.os.Process.myPid());
                } catch (Throwable ignored) {
                }
            }, 250);
        }).start();
    }

    /** 是否正在“自动补构建”（缺 bin.js 时启动触发） */
    public boolean isBuilding() {
        try {
            return new java.io.File(proot.getRootfsDir(), "root/.dsha-building").isFile();
        } catch (Throwable e) {
            return false;
        }
    }

    /** 解析 rootfs ~/dsh-web.log 尾部，抽取关键错误给启动页展示 */
    public String diagnoseWebFailure() {
        try {
            java.io.File f = new java.io.File(proot.getRootfsDir(), "root/dsh-web.log");
            if (!f.isFile() || f.length() == 0) return "未找到 WebUI 日志（~/dsh-web.log）";
            long len = Math.min(f.length(), 16384);
            byte[] bytes;
            try (java.io.FileInputStream in = new java.io.FileInputStream(f)) {
                bytes = new byte[(int) len];
                int off = 0;
                while (off < bytes.length) {
                    int n = in.read(bytes, off, bytes.length - off);
                    if (n < 0) break;
                    off += n;
                }
            }
            String log = new String(bytes, java.nio.charset.StandardCharsets.UTF_8);
            java.util.regex.Matcher m = java.util.regex.Pattern.compile("Cannot find module '([^']+)'").matcher(log);
            if (m.find()) {
                String mod = m.group(1);
                if (mod.endsWith("apps/cli/lib/bin.js")) {
                    return "入口文件缺失：" + mod + "\n（deepseek-harness 未构建成功；请点「重启」自动补构建，或重跑安装步骤⑤）";
                }
                return "缺少模块：" + mod + "\n（依赖安装不完整，已自动重装；仍失败请重跑步骤⑤）";
            }
            // 一类会把用户锁在外面的死锁：dsh 的插件列表里会列出它自己的依赖
            // cordis-plugin-hmr（热重载），而那个插件要求 node 带 --expose-internals。
            // 用户在 WebUI 里手一点启用，重启后整个 profile 加载失败 → Web 起不来
            // → 进不去 WebUI → 也就没法把它关掉。自动在 patch 层禁用它。
            if (log.contains("--expose-internals is required")
                    || (log.contains("failed to apply loader entry") && log.contains("plugin-hmr"))) {
                String heal = runHealProfileBoot();
                if (heal != null && heal.contains("HEAL_PROFILE_OK: 禁用")) {
                    return "已自动关闭热重载插件（cordis-plugin-hmr）—— 它需要 Node 的 "
                            + "--expose-internals 启动参数，缺了会让整个 profile 加载失败。\n"
                            + "点「重启」即可正常进入。普通使用不需要热重载；想恢复可编辑 "
                            + "~/.dsh/profiles/web/cordis.patch.yml 删掉那几行。";
                }
                return "WebUI 启动失败：启用了需要 Node --expose-internals 的插件"
                        + "（cordis-plugin-hmr 热重载）。\n自动关闭没成功，可手动编辑 "
                        + "~/.dsh/profiles/web/cordis.patch.yml，在末尾加：\n"
                        + "- id: <日志里 entry 后面那串 id>\n  disabled: true";
            }
            if (log.contains("MODULE_NOT_FOUND")) {
                return "MODULE_NOT_FOUND：入口依赖缺失，请重跑安装步骤⑤（应用已自动尝试自愈）";
            }
            java.util.regex.Matcher vm = java.util.regex.Pattern.compile("ValidationError[^\\n]*").matcher(log);
            if (vm.find()) {
                return "配置校验失败：" + vm.group().trim() + "\n（已自动钳制超限配置；仍有问题可重置配置）";
            }
            String tail = log.trim();
            int nl = tail.lastIndexOf('\n');
            if (nl >= 0) tail = tail.substring(nl + 1);
            if (log.contains("dsh web:")) {
                // 探测结果分三种，措辞也要分三种 —— 以前「超时」被说成「未就绪」，
                // 而那时候服务往往正在起，用户被引导去做多余的重启
                String probe = probeWebPort(600);
                if ("UP".equals(probe)) {
                    return "Web 服务正在运行但页面探测失败，可点「打开预览」或「重启」再试。";
                }
                if ("UNKNOWN".equals(probe)) {
                    return "Web 已打印启动地址，但探测端口超时（没被拒绝，也没连上）——"
                            + "通常是还在加载依赖或系统正忙。\n先等十几秒再点「打开预览」；"
                            + "一直这样再看 ~/dsh-web.log。";
                }
                return "Web 启动过（已打印 URL），但端口 " + parsePort()
                        + " 上没有服务在监听（连接被拒）：\n"
                        + "多为进程随后退出、端口被别的应用占用，或依赖加载失败。\n"
                        + "点「重启」再试；仍不行请查看 ~/dsh-web.log 完整内容。";
            }
            return tail.isEmpty() ? "WebUI 异常退出（日志为空）" : "WebUI 异常退出：\n" + tail;
        } catch (Exception e) {
            return "无法解析 WebUI 日志：" + e.getMessage();
        }
    }
    /** 启动失败自愈：把「启用了却跑不起来」的 loader entry 在 profile 的 patch 层禁用。
     *  只针对已知需要特殊 node 标志的插件；别的失败原因（缺依赖、配置错）不该靠禁用掩盖。 */
    private String runHealProfileBoot() {
        try {
            String script = readAsset("heal-profile-boot.py");
            if (script == null || script.isEmpty()) return null;
            java.io.File dst = new java.io.File(proot.getRootfsDir(), "root/.dsha-heal-profile.py");
            if (dst.getParentFile() != null) dst.getParentFile().mkdirs();
            java.nio.file.Files.write(dst.toPath(),
                    script.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            String out = proot.execAndRead(
                    "python3 /root/.dsha-heal-profile.py --log /root/dsh-web.log"
                            + " --profile /root/.dsh/profiles/web 2>&1; "
                            + "rm -f /root/.dsha-heal-profile.py", 60_000);
            android.util.Log.i("DSHA", "profile 启动自愈: " + (out == null ? "无输出" : out.trim()));
            return out;
        } catch (Throwable e) {
            android.util.Log.w("DSHA", "profile 启动自愈失败: " + e);
            return null;
        }
    }

    /** 局域网 0.0.0.0 放行补丁是否已就绪（防重复打补丁） */
    private volatile boolean lanBindReady = false;
    /** 进度持久化节流用时间戳 */
    private volatile long lastStageWriteTs = 0;

    // ===== 步骤状态缓存（UI 频繁查询，其内部会起 proot 检查，慢） =====
    /** 步骤缓存时间戳，-1 表示无效需重算 */
    private volatile long stepCacheTs = -1;
    private final boolean[] stepCache = new boolean[7];
    /** 步骤"可更新"缓存（装了旧版但未达标：⑤ dsh 旧版 / ⑥ 守卫版本旧）；
     *  与 stepCache 同一次 proot 查询算出，同生命周期。 */
    private final boolean[] updatableCache = new boolean[7];

    /** 使步骤缓存失效：安装结束/空闲时调用，让 UI 拿到最新状态 */
    private void invalidateStepCache() {
        stepCacheTs = -1;
    }

    /** 供 UI 层（如卸载环境后）强制刷新步骤状态 */
    public void invalidateSteps() {
        invalidateStepCache();
    }

    // ===== 下载源自选（测速 → 弹窗等待用户选择） =====
    private final Object sourceLock = new Object();
    private volatile boolean awaitingSource = false;
    private volatile int sourceChoice = -1;
    private volatile int pendingTask = 0;
    private volatile String[] pendingUrls = null;
    private volatile long[] pendingLat = null;

    public static synchronized HarnessController get(Context ctx) {
        if (instance == null) {
            instance = new HarnessController(ctx.getApplicationContext());
        }
        return instance;
    }

    private HarnessController(Context ctx) {
        this.appContext = ctx;
        this.prefs = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        this.proot = new ProotBootstrap(ctx);
        this.plugins = new PluginController(this, ctx, this.proot);
        // issue #48：桥没启动过时 HttpShellService 拿不到 Context，
        // ensureToken() 里 tokenFileIfPossible() 返回 null —— 于是它只在内存里生成
        // 一个 token 就返回，**没有写进 /root/.dsh/.bridge_token**。启动页把这个内存
        // token 拼进 URL，而 dsh 服务端校验的是文件里那个（webserver-auth-patch.sh 读文件），
        // 文件压根不存在 → 用户看到「需要 token」闸门，进不去 WebUI。
        // 原先只有自检路径绑过 Context，那要用户先去点一次自检才碰巧修好。
        // 控制器一建好就绑上，token 生成从此总能落盘。
        HttpShellService.bindTokenContext(ctx);
    }

    public void addStateListener(StateListener l) { stateListeners.add(l); }
    public void removeStateListener(StateListener l) { stateListeners.remove(l); }
    public String getStage() { return stage; }
    public int getPercent() { return percent; }
    public String getMessage() { return message; }
    public String getError() { return error; }
    /** 是否忙碌。带超时自愈：busy 卡住超过阈值视为假死，自动释放并记录。 */
    public boolean isBusy() {
        if (busy && busySince > 0 && System.currentTimeMillis() - busySince > BUSY_STALE_MS) {
            // 自愈：任务卡死超时，强制释放 busy，避免后续操作全部被挡（App 假死）
            android.util.Log.w("DSHA", "busy 超时自愈：任务卡死超过 " + (BUSY_STALE_MS / 60000) + " 分钟，强制释放");
            busy = false;
            busySince = 0;
            error = "上一次操作超时被自愈（可能是网络/环境问题），请重试";
        }
        return busy;
    }

    /** 尝试原子获取 busy（防并发重入）；获取失败返回 false */
    public boolean tryBeginBusy() {
        if (isBusy()) return false; // isBusy 内部已处理超时自愈
        synchronized (this) {
            if (busy) return false;
            busy = true;
            busySince = System.currentTimeMillis();
            return true;
        }
    }

    /** 当前正在执行的步骤（0 = 空闲） */
    public int getCurrentStep() { return currentStep; }

    private void setState(String stage, int percent, String msg, String err, boolean b) {
        this.stage = stage;
        this.percent = percent;
        this.message = msg;
        this.error = err;
        this.busy = b;
        this.busySince = b ? System.currentTimeMillis() : 0;
        // 持久化进度，闪退后下次启动可定位中断步骤（节流：最多 2 秒写一次，避免磁盘 IO 卡顿）
        if (!stage.isEmpty()) {
            long now = System.currentTimeMillis();
            if (now - lastStageWriteTs > 2000) {
                lastStageWriteTs = now;
                prefs.edit().putString("last_stage", stage + " " + percent + "%").apply();
            }
        }
        if (err != null && !err.isEmpty()) {
            prefs.edit().putString("last_error", err).apply();
        }
        // 状态可能在 IO 线程变更，回调需切回主线程再通知 UI
        mainHandler.post(() -> {
            for (StateListener l : stateListeners) l.onStateChanged();
        });
        // 空闲时（busy=false）步骤状态可能已变化，失效缓存让 UI 下次查到最新值；
        // busy 期间步骤不变，保留缓存避免每次进度广播都重查 proot（否则极卡）
        if (!b) {
            invalidateStepCache();
        }
    }

    public String getLastStage() { return prefs.getString("last_stage", ""); }
    public String getLastError() { return prefs.getString("last_error", ""); }

    private void setProgress(String stage, int percent) {
        setState(stage, percent, "", "", true);
    }

    /** 生成可读的错误描述（含异常类名与堆栈首帧，便于排查） */
    /** 包级可见：{@link PluginController} 也要把异常转成人话。 */
    static String describe(Throwable e) {
        StringBuilder sb = new StringBuilder();
        sb.append(e.getClass().getSimpleName()).append(": ").append(e.getMessage());
        StackTraceElement[] st = e.getStackTrace();
        if (st != null && st.length > 0) {
            sb.append("\n    at ").append(st[0].toString());
        }
        return sb.toString();
    }

    /** 报错文案统一附加 App 版本号（方便确认用户是否用新版 APK） */
    private String errMsg(String prefix, Throwable e) {
        String v = "?";
        try {
            v = appContext.getPackageManager().getPackageInfo(appContext.getPackageName(), 0).versionName;
        } catch (Exception ignored) {
        }
        return prefix + "（DSHA v" + v + "）" + describe(e);
    }

    /** 步骤显示名 */
    public static String stepName(int step) {
        switch (step) {
            case STEP_ROOTFS: return "① Linux 环境（rootfs）";
            case STEP_TOOLS: return "② 基础工具（apt）";
            case STEP_NODE: return "③ Node.js";
            case STEP_PNPM: return "④ Node 附加工具（pnpm/node-gyp）";
            case STEP_HARNESS: return "⑤ deepseek-harness";
            case STEP_GUARD: return "⑥ 安全与补丁（守卫/dsh命令/polyfill）";
        }
        return "步骤 " + step;
    }

    /** 从 URL 取主机名（进度显示用） */
    private static String hostOf(String url) {
        try {
            String h = new java.net.URI(url).getHost();
            return h != null ? h : url;
        } catch (Exception e) {
            return url;
        }
    }

    // ================= 配置读写 =================
    /** API Key 的本地存储封装。
     *
     *  之前是明文存 SharedPreferences（"api_key"），然后明文写进 rootfs 的
     *  .dsh/.dsha-apikey、再随备份进 Download/DSHA（公共目录，任何有存储权限的
     *  App 都读得到）—— 这是个真实的泄露面。
     *
     *  参考 dsh-mobile（Apache-2.0）的做法：密钥经 Android Keystore 加密。
     *  这里用 Keystore 里的 AES-GCM 密钥 + 随机 nonce 做认证加密，密钥不出 Keystore。
     *  旧版 CBC 只保留读取迁移；新写入绝不降级成明文。
     *
     *  兼容迁移：旧版明文 "api_key" 仍在时，第一次读取会自动加密并清掉明文。
     *  解密失败（如用户清除 App 数据导致 Keystore 密钥丢失）回退空串，
     *  不崩、不卡死启动。 */
    private static final String KS_ALIAS = "dsha_apikey";          // 旧版 CBC，仅用于读迁移
    private static final String KS_ALIAS_GCM = "dsha_apikey_gcm";  // 新写入：认证加密
    private static final Object ksLock = new Object();

    private javax.crypto.SecretKey getOrCreateKey() throws Exception {
        synchronized (ksLock) {
            java.security.KeyStore ks = java.security.KeyStore.getInstance("AndroidKeyStore");
            ks.load(null);
            if (ks.containsAlias(KS_ALIAS)) {
                return (javax.crypto.SecretKey) ks.getKey(KS_ALIAS, null);
            }
            android.security.keystore.KeyGenParameterSpec spec =
                    new android.security.keystore.KeyGenParameterSpec.Builder(
                            KS_ALIAS,
                            android.security.keystore.KeyProperties.PURPOSE_ENCRYPT
                                    | android.security.keystore.KeyProperties.PURPOSE_DECRYPT)
                            .setBlockModes(android.security.keystore.KeyProperties.BLOCK_MODE_CBC)
                            .setEncryptionPaddings(
                                    android.security.keystore.KeyProperties.ENCRYPTION_PADDING_PKCS7)
                            .setUserAuthenticationRequired(false)
                            .build();
            javax.crypto.KeyGenerator kg = javax.crypto.KeyGenerator.getInstance(
                    android.security.keystore.KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore");
            kg.init(spec);
            return kg.generateKey();
        }
    }

    /** 新写入一律使用 GCM：旧 CBC 密钥的 block-mode 限制不能原地升级，故用独立 alias。 */
    private javax.crypto.SecretKey getOrCreateGcmKey() throws Exception {
        synchronized (ksLock) {
            java.security.KeyStore ks = java.security.KeyStore.getInstance("AndroidKeyStore");
            ks.load(null);
            if (ks.containsAlias(KS_ALIAS_GCM)) {
                return (javax.crypto.SecretKey) ks.getKey(KS_ALIAS_GCM, null);
            }
            android.security.keystore.KeyGenParameterSpec spec =
                    new android.security.keystore.KeyGenParameterSpec.Builder(
                            KS_ALIAS_GCM,
                            android.security.keystore.KeyProperties.PURPOSE_ENCRYPT
                                    | android.security.keystore.KeyProperties.PURPOSE_DECRYPT)
                            .setBlockModes(android.security.keystore.KeyProperties.BLOCK_MODE_GCM)
                            .setEncryptionPaddings(android.security.keystore.KeyProperties.ENCRYPTION_PADDING_NONE)
                            .setUserAuthenticationRequired(false)
                            .build();
            javax.crypto.KeyGenerator kg = javax.crypto.KeyGenerator.getInstance(
                    android.security.keystore.KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore");
            kg.init(spec);
            return kg.generateKey();
        }
    }

    /**
     * 新密钥用 AES-GCM（带认证标签）；Keystore 不可用时返回 null，绝不降级成明文。
     * 用户可以稍后重试输入，明文落进 SharedPreferences 或公共备份却无法收回。
     */
    private String encryptKey(String plain) {
        try {
            javax.crypto.Cipher c = javax.crypto.Cipher.getInstance("AES/GCM/NoPadding");
            c.init(javax.crypto.Cipher.ENCRYPT_MODE, getOrCreateGcmKey());
            byte[] iv = c.getIV();
            byte[] enc = c.doFinal(plain.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            return "GCM:" + android.util.Base64.encodeToString(iv, android.util.Base64.NO_WRAP)
                    + ":" + android.util.Base64.encodeToString(enc, android.util.Base64.NO_WRAP);
        } catch (Throwable e) {
            android.util.Log.w("DSHA", "加密 API key 失败，未保存该密钥: " + e.getClass().getSimpleName());
            return null;
        }
    }

    /** 兼容读取旧 CBC 格式；写入只走上面的 GCM。 */
    private String decryptKey(String stored) {
        if (stored == null || stored.isEmpty()) return "";
        if (stored.startsWith("PLAIN:")) return stored.substring(6); // 历史版本的失败降级，读后会迁移
        try {
            if (stored.startsWith("GCM:")) {
                String[] parts = stored.split(":", -1);
                if (parts.length != 3) return "";
                byte[] iv = android.util.Base64.decode(parts[1], android.util.Base64.NO_WRAP);
                byte[] enc = android.util.Base64.decode(parts[2], android.util.Base64.NO_WRAP);
                javax.crypto.Cipher c = javax.crypto.Cipher.getInstance("AES/GCM/NoPadding");
                c.init(javax.crypto.Cipher.DECRYPT_MODE, getOrCreateGcmKey(),
                        new javax.crypto.spec.GCMParameterSpec(128, iv));
                return new String(c.doFinal(enc), java.nio.charset.StandardCharsets.UTF_8);
            }
            // 旧版 AES-CBC；保留只读迁移，不再生成这类缺认证标签的密文。
            int sep = stored.indexOf(':');
            if (sep <= 0 || sep == stored.length() - 1) return "";
            byte[] iv = android.util.Base64.decode(stored.substring(0, sep), android.util.Base64.NO_WRAP);
            byte[] enc = android.util.Base64.decode(stored.substring(sep + 1), android.util.Base64.NO_WRAP);
            javax.crypto.Cipher c = javax.crypto.Cipher.getInstance("AES/CBC/PKCS7Padding");
            c.init(javax.crypto.Cipher.DECRYPT_MODE, getOrCreateKey(),
                    new javax.crypto.spec.IvParameterSpec(iv));
            return new String(c.doFinal(enc), java.nio.charset.StandardCharsets.UTF_8);
        } catch (Throwable e) {
            android.util.Log.w("DSHA", "解密 API key 失败（Keystore 密钥可能已丢）: "
                    + e.getClass().getSimpleName());
            return "";
        }
    }

    public String getApiKey() {
        String stored = prefs.getString("api_key_enc", null);
        if (stored == null) {
            // 兼容旧版明文迁移：加密失败时保留旧值，不能一边报错一边把用户的 key 清掉。
            String legacy = prefs.getString("api_key", "");
            if (!legacy.isEmpty()) {
                String encrypted = encryptKey(legacy);
                if (encrypted != null) {
                    prefs.edit().putString("api_key_enc", encrypted).remove("api_key").apply();
                }
            }
            return legacy;
        }
        String dec = decryptKey(stored);
        // PLAIN/CBC 是历史格式：能读到就立即尝试迁到 GCM；迁移失败仍可本次使用，
        // 但不再把任何新明文/旧 CBC 写回去。
        if (!dec.isEmpty() && !stored.startsWith("GCM:")) {
            String encrypted = encryptKey(dec);
            if (encrypted != null) {
                prefs.edit().putString("api_key_enc", encrypted).remove("api_key").apply();
            }
        }
        return dec;
    }

    /** @return 是否已安全落盘；Keystore 不可用时拒绝保存，绝不退化为明文。 */
    public boolean setApiKey(String v) {
        if (v == null || v.isEmpty()) {
            prefs.edit().putString("api_key_enc", "").remove("api_key").apply();
            return true;
        }
        String encrypted = encryptKey(v);
        if (encrypted == null) return false;
        prefs.edit().putString("api_key_enc", encrypted).remove("api_key").apply();
        return true;
    }

    /** 给备份用的认证加密；失败返回 null，调用方必须明确跳过而不是写明文。 */
    public String encryptKeyForBackup(String plain) {
        return plain == null || plain.isEmpty() ? "" : encryptKey(plain);
    }

    /** 备份里那份 API key 的还原。返回明文；不是密文格式就原样返回（兼容老备份里
     *  存的是明文的情况）；是密文但解不开则返回 null。
     *
     *  <p><b>为什么必须有这个方法</b>：备份时用 encryptKeyForBackup 加了密，而两条恢复
     *  路径（syncApiKeyFromRootfs / WorkspaceFragment.doRestore）过去都是把文件内容
     *  **原样**写进配置 —— 从来没解密。于是恢复之后配置里的 key 是
     *  {@code base64(iv):base64(ct)} 这串密文，启动时 export 给 dsh 的也是它，
     *  对话必然鉴权失败；而配置页看着「key 已填」，用户根本无从判断。
     *
     *  <p>解不开的情形是真实存在的：加密用的是 Android Keystore 里的密钥，**不出设备**。
     *  换手机、或者清除 App 数据导致 Keystore 条目重建之后，老备份里的密文就永久解不开。
     *  那种情况下宁可不回填，让用户重新填一次 —— 写一串解不开的密文进去，
     *  等于把「要重填 key」这件事藏起来，换成一个查不出原因的鉴权失败。 */
    public String decryptKeyFromBackup(String raw) {
        if (raw == null) return null;
        String t = raw.trim();
        if (t.isEmpty()) return null;
        if (t.startsWith("PLAIN:")) return t.substring(6); // 历史失败降级，兼容读取后不再写出
        if (!looksEncryptedKey(t)) return t;      // 更早的备份：明文，直接用
        String dec = decryptKey(t);
        return dec == null || dec.isEmpty() ? null : dec;
    }

    /** 是否是本 App 写出的 GCM 或历史 CBC 密文。 */
    private static boolean looksEncryptedKey(String s) {
        if (s.startsWith("GCM:")) {
            String[] p = s.split(":", -1);
            return p.length == 3 && p[1].matches("[A-Za-z0-9+/=]{8,}")
                    && p[2].matches("[A-Za-z0-9+/=]{16,}");
        }
        int i = s.indexOf(':');
        if (i <= 0 || i == s.length() - 1) return false;
        if (s.indexOf(':', i + 1) >= 0) return false;    // CBC 旧格式只允许一个分隔符
        String iv = s.substring(0, i), ct = s.substring(i + 1);
        return iv.matches("[A-Za-z0-9+/=]{8,}") && ct.matches("[A-Za-z0-9+/=]{8,}");
    }


    public String getPort() {
        // 兜底校验：空/非数字/越界全部回退默认 3080（否则 --port 后是空串导致启动失败）
        String p = prefs.getString("port", "3080");
        if (p == null) return "3080";
        String t = p.trim();
        if (t.isEmpty()) return "3080";
        try {
            int n = Integer.parseInt(t);
            if (n < 1 || n > 65535) return "3080";
            return String.valueOf(n);
        } catch (Exception e) {
            return "3080";
        }
    }

    private int parsePort() {
        try {
            return Integer.parseInt(getPort());
        } catch (Exception e) {
            return 3080;
        }
    }
    /** 保存端口时就校验，避免 UI 显示已保存但启动时静默回退。 */
    public void setPort(String v) {
        try {
            int n = Integer.parseInt(v == null ? "" : v.trim());
            if (n < 1 || n > 65535 || n == LanProxyService.LAN_PORT) return;
            prefs.edit().putString("port", String.valueOf(n)).apply();
        } catch (Exception ignored) {
        }
    }

    public String getModel() { return prefs.getString("model", "deepseek-v4-flash"); }
    public void setModel(String v) {
        if (v == null) return;
        String t = v.trim();
        if (t.isEmpty() || t.length() > 128 || !t.matches("[A-Za-z0-9._:/-]+")) return;
        prefs.edit().putString("model", t).apply();
    }

    private static final java.util.Set<String> PERMISSION_MODES =
            java.util.Collections.unmodifiableSet(new java.util.HashSet<>(java.util.Arrays.asList(
                    "danger-full-access", "workspace-write", "read-only")));

    public String getPermissionMode() {
        String mode = prefs.getString("permission_mode", "danger-full-access");
        return PERMISSION_MODES.contains(mode) ? mode : "danger-full-access";
    }
    public void setPermissionMode(String v) {
        if (v != null && PERMISSION_MODES.contains(v)) {
            prefs.edit().putString("permission_mode", v).apply();
        }
    }

    /** agent 是否被允许使用 root shell（--su 提权）。默认关，配置页手动授权。 */
    public boolean isRootShellAllowed() {
        return prefs.getBoolean("allow_root_shell", false);
    }
    public void setRootShellAllowed(boolean v) { prefs.edit().putBoolean("allow_root_shell", v).apply(); }

    public String getWorkdir() {
        String value = prefs.getString("workdir", "deepseek-harness");
        if (!isSafeWorkdir(value)) {
            // 兼容旧版本已经写入的损坏值：回退并持久化，不能继续拼进 shell。
            prefs.edit().putString("workdir", "deepseek-harness").apply();
            value = "deepseek-harness";
        }
        // ===== 工作区名自适应 =====
        // 只处理一种漂移：配置指向的目录**根本不存在**（被清理、被重置、换了环境）。
        // 这时扫描 /root 认出唯一含源码入口的目录并回写，让启动/看门狗/备份/守卫补丁
        // 全部跟随真实目录。
        //
        // 判据**不能**是「有没有源码入口（apps/cli/lib/bin.js）」—— 用户新建的工作区
        // 就是一个空目录，那样会被当成漂移、立刻被扫描结果覆盖，表现出来就是
        // 「选了新工作区，一松手就跳回旧的」，新建也一样（新目录永远没有源码入口）。
        // 有人就这样完全改不动工作区。目录在就尊重用户的选择。
        if (!workdirDirExists(value)) {
            String detected = scanWorkdirSource();
            if (detected != null) {
                prefs.edit().putString("workdir", detected).apply();
                return detected;
            }
        }
        return value;
    }

    /** 工作目录本身是否存在（不看里面有没有 dsh 源码）。 */
    private boolean workdirDirExists(String wd) {
        try {
            return new java.io.File(proot.getRootfsDir(), "root/" + wd).isDirectory();
        } catch (Exception e) {
            return false;
        }
    }

    /** 扫描 rootfs /root 下含 harness 源码入口（apps/cli/lib/bin.js / lib/bin.js）的目录名；无则 null。 */
    private String scanWorkdirSource() {
        try {
            java.io.File root = new java.io.File(proot.getRootfsDir(), "root");
            java.io.File[] dirs = root.isDirectory() ? root.listFiles(java.io.File::isDirectory) : null;
            if (dirs != null) for (java.io.File d : dirs) {
                if (new java.io.File(d, "apps/cli/lib/bin.js").isFile()
                        || new java.io.File(d, "lib/bin.js").isFile()) {
                    return d.getName();
                }
            }
        } catch (Exception ignored) {
        }
        return null;
    }

    /** 设置工作目录：只允许安全字符（字母数字下划线连字符），防 shell 注入。 */
    public void setWorkdir(String v) {
        if (v == null) return;
        String t = v.trim();
        if (!isSafeWorkdir(t)) return; // 非法：拒绝（保持原值）
        prefs.edit().putString("workdir", t).apply();
        // 用户点「应用」就是明确要用这个工作区，目录不存在就建出来。
        // 不建的话 getWorkdir() 会认为「配置指向一个不存在的目录」= 漂移，
        // 立刻扫描回退到旧目录 —— 用户看到的就是刚设的值又跳回去了。
        try {
            java.io.File d = new java.io.File(proot.getRootfsDir(), "root/" + t);
            if (!d.isDirectory()) {
                //noinspection ResultOfMethodCallIgnored
                d.mkdirs();
            }
        } catch (Throwable e) {
            android.util.Log.w("DSHA", "新工作区目录创建失败（仍已保存配置）: " + e);
        }
    }

    private static boolean isSafeWorkdir(String value) {
        return value != null && value.matches("[A-Za-z0-9_-]{1,64}")
                && !".".equals(value) && !"..".equals(value);
    }

    /** 供局域网桥使用的规范化端口。 */
    public int getPortInt() {
        return parsePort();
    }

    /** 局域网模式是否开启（App 设置项） */
    public boolean isLanMode() {
        return prefs.getBoolean("lan_mode", false);
    }

    /** rootfs 绝对路径（供桥等写日志） */
    public String getRootfsDirPath() {
        return proot.getRootfsDir().getAbsolutePath();
    }

    /** 实际工作目录自愈：直接委托 getWorkdir()（其内部已含“配置缺失→扫描 /root
     *  认源码目录并回写”的自适应逻辑）。 */
    public String detectWorkdir() {
        return getWorkdir();
    }

    public String effectiveApiKey() {
        return getApiKey();
    }

    /** 写入 .env 时清理非法字符（换行/控制符会破坏 env 文件解析） */
    private static String cleanEnvValue(String s) {
        if (s == null) return "";
        return s.replaceAll("[\\r\\n\\u0000]", "").trim();
    }

    public ProotBootstrap getProot() { return proot; }

    /** 是否已安装 deepseek-harness（跟随自定义工作区路径；RC6 模式检查 dsh 命令） */
    public boolean isHarnessInstalled() {
        if (proot.isHarnessInstalled(getWorkdir())) return true;
        try {
            String r = proot.execAndRead("command -v dsh 2>/dev/null || echo MISSING");
            return r != null && !r.startsWith("ERROR") && !r.contains("MISSING") && !r.trim().isEmpty();
        } catch (Exception e) {
            return false;
        }
    }

    /** Web UI 进程是否在运行 */
    public boolean isWebRunning() {
        return webProcess != null && webProcess.isAlive();
    }


    /** 重启 Web UI：原子完成 深停→等端口关透→拉起（复用 stopWebAndWait + startWeb）。
     *  webRestartLock 防重复点击；与 stop 排队语义确定。 */
    public void restartWeb() {
        if (!tryAcquireWebRestartLock()) return; // 正在重启，忽略重复
        IO.execute(() -> {
            try {
                setProgress("正在重启 Web UI（先停止）", 0);
                stopWebAndWait(); // 深停：destroy + pkill 看门狗/web + 等端口关透 + 宽杀兜底
                setProgress("正在重启 Web UI（再启动）", 0);
                startWeb();
            } catch (Throwable e) {
                setState("", 0, "", errMsg("重启出错：", e), false);
            } finally {
                releaseWebRestartLock();
            }
        });
    }

    /** 用户手动停止后，keepAlive 是否应暂停自动拉起（直到再次 startWeb） */
    public boolean isKeepAlivePaused() {
        return prefs.getBoolean("keepalive_paused", false);
    }

    /**
     * 「现在该不该<b>自动</b>把 Web 拉起来」—— 所有自动拉起路径的唯一判据。
     *
     * <p>用户主动点「启动」不走这里：那是明确意图，直接 {@link #startWeb()}。
     *
     * <p><b>为什么要收口</b>：这套判据原先散在五个地方，而且各查各的 —— 保活循环查
     * 「手动停止 + 会话自愈」，预启动查「手动停止 + 90 秒冷却」，看门狗安装和「服务被
     * START_STICKY 重建」这两处压根没查。于是用户按下停止之后，看门狗 1.5 秒后被预热线程
     * 写回、失联 3 次再把 WebUI 拽回来，表现出来就是「停止根本没用、后台不停拉活」。
     *
     * <p>收口之后，新增拉起路径时问题从「记不记得查」变成「调不调这个方法」；日志里带上
     * caller，真机上一眼就能看出是谁拉起的 —— 以前这个信息完全缺失，只能靠猜。
     *
     * @param caller 调用方标识，只用于日志
     */
    public boolean shouldAutoStartWeb(String caller) {
        if (isKeepAlivePaused()) {
            android.util.Log.i("DSHA", "[自动拉起/" + caller + "] 跳过：用户手动停止过");
            return false;
        }
        if (isHealingSession()) {
            android.util.Log.i("DSHA", "[自动拉起/" + caller + "] 跳过：会话自愈进行中（防边修边写）");
            return false;
        }
        long lastStop = prefs.getLong("last_web_stop", 0);
        long since = System.currentTimeMillis() - lastStop;
        if (lastStop > 0 && since < PREWARM_STOP_GUARD_MS) {
            android.util.Log.i("DSHA", "[自动拉起/" + caller + "] 跳过："
                    + (since / 1000) + " 秒前刚手动停过（守护期 "
                    + (PREWARM_STOP_GUARD_MS / 1000) + " 秒）");
            return false;
        }
        return true;
    }

    /** 预启动阈值：距上次手动停止小于该值则尊重用户、不自动拉起（ms） */
    private static final long PREWARM_STOP_GUARD_MS = 90_000;

    /**
     * 自动后台预启动（进入启动页/App 前台时调用）：
     * 环境就绪 && web 未运行 && 用户近期未手动停止 → 后台静默 startWeb()，
     * 让用户点「启动」时基本秒开。幂等：web 已在跑/启动中自动跳过。
     */
    /** 启动 Web 前把 profile 的 bundles 校准到「一定能启动」的状态。
     *
     *  为什么必须自动：发行版里绝大多数用户不会去看自检，更不会到终端敲命令。
     *  dsh 的 resolveBundleDir 只认 profile 的 node_modules 或 dsh 安装树 ——
     *  bundles 里只要有一个名字解析不到，启动直接抛
     *  「cannot resolve profile bundle」退出，整个 Web 起不来，
     *  而用户看到的只是一个白屏，没有任何线索。
     *
     *  三种处置：
     *    能解析              → 留着
     *    解析不到但实体在     → 建 node_modules 链接（修好，插件照常可用）
     *    解析不到实体也没有   → 从 bundles 与 dependencies 摘掉（降级保命）
     *
     *  宁可少一个插件，也不能让用户对着起不来的 Web 干瞪眼 ——
     *  少插件是功能缺失，起不来是完全不可用。摘掉的项会记进
     *  .dsh/profile-sanitize.log，插件页与自检都能看到。
     */
    private void sanitizeProfileBundles() {
        try {
            java.io.File pf = new java.io.File(proot.getRootfsDir(),
                    "root/.dsh/profiles/web/package.json");
            if (!pf.isFile()) return;
            String txt = new String(java.nio.file.Files.readAllBytes(pf.toPath()),
                    StandardCharsets.UTF_8);
            org.json.JSONObject root;
            try {
                root = new org.json.JSONObject(txt);
            } catch (Throwable bad) {
                // package.json 本身坏了：这里不擅自重建（会丢用户的插件配置），
                // 交给备份恢复或 dsh 自己重建，但要留下痕迹
                android.util.Log.w("DSHA", "profile package.json 无法解析，跳过校准: " + bad);
                return;
            }
            org.json.JSONObject dshObj = root.optJSONObject("dsh");
            org.json.JSONObject profObj = dshObj == null ? null : dshObj.optJSONObject("profile");
            org.json.JSONArray bundles = profObj == null ? null : profObj.optJSONArray("bundles");
            if (bundles == null) return;
            org.json.JSONObject deps = root.optJSONObject("dependencies");

            // 内置插件的实体位置：解析不到时优先尝试建链接救回来
            java.util.Map<String, String> builtinReal = new java.util.LinkedHashMap<>();
            builtinReal.put("dsh-web-mobile", "/root/dsha-web-mobile");
            builtinReal.put("dsh-device-shell-guide", "/root/dsha-device-shell-guide");
            builtinReal.put("dsh-task-notifier", "/root/dsha-task-notifier");
            builtinReal.put("dsh-status-overlay", "/root/dsha-status-overlay");

            String[] globalRoots = {
                    "usr/local/lib/node_modules/@deepseek-ai/dsh/node_modules",
                    "usr/local/lib/node_modules",
            };
            java.io.File nmDir = new java.io.File(proot.getRootfsDir(),
                    "root/.dsh/profiles/web/node_modules");

            // ===== 先救回被摘掉的官方核心 =====
            // web profile 必然包含 @deepseek-ai/dsh-base 与 @deepseek-ai/dsh-web-app
            // （前者是内核，后者提供 Web UI）。bundles 里一个 @deepseek-ai/* 都没有，
            // 只可能是被早前版本的 sanitizeProfileBundles 误摘了 ——
            // 那样 Web 100% 起不来，且清数据/重装/清除环境都救不回来
            // （rootfs 数据在卸载后保留，每次启动又摘一遍）。
            boolean hasOfficial = false;
            for (int i = 0; i < bundles.length(); i++) {
                if (bundles.optString(i, "").startsWith("@deepseek-ai/")) {
                    hasOfficial = true;
                    break;
                }
            }
            java.util.List<String> restored = new java.util.ArrayList<>();
            if (!hasOfficial) {
                // 官方核心放在最前：dsh 按顺序加载，内核要先于依赖它的插件
                org.json.JSONArray rebuilt = new org.json.JSONArray();
                for (String core : new String[]{"@deepseek-ai/dsh-base",
                        "@deepseek-ai/dsh-web-app"}) {
                    rebuilt.put(core);
                    restored.add(core);
                }
                for (int i = 0; i < bundles.length(); i++) {
                    String n = bundles.optString(i, "");
                    if (!n.isEmpty()) rebuilt.put(n);
                }
                bundles = rebuilt;
                profObj.put("bundles", bundles);
                logActivity("profile 缺官方核心 bundle，已补回 dsh-base / dsh-web-app（Web 起不来的直接原因）");
            }

            org.json.JSONArray keep = new org.json.JSONArray();
            java.util.List<String> linked = new java.util.ArrayList<>();
            java.util.List<String> dropped = new java.util.ArrayList<>();
            for (int i = 0; i < bundles.length(); i++) {
                String name = bundles.optString(i, "");
                if (name.isEmpty()) continue;
                if (bundleResolvable(name, nmDir, globalRoots)) {
                    keep.put(name);
                    continue;
                }
                String real = builtinReal.get(name);
                if (real != null && new java.io.File(proot.getRootfsDir(),
                        "root" + real.substring(5)).isDirectory()) {
                    if (linkPlugin(name, real, nmDir)) {
                        keep.put(name);
                        linked.add(name);
                        continue;
                    }
                }
                // 官方核心：绝不摘。摘掉它 Web 必然起不来，与本方法的意图正好相反。
                // 解析不到更可能是我们的判据查漏了地方（源码模式 / .pnpm / 作用域子路径）。
                if (isProtectedBundle(name)) {
                    keep.put(name);
                    continue;
                }
                // 救不回来：摘掉，让 Web 至少能起来
                dropped.add(name);
                if (deps != null) deps.remove(name);
            }
            if (dropped.isEmpty() && linked.isEmpty() && restored.isEmpty()) return;
            profObj.put("bundles", keep);
            java.nio.file.Files.write(pf.toPath(),
                    root.toString(2).getBytes(StandardCharsets.UTF_8));
            String rec = "== " + new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss",
                    java.util.Locale.US).format(new java.util.Date()) + " ==\n"
                    + (restored.isEmpty() ? "" : "补回官方核心: " + restored
                    + "（此前被误摘，Web 无法启动）\n")
                    + (linked.isEmpty() ? "" : "补链接后保留: " + linked + "\n")
                    + (dropped.isEmpty() ? "" : "解析不到已摘除: " + dropped
                    + "（否则 dsh 启动会崩）\n");
            try {
                java.nio.file.Files.write(new java.io.File(proot.getRootfsDir(),
                                "root/.dsh/profile-sanitize.log").toPath(),
                        rec.getBytes(StandardCharsets.UTF_8),
                        java.nio.file.StandardOpenOption.CREATE,
                        java.nio.file.StandardOpenOption.APPEND);
            } catch (Throwable ignored) {
            }
            android.util.Log.w("DSHA", "profile bundles 校准: " + rec.replace("\n", " "));
            if (!dropped.isEmpty()) {
                logActivity("启动前校准：摘掉了解析不到的插件 " + dropped
                        + "（否则 dsh 起不来）；到「插件」页或重启一次会自动补回");
            }
            if (!linked.isEmpty()) {
                logActivity("启动前校准：补好了内置插件 " + linked);
            }
        } catch (Throwable e) {
            android.util.Log.w("DSHA", "profile bundles 校准失败: " + e);
        }
    }

    /** 判据必须与 dsh 的 resolveBundleDir 一致：只看 node_modules 与 dsh 安装树，
     *  不看 dependencies 里的 link: 声明（那是给 pnpm 建链接用的，不是运行时依据）。 */
    private boolean bundleResolvable(String name, java.io.File nmDir, String[] globalRoots) {
        if (new java.io.File(new java.io.File(nmDir, name), "package.json").isFile()) return true;
        for (String g : globalRoots) {
            if (new java.io.File(new java.io.File(proot.getRootfsDir(), g + "/" + name),
                    "package.json").isFile()) return true;
        }
        // ===== 以下三路是补上的（此前全都没查，导致官方 bundle 被误判为不可解析）=====
        // ① 源码模式：dsh 装在工作区里，官方包在 <workdir>/node_modules 下，
        //    压根不在 /usr/local/lib。只查全局的话，源码模式环境里所有官方
        //    bundle 都会被判成「解析不到」。
        try {
            String wd = detectWorkdir();
            if (wd != null && !wd.isEmpty()) {
                if (new java.io.File(new java.io.File(proot.getRootfsDir(),
                        "root/" + wd + "/node_modules/" + name), "package.json").isFile()) {
                    return true;
                }
            }
        } catch (Throwable ignored) {
        }
        // ② @deepseek-ai 作用域包在 dsh 自己的 node_modules 下还有一层
        String shortName = name.substring(name.lastIndexOf('/') + 1);
        String[] scoped = {
                "usr/local/lib/node_modules/@deepseek-ai",
                "usr/local/lib/node_modules/@deepseek-ai/dsh/node_modules/@deepseek-ai",
        };
        for (String g : scoped) {
            if (new java.io.File(new java.io.File(proot.getRootfsDir(), g + "/" + shortName),
                    "package.json").isFile()) return true;
        }
        // ③ pnpm store：实体在 .pnpm/<name>@<ver>/node_modules/<name>，
        //    nmDir 下只是符号链接。assets 里的 fix-stale-bundles.sh 一直查了这一路，
        //    Java 这套却没有 —— 同一个判断两套实现、其中一套更弱，
        //    正是本项目反复栽的那个模式。
        try {
            java.io.File pnpm = new java.io.File(nmDir, ".pnpm");
            String key = name.replace("@", "").replace("/", "+") + "@";
            String[] kids = pnpm.list();
            if (kids != null) {
                for (String k : kids) {
                    if (k.startsWith(key)) return true;
                }
            }
        } catch (Throwable ignored) {
        }
        return false;
    }

    /** 官方核心 bundle 一律不摘。
     *
     *  sanitizeProfileBundles 的设计意图是「宁可少一个插件，也别让 Web 起不来」，
     *  但它对官方 @deepseek-ai/* 没有任何保护 —— 一旦判据出错把
     *  @deepseek-ai/dsh-base 或 dsh-web-app 摘掉，Web 就**必然**起不来，
     *  结果和设计意图完全相反。
     *
     *  用户实测现场（1.1.7）：bundles 从 5 个变成
     *      [ "dsh-client-ui-mobile-adapt", "dsh-task-notifier" ]
     *  官方两个核心全没了，Web 60 秒端口未就绪、proroot 连续失败 3 次回退 proot，
     *  而且清数据、重装、清除环境都救不回来 —— 因为每次启动又摘一遍。
     *
     *  assets 里的 fix-stale-bundles.sh 从一开始就有这个保护（非内置插件
     *  解析不到只警告不删），Java 这套却没有。 */
    private static boolean isProtectedBundle(String name) {
        if (name.startsWith("@deepseek-ai/")) return true;
        // 内置插件同样不该被摘。它们的实体在 /root/dsha-* 而不是 node_modules 的常规
        // 位置，判据一旦出错就会被当成「解析不到」——摘掉 UI 适配插件的后果是手机上
        // 界面突然回到桌面布局，而用户完全不知道发生了什么。
        for (String b : PluginController.BUILTIN_PLUGIN_NAMES) {
            if (b.equals(name)) return true;
        }
        return false;
    }

    /** 三个内置插件是否都已注册（bundles 与 dependencies 双在）。 */
    private boolean allBuiltinRegistered() {
        return guideRegistered("dsh-device-shell-guide")
                && guideRegistered("dsh-web-mobile")
                && guideRegistered("dsh-task-notifier");
    }

    /** 读 rootfs 内某个文件的尾部若干字符（读不到返回 null）。 */
    private String tailOfFile(String relPath, int maxChars) {
        try {
            java.io.File f = new java.io.File(proot.getRootfsDir(), relPath);
            if (!f.isFile()) return null;
            byte[] all = java.nio.file.Files.readAllBytes(f.toPath());
            String t = new String(all, StandardCharsets.UTF_8).trim();
            return t.length() <= maxChars ? t : t.substring(t.length() - maxChars);
        } catch (Throwable e) {
            return null;
        }
    }

    /** 按自检结论触发可以自动完成的修复。只写文件、不碰进程。
     *  返回追加到自检报告末尾的说明（没修任何东西时返回空串）。 */
    private String autoFixFromSelfTest(String out) {
        StringBuilder sb = new StringBuilder();
        try {
            boolean pluginTrouble = out.contains("设备引导插件未注册")
                    || out.contains("解析不到")
                    || out.contains("已摘除")
                    || out.contains("修复没成功");
            if (pluginTrouble) {
                int fixed = repairBuiltinPlugins(true);
                if (fixed > 0) {
                    sb.append("· 已安置并注册 ").append(fixed).append(" 个内置插件\n");
                } else if (allBuiltinRegistered()) {
                    // fixed==0 有两种含义：修失败、以及**本来就好**。
                    // 混在一起就会出现「上面写全部关键项通过、下面写仍未修好」这种
                    // 自相矛盾的报告（用户实际遇到过）。
                    sb.append("· 内置插件本来就是好的，无需修复\n");
                } else {
                    // 把原因直接摆在报告里 —— 让用户去 cat 日志文件不叫「修好」，
                    // 这轮就是因为原因只写进 log，来回试了六版才发现是路径差一。
                    String why = tailOfFile("root/.dsh/repair-builtin.log", 600);
                    sb.append("· 内置插件仍未修好");
                    if (why != null && !why.isEmpty()) {
                        sb.append("，最近一次的原因：\n    ").append(why.replace("\n", "\n    "));
                    } else {
                        sb.append("（日志也是空的，说明压根没走到安置逻辑）");
                    }
                    sb.append('\n');
                }
            }
            if (out.contains("❌ write 补丁") || out.contains("❌ 会话发布补丁")) {
                ensureFsWritePatch();
                sb.append("· 已重打 write / 会话发布补丁\n");
            }
            if (out.contains("❌ 危险命令守卫")) {
                ensureDangerGuard();
                sb.append("· 已补装危险命令守卫\n");
            }
            if (out.contains("l2s 悬空链")) {
                runAssetScript("flatten-l2s.py", "dsha-flatten-l2s.py", 120_000);
                sb.append("· 已清理 l2s 悬空链（备份不会再因它失败）\n");
            }
        } catch (Throwable e) {
            sb.append("· 自动修复中断：").append(describe(e)).append('\n');
        }
        if (sb.length() == 0) return "";
        return "\n\n=== 自检顺手做的修复 ===\n" + sb
                + "这些改动在下次启动 Web 后生效 —— 点启动页的「重启」，"
                + "然后可以再跑一次自检确认。";
    }

    /** 内置插件包名 → assets 目录名。方案 B 直接从 APK 写入，连中间实体都不需要。 */
    private static String builtinAssetDir(String pkgName) {
        if ("dsh-device-shell-guide".equals(pkgName)) return "device-shell-guide";
        if ("dsh-web-mobile".equals(pkgName)) return "dsh-web-mobile";
        if ("dsh-task-notifier".equals(pkgName)) return "task-notifier";
        if ("dsh-status-overlay".equals(pkgName)) return "status-overlay";
        return null;
    }

    /** 把内置插件安置进 node_modules。**四条路依次尝试，任一成功即返回。**
     *
     *  之所以做成多路：前面四个版本每次只赌一种做法，失败了还得等用户回报再换一种，
     *  一来一回好几轮（写注册不建链接 → dsh 崩；createSymbolicLink → 私有目录不支持；
     *  proot ln -sfn → 仍失败；proot cp -a 带嵌套引号 → 转义出错整条失败）。
     *  这些机制的可用性取决于设备文件系统、proot 状态、shell 包裹方式，
     *  在容器里全都测不准 —— 那就别赌，全都试一遍。
     *
     *    A  Java 递归复制      不依赖链接支持、不依赖 proot、无 shell 转义
     *    B  直接从 assets 写   连 /root 下的中间实体都不需要，最彻底
     *    C  proot 内 cp -a     命令极简、无嵌套引号
     *    D  符号链接           省空间，但 Android 私有目录常不支持
     *
     *  四条都失败时把每条的原因拼起来记进日志与 repair-builtin.log，
     *  不再让失败消失在 logcat 里。 */
    private boolean linkPlugin(String name, String real, java.io.File nmDir) {
        java.io.File dst = new java.io.File(nmDir, name);
        java.io.File dstPkg = new java.io.File(dst, "package.json");
        java.io.File src = new java.io.File(proot.getRootfsDir(), "root" + real.substring(5));
        java.io.File srcPkg = new java.io.File(src, "package.json");
        StringBuilder tried = new StringBuilder();

        // 已安置且版本一致 → 直接过
        try {
            if (dstPkg.isFile() && srcPkg.isFile()) {
                String a = readVersionOf(dstPkg), b = readVersionOf(srcPkg);
                // 基准取 assets，不取实体。实体本身也可能是残缺的（它同样是复制出来的），
                // 两边都缺时 countFiles(dst) >= countFiles(src) 照样成立，于是又一次放过。
                // assets 是唯一权威来源，只有它说得清「应该有几个文件」。
                int want = countAssetTree(builtinAssetDir(name));
                if (!a.isEmpty() && a.equals(b) && want > 0 && countFiles(dst) >= want) return true;
            }
        } catch (Throwable ignored) {
        }

        // ---- A：Java 递归复制 ----
        try {
            if (srcPkg.isFile()) {
                purgeForPlace(dst);
                copyForPlace(src, dst);
                // 同样以 assets 的文件数为准：实体残缺的话，从它复制出来的也是残缺的，
                // 这时候必须让 A 路失败、落到下面 B 路直接从 assets 写。
                int wantA = countAssetTree(builtinAssetDir(name));
                if (dstPkg.isFile() && (wantA <= 0 || countFiles(dst) >= wantA)) return true;
                tried.append("A(复制后 ").append(countFiles(dst)).append("/").append(wantA)
                        .append(" 个文件，实体本身可能就是残缺的) ");
            } else {
                tried.append("A(实体缺 package.json) ");
            }
        } catch (Throwable e) {
            tried.append("A(").append(e.getClass().getSimpleName()).append(") ");
        }

        // ---- B：直接从 assets 写入 ----
        try {
            String adir = builtinAssetDir(name);
            if (adir != null) {
                purgeForPlace(dst);
                int n = writeAssetTree(adir, dst);
                int want = countAssetTree(adir);
                // 判据不能只看「写了几个」加 package.json 在不在：少写一个 lib 文件同样能过，
                // 而 dsh 加载时 import 不到就会整棵 plugin tree 崩。要求写满。
                if (n >= want && dstPkg.isFile()) return true;
                tried.append("B(写了").append(n).append("/").append(want).append("个文件) ");
            } else {
                tried.append("B(无 assets 映射) ");
            }
        } catch (Throwable e) {
            tried.append("B(").append(e.getClass().getSimpleName()).append(") ");
        }

        android.util.Log.w("DSHA", "安置 " + name + " 两种方式都失败: " + tried);
        try {
            java.nio.file.Files.write(new java.io.File(proot.getRootfsDir(),
                            "root/.dsh/repair-builtin.log").toPath(),
                    ("安置 " + name + " 全部失败: " + tried + "\n")
                            .getBytes(StandardCharsets.UTF_8),
                    java.nio.file.StandardOpenOption.CREATE,
                    java.nio.file.StandardOpenOption.APPEND);
        } catch (Throwable ignored) {
        }
        return false;
    }

    /** 递归数一个目录下的文件数（不含目录本身）。用来判断安置出来的实体是否写全了 ——
     *  只比 package.json 的 version 会漏掉「文件少了一个」这种残缺。 */
    private int countFiles(java.io.File dir) {
        try {
            if (dir == null || !dir.isDirectory()) return 0;
            java.io.File[] kids = dir.listFiles();
            if (kids == null) return 0;
            int n = 0;
            for (java.io.File f : kids) {
                if (FileCopy.isSymlink(f)) continue;   // 软链不跟进，免得数到别处去
                n += f.isDirectory() ? countFiles(f) : 1;
            }
            return n;
        } catch (Throwable t) {
            return 0;
        }
    }

    /** 把 assets 下某个内置插件的整棵树铺到 rootfs 里的实体目录，写完核对文件数。
     *
     *  <p>取代原先每个 {@code ensureXxx} 里手写一串 {@code writeAssetTo} 的做法 ——
     *  那种写法在插件加文件时必然漏，而且漏了没人知道：dsh-web-mobile 升到 2.3.0 时
     *  多了 {@code lib/compress.js}（index.js 会 import 它）和 LICENSE，那个清单
     *  没跟着改，于是实体永远缺两个文件。dsh 按 dependencies 里的 {@code link:} 走到
     *  实体时就是 ERR_MODULE_NOT_FOUND，整棵 plugin tree 崩掉。
     *
     *  @return 是否写满。没写满时调用方应该丢掉 marker，下次启动重来。 */
    private boolean placeBuiltinEntity(String assetDir, java.io.File dstDir) {
        try {
            dstDir.mkdirs();
            int wrote = writeAssetTree(assetDir, dstDir);
            int want = countAssetTree(assetDir);
            if (want > 0 && wrote < want) {
                android.util.Log.w("DSHA", assetDir + " 实体只写了 " + wrote + "/" + want + " 个文件");
                return false;
            }
            return true;
        } catch (Throwable t) {
            android.util.Log.w("DSHA", assetDir + " 实体写入失败: " + t);
            return false;
        }
    }

    /** assets 下某个目录里应该有多少个文件。与 {@link #writeAssetTree} 的返回值对照。 */
    private int countAssetTree(String assetDir) {
        try {
            String[] kids = appContext.getAssets().list(assetDir);
            if (kids == null || kids.length == 0) return 1;   // 没有子项 = 它自己是个文件
            int n = 0;
            for (String k : kids) {
                n += countAssetTree(assetDir + "/" + k);
            }
            return n;
        } catch (Throwable t) {
            return 0;
        }
    }

    /** 把 assets 下某个目录整棵写进目标目录，返回写出的文件数。 */
    private int writeAssetTree(String assetDir, java.io.File dstRoot) throws java.io.IOException {
        int count = 0;
        String[] kids = appContext.getAssets().list(assetDir);
        if (kids == null || kids.length == 0) return 0;
        for (String k : kids) {
            String child = assetDir + "/" + k;
            String[] sub = appContext.getAssets().list(child);
            if (sub != null && sub.length > 0) {
                count += writeAssetTree(child, new java.io.File(dstRoot, k));
                continue;
            }
            java.io.File out = new java.io.File(dstRoot, k);
            if (out.getParentFile() != null) {
                //noinspection ResultOfMethodCallIgnored
                out.getParentFile().mkdirs();
            }
            try (java.io.InputStream in = appContext.getAssets().open(child);
                 java.io.OutputStream os = new java.io.FileOutputStream(out)) {
                byte[] buf = new byte[8192];
                int r;
                while ((r = in.read(buf)) != -1) os.write(buf, 0, r);
            }
            count++;
        }
        return count;
    }

    private String readVersionOf(java.io.File pkgJson) {
        try {
            String t = new String(java.nio.file.Files.readAllBytes(pkgJson.toPath()),
                    StandardCharsets.UTF_8);
            return new org.json.JSONObject(t).optString("version", "");
        } catch (Throwable e) {
            return "";
        }
    }

    /** 递归删除（安置专用）。符号链接（含悬空）只删链接本身，不跟随 ——
     *  类里已有的 deleteRecursively 会跟随链接，用在这儿会顺着悬空链报错。 */
    private void purgeForPlace(java.io.File f) throws java.io.IOException {
        java.nio.file.Path p = f.toPath();
        if (!java.nio.file.Files.exists(p, java.nio.file.LinkOption.NOFOLLOW_LINKS)) return;
        if (java.nio.file.Files.isSymbolicLink(p) || !f.isDirectory()) {
            java.nio.file.Files.deleteIfExists(p);
            return;
        }
        java.io.File[] kids = f.listFiles();
        if (kids != null) {
            for (java.io.File k : kids) purgeForPlace(k);
        }
        java.nio.file.Files.deleteIfExists(p);
    }

    /** 递归复制（安置专用）：目录建出来，文件逐个 Files.copy 覆盖。 */
    private void copyForPlace(java.io.File src, java.io.File dst) throws java.io.IOException {
        if (src.isDirectory()) {
            //noinspection ResultOfMethodCallIgnored
            dst.mkdirs();
            java.io.File[] kids = src.listFiles();
            if (kids != null) {
                for (java.io.File k : kids) copyForPlace(k, new java.io.File(dst, k.getName()));
            }
        } else {
            if (dst.getParentFile() != null) {
                //noinspection ResultOfMethodCallIgnored
                dst.getParentFile().mkdirs();
            }
            java.nio.file.Files.copy(src.toPath(), dst.toPath(),
                    java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        }
    }
    /** 确保配置自愈脚本已写入 rootfs（启动前把超限 timeoutMs 钳回合法值，防 ValidationError 崩溃 WebUI） */
    /** 每次启动前校验内置 bundle（dsh-web-mobile / device-shell-guide）：
     *  若被 dsh plugin reconcile 清掉/链接丢失，自动补回注册（幂等，秒级）。
     *  防止"插件莫名其妙消失导致没效果"。 */
    /** 包级可见：{@link PluginController} 装完插件后要补齐 bundles 声明。 */
    void ensureBuiltinBundles() {
        try {
            java.io.File pf = new java.io.File(proot.getRootfsDir(), "root/.dsh/profiles/web/package.json");
            if (!pf.isFile()) return;
            String txt = new String(java.nio.file.Files.readAllBytes(pf.toPath()), StandardCharsets.UTF_8);
            org.json.JSONObject root = new org.json.JSONObject(txt);
            if (root.optJSONObject("dsh") == null) return;
            org.json.JSONObject profObj = root.optJSONObject("dsh").optJSONObject("profile");
            if (profObj == null) return;
            org.json.JSONArray bundles = profObj.optJSONArray("bundles");
            if (bundles == null) return;
            String[][] builtins = {
                    {"dsh-web-mobile", "/root/dsha-web-mobile"},
                    {"dsh-device-shell-guide", "/root/dsha-device-shell-guide"},
                    {"dsh-task-notifier", "/root/dsha-task-notifier"},
                    {"dsh-status-overlay", "/root/dsha-status-overlay"},
            };
            boolean changed = false;
            org.json.JSONObject deps = root.optJSONObject("dependencies");
            if (deps == null) {
                deps = new org.json.JSONObject();
                root.put("dependencies", deps);
            }
            for (String[] b : builtins) {
                String name = b[0], real = b[1];
                boolean inBundles = false;
                for (int i = 0; i < bundles.length(); i++) {
                    if (name.equals(bundles.optString(i, "").trim())) { inBundles = true; break; }
                }
                // "/root" 长度是 5。写 4 会拼出 roott/dsha-… 这个不存在的路径，
                // dirOk 恒为 false，整个插件直接跳过 —— 后面建链接、写 bundles、
                // 写 dependencies 一步都不会执行。这道门一直关着，所以此前六种
                // 安置做法（符号链接 / proot ln / proot cp / Java 复制 / assets 直写 /
                // 四路 fallback）全都压根没被走到，每次都报「修回 0 个」。
                boolean dirOk = new java.io.File(proot.getRootfsDir(),
                        "root" + real.substring(5)).isDirectory();
                // 实体目录不见了（被清理/从没装成）→ 先把 assets 里的补出来再谈注册，
                // 否则这里只会一路 return，用户看到的就是「插件没自动安装」
                if (!dirOk && "dsh-device-shell-guide".equals(name)) {
                    ensureDeviceShellGuide();
                    dirOk = new java.io.File(proot.getRootfsDir(),
                            "root" + real.substring(5)).isDirectory();
                }
                // 用户主动禁用（.disabled 存在）→ 跳过补回（尊重用户）。
                // 但要区分两种 .disabled：
                //   目录/非空  = 真的禁用（禁用时把实体 mv 过去了）→ 尊重
                //   空文件     = 异常残留（禁用时实体已丢失，只 touch 了个占位）
                // 空占位不清掉的话，补回逻辑会永远跳过，用户重开 App、重跑步骤⑥都没用 ——
                // 自检还会显示「实体在，但 bundles 与 dependencies 都没有它」，死在这儿。
                java.io.File disabledMark = new java.io.File(proot.getRootfsDir(),
                        "root/.dsh/profiles/web/node_modules/" + name + ".disabled");
                boolean staleMark = disabledMark.isFile() && disabledMark.length() == 0;
                if (staleMark && dirOk) {
                    //noinspection ResultOfMethodCallIgnored
                    disabledMark.delete();
                    android.util.Log.w("DSHA", "清掉 " + name
                            + " 的空禁用标记（实体在，属异常残留）");
                }
                boolean userDisabled = disabledMark.exists() && !staleMark;
                // 顺序至关重要：**先把 node_modules 链接建好，再写注册**。
                // 反过来的话（旧实现），一旦链接没建成，profile 里就留下
                // 「bundles 有名字 + dependencies 有 link:，但 node_modules 没有实体」
                // 的组合 —— dsh 启动时 resolveBundleDir 直接抛：
                //   Error: cannot resolve profile bundle "dsh-client-ui-mobile-adapt"
                // 整个 Web 起不来。那比「插件不生效」严重得多：插件不生效只是少个功能，
                // 这个是把能用的环境搞崩。
                java.io.File nmLink = new java.io.File(proot.getRootfsDir(),
                        "root/.dsh/profiles/web/node_modules/" + name);
                // 安置统一走 linkPlugin（Java 递归复制 → assets 直写两路 fallback）。
                // 这里原来内联了一套 Files.createSymbolicLink：于是仓库里有两套安置
                // 逻辑并存，我这几版改的是 linkPlugin，实际跑的却是这段旧的 ——
                // 这是「改了六版都没效果」的直接原因。
                boolean linkOk = false;
                if (dirOk && !userDisabled) {
                    java.io.File nmDir = new java.io.File(proot.getRootfsDir(),
                            "root/.dsh/profiles/web/node_modules");
                    linkOk = linkPlugin(name, real, nmDir);
                }
                if (dirOk && !userDisabled && linkOk && !inBundles) {
                    bundles.put(name);
                    changed = true;
                    android.util.Log.w("DSHA", "内置插件 " + name + " 被清掉，已自动补回");
                }
                // 关键：dsh 的 reconcile 会把「bundles 里有名字、但 dependencies 没有
                // 对应声明」的项判为无法解析并摘掉。以前这里只补 bundles 和符号链接，
                // 于是形成「补回 → 被摘 → 再补回」的死循环，用户看到的就是插件一直没生效
                // （实测现场：实体在、node_modules 链接在，bundles 与 dependencies 双缺）。
                boolean depOk = deps.optString(name, "").startsWith("link:");
                if (dirOk && !userDisabled && linkOk && !depOk) {
                    deps.put(name, "link:" + real);
                    changed = true;
                    android.util.Log.w("DSHA", "内置插件 " + name + " 缺 dependencies 声明，已补 link:");
                }

            }
            if (changed) {
                java.nio.file.Files.write(pf.toPath(), root.toString(2).getBytes(StandardCharsets.UTF_8));
            }
        } catch (Throwable ignored) {
        }
    }


    /** 清理极简模式自定义预设（dsha-minimal 已废弃，删除残留文件） */

    private void ensureConfigFixAsset() {
        try {
            String js = readAsset("config-fix.js");
            if (js == null || js.isEmpty()) return;
            java.io.File f = new java.io.File(proot.getRootfsDir(), "root/dsh-config-fix.js");
            f.getParentFile().mkdirs();
            java.nio.file.Files.write(f.toPath(), js.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            f.setExecutable(true, false);
        } catch (Exception ignored) {
        }
    }

    /** 确保前端"插件失败降级"热补丁已应用（对编译产物打，幂等，RC6/源码通用）：
     *  坏插件不再卡死整个 WebUI 启动。 */
    private void ensureWebUiDegrade() {
        runAssetScript("webui-degrade-patch.sh", "dsha-degrade.sh", 60_000);
    }

    /**
     * 校验并补装 dsh 全局包缺失的 @deepseek-ai/* 子包依赖。
     * 背景（dsh-issue-report）：npmmirror 镜像元数据缓存不一致导致 rc.8 部分子包
     * （dsh-client-ui-slots / dsh-client-ui-primitives 等）声明了依赖但安装时未解析，
     * 服务端插件 require 时 Cannot find module。幂等：全就绪秒回（只读检查）。
     * 在 IO 线程执行（起 proot 子进程，不能卡主线程）。
     */
    public void maybeHealDshDeps() {
        IO.execute(() -> {
            try {
                if (!proot.isInstalled()) return;
                String script = readAsset("dsh-deps-heal.sh");
                if (script == null || script.isEmpty()) return;
                java.io.File f = new java.io.File(proot.getRootfsDir(), "root/dsha-deps-heal.sh");
                f.getParentFile().mkdirs();
                java.nio.file.Files.write(f.toPath(), script.getBytes(StandardCharsets.UTF_8));
                String r = proot.execAndRead("bash /root/dsha-deps-heal.sh; rm -f /root/dsha-deps-heal.sh");
                if (r != null && (r.contains("HEAL_OK") || r.contains("HEAL_DONE"))) {
                    android.util.Log.i("DSHA", "dsh 子包依赖自愈: " + (r.contains("HEAL_DONE") ? "已补装" : "已就绪"));
                } else if (r != null && r.contains("HEAL_PARTIAL")) {
                    android.util.Log.w("DSHA", "dsh 子包依赖部分缺失（构建期包，可忽略）: " + r.trim());
                }
            } catch (Throwable ignored) {
            }
        });
    }

    /** 确保 WebUI 老浏览器兼容补丁已应用（AbortSignal.any/timeout polyfill，幂等）。
     *  老版本 Android System WebView（< Chrome 116）没有 AbortSignal.any/timeout，
     *  dsh 前端在选择工作区等带取消的 remote 调用上会抛 "AbortSignal.any is not a function"。
     *  RC6 / 源码构建通用；失败不阻塞启动。 */
    /** 后台静默补 write 发布补丁（开 App 即跑，不必等点「启动」——
     *  agent 在已运行的 WebUI 里就可能用 write 工具，等启动就晚了）。 */
    public void maybeFixFsWrite() {
        IO.execute(() -> {
            try {
                if (!proot.isInstalled()) return;
                ensureFsWritePatch();
            } catch (Throwable ignored) {
            }
        });
    }
    /** 立刻跑一次公开数据迁移（用户刚授予「所有文件访问」后调用）。
     *
     *  平时这个脚本只在启动 Web 时跑，但用户授权的时机往往在那之后 ——
     *  不补这一下，就得等下次启动才生效，而用户此刻正期待「数据已经安全了」。 */
    public void migratePublicDataNow() {
        String out = runAssetScript("migrate-public-data.sh", "dsha-migrate-public.sh", 60_000);
        // sessions 的落点由 session-home.sh 单独管（它要留在私有目录），
        // 但公开镜像这时候才第一次可写 —— 顺手建起来，别等下次启动。
        ensureSessionHome();
        syncSessionMirror();
        if (out == null) return;
        if (out.contains("已迁移") || out.contains("接回公开副本")) {
            logActivity("已获授权，会话数据迁到公开目录（卸载重装不再丢）");
        } else if (out.contains("不可写")) {
            logActivity("公开目录仍不可写，数据留在私有目录（卸载会丢）");
        }
    }

    /** 活动日志：凡是「App 自己悄悄做了什么」都往这里写一行，用户随时能看到。
     *
     *  这个项目最反复的一类问题不是功能坏了，而是**用户看不到 App 在做什么**：
     *  插件被自动摘掉、运行时被降级、备份清单没生成、修复失败 ——
     *  每一次都只写进 logcat（用户根本拿不到），界面上只留下一个说不清的结果，
     *  于是只能靠反复装包试错。有这份日志，自检就能直接告诉用户
     *  「刚才发生了什么」，而不是让人猜。
     *
     *  只留最后 200 行，避免无限增长。 */
    public void logActivity(String what) {
        try {
            // 这份日志用户能导出、自检也会读 —— 落盘与 Logcat 之前都先脱敏。
            String safeWhat = SensitiveData.redact(what);
            java.io.File f = rootfsFile("root/.dsh/dsha-activity.log");
            if (f.getParentFile() != null) {
                //noinspection ResultOfMethodCallIgnored
                f.getParentFile().mkdirs();
            }
            String line = new java.text.SimpleDateFormat("MM-dd HH:mm:ss",
                    java.util.Locale.US).format(new java.util.Date()) + "  " + safeWhat + "\n";
            java.nio.file.Files.write(f.toPath(), line.getBytes(StandardCharsets.UTF_8),
                    java.nio.file.StandardOpenOption.CREATE,
                    java.nio.file.StandardOpenOption.APPEND);
            if (f.length() > 60_000) {
                java.util.List<String> all = java.nio.file.Files.readAllLines(f.toPath());
                if (all.size() > 400) {
                    java.nio.file.Files.write(f.toPath(),
                            String.join("\n", all.subList(all.size() - 200, all.size()))
                                    .getBytes(StandardCharsets.UTF_8));
                }
            }
            android.util.Log.i("DSHA", "[活动] " + safeWhat);
        } catch (Throwable ignored) {
        }
    }

    /** 一键自检 & 修补：注入 selftest.py 跑一遍检查（环境/3090 桥/ADB/引导插件/
     *  write 补丁/会话/bundles/备份/守卫/版本标记），返回给用户看的报告。
     *  期望版本由这里传进脚本，避免版本号在两边各写一份。
     *
     *  注意它<b>不是只读的</b>（这行注释以前写着「只读」，与实际不符）：
     *  补插件链接、修 pnpm 空壳、补回被摘掉的官方 bundle、去掉插件的模块级硬依赖
     *  都会真动文件（改前留 .bak，报告里说明改了什么）。按钮文案也已经改成
     *  「自检 &amp; 修补」—— 用户遇到起不来的 Web 才会想到点它。 */
    public String runSelfTest() {
        if (!proot.isInstalled()) {
            return "环境未就绪：请先完成内置环境的解压/安装，再运行自检。";
        }
        try {
            String py = readAsset("selftest.py");
            if (py == null || py.isEmpty()) return "自检脚本缺失（APK 资源异常）";
            java.io.File f = new java.io.File(proot.getRootfsDir(), "root/dsha-selftest.py");
            if (f.getParentFile() != null) f.getParentFile().mkdirs();
            java.nio.file.Files.write(f.toPath(), py.getBytes(StandardCharsets.UTF_8));
            // 空壳修复脚本随自检一起下发：自检里的「插件空壳检查」会调用它
            try {
                String heal = readAsset("heal-pnpm-shells.py");
                if (heal != null && !heal.isEmpty()) {
                    java.nio.file.Files.write(
                            new java.io.File(proot.getRootfsDir(),
                                    "root/dsha-heal-pnpm-shells.py").toPath(),
                            heal.getBytes(StandardCharsets.UTF_8));
                }
            } catch (Throwable ignored) {
            }
            String args = " --runtime " + proot.runtime().id()
                    + " --runtime-pref " + proot.preferredRuntimeId()
                    + " --script-ver " + AdbBridge.scriptVersion()
                    + " --guard-ver " + GUARD_VERSION
                    + " --step6 " + STEP6_VERSION
                    + " --assets " + BUILTIN_ASSET_VERSION
                    + " --guide-ver " + builtinGuideVersion()
                    + " --adb-on " + (prefs.getBoolean(Constants.KEY_ADB_ENABLED, false) ? "1" : "0")
                    + " --battery-opt " + (batteryOptWhitelisted() ? "1" : "0");
            String out = proot.execAndRead(
                    "python3 /root/dsha-selftest.py" + args
                            + " 2>&1; rm -f /root/dsha-selftest.py"
                            + " /root/dsha-heal-pnpm-shells.py", 180_000);
            if (out == null || out.trim().isEmpty()) {
                return "自检没有输出：rootfs 内的 python3 可能不可用（可重跑步骤②安装基础工具）。";
            }
            // 用户点「运行自检」的这一刻，正是他明确想解决问题的时候 —— 顺手把能自动
            // 修的修掉。比挂在启动路径上安全得多（启动那条路已经栽过好几次：自动重启
            // 打断自己、pkill 误杀宿主进程），而且修完可以立刻再跑一次自检验证。
            //
            // 原则：**只动文件，不动进程**。要不要重启交给用户决定，
            // 绝不在用户没预期的时候去停/起 Web。
            return (out.trim() + autoFixFromSelfTest(out)).trim();
        } catch (Throwable e) {
            return "自检失败：" + describe(e);
        }
    }

    /** 插件是否真的注册进 web profile（bundles 里有名字 + node_modules 链接在） */
    private boolean guideRegistered(String name) {
        try {
            java.io.File nmLink = new java.io.File(proot.getRootfsDir(),
                    "root/.dsh/profiles/web/node_modules/" + name);
            if (!nmLink.exists()) return false;
            java.io.File pf = new java.io.File(proot.getRootfsDir(),
                    "root/.dsh/profiles/web/package.json");
            if (!pf.isFile()) return false;
            org.json.JSONObject root = new org.json.JSONObject(
                    new String(java.nio.file.Files.readAllBytes(pf.toPath()), StandardCharsets.UTF_8));
            org.json.JSONObject dsh = root.optJSONObject("dsh");
            org.json.JSONObject prof = dsh == null ? null : dsh.optJSONObject("profile");
            org.json.JSONArray bundles = prof == null ? null : prof.optJSONArray("bundles");
            if (bundles == null) return false;
            boolean inBundles = false;
            for (int i = 0; i < bundles.length(); i++) {
                if (name.equals(bundles.optString(i, "").trim())) {
                    inBundles = true;
                    break;
                }
            }
            // dependencies 声明缺失时，dsh reconcile 会把它从 bundles 里摘掉 ——
            // 所以「注册成功」必须两者都在，只看 bundles 会误判为已就绪
            org.json.JSONObject deps = root.optJSONObject("dependencies");
            boolean inDeps = deps != null && deps.optString(name, "").startsWith("link:");
            return inBundles && inDeps;
        } catch (Throwable e) {
            return false;
        }
    }

    /** 首次启动 Web 之后补内置插件；真补上了就自动重启一次 Web 让 dsh 加载它们。
     *
     *  只可能在「全新安装的第一次启动」触发：那时 profile 刚被 dsh 建出来，
     *  App 启动时的那次注册尝试早已扑空。补完必须重启 —— 插件是 profile 加载时
     *  读进去的，光写进 package.json 当前这个实例看不见。
     *  用 builtinPatchedOnce 保证只重启一次，避免「补→重启→再补」打转。 */
    private volatile boolean builtinPatchedOnce = false;

    private void ensureBuiltinPluginsAfterProfileReady() {
        try {
            java.io.File pkg = new java.io.File(proot.getRootfsDir(),
                    "root/.dsh/profiles/web/package.json");
            if (!pkg.isFile()) return;   // profile 还没生成，等下一次启动
            boolean before = guideRegistered("dsh-device-shell-guide");
            ensureDeviceShellGuide();
            ensureTaskNotifier();
            ensureStatusOverlay();
            ensureBuiltinBundles();
            boolean after = guideRegistered("dsh-device-shell-guide");
            if (!before && after && !builtinPatchedOnce) {
                builtinPatchedOnce = true;
                // 这里**不再自动 restartWeb()**。原来那句是在 waitWebPortUp 成功的回调线程里
                // 调重启：停掉刚起来的 Web 进程、再走一遍完整启动流程，时序上撞在一起，
                // 是用户报的「点重启就闪退」最合理的嫌疑之一。
                // 而且现在已经用不着它了 —— sanitizeProfileBundles() 在 dsh 启动**之前**
                // 就把 bundles 校准好了，插件在第一次启动时就已就位，不需要「起来后再补一次
                // 然后重启」这种绕法。
                android.util.Log.w("DSHA", "启动后补齐了内置插件的注册（下次启动即生效，不自动重启）");
            }
        } catch (Throwable e) {
            android.util.Log.w("DSHA", "启动后补内置插件失败: " + e);
        }
    }

    /** 启动自愈：确保内置插件（设备引导 / 任务通知 / 移动端适配）实体在位且注册有效。
     *  不再只依赖步骤⑥ —— ⑥ 可能跑在 profile 生成之前，也可能被版本标记判定跳过。 */
    /** 能力清单的 JSON。抽出来是为了能和上次写的内容比较。 */
    private String capsJson() {
        return "{"
                + "\"adb\":" + DeviceBridgeService.isAdbEnabled(appContext)
                + ",\"ui\":" + DshaAccessibilityService.enabled(appContext)
                + ",\"location\":" + DeviceSense.locationAllowed(appContext)
                + ",\"sensors\":" + DeviceSense.sensorsAllowed(appContext)
                + ",\"root\":" + isRootShellAllowed()
                + ",\"overlay\":" + prefs.getBoolean("overlay_stream", false)
                + ",\"confirm\":" + prefs.getBoolean("confirm_shell", true)
                + "}";
    }

    /**
     * 把「现在真能用的能力」写成 JSON 给内置插件读。
     *
     * <p>device-shell-guide 原先把位置、传感器、屏幕操作、root 那几段无条件全注入 ——
     * 关着的能力也在教 agent 怎么调。代价有两头：白付一轮上下文，而且 agent 会去试注定回
     * DISABLED / NO_PERMISSION 的接口，然后照提示词里那条「原话告诉用户去哪里开」绕一圈。
     * 能力开关是 App 侧状态，写出来让插件读一眼就知道，不必靠试错。
     *
     * <p>为什么用文件而不是加个 3090 端点：插件的 {@code systemPrompt.section} 是同步调用，
     * 没法 await 一次 HTTP；readFileSync 快、不依赖网络、也不受桥起没起来影响。
     *
     * @return 内容与上次不同就返回 true。调用方据此提示「重启 Web 之后 agent 才知道」——
     *         提示词是插件在 dsh 进程里读的，光改开关不重启，agent 手上还是旧的那份。
     *         第一次写（原先没有这个文件）不算变化，免得初次启动就弹一句没头没尾的提示。
     */
    boolean writeCapsFile() {
        try {
            String neu = capsJson();
            java.io.File f = rootfsFile("root/.dsh/.dsha-caps.json");
            String old = null;
            if (f.isFile()) {
                old = new String(java.nio.file.Files.readAllBytes(f.toPath()),
                        StandardCharsets.UTF_8).trim();
            }
            if (neu.equals(old)) return false;
            if (f.getParentFile() != null) f.getParentFile().mkdirs();
            java.nio.file.Files.write(f.toPath(), neu.getBytes(StandardCharsets.UTF_8));
            return old != null;
        } catch (Throwable t) {
            // 不致命：插件读不到就退回全量提示词，那是旧行为
            android.util.Log.w("DSHA", "写能力清单失败（插件将退回全量提示词）: " + t);
            return false;
        }
    }

    public void ensureBuiltinPluginsReady() {
        migrateMobileNavRename();
        writeCapsFile();
        IO.execute(() -> {
            try {
                if (!proot.isInstalled()) return;
                ensureDeviceShellGuide();
                ensureTaskNotifier();
                ensureStatusOverlay();
                ensureBuiltinBundles();
                // 自愈自指依赖 + 自动注册本地插件，两件事共用一个存档点
                // （都在动 profile；拆成两份的话第二份拍到的是改了一半的状态）。
                // 先修再注册：坏条目会让 pnpm 一扫就 ELOOP，那时候注册什么都白搭。
                String healed = plugins.startupProfileMaintenance();
                if (healed != null && !healed.isEmpty()) logActivity(healed);
                if (!guideRegistered("dsh-device-shell-guide")) {
                    boolean disabled = new java.io.File(proot.getRootfsDir(),
                            "root/.dsh/profiles/web/node_modules/dsh-device-shell-guide.disabled").exists();
                    if (!disabled) {
                        android.util.Log.w("DSHA", "设备引导插件暂未注册：profile 还没生成。"
                                + "Web 首次启动成功后会自动补齐并重启一次（见 "
                                + "ensureBuiltinPluginsAfterProfileReady）");
                    }
                }
            } catch (Throwable e) {
                android.util.Log.w("DSHA", "内置插件自愈失败（不影响启动）: " + e);
            }
        });
    }

    /** 是否已加入电池优化白名单（自检据此判断保活能不能真正生效） */
    private boolean batteryOptWhitelisted() {
        try {
            android.os.PowerManager pm = (android.os.PowerManager)
                    appContext.getSystemService(android.content.Context.POWER_SERVICE);
            return pm != null && pm.isIgnoringBatteryOptimizations(appContext.getPackageName());
        } catch (Throwable e) {
            return false;
        }
    }

    /** assets 里内置引导插件的版本号（自检据此对账 rootfs 内已装的那份） */
    private String builtinGuideVersion() {
        try {
            String json = readAsset("device-shell-guide/package.json");
            if (json == null || json.isEmpty()) return "";
            return new org.json.JSONObject(json).optString("version", "");
        } catch (Throwable e) {
            return "";
        }
    }

    /** 启动前自愈：dsh 的 write 工具在 proot 下新建文件会变悬空链接
     *  （dsh 用 link(临时文件,目标) 发布 + 删临时目录，撞上 proot 的 --link2symlink）。
     *  给 fs-local 打「目标不存在改用 rename 发布」的补丁，幂等，命中已打过时 0.05 秒返回。 */
    public void ensureFsWritePatch() {
        noteFsWritePatchResult(
                runAssetScript("fs-write-patch.sh", "dsha-fs-write-patch.sh", 90_000));
    }

    /** 启动前自愈：老 WebView 兼容补丁（AbortSignal.any/timeout polyfill，幂等） */
    private void ensureWebUiPolyfill() {
        // dsh 的 Web 服务本身没有鉴权（上游只绑 127.0.0.1，而 Android 上任何 App
        // 都能访问回环且不需要权限）→ 给它加 token 校验，否则随便一个应用就能读走
        // 全部会话、建会话让 agent 执行 bash。
        runAssetScript("webserver-auth-patch.sh", "dsha-webserver-auth.sh", 60_000);
        runAssetScript("webui-polyfill.sh", "dsha-webui-polyfill.sh", 60_000);
    }

    /** 确保外部浏览器 /api 403 修复已应用（Chrome 150+ Origin 省略端口，幂等）。
     *  dsh-client-connection 的 isTrustedApiRequest 用 new URL(origin).host === hostUrl.host
     *  比较 Origin 与 Host；Chrome 150+ 对 loopback 同源请求发送的 Origin 省略端口，
     *  导致 127.0.0.1:3080 页面所有 /api 请求被 403 拒绝，表现为外部浏览器无法连接网络。
     *  修复为只比较 hostname；失败不阻塞启动。 */
    private void ensureWebUiOriginPatch() {
        runAssetScript("webui-origin-port-patch.sh", "dsha-origin-port-patch.sh", 60_000);
    }

    public void maybePrewarmWeb() {
        if (isHealingSession()) return; // 自愈进行中不预启动（防写会话文件）
        try {
            ensureWebUiDegrade(); // 每次启动前置自愈（幂等秒回，防插件失败卡启动）
        } catch (Throwable ignored) {
        }
        try {
            if (!proot.isInstalled() || !isHarnessInstalled()) return; // 环境/harness 未装
            if (webProcess != null && webProcess.isAlive()) return;    // 已在运行
            // 手动停止 / 会话自愈 / 90 秒冷却，统一走 shouldAutoStartWeb —— 这几个判据
            // 以前在这里各写一遍，别处又漏写，正是「停止按不动」的来源
            if (!shouldAutoStartWeb("预启动")) return;
            android.util.Log.i("DSHA", "[预启动] 后台预热 Web UI…");
            startWeb();
        } catch (Throwable ignored) {
        }
    }

    // ================= 分步安装 =================

    /** 一键安装：按顺序补装尚未完成的步骤 */
    public void install() {
        if (!tryBeginBusy()) return;
        invalidateStepCache(); // 重新判定安装状态
        IO.execute(() -> {
            try {
                for (int s = STEP_ROOTFS; s <= STEP_GUARD; s++) {
                    if (!isStepDone(s)) runInstallStep(s);
                }
                setState("", 100, "全部安装完成，可到「启动」页启动 Web UI", "", false);
            } catch (Throwable e) {
                setState("", 0, "", errMsg("安装出错：", e), false);
            }
        });
    }

    /** 单独执行一个步骤（已完成则视为重装/更新） */
    public void installStep(int step) {
        if (!tryBeginBusy()) return;
        invalidateStepCache(); // 重新判定安装状态
        IO.execute(() -> {
            try {
                runInstallStep(step);
                setState("", 100, "「" + stepName(step) + "」完成", "", false);
            } catch (Throwable e) {
                setState("", 0, "", errMsg("安装出错：", e), false);
            }
        });
    }

    /** 步骤是否已完成（UI 打勾用）。内部全部走缓存：步骤一旦装完在缓存有效期内不变 */
    public boolean isStepDone(int step) {
        return stepDoneSnapshot()[step];
    }

    /**
     * 批量查询 4 个步骤是否完成（下标 1~4 对应 STEP_*；0 恒 false）。
     * 结果带缓存：busy 期间避免重复起 proot 检查（起一次 rootfs 子进程很慢）；
     * 缓存 5 秒自然过期，或安装结束（busy=false）被 setState 主动失效。
     */
    public boolean[] stepDoneSnapshot() {
        return stepDoneSnapshot(true);
    }

    /** 只读当前步骤缓存（不触发重算，主线程安全零耗时）；未初始化时返回全 false */
    public boolean[] peekStepCache() {
        synchronized (stepCache) {
            return stepCache.clone();
        }
    }

    /** 只读当前"可更新"缓存（装了旧版但未达标：⑤ dsh 旧版 / ⑥ 守卫版本旧）。
     *  与 peekStepCache 同生命周期（同一次 proot 查询算出）。 */
    public boolean[] peekUpdatableCache() {
        synchronized (stepCache) {
            return updatableCache.clone();
        }
    }

    /**
     * 批量查询 4 个步骤是否完成（下标 1~4 对应 STEP_*；0 恒 false）。
     * 结果带缓存：busy 期间避免重复起 proot 检查（起一次 rootfs 子进程很慢）；
     * 缓存 5 秒自然过期，或安装结束（busy=false）被 setState 主动失效。
     * @param allowCompute 是否允许缓存过期时重算（false 时只返回缓存，不重算）
     */
    private boolean[] stepDoneSnapshot(boolean allowCompute) {
        long ts = stepCacheTs;
        if (ts >= 0 && System.currentTimeMillis() - ts < 5000) {
            return stepCache.clone(); // 缓存内，直接返回副本
        }
        if (!allowCompute) {
            synchronized (stepCache) { // 只读：短锁，零耗时
                return stepCache.clone();
            }
        }
        synchronized (stepCache) {
            // 双重检查（短锁）
            long ts2 = stepCacheTs;
            if (ts2 >= 0 && System.currentTimeMillis() - ts2 < 5000) {
                return stepCache.clone();
            }
        }
        // 重算：不持锁！proot 子进程很慢（1~3 秒），持锁会把主线程 peek 一起卡死
        boolean r1 = proot.isInstalled();
        // 优化：②④⑤⑥ 四项 rootfs 检查合并为【单次 proot 进程】执行，
        // 原来各起一个子进程（串行 4~12s），现在 1~3s 搞定。
        // U=EF 附加"可更新"检测：E=装了旧版 dsh（rc<8），F=守卫版本旧（.version≠当前）。
        String merged = proot.execAndRead(
                "A=$(command -v curl >/dev/null 2>&1 && command -v git >/dev/null 2>&1 " +
                "&& command -v python3 >/dev/null 2>&1 && command -v make >/dev/null 2>&1 " +
                "&& command -v gcc >/dev/null 2>&1 && command -v xz >/dev/null 2>&1 && echo 1 || echo 0); " +
                "B=$(command -v pnpm >/dev/null 2>&1 && command -v node-gyp >/dev/null 2>&1 && echo 1 || echo 0); " +
                "C=$(command -v dsh >/dev/null 2>&1 && dsh --version 2>/dev/null | head -1 || echo NONE); " +
                "D=$(test -f /root/dsh-guard.sh && test -d /root/dsh-bin && test -f /root/dsh-bin/.version && echo 1 || echo 0); " +
                "E=$(command -v dsh >/dev/null 2>&1 && dsh --version 2>/dev/null | head -1 || echo NONE); " +
                "F=$(test -f /root/dsh-bin/.version && V2=$(cat /root/dsh-bin/.version 2>/dev/null) " +
                "&& [ -n \"$V2\" ] && [ \"$V2\" != \"" + GUARD_VERSION + "\" ] && echo 1 || echo 0); " +
                // C/E 是完整版本号（如 0.1.1-rc.2），不能拼进 R/U（会破坏位解析）！
                // 转为 0/1 位：C_OK=dsh 存在（具体版本判定在 Java 侧），
                // R 用 A/B/C_OK/D，U 用 E_OK/F
                "C_OK=$(command -v dsh >/dev/null 2>&1 && echo 1 || echo 0); " +
                "E_OK=$(command -v dsh >/dev/null 2>&1 && echo 1 || echo 0); " +
                // 用 | 分隔而不是空格：万一将来 dsh --version 输出成 "dsh 0.1.1-rc.2"，
                // 按空格取第一段会得到 "dsh"，版本评分归零，步骤⑤永远显示未安装
                "echo \"V=$C|$E\"; " +
                "echo R=$A$B$C_OK$D U=$E_OK$F");
        // 解析 R=ABCD：不用 matches() 正则（全匹配会被 echo 末尾换行坑到，之前
        // 因此②④⑤⑥全显示未安装）——直接用 indexOf + substring 取 4 位
        boolean[] bits = new boolean[4];
        int ri = merged == null ? -1 : merged.indexOf("R=");
        if (ri >= 0 && ri + 6 <= merged.length()) {
            String b = merged.substring(ri + 2, ri + 6);
            for (int i = 0; i < 4; i++) bits[i] = b.charAt(i) == '1';
        }
        // 解析 U=EF（可更新标记）
        boolean[] upd = new boolean[2];
        int ui = merged == null ? -1 : merged.indexOf("U=");
        if (ui >= 0 && ui + 4 <= merged.length()) {
            String b = merged.substring(ui + 2, ui + 4);
            upd[0] = b.charAt(0) == '1';
            upd[1] = b.charAt(1) == '1';
        }
        // 版本号从 V= 行提取（C/E 是完整版本如 0.1.1-rc.2，命令里单独 echo V=$C|$E；
        // 之前 C 直接拼进 R 导致 R=110.1.1-rc.21 位解析错乱 → 步骤⑤永远未安装）
        String dshVer = "";
        int vi = merged == null ? -1 : merged.indexOf("V=");
        if (vi >= 0) {
            String vv = merged.substring(vi + 2);
            int amp = vv.indexOf('|');
            if (amp >= 0) vv = vv.substring(0, amp); // 取 $C（| 分隔，见上面 echo）
            int nl2 = vv.indexOf('\n');
            if (nl2 >= 0) vv = vv.substring(0, nl2);
            dshVer = vv.trim();
        }
        boolean dshReady = dshVersionScore(dshVer) >= dshVersionScore("0.1.0-rc.8");
        boolean dshOld = !dshVer.isEmpty() && !"NONE".equals(dshVer)
                && dshVersionScore(dshVer) < dshVersionScore("0.1.0-rc.8");
        boolean r2 = bits[0];
        boolean r4 = bits[1];
        // r5 由 dshReady 决定（不再用 bits[2]——那是 C_OK=dsh 存在位）
        boolean r5;
        boolean r6 = bits[3];
        r5 = dshReady; // ⑤ dsh 已就绪（完整版本判定）
        updatableCache[STEP_HARNESS] = dshOld; // ⑤ 装了旧版 → 可更新
        boolean r3 = new File(proot.getRootfsDir(), "usr/local/bin/node").exists()
                && new File(proot.getRootfsDir(), "usr/local/bin/npm").exists();
        synchronized (stepCache) {
            stepCache[STEP_ROOTFS] = r1;
            stepCache[STEP_TOOLS] = r2;
            stepCache[STEP_NODE] = r3;
            stepCache[STEP_PNPM] = r4;
            stepCache[STEP_HARNESS] = r5;
            stepCache[STEP_GUARD] = r6;
            stepCache[0] = false;
            // 可更新标记：⑥ 守卫版本旧（⑤ dsh 旧版已在上面处理）
            updatableCache[STEP_GUARD] = upd[1];
            stepCacheTs = System.currentTimeMillis();
            return stepCache.clone();
        }
    }


    /** 检查 node-pty 编译产物（pty.node）是否就绪（RC6 模式查 npm 包，源码模式查项目目录） */
    private boolean hasPtyNode() {
        try {
            if (useRc6()) {
                String r = proot.execAndRead(
                        "find /usr/local/lib/node_modules -maxdepth 8 -path '*/node-pty/build/Release/pty.node' 2>/dev/null | head -1; " +
                        "find /usr/local/lib/node_modules -maxdepth 8 -path '*/node-pty/prebuilds/linux-arm64/pty.node' 2>/dev/null | head -1");
                // execAndRead 出错返回 "ERROR: ..." 前缀，须排除（不能把执行失败当成有 pty.node）
                return r != null && !r.startsWith("ERROR") && !r.trim().isEmpty();
            }
            File wdDir = new File(proot.getRootfsDir(), "root/" + getWorkdir());
            File pnpmDir = new File(wdDir, "node_modules/.pnpm");
            if (!pnpmDir.isDirectory()) return false;
            File[] ptyDirs = pnpmDir.listFiles((d, n) -> n.startsWith("node-pty@"));
            if (ptyDirs == null) return false;
            for (File d : ptyDirs) {
                File base = new File(d, "node_modules/node-pty");
                if (new File(base, "build/Release/pty.node").isFile()) return true;
                if (new File(base, "prebuilds/linux-arm64/pty.node").isFile()) return true;
            }
        } catch (Throwable ignored) {
        }
        return false;
    }

    /** 检查文件是否为有效的 xz 压缩包（魔数 FD 37 7A 58 5A） */
    public boolean validXz(File f) {
        try (java.io.FileInputStream in = new java.io.FileInputStream(f)) {
            return in.read() == 0xfd && in.read() == 0x37 && in.read() == 0x7a
                    && in.read() == 0x58 && in.read() == 0x5a;
        } catch (Exception e) {
            return false;
        }
    }


    /** rootfs 内基础工具是否齐备 */
    private boolean toolsInstalled() {
        try {
            String out = proot.execAndRead(
                    "command -v curl >/dev/null && command -v git >/dev/null && " +
                    "command -v python3 >/dev/null && command -v make >/dev/null && " +
                    "command -v gcc >/dev/null && command -v xz >/dev/null && echo TOOLS_OK || echo TOOLS_MISSING");
            return out != null && out.contains("TOOLS_OK");
        } catch (Throwable e) {
            return false;
        }
    }

    private void runInstallStep(int step) throws Exception {
        currentStep = step;
        try {
            switch (step) {
                case STEP_ROOTFS: installRootfs(); break;
                case STEP_TOOLS: installTools(); break;
                case STEP_NODE: installNode(); break;
                case STEP_PNPM: installPnpmExtras(); break;
                case STEP_HARNESS: installHarness(); break;
                case STEP_GUARD: installGuard(); break;
                default: throw new Exception("未知步骤：" + step);
            }
        } finally {
            currentStep = 0;
        }
    }

    private void requireRootfs() throws Exception {
        if (!proot.isInstalled()) {
            throw new Exception("前置步骤未完成，请先执行 ① Linux 环境（rootfs）");
        }
    }

    private void requireTools() throws Exception {
        if (!toolsInstalled()) {
            throw new Exception("前置步骤未完成，请先执行 ② 基础工具（apt）");
        }
    }

    /** ① rootfs：测速下载 → 解压 → 冒烟测试（全局进度 0~59） */
    /** ④ Node 附加工具（pnpm + node-gyp）安装：独立可重跑步骤 */
    private void installPnpmExtras() throws Exception {
        requireRootfs();
        requireTools();
        setProgress("安装 Node 附加工具（pnpm / node-gyp）", 90);
        runStep("安装 pnpm", 91,
                "(pnpm -v >/dev/null 2>&1 && echo 'pnpm 已就绪，跳过安装') || " +
                "npm install -g pnpm@11.7.0 --registry=https://registry.npmmirror.com 2>&1 | tail -3");
        runStep("安装 node-gyp（node-pty 编译必需）", 95,
                "(node-gyp --version >/dev/null 2>&1 && echo 'node-gyp 已就绪') || " +
                "npm install -g node-gyp --registry=https://registry.npmmirror.com 2>&1 | tail -3");
        setProgress("Node 附加工具就绪", 100);
    }


    /** ⑥ 安全与补丁：守卫包装器 + bash 守卫补丁 + 运行环境补丁 + 看门狗文件（全幂等） */
    private void installGuard() throws Exception {
        requireRootfs();
        setProgress("安装安全守卫与补丁", 91);
        // 内置插件资产版本自愈：资产变更时删 marker 强制重注入（老用户拿到新 UI/引导）。
        // 注意：删 marker 与写版本分离（版本在末尾 runStep 写）——中途失败则版本未写，
        // 下次启动版本不一致仍会重跑⑥，自愈闭环不中断。
        refreshBuiltinAssetMarkers();
        ensureDangerGuard();   // PATH 包装器（rm/adb 等 15 命令）
        // 守卫开关标记同步：confirm_shell=true → 写标记（adb-shell 设备命令弹确认）
        try {
            boolean confirmOn = prefs.getBoolean("confirm_shell", true);
            proot.execAndRead(confirmOn
                    ? "mkdir -p /root/.dsh && touch /root/.dsh/confirm-shell-enabled && echo ok"
                    : "rm -f /root/.dsh/confirm-shell-enabled && echo ok");
        } catch (Throwable ignored) {
        }
        ensureBashGuardPatch(); // bash 工具 lib 强制加载 dsh-guard
        try {
            proot.ensureRuntimeFiles(); // 运行环境文件
        } catch (Throwable ignored) {
        }
        ensureWatchdogFiles();  // 看门狗 + 重启命令（最新端口）
        try {
            ensureWebUiPolyfill(); // WebView 老版本 AbortSignal.any/timeout polyfill（幂等）
        } catch (Throwable ignored) {
        }
        try {
            ensureWebUiOriginPatch(); // 外部浏览器 /api 403 修复（Chrome 150+ Origin 省略端口）
        } catch (Throwable ignored) {
        }
        try {
            ensureFsWritePatch(); // write 工具悬空链接（重装 dsh 后补丁会丢，⑥ 里补回）
        } catch (Throwable ignored) {
        }
        // ===== 原生内置移动端 UI 适配（免第三方插件） =====
        // 把 dsh-web-mobile 的 client 产物直接注入 web-app 前端，
        // 手机端单栏/抽屉/汉堡/全屏设置开箱即用。幂等，失败不阻塞安装。
        try {
            // 布局迁移：官方版改 lib/ 子目录布局。旧 rootfs（根目录布局）marker 存在
            // 会跳过重注入 → 检测 lib/client.js 不存在（旧布局/缺失）时删 marker 强制重注入；
            // 新布局（含离线预置）存在则保留 marker，维持「解压即用」零注入。
            java.io.File mobileNew = new java.io.File(proot.getRootfsDir(),
                    "root/dsha-web-mobile/lib/client.js");
            if (!mobileNew.isFile()) {
                java.io.File mobileMarker = new java.io.File(proot.getRootfsDir(),
                        "root/dsha-web-mobile-installed");
                if (mobileMarker.exists()) mobileMarker.delete();
                android.util.Log.i("DSHA", "dsh-web-mobile 布局升级：删 marker 强制重注入官方版");
            }
            ensureNativeMobileNav();
        } catch (Throwable ignored) {
        }
        // 设备 Shell 引导插件（rc.8 bundle 模式）：让 agent 系统提示里知道可用 ADB
        try {
            ensureDeviceShellGuide();
        } catch (Throwable ignored) {
        }
        // 任务完成通知插件：turn/end → 3090 桥发 App 通知（替代轮询）
        try {
            ensureTaskNotifier();
            ensureStatusOverlay();
        } catch (Throwable ignored) {
        }
        // 极简模式设备引导已并入 device-shell-guide 插件（home patch 覆盖官方极简 bash 描述）
        // 内置插件快照：只录实体目录（排除符号链接=用户安装插件），安装完成时最干净基线
        // 快照缺失时才生成（后续沿用；想重扫可删 /root/dsha-builtin.txt）
        runStep("生成内置插件快照", 98,
                "if [ ! -f /root/dsha-builtin.txt ]; then " +
                "find /root/.dsh/profiles/web/node_modules/ -maxdepth 1 \\( -type d -o -type f \\) ! -type l 2>/dev/null " +
                "| sed 's|.*/||' | grep -v '^\\.' | grep -v '\\.disabled$' > /root/dsha-builtin.txt; " +
                "echo '内置快照：'$(wc -l < /root/dsha-builtin.txt 2>/dev/null)' 项'; " +
                "else echo '内置插件快照已存在，沿用'; fi");
        // 写入步骤⑥版本标记（启动时对比，不符自动重跑⑥）
        runStep("写入⑥版本标记", 99,
                "printf '%s' '" + STEP6_VERSION + "' > /root/.dsh/step6.version; " +
                "printf '%s' '" + BUILTIN_ASSET_VERSION + "' > /root/.dsh/builtin-assets.version; " +
                "echo '⑥版本: " + STEP6_VERSION + " 资产版本: " + BUILTIN_ASSET_VERSION + "'");
        setProgress("安全守卫与补丁就绪", 100);
    }


    private void installRootfs() throws Exception {
        setProgress("准备 proot 运行时", 2);
        proot.ensureRuntimeFiles();

        File tarball = new File(proot.getRootfsDir().getParentFile(), "rootfs.tar.gz");
        File doneMark = new File(tarball.getAbsolutePath() + ".done");

        // 已有完整下载则跳过；否则断点续传（downloadRootfs 内部 Range 续传）
        boolean haveComplete = doneMark.exists() && tarball.exists()
                && tarball.length() > 15L * 1024 * 1024;
        if (!haveComplete) {
            downloadWithPick(TASK_ROOTFS, ProotBootstrap.ROOTFS_URLS,
                    "下载 Ubuntu rootfs（~30MB）", tarball, 4, 2);
        }

        setProgress("rootfs 下载完成，正在解压（约 5~15 分钟，进度会暂时停住，请勿关闭 App）", 57);
        proot.extractRootfs(tarball);
        proot.setupResolvConf();

        // proot 冒烟测试：确认能进入 rootfs 执行命令
        String smoke = proot.smokeTest();
        if (smoke == null || !smoke.contains("SMOKE_OK")) {
            throw new Exception("proot 进入 rootfs 失败（bash 无法执行）：\n"
                    + (smoke == null ? "" : smoke)
                    + "\n\n[环境诊断]\n" + proot.diagnoseRootfs());
        }

        proot.markInstalled();
        // 解压成功后清理 tarball 与标记，释放空间
        //noinspection ResultOfMethodCallIgnored
        tarball.delete();
        //noinspection ResultOfMethodCallIgnored
        doneMark.delete();
        setProgress("环境安装完成", 59);
    }

    /** ② 基础工具：apt 换国内源 + 安装 curl/git/python3/make/xz（全局进度 60~69） */
    private void installTools() throws Exception {
        requireRootfs();
        // 先把 apt 源换成国内镜像（直连 ports.ubuntu.com 在国内常被重置）
        setProgress("替换 apt 国内源", 60);
        proot.execAndRead(
                "sed -i 's|ports.ubuntu.com|mirrors.tuna.tsinghua.edu.cn|g; " +
                "s|archive.ubuntu.com|mirrors.tuna.tsinghua.edu.cn|g' " +
                "/etc/apt/sources.list /etc/apt/sources.list.d/*.sources 2>/dev/null || true");
        try {
            runStep("更新 apt 源", 62, "apt-get update -y");
            runStep("安装基础工具（curl/git/python3/make/gcc/xz）", 65,
                    "apt-get install -y --no-install-recommends curl git python3 make gcc g++ xz-utils");
        } catch (Throwable e) {
            throw new Exception(e.getMessage() + "\n\n[环境诊断]\n" + proot.diagnoseRootfs());
        }
        // ca-certificates 的 postinst 在 proot 下必失败，装完基础工具后单独处理，
        // 强制移除避免 dpkg broken 状态阻塞后续 apt 操作
        try {
            proot.execAndRead(
                "apt-get install -y --no-install-recommends ca-certificates 2>/dev/null || true; " +
                "dpkg --remove --force-remove-reinstreq ca-certificates 2>/dev/null || true; " +
                "dpkg --configure -a 2>/dev/null || true");
        } catch (Throwable ignored) {
        }
        setProgress("基础工具就绪", 69);
    }

    /** ③ Node.js：测速下载到 rootfs /tmp → 解压（全局进度 70~89） */
    private void installNode() throws Exception {
        requireRootfs();
        requireTools();
        File nodePkg = new File(proot.getRootfsDir(), "tmp/node.tar.xz");
        // 完整性检查：大小 ≥40MB 且 xz 魔数正确（防下载中断的截断文件混过检查导致解压 EOF）
        boolean haveGood = nodePkg.exists() && nodePkg.length() >= 40L * 1024 * 1024
                && validXz(nodePkg);
        if (haveGood) {
            setProgress("Node.js 安装包已存在，跳过下载", 71);
        } else {
            if (nodePkg.exists()) {
                //noinspection ResultOfMethodCallIgnored
                nodePkg.delete(); // 清掉截断/损坏的旧包
            }
            downloadWithPick(TASK_NODE, ProotBootstrap.NODE_URLS, "下载 Node.js", nodePkg, 71, 6);
        }
        // 解压失败自动重下一次（npmmirror）再试一次；仍失败则抛错中断
        // 注意：不使用 `cd /tmp` —— 部分云手机/翻译层（如卓易通）对 chdir(/tmp)
        // 返回 ENOSYS("Function not implemented")，必须全程绝对路径。
        runStep("安装 Node.js", 88,
                "mkdir -p /tmp /usr/local 2>/dev/null; "
                        + "(tar -xJf /tmp/node.tar.xz -C /usr/local --strip-components=1 || "
                        + "(echo '安装包损坏，自动重新下载…'; rm -f /tmp/node.tar.xz; "
                        + "curl -kfsSL --retry 3 https://npmmirror.com/mirrors/node/v24.19.0/node-v24.19.0-linux-arm64.tar.xz -o /tmp/node.tar.xz && "
                        + "tar -xJf /tmp/node.tar.xz -C /usr/local --strip-components=1))");
        setProgress("Node.js 就绪", 89);
    }

    /** ④ deepseek-harness：预构建包 或 直连源码构建（全局进度 90~100） */
    private void installHarness() throws Exception {
        requireRootfs();
        if (isAncoContainer()) {
            // 鸿蒙 6 对卓易通后台杀得极快（社区反馈：切换应用/锁屏即杀）。
            // 这一步要跑十几分钟，用户切出去回来就以为「卡住了」。
            logActivity("卓易通环境：本步耗时较长，请保持 App 在前台且屏幕常亮");
            setProgress("⚠ 卓易通环境：请勿切出 App（会被系统杀掉）", 90);
            try { Thread.sleep(2500); } catch (InterruptedException ignored) { }
        }
        // 前置检查：第五步整个建立在 node/npm 之上。它们不可用时，
        // 原来的表现是一条 300 字符的 npm 命令加一个裸「退出码 127」——
        // 用户完全无法从中知道「其实是步骤③没装成」。
        try {
            String probe = proot.execAndRead(
                    "export PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin:$PATH; "
                    + "printf 'node=%s npm=%s\\n' "
                    + "\"$(command -v node 2>/dev/null || echo MISSING)\" "
                    + "\"$(command -v npm 2>/dev/null || echo MISSING)\"");
            if (probe != null && probe.contains("MISSING")) {
                boolean noNode = probe.contains("node=MISSING");
                throw new Exception("第五步需要 Node 环境，但容器里"
                        + (noNode ? "找不到 node" : "找不到 npm")
                        + "。\n\n"
                        + "这一般说明步骤③（Node 运行时）没有真正装成 —— "
                        + "它可能显示过成功，但产物没落到 /usr/local/bin。\n"
                        + "下一步：回到分步安装页重跑 ③ Node 运行时，"
                        + "看到它输出版本号后再跑本步骤。\n"
                        + "（探测结果：" + probe.trim() + "）");
            }
        } catch (Exception probeFail) {
            if (probeFail.getMessage() != null
                    && probeFail.getMessage().startsWith("第五步需要 Node")) {
                throw probeFail;
            }
            // 探测本身失败（容器不可用等）不阻断，让后面的真实命令去报错
        }
        // 预构建包源已暂停（catbox 匿名站包体被污染/损坏，含 WSL 脚本非官方产物）。
        // 当前唯一可靠路径 = 直连 GitHub 源码构建（多镜像 fallback + 工具链齐全，已验证稳定）。
        installHarnessFromSource();
        // ===== 预构建包线路（暂停，代码保留供恢复源后使用） =====
        /*
        String apiKey = effectiveApiKey();
        String[] urls = ProotBootstrap.HARNESS_URLS;
        setProgress("测速中…（含直连选项）", 91);
        long[] lat = proot.probeAll(urls, 6000);
        setProgress("请选择安装方式（预构建包 / 直连源码）", 91);
        String[] ordered = waitUserPick(TASK_HARNESS, urls, lat);
        if (ordered[0].startsWith("git://")) {
            installHarnessFromSource();
            return;
        }
        // ... 预构建包解压逻辑见历史版本 ...
        */
    }

    // ================= 受限容器环境（卓易通 / anco）=================
    /** 检测是否跑在鸿蒙的安卓兼容容器里（卓易通 anco，底层是华为 iSulad）。
     *
     *  为什么要单独识别：用户报「总卡在安装第五步」。第五步是从源码构建 dsh
     *  （git clone + pnpm install + node-gyp 编译 node-pty），是整条链路里
     *  **最长、最吃内存、fork 最多**的一步。而卓易通环境有三个已知硬约束：
     *
     *  1. 鸿蒙 NEXT 的应用沙箱**默认禁止非系统应用 fork 子进程**，
     *     Termux 在纯血鸿蒙上跑 proot 也是栽在这里；
     *  2. anco 容器自身要 ~8GB 内存，剩给我们编译的余量很小；
     *  3. 鸿蒙 6 对卓易通后台**杀得极快**（社区反馈：切换应用、锁屏即杀）——
     *     一步要跑十几分钟，用户切出去看一眼回来就「卡住」了，
     *     其实是进程已经没了。
     *
     *  判据参考社区做法：读 /proc/self/cgroup 找 isulad / lxc 标识。
     *  结果缓存，避免每步都读文件。 */
    private volatile Integer ancoCache = null;

    public boolean isAncoContainer() {
        Integer c = ancoCache;
        if (c != null) return c == 1;
        boolean hit = false;
        try {
            java.io.File f = new java.io.File("/proc/self/cgroup");
            if (f.canRead()) {
                String txt = new String(java.nio.file.Files.readAllBytes(f.toPath()),
                        java.nio.charset.StandardCharsets.UTF_8).toLowerCase();
                hit = txt.contains("isulad") || txt.contains("/lxc/") || txt.contains("zhuoyi");
            }
            if (!hit) {
                // 兜底判据要收紧。原来只要 /proc/version 里有 ohos/harmony 就算命中 ——
                // 但华为 EMUI / HarmonyOS 4 及更早本身就是安卓系，内核串里同样可能
                // 出现 harmony 字样。那些机器不是卓易通容器，误判会让它们被无谓地
                // 降到串行安装（明显变慢）还弹出莫名其妙的「卓易通环境」提示。
                // 所以要求「内核标识 + 容器特征」同时成立：真正跑在 anco 里时，
                // /proc/1/cmdline 不是 Android 的 init，或存在 iSulad 的运行时目录。
                String v = new String(java.nio.file.Files.readAllBytes(
                        new java.io.File("/proc/version").toPath()),
                        java.nio.charset.StandardCharsets.UTF_8).toLowerCase();
                boolean kernelMark = v.contains("ohos") || v.contains("harmony");
                boolean containerMark = new java.io.File("/dev/isulad").exists()
                        || new java.io.File("/system/etc/anco").exists()
                        || new java.io.File("/vendor/etc/anco").exists();
                hit = kernelMark && containerMark;
            }
        } catch (Throwable ignored) {
        }
        ancoCache = hit ? 1 : 0;
        if (hit) logActivity("检测到鸿蒙安卓容器（卓易通）：已切换为低并发安装模式");
        return hit;
    }

    /** 受限环境下给 pnpm/npm 用的环境变量前缀。
     *
     *  三件事：把子进程并发压到 1（fork 受限）、给 node 设内存上限
     *  （anco 里 OOM 比超时更常见）、关掉 pnpm 的进度动画
     *  （被杀后日志里看不出走到哪，纯文本反而好排查）。
     *  非 anco 环境返回空串，完全不影响原有行为。 */
    private String lowResourceEnv() {
        if (!isAncoContainer()) return "";
        return "export NPM_CONFIG_CHILD_CONCURRENCY=1 "
                + "PNPM_CHILD_CONCURRENCY=1 "
                + "NPM_CONFIG_NETWORK_CONCURRENCY=2 "
                + "NODE_OPTIONS='--max-old-space-size=1024' "
                + "CI=1 TERM=dumb; ";
    }

    /** 是否使用 RC6 版本。已改为“始终最新 RC”（@deepseek-ai/dsh@rc），无开关。 */
    public boolean useRc6() {
        return true;
    }

    /** 字节格式化：134833152 -> "134.8MB"，1.33GB -> "1.33GB" */
    public static String fmtBytes(long bytes) {
        if (bytes <= 0) return "0B";
        if (bytes >= 1024L * 1024 * 1024)
            return String.format(java.util.Locale.US, "%.2fGB", bytes / 1073741824.0);
        if (bytes >= 1024 * 1024)
            return String.format(java.util.Locale.US, "%.1fMB", bytes / 1048576.0);
        if (bytes >= 1024)
            return String.format(java.util.Locale.US, "%.1fKB", bytes / 1024.0);
        return bytes + "B";
    }

    /** 速率文案：3.2MB/s、850KB/s；未知/为零时给 "—"（别让界面显示 0.0MB/s 让人以为卡死） */
    public static String fmtRate(double bytesPerSec) {
        if (bytesPerSec <= 1) return "—";
        return fmtBytes((long) bytesPerSec) + "/s";
    }

    /** 剩余时间文案：45 秒 / 2 分 10 秒 / 1 小时 5 分（负数或算不出给 "—"） */
    public static String fmtEta(long seconds) {
        if (seconds < 0) return "—";
        if (seconds < 60) return seconds + " 秒";
        long m = seconds / 60;
        long s = seconds % 60;
        if (m < 60) return s == 0 ? m + " 分" : m + " 分 " + s + " 秒";
        long h = m / 60;
        m %= 60;
        return m == 0 ? h + " 小时" : h + " 小时 " + m + " 分";
    }

    /** 进度采样器：把「已完成字节」序列换算成平滑速率与剩余时间。
     *  指数平滑（EMA）避免数字每次刷新都乱跳；解压页/下载共用一套算法。 */
    public static final class RateMeter {
        private long lastBytes = -1;
        private long lastAt = 0;
        private double rate = 0; // 字节/秒

        /** 喂入最新的累计字节数，返回当前平滑速率（字节/秒，未知为 0） */
        public synchronized double feed(long done) {
            long now = System.currentTimeMillis();
            if (lastBytes < 0) {
                lastBytes = done;
                lastAt = now;
                return 0;
            }
            long dt = now - lastAt;
            if (dt < 200) return rate; // 采样太密：噪声大，沿用上次
            double inst = (done - lastBytes) * 1000.0 / dt;
            if (inst < 0) inst = 0; // 断点续传等导致回退：忽略
            rate = rate <= 0 ? inst : rate * 0.7 + inst * 0.3;
            lastBytes = done;
            lastAt = now;
            return rate;
        }

        /** 当前平滑速率（字节/秒） */
        public synchronized double rate() {
            return rate;
        }

        /** 剩余秒数（算不出返回 -1） */
        public synchronized long eta(long done, long total) {
            if (total <= 0 || done >= total || rate <= 1) return -1;
            return (long) ((total - done) / rate);
        }
    }

    private void installHarnessRc6() throws Exception {
        requireRootfs();
        requireTools();
        // 先写入依赖自愈脚本（安装末尾的"校验 dsh 子包依赖完整性"步骤要用；
        // 若安装中途失败，启动时的 maybeHealDshDeps 也会重写）
        try {
            String heal = readAsset("dsh-deps-heal.sh");
            if (heal != null && !heal.isEmpty()) {
                java.io.File hf = new java.io.File(proot.getRootfsDir(), "root/dsha-deps-heal.sh");
                hf.getParentFile().mkdirs();
                java.nio.file.Files.write(hf.toPath(), heal.getBytes(StandardCharsets.UTF_8));
            }
        } catch (Throwable ignored) {
        }
        setProgress("安装 deepseek-harness 最新 RC（npm 全局）", 91);
        runStep("RC 安装环境准备", 92,
                // 先写 registry 再追加 allow-scripts：顺序反了会把前者 printf 覆盖掉
                "echo 'registry=https://registry.npmmirror.com' > /root/.npmrc; " +
                "npm config set allow-scripts=@deepseek-ai/dsh-subprocess-local,koffi,node-pty,@google/genai,protobufjs --location=user 2>/dev/null; " +
                "echo '--- /root/.npmrc ---'; cat /root/.npmrc");
        runStep("安装 @deepseek-ai/dsh 最新 RC", 95,
                // 离线包（快照）里已经预装好了 dsh。这一步过去无条件跑 npm install，
                // 于是第一次进安装页就开始重新下载几十 MB 的 dsh 及其依赖 ——
                // 白等、白耗流量，网差的用户还会直接失败，而本地那份明明是好的。
                // 现在先探本地：有可用的就跳过。想升级到更新的 RC 是另一件事，
                // 应该由用户显式发起，而不是装机流程偷偷替换掉自带版本。
                "if [ -f /usr/local/lib/node_modules/@deepseek-ai/dsh/package.json ] && "
                + "command -v dsh >/dev/null 2>&1; then "
                + "echo \"已有可用的 dsh（离线包自带 $(node -p \\\"require('/usr/local/lib/node_modules/@deepseek-ai/dsh/package.json').version\\\" 2>/dev/null || echo 未知)），跳过 npm 安装\"; "
                + "echo '如需升级到最新 RC，可在「设置」页手动更新'; "
                + "else "
                // npm 自己都不在了的情况要单独说清楚。真机上出过：一次全局 npm 安装中途
                // 断了，把 /usr/local/lib/node_modules 连 npm 一起带走，于是这一步只报一个
                // 裸 127（command not found），用户既看不懂也无路可走 —— 而这时候需要的
                // 不是重试网络，是重解压环境。
                + "if ! command -v npm >/dev/null 2>&1 || ! command -v node >/dev/null 2>&1; then "
                + "echo '环境已损坏：npm 或 node 不在 PATH 里 —— 这不是网络问题。'; "
                + "echo '常见原因：上一次全局 npm 安装中途断了，把 /usr/local/lib/node_modules 连 npm 自己一起带走了。'; "
                + "echo '修法：到「设置」页点「重新解压内置环境」，配置、API Key 与对话记录会保留，修完再回来装。'; "
                + "echo '--- /usr/local/bin ---'; ls -la /usr/local/bin 2>&1 | head -15; "
                + "echo '--- /usr/local/lib/node_modules ---'; ls -la /usr/local/lib/node_modules 2>&1 | head -15; "
                + "exit 1; fi; "
                // 优先 @next（官方最新 rc）；npmmirror 镜像同步滞后时回退 pin rc.8，再回退官方源
                + "(npm install -g @deepseek-ai/dsh@next --force --registry=https://registry.npmmirror.com 2>&1 || " +
                "npm install -g @deepseek-ai/dsh@0.1.1-rc.2 --force --registry=https://registry.npmmirror.com 2>&1 || " +
                "npm install -g @deepseek-ai/dsh@rc --force --registry=https://registry.npmmirror.com 2>&1 || " +
                "npm install -g @deepseek-ai/dsh@next --force --registry=https://registry.npmjs.org 2>&1) | tail -25; " +
                "echo \">> npm 退出码: ${PIPESTATUS[0]}\"; " +
                // 强制 RC8/npm 路线：失败直接退出（不 fallback clone——手机 clone GitHub
                // 几乎必失败且会留下空源码目录，用户看到"源码是空的"）
                "if [ \"${PIPESTATUS[0]}\" != 0 ]; then echo 'RC 安装失败：npm 三个源都不通，请检查网络后重试'; exit 1; fi; "
                + "fi");
        // 预下载 Node headers（node-gyp 编译 node-pty 必需；否则 node-gyp 默认访问
        // nodejs.org 下载，国内手机网络不通 → undici 报错 → 退出码 1）
        // 新版 node-pty 自带 prebuilds/linux-arm64/pty.node，有它就不必下 headers、
        // 也不必 node-gyp 编译 —— 那是几十 MB 加慢设备上好几分钟，全是白花的。
        if (hasPtyNode()) {
            setProgress("node-pty 已就绪（自带预编译产物），跳过 headers 与编译", 98);
        } else {
        runStep("准备 Node headers（node-gyp 编译依赖）", 96,
                "NGV=$(node -v | sed 's/^v//'); " +
                "if [ ! -f /root/.cache/node-gyp/$NGV/include/node/node.h ]; then " +
                "mkdir -p /root/.cache/node-gyp/$NGV; cd /root/.cache/node-gyp/$NGV; " +
                "(curl -kfsSL --retry 3 https://npmmirror.com/mirrors/node/v$NGV/node-v$NGV-headers.tar.gz -o headers.tar.gz || " +
                "curl -kfsSL --retry 3 https://mirrors.huaweicloud.com/nodejs/v$NGV/node-v$NGV-headers.tar.gz -o headers.tar.gz || " +
                "curl -kfsSL --retry 3 https://nodejs.org/dist/v$NGV/node-v$NGV-headers.tar.gz -o headers.tar.gz) && " +
                "tar -xzf headers.tar.gz --strip-components=1 && rm -f headers.tar.gz && echo 'Node headers 已准备' || " +
                "{ echo 'Node headers 下载失败（node-gyp 编译将无法进行）'; exit 1; }; " +
                "else echo 'Node headers 已缓存'; fi; " +
                "export npm_config_nodedir=/root/.cache/node-gyp/$NGV; " +
                "export npm_config_disturl=https://npmmirror.com/mirrors/node");
        runStep("编译 node-pty 原生模块", 98,
                "node-gyp --version >/dev/null 2>&1 || npm install -g node-gyp --registry=https://registry.npmmirror.com 2>&1 | tail -2; " +
                "NGV=$(node -v | sed 's/^v//'); " +
                "export npm_config_nodedir=/root/.cache/node-gyp/$NGV; " +
                "export npm_config_disturl=https://npmmirror.com/mirrors/node; " +
                "npty_dir=$(find /usr/local/lib/node_modules -maxdepth 6 -path '*/node-pty' -type d 2>/dev/null | head -1); " +
                "if [ -z \"$npty_dir\" ]; then " +
                "echo '未找到 node-pty（说明 dsh 包没装上）'; " +
                "echo '--- /usr/local/lib/node_modules ---'; ls /usr/local/lib/node_modules 2>&1; " +
                "echo '--- @deepseek-ai 目录 ---'; ls /usr/local/lib/node_modules/@deepseek-ai/ 2>&1; " +
                "echo '--- dsh 命令 ---'; command -v dsh || echo 'dsh 不存在'; " +
                "exit 1; fi; " +
                "if [ ! -f \"$npty_dir/build/Release/pty.node\" ] && [ ! -f \"$npty_dir/prebuilds/linux-arm64/pty.node\" ]; then " +
                "(cd \"$npty_dir\" && node-gyp rebuild > /tmp/rc6-gyp.log 2>&1) || " +
                "{ echo 'node-pty 编译失败：'; tail -10 /tmp/rc6-gyp.log 2>&1; exit 1; }; fi; " +
                "{ [ -f \"$npty_dir/build/Release/pty.node\" ] || [ -f \"$npty_dir/prebuilds/linux-arm64/pty.node\" ]; } >/dev/null 2>&1 && echo 'pty.node 已就绪' && command -v dsh && echo 'RC 安装完成'");
        }
        // 依赖完整性自愈：npmmirror 元数据不一致可能导致 @deepseek-ai/* 子包
        // 声明了但没装上（Cannot find module）——安装时强制校验补装一次
        runStep("校验 dsh 子包依赖完整性", 99,
                "if [ -f /root/dsha-deps-heal.sh ]; then bash /root/dsha-deps-heal.sh; rm -f /root/dsha-deps-heal.sh; " +
                "else echo 'dsha-deps-heal.sh 未就位，跳过'; fi; tail -3 /root/dsh-deps-heal.log 2>/dev/null || true");
        // 立即打老 WebView 兼容补丁：单独重装⑤（dsh 更新）时不会走⑥，这里保证 RC6 路径也生效
        try {
            ensureWebUiPolyfill();
        } catch (Throwable ignored) {
        }
        try {
            ensureWebUiOriginPatch();
        } catch (Throwable ignored) {
        }
        setProgress("RC 安装完成", 100);
    }

    /** 直连 GitHub 源码构建（clone 多通道 fallback + npmmirror 依赖/headers 源） */
    private void installHarnessFromSource() throws Exception {
        if (useRc6()) {
            installHarnessRc6();
            return;
        }
        String wd = getWorkdir();

        // 已装环境不会重跑 setupResolvConf：这里强制重写 DNS（223.5.5.5 等国内源），
        // 否则 git clone / curl 全域名解析失败
        requireRootfs();
        proot.setupResolvConf();
        if (!toolsInstalled()) {
            setProgress("自动补装基础工具（gcc/g++ 等）", 91);
            installTools();
        }

        setProgress("启用 pnpm", 92);
        // 不依赖 corepack（新版 Node 常缺失）：直接用 npm 安装 pnpm@11.7.0（与项目 packageManager 匹配）
        // 已安装则跳过（否则 npm 报 EEXIST 导致重装失败）
        runStep("启用 pnpm", 92,
                "(pnpm -v >/dev/null 2>&1 && echo 'pnpm 已就绪，跳过安装') || "
                        + "if command -v npm >/dev/null 2>&1; then "
                        + "npm install -g pnpm@11.7.0 --registry=https://registry.npmmirror.com; "
                        + "else "
                        + "echo 'npm 缺失，自动补装 Node.js'; "
                        + "[ -s /tmp/node.tar.xz ] || curl -kfsSL --retry 3 https://npmmirror.com/mirrors/node/v24.19.0/node-v24.19.0-linux-arm64.tar.xz -o /tmp/node.tar.xz; "
                        + "mkdir -p /tmp /usr/local 2>/dev/null; "
                        + "tar -xJf /tmp/node.tar.xz -C /usr/local --strip-components=1 && "
                        + "npm install -g pnpm@11.7.0 --registry=https://registry.npmmirror.com; "
                        + "fi");

        setProgress("获取 deepseek-harness 源码", 93);
        runStep("获取 deepseek-harness 源码", 93,
                "cd /root && " +
                "if [ -d " + wd + " ] && [ -f " + wd + "/package.json ]; then " +
                "echo '源码已存在，跳过克隆（尝试增量更新）'; " +
                "(cd " + wd + " && git pull --ff-only 2>/dev/null || true); " +
                "else rm -rf " + wd + " && ( " +
                "git clone --depth 1 " + gitHubProxy("https://github.com/deepseek-ai/deepseek-harness.git") + " " + wd + " || " +
                "git clone --depth 1 https://github.com/deepseek-ai/deepseek-harness.git " + wd + " || " +
                "git clone --depth 1 https://gitclone.com/github.com/deepseek-ai/deepseek-harness.git " + wd + " || " +
                "git clone --depth 1 https://ghfast.top/https://github.com/deepseek-ai/deepseek-harness.git " + wd + " || " +
                "git clone --depth 1 https://gh-proxy.com/https://github.com/deepseek-ai/deepseek-harness.git " + wd + " || " +
                "git clone --depth 1 https://ghproxy.net/https://github.com/deepseek-ai/deepseek-harness.git " + wd + " || " +
                "git clone --depth 1 https://gitcode.com/gh_mirrors/de/deepseek-harness.git " + wd + " ) || " +
                "(echo 'git 克隆失败，改用源码包下载…'; rm -rf " + wd + " && " +
                "(curl -kfsSL --retry 3 -m 300 " + gitHubProxy("https://codeload.github.com/deepseek-ai/deepseek-harness/tar.gz/refs/heads/master") + " -o dsh-src.tar.gz || " +
                "curl -kfsSL --retry 3 -m 300 https://codeload.github.com/deepseek-ai/deepseek-harness/tar.gz/refs/heads/master -o dsh-src.tar.gz || " +
                "curl -kfsSL --retry 3 -m 300 https://ghfast.top/https://codeload.github.com/deepseek-ai/deepseek-harness/tar.gz/refs/heads/master -o dsh-src.tar.gz || " +
                "curl -kfsSL --retry 3 -m 300 https://gh-proxy.com/https://codeload.github.com/deepseek-ai/deepseek-harness/tar.gz/refs/heads/master -o dsh-src.tar.gz || " +
                "curl -kfsSL --retry 3 -m 300 https://ghproxy.net/https://codeload.github.com/deepseek-ai/deepseek-harness/tar.gz/refs/heads/master -o dsh-src.tar.gz) && " +
                "tar -xzf dsh-src.tar.gz && (mv deepseek-harness-master 2>/dev/null || mv deepseek-harness-main " + wd + " ) && rm -f dsh-src.tar.gz); fi");

        // 应用 WebUI 移动端补丁（移除“打开/收起侧边栏”按钮）；失败不阻塞安装
        try {
            String patchStr = readAsset("webui-sidebar.patch");
            if (!patchStr.isEmpty()) {
                java.io.File patchFile = new java.io.File(proot.getRootfsDir(), "root/dsha-webui.patch");
                patchFile.getParentFile().mkdirs();
                try (java.io.FileOutputStream fo = new java.io.FileOutputStream(patchFile)) {
                    fo.write(patchStr.getBytes(StandardCharsets.UTF_8));
                }
                runStep("应用 WebUI 移动端补丁", 93,
                        "cd /root/" + wd + " && " +
                        "(git apply --check /root/dsha-webui.patch 2>/dev/null && " +
                        "git apply /root/dsha-webui.patch && echo '补丁已应用（已移除侧边栏开关按钮）') || " +
                        "echo '补丁跳过（可能已应用或源码已更新）'");
            }
        } catch (Exception ignored) {
        }

        // 应用 bash 安全守卫补丁（bash 工具每次执行前 source dsh-guard.sh，防环境白名单绕过）；失败不阻塞
        try {
            String bgPatch = readAsset("bash-guard.patch");
            if (!bgPatch.isEmpty()) {
                java.io.File bgFile = new java.io.File(proot.getRootfsDir(), "root/dsha-bash-guard.patch");
                try (java.io.FileOutputStream fo = new java.io.FileOutputStream(bgFile)) {
                    fo.write(bgPatch.getBytes(StandardCharsets.UTF_8));
                }
                runStep("应用 bash 守卫补丁", 93,
                        "cd /root/" + wd + " && " +
                        "(git apply --check /root/dsha-bash-guard.patch 2>/dev/null && " +
                        "git apply /root/dsha-bash-guard.patch && echo 'bash 守卫补丁已应用') || " +
                        "echo 'bash 守卫补丁跳过（可能已应用或源码已更新）'");
            }
        } catch (Exception ignored) {
        }

        // WebUI 老浏览器兼容补丁：AbortSignal.any/timeout polyfill（Chrome 118 以下会报 "is not a function"）
        try {
            String poly = readAsset("webui-polyfill.sh");
            if (!poly.isEmpty()) {
                java.io.File polyFile = new java.io.File(proot.getRootfsDir(), "root/dsha-webui-polyfill.sh");
                try (java.io.FileOutputStream fo = new java.io.FileOutputStream(polyFile)) {
                    fo.write(poly.getBytes(StandardCharsets.UTF_8));
                }
                runStep("WebUI 浏览器兼容补丁", 93, "bash /root/dsha-webui-polyfill.sh; rm -f /root/dsha-webui-polyfill.sh");
            }
        } catch (Exception e) {
            android.util.Log.w("DSHA", "WebUI polyfill 注入失败（老 WebView 可能白屏）: " + e);
        }

        // 安装危险命令确认包装器（rootfs 内 rm/dd 等先弹确认，防止 agent/终端误删）
        // 失败必须中断：安全功能没装好，安装不算完成
        {
            String inst = readAsset("rootfs-confirm-install.sh");
            if (!inst.isEmpty()) {
                java.io.File instFile = new java.io.File(proot.getRootfsDir(), "root/install-confirm.sh");
                try (java.io.FileOutputStream fo = new java.io.FileOutputStream(instFile)) {
                    fo.write(inst.getBytes(StandardCharsets.UTF_8));
                }
                runStep("安装危险命令确认包装器", 93,
                        "bash /root/install-confirm.sh && rm -f /root/install-confirm.sh");
            }
        }

        // 准备 Node headers（已缓存则跳过；node-gyp 现场下载会连 nodejs.org，国内被墙）
        runStep("准备 Node headers", 94,
                "if [ ! -f /root/.cache/node-gyp/24.19.0/include/node/node.h ]; then " +
                "mkdir -p /root/.cache/node-gyp/24.19.0 && cd /root/.cache/node-gyp/24.19.0 && " +
                "(curl -kfsSL --retry 3 https://npmmirror.com/mirrors/node/v24.19.0/node-v24.19.0-headers.tar.gz -o headers.tar.gz || " +
                "curl -kfsSL https://cdn.npmmirror.com/binaries/node/v24.19.0/node-v24.19.0-headers.tar.gz -o headers.tar.gz) && " +
                "tar -xzf headers.tar.gz --strip-components=1 && rm -f headers.tar.gz && touch .install-stamp; " +
                "else echo 'Node headers 已缓存，跳过下载'; fi");

        // 关键：pnpm 10/11 默认忽略依赖构建脚本（node-pty 的 node-gyp 编译会被跳过），
        // 必须把 node-pty 加入 onlyBuiltDependencies 白名单才会执行
        runStep("允许原生模块构建（node-pty）", 94,
                "cd /root/" + wd + " && " +
                "(grep -q 'onlyBuiltDependencies' pnpm-workspace.yaml 2>/dev/null || " +
                "printf '\\nonlyBuiltDependencies:\\n  - node-pty\\n' >> pnpm-workspace.yaml) && " +
                // Ubuntu 24.04 无 /usr/bin/python（只有 python3），部分构建工具死认 python 命令
                "(command -v python >/dev/null 2>&1 || ln -sf /usr/bin/python3 /usr/bin/python || true)");

        // 先把 pnpm 的硬链接导入关掉：proot 下 link() 只是 symlink 模拟，
        // 留下的悬空链会让后续安装报各种莫名的 ENOENT
        runAssetScript("pnpm-env-fix.sh", "dsha-pnpm-env-fix.sh", 60_000);
        setProgress("安装依赖 pnpm install（npmmirror 源）", 95);
        try {
            // 注意：npm 11 的 `npm config set disturl` 会报 "not a valid npm option"，
            // 所以直接写 .npmrc 文件 + 环境变量（不经过 npm 配置校验）
            // pnpm 会持续打印进度，五分钟一声不吭基本就是网络挂了
            runStep("安装依赖 pnpm install", 95,
                    "cd /root/" + wd + " && " +
                    // 三项一次写全（以前只写 registry 且用 > 覆盖，会把 pnpm-env-fix.sh
                    // 刚配好的两项冲掉）。**但别指望这里的 package-import-method 生效**：
                    // pnpm ≥10 这一项只从 pnpm-workspace.yaml 读（2026-08-30 实测，
                    // 见 tools/pnpm-import-test.sh），.npmrc 里的写法只对 pnpm 9 / npm 有意义。
                    // 真正关掉硬链接导入的是 pnpm-env-fix.sh §③ 往工作区 pnpm-workspace.yaml
                    // 追加的 packageImportMethod —— 它在上面那次 runAssetScript 里已经跑过。
                    "printf 'registry=https://registry.npmmirror.com\\n"
                    + "package-import-method=copy\\nside-effects-cache=false\\n'"
                    + " > /root/.npmrc && " +
                    lowResourceEnv() +
                    // 受限容器里 fork 受限，--child-concurrency=1 让 pnpm 串行跑
                    // 依赖脚本；非 anco 环境 lowResourceEnv() 为空，参数也不加
                    (isAncoContainer() ? "pnpm install --child-concurrency=1" : "pnpm install"),
                    // pnpm 会持续打印包名，五分钟一声不吭基本就是网络挂了
                    300_000L);
        } catch (Exception e) {
            throw new Exception(e.getMessage() + "\n\n[原生模块编译失败提示]\n"
                    + "1. Node headers 已预下载到 node-gyp 缓存（npmmirror 源），不依赖 nodejs.org\n"
                    + "2. 工具链已自动补装（gcc/g++/make/python3），可重试本步骤\n"
                    + "3. 若仍失败，可能是设备内存不足，可改选「预构建包」方式");
        }

        // 直接调用 node-gyp 编译 node-pty（绕开 npm/pnpm 的构建脚本管理）：
        // 有预编译产物（prebuilds/linux-arm64/pty.node）则直接跳过编译；
        // 编译失败时输出完整诊断日志（便于定位根因）
        runStep("编译 node-pty（node-gyp）", 96,
                "cd /root/" + wd + " && " +
                "NP=$(ls -d node_modules/.pnpm/node-pty@*/node_modules/node-pty 2>/dev/null | head -1) && " +
                "if [ -f \"$NP/prebuilds/linux-arm64/pty.node\" ]; then " +
                "echo '检测到 node-pty 预编译产物，跳过 node-gyp 编译'; " +
                "else cd \"$NP\" && " +
                "GYP=/usr/local/lib/node_modules/npm/node_modules/node-gyp/bin/node-gyp.js && " +
                "if [ ! -f \"$GYP\" ]; then GYP=$(find /usr/local/lib -maxdepth 8 -path '*/node-gyp/bin/node-gyp.js' 2>/dev/null | head -1); fi && " +
                "echo \"node-gyp 路径: $GYP\" && " +
                "export npm_config_disturl=https://npmmirror.com/mirrors/node && " +
                "(node \"$GYP\" rebuild > /tmp/node-gyp.log 2>&1 || " +
                "{ echo '--- node-gyp 编译失败，诊断信息 ---'; " +
                "grep -E 'gyp ERR!|Error|error:|fatal' /tmp/node-gyp.log | head -25; exit 1; }); fi");

        // 验证 node-pty 编译产物确实生成了（否则启动 Web UI 时必炸）
        // 全局 dsh 命令：符号链接到 /usr/local/bin（终端可直接敲 dsh）
        try {
            runStep("安装 dsh 命令", 99,
                    "ln -sf /root/" + wd + "/apps/cli/lib/bin.js /usr/local/bin/dsh && " +
                    "chmod +x /usr/local/bin/dsh 2>/dev/null; echo 'dsh 命令已安装'");
        } catch (Exception e) {
            android.util.Log.w("DSHA", "安装 dsh 命令这一步失败（后续 dsh 可能不可用）: " + e);
        }

        runStep("验证 pty.node 产物", 97,
                "cd /root/" + wd + " && " +
                "P=$(ls node_modules/.pnpm/node-pty@*/node_modules/node-pty/build/Release/pty.node 2>/dev/null | head -1); " +
                "if [ -z \"$P\" ]; then P=$(ls node_modules/.pnpm/node-pty@*/node_modules/node-pty/prebuilds/linux-arm64/pty.node 2>/dev/null | head -1); fi; " +
                "if [ -z \"$P\" ]; then " +
                "echo 'ERROR: pty.node 未生成，node-pty 目录内容：'; " +
                "ls -la node_modules/.pnpm/node-pty@*/node_modules/node-pty/ 2>/dev/null; " +
                "ls -la node_modules/.pnpm/node-pty@*/node_modules/node-pty/build/ 2>/dev/null; " +
                "exit 1; fi; " +
                "echo \"pty.node 已就绪: $P\"");

        setProgress("构建 deepseek-harness", 97);
        runStep("构建 pnpm run build", 97, "cd /root/" + wd + " && pnpm run build");

        runStep(effectiveApiKey().isEmpty() ? "写入配置（API key 留空）" : "写入 API key", 99,
                "cd /root/" + wd + " && " + envWriteCommand());
        setProgress("deepseek-harness 构建完成", 99);
    }

    // ================= 下载源：测速 + 用户自选 =================

    /** 统一下载流程：测速 → 弹窗自选源 → 下载（失败自动 fallback 其他源） */
    private void downloadWithPick(int task, String[] urls, String what, File dest,
                                  int pBase, int pDiv) throws Exception {
        setProgress(what + "：测速中…（" + urls.length + " 个源并行测速）", pBase);
        long[] lat = proot.probeAll(urls, 6000);
        setProgress(what + "：请在弹窗中选择下载源", pBase + 1);
        String[] ordered = waitUserPick(task, urls, lat);

        boolean ok = false;
        String lastErr = "";
        for (String url : ordered) {
            final RateMeter meter = new RateMeter();
            try {
                proot.downloadRootfs(url, dest, (down, total) -> {
                    double rate = meter.feed(down);
                    if (total <= 0) {
                        // 源没给 Content-Length：至少把已下大小与速率显示出来，别让用户以为卡死
                        setProgress(what + "… " + fmtBytes(down) + " · " + fmtRate(rate)
                                + "（源未提供大小 · " + hostOf(url) + "）", Math.min(99, pBase + 1));
                    } else {
                        int pct = (int) (down * 100 / total);
                        long eta = meter.eta(down, total);
                        setProgress(what + " " + fmtBytes(down) + "/" + fmtBytes(total)
                                        + "（" + pct + "%）· " + fmtRate(rate)
                                        + (eta >= 0 ? " · 剩余 " + fmtEta(eta) : "")
                                        + " · 源 " + hostOf(url),
                                Math.min(99, pBase + 1 + pct / pDiv));
                    }
                });
                ok = true;
                break;
            } catch (Exception e) {
                lastErr = e.getMessage();
            }
        }
        if (!ok) {
            throw new Exception(what + " 下载失败: " + lastErr
                    + "\n\n可尝试：切换网络 / 开启代理");
        }
    }

    /** IO 线程阻塞等待用户在 UI 弹窗中选择下载源（2 分钟超时后自动选最快） */
    private String[] waitUserPick(int task, String[] urls, long[] lat) throws Exception {
        pendingTask = task;
        pendingUrls = urls;
        pendingLat = lat;
        sourceChoice = -1;
        awaitingSource = true;
        setState("请选择下载源（测速完成）", percent, "", "", true);
        synchronized (sourceLock) {
            long deadline = System.currentTimeMillis() + 120_000;
            while (awaitingSource) {
                long remain = deadline - System.currentTimeMillis();
                if (remain <= 0) break;
                sourceLock.wait(remain);
            }
        }
        awaitingSource = false;

        // 确定首选：用户选择优先，否则自动选测速最快的
        int first = -1;
        if (sourceChoice >= 0 && sourceChoice < urls.length) {
            first = sourceChoice;
            prefs.edit().putString("src_" + task, urls[sourceChoice]).apply();
        } else {
            for (int i = 0; i < lat.length; i++) {
                if (lat[i] >= 0 && (first < 0 || lat[i] < lat[first])) first = i;
            }
            if (first < 0) first = 0;
        }
        String[] ordered = new String[urls.length];
        ordered[0] = urls[first];
        int k = 1;
        for (int i = 0; i < urls.length; i++) {
            if (i != first) ordered[k++] = urls[i];
        }
        return ordered;
    }

    /** UI 调用：用户已选择（index>=0 选中项；-1 自动选最快） */
    public void onSourceChosen(int index) {
        sourceChoice = index;
        awaitingSource = false;
        synchronized (sourceLock) {
            sourceLock.notifyAll();
        }
    }

    public boolean isAwaitingSourceChoice() { return awaitingSource; }

    /** 待选源的展示文案（名称 + 延迟；git:// 为直连源码构建选项） */
    public String[] getPendingSourceLabels() {
        if (pendingUrls == null || pendingLat == null) return new String[0];
        String[] labels = new String[pendingUrls.length];
        for (int i = 0; i < pendingUrls.length; i++) {
            String u = pendingUrls[i];
            if (u.startsWith("git://")) {
                labels[i] = "⚡ 直连 GitHub 源码构建（clone + 本地构建，无需预构建包）";
                continue;
            }
            long l = pendingLat[i];
            labels[i] = sourceLabel(u) + (l >= 0 ? "   延迟 " + l + "ms" : "   不可用 ✗");
        }
        return labels;
    }

    /** 弹窗默认选中项：上次选择 > 测速最快 */
    public int getPendingDefaultIndex() {
        String saved = pendingUrls != null
                ? prefs.getString("src_" + pendingTask, "") : "";
        for (int i = 0; pendingUrls != null && i < pendingUrls.length; i++) {
            if (pendingUrls[i].equals(saved)) return i;
        }
        int best = 0;
        // 防御：pendingLat 必须与 pendingUrls 等长（否则越界/选错）
        if (pendingLat == null || pendingUrls == null
                || pendingLat.length != pendingUrls.length) {
            return 0;
        }
        for (int i = 1; i < pendingLat.length; i++) {
            if (pendingLat[i] >= 0 && (pendingLat[best] < 0 || pendingLat[i] < pendingLat[best])) best = i;
        }
        return best;
    }

    private static String sourceLabel(String url) {
        String h = hostOf(url);
        if (h.startsWith("cdn.npmmirror")) return "npmmirror CDN（" + h + "）";
        if (h.contains("npmmirror")) return "npmmirror（" + h + "）";
        if (h.contains("tuna")) return "清华镜像（" + h + "）";
        if (h.contains("aliyun")) return "阿里云镜像（" + h + "）";
        if (h.contains("huaweicloud")) return "华为云镜像（" + h + "）";
        if (h.contains("tencent")) return "腾讯云镜像（" + h + "）";
        if (h.contains("nju.edu")) return "南京大学镜像（" + h + "）";
        if (h.contains("hit.edu")) return "哈工大镜像（" + h + "）";
        if (h.contains("bfsu")) return "北外镜像（" + h + "）";
        if (h.contains("sjtu")) return "上海交大镜像（" + h + "）";
        if (h.contains("nodejs.org")) return "Node 官方（" + h + "）";
        if (h.contains("cdimage")) return "Ubuntu 官方（" + h + "）";
        if (h.contains("catbox")) return "catbox 网盘（" + h + "）";
        return h;
    }

    /**
     * 执行单个安装步骤：输出重定向到日志文件（避免大量输出走 proot 管道导致崩溃），
     * 失败时输出日志尾部以便定位。
     */
    private void runStep(String stage, int percent, String cmd) throws Exception {
        runStep(stage, percent, cmd, 0L);
    }

    /** @param stallMs 大于 0 时启用静默看门狗：这么久一个字都不吐就判卡死。
     *                 只给会持续打印进度的命令用（pnpm/apt/npm）；tar、xz 正常
     *                 也会长时间静默，传 0 走无超时的老路，免得误杀。 */
    private void runStep(String stage, int percent, String cmdIn, long stallMs)
            throws Exception {
        String cmd = cmdIn;
        setProgress(stage, percent);
        // 安装步骤此前完全依赖容器的默认 PATH。用户实测（1.1.7）第五步直接抛
        // 「退出码 127」——127 就是 command not found：node/npm 装在
        // /usr/local/bin，而某些 rootfs 的非登录 shell 默认 PATH 里没有它，
        // 于是 npm 压根找不到。整条 exec 只回一个裸数字，用户看不懂，
        // 我们也无从判断是网络问题还是环境问题。
        // 这里统一前置补全，$PATH 拼在后面保留原有值（幂等、对正常环境无影响）。
        cmd = "export PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin:$PATH; "
                + cmd;
        // 输出重定向到日志、失败时回吐尾部 —— 但这样一来标准输出就没内容了，
        // 静默看门狗看不到进度。所以启用看门狗时改成 tee：日志照留，同时有输出可判活。
        String fullCmd = stallMs > 0
                ? "(" + cmd + ") 2>&1 | tee /root/dsh-step.log; "
                + "test ${PIPESTATUS[0]} -eq 0 || { echo '--- 日志尾部 ---'; "
                + "tail -100 /root/dsh-step.log; exit 1; }"
                : "(" + cmd + ") >/root/dsh-step.log 2>&1"
                + " || { echo '--- 日志尾部 ---'; tail -100 /root/dsh-step.log; exit 1; }";
        if (stallMs > 0) {
            proot.execCheckedStall(fullCmd, stallMs);
        } else {
            proot.execChecked(fullCmd);
        }
    }

    // ================= 脚本与命令 =================
    /** 注入并执行 assets 里的一次性 bash 脚本（跑完即删），返回合并输出；出错静默返回 null。
     *  这些补丁/自愈脚本的调用模式完全一致，集中在这里，省掉六份复制粘贴。 */
    /** 包级可见：{@link PluginController} 的 pnpm 依赖自愈要用。 */
    String runAssetScript(String assetName, String remoteName, long timeoutMs) {
        try {
            String script = readAsset(assetName);
            if (script == null || script.isEmpty()) return null;
            java.io.File f = new java.io.File(proot.getRootfsDir(), "root/" + remoteName);
            if (f.getParentFile() != null) f.getParentFile().mkdirs();
            java.nio.file.Files.write(f.toPath(), script.getBytes(StandardCharsets.UTF_8));
            // 公开目录名随变体而变（内测包用 dshdata-beta），脚本不能自己写死 ——
            // 两个包共用一份公开数据的后果是互相覆盖会话镜像与备份。
            return proot.execAndRead(
                    "export DSH_PUB_DIR=" + ShellQuote.arg(PublicDirs.dataDirGuestPath()) + "; "
                            + "bash /root/" + remoteName + "; rm -f /root/" + remoteName, timeoutMs);
        } catch (Throwable e) {
            android.util.Log.w("DSHA", "脚本 " + assetName + " 执行失败（不影响主流程）: " + e);
            return null;
        }
    }

    /** 会话落点脚本在 rootfs 里的常驻名（{@code sync} 要反复调，不能每次重新注入）。 */
    private static final String SESSION_HOME_SCRIPT = "dsha-session-home.sh";

    /**
     * 保证 {@code .dsh/sessions} 是 <b>App 私有目录里的实体目录</b>，必要时从公开目录搬回。
     *
     * <p><b>为什么必须这样</b>：dsh 0.1.3 起每个会话目录里有一个 {@code session.lock}，
     * 由 fs-ext 做非阻塞 {@code flock(2)}。真机实测（V2352A）：App 私有目录（ext4）上
     * flock 成功且互斥生效，而公开目录是 FUSE —— 一律返回 <b>ENOSYS</b>。dsh 的 lease
     * 只把 EAGAIN 当「别人持锁」、其它 errno 直接往上抛，所以 sessions 留在公开目录
     * 就是「会话根本打不开」。
     *
     * <p>必须在 dsh 启动<b>之前</b>跑完：搬动的是会话目录本身，边跑边写会撕。
     */
    void ensureSessionHome() {
        String out = runAssetScript("session-home.sh", SESSION_HOME_SCRIPT, 120_000);
        if (out == null) return;
        if (out.contains("SESSIONS_MIGRATED")) {
            logActivity("会话数据已搬回 App 私有目录（新版 dsh 的会话锁在公开目录上不工作），"
                    + "公开目录保留一份镜像用于重装恢复");
        } else if (out.contains("SESSIONS_RESTORED")) {
            logActivity("已从公开目录的镜像恢复会话数据");
        } else if (out.contains("SESSIONS_FAIL")) {
            logActivity("会话目录就位失败，数据没动，详见日志：" + SensitiveData.redact(out.trim()));
        }
        // 脚本被 runAssetScript 跑完删掉了，这里再落一份常驻副本供 sync 使用
        try {
            String script = readAsset("session-home.sh");
            if (script != null && !script.isEmpty()) {
                java.io.File f = new java.io.File(proot.getRootfsDir(), "root/" + SESSION_HOME_SCRIPT);
                if (f.getParentFile() != null) f.getParentFile().mkdirs();
                java.nio.file.Files.write(f.toPath(), script.getBytes(StandardCharsets.UTF_8));
            }
        } catch (Throwable ignored) {
        }
    }

    /**
     * 把私有目录里的会话增量同步到公开镜像 —— 卸载重装后靠它恢复。
     *
     * <p>刻意只增不删：删除不可逆，而「重装后多回来几个已删会话」远比同步逻辑误删轻。
     * 调用时机是「一段工作结束」而不是定时轮询：停 Web、进后台、自动备份那条线上各一次。
     */
    void syncSessionMirror() {
        try {
            if (!proot.isInstalled()) return;
            java.io.File f = new java.io.File(proot.getRootfsDir(), "root/" + SESSION_HOME_SCRIPT);
            if (!f.isFile()) {
                ensureSessionHome();
                if (!f.isFile()) return;
            }
            String out = proot.execAndRead(
                    "export DSH_PUB_DIR=" + ShellQuote.arg(PublicDirs.dataDirGuestPath()) + "; "
                            + "bash /root/" + SESSION_HOME_SCRIPT + " sync", 120_000);
            if (out != null && out.contains("SYNC_PARTIAL")) {
                android.util.Log.w("DSHA", "会话镜像同步不完整: " + out.trim());
            }
        } catch (Throwable e) {
            android.util.Log.w("DSHA", "会话镜像同步失败（不影响使用）: " + e);
        }
    }

    /**
     * 一次容器会话里顺序跑多个 assets 脚本，返回每个脚本各自的输出（key = assetName）。
     *
     * <p><b>为什么要有它</b>：{@link #runAssetScript} 每调一次就是一次独立的容器会话 ——
     * 启动器 + bash 的固定成本要付一遍。而启动路径上排着 6 个幂等自愈脚本，绝大多数时候
     * 它们只是确认「补丁已经在了」就返回，真正干活的时间远小于起会话的开销。
     * 合并之后语义不变：还是顺序执行、彼此独立、某个失败不影响后面（用 {@code ;} 串联，
     * 和原来各自一次会话一样），只是固定成本付一次。
     *
     * <p><b>唯一的行为差异</b>：原先每个脚本有各自的超时，现在整批共用一个。也就是说
     * 前面某个脚本卡到超时，后面的这一轮就不跑了（下次启动还会再来，它们都是幂等的）。
     * 这几个脚本都是本地 sed/patch，卡住基本等于容器本身出了问题，那时后面的也做不成 ——
     * 拿这个换掉 5 次会话的固定开销是划算的。
     *
     * <p>输出用一行随机哨兵切分，所以脚本自己打印什么都不会破坏切分。
     *
     * @param specs 每项是 {@code {assetName, 落在 rootfs /root 下的文件名}}，按数组顺序执行
     */
    java.util.Map<String, String> runAssetScripts(String[][] specs, long timeoutMs) {
        java.util.List<String> assetNames = new java.util.ArrayList<>();
        java.util.List<String> remoteNames = new java.util.ArrayList<>();
        for (String[] sp : specs) {
            String script = readAsset(sp[0]);
            if (script == null || script.isEmpty()) continue;
            try {
                java.io.File f = new java.io.File(proot.getRootfsDir(), "root/" + sp[1]);
                if (f.getParentFile() != null) f.getParentFile().mkdirs();
                java.nio.file.Files.write(f.toPath(), script.getBytes(StandardCharsets.UTF_8));
            } catch (Throwable e) {
                android.util.Log.w("DSHA", "写脚本失败（跳过）: " + sp[0] + " " + e);
                continue;
            }
            assetNames.add(sp[0]);
            remoteNames.add(sp[1]);
        }
        if (assetNames.isEmpty()) return new java.util.LinkedHashMap<>();
        String sep = AssetBatch.newSeparator();
        long t0 = System.currentTimeMillis();
        String all = proot.execAndRead(
                "export DSH_PUB_DIR=" + ShellQuote.arg(PublicDirs.dataDirGuestPath()) + "; "
                        + AssetBatch.buildCommand(sep, remoteNames), timeoutMs);
        lastSelfHealMs += System.currentTimeMillis() - t0;
        lastSelfHealSessions++;
        return AssetBatch.splitOutput(sep, assetNames, all);
    }

    /** 启动准备阶段的耗时与会话数（只用于活动日志里那一行观测，真机拿数据用）。 */
    private long lastSelfHealMs;
    private int lastSelfHealSessions;

    /** {@code fs-write-patch.sh} 的结果记账 —— 单跑与批量跑共用同一份判断。 */
    private void noteFsWritePatchResult(String r) {
        android.util.Log.i("DSHA", "write 发布补丁: " + (r == null ? "无输出" : r.trim()));
        // 只在真的打上时记账：幂等返回（ALREADY）每次启动都会有，记了就是刷屏
        if (r != null && r.contains("PATCHED")) {
            logActivity("给 dsh 打了 write / 会话发布补丁（治新建文件变悬空链接）");
        }
    }

    public String readAsset(String name) {
        // 增量更新的覆盖层优先：脚本层的修复（几 KB）不必等下一个 384MB 的 APK。
        // 覆盖层为空或读失败就回落到 APK 内置版本 —— 删掉覆盖文件即回退。
        try {
            java.io.File over = RuntimeUpdater.overlayFile(appContext, name);
            if (over.isFile() && over.length() > 0) {
                byte[] b = java.nio.file.Files.readAllBytes(over.toPath());
                return new String(b, StandardCharsets.UTF_8);
            }
        } catch (Throwable e) {
            android.util.Log.w("DSHA", "读覆盖层脚本失败，回落内置版本: " + name + " " + e);
        }
        try (BufferedReader r = new BufferedReader(new InputStreamReader(
                appContext.getAssets().open(name), StandardCharsets.UTF_8))) {
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = r.readLine()) != null) sb.append(line).append('\n');
            return sb.toString();
        } catch (Exception e) {
            return "";
        }
    }

    /** 二进制读取 assets（如 whl 等）；失败返回 null */
    private byte[] readAssetBytes(String name) {
        try (java.io.InputStream in = appContext.getAssets().open(name)) {
            java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream();
            byte[] buf = new byte[65536];
            int n;
            while ((n = in.read(buf)) != -1) bos.write(buf, 0, n);
            return bos.toByteArray();
        } catch (Exception e) {
            return null;
        }
    }

    /** .env 写入命令。没填 key 就只写一行注释——写 DEEPSEEK_API_KEY= 空值的话，
     *  dsh 会认为「配了一个无效 key」而直接报错；变量完全不存在它才会回退到
     *  自己的设置（WebUI 里可以配官方或第三方服务商）。 */
    private String envWriteCommand() {
        String k = effectiveApiKey();
        if (k.isEmpty()) {
            return "printf '%s\\n' "
                    + "'# DEEPSEEK_API_KEY 未配置：可在 WebUI 的设置里配置官方或第三方 API' > .env";
        }
        return "printf 'DEEPSEEK_API_KEY=%s\\n' " + ShellQuote.arg(k) + " > .env";
    }

    /** 启动命令里的 key 导出片段（含尾部 " && "）；未配置则返回空串，不导出空值 */
    private String apiKeyExportChain() {
        String k = effectiveApiKey();
        return k.isEmpty() ? "" : "export DEEPSEEK_API_KEY=" + ShellQuote.arg(k) + " && ";
    }

    /** 重启脚本里的 key 导出行（含换行）；未配置则返回空串 */
    private String apiKeyExportLine() {
        String k = effectiveApiKey();
        return k.isEmpty() ? "" : "export DEEPSEEK_API_KEY=" + ShellQuote.arg(k) + "\n";
    }


    public String startWebCommand() {
        // 启动前自愈：确保配置修复脚本已就位（纯文件写入，不进容器）
        ensureConfigFixAsset();
        // ── 启动前自愈脚本：合并成一次容器会话 ──
        // 这四个原来是四次独立调用（webserver-auth / polyfill / origin-port 各一次，
        // fs-write 一次），也就是四次启动器 + bash 的固定开销，而它们绝大多数时候只是
        // 确认「补丁已经在了」。顺序保持原样，彼此本来也没有依赖（改的是不同文件）。
        // 它们都跟 profile 无关，所以整批放在 ensureBuiltinBundles 之前，与原顺序一致。
        lastSelfHealMs = 0;
        lastSelfHealSessions = 0;
        long prepStart = System.currentTimeMillis();
        // 这 6 个脚本都是幂等补丁：绝大多数启动里它们只是确认「补丁已经在了」就返回。
        // 而真机日志显示这一段要 3.5 秒（自愈脚本 3538ms / 2 次容器会话）——
        // 几乎全是起容器会话的固定成本，用户在白等一件大概率什么都不用做的事。
        //
        // 门槛：这些补丁的前提只有两个 —— 我们的资产版本，以及 profile 的
        // node_modules 那一棵。两者都没变就没有重打的理由。pnpm 重装、装插件、
        // 换 dsh 版本都会改 node_modules 的 mtime，那时 stamp 对不上，照旧全打一遍。
        final String patchStamp = patchStampNow();
        final boolean patchesFresh = !patchStamp.isEmpty()
                && patchStamp.equals(prefs.getString("patch_stamp", ""));
        try {
            java.util.Map<String, String> r1 = patchesFresh
                    ? java.util.Collections.<String, String>emptyMap()
                    : runAssetScripts(new String[][]{
                    // dsh 的 Web 服务本身没有鉴权（上游只绑 127.0.0.1，而 Android 上任何
                    // App 都能访问回环且不需要权限）→ 给它加 token 校验
                    {"webserver-auth-patch.sh", "dsha-webserver-auth.sh"},
                    // 老 WebView 兼容：AbortSignal.any/timeout + crypto.randomUUID polyfill
                    {"webui-polyfill.sh", "dsha-webui-polyfill.sh"},
                    // 外部浏览器 /api 403 修复（Chrome 150+ 的 Origin 省略端口）
                    {"webui-origin-port-patch.sh", "dsha-origin-port-patch.sh"},
                    // write 工具新建文件变悬空链接（l2s 与 dsh 的 link 发布冲突）
                    {"fs-write-patch.sh", "dsha-fs-write-patch.sh"},
                    // 会话锁兜底：落点不支持 flock(2) 时视为已持锁。正主是 session-home.sh
                    // （把 sessions 放在 flock 可用的私有目录），这条管兜不住的路径 ——
                    // 迁移中间态、从镜像恢复、用户手改配置、别家 ROM 挂载差异。
                    // dsh 0.1.3 之前没有 fs-ext，脚本会明确报 NOTARGET。
                    {"session-lock-patch.sh", "dsha-session-lock-patch.sh"},
            }, 240_000);
            noteFsWritePatchResult(r1.get("fs-write-patch.sh"));
        } catch (Throwable ignored) {
        }
        // 启动前自愈：内置插件（dsh-web-mobile/device-shell-guide）注册校验，
        // 被 dsh plugin reconcile 清掉/丢失时自动补回（幂等，纯 Java）
        try {
            ensureBuiltinBundles();
        } catch (Throwable ignored) {
        }
        // ── 第二批：这两个要动 .dsh 数据与 profile 的 bundles ──
        // **必须留在 ensureBuiltinBundles 之后**：先补回内置 bundle，再清理解析不到的，
        // 顺序反过来会把刚补回的又清掉。所以这里单独一批，不与上面那四个合并。
        try {
            if (!patchesFresh) runAssetScripts(new String[][]{
                    // 热数据迁移到公开目录（会话/设置/附件跨重装不丢）：
                    // 幂等且安全，只迁纯文件目录、不碰 credentials、失败保留私有副本
                    {"migrate-public-data.sh", "dsha-migrate-public.sh"},
                    // 清理无法解析的 stale bundle（防 cannot resolve profile bundle 启动崩溃）
                    {"fix-stale-bundles.sh", "dsha-fix-stale-bundles.sh"},
            }, 120_000);
        } catch (Throwable ignored) {
        }
        // 会话落点：sessions 必须是私有实体目录（新版 dsh 的会话锁在公开目录 FUSE 上
        // 返回 ENOSYS）。**必须在 dsh 起来之前**做完 —— 它搬的是会话目录本身。
        try {
            ensureSessionHome();
        } catch (Throwable ignored) {
        }
        // 老 WebView 兼容：系统内核太旧时先把前端降级，成功的话就不必起 GeckoView
        // （省一整份浏览器引擎的内存）。放在 patch 那批之后：它改的是前端产物，
        // 与 profile / bundles 无关，但要在 Web 起来之前做完。
        ensureLegacyFrontendIfNeeded();
        // 局域网访问：deepseek-harness 官方 CLI 默认拒绝 --host 0.0.0.0，
        // 需先打 lan-bind-patch.sh 放行（失败则回落到 127.0.0.1，服务保证能起）。
        boolean lan = appContext.getSharedPreferences("deepseekharness", android.content.Context.MODE_PRIVATE)
                .getBoolean("lan_mode", false);
        boolean lanReady = lan && tryEnableLanBind();
        StringBuilder sb = new StringBuilder();
        // 用户明确要启动：撤掉停止哨兵。这是它<b>唯一</b>的删除点（见 WebProcSel.STOP_SENTINEL 的说明）——
        // 谁都别顺手在别处 rm 一下，那等于把「用户已停止」这个事实抹掉。
        sb.append("rm -f ").append(WebProcSel.STOP_SENTINEL).append(" 2>/dev/null; ")
          .append("export DSH_HOME=/root/.dsh && ")
          .append(apiKeyExportChain())
          .append("export DSH_PERMISSION_MODE=").append(ShellQuote.arg(getPermissionMode())).append(" && ")
          // 危险命令确认：agent 在 rootfs 内的 rm/dd 等操作需用户确认
          // PATH 包装器 + bash 工具 lib 补丁加载守卫（双保险；不设 BASH_ENV——它会污染
          // RC6 插件初始化时子 shell 的环境，导致 dsh web 加载插件失败(index 24 崩溃)）
          .append("export DSH_CONFIRM=1 && ")
          // 不让 dsh 去拉系统浏览器（我们用 GeckoView 内嵌显示）
          .append("export BROWSER=true && ")
          // 预创建常见插件数据目录：只建 /root/.dsh/plugins（无副作用）。
          // 注意：不再建 /root/.codex/pets —— deepseek-pet 插件把「空 pets 目录」
          // 当错误（no pet packages found）→ 整个插件树加载失败！
          // 改为：若 pets 目录存在但为空则删除（让插件走「无 pet」正常分支）。
          .append("mkdir -p /root/.dsh/plugins 2>/dev/null; "
                  + "[ -d /root/.codex/pets ] && [ -z \"$(ls -A /root/.codex/pets 2>/dev/null)\" ] "
                  + "&& rmdir /root/.codex/pets 2>/dev/null; ")
          // 局域网模式：补丁成功后绑定 0.0.0.0 并打印访问地址；失败只提示，不影响启动
          //
          // 这条 echo 原来是：
          //   echo '[DSHA] 局域网访问(App桥): http://$(hostname -I ... | cut -d' ' -f1):3081'
          // 两个毛病：① $() 在**单引号**里不展开，② `cut -d' '` 的单引号先把外层单引号
          // 闭合了，于是 echo 拿到两个参数，用户看到的是字面
          //   http://$(hostname -I 2>/dev/null | cut -d  -f1):3081
          // 现在把 IP 先取到变量里（命令替换在引号外），再用「单引号段 + 变量」相邻拼接，
          // 不出现引号嵌套。execRootfs 是 ProcessBuilder 直传 bash -c，没有二次解析。
          //
          // 刻意**不打印 token**：完整地址在启动页可一键复制，而这里的输出会进
          // dsh 日志、被 agent 与第三方插件读到，没必要多一个泄漏面。
          .append(lanReady
                  ? "LANIP=$(hostname -I 2>/dev/null | cut -d' ' -f1); "
                    + "echo '[DSHA] 局域网访问(App桥): http://'${LANIP:-手机IP}':"
                    + LanProxyService.LAN_PORT + "/  完整地址含 token，请在 App 启动页复制' && "
                  : lan ? "echo '[DSHA] 局域网未开启(官方 0.0.0.0 未放行)，仅本机可访问' && " : "")
          // 先拉起看门狗（后台），再 exec WebUI（前台阻塞）——顺序不能反，否则看门狗永不启动
          .append("nohup bash /root/dsh-watchdog.sh >> /root/dsh-watchdog.log 2>&1 & ")
          // 核心命令：源码目录存在走 node，否则自动回退全局 dsh（含 exec + 日志重定向）
          .append(runCoreCommand(lanReady));
        // 写入看门狗（重启脚本 = 启动核心命令），并拉起看门狗守护
        writeWatchdogFiles(runCoreCommand(lanReady), parsePort());
        // 观测用（真机拿数据）：准备阶段总耗时，以及自愈脚本占了多少、跑了几次容器会话。
        // 这一行是「还要不要继续合并会话」的依据 —— 没有数字就不该再动启动路径。
        if (!patchesFresh && !patchStamp.isEmpty()) {
            // 打完之后重新取一次：补丁自己动了 node_modules 的话，
            // 用打之前那份 stamp 会导致下次启动又对不上、又白打一遍。
            prefs.edit().putString("patch_stamp", patchStampNow()).apply();
        }
        logActivity("启动准备耗时 " + (System.currentTimeMillis() - prepStart)
                + "ms（自愈脚本 " + lastSelfHealMs + "ms / "
                + lastSelfHealSessions + " 次容器会话"
                + (patchesFresh ? "，补丁前提未变已整批跳过" : "") + "）");
        return sb.toString();
    }

    /** 依赖自愈命令片段：源码构建模式下，workspace 关键包缺失时自动重跑 pnpm install。
     *  k 先探 require.resolve（毫秒级），只有缺失才修复（--offline 用本机 store，失败回落 npmmirror）。 */
    /** 包级可见：{@link PluginController} 装插件失败时先跑一遍依赖自愈。 */
    String depsSelfHeal() {
        if (useRc6()) return ""; // 预构建包（全局 node_modules）不走源码仓库结构
        String wd = getWorkdir();
        // 关键 workspace 包清单：任一 require.resolve 失败即视为依赖缺失，自动 pnpm install
        // 保底超时：offline 90s / 联网 180s，避免长卡拖崩启动
        // 探测「这几个 workspace 包在不在」原来用 node -e require.resolve —— 那是一次完整的
        // node 冷启动（进程创建 + V8 初始化），在 proot 里 200~500ms，每次启动都付，而绝大
        // 多数时候答案都是「在」。改用 shell 看 package.json：缺失场景是整个包没装，而
        // package.json 存在比目录存在更能说明装完了。省掉一次 node 启动。
        return "for m in dsh-app-boot dsh-workspace dsh-session dsh-base; do "
                + "[ -f node_modules/@deepseek-ai/$m/package.json ] || "
                + "{ echo '[DSHA] 检测到 harness 依赖缺失，正在自动修复…'; "
                + "(timeout 90 pnpm install --offline 2>/dev/null || timeout 180 pnpm install) "
                + ">> /root/deps-selfheal.log 2>&1; break; }; "
                + "done; ";
    }

    /** 这批幂等补丁上次是在什么前提下打成功的 —— 前提没变就不必再打一遍。
     *  空串表示「拿不到前提」（例如 profile 还没建），那时一律照打，不敢跳。 */
    private String patchStampNow() {
        try {
            java.io.File nm = rootfsFile("root/.dsh/profiles/web/node_modules");
            if (!nm.isDirectory()) return "";
            return BUILTIN_ASSET_VERSION + ":" + nm.lastModified();
        } catch (Throwable t) {
            return "";
        }
    }

    /** WebUI 实际启动命令核心（看门狗重启与正常启动共用）。
     *  自动判断：源码目录存在 → cd + 依赖自愈 + node apps/cli/lib/bin.js web；
     *  否则回退全局 dsh web（预构建/目录缺失场景）。含 exec 与日志重定向。 */

    private String runCoreCommand(boolean lanReady) {
        int port = parsePort();
        // 默认端口(3080)不显式传 --port —— 彻底避免 commander 报 'argument missing'；
        // 只有用户自定义端口才追加 --port
        String opts = "";
        if (port != 3080) opts += " --port " + port;
        if (lanReady) opts += " --host 0.0.0.0" + lanTrustArgs(); // 0.0.0.0 + 信任本机所有 IP（Host 头校验放行）
        String wd = detectWorkdir();
        return
                // Node 的模块编译缓存（v22.8+ 起支持，我们是 Node 24）：把编译后的字节码落盘，
                // 二次启动省掉重新编译。dsh 启动要加载几百个模块，这一项对「点启动到 WebUI
                // 可用」的体感最直接。
                //
                // 它是 quiet optimization —— 目录不可写、node 版本不支持时会静默跳过，
                // 不影响启动，所以不需要兜底判断（要关就设 NODE_DISABLE_COMPILE_CACHE=1）。
                // 先 mkdir 是因为目录不存在会被判 FAILED 而静默放弃缓存。
                "mkdir -p /root/.cache/node-compile 2>/dev/null; "
                + "export NODE_COMPILE_CACHE=/root/.cache/node-compile; "
                + "if [ ! -f /root/.dsh/.config-fix-done ] || "
                + "[ /root/.dsh/settings.yaml -nt /root/.dsh/.config-fix-done ]; then "
                + "node /root/dsh-config-fix.js 2>/dev/null; "
                + "touch /root/.dsh/.config-fix-done 2>/dev/null; fi; "
                // 判定源码模式必须认启动入口 bin.js：RC6 模式下工作区目录也存在（只是没有源码），
                // 只认 -d 会把空工作区误判成源码树 → 启动失败
                + "if [ -f /root/" + wd + "/apps/cli/lib/bin.js ]; then cd /root/" + wd + "; "
                // pid 落盘要在**依赖自愈之前**：源码模式下 depsSelfHeal 可能跑 pnpm install
                // （90~180 秒），那段时间里如果用户点停止，没有 pid 文件就只能靠 cmdline 猜，
                // 而 pnpm 的命令行既不含 bin.js web 也不该被当成目标 —— 结果是装完照样把
                // Web 拉起来，又是一次「停不掉」。$$ 在这里写下、exec 时不变，所以一个数
                // 覆盖「启动准备 + 运行」的全程。
                + "echo $$ > " + WebProcSel.PID_WEB + " 2>/dev/null; " + depsSelfHeal()
                + "exec node --expose-internals apps/cli/lib/bin.js web" + opts + " > ~/dsh-web.log 2>&1; "
                + "else "
                + "if command -v dsh >/dev/null 2>&1 && test -f \"$(command -v dsh)\"; then "
                // RC6 模式没有源码树，但工作区目录必须存在并作为运行目录：
                // 1) 否则用户在 MT/工作区页看不到 deepseek-harness 文件夹（"下载完没有工作区"）
                // 2) agent 产物/上传文件有固定落点，备份功能才能带上
                + "mkdir -p /root/" + wd + " && cd /root/" + wd + " && "
                // 必须带 --expose-internals：dsh 的 profile-boot 会无条件创建
                // @deepseek-ai/cordis-plugin-hmr（它要靠 HMR 去监听用户 patch 文件），
                // 而那个插件第一行就检查 loader.internal，没有这个标志直接抛
                // 「--expose-internals is required for HMR service」把整个启动带崩。
                // NODE_OPTIONS 传不了这个标志（node 明确拒绝），只能作为命令行参数；
                // 而 dsh 是个 wrapper，所以先 readlink 出真正的 bin.js 再交给 node。
                + "DSH_REAL=$(readlink -f \"$(command -v dsh)\" 2>/dev/null || command -v dsh); "
                + "echo $$ > " + WebProcSel.PID_WEB + " 2>/dev/null; "
                + "exec node --expose-internals \"$DSH_REAL\" web" + opts + " > ~/dsh-web.log 2>&1; "
                + "else echo '[DSHA] 全局 dsh 不可用（悬空链接或未安装），请到分步安装页重装 ⑤ deepseek-harness'; exit 1; fi; fi";
    }

    /** 生成 --trusted-host 参数：枚举本机所有非 loopback IPv4（WiFi/热点/有线），供 LAN Host 头校验放行 */
    private String lanTrustArgs() {
        StringBuilder sb = new StringBuilder();
        try {
            java.util.Enumeration<java.net.NetworkInterface> nis = java.net.NetworkInterface.getNetworkInterfaces();
            if (nis != null) while (nis.hasMoreElements()) {
                java.net.NetworkInterface ni = nis.nextElement();
                if (ni.isLoopback() || !ni.isUp()) continue;
                java.util.Enumeration<java.net.InetAddress> addrs = ni.getInetAddresses();
                if (addrs != null) while (addrs.hasMoreElements()) {
                    java.net.InetAddress a = addrs.nextElement();
                    if (a instanceof java.net.Inet4Address && !a.isLoopbackAddress()) {
                        sb.append(" --trusted-host ").append(a.getHostAddress());
                    }
                }
            }
        } catch (Throwable ignored) {
        }
        return sb.toString();
    }

    /** 用当前配置刷新看门狗文件（启动页可见时调用，确保旧坏命令被覆盖） */
    public void ensureWatchdogFiles() {
        // 用户主动停过就别再装看门狗。否则「停止」按下去之后，只要还停在启动页，
        // 1.5 秒后 LaunchFragment 的预热线程又把看门狗写回并拉起，它失联 3 次（约 90 秒）
        // 就把 WebUI 拽回来 —— 真机反馈的「启动页的停止没有用、后台不停拉活」正是这条路。
        if (!shouldAutoStartWeb("看门狗安装")) return;
        boolean lan = appContext.getSharedPreferences("deepseekharness", android.content.Context.MODE_PRIVATE)
                .getBoolean("lan_mode", false);
        // 与 startWebCommand 一致：只有补丁真的打上（lanReady=true）才用 0.0.0.0，
        // 否则看门狗重启命令带 --host 0.0.0.0 会被官方拒绝 → 重启失败
        // 启动前把热数据迁移到公开目录（与 startWebCommand 同款钩子，
        // 否则 HarnessService 的独立启动路径会跳过这一步）
        try {
            runAssetScript("migrate-public-data.sh", "dsha-migrate-public.sh", 60_000);
        } catch (Throwable ignored) {
        }
        boolean lanReady = lan && tryEnableLanBind();
        writeWatchdogFiles(runCoreCommand(lanReady), parsePort());
    }

    /**
     * 看门狗：WebUI 崩溃/卡死（失联 3 次，约 90 秒）自动重启。
     * 写入 /root/dsh-web-restart.sh + /root/dsh-cmd.txt（重启命令，含 cd+env）。
     * 看门狗重启时读 dsh-cmd.txt（永远拿到最新命令，避免旧坏命令反复触发）。
     * 幂等：watchdog 自身已在运行则直接退出。
     */
    private void writeWatchdogFiles(String restartCmd, int port) {
        try {
            java.io.File wdDir = new java.io.File(proot.getRootfsDir(), "root");
            // 两段脚本的文本生成搬到 WatchdogScript（纯逻辑）——
            // 拼错一处的后果全是静默的（看门狗启动即退、或者以为重启了其实没动），
            // 放那里才能写断言、才能过 bash -n。
            String restart = WatchdogScript.restart(apiKeyExportLine(),
                    ShellQuote.arg(getPermissionMode()), getWorkdir(), restartCmd);
            String watchdog = WatchdogScript.watchdog(port);
            java.io.File wdScript = new java.io.File(wdDir, "dsh-watchdog.sh");
            java.io.File rstScript = new java.io.File(wdDir, "dsh-web-restart.sh");
            java.io.File cmdFile = new java.io.File(wdDir, "dsh-cmd.txt");
            try (java.io.FileOutputStream a = new java.io.FileOutputStream(wdScript);
                 java.io.FileOutputStream b = new java.io.FileOutputStream(rstScript);
                 java.io.FileOutputStream cc = new java.io.FileOutputStream(cmdFile)) {
                a.write(watchdog.getBytes(StandardCharsets.UTF_8));
                b.write(restart.getBytes(StandardCharsets.UTF_8));
                cc.write(restart.getBytes(StandardCharsets.UTF_8)); // 与 restart.sh 同内容，watchdog 读它
            }
        } catch (Exception ignored) {
        }
    }

    /**
     * 局域网放行：把 assets 里的 lan-bind-patch.sh 写入 rootfs 执行，
     * 移除 deepseek-harness CLI 对 --host 0.0.0.0 的拒绝（底层 webServer 本就支持）。
     * 幂等；返回 true 表示本次可用 0.0.0.0。
     */
    /** 内置移动端 UI 适配：把 dsh-web-mobile 的 client 产物注入 web-app 前端。
     *  原则：
     *  - 不依赖第三方插件仓库（assets 里自带完整 client.js/index.js/cordis.patch.yml）；
     *  - 注入点是「web-app 的 dist 静态目录 + cordis.patch insert」；
     *  - 幂等：已注入（/root/dsha-web-mobile-installed 标记）则跳过；
     *  - 失败绝不影响安装（catch 吞掉）。
     */
    /** 一次性迁移：把旧的内置 UI 适配插件（dsh-client-ui-mobile-adapt）下线。
     *
     *  <p>内置的移动端适配换成 {@code dsh-web-mobile}
     *  （上游 mexiaosqwq/dsh-web-mobile，MIT）—— 旧那个（Hotsteel2901/
     *  dsh-client-ui-mobile-adapt）作者长期停更。两个插件都靠 DOM/CSS 改造同一批
     *  界面元素，同时激活必然打架：抽屉/浮层出两份、事件绑定两遍。所以旧的要真正
     *  下线，不能放着不管。
     *
     *  <p>全部幂等，而且先用几个便宜的 File 判断决定要不要动手 —— 没有旧痕迹就直接
     *  返回，不进 proot（这是每次启动都会走的路径，一次 proot 调用都不该白花）。
     *  <ul>
     *    <li>profile 的 bundles / dependencies 里摘掉旧名字；</li>
     *    <li>删 node_modules 下的旧链接/副本与 .disabled 残留；</li>
     *    <li>删实体 /root/dsha-mobile-adapt、它的 marker 和日志；</li>
     *    <li><b>继承禁用状态</b>：旧插件被用户手动禁用过，新插件也建 .disabled ——
     *        换个包名就把用户关掉的东西重新打开，等于替他做决定。</li>
     *  </ul>
     */
    private void migrateLegacyMobileAdapt() {
        final String OLD = "dsh-client-ui-mobile-adapt";
        final String NEW = "dsh-web-mobile";
        try {
            java.io.File rootfs = proot.getRootfsDir();
            java.io.File oldReal = new java.io.File(rootfs, "root/dsha-mobile-adapt");
            java.io.File oldMarker = new java.io.File(rootfs, "root/dsha-mobile-adapt-installed");
            java.io.File nm = new java.io.File(rootfs, "root/.dsh/profiles/web/node_modules");
            java.io.File oldNm = new java.io.File(nm, OLD);
            java.io.File oldDisabled = new java.io.File(nm, OLD + ".disabled");
            java.io.File pf = new java.io.File(rootfs, "root/.dsh/profiles/web/package.json");
            java.io.File snap = new java.io.File(rootfs, "root/dsha-builtin.txt");

            boolean inProfile = false;
            String txt = null;
            if (pf.isFile()) {
                txt = new String(java.nio.file.Files.readAllBytes(pf.toPath()), StandardCharsets.UTF_8);
                inProfile = txt.contains(OLD);
            }
            boolean snapStale = false;
            String snapTxt = null;
            if (snap.isFile()) {
                try {
                    snapTxt = new String(java.nio.file.Files.readAllBytes(snap.toPath()),
                            StandardCharsets.UTF_8);
                    snapStale = snapTxt.contains(OLD) || !snapTxt.contains(NEW);
                } catch (Throwable ignored) {
                }
            }
            if (!inProfile && !snapStale && !oldReal.exists() && !oldMarker.exists()
                    && !oldNm.exists() && !oldDisabled.exists()) {
                return;     // 干净环境（或已经迁过）→ 零成本退出
            }

            // 1) 先取禁用状态，再删旧标记（顺序反了就丢信息）
            boolean wasDisabled = oldDisabled.exists()
                    && !(oldDisabled.isFile() && oldDisabled.length() == 0);
            if (wasDisabled) {
                java.io.File newDisabled = new java.io.File(nm, NEW + ".disabled");
                if (!newDisabled.exists()) {
                    try {
                        if (newDisabled.getParentFile() != null) {
                            //noinspection ResultOfMethodCallIgnored
                            newDisabled.getParentFile().mkdirs();
                        }
                        // 内容非空是刻意的：空标记会被 userDisabledPlugin 当成
                        // 「异常残留」清掉，那就等于没继承
                        java.nio.file.Files.write(newDisabled.toPath(),
                                ("migrated from " + OLD + "\n").getBytes(StandardCharsets.UTF_8));
                    } catch (Throwable ignored) {
                    }
                }
                android.util.Log.i("DSHA", "旧 UI 适配插件处于禁用状态 → 新插件也保持禁用");
            }

            // 2) profile 里摘名字（bundles 与 dependencies 都要摘，少一个 reconcile 会报错）
            if (inProfile && txt != null) {
                try {
                    org.json.JSONObject root = new org.json.JSONObject(txt);
                    boolean changed = false;
                    org.json.JSONObject dsh = root.optJSONObject("dsh");
                    org.json.JSONObject prof = dsh == null ? null : dsh.optJSONObject("profile");
                    org.json.JSONArray bundles = prof == null ? null : prof.optJSONArray("bundles");
                    if (bundles != null) {
                        org.json.JSONArray kept = new org.json.JSONArray();
                        for (int i = 0; i < bundles.length(); i++) {
                            String n = bundles.optString(i, "").trim();
                            if (OLD.equals(n)) {
                                changed = true;
                                continue;
                            }
                            if (!n.isEmpty()) kept.put(n);
                        }
                        if (changed) prof.put("bundles", kept);
                    }
                    org.json.JSONObject deps = root.optJSONObject("dependencies");
                    if (deps != null && deps.has(OLD)) {
                        deps.remove(OLD);
                        changed = true;
                    }
                    if (changed) {
                        java.nio.file.Files.write(pf.toPath(),
                                root.toString(2).getBytes(StandardCharsets.UTF_8));
                        android.util.Log.i("DSHA", "已从 profile 摘掉旧 UI 适配插件 " + OLD);
                    }
                } catch (Throwable e) {
                    // 解析不了就别动文件 —— 写坏 profile 会让 Web 起不来，比留个旧插件严重得多
                    android.util.Log.w("DSHA", "摘旧插件时 profile 解析失败，未改动: " + e);
                }
            }

            // 3) 删旧链接/副本/实体/marker/日志（Java 直接删，不进 proot）
            for (java.io.File f : new java.io.File[]{oldNm, oldDisabled, oldReal, oldMarker,
                    new java.io.File(rootfs, "root/dsha-mobile-adapt.log")}) {
                try {
                    purgeForPlace(f);
                } catch (Throwable ignored) {
                }
            }
            // 4) 内置插件快照换名。listPlugins 的「隐藏自带」是**精确名字匹配**
            //    （names.removeAll(builtin)），快照里没有新名的话，新内置插件会被
            //    当成用户自己装的显示在列表里。
            if (snapStale && snapTxt != null) {
                try {
                    StringBuilder out = new StringBuilder();
                    boolean hasNew = false;
                    for (String line : snapTxt.split("\n")) {
                        String t = line.trim();
                        if (t.isEmpty() || OLD.equals(t)) continue;
                        if (NEW.equals(t)) hasNew = true;
                        out.append(t).append('\n');
                    }
                    if (!hasNew) out.append(NEW).append('\n');
                    java.nio.file.Files.write(snap.toPath(),
                            out.toString().getBytes(StandardCharsets.UTF_8));
                } catch (Throwable ignored) {
                }
            }
            logActivity("内置 UI 适配插件已更换：" + OLD + " → " + NEW
                    + (wasDisabled ? "（沿用你之前的「已禁用」设置）" : "，重启 Web 生效"));
        } catch (Throwable e) {
            android.util.Log.w("DSHA", "旧 UI 适配插件迁移失败（不影响启动）: " + e);
        }
    }

    private void ensureNativeMobileNav() {
        try {
            // 换插件前先把旧的下线：两个 UI 适配同时激活会互相打架
            migrateLegacyMobileAdapt();
            final String NAME = "dsh-web-mobile";
            // 用户主动禁用（.disabled 存在）→ 只更新实体文件（assets 新版本写到
            // 实体目录，重新启用时拿到的是新版），不 touch marker / 不注册 bundle /
            // 不建链接（否则资产版本变化删 marker 后会把禁用的插件强制重新启用）。
            boolean userDisabled = userDisabledPlugin(NAME);
            java.io.File aDir = new java.io.File(proot.getRootfsDir(), "root/dsha-web-mobile");
            aDir.mkdirs();
            // 实体始终更新（幂等，秒级）
            // 整棵树一起铺，不再逐个列文件 —— 见 placeBuiltinEntity 的说明。
            boolean full = placeBuiltinEntity("dsh-web-mobile", aDir);
            if (userDisabled) {
                android.util.Log.i("DSHA", "dsh-web-mobile 已被用户禁用：仅更新实体，跳过注册/注入");
                return;
            }
            // 1) 注入脚本（保留幂等标记；手动 cp 已废弃——双通道加载会冲突导致
            //    "facade is missing"。加载统一走 registerMobileNavBundle（link: bundle）。
            //    老用户残留的手动注入文件在这里清理。
            String script =
                    "set -e; " +
                    "DST=$(find /usr/local/lib/node_modules /root -maxdepth 14 " +
                    "  \\( -path '*dsh-client-connection/lib/client' -o -path '*dsh-web-app/dist*/client' -o -path '*dsh-web-app/lib/client' \\) " +
                    "  -type d 2>/dev/null | head -1); " +
                    "if [ -z \"$DST\" ]; then " +
                    "echo 'NOT_FOUND: 未找到 web-app client 目录 '$(date) >> /root/dsha-web-mobile.log; " +
                    "echo '[DSHA] 未找到 web-app client 目录，跳过移动端适配'; exit 0; fi; " +
                    "if [ -n \"$DST\" ] && [ -f \"$DST/dsh-client-ui-mobile-adapt.js\" ]; then " +
                    "rm -f \"$DST/dsh-client-ui-mobile-adapt.js\" && echo '[DSHA] 已清理旧手动注入（改用 bundle 注册）'; fi; " +
                    "echo 'CLEANED: '$(date) >> /root/dsha-web-mobile.log; " +
                    "touch /root/dsha-web-mobile-installed && echo OK";
            java.io.File sF = new java.io.File(proot.getRootfsDir(), "root/dsha-mobile-inject.sh");
            java.nio.file.Files.write(sF.toPath(), script.getBytes(StandardCharsets.UTF_8));
            // 3) 执行注入（幂等标记存在则跳过）
            //
            // marker 不是唯一判据：离线包预置脚本曾经在实体拷贝全部静默失败的情况下
            // 照样 touch 出 marker（源里的入口在 lib/ 下，它却按根目录拷），于是
            // 「已装好」的凭证配着一个只有 package.json 的空壳，dsh 加载必然失败，
            // 而 App 因为看见 marker 永远不会来补。所以实体不完整时先把 marker 删掉。
            // 判据从「client.js 与 index.js 在且非空」升成「文件数写满」：前者放过了
             // 缺 lib/compress.js 的实体，而 index.js 一 import 它整棵 plugin tree 就崩。
            java.io.File clientJs = new java.io.File(aDir, "lib/client.js");
            java.io.File indexJs = new java.io.File(aDir, "lib/index.js");
            if (!full || !clientJs.isFile() || clientJs.length() == 0
                    || !indexJs.isFile() || indexJs.length() == 0) {
                proot.execAndRead("rm -f /root/dsha-web-mobile-installed; echo dropped");
                android.util.Log.w("DSHA", "dsh-web-mobile 实体不完整（client.js/index.js 缺失或为空）→ 已丢弃 marker 重注入");
            }
            String r = proot.execAndRead(
                    "if [ -f /root/dsha-web-mobile-installed ]; then echo ALREADY; "
                    + "else bash /root/dsha-mobile-inject.sh; fi; "
                    + "rm -f /root/dsha-mobile-inject.sh");
            // 4) 【新增】profile 注册：把移动端适配作为 web profile 的 bundle 挂上
            //    （仅当 manifest 还没包含时追加；dependencies 用 file: 指向本机目录，零网络）
            if (r != null && (r.contains("OK") || r.contains("ALREADY"))) {
                registerMobileNavBundle();
            }
        } catch (Throwable ignored) {
        }
    }

    /** profile 注册移动端适配 bundle：手写 link: 依赖 + bundles（不跑 pnpm/dsh plugin，
     *  避免 pnpm 重装破坏 profile node_modules 导致其他插件异常）。
     *  幂等：已在 bundles 则跳过。配合启动前 fix-stale-bundles.sh 自愈兜底。 */
    private void registerMobileNavBundle() {
        try {
            final String NAME = "dsh-web-mobile";
            final String REAL = "/root/dsha-web-mobile";
            java.io.File pf = new java.io.File(proot.getRootfsDir(), "root/.dsh/profiles/web/package.json");
            if (!pf.isFile()) return;
            String txt = new String(java.nio.file.Files.readAllBytes(pf.toPath()), StandardCharsets.UTF_8);
            org.json.JSONObject root = new org.json.JSONObject(txt);
            // dependencies 加 link: 指向我们注入的目录（官方认可的本地依赖语义，零网络）
            org.json.JSONObject deps = root.optJSONObject("dependencies");
            if (deps == null) { deps = new org.json.JSONObject(); root.put("dependencies", deps); }
            if (!deps.has(NAME)) deps.put(NAME, "link:" + REAL);
            // dsh.profile.bundles 追加
            org.json.JSONObject dsh = root.optJSONObject("dsh");
            org.json.JSONObject prof = dsh == null ? null : dsh.optJSONObject("profile");
            if (prof == null) {
                prof = new org.json.JSONObject();
                if (dsh == null) dsh = new org.json.JSONObject();
                dsh.put("profile", prof);
                root.put("dsh", dsh);
            }
            org.json.JSONArray bundles = prof.optJSONArray("bundles");
            if (bundles == null) { bundles = new org.json.JSONArray(); prof.put("bundles", bundles); }
            boolean has = false;
            for (int i = 0; i < bundles.length(); i++) {
                if (NAME.equals(bundles.optString(i, "").trim())) { has = true; break; }
            }
            if (!has) bundles.put(NAME);
            String s;
            try { s = root.toString(2); } catch (Throwable e) { s = root.toString(); }
            java.nio.file.Files.write(pf.toPath(), s.getBytes(StandardCharsets.UTF_8));
            // 关键：确保 node_modules 里有可解析的链接（link: 语义 = 建符号链接即可，
            // 不跑 pnpm 以免破坏 profile 依赖结构）
            java.io.File nmDir = new java.io.File(proot.getRootfsDir(),
                    "root/.dsh/profiles/web/node_modules");
            if (nmDir.getParentFile() != null) nmDir.mkdirs();
            java.io.File link = new java.io.File(nmDir, NAME);
            if (!link.exists()) {
                try {
                    java.nio.file.Files.createSymbolicLink(link.toPath(),
                            java.nio.file.Paths.get(REAL));
                } catch (Throwable ignored) {
                }
            }
        } catch (Throwable ignored) {
        }
    }

    /** 把 assets 内文本资源写入 rootfs 指定文件（目录自动建） */
    /** 确保「设备 Shell 引导」插件已注入 rootfs 并注册为 web profile bundle。
     *  让 agent 在系统提示里知道可用 /root/dsh-bin/adb-shell 干预手机。
     *  rc.8 全局 npm 模式适配（不走 packages/host，直接 file: bundle 挂载）。
     *  装到 node_modules/dsh-device-shell-guide（符号链接→实体目录），
     *  这样「已装插件」列表可见、togglePlugin 开关可生效（改名链接）。
     *  幂等：已注册跳过；失败不影响安装。 */
    /** 确保「任务完成通知」插件已注入 rootfs 并注册为 web profile bundle：
     *  监听 turn/end → 3090 桥发 App 通知（替代轮询会话文件，更准）。
     *  幂等：marker 存在跳过；失败不影响安装。 */
    /** 确保「流式悬浮条」插件已注入 rootfs 并注册为 web profile bundle：
     *  监听 session/event → 节流后经 3090 桥把 agent 正在生成的内容送到屏幕顶部
     *  （{@link OverlayController}），工具调用显示成「正在执行命令」这类人话。
     *
     *  <p>插件<b>始终注册</b>，可见性由配置页那个开关控制：它只挂一个事件监听，
     *  读不到桥 token（桌面环境 / 桥没起）或收到 DISABLED 就自己进冷却，开销可以忽略。
     *  反过来「按需安装」要等重启 Web 才生效，体验更差。
     *
     *  <p>幂等：marker 存在且语法正常则跳过；失败不影响安装。 */
    /** 把一个内置插件注册进 web profile 的 package.json。
     *
     *  <p><b>dependencies 与 dsh.profile.bundles 必须同时写</b>：dsh 的 reconcile 会把
     *  「bundles 里列了、dependencies 里解析不到」的条目当成无效项剪掉，于是下一次启动
     *  又要补、又被剪 —— 用户看到的现象是插件装了但永远不生效。历史上补过一轮只写
     *  bundles 的版本，就是这么进死循环的。
     *
     *  <p>这段逻辑原先在 ensureStatusOverlay / ensureTaskNotifier /
     *  ensureDeviceShellGuide 里各有一份逐字拷贝。三份意味着「修一处漏两处」，而内置
     *  插件安置正是本项目返工次数最多的地方，所以收成一处：<b>要改注册规则，只有这里。</b>
     *
     *  <p>profile 还没生成时（dsh 首次启动才建）直接跳过 —— 调用方都是幂等的
     *  ensureXxx，下一轮会再来。异常抛给调用方，它们统一 catch 成「可选功能失败不影响启动」。
     *
     *  @param name 插件包名（写进 bundles 与 dependencies 的键）
     *  @param real 插件实体在 rootfs 内的绝对路径（写成 {@code link:<real>}）
     */
    private void registerBuiltinInProfile(String name, String real) throws Exception {
        java.io.File pf = new java.io.File(proot.getRootfsDir(), "root/.dsh/profiles/web/package.json");
        if (!pf.isFile()) return;
        String txt = new String(java.nio.file.Files.readAllBytes(pf.toPath()), StandardCharsets.UTF_8);
        org.json.JSONObject root = new org.json.JSONObject(txt);
        org.json.JSONObject deps = root.optJSONObject("dependencies");
        if (deps == null) { deps = new org.json.JSONObject(); root.put("dependencies", deps); }
        if (!deps.has(name)) deps.put(name, "link:" + real);
        // 空安全：dsh/profile 可能不存在（全新 profile，或被 reconcile 清空）→ 逐层创建
        org.json.JSONObject dshObj = root.optJSONObject("dsh");
        if (dshObj == null) { dshObj = new org.json.JSONObject(); root.put("dsh", dshObj); }
        org.json.JSONObject profile = dshObj.optJSONObject("profile");
        if (profile == null) { profile = new org.json.JSONObject(); dshObj.put("profile", profile); }
        org.json.JSONArray bundles = profile.optJSONArray("bundles");
        if (bundles == null) { bundles = new org.json.JSONArray(); profile.put("bundles", bundles); }
        boolean found = false;
        for (int i = 0; i < bundles.length(); i++) {
            if (name.equals(bundles.optString(i, ""))) { found = true; break; }
        }
        if (!found) bundles.put(name);
        java.nio.file.Files.write(pf.toPath(), root.toString(2).getBytes(StandardCharsets.UTF_8));
    }

    private void ensureStatusOverlay() {
        try {
            final String NAME = "dsh-status-overlay";
            final String REAL = "/root/dsha-status-overlay";
            java.io.File realDir = new java.io.File(proot.getRootfsDir(), "root/dsha-status-overlay");
            java.io.File nmLink = new java.io.File(proot.getRootfsDir(),
                    "root/.dsh/profiles/web/node_modules/" + NAME);
            java.io.File marker = new java.io.File(proot.getRootfsDir(),
                    "root/dsha-status-overlay-installed");
            if (userDisabledPlugin(NAME)) {
                // 用户禁用 → 只更新实体（重新启用时拿到的是新版），不注册
                // 整棵树一起铺，不逐个列文件 —— 见 placeBuiltinEntity 的说明
                placeBuiltinEntity("status-overlay", realDir);
                return;
            }
            if (marker.exists() && nmLink.exists()) {
                // 语法自愈：热更新推来的脚本万一是坏的，删 marker 强制用 APK 内置版重注入
                String syn = proot.execAndRead(
                        "node --check /root/dsha-status-overlay/lib/index.js 2>&1 | head -2; echo SYNTAX=${PIPESTATUS[0]}");
                if (syn != null && syn.contains("SYNTAX=1")) {
                    android.util.Log.w("DSHA", "status-overlay JS 语法错误，删 marker 强制重注入");
                    //noinspection ResultOfMethodCallIgnored
                    marker.delete();
                } else {
                    return;
                }
            }
            writeAssetTo("status-overlay/package.json", new java.io.File(realDir, "package.json"));
            writeAssetTo("status-overlay/cordis.patch.yml", new java.io.File(realDir, "cordis.patch.yml"));
            writeAssetTo("status-overlay/lib/index.js", new java.io.File(realDir, "lib/index.js"));
            if (nmLink.getParentFile() != null) {
                //noinspection ResultOfMethodCallIgnored
                nmLink.getParentFile().mkdirs();
            }
            if (!nmLink.exists()) {
                try {
                    java.nio.file.Files.createSymbolicLink(nmLink.toPath(),
                            java.nio.file.Paths.get(REAL));
                } catch (Throwable linkErr) {
                    // 私有目录不一定支持符号链接（历史上栽过）→ 退回递归复制
                    java.io.File nmDir = nmLink.getParentFile();
                    if (nmDir != null) linkPlugin(NAME, REAL, nmDir);
                }
            }
            registerBuiltinInProfile(NAME, REAL);
            java.nio.file.Files.write(marker.toPath(), "1".getBytes(StandardCharsets.UTF_8));
            android.util.Log.i("DSHA", "流式悬浮条插件已注册");
        } catch (Throwable ignored) {
            // 可选功能，失败不该影响安装或启动
        }
    }

    private void ensureTaskNotifier() {
        try {
            final String NAME = "dsh-task-notifier";
            final String REAL = "/root/dsha-task-notifier";
            java.io.File realDir = new java.io.File(proot.getRootfsDir(), "root/dsha-task-notifier");
            java.io.File nmLink = new java.io.File(proot.getRootfsDir(),
                    "root/.dsh/profiles/web/node_modules/" + NAME);
            java.io.File marker = new java.io.File(proot.getRootfsDir(), "root/dsha-task-notifier-installed");
            // 用户禁用 → 仅更新实体不注册
            if (userDisabledPlugin(NAME)) {
                // 整棵树一起铺，不逐个列文件 —— 见 placeBuiltinEntity 的说明
                placeBuiltinEntity("task-notifier", realDir);
                return;
            }
            if (marker.exists() && nmLink.exists()) {
                // 语法自愈：JS 语法错误（漏 + 连接符等）→ 删 marker 强制重注入
                String syn = proot.execAndRead(
                        "node --check /root/dsha-task-notifier/lib/index.js 2>&1 | head -2; echo SYNTAX=${PIPESTATUS[0]}");
                if (syn != null && syn.contains("SYNTAX=1")) {
                    android.util.Log.w("DSHA", "task-notifier JS 语法错误，删 marker 强制重注入");
                    marker.delete();
                } else {
                    return; // 已注入且语法正常
                }
            }
            // 1) 注入实体
            writeAssetTo("task-notifier/package.json", new java.io.File(realDir, "package.json"));
            writeAssetTo("task-notifier/cordis.patch.yml", new java.io.File(realDir, "cordis.patch.yml"));
            writeAssetTo("task-notifier/lib/index.js", new java.io.File(realDir, "lib/index.js"));
            // 2) node_modules 符号链接
            if (nmLink.getParentFile() != null) nmLink.getParentFile().mkdirs();
            if (!nmLink.exists()) {
                java.nio.file.Files.createSymbolicLink(nmLink.toPath(),
                        java.nio.file.Paths.get(REAL));
            }
            // 3) 注册 profile（dependencies + bundles）
            registerBuiltinInProfile(NAME, REAL);
            java.nio.file.Files.write(marker.toPath(), "1".getBytes(StandardCharsets.UTF_8));
            android.util.Log.i("DSHA", "任务通知插件已注册");
        } catch (Throwable ignored) {
        }
    }

    /** 把内置插件修回可用状态，返回这次真的补了几个（0 = 本来就好）。
     *
     *  为什么需要一个能被 UI 随时调用的入口：注册丢失最常见的成因是 dsh 首次启动时
     *  initProfile() 用官方 bundles 重建了 package.json，把我们写进去的 link: 声明
     *  整段覆盖。此前的补回只挂在「Web 启动成功后」——Web 起不来、或用户没重启 Web，
     *  就永远轮不到，自检报了错也只能让人去终端手改。这不叫修好。
     *  现在插件页每次打开都会静默跑一次。 */
    private final java.util.concurrent.atomic.AtomicLong lastBuiltinRepairAt =
            new java.util.concurrent.atomic.AtomicLong(0);

    public int repairBuiltinPlugins() {
        return repairBuiltinPlugins(false);
    }

    /** force=true 时忽略节流 —— 用户主动跑自检就是「现在就给我修」，不该被时间窗挡住。 */
    public int repairBuiltinPlugins(boolean force) {
        int fixed = 0;
        try {
            if (!proot.isInstalled()) return 0;
            // 节流：插件页每次可见都会调一次（切标签、从后台回来都算），
            // 而这里要读写 rootfs 里的 package.json —— 频繁跑纯属浪费 IO。
            // 注册丢失不是高频事件，20 秒的窗口足够。
            long now = System.currentTimeMillis();
            if (force) {
                lastBuiltinRepairAt.set(now);
            } else {
                // volatile 上的「先读再写」在并发下会让两个线程一起通过节流，
                // 各自把 rootfs 里的 package.json 读一遍写一遍。CAS 只让一个进来。
                long prev = lastBuiltinRepairAt.get();
                if (now - prev < 20_000 || !lastBuiltinRepairAt.compareAndSet(prev, now)) return 0;
            }
            java.io.File pkg = new java.io.File(proot.getRootfsDir(),
                    "root/.dsh/profiles/web/package.json");
            if (!pkg.isFile()) return 0;   // profile 还没生成，启动一次 Web 再说
            String[] names = {"dsh-device-shell-guide", "dsh-web-mobile",
                    "dsh-task-notifier"};
            boolean[] before = new boolean[names.length];
            for (int i = 0; i < names.length; i++) {
                before[i] = guideRegistered(names[i]);
            }
            ensureDeviceShellGuide();
            ensureTaskNotifier();
            ensureStatusOverlay();
            ensureBuiltinBundles();
            StringBuilder diag = new StringBuilder();
            for (int i = 0; i < names.length; i++) {
                boolean after = guideRegistered(names[i]);
                if (!before[i] && after) fixed++;
                diag.append(names[i]).append(before[i] ? "=已注册" : (after ? "=修好" : "=仍未注册"))
                        .append('\n');
            }
            // 把每个插件的前后状态落盘：用户看不到 logcat，而「修了但没成功」和
            // 「压根没跑到这里」在界面上是同一个样子（插件还是不见）。
            // 有这份记录，自检就能告诉用户卡在哪一步，而不是让人反复点插件页。
            try {
                java.io.File pkgf = new java.io.File(proot.getRootfsDir(),
                        "root/.dsh/profiles/web/package.json");
                String pkgTxt = pkgf.isFile() ? new String(java.nio.file.Files.readAllBytes(
                        pkgf.toPath()), StandardCharsets.UTF_8) : "(package.json 不存在)";
                String bundlesLine = "";
                int bi = pkgTxt.indexOf("\"bundles\"");
                if (bi >= 0) {
                    String seg = pkgTxt.substring(bi, Math.min(pkgTxt.length(), bi + 200));
                    bundlesLine = seg.replaceAll("\\s+", " ");
                }
                String depsLine = "";
                int di = pkgTxt.indexOf("\"dependencies\"");
                if (di >= 0) {
                    String seg = pkgTxt.substring(di, Math.min(pkgTxt.length(), di + 240));
                    depsLine = seg.replaceAll("\\s+", " ");
                }
                java.io.File logf = new java.io.File(proot.getRootfsDir(),
                        "root/.dsh/repair-builtin.log");
                String rec = "== " + new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss",
                        java.util.Locale.US).format(new java.util.Date()) + " 修回 " + fixed
                        + " 个 ==\n" + diag + "写入后 bundles: " + bundlesLine + "\n\n";
                java.nio.file.Files.write(logf.toPath(), rec.getBytes(StandardCharsets.UTF_8),
                        java.nio.file.StandardOpenOption.CREATE,
                        java.nio.file.StandardOpenOption.APPEND);
            } catch (Throwable e) {
                android.util.Log.w("DSHA", "写 repair-builtin.log 失败: " + e);
            }
            android.util.Log.w("DSHA", "内置插件修复结果 fixed=" + fixed + "\n" + diag);
            logActivity(fixed > 0
                    ? ("修好了 " + fixed + " 个内置插件的注册（重启 Web 生效）")
                    : ("内置插件检查完毕，本次没有需要修的（或修不上，详见 repair-builtin.log）"));
        } catch (Throwable e) {
            android.util.Log.w("DSHA", "修内置插件失败: " + e);
        }
        return fixed;
    }

    /** 用户是否**真的**禁用了这个内置插件。
     *
     *  .disabled 有两种形态，混在一起就是 bug 源头：
     *    目录 / 非空 = 真禁用（禁用时把实体 mv 过去了）→ 必须尊重
     *    空文件      = 异常残留（禁用时实体已丢失，代码只 touch 了个占位）
     *  空占位不清掉的话，所有补回逻辑都会永久跳过 —— 用户重开 App、重跑步骤⑥、
     *  甚至覆盖安装都没用，因为标记活在 rootfs 里。这里统一处理，
     *  避免各处 ensureXxx 各写一遍判断又各漏一处。 */
    private boolean userDisabledPlugin(String name) {
        java.io.File mark = new java.io.File(proot.getRootfsDir(),
                "root/.dsh/profiles/web/node_modules/" + name + ".disabled");
        if (!mark.exists()) return false;
        if (mark.isFile() && mark.length() == 0) {
            //noinspection ResultOfMethodCallIgnored
            mark.delete();
            android.util.Log.w("DSHA", "清掉 " + name + " 的空禁用标记（异常残留，不是用户禁用）");
            return false;
        }
        return true;
    }

    private void ensureDeviceShellGuide() {
        try {
            final String NAME = "dsh-device-shell-guide";
            final String REAL = "/root/dsha-device-shell-guide";
            java.io.File realDir = new java.io.File(proot.getRootfsDir(), "root/dsha-device-shell-guide");
            java.io.File nmLink = new java.io.File(proot.getRootfsDir(),
                    "root/.dsh/profiles/web/node_modules/" + NAME);
            // 用户主动禁用（开关 → .disabled）：尊重用户，不再自动补回！
            // （否则启动时 ensureDeviceShellGuide 发现"注册缺失"会把禁用覆盖掉，
            //   表现就是"内置插件不能禁用"）
            if (userDisabledPlugin(NAME)) {
                // 仅更新实体文件（assets 新版本写到实体目录，重新启用时拿到新版），
                // 不 touch marker / 不注册 / 不建链接
                // 整棵树一起铺，不逐个列文件 —— 见 placeBuiltinEntity 的说明
                placeBuiltinEntity("device-shell-guide", realDir);
                android.util.Log.i("DSHA", "device-shell-guide 已被用户禁用：仅更新实体，跳过注册");
                return;
            }
            java.io.File marker = new java.io.File(proot.getRootfsDir(), "root/dsha-device-shell-guide-installed");
            // 语法自愈：marker 存在但 JS 语法错误（如漏 + 连接符 → Unexpected string 崩溃）
            // → 删 marker 强制重注入修复版（不依赖版本 bump）
            if (marker.exists()) {
                String syn = proot.execAndRead(
                        "node --check /root/dsha-device-shell-guide/lib/index.js 2>&1 | head -2; echo SYNTAX=${PIPESTATUS[0]}");
                if (syn != null && syn.contains("SYNTAX=1")) {
                    android.util.Log.w("DSHA", "device-shell-guide JS 语法错误，删 marker 强制重注入");
                    marker.delete();
                }
            }
            // 版本自愈：marker 存在但插件版本旧（缺 cordis.entry 等修复）→ 删除 marker 强制重注入
            if (marker.exists()) {
                String curVer = "";
                try {
                    java.io.File pf2 = new java.io.File(realDir, "package.json");
                    if (pf2.isFile()) {
                        curVer = new org.json.JSONObject(
                                new String(java.nio.file.Files.readAllBytes(pf2.toPath()), StandardCharsets.UTF_8))
                                .optString("version", "");
                    }
                } catch (Throwable ignored) {
                }
                // 这里以前写死 "0.1.5"，插件版本一升就永远走「删 marker 重做」分支。
                // 改成对账 assets 里的真实版本。
                if (!builtinGuideVersion().equals(curVer)) {
                    //noinspection ResultOfMethodCallIgnored
                    marker.delete();
                } else {
                    // 版本对但可能之前注册失败（NPE 旧版）：校验注册是否真生效，
                    // 没生效（bundles 缺/链接缺）→ 删 marker 重做
                    boolean registered = false;
                    try {
                        java.io.File pfV = new java.io.File(proot.getRootfsDir(), "root/.dsh/profiles/web/package.json");
                        if (pfV.isFile()) {
                            String tv = new String(java.nio.file.Files.readAllBytes(pfV.toPath()), StandardCharsets.UTF_8);
                            org.json.JSONObject rv = new org.json.JSONObject(tv);
                            org.json.JSONArray bs = rv.optJSONObject("dsh") == null ? null
                                    : rv.optJSONObject("dsh").optJSONObject("profile") == null ? null
                                    : rv.optJSONObject("dsh").optJSONObject("profile").optJSONArray("bundles");
                            if (bs != null) {
                                for (int i = 0; i < bs.length(); i++) {
                                    if (NAME.equals(bs.optString(i, "").trim())) { registered = true; break; }
                                }
                            }
                        }
                    } catch (Throwable ignored) {
                    }
                    if (registered && nmLink.exists()) {
                        return; // 真的注册好了
                    }
                    //noinspection ResultOfMethodCallIgnored
                    marker.delete(); // 注册缺失 → 重做
                }
            }
            // 1) 注入插件包实体（assets 三件套）
            writeAssetTo("device-shell-guide/package.json", new java.io.File(realDir, "package.json"));
            writeAssetTo("device-shell-guide/cordis.patch.yml", new java.io.File(realDir, "cordis.patch.yml"));
            writeAssetTo("device-shell-guide/lib/index.js", new java.io.File(realDir, "lib/index.js"));
            // 1.5) 清理旧痕迹（旧版 file: 依赖/手建链接会干扰 dsh plugin add）：
            //      移除旧依赖声明 + 删旧符号链接，让 dsh plugin 走干净状态
            try {
                java.io.File pf0 = new java.io.File(proot.getRootfsDir(), "root/.dsh/profiles/web/package.json");
                if (pf0.isFile()) {
                    String t0 = new String(java.nio.file.Files.readAllBytes(pf0.toPath()), StandardCharsets.UTF_8);
                    org.json.JSONObject r0 = new org.json.JSONObject(t0);
                    org.json.JSONObject d0 = r0.optJSONObject("dependencies");
                    if (d0 != null && d0.has(NAME)) d0.remove(NAME);
                    java.nio.file.Files.write(pf0.toPath(), r0.toString(2).getBytes(StandardCharsets.UTF_8));
                }
            } catch (Throwable ignored) {
            }
            try {
                if (nmLink.exists()) nmLink.delete();
            } catch (Throwable ignored) {
            }
            // 2) node_modules 符号链接 → 实体目录（togglePlugin 靠改链接名开关）
            if (nmLink.getParentFile() != null) nmLink.getParentFile().mkdirs();
            if (!nmLink.exists()) {
                java.nio.file.Files.createSymbolicLink(nmLink.toPath(),
                        java.nio.file.Paths.get(REAL));
            }
            // 3) 注册到 web profile：手写 link: 依赖 + bundles（不跑 pnpm/dsh plugin，
            //    避免重装破坏 profile node_modules 导致其他插件异常；配合启动前
            //    fix-stale-bundles.sh 自愈兜底）
            {
                registerBuiltinInProfile(NAME, REAL);
                // 确保 node_modules 有可解析链接（link: 语义 = 符号链接）
                if (!nmLink.exists()) {
                    try {
                        java.nio.file.Files.createSymbolicLink(nmLink.toPath(),
                                java.nio.file.Paths.get(REAL));
                    } catch (Throwable ignored) {
                    }
                }
            }
            // 只有「真的注册进 bundles 且链接在」才写 marker。
            // 以前无条件写：步骤⑥往往跑在 dsh 首次启动之前，那时
            // /root/.dsh/profiles/web/package.json 还不存在 → 注册段被 if 跳过 →
            // marker 却已落地 → 之后永远认为装好了，agent 一直拿不到设备提示词。
            if (!guideRegistered(NAME)) {
                android.util.Log.w("DSHA", "device-shell-guide 实体已就位，但 profile 尚未生成："
                        + "本次不写 marker，等 profile 出现后由 ensureBuiltinBundles/下次启动补注册");
                return;
            }
            java.nio.file.Files.write(marker.toPath(), "1".getBytes(StandardCharsets.UTF_8));
            android.util.Log.i("DSHA", "设备 Shell 引导插件已注册（link: 依赖 + 符号链接）");
            // 双保险：home 级 cordis.patch.yml 覆盖【官方极简】的 bash 工具描述，
            // 让极简模式下 agent 也能看到 ADB 提示（和本插件联动的开关控制）
            try {
                java.io.File hp = new java.io.File(proot.getRootfsDir(), "root/.dsh/cordis.patch.yml");
                String hpText = hp.isFile()
                        ? new String(java.nio.file.Files.readAllBytes(hp.toPath()), StandardCharsets.UTF_8) : "";
                if (!hpText.contains("dsha-device-guide-bash")) {
                    String patchBlock =
                            "\n# DSHA device guide (dsha-device-guide-bash) - 官方极简模式 bash 工具描述注入\n"
                            + "- update:\n"
                            + "  - id: persistent-bash\n"
                            + "    name: '@deepseek-ai/dsh-tool-bash-persistent'\n"
                            + "    config:\n"
                            + "      description: |+\n"
                            + "        Run commands in a bash shell\n"
                            + "        * 设备操作：/root/dsh-bin/adb-shell \"命令\"（唯一可用通道，uid=2000，已配对）\n"
                            + "        * 不要用裸 adb（守卫脚本，会失败）；Shizuku 桥备用 curl -s \"http://127.0.0.1:3090/exec?cmd=...&token=$(cat /root/.dsh/.bridge_token)\"（漏 token 一律 UNAUTHORIZED）\n"
                            + "        * 与用户交流请用中文回复\n";
                    if (safeAppendYamlBlock(hp, hpText, patchBlock)) {
                        android.util.Log.i("DSHA", "home patch 已注入官方极简 bash 描述");
                    }
                }
            } catch (Throwable ignored) {
            }
            // 清理旧版 dsha-minimal 独立预设（已合并到本插件）
            try {
                java.io.File oldPreset = new java.io.File(proot.getRootfsDir(),
                        "root/.dsh/.agent-presets/dsha-minimal");
                if (oldPreset.exists()) {
                    deleteRecursively(oldPreset);
                    android.util.Log.i("DSHA", "已清理旧版 dsha-minimal 预设");
                }
            } catch (Throwable ignored) {
            }
        } catch (Throwable ignored) {
        }
    }

    /** 结构安全地往 cordis.patch.yml 追加一段 block（议题 #36 Bug 1 的修复）。
     *
     *  原来是 `text += block` 然后直接写回。当现有文件内容是 flow-style 的
     *  空容器时（`[]` 或 `{}`，dsh 与某些第三方安装脚本都会写成这样），
     *  拼接结果是：
     *
     *      []
     *      - insert:
     *          - id: ...
     *
     *  `[]` 本身已经是一个**完整的 YAML 文档**，后面再接块序列属于非法语法，
     *  Cordis 加载时直接抛 YAMLException（end of the stream or a document
     *  separator is expected），**Web 完全起不来**。
     *  用户在 #36 里就是这样被我们自己的修复机制打死的。
     *
     *  现在分三层防：
     *   ① 空容器字面量（[] / {} / 纯空白）→ 用新块**替换**而不是追加；
     *   ② 追加前确保以换行结尾（原来少一个 \n 就会把 block 接到最后一行尾部）；
     *   ③ 写之前先备份 .bak，写之后用容器里的 python3 做一次 YAML 解析校验，
     *      解析失败就回滚 —— 宁可这次注入不生效，也不能让 Web 起不来。
     *
     *  @param existing 调用方已经读好的现有内容（避免重复读盘）
     *  @return 是否真的写入成功 */
    private boolean safeAppendYamlBlock(java.io.File f, String existing, String block) {
        try {
            String cur = existing == null ? "" : existing;
            String trimmed = cur.trim();
            String merged;
            if (trimmed.isEmpty() || "[]".equals(trimmed) || "{}".equals(trimmed)
                    || "null".equals(trimmed) || "~".equals(trimmed)) {
                // ① 空容器：直接用新块，别在 [] 后面接块序列
                merged = block.startsWith("\n") ? block.substring(1) : block;
            } else {
                // ② 保证换行边界
                merged = cur.endsWith("\n") ? cur : cur + "\n";
                merged += block.startsWith("\n") ? block.substring(1) : block;
            }
            // ③ 备份 + 写 + 校验 + 失败回滚
            java.io.File bak = new java.io.File(f.getPath() + ".bak");
            if (f.isFile()) {
                try {
                    java.nio.file.Files.copy(f.toPath(), bak.toPath(),
                            java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                } catch (Throwable ignored) {
                }
            }
            if (f.getParentFile() != null) f.getParentFile().mkdirs();
            java.nio.file.Files.write(f.toPath(), merged.getBytes(StandardCharsets.UTF_8));
            if (!yamlParses(f)) {
                // 回滚：有备份就还原，没有就删掉（宁可没有这个文件，也不留个坏的）
                if (bak.isFile()) {
                    try {
                        java.nio.file.Files.copy(bak.toPath(), f.toPath(),
                                java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                    } catch (Throwable ignored) {
                    }
                } else {
                    f.delete();
                }
                logActivity("patch.yml 注入后语法校验不通过，已回滚（Web 不受影响）");
                return false;
            }
            return true;
        } catch (Throwable e) {
            android.util.Log.w("DSHA", "safeAppendYamlBlock 失败: " + e);
            return false;
        }
    }

    /** 用容器内的 python3 校验一个 YAML 文件能否解析。
     *  没有 pyyaml 时退回「只要不是明显的 [] 后接块序列就算过」——
     *  校验器缺失不该让功能不可用，但也不能假装校验过了。 */
    private boolean yamlParses(java.io.File f) {
        try {
            String rel = f.getAbsolutePath();
            java.io.File root = proot.getRootfsDir();
            if (root != null && rel.startsWith(root.getAbsolutePath())) {
                rel = rel.substring(root.getAbsolutePath().length());
            }
            String out = proot.execAndRead(
                    "python3 - <<'EOF' 2>&1\n"
                    + "import sys\n"
                    + "p = " + quoteForPython(rel) + "\n"
                    + "try:\n"
                    + "    import yaml\n"
                    + "except ImportError:\n"
                    + "    print('NOYAML'); sys.exit(0)\n"
                    + "try:\n"
                    + "    list(yaml.safe_load_all(open(p, encoding='utf-8')))\n"
                    + "    print('OK')\n"
                    + "except Exception as e:\n"
                    + "    print('BAD:' + str(e)[:200])\n"
                    + "EOF\n", 20_000);
            if (out == null) return true;              // 跑不起来就别拦
            if (out.contains("BAD:")) {
                android.util.Log.w("DSHA", "YAML 校验失败: " + out.trim());
                return false;
            }
            return true;
        } catch (Throwable e) {
            return true;
        }
    }

    private static String quoteForPython(String s) {
        return "'" + s.replace("\\", "\\\\").replace("'", "\\'") + "'";
    }

    private void writeAssetTo(String assetName, java.io.File dst) {
        try {
            String s = readAsset(assetName);
            if (s == null || s.isEmpty()) return;
            if (dst.getParentFile() != null) dst.getParentFile().mkdirs();
            java.nio.file.Files.write(dst.toPath(), s.getBytes(StandardCharsets.UTF_8));
        } catch (Throwable ignored) {
        }
    }

    private boolean tryEnableLanBind() {
        // 缓存只在进程内有效：dsh 重装⑤/文件被覆盖后补丁可能丢失，
        // 不能永久信任 lanBindReady —— 每次调用重新校验补丁是否还在（幂等）。
        // 优化：上次成功且文件仍带 dsha-lan 标记 → 快速返回 true（秒级）。
        if (lanBindReady) {
            try {
                // 快速校验：补丁文件是否仍被改过（找 startup.js 带 dsha-lan 标记）
                String check = proot.execAndRead(
                        "grep -rl 'dsha-lan' /usr/local/lib/node_modules/@deepseek-ai/dsh/node_modules/@deepseek-ai/dsh-web-app/lib/startup.js "
                        + "2>/dev/null | head -1 || grep -rl 'dsha-lan' "
                        + "/usr/local/lib/node_modules/@deepseek-ai/dsh-web-app/lib/startup.js 2>/dev/null | head -1");
                if (check != null && !check.trim().isEmpty()) return true;
                // 补丁丢了 → 重置缓存，重新打
                lanBindReady = false;
            } catch (Throwable ignored) {
                return lanBindReady; // 校验失败保守放行
            }
        }
        try {
            String script = readAsset("lan-bind-patch.sh");
            if (script.isEmpty()) return false;
            java.io.File f = new java.io.File(proot.getRootfsDir(), "root/dsha-lan-patch.sh");
            f.getParentFile().mkdirs();
            try (java.io.FileOutputStream fo = new java.io.FileOutputStream(f)) {
                fo.write(script.getBytes(StandardCharsets.UTF_8));
            }
            String r = proot.execAndRead("bash /root/dsha-lan-patch.sh; rm -f /root/dsha-lan-patch.sh");
            lanBindReady = r != null && (r.contains("LAN_PATCHED") || r.contains("LAN_ALREADY"));
            return lanBindReady;
        } catch (Throwable e) {
            return false;
        }
    }

    /** 检测本机局域网 IPv4 地址（免权限，NetworkInterface 枚举） */
    public static String getLanAddress() {
        try {
            java.util.Enumeration<java.net.NetworkInterface> nis = java.net.NetworkInterface.getNetworkInterfaces();
            while (nis != null && nis.hasMoreElements()) {
                java.net.NetworkInterface ni = nis.nextElement();
                if (!ni.isUp() || ni.isLoopback()) continue;
                java.util.Enumeration<java.net.InetAddress> as = ni.getInetAddresses();
                while (as.hasMoreElements()) {
                    java.net.InetAddress a = as.nextElement();
                    if (a instanceof java.net.Inet4Address && !a.isLoopbackAddress()) {
                        String ip = a.getHostAddress();
                        if (ip != null && (ip.startsWith("192.168.") || ip.startsWith("10.") || ip.startsWith("172."))) {
                            return ip;
                        }
                    }
                }
            }
        } catch (Exception ignored) {
        }
        return null;
    }

    /**
     * 停 WebUI 的命令。
     *
     * <p><b>为什么不只用 {@code pkill -f}</b>：命令行模式会随 dsh 的启动方式变 ——
     * 源码模式是 {@code node … apps/cli/lib/bin.js web}，预构建模式是包装脚本再转
     * {@code node …/dsh-cli/lib/bin.js}，后面还跟着一串会变的 opts。模式对不上就一个都杀不掉，
     * 而用户看到的只是「点了停止没反应」。
     *
     * <p>改成遍历 {@code /proc} 自己认：只认命令行里带 {@code bin.js web} / {@code dsh web} /
     * dsh 自家包名的进程，并<b>明确排除 proot 自己</b> —— proot 不隔离 PID，容器里能看到宿主
     * 全部进程，杀到 proot 就等于把 App 一起带走（点重启后约 10 秒闪退、通知栏一起消失，
     * 这个坑踩过）。
     *
     * <p>末尾回报 {@code STOP_LEFT}（还剩几个）与 {@code STOP_PORT}（3080 还在不在监听），
     * 让调用方能<b>眼见为实</b>，而不是无论如何都显示「已停止」。
     */
    // 进程判据与哨兵的唯一定义搬到了 WebProcSel（纯逻辑、带断言）——
    // 停止这条路的病根一直在判据上，收成一个能写断言的类才挡得住「又改错一处」。

    /**
     * 停止后的「复活侦测」：端口又活了的时候，问一句<b>是谁拉起来的</b>。
     *
     * <p>光知道「还剩 N 个进程」定位不了复活 —— 复活的是<b>新</b>进程，要看它的父进程。
     * 所以这里连 PPid 与父进程 cmdline 一起打，一次真机操作就能指认拉起者
     * （看门狗？重启脚本？还是 App 自己某条路径）。
     */
    private String reviveDiagCommand(int port) {
        return WebProcSel.pidsPort(port) + WebProcSel.pidsFile()
            + "echo REVIVE_BEGIN; "
            + "echo \"哨兵: $([ -f " + WebProcSel.STOP_SENTINEL + " ] && echo 在 || echo '不在（被谁删了）')\"; "
            + "echo \"pid文件: web=$(cat " + WebProcSel.PID_WEB + " 2>/dev/null)"
            +   " 看门狗=$(cat " + WebProcSel.PID_WATCHDOG + " 2>/dev/null)\"; "
            + "for p in $({ pids_file; pids_port; } | sort -u); do "
            +   "c=$(tr '\\0' ' ' < /proc/$p/cmdline 2>/dev/null | cut -c1-100); "
            +   "pp=$(awk '/^PPid:/{print $2}' /proc/$p/status 2>/dev/null); "
            +   "pc=$(tr '\\0' ' ' < /proc/$pp/cmdline 2>/dev/null | cut -c1-80); "
            +   "echo \"活着 $p | $c\"; "
            +   "echo \"  父 $pp | $pc\"; "
            + "done; "
            + "tail -3 /root/dsh-watchdog.log 2>/dev/null | sed 's/^/  看门狗日志: /'; "
            + "echo REVIVE_END";
    }

    private String stopWebCommand() {
        int port = parsePort();
        // 停止时连看门狗一起收掉，否则它立刻把 WebUI 拉回来
        return
            // ① 先挂闸再动手：哨兵一落地，看门狗与它写的重启脚本下一轮就自己退出。
            //    这一条不依赖「杀得准」，是「秒复活」的正面解法。
            "touch " + WebProcSel.STOP_SENTINEL + " 2>/dev/null; "
            + WebProcSel.pidsDsh(true)
            + WebProcSel.pidsPort(port)
            + WebProcSel.pidsFile()
            // ② 三份判据取并集，从可靠到勉强：
            //    pid 文件（启动时自己写的，最准）→ cmdline 长相（看门狗这类不占端口的靠它）
            //    → 端口反查（/proc/net 在 Android 10+ 基本读不到，能用就用）。
            //    去重是为了 STOP_LEFT 数得准。
            + "all_pids() { { pids_file; pids_dsh; pids_port; } | sort -u; }; "
            // 先 SIGTERM：让 dsh 优雅 flush SQLite。直接 -9 会把会话写坏
            // （用户报过 SessionPersistenceCorruptionError）。看门狗也在这一批里，
            // 它先死掉就不会把 WebUI 又拉起来。
            + "for p in $(all_pids); do kill -TERM \"$p\" 2>/dev/null; done; "
            + "sleep 3; "
            + "for p in $(all_pids); do kill -9 \"$p\" 2>/dev/null; done; "
            + "sleep 1; "
            // 眼见为实：还剩几个进程、Web 端口还在不在监听。
            // 端口那项要区分「查了，是干净的」和「工具都没有，查不了」——
            // 一律回 0 就又是一个「兜底把失败藏起来」的写法（这轮已经栽过两次）。
            // 端口号必须用实际配置值：写死 3080 的话，改过端口的用户这项永远是 0（假干净）。
            + "echo STOP_LEFT=$(all_pids | wc -l | tr -d ' '); "
            + "if command -v ss >/dev/null 2>&1; then "
            +   "echo STOP_PORT=$(ss -ltn 2>/dev/null | grep -c ':" + port + "'); "
            + "elif command -v netstat >/dev/null 2>&1; then "
            +   "echo STOP_PORT=$(netstat -ltn 2>/dev/null | grep -c ':" + port + "'); "
            + "else echo STOP_PORT=$(pids_port | wc -l | tr -d ' '); fi; "
            + "echo stopped; "
            // ── 诊断：把「所有含 node 或 dsh 的进程」连 cmdline 与父进程一起列出来 ──
            // 停止已经改过两版还是不行，就不该再猜模式了：把设备上的地面真相打出来，
            // 用户点一次停止就能把这段发回来。**PPid 与父进程 cmdline 是关键** ——
            // 「秒复活」问的不是「有谁活着」而是「谁把它拉起来的」，只有父进程能回答。
            // 只列前 100 字符 × 最多 20 条，避免灌满日志。
            + "echo STOP_DIAG_BEGIN; "
            + "for d in /proc/[0-9]*; do "
            +   "c=$(tr '\\0' ' ' < $d/cmdline 2>/dev/null) || continue; "
            +   "case \"$c\" in *pids_dsh*|*pids_port*) continue ;; esac; "
            +   "case \"$c\" in *node*|*dsh*) "
            +     "pp=$(awk '/^PPid:/{print $2}' $d/status 2>/dev/null); "
            +     "pc=$(tr '\\0' ' ' < /proc/$pp/cmdline 2>/dev/null | cut -c1-60); "
            +     "echo \"${d#/proc/} ppid=$pp | $(printf '%s' \"$c\" | cut -c1-100)\"; "
            +     "echo \"    父: $pc\" ;; "
            +   "esac; "
            + "done | head -40; "
            + "echo STOP_DIAG_END";
    }

    /** 从 {@code KEY=数字} 形式的输出里取值；取不到（含 {@code unknown}）给 -1。 */
    private static int parseKvInt(String out, String key) {
        if (out == null) return -1;
        java.util.regex.Matcher m = java.util.regex.Pattern
                .compile(java.util.regex.Pattern.quote(key) + "=(\\d+)").matcher(out);
        return m.find() ? Integer.parseInt(m.group(1)) : -1;
    }

    /** 取两个标记之间的内容（诊断段用）；找不到返回 null。 */
    private static String sliceBetween(String out, String begin, String end) {
        if (out == null) return null;
        int i = out.indexOf(begin);
        if (i < 0) return null;
        int j = out.indexOf(end, i + begin.length());
        return j < 0 ? out.substring(i + begin.length())
                : out.substring(i + begin.length(), j);
    }

    private String statusCommand() {
        return "curl -s -o /dev/null -w '%{http_code}' http://127.0.0.1:" + getPort() + "/ 2>/dev/null || echo 000";
    }

    // ================= Termux 模式 =================
    public boolean isTermuxInstalled() { return TermuxBridge.isInstalled(appContext); }
    public void openTermuxInstall() { TermuxBridge.openInstall(appContext); }

    private String buildTermuxInstallScript() {
        String s = readAsset("install-termux.sh");
        return s.replace("@@API_KEY@@", effectiveApiKey())
                .replace("@@PERMISSION_MODE@@", getPermissionMode());
    }

    /** 通过 Termux 安装 deepseek-harness */
    public void installViaTermux() {
        setProgress("提交安装任务到 Termux", 5);
        try {
            TermuxBridge.runScript(appContext, buildTermuxInstallScript(), null);
            setState("", 30, "已提交到 Termux 执行，请切到 Termux 查看进度", "", false);
        } catch (Throwable e) {
            setState("", 0, "", errMsg("提交失败：", e), false);
        }
    }

    private String startWebTermuxCommand() {
            // key 嵌在双引号里：转义 \\ 和 \"（防特殊字符破坏 Termux 命令）
            String k = effectiveApiKey().replace("\\", "\\\\").replace("\"", "\\\"");
            String pm = getPermissionMode(); // 白名单枚举，仍补引号
            StringBuilder sb = new StringBuilder();
            sb.append("export PATH=$HOME/dsh-bin:$PATH && ")
              .append("cd ~/").append(getWorkdir()).append(" && ")
              .append("export DEEPSEEK_API_KEY=\"").append(k).append("\" && ")
              .append("export DSH_PERMISSION_MODE=\"").append(pm).append("\" && ")
              .append("nohup node apps/cli/lib/bin.js web > ~/dsh-web.log 2>&1 & echo started");
            return sb.toString();
        }

    /** 通过 Termux 启动 Web UI */
    public void startWebViaTermux() {
        setProgress("正在启动 Web UI", 0);
        try {
            TermuxBridge.runScript(appContext, startWebTermuxCommand(), null);
            setState("", 100, "已提交启动，稍候在「启动」页打开预览", "", false);
        } catch (Throwable e) {
            setState("", 0, "", errMsg("启动失败：", e), false);
        }
    }

    public void stopWebViaTermux() {
        try {
            // 同样不能用 pkill -f：脚本文本里带着模式串，会把执行它的 shell 一起杀掉
            TermuxBridge.runScript(appContext,
                    WebProcSel.pidsDsh(true)
                            + "for p in $(pids_dsh); do kill -9 \"$p\" 2>/dev/null; done; "
                            + "echo stopped", null);
        } catch (Throwable ignored) {
        }
    }

    // ================= 启动 / 停止 =================
    // ================= 版本与自愈常量 =================
    // 注意：GUARD_VERSION 必须与 assets/rootfs-confirm-install.sh 末尾写入的
    // /root/dsh-bin/.version 数字一致！曾出现 8 vs 9 不匹配 → 每次启动都强制
    // rm -rf 重装守卫（幂等但白干 + 可能打断进行中的命令）。
    private static final String GUARD_VERSION = "11";
    /** 步骤⑥整体版本号：内置插件/补丁/极简 preset 任一变更时 +1，
     *  启动时对比 rootfs 标记（step6.version），不符则自动重跑⑥（防"改了不生效"）。
     *  由 installGuard 末尾的 runStep 写入（先删 marker 后写版本 → 中途失败
     *  版本未写，下次启动版本不一致仍会重跑，自愈闭环不中断）。 */
    private static final String STEP6_VERSION = "4";
    /** 内置插件资产版本：dsh-web-mobile / device-shell-guide 的 client.js 等
     *  资产内容变更时 +1（marker 存在会导致重跑⑥时跳过重注入，
     *  必须靠版本标记删 marker 强制重注入，老用户才能拿到新资产）。
     *  与 STEP6_VERSION 一起写入 builtin-assets.version（installGuard 末尾）。 */
    private static final String BUILTIN_ASSET_VERSION = "32";

    /** 内置插件资产版本自愈（检查 + 删 marker；版本标记写入在 installGuard
     *  末尾 runStep 里——若中途失败版本未写，下次启动版本不一致会重跑⑥重注入，
     *  保证自愈闭环不因"先删后写"断裂）。
     *  幂等：版本一致秒回。 */
    private void refreshBuiltinAssetMarkers() {
        try {
            if (!proot.isInstalled()) return;
            String r = proot.execAndRead(
                    "cat /root/.dsh/builtin-assets.version 2>/dev/null || echo NONE");
            boolean sameVersion = r != null && r.trim().equals(BUILTIN_ASSET_VERSION);
            // 只看版本标记会漏一整类情况：**标记和实体住在会被分别重置的两个地方**。
            // 版本标记在 /root/.dsh 里，插件实体在 /root 下 —— 而「重新解压内置环境」
            // 把整个 rootfs 换掉（实体与 marker 全没），却会备份还原 .dsh（标记留下来了）。
            // 于是 App 认为「资产已是最新」直接早退，内置插件（悬浮条 / 移动端适配 /
            // 设备 shell 指南）全部静默消失，用户看到的是「悬浮窗怎么突然不显示了」
            // 这种毫无线索的症状。真机上真的发生了，所以判据必须带上「实体还在吗」。
            String missing = missingBuiltinEntities();
            if (sameVersion && missing.isEmpty()) return;
            proot.execAndRead(
                    "rm -f /root/dsha-web-mobile-installed /root/dsha-device-shell-guide-installed /root/dsha-status-overlay-installed; "
                    + "echo refreshed");
            android.util.Log.i("DSHA", "内置插件资产要重注入（版本"
                    + (sameVersion ? "一致" : "变化：" + (r == null ? "?" : r.trim()))
                    + (missing.isEmpty() ? "" : "；实体缺失 " + missing) + "）");
        } catch (Throwable ignored) {
        }
    }

    /** 哪些内置插件的实体目录不见了（逗号分隔，都在则返回空串）。
     *  只查有 {@code -installed} marker 的那几个 —— 与上面删 marker 的范围保持一致，
     *  免得又出现「一处检查、另一处漏掉」。 */
    private String missingBuiltinEntities() {
        String[] dirs = {"dsha-web-mobile", "dsha-device-shell-guide", "dsha-status-overlay"};
        StringBuilder sb = new StringBuilder();
        for (String d : dirs) {
            if (!new java.io.File(proot.getRootfsDir(), "root/" + d).isDirectory()) {
                if (sb.length() > 0) sb.append(',');
                sb.append(d);
            }
        }
        return sb.toString();
    }

    /**
     * 桥与悬浮条链路自检，能自动修的当场修。
     *
     * <p><b>为什么必须在 App 侧做</b>：这几轮的真机故障全出在这条链上，而它跨了三层 ——
     * App 侧的服务与权限、rootfs 里的 token 文件、dsh 插件发出的 HTTP 请求。
     * selftest.py 跑在容器里，看不到 App 的服务状态和悬浮窗权限；容器里的 ss / netstat
     * 也看不到 host 侧的监听 socket（用户实测「没监听」其实是误报 —— 同一时刻 curl
     * 拿到的是 HTTP 200）。所以这段只能由 App 自己查，而且要**端到端真发一次请求**，
     * 光看配置项是查不出错位的。
     *
     * <p>覆盖的历史故障：3090 桥挂在 ADB 开关上导致压根没监听 · 重解压把旧
     * .bridge_token 还原回来导致一律 401 · 重解压后内置插件实体丢失而版本标记还在
     * 导致永不重装 · 悬浮条开关或悬浮窗权限没开却毫无提示。
     */
    public String selfCheckBridgeChain() {
        StringBuilder sb = new StringBuilder("\n===== 桥与悬浮条链路（App 侧）=====\n");
        int fixed = 0;
        // 桥可能压根没启动过，那时 HttpShellService 取不到 Context、token 也就写不进去。
        // 先把 Context 交给它，免得下面「修 token」修了个空还报成功。
        HttpShellService.bindTokenContext(appContext);

        // 1) 服务在不在。3090 桥是 DeviceBridgeService 起的，而它以前只看 ADB 开关
        boolean running = DeviceBridgeService.isRunning();
        boolean need = DeviceBridgeService.needed(appContext);
        if (running) {
            sb.append("OK   桥服务在跑\n");
        } else if (need) {
            try {
                DeviceBridgeService.apply(appContext);
                fixed++;
                sb.append("修复 桥服务没在跑（有功能需要它）→ 已拉起，等几秒重试\n");
            } catch (Throwable e) {
                sb.append("FAIL 桥服务没在跑，拉起失败：").append(describe(e)).append('\n');
            }
        } else {
            sb.append("SKIP 桥服务未启动，但也没有功能需要它（ADB 与悬浮条都关着）\n");
        }

        // 2) token 一致性。文件在 rootfs 里，内存那份是静态字段 —— 重解压/恢复会让两边错位
        String memTok = "";
        try {
            // 只读内存快照，不触发生成 —— 自检本身不该有副作用
            memTok = HttpShellService.tokenSnapshot();
        } catch (Throwable ignored) {
        }
        java.io.File tf = rootfsFile("root/.dsh/.bridge_token");
        String fileTok = "";
        try {
            if (tf.isFile()) {
                fileTok = new String(java.nio.file.Files.readAllBytes(tf.toPath()),
                        StandardCharsets.UTF_8).trim();
            }
        } catch (Throwable ignored) {
        }
        if (!running && !need) {
            sb.append("SKIP token：桥没启动、也没功能需要它 —— token 是桥启动时才生成的。"
                    + "下面容器侧 selftest 里那条「❌ 缺 .bridge_token」是这条的连带，不用管\n");
        } else if (fileTok.isEmpty()) {
            boolean ok = false;
            try {
                HttpShellService.resetTokenAfterRestore();
                java.io.File after = rootfsFile("root/.dsh/.bridge_token");
                ok = after.isFile() && after.length() > 0;   // 必须核实，不能光看没抛异常
            } catch (Throwable ignored) {
            }
            if (ok) {
                fixed++;
                sb.append("修复 rootfs 里没有 token 文件 → 已写入\n");
            } else {
                sb.append("FAIL rootfs 里没有 token 文件，这次也没写进去 —— 桥还没真正启动过，"
                        + "token 的落盘路径要等桥拿到 rootfs 才算得出来。"
                        + "去「配置」页把「设备桥」或「悬浮条」开一次，桥起来就会生成\n");
            }
        } else if (!memTok.isEmpty() && !fileTok.equals(memTok)) {
            try {
                HttpShellService.resetTokenAfterRestore();
                fixed++;
                sb.append("修复 token 错位（插件读到的和 App 校验的不是同一个）→ 已重置，"
                        + "**要重启 WebUI 才彻底生效**（dsh 后端也缓存 token）\n");
            } catch (Throwable e) {
                sb.append("FAIL token 错位，重置失败：").append(describe(e)).append('\n');
            }
        } else {
            sb.append("OK   token 两侧一致\n");
        }

        // 3) 悬浮条的开关与权限（只有 App 能查；权限没法代授，只能说清楚）
        boolean ovOn = OverlayController.enabled(appContext);
        boolean ovPerm = OverlayController.permitted(appContext);
        sb.append(ovOn ? "OK   悬浮条开关：已开\n" : "SKIP 悬浮条开关：关着（配置页可开）\n");
        if (ovOn) {
            sb.append(OverlayController.showReasoning(appContext)
                    ? "OK   思考过程：已开（reasoning 会显示）\n"
                    : "SKIP 思考过程：关着 —— 这就是「看不到思考内容」的原因，"
                            + "配置页勾「显示思考过程」，勾完重启一次 WebUI（插件那侧有一分钟冷却）\n");
        }
        if (ovOn && !ovPerm) {
            sb.append("FAIL 悬浮窗权限没给 —— 开关开着也不会显示。"
                    + "去「配置」页重新勾一次会跳到系统授权页\n");
        } else if (ovOn) {
            sb.append("OK   悬浮窗权限：已授予\n");
        }

        // 4) 端到端实测：真发一次请求，配置项看着对也可能是错位的
        String tok = memTok.isEmpty() ? fileTok : memTok;
        sb.append("探针 /app/overlay → ")
                .append(httpLocalGet("/app/overlay?kind=text&text=selftest-probe", tok))
                .append('\n');
        sb.append("     返回 OK=链路通 · DISABLED=开关关 · NO_PERMISSION=没授权 · "
                + "HTTP 401=token 错位 · 连不上=桥没监听\n");

        // 5) 内置插件实体（重解压会把它们清掉，而版本标记留在 .dsh 里活着）
        String missing = missingBuiltinEntities();
        if (missing.isEmpty()) {
            sb.append("OK   内置插件实体齐全\n");
        } else {
            try {
                proot.execAndRead("rm -f /root/.dsh/builtin-assets.version /root/dsha-*-installed; echo cleared");
                fixed++;
                sb.append("修复 内置插件实体缺失（").append(missing)
                        .append("）→ 已清版本标记，**重启 App 后会自动重装**\n");
            } catch (Throwable e) {
                sb.append("FAIL 内置插件实体缺失（").append(missing)
                        .append("），清标记失败：").append(describe(e)).append('\n');
            }
        }

        sb.append(fixed > 0 ? "本节自动修复 " + fixed + " 处\n" : "本节无需修复\n");
        if (!ovOn) {
            sb.append("提示 悬浮条现在是关着的 —— 这就是它不显示的原因，不是故障。要用的话：\n"
                    + "     配置页勾「屏幕顶部流式显示 AI 输出」→ 按提示授权悬浮窗 → 想看思考过程或就地回话，进「悬浮条外观与行为」再勾 → 重启 WebUI。\n"
                    + "     卸载重装会把所有开关恢复默认（全关），桥和 token 也就跟着不启动了。\n");
        }
        return sb.toString();
    }

    /** 往本机 3090 桥发一个 GET，返回「HTTP 码 + 响应首段」或连不上的原因。 */
    private String httpLocalGet(String pathAndQuery, String token) {
        java.net.HttpURLConnection conn = null;
        try {
            java.net.URL u = new java.net.URL(
                    "http://127.0.0.1:" + HttpShellService.PORT + pathAndQuery);
            conn = (java.net.HttpURLConnection) u.openConnection();
            conn.setConnectTimeout(2000);
            conn.setReadTimeout(3000);
            if (token != null && !token.isEmpty()) conn.setRequestProperty("X-Token", token);
            int code = conn.getResponseCode();
            String body = "";
            try (java.io.InputStream in = code >= 400 ? conn.getErrorStream() : conn.getInputStream()) {
                if (in != null) {
                    byte[] buf = new byte[256];
                    int n = in.read(buf);
                    if (n > 0) body = new String(buf, 0, n, StandardCharsets.UTF_8).trim();
                }
            }
            return "HTTP " + code + (body.isEmpty() ? "" : " " + body);
        } catch (Throwable e) {
            return "连不上（" + describe(e) + "）";
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    /** 确保 rootfs 内危险命令确认包装器已部署（版本不匹配则强制重装，幂等） */
    public void ensureDangerGuard() {
        try {
            // 版本标记：旧版包装器/守卫不升级是之前漏拦截的根因，必须强制刷新
            String ver = proot.execAndRead("cat /root/dsh-bin/.version 2>/dev/null || echo 0");
            if (ver != null && ver.trim().equals(GUARD_VERSION)) return;
            String inst = readAsset("rootfs-confirm-install.sh");
            if (inst.isEmpty()) return;
            // 清掉旧版（含旧 dsh-bin/守卫脚本），避免残留旧包装器
            proot.execChecked("rm -rf /root/dsh-bin /root/dsh-guard.sh /root/dsh-confirm.sh && echo CLEARED");
            java.io.File f = new java.io.File(proot.getRootfsDir(), "root/install-confirm.sh");
            if (f.getParentFile() != null) f.getParentFile().mkdirs();
            try (java.io.FileOutputStream fo = new java.io.FileOutputStream(f)) {
                fo.write(inst.getBytes(StandardCharsets.UTF_8));
            }
            proot.execChecked("bash /root/install-confirm.sh && rm -f /root/install-confirm.sh");
        } catch (Exception ignored) {
            // 环境未安装等场景静默
        }
    }

    private static final String KEY_GUARD_PATCH = "guard_patch_state";

    /** bash 工具守卫补丁的状态：ok / no_lib / patch_failed / unknown（还没启动过 Web）。
     *  （吸收上游 PR#24）配置页据此显示，别让用户以为确认仍是双保险。 */
    public String guardPatchState() {
        return prefs.getString(KEY_GUARD_PATCH, "unknown");
    }

    /** 给已构建的 bash 工具 lib 直接打补丁（强制每次执行前加载守卫，不依赖重新 build）。
     *
     *  这是全项目对 dsh 内部实现最脆弱的耦合：sed 匹配的是已构建代码里的字面串
     *  `command: request.command`，而 dsh 走「始终最新 RC」自动升级 —— 上游一改这行，
     *  这层保险就静默失配。所以每个分支都要 echo 到 stdout 让 Java 侧判得出成败，
     *  结果记进 prefs 供配置页展示（以前只写个用户永远看不到的日志文件）。 */
    public void ensureBashGuardPatch() {
        String state = "unknown";
        try {
            // RC6（npm 全局安装，依赖可能是嵌套或扁平布局，用 find 通配兼容两种）；
            // 源码版：packages/shell/bash-local
            String wd = getWorkdir();
            String out = proot.execAndRead(
                    "F=$(find /usr/local/lib/node_modules -path '*/@deepseek-ai/dsh-bash-local/lib/index.js' 2>/dev/null | head -1); " +
                    "if [ -z \"$F\" ]; then F=/root/" + wd + "/packages/shell/bash-local/lib/index.js; fi; " +
                    "if [ ! -f \"$F\" ]; then echo 'NO_LIB 守卫补丁: 未找到 bash 工具 lib' | tee /root/dsh-guard-patch.log; " +
                    "elif grep -q 'dsh-guard' \"$F\"; then echo LIB_ALREADY; " +
                    "else sed -i 's|command: request\\.command|command: `source /root/dsh-guard.sh 2>/dev/null; ${request.command}`|' \"$F\" " +
                    "&& grep -q 'dsh-guard' \"$F\" && echo LIB_PATCHED || " +
                    "echo 'PATCH_FAILED 守卫补丁: sed 未匹配（dsh 可能已升级改动代码）' | tee /root/dsh-guard-patch.log; fi");
            if (out != null) {
                if (out.contains("LIB_ALREADY") || out.contains("LIB_PATCHED")) state = "ok";
                else if (out.contains("NO_LIB")) state = "no_lib";
                else if (out.contains("PATCH_FAILED")) state = "patch_failed";
            }
        } catch (Exception ignored) {
        }
        try {
            if (!"unknown".equals(state) && !state.equals(prefs.getString(KEY_GUARD_PATCH, ""))) {
                prefs.edit().putString(KEY_GUARD_PATCH, state).apply();
                if (!"ok".equals(state)) {
                    android.util.Log.w("DSHA", "bash 工具守卫补丁未生效：" + state
                            + "（危险命令仍有 PATH 包装器拦截，但少一层兜底）");
                }
            }
        } catch (Throwable ignored) {
        }
    }

    public void startWeb() {
        // Web 又要跑起来了 —— 那么「上次手动停止」的两个约束就到此为止：
        //   keepalive_paused  暂停自动拉起（原先只有 LaunchFragment 的启动按钮会清它，
        //                     从通知栏「重启」等其它入口进来就清不掉）
        //   last_web_stop     90 秒冷却期
        // 不清的话会留下一个隐蔽的坑：停止后立刻手动启动，Web 万一崩了，
        // 90 秒内不会自动重试 —— 而这个冷却期本来是为了「尊重用户不想让它跑」，
        // 用户都亲手把它拉起来了，它就没有存在意义了。
        prefs.edit().putBoolean("keepalive_paused", false).remove("last_web_stop").apply();
        // ===== 会话自愈必跑点：无论 web 是否已经在运行，都先提交一次自愈（幂等，秒级）。
        // 若把 heal 放在 IO 启动任务里，web 已存活时 startWeb 提前 return 会导致修复永不执行。
        // 点下去立刻给反馈。下面的活都排在单线程 IO 队列里，前面可能还有别的任务，
        // 而用户此刻只想知道「按钮生效了」—— 真机体感「点了三秒没反应」就是这里空着。
        setProgress("正在启动 Web UI", 0);
        repairProrootDowngradeOnce();
        maybeHealSessionCorruption();
        synchronized (webStartLock) {
            if (webProcess != null && webProcess.isAlive()) {
                return; // 已在运行，避免重复启动
            }
            if (webStarting) return; // 已有启动在进行（防 keepAlive/手动并发起第二个实例 → EADDRINUSE）
            webStarting = true;
        }
        // 局域网模式：桥跟随 WebUI 启动（不依赖 HarnessService——预启动/
        // 保活重启路径不经过 HarnessService，之前桥没起导致局域网访问不了）
        if (isLanMode()) {
            try {
                LanProxyService.start(getRootfsDirPath(), appContext, getPortInt());
            } catch (Throwable ignored) {
            }
        }
        IO.execute(() -> {
            boolean started = false;
            // 启动分步耗时 —— 用户反馈「1.2 两秒、我们二十秒」，而光读代码只能猜。
            // 这几行把每一步实际花的毫秒打进活动日志，一次真机启动就能指出瓶颈在哪。
            final long tStart = System.currentTimeMillis();
            long tPrev = tStart;
            final StringBuilder timing = new StringBuilder();
            try {
                // 启动前预检：端口仍被占 → 深杀残留（根治 EADDRINUSE）
                // 端口上已经有人监听时，先问清那位还能不能服务 —— 能就直接接管。
                //
                // 下面那套清场（stop → 等端口关 → TERM → sleep 3 → KILL → sleep 1 →
                // 再等 4 秒）最坏要付 12 秒，而它最常见的触发场景恰恰是：App 被系统回收
                // 或用户划掉，容器里的 dsh 还好好跑着，用户重新进来点启动。那种情况下
                // 把一个健康实例杀掉重建纯属白等 —— 用户实测「1.2 两秒进去，我们要二十秒」
                // 差的就是这一段。
                //
                // 只用 isWebPortUp 判断不够：连得上不代表答话（端口被别的进程占、node
                // 卡死、还没进 listen 回调都连得上）。所以再让 WebProbe 发一次真请求，
                // 对方回 HTTP 状态行才算健康；不健康就照旧走清场。
                boolean portUp = isWebPortUp(400);
                if (portUp && WebProbe.servesHttp(parsePort(), 1500)) {
                    proot.noteProrootSuccess();   // 这个运行时确实能用
                    bumpWebEpoch();               // 通知预览端刷新
                    setState("", 100, "Web UI 已在运行", "", false);
                    logActivity("接管已在运行的 dsh（跳过清场与重启），耗时 "
                            + (System.currentTimeMillis() - tStart) + "ms");
                    ensureBuiltinPluginsAfterProfileReady();
                    return;                       // finally 会释放 webStarting
                }
                if (portUp) {
                    destroyAllWebProcesses();
                    proot.execAndRead(stopWebCommand());
                    if (!waitPortClosed(4000)) {
                        // 这里原来是裸的 pkill -f node / -f 'bin.js' —— 同一个文件第 178 行
                        // 的注释就写着「裸 pkill -f node 会误杀」，可这儿漏了。
                        // 后果比误杀 agent 严重得多：proot 的命令行里带着 rootfs 路径和
                        // 待执行命令，'node' 与 'bin.js' 都可能命中 proot 自身，
                        // 于是把承载整个环境的 proot 一起杀掉，App 的前台服务随之死亡 ——
                        // 用户看到的就是「点重启约 10 秒后闪退，通知栏也没了」
                        // （那个 10 秒正是这段里的 sleep 3 加上前面 waitPortClosed 的累计）。
                        // 而且只有「端口仍被占、正常停止没成功」才会走到这儿，也就是重启场景，
                        // 首次启动端口是空的、压根不执行 —— 所以表现为「以前一直是好的」。
                        killWebProcessesFromAndroid();
                        proot.execAndRead(safeKillWebCmd("TERM") + "sleep 3; "
                                + safeKillWebCmd("9") + "sleep 1; echo done");
                        waitPortClosed(4000);
                    }
                }
                // 会话损坏自愈：web 拉起前先修（无门槛全量扫描，幂等；修复后用户刷新即恢复历史）
                doHealSessionCorruption();
                timing.append("清场+会话自检 ").append(System.currentTimeMillis() - tPrev).append("ms · ");
                tPrev = System.currentTimeMillis();
                setProgress("正在启动 Web UI", 0);
                // 三步都是幂等写文件，常态下只是确认「已经在了」，实测合计 364~393ms ——
                // 这是启动路径上最后一个同步大项。资产版本没变、guard 也在，就整段跳过。
                if (!runtimeFilesFresh()) {
                    proot.ensureRuntimeFiles();
                    ensureDangerGuard(); // 安全包装器缺失则自动补装
                    ensureBashGuardPatch(); // bash 工具 lib 强制加载守卫（不依赖重装）
                    markRuntimeFilesFresh();
                }
                timing.append("运行文件+守卫 ").append(System.currentTimeMillis() - tPrev).append("ms · ");
                tPrev = System.currentTimeMillis();
                // 校准 bundles 必须在 dsh 起来之前做完（它读到解析不到的 bundle 就直接退出），
                // 但**不能**放在 startWebCommand() 里面 —— 那个方法只该组装命令字符串，
                // 在里面做文件 IO、甚至再起一个 proot 进程，正撞上马上要启动的 Web 进程。
                sanitizeProfileBundles();
                timing.append("清理bundle ").append(System.currentTimeMillis() - tPrev).append("ms · ");
                tPrev = System.currentTimeMillis();
                Process p = proot.execRootfs(startWebCommand());
                timing.append("起进程 ").append(System.currentTimeMillis() - tPrev).append("ms");
                logActivity("启动分步耗时：" + timing);
                webProcesses.add(p);
                synchronized (webStartLock) {
                    webProcess = p;
                }
                // 「解除 keepAlive 暂停」已经由 startWeb() 开头统一做掉，这里不再重复写一遍 ——
                // 同一个标记在多处置位，就是这次「停止按不动」的病根。
                bumpWebEpoch(); // 新 web 进程已起：通知预览端刷新
                // 关键：Web 启动中 busy=true 会让安装/重装按钮全灰。
                // 端口就绪即视为启动完成 → 释放 busy（不能等 drainOutput 阻塞返回，
                // 否则 Web 运行期间 busy 永远 true → 重装按钮永远灰色）。
                // 用独立线程阻塞 drain（保持启动器 + node 进程存活）。
                // 注意别把这句读成「不 drain 就会自动收尾」：`--kill-on-exit` 只有
                // ContainerRuntime.Proot 传，**默认的 proroot 没有这个参数**，
                // 它退出时容器里的 node 与 nohup 起的看门狗都会活下来（变孤儿继续跑）。
                // 停止那条链路因此不能靠杀启动器传播信号，只能按 pid 精确杀。
                // 本线程继续等待端口就绪后释放 busy 并处理退出诊断。
                Thread drainer = new Thread(() -> {
                    try {
                        String out = proot.drainOutput(p);
                        // 进程退出：非用户主动停止 → 交给 keepAlive/自动重试。
                        // 判据统一走 shouldAutoStartWeb —— 用户停过、自愈中、刚停过的冷却期内
                        // 都不该自动重试，这几条以前在别处各写一遍。
                        if (shouldAutoStartWeb("Web 意外退出重试")) {
                            String low = out == null ? "" : out.toLowerCase();
                            boolean configErr = low.contains("invalid api key") || low.contains("validationerror")
                                    || low.contains("api key") && low.contains("missing");
                            if (!configErr && autoRetryWebOnce()) return;
                            String tail = out.length() > 600 ? out.substring(out.length() - 600) : out;
                            setState("", 0, "", "Web UI 意外退出：\n" + tail, false);
                        } else {
                            setState("", 0, "已停止后台服务", "", false);
                        }
                    } catch (Throwable ignored) {
                    } finally {
                        synchronized (webStartLock) {
                            webStarting = false;
                        }
                    }
                }, "dsha-web-drain");
                drainer.setDaemon(true);
                drainer.start();
                // 端口等待放独立线程：IO 是单线程执行器，若在此阻塞 60s，
                // stopWeb/restartWeb/install 全部排队卡死（用户点停止没反应）
                Thread waiter = new Thread(() -> {
                    try {
                        if (waitWebPortUp(60_000)) {
                            logActivity("Web 就绪，自点击起共 "
                                    + (System.currentTimeMillis() - tStart) + "ms");
                            setState("", 100, "Web UI 已启动", "", false);
                            proot.noteProrootSuccess();   // 这次运行时可用，清零失败计数
                            // 全新安装的关键一步：profile 是 dsh 首次启动时才创建的，
                            // 而 MainActivity 里那次 ensureBuiltinPluginsReady 跑在它之前 ——
                            // 那时三个内置插件一个也注册不上。以前只在日志里写「启动一次
                            // WebUI 后会自动补上」，却没有任何代码真的去补，于是全新安装
                            // 的用户根本没有内置插件（除非碰巧重开一次 App）。
                            ensureBuiltinPluginsAfterProfileReady();
                        } else {
                            // 超时：释放 busy（否则一直卡灰，靠 10 分钟自愈太慢）
                            //
                            // 端口没起来**不等于 proroot 不行**。超时的根因绝大多数跟容器运行时
                            // 无关：profile 装不起来（#46 那个 semver）、key 没填、插件报错卡住
                            // 加载、磁盘满、首次装依赖被网络拖住 —— 换成 proot 同样起不来，
                            // 但用户从此背上慢容器，而且真正的毛病一点没修。
                            // 所以先问一句「容器本身还能执行命令吗」：能，就说明 proroot 是好的，
                            // 这笔账不该记在它头上。
                            boolean containerOk;
                            try {
                                String probe = proot.execAndRead("echo DSHA_RT_OK", 8_000);
                                containerOk = probe != null && probe.contains("DSHA_RT_OK");
                            } catch (Throwable t) {
                                containerOk = false;
                            }
                            boolean forced = false;
                            if (!containerOk) {
                                forced = proot.noteProrootFailure(
                                        "Web 启动超时（60s）且容器无法执行命令");
                            } else {
                                logActivity("Web 启动超时，但容器能正常执行命令 —— "
                                        + "不计入 proroot 失败（根因在 dsh 侧，查启动页日志尾部）");
                            }
                            setState("", 0, "", "Web UI 启动超时（60s 端口未就绪）\n"
                                    + (forced
                                    ? "proroot 连续失败已达上限，已自动切回 proot —— 再点一次「重启」"
                                    : containerOk
                                    ? "容器本身是正常的，问题出在 dsh 启动阶段 —— 查看启动页日志尾部"
                                    : "可稍后点「重启」，或查看启动页日志尾部"), false);
                        }
                    } catch (Throwable ignored) {
                    }
                }, "dsha-web-portwait");
                waiter.setDaemon(true);
                waiter.start();
            } catch (Throwable e) {
                setState("", 0, "", errMsg("启动出错：", e), false);
            } finally {
                synchronized (webStartLock) {
                    webStarting = false; // 无论成功失败都要释放，否则后续启动全被挡
                }
            }
        });
    }

    /** Web 意外退出自动重试（限 1 次，防抖动死循环）。返回 true=已重试 */
    private volatile long lastAutoRetryAt = 0;
    private boolean autoRetryWebOnce() {
        long now = System.currentTimeMillis();
        if (now - lastAutoRetryAt < 30_000) return false; // 30s 内不重复重试
        lastAutoRetryAt = now;
        android.util.Log.w("DSHA", "Web 意外退出，30s 后自动重试 1 次");
        prefs.edit().putLong("web_auto_retry_at", now).apply();
        new Handler(Looper.getMainLooper()).postDelayed(this::startWeb, 30_000);
        return true;
    }

    /**
     * 通过 ADB 通道（uid=2000 的 shell 身份）给自己开后台白名单 —— 免 root。
     *
     * <p>比引导用户去设置页翻更靠得住：厂商 ROM 的电池设置藏得深、每家名字还不一样，
     * 而这几条在 AOSP 系上语义一致：
     * <ul>
     *   <li>{@code dumpsys deviceidle whitelist +<包名>} → 进 Doze 白名单，
     *       熄屏静止后不被打入待机；</li>
     *   <li>{@code cmd appops set <包名> RUN_ANY_IN_BACKGROUND allow} → 解除后台运行限制。</li>
     * </ul>
     *
     * <p>全都幂等，重复执行无害。<b>ADB 没接上时直接跳过</b> —— 这是加分项，
     * 息屏保活的主力是 WakeLock 那条（不依赖 ADB，见 HarnessService.acquireLocks）。
     *
     * <p>分成多次调用而不是拼一条带分号的串：{@code adb-shell.py} 收的是命令参数，
     * 塞整段 shell 不一定被它当一条命令执行 —— 与其赌，不如一条一条来。
     */
    public String hardenBackground() {
        StringBuilder log = new StringBuilder();
        try {
            if (!AdbBridge.injected(proot)) return "ADB 通道没就绪，跳过后台加固";
            final String pkg = BuildConfig.APPLICATION_ID;
            String[] cmds = {
                    "dumpsys deviceidle whitelist +" + pkg,
                    "cmd appops set " + pkg + " RUN_ANY_IN_BACKGROUND allow",
                    "cmd appops set " + pkg + " RUN_IN_BACKGROUND allow",
            };
            for (String cmd : cmds) {
                String r = proot.execAndRead("DSH_INTERNAL=" + HttpShellService.mintInternalTicket()
                        + " python3 /root/.dsh/adb-shell.py "
                        + cmd + " 2>&1 | tail -2", 45_000);
                log.append(cmd).append(" → ")
                   .append(r == null ? "无输出" : r.replace('\n', ' ').trim()).append('\n');
            }
            logActivity("已通过 ADB 开后台白名单（Doze + appops），息屏更不容易被冻");
            android.util.Log.i("DSHA", "[保活] 后台加固:\n" + log);
            return log.toString();
        } catch (Throwable t) {
            // 别静默：这条失败只表现为「息屏一会儿就停」，没日志根本追不到
            android.util.Log.w("DSHA", "[保活] 后台加固失败: " + t);
            return "后台加固失败：" + t;
        }
    }

    public void stopWeb() {
        // 记录手动停止时间：最近停止后 90s 内关闭自动预启动（尊重用户）
        final long stopStamp = System.currentTimeMillis();
        prefs.edit().putLong("last_web_stop", stopStamp).apply();
        // 标记"用户主动停止"：keepAlive 暂停自动拉起，直到用户/预启动再次 startWeb
        prefs.edit().putBoolean("keepalive_paused", true).apply();
        IO.execute(() -> {
            try {
                // 停止这条路以前只 destroy 了「最后一个」webProcess，而重启那条走的是
                // destroyAllWebProcesses()。预热与用户点启动是两条 startWeb 路径，
                // 容器里可能同时有不止一个实例 —— 只 destroy 一个，剩下那个继续占着端口，
                // 用户看到的就是「点了停止但没停」。两条路统一用全量。
                destroyAllWebProcesses();
                // ① pid 文件通道：按启动时自己写下的 pid，在 App 侧直接送 SIGTERM。
                //    容器内那套判据受两个环境限制（/proc/net 读不到、/proc 只见同 uid），
                //    这条不受 —— 所以放在最前面。
                //    **必须是 TERM 不是 KILL**：dsh 要有机会 flush SQLite，直接 -9 会把
                //    会话写坏（用户报过 SessionPersistenceCorruptionError）。
                //    紧跟着的容器脚本自己带 TERM → sleep 3 → KILL 的兜底。
                String pidLog = killByPidFiles(15);
                if (!pidLog.isEmpty()) logActivity("停止 · pid 文件通道：\n" + pidLog);
                String out = proot.execAndRead(stopWebCommand());
                // Web 停了桥也没用：停桥（幂等）
                LanProxyService.stop();
                // 没人在写会话了 —— 这是把私有目录同步到公开镜像最安全的时机。
                // 镜像是卸载重装后唯一的恢复来源，所以每次干净停止都要更新它。
                syncSessionMirror();
                // 眼见为实：以前不管杀没杀掉都显示「已停止」，于是 dsh 还在跑、
                // 端口还占着，用户却以为停了 —— 这正是「停止用不了」的体验来源。
                int left = parseKvInt(out, "STOP_LEFT");
                int port = parseKvInt(out, "STOP_PORT");
                // 诊断段一律进活动日志：停止这条路已经改过一版还没好，光有「还剩 N 个」
                // 不够定位 —— 得知道设备上那些进程的 cmdline 到底长什么样。
                // 用户能在设置页把活动日志复制出来发回来。
                String diag = sliceBetween(out, "STOP_DIAG_BEGIN", "STOP_DIAG_END");
                if (diag != null && !diag.trim().isEmpty()) {
                    logActivity("停止诊断（含 node/dsh 的进程）：\n" + diag.trim());
                }
                // 没停干净就补第二轮：Android 侧按 pid 精确杀（不依赖容器内命令，
                // 也不受 proot/proroot 切换影响）+ 容器内兜底。这一套原来只有「重启」
                // 那条路在用，停止这条路漏了 —— 于是「重启能停下来、停止停不下来」。
                if (left > 0 || port > 0 || !waitPortClosed(1500)) {
                    int k = killWebProcessesFromAndroid();
                    proot.execAndRead(safeKillWebCmd("TERM") + "sleep 2; "
                            + safeKillWebCmd("9") + "sleep 1; echo done");
                    logActivity("停止第二轮：Android 侧精确杀 " + k + " 个"
                            + (waitPortClosed(4000) ? "，端口已关闭" : "，端口仍被占用"));
                }
                boolean stillUp = isWebPortUp(400);
                String why = stillUp
                        ? "停止没干净：端口还在监听 —— 到设置页看活动日志"
                        : "已停止后台服务";
                logActivity(why);
                setState("", 0, why, "", false);
                // ── 复活侦测 ──
                // 真机症状是「停止后 dsh 秒复活」：停完那一刻是干净的，几秒后又活了。
                // 停完立刻报「已停止」根本反映不出这件事，所以再等 4 秒回头看一眼；
                // 又活了就把占端口进程的**父进程**打出来 —— 「谁拉起来的」只有父进程能回答。
                //
                // 放独立线程而不是接在这后面：IO 是单线程执行器，在里面多睡 4~10 秒会让
                // 用户紧接着点的「启动」排队等待（这个坑本文件注释里写过）。
                startReviveProbe(stopStamp);
            } catch (Exception ignored) {
            }
        });
    }

    /**
     * 停止后的复活侦测：4 秒后回看端口，还活着就记下「谁拉起来的」并再收一轮。
     *
     * <p>哨兵（{@link WebProcSel#STOP_SENTINEL}）此时已经落地，所以第二轮之后拉起者自己
     * 也会退出；这里的重点是<b>把证据留在活动日志里</b>，让下一次不必再猜。
     *
     * <p><b>两道门缺一不可</b>：用户完全可能在这 4 秒里改主意点「启动」——
     * 那时端口活着是他要的结果，把它当成「复活」杀掉就成了一个更难解释的 bug
     * （「点启动之后几秒自己又停了」）。所以：
     * <ul>
     *   <li>{@code keepalive_paused} 被清掉 = 有人调过 {@link #startWeb()}（那个方法开头
     *       统一清它），说明现在该让 Web 跑着 → 退场；</li>
     *   <li>{@code last_web_stop} 变了 = 期间又停过一次，新的那轮自带侦测 → 退场，
     *       免得两个侦测线程互相打脸。</li>
     * </ul>
     *
     * @param stopStamp 发起这次停止时写下的时间戳，用来认领「这一轮」
     */
    private void startReviveProbe(long stopStamp) {
        Thread t = new Thread(() -> {
            try {
                // 采样两次：4 秒抓「秒复活」，15 秒抓慢一点的拉起者。两轮都要重新过门 ——
                // 用户随时可能改主意点启动，那时端口活着是他要的结果。
                int[] waits = {4000, 11000};
                for (int wait : waits) {
                    Thread.sleep(wait);
                    if (!isKeepAlivePaused()) return;                          // 用户又点了启动
                    if (prefs.getLong("last_web_stop", 0) != stopStamp) return; // 又停过一次
                    if (!isWebPortUp(400)) continue;
                    String rev = proot.execAndRead(reviveDiagCommand(parsePort()));
                    String seg = sliceBetween(rev, "REVIVE_BEGIN", "REVIVE_END");
                    logActivity("复活侦测（停止后 " + (wait >= 11000 ? "15" : "4")
                            + " 秒）：端口又活了。谁拉起来的：\n"
                            + (seg == null || seg.trim().isEmpty() ? String.valueOf(rev) : seg.trim()));
                    proot.execAndRead(stopWebCommand());  // 哨兵已在，这轮之后拉起者会自己退出
                    killByPidFiles(9);                    // 兜底这一轮才用 KILL
                    killWebProcessesFromAndroid();
                    boolean closed = waitPortClosed(4000);
                    String why2 = closed
                            ? "已停止（期间有东西试图把 Web 拉起来，已一并收掉；证据在活动日志）"
                            : "停止没干净：有东西在反复拉起 Web —— 把活动日志里的「复活侦测」发回来";
                    logActivity(why2);
                    setState("", 0, why2, "", false);
                }
            } catch (Throwable ignored) {
            }
        }, "dsha-revive-probe");
        t.setDaemon(true);
        t.start();
    }

    public void checkStatus() {
        IO.execute(() -> {
            try {
                proot.ensureRuntimeFiles();
                String out = proot.execAndRead(statusCommand());
                setState("", 0, "状态码：" + out.trim(), "", false);
            } catch (Throwable e) {
                setState("", 0, "", errMsg("检查失败：", e), false);
            }
        });
    }

    // ================= 配置备份 / 重置（防死机无法恢复） =================

    private File rootfsFile(String rel) {
        return new File(proot.getRootfsDir(), rel);
    }

    /**
     * 备份关键配置到 App 私有目录（可通过 MT 管理器 data/files/backup 拷出）。
     * 备份内容：.env + 整个 .dsh（含 settings.yaml、对话记录等）。
     * 返回备份目录绝对路径；失败返回 null。
     */
    /** 备份到外部存储（手动，时间戳命名）；返回路径或 null */
    public String backupConfig() {
        try {
            return BackupManager.backupToExternal(appContext, this);
        } catch (Exception e) {
            return null;
        }
    }

    /** 更新前自动存档：检测到新版本时静默备份一次（防覆盖安装丢数据）。
     *  节流：同一目标版本只备份一次（backup_before_update_tag 记录），
     *  rootfs 未就绪/备份失败静默跳过，不阻塞更新流程。 */
    public void backupBeforeUpdate(String targetVersion) {
        try {
            if (targetVersion == null || targetVersion.isEmpty()) return;
            final SharedPreferences prefs =
                    appContext.getSharedPreferences("deepseekharness", android.content.Context.MODE_PRIVATE);
            if (targetVersion.equals(prefs.getString("backup_before_update_tag", ""))) return; // 已备份过该版本
            new Thread(() -> {
                try {
                    if (!proot.isInstalled()) return;
                    String p = BackupManager.backupToExternal(appContext, this);
                    if (p != null) {
                        // 标记只在**成功之后**写。原来是备份前就写死，
                        // 备份一旦失败，这个版本就永远不会再尝试备份了 ——
                        // 而用户以为「升级前自动存过档」，升级出问题就没救。
                        prefs.edit().putString("backup_before_update_tag", targetVersion).apply();
                        logActivity("更新前已自动存档（准备升级 " + targetVersion + "）");
                    } else {
                        // 失败必须说出来。原来只在成功时写一行 logcat，
                        // 失败时一声不响 —— 这正是这个项目最该避免的形态。
                        String why = BackupManager.lastError();
                        logActivity("更新前自动存档失败"
                                + (why == null || why.isEmpty() ? "" : "：" + why)
                                + " —— 建议到「工作区」页手动备份后再升级");
                    }
                } catch (Throwable t) {
                    logActivity("更新前自动存档异常：" + describe(t)
                            + " —— 建议手动备份后再升级");
                }
            }, "dsha-backup-before-update").start();
        } catch (Throwable ignored) {
        }
    }

    // ================= 升级自动备份 / 恢复（防卸载重装丢数据） =================

    /** 当前 App 的 versionCode；读取失败返回 0 */
    private int currentVersionCode() {
        try {
            return appContext.getPackageManager().getPackageInfo(appContext.getPackageName(), 0).versionCode;
        } catch (Exception e) {
            return 0;
        }
    }

    /**
     * 升级/首次启动自愈（幂等）：版本号比上次运行时提升 → 后台自动把旧环境
     * （.dsh 配置+对话记录 + .env）备份到外部 Download/DSHA，
     * 避免后续覆盖安装/卸载重装导致数据丢失。
     * @return true = 本次为升级或首次启动（调用方可据此检测"全新环境可恢复"）
     */
    public boolean upgradeGuard() {
        final int cur = currentVersionCode();
        if (cur <= 0) return false;
        final SharedPreferences prefs =
                appContext.getSharedPreferences("deepseekharness", android.content.Context.MODE_PRIVATE);
        final int last = prefs.getInt("last_version_code", 0);
        if (cur <= last) return false; // 版本未变，幂等返回
        prefs.edit().putInt("last_version_code", cur).apply();
        final int from = last; // 迁移起点（0=全新安装）
        if (last > 0) { // 真升级（非全新安装）：后台自动备份旧环境 + 版本迁移
            IO.execute(() -> {
                try {
                    // rootfs 已就绪（有 bash）才备份；未解压/未安装时跳过
                    if (proot.isInstalled() && rootfsFile("root/.dsh").isDirectory()) {
                        String p = BackupManager.backupToExternal(appContext, HarnessController.this);
                        if (p != null) {
                            logActivity("升级前已自动备份旧环境");
                        } else {
                            String why = BackupManager.lastError();
                            logActivity("升级自动备份失败"
                                    + (why == null || why.isEmpty() ? "" : "：" + why)
                                    + " —— 旧环境没有存档，如遇问题请到「工作区」页手动备份");
                        }
                    }
                } catch (Throwable t) {
                    logActivity("升级自动备份异常：" + describe(t));
                }
                // ===== 低版本安装适配：老 rootfs 结构差异集中迁移 =====
                // 按来源版本分层，逐层升级（幂等，每层只做该层需要的事）：
                // 老版本（versionCode<=21，即 v1.1.1 及更早）需要补适配。
                try {
                    if (from <= 21 && proot.isInstalled()) {
                        // 1) 老版本无 builtin-assets.version → 删旧 marker 强制重注入官方版
                        //    （老 rootfs 的 dsh-web-mobile 是旧布局/旧内容，靠 STEP6 版本变化
                        //    重跑⑥时 refreshBuiltinAssetMarkers 已处理；这里兜底删 marker）
                        String r = proot.execAndRead(
                                "cat /root/.dsh/builtin-assets.version 2>/dev/null || echo NONE");
                        if (r == null || !r.trim().equals(BUILTIN_ASSET_VERSION)) {
                            proot.execAndRead(
                                    "rm -f /root/dsha-web-mobile-installed /root/dsha-device-shell-guide-installed /root/dsha-status-overlay-installed; echo cleaned");
                            android.util.Log.i("DSHA", "迁移(≤v1.1.1)：已删内置插件 marker，等待重注入");
                        }
                        // 2) 老版本无离线包版本标记 → 写当前（避免误弹升级提示）
                        //    installedOfflineVersion()=="0" 且 bundled>"0" 时
                        //    用户会收到一次升级提示（合理）；这里不主动写，保持提示语义。
                        // 3) 老版本工作区 .env 若在默认目录 → 已由数据保护覆盖
                        android.util.Log.i("DSHA", "迁移(≤v1.1.1)完成");
                    }
                    // 更早版本（v1.0.x，versionCode<=19）可能有旧 profile 结构
                    if (from <= 19 && proot.isInstalled()) {
                        // 旧版 profile 可能缺 dependencies 字段 / 用 file: 依赖，
                        // 触发一次 fix-stale-bundles 自愈（App 启动时会跑，这里显式跑一次）
                        proot.execAndRead(
                                "rm -f /root/dsha-web-mobile-installed /root/dsha-device-shell-guide-installed /root/dsha-status-overlay-installed; "
                                + "echo 'v1.0.x 迁移：删 marker 强制重注入'");
                        android.util.Log.i("DSHA", "迁移(v1.0.x)完成");
                    }
                } catch (Throwable ignored) {
                    // 迁移失败不影响使用（幂等，下次启动 STEP6 变化仍会自愈）
                }
            });
        }
        return true;
    }



    /** 清理损坏会话（供工作区页按钮调用）：
     *  把「无法解码/极小」的会话移到 .dsh/corrupt-backup/（不删除可恢复）。
     *  返回处理结果文案。 */
    public String cleanCorruptSessions() {
        try {
            if (!proot.isInstalled()) return "环境未就绪";
            String out = proot.execAndRead(
                    "mkdir -p /root/.dsh/corrupt-backup; "
                    + "find /root/.dsh/sessions -name 'session.jsonl.zstd' -size -100c 2>/dev/null "
                    + "| while read f; do "
                    + "d=$(dirname \"$f\"); id=$(basename \"$d\"); "
                    + "mkdir -p /root/.dsh/corrupt-backup/\"$id\"; "
                    + "mv \"$f\" /root/.dsh/corrupt-backup/\"$id\"/ 2>/dev/null && echo \"已隔离: $id\"; done");
            if (out == null || !out.contains("已隔离")) return "未发现损坏会话（<100字节的极小文件）";
            return out.trim();
        } catch (Throwable e) {
            return "清理失败: " + e.getMessage();
        }
    }

    /** 启动时自愈：删除空的 /root/.codex/pets（deepseek-pet 插件把空目录当错误
     *  → 整个插件树加载失败）。老版本预创建过空目录，需清理。幂等、后台静默。 */
    public void maybeCleanEmptyPets() {
        IO.execute(() -> {
            try {
                if (!proot.isInstalled()) return;
                proot.execAndRead(
                        "[ -d /root/.codex/pets ] && [ -z \"$(ls -A /root/.codex/pets 2>/dev/null)\" ] "
                        + "&& rmdir /root/.codex/pets 2>/dev/null; echo cleaned");
            } catch (Throwable ignored) {
            }
        });
    }

    /** 会话损坏自愈：启动时检测 dsh-web.log 里的 SessionPersistenceCorruptionError
     *  （中途强杀导致 SQLite 写一半损坏，用户反馈"历史加载失败"）。
     *  检测到 → 备份损坏 .db 并删除（dsh 重建），老会话丢失但 App 可用。
     *  配合停止命令 SIGTERM 优雅退出（减少写入中断）。幂等、后台静默。 */
    public void maybeHealSessionCorruption() {
        IO.execute(this::doHealSessionCorruption);
    }

    /** 同步执行会话自愈（可在 IO 线程/启动链路内联调用）：
     *  1) 修复前先停 Web（否则写入中的会话文件边修边坏）；
     *  2) heal-sessions.py（Python os.walk + 流式 zstd 解码）无门槛全量扫描修复。
     *  幂等：正常文件秒过。 */
    /** 会话自愈的结果要让用户看见 —— 它会改写历史记录，静默进行最不合适。 */
    /** 会话自愈的结果留痕。
     *
     *  <p>两条都要写：logcat 给开发者看，<b>活动日志给用户看</b>。自愈会移动、甚至隔离
     *  用户的会话文件（原件留 .pre-fix / corrupt-backup），这属于「App 动了你的数据」，
     *  只写 logcat 等于用户永远不知道发生过什么 —— 而且 logcat 重启即丢，用户来报
     *  「我的会话不见了」时无据可查。
     *
     *  <p>这个方法曾经存在但没人调用（调用点只剩一行 Log.w），等于活动日志里一直缺这一类。 */
    private void logHealResult(String out) {
        if (out == null || !out.contains("SESSION_HEALED")) return;
        android.util.Log.w("DSHA", "会话损坏自愈：已修复/隔离损坏会话（详情见 heal 输出）");
        int i = out.indexOf("SESSION_HEALED");
        logActivity("会话自愈：" + out.substring(i, Math.min(out.length(), i + 60)).trim()
                + "（原文件留 .pre-fix 备份）");
    }

    /** 系统 WebView 的 Chromium 主版本号，取不到返回 -1。
     *
     *  <p>刻意用 indexOf 而不是正则：解析 UA 这种半结构化文本时，Android 的
     *  java.util.regex 比桌面 JVM 严格，而我们的纯逻辑测试跑在桌面 JVM 上测不到那一层
     *  （曾因一个单独的右花括号在真机抛 PatternSyntaxException，桌面全绿）。 */
    static int systemWebViewMajor(Context ctx) {
        try {
            String ua = android.webkit.WebSettings.getDefaultUserAgent(ctx);
            int i = ua.indexOf("Chrome/");
            if (i < 0) return -1;
            int j = i + "Chrome/".length();
            int k = j;
            while (k < ua.length() && Character.isDigit(ua.charAt(k))) k++;
            return k > j ? Integer.parseInt(ua.substring(j, k)) : -1;
        } catch (Throwable t) {
            return -1;
        }
    }

    /** 系统 WebView 太旧时，把 dsh 前端降级成老内核能跑的 IIFE bundle。
     *
     *  <p>为什么值得做：前端是 Vite 现代产物，Chrome &lt; 118 的机器上会因为
     *  AbortSignal.any 之类的新 API 直接白屏。原先唯一的兜底是切 GeckoView，
     *  但那要多一份浏览器引擎的内存 —— 而需要兜底的恰恰是 2~3GB 内存的老机器。
     *  降级这条路不需要额外内核。
     *
     *  <p>脚本幂等，做完写 {@code /root/.dsh/.legacy-frontend-ok}；
     *  {@link LaunchFragment} 读这个标记决定还要不要退回 GeckoView。 */
    private void ensureLegacyFrontendIfNeeded() {
        try {
            int major = systemWebViewMajor(appContext);
            if (major <= 0 || major >= 118) return;
            java.io.File mark = rootfsFile("root/.dsh/.legacy-frontend-ok");
            boolean done = mark.isFile();
            if (!done) {
                logActivity("系统 WebView 偏旧（Chrome " + major + "），正在降级前端以免用上内置内核");
                runAssetScripts(new String[][]{
                        {"legacy-frontend-patch.sh", "dsha-legacy-frontend.sh"},
                }, 240_000);
                done = mark.isFile();
                logActivity(done ? "前端降级完成，系统内核可直接打开 WebUI"
                                 : "前端降级没成功，仍用内置内核（更吃内存）");
            }
            prefs.edit().putBoolean("legacy_frontend_ready", done).apply();
        } catch (Throwable t) {
            android.util.Log.w("DSHA", "前端降级失败（不致命，回落 GeckoView）: " + t);
        }
    }

    /** 从 profile 的 cordis.patch.yml 里摘掉旧名那条 insert 条目。
     *
     *  <p>patch 行原本随插件目录里的 cordis.patch.yml 走，但 profile 那份是合并后持久化的，
     *  删掉插件目录不会让它自己消失 —— 留着就是一条指向不存在模块的 loader entry。
     *
     *  <p>按缩进切块而不是解析 YAML：条目形如
     *  <pre>    - id: dsh-mobile-nav
     *      name: '@dsh-external/dsh-mobile-nav'
     *      …</pre>
     *  从命中的 {@code - id:} 行起，一直吃到下一个同缩进的 {@code - } 或文件尾。
     *  引一个 YAML 库进来只为删一条记录不值得，而这个结构是我们自己写进去的、形状固定。 */
    private boolean stripOldPatchRow() {
        try {
            java.io.File pf = rootfsFile("root/.dsh/profiles/web/cordis.patch.yml");
            if (!pf.isFile()) return false;
            String txt = new String(java.nio.file.Files.readAllBytes(pf.toPath()),
                    StandardCharsets.UTF_8);
            if (!txt.contains("dsh-mobile-nav")) return false;
            String[] lines = txt.split("\n", -1);
            java.util.List<String> out = new java.util.ArrayList<>(lines.length);
            int skipIndent = -1;
            for (String line : lines) {
                String t = line.trim();
                if (skipIndent >= 0) {
                    // 同缩进的下一个列表项 → 跳过结束
                    int ind = line.length() - line.replaceAll("^\\s+", "").length();
                    if (t.startsWith("- ") && ind <= skipIndent) {
                        skipIndent = -1;
                    } else if (!t.isEmpty() && ind <= skipIndent && !t.startsWith("-")) {
                        skipIndent = -1;   // 退回上一层（比如新的顶层键）
                    } else {
                        continue;
                    }
                }
                if (t.startsWith("- id:") && t.contains("dsh-mobile-nav")) {
                    skipIndent = line.length() - line.replaceAll("^\\s+", "").length();
                    continue;
                }
                out.add(line);
            }
            String neu = String.join("\n", out);
            if (neu.equals(txt)) return false;
            java.nio.file.Files.write(pf.toPath(), neu.getBytes(StandardCharsets.UTF_8));
            return true;
        } catch (Throwable t) {
            android.util.Log.w("DSHA", "清旧 patch 行失败（不致命）: " + t);
            return false;
        }
    }

    /**
     * 从 profile 的 {@code cordis.patch.yml} 里删掉注册指定插件的<b>整块</b>。
     *
     * <p>与 {@link #stripOldPatchRow()} 的区别在匹配方式：那个只看 {@code - id:} 行里
     * 有没有插件名，因此只治得了「id 恰好等于插件名」的行。而 dsh 自己写进 profile 的
     * 行用的是<b>随机 id</b>（真机上遇到的是 {@code d8828179}），插件名在块内的
     * {@code name:} 上 —— 所以必须把整块读出来判断。
     *
     * <p><b>为什么这一份可以删</b>：内置插件的注册来自 bundle 自带的
     * {@code node_modules/<插件>/cordis.patch.yml}，那是 dsh 的标准机制。profile 里再有
     * 一行就是第二份注册，两份都会加载，于是报
     * {@code locale namespace "mobileNav" already has locale "zh"}
     * （同一个插件的 locale 命名空间注册了两遍，后一个直接冲突）→ 插件整个加载失败。
     * 删掉 profile 那份、留下 bundle 那份，功能一点不少。
     *
     * <p>安全阀：<b>只有在 bundle 自带 patch 确实存在时才动手</b>。否则 profile 里那行
     * 可能是唯一的注册来源，删了插件就彻底不见了。
     */
    private boolean stripDuplicateProfileRows(String pluginName) {
        try {
            java.io.File pf = rootfsFile("root/.dsh/profiles/web/cordis.patch.yml");
            if (!pf.isFile()) return false;
            java.io.File bundlePatch = rootfsFile("root/.dsh/profiles/web/node_modules/"
                    + pluginName + "/cordis.patch.yml");
            if (!bundlePatch.isFile()) return false;      // 没有第二份注册，别乱删
            String txt = new String(java.nio.file.Files.readAllBytes(pf.toPath()),
                    StandardCharsets.UTF_8);
            String neu = PatchYaml.stripBlocksContaining(txt, pluginName);
            if (neu == null) return false;
            java.nio.file.Files.write(pf.toPath(), neu.getBytes(StandardCharsets.UTF_8));
            android.util.Log.i("DSHA", "清掉 profile 里重复注册 " + pluginName + " 的行");
            return true;
        } catch (Throwable t) {
            android.util.Log.w("DSHA", "清重复 profile 注册失败（不致命）: " + t);
            return false;
        }
    }

    /** dsh-web-mobile 改名迁移（原 {@code @dsh-external/dsh-mobile-nav}）。
     *
     *  <p>上游 2026-08-30 把包名换成 dsh-web-mobile，旧 npm 名整包撤下（issue #40）。
     *  我们是复制安置、不走 npm，撤包本身不影响装得上；但升级之后新旧两个名字会各占一个
     *  实体目录、各注册一行 patch —— 同一个插件加载两遍，行为叠加，界面上会出现重复元素。
     *
     *  <p>清掉旧的三样：实体目录、安置标记、profile 依赖里的旧键。patch 行的源头就是实体
     *  目录里的 cordis.patch.yml，目录一删，dsh 下次 reconcile 那行自然消失，不用碰 YAML。
     *
     *  <p>删目录走 DataPreserve.deleteRecursively（不跟随软链）—— 内置插件是软链安置过的，
     *  用跟随软链的删法会顺着链接删到别处去，那个坑在 uninstall 兜底路径上踩过一次。 */
    private void migrateMobileNavRename() {
        try {
            java.io.File old = rootfsFile("root/dsha-mobile-nav");
            java.io.File mark = rootfsFile("root/dsha-mobile-nav-installed");
            boolean had = old.exists() || mark.exists();
            if (old.exists()) DataPreserve.deleteRecursively(old);
            if (mark.exists()) mark.delete();
            // node_modules 里的旧实体也必须清 —— dsh 扫的是这里，不是 /root 下那个实体目录。
            // 留着就会新旧两份同时加载，现象是
            //   locale namespace "mobileNav" already has locale "zh"
            // （两份插件都往同一个 locale 命名空间注册，后一个直接报冲突）。
            // 上一版只删了 /root/dsha-mobile-nav 与 package.json 里的依赖键，漏了这一层。
            java.io.File nm = rootfsFile("root/.dsh/profiles/web/node_modules");
            java.io.File[] oldEntities = {
                    new java.io.File(nm, "@dsh-external/dsh-mobile-nav"),
                    new java.io.File(nm, "dsh-mobile-nav"),
            };
            for (java.io.File d : oldEntities) {
                if (d.exists()) {
                    DataPreserve.deleteRecursively(d);
                    had = true;
                }
            }
            // pnpm 把实体放在 .pnpm/ 下、外面只是链接，那份副本同样要清
            java.io.File pnpmDir = new java.io.File(nm, ".pnpm");
            if (pnpmDir.isDirectory()) {
                java.io.File[] kids = pnpmDir.listFiles();
                if (kids != null) {
                    for (java.io.File k : kids) {
                        if (k.getName().contains("dsh-mobile-nav")) {
                            DataPreserve.deleteRecursively(k);
                            had = true;
                        }
                    }
                }
            }
            if (stripOldPatchRow()) had = true;
            // 新名字的重复注册：dsh 自己往 profile 写的行用随机 id，上面那个函数按
            // id 匹配抓不到。真机症状是插件加载失败并报 locale 命名空间冲突。
            if (stripDuplicateProfileRows("dsh-web-mobile")) {
                had = true;
                logActivity("清掉了 profile 里重复注册 dsh-web-mobile 的一行 —— "
                        + "bundle 自己已经注册过，两份同时加载会报 locale 命名空间冲突");
            }
            java.io.File pf = rootfsFile("root/.dsh/profiles/web/package.json");
            if (pf.isFile()) {
                String txt = new String(java.nio.file.Files.readAllBytes(pf.toPath()),
                        StandardCharsets.UTF_8);
                org.json.JSONObject root = new org.json.JSONObject(txt);
                org.json.JSONObject deps = root.optJSONObject("dependencies");
                if (deps != null && deps.has("@dsh-external/dsh-mobile-nav")) {
                    deps.remove("@dsh-external/dsh-mobile-nav");
                    java.nio.file.Files.write(pf.toPath(),
                            root.toString(2).getBytes(StandardCharsets.UTF_8));
                    had = true;
                }
            }
            if (had) {
                logActivity("已清理旧 dsh-mobile-nav 残留 —— 上游把它改名为 dsh-web-mobile，"
                        + "新旧并存会让同一个插件加载两遍");
            }
        } catch (Throwable t) {
            android.util.Log.w("DSHA", "dsh-web-mobile 改名迁移失败（不致命）: " + t);
        }
    }

    /** 延迟一段时间再排进 IO 队列。
     *
     *  <p>维护类任务（自动备份这种）不该和启动抢那唯一的 IO 线程 —— 直接 IO.execute
     *  会让用户点了启动之后干等它做完。用独立线程只负责等，等完再交给 IO，
     *  这样既保留了「容器操作串行」的语义，又不占启动的位置。
     */
    private void ioLater(long delayMs, Runnable task) {
        Thread t = new Thread(() -> {
            try {
                Thread.sleep(delayMs);
            } catch (InterruptedException e) {
                return;
            }
            try {
                IO.execute(task);
            } catch (Throwable ignored) {
            }
        }, "dsha-io-later");
        t.setDaemon(true);
        t.start();
    }

    /** 一次性修正：把被旧逻辑误切到 proot 的用户切回 proroot。
     *
     *  <p>旧版本把「Web 60 秒端口未就绪」直接记成 proroot 的失败，连续 3 次就永久切到
     *  proot。但超时的根因绝大多数跟容器运行时无关 —— profile 装不起来（#46 的 semver）、
     *  key 没填、插件报错卡住加载、磁盘满、首次装依赖被网络拖住 —— 换成 proot 同样起不来，
     *  于是用户白背一个慢 5~6 倍的运行时，真正的毛病一点没修。
     *
     *  <p>判据用「上次降级的原因里带『超时』」：那是旧逻辑的措辞。运行时探针
     *  （checkRuntimeHealthAndHeal）判定的确定性不兼容不会长这样，那种降级是对的，不动。
     *  手动在设置页选 proot 的用户也不会有这个原因，不会被覆盖。 */
    private void repairProrootDowngradeOnce() {
        try {
            if (prefs.getBoolean("proroot_downgrade_repaired", false)) return;
            prefs.edit().putBoolean("proroot_downgrade_repaired", true).apply();
            if (!"proot".equals(prefs.getString("container_runtime", "proroot"))) return;
            String why = prefs.getString("proroot_last_error", "");
            if (!why.contains("超时")) return;
            prefs.edit().putString("container_runtime", "proroot")
                    .putInt("proroot_fail_streak", 0).apply();
            logActivity("已把容器运行时切回 proroot：上次降级的原因是「" + why
                    + "」，而 Web 启动超时通常不是 proroot 的问题（旧版本会误判）");
        } catch (Throwable ignored) {
        }
    }

    /** 运行文件与守卫这三步（ensureRuntimeFiles / ensureDangerGuard / ensureBashGuardPatch）
     *  都是幂等写文件，常态下只是逐个确认「已经在了」—— 真机实测合计 364~393ms，
     *  是现在启动路径上唯一剩下的同步大项。
     *
     *  <p>判据：rootfs 里的标记文件内容等于当前 BUILTIN_ASSET_VERSION，且最关键的产物
     *  dsh-guard.sh 确实在。改了 assets 就要升 BUILTIN_ASSET_VERSION（本来就是约定），
     *  那时标记对不上、照旧全做一遍；重装 rootfs 时标记跟着消失，也会重做。 */
    private boolean runtimeFilesFresh() {
        try {
            java.io.File marker = rootfsFile("root/.dsha-runtime-stamp");
            if (!marker.isFile()) return false;
            String v = new String(java.nio.file.Files.readAllBytes(marker.toPath()),
                    java.nio.charset.StandardCharsets.UTF_8).trim();
            if (!BUILTIN_ASSET_VERSION.equals(v)) return false;
            // 标记在但 guard 脚本被删了（用户手动清理过）→ 不能信标记
            return rootfsFile("root/dsh-guard.sh").isFile();
        } catch (Throwable t) {
            return false;
        }
    }

    private void markRuntimeFilesFresh() {
        try {
            java.io.File marker = rootfsFile("root/.dsha-runtime-stamp");
            java.nio.file.Files.write(marker.toPath(),
                    BUILTIN_ASSET_VERSION.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        } catch (Throwable ignored) {
        }
    }

    /** 会话目录的现状指纹：目录 mtime + 直接子项个数。空串＝拿不到，那就照扫不敢跳。 */    private String sessionsStamp() {
        try {
            java.io.File d = rootfsFile("root/.dsh/sessions");
            if (!d.isDirectory()) return "";
            String[] kids = d.list();
            return d.lastModified() + ":" + (kids == null ? -1 : kids.length);
        } catch (Throwable t) {
            return "";
        }
    }

    /** 上次真的扫过会话的时刻 —— 用来挡住同一次启动里的重复调用。 */
    private volatile long lastSessionHealAt = 0;

    private void doHealSessionCorruption() {
        // 这个方法有三个入口：MainActivity 打开 App 时、startWeb 的同步段、启动线程里。
        // 真机日志是一次启动跑三遍（01:20:09 / :11 / :11），每遍都返回
        // SESSION_HEALED_NONE，而且每遍开头都要停 Web、等端口关透 —— 纯白等。
        // 同一次启动扫一遍就够：60 秒内的重复调用直接跳过，不丢「每次开 App 扫一次」的能力。
        long nowMs = System.currentTimeMillis();
        if (nowMs - lastSessionHealAt < 60_000) return;
        lastSessionHealAt = nowMs;
        // 停 Web 之前先问一句「有没有必要」。
        //
        // 下面第一步就是停 Web + 等端口关透，然后起 python 全量扫一遍（1~3 秒）。
        // 而 IO 是**单线程**池 —— 用户打开 App（MainActivity 会调这里）之后马上点启动，
        // 启动的活就排在这后面白等，真机体感正是「点了三秒没反应」。
        // 会话目录没动过就没有再扫一遍的理由，直接返回：不停 Web、不起 python。
        String sStamp = sessionsStamp();
        if (!sStamp.isEmpty() && sStamp.equals(prefs.getString("sessions_heal_stamp", ""))) {
            return;
        }
        healingSession = true;
        try {
            if (!proot.isInstalled()) return;
            // 先停 Web / 等端口关透，防止修复期间 dsh 进程继续写同一会话文件
            try {
                if (isWebPortUp(300)) {
                    destroyAllWebProcesses();
                    proot.execAndRead(stopWebCommand());
                    waitPortClosed(5000);
                }
            } catch (Throwable ignored) {
            }
            String script = readAsset("heal-session.sh");
            if (script == null || script.isEmpty()) return;
            java.io.File f = new java.io.File(proot.getRootfsDir(), "root/dsha-heal-session.sh");
            f.getParentFile().mkdirs();
            java.nio.file.Files.write(f.toPath(), script.getBytes(StandardCharsets.UTF_8));
            // 注入 Python 自愈主程序（os.walk 扫描，不依赖 find/bash glob）
            String healPy = readAsset("heal-sessions.py");
            if (healPy != null && !healPy.isEmpty()) {
                java.io.File hp = new java.io.File(proot.getRootfsDir(), "root/.dsh/heal-sessions.py");
                if (hp.getParentFile() != null) hp.getParentFile().mkdirs();
                java.nio.file.Files.write(hp.toPath(), healPy.getBytes(StandardCharsets.UTF_8));
            }
            // 注入 zstandard 离线 wheel（在线安装的 rootfs 无 zstd、pip 可能没网时，
            // heal 用它 --no-index 本地装，保证能解压修复会话）
            try {
                String wheelName = "zstandard-0.25.0-cp312-cp312-manylinux_2_17_aarch64.whl";
                byte[] wheel = readAssetBytes(wheelName);
                if (wheel != null && wheel.length > 0) {
                    java.io.File whl = new java.io.File(proot.getRootfsDir(), "root/.dsh/" + wheelName);
                    if (whl.getParentFile() != null) whl.getParentFile().mkdirs();
                    java.nio.file.Files.write(whl.toPath(), wheel);
                }
            } catch (Throwable ignored) {
            }
            String r = proot.execAndRead("bash /root/dsha-heal-session.sh; rm -f /root/dsha-heal-session.sh");
            logHealResult(r);
        } catch (Throwable ignored) {
        } finally {
            // 记下这次扫过的会话现状，下次同样的现状就不必再扫（见方法开头那道门槛）。
            // 放在 finally 里意味着「扫到一半失败」也会记 —— 取舍是这样：会话损坏本来罕见，
            // 而 mtime 或子项数一变还是会重扫，60 秒去重窗口过后手动重启也会重扫，
            // 漏掉一次的代价远小于每次启动都白付 1~3 秒。
            prefs.edit().putString("sessions_heal_stamp", sessionsStamp()).apply();
            healingSession = false;
        }
    }

    /** 启动时对比步骤⑥版本标记（step6.version + builtin-assets.version）：
     *  任一与当前不符 → 自动重跑⑥（守卫/补丁/内置插件资产更新自动适配）。
     *  幂等、后台静默。注意：版本检查（execAndRead 起 proot）丢 IO 线程，不能在主线程跑。
     *  全新离线包（预置 marker 但无版本标记）首启也会重跑⑥——幂等无害，可接受
     *  （离线预置与在线⑥内容一致，重跑只是把版本标记补齐）。 */
    public void maybeRefreshStep6() {
        IO.execute(() -> {
            try {
                if (!proot.isInstalled()) return;
                String r = proot.execAndRead(
                        "S=$(cat /root/.dsh/step6.version 2>/dev/null || echo NONE); "
                        + "A=$(cat /root/.dsh/builtin-assets.version 2>/dev/null || echo NONE); "
                        + "echo \"$S|$A\"");
                String want = STEP6_VERSION + "|" + BUILTIN_ASSET_VERSION;
                if (r != null && r.trim().equals(want)) return; // 版本一致
                android.util.Log.i("DSHA", "步骤⑥/资产版本变化（rootfs=" + (r == null ? "?" : r.trim())
                        + " 期望=" + want + "），自动重跑⑥");
                if (tryBeginBusy()) {
                    runInstallStep(STEP_GUARD);
                    setState("", 100, "已自动更新安全守卫与内置插件（⑥）", "", false);
                }
            } catch (Throwable e) {
                android.util.Log.w("DSHA", "自动重跑⑥失败（不影响使用）: " + e);
                setState("", 0, "", "", false);
            }
        });
    }

    /** 主动检测 dsh 新版本：已装版本 vs npm 最新 rc（dist-tags.next，24h 节流），
     *  npm 查询失败静默跳过（网络/镜像问题），版本比较只升不降。 */
    private String queryLatestDshRc() {
        try {
            // 节流：24h 内不重复查（避免每次启动都打 registry）。
            // 注意：只在「真正执行了 npm 查询」后更新时间戳——网络故障/命令失败
            // 时不更新，下次启动仍会重试（否则一次失败会哑 24h）。
            final SharedPreferences prefs =
                    appContext.getSharedPreferences("deepseekharness", android.content.Context.MODE_PRIVATE);
            long last = prefs.getLong("last_dsh_rc_check_ts", 0);
            if (System.currentTimeMillis() - last < 24L * 3600 * 1000) return null;
            // npmmirror 优先（国内快），失败回退官方源；只取 dist-tags.next（最新 rc）
            String r = proot.execAndRead(
                    "timeout 20 npm view @deepseek-ai/dsh dist-tags.next --registry=https://registry.npmmirror.com 2>/dev/null "
                    + "|| timeout 20 npm view @deepseek-ai/dsh dist-tags.next --registry=https://registry.npmjs.org 2>/dev/null");
            if (r == null || r.startsWith("ERROR") || r.contains("NONE")) return null;
            // 查询真正执行且有输出（哪怕没解析出 rc）→ 记时间戳
            prefs.edit().putLong("last_dsh_rc_check_ts", System.currentTimeMillis()).apply();
            String v = r.trim();
            // 返回完整版本（0.1.1-rc.2）——旧实现截成 rc.2 无法区分 0.1.0-rc.2 和
            // 0.1.1-rc.2（跨小版本同 rc 号会误判/漏判）。只认含 rc 的版本，防 stable。
            java.util.regex.Matcher m = java.util.regex.Pattern.compile(
                    "(\\d+\\.\\d+\\.\\d+-rc\\.\\d+)").matcher(v);
            return m.find() ? m.group(1) : null;
        } catch (Throwable e) {
            return null;
        }
    }

    /** rc 号比较：a > b 返回 true（只升不降，防镜像回退触发重装） */
    /** dsh 版本比较（完整版本，支持 0.1.0-rc.8 与 0.1.1-rc.2 这种跨小版本）。
     *  仅比较 rc 号会误判（rc.2 < rc.8 → 0.1.1-rc.2 被当旧版 → 不升级）！
     *  格式：<major>.<minor>.<patch>-rc.<n>，缺省段按 0。a > b 返回 true。 */
    private static boolean dshVersionNewer(String a, String b) {
        try {
            return dshVersionScore(a) > dshVersionScore(b);
        } catch (Exception e) {
            return false;
        }
    }

    /** 解析单个版本字符串（X.Y.Z-rc.N）为分数 */
    /** dsh 版本分数。实现在 {@link DshVersion} —— 那里能被纯逻辑断言覆盖，
     *  而这个判断的后果是「自动重装 dsh」，认错了就是把 0.1.3 降级成 0.1.2。 */
    private static long dshVersionScore(String v) {
        return DshVersion.score(v);
    }


    /** 启动时检测 dsh 新版本：主动查 npm 最新 rc，比已装新 → 自动【重装⑤+⑥】；
     *  兼容被动场景（离线包/手动重装导致已装版本变化 → 同样适配）。
     *  先装新版再适配，一气呵成；幂等、后台静默；失败不影响启动。 */
    public void maybeAutoReinstallGuardOnDshUpdate() {
        IO.execute(() -> {
            try {
                if (!proot.isInstalled()) return;
                final SharedPreferences prefs =
                        appContext.getSharedPreferences("deepseekharness", android.content.Context.MODE_PRIVATE);
                String installed = proot.execAndRead(
                        "command -v dsh >/dev/null 2>&1 && dsh --version 2>/dev/null | head -1 || echo NONE");
                if (installed == null || installed.contains("NONE") || installed.startsWith("ERROR")) return;
                String installedRc = installed.trim(); // 完整版本，如 0.1.1-rc.2
                String last = prefs.getString("last_dsh_rc", "");
                String target = null;
                // 1) 主动：npm 最新 rc > 已装 → 目标 = 最新（真正"检测到新版自动升级"）
                String latest = queryLatestDshRc();
                if (latest != null && dshVersionNewer(latest, installedRc)) {
                    target = latest;
                }
                // 2) 被动：已装版本比上次记录**更新**（离线包带新版/手动升级）→ 适配已装版本。
                //    只升不降：防离线包回退旧版触发降级重装（重装 @next 又升回去 → 反复重装）
                if (target == null && !last.isEmpty() && dshVersionNewer(installedRc, last)) {
                    target = installedRc;
                }
                if (target == null) {
                    // 首次检测只记录基线
                    if (last.isEmpty()) prefs.edit().putString("last_dsh_rc", installedRc).apply();
                    return;
                }
                // 升级前快照 + 记录目标版本
                if (!last.isEmpty()) prefs.edit().putString("prev_dsh_rc", last).apply();
                prefs.edit().putString("last_dsh_rc", target).apply();
                android.util.Log.i("DSHA", "检测到 dsh 新版本 " + installedRc + " → " + target + "，自动重装⑤+⑥");
                if (tryBeginBusy()) {
                    // ⑤：重装最新 RC（npm @next 跟随官方，npmmirror 同步滞后时回退官方源）
                    runInstallStep(STEP_HARNESS);
                    // ⑥：守卫/补丁/内置插件/极简preset 适配新版
                    runInstallStep(STEP_GUARD);
                    setState("", 100, "已自动升级 dsh 并完成适配（⑤+⑥）", "", false);
                }
            } catch (Throwable e) {
                android.util.Log.w("DSHA", "自动升级⑤+⑥失败（不影响使用）: " + e);
                setState("", 0, "", "", false);
            }
        });
    }

    /** 启动时全链路自动体检+自愈（打开即用，用户无感）：
     *  脚本注入→依赖→包装命令→连接，缺啥修啥；ADB 开关没开则跳过。 */
    public void maybeAdbSelfHeal() {
        try {
            if (!DeviceBridgeService.isAdbEnabled(appContext)) return; // 尊重开关
            if (!proot.isInstalled()) return;
            IO.execute(() -> {
                try {
                    // 1) 脚本版本不符 → 重注入
                    if (!AdbBridge.injected(proot)) {
                        AdbBridge.inject(appContext, proot);
                    }
                    // 2) wheels 缺失 → 注入
                    if (!AdbBridge.wheelsPresent(proot)) {
                        AdbBridge.injectWheels(appContext, proot);
                    }
                    // 3) 依赖/密钥/包装命令 任一缺失 → 完整 setup
                    if (!AdbBridge.keyPresent(proot) || !AdbBridge.depsOk(proot)
                            || !AdbBridge.wrapperPresent(proot)) {
                        String setup = AdbBridge.setup(proot);
                        android.util.Log.i("DSHA-ADB", "启动自愈 setup: " + setup);
                    }
                    // 4) 有密钥有依赖 → 探一次连接（失败交给看门狗周期重连）
                    if (AdbBridge.keyPresent(proot) && AdbBridge.depsOk(proot)) {
                        String r = proot.execAndRead("DSH_INTERNAL=" + HttpShellService.mintInternalTicket()
                                + " python3 /root/.dsh/adb-shell.py id 2>&1 | head -2");
                        if (r != null && r.contains("uid=")) {
                            android.util.Log.i("DSHA-ADB", "启动体检：ADB 连接正常");
                        } else {
                            android.util.Log.i("DSHA-ADB", "启动体检：未连接（看门狗将自动重连）" + r);
                        }
                    }
                } catch (Throwable e) {
                    android.util.Log.w("DSHA-ADB", "启动自愈异常（忽略）: " + e);
                }
            });
        } catch (Throwable ignored) {
        }
    }

    /** 启动计数自动备份：每启动 N 次触发一次自动备份（固定名自动覆盖上一个自动备份）。
     *  N 从配置项 auto_backup_launches 读取（默认 5，0=关闭）。
     *  与手动备份独立（手动每次保留时间戳文件）。幂等、后台执行、失败静默。
     *  计数器独立（backup_launch_count），不与其他启动计数功能（备份提醒）共用。 */
    public void maybeAutoBackupOnLaunch() {
        try {
            final SharedPreferences prefs =
                    appContext.getSharedPreferences("deepseekharness", android.content.Context.MODE_PRIVATE);
            final int interval = prefs.getInt("auto_backup_launches", 5);
            if (interval <= 0) return; // 配置为 0 = 关闭自动备份
            final int n = prefs.getInt("backup_launch_count", 0) + 1;
            prefs.edit().putInt("backup_launch_count", n).apply();
            if (n % interval != 0) return; // 每 N 次才备份
            // 备份要打 tar 包（几秒到几十秒），而 IO 是**单线程**池 —— 直接 IO.execute
            // 就是排在启动前面让用户干等。真机现场：03:17:45 点启动，03:17:48 才打完备份，
            // 03:17:52 会话自愈完，03:17:53 启动的活才真正开始，用户在「正在启动」上卡 12 秒。
            // 备份是维护任务，晚 45 秒做没有任何损失，那时 Web 早起来了、队列也空了。
            ioLater(45_000, () -> {
                try {
                    // 判据用 hasUserDataInDsh() 而不是「.dsh 目录存在」：全新环境里解压收尾
                    // 就会把 .dsh 建出来，老判据会把**空环境**打成一个"有效"备份。它是当次
                    // 安装写的（唯一可读），于是自动恢复反而会挑中它、覆盖掉本可手动恢复的
                    // 真数据 —— 比不备份更糟。
                    if (proot.isInstalled() && hasUserDataInDsh()) {
                        // 先把会话同步到公开镜像，再打备份包：两条恢复路径（镜像、备份）
                        // 更新于同一时刻，事后追查「哪份更新」时不必猜。
                        syncSessionMirror();
                        String p = BackupManager.backupToExternalAuto(appContext, HarnessController.this);
                        if (p != null) {
                            logActivity("第 " + n + " 次启动，已自动备份");
                        } else {
                            // 失败要说出来，而且**把计数退回去**：
                            // 计数在备份之前就加过了，不退的话这次失败要再等
                            // N 次启动才会重试，而用户一直以为「每 N 次自动备份」在生效。
                            prefs.edit().putInt("backup_launch_count", n - 1).apply();
                            String why = BackupManager.lastError();
                            logActivity("自动备份失败"
                                    + (why == null || why.isEmpty() ? "" : "：" + why)
                                    + " —— 下次启动会重试；也可到「工作区」页手动备份");
                        }
                    } else {
                        // 空环境跳过是正常的，但计数不该被这次白白消耗
                        prefs.edit().putInt("backup_launch_count", n - 1).apply();
                        android.util.Log.i("DSHA", "第 " + n + " 次启动：.dsh 里还没有用户数据，跳过自动备份");
                    }
                } catch (Throwable t) {
                    prefs.edit().putInt("backup_launch_count", n - 1).apply();
                    logActivity("自动备份异常：" + describe(t) + " —— 下次启动会重试");
                }
            });
        } catch (Throwable ignored) {
        }
    }

    // ================= 外部备份发现（分区存储下必须按「能否真的打开」判定） =================

    /** 一个候选备份包。uri 来自 MediaStore，file 是同名的直接路径 —— 两者都留着，
     *  读的时候 uri 优先、file 兜底（哪条通走哪条）。 */
    public static final class BackupCandidate {
        public final String name;
        public final android.net.Uri uri;   // MediaStore 条目，可空
        public final File file;             // 直接文件路径，可空
        public final long time;             // 修改时间（毫秒）
        public final long size;             // 字节数，0=未知

        BackupCandidate(String name, android.net.Uri uri, File file, long time, long size) {
            this.name = name;
            this.uri = uri;
            this.file = file;
            this.time = time;
            this.size = size;
        }

        /** 弹窗里给用户看的一行描述：时间 + 大小。
         *  自动备份用固定名 DSHA-backup-auto.tar.gz，MediaStore 同名去重又派生出
         *  "…auto.tar (3).gz" 这种孤儿包 —— 名字里没有时间戳，只给文件名用户没法判断
         *  这到底是哪一次的数据，所以时间和大小必须一起显示。 */
        public String describe() {
            StringBuilder sb = new StringBuilder(name);
            if (time > 0) {
                sb.append("\n备份时间：").append(new java.text.SimpleDateFormat(
                        "yyyy-MM-dd HH:mm", java.util.Locale.US).format(new java.util.Date(time)));
            }
            if (size > 0) {
                sb.append(size > 0 && time > 0 ? "　　" : "\n");
                sb.append("大小：");
                if (size >= 1024L * 1024L) {
                    sb.append(String.format(java.util.Locale.US, "%.1f MB", size / 1048576.0));
                } else {
                    sb.append(Math.max(1L, size / 1024L)).append(" KB");
                }
            }
            return sb.toString();
        }
    }

    /** 一次备份扫描的结果：最新且**确认可读**的候选，加上「发现了但读不了」的数量。
     *
     *  为什么非得区分「发现」和「可读」，以及为什么 total()==0 不等于「没有备份」：
     *  Android 11+ 分区存储下 App 查 MediaStore.Downloads 只能看到自己**这次安装**写的行，
     *  listFiles() 同样被整批过滤。卸载重装后 Download/DSHA 里旧包的 owner_package_name
     *  变成 NULL，于是对新安装**完全不可见** —— 真机实测（Android 16 / SDK 36）：未授予
     *  「所有文件访问」时扫描结果是 0 个；授予后同一台机同一批文件立刻变成「14 个，可读 14」。
     *  文件一直都在，是枚举这一步就看不见。
     *
     *  所以 0 个有两种截然不同的含义，调用方必须靠 canSeeAllFiles() 区分：
     *    真的没有备份            → 静默
     *    有备份但本次安装看不见  → 必须给出口（SAF 手动选择 / 让用户自己去开权限）
     *  否则空环境下就是「明明有备份却什么都不说」（issue #22 用户复现的正是这个）。 */
    public static final class BackupScan {
        public final BackupCandidate best;   // 最新且可读；一个都读不到时为 null
        public final int readable;
        public final int unreadable;

        BackupScan(BackupCandidate best, int readable, int unreadable) {
            this.best = best;
            this.readable = readable;
            this.unreadable = unreadable;
        }

        public int total() {
            return readable + unreadable;
        }
    }

    /** 本次安装能否枚举到「别的安装写进公共目录的文件」。
     *  API 30+ 要「所有文件访问」；30 以下没有分区存储那道墙，但
     *  WRITE_EXTERNAL_STORAGE 是运行时权限，没授予时同样什么都扫不到。
     *  唯一用途：把「扫描到 0 个」解释成「真的没有」还是「看不见」。 */
    private boolean canSeeAllFiles() {
        try {
            if (android.os.Build.VERSION.SDK_INT >= 30) {
                return android.os.Environment.isExternalStorageManager();
            }
            // 这里原先无条件返回 true —— 在 Android 8~10 上没授予存储权限时，
            // 会把「没权限所以看不见」误报成「公共目录里真的没有备份」。
            return appContext.checkSelfPermission(
                    android.Manifest.permission.WRITE_EXTERNAL_STORAGE)
                    == android.content.pm.PackageManager.PERMISSION_GRANTED;
        } catch (Throwable t) {
            return false;
        }
    }

    /** 备份文件名判据（宽容）。
     *
     *  必须容忍 MediaStore 的同名去重后缀：insert 同名条目不会覆盖，而是把 DISPLAY_NAME
     *  改成 "DSHA-backup-auto.tar (1).gz" —— 序号插在 .tar 和 .gz **中间**，于是
     *  endsWith(".tar.gz") 永远不成立，自动备份攒下的包会被整批筛掉。这正是
     *  「Download/DSHA 里明明有备份却从不弹恢复窗」的直接原因（issue #22）。 */
    static boolean looksLikeBackupName(String name) {
        if (name == null) return false;
        String low = name.toLowerCase(java.util.Locale.US);
        if (!low.startsWith("dsha-backup-") && !low.startsWith("dsha-migration-")) return false;
        // 去掉 " (1)" / "(2)" 这类去重后缀后再判扩展名
        String norm = low.replaceAll("\\s*\\(\\d+\\)", "");
        return norm.endsWith(".tar.gz") || norm.endsWith(".tgz") || norm.endsWith(".tar");
    }

    /** 候选是否真的能打开（只读 1 字节，开销可忽略）。isFile()/length() 都不算数，
     *  归属失效的文件这两项照样是真的，只有 open 会如实报 EACCES。 */
    private boolean canOpen(BackupCandidate c) {
        if (c.uri != null) {
            try (java.io.InputStream in = appContext.getContentResolver().openInputStream(c.uri)) {
                if (in != null && in.read() != -1) return true;
            } catch (Throwable ignored) {
            }
        }
        if (c.file != null) {
            try (java.io.InputStream in = new java.io.FileInputStream(c.file)) {
                if (in.read() != -1) return true;
            } catch (Throwable ignored) {
            }
        }
        return false;
    }

    /** 扫描 Download/DSHA：MediaStore 与直接列目录两路合并去重，逐个试开确认可读，
     *  返回最新可读的那个 + 不可读计数。 */
    public BackupScan scanExternalBackups() {
        final File dshaDir = new File(android.os.Environment.getExternalStoragePublicDirectory(
                android.os.Environment.DIRECTORY_DOWNLOADS), PublicDirs.ROOT);
        java.util.LinkedHashMap<String, BackupCandidate> found = new java.util.LinkedHashMap<>();
        // 1) MediaStore（Android 10+，不需要任何存储权限）
        if (android.os.Build.VERSION.SDK_INT >= 29) {
            try {
                android.net.Uri col = android.provider.MediaStore.Downloads.EXTERNAL_CONTENT_URI;
                String base = android.os.Environment.DIRECTORY_DOWNLOADS; // "Download"
                // 用 LIKE 一次覆盖 DSHA 及其所有子目录：备份现在写在 DSHA/存档/，
                // 而用户手机上已有的老备份在 DSHA/ 根下 —— 两处都得能找到，否则升级
                // 之后恢复列表会突然空掉。文件名判据（looksLikeBackupName）会把
                // 插件/下载子目录里的东西挡在外面。
                String sel = android.provider.MediaStore.MediaColumns.RELATIVE_PATH + " LIKE ?";
                String[] selArgs = new String[]{base + "/" + PublicDirs.ROOT + "%"};
                try (android.database.Cursor cur = appContext.getContentResolver().query(
                        col,
                        new String[]{android.provider.MediaStore.MediaColumns._ID,
                                android.provider.MediaStore.MediaColumns.DISPLAY_NAME,
                                android.provider.MediaStore.MediaColumns.DATE_MODIFIED,
                                android.provider.MediaStore.MediaColumns.SIZE,
                                android.provider.MediaStore.MediaColumns.RELATIVE_PATH},
                        sel, selArgs, null)) {
                    if (cur != null) {
                        while (cur.moveToNext()) {
                            String dn = cur.getString(1);
                            if (!looksLikeBackupName(dn)) continue;
                            android.net.Uri u = android.content.ContentUris.withAppendedId(
                                    col, cur.getLong(0));
                            // File 路径要按条目自己的 RELATIVE_PATH 拼，不能一律当成 DSHA 根 ——
                            // 存档子目录里的备份会被拼成不存在的路径，content:// 还能读，
                            // File 兜底那条路就断了。
                            String rel = cur.isNull(4) ? null : cur.getString(4);
                            File f = rel == null || rel.isEmpty()
                                    ? new File(dshaDir, dn)
                                    : new File(android.os.Environment.getExternalStorageDirectory(),
                                            rel + (rel.endsWith("/") ? "" : "/") + dn);
                            found.put(dn, new BackupCandidate(dn, u, f,
                                    cur.getLong(2) * 1000L, // DATE_MODIFIED 是秒
                                    cur.isNull(3) ? 0L : cur.getLong(3)));
                        }
                    }
                }
            } catch (Throwable ignored) {
            }
        }
        // 2) 直接列目录（Android 9-，或已授予「所有文件访问」的设备）：新目录 + 老目录
        for (String sub : PublicDirs.archiveSubdirs()) {
            try {
                File dir = sub.isEmpty() ? dshaDir : new File(dshaDir, sub);
                File[] fs = dir.listFiles();
                if (fs == null) continue;
                for (File f : fs) {
                    String n = f.getName();
                    if (!looksLikeBackupName(n) || found.containsKey(n)) continue;
                    found.put(n, new BackupCandidate(n, null, f, f.lastModified(), f.length()));
                }
            } catch (Throwable ignored) {
            }
        }
        // 3) 逐个试开：可读的参与「挑最新」，读不到的只计数
        BackupCandidate best = null;
        int readable = 0, unreadable = 0;
        for (BackupCandidate c : found.values()) {
            if (canOpen(c)) {
                readable++;
                if (best == null || c.time > best.time) best = c;
            } else {
                unreadable++;
            }
        }
        return new BackupScan(best, readable, unreadable);
    }

    /** 兼容旧调用：最新且可读备份的文件路径（只有 MediaStore 条目时可能为 null）。
     *  新代码请用 scanExternalBackups() —— 它还能告诉你有多少个读不到。 */
    public File findLatestExternalBackup() {
        BackupScan s = scanExternalBackups();
        return s.best == null ? null : s.best.file;
    }

    /** 恢复一个候选：content:// 优先（分区存储下唯一可靠的读法），File 路径兜底。 */
    public String restoreCandidate(BackupCandidate c) {
        if (c == null) return "没有可恢复的备份";
        if (c.uri != null) {
            String r = restoreFromUri(c.uri);
            if (r != null) return r;
        }
        if (c.file != null && c.file.isFile()) return restoreFromBackup(c.file);
        return "该备份无法读取（系统分区存储限制），请用「手动选择」重新指定文件";
    }

    /** 从 content:// 读备份并恢复。返回 null 表示这个 Uri 根本读不出来（权限/条目失效），
     *  调用方可以退回别的读法；非 null 一律是给用户看的结果文案。 */
    public String restoreFromUri(android.net.Uri uri) {
        File tmp = rootfsFile("root/.dsha-restore-src.tar.gz");
        try {
            if (tmp.getParentFile() != null) {
                //noinspection ResultOfMethodCallIgnored
                tmp.getParentFile().mkdirs();
            }
            // 打开阶段的失败要与恢复阶段区分开：openInputStream 抛 EACCES 时必须返回 null，
            // 让 restoreCandidate 还能去试 File 路径，别把「换条路就能成」说成恢复失败。
            java.io.InputStream in;
            try {
                in = appContext.getContentResolver().openInputStream(uri);
            } catch (Throwable e) {
                android.util.Log.w("DSHA", "备份 Uri 打不开: " + describe(e));
                return null;
            }
            if (in == null) return null;
            try (java.io.InputStream ins = in;
                 java.io.OutputStream out = new java.io.FileOutputStream(tmp)) {
                byte[] buf = new byte[65536];
                int n;
                while ((n = ins.read(buf)) != -1) out.write(buf, 0, n);
            }
            if (!tmp.isFile() || tmp.length() == 0) return "备份内容为空，无法恢复";
            return restoreFromBackup(tmp);
        } catch (Throwable e) {
            return "恢复失败: " + describe(e);
        } finally {
            //noinspection ResultOfMethodCallIgnored
            tmp.delete();
        }
    }

    /** 从外部备份 tar.gz 恢复：宽容优先——能恢复多少就恢复多少，绝不因一处不认识就整包失败。
     *
     *  流程：宽松解压到 stage → restore-merge.py 做布局识别 / 工作目录重映射 /
     *  本机路径插件重建 / bundle 预检（解析不了的先摘掉，保证 dsh web 能启动）。
     *  脚本不可用（老 rootfs 无 python3 等）时退回「整包直接铺到 /root」的老行为。
     *  兼容：.dsh 在包里任意层级、备份工作目录名与本机不同、无清单的老备份。 */
    public String restoreFromBackup(File backup) {
        try {
            if (!proot.isInstalled()) return "环境未就绪，请先完成环境解压/安装后再恢复";
            // ===== 恢复前体检（只读走一遍整个包）=====
            // 恢复用的是宽容解压（坏条目跳过继续），这对「个别文件损坏」是对的，但它会把
            // 「整个包被截断」掩盖成「恢复完成，N 个异常条目已跳过」—— 用户以为好了，
            // 其实少了一半会话。而备份被截断很常见：存储写满、云同步传一半、拷贝中断。
            // gzip 自带 CRC 与长度尾部，完整读一遍就能发现，且不需要额外的校验文件，
            // 所以对用户从别处拷来、通过 SAF 选中的包同样有效。
            BackupInspector.Info info = BackupInspector.inspect(backup);
            if (!info.readable) {
                return "这份备份不能用：" + info.error
                        + "\n\n换一份再试（「工作区」页可以手动选择备份文件），"
                        + "或者用当前环境重新导出一份。";
            }
            // 注意这里**不拒绝**：认不出特征只是提示。备份是用户自己选的文件，
            // 里面装着他的数据；为了一个启发式判据把一份其实能用的老备份拦在门外，
            // 比恢复一个可疑的包糟得多 —— 尤其人往往是丢了数据才来恢复的。
            // 真正会拒绝的只有上面那种「读都读不完整」，那种恢复只会得到半个环境。
            String shapeWarn = info.looksLikeDsha ? ""
                    : "\n· 注意：这个包里没找到 .dsh 之类的 DSHA 特征（共 " + info.entries
                            + " 个条目），仍然按你的选择恢复了。如果结果不对，"
                            + "换一份 Download/DSHA 下的 DSHA-backup-*.tar.gz 再试";
            File rootDir = new File(proot.getRootfsDir(), "root");
            File stage = new File(rootDir, ".dsha-restore-stage");
            deleteRecursively(stage);
            //noinspection ResultOfMethodCallIgnored
            stage.mkdirs();
            File tmp = rootfsFile("root/.dsha-restore.tar.gz");
            File src = backup;
            boolean copied = false;
            // 调用方可能已经把包放在 rootfs 内（工作区页的恢复）：同路径就别自己拷自己
            if (!backup.getAbsolutePath().equals(tmp.getAbsolutePath())) {
                copyFile(backup, tmp);
                src = tmp;
                copied = true;
            }
            try {
                TarGzipExtractor.extractLenient(src, stage);
            } finally {
                if (copied) {
                    //noinspection ResultOfMethodCallIgnored
                    tmp.delete();
                }
            }
            String script = readAsset("restore-merge.py");
            String out = null;
            if (script != null && !script.isEmpty()) {
                java.io.File sf = new java.io.File(rootDir, ".dsha-restore-merge.py");
                java.nio.file.Files.write(sf.toPath(), script.getBytes(StandardCharsets.UTF_8));
                out = proot.execAndRead("python3 /root/.dsha-restore-merge.py"
                        + " --stage /root/.dsha-restore-stage --root /root --workdir "
                        + ShellQuote.arg(getWorkdir())
                        // 范围兜底：脚本优先信包内清单，这里传的是从文件名推断的值，
                        // 只有「清单没生成」的包才用得上（调用方有时已把包改名成
                        // .dsha-restore.tar.gz，那时推断结果就是 full —— 与老行为一致）
                        + " --scope " + BackupScope.id(BackupScope.fromFileName(backup.getName()))
                        + " 2>&1; rm -f /root/.dsha-restore-merge.py", 240_000);
            }
            boolean ok = out != null && out.contains("RESTORE_OK");
            boolean partial = out != null && out.contains("RESTORE_PARTIAL");
            if (!ok && !partial) {
                // 兜底（宽容）：整理脚本没跑成 → 老行为，把包内容直接铺到 /root
                proot.execAndRead("cp -a /root/.dsha-restore-stage/. /root/ 2>/dev/null; "
                        + "rm -rf /root/.dsha-restore-stage; echo DONE");
                deleteRecursively(stage);
                syncApiKeyFromRootfs();
                return "恢复完成（基础模式：整包还原）\n"
                        + "若启动报插件缺失，可到「市场」重新安装该插件。刷新页面即可生效（多数插件热加载）";
            }
            String body = out.replace("RESTORE_OK", "").replace("RESTORE_PARTIAL", "")
                    .replace("RESTORE_EMPTY", "").trim();
            // 把体检结果摆出来：用户至少能判断「恢复进来的是不是我想要的那份」，
            // 而不是只看到一句「恢复完成」。跨版本时版本号尤其有用。
            body = "· 备份内容：" + info.sessionFiles + " 个会话文件 · 解压后约 "
                    + info.humanSize()
                    + (info.appVersion.isEmpty() ? "" : " · 来自 v" + info.appVersion)
                    + (info.hasManifest ? "" : "（老格式备份，无清单）")
                    + shapeWarn
                    + (body.isEmpty() ? "" : "\n" + body);
            // 解压阶段跳过的异常条目也如实告知（宽容 ≠ 悄悄丢东西）
            if (TarGzipExtractor.lastSkipped > 0) {
                body += "\n· 备份里有 " + TarGzipExtractor.lastSkipped
                        + " 个异常条目已跳过：" + TarGzipExtractor.lastSkipNote;
            }
            // 缺失插件：后台静默补装（不阻塞恢复结果返回，失败无感）。
            // 已弃用的插件不在补装范围内 —— 老备份的 profile 里还注册着旧内置 UI 适配，
            // 重装后它的 link: 源码不存在，整理脚本会把它报成「可自动补装」。
            java.util.List<String> missing = parseMissingPlugins(out);
            body = stripMachineLines(body);
            if (!missing.isEmpty()) {
                java.util.List<String> skipped = autoInstallPluginsSilently(missing);
                int willInstall = missing.size() - skipped.size();
                if (willInstall > 0) {
                    body += "\n· " + willInstall + " 个插件正在后台补装，装好后重启 WebUI 即回到启用状态";
                }
                for (String s : skipped) {
                    body += "\n· 未补装 " + s + "：" + DeprecatedPlugins.reason(s);
                }
            }
            deleteRecursively(stage);
            // 恢复会把整个 .dsh 换成实体目录（restore-merge.py 里 move_aside + move），
            // 指向公开目录的软链因此丢失 —— 数据分裂成「.dsh 里的新数据」和
            // 「Documents/dshdata 里的旧数据」，且没人指向后者。
            // 跑一次迁移把它归位：脚本的冲突分支会保留旧公开副本为 .conflict-<时间>，
            // 用刚恢复的数据覆盖，不会静默删任何一边。
            try {
                String mig = runAssetScript("migrate-public-data.sh", "dsha-migrate-public.sh", 60_000);
                if (mig != null && mig.contains("conflict-")) {
                    logActivity("恢复后公开数据归位：发现冲突，旧公开副本已存为 .conflict-*");
                }
            } catch (Throwable ignored) {
            }
            // ===== 恢复之后必须重跑的自愈 =====
            // 这一组的共同前提：**恢复会把环境改回一个旧状态，但不会改动任何
            // 「已经处理过」的标记**。于是所有「按版本/时间戳门槛决定要不要跑」的
            // 自愈逻辑都会认为无事可做，而恰恰是恢复引入了需要它们处理的状态。
            // 已经为此付过三次代价（同一个形状）：
            //   · 恢复带回指向公开目录的软链，目标不可达 → dsh 对它 mkdir 得到 ENOENT
            //     → 插件树加载失败、完全起不来；
            //   · 恢复带回旧内置插件实体（node_modules 里那份），与新版同时加载
            //     → locale namespace "mobileNav" already has locale "zh"；
            //   · 恢复带回旧 patch 标记 → 补丁按「已是最新」跳过，实际没打上。
            // 所以这里不看任何门槛，无条件重跑一遍。都是幂等的。
            try {
                ensureSessionHome();          // sessions 落点 + 三项软链体检
            } catch (Throwable ignored) {
            }
            try {
                // 清掉 patch 时间戳，下次启动重打一遍补丁（恢复很可能带回了旧的 profile）
                prefs.edit().remove("patch_stamp").apply();
            } catch (Throwable ignored) {
            }
            try {
                ensureBuiltinPluginsReady();  // 含 migrateMobileNavRename：清旧插件实体
            } catch (Throwable ignored) {
            }
            invalidateSteps();
            // ===== 老备份 → 新版本的适配（恢复流程自己做，别指望「下次启动 Web 时自愈」）=====
            // 备份里的 profiles/web/package.json 是**备份那个版本**的：可能还注册着已经换掉的
            // 内置插件（旧 UI 适配 dsh-client-ui-mobile-adapt），也不会有新内置插件的声明。
            // restore-merge.py 的 bundles 预检会把解析不到的摘掉，但「补回新内置插件」和
            // 「按新版语义下线旧插件」是 App 侧的事，而它们只挂在启动/安装路径上 ——
            // 恢复发生在 App 已经启动之后，不显式跑一遍的话得等用户重启 Web 才对齐。
            // 两个都是幂等且秒级的，直接在这里做完。
            try {
                migrateLegacyMobileAdapt();
                ensureBuiltinBundles();
            } catch (Throwable t) {
                android.util.Log.w("DSHA", "恢复后内置插件适配失败（重启 Web 时会再试）: " + t);
            }
            // 老备份里带着**备份那台机器**的 .bridge_token，恢复出来就和 App 内存里的
            // 不一致 —— WebUI 会弹「需要 token，请在 DSHA 应用内打开」。删掉重新生成。
            HttpShellService.resetTokenAfterRestore();
            // issue #22：恢复数据落位后回读备份里的 .dsha-apikey 并回填配置页——
            // 否则离线包用户（无 .env）走自动/迁移恢复后配置页 key 为空、启动注入空 key。
            String keyState = syncApiKeyFromRootfs();
            if ("undecryptable".equals(keyState)) {
                // 解不开不是错误，但必须说出来 —— 否则用户拿着一个「看起来已填」的
                // 配置去对话，只会收到查不出原因的鉴权失败
                body += "\n· 备份里的 API key 无法解密（换了设备或清过 App 数据），"
                        + "请到「配置」页重新填写";
            } else if ("store_failed".equals(keyState)) {
                body += "\n· API key 已从备份读出，但 Android Keystore 不可用，未以明文保存；"
                        + "请稍后到「配置」页重新填写";
            }
            return (ok ? "恢复完成" : "恢复完成（部分内容已跳过，详见下方）")
                    + (body.isEmpty() ? "" : "\n" + body)
                    + "\n刷新页面即可生效（多数插件热加载）";
        } catch (Exception e) {
            return "恢复失败: " + e.getMessage();
        }
    }

    /** 【自检项】容器运行时兼容性 —— 跑一次**不依赖 python** 的探针，
     *  命中致命问题就当场切回 proot。返回拼进自检报告的一段文本。
     *
     *  <p>为什么这一项必须在 App 侧而不是写进 selftest.py：selftest.py 本身是 python，
     *  而这类故障恰恰让容器里所有 python 一启动就 abort —— 最该报告问题的工具会是
     *  第一个受害者，用户只会看到一屏寄存器。探针只用 shell 与 /bin/true，坏环境下也跑得完。
     *
     *  <p>命中就直接切回，不走 noteProrootFailure 的「连续 3 次」计数：那是给偶发失败
     *  设计的，而这里是确定性的不兼容，重试多少次结果都一样。 */
    public String checkRuntimeHealthAndHeal() {
        try {
            String out = proot.execAndRead(RuntimeHealth.probeScript(), 30_000);
            RuntimeHealth.Probe p = RuntimeHealth.parse(out);
            if (p.healthy()) {
                return "【容器运行时兼容性】✅ " + p.reason + "\n\n";
            }
            StringBuilder sb = new StringBuilder();
            sb.append("【容器运行时兼容性】❌ 不兼容\n  ").append(p.reason).append("\n");
            String cur = prefs.getString("container_runtime", "proroot");
            if ("proroot".equals(cur)) {
                prefs.edit().putString("container_runtime", "proot")
                        .putInt("proroot_fail_streak", 0)
                        .putString("proroot_last_error", p.reason)
                        .apply();
                sb.append("  已自动切回 proot 运行时。").append(p.advice).append("\n");
                logActivity("自检发现容器运行时不兼容，已切回 proot：" + p.reason);
            } else {
                sb.append("  当前已经是 proot 却仍然不兼容 —— 这超出自动修复范围，"
                        + "请把这份报告发给开发者。\n");
                logActivity("自检发现 proot 下也不兼容：" + p.reason);
            }
            return sb.append("\n").toString();
        } catch (Throwable t) {
            // 探针自己没跑成不代表环境坏了，别据此下结论
            return "【容器运行时兼容性】⚠ 探针未能执行：" + describe(t) + "\n\n";
        }
    }

    /** issue #22：把 rootfs 里恢复出的 .dsha-apikey 回填到 App 配置页（与 WorkspaceFragment.doRestore 的手动恢复一致）。
     *  .env 只在「在线安装」最后一步写，离线包用户恢复后没有它，key 在备份的 .dsh/.dsha-apikey 里。
     *
     *  @return 空串 = 备份里没有 key（或不像 key）；{@code "ok"} = 已回填；
     *          {@code "undecryptable"} = 有但解不开；{@code "store_failed"} = 读到了但无法安全保存。 */
    private String syncApiKeyFromRootfs() {
        try {
            String k = proot.execAndRead("cat /root/.dsh/.dsha-apikey 2>/dev/null");
            if (k == null) return "";
            k = k.trim();
            if (k.isEmpty() || k.contains(" ") || k.length() < 8) return "";
            // 过去这里是 setApiKey(k) —— 直接把**密文**写进了配置。
            // 备份时是加过密的（encryptKeyForBackup），不解密就等于把
            // base64(iv):base64(ct) 当成 key export 给 dsh，对话必然鉴权失败，
            // 而配置页看着「已填」，用户完全无从判断。
            String plain = decryptKeyFromBackup(k);
            if (plain == null) {
                android.util.Log.w("DSHA", "备份里的 API key 解不开（换机或 Keystore 重置）—— 不回填，等用户重填");
                return "undecryptable";
            }
            if (!setApiKey(plain)) {
                android.util.Log.w("DSHA", "恢复后 API key 无法安全保存（Android Keystore 不可用）");
                return "store_failed";
            }
            android.util.Log.i("DSHA", "恢复后已从 .dsha-apikey 回填 API key（issue #22）");
            return "ok";
        } catch (Throwable ignored) {
            return "";
        }
    }

    /** 从整理脚本输出里取出可自动补装的插件名（机器可读行 `MISSING_PLUGINS: a,b`） */
    private static java.util.List<String> parseMissingPlugins(String out) {
        java.util.List<String> list = new java.util.ArrayList<>();
        if (out == null) return list;
        for (String line : out.split("\n")) {
            String s = line.trim();
            if (!s.startsWith("MISSING_PLUGINS:")) continue;
            for (String raw : s.substring("MISSING_PLUGINS:".length()).split(",")) {
                String name = raw.trim();
                if (!name.isEmpty() && PluginController.isValidPluginSpec(name) && !list.contains(name)) {
                    list.add(name);
                }
            }
        }
        return list;
    }

    /** 去掉给程序看的行，只留给用户看的报告正文 */
    private static String stripMachineLines(String body) {
        if (body == null || body.isEmpty()) return "";
        StringBuilder sb = new StringBuilder();
        for (String line : body.split("\n")) {
            if (line.trim().startsWith("MISSING_PLUGINS:")) continue;
            if (sb.length() > 0) sb.append('\n');
            sb.append(line);
        }
        return sb.toString().trim();
    }

    /** 恢复后台静默补装缺失插件：逐个 dsh plugin add，不弹窗不打扰；
     *  结果写 logcat 与 .dsh/restore-report.txt。装不上也不影响已恢复的数据。
     *
     *  <p><b>已弃用的插件一律不装</b>（{@link DeprecatedPlugins}）——「程序替用户装」
     *  和「用户自己点安装」是两回事，前者不该把一个已经下线的插件带回来。老备份里
     *  注册着旧内置 UI 适配（{@code link:/root/dsha-mobile-adapt}），重装后那个实体
     *  不存在，restore-merge.py 就把它报成「可自动补装」——装回来正好和新内置插件打架。
     *  返回被跳过的名字，调用方据此告诉用户为什么没装。
     *
     *  <p>收尾除了 {@code ensureBuiltinBundles()} 还要再跑一次
     *  {@code migrateLegacyMobileAdapt()}：那个方法是「看到痕迹才动手」的收敛型逻辑，
     *  而这里是并发线程 —— 主线程调它的时候补装往往还没落地，它看到干净环境就退出了，
     *  等于白跑。装完再收敛一次，才能兜住清单没覆盖到的情况。 */
    private java.util.List<String> autoInstallPluginsSilently(final java.util.List<String> names) {
        final java.util.List<String> skipped = new java.util.ArrayList<>();
        final java.util.List<String> todo = new java.util.ArrayList<>();
        for (String name : names) {
            if (DeprecatedPlugins.isDeprecated(name)) {
                skipped.add(name);
                android.util.Log.i("DSHA", "已弃用插件不补装：" + name);
            } else {
                todo.add(name);
            }
        }
        if (todo.isEmpty() && skipped.isEmpty()) return skipped;
        Thread t = new Thread(() -> {
            StringBuilder log = new StringBuilder("== 后台补装插件 ==\n");
            for (String name : skipped) {
                log.append("– ").append(name).append("：已弃用，未补装（")
                        .append(DeprecatedPlugins.reason(name)).append("）\n");
            }
            int ok = 0;
            for (String name : todo) {
                try {
                    String r = installPlugin(name);
                    boolean good = r != null && r.contains("INSTALL_EXIT=0");
                    if (good) ok++;
                    log.append(good ? "✓ " : "✗ ").append(name).append("：")
                            .append(r == null ? "无输出" : tail(r.trim(), 200)).append('\n');
                } catch (Throwable e) {
                    log.append("✗ ").append(name).append("：").append(describe(e)).append('\n');
                }
            }
            log.append("小结：成功 ").append(ok).append('/').append(todo.size());
            if (!skipped.isEmpty()) log.append("，跳过已弃用 ").append(skipped.size()).append(" 个");
            log.append('\n');
            android.util.Log.i("DSHA", "恢复后补装插件完成 " + ok + "/" + todo.size()
                    + (skipped.isEmpty() ? "" : "，跳过已弃用 " + skipped.size()));
            try {
                migrateLegacyMobileAdapt(); // 补装落地后再收敛一次（见方法注释）
                ensureBuiltinBundles();     // 顺带把内置插件校验补回
            } catch (Throwable ignored) {
            }
            try {
                java.io.File rp = rootfsFile("root/.dsh/restore-report.txt");
                if (rp.getParentFile() != null && rp.getParentFile().isDirectory()) {
                    java.nio.file.Files.write(rp.toPath(),
                            log.toString().getBytes(StandardCharsets.UTF_8),
                            java.nio.file.StandardOpenOption.CREATE,
                            java.nio.file.StandardOpenOption.APPEND);
                }
            } catch (Throwable e) {
            android.util.Log.w("DSHA", "恢复报告写入失败，用户将看不到恢复结果: " + e);
        }
        }, "dsha-restore-plugins");
        t.setDaemon(true);
        t.start();
        return skipped;
    }

    /** 取字符串尾部 n 个字符（日志用，避免把整段安装输出塞进报告） */
    private static String tail(String s, int n) {
        if (s == null) return "";
        String one = s.replace("\n", " ");
        return one.length() <= n ? one : "…" + one.substring(one.length() - n);
    }

    /**
     * .dsh 里是否已经有「用户数据」。
     *
     * 不能拿「root/.dsh 目录存在」当判据 —— 解压收尾时 ProotBootstrap.writeOfflineVersion()
     * 自己就会写出 root/.dsh/offline-rootfs.version 把这个目录建起来（离线包预置的
     * profiles/web、步骤⑥ 的 step6.version / builtin-assets.version 同理），而这些都发生在
     * MainActivity 判定之前。于是全新环境里 root/.dsh 永远「已存在」，自动恢复弹窗被自己的
     * 前置步骤挡死 —— 这就是 issue #22「卸载重装后从不弹『检测到旧版备份』」的根因。
     *
     * 判据改成只认用户数据本体：会话、存储、配置、凭据。纯版本标记文件不算数据；
     * profiles/ 也故意不算 —— 它可能由同一次启动里的 ⑥ 自愈 / 内置插件校验并发创建。
     */
    private boolean hasUserDataInDsh() {
        try {
            File dsh = rootfsFile("root/.dsh");
            if (!dsh.isDirectory()) return false;
            // 凭据文件实际叫 .credentials.yaml（老版本可能是 .credentials）—— 两个都探，
            // 只写前者会漏判。
            String[] probes = {"sessions", "storages", "settings.yaml", "settings.json",
                    ".credentials.yaml", ".credentials", ".dsha-apikey"};
            for (String p : probes) {
                File f = new File(dsh, p);
                if (f.isDirectory()) {
                    String[] kids = f.list();
                    if (kids != null && kids.length > 0) return true;
                } else if (f.isFile() && f.length() > 0) {
                    return true;
                }
            }
            return false;
        } catch (Throwable t) {
            // 判不出来时按「已有数据」处理：宁可少弹一次窗，也不冒险覆盖用户数据
            return true;
        }
    }

    /**
     * 全新环境检测：rootfs 已就绪但 .dsh 里还没有用户数据（卸载重装后的空环境），
     * 且 Download/DSHA 有旧备份 → 弹窗询问是否恢复。
     * 仅在 rootfs 已就绪后由调用方触发（避免解压前误弹）。
     *
     * 四种分支（分区存储决定了必须这么分）：
     *   有可读候选              → 正常问「是否恢复 xxx」（带时间+大小）；若同时有读不到的，说明并给「手动选择」
     *   只有读不到的候选        → 不给必然失败的「恢复」，只给「手动选择」
     *   0 个候选 + 无全文件权限 → **不能静默**：文件可能就在那儿只是看不见，给「手动选择」+「开启文件访问」
     *   0 个候选 + 有全文件权限 → 真的没有备份，静默（日志留痕）
     *
     * 关于权限：不主动申请 MANAGE_EXTERNAL_STORAGE。它要跳系统设置手动拉开关，而绝大多数
     * 用户（全新安装、没有任何备份）根本不需要它，部分 ROM 还会拦。只在确实可能有备份、
     * 且自动发现已经失败时，把入口交给用户自己点。
     */
    /** 恢复弹窗是否正在走流程。
     *
     *  PR#33 的恢复弹窗和 rc83 加的「所有文件访问」申请弹窗要的是**同一个权限**，
     *  只是理由不同（一个为发现备份，一个为数据不随卸载丢）。两者一个挂在
     *  onCreate、一个挂在 onResume，首启时会叠在一起弹。
     *
     *  这里让恢复弹窗优先 —— 用户刚重装，恢复数据比迁移目录紧急。
     *  权限弹窗看到这个标志就本轮让路，并且**不消耗** asked_all_files
     *  （否则「只问一次」的额度会被这次让路白白用掉）。
     *  恢复流程结束时回调 MainActivity 补问一次。 */
    public static volatile boolean restoreFlowActive = false;

    /** 恢复流程收尾：清标志，并让 MainActivity 有机会补问权限。 */
    private static void endRestoreFlow(final android.app.Activity act) {
        restoreFlowActive = false;
        try {
            if (act instanceof MainActivity) {
                ((MainActivity) act).recheckAllFilesAccess();
            }
        } catch (Throwable ignored) {
        }
    }

    public void maybePromptRestore(final android.app.Activity act) {
        // 独立线程而不是 IO 单线程队列：首启自愈任务多时会排在它们后面，等轮到自己
        // 时步骤⑥ 已经把 .dsh 铺出来了，弹窗窗口早已错过（issue #22）。
        Thread probe = new Thread(() -> {
            try {
                if (!proot.isInstalled()) return; // rootfs 未就绪（未解压/未安装）不弹
                if (hasUserDataInDsh()) return;   // 已有数据，不打扰也不可能误覆盖
                final BackupScan scan = scanExternalBackups();
                final boolean allFiles = canSeeAllFiles();
                if (scan.total() == 0 && allFiles) {
                    // 能看全盘还是 0 个 —— 这才是真的没有备份
                    android.util.Log.i("DSHA", "自动恢复：空环境，Download/DSHA 确无备份包");
                    return;
                }
                android.util.Log.i("DSHA", "自动恢复：空环境，备份 " + scan.total()
                        + " 个（可读 " + scan.readable + " / 读不到 " + scan.unreadable
                        + "），全文件访问=" + allFiles);
                final SharedPreferences prefs = appContext.getSharedPreferences(
                        "deepseekharness", android.content.Context.MODE_PRIVATE);
                final BackupCandidate best = scan.best;
                // 对同一个备份说过「忽略」就不再追问；出现更新的备份会重新问一次。
                // 没有可读候选时按情形固定记忆，避免每次开 App 都弹同一句提示。
                final String declineKey = best != null ? best.name
                        : (scan.total() == 0 ? "__invisible__" : "__unreadable__" + scan.unreadable);
                if (declineKey.equals(prefs.getString("restore_prompt_declined", ""))) return;
                restoreFlowActive = true;   // 从这里起权限弹窗让路
                new Handler(Looper.getMainLooper()).post(() -> {
                    // 正在结束的 Activity 上 show() 抛 BadTokenException（主线程，外层 catch 不到）
                    if (act.isFinishing() || act.isDestroyed()) {
                        restoreFlowActive = false;
                        return;
                    }
                    try {
                        android.app.AlertDialog.Builder b = new android.app.AlertDialog.Builder(act);
                        final android.content.DialogInterface.OnClickListener decline =
                                (d, w) -> prefs.edit()
                                        .putString("restore_prompt_declined", declineKey).apply();
                        if (best != null) {
                            // 时间+大小必须显示：自动备份是固定名，同名去重又派生出
                            // "…auto.tar (3).gz"，只看名字用户无法判断这是哪一次的数据。
                            String msg = "发现备份：\n" + best.describe()
                                    + "\n\n是否恢复到当前环境？\n（恢复配置、API Key 与对话记录）";
                            if (scan.unreadable > 0) {
                                msg += "\n\n另有 " + scan.unreadable
                                        + " 个备份无法自动读取（卸载重装会让旧文件归属失效）。"
                                        + "想用其中某一个，请点「手动选择」。";
                            }
                            b.setTitle("检测到旧版备份").setMessage(msg)
                                    .setPositiveButton("恢复", (d, w) -> startRestore(act, best))
                                    .setNegativeButton("忽略", decline);
                            if (scan.unreadable > 0) {
                                b.setNeutralButton("手动选择", (d, w) -> pickBackupManually(act));
                            }
                        } else if (scan.total() > 0) {
                            // 枚举到了但一个都打不开：不能给「恢复」按钮——点了必然失败。
                            b.setTitle("发现 " + scan.unreadable + " 个备份，但无法自动读取")
                                    .setMessage("Download/DSHA 里有 " + scan.unreadable
                                            + " 个备份包，系统的分区存储限制让本次安装读不到它们"
                                            + "（卸载重装后旧文件的归属失效）。\n\n"
                                            + "点「手动选择」在文件选择器里指定备份即可正常恢复。")
                                    .setPositiveButton("手动选择", (d, w) -> pickBackupManually(act))
                                    .setNegativeButton("忽略", decline);
                        } else {
                            // 0 个候选且没有全文件访问：备份可能就在 Download/DSHA 里，只是
                            // 本次安装枚举不到（真机实测：授权前扫描 0 个，授权后同一批文件 14 个
                            // 全部可读）。假装没有备份就是 issue #22 用户看到的那个静默。
                            b.setTitle("是否需要恢复以前的备份？")
                                    .setMessage("当前是全新环境。系统的分区存储限制让本次安装"
                                            + "无法自动扫描 Download/DSHA —— 如果你以前备份过，"
                                            + "文件还在，只是这里看不到。\n\n"
                                            + "「手动选择」：在文件选择器里挑备份包，不需要任何权限，"
                                            + "推荐。\n"
                                            + "「开启文件访问」：授予「所有文件访问」后，以后每次"
                                            + "重装都能自动发现备份。")
                                    .setPositiveButton("手动选择", (d, w) -> pickBackupManually(act))
                                    .setNeutralButton("开启文件访问", (d, w) -> openAllFilesAccessSettings(act))
                                    .setNegativeButton("不用了", decline);
                        }
                        android.app.AlertDialog dlg = b.create();
                        // 所有出口统一收口：恢复/手动选择/开启访问/忽略/点外部取消
                        dlg.setOnDismissListener(d -> endRestoreFlow(act));
                        dlg.show();
                    } catch (Throwable t) {
                        restoreFlowActive = false;   // 弹不出来也要放行权限弹窗
                        android.util.Log.w("DSHA", "自动恢复弹窗未能显示: " + describe(t));
                    }
                });
            } catch (Throwable t) {
                android.util.Log.w("DSHA", "自动恢复检测异常: " + describe(t));
            }
        }, "dsha-restore-probe");
        probe.setDaemon(true);
        probe.start();
    }

    /** 后台执行恢复 + Toast 回报（恢复要解压 + 跑 proot 脚本，放 UI 线程足够 ANR）。 */
    private void startRestore(final android.app.Activity act, final BackupCandidate c) {
        android.widget.Toast.makeText(act, "正在恢复，请稍候…",
                android.widget.Toast.LENGTH_SHORT).show();
        Thread work = new Thread(() -> {
            final String r = restoreCandidate(c);
            new Handler(Looper.getMainLooper()).post(() -> {
                try {
                    android.widget.Toast.makeText(appContext, r,
                            android.widget.Toast.LENGTH_LONG).show();
                } catch (Throwable ignored) {
                }
            });
        }, "dsha-auto-restore");
        work.setDaemon(true);
        work.start();
    }

    /** 跳系统的「所有文件访问」设置页。只在用户主动点击时调用 —— 不自动申请。
     *  部分 ROM 没有按包名直达的页面，退回全局列表页；两条都不行就如实说一句。 */
    private void openAllFilesAccessSettings(android.app.Activity act) {
        if (android.os.Build.VERSION.SDK_INT < 30) return;
        try {
            Intent i = new Intent(android.provider.Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                    android.net.Uri.parse("package:" + appContext.getPackageName()));
            act.startActivity(i);
            android.widget.Toast.makeText(act,
                    "打开「允许管理所有文件」后返回，重开 App 即可自动发现备份",
                    android.widget.Toast.LENGTH_LONG).show();
            return;
        } catch (Throwable ignored) {
        }
        try {
            act.startActivity(new Intent(
                    android.provider.Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION));
            android.widget.Toast.makeText(act, "请在列表里找到 DSHA 并打开开关",
                    android.widget.Toast.LENGTH_LONG).show();
        } catch (Throwable e) {
            android.widget.Toast.makeText(act,
                    "本机没有该设置页，请用「手动选择」恢复备份",
                    android.widget.Toast.LENGTH_LONG).show();
        }
    }

    /** 打开系统文件选择器让用户手点备份（SAF 不需要任何存储权限，是分区存储下
     *  读取「别的安装写的文件」唯一稳妥的办法）。 */
    private void pickBackupManually(android.app.Activity act) {
        try {
            if (act instanceof MainActivity) {
                ((MainActivity) act).pickBackupForRestore();
                return;
            }
        } catch (Throwable ignored) {
        }
        android.widget.Toast.makeText(act,
                "请到「设置 → 工作区 → 恢复备份」手动选择备份文件",
                android.widget.Toast.LENGTH_LONG).show();
    }

    /** 手动选中备份后的恢复入口（MainActivity 的选择器回调用）：后台执行 + Toast 回报。 */
    public void restorePickedUri(final android.app.Activity act, final android.net.Uri uri) {
        if (uri == null) return;
        android.widget.Toast.makeText(act, "正在恢复，请稍候…",
                android.widget.Toast.LENGTH_SHORT).show();
        Thread work = new Thread(() -> {
            String r = restoreFromUri(uri);
            final String msg = r == null ? "无法读取所选文件（权限不足或文件已损坏）" : r;
            new Handler(Looper.getMainLooper()).post(() -> {
                try {
                    android.widget.Toast.makeText(appContext, msg,
                            android.widget.Toast.LENGTH_LONG).show();
                } catch (Throwable ignored) {
                }
            });
        }, "dsha-manual-restore");
        work.setDaemon(true);
        work.start();
    }

    /** 离线包升级感知：APK 内置离线包版本 > rootfs 已解压版本 → 弹窗提示升级
     * （重解压自带数据保护：.dsh/.env 自动备份还原，用户确认后跳转强制解压页）。
     * 用户点"忽略"记录版本，下次不弹。rootfs 未解压/无内置包/版本相同 → 静默。 */
    public void maybeOfferOfflineUpgrade(final android.app.Activity act) {
        IO.execute(() -> {
            try {
                if (!proot.isOfflineExtracted()) return; // 首启未解压：走正常解压流程，不提示
                // 上次重解压留下的数据保护目录还在 → 先让用户把数据恢复回来，别再提示升级。
                // 再来一次重解压只会把同一个失败重复一遍，而且用户会以为「升级又坏了」，
                // 真正该做的是先把上一轮的数据归位（maybeOfferPreservedDataRecovery）。
                if (proot.findPreservedData() != null) {
                    android.util.Log.i("DSHA", "有未恢复的数据保护目录 → 暂不提示内置环境升级");
                    return;
                }
                final String bundled = proot.bundledOfflineVersion();
                final String installed = proot.installedOfflineVersion();
                if ("0".equals(bundled) || bundled.equals(installed)) return; // 无内置包/无更新
                // 标记不相等 ≠ 内置包更新。用旧离线包打的本地测试包装上来同样会「不等」，
                // 照旧提示的话用户一点「升级」就把环境降级了（dsh 从 0.1.1-rc.2 退回
                // 0.1.0-rc.6，还得自己再更新回去）。漏提示只是晚点拿到新环境，误提示是把
                // 正在用的环境弄旧 —— 代价不对称，所以拿不准就不提示。
                if (!OfflineVersion.isNewer(bundled, installed)) {
                    android.util.Log.i("DSHA", "内置离线环境 " + bundled + " 不比已解压的 "
                            + installed + " 新 → 不提示升级");
                    return;
                }
                final SharedPreferences prefs =
                        appContext.getSharedPreferences("deepseekharness", android.content.Context.MODE_PRIVATE);
                if (bundled.equals(prefs.getString("ignored_offline_version", ""))) return; // 已忽略
                new Handler(Looper.getMainLooper()).post(() -> {
                    new android.app.AlertDialog.Builder(act)
                            .setTitle("发现新版内置环境 v" + bundled)
                            .setMessage("当前已解压环境：v" + installed + "\n\n"
                                    + "新版包含：预置内置插件 / 组件更新 / 修复。\n"
                                    + "升级将重新解压（约数分钟），配置、API Key 与对话记录会自动保留。\n\n"
                                    + "是否现在升级？")
                            .setPositiveButton("升级", (d, w) -> {
                                try {
                                    Intent i = new Intent(act, ExtractActivity.class);
                                    i.putExtra("force_extract", true);
                                    i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
                                    act.startActivity(i);
                                } catch (Throwable ignored) {
                                }
                            })
                            .setNegativeButton("忽略", (d, w) ->
                                    prefs.edit().putString("ignored_offline_version", bundled).apply())
                            .setNeutralButton("稍后", null)
                            .show();
                });
            } catch (Throwable ignored) {
            }
        });
    }

    /**
     * 上次重解压没走完 → 提示把残留的用户数据恢复回来。
     *
     * <p>数据保护目录（{@code .data-preserve-<时间戳>}）在正常流程结束时会被删掉，所以它
     * 还在就意味着上一次重解压中途失败了（空间不够、被系统杀、断电…）。里面是用户的对话与
     * 插件源码，而 App 原先<b>不会再看它一眼</b> —— 数据明明还在磁盘上，用户却拿不回来，
     * 这是最难受的那种「丢」。
     *
     * <p>刻意不自动恢复：当前环境里可能已经有新对话了，悄悄覆盖比不恢复更糟。所以是问一句，
     * 并且恢复时把现有的 {@code .dsh} 挪到 {@code .dsh.pre-recover-<ts>} 而不是删掉 ——
     * 两份都留着，用户可以自己挑。
     */
    public void maybeOfferPreservedDataRecovery(final android.app.Activity act) {
        IO.execute(() -> {
            try {
                final java.io.File d = proot.findPreservedData();
                if (d == null) return;
                final int count = proot.preservedDataCount();
                final String summary = proot.preservedDataSummary(d);
                final boolean live = proot.hasLiveDshData();
                new Handler(Looper.getMainLooper()).post(() -> {
                    try {
                        StringBuilder msg = new StringBuilder();
                        msg.append("重新解压内置环境的过程中断过一次。你的数据当时被保护起来了，"
                                + "现在还在手机上。\n\n");
                        if (!summary.isEmpty()) msg.append(summary).append("\n\n");
                        if (live) {
                            msg.append("注意：现在的环境里已经有对话数据。恢复会把现在这份挪到 "
                                    + ".dsh.pre-recover-<时间>，再把保护起来的那份放回去 —— "
                                    + "两份都留着，之后你可以自己挑。\n\n");
                        }
                        if (count > 1) {
                            msg.append("（一共有 ").append(count)
                               .append(" 份保护数据，这次恢复最近的那份）\n\n");
                        }
                        msg.append("恢复完需要重启一次 Web UI 才生效。");
                        new android.app.AlertDialog.Builder(act)
                                .setTitle("上次升级没有完成")
                                .setMessage(msg.toString())
                                .setPositiveButton("恢复", (dlg, w) -> IO.execute(() -> {
                                    setProgress("正在恢复上次中断的数据", 0);
                                    String r = proot.restorePreservedData(d, true);
                                    logActivity("恢复上次升级中断的数据：" + r);
                                    setState("", 0, "已恢复上次中断的数据（重启 Web 生效）：" + r,
                                            "", false);
                                }))
                                .setNeutralButton("稍后", null)
                                .setNegativeButton("不要了", (dlg, w) -> {
                                    try {
                                        new android.app.AlertDialog.Builder(act)
                                                .setTitle("确认删掉这份数据？")
                                                .setMessage("里面是上次升级前保护起来的对话与"
                                                        + "插件源码，删掉之后不可恢复。")
                                                .setPositiveButton("删掉", (d2, w2) ->
                                                        IO.execute(() -> {
                                                            boolean ok = proot.dropPreservedData(d);
                                                            logActivity(ok
                                                                    ? "用户删掉了上次升级残留的保护数据"
                                                                    : "删除保护数据失败（文件可能被占用）");
                                                        }))
                                                .setNegativeButton("取消", null)
                                                .show();
                                    } catch (Throwable ignored) {
                                    }
                                })
                                .show();
                    } catch (Throwable ignored) {
                        // 在 finishing 的 Activity 上 show() 会抛 BadTokenException，
                        // 这里是主线程、不在外层 catch 范围内 —— 必须自己兜住。
                    }
                });
            } catch (Throwable ignored) {
            }
        });
    }

    /** 重置配置：删除 settings.yaml + .env（保留对话记录），并重写 .env。 */
    public String resetConfig() {
        try {
            boolean any = false;
            File settings = rootfsFile("root/.dsh/settings.yaml");
            if (settings.isFile()) {
                //noinspection ResultOfMethodCallIgnored
                settings.delete();
                any = true;
            }
            File env = rootfsFile("root/" + getWorkdir() + "/.env");
            if (env.isFile()) {
                //noinspection ResultOfMethodCallIgnored
                env.delete();
                any = true;
            }
            writeEnvFile();
            return any
                    ? "配置已重置，对话记录已保留\n（.env 已按当前配置重写）"
                    : "没有可重置的配置（.env 已重写）";
        } catch (Exception e) {
            return errMsg("重置失败：", e);
        }
    }

    /** 用当前 App 配置重写 rootfs 内的 .env */
    private void writeEnvFile() throws Exception {
        File env = rootfsFile("root/" + getWorkdir() + "/.env");
        if (env.getParentFile() != null) env.getParentFile().mkdirs();
        try (java.io.FileOutputStream out = new java.io.FileOutputStream(env)) {
            out.write(("DEEPSEEK_API_KEY=" + cleanEnvValue(effectiveApiKey()) + "\n")
                    .getBytes(StandardCharsets.UTF_8));
        }
    }

    /** 递归删除文件/目录（Java 侧，绕过 bash rm 的环境问题）。包级可见：插件卸载也用它。
     *  符号链接一律只删链接本身，禁止跟随链接递归（防恶意链接指向目录外被连带删除）。 */
    void deleteRecursively(java.io.File f) {
        if (f == null || !f.exists()) return;
        try {
            if (java.nio.file.Files.isSymbolicLink(f.toPath())) {
                //noinspection ResultOfMethodCallIgnored
                f.delete();
                return;
            }
        } catch (Throwable ignored) {
        }
        if (f.isDirectory()) {
            java.io.File[] cs = f.listFiles();
            if (cs != null) for (java.io.File c : cs) deleteRecursively(c);
        }
        //noinspection ResultOfMethodCallIgnored
        f.delete();
    }

    /** 包级可见：{@link PluginController} 导入/导出插件时要复制文件。 */
    void copyFile(File src, File dst) throws Exception {
        if (dst.getParentFile() != null) dst.getParentFile().mkdirs();
        try (java.io.FileInputStream in = new java.io.FileInputStream(src);
             java.io.FileOutputStream out = new java.io.FileOutputStream(dst)) {
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) != -1) out.write(buf, 0, n);
        }
    }

    private void copyDir(File srcDir, File dstDir) throws Exception {
        File[] children = srcDir.listFiles();
        if (children == null) return;
        for (File c : children) {
            if (c.isDirectory()) {
                copyDir(c, new File(dstDir, c.getName()));
            } else {
                copyFile(c, new File(dstDir, c.getName()));
            }
        }
    }
    /** 已装插件目录候选（out-of-tree 插件经符号链接加载）；均为 rootfs 内绝对路径。
     *  只扫 web profile（dsh plugin --profile web add 的真正安装目录）。
     *  其余（.dsh/node_modules 等）是框架依赖目录，不能当"已装插件"显示。 */
    public static final String[] PLUGIN_DIRS = {
            "/root/.dsh/profiles/web/node_modules",
    };

    // ================= 插件：转发到 PluginController =================
    // 这一整块（原 1662 行）已搬进 PluginController.java，这里只留转发。
    // 两个作用：① PluginFragment / BackupManager 这些调用方一行都不用改；
    // ② 把「插件对外的公开面到底有多大」摆在一处看得见 —— 想加新入口的人
    //    会先看见这份清单，而不是往 6000 行里再塞一个方法。
    /**
     * 插件真实加载状态里「没加载起来」的那批：插件名 → 原因（fiber 状态）。
     *
     * <p>数据来自 3090 桥的 {@code /app/plugins} —— dsh 进程内的 status-overlay 插件遍历
     * cordis registry 报上来的。App 自己读 profile 的 package.json 只知道「注册了」，
     * 看不到「加载起来了没有」；而 PENDING（inject 的服务没有提供者）<b>不报错</b>、
     * 插件静静地什么都不做，正是「插件装了没反应」最常见的形态。
     *
     * <p><b>只认本次 Web 运行期间的上报</b>（时间戳晚于 {@link #webEpoch}）：上一次运行
     * 留下的旧报告可能早就不成立了，拿它给用户打标记只会误导。
     */
    public java.util.Map<String, String> pluginLoadFailures() {
        java.util.LinkedHashMap<String, String> out = new java.util.LinkedHashMap<>();
        try {
            long ts = prefs.getLong("plugin_report_ts", 0L);
            if (ts <= 0 || ts < webEpoch) return out;   // 没报告，或报告比本次启动还早
            String failed = prefs.getString("plugin_failed", "");
            if (failed == null || failed.trim().isEmpty()) return out;
            for (String item : failed.split(",")) {
                String s = item.trim();
                if (s.isEmpty()) continue;
                int c = s.indexOf(':');   // 格式是 name:原因，包名里不会有冒号
                if (c > 0) out.put(s.substring(0, c), s.substring(c + 1));
                else out.put(s, "未知");
            }
        } catch (Throwable ignored) {
        }
        return out;
    }

    public String[][] listPlugins() { return plugins.listPlugins(); }
    public String[][] listPlugins(boolean hideBuiltin) { return plugins.listPlugins(hideBuiltin); }
    public String getVersionNameForUa() { return plugins.getVersionNameForUa(); }
    public String getLastToggleError() { return plugins.getLastToggleError(); }
    public boolean togglePlugin(String name, boolean enable) { return plugins.togglePlugin(name, enable); }
    public String exportPlugins() { return plugins.exportPlugins(); }
    /** 导出单个插件到 Download/DSHA/插件/（单文件）。 */
    public String exportOnePlugin(String name) { return plugins.exportOnePlugin(name); }
    /** 批量启用/禁用（一次写 patch）。 */
    public String togglePlugins(java.util.List<String> names, boolean enable) { return plugins.togglePlugins(names, enable); }
    /** 批量卸载。 */
    public String removePlugins(java.util.List<String> names) { return plugins.removePlugins(names); }
    /** 把选中的若干插件打成一个压缩包放进 Download/DSHA/插件/。 */
    public String exportSelectedPlugins(java.util.List<String> names) { return plugins.exportSelectedPlugins(names); }
    public boolean importPlugins(java.io.File tarGz) { return plugins.importPlugins(tarGz); }
    /** 导入插件归档（自动识别 tar.gz / tar / zip 与单插件 / 多插件布局）。
     *  返回 {status, message}，status = "OK" | "ERR"。 */
    public String[] importArchive(java.io.File archive) { return plugins.importArchive(archive); }
    /** 自动注册「本地已存在但没注册进 profile」的插件（自己在容器里手写的插件走这条）。 */
    public java.util.List<String> autoRegisterLocalPlugins() { return plugins.autoRegisterLocalPlugins(); }
    /** 自愈自指依赖（file:./node_modules/* → link:），修不了的隔离掉。 */
    public String healSelfRefDeps() { return plugins.healSelfRefDeps(); }
    /** 撤销上一次插件安装（还原到安装前的存档点）。 */
    public String undoLastPluginInstall() { return PluginSavepoint.restore(proot, this); }
    /** 最近一个存档点的信息（给 UI 显示），没有返回 null。 */
    public String lastPluginSavepointInfo() { return PluginSavepoint.latestInfo(proot); }
    public String fetchMarketIndex() { return plugins.fetchMarketIndex(); }
    /** 拉市场列表（首选 plugins.json，带 npm 映射；失败退回 Markdown 表格）。 */
    public java.util.List<String[]> fetchMarketRows() { return plugins.fetchMarketRows(); }
    public long getMarketCacheAgeMs() { return plugins.getMarketCacheAgeMs(); }
    public void refreshMarketIndex() { plugins.refreshMarketIndex(); }
    public String[] fetchRepoInfo(String owner, String repo) { return plugins.fetchRepoInfo(owner, repo); }
    public String fetchNpmName(String owner, String repo) { return plugins.fetchNpmName(owner, repo); }
    public String fetchGitHubPackageJson(String owner, String repo) { return plugins.fetchGitHubPackageJson(owner, repo); }
    public String removePlugin(String pkg) { return plugins.removePlugin(pkg); }
    public String installPlugin(String pkg) { return plugins.installPlugin(pkg); }
    public String installPlugin(String pkg, String fallbackSpec) { return plugins.installPlugin(pkg, fallbackSpec); }
    public String[] precheckForMarket(String spec, String npmNameHint) { return plugins.precheckForMarket(spec, npmNameHint); }
    public String[] parseGithubUrl(String url) { return plugins.parseGithubUrl(url); }
    public String installFromGithubUrl(String url) { return plugins.installFromGithubUrl(url); }
    /** 市场表格解析是纯字符串处理，静态转发（调用方以类名调用，历史签名保持不变）。 */
    public static java.util.List<String[]> parseMarketTable(String md) { return PluginController.parseMarketTable(md); }
    // ================= 插件转发结束 =================

}
