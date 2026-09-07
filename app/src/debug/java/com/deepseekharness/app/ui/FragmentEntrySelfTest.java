package com.deepseekharness.app.ui;

import android.app.Activity;
import android.app.Instrumentation;
import android.os.Bundle;
import android.view.View;
import android.widget.Switch;
import android.widget.TextView;
import androidx.fragment.app.Fragment;
import androidx.fragment.app.FragmentActivity;
import androidx.lifecycle.MutableLiveData;
import androidx.lifecycle.ViewModelProvider;
import androidx.recyclerview.widget.RecyclerView;
import com.deepseekharness.app.core.PluginRepository;
import com.deepseekharness.app.R;
import org.json.JSONObject;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;

/**
 * 调试入口：在 instrumentation 工作线程调用 run(driver, 空测试 Activity, 容器 ID)。
 * 替换鉴权/打开页面/刷新边界；不启动 Web、不更改实际插件。调用方提供独立测试 Activity。
 */
public final class FragmentEntrySelfTest {
    private FragmentEntrySelfTest() { }

    public static String run(Instrumentation driver, FragmentActivity host, int container) throws Exception {
        return run(driver, host, container, ignored -> { });
    }

    static String run(Instrumentation driver, FragmentActivity host, int container, java.util.function.Consumer<String> result) throws Exception {
        EntryProbe entry = new EntryProbe();
        PluginProbe plugin = new PluginProbe();
        PluginCacheScope cache = new PluginCacheScope();
        main(driver, cache::capture);
        try {
            show(driver, host, container, entry);
            main(driver, () -> {
                for (int i = 0; i < 30; i++) entry.requireView().findViewById(R.id.launch_start).performClick();
                require(!entry.requireView().findViewById(R.id.launch_start).isEnabled(), "鉴权中按钮未禁用");
                require(((TextView) entry.requireView().findViewById(R.id.launch_status)).getText().toString().contains("验证"), "缺少鉴权状态");
            });
            until(() -> entry.auth.get() == 1);
            entry.release.countDown();
            until(() -> entry.opened.get() == 1);
            main(driver, () -> {
                for (int i = 0; i < 30; i++) entry.requireView().findViewById(R.id.launch_start).performClick();
            });
            require(entry.auth.get() == 1 && entry.opened.get() == 1, "已打开后重复进入");

            // 销毁视图后重建同一 Fragment，旧回调不能启动页面或解除新请求的锁。
            rebuild(driver, host, entry);
            entry.release = new CountDownLatch(1);
            click(driver, entry);
            until(() -> entry.auth.get() == 2);
            CountDownLatch old = entry.release;
            rebuild(driver, host, entry);
            entry.release = new CountDownLatch(1);
            click(driver, entry);
            until(() -> entry.auth.get() == 3);
            old.countDown();
            entry.authThreads.get(1).join(5000);
            require(!entry.authThreads.get(1).isAlive(), "旧鉴权线程未完成");
            driver.waitForIdleSync();
            require(entry.opened.get() == 1, "旧视图鉴权回调打开了页面");
            main(driver, () -> require(!entry.requireView().findViewById(R.id.launch_start).isEnabled(), "旧回调解除了新请求的锁"));
            entry.release.countDown();
            until(() -> entry.opened.get() == 2);

            rebuild(driver, host, entry);
            entry.throwAuth = true;
            click(driver, entry);
            untilUi(driver, () -> entry.requireView().findViewById(R.id.launch_start).isEnabled());
            main(driver, () -> require(((TextView) entry.requireView().findViewById(R.id.launch_status)).getText().toString().contains("鉴权失败"), "异常未显示可重试状态"));
            require(entry.opened.get() == 2, "鉴权异常仍打开页面");
            entry.throwAuth = false;

            entry.emptyCookie = true;
            click(driver, entry);
            untilUi(driver, () -> entry.requireView().findViewById(R.id.launch_start).isEnabled());
            require(entry.opened.get() == 2, "空 cookie 仍打开页面");
            entry.emptyCookie = false;
            entry.throwOpen = true;
            click(driver, entry);
            untilUi(driver, () -> entry.requireView().findViewById(R.id.launch_start).isEnabled());
            main(driver, () -> require(((TextView) entry.requireView().findViewById(R.id.launch_status)).getText().toString().contains("无法打开"), "Activity 异常未返回可重试状态"));
            entry.throwOpen = false;

            entry.release = new CountDownLatch(1);
            int authBefore = entry.auth.get();
            click(driver, entry);
            until(() -> entry.auth.get() == authBefore + 1);
            main(driver, () -> entry.generation++);
            entry.release.countDown();
            untilUi(driver, () -> entry.requireView().findViewById(R.id.launch_start).isEnabled());
            require(entry.opened.get() == 2, "Web 代次变化后仍打开旧页面");
            result.accept("launch=PASS：连点只进入一次，鉴权失败和旧视图回调可安全返回");

            Bundle installed = new Bundle(); installed.putBoolean("show_installed", true); plugin.setArguments(installed);
            main(driver, cache::invalidate);
            show(driver, host, container, plugin);
            until(() -> plugin.refreshes.get() == 1);
            untilUi(driver, () -> plugin.showsSafeMode(false));
            rebuild(driver, host, plugin);
            require(plugin.refreshes.get() == 1, "普通返回触发多余全量刷新");
            main(driver, () -> {
                host.getSupportFragmentManager().beginTransaction().detach(plugin).commitNow();
                cache.invalidate();
                plugin.fixtureSafeMode = true;
                plugin.blocked = true;
                host.getSupportFragmentManager().beginTransaction().attach(plugin).commitNow();
            });
            Thread.sleep(650);
            require(plugin.refreshes.get() == 1, "安全启动未完成就刷新了旧状态");
            main(driver, () -> require(plugin.showsSafeMode(false), "未刷新前测试缓存不正确"));
            plugin.blocked = false;
            until(() -> plugin.refreshes.get() == 2);
            untilUi(driver, () -> plugin.showsSafeMode(true));
            rebuild(driver, host, plugin);
            require(plugin.refreshes.get() == 2, "同一失效被重复刷新");
            main(driver, () -> {
                host.getSupportFragmentManager().beginTransaction().detach(plugin).commitNow();
                plugin.fixtureSafeMode = false;
                cache.invalidate();
                host.getSupportFragmentManager().beginTransaction().attach(plugin).commitNow();
            });
            until(() -> plugin.refreshes.get() == 3);
            untilUi(driver, () -> plugin.showsSafeMode(false));
            rebuild(driver, host, plugin);
            require(plugin.refreshes.get() == 3, "恢复后的普通返回重复刷新");
            result.accept("plugin=PASS：内存夹具的安全状态、启用开关、恢复按钮同步，刷新次数 1→2→3");
            return "PASS：Launch 连点/空 cookie/鉴权异常/Activity 异常/旧视图/代次变化；Plugin 内存夹具安全状态、开关和恢复按钮同步，刷新次数 1→2→3";
        } finally {
            entry.release.countDown();
            main(driver, () -> {
                try {
                    Fragment current = host.getSupportFragmentManager().findFragmentById(container);
                    if (current != null) host.getSupportFragmentManager().beginTransaction().remove(current).commitNow();
                } finally { cache.restore(plugin.fixtureRepository); }
            });
        }
    }

