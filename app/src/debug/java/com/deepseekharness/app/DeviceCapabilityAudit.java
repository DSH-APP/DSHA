package com.deepseekharness.app;

import android.app.Activity;
import android.app.Instrumentation;
import android.content.Context;
import android.content.SharedPreferences;
import android.os.Bundle;
import com.deepseekharness.app.util.Constants;
import com.deepseekharness.app.util.DeviceCommandResult;

/** 真机验证原生通道；只执行 id、只检查策略，不读取短信或改变设备文件。 */
public final class DeviceCapabilityAudit extends Instrumentation {
    private Bundle arguments;
    @Override public void onCreate(Bundle args) { arguments = args == null ? new Bundle() : args; start(); }
    @Override public void onStart() {
        Bundle result = new Bundle(); Context app = getTargetContext();
        SharedPreferences prefs = app.getSharedPreferences(Constants.PREFS, 0);
        boolean hadRoot = prefs.contains(Constants.KEY_ALLOW_ROOT_SHELL), root = RootShell.enabled(app);
        try {
            if (arguments.getString("mode", "probe").startsWith("shizuku")) {
                for(String command:new String[]{"input keyevent 224","wm dismiss-keyguard",
                        "am start -W -n com.dsh.client/com.deepseekharness.app.ui.MainActivity --ez limited_entry true"}) {
                    try(android.os.ParcelFileDescriptor fd=getUiAutomation().executeShellCommand(command);
                        java.io.InputStream in=new android.os.ParcelFileDescriptor.AutoCloseInputStream(fd)){while(in.read()!=-1){}}
                }
            }
            long binderDeadline = android.os.SystemClock.elapsedRealtime() + 5000;
            while (ShizukuShell.isInstalled(app) && !ShizukuShell.isAvailable()
                    && android.os.SystemClock.elapsedRealtime() < binderDeadline) Thread.sleep(40);
            android.content.pm.ProviderInfo provider = app.getPackageManager().resolveContentProvider("com.dsh.client.shizuku", 0);
            if (provider == null || !provider.name.equals("rikka.shizuku.ShizukuProvider")) throw new AssertionError("Shizuku Provider 没有正确注册");
            result.putString("provider", provider.name);
            result.putString("shizuku", ShizukuShell.userStatus(app));
            result.putBoolean("shizuku_installed", ShizukuShell.isInstalled(app));
            result.putBoolean("root_present", RootShell.present());
            result.putBoolean("root_enabled_before", root);
            if (arguments.getString("mode", "probe").equals("shizuku-reconnect")) {
                android.content.pm.ApplicationInfo manager = ShizukuManagerCompat.manager(app);
                if (manager == null) throw new AssertionError("无法识别权限所属管理器");
                result.putString("manager", manager.packageName);
                Activity host = startActivitySync(new android.content.Intent(app,
                        com.deepseekharness.app.ui.FragmentSessionTestActivity.class).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK));
                try {
                    // 测试专用：模拟客户端错过服务分发，不停止服务器或清除用户授权。
                    rikka.shizuku.Shizuku.class.getMethod("onBinderReceived", android.os.IBinder.class, String.class).invoke(null, null, app.getPackageName());
                    waitForIdleSync();
                    if (ShizukuShell.isAvailable()) throw new AssertionError("未模拟 Binder 缺失");
                    java.util.concurrent.CountDownLatch recovered = new java.util.concurrent.CountDownLatch(1);
                    runOnMainSync(() -> ShizukuManagerCompat.reconnect(host, recovered::countDown));
                    if (!recovered.await(20, java.util.concurrent.TimeUnit.SECONDS) || !ShizukuShell.isAvailable())
                        throw new AssertionError("主动请求未恢复 Binder：" + ShizukuShell.status());
                    result.putString("reconnect", "PASS: manager callback, protected provider handshake, permission retained");
                } finally { runOnMainSync(host::finish); }
            }
            if (arguments.getString("mode", "probe").equals("root")) {
                if (!RootShell.present()) throw new AssertionError("当前设备没有 su");
                prefs.edit().putBoolean(Constants.KEY_ALLOW_ROOT_SHELL, true).commit();
                String output = RootShell.exec(app, "id", -1); result.putString("root_id", output);
                if (DeviceCommandResult.exitCode(output) != 0 || !output.contains("uid=0("))
                    throw new AssertionError("DSHA 的直接 root 通道没有获得 uid=0");
                String denied = RootShell.exec(app, "setenforce 0", -1);
                if (DeviceCommandResult.exitCode(denied) != 126) throw new AssertionError("危险命令未在 su 前拒绝");
                result.putString("root_policy", "PASS");
                // 与 Ubuntu adb-shell 使用同一原生路由，ADB 关闭时也必须选中直接 su。
                Context noAdb = new android.content.ContextWrapper(app) {
                    @Override public Context getApplicationContext() { return this; }
                    @Override public SharedPreferences getSharedPreferences(String name, int mode) {
                        if (!Constants.PREFS.equals(name)) return super.getSharedPreferences(name, mode);
                        return super.getSharedPreferences("device_route_audit", mode);
                    }
                };
                SharedPreferences isolated = noAdb.getSharedPreferences(Constants.PREFS, 0);
                try {
                    isolated.edit().clear().putBoolean(Constants.KEY_ALLOW_ROOT_SHELL, true).putBoolean("adb_enabled", false).commit();
                    HttpShellService bridge = new HttpShellService(noAdb);
                    java.lang.reflect.Method route = HttpShellService.class.getDeclaredMethod("deviceExecute", String.class, boolean.class, boolean.class);
                    route.setAccessible(true);
                    org.json.JSONObject executed = new org.json.JSONObject((String)route.invoke(bridge,"id",false,false));
                    if (!"root".equals(executed.getString("transport")) || executed.getInt("exit")!=0
                            || !executed.getString("output").contains("uid=0(")) throw new AssertionError("ADB 关闭时原生路由没有直接执行 su");
                    result.putString("root_route_adb_off", "PASS");
                } finally { isolated.edit().clear().commit(); }
            }
            if (arguments.getString("mode", "probe").startsWith("shizuku")) {
                if (!ShizukuShell.hasPermission() && arguments.getString("mode").equals("shizuku-request")) {
                    if(!ShizukuShell.isAvailable())throw new AssertionError("Shizuku Binder 尚未接入："+ShizukuShell.status());
                    java.util.concurrent.CountDownLatch answered=new java.util.concurrent.CountDownLatch(1);
                    ShizukuShell.requestPermission((code, grant)->answered.countDown());
                    Bundle status=new Bundle();status.putString("stream","等待 Shizuku 授权窗口确认\n");sendStatus(0,status);
                    if(!answered.await(60,java.util.concurrent.TimeUnit.SECONDS))throw new AssertionError("授权窗口尚未确认");
                }
                if (!ShizukuShell.hasPermission()) throw new AssertionError("请先在 Shizuku 授权 DSHA");
                String output = ShizukuShell.exec("id"); result.putString("shizuku_id", output);
                if (DeviceCommandResult.exitCode(output) != 0 || (!output.contains("uid=0(") && !output.contains("uid=2000(")))
                    throw new AssertionError("Shizuku UserService 没有返回特权身份");
            }
            result.putString("result", "PASS");
        } catch (Throwable error) { result.putString("failure", android.util.Log.getStackTraceString(error)); }
        finally {
            SharedPreferences.Editor edit = prefs.edit();
            if (hadRoot) edit.putBoolean(Constants.KEY_ALLOW_ROOT_SHELL, root); else edit.remove(Constants.KEY_ALLOW_ROOT_SHELL);
            edit.commit();
        }
        finish(result.containsKey("failure") ? Activity.RESULT_CANCELED : Activity.RESULT_OK, result);
    }
}
