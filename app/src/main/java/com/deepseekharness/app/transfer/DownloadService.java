package com.deepseekharness.app.transfer;

import android.app.*;
import android.content.Intent;
import android.os.IBinder;
import android.os.PowerManager;
import androidx.core.app.NotificationCompat;
import androidx.lifecycle.Observer;
import com.deepseekharness.app.ui.FileTransfersActivity;
import com.deepseekharness.app.util.UiText;

/**
 * User-started dataSync service; boot/process restart leaves journals waiting for manual resume.
 */
public final class DownloadService extends Service {
  private static final String CHANNEL = "dsha_file_downloads";
  private static final int NOTIFICATION_ID =
      com.deepseekharness.app.util.NotificationIds.FILE_DOWNLOAD;
  private static final String PAUSE = "com.deepseekharness.app.FILE_PAUSE";
  private static final String CANCEL = "com.deepseekharness.app.FILE_CANCEL";
  private DownloadEngine engine;
  private PowerManager.WakeLock wakeLock;
  private boolean observing;
  private int lastStartId;
  private long ownedAdmission;
  private final Observer<DownloadEngine.State> observer =
      value -> {
        if (engine.isBusy()) {
          getSystemService(NotificationManager.class).notify(NOTIFICATION_ID, notification(value));
        } else {
          stopForeground(STOP_FOREGROUND_DETACH);
          getSystemService(NotificationManager.class).notify(NOTIFICATION_ID, notification(value));
          stopSelf(lastStartId);
        }
      };

  @Override
  public void onCreate() {
    super.onCreate();
    engine = DownloadEngine.get(this);
    getSystemService(NotificationManager.class)
        .createNotificationChannel(
            new NotificationChannel(
                CHANNEL,
                UiText.choose("文件下载", "File downloads"),
                NotificationManager.IMPORTANCE_LOW));
  }

  @Override
  public int onStartCommand(Intent intent, int flags, int startId) {
    lastStartId = startId;
    try {
      startForeground(NOTIFICATION_ID, notification(engine.state().getValue()));
      String action = intent == null ? "" : intent.getAction();
      String id = intent == null ? null : intent.getStringExtra("taskId");
      if (PAUSE.equals(action)) engine.pause(id);
      else if (CANCEL.equals(action)) engine.cancel(id);
      if (!engine.isBusy()) {
        stopForeground(STOP_FOREGROUND_DETACH);
        stopSelf(startId);
        return START_NOT_STICKY;
      }
      if (wakeLock == null) {
        wakeLock =
            getSystemService(PowerManager.class)
                .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "DSHA:file-download");
        wakeLock.setReferenceCounted(false);
      }
      if (!wakeLock.isHeld()) wakeLock.acquire(6 * 60 * 60 * 1000L);
      ownedAdmission = engine.runQueued();
      if (!observing) {
        observing = true;
        engine.state().observeForever(observer);
      }
    } catch (RuntimeException denied) {
      engine.pauseAll(UiText.choose("下载服务已暂停，请在文件页继续", "Downloads paused; resume in Files"));
      stopForeground(STOP_FOREGROUND_REMOVE);
      stopSelf(startId);
    }
    return START_NOT_STICKY;
  }

  private Notification notification(DownloadEngine.State state) {
    DownloadTask active = null;
    if (state != null)
      for (DownloadTask task : state.tasks)
        if (task.id.equals(state.activeId)) {
          active = task;
          break;
        }
    boolean busy = engine != null && engine.isBusy();
    PendingIntent open =
        PendingIntent.getActivity(
            this,
            NOTIFICATION_ID,
            new Intent(this, FileTransfersActivity.class).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    NotificationCompat.Builder builder =
        new NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle(
                active == null
                    ? UiText.choose("DSHA 文件下载", "DSHA file downloads")
                    : active.displayName)
            .setContentText(
                active == null
                    ? UiText.choose("在文件页查看下载记录", "View transfer history in Files")
                    : active.message)
            .setOnlyAlertOnce(true)
            .setContentIntent(open)
            .setOngoing(busy)
            .setAutoCancel(!busy);
    if (active != null && busy) {
      builder.setProgress(
          100, active.total == 0 ? 0 : (int) (active.downloaded * 100 / active.total), false);
      builder.addAction(0, UiText.choose("暂停", "Pause"), action(PAUSE, active.id, 1));
      builder.addAction(0, UiText.choose("取消", "Cancel"), action(CANCEL, active.id, 2));
    }
    return builder.build();
  }

  private PendingIntent action(String action, String id, int request) {
    return PendingIntent.getService(
        this,
        NOTIFICATION_ID + request,
        new Intent(this, DownloadService.class).setAction(action).putExtra("taskId", id),
        PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
  }

  @Override
  public void onTimeout(int startId, int fgsType) {
    engine.pauseAll(
        UiText.choose(
            "系统已暂停长时间下载，进度保留，请手动继续", "System time limit; progress kept for manual resume"));
    stopForeground(STOP_FOREGROUND_REMOVE);
    stopSelf();
  }

  @Override
  public void onDestroy() {
    if (observing) engine.state().removeObserver(observer);
    if (engine.isBusy())
      engine.serviceGone(
          ownedAdmission, UiText.choose("下载服务中断，进度保留", "Service interrupted; progress kept"));
    if (wakeLock != null && wakeLock.isHeld()) wakeLock.release();
    super.onDestroy();
  }

  @Override
  public IBinder onBind(Intent intent) {
    return null;
  }
}
