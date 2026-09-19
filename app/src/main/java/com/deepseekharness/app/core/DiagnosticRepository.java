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
    public static final class Result {
        public final String title,status,detail;
        Result(String title,String status,String detail){this.title=title;this.status=status;this.detail=detail;}
    }
    public final MutableLiveData<java.util.List<Result>> results=new MutableLiveData<>(java.util.List.of());
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
                .append(com.deepseekharness.app.util.UiText.text("最近失败阶段：")).append(com.deepseekharness.app.util.UiStateText.render(config.getDiagnosticFailureStage())).append('\n')
                .append(com.deepseekharness.app.util.UiText.text("最近失败原因：")).append(config.getDiagnosticFailureReason()).append('\n');
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
        out.append(ColdInstallDiagnostics.read(getApplication()));
        String trialFailure=com.deepseekharness.app.runtime.RuntimeTrial.latestFailure(getApplication());
        if(!trialFailure.isEmpty())out.append(com.deepseekharness.app.util.UiText.choose("\n最近隔离运行试验失败\n","\nLatest isolated runtime trial failure\n")).append(trialFailure).append('\n');
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
        publishResults(out.toString(),proot);
        return out.toString();
    }
    private void publishResults(String report,ProotBootstrap proot){
        java.util.List<Result> rows=new java.util.ArrayList<>();var controller=HarnessController.get(getApplication());
        boolean ready=proot.isEnvironmentReady();
        rows.add(new Result(t("Ubuntu 与 Bash 加载器","Ubuntu and Bash loader"),ready?t("就绪检查通过","Readiness passed"):t("需要处理","Needs attention"),t("就绪检查会核对实际 Bash、ELF 加载器与环境身份。","Readiness checks actual Bash, its ELF loader and environment identity.")+"\n\n"+(ready?t("当前环境已就绪。","Environment ready."):t("请到安装与环境查看修复方式。","Open Installation and environment for repair options."))));
        String runtimeLines=java.util.Arrays.stream(report.split("\\n")).filter(line->line.startsWith("Node:")||line.startsWith("npm:")||line.startsWith("Python:")).collect(java.util.stream.Collectors.joining("\n"));
        rows.add(new Result("Node · npm · Python",runtimeLines.contains("Node: v")&&runtimeLines.contains("Python: Python")?t("实际命令已响应","Commands responded"):t("查看检查结果","Review results"),runtimeLines.isEmpty()?report:runtimeLines));
        String identity=t("尚未读取到运行时标识。","Runtime identity unavailable.");String identityState=t("未知","Unknown");
        try{var installed=proot.installedRuntimeDescriptor();if(installed!=null){identity=installed.json().get("dshVersion")+"\n"+installed.id();identityState=installed.latest(proot.expectedRuntimeDescriptor())?t("与包内版本一致","Matches bundled version"):t("存在版本差异","Version differs");}}catch(Exception unavailable){}
        rows.add(new Result(t("运行时版本与身份","Runtime version and identity"),identityState,identity+"\n\n"+t("完整文件检查请在安装与环境中执行。","Run the complete component check in Installation and environment.")));
        String trial=com.deepseekharness.app.runtime.RuntimeTrial.latestFailure(getApplication());
        rows.add(new Result(t("最近隔离运行试验","Latest isolated runtime trial"),trial.isEmpty()?t("没有失败记录","No failure recorded"):t("失败证据已保留","Failure evidence retained"),trial.isEmpty()?t("运行时更新通过后不会生成失败记录。","A successful runtime update does not create a failure record."):trial));
        rows.add(new Result(t("存储与数据目录","Storage and data folders"),getApplication().getFilesDir().isDirectory()?t("私有目录可用","Private directory available"):t("需要处理","Needs attention"),t("可用空间：","Available space: ")+String.format(java.util.Locale.ROOT,"%.2f GiB",getApplication().getFilesDir().getUsableSpace()/1073741824.0)+"\n"+t("不遍历对话与个人文件。","Conversations and personal files are not traversed.")));
        boolean web=!controller.getWebAuthUrl().isEmpty();rows.add(new Result(t("Web 鉴权与桥接","Web authentication and bridge"),web?t("已有本轮鉴权地址","Current startup has an auth URL"):t("DSH 未运行","DSH is not running"),t("桥接请求仍须通过鉴权与原生能力确认。","Bridge requests still require authentication and native capability confirmation.")+"\n\n"+controller.getWebAuthFailure()));
        rows.add(new Result(t("网页内核","Browser engine"),BuildConfig.LOW_ANDROID?"WebView / Gecko 143":"System WebView",report.split(t("\n环境检查\n","\nEnvironment checks\n"))[0]));
        var trace=controller.startupDiagnostics().snapshot();String issues=trace.issues.isEmpty()?t("本轮没有记录到插件加载错误；未运行的插件仍需使用时验证。","No plugin loading error recorded for this startup. Unused plugins still require runtime verification."):String.join("\n",trace.issues.values());
        rows.add(new Result(t("插件加载","Plugin loading"),trace.issues.isEmpty()?t("查看启动观察结果","Review startup observations"):t("存在加载错误","Loading errors recorded"),issues));
        rows.add(new Result(t("设备通道","Device channels"),t("按需授权","Optional access"),com.deepseekharness.app.RootShell.status(getApplication())+"\n\n"+com.deepseekharness.app.ShizukuShell.userStatus(getApplication())+"\n\nADB: "+com.deepseekharness.app.DeviceBridgeService.adbState+"\nAccessibility: "+com.deepseekharness.app.DshaAccessibilityService.enabledState(getApplication())));
        results.postValue(rows);
    }
    private static String t(String zh,String en){return com.deepseekharness.app.util.UiText.choose(zh,en);}
}
