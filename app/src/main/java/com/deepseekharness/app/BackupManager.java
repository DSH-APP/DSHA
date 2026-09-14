package com.deepseekharness.app;

import android.content.Context;
import android.net.Uri;
import android.provider.OpenableColumns;
import com.deepseekharness.app.core.HarnessController;
import com.deepseekharness.app.data.DownloadsExport;
import com.deepseekharness.app.util.BackupScope;
import com.deepseekharness.app.util.Compat;
import com.deepseekharness.app.util.FileIntegrity;
import com.deepseekharness.app.util.SensitiveData;
import com.deepseekharness.app.util.ShellQuote;
import org.json.JSONObject;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.UUID;

/** Android 协调层：URI 输入输出与配置；归档、范围和事务由容器内核心完成。 */
public final class BackupManager {
    private BackupManager() { }
    private static final Object LOCK = new Object();
    private static final long MAX_ARCHIVE = 16L * 1024 * 1024 * 1024;
    private static volatile String error = "";
    private static final java.util.concurrent.atomic.AtomicBoolean restoring = new java.util.concurrent.atomic.AtomicBoolean();
    public static final String LATEST_BACKUP_NAME = "DSHA-backup-latest.tar.gz";
    public static String lastError() { return SensitiveData.redact(error); }
    public static boolean isRestoring() { return restoring.get(); }

    /** 安全入口供页面任务使用：停止队列排空后才拿归档锁，避免运行任务互等。 */
    public interface DataOperation<T> { T run() throws Exception; }
    private static final ThreadLocal<Boolean> dataOwner = new ThreadLocal<>();
    public static boolean isDataTaskOwner() { return Boolean.TRUE.equals(dataOwner.get()); }
    /** 快照/预检只取得数据锁，保留正在运行的 Web；引擎检测到数据变化时返回失败。 */
    public static <T> T runSnapshotTask(HarnessController controller, DataOperation<T> operation) throws Exception {
        if (!com.deepseekharness.app.util.EnvironmentTaskGate.ownsCurrentThread()) {
            com.deepseekharness.app.util.EnvironmentTaskGate.Lease lease =
                    com.deepseekharness.app.util.EnvironmentTaskGate.tryAcquire(com.deepseekharness.app.util.UiText.text("数据快照"));
            if (lease == null) throw new IOException(com.deepseekharness.app.util.UiText.text("有安装、备份、恢复或维护任务正在进行"));
            try (lease) { return lease.run(() -> runSnapshotTask(controller, operation)); }
        }
        synchronized (LOCK) {
            try (com.deepseekharness.app.core.RuntimeTasks work = com.deepseekharness.app.core.RuntimeTasks.begin()) {
                return operation.run();
            }
        }
    }
    public static <T> T runDataTask(HarnessController controller, DataOperation<T> operation) throws Exception {
        if (Thread.holdsLock(LOCK)) throw new IOException(com.deepseekharness.app.util.UiText.text("不能在快照或归档回调内停止 Web；请先结束快照，再开始恢复或维护"));
        if (!com.deepseekharness.app.util.EnvironmentTaskGate.ownsCurrentThread()) {
            com.deepseekharness.app.util.EnvironmentTaskGate.Lease lease =
                    com.deepseekharness.app.util.EnvironmentTaskGate.tryAcquire(com.deepseekharness.app.util.UiText.text("数据维护"));
            if (lease == null) throw new IOException(com.deepseekharness.app.util.UiText.text("有安装、备份、恢复或维护任务正在进行"));
            try (lease) { return lease.run(() -> runDataTask(controller, operation)); }
        }
        if (!restoring.compareAndSet(false, true)) throw new IOException(com.deepseekharness.app.util.UiText.text("已有备份、恢复或维护任务，请等待完成"));
        try {
            stopWebForMaintenance(controller);
            com.deepseekharness.app.ui.PtyTerminalFragment.shutdownAndWait(5000);
            com.deepseekharness.app.ui.TerminalFragment.shutdownShellAndWait(5000);
            com.deepseekharness.app.util.RuntimeTaskRegistry.Maintenance maintenance =
                    com.deepseekharness.app.core.RuntimeTasks.tryEnterMaintenance();
            long drainDeadline = android.os.SystemClock.elapsedRealtime() + 3000;
            while (maintenance == null && android.os.SystemClock.elapsedRealtime() < drainDeadline) {
                Thread.sleep(50);
                maintenance = com.deepseekharness.app.core.RuntimeTasks.tryEnterMaintenance();
            }
            if (maintenance == null) throw new IOException(com.deepseekharness.app.util.UiText.text("Web 已停止，但终端或后台任务仍在运行。请结束这些任务后重试；原环境未移动，数据未覆盖。"));
            // 检查与新 RuntimeTasks 登记原子互斥；只放行本线程的同步嵌套任务。
            try (com.deepseekharness.app.util.RuntimeTaskRegistry.Maintenance held = maintenance) {
            synchronized (LOCK) {
                dataOwner.set(true);
                try (com.deepseekharness.app.core.RuntimeTasks work = com.deepseekharness.app.core.RuntimeTasks.begin()) {
                    return operation.run();
                } finally { dataOwner.remove(); }
            }
            }
        } finally { restoring.set(false); }
    }

