package com.deepseekharness.app.core;

import android.app.Activity;
import android.app.Application;
import android.app.Instrumentation;
import android.content.Context;
import android.content.ContextWrapper;
import android.content.SharedPreferences;
import android.os.Bundle;
import androidx.lifecycle.Lifecycle;
import androidx.lifecycle.LifecycleOwner;
import androidx.lifecycle.LifecycleRegistry;
import androidx.lifecycle.ViewModel;
import androidx.lifecycle.ViewModelProvider;
import androidx.lifecycle.ViewModelStore;
import com.deepseekharness.app.BuildConfig;
import com.deepseekharness.app.util.UpdatePolicy;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.concurrent.Callable;
import java.util.concurrent.Executor;

/** 更新流程隔离自测：虚拟清单、独立私有目录；不会联网、下载或唤起安装器。 */
public final class UpdateFlowInstrumentation extends Instrumentation {
    private Sandbox sandbox;
    private Feed feed;
    private UpdateEngine engine;
    private int assertions;
    private static final String HASH = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa";

    @Override public void onCreate(Bundle arguments) { super.onCreate(arguments); start(); }
    @Override public void onStart() {
        Bundle result = new Bundle();
        try {
            sandbox = new Sandbox(getTargetContext(), "update-flow-" + System.nanoTime());
            feed = new Feed();
            startup();
            channelFailures();
            legacySources();
            progressStages();
            installHandoff();
            check(sandbox.services == 0, "自测不能启动下载服务");
            result.putString("result", "PASS: " + assertions + " assertions");
        } catch (Throwable error) {
            result.putString("result", "FAIL: " + error);
            result.putString("trace", android.util.Log.getStackTraceString(error));
        } finally {
            if (sandbox != null) sandbox.cleanup();
        }
        finish(result.getString("result").startsWith("PASS") ? Activity.RESULT_OK : Activity.RESULT_CANCELED, result);
    }

