#!/usr/bin/env python3
"""Static guard: every DSHA notification body has a real app entry point."""
from pathlib import Path
import re
from verification_require import require

root = Path(__file__).resolve().parents[1] / 'app/src/main/java/com/deepseekharness/app'
checks = {
    'HarnessService.java': ['setContentIntent(pi)', 'FLAG_ACTIVITY_CLEAR_TOP', 'putExtra("open_launch", true)'],
    'HttpShellService.java': ['setContentIntent(agentNotificationIntent())'],
    'DeviceBridgeService.java': ['setContentIntent(dshaNotificationIntent(false))', 'FLAG_ACTIVITY_CLEAR_TOP'],
    'UpdateDownloadService.java': ['setContentIntent(open)', 'FLAG_ACTIVITY_CLEAR_TOP'],
    'backup/DataProtectionService.java': ['setContentIntent(open)', 'if(open==null)',
                                         'MaintenanceUiPorts.fromOwner(getApplicationContext())',
                                         '.dataProtectionPage(nativeJob.busy,maintenance.id)',
                                         '.runtimePage()'],
    'DshaApp.java': ['Intent dataProtectionPage(', 'Intent runtimePage()',
                    'new android.content.Intent(this,com.deepseekharness.app.ui.MainActivity.class)',
                    'new android.content.Intent(this,com.deepseekharness.app.ui.NativeDataActivity.class)',
                    'new android.content.Intent(this,com.deepseekharness.app.ui.ExtractActivity.class)',
                    'putExtra("review_only",true)', 'putExtra("open_launch",true)'],
}
for name, needles in checks.items():
    text = (root / name).read_text(encoding='utf-8')
    compact = re.sub(r'\s+', '', text)
    for needle in needles:
        require(re.sub(r'\s+', '', needle) in compact, f'{name}: missing {needle}')
print('PASS: DSHA notification bodies have app click entry points; action buttons remain separate.')
