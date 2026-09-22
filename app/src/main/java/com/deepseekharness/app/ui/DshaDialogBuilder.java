package com.deepseekharness.app.ui;

import android.content.Context;
import androidx.appcompat.app.AlertDialog;
import com.deepseekharness.app.R;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;

/** 所有应用内弹窗共用 Material 容器、排版、按钮和窗口动画，不接管业务监听器。 */
public final class DshaDialogBuilder extends MaterialAlertDialogBuilder {
    public DshaDialogBuilder(Context context) { super(context, R.style.Dialog_DSHA_Material); }

    @Override public AlertDialog create() {
        AlertDialog dialog=super.create();
        if(dialog.getWindow()!=null) {
            android.view.Window window=dialog.getWindow();
            window.setWindowAnimations(R.style.Animation_DSHA_Dialog);
            // 只在窗口第一次真正显示后设置尺寸。持续监听布局会在动画、输入法和
            // CardSheet 的底部测量期间反复调用 setLayout，导致 Language 首帧停顿。
            window.getDecorView().post(() -> {
                if (dialog.isShowing() && window.getDecorView().getWindowToken() != null)
                    sizeOnce(window, window.getDecorView());
            });
        }
        return dialog;
    }

    private void sizeOnce(android.view.Window window, android.view.View view) {
        android.view.WindowManager.LayoutParams attributes=window.getAttributes();
        int gravity=attributes.gravity;
        // CardSheet.show() 在 show() 后同步设置底部和 MATCH_PARENT；不能把它改回中心窄窗。
        if ((gravity & android.view.Gravity.VERTICAL_GRAVITY_MASK)==android.view.Gravity.BOTTOM
                || attributes.width==android.view.ViewGroup.LayoutParams.MATCH_PARENT) return;
        {
                android.content.res.Resources resources=getContext().getResources();
                float density=resources.getDisplayMetrics().density;
                int width=Math.min(Math.round(560*density),Math.min(resources.getDisplayMetrics().widthPixels,
                        Math.round(resources.getConfiguration().screenWidthDp*density)));
                int maxHeight=Math.round((resources.getConfiguration().screenHeightDp-24)*density);
                int height=view.getHeight()>maxHeight?maxHeight:attributes.height;
                if(width!=attributes.width || height!=attributes.height)window.setLayout(width,height);
        }
    }
}