    private void check(boolean value, String message) { assertions++; if (!value) throw new AssertionError(message); }
    private void idle() throws Exception {
        long end = System.currentTimeMillis() + 5000;
        while (engine.state().getValue().busy && System.currentTimeMillis() < end) Thread.sleep(10);
        waitForIdleSync();
        check(!engine.state().getValue().busy, "虚拟检查未完成");
    }
    private void reset(String selected, String source, String releaseChannel, String phase) throws Exception {
        sandbox.preferences.edit().clear().putString("channel", selected).commit();
        if (source != null) {
            JSONObject task = release(releaseChannel, 2);
            JSONObject artifact = task.getJSONArray("artifacts").getJSONObject(0);
            JSONObject saved = new JSONObject().put("code", task.getInt("versionCode"))
                    .put("version", task.getString("version")).put("channel", releaseChannel)
                    .put("checkedChannel", source).put("pageUrl", task.getString("pageUrl"));
            for (String key : new String[]{"flavor", "minSdk", "abi", "url", "sha256", "bytes"}) saved.put(key, artifact.get(key));
            sandbox.preferences.edit().putString("task", saved.toString()).putString("phase", phase).commit();
            try (FileOutputStream out = new FileOutputStream(sandbox.apk())) { out.write(new byte[]{1, 2, 3, 4}); }
        }
        runOnMainSync(() -> engine = new UpdateEngine(sandbox, feed));
    }
    private void startup() throws Exception {
        feed.body = new JSONObject().put("schemaVersion", 1).put("releases", new JSONArray()
                .put(release(UpdatePolicy.STABLE, 1)).put(release(UpdatePolicy.PREVIEW, 2))).toString();
        reset(UpdatePolicy.PREVIEW, null, null, "");
        runOnMainSync(() -> engine.checkOnStartup(false));
        check(feed.requests == 0, "关闭启动开关仍发出请求");
        runOnMainSync(() -> { engine.checkOnStartup(true); engine.checkOnStartup(true); });
        idle();
        check(feed.requests == 1, "启动/旋转重复请求清单");
        check(UpdatePolicy.PREVIEW.equals(engine.state().getValue().release.channel), "启动未使用所选预览通道");
        runOnMainSync(() -> {
            UpdatePolicy.Release notice = engine.startupNotice();
            check(notice != null, "自动检查成功没有可见提示候选");
            check(engine.startupNotice() == notice, "未显示的提示被 Web/旋转吞掉");
            engine.markStartupNoticeShown(notice);
            check(engine.startupNotice() == null, "提示被重复领取");
            engine.checkOnStartup(true);
        });
        check(feed.requests == 1, "从 Web 返回或旋转又检查");
        reset(UpdatePolicy.STABLE, null, null, "");
        runOnMainSync(() -> engine.checkOnStartup(true)); idle();
        check(UpdatePolicy.STABLE.equals(engine.state().getValue().release.channel), "启动忽略所选稳定通道");
        feed.fail = true;
        reset(UpdatePolicy.PREVIEW, null, null, "");
        runOnMainSync(() -> engine.checkOnStartup(true)); idle();
        runOnMainSync(() -> check(engine.startupNotice() == null, "网络失败误报新版本"));
    }
    private void channelFailures() throws Exception {
        // 两种候选都来自预览查询；稳定候选也不能直接冒充稳定通道的查询结果。
        for (String releaseChannel : new String[]{UpdatePolicy.PREVIEW, UpdatePolicy.STABLE}) {
            reset(UpdatePolicy.PREVIEW, UpdatePolicy.PREVIEW, releaseChannel, "ready");
            runOnMainSync(engine::check); idle();
            check(engine.state().getValue().apk != null, "同通道离线重试丢失已下载包");
            runOnMainSync(() -> engine.setChannel(UpdatePolicy.STABLE)); idle();
            UpdateRepository.State failed = engine.state().getValue();
            check(failed.release == null && failed.apk == null, "新通道失败暴露旧候选/安装入口");
            check(UpdatePolicy.PREVIEW.equals(failed.sourceChannel) && failed.message.contains("不匹配"), "没有明确旧候选来源和失配");
            check(!engine.shouldResume(), "失配候选被自动恢复下载");
            runOnMainSync(engine::download);
            check(sandbox.services == 0, "失配候选启动了下载");
            try { engine.installableApk(); throw new AssertionError("失配候选可安装"); }
            catch (IOException expected) { check(expected.getMessage().contains("通道"), "没有在安装前拒绝通道失配"); }
            runOnMainSync(() -> engine = new UpdateEngine(sandbox, feed));
            check(engine.state().getValue().apk == null && engine.state().getValue().message.contains("不匹配"), "进程恢复丢失通道来源");
            runOnMainSync(() -> engine.setChannel(UpdatePolicy.PREVIEW)); idle();
            check(engine.state().getValue().apk != null, "切回原通道后离线包不可用");
        }
        reset(UpdatePolicy.STABLE, UpdatePolicy.PREVIEW, UpdatePolicy.PREVIEW, "downloading");
        check(!engine.shouldResume(), "进程恢复续传了其他通道任务");
        reset(UpdatePolicy.PREVIEW, UpdatePolicy.PREVIEW, UpdatePolicy.PREVIEW, "paused");
        File part = new File(sandbox.apk().getParentFile(), HASH + ".part");
        try (FileOutputStream out = new FileOutputStream(part)) { out.write(new byte[]{1, 2}); }
        runOnMainSync(engine::check); idle();
        check(engine.state().getValue().release != null && engine.state().getValue().downloaded == 2, "同通道失败丢失续传候选或进度");
        feed.fail = false;
        runOnMainSync(() -> engine.setChannel(UpdatePolicy.STABLE)); idle();
        check(UpdatePolicy.STABLE.equals(engine.state().getValue().sourceChannel) && engine.state().getValue().apk == null,
                "成功切换通道后沿用了旧包校验状态");
        feed.body = "{\"schemaVersion\":1,\"releases\":[]}";
        runOnMainSync(engine::check); idle();
        check(engine.state().getValue().release == null, "有效空清单仍显示旧候选");
        feed.fail = true;
    }

