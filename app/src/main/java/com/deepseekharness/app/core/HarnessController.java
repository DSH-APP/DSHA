package com.deepseekharness.app.core;
import com.deepseekharness.app.util.Compat;

import android.content.Context;
import android.util.Log;

import com.deepseekharness.app.runtime.ProotBootstrap;
import com.deepseekharness.app.runtime.WebProcessManager;
import com.deepseekharness.app.util.DshAuthUrl;
import com.deepseekharness.app.util.Fmt;
import com.deepseekharness.app.util.ShellQuote;
import com.deepseekharness.app.util.WebLifecycle;
import com.deepseekharness.app.util.WebProcSel;

import java.io.File;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/**
 * 业务编排核心：环境准备 + 启动/停止 dsh Web + BrowserAuth 鉴权链接捕获。
 * 安装六步、备份恢复、插件市场等是后续按 seam 回填的独立协作者，不再堆进这一个类。
 */
public class HarnessController {

    private final Context ctx;
    private final ConfigStore config;
    private final ProotBootstrap proot;
    private final WebProcessManager webProc;
    /** 旧调用方仍会 new Controller，故队列与门控都必须是进程级。 */
    private static final WebLifecycle lifecycle = new WebLifecycle();
    private static final ScheduledExecutorService io = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "dsh-io");
        t.setDaemon(true);
        return t;
    });
    private static Future<?> stopTask;
    private static final java.util.concurrent.ConcurrentHashMap<Process, Boolean> webLaunches =
            new java.util.concurrent.ConcurrentHashMap<>();
    private static WebRecovery recovery;

    /**
     * 当前 dsh 进程打印的 BrowserAuth 鉴权链接（内存态，不落盘）。
     * 与门控共用进程级生命周期，页面重建不会丢失，旧进程不能覆盖新会话。
     */
    private static volatile String webAuthUrl = "";

    public HarnessController(Context ctx) {
        this.ctx = ctx.getApplicationContext();
        this.config = new ConfigStore(this.ctx);
        this.proot = new ProotBootstrap(this.ctx);
        this.webProc = new WebProcessManager(proot);
        synchronized (lifecycle) { if (recovery == null) recovery = new WebRecovery(config); }
    }

    public ConfigStore config() {
        return config;
    }

    public ProotBootstrap proot() {
        return proot;
    }

    /** 别名：供 3090 桥等原版调用方使用。 */
    public ProotBootstrap getProot() {
        return proot;
    }

    /** Web 是否在运行（按 pid 文件 + kill -0 判断，不依赖端口反查）。 */
    public boolean isWebRunning() {
        try {
            java.io.File pidFile = new java.io.File(proot.getRootfsDir(),
                    WebProcSel.pidFileRel(WebProcSel.PID_WEB));
            if (!pidFile.isFile() || pidFile.length() > 32 || Compat.isSymbolicLink(pidFile)) return false;
            int pid = WebProcSel.parsePid(new String(Compat.readAllBytes(pidFile), StandardCharsets.UTF_8));
            if (pid < 0) return false;
            // 状态读取不启动容器、不改运行文件，也不会等待 shell 超时而阻塞界面。
            android.system.Os.kill(pid, 0);
            return true;
        } catch (Throwable e) {
            return false;
        }
    }

    /** 维护前确认所有本进程启动的 Web 启动器及其管道已退出；不按名称误杀容器。 */
    public boolean hasLiveWebProcesses() {
        boolean alive = false;
        for (Process process : webLaunches.keySet()) {
            if (Compat.isAlive(process)) alive = true;
            else webLaunches.remove(process);
        }
        return alive;
    }

    /** 进程级单例（3090 桥、保活服务等共享同一实例）。 */
    private static volatile HarnessController instance;

    public static HarnessController get(Context ctx) {
        if (instance == null) {
            synchronized (HarnessController.class) {
                if (instance == null) {
                    instance = new HarnessController(ctx.getApplicationContext());
                }
            }
        }
        return instance;
    }

    public static String fmtBytes(long b) {
        return Fmt.bytes(b);
    }

    public void logActivity(String s) {
        Log.i("DSHA", s == null ? "" : s);
    }

    /** 读取 assets 里的脚本全文（供备份/自愈等注入 rootfs）。 */
    public String readAsset(String name) {
        try {
            java.io.InputStream in = ctx.getAssets().open(name);
            java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream();
            byte[] buf = new byte[16384];
            int n;
            while ((n = in.read(buf)) != -1) bos.write(buf, 0, n);
            in.close();
            // 资产在 Windows 检出时可能是 CRLF，注入容器后脚本认不了 \r → 统一转 LF
            return bos.toString("UTF-8").replace("\r\n", "\n").replace("\r", "\n");
        } catch (Exception e) {
            return "";
        }
    }

    public boolean isEnvironmentReady() {
        return proot.isEnvironmentReady();
    }

    public boolean hasOfflineBundle() {
        return proot.hasOfflineBundle();
    }

    /** 当前 BrowserAuth 鉴权链接；dsh 还没打印出来时为空串。 */
    public String getWebAuthUrl() {
        return webAuthUrl;
    }

    /** dsh 实际启动命令（写 pid 文件要在 exec 之前，exec 不换 pid）。 */
    public String runCoreCommand() {
        String apiKey = config.getApiKey();
        String apiExport = apiKey.isEmpty()
                ? ""
                : "export DEEPSEEK_API_KEY=" + ShellQuote.arg(apiKey) + " && ";
        return "export DSH_HOME=/root/.dsh && "
                + apiExport
                + "export DSH_PERMISSION_MODE=" + ShellQuote.arg(config.getPermissionMode()) + " && "
                + "export DSH_CONFIRM=" + (config.isConfirmShell() ? "1" : "0") + " && "
                + "export BROWSER=true && "
                + "export DSHA_WEB_GENERATION=" + getWebGeneration() + " && "
                + "cd /root && "
                + "echo $$ > " + WebProcSel.PID_WEB + " 2>/dev/null; "
                // 先写 PID 再查哨兵：停止方先写哨兵再读 PID，两边不会同时漏过。
                + "[ ! -e " + WebProcSel.STOP_SENTINEL + " ] || exit 0; "
                + "exec dsh web --no-open --host 127.0.0.1 --port "
                + config.getPortInt();
    }

    /**
     * 后台启动 dsh：先清残留进程（避免端口冲突），确保运行时与 rootfs 就绪后拉起 dsh web，
     * 独立线程捕获 BrowserAuth 鉴权链接。
     */
    public boolean startWeb(Consumer<String> onStatus) {
        return requestStart(onStatus, false, 0, false);
    }

    public boolean startWebSafely(Consumer<String> onStatus) {
        return requestStart(onStatus, false, 0, true);
    }

    /** 看门狗不能撤销用户停止意图；检查与入队在同一把锁内完成。 */
    public boolean restartWebAutomatically(long expectedGeneration, Consumer<String> onStatus) {
        synchronized (lifecycle) {
            if (expectedGeneration != lifecycle.generation() || !canAutoRestart()) return false;
            recordWebFailure(expectedGeneration, "服务连续三次健康检查未响应");
            if (recovery.blocked()) return false;
        }
        return requestStart(onStatus, true, expectedGeneration, false);
    }

    private boolean requestStart(Consumer<String> onStatus, boolean automatic, long expectedGeneration, boolean safeMode) {
        synchronized (lifecycle) {
            if (com.deepseekharness.app.BackupManager.hasPendingMaintenance(this)) {
                if (onStatus != null) onStatus.accept("上次环境维护未完成，请先到安装与修复页恢复中断维护");
                return false;
            }
            if (com.deepseekharness.app.BackupManager.isEnvironmentTaskBusy()) {
                if (onStatus != null) onStatus.accept("正在执行环境任务，完成后再启动 Web");
                return false;
            }
            if (automatic && (recovery.blocked() || expectedGeneration != lifecycle.generation()
                    || Thread.currentThread().isInterrupted())) return false;
            com.deepseekharness.app.util.EnvironmentTaskGate.Lease startup =
                    com.deepseekharness.app.util.EnvironmentTaskGate.tryAcquire("启动 Web");
            if (startup == null) {
                if (onStatus != null) onStatus.accept("已有环境任务启动，请等待完成后重试");
                return false;
            }
            long startedGeneration = -1;
            boolean transferred = false;
            try {
                final long generation = lifecycle.beginStart(automatic, hasStopSentinel());
                if (generation < 0) return false;
                startedGeneration = generation;
                recovery.begin(generation, !automatic);
                new File(proot.getRootfsDir(), "root/.dsha-web-activity.json").delete();
                webAuthUrl = "";
                io.execute(() -> {
                    try (startup) {
                        startup.run(() -> { startWeb(generation, onStatus, safeMode, !automatic); return null; });
                    } catch (Exception e) {
                        lifecycle.finishStart(generation);
                        reportStatus(generation, onStatus, "启动未完成：" + com.deepseekharness.app.util.SensitiveData.redact(String.valueOf(e)));
                    }
                });
                transferred = true;
                return true;
            } catch (RuntimeException e) {
                String detail = "启动排队失败：" + com.deepseekharness.app.util.SensitiveData.redact(String.valueOf(e));
                if (startedGeneration >= 0) {
                    lifecycle.finishStart(startedGeneration);
                    recordWebFailure(startedGeneration, detail);
                    reportStatus(startedGeneration, onStatus, detail);
                } else if (onStatus != null) onStatus.accept(detail);
                return false;
            } finally {
                // 入队前任何一步失败均释放；成功后由启动 worker 独占并负责关闭。
                if (!transferred) startup.close();
            }
        }
    }

    private void startWeb(long generation, Consumer<String> onStatus, boolean safeMode, boolean manual) {
        boolean draining = false;
        try {
            if (!lifecycle.isCurrent(generation) || com.deepseekharness.app.BackupManager.isRestoring()) return;
            if (com.deepseekharness.app.BackupManager.hasPendingMaintenance(this)) {
                reportStatus(generation, onStatus, "存在未完成的环境维护，请先恢复中断维护");
                return;
            }
            com.deepseekharness.app.LanProxyService.stop();
            String stopError = webProc.stop();
            if (!stopError.isEmpty()) throw new java.io.IOException(stopError);
            if (!lifecycle.isCurrent(generation)) return;
            proot.ensureRuntimeFiles();
            if (!proot.isEnvironmentReady()) {
                if (!proot.hasOfflineBundle()) {
                    lifecycle.finishStart(generation);
                    recordWebFailure(generation, "缺少内置离线环境包");
                    reportStatus(generation, onStatus, "没有内置离线环境包：请用完整 APK（含 offline-rootfs）安装");
                    return;
                }
                reportStatus(generation, onStatus, "正在解压内置环境（首次约需几分钟，请勿退出）…");
                setWebStage(generation, "解压运行环境");
                proot.extractOfflineBundle((done, total) -> { });
                reportStatus(generation, onStatus, "环境解压完成，正在启动 dsh web…");
            }
            if (!lifecycle.isCurrent(generation)) return;
            setWebStage(generation, "恢复数据事务");
            com.deepseekharness.app.BackupManager.recoverInterrupted(this);
            if (manual && config.countLaunchForBackup()) {
                reportStatus(generation, onStatus, "正在进行启动前自动备份…");
                String backup = com.deepseekharness.app.BackupManager.backupForAutomaticLaunch(ctx, this);
                reportStatus(generation, onStatus, backup == null
                        ? "自动备份未完成：" + com.deepseekharness.app.BackupManager.lastError() + "；继续启动，可在数据与备份页重试"
                        : "自动备份已保存并校验");
            }
            if (!lifecycle.isCurrent(generation)) return;
            setWebStage(generation, "注册插件");
            // 内置四插件注册：rootfs 烘焙的实体要登记进 web profile 才会被 dsh 加载。
            // 覆盖安装（rootfs 保留）与全新安装（rootfs 重新解压）都靠这一步补齐；
            // 失败不阻塞启动（插件页打开时会再触发一次，dsh 下次重启生效）。
            try {
                String r = proot.registerBuiltinPlugins();
                if (r != null && (r.contains("BUILTIN_REGISTER_OK")
                        || r.contains("BUILTIN_REGISTER_PARTIAL")
                        || r.contains("FAIL"))) {
                    Log.i("DSHA", "内置插件注册: " + r.trim());
                }
            } catch (Throwable ignored) {
            }
            if (safeMode) {
                setWebStage(generation, "禁用第三方插件");
                reportStatus(generation, onStatus, "正在暂时禁用第三方插件，准备安全启动…");
                String result = com.deepseekharness.app.util.PluginOutput.resultJson(proot.runPluginManager("safe-mode on"));
                org.json.JSONObject status = new org.json.JSONObject(result);
                if (!"ok".equals(status.optString("status"))) throw new java.io.IOException(status.optString("message"));
            }
            // 只有当前启动任务能清哨兵；延迟进入容器的旧 shell 不再自行删除它。
            synchronized (lifecycle) {
                if (!lifecycle.isCurrent(generation)) return;
                File sentinel = stopSentinel();
                if (sentinel.exists() && !sentinel.delete()) {
                    throw new java.io.IOException("无法清除停止标记");
                }
                // 日志也归当前代次管理，旧 shell 不再截断新会话的日志。
                try {
                    Compat.write(new File(proot.getRootfsDir(), "root/dsh-web.log"), new byte[0]);
                } catch (Exception ignored) {
                }
            }
            setWebStage(generation, "创建 Web 进程");
            Process p = proot.execRootfs(runCoreCommand());
            webLaunches.put(p, Boolean.TRUE);
            setWebStage(generation, "等待鉴权链接");
            // 3090 桥就绪：agent 在容器里调设备能力（/exec /confirm /status）走这条通道。
            // 跨实例互斥，DeviceBridgeService 已起过则是幂等 no-op。
            try {
                if (com.deepseekharness.app.HttpShellService.instance() == null) {
                    new com.deepseekharness.app.HttpShellService(ctx).start();
                }
            } catch (Throwable e) {
                Log.w("DSHA", "3090 桥启动失败: "
                        + com.deepseekharness.app.util.SensitiveData.redact(String.valueOf(e)));
            }
            reportStatus(generation, onStatus, "dsh web 进程已创建 → 127.0.0.1:" + config.getPortInt()
                    + "（等待鉴权链接…）");
            Thread drainer = new Thread(() -> drainWebOutput(p, generation, onStatus), "dsh-drain");
            drainer.setDaemon(true);
            drainer.start();
            // 启动完成以鉴权链接为准，不等常驻进程退出；无链接也不能永久占锁。
            io.schedule(() -> {
                synchronized (lifecycle) {
                    if (lifecycle.finishStart(generation)) {
                        recordWebFailure(generation, "等待鉴权链接超过 60 秒");
                        reportStatus(generation, onStatus, "等待鉴权链接超时，可查看日志或手动重启");
                    }
                }
            }, 60, TimeUnit.SECONDS);
            draining = true;
        } catch (Exception e) {
            Log.e("DSHA", "startWeb failed", e);
            lifecycle.finishStart(generation);
            recordWebFailure(generation, "启动失败：" + e.getMessage());
            reportStatus(generation, onStatus, "启动失败：" + e.getMessage());
        } finally {
            if (!draining) lifecycle.finishStart(generation);
        }
    }

    /** 读 dsh 进程输出：抓鉴权链接（宽松）、并把脱敏后的输出落到容器日志方便排查。 */
    private void drainWebOutput(Process p, long generation, Consumer<String> onStatus) {
        StringBuilder scan = new StringBuilder();
        try (InputStream in = p.getInputStream()) {
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) != -1) {
                String chunk = new String(buf, 0, n, StandardCharsets.UTF_8);
                scan.append(chunk);
                if (scan.length() > 64_384) scan.delete(0, scan.length() - 64_384);
                String url = null;
                synchronized (lifecycle) {
                    if (!lifecycle.isCurrent(generation)) continue;
                    appendHostLog(chunk);
                    if (webAuthUrl.isEmpty()) url = extractAuthUrl(scan.toString());
                    if (url != null && !recovery.failed(generation)) {
                        webAuthUrl = url;
                        recovery.stage(generation, "Web 运行");
                        lifecycle.finishStart(generation);
                        reportStatus(generation, onStatus, "鉴权链接已就绪，点「进入对话」即可进入 dsh");
                    }
                }
                if (url != null) {
                    // LAN 模式：拿到鉴权链接后自动交换 cookie 并启动 3081 代理。
                    // 否则代理要等用户手动点「进入」才绑定 —— 其它设备在手机上没点过
                    // 「进入」时就连不上（连接被拒），正是「局域网连不上」的头号原因。
                    // dsh 打印 URL 时 HTTP 服务可能还没就绪，交换失败就短等重试几次。
                    if (config.isLanMode()) {
                        for (int attempt = 0; attempt < 3 && lifecycle.isCurrent(generation); attempt++) {
                            try {
                                if (exchangeDshAuthCookie(generation) != null) break;
                            } catch (Throwable ignored) {
                            }
                            try {
                                Thread.sleep(1200);
                            } catch (InterruptedException ie) {
                                Thread.currentThread().interrupt();
                                break;
                            }
                        }
                        // LanProxyService.start 的绑定在独立 accept 线程里异步完成，
                        // 刚返回时 isBound() 可能还是 false —— 轮询等它绑定完再刷新 UI。
                        for (int i = 0; i < 12 && lifecycle.isCurrent(generation)
                                && !com.deepseekharness.app.LanProxyService.isBound(); i++) {
                            try {
                                Thread.sleep(200);
                            } catch (InterruptedException ie) {
                                Thread.currentThread().interrupt();
                                break;
                            }
                        }
                        // 代理是在上面 onStatus.accept 之后才绑定的，启动页那次刷新
                        // 会停在「等待本轮认证」；这里再触发一次 UI 刷新，让地址可点。
                        if (com.deepseekharness.app.LanProxyService.isBound()) {
                            reportStatus(generation, onStatus, "局域网代理已就绪：同网段设备可访问，启动页可复制地址");
                        }
                    }
                }
            }
        } catch (Exception ignored) {
        }
        if (!Compat.isAlive(p)) webLaunches.remove(p);
        synchronized (lifecycle) {
            if (!lifecycle.isCurrent(generation)) return;
            boolean hadAuth = !webAuthUrl.isEmpty();
            webAuthUrl = "";
            lifecycle.finishStart(generation);
            com.deepseekharness.app.LanProxyService.stop(generation);
            recordWebFailure(generation, hadAuth ? "Web 进程意外退出" : "Web 进程未打印鉴权链接便退出");
            reportStatus(generation, onStatus, hadAuth ? "dsh 进程已退出"
                    : "dsh 进程已退出且未打印鉴权链接，日志见 /root/dsh-web.log");
        }
    }

    /** 提取鉴权链接：先严格（官方输出行），失败再宽松（直接扫 URL）。 */
    private String extractAuthUrl(String output) {
        return DshAuthUrl.findAny(output);
    }

    /** 把 dsh 输出脱敏后落到容器内 /root/dsh-web.log（排查用，鉴权 token 不落盘）。 */
    private void appendHostLog(String chunk) {
        try {
            File log = new File(proot.getRootfsDir(), "root/dsh-web.log");
            if (log.getParentFile() != null) log.getParentFile().mkdirs();
            Compat.append(log, redactAuthUrl(chunk).getBytes(StandardCharsets.UTF_8));
        } catch (Throwable ignored) {
        }
    }

    /** 把鉴权 token 打码，避免落盘泄露。 */
    static String redactAuthUrl(String s) {
        return DshAuthUrl.redact(s);
    }

    /**
     * Java 侧直接做一次 BrowserAuth cookie 交换：GET 鉴权链接，取回 dsh-auth-* cookie。
     * 返回 {@code "name=value"} 或 null。用于 WebView 的确定性注入鉴权。
     * 拿到 cookie 后若开了 LAN 模式，同步启动局域网反向代理（3081）。
     */
    public String exchangeDshAuthCookie() {
        return exchangeDshAuthCookie(lifecycle.generation());
    }

    private String exchangeDshAuthCookie(long generation) {
        String url;
        synchronized (lifecycle) {
            if (!lifecycle.isCurrent(generation)) return null;
            url = webAuthUrl;
            if (url.isEmpty()) return null;
        }
        HttpURLConnection conn = null;
        try {
            conn = (HttpURLConnection) new URL(url).openConnection();
            conn.setInstanceFollowRedirects(false);
            conn.setConnectTimeout(2500);
            conn.setReadTimeout(4000);
            conn.setRequestMethod("GET");
            conn.getResponseCode();
            String cookie = extractDshAuthCookie(conn.getHeaderFields());
            synchronized (lifecycle) {
                if (!lifecycle.isCurrent(generation) || !url.equals(webAuthUrl)) return null;
                if (cookie != null) {
                    try {
                        com.deepseekharness.app.LanProxyService.setDshAuthCookie(cookie, generation);
                        if (config.isLanMode()) {
                            com.deepseekharness.app.LanProxyService.start(
                                    proot.getRootfsDir().getAbsolutePath(), ctx,
                                    config.getPortInt(), generation);
                        }
                    } catch (Throwable ignored) {
                    }
                }
                return cookie;
            }
        } catch (Throwable ignored) {
            return null;
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    /** 从 Set-Cookie 里挑 dsh-auth-* 那个 cookie（不假设它是第一个）。 */
    private static String extractDshAuthCookie(Map<String, List<String>> headers) {
        return DshAuthUrl.extractCookie(headers);
    }

    /** 保留清除环境等旧调用方的等待语义；页面和服务使用异步重载。 */
    public void stopWeb() {
        try {
            enqueueStop(null).get();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (Exception e) {
            Log.w("DSHA", "等待停止失败", e);
        }
    }

    /** 立即禁用自动拉起，实际停止在共享队列执行，回调在后台线程。 */
    public void stopWeb(Consumer<String> onStatus) {
        enqueueStop(onStatus);
    }

    private Future<?> enqueueStop(Consumer<String> onStatus) {
        synchronized (lifecycle) {
            if (lifecycle.isStopping()) return stopTask;
            long previous = lifecycle.generation();
            long generation = lifecycle.beginStop();
            webAuthUrl = "";
            // 宿主直接写小标记，不等可能仍在解压/注册插件的串行任务。
            try {
                File sentinel = stopSentinel();
                if (sentinel.getParentFile().isDirectory()) sentinel.createNewFile();
            } catch (Exception e) {
                Log.w("DSHA", "写停止标记失败，将由停止脚本重试", e);
            }
            stopTask = io.submit(() -> {
                String stopError = "";
                try {
                    stopError = webProc.stop(); // 仍用原 PID 判据，绝不直接 destroy proot。
                    com.deepseekharness.app.LanProxyService.stop(previous);
                } finally {
                    synchronized (lifecycle) {
                        lifecycle.finishStop(generation);
                        reportStatus(generation, onStatus, stopError.isEmpty() ? "停止操作已完成" : stopError);
                    }
                }
            });
            return stopTask;
        }
    }

    private File stopSentinel() {
        return new File(proot.getRootfsDir(), WebProcSel.pidFileRel(WebProcSel.STOP_SENTINEL));
    }

    private boolean hasStopSentinel() { return stopSentinel().exists(); }
    public boolean isStarting() { return lifecycle.isStarting(); }
    public boolean isStopping() { return lifecycle.isStopping(); }
    public boolean isUserStopped() { return lifecycle.isUserStopped(); }

    public boolean canAutoRestart() {
        synchronized (lifecycle) {
            return !recovery.blocked() && lifecycle.canAutoStart(hasStopSentinel());
        }
    }

    public boolean isRestartBlocked() { synchronized (lifecycle) { return recovery.blocked(); } }
    public void reportWebHealth(long generation, boolean healthy) {
        synchronized (lifecycle) {
            if (!lifecycle.isCurrent(generation)) return;
            if (healthy) recovery.healthy(generation, android.os.SystemClock.elapsedRealtime());
            else recovery.unhealthy(generation);
        }
    }
    private void setWebStage(long generation, String stage) {
        synchronized (lifecycle) { if (lifecycle.isCurrent(generation)) recovery.stage(generation, stage); }
    }
    private void recordWebFailure(long generation, String reason) {
        synchronized (lifecycle) {
            if (!lifecycle.isCurrent(generation) || !recovery.fail(generation, reason)) return;
            DiagnosticLog.record(ctx, "WEB_FAILURE", config.getWebFailureStage() + "：" + reason);
            if (recovery.blocked()) {
                // 立即撤销失败代次；停止仍只使用哨兵和 Web PID，不杀容器启动器。
                stopWeb(null);
            }
        }
    }

    /** 发布前检查代次；UI 入队后还要再检查，防主线程消费到旧消息。 */
    private void reportStatus(long generation, Consumer<String> onStatus, String message) {
        synchronized (lifecycle) {
            if (!lifecycle.isCurrent(generation)) return;
            DiagnosticLog.record(ctx, "WEB_START_STOP", message);
            if (onStatus == null) return;
            try {
                onStatus.accept(message);
            } catch (RuntimeException e) {
                Log.w("DSHA", "启动状态回调失败", e);
            }
        }
    }

    /** 当前 dsh 代次号（供 LAN 代理 / 配置页开关联动）。 */
    public long getWebGeneration() {
        return lifecycle.generation();
    }

    /** 撤销解压标记：下次启动重新走 ExtractActivity 解压（配置保留）。 */
    public void resetExtraction() {
        proot.markNotExtracted();
    }

    /** 重置容器内配置（settings.yaml + .env），保留对话记录，并按当前 App 配置重写 .env。 */
    public String resetConfig() {
        try {
            boolean any = false;
            java.io.File settings = new java.io.File(proot.getRootfsDir(), "root/.dsh/settings.yaml");
            if (settings.isFile()) {
                //noinspection ResultOfMethodCallIgnored
                settings.delete();
                any = true;
            }
            String wd = config.getWorkdir();
            java.io.File env = new java.io.File(proot.getRootfsDir(),
                    "root/" + (wd.startsWith("/") ? wd.substring(1) : wd) + "/.env");
            if (env.isFile()) {
                //noinspection ResultOfMethodCallIgnored
                env.delete();
                any = true;
            }
            writeEnvFile(env);
            return any
                    ? "配置已重置，对话记录已保留\n（.env 已按当前配置重写）"
                    : "没有可重置的配置（.env 已重写）";
        } catch (Throwable e) {
            return "重置失败：" + com.deepseekharness.app.util.SensitiveData.redact(String.valueOf(e));
        }
    }

    /** 用当前 App 配置重写 rootfs 内的 .env。 */
    private void writeEnvFile(java.io.File env) throws Exception {
        if (env.getParentFile() != null) env.getParentFile().mkdirs();
        String apiKey = config.getApiKey();
        String keyLine = apiKey.isEmpty()
                ? "# DEEPSEEK_API_KEY=\n"
                : "DEEPSEEK_API_KEY=" + com.deepseekharness.app.util.ShellQuote.arg(apiKey) + "\n";
        Compat.write(env, keyLine.getBytes(StandardCharsets.UTF_8));
    }

    /** proot 冒烟测试，返回诊断文本。 */
    public String smokeTest() {
        return proot.smokeTest();
    }

    /**
     * 检测本机局域网 IPv4 地址（免权限，NetworkInterface 枚举，给 LAN 代理分享用）。
     * 优先 WiFi/以太网接口（wlan/eth/radio），避免选到 USB 共享网络等非目标网卡的地址
     * —— 否则复制出去的局域网地址另一台设备永远连不上。
     */
    public static String getLanAddress() {
        try {
            String fallback = null;
            java.util.Enumeration<java.net.NetworkInterface> nis =
                    java.net.NetworkInterface.getNetworkInterfaces();
            while (nis != null && nis.hasMoreElements()) {
                java.net.NetworkInterface ni = nis.nextElement();
                if (!ni.isUp() || ni.isLoopback()) continue;
                String ifName = ni.getName() == null ? "" : ni.getName();
                boolean wifiLike = ifName.startsWith("wlan") || ifName.startsWith("eth")
                        || ifName.startsWith("radio") || ifName.startsWith("wifi");
                java.util.Enumeration<java.net.InetAddress> as = ni.getInetAddresses();
                while (as.hasMoreElements()) {
                    java.net.InetAddress a = as.nextElement();
                    if (!(a instanceof java.net.Inet4Address) || a.isLoopbackAddress()) continue;
                    String ip = a.getHostAddress();
                    if (ip != null && (ip.startsWith("192.168.") || ip.startsWith("10.")
                            || ip.startsWith("172."))) {
                        if (fallback == null) fallback = ip;
                        if (wifiLike) return ip; // 目标网卡命中，直接返回
                    }
                }
            }
            return fallback;
        } catch (Exception ignored) {
        }
        return null;
    }
}
