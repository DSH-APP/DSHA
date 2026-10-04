package com.deepseekharness.app;

import com.deepseekharness.app.util.SensitiveData;

import android.accessibilityservice.AccessibilityService;
import android.content.Context;
import android.provider.Settings;
import android.text.TextUtils;
import android.util.Log;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 接收窗口事件的无障碍服务。事件处理仅在用户开启的短暂配对窗口扫描设置页，
 * 配对码读取后关闭窗口；读屏、截图由已授权的设备能力请求单独触发。
 * 清单不限定设置应用，不能宣称系统不投递其他应用事件。
 * 截图写入应用私有 Pictures/DSHA，返回受控内容 URI；它不是完全不落盘的能力。
 * 不将配对码或敏感屏幕内容写入公开日志；撤销和运行代次约束见对应能力实现。
 */
public class DshaAccessibilityService extends AccessibilityService {

  private static final String TAG = "DSHA";
  private static final String SETTINGS_PKG = "com.android.settings";

  /** 与配对码本身的有效期对齐：Android 的配对弹窗约 2 分钟失效 */
  private static final long WATCH_MS = 120_000L;

  /** 无线调试配对码固定 6 位；前后加边界避免从长数字里截一段 */
  private static final Pattern CODE = Pattern.compile("(?<!\\d)(\\d{6})(?!\\d)");

  /** 弹窗里的「IP 地址和端口」，端口是随机高位 */
  private static final Pattern ADDR = Pattern.compile("(\\d{1,3}(?:\\.\\d{1,3}){3}):(\\d{2,5})");

  /** 读到配对信息后的回调（在无障碍服务线程上调用，实现方自己切主线程） */
  public interface PairInfoListener {
    void onPairInfo(String code, String ip, String port, String connectPort);
  }

