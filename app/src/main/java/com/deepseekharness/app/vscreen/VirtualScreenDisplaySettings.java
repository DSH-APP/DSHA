package com.deepseekharness.app.vscreen;

import android.content.Context;
import android.content.SharedPreferences;
import com.deepseekharness.app.util.Constants;
import com.deepseekharness.app.util.VirtualScreenDisplayPreset;

/**
 * 虚拟屏显示规格选择的唯一读写入口；设置损坏、旧版本未知 id 或空值都按默认预设处理。
 *
 * <p>选择只影响之后的创建与旋转（旋转会按当前方向重建显示），不改运行中显示的尺寸；也不改 Core 的
 * 代次/帧身份与输入门。预设本身是白名单：只保存 id，不保存任意尺寸或 dpi。
 */
public final class VirtualScreenDisplaySettings {
  private VirtualScreenDisplaySettings() {}

  private static SharedPreferences prefs(Context context) {
    return context.getSharedPreferences(Constants.PREFS, Context.MODE_PRIVATE);
  }

  /** 当前选择；未设置或值无法识别时返回默认 1008×1792@320。 */
  public static VirtualScreenDisplayPreset.Preset preset(Context context) {
    return VirtualScreenDisplayPreset.resolve(
        prefs(context).getString(VirtualScreenDisplayPreset.KEY_PRESET, ""));
  }

  /** 用户显式选择白名单预设；白名单之外的 id 不写入，避免把坏值持久化。 */
  public static void setPreset(Context context, String id) {
    VirtualScreenDisplayPreset.Preset known = VirtualScreenDisplayPreset.parse(id);
    if (known == null) return;
    prefs(context).edit().putString(VirtualScreenDisplayPreset.KEY_PRESET, known.id).apply();
  }
}
