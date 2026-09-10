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
            report.setValue("上次环境维护未完成，请先到安装与修复页恢复中断维护。"); return;
        }
        com.deepseekharness.app.util.EnvironmentTaskGate.Lease lease =
                com.deepseekharness.app.util.EnvironmentTaskGate.tryAcquire(repair ? "诊断修复" : "环境诊断");
        if (lease == null) {
            report.setValue("正在" + com.deepseekharness.app.util.EnvironmentTaskGate.activeKind() + "，完成后可重新生成报告。"); return;
        }
        busy.setValue(true);
        report.setValue(repair ? "正在准备证书与网络工具修复…\n" : "正在读取设备与环境信息…\n");
        try { IO.execute(() -> {
            try (lease) { lease.run(() -> {
            String repairResult = "";
            if (repair) {
                try {
                    ProotBootstrap proot = HarnessController.get(getApplication()).proot();
                    if (!proot.isEnvironmentReady()) throw new java.io.IOException("环境未就绪，请先完成首次解压");
                    proot.prepareRuntimeTools();
                    proot.ensureRuntimeFiles();
                    if (!proot.ensureGlibcPython() || !proot.ensureBundledPnpm()) throw new java.io.IOException("内置 Python / pnpm 修复失败");
                    String output = proot.execAndReadWithProot("python3 -c 'import ssl; ssl.create_default_context()' && npm --version && printf '\\nDSHA_NETWORK_REPAIR_OK\\n'", 30000);
                    if (!output.contains("DSHA_NETWORK_REPAIR_OK")) throw new java.io.IOException(output);
                    repairResult = "证书、Python、npm 与 pnpm 已修复并通过启动检查。\n";
                } catch (Exception e) { repairResult = "修复失败：" + SensitiveData.redact(String.valueOf(e.getMessage())) + "\n"; }
                DiagnosticLog.record(getApplication(), "REPAIR_NETWORK_TOOLS", repairResult);
            }
            String result;
            try { result = repairResult + collect(); }
            catch (Exception e) { result = "诊断未完成：" + SensitiveData.redact(String.valueOf(e.getMessage())); }
            report.postValue(SensitiveData.redact(result)); return null;
            }); } catch (Exception | LinkageError error) {
                report.postValue("诊断未完成：" + SensitiveData.redact(String.valueOf(error)));
            } finally { busy.postValue(false); }
        }); } catch (RuntimeException error) {
            lease.close(); busy.setValue(false);
            report.setValue("无法开始诊断：" + SensitiveData.redact(String.valueOf(error)));
        }
    }
    private String collect() {
        StringBuilder out = new StringBuilder("DSHA 诊断报告\n");
        ConfigStore config = new ConfigStore(getApplication());
        out.append("连续 Web 失败：").append(config.getWebFailures()).append("/3\n")
                .append("最近失败阶段：").append(config.getWebFailureStage()).append('\n')
                .append("最近失败原因：").append(config.getWebFailureReason()).append('\n');
        out.append("版本：").append(BuildConfig.VERSION_NAME).append(" / ").append(BuildConfig.VERSION_CODE)
                .append(BuildConfig.LOW_ANDROID ? " / 兼容版\n" : " / 标准版\n");
        out.append("系统：Android ").append(Build.VERSION.RELEASE).append(" / API ").append(Build.VERSION.SDK_INT).append('\n');
        out.append("机型：").append(Build.MANUFACTURER).append(' ').append(Build.MODEL).append('\n');
        out.append("架构：").append(String.join(", ", Build.SUPPORTED_ABIS)).append('\n');
        out.append("内核：").append(System.getProperty("os.version", "未知")).append('\n');
        try { out.append("内存页：").append(android.system.Os.sysconf(android.system.OsConstants._SC_PAGESIZE)).append(" bytes\n"); }
        catch (Exception ignored) { }
        out.append("可用存储：").append(getApplication().getFilesDir().getUsableSpace() / 1048576).append(" MiB\n");
        try {
            android.content.pm.PackageInfo web = Build.VERSION.SDK_INT >= 26 ? android.webkit.WebView.getCurrentWebViewPackage() : null;
            out.append("WebView：").append(web == null ? "系统未提供版本信息" : web.packageName + " " + web.versionName).append('\n');
        } catch (Exception | LinkageError error) { out.append("WebView：不可用（").append(error.getClass().getSimpleName()).append("）\n"); }
        out.append("兼容内核：").append(BuildConfig.LOW_ANDROID ? "Gecko 143 可用（旧系统自动切换）" : "未内置").append('\n');
        ProotBootstrap proot = HarnessController.get(getApplication()).proot();
        out.append("\n环境检查\n");
        out.append("离线环境：").append(proot.isEnvironmentReady() ? "已就绪" : "未就绪，请完成首次解压").append('\n');
        File root = proot.getRootfsDir();
        String[][] probes = {{"Node", "usr/local/bin/node"}, {"npm", "usr/local/lib/node_modules/npm/bin/npm-cli.js"},
                {"npm 入口", "root/dsh-bin/npm"}, {"CA 证书", "usr/local/share/dsha/ca-certificates.crt"},
                {"插件管理器", "root/.dsh/plugin-manager.py"}};
        for (String[] probe : probes) out.append(probe[0]).append("：").append(new File(root, probe[1]).isFile() ? "存在" : "缺失，可尝试修复证书与 npm").append('\n');
        report.postValue(SensitiveData.redact(out.toString()) + "\n正在验证 Node / npm / Python 实际运行，最多 20 秒…\n");
        if (proot.isEnvironmentReady()) {
            String probe = proot.execAndReadWithProot("printf 'Node: '; node --version; printf 'npm: '; npm --version; printf 'Python: '; python3 --version", 20000);
            if (probe.length() > 1500) probe = probe.substring(0, 1500);
            out.append(SensitiveData.redact(probe)).append('\n');
        }
        out.append("\n最近操作与失败步骤\n").append(DiagnosticLog.read(getApplication()));
        out.append("\n建议操作\n证书或 npm 异常：点击「修复证书与 npm」。\n文件选择无返回：到插件页使用「其他文件选择器」。\n第三方插件导致启动失败：使用启动页的安全启动，再逐个恢复插件。\n存储不足：清理下载目录后重试，避免重新解压整个环境。\n");
        out.append("\n隐私范围：未读取 API 配置、对话、终端命令或系统完整日志；没有自动上传此报告。可在下面补充复现步骤后复制或导出。\n");
        return out.toString();
    }
}
