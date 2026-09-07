package com.deepseekharness.app.ui;

import android.app.Activity;
import android.app.Instrumentation;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.content.res.Configuration;
import android.os.Bundle;
import android.os.SystemClock;
import android.provider.Settings;
import androidx.appcompat.app.AppCompatDelegate;
import androidx.lifecycle.ViewModelProvider;
import com.deepseekharness.app.BuildConfig;
import com.deepseekharness.app.DeviceBridgeService;
import com.deepseekharness.app.bridge.AdbBridge;
import com.deepseekharness.app.bridge.LocalNetworkAccess;
import com.deepseekharness.app.core.HarnessController;
import com.deepseekharness.app.runtime.ProotBootstrap;
import com.deepseekharness.app.util.AdbResult;
import com.deepseekharness.app.util.AdbEnvironmentTask;
import com.deepseekharness.app.util.SensitiveData;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Comparator;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BooleanSupplier;
import org.json.JSONObject;

/** debug 自插桩。配对码只经私有一次性命令进入，绝不放 Intent、Bundle 参数或日志。 */
public final class AdbFlowAudit extends Instrumentation {
    private Bundle arguments;
    private Context app;
    private ProotBootstrap proot;
    private File folder;
    private volatile AdbPairActivity page;
    private volatile Bundle lastSaved;
    private volatile JSONObject transitionReport;
    private final AtomicBoolean busy = new AtomicBoolean();
    private volatile boolean stopping;
    private volatile boolean listening;
    private volatile String jobId = "", operation = "", phase = "idle", output = "", outcome = "READY";
    private int originalNight = AppCompatDelegate.MODE_NIGHT_UNSPECIFIED;
    private Thread worker;
    private int assertions;

    @Override public void onCreate(Bundle args) {
        super.onCreate(args);
        arguments = args == null ? new Bundle() : args;
        start();
    }

    @Override public void callActivityOnResume(Activity activity) {
        super.callActivityOnResume(activity);
        if (activity instanceof AdbPairActivity && activity.getIntent().getBooleanExtra("adb_flow_audit", false))
            page = (AdbPairActivity) activity;
    }

    @Override public void callActivityOnSaveInstanceState(Activity activity, Bundle state) {
        super.callActivityOnSaveInstanceState(activity, state);
        if (activity instanceof AdbPairActivity && activity.getIntent().getBooleanExtra("adb_flow_audit", false))
            lastSaved = new Bundle(state);
    }

    /** UI 断言回传到插桩线程，不能让失败直接崩掉 App 主线程。 */
    private void ui(Runnable action) {
        final Throwable[] error = {null};
        runOnMainSync(() -> {
            try { action.run(); } catch (Throwable e) { error[0] = e; }
        });
        if (error[0] instanceof AssertionError) throw (AssertionError) error[0];
        if (error[0] != null) throw new IllegalStateException("UI 操作失败：" + error[0].getClass().getSimpleName());
    }

    private void check(boolean condition, String reason) {
        assertions++;
        if (!condition) throw new AssertionError(reason);
    }

    private String safe(String text) {
        // 即使底层库异常地回显配对码，任何完整六位数字也不会进入报告。
        return SensitiveData.redact(text == null ? "" : text).replaceAll("(?<!\\d)[0-9]{6}(?!\\d)", "[配对码已隐藏]");
    }

    private synchronized void write(String name, JSONObject value) throws IOException {
        File temp = new File(folder, name + ".tmp");
        try (FileOutputStream out = new FileOutputStream(temp)) {
            out.write(value.toString().getBytes(StandardCharsets.UTF_8));
        }
        if (!temp.renameTo(new File(folder, name))) throw new IOException("测试报告写入失败");
    }

    private synchronized void progress(String text) {
        phase = safe(text);
        try { write("state.json", snapshot()); } catch (Exception ignored) { }
    }

    private JSONObject snapshot() throws Exception {
        JSONObject result = new JSONObject().put("job_id", jobId).put("operation", operation)
                .put("listening", listening)
                .put("busy", busy.get()).put("phase", safe(phase)).put("outcome", outcome)
                .put("output", safe(output)).put("wireless_debugging", wireless())
                .put("local_network_allowed", LocalNetworkAccess.granted(app))
                .put("adb_state", safe(DeviceBridgeService.adbState)).put("assertions", assertions);
        if (transitionReport != null) result.put("lifecycle", transitionReport);
        AdbPairActivity screen = page;
        if (screen != null && !screen.isDestroyed() && !screen.isFinishing()) {
            final String[] text = {""};
            final boolean[] inFlight = {false};
            ui(() -> {
                AdbPairActivity.PairModel model = model();
                inFlight[0] = model.busy;
                text[0] = safe(model.status.getValue());
            });
            result.put("ui_busy", inFlight[0]).put("ui_status", text[0]);
        }
        return result;
    }

