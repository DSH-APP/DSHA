package com.deepseekharness.app.runtime;

import android.content.Context;
import com.deepseekharness.app.util.Compat;
import com.deepseekharness.app.util.InstallProbe;
import com.deepseekharness.app.util.InstallProcess;
import com.deepseekharness.app.util.InstallTask;
import java.io.File;
import java.io.IOException;
import java.util.List;
import java.util.function.Consumer;

/** 安装六步编排：先一次探测，仅在明确请求修复且检查失败时写入，完成后复验。 */
public final class InstallPipeline {
    /** 操作边界可替换，独立测试能验证不发生多余修复、清环境或取消后的写入。 */
    interface Environment {
        boolean ready();
        boolean canExtractFresh();
        void extract(Consumer<Long> progress) throws Exception;
        void repairTools(InstallProbe.Results checked, InstallTask task) throws Exception;
        boolean repairPnpm();
        void repairGroups();
        int execute(String script, long timeout, boolean cancellable, InstallTask task, Consumer<String> output) throws Exception;
    }
    private final Environment environment;
    public InstallPipeline(Context context) { this(new LocalEnvironment(context)); }
    InstallPipeline(Environment environment) { this.environment = environment; }
    public void run(InstallTask task, boolean repair, int selected) throws Exception {
        task.stage(1, "检查第 1 步：Linux 解压标记、版本与 bash", true);
        boolean ready = environment.ready();
        if (!ready && repair && (selected == 0 || selected == 1)) {
            // 版本迁移会删除旧目录；只准首次空环境进入，绝不重置已有数据。
            if (environment.canExtractFresh()) {
                task.stage(1, "安装第 1 步：首次解压离线环境（当前解压完成后可停止）", false);
                long[] last = {0};
                environment.extract(done -> {
                    if (done - last[0] >= 8 * 1024 * 1024) {
                        task.append("已读取离线包 " + done / (1024 * 1024) + " MiB"); last[0] = done;
                    }
                });
                ready = environment.ready();
            }
        }
        task.result(1, ready, ready ? "标记、版本和 bash 在位" : "环境未就绪；保留现有文件，不自动重解压或清除，请先备份后恢复环境");
        task.checkCancelled();
        if (!ready) {
            for (int step = 2; step <= 6; step++) if (selected == 0 || selected == step) task.skip(step, "Linux 环境未就绪");
            task.finish(InstallTask.Outcome.FAILED, "第 1 步未通过，无法继续检查；现有环境已保留"); return;
        }
        if (selected == 1) { task.finish(InstallTask.Outcome.SUCCEEDED, "第 1 步检查通过"); return; }
        InstallProbe.Results checked = probe(task, selected);
        boolean failed = false; int repaired = 0;
        for (int step = 2; step <= 6; step++) {
            if (selected != 0 && selected != step) continue;
            task.checkCancelled();
            if (checked.ok(step)) continue;
            if (!repair) { failed = true; continue; }
            try {
                switch (step) {
                    case 2: environment.repairTools(checked, task); break;
                    case 4:
                        task.stage(4, "修复第 4 步：补齐离线 pnpm", false);
                        if (!environment.repairPnpm()) throw new IOException("离线 pnpm 修复失败，请检查可用空间");
                        task.append("离线 pnpm 文件已核对，开始复验"); break;
                    case 6:
                        if (!checked.ok("dns") || !checked.ok("session") || !checked.ok("settings")) {
                            task.stage(6, "修复第 6 步：DNS、会话写入与局域网设置补丁", false);
                            int code = environment.execute(InstallProbe.patchScript(), 60_000, false, task, task::append);
                            if (code != 0) throw new IOException("运行补丁失败（退出码 " + code + "），已完成的部分会保留");
                        }
                        task.checkCancelled();
                        if (!checked.ok("groups")) {
                            task.stage(6, "修复第 6 步：补齐 Android 用户组", false);
                            environment.repairGroups(); task.append("Android 用户组已核对，开始复验");
                        }
                        break;
                    default:
                        task.result(step, false, "内置组件无法运行；本页不覆盖用户升级的 Node/dsh，请备份后修复该组件");
                        failed = true; continue;
                }
                task.checkCancelled();
                InstallProbe.Results verified = probe(task, step);
                if (!verified.ok(step)) failed = true; else repaired++;
            } catch (InstallTask.Cancelled cancelled) { throw cancelled; }
            catch (Exception error) {
                if (InstallProcess.cleanupFailure(error) != null) throw error;
                if (isCancellation(error)) throw new InstallTask.Cancelled();
                task.result(step, false, String.valueOf(error)); failed = true;
            }
        }
        task.checkCancelled();
        task.finish(failed ? InstallTask.Outcome.FAILED : InstallTask.Outcome.SUCCEEDED,
                failed ? "检查完成，仍有步骤未通过；失败项与详细输出已保留"
                        : repaired > 0 ? "已按需修复 " + repaired + " 步，并通过复验" : "检查全部通过，无需修复");
    }
    private InstallProbe.Results probe(InstallTask task, int selected) throws Exception {
        List<InstallProbe.Check> checks = InstallProbe.checks(selected);
        InstallProbe.Results result = new InstallProbe.Results(checks);
        task.stage(selected == 0 ? 2 : selected, "启动一次容器探测，逐项检查实际运行结果", true);
        InstallProbe.Check[] active = {null};
        int code = environment.execute(InstallProbe.script(checks), Math.max(30_000, checks.size() * 22_000L), true, task, line -> {
                    InstallProbe.Check begin = result.beginning(line);
                    if (begin != null) {
                        if (active[0] != null && active[0].step != begin.step)
                            task.result(active[0].step, result.ok(active[0].step), result.detail(active[0].step));
                        active[0] = begin; task.stage(begin.step, "检查第 " + begin.step + " 步：" + begin.label, true);
                    } else if (result.accept(line)) {
                        if (active[0] != null) task.append(active[0].label + "："
                                + (result.ok(active[0].key) ? "通过" : "失败（退出码 " + line.substring(line.lastIndexOf(':') + 1) + "）"));
                    } else task.append(line);
                });
        if (code != 0) throw new IOException("容器探测退出码 " + code + "，检查未完整执行");
        if (active[0] != null) task.result(active[0].step, result.ok(active[0].step), result.detail(active[0].step));
        return result;
    }
    public static boolean isCancellation(Throwable error) {
        for (Throwable current = error; current != null; current = current.getCause())
            if (current instanceof InstallTask.Cancelled) return true;
        return false;
    }

    private static final class LocalEnvironment implements Environment {
        private final ProotBootstrap proot;
        private final BasicToolsInstaller tools;
        LocalEnvironment(Context context) {
            proot = new ProotBootstrap(context); tools = new BasicToolsInstaller(context, proot);
        }
        @Override public boolean ready() { return proot.isEnvironmentReady(); }
        @Override public boolean canExtractFresh() {
            File root = proot.getRootfsDir(); File[] contents = root.listFiles();
            return (!root.exists() || contents != null && contents.length == 0) && proot.hasOfflineBundle();
        }
        @Override public void extract(Consumer<Long> progress) throws Exception {
            proot.extractOfflineBundle((done, total) -> progress.accept(done));
        }
        @Override public void repairTools(InstallProbe.Results checked, InstallTask task) throws Exception { tools.repair(checked, task); }
        @Override public boolean repairPnpm() { return proot.ensureBundledPnpm(); }
        @Override public void repairGroups() { proot.ensureAndroidGroups(); }
        @Override public int execute(String script, long timeout, boolean cancellable, InstallTask task, Consumer<String> output) throws Exception {
            return InstallProcess.read(proot.execRootfsForInstall(script), timeout, cancellable, task::cancellationRequested, output, Compat::destroy);
        }
    }
}
