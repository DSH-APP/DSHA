package com.deepseekharness.app;

import android.app.Activity;
import android.app.Instrumentation;
import android.content.Context;
import android.content.ContextWrapper;
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.ParcelFileDescriptor;
import android.os.SystemClock;
import android.widget.Button;
import android.widget.TextView;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.lifecycle.Lifecycle;
import com.deepseekharness.app.util.Compat;
import com.deepseekharness.app.util.SensitiveData;
import com.deepseekharness.app.util.ShellQuote;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.InputStream;
import java.lang.reflect.Field;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;

/**
 * debug 主 APK 自插桩：真实 sh/回环 socket/AlertDialog，全部使用测试数据。
 * 主线程注册本类 instrumentation 与下方 AskActivity 即可；本类不调用 adb、不启动 Web。
 * 参数 case=all|shell|binding|ask，shell_ms=750，ask_ms=1500；输出仅断言结果与假数据目录。
 */
public final class BridgeAuditInstrumentation extends Instrumentation {
    private Bundle args;
    private int assertions;
    private final List<String> passed = new ArrayList<>();
    private FixtureContext fixture;
    private HttpShellService bridge;
    private AskActivity activity;
    private String fixtureToken;
    private final AtomicReference<BridgeAskDialog> productionAskUi = new AtomicReference<>();
    private long shellMillis, askMillis;
    private final ExecutorService workers = Executors.newFixedThreadPool(4, task -> {
        Thread t = new Thread(task, "bridge-audit"); t.setDaemon(true); return t;
    });

    @Override public void onCreate(Bundle arguments) {
        super.onCreate(arguments);
        args = arguments == null ? new Bundle() : new Bundle(arguments);
        start();
    }

    @Override public void onStart() {
        Bundle result = new Bundle();
        Throwable failure = null;
        try {
            check(BuildConfig.DEBUG, "只允许 debug 自插桩");
            String selected = args.getString("case", "all");
            check(Arrays.asList("all", "shell", "binding", "ask").contains(selected), "未知 case");
            shellMillis = duration("shell_ms", 750, 100, 30_000);
            askMillis = duration("ask_ms", 1500, 250, 15_000);
            if (selected.equals("all") || selected.equals("shell")) shellCases();
            if (!selected.equals("shell")) {
                check(!HttpShellService.isReady() && !HttpShellService.isStarting(),
                        "已有设备桥运行或启动中，保留现场；本测试不停止用户桥");
                fixture = new FixtureContext(getTargetContext());
                fixtureToken = "audit_" + UUID.randomUUID().toString().replace("-", "");
                File token = new File(fixture.base, ".bridge_token");
                Compat.write(token, fixtureToken);
                bridge = new HttpShellService(fixture, token, productionAskUi::set);
                if (selected.equals("all") || selected.equals("binding")) bindingCases();
                else { bridge.start(); awaitReady(); }
                if (selected.equals("all") || selected.equals("ask")) askCases();
            }
        } catch (Throwable error) { failure = error; }
        finally {
            try {
                if (bridge != null) bridge.stop(); // 只停止本类持有的实例；抢占失败的实例无权停真实桥。
                if (activity != null) ui(() -> { activity.finish(); return null; });
                waitForIdleSync();
            } catch (Throwable cleanup) { if (failure == null) failure = cleanup; }
            workers.shutdownNow();
        }
        result.putInt("assertions", assertions);
        result.putString("passed", android.text.TextUtils.join("; ", passed));
        if (fixture != null) result.putString("fixture", fixture.base.getAbsolutePath());
        result.putString("result", failure == null ? "PASS" : "FAIL");
        if (failure != null) result.putString("failure", SensitiveData.redact(failure.toString()));
        // 仅保留本次创建的假 token/诊断文件供定位，不递归清理任何应用数据。
        finish(failure == null ? Activity.RESULT_OK : Activity.RESULT_CANCELED, result);
    }

    private long duration(String key, long fallback, long min, long max) {
        long value = Long.parseLong(args.getString(key, String.valueOf(fallback)));
        if (value < min || value > max) throw new IllegalArgumentException(key + " 超出测试期限范围");
        return value;
    }

