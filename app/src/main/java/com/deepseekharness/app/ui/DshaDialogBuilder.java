package com.deepseekharness.app.ui;

import android.content.Context;
import androidx.appcompat.app.AlertDialog;
import com.deepseekharness.app.R;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;

/** 所有应用内弹窗共用 Material 容器、排版、按钮和窗口动画，不接管业务监听器。 */
public final class DshaDialogBuilder extends MaterialAlertDialogBuilder {
  private final boolean safetyConfirmation;
  private final long consentRevision;
  private android.content.DialogInterface.OnClickListener positive;
  private static final java.util.Map<AlertDialog, Long> safetyDialogs =
      new java.util.WeakHashMap<>();

  public DshaDialogBuilder(Context context) {
    this(context, false);
  }

  /** Only explicit yes/no safety approvals opt in. Parameter, scope and /ask dialogs never do. */
  public DshaDialogBuilder(Context context, boolean safetyConfirmation) {
    super(context, R.style.Dialog_DSHA_Material);
    this.safetyConfirmation = safetyConfirmation;
    consentRevision =
        safetyConfirmation
            ? new com.deepseekharness.app.core.DeviceGrants(context).consentPolicy().revision()
            : -1;
  }

  @Override
  public DshaDialogBuilder setPositiveButton(
      CharSequence text, android.content.DialogInterface.OnClickListener listener) {
    positive = listener;
    super.setPositiveButton(
        text,
        listener == null
            ? null
            : (dialog, which) -> {
              if (!safetyConfirmation
                  || consentRevision
                      == new com.deepseekharness.app.core.DeviceGrants(getContext())
                          .consentPolicy()
                          .revision()) listener.onClick(dialog, which);
            });
    return this;
  }

  @Override
  public DshaDialogBuilder setPositiveButton(
      int text, android.content.DialogInterface.OnClickListener listener) {
    return setPositiveButton(getContext().getText(text), listener);
  }

  @Override
  public AlertDialog show() {
    var policy =
        safetyConfirmation
            ? new com.deepseekharness.app.core.DeviceGrants(getContext()).consentPolicy()
            : null;
    if (policy != null && policy.revision() != consentRevision) return create();
    if (positive != null && policy != null && policy.currentGlobal(consentRevision)) {
      AlertDialog dialog = create();
      // This builder exists only because the user already requested its concrete action.
      if (new com.deepseekharness.app.core.DeviceGrants(getContext())
          .consentPolicy()
          .currentGlobal(consentRevision))
        positive.onClick(dialog, android.content.DialogInterface.BUTTON_POSITIVE);
      return dialog;
    }
    return super.show();
  }

  public static void invalidateSafetyConfirmations() {
    new android.os.Handler(android.os.Looper.getMainLooper())
        .post(
            () -> {
              for (var entry : new java.util.ArrayList<>(safetyDialogs.entrySet())) {
                AlertDialog dialog = entry.getKey();
                if (dialog != null
                    && entry.getValue()
                        != new com.deepseekharness.app.core.DeviceGrants(dialog.getContext())
                            .consentPolicy()
                            .revision()) {
                  if (dialog.isShowing()) dialog.cancel();
                  safetyDialogs.remove(dialog);
                }
              }
            });
  }

  @Override
  public AlertDialog create() {
    AlertDialog dialog = super.create();
    if (safetyConfirmation) safetyDialogs.put(dialog, consentRevision);
    if (dialog.getWindow() != null) {
      android.view.Window window = dialog.getWindow();
      window.setWindowAnimations(R.style.Animation_DSHA_Dialog);
      // 只在窗口第一次真正显示后设置尺寸。持续监听布局会在动画、输入法和
      // CardSheet 的底部测量期间反复调用 setLayout，导致 Language 首帧停顿。
      window
          .getDecorView()
          .post(
              () -> {
                if (dialog.isShowing() && window.getDecorView().getWindowToken() != null)
                  sizeOnce(window, window.getDecorView());
              });
    }
    return dialog;
  }

  private void sizeOnce(android.view.Window window, android.view.View view) {
    android.view.WindowManager.LayoutParams attributes = window.getAttributes();
    int gravity = attributes.gravity;
    // CardSheet.show() 在 show() 后同步设置底部和 MATCH_PARENT；不能把它改回中心窄窗。
    if ((gravity & android.view.Gravity.VERTICAL_GRAVITY_MASK) == android.view.Gravity.BOTTOM
        || attributes.width == android.view.ViewGroup.LayoutParams.MATCH_PARENT) return;
    {
      android.content.res.Resources resources = getContext().getResources();
      float density = resources.getDisplayMetrics().density;
      int width =
          Math.min(
              Math.round(560 * density),
              Math.min(
                  resources.getDisplayMetrics().widthPixels,
                  Math.round(resources.getConfiguration().screenWidthDp * density)));
      int maxHeight = Math.round((resources.getConfiguration().screenHeightDp - 24) * density);
      int height = view.getHeight() > maxHeight ? maxHeight : attributes.height;
      if (width != attributes.width || height != attributes.height) window.setLayout(width, height);
    }
  }
}
