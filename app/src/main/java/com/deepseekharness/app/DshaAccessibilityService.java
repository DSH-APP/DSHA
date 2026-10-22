package com.deepseekharness.app;

import android.accessibilityservice.AccessibilityService;
import android.content.Context;
import android.provider.Settings;
import android.text.TextUtils;
import android.util.Log;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;

/**
 * 接收窗口事件的无障碍服务。事件处理仅在用户开启的短暂配对窗口扫描设置页，
 * 配对码读取后关闭窗口；读屏、截图由已授权的设备能力请求单独触发。
 * 清单不限定设置应用，不能宣称系统不投递其他应用事件。
 * 普通截图写入应用私有 Pictures/DSHA；MCP请求返回同一次截图的内存图片。
 * 不将配对码或敏感屏幕内容写入公开日志；撤销和运行代次约束见对应能力实现。
 */
public class DshaAccessibilityService extends AccessibilityService {

  private static final String TAG = "DSHA";
  private static final String SETTINGS_PKG = "com.android.settings";

  /** 与配对码本身的有效期对齐：Android 的配对弹窗约 2 分钟失效 */
  private static final long WATCH_MS = 120_000L;

  /** 读到配对信息后的回调（在无障碍服务线程上调用，实现方自己切主线程） */
  public interface PairInfoListener {
    void onPairInfo(String code, String ip, String port, String connectPort);
  }

  private static volatile long watchUntil = 0L;
  private static final Object WATCH_LOCK = new Object();
  private static long watchEpoch;
  private static volatile PairInfoListener listener;
  private static volatile String observedConnectHost = "", observedConnectPort = "";

