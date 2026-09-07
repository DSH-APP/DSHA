package com.deepseekharness.app.core;

import android.app.Activity;
import android.app.Instrumentation;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Bundle;
import com.deepseekharness.app.BuildConfig;
import com.deepseekharness.app.R;
import com.deepseekharness.app.ui.UpdateActivity;
import com.deepseekharness.app.util.Compat;
import com.deepseekharness.app.util.UpdatePolicy;
import org.json.JSONObject;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.function.BooleanSupplier;

/** 真实 HTTPS 和进程终止验收。只暂存更新任务；归档是禁止安装的独立测试样本。 */
public final class UpdateProcessInstrumentation extends Instrumentation {
    private Bundle args;
    private Activity page;
    private UpdateEngine engine;
    private File control;
    @Override public void onCreate(Bundle args) { super.onCreate(args); this.args = args; start(); }
    private void require(boolean ok, String reason) { if (!ok) throw new AssertionError(reason); }
    private void until(BooleanSupplier ready, String reason) throws Exception {
        long deadline = System.currentTimeMillis() + 120000;
        while (!ready.getAsBoolean() && System.currentTimeMillis() < deadline) Thread.sleep(100);
        require(ready.getAsBoolean(), reason);
    }
    private void phase(String message) { Bundle b = new Bundle(); b.putString("phase", message); sendStatus(1, b); }
    private void shell(String command) throws Exception {
        try (android.os.ParcelFileDescriptor p = getUiAutomation().executeShellCommand(command);
             InputStream input = new android.os.ParcelFileDescriptor.AutoCloseInputStream(p)) { while (input.read() != -1) { } }
    }
    private void open() throws Exception {
        Context base = getTargetContext();
        // 本夹具恢复/注入固定清单；打开主界面时不能被启动自动检查改写。
        Field startupChecked = UpdateEngine.class.getDeclaredField("startupChecked"); startupChecked.setAccessible(true); startupChecked.setBoolean(engine, true);
        shell("am start -W -n com.dsh.client/com.deepseekharness.app.ui.MainActivity");
        page = startActivitySync(new Intent(base, UpdateActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
    }
    private JSONObject saved() throws Exception {
        return new JSONObject(new String(Compat.readAllBytes(control), StandardCharsets.UTF_8));
    }
    private void cleanup() throws Exception {
        if (!control.isFile()) return;
        Context base = getTargetContext();
        JSONObject original = saved();
        String hash = original.getString("sha256");
        require(hash.matches("[a-f0-9]{64}"), "清理标识无效");
        SharedPreferences preferences = base.getSharedPreferences("dsha-updates", 0);
        JSONObject task = new JSONObject(preferences.getString("task", "{}"));
        require(task.optString("sha256").equals(hash), "更新任务已改变，保留现场，不能覆盖");
        if (engine != null) {
            runOnMainSync(engine::cancel);
            until(() -> !engine.state().getValue().busy, "测试下载未停止");
        }
        base.stopService(new Intent(base, com.deepseekharness.app.UpdateDownloadService.class));
        SharedPreferences.Editor edit = preferences.edit().clear();
        JSONObject before = original.getJSONObject("preferences");
        for (java.util.Iterator<String> names = before.keys(); names.hasNext();) {
            String key = names.next(); edit.putString(key, before.getString(key));
        }
        require(edit.commit(), "原更新设置恢复失败");
        for (String suffix : new String[]{".part", ".apk"}) {
            File file = new File(base.getFilesDir(), "updates/" + hash + suffix);
            require(!file.exists() || file.delete(), "测试归档清理失败");
        }
        require(control.delete(), "测试控制文件清理失败");
        Field singleton = UpdateEngine.class.getDeclaredField("instance"); singleton.setAccessible(true); singleton.set(null, null);
        base.getSystemService(android.app.NotificationManager.class).cancel(1004);
    }
    @Override public void onStart() {
        Bundle result = new Bundle(); boolean holding = "hold".equals(args.getString("mode"));
        try {
            Context base = getTargetContext().getApplicationContext();
            control = new File(base.getCacheDir(), "optimization-https-control.json");
            require(!HarnessController.get(base).isWebRunning(), "用户 Web 正在运行，不能做进程终止验收");
            runOnMainSync(() -> engine = UpdateEngine.get(base));
            if ("cleanup".equals(args.getString("mode"))) { cleanup(); result.putString("result", "PASS"); }
            else if (holding) {
                require(!control.exists(), "上一项验收尚未清理");
                require(!engine.state().getValue().busy && !engine.shouldResume(), "已有下载正在进行");
                String hash = args.getString("sha256"), url = args.getString("url");
                require(hash != null && hash.matches("[a-f0-9]{64}"), "测试摘要无效");
                require(url != null && url.matches("https://dsha\\.cc/opt-https-[a-f0-9]{8}\\.apk"), "只允许本次测试地址");
                for (String suffix : new String[]{".part", ".apk"}) require(!new File(base.getFilesDir(), "updates/"+hash+suffix).exists(), "样本已存在，不能覆盖");
                SharedPreferences prefs = base.getSharedPreferences("dsha-updates", 0);
                JSONObject before = new JSONObject();
                for (Map.Entry<String, ?> value : prefs.getAll().entrySet()) {
                    require(value.getValue() instanceof String, "不能备份未知更新设置类型"); before.put(value.getKey(), value.getValue());
                }
                JSONObject marker = new JSONObject().put("preferences", before).put("sha256", hash).put("pid", android.os.Process.myPid());
                try (FileOutputStream out = new FileOutputStream(control)) { out.write(marker.toString().getBytes(StandardCharsets.UTF_8)); out.getFD().sync(); }
                require(prefs.edit().putString("channel", UpdatePolicy.PREVIEW).commit(), "测试通道保存失败");
                Field channel = UpdateEngine.class.getDeclaredField("channel"); channel.setAccessible(true); channel.set(engine, UpdatePolicy.PREVIEW);
                UpdatePolicy.Release release = new UpdatePolicy.Release(Integer.parseInt(args.getString("code")), "1.2.0-validation", UpdatePolicy.PREVIEW,
                        BuildConfig.LOW_ANDROID ? "low" : "standard", BuildConfig.LOW_ANDROID ? 23 : 30, "arm64-v8a", url,
                        hash, Long.parseLong(args.getString("bytes")), "独立下载验收，禁止安装", "https://dsha.cc/download/");
                Field candidate = UpdateEngine.class.getDeclaredField("candidate"); candidate.setAccessible(true); candidate.set(engine, release);
                Field candidateChannel = UpdateEngine.class.getDeclaredField("candidateChannel"); candidateChannel.setAccessible(true); candidateChannel.set(engine, UpdatePolicy.PREVIEW);
                Field verifiedApk = UpdateEngine.class.getDeclaredField("verifiedApk"); verifiedApk.setAccessible(true); verifiedApk.set(engine, null);
                Method publish = UpdateEngine.class.getDeclaredMethod("finishState", String.class, String.class); publish.setAccessible(true);
                runOnMainSync(() -> { try { publish.invoke(engine,"available","独立 HTTPS 验收"); } catch (Exception error) { throw new RuntimeException(error); } });
                require(UpdatePolicy.PREVIEW.equals(new JSONObject(prefs.getString("task", "{}")).optString("checkedChannel")), "夹具未保存候选查询通道");
                open(); runOnMainSync(engine::download);
                until(() -> engine.state().getValue().downloaded >= 1048576 || !engine.state().getValue().busy, "HTTPS 下载未响应");
                require(engine.state().getValue().busy, engine.state().getValue().message);
                Activity old = page; runOnMainSync(old::finish); page = null;
                shell("input keyevent KEYCODE_HOME");
                phase("KILL_READY pid="+android.os.Process.myPid()+" bytes="+engine.state().getValue().downloaded);
                // Android hidepid 会阻止 run-as 的外部 kill 工具查找 PID；测试进程读取一次性信号后发送同样的 SIGKILL。
                File trigger = new File(base.getCacheDir(), "optimization-https-kill");
                long deadline = System.currentTimeMillis() + 30000;
                while (System.currentTimeMillis() < deadline) {
                    if (trigger.isFile()) { require(trigger.delete(), "测试信号清理失败"); android.os.Process.killProcess(android.os.Process.myPid()); }
                    Thread.sleep(50);
                }
                throw new AssertionError("控制端没有终止进程");
            } else {
                require(control.isFile(), "缺少进程终止前的验收记录");
                require(saved().getInt("pid") != android.os.Process.myPid(), "没有发生实际进程重建");
                require(engine.hasTask(), "进程重建后任务丢失");
                require(UpdatePolicy.PREVIEW.equals(engine.channel()) && UpdatePolicy.PREVIEW.equals(new JSONObject(
                        base.getSharedPreferences("dsha-updates", 0).getString("task", "{}")).optString("checkedChannel")), "进程重建后夹具查询通道丢失");
                require(engine.state().getValue().downloaded > 0, "进程重建后进度丢失");
                long resumed = engine.state().getValue().downloaded;
                open();
                if (!engine.state().getValue().busy && engine.state().getValue().apk == null)
                    runOnMainSync(engine::download);
                until(() -> !engine.state().getValue().busy, "HTTPS 续传没有结束");
                require(engine.state().getValue().apk != null, engine.state().getValue().message);
                File verified = engine.installableApk();
                require(verified.isFile(), "完整 APK 核验失败");
                require(page.findViewById(R.id.update_install).isEnabled(), "最终安装按钮没有就绪");
                result.putString("coverage", "PASS: 实际 PID 改变、HTTPS 保存进度 "+resumed+" bytes、续传完成 "+verified.length()+" bytes、完整摘要及包签名校验；未安装测试包");
                result.putString("result", "PASS");
            }
        } catch (Throwable error) { result.putString("result", "FAIL: "+error); }
        finally {
            if (page != null) { Activity old = page; runOnMainSync(old::finish); }
            if (!holding || !"PASS".equals(result.getString("result"))) {
                try { cleanup(); } catch (Exception e) { result.putString("cleanup", "FAILED: "+e); }
            }
        }
        finish("PASS".equals(result.getString("result")) ? Activity.RESULT_OK : Activity.RESULT_CANCELED, result);
    }
}
