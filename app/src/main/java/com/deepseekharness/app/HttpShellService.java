package com.deepseekharness.app;
import com.deepseekharness.app.util.Compat;

import com.deepseekharness.app.util.Constants;
import com.deepseekharness.app.util.Query;
import com.deepseekharness.app.core.HarnessController;
import com.deepseekharness.app.runtime.TarGzipExtractor;
import com.deepseekharness.app.util.SensitiveData;
import com.deepseekharness.app.util.BridgeLifecycle;
import com.deepseekharness.app.util.BridgeQuestions;
import com.deepseekharness.app.util.BoundedUiCall;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;

import androidx.core.app.NotificationCompat;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URLDecoder;
import java.util.concurrent.TimeUnit;

/**
 * 极简 HTTP 服务（host 侧，端口 3090），把 Shizuku shell 能力桥接给 rootfs 里的助手。
 * rootfs 内的 agent 可用 bash 工具执行：
 *   curl -s "http://127.0.0.1:3090/exec?cmd=<urlencoded>"
 * 返回 JSON：{"result":"...输出...[EXIT=0]"}
 *
 * 设备命令使用默认拒绝的原生策略；高危与未知命令不可通过确认开关放行。
 */
public final class HttpShellService {

    public static final int PORT = 3090;
    private static final String CONFIRM_CHANNEL = "dsh_confirm_channel";
    private static final int CONFIRM_NOTIF_ID = Constants.NOTIF_SHELL_CONFIRM;
    /** 智能体通知：与确认通知使用不同 id / requestCode，互不覆盖。 */
    private static final int AGENT_NOTIF_ID = 2002;
    private static final int AGENT_NOTIF_REQUEST = 2002;
    private static final long CONFIRM_TIMEOUT_S = 60;

    /** Error text can echo a URL/header supplied by the caller; responses and
     * diagnostics must never expose credentials. This only sanitizes text for
     * display/logging and is never used for the command or network request. */
    private static String safeError(Throwable e) {
        return SensitiveData.redact(String.valueOf(e));
    }

    private static String safeDisplay(String value) {
        return SensitiveData.redact(value == null ? "" : value);
    }

    private static volatile HttpShellService instance;
    /** 启动占位与已绑定状态分开；资源清理结束后才允许下一次重试。 */
    private static final BridgeLifecycle LIFECYCLE = new BridgeLifecycle();
    private static final com.deepseekharness.app.util.SharedServiceLeases<HttpShellService> DEMANDS =
            new com.deepseekharness.app.util.SharedServiceLeases<>(HttpShellService::startListener, HttpShellService::stopListener);
    public static final class Lease implements AutoCloseable {
        private final com.deepseekharness.app.util.SharedServiceLeases<HttpShellService>.Lease demand;
        private Lease(com.deepseekharness.app.util.SharedServiceLeases<HttpShellService>.Lease demand) { this.demand = demand; }
        public void ensureStarted() { demand.ensureStarted(); }
        @Override public void close() { demand.close(); }
    }
    /** 调用入口持有自己的需求；关闭入口不能停止仍被其它入口使用的监听。 */
    public static Lease acquire(Context context) { return new Lease(DEMANDS.acquire(() -> new HttpShellService(context))); }
    private Lease legacyLease;

    private final Context ctx;
    private final java.io.File fixtureTokenFile;
    private final java.util.function.Consumer<BridgeAskDialog> fixtureAskObserver;
    /** 绑定前也必须知道本轮 token 的文件归属；instance 仍仅发布已经就绪的监听。 */
    private static volatile HttpShellService tokenOwner;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final com.deepseekharness.app.util.BridgeConfirmations confirmations = new com.deepseekharness.app.util.BridgeConfirmations();
    private volatile PendingConfirmation pendingConfirm; // 与 LIFECYCLE 共用锁，旧 run 不能清理新请求。
    private static final class PendingConfirmation {
        final com.deepseekharness.app.util.BridgeConfirmations.Request request;
        volatile PendingIntent allow, deny;
        androidx.appcompat.app.AlertDialog dialog;
        PendingConfirmation(com.deepseekharness.app.util.BridgeConfirmations.Request request) { this.request = request; }
    }
    private final BridgeQuestions questions = new BridgeQuestions();
    private volatile boolean running;
    private volatile BridgeRun activeRun;
    private final ThreadLocal<BridgeRun> requestRun = new ThreadLocal<>();

    /** 每一轮监听独立持有 socket/线程池，迟到的旧线程只能清理自己的资源。 */
    private static final class BridgeRun {
        final long generation;
        volatile ServerSocket server, server6;
        final java.util.Set<Socket> clients = java.util.Collections.newSetFromMap(new java.util.concurrent.ConcurrentHashMap<>());
        final java.util.concurrent.ExecutorService pool = new java.util.concurrent.ThreadPoolExecutor(
                4, 4, 0, TimeUnit.MILLISECONDS, new java.util.concurrent.ArrayBlockingQueue<>(16), r -> {
                    Thread t = new Thread(r, "http-shell"); t.setDaemon(true); return t;
                });
        BridgeRun(long generation) { this.generation = generation; }
    }
    /** 鉴权 token（随机生成，rootfs 内 agent 通过它访问；外部网络无法到达 127.0.0.1）。
     *  每次 start 都会和 rootfs 文件对账：文件存在则沿用，缺失/内容异常则轮换重写，
     *  防止重解压 rootfs 后内存 token 与文件不一致导致 agent 无法认证。 */
    private static volatile String authToken = "";
    /** token 持久化位置（rootfs 内 agent 可读，建议 0600） */

    public HttpShellService(Context ctx) {
        this(ctx, null);
    }

    /** debug 自插桩使用隔离文件；正式构造仍走原来的 rootfs token。 */
    HttpShellService(Context ctx, java.io.File fixtureTokenFile) {
        this(ctx, fixtureTokenFile, null);
    }

    /** 仅观察 debug fixture 的真实默认 HTTP 提问，不替换其前台宿主选择。 */
    HttpShellService(Context ctx, java.io.File fixtureTokenFile,
                     java.util.function.Consumer<BridgeAskDialog> fixtureAskObserver) {
        if (fixtureTokenFile != null && !BuildConfig.DEBUG) throw new IllegalStateException(com.deepseekharness.app.util.UiText.text("仅 debug 可注入 token 文件"));
        if (fixtureAskObserver != null && (!BuildConfig.DEBUG || fixtureTokenFile == null))
            throw new IllegalStateException(com.deepseekharness.app.util.UiText.text("仅隔离 debug fixture 可观察提问窗口"));
        this.ctx = ctx.getApplicationContext();
        this.fixtureTokenFile = fixtureTokenFile;
        this.fixtureAskObserver = fixtureAskObserver;
    }

    public static HttpShellService instance() {
        synchronized (LIFECYCLE) { return isReady() ? instance : null; }
    }

    /** 供设备桥保活判断；仅 IPv4 主监听已绑定并存活才算就绪。 */
    public static boolean isReady() {
        synchronized (LIFECYCLE) {
            HttpShellService current = instance;
            BridgeRun run = current == null ? null : current.activeRun;
            return LIFECYCLE.isReady() && current != null && current.running && run != null
                    && run.server != null && run.server.isBound() && !run.server.isClosed();
        }
    }

    public static boolean isStarting() { return LIFECYCLE.isStarting(); }
    public static java.util.Map<String,Long> resourceMetrics(){
        HttpShellService current=instance();BridgeRun run=current==null?null:current.activeRun;
        if(run==null)return java.util.Collections.emptyMap();var pool=(java.util.concurrent.ThreadPoolExecutor)run.pool;
        return java.util.Map.of("connections",(long)run.clients.size(),"queued",(long)pool.getQueue().size(),"threads",(long)pool.getPoolSize());
    }

    /** 桥还没启动过时的兜底 Context。
     *
     *  <p>{@link #tokenFileIfPossible()} 原先只从 {@code instance().ctx} 取 Context，
     *  于是桥没启动过时（比如用户把「设备桥」和「悬浮条」都关着）它返回 null，
     *  {@link #ensureToken()} 只改内存、**静默不写文件**。自检因此谎报「已重新写入」，
     *  而容器侧 selftest 同时报「缺 .bridge_token」—— 两份报告自相矛盾，真机上出现过。 */
    private static volatile Context tokenCtx;

    /** 自检、恢复备份这类在桥启动前就要对齐 token 的场合，先把 Context 交给它。 */
    static void bindTokenContext(Context ctx) {
        if (ctx != null) tokenCtx = ctx.getApplicationContext();
    }

    private static java.io.File tokenFileIfPossible() {
        Context c = null;
        try {
            HttpShellService current = tokenOwner;
            if (current == null) current = instance;
            if (current != null && current.fixtureTokenFile != null) return current.fixtureTokenFile;
            c = current == null ? null : current.ctx;
        } catch (Throwable ignored) {
        }
        if (c == null) c = tokenCtx;
        if (c == null) return null;
        try {
            HarnessController hc = HarnessController.get(c);
            if (hc != null && hc.getProot() != null && hc.getProot().getRootfsDir() != null) {
                return new java.io.File(hc.getProot().getRootfsDir(), "root/.dsh/.bridge_token");
            }
        } catch (Throwable ignored) {
        }
        return null;
    }

    /** 读取 rootfs 内 token 文件（只读，不修改内容）。 */
    private static String readTokenFromFile(java.io.File tf) {
        if (tf == null || !tf.isFile()) return null;
        try {
            String s = new String(Compat.readAllBytes(tf),
                    java.nio.charset.StandardCharsets.UTF_8).trim();
            if (s.isEmpty() || s.length() > 128) return null;
            // 只允许可安全放入 URL/Header 的一半字符，拒绝换行等脏内容
            if (!s.matches("[A-Za-z0-9_-]+")) return null;
            return s;
        } catch (Throwable ignored) {
            return null;
        }
    }

    /** 生成/对账 token（rootfs 文件优先；缺失或无效则轮换并写入）。
     *  注：token 属于 rootfs 内 agent 访问 3090 桥的共享凭据，不做 0600 之外的额外加密。 */
    private static String ensureToken() {
        synchronized (HttpShellService.class) {
            java.io.File tf = tokenFileIfPossible();
            String fromFile = readTokenFromFile(tf);
            if (fromFile != null && !fromFile.isEmpty()) {
                authToken = fromFile;
                return authToken;
            }
            // 无文件或内容无效 → 轮换（不能用旧内存值，否则 agent 读到的文件永远不会出现）
            String t = java.util.UUID.randomUUID().toString().replace("-", "").substring(0, 32);
            authToken = t;
            if (tf != null) {
                try {
                    if (tf.getParentFile() != null) tf.getParentFile().mkdirs();
                    Compat.write(tf, t.getBytes(java.nio.charset.StandardCharsets.UTF_8));
                    try {
                        Compat.chmod(tf, "rw-------");
                    } catch (Throwable e) {
            android.util.Log.w("DSHA", com.deepseekharness.app.util.UiText.text("token 文件写入失败，3090 桥将无法鉴权: ") + safeError(e));
        }
                } catch (Throwable e) {
            android.util.Log.w("DSHA", com.deepseekharness.app.util.UiText.text("token 文件读取/清理失败: ") + safeError(e));
        }
            }
            return authToken;
        }
    }

    /** 最近一次绑定结果：空 = 正常；非空 = 失败原因（自检与诊断读它）。
     *  端口被别的应用占掉时，症状和当年那个「只绑 ::1」的 bug 一模一样
     *  （agent 调什么都超时、确认弹窗不出现），所以必须留下明确的失败原因。 */
    private static volatile String bindError = "";

    public static String bindError() {
        return bindError;
    }

    private void noteBindOk() {
        bindError = "";
        writeBridgeStatus("ok port=" + PORT);
    }

    private void noteBindError(String why) {
        String safe = safeDisplay(why);
        bindError = safe;
        android.util.Log.e("DSHA", com.deepseekharness.app.util.UiText.text("3090 桥绑定失败：") + safe);
        com.deepseekharness.app.core.DiagnosticLog.record(ctx, "BRIDGE_BIND", safe);
        writeBridgeStatus("fail " + safe);
    }