    private void legacySources() throws Exception {
        feed.fail = true;
        for (String selected : new String[]{UpdatePolicy.STABLE, UpdatePolicy.PREVIEW}) {
            for (String releaseChannel : new String[]{UpdatePolicy.STABLE, UpdatePolicy.PREVIEW}) {
                reset(selected, UpdatePolicy.PREVIEW, releaseChannel, "ready");
                JSONObject saved = new JSONObject(sandbox.preferences.getString("task", "{}"));
                saved.remove("checkedChannel"); // 旧任务实际只保存发布通道。
                sandbox.preferences.edit().putString("task", saved.toString()).commit();
                runOnMainSync(() -> engine = new UpdateEngine(sandbox, feed));
                boolean known = UpdatePolicy.PREVIEW.equals(releaseChannel);
                boolean usable = known && UpdatePolicy.PREVIEW.equals(selected);
                check((engine.state().getValue().apk != null) == usable, "旧任务被错误归入当前通道");
                check(known ? UpdatePolicy.PREVIEW.equals(engine.state().getValue().sourceChannel)
                        : engine.state().getValue().sourceChannel == null, "迁移猜测了无法确定的来源");
                if (!known) {
                    check(engine.state().getValue().message.contains("联网重新检查"), "未知来源缺少联网提示");
                    runOnMainSync(engine::download);
                    try { engine.installableApk(); throw new AssertionError("来源未知的旧包可安装"); }
                    catch (IOException expected) { check(expected.getMessage().contains("通道"), "旧包未在通道门禁拒绝"); }
                }
                runOnMainSync(engine::check); idle();
                check((engine.state().getValue().apk != null) == usable, "离线检查改变了来源的可信程度");
                JSONObject persisted = new JSONObject(sandbox.preferences.getString("task", "{}"));
                check(persisted.has("checkedChannel") && (known ? UpdatePolicy.PREVIEW : "").equals(persisted.getString("checkedChannel")),
                        "来源迁移或未知标记未持久化");
                runOnMainSync(() -> engine = new UpdateEngine(sandbox, feed));
                check((engine.state().getValue().apk != null) == usable && sandbox.apk().isFile(), "重建丢失文件或误开放旧包");
                if (!known) {
                    String other = UpdatePolicy.PREVIEW.equals(selected) ? UpdatePolicy.STABLE : UpdatePolicy.PREVIEW;
                    runOnMainSync(() -> engine.setChannel(other)); idle();
                    check(engine.state().getValue().apk == null && engine.state().getValue().sourceChannel == null, "切通道让未知来源变成可用");
                }
            }
        }
        // 已记录的未知/损坏来源不能在下次恢复时被当作旧字段缺失再次推断。
        for (Object invalid : new Object[]{"", "broken", JSONObject.NULL}) {
            reset(UpdatePolicy.PREVIEW, UpdatePolicy.PREVIEW, UpdatePolicy.PREVIEW, "downloading");
            JSONObject saved = new JSONObject(sandbox.preferences.getString("task", "{}"));
            saved.put("checkedChannel", invalid);
            sandbox.preferences.edit().putString("task", saved.toString()).commit();
            runOnMainSync(() -> engine = new UpdateEngine(sandbox, feed));
            check(!engine.shouldResume() && engine.state().getValue().release == null, "损坏来源被自动续传");
            runOnMainSync(engine::check); idle();
            runOnMainSync(() -> engine = new UpdateEngine(sandbox, feed));
            check(engine.state().getValue().sourceChannel == null && engine.state().getValue().release == null, "未知标记在重建后被重新推断");
        }
        reset(UpdatePolicy.PREVIEW, UpdatePolicy.PREVIEW, UpdatePolicy.STABLE, "ready");
        JSONObject saved = new JSONObject(sandbox.preferences.getString("task", "{}")); saved.remove("checkedChannel");
        sandbox.preferences.edit().putString("task", saved.toString()).commit();
        runOnMainSync(() -> engine = new UpdateEngine(sandbox, feed));
        feed.fail = false;
        feed.body = new JSONObject().put("schemaVersion", 1).put("releases", new JSONArray().put(release(UpdatePolicy.STABLE, 2))).toString();
        runOnMainSync(engine::check); idle();
        check(engine.state().getValue().release != null && UpdatePolicy.PREVIEW.equals(engine.state().getValue().sourceChannel), "联网检查未确认当前查询来源");
        check(sandbox.apk().isFile(), "联网确认清除了保留的旧文件");
        feed.fail = true;
        runOnMainSync(engine::check); idle();
        check(engine.state().getValue().release != null && UpdatePolicy.PREVIEW.equals(engine.state().getValue().sourceChannel), "重新确认的来源在离线检查后丢失");
    }

