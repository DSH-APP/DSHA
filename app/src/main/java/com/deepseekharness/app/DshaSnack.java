package com.deepseekharness.app;

import android.content.Context;
import android.view.View;
import android.widget.Toast;

import androidx.fragment.app.Fragment;

import com.google.android.material.snackbar.Snackbar;

/**
 * 统一的轻提示。优先 Snackbar，拿不到 View 时回退 Toast。
 *
 * <p><b>为什么不继续用 Toast。</b>Android 11 起 Toast 被系统限制成两行、后台不给显示，
 * 而我们有一堆「装了哪些插件 / 跳过哪些 / 为什么」这种长结果，Toast 会直接截断 ——
 * 用户看到半句话。Snackbar 是 Material 的标配方案：不受行数硬限、能带一个动作按钮
 * （撤销、查看详情），而且是应用内的 View，能锚在底栏之上不被挡住。
 *
 * <p>Toast 保留作为回退：Service、BroadcastReceiver 这类没有 View 的地方仍然只能用它。
 */
final class DshaSnack {

    private DshaSnack() {
    }

    static void show(Fragment f, CharSequence msg) {
        show(f, msg, false);
    }

    static void showLong(Fragment f, CharSequence msg) {
        show(f, msg, true);
    }

    private static void show(Fragment f, CharSequence msg, boolean lng) {
        if (f == null) return;
        View v = f.getView();
        if (v == null || !f.isAdded()) {
            Context c = f.getContext();
            if (c != null) {
                Toast.makeText(c, msg, lng ? Toast.LENGTH_LONG : Toast.LENGTH_SHORT).show();
            }
            return;
        }
        show(v, msg, lng);
    }

    static void show(View anchor, CharSequence msg, boolean lng) {
        if (anchor == null) return;
        try {
            Snackbar sb = Snackbar.make(anchor, msg,
                    lng ? Snackbar.LENGTH_LONG : Snackbar.LENGTH_SHORT);
            // 多行不截断。Snackbar 默认 maxLines=2，长结果照样会被吞掉 —— 换 Toast 的
            // 意义就在于把话说完，所以这里放到 5 行。
            View tv = sb.getView().findViewById(com.google.android.material.R.id.snackbar_text);
            if (tv instanceof android.widget.TextView) {
                ((android.widget.TextView) tv).setMaxLines(5);
            }
            // 锚在底栏之上。不设的话 Snackbar 贴屏幕底边，正好压住底部导航。
            View nav = anchor.getRootView().findViewById(R.id.bottom_glass);
            if (nav == null) nav = anchor.getRootView().findViewById(R.id.bottom_nav);
            if (nav != null && nav.getVisibility() == View.VISIBLE) {
                sb.setAnchorView(nav);
            }
            sb.show();
        } catch (Throwable t) {
            Toast.makeText(anchor.getContext(), msg,
                    lng ? Toast.LENGTH_LONG : Toast.LENGTH_SHORT).show();
        }
    }

    /** 带一个动作按钮的版本（撤销、查看详情之类）。 */
    static void action(Fragment f, CharSequence msg, CharSequence label, Runnable onClick) {
        if (f == null) return;
        View v = f.getView();
        if (v == null) {
            show(f, msg, true);
            return;
        }
        try {
            Snackbar sb = Snackbar.make(v, msg, Snackbar.LENGTH_LONG)
                    .setAction(label, x -> {
                        if (onClick != null) onClick.run();
                    });
            View tv = sb.getView().findViewById(com.google.android.material.R.id.snackbar_text);
            if (tv instanceof android.widget.TextView) {
                ((android.widget.TextView) tv).setMaxLines(5);
            }
            View nav = v.getRootView().findViewById(R.id.bottom_glass);
            if (nav == null) nav = v.getRootView().findViewById(R.id.bottom_nav);
            if (nav != null && nav.getVisibility() == View.VISIBLE) {
                sb.setAnchorView(nav);
            }
            sb.show();
        } catch (Throwable t) {
            show(f, msg, true);
        }
    }
}
