package com.deepseekharness.app.screen;

import android.content.Context;
import com.deepseekharness.app.DeviceBridgeService;
import com.deepseekharness.app.DshaAccessibilityService;
import com.deepseekharness.app.RootShell;
import com.deepseekharness.app.ShizukuShell;
import com.deepseekharness.app.bridge.LocalNetworkAccess;
import com.deepseekharness.app.util.ScreenCapabilityGate;

/** 采样识屏准入所需的设备状态；判定交给纯函数 {@link ScreenCapabilityGate}。 */
public final class ScreenCapabilities {
  private ScreenCapabilities() {}

  public static ScreenCapabilityGate.Inputs sample(Context context) {
    Context app =
        context.getApplicationContext() == null ? context : context.getApplicationContext();
    return new ScreenCapabilityGate.Inputs(
        android.os.Build.VERSION.SDK_INT,
        // 冷启动时服务可能尚未连上；系统设置里已开启也算可用（读屏时仍由服务实例把关）。
        safe(() -> DshaAccessibilityService.isConnected() || DshaAccessibilityService.enabled(app)),
        safe(() -> RootShell.enabled(app)),
        safe(RootShell::present),
        shizuku(),
        safe(() -> DeviceBridgeService.isAdbEnabled(app)),
        safe(() -> LocalNetworkAccess.granted(app)),
        "connected".equals(DeviceBridgeService.adbState),
        safe(() -> android.provider.Settings.canDrawOverlays(app)));
  }

  private static ScreenCapabilityGate.Shizuku shizuku() {
    if (safe(ShizukuShell::isReady)) return ScreenCapabilityGate.Shizuku.READY;
    if (safe(ShizukuShell::hasPermission)) return ScreenCapabilityGate.Shizuku.PERMITTED;
    return ScreenCapabilityGate.Shizuku.NONE;
  }

  private static boolean safe(java.util.function.BooleanSupplier probe) {
    try {
      return probe.getAsBoolean();
    } catch (RuntimeException unavailable) {
      return false;
    }
  }
}
