package com.deepseekharness.app.screen;

import android.content.Context;
import android.content.SharedPreferences;
import com.deepseekharness.app.DshaAccessibilityService;
import com.deepseekharness.app.HttpShellService;
import com.deepseekharness.app.util.Constants;
import com.deepseekharness.app.util.ScreenCapabilityGate;
import com.deepseekharness.app.util.ScreenModePolicy;
import com.deepseekharness.app.util.ScreenModePolicy.Mode;
import com.deepseekharness.app.vscreen.VirtualScreenManager;

/**
 * 识屏模式的唯一读写入口：off / physical / virtual 互斥。
 *
 * <p>新键 {@link #KEY}（不改旧键）；不在可迁移设置白名单内，本机授权状态不随备份迁移。切换先收尾当前模式：离开
 * virtual 走 close 并核验（待清理时拒绝），离开 physical 撤销本轮授权与节点租约。准入丢失只降为 off 并记录原因，不自动换模式。
 */
public final class ScreenMode {
  private ScreenMode() {}

  public static final String KEY = "screen_mode";
  public static final String REASON_KEY = "screen_mode_reason";

  private static final Object LOCK = new Object();

  public static final class Result {
    public final ScreenModePolicy.Switch outcome;
    public final Mode mode;

    /** 失败或降级的技术原因代码；成功时为空串。 */
    public final String code;

    /** 进入 virtual 时自动创建虚拟屏的结果（可能为空）。 */
    public final org.json.JSONObject start;

    Result(ScreenModePolicy.Switch outcome, Mode mode, String code, org.json.JSONObject start) {
      this.outcome = outcome;
      this.mode = mode;
      this.code = code == null ? "" : code;
      this.start = start;
    }

    public boolean ok() {
      return outcome == ScreenModePolicy.Switch.ALLOWED
          || outcome == ScreenModePolicy.Switch.UNCHANGED;
    }
  }

  private static SharedPreferences prefs(Context context) {
    return context
        .getApplicationContext()
        .getSharedPreferences(Constants.PREFS, Context.MODE_PRIVATE);
  }

  /** 当前模式；新键缺失时按 {@link ScreenModePolicy#migrate} 写入一次默认值。 */
  public static Mode current(Context context) {
    SharedPreferences prefs = prefs(context);
    String stored = prefs.getString(KEY, null);
    if (stored != null) return Mode.parse(stored);
    synchronized (LOCK) {
      stored = prefs.getString(KEY, null);
      if (stored != null) return Mode.parse(stored);
      boolean virtualLive;
      try {
        var state = VirtualScreenManager.from(context).lifecycleState();
        virtualLive = state != com.deepseekharness.app.util.VirtualScreenLifecycleState.STOPPED;
      } catch (RuntimeException unavailable) {
        virtualLive = false;
      }
      Mode migrated = ScreenModePolicy.migrate(virtualLive, accessibility(context));
      prefs.edit().putString(KEY, migrated.wire()).apply();
      return migrated;
    }
  }

  /** 最近一次自动降级的原因代码；用户主动切换后清空。 */
  public static String lastReason(Context context) {
    return prefs(context).getString(REASON_KEY, "");
  }

  /** 桥用：返回 null 放行，否则返回稳定拒绝码。 */
  public static String bridgeRejection(
      Context context, ScreenModePolicy.Domain domain, String operation) {
    if (ScreenModePolicy.exempt(domain, operation)) return null;
    return ScreenModePolicy.rejection(current(context), domain);
  }

  public static ScreenCapabilityGate.Verdict verdict(Context context, Mode mode) {
    ScreenCapabilityGate.Inputs in = ScreenCapabilities.sample(context);
    return mode == Mode.VIRTUAL
        ? ScreenCapabilityGate.virtual(in)
        : ScreenCapabilityGate.physical(in);
  }

  /**
   * 用户切换模式。会阻塞（离开 virtual 时等待 close，进入 virtual 时等待创建），必须在工作线程调用。
   *
   * @param orientation 进入 virtual 时自动创建的方向（portrait / landscape）
   */
  public static Result request(Context context, Mode target, String orientation) {
    if (android.os.Looper.myLooper() == android.os.Looper.getMainLooper())
      throw new IllegalStateException("ScreenMode.request blocks; call it off the main thread");
    Context app = context.getApplicationContext();
    VirtualScreenManager manager = VirtualScreenManager.from(app);
    synchronized (LOCK) {
      Mode current = current(app);
      boolean pending = manager.lifecycleState().cleanupPending();
      ScreenModePolicy.Switch outcome =
          ScreenModePolicy.evaluate(
              current,
              target,
              ScreenModePolicy.admitted(target, ScreenCapabilities.sample(app)),
              pending);
      if (outcome == ScreenModePolicy.Switch.UNCHANGED) {
        // 已在 virtual 但虚拟屏未运行（例如上次创建失败或已关闭）：再次选择即重新创建。
        org.json.JSONObject start =
            target == Mode.VIRTUAL
                    && manager.lifecycleState()
                        == com.deepseekharness.app.util.VirtualScreenLifecycleState.STOPPED
                ? manager.start(orientation)
                : null;
        return new Result(outcome, current, startCode(start), start);
      }
      if (outcome != ScreenModePolicy.Switch.ALLOWED)
        return new Result(outcome, current, outcome.name(), null);
      if (current == Mode.VIRTUAL) {
        manager.close();
        if (manager.lifecycleState().cleanupPending())
          return new Result(
              ScreenModePolicy.Switch.CLEANUP_PENDING,
              current,
              "VSCREEN_" + manager.lifecycleState().name(),
              null);
      }
      if (current == Mode.PHYSICAL) HttpShellService.screenPolicyChanged(app);
      prefs(app).edit().putString(KEY, target.wire()).putString(REASON_KEY, "").commit();
      org.json.JSONObject start = target == Mode.VIRTUAL ? manager.start(orientation) : null;
      return new Result(outcome, target, startCode(start), start);
    }
  }

  /**
   * 统一撤销钩子：某项能力被撤销（用户关开关、Shizuku 权限丢失等）。当前模式不再保有授权时降为 off 并记录原因；
   * 不自动换到另一模式。虚拟屏本身的回收由 {@link VirtualScreenManager#revokeFor} 负责。
   */
  public static void capabilityRevoked(Context context, String reason) {
    Context app = context.getApplicationContext();
    synchronized (LOCK) {
      Mode current = current(app);
      Mode next =
          ScreenModePolicy.afterCapabilityChange(
              current, ScreenModePolicy.retained(current, ScreenCapabilities.sample(app)));
      if (next == current) return;
      prefs(app).edit().putString(KEY, next.wire()).putString(REASON_KEY, reason).apply();
      if (current == Mode.PHYSICAL) HttpShellService.screenPolicyChanged(app);
    }
  }

  private static String startCode(org.json.JSONObject start) {
    if (start == null || start.optBoolean("ok")) return "";
    String code = start.optString("code", start.optString("error"));
    return code.isEmpty() ? "VSCREEN_START_FAILED" : code;
  }

  static boolean accessibility(Context context) {
    try {
      return DshaAccessibilityService.isConnected() || DshaAccessibilityService.enabled(context);
    } catch (RuntimeException unavailable) {
      return false;
    }
  }
}
