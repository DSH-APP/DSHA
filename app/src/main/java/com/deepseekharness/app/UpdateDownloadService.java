package com.deepseekharness.app;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.os.Build;
import android.os.IBinder;
import androidx.core.app.NotificationCompat;
import androidx.lifecycle.Observer;
import com.deepseekharness.app.core.UpdateEngine;
import com.deepseekharness.app.core.UpdateRepository;
import com.deepseekharness.app.ui.UpdateActivity;

/** 用户发起的 APK 下载；前台通知和重投递恢复独立于页面。 */
public final class UpdateDownloadService extends Service {
    private static final String CHANNEL = "dsha_update_download";
    private static final int ID = 1004;
    private static final String CANCEL = "com.deepseekharness.app.UPDATE_CANCEL";
    private UpdateEngine engine;
    private boolean observing;
    private android.os.PowerManager.WakeLock wakeLock;
    private final Observer<UpdateRepository.State> observer = state -> {
        if (state.busy) {
            notifyState(state);
        } else {
            stopForeground(false);
            notifyState(state);
            stopSelf();
        }
    };
    @Override public void onCreate() {
        super.onCreate();
        engine = UpdateEngine.get(this);
        if (Build.VERSION.SDK_INT >= 26) getSystemService(NotificationManager.class).createNotificationChannel(
                new NotificationChannel(CHANNEL, "应用更新下载", NotificationManager.IMPORTANCE_LOW));
    }
    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        try {
            // 即使重投递的是取消动作，也先满足 startForegroundService 的时限契约。
            startForeground(ID, notification(engine.state().getValue()));
            if (intent != null && CANCEL.equals(intent.getAction())) {
                engine.cancel();
                if (!observing) { stopForeground(true); stopSelf(); }
                return START_NOT_STICKY;
            }
            if (!engine.shouldResume()) { stopForeground(true); stopSelf(); return START_NOT_STICKY; }
            if (wakeLock == null) wakeLock = getSystemService(android.os.PowerManager.class)
                    .newWakeLock(android.os.PowerManager.PARTIAL_WAKE_LOCK, "DSHA:update");
            if (!wakeLock.isHeld()) wakeLock.acquire(2 * 60 * 60 * 1000L);
            engine.runDownload();
            if (!observing) { observing = true; engine.state().observeForever(observer); }
            return START_REDELIVER_INTENT;
        } catch (Exception error) {
            engine.startFailed(error);
            stopForeground(true); stopSelf();
            return START_NOT_STICKY;
        }
    }
    private Notification notification(UpdateRepository.State state) {
        PendingIntent open = PendingIntent.getActivity(this, ID, new Intent(this, UpdateActivity.class),
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        NotificationCompat.Builder builder = new NotificationCompat.Builder(this, CHANNEL)
                .setSmallIcon(android.R.drawable.stat_sys_download).setContentTitle(state != null && state.apk != null ? "DSHA 更新已就绪" : "DSHA 应用更新")
                .setContentText(state == null ? "正在准备下载…" : state.message)
                .setContentIntent(open).setOnlyAlertOnce(true).setOngoing(state != null && state.busy)
                .setAutoCancel(state == null || !state.busy);
        if (state != null && state.busy) {
            builder.setProgress(100, state.total > 0 ? (int) (state.downloaded * 100 / state.total) : 0, state.total <= 0);
            PendingIntent cancel = PendingIntent.getService(this, ID, new Intent(this, UpdateDownloadService.class).setAction(CANCEL),
                    PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
            builder.addAction(0, "取消", cancel);
        }
        return builder.build();
    }
    private void notifyState(UpdateRepository.State state) {
        try { getSystemService(NotificationManager.class).notify(ID, notification(state)); }
        catch (SecurityException ignored) { }
    }
    @Override public void onTimeout(int startId, int fgsType) {
        // Android 15+ dataSync 配额到期必须及时退出；保留分段文件供下一次前台操作继续。
        engine.pause("系统已暂停长时间下载，进度已保留，请在更新页继续");
        stopForeground(true); stopSelf();
    }
    @Override public void onDestroy() {
        if (observing) engine.state().removeObserver(observer);
        if (engine.shouldResume()) engine.pause("下载服务已中断，进度已保留，可继续下载");
        if (wakeLock != null && wakeLock.isHeld()) wakeLock.release();
        super.onDestroy();
    }
    @Override public IBinder onBind(Intent intent) { return null; }
}