    /** 主线程与安装入口的只读门控；调用方仍需原子取得 EnvironmentTaskGate.Lease 才能开始工作。 */
    public static boolean isEnvironmentTaskBusy() {
        return restoring.get() || com.deepseekharness.app.util.EnvironmentTaskGate.isBusy();
    }

    /** 与普通停止共用 PID 身份与同 UID 进程核验，未知读取错误仍阻止维护。 */
    public static void stopWebForMaintenance(HarnessController controller) throws Exception {
        if (Thread.holdsLock(LOCK)) throw new IOException(com.deepseekharness.app.util.UiText.text("等待 Web 停止前必须释放归档锁"));
        if (android.os.Looper.myLooper() == android.os.Looper.getMainLooper())
            throw new IOException(com.deepseekharness.app.util.UiText.text("请在独立数据任务线程等待 Web 停止，不能阻塞界面线程"));
        controller.stopWeb(message -> { });
        long deadline = android.os.SystemClock.elapsedRealtime() + 45_000;
        do {
            if (Thread.currentThread().isInterrupted()) throw new InterruptedException(com.deepseekharness.app.util.UiText.text("等待停止被中断"));
            if (!controller.isStarting() && !controller.isStopping() && controller.isWebStoppedForMaintenance()) return;
            Thread.sleep(100);
        } while (android.os.SystemClock.elapsedRealtime() < deadline);
        throw new IOException(com.deepseekharness.app.util.UiText.text("等待 Web 或它启动的后台进程退出超时；原环境未移动，请结束运行任务后重试"));
    }

    /** 新增的启动查询供安装/运行 worker 接入；未完成的磁盘事务必须先回滚。 */
    public static boolean hasPendingMaintenance(HarnessController controller) {
        return hasPendingMaintenance(controller.proot().getRootfsDir().getParentFile().getParentFile());
    }
    public static boolean hasPendingMaintenance(File filesDir) {
        try { return com.deepseekharness.app.util.MaintenanceTransaction.pending(filesDir) != null
                || com.deepseekharness.app.util.RuntimeUpdateTransaction.pending(filesDir) != null; }
        catch (IOException e) { return true; }
    }

    public static void recoverMaintenanceBeforeStart(HarnessController controller) throws Exception {
        if (!hasPendingMaintenance(controller)) return;
        runDataTask(controller, () -> {
            com.deepseekharness.app.core.EnvironmentMaintenance.recover(controller);
            return null;
        });
    }

