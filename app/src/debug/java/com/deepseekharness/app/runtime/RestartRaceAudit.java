package com.deepseekharness.app.runtime;

import android.app.Activity;
import android.app.Instrumentation;
import android.os.Bundle;
import android.os.SystemClock;
import com.deepseekharness.app.BackupManager;
import com.deepseekharness.app.core.HarnessController;
import com.deepseekharness.app.util.Compat;
import com.deepseekharness.app.util.SensitiveData;

/** 验证退出时空 cmdline 的窗口，以及从已运行状态直接请求多次重启。 */
public final class RestartRaceAudit extends Instrumentation {
    @Override public void onCreate(Bundle args) { super.onCreate(args); start(); }
    @Override public void onStart() {
        Bundle result = new Bundle(); HarnessController controller = HarnessController.get(getTargetContext());
        Process witness = null;
        try {
            witness = new ProcessBuilder("/system/bin/sleep", "90").start();
            final int pid = com.deepseekharness.app.util.ProcessIdentity.androidPid(witness.getClass().getName(), witness.toString());
            if (pid < 2) throw new AssertionError("无法读取测试进程 PID");
            String stat;
            try (java.io.FileInputStream in = new java.io.FileInputStream("/proc/" + pid + "/stat")) {
                byte[] data = new byte[4096]; int n = in.read(data); stat = new String(data, 0, n, java.nio.charset.StandardCharsets.UTF_8);
            }
            final String live = stat;
            WebProcessManager manager = new WebProcessManager(controller.proot()) {
                int reads;
                @Override String readProcessFile(int value, String file) {
                    if (file.equals("cmdline")) return "";
                    if (++reads == 1) return live;
                    int close = live.lastIndexOf(')'); return live.substring(0, close + 2) + "Z" + live.substring(close + 3);
                }
            };
            if (manager.inspect(pid).kind != WebProcessManager.Kind.GONE || !Compat.isAlive(witness))
                throw new AssertionError("退出复核失败或误发了信号");
            WebProcessManager unreadable = new WebProcessManager(controller.proot()) {
                @Override String readProcessFile(int value, String file) { return file.equals("cmdline") ? "" : live; }
            };
            try { unreadable.inspect(pid); throw new AssertionError("长期不可读的活进程被当作已退出"); }
            catch (java.io.IOException expected) { }
            BackupManager.stopWebForMaintenance(controller);
            java.util.ArrayList<Long> times = new java.util.ArrayList<>();
            for (int i = 0; i < 6; i++) {
                long began = SystemClock.elapsedRealtime();
                if (!controller.startWeb(null)) throw new AssertionError("重启未接受");
                while (controller.getWebAuthUrl().isEmpty() && SystemClock.elapsedRealtime()-began < 90000) Thread.sleep(50);
                if (controller.getWebAuthUrl().isEmpty()) throw new AssertionError(controller.startupDiagnostics().snapshot().log);
                times.add(SystemClock.elapsedRealtime()-began);
                Bundle progress = new Bundle(); progress.putString("stream", "直接启动/重启第 " + (i+1) + " 次：" + times.get(i) + "ms\n"); sendStatus(0, progress);
            }
            BackupManager.stopWebForMaintenance(controller);
            if (!controller.isWebStoppedForMaintenance()) throw new AssertionError("最终停止未确认");
            result.putString("result", "PASS"); result.putString("restart_ms", times.toString());
        } catch (Throwable error) { result.putString("failure", SensitiveData.redact(android.util.Log.getStackTraceString(error))); }
        finally {
            if (witness != null) Compat.destroy(witness);
            try { BackupManager.stopWebForMaintenance(controller); } catch (Exception ignored) { }
            finish(result.containsKey("failure") ? Activity.RESULT_CANCELED : Activity.RESULT_OK, result);
        }
    }
}