    private int wireless() {
        try { return Settings.Global.getInt(app.getContentResolver(), "adb_wifi_enabled", -1); }
        catch (Exception ignored) { return -1; }
    }

    private void connectionGate() {
        check(proot.isEnvironmentReady(), "BLOCKED_ENV: 请先安装环境，验收不自动解压");
        check(LocalNetworkAccess.granted(app), "BLOCKED_PERMISSION: 请先手动允许 DSHA 局域网权限");
        check(wireless() != 0, "BLOCKED_WIRELESS_DISABLED: 请手动打开系统无线调试；本入口不修改该开关");
    }

    @Override public void onStart() {
        Bundle result = new Bundle();
        try {
            check(BuildConfig.DEBUG, "仅允许 debug 包");
            check(!arguments.containsKey("code"), "配对码不能作为 instrumentation 参数，请使用私有命令助手");
            app = getTargetContext().getApplicationContext();
            proot = HarnessController.get(app).proot();
            folder = new File(app.getCacheDir(), "adb-flow-audit");
            check(folder.isDirectory() || folder.mkdirs(), "无法创建私有测试目录");
            // 只清理上次中断留下的请求，绝不自动重放尚未确认完成的配对。
            File[] stale = folder.listFiles((d, n) -> n.matches("command-[a-f0-9]{32}\\.(json|claimed|tmp)"));
            if (stale != null) for (File f : stale) check(f.delete(), "无法移除过期私有请求");
            progress("READY: 等待显式测试命令");
            String mode = arguments.getString("mode", "status");
            if (mode.equals("listen")) listen();
            else if (mode.equals("lifecycle")) lifecycle();
            else {
                check(mode.equals("status") || mode.equals("prepare") || mode.equals("verify"), "未知测试模式");
                begin(new JSONObject().put("id", "oneshot").put("op", mode));
                await(() -> !busy.get(), 480_000, "验收仍在运行，请查看 state.json；勿重放已派发操作");
                check(outcome.equals("PASS"), output);
            }
            result.putString("result", "PASS");
        } catch (Throwable e) {
            result.putString("result", "FAIL");
            result.putString("failure", safe(e instanceof AssertionError ? e.getMessage() : e.getClass().getSimpleName()));
        } finally {
            listening = false;
            if (busy.get()) {
                // 不在真实配对中途杀进程；请求有生产超时，此处等待其安全结束。
                try { await(() -> !busy.get(), 240_000, "任务尚未收尾"); }
                catch (Throwable e) { result.putString("failure", "任务未收尾，结果未知，请先检查连接再决定下一步"); }
            }
            try {
                if (page != null && !page.isDestroyed()) ui(() -> {
                    page.getDelegate().setLocalNightMode(originalNight);
                    page.finish();
                });
            } catch (Throwable e) { result.putString("failure", "验收页面收尾失败，请手动返回 DSHA"); }
            lastSaved = null;
            if (result.containsKey("failure")) result.putString("result", "FAIL");
            result.putInt("assertions", assertions);
            if (folder != null) try {
                JSONObject report = new JSONObject().put("result", result.getString("result", "FAIL"))
                        .put("failure", safe(result.getString("failure", ""))).put("assertions", assertions);
                write("report.json", report);
                write("state.json", snapshot());
            } catch (Exception ignored) { }
            finish(result.containsKey("failure") ? Activity.RESULT_CANCELED : Activity.RESULT_OK, result);
        }
    }

    private void listen() throws Exception {
        long seconds = Math.max(60, Math.min(3600, Long.parseLong(arguments.getString("seconds", "1800"))));
        long until = SystemClock.elapsedRealtime() + seconds * 1000;
        listening = true;
        progress("READY: 私有命令监听已启动");
        Bundle ready = new Bundle(); ready.putString("stream", "AdbFlowAudit READY；私有目录 cache/adb-flow-audit\n"); sendStatus(0, ready);
        while (!stopping && SystemClock.elapsedRealtime() < until) {
            File[] requests = folder.listFiles((d, n) -> n.matches("command-[a-f0-9]{32}\\.json"));
            if (requests != null) {
                Arrays.sort(requests, Comparator.comparingLong(File::lastModified));
                for (File request : requests) {
                    consume(request);
                    if (stopping) break;
                }
            }
            Thread.sleep(100);
        }
    }