    /** 安全归档永远位于 linux 之外，不导出公共存储，也不覆盖已有归档。 */
    public static String createMaintenanceBackup(HarnessController controller, File destination) throws Exception {
        if (!Boolean.TRUE.equals(dataOwner.get())) throw new IOException(com.deepseekharness.app.util.UiText.text("维护备份必须持有全局任务锁"));
        if (destination.exists()) throw new IOException(com.deepseekharness.app.util.UiText.text("安全备份目标已存在"));
        File rootfs = controller.proot().getRootfsDir();
        if (destination.getCanonicalPath().startsWith(rootfs.getParentFile().getCanonicalPath() + File.separator))
            throw new IOException(com.deepseekharness.app.util.UiText.text("安全备份不能位于待替换环境内"));
        String token = UUID.randomUUID().toString();
        File archive = new File(rootfs, "root/.dsha-maintenance-" + token + ".tar.gz");
        File config = new File(rootfs, "root/.dsha-maintenance-" + token + ".json");
        try {
            Compat.write(config, controller.config().exportBackupSettings().toString().getBytes(StandardCharsets.UTF_8));
            JSONObject result = run(controller, "backup --scope full --archive " + ShellQuote.arg("/root/" + archive.getName())
                    + " --native-config " + ShellQuote.arg("/root/" + config.getName())
                    + " --app-version " + ShellQuote.arg(BuildConfig.VERSION_NAME) + " --app-code " + BuildConfig.VERSION_CODE);
            FileIntegrity.Result copied;
            try (FileInputStream in = new FileInputStream(archive); FileOutputStream out = new FileOutputStream(destination)) {
                copied = FileIntegrity.copy(in, out, MAX_ARCHIVE); out.getFD().sync();
            }
            if (copied.size != result.getLong("bytes") || !copied.sha256.equals(result.getString("sha256")))
                throw new IOException(com.deepseekharness.app.util.UiText.text("私有安全备份复制校验失败，原环境保持原位"));
            return copied.sha256;
        } finally { archive.delete(); config.delete(); }
    }

    /** 独立任务内恢复，共用原有引擎的预检、逐文件校验与延迟提交协议。 */
    public static String restoreWithinDataTask(HarnessController controller, PreparedRestore prepared) throws Exception {
        if (!Boolean.TRUE.equals(dataOwner.get())) throw new IOException(com.deepseekharness.app.util.UiText.text("恢复必须持有全局任务锁"));
        try (InputStream in = new FileInputStream(prepared.archive)) {
            if (!prepared.hash.equals(FileIntegrity.copy(in, null, MAX_ARCHIVE).sha256)) throw new IOException(com.deepseekharness.app.util.UiText.text("待恢复文件已变化"));
        }
        try {
            controller.config().beginRestoreSettings();
            JSONObject result = run(controller, "restore --archive " + ShellQuote.arg("/root/" + prepared.archive.getName())
                    + " --scope " + BackupScope.id(prepared.scope) + " --defer-commit");
            if (!result.optBoolean("committed")) throw new IOException(com.deepseekharness.app.util.UiText.text("恢复尚未提交"));
            JSONObject settings = result.optJSONObject("nativeConfig");
            if (settings != null) controller.config().importBackupSettings(settings);
            run(controller, "finalize");
            controller.config().finishRestoreSettings(false);
            return com.deepseekharness.app.util.UiText.text("恢复完成：") + BackupScope.label(prepared.scope) + com.deepseekharness.app.util.UiText.text("；原数据已保留，完成后可手动启动 Web。");
        } catch (Exception e) {
            try { recoverInterrupted(controller); } catch (Exception rollback) { e.addSuppressed(rollback); }
            throw e;
        }
    }

    /** 从私有安全归档恢复到新容器；只拷贝本次输入，绝不把安全归档交给 close 删除。 */
    public static void restoreMaintenanceBackup(HarnessController controller, File archive) throws Exception {
        if (!Boolean.TRUE.equals(dataOwner.get())) throw new IOException(com.deepseekharness.app.util.UiText.text("维护恢复必须持有全局任务锁"));
        File input = new File(controller.proot().getRootfsDir(), "root/.dsha-maintenance-input-" + UUID.randomUUID() + ".tar.gz");
        try {
            String hash;
            try (InputStream in = new FileInputStream(archive); FileOutputStream out = new FileOutputStream(input)) {
                hash = FileIntegrity.copy(in, out, MAX_ARCHIVE).sha256; out.getFD().sync();
            }
            // 原生偏好及 Keystore 保持原位；只恢复容器数据，避免维护引入跨层设置事务。
            JSONObject result = run(controller, "restore --scope full --archive " + ShellQuote.arg("/root/" + input.getName()));
            if (!result.optBoolean("committed")) throw new IOException(com.deepseekharness.app.util.UiText.text("环境数据未完整恢复"));
            try (InputStream in = new FileInputStream(input)) {
                if (!hash.equals(FileIntegrity.copy(in, null, MAX_ARCHIVE).sha256)) throw new IOException(com.deepseekharness.app.util.UiText.text("安全归档发生变化"));
            }
        } finally { input.delete(); }
    }

