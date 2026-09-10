package com.deepseekharness.app.runtime;

import android.app.Activity;
import android.app.Instrumentation;
import android.content.Context;
import android.content.ContextWrapper;
import android.content.SharedPreferences;
import android.os.Bundle;
import com.deepseekharness.app.BackupManager;
import com.deepseekharness.app.core.HarnessController;
import com.deepseekharness.app.util.Compat;
import com.deepseekharness.app.util.ProcessIdentity;
import com.deepseekharness.app.util.WebLifecycle;
import com.deepseekharness.app.util.WebPidIdentity;
import java.io.File;
import java.io.IOException;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

/** 仅操作独立 cache；外部 PID 只做 signal 0，绝不终止系统进程。 */
public final class PidMaintenanceAudit extends Instrumentation {
    private Bundle args; private int checks;
    private void check(boolean value, String why) { checks++; if (!value) throw new AssertionError(why); }
    private void note(String message) { Bundle out = new Bundle(); out.putString("stream", message + "\n"); sendStatus(0, out); }
    @Override public void onCreate(Bundle args) { super.onCreate(args); this.args=args; start(); }
    @Override public void onStart() {
        Bundle out = new Bundle(); File base = null; Process unrelated = null; HarnessController harness = null;
        try {
            Context app = getTargetContext();
            check(!HarnessController.get(app).isWebRunning() && !HarnessController.get(app).isStarting(), "先保留并结束正在运行的 Web");
            File owned = new File(app.getCacheDir(), "pid-maintenance-" + UUID.randomUUID()); check(owned.mkdir(), "建立隔离测试目录"); base=owned;
            Context isolated = new ContextWrapper(app) {
                @Override public Context getApplicationContext() { return this; }
                @Override public File getFilesDir() { return owned; }
                @Override public SharedPreferences getSharedPreferences(String name,int mode) { return super.getSharedPreferences("pid-fixture-"+name,mode); }
            };
            harness = new HarnessController(isolated); HarnessController target = harness;
            check(harness.runCoreCommand().contains("echo $$ > /root/.dsha-web.pid"), "启动命令必须记录实际 shell PID");
            File root = new File(harness.proot().getRootfsDir(), "root"); check(root.mkdirs(), "建立隔离 root");
            File record = new File(root,".dsha-web.pid"), marker = new File(root,"user-marker"); Compat.write(marker,"keep-data");
            int foreign = Integer.parseInt(args.getString("foreign_pid"));
            try { android.system.Os.kill(foreign,0); throw new AssertionError("需要真实无权控制的外部 PID"); }
            catch (android.system.ErrnoException error) { check(error.errno==android.system.OsConstants.EPERM,"重现 EPERM"); }
            Compat.write(record,foreign+"\n");
            check("ok".equals(BackupManager.runDataTask(harness,()->"ok")),"EPERM 残留编号不再阻塞维护");
            check(!record.exists() && new File(root,".dsha-web.pid.stale").isFile(),"只隔离过期编号");
            check("keep-data".equals(Compat.readAll(marker)),"用户标记未改变");
            note("真实 EPERM 旧 PID：停止屏障放行，外部进程与原数据保留");
            unrelated = new ProcessBuilder("/system/bin/sleep","120").start();
            int child = ProcessIdentity.androidPid(unrelated.getClass().getName(), unrelated.toString()); check(child>1,"取得本次测试子进程 PID");
            Compat.write(record,child+"\n");
            check("ok".equals(BackupManager.runDataTask(harness,()->"ok")),"同 UID 无关进程不应阻塞升级");
            check(Compat.isAlive(unrelated),"没有终止无关进程");
            WebProcessManager live = new WebProcessManager(harness.proot()) {
                @Override boolean hasOwnedWeb() { return true; }
            };
            Compat.write(record,foreign+"\n");
            check(!live.stop().isEmpty() && !live.confirmStopped(false) && record.exists(),"仍有本应用 Web 时不能忽略 EPERM 放行");
            check(!new WebProcessManager(harness.proot()).confirmStopped(true),"启动器未退出时不放行");
            WebProcessManager unreadable = new WebProcessManager(harness.proot()) {
                @Override ProcessState inspect(int pid) throws IOException { throw new IOException("EXPECTED_PROC_FAILURE"); }
            };
            check(unreadable.stop().contains("EXPECTED_PROC_FAILURE"),"未知系统错误有具体信息");
            try { unreadable.confirmStopped(false); throw new AssertionError("未知系统错误不能放行"); }
            catch(IOException expected) { checks++; }
            check(record.exists() && "keep-data".equals(Compat.readAll(marker)),"核验失败保留编号和环境");
            Compat.write(record,child+"\n"); Compat.write(new File(root,".dsha-web.identity"),child+" 1");
            WebProcessManager reused = new WebProcessManager(harness.proot()) {
                @Override ProcessState inspect(int pid) throws IOException {
                    ProcessState state=super.inspect(pid);
                    return new ProcessState(Kind.WEB,state.identity,"node /usr/local/bin/dsh web");
                }
                @Override boolean hasOwnedWeb() { return false; }
            };
            check(reused.stop().isEmpty() && Compat.isAlive(unrelated),"启动时刻不符时不误杀复用 PID");
            note("PID 复用、同 UID 无关进程、活跃 Web 和系统读取失败回归通过");
            java.lang.reflect.Field field=HarnessController.class.getDeclaredField("lifecycle"); field.setAccessible(true);
            WebLifecycle lifecycle=(WebLifecycle)field.get(null);
            File stopped=new File(root,".dsha-stopped"); stopped.delete();
            int failures=harness.config().getWebFailures(); long generation=lifecycle.beginStart(false,false);
            check(generation>=0,"模拟仍在等待鉴权的启动");
            java.lang.reflect.Method slow=HarnessController.class.getDeclaredMethod("reportSlowStart",long.class,java.util.function.Consumer.class); slow.setAccessible(true);
            AtomicBoolean warned=new AtomicBoolean();
            slow.invoke(harness,generation,(java.util.function.Consumer<String>)message->warned.set(message.contains("仍在等待")));
            check(warned.get() && lifecycle.isStarting(),"超时回调只提示并保持启动");
            check(!stopped.exists() && harness.config().getWebFailures()==failures && Compat.isAlive(unrelated),"不写停止哨兵、不累计失败、不终止进程");
            check(!lifecycle.canAutoStart(false),"看门狗不能重启仍在启动的代次");
            check(lifecycle.finishStart(generation),"晚到的就绪事件仍可完成原启动");
            note("启动超过原等待阈值：继续等待，允许稍后就绪及手动停止");
            out.putString("result","PASS"); out.putInt("checks",checks);
        } catch(Throwable error) { out.putString("failure",android.util.Log.getStackTraceString(error)); }
        finally {
            if(unrelated!=null){unrelated.destroy();try{unrelated.waitFor();}catch(Exception ignored){}}
            if(harness!=null)harness.stopWeb();
            if(base!=null)try{clear(base);}catch(Exception error){out.putString("cleanup",error.toString());}
            finish(out.containsKey("failure")?Activity.RESULT_CANCELED:Activity.RESULT_OK,out);
        }
    }
    private void clear(File file) throws IOException {
        if(file.isDirectory()&&!Compat.isSymbolicLink(file)){File[] children=file.listFiles();if(children==null)throw new IOException("夹具目录不可读");for(File child:children)clear(child);}
        if(!file.delete())throw new IOException("夹具文件清理失败");
    }
}