    /** 桥状态落到 rootfs 的 /root/.dsh/.bridge_status，容器里 cat 一下就知道桥为什么不通 */
    private void writeBridgeStatus(String s) {
        try {
            java.io.File tf = tokenFileIfPossible();
            if (tf == null || tf.getParentFile() == null) return;
            java.io.File f = new java.io.File(tf.getParentFile(), ".bridge_status");
            if (!f.getParentFile().isDirectory() && !f.getParentFile().mkdirs()) return;
            Compat.write(f, (s + "\n").getBytes(java.nio.charset.StandardCharsets.UTF_8));
        } catch (Throwable ignored) {
        }
    }

    /** 兼容隔离夹具；正式入口使用 acquire 并显式持有 lease。 */
    public synchronized void start() {
        if (legacyLease == null) legacyLease = new Lease(DEMANDS.acquire(() -> this));
        else legacyLease.ensureStarted();
    }

    private void startListener() {
        synchronized (LIFECYCLE) {
            long generation = LIFECYCLE.beginStart();
            if (generation < 0) return;
            try {
                BridgeRun run = new BridgeRun(generation);
                activeRun = run;
                tokenOwner = this;
                bindTokenContext(ctx);
                Thread t = new Thread(() -> bindAndServe(run), "http-shell-accept");
                t.setDaemon(true);
                t.start();
            } catch (RuntimeException error) {
                if (activeRun != null) finishRun(activeRun, safeError(error));
                else { noteBindError(safeError(error)); LIFECYCLE.finish(generation); }
            }
        }
    }

    private void bindAndServe(BridgeRun run) {
        String failure = null;
        try {
            synchronized (LIFECYCLE) {
                if (!LIFECYCLE.isCurrent(run.generation) || activeRun != run) return;
                ensureToken();
                run.server = new ServerSocket();
            }
            // IPv4 是主通道；失败时不得启动仅 IPv6 的假就绪服务。
            run.server.setReuseAddress(true);
            run.server.bind(new java.net.InetSocketAddress(java.net.InetAddress.getByName("127.0.0.1"), PORT));
            synchronized (LIFECYCLE) {
                if (activeRun != run || !LIFECYCLE.publish(run.generation)) return;
                running = true;
                instance = this;
                noteBindOk();
                Thread t6 = new Thread(() -> serveIpv6(run), "http-shell-accept6");
                t6.setDaemon(true);
                t6.start();
            }
            acceptLoop(run, run.server);
        } catch (java.net.BindException error) {
            failure = com.deepseekharness.app.util.UiText.choose("设备桥端口 3090 被占用，设备工具暂不可用；Web 可独立启动。关闭占用端口的其他实例后重试：", "Device bridge port 3090 is in use. Device tools are temporarily unavailable; Web can still start. Close the other instance using this port and retry: ") + safeError(error);
        } catch (IOException | RuntimeException error) {
            failure = safeError(error);
        } finally {
            finishRun(run, failure);
        }
    }

    private void serveIpv6(BridgeRun run) {
        try {
            synchronized (LIFECYCLE) {
                if (!running || activeRun != run || !LIFECYCLE.isCurrent(run.generation)) return;
                run.server6 = new ServerSocket();
            }
            run.server6.setReuseAddress(true);
            run.server6.bind(new java.net.InetSocketAddress(java.net.InetAddress.getByName("::1"), PORT));
            acceptLoop(run, run.server6);
        } catch (IOException | RuntimeException error) {
            if (activeRun == run && running) android.util.Log.i("DSHA", com.deepseekharness.app.util.UiText.text("3090 的 IPv6 附加监听不可用：") + safeError(error));
        } finally { closeSocket(run.server6); }
    }

    /** 接受连接并分发到线程池（IPv4/IPv6 两个监听共用） */
    private void acceptLoop(BridgeRun run, ServerSocket ss) throws IOException {
        while (running && activeRun == run) {
            Socket client = ss.accept();
            try {
                // 排队时间计入请求头总截止时间；鉴权后的命令/确认不受此预算影响。
                client.setSoTimeout(com.deepseekharness.app.util.HttpProtocol.BRIDGE.timeoutMs);
                synchronized (LIFECYCLE) {
                    if (!running || activeRun != run) { client.close(); return; }
                    if(run.clients.size()>=20){closeSocket(client);continue;}
                    long headerDeadline=com.deepseekharness.app.util.HttpProtocol.deadline(com.deepseekharness.app.util.HttpProtocol.BRIDGE.timeoutMs);
                    run.clients.add(client);
                    run.pool.execute(() -> {
                        requestRun.set(run);
                        try { handle(client,headerDeadline); }
                        finally { requestRun.remove(); run.clients.remove(client); }
                    });
                }
            } catch (java.util.concurrent.RejectedExecutionException busy) {
                run.clients.remove(client);
                closeSocket(client); // 有界队列已满，不能无限积攒连接和文件描述符。
            } catch (IOException | RuntimeException error) {
                run.clients.remove(client);
                closeSocket(client);
                throw error;
            }
        }
    }

    public synchronized void stop() {
        if (legacyLease != null) { legacyLease.close(); legacyLease = null; }
    }

    private void stopListener() {
        BridgeRun run = activeRun;
        if (run != null) finishRun(run, null);
    }

    private void finishRun(BridgeRun run, String failure) {
        synchronized (LIFECYCLE) {
            // 旧 accept 线程的 finally 可以迟到，但不能清空新实例的就绪状态。
            if (activeRun != run || !LIFECYCLE.isCurrent(run.generation)) return;
            running = false;
            try { revokeScreenGrant(); } catch (RuntimeException error) { android.util.Log.w("DSHA", "BRIDGE_GRANT_REVOKE", error); }
            if (instance == this) instance = null;
            closeSocket(run.server);
            closeSocket(run.server6);
            for (Socket client : run.clients) closeSocket(client);
            run.clients.clear();
            run.pool.shutdownNow();
            questions.stop();
            if (pendingConfirm != null) finishConfirmation(pendingConfirm);
            if (failure == null) writeBridgeStatus("stopped");
            else noteBindError(failure);
            activeRun = null;
            if (tokenOwner == this) tokenOwner = null;
            if (fixtureTokenFile != null) { authToken = ""; tokenCtx = null; }
            LIFECYCLE.finish(run.generation);
        }
    }

    private static void closeSocket(java.io.Closeable socket) {
        if (socket != null) try { socket.close(); } catch (IOException ignored) { }
    }

    /** 校验查询串/头中的 token（常量时间比较 + URL 解码容错） */
    /** 当前桥 token；桥还没起来时返回空串（调用方按「不带 token」处理）。
     *  WebView 首帧 URL 与局域网代理都要用它 —— dsh 的 Web 服务已加 token 鉴权
     *  （webserver-auth-patch.sh），不带 token 会 403。 */
    public static String currentToken() {
        try {
            String t = authToken.isEmpty() ? ensureToken() : authToken;
            return t == null ? "" : t;
        } catch (Throwable e) {
            return "";
        }
    }

    /** 自检用：当前内存里的桥 token 快照（空串 = 桥还没起来过）。
     *  故意不触发生成 —— 自检本身不该有副作用，写文件那是
     *  {@link #resetTokenAfterRestore()} 的活儿。 */
    static String tokenSnapshot() {
        return authToken == null ? "" : authToken;
    }

    /** 恢复备份后重新对齐 3090 桥的 token。
     *
     *  <p>老备份包里带着**备份那台机器**的 {@code .dsh/.bridge_token}（新版备份已经把它
     *  排除了）。恢复出来之后 rootfs 里是旧 token，而 App 进程内的 {@link #authToken}
     *  还是当前那个 —— 它是静态字段，{@link #ensureToken()} 只在缓存为空时才读文件。
     *  于是 App 用自己的 token 拼 WebView 首帧 URL，dsh 后端却按恢复出来的旧 token 校验，
     *  用户看到的就是「DSHA：需要 token，请在 DSHA 应用内打开」。
     *
     *  <p>处理：删掉恢复出来的 token 文件、清空内存缓存，再让 ensureToken 重新生成并写回，
     *  两侧重新对齐。dsh 后端自己也缓存了 token（webserver-auth-patch 里的
     *  {@code __dshaTokenCache}），所以要重启 Web 才彻底生效 —— 恢复流程本来就提示重启。 */
    public static void resetTokenAfterRestore() {
        try {
            java.io.File tf = tokenFileIfPossible();
            if (tf != null && tf.isFile()) {
                //noinspection ResultOfMethodCallIgnored
                tf.delete();
            }
            authToken = "";
            ensureToken();
            android.util.Log.i("DSHA", com.deepseekharness.app.util.UiText.text("恢复后已重置 3090 桥 token（老备份里带的是别的机器的）"));
        } catch (Throwable e) {
            android.util.Log.w("DSHA", com.deepseekharness.app.util.UiText.text("恢复后重置桥 token 失败: ") + safeError(e));
        }
    }

    private static boolean tokenMatch(String presented) {
        String token = authToken.isEmpty() ? ensureToken() : authToken;
        return token != null && !token.isEmpty() && LanAuth.constantTimeEquals(token, presented);
    }