    public static String backupToExternal(Context ctx, HarnessController controller) {
        return backupToExternal(ctx, controller, BackupScope.FULL);
    }

    public static String backupToExternal(Context ctx, HarnessController controller, int scope) {
        synchronized (LOCK) {
            error = "";
            String token = UUID.randomUUID().toString();
            File archive = new File(controller.proot().getRootfsDir(), "root/.dsha-backup-" + token + ".tar.gz");
            File config = new File(controller.proot().getRootfsDir(), "root/.dsha-config-" + token + ".json");
            try (com.deepseekharness.app.core.RuntimeTasks work = com.deepseekharness.app.core.RuntimeTasks.begin()) {
                Compat.write(config, controller.config().exportBackupSettings().toString().getBytes(StandardCharsets.UTF_8));
                JSONObject result = run(controller, "backup --archive " + ShellQuote.arg("/root/" + archive.getName())
                        + " --scope " + BackupScope.id(scope) + " --app-version " + ShellQuote.arg(BuildConfig.VERSION_NAME)
                        + " --app-code " + BuildConfig.VERSION_CODE + " --native-config " + ShellQuote.arg("/root/" + config.getName()));
                if (!archive.isFile() || archive.length() != result.getLong("bytes")) throw new IOException(com.deepseekharness.app.util.UiText.text("打包产物不完整"));
                String suffix = new SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(new Date()) + "-" + token.substring(0, 8);
                DownloadsExport.Result saved = DownloadsExport.write(ctx, archive, BackupScope.archiveName(scope, suffix));
                if (!saved.integrity.sha256.equals(result.getString("sha256"))) throw new IOException(com.deepseekharness.app.util.UiText.text("归档与导出摘要不符"));
                controller.config().recordBackupResult(saved.uri.toString(), saved.displayName, "", scope);
                return "Download/DSHA/" + saved.displayName + "\nSHA-256：" + saved.integrity.sha256 + warnings(result);
            } catch (Exception e) {
                error = safeError(e);
                controller.config().recordBackupResult("", "", error, scope);
                return null;
            } finally { archive.delete(); config.delete(); }
        }
    }

    private static JSONObject run(HarnessController controller, String arguments) throws Exception {
        // 升级门禁不能挡住旧环境的迁移备份；只有持有停止屏障的数据任务能使用旧版本。
        if (!controller.isEnvironmentReady() && !(isDataTaskOwner() && controller.proot().hasBash()))
            throw new IOException(com.deepseekharness.app.util.UiText.text("环境未就绪，请先完成安装"));
        if (!controller.proot().ensureBundledPython()) throw new IOException(com.deepseekharness.app.util.UiText.text("内置 Python 无法使用，请从诊断页修复工具"));
        File script = new File(controller.proot().getRootfsDir(), "root/.dsha-backup-engine.py");
        String asset = controller.readAsset("backup-engine.py");
        if (asset.isEmpty()) throw new IOException(com.deepseekharness.app.util.UiText.text("缺少备份核心脚本"));
        Compat.write(script, asset.getBytes(StandardCharsets.UTF_8));
        for (String helper : new String[]{"backup-plugin-graph.py", "register-builtin-plugins.py"}) {
            String body = controller.readAsset(helper);
            if (body.isEmpty()) throw new IOException(com.deepseekharness.app.util.UiText.text("缺少备份支持脚本：") + helper);
            Compat.write(new File(controller.proot().getRootfsDir(), "root/.dsha-" + helper), body.getBytes(StandardCharsets.UTF_8));
        }
        String out = controller.proot().execAndReadWithProot("python3 -B /root/.dsha-backup-engine.py " + arguments
                + " --workdir " + ShellQuote.arg(controller.config().getWorkdir()) + " 2>&1", 600_000);
        String marker = "DSHA_BACKUP_RESULT=";
        int start = out == null ? -1 : out.lastIndexOf(marker);
        if (start < 0) throw new IOException(out == null ? com.deepseekharness.app.util.UiText.text("备份核心没有返回结果") : out);
        return new JSONObject(out.substring(start + marker.length()).trim());
    }

