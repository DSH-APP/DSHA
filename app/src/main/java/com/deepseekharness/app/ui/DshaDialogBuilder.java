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
            window.getDecorView().addOnLayoutChangeListener((view,l,t,r,b,ol,ot,or,ob)->{
                android.content.res.Resources resources=getContext().getResources();
                float density=resources.getDisplayMetrics().density;
                int width=Math.min(Math.round(560*density),Math.min(resources.getDisplayMetrics().widthPixels,
                        Math.round(resources.getConfiguration().screenWidthDp*density)));
                int maxHeight=Math.round((resources.getConfiguration().screenHeightDp-24)*density);
                int height=view.getHeight()>maxHeight?maxHeight:window.getAttributes().height;
                if(width!=window.getAttributes().width || height!=window.getAttributes().height)window.setLayout(width,height);
            });
        }
        return dialog;
    }
}
