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

    /** 同一 Ubuntu 基础环境只替换 APK 受管树，现有 .dsh 数据与工作区无需复制。 */
    public static String update(HarnessController controller, Consumer<String> progress) throws Exception {
        if (!controller.proot().canUpdateManagedRuntime()) return rebuild(controller, progress);
        if (!BackupManager.isDataTaskOwner()) throw new IOException(com.deepseekharness.app.util.UiText.text("运行时更新必须持有停止屏障与数据任务锁"));
        File files = controller.proot().getRootfsDir().getParentFile().getParentFile();
        if (BackupManager.hasPendingMaintenance(files)) throw new IOException(com.deepseekharness.app.util.UiText.text("请先恢复中断的环境维护"));
        requireSpace(files, 512L * 1024 * 1024);
        com.deepseekharness.app.util.RuntimeUpdateTransaction transaction = com.deepseekharness.app.util.RuntimeUpdateTransaction.create(files);
        boolean began = false;
        try {
            java.util.List<String> paths = controller.proot().stageManagedRuntime(transaction.stage(), progress);
            transaction.begin(paths); began = true;
            progress.accept(com.deepseekharness.app.util.UiText.text("新版运行时准备完成，正在保留并切换受管文件…"));
            transaction.replace();
            controller.proot().ensureDshRuntimePatches();
            controller.proot().prepareRuntimeTools();
            progress.accept(com.deepseekharness.app.util.UiText.text("正在验证 Node、Python、原生模块及新版 dsh…"));
            validateRuntime(controller.proot(), false);
            transaction.commit();
        } catch (Exception failure) {
            if (began && !RuntimeTasks.hasOtherTasks()) transaction.rollback();
            throw new IOException(com.deepseekharness.app.util.UiText.text("运行时更新未完成，原运行时及个人数据已保留：\n") + transaction.directory()
                    + "\n" + BackupManager.safeError(failure), failure);
        }
        String cleanup = "";
        try { progress.accept(com.deepseekharness.app.util.UiText.text("验证通过，正在释放旧受管运行时…")); transaction.cleanup(EnvironmentMaintenance::deleteTree); }
        catch (IOException error) { cleanup = com.deepseekharness.app.util.UiText.text("\n部分旧运行时待下次清理：") + BackupManager.safeError(error); }
        return com.deepseekharness.app.util.UiText.text("dsh 与内置插件更新完成；Ubuntu、个人目录、会话、配置和第三方插件保持原位。") + cleanup;
    }

    static String rebuild(HarnessController controller, Consumer<String> progress, ExtractStep extract) throws Exception {
        if (!BackupManager.isDataTaskOwner()) throw new IOException(com.deepseekharness.app.util.UiText.text("环境维护必须经过停止屏障与全局数据任务锁"));
        File files = controller.proot().getRootfsDir().getParentFile().getParentFile();
        if (MaintenanceTransaction.pending(files) != null) throw new IOException(com.deepseekharness.app.util.UiText.text("先恢复上次中断的维护，再尝试重建"));
        if (!controller.hasOfflineBundle()) throw new IOException(com.deepseekharness.app.util.UiText.text("APK 缺少内置环境包，原环境保持原位"));
        File linux = controller.proot().getRootfsDir().getParentFile();
        boolean fresh = !linux.exists();
        long expanded = controller.proot().expandedEnvironmentBytes();
        requireSpace(files, expanded + 64L * 1024 * 1024);
        // 不把损坏环境误认为首次安装；缺少 Python/数据时安全备份会失败并保留原环境。
        MaintenanceTransaction transaction = MaintenanceTransaction.create(files);
        EnvironmentDataBackup.Snapshot personal = null;
        boolean switchStarted = false;
        try {
        if (!fresh) {
            BackupManager.recoverInterrupted(controller);
            progress.accept(com.deepseekharness.app.util.UiText.text("正在创建完整安全备份（含私有配置、会话和本地插件）…"));
            String hash = BackupManager.createMaintenanceBackup(controller, transaction.archive());
            transaction.verify(hash);
            progress.accept(com.deepseekharness.app.util.UiText.text("正在保护个人目录、项目文件和已登记工作区…"));
            personal = EnvironmentDataBackup.snapshot(controller, transaction.personalArchive(), progress);
            // 已保留旧环境与归档；新环境解压、个人数据恢复和归档输入仍需临时空间。
            requireSpace(files, expanded + personal.unpackedBytes + transaction.personalArchive().length()
                    + transaction.archive().length() * 4 + 64L * 1024 * 1024);
            progress.accept(com.deepseekharness.app.util.UiText.text("安全备份与逐文件校验通过，正在保留旧环境…"));
        }
            switchStarted = true;
            transaction.begin(fresh);
            final long[] last = {0};
            extract.extract(new com.deepseekharness.app.runtime.ProotBootstrap.ExtractionProgress() {
                @Override public void onStage(String stage) { progress.accept(stage + "…"); }
                @Override public void accept(Long done, Long total) {
                    long now = android.os.SystemClock.elapsedRealtime();
                    if (now - last[0] >= 500) {
                        last[0] = now;
                        progress.accept(com.deepseekharness.app.util.UiText.text("正在解压内置环境… ") + (total > 0 ? Math.min(100, done * 100 / total)
                                + "%（" + Fmt.bytes(done) + " / " + Fmt.bytes(total) + com.deepseekharness.app.util.UiText.text("）") : Fmt.bytes(done)));
                    }
                }
            });
            if (!controller.isEnvironmentReady()) throw new IOException(com.deepseekharness.app.util.UiText.text("新环境安装校验未通过"));
            if (!fresh) {
                progress.accept(com.deepseekharness.app.util.UiText.text("正在恢复个人目录与工作区，并逐文件校验…"));
                EnvironmentDataBackup.restore(controller, transaction.personalArchive(), personal.hash, progress);
                progress.accept(com.deepseekharness.app.util.UiText.text("正在恢复配置、会话与本地插件…"));
                // 出厂 .dsh 可能带公有目录软链；整棵移到私有保留位置，恢复生成真实目录。
                File data = new File(controller.proot().getRootfsDir(), "root/.dsh");
                File bundled = new File(controller.proot().getRootfsDir(), "root/.dsha-bundled-before-maintenance");
                if ((data.exists() || Compat.isSymbolicLink(data)) && (bundled.exists() || !data.renameTo(bundled)))
                    throw new IOException(com.deepseekharness.app.util.UiText.text("无法隔离内置数据目录，已停止恢复"));
                BackupManager.restoreMaintenanceBackup(controller, transaction.archive());
            }
            // 个人设置恢复后重放新版 APK 的受管工具；内置插件使用新版源码。
            controller.proot().prepareRuntimeTools();
            progress.accept(com.deepseekharness.app.util.UiText.text("正在验证 Node、Python、原生模块及新版 dsh…"));
            validateRuntime(controller.proot());
            File stopped = new File(controller.proot().getRootfsDir(), "root/.dsha-stopped");
            if (!stopped.exists() && !stopped.createNewFile()) throw new IOException(com.deepseekharness.app.util.UiText.text("无法保持 Web 停止状态"));
            if (personal != null) transaction.dataPreserved(personal.hash);
            transaction.commit();
            String cleanup = "";
            if (!fresh) {
                progress.accept(com.deepseekharness.app.util.UiText.text("数据校验通过，正在释放旧运行环境和迁移临时文件…"));
                try {
                    transaction.cleanup(EnvironmentMaintenance::deleteTree);
                    File bundled = new File(controller.proot().getRootfsDir(), "root/.dsha-bundled-before-maintenance");
                    if (bundled.exists() || Compat.isSymbolicLink(bundled)) deleteTree(bundled);
                } catch (IOException failure) {
                    cleanup = com.deepseekharness.app.util.UiText.text("\n部分旧文件暂未释放，下次启动会重试清理：") + BackupManager.safeError(failure);
                }
            }
            return fresh ? com.deepseekharness.app.util.UiText.text("环境准备完成，可进入主界面。")
                    : com.deepseekharness.app.util.UiText.text("环境更新完成，个人目录、工作区、配置、会话和插件已恢复并校验。")
                    + com.deepseekharness.app.util.UiText.text("\n旧运行环境已进入清理流程，升级前的对话与配置安全备份：\n")
                    + transaction.archive().getAbsolutePath() + cleanup;
        } catch (Exception failure) {
            // 切换之前原环境仍是完整数据源；释放本事务额外产生的个人文件压缩副本。
            if (!switchStarted) transaction.personalArchive().delete();
            // 同线程同步作用域不算其他任务；retainUntilExit 已 detach 的进程必须等退出后再移动目录。
            if (RuntimeTasks.hasOtherTasks()) {
                throw new IOException(com.deepseekharness.app.util.UiText.text("维护未完成，后台进程尚未退出，已暂停自动回切。维护日志、旧环境和新环境均保留。")
                        + com.deepseekharness.app.util.UiText.text("请等待后台进程退出后，再使用「恢复中断维护」。\n")
                        + transaction.directory().getAbsolutePath() + "\n" + BackupManager.safeError(failure), failure);
            }
            try {
                if (MaintenanceTransaction.pending(files) != null) {
                    progress.accept(com.deepseekharness.app.util.UiText.text("维护未完成，正在回切原环境…"));
                    transaction.rollback();
                }
            } catch (Exception rollback) {
                throw new IOException(com.deepseekharness.app.util.UiText.text("维护失败且自动回切未完成；所有目录均保留。请使用「恢复中断维护」。\n")
                        + transaction.directory().getAbsolutePath() + "\n" + BackupManager.safeError(rollback), failure);
            }
            throw new IOException(com.deepseekharness.app.util.UiText.text("维护未完成，原环境已保留；安全备份位置：\n") + transaction.directory().getAbsolutePath()
                    + "\n" + BackupManager.safeError(failure), failure);
        }
    }

    private static void requireSpace(File files, long required) throws IOException {
        long available = files.getUsableSpace();
        if (available < required) throw new IOException(com.deepseekharness.app.util.UiText.text("可用空间不足：本阶段预计需要 ") + Fmt.bytes(required)
                + com.deepseekharness.app.util.UiText.text("，当前可用 ") + Fmt.bytes(available) + com.deepseekharness.app.util.UiText.text("。原环境保持原位，请释放空间后重试。"));
    }

    private static void validateRuntime(com.deepseekharness.app.runtime.ProotBootstrap proot) throws IOException {
        validateRuntime(proot, true);
    }

    private static void validateRuntime(com.deepseekharness.app.runtime.ProotBootstrap proot, boolean register) throws IOException {
        // 与安装页共用检查项，冷重建不能依赖用户先启动一次 Web 才补齐运行补丁。
        java.util.List<com.deepseekharness.app.util.InstallProbe.Check> checks = com.deepseekharness.app.util.InstallProbe.checks(0);
        com.deepseekharness.app.util.InstallProbe.Results checked = new com.deepseekharness.app.util.InstallProbe.Results(checks);
        String probe = proot.execAndReadWithProot(com.deepseekharness.app.util.InstallProbe.script(checks), 90_000);
        for (String line : probe.split("\\r?\\n")) checked.accept(line);
        for (int step = 2; step <= 6; step++) if (!checked.ok(step))
            throw new IOException(com.deepseekharness.app.util.UiText.text("新环境第 ") + step + com.deepseekharness.app.util.UiText.text(" 步检查失败：") + checked.detail(step));
        String script = "set -e; curl --version; git --version; id -Gn; python3 -c 'import ssl,sqlite3,json,tarfile; assert ssl.OPENSSL_VERSION'; "
                + (register ? "python3 /root/.dsh/register-builtin-plugins.py; " : "")
                + "node -e " + com.deepseekharness.app.util.ShellQuote.arg(
                "(async()=>{const fs=require('node:fs');const p='/usr/local/lib/node_modules/@deepseek-ai/dsh/package.json';"
                + "const pkg=JSON.parse(fs.readFileSync(p,'utf8'));if(pkg.version!=='" + com.deepseekharness.app.util.Constants.DSH_VERSION
                + "'||process.arch!=='arm64'||!process.versions.node.startsWith('24.'))throw Error('运行时版本不符');"
                + "const r=require('node:module').createRequire(p);for(const n of ['sharp','koffi','node-pty','@deepseek-ai/node-addon-system/landlock-run'])r(n);"
                + "const loader=r('@deepseek-ai/cordis-plugin-loader').ModuleLoader.fromInternal();"
                + "if(!loader||typeof loader.import!=='function')throw Error('Node 插件加载器首次初始化失败');"
                + "for(const n of ['device-shell-guide','task-notifier','status-overlay','web-mobile'])await import('file:///root/dsha-'+n+'/lib/index.js');"
                + "await import('file:///root/dsha-app-integration/index.js');"
                + "const q='/root/.dsha-native-check-'+process.pid;const fd=fs.openSync(q,'wx',384);"
                + "try{await r('@deepseek-ai/node-addon-system/flock').tryLockExclusive(fd);}"
                + "finally{fs.closeSync(fd);fs.unlinkSync(q);}})().catch(e=>{console.error(e);process.exitCode=1;});")
                + "; dsh --version; printf '\\nDSHA_RUNTIME_VALIDATED\\n'";
        String output = proot.execAndReadWithProot(script, 90_000);
        if (output == null || !output.contains("\nDSHA_RUNTIME_VALIDATED\n"))
            throw new IOException(com.deepseekharness.app.util.UiText.text("新环境运行校验失败，将保留并回切原环境。\n") + output);
    }

    /** 只删除事务拥有的树；lstat 失败就中止，绝不沿链接清理公开存储。 */
    static void deleteTree(File root) throws IOException {
        try {
            android.system.StructStat info = android.system.Os.lstat(root.getAbsolutePath());
            if (android.system.OsConstants.S_ISDIR(info.st_mode)) {
                // proot 的虚拟挂载占位目录可能是 000；事务私有旧树已停用，可恢复本 UID 的遍历权限。
                // 先 lstat，绝不沿软链 chmod 到公开存储；未知所有者仍保留并报告。
                if ((info.st_mode & 0700) != 0700) {
                    if (info.st_uid != android.os.Process.myUid())
                        throw new IOException(com.deepseekharness.app.util.UiText.text("待清理目录不属于本应用：") + root.getName());
                    android.system.Os.chmod(root.getAbsolutePath(), (info.st_mode & 0777) | 0700);
                }
                File[] children = root.listFiles();
                if (children == null) throw new IOException(com.deepseekharness.app.util.UiText.text("无法读取待清理目录：") + root.getName());
                for (File child : children) deleteTree(child);
            }
            if (!root.delete()) throw new IOException(com.deepseekharness.app.util.UiText.text("无法清理旧文件：") + root.getName());
        } catch (android.system.ErrnoException error) { throw new IOException(com.deepseekharness.app.util.UiText.text("旧文件状态无法确认，已停止清理"), error); }
    }

    /** 仅回收新版已提交且保存过个人文件的事务，历史安全副本不在自动清理范围内。 */
    public static void cleanupCompleted(HarnessController controller) throws IOException {
        MaintenanceTransaction.cleanupCompleted(controller.proot().getRootfsDir().getParentFile().getParentFile(),
                EnvironmentMaintenance::deleteTree);
        com.deepseekharness.app.util.RuntimeUpdateTransaction.cleanupCompleted(
                controller.proot().getRootfsDir().getParentFile().getParentFile(), EnvironmentMaintenance::deleteTree);
    }

    public static String recover(HarnessController controller) throws Exception {
        if (!BackupManager.isDataTaskOwner()) throw new IOException(com.deepseekharness.app.util.UiText.text("维护回滚必须经过停止屏障与全局数据任务锁"));
        File files = controller.proot().getRootfsDir().getParentFile().getParentFile();
        com.deepseekharness.app.util.RuntimeUpdateTransaction runtime = com.deepseekharness.app.util.RuntimeUpdateTransaction.pending(files);
        if (runtime != null) {
            if (RuntimeTasks.hasOtherTasks()) throw new IOException(com.deepseekharness.app.util.UiText.text("后台进程尚未退出，请稍后恢复运行时更新"));
            runtime.rollback();
            return com.deepseekharness.app.util.UiText.text("中断的运行时更新已回切；个人目录、会话和配置保持原位。\n") + runtime.directory();
        }
        MaintenanceTransaction pending = MaintenanceTransaction.pending(files);
        if (pending == null) return com.deepseekharness.app.util.UiText.text("没有未完成的环境维护。");
        if (RuntimeTasks.hasOtherTasks()) throw new IOException(com.deepseekharness.app.util.UiText.text("后台进程尚未退出，维护日志及新旧环境均保留。请等待进程退出后再恢复中断维护。"));
        pending.rollback();
        return com.deepseekharness.app.util.UiText.text("中断的维护已回滚；安全备份、旧环境及失败的新环境均已保留。\n") + pending.directory().getAbsolutePath();
    }
}
