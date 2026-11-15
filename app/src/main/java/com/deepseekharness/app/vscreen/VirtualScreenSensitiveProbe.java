package com.deepseekharness.app.vscreen;

import android.view.accessibility.AccessibilityNodeInfo;
import com.deepseekharness.app.DshaAccessibilityService;
import com.deepseekharness.app.util.SensitiveScreenClassifier;
import com.deepseekharness.app.util.SensitiveScreenPolicy;
import java.util.ArrayList;
import java.util.List;

/**
 * 宿主侧读取虚拟屏敏感界面证据（T119）。
 *
 * <p>只读：解析该显示的唯一应用窗口，按预算遍历控件树，收集 {@code isPassword}/可编辑/文本/描述/提示/资源 id
 * 等字段交给纯逻辑 {@link SensitiveScreenClassifier}；不登记目标、不签发输入凭据、不改变 Core/Manager 状态。
 * 返回只有类别与稳定依据码，不包含界面文本。
 *
 * <p>读取失败如实返回「不可读」：已知敏感应用会归为 {@link SensitiveScreenPolicy.Category#SECURE_UNKNOWN}
 * （默认 ASK），其它情况返回无类别，交给原有按应用确认。无障碍未连接时没有语义证据通道，不做分类，
 * 这是文档化的保护降级，不是「安全」。
 *
 * <p>限制与 T97 密码探测相同：只覆盖宿主无障碍能看到的控件，与随后注入之间存在时间窗；自定义控件可能漏判。
 */
public final class VirtualScreenSensitiveProbe {
  /** 单次分类的遍历上限；与无障碍树读取预算同一数量级，避免每个动作读整棵巨树。 */
  static final int MAX_NODES = 600;

  static final int MAX_DEPTH = 40;

  private VirtualScreenSensitiveProbe() {}

  /** 一次只读分类结果；字段都是稳定码，不包含界面文本。 */
  public static final class Evidence {
    public final SensitiveScreenPolicy.Category category;
    public final String basis;
    public final String packageName;
    public final String windowDiagnostic;
    public final boolean targetChanged;
    public final boolean readable;

    Evidence(
        SensitiveScreenPolicy.Category category,
        String basis,
        String packageName,
        String windowDiagnostic,
        boolean targetChanged,
        boolean readable) {
      this.category = category;
      this.basis = basis == null ? "" : basis;
      this.packageName = packageName == null ? "" : packageName;
      this.windowDiagnostic = windowDiagnostic == null ? "UNKNOWN" : windowDiagnostic;
      this.targetChanged = targetChanged;
      this.readable = readable;
    }

    static Evidence skipped(String diagnostic) {
      return new Evidence(null, "", "", diagnostic, false, false);
    }
  }

  public static Evidence read(int displayId, String expectedPackage) {
    if (!DshaAccessibilityService.isConnected())
      return Evidence.skipped("ACCESSIBILITY_UNAVAILABLE");
    if (displayId <= 0) return Evidence.skipped("DISPLAY_UNAVAILABLE");
    AccessibilityNodeInfo root = null;
    try {
      // virtualRoot 会校验 ACTION_AUTHORITY（若有）；窗口变了就是「未发送」，不去猜分类。
      root = DshaAccessibilityService.virtualRoot(displayId);
      if (root == null) {
        String diagnostic = windowDiagnostic(displayId);
        SensitiveScreenClassifier.Result unknown =
            SensitiveScreenClassifier.unreadableSensitiveApp(expectedPackage);
        return new Evidence(
            unknown == null ? null : unknown.category,
            unknown == null ? "" : unknown.basis,
            expectedPackage,
            diagnostic,
            false,
            false);
      }
      CharSequence rootPackage = root.getPackageName();
      String pkg =
          expectedPackage != null && !expectedPackage.isEmpty()
              ? expectedPackage
              : rootPackage == null ? "" : rootPackage.toString();
      List<SensitiveScreenClassifier.Node> nodes = new ArrayList<>();
      int[] budget = {MAX_NODES};
      walk(root, 0, nodes, budget);
      SensitiveScreenClassifier.Result result = SensitiveScreenClassifier.classify(pkg, nodes);
      return new Evidence(
          result == null ? null : result.category,
          result == null ? "" : result.basis,
          pkg,
          "READABLE",
          false,
          true);
    } catch (DshaAccessibilityService.TargetChangedException changed) {
      return new Evidence(null, "", expectedPackage, "TARGET_CHANGED", true, false);
    } catch (Throwable unavailable) {
      SensitiveScreenClassifier.Result unknown =
          SensitiveScreenClassifier.unreadableSensitiveApp(expectedPackage);
      return new Evidence(
          unknown == null ? null : unknown.category,
          unknown == null ? "" : unknown.basis,
          expectedPackage,
          windowDiagnostic(displayId),
          false,
          false);
    } finally {
      recycle(root);
    }
  }

  private static void walk(
      AccessibilityNodeInfo node,
      int depth,
      List<SensitiveScreenClassifier.Node> out,
      int[] budget) {
    if (node == null || depth > MAX_DEPTH || budget[0] <= 0) return;
    budget[0]--;
    try {
      boolean password = node.isPassword();
      out.add(
          new SensitiveScreenClassifier.Node(
              textOf(node),
              descriptionOf(node),
              hintOf(node),
              viewIdOf(node),
              classNameOf(node),
              password,
              node.isEditable(),
              inputTypeOf(node)));
    } catch (RuntimeException unreadable) {
      // 单个节点读不出属性不影响整页分类；继续预算内的其它节点。
    }
    int children = 0;
    try {
      children = node.getChildCount();
    } catch (RuntimeException unavailable) {
      return;
    }
    for (int i = 0; i < children && budget[0] > 0; i++) {
      AccessibilityNodeInfo child = null;
      try {
        child = node.getChild(i);
        walk(child, depth + 1, out, budget);
      } catch (RuntimeException unreadable) {
        // 子节点读取失败只跳过该子树。
      } finally {
        recycle(child);
      }
    }
  }

  private static CharSequence textOf(AccessibilityNodeInfo node) {
    try {
      return node.getText();
    } catch (RuntimeException unavailable) {
      return null;
    }
  }

  private static CharSequence descriptionOf(AccessibilityNodeInfo node) {
    try {
      return node.getContentDescription();
    } catch (RuntimeException unavailable) {
      return null;
    }
  }

  private static CharSequence hintOf(AccessibilityNodeInfo node) {
    try {
      return node.getHintText();
    } catch (RuntimeException unavailable) {
      return null;
    }
  }

  private static String viewIdOf(AccessibilityNodeInfo node) {
    try {
      return node.getViewIdResourceName();
    } catch (RuntimeException unavailable) {
      return "";
    }
  }

  private static String classNameOf(AccessibilityNodeInfo node) {
    try {
      CharSequence name = node.getClassName();
      return name == null ? "" : name.toString();
    } catch (RuntimeException unavailable) {
      return "";
    }
  }

  private static int inputTypeOf(AccessibilityNodeInfo node) {
    try {
      return node.getInputType();
    } catch (RuntimeException unavailable) {
      return 0;
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
      // 回收失败不影响本次判断。
    }
  }
}
