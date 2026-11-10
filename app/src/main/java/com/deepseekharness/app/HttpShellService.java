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
import com.deepseekharness.app.util.ScreenActionResult;
import com.deepseekharness.app.util.VirtualScreenRoutes;
import com.deepseekharness.app.util.VirtualScreenWindowPolicy;

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

  /**
   * 未鉴权失败的滑动窗口：60 秒内累计 30 次就短暂拒绝。
   *
   * <p>token 是 128 位随机值，暴力穷举本身不可行；这个闸门挡的是「把桥当作免费算力/日志
   * 放大器」的用法——每次失败都要走一遍路由匹配与日志，量级起来就是本地 DoS。
   */
  private static final class AuthFailureWindow {
    private static final long WINDOW_MS = 60_000L;
    private static final int LIMIT = 30;
    private long windowStart = System.currentTimeMillis();
    private int count;

    synchronized boolean allow(long now) {
      if (now - windowStart > WINDOW_MS) {
        windowStart = now;
        count = 0;
      }
      return ++count <= LIMIT;
    }
  }

  private static final AuthFailureWindow AUTH_FAILURES = new AuthFailureWindow();

  /** 测试夹具 rootfs：非空时替代 HarnessController 的真实 rootfs（仅包内测试通过反射设置）。 */
  private static java.io.File safRootfsFixture;

  /** 同时最多两个 SAF 大文件传输：每个请求都有 32 MiB 上限，避免 4 个 worker 一起放大内存。 */
  private static final java.util.concurrent.Semaphore SAF_TRANSFERS =
      new java.util.concurrent.Semaphore(2);

  /** 已发布的 IPv4 监听端口；未就绪时为 0。 */
  private volatile int bridgePort;

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
    final boolean screen;
    final com.deepseekharness.app.util.ScreenTarget target;
    final RequestScope scope;
    final com.deepseekharness.app.control.ControlTaskAuthority.Lease taskLease;
    com.deepseekharness.app.control.ScreenControlConfirmation.Prompt taskPrompt;
    volatile PendingIntent allow, deny;
    androidx.appcompat.app.AlertDialog dialog;
    boolean resolving, targetChanged;
    int promptWindow = -1;
    volatile boolean rememberTurn;

    PendingConfirmation(com.deepseekharness.app.util.BridgeConfirmations.Request request) {
      this(request, false, null, null, null);
    }

    PendingConfirmation(
        com.deepseekharness.app.util.BridgeConfirmations.Request request,
        boolean screen,
        com.deepseekharness.app.util.ScreenTarget target,
        RequestScope scope,
        com.deepseekharness.app.control.ControlTaskAuthority.Lease taskLease) {
      this.request = request;
      this.screen = screen;
      this.target = target;
      this.scope = scope;
      this.taskLease = taskLease;
    }
  }

  private final BridgeQuestions questions = new BridgeQuestions();
  private volatile boolean running;
  private volatile BridgeRun activeRun;
  private final ThreadLocal<BridgeRun> requestRun = new ThreadLocal<>();

  private static final class RequestScope {
    final BridgeRun run;
    final long screenRevision;
    final long consentRevision;
    final long webGeneration;
    final String requestId;
    volatile boolean canceled;
    volatile com.deepseekharness.app.capabilities.CapabilityRequest capability;

    RequestScope(
        BridgeRun run,
        long screenRevision,
        String requestId,
        long consentRevision,
        long webGeneration) {
      this.run = run;
      this.screenRevision = screenRevision;
      this.requestId = requestId;
      this.consentRevision = consentRevision;
      this.webGeneration = webGeneration;
    }
  }

  private interface RouteHandler {
    String handle(HttpShellService service, BridgeRequest request) throws Exception;
  }

  private static final class BridgeRequest {
    final String path, route, query, command;
    final boolean post;
    final com.deepseekharness.app.util.HttpProtocol.Head head;
    final java.io.InputStream input;
    final Socket socket;
    final RequestScope scope;

    BridgeRequest(
        String path,
        String route,
        String command,
        boolean post,
        com.deepseekharness.app.util.HttpProtocol.Head head,
        java.io.InputStream input,
        Socket socket,
        RequestScope scope) {
      this.scope = scope;
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
    routes.put(BridgeRoutes.Route.EXTDEV_STATUS, (s, r) -> s.externalDeviceStatus());
    routes.put(BridgeRoutes.Route.EXTDEV_DISCOVER, (s, r) -> s.externalDeviceDiscover());
    routes.put(
        BridgeRoutes.Route.EXTDEV_INSPECT,
        (s, r) -> s.externalDeviceInspect(r.param("serial", "")));
    routes.put(BridgeRoutes.Route.EXTDEV_BIND, (s, r) -> s.externalDeviceBind(r));
    routes.put(BridgeRoutes.Route.EXTDEV_OPERATE, (s, r) -> s.externalDeviceOperate(r));
    routes.put(BridgeRoutes.Route.EXTDEV_WATCH, (s, r) -> s.externalDeviceWatch(r));
    routes.put(BridgeRoutes.Route.EXTDEV_PREFLIGHT, (s, r) -> s.externalDevicePreflight(r));
    routes.put(BridgeRoutes.Route.EXTDEV_IMPORT, (s, r) -> s.externalDeviceImport(r));
    routes.put(BridgeRoutes.Route.EXTDEV_FORGET, (s, r) -> s.externalDeviceForget());
    routes.put(BridgeRoutes.Route.CONSENT_POLICY, (s, r) -> s.consentPolicy(r.scope));
    routes.put(BridgeRoutes.Route.NOTIFY, (s, r) -> s.appNotify(r.path));
    routes.put(
        BridgeRoutes.Route.TASK_CONTEXT, (s, r) -> s.appTask("context", r.param("payload", "")));
    routes.put(BridgeRoutes.Route.TASK_EVENT, (s, r) -> s.appTask("event", r.param("payload", "")));
    routes.put(BridgeRoutes.Route.TASK_CLAIM, (s, r) -> s.appTask("claim", r.param("payload", "")));
    routes.put(BridgeRoutes.Route.TASK_ACK, (s, r) -> s.appTask("ack", r.param("payload", "")));
    routes.put(BridgeRoutes.Route.TOAST, (s, r) -> s.appToast(r.path));
    routes.put(BridgeRoutes.Route.READ_FILE, (s, r) -> s.appReadFile(r.path));
    routes.put(BridgeRoutes.Route.SAF_TREES, (s, r) -> s.appSafTrees());
    routes.put(BridgeRoutes.Route.SAF_LS, (s, r) -> s.appSafList(r));
    routes.put(BridgeRoutes.Route.SAF_STAT, (s, r) -> s.appSafStat(r));
    routes.put(BridgeRoutes.Route.SAF_READ, (s, r) -> s.appSafRead(r));
    routes.put(BridgeRoutes.Route.SAF_WRITE, (s, r) -> s.appSafWrite(r));
    routes.put(BridgeRoutes.Route.SAF_MKDIR, (s, r) -> s.appSafMkdir(r));
    routes.put(BridgeRoutes.Route.SAF_RM, (s, r) -> s.appSafRemove(r));
    routes.put(BridgeRoutes.Route.SAF_PULL, (s, r) -> s.appSafPull(r));
    routes.put(BridgeRoutes.Route.SAF_PUSH, (s, r) -> s.appSafPush(r));
    routes.put(BridgeRoutes.Route.HEALTH, (s, r) -> "OK");
    routes.put(BridgeRoutes.Route.UI, (s, r) -> s.appUi(r.path, r.scope));
    routes.put(BridgeRoutes.Route.SENSE, (s, r) -> s.appSense(r.path, r.scope));
    routes.put(BridgeRoutes.Route.VSCREEN, (s, r) -> s.handleVscreen(r));
    routes.put(BridgeRoutes.Route.PRIV, (s, r) -> s.appPriv(r.path, r.scope));
    routes.put(BridgeRoutes.Route.DEVICE, (s, r) -> s.appDevice());
    routes.put(BridgeRoutes.Route.NET, (s, r) -> s.appNet());
    routes.put(
        BridgeRoutes.Route.NET_RESOLVE,
        (s, r) ->
            com.deepseekharness.app.device.HostResolver.resolve(
                s.ctx, r.param("host", ""), r.param("family", "0")));
    routes.put(BridgeRoutes.Route.APPS, (s, r) -> s.appList(r.path));
    routes.put(BridgeRoutes.Route.LAUNCH, (s, r) -> s.appLaunch(r.path, r.scope));
    routes.put(BridgeRoutes.Route.CLIP, (s, r) -> s.appClip(r.path, r.scope));
    routes.put(BridgeRoutes.Route.SHARE, (s, r) -> s.appShare(r.path));
    routes.put(BridgeRoutes.Route.INSTALL, (s, r) -> s.appInstall(r.path));
    routes.put(BridgeRoutes.Route.OPEN, (s, r) -> s.appOpen(r.path, r.scope));
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
    routes.put(BridgeRoutes.Route.ENTRY_TARGET, (s, r) -> s.appEntryTarget(r.path));
    routes.put(BridgeRoutes.Route.CONFIRM, (s, r) -> s.confirmReadOnly(r.command));
    routes.put(BridgeRoutes.Route.EXEC, (s, r) -> s.execOutput(r.command));
    return java.util.Collections.unmodifiableMap(routes);
  }

  /** 每一轮监听独立持有 socket/线程池，迟到的旧线程只能清理自己的资源。 */
  private static final class BridgeRun {
    final long generation;
    final String runId = java.util.UUID.randomUUID().toString();
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

    final java.util.concurrent.ExecutorService cancellationPool =
        new java.util.concurrent.ThreadPoolExecutor(
            4,
            4,
            0,
            TimeUnit.MILLISECONDS,
            new java.util.concurrent.ArrayBlockingQueue<>(20),
            task -> {
              Thread thread = new Thread(task, "bridge-screen-peer");
              thread.setDaemon(true);
              return thread;
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

  /** Actual bound port, or 0 when no IPv4 listener is ready. */
  public static int port() {
    synchronized (LIFECYCLE) {
      HttpShellService current = instance;
      return current == null || !current.running ? 0 : current.bridgePort;
    }
  }

  /** Exact physical-screen bridge connection, or null when its listener is retired. */
  public static String currentScreenConnection(Context context) {
    HttpShellService current;
    BridgeRun run;
    synchronized (LIFECYCLE) {
      if (!isReady()) return null;
      current = instance;
      run = current.activeRun;
      if (!LIFECYCLE.isCurrent(run.generation)) return null;
    }
    // Harness and bridge lifecycle locks have independent callers. Never nest
    // them merely to identify the ready connection.
    String runtime = com.deepseekharness.app.core.ScreenRuntimeIdentity.epoch(context).instanceId;
    synchronized (LIFECYCLE) {
      if (!isReady()
          || instance != current
          || current.activeRun != run
          || !LIFECYCLE.isCurrent(run.generation)) return null;
      return run.runId + ":" + runtime;
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

  private void noteBindOk(int port) {
    bindError = "";
    writeBridgeStatus("ok port=" + port);
  }

  private void noteBindError(String why) {
    String safe = safeDisplay(why);
    bindError = safe;
    android.util.Log.e("DSHA", com.deepseekharness.app.util.UiText.format("设备桥绑定失败：%s", safe));
    com.deepseekharness.app.core.DiagnosticLog.record(ctx, "BRIDGE_BIND", safe);
    clearBridgePort();
    writeBridgeStatus("fail " + safe);
  }

  /** Guest-side discovery: a 0600 machine-state file, not a credential. */
  private void writeBridgePort(int port) throws IOException {
    try {
      java.io.File tf = tokenFileIfPossible();
      if (tf == null || tf.getParentFile() == null)
        throw new IOException("BRIDGE_DATA_ROOT_UNAVAILABLE");
      java.io.File file =
          new java.io.File(
              tf.getParentFile(), com.deepseekharness.app.util.CredentialPaths.BRIDGE_PORT);
      com.deepseekharness.app.util.BridgeCredentialFiles.publishPort(
          new com.deepseekharness.app.backup.AndroidBackupFileSystem(), file, port);
    } catch (IOException | RuntimeException error) {
      throw new IOException("BRIDGE_PORT_FILE_FAILED", error);
    }
  }

  private void clearBridgePort() {
    try {
      java.io.File tf = tokenFileIfPossible();
      if (tf == null || tf.getParentFile() == null) return;
      java.io.File file =
          new java.io.File(
              tf.getParentFile(), com.deepseekharness.app.util.CredentialPaths.BRIDGE_PORT);
      var fs = new com.deepseekharness.app.backup.AndroidBackupFileSystem();
      if (fs.stat(file).type.equals("MISSING")) return;
      if (!fs.stat(file).type.equals("FILE")) throw new IOException("BRIDGE_PORT_FILE_TYPE");
      fs.delete(file);
      fs.syncDirectory(file.getParentFile());
    } catch (Throwable error) {
      android.util.Log.w("DSHA", "BRIDGE_PORT_DELETE_FAILED: " + safeError(error));
    }
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
        if (ensureToken().isEmpty()) throw new IOException("BRIDGE_TOKEN_UNAVAILABLE");
        // 绑定、状态发布与停止共用锁：旧启动线程不能在停止清理后补出一个孤立监听。
        run.server =
            com.deepseekharness.app.util.BridgePortPolicy.bind(
                com.deepseekharness.app.util.BridgePortPolicy.candidates(ctx.getPackageName()));
        int bound = run.server.getLocalPort();
        // 端口文件写入与读回失败时关闭本轮监听；guest 不可发现的桥不能报 ready。
        writeBridgePort(bound);
        if (!LIFECYCLE.publish(run.generation)) return;
        bridgePort = bound;
        running = true;
        instance = this;
        noteBindOk(bound);
        Thread t6 = new Thread(() -> serveIpv6(run), "http-shell-accept6");
        t6.setDaemon(true);
        t6.start();
      }
      acceptLoop(run, run.server);
    } catch (com.deepseekharness.app.util.BridgePortPolicy.Exhausted error) {
      failure =
          com.deepseekharness.app.util.UiText.format(
              "设备桥端口 %s 均被占用，设备工具暂不可用；Web 可独立启动。关闭占用端口的其他实例后重试：%s",
              com.deepseekharness.app.util.BridgePortPolicy.rangeLabel(), safeError(error));
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
        run.server6.setReuseAddress(true);
        run.server6.bind(
            new java.net.InetSocketAddress(java.net.InetAddress.getByName("::1"), bridgePort));
      }
      acceptLoop(run, run.server6);
    } catch (IOException | RuntimeException error) {
      if (activeRun == run && running)
        android.util.Log.i(
            "DSHA",
            com.deepseekharness.app.util.UiText.format("设备桥的 IPv6 附加监听不可用：%s", safeError(error)));
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
          RequestScope scope =
              new RequestScope(
                  run,
                  uiGrant.revision(),
                  java.util.UUID.randomUUID().toString(),
                  new com.deepseekharness.app.core.DeviceGrants(ctx).consentPolicy().revision(),
                  HarnessController.get(ctx).getWebGeneration());
          run.clients.add(client);
          run.pool.execute(
              () -> {
                requestRun.set(run);
                try {
                  handle(client, headerDeadline, scope);
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
      bridgePort = 0;
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
      run.cancellationPool.shutdownNow();
      questions.stop();
      if (pendingConfirm != null) finishConfirmation(pendingConfirm);
      if (failure == null) {
        clearBridgePort();
        writeBridgeStatus("stopped");
      } else noteBindError(failure);
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
  private void handle(Socket client, long headerDeadline, RequestScope scope) {
    com.deepseekharness.app.util.BridgeImageBudget.Lease imageLease = null;
    com.deepseekharness.app.util.BridgeImageWriteDeadline imageDeadline = null;
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
      // 同机任意应用都能连 127.0.0.1:3090，未鉴权请求必须有成本上限：
      // 一分钟内失败超过阈值就直接回 429 并断开，而不是让对端无限试。
      if (!authed && !AUTH_FAILURES.allow(System.currentTimeMillis())) {
        android.util.Log.w("HttpShellService", "鉴权失败过多，暂时拒绝 path=" + route);
        writeBridgeProtocolError(c, 429, "TOO_MANY_UNAUTHENTICATED");
        return;
      }
      // 预连接与未完成鉴权的 socket 不读写环境，不能占住维护屏障。
      try (com.deepseekharness.app.core.RuntimeTasks work =
          authed ? com.deepseekharness.app.core.RuntimeTasks.begin() : null) {
        String result;
        if (!authed) {
          result = "[UNAUTHORIZED]";
        } else {
          if (!post
              && (selected == BridgeRoutes.Route.UI
                  || selected == BridgeRoutes.Route.SENSE
                  || selected == BridgeRoutes.Route.VSCREEN
                  || selected == BridgeRoutes.Route.LAUNCH)) monitorScreenPeer(scope, requestInput);
          BridgeRequest bridgeRequest =
              new BridgeRequest(path, route, cmd, post, request, requestInput, c, scope);
          boolean imageRequest =
              selected == BridgeRoutes.Route.READ_FILE
                  && "image".equals(getParam(queryOf(path), "format", "text"));
          if (imageRequest)
            imageLease = com.deepseekharness.app.util.BridgeImageBudget.tryAcquire();
          try {
            result =
                imageRequest && imageLease == null
                    ? "IMAGE_BUSY"
                    : dispatch(selected, bridgeRequest);
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
        if (imageLease != null)
          imageDeadline =
              com.deepseekharness.app.util.BridgeImageWriteDeadline.arm(
                  c, com.deepseekharness.app.util.BridgeImageWriteDeadline.RESPONSE_TIMEOUT_MILLIS);
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
    } finally {
      if (imageDeadline != null) imageDeadline.close();
      if (imageLease != null) imageLease.close();
      cancelScope(scope);
    }
  }

  private void cancelScope(RequestScope scope) {
    if (scope == null) return;
    scope.canceled = true;
    var capability = scope.capability;
    if (capability != null) DshaApp.from(ctx).capabilityBroker().cancel(capability);
  }

  /** The body is fully consumed before this observer becomes its sole reader. */
  private void monitorScreenPeer(RequestScope scope, java.io.InputStream input) {
    scope.run.cancellationPool.execute(
        () -> {
          try {
            input.read();
          } catch (IOException closed) {
            /* EOF and close both withdraw the request. */
          }
          cancelScope(scope);
        });
  }

  private String handleVscreen(BridgeRequest request) throws Exception {
    var parsed =
        request.post
            ? com.deepseekharness.app.util.VscreenBridgeRequest.json(
                request.route,
                com.deepseekharness.app.util.VscreenBridgeRequest.readPost(
                    request.head, request.input, request.socket))
            : com.deepseekharness.app.util.VscreenBridgeRequest.query(request.route, request.query);
    if (request.post) {
      request.socket.setSoTimeout(0);
      monitorScreenPeer(request.scope, request.input);
    }
    return appVscreen(parsed, request.scope);
  }

  private String dispatch(BridgeRoutes.Route route, BridgeRequest request) throws Exception {
    // 历史响应：未知路径不解析 cmd，所以返回 NO_CMD。
    if (route == BridgeRoutes.Route.UNKNOWN) return "[NO_CMD]";
    if ((route == BridgeRoutes.Route.CONFIRM || route == BridgeRoutes.Route.EXEC)
        && request.command.isEmpty()) return "[NO_CMD]";
    if (!bridgeCurrent(request.scope)) return "[STOPPED] BRIDGE_RUN_CHANGED";
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

  private String consentPolicy(RequestScope scope) throws org.json.JSONException {
    var controller = HarnessController.get(ctx);
    var runtime = com.deepseekharness.app.core.ScreenRuntimeIdentity.epoch(ctx);
    var snapshot = new com.deepseekharness.app.core.DeviceGrants(ctx).consentSnapshot();
    boolean available =
        bridgeCurrent(scope)
            && runtime.generation > 0
            && runtime.generation == controller.getWebGeneration()
            && scope.webGeneration == runtime.generation
            && controller.isWebRunning()
            && !controller.isStarting()
            && runtime.runningIdentity()
            && !controller.isStopping()
            && !controller.isUserStopped();
    return new org.json.JSONObject(
            com.deepseekharness.app.util.NativeConsentProjection.payload(
                snapshot,
                available,
                DshaApp.from(ctx).installationId(),
                scope.run.runId,
                runtime.generation,
                runtime.instanceId))
        .toString();
  }

  private String appTask(String operation, String payload) throws Exception {
    return AgentTaskNotifications.get(ctx).bridge(operation, payload);
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
            : status == 429
                ? "Too Many Requests"
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
      String blockedHost = com.deepseekharness.app.util.HostDeviceCommandGuard.rejection(command);
      if (blockedHost != null) return completedExecution("policy", blockedHost + "\n[EXIT=126]");
      String planned = devicePlan(command, forceRoot);
      if (!planned.startsWith("{")) return completedExecution("policy", planned + "\n[EXIT=126]");
      org.json.JSONObject plan = new org.json.JSONObject(planned);
      if (plan.optString("kind").equals("DENY"))
        return completedExecution("policy", plan.optString("reason") + "\n[EXIT=126]");
      boolean sms = "sms.read".equals(plan.optString("capability"));
      boolean rawShell = "SHELL".equals(plan.optString("kind"));
      // 通道顺序与判据和虚拟屏共用 DeviceChannelOrder，只选一次；失败不换通道重放。
      // 原始 shell 只允许 root 身份（本机 su 或 ADB + su），不落到 Shizuku、不降级；短信不走 Shizuku。
      com.deepseekharness.app.util.DeviceChannelOrder.Choice choice =
          com.deepseekharness.app.util.DeviceChannelOrder.forDeviceCommand(
              com.deepseekharness.app.screen.ScreenCapabilities.sample(ctx),
              rawShell
                  ? com.deepseekharness.app.util.DeviceChannelOrder.Kind.SHELL
                  : sms
                      ? com.deepseekharness.app.util.DeviceChannelOrder.Kind.SMS
                      : com.deepseekharness.app.util.DeviceChannelOrder.Kind.NORMAL,
              forceRoot,
              forceAdb);
      switch (choice.channel) {
        case com.deepseekharness.app.util.DeviceChannelOrder.ROOT:
          {
            int user = sms ? android.os.Process.myUid() / 100000 : -1;
            return completedExecution("root", RootShell.exec(ctx, command, user));
          }
        case com.deepseekharness.app.util.DeviceChannelOrder.SHIZUKU:
          return completedExecution("shizuku", ShizukuShell.exec(command));
        case com.deepseekharness.app.util.DeviceChannelOrder.ADB:
          plan.put("su", choice.su);
          return new org.json.JSONObject().put("state", "adb").put("plan", plan).toString();
        default:
          if (rawShell)
            return completedExecution(
                "none",
                com.deepseekharness.app.util.UiText.text(
                    "[DEVICE_CHANNEL_UNAVAILABLE] 放宽档需要可用的 root 通道；请先在设备能力授权中启用 root shell\n[EXIT=124]"));
          return completedExecution(
              "none",
              com.deepseekharness.app.util.UiText.text(
                  "[DEVICE_CHANNEL_UNAVAILABLE] 请在设置 → 设备能力授权中连接 root、Shizuku 或 ADB 通道\n[EXIT=124]"));
      }
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
          com.deepseekharness.app.util.DeviceShellPolicy.inspectManagedVirtualScreen(
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

  /** ADB 每次发送前向原生策略取计划；计划只包含已验证 argv 或显式 root 原始命令。 */
  private String devicePlan(String command, boolean root) {
    try {
      String blockedHost = com.deepseekharness.app.util.HostDeviceCommandGuard.rejection(command);
      if (blockedHost != null)
        return new org.json.JSONObject()
            .put("version", 1)
            .put("kind", "DENY")
            .put("reason", blockedHost)
            .put("argv", new org.json.JSONArray())
            .toString();
      com.deepseekharness.app.util.DeviceShellPolicy.Mode mode =
          new com.deepseekharness.app.core.ConfigStore(ctx).rootPolicyMode();
      // 放宽档只对真实可用的 root 通道生效；Shizuku/ADB 通用执行器继续按严格档取计划。
      boolean relaxedRoot =
          mode != com.deepseekharness.app.util.DeviceShellPolicy.Mode.STRICT
              && RootShell.enabled(ctx)
              && RootShell.present();
      com.deepseekharness.app.util.DeviceShellPolicy.Plan plan =
          com.deepseekharness.app.util.DeviceShellPolicy.inspect(
              command,
              root || relaxedRoot
                  ? mode
                  : com.deepseekharness.app.util.DeviceShellPolicy.Mode.STRICT);
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
      if (plan.kind == com.deepseekharness.app.util.DeviceShellPolicy.Kind.SHELL
          && plan.shell != null) {
        // guest 只在 kind=SHELL、mode 合法且 su=true 时执行原始命令；rootOnly 留给原生选择器核验。
        value
            .put("kind", "SHELL")
            .put("reason", "")
            .put("argv", new org.json.JSONArray())
            .put("operands", new org.json.JSONArray())
            .put("command", plan.shell)
            .put("mode", com.deepseekharness.app.util.DeviceShellPolicy.Mode.idOf(plan.mode))
            .put("rootOnly", true);
      }
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
   * <p>用共享原生导航复用已有任务，保留浏览器页面；requestCode 与
   * 其它通知区分，避免 {@code FLAG_UPDATE_CURRENT} 让后建的通知覆盖前一个的 PendingIntent。
   */
  private PendingIntent agentNotificationIntent() {
    Intent intent =
        com.deepseekharness.app.ui.MainActivity.navigationIntent(ctx).putExtra("open_web", true);
    // 经应用入口中转：启动目标为 Web 时直接回到网页，不先切到 Main 再自动进入。
    return com.deepseekharness.app.ui.AppEntryRouterActivity.pending(
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
    var coordinator = com.deepseekharness.app.control.PhoneControlCoordinator.get(context);
    coordinator.stopLocal("LOCAL_DSH_STOPPED");
    com.deepseekharness.app.screen.AccessibilityRepairOwner.cancel(
        com.deepseekharness.app.util.AccessibilityRepairState.CancelCause.SCREEN_AUTHORITY_REVOKED);
    revokeDisconnectedScreenGrant(context);
  }

  /** Revokes every old screen request without treating a planned platform unbind as user consent. */
  public static void revokeDisconnectedScreenGrant(Context context) {
    com.deepseekharness.app.control.PhoneControlCoordinator.get(context)
        .stop("ACCESSIBILITY_DISCONNECTED");
    DshaApp.from(context).capabilityBroker().revoke("screen");
    DshaAccessibilityService.revokeRequests();
    uiGrant.revoke();
    cancelScreenConfirmation();
    com.deepseekharness.app.vscreen.VirtualScreenManager.from(context)
        .revokeFor("ACCESSIBILITY_DISCONNECTED");
  }

  public static void screenPolicyChanged(Context context) {
    // Reconfiguration retires old work; only an explicit user pause blocks new tasks.
    // stop preserves an existing user pause instead of replacing or clearing it.
    com.deepseekharness.app.control.PhoneControlCoordinator.get(context)
        .stop("SCREEN_POLICY_CHANGED");
    com.deepseekharness.app.screen.AccessibilityRepairOwner.cancel(
        com.deepseekharness.app.util.AccessibilityRepairState.CancelCause.POLICY_CHANGED);
    DshaApp.from(context).capabilityBroker().revoke("screen");
    DshaAccessibilityService.revokeRequests();
    uiGrant.revoke();
    cancelScreenConfirmation();
    com.deepseekharness.app.vscreen.VirtualScreenManager.from(context).discardObservations();
  }

  /** A global consent change invalidates all domains before the new local mode is published. */
  public static void globalConsentChanged(Context context) {
    com.deepseekharness.app.control.PhoneControlCoordinator.get(context)
        .stop("CONSENT_POLICY_CHANGED");
    com.deepseekharness.app.ui.DshaDialogBuilder.invalidateSafetyConfirmations();
    DshaApp.from(context).capabilityBroker().revokeAll();
    com.deepseekharness.app.screen.AccessibilityRepairOwner.cancel(
        com.deepseekharness.app.util.AccessibilityRepairState.CancelCause.POLICY_CHANGED);
    DshaAccessibilityService.revokeRequests();
    uiGrant.revoke();
    DeviceBridgeService.adbIntentChanging();
    synchronized (LIFECYCLE) {
      HttpShellService service = instance;
      if (service != null && service.pendingConfirm != null)
        service.finishConfirmation(service.pendingConfirm);
    }
    com.deepseekharness.app.vscreen.VirtualScreenManager.from(context)
        .revokeFor("CONSENT_POLICY_CHANGED");
  }

  public static void smsPolicyChanged(Context context) {
    DshaApp.from(context).capabilityBroker().revoke("sms");
  }

  public static void clipboardPolicyChanged(Context context) {
    DshaApp.from(context).capabilityBroker().revoke("clipboard.read");
  }

  public static boolean screenPaused() {
    return uiGrant.paused();
  }

  public static void setScreenPaused(Context context, boolean paused) {
    var coordinator = com.deepseekharness.app.control.PhoneControlCoordinator.get(context);
    if (paused) coordinator.pause("LOCAL_USER_PAUSE");
    else coordinator.resume();
    com.deepseekharness.app.screen.AccessibilityRepairOwner.cancel(
        com.deepseekharness.app.util.AccessibilityRepairState.CancelCause.PAUSE_CHANGED);
    DshaApp.from(context).capabilityBroker().revoke("screen");
    DshaAccessibilityService.revokeRequests();
    com.deepseekharness.app.vscreen.VirtualScreenManager.from(context).discardObservations();
    uiGrant.setPaused(paused);
    cancelScreenConfirmation();
    if (paused)
      com.deepseekharness.app.vscreen.VirtualScreenManager.from(context)
          .revokeFor("LOCAL_USER_PAUSE");
  }

  private static void cancelScreenConfirmation() {
    synchronized (LIFECYCLE) {
      HttpShellService service = instance;
      if (service != null && service.pendingConfirm != null && service.pendingConfirm.screen)
        service.finishConfirmation(service.pendingConfirm);
    }
  }

  private boolean bridgeCurrent(RequestScope scope) {
    synchronized (LIFECYCLE) {
      return scope != null
          && !scope.canceled
          && scope.run != null
          && running
          && activeRun == scope.run
          && scope.consentRevision
              == new com.deepseekharness.app.core.DeviceGrants(ctx).consentPolicy().revision()
          && LIFECYCLE.isCurrent(scope.run.generation);
    }
  }

  private boolean screenBridgeCurrent(RequestScope scope) {
    synchronized (LIFECYCLE) {
      return scope != null
          && !scope.canceled
          && scope.run != null
          && running
          && activeRun == scope.run
          && scope.consentRevision
              == new com.deepseekharness.app.core.DeviceGrants(ctx).consentPolicy().revision()
          && LIFECYCLE.isCurrent(scope.run.generation)
          && scope.screenRevision == uiGrant.revision()
          && scope.webGeneration == HarnessController.get(ctx).getWebGeneration();
    }
  }

  private com.deepseekharness.app.capabilities.CapabilityRequest capabilityRequest(
      RequestScope scope,
      String capability,
      String action,
      com.deepseekharness.app.capabilities.TargetEvidence target,
      long policyRevision) {
    if (scope == null || scope.run == null || !bridgeCurrent(scope)) return null;
    var broker = DshaApp.from(ctx).capabilityBroker();
    var runtime = com.deepseekharness.app.core.ScreenRuntimeIdentity.epoch(ctx);
    if (runtime.generation <= 0) return null;
    try {
      var request =
          new com.deepseekharness.app.capabilities.CapabilityRequest(
              scope.requestId,
              capability,
              action,
              runtime,
              DshaApp.from(ctx).installationId(),
              scope.run.runId,
              target,
              policyRevision,
              broker.epoch(capability),
              broker.nowMillis() + 65000);
      scope.capability = request;
      return request;
    } catch (RuntimeException invalid) {
      return null;
    }
  }

  private com.deepseekharness.app.capabilities.CapabilityRequest screenRequest(
      RequestScope scope, String action, com.deepseekharness.app.util.ScreenTarget target) {
    var broker = DshaApp.from(ctx).capabilityBroker();
    com.deepseekharness.app.capabilities.TargetEvidence evidence =
        target == null
            ? () -> "application"
            : new com.deepseekharness.app.capabilities.WindowTargetEvidence() {
              public String kind() {
                return "android-window";
              }

              public int displayId() {
                return target.displayId;
              }

              public int windowId() {
                return target.windowId;
              }

              public String packageName() {
                return target.packageName;
              }
            };
    return screenRequestEvidence(scope, action, evidence);
  }

  private com.deepseekharness.app.capabilities.CapabilityRequest screenRequestEvidence(
      RequestScope scope,
      String action,
      com.deepseekharness.app.capabilities.TargetEvidence evidence) {
    var broker = DshaApp.from(ctx).capabilityBroker();
    var request =
        new com.deepseekharness.app.capabilities.CapabilityRequest(
            scope.requestId,
            "screen",
            action,
            com.deepseekharness.app.core.ScreenRuntimeIdentity.epoch(ctx),
            DshaApp.from(ctx).installationId(),
            scope.run.runId,
            evidence,
            new com.deepseekharness.app.core.DeviceGrants(ctx).screenAuthorityRevision(),
            broker.epoch("screen"),
            android.os.SystemClock.elapsedRealtime() + 65000);
    scope.capability = request;
    if (scope.canceled) broker.cancel(request);
    return request;
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

  private ScreenAuthorization screenAuthorization(
      RequestScope scope,
      String action,
      String pkg,
      com.deepseekharness.app.util.ScreenTarget target,
      com.deepseekharness.app.capabilities.CapabilityRequest capability) {
    return screenAuthorization(scope, action, pkg, target, capability, null, false);
  }

  /**
   * 屏幕授权。{@code sensitivity} 是已识别的敏感界面类别（虚拟屏），{@code sensitiveAsk} 表示该类别策略为
   * ASK：跳过「全部允许」与本轮应用记忆，本次动作必须再确认；密码文本读写不走这里（输入层硬阻断）。
   */
  private ScreenAuthorization screenAuthorization(
      RequestScope scope,
      String action,
      String pkg,
      com.deepseekharness.app.util.ScreenTarget target,
      com.deepseekharness.app.capabilities.CapabilityRequest capability,
      com.deepseekharness.app.util.SensitiveScreenPolicy.Category sensitivity,
      boolean sensitiveAsk) {
    boolean sensitive = isSensitiveApp(pkg);
    var controller = com.deepseekharness.app.core.HarnessController.get(ctx);
    var grants = new com.deepseekharness.app.core.DeviceGrants(ctx);
    long generation = controller.getWebGeneration(), revision = uiGrant.revision();
    // 合并修订号：确认方式或敏感界面策略任一保存都会让旧确认/旧请求失效。
    long policyRevision = grants.screenAuthorityRevision();
    if (!screenBridgeCurrent(scope)
        || uiGrant.paused()
        || !controller.isWebRunning()
        || controller.isStarting()
        || controller.isStopping()
        || controller.isUserStopped()
        || generation <= 0) return ScreenAuthorization.UNAVAILABLE;
    var policy = grants.effectiveScreenPolicy();
    if (!sensitiveAsk && policy.allows(pkg)) {
      boolean granted =
          uiGrant.completeConfirmation(
              true,
              capability,
              com.deepseekharness.app.core.ScreenRuntimeIdentity.epoch(ctx),
              scope.run.runId,
              revision,
              policyRevision,
              screenBridgeCurrent(scope)
                  && controller.isWebRunning()
                  && !controller.isStarting()
                  && !controller.isStopping()
                  && !controller.isUserStopped()
                  && DshaApp.from(ctx).capabilityBroker().current(capability),
              true);
      return granted && DshaApp.from(ctx).capabilityBroker().authorize(capability)
          ? ScreenAuthorization.ALLOWED
          : ScreenAuthorization.UNAVAILABLE;
    }
    // 「本轮允许此应用」：同一轮、同一应用、同一确认策略修订号内免于重复弹窗；敏感应用每 5 次再问。
    // 仍走与弹窗允许相同的一次性授权与全部运行身份复核，不跳过目标、遮挡或代次检查。
    var turnTask =
        com.deepseekharness.app.control.PhoneControlCoordinator.get(ctx).snapshot().binding();
    String turnKey = turnTask == null ? null : turnTask.identity();
    var turnGrants = com.deepseekharness.app.control.PhoneControlCoordinator.get(ctx).turnGrants();
    long confirmRevision = policyRevision;
    if (!sensitiveAsk && turnGrants.allows(turnKey, pkg, sensitive, confirmRevision)) {
      boolean remembered =
          uiGrant.completeConfirmation(
              true,
              capability,
              com.deepseekharness.app.core.ScreenRuntimeIdentity.epoch(ctx),
              scope.run.runId,
              revision,
              confirmRevision,
              screenBridgeCurrent(scope)
                  && !controller.isStopping()
                  && !controller.isUserStopped()
                  && DshaApp.from(ctx).capabilityBroker().current(capability),
              false);
      if (remembered && DshaApp.from(ctx).capabilityBroker().authorize(capability))
        return ScreenAuthorization.ALLOWED;
    }
    String where = pkg.isEmpty() ? com.deepseekharness.app.util.UiText.text("当前界面") : pkg;
    String why;
    if (sensitiveAsk) {
      // 敏感类别：说明这是「识别到的类别」，仍是一次性确认；密码文本硬阻断不因确认而解除。
      why =
          com.deepseekharness.app.util.UiText.format(
              "在【%s】里：%s  # 已识别为%s；本次仍需你确认。密码文本读写始终阻断", where, action, sensitiveLabel(sensitivity));
    } else {
      why =
          sensitive
              ? com.deepseekharness.app.util.UiText.format(
                  "在【%s】里：%s  # 当前确认策略要求你确认，界面可能涉及支付或隐私", where, action)
              : com.deepseekharness.app.util.UiText.format("%s  # 仅批准本次操作；可在设备能力授权中设置确认策略", action);
    }
    boolean[] rememberTurn = new boolean[1];
    ConfirmationOutcome outcome =
        confirmOutcome(scope, capability, why, true, target, rememberTurn);
    // A sensitive confirmation is one-shot, but must obey the same revocation and
    // run identity checks as a remembered grant. Never revive an old dialog result.
    boolean granted =
        uiGrant.completeConfirmation(
            outcome == ConfirmationOutcome.ALLOWED,
            capability,
            com.deepseekharness.app.core.ScreenRuntimeIdentity.epoch(ctx),
            scope.run.runId,
            revision,
            policyRevision,
            screenBridgeCurrent(scope)
                && !controller.isStopping()
                && !controller.isUserStopped()
                && DshaApp.from(ctx).capabilityBroker().current(capability),
            false);
    if (granted && DshaApp.from(ctx).capabilityBroker().authorize(capability)) {
      if (!sensitiveAsk && rememberTurn[0]) {
        turnGrants.remember(turnKey, pkg, confirmRevision);
        // 敏感应用：这一次已用掉一次额度。
        if (sensitive) turnGrants.allows(turnKey, pkg, true, confirmRevision);
      }
      return ScreenAuthorization.ALLOWED;
    }
    if (outcome == ConfirmationOutcome.DENIED) return ScreenAuthorization.DENIED;
    if (outcome == ConfirmationOutcome.NO_ANSWER) return ScreenAuthorization.NO_ANSWER;
    return ScreenAuthorization.UNAVAILABLE;
  }

  /** 敏感类别的人类可读名称；只用于已本地化的确认文案。 */
  private static String sensitiveLabel(
      com.deepseekharness.app.util.SensitiveScreenPolicy.Category category) {
    if (category == null)
      return com.deepseekharness.app.util.UiText.choose("敏感界面", "sensitive screen");
    switch (category) {
      case PASSWORD:
        return com.deepseekharness.app.util.UiText.choose("密码界面", "password screen");
      case OTP:
        return com.deepseekharness.app.util.UiText.choose("验证码界面", "one-time code screen");
      case PAYMENT:
        return com.deepseekharness.app.util.UiText.choose("支付界面", "payment screen");
      case SYSTEM_SECURITY:
        return com.deepseekharness.app.util.UiText.choose("系统安全界面", "system security screen");
      case SECURE_UNKNOWN:
      default:
        return com.deepseekharness.app.util.UiText.choose(
            "无法确认的敏感界面", "unverified sensitive screen");
    }
  }

  private static String shortText(String s) {
    if (s == null) return "";
    String t = s.replace('\n', ' ').trim();
    return t.length() > 24 ? t.substring(0, 24) + "…" : t;
  }

  /** Strict ScreenSense namespace; the implementation reuses the existing task/auth fence. */
  private String appSense(String path, RequestScope scope) {
    String route = path.split("\\?", 2)[0];
    if (!route.equals("/app/sense/state")) return "[ERR] UNKNOWN_ROUTE";
    String query = queryOf(path);
    return appUi("/app/ui/sense/state" + (query.isEmpty() ? "" : "?" + query), scope);
  }

  /**
   * T130：实体屏特权执行桥。{@code /app/priv/*} 只接受完整端点；能力开关、当前手机任务、
   * 屏幕确认策略与目标窗口都在这里先判，再交给 {@code PhysicalScreenManager}。
   *
   * <p>Phase A 不发原生逐动作确认窗：屏幕确认策略未允许该应用时一律 NOT_SENT（fail-closed），
   * 只放行「全部允许」或该应用显式 ALLOW。整条路径不需要无障碍：抓帧与注入由受管特权 Core 完成。
   */
  private String appPriv(String path, RequestScope scope) {
    String route = path.split("\\?", 2)[0];
    String operation = com.deepseekharness.app.util.PrivilegedScreenRoutes.operation(route);
    if (operation.isEmpty())
      return privRefused("PRIV_UNKNOWN_ROUTE", "USE_A_DOCUMENTED_PRIV_ENDPOINT", null);
    try {
      org.json.JSONObject arguments = new org.json.JSONObject();
      for (String pair : queryOf(path).split("&")) {
        if (pair.isEmpty()) continue;
        String[] item = pair.split("=", 2);
        String key = java.net.URLDecoder.decode(item[0], "UTF-8");
        if (arguments.has(key))
          return privRefused("PRIV_DUPLICATE_ARGUMENT", "FIX_THE_REQUEST", null);
        arguments.put(key, java.net.URLDecoder.decode(item.length == 2 ? item[1] : "", "UTF-8"));
      }
      if (!new com.deepseekharness.app.core.DeviceGrants(ctx).privilegedExecutionAllowed()) {
        com.deepseekharness.app.vscreen.PhysicalScreenManager.from(ctx).revoke();
        return privRefused(
            "PRIVILEGED_EXECUTION_DISABLED", "ENABLE_PRIVILEGED_EXECUTION_IN_DEVICE_GRANTS", null);
      }
      if (!arguments.has("taskSessionId") || !arguments.has("turnId"))
        return privRefused("SCREEN_TASK_IDENTITY_REQUIRED", "CALL_app_ui_task_begin_FIRST", null);
      if (!screenBridgeCurrent(scope)) return "[ERR] BRIDGE_RUN_CHANGED";
      var runtime = com.deepseekharness.app.core.ScreenRuntimeIdentity.epoch(ctx);
      var binding =
          new com.deepseekharness.app.control.ControlTaskAuthority.Binding(
              com.deepseekharness.app.control.ControlTaskAuthority.Owner.LOCAL_DSH,
              scope.run.runId + ":" + runtime.instanceId,
              arguments.getString("taskSessionId"),
              arguments.getString("turnId"));
      var state = com.deepseekharness.app.control.PhoneControlCoordinator.get(ctx).snapshot();
      if (state.binding() == null || !state.binding().equals(binding))
        return privRefused("PRIV_SCREEN_TASK_REQUIRED", "BEGIN_THE_PHONE_TASK_FIRST", null);
      if (state.paused())
        return privRefused("PRIV_SCREEN_TASK_PAUSED", "ASK_USER_TO_RESUME_OR_STOP_THE_TASK", null);

      var manager = com.deepseekharness.app.vscreen.PhysicalScreenManager.from(ctx);
      if (operation.equals("info") || operation.equals("frame")) {
        org.json.JSONObject receipt = operation.equals("info") ? manager.info() : manager.frame();
        if (receipt.optBoolean("ok")) {
          String rejected =
              privTargetGate(
                  receipt.optString("package", ""), receipt.optBoolean("packageVerified", false));
          if (rejected != null) return rejected;
        }
        return receipt.toString();
      }
      if (operation.equals("config")) {
        Integer maxWidth = arguments.has("maxWidth") ? privInt(arguments, "maxWidth") : null;
        Integer quality = arguments.has("jpegQuality") ? privInt(arguments, "jpegQuality") : null;
        Integer fps = arguments.has("maxFps") ? privInt(arguments, "maxFps") : null;
        return manager.configure(maxWidth, quality, fps).toString();
      }
      if (operation.equals("close")) return manager.close().toString();
      // 动作与 benchmark 必须先有过一次成功观察：Phase A 不做"没看屏就点"。
      String target = manager.targetPackage();
      if (!manager.targetVerified() || target.isEmpty())
        return privRefused("PRIV_OBSERVE_FIRST", "CALL_app_priv_frame_FIRST", null);
      String rejected = privTargetGate(target, true);
      if (rejected != null) return rejected;
      int waitMs =
          arguments.has("waitMs")
              ? privInt(arguments, "waitMs")
              : com.deepseekharness.app.util.PrivilegedScreenPolicy.DEFAULT_FRAME_WAIT_MS;
      switch (operation) {
        case "tap":
          return manager
              .tap(privFloat(arguments, "x"), privFloat(arguments, "y"), waitMs)
              .toString();
        case "swipe":
          return manager
              .swipe(
                  privFloat(arguments, "x1"),
                  privFloat(arguments, "y1"),
                  privFloat(arguments, "x2"),
                  privFloat(arguments, "y2"),
                  arguments.has("ms") ? privInt(arguments, "ms") : 300,
                  waitMs)
              .toString();
        case "key":
          return manager.key(privInt(arguments, "keycode"), waitMs).toString();
        case "benchmark":
          return manager
              .benchmark(
                  privInt(arguments, "n"),
                  privFloat(arguments, "x"),
                  privFloat(arguments, "y"),
                  waitMs)
              .toString();
        default:
          return privRefused("PRIV_UNKNOWN_ROUTE", "USE_A_DOCUMENTED_PRIV_ENDPOINT", null);
      }
    } catch (IllegalArgumentException invalid) {
      return privRefused("PRIV_INVALID_ARGUMENT", "FIX_THE_REQUEST", null);
    } catch (Exception error) {
      return "[ERR] " + safeError(error);
    }
  }

  /** 目标窗口与屏幕确认门禁：目标不明、敏感应用或未允许都 fail-closed。 */
  private String privTargetGate(String pkg, boolean verified) {
    if (!verified || pkg.isEmpty())
      return privRefused("PRIV_TARGET_UNVERIFIABLE", "OBSERVE_THE_PHYSICAL_SCREEN_AGAIN", pkg);
    if (com.deepseekharness.app.util.SensitiveAppPolicy.sensitive(pkg))
      return privRefused(
          "PRIV_SENSITIVE_TARGET_UNSUPPORTED", "BRING_A_SUPPORTED_APP_TO_THE_FOREGROUND", pkg);
    var policy = new com.deepseekharness.app.core.DeviceGrants(ctx).effectiveScreenPolicy();
    if (!policy.allows(pkg))
      return privRefused(
          "SCREEN_CONFIRMATION_REQUIRED",
          "ALLOW_SCREEN_CONTROL_FOR_THIS_APP_IN_DEVICE_GRANTS",
          pkg);
    return null;
  }

  private static int privInt(org.json.JSONObject arguments, String key) {
    String value = arguments.optString(key, "");
    if (!value.matches("-?[0-9]{1,6}")) throw new IllegalArgumentException("PRIV_INVALID_ARGUMENT");
    return Integer.parseInt(value);
  }

  private static float privFloat(org.json.JSONObject arguments, String key) {
    String value = arguments.optString(key, "");
    if (!value.matches("-?[0-9]{1,6}(\\.[0-9]{1,4})?"))
      throw new IllegalArgumentException("PRIV_INVALID_ARGUMENT");
    return Float.parseFloat(value);
  }

  private static String privRefused(String code, String nextAction, String detail) {
    org.json.JSONObject value = new org.json.JSONObject();
    try {
      value
          .put("ok", false)
          .put("state", "NOT_SENT")
          .put("error", code)
          .put("retryable", false)
          .put("automaticRetry", false)
          .put("accessibilityRequired", false)
          .put("nextAction", nextAction);
      if (detail != null && !detail.isEmpty()) value.put("package", detail);
    } catch (Exception ignored) {
      // 结构固定的回执不会编码失败；失败也不能让桥返回未处理的异常。
    }
    return value.toString();
  }

  /** Main-screen adapter receives an explicit request and the caller's observation evidence. */
  private String appUi(String path, RequestScope scope) {
    String route = path.split("\\?", 2)[0];
    String operation = route.substring("/app/ui/".length());
    if (operation.equals("shot")) operation = "screenshot";
    if (operation.equals("sense/state")) operation = "senseState";
    if (!java.util.Set.of(
            "dump",
            "screenshot",
            "tap",
            "input",
            "key",
            "swipe",
            "tree",
            "senseState",
            "node",
            "history",
            "task-begin",
            "task-end")
        .contains(operation)) return "[ERR] UNKNOWN_ROUTE";
    // 识屏模式门控：实体主屏工具只在 physical 模式放行；不回退到虚拟屏。
    String modeRejection =
        com.deepseekharness.app.screen.ScreenMode.bridgeRejection(
            ctx, com.deepseekharness.app.util.ScreenModePolicy.Domain.PHYSICAL, operation);
    if (modeRejection != null)
      return com.deepseekharness.app.screen.ScreenReply.refused(modeRejection).toString();
    String query = queryOf(path);
    try {
      var arguments = new org.json.JSONObject();
      for (String pair : query.split("&")) {
        if (pair.isEmpty()) continue;
        String[] item = pair.split("=", 2);
        String key = java.net.URLDecoder.decode(item[0], "UTF-8");
        if (arguments.has(key)) return "[ERR] DUPLICATE_ARGUMENT";
        arguments.put(key, java.net.URLDecoder.decode(item.length == 2 ? item[1] : "", "UTF-8"));
      }
      if (operation.equals("tap") && !arguments.optString("text").isEmpty()) {
        if (arguments.has("observationId") || arguments.has("gesture"))
          return "[ERR] AMBIGUOUS_SCREEN_ACTION";
        operation = "tapText";
      }
      var runtime = com.deepseekharness.app.core.ScreenRuntimeIdentity.epoch(ctx);
      // The bridge runtime identifies a connection, never a conversation. Both
      // caller IDs must be supplied by the actual tool execution transport.
      if (!arguments.has("taskSessionId") || !arguments.has("turnId"))
        return "[ERR] SCREEN_TASK_IDENTITY_REQUIRED";
      if (!screenBridgeCurrent(scope)) return "[ERR] BRIDGE_RUN_CHANGED";
      String taskSessionId = arguments.getString("taskSessionId");
      String turnId = arguments.getString("turnId");
      var binding =
          new com.deepseekharness.app.control.ControlTaskAuthority.Binding(
              com.deepseekharness.app.control.ControlTaskAuthority.Owner.LOCAL_DSH,
              scope.run.runId + ":" + runtime.instanceId,
              taskSessionId,
              turnId);
      // 统一包装点：同一任务的所有 JSON 回执（含拒绝与 history）都附带最近活动摘要。
      String reply = appUiBound(scope, operation, arguments, binding);
      return com.deepseekharness.app.screen.ScreenActivityRecorder.attach(reply, binding);
    } catch (Exception error) {
      return "[ERR] " + safeError(error);
    }
  }

  /**
   * 绑定任务后的 /app/ui/* 执行体。由 {@link #appUi} 统一调用后再经
   * {@link com.deepseekharness.app.screen.ScreenActivityRecorder#attach} 附最近活动，保证每条 JSON 回执
   * （包括拒绝和 history）只有一个包装点。
   */
  private String appUiBound(
      RequestScope scope,
      String operation,
      org.json.JSONObject arguments,
      com.deepseekharness.app.control.ControlTaskAuthority.Binding binding)
      throws Exception {
    var coordinator = com.deepseekharness.app.control.PhoneControlCoordinator.get(ctx);
    String taskSessionId = binding.taskSessionId();
    long actualTurn =
        com.deepseekharness.app.control.ControlTaskAuthority.localTurn(binding.turnId());
    if (operation.equals("task-end")) {
      if (!screenBridgeCurrent(scope)) return "[ERR] BRIDGE_RUN_CHANGED";
      coordinator.completeLocal(binding);
      // finishLocalTask rechecks the exact controller on the UI thread. A user-expanded and
      // paused task stays attached for explicit Continue/Stop; ordinary completion is retired.
      com.deepseekharness.app.assistant.AssistantService.finishLocalTask(ctx, binding);
      return "OK";
    }
    // A turn/end HTTP notification can race the next turn. Its completion
    // evidence is also sent with the next actual execution, scoped to the
    // same full conversation, never a global last-active turn.
    if (arguments.has("completedTurnId")) {
      String completed = arguments.getString("completedTurnId");
      if (com.deepseekharness.app.control.ControlTaskAuthority.localTurn(completed) >= actualTurn)
        return "[ERR] CONTROL_TURN_INVALID";
      if (!screenBridgeCurrent(scope)) return "[ERR] BRIDGE_RUN_CHANGED";
      coordinator.completeLocal(
          new com.deepseekharness.app.control.ControlTaskAuthority.Binding(
              binding.owner(), binding.connection(), taskSessionId, completed));
    }
    // 活动历史是内存里的只读事实，不要求任务窗可见；未开始/结束/暂停时返回空记录。
    if (operation.equals("history")) return appUiHistory(arguments, binding);
    var taskUi = com.deepseekharness.app.assistant.AssistantService.ensureLocalTask(ctx, binding);
    if (!taskUi.ready()) return taskUi.failureReply();
    if (!screenBridgeCurrent(scope)) return "[ERR] BRIDGE_RUN_CHANGED";
    if (operation.equals("task-begin")) {
      if (!com.deepseekharness.app.assistant.AssistantService.stopChannelCurrent(ctx, binding))
        return new com.deepseekharness.app.assistant.AssistantService.TaskUiResult(
                false, "ASSISTANT_STOP_NOTIFICATION_REQUIRED")
            .failureReply();
      coordinator.begin(binding);
      com.deepseekharness.app.screen.ScreenActivityRecorder.observeTask(ctx, binding);
      return com.deepseekharness.app.screen.ScreenReply.completed()
          .put("needsObservation", true)
          .toString();
    }
    boolean observing =
        java.util.Set.of("dump", "tree", "screenshot", "senseState").contains(operation);
    var compose = coordinator.compose();
    if (!observing) {
      // 用户正在给 AI 输入消息：动作停在原地等，不取消对话、不结束本轮。读屏不受影响。
      java.util.function.BooleanSupplier taskLive =
          () -> {
            var state = coordinator.snapshot();
            return !state.paused() && binding.equals(state.binding()) && screenBridgeCurrent(scope);
          };
      com.deepseekharness.app.util.ComposeGate.Outcome waited;
      try {
        waited =
            compose.await(com.deepseekharness.app.util.ComposeGate.DEFAULT_WAIT_MILLIS, taskLive);
      } catch (InterruptedException interrupted) {
        Thread.currentThread().interrupt();
        return com.deepseekharness.app.screen.ScreenReply.refused("SCREEN_RUN_CHANGED").toString();
      }
      if (waited == com.deepseekharness.app.util.ComposeGate.Outcome.MESSAGE_ARRIVED)
        return com.deepseekharness.app.screen.ScreenReply.refused("USER_MESSAGE_ARRIVED")
            .put("userMessage", true)
            .put(
                "guidance",
                com.deepseekharness.app.assistant.AssistantService.taskGuidance(
                    "USER_MESSAGE_ARRIVED"))
            .put("actionExecuted", false)
            .put("nextAction", "READ_THE_NEW_USER_MESSAGE_THEN_OBSERVE_THE_SCREEN_AGAIN")
            .put("automaticRetry", false)
            .toString();
      if (waited == com.deepseekharness.app.util.ComposeGate.Outcome.STILL_COMPOSING)
        return com.deepseekharness.app.screen.ScreenReply.refused("USER_COMPOSING")
            .put("userComposing", true)
            .put(
                "guidance",
                com.deepseekharness.app.assistant.AssistantService.taskGuidance("USER_COMPOSING"))
            .put("actionExecuted", false)
            .put("nextAction", "WAIT_FOR_THE_USER_MESSAGE_DO_NOT_ACT")
            .put("automaticRetry", false)
            .toString();
      // INTERRUPTED 落到下面原有的暂停 / 换轮检查，给出原来的拒绝原因。
    }
    var screenUi =
        com.deepseekharness.app.assistant.AssistantService.prepareScreenOperation(
            ctx, binding, observing);
    if (!screenUi.ready()) return screenUi.failureReply();
    var taskLease = coordinator.begin(binding);
    // 只有真正拿到当前任务的租约才开始记录；同一任务重复调用不会重置已有摘要。
    com.deepseekharness.app.screen.ScreenActivityRecorder.observeTask(ctx, binding);
    if (!observing && !coordinator.actionAllowed(taskLease))
      return com.deepseekharness.app.screen.ScreenReply.refused("FRESH_SCREEN_OBSERVATION_REQUIRED")
          .toString();
    long observationSequence = observing ? coordinator.beginObservation(taskLease) : 0;
    if (observing && observationSequence < 0)
      return com.deepseekharness.app.screen.ScreenReply.refused("SCREEN_RUN_CHANGED").toString();
    var observed =
        DshaAccessibilityService.observeTargetForRequest(
            () ->
                com.deepseekharness.app.assistant.AssistantService.stopChannelCurrent(ctx, binding)
                    && screenBridgeCurrent(scope)
                    && coordinator.current(taskLease));
    if (observed.target == null) return observed.failure().toString();
    if (!observing && !coordinator.checkActionTarget(taskLease, observed.target))
      return com.deepseekharness.app.screen.ScreenReply.refused("SCREEN_TARGET_CHANGED").toString();
    var target = observed.target;
    var capability = screenRequest(scope, operation, target);
    coordinator.bindRequest(capability, taskLease);
    ScreenAuthorization authorization =
        screenAuthorization(scope, operation, target.packageName, target, capability);
    if (authorization != ScreenAuthorization.ALLOWED)
      return "[ERR] "
          + (authorization == ScreenAuthorization.DENIED
              ? "USER_REJECTED"
              : authorization == ScreenAuthorization.NO_ANSWER
                  ? "USER_NO_ANSWER"
                  : "USER_CONFIRM_UNAVAILABLE");
    var controller = HarnessController.get(ctx);
    java.util.function.BooleanSupplier live =
        () ->
            com.deepseekharness.app.assistant.AssistantService.stopChannelCurrent(ctx, binding)
                && screenBridgeCurrent(scope)
                && coordinator.current(taskLease)
                && (observing || coordinator.actionAllowed(taskLease, target))
                && (observing || !compose.composing())
                && !com.deepseekharness.app.control.OwnedScreenOverlay.expanded()
                && capability.runtime.equals(
                    com.deepseekharness.app.core.ScreenRuntimeIdentity.epoch(ctx))
                && controller.isWebRunning()
                && !controller.isStarting()
                && !controller.isStopping()
                && !controller.isUserStopped();
    String reply =
        DshaAccessibilityService.execute(
            DshaApp.from(ctx).capabilityBroker(), capability, live, operation, arguments);
    if (observing && compose.composing()) {
      // 读屏照常返回，但明确告诉 AI 用户正在输入，下一步动作会先等待。
      var annotated = new org.json.JSONObject(reply);
      annotated.put("userComposing", true);
      reply = annotated.toString();
    }
    if (observing
        && new org.json.JSONObject(reply).optString("state").equals("COMPLETED")
        && !coordinator.observed(taskLease, observationSequence, target))
      return com.deepseekharness.app.screen.ScreenReply.refused("SCREEN_OBSERVATION_SUPERSEDED")
          .toString();
    return reply;
  }

  /** 内存活动历史只属于当前任务；别的任务、暂停或结束状态一律返回空记录。 */
  private String appUiHistory(
      org.json.JSONObject arguments,
      com.deepseekharness.app.control.ControlTaskAuthority.Binding binding) {
    var state = com.deepseekharness.app.control.PhoneControlCoordinator.get(ctx).snapshot();
    String identity =
        state.binding() != null && state.binding().equals(binding) && !state.paused()
            ? binding.identity()
            : null;
    return com.deepseekharness.app.screen.ScreenActivityRecorder.history(
        arguments.optString("since"), arguments.optString("limit"), identity);
  }

  /**
   * 虚拟屏语义动作拿不到窗口目标时的只读失败：error 保留 ACCESSIBILITY_UNAVAILABLE 兼容旧调用方，
   * reason / nextAction 区分无障碍未开启、未连接、显示里没有窗口和窗口不可读。请求未发送，可安全重新观察。
   *
   * <p>T98：额外附最小窗口证据（目标窗口字段、应用/系统/输入法窗口计数、歧义标记）。多应用窗口并列时
   * reason=VSCREEN_WINDOW_AMBIGUOUS、nextAction=ASK_USER_TO_CLOSE_SPLIT_SCREEN；证据只用于解释，不参与授权。
   */
  private String vscreenSemanticUnavailable(int display, boolean notSent)
      throws org.json.JSONException {
    // 没有可用显示时本次请求不会解析窗口，线程局部诊断可能残留上一次请求的值，不能当成空屏证据。
    var window =
        display > 0
            ? DshaAccessibilityService.displayWindowSnapshot(display)
            : new com.deepseekharness.app.screen.ScreenTargetResolver.Diagnostics(
                "DISPLAY_UNAVAILABLE", null);
    String diagnostic = window.diagnostic;
    var accessibility =
        com.deepseekharness.app.util.VirtualScreenObservationHint.Accessibility.of(
            DshaAccessibilityService.isConnected(), DshaAccessibilityService.enabledState(ctx));
    var value =
        new org.json.JSONObject()
            .put("ok", false)
            .put("error", "ACCESSIBILITY_UNAVAILABLE")
            .put("treeUnavailable", diagnostic)
            .put("windowDiagnostic", diagnostic)
            .put(
                "windowEvidence",
                com.deepseekharness.app.util.VirtualScreenObservationHint.windowEvidence(
                    accessibility, diagnostic))
            .put(
                "reason",
                com.deepseekharness.app.util.VirtualScreenObservationHint.semanticReason(
                    accessibility, diagnostic, window.evidence))
            .put(
                "nextAction",
                com.deepseekharness.app.util.VirtualScreenObservationHint.semanticNextAction(
                    accessibility, diagnostic, window.evidence))
            .put("retryable", true);
    if (window.evidence != null)
      for (var field : window.evidence.fields().entrySet())
        value.put(field.getKey(), field.getValue());
    if (notSent) value.put("state", "NOT_SENT");
    return value.toString();
  }

  /** 虚拟屏只接受已认证的固定端点；每个写入/启动动作仍复用当前屏幕授权确认。 */
  private String appVscreen(
      com.deepseekharness.app.util.VscreenBridgeRequest request, RequestScope scope) {
    String route = request.route(),
        query = request.query(),
        operation = com.deepseekharness.app.util.VirtualScreenRoutes.operation(route);
    // 识屏模式门控：虚拟屏工具只在 virtual 模式放行；不回退到实体主屏。
    String modeRejection =
        com.deepseekharness.app.screen.ScreenMode.bridgeRejection(
            ctx, com.deepseekharness.app.util.ScreenModePolicy.Domain.VIRTUAL, operation);
    if (modeRejection != null) return vscreenNotSent(modeRejection);
    boolean[] dispatched = {false};
    String[] receipt = {null};
    try {
      String callerCore = request.parameters.getOrDefault("coreInstance", "");
      String callerGeneration = request.parameters.getOrDefault("generation", "");
      if (operation.equals("status") || operation.equals("close")) {
        var capability =
            callerCore.isEmpty()
                ? screenRequest(scope, "vscreen." + operation, null)
                : screenRequestEvidence(
                    scope,
                    "vscreen." + operation,
                    new com.deepseekharness.app.vscreen.VscreenTargetEvidence(
                        callerCore,
                        callerGeneration,
                        -1,
                        -1,
                        "",
                        com.deepseekharness.app.vscreen.VscreenTargetEvidence.Observation.DISPLAY,
                        -1,
                        ""));
        return com.deepseekharness.app.vscreen.VirtualScreenManager.from(ctx)
            .bridge(
                DshaApp.from(ctx).capabilityBroker(),
                capability,
                request,
                () -> screenBridgeCurrent(scope));
      }
      var controller = HarnessController.get(ctx);
      long generation = controller.getWebGeneration(), revision = uiGrant.revision();
      var status = com.deepseekharness.app.vscreen.VirtualScreenManager.from(ctx).targetStatus();
      boolean existingCore = !operation.equals("create");
      if (existingCore) {
        if (callerCore.isEmpty()) return vscreenNotSent("VSCREEN_CORE_IDENTITY_REQUIRED");
        // status 本身失败（待清理、未运行等）时如实回传该状态，不把它说成「Core 身份已变」。
        if (!status.optBoolean("ok")) return vscreenStatusNotSent(status);
        if (!callerCore.equals(status.optString("coreInstance", "")))
          return vscreenNotSent("VSCREEN_CORE_IDENTITY_CHANGED");
        if (!callerGeneration.isEmpty()
            && !callerGeneration.equals(status.optString("generation", "")))
          return vscreenNotSent("VSCREEN_GENERATION_CHANGED");
      }
      int display = status.optInt("displayId", -1);
      var promptTarget = display > 0 ? DshaAccessibilityService.observeTarget(display) : null;
      if (java.util.Set.of("anchor", "anchored-click").contains(operation) && promptTarget == null)
        return vscreenSemanticUnavailable(display, true);
      String pkg =
          operation.equals("launch")
              ? getParam(query, "package", "")
              : promptTarget != null
                  ? promptTarget.packageName
                  : status.optBoolean("packageVerified") ? status.optString("package") : "";
      String selfLaunch =
          operation.equals("launch")
              ? com.deepseekharness.app.util.VirtualScreenPolicy.selfLaunchRejection(
                  pkg, ctx.getPackageName())
              : null;
      // 宿主自身不能启动到虚拟屏（系统会复用运行中的本进程，副屏 WebView 崩溃，T76）：
      // 连授权询问都不发起，直接回明确未发送。
      if (selfLaunch != null) return vscreenNotSent(selfLaunch);
      // 敏感界面分档（T119）：只读分类绑定当前显示；HANDOFF 在派发任何输入前暂停助手。
      // 分类失败不宣称安全：无障碍关闭时不做分类（保护降级），已知敏感应用读不出树时按 ASK 处理。
      com.deepseekharness.app.util.SensitiveScreenPolicy.Category sensitivity = null;
      String sensitivityBasis = "";
      boolean sensitiveAsk = false;
      if (com.deepseekharness.app.util.VirtualScreenRoutes.classified(operation) && display > 0) {
        var evidence =
            com.deepseekharness.app.vscreen.VirtualScreenSensitiveProbe.read(display, pkg);
        if (evidence.targetChanged) return vscreenNotSent("SCREEN_TARGET_CHANGED");
        sensitivity = evidence.category;
        sensitivityBasis = evidence.basis;
        if (sensitivity != null) {
          var sensitivePolicy =
              new com.deepseekharness.app.core.DeviceGrants(ctx).sensitiveScreenPolicy();
          var decision = sensitivePolicy.decide(sensitivity, pkg);
          if (decision == com.deepseekharness.app.util.SensitiveScreenPolicy.Decision.HANDOFF)
            return sensitiveScreenHandoff(sensitivity, sensitivityBasis);
          if (decision == com.deepseekharness.app.util.SensitiveScreenPolicy.Decision.ASK)
            sensitiveAsk = true;
        }
        // 密码界面的文本写入由输入层硬阻断；不先弹一个注定会被拒绝的确认。
        if (sensitivity == com.deepseekharness.app.util.SensitiveScreenPolicy.Category.PASSWORD
            && writesText(request, operation)) sensitiveAsk = false;
      }
      final com.deepseekharness.app.util.SensitiveScreenPolicy.Category sensitivityForResult =
          sensitivity;
      final String sensitivityBasisForResult = sensitivityBasis;
      final boolean sensitiveAskForResult = sensitiveAsk;
      var capability =
          existingCore || !callerCore.isEmpty()
              ? screenRequestEvidence(
                  scope,
                  "vscreen." + operation,
                  vscreenEvidence(
                      request,
                      callerCore,
                      callerGeneration,
                      display,
                      promptTarget,
                      pkg,
                      scope.consentRevision))
              : screenRequest(scope, "vscreen." + operation, promptTarget);
      ScreenAuthorization authorization =
          screenAuthorization(
              scope,
              com.deepseekharness.app.util.UiText.format("操作独立虚拟屏：%s", route),
              pkg,
              promptTarget,
              capability,
              sensitivityForResult,
              sensitiveAskForResult);
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
      // 确认结束后重新取目标，但不能把批准 A 的许可用于 B。
      var target = display > 0 ? DshaAccessibilityService.observeTarget(display) : null;
      var confirmedStatus =
          com.deepseekharness.app.vscreen.VirtualScreenManager.from(ctx).targetStatus();
      if (existingCore && !callerCore.equals(confirmedStatus.optString("coreInstance", "")))
        return vscreenNotSent("VSCREEN_CORE_IDENTITY_CHANGED");
      if (existingCore
          && !callerGeneration.isEmpty()
          && !callerGeneration.equals(confirmedStatus.optString("generation", "")))
        return vscreenNotSent("VSCREEN_GENERATION_CHANGED");
      int confirmedDisplay = confirmedStatus.optInt("displayId", -1);
      String confirmedPackage =
          target != null
              ? target.packageName
              : confirmedStatus.optBoolean("packageVerified")
                  ? confirmedStatus.optString("package")
                  : "";
      if (!operation.equals("launch")
          && display > 0
          && (display != confirmedDisplay
              || !pkg.isEmpty()
                  && !VirtualScreenWindowPolicy.approved(
                      display, pkg, confirmedDisplay, confirmedPackage)
              || VirtualScreenRoutes.mutates(operation) && pkg.isEmpty()))
        return ScreenActionResult.notExecuted();
      boolean semantic =
          java.util.Set.of("node", "editor", "edit", "submit", "tree", "anchor", "anchored-click")
                  .contains(operation)
              || operation.equals("type") && !getParam(query, "text", "").matches("[\\x20-\\x7e]+");
      if (semantic && target == null) return vscreenSemanticUnavailable(display, false);
      java.util.function.BooleanSupplier live =
          () ->
              screenBridgeCurrent(scope)
                  && capability.runtime.equals(
                      com.deepseekharness.app.core.ScreenRuntimeIdentity.epoch(ctx))
                  && generation == controller.getWebGeneration()
                  && revision == uiGrant.revision()
                  && controller.isWebRunning()
                  && !controller.isStarting()
                  && !controller.isStopping()
                  && !controller.isUserStopped();
      if (!live.getAsBoolean()) return "{\"ok\":false,\"error\":\"SCREEN_RUN_CHANGED\"}";
      // create 在现有 Core 上就是旋转：它会重建显示并销毁当前窗口，不能再绑定这个窗口做执行期复核，
      // 否则执行后必判「目标已变」、管理器不采用新代次，之后 status 永远 STALE_GENERATION。
      // 确认前后的窗口一致性检查（上方）照旧；launch 原本就不绑定窗口。
      boolean bindWindow =
          target != null && !operation.equals("launch") && !operation.equals("create");
      java.util.function.Supplier<String> action =
          () -> {
            if (!live.getAsBoolean()
                || bindWindow && !DshaAccessibilityService.matchesTarget(target))
              return "{\"ok\":false,\"error\":\"SCREEN_TARGET_CHANGED\"}";
            // 确认期间页面可能已换到另一个敏感类别：执行前再读一次证据。读不出就不派发（可能已换屏）。
            if (sensitivityForResult != null && VirtualScreenRoutes.mutates(operation)) {
              var fresh =
                  com.deepseekharness.app.vscreen.VirtualScreenSensitiveProbe.read(
                      confirmedDisplay, confirmedPackage);
              if (fresh.targetChanged || !fresh.readable)
                return vscreenSensitivityNotSent("SENSITIVE_SCREEN_UNVERIFIED");
              if (fresh.category != sensitivityForResult)
                return vscreenSensitivityNotSent("SENSITIVE_SCREEN_CHANGED");
            }
            dispatched[0] = true;
            String result =
                com.deepseekharness.app.vscreen.VirtualScreenManager.from(ctx)
                    .bridge(
                        DshaApp.from(ctx).capabilityBroker(),
                        capability,
                        request,
                        () ->
                            live.getAsBoolean()
                                && (!bindWindow || DshaAccessibilityService.matchesTarget(target)));
            if (sensitivityForResult != null)
              result = annotateSensitivity(result, sensitivityForResult, sensitivityBasisForResult);
            receipt[0] = result;
            boolean targetChanged = bindWindow && !DshaAccessibilityService.matchesTarget(target);
            boolean runChanged = !live.getAsBoolean();
            if (targetChanged || runChanged)
              return VirtualScreenRoutes.mutates(operation)
                  ? ScreenActionResult.executed(result, targetChanged, runChanged)
                  : ScreenActionResult.notExecuted();
            return operation.equals("create") ? vscreenRecreatedHint(result, display, pkg) : result;
          };
      return bindWindow
          ? DshaAccessibilityService.runAuthorized(target, live, action)
          : action.get();
    } catch (Throwable error) {
      if (receipt[0] != null) return receipt[0];
      try {
        boolean unknown = dispatched[0] && VirtualScreenRoutes.mutates(operation);
        org.json.JSONObject value =
            new org.json.JSONObject()
                .put("ok", false)
                .put(
                    "error",
                    unknown
                        ? "INPUT_RESULT_UNKNOWN"
                        : error instanceof DshaAccessibilityService.TargetChangedException
                            ? "SCREEN_TARGET_CHANGED"
                            : com.deepseekharness.app.util.VirtualScreenFailures.code(error));
        if (unknown) value.put("executionUnknown", true);
        value.put("cause", com.deepseekharness.app.util.VirtualScreenFailures.cause(error));
        return value.toString();
      } catch (Exception ignored) {
        return "{\"ok\":false,\"error\":\"INPUT_RESULT_UNKNOWN\",\"executionUnknown\":true}";
      }
    }
  }

  /** Caller observation identity is retained verbatim; current status may only reject it. */
  private static com.deepseekharness.app.vscreen.VscreenTargetEvidence vscreenEvidence(
      com.deepseekharness.app.util.VscreenBridgeRequest request,
      String core,
      String generation,
      int display,
      com.deepseekharness.app.util.ScreenTarget target,
      String pkg,
      long consentRevision) {
    String operation = com.deepseekharness.app.util.VirtualScreenRoutes.operation(request.route());
    var observation =
        operation.equals("anchored-click")
            ? com.deepseekharness.app.vscreen.VscreenTargetEvidence.Observation.ANCHORED_NODE
            : operation.equals("anchor")
                ? com.deepseekharness.app.vscreen.VscreenTargetEvidence.Observation.OBSERVED_ANCHOR
                : operation.equals("node")
                    ? com.deepseekharness.app.vscreen.VscreenTargetEvidence.Observation.NODE
                    : java.util.Set.of("editor", "edit", "submit").contains(operation)
                        ? com.deepseekharness.app.vscreen.VscreenTargetEvidence.Observation.EDITOR
                        : java.util.Set.of("touch", "tap", "swipe", "key", "type")
                                .contains(operation)
                            ? com.deepseekharness.app.vscreen.VscreenTargetEvidence.Observation
                                .PIXEL
                            : com.deepseekharness.app.vscreen.VscreenTargetEvidence.Observation
                                .DISPLAY;
    long frame = Long.parseLong(request.parameters.getOrDefault("frameSeq", "-1"));
    String id =
        request.parameters.getOrDefault(
            operation.equals("anchored-click")
                ? "anchorId"
                : operation.equals("anchor")
                    ? "observationId"
                    : operation.equals("node") ? "nodeId" : "editorId",
            "");
    return new com.deepseekharness.app.vscreen.VscreenTargetEvidence(
        core,
        generation,
        display,
        target == null ? -1 : target.windowId,
        pkg,
        observation,
        frame,
        id,
        consentRevision);
  }

  /**
   * 现有 Core 上的 create（旋转）会重建显示，原窗口不会迁到新显示。成功回复里写明这一点和原前台包名，
   * 让助手直接决定是否重新 launch，而不是等下一次 see 失败才知道；不自动 launch（launch 需要单独授权）。
   */
  private static String vscreenRecreatedHint(
      String result, int previousDisplay, String previousPackage) {
    try {
      var value = new org.json.JSONObject(result);
      int display = value.optInt("displayId", -1);
      if (!value.optBoolean("ok")
          || previousDisplay <= 0
          || display <= 0
          || display == previousDisplay) return result;
      value.put("displayRecreated", true).put("previousDisplayId", previousDisplay);
      if (!previousPackage.isEmpty()) value.put("previousPackage", previousPackage);
      return value
          .put(
              "nextAction",
              com.deepseekharness.app.util.VirtualScreenObservationHint.LAUNCH_APP_FIRST)
          .toString();
    } catch (org.json.JSONException invalid) {
      return result;
    }
  }

  /**
   * 敏感界面交接（T119）：暂停助手并把界面交给用户，不派发任何输入。沿用现有暂停语义（清本轮授权、
   * 撤销在途请求、取消当前轮），并丢弃虚拟屏观察，用户恢复后必须重新 see，不自动重放。
   */
  private String sensitiveScreenHandoff(
      com.deepseekharness.app.util.SensitiveScreenPolicy.Category category, String basis) {
    com.deepseekharness.app.control.PhoneControlCoordinator.get(ctx)
        .pause("SENSITIVE_SCREEN_" + category.name());
    com.deepseekharness.app.vscreen.VirtualScreenManager.from(ctx).discardObservations();
    try {
      return new org.json.JSONObject()
          .put("ok", false)
          .put("state", "NOT_SENT")
          .put("error", "SENSITIVE_SCREEN_HANDOFF")
          .put("code", "SENSITIVE_SCREEN_HANDOFF")
          .put("sensitivity", category.name())
          .put("sensitivityBasis", basis == null ? "" : basis)
          .put("readOnly", true)
          .put("retryable", false)
          .put("actionExecuted", false)
          .put(
              "nextAction",
              com.deepseekharness.app.util.VirtualScreenObservationHint.HANDOFF_TO_USER)
          .toString();
    } catch (org.json.JSONException invalid) {
      return vscreenNotSent("SENSITIVE_SCREEN_HANDOFF");
    }
  }

  /** 敏感证据在确认后失效或类别变化：动作未派发，重新观察后可安全重试。 */
  private static String vscreenSensitivityNotSent(String code) {
    try {
      return new org.json.JSONObject()
          .put("ok", false)
          .put("state", "NOT_SENT")
          .put("error", code)
          .put("code", code)
          .put("retryable", true)
          .put("actionExecuted", false)
          .put("nextAction", com.deepseekharness.app.util.VirtualScreenObservationHint.SEE_AGAIN)
          .toString();
    } catch (org.json.JSONException invalid) {
      return "{\"ok\":false,\"state\":\"NOT_SENT\",\"error\":\"SENSITIVE_SCREEN_UNVERIFIED\"}";
    }
  }

  /** 只回类别与依据码，绝不回界面文本；成功/失败回执都不改变原有字段。 */
  private static String annotateSensitivity(
      String json,
      com.deepseekharness.app.util.SensitiveScreenPolicy.Category category,
      String basis) {
    if (json == null || category == null) return json;
    try {
      var value = new org.json.JSONObject(json);
      value.put("sensitivity", category.name());
      if (basis != null && !basis.isEmpty()) value.put("sensitivityBasis", basis);
      return value.toString();
    } catch (org.json.JSONException invalid) {
      return json;
    }
  }

  /** 该请求是否会写入文本（含 node 的 set_text）；密码界面的这类请求交给输入层硬阻断。 */
  private static boolean writesText(
      com.deepseekharness.app.util.VscreenBridgeRequest request, String operation) {
    if (com.deepseekharness.app.util.VirtualScreenRoutes.writesText(operation)) return true;
    return operation.equals("node")
        && "set_text".equals(request.parameters.getOrDefault("action", ""));
  }

  private static String vscreenNotSent(String code) {
    String next =
        code.startsWith("VSCREEN_CORE_IDENTITY") || code.equals("VSCREEN_GENERATION_CHANGED")
            ? ",\"nextAction\":\""
                + com.deepseekharness.app.util.VirtualScreenObservationHint.identityNextAction(code)
                + "\""
            : "";
    return "{\"ok\":false,\"state\":\"NOT_SENT\",\"error\":\""
        + code
        + "\",\"code\":\""
        + code
        + "\""
        + next
        + "}";
  }

  /**
   * 现有 Core 请求发送前 status 失败：回传原状态码、生命周期与清理信息及下一步，请求未发送。待清理时
   * nextAction=ASK_USER_TO_CLOSE：须用户同意后用同一 coreInstance 显式 close，绝不自动重建或换通道。
   */
  private static String vscreenStatusNotSent(org.json.JSONObject status) {
    try {
      String error = status.optString("error", "VSCREEN_STATUS_UNAVAILABLE");
      boolean cleanup = status.optBoolean("cleanupPending");
      String next =
          com.deepseekharness.app.util.VirtualScreenObservationHint.statusNextAction(
              error, cleanup);
      var value =
          new org.json.JSONObject()
              .put("ok", false)
              .put("state", "NOT_SENT")
              .put("error", error)
              .put("code", error)
              .put("cleanupPending", cleanup)
              .put("nextAction", next)
              // 需要用户关闭重建时，重看或重试都不会改变结果。
              .put(
                  "retryable",
                  !com.deepseekharness.app.util.VirtualScreenObservationHint.ASK_USER_TO_CLOSE
                      .equals(next));
      for (String key :
          new String[] {"lifecycleState", "coreInstance", "cleanupChannel", "cleanupProbeReason"})
        if (status.has(key)) value.put(key, status.get(key));
      return value.toString();
    } catch (org.json.JSONException invalid) {
      return vscreenNotSent("VSCREEN_STATUS_UNAVAILABLE");
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
    // Fixed protocol keys must not be translated. Read the current locale on every request,
    // rather than the Web process's language captured before an in-place language change.
    return String.format(
        java.util.Locale.ROOT,
        "BRIDGE_PROTOCOL=%s\nBRIDGE_PORT=%s\nAPPLICATION_ID=%s\nBRIDGE_AUTH=header-x-token\nSCREEN_SENSE=1\nAPP_VERSION=%s\nAPP_CODE=%s\nUI_LANGUAGE=%s\nHINT=See /app/help; compare protocol with >=\n",
        BRIDGE_PROTOCOL,
        bridgePort,
        ctx.getPackageName(),
        BuildConfig.VERSION_NAME,
        BuildConfig.VERSION_CODE,
        com.deepseekharness.app.util.UiText.language());
  }

  /**
   * {@code /app/entry-target}：读取或设置桌面图标启动后进入的页面。
   *
   * <p>只接受精确的 {@code set=main} / {@code set=web}；不启动、不停止任何运行实例，
   * 也不越过入口门禁。设置同步提交后回读真实值；未提交时如实报告失败。
   */
  private String appEntryTarget(String path) {
    String requested = getParam(queryOf(path), "set", null);
    var config = new com.deepseekharness.app.core.ConfigStore(ctx);
    if (requested != null) {
      if (!com.deepseekharness.app.util.EntryTarget.valid(requested)) return "INVALID_TARGET";
      if (!config.setEntryTarget(requested)) return "ERROR: ENTRY_TARGET_NOT_SAVED";
    }
    return com.deepseekharness.app.util.EntryTarget.bridgeState(config.getEntryTarget());
  }

  /** /app/help lists bridge endpoint parameters for authenticated clients. */
  private String appHelp() {
    String installHelp =
        com.deepseekharness.app.util.UiText.choose(
            "\n== 安装应用（宿主系统安装器）==\n/app/install?path=/root/out/app.apk  把容器路径或 /sdcard 下的单个 .apk 交给系统安装器；安装与否由用户在系统界面确认\nDSHA 在前台、无障碍服务已连接，或 Android 14 及以下已授悬浮窗时直接打开安装器（OK: installer）；否则发通知由用户点按打开（OK: notification）。通知被关闭返回 NO_NOTIFICATION。\nOK 只表示已请求，不证明已安装；之后用 /app/apps?q=包名 核对。\nNO_PERMISSION：DSHA 未获「安装未知应用」授权，已打开设置页，用户允许后再请求一次，不要循环重试。\n不支持 .apks/.xapk 分包；不能经桥覆盖安装 DSHA 自身（会结束 App 与全部会话），请用应用内更新页。\n",
            "\n== Install apps (host system installer) ==\n/app/install?path=/root/out/app.apk  hand a single .apk from a container path or /sdcard to the system installer; the user confirms in the system screen\nWhen DSHA is in the foreground, its accessibility service is connected, or it holds the overlay grant on Android 14 or lower, the installer opens directly (OK: installer); otherwise a notification is posted for the user to tap (OK: notification). Disabled notifications return NO_NOTIFICATION.\nOK only means the request was made, not that the app is installed; verify with /app/apps?q=package.\nNO_PERMISSION: DSHA lacks the \"Install unknown apps\" grant; the settings page was opened, request once more after the user allows it and never loop.\n.apks/.xapk bundles are unsupported; DSHA cannot overwrite itself through the bridge (that ends the App and every session); use the in-app update page.\n");
    String netHelp =
        com.deepseekharness.app.util.UiText.choose(
            "\n== 网络与 DNS（实验性） ==\n/app/net  宿主默认网络（类型/VPN/验证/私有 DNS/设备 DNS）与容器 resolv.conf 来源及是否过期；只读，不含本机 IP\n/app/net/resolve?host=example.com&family=0|4|6  用 Android 系统解析器在当前网络解析域名，返回 DSHA_RESOLVE_V1 文本；只解析不连接\n切网后宿主自动刷新容器 resolv.conf 的 DSHA 托管段；Node 解析遇 EAI_AGAIN/EAI_FAIL 时会经此端点兜底一次。用户自定义 nameserver 不被改写。属争取性修复，不保证所有网络下可靠。\n",
            "\n== Network and DNS (experimental) ==\n/app/net  host default network (transport/VPN/validation/private DNS/device DNS) and the container resolv.conf source and staleness; read-only, no local IP\n/app/net/resolve?host=example.com&family=0|4|6  resolve a name with the Android system resolver on the current network; returns DSHA_RESOLVE_V1 text; resolves only, never connects\nAfter a network switch the host refreshes the DSHA-managed block of the container resolv.conf; Node lookups that fail with EAI_AGAIN/EAI_FAIL fall back to this endpoint once. User nameservers are never rewritten. Best-effort fix; reliability is not guaranteed on every network.\n");
    String rootPolicyHelp =
        com.deepseekharness.app.util.UiText.choose(
            "\n== root 命令拦截档位 ==\n设置 → 设备能力授权 → Root 命令拦截：严格（默认，仅白名单）、放宽（任意命令，但拦截格式化/刷机/重启/清数据等不可逆操作）、完全放宽（不按命令内容拦截）。档位只对 root 通道生效；Shizuku/ADB 通用执行器仍按严格档，短信读取三档都需要原生授权。\n",
            "\n== Root command interception modes ==\nSettings → Device permissions → Root command interception: Strict (default, allow-list only), Relaxed (any command, but irreversible operations such as formatting, flashing, reboot or clearing app data stay blocked), Full (no command-content checks). Modes apply to the root channel only; the generic Shizuku/ADB executor stays strict, and SMS reads still require native authorization in all modes.\n");
    String modeHelp =
        com.deepseekharness.app.util.UiText.choose(
            "\n== 识屏模式（实体 / 虚拟互斥） ==\n用户在 设置 → 设备能力授权 → 识屏 选择：关闭、实体识屏或虚拟识屏，两者不能同时开启。\n实体识屏：/app/ui/* 与 dsha-android 工具可用；需要无障碍。虚拟识屏：/app/vscreen/* 与 dsha-vscreen 工具可用；需要 Root / Shizuku / ADB 任一可用通道；只在设置里切换到虚拟识屏、或已开启但虚拟屏未运行（STOPPED）时再次选择，才尝试创建；App 启动/恢复、崩溃或关闭后不会自动重建，创建失败会在设置页提示错误码；无障碍只增强节点与中文输入。\n模式关闭返回 SCREEN_MODE_OFF；实体模式下调虚拟屏返回 SCREEN_MODE_VIRTUAL_OFF，虚拟模式下调实体主屏返回 SCREEN_MODE_PHYSICAL_OFF。这表示容器没有拿到这块屏的授权：照原话告诉用户去哪里切换，不要改用另一块屏，也不要重试。/app/ui/task-end 收尾不受模式限制。\n",
            "\n== Screen mode (physical / virtual, mutually exclusive) ==\nThe user chooses Off, Physical screen or Virtual screen in Settings → Device permissions → Screen; both cannot be on at once.\nPhysical: /app/ui/* and the dsha-android tools work; requires accessibility. Virtual: /app/vscreen/* and the dsha-vscreen tools work; requires a usable Root / Shizuku / ADB channel; creation is attempted only when switching the mode to Virtual, or re-selecting while the mode is on but the screen is stopped (STOPPED); it is not recreated after app start, recovery or close, and a failed creation shows an error code in Settings; accessibility only adds nodes and Unicode input.\nOff returns SCREEN_MODE_OFF; virtual-screen calls in physical mode return SCREEN_MODE_VIRTUAL_OFF and main-screen calls in virtual mode return SCREEN_MODE_PHYSICAL_OFF. This means the container has no authorization for that screen: tell the user where to switch, never use the other screen instead and do not retry. /app/ui/task-end cleanup is not restricted by the mode.\n");
    String privHelp =
        com.deepseekharness.app.util.UiText.choose(
            "\n== 实体屏特权执行（实验；设置→设备能力授权→特权执行，默认关闭）==\n不依赖无障碍：受管特权 Core 镜像 display 0 并注入 tap/swipe/key。只在当前手机任务期间可用（先 /app/ui/task-begin，并带真实 taskSessionId/turnId）；权限要求：能力开关 + 目标应用屏幕确认允许；敏感应用与无法核验的目标一律 NOT_SENT。\n/app/priv/info  状态：displayId=0、generation、frameSeq、目标包、输入后端、frameConfig\n/app/priv/frame?since=  取帧并签发一次性 observationId；同帧只回 unchanged\n/app/priv/config?maxWidth=320..1920&jpegQuality=30..90&maxFps=1..10\n/app/priv/tap?x=&y=&frameSeq=&observationId=&generation=&waitMs=0..500\n/app/priv/swipe?x1=&y1=&x2=&y2=&ms=1..3000&frameSeq=&observationId=&generation=\n/app/priv/key?keycode=3|4|66|67&frameSeq=&observationId=&generation=\n/app/priv/benchmark?n=1..20&x=&y=&waitMs=  连续动作，返回 roundTripMs/inputMs/frameWaitMs 的 p50/p95/max 与 frameConfig\n/app/priv/close  关闭 Core\n坐标使用回执 width×height 图像空间。动作回执带新帧与新 observation，可直接做下一步，不要每步重新取帧。凭据 30 秒、一次性、绑定 generation/目标包；EXECUTION_UNKNOWN 不自动重放；失败不回退无障碍。默认帧配置 maxWidth=1280 / jpegQuality=60 / maxFps=6。\n",
            "\n== Physical-screen privileged input (experimental; Settings → Device permissions → Privileged input, off by default) ==\nNo accessibility dependency: the managed privileged Core mirrors display 0 and injects tap/swipe/key. It only works during the current phone task (call /app/ui/task-begin first and pass the real taskSessionId/turnId); gates: capability switch + screen-control permission for the target app; sensitive apps and unverifiable targets are always NOT_SENT.\n/app/priv/info  status: displayId=0, generation, frameSeq, target package, input backend, frameConfig\n/app/priv/frame?since=  capture a frame and issue a one-use observationId; the same frame returns unchanged only\n/app/priv/config?maxWidth=320..1920&jpegQuality=30..90&maxFps=1..10\n/app/priv/tap?x=&y=&frameSeq=&observationId=&generation=&waitMs=0..500\n/app/priv/swipe?x1=&y1=&x2=&y2=&ms=1..3000&frameSeq=&observationId=&generation=\n/app/priv/key?keycode=3|4|66|67&frameSeq=&observationId=&generation=\n/app/priv/benchmark?n=1..20&x=&y=&waitMs=  repeated actions; returns p50/p95/max of roundTripMs/inputMs/frameWaitMs plus frameConfig\n/app/priv/close  close the Core\nCoordinates are in the receipt image space (width x height). An action receipt carries a new frame and a new observation, so the next action can follow directly without re-capturing. Credentials last 30 s, are single-use and bound to generation/target package; EXECUTION_UNKNOWN is never replayed and failures never fall back to accessibility. Defaults: maxWidth=1280 / jpegQuality=60 / maxFps=6.\n");
    String extdevHelp =
        com.deepseekharness.app.util.UiText.choose(
            "\n== 外接设备（USB-ADB 主机，实验性） ==\n先在 设置 → 设备能力授权 打开「外接设备（实验性）」开关并启用「允许 Root Shell」；没有 su 时明确不可用，不会改用本机 shell。这是对另一台 Android 设备的独立通道，与本机 su / Shizuku / ADB 三通道语义无关。\n/app/extdev/status   能力开关 / root 状态 / transport（usb-root 或 wireless-root）/ 是否已绑定目标\n/app/extdev/discover  列出宿主 USB 主机口设备（含 9008 等非 adb 模式）与本次会话 adb 设备；adb 不可用时返回 adbUnavailable 原因\n/app/extdev/inspect?serial=…   只读读取目标 product / fingerprint / epoch，作为 bind 的输入\n/app/extdev/bind?serial=…&product=…&fingerprint=…&epoch=…   绑定唯一目标；必须经用户原生确认，之后每次操作仍校验目标代次\n/app/extdev/import?path=/sdcard/Download/xxx.apk   从共享 Download 复制到 App 私有导入槽（≤4 GiB），返回 file / name / size / sha256 / imageType\n/app/extdev/preflight?file=导入返回的file&partition=boot   只读预检与刷写计划（dry-run），不写目标\n/app/extdev/operate?operation=…&partition=…&value=…   已开放：status / devices / shell（只读诊断命令）/ reboot / connect / pair / getvar；screenshot 与 flash / sideload / set_active 未开放；L1 及以上需原生确认，L3 直接拒绝；加 observe=1 时动作执行后在同一请求内附 settle 与 observation（事件采样，UNKNOWN 不附）\n安装  operation=install&value=导入返回的file   专用确认先显示摘要、大小与目标代次，确认后才安装；文件或目标在确认后变化即拒绝\n/app/extdev/watch?mode=events&since=<digest>&samples=6   只读观察/等待页面变化：在同一个 adb 会话里连续采样目标事件，返回 state（CHANGED / UNCHANGED / UNCERTAIN / OBSERVED）、sequence、timestampMs、foregroundPackage / foregroundActivity、digest 与 settle；mode=window 用 dumpsys window 做单次权威采样（state=OBSERVED），samples 2–12 默认 6，quiet 默认 2；启动后 250ms 内重复调用返回 NOT_SENT / RATE_LIMITED。只读、不需要确认、不写目标，digest 不含屏幕文本。\n/app/extdev/forget   清除绑定\n失败返回 NOT_SENT / CONFIRMATION_REQUIRED / [POLICY_BLOCKED] / [EXECUTION_UNKNOWN]：未发送的不重试，UNKNOWN 先核对目标实际状态，不切换通道、不自动重放。\n",
            "\n== External device (USB-ADB host, experimental) ==\nTurn on External device (experimental) and Allow Root Shell in Settings → Device permissions first; without su it is explicitly unavailable and never falls back to the host shell. This is an independent channel to another Android device and does not change the host su / Shizuku / ADB channel semantics.\n/app/extdev/status   capability switch / root state / transport (usb-root or wireless-root) / whether a target is bound\n/app/extdev/discover  list host USB-port devices (including non-adb modes such as 9008) and adb devices for this session; adbUnavailable carries the reason when adb cannot start\n/app/extdev/inspect?serial=…   read target product / fingerprint / epoch (read-only) as bind input\n/app/extdev/bind?serial=…&product=…&fingerprint=…&epoch=…   bind the single target; requires the user native confirmation, and every later operation still re-checks the target epoch\n/app/extdev/import?path=/sdcard/Download/x.apk   copy from shared Download into the app-private import slot (≤4 GiB); returns file / name / size / sha256 / imageType\n/app/extdev/preflight?file=returned-file&partition=boot   read-only preflight and flash plan (dry-run); never writes the target\n/app/extdev/operate?operation=…&partition=…&value=…   available: status / devices / shell (read-only diagnostics) / reboot / connect / pair / getvar; screenshot and flash / sideload / set_active are not open; L1 and above require native confirmation, L3 is rejected; observe=1 attaches settle and an observation after the action inside the same request (event sampling; never attached for UNKNOWN)\ninstall  operation=install&value=returned-file   the dedicated confirmation shows digest, size and target epoch first; it installs only after confirmation, and refuses when the file or target changed meanwhile\n/app/extdev/watch?mode=events&since=<digest>&samples=6   read-only watch for page changes: samples the target event buffer several times inside one adb session and returns state (CHANGED / UNCHANGED / UNCERTAIN / OBSERVED), sequence, timestampMs, foregroundPackage / foregroundActivity, digest and settle; mode=window takes one authoritative dumpsys window sample (state=OBSERVED), samples 2–12 (default 6), quiet default 2; a repeat call within 250 ms returns NOT_SENT / RATE_LIMITED. Read-only, no confirmation, never writes the target, and digest carries no screen text.\n/app/extdev/forget   clear the binding\nFailures return NOT_SENT / CONFIRMATION_REQUIRED / [POLICY_BLOCKED] / [EXECUTION_UNKNOWN]: never retry what was not sent, and for UNKNOWN check the target actual state instead of switching channels or replaying.\n");
    String senseHelp =
        com.deepseekharness.app.util.UiText.choose(
            "\n== 紧凑识屏预览（ScreenSense） ==\n/app/sense/state?taskSessionId=...&turnId=...&geom=0|1&max=200&full=1\n该端点复用手机任务、无障碍授权、窗口代次和 owner 围栏，只返回紧凑树状态：连续观察默认回增量——mode=delta 只列变化行，mode=same 表示与上次相同，变化超过三分之一、窗口/尺寸/焦点/来源变化或加 full=1 时回全量 mode=full；encodedState 的内容由 mode 决定（全量或 D 开头的差异）。返回的 k*** 是阅读顺序位置编号，只用于预览与差异展示，不是动作凭据。每行为 `id 角色 \"标签\" [标志+动作]`，标志 !不可用 *已选 %焦点 ~密码，动作 c点击 l长按 f/b滚动 o聚焦 t输入，无标志无动作时省略。回复另含 source、quality（ok/partial/empty）、partial、readMs、ageMs（-1 为未知）；ageMs 只表示快照采集后经过的时间，不证明界面未变。动作仍使用最新 /app/ui/tree 或 dump 返回的 treeId/nodeId；Android 接受动作不等于业务状态已变化，不自动重放 UNKNOWN。\n",
            "\n== Compact screen preview (ScreenSense) ==\n/app/sense/state?taskSessionId=...&turnId=...&geom=0|1&max=200&full=1\nThis endpoint reuses the phone task, accessibility authorization, window generation and owner fences and returns the compact tree state only: consecutive reads default to a delta (mode=delta lists changed lines only, mode=same when nothing changed, mode=full when more than one third changed, when window/size/focus/source changed, or with full=1); the content of encodedState depends on mode (the full state, or a diff starting with D); k*** ids are reading-order positions for preview and diff display only and are not action credentials. Each line is `id role \"label\" [flags+actions]`: flags ! disabled, * checked, % focused, ~ password; actions c click, l long-click, f/b scroll, o focus, t set-text; the token is omitted when both are empty. The reply also carries source, quality (ok/partial/empty), partial, readMs and ageMs (-1 = unknown); ageMs is time since capture and does not prove the screen is unchanged. Actions still use treeId/nodeId from a fresh /app/ui/tree or dump; Android accepting an action does not prove a business change, and UNKNOWN is never replayed automatically.\n");
    String taskHelp =
        com.deepseekharness.app.util.UiText.choose(
            "\n== 当前对话手机任务 ==\n用户明确要求手机操作时，先 /app/ui/task-begin；包括只启动应用的任务。每次请求携带真实工具环境的 taskSessionId=$DSH_PHONE_SESSION_ID 与 turnId=$DSH_PHONE_TURN_ID；不猜测或复制其它会话身份。begin 只显示当前对话状态和建立任务围栏，不授予系统权限、不自动批准动作。需要读屏再 dump/tree/screenshot；任务窗口会先隐藏，窗口可见状态变化后必须重新观察。成功 begin 后，任务完成或放弃时在 finally 收尾中调用一次 /app/ui/task-end，完成围栏；正常完成会关闭任务窗口，但用户在本机展开并暂停的任务会保留控制窗供明确继续或停止，不取消模型对话；本轮结束后不再重新 begin。完成收尾不证明 UNKNOWN 动作已成功或已退出，不自动重放。普通讨论、引用或翻译不开始手机任务。\n",
            "\n== Phone task in the current conversation ==\nWhen the user explicitly requests phone actions, call /app/ui/task-begin first, including app-launch-only tasks. Every request carries the real tool environment taskSessionId=$DSH_PHONE_SESSION_ID and turnId=$DSH_PHONE_TURN_ID; never invent or copy another conversation identity. Begin displays this conversation state and establishes the task boundary; it grants no system permission or automatic approval. Read dump/tree/screenshot when observation is needed; the task window is hidden first, and any visibility change requires a fresh observation. After successful begin, call /app/ui/task-end once in final cleanup when the task is completed or abandoned. End finishes this task and normally closes its window without cancelling the model conversation; if the user expanded and paused the task locally, the control window remains for an explicit Continue or Stop; do not begin again in the same finished turn. Cleanup does not prove an UNKNOWN action succeeded or exited; never replay automatically. Discussion, quotations and translations do not start phone tasks.\n");
    String historyHelp =
        com.deepseekharness.app.util.UiText.choose(
            "\n== 屏幕活动历史（仅实体主屏手机任务） ==\n/app/ui/history?taskSessionId=...&turnId=...&since=&limit=20\n手机任务期间自动在内存记录实体主屏活动：目标切换、用户点按/滚动、内容变化与助手动作；约 120 秒 / 64 条，任务结束、暂停或无障碍断开立即清空，不落盘、不进备份或诊断日志。任务未开始、已结束或暂停时 events 为空。since 传上次返回的 nextSince 增量续读（事件序号，不是毫秒时间戳），limit 默认 20、最大 100；truncated=true 表示后面还有。M1 不返回图片，frames 为空数组。每个 /app/ui/* 的 JSON 回执自动附带 recentActivity（上次回执之后最多 3 条）；任务未开始时为空数组。\n",
            "\n== Screen activity history (physical main screen, phone task only) ==\n/app/ui/history?taskSessionId=...&turnId=...&since=&limit=20\nWhile the phone task is active the app keeps an in-memory record of physical-screen activity: target changes, user taps and scrolls, content changes and assistant actions; about 120 seconds / 64 events, cleared immediately on task-end, pause or accessibility disconnect, and never written to disk, backups or diagnostic logs. events is empty when the task has not started, has ended or is paused. Pass the previous nextSince as since for incremental reads (event sequence, not a millisecond timestamp); limit defaults to 20 and maxes out at 100; truncated=true means more events remain. M1 returns no images; frames is an empty array. Every JSON /app/ui/* receipt automatically includes recentActivity (at most 3 events since the previous receipt); it is an empty array when no task has started.\n");
    String safHelp =
        com.deepseekharness.app.util.UiText.choose(
            "\n== 跨应用文件访问（SAF；先到 设置→设备能力授权→跨应用文件访问 授权）==\n/app/saf/trees   已授权目录：id / app / label / readable / writable / valid\n/app/saf/ls?tree=$ID&path=/files&limit=200\n/app/saf/stat?tree=$ID&path=/files/x.db\n/app/saf/read?tree=$ID&path=/files/x.txt&max=262144&offset=0   文本原样返回；二进制加 base64=1；offset 越界返回 OFFSET_OUT_OF_RANGE\n/app/saf/write?tree=$ID&path=/files/x.txt&text=…      小文本直接写（≤16 KiB；更大的用 push）\n/app/saf/push?tree=$ID&path=/files/x.db&src=/root/x.db    容器文件→目标（≤32 MiB）\n/app/saf/pull?tree=$ID&path=/files/x.db&dest=/root/x.db   目标→容器文件（≤32 MiB）\n/app/saf/mkdir?tree=$ID&path=/files/new   ·   /app/saf/rm?tree=$ID&path=/files/x.txt&confirm=1\n只认 /app/saf/trees 里的 id，不接受直接给 URI/authority。写与删要求该目录在设置里开着「允许写入」，否则 READ_ONLY；只给写权限的树会被拒绝（WRITE_ONLY，路径解析需要读权限）。rm 是两段式 + 一次性 token：先不带 confirm 调用获取 NEEDS_CONFIRM、目标/子项数与 token，再对同一路径带 confirm=<token>（60 秒有效）才会删除；confirm=1 一律返回 CONFIRM_REQUIRED，未知 token 同样 CONFIRM_REQUIRED。\n目标应用没启动/无响应时返回 PROVIDER_ERROR 或 TIMEOUT：先请用户打开那个应用再试。授权失效返回 PERMISSION_DENIED，让用户回设置页移除后重选。\n路径一律相对授权根；不许 ..；大文件先 pull 到容器改完再 push，别直接手改二进制。\n",
            "\n== Cross-app file access (SAF; grant a folder in Settings → Device permissions → Cross-app files first) ==\n/app/saf/trees   granted folders: id / app / label / readable / writable / valid\n/app/saf/ls?tree=$ID&path=/files&limit=200\n/app/saf/stat?tree=$ID&path=/files/x.db\n/app/saf/read?tree=$ID&path=/files/x.txt&max=262144&offset=0   text is returned as-is; add base64=1 for binary; an out-of-range offset returns OFFSET_OUT_OF_RANGE\n/app/saf/write?tree=$ID&path=/files/x.txt&text=…      write small text directly (≤16 KiB; use push for more)\n/app/saf/push?tree=$ID&path=/files/x.db&src=/root/x.db    container file → target (≤32 MiB)\n/app/saf/pull?tree=$ID&path=/files/x.db&dest=/root/x.db   target → container file (≤32 MiB)\n/app/saf/mkdir?tree=$ID&path=/files/new   ·   /app/saf/rm?tree=$ID&path=/files/x.txt&confirm=1\nOnly ids from /app/saf/trees are accepted; never pass a URI or authority directly. Writes and deletes require \"allow assistant writes\" for that folder, otherwise READ_ONLY; write-only trees are rejected (WRITE_ONLY, path resolution needs read access). rm uses two-step confirmation with a one-time token: call without confirm to get NEEDS_CONFIRM plus the target/child count and a token, then call again with confirm=<token> for the same path (valid for 60 seconds). confirm=1 always returns CONFIRM_REQUIRED; an unknown token also returns CONFIRM_REQUIRED.\nPROVIDER_ERROR or TIMEOUT means the target app is not running or not responding: ask the user to open it and retry. PERMISSION_DENIED means the grant is gone: remove it in Settings and pick the folder again.\nPaths are always relative to the granted root; .. is rejected. For large files, pull into the container, edit, then push; do not edit binary files through the bridge.\n");
    return com.deepseekharness.app.util.UiText.format(
                "DSHA 3090 桥端点清单（BRIDGE_PROTOCOL=%s）\n凭据由宿主写入 /root/.dsh/.bridge_headers；使用 curl -H @/root/.dsh/.bridge_headers，不把 token 写进 URL 或命令参数。\n带中文/空格的参数一律用 -G --data-urlencode，别手写 URL 编码。\n\n== 屏幕操作（无障碍服务，不需要 ADB/Shizuku）==\n坐标触控仅限物理display 0：先 /app/ui/screenshot，返回 coordinatesAvailable:true 且含 observationId 才可用；dump/tree不签发像素凭据。ID限最新截图、30秒、一次手势；画面/窗口/遮挡/代次变化即拒绝。\n文字/节点动作返回 SCREEN_ACTION_SUBMITTED、actionAccepted:true、effectVerified:false 只代表Android接受动作，不证明WebView DOM已变。动作端点加 observe=1（MCP 动作工具默认带上）时，成功后同一次请求会先等界面稳定（首个事件≤800ms、静默250ms、总上限2.5s，回执 settle 字段如实记录），再返回新生成的 observation（新 treeId/nodeId 与文字树）；坐标动作只回新树、不返回截图，坐标仍须重新 /app/ui/screenshot。观察失败只回 observationUnavailable，不改写动作状态；UNKNOWN 仍不得重放。\n读屏  curl -s -H @/root/.dsh/.bridge_headers \"127.0.0.1:3090/app/ui/dump\"\n      → 每行语义节点附bounds；boundsHaveArea:false没有可触摸面积，不能取中心点触控\n点按  curl -s -G 127.0.0.1:3090/app/ui/tap --data-urlencode \"text=设置\" -H @/root/.dsh/.bridge_headers\n      → 优先按文字点：控件位置随滚动/动画变，文字不变。坐标触控须先截图取得 observationId（dump不签发），再用 ?observationId=截图返回的ID&x=&y=\n语义滚动 先 /app/ui/tree 或 dump 取 treeId、nodeId 和 supportedActions；/app/ui/node?treeId=返回的树ID&nodeId=返回的节点ID&action=scroll_forward（或 scroll_backward）。只调用节点列出的动作，一次请求后取新树；无障碍接受不代表实际已滚动，不自动改用滑动或重放未知结果。\n输入  curl -s -G 127.0.0.1:3090/app/ui/input --data-urlencode \"text=内容\" -H @/root/.dsh/.bridge_headers\n      → 填到当前焦点框；没有焦点先 tap 一下输入框\n按键  /app/ui/key?name=back  （back/home/recents/notifications/quicksettings/lock）\n长按  /app/ui/tap?observationId=截图返回的ID&x=540&y=1200&gesture=long_press&ms=600\n双击  /app/ui/tap?observationId=截图返回的ID&x=540&y=1200&gesture=double_tap（同一Gesture/一次许可）\n当前只支持单指触控；多指/缩放不支持。\n滑动  /app/ui/swipe?observationId=截图返回的ID&x1=500&y1=1500&x2=500&y2=500&ms=300\n截屏  /app/ui/screenshot   → 存 PNG 到应用截图目录并返回路径（不回 base64）\n节奏：点按/输入带 observe=1 时回执已附新树，直接用新 treeId/nodeId 继续；没有 observation 或出现 SCREEN_TARGET_CHANGED 时再 dump，别凭记忆连点。\n\n== 独立虚拟屏（Android 11+） ==\n/app/vscreen/create?orientation=portrait  创建虚拟屏；也可选 landscape\n/app/vscreen/status  查询 coreInstance、generation、displayId 和状态\n$CORE 取自 create/status 返回的 coreInstance；$GEN 取自当前 see 或 tree 的 generation，$FRAME 取自该 see 的 frameSeq，$NODE 取自该 tree 的 nodeId。不要混用不同 Core 或不同观察的身份。\n/app/vscreen/launch?coreInstance=$CORE&package=com.example.app  启动已安装应用；此端点不接受 generation\n/app/vscreen/see?coreInstance=$CORE  获取新画面及 generation、frameSeq\n空屏：新建的虚拟屏可能没有窗口；see 失败且 nextAction=LAUNCH_APP_FIRST（VSCREEN_NO_WINDOW）时先 launch，WAIT_AND_SEE_AGAIN（FRAME_PENDING）稍等再 see，REPORT_TO_USER（FRAME_STALLED）告诉用户。只读失败可安全重看，legacyError 保留 FRAME_NOT_READY。\n/app/vscreen/tap?coreInstance=$CORE&generation=$GEN&frameSeq=$FRAME&x=10&y=20\ntap/swipe/touch/type/key 需要 coreInstance、generation 和最新 see 的 frameSeq；坐标按显示尺寸换算。\n/app/vscreen/tree?coreInstance=$CORE  获取当前树及 nodeId、generation\n/app/vscreen/node?coreInstance=$CORE&generation=$GEN&nodeId=$NODE&action=click  语义节点动作；不要求 frameSeq，使用新树中的节点\n/app/vscreen/editor?coreInstance=$CORE&generation=$GEN  读取当前输入框；edit/submit 还需 editorId，edit 需 text；均不要求 frameSeq\nanchor 使用原 see 的 coreInstance、generation、frameSeq、observationId 和全尺寸整数 x/y；anchored-click 使用同一组身份及 anchorId。\n/app/vscreen/close?coreInstance=$CORE  关闭对应 Core；无运行中或待清理的 Core 时可省略身份\n应用启动或动作后重新观察；UNKNOWN 不自动重放。\n\n== 设备与应用 ==\n/app/device                     机型/系统/电量/网络/屏幕/存储/内存\n/app/apps                       全部已装应用，分用户应用与系统应用；可加 q/limit/user=1 筛选显示\n/app/launch?pkg=com.tencent.mm  启动应用\n/app/clip                       读剪贴板（需 App 在前台，系统限制）\n/app/clip + text=…              写剪贴板\n/app/readfile?path=/…          读取容器路径或当前用户共享存储的目录或文本（最多 256 KiB）；受 Android 权限限制，凭据区及跨域符号链接不可读；format=image 返回 8 MiB 内静态 PNG/JPEG/WebP 数据，动图及 GIF 不支持；/proc/self/status 单独提供当前桥进程诊断\n设备文件写入请走下方受保护的设备 shell；普通 Download 文件可操作，DCIM/Pictures/Android/data/obb 只读。\n\n== 与用户交互 ==\n/app/ask?options=继续|取消 + q=…  弹窗阻塞等回答（最多三个选项）\n/app/notify?title=… + text=…      通知栏\n/app/toast + text=…               App 内提示\n/app/vibrate?ms=300               震动（长任务跑完叫醒用户）\n/app/share（text= 或 path=）      分享到其它应用\n/app/open?url=https://…           打开链接\n/app/export?path=/root/report.md  把产物交给用户 → 落 Download/DSHA；凭据区不可导出\n建议：需要用户拍板用 /app/ask 而不是干等；长任务结束用 notify 或 vibrate 叫人；\n产出报告用 /app/export，别只留在容器里。\n\n== 传感器与位置（默认关闭，需用户在配置页勾选）==\n/app/location（加 fresh=1 强制重新定位，可能等数秒）\n/app/sensors 列表 · /app/sensor?name=light 读值\n（light 环境光 lux / accel / gyro / magnet / pressure / proximity /\n gravity / rotation 姿态四元数 / steps 开机后步数）\n/app/torch?on=1 手电\n这三类返回 DISABLED（用户没开该能力）或 NO_PERMISSION（没授系统权限）时，\n照原话告诉用户去哪开，不要重试 —— 重试不会让开关自己变。\n\n== 元信息 ==\n/app/version                          桥协议版本 + App 版本（特性检测用）\n/app/help                             本清单\n/app/entry-target                     读取桌面图标启动后进入的页面；加 set=main 或 set=web 修改（仅影响下次从桌面图标启动）\n\n== 插件状态 ==\n/app/plugins                          读回上次上报的加载状态\n/app/plugins?loaded=a,b&failed=c      上报（插件侧用）\n\n== 设备 shell（自动选择 root / Shizuku / ADB）==\n/root/dsh-bin/adb-shell \"命令\"        用 id 核验实际身份\n包装命令不存在时：python3 /root/.dsh/adb-shell.py \"命令\"\n短信只允许当前 Android 用户的 content query --uri content://sms，默认关闭；设置 → 设备能力授权可开启或撤销。\n短信预授权可能返回正文及验证码；不允许发送、修改或删除，Android 仍可拒绝访问。Shizuku 不执行此敏感查询。\n仅执行已识别的单条命令；允许读取各目录和明确路径的普通文件操作。禁止脚本、管道、重定向、未知命令。\n根目录及系统目录只读；禁止块设备/分区、SELinux、系统设置写入、挂载和刷机操作。\n结束进程前自动刷新全量用户/系统应用清单；普通用户应用直接结束，系统应用与关键进程拦截。\n按完整包名调用 am force-stop / killall / pkill -x；kill 正数 PID 会核对 UID 后按包名停止。\n策略拦截返回 [POLICY_BLOCKED]/126；root 和旧确认开关不能放行。不可用其它解释器或 UI 绕过。\n报连不上/未配对：先看上面的 App 层接口能不能办成；确实必须 shell 才请用户到\n设置 → 设备能力授权中连接可用通道，别反复试同一条命令。\n不要用 /root/dsh-bin/adb 或裸 adb —— 那是守卫包装脚本，会失败。\n\n== root（--su）==\n已启用并经 root 管理器授权的 su 可直接执行，无需 ADB 配对；其次使用 Shizuku，再使用 ADB。\n--su 仅在明确需要 root 时使用，须先在设备能力授权页允许；同一设备保护策略始终生效。\n[EXECUTION_UNKNOWN] 表示命令可能已执行，先核对实际状态，不能切换通道或自动重放。\n",
                BRIDGE_PROTOCOL)
            .replace("3090", Integer.toString(bridgePort))
        + modeHelp
        + privHelp
        + taskHelp
        + historyHelp
        + installHelp
        + extdevHelp
        + senseHelp
        + netHelp
        + safHelp
        + rootPolicyHelp;
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
      String format = getParam(queryOf(path), "format", "text");
      if (!format.equals("text") && !format.equals("image")) return "BAD_FORMAT";
      // A fixed diagnostic file, not a general host /proc access rule.
      if (format.equals("text") && com.deepseekharness.app.util.BridgeProcessStatus.isExactPath(p))
        return com.deepseekharness.app.util.BridgeProcessStatus.read();
      if (com.deepseekharness.app.util.BridgeProcessStatus.isExactPath(p)) return "NOT_IMAGE";
      java.io.File f = new java.io.File(p);
      if (!f.isAbsolute()) return com.deepseekharness.app.util.UiText.text("FORBIDDEN: 读取需要绝对路径");
      // 凭据/运行时内部状态不开放给桥读取：读到的内容会回到会话里，
      // 再经 /app/export 就能落到公共目录（真机实测过这条链路）。
      if (com.deepseekharness.app.util.BridgePathPolicy.deniedReadRaw(p))
        return "FORBIDDEN: " + com.deepseekharness.app.util.BridgePathPolicy.reason();
      boolean sharedDomain = com.deepseekharness.app.util.BridgePathPolicy.readSharedDomain(p);
      java.io.File rootfs = null;
      String rootfsCanonical = null;
      String sharedCanonical = null;
      String mediaCanonical = null;
      try {
        rootfs = HarnessController.get(ctx).getProot().getRootfsDir().getCanonicalFile();
        rootfsCanonical = rootfs.getPath();
      } catch (Throwable ignored) {
        // Without a rootfs identity, private host paths remain forbidden.
      }
      try {
        java.io.File shared = android.os.Environment.getExternalStorageDirectory();
        sharedCanonical = shared.getCanonicalPath();
        String user = shared.getName();
        if (user.matches("[0-9]+")) mediaCanonical = "/data/media/" + user;
      } catch (Throwable ignored) {
        // Fail closed if the current user's storage root cannot be established.
      }
      f = com.deepseekharness.app.util.BridgePathPolicy.resolveReadFile(p, rootfs);
      if (f == null) return "FORBIDDEN: " + com.deepseekharness.app.util.BridgePathPolicy.reason();
      String canon;
      try {
        canon = f.getCanonicalPath();
      } catch (Exception e) {
        return com.deepseekharness.app.util.UiText.format("FORBIDDEN: 路径无法解析（%s）", p);
      }
      // 复核 canonical：软链接不能成为读凭据的跳板。
      if (com.deepseekharness.app.util.BridgePathPolicy.deniedReadCanonical(
          canon, rootfsCanonical, sharedCanonical, mediaCanonical, sharedDomain))
        return "FORBIDDEN: " + com.deepseekharness.app.util.BridgePathPolicy.reason();
      // canon 之后用于目录遍历的越界复核，避免同一路径被解析两遍产生竞态。
      if (canon == null || canon.isEmpty())
        return com.deepseekharness.app.util.UiText.format("FORBIDDEN: 路径无法解析（%s）", p);
      // 按用户策略放开可读目录；实际权限仍由 Android 执行，不把拒绝伪装成成功。
      var fs = new com.deepseekharness.app.backup.AndroidBackupFileSystem();
      java.io.File resolved = new java.io.File(canon);
      var identity = fs.stat(resolved);
      if (identity.type.equals("DIRECTORY")) {
        if (format.equals("image")) return "NOT_IMAGE";
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
              || com.deepseekharness.app.util.BridgePathPolicy.deniedReadCanonical(
                  child.getCanonicalPath(),
                  rootfsCanonical,
                  sharedCanonical,
                  mediaCanonical,
                  sharedDomain)) continue;
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
      if (format.equals("image")) {
        if (identity.size > com.deepseekharness.app.util.BridgeImage.MAX_BYTES)
          return "IMAGE_TOO_LARGE";
        byte[] image;
        try (java.io.InputStream in = fs.read(resolved, identity)) {
          try {
            image = com.deepseekharness.app.util.BridgeImage.read(in);
          } catch (java.io.IOException tooLarge) {
            if ("IMAGE_TOO_LARGE".equals(tooLarge.getMessage())) return "IMAGE_TOO_LARGE";
            throw tooLarge;
          }
        }
        String media = com.deepseekharness.app.util.BridgeImage.mediaType(image);
        if (media == null) return "NOT_IMAGE";
        if (!com.deepseekharness.app.util.BridgeImage.isStatic(image, media))
          return "ANIMATED_OR_INVALID_IMAGE";
        if (!com.deepseekharness.app.bridge.BridgeImageDecoder.valid(image, media))
          return "INVALID_IMAGE";
        return com.deepseekharness.app.util.BridgeImage.envelope(
            media, android.util.Base64.encodeToString(image, android.util.Base64.NO_WRAP));
      }
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

  // ================= 外接设备（独立授权、独立目标、结构化操作） =================

  /** 动作后观察固定 4 个样本：够判断末尾 2 个样本稳定，又不把动作回执拖太久。 */
  private static final int EXTERNAL_OBSERVE_ACTION_SAMPLES = 4;

  private com.deepseekharness.app.extdev.ExternalDeviceHost externalDeviceHost() {
    return new com.deepseekharness.app.extdev.ExternalDeviceHost(ctx);
  }

  private String externalDeviceStatus() {
    try {
      return externalDeviceHost().status().toString();
    } catch (Throwable error) {
      return "{\"state\":\"ERROR\",\"error\":\"" + jsonEscape(safeError(error)) + "\"}";
    }
  }

  private String externalDeviceDiscover() {
    try {
      return externalDeviceHost().discover().toString();
    } catch (Throwable error) {
      return "{\"state\":\"ERROR\",\"error\":\"" + jsonEscape(safeError(error)) + "\"}";
    }
  }

  private String externalDeviceInspect(String serial) {
    try {
      return externalDeviceHost().inspectTarget(serial).toString();
    } catch (Throwable error) {
      return "{\"state\":\"ERROR\",\"error\":\"" + jsonEscape(safeError(error)) + "\"}";
    }
  }

  /** 确认前捕获本次请求的目标；请求失效、Web 代次变化或目标改动后，未派发的步骤一律拒绝。 */
  private com.deepseekharness.app.extdev.ExternalDeviceHost externalOperation(
      BridgeRequest request) {
    return new com.deepseekharness.app.extdev.ExternalDeviceHost(
        ctx,
        () ->
            bridgeCurrent(request.scope)
                && request.scope.webGeneration == HarnessController.get(ctx).getWebGeneration());
  }

  private String externalDeviceBind(BridgeRequest request) {
    String serial = request.param("serial", "");
    String product = request.param("product", "");
    String fingerprint = request.param("fingerprint", "");
    String epoch = request.param("epoch", "");
    try {
      long value = Long.parseLong(epoch);
      var host = externalOperation(request);
      String summary =
          "绑定外接目标 "
              + serial
              + " / "
              + product
              + " / 指纹 "
              + (fingerprint.length() > 16 ? fingerprint.substring(0, 16) : fingerprint);
      if (!requestUserConfirm(summary))
        return "{\"state\":\"NOT_SENT\",\"error\":\"CONFIRMATION_REQUIRED\"}";
      org.json.JSONObject candidate =
          new org.json.JSONObject()
              .put("serial", serial)
              .put("product", product)
              .put("fingerprint", fingerprint)
              .put("epoch", value);
      return host.bind(candidate) ? "{\"state\":\"BOUND\"}" : "{\"state\":\"NOT_BOUND\"}";
    } catch (Throwable error) {
      return "{\"state\":\"ERROR\",\"error\":\"" + jsonEscape(safeError(error)) + "\"}";
    }
  }

  private String externalDeviceOperate(BridgeRequest request) {
    String operation = request.param("operation", "");
    String partition = request.param("partition", "");
    String value = request.param("value", "");
    boolean observe = wantsObserve(request);
    try {
      var plan =
          com.deepseekharness.app.util.ExternalDevicePolicy.plan(operation, partition, value);
      if (!plan.allowed())
        return "{\"state\":\"BLOCKED\",\"error\":\"" + jsonEscape(plan.reason) + "\"}";
      if (plan.risk == com.deepseekharness.app.util.ExternalDevicePolicy.Risk.L3)
        return "{\"state\":\"BLOCKED\",\"error\":\"L3 操作未开放\"}";
      var host = externalOperation(request);
      if (plan.operation == com.deepseekharness.app.util.ExternalDevicePolicy.Operation.INSTALL)
        return externalDeviceInstall(host, value, observe);
      if (plan.risk != com.deepseekharness.app.util.ExternalDevicePolicy.Risk.L0) {
        String summary =
            com.deepseekharness.app.util.ExternalDevicePolicy.summary(
                plan,
                host.confirmationTarget() == null ? "" : host.confirmationTarget().describe(),
                "",
                "",
                0);
        if (!requestUserConfirm(summary))
          return "{\"state\":\"NOT_SENT\",\"error\":\"CONFIRMATION_REQUIRED\"}";
      }
      org.json.JSONObject result = host.operate(operation, partition, value, true);
      // observe=1：动作已派发（非 UNKNOWN）时附动作后的新观察；观察失败只写 observationUnavailable。
      if (observe && !"UNKNOWN".equals(result.optString("state")) && !result.has("error"))
        host.attachObservation(result, EXTERNAL_OBSERVE_ACTION_SAMPLES);
      return result.toString();
    } catch (Throwable error) {
      return "{\"state\":\"ERROR\",\"error\":\"" + jsonEscape(safeError(error)) + "\"}";
    }
  }

  /** 只读观察通道：不改目标、不需要确认；since 是上次返回的 digest，用来等待下一次页面变化。 */
  private String externalDeviceWatch(BridgeRequest request) {
    try {
      int samples =
          observationInt(
              request.param("samples", ""),
              com.deepseekharness.app.util.ExternalWatchPolicy.DEFAULT_SAMPLES);
      int quiet =
          observationInt(
              request.param("quiet", ""),
              com.deepseekharness.app.util.ExternalWatchPolicy.DEFAULT_QUIET_SAMPLES);
      return externalOperation(request)
          .watch(request.param("mode", "events"), request.param("since", ""), samples, quiet)
          .toString();
    } catch (Throwable error) {
      return "{\"state\":\"ERROR\",\"error\":\"" + jsonEscape(safeError(error)) + "\"}";
    }
  }

  /** 空串/缺省用 fallback；非法数字返回 -1，交给 watch 的区间校验拒绝。 */
  private static int observationInt(String raw, int fallback) {
    String value = raw == null ? "" : raw.trim();
    if (value.isEmpty()) return fallback;
    try {
      return Integer.parseInt(value);
    } catch (NumberFormatException invalid) {
      return -1;
    }
  }

  private static boolean wantsObserve(BridgeRequest request) {
    String value = request.param("observe", "0");
    return "1".equals(value) || "true".equalsIgnoreCase(value);
  }

  /**
   * 安装专用确认：先只读描述（种类、风险级、SHA-256、大小、目标与代次），确认框显示这些值；确认后 install()
   * 按同一摘要与代次复核，文件或目标在确认后变化即拒绝。每次安装都确认，没有「本任务内不再询问」。
   */
  private String externalDeviceInstall(
      com.deepseekharness.app.extdev.ExternalDeviceHost host, String importName, boolean observe) {
    try {
      org.json.JSONObject info = host.describeInstall(importName);
      String summary =
          String.format(
              java.util.Locale.ROOT,
              "外接目标 %s（%s%s）：安装 %s（%s，%s）\nSHA-256 %s\n%d bytes%s",
              info.getString("serial"),
              info.getString("product"),
              info.getBoolean("wireless") ? "，无线" : "，USB",
              info.getString("name"),
              info.getString("kind"),
              info.getString("level"),
              info.getString("sha256"),
              info.getLong("size"),
              "L2".equals(info.getString("level")) ? "\n模块在开机早期执行，坏模块可能导致目标无法开机" : "");
      if (!requestUserConfirm(summary))
        return "{\"state\":\"NOT_SENT\",\"error\":\"CONFIRMATION_REQUIRED\"}";
      org.json.JSONObject installed =
          host.install(importName, info.getString("sha256"), info.getLong("epoch"));
      // 结果未知时不附观察，避免把“可能已执行”当成可继续操作的状态。
      if (observe && !"UNKNOWN".equals(installed.optString("state")))
        host.attachObservation(installed, EXTERNAL_OBSERVE_ACTION_SAMPLES);
      return installed.toString();
    } catch (Throwable error) {
      return "{\"state\":\"ERROR\",\"error\":\"" + jsonEscape(safeError(error)) + "\"}";
    }
  }

  private String externalDevicePreflight(BridgeRequest request) {
    try {
      java.io.File image = externalDeviceHost().importedFile(request.param("file", ""));
      return externalDeviceHost().preflight(request.param("partition", ""), image).toString();
    } catch (Throwable error) {
      return "{\"state\":\"ERROR\",\"error\":\"" + jsonEscape(safeError(error)) + "\"}";
    }
  }

  private String externalDeviceImport(BridgeRequest request) {
    try {
      return externalDeviceHost().importImage(request.param("path", "")).toString();
    } catch (Throwable error) {
      return "{\"state\":\"ERROR\",\"error\":\"" + jsonEscape(safeError(error)) + "\"}";
    }
  }

  private String externalDeviceForget() {
    try {
      return externalDeviceHost().forgetTarget()
          ? "{\"state\":\"FORGOTTEN\"}"
          : "{\"state\":\"ERROR\"}";
    } catch (Throwable error) {
      return "{\"state\":\"ERROR\",\"error\":\"" + jsonEscape(safeError(error)) + "\"}";
    }
  }

  // ================= App 层能力（不需要 ADB / Shizuku，agent 直接调） =================

  /** /app/net ：宿主网络与容器 resolv.conf 的只读诊断（不发 DNS 查询、不含本机 IP）。 */
  private String appNet() {
    return com.deepseekharness.app.device.NetworkDiagnostics.render(
        ctx,
        new com.deepseekharness.app.core.ConfigStore(ctx).getDnsMode(),
        java.util.List.of(new java.io.File(ctx.getFilesDir(), "linux/ubuntu").getAbsolutePath()));
  }

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
  private String appLaunch(String path, RequestScope scope) {
    var controller = HarnessController.get(ctx);
    long generation = controller.getWebGeneration(), revision = uiGrant.revision();
    String packageName = getParam(queryOf(path), "pkg", "");
    if (packageName.isEmpty()) return "NO_PKG";
    var capability =
        screenRequestEvidence(
            scope,
            "launch",
            new com.deepseekharness.app.bridge.AppLaunchTargetEvidence(packageName));
    bindLaunchToCurrentTask(path, scope, capability);
    return com.deepseekharness.app.bridge.AppDeviceActions.launch(
        ctx,
        getParam(queryOf(path), "pkg", ""),
        (action, pkg) ->
            switch (screenAuthorization(scope, action, pkg, null, capability)) {
              case ALLOWED -> null;
              case DENIED -> "USER_REJECTED";
              case NO_ANSWER -> "USER_NO_ANSWER";
              case UNAVAILABLE -> "USER_CONFIRM_UNAVAILABLE";
            },
        () ->
            screenBridgeCurrent(scope)
                && generation == controller.getWebGeneration()
                && revision == uiGrant.revision()
                && controller.isWebRunning()
                && !controller.isStarting()
                && !controller.isStopping()
                && !controller.isUserStopped(),
        DshaApp.from(ctx).capabilityBroker(),
        capability);
  }

  /**
   * 调用方带上当前手机任务的 taskSessionId/turnId 时，把启动请求绑定到这一轮：确认走悬浮助手的确认窗
   * （可用「本轮允许此应用」），而不是容易错过的通知通道。身份不匹配或没有任务时保持原样（不借用别的会话）。
   */
  private void bindLaunchToCurrentTask(
      String path,
      RequestScope scope,
      com.deepseekharness.app.capabilities.CapabilityRequest capability) {
    bindActionToCurrentTask(path, scope, capability, true);
  }

  /** Bind a non-screen capability to the exact local conversation turn when supplied. */
  private void bindActionToCurrentTask(
      String path,
      RequestScope scope,
      com.deepseekharness.app.capabilities.CapabilityRequest capability,
      boolean screen) {
    String query = queryOf(path);
    String taskSessionId = getParam(query, "taskSessionId", "");
    String turnId = getParam(query, "turnId", "");
    if (taskSessionId.isEmpty()
        || turnId.isEmpty()
        || scope == null
        || scope.run == null
        || !(screen ? screenBridgeCurrent(scope) : bridgeCurrent(scope))) return;
    var coordinator = com.deepseekharness.app.control.PhoneControlCoordinator.get(ctx);
    var runtime = com.deepseekharness.app.core.ScreenRuntimeIdentity.epoch(ctx);
    var binding =
        new com.deepseekharness.app.control.ControlTaskAuthority.Binding(
            com.deepseekharness.app.control.ControlTaskAuthority.Owner.LOCAL_DSH,
            scope.run.runId + ":" + runtime.instanceId,
            taskSessionId,
            turnId);
    var state = coordinator.snapshot();
    if (state.paused() || !binding.equals(state.binding())) return;
    try {
      coordinator.bindRequest(capability, coordinator.lease(binding));
    } catch (IllegalStateException changed) {
      // 任务刚好换轮或暂停：保持未绑定，走原有确认通道。
    }
  }

  private static String capabilityTurnKey(String path) {
    String query = queryOf(path);
    String session = getParam(query, "taskSessionId", "");
    String turn = getParam(query, "turnId", "");
    return session.isEmpty() || turn.isEmpty() ? "" : session + "\0" + turn;
  }

  private String appClip(String path, RequestScope scope) {
    try {
      String query = queryOf(path);
      String rawText = com.deepseekharness.app.util.Query.raw(query, "text");
      String text = getParam(query, "text", "");
      // /app/clip?text=... is a write even when the value is empty; an omitted text reads.
      if (rawText != null)
        return com.deepseekharness.app.bridge.AppDeviceActions.clipboard(
            ctx, mainHandler, text, true, () -> true);
      if (!bridgeCurrent(scope)) return "[ERR] BRIDGE_RUN_CHANGED";
      var grants = new com.deepseekharness.app.core.DeviceGrants(ctx);
      var mode = grants.clipboardReadMode();
      if (!com.deepseekharness.app.util.ClipboardReadPolicy.readsAllowed(mode))
        return com.deepseekharness.app.util.ClipboardReadPolicy.ERROR_OFF
            + ": "
            + com.deepseekharness.app.util.ClipboardReadPolicy.USER_ACTION;
      var request =
          capabilityRequest(
              scope,
              "clipboard.read",
              "读取剪贴板（内容不会写入 DSHA 日志）",
              new com.deepseekharness.app.bridge.ClipboardTargetEvidence(),
              grants.clipboardReadRevision());
      if (request == null) return "DEVICE_CONFIRM_UNAVAILABLE";
      var broker = DshaApp.from(ctx).capabilityBroker();
      if (mode == com.deepseekharness.app.util.ClipboardReadPolicy.Mode.ASK) {
        bindActionToCurrentTask(path, scope, request, false);
        var outcome = capabilityConfirmation(path, scope, request);
        if (outcome != ConfirmationOutcome.ALLOWED)
          return switch (outcome) {
            case DENIED -> "DEVICE_USER_REJECTED";
            case NO_ANSWER -> "DEVICE_USER_NO_ANSWER";
            default -> "DEVICE_CONFIRM_UNAVAILABLE";
          };
      }
      if (!broker.authorize(request)) return "DEVICE_CONFIRM_UNAVAILABLE";
      var permit = broker.claimDispatch(request);
      if (permit == null) return "DEVICE_CONFIRM_UNAVAILABLE";
      return com.deepseekharness.app.bridge.AppDeviceActions.clipboard(
          ctx,
          mainHandler,
          text,
          false,
          () ->
              bridgeCurrent(scope)
                  && new com.deepseekharness.app.core.DeviceGrants(ctx).clipboardReadMode()
                      != com.deepseekharness.app.util.ClipboardReadPolicy.Mode.OFF
                  && permit.isCurrent());
    } catch (Throwable error) {
      return "ERROR: " + safeError(error);
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

  /**
   * /app/install?path=/root/x.apk ：把容器或共享存储里的 APK 交给宿主系统安装器。
   *
   * <p>桥只复制快照并拉起系统安装界面，安装与否由用户在系统界面确认；未允许「安装未知应用」时打开对应设置页。
   * 覆盖安装 DSHA 自身会结束整个 App，必须走应用内更新页。
   */
  private String appInstall(String path) {
    try {
      String src = getParam(queryOf(path), "path", "");
      if (src.isEmpty()) return "NO_PATH";
      if (com.deepseekharness.app.util.BridgePathPolicy.deniedReadRaw(src))
        return "FORBIDDEN: " + com.deepseekharness.app.util.BridgePathPolicy.reason();
      java.io.File rootfs = null;
      String rootfsCanonical = null, sharedCanonical = null, mediaCanonical = null;
      try {
        rootfs = HarnessController.get(ctx).getProot().getRootfsDir().getCanonicalFile();
        rootfsCanonical = rootfs.getPath();
      } catch (Throwable ignored) {
        // 无法确认正式 rootfs 时，只可能接受已确认当前用户的共享存储域。
      }
      try {
        java.io.File shared = android.os.Environment.getExternalStorageDirectory();
        sharedCanonical = shared.getCanonicalPath();
        String user = shared.getName();
        if (user.matches("[0-9]+")) mediaCanonical = "/data/media/" + user;
      } catch (Throwable ignored) {
        // 不能确认当前用户共享根时，不猜测其他用户或宿主目录。
      }
      java.io.File candidate =
          com.deepseekharness.app.util.BridgePathPolicy.resolveReadFile(src, rootfs);
      if (candidate == null)
        return "FORBIDDEN: " + com.deepseekharness.app.util.BridgePathPolicy.reason();
      // 只解析一次：域检查与之后的描述符快照必须绑定同一个 canonical 对象。
      java.io.File f = candidate.getCanonicalFile();
      if (!com.deepseekharness.app.util.BridgeApkInstall.sourceAllowed(
          src, f.getPath(), rootfsCanonical, sharedCanonical, mediaCanonical))
        return "FORBIDDEN: " + com.deepseekharness.app.util.BridgePathPolicy.reason();
      if (!f.isFile()) return "NOT_FOUND: " + SensitiveData.redact(src);
      if (!com.deepseekharness.app.util.BridgeApkInstall.apkName(candidate.getName()))
        return com.deepseekharness.app.util.UiText.choose(
            "NOT_APK: 只支持单个 .apk 文件（.apks/.xapk 分包不支持）",
            "NOT_APK: only a single .apk file is supported (.apks/.xapk bundles are not)");
      if (!com.deepseekharness.app.util.BridgeApkInstall.sizeAllowed(f.length()))
        return "TOO_LARGE: " + f.length();
      if (!ctx.getPackageManager().canRequestPackageInstalls()) {
        // 系统权限只能由用户在设置页授予；不自动重试，授权后由调用方重新请求。
        Intent settings =
            new Intent(
                    android.provider.Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                    android.net.Uri.parse("package:" + ctx.getPackageName()))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        boolean notified = false;
        if (installLaunch() == com.deepseekharness.app.util.BridgeApkInstall.Launch.NOTIFICATION) {
          notified =
              postInstallNotification(
                  "unknown-sources",
                  com.deepseekharness.app.util.UiText.choose(
                      "需要允许 DSHA 安装应用", "Allow DSHA to install apps"),
                  com.deepseekharness.app.util.UiText.choose(
                      "点此打开「安装未知应用」设置", "Tap to open \"Install unknown apps\""),
                  settings);
        } else {
          try {
            ctx.startActivity(settings);
          } catch (Throwable ignored) {
          }
        }
        // 整句选择，不在 choose 参数里拼接（check-ui-i18n）。
        return notified
            ? com.deepseekharness.app.util.UiText.choose(
                "NO_PERMISSION: 未允许 DSHA 安装未知应用。已发通知，用户点通知打开设置页：请在「安装未知应用」中允许 DSHA，然后重新请求一次。",
                "NO_PERMISSION: DSHA is not allowed to install unknown apps. Posted a notification that opens the settings page: allow DSHA under \"Install unknown apps\", then request once more.")
            : com.deepseekharness.app.util.UiText.choose(
                "NO_PERMISSION: 未允许 DSHA 安装未知应用。已尝试打开设置页：请在「安装未知应用」中允许 DSHA，然后重新请求一次。",
                "NO_PERMISSION: DSHA is not allowed to install unknown apps. Opened the settings page: allow DSHA under \"Install unknown apps\", then request once more.");
      }
      var fs = new com.deepseekharness.app.backup.AndroidBackupFileSystem();
      java.io.File cache = ctx.getCacheDir().getCanonicalFile();
      java.io.File slots = fs.child(cache, "bridge-installs");
      if (fs.stat(slots).type.equals("MISSING")) fs.directory(slots);
      long now = System.currentTimeMillis();
      pruneInstallSlots(fs, slots, now);
      java.io.File owned =
          fs.child(
              slots,
              com.deepseekharness.app.util.BridgeApkInstall.slotName(
                  now, java.util.UUID.randomUUID().toString()));
      fs.directory(owned);
      java.io.File snapshot = fs.child(owned, "payload.apk");
      com.deepseekharness.app.util.BridgeFileSnapshot.copy(
          fs, f, snapshot, com.deepseekharness.app.util.BridgeApkInstall.SIZE_LIMIT);
      android.content.pm.PackageInfo info =
          ctx.getPackageManager().getPackageArchiveInfo(snapshot.getPath(), 0);
      if (info == null || info.packageName == null) {
        removeInstallSlot(fs, owned);
        return com.deepseekharness.app.util.UiText.choose(
            "BAD_APK: 系统无法解析该安装包", "BAD_APK: the system could not parse this package");
      }
      String version =
          info.packageName
              + " "
              + safeDisplay(info.versionName == null ? "" : info.versionName)
              + " ("
              + info.getLongVersionCode()
              + ")";
      if (com.deepseekharness.app.util.BridgeApkInstall.selfPackage(
          info.packageName, ctx.getPackageName())) {
        removeInstallSlot(fs, owned);
        return com.deepseekharness.app.util.UiText.choose(
            "FORBIDDEN: 不能经桥覆盖安装 DSHA 自身（会结束 App 与全部会话），请使用应用内更新页。",
            "FORBIDDEN: DSHA cannot overwrite itself through the bridge (that ends the App and every session); use the in-app update page.");
      }
      android.net.Uri uri =
          androidx.core.content.FileProvider.getUriForFile(
              ctx, ctx.getPackageName() + ".updates", snapshot);
      Intent install =
          new Intent(Intent.ACTION_VIEW)
              .setDataAndType(uri, "application/vnd.android.package-archive")
              .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_ACTIVITY_NEW_TASK);
      install.setClipData(android.content.ClipData.newRawUri("DSHA install", uri));
      if (installLaunch() == com.deepseekharness.app.util.BridgeApkInstall.Launch.NOTIFICATION) {
        // 无 AOSP 后台启动豁免：改由用户点通知打开，点按属于用户操作，系统不会拦截。
        if (!postInstallNotification(
            info.packageName,
            com.deepseekharness.app.util.UiText.choose("点此安装应用", "Tap to install app"),
            version,
            install)) {
          removeInstallSlot(fs, owned);
          return com.deepseekharness.app.util.UiText.choose(
              "NO_NOTIFICATION: DSHA 在后台且通知被关闭，无法拉起安装器。请把 DSHA 切到前台或允许通知后再请求一次。",
              "NO_NOTIFICATION: DSHA is in the background and notifications are off, so the installer cannot be opened. Bring DSHA to the foreground or allow notifications, then request once more.");
        }
        return String.format(
            java.util.Locale.ROOT,
            com.deepseekharness.app.util.UiText.choose(
                "OK: notification 已发出安装通知 %s；用户点通知后打开系统安装器并确认。",
                "OK: notification posted for %s; the user taps it to open the system installer and confirm."),
            version);
      }
      try {
        ctx.startActivity(install);
      } catch (android.content.ActivityNotFoundException missing) {
        removeInstallSlot(fs, owned);
        return com.deepseekharness.app.util.UiText.text("ERROR: 系统没有可用的安装器");
      }
      return String.format(
          java.util.Locale.ROOT,
          com.deepseekharness.app.util.UiText.choose(
              "OK: installer 已请求系统安装器 %s；需用户在系统界面确认。厂商「后台弹出界面」开关关闭时仍可能不显示。",
              "OK: installer requested for %s; the user must confirm in the system screen. A vendor \"background pop-up\" switch may still hide it."),
          version);
    } catch (Throwable e) {
      return "ERROR: " + safeError(e);
    }
  }

  /** 按 AOSP 后台启动 Activity 豁免选择直接打开或发通知；判定逻辑在 util 并有单测。 */
  private com.deepseekharness.app.util.BridgeApkInstall.Launch installLaunch() {
    return com.deepseekharness.app.util.BridgeApkInstall.launch(
        ForegroundActivity.current() != null,
        DshaAccessibilityService.connected(),
        OverlayController.permitted(ctx),
        Build.VERSION.SDK_INT);
  }

  /** 发一条点按后打开 target 的通知；通知被关闭时返回 false，由调用方如实告知。 */
  private boolean postInstallNotification(String tag, String title, String text, Intent target) {
    try {
      NotificationManager nm =
          (NotificationManager) ctx.getSystemService(Context.NOTIFICATION_SERVICE);
      if (nm == null
          || !androidx.core.app.NotificationManagerCompat.from(ctx).areNotificationsEnabled())
        return false;
      NotificationChannel ch =
          new NotificationChannel(
              "dsh_install_channel",
              com.deepseekharness.app.util.UiText.choose("应用安装", "App installs"),
              NotificationManager.IMPORTANCE_HIGH);
      ch.setDescription(
          com.deepseekharness.app.util.UiText.choose(
              "智能体请求安装应用时，点通知打开系统安装器",
              "Tap to open the system installer when the agent requests an install"));
      nm.createNotificationChannel(ch);
      if (nm.getNotificationChannel("dsh_install_channel").getImportance()
          == NotificationManager.IMPORTANCE_NONE) return false;
      // 每个包（或设置页）用独立 tag + requestCode，后一次不覆盖前一次的 PendingIntent。
      PendingIntent open =
          PendingIntent.getActivity(
              ctx,
              tag.hashCode(),
              target,
              PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
      nm.notify(
          tag,
          com.deepseekharness.app.util.NotificationIds.BRIDGE_INSTALL,
          new NotificationCompat.Builder(ctx, "dsh_install_channel")
              .setSmallIcon(R.drawable.ic_launch)
              .setContentTitle(safeDisplay(title))
              .setContentText(safeDisplay(text))
              .setPriority(NotificationCompat.PRIORITY_HIGH)
              .setContentIntent(open)
              .setAutoCancel(true)
              // 快照超过保留期会被清理，通知随之过期，不留指向已删文件的入口。
              .setTimeoutAfter(com.deepseekharness.app.util.BridgeApkInstall.RETAIN_MS)
              .build());
      return true;
    } catch (Throwable e) {
      return false;
    }
  }

  /** 清理超过保留期的安装快照；只动本端点格式的目录，失败不影响本次安装。 */
  private static void pruneInstallSlots(
      com.deepseekharness.app.backup.AndroidBackupFileSystem fs, java.io.File slots, long now) {
    try {
      for (String name :
          com.deepseekharness.app.util.BridgeApkInstall.expiredSlots(fs.list(slots), now))
        removeInstallSlot(fs, fs.child(slots, name));
    } catch (Throwable ignored) {
    }
  }

  private static void removeInstallSlot(
      com.deepseekharness.app.backup.AndroidBackupFileSystem fs, java.io.File owned) {
    try {
      if (!fs.stat(owned).type.equals("DIRECTORY")) return;
      for (String child : fs.list(owned)) {
        java.io.File file = fs.child(owned, child);
        if (fs.stat(file).type.equals("FILE")) fs.delete(file);
      }
      if (fs.list(owned).isEmpty()) fs.delete(owned);
    } catch (Throwable ignored) {
    }
  }

  /** /app/open?url=...：安全 scheme 直接打开，tel/mailto/market 先走原生确认。 */
  private String appOpen(String path, RequestScope scope) {
    try {
      String url = getParam(queryOf(path), "url", "");
      if (url.isEmpty()) return "NO_URL";
      var kind = com.deepseekharness.app.util.ExternalOpenPolicy.classify(url);
      if (kind == com.deepseekharness.app.util.ExternalOpenPolicy.Kind.FORBIDDEN)
        return com.deepseekharness.app.util.UiText.text(
            "FORBIDDEN: 只支持 http/https/geo/tel/mailto/market 链接");
      if (!bridgeCurrent(scope)) return "[ERR] BRIDGE_RUN_CHANGED";
      android.net.Uri uri = android.net.Uri.parse(url);
      if (kind == com.deepseekharness.app.util.ExternalOpenPolicy.Kind.CONFIRM) {
        var request =
            capabilityRequest(
                scope,
                "external.open",
                com.deepseekharness.app.util.UiText.format("打开外部链接（%s）", uri.getScheme()),
                new com.deepseekharness.app.bridge.ExternalOpenTargetEvidence(
                    url, uri.getScheme().toLowerCase(java.util.Locale.ROOT)),
                new com.deepseekharness.app.core.DeviceGrants(ctx).clipboardReadRevision());
        if (request == null) return "DEVICE_CONFIRM_UNAVAILABLE";
        var broker = DshaApp.from(ctx).capabilityBroker();
        bindActionToCurrentTask(path, scope, request, false);
        var outcome = capabilityConfirmation(path, scope, request);
        if (outcome != ConfirmationOutcome.ALLOWED)
          return switch (outcome) {
            case DENIED -> "DEVICE_USER_REJECTED";
            case NO_ANSWER -> "DEVICE_USER_NO_ANSWER";
            default -> "DEVICE_CONFIRM_UNAVAILABLE";
          };
        if (!broker.authorize(request) || broker.claimDispatch(request) == null)
          return "DEVICE_CONFIRM_UNAVAILABLE";
      }
      android.content.Intent i =
          new android.content.Intent(android.content.Intent.ACTION_VIEW, uri)
              .addCategory(android.content.Intent.CATEGORY_BROWSABLE)
              .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK);
      ctx.startActivity(i);
      return com.deepseekharness.app.util.UiText.format("OK: 已打开 %s", safeDisplay(url));
    } catch (Throwable e) {
      return "[RESULT_UNKNOWN] EXTERNAL_OPEN_RESULT_UNKNOWN: " + safeError(e);
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

  // ---------------------------------------------------------------- 跨应用文件访问（SAF）

  /** /app/saf/trees：跨应用文件访问页里授权的目录；agent 只认这里的 id。 */
  private String appSafTrees() {
    try {
      org.json.JSONArray array = new org.json.JSONArray();
      for (com.deepseekharness.app.saf.SafGrants.Grant grant :
          com.deepseekharness.app.saf.SafGrants.listLight(ctx))
        array.put(
            new org.json.JSONObject()
                .put("id", grant.id)
                .put("app", grant.app)
                .put("label", grant.label)
                .put("readable", grant.readable)
                .put("writable", grant.writable)
                .put("valid", grant.valid));
      return new org.json.JSONObject().put("trees", array).toString();
    } catch (Throwable error) {
      return "ERROR: " + safeError(error);
    }
  }

  private String appSafList(BridgeRequest request) {
    try {
      com.deepseekharness.app.saf.SafTree tree = safTree(request, false);
      String path = getParam(request.query, "path", "");
      int limit =
          safInt(
              getParam(request.query, "limit", ""),
              com.deepseekharness.app.saf.SafTree.MAX_ENTRIES);
      com.deepseekharness.app.saf.SafTree.Page page = tree.list(path, limit);
      org.json.JSONArray array = new org.json.JSONArray();
      for (com.deepseekharness.app.saf.SafTree.Entry entry : page.entries)
        array.put(safEntry(entry));
      return new org.json.JSONObject()
          .put("path", com.deepseekharness.app.saf.SafTree.normalize(path))
          .put("count", page.entries.size())
          .put("truncated", page.truncated)
          .put("entries", array)
          .toString();
    } catch (com.deepseekharness.app.saf.SafTree.Failure failure) {
      return "ERROR: " + failure.getMessage();
    } catch (Throwable error) {
      return "ERROR: " + safeError(error);
    }
  }

  private String appSafStat(BridgeRequest request) {
    try {
      com.deepseekharness.app.saf.SafTree tree = safTree(request, false);
      String path = getParam(request.query, "path", "");
      return safEntry(tree.stat(path))
          .put("path", com.deepseekharness.app.saf.SafTree.normalize(path))
          .toString();
    } catch (com.deepseekharness.app.saf.SafTree.Failure failure) {
      return "ERROR: " + failure.getMessage();
    } catch (Throwable error) {
      return "ERROR: " + safeError(error);
    }
  }

  private String appSafRead(BridgeRequest request) {
    try {
      com.deepseekharness.app.saf.SafTree tree = safTree(request, false);
      String path = getParam(request.query, "path", "");
      String normalized = com.deepseekharness.app.saf.SafTree.normalize(path);
      int max =
          safInt(getParam(request.query, "max", ""), com.deepseekharness.app.saf.SafTree.MAX_READ);
      long offset = safLong(getParam(request.query, "offset", ""), 0L);
      byte[] data = tree.read(path, offset, max);
      if ("1".equals(getParam(request.query, "base64", "")))
        return new org.json.JSONObject()
            .put("base64", android.util.Base64.encodeToString(data, android.util.Base64.NO_WRAP))
            .put("bytes", data.length)
            .put("path", normalized)
            .toString();
      if (!com.deepseekharness.app.saf.SafTree.looksText(data))
        return new org.json.JSONObject()
            .put("binary", true)
            .put("bytes", data.length)
            .put("path", normalized)
            .put("hint", "binary: 用 base64=1，或 /app/saf/pull 落成文件")
            .toString();
      return new String(data, java.nio.charset.StandardCharsets.UTF_8);
    } catch (com.deepseekharness.app.saf.SafTree.Failure failure) {
      return "ERROR: " + failure.getMessage();
    } catch (Throwable error) {
      return "ERROR: " + safeError(error);
    }
  }

  private String appSafWrite(BridgeRequest request) {
    try {
      com.deepseekharness.app.saf.SafTree tree = safTree(request, true);
      String path = getParam(request.query, "path", "");
      String text = getParam(request.query, "text", null);
      if (text == null) return "ERROR: NO_CONTENT (text= 写小文件；大文件用 /app/saf/push?src=)";
      byte[] data = text.getBytes(java.nio.charset.StandardCharsets.UTF_8);
      // 查询串有界（BRIDGE firstLine 96 KiB，最坏百分号编码约 3 倍），因此文本直写按 16 KiB 兜底。
      if (data.length > 16 * 1024)
        return "ERROR: TOO_LARGE (text 上限 16 KiB；更大的内容用 /app/saf/push?src=)";
      boolean create = !"0".equals(getParam(request.query, "create", "1"));
      long written = tree.write(path, data, create);
      return new org.json.JSONObject()
          .put("ok", true)
          .put("bytes", written)
          .put("path", com.deepseekharness.app.saf.SafTree.normalize(path))
          .toString();
    } catch (com.deepseekharness.app.saf.SafTree.Failure failure) {
      return "ERROR: " + failure.getMessage();
    } catch (Throwable error) {
      return "ERROR: " + safeError(error);
    }
  }

  private String appSafMkdir(BridgeRequest request) {
    try {
      com.deepseekharness.app.saf.SafTree tree = safTree(request, true);
      String path = getParam(request.query, "path", "");
      tree.mkdir(path);
      return new org.json.JSONObject()
          .put("ok", true)
          .put("path", com.deepseekharness.app.saf.SafTree.normalize(path))
          .toString();
    } catch (com.deepseekharness.app.saf.SafTree.Failure failure) {
      return "ERROR: " + failure.getMessage();
    } catch (Throwable error) {
      return "ERROR: " + safeError(error);
    }
  }

  private String appSafRemove(BridgeRequest request) {
    try {
      com.deepseekharness.app.saf.SafTree tree = safTree(request, true);
      String path = getParam(request.query, "path", "");
      if (path == null || path.trim().isEmpty() || "/".equals(path.trim()))
        return "ERROR: BAD_PATH (empty path; root is never deletable)";
      String confirm = getParam(request.query, "confirm", "");
      if (confirm == null || confirm.isEmpty()) {
        com.deepseekharness.app.saf.SafTree.PendingDelete pending = tree.delete(path);
        return "ERROR: NEEDS_CONFIRM (confirm="
            + pending.token
            + "；"
            + pending.hint
            + "；60 秒内对同一路径再次调用才会删除)";
      }
      if ("1".equals(confirm)) {
        // confirm=1 不再触发探测或删除：必须使用首次探测返回的一次性 token。
        return "ERROR: CONFIRM_REQUIRED (confirm=1 不再放行；先不带 confirm 调用获取 token)";
      }
      tree.deleteConfirmed(path, confirm);
      return new org.json.JSONObject()
          .put("ok", true)
          .put("path", com.deepseekharness.app.saf.SafTree.normalize(path))
          .toString();
    } catch (com.deepseekharness.app.saf.SafTree.Failure failure) {
      if ("CONFIRM_REQUIRED".equals(failure.code))
        return "ERROR: CONFIRM_REQUIRED (先不带 confirm 调用获取一次性 token；confirm=1 不再放行)";
      if ("CONFIRM_EXPIRED".equals(failure.code)) return "ERROR: CONFIRM_EXPIRED (token 已过期，请重新探测)";
      if ("CONFIRM_MISMATCH".equals(failure.code))
        return "ERROR: CONFIRM_MISMATCH (token 与当前 tree/path/目标不一致，请重新探测)";
      return "ERROR: " + failure.getMessage();
    } catch (Throwable error) {
      return "ERROR: " + safeError(error);
    }
  }

  /** 目标 → 容器文件：大文件读取的正路（不走 base64，不受 query 长度限制）。 */
  private String appSafPull(BridgeRequest request) {
    boolean acquired = false;
    try {
      com.deepseekharness.app.saf.SafTree tree = safTree(request, false);
      if (!SAF_TRANSFERS.tryAcquire(15, java.util.concurrent.TimeUnit.SECONDS))
        return "ERROR: BUSY (too many concurrent SAF transfers)";
      acquired = true;
      String path = getParam(request.query, "path", "");
      java.io.File target = safGuestFile(getParam(request.query, "dest", ""));
      if (target.isDirectory()) return "ERROR: IS_DIRECTORY";
      java.io.File parent = target.getParentFile();
      if (parent == null) return "ERROR: BAD_PATH";
      long limit = com.deepseekharness.app.saf.SafTree.MAX_WRITE;
      // 单次读上限由 SafTree 兜到 MAX_READ，分段大小必须与它一致，否则第一段就判成末段。
      int chunk = com.deepseekharness.app.saf.SafTree.MAX_READ;
      com.deepseekharness.app.saf.SafTree.Entry source = tree.stat(path);
      if (source.directory) return "ERROR: IS_DIRECTORY";
      long declared = source.size;
      if (declared > limit) return "ERROR: TOO_LARGE (source exceeds 32 MiB)";
      String tempName = "." + target.getName() + ".saf-tmp-" + java.util.UUID.randomUUID();
      long total = 0;
      // 先写临时文件，核对后才在同一个已锚定目录 fd 内原子发布：祖先目录被换链也不会改目标。
      // 目录缺失时由 ensureAnchoredDirectory 在父目录 fd 下逐级创建，不再按路径 mkdirs。
      try (com.deepseekharness.app.saf.SafLocalFiles.AnchoredDirectory directory =
          com.deepseekharness.app.saf.SafLocalFiles.ensureAnchoredDirectory(
              parent, safPathCheck())) {
        pruneSafTemps(directory, target.getName());
        int previousMode = -1;
        try {
          android.system.StructStat existing =
              android.system.Os.stat("/proc/self/fd/" + directory.fd + "/" + target.getName());
          previousMode = existing.st_mode & 0777;
        } catch (android.system.ErrnoException missing) {
          // 目标不存在：新文件按 createInDirectory 的 0600 落盘。
        }
        boolean published = false;
        try {
          try (java.io.OutputStream output =
              com.deepseekharness.app.saf.SafLocalFiles.createInDirectory(
                  directory, tempName, safPathCheck())) {
            if (declared >= 0) {
              while (total < declared) {
                byte[] data = tree.read(path, total, (int) Math.min(chunk, declared - total));
                if (data.length == 0) throw new java.io.IOException("UNEXPECTED_EOF");
                output.write(data);
                total += data.length;
              }
              // Provider 可能把 COLUMN_SIZE 报小（含 0）：再读 1 字节确认真的到 EOF。
              if (tree.read(path, total, 1).length > 0)
                throw new java.io.IOException("SIZE_MISMATCH");
            } else {
              // Provider 不给大小：按上限拉，读完还要多读 1 字节确认没有超限。
              while (total < limit) {
                byte[] data = tree.read(path, total, (int) Math.min(chunk, limit - total));
                if (data.length == 0) break;
                output.write(data);
                total += data.length;
                if (data.length < chunk) break;
              }
              if (total >= limit && tree.read(path, limit, 1).length > 0)
                throw new java.io.IOException("TOO_LARGE");
            }
          }
          if (previousMode >= 0) {
            try {
              android.system.Os.chmod(
                  "/proc/self/fd/" + directory.fd + "/" + tempName, previousMode);
            } catch (android.system.ErrnoException ignored) {
              // 权限复制失败不阻断发布；新文件按 createInDirectory 的 0600 落盘。
            }
          }
          com.deepseekharness.app.saf.SafLocalFiles.publishInDirectory(
              directory, tempName, target.getName());
          published = true;
        } finally {
          if (!published) {
            try {
              com.deepseekharness.app.saf.SafLocalFiles.deleteInDirectory(directory, tempName);
            } catch (Throwable ignored) {
              // 已确认目录 fd 下没有可发布的半截文件就够；清理失败不掩盖原始错误。
            }
          }
        }
      }
      return new org.json.JSONObject()
          .put("ok", true)
          .put("bytes", total)
          .put("dest", target.getPath())
          .toString();
    } catch (com.deepseekharness.app.saf.SafTree.Failure failure) {
      return "ERROR: " + failure.getMessage();
    } catch (Throwable error) {
      return "ERROR: " + safeError(error);
    } finally {
      if (acquired) SAF_TRANSFERS.release();
    }
  }

  /** 容器文件 → 目标：改别人数据的主路（≤32 MiB，流式写入不整份驻留内存）。 */
  private String appSafPush(BridgeRequest request) {
    boolean acquired = false;
    try {
      com.deepseekharness.app.saf.SafTree tree = safTree(request, true);
      if (!SAF_TRANSFERS.tryAcquire(15, java.util.concurrent.TimeUnit.SECONDS))
        return "ERROR: BUSY (too many concurrent SAF transfers)";
      acquired = true;
      String path = getParam(request.query, "path", "");
      java.io.File source = safGuestFile(getParam(request.query, "src", ""));
      long length = source.length();
      if (length > com.deepseekharness.app.saf.SafTree.MAX_WRITE)
        return "ERROR: TOO_LARGE (" + length + " > 32 MiB)";
      long written;
      try (java.io.InputStream input =
          com.deepseekharness.app.saf.SafLocalFiles.openRead(source, safPathCheck())) {
        written = tree.writeStream(path, input, length, true);
      }
      return new org.json.JSONObject()
          .put("ok", true)
          .put("bytes", written)
          .put("path", com.deepseekharness.app.saf.SafTree.normalize(path))
          .toString();
    } catch (com.deepseekharness.app.saf.SafTree.Failure failure) {
      return "ERROR: " + failure.getMessage();
    } catch (Throwable error) {
      return "ERROR: " + safeError(error);
    } finally {
      if (acquired) SAF_TRANSFERS.release();
    }
  }

  /** 清理已锚定目录里超过 1 小时的旧 .saf-tmp 残留；只动本端点自己的命名格式。 */
  private static void pruneSafTemps(
      com.deepseekharness.app.saf.SafLocalFiles.AnchoredDirectory directory, String targetName) {
    long cutoff = System.currentTimeMillis() - 60L * 60 * 1000;
    String prefix = "." + targetName + ".saf-tmp-";
    for (String name : com.deepseekharness.app.saf.SafLocalFiles.listInDirectory(directory)) {
      if (!name.startsWith(prefix)) continue;
      try {
        android.system.StructStat stat =
            android.system.Os.lstat("/proc/self/fd/" + directory.fd + "/" + name);
        if (stat.st_mtime * 1000L > cutoff) continue;
        com.deepseekharness.app.saf.SafLocalFiles.deleteInDirectory(directory, name);
      } catch (android.system.ErrnoException | java.io.IOException ignored) {
        // 残留清理是尽力而为，不阻断本次传输。
      }
    }
  }

  /** 解析 tree=id：只认授权簿里的短 id，绝不接受调用方直接给 authority / URI。 */
  private com.deepseekharness.app.saf.SafTree safTree(BridgeRequest request, boolean writable)
      throws com.deepseekharness.app.saf.SafTree.Failure {
    String id = getParam(request.query, "tree", "");
    com.deepseekharness.app.saf.SafGrants.Lookup lookup =
        com.deepseekharness.app.saf.SafGrants.lookup(ctx, id);
    if (lookup.failure != null)
      throw new com.deepseekharness.app.saf.SafTree.Failure(lookup.failure, id);
    com.deepseekharness.app.saf.SafGrants.Grant grant = lookup.grant;
    if (grant == null) throw new com.deepseekharness.app.saf.SafTree.Failure("NO_GRANT", id);
    if (!grant.valid)
      throw new com.deepseekharness.app.saf.SafTree.Failure("PERMISSION_DENIED", id);
    // 只写授权（Provider 没给读位）也必须能 push/mkdir/rm；读操作单独要求 read 位。
    if (writable) {
      if (!grant.canWrite)
        throw new com.deepseekharness.app.saf.SafTree.Failure("PERMISSION_DENIED", id);
      if (!grant.writable) throw new com.deepseekharness.app.saf.SafTree.Failure("READ_ONLY", id);
    } else if (!grant.readable) {
      throw new com.deepseekharness.app.saf.SafTree.Failure("PERMISSION_DENIED", id);
    }
    return new com.deepseekharness.app.saf.SafTree(ctx.getContentResolver(), grant.uri);
  }

  /**
   * 打开后的真实路径复核：safGuestFile 的 canonical 判据在「判完再开」之间可能被祖先换链，
   * SafLocalFiles 用内核记录的 /proc/self/fd 真实路径再跑一次 BridgePathPolicy 的 guest 判据。
   */
  private com.deepseekharness.app.saf.SafLocalFiles.PathCheck safPathCheck() {
    String rootfs = null;
    try {
      rootfs =
          safRootfsFixture != null
              ? safRootfsFixture.getCanonicalPath()
              : HarnessController.get(ctx).getProot().getRootfsDir().getCanonicalPath();
    } catch (Throwable ignored) {
      // 取不到 rootfs 时按拒绝处理（deniedGuestView(null) 为 true），不放过未验证路径。
    }
    final String root = rootfs;
    return realPath ->
        com.deepseekharness.app.util.BridgePathPolicy.deniedGuestView(realPath, root);
  }

  /** guest 路径（/root/x）→ 容器内真实文件；只接受 rootfs 内的普通路径，凭据区一律拒绝。 */
  private java.io.File safGuestFile(String raw) throws java.io.IOException {
    if (raw == null || raw.trim().isEmpty()) throw new java.io.IOException("NO_PATH");
    String value = raw.trim();
    if (!value.startsWith("/")) throw new java.io.IOException("ABSOLUTE_ONLY");
    if (com.deepseekharness.app.util.BridgePathPolicy.denied(value))
      throw new java.io.IOException("FORBIDDEN");
    java.io.File rootfs =
        safRootfsFixture != null
            ? safRootfsFixture
            : HarnessController.get(ctx).getProot().getRootfsDir().getCanonicalFile();
    String root = rootfs.getCanonicalPath();
    java.io.File file = new java.io.File(rootfs, value.substring(1)).getCanonicalFile();
    if (!file.getPath().equals(root) && !file.getPath().startsWith(root + java.io.File.separator))
      throw new java.io.IOException("FORBIDDEN");
    if (com.deepseekharness.app.util.BridgePathPolicy.deniedGuestView(file.getPath(), root))
      throw new java.io.IOException("FORBIDDEN");
    // proot --link2symlink 把硬链接落成指向根下 .l2s 实体的软链接；canonical 解析后
    // 会落在 .l2s 里，按路径判据拦不住，这里显式拒绝（读源与 pull 目标都走这道）。
    if (com.deepseekharness.app.saf.SafLocalFiles.link2SymlinkPath(value)
        || com.deepseekharness.app.saf.SafLocalFiles.link2SymlinkPath(file.getPath()))
      throw new java.io.IOException("FORBIDDEN_LINK2SYMLINK");
    return file;
  }

  private static org.json.JSONObject safEntry(com.deepseekharness.app.saf.SafTree.Entry entry)
      throws org.json.JSONException {
    return new org.json.JSONObject()
        .put("name", entry.name)
        .put("dir", entry.directory)
        .put("mime", entry.mime)
        .put("size", entry.size)
        .put("mtime", entry.modified);
  }

  private static int safInt(String raw, int fallback) {
    try {
      return Integer.parseInt(raw.trim());
    } catch (Throwable ignored) {
      return fallback;
    }
  }

  private static long safLong(String raw, long fallback) {
    try {
      return Long.parseLong(raw.trim());
    } catch (Throwable ignored) {
      return fallback;
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

  /** 只请求用户确认（不执行命令），返回是否允许；/confirm 端点用。
   *  通知与弹窗同时发：只走弹窗的话，Activity 一被 pause 用户就再也看不见，
   *  只能干等 60s 超时——这正是「弹窗有时不出现」的由来。（吸收上游 PR#24） */
  private boolean requestUserConfirm(String cmd) {
    return requestUserConfirm(cmd, null);
  }

  /** Generic bridge confirmation; rememberTurn is only exposed when the caller supplies a valid task. */
  private boolean requestUserConfirm(String cmd, boolean[] rememberTurn) {
    final PendingConfirmation pending;
    synchronized (LIFECYCLE) {
      BridgeRun run = activeRun;
      if (!running
          || run == null
          || !LIFECYCLE.isCurrent(run.generation)
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
        return allowed
            && running
            && run != null
            && run.generation == pending.request.generation
            && LIFECYCLE.isCurrent(run.generation);
      }
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      return false;
    } finally {
      synchronized (LIFECYCLE) {
        finishConfirmation(pending);
      }
    }
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
  private ConfirmationOutcome capabilityConfirmation(
      String path,
      RequestScope scope,
      com.deepseekharness.app.capabilities.CapabilityRequest capability) {
    var coordinator = com.deepseekharness.app.control.PhoneControlCoordinator.get(ctx);
    String turnKey = capabilityTurnKey(path);
    var grants = coordinator.capabilityGrants();
    if ("external.open".equals(capability.capability)
        && grants.allows(turnKey, capability.capability, capability.policyRevision))
      return ConfirmationOutcome.ALLOWED;
    boolean[] remember = new boolean[1];
    var outcome = confirmOutcome(scope, capability, capability.action, false, null, remember);
    if (outcome == ConfirmationOutcome.ALLOWED
        && remember[0]
        && "external.open".equals(capability.capability))
      grants.remember(turnKey, capability.capability, capability.policyRevision);
    return outcome;
  }

  private ConfirmationOutcome confirmOutcome(
      RequestScope scope,
      com.deepseekharness.app.capabilities.CapabilityRequest capability,
      String cmd,
      boolean screen,
      com.deepseekharness.app.util.ScreenTarget target) {
    return confirmOutcome(scope, capability, cmd, screen, target, null);
  }

  /** {@code rememberTurn} 非空时回填用户是否选了「本轮允许此应用」。 */
  private ConfirmationOutcome confirmOutcome(
      RequestScope scope,
      com.deepseekharness.app.capabilities.CapabilityRequest capability,
      String cmd,
      boolean screen,
      com.deepseekharness.app.util.ScreenTarget target,
      boolean[] rememberTurn) {
    final PendingConfirmation pending;
    final com.deepseekharness.app.control.ScreenControlConfirmation taskHandler;
    synchronized (LIFECYCLE) {
      BridgeRun run = activeRun;
      if (!running
          || run == null
          || !LIFECYCLE.isCurrent(run.generation)
          || scope.run != run
          || !(screen ? screenBridgeCurrent(scope) : bridgeCurrent(scope)))
        return ConfirmationOutcome.UNAVAILABLE;
      com.deepseekharness.app.control.ControlTaskAuthority.Lease taskLease = null;
      var coordinator = com.deepseekharness.app.control.PhoneControlCoordinator.get(ctx);
      String taskIdentity =
          com.deepseekharness.app.control.ScreenAuthorityBindings.identity(capability);
      if (!taskIdentity.isEmpty()) {
        var state = coordinator.snapshot();
        var binding = state.binding();
        // A bound request cannot fall back to another confirmation channel after losing its task.
        // Unbound commands never borrow whichever conversation happens to be active.
        if (binding == null
            || binding.owner()
                != com.deepseekharness.app.control.ControlTaskAuthority.Owner.LOCAL_DSH
            || state.paused()
            || !binding.connection().equals(run.runId + ":" + capability.runtime.instanceId)
            || !taskIdentity.equals(binding.identity() + "\0" + state.revision())
            || !com.deepseekharness.app.control.ScreenAuthorityBindings.current(capability)
            || !capability.runtime.equals(
                com.deepseekharness.app.core.ScreenRuntimeIdentity.epoch(ctx))
            || !capability.bridgeRunId.equals(run.runId)) return ConfirmationOutcome.UNAVAILABLE;
        taskLease =
            new com.deepseekharness.app.control.ControlTaskAuthority.Lease(
                binding, state.revision());
        taskHandler = coordinator.confirmationHandler();
        if (taskHandler == null) return ConfirmationOutcome.UNAVAILABLE;
      } else taskHandler = null;
      var request =
          confirmations.begin(DshaApp.from(ctx).capabilityBroker(), capability, run.generation);
      if (request == null) return ConfirmationOutcome.UNAVAILABLE;
      pending = new PendingConfirmation(request, screen, target, scope, taskLease);
      pendingConfirm = pending;
    }
    try {
      if (taskHandler != null) {
        var prompt =
            taskHandler.confirm(
                capability,
                target,
                () -> {
                  synchronized (LIFECYCLE) {
                    return currentConfirmation(pending);
                  }
                },
                decision -> {
                  synchronized (LIFECYCLE) {
                    if (!currentConfirmation(pending) || decision.allowed() == null) {
                      // Expiry can make the broker non-current before its blocking waiter wakes.
                      // Retire only this request, including its exact prompt, without a late grant.
                      if (pendingConfirm == pending) finishConfirmation(pending);
                      return;
                    }
                    pending.promptWindow = decision.windowId();
                    pending.rememberTurn = decision.rememberTurn();
                  }
                  resolveConfirm(Boolean.TRUE.equals(decision.allowed()), pending.request.identity);
                });
        synchronized (LIFECYCLE) {
          if (currentConfirmation(pending)) pending.taskPrompt = prompt;
          else prompt.close();
        }
      } else {
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
                    pending.promptWindow = confirmationWindowId(pending.dialog);
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
      }
      boolean allowed = pending.request.await(CONFIRM_TIMEOUT_S, TimeUnit.SECONDS);
      synchronized (LIFECYCLE) {
        BridgeRun run = activeRun;
        if (!(running
            && run != null
            && run.generation == pending.request.generation
            && LIFECYCLE.isCurrent(run.generation))) return ConfirmationOutcome.UNAVAILABLE;
        if (pending.targetChanged) throw new DshaAccessibilityService.TargetChangedException();
        if (rememberTurn != null) rememberTurn[0] = pending.rememberTurn;
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
        && (pending.taskLease == null
            || (pending.screen ? screenBridgeCurrent(pending.scope) : bridgeCurrent(pending.scope))
                && com.deepseekharness.app.control.PhoneControlCoordinator.get(ctx)
                    .current(pending.taskLease)
                && com.deepseekharness.app.control.ScreenAuthorityBindings.current(
                    pending.request.context)
                && com.deepseekharness.app.assistant.AssistantService.stopChannelCurrent(
                    ctx, pending.taskLease.binding()))
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
      if (pending == null
          || !currentConfirmation(pending)
          || pending.resolving
          || !pending.request.identity.equals(identity)) return;
      if (!allow) {
        if (confirmations.resolve(pending.request.generation, identity, false))
          clearConfirmationUi(pending);
        return;
      }
      // Reserve the first decision without waking the HTTP worker under its own confirmation
      // window.
      pending.resolving = true;
      if (Looper.myLooper() == Looper.getMainLooper()) {
        int window = confirmationWindowId(pending.dialog);
        if (window >= 0) pending.promptWindow = window;
      }
      long deadline = android.os.SystemClock.elapsedRealtime() + 1500;
      mainHandler.post(() -> closeAllowedConfirmation(pending, identity, deadline));
    }
  }

  private void closeAllowedConfirmation(
      PendingConfirmation pending, String identity, long deadline) {
    synchronized (LIFECYCLE) {
      if (!currentConfirmation(pending)) return;
      final androidx.appcompat.app.AlertDialog dialog = pending.dialog;
      int window = confirmationWindowId(dialog);
      if (window >= 0) pending.promptWindow = window;
      clearConfirmationUi(pending);
      final int ownWindow = pending.promptWindow;
      // FIFO after the existing dismiss, then wait only by read-only metadata observations.
      mainHandler.post(
          () -> finishAllowedConfirmation(pending, identity, dialog, ownWindow, deadline));
    }
  }

  private static int confirmationWindowId(androidx.appcompat.app.AlertDialog dialog) {
    if (dialog == null || !dialog.isShowing() || dialog.getWindow() == null) return -1;
    android.view.accessibility.AccessibilityNodeInfo node = null;
    try {
      node = dialog.getWindow().getDecorView().createAccessibilityNodeInfo();
      return node == null ? -1 : node.getWindowId();
    } catch (RuntimeException unavailable) {
      // An unknown prompt identity cannot excuse a different application window.
      return -1;
    } finally {
      if (node != null) node.recycle();
    }
  }

  private void finishAllowedConfirmation(
      PendingConfirmation pending,
      String identity,
      androidx.appcompat.app.AlertDialog dialog,
      int promptWindow,
      long deadline) {
    synchronized (LIFECYCLE) {
      if (!currentConfirmation(pending)) return;
      if (android.os.SystemClock.elapsedRealtime() >= deadline) {
        pending.targetChanged = pending.target != null;
        finishConfirmation(pending);
        return;
      }
      boolean waiting = dialog != null && dialog.isShowing();
      if (!waiting && pending.target != null) {
        com.deepseekharness.app.util.ScreenTarget current = null;
        try {
          current = DshaAccessibilityService.observeTarget(pending.target.displayId);
        } catch (RuntimeException unavailable) {
          // Keep the action undispatched while the accessibility window list is unavailable.
        }
        if (!pending.target.matches(current)) {
          waiting =
              current == null
                  || promptWindow >= 0
                      && current.displayId == 0
                      && current.windowId == promptWindow
                      && ctx.getPackageName().equals(current.packageName);
          if (!waiting) {
            pending.targetChanged = true;
            finishConfirmation(pending);
            return;
          }
        }
      }
      if (waiting) {
        mainHandler.postDelayed(
            () -> finishAllowedConfirmation(pending, identity, dialog, promptWindow, deadline), 50);
        return;
      }
      confirmations.resolve(pending.request.generation, identity, true);
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
    var taskPrompt = pending.taskPrompt;
    pending.taskPrompt = null;
    if (taskPrompt != null) taskPrompt.close();
    // The task prompt is the only approval UI for a bound task, including its cleanup.
    if (pending.taskLease != null) return;
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