    private static void show(Instrumentation driver, FragmentActivity host, int container, Fragment fragment) {
        main(driver, () -> host.getSupportFragmentManager().beginTransaction().replace(container, fragment).commitNow());
        driver.waitForIdleSync();
    }
    private static void rebuild(Instrumentation driver, FragmentActivity host, Fragment fragment) {
        main(driver, () -> {
            host.getSupportFragmentManager().beginTransaction().detach(fragment).commitNow();
            host.getSupportFragmentManager().beginTransaction().attach(fragment).commitNow();
        });
        driver.waitForIdleSync();
    }
    private static void click(Instrumentation driver, EntryProbe entry) {
        main(driver, () -> entry.requireView().findViewById(R.id.launch_start).performClick());
    }
    private static void until(BooleanSupplier condition) throws Exception {
        long end = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (!condition.getAsBoolean() && System.nanoTime() < end) Thread.sleep(10);
        require(condition.getAsBoolean(), "等待异步结果超时");
    }
    private static void untilUi(Instrumentation driver, BooleanSupplier condition) throws Exception {
        until(() -> {
            boolean[] result = {false};
            main(driver, () -> result[0] = condition.getAsBoolean());
            return result[0];
        });
    }
    private static void require(boolean okay, String message) { if (!okay) throw new AssertionError(message); }

    /** 把断言传回 instrumentation 线程，避免主线程异常直接杀掉目标进程。 */
    private static void main(Instrumentation driver, Runnable work) {
        Throwable[] failure = {null};
        driver.runOnMainSync(() -> { try { work.run(); } catch (Throwable error) { failure[0] = error; } });
        if (failure[0] != null) throw new AssertionError("主线程自测失败", failure[0]);
    }

