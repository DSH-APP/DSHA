#!/usr/bin/env python3
"""Static guard: every DSHA notification body has a real app entry point.

通知正文统一走 MainActivity.homeIntent()（build197 导航重构）：该入口复用已有任务并携带
open_launch，避免用户已停在设置/终端页时点击通知没有反应。本守卫同时检查各通知使用该
入口，以及入口自身仍保留「打开启动页 + 复用任务」的语义。
"""
from pathlib import Path
import re
from verification_require import require

root = Path(__file__).resolve().parents[1] / 'app/src/main/java/com/deepseekharness/app'
checks = {
    'HarnessService.java': ['setContentIntent(pi)',
                            'com.deepseekharness.app.ui.MainActivity.homeIntent(this)'],
    'HttpShellService.java': ['setContentIntent(agentNotificationIntent())'],
    'DeviceBridgeService.java': ['setContentIntent(dshaNotificationIntent(false))',
                                 'com.deepseekharness.app.ui.MainActivity.homeIntent(this)'],
    'UpdateDownloadService.java': ['setContentIntent(open)', 'FLAG_ACTIVITY_CLEAR_TOP'],
    'backup/DataProtectionService.java': ['setContentIntent(open)', 'if(open==null)',
                                         'MaintenanceUiPorts.fromOwner(getApplicationContext())',
                                         '.dataProtectionPage(nativeJob.busy,maintenance.id)',
                                         '.runtimePage()'],
    'DshaApp.java': ['Intent dataProtectionPage(', 'Intent runtimePage()',
                    'com.deepseekharness.app.ui.MainActivity.homeIntent(this)',
                    'new android.content.Intent(this,com.deepseekharness.app.ui.NativeDataActivity.class)',
                    'new android.content.Intent(this,com.deepseekharness.app.ui.ExtractActivity.class)',
                    'putExtra("review_only",true)'],
    # 共用的通知点击入口：显式目标 Activity、open_launch、复用已有任务而不新建实例。
    'ui/BrowserTasks.java': ['static Intent homeIntent(Context context)',
                             'putExtra("open_launch", true)',
                             'Intent.FLAG_ACTIVITY_REORDER_TO_FRONT',
                             'Intent.FLAG_ACTIVITY_SINGLE_TOP'],
    'ui/MainActivity.java': ['public static Intent homeIntent(Context ctx)',
                             'addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)'],
}
for name, needles in checks.items():
    text = (root / name).read_text(encoding='utf-8')
    compact = re.sub(r'\s+', '', text)
    for needle in needles:
        require(re.sub(r'\s+', '', needle) in compact, f'{name}: missing {needle}')
print('PASS: DSHA notification bodies have app click entry points; action buttons remain separate.')
