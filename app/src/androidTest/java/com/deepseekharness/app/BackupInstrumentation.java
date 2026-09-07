package com.deepseekharness.app;

import android.app.Instrumentation;
import android.content.Context;
import android.content.ContextWrapper;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Bundle;
import com.deepseekharness.app.core.ConfigStore;
import com.deepseekharness.app.core.HarnessController;
import com.deepseekharness.app.runtime.ProotBootstrap;
import com.deepseekharness.app.util.BackupScope;
import com.deepseekharness.app.util.Compat;
import com.deepseekharness.app.util.FileIntegrity;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/** 真实容器和 MediaStore 往返；所有恢复目标、偏好和输入均为本次独立样本。 */
public final class BackupInstrumentation extends Instrumentation {
    private static final String GUEST = "/root/.dsha-opt-java-check/rootfs/root";
    private final List<Uri> exported = new ArrayList<>();
    private boolean realSnapshot;
    @Override public void onCreate(Bundle arguments) {
        super.onCreate(arguments);
        realSnapshot = "true".equals(arguments.getString("realSnapshot"));
        start();
    }
    private static void check(boolean value, String message) { if (!value) throw new AssertionError(message); }
    private static void put(File root, String relative, String value) throws Exception {
        File target = new File(root, relative);
        if (!target.getParentFile().isDirectory()) target.getParentFile().mkdirs();
        Compat.write(target, value.getBytes(StandardCharsets.UTF_8));
    }
    private static String read(File root, String relative) throws Exception {
        return new String(Compat.readAllBytes(new File(root, relative)), StandardCharsets.UTF_8);
    }
    @Override public void onStart() {
        Bundle result = new Bundle();
        Context actual = getTargetContext();
        Context isolated = new ContextWrapper(actual) {
            @Override public Context getApplicationContext() { return this; }
            @Override public SharedPreferences getSharedPreferences(String name, int mode) {
                return super.getSharedPreferences("backup-instrumentation-owned-" + name, mode);
            }
        };
        ProotBootstrap runtime = new ProotBootstrap(actual);
        File rootfs = new File(runtime.getRootfsDir(), "root/.dsha-opt-java-check/rootfs");
        File root = new File(rootfs, "root");
        ConfigStore config = new ConfigStore(isolated);
        ProotBootstrap fixture = new ProotBootstrap(actual) {
            @Override public File getRootfsDir() { return rootfs; }
            @Override public boolean isEnvironmentReady() { return true; }
            @Override public boolean ensureBundledPython() { return runtime.ensureBundledPython(); }
            @Override public String execAndReadWithProot(String command, long timeout) {
                return runtime.execAndReadWithProot(command.replace("/root/", GUEST + "/") + " --root " + GUEST, timeout);
            }
        };
        HarnessController controller = new HarnessController(isolated) {
            @Override public ProotBootstrap proot() { return fixture; }
            @Override public ConfigStore config() { return config; }
            @Override public boolean isEnvironmentReady() { return true; }
            @Override public void stopWeb() { }
            @Override public boolean isWebRunning() { return false; }
        };
        try {
            if (realSnapshot) {
                HarnessController current = HarnessController.get(actual);
                String saved = BackupManager.backupToExternal(actual, current, BackupScope.FULL);
                check(saved != null, "现有环境备份失败：" + BackupManager.lastError());
                try (BackupManager.PreparedRestore preview = BackupManager.prepareRestore(actual, current, Uri.parse(current.config().getLastBackupUri()))) {
                    check(preview.scope == BackupScope.FULL, "现有环境备份预检范围错误");
                }
                result.putString("snapshot", "PASS: 现有环境完整打包和逐文件预检；未执行恢复");
                result.putString("file", current.config().getLastBackupName());
                result.putString("result", "PASS");
                return;
            }
            config.setBackupKey(false);
            for (int scope : BackupScope.ALL) {
                Bundle phase = new Bundle(); phase.putString("phase", "验证 " + BackupScope.id(scope)); sendStatus(1, phase);
                put(root, ".dsh/sessions/one/session.jsonl", "{\"message\":\"独立会话样本\"}\n");
                put(root, ".dsh/storages/workspace.json", "{\"tables\":{\"workspaces\":{}}}");
                put(root, ".dsh/attachments/test.txt", "attachment-before");
                put(root, ".dsh/settings.yaml", "model: before\n");
                put(root, ".dsh/profiles/web/package.json", "{\"dependencies\":{}}");
                config.setPort("3087");
                String saved = BackupManager.backupToExternal(actual, controller, scope);
                check(saved != null, "备份失败：" + BackupManager.lastError());
                Uri uri = Uri.parse(config.getLastBackupUri()); exported.add(uri);
                check(config.getLastBackupName().startsWith(BackupScope.fileNamePrefix(scope)), "文件名前缀与范围不符");
                try (InputStream in = actual.getContentResolver().openInputStream(uri)) {
                    check(FileIntegrity.copy(in, null, 16L * 1024 * 1024).size > 0, "实际 URI 无法读取");
                }
                BackupManager.PreparedRestore prepared = BackupManager.prepareRestore(actual, controller, uri);
                check(prepared.scope == scope && prepared.summary.contains("SHA-256"), "预览与实际内容不符");
                put(root, ".dsh/sessions/one/session.jsonl", "changed-session");
                put(root, ".dsh/storages/workspace.json", "{\"changed\":true}");
                put(root, ".dsh/attachments/test.txt", "changed-attachment");
                put(root, ".dsh/settings.yaml", "changed-settings");
                put(root, ".dsh/profiles/web/package.json", "{\"changed\":true}");
                config.setPort("3097");
                BackupManager.restorePrepared(controller, prepared);
                boolean sessions = scope == BackupScope.FULL || scope == BackupScope.SESSIONS;
                boolean settings = scope == BackupScope.FULL || scope == BackupScope.SETTINGS;
                boolean plugins = scope == BackupScope.FULL || scope == BackupScope.PLUGINS;
                check(read(root, ".dsh/sessions/one/session.jsonl").contains(sessions ? "独立会话样本" : "changed-session"), "会话范围错误");
                check(read(root, ".dsh/storages/workspace.json").contains(sessions ? "workspaces" : "changed"), "索引范围错误");
                check(read(root, ".dsh/attachments/test.txt").equals(sessions ? "attachment-before" : "changed-attachment"), "附件范围错误");
                check(read(root, ".dsh/settings.yaml").equals(settings ? "model: before\n" : "changed-settings"), "设置范围错误");
                check(read(root, ".dsh/profiles/web/package.json").contains(plugins ? "dependencies" : "changed"), "插件范围错误");
                check(config.getPort().equals(settings ? "3087" : "3097"), "原生设置范围错误");
                result.putString(BackupScope.id(scope), "PASS: MediaStore URI、完整预检、范围隔离与真实容器往返");
            }
            config.setAutoBackupLaunches(2);
            check(!config.countLaunchForBackup() && config.countLaunchForBackup() && !config.countLaunchForBackup(), "自动备份计数不符");
            config.beginRestoreSettings(); config.setPort("3187"); config.finishRestoreSettings(true);
            check(!config.getPort().equals("3187"), "原生设置回滚失败");
            result.putString("native", "PASS: 自动备份阈值、原生设置事务回滚");
            result.putString("result", "PASS");
        } catch (Throwable failure) {
            result.putString("result", "FAIL"); result.putString("error", failure.toString());
        } finally {
            for (Uri uri : exported) try { actual.getContentResolver().delete(uri, null, null); } catch (Exception ignored) { }
            // 删除严格限定在本轮独立目录，既不跟随链接，也不接触用户 .dsh。
            if (!realSnapshot) {
                runtime.execAndReadWithProot("python3 -c 'import shutil; shutil.rmtree(\"/root/.dsha-opt-java-check\", ignore_errors=True)'", 60_000);
                isolated.getSharedPreferences("deepseekharness", Context.MODE_PRIVATE).edit().clear().commit();
            }
            finish("PASS".equals(result.getString("result")) ? -1 : 0, result);
        }
    }
}