    /** 在下次启动 dsh 之前回滚上次异常退出的恢复事务。 */
    public static void recoverInterrupted(HarnessController controller) throws Exception {
        synchronized (LOCK) {
            File journal = new File(controller.proot().getRootfsDir(), "root/.dsha-restore-journal.json");
            boolean interrupted = journal.isFile() && !new JSONObject(new String(Compat.readAllBytes(journal), StandardCharsets.UTF_8)).optBoolean("complete");
            if (journal.isFile()) run(controller, "recover");
            controller.config().finishRestoreSettings(interrupted);
        }
    }

    private static String warnings(JSONObject result) {
        org.json.JSONArray values = result.optJSONArray("warnings");
        if (values == null || values.length() == 0) return "";
        StringBuilder text = new StringBuilder(com.deepseekharness.app.util.UiText.text("\n注意："));
        for (int i = 0; i < Math.min(10, values.length()); i++) text.append('\n').append(values.optString(i));
        if (values.length() > 10) text.append(com.deepseekharness.app.util.UiText.text("\n其余缺失项见备份清单。"));
        return text.toString();
    }

    public static final class PreparedRestore implements AutoCloseable {
        final File archive;
        final String hash;
        public final int scope;
        public final String summary;
        PreparedRestore(File archive, String hash, int scope, String summary) {
            this.archive = archive; this.hash = hash; this.scope = scope; this.summary = summary;
        }
        @Override public void close() { archive.delete(); }
    }

    /** 先保存独立副本并完整预检，再给用户展示真实内容和影响范围。 */
    public static PreparedRestore prepareRestore(Context ctx, HarnessController controller, Uri uri) throws Exception {
        synchronized (LOCK) {
            String name = "";
            try (android.database.Cursor cursor = ctx.getContentResolver().query(uri,
                    new String[]{OpenableColumns.DISPLAY_NAME}, null, null, null)) {
                if (cursor != null && cursor.moveToFirst()) name = cursor.getString(0);
            } catch (Exception ignored) { name = uri.getLastPathSegment(); }
            if (name == null || name.isEmpty()) name = uri.getLastPathSegment();
            File target = new File(controller.proot().getRootfsDir(), "root/.dsha-restore-input-" + UUID.randomUUID() + ".tar.gz");
            boolean ready = false;
            try (com.deepseekharness.app.core.RuntimeTasks work = com.deepseekharness.app.core.RuntimeTasks.begin()) {
                FileIntegrity.Result copied;
                try (InputStream in = ctx.getContentResolver().openInputStream(uri); FileOutputStream out = new FileOutputStream(target)) {
                    copied = FileIntegrity.copy(in, out, Math.min(MAX_ARCHIVE, Math.max(0, target.getParentFile().getUsableSpace() - 32L * 1024 * 1024)));
                    out.getFD().sync();
                }
                if (copied.size == 0) throw new IOException(com.deepseekharness.app.util.UiText.text("所选备份为空"));
                int guessed = BackupScope.fromFileName(name);
                JSONObject result = run(controller, "inspect --archive " + ShellQuote.arg("/root/" + target.getName()) + " --scope " + BackupScope.id(guessed));
                int scope = BackupScope.fromId(result.getString("scope"));
                JSONObject manifest = result.optJSONObject("manifest");
                String summary = BackupScope.label(scope) + "\n" + BackupScope.restoreImpact(scope)
                        + com.deepseekharness.app.util.UiText.text("\n文件数：") + result.getInt("files") + com.deepseekharness.app.util.UiText.text("\n解压内容：") + HarnessController.fmtBytes(result.getLong("bytes"))
                        + com.deepseekharness.app.util.UiText.text("\n来自版本：") + (manifest == null ? com.deepseekharness.app.util.UiText.text("未知") : manifest.optString("appVersion", com.deepseekharness.app.util.UiText.text("未知")))
                        + (result.optBoolean("legacy") ? com.deepseekharness.app.util.UiText.text("\n旧格式：已验证归档完整性，但包内没有逐文件摘要。") : com.deepseekharness.app.util.UiText.text("\n归档与逐文件 SHA-256 校验通过。"))
                        + warnings(result)
                        + com.deepseekharness.app.util.UiText.text("\n\n恢复会先停止 Web。当前数据会保留为 .pre-restore-*，恢复失败自动回滚；完成后可手动启动 Web。");
                ready = true;
                return new PreparedRestore(target, copied.sha256, scope, summary);
            } finally { if (!ready) target.delete(); }
        }
    }

