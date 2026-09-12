package com.deepseekharness.app;

import android.app.Activity;
import android.app.Application;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import androidx.appcompat.app.AlertDialog;
import androidx.fragment.app.FragmentActivity;
import com.deepseekharness.app.util.BridgeQuestions;
import java.util.concurrent.atomic.AtomicBoolean;

/** 一次提问的窗口资源；所有清理都绑定该窗口，不读取下一次请求的共享 UI 引用。 */
final class BridgeAskDialog implements Application.ActivityLifecycleCallbacks {
    private final FragmentActivity activity;
    private final BridgeQuestions questions;
    private final BridgeQuestions.Request request;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final AtomicBoolean closed = new AtomicBoolean();
    private AlertDialog dialog;
    private boolean registered;

    BridgeAskDialog(FragmentActivity activity, BridgeQuestions questions, BridgeQuestions.Request request) {
        this.activity = activity;
        this.questions = questions;
        this.request = request;
    }

    void show(String question, String[] options, String[] displayOptions) {
        main.post(() -> {
            if (closed.get() || !questions.pending(request)) return;
            if (!ForegroundActivity.isResumed(activity)) {
                questions.cancel(request, BridgeQuestions.End.BACKGROUND);
                close();
                return;
            }
            try {
                activity.getApplication().registerActivityLifecycleCallbacks(this);
                registered = true;
                AlertDialog.Builder builder = new com.deepseekharness.app.ui.DshaDialogBuilder(activity)
                        .setTitle(com.deepseekharness.app.util.UiText.text("助手提问")).setMessage(question)
                        .setPositiveButton(displayOptions[0], (d, w) -> questions.answer(request, options[0]));
                if (options.length > 1) builder.setNegativeButton(displayOptions[1],
                        (d, w) -> questions.answer(request, options[1]));
                if (options.length > 2) builder.setNeutralButton(displayOptions[2],
                        (d, w) -> questions.answer(request, options[2]));
                builder.setOnCancelListener(d -> questions.cancel(request, BridgeQuestions.End.DISMISSED));
                dialog = builder.create();
                dialog.setOnDismissListener(d -> {
                    // 没有按钮/返回键决定的消失只能视作窗口不可用，不能伪造用户答案。
                    questions.cancel(request, BridgeQuestions.End.UNAVAILABLE);
                    close();
                });
                if (closed.get() || !questions.pending(request)) { close(); return; }
                dialog.show();
            } catch (RuntimeException error) {
                questions.cancel(request, BridgeQuestions.End.UNAVAILABLE);
                close();
            }
        });
    }

    void close() {
        closed.set(true); // 先阻止还在主线程队列里的 show，再清理已显示的窗口。
        if (Looper.myLooper() == Looper.getMainLooper()) dismiss();
        else main.post(this::dismiss);
    }

    private void dismiss() {
        if (registered) {
            activity.getApplication().unregisterActivityLifecycleCallbacks(this);
            registered = false;
        }
        AlertDialog old = dialog;
        dialog = null;
        if (old != null) {
            old.setOnDismissListener(null);
            old.setOnCancelListener(null);
            try { old.dismiss(); } catch (RuntimeException ignored) { }
        }
    }

    private void left(Activity changed) {
        if (changed != activity) return;
        questions.cancel(request, BridgeQuestions.End.BACKGROUND);
        close();
    }
    @Override public void onActivityPaused(Activity changed) { left(changed); }
    @Override public void onActivityStopped(Activity changed) { left(changed); }
    @Override public void onActivityDestroyed(Activity changed) { left(changed); }
    @Override public void onActivityCreated(Activity a, Bundle b) { }
    @Override public void onActivityStarted(Activity a) { }
    @Override public void onActivityResumed(Activity a) { }
    @Override public void onActivitySaveInstanceState(Activity a, Bundle b) { }
}