  private static volatile long watchUntil = 0L;
  private static volatile PairInfoListener listener;
  private static volatile String observedConnectHost = "", observedConnectPort = "";
  private static volatile long lastKeepAliveAt;
  private static final long KEEPALIVE_DEBOUNCE_MS = 30_000L;

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
      for (String e0 : on.split(":")) {
        String e = e0.trim();
        if (e.equalsIgnoreCase(want)) return "YES";
        if (e.startsWith(ctx.getPackageName() + "/")
            && cls.endsWith(e.substring(e.indexOf('/') + 1))) {
          return "YES";
        }
      }
      return "NO";
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
      for (String s : on.split(":")) {
        String e = s.trim();
        if (e.equalsIgnoreCase(want)) return true;
        // 部分机型存的是 包名/.类简名 的简写形式
        if (e.startsWith(ctx.getPackageName() + "/")
            && cls.endsWith(e.substring(e.indexOf('/') + 1))) {
          return true;
        }
      }
      return false;
    } catch (Throwable e) {
      return false;
    }
  }

  /** 打开监听窗口（120 秒，一次性）。只有这段时间内才会去读设置页的内容。 */
  public static void startWatch(PairInfoListener l) {
    observedConnectHost = "";
    observedConnectPort = "";
    listener = l;
    watchUntil = System.currentTimeMillis() + WATCH_MS;
  }

  public static void stopWatch() {
    watchUntil = 0L;
    listener = null;
  }

  public static boolean watching() {
    return System.currentTimeMillis() <= watchUntil;
  }

  /**
   * Rebind only a service that the user already enabled in Android settings.
   * The ADB pairing flow grants WRITE_SECURE_SETTINGS for this app; the ADB
   * watchdog uses that grant after a verified probe to recover ROMs that
   * silently unbind the accessibility service. A user who turns the service
   * off removes the component, so this method never re-enables a manual off.
   */
  public static boolean rebindIfEnabled(Context context) {
    if (instance != null || context == null || !enabled(context)) return false;
    long now = android.os.SystemClock.elapsedRealtime();
    if (now - lastKeepAliveAt < KEEPALIVE_DEBOUNCE_MS) return false;
    lastKeepAliveAt = now;
    try {
      if (context.checkSelfPermission(android.Manifest.permission.WRITE_SECURE_SETTINGS)
          != android.content.pm.PackageManager.PERMISSION_GRANTED) return false;
      String configured =
          Settings.Secure.getString(
              context.getContentResolver(), Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
      if (configured == null || configured.isEmpty()) return false;
      String component = context.getPackageName() + "/" + DshaAccessibilityService.class.getName();
      java.util.ArrayList<String> entries = new java.util.ArrayList<>();
      boolean found = false;
      for (String raw : configured.split(":")) {
        String value = raw.trim();
        if (value.isEmpty()) continue;
        if (value.equalsIgnoreCase(component)
            || (value.startsWith(context.getPackageName() + "/")
                && DshaAccessibilityService.class
                    .getName()
                    .endsWith(value.substring(value.indexOf('/') + 1)))) {
          found = true;
          continue;
        }
        entries.add(value);
      }
      if (!found) return false;
      String disabled = android.text.TextUtils.join(":", entries);
      if (!Settings.Secure.putString(
          context.getContentResolver(), Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES, disabled))
        return false;
      android.os.SystemClock.sleep(120L);
      if (!Settings.Secure.putString(
          context.getContentResolver(), Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES, configured))
        return false;
      Log.i(TAG, "ADB watchdog requested an accessibility service rebind");
      return true;
    } catch (Throwable error) {
      Log.w(TAG, "Accessibility rebind unavailable: " + error.getClass().getSimpleName());
      return false;
    }
  }

  @Override
  public void onAccessibilityEvent(AccessibilityEvent event) {
    // 平时一律不读屏幕：没有用户主动开的窗口，这里立刻返回
    if (System.currentTimeMillis() > watchUntil) return;
    if (event == null) return;
    CharSequence pkg = event.getPackageName();
    if (pkg == null || !SETTINGS_PKG.contentEquals(pkg)) return;

    AccessibilityNodeInfo root = null;
    try {
      root = getRootInActiveWindow();
      if (root == null) return;
      StringBuilder sb = new StringBuilder();
      collectText(root, sb, 0);
      String all = sb.toString();
      var connection = com.deepseekharness.app.util.AdbPairingInfo.connection(all);
      if (connection != null) {
        observedConnectHost = connection.host;
        observedConnectPort = connection.port;
      }
      var pair = com.deepseekharness.app.util.AdbPairingInfo.pairing(all);
      if (pair == null) return;
      String code = pair.code, ip = pair.host, port = pair.port;
      String connectPort = ip.equals(observedConnectHost) ? observedConnectPort : "";
      PairInfoListener l = listener;
      stopWatch(); // 一次性：读到就收工，不再继续读屏
      Log.i(TAG, "已从配对弹窗读到配对码（端口 " + (port.isEmpty() ? "未识别" : port) + "）");
      if (l != null) l.onPairInfo(code, ip, port, connectPort);
    } catch (Throwable t) {
      Log.w(TAG, "读配对码失败：" + SensitiveData.redact(String.valueOf(t)));
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
  private static void collectText(AccessibilityNodeInfo node, StringBuilder sb, int depth) {
    if (node == null || depth > 24 || sb.length() > 8000) return;
    if (node.isPassword()) return;
    CharSequence t = node.getText();
    if (!TextUtils.isEmpty(t)) sb.append(t).append('\n');
    CharSequence d = node.getContentDescription();
    if (!TextUtils.isEmpty(d)) sb.append(d).append('\n');
    int n = node.getChildCount();
    for (int i = 0; i < n; i++) {
      AccessibilityNodeInfo ch = node.getChild(i);
      if (ch == null) continue;
      try {
        collectText(ch, sb, depth + 1);
      } finally {
        try {
          ch.recycle();
        } catch (Throwable ignored) {
        }
      }
    }
  }

  // ==================== 给 agent 用的屏幕操作能力 ====================
  //
  // 这一层让 agent 不依赖 ADB / Shizuku 就能读屏、点按、输入、按键 —— 对多数
  // 用户来说 ADB 无线调试根本没开，Shizuku 也没装，而无障碍是一次授权永久可用。
  //
  // 隐私：这里全部是「按需拉取」——只有 agent 调用时才去 getRootInActiveWindow()，
  // 服务自身不做任何持续记录（onAccessibilityEvent 里除了配对窗口一律直接返回）。

  private static volatile DshaAccessibilityService instance;

  public static boolean connected() {
    return instance != null;
  }

  /** 授权弹窗关闭的短暂窗口切换期间只重试读取窗口，不重放点击或输入。 */
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

  private static AccessibilityNodeInfo displayRoot(DshaAccessibilityService service, int display) {
    if (service == null) return null;
    if (android.os.Build.VERSION.SDK_INT < 30)
      return display == 0 ? service.getRootInActiveWindow() : null;
    var windows = service.getWindowsOnAllDisplays().get(display);
    if (windows == null) return null;
    AccessibilityNodeInfo result = null;
    try {
      for (var window : windows) {
        if (display > 0
            && window.getType()
                != android.view.accessibility.AccessibilityWindowInfo.TYPE_APPLICATION) continue;
        var root = window.getRoot();
        if (root == null) continue;
        if (result == null || window.isActive() || window.isFocused()) {
          if (result != null) result.recycle();
          result = root;
        } else root.recycle();
        if (window.isActive()) break;
      }
    } finally {
      for (var window : windows)
        try {
          window.recycle();
        } catch (Throwable ignored) {
        }
    }
    return result;
  }

  private static com.deepseekharness.app.util.ScreenTarget identity(
      int display, AccessibilityNodeInfo root) {
    return root == null
        ? null
        : new com.deepseekharness.app.util.ScreenTarget(
            display,
            root.getWindowId(),
            root.getPackageName() == null ? "" : root.getPackageName().toString());
  }

  public static com.deepseekharness.app.util.ScreenTarget observeTarget(int display) {
    AccessibilityNodeInfo root = displayRoot(instance, display);
    try {
      return identity(display, root);
    } finally {
      if (root != null) root.recycle();
    }
  }

  public static boolean matchesTarget(com.deepseekharness.app.util.ScreenTarget target) {
    return target != null && target.matches(observeTarget(target.displayId));
  }

  private static AccessibilityNodeInfo activeWindow(DshaAccessibilityService service) {
    ActionAuthority authority = ACTION_AUTHORITY.get();
    int display = authority == null ? 0 : authority.target.displayId;
    var root = displayRoot(service, display);
    if (authority != null
        && (!authority.live.getAsBoolean() || !authority.target.matches(identity(display, root)))) {
      if (root != null) root.recycle();
      throw new IllegalStateException("SCREEN_TARGET_CHANGED");
    }
    return root;
  }

  private static void verifyActionTarget() {
    ActionAuthority authority = ACTION_AUTHORITY.get();
    if (authority != null && (!authority.live.getAsBoolean() || !matchesTarget(authority.target)))
      throw new IllegalStateException("SCREEN_TARGET_CHANGED");
  }

  public static void validateActionRoot(int display, AccessibilityNodeInfo root) {
    ActionAuthority a = ACTION_AUTHORITY.get();
    if (a != null && (!a.live.getAsBoolean() || !a.target.matches(identity(display, root))))
      throw new IllegalStateException("SCREEN_TARGET_CHANGED");
  }

  @Override
  protected void onServiceConnected() {
    super.onServiceConnected();
    instance = this;
    lastKeepAliveAt = 0L;
    Log.i(TAG, "无障碍服务已连接");
  }

  /** 服务没开时统一的提示语：告诉 agent 该让用户做什么，而不是只丢一个错误码 */
  private static final String NOT_READY =
      "[ERR] 无障碍服务未开启。请让用户在 DSHA「设置 → 设备能力授权」点「设置屏幕操作」，" + "或到系统设置 → 无障碍 → DSHA 配对助手 打开。";

  /** 当前前台窗口的应用包名；取不到返回空串。授权闸门用它识别支付/银行类应用。 */
  public static String currentPackage() {
    DshaAccessibilityService s = instance;
    if (s == null) return "";
    AccessibilityNodeInfo root = null;
    try {
      root = activeWindow(s);
      if (root == null) return "";
      CharSequence p = root.getPackageName();
      return p == null ? "" : p.toString();
    } catch (Throwable t) {
      return "";
    } finally {
      if (root != null) {
        try {
          root.recycle();
        } catch (Throwable ignored) {
        }
      }
    }
  }

  public static boolean isConnected() {
    return instance != null;
  }

  public static org.json.JSONObject virtualControl(
      com.deepseekharness.app.vscreen.VirtualScreenAccessibility session,
      int displayId,
      String operation,
      org.json.JSONObject args) {
    return session.run(instance, displayId, operation, args);
  }

  /** 只读取指定虚拟显示的窗口，绝不回退到手机主屏。 */
  private static AccessibilityNodeInfo virtualRoot(int displayId) {
    DshaAccessibilityService service = instance;
    if (service == null || android.os.Build.VERSION.SDK_INT < 30 || displayId <= 0) return null;
    var windows = service.getWindowsOnAllDisplays().get(displayId);
    if (windows == null) return null;
    AccessibilityNodeInfo result = null;
    try {
      for (var window : windows) {
        if (window.getType() != android.view.accessibility.AccessibilityWindowInfo.TYPE_APPLICATION)
          continue;
        AccessibilityNodeInfo root = window.getRoot();
        if (root == null) continue;
        if (result == null || window.isActive()) {
          if (result != null) result.recycle();
          result = root;
        } else root.recycle();
        if (window.isActive()) break;
      }
      validateActionRoot(displayId, result);
      return result;
    } finally {
      for (var window : windows) window.recycle();
    }
  }

  public static String virtualDump(int displayId) {
    if (instance == null) return "ACCESSIBILITY_UNAVAILABLE";
    AccessibilityNodeInfo root = null;
    try {
      root = virtualRoot(displayId);
      if (root == null) return "VIRTUAL_WINDOW_UNAVAILABLE";
      StringBuilder out = new StringBuilder();
      dumpNode(root, out, 0, new int[] {0});
      return out.toString();
    } catch (Throwable e) {
      return "VIRTUAL_TREE_UNAVAILABLE";
    } finally {
      if (root != null) root.recycle();
    }
  }

  public static String virtualInput(int displayId, String text) {
    if (instance == null) return "ACCESSIBILITY_REQUIRED_FOR_UNICODE";
    AccessibilityNodeInfo root = null, target = null;
    try {
      root = virtualRoot(displayId);
      if (root == null) return "VIRTUAL_WINDOW_UNAVAILABLE";
      target = root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT);
      if (target == null || !target.isEditable()) return "VIRTUAL_INPUT_NOT_FOCUSED";
      android.os.Bundle args = new android.os.Bundle();
      args.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text);
      return target.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
          ? "OK"
          : "VIRTUAL_INPUT_REJECTED";
    } catch (Throwable e) {
      return "VIRTUAL_INPUT_RESULT_UNKNOWN";
    } finally {
      if (target != null) target.recycle();
      if (root != null) root.recycle();
    }
  }

  /** 读当前屏幕：输出带序号、文本、可点击性与坐标的清单，供 agent 决定下一步点哪个。 */
  public static String uiDump() {
    DshaAccessibilityService s = instance;
    if (s == null) return com.deepseekharness.app.util.UiText.text(NOT_READY);
    try {
      AccessibilityNodeInfo root = activeWindow(s);
      if (root == null)
        return com.deepseekharness.app.util.UiText.text("[ERR] 取不到当前窗口（可能停在锁屏或系统弹窗上）");
      StringBuilder sb = new StringBuilder();
      CharSequence pkg = root.getPackageName();
      sb.append("窗口应用: ").append(pkg == null ? "未知" : pkg).append('\n');
      int[] n = {0};
      try {
        dumpNode(root, sb, 0, n);
      } finally {
        try {
          root.recycle();
        } catch (Throwable ignored) {
        }
      }
      if (n[0] == 0) sb.append("（没有可读节点）\n");
      return sb.toString();
    } catch (Throwable t) {
      return com.deepseekharness.app.util.UiText.format(
          "[ERR] 读屏失败：%s", SensitiveData.redact(String.valueOf(t)));
    }
  }

  private static void dumpNode(
      AccessibilityNodeInfo node, StringBuilder sb, int depth, int[] count) {
    if (node == null || depth > 24 || count[0] >= 200 || sb.length() > 12000) return;
    CharSequence t = node.isPassword() ? null : node.getText();
    CharSequence d = node.isPassword() ? null : node.getContentDescription();
    boolean clickable = node.isClickable();
    boolean editable = node.isEditable();
    String label =
        node.isPassword()
            ? "[PASSWORD]"
            : !TextUtils.isEmpty(t) ? t.toString() : (!TextUtils.isEmpty(d) ? d.toString() : "");
    android.graphics.Rect r = new android.graphics.Rect();
    node.getBoundsInScreen(r);
    // 只输出「有文字」或「能点/能输入」的节点：全量节点树对 agent 是噪音
    if ((!label.isEmpty() || clickable || editable)
        && com.deepseekharness.app.util.AccessibilityDumpBounds.ordered(
            r.left, r.top, r.right, r.bottom)) {
      count[0]++;
      sb.append('[').append(count[0]).append("] ");
      if (!label.isEmpty()) sb.append('"').append(label.replace('\n', ' ')).append('"');
      if (clickable) sb.append(" 可点击");
      if (editable) sb.append(" 可输入");
      if (node.isChecked()) sb.append(" 已选中");
      if (!node.isEnabled()) sb.append(" 不可用");
      sb.append(" 中心=(")
          .append(r.centerX())
          .append(',')
          .append(r.centerY())
          .append(')')
          .append(" 区域=")
          .append(r.left)
          .append(',')
          .append(r.top)
          .append(',')
          .append(r.right)
          .append(',')
          .append(r.bottom)
          .append('\n');
    }
    if (node.isPassword()) return;
    int cn = node.getChildCount();
    for (int i = 0; i < cn; i++) {
      AccessibilityNodeInfo ch = node.getChild(i);
      if (ch == null) continue;
      try {
        dumpNode(ch, sb, depth + 1, count);
      } finally {
        try {
          ch.recycle();
        } catch (Throwable ignored) {
        }
      }
    }
  }

  /** 按坐标点按。坐标从 uiDump 的「中心=」里取。 */
  public static String uiTap(int x, int y) {
    if (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.N) {
      return "手势点按需 Android 7+（当前系统过旧）";
    }
    DshaAccessibilityService s = instance;
    if (s == null) return com.deepseekharness.app.util.UiText.text(NOT_READY);
    return s.gesture(
        buildTap(x, y), com.deepseekharness.app.util.UiText.format("点按 (%d,%d)", x, y));
  }

  /** 按文字点按：优先走节点自身的 ACTION_CLICK，比盲点坐标稳得多
   *  （控件位置会随滚动、折叠、动画变化，文字不会）。 */
  public static String uiTapText(String text) {
    DshaAccessibilityService s = instance;
    if (s == null) return com.deepseekharness.app.util.UiText.text(NOT_READY);
    if (text == null || text.isEmpty())
      return com.deepseekharness.app.util.UiText.text("[ERR] 要点的文字不能为空");
    AccessibilityNodeInfo root = null;
    try {
      root = activeWindow(s);
      if (root == null) return com.deepseekharness.app.util.UiText.text("[ERR] 取不到当前窗口");
      AccessibilityNodeInfo hit = findClickableByText(root, text, 0);
      if (hit == null)
        return com.deepseekharness.app.util.UiText.format(
            "[ERR] 屏幕上找不到可点击的「%s」（先用 dump 看看实际文字）", text);
      boolean ok;
      try {
        ok = hit.performAction(AccessibilityNodeInfo.ACTION_CLICK);
      } finally {
        try {
          hit.recycle();
        } catch (Throwable ignored) {
        }
      }
      return ok
          ? com.deepseekharness.app.util.UiText.format("OK 已点击「%s」", text)
          : com.deepseekharness.app.util.UiText.text("[ERR] 点击被系统拒绝（控件可能不可用）");
    } catch (Throwable t) {
      return com.deepseekharness.app.util.UiText.format(
          "[ERR] 点击失败：%s", SensitiveData.redact(String.valueOf(t)));
    } finally {
      if (root != null) {
        try {
          root.recycle();
        } catch (Throwable ignored) {
        }
      }
    }
  }

  /** 找到含指定文字、且自身或祖先可点击的节点（返回的节点由调用方 recycle） */
  private static AccessibilityNodeInfo findClickableByText(
      AccessibilityNodeInfo node, String text, int depth) {
    if (node == null || depth > 24) return null;
    CharSequence t = node.isPassword() ? null : node.getText();
    CharSequence d = node.isPassword() ? null : node.getContentDescription();
    boolean match =
        (t != null && t.toString().contains(text)) || (d != null && d.toString().contains(text));
    if (match) {
      // 文字节点常常自己不可点击，真正的按钮是它的某级父节点
      AccessibilityNodeInfo cur = node;
      for (int up = 0; up < 6 && cur != null; up++) {
        if (cur.isClickable() && cur.isEnabled()) {
          return AccessibilityNodeInfo.obtain(cur);
        }
        cur = cur.getParent();
      }
    }
    int cn = node.getChildCount();
    for (int i = 0; i < cn; i++) {
      AccessibilityNodeInfo ch = node.getChild(i);
      if (ch == null) continue;
      AccessibilityNodeInfo r = findClickableByText(ch, text, depth + 1);
      try {
        ch.recycle();
      } catch (Throwable ignored) {
      }
      if (r != null) return r;
    }
    return null;
  }

  /** 往当前焦点输入框填文字（没有焦点时要求先明确点选输入框） */
  public static String uiInput(String text) {
    DshaAccessibilityService s = instance;
    if (s == null) return com.deepseekharness.app.util.UiText.text(NOT_READY);
    if (text == null) return com.deepseekharness.app.util.UiText.text("[ERR] 文本不能为空");
    AccessibilityNodeInfo root = null;
    try {
      root = activeWindow(s);
      if (root == null) return com.deepseekharness.app.util.UiText.text("[ERR] 取不到当前窗口");
      AccessibilityNodeInfo target = root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT);

      if (target == null || !target.isEditable() || !target.isEnabled())
        return com.deepseekharness.app.util.UiText.text("[ERR] 屏幕上没有输入框（先点一下要输入的位置）");
      android.os.Bundle args = new android.os.Bundle();
      args.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text);
      boolean ok;
      try {
        ok = target.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args);
      } finally {
        try {
          target.recycle();
        } catch (Throwable ignored) {
        }
      }
      return ok
          ? com.deepseekharness.app.util.UiText.format("OK 已输入 %s 个字符", text.length())
          : com.deepseekharness.app.util.UiText.text("[ERR] 输入被系统拒绝");
    } catch (Throwable t) {
      return com.deepseekharness.app.util.UiText.format(
          "[ERR] 输入失败：%s", SensitiveData.redact(String.valueOf(t)));
    } finally {
      if (root != null) {
        try {
          root.recycle();
        } catch (Throwable ignored) {
        }
      }
    }
  }

  private static AccessibilityNodeInfo findEditable(AccessibilityNodeInfo node, int depth) {
    if (node == null || depth > 24) return null;
    if (node.isEditable() && node.isEnabled()) return AccessibilityNodeInfo.obtain(node);
    int cn = node.getChildCount();
    for (int i = 0; i < cn; i++) {
      AccessibilityNodeInfo ch = node.getChild(i);
      if (ch == null) continue;
      AccessibilityNodeInfo r = findEditable(ch, depth + 1);
      try {
        ch.recycle();
      } catch (Throwable ignored) {
      }
      if (r != null) return r;
    }
    return null;
  }

  /** 全局按键：back / home / recents / notifications / quicksettings / lock */
  public static String uiKey(String name) {
    DshaAccessibilityService s = instance;
    if (s == null) return com.deepseekharness.app.util.UiText.text(NOT_READY);
    String k = name == null ? "" : name.trim().toLowerCase(java.util.Locale.ROOT);
    int action;
    switch (k) {
      case "back":
        action = GLOBAL_ACTION_BACK;
        break;
      case "home":
        action = GLOBAL_ACTION_HOME;
        break;
      case "recents":
      case "recent":
        action = GLOBAL_ACTION_RECENTS;
        break;
      case "notifications":
      case "notification":
        action = GLOBAL_ACTION_NOTIFICATIONS;
        break;
      case "quicksettings":
      case "quick":
        action = GLOBAL_ACTION_QUICK_SETTINGS;
        break;
      case "lock":
        if (android.os.Build.VERSION.SDK_INT < 28)
          return com.deepseekharness.app.util.UiText.text("[ERR] 锁屏需要 Android 9+");
        action = GLOBAL_ACTION_LOCK_SCREEN;
        break;
      default:
        return com.deepseekharness.app.util.UiText.format(
            "[ERR] 不认识的按键「%s」（可用：back/home/recents/notifications/quicksettings/lock）", name);
    }
    try {
      verifyActionTarget();
      return s.performGlobalAction(action)
          ? com.deepseekharness.app.util.UiText.format("OK 已发送 %s", k)
          : com.deepseekharness.app.util.UiText.format("[ERR] 系统拒绝了 %s", k);
    } catch (Throwable t) {
      return com.deepseekharness.app.util.UiText.format(
          "[ERR] 按键失败：%s", SensitiveData.redact(String.valueOf(t)));
    }
  }

  /** 滑动：翻页、下拉刷新、侧滑都靠它。durationMs 太短系统会当成甩动。 */
  public static String uiSwipe(int x1, int y1, int x2, int y2, int durationMs) {
    if (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.N) {
      return "手势滑动需 Android 7+（当前系统过旧）";
    }
    DshaAccessibilityService s = instance;
    if (s == null) return com.deepseekharness.app.util.UiText.text(NOT_READY);
    int dur = durationMs <= 0 ? 300 : Math.min(durationMs, 5000);
    android.graphics.Path path = new android.graphics.Path();
    path.moveTo(x1, y1);
    path.lineTo(x2, y2);
    android.accessibilityservice.GestureDescription.StrokeDescription stroke =
        new android.accessibilityservice.GestureDescription.StrokeDescription(path, 0, dur);
    return s.gesture(
        new android.accessibilityservice.GestureDescription.Builder().addStroke(stroke).build(),
        com.deepseekharness.app.util.UiText.format("滑动 (%d,%d)→(%d,%d)", x1, y1, x2, y2));
  }

  @androidx.annotation.RequiresApi(24)
  private static android.accessibilityservice.GestureDescription buildTap(int x, int y) {
    android.graphics.Path p = new android.graphics.Path();
    p.moveTo(x, y);
    return new android.accessibilityservice.GestureDescription.Builder()
        .addStroke(new android.accessibilityservice.GestureDescription.StrokeDescription(p, 0, 50))
        .build();
  }

  /** 派发手势并等结果：dispatchGesture 是异步回调，agent 那边要的是同步答复 */
  @androidx.annotation.RequiresApi(24)
  private String gesture(android.accessibilityservice.GestureDescription gd, String what) {
    final java.util.concurrent.CountDownLatch latch = new java.util.concurrent.CountDownLatch(1);
    final boolean[] ok = {false};
    try {
      verifyActionTarget();
      boolean accepted =
          dispatchGesture(
              gd,
              new GestureResultCallback() {
                @Override
                public void onCompleted(android.accessibilityservice.GestureDescription d) {
                  ok[0] = true;
                  latch.countDown();
                }

                @Override
                public void onCancelled(android.accessibilityservice.GestureDescription d) {
                  latch.countDown();
                }
              },
              null);
      if (!accepted) return com.deepseekharness.app.util.UiText.format("[ERR] 手势未被接受（%s）", what);
      if (!latch.await(6, java.util.concurrent.TimeUnit.SECONDS)) {
        return com.deepseekharness.app.util.UiText.format("[ERR] 手势超时（%s）", what);
      }
      return ok[0]
          ? com.deepseekharness.app.util.UiText.format("OK 已%s", what)
          : com.deepseekharness.app.util.UiText.format("[ERR] 手势被取消（%s，可能被其它手势打断）", what);
    } catch (Throwable t) {
      return com.deepseekharness.app.util.UiText.format(
          "[ERR] 手势失败：%s", SensitiveData.redact(String.valueOf(t)));
    }
  }

  /** 截屏（Android 11+）。普通调用返回路径；MCP 返回同一次截图的图片块，
   *  不让 guest 再读取未挂载的 Android 多用户外部路径。 */
  @android.annotation.TargetApi(30)
  public static String uiScreenshot() {
    return uiScreenshot(() -> true, false);
  }

  @android.annotation.TargetApi(30)
  public static String uiScreenshot(java.util.function.BooleanSupplier validRun, boolean mcp) {
    DshaAccessibilityService s = instance;
    if (s == null) return com.deepseekharness.app.util.UiText.text(NOT_READY);
    if (!validRun.getAsBoolean()) return "[ERR] SCREENSHOT_RUN_CHANGED";
    if (android.os.Build.VERSION.SDK_INT < 30) {
      return com.deepseekharness.app.util.UiText.format(
          "[ERR] 截屏需要 Android 11 及以上（当前 API %s）", android.os.Build.VERSION.SDK_INT);
    }
    final java.util.concurrent.CountDownLatch latch = new java.util.concurrent.CountDownLatch(1);
    final String[] out = {com.deepseekharness.app.util.UiText.text("[ERR] 截屏无结果")};
    final java.util.concurrent.atomic.AtomicBoolean closed =
        new java.util.concurrent.atomic.AtomicBoolean();
    final java.util.concurrent.ExecutorService executor =
        java.util.concurrent.Executors.newSingleThreadExecutor();
    try {
      s.takeScreenshot(
          android.view.Display.DEFAULT_DISPLAY,
          executor,
          new TakeScreenshotCallback() {
            @Override
            public void onSuccess(ScreenshotResult result) {
              android.graphics.Bitmap hardware = null, bmp = null;
              try {
                if (closed.get() || instance != s || !validRun.getAsBoolean()) {
                  out[0] = "[ERR] SCREENSHOT_RUN_CHANGED";
                  return;
                }
                hardware =
                    android.graphics.Bitmap.wrapHardwareBuffer(
                        result.getHardwareBuffer(), result.getColorSpace());
                // Encode an owned software bitmap, then release both the
                // temporary copy and the framework HardwareBuffer.
                bmp =
                    hardware == null
                        ? null
                        : hardware.copy(android.graphics.Bitmap.Config.ARGB_8888, false);
                if (bmp == null) {
                  out[0] = com.deepseekharness.app.util.UiText.text("[ERR] 截屏数据无法解析");
                } else {
                  if (closed.get() || !validRun.getAsBoolean())
                    out[0] = "[ERR] SCREENSHOT_RUN_CHANGED";
                  else out[0] = saveShot(bmp, mcp);
                }
              } catch (Throwable t) {
                out[0] =
                    com.deepseekharness.app.util.UiText.format(
                        "[ERR] 保存截屏失败：%s", SensitiveData.redact(String.valueOf(t)));
              } finally {
                if (bmp != null) bmp.recycle();
                if (hardware != null) hardware.recycle();
                try {
                  result.getHardwareBuffer().close();
                } catch (Throwable ignored) {
                }
                latch.countDown();
              }
            }

            @Override
            public void onFailure(int errorCode) {
              out[0] =
                  com.deepseekharness.app.util.UiText.format(
                      errorCode == ERROR_TAKE_SCREENSHOT_INTERVAL_TIME_SHORT
                          ? "[ERR] 截屏被系统拒绝（错误码 %d，太频繁了，隔一秒再试）"
                          : "[ERR] 截屏被系统拒绝（错误码 %d）",
                      errorCode);
              latch.countDown();
            }
          });
      if (!latch.await(10, java.util.concurrent.TimeUnit.SECONDS)) {
        return com.deepseekharness.app.util.UiText.text("[ERR] 截屏超时");
      }
      return instance == s && validRun.getAsBoolean() ? out[0] : "[ERR] SCREENSHOT_RUN_CHANGED";
    } catch (Throwable t) {
      return com.deepseekharness.app.util.UiText.format(
          "[ERR] 截屏失败：%s", SensitiveData.redact(String.valueOf(t)));
    } finally {
      closed.set(true);
      executor.shutdownNow();
    }
  }

  /** 保存到本应用外部私有目录，无需“所有文件访问”；MCP 通过原生响应传送图片。 */
  private static String saveShot(android.graphics.Bitmap bmp, boolean mcp) {
    try {
      DshaAccessibilityService service = instance;
      if (service == null) return com.deepseekharness.app.util.UiText.text(NOT_READY);
      if (mcp) {
        java.io.ByteArrayOutputStream encoded = new java.io.ByteArrayOutputStream();
        if (!bmp.compress(android.graphics.Bitmap.CompressFormat.PNG, 90, encoded))
          throw new java.io.IOException("PNG_ENCODING_FAILED");
        if (encoded.size() > 16 * 1024 * 1024) return "[ERR] SCREENSHOT_TOO_LARGE";
        return new org.json.JSONObject()
            .put("kind", "dsha-screenshot-v1")
            .put("mimeType", "image/png")
            .put(
                "data",
                android.util.Base64.encodeToString(
                    encoded.toByteArray(), android.util.Base64.NO_WRAP))
            .toString();
      }
      java.io.File base = service.getExternalFilesDir(android.os.Environment.DIRECTORY_PICTURES);
      if (base == null)
        return com.deepseekharness.app.util.UiText.choose(
            "[ERR] 截屏存储暂不可用，请检查设备存储。",
            "[ERR] Screenshot storage is unavailable. Check device storage.");
      java.io.File dir = new java.io.File(base, "DSHA");
      if (!dir.isDirectory() && !dir.mkdirs()) {
        return com.deepseekharness.app.util.UiText.format("[ERR] 建不了目录 %s", dir);
      }
      java.io.File f =
          new java.io.File(
              dir,
              "screen-"
                  + new java.text.SimpleDateFormat("yyyyMMdd-HHmmss", java.util.Locale.ROOT)
                      .format(new java.util.Date())
                  + "-"
                  + java.util.UUID.randomUUID().toString().substring(0, 8)
                  + ".png");
      try (java.io.FileOutputStream fo = new java.io.FileOutputStream(f)) {
        if (!bmp.compress(android.graphics.Bitmap.CompressFormat.PNG, 90, fo))
          throw new java.io.IOException("PNG_ENCODING_FAILED");
      }
      return com.deepseekharness.app.util.UiText.format(
          "OK 截屏已保存：%s（%sx%s）", f.getAbsolutePath(), bmp.getWidth(), bmp.getHeight());
    } catch (Throwable t) {
      return com.deepseekharness.app.util.UiText.format(
          "[ERR] 写截屏文件失败：%s", SensitiveData.redact(String.valueOf(t)));
    }
  }

  @Override
  public void onInterrupt() {
    // 无需处理：本服务不提供持续反馈
  }

  @Override
  public boolean onUnbind(android.content.Intent intent) {
    // Some ROMs unbind without destroying the service. Do not report a
    // stale connected instance or keep a screen grant across that gap.
    HttpShellService.revokeScreenGrant(this);
    instance = null;
    stopWatch();
    return super.onUnbind(intent);
  }

  @Override
  public void onDestroy() {
    HttpShellService.revokeScreenGrant(this);
    instance = null;
    stopWatch();
    super.onDestroy();
  }
}
