package com.deepseekharness.app.runtime;

import android.content.Context;
import com.deepseekharness.app.util.Compat;
import com.deepseekharness.app.util.InstallProbe;
import com.deepseekharness.app.util.InstallProcess;
import com.deepseekharness.app.util.InstallTask;
import com.deepseekharness.app.util.InstallStage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;

/** 第 2 步只修复探测失败的工具；任务单飞由 InstallRepository 统一管理。 */
public final class BasicToolsInstaller {
  private final Context context;
  private final ProotBootstrap proot;

  public BasicToolsInstaller(Context context, ProotBootstrap proot) {
    this.context = context.getApplicationContext();
    this.proot = proot;
  }

  public void repair(InstallProbe.Results checked, InstallTask task) throws Exception {
    if (checked.ok(InstallStage.TOOLS.id())) return;
    task.stage(
        InstallStage.TOOLS.id(),
        com.deepseekharness.app.util.UiText.text("修复第 2 步：准备随包证书与命令入口"),
        false);
    RuntimeTools.prepare(context, proot.getRootfsDir());
    task.append(com.deepseekharness.app.util.UiText.text("随包证书与命令入口已核对"));
    task.checkCancelled();
    if (!checked.ok("python")) {
      task.stage(
          InstallStage.TOOLS.id(),
          com.deepseekharness.app.util.UiText.text("修复第 2 步：补齐离线 Python"),
          false);
      if (!proot.ensureBundledPython())
        throw new IOException(
            com.deepseekharness.app.util.UiText.text("离线 Python 修复失败；请检查可用空间与安装包完整性"));
      task.append(com.deepseekharness.app.util.UiText.text("离线 Python 文件已补齐，稍后验证实际运行结果"));
    }
    task.checkCancelled();
    if (checked.ok("curl") && checked.ok("git")) return;
    byte[] script = GuestScripts.read(context.getAssets().open("install-basic-tools.sh"));
    task.stage(
        InstallStage.TOOLS.id(),
        com.deepseekharness.app.util.UiText.text("修复第 2 步：联网补齐 curl / git（取消将在本轮软件包操作后生效）"),
        false);
    var work = com.deepseekharness.app.util.RuntimeWorkPort.begin("基础工具安装");
    Process process = null;
    try {
      process = proot.execRootfsForInstallWithPipedInput(GuestScripts.INSTALL);
      GuestScripts.send(process, script);
      // apt/dpkg 写入期间不响应取消；结束后再到安全点。
      int code =
          InstallProcess.read(
              process,
              900_000,
              false,
              task::cancellationRequested,
              line -> {
                if (!"DSHA_TOOLS_OK".equals(line)) task.append(line);
              },
              Compat::destroy);
      if (code != 0)
        throw new IOException(
            com.deepseekharness.app.util.UiText.format("基础工具安装失败（退出码 %s），请查看上方软件源/网络输出", code));
    } finally {
      if (process != null && !com.deepseekharness.app.util.ProcessTermination.exited(process)) {
        Compat.destroy(process);
        if (!com.deepseekharness.app.util.ProcessTermination.exited(process)) {
          work.retainUntilExit(process);
          throw new InstallProcess.CleanupFailure(
              process, Compat::destroy, new IOException("TOOLS_PROCESS_EXIT_UNCONFIRMED"));
        }
      }
      work.close();
    }
  }
}
