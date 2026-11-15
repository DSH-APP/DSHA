package com.deepseekharness.app.ui;

import android.view.View;
import android.view.accessibility.AccessibilityNodeInfo;

/** 首次引导里自绘控件的无障碍角色：可点说明行报告为按钮，用途项报告为复选框。 */
final class OnboardingRoles {
  private OnboardingRoles() {}

  /** 整行可点的说明行：朗读为“按钮”。 */
  static final View.AccessibilityDelegate BUTTON =
      new View.AccessibilityDelegate() {
        @Override
        public void onInitializeAccessibilityNodeInfo(View host, AccessibilityNodeInfo info) {
          super.onInitializeAccessibilityNodeInfo(host, info);
          info.setClassName(android.widget.Button.class.getName());
        }
      };

  /** 复选框角色；checked 由视图的 selected 状态给出，locked 时报告为不可切换。 */
  static View.AccessibilityDelegate checkbox(boolean locked) {
    return new View.AccessibilityDelegate() {
      @Override
      public void onInitializeAccessibilityNodeInfo(View host, AccessibilityNodeInfo info) {
        super.onInitializeAccessibilityNodeInfo(host, info);
        info.setClassName(android.widget.CheckBox.class.getName());
        info.setCheckable(true);
        info.setChecked(host.isSelected());
        info.setSelected(false);
        if (locked) {
          info.setEnabled(false);
          info.setClickable(false);
          info.removeAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_CLICK);
        }
      }
    };
  }
}