    private void progressStages() throws Exception {
        reset(UpdatePolicy.PREVIEW, UpdatePolicy.PREVIEW, UpdatePolicy.PREVIEW, "ready");
        android.view.View[] root = new android.view.View[1];
        runOnMainSync(() -> {
            Context themed = new androidx.appcompat.view.ContextThemeWrapper(getTargetContext(), com.deepseekharness.app.R.style.Theme_DeepseekHarness);
            root[0] = android.view.LayoutInflater.from(themed).inflate(com.deepseekharness.app.R.layout.activity_update, null);
            engine.checkOnStartup(true);
            check(engine.state().getValue().stage == UpdateRepository.Stage.CHECKING, "自动检查未标注 CHECKING 阶段");
            check(engine.state().getValue().downloaded == engine.state().getValue().total, "夹具应保留已下载的完整包");
            renderProgress(root[0], engine.state().getValue(), false, false, true, true, false, -1);
        });
        idle();
        runOnMainSync(() -> {
            renderProgress(root[0], engine.state().getValue(), false, false, false, true, true, -1);
            engine.check();
            renderProgress(root[0], engine.state().getValue(), false, false, true, true, false, -1);
        });
        idle();
        runOnMainSync(() -> {
            UpdateRepository.State ready = engine.state().getValue();
            UpdateRepository.State downloading = new UpdateRepository.State("下载夹具", UpdateRepository.Stage.DOWNLOADING,
                    2, 4, ready.release, null, ready.channel, ready.sourceChannel);
            renderProgress(root[0], downloading, false, false, true, false, true, 50);
            UpdateRepository.State checking = new UpdateRepository.State("缓存部分下载后检查", UpdateRepository.Stage.CHECKING,
                    2, 4, ready.release, null, ready.channel, ready.sourceChannel);
            renderProgress(root[0], checking, false, false, true, true, false, -1);
            UpdateRepository.State verifying = new UpdateRepository.State("下载后核验", UpdateRepository.Stage.VERIFYING,
                    4, 4, ready.release, null, ready.channel, ready.sourceChannel);
            renderProgress(root[0], verifying, false, false, true, true, false, -1);
            renderProgress(root[0], ready, true, true, true, true, false, -1);
            check(!root[0].findViewById(com.deepseekharness.app.R.id.update_check).isEnabled()
                    && !root[0].findViewById(com.deepseekharness.app.R.id.update_install).isEnabled(), "安装校验时操作未锁定");
            // READY 结果等待页面领取时已结束校验，不能继续显示转圈。
            renderProgress(root[0], ready, true, false, false, true, true, -1);
            check(!root[0].findViewById(com.deepseekharness.app.R.id.update_install).isEnabled(), "待领取安装结果重复开放按钮");
            renderProgress(root[0], ready, false, false, false, true, true, -1);
            check(root[0].findViewById(com.deepseekharness.app.R.id.update_install).isEnabled(), "空闲时安装入口未恢复");
        });
    }
    private void renderProgress(android.view.View root, UpdateRepository.State state, boolean installing, boolean verifying,
                                boolean visible, boolean indeterminate, boolean bytes, int percent) {
        try {
            java.lang.reflect.Method render = Class.forName("com.deepseekharness.app.ui.UpdateUi").getDeclaredMethod("render",
                    android.view.View.class, UpdateRepository.State.class, boolean.class, boolean.class);
            render.setAccessible(true); render.invoke(null, root, state, installing, verifying);
            android.widget.ProgressBar bar = root.findViewById(com.deepseekharness.app.R.id.update_progress);
            check((bar.getVisibility() == android.view.View.VISIBLE) == visible, "进度动画可见性与任务阶段不符");
            check(bar.isIndeterminate() == indeterminate, "检查/校验复用了下载百分比");
            check((root.findViewById(com.deepseekharness.app.R.id.update_bytes).getVisibility() == android.view.View.VISIBLE) == bytes,
                    "非下载阶段冒充字节传输进度");
            if (percent >= 0) check(bar.getProgress() == percent, "真实下载进度未保留");
        } catch (ReflectiveOperationException error) { throw new AssertionError("无法渲染更新页自测", error); }
    }