    private void handle(Socket client,long headerDeadline) {
        try (Socket c = client) {
            var request=com.deepseekharness.app.util.HttpProtocol.readHead(new java.io.BufferedInputStream(c.getInputStream(),8192),c,
                    com.deepseekharness.app.util.HttpProtocol.BRIDGE,headerDeadline,true);
            if(request==null)return;
            synchronized (LIFECYCLE) {
                if (!running || requestRun.get() != activeRun) return;
            }
            // 当前设备桥全部为查询串 GET 路由；正文与其它方法不能被静默忽略。
            if(!request.method.equals("GET")||request.requestBody().kind!=com.deepseekharness.app.util.HttpProtocol.Kind.NONE){
                c.getOutputStream().write("HTTP/1.1 405 Method Not Allowed\r\nAllow: GET\r\nContent-Length: 0\r\nConnection: close\r\n\r\n".getBytes(java.nio.charset.StandardCharsets.US_ASCII));return;
            }
            c.setSoTimeout(0);String path=request.target;
            String cmd = "";
            String route = path.split("\\?", 2)[0];
            if (route.equals("/exec") || route.equals("/confirm") || route.equals("/device/plan") || route.equals("/device/execute")) {
                // 走统一的查询串解析（Query.param）：值要截断到 &，参数名要精确匹配。
                // 旧实现是 path.indexOf("cmd=") —— 值截断修过了，但参数名边界一直没有，
                // 于是 ?xcmd=junk&cmd=真命令 会取到 junk。/confirm 的 cmd 是<b>给用户看的
                // 命令原文</b>，取错就等于让用户批准了一条与实际不符的命令。
                cmd = getParam(queryOf(path), "cmd", "");
            }
            // 鉴权：token 必须匹配（通过 ?token= 或 X-Token header）
            boolean authed = false;
            String t = "";
            // 解析与 LanProxyService 共用 LanAuth 那一份。原来这里是
            // query.indexOf("token=")，没有参数名边界：?xtoken=junk&token=真值
            // 会先命中 xtoken= 取到 junk 而误拒。两处各写一套判断正是本项目
            // 反复栽的模式，合并后由 tools/pure-logic-test.sh 一起覆盖。
            String qt = LanAuth.queryTokenFromTarget(path);
            if (qt != null && !qt.isEmpty()) {
                try { qt = URLDecoder.decode(qt, "UTF-8"); } catch (Exception ignored) { }
                t = qt;
                authed = tokenMatch(qt.trim());
            }
            if (!authed) {
                // 也支持 header 传 token（agent 引导用 curl -H）
                java.util.List<String> headers=request.values("X-Token");
                if(headers.size()==1)authed=tokenMatch(headers.get(0));
            }
            // 预连接与未完成鉴权的 socket 不读写环境，不能占住维护屏障。
            try (com.deepseekharness.app.core.RuntimeTasks work = authed
                    ? com.deepseekharness.app.core.RuntimeTasks.begin() : null) {
            String result;
            if (!authed) {
                result = "[UNAUTHORIZED]";
            } else if (route.equals("/device/plan")) {
                result = devicePlan(cmd, "1".equals(getParam(queryOf(path), "su", "0")));
            } else if (route.equals("/device/execute")) {
                result = deviceExecute(cmd, "1".equals(getParam(queryOf(path), "su", "0")),
                        "1".equals(getParam(queryOf(path), "adb", "0")));
            } else if (route.equals("/app/notify")) {
                // agent 通过 App 发通知栏提醒（App 层交互）
                result = appNotify(path);
            } else if (route.equals("/app/toast")) {
                // agent 弹 App 内 Toast
                result = appToast(path);
            } else if (route.equals("/app/readfile")) {
                // agent 读外部文件（rootfs 挂载 /sdcard 的补充；支持路径参数）
                result = appReadFile(path);
            } else if (route.equals("/health")) {
                result = "OK"; // 存活探测（仍需 token）：客户端可据此区分「桥没起」与「命令失败」
            } else if (route.startsWith("/app/ui/")) {
                // 唯一保留前缀的组：它是一个端点命名空间，真子路径在 appUi 内再精确分发
                result = appUi(path);
            } else if (route.startsWith("/app/vscreen/")) {
                result = appVscreen(path);
            } else if (route.equals("/app/device")) {
                result = appDevice();
            } else if (route.equals("/app/apps")) {
                result = appList(path);
            } else if (route.equals("/app/launch")) {
                result = appLaunch(path);
            } else if (route.equals("/app/clip")) {
                result = appClip(path);
            } else if (route.equals("/app/share")) {
                result = appShare(path);
            } else if (route.equals("/app/open")) {
                result = appOpen(path);
            } else if (route.equals("/app/vibrate")) {
                result = appVibrate(path);
            } else if (route.equals("/app/ask")) {
                result = appAsk(path);
            } else if (route.equals("/app/version")) {
                result = appVersion();
            } else if (route.equals("/app/help")) {
                result = appHelp();
            } else if (route.equals("/app/plugins")) {
                result = appPlugins(path);
            } else if (route.equals("/app/overlay")) {
                result = appOverlay(path);
            } else if (route.equals("/app/location")) {
                // 位置 / 传感器 / 手电：手机相对服务器真正独有的那几样能力。
                // /app/sensors 与 /app/sensor 是两条独立精确路由，不再有先后依赖。
                result = DeviceSense.location(ctx, "1".equals(getParam(queryOf(path), "fresh", "")));
            } else if (route.equals("/app/sensors")) {
                result = DeviceSense.sensorList(ctx);
            } else if (route.equals("/app/sensor")) {
                result = DeviceSense.sensorRead(ctx, getParam(queryOf(path), "name", "light"));
            } else if (route.equals("/app/torch")) {
                String on = getParam(queryOf(path), "on", "1");
                result = DeviceSense.torch(ctx, !"0".equals(on) && !"off".equalsIgnoreCase(on));
            } else if (route.equals("/app/export")) {
                result = appExport(path);
            } else if (cmd.isEmpty()) {
                result = "[NO_CMD]";
            } else if (route.equals("/confirm")) {
                com.deepseekharness.app.util.DeviceShellPolicy.Plan plan = com.deepseekharness.app.util.DeviceShellPolicy.inspect(cmd);
                // 旧确认接口不能验证实际路径和最新 UID，不再为写入或结束进程发放放行信号。
                // 这些操作必须交给 /exec 或 ADB 原生计划执行器现场校验。
                result = plan.kind == com.deepseekharness.app.util.DeviceShellPolicy.Kind.READ ? "YES" : "NO";
            } else if (route.equals("/exec")) {
                org.json.JSONObject execution = new org.json.JSONObject(deviceExecute(cmd, false, false));
                result = execution.optString("output", com.deepseekharness.app.util.UiText.text("[ADB_REQUIRED] 请使用 adb-shell 执行此命令\n[EXIT=124]"));
            } else {
                result = "[UNKNOWN_ENDPOINT]";
            }
            // 关键：result 必须包引号 —— 旧实现输出 {"result":YES} 是非法 JSON，
            // 客户端（adb-shell.py 判 '"YES"' in body / agent 用 json 解析）全部失效：
            // 用户点「允许」也会被当成拒绝。
            String body = "{\"result\":\"" + jsonEscape(result) + "\"}";
            byte[] bodyBytes = body.getBytes("UTF-8");
            String head = "HTTP/1.1 200 OK\r\n"
                    + "Content-Type: application/json; charset=utf-8\r\n"
                    + "Content-Length: " + bodyBytes.length + "\r\n"
                    + "Connection: close\r\n\r\n";
            c.getOutputStream().write(head.getBytes("UTF-8"));
            c.getOutputStream().write(bodyBytes);
            c.getOutputStream().flush();
            }
        } catch (Exception ignored) {
        }
    }

    // ================= App 层交互端点（agent 通过 3090 桥调用） =================

    /** 在发送命令前选一次通道；只有明确返回 ADB 计划时客户端才可连接 ADB。 */
    private String deviceExecute(String command, boolean forceRoot, boolean forceAdb) {
        try {
            String planned = devicePlan(command, forceRoot);
            if (!planned.startsWith("{")) return completedExecution("policy", planned + "\n[EXIT=126]");
            org.json.JSONObject plan = new org.json.JSONObject(planned);
            if (plan.optString("kind").equals("DENY"))
                return completedExecution("policy", plan.optString("reason") + "\n[EXIT=126]");
            boolean sms = "sms.read".equals(plan.optString("capability"));
            if (!forceAdb && RootShell.enabled(ctx) && RootShell.present()) {
                int user = sms ? android.os.Process.myUid() / 100000 : -1;
                return completedExecution("root", RootShell.exec(ctx, command, user));
            }
            if (!forceAdb && !forceRoot && !sms && ShizukuShell.hasPermission()) {
                return completedExecution("shizuku", ShizukuShell.exec(command));
            }
            if (!DeviceBridgeService.isAdbEnabled(ctx))
                return completedExecution("none", com.deepseekharness.app.util.UiText.text("[DEVICE_CHANNEL_UNAVAILABLE] 请在设置 → 设备能力授权中连接 root、Shizuku 或 ADB 通道\n[EXIT=124]"));
            plan.put("su", forceRoot);
            return new org.json.JSONObject().put("state", "adb").put("plan", plan).toString();
        } catch (Exception error) {
            // 此时可能已经向设备发过命令，绝不能返回可重试的 ADB 计划。
            return completedExecution("unknown", "[EXECUTION_UNKNOWN] " + safeError(error) + "\n[EXIT=125]");
        }
    }
    private String completedExecution(String transport, String output) {
        try {
            return new org.json.JSONObject().put("state", "completed").put("transport", transport)
                    .put("output", output).put("exit", com.deepseekharness.app.util.DeviceCommandResult.exitCode(output)).toString();
        } catch (org.json.JSONException error) { return com.deepseekharness.app.util.UiText.text("[EXECUTION_UNKNOWN] 结果编码失败\n[EXIT=125]"); }
    }

    /** ADB 每次发送前向原生策略取计划；计划只包含已验证 argv，拒绝时没有可执行内容。 */
    private String devicePlan(String command, boolean root) {
        try {
            com.deepseekharness.app.util.DeviceShellPolicy.Plan plan = com.deepseekharness.app.util.DeviceShellPolicy.inspect(command);
            org.json.JSONObject value = new org.json.JSONObject().put("version", 1).put("kind", plan.kind.name())
                    .put("reason", plan.reason).put("argv", new org.json.JSONArray(plan.argv))
                    .put("operands", new org.json.JSONArray(plan.operands))
                    .put("paths", new org.json.JSONObject(com.deepseekharness.app.util.DeviceShellPolicy.pathRules()));
            if (root && !ctx.getSharedPreferences(Constants.PREFS, 0).getBoolean(Constants.KEY_ALLOW_ROOT_SHELL, false))
                return value.put("kind", "DENY").put("argv", new org.json.JSONArray()).put("reason", com.deepseekharness.app.util.UiText.text("[POLICY_BLOCKED] 未允许 root shell")).toString();
            if (plan.kind == com.deepseekharness.app.util.DeviceShellPolicy.Kind.SENSITIVE_READ) {
                java.util.List<String> query = com.deepseekharness.app.util.SmsQuery.forUser(plan.argv, android.os.Process.myUid() / 100000);
                if (!new com.deepseekharness.app.core.DeviceGrants(ctx).smsReadAllowed())
                    return value.put("kind", "DENY").put("argv", new org.json.JSONArray())
                            .put("reason", com.deepseekharness.app.util.UiText.choose("[POLICY_BLOCKED] 短信读取已关闭，请在设备能力授权中开启", "[POLICY_BLOCKED] SMS reading is off. Enable it in Device permissions")).toString();
                value.put("kind", "READ").put("argv", new org.json.JSONArray(query))
                        .put("capability", "sms.read").put("authorization", "remembered");
            }
            if (plan.kind == com.deepseekharness.app.util.DeviceShellPolicy.Kind.STOP) {
                // PackageManager 可能被厂商限制，只返回部分应用。这里只解析目标；
                // 实际 ADB/Shizuku 执行器必须用自己的身份现场取得完整 pm/ps 清单才能停止。
                value.put("requiresAppInventory", true);
            }
            return value.toString();
        } catch (Exception error) {
            return "[POLICY_BLOCKED] " + safeError(error);
        }
    }

    /** /app/notify?title=&text= ：发通知栏提醒 */
    private String appNotify(String path) {
        try {
            // App 前台时不发通知（用户正看着页面，不打扰）——与前台抑制一致
            if (ForegroundActivity.current() != null) return "FOREGROUND_SKIP";
            String q = queryOf(path);
            String title = getParam(q, "title", com.deepseekharness.app.util.UiText.text("DSHA 通知"));
            String text = getParam(q, "text", "");
            if (text.isEmpty()) return "NO_TEXT";
            title = safeDisplay(title);
            text = safeDisplay(text);
            if(title.startsWith("DSHA · ")) {
                title=com.deepseekharness.app.util.UiText.text(title);
                text=com.deepseekharness.app.util.UiText.text(text);
            }
            NotificationManager nm = (NotificationManager) ctx.getSystemService(Context.NOTIFICATION_SERVICE);
            if (nm == null) return "NO_SERVICE";
            if (Build.VERSION.SDK_INT >= 26) {
                NotificationChannel ch = new NotificationChannel(
                        "dsh_agent_channel", com.deepseekharness.app.util.UiText.text("Agent 通知"),
                        NotificationManager.IMPORTANCE_HIGH);
                ch.setDescription(com.deepseekharness.app.util.UiText.text("智能体通过 App 发送的通知"));
                nm.createNotificationChannel(ch);
            }
            NotificationCompat.Builder b = new NotificationCompat.Builder(ctx, "dsh_agent_channel")
                    .setSmallIcon(R.drawable.ic_launch)
                    .setContentTitle(title)
                    .setContentText(text)
                    .setStyle(new NotificationCompat.BigTextStyle().bigText(text))
                    .setPriority(NotificationCompat.PRIORITY_HIGH)
                    // 正文点击回到 App；操作按钮仍分别走安全确认接收器。
                    .setContentIntent(agentNotificationIntent())
                    .setAutoCancel(true);
            nm.notify(AGENT_NOTIF_ID, b.build());
            return "OK";
        } catch (Throwable e) {
            return "ERROR: " + safeError(e);
        }
    }

