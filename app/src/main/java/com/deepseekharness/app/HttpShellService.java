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
import com.deepseekharness.app.util.BridgeRoutes;

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
import java.util.EnumMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * 极简 HTTP 服务（host 侧，端口 3090），把 Shizuku shell 能力桥接给 rootfs 里的助手。
 * rootfs 内的 agent 可用 bash 工具执行：
 *   curl -s -H @/root/.dsh/.bridge_headers "http://127.0.0.1:3090/exec?cmd=<urlencoded>"
 * 返回 JSON：{"result":"...输出...[EXIT=0]"}
 *
 * 设备命令使用默认拒绝的原生策略；高危与未知命令不可通过确认开关放行。
 */
public final class HttpShellService {

  public static final int PORT = 3090;
  private static final String CONFIRM_CHANNEL = "dsh_confirm_channel";
  private static final int CONFIRM_NOTIF_ID = Constants.NOTIF_SHELL_CONFIRM;

  /** 智能体通知：与确认通知使用不同 id / requestCode，互不覆盖。 */
  private static final int AGENT_NOTIF_ID = com.deepseekharness.app.util.NotificationIds.AGENT;

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
      new com.deepseekharness.app.util.SharedServiceLeases<>(
          HttpShellService::startListener, HttpShellService::stopListener);

  public static final class Lease implements AutoCloseable {
    private final com.deepseekharness.app.util.SharedServiceLeases<HttpShellService>.Lease demand;

    private Lease(com.deepseekharness.app.util.SharedServiceLeases<HttpShellService>.Lease demand) {
      this.demand = demand;
    }

    public void ensureStarted() {
      demand.ensureStarted();
    }

    @Override
    public void close() {
      demand.close();
    }
  }

  /** 调用入口持有自己的需求；关闭入口不能停止仍被其它入口使用的监听。 */
  public static Lease acquire(Context context) {
    return new Lease(DEMANDS.acquire(() -> new HttpShellService(context)));
  }

  private Lease legacyLease;

  private final Context ctx;
  private final java.io.File fixtureTokenFile;
  private final java.util.function.Consumer<BridgeAskDialog> fixtureAskObserver;

  /** 绑定前也必须知道本轮 token 的文件归属；instance 仍仅发布已经就绪的监听。 */
  private static volatile HttpShellService tokenOwner;

  private final Handler mainHandler = new Handler(Looper.getMainLooper());
  private final com.deepseekharness.app.util.BridgeConfirmations confirmations =
      new com.deepseekharness.app.util.BridgeConfirmations();
  private volatile PendingConfirmation pendingConfirm; // 与 LIFECYCLE 共用锁，旧 run 不能清理新请求。

  private static final class PendingConfirmation {
    final com.deepseekharness.app.util.BridgeConfirmations.Request request;
    volatile PendingIntent allow, deny;
    androidx.appcompat.app.AlertDialog dialog;

    PendingConfirmation(com.deepseekharness.app.util.BridgeConfirmations.Request request) {
      this.request = request;
    }
  }

  private final BridgeQuestions questions = new BridgeQuestions();
  private volatile boolean running;
  private volatile BridgeRun activeRun;
  private final ThreadLocal<BridgeRun> requestRun = new ThreadLocal<>();

  private interface RouteHandler {
    String handle(HttpShellService service, BridgeRequest request) throws Exception;
  }

  private static final class BridgeRequest {
    final String path, route, query, command;
    final boolean post;
    final com.deepseekharness.app.util.HttpProtocol.Head head;
    final java.io.InputStream input;
    final Socket socket;

    BridgeRequest(
        String path,
        String route,
        String command,
        boolean post,
        com.deepseekharness.app.util.HttpProtocol.Head head,
        java.io.InputStream input,
        Socket socket) {
      this.path = path;
      this.route = route;
      this.query = queryOf(path);
      this.command = command;
      this.post = post;
      this.head = head;
      this.input = input;
      this.socket = socket;
    }

    String param(String name, String fallback) {
      return getParam(query, name, fallback);
    }
  }

  /** 路由选择是纯逻辑；各处理器只在 HTTP 鉴权与运行任务门禁通过后调用。 */
  private static final Map<BridgeRoutes.Route, RouteHandler> ROUTE_HANDLERS = routeHandlers();

  private static Map<BridgeRoutes.Route, RouteHandler> routeHandlers() {
    Map<BridgeRoutes.Route, RouteHandler> routes = new EnumMap<>(BridgeRoutes.Route.class);
    routes.put(
        BridgeRoutes.Route.DEVICE_VSCREEN_START,
        (s, r) -> s.deviceVscreenStart(r.command, r.param("ticket", "")));
    routes.put(
        BridgeRoutes.Route.DEVICE_VSCREEN_COMMIT,
        (s, r) -> s.deviceVscreenCommit(r.param("ticket", "")));
    routes.put(
        BridgeRoutes.Route.DEVICE_PLAN,
        (s, r) -> s.devicePlan(r.command, "1".equals(r.param("su", "0"))));
    routes.put(
        BridgeRoutes.Route.DEVICE_EXECUTE,
        (s, r) ->
            s.deviceExecute(
                r.command, "1".equals(r.param("su", "0")), "1".equals(r.param("adb", "0"))));
    routes.put(BridgeRoutes.Route.NOTIFY, (s, r) -> s.appNotify(r.path));
    routes.put(BridgeRoutes.Route.TOAST, (s, r) -> s.appToast(r.path));
    routes.put(BridgeRoutes.Route.READ_FILE, (s, r) -> s.appReadFile(r.path));
    routes.put(BridgeRoutes.Route.HEALTH, (s, r) -> "OK");
    routes.put(BridgeRoutes.Route.UI, (s, r) -> s.appUi(r.path));
    routes.put(
        BridgeRoutes.Route.VSCREEN,
        (s, r) ->
            s.appVscreen(
                r.post
                    ? com.deepseekharness.app.util.VscreenBridgeRequest.json(
                        r.route,
                        com.deepseekharness.app.util.VscreenBridgeRequest.readPost(
                            r.head, r.input, r.socket))
                    : com.deepseekharness.app.util.VscreenBridgeRequest.query(r.route, r.query)));
    routes.put(BridgeRoutes.Route.DEVICE, (s, r) -> s.appDevice());
    routes.put(BridgeRoutes.Route.APPS, (s, r) -> s.appList(r.path));
    routes.put(BridgeRoutes.Route.LAUNCH, (s, r) -> s.appLaunch(r.path));
    routes.put(BridgeRoutes.Route.CLIP, (s, r) -> s.appClip(r.path));
    routes.put(BridgeRoutes.Route.SHARE, (s, r) -> s.appShare(r.path));
    routes.put(BridgeRoutes.Route.OPEN, (s, r) -> s.appOpen(r.path));
    routes.put(BridgeRoutes.Route.VIBRATE, (s, r) -> s.appVibrate(r.path));
    routes.put(BridgeRoutes.Route.ASK, (s, r) -> s.appAsk(r.path));
    routes.put(BridgeRoutes.Route.VERSION, (s, r) -> s.appVersion());
    routes.put(BridgeRoutes.Route.HELP, (s, r) -> s.appHelp());
    routes.put(BridgeRoutes.Route.PLUGINS, (s, r) -> s.appPlugins(r.path));
    routes.put(BridgeRoutes.Route.OVERLAY, (s, r) -> s.appOverlay(r.path));
    routes.put(
        BridgeRoutes.Route.LOCATION,
        (s, r) -> DeviceSense.location(s.ctx, "1".equals(r.param("fresh", ""))));
    routes.put(BridgeRoutes.Route.SENSORS, (s, r) -> DeviceSense.sensorList(s.ctx));
    routes.put(
        BridgeRoutes.Route.SENSOR,
        (s, r) -> DeviceSense.sensorRead(s.ctx, r.param("name", "light")));
    routes.put(
        BridgeRoutes.Route.TORCH,
        (s, r) -> {
          String on = r.param("on", "1");
          return DeviceSense.torch(s.ctx, !"0".equals(on) && !"off".equalsIgnoreCase(on));
        });
    routes.put(BridgeRoutes.Route.EXPORT, (s, r) -> s.appExport(r.path));
    routes.put(BridgeRoutes.Route.CONFIRM, (s, r) -> s.confirmReadOnly(r.command));
    routes.put(BridgeRoutes.Route.EXEC, (s, r) -> s.execOutput(r.command));
    return java.util.Collections.unmodifiableMap(routes);
  }

  /** 每一轮监听独立持有 socket/线程池，迟到的旧线程只能清理自己的资源。 */
  private static final class BridgeRun {
    final long generation;
    volatile ServerSocket server, server6;
    final java.util.Set<Socket> clients =
        java.util.Collections.newSetFromMap(new java.util.concurrent.ConcurrentHashMap<>());
    final java.util.concurrent.ExecutorService pool =
        new java.util.concurrent.ThreadPoolExecutor(
            4,
            4,
            0,
            TimeUnit.MILLISECONDS,
            new java.util.concurrent.ArrayBlockingQueue<>(16),
            r -> {
              Thread t = new Thread(r, "http-shell");
              t.setDaemon(true);
              return t;
            });

    BridgeRun(long generation) {
      this.generation = generation;
    }
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
  HttpShellService(
      Context ctx,
      java.io.File fixtureTokenFile,
      java.util.function.Consumer<BridgeAskDialog> fixtureAskObserver) {
    if (fixtureTokenFile != null && !BuildConfig.DEBUG)
      throw new IllegalStateException(
          com.deepseekharness.app.util.UiText.text("仅 debug 可注入 token 文件"));
    if (fixtureAskObserver != null && (!BuildConfig.DEBUG || fixtureTokenFile == null))
      throw new IllegalStateException(
          com.deepseekharness.app.util.UiText.text("仅隔离 debug fixture 可观察提问窗口"));
    this.ctx = ctx.getApplicationContext();
    this.fixtureTokenFile = fixtureTokenFile;
    this.fixtureAskObserver = fixtureAskObserver;
  }

  public static HttpShellService instance() {
    synchronized (LIFECYCLE) {
      return isReady() ? instance : null;
    }
  }

