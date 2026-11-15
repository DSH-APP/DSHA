package com.deepseekharness.app.vscreen;

import android.content.Context;
import android.content.SharedPreferences;
import com.deepseekharness.app.util.Constants;
import com.deepseekharness.app.util.VirtualScreenPreviewPolicy;

/**
 * 虚拟屏预览偏好的唯一读写入口：档位与收起意图都持久化在应用私有偏好里。
 *
 * <p>与系统悬浮预览的 opt-in 开关（{@link VirtualScreenOverlayController#KEY_ENABLED}）分开：
 * 本类只管 DSHA 前台页里的提示条 / 完整预览，不改悬浮窗权限通道。
 */
public final class VirtualScreenPreviewSettings {
  private VirtualScreenPreviewSettings() {}

  private static SharedPreferences prefs(Context context) {
    return context.getSharedPreferences(Constants.PREFS, Context.MODE_PRIVATE);
  }

  /** 当前档位；未设置或值无法识别时返回默认提示条。 */
  public static VirtualScreenPreviewPolicy.Mode mode(Context context) {
    return VirtualScreenPreviewPolicy.parse(
        prefs(context).getString(VirtualScreenPreviewPolicy.KEY_MODE, ""));
  }

  /**
   * 用户在设置里显式选择档位。选择即重新显示：同时清除收起意图，否则会出现「选了提示条却
   * 仍然看不到」的状态。
   */
  public static void setMode(Context context, VirtualScreenPreviewPolicy.Mode mode) {
    prefs(context)
        .edit()
        .putString(VirtualScreenPreviewPolicy.KEY_MODE, VirtualScreenPreviewPolicy.stored(mode))
        .putBoolean(VirtualScreenPreviewPolicy.KEY_COLLAPSED, false)
        .apply();
  }

  /** 是否已收起；收起跨虚拟屏代次与 App 重启保持，直到用户重新选择档位。 */
  public static boolean collapsed(Context context) {
    return prefs(context).getBoolean(VirtualScreenPreviewPolicy.KEY_COLLAPSED, false);
  }

  /** 收起当前预览（提示条上的「×」或完整预览的「−」）；不改档位，设置页仍能看到当前选择。 */
  public static void collapse(Context context) {
    prefs(context).edit().putBoolean(VirtualScreenPreviewPolicy.KEY_COLLAPSED, true).apply();
  }
}