    /**
     * 智能体通知的点击出口：回到 App 并<b>直接进入 Web 会话</b>。
     *
     * <p>通知正文点击回到 App 并尝试进入 Web；Web 未就绪时只切回启动页，不报错。
     * {@link com.deepseekharness.app.ui.MainActivity} 收到后切到启动页并尝试进入 Web
     * （Web 未就绪时只切页，不报错）。
     *
     * <p>用 {@code SINGLE_TOP + CLEAR_TOP} 复用已有任务，不新开一层；requestCode 与
     * 其它通知区分，避免 {@code FLAG_UPDATE_CURRENT} 让后建的通知覆盖前一个的 PendingIntent。
     */
    private PendingIntent agentNotificationIntent() {
        Intent intent = new Intent(ctx, com.deepseekharness.app.ui.MainActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP)
                .putExtra("open_web", true);
        return PendingIntent.getActivity(ctx, AGENT_NOTIF_REQUEST, intent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }

    /** /app/toast?text= ：弹 App 内 Toast */
    /** 屏幕操作（走无障碍服务）：读屏 / 点按 / 输入 / 按键 / 滑动。
     *
     *  这条通道不需要 ADB 也不需要 Shizuku —— 绝大多数用户两者都没有，
     *  而无障碍是一次授权长期可用，这才是 agent 能真正「操作手机」的现实路径。 */
    // ==================== 屏幕操作的授权闸门 ====================
    //
    // 为什么必须有这道闸：/app/ui/* 能读屏、点按、输入，破坏力其实**超过** shell 命令 ——
    // 它直接操作用户**已经登录**的应用，绕过所有应用层权限。agent 一旦被 prompt
    // injection 诱导（读到网页或文件里夹带的指令），就能在支付软件里点按、把私信
    // 截屏留到磁盘。而 /exec 一直有危险命令守卫，UI 操作在我加完那六个端点之后
    // 一道闸都没有 —— 这是自查时发现的最大缺口。
    //
    // 本次 DSH 运行有效；停止、无障碍断开或用户撤销时失效。敏感界面仍逐次确认。
    private static final com.deepseekharness.app.util.ScreenSessionGrant uiGrant = new com.deepseekharness.app.util.ScreenSessionGrant();
    public static void revokeScreenGrant() { uiGrant.revoke(); com.deepseekharness.app.vscreen.VirtualScreenManager.revoke(); }
    public static boolean hasScreenGrant(Context context) {
        var controller = com.deepseekharness.app.core.HarnessController.get(context);
        return !controller.isStopping() && !controller.isUserStopped() && uiGrant.allowed(controller.getWebGeneration());
    }

    /** 涉钱、涉密的应用：宁可多问一次。取不到包名也按敏感处理。 */
    private static boolean isSensitiveApp(String pkg) {
        if (pkg == null || pkg.isEmpty()) return true;
        String p = pkg.toLowerCase(java.util.Locale.ROOT);
        String[] keys = {
                "alipay", "tencent.mm", "unionpay", "jdpay", "wallet", "paypal",
                "bank", "icbc", "ccb", "abchina", "bankofchina", "cmbchina",
                "bankcomm", "psbc", "cebbank", "cmbc", "spdb", "citic", "hxb",
                "keepass", "bitwarden", "lastpass", "1password", "authenticator",
                "com.android.settings",   // 系统设置：能改权限、开无障碍、卸载应用
        };
        for (String k : keys) {
            if (p.contains(k)) return true;
        }
        return false;
    }

    /** @param action 给用户看的具体动作描述 —— 弹窗必须说清 AI 要干什么，
     *               而不是笼统一句「操作屏幕」，否则用户等于盲签。 */
    private boolean uiAuthorized(String action) {
        String pkg = DshaAccessibilityService.currentPackage();
        boolean sensitive = isSensitiveApp(pkg);
        var controller = com.deepseekharness.app.core.HarnessController.get(ctx);
        long generation = controller.getWebGeneration(), revision = uiGrant.revision();
        if (!sensitive && hasScreenGrant(ctx)) {
            return true;
        }
        String where = pkg.isEmpty() ? com.deepseekharness.app.util.UiText.text("当前界面") : pkg;
        String why = sensitive
                ? com.deepseekharness.app.util.UiText.text("在【") + where + com.deepseekharness.app.util.UiText.text("】里：") + action
                + com.deepseekharness.app.util.UiText.text("  # 这类应用涉及支付或隐私，每次都需要你确认")
                : action + com.deepseekharness.app.util.UiText.choose("  # 本次 DSH 运行期间有效，可在设备能力授权中随时撤销", "  # Valid for this DSH run. Revoke anytime in Device permissions");
        boolean ok = requestUserConfirm(why);
        if (ok && !sensitive) {
            return !controller.isStopping() && !controller.isUserStopped()
                    && controller.getWebGeneration() == generation && uiGrant.accept(generation, revision);
        }
        return ok;
    }

    private static String shortText(String s) {
        if (s == null) return "";
        String t = s.replace('\n', ' ').trim();
        return t.length() > 24 ? t.substring(0, 24) + "…" : t;
    }

    private String appUi(String path) {
        String q = queryOf(path);
        // 命名空间内的子端点也走精确匹配：startsWith 会让 /app/ui/shotXXX
        // 命中截屏，而截屏会把当前画面留到磁盘。
        String r = path.split("\\?", 2)[0];
        try {
            if (r.equals("/app/ui/dump")) {
                if (!uiAuthorized(com.deepseekharness.app.util.UiText.text("读取当前屏幕上的文字与控件"))) return com.deepseekharness.app.util.UiText.text("[ERR] 你拒绝了这次屏幕读取");
                return DshaAccessibilityService.uiDump();
            }
            if (r.equals("/app/ui/tap")) {
                String text = getParam(q, "text", "");
                // 有文字就按文字点：控件位置会随滚动和动画变，文字不会
                if (!text.isEmpty()) {
                    if (!uiAuthorized(com.deepseekharness.app.util.UiText.text("点击「") + shortText(text) + com.deepseekharness.app.util.UiText.text("」"))) return com.deepseekharness.app.util.UiText.text("[ERR] 你拒绝了这次点击");
                    return DshaAccessibilityService.uiTapText(text);
                }
                int x = intParam(q, "x", -1);
                int y = intParam(q, "y", -1);
                if (x < 0 || y < 0) return com.deepseekharness.app.util.UiText.text("[ERR] 需要 ?text=要点的文字 或 ?x=&y=坐标");
                if (!uiAuthorized(com.deepseekharness.app.util.UiText.text("点击坐标 (") + x + "," + y + ")")) return com.deepseekharness.app.util.UiText.text("[ERR] 你拒绝了这次点击");
                return DshaAccessibilityService.uiTap(x, y);
            }
            if (r.equals("/app/ui/input")) {
                String text = getParam(q, "text", null);
                if (text == null) return com.deepseekharness.app.util.UiText.text("[ERR] 需要 ?text=");
                if (!uiAuthorized(text.isEmpty() ? com.deepseekharness.app.util.UiText.choose("清空当前输入框", "Clear the current input field") : com.deepseekharness.app.util.UiText.text("在输入框里填入「") + shortText(text) + com.deepseekharness.app.util.UiText.text("」"))) {
                    return com.deepseekharness.app.util.UiText.text("[ERR] 你拒绝了这次输入");
                }
                return DshaAccessibilityService.uiInput(text);
            }
            if (r.equals("/app/ui/key")) {
                String k = getParam(q, "name", "");
                if (!uiAuthorized(com.deepseekharness.app.util.UiText.text("按下系统按键 ") + shortText(k))) return com.deepseekharness.app.util.UiText.text("[ERR] 你拒绝了这次按键");
                return DshaAccessibilityService.uiKey(k);
            }
            if (r.equals("/app/ui/screenshot") || r.equals("/app/ui/shot")) {
                // 截屏会把当前画面留到磁盘，等于一份可被后续读取的隐私快照
                if (!uiAuthorized(com.deepseekharness.app.util.UiText.text("截取当前屏幕并保存为图片"))) return com.deepseekharness.app.util.UiText.text("[ERR] 你拒绝了这次截屏");
                return DshaAccessibilityService.uiScreenshot();
            }
            if (r.equals("/app/ui/swipe")) {
                int x1 = intParam(q, "x1", -1);
                int y1 = intParam(q, "y1", -1);
                int x2 = intParam(q, "x2", -1);
                int y2 = intParam(q, "y2", -1);
                if (x1 < 0 || y1 < 0 || x2 < 0 || y2 < 0) {
                    return com.deepseekharness.app.util.UiText.text("[ERR] 需要 ?x1=&y1=&x2=&y2=（可选 &ms=时长）");
                }
                if (!uiAuthorized(com.deepseekharness.app.util.UiText.text("滑动屏幕 (") + x1 + "," + y1 + ")→(" + x2 + "," + y2 + ")")) {
                    return com.deepseekharness.app.util.UiText.text("[ERR] 你拒绝了这次滑动");
                }
                return DshaAccessibilityService.uiSwipe(x1, y1, x2, y2, intParam(q, "ms", 300));
            }
            return com.deepseekharness.app.util.UiText.text("[ERR] 未知端点（可用：dump/tap/input/key/swipe）");
        } catch (Throwable t) {
            return "[ERR] " + SensitiveData.redact(String.valueOf(t));
        }
    }

    /** 虚拟屏只接受已认证的固定端点；每个写入/启动动作仍复用当前屏幕授权确认。 */
    private String appVscreen(String path) {
        String route = path.split("\\?", 2)[0];
        String operation = com.deepseekharness.app.util.VirtualScreenRoutes.operation(route);
        if (operation.isEmpty()) return "{\"ok\":false,\"error\":\"UNKNOWN_ROUTE\"}";
        try {
            if (!operation.equals("status") && !operation.equals("close") && !uiAuthorized(com.deepseekharness.app.util.UiText.text("操作独立虚拟屏：") + route))
                return "{\"ok\":false,\"error\":\"USER_REJECTED\"}";
            return com.deepseekharness.app.vscreen.VirtualScreenManager.bridge(ctx, route, queryOf(path));
        } catch (Throwable error) {
            return "[ERR] " + SensitiveData.redact(String.valueOf(error));
        }
    }

    private int intParam(String q, String k, int def) {
        try {
            return Integer.parseInt(getParam(q, k, String.valueOf(def)).trim());
        } catch (Exception e) {
            return def;
        }
    }

    /**
     * {@code /app/overlay?session=&kind=delta|tool|text|done|clear&text=} ——
     * 把 agent 正在生成的内容送到屏幕顶部的流式悬浮条（{@link OverlayController}）。
     *
     * <p>返回值刻意分三种，让插件侧能自己降级：{@code DISABLED}（用户没开这个功能）、
     * {@code NO_PERMISSION}（没给悬浮窗权限）、{@code OK}。插件拿到前两种就该停止推送 ——
     * 流式增量是高频调用，白发一路 HTTP 纯属烧电。
     */
    /**
     * {@code /app/plugins}：让 dsh 进程内的插件把<b>真实加载状态</b>报给 App，
     * 也可以只读回上一次上报。
     *
     * <p><b>为什么要走桥，而不是 App 自己读文件</b>：App 只能读 profile 的 package.json
     * 猜「注册了没有」，而<b>注册了不等于加载成功</b> —— 入口文件缺失、inject 的服务不存在、
     * patch 里的 name 与目标行对不上，都会让插件静静地不生效，而 package.json 看起来一切正常。
     * 只有跑在 dsh 进程里的插件能通过 cordis 上下文看到真实状态。这正是「插件装了没反应」
     * 一直缺的那份证据 —— 缺了它，App 只能猜，用户只能重装。
     *
     * <p>约定：
     * <ul>
     *   <li>{@code ?loaded=a,b&failed=c}（逗号分隔）→ 上报，存起来给插件页与自检用；</li>
     *   <li>不带参数 → 只读，返回 {@code LOADED:… / FAILED:… / AT:<毫秒时间戳>}。</li>
     * </ul>
     */
    /**
     * {@code /app/help}：3090 桥的完整端点清单，纯文本、给 agent 读。
     *
     * <p><b>为什么要有这个端点</b>：这份清单原来整份写在 device-shell-guide 的注入提示词里
     * （12KB，约几千 token），而它是<b>每一轮对话都要付的成本</b> —— 哪怕这轮根本不碰设备。
     * 挪到运行时按需查之后，提示词只留骨架，agent 要用设备能力时 curl 一次就拿到全部细节。
     *
     * <p>还有个额外好处：这份清单跟端点实现<b>在同一个文件里</b>，加端点时顺手就更新了；
     * 写在插件的提示词里则要改 assets、bump 版本、重签清单，于是必然脱节
     * （AGENTS.md 里「文档说 14 个端点、实际 26 个」就是这么来的）。
     */
    /**
     * 桥协议版本 —— 插件侧靠它判断「这台 App 支持哪些端点」。
     *
     * <p><b>什么时候该涨</b>（写清楚，否则这个号形同虚设）：
     * <ul>
     *   <li><b>加新端点：不涨。</b>老插件不知道新端点，行为不变；新插件想用新端点，
     *       自己 try 一下拿 404 就知道了；</li>
     *   <li><b>改已有端点的参数含义、返回格式，或删端点：涨。</b>这类改动会让按老约定
     *       写的插件静默拿到错东西 —— 那正是版本号要挡的事。</li>
     * </ul>
     *
     * <p>所以插件的正确写法是 {@code if (protocol >= N)} 而不是 {@code == N}。
     */
    private static final int BRIDGE_PROTOCOL = 2;

    /**
     * {@code /app/version}：桥协议与 App 版本，给插件做特性检测。
     *
     * <p>没有这个端点时，插件只能靠「试着调一下看会不会 404」来猜 App 的能力，
     * 而 dsh 与 DSHA 是各自升级的 —— 用户完全可能拿新插件配旧 App。
     */
    private String appVersion() {
        return "BRIDGE_PROTOCOL=" + BRIDGE_PROTOCOL + "\n"
                + "APP_VERSION=" + BuildConfig.VERSION_NAME + "\n"
                + "APP_CODE=" + BuildConfig.VERSION_CODE + "\n"
                + com.deepseekharness.app.util.UiText.text("HINT=端点清单见 /app/help；判版本请用 >= 而不是 ==\n");
    }

    private String appHelp() {
        return com.deepseekharness.app.util.UiText.text("DSHA 3090 桥端点清单（BRIDGE_PROTOCOL=") + BRIDGE_PROTOCOL + "）\n"
            + com.deepseekharness.app.util.UiText.text("token 取自 /root/.dsh/.bridge_token，下面记为 $T。\n")
            + com.deepseekharness.app.util.UiText.text("带中文/空格的参数一律用 -G --data-urlencode，别手写 URL 编码。\n")
            + "\n"
            + com.deepseekharness.app.util.UiText.text("== 屏幕操作（无障碍服务，不需要 ADB/Shizuku）==\n")
            + com.deepseekharness.app.util.UiText.text("读屏  curl -s \"127.0.0.1:3090/app/ui/dump?token=$T\"\n")
            + com.deepseekharness.app.util.UiText.text("      → 每行「[序号] \"文字\" 可点击 中心=(x,y) 区域=l,t,r,b」\n")
            + com.deepseekharness.app.util.UiText.text("点按  curl -s -G 127.0.0.1:3090/app/ui/tap --data-urlencode \"text=设置\" --data-urlencode \"token=$T\"\n")
            + com.deepseekharness.app.util.UiText.text("      → 优先按文字点：控件位置随滚动/动画变，文字不变。没有文字才用 ?x=&y=\n")
            + com.deepseekharness.app.util.UiText.text("输入  curl -s -G 127.0.0.1:3090/app/ui/input --data-urlencode \"text=内容\" --data-urlencode \"token=$T\"\n")
            + com.deepseekharness.app.util.UiText.text("      → 填到当前焦点框；没有焦点先 tap 一下输入框\n")
            + com.deepseekharness.app.util.UiText.text("按键  /app/ui/key?name=back  （back/home/recents/notifications/quicksettings/lock）\n")
            + com.deepseekharness.app.util.UiText.text("滑动  /app/ui/swipe?x1=500&y1=1500&x2=500&y2=500&ms=300\n")
            + com.deepseekharness.app.util.UiText.choose("截屏  /app/ui/screenshot   → 存 PNG 到应用截图目录并返回路径（不回 base64）\n", "Screenshot  /app/ui/screenshot   → save a PNG in the app's screenshot folder and return its path (not base64)\n")
            + com.deepseekharness.app.util.UiText.text("节奏：每次点按/输入后先 dump 再决定下一步，别凭记忆连点。\n")
            + "\n"
            + com.deepseekharness.app.util.UiText.text("== 独立虚拟屏（Android 11+；每次输入必须带最新 frameSeq）==\n")
            + com.deepseekharness.app.util.UiText.text("/app/vscreen/create?orientation=portrait|landscape  创建虚拟屏\n")
            + com.deepseekharness.app.util.UiText.text("/app/vscreen/status  查询 displayId、尺寸和 frameSeq\n")
            + com.deepseekharness.app.util.UiText.text("/app/vscreen/launch?package=com.example.app  启动已安装应用\n")
            + com.deepseekharness.app.util.UiText.text("/app/vscreen/see  获取最新预览；tap/swipe/type/key 必须携带该 frameSeq\n")
            + com.deepseekharness.app.util.UiText.text("/app/vscreen/close  关闭并回收虚拟屏\n")
            + "\n"
            + com.deepseekharness.app.util.UiText.text("== 设备与应用 ==\n")
            + com.deepseekharness.app.util.UiText.text("/app/device                     机型/系统/电量/网络/屏幕/存储/内存\n")
            + com.deepseekharness.app.util.UiText.text("/app/apps                       全部已装应用，分用户应用与系统应用；可加 q/limit/user=1 筛选显示\n")
            + com.deepseekharness.app.util.UiText.text("/app/launch?pkg=com.tencent.mm  启动应用\n")
            + com.deepseekharness.app.util.UiText.text("/app/clip                       读剪贴板（需 App 在前台，系统限制）\n")
            + com.deepseekharness.app.util.UiText.text("/app/clip + text=…              写剪贴板\n")
            + com.deepseekharness.app.util.UiText.text("/app/readfile?path=/…          读取绝对路径的目录或文本，仍受 Android 权限限制；凭据区（.dsh/.ssh/.android）不可读\n")
            + com.deepseekharness.app.util.UiText.text("设备文件写入请走下方受保护的设备 shell；普通 Download 文件可操作，DCIM/Pictures/Android/data/obb 只读。\n")
            + "\n"
            + com.deepseekharness.app.util.UiText.text("== 与用户交互 ==\n")
            + com.deepseekharness.app.util.UiText.text("/app/ask?options=继续|取消 + q=…  弹窗阻塞等回答（最多三个选项）\n")
            + com.deepseekharness.app.util.UiText.text("/app/notify?title=… + text=…      通知栏\n")
            + com.deepseekharness.app.util.UiText.text("/app/toast + text=…               App 内提示\n")
            + com.deepseekharness.app.util.UiText.text("/app/vibrate?ms=300               震动（长任务跑完叫醒用户）\n")
            + com.deepseekharness.app.util.UiText.text("/app/share（text= 或 path=）      分享到其它应用\n")
            + com.deepseekharness.app.util.UiText.text("/app/open?url=https://…           打开链接\n")
            + com.deepseekharness.app.util.UiText.text("/app/export?path=/root/report.md  把产物交给用户 → 落 Download/DSHA；凭据区不可导出\n")
            + com.deepseekharness.app.util.UiText.text("建议：需要用户拍板用 /app/ask 而不是干等；长任务结束用 notify 或 vibrate 叫人；\n")
            + com.deepseekharness.app.util.UiText.text("产出报告用 /app/export，别只留在容器里。\n")
            + "\n"
            + com.deepseekharness.app.util.UiText.text("== 传感器与位置（默认关闭，需用户在配置页勾选）==\n")
            + com.deepseekharness.app.util.UiText.text("/app/location（加 fresh=1 强制重新定位，可能等数秒）\n")
            + com.deepseekharness.app.util.UiText.text("/app/sensors 列表 · /app/sensor?name=light 读值\n")
            + com.deepseekharness.app.util.UiText.text("（light 环境光 lux / accel / gyro / magnet / pressure / proximity /\n")
            + com.deepseekharness.app.util.UiText.text(" gravity / rotation 姿态四元数 / steps 开机后步数）\n")
            + com.deepseekharness.app.util.UiText.text("/app/torch?on=1 手电\n")
            + com.deepseekharness.app.util.UiText.text("这三类返回 DISABLED（用户没开该能力）或 NO_PERMISSION（没授系统权限）时，\n")
            + com.deepseekharness.app.util.UiText.text("照原话告诉用户去哪开，不要重试 —— 重试不会让开关自己变。\n")
            + "\n"
            + com.deepseekharness.app.util.UiText.text("== 元信息 ==\n")
            + com.deepseekharness.app.util.UiText.text("/app/version                          桥协议版本 + App 版本（特性检测用）\n")
            + com.deepseekharness.app.util.UiText.text("/app/help                             本清单\n")
            + "\n"
            + com.deepseekharness.app.util.UiText.text("== 插件状态 ==\n")
            + com.deepseekharness.app.util.UiText.text("/app/plugins                          读回上次上报的加载状态\n")
            + com.deepseekharness.app.util.UiText.text("/app/plugins?loaded=a,b&failed=c      上报（插件侧用）\n")
            + "\n"
            + com.deepseekharness.app.util.UiText.text("== 设备 shell（自动选择 root / Shizuku / ADB）==\n")
            + com.deepseekharness.app.util.UiText.text("/root/dsh-bin/adb-shell \"命令\"        用 id 核验实际身份\n")
            + com.deepseekharness.app.util.UiText.text("包装命令不存在时：python3 /root/.dsh/adb-shell.py \"命令\"\n")
            + com.deepseekharness.app.util.UiText.text("短信只允许当前 Android 用户的 content query --uri content://sms，默认关闭；设置 → 设备能力授权可开启或撤销。\n")
            + com.deepseekharness.app.util.UiText.text("短信预授权可能返回正文及验证码；不允许发送、修改或删除，Android 仍可拒绝访问。Shizuku 不执行此敏感查询。\n")
            + com.deepseekharness.app.util.UiText.text("仅执行已识别的单条命令；允许读取各目录和明确路径的普通文件操作。禁止脚本、管道、重定向、未知命令。\n")
            + com.deepseekharness.app.util.UiText.text("根目录及系统目录只读；禁止块设备/分区、SELinux、系统设置写入、挂载和刷机操作。\n")
            + com.deepseekharness.app.util.UiText.text("结束进程前自动刷新全量用户/系统应用清单；普通用户应用直接结束，系统应用与关键进程拦截。\n")
            + com.deepseekharness.app.util.UiText.text("按完整包名调用 am force-stop / killall / pkill -x；kill 正数 PID 会核对 UID 后按包名停止。\n")
            + com.deepseekharness.app.util.UiText.text("策略拦截返回 [POLICY_BLOCKED]/126；root 和旧确认开关不能放行。不可用其它解释器或 UI 绕过。\n")
            + com.deepseekharness.app.util.UiText.text("报连不上/未配对：先看上面的 App 层接口能不能办成；确实必须 shell 才请用户到\n")
            + com.deepseekharness.app.util.UiText.text("设置 → 设备能力授权中连接可用通道，别反复试同一条命令。\n")
            + com.deepseekharness.app.util.UiText.text("不要用 /root/dsh-bin/adb 或裸 adb —— 那是守卫包装脚本，会失败。\n")
            + "\n"
            + "== root（--su）==\n"
            + com.deepseekharness.app.util.UiText.text("已启用并经 root 管理器授权的 su 可直接执行，无需 ADB 配对；其次使用 Shizuku，再使用 ADB。\n")
            + com.deepseekharness.app.util.UiText.text("--su 仅在明确需要 root 时使用，须先在设备能力授权页允许；同一设备保护策略始终生效。\n")
            + com.deepseekharness.app.util.UiText.text("[EXECUTION_UNKNOWN] 表示命令可能已执行，先核对实际状态，不能切换通道或自动重放。\n");
    }

    private String appPlugins(String path) {
        try {
            String q = queryOf(path);
            String loaded = getParam(q, "loaded", null);
            String failed = getParam(q, "failed", null);
            String pending = getParam(q, "pending", null);
            String safeLoaded = loaded == null ? null : safeDisplay(loaded.trim());
            String safeFailed = failed == null ? null : safeDisplay(failed.trim());
            android.content.SharedPreferences sp =
                    ctx.getSharedPreferences("deepseekharness", Context.MODE_PRIVATE);
            if (loaded == null && failed == null && pending == null) {
                return "LOADED:" + sp.getString("plugin_loaded", "")
                        + "\nFAILED:" + sp.getString("plugin_failed", "")
                        + "\nPENDING:" + sp.getString("plugin_pending", "")
                        + "\nAT:" + sp.getLong("plugin_report_ts", 0L);
            }
            String previousFailure = sp.getString("plugin_failed", "");
            sp.edit()
                    .putString("plugin_loaded", safeLoaded == null ? "" : safeLoaded)
                    .putString("plugin_failed", safeFailed == null ? "" : safeFailed)
                    .putString("plugin_pending", pending == null ? "" : safeDisplay(pending.trim()))
                    .putLong("plugin_report_ts", System.currentTimeMillis())
                    .apply();
            // 有加载失败的就写进活动日志 —— 那是用户唯一能看到「插件为什么没反应」的地方
            if (safeFailed != null && !safeFailed.isEmpty() && !safeFailed.equals(previousFailure)) {
                try {
                    HarnessController.get(ctx).logActivity(com.deepseekharness.app.util.UiText.text("插件加载失败：") + safeFailed);
                } catch (Throwable ignored) {
                }
            }
            return "OK";
        } catch (Throwable e) {
            return "ERROR: " + safeError(e);
        }
    }

    private String appOverlay(String path) {
        try {
            String q = queryOf(path);
            String kind = getParam(q, "kind", "delta");
            String text = getParam(q, "text", "");
            String session = getParam(q, "session", "");
            String displayText = safeDisplay(text);
            if (!OverlayController.enabled(ctx)) return "DISABLED";
            if (!OverlayController.permitted(ctx)) return "NO_PERMISSION";
            // 让插件知道用户想不想看这两类内容，省得白发一路 HTTP
            if ("reasoning".equals(kind) && !OverlayController.showReasoning(ctx)) {
                return "SKIP_REASONING";
            }
            OverlayController.push(ctx, session, kind, displayText);
            return OverlayController.showCommand(ctx) ? "OK" : "OK_NO_CMD";
        } catch (Throwable e) {
            return "ERROR: " + safeError(e);
        }
    }

    private String appToast(String path) {
        try {
            final String text = getParam(queryOf(path), "text", "");
            if (text.isEmpty()) return "NO_TEXT";
            final String displayText = safeDisplay(text);
            new Handler(Looper.getMainLooper()).post(() -> {
                try {
                    android.widget.Toast.makeText(ctx, displayText, android.widget.Toast.LENGTH_LONG).show();
                } catch (Throwable ignored) {
                }
            });
            return "OK";
        } catch (Throwable e) {
            return "ERROR: " + safeError(e);
        }
    }

    /** /app/readfile?path= ：按绝对路径读取目录或文本（256 KiB），权限由 Android 决定。 */
    private String appReadFile(String path) {
        try {
            String p = getParam(queryOf(path), "path", "");
            if (p.isEmpty()) return "NO_PATH";
            java.io.File f = new java.io.File(p);
            if (!f.isAbsolute()) return com.deepseekharness.app.util.UiText.text("FORBIDDEN: 读取需要绝对路径");
            // 凭据/运行时内部状态不开放给桥读取：读到的内容会回到会话里，
            // 再经 /app/export 就能落到公共目录（真机实测过这条链路）。
            if (com.deepseekharness.app.util.BridgePathPolicy.denied(p))
                return "FORBIDDEN: " + com.deepseekharness.app.util.BridgePathPolicy.reason();
            String canon;
            try {
                canon = f.getCanonicalPath();
            } catch (Exception e) {
                return com.deepseekharness.app.util.UiText.text("FORBIDDEN: 路径无法解析（") + p + com.deepseekharness.app.util.UiText.text("）");
            }
            // 复核 canonical：软链接不能成为读凭据的跳板。
            if (exportDeniedByCanonical(f))
                return "FORBIDDEN: " + com.deepseekharness.app.util.BridgePathPolicy.reason();
            // canon 之后用于目录遍历的越界复核，避免同一路径被解析两遍产生竞态。
            if (canon == null || canon.isEmpty())
                return com.deepseekharness.app.util.UiText.text("FORBIDDEN: 路径无法解析（") + p + com.deepseekharness.app.util.UiText.text("）");
            // 按用户策略放开可读目录；实际权限仍由 Android 执行，不把拒绝伪装成成功。
            if (f.isDirectory()) {
                java.io.File[] children = f.listFiles();
                if (children == null) return com.deepseekharness.app.util.UiText.text("NO_PERMISSION: Android 未授予读取此目录的权限");
                StringBuilder listing = new StringBuilder();
                for (java.io.File child : children) {
                    if (listing.length() > 250000) { listing.append("[OUTPUT_TRUNCATED]\n"); break; }
                    // 列出上层目录时也不能暴露凭据区条目（名字本身就是情报）。
                    if (com.deepseekharness.app.util.BridgePathPolicy.denied(child.getPath())
                            || exportDeniedByCanonical(child)) continue;
                    listing.append(child.isDirectory() ? "d\t" : "f\t").append(child.getName()).append('\n');
                }
                return listing.toString();
            }
            if (!f.isFile()) return com.deepseekharness.app.util.UiText.text("NOT_FOUND_OR_NO_PERMISSION: 路径不存在或 Android 未授予读取权限：") + p;
            if (f.length() > 256 * 1024) return "TOO_LARGE: " + f.length();
            java.io.ByteArrayOutputStream bytes = new java.io.ByteArrayOutputStream();
            try (java.io.FileInputStream in = new java.io.FileInputStream(f)) {
                byte[] buffer = new byte[8192]; int count;
                while ((count = in.read(buffer)) != -1) {
                    if (bytes.size() + count > 256 * 1024) return com.deepseekharness.app.util.UiText.text("TOO_LARGE: 内容超过 256 KiB");
                    bytes.write(buffer, 0, count);
                }
            }
            return bytes.toString("UTF-8");
        } catch (Throwable e) {
            return "ERROR: " + safeError(e);
        }
    }

    // ================= App 层能力（不需要 ADB / Shizuku，agent 直接调） =================

    /** /app/device ：设备状态一览（机型/系统/电量/网络/屏幕/存储/内存） */
    private String appDevice() {
        StringBuilder sb = new StringBuilder();
        try {
            sb.append("model=").append(Build.MANUFACTURER).append(' ').append(Build.MODEL).append('\n');
            sb.append("android=").append(Build.VERSION.RELEASE)
                    .append(" (SDK ").append(Build.VERSION.SDK_INT).append(")\n");
            try {
                android.os.BatteryManager bm =
                        (android.os.BatteryManager) ctx.getSystemService(Context.BATTERY_SERVICE);
                android.content.Intent st = ctx.registerReceiver(null,
                        new android.content.IntentFilter(android.content.Intent.ACTION_BATTERY_CHANGED));
                int status = st == null ? -1 : st.getIntExtra(android.os.BatteryManager.EXTRA_STATUS, -1);
                boolean charging = status == android.os.BatteryManager.BATTERY_STATUS_CHARGING
                        || status == android.os.BatteryManager.BATTERY_STATUS_FULL;
                int level = bm == null ? -1
                        : bm.getIntProperty(android.os.BatteryManager.BATTERY_PROPERTY_CAPACITY);
                sb.append("battery=").append(level).append("% charging=").append(charging).append('\n');
            } catch (Throwable ignored) {
            }
            try {
                android.net.ConnectivityManager cm = (android.net.ConnectivityManager)
                        ctx.getSystemService(Context.CONNECTIVITY_SERVICE);
                String net = "none";
                if (cm != null) {
                    android.net.Network n = cm.getActiveNetwork();
                    android.net.NetworkCapabilities nc = n == null ? null : cm.getNetworkCapabilities(n);
                    if (nc != null) {
                        if (nc.hasTransport(android.net.NetworkCapabilities.TRANSPORT_WIFI)) net = "wifi";
                        else if (nc.hasTransport(android.net.NetworkCapabilities.TRANSPORT_CELLULAR)) net = "cellular";
                        else if (nc.hasTransport(android.net.NetworkCapabilities.TRANSPORT_ETHERNET)) net = "ethernet";
                        else net = "other";
                    }
                }
                sb.append("network=").append(net).append('\n');
            } catch (Throwable ignored) {
            }
            try {
                android.os.PowerManager pm = (android.os.PowerManager) ctx.getSystemService(Context.POWER_SERVICE);
                sb.append("screen=").append(pm != null && pm.isInteractive() ? "on" : "off").append('\n');
            } catch (Throwable ignored) {
            }
            sb.append("app_foreground=").append(ForegroundActivity.current() != null).append('\n');
            try {
                android.os.StatFs fs = new android.os.StatFs(
                        android.os.Environment.getExternalStorageDirectory().getPath());
                long free = fs.getAvailableBytes(), total = fs.getTotalBytes();
                sb.append("storage_free=").append(HarnessController.fmtBytes(free))
                        .append(" total=").append(HarnessController.fmtBytes(total)).append('\n');
            } catch (Throwable ignored) {
            }
            try {
                android.app.ActivityManager am =
                        (android.app.ActivityManager) ctx.getSystemService(Context.ACTIVITY_SERVICE);
                android.app.ActivityManager.MemoryInfo mi = new android.app.ActivityManager.MemoryInfo();
                if (am != null) {
                    am.getMemoryInfo(mi);
                    sb.append("memory_free=").append(HarnessController.fmtBytes(mi.availMem))
                            .append(" total=").append(HarnessController.fmtBytes(mi.totalMem)).append('\n');
                }
            } catch (Throwable ignored) {
            }
        } catch (Throwable e) {
            return "ERROR: " + safeError(e);
        }
        return sb.toString().trim();
    }

    /** 默认完整分组；显式筛选不影响停止操作所用的完整清单。 */
    private String appList(String path) {
        try {
            String q = getParam(queryOf(path), "q", "").toLowerCase();
            int limit = Integer.MAX_VALUE;
            try {
                String count = getParam(queryOf(path), "limit", "");
                if (!count.isEmpty()) limit = Math.max(1, Integer.parseInt(count));
            } catch (Exception ignored) {
            }
            boolean userOnly = "1".equals(getParam(queryOf(path), "user", ""));
            DeviceAppInventory inventory = new DeviceAppInventory(ctx);
            StringBuilder sb = new StringBuilder();
            int n = 0;
            for (boolean system : new boolean[]{false, true}) {
                if (system && userOnly) continue;
                sb.append(system ? com.deepseekharness.app.util.UiText.text("[系统应用]\n") : com.deepseekharness.app.util.UiText.text("[用户应用]\n"));
                for (int index = 0; index < inventory.entries.length(); index++) {
                    org.json.JSONObject app = inventory.entries.getJSONObject(index);
                    if (app.getBoolean("system") != system) continue;
                    String name = app.getString("name"), label = app.getString("label");
                    if (!q.isEmpty() && !name.toLowerCase().contains(q) && !label.toLowerCase().contains(q)) continue;
                    if (n >= limit) break;
                    sb.append(name).append('\t').append(label).append(" uid=").append(app.getInt("uid")).append('\n'); n++;
                }
            }
            return sb.append(com.deepseekharness.app.util.UiText.text("显示 ")).append(n).append(com.deepseekharness.app.util.UiText.text(" 个；Android 返回 ")).append(inventory.entries.length())
                    .append(com.deepseekharness.app.util.UiText.text(" 个可见应用。系统可能限制可见范围；结束应用前会另行核对完整清单。")).toString();
        } catch (Throwable e) {
            return com.deepseekharness.app.util.UiText.text("[APP_LIST_UNAVAILABLE] Android 未提供完整应用清单；设备停止操作会通过 ADB/Shizuku 重新读取。")
                    + com.deepseekharness.app.util.UiText.text("可用设备命令分别查询 pm list packages -U -3 和 pm list packages -U -s。原因：") + safeError(e);
        }
    }

    /** /app/launch?pkg=包名 ：启动应用（App 层，不需要 ADB） */
    private String appLaunch(String path) {
        try {
            String pkg = getParam(queryOf(path), "pkg", "");
            if (pkg.isEmpty()) return "NO_PKG";
            android.content.Intent i = ctx.getPackageManager().getLaunchIntentForPackage(pkg);
            if (i == null) return "NOT_FOUND: " + pkg + com.deepseekharness.app.util.UiText.text("（该应用没有启动入口或未安装）");
            i.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK);
            ctx.startActivity(i);
            return com.deepseekharness.app.util.UiText.text("OK: 已启动 ") + pkg;
        } catch (Throwable e) {
            return "ERROR: " + safeError(e);
        }
    }

