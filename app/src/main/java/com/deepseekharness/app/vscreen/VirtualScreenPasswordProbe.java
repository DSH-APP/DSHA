package com.deepseekharness.app.vscreen;

import android.view.accessibility.AccessibilityNodeInfo;
import com.deepseekharness.app.DshaAccessibilityService;
import com.deepseekharness.app.util.VirtualScreenTextInputPolicy;

/**
 * 宿主侧读取虚拟屏当前输入焦点的密码证据（T97 安全切）。
 *
 * <p>只读：解析该显示的唯一应用窗口、取 {@code FOCUS_INPUT} 焦点节点并判断 {@code isPassword()}；不登记目标、
 * 不签发输入凭据、不改变 Core/Manager 状态。读取失败一律如实返回「不可读」，由
 * {@link VirtualScreenTextInputPolicy} 决定 fail-closed；绝不用 Core 的包名提示、上一次观察或上一次树来冒充证据。
 *
 * <p>这是最佳努力检查：它只覆盖宿主无障碍能看到的焦点节点，且与随后的 Core 注入之间存在时间窗。自定义控件
 * 不暴露 {@code isPassword()} 时读到的只是「不可读」，不是「安全」。
 */
final class VirtualScreenPasswordProbe {
  private VirtualScreenPasswordProbe() {}

  /** 一次只读探测结果；字段都是稳定码，不包含焦点文本或界面内容。 */
  static final class Evidence {
    final VirtualScreenTextInputPolicy.FocusEvidence focus;
    final String windowDiagnostic;
    final boolean targetChanged;

    Evidence(
        VirtualScreenTextInputPolicy.FocusEvidence focus,
        String windowDiagnostic,
        boolean targetChanged) {
      this.focus = focus;
      this.windowDiagnostic = windowDiagnostic == null ? "UNKNOWN" : windowDiagnostic;
      this.targetChanged = targetChanged;
    }
  }

  static Evidence read(int displayId) {
    if (!DshaAccessibilityService.isConnected())
      return new Evidence(
          VirtualScreenTextInputPolicy.FocusEvidence.ACCESSIBILITY_UNAVAILABLE,
          "ACCESSIBILITY_UNAVAILABLE",
          false);
    if (displayId <= 0)
      return new Evidence(
          VirtualScreenTextInputPolicy.FocusEvidence.FOCUS_UNREADABLE,
          "DISPLAY_UNAVAILABLE",
          false);
    AccessibilityNodeInfo root = null;
    AccessibilityNodeInfo focus = null;
    try {
      // virtualRoot 会校验 ACTION_AUTHORITY（授权时的窗口目标）；窗口变了就是「未发送」而不是猜焦点。
      root = DshaAccessibilityService.virtualRoot(displayId);
      if (root == null)
        return new Evidence(
            VirtualScreenTextInputPolicy.FocusEvidence.FOCUS_UNREADABLE,
            windowDiagnostic(displayId),
            false);
      focus = root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT);
      return new Evidence(focusEvidence(focus), "READABLE", false);
    } catch (DshaAccessibilityService.TargetChangedException changed) {
      return new Evidence(
          VirtualScreenTextInputPolicy.FocusEvidence.FOCUS_UNREADABLE, "TARGET_CHANGED", true);
    } catch (Throwable unavailable) {
      return new Evidence(
          VirtualScreenTextInputPolicy.FocusEvidence.FOCUS_UNREADABLE,
          windowDiagnostic(displayId),
          false);
    } finally {
      recycle(focus);
      recycle(root);
    }
  }

  /** 焦点节点 → 证据；只调用只读访问器，异常按不可读处理。 */
  static VirtualScreenTextInputPolicy.FocusEvidence focusEvidence(AccessibilityNodeInfo focus) {
    if (focus == null) return VirtualScreenTextInputPolicy.FocusEvidence.FOCUS_UNREADABLE;
    try {
      if (focus.isPassword()) return VirtualScreenTextInputPolicy.FocusEvidence.PASSWORD_FOCUSED;
      if (focus.isEditable() && focus.isEnabled())
        return VirtualScreenTextInputPolicy.FocusEvidence.SAFE_FOCUS;
      return VirtualScreenTextInputPolicy.FocusEvidence.FOCUS_UNREADABLE;
    } catch (RuntimeException unavailable) {
      return VirtualScreenTextInputPolicy.FocusEvidence.FOCUS_UNREADABLE;
    }
  }

  private static String windowDiagnostic(int displayId) {
    try {
      return DshaAccessibilityService.displayWindowDiagnostic(displayId);
    } catch (Throwable unavailable) {
      return "ROOT_UNAVAILABLE";
    }
  }

  private static void recycle(AccessibilityNodeInfo node) {
    if (node == null) return;
    try {
      node.recycle();
    } catch (Throwable ignored) {
      // 回收失败不影响本次判断，也不能把异常带出只读探测。
    }
  }
}
