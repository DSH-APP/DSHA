package com.deepseekharness.app;

import android.app.Activity;
import android.app.Instrumentation;
import android.content.Context;
import android.content.ContextWrapper;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.os.SystemClock;
import com.deepseekharness.app.util.Compat;
import com.deepseekharness.app.util.Constants;
import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/** 调试专用：隔离配置和 token，宿主通过 USB 转发测试真实原生计划与设备文件预检。 */
public final class DevicePolicyAudit extends Instrumentation {
    private int checks;
    private void check(boolean ok, String label) { if (!ok) throw new AssertionError(label); checks++; }
    @Override public void onCreate(Bundle args) { super.onCreate(args); start(); }
    @Override public void onStart() {
        Bundle result = new Bundle(); HttpShellService bridge = null; Fixture fixture = null;
        try {
            check(BuildConfig.DEBUG, "仅限调试包");
            ShellService service = new ShellService(1000);
            for (String command : new String[]{"rm -rf /", "touch /sdcard/Pictures/x", "mkdir /system/x",
                    "dd if=/dev/zero of=/dev/block/x", "mkfs.ext4 /dev/block/x", "fdisk -l", "parted /dev/block/x print",
                    "setenforce 0", "chcon -R x /data", "restorecon -R /data", "settings put global x 1",
                    "setprop persist.x 1", "mount -o remount,rw /system", "umount /data", "fastboot erase userdata",
                    "flash boot x", "format data", "wipe data", "unknown", "sh -c id", "rmdir -p /data/local/tmp/x"})
                check(service.exec(command).contains("[POLICY_BLOCKED]"), "Binder 入口拦截：" + command);
            check(service.exec("cat /proc/self/status").contains("Name:"), "Binder 实际读取系统目录");
            List<List<String>> sent = new ArrayList<>();
            DeviceShellExecutor.Runner spy = argv -> {
                sent.add(new ArrayList<>(argv));
                if (argv.get(0).equals("pm")) return argv.contains("-3") ? "package:example.app uid:12345\n[EXIT=0]" : "package:android uid:1000\n[EXIT=0]";
                if (argv.get(0).equals("ps")) return "PID UID NAME\n42 12345 example.app\n43 1000 system_server\n[EXIT=0]";
                return "[EXIT=0]";
            };
            check(DeviceShellExecutor.execute("kill 42", spy).contains("停止用户应用：example.app"), "PID 转为包名");
            check(sent.get(sent.size()-1).equals(java.util.Arrays.asList("am", "force-stop", "example.app")), "只停止明确应用");
            sent.clear();
            check(DeviceShellExecutor.execute("kill 42 43", spy).contains("[POLICY_BLOCKED]"), "混合批次全部拒绝");
            check(sent.stream().noneMatch(a -> a.get(0).equals("am")), "拒绝批次未提前执行普通应用停止");
            check(!HttpShellService.isReady() && !HttpShellService.isStarting(), "不能占用现有设备桥");
            fixture = new Fixture(getTargetContext());
            fixture.getSharedPreferences(Constants.PREFS, 0).edit().putBoolean(Constants.KEY_CONFIRM_SHELL, false)
                    .putBoolean(Constants.KEY_ALLOW_ROOT_SHELL, true).commit();
            File token = new File(fixture.base, "token");
            Compat.write(token, "audit_" + UUID.randomUUID().toString().replace("-", ""));
            bridge = new HttpShellService(fixture, token); bridge.start();
            long end = SystemClock.elapsedRealtime() + 5000;
            while (!HttpShellService.isReady() && SystemClock.elapsedRealtime() < end) Thread.sleep(40);
            check(HttpShellService.isReady(), "测试桥就绪");
            Compat.write(new File(fixture.base, "ready"), "ready");
            Bundle ready = new Bundle(); ready.putString("state", "READY"); sendStatus(0, ready);
            end = SystemClock.elapsedRealtime() + 240000;
            while (!new File(fixture.base, "done").exists() && SystemClock.elapsedRealtime() < end) Thread.sleep(100);
            check(new File(fixture.base, "done").exists(), "宿主完成测试");
            result.putString("result", "PASS");
        } catch (Throwable error) {
            result.putString("result", "FAIL"); result.putString("failure", String.valueOf(error));
        } finally {
            if (bridge != null) bridge.stop();
            if (fixture != null) fixture.getSharedPreferences(Constants.PREFS, 0).edit().clear().commit();
        }
        result.putInt("assertions", checks);
        finish("PASS".equals(result.getString("result")) ? Activity.RESULT_OK : Activity.RESULT_CANCELED, result);
    }
    private static final class Fixture extends ContextWrapper {
        final File base;
        Fixture(Context context) throws Exception {
            super(context.getApplicationContext());
            base = new File(context.getCacheDir(), "device-policy-audit");
            if (!base.mkdirs() || !getFilesDir().mkdirs()) throw new java.io.IOException("测试目录已存在或不可写");
        }
        @Override public Context getApplicationContext() { return this; }
        @Override public File getFilesDir() { return new File(base, "files"); }
        @Override public SharedPreferences getSharedPreferences(String name, int mode) {
            return super.getSharedPreferences("device_policy_audit_" + name, mode);
        }
    }
}