    /** /app/clip 读剪贴板；/app/clip?text=xxx 写剪贴板 */
    private String appClip(String path) {
        final String text = getParam(queryOf(path), "text", "");
        try {
            // HTTP 工作线程有界等待。不能在排队后先报成功，也不能因等待超时就假定尚未写入。
            if (Looper.myLooper() == Looper.getMainLooper()) return com.deepseekharness.app.util.UiText.text("ERROR: 剪贴板桥需由请求工作线程调用");
            BoundedUiCall.Result<String> result = BoundedUiCall.call(new BoundedUiCall.Dispatcher() {
                @Override public boolean post(Runnable task) { return mainHandler.post(task); }
                @Override public void remove(Runnable task) { mainHandler.removeCallbacks(task); }
            }, () -> {
                android.content.ClipboardManager cm = (android.content.ClipboardManager) ctx.getSystemService(Context.CLIPBOARD_SERVICE);
                if (cm == null) return "NO_SERVICE";
                if (!text.isEmpty()) {
                    cm.setPrimaryClip(android.content.ClipData.newPlainText("DSHA", text));
                    return com.deepseekharness.app.util.UiText.text("OK: 已写入剪贴板（") + text.length() + com.deepseekharness.app.util.UiText.text(" 字）");
                }
                // 读取时复核真正前台窗口，WebView/Gecko 同样属于前台应用。
                if (!ForegroundActivity.isResumed(ForegroundActivity.current()))
                    return com.deepseekharness.app.util.UiText.text("[APP_BACKGROUND] 系统限制：只有 App 在前台时才能读剪贴板，可先用 /app/notify 提醒用户打开 DSHA");
                android.content.ClipData cd = cm.getPrimaryClip();
                if (cd == null || cd.getItemCount() == 0) return com.deepseekharness.app.util.UiText.text("（剪贴板为空）");
                CharSequence cs = cd.getItemAt(0).coerceToText(ctx);
                String value = cs == null ? "" : cs.toString();
                return value.length() > 8192 ? value.substring(0, 8192) + com.deepseekharness.app.util.UiText.text("…（已截断）") : value;
            }, 3000);
            switch (result.status) {
                case SUCCESS: return result.value;
                case FAILED: return com.deepseekharness.app.util.UiText.text("ERROR: 剪贴板操作失败：") + safeError(result.error);
                case NOT_EXECUTED: return (result.interrupted ? "[INTERRUPTED] " : "[TIMEOUT] ")
                        + com.deepseekharness.app.util.UiText.text("主线程尚未执行，已取消本次剪贴板操作；可重试");
                default: return com.deepseekharness.app.util.UiText.text("[RESULT_UNKNOWN] 剪贴板操作已开始，但等待已结束，结果未知；请确认后再重试");
            }
        } catch (Throwable e) {
            return "ERROR: " + safeError(e);
        }
    }

