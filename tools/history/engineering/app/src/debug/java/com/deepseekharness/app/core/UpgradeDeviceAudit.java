package com.deepseekharness.app.core;

import android.app.Activity;
import android.app.Application;
import android.app.Instrumentation;
import android.os.Bundle;
import com.deepseekharness.app.BackupManager;
import com.deepseekharness.app.util.Compat;
import com.deepseekharness.app.util.EnvironmentTaskGate;
import com.deepseekharness.app.util.SensitiveData;
import com.deepseekharness.app.util.ShellQuote;
import java.io.File;
import java.io.InputStream;
import java.util.UUID;

/** debug 真机验收：重建走生产事务；其余仅操作本次随机测试目录和测试插件。 */
public final class UpgradeDeviceAudit extends Instrumentation {
    private Bundle args;
    private int checks;
    private void check(boolean ok, String reason) { checks++; if (!ok) throw new AssertionError(reason); }
    private void note(String text) { Bundle b = new Bundle(); b.putString("stream", SensitiveData.redact(text) + "\n"); sendStatus(0, b); }
    @Override public void onCreate(Bundle arguments) { args = arguments; super.onCreate(arguments); start(); }
    private void complete(PluginRepository repository) throws Exception {
        long end = System.currentTimeMillis() + 60_000;
        while (repository.isBusy() && System.currentTimeMillis() < end) Thread.sleep(100);
        check(!repository.isBusy(), "原生插件操作超时"); waitForIdleSync();
        note(repository.state().getValue().message);
    }
    private PluginRepository.Item item(PluginRepository repository, String name) {
        return repository.state().getValue().items.stream().filter(i -> i.name.equals(name)).findFirst().orElseThrow();
    }
    @Override public void onStart() {
        Bundle result = new Bundle(); File owned = null, profile = null;
        try {
            HarnessController controller = HarnessController.get(getTargetContext());
            check(!controller.isWebRunning() && !controller.isStarting() && !RuntimeTasks.isBusy()
                    && !EnvironmentTaskGate.isBusy(), "请先结束实际 Web 和数据任务");
            if ("cleanup-alpha2".equals(args.getString("mode"))) {
                File root = controller.proot().getRootfsDir();
                for (File target : new File[]{
                        new File(getTargetContext().getCacheDir(), "runtime-startup/cold-alpha2-20260910"),
                        new File(root, "root/.dsha-alpha2-tests"), new File(root, "root/.dsha-alpha2-snapshot.py"),
                        new File(root, "root/闲聊/DSHA-alpha2-preview-check")}) {
                    File allowed = target.getPath().startsWith(getTargetContext().getCacheDir().getPath())
                            ? getTargetContext().getCacheDir() : root;
                    check(!Compat.isSymbolicLink(target) && target.getCanonicalPath().startsWith(allowed.getCanonicalPath() + File.separator),
                            "测试目录超出隔离边界，停止清理");
                    if (target.exists()) EnvironmentMaintenance.deleteTree(target);
                }
                note("本次冷环境、预览文件与测试源码已清理");
            } else if ("automatic-update".equals(args.getString("mode"))) {
                check(!controller.isEnvironmentReady(), "需要旧版环境，不能把重复更新当作覆盖升级");
                check(controller.proot().canUpdateManagedRuntime(), "旧版基础环境不支持局部更新");
                long began = android.os.SystemClock.elapsedRealtime();
                try (android.os.ParcelFileDescriptor fd = getUiAutomation().executeShellCommand(
                        "am start -W -n com.dsh.client/com.deepseekharness.app.ui.MainActivity");
                     InputStream input = new android.os.ParcelFileDescriptor.AutoCloseInputStream(fd)) {
                    while (input.read() != -1) { }
                }
                BackupTask task = BackupTask.get(getTargetContext());
                String previous = "";
                while (android.os.SystemClock.elapsedRealtime() - began < 180000) {
                    String detail = task.snapshot().detail;
                    if (!detail.equals(previous)) { note(detail); previous = detail; }
                    if (controller.isEnvironmentReady() && !task.busy()) break;
                    if (task.snapshot().status == com.deepseekharness.app.util.BackupTaskState.Status.FAILED)
                        throw new AssertionError(detail);
                    Thread.sleep(100);
                }
                check(controller.isEnvironmentReady() && !task.busy(), "自动覆盖升级未完成");
                check(task.snapshot().status == com.deepseekharness.app.util.BackupTaskState.Status.SUCCEEDED,
                        "自动更新任务未成功");
                check(!task.pendingMaintenance(), "升级后仍有未完成事务");
                note("MainActivity → ExtractActivity 自动局部更新通过");
                result.putLong("automatic_update_ms", android.os.SystemClock.elapsedRealtime() - began);
                result.putBoolean("api_key_present", !controller.config().getApiKey().isEmpty());
            } else if ("update".equals(args.getString("mode"))) {
                long began = android.os.SystemClock.elapsedRealtime();
                check(controller.proot().canUpdateManagedRuntime(), "当前基础环境不适用于局部更新");
                note(BackupManager.runDataTask(controller, () -> EnvironmentMaintenance.update(controller, this::note)));
                check(controller.isEnvironmentReady(), "更新后的环境未就绪");
                result.putLong("update_ms", android.os.SystemClock.elapsedRealtime() - began);
            } else if ("rebuild".equals(args.getString("mode"))) {
                note(BackupManager.runDataTask(controller, () -> EnvironmentMaintenance.rebuild(controller, this::note)));
                check(controller.isEnvironmentReady(), "重建后的环境未就绪");
            } else {
                check(controller.isEnvironmentReady(), "需要就绪的 Ubuntu 环境");
                String token = UUID.randomUUID().toString();
                String guest = "/root/.dsha-upgrade-audit-" + token;
                owned = new File(controller.proot().getRootfsDir(), guest.substring(1));
                check(owned.mkdir(), "创建独立检查目录");
                if ("backup".equals(args.getString("mode"))) {
                    File test = new File(getTargetContext().getCacheDir(), "upgrade-device-audit/test-backup-engine.py");
                    check(test.isFile(), "未提供已审查的备份回归脚本");
                    Compat.copy(test, new File(owned, "test.py"), true);
                    for (String name : new String[]{"backup-engine.py", "backup-plugin-graph.py", "register-builtin-plugins.py"}) {
                        try (InputStream input = getTargetContext().getAssets().open(name)) {
                            java.io.ByteArrayOutputStream bytes = new java.io.ByteArrayOutputStream();
                            byte[] buffer = new byte[8192]; int count;
                            while ((count = input.read(buffer)) != -1) bytes.write(buffer, 0, count);
                            Compat.write(new File(owned, name), bytes.toByteArray());
                        }
                    }
                    final String command = "python3 -B " + guest + "/test.py " + guest + "/backup-engine.py"
                            + " BackupTest.test_all_scopes_roundtrip_and_isolation"
                            + " BackupTest.test_legacy_mobile_host_peer_does_not_block_upgrade_backup"
                            + " BackupTest.test_missing_dependencies_preserve_source_and_report_in_backup"
                            + " BackupTest.test_file_upload_missing_mammoth_preserves_conversations"
                            + " BackupTest.test_missing_workspace_directories_are_created"
                            + " BackupTest.test_local_plugin_code_and_runtime_dependency_roundtrip";
                    note(BackupManager.runSnapshotTask(controller, () -> controller.proot().execChecked(command)));
                } else if ("plugins".equals(args.getString("mode"))) {
                    String name = "dsha-audit-" + token;
                    File source = new File(owned, "source"); check(source.mkdir(), "创建独立 npm 插件源码");
                    Compat.write(new File(source, "package.json"), "{\"name\":\"" + name
                            + "\",\"version\":\"1.0.0\",\"type\":\"module\",\"main\":\"index.js\",\"dsh\":{\"bundle\":{\"patch\":\"cordis.patch.yml\"}}}");
                    Compat.write(new File(source, "index.js"), "export function apply(ctx) {}\n");
                    Compat.write(new File(source, "cordis.patch.yml"), "- id: " + name + "\n  name: " + name + "\n");
                    String destination = "/root/.dsh/profiles/alpha15-" + token;
                    profile = new File(controller.proot().getRootfsDir(), destination.substring(1));
                    try (EnvironmentTaskGate.Lease lease = EnvironmentTaskGate.tryAcquire("隔离 npm 安装验收")) {
                        check(lease != null, "无法获取检查锁");
                        note(lease.run(() -> controller.proot().execChecked("npm install --offline --ignore-scripts --install-links --no-audit --no-fund --cache "
                                + ShellQuote.arg(guest + "/npm-cache") + " --prefix " + ShellQuote.arg(destination) + " " + ShellQuote.arg(guest + "/source"))));
                    }
                    PluginRepository repository = new PluginRepository((Application) getTargetContext().getApplicationContext());
                    runOnMainSync(repository::refresh); complete(repository);
                    check(repository.state().getValue().items.stream().filter(i -> i.builtin).count() == 4, "原生列表必须显示四项内置插件");
                    PluginRepository.Item detected = item(repository, name);
                    check(detected.detected && detected.available && !detected.enabled, "npm 安装未被原生管理检测");
                    for (boolean enable : new boolean[]{true, false, true}) {
                        PluginRepository.Item current = item(repository, name);
                        runOnMainSync(() -> repository.setEnabled(current, enable)); complete(repository);
                        check(item(repository, name).enabled == enable, "插件开关未生效");
                    }
                    PluginRepository.Item installed = item(repository, name);
                    check(installed.deletable, "原生登记的测试插件不可移除");
                    runOnMainSync(() -> repository.delete(installed)); complete(repository);
                    check(new File(profile, "node_modules/" + name + "/package.json").isFile(), "删除导入副本不应移除 npm 源码");
                    check(!item(repository, name).enabled, "测试插件未从 Web 停用");
                    note("原生检测、四项内置插件、启停再启用及移除均通过");
                } else throw new IllegalArgumentException("未知检查模式");
            }
            result.putString("result", "PASS"); result.putInt("checks", checks);
        } catch (Throwable error) { result.putString("failure", SensitiveData.redact(android.util.Log.getStackTraceString(error))); }
        finally {
            try {
                if (profile != null && profile.exists()) EnvironmentMaintenance.deleteTree(profile);
                if (owned != null && owned.exists()) EnvironmentMaintenance.deleteTree(owned);
            } catch (Exception error) { result.putString("cleanup", error.toString()); }
            finish(result.containsKey("failure") ? Activity.RESULT_CANCELED : Activity.RESULT_OK, result);
        }
    }
}