    private void shellCases() throws Exception {
        report("真实 ShellService：四路静默命令超时与同一工作池的后继请求");
        ShellService shell = new ShellService(shellMillis);
        List<Future<String>> requests = new ArrayList<>();
        long start = SystemClock.elapsedRealtime();
        // 已识别的 cat 读取无写端 FIFO，覆盖静默超时，避免以任意脚本绕过新策略。
        File base = new File(getTargetContext().getCacheDir(), "bridge-shell-" + UUID.randomUUID());
        check(base.mkdirs(), "创建独立 FIFO 目录");
        List<File> pipes = new ArrayList<>();
        try {
        for (int i = 0; i < 4; i++) {
            File pipe = new File(base, "wait-" + i); pipes.add(pipe);
            android.system.Os.mkfifo(pipe.getPath(), 0600);
            requests.add(workers.submit(() -> shell.exec("cat " + ShellQuote.arg(pipe.getPath()))));
        }
        for (Future<String> request : requests) {
            String response = request.get(shellMillis + 5000, TimeUnit.MILLISECONDS);
            check(response.contains("[EXIT=timeout]"), "静默命令必须返回超时标记");
            check(response.length() < 512, "无输出命令响应有界");
        }
        long elapsed = SystemClock.elapsedRealtime() - start;
        check(elapsed >= shellMillis - 100 && elapsed < shellMillis + 5000, "四路命令总等待有界");
        String next = workers.submit(() -> shell.exec("printf BRIDGE_AUDIT_NEXT")).get(4, TimeUnit.SECONDS);
        check(next.contains("BRIDGE_AUDIT_NEXT") && next.contains("[EXIT=0]"), "超时后后继请求可以执行");
        String nonzero = workers.submit(() -> shell.exec("ls " + ShellQuote.arg(new File(base, "missing").getPath()))).get(4, TimeUnit.SECONDS);
        check(nonzero.contains("missing") && nonzero.contains("[EXIT=1]"), "普通输出与非零退出码保留");
        report("真实 ShellService：持续输出达到上限仍按期返回");
        ShellService flooding = new ShellService(Math.max(2500, shellMillis));
        String command = "cat /dev/zero";
        String output = workers.submit(() -> flooding.exec(command)).get(Math.max(2500, shellMillis) + 5000, TimeUnit.MILLISECONDS);
        check(output.contains("[OUTPUT_TRUNCATED]") && output.contains("[EXIT=timeout]"), "持续输出同时受大小与期限约束");
        check(output.length() <= 262_144 + 256, "输出正文不得超过 256 KiB 加固定状态说明");
        passed.add("ShellService: four timeouts, follow-up, exit code, bounded flood; elapsed=" + elapsed + "ms");
        } finally {
            for (File pipe : pipes) pipe.delete();
            base.delete();
        }
    }

    private void bindingCases() throws Exception {
        report("真实 3090：占用端口 → 撤销假就绪 → 释放端口 → 同实例恢复监听");
        // 若端口已被真实服务占用，bind 会失败并退出，不驱逐服务或轮换其 token。
        try (ServerSocket occupied = new ServerSocket()) {
            occupied.setReuseAddress(true);
            occupied.bind(new InetSocketAddress(InetAddress.getByName("127.0.0.1"), HttpShellService.PORT));
            bridge.start();
            until(() -> !HttpShellService.isStarting(), 5000, "绑定失败未释放启动占位");
            check(!HttpShellService.isReady() && HttpShellService.instance() == null, "绑定失败不得发布就绪实例");
            check(!HttpShellService.bindError().isEmpty(), "绑定失败有可见原因");
            check(Compat.readAll(new File(fixture.base, ".bridge_status")).startsWith("fail "), "绑定失败写入隔离桥状态");
            check(Compat.readAll(new File(fixture.getFilesDir(), "diagnostic-events.txt")).contains("BRIDGE_BIND"), "绑定失败进入诊断记录");
        }
        bridge.start(); awaitReady();
        check(HttpShellService.bindError().isEmpty(), "重试成功清除旧失败状态");
        check(Compat.readAll(new File(fixture.base, ".bridge_status")).startsWith("ok port=3090"), "恢复后状态为真实绑定成功");
        check("[UNAUTHORIZED]".equals(health("audit_wrong_token")), "错误 token 仍被拒绝");
        check("OK".equals(health(fixtureToken)), "恢复后正确 token 可以访问健康端点");
        HttpShellService duplicate = new HttpShellService(fixture, new File(fixture.base, "unused-token"));
        duplicate.start(); duplicate.stop();
        check(HttpShellService.instance() == bridge && "OK".equals(health(fixtureToken)), "非持有者不能停止真实监听");
        try (Socket idle = new Socket()) {
            idle.connect(new InetSocketAddress("127.0.0.1", HttpShellService.PORT), 2000);
            idle.setSoTimeout(2000);
            // 等 accept 已登记这个连接后再停止，覆盖线程池内阻塞读请求的关闭。
            until(() -> activeClients() > 0, 3000, "测试连接未进入桥工作池");
            bridge.stop();
            check(idle.getInputStream().read() == -1, "停止桥应关闭正在等请求头的连接");
        }
        check(!HttpShellService.isReady() && !HttpShellService.isStarting(), "停止撤销全部就绪标志");
        bridge.start(); awaitReady();
        check("OK".equals(health(fixtureToken)), "主动停止后还可再次启动与处理请求");
        passed.add("HttpShellService: occupied 3090 recovery, diagnostics, token gate, owner isolation, socket cleanup");
    }