  /** 三态：YES 确认已开 / NO 确认未开 / UNKNOWN 读不到设置（别当成未开）。
   *
   *  服务实例存在是最硬的证据 —— 系统能把它连起来，就一定是开着的。
   *  只在拿不到实例时才去解析 Settings 字符串，而那串在各家 ROM 上格式不一，
   *  解析失败时必须承认「不知道」，不能报「未开启」害用户反复去开。 */
  public static String enabledState(Context ctx) {
    if (instance != null) return "YES";
    try {
      String cls = DshaAccessibilityService.class.getName();
      String want = ctx.getPackageName() + "/" + cls;
      String on =
          Settings.Secure.getString(
              ctx.getContentResolver(), Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
      if (on == null) return "UNKNOWN"; // 读不到这一项，不代表用户没开
      if (on.isEmpty()) return "NO"; // 明确是空串 = 一个无障碍服务都没开
      return com.deepseekharness.app.util.AccessibilityEnableList.containsOwn(on, want)
          ? "YES"
          : "NO";
    } catch (Throwable e) {
      return "UNKNOWN";
    }
  }

  /** 用户是否已在系统设置里开启本服务 */
  public static boolean enabled(Context ctx) {
    try {
      String cls = DshaAccessibilityService.class.getName();
      String want = ctx.getPackageName() + "/" + cls;
      String on =
          Settings.Secure.getString(
              ctx.getContentResolver(), Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
      if (on == null || on.isEmpty()) return false;
      return com.deepseekharness.app.util.AccessibilityEnableList.containsOwn(on, want);
    } catch (Throwable e) {
      return false;
    }
  }

  /** 打开监听窗口（120 秒，一次性）。只有这段时间内才会去读设置页的内容。 */
  public static void startWatch(PairInfoListener l) {
    synchronized (WATCH_LOCK) {
      watchEpoch++;
      observedConnectHost = "";
      observedConnectPort = "";
      listener = l;
      watchUntil = android.os.SystemClock.elapsedRealtime() + WATCH_MS;
    }
  }

  public static void stopWatch() {
    synchronized (WATCH_LOCK) {
      watchEpoch++;
      watchUntil = 0L;
      listener = null;
    }
  }

  public static boolean watching() {
    return listener != null && android.os.SystemClock.elapsedRealtime() <= watchUntil;
  }

  /** Legacy callers have no fresh authenticated probe and cannot start a repair. */
  public static synchronized boolean rebindIfEnabled(Context context) {
    return false; // DeviceBridgeService supplies this probe directly to AccessibilityRepairOwner.
  }

  public static String connectionState(Context context) {
    if (connected()) return "CONNECTED";
    return switch (enabledState(context)) {
      case "YES" -> "ENABLED_NOT_CONNECTED";
      case "NO" -> "DISABLED";
      default -> "UNKNOWN";
    };
  }

  public static org.json.JSONObject availability(Context context) {
    String state = connectionState(context);
    var result =
        state.equals("CONNECTED")
            ? com.deepseekharness.app.screen.ScreenReply.completed()
            : com.deepseekharness.app.screen.ScreenReply.unavailable();
    try {
      result.put("connectionState", state);
      result.put("repair", com.deepseekharness.app.screen.AccessibilityRepairOwner.status());
      result.put(
          "component",
          new android.content.ComponentName(context, DshaAccessibilityService.class)
              .flattenToString());
      return result;
    } catch (Exception unavailable) {
      return result;
    }
  }

  /** Fixed platform metadata only; a target failure is distinct from a disconnected service. */
  public static org.json.JSONObject targetUnavailable(Context context, int displayId) {
    try {
      String lockState = "UNKNOWN";
      try {
        android.app.KeyguardManager keyguard =
            context.getSystemService(android.app.KeyguardManager.class);
        if (keyguard != null) lockState = keyguard.isKeyguardLocked() ? "LOCKED" : "UNLOCKED";
      } catch (RuntimeException unavailable) {
        /* This optional fact cannot replace the refusal. */
      }
      return com.deepseekharness.app.screen.ScreenReply.refused("SCREEN_TARGET_UNAVAILABLE")
          .put("connectionState", connectionState(context))
          .put("targetDiagnostic", windowDiagnostic())
          .put("displayId", displayId)
          .put("keyguardState", lockState)
          .put(
              "nextAction",
              !connected()
                  ? "CHECK_ACCESSIBILITY_CONNECTION"
                  : "LOCKED".equals(lockState)
                      ? "UNLOCK_WITH_SYSTEM_UI"
                      : "OPEN_A_READABLE_FOREGROUND_WINDOW")
          .put("automaticRetry", false);
    } catch (org.json.JSONException impossible) {
      throw new IllegalStateException(impossible);
    }
  }

  @Override
  public void onAccessibilityEvent(AccessibilityEvent event) {
    // 平时一律不读屏幕：没有用户主动开的窗口，这里立刻返回
    if (instance != this || !watching()) return;
    if (event == null) return;
    CharSequence pkg = event.getPackageName();
    if (pkg == null || !SETTINGS_PKG.contentEquals(pkg)) return;
    final long expectedWatch;
    final PairInfoListener expectedListener;
    synchronized (WATCH_LOCK) {
      expectedWatch = watchEpoch;
      expectedListener = listener;
      if (expectedListener == null || !watching()) return;
    }

    AccessibilityNodeInfo root = null;
    try {
      root = getRootInActiveWindow();
      if (root == null) return;
      StringBuilder sb = new StringBuilder();
      collectText(root, sb, 0, new com.deepseekharness.app.util.AccessibilityTreeBudget());
      String all = sb.toString();
      var connection = com.deepseekharness.app.util.AdbPairingInfo.connection(all);
      var pair = com.deepseekharness.app.util.AdbPairingInfo.pairing(all);
      String connectPort;
      synchronized (WATCH_LOCK) {
        if (watchEpoch != expectedWatch || listener != expectedListener || instance != this) return;
        if (connection != null) {
          observedConnectHost = connection.host;
          observedConnectPort = connection.port;
        }
        if (pair == null) return;
        connectPort = pair.host.equals(observedConnectHost) ? observedConnectPort : "";
        stopWatch(); // 一次性；旧扫描不能消费后来开启的窗口。
      }
      Log.i(TAG, "已从用户开启的配对窗口读取连接信息");
      expectedListener.onPairInfo(pair.code, pair.host, pair.port, connectPort);
    } catch (Throwable t) {
      Log.w(TAG, "配对窗口暂不可读：" + t.getClass().getSimpleName());
    } finally {
      if (root != null) {
        try {
          root.recycle();
        } catch (Throwable ignored) {
        }
      }
    }
  }

  /** 收集节点树上的可见文本。限深度与总长，避免深树递归过深或把内存吃爆。 */
  private static void collectText(
      AccessibilityNodeInfo node,
      StringBuilder sb,
      int depth,
      com.deepseekharness.app.util.AccessibilityTreeBudget budget) {
    if (node == null || !budget.enter(depth, 0) || sb.length() > 8000) return;
    if (node.isPassword()) return;
    CharSequence t = node.getText();
    if (!TextUtils.isEmpty(t)) appendPairingText(sb, t);
    CharSequence d = node.getContentDescription();
    if (!TextUtils.isEmpty(d)) appendPairingText(sb, d);
    int n = node.getChildCount();
    for (int i = 0; i < n && budget.childSlot(0); i++) {
      AccessibilityNodeInfo ch = node.getChild(i);
      if (ch == null) continue;
      try {
        collectText(ch, sb, depth + 1, budget);
      } finally {
        try {
          ch.recycle();
        } catch (Throwable ignored) {
        }
      }
    }
  }

  private static void appendPairingText(StringBuilder result, CharSequence text) {
    int remaining = 8000 - result.length();
    if (remaining > 0) result.append(text, 0, Math.min(remaining, text.length())).append('\n');
  }

  // 主屏新请求只通过显式上下文；旧门面不再隐式认领助手授权。
  private static volatile DshaAccessibilityService instance;
  private static final com.deepseekharness.app.util.ScreenConnectionEpoch<DshaAccessibilityService>
      CONNECTION = new com.deepseekharness.app.util.ScreenConnectionEpoch<>();
  private static final com.deepseekharness.app.screen.MainScreenActions MAIN_SCREEN =
      new com.deepseekharness.app.screen.MainScreenActions(CONNECTION);

  public static boolean connected() {
    return CONNECTION.snapshot() != null;
  }

  public static boolean isConnected() {
    return connected();
  }

  public static void revokeRequests() {
    MAIN_SCREEN.revoke();
  }

  public static String execute(
      com.deepseekharness.app.capabilities.CapabilityBroker broker,
      com.deepseekharness.app.capabilities.CapabilityRequest request,
      java.util.function.BooleanSupplier current,
      String operation,
      org.json.JSONObject args) {
    return MAIN_SCREEN.execute(broker, request, current, operation, args);
  }

  public static org.json.JSONObject virtualControl(
      com.deepseekharness.app.vscreen.VirtualScreenAccessibility session,
      com.deepseekharness.app.capabilities.CapabilityBroker broker,
      com.deepseekharness.app.capabilities.CapabilityRequest request,
      java.util.function.BooleanSupplier current,
      int displayId,
      String operation,
      org.json.JSONObject args) {
    var context =
        new com.deepseekharness.app.screen.ScreenRequestContext(
            broker, request, current, CONNECTION);
    if (context.service() == null) return com.deepseekharness.app.screen.ScreenReply.unavailable();
    if (!context.current() || context.target().displayId != displayId)
      return com.deepseekharness.app.screen.ScreenReply.refused("SCREEN_RUN_CHANGED");
    return session.run(context.service(), displayId, operation, args, context);
  }

  public static org.json.JSONObject virtualObservedControl(
      com.deepseekharness.app.vscreen.VirtualScreenAccessibility session,
      com.deepseekharness.app.capabilities.CapabilityBroker broker,
      com.deepseekharness.app.capabilities.CapabilityRequest request,
      java.util.function.BooleanSupplier current,
      int displayId,
      String operation,
      org.json.JSONObject args,
      com.deepseekharness.app.vscreen.VirtualScreenAccessibility.ObservedPixels pixels) {
    var context =
        new com.deepseekharness.app.screen.ScreenRequestContext(
            broker, request, current, CONNECTION);
    if (context.service() == null) return com.deepseekharness.app.screen.ScreenReply.unavailable();
    if (!context.current() || context.target().displayId != displayId || displayId <= 0)
      return com.deepseekharness.app.screen.ScreenReply.refused("SCREEN_RUN_CHANGED");
    return session.observedControl(context.service(), displayId, operation, args, context, pixels);
  }

  public static org.json.JSONObject typeVirtual(
      com.deepseekharness.app.capabilities.CapabilityBroker broker,
      com.deepseekharness.app.capabilities.CapabilityRequest request,
      java.util.function.BooleanSupplier current,
      int displayId,
      String text) {
    var context =
        new com.deepseekharness.app.screen.ScreenRequestContext(
            broker, request, current, CONNECTION);
    if (context.service() == null) return com.deepseekharness.app.screen.ScreenReply.unavailable();
    if (!context.current() || context.target().displayId != displayId || displayId <= 0)
      return com.deepseekharness.app.screen.ScreenReply.refused("SCREEN_RUN_CHANGED");
    AccessibilityNodeInfo target = null;
    try (var root =
        com.deepseekharness.app.screen.ScreenTargetResolver.resolve(context.service(), displayId)) {
      context.validateRoot(displayId, root.root);
      target = root.root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT);
      if (target == null || !target.isEditable() || !target.isEnabled())
        return com.deepseekharness.app.screen.ScreenReply.refused("VIRTUAL_INPUT_NOT_FOCUSED");
      if (text == null || text.length() > 16000)
        return com.deepseekharness.app.screen.ScreenReply.refused("TEXT_LIMIT");
      android.os.Bundle args = new android.os.Bundle();
      args.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text);
      context.validateCurrentTarget();
      if (!context.claimDispatch())
        return com.deepseekharness.app.screen.ScreenReply.refused("SCREEN_RUN_CHANGED");
      boolean accepted = target.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args);
      var result =
          accepted
              ? com.deepseekharness.app.screen.ScreenReply.completed()
              : com.deepseekharness.app.screen.ScreenReply.of(
                  com.deepseekharness.app.capabilities.CapabilityResult.rejected(
                      "VIRTUAL_INPUT_REJECTED", ""));
      if (accepted && !context.current()) result.put("runChangedAfterExecution", true);
      return result;
    } catch (Exception failure) {
      return com.deepseekharness.app.screen.ScreenReply.failure(
          context, "VIRTUAL_INPUT_RESULT_UNKNOWN");
    } finally {
      if (target != null) target.recycle();
    }
  }

  public static org.json.JSONObject typeVirtualUser(
      int displayId, String text, java.util.function.BooleanSupplier current) {
    return com.deepseekharness.app.screen.NativeScreenUserActions.typeVirtualUser(
        CONNECTION, displayId, text, current);
  }

  public static com.deepseekharness.app.screen.NativeScreenControlLease nativeVirtualControlLease(
      com.deepseekharness.app.vscreen.VirtualScreenNativeControls subject) {
    return com.deepseekharness.app.screen.NativeScreenUserActions.virtualControlLease(
        CONNECTION, subject);
  }

  // 兼容只读/虚拟屏门面。新异步链不使用下面的ThreadLocal。
  private static final class ActionAuthority {
    final com.deepseekharness.app.util.ScreenTarget target;
    final java.util.function.BooleanSupplier live;

    ActionAuthority(
        com.deepseekharness.app.util.ScreenTarget target, java.util.function.BooleanSupplier live) {
      this.target = target;
      this.live = live;
    }
  }

  private static final ThreadLocal<ActionAuthority> ACTION_AUTHORITY = new ThreadLocal<>();
  private static final ThreadLocal<String> WINDOW_DIAGNOSTIC = new ThreadLocal<>();

  public static String runAuthorized(
      com.deepseekharness.app.util.ScreenTarget target,
      java.util.function.BooleanSupplier live,
      java.util.function.Supplier<String> action) {
    ActionAuthority previous = ACTION_AUTHORITY.get();
    ACTION_AUTHORITY.set(new ActionAuthority(target, live));
    try {
      return action.get();
    } finally {
      if (previous == null) ACTION_AUTHORITY.remove();
      else ACTION_AUTHORITY.set(previous);
    }
  }

  public static String windowDiagnostic() {
    String value = WINDOW_DIAGNOSTIC.get();
    return value == null ? "ROOT_UNAVAILABLE" : value;
  }

  private static AccessibilityNodeInfo displayRoot(DshaAccessibilityService service, int display) {
    var result = com.deepseekharness.app.screen.ScreenTargetResolver.resolve(service, display);
    WINDOW_DIAGNOSTIC.set(result.diagnostic);
    return result.root; // 返回值所有权转移给调用方。
  }

  public static com.deepseekharness.app.util.ScreenTarget observeTarget(int display) {
    var lease = CONNECTION.snapshot();
    if (lease == null) {
      WINDOW_DIAGNOSTIC.set("ACCESSIBILITY_UNAVAILABLE");
      return null;
    }
    AccessibilityNodeInfo root = displayRoot(lease.service, display);
    try {
      return CONNECTION.current(lease)
          ? com.deepseekharness.app.screen.ScreenTargetResolver.identity(display, root)
          : null;
    } finally {
      if (root != null) root.recycle();
    }
  }

  /** Bounded physical HTTP preflight; legacy UI confirmations and virtual lookup stay separate. */
  public static com.deepseekharness.app.screen.MainScreenActions.TargetPreflight
      observeTargetForRequest(java.util.function.BooleanSupplier current) {
    return MAIN_SCREEN.observeTargetForRequest(current);
  }

  public static boolean matchesTarget(com.deepseekharness.app.util.ScreenTarget target) {
    return target != null && target.matches(observeTarget(target.displayId));
  }

  public static void validateActionRoot(int display, AccessibilityNodeInfo root) {
    ActionAuthority authority = ACTION_AUTHORITY.get();
    if (authority != null
        && (!authority.live.getAsBoolean()
            || !authority.target.matches(
                com.deepseekharness.app.screen.ScreenTargetResolver.identity(display, root))))
      throw new TargetChangedException();
  }

  public static final class TargetChangedException extends IllegalStateException {
    private static final long serialVersionUID = 1L;

    public TargetChangedException() {
      super("SCREEN_TARGET_CHANGED");
    }
  }

  public static AccessibilityNodeInfo virtualRoot(int displayId) {
    var lease = CONNECTION.snapshot();
    if (lease == null || android.os.Build.VERSION.SDK_INT < 30 || displayId <= 0) return null;
    AccessibilityNodeInfo root = displayRoot(lease.service, displayId);
    try {
      if (!CONNECTION.current(lease)) throw new TargetChangedException();
      validateActionRoot(displayId, root);
      return root;
    } catch (RuntimeException failure) {
      if (root != null) root.recycle();
      throw failure;
    }
  }

  public static AccessibilityNodeInfo refreshVirtualRoot(
      int displayId, AccessibilityNodeInfo previous) {
    if (previous == null) return null;
    var expected =
        com.deepseekharness.app.screen.ScreenTargetResolver.identity(displayId, previous);
    previous.refresh();
    AccessibilityNodeInfo root = virtualRoot(displayId);
    if (root != null
        && !expected.matches(
            com.deepseekharness.app.screen.ScreenTargetResolver.identity(displayId, root))) {
      root.recycle();
      throw new TargetChangedException();
    }
    return root;
  }

  public static String currentPackage() {
    var target = observeTarget(0);
    return target == null ? "" : target.packageName;
  }

  public static org.json.JSONObject virtualControl(
      com.deepseekharness.app.vscreen.VirtualScreenAccessibility session,
      int displayId,
      String operation,
      org.json.JSONObject args) {
    return com.deepseekharness.app.screen.ScreenReply.refused("EXPLICIT_SCREEN_REQUEST_REQUIRED");
  }

  public static String virtualDump(int displayId) {
    return "[ERR] EXPLICIT_SCREEN_REQUEST_REQUIRED";
  }

  /** 未带显式请求的旧写入口不再执行，桥与内置插件已迁移到新上下文。 */
  public static String virtualInput(int displayId, String text) {
    return "EXPLICIT_SCREEN_REQUEST_REQUIRED";
  }

  public static String uiDump() {
    return "[ERR] EXPLICIT_SCREEN_REQUEST_REQUIRED";
  }

  public static String uiTap(int x, int y) {
    return "[ERR] FRAME_NOT_OBSERVED";
  }

  public static String uiTapText(String text) {
    return "[ERR] EXPLICIT_SCREEN_REQUEST_REQUIRED";
  }

  public static String uiInput(String text) {
    return "[ERR] EXPLICIT_SCREEN_REQUEST_REQUIRED";
  }

  public static String uiKey(String name) {
    return "[ERR] EXPLICIT_SCREEN_REQUEST_REQUIRED";
  }

  public static String uiSwipe(int x1, int y1, int x2, int y2, int millis) {
    return "[ERR] FRAME_NOT_OBSERVED";
  }

  public static String uiScreenshot() {
    return "[ERR] EXPLICIT_SCREEN_REQUEST_REQUIRED";
  }

  public static String uiScreenshot(java.util.function.BooleanSupplier current, boolean mcp) {
    return "[ERR] EXPLICIT_SCREEN_REQUEST_REQUIRED";
  }

  @Override
  protected void onServiceConnected() {
    super.onServiceConnected();
    CONNECTION.connect(this);
    instance = this;
    MAIN_SCREEN.revoke();
    com.deepseekharness.app.screen.AccessibilityRepairOwner.serviceConnected();
    HttpShellService.revokeDisconnectedScreenGrant(this);
    Log.i(TAG, "无障碍服务已连接");
  }

  @Override
  public void onInterrupt() {
    // 不提供持续反馈；interrupt不等于服务已解绑。
  }

  private void disconnectCurrentService() {
    if (!CONNECTION.disconnect(this)) return;
    if (instance == this) instance = null;
    MAIN_SCREEN.revoke();
    // Platform disconnect always fences old screen authority, including our own planned disable.
    HttpShellService.revokeDisconnectedScreenGrant(this);
    com.deepseekharness.app.screen.AccessibilityRepairOwner.platformDisconnected();
    stopWatch();
  }

  @Override
  public boolean onUnbind(android.content.Intent intent) {
    disconnectCurrentService();
    return super.onUnbind(intent);
  }

  @Override
  public void onDestroy() {
    disconnectCurrentService();
    super.onDestroy();
  }
}
