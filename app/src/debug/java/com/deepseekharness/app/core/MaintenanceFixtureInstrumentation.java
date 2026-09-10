package com.deepseekharness.app.core;

import android.app.Activity;
import android.app.Instrumentation;
import android.content.Context;
import android.content.ContextWrapper;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.net.Uri;
import com.deepseekharness.app.BackupManager;
import com.deepseekharness.app.util.BackupTaskState;
import com.deepseekharness.app.util.Compat;
import com.deepseekharness.app.util.MaintenanceTransaction;
import com.deepseekharness.app.util.ShellQuote;
import java.io.File;
import java.io.IOException;
import java.util.UUID;
import java.util.function.Consumer;

/** 主 APK 的 debug 自插桩。只操作本类创建的 UUID fixture，绝不停止用户 Web 或清理用户环境。 */
public final class MaintenanceFixtureInstrumentation extends Instrumentation {
    private Bundle args;
    private int assertions;
    private FixtureContext fixture;

    @Override public void onCreate(Bundle arguments) { args = arguments == null ? new Bundle() : arguments; start(); }

    @Override public void onStart() {
        Bundle result = new Bundle();
        try {
            if (!com.deepseekharness.app.BuildConfig.DEBUG) throw new IOException("仅允许 debug fixture");
            if (BackupManager.isEnvironmentTaskBusy()) throw new IOException("有真实环境任务运行，请完成后再执行 fixture");
            if (RuntimeTasks.isBusy()) throw new IOException("有实际终端或后台工作运行，请结束后再执行隔离 fixture");
            fixture = new FixtureContext(getTargetContext());
            if ("cleanup".equals(args.getString("case"))) {
                File old = new File(fixture.base, "owned-old-tree");
                File empty = new File(old, "data/user/0/fixture/.l2s");
                File locked = new File(old, "locked");
                File outside = new File(fixture.base, "outside");
                check(empty.mkdirs() && locked.mkdirs() && outside.mkdirs(), "创建清理夹具");
                Compat.write(new File(locked, "content"), "old-only");
                Compat.write(new File(outside, "sentinel"), "keep");
                android.system.Os.symlink(outside.getAbsolutePath(), new File(old, "external-link").getAbsolutePath());
                android.system.Os.chmod(empty.getAbsolutePath(), 0000);
                android.system.Os.chmod(locked.getAbsolutePath(), 0000);
                EnvironmentMaintenance.deleteTree(old);
                check(!old.exists(), "000 挂载占位和含文件目录均已清理");
                check("keep".equals(Compat.readAll(new File(outside, "sentinel"))), "不沿软链清理外部目录");
                EnvironmentMaintenance.deleteTree(fixture.base);
                result.putString("status", "PASS"); result.putInt("assertions", assertions);
                finish(Activity.RESULT_OK, result); return;
            }
            FixtureHarness harness = new FixtureHarness(fixture);
            result.putString("fixture", fixture.base.getAbsolutePath());
            check(!harness.proot().getRootfsDir().getCanonicalPath().startsWith(
                    new File(getTargetContext().getFilesDir(), "linux").getCanonicalPath() + File.separator), "fixture 不得位于用户 linux 中");
            if ("stop".equals(args.getString("case", "all"))) {
                stopBoundary(harness);
                result.putString("status", "PASS"); result.putInt("assertions", assertions);
                result.putString("stream", "PASS " + assertions + " stop assertions；fixture 原样保留：" + fixture.base);
                finish(Activity.RESULT_OK, result); return;
            }
            if ("runtime".equals(args.getString("case", "all"))) {
                runtimeBoundary(harness);
                result.putString("status", "PASS"); result.putInt("assertions", assertions);
                result.putString("stream", "PASS " + assertions + " runtime assertions；fixture 原样保留：" + fixture.base);
                finish(Activity.RESULT_OK, result); return;
            }
            if (fixture.base.getUsableSpace() < 3L * 1024 * 1024 * 1024)
                throw new IOException("fixture 至少需要 3 GiB 可用空间，已中止；未清理任何文件");
            freshInitialization(harness);
            if ("fresh".equals(args.getString("case", "all"))) {
                result.putString("status", "PASS"); result.putInt("assertions", assertions);
                result.putString("stream", "PASS " + assertions + " fresh assertions；fixture 原样保留：" + fixture.base);
                finish(Activity.RESULT_OK, result); return;
            }
            runtimeBoundary(harness);
            python(harness, SEED);
            check(new File(harness.proot().getRootfsDir(), "root/.dsh/attachments/private.txt").isFile(), "私有用户数据 fixture 已建立");
            retainedRollback(harness);
            if ("retained".equals(args.getString("case", "all"))) {
                result.putString("status", "PASS"); result.putInt("assertions", assertions);
                result.putString("stream", "PASS " + assertions + " retained assertions；fixture 原样保留：" + fixture.base);
                finish(Activity.RESULT_OK, result); return;
            }

            report("注入不可备份的 fixture 链接，确认备份失败不移走旧环境");
            python(harness, "from pathlib import Path\nPath('/root/.dsh/fixture-broken-link').symlink_to('/root/fixture-missing-target')");
            boolean backupFailed = false;
            try { BackupManager.runDataTask(harness, () -> EnvironmentMaintenance.rebuild(harness, this::report)); }
            catch (IOException expected) { backupFailed = true; }
            check(backupFailed && harness.isEnvironmentReady(), "备份失败保留旧环境和就绪标记");
            check(find(new File(fixture.getFilesDir(), "maintenance"), "previous-linux/.offline-extracted") == null,
                    "备份失败未重命名环境");
            // 仅移除本测试刚建立的 fixture 链接，不清理任何实际用户文件。
            python(harness, "from pathlib import Path\nPath('/root/.dsh/fixture-broken-link').unlink()");

            // 故障注入发生在真实备份成功且旧环境已重命名之后。
            report("注入解压失败，验证原环境回切和安全副本保留");
            boolean failed = false;
            try {
                BackupManager.runDataTask(harness, () -> EnvironmentMaintenance.rebuild(harness, this::report, progress -> {
                    File partial = new File(harness.proot().getRootfsDir(), "root/fixture-partial.txt");
                    if (!partial.getParentFile().mkdirs()) throw new IOException("无法创建 partial fixture");
                    Compat.write(partial, "partial-fixture");
                    throw new IOException("fixture 注入：解压失败");
                }));
            } catch (IOException expected) { failed = true; }
            check(failed, "注入失败不得报告成功");
            check(!BackupManager.hasPendingMaintenance(harness), "失败已回切，无悬挂维护日志");
            check("fixture-private\n".equals(Compat.readAll(new File(harness.proot().getRootfsDir(), "root/.dsh/attachments/private.txt"))), "回切后私有数据逐字一致");
            check(find(new File(fixture.getFilesDir(), "maintenance"), "failed-linux/ubuntu/root/fixture-partial.txt") != null, "失败的新环境未删除");
            File safety = find(new File(fixture.getFilesDir(), "maintenance"), "safety.tar.gz");
            check(safety != null && safety.length() > 0, "完整安全归档保留在 linux 外");

            report("模拟切换后进程退出，使用磁盘日志恢复原环境");
            boolean interrupted = false;
            try {
                BackupManager.runDataTask(harness, () -> EnvironmentMaintenance.rebuild(harness, this::report, progress -> {
                    File partial = new File(harness.proot().getRootfsDir(), "root/fixture-interrupted.txt");
                    if (!partial.getParentFile().mkdirs()) throw new IOException("无法创建中断 fixture");
                    Compat.write(partial, "interrupted-fixture");
                    // Error 绕过维护的 Exception 自动回切，留下与进程退出相同的磁盘切换现场。
                    throw new FixtureExit();
                }));
            } catch (FixtureExit expected) { interrupted = true; }
            check(interrupted && BackupManager.hasPendingMaintenance(harness), "磁盘日志识别未完成维护");
            BackupManager.recoverMaintenanceBeforeStart(harness);
            check(!BackupManager.hasPendingMaintenance(harness), "重新读取日志后回切完成");
            check("fixture-private\n".equals(Compat.readAll(new File(harness.proot().getRootfsDir(), "root/.dsh/attachments/private.txt"))),
                    "中断恢复后私有用户数据一致");

            report("验证快照嵌套 Lease/RuntimeTasks，不停止 Web、不释放调用方凭据");
            int snapshotStops = harness.stops;
            boolean runtimeBefore = RuntimeTasks.isBusy();
            try (com.deepseekharness.app.util.EnvironmentTaskGate.Lease outer =
                         com.deepseekharness.app.util.EnvironmentTaskGate.tryAcquire("fixture 快照调用方")) {
                check(outer != null, "快照调用方取得 Lease");
                outer.run(() -> {
                    try (RuntimeTasks caller = RuntimeTasks.begin()) {
                        String nested = BackupManager.runSnapshotTask(harness,
                                () -> BackupManager.runSnapshotTask(harness, () -> "nested-ok"));
                        check("nested-ok".equals(nested), "嵌套快照重入不等待自身");
                        BackupManager.runSnapshotTask(harness, () -> {
                            boolean denied = false;
                            try { BackupManager.runDataTask(harness, () -> "不得在快照锁内停止"); }
                            catch (IOException expected) { denied = true; }
                            check(denied && harness.stops == snapshotStops, "快照锁内升级为停止维护立即拒绝，未调用 stop");
                            return null;
                        });
                        check(com.deepseekharness.app.util.EnvironmentTaskGate.ownsCurrentThread(), "嵌套调用未释放外层 Lease");
                        check(RuntimeTasks.isBusy(), "嵌套调用未释放调用方 RuntimeTasks");
                    }
                    return null;
                });
            }
            check(harness.stops == snapshotStops, "快照不调用停止 Web");
            if (!runtimeBefore) check(!RuntimeTasks.isBusy(), "快照异常路径没有遗留 RuntimeTasks");
            java.util.concurrent.atomic.AtomicBoolean uiRejected = new java.util.concurrent.atomic.AtomicBoolean();
            runOnMainSync(() -> {
                try { BackupManager.stopWebForMaintenance(harness); }
                catch (Exception expected) { uiRejected.set(true); }
            });
            check(uiRejected.get() && harness.stops == snapshotStops, "UI 线程停止等待入口立即拒绝且不操作 Web");

            if (!"failure".equals(args.getString("case", "all"))) {
                report("运行真实维护全链：校验备份 → 保留旧环境 → 真实 assets → 恢复");
                BackupTask task = new BackupTask(fixture, harness);
                java.util.concurrent.CountDownLatch stopGate = new java.util.concurrent.CountDownLatch(1);
                harness.stopGate = stopGate;
                int stopsBefore = harness.stops;
                try {
                    check(task.rebuild(), "维护任务可提交");
                    long stopDeadline = android.os.SystemClock.elapsedRealtime() + 5_000;
                    while (harness.stops == stopsBefore && android.os.SystemClock.elapsedRealtime() < stopDeadline) Thread.sleep(20);
                    check(harness.stops > stopsBefore && task.busy(), "任务进入等待停止屏障");
                    check(new File(harness.proot().getRootfsDir(), "root/.dsh/attachments/private.txt").exists(), "停止完成前原环境仍在原位");
                    check(!BackupManager.hasPendingMaintenance(harness), "停止完成前未进入环境切换事务");
                } finally { stopGate.countDown(); }
                long id = task.snapshot().id;
                check(!task.rebuild() && !task.resetConfig(), "维护过程中拒绝重复维护和重置");
                await(task, null);
                check(task.snapshot().status == BackupTaskState.Status.SUCCEEDED, "维护成功：" + task.snapshot().detail);
                check(task.snapshot().id == id, "任务编号跨后台执行保持稳定");
                python(harness, VERIFY_REBUILT);
                check(!BackupManager.hasPendingMaintenance(harness), "完成后无未提交维护");
                BackupTask recreated = new BackupTask(fixture, harness);
                check(recreated.snapshot().id == id && recreated.snapshot().status == BackupTaskState.Status.SUCCEEDED,
                        "新任务宿主从隔离偏好恢复结果，不重跑");

                report("验证预览持锁、旧页面确认拒绝、取消和真实恢复");
                int stopsBeforePreview = harness.stops;
                boolean runtimeBeforePreview = RuntimeTasks.isBusy();
                check(task.prepareRestore(Uri.fromFile(safety)), "读取 fixture 安全备份");
                await(task, BackupTaskState.Status.PREVIEW);
                check(harness.stops == stopsBeforePreview, "预检不停止 Web");
                if (!runtimeBeforePreview) check(!RuntimeTasks.isBusy(), "等待用户预览确认不占用 RuntimeTasks");
                long preview = task.snapshot().id;
                check(!task.resetConfig() && !task.rebuild(), "恢复预览期间重置与维护互斥");
                boolean excluded = false;
                try { BackupManager.runDataTask(harness, () -> "不得执行"); }
                catch (IOException expected) { excluded = true; }
                check(excluded, "第二数据任务无法进入全局锁");
                check(!task.decide(preview - 1, true), "旧页面编号不能确认本次恢复");
                check(task.decide(preview, false), "取消当前预览");
                await(task, null);
                check(task.snapshot().status == BackupTaskState.Status.CANCELLED, "取消结果保留");
                check(harness.stops == stopsBeforePreview, "取消预览不停止 Web");
                python(harness, VERIFY_REBUILT);
                check(task.prepareRestore(Uri.fromFile(safety)), "再次准备 fixture 恢复");
                await(task, BackupTaskState.Status.PREVIEW);
                check(harness.stops == stopsBeforePreview, "再次预检仍不停止 Web");
                check(task.decide(task.snapshot().id, true), "明确确认后执行恢复");
                await(task, null);
                check(task.snapshot().status == BackupTaskState.Status.SUCCEEDED, "真实恢复完成：" + task.snapshot().detail);
                check(harness.stops > stopsBeforePreview, "最终确认恢复才执行停止屏障");
                python(harness, VERIFY_REBUILT);
            }
            check(harness.stops > 0, "维护经过停止屏障（fixture 替身，不操作用户 Web）");
            result.putString("status", "PASS"); result.putInt("assertions", assertions);
            result.putString("stream", "PASS " + assertions + " assertions；fixture 原样保留：" + fixture.base);
            finish(Activity.RESULT_OK, result);
        } catch (Throwable error) {
            result.putString("status", "FAIL"); result.putInt("assertions", assertions);
            result.putString("stream", "FAIL：" + com.deepseekharness.app.util.SensitiveData.redact(String.valueOf(error)));
            finish(Activity.RESULT_CANCELED, result);
        }
    }
    private void check(boolean condition, String description) throws IOException {
        assertions++; if (!condition) throw new IOException("断言失败：" + description);
    }
    private void report(String value) {
        Bundle state = new Bundle(); state.putString("stream", value + "\n"); sendStatus(0, state);
    }
    /** 真实备份和目录切换，故障进程仅为内存替身；不创建或终止任何真实进程。 */
    private void retainedRollback(FixtureHarness harness) throws Exception {
        report("验证同线程进程 detach 后禁止自动 rename，退出后可恢复原环境");
        RetainedFixtureProcess process = new RetainedFixtureProcess();
        File files = harness.proot().getRootfsDir().getParentFile().getParentFile();
        File partial = new File(harness.proot().getRootfsDir(), "root/fixture-retained-process.txt");
        boolean refused = false;
        try {
            try {
                BackupManager.runDataTask(harness, () -> EnvironmentMaintenance.rebuild(harness, this::report, progress -> {
                    if (!partial.getParentFile().mkdirs()) throw new IOException("无法创建进程保留 fixture");
                    Compat.write(partial, "partial-still-owned");
                    try (RuntimeTasks work = RuntimeTasks.begin()) {
                        check(!RuntimeTasks.hasOtherTasks(), "同线程同步工作在 detach 前不误判为外部任务");
                        work.retainUntilExit(process);
                        work.retainUntilExit(process);
                        check(RuntimeTasks.hasOtherTasks(), "同线程原 token detach 后失去维护豁免");
                    }
                    throw new IOException("fixture 注入：操作失败但子进程尚未退出");
                }));
            } catch (IOException expected) {
                refused = expected.getMessage().contains("后台进程尚未退出") && expected.getMessage().contains("恢复中断维护");
            }
            check(refused, "失败结果明确要求等待进程退出后恢复");
            MaintenanceTransaction pending = MaintenanceTransaction.pending(files);
            check(pending != null, "失败后维护日志仍处于待恢复状态");
            File journal = new File(pending.directory(), "intent.properties");
            byte[] journalBefore = Compat.readAllBytes(journal);
            File original = new File(pending.directory(), "previous-linux/ubuntu/root/.dsh/attachments/private.txt");
            check("fixture-private\n".equals(Compat.readAll(original)), "旧环境保留在 previous-linux，私有数据完整");
            check("partial-still-owned".equals(Compat.readAll(partial))
                    && !new File(pending.directory(), "failed-linux").exists()
                    && !new File(pending.directory(), "rolled-back").exists(), "当前新环境未 rename，未伪报已经回切");
            check(RuntimeTasks.hasOtherTasks() && !BackupManager.isEnvironmentTaskBusy(), "操作返回后 Gate 释放但 detached token 继续保护环境");

            boolean blocked = false;
            try { BackupManager.recoverMaintenanceBeforeStart(harness); }
            catch (IOException expected) { blocked = true; }
            check(blocked && java.util.Arrays.equals(journalBefore, Compat.readAllBytes(journal)) && partial.isFile() && original.isFile(),
                    "存活期间中断恢复入口也被 fence 拒绝，日志及新旧目录不变");
            check(process.observed.await(3, java.util.concurrent.TimeUnit.SECONDS), "退出监听已观察同一个 fakeProcess");
            int polls = process.polls.get();
            process.waiter.get().interrupt();
            long deadline = android.os.SystemClock.elapsedRealtime() + 3_000;
            while (process.polls.get() <= polls && android.os.SystemClock.elapsedRealtime() < deadline) Thread.sleep(20);
            check(process.polls.get() > polls && RuntimeTasks.hasOtherTasks() && !process.exited,
                    "中断监听不提前释放仍存活进程的 token");
            check(process.destroyCalls.get() == 0, "监听与回切拒绝均未销毁进程");
        } finally {
            process.finish();
            long deadline = android.os.SystemClock.elapsedRealtime() + 5_000;
            while (RuntimeTasks.hasOtherTasks() && android.os.SystemClock.elapsedRealtime() < deadline) Thread.sleep(20);
        }
        check(!RuntimeTasks.hasOtherTasks(), "fakeProcess 确实退出后才释放 detached token");
        try (RuntimeTasks caller = RuntimeTasks.begin()) {
            String result = BackupManager.runDataTask(harness, () -> EnvironmentMaintenance.recover(harness));
            check(result.contains("已回滚") && RuntimeTasks.isBusy() && !RuntimeTasks.hasOtherTasks(),
                    "退出后允许同线程外层同步作用域中恢复，且不释放调用方 token");
        }
        check(!BackupManager.hasPendingMaintenance(harness) && !RuntimeTasks.isBusy(), "退出后恢复提交，任务保护完整收尾");
        check("fixture-private\n".equals(Compat.readAll(new File(harness.proot().getRootfsDir(), "root/.dsh/attachments/private.txt"))),
                "恢复后原私有数据回到活动环境");
        check(process.destroyCalls.get() == 0, "全程只观察指定 fakeProcess 的退出，没有调用 destroy");
    }
    /** 只创建一个同 UID 的 sleep；被测停止入口只读本次 fixture PID，清理只针对该 Process 对象。 */
    private void stopBoundary(FixtureHarness harness) throws Exception {
        report("验证原生停止：空环境、无效 PID、软链接、同 UID 的非 Web 子进程");
        java.util.List<String> failures = new java.util.ArrayList<>();
        File linux = harness.proot().getRootfsDir().getParentFile();
        File root = new File(harness.proot().getRootfsDir(), "root");
        com.deepseekharness.app.runtime.WebProcessManager stop =
                new com.deepseekharness.app.runtime.WebProcessManager(harness.proot());
        check(!linux.exists(), "stop fixture 初始不存在 linux");
        expectStop(failures, stop.stop().isEmpty() && !linux.exists(), "空目录 stop 不创建 linux/rootfs");
        if (!root.isDirectory() && !root.mkdirs()) throw new IOException("无法建立 stop fixture");
        File pidFile = new File(root, ".dsha-web.pid");
        File sentinel = new File(root, ".dsha-stopped");
        File marker = new File(root, "stop-fixture-user-marker");
        Compat.write(marker, "fixture-original");

        for (String value : new String[]{"", "not-a-pid", "1", "0", "-1", "22 23", "2;echo injected", "99999999999999999999999999999999999"}) {
            // 先验证安全解析器拒绝，绝不把可解析的进程组/不明 PID 交给系统停止接口。
            check(com.deepseekharness.app.util.WebProcSel.parsePid(value) < 0, "无效 PID 样本未被解析为进程：" + value);
            Compat.write(pidFile, value);
            String reason = stop.stop();
            expectStop(failures, !reason.isEmpty(), "无效 PID 应明确拒绝：" + value);
        }
        removeStopFixtureEntry(pidFile);
        File target = new File(root, "stop-fixture-pid-target");
        Compat.write(target, "1\n");
        Compat.symlink(target.getAbsolutePath(), pidFile);
        expectStop(failures, stop.stop().contains("PID 文件异常"), "存在目标的 PID 软链接应明确拒绝");
        expectStop(failures, "1\n".equals(Compat.readAll(target)), "PID 软链接目标内容未改变");
        removeStopFixtureEntry(pidFile);
        Compat.symlink(new File(root, "stop-fixture-missing-target").getAbsolutePath(), pidFile);
        expectStop(failures, stop.stop().contains("PID 文件异常"), "断开的 PID 软链接应明确拒绝，不能当作无 PID 成功返回");
        removeStopFixtureEntry(pidFile);

        removeStopFixtureEntry(sentinel);
        File sentinelTarget = new File(root, "stop-fixture-sentinel-target");
        Compat.write(sentinelTarget, "unchanged");
        Compat.symlink(sentinelTarget.getAbsolutePath(), sentinel);
        expectStop(failures, stop.stop().contains("停止标记"), "停止哨兵软链接应明确拒绝");
        expectStop(failures, "unchanged".equals(Compat.readAll(sentinelTarget)), "哨兵软链接目标未被写入");
        removeStopFixtureEntry(sentinel);

        expectStop(failures, !com.deepseekharness.app.util.WebProcSel.looksLikeWeb(
                "/app/libproot.so -r /fixture /usr/local/bin/node /usr/local/lib/node_modules/@deepseek-ai/dsh/lib/bin.js web"),
                "包含完整 dsh 参数的 proot launcher 仍被排除");
        expectStop(failures, !com.deepseekharness.app.util.WebProcSel.looksLikeWeb(
                "/app/libproroot.so -r /fixture /usr/local/bin/node /usr/local/lib/node_modules/@deepseek-ai/dsh/lib/bin.js web"),
                "包含完整 dsh 参数的 proroot launcher 仍被排除");
        expectStop(failures, !com.deepseekharness.app.util.WebProcSel.looksLikeWeb("node /root/custom-service.js"),
                "普通用户 Node argv 不匹配 dsh Web");

        Process sleep = null;
        try (RuntimeTasks processLifetime = RuntimeTasks.beginDetached()) {
            try {
                sleep = new ProcessBuilder("/system/bin/sleep", "120").redirectErrorStream(true).start();
                int pid = com.deepseekharness.app.util.ProcessIdentity.androidPid(sleep.getClass().getName(), sleep.toString());
                check(pid > 1 && Compat.isAlive(sleep), "只使用本次创建的存活 sleep Process PID");
                File proc = new File("/proc/" + pid);
                com.deepseekharness.app.util.ProcessIdentity identity = com.deepseekharness.app.util.ProcessIdentity.fromStat(
                        Compat.readAll(new File(proc, "stat")), pid, android.os.Process.myPid());
                check(identity != null && android.system.Os.stat(proc.getAbsolutePath()).st_uid == android.os.Process.myUid(),
                        "核验 sleep 是当前 App 的同 UID 直接子进程");
                String cmdline = new String(Compat.readAllBytes(new File(proc, "cmdline")), java.nio.charset.StandardCharsets.UTF_8).replace('\0', ' ');
                check(cmdline.contains("sleep") && !com.deepseekharness.app.util.WebProcSel.looksLikeWeb(cmdline), "实际 sleep argv 不属于 Web");
                Compat.write(pidFile, Integer.toString(pid));
                check(Compat.isAlive(sleep) && identity.sameProcess(com.deepseekharness.app.util.ProcessIdentity.fromStat(
                        Compat.readAll(new File(proc, "stat")), pid, android.os.Process.myPid())), "调用 stop 前再次核验本次子进程身份");
                String reason = stop.stop();
                Thread.sleep(100);
                expectStop(failures, reason.isEmpty() && !pidFile.exists(), "同 UID 非 Web 的过期编号应被隔离：" + reason);
                expectStop(failures, Compat.isAlive(sleep), "被测 stop 返回后专属 sleep 仍存活，未被误杀");
            } finally {
                // 必须先清理自己创建的进程；不扫描进程、不使用 pid 文件或名字进行 cleanup。
                try {
                    if (sleep != null) {
                        try {
                            sleep.destroy();
                            if (!Compat.waitFor(sleep, 2000)) Compat.destroy(sleep);
                            expectStop(failures, !Compat.isAlive(sleep), "仅本次创建的 Process 已清理并退出");
                        } finally {
                            try { sleep.getOutputStream().close(); } catch (Exception ignored) { }
                            try { sleep.getInputStream().close(); } catch (Exception ignored) { }
                            try { sleep.getErrorStream().close(); } catch (Exception ignored) { }
                        }
                    }
                } finally { removeStopFixtureEntry(pidFile); }
            }
        }
        expectStop(failures, "fixture-original".equals(Compat.readAll(marker)), "所有停止检查后 fixture 用户文件保持原样");
        expectStop(failures, !RuntimeTasks.isBusy() && !BackupManager.isEnvironmentTaskBusy(), "stop fixture 未遗留任务锁");
        if (!failures.isEmpty()) throw new IOException("原生停止边界未通过：\n" + String.join("\n", failures));
    }
    private void expectStop(java.util.List<String> failures, boolean condition, String description) {
        assertions++;
        if (!condition) { failures.add(description); report("未通过：" + description); }
    }
    private void removeStopFixtureEntry(File file) throws IOException {
        // 只删除本次隔离 stop 用例的两个入口文件/链接本身，绝不跟随链接，也不递归清理目录。
        File expected = new File(fixture.getFilesDir(), "linux/ubuntu/root").getCanonicalFile();
        if (!expected.getPath().startsWith(fixture.getFilesDir().getCanonicalPath() + File.separator)
                || !expected.equals(file.getParentFile().getCanonicalFile())
                || !(file.getName().equals(".dsha-web.pid") || file.getName().equals(".dsha-stopped")))
            throw new IOException("拒绝清理非 stop fixture 入口");
        if ((file.exists() || Compat.isSymbolicLink(file)) && !file.delete()) throw new IOException("无法清理本次 fixture 入口");
    }
    private void freshInitialization(FixtureHarness harness) throws Exception {
        report("验证 linux 不存在时真实停止无副作用，再经完整任务链初始化");
        File linux = harness.proot().getRootfsDir().getParentFile();
        check(!linux.exists(), "首次安装 fixture 最初不存在 linux");
        String stopped = new com.deepseekharness.app.runtime.WebProcessManager(harness.proot()).stop();
        check(stopped.isEmpty() && !linux.exists(), "真实 WebProcessManager.stop 正常返回且不创建 linux/rootfs");
        BackupTask task = new BackupTask(fixture, harness);
        int before = harness.stops;
        check(task.rebuild(), "首次安装经 BackupTask 提交");
        await(task, null);
        check(task.snapshot().status == BackupTaskState.Status.SUCCEEDED, "runDataTask→rebuild 首次安装完成：" + task.snapshot().detail);
        check(harness.stops > before, "首次安装完整链经过真实 fixture 停止入口");
        check(harness.isEnvironmentReady(), "真实 assets 初始化产物校验通过");
        check(find(new File(fixture.getFilesDir(), "maintenance"), "safety.tar.gz") == null,
                "首次安装未误判为旧环境并尝试备份");
        check(!BackupManager.hasPendingMaintenance(harness) && !BackupManager.isEnvironmentTaskBusy() && !RuntimeTasks.isBusy(),
                "首次安装完成后日志已提交、所有任务锁释放");
    }
    /** 不解压 assets，不启动真实进程：仅用专属文件和真实 RuntimeTasks 验证维护准入边界。 */
    private void runtimeBoundary(FixtureHarness harness) throws Exception {
        report("验证已有后台任务/异步终端阻止维护，同线程同步嵌套兼容");
        File marker = new File(harness.proot().getRootfsDir(), "root/runtime-fixture-marker");
        if (!marker.getParentFile().isDirectory() && !marker.getParentFile().mkdirs()) throw new IOException("无法创建运行边界 fixture");
        Compat.write(marker, "fixture-original");
        java.util.concurrent.CountDownLatch ready = new java.util.concurrent.CountDownLatch(1);
        java.util.concurrent.CountDownLatch release = new java.util.concurrent.CountDownLatch(1);
        Thread oldTask = new Thread(() -> {
            try (RuntimeTasks work = RuntimeTasks.begin()) { ready.countDown(); release.await(); }
            catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        }, "fixture-existing-runtime");
        oldTask.start();
        try {
            check(ready.await(3, java.util.concurrent.TimeUnit.SECONDS), "已有后台任务进入 RuntimeTasks");
            java.util.concurrent.atomic.AtomicBoolean entered = new java.util.concurrent.atomic.AtomicBoolean();
            boolean denied = false;
            try { BackupManager.runDataTask(harness, () -> { entered.set(true); return null; }); }
            catch (IOException expected) { denied = true; }
            check(denied && !entered.get(), "存在其他线程任务时停止后拒绝进入维护回调");
            check(RuntimeTasks.isBusy() && oldTask.isAlive(), "拒绝维护未释放或结束原任务");
            check("fixture-original".equals(Compat.readAll(marker)), "拒绝时原环境文件保持原位和原内容");
            check(!BackupManager.isEnvironmentTaskBusy() && !BackupManager.hasPendingMaintenance(harness), "拒绝后没有残留 Gate 或维护事务");
        } finally { release.countDown(); oldTask.join(5_000); }
        check(!oldTask.isAlive(), "专属旧任务正常结束");

        try (RuntimeTasks terminal = RuntimeTasks.beginDetached()) {
            boolean denied = false;
            try { BackupManager.runDataTask(harness, () -> "不得进入"); }
            catch (IOException expected) { denied = true; }
            check(denied && RuntimeTasks.isBusy(), "同线程创建的异步终端仍阻止维护，不能伪装为嵌套作用域");
        }
        try (RuntimeTasks caller = RuntimeTasks.begin()) {
            String result = BackupManager.runDataTask(harness, () -> {
                try (RuntimeTasks nested = RuntimeTasks.begin()) { check(RuntimeTasks.isBusy(), "维护内部同步工作可正常嵌套"); }
                java.util.concurrent.FutureTask<Boolean> newcomer = new java.util.concurrent.FutureTask<>(() -> {
                    try (RuntimeTasks unexpected = RuntimeTasks.begin()) { return false; }
                    catch (IllegalStateException expected) { return true; }
                });
                new Thread(newcomer, "fixture-runtime-newcomer").start();
                check(newcomer.get(3, java.util.concurrent.TimeUnit.SECONDS), "维护已进入后新的外部任务立即被拒绝");
                return "nested-ok";
            });
            check("nested-ok".equals(result) && RuntimeTasks.isBusy() && !RuntimeTasks.hasOtherTasks(), "允许同线程上层同步作用域，且不释放调用方工作锁");
        }
        check(!RuntimeTasks.isBusy(), "所有专属 RuntimeTasks 均正确释放");

        report("验证 Node PID 消失但 launcher 尚存时，不能进入维护回调");
        harness.liveWebProcess = true;
        int before = harness.launcherChecks.get();
        java.util.concurrent.atomic.AtomicBoolean entered = new java.util.concurrent.atomic.AtomicBoolean();
        java.util.concurrent.FutureTask<String> waiting = new java.util.concurrent.FutureTask<>(() ->
                BackupManager.runDataTask(harness, () -> { entered.set(true); return "launcher-gone"; }));
        new Thread(waiting, "fixture-launcher-wait").start();
        try {
            long deadline = android.os.SystemClock.elapsedRealtime() + 5_000;
            while (harness.launcherChecks.get() == before && android.os.SystemClock.elapsedRealtime() < deadline) Thread.sleep(20);
            check(harness.launcherChecks.get() > before && !entered.get() && !waiting.isDone(), "launcher 存活时仍等待，未进入维护");
            check("fixture-original".equals(Compat.readAll(marker)), "等待 launcher 期间原环境未移动");
        } finally { harness.liveWebProcess = false; }
        check("launcher-gone".equals(waiting.get(5, java.util.concurrent.TimeUnit.SECONDS)) && entered.get(), "launcher 退出后才放行维护");
        check(!BackupManager.isEnvironmentTaskBusy() && !RuntimeTasks.isBusy(), "launcher 屏障结束后 Gate 与 RuntimeTasks 均空闲");
    }
    private void await(BackupTask task, BackupTaskState.Status wanted) throws Exception {
        long deadline = android.os.SystemClock.elapsedRealtime() + 900_000;
        while (android.os.SystemClock.elapsedRealtime() < deadline) {
            BackupTaskState.Snapshot s = task.snapshot();
            if (wanted == null && !task.busy()) return;
            if (wanted != null && s.status == wanted) return;
            if (wanted != null && !task.busy()) throw new IOException("fixture 任务提前结束：" + s.detail);
            Thread.sleep(100);
        }
        throw new IOException("fixture 任务超时；所有现场已保留");
    }
    private void python(FixtureHarness harness, String script) throws Exception {
        String output = harness.proot().execAndReadWithProot("python3 -B -c " + ShellQuote.arg(script
                + "\nprint('MAINTENANCE_FIXTURE_OK')") + " 2>&1", 120_000);
        check(output != null && output.contains("MAINTENANCE_FIXTURE_OK"), "fixture Python：" + output);
    }
    private File find(File parent, String relative) {
        File[] entries = parent.listFiles();
        if (entries != null) for (File entry : entries) {
            File file = new File(entry, relative); if (file.isFile()) return file;
        }
        return null;
    }
    private static final class FixtureHarness extends HarnessController {
        volatile int stops;
        volatile java.util.concurrent.CountDownLatch stopGate;
        volatile boolean failBackup;
        volatile boolean liveWebProcess;
        final java.util.concurrent.atomic.AtomicInteger launcherChecks = new java.util.concurrent.atomic.AtomicInteger();
        FixtureHarness(Context context) { super(context); }
        @Override public void stopWeb(Consumer<String> status) {
            stops++;
            // 不经 Controller 的全局停止队列；真实停止协作者只看到独立 fixture 且绝不允许带入真实 PID。
            File pid = new File(proot().getRootfsDir(), "root/.dsha-web.pid");
            if (pid.exists() || Compat.isSymbolicLink(pid)) throw new IllegalStateException("fixture 不允许对任何真实 PID 发停止信号");
            try {
                String result = new com.deepseekharness.app.runtime.WebProcessManager(proot()).stop();
                if (!result.isEmpty()) throw new IllegalStateException(result);
            }
            catch (Exception e) { throw new IllegalStateException("fixture 停止失败", e); }
            if (status != null) status.accept("fixture 已停止");
        }
        @Override public boolean isStarting() { return false; }
        @Override public boolean isStopping() { return stopGate != null && stopGate.getCount() > 0; }
        @Override public boolean isWebRunning() { return false; }
        @Override public boolean hasLiveWebProcesses() { launcherChecks.incrementAndGet(); return liveWebProcess; }
        @Override public String readAsset(String name) {
            if (failBackup && "backup-engine.py".equals(name)) return "raise RuntimeError('fixture backup failure before export')\n";
            return super.readAsset(name);
        }
    }
    private static final class FixtureExit extends Error { }
    private static final class RetainedFixtureProcess extends Process {
        volatile boolean exited;
        final java.util.concurrent.atomic.AtomicInteger polls = new java.util.concurrent.atomic.AtomicInteger();
        final java.util.concurrent.atomic.AtomicInteger destroyCalls = new java.util.concurrent.atomic.AtomicInteger();
        final java.util.concurrent.atomic.AtomicReference<Thread> waiter = new java.util.concurrent.atomic.AtomicReference<>();
        final java.util.concurrent.CountDownLatch observed = new java.util.concurrent.CountDownLatch(1);
        void finish() { exited = true; }
        @Override public int exitValue() {
            waiter.compareAndSet(null, Thread.currentThread()); polls.incrementAndGet(); observed.countDown();
            if (!exited) throw new IllegalThreadStateException("fixture process still alive");
            return 0;
        }
        @Override public int waitFor() throws InterruptedException { while (!exited) Thread.sleep(20); return 0; }
        @Override public void destroy() { destroyCalls.incrementAndGet(); exited = true; }
        @Override public java.io.InputStream getInputStream() { return new java.io.ByteArrayInputStream(new byte[0]); }
        @Override public java.io.InputStream getErrorStream() { return new java.io.ByteArrayInputStream(new byte[0]); }
        @Override public java.io.OutputStream getOutputStream() { return new java.io.ByteArrayOutputStream(); }
    }
    private static final class FixtureContext extends ContextWrapper {
        final File base, files, cache;
        final String prefix = "maintenance-fixture-" + UUID.randomUUID() + "-";
        FixtureContext(Context original) throws IOException {
            super(original);
            base = new File(original.getCacheDir(), prefix); files = new File(base, "files"); cache = new File(base, "cache");
            if (!files.mkdirs() || !cache.mkdirs()) throw new IOException("无法建立隔离 fixture");
        }
        @Override public Context getApplicationContext() { return this; }
        @Override public File getFilesDir() { return files; }
        @Override public File getCacheDir() { return cache; }
        @Override public SharedPreferences getSharedPreferences(String name, int mode) {
            // 独立名字空间，只写新建 fixture 偏好，绝不读取或清除 dsha 的真实偏好。
            return super.getSharedPreferences(prefix + name, mode);
        }
    }
    private static final String SEED = """
            import json, os
            from pathlib import Path
            r=Path('/root'); d=r/'.dsh'
            if os.path.lexists(d): d.rename(r/'.maintenance-fixture-bundled')
            (d/'attachments').mkdir(parents=True)
            (d/'attachments/private.txt').write_text('fixture-private\\n')
            (d/'.credentials.yaml').write_text('fixture-credential: fake\\n')
            p=r/'.maintenance-fixture-public'; (p/'sessions/s1').mkdir(parents=True)
            (p/'sessions/s1/session.jsonl').write_text('{"fixture":"session"}\\n')
            (p/'settings.yaml').write_text('model: fixture-model\\n')
            (d/'sessions').symlink_to(p/'sessions', target_is_directory=True)
            (d/'settings.yaml').symlink_to(p/'settings.yaml')
            source=r/'.maintenance-fixture-plugin'; (source/'node_modules/fixture-dep').mkdir(parents=True)
            (source/'package.json').write_text('{"name":"fixture-local","version":"1.0.0"}')
            (source/'index.js').write_text('module.exports="fixture-local";')
            (source/'node_modules/fixture-dep/package.json').write_text('{"name":"fixture-dep","version":"1.0.0"}')
            (d/'profiles/web').mkdir(parents=True)
            (d/'profiles/web/package.json').write_text(json.dumps({'dependencies':{'fixture-local':'link:'+str(source)},'dsh':{'profile':{'bundles':['fixture-local']}}}))
            (d/'storages').mkdir(); (d/'storages/workspace.json').write_text('{"tables":{"workspaces":{}}}')
            """;
    private static final String VERIFY_REBUILT = """
            from pathlib import Path
            d=Path('/root/.dsh')
            assert not (d/'sessions').is_symlink()
            assert not (d/'settings.yaml').is_symlink()
            assert (d/'sessions/s1/session.jsonl').read_text()=='{"fixture":"session"}\\n'
            assert (d/'settings.yaml').read_text()=='model: fixture-model\\n'
            assert (d/'attachments/private.txt').read_text()=='fixture-private\\n'
            assert (d/'.credentials.yaml').read_text()=='fixture-credential: fake\\n'
            plugin=d/'profiles/web/node_modules/fixture-local'
            assert (plugin/'index.js').read_text()=='module.exports="fixture-local";'
            assert (plugin/'node_modules/fixture-dep/package.json').is_file()
            assert not Path('/root/.maintenance-fixture-public').exists()
            assert not Path('/root/.maintenance-fixture-plugin').exists()
            """;
}
