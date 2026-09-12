package com.deepseekharness.app;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.os.Binder;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.Parcel;
import java.util.concurrent.atomic.AtomicBoolean;
import rikka.shizuku.Shizuku;

/** 兼容标准协议的管理器；重新索取 Binder 只在用户点击时发生，不代替管理器授权。 */
public final class ShizukuManagerCompat {
    private static final String PERMISSION = "moe.shizuku.manager.permission.API_V23";
    private static final String REQUEST_BINDER = "rikka.shizuku.intent.action.REQUEST_BINDER";
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static boolean requesting;
    private ShizukuManagerCompat() { }

    public static ApplicationInfo manager(Context context) {
        PackageManager pm = context.getPackageManager();
        try {
            // 权限声明者是协议管理器，名称变化时无需不断追加包名白名单。
            String owner = pm.getPermissionInfo(PERMISSION, 0).packageName;
            return pm.getApplicationInfo(owner, 0);
        } catch (PackageManager.NameNotFoundException | RuntimeException error) { return null; }
    }

    public static Intent launchIntent(Context context) {
        ApplicationInfo info = manager(context);
        return info == null ? null : context.getPackageManager().getLaunchIntentForPackage(info.packageName);
    }

    /** 官方与 thedjchi 的公开 REQUEST_BINDER 入口；仅接受所选管理器 UID 的单次回调。 */
    public static void reconnect(Activity activity, Runnable completed) {
        if (Looper.myLooper() != Looper.getMainLooper()) { MAIN.post(() -> reconnect(activity, completed)); return; }
        if (ShizukuShell.isAvailable()) { completed.run(); return; }
        if (requesting) { completed.run(); return; }
        ApplicationInfo info = manager(activity);
        if (info == null) { completed.run(); return; }
        Intent intent = new Intent(REQUEST_BINDER).setPackage(info.packageName);
        android.content.pm.ResolveInfo handler = activity.getPackageManager().resolveActivity(intent, 0);
        if (handler == null || handler.activityInfo == null || !handler.activityInfo.exported
                || handler.activityInfo.applicationInfo.uid != info.uid) {
            Intent launch = launchIntent(activity);
            if (launch != null) try { activity.startActivity(launch); } catch (RuntimeException ignored) { }
            completed.run(); return;
        }
        intent.setComponent(new android.content.ComponentName(handler.activityInfo.packageName, handler.activityInfo.name));
        requesting = true;
        AtomicBoolean finished = new AtomicBoolean();
        AtomicBoolean delivered = new AtomicBoolean();
        Runnable finish = () -> { if (finished.compareAndSet(false, true)) { requesting = false; completed.run(); } };
        Binder callback = new Binder() {
            @Override protected boolean onTransact(int code, Parcel data, Parcel reply, int flags) {
                if (code != 1 || Binder.getCallingUid() != info.uid || finished.get()
                        || !delivered.compareAndSet(false, true)) return false;
                try {
                    IBinder remote = data.readStrongBinder();
                    // 管理器另附的 APK 路径无需读取，绝不执行或加载外部 APK。
                    if (remote != null && remote.pingBinder()
                            && "moe.shizuku.server.IShizukuService".equals(remote.getInterfaceDescriptor())) {
                        MAIN.post(() -> {
                            if (finished.get()) return;
                            Shizuku.OnBinderReceivedListener[] listener = new Shizuku.OnBinderReceivedListener[1];
                            listener[0] = () -> MAIN.post(() -> { Shizuku.removeBinderReceivedListener(listener[0]); finish.run(); });
                            try {
                                Shizuku.addBinderReceivedListener(listener[0]);
                                // 交给本应用受保护的标准 Provider 完成 API 版本握手，避免调用库内部接口。
                                receiveThroughProvider(activity, remote);
                                MAIN.postDelayed(() -> Shizuku.removeBinderReceivedListener(listener[0]), 15000);
                            } catch (Throwable error) { Shizuku.removeBinderReceivedListener(listener[0]); finish.run(); }
                        });
                    } else MAIN.post(finish);
                } catch (Throwable error) { MAIN.post(finish); }
                return true;
            }
        };
        Bundle data = new Bundle(); data.putBinder("binder", callback); intent.putExtra("data", data);
        try { activity.startActivity(intent); }
        catch (RuntimeException error) { finish.run(); }
        MAIN.postDelayed(finish, 15000);
    }

    // 锁定的 API 12.2.0 / 13.1.5 均使用这一 Parcelable 线协议；仅此适配点接触库内部类型。
    // 入口此前已核对管理器 UID、Binder 描述符及单次请求，本应用 Provider 的系统权限不变。
    @android.annotation.SuppressLint("RestrictedApi")
    private static void receiveThroughProvider(Context context, IBinder binder) {
        Bundle received = new Bundle();
        received.putParcelable("moe.shizuku.privileged.api.intent.extra.BINDER",
                new moe.shizuku.api.BinderContainer(binder));
        context.getContentResolver().call(android.net.Uri.parse("content://"
                + context.getPackageName() + ".shizuku"), "sendBinder", null, received);
    }
}
