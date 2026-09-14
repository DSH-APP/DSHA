package com.deepseekharness.app;

import android.app.Application;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.os.Build;

/**
 * 应用入口：全局初始化。
 * 骨架阶段只建一个任务通知渠道；完整版另有配对/确认渠道（见原 Constants.CHANNEL_*）。
 */
public class DshaApp extends Application {

    @Override
    public void onCreate() {
        super.onCreate();
        // 必须在【任何 Locale.setDefault 之前】锁存系统语言：真机上 Resources.getSystem()
        // 会被 LanguageController 的 setDefault 污染，导致「跟随系统」自我锁死。
        com.deepseekharness.app.util.SystemLanguage.initialize();
        com.deepseekharness.app.ui.LanguageController.apply(this);
        ShizukuShell.init(this);
        com.deepseekharness.app.ui.ThemeController.apply(this);
        com.deepseekharness.app.core.RuntimeTasks.initialize(this);
        com.deepseekharness.app.core.DiagnosticLog.installCrashHandler(this);
        registerActivityLifecycleCallbacks(new com.deepseekharness.app.ui.ModernAndroidUi());
        registerActivityLifecycleCallbacks(new ForegroundActivity());
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationManager nm = getSystemService(NotificationManager.class);
            nm.createNotificationChannel(new NotificationChannel(
                    "dsh_task_channel", com.deepseekharness.app.util.UiText.text("任务通知"), NotificationManager.IMPORTANCE_LOW));
        }
    }
}
