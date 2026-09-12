/*
 * Decompiled with CFR 0.152.
 */
package com.deepseekharness.app;

import com.deepseekharness.app.IShellService;
import com.deepseekharness.app.util.Compat;
import com.deepseekharness.app.util.BoundedProcessRunner;
import com.deepseekharness.app.util.SensitiveData;
import java.util.Map;

public class ShellService
extends IShellService.Stub {
    private final long timeoutMillis;
    private final java.util.Set<Process> processes = java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>());
    private boolean closing;
    public ShellService() { this(30_000); }
    /** 包内测试可缩短等待；Binder 入口始终使用默认的 30 秒。 */
    ShellService(long timeoutMillis) {
        if (timeoutMillis <= 0 || timeoutMillis > 30_000) throw new IllegalArgumentException(com.deepseekharness.app.util.UiText.text("无效的命令期限"));
        this.timeoutMillis = timeoutMillis;
    }

    @Override
    public String exec(String cmd) {
        return DeviceShellExecutor.execute(cmd, this::executeArgv);
    }
    @Override public void destroy() {
        java.util.List<Process> owned;
        synchronized (processes) { closing = true; owned = new java.util.ArrayList<>(processes); }
        for (Process process : owned) Compat.destroy(process);
        // 此协议只负责 Shizuku 的特权进程；本进程中的测试实例不能退出 DSHA。
        int uid = android.os.Process.myUid();
        if (uid == 0 || uid == 2000) System.exit(0);
    }

    /** 只供原生授权后的 root 子进程调用；Binder exec 不能选择此分支。 */
    String execAuthorizedSms(String command, int user) {
        com.deepseekharness.app.util.DeviceShellPolicy.Plan plan = com.deepseekharness.app.util.DeviceShellPolicy.inspect(command);
        if (plan.kind != com.deepseekharness.app.util.DeviceShellPolicy.Kind.SENSITIVE_READ)
            return com.deepseekharness.app.util.UiText.text("[POLICY_BLOCKED] 不是受支持的短信查询\n[EXIT=126]");
        return executeArgv(com.deepseekharness.app.util.SmsQuery.forUser(plan.argv, user));
    }

    private String executeArgv(java.util.List<String> arguments) {
        Process p = null;
        try {
            java.util.List<String> argv = new java.util.ArrayList<>(arguments);
            argv.set(0, "/system/bin/" + argv.get(0));
            ProcessBuilder pb = new ProcessBuilder(argv).redirectErrorStream(true);
            Map<String, String> env = pb.environment();
            env.put("PATH", "/system/bin:/system/xbin:/sbin:/vendor/bin");
            synchronized (processes) {
                if (closing) return com.deepseekharness.app.util.UiText.text("[SHIZUKU_SERVICE_NOT_READY] 设备 Shell 正在退出\n[EXIT=124]");
                p = pb.start(); processes.add(p);
            }
            BoundedProcessRunner.Result result = BoundedProcessRunner.collect(p, timeoutMillis, 262_144, Compat::destroy);
            String output = result.output + (result.truncated ? com.deepseekharness.app.util.UiText.text("\n[OUTPUT_TRUNCATED] 输出超过 256 KiB，已截断") : "");
            String duration = timeoutMillis % 1000 == 0 ? timeoutMillis / 1000 + "s" : timeoutMillis + "ms";
            return output + (result.timedOut ? com.deepseekharness.app.util.UiText.text("\n命令执行超时(") + duration + com.deepseekharness.app.util.UiText.text(")，已终止本次进程\n[EXIT=timeout]")
                    : "\n[EXIT=" + result.exitCode + "]");
        }
        catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return com.deepseekharness.app.util.UiText.text("命令等待已中断，本次进程已关闭\n[EXIT=interrupted]");
        }
        catch (Throwable e) {
            return "ERROR: " + SensitiveData.redact(String.valueOf(e));
        }
        finally { if (p != null) { Compat.destroy(p); synchronized (processes) { processes.remove(p); } } }
    }
}
