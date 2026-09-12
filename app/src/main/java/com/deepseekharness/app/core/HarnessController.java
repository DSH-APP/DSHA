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
import java.io.IOException;
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
    private static StartupDiagnostics startupDiagnostics;
    private static volatile boolean webCompatibilityFallback;

    /**
     * 当前 dsh 进程打印的 BrowserAuth 鉴权链接（内存态，不落盘）。
     * 与门控共用进程级生命周期，页面重建不会丢失，旧进程不能覆盖新会话。
     */
    private static volatile String webAuthUrl = "";
    private static volatile int activeWebPort;

    public HarnessController(Context ctx) {
        this(ctx, new ProotBootstrap(ctx));
    }

    HarnessController(Context ctx, ProotBootstrap bootstrap) {
        this.ctx = ctx.getApplicationContext();
        this.config = new ConfigStore(this.ctx);
        this.proot = bootstrap;
        this.webProc = new WebProcessManager(proot);
        synchronized (lifecycle) {
            if (recovery == null) recovery = new WebRecovery(config);
            if (startupDiagnostics == null) startupDiagnostics = new StartupDiagnostics(this.ctx);
            startupDiagnostics.onHealthy = generation -> StartupRepairs.healthy(this.ctx,this,generation);
        }
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
    public boolean isWebRunning() { return webProc.isRunning(); }

    public boolean isWebStoppedForMaintenance() throws IOException {
        return webProc.confirmStopped(hasLiveWebProcesses());
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
    public int getWebPort() { return activeWebPort>0?activeWebPort:config.getPortInt(); }

    public String getWebAuthUrl() {
        return webAuthUrl;
    }

    /** dsh 实际启动命令（写 pid 文件要在 exec 之前，exec 不换 pid）。 */
    public String runCoreCommand() {
        return runCoreCommand("web",config.getPortInt());
    }

    private String runCoreCommand(String profile,int listenPort) {
        String apiKey = config.getApiKey();
        String apiExport = apiKey.isEmpty()
                ? ""
                : "export DEEPSEEK_API_KEY=" + ShellQuote.arg(apiKey) + " && ";
        return "export DSH_HOME=/root/.dsh && "
                + apiExport
                + "export DSH_PERMISSION_MODE=" + ShellQuote.arg(config.getPermissionMode()) + " && "
                + "export DSH_CONFIRM=" + (config.isConfirmShell() ? "1" : "0") + " && "
                + "export BROWSER=true && "
                + "export DSHA_PRELOAD_PREVIOUS=\"${NODE_OPTIONS-}\" && "
                + "export NODE_OPTIONS=\"--import=/root/.dsh/startup-observer.cjs ${NODE_OPTIONS-}\" && "
                + "export DSHA_UI_LANGUAGE=" + ShellQuote.arg(config.getUiLanguage()) + " && "
                + "export DSHA_STARTUP_PROFILE=" + ShellQuote.arg(profile) + " && "
                + "export DSHA_WEB_GENERATION=" + getWebGeneration() + " && "
                + "cd /root && "
                + "rm -f " + WebProcSel.IDENTITY_WEB + "; echo $$ > " + WebProcSel.PID_WEB + " 2>/dev/null; "
                // 先写 PID 再查哨兵：停止方先写哨兵再读 PID，两边不会同时漏过。
                + "[ ! -e " + WebProcSel.STOP_SENTINEL + " ] || exit 0; "
                + "exec dsh " + ("web".equals(profile) ? "web" : "--profile " + ShellQuote.arg(profile))
                + " --no-open --host 127.0.0.1 --port "
                + listenPort;
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
            recordWebFailure(expectedGeneration, com.deepseekharness.app.util.UiText.text("服务连续三次健康检查未响应"));
            if (recovery.blocked()) return false;
        }
        return requestStart(onStatus, true, expectedGeneration, startupDiagnostics.snapshot().safe);
    }

    private boolean requestStart(Consumer<String> onStatus, boolean automatic, long expectedGeneration, boolean safeMode) {
        synchronized (lifecycle) {
            if (StartupRepairs.pending(ctx)) {
                if(onStatus!=null)onStatus.accept(com.deepseekharness.app.util.UiText.choose("配置修复尚未完成，请先进入启动恢复。","Configuration repair is incomplete. Open Startup Recovery first."));
                return false;
            }
            if (com.deepseekharness.app.BackupManager.hasPendingMaintenance(this)) {
                if (onStatus != null) onStatus.accept(com.deepseekharness.app.util.UiText.text("上次环境维护未完成，请先到安装与修复页恢复中断维护"));
                return false;
            }
            if (com.deepseekharness.app.BackupManager.isEnvironmentTaskBusy()) {
                if (onStatus != null) onStatus.accept(com.deepseekharness.app.util.UiText.text("正在执行环境任务，完成后再启动 Web"));
                return false;
            }
            if (automatic && (recovery.blocked() || expectedGeneration != lifecycle.generation()
                    || Thread.currentThread().isInterrupted())) return false;
            com.deepseekharness.app.util.EnvironmentTaskGate.Lease startup =
                    com.deepseekharness.app.util.EnvironmentTaskGate.tryAcquire(com.deepseekharness.app.util.UiText.text("启动 Web"));
            if (startup == null) {
                if (onStatus != null) onStatus.accept(com.deepseekharness.app.util.UiText.text("已有环境任务启动，请等待完成后重试"));
                return false;
            }
            long startedGeneration = -1;
            boolean transferred = false;
            try {
                final long generation = lifecycle.beginStart(automatic, hasStopSentinel());
                if (generation < 0) return false;
                startedGeneration = generation;
                recovery.begin(generation, !automatic);
                config.requestStartupRecovery(false);
                startupDiagnostics.begin(generation, safeMode);
                startupDiagnostics.message(generation, com.deepseekharness.app.util.UiText.text("运行方式：") + (config.isProroot() && android.os.Build.VERSION.SDK_INT >= 26 ? "proroot" : "proot"));
                new File(proot.getRootfsDir(), "root/.dsha-web-activity.json").delete();
                webAuthUrl = "";activeWebPort=0;
                io.execute(() -> {
                    try (startup) {
                        startup.run(() -> { startWeb(generation, onStatus, safeMode, !automatic,
                                automatic && webCompatibilityFallback); return null; });
                    } catch (Exception e) {
                        lifecycle.finishStart(generation);
                        recordWebFailure(generation,String.valueOf(e));
                        reportStatus(generation, onStatus, com.deepseekharness.app.util.UiText.text("启动未完成：") + com.deepseekharness.app.util.SensitiveData.redact(String.valueOf(e)));
                    }
                });
                transferred = true;
                return true;
            } catch (RuntimeException e) {
                String detail = com.deepseekharness.app.util.UiText.text("启动排队失败：") + com.deepseekharness.app.util.SensitiveData.redact(String.valueOf(e));
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

    private void startWeb(long generation, Consumer<String> onStatus, boolean safeMode, boolean manual, boolean compatible) {
        ProotBootstrap boot = compatible ? new ProotBootstrap(ctx, true) : proot;
        boolean draining = false;
        try {
            if (!lifecycle.isCurrent(generation) || com.deepseekharness.app.BackupManager.isRestoring()) return;
            if (com.deepseekharness.app.BackupManager.hasPendingMaintenance(this)) {
                reportStatus(generation, onStatus, com.deepseekharness.app.util.UiText.text("存在未完成的环境维护，请先恢复中断维护"));
                return;
            }
            com.deepseekharness.app.LanProxyService.stop();
            setWebStage(generation, com.deepseekharness.app.util.UiText.text("停止旧 Web 进程"));
            String stopError = webProc.stop();
            if (!stopError.isEmpty()) throw new java.io.IOException(stopError);
            if (!lifecycle.isCurrent(generation)) return;
            setWebStage(generation, com.deepseekharness.app.util.UiText.text("检查随包运行工具"));
            boot.ensureRuntimeFiles();
            if (!proot.isEnvironmentReady()) {
                if (!proot.hasOfflineBundle()) {
                    lifecycle.finishStart(generation);
                    recordWebFailure(generation, com.deepseekharness.app.util.UiText.text("缺少内置离线环境包"));
                    reportStatus(generation, onStatus, com.deepseekharness.app.util.UiText.text("没有内置离线环境包：请用完整 APK（含 offline-rootfs）安装"));
                    return;
                }
                reportStatus(generation, onStatus, com.deepseekharness.app.util.UiText.text("正在解压内置环境（首次约需几分钟，请勿退出）…"));
                setWebStage(generation, com.deepseekharness.app.util.UiText.text("解压运行环境"));
                proot.extractOfflineBundle((done, total) -> { });
                reportStatus(generation, onStatus, com.deepseekharness.app.util.UiText.text("环境解压完成，正在启动 dsh web…"));
            }
            if (!lifecycle.isCurrent(generation)) return;
            setWebStage(generation, com.deepseekharness.app.util.UiText.text("恢复数据事务"));
            com.deepseekharness.app.BackupManager.recoverInterrupted(this);
            try { EnvironmentMaintenance.cleanupCompleted(this); }
            catch (IOException cleanup) { reportStatus(generation, onStatus, com.deepseekharness.app.util.UiText.text("部分旧环境待清理：") + cleanup.getMessage()); }
            if (!lifecycle.isCurrent(generation)) return;
            if (!safeMode) {
            setWebStage(generation, com.deepseekharness.app.util.UiText.text("注册插件"));
            // 内置四插件注册：rootfs 烘焙的实体要登记进 web profile 才会被 dsh 加载。
            // 覆盖安装（rootfs 保留）与全新安装（rootfs 重新解压）都靠这一步补齐；
            // 失败不阻塞启动（插件页打开时会再触发一次，dsh 下次重启生效）。
            try {
                String r = boot.registerBuiltinPlugins();
                startupDiagnostics.message(generation, r);
                if (r != null && (r.contains("BUILTIN_REGISTER_OK")
                        || r.contains("BUILTIN_REGISTER_PARTIAL")
                        || r.contains("FAIL"))) {
                    Log.i("DSHA", com.deepseekharness.app.util.UiText.text("内置插件注册: ") + r.trim());
                }
            } catch (Throwable error) {
                startupDiagnostics.issue(generation, "", com.deepseekharness.app.util.UiText.text("插件注册失败：") + error);
            }
            }
            String startupProfile = "web";
            if (safeMode) {
                setWebStage(generation, com.deepseekharness.app.util.UiText.text("准备独立基础配置"));
                String result = boot.execAndReadWithProot("python3 /root/.dsh/startup-recovery.py", 90_000);
                java.util.regex.Matcher profile = java.util.regex.Pattern.compile("(?m)^DSHA_RECOVERY_PROFILE=(dsha-recovery-[0-9a-f]{16})$").matcher(result.trim());
                if (!profile.find()) throw new IOException(com.deepseekharness.app.util.UiText.text("安全配置创建失败：") + result);
                startupProfile = profile.group(1);
                reportStatus(generation, onStatus, com.deepseekharness.app.util.UiText.text("安全启动仅加载官方基础界面；原插件开关和配置保留，点普通重启可返回。"));
            }
            // 只有当前启动任务能清哨兵；延迟进入容器的旧 shell 不再自行删除它。
            synchronized (lifecycle) {
                if (!lifecycle.isCurrent(generation)) return;
                File sentinel = stopSentinel();
                if (sentinel.exists() && !sentinel.delete()) {
                    throw new java.io.IOException(com.deepseekharness.app.util.UiText.text("无法清除停止标记"));
                }
                // 日志也归当前代次管理，旧 shell 不再截断新会话的日志。
                try {
                    Compat.write(new File(proot.getRootfsDir(), "root/dsh-web.log"), new byte[0]);
                } catch (Exception ignored) {
                }
            }
            setWebStage(generation, com.deepseekharness.app.util.UiText.text("创建 Web 进程"));
            if(!safeMode)try {
                StartupRepairs.checkpoint(this,new org.json.JSONObject().put("command","prepare").put("startupId",startupDiagnostics.recordId()));
            } catch(Exception error) {
                startupDiagnostics.message(generation,com.deepseekharness.app.util.UiText.choose("本次配置快照暂不可用：","Configuration snapshot unavailable for this start: ")+error.getClass().getSimpleName());
            }
            String runtimeName = boot.runtime().id();
            webCompatibilityFallback = compatible;
            int preferred=config.getPortInt();
            com.deepseekharness.app.util.WebPortPolicy.Choice ports=com.deepseekharness.app.util.WebPortPolicy.choose(
                    preferred,config.fallbackWebPort(preferred),com.deepseekharness.app.util.WebPortPolicy::available);
            if(!lifecycle.isCurrent(generation))return;
            activeWebPort=ports.listen;
            if(ports.fallback()) {
                reportStatus(generation,onStatus,com.deepseekharness.app.util.UiText.choose("首选 Web 端口已占用：","Preferred Web port is in use: ")+preferred);
                reportStatus(generation,onStatus,com.deepseekharness.app.util.UiText.text("正在自动选择可用 Web 端口，首选端口设置保留"));
            }
            Process p = boot.execRootfs(runCoreCommand(startupProfile,ports.listen));
            webLaunches.put(p, Boolean.TRUE);
            setWebStage(generation, com.deepseekharness.app.util.UiText.text("等待鉴权链接"));
            // 3090 桥就绪：agent 在容器里调设备能力（/exec /confirm /status）走这条通道。
            // 跨实例互斥，DeviceBridgeService 已起过则是幂等 no-op。
            try {
                if (com.deepseekharness.app.HttpShellService.instance() == null) {
                    new com.deepseekharness.app.HttpShellService(ctx).start();
                }
            } catch (Throwable e) {
                Log.w("DSHA", com.deepseekharness.app.util.UiText.text("3090 桥启动失败: ")
                        + com.deepseekharness.app.util.SensitiveData.redact(String.valueOf(e)));
            }
            if(ports.listen==0)reportStatus(generation,onStatus,com.deepseekharness.app.util.UiText.text("Web 进程已创建，等待系统分配端口和鉴权链接"));
            else reportStatus(generation, onStatus, com.deepseekharness.app.util.UiText.text("dsh web 进程已创建 → 127.0.0.1:") + ports.listen
                    + com.deepseekharness.app.util.UiText.text("（等待鉴权链接…）"));
            Thread drainer = new Thread(() -> drainWebOutput(p, generation, onStatus, runtimeName, compatible, safeMode, ports), "dsh-drain");
            drainer.setDaemon(true);
            drainer.start();
            // 启动慢只提示；保持启动状态，看门狗不会因此重启，用户仍可随时手动停止。
            io.schedule(() -> reportSlowStart(generation, onStatus), 60, TimeUnit.SECONDS);
            draining = true;
        } catch (Exception e) {
            Log.e("DSHA", "startWeb failed", e);
            lifecycle.finishStart(generation);
            recordWebFailure(generation, com.deepseekharness.app.util.UiText.text("启动失败：") + e.getMessage());
            reportStatus(generation, onStatus, com.deepseekharness.app.util.UiText.text("启动失败：") + e.getMessage());
        } finally {
            if (!draining) lifecycle.finishStart(generation);
        }
    }

    /** 读 dsh 进程输出：抓鉴权链接（宽松）、并把脱敏后的输出落到容器日志方便排查。 */
    private void drainWebOutput(Process p, long generation, Consumer<String> onStatus,
                                String runtimeName, boolean compatible, boolean safeMode, com.deepseekharness.app.util.WebPortPolicy.Choice ports) {
        StringBuilder scan = new StringBuilder();
        com.deepseekharness.app.util.DshAuthLog safeLog = new com.deepseekharness.app.util.DshAuthLog();
        try (java.io.Reader in = new java.io.InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8)) {
            char[] buf = new char[8192];
            int n;
            while ((n = in.read(buf)) != -1) {
                String chunk = new String(buf, 0, n);
                scan.append(chunk);
                if (scan.length() > 64_384) scan.delete(0, scan.length() - 64_384);
                String url = null;
                synchronized (lifecycle) {
                    if (!lifecycle.isCurrent(generation)) continue;
                    String lines = safeLog.append(chunk);
                    appendHostLog(lines);
                    startupDiagnostics.output(generation, lines);
                    if(startupDiagnostics.hasExplicitStartupFailure(generation)) {
                        failedWebPage(generation,startupDiagnostics.failureReason());
                        continue;
                    }
                    if (webAuthUrl.isEmpty()) url = com.deepseekharness.app.util.WebPortPolicy.authentication(scan.toString(),ports.listen);
                    if (url != null && !recovery.failed(generation)) {
                        activeWebPort=java.net.URI.create(url).getPort();
                        if(ports.fallback()) {
                            config.rememberFallbackWebPort(ports.preferred,activeWebPort);
                            reportStatus(generation,onStatus,com.deepseekharness.app.util.UiText.choose("实际 Web 端口：","Active Web port: ")+activeWebPort);
                        }
                        webAuthUrl = url;
                        webProc.recordIdentity();
                        setWebStage(generation, com.deepseekharness.app.util.UiText.text("服务已就绪，等待进入网页"));
                        lifecycle.finishStart(generation);
                        reportStatus(generation, onStatus, com.deepseekharness.app.util.UiText.text("鉴权链接已就绪，点「进入对话」即可进入 dsh"));
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
                            reportStatus(generation, onStatus, com.deepseekharness.app.util.UiText.text("局域网代理已就绪：同网段设备可访问，启动页可复制地址"));
                        }
                    }
                }
            }
        } catch (Exception ignored) {
        } finally {
            synchronized (lifecycle) { if (lifecycle.isCurrent(generation)) {
                String lines = safeLog.finish(); appendHostLog(lines); startupDiagnostics.output(generation, lines);
            } }
        }
        // 管道关闭不等于进程退出；不因读取结束或等待时长误判并启动第二个实例。
        final int exitCode;
        try { exitCode = p.waitFor(); }
        catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); return; }
        webLaunches.remove(p);
        synchronized (lifecycle) {
            if (!lifecycle.isCurrent(generation)) return;
            boolean hadAuth = !webAuthUrl.isEmpty();
            String exitReason = runtimeName + com.deepseekharness.app.util.UiText.text(" 进程退出，退出码 ") + exitCode
                    + (hadAuth ? com.deepseekharness.app.util.UiText.text("（鉴权后）") : com.deepseekharness.app.util.UiText.text("（鉴权前）"));
            startupDiagnostics.message(generation, exitReason);
            startupDiagnostics.preserveFailure(new File(proot.getRootfsDir(), "root/dsh-web.log"), exitReason);
            boolean namedFailure = startupDiagnostics.hasExplicitStartupFailure(generation) || startupDiagnostics.snapshot().issues.keySet().stream().anyMatch(name -> !name.isEmpty());
            if (com.deepseekharness.app.util.WebRuntimeFallback.shouldRetry(runtimeName, compatible, hadAuth,
                    namedFailure, lifecycle.isCurrent(generation), exitCode)) {
                reportStatus(generation, onStatus, com.deepseekharness.app.util.UiText.text("proroot 已退出，正在自动使用 proot 兼容重试；本轮只重试一次。"));
                io.execute(() -> retryWithProot(generation, onStatus, safeMode));
                return;
            }
            webAuthUrl = "";
            lifecycle.finishStart(generation);
            com.deepseekharness.app.LanProxyService.stop(generation);
            recordWebFailure(generation, exitReason);
            reportStatus(generation, onStatus, hadAuth ? com.deepseekharness.app.util.UiText.text("dsh 进程已退出")
                    : com.deepseekharness.app.util.UiText.text("启动失败：进程在鉴权前退出。下方保留实际错误；可查看恢复选项或安全启动。"));
        }
    }

    private void retryWithProot(long generation, Consumer<String> onStatus, boolean safeMode) {
        synchronized (lifecycle) { if (!lifecycle.isCurrent(generation) || !lifecycle.isStarting()) return; }
        com.deepseekharness.app.util.EnvironmentTaskGate.Lease lease =
                com.deepseekharness.app.util.EnvironmentTaskGate.tryAcquire(com.deepseekharness.app.util.UiText.text("proroot 退出后的兼容重试"));
        if (lease == null) {
            lifecycle.finishStart(generation);
            reportStatus(generation, onStatus, com.deepseekharness.app.util.UiText.text("环境任务正在进行，兼容重试未启动；完成后可选择 proot 再启动。"));
            return;
        }
        try (lease) {
            lease.run(() -> { startWeb(generation, onStatus, safeMode, false, true); return null; });
        } catch (Exception error) {
            lifecycle.finishStart(generation);
            reportStatus(generation, onStatus, com.deepseekharness.app.util.UiText.text("兼容重试失败：") + error.getMessage());
        }
    }

    public boolean isWebCompatibilityFallback() { return webCompatibilityFallback; }

    /** 提取鉴权链接：先严格（官方输出行），失败再宽松（直接扫 URL）。 */
    private String extractAuthUrl(String output) {
        return DshAuthUrl.findAny(output, getWebPort());
    }

    /** 把 dsh 输出脱敏后落到容器内 /root/dsh-web.log（排查用，鉴权 token 不落盘）。 */
    private void appendHostLog(String chunk) {
        try {
            File log = new File(proot.getRootfsDir(), "root/dsh-web.log");
            if (log.getParentFile() != null) log.getParentFile().mkdirs();
            Compat.append(log, com.deepseekharness.app.util.SensitiveData.redact(redactAuthUrl(chunk)).getBytes(StandardCharsets.UTF_8));
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
    private volatile String webAuthFailure = com.deepseekharness.app.util.UiText.text("Web 鉴权尚未就绪，请稍后重试");

    public String getWebAuthFailure() { return webAuthFailure; }

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
        com.deepseekharness.app.util.DshAuthSession.Result result =
                com.deepseekharness.app.util.DshAuthSession.exchange(url, java.net.URI.create(url).getPort(),
                        () -> lifecycle.isCurrent(generation) && url.equals(webAuthUrl));
            synchronized (lifecycle) {
                if (!lifecycle.isCurrent(generation) || !url.equals(webAuthUrl)) return null;
                webAuthFailure = result.message;
                String cookie = result.cookie;
                if (cookie != null) {
                    try {
                        com.deepseekharness.app.LanProxyService.setDshAuthCookie(cookie, generation);
                        if (config.isLanMode()) {
                            com.deepseekharness.app.LanProxyService.start(
                                    proot.getRootfsDir().getAbsolutePath(), ctx,
                                    java.net.URI.create(url).getPort(), generation);
                        }
                    } catch (Throwable ignored) {
                    }
                }
                return cookie;
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
            Log.w("DSHA", com.deepseekharness.app.util.UiText.text("等待停止失败"), e);
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
            startupDiagnostics.completed(previous,"stopped","");
            long generation = lifecycle.beginStop();
            webAuthUrl = "";
            // 宿主直接写小标记，不等可能仍在解压/注册插件的串行任务。
            try {
                File sentinel = stopSentinel();
                if (sentinel.getParentFile().isDirectory()) sentinel.createNewFile();
            } catch (Exception e) {
                Log.w("DSHA", com.deepseekharness.app.util.UiText.text("写停止标记失败，将由停止脚本重试"), e);
            }
            stopTask = io.submit(() -> {
                String stopError = "";
                try {
                    stopError = webProc.stop(); // 仍用原 PID 判据，绝不直接 destroy proot。
                    com.deepseekharness.app.LanProxyService.stop(previous);
                } finally {
                    synchronized (lifecycle) {
                        lifecycle.finishStop(generation);
                        reportStatus(generation, onStatus, stopError.isEmpty() ? com.deepseekharness.app.util.UiText.text("停止操作已完成") : stopError);
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
            return !config.isStartupRecoveryRequested() && !recovery.blocked() && lifecycle.canAutoStart(hasStopSentinel());
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
        synchronized (lifecycle) { if (lifecycle.isCurrent(generation)) {
            recovery.stage(generation, stage); startupDiagnostics.stage(generation, stage);
        } }
    }
    public StartupDiagnostics startupDiagnostics() { return startupDiagnostics; }

    /** 用户主动恢复：停止当前任务后再启动，慢启动也能使用此入口。 */
    public void recoverWeb(boolean safe, String disabledPlugin, Consumer<String> onStatus) {
        if (disabledPlugin != null && (!startupDiagnostics.snapshot().issues.containsKey(disabledPlugin)
                || com.deepseekharness.app.util.BuiltinPlugins.internal(disabledPlugin))) return;
        enqueueStop(null);
        final long stopped = lifecycle.generation();
        io.execute(() -> {
            if (!lifecycle.isCurrent(stopped) || lifecycle.isStopping()) return;
            if (disabledPlugin != null) {
                com.deepseekharness.app.util.EnvironmentTaskGate.Lease lease =
                        com.deepseekharness.app.util.EnvironmentTaskGate.tryAcquire(com.deepseekharness.app.util.UiText.text("停用故障插件"));
                if (lease == null) { reportStatus(stopped, onStatus, com.deepseekharness.app.util.UiText.text("正在进行环境任务，完成后再停用插件。")); return; }
                try (lease) {
                    String result = lease.run(() -> proot.setPluginEnabled(disabledPlugin, false));
                    if (!result.contains("BUILTIN_REGISTER_OK")) {
                        reportStatus(stopped, onStatus, com.deepseekharness.app.util.UiText.text("停用插件失败：") + result); return;
                    }
                } catch (Exception error) { reportStatus(stopped, onStatus, com.deepseekharness.app.util.UiText.text("停用插件失败：") + error.getMessage()); return; }
            }
            requestStart(onStatus, false, 0, safe);
        });
    }
    private void recordWebFailure(long generation, String reason) {
        synchronized (lifecycle) {
            if (lifecycle.isCurrent(generation)) recovery.stage(generation, startupDiagnostics.snapshot().stage);
            if (!lifecycle.isCurrent(generation) || !recovery.fail(generation, reason)) return;
            startupDiagnostics.completed(generation,"failed",reason);
            startupDiagnostics.preserveFailure(new File(proot.getRootfsDir(), "root/dsh-web.log"),reason);
            if(!startupDiagnostics.snapshot().browserReady || recovery.blocked())config.requestStartupRecovery(true);
            DiagnosticLog.record(ctx, "WEB_FAILURE", config.getWebFailureStage() + com.deepseekharness.app.util.UiText.text("：") + reason);
            if (recovery.blocked()) {
                // 立即撤销失败代次；停止仍只使用哨兵和 Web PID，不杀容器启动器。
                stopWeb(null);
            }
        }
    }

    /** 明确的网页启动失败进入原生恢复；慢启动与普通控制台错误不调用此入口。 */
    public void failedWebPage(long generation,String reason) {
        synchronized(lifecycle) {
            if(!lifecycle.isCurrent(generation))return;
            recordWebFailure(generation,reason);
            config.requestStartupRecovery(true);
            stopWeb(null);
        }
    }

    /** 仅给当前仍在启动的代次提示，不累计失败、不写停止标记、不终止进程。 */
    void reportSlowStart(long generation, Consumer<String> onStatus) {
        synchronized (lifecycle) {
            if (lifecycle.isCurrent(generation) && lifecycle.isStarting())
                reportStatus(generation, onStatus, com.deepseekharness.app.util.UiText.text("启动时间较长，仍在等待鉴权；可查看日志或手动停止"));
        }
    }

    /** 发布前检查代次；UI 入队后还要再检查，防主线程消费到旧消息。 */
    private void reportStatus(long generation, Consumer<String> onStatus, String message) {
        synchronized (lifecycle) {
            if (!lifecycle.isCurrent(generation)) return;
            DiagnosticLog.record(ctx, "WEB_START_STOP", message);
            startupDiagnostics.message(generation, message);
            if (onStatus == null) return;
            try {
                onStatus.accept(message);
            } catch (RuntimeException e) {
                Log.w("DSHA", com.deepseekharness.app.util.UiText.text("启动状态回调失败"), e);
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
                    ? com.deepseekharness.app.util.UiText.text("配置已重置，对话记录已保留\n（.env 已按当前配置重写）")
                    : com.deepseekharness.app.util.UiText.text("没有可重置的配置（.env 已重写）");
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
