package com.deepseekharness.app.runtime;

import android.content.Context;
import android.util.Base64;
import com.deepseekharness.app.util.Compat;
import com.deepseekharness.app.util.InstallProbe;
import com.deepseekharness.app.util.InstallProcess;
import com.deepseekharness.app.util.InstallTask;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;

/** 第 2 步只修复探测失败的工具；任务单飞由 InstallRepository 统一管理。 */
public final class BasicToolsInstaller {
    private final Context context;
    private final ProotBootstrap proot;
    public BasicToolsInstaller(Context context, ProotBootstrap proot) {
        this.context = context.getApplicationContext(); this.proot = proot;
    }
    public void repair(InstallProbe.Results checked, InstallTask task) throws Exception {
        if (checked.ok(2)) return;
        task.stage(2, com.deepseekharness.app.util.UiText.text("修复第 2 步：准备随包证书与命令入口"), false);
        RuntimeTools.prepare(context, proot.getRootfsDir());
        task.append(com.deepseekharness.app.util.UiText.text("随包证书与命令入口已核对"));
        task.checkCancelled();
        if (!checked.ok("python")) {
            task.stage(2, com.deepseekharness.app.util.UiText.text("修复第 2 步：补齐离线 Python"), false);
            if (!proot.ensureBundledPython()) throw new IOException(com.deepseekharness.app.util.UiText.text("离线 Python 修复失败；请检查可用空间与安装包完整性"));
            task.append(com.deepseekharness.app.util.UiText.text("离线 Python 文件已补齐，稍后验证实际运行结果"));
        }
        task.checkCancelled();
        if (checked.ok("curl") && checked.ok("git")) return;
        String encoded;
        try (InputStream input = context.getAssets().open("install-basic-tools.sh");
             ByteArrayOutputStream bytes = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[8192]; int count;
            while ((count = input.read(buffer)) != -1) bytes.write(buffer, 0, count);
            encoded = Base64.encodeToString(bytes.toByteArray(), Base64.NO_WRAP);
        }
        task.stage(2, com.deepseekharness.app.util.UiText.text("修复第 2 步：联网补齐 curl / git（取消将在本轮软件包操作后生效）"), false);
        Process process = proot.execRootfsForInstall("set -o pipefail; printf '%s' '" + encoded
                + "' | base64 -d | tr -d '\\r' | /bin/bash");
        // apt/dpkg 写入期间不响应取消；结束后再到安全点。
        int code = InstallProcess.read(process, 900_000, false, task::cancellationRequested,
                line -> { if (!"DSHA_TOOLS_OK".equals(line)) task.append(line); }, Compat::destroy);
        if (code != 0) throw new IOException(com.deepseekharness.app.util.UiText.text("基础工具安装失败（退出码 ") + code + com.deepseekharness.app.util.UiText.text("），请查看上方软件源/网络输出"));
    }
}