    private int activeClients() {
        try {
            Object run = field(bridge, "activeRun");
            return run == null ? 0 : ((java.util.Set<?>) field(run, "clients")).size();
        } catch (Exception e) { throw new AssertionError(e); }
    }

    private void awaitReady() throws Exception {
        until(() -> HttpShellService.isReady() && HttpShellService.instance() == bridge, 5000, "本测试桥未恢复真实就绪");
    }

    private String health(String token) throws Exception {
        return bridgeRequest("/health", token, 2000);
    }

    private String bridgeRequest(String target, String token, int readTimeoutMillis) throws Exception {
        try (Socket client = new Socket()) {
            client.connect(new InetSocketAddress("127.0.0.1", HttpShellService.PORT), 2000);
            client.setSoTimeout(readTimeoutMillis);
            String request = "GET " + target + " HTTP/1.1\r\nHost: 127.0.0.1\r\nX-Token: " + token + "\r\n\r\n";
            client.getOutputStream().write(request.getBytes(StandardCharsets.UTF_8));
            client.getOutputStream().flush();
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            InputStream input = client.getInputStream(); byte[] buffer = new byte[1024]; int count;
            while ((count = input.read(buffer)) != -1) {
                if (bytes.size() + count > 8192) throw new AssertionError("健康响应超过测试上限");
                bytes.write(buffer, 0, count);
            }
            String response = new String(bytes.toByteArray(), StandardCharsets.UTF_8);
            int boundary = response.indexOf("\r\n\r\n");
            check(boundary >= 0, "健康端点返回完整 HTTP 响应");
            return new org.json.JSONObject(response.substring(boundary + 4)).getString("result");
        }
    }

