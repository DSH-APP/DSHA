package com.deepseekharness.app.core;

import android.app.Application;
import android.os.Build;
import androidx.annotation.NonNull;
import androidx.lifecycle.AndroidViewModel;
import androidx.lifecycle.MutableLiveData;
import com.deepseekharness.app.BuildConfig;
import com.deepseekharness.app.runtime.ProotBootstrap;
import com.deepseekharness.app.util.SensitiveData;
import java.io.File;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** 诊断使用有限的环境探针与结构化操作记录，不读取对话、API 配置或整段 logcat。 */
public final class DiagnosticRepository extends AndroidViewModel {
    private static final ExecutorService IO = Executors.newSingleThreadExecutor();
    public final MutableLiveData<String> report = new MutableLiveData<>("");
    public final MutableLiveData<Boolean> busy = new MutableLiveData<>(false);
    public DiagnosticRepository(@NonNull Application app) { super(app); }
    public void generate() { run(false); }
    public void repairNetworkTools() { run(true); }
    private void run(boolean repair) {
        if (Boolean.TRUE.equals(busy.getValue())) return;
        HarnessController controller = HarnessController.get(getApplication());
        if (com.deepseekharness.app.BackupManager.hasPendingMaintenance(controller)) {
            report.setValue(com.deepseekharness.app.util.UiText.text("上次环境维护未完成，请先到安装与修复页恢复中断维护。")); return;
        }
        com.deepseekharness.app.util.EnvironmentTaskGate.Lease lease =
                com.deepseekharness.app.util.EnvironmentTaskGate.tryAcquire(repair ? com.deepseekharness.app.util.UiText.text("诊断修复") : com.deepseekharness.app.util.UiText.text("环境诊断"));
        if (lease == null) {
            report.setValue(com.deepseekharness.app.util.UiText.text("正在") + com.deepseekharness.app.util.EnvironmentTaskGate.activeKind() + com.deepseekharness.app.util.UiText.text("，完成后可重新生成报告。")); return;
        }
        busy.setValue(true);
        report.setValue(repair ? com.deepseekharness.app.util.UiText.text("正在准备证书与网络工具修复…\n") : com.deepseekharness.app.util.UiText.text("正在读取设备与环境信息…\n"));
        try { IO.execute(() -> {
            try (lease) { lease.run(() -> {
            String repairResult = "";
            if (repair) {
                try {
                    ProotBootstrap proot = HarnessController.get(getApplication()).proot();
                    if (!proot.isEnvironmentReady()) throw new java.io.IOException(com.deepseekharness.app.util.UiText.text("环境未就绪，请先完成首次解压"));
                    proot.prepareRuntimeTools();
                    proot.ensureRuntimeFiles();
                    if (!proot.ensureGlibcPython() || !proot.ensureBundledPnpm()) throw new java.io.IOException(com.deepseekharness.app.util.UiText.text("内置 Python / pnpm 修复失败"));
                    String output = proot.execAndReadWithProot("python3 -c 'import ssl; ssl.create_default_context()' && npm --version && printf '\\nDSHA_NETWORK_REPAIR_OK\\n'", 30000);
                    if (!output.contains("DSHA_NETWORK_REPAIR_OK")) throw new java.io.IOException(output);
                    repairResult = com.deepseekharness.app.util.UiText.text("证书、Python、npm 与 pnpm 已修复并通过启动检查。\n");
                } catch (Exception e) { repairResult = com.deepseekharness.app.util.UiText.text("修复失败：") + SensitiveData.redact(String.valueOf(e.getMessage())) + "\n"; }
                DiagnosticLog.record(getApplication(), "REPAIR_NETWORK_TOOLS", repairResult);
            }
            String result;
            try { result = repairResult + collect(); }
            catch (Exception e) { result = com.deepseekharness.app.util.UiText.text("诊断未完成：") + SensitiveData.redact(String.valueOf(e.getMessage())); }
            report.postValue(SensitiveData.redact(result)); return null;
            }); } catch (Exception | LinkageError error) {
                report.postValue(com.deepseekharness.app.util.UiText.text("诊断未完成：") + SensitiveData.redact(String.valueOf(error)));
            } finally { busy.postValue(false); }
        }); } catch (RuntimeException error) {
            lease.close(); busy.setValue(false);
            report.setValue(com.deepseekharness.app.util.UiText.text("无法开始诊断：") + SensitiveData.redact(String.valueOf(error)));
        }
    }
    private String collect() {
        StringBuilder out = new StringBuilder(com.deepseekharness.app.util.UiText.text("DSHA 诊断报告\n"));
        ConfigStore config = new ConfigStore(getApplication());
        out.append(com.deepseekharness.app.util.UiText.text("连续 Web 失败：")).append(config.getWebFailures()).append("/3\n")
                .append(com.deepseekharness.app.util.UiText.text("最近失败阶段：")).append(config.getWebFailureStage()).append('\n')
                .append(com.deepseekharness.app.util.UiText.text("最近失败原因：")).append(config.getWebFailureReason()).append('\n');
        out.append(com.deepseekharness.app.util.UiText.text("版本：")).append(BuildConfig.VERSION_NAME).append(" / ").append(BuildConfig.VERSION_CODE)
                .append(BuildConfig.LOW_ANDROID ? com.deepseekharness.app.util.UiText.text(" / 兼容版\n") : com.deepseekharness.app.util.UiText.text(" / 标准版\n"));
        out.append(com.deepseekharness.app.util.UiText.text("系统：Android ")).append(Build.VERSION.RELEASE).append(" / API ").append(Build.VERSION.SDK_INT).append('\n');
        out.append(com.deepseekharness.app.util.UiText.text("机型：")).append(Build.MANUFACTURER).append(' ').append(Build.MODEL).append('\n');
        out.append(com.deepseekharness.app.util.UiText.text("架构：")).append(String.join(", ", Build.SUPPORTED_ABIS)).append('\n');
        out.append(com.deepseekharness.app.util.UiText.text("内核：")).append(System.getProperty("os.version", com.deepseekharness.app.util.UiText.text("未知"))).append('\n');
        try { out.append(com.deepseekharness.app.util.UiText.text("内存页：")).append(android.system.Os.sysconf(android.system.OsConstants._SC_PAGESIZE)).append(" bytes\n"); }
        catch (Exception ignored) { }
        out.append(com.deepseekharness.app.util.UiText.text("可用存储：")).append(getApplication().getFilesDir().getUsableSpace() / 1048576).append(" MiB\n");
        try {
            android.content.pm.PackageInfo web = Build.VERSION.SDK_INT >= 26 ? android.webkit.WebView.getCurrentWebViewPackage() : null;
            out.append("WebView：").append(web == null ? com.deepseekharness.app.util.UiText.text("系统未提供版本信息") : web.packageName + " " + web.versionName).append('\n');
        } catch (Exception | LinkageError error) { out.append(com.deepseekharness.app.util.UiText.text("WebView：不可用（")).append(error.getClass().getSimpleName()).append("）\n"); }
        out.append(com.deepseekharness.app.util.UiText.text("兼容内核：")).append(BuildConfig.LOW_ANDROID ? com.deepseekharness.app.util.UiText.text("Gecko 143 可用（旧系统自动切换）") : com.deepseekharness.app.util.UiText.text("未内置")).append('\n');
        ProotBootstrap proot = HarnessController.get(getApplication()).proot();
        out.append(com.deepseekharness.app.util.UiText.text("\n环境检查\n"));
        out.append(com.deepseekharness.app.util.UiText.text("离线环境：")).append(proot.isEnvironmentReady() ? com.deepseekharness.app.util.UiText.text("已就绪") : com.deepseekharness.app.util.UiText.text("未就绪，请完成首次解压")).append('\n');
        File root = proot.getRootfsDir();
        String[][] probes = {{"Node", "usr/local/bin/node"}, {"npm", "usr/local/lib/node_modules/npm/bin/npm-cli.js"},
                {com.deepseekharness.app.util.UiText.text("npm 入口"), "root/dsh-bin/npm"}, {com.deepseekharness.app.util.UiText.text("CA 证书"), "usr/local/share/dsha/ca-certificates.crt"},
                {com.deepseekharness.app.util.UiText.text("插件管理器"), "root/.dsh/plugin-manager.py"}};
        for (String[] probe : probes) out.append(probe[0]).append(com.deepseekharness.app.util.UiText.text("：")).append(new File(root, probe[1]).isFile() ? com.deepseekharness.app.util.UiText.text("存在") : com.deepseekharness.app.util.UiText.text("缺失，可尝试修复证书与 npm")).append('\n');
        report.postValue(SensitiveData.redact(out.toString()) + com.deepseekharness.app.util.UiText.text("\n正在验证 Node / npm / Python 实际运行，最多 20 秒…\n"));
        if (proot.isEnvironmentReady()) {
            String probe = proot.execAndReadWithProot("printf 'Node: '; node --version; printf 'npm: '; npm --version; printf 'Python: '; python3 --version", 20000);
            if (probe.length() > 1500) probe = probe.substring(0, 1500);
            out.append(SensitiveData.redact(probe)).append('\n');
        }
        out.append(com.deepseekharness.app.util.UiText.text("\n最近操作与失败步骤\n")).append(DiagnosticLog.read(getApplication()));
        out.append(com.deepseekharness.app.util.UiText.text("\n建议操作\n证书或 npm 异常：点击「修复证书与 npm」。\n文件选择无返回：到插件页使用「其他文件选择器」。\n第三方插件导致启动失败：使用启动页的安全启动，再逐个恢复插件。\n存储不足：清理下载目录后重试，避免重新解压整个环境。\n"));
        out.append(com.deepseekharness.app.util.UiText.text("\n隐私范围：未读取 API 配置、对话、终端命令或系统完整日志；没有自动上传此报告。可在下面补充复现步骤后复制或导出。\n"));
        return out.toString();
    }
}
