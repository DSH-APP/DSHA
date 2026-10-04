package com.deepseekharness.app.recovery;

import android.app.*;
import android.content.*;
import android.os.*;
import com.deepseekharness.app.R;
import com.deepseekharness.app.ui.RecoveryActivity;
import com.deepseekharness.app.util.UiText;
import java.io.IOException;

/** 用户启动的应急服务有自己的前台通知，不接入正式 Web 的看门狗。 */
public final class RecoveryService extends Service {
  private static final String CHANNEL = "dsha_emergency";
  private final Handler main = new Handler(Looper.getMainLooper());
  private PendingIntent stopAction;
  private String actionIdentity = "";

  public static void start(Context context, String temporaryKey) throws IOException {
    Intent intent = new Intent(context, RecoveryService.class).setAction("start");
    if (temporaryKey != null && !temporaryKey.isEmpty())
      intent.putExtra("temporary_key", temporaryKey);
    try {
      if (Build.VERSION.SDK_INT >= 26) context.startForegroundService(intent);
      else context.startService(intent);
    } catch (RuntimeException error) {
      throw new IOException("RECOVERY_FOREGROUND_UNAVAILABLE", error);
    }
  }

  @Override
  public void onCreate() {
    super.onCreate();
    if (Build.VERSION.SDK_INT >= 26)
      getSystemService(NotificationManager.class)
          .createNotificationChannel(
              new NotificationChannel(
                  CHANNEL,
                  UiText.choose("应急 DSH", "Emergency DSH"),
                  NotificationManager.IMPORTANCE_LOW));
    publish(UiText.choose("正在准备应急服务…", "Preparing the emergency service…"));
  }

  private void publish(String text) {
    Intent open =
        new Intent(this, RecoveryActivity.class)
            .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP | Intent.FLAG_ACTIVITY_CLEAR_TOP);
    PendingIntent content =
        PendingIntent.getActivity(
            this, 9060, open, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    Notification.Builder builder =
        Build.VERSION.SDK_INT >= 26
            ? new Notification.Builder(this, CHANNEL)
            : new Notification.Builder(this);
    builder
        .setSmallIcon(R.mipmap.ic_launcher)
        .setContentTitle(UiText.choose("DSHA 应急 DSH", "DSHA emergency DSH"))
        .setContentText(text)
        .setContentIntent(content)
        .setOnlyAlertOnce(true)
        .setOngoing(true);
    if (stopAction != null)
      builder.addAction(
          new Notification.Action.Builder(
                  null, UiText.choose("停止应急 DSH", "Stop emergency DSH"), stopAction)
              .build());
    if (Build.VERSION.SDK_INT >= 34)
      startForeground(
          com.deepseekharness.app.util.NotificationIds.RECOVERY,
          builder.build(),
          android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);
    else startForeground(com.deepseekharness.app.util.NotificationIds.RECOVERY, builder.build());
  }

  private final Runnable refresh =
      new Runnable() {
        public void run() {
          var state = RecoveryController.get(RecoveryService.this).snapshot();
          boolean repairing = RecoveryRepairBroker.activeNativeRepairs() > 0;
          String identity = state.instanceId + ":" + state.generation;
          if (!identity.equals(actionIdentity)) {
            if (stopAction != null) stopAction.cancel();
            actionIdentity = identity;
            Intent stop =
                new Intent(RecoveryService.this, RecoveryService.class)
                    .setAction("stop")
                    .setData(
                        android.net.Uri.parse(
                            "dsha://emergency/" + state.instanceId + "/" + state.generation))
                    .putExtra("instance", state.instanceId)
                    .putExtra("generation", state.generation);
            stopAction =
                PendingIntent.getService(
                    RecoveryService.this, 0, stop, PendingIntent.FLAG_IMMUTABLE);
          }
          if (!state.busy && !state.ready && !state.live && !repairing) {
            stopForeground(true);
            stopSelf();
            return;
          }
          publish(
              repairing && !state.ready
                  ? UiText.choose(
                      "已确认的原生修复仍在执行，可打开查看结果。",
                      "An approved native repair is still running. Open to review its result.")
                  : state.detail);
          main.postDelayed(this, 1000);
        }
      };

  @Override
  public int onStartCommand(Intent intent, int flags, int id) {
    var controller = RecoveryController.get(this);
    if (intent != null && "start".equals(intent.getAction())) {
      String temporary = intent.getStringExtra("temporary_key");
      intent.removeExtra("temporary_key");
      controller.start(temporary);
    } else if (intent != null && "stop".equals(intent.getAction())) {
      var state = controller.snapshot();
      if (state.generation == intent.getLongExtra("generation", -1)
          && state.instanceId.equals(intent.getStringExtra("instance"))) controller.stop();
    }
    main.removeCallbacks(refresh);
    main.post(refresh);
    return START_NOT_STICKY;
  }

  @Override
  public IBinder onBind(Intent intent) {
    return null;
  }

  @Override
  public void onDestroy() {
    main.removeCallbacks(refresh);
    if (stopAction != null) stopAction.cancel();
    super.onDestroy();
  }
}
