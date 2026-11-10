package com.deepseekharness.app.ui;

import android.content.Context;
import androidx.fragment.app.Fragment;
import com.deepseekharness.app.R;

/**
 * UI v5 方案 A：设置的「列表 + 详情」双栏判定与页面推进。
 *
 * <p>窗口内容宽度 ≥ {@link #TWO_PANE_DP}dp（Material 3 expanded 档）时设置页常驻左侧列表，子页
 * 在右侧详情窗格切换；更窄时保持原来的整页推进与返回栈。子页再打开下一级页面时统一走
 * {@link #open}，由这里决定是推进详情窗格的子返回栈还是推进主容器，避免嵌套页面去找不存在的
 * {@code fragment_container}。
 */
public final class SettingsPanes {
  /** Material 3 expanded 窗口宽度；平板竖屏 768/820 仍走单栏推进。 */
  public static final int TWO_PANE_DP = 840;

  private SettingsPanes() {}

  /** 按当前窗口（含分屏、自由窗口）的可用宽度判定，不按设备类型或横竖屏。 */
  public static boolean twoPane(Context context) {
    return context != null
        && context.getResources().getConfiguration().screenWidthDp >= TWO_PANE_DP;
  }

  /** 从任意设置子页打开下一级页面；在双栏详情里则留在详情窗格内并可返回。 */
  public static void open(Fragment from, Fragment target, String backStackName) {
    SettingsFragment host = host(from);
    if (host != null && host.isTwoPane()) {
      host.pushDetail(target);
      return;
    }
    UiMotion.page(from.requireContext(), from.getParentFragmentManager().beginTransaction())
        .replace(R.id.fragment_container, target)
        .addToBackStack(backStackName)
        .commit();
  }

  private static SettingsFragment host(Fragment from) {
    for (Fragment parent = from.getParentFragment();
        parent != null;
        parent = parent.getParentFragment()) {
      if (parent instanceof SettingsFragment) return (SettingsFragment) parent;
    }
    return null;
  }
}