    private void askCases() throws Exception {
        foregroundMain();
        report("真实 AlertDialog：短期限超时清窗、旧按钮回调与新请求取消隔离");
        ActivityMonitor monitor = addMonitor(AskActivity.class.getName(), null, false);
        try {
            ui(() -> {
                getTargetContext().startActivity(new Intent(getTargetContext(), AskActivity.class)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
                return null;
            });
            Activity opened = waitForMonitorWithTimeout(monitor, 15_000);
            check(opened instanceof AskActivity,
                    "AskActivity 在 15 秒内未打开，可能被系统后台启动限制拦截；测试已结束等待");
            activity = (AskActivity) opened;
        } finally { removeMonitor(monitor); }
        // Monitor 通常在 onCreate 返回实例；队列暂时空闲也不代表已 RESUMED 或取得窗口焦点。
        // 每次 ask() 都单独等待宿主就绪，同时覆盖上一弹窗关闭后焦点尚未归还的情况。
        productionHostCase();
        Ask first = ask("old_answer", askMillis);
        AlertDialog oldWindow = window(first);
        Button oldButton = ui(() -> oldWindow.getButton(AlertDialog.BUTTON_POSITIVE));
        check(first.future.get(askMillis + 4000, TimeUnit.MILLISECONDS).startsWith("[TIMEOUT]"), "无回答提问按测试期限超时");
        waitForIdleSync();
        check(ui(() -> !oldWindow.isShowing()), "超时后旧窗口必须关闭");
        check(!((Boolean) field(first.ui.get(), "registered")), "超时后解除窗口生命周期监听");
        Ask second = ask("new_answer", Math.min(120_000, askMillis * 3 + 5000));
        AlertDialog currentWindow = window(second);
        ui(() -> { oldButton.performClick(); return null; }); // 故意重放已经关闭的旧窗口回调。
        waitForIdleSync();
        check(!second.future.isDone(), "旧按钮不能提前结束新提问");
        ui(() -> { currentWindow.cancel(); return null; });
        check(second.future.get(3, TimeUnit.SECONDS).startsWith("[DISMISSED]"), "新提问取消不能返回旧答案");

        Ask answered = ask("current_answer", 10_000);
        AlertDialog answeredWindow = window(answered);
        ui(() -> { answeredWindow.getButton(AlertDialog.BUTTON_POSITIVE).performClick(); return null; });
        check("current_answer".equals(answered.future.get(3, TimeUnit.SECONDS)), "当前按钮仍能正常回答");

        Ask stopped = ask("stopped_answer", 10_000);
        AlertDialog stoppedWindow = window(stopped);
        bridge.stop();
        check(stopped.future.get(3, TimeUnit.SECONDS).startsWith("[STOPPED]"), "服务停止立即释放提问等待");
        waitForIdleSync(); check(ui(() -> !stoppedWindow.isShowing()), "服务停止关闭本次窗口");
        bridge.start(); awaitReady();

        Ask destroyed = ask("destroyed_answer", 10_000);
        AlertDialog destroyedWindow = window(destroyed);
        ui(() -> { activity.finish(); return null; });
        String ended = destroyed.future.get(3, TimeUnit.SECONDS);
        check(ended.startsWith("[APP_BACKGROUND]") || ended.startsWith("[UNAVAILABLE]"), "页面销毁不得伪造用户答案或等满超时");
        until(() -> activity.isDestroyed(), 5000, "测试 Activity 未销毁");
        waitForIdleSync(); check(ui(() -> !destroyedWindow.isShowing()), "页面销毁清理窗口");
        check(!((Boolean) field(destroyed.ui.get(), "registered")), "页面销毁解除监听");
        check(ui(() -> ForegroundActivity.current() != activity), "已销毁的提问宿主不能留在生产前台跟踪器中");
        passed.add("app/ask: timeout cleanup, stale real button, new cancellation, current answer, service stop, Activity destruction");
    }

    /** 用带 token 的真实 HTTP 走私有默认 appAsk，确保不是注入 Activity 掩盖生产宿主缺失。 */
    private void productionHostCase() throws Exception {
        awaitAskHostReady();
        AtomicReference<com.deepseekharness.app.ui.MainActivity> previous = new AtomicReference<>();
        java.util.concurrent.atomic.AtomicBoolean cleared = new java.util.concurrent.atomic.AtomicBoolean();
        try {
            // current 是导航引用，Main 被覆盖后仍可非空。只在测试作用域清空它，证明桥不依赖它。
            // ForegroundActivity 完全由真实生命周期驱动，此处不注入、不修改前台跟踪器。
            ui(() -> {
                previous.set(com.deepseekharness.app.ui.MainActivity.current);
                com.deepseekharness.app.ui.MainActivity.current = null;
                cleared.set(true);
                return null;
            });
            check(ui(() -> com.deepseekharness.app.ui.MainActivity.current == null), "测试作用域已清空导航引用");
            check(ui(() -> ForegroundActivity.current() == activity && ForegroundActivity.isResumed(activity)),
                    "DshaApp 注册的跟踪器必须找到非 Main 的 RESUMED Activity");
            check(bridgeRequest("/app/device", fixtureToken, 5000).contains("app_foreground=true"), "设备状态正确识别非 Main 前台页面");
            productionAskUi.set(null);
            Ask production = new Ask(productionAskUi);
            production.future = workers.submit(() -> bridgeRequest("/app/ask?q=production_host&options=production_answer|other",
                    fixtureToken, 10_000));
            AlertDialog window = window(production);
            check(field(production.ui.get(), "activity") == activity, "生产默认提问必须使用前台跟踪器选出的宿主");
            ui(() -> { window.getButton(AlertDialog.BUTTON_POSITIVE).performClick(); return null; });
            check("production_answer".equals(production.future.get(5, TimeUnit.SECONDS)), "默认 HTTP /app/ask 在 Main.current 为空时仍可正常回答");
            passed.add("production HTTP ask: authenticated request selects non-Main foreground host; app status correct");
        } finally {
            ui(() -> {
                com.deepseekharness.app.ui.MainActivity old = previous.get();
                // 不复活已销毁/正在退出的导航宿主，也不覆盖期间新发布的 current。
                if (cleared.get() && com.deepseekharness.app.ui.MainActivity.current == null
                        && old != null && !old.isFinishing() && !old.isDestroyed())
                    com.deepseekharness.app.ui.MainActivity.current = old;
                return null;
            });
        }
    }

    /** MIUI 可拦截后台 startActivity；先通过测试 shell 取前台，不启动 Web、不扩大组件导出权限。 */
    private void foregroundMain() throws Exception {
        report("提问测试准备：唤醒并解除无安全锁的锁屏，将 MainActivity 带到前台（最多等待 15 秒）");
        AtomicReference<ParcelFileDescriptor> openedPipe = new AtomicReference<>();
        Future<String> foreground = workers.submit(() -> {
            StringBuilder response = new StringBuilder();
            // 仅在测试主动运行时取前台；不绕过安全锁、不修改系统休眠时间。
            for (String command : new String[]{"input keyevent 224", "wm dismiss-keyguard",
                    "am start -W -n com.dsh.client/com.deepseekharness.app.ui.MainActivity"}) {
                if (Thread.currentThread().isInterrupted()) throw new InterruptedException("前台启动检查已结束");
                ParcelFileDescriptor pipe = getUiAutomation().executeShellCommand(command);
                openedPipe.set(pipe);
                try (InputStream input = new ParcelFileDescriptor.AutoCloseInputStream(pipe)) {
                    if (Thread.currentThread().isInterrupted()) throw new InterruptedException("前台启动检查已结束");
                    ByteArrayOutputStream bytes = new ByteArrayOutputStream();
                    byte[] buffer = new byte[1024]; int count;
                    while ((count = input.read(buffer)) != -1) {
                        if (bytes.size() + count > 16_384) throw new AssertionError("前台启动响应超过测试上限");
                        bytes.write(buffer, 0, count);
                    }
                    response.append(command).append('\n')
                            .append(new String(bytes.toByteArray(), StandardCharsets.UTF_8)).append('\n');
                } finally { openedPipe.compareAndSet(pipe, null); }
            }
            return response.toString();
        });
        try {
            String response = foreground.get(15, TimeUnit.SECONDS);
            check(!response.contains("Error:") && !response.contains("Exception"),
                    "MainActivity 前台启动失败：" + SensitiveData.redact(response));
        } catch (java.util.concurrent.TimeoutException timeout) {
            throw new AssertionError("MainActivity 前台启动超过 15 秒，已停止等待；未发起提问", timeout);
        } finally {
            foreground.cancel(true);
            ParcelFileDescriptor pipe = openedPipe.getAndSet(null);
            if (pipe != null) try { pipe.close(); } catch (java.io.IOException ignored) { }
        }
    }

    private static final class Ask {
        final AtomicReference<BridgeAskDialog> ui;
        Ask() { this(new AtomicReference<>()); }
        Ask(AtomicReference<BridgeAskDialog> ui) { this.ui = ui; }
        Future<String> future;
    }
    private Ask ask(String answer, long timeout) throws Exception {
        awaitAskHostReady();
        Ask ask = new Ask();
        ask.future = workers.submit(() -> bridge.appAsk("/app/ask?q=bridge_audit&options=" + answer + "|cancel_option",
                timeout, ask.ui::set));
        return ask;
    }

    /** 启动/关闭弹窗的系统事务可以跨越多个主线程空闲点，必须等实际前台状态。 */
    private AskActivity awaitAskHostReady() throws Exception {
        final AskActivity host = activity;
        check(host != null, "提问宿主尚未创建");
        long deadline = SystemClock.elapsedRealtime() + 15_000;
        String last = "未取得状态";
        while (SystemClock.elapsedRealtime() < deadline) {
            HostState state = ui(host::hostState, Math.max(1, deadline - SystemClock.elapsedRealtime()));
            last = state.detail;
            if (state.ready) {
                report("提问宿主已就绪：" + state.detail);
                return host;
            }
            if (state.closed) throw new AssertionError("提问前宿主已关闭：" + state.detail);
            Thread.sleep(25);
        }
        throw new AssertionError("AskActivity 15 秒内未达到 RESUMED 且窗口取得焦点：" + last);
    }

    private static final class HostState {
        final boolean ready, closed;
        final String detail;
        HostState(boolean ready, boolean closed, String detail) {
            this.ready = ready; this.closed = closed; this.detail = detail;
        }
    }
    private AlertDialog window(Ask ask) throws Exception {
        long deadline = SystemClock.elapsedRealtime() + 5000;
        while (SystemClock.elapsedRealtime() < deadline) {
            AlertDialog window = ui(() -> ask.ui.get() == null ? null : (AlertDialog) field(ask.ui.get(), "dialog"));
            if (window != null && ui(window::isShowing)) return window;
            if (ask.future.isDone()) throw new AssertionError("提问未显示窗口便结束：" + ask.future.get()
                    + "；宿主状态：" + ui(() -> activity.hostState().detail));
            Thread.sleep(20);
        }
        throw new AssertionError("提问窗口未显示，请确认注册了 AskActivity");
    }

    private static Object field(Object instance, String name) throws Exception {
        Field field = instance.getClass().getDeclaredField(name); field.setAccessible(true); return field.get(instance);
    }
    private <T> T ui(Callable<T> action) throws Exception {
        return ui(action, 10_000);
    }
    private <T> T ui(Callable<T> action, long timeoutMillis) throws Exception {
        if (Looper.myLooper() == Looper.getMainLooper()) return action.call();
        FutureTask<T> task = new FutureTask<>(action);
        if (!new Handler(Looper.getMainLooper()).post(task)) throw new AssertionError("主线程已不能接收测试检查");
        try { return task.get(timeoutMillis, TimeUnit.MILLISECONDS); }
        catch (java.util.concurrent.TimeoutException error) {
            throw new AssertionError("主线程测试检查超过 " + timeoutMillis + "ms，已结束等待", error);
        } catch (java.util.concurrent.ExecutionException error) {
            throw new AssertionError("原生 UI 检查失败", error.getCause());
        } finally { task.cancel(false); }
    }
    private void until(BooleanSupplier condition, long timeout, String reason) throws Exception {
        long end = SystemClock.elapsedRealtime() + timeout;
        while (!condition.getAsBoolean() && SystemClock.elapsedRealtime() < end) Thread.sleep(20);
        check(condition.getAsBoolean(), reason);
    }
    private void check(boolean condition, String message) {
        assertions++;
        if (!condition) throw new AssertionError(message);
    }
    private void report(String message) {
        Bundle status = new Bundle(); status.putString("phase", message); sendStatus(1, status);
    }

    private static final class FixtureContext extends ContextWrapper {
        final File base;
        FixtureContext(Context context) throws Exception {
            super(context.getApplicationContext());
            base = new File(context.getCacheDir(), "bridge-audit-" + UUID.randomUUID());
            if (!base.mkdirs() || !getFilesDir().mkdirs()) throw new java.io.IOException("无法创建桥测试目录");
        }
        @Override public Context getApplicationContext() { return this; }
        @Override public File getFilesDir() { return new File(base, "files"); }
    }

    /** 独立的空白窗口，不进入 MainActivity、不触发解压或 Web 启动。 */
    public static final class AskActivity extends AppCompatActivity {
        private boolean resumed;
        private final java.util.ArrayDeque<String> events = new java.util.ArrayDeque<>();
        private void record(String event) {
            events.addLast(event + "@" + SystemClock.elapsedRealtime());
            if (events.size() > 12) events.removeFirst();
        }
        private HostState hostState() {
            android.view.View decor = getWindow() == null ? null : getWindow().peekDecorView();
            Lifecycle.State lifecycle = getLifecycle().getCurrentState();
            boolean attached = decor != null && decor.getWindowToken() != null;
            boolean shown = decor != null && decor.isShown();
            boolean focused = hasWindowFocus();
            boolean closed = isFinishing() || isDestroyed();
            boolean ready = !closed && resumed && lifecycle.isAtLeast(Lifecycle.State.RESUMED)
                    && attached && shown && focused;
            String detail = "lifecycle=" + lifecycle + ", resumed=" + resumed + ", focus=" + focused
                    + ", attached=" + attached + ", shown=" + shown + ", closed=" + closed
                    + ", events=" + android.text.TextUtils.join(" > ", events);
            return new HostState(ready, closed, detail);
        }
        @Override protected void onCreate(Bundle state) {
            super.onCreate(state);
            getWindow().addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
            record("create");
            TextView title = new TextView(this);
            title.setText("DSHA 原生桥提问测试（仅测试数据）");
            title.setPadding(24, 72, 24, 24);
            setContentView(title);
        }
        @Override protected void onStart() { super.onStart(); record("start"); }
        @Override protected void onResume() { super.onResume(); resumed = true; record("resume"); }
        @Override protected void onPostResume() { super.onPostResume(); record("postResume"); }
        @Override protected void onPause() { resumed = false; record("pause"); super.onPause(); }
        @Override protected void onStop() { record("stop"); super.onStop(); }
        @Override protected void onDestroy() { record("destroy"); super.onDestroy(); }
        @Override public void onWindowFocusChanged(boolean focused) {
            super.onWindowFocusChanged(focused); record("focus=" + focused);
        }
    }
}
