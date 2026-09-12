package com.deepseekharness.app.ui;

import android.content.Context;
import android.provider.Settings;
import androidx.fragment.app.FragmentTransaction;
import com.deepseekharness.app.R;

/** 只动画化页面容器；保持终端进程和业务状态，尊重系统关闭动画设置。 */
final class UiMotion {
    private UiMotion() { }
    static FragmentTransaction page(Context context, FragmentTransaction transaction) {
        transaction.setReorderingAllowed(true);
        try {
            if(Settings.Global.getFloat(context.getContentResolver(),Settings.Global.TRANSITION_ANIMATION_SCALE,1f)>0)
                transaction.setCustomAnimations(R.anim.page_enter,R.anim.page_exit,R.anim.page_enter,R.anim.page_exit);
        } catch(RuntimeException ignored) { /* 读取设置失败仍可正常导航。 */ }
        return transaction;
    }
}