  /** 供设备桥保活判断；仅 IPv4 主监听已绑定并存活才算就绪。 */
  public static boolean isReady() {
    synchronized (LIFECYCLE) {
      HttpShellService current = instance;
      BridgeRun run = current == null ? null : current.activeRun;
      return LIFECYCLE.isReady()
          && current != null
          && current.running
          && run != null
          && run.server != null
          && run.server.isBound()
          && !run.server.isClosed();
    }
  }

  public static boolean isStarting() {
    return LIFECYCLE.isStarting();
  }

  public static java.util.Map<String, Long> resourceMetrics() {
    HttpShellService current = instance();
    BridgeRun run = current == null ? null : current.activeRun;
    if (run == null) return java.util.Collections.emptyMap();
    var pool = (java.util.concurrent.ThreadPoolExecutor) run.pool;
    return java.util.Map.of(
        "connections",
        (long) run.clients.size(),
        "queued",
        (long) pool.getQueue().size(),
        "threads",
        (long) pool.getPoolSize());
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
        var fs = new com.deepseekharness.app.backup.AndroidBackupFileSystem();
        String authority = c.getApplicationInfo().dataDir;
        if (authority == null) return null;
        java.io.File files =
            com.deepseekharness.app.util.ColdInstallPackages.privateFiles(
                fs, new java.io.File(authority), c.getFilesDir());
        java.io.File data = new com.deepseekharness.app.backup.UserDataLayout(fs, files).current();
        if (!fs.stat(data).type.equals("DIRECTORY")) return null;
        return fs.child(data, com.deepseekharness.app.util.CredentialPaths.BRIDGE_TOKEN);
      }
    } catch (Throwable ignored) {
    }
    return null;
  }

  /** 读取 rootfs 内 token 文件（只读，不修改内容）。 */
  private static String readTokenFromFile(java.io.File tf) {
    if (tf == null) return null;
    try {
      return com.deepseekharness.app.util.BridgeCredentialFiles.read(
          new com.deepseekharness.app.backup.AndroidBackupFileSystem(), tf);
    } catch (java.io.IOException error) {
      throw new IllegalStateException("BRIDGE_TOKEN_UNREADABLE", error);
    }
  }

  private static String ensureToken() {
    synchronized (HttpShellService.class) {
      java.io.File tf = tokenFileIfPossible();
      if (tf == null) {
        if (authToken.isEmpty())
          authToken = java.util.UUID.randomUUID().toString().replace("-", "");
        return authToken;
      }
      try {
        var fs = new com.deepseekharness.app.backup.AndroidBackupFileSystem();
        String token = readTokenFromFile(tf);
        boolean replace = token == null || token.isEmpty();
        if (replace) token = java.util.UUID.randomUUID().toString().replace("-", "");
        com.deepseekharness.app.util.BridgeCredentialFiles.publish(fs, tf, token, replace);
        authToken = token;
        com.deepseekharness.app.util.SensitiveData.registerKnownSecret(token);
        return authToken;
      } catch (Throwable error) {
        authToken = "";
        android.util.Log.w("DSHA", "BRIDGE_TOKEN_UNAVAILABLE: " + safeError(error));
        return "";
      }
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
    android.util.Log.e("DSHA", com.deepseekharness.app.util.UiText.format("3090 桥绑定失败：%s", safe));
    com.deepseekharness.app.core.DiagnosticLog.record(ctx, "BRIDGE_BIND", safe);
    writeBridgeStatus("fail " + safe);
  }

  /** 桥状态落到 rootfs 的 /root/.dsh/.bridge_status，容器里 cat 一下就知道桥为什么不通 */
  private void writeBridgeStatus(String s) {
    try {
      java.io.File tf = tokenFileIfPossible();
      if (tf == null || tf.getParentFile() == null) return;
      java.io.File f = new java.io.File(tf.getParentFile(), ".bridge_status");
      new com.deepseekharness.app.backup.AndroidBackupFileSystem()
          .atomic(
              f.getParentFile(),
              f.getName(),
              (s + "\n").getBytes(java.nio.charset.StandardCharsets.UTF_8));
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
        else {
          noteBindError(safeError(error));
          LIFECYCLE.finish(generation);
        }
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
      run.server.bind(
          new java.net.InetSocketAddress(java.net.InetAddress.getByName("127.0.0.1"), PORT));
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
      failure =
          com.deepseekharness.app.util.UiText.format(
              "设备桥端口 3090 被占用，设备工具暂不可用；Web 可独立启动。关闭占用端口的其他实例后重试：%s", safeError(error));
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
      if (activeRun == run && running)
        android.util.Log.i(
            "DSHA",
            com.deepseekharness.app.util.UiText.format("3090 的 IPv6 附加监听不可用：%s", safeError(error)));
    } finally {
      closeSocket(run.server6);
    }
  }

  /** 接受连接并分发到线程池（IPv4/IPv6 两个监听共用） */
  private void acceptLoop(BridgeRun run, ServerSocket ss) throws IOException {
    while (running && activeRun == run) {
      Socket client = ss.accept();
      try {
        // 排队时间计入请求头总截止时间；鉴权后的命令/确认不受此预算影响。
        client.setSoTimeout(com.deepseekharness.app.util.HttpProtocol.BRIDGE.timeoutMs);
        synchronized (LIFECYCLE) {
          if (!running || activeRun != run) {
            client.close();
            return;
          }
          if (run.clients.size() >= 20) {
            closeSocket(client);
            continue;
          }
          long headerDeadline =
              com.deepseekharness.app.util.HttpProtocol.deadline(
                  com.deepseekharness.app.util.HttpProtocol.BRIDGE.timeoutMs);
          run.clients.add(client);
          run.pool.execute(
              () -> {
                requestRun.set(run);
                try {
                  handle(client, headerDeadline);
                } finally {
                  requestRun.remove();
                  run.clients.remove(client);
                }
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
    if (legacyLease != null) {
      legacyLease.close();
      legacyLease = null;
    }
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
      try {
        revokeScreenGrant(ctx);
      } catch (RuntimeException error) {
        android.util.Log.w("DSHA", "BRIDGE_GRANT_REVOKE", error);
      }
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
      if (fixtureTokenFile != null) {
        authToken = "";
        tokenCtx = null;
      }
      LIFECYCLE.finish(run.generation);
    }
  }

  private static void closeSocket(java.io.Closeable socket) {
    if (socket != null)
      try {
        socket.close();
      } catch (IOException ignored) {
      }
  }

  /** Current 3090 bridge token for managed X-Token clients; unavailable identity returns empty. */
  public static String currentToken() {
    try {
      String t = ensureToken();
      return t == null ? "" : t;
    } catch (Throwable e) {
      return "";
    }
  }

  /** 自检用：当前内存里的桥 token 快照（空串 = 桥还没起来过）。
   *  故意不触发生成 —— 自检本身不该有副作用，写文件那是
   *  {@link #resetTokenAfterRestore(Context)} 的活儿。 */
  static String tokenSnapshot() {
    return authToken == null ? "" : authToken;
  }

  /** After a committed restore, atomically rotate the host bridge token and revoke screen grants. */
  public static void resetTokenAfterRestore(Context context) {
    bindTokenContext(context);
    synchronized (HttpShellService.class) {
      authToken = "";
      java.io.File file = tokenFileIfPossible();
      if (file == null) return;
      try {
        String token = java.util.UUID.randomUUID().toString().replace("-", "");
        com.deepseekharness.app.util.BridgeCredentialFiles.publish(
            new com.deepseekharness.app.backup.AndroidBackupFileSystem(), file, token, true);
        authToken = token;
        com.deepseekharness.app.util.SensitiveData.registerKnownSecret(token);
        revokeScreenGrant(context);
      } catch (Throwable error) {
        android.util.Log.w("DSHA", "BRIDGE_TOKEN_ROTATION_FAILED: " + safeError(error));
      }
    }
  }

  private static boolean tokenMatch(String presented) {
    String token = authToken.isEmpty() ? ensureToken() : authToken;
    return token != null && !token.isEmpty() && LanAuth.constantTimeEquals(token, presented);
  }

  /** Parse a bounded request and authenticate its X-Token header before dispatch. */
  private void handle(Socket client, long headerDeadline) {
    try (Socket c = client) {
      java.io.BufferedInputStream requestInput =
          new java.io.BufferedInputStream(c.getInputStream(), 8192);
      var request =
          com.deepseekharness.app.util.HttpProtocol.readHead(
              requestInput,
              c,
              com.deepseekharness.app.util.HttpProtocol.BRIDGE,
              headerDeadline,
              true);
      if (request == null) return;
      synchronized (LIFECYCLE) {
        if (!running || requestRun.get() != activeRun) return;
      }
      String path = request.target, route = path.split("\\?", 2)[0];
      boolean post =
          request.method.equals("POST")
              && com.deepseekharness.app.util.VscreenBridgeRequest.postRoute(route);
      if (!post
          && (!request.method.equals("GET")
              || request.requestBody().kind
                  != com.deepseekharness.app.util.HttpProtocol.Kind.NONE)) {
        writeBridgeProtocolError(c, 405, "METHOD_NOT_ALLOWED");
        return;
      }
      c.setSoTimeout(0);
      BridgeRoutes.Route selected = BridgeRoutes.match(route);
      // 保留原命令参数的精确解析；设备计划允许空命令并交给自身策略判定。
      String cmd = selected.commandParameter() ? getParam(queryOf(path), "cmd", "") : "";
      java.util.List<String> tokenHeaders = request.values("X-Token");
      boolean authed =
          com.deepseekharness.app.util.BridgeCredentialAuth.authorized(
              path, tokenHeaders, authToken.isEmpty() ? ensureToken() : authToken);
      // 预连接与未完成鉴权的 socket 不读写环境，不能占住维护屏障。
      try (com.deepseekharness.app.core.RuntimeTasks work =
          authed ? com.deepseekharness.app.core.RuntimeTasks.begin() : null) {
        String result;
        if (!authed) {
          result = "[UNAUTHORIZED]";
        } else {
          BridgeRequest bridgeRequest =
              new BridgeRequest(path, route, cmd, post, request, requestInput, c);
          try {
            result = dispatch(selected, bridgeRequest);
          } catch (com.deepseekharness.app.util.HttpProtocol.Failure invalid) {
            if (selected != BridgeRoutes.Route.VSCREEN) throw invalid;
            writeBridgeProtocolError(c, invalid.status, invalid.getMessage());
            return;
          } catch (java.net.SocketTimeoutException timeout) {
            if (selected != BridgeRoutes.Route.VSCREEN) throw timeout;
            writeBridgeProtocolError(c, 408, "VSCREEN_BODY_DEADLINE");
            return;
          }
        }
        // 关键：result 必须包引号 —— 旧实现输出 {"result":YES} 是非法 JSON，
        // 客户端（adb-shell.py 判 '"YES"' in body / agent 用 json 解析）全部失效：
        // 用户点「允许」也会被当成拒绝。
        String body = "{\"result\":\"" + jsonEscape(result) + "\"}";
        byte[] bodyBytes = body.getBytes("UTF-8");
        String head =
            "HTTP/1.1 200 OK\r\n"
                + "Content-Type: application/json; charset=utf-8\r\n"
                + "Content-Length: "
                + bodyBytes.length
                + "\r\n"
                + "Connection: close\r\n\r\n";
        c.getOutputStream().write(head.getBytes("UTF-8"));
        c.getOutputStream().write(bodyBytes);
        c.getOutputStream().flush();
      }
    } catch (Exception ignored) {
    }
  }

  private String dispatch(BridgeRoutes.Route route, BridgeRequest request) throws Exception {
    // 历史响应：未知路径不解析 cmd，所以返回 NO_CMD。
    if (route == BridgeRoutes.Route.UNKNOWN) return "[NO_CMD]";
    if ((route == BridgeRoutes.Route.CONFIRM || route == BridgeRoutes.Route.EXEC)
        && request.command.isEmpty()) return "[NO_CMD]";
    RouteHandler handler = ROUTE_HANDLERS.get(route);
    if (handler == null) throw new IllegalStateException("Missing bridge handler: " + route);
    return handler.handle(this, request);
  }

  private String confirmReadOnly(String command) {
    com.deepseekharness.app.util.DeviceShellPolicy.Plan plan =
        com.deepseekharness.app.util.DeviceShellPolicy.inspect(command);
    // 旧确认接口不发放写入或结束进程的放行信号。
    return plan.kind == com.deepseekharness.app.util.DeviceShellPolicy.Kind.READ ? "YES" : "NO";
  }

  private String execOutput(String command) throws org.json.JSONException {
    org.json.JSONObject execution = new org.json.JSONObject(deviceExecute(command, false, false));
    return execution.optString(
        "output",
        com.deepseekharness.app.util.UiText.text("[ADB_REQUIRED] 请使用 adb-shell 执行此命令\n[EXIT=124]"));
  }

  private static void writeBridgeProtocolError(Socket socket, int status, String code)
      throws IOException {
    String reason =
        status == 405
            ? "Method Not Allowed"
            : status == 413
                ? "Payload Too Large"
                : status == 415
                    ? "Unsupported Media Type"
                    : status == 411
                        ? "Length Required"
                        : status == 408 ? "Request Timeout" : "Bad Request";
    byte[] body =
        ("{\"ok\":false,\"error\":\"" + code + "\"}")
            .getBytes(java.nio.charset.StandardCharsets.UTF_8);
    String header =
        "HTTP/1.1 "
            + status
            + " "
            + reason
            + "\r\nContent-Type: application/json; charset=utf-8\r\nContent-Length: "
            + body.length
            + "\r\nConnection: close\r\n"
            + (status == 405 ? "Allow: GET, POST\r\n" : "")
            + "\r\n";
    socket.getOutputStream().write(header.getBytes(java.nio.charset.StandardCharsets.US_ASCII));
    socket.getOutputStream().write(body);
    socket.getOutputStream().flush();
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
        return completedExecution(
            "none",
            com.deepseekharness.app.util.UiText.text(
                "[DEVICE_CHANNEL_UNAVAILABLE] 请在设置 → 设备能力授权中连接 root、Shizuku 或 ADB 通道\n[EXIT=124]"));
      plan.put("su", forceRoot);
      return new org.json.JSONObject().put("state", "adb").put("plan", plan).toString();
    } catch (Exception error) {
      // 此时可能已经向设备发过命令，绝不能返回可重试的 ADB 计划。
      return completedExecution(
          "unknown", "[EXECUTION_UNKNOWN] " + safeError(error) + "\n[EXIT=125]");
    }
  }

  /** ADB-only typed start plan; a one-use ticket is minted by VirtualScreenManager after native authorization. */
  private String deviceVscreenStart(String command, String ticket) {
    try {
      if (!DeviceBridgeService.isAdbEnabled(ctx)
          || !com.deepseekharness.app.bridge.LocalNetworkAccess.granted(ctx))
        return completedExecution("policy", "[POLICY_BLOCKED] ADB 通道不可用\n[EXIT=126]");
      String source = ctx.getApplicationInfo().sourceDir;
      String canonical = new java.io.File(source).getCanonicalPath();
      if (!source.equals(canonical))
        return completedExecution("policy", "[POLICY_BLOCKED] 无法核验 DSHA 安装包路径\n[EXIT=126]");
      var plan =
          com.deepseekharness.app.util.DeviceShellPolicy.inspectVirtualScreenLaunch(
              command, canonical, ctx.getPackageName());
      if (!plan.allowed()) return completedExecution("policy", plan.reason + "\n[EXIT=126]");
      if (!com.deepseekharness.app.vscreen.VirtualScreenManager.from(ctx)
          .authorizeAdbLaunchPlan(ticket, command))
        return completedExecution("policy", "[POLICY_BLOCKED] 虚拟屏启动许可无效、过期或已使用\n[EXIT=126]");
      org.json.JSONObject value =
          new org.json.JSONObject()
              .put("version", 1)
              .put("kind", plan.kind.name())
              .put("reason", "")
              .put("argv", new org.json.JSONArray(plan.argv))
              .put("operands", new org.json.JSONArray())
              .put("sourceApk", canonical)
              .put("selfPackage", ctx.getPackageName())
              .put("selfUid", ctx.getApplicationInfo().uid)
              .put("nativeAuthorization", "managed-vscreen-start")
              .put("nativeTicket", ticket)
              .put("su", false);
      return new org.json.JSONObject().put("state", "adb").put("plan", value).toString();
    } catch (Exception error) {
      return completedExecution("policy", "[POLICY_BLOCKED] " + safeError(error) + "\n[EXIT=126]");
    }
  }

  /** Called by the bundled ADB client immediately before its sole remote shell send. */
  private String deviceVscreenCommit(String ticket) {
    return com.deepseekharness.app.vscreen.VirtualScreenManager.from(ctx).commitAdbLaunch(ticket)
        ? "VSCREEN_START_COMMITTED"
        : "[POLICY_BLOCKED] 虚拟屏启动许可已撤销或过期";
  }

  private String completedExecution(String transport, String output) {
    try {
      return new org.json.JSONObject()
          .put("state", "completed")
          .put("transport", transport)
          .put("output", output)
          .put("exit", com.deepseekharness.app.util.DeviceCommandResult.exitCode(output))
          .toString();
    } catch (org.json.JSONException error) {
      return com.deepseekharness.app.util.UiText.text("[EXECUTION_UNKNOWN] 结果编码失败\n[EXIT=125]");
    }
  }

  /** ADB 每次发送前向原生策略取计划；计划只包含已验证 argv，拒绝时没有可执行内容。 */
  private String devicePlan(String command, boolean root) {
    try {
      com.deepseekharness.app.util.DeviceShellPolicy.Plan plan =
          com.deepseekharness.app.util.DeviceShellPolicy.inspect(command);
      org.json.JSONObject value =
          new org.json.JSONObject()
              .put("version", 1)
              .put("kind", plan.kind.name())
              .put("reason", plan.reason)
              .put("argv", new org.json.JSONArray(plan.argv))
              .put("operands", new org.json.JSONArray(plan.operands))
              .put(
                  "paths",
                  new org.json.JSONObject(
                      com.deepseekharness.app.util.DeviceShellPolicy.pathRules()));
      String source = ctx.getApplicationInfo().sourceDir;
      if (!source.equals(new java.io.File(source).getCanonicalPath())
          || !com.deepseekharness.app.util.DeviceShellPolicy.canonicalApkPath(source))
        return value
            .put("kind", "DENY")
            .put("argv", new org.json.JSONArray())
            .put("reason", "SELF_PACKAGE_SOURCE_UNVERIFIED")
            .toString();
      value
          .put("selfPackage", ctx.getPackageName())
          .put("selfUid", ctx.getApplicationInfo().uid)
          .put("sourceApk", source);
      if (root
          && !ctx.getSharedPreferences(Constants.PREFS, 0)
              .getBoolean(Constants.KEY_ALLOW_ROOT_SHELL, false))
        return value
            .put("kind", "DENY")
            .put("argv", new org.json.JSONArray())
            .put(
                "reason",
                com.deepseekharness.app.util.UiText.text("[POLICY_BLOCKED] 未允许 root shell"))
            .toString();
      if (plan.kind == com.deepseekharness.app.util.DeviceShellPolicy.Kind.SENSITIVE_READ) {
        java.util.List<String> query =
            com.deepseekharness.app.util.SmsQuery.forUser(
                plan.argv, android.os.Process.myUid() / 100000);
        if (!new com.deepseekharness.app.core.DeviceGrants(ctx).smsReadAllowed())
          return value
              .put("kind", "DENY")
              .put("argv", new org.json.JSONArray())
              .put(
                  "reason",
                  com.deepseekharness.app.util.UiText.choose(
                      "[POLICY_BLOCKED] 短信读取已关闭，请在设备能力授权中开启",
                      "[POLICY_BLOCKED] SMS reading is off. Enable it in Device permissions"))
              .toString();
        value
            .put("kind", "READ")
            .put("argv", new org.json.JSONArray(query))
            .put("capability", "sms.read")
            .put("authorization", "remembered");
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
      NotificationManager nm =
          (NotificationManager) ctx.getSystemService(Context.NOTIFICATION_SERVICE);
      if (nm == null) return "NO_SERVICE";
      if (Build.VERSION.SDK_INT >= 26) {
        NotificationChannel ch =
            new NotificationChannel(
                "dsh_agent_channel",
                com.deepseekharness.app.util.UiText.text("Agent 通知"),
                NotificationManager.IMPORTANCE_HIGH);
        ch.setDescription(com.deepseekharness.app.util.UiText.text("智能体通过 App 发送的通知"));
        nm.createNotificationChannel(ch);
      }
      NotificationCompat.Builder b =
          new NotificationCompat.Builder(ctx, "dsh_agent_channel")
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
    Intent intent =
        new Intent(ctx, com.deepseekharness.app.ui.MainActivity.class)
            .addFlags(
                Intent.FLAG_ACTIVITY_NEW_TASK
                    | Intent.FLAG_ACTIVITY_CLEAR_TOP
                    | Intent.FLAG_ACTIVITY_SINGLE_TOP)
            .putExtra("open_web", true);
    return PendingIntent.getActivity(
        ctx,
        AGENT_NOTIF_REQUEST,
        intent,
        PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
  }

  // ==================== 屏幕操作的授权闸门 ====================
  //
  // 为什么必须有这道闸：/app/ui/* 能读屏、点按、输入，破坏力其实**超过** shell 命令 ——
  // 它直接操作用户**已经登录**的应用，绕过所有应用层权限。agent 一旦被 prompt
  // injection 诱导（读到网页或文件里夹带的指令），就能在支付软件里点按、把私信
  // 截屏留到磁盘。而 /exec 一直有危险命令守卫，UI 操作在我加完那六个端点之后
  // 一道闸都没有 —— 这是自查时发现的最大缺口。
  //
  // 本次 DSH 运行有效；停止、无障碍断开或用户撤销时失效。敏感界面仍逐次确认。
  private static final com.deepseekharness.app.util.ScreenSessionGrant uiGrant =
      new com.deepseekharness.app.util.ScreenSessionGrant();

  public static void revokeScreenGrant(Context context) {
    uiGrant.revoke();
    com.deepseekharness.app.vscreen.VirtualScreenManager.from(context).revoke();
  }

  public static boolean hasScreenGrant(Context context) {
    var controller = com.deepseekharness.app.core.HarnessController.get(context);
    return !controller.isStopping()
        && !controller.isUserStopped()
        && uiGrant.allowed(controller.getWebGeneration());
  }

  /** 涉钱、涉密的应用：宁可多问一次。取不到包名也按敏感处理。 */
  private static boolean isSensitiveApp(String pkg) {
    return com.deepseekharness.app.util.SensitiveAppPolicy.sensitive(pkg);
  }

  /** 屏幕授权的三态结果：桥要能区分「用户还没点允许」与「用户拒绝了」（issue #123）。 */
  private enum ScreenAuthorization {
    ALLOWED,
    DENIED,
    NO_ANSWER,
    UNAVAILABLE
  }

  /** @param action 给用户看的具体动作描述 —— 弹窗必须说清 AI 要干什么，
   *               而不是笼统一句「操作屏幕」，否则用户等于盲签。 */
  private boolean uiAuthorized(String action) {
    return uiAuthorized(action, DshaAccessibilityService.currentPackage());
  }

  private boolean uiAuthorized(String action, String pkg) {
    return screenAuthorization(action, pkg) == ScreenAuthorization.ALLOWED;
  }

  /** 同一次屏幕授权请求的完整结果；只被需要区分三态的端点使用（如 /app/vscreen/*）。 */
  private ScreenAuthorization screenAuthorization(String action, String pkg) {
    boolean sensitive = isSensitiveApp(pkg);
    var controller = com.deepseekharness.app.core.HarnessController.get(ctx);
    long generation = controller.getWebGeneration(), revision = uiGrant.revision();
    if (!sensitive && hasScreenGrant(ctx)) {
      return ScreenAuthorization.ALLOWED;
    }
    String where = pkg.isEmpty() ? com.deepseekharness.app.util.UiText.text("当前界面") : pkg;
    String why =
        sensitive
            ? com.deepseekharness.app.util.UiText.format(
                "在【%s】里：%s  # 这类应用涉及支付或隐私，每次都需要你确认", where, action)
            : com.deepseekharness.app.util.UiText.format(
                "%s  # 本次 DSH 运行期间有效，可在设备能力授权中随时撤销", action);
    ConfirmationOutcome outcome = confirmOutcome(why);
    // A sensitive confirmation is one-shot, but must obey the same revocation and
    // run identity checks as a remembered grant. Never revive an old dialog result.
    boolean granted =
        uiGrant.completeConfirmation(
            outcome == ConfirmationOutcome.ALLOWED,
            generation,
            controller.getWebGeneration(),
            revision,
            !controller.isStopping() && !controller.isUserStopped(),
            !sensitive);
    if (granted) return ScreenAuthorization.ALLOWED;
    if (outcome == ConfirmationOutcome.DENIED) return ScreenAuthorization.DENIED;
    if (outcome == ConfirmationOutcome.NO_ANSWER) return ScreenAuthorization.NO_ANSWER;
    return ScreenAuthorization.UNAVAILABLE;
  }

  private static String shortText(String s) {
    if (s == null) return "";
    String t = s.replace('\n', ' ').trim();
    return t.length() > 24 ? t.substring(0, 24) + "…" : t;
  }

  /** Accessibility requests retain the current generation, target and native screen-authorization checks. */
  private String appUi(String path) {
    var target = DshaAccessibilityService.observeTarget(0);
    if (target == null) return "[ERR] SCREEN_TARGET_UNAVAILABLE";
    var controller = HarnessController.get(ctx);
    long generation = controller.getWebGeneration(), revision = uiGrant.revision();
    return DshaAccessibilityService.runAuthorized(
        target,
        () ->
            generation == controller.getWebGeneration()
                && revision == uiGrant.revision()
                && !controller.isStopping()
                && !controller.isUserStopped(),
        () -> appUiAuthorized(path, target));
  }

  private boolean uiAuthorizedForTarget(
      com.deepseekharness.app.util.ScreenTarget target, String action) {
    return uiAuthorized(action, target.packageName);
  }

  private String appUiAuthorized(String path, com.deepseekharness.app.util.ScreenTarget target) {
    String q = queryOf(path);
    // 命名空间内的子端点也走精确匹配：startsWith 会让 /app/ui/shotXXX
    // 命中截屏，而截屏会把当前画面留到磁盘。
    String r = path.split("\\?", 2)[0];
    try {
      if (r.equals("/app/ui/dump")) {
        if (!uiAuthorizedForTarget(
            target, com.deepseekharness.app.util.UiText.text("读取当前屏幕上的文字与控件")))
          return com.deepseekharness.app.util.UiText.text("[ERR] 未获得本次屏幕读取授权（未确认或已拒绝）");
        return DshaAccessibilityService.uiDump();
      }
      if (r.equals("/app/ui/tap")) {
        String text = getParam(q, "text", "");
        // 有文字就按文字点：控件位置会随滚动和动画变，文字不会
        if (!text.isEmpty()) {
          if (!uiAuthorizedForTarget(
              target, com.deepseekharness.app.util.UiText.format("点击「%s」", shortText(text))))
            return com.deepseekharness.app.util.UiText.text("[ERR] 未获得本次点击授权（未确认或已拒绝）");
          return DshaAccessibilityService.uiTapText(text);
        }
        int x = intParam(q, "x", -1);
        int y = intParam(q, "y", -1);
        if (x < 0 || y < 0)
          return com.deepseekharness.app.util.UiText.text("[ERR] 需要 ?text=要点的文字 或 ?x=&y=坐标");
        if (!uiAuthorizedForTarget(
            target, com.deepseekharness.app.util.UiText.format("点击坐标 (%s,%s)", x, y)))
          return com.deepseekharness.app.util.UiText.text("[ERR] 未获得本次点击授权（未确认或已拒绝）");
        return DshaAccessibilityService.uiTap(x, y);
      }
      if (r.equals("/app/ui/input")) {
        String text = getParam(q, "text", null);
        if (text == null) return com.deepseekharness.app.util.UiText.text("[ERR] 需要 ?text=");
        if (!uiAuthorizedForTarget(
            target,
            text.isEmpty()
                ? com.deepseekharness.app.util.UiText.choose(
                    "清空当前输入框", "Clear the current input field")
                : com.deepseekharness.app.util.UiText.format("在输入框里填入「%s」", shortText(text)))) {
          return com.deepseekharness.app.util.UiText.text("[ERR] 未获得本次输入授权（未确认或已拒绝）");
        }
        return DshaAccessibilityService.uiInput(text);
      }
      if (r.equals("/app/ui/key")) {
        String k = getParam(q, "name", "");
        if (!uiAuthorizedForTarget(
            target, com.deepseekharness.app.util.UiText.format("按下系统按键 %s", shortText(k))))
          return com.deepseekharness.app.util.UiText.text("[ERR] 未获得本次按键授权（未确认或已拒绝）");
        return DshaAccessibilityService.uiKey(k);
      }
      if (r.equals("/app/ui/screenshot") || r.equals("/app/ui/shot")) {
        // 截屏会把当前画面留到磁盘，等于一份可被后续读取的隐私快照
        String format = getParam(q, "format", "path");
        if (!format.equals("path") && !format.equals("mcp"))
          return "[ERR] SCREENSHOT_FORMAT_INVALID";
        var controller = com.deepseekharness.app.core.HarnessController.get(ctx);
        long generation = controller.getWebGeneration(), revision = uiGrant.revision();
        if (!uiAuthorizedForTarget(
            target, com.deepseekharness.app.util.UiText.text("截取当前屏幕并保存为图片")))
          return com.deepseekharness.app.util.UiText.text("[ERR] 未获得本次截屏授权（未确认或已拒绝）");
        return DshaAccessibilityService.uiScreenshot(
            () ->
                generation == controller.getWebGeneration()
                    && revision == uiGrant.revision()
                    && !controller.isStopping()
                    && !controller.isUserStopped()
                    && DshaAccessibilityService.matchesTarget(target),
            format.equals("mcp"));
      }
      if (r.equals("/app/ui/swipe")) {
        int x1 = intParam(q, "x1", -1);
        int y1 = intParam(q, "y1", -1);
        int x2 = intParam(q, "x2", -1);
        int y2 = intParam(q, "y2", -1);
        if (x1 < 0 || y1 < 0 || x2 < 0 || y2 < 0) {
          return com.deepseekharness.app.util.UiText.text("[ERR] 需要 ?x1=&y1=&x2=&y2=（可选 &ms=时长）");
        }
        if (!uiAuthorizedForTarget(
            target,
            com.deepseekharness.app.util.UiText.format("滑动屏幕 (%s,%s)→(%s,%s)", x1, y1, x2, y2))) {
          return com.deepseekharness.app.util.UiText.text("[ERR] 未获得本次滑动授权（未确认或已拒绝）");
        }
        return DshaAccessibilityService.uiSwipe(x1, y1, x2, y2, intParam(q, "ms", 300));
      }
      return com.deepseekharness.app.util.UiText.text("[ERR] 未知端点（可用：dump/tap/input/key/swipe）");
    } catch (Throwable t) {
      return "[ERR] " + SensitiveData.redact(String.valueOf(t));
    }
  }

  /** 虚拟屏只接受已认证的固定端点；每个写入/启动动作仍复用当前屏幕授权确认。 */
  private String appVscreen(com.deepseekharness.app.util.VscreenBridgeRequest request) {
    String route = request.route(),
        query = request.query(),
        operation = com.deepseekharness.app.util.VirtualScreenRoutes.operation(route);
    try {
      if (operation.equals("status") || operation.equals("close"))
        return com.deepseekharness.app.vscreen.VirtualScreenManager.from(ctx).bridge(request);
      var controller = HarnessController.get(ctx);
      long generation = controller.getWebGeneration(), revision = uiGrant.revision();
      var status = com.deepseekharness.app.vscreen.VirtualScreenManager.from(ctx).status();
      int display = status.optInt("displayId", -1);
      var target = display > 0 ? DshaAccessibilityService.observeTarget(display) : null;
      String pkg =
          operation.equals("launch")
              ? getParam(query, "package", "")
              : target != null
                  ? target.packageName
                  : status.optBoolean("packageVerified") ? status.optString("package") : "";
      ScreenAuthorization authorization =
          screenAuthorization(com.deepseekharness.app.util.UiText.format("操作独立虚拟屏：%s", route), pkg);
      if (authorization != ScreenAuthorization.ALLOWED)
        // "用户还没点允许"与"用户拒绝了"必须给不同的码：前者调用方应等一会儿再问，
        // 后者不该重试（issue #123 的 60 秒挂起）。
        return "{\"ok\":false,\"error\":\""
            + (authorization == ScreenAuthorization.DENIED
                ? "USER_REJECTED"
                : authorization == ScreenAuthorization.NO_ANSWER
                    ? "USER_NO_ANSWER"
                    : "USER_CONFIRM_UNAVAILABLE")
            + "\"}";
      java.util.function.BooleanSupplier live =
          () ->
              generation == controller.getWebGeneration()
                  && revision == uiGrant.revision()
                  && !controller.isStopping()
                  && !controller.isUserStopped();
      if (!live.getAsBoolean()) return "{\"ok\":false,\"error\":\"SCREEN_RUN_CHANGED\"}";
      java.util.function.Supplier<String> action =
          () -> {
            if (!live.getAsBoolean()
                || target != null
                    && !operation.equals("launch")
                    && !DshaAccessibilityService.matchesTarget(target))
              return "{\"ok\":false,\"error\":\"SCREEN_TARGET_CHANGED\"}";
            String result =
                com.deepseekharness.app.vscreen.VirtualScreenManager.from(ctx)
                    .withBridgeTarget(
                        operation.equals("launch") ? "" : pkg,
                        () ->
                            live.getAsBoolean()
                                && (target == null
                                    || operation.equals("launch")
                                    || DshaAccessibilityService.matchesTarget(target)),
                        () ->
                            com.deepseekharness.app.vscreen.VirtualScreenManager.from(ctx)
                                .bridge(request));
            if (!live.getAsBoolean()
                || target != null
                    && !operation.equals("launch")
                    && !DshaAccessibilityService.matchesTarget(target))
              return "{\"ok\":false,\"error\":\"SCREEN_TARGET_CHANGED\"}";
            return result;
          };
      return target != null && !operation.equals("launch")
          ? DshaAccessibilityService.runAuthorized(target, live, action)
          : action.get();
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
  private static final int BRIDGE_PROTOCOL = 3;

  /**
   * {@code /app/version}：桥协议与 App 版本，给插件做特性检测。
   *
   * <p>没有这个端点时，插件只能靠「试着调一下看会不会 404」来猜 App 的能力，
   * 而 dsh 与 DSHA 是各自升级的 —— 用户完全可能拿新插件配旧 App。
   */
  private String appVersion() {
    return com.deepseekharness.app.util.UiText.format(
        "BRIDGE_PROTOCOL=%s\nBRIDGE_AUTH=header-x-token\nAPP_VERSION=%s\nAPP_CODE=%s\nHINT=端点清单见 /app/help；判版本请用 >= 而不是 ==\n",
        BRIDGE_PROTOCOL, BuildConfig.VERSION_NAME, BuildConfig.VERSION_CODE);
  }

  /** /app/help lists bridge endpoint parameters for authenticated clients. */
  private String appHelp() {
    return com.deepseekharness.app.util.UiText.format(
        "DSHA 3090 桥端点清单（BRIDGE_PROTOCOL=%s）\n凭据由宿主写入 /root/.dsh/.bridge_headers；使用 curl -H @/root/.dsh/.bridge_headers，不把 token 写进 URL 或命令参数。\n带中文/空格的参数一律用 -G --data-urlencode，别手写 URL 编码。\n\n== 屏幕操作（无障碍服务，不需要 ADB/Shizuku）==\n读屏  curl -s -H @/root/.dsh/.bridge_headers \"127.0.0.1:3090/app/ui/dump\"\n      → 每行「[序号] \"文字\" 可点击 中心=(x,y) 区域=l,t,r,b」\n点按  curl -s -G 127.0.0.1:3090/app/ui/tap --data-urlencode \"text=设置\" -H @/root/.dsh/.bridge_headers\n      → 优先按文字点：控件位置随滚动/动画变，文字不变。没有文字才用 ?x=&y=\n输入  curl -s -G 127.0.0.1:3090/app/ui/input --data-urlencode \"text=内容\" -H @/root/.dsh/.bridge_headers\n      → 填到当前焦点框；没有焦点先 tap 一下输入框\n按键  /app/ui/key?name=back  （back/home/recents/notifications/quicksettings/lock）\n滑动  /app/ui/swipe?x1=500&y1=1500&x2=500&y2=500&ms=300\n截屏  /app/ui/screenshot   → 存 PNG 到应用截图目录并返回路径（不回 base64）\n节奏：每次点按/输入后先 dump 再决定下一步，别凭记忆连点。\n\n== 独立虚拟屏（Android 11+；坐标与文本输入必须带“刚看过的那一帧”）==\n/app/vscreen/create?orientation=portrait|landscape  创建虚拟屏\n/app/vscreen/status  查询 displayId、尺寸和 frameSeq（只读；它的 frameSeq 不能直接用于输入）\n/app/vscreen/launch?package=com.example.app  启动已安装应用\n/app/vscreen/see  取最新预览（可带 since=<帧号> 去重）；输入要带的是它返回的 frameSeq\n/app/vscreen/tap?x=&y=&frameSeq=&generation=  按坐标点按\n/app/vscreen/swipe?x1=&y1=&x2=&y2=&ms=&frameSeq=&generation=  滑动\n/app/vscreen/touch?stroke=&action=0..3&x=&y=&frameSeq=&generation=  连续触控（stroke 为 16–64 位十六进制）\n/app/vscreen/type?text=&frameSeq=&generation=  输入文本（中文需开启无障碍）\n/app/vscreen/key?keycode=3|4|66|67&frameSeq=&generation=  系统按键\n/app/vscreen/tree  读虚拟屏控件树；返回的 generation 与 frameSeq 供 node 使用\n/app/vscreen/node  POST JSON {generation,nodeId,action,text?}  按 nodeId 做语义操作（click/long_click/scroll_forward/scroll_backward/focus/set_text）；与帧号无关，不需要 frameSeq，nodeId 用一次即失效\n/app/vscreen/editor|edit|submit  POST JSON {generation,editorId,text?,start?,end?}  读写当前焦点输入框\n/app/vscreen/close  关闭并回收虚拟屏\n帧门：坐标与文本输入要求“刚 see 过的那一帧仍是最新帧”。帧间隔短于一次 see→输入的往返时它必然过期（约 20–60fps 的界面基本必失败，1fps 那种慢速重绘仍可能成功），返回 STALE_FRAME 并带 reason：FRAME_NOT_OBSERVED（没 see 过这一帧）/ FRAME_EXPIRED（已被新帧顶掉）/ OBSERVATION_TIMEOUT（超过 30 秒）。先 see 再输入；动态界面请改用 tree + node（不受帧率影响，也不用回传图片）。\n空屏时 frameSeq 恒为 0、see 返回 FRAME_NOT_READY —— 那是“屏上还没有内容”，不是创建失败。\n创建失败：VSCREEN_TRUSTED_DISPLAY_DENIED（该通道拿不到 ADD_TRUSTED_DISPLAY，请改用 Root 或无障碍通道）/ VSCREEN_DISPLAY_CREATE_FAILED / VSCREEN_PERMISSION_DENIED。\n等待授权不等于被拒绝：确认没等到回答返回 USER_NO_ANSWER（等用户点“允许”后再试一次），用户明确拒绝才是 USER_REJECTED，确认通道不可用返回 USER_CONFIRM_UNAVAILABLE。\n\n== 设备与应用 ==\n/app/device                     机型/系统/电量/网络/屏幕/存储/内存\n/app/apps                       全部已装应用，分用户应用与系统应用；可加 q/limit/user=1 筛选显示\n/app/launch?pkg=com.tencent.mm  启动应用\n/app/clip                       读剪贴板（需 App 在前台，系统限制）\n/app/clip + text=…              写剪贴板\n/app/readfile?path=/…          读取绝对路径的目录或文本，仍受 Android 权限限制；凭据区（.dsh/.ssh/.android）不可读\n设备文件写入请走下方受保护的设备 shell；普通 Download 文件可操作，DCIM/Pictures/Android/data/obb 只读。\n\n== 与用户交互 ==\n/app/ask?options=继续|取消 + q=…  弹窗阻塞等回答（最多三个选项）\n/app/notify?title=… + text=…      通知栏\n/app/toast + text=…               App 内提示\n/app/vibrate?ms=300               震动（长任务跑完叫醒用户）\n/app/share（text= 或 path=）      分享到其它应用\n/app/open?url=https://…           打开链接\n/app/export?path=/root/report.md  把产物交给用户 → 落 Download/DSHA；凭据区不可导出\n建议：需要用户拍板用 /app/ask 而不是干等；长任务结束用 notify 或 vibrate 叫人；\n产出报告用 /app/export，别只留在容器里。\n\n== 传感器与位置（默认关闭，需用户在配置页勾选）==\n/app/location（加 fresh=1 强制重新定位，可能等数秒）\n/app/sensors 列表 · /app/sensor?name=light 读值\n（light 环境光 lux / accel / gyro / magnet / pressure / proximity /\n gravity / rotation 姿态四元数 / steps 开机后步数）\n/app/torch?on=1 手电\n这三类返回 DISABLED（用户没开该能力）或 NO_PERMISSION（没授系统权限）时，\n照原话告诉用户去哪开，不要重试 —— 重试不会让开关自己变。\n\n== 元信息 ==\n/app/version                          桥协议版本 + App 版本（特性检测用）\n/app/help                             本清单\n\n== 插件状态 ==\n/app/plugins                          读回上次上报的加载状态\n/app/plugins?loaded=a,b&failed=c      上报（插件侧用）\n\n== 设备 shell（自动选择 root / Shizuku / ADB）==\n/root/dsh-bin/adb-shell \"命令\"        用 id 核验实际身份\n包装命令不存在时：python3 /root/.dsh/adb-shell.py \"命令\"\n短信只允许当前 Android 用户的 content query --uri content://sms，默认关闭；设置 → 设备能力授权可开启或撤销。\n短信预授权可能返回正文及验证码；不允许发送、修改或删除，Android 仍可拒绝访问。Shizuku 不执行此敏感查询。\n仅执行已识别的单条命令；允许读取各目录和明确路径的普通文件操作。禁止脚本、管道、重定向、未知命令。\n根目录及系统目录只读；禁止块设备/分区、SELinux、系统设置写入、挂载和刷机操作。\n结束进程前自动刷新全量用户/系统应用清单；普通用户应用直接结束，系统应用与关键进程拦截。\n按完整包名调用 am force-stop / killall / pkill -x；kill 正数 PID 会核对 UID 后按包名停止。\n策略拦截返回 [POLICY_BLOCKED]/126；root 和旧确认开关不能放行。不可用其它解释器或 UI 绕过。\n报连不上/未配对：先看上面的 App 层接口能不能办成；确实必须 shell 才请用户到\n设置 → 设备能力授权中连接可用通道，别反复试同一条命令。\n不要用 /root/dsh-bin/adb 或裸 adb —— 那是守卫包装脚本，会失败。\n\n== root（--su）==\n已启用并经 root 管理器授权的 su 可直接执行，无需 ADB 配对；其次使用 Shizuku，再使用 ADB。\n--su 仅在明确需要 root 时使用，须先在设备能力授权页允许；同一设备保护策略始终生效。\n[EXECUTION_UNKNOWN] 表示命令可能已执行，先核对实际状态，不能切换通道或自动重放。\n",
        BRIDGE_PROTOCOL);
  }

  /** /app/plugins records actual startup loading observations; registration alone is not readiness. */
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
        return "LOADED:"
            + sp.getString("plugin_loaded", "")
            + "\nFAILED:"
            + sp.getString("plugin_failed", "")
            + "\nPENDING:"
            + sp.getString("plugin_pending", "")
            + "\nAT:"
            + sp.getLong("plugin_report_ts", 0L);
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
          HarnessController.get(ctx)
              .logActivity(com.deepseekharness.app.util.UiText.format("插件加载失败：%s", safeFailed));
        } catch (Throwable ignored) {
        }
      }
      return "OK";
    } catch (Throwable e) {
      return "ERROR: " + safeError(e);
    }
  }

  /** /app/overlay publishes caller text to the authorized overlay and reports its availability. */
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

  /** /app/toast displays caller-provided text without translating it. */
  private String appToast(String path) {
    try {
      final String text = getParam(queryOf(path), "text", "");
      if (text.isEmpty()) return "NO_TEXT";
      final String displayText = safeDisplay(text);
      new Handler(Looper.getMainLooper())
          .post(
              () -> {
                try {
                  android.widget.Toast.makeText(ctx, displayText, android.widget.Toast.LENGTH_LONG)
                      .show();
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
        return com.deepseekharness.app.util.UiText.format("FORBIDDEN: 路径无法解析（%s）", p);
      }
      // 复核 canonical：软链接不能成为读凭据的跳板。
      if (exportDeniedByCanonical(f))
        return "FORBIDDEN: " + com.deepseekharness.app.util.BridgePathPolicy.reason();
      // canon 之后用于目录遍历的越界复核，避免同一路径被解析两遍产生竞态。
      if (canon == null || canon.isEmpty())
        return com.deepseekharness.app.util.UiText.format("FORBIDDEN: 路径无法解析（%s）", p);
      // 按用户策略放开可读目录；实际权限仍由 Android 执行，不把拒绝伪装成成功。
      var fs = new com.deepseekharness.app.backup.AndroidBackupFileSystem();
      java.io.File resolved = new java.io.File(canon);
      var identity = fs.stat(resolved);
      if (identity.type.equals("DIRECTORY")) {
        java.util.List<String> children = fs.list(resolved);
        StringBuilder listing = new StringBuilder();
        for (String entry : children) {
          java.io.File child = fs.child(resolved, entry);
          if (listing.length() > 250000) {
            listing.append("[OUTPUT_TRUNCATED]\n");
            break;
          }
          // 列出上层目录时也不能暴露凭据区条目（名字本身就是情报）。
          if (com.deepseekharness.app.util.BridgePathPolicy.denied(child.getPath())
              || exportDeniedByCanonical(child)) continue;
          listing
              .append(fs.stat(child).type.equals("DIRECTORY") ? "d\t" : "f\t")
              .append(child.getName())
              .append('\n');
        }
        if (!identity.same(fs.stat(resolved))) return "[ERR] SOURCE_CHANGED";
        return listing.toString();
      }
      if (!identity.type.equals("FILE"))
        return com.deepseekharness.app.util.UiText.format(
            "NOT_FOUND_OR_NO_PERMISSION: 路径不存在或 Android 未授予读取权限：%s", p);
      if (identity.size > 256 * 1024) return "TOO_LARGE: " + identity.size;
      java.io.ByteArrayOutputStream bytes = new java.io.ByteArrayOutputStream();
      try (java.io.InputStream in = fs.read(resolved, identity)) {
        byte[] buffer = new byte[8192];
        int count;
        while ((count = in.read(buffer)) != -1) {
          if (bytes.size() + count > 256 * 1024)
            return com.deepseekharness.app.util.UiText.text("TOO_LARGE: 内容超过 256 KiB");
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
      sb.append("android=")
          .append(Build.VERSION.RELEASE)
          .append(" (SDK ")
          .append(Build.VERSION.SDK_INT)
          .append(")\n");
      try {
        android.os.BatteryManager bm =
            (android.os.BatteryManager) ctx.getSystemService(Context.BATTERY_SERVICE);
        android.content.Intent st =
            ctx.registerReceiver(
                null,
                new android.content.IntentFilter(android.content.Intent.ACTION_BATTERY_CHANGED));
        int status = st == null ? -1 : st.getIntExtra(android.os.BatteryManager.EXTRA_STATUS, -1);
        boolean charging =
            status == android.os.BatteryManager.BATTERY_STATUS_CHARGING
                || status == android.os.BatteryManager.BATTERY_STATUS_FULL;
        int level =
            bm == null
                ? -1
                : bm.getIntProperty(android.os.BatteryManager.BATTERY_PROPERTY_CAPACITY);
        sb.append("battery=").append(level).append("% charging=").append(charging).append('\n');
      } catch (Throwable ignored) {
      }
      try {
        android.net.ConnectivityManager cm =
            (android.net.ConnectivityManager) ctx.getSystemService(Context.CONNECTIVITY_SERVICE);
        String net = "none";
        if (cm != null) {
          android.net.Network n = cm.getActiveNetwork();
          android.net.NetworkCapabilities nc = n == null ? null : cm.getNetworkCapabilities(n);
          if (nc != null) {
            if (nc.hasTransport(android.net.NetworkCapabilities.TRANSPORT_WIFI)) net = "wifi";
            else if (nc.hasTransport(android.net.NetworkCapabilities.TRANSPORT_CELLULAR))
              net = "cellular";
            else if (nc.hasTransport(android.net.NetworkCapabilities.TRANSPORT_ETHERNET))
              net = "ethernet";
            else net = "other";
          }
        }
        sb.append("network=").append(net).append('\n');
      } catch (Throwable ignored) {
      }
      try {
        android.os.PowerManager pm =
            (android.os.PowerManager) ctx.getSystemService(Context.POWER_SERVICE);
        sb.append("screen=").append(pm != null && pm.isInteractive() ? "on" : "off").append('\n');
      } catch (Throwable ignored) {
      }
      sb.append("app_foreground=").append(ForegroundActivity.current() != null).append('\n');
      try {
        android.os.StatFs fs =
            new android.os.StatFs(android.os.Environment.getExternalStorageDirectory().getPath());
        long free = fs.getAvailableBytes(), total = fs.getTotalBytes();
        sb.append("storage_free=")
            .append(HarnessController.fmtBytes(free))
            .append(" total=")
            .append(HarnessController.fmtBytes(total))
            .append('\n');
      } catch (Throwable ignored) {
      }
      try {
        android.app.ActivityManager am =
            (android.app.ActivityManager) ctx.getSystemService(Context.ACTIVITY_SERVICE);
        android.app.ActivityManager.MemoryInfo mi = new android.app.ActivityManager.MemoryInfo();
        if (am != null) {
          am.getMemoryInfo(mi);
          sb.append("memory_free=")
              .append(HarnessController.fmtBytes(mi.availMem))
              .append(" total=")
              .append(HarnessController.fmtBytes(mi.totalMem))
              .append('\n');
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
      String q = getParam(queryOf(path), "q", "").toLowerCase(java.util.Locale.ROOT);
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
      for (boolean system : new boolean[] {false, true}) {
        if (system && userOnly) continue;
        sb.append(
            system
                ? com.deepseekharness.app.util.UiText.text("[系统应用]\n")
                : com.deepseekharness.app.util.UiText.text("[用户应用]\n"));
        for (int index = 0; index < inventory.entries.length(); index++) {
          org.json.JSONObject app = inventory.entries.getJSONObject(index);
          if (app.getBoolean("system") != system) continue;
          String name = app.getString("name"), label = app.getString("label");
          if (!q.isEmpty()
              && !name.toLowerCase(java.util.Locale.ROOT).contains(q)
              && !label.toLowerCase(java.util.Locale.ROOT).contains(q)) continue;
          if (n >= limit) break;
          sb.append(name)
              .append('\t')
              .append(label)
              .append(" uid=")
              .append(app.getInt("uid"))
              .append('\n');
          n++;
        }
      }
      return sb.append(
              com.deepseekharness.app.util.UiText.format(
                  "显示 %d 个；Android 返回 %d 个可见应用。系统可能限制可见范围；结束应用前会另行核对完整清单。",
                  n, inventory.entries.length()))
          .toString();
    } catch (Throwable e) {
      return com.deepseekharness.app.util.UiText.format(
          "[APP_LIST_UNAVAILABLE] Android 未提供完整应用清单；设备停止操作会通过 ADB/Shizuku 重新读取。可用设备命令分别查询 pm list packages -U -3 和 pm list packages -U -s。原因：%s",
          safeError(e));
    }
  }

  /** 应用动作的授权仍由本轮桥持有，平台操作交给具体协作者。 */
  private String appLaunch(String path) {
    var controller = HarnessController.get(ctx);
    long generation = controller.getWebGeneration(), revision = uiGrant.revision();
    return com.deepseekharness.app.bridge.AppDeviceActions.launch(
        ctx,
        getParam(queryOf(path), "pkg", ""),
        this::uiAuthorized,
        () ->
            generation == controller.getWebGeneration()
                && revision == uiGrant.revision()
                && !controller.isStopping()
                && !controller.isUserStopped());
  }

  private String appClip(String path) {
    return com.deepseekharness.app.bridge.AppDeviceActions.clipboard(
        ctx, mainHandler, getParam(queryOf(path), "text", ""));
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
        if (!com.deepseekharness.app.util.BridgePathPolicy.startsWithPath(canon, "/sdcard")
            && !canon.matches("^/storage/emulated/[0-9]+/.*")) {
          return com.deepseekharness.app.util.UiText.text("FORBIDDEN: 只能分享 /sdcard 下的文件");
        }
        if (com.deepseekharness.app.util.BridgePathPolicy.denied(file)
            || exportDeniedByCanonical(f))
          return "FORBIDDEN: " + com.deepseekharness.app.util.BridgePathPolicy.reason();
        var fs = new com.deepseekharness.app.backup.AndroidBackupFileSystem();
        java.io.File cache = ctx.getCacheDir().getCanonicalFile();
        java.io.File shares = fs.child(cache, "bridge-shares");
        if (fs.stat(shares).type.equals("MISSING")) fs.directory(shares);
        java.io.File owned = fs.child(shares, java.util.UUID.randomUUID().toString());
        fs.directory(owned);
        java.io.File snapshot =
            fs.child(owned, com.deepseekharness.app.util.WebTransferPolicy.fileName(f.getName()));
        com.deepseekharness.app.util.BridgeFileSnapshot.copy(
            fs,
            new java.io.File(canon),
            snapshot,
            com.deepseekharness.app.util.WebTransferPolicy.DOWNLOAD_LIMIT);
        android.net.Uri uri =
            androidx.core.content.FileProvider.getUriForFile(
                ctx, ctx.getPackageName() + ".updates", snapshot);
        send.setType(
            com.deepseekharness.app.util.BackupFileNames.exportMimeType(snapshot.getName()));
        send.putExtra(android.content.Intent.EXTRA_STREAM, uri);
        send.setClipData(android.content.ClipData.newRawUri("DSHA shared file", uri));
        send.addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION);
        if (!text.isEmpty()) send.putExtra(android.content.Intent.EXTRA_TEXT, text);
      } else {
        if (text.isEmpty()) return "NO_CONTENT";
        send.setType("text/plain");
        send.putExtra(android.content.Intent.EXTRA_TEXT, text);
      }
      android.content.Intent chooser =
          android.content.Intent.createChooser(
              send, com.deepseekharness.app.util.UiText.text("分享"));
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
      String low = url.toLowerCase(java.util.Locale.ROOT);
      // 只放行常见安全 scheme：file:// 会把 App 私有文件暴露给任意应用
      if (!low.startsWith("http://")
          && !low.startsWith("https://")
          && !low.startsWith("geo:")
          && !low.startsWith("tel:")
          && !low.startsWith("mailto:")
          && !low.startsWith("market://")) {
        return com.deepseekharness.app.util.UiText.text(
            "FORBIDDEN: 只支持 http/https/geo/tel/mailto/market 链接");
      }
      android.content.Intent i =
          new android.content.Intent(
              android.content.Intent.ACTION_VIEW, android.net.Uri.parse(url));
      i.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK);
      ctx.startActivity(i);
      return com.deepseekharness.app.util.UiText.format("OK: 已打开 %s", safeDisplay(url));
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
        v.vibrate(
            android.os.VibrationEffect.createOneShot(
                ms, android.os.VibrationEffect.DEFAULT_AMPLITUDE));
      } else {
        // Android 6：VibrationEffect 是 API 26，退回旧式 vibrate(ms)
        v.vibrate(ms);
      }
      return com.deepseekharness.app.util.UiText.format("OK: 震动 %sms", ms);
    } catch (Throwable e) {
      return "ERROR: " + safeError(e);
    }
  }

  /** /app/ask?q=问题&options=选项A|选项B|选项C ：弹窗问用户，阻塞等回答（最多 3 个选项，120 秒超时） */
  private String appAsk(String path) {
    return appAsk(path, 120_000, fixtureAskObserver);
  }

  /** 测试可缩短期限，但必须复用生产的前台宿主选择，不能注入一个永远前台的 Activity。 */
  String appAsk(
      String path, long timeoutMillis, java.util.function.Consumer<BridgeAskDialog> created) {
    return appAsk(path, timeoutMillis, ForegroundActivity.current(), created);
  }

  /** 包内注入窗口宿主与较短期限；不向 HTTP 参数开放这些测试控制。 */
  String appAsk(
      String path,
      long timeoutMillis,
      androidx.fragment.app.FragmentActivity act,
      java.util.function.Consumer<BridgeAskDialog> created) {
    if (timeoutMillis <= 0 || timeoutMillis > 120_000)
      throw new IllegalArgumentException(com.deepseekharness.app.util.UiText.text("无效的提问期限"));
    String q = getParam(queryOf(path), "q", "");
    String optRaw = getParam(queryOf(path), "options", "");
    if (q.isEmpty()) return "NO_QUESTION";
    if (act == null) {
      return com.deepseekharness.app.util.UiText.text(
          "[APP_BACKGROUND] App 不在前台，弹不出提问 —— 可先 /app/notify 提醒用户打开 DSHA");
    }
    String[] parts =
        optRaw.isEmpty()
            ? new String[] {com.deepseekharness.app.util.UiText.text("好")}
            : optRaw.split("\\|", -1);
    final String[] opts = parts.length <= 3 ? parts : new String[] {parts[0], parts[1], parts[2]};
    final String displayQuestion = safeDisplay(q);
    final String[] displayOptions = new String[opts.length];
    for (int i = 0; i < opts.length; i++) displayOptions[i] = safeDisplay(opts[i]);
    final BridgeQuestions.Request request;
    synchronized (LIFECYCLE) {
      if (!running || instance != this)
        return com.deepseekharness.app.util.UiText.text("[STOPPED] 设备桥已停止，请稍后重试");
      request = questions.begin(timeoutMillis);
    }
    if (request == null) return com.deepseekharness.app.util.UiText.text("[BUSY] 已有一个提问在等用户回答");
    BridgeAskDialog dialog = new BridgeAskDialog(act, questions, request);
    try {
      if (created != null) created.accept(dialog);
      dialog.show(displayQuestion, opts, displayOptions);
      switch (questions.await(request)) {
        case ANSWER:
          return request.answer();
        case TIMEOUT:
          return com.deepseekharness.app.util.UiText.text("[TIMEOUT] 用户在提问期限内没有回答");
        case DISMISSED:
          return com.deepseekharness.app.util.UiText.text("[DISMISSED] 用户关掉了提问框");
        case BACKGROUND:
          return com.deepseekharness.app.util.UiText.text(
              "[APP_BACKGROUND] 页面已离开或重建，请回到 DSHA 后重新提问");
        case STOPPED:
          return com.deepseekharness.app.util.UiText.text("[STOPPED] 设备桥已停止，请稍后重试");
        default:
          return com.deepseekharness.app.util.UiText.text("[UNAVAILABLE] 提问窗口已关闭或无法显示，请重新提问");
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
          java.io.File guess =
              new java.io.File(
                  hc.getProot().getRootfsDir(), src.startsWith("/") ? src.substring(1) : src);
          if (guess.isFile()) f = guess;
        } catch (Throwable ignored) {
        }
      }
      if (!f.isFile()) return "NOT_FOUND: " + SensitiveData.redact(src);
      // 字符串判据之后再核 canonical：挡住「先建软链接指向凭据」的绕法。
      if (exportDeniedByCanonical(f))
        return "FORBIDDEN: " + com.deepseekharness.app.util.BridgePathPolicy.reason();
      if (f.length() > 64L * 1024 * 1024) return "TOO_LARGE: " + f.length();
      if (name.isEmpty()) name = f.getName();
      if (name.contains("/") || name.contains("..")) return "BAD_NAME";
      var fs = new com.deepseekharness.app.backup.AndroidBackupFileSystem();
      java.io.File cache = ctx.getCacheDir().getCanonicalFile(),
          copies = fs.child(cache, "bridge-exports");
      if (fs.stat(copies).type.equals("MISSING")) fs.directory(copies);
      java.io.File owned = fs.child(copies, java.util.UUID.randomUUID().toString());
      fs.directory(owned);
      java.io.File snapshot = fs.child(owned, "payload");
      com.deepseekharness.app.util.BridgeFileSnapshot.copy(
          fs, f.getCanonicalFile(), snapshot, 64L * 1024 * 1024);
      var copied = fs.stat(snapshot);
      String out;
      try {
        out = BackupManager.exportToDownloads(ctx, snapshot, name);
      } finally {
        if (copied.same(fs.stat(snapshot))) {
          fs.delete(snapshot);
          if (fs.list(owned).isEmpty()) fs.delete(owned);
        }
      }
      return out == null
          ? com.deepseekharness.app.util.UiText.text("ERROR: 导出失败（存储权限或空间不足）")
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

  /** 确认结果三态：允许 / 用户明确拒绝 / 没等到回答（超时、窗口关闭、代次失效、桥停止）。 */
  private enum ConfirmationOutcome {
    ALLOWED,
    DENIED,
    NO_ANSWER,
    UNAVAILABLE
  }

  /** 只请求用户确认（不执行命令）。
   *  通知与弹窗同时发：只走弹窗的话，Activity 一被 pause 用户就再也看不见，
   *  只能干等 60s 超时——这正是「弹窗有时不出现」的由来。（吸收上游 PR#24）
   *  返回三态结果：桥要用它区分「用户还没点允许」与「用户拒绝了」（issue #123）。 */
  private ConfirmationOutcome confirmOutcome(String cmd) {
    final PendingConfirmation pending;
    synchronized (LIFECYCLE) {
      BridgeRun run = activeRun;
      if (!running
          || run == null
          || !LIFECYCLE.isCurrent(run.generation)
          || requestRun.get() != null && requestRun.get() != run)
        return ConfirmationOutcome.UNAVAILABLE;
      var request = confirmations.begin(run.generation);
      if (request == null) return ConfirmationOutcome.UNAVAILABLE;
      pending = new PendingConfirmation(request);
      pendingConfirm = pending;
    }
    try {
      synchronized (LIFECYCLE) {
        if (!currentConfirmation(pending)) return ConfirmationOutcome.UNAVAILABLE;
        showConfirmNotification(cmd, pending);
        OverlayController.askConfirm(
            ctx,
            pending,
            safeDisplay(cmd),
            () -> resolveConfirm(true, pending.request.identity),
            () -> resolveConfirm(false, pending.request.identity));
      }
      final androidx.fragment.app.FragmentActivity act = ForegroundActivity.current();
      if (act != null) {
        final String prompt =
            com.deepseekharness.app.util.UiText.format(
                "模型试图在设备上执行：\n%s\n\n是否允许？", safeDisplay(cmd));
        act.runOnUiThread(
            () -> {
              synchronized (LIFECYCLE) {
                try {
                  if (!ForegroundActivity.isResumed(act) || !currentConfirmation(pending)) return;
                  pending.dialog =
                      new com.deepseekharness.app.ui.DshaDialogBuilder(act)
                          .setTitle(com.deepseekharness.app.util.UiText.text("DSHA 安全确认"))
                          .setMessage(prompt)
                          .setCancelable(false)
                          .setPositiveButton(
                              com.deepseekharness.app.util.UiText.text("允许"),
                              (d, w) -> resolveConfirm(true, pending.request.identity))
                          .setNegativeButton(
                              com.deepseekharness.app.util.UiText.text("拒绝"),
                              (d, w) -> resolveConfirm(false, pending.request.identity))
                          .show();
                } catch (Throwable t) {
                  android.util.Log.w(
                      "DSHA",
                      com.deepseekharness.app.util.UiText.format(
                          "确认弹窗弹出失败，仍可从通知确认：%s", safeError(t)));
                }
              }
            });
      } else if (!notificationsEnabled()) {
        android.util.Log.w(
            "DSHA",
            com.deepseekharness.app.util.UiText.format(
                "无前台界面且通知权限被拒，确认必然超时拒绝：%s", safeDisplay(cmd)));
      }
      boolean allowed = pending.request.await(CONFIRM_TIMEOUT_S, TimeUnit.SECONDS);
      synchronized (LIFECYCLE) {
        BridgeRun run = activeRun;
        if (!(running
            && run != null
            && run.generation == pending.request.generation
            && LIFECYCLE.isCurrent(run.generation))) return ConfirmationOutcome.UNAVAILABLE;
        if (allowed) return ConfirmationOutcome.ALLOWED;
        // await 返回 false 只说明"期限内没等到"；用户的选择必须单独读 —— 否则迟到的「允许」
        // 会被报成「拒绝」，正是 issue #123 要消灭的那种误判。
        Boolean answer = pending.request.answer();
        if (answer == null) return ConfirmationOutcome.NO_ANSWER;
        return answer ? ConfirmationOutcome.ALLOWED : ConfirmationOutcome.DENIED;
      }
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      return ConfirmationOutcome.UNAVAILABLE;
    } finally {
      synchronized (LIFECYCLE) {
        finishConfirmation(pending);
      }
    }
  }

  private boolean currentConfirmation(PendingConfirmation pending) {
    BridgeRun run = activeRun;
    return running
        && run != null
        && pendingConfirm == pending
        && run.generation == pending.request.generation
        && LIFECYCLE.isCurrent(run.generation)
        && confirmations.pending(pending.request);
  }

  private boolean notificationsEnabled() {
    try {
      NotificationManager nm =
          (NotificationManager) ctx.getSystemService(Context.NOTIFICATION_SERVICE);
      // framework API 24+，比运行时权限检查更准（用户在设置里关掉通知也算）
      return nm == null
          || androidx.core.app.NotificationManagerCompat.from(ctx).areNotificationsEnabled();
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
    if (pending.allow != null) {
      try {
        pending.allow.cancel();
      } catch (RuntimeException ignored) {
      }
      pending.allow = null;
    }
    if (pending.deny != null) {
      try {
        pending.deny.cancel();
      } catch (RuntimeException ignored) {
      }
      pending.deny = null;
    }
    try {
      NotificationManager nm =
          (NotificationManager) ctx.getSystemService(Context.NOTIFICATION_SERVICE);
      if (nm != null) nm.cancel(CONFIRM_NOTIF_ID);
    } catch (RuntimeException ignored) {
    }
    final androidx.appcompat.app.AlertDialog dialog = pending.dialog;
    pending.dialog = null;
    try {
      if (dialog != null)
        mainHandler.post(
            () -> {
              try {
                if (dialog.isShowing()) dialog.dismiss();
              } catch (Throwable ignored) {
              }
            });
      OverlayController.dismissConfirm(ctx, pending);
    } catch (RuntimeException ignored) {
    }
  }

  private void showConfirmNotification(String cmd, PendingConfirmation pending) {
    createConfirmChannel();
    String displayCmd = safeDisplay(cmd);
    String shortCmd = displayCmd.length() > 100 ? displayCmd.substring(0, 100) + "…" : displayCmd;
    // data 参与 PendingIntent 身份比较，旧通知持有者不会被 UPDATE_CURRENT 更新为新请求。
    android.net.Uri identity = android.net.Uri.parse(pending.request.identity);
    Intent allowI =
        new Intent(ctx, ConfirmReceiver.class)
            .setAction(ConfirmReceiver.ACTION_ALLOW)
            .setData(identity);
    Intent denyI =
        new Intent(ctx, ConfirmReceiver.class)
            .setAction(ConfirmReceiver.ACTION_DENY)
            .setData(identity);
    pending.allow =
        PendingIntent.getBroadcast(
            ctx, 31, allowI, PendingIntent.FLAG_ONE_SHOT | PendingIntent.FLAG_IMMUTABLE);
    pending.deny =
        PendingIntent.getBroadcast(
            ctx, 32, denyI, PendingIntent.FLAG_ONE_SHOT | PendingIntent.FLAG_IMMUTABLE);
    Notification n =
        new NotificationCompat.Builder(ctx, CONFIRM_CHANNEL)
            .setSmallIcon(R.drawable.ic_launch)
            .setContentTitle(com.deepseekharness.app.util.UiText.text("⚠️ DSHA 安全确认"))
            .setContentText(com.deepseekharness.app.util.UiText.format("模型试图执行：%s", shortCmd))
            .setStyle(
                new NotificationCompat.BigTextStyle()
                    .bigText(
                        com.deepseekharness.app.util.UiText.format(
                            "模型试图在设备上执行：\n%s\n\n是否允许？", displayCmd)))
            .addAction(0, com.deepseekharness.app.util.UiText.text("允许"), pending.allow)
            .addAction(0, com.deepseekharness.app.util.UiText.text("拒绝"), pending.deny)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setContentIntent(agentNotificationIntent())
            .setOngoing(true)
            .build();
    NotificationManager nm =
        (NotificationManager) ctx.getSystemService(Context.NOTIFICATION_SERVICE);
    if (nm != null) nm.notify(CONFIRM_NOTIF_ID, n);
  }

  private void createConfirmChannel() {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
      NotificationChannel ch =
          new NotificationChannel(
              CONFIRM_CHANNEL,
              com.deepseekharness.app.util.UiText.text("安全确认"),
              NotificationManager.IMPORTANCE_HIGH);
      ch.setDescription(com.deepseekharness.app.util.UiText.text("模型执行危险操作时的确认提醒"));
      NotificationManager nm =
          (NotificationManager) ctx.getSystemService(Context.NOTIFICATION_SERVICE);
      if (nm != null) nm.createNotificationChannel(ch);
    }
  }

  private static String jsonEscape(String s) {
    StringBuilder sb = new StringBuilder();
    for (char ch : s.toCharArray()) {
      switch (ch) {
        case '"':
          sb.append("\\\"");
          break;
        case '\\':
          sb.append("\\\\");
          break;
        case '\n':
          sb.append("\\n");
          break;
        case '\r':
          sb.append("\\r");
          break;
        case '\t':
          sb.append("\\t");
          break;
        default:
          if (ch < 0x20) sb.append(String.format("\\u%04x", (int) ch));
          else sb.append(ch);
      }
    }
    return sb.toString();
  }
}