    public static String restorePrepared(HarnessController controller, PreparedRestore prepared) throws Exception {
        if (!restoring.compareAndSet(false, true)) throw new IOException(com.deepseekharness.app.util.UiText.text("已有恢复任务正在进行"));
        try (com.deepseekharness.app.core.RuntimeTasks work = com.deepseekharness.app.core.RuntimeTasks.begin()) {
            // 等待 Web 停止时不持有归档锁，避免与运行队列形成互等。
            controller.stopWeb();
            synchronized (LOCK) {
            try {
                try (InputStream in = new FileInputStream(prepared.archive)) {
                    if (!prepared.hash.equals(FileIntegrity.copy(in, null, MAX_ARCHIVE).sha256)) throw new IOException(com.deepseekharness.app.util.UiText.text("待恢复文件发生变化，请重新选择"));
                }
                if (controller.isWebRunning()) throw new IOException(com.deepseekharness.app.util.UiText.text("Web 尚未停止，现有数据未覆盖，请稍后重试"));
                controller.config().beginRestoreSettings();
                JSONObject result = run(controller, "restore --archive " + ShellQuote.arg("/root/" + prepared.archive.getName())
                        + " --scope " + BackupScope.id(prepared.scope) + " --defer-commit");
                if (!result.optBoolean("committed")) throw new IOException(com.deepseekharness.app.util.UiText.text("恢复尚未提交"));
                // 恢复出来的 .dsh 可能带着【备份那台机器】的本机凭据：
                //   · .bridge_token —— 3090 桥的共享凭据（新版备份已排除，但老备份仍带）
                //   · browser-session —— 登录 cookie 的签名密钥（新版按字段剔除）
                // 与本机内存里的值不一致时桥会拒绝所有请求，用户看到「需要 token，
                // 请在 DSHA 应用内打开」。这里在提交之后立即让本机凭据重新对齐。
                com.deepseekharness.app.HttpShellService.resetTokenAfterRestore();
                JSONObject nativeConfig = result.optJSONObject("nativeConfig");
                if (nativeConfig != null) controller.config().importBackupSettings(nativeConfig);
                run(controller, "finalize");
                controller.config().finishRestoreSettings(false);
                return com.deepseekharness.app.util.UiText.text("恢复完成：") + BackupScope.label(prepared.scope) + com.deepseekharness.app.util.UiText.text("\n文件数：") + result.getInt("files")
                        + com.deepseekharness.app.util.UiText.text("\n原数据已保留，重新启动 Web 后可查看恢复内容。");
            } catch (Exception failure) {
                try { recoverInterrupted(controller); }
                catch (Exception rollback) { failure.addSuppressed(rollback); }
                throw failure;
            }
            }
        } finally { restoring.set(false); prepared.close(); }
    }

    public static String restoreFromBackup(Context ctx, HarnessController controller, Uri uri) throws Exception {
        try (PreparedRestore prepared = prepareRestore(ctx, controller, uri)) { return restorePrepared(controller, prepared); }
    }
    public static String restoreFromBackup(Context ctx, HarnessController controller, File file) throws Exception {
        return restoreFromBackup(ctx, controller, Uri.fromFile(file));
    }
    public static String exportToDownloads(Context ctx, File source, String name) {
        try (com.deepseekharness.app.core.RuntimeTasks work = com.deepseekharness.app.core.RuntimeTasks.begin()) {
            return DownloadsExport.write(ctx, source, name).uri.toString();
        }
        catch (Exception e) { error = safeError(e); return null; }
    }
    public static String safeError(Exception e) {
        String message = SensitiveData.redact(e.getMessage() == null ? e.toString() : e.getMessage()).trim();
        return message.length() < 800 ? message : message.substring(message.length() - 800);
    }
}