    private void installHandoff() throws Exception {
        reset(UpdatePolicy.PREVIEW, UpdatePolicy.PREVIEW, UpdatePolicy.PREVIEW, "ready");
        Queue queue = new Queue();
        ViewModelStore store = new ViewModelStore();
        UpdateRepository[] retained = new UpdateRepository[1];
        int[] oldReady = {0}, newReady = {0};
        runOnMainSync(() -> {
            ViewModelProvider.Factory factory = factory(queue, () -> sandbox.apk());
            UpdateRepository old = new ViewModelProvider(store, factory).get(UpdateRepository.class);
            Owner oldOwner = new Owner(); oldOwner.lifecycle.setCurrentState(Lifecycle.State.RESUMED);
            old.installation().observe(oldOwner, state -> { if (state.apk != null) oldReady[0]++; });
            old.requestInstall(); old.requestInstall();
            check(queue.tasks.size() == 1, "重复点击启动了多个校验");
            oldOwner.lifecycle.setCurrentState(Lifecycle.State.DESTROYED);
            UpdateRepository next = new ViewModelProvider(store, factory).get(UpdateRepository.class);
            check(old == next && next.installationPending(), "重建丢失正在校验的请求");
            Owner nextOwner = new Owner(); nextOwner.lifecycle.setCurrentState(Lifecycle.State.STARTED);
            next.installation().observe(nextOwner, state -> { if (state.apk != null) newReady[0]++; });
            retained[0] = next;
        });
        queue.run(); waitForIdleSync();
        runOnMainSync(() -> {
            UpdateRepository next = retained[0];
            check(oldReady[0] == 0 && newReady[0] == 1, "结果未交给新生命周期所有者");
            check(next.installationPending(), "后台校验结果未保留到页面返回");
            check(next.takeInstallReady() != null && next.takeInstallReady() == null, "安装结果丢失或可重复领取");
            next.requestInstall();
            store.clear(); // 用户离开页面，完成回调不得再次触发安装。
        });
        queue.run(); waitForIdleSync();
        runOnMainSync(() -> check(retained[0].takeInstallReady() == null, "已离开的页面仍可领取安装"));

        Queue interrupted = new Queue();
        runOnMainSync(() -> {
            retained[0] = repository(interrupted, () -> sandbox.apk());
            retained[0].restoreInterruptedInstall(true);
            check(!retained[0].installationPending() && retained[0].installation().getValue().error.contains("重试"), "进程重建未给明确重试状态");
            retained[0].requestInstall();
        });
        interrupted.run(); waitForIdleSync();
        runOnMainSync(() -> check(retained[0].takeInstallReady() != null, "进程重建后无法显式重试"));

        Queue stale = new Queue();
        runOnMainSync(() -> {
            retained[0] = repository(stale, () -> sandbox.apk()); retained[0].requestInstall();
            engine.setChannel(UpdatePolicy.STABLE); // 模拟另一个入口改变引擎状态。
        });
        idle(); stale.run(); waitForIdleSync();
        runOnMainSync(() -> check(retained[0].takeInstallReady() == null
                && retained[0].installation().getValue().error.contains("候选"), "校验后领取时未拒绝过期候选"));

        reset(UpdatePolicy.PREVIEW, UpdatePolicy.PREVIEW, UpdatePolicy.PREVIEW, "ready");
        Queue retry = new Queue(); int[] calls = {0};
        runOnMainSync(() -> {
            retained[0] = repository(retry, () -> { if (calls[0]++ == 0) throw new IOException("模拟校验失败"); return sandbox.apk(); });
            retained[0].requestInstall();
        });
        retry.run(); waitForIdleSync();
        runOnMainSync(() -> {
            check(!retained[0].installationPending() && retained[0].installation().getValue().error.contains("模拟校验失败"), "校验失败状态未保留");
            retained[0].requestInstall();
        });
        retry.run(); waitForIdleSync();
        runOnMainSync(() -> check(retained[0].takeInstallReady() != null, "校验失败后无法重试"));
    }
    private UpdateRepository repository(Executor queue, Callable<File> verify) {
        return new UpdateRepository((Application) getTargetContext().getApplicationContext(), engine, queue, verify);
    }
    private ViewModelProvider.Factory factory(Executor queue, Callable<File> verify) {
        return new ViewModelProvider.Factory() {
            @Override @SuppressWarnings("unchecked") public <T extends ViewModel> T create(Class<T> type) { return (T) repository(queue, verify); }
        };
    }
    private static final class Owner implements LifecycleOwner {
        final LifecycleRegistry lifecycle = new LifecycleRegistry(this);
        @Override public Lifecycle getLifecycle() { return lifecycle; }
    }
    private static final class Queue implements Executor {
        final ArrayDeque<Runnable> tasks = new ArrayDeque<>();
        @Override public void execute(Runnable task) { tasks.add(task); }
        void run() { tasks.remove().run(); }
    }
    private static JSONObject release(String channel, int increment) throws Exception {
        JSONObject apk = new JSONObject().put("flavor", BuildConfig.LOW_ANDROID ? "low" : "standard")
                .put("minSdk", BuildConfig.LOW_ANDROID ? 23 : 30).put("abi", "arm64-v8a")
                .put("url", "https://example.invalid/update.apk").put("sha256", HASH).put("bytes", 4);
        return new JSONObject().put("versionCode", BuildConfig.VERSION_CODE + increment).put("version", "validation-" + increment)
                .put("channel", channel).put("pageUrl", "https://example.invalid/release").put("artifacts", new JSONArray().put(apk));
    }
    private static final class Feed implements UpdateEngine.Transport {
        volatile String body;
        volatile boolean fail;
        volatile int requests;
        @Override public HttpURLConnection open(String url, long offset) throws Exception {
            requests++;
            if (!UpdateEngine.FEED.equals(url) || offset != 0) throw new AssertionError("测试不能发出下载请求");
            if (fail) throw new IOException("模拟离线");
            return new HttpURLConnection(new URL(url)) {
                @Override public void disconnect() { }
                @Override public boolean usingProxy() { return false; }
                @Override public void connect() { }
                @Override public java.io.InputStream getInputStream() { return new ByteArrayInputStream(body.getBytes(StandardCharsets.UTF_8)); }
            };
        }
    }
    private static final class Sandbox extends ContextWrapper {
        final File folder;
        final SharedPreferences preferences;
        final String name;
        int services;
        Sandbox(Context context, String name) {
            super(context); this.name=name;
            folder = new File(context.getCacheDir(), name);
            new File(folder, "updates").mkdirs();
            preferences = context.getSharedPreferences(name, 0);
        }
        File apk() { return new File(folder, "updates/" + HASH + ".apk"); }
        @Override public File getFilesDir() { return folder; }
        @Override public SharedPreferences getSharedPreferences(String name, int mode) { return preferences; }
        @Override public android.content.ComponentName startService(android.content.Intent intent) { services++; throw new AssertionError("不能启动服务"); }
        @Override public android.content.ComponentName startForegroundService(android.content.Intent intent) { services++; throw new AssertionError("不能启动服务"); }
        void cleanup() {
            preferences.edit().clear().commit();
            if (android.os.Build.VERSION.SDK_INT >= 24) getBaseContext().deleteSharedPreferences(name);
            delete(folder);
        }
        private static void delete(File file) {
            File[] children = file.listFiles();
            if (children != null) for (File child : children) delete(child);
            file.delete();
        }
    }
}
