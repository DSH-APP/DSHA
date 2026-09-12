package com.deepseekharness.app;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/**
 * ADB 保活的 Doze 兜底。
 *
 * 看门狗本体用 Handler.postDelayed 驱动，但设备进入深度休眠（Doze）后
 * Handler 会被无限推迟——用户第二天拿起手机时连接早断了却没人重连。
 * 所以再挂一条 AlarmManager 闹钟：{@code setAndAllowWhileIdle} 允许在 Doze 中
 * 唤醒（系统会把间隔拉长到 ~9 分钟，这个精度对保活足够），醒来后确保设备桥
 * 服务还在、并立刻探一次连接。
 */
public class AdbKeepAliveReceiver extends BroadcastReceiver {

    public static final String ACTION = "com.deepseekharness.app.ADB_KEEPALIVE";
    private static final int REQ_CODE = 71;
    private static final long INTERVAL_MS = 5 * 60_000L;

    /** 排下一次唤醒（一次性闹钟，每次收到后重排） */
    public static void schedule(Context ctx) {
        try {
            AlarmManager am = (AlarmManager) ctx.getSystemService(Context.ALARM_SERVICE);
            if (am == null) return;
            am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP,
                    System.currentTimeMillis() + INTERVAL_MS, pending(ctx));
        } catch (Throwable ignored) {
        }
    }

    public static void cancel(Context ctx) {
        try {
            AlarmManager am = (AlarmManager) ctx.getSystemService(Context.ALARM_SERVICE);
            if (am != null) am.cancel(pending(ctx));
        } catch (Throwable ignored) {
        }
    }

    private static PendingIntent pending(Context ctx) {
        Intent i = new Intent(ctx.getApplicationContext(), AdbKeepAliveReceiver.class).setAction(ACTION);
        return PendingIntent.getBroadcast(ctx.getApplicationContext(), REQ_CODE, i,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }

    @Override
    public void onReceive(Context ctx, Intent intent) {
        try {
            if (!DeviceBridgeService.isAdbEnabled(ctx)) {
                return; // 用户关了通道：不再排下一次，闹钟自然消失
            }
            // 服务若已被回收，kickNow 内部会按开关重新拉起
            DeviceBridgeService.kickNow(ctx, com.deepseekharness.app.util.UiText.text("Alarm 唤醒"));
            schedule(ctx);
        } catch (Throwable ignored) {
        }
    }
}
