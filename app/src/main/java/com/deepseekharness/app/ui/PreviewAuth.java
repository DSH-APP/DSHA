package com.deepseekharness.app.ui;

import android.app.Activity;
import com.deepseekharness.app.core.HarnessController;

/** 两种预览内核共用的异步鉴权；旧 Activity、旧进程的响应不能接管当前页面。 */
final class PreviewAuth {
    interface Callback { void complete(String url, String cookie, String error); }
    private final Activity activity;
    private int request;
    private boolean busy;
    PreviewAuth(Activity activity) { this.activity = activity; }
    boolean busy() { return busy; }
    void cancel() { request++; busy = false; }
    void refresh(Callback callback) {
        int id = ++request;
        busy = true;
        HarnessController controller = HarnessController.get(activity);
        String url = controller.getWebAuthUrl();
        long generation = controller.getWebGeneration();
        new Thread(() -> {
            String cookie = null;
            String error = null;
            try {
                if (url.isEmpty()) error = "Web 尚未就绪，请返回启动页查看服务状态";
                else {
                    cookie = controller.exchangeDshAuthCookie();
                    if (cookie == null) error = controller.getWebAuthFailure();
                }
            } catch (RuntimeException failure) { error = "无法验证 Web 访问权限，请稍后重试"; }
            String readyCookie = cookie, failure = error;
            activity.runOnUiThread(() -> {
                if (id != request || activity.isFinishing() || activity.isDestroyed()) return;
                busy = false;
                if (generation != controller.getWebGeneration() || !url.equals(controller.getWebAuthUrl())) {
                    callback.complete(null, null, "服务在鉴权期间重新启动，请稍后重试"); return;
                }
                callback.complete(url, readyCookie, failure);
            });
        }, "preview-auth").start();
    }
}
