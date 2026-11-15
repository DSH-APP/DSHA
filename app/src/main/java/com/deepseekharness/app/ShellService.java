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
    private android.content.Context packageContext;
    private com.deepseekharness.app.util.SelfPackageIdentity launchIdentity;
    public ShellService() { this(30_000); }
    @androidx.annotation.Keep public ShellService(android.content.Context context){this(30_000);this.packageContext=context;}
    ShellService(com.deepseekharness.app.util.SelfPackageIdentity identity){this(30_000);this.launchIdentity=identity;}
    /** 包内测试可缩短等待；Binder 入口始终使用默认的 30 秒。 */
    ShellService(long timeoutMillis) {
        if (timeoutMillis <= 0 || timeoutMillis > 30_000) throw new IllegalArgumentException(com.deepseekharness.app.util.UiText.text("无效的命令期限"));
        this.timeoutMillis = timeoutMillis;
    }

    @Override
    public String exec(String cmd) {
        var plan=com.deepseekharness.app.util.DeviceShellPolicy.inspect(cmd);
        try{return DeviceShellExecutor.execute(cmd,this::executeArgv,plan.kind==com.deepseekharness.app.util.DeviceShellPolicy.Kind.STOP?identity():null);}
        catch(java.io.IOException error){return "[POLICY_BLOCKED] "+SensitiveData.redact(error.getMessage())+"\n[EXIT=126]";}
    }
    @Override public String execVirtualScreen(String command) {
        try{return DeviceShellExecutor.executeVirtualScreen(command,this::executeArgv,identity());}
        catch(java.io.IOException error){return "[POLICY_BLOCKED] "+SensitiveData.redact(error.getMessage())+"\n[EXIT=126]";}
    }
    private com.deepseekharness.app.util.SelfPackageIdentity identity()throws java.io.IOException{
        return launchIdentity!=null?launchIdentity:com.deepseekharness.app.runtime.PrivilegedPackageContext.callerIdentity(ShellService.class,packageContext);
    }
    /** RootShellMain 看门狗用：以本进程（root）身份结束本次登记的子进程，不退出、不抛出。 */
    void terminateOwned() {
        java.util.List<Process> owned;
        synchronized (processes) { closing = true; owned = new java.util.ArrayList<>(processes); }
        for (Process process : owned) {
            try { Compat.destroy(process); } catch (RuntimeException ignored) { }
        }
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

    /** Native-only argv execution for the separately authorized USB target channel. */
    String execExternal(java.util.List<String> argv, java.util.Map<String, String> environment, long timeoutMillis) {
        if (android.os.Process.myUid() != 0)
            return com.deepseekharness.app.util.UiText.text("[POLICY_BLOCKED] 外接设备执行器仅允许 uid 0\n[EXIT=126]");
        if (argv == null || argv.isEmpty() || argv.size() > 32 || timeoutMillis <= 0 || timeoutMillis > 900_000)
            return "[POLICY_BLOCKED] EXTERNAL_ARGUMENTS_INVALID\n[EXIT=126]";
        for (String value : argv)
            if (value == null || value.isEmpty() || value.length() > 4096 || value.indexOf('\0') >= 0)
                return "[POLICY_BLOCKED] EXTERNAL_ARGUMENT_INVALID\n[EXIT=126]";
        if (environment == null || environment.size() > 32) return "[POLICY_BLOCKED] EXTERNAL_ENV_INVALID\n[EXIT=126]";
        for (var entry : environment.entrySet())
            if (entry.getKey() == null || !entry.getKey().matches("[A-Z_][A-Z0-9_]{0,63}")
                    || entry.getValue() == null || entry.getValue().length() > 4096
                    || entry.getValue().indexOf('\0') >= 0)
                return "[POLICY_BLOCKED] EXTERNAL_ENV_INVALID\n[EXIT=126]";
        Process process = null;
        try {
            ProcessBuilder builder = new ProcessBuilder(new java.util.ArrayList<>(argv)).redirectErrorStream(true);
            builder.environment().clear();
            builder.environment().put("PATH", "/system/bin:/system/xbin:/sbin:/vendor/bin");
            builder.environment().putAll(environment);
            synchronized (processes) {
                if (closing) return "[SHIZUKU_SERVICE_NOT_READY] Root service is closing\n[EXIT=124]";
                process = builder.start();
                processes.add(process);
            }
            var result = BoundedProcessRunner.collect(process, timeoutMillis, 768 * 1024, Compat::destroy);
            return result.output + (result.timedOut
                ? "\n[EXECUTION_UNKNOWN] external process timeout; do not replay\n[EXIT=125]"
                : result.truncated
                    ? "\n[EXECUTION_UNKNOWN] external output truncated\n[EXIT=125]"
                    : "\n[EXIT=" + result.exitCode + "]");
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            return "[EXECUTION_UNKNOWN] external process interrupted; do not replay\n[EXIT=125]";
        } catch (Throwable error) {
            return "[EXECUTION_UNKNOWN] " + SensitiveData.redact(String.valueOf(error)) + "\n[EXIT=125]";
        } finally {
            if (process != null) {
                Compat.destroy(process);
                synchronized (processes) { processes.remove(process); }
            }
        }
    }

    /**
     * 在同一个 root 子进程内拉起本次专属的 nodaemon ADB server，等它只在 loopback 就绪后执行一条客户端命令，
     * 结束后回收 server。server 是本进程的直接子进程，不经 start-server 的自我 re-exec，也不留常驻进程。
     */
    String execExternalAdbSession(java.util.List<String> serverArgv, java.util.List<String> clientArgv,
            java.util.Map<String, String> environment, long timeoutMillis) {
        if (android.os.Process.myUid() != 0)
            return com.deepseekharness.app.util.UiText.text("[POLICY_BLOCKED] 外接设备执行器仅允许 uid 0\n[EXIT=126]");
        int port = com.deepseekharness.app.util.ExternalToolPolicy.adbSessionPort(serverArgv, clientArgv);
        if (port < 0) return "[POLICY_BLOCKED] EXTERNAL_ADB_SESSION_PORT\n[EXIT=126]";
        if (timeoutMillis <= 0 || timeoutMillis > 900_000)
            return "[POLICY_BLOCKED] EXTERNAL_ARGUMENTS_INVALID\n[EXIT=126]";
        if (environment == null || environment.size() > 32) return "[POLICY_BLOCKED] EXTERNAL_ENV_INVALID\n[EXIT=126]";
        Process server = null;
        try {
            server = runExternalServer(serverArgv, environment);
            if (server == null) return "[SHIZUKU_SERVICE_NOT_READY] Root service is closing\n[EXIT=124]";
            long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.MILLISECONDS.toNanos(Math.min(timeoutMillis, 15_000));
            String waited = awaitLoopbackServer(server, port, deadline);
            // server 未就绪时客户端尚未执行，本次结果确定为失败，可以由用户决定是否重试。
            if (waited != null) return "[EXTERNAL_ADB_SERVER_FAILED] " + waited + "\n[EXIT=124]";
            return runExternalClient(clientArgv, environment, timeoutMillis);
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            return "[EXECUTION_UNKNOWN] external adb session interrupted; do not replay\n[EXIT=125]";
        } catch (Throwable error) {
            return "[EXECUTION_UNKNOWN] " + SensitiveData.redact(String.valueOf(error)) + "\n[EXIT=125]";
        } finally {
            if (server != null) {
                Compat.destroy(server);
                synchronized (processes) { processes.remove(server); }
            }
        }
    }

    /**
     * 多步版本：同一个专属 server 内按顺序执行多条客户端命令（无线 connect 后续命令、安装推送 + pm 会话）。
     * 每步前写 {@code [DSHA_STEP nonce i]}；默认遇到失败即停，continueOnFailure 用于只读探测。
     * 总期限用尽时把下一步记为未知并停止；已执行步骤的结果照常返回，调用方不得重放未知步骤。
     */
    String execExternalAdbSteps(java.util.List<String> serverArgv, java.util.List<java.util.List<String>> steps,
            java.util.Map<String, String> environment, long timeoutMillis, String nonce, boolean continueOnFailure) {
        if (android.os.Process.myUid() != 0)
            return com.deepseekharness.app.util.UiText.text("[POLICY_BLOCKED] 外接设备执行器仅允许 uid 0\n[EXIT=126]");
        if (steps == null || steps.isEmpty() || steps.size() > 64
                || !com.deepseekharness.app.util.ExternalStepOutput.validNonce(nonce))
            return "[POLICY_BLOCKED] EXTERNAL_ADB_STEPS_INVALID\n[EXIT=126]";
        int port = -1;
        for (java.util.List<String> step : steps) {
            int stepPort = com.deepseekharness.app.util.ExternalToolPolicy.adbSessionPort(serverArgv, step);
            if (stepPort < 0 || port >= 0 && stepPort != port)
                return "[POLICY_BLOCKED] EXTERNAL_ADB_SESSION_PORT\n[EXIT=126]";
            port = stepPort;
        }
        if (timeoutMillis <= 0 || timeoutMillis > 900_000)
            return "[POLICY_BLOCKED] EXTERNAL_ARGUMENTS_INVALID\n[EXIT=126]";
        if (environment == null || environment.size() > 32) return "[POLICY_BLOCKED] EXTERNAL_ENV_INVALID\n[EXIT=126]";
        Process server = null;
        long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.MILLISECONDS.toNanos(timeoutMillis);
        StringBuilder out = new StringBuilder();
        try {
            server = runExternalServer(serverArgv, environment);
            if (server == null) return "[SHIZUKU_SERVICE_NOT_READY] Root service is closing\n[EXIT=124]";
            long ready = System.nanoTime() + java.util.concurrent.TimeUnit.MILLISECONDS.toNanos(Math.min(timeoutMillis, 15_000));
            String waited = awaitLoopbackServer(server, port, ready);
            if (waited != null) return "[EXTERNAL_ADB_SERVER_FAILED] " + waited + "\n[EXIT=124]";
            for (int i = 0; i < steps.size(); i++) {
                out.append(com.deepseekharness.app.util.ExternalStepOutput.marker(nonce, i));
                long left = java.util.concurrent.TimeUnit.NANOSECONDS.toMillis(deadline - System.nanoTime());
                if (left < 1_000) {
                    out.append("[STEP_NOT_RUN] session deadline reached; step was not started\n[EXIT=124]\n");
                    break;
                }
                String result = runExternalClient(steps.get(i), environment, left);
                out.append(result).append('\n');
                boolean ok = result.endsWith("[EXIT=0]") && !result.contains("[EXECUTION_UNKNOWN]");
                if (result.contains("[EXECUTION_UNKNOWN]")) break;
                if (!ok && !continueOnFailure) break;
            }
            return out.toString();
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            return out + "[EXECUTION_UNKNOWN] external adb session interrupted; do not replay\n[EXIT=125]";
        } catch (Throwable error) {
            return out + "[EXECUTION_UNKNOWN] " + SensitiveData.redact(String.valueOf(error)) + "\n[EXIT=125]";
        } finally {
            if (server != null) {
                Compat.destroy(server);
                synchronized (processes) { processes.remove(server); }
            }
        }
    }

    /** 启动 server；输出丢弃，避免管道写满阻塞 server。 */
    private Process runExternalServer(java.util.List<String> argv, java.util.Map<String, String> environment)
            throws java.io.IOException {
        ProcessBuilder builder = new ProcessBuilder(new java.util.ArrayList<>(argv))
            .redirectErrorStream(true)
            // Redirect.DISCARD 在旧 Android 上不可用，直接写 /dev/null。
            .redirectOutput(ProcessBuilder.Redirect.to(new java.io.File("/dev/null")));
        builder.environment().clear();
        builder.environment().put("PATH", "/system/bin:/system/xbin:/sbin:/vendor/bin");
        builder.environment().putAll(environment);
        synchronized (processes) {
            if (closing) return null;
            Process process = builder.start();
            processes.add(process);
            return process;
        }
    }

    /** 轮询 127.0.0.1:port，直到可连接；server 提前退出或超时返回原因，成功返回 null。 */
    private static String awaitLoopbackServer(Process server, int port, long deadline) throws InterruptedException {
        java.net.InetSocketAddress address = new java.net.InetSocketAddress(java.net.InetAddress.getLoopbackAddress(), port);
        while (true) {
            if (!server.isAlive()) return "server exited " + server.exitValue();
            try (java.net.Socket socket = new java.net.Socket()) {
                socket.connect(address, 250);
                return null;
            } catch (java.io.IOException notYet) {
                // server 尚未监听，继续等待。
            }
            if (System.nanoTime() >= deadline) return "server not listening on loopback";
            Thread.sleep(50);
        }
    }

    private String runExternalClient(java.util.List<String> argv, java.util.Map<String, String> environment, long timeoutMillis) {
        return execExternal(argv, environment, timeoutMillis);
    }

    /**
     * 用户显式选择放宽/完全放宽档后的 root 原始 shell。
     *
     * <p>只允许由 {@link RootShellMain} 在 uid 0 下调用；方法不在 AIDL 接口里，Shizuku 的 Binder
     * 调用方无法触达。原生策略已在 App 进程判定过档位，这里只做执行身份、长度和并发生命周期检查。
     */
    String execUnrestricted(String command) {
        if (android.os.Process.myUid() != 0)
            return com.deepseekharness.app.util.UiText.text("[POLICY_BLOCKED] 原始 root shell 仅允许 uid 0 执行\n[EXIT=126]");
        if (command == null || command.isEmpty()
                || command.length() > com.deepseekharness.app.util.DeviceShellPolicy.MAX_SHELL_COMMAND
                || command.indexOf('\0') >= 0)
            return com.deepseekharness.app.util.UiText.text("[POLICY_BLOCKED] ROOT_SHELL_PLAN_INVALID\n[EXIT=126]");
        Process p = null;
        try {
            ProcessBuilder pb = new ProcessBuilder("/system/bin/sh", "-c", command).redirectErrorStream(true);
            Map<String, String> env = pb.environment();
            env.put("PATH", "/system/bin:/system/xbin:/sbin:/vendor/bin");
            synchronized (processes) {
                if (closing) return com.deepseekharness.app.util.UiText.text("[SHIZUKU_SERVICE_NOT_READY] 设备 Shell 正在退出\n[EXIT=124]");
                p = pb.start(); processes.add(p);
            }
            BoundedProcessRunner.Result result = BoundedProcessRunner.collect(p, timeoutMillis, 262_144, Compat::destroy);
            return renderResult(result);
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

    private String renderResult(BoundedProcessRunner.Result result) {
        String output = result.output + (result.truncated ? com.deepseekharness.app.util.UiText.text("\n[OUTPUT_TRUNCATED] 输出超过 256 KiB，已截断") : "");
        String duration = timeoutMillis % 1000 == 0 ? timeoutMillis / 1000 + "s" : timeoutMillis + "ms";
        return output + (result.timedOut
                ? com.deepseekharness.app.util.UiText.format("\n命令执行超时(%s)，已终止本次进程\n[EXIT=timeout]", duration)
                : "\n[EXIT=" + result.exitCode + "]");
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
            return renderResult(result);
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