    private void consume(File request) throws Exception {
        String id = request.getName().substring(8, 40);
        File claimed = new File(folder, "command-" + id + ".claimed");
        if (!request.renameTo(claimed)) return;
        JSONObject job = null;
        byte[] raw = null;
        JSONObject response = new JSONObject().put("id", id);
        try {
            check(claimed.length() > 0 && claimed.length() <= 4096, "私有请求长度无效");
            try (FileInputStream in = new FileInputStream(claimed); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
                byte[] buffer = new byte[1024]; int n;
                while ((n = in.read(buffer)) != -1) { out.write(buffer, 0, n); check(out.size() <= 4096, "请求过长"); }
                raw = out.toByteArray();
            }
            check(claimed.delete(), "私有请求未能在执行前清理");
            job = new JSONObject(new String(raw, StandardCharsets.UTF_8));
            check(id.equals(job.optString("id")), "请求标识不匹配");
            String op = job.optString("op");
            if (op.equals("state")) response.put("state", snapshot());
            else if (op.equals("stop")) {
                check(!busy.get(), "BUSY: 请等待配对或验证结束后再停止插桩"); stopping = true;
            } else if (op.equals("launch")) launch();
            else if (op.equals("rotate")) { rotate(); response.put("lifecycle", transitionReport); }
            else if (op.equals("theme")) { theme(job.optString("night", "toggle")); response.put("lifecycle", transitionReport); }
            else if (op.equals("lifecycle")) { check(!busy.get(), "BUSY: 实际任务进行中，请改用 rotate/theme"); lifecycle(); }
            else {
                check(op.equals("status") || op.equals("prepare") || op.equals("verify") || op.equals("pair"), "未知操作");
                begin(job);
                response.put("accepted", true).put("job_id", id);
            }
            response.put("ok", true);
        } catch (Throwable e) {
            // JSON/编码异常不打印 message，以免原始请求被系统解析器带出。
            response.put("ok", false).put("error", safe(e instanceof AssertionError ? e.getMessage() : e.getClass().getSimpleName()));
        } finally {
            if (job != null) job.remove("code");
            if (raw != null) Arrays.fill(raw, (byte) 0);
            if (claimed.exists()) claimed.delete();
        }
        write(id + ".json", response);
    }

    private synchronized void begin(JSONObject request) throws Exception {
        String op = request.getString("op");
        String code = request.optString("code", ""); request.remove("code");
        String host = request.optString("host", "").trim();
        String pp = request.optString("port", "").trim(), cp = request.optString("connect_port", "").trim();
        boolean viaUi = request.optBoolean("ui", false);
        AdbResult.port(pp); AdbResult.port(cp);
        if (!host.isEmpty()) check(AdbBridge.localAddresses().contains(host), "请使用本机无线调试 IP");
        if (op.equals("pair")) check(AdbResult.code(code), "配对码应恰好为六位数字，未派发");
        if (op.equals("pair") || op.equals("verify")) connectionGate();
        if (viaUi) check(page != null && !page.isDestroyed(), "UI 模式需先运行 launch，再打开系统配对设置");
        check(busy.compareAndSet(false, true), "BUSY: 已有实际任务，未派发新操作");
        jobId = request.getString("id"); operation = op; outcome = "RUNNING"; output = "";
        progress("已接收 " + op + "，尚未完成");
        final String privateCode = code;
        worker = new Thread(() -> {
            try {
                if (op.equals("status")) {
                    output = proot.isEnvironmentReady() ? safe(AdbBridge.status(proot)) : "env:not_ready";
                    outcome = "PASS";
                } else if (op.equals("prepare")) {
                    check(proot.isEnvironmentReady(), "BLOCKED_ENV: 请先安装环境");
                    output = safe(AdbBridge.ensureReady(app, proot, this::progress));
                    outcome = AdbResult.marker(output, "SETUP_DONE") ? "PASS" : "FAIL";
                } else {
                    String result;
                    if (viaUi) result = runUi(op, privateCode, host, pp, cp);
                    else {
                        result = AdbBridge.runEnvironmentTask(app, "ADB 自测配对或验证", () -> {
                            // 先单独 prepare，避免系统配对码在准备环境期间失效。
                            check(AdbBridge.injected(proot), "BLOCKED_PREPARE: 请先运行 prepare，再获取新的系统配对码");
                            progress(op.equals("pair") ? "正在完成一次配对和连接验证" : "正在验证已有连接");
                            return op.equals("pair") ? AdbBridge.pair(proot, privateCode, pp, cp, host) : AdbBridge.verify(proot, cp, host);
                        });
                    }
                    output = safe(result);
                    AdbResult.PairState state = AdbResult.pairState(result);
                    outcome = state == AdbResult.PairState.CONNECTED ? "PASS" : state == AdbResult.PairState.PAIRED ? "PAIRED_UNVERIFIED" : "FAIL";
                }
                if (AdbResult.marker(output, "ENVIRONMENT_BUSY")) outcome = "ENVIRONMENT_BUSY";
            } catch (AdbEnvironmentTask.Busy e) {
                output = safe("ENVIRONMENT_BUSY: " + e.getMessage());
                outcome = "ENVIRONMENT_BUSY";
            } catch (Throwable e) {
                output = safe(e instanceof AssertionError ? e.getMessage() : e.getClass().getSimpleName());
                outcome = "FAIL";
            } finally {
                synchronized (AdbFlowAudit.this) {
                    busy.set(false);
                    progress("任务结束：" + outcome);
                    try { write(jobId + "-result.json", snapshot()); } catch (Exception ignored) { }
                }
            }
        }, "adb-flow-audit-job");
        worker.start();
    }

