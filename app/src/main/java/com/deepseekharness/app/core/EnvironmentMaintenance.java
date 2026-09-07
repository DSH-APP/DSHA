package com.deepseekharness.app.core;

import com.deepseekharness.app.BackupManager;
import com.deepseekharness.app.util.Compat;
import com.deepseekharness.app.util.Fmt;
import com.deepseekharness.app.util.MaintenanceTransaction;
import java.io.File;
import java.io.IOException;
import java.util.function.Consumer;

/** 维护顺序唯一入口：调用方已停止 Web 并持有 BackupManager 的数据任务锁。 */
public final class EnvironmentMaintenance {
    private EnvironmentMaintenance() { }

    /** 包内故障注入缝；正式入口始终使用真实 APK 解压，debug fixture 可在此抛出失败。 */
    interface ExtractStep { void extract(java.util.function.BiConsumer<Long, Long> progress) throws Exception; }

    public static String rebuild(HarnessController controller, Consumer<String> progress) throws Exception {
        return rebuild(controller, progress, controller.proot()::extractOfflineBundle);
    }

    static String rebuild(HarnessController controller, Consumer<String> progress, ExtractStep extract) throws Exception {
        if (!BackupManager.isDataTaskOwner()) throw new IOException("环境维护必须经过停止屏障与全局数据任务锁");
        File files = controller.proot().getRootfsDir().getParentFile().getParentFile();
        if (MaintenanceTransaction.pending(files) != null) throw new IOException("先恢复上次中断的维护，再尝试重建");
        if (!controller.hasOfflineBundle()) throw new IOException("APK 缺少内置环境包，原环境保持原位");
        File linux = controller.proot().getRootfsDir().getParentFile();
        boolean fresh = !linux.exists();
        // 不把损坏环境误认为首次安装；缺少 Python/数据时安全备份会失败并保留原环境。
        MaintenanceTransaction transaction = MaintenanceTransaction.create(files);
        if (!fresh) {
            progress.accept("正在创建完整安全备份（含私有配置、会话和本地插件）…");
            String hash = BackupManager.createMaintenanceBackup(controller, transaction.archive());
            transaction.verify(hash);
            progress.accept("安全备份与逐文件校验通过，正在保留旧环境…");
        }
        try {
            transaction.begin(fresh);
            final long[] last = {0};
            extract.extract((done, total) -> {
                long now = android.os.SystemClock.elapsedRealtime();
                if (now - last[0] >= 500) { last[0] = now; progress.accept("正在解压内置环境… " + Fmt.bytes(done)); }
            });
            if (!controller.isEnvironmentReady()) throw new IOException("新环境安装校验未通过");
            if (!fresh) {
                progress.accept("正在恢复配置、会话与本地插件…");
                // 出厂 .dsh 可能带公有目录软链；整棵移到私有保留位置，恢复生成真实目录。
                File data = new File(controller.proot().getRootfsDir(), "root/.dsh");
                File bundled = new File(controller.proot().getRootfsDir(), "root/.dsha-bundled-before-maintenance");
                if ((data.exists() || Compat.isSymbolicLink(data)) && (bundled.exists() || !data.renameTo(bundled)))
                    throw new IOException("无法隔离内置数据目录，已停止恢复");
                BackupManager.restoreMaintenanceBackup(controller, transaction.archive());
            }
            File stopped = new File(controller.proot().getRootfsDir(), "root/.dsha-stopped");
            if (!stopped.exists() && !stopped.createNewFile()) throw new IOException("无法保持 Web 停止状态");
            transaction.commit();
            return fresh ? "环境准备完成，可进入主界面。"
                    : "环境重建完成，配置、会话和本地插件已恢复。\n安全备份与旧环境保留于：\n"
                    + transaction.directory().getAbsolutePath() + "\n额外安装的系统软件保留在旧环境中，未迁入新环境。";
        } catch (Exception failure) {
            // 同线程同步作用域不算其他任务；retainUntilExit 已 detach 的进程必须等退出后再移动目录。
            if (RuntimeTasks.hasOtherTasks()) {
                throw new IOException("维护未完成，后台进程尚未退出，已暂停自动回切。维护日志、旧环境和新环境均保留。"
                        + "请等待后台进程退出后，再使用「恢复中断维护」。\n"
                        + transaction.directory().getAbsolutePath() + "\n" + BackupManager.safeError(failure), failure);
            }
            try {
                if (MaintenanceTransaction.pending(files) != null) {
                    progress.accept("维护未完成，正在回切原环境…");
                    transaction.rollback();
                }
            } catch (Exception rollback) {
                throw new IOException("维护失败且自动回切未完成；所有目录均保留。请使用「恢复中断维护」。\n"
                        + transaction.directory().getAbsolutePath() + "\n" + BackupManager.safeError(rollback), failure);
            }
            throw new IOException("维护未完成，原环境已保留；安全备份位置：\n" + transaction.directory().getAbsolutePath()
                    + "\n" + BackupManager.safeError(failure), failure);
        }
    }

    public static String recover(HarnessController controller) throws Exception {
        if (!BackupManager.isDataTaskOwner()) throw new IOException("维护回滚必须经过停止屏障与全局数据任务锁");
        MaintenanceTransaction pending = MaintenanceTransaction.pending(controller.proot().getRootfsDir().getParentFile().getParentFile());
        if (pending == null) return "没有未完成的环境维护。";
        if (RuntimeTasks.hasOtherTasks()) throw new IOException("后台进程尚未退出，维护日志及新旧环境均保留。请等待进程退出后再恢复中断维护。");
        pending.rollback();
        return "中断的维护已回滚；安全备份、旧环境及失败的新环境均已保留。\n" + pending.directory().getAbsolutePath();
    }
}