    /** /app/share?text=... 或 /app/share?path=/sdcard/x.txt ：调起系统分享面板 */
    private String appShare(String path) {
        try {
            String q = queryOf(path);
            String text = getParam(q, "text", "");
            String file = getParam(q, "path", "");
            android.content.Intent send = new android.content.Intent(android.content.Intent.ACTION_SEND);
            if (!file.isEmpty()) {
                java.io.File f = new java.io.File(file);
                if (!f.isFile()) return "NOT_FOUND: " + file;
                // 只允许分享外部存储里的文件（App 私有目录需要 FileProvider 授权）
                String canon = f.getCanonicalPath();
                if (!canon.startsWith("/sdcard") && !canon.startsWith("/storage/emulated/0")) {
                    return com.deepseekharness.app.util.UiText.text("FORBIDDEN: 只能分享 /sdcard 下的文件");
                }
                send.setType("*/*");
                send.putExtra(android.content.Intent.EXTRA_STREAM, android.net.Uri.fromFile(f));
                send.addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION);
                if (!text.isEmpty()) send.putExtra(android.content.Intent.EXTRA_TEXT, text);
            } else {
                if (text.isEmpty()) return "NO_CONTENT";
                send.setType("text/plain");
                send.putExtra(android.content.Intent.EXTRA_TEXT, text);
            }
            android.content.Intent chooser = android.content.Intent.createChooser(send, com.deepseekharness.app.util.UiText.text("分享"));
            chooser.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK);
            ctx.startActivity(chooser);
            return com.deepseekharness.app.util.UiText.text("OK: 已弹出分享面板");
        } catch (Throwable e) {
            return "ERROR: " + safeError(e);
        }
    }

    /** /app/open?url=... ：用系统默认应用打开链接（http/https/geo/tel…） */
    private String appOpen(String path) {
        try {
            String url = getParam(queryOf(path), "url", "");
            if (url.isEmpty()) return "NO_URL";
            String low = url.toLowerCase();
            // 只放行常见安全 scheme：file:// 会把 App 私有文件暴露给任意应用
            if (!low.startsWith("http://") && !low.startsWith("https://")
                    && !low.startsWith("geo:") && !low.startsWith("tel:")
                    && !low.startsWith("mailto:") && !low.startsWith("market://")) {
                return com.deepseekharness.app.util.UiText.text("FORBIDDEN: 只支持 http/https/geo/tel/mailto/market 链接");
            }
            android.content.Intent i = new android.content.Intent(android.content.Intent.ACTION_VIEW,
                    android.net.Uri.parse(url));
            i.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK);
            ctx.startActivity(i);
            return com.deepseekharness.app.util.UiText.text("OK: 已打开 ") + safeDisplay(url);
        } catch (Throwable e) {
            return "ERROR: " + safeError(e);
        }
    }

    /** /app/vibrate?ms=300 ：震动提醒（长任务跑完叫醒用户） */
    private String appVibrate(String path) {
        try {
            long ms = 300;
            try {
                ms = Math.max(30, Math.min(2000, Long.parseLong(getParam(queryOf(path), "ms", "300"))));
            } catch (Exception ignored) {
            }
            android.os.Vibrator v;
            if (Build.VERSION.SDK_INT >= 31) {
                android.os.VibratorManager vm =
                        (android.os.VibratorManager) ctx.getSystemService(Context.VIBRATOR_MANAGER_SERVICE);
                v = vm == null ? null : vm.getDefaultVibrator();
            } else {
                v = (android.os.Vibrator) ctx.getSystemService(Context.VIBRATOR_SERVICE);
            }
            if (v == null) return "NO_VIBRATOR";
            if (Build.VERSION.SDK_INT >= 26) {
                v.vibrate(android.os.VibrationEffect.createOneShot(ms,
                        android.os.VibrationEffect.DEFAULT_AMPLITUDE));
            } else {
                // Android 6：VibrationEffect 是 API 26，退回旧式 vibrate(ms)
                v.vibrate(ms);
            }
            return com.deepseekharness.app.util.UiText.text("OK: 震动 ") + ms + "ms";
        } catch (Throwable e) {
            return "ERROR: " + safeError(e);
        }
    }

    /** /app/ask?q=问题&options=选项A|选项B|选项C ：弹窗问用户，阻塞等回答（最多 3 个选项，120 秒超时） */
    private String appAsk(String path) {
        return appAsk(path, 120_000, fixtureAskObserver);
    }

    /** 测试可缩短期限，但必须复用生产的前台宿主选择，不能注入一个永远前台的 Activity。 */
    String appAsk(String path, long timeoutMillis, java.util.function.Consumer<BridgeAskDialog> created) {
        return appAsk(path, timeoutMillis, ForegroundActivity.current(), created);
    }

    /** 包内注入窗口宿主与较短期限；不向 HTTP 参数开放这些测试控制。 */
    String appAsk(String path, long timeoutMillis, androidx.fragment.app.FragmentActivity act,
                  java.util.function.Consumer<BridgeAskDialog> created) {
        if (timeoutMillis <= 0 || timeoutMillis > 120_000) throw new IllegalArgumentException(com.deepseekharness.app.util.UiText.text("无效的提问期限"));
        String q = getParam(queryOf(path), "q", "");
        String optRaw = getParam(queryOf(path), "options", "");
        if (q.isEmpty()) return "NO_QUESTION";
        if (act == null) {
            return com.deepseekharness.app.util.UiText.text("[APP_BACKGROUND] App 不在前台，弹不出提问 —— 可先 /app/notify 提醒用户打开 DSHA");
        }
        String[] parts = optRaw.isEmpty() ? new String[] { com.deepseekharness.app.util.UiText.text("好") } : optRaw.split("\\|", -1);
        final String[] opts = parts.length <= 3 ? parts : new String[] { parts[0], parts[1], parts[2] };
        final String displayQuestion = safeDisplay(q);
        final String[] displayOptions = new String[opts.length];
        for (int i = 0; i < opts.length; i++) displayOptions[i] = safeDisplay(opts[i]);
        final BridgeQuestions.Request request;
        synchronized (LIFECYCLE) {
            if (!running || instance != this) return com.deepseekharness.app.util.UiText.text("[STOPPED] 设备桥已停止，请稍后重试");
            request = questions.begin(timeoutMillis);
        }
        if (request == null) return com.deepseekharness.app.util.UiText.text("[BUSY] 已有一个提问在等用户回答");
        BridgeAskDialog dialog = new BridgeAskDialog(act, questions, request);
        try {
            if (created != null) created.accept(dialog);
            dialog.show(displayQuestion, opts, displayOptions);
            switch (questions.await(request)) {
                case ANSWER: return request.answer();
                case TIMEOUT: return com.deepseekharness.app.util.UiText.text("[TIMEOUT] 用户在提问期限内没有回答");
                case DISMISSED: return com.deepseekharness.app.util.UiText.text("[DISMISSED] 用户关掉了提问框");
                case BACKGROUND: return com.deepseekharness.app.util.UiText.text("[APP_BACKGROUND] 页面已离开或重建，请回到 DSHA 后重新提问");
                case STOPPED: return com.deepseekharness.app.util.UiText.text("[STOPPED] 设备桥已停止，请稍后重试");
                default: return com.deepseekharness.app.util.UiText.text("[UNAVAILABLE] 提问窗口已关闭或无法显示，请重新提问");
            }
        } catch (InterruptedException e) {
            questions.cancel(request, BridgeQuestions.End.INTERRUPTED);
            Thread.currentThread().interrupt();
            return "[INTERRUPTED]";
        } finally {
            dialog.close();
            questions.release(request);
        }
    }

    /** /app/export?path=/root/x.md&name=x.md ：把文件导出到 Download/DSHA（走 MediaStore，用户可直接在文件管理器看到） */
    private String appExport(String path) {
        try {
            String q = queryOf(path);
            String src = getParam(q, "path", "");
            if (src.isEmpty()) return "NO_PATH";
            // 凭据/运行时内部状态不可导出到公共目录。真机实测过攻击链：
            // 导出 .bridge_token 后，同机任意应用即可完全接管本桥。
            if (com.deepseekharness.app.util.BridgePathPolicy.denied(src))
                return "FORBIDDEN: " + com.deepseekharness.app.util.BridgePathPolicy.reason();
            String name = getParam(q, "name", "");
            java.io.File f = new java.io.File(src);
            if (!f.isFile()) {
                // 允许传 rootfs 内的 guest 路径（/root/... → 映射到 App 私有目录）
                try {
                    HarnessController hc = HarnessController.get(ctx);
                    java.io.File guess = new java.io.File(hc.getProot().getRootfsDir(),
                            src.startsWith("/") ? src.substring(1) : src);
                    if (guess.isFile()) f = guess;
                } catch (Throwable ignored) {
                }
            }
            if (!f.isFile()) return "NOT_FOUND: " + SensitiveData.redact(src);
            // 字符串判据之后再核 canonical：挡住「先建软链接指向凭据」的绕法。
            if (exportDeniedByCanonical(f)) return "FORBIDDEN: " + com.deepseekharness.app.util.BridgePathPolicy.reason();
            if (f.length() > 64L * 1024 * 1024) return "TOO_LARGE: " + f.length();
            if (name.isEmpty()) name = f.getName();
            if (name.contains("/") || name.contains("..")) return "BAD_NAME";
            String out = BackupManager.exportToDownloads(ctx, f, name);
            return out == null ? com.deepseekharness.app.util.UiText.text("ERROR: 导出失败（存储权限或空间不足）")
                    : "OK: " + SensitiveData.redact(out);
        } catch (Throwable e) {
            return "ERROR: " + safeError(e);
        }
    }

    /**
     * 用 canonical 路径复核访问目标，拦住「先建软链接指向凭据」的绕过。
     *
     * <p>只看调用方给的字符串不够：容器里可以先建
     * {@code ln -s /root/.dsh/.bridge_token /root/innocent.md}，
     * 让字符串判据通过。{@code getCanonicalPath()} 会把软链接解析到真实目标，
     * 据此就能认出它是凭据。
     *
     * <p>注意不能直接用 {@code denied(canonical)}：容器 rootfs 本身就在
     * {@code /data/data/com.dsh.client/files/linux/ubuntu} 下，通用拒绝表里的
     * {@code /data/data} 会把整个 rootfs 封死（连正常产物都导不出去）。
     * 所以这里交给 {@code deniedGuestView}，由它区分「rootfs 内」与「App 私有数据」。
     */
    private boolean exportDeniedByCanonical(java.io.File file) {
        try {
            String canonical = file.getCanonicalPath();
            String rootfs = null;
            try {
                rootfs = HarnessController.get(ctx).getProot().getRootfsDir().getCanonicalPath();
            } catch (Throwable ignored) {
            }
            return com.deepseekharness.app.util.BridgePathPolicy.deniedGuestView(canonical, rootfs);
        } catch (Throwable unreadable) {
            // 取不到 canonical（异常路径）按拒绝处理，不放过。
            return true;
        }
    }

