package com.deepseekharness.app.vscreen;

import android.content.Context;
import com.deepseekharness.app.util.ShellQuote;

/**
 * 实体屏特权 Core 的受管启动命令（T130 Phase A）。
 *
 * <p>只生成当前安装包的 {@code app_process} 启动器，参数由 {@code DeviceShellPolicy} 逐项核验；
 * 不接受任意 shell、路径或 displayId。与虚拟屏启动器共用同一 Core 类，用 {@code --physical} 区分模式。
 */
final class PhysicalScreenLauncher {
  private static String prefix(Context app) {
    return "/system/bin/app_process "
        + ShellQuote.arg("-Djava.class.path=" + app.getApplicationInfo().sourceDir)
        + " /system/bin com.deepseekharness.app.vscreen.VirtualScreenCore ";
  }

  String launchCommand(Context app) {
    return prefix(app)
        + "--launch --physical --port 0 --package "
        + ShellQuote.arg(app.getPackageName());
  }
}
