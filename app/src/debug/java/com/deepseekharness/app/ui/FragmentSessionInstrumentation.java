package com.deepseekharness.app.ui;

import android.app.Activity;
import android.app.Instrumentation;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.ParcelFileDescriptor;
import android.os.SystemClock;
import com.deepseekharness.app.R;
import com.deepseekharness.app.core.HarnessController;
import com.deepseekharness.app.runtime.ProotBootstrap;
import com.deepseekharness.app.util.SensitiveData;
import org.json.JSONObject;
import java.io.File;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * debug 注册入口：com.deepseekharness.app.ui.FragmentSessionInstrumentation，targetPackage=com.dsh.client。
 * 同时注册 FragmentSessionTestActivity（exported=false）。only=all（默认）/ui/terminal。
 * 不启动/停止真实 Web，不启用/禁用用户插件；终端只使用自己创建的进程与临时文件。
 */
public final class FragmentSessionInstrumentation extends Instrumentation {
    private Bundle arguments;
    private final JSONObject checks = new JSONObject();
    private final List<String> failures = new ArrayList<>();
    private final StringBuilder transcript = new StringBuilder();
    private static final long LAUNCH_TIMEOUT_MS = 15_000;
    private static final String OWNER_EXTRA = "dsha_fragment_self_owner";
    private final String launchOwner = UUID.randomUUID().toString().replace("-", "");
    private MainActivity ownMain;

    @Override public void onCreate(Bundle arguments) {
        super.onCreate(arguments);
        this.arguments = arguments == null ? new Bundle() : arguments;
        start();
    }

    @Override public void onStart() {
        Bundle output = new Bundle();
        Context app = getTargetContext().getApplicationContext();
        File folder = new File(app.getCacheDir(), "fragment-session-selftest/" + UUID.randomUUID());
        long started = System.currentTimeMillis();
        FragmentSessionTestActivity page = null;
        HarnessController controller = HarnessController.get(app);
        ProotBootstrap proot = controller.proot();
        long webGeneration = controller.getWebGeneration();
        String webAuthUrl = controller.getWebAuthUrl(); // 仅用于内存比较，绝不输出实际 URL/token。
        Map<String, String> pluginBefore = null;
        try {
            if (!folder.mkdirs()) throw new IOException("无法创建自测结果目录");
            String only = arguments.getString("only", "all");
            if (!only.equals("all") && !only.equals("ui") && !only.equals("terminal"))
                throw new IllegalArgumentException("only 仅支持 all/ui/terminal");
            pluginBefore = pluginState(proot.getRootfsDir());
            if (!only.equals("terminal")) {
                phase("独立页面：Launch 连点与 Plugin 安全状态刷新");
                try {
                    foregroundMain();
                    page = startTestHost(app);
                    // 宿主已在前台后才能关闭 Main；测试断言开始前等待其 onDestroy 收尾。
                    closeOwnMain();
                    FragmentEntrySelfTest.run(this, page, R.id.fragment_container, this::check);
                } catch (Throwable error) { fail("ui", error); }
                finally {
                    if (page != null) {
                        FragmentSessionTestActivity owned = page;
                        try { onMain(owned::finish); } catch (Throwable error) { fail("ui_cleanup", error); }
                        page = null;
                    }
                    try { closeOwnMain(); } catch (Throwable error) { fail("main_cleanup", error); }
                }
            }
            if (!only.equals("ui")) {
                phase("独立普通管道 shell：sleep 真实取消、exit 后首命令");
                try {
                    // UI 准备失败时也不能带着未清理的 Main 继续运行终端断言。
                    closeOwnMain();
                    TerminalSessionSelfTest.run(proot, this::appendTranscript, this::check);
                }
                catch (Throwable error) { fail("terminal", error); }
            }
        } catch (Throwable error) { fail("setup", error); }
        finally {
            if (page != null) {
                Activity owned = page;
                try { onMain(owned::finish); } catch (Throwable error) { fail("ui_cleanup", error); }
            }
            try { closeOwnMain(); } catch (Throwable error) { fail("main_cleanup", error); }
            try {
                if (pluginBefore != null && !pluginBefore.equals(pluginState(proot.getRootfsDir())))
                    throw new AssertionError("插件清单/安全模式/禁用标记在测试期间发生变化；自测不覆盖并发真实修改");
                if (webGeneration != controller.getWebGeneration() || !webAuthUrl.equals(controller.getWebAuthUrl()))
                    throw new AssertionError("真实 Web 代次或鉴权地址在测试期间变化；自测未请求启停 Web");
                if (pluginBefore != null) check("isolation=PASS：用户插件状态指纹及真实 Web 代次/鉴权地址保持不变");
            } catch (Throwable error) { fail("isolation", error); }
            try {
                JSONObject report = new JSONObject().put("result", failures.isEmpty() ? "PASS" : "FAIL")
                        .put("elapsedMs", System.currentTimeMillis() - started).put("checks", checks)
                        .put("failures", new org.json.JSONArray(failures));
                write(new File(folder, "report.json"), report.toString(2));
                synchronized (transcript) { write(new File(folder, "terminal.log"), transcript.toString()); }
                output.putString("report", new File(folder, "report.json").getAbsolutePath());
            } catch (Throwable error) { fail("report", error); }
            output.putString("result", failures.isEmpty() ? "PASS：指定自测完成" : "FAIL：请查看 checks/failures");
            output.putString("checks", checks.toString());
            if (!failures.isEmpty()) output.putString("failures", failures.toString());
            finish(failures.isEmpty() ? 0 : 1, output);
        }
    }

