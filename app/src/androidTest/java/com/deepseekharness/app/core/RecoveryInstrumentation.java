package com.deepseekharness.app.core;

import android.app.Instrumentation;
import android.content.*;
import android.os.Bundle;
import android.os.PowerManager;
import com.deepseekharness.app.HarnessService;
import com.deepseekharness.app.runtime.ProotBootstrap;
import com.deepseekharness.app.runtime.WebProcessManager;
import com.deepseekharness.app.util.Compat;
import com.deepseekharness.app.util.Constants;
import java.io.*;
import java.lang.reflect.Field;
import java.util.function.BooleanSupplier;

/** 故障注入使用独立目录；真实启动只备份当前数据，不恢复或创建对话。 */
public final class RecoveryInstrumentation extends Instrumentation {
    private static final String PREF = "optimization-recovery-owned-";
    @Override public void onCreate(Bundle args) { super.onCreate(args); start(); }
    private void check(boolean ok, String why) { if (!ok) throw new AssertionError(why); }
    private void phase(String message) { Bundle b = new Bundle(); b.putString("phase", message); sendStatus(1, b); }
    private void until(BooleanSupplier ready, long timeout, String reason) throws Exception {
        long end = System.currentTimeMillis() + timeout;
        while (!ready.getAsBoolean() && System.currentTimeMillis() < end) Thread.sleep(150);
        check(ready.getAsBoolean(), reason);
    }
    private static Field field(Class<?> type, String name) throws Exception {
        Field f = type.getDeclaredField(name); f.setAccessible(true); return f;
    }
    private void shell(String text) throws Exception {
        try (android.os.ParcelFileDescriptor command = getUiAutomation().executeShellCommand(text);
             InputStream in = new android.os.ParcelFileDescriptor.AutoCloseInputStream(command)) { while (in.read() != -1) {} }
    }
    private boolean held(HarnessService service) {
        try { PowerManager.WakeLock lock = (PowerManager.WakeLock) field(HarnessService.class, "wakeLock").get(service);
            return lock != null && lock.isHeld(); } catch (Exception error) { throw new RuntimeException(error); }
    }
    private void removeOwned(File target) {
        if (target.isDirectory() && !Compat.isSymbolicLink(target)) {
            File[] children = target.listFiles(); if (children != null) for (File file : children) removeOwned(file);
        }
        target.delete();
    }
    private void failureScenario(Context base, HarnessController actual) throws Exception {
        Context isolated = new ContextWrapper(base) {
            @Override public Context getApplicationContext() { return this; }
            @Override public SharedPreferences getSharedPreferences(String name, int mode) { return super.getSharedPreferences(PREF + name, mode); }
        };
        File owned = new File(base.getCacheDir(), "optimization-recovery-owned");
        check(!owned.exists(), "存在未清理的独立故障测试目录");
        File rootfs = new File(owned, "rootfs"); new File(rootfs, "root").mkdirs();
        ProotBootstrap fixture = new ProotBootstrap(isolated) {
            @Override public File getRootfsDir() { return rootfs; }
            @Override public void ensureRuntimeFiles() {}
            @Override public boolean isEnvironmentReady() { return true; }
            @Override public String registerBuiltinPlugins() { return "BUILTIN_REGISTER_OK"; }
            @Override public Process execRootfs(String command) throws IOException { throw new IOException("独立样本：进程创建失败"); }
        };
        HarnessController controlled = new HarnessController(isolated);
        controlled.config().setAutoBackupLaunches(0);
        field(HarnessController.class,"proot").set(controlled,fixture);
        field(HarnessController.class,"webProc").set(controlled,new WebProcessManager(fixture) { @Override public String stop() { return ""; } });
        Field recovery = field(HarnessController.class,"recovery"); Object previous = recovery.get(null);
        try {
            recovery.set(null,new WebRecovery(controlled.config()));
            check(controlled.startWeb(null),"未接受初次手动启动");
            until(()->controlled.config().getWebFailures()==1,10000,"未记录首次失败");
            for (int attempt=2;attempt<=3;attempt++) {
                int expected = attempt;
                check(controlled.restartWebAutomatically(controlled.getWebGeneration(),null),"自动重试未接受");
                until(()->controlled.config().getWebFailures()==expected,10000,"失败次数不一致");
            }
            until(()->!controlled.isStopping(),10000,"失败停机没有结束");
            check(controlled.isRestartBlocked(),"三次失败未暂停");
            check(!controlled.restartWebAutomatically(controlled.getWebGeneration(),null),"暂停后仍然自动重启");
            check(controlled.config().getWebFailureStage().equals("创建 Web 进程"),"未保存正确失败阶段");
            recovery.set(null,new WebRecovery(new ConfigStore(isolated)));
            check(controlled.isRestartBlocked(),"重建恢复器后丢失暂停状态");
            check(controlled.startWeb(null),"手动重试未解除暂停");
            until(()->controlled.config().getWebFailures()==1,10000,"手动重试未重新计算预算");
            controlled.stopWeb();
            phase("故障注入通过：三次失败暂停、阶段保存、恢复器重建、手动重试");
        } finally {
            controlled.stopWeb(); recovery.set(null,previous);
            isolated.getSharedPreferences(Constants.PREFS,0).edit().clear().commit();
            removeOwned(owned);
        }
    }
    @Override public void onStart() {
        Bundle output = new Bundle();
        Context base = getTargetContext().getApplicationContext();
        HarnessController controller = HarnessController.get(base);
        SharedPreferences prefs = base.getSharedPreferences(Constants.PREFS,0);
        Object oldCount = prefs.getAll().get("backup_launch_count");
        Object oldEco = prefs.getAll().get("runtime_eco_mode");
        boolean started = false;
        try {
            check(!controller.isStarting() && !controller.isWebRunning(),"用户 Web 正在运行，停止本轮启动测试以保留现场");
            failureScenario(base,controller);
            int every = controller.config().getAutoBackupLaunches();
            check(every > 0,"自动备份当前关闭，未修改用户设置");
            prefs.edit().putInt("backup_launch_count",every-1).commit();
            long before = controller.config().getLastBackupSuccess();
            shell("am start -W -n com.dsh.client/com.deepseekharness.app.ui.MainActivity");
            check(controller.startWeb(null),"真实启动未接受"); started = true;
            runOnMainSync(()->androidx.core.content.ContextCompat.startForegroundService(base,new Intent(base,HarnessService.class)));
            until(()->!controller.getWebAuthUrl().isEmpty(),90000,"真实 Web 未获得鉴权链接："+controller.config().getWebFailureReason());
            check(controller.config().getLastBackupSuccess()>before,"启动前自动备份未触发");
            check(controller.config().getLastBackupError().isEmpty(),"自动备份报错");
            output.putString("backup",controller.config().getLastBackupName());
            phase("真实启动通过：自动备份已保存校验，Web 鉴权已就绪");
            File activity = new File(controller.proot().getRootfsDir(),"root/.dsha-web-activity.json");
            until(activity::isFile,15000,"运行状态插件未写出状态");
            org.json.JSONObject status = new org.json.JSONObject(new String(Compat.readAllBytes(activity),java.nio.charset.StandardCharsets.UTF_8));
            check(status.getLong("generation")==controller.getWebGeneration(),"运行状态代次错误");
            check(status.getBoolean("idle"),"当前状态不是已知空闲，未继续省电测试");
            HarnessService service = (HarnessService) field(HarnessService.class,"activeService").get(null);
            check(service!=null && held(service),"持续模式未持有 Web 锁");
            controller.config().setEcoMode(true);
            shell("input keyevent KEYCODE_SLEEP");
            HarnessService.refreshPowerMode();
            until(()->!held(service),85000,"持续空闲后 Web 锁仍未释放");
            phase("熄屏省电通过：确认空闲后释放 Web 锁");
            try (RuntimeTasks task=RuntimeTasks.begin()) {
                check(RuntimeTasks.isBusy(),"原生任务未进入工作状态");
                PowerManager.WakeLock taskLock=(PowerManager.WakeLock)field(RuntimeTasks.class,"lock").get(null);
                check(taskLock!=null && taskLock.isHeld(),"原生工作未立即取得任务锁");
                HarnessService.refreshPowerMode(); check(held(service),"任务开始后未恢复 Web 保活");
            }
            check(!RuntimeTasks.isBusy(),"任务结束后未清理工作状态");
            check(controller.isWebRunning(),"省电错误停止了 Web");
            phase("任务恢复通过：原生任务立即取锁，Web 保持运行");
            output.putString("result","PASS");
        } catch(Throwable error) {
            output.putString("result","FAIL"); output.putString("error",error.toString());
            StringWriter trace=new StringWriter(); error.printStackTrace(new PrintWriter(trace)); output.putString("trace",trace.toString());
        } finally {
            if (started) { controller.stopWeb(); base.stopService(new Intent(base,HarnessService.class)); }
            SharedPreferences.Editor restore=prefs.edit();
            if(oldCount==null)restore.remove("backup_launch_count");else restore.putInt("backup_launch_count",(Integer)oldCount);
            if(oldEco==null)restore.remove("runtime_eco_mode");else restore.putBoolean("runtime_eco_mode",(Boolean)oldEco);
            restore.commit();
            try { shell("input keyevent KEYCODE_WAKEUP"); } catch(Exception ignored) {}
            finish(output.getString("result","FAIL").equals("PASS")?-1:0,output);
        }
    }
}