    /** 只还原本次测试产生的失效计数；并发真实失效不能被覆盖。 */
    private static final class PluginCacheScope {
        long original;
        int increments;
        Field revision, refreshed;
        void capture() {
            try {
                revision = PluginFragment.class.getDeclaredField("installedRevision"); revision.setAccessible(true);
                refreshed = PluginFragment.class.getDeclaredField("refreshedRevisions"); refreshed.setAccessible(true);
                original = revision.getLong(null);
            } catch (Exception error) { throw new AssertionError(error); }
        }
        void invalidate() { PluginFragment.invalidateInstalledState(); increments++; }
        void restore(PluginRepository fixture) {
            try {
                if (revision.getLong(null) == original + increments) revision.setLong(null, original);
                ((java.util.Map<?, ?>) refreshed.get(null)).remove(fixture);
            } catch (Exception error) { throw new AssertionError(error); }
        }
    }

    public static class EntryProbe extends LaunchFragment {
        final AtomicInteger auth = new AtomicInteger(), opened = new AtomicInteger();
        final java.util.List<Thread> authThreads = new java.util.concurrent.CopyOnWriteArrayList<>();
        volatile CountDownLatch release = new CountDownLatch(1);
        volatile boolean throwAuth, emptyCookie, throwOpen;
        long generation;
        @Override String webEntryUrl() { return "http://127.0.0.1:3080/?token=debug-fixture"; }
        @Override long webEntryGeneration() { return generation; }
        @Override String exchangeWebEntryCookie() {
            CountDownLatch gate = release;
            authThreads.add(Thread.currentThread());
            auth.incrementAndGet();
            if (throwAuth) throw new IllegalStateException("调试鉴权异常");
            try { if (!gate.await(5, TimeUnit.SECONDS)) throw new IllegalStateException("自测未释放鉴权"); }
            catch (InterruptedException error) { Thread.currentThread().interrupt(); throw new IllegalStateException(error); }
            return emptyCookie ? null : "dsh-auth-fixture=debug";
        }
        @Override void openWebEntry(Activity activity, String url, String cookie) {
            if (throwOpen) throw new IllegalStateException("调试 Activity 异常");
            opened.incrementAndGet();
        }
    }

    public static class PluginProbe extends PluginFragment {
        final AtomicInteger refreshes = new AtomicInteger();
        volatile boolean blocked;
        boolean fixtureSafeMode;
        PluginRepository fixtureRepository;
        volatile Throwable fixtureFailure;
        @Override boolean pluginRefreshBlocked() { return blocked; }
        @Override void syncInstalledState() {
            try {
                // 独立测试宿主的 ViewModel；只发布内存夹具，不调用 refresh/safeMode/runPluginManager。
                fixtureRepository = new ViewModelProvider(requireActivity()).get(PluginRepository.class);
                Constructor<PluginRepository.Item> item = PluginRepository.Item.class.getDeclaredConstructor(JSONObject.class);
                item.setAccessible(true);
                List<PluginRepository.Item> items = Collections.singletonList(item.newInstance(new JSONObject()
                        .put("name", "dsha-debug-fixture").put("description", "只存在于测试页面内存")
                        .put("version", "0.0.0").put("available", true).put("enabled", !fixtureSafeMode)));
                Field safe = PluginRepository.class.getDeclaredField("safeMode"); safe.setAccessible(true);
                safe.setBoolean(fixtureRepository, fixtureSafeMode);
                Field cached = PluginRepository.class.getDeclaredField("items"); cached.setAccessible(true);
                cached.set(fixtureRepository, items);
                Constructor<PluginRepository.State> state = PluginRepository.State.class.getDeclaredConstructor(List.class, boolean.class, String.class);
                state.setAccessible(true);
                ((MutableLiveData<PluginRepository.State>) fixtureRepository.state()).setValue(
                        state.newInstance(items, false, fixtureSafeMode ? "测试夹具：安全模式" : "测试夹具：普通模式"));
                refreshes.incrementAndGet();
            } catch (Exception error) { fixtureFailure = error; refreshes.incrementAndGet(); }
        }
        boolean showsSafeMode(boolean safe) {
            if (fixtureFailure != null) throw new AssertionError("发布插件夹具失败", fixtureFailure);
            View root = getView();
            if (root == null || fixtureRepository == null || fixtureRepository.isSafeMode() != safe) return false;
            if ((root.findViewById(R.id.btnPluginRestore).getVisibility() == View.VISIBLE) != safe) return false;
            RecyclerView list = root.findViewById(R.id.pluginList);
            RecyclerView.ViewHolder holder = list.findViewHolderForAdapterPosition(0);
            if (holder == null) return false;
            Switch toggle = holder.itemView.findViewById(R.id.pluginSwitch);
            TextView status = holder.itemView.findViewById(R.id.pluginStatus);
            return toggle.isChecked() != safe && status.getText().toString().contains(safe ? "已禁用" : "已启用");
        }
    }
}