    /** MIUI 后台禁止拉起非 exported 宿主时，同步 startActivity 等不到回调；先由 shell 取前台。 */
    private void foregroundMain() throws Exception {
        phase("前台准备：shell 启动自测 Main（15 秒期限）");
        ActivityMonitor monitor = addMonitor(MainActivity.class.getName(), null, false);
        AtomicReference<ParcelFileDescriptor> pipe = new AtomicReference<>();
        AtomicBoolean abandoned = new AtomicBoolean();
        long deadline = SystemClock.elapsedRealtime() + LAUNCH_TIMEOUT_MS;
        FutureTask<String> command = new FutureTask<>(() -> {
            // 单独任务与随机标记保证只清理本次创建的 Main，不认领用户先前的 Activity。
            ParcelFileDescriptor opened = getUiAutomation().executeShellCommand(
                    "am start -W -f 0x18000000 -n com.dsh.client/com.deepseekharness.app.ui.MainActivity --es "
                            + OWNER_EXTRA + " " + launchOwner);
            pipe.set(opened);
            try (InputStream input = new ParcelFileDescriptor.AutoCloseInputStream(opened)) {
                if (abandoned.get() || Thread.currentThread().isInterrupted())
                    throw new InterruptedException("Main 前台启动已结束等待");
                ByteArrayOutputStream bytes = new ByteArrayOutputStream();
                byte[] buffer = new byte[1024]; int count;
                while ((count = input.read(buffer)) != -1) {
                    if (bytes.size() + count > 16384) throw new IOException("Main 启动响应超过自测上限");
                    bytes.write(buffer, 0, count);
                }
                return new String(bytes.toByteArray(), StandardCharsets.UTF_8);
            } finally { pipe.compareAndSet(opened, null); }
        });
        Thread worker = new Thread(command, "fragment-self-foreground"); worker.setDaemon(true);
        try {
            worker.start();
            String response = command.get(remaining(deadline), TimeUnit.MILLISECONDS);
            if (response.contains("Error:") || response.contains("Exception"))
                throw new IOException("Main 前台启动失败：" + SensitiveData.redact(response));
            Activity observed = waitForMonitorWithTimeout(monitor, remaining(deadline));
            if (!(observed instanceof MainActivity) || !owned(observed))
                throw new IOException("15 秒内未观察到自测 Main；可能被后台限制或首次引导门禁拦截");
            ownMain = (MainActivity) observed;
            awaitVisible(ownMain, deadline, "Main 在 15 秒内未取得前台");
        } catch (java.util.concurrent.TimeoutException timeout) {
            throw new IOException("Main 前台启动超过 15 秒，已结束等待", timeout);
        } finally {
            Activity last = monitor.getLastActivity();
            if (ownMain == null && last instanceof MainActivity && owned(last)) ownMain = (MainActivity) last;
            removeMonitor(monitor);
            abandoned.set(true);
            command.cancel(true);
            ParcelFileDescriptor opened = pipe.getAndSet(null);
            if (opened != null) try { opened.close(); } catch (IOException ignored) { }
        }
    }