    private String runUi(String op, String code, String host, String pp, String cp) throws Exception {
        final AdbPairActivity.PairModel[] value = {null};
        ui(() -> {
            value[0] = model();
            check(!value[0].busy, "配对页面已有任务，未重复派发");
            value[0].host = host; value[0].pairPort = pp; value[0].connectPort = cp;
            value[0].code = code;
            value[0].start(op.equals("verify"), "", 0);
        });
        check(value[0].busy, "UI 未接受任务，请查看页面的权限或输入反馈");
        await(() -> !value[0].busy, 420_000, "UI 任务未在时限内结束；勿重放配对");
        check(!value[0].cleared, "任务期间配对页面被关闭，结果未知");
        return value[0].status.getValue();
    }

    private void launch() {
        check(LocalNetworkAccess.granted(app), "请先手动允许局域网权限，再进行 UI 生命周期检查");
        if (page != null && !page.isDestroyed()) return;
        startActivitySync(new Intent(app, AdbPairActivity.class).putExtra("adb_flow_audit", true).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        check(page != null, "配对页面未恢复到前台");
        ui(() -> originalNight = page.getDelegate().getLocalNightMode());
    }

    private AdbPairActivity.PairModel model() {
        return new ViewModelProvider(page).get(AdbPairActivity.PairModel.class);
    }

    private static final class UiSnapshot {
        AdbPairActivity activity;
        AdbPairActivity.PairModel model;
        Object task;
        String code, status;
        boolean busy;
    }

    private UiSnapshot capture() {
        check(page != null && !page.isDestroyed(), "请先运行 launch 打开配对页");
        UiSnapshot old = new UiSnapshot();
        ui(() -> {
            old.activity = page; old.model = model(); old.code = old.model.code;
            old.status = old.model.status.getValue(); old.busy = old.model.busy;
            try {
                java.lang.reflect.Field field = AdbPairActivity.PairModel.class.getDeclaredField("task");
                field.setAccessible(true); old.task = field.get(old.model);
            } catch (ReflectiveOperationException e) { throw new AssertionError("无法检查配对任务身份"); }
        });
        return old;
    }

    private void retained(UiSnapshot old) throws Exception {
        UiSnapshot now = capture();
        check(now.model == old.model, "页面重建后 ViewModel 丢失");
        check(now.task == old.task, "页面重建重启了配对任务");
        check(!now.model.cleared, "重建错误地取消了配对任务");
        if (old.busy && now.busy) check(now.code.equals(old.code), "配对中内存输入丢失");
        if (!old.busy) check(java.util.Objects.equals(old.status, now.status), "已完成的结果在重建后丢失");
        if (old.code != null && !old.code.isEmpty() && old.activity != now.activity) {
            check(lastSaved != null, "未取得重建时的保存状态，不能证明配对码未落入其中");
            check(!lastSaved.toString().contains(old.code), "一次性配对码进入保存状态");
        }
        ui(() -> {
            try {
                java.lang.reflect.Field text = AdbPairActivity.class.getDeclaredField("statusText");
                text.setAccessible(true);
                android.widget.TextView view = (android.widget.TextView) text.get(page);
                check(java.util.Objects.equals(view.getText().toString(), model().status.getValue()), "配对状态未恢复到新页面");
                java.lang.reflect.Field button = AdbPairActivity.class.getDeclaredField("startBtn");
                button.setAccessible(true);
                check(((android.widget.Button) button.get(page)).isEnabled() != model().busy, "配对按钮没有恢复忙闲状态");
            } catch (ReflectiveOperationException e) { throw new AssertionError("无法验证配对页面状态"); }
        });
        transitionReport = new JSONObject().put("recreated", old.activity != now.activity)
                .put("model_retained", true).put("task_retained", true)
                .put("before_busy", old.busy).put("after_busy", now.busy);
        old.code = null; now.code = null;
    }

    private void rotate() throws Exception {
        UiSnapshot old = capture(); lastSaved = null;
        ui(() -> page.setRequestedOrientation(page.getResources().getConfiguration().orientation == Configuration.ORIENTATION_LANDSCAPE
                ? ActivityInfo.SCREEN_ORIENTATION_PORTRAIT : ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE));
        await(() -> page != old.activity && !page.isDestroyed(), 15000, "旋转未触发重建，系统可能限制方向；未算通过");
        retained(old);
    }

    private void theme(String night) throws Exception {
        check(night.equals("dark") || night.equals("light") || night.equals("toggle") || night.equals("restore"), "未知主题操作");
        UiSnapshot old = capture(); lastSaved = null;
        final boolean[] changed = {false};
        ui(() -> {
            int target = night.equals("restore") ? originalNight : night.equals("dark") ? AppCompatDelegate.MODE_NIGHT_YES
                    : night.equals("light") ? AppCompatDelegate.MODE_NIGHT_NO
                    : ThemeController.isDark(page) ? AppCompatDelegate.MODE_NIGHT_NO : AppCompatDelegate.MODE_NIGHT_YES;
            changed[0] = !night.equals("restore") && ((target == AppCompatDelegate.MODE_NIGHT_YES) != ThemeController.isDark(page));
            page.getDelegate().setLocalNightMode(target);
        });
        if (changed[0]) await(() -> page != old.activity && !page.isDestroyed(), 15000, "主题切换未重建，未算通过");
        else { waitForIdleSync(); Thread.sleep(400); }
        retained(old);
    }

    private void lifecycle() throws Exception {
        launch();
        AdbPairActivity.PairModel[] original = {null}; Bundle saved = new Bundle();
        ui(() -> {
            original[0] = model(); check(!original[0].busy, "已有真实任务，不能注入生命周期 fixture");
            original[0].busy = true; original[0].code = "102938";
            original[0].status.setValue("AUDIT_FIXTURE: 模拟配对进行中，不执行任何配对或设备命令");
            original[0].save(saved);
        });
        try {
            rotate(); theme("toggle");
            ui(() -> {
                original[0].busy = false; original[0].code = "";
                original[0].status.setValue("AUDIT_FIXTURE: 配对结果保留检查完成");
            });
            rotate(); theme("restore");
            ui(() -> {
                AdbPairActivity.PairModel restored = new AdbPairActivity.PairModel((android.app.Application) app);
                restored.restore(saved);
                check(!restored.busy && restored.code.isEmpty(), "进程恢复错误地重放任务或恢复配对码");
                check(restored.status.getValue().contains("被系统中断"), "进程恢复没有明确中断反馈");
            });
            output = "PASS: fixture 在执行中/完成后旋转、主题切换保留模型和结果；保存状态无配对码；进程恢复提示中断。";
            outcome = "PASS"; progress("生命周期 fixture 通过（不等同真实配对或杀进程实测）");
        } finally {
            ui(() -> { if (original[0] != null) { original[0].busy = false; original[0].code = ""; } });
        }
    }

    private void await(BooleanSupplier condition, long timeout, String reason) throws Exception {
        long until = SystemClock.elapsedRealtime() + timeout;
        while (!condition.getAsBoolean()) {
            if (SystemClock.elapsedRealtime() >= until) throw new AssertionError(reason);
            Thread.sleep(50);
        }
    }
}