/** 从（仅含 query 的）查询串提取参数。调用方务必先截取 '?' 之后的内容。 */
    private static String getParam(String q, String key, String def) {
        return Query.param(q, key, def);
    }

    // 便捷包装：路径中取 query 部分
    private static String queryOf(String path) {
        return Query.of(path);
    }

    private boolean confirmEnabled() {
        return ctx.getSharedPreferences("deepseekharness", Context.MODE_PRIVATE)
                .getBoolean("confirm_shell", true);
    }

    /** 危险命令：挂起等待用户确认（前台弹窗 / 后台通知），超时默认拒绝 */
    private String awaitConfirm(String cmd) {
        return requestUserConfirm(cmd) ? ShizukuShell.exec(cmd) : "[USER_REJECTED]";
    }

    /** 只请求用户确认（不执行命令），返回是否允许；/confirm 端点用。
     *  通知与弹窗同时发：只走弹窗的话，Activity 一被 pause 用户就再也看不见，
     *  只能干等 60s 超时——这正是「弹窗有时不出现」的由来。（吸收上游 PR#24） */
    private boolean requestUserConfirm(String cmd) {
        final PendingConfirmation pending;
        synchronized (LIFECYCLE) {
            BridgeRun run = activeRun;
            if (!running || run == null || !LIFECYCLE.isCurrent(run.generation)
                    || requestRun.get() != null && requestRun.get() != run) return false;
            var request = confirmations.begin(run.generation);
            if (request == null) return false;
            pending = new PendingConfirmation(request);
            pendingConfirm = pending;
        }
        try {
            synchronized (LIFECYCLE) {
                if (!currentConfirmation(pending)) return false;
                showConfirmNotification(cmd, pending);
                OverlayController.askConfirm(ctx, pending, safeDisplay(cmd),
                        () -> resolveConfirm(true, pending.request.identity),
                        () -> resolveConfirm(false, pending.request.identity));
            }
            final androidx.fragment.app.FragmentActivity act = ForegroundActivity.current();
            if (act != null) {
                final String prompt = com.deepseekharness.app.util.UiText.text("模型试图在设备上执行：\n") + safeDisplay(cmd)
                        + com.deepseekharness.app.util.UiText.text("\n\n是否允许？");
                act.runOnUiThread(() -> {
                    synchronized (LIFECYCLE) {
                        try {
                            if (!ForegroundActivity.isResumed(act) || !currentConfirmation(pending)) return;
                            pending.dialog = new com.deepseekharness.app.ui.DshaDialogBuilder(act)
                                    .setTitle(com.deepseekharness.app.util.UiText.text("DSHA 安全确认"))
                                    .setMessage(prompt).setCancelable(false)
                                    .setPositiveButton(com.deepseekharness.app.util.UiText.text("允许"),
                                            (d, w) -> resolveConfirm(true, pending.request.identity))
                                    .setNegativeButton(com.deepseekharness.app.util.UiText.text("拒绝"),
                                            (d, w) -> resolveConfirm(false, pending.request.identity)).show();
                        } catch (Throwable t) {
                            android.util.Log.w("DSHA", com.deepseekharness.app.util.UiText.text("确认弹窗弹出失败，仍可从通知确认：") + safeError(t));
                        }
                    }
                });
            } else if (!notificationsEnabled()) {
                android.util.Log.w("DSHA", com.deepseekharness.app.util.UiText.text("无前台界面且通知权限被拒，确认必然超时拒绝：") + safeDisplay(cmd));
            }
            boolean allowed = pending.request.await(CONFIRM_TIMEOUT_S, TimeUnit.SECONDS);
            synchronized (LIFECYCLE) {
                BridgeRun run = activeRun;
                return allowed && running && run != null && run.generation == pending.request.generation
                        && LIFECYCLE.isCurrent(run.generation);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        } finally {
            synchronized (LIFECYCLE) { finishConfirmation(pending); }
        }
    }

    private boolean currentConfirmation(PendingConfirmation pending) {
        BridgeRun run = activeRun;
        return running && run != null && pendingConfirm == pending && run.generation == pending.request.generation
                && LIFECYCLE.isCurrent(run.generation) && confirmations.pending(pending.request);
    }

    private boolean notificationsEnabled() {
        try {
            NotificationManager nm = (NotificationManager) ctx.getSystemService(Context.NOTIFICATION_SERVICE);
            // framework API 24+，比运行时权限检查更准（用户在设置里关掉通知也算）
            return nm == null || androidx.core.app.NotificationManagerCompat.from(ctx).areNotificationsEnabled();
        } catch (Throwable e) {
            return true; // 判断不了就别妄下结论
        }
    }

    /** 通知、弹窗和悬浮条必须携带本次不可复用的身份。 */
    public void resolveConfirm(boolean allow, String identity) {
        synchronized (LIFECYCLE) {
            PendingConfirmation pending = pendingConfirm;
            if (pending == null || !currentConfirmation(pending)) return;
            if (!confirmations.resolve(pending.request.generation, identity, allow)) return;
            clearConfirmationUi(pending);
        }
    }

    /** 在释放待决槽位前取消本次 PendingIntent；不触碰后来请求的 UI。 */
    private void finishConfirmation(PendingConfirmation pending) {
        if (pendingConfirm != pending) return;
        clearConfirmationUi(pending);
        confirmations.finish(pending.request);
        pendingConfirm = null;
    }

    private void clearConfirmationUi(PendingConfirmation pending) {
        if (pendingConfirm != pending) return;
        if (pending.allow != null) { try { pending.allow.cancel(); } catch (RuntimeException ignored) { } pending.allow = null; }
        if (pending.deny != null) { try { pending.deny.cancel(); } catch (RuntimeException ignored) { } pending.deny = null; }
        try {
            NotificationManager nm = (NotificationManager) ctx.getSystemService(Context.NOTIFICATION_SERVICE);
            if (nm != null) nm.cancel(CONFIRM_NOTIF_ID);
        } catch (RuntimeException ignored) { }
        final androidx.appcompat.app.AlertDialog dialog = pending.dialog;
        pending.dialog = null;
        try {
            if (dialog != null) mainHandler.post(() -> { try { if (dialog.isShowing()) dialog.dismiss(); } catch (Throwable ignored) { } });
            OverlayController.dismissConfirm(ctx, pending);
        } catch (RuntimeException ignored) { }
    }

    private void showConfirmNotification(String cmd, PendingConfirmation pending) {
        createConfirmChannel();
        String displayCmd = safeDisplay(cmd);
        String shortCmd = displayCmd.length() > 100 ? displayCmd.substring(0, 100) + "…" : displayCmd;
        // data 参与 PendingIntent 身份比较，旧通知持有者不会被 UPDATE_CURRENT 更新为新请求。
        android.net.Uri identity = android.net.Uri.parse(pending.request.identity);
        Intent allowI = new Intent(ctx, ConfirmReceiver.class).setAction(ConfirmReceiver.ACTION_ALLOW).setData(identity);
        Intent denyI = new Intent(ctx, ConfirmReceiver.class).setAction(ConfirmReceiver.ACTION_DENY).setData(identity);
        pending.allow = PendingIntent.getBroadcast(ctx, 31, allowI,
                PendingIntent.FLAG_ONE_SHOT | PendingIntent.FLAG_IMMUTABLE);
        pending.deny = PendingIntent.getBroadcast(ctx, 32, denyI,
                PendingIntent.FLAG_ONE_SHOT | PendingIntent.FLAG_IMMUTABLE);
        Notification n = new NotificationCompat.Builder(ctx, CONFIRM_CHANNEL)
                .setSmallIcon(R.drawable.ic_launch)
                .setContentTitle(com.deepseekharness.app.util.UiText.text("⚠️ DSHA 安全确认"))
                .setContentText(com.deepseekharness.app.util.UiText.text("模型试图执行：") + shortCmd)
                .setStyle(new NotificationCompat.BigTextStyle()
                        .bigText(com.deepseekharness.app.util.UiText.text("模型试图在设备上执行：\n") + displayCmd
                                + com.deepseekharness.app.util.UiText.text("\n\n是否允许？")))
                .addAction(0, com.deepseekharness.app.util.UiText.text("允许"), pending.allow)
                .addAction(0, com.deepseekharness.app.util.UiText.text("拒绝"), pending.deny)
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setContentIntent(agentNotificationIntent()).setOngoing(true).build();
        NotificationManager nm = (NotificationManager) ctx.getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm != null) nm.notify(CONFIRM_NOTIF_ID, n);
    }

    private void createConfirmChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel ch = new NotificationChannel(
                    CONFIRM_CHANNEL, com.deepseekharness.app.util.UiText.text("安全确认"),
                    NotificationManager.IMPORTANCE_HIGH);
            ch.setDescription(com.deepseekharness.app.util.UiText.text("模型执行危险操作时的确认提醒"));
            NotificationManager nm = (NotificationManager) ctx.getSystemService(Context.NOTIFICATION_SERVICE);
            if (nm != null) nm.createNotificationChannel(ch);
        }
    }

    private static String jsonEscape(String s) {
        StringBuilder sb = new StringBuilder();
        for (char ch : s.toCharArray()) {
            switch (ch) {
                case '"': sb.append("\\\""); break;
                case '\\': sb.append("\\\\"); break;
                case '\n': sb.append("\\n"); break;
                case '\r': sb.append("\\r"); break;
                case '\t': sb.append("\\t"); break;
                default:
                    if (ch < 0x20) sb.append(String.format("\\u%04x", (int) ch));
                    else sb.append(ch);
            }
        }
        return sb.toString();
    }
}