    private FragmentSessionTestActivity startTestHost(Context app) throws Exception {
        phase("前台准备：打开独立测试宿主（ActivityMonitor 最多 15 秒）");
        ActivityMonitor monitor = addMonitor(FragmentSessionTestActivity.class.getName(), null, false);
        long deadline = SystemClock.elapsedRealtime() + LAUNCH_TIMEOUT_MS;
        FragmentSessionTestActivity opened = null;
        boolean ready = false;
        try {
            onMain(() -> ownMain.startActivity(new Intent(app, FragmentSessionTestActivity.class)
                    .putExtra(OWNER_EXTRA, launchOwner)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_MULTIPLE_TASK)), remaining(deadline));
            Activity observed = waitForMonitorWithTimeout(monitor, remaining(deadline));
            if (!(observed instanceof FragmentSessionTestActivity) || !owned(observed))
                throw new IOException("测试宿主在 15 秒内未打开；可能被系统后台启动限制拦截，已结束等待");
            opened = (FragmentSessionTestActivity) observed;
            awaitVisible(opened, deadline, "测试宿主在 15 秒内未进入前台");
            ready = true;
            return opened;
        } finally {
            Activity last = opened == null ? monitor.getLastActivity() : opened;
            removeMonitor(monitor);
            if (!ready && last instanceof FragmentSessionTestActivity && owned(last)) onMain(last::finish);
        }
    }

    private boolean owned(Activity activity) {
        return activity.getIntent() != null && launchOwner.equals(activity.getIntent().getStringExtra(OWNER_EXTRA));
    }

    private void awaitVisible(Activity activity, long deadline, String message) throws Exception {
        while (SystemClock.elapsedRealtime() < deadline) {
            boolean[] visible = {false};
            onMain(() -> visible[0] = !activity.isFinishing() && !activity.isDestroyed() && activity.hasWindowFocus(), remaining(deadline));
            if (visible[0]) return;
            Thread.sleep(25);
        }
        throw new IOException(message);
    }

    private static long remaining(long deadline) throws java.util.concurrent.TimeoutException {
        long millis = deadline - SystemClock.elapsedRealtime();
        if (millis <= 0) throw new java.util.concurrent.TimeoutException("Activity 启动期限已用完");
        return millis;
    }

    private void closeOwnMain() throws Exception {
        MainActivity main = ownMain;
        if (main == null) return;
        if (!owned(main)) throw new IOException("拒绝关闭非本次自测创建的 Main");
        phase("自测前关闭 ownMain，等待生命周期收尾");
        long deadline = SystemClock.elapsedRealtime() + LAUNCH_TIMEOUT_MS;
        onMain(main::finish, remaining(deadline));
        while (SystemClock.elapsedRealtime() < deadline) {
            boolean[] destroyed = {false};
            onMain(() -> destroyed[0] = main.isDestroyed(), remaining(deadline));
            if (destroyed[0]) { ownMain = null; return; }
            Thread.sleep(25);
        }
        throw new IOException("ownMain 在 15 秒内未销毁，未开始页面断言");
    }

    private void phase(String message) {
        Bundle status = new Bundle(); status.putString("phase", message); sendStatus(1, status);
    }
    private void check(String line) {
        int separator = line.indexOf('=');
        if (separator <= 0) throw new IllegalArgumentException("自测结果缺少键名");
        try { checks.put(line.substring(0, separator), SensitiveData.redact(line.substring(separator + 1))); }
        catch (org.json.JSONException error) { throw new IllegalStateException(error); }
        phase(line);
    }
    private void fail(String key, Throwable error) {
        String detail = SensitiveData.redact(android.util.Log.getStackTraceString(error));
        failures.add(key + ": " + detail);
        check(key + "=FAIL：" + SensitiveData.redact(String.valueOf(error.getMessage())));
    }
    private void appendTranscript(String text) {
        synchronized (transcript) {
            transcript.append(text);
            if (transcript.length() > 65536) transcript.delete(0, transcript.length() - 65536);
        }
    }
    private static void write(File file, String text) throws IOException {
        try (FileOutputStream stream = new FileOutputStream(file)) {
            stream.write(SensitiveData.redact(text).getBytes(StandardCharsets.UTF_8));
        }
    }
    private void onMain(Runnable work) {
        onMain(work, LAUNCH_TIMEOUT_MS);
    }
    private void onMain(Runnable work, long timeoutMs) {
        FutureTask<Void> task = new FutureTask<>(work, null);
        Handler main = new Handler(Looper.getMainLooper());
        if (!main.post(task)) throw new AssertionError("主线程已停止，无法执行自测操作");
        try { task.get(timeoutMs, TimeUnit.MILLISECONDS); }
        catch (InterruptedException error) {
            Thread.currentThread().interrupt(); throw new AssertionError("主线程自测等待被中断", error);
        } catch (Exception error) { throw new AssertionError("主线程自测操作失败或超过等待期限", error); }
        finally { task.cancel(false); main.removeCallbacks(task); }
    }

    /** 只读用户插件状态指纹；不遍历包内容、不读取 API key，也不运行插件管理脚本。 */
    private static Map<String, String> pluginState(File rootfs) throws Exception {
        File home = new File(rootfs, "root/.dsh");
        Map<String, String> state = new TreeMap<>();
        for (String name : new String[]{"package.json", "profiles/web/package.json", "plugin-safe-mode.json"})
            state.put(name, fingerprint(new File(home, name)));
        File modules = new File(home, "profiles/web/node_modules");
        if (modules.isDirectory()) {
            File[] entries = modules.listFiles();
            if (entries == null) throw new IOException("无法读取插件禁用标记");
            for (File entry : entries) {
                if (entry.getName().endsWith(".disabled")) state.put("disabled/" + entry.getName(), fingerprint(entry));
                else if (entry.getName().startsWith("@") && entry.isDirectory()) {
                    File[] scoped = entry.listFiles((dir, name) -> name.endsWith(".disabled"));
                    if (scoped == null) throw new IOException("无法读取作用域插件禁用标记");
                    for (File marker : scoped) state.put("disabled/" + entry.getName() + "/" + marker.getName(), fingerprint(marker));
                }
            }
        }
        return state;
    }
    private static String fingerprint(File file) throws Exception {
        if (!file.exists()) return "absent";
        if (!file.isFile() || file.length() > 8 * 1024 * 1024) throw new IOException("插件状态文件无法正常读取");
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        try (FileInputStream stream = new FileInputStream(file)) {
            byte[] buffer = new byte[8192]; int count;
            while ((count = stream.read(buffer)) != -1) digest.update(buffer, 0, count);
        }
        return android.util.Base64.encodeToString(digest.digest(), android.util.Base64.NO_WRAP);
    }
}
