package com.deepseekharness.app.ui;

import android.app.Activity;
import android.app.Instrumentation;
import android.content.Context;
import android.content.ContextWrapper;
import android.content.Intent;
import android.os.Bundle;
import android.os.SystemClock;
import android.view.View;
import android.widget.TextView;
import androidx.fragment.app.Fragment;
import com.deepseekharness.app.BackupManager;
import com.deepseekharness.app.BuildConfig;
import com.deepseekharness.app.R;
import com.deepseekharness.app.core.BackupTask;
import com.deepseekharness.app.core.HarnessController;
import com.deepseekharness.app.core.InstallRepository;
import com.deepseekharness.app.runtime.ProotBootstrap;
import com.deepseekharness.app.util.Compat;
import com.deepseekharness.app.util.Constants;
import com.deepseekharness.app.util.EnvironmentTaskGate;
import com.deepseekharness.app.util.InstallProcess;
import com.deepseekharness.app.util.InstallTask;
import com.deepseekharness.app.util.MaintenanceTransaction;
import java.io.File;
import java.io.FileInputStream;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.security.MessageDigest;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.function.BooleanSupplier;

/** debug 自插桩：真实检查/健康修复/页面重建；失败只用空目录 fixture，取消只终止本测试创建的探测。 */
public final class InstallAuditInstrumentation extends Instrumentation {
    private Bundle args;
    private volatile LayoutPreviewActivity page;
    private InstallRepository repository;
    private volatile boolean ownsTask;
    private File output;
    private final InstallTask report = new InstallTask();
    private int assertions;
    @Override public void onCreate(Bundle arguments) { args = arguments == null ? new Bundle() : arguments; start(); }
    @Override public void callActivityOnCreate(Activity activity, Bundle state) {
        super.callActivityOnCreate(activity, state);
        if (activity instanceof LayoutPreviewActivity && activity.getIntent().getBooleanExtra("install_audit", false)) {
            // 只让本次自测页面在锁屏上显示；不解锁、不写系统设置，窗口销毁后失效。
            activity.getWindow().addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
                    | android.view.WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED
                    | android.view.WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON);
            if (android.os.Build.VERSION.SDK_INT >= 27) {
                activity.setShowWhenLocked(true); activity.setTurnScreenOn(true);
            }
        }
    }
    @Override public void callActivityOnResume(Activity activity) {
        super.callActivityOnResume(activity);
        if (activity instanceof LayoutPreviewActivity && activity.getIntent().getBooleanExtra("install_audit", false))
            page = (LayoutPreviewActivity) activity;
    }
    private void check(boolean condition, String reason) { assertions++; if (!condition) throw new AssertionError(reason); }
    private void ui(Runnable action) {
        Throwable[] failure = {null};
        runOnMainSync(() -> { try { action.run(); } catch (Throwable error) { failure[0] = error; } });
        if (failure[0] != null) throw new AssertionError("界面自测失败：" + failure[0], failure[0]);
    }
    private void note(String message) {
        report.append(message);
        Bundle progress = new Bundle(); progress.putString("stream", message + "\n"); sendStatus(0, progress);
        try { Compat.write(new File(output, "report.txt"), report.snapshot().log); } catch (Exception ignored) { }
    }
    private void await(BooleanSupplier condition, long timeout, String reason) throws Exception {
        long end = SystemClock.elapsedRealtime() + timeout;
        while (!condition.getAsBoolean()) {
            if (SystemClock.elapsedRealtime() >= end) throw new AssertionError(reason);
            Thread.sleep(50);
        }
    }
    private InstallTask.Snapshot completed(InstallRepository task) throws Exception {
        await(() -> !task.snapshot().busy(), 240_000, "安装任务 240 秒内未结束");
        await(() -> !EnvironmentTaskGate.isBusy(), 3000, "任务已结束但全局锁尚未释放");
        if (task == repository) ownsTask = false;
        return task.snapshot();
    }
    private void startTask(boolean repair) throws Exception {
        await(() -> repository.start(repair, 0), 2000, "任务入口仍被占用");
        ownsTask = true;
    }
    private InstallFragment attach(InstallRepository task) {
        InstallFragment fragment = new InstallFragment();
        ui(() -> {
            androidx.fragment.app.FragmentManager manager = page.getSupportFragmentManager();
            Fragment previous = manager.findFragmentById(R.id.fragment_container);
            if (previous != null) manager.beginTransaction().remove(previous).commitNow();
            // 预览宿主先放了一份静态 XML 示例；必须移走它，避免重复 ID 命中未绑定的示例控件。
            android.view.ViewGroup container = page.findViewById(R.id.fragment_container);
            container.removeAllViews();
            manager.beginTransaction().add(R.id.fragment_container, fragment).commitNow();
            check(container.getChildCount() == 1 && container.getChildAt(0) == fragment.getView(),
                    "自测容器必须只包含真实安装 Fragment，不能混入静态示例视图");
            try {
                // 注入独立失败仓库仅用于本 debug 页面，不替换生产单例或修改用户 rootfs。
                Field field = InstallFragment.class.getDeclaredField("repository"); field.setAccessible(true); field.set(fragment, task);
                Field revision = InstallFragment.class.getDeclaredField("shownRevision"); revision.setAccessible(true); revision.setLong(fragment, -1);
            } catch (Exception error) { throw new IllegalStateException(error); }
        });
        return fragment;
    }
    private String uiText(int id) {
        String[] value = {""};
        ui(() -> { TextView view = page.findViewById(id); if (view != null) value[0] = view.getText().toString(); });
        return value[0];
    }

    @Override public void onStart() {
        Bundle result = new Bundle(); Context app = getTargetContext().getApplicationContext();
        output = new File(app.getCacheDir(), "install-audit"); output.mkdirs(); report.start(false, 0);
        try {
            if ("fixture".equals(args.getString("mode"))) {
                fixtureUi(app);
                result.putString("result", "PASS"); result.putInt("assertions", assertions); return;
            }
            check(BuildConfig.DEBUG, "仅允许 debug 包");
            HarnessController controller = HarnessController.get(app);
            check(!BackupTask.get(app).busy() && !BackupManager.isRestoring()
                    && !BackupManager.hasPendingMaintenance(controller), "请先完成备份/维护任务再测试");
            check(controller.isEnvironmentReady(), "需要已安装环境；测试不解压或覆盖用户环境");
            repository = InstallRepository.get(app); check(!repository.snapshot().busy(), "安装任务已运行，请保留现场稍后测试");
            try (EnvironmentTaskGate.Lease other = EnvironmentTaskGate.tryAcquire("安装验收：模拟维护占位")) {
                check(other != null, "无法取得测试占位，请完成其他环境任务");
                check(!repository.start(false, 0), "维护占位期间安装检查未被拒绝");
                check(!repository.snapshot().busy(), "拒绝任务后错误发布了运行状态");
            }
            String runtime = app.getSharedPreferences(Constants.PREFS, 0).getString("container_runtime", "proot");
            long began = SystemClock.elapsedRealtime(); startTask(false);
            InstallTask.Snapshot quick = completed(repository);
            Compat.write(new File(output, "quickcheck.log"), quick.log);
            result.putLong("quickcheck_ms", SystemClock.elapsedRealtime() - began);
            check(quick.outcome == InstallTask.Outcome.SUCCEEDED, "真实六步检查未通过，已保存 quickcheck.log；未进入修复");
            for (InstallTask.Step step : quick.steps) check(step == InstallTask.Step.OK, "六步状态必须全部通过");
            note("PASS 真实六步检查：" + result.getLong("quickcheck_ms") + " ms");
            if (!"quick".equals(args.getString("mode", "full"))) {
                Map<String, String> before = footprint(controller.proot().getRootfsDir());
                began = SystemClock.elapsedRealtime(); startTask(true);
                InstallTask.Snapshot repair = completed(repository);
                Compat.write(new File(output, "healthy-repair.log"), repair.log);
                result.putLong("healthy_repair_ms", SystemClock.elapsedRealtime() - began);
                check(repair.outcome == InstallTask.Outcome.SUCCEEDED, "健康环境修复检查失败");
                check(!repair.log.contains("修复第 ") && !repair.log.contains("正在补齐："), "健康环境意外进入组件修复/下载分支");
                check(before.equals(footprint(controller.proot().getRootfsDir())), "健康检查改变了软件包或运行组件文件");
                check(runtime.equals(app.getSharedPreferences(Constants.PREFS, 0).getString("container_runtime", "proot")), "检查改动了用户运行时偏好");
                note("PASS 健康修复不进入下载分支，APT/运行组件文件未改变");
                processFailures(controller.proot());
                navigation(app);
                failurePage(app);
            }
            result.putString("result", "PASS"); result.putInt("assertions", assertions);
            note("PASS 安装验收 " + assertions + " 条断言");
        } catch (Throwable error) {
            report.append("FAIL " + error); result.putString("failure", report.snapshot().log);
        } finally {
            if (ownsTask && repository != null && repository.snapshot().busy()) {
                repository.cancel();
                try { await(() -> !repository.snapshot().busy(), 30_000, "测试任务仍在安全收尾"); }
                catch (Exception ignored) { }
            }
            if (page != null) ui(() -> page.finish());
            try { Compat.write(new File(output, "report.txt"), report.snapshot().log); } catch (Exception ignored) { }
            result.putString("reports", output.getAbsolutePath());
            finish(result.containsKey("failure") ? Activity.RESULT_CANCELED : Activity.RESULT_OK, result);
        }
    }
    private void fixtureUi(Context app) throws Exception {
        String name=args.getString("name", "rc11-device-20260911");
        check(name.matches("[a-z0-9-]+"), "无效的冷安装夹具名称");
        File base=new File(app.getCacheDir(),"runtime-startup/cold-"+name+"/files");
        check(base.isDirectory(), "需要本轮独立冷安装夹具");
        Context isolated=new ContextWrapper(app) {
            @Override public Context getApplicationContext() { return this; }
            @Override public File getFilesDir() { return base; }
        };
        Constructor<InstallRepository> ctor=InstallRepository.class.getDeclaredConstructor(Context.class);
        ctor.setAccessible(true);repository=ctor.newInstance(isolated);
        for(String command:new String[]{"input keyevent 224","wm dismiss-keyguard",
                "am start -W -n com.dsh.client/com.deepseekharness.app.ui.MainActivity --ez limited_entry true"}) {
            try(android.os.ParcelFileDescriptor fd=getUiAutomation().executeShellCommand(command);
                java.io.InputStream in=new android.os.ParcelFileDescriptor.AutoCloseInputStream(fd)){while(in.read()!=-1){}}
        }
        page=(LayoutPreviewActivity)startActivitySync(new Intent(app,LayoutPreviewActivity.class)
                .putExtra("scene","fragment_install").putExtra("install_audit",true).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        attach(repository);ui(()->page.findViewById(R.id.install_btn).performClick());ownsTask=true;
        check(completed(repository).outcome==InstallTask.Outcome.SUCCEEDED,"独立冷环境六步检查失败："+repository.snapshot().log);
        for(int id:new int[]{R.id.install_state1,R.id.install_state2,R.id.install_state3,R.id.install_state4,R.id.install_state5,R.id.install_state6})
            await(()->uiText(id).equals("成功"),5000,"成功状态没有显示在对应组件行");
        note("PASS 非调试包：独立冷环境检查全部六项，成功状态逐行可见");
        failurePage(app);
    }

    private void processFailures(ProotBootstrap proot) throws Exception {
        InstallTask task = new InstallTask(); task.start(false, 0);
        Process failed = proot.execRootfsForInstall("printf 'token=AUDIT_FAKE_SECRET\\n'; exit 23");
        int code = InstallProcess.read(failed, 10_000, true, () -> false, task::append, Compat::destroy);
        check(code == 23 && !task.snapshot().log.contains("AUDIT_FAKE_SECRET"), "真实退出码/逐行脱敏失效");
        note("PASS 真 proot 失败退出码 23 与日志脱敏");
        InstallTask cancelled = new InstallTask(); cancelled.start(false, 0);
        Process sleeper = proot.execRootfsForInstall("printf 'AUDIT_STARTED\\n'; sleep 30");
        long began = SystemClock.elapsedRealtime(); boolean stopped = false;
        try {
            InstallProcess.read(sleeper, 10_000, true, cancelled::cancellationRequested, line -> {
                cancelled.append(line); if (line.equals("AUDIT_STARTED")) cancelled.requestCancel();
            }, Compat::destroy);
        } catch (InstallTask.Cancelled expected) { stopped = true; }
        check(stopped && Compat.waitFor(sleeper, 3000), "取消未回收本次 proot 探测");
        check(SystemClock.elapsedRealtime() - began < 10_000, "取消等待了整段 sleep");
        Process safe = proot.execRootfsForInstall("printf 'SAFE_BEGIN\\n'; sleep 1; printf 'SAFE_END\\n'");
        check(InstallProcess.read(safe, 10_000, false, () -> true, task::append, Compat::destroy) == 0
                && task.snapshot().log.contains("SAFE_END"), "安全点模式提前杀死当前操作");
        note("PASS 真实探测取消与安全点等待（只终止测试自身进程）");
    }

    private void navigation(Context app) throws Exception {
        note("正在启动安装自测页面");
        // 某些系统会静默拒绝锁屏后台启动，startActivitySync 此时会无限等待。
        // 仅在本次启动期间借用 instrumentation 的后台启动权限，不改应用授权或系统设置。
        boolean borrowed = android.os.Build.VERSION.SDK_INT >= 29;
        if (borrowed) getUiAutomation().adoptShellPermissionIdentity("android.permission.START_ACTIVITIES_FROM_BACKGROUND");
        try {
            ui(() -> app.startActivity(new Intent(app, LayoutPreviewActivity.class).putExtra("scene", "fragment_install")
                    .putExtra("install_audit", true).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)));
            await(() -> page != null, 15_000, "系统未在 15 秒内恢复自测页面，请检查锁屏或后台启动限制");
        } finally { if (borrowed) getUiAutomation().dropShellPermissionIdentity(); }
        check(page != null, "debug 页面未打开，请确认 LayoutPreviewActivity 已注册");
        InstallFragment original = attach(repository);
        ui(() -> {
            check(repository.start(false, 0), "生命周期测试无法开始检查");
            ownsTask = true;
            check(!repository.start(true, 0), "重复启动未拦截");
            page.getSupportFragmentManager().beginTransaction().replace(R.id.fragment_container, new Fragment()).commitNow();
        });
        check(original.getView() == null, "切页没有销毁安装视图");
        resultWhileDetached();
        attach(repository);
        String retained = repository.snapshot().log;
        LayoutPreviewActivity old = page; ui(old::recreate);
        await(() -> page != old && page != null, 15_000, "Activity 重建没有完成");
        waitForIdleSync(); attach(repository);
        check(InstallRepository.get(app) == repository, "页面重建替换了任务所有者");
        InstallTask.Snapshot done = completed(repository);
        check(done.outcome == InstallTask.Outcome.SUCCEEDED && done.log.contains(retained), "重建丢失任务或日志");
        await(() -> uiText(R.id.install_log).equals(repository.snapshot().log), 5000, "返回安装页没有展示任务结果");
        note("PASS 切页销毁/Activity 重建后任务与详细结果保留");
        ui(() -> { check(repository.start(false, 0), "取消测试启动失败"); ownsTask = true; check(repository.cancel(), "取消请求未接受"); });
        check(completed(repository).outcome == InstallTask.Outcome.CANCELLED, "仓库取消没有完成收尾");
        note("PASS 仓库取消释放忙碌状态");
    }
    private void resultWhileDetached() {
        check(repository.snapshot().busy(), "检查已过快结束，未覆盖运行中切页；请单独重试 full 模式");
        note("运行中切离安装页：仓库仍忙碌");
    }
    private void failurePage(Context app) throws Exception {
        File fixture = new File(output, "missing-environment-" + UUID.randomUUID()); fixture.mkdirs();
        Context isolated = new ContextWrapper(app) {
            @Override public Context getApplicationContext() { return this; }
            @Override public File getFilesDir() { return fixture; }
        };
        Constructor<InstallRepository> constructor = InstallRepository.class.getDeclaredConstructor(Context.class);
        constructor.setAccessible(true); InstallRepository failure = constructor.newInstance(isolated);
        attach(failure);
        ui(() -> page.findViewById(R.id.install_btn).performClick());
        InstallTask.Snapshot state = completed(failure);
        check(state.outcome == InstallTask.Outcome.FAILED && !state.failure.isEmpty(), "缺环境未显示明确失败");
        await(() -> !uiText(R.id.install_error).isEmpty(), 5000, "失败原因未返回安装页面");
        for (int status : new int[]{R.id.install_state1, R.id.install_state2, R.id.install_state3,
                R.id.install_state4, R.id.install_state5, R.id.install_state6}) {
            check(uiText(status).equals("失败"), "组件状态未直接显示在对应行");
        }
        check(!new File(fixture, "linux").exists(), "仅检查创建了 fixture 环境");
        await(() -> failure.start(false, 1), 2000, "失败后任务入口未释放");
        check(completed(failure).outcome == InstallTask.Outcome.FAILED, "失败后重试未正常完成");
        MaintenanceTransaction pending = MaintenanceTransaction.create(fixture);
        pending.begin(true);
        boolean refused = false;
        try {
            new ProotBootstrap(isolated) {
                @Override public void ensureRuntimeFiles() { throw new AssertionError("未完成维护时进入了资产写入"); }
            }.extractOfflineBundle((done, total) -> { });
        } catch (java.io.IOException expected) { refused = expected.getMessage().contains("维护尚未完成"); }
        check(refused && !new File(fixture, "linux").exists(), "未完成维护的自动解压未在写入前拒绝");
        check(!failure.start(true, 1) && !EnvironmentTaskGate.isBusy(), "安装入口没有拒绝 pending 维护或泄漏锁");
        pending.rollback();
        attach(repository); note("PASS 隔离缺环境失败、UI 错误展示与失败后重试");
        note("PASS 未完成维护在任何解压写入前拒绝，安装拒绝后锁已释放");
    }
    private Map<String, String> footprint(File root) throws Exception {
        Map<String, String> result = new LinkedHashMap<>();
        for (String name : new String[]{"var/lib/dpkg/status", "var/log/apt/history.log", "var/log/dpkg.log",
                "root/.dsha-python-version", "root/.dsha-pnpm-version", "root/dsh-bin/pnpm", "usr/local/lib/dsha-pnpm/bin/pnpm.cjs"}) {
            File file = new File(root, name); String value = file.length() + ":" + file.lastModified() + ":" + file.exists();
            if (file.isFile() && file.length() < 8 * 1024 * 1024) {
                MessageDigest hash = MessageDigest.getInstance("SHA-256");
                try (FileInputStream input = new FileInputStream(file)) {
                    byte[] bytes = new byte[8192]; int count; while ((count = input.read(bytes)) != -1) hash.update(bytes, 0, count);
                }
                value += ":" + android.util.Base64.encodeToString(hash.digest(), android.util.Base64.NO_WRAP);
            }
            result.put(name, value);
        }
        return result;
    }
}
