package com.deepseekharness.app.core;

import android.app.Activity;
import android.app.ActivityManager;
import android.app.Instrumentation;
import android.content.Context;
import android.content.ContextWrapper;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Bundle;
import com.deepseekharness.app.BuildConfig;
import com.deepseekharness.app.UpdateDownloadService;
import com.deepseekharness.app.ui.UpdateActivity;
import com.deepseekharness.app.util.FileIntegrity;
import com.deepseekharness.app.util.UpdatePolicy;
import java.io.*;
import java.net.*;
import java.lang.reflect.*;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BooleanSupplier;

/** 真 Android 服务/页面/文件与签名检查；传输流可控，所有元数据和归档均为独立样本。 */
public final class OptimizationInstrumentation extends Instrumentation {
    private Bundle args;
    private Activity page;
    private UpdateEngine active;
    private static final String PREF = "optimization-transfer-owned-";
    @Override public void onCreate(Bundle args) { super.onCreate(args); this.args = args; start(); }
    private void require(boolean ok, String reason) { if (!ok) throw new AssertionError(reason); }
    private void until(BooleanSupplier ready, String reason) throws Exception {
        long deadline = System.currentTimeMillis() + 45000;
        while (!ready.getAsBoolean() && System.currentTimeMillis() < deadline) Thread.sleep(75);
        require(ready.getAsBoolean(), reason);
    }
    private void phase(String message) { Bundle b = new Bundle(); b.putString("phase", message); sendStatus(1, b); }
    private void shell(String text) throws Exception {
        try (android.os.ParcelFileDescriptor command = getUiAutomation().executeShellCommand(text);
             InputStream in = new android.os.ParcelFileDescriptor.AutoCloseInputStream(command)) { while (in.read() != -1) { } }
    }
    private boolean serviceRunning() {
        for (ActivityManager.RunningServiceInfo service : getTargetContext().getSystemService(ActivityManager.class).getRunningServices(50))
            if (service.service.getClassName().equals(UpdateDownloadService.class.getName())) return true;
        return false;
    }
    @Override public void onStart() {
        Bundle output = new Bundle();
        UpdateEngine previous = null;
        Context isolated = null;
        File part = null, apk = null;
        Field singleton = null;
        boolean filesOwned = false;
        try {
            Context base = getTargetContext().getApplicationContext();
            isolated = new ContextWrapper(base) {
                @Override public SharedPreferences getSharedPreferences(String name, int mode) {
                    return super.getSharedPreferences(PREF + name, mode);
                }
            };
            File fixtures = new File(args.getString("fixtures"));
            File valid = new File(fixtures, "valid.apk");
            String hash;
            try (InputStream in = new FileInputStream(valid)) { hash = FileIntegrity.copy(in, null, 32L * 1024 * 1024).sha256; }
            android.content.pm.PackageInfo info = base.getPackageManager().getPackageArchiveInfo(valid.getAbsolutePath(), 0);
            require(info != null, "缺少有效测试 APK");
            UpdatePolicy.Release release = new UpdatePolicy.Release(info.versionCode, "1.2.0-validation", UpdatePolicy.PREVIEW,
                    BuildConfig.LOW_ANDROID ? "low" : "standard", BuildConfig.LOW_ANDROID ? 23 : 30,
                    "arm64-v8a", "https://dsha-test.invalid/owned.apk", hash, valid.length(), "仅测试，不安装", "https://dsha.cc/download/");
            AtomicLong resumed = new AtomicLong();
            UpdateEngine.Transport source = (url, offset) -> {
                resumed.set(Math.max(resumed.get(), offset));
                return new HttpURLConnection(new URL(url)) {
                    volatile boolean closed;
                    public int getResponseCode() { return offset > 0 ? 206 : 200; }
                    public String getHeaderField(String name) {
                        if (name.equals("Content-Length")) return "" + (valid.length() - offset);
                        if (name.equals("Content-Range") && offset > 0) return "bytes " + offset + "-" + (valid.length() - 1) + "/" + valid.length();
                        return null;
                    }
                    public InputStream getInputStream() throws IOException {
                        FileInputStream input = new FileInputStream(valid);
                        input.getChannel().position(offset);
                        return new FilterInputStream(input) {
                            @Override public int read(byte[] b, int off, int len) throws IOException {
                                if (closed) throw new IOException("测试连接已取消");
                                try { Thread.sleep(12); } catch (InterruptedException e) { throw new IOException(e); }
                                return super.read(b, off, Math.min(len, 16384));
                            }
                        };
                    }
                    public void disconnect() { closed = true; }
                    public boolean usingProxy() { return false; }
                    public void connect() { }
                };
            };
            singleton = UpdateEngine.class.getDeclaredField("instance"); singleton.setAccessible(true);
            previous = UpdateEngine.get(base);
            require(!previous.state().getValue().busy, "用户更新正在运行，不能覆盖测试");
            Context data = isolated;
            require(data.getSharedPreferences("dsha-updates", 0).edit().putString("channel", UpdatePolicy.PREVIEW).commit(), "夹具所选通道保存失败");
            runOnMainSync(() -> active = new UpdateEngine(data, source));
            Field candidate = UpdateEngine.class.getDeclaredField("candidate"); candidate.setAccessible(true); candidate.set(active, release);
            Field candidateChannel = UpdateEngine.class.getDeclaredField("candidateChannel"); candidateChannel.setAccessible(true); candidateChannel.set(active, UpdatePolicy.PREVIEW);
            Field verifiedApk = UpdateEngine.class.getDeclaredField("verifiedApk"); verifiedApk.setAccessible(true); verifiedApk.set(active, null);
            // 固定清单的下载夹具不接收主界面启动时的真实清单检查。
            Field startupChecked = UpdateEngine.class.getDeclaredField("startupChecked"); startupChecked.setAccessible(true); startupChecked.setBoolean(active, true);
            Method save = UpdateEngine.class.getDeclaredMethod("save", String.class, String.class); save.setAccessible(true);
            save.invoke(active, "available", "独立下载验收");
            singleton.set(null, active);
            part = new File(base.getFilesDir(), "updates/" + hash + ".part");
            apk = new File(base.getFilesDir(), "updates/" + hash + ".apk");
            require(!part.exists() && !apk.exists(), "存在同名测试文件，需先确认来源");
            filesOwned = true;
            active.validatePackage(valid, release);
            for (String bad : new String[]{"wrong-signature.apk", "wrong-package.apk", "wrong-flavor.apk"}) {
                boolean rejected = false;
                try { active.validatePackage(new File(fixtures, bad), release); } catch (IOException expected) { rejected = true; }
                require(rejected, "未拒绝 " + bad);
            }
            phase("包名、版本和签名检查通过；开始真实前台下载服务");
            shell("am start -W -n com.dsh.client/com.deepseekharness.app.ui.MainActivity");
            page = startActivitySync(new Intent(base, UpdateActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            runOnMainSync(active::download);
            until(() -> active.state().getValue().downloaded > 131072 || !active.state().getValue().busy, "下载没有响应");
            require(active.state().getValue().busy, active.state().getValue().message);
            require(serviceRunning(), "下载前台服务未运行");
            long before = active.state().getValue().downloaded;
            Activity old = page; runOnMainSync(old::finish); page = null;
            shell("input keyevent KEYCODE_HOME");
            until(() -> active.state().getValue().downloaded > before + 131072, "关闭页面后下载没有继续");
            runOnMainSync(active::cancel);
            until(() -> !active.state().getValue().busy && !serviceRunning(), "取消后服务未结束");
            require(part.length() > 0 && part.length() < valid.length(), "取消未留下正确的部分文件");
            long savedBytes = part.length();
            phase("关闭页面继续下载、取消和服务退出通过；重建任务恢复进度");
            runOnMainSync(() -> active = new UpdateEngine(data, source));
            startupChecked.setBoolean(active, true);
            singleton.set(null, active);
            require(UpdatePolicy.PREVIEW.equals(active.channel()) && UpdatePolicy.PREVIEW.equals(active.state().getValue().sourceChannel), "重建后夹具查询通道丢失");
            require(active.state().getValue().downloaded == savedBytes, "重建后进度丢失");
            shell("am start -W -n com.dsh.client/com.deepseekharness.app.ui.MainActivity");
            page = startActivitySync(new Intent(base, UpdateActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            runOnMainSync(active::download);
            until(() -> !active.state().getValue().busy, "续传未完成");
            require(active.state().getValue().apk != null, active.state().getValue().message);
            require(resumed.get() == savedBytes, "没有按保存长度请求续传");
            require(active.installableApk().length() == valid.length(), "最终校验失败");
            try (RandomAccessFile changed = new RandomAccessFile(apk, "rw")) { changed.seek(512); changed.write(123); }
            boolean rejected = false;
            try { active.installableApk(); } catch (IOException expected) { rejected = expected.getMessage().contains("SHA-256"); }
            require(rejected, "安装前没有重新核验完整文件");
            output.putString("result", "PASS");
            output.putString("coverage", "真实 Android 前台服务、关闭页面继续、取消退出、持久任务重建、Range续传、签名/包名/版本与安装前完整摘要；传输流为隔离可控样本");
        } catch (Throwable error) { output.putString("result", "FAIL: " + error); }
        finally {
            if (active != null) {
                runOnMainSync(active::cancel);
                try { until(() -> !active.state().getValue().busy && !serviceRunning(), "测试服务清理超时"); } catch (Exception ignored) { }
            }
            if (page != null) { Activity old = page; runOnMainSync(old::finish); }
            if (singleton != null) try { singleton.set(null, previous); } catch (Exception ignored) { }
            if (filesOwned) { if (part != null) part.delete(); if (apk != null) apk.delete(); }
            if (isolated != null) {
                isolated.getSharedPreferences("dsha-updates", 0).edit().clear().commit();
            }
            getTargetContext().getSystemService(android.app.NotificationManager.class).cancel(1004);
        }
        finish("PASS".equals(output.getString("result")) ? Activity.RESULT_OK : Activity.RESULT_CANCELED, output);
    }
}
