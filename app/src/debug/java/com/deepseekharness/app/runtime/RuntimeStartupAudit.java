package com.deepseekharness.app.runtime;

import android.app.Activity;
import android.app.Instrumentation;
import android.content.Context;
import android.content.ContextWrapper;
import android.os.Bundle;
import android.os.SystemClock;
import com.deepseekharness.app.util.BoundedProcessRunner;
import com.deepseekharness.app.util.Compat;
import com.deepseekharness.app.util.InstallProbe;
import com.deepseekharness.app.util.SensitiveData;
import com.deepseekharness.app.util.ShellQuote;
import java.io.File;
import java.nio.charset.StandardCharsets;

/** 冷安装只写独立 cache，模块复现只写独立 fixture；不改用户配置或现有 profile。 */
public final class RuntimeStartupAudit extends Instrumentation {
    private Bundle args;
    private long started;
    private final StringBuilder log = new StringBuilder();
    @Override public void onCreate(Bundle value) { super.onCreate(value); args = value; start(); }
    private void report(String value) {
        String line = (SystemClock.elapsedRealtime() - started) + "ms " + SensitiveData.redact(value);
        log.append(line).append('\n');
        Bundle status = new Bundle(); status.putString("stream", line + "\n"); sendStatus(0, status);
    }
    @Override public void onStart() {
        started = SystemClock.elapsedRealtime(); Bundle result = new Bundle();
        Context app = getTargetContext(); File output = new File(app.getCacheDir(), "runtime-startup"); output.mkdirs();
        try {
            String mode = args.getString("mode", "links");
            if (mode.equals("terminal-maintenance")) {
                int repetitions = Integer.parseInt(args.getString("repeat", "1"));
                for (int i = 0; i < repetitions; i++) {
                    terminalMaintenance(app);
                    report("TERMINAL_ROUND " + (i + 1) + "/" + repetitions);
                }
            } else if (mode.equals("terminal-birth")) {
                terminalBirth(app);
            } else if (mode.equals("groups")) {
                groups(output);
            } else if (mode.equals("cold")) {
                String name = args.getString("name", "baseline");
                if (!name.matches("[a-z0-9-]+")) throw new IllegalArgumentException("无效测试目录");
                File base = new File(output, "cold-" + name);
                if (!base.mkdir()) throw new IllegalStateException("冷环境测试目录已存在");
                Context isolated = new ContextWrapper(app) {
                    @Override public Context getApplicationContext() { return this; }
                    @Override public File getFilesDir() { File f = new File(base, "files"); f.mkdirs(); return f; }
                    @Override public File getCacheDir() { File f = new File(base, "cache"); f.mkdirs(); return f; }
                };
                ProotBootstrap proot = new ProotBootstrap(isolated) {
                    @Override Process execRootfsForColdInstall(String command) throws java.io.IOException {
                        String runtime = args.getString("install_runtime", "automatic");
                        if (runtime.equals("proot")) return super.execRootfsForInstall(command);
                        if (runtime.equals("fail")) return super.execRootfsForColdInstall(
                                "cd /root/.dsha-bundled-tools; dpkg --unpack ./ca-certificates_*.deb; printf EXPECTED_INSTALL_FAILURE; exit 7");
                        return super.execRootfsForColdInstall(command);
                    }
                    @Override public Process execRootfsForInstall(String command) throws java.io.IOException {
                        if (command.equals("/bin/bash /root/dsh-bin/install-ubuntu-tools")) {
                            command = "PS4='+${EPOCHREALTIME} ' /bin/bash -x /root/dsh-bin/install-ubuntu-tools "
                                    + "> /root/.dsha-install-timing.log 2>&1; rc=$?; cat /root/.dsha-install-timing.log; exit $rc";
                        }
                        return super.execRootfsForInstall(command);
                    }
                };
                proot.extractOfflineBundle(new ProotBootstrap.ExtractionProgress() {
                    long last;
                    @Override public void onStage(String stage) { report("STAGE " + stage); }
                    @Override public void accept(Long done, Long total) {
                        long now = SystemClock.elapsedRealtime();
                        if (now - last > 5000) { last = now; report("BYTES " + done); }
                    }
                });
                report("COLD_EXTRACTED");
                java.lang.reflect.Method validate = com.deepseekharness.app.core.EnvironmentMaintenance.class
                        .getDeclaredMethod("validateRuntime", ProotBootstrap.class);
                validate.setAccessible(true); validate.invoke(null, proot);
                report("COLD_FULLY_VALIDATED");
            } else {
                ProotBootstrap proot = new ProotBootstrap(app); proot.ensureRuntimeFiles();
                if (mode.equals("command")) {
                    String command = new String(Compat.readAllBytes(new File(output, "command.sh")), StandardCharsets.UTF_8);
                    report(run(proot, app, args.getString("runtime", "proot"), command, 180000));
                } else if (mode.equals("links")) links(proot, app);
                else throw new IllegalArgumentException("未知模式");
            }
            result.putString("result", "PASS");
        } catch (Throwable error) { result.putString("failure", SensitiveData.redact(android.util.Log.getStackTraceString(error))); }
        finally {
            try { Compat.write(new File(output, "last.log"), log.toString().getBytes(StandardCharsets.UTF_8)); }
            catch (Exception ignored) { }
            finish(result.containsKey("failure") ? Activity.RESULT_CANCELED : Activity.RESULT_OK, result);
        }
    }
    private void terminalMaintenance(Context app) throws Exception {
        ProotBootstrap proot = new ProotBootstrap(app, "proot".equals(args.getString("terminal_runtime")));
        Process unrelated = new ProcessBuilder("/system/bin/sleep", "120").start();
        try {
        com.deepseekharness.app.PtySession[] terminal = {null};
        runOnMainSync(() -> {
            terminal[0] = com.deepseekharness.app.PtySession.start(proot, 80, 24, null);
            try {
                java.lang.reflect.Field field = com.deepseekharness.app.ui.PtyTerminalFragment.class.getDeclaredField("sessions");
                field.setAccessible(true);
                ((com.deepseekharness.app.util.TerminalTabs<com.deepseekharness.app.PtySession>)field.get(null)).add(terminal[0]);
            } catch (Exception e) { throw new IllegalStateException(e); }
        });
        File childFile = new File(proot.getRootfsDir(), "tmp/dsha-terminal-audit.pid");
        terminal[0].write("unset HISTFILE; sleep 120 & echo $! > /tmp/dsha-terminal-audit.pid\n");
        long deadline = SystemClock.elapsedRealtime() + 5000;
        while (!childFile.isFile() && SystemClock.elapsedRealtime() < deadline) Thread.sleep(20);
        if (!childFile.isFile()) throw new AssertionError("终端子进程未启动");
        int child = Integer.parseInt(Compat.readAll(childFile).trim()); childFile.delete();
        report("TERMINAL_STARTED child=" + child);
        if ("true".equals(args.getString("identity_probe"))) {
            int leader = terminal[0].session().getPid();
            report("IDENTITY_PROBE debug=" + com.deepseekharness.app.BuildConfig.DEBUG + " app=" + android.os.Process.myPid() + " leader=" + leader);
            try {
                String stat = Compat.readAll(new File("/proc/" + leader + "/stat"));
                var parsed = com.deepseekharness.app.util.ProcessIdentity.fromStat(stat, leader, android.os.Process.myPid());
                report("IDENTITY_PROBE laterStat=" + (parsed == null ? "not-owned" : "owned session=" + parsed.session + " started=" + parsed.started));
            } catch (Exception error) { report("IDENTITY_PROBE laterStat=" + error); }
        }
        if ("true".equals(String.valueOf(args.get("fail_once")))) {
            java.lang.reflect.Field identity = com.deepseekharness.app.PtySession.class.getDeclaredField("identity");
            identity.setAccessible(true); Object saved = identity.get(terminal[0]);
            boolean refused = false;
            try {
                identity.set(terminal[0], null);
                try { terminal[0].finishAndWait(200); } catch (java.io.IOException expected) { refused = true; }
            } finally { identity.set(terminal[0], saved); }
            if (!refused || !terminal[0].isRunning() || !com.deepseekharness.app.core.RuntimeTasks.isBusy())
                throw new AssertionError("首次核验失败未保留终端保护");
            report("FIRST_CLOSE_REFUSED_RETRYING_SAME_SESSION");
        }
        String action = args.getString("action", "none");
        if (action.equals("update") || action.equals("rebuild")) {
            var task = com.deepseekharness.app.core.BackupTask.get(app);
            if (!(action.equals("update") ? task.updateEnvironment() : task.rebuild()))
                throw new AssertionError("实际维护任务未接受");
            String previous = "";
            long end = SystemClock.elapsedRealtime() + 180000;
            while (task.busy() && SystemClock.elapsedRealtime() < end) {
                String message = task.snapshot().detail;
                if (!previous.equals(message)) { report(message); previous = message; }
                Thread.sleep(100);
            }
            if (task.snapshot().status != com.deepseekharness.app.util.BackupTaskState.Status.SUCCEEDED)
                throw new AssertionError("实际维护失败：" + task.snapshot().detail);
            report("TASK_SUCCEEDED " + task.snapshot().detail);
        }
        com.deepseekharness.app.BackupManager.runDataTask(com.deepseekharness.app.core.HarnessController.get(app), () -> {
            if (terminal[0].isRunning()) throw new AssertionError("终端尚未退出就进入维护");
            try {
                com.deepseekharness.app.util.WebPidIdentity state = com.deepseekharness.app.util.WebPidIdentity.parse(
                        Compat.readAll(new File("/proc/" + child + "/stat")), child);
                if (state != null && !state.exited()) throw new AssertionError("终端后台任务残留 " + child);
            } catch (java.io.FileNotFoundException gone) { }
            if (!Compat.isAlive(unrelated)) throw new AssertionError("回收终端误伤其他进程");
            report("TERMINAL_MAINTENANCE_ENTERED_AND_CHILD_GONE");
            return null;
        });
        } finally { Compat.destroy(unrelated); }
    }
    private void terminalBirth(Context app) throws Exception {
        if (com.deepseekharness.app.BuildConfig.DEBUG) throw new AssertionError("必须使用非调试版复测");
        ProotBootstrap proot = new ProotBootstrap(app);
        Process unrelated = new ProcessBuilder("/system/bin/sleep", "120").start();
        try {
            for (int i = 0; i < 20; i++) {
                // 覆盖 exec 立即失败和正常快速退出：身份必须在 waitFor 可以回收 PID 前记录。
                String executable = i % 2 == 0 ? "/system/bin/true" : "/dsha-missing-terminal-executable";
                ProotBootstrap fast = new ProotBootstrap(app) {
                    @Override public String[] ptyArgv(String... guestCmd) { return new String[]{executable}; }
                };
                com.deepseekharness.app.PtySession[] terminal = {null};
                runOnMainSync(() -> terminal[0] = com.deepseekharness.app.PtySession.start(fast, 80, 24, null));
                long deadline = SystemClock.elapsedRealtime() + 3000;
                while (terminal[0].isRunning() && SystemClock.elapsedRealtime() < deadline) Thread.sleep(10);
                if (terminal[0].isRunning()) throw new AssertionError("快速退出未结束");
                terminal[0].finishAndWait(3000);
                if (com.deepseekharness.app.core.RuntimeTasks.isBusy()) throw new AssertionError("快速退出工作锁残留");
            }
            report("FAST_EXIT_AND_EXEC_FAILURE_20_PASSED");
            java.lang.reflect.Constructor<com.deepseekharness.app.PtySession> constructor =
                    com.deepseekharness.app.PtySession.class.getDeclaredConstructor();
            constructor.setAccessible(true);
            for (int i = 0; i < 10; i++) {
                final Throwable[] failure = {null};
                com.deepseekharness.app.PtySession client = constructor.newInstance();
                com.deepseekharness.app.util.ProcessIdentity[] birth = {null};
                String[] parentRead = {""};
                runOnMainSync(() -> {
                    com.termux.terminal.TerminalSession raw = new com.termux.terminal.TerminalSession(
                            "/system/bin/sleep", "/", new String[]{"sleep", "120"}, proot.ptyEnv(), 100,
                            client);
                    try {
                        NativeProcess.capturePtyIdentity(value -> {
                            birth[0] = value;
                            try { Compat.readAll(new File("/proc/" + value.pid + "/stat")); parentRead[0] = "readable"; }
                            catch (java.io.IOException error) { parentRead[0] = String.valueOf(error); }
                            throw new IllegalStateException("EXPECTED_BIRTH_REJECTION");
                        },
                                () -> raw.initializeEmulator(80, 24));
                    } catch (Throwable error) { failure[0] = error; }
                });
                if (failure[0] == null || !String.valueOf(failure[0]).contains("EXPECTED_BIRTH_REJECTION"))
                    throw new AssertionError("出生身份登记失败未阻止 exec", failure[0]);
                if (birth[0] == null || new File("/proc/" + birth[0].pid).exists())
                    throw new AssertionError("握手失败未回收刚创建的子进程");
                if (i == 0) report("BEFORE_EXEC_PARENT_STAT " + parentRead[0]);
            }
            report("BIRTH_REJECTION_10_PASSED");
            ProotBootstrap beforeFork = new ProotBootstrap(app) {
                @Override public String[] ptyArgv(String... guestCmd) { throw new IllegalStateException("EXPECTED_BEFORE_FORK"); }
            };
            final boolean[] refused = {false};
            runOnMainSync(() -> {
                try { com.deepseekharness.app.PtySession.start(beforeFork, 80, 24, null); }
                catch (IllegalStateException expected) { refused[0] = true; }
            });
            if (!refused[0] || com.deepseekharness.app.core.RuntimeTasks.isBusy())
                throw new AssertionError("fork 前失败留下工作锁");
            if (!Compat.isAlive(unrelated)) throw new AssertionError("误伤无关进程");
            report("BEFORE_FORK_FAILURE_AND_UNRELATED_PROCESS_PASSED");
        } finally { Compat.destroy(unrelated); }
    }
    private void groups(File temporary) throws Exception {
        Process unrelated = new ProcessBuilder("/system/bin/sleep", "30").start();
        try {
            for (int code : new int[]{0, 7}) {
                try (IsolatedInstallProcess process = IsolatedInstallProcess.start(new ProcessBuilder(
                        "/system/bin/sh", "-c", "printf 'OWNED_GROUP_OK'; exit " + code).redirectErrorStream(true), temporary,getTargetContext())) {
                    BoundedProcessRunner.Result result = BoundedProcessRunner.collect(process, 3000, 8192, Compat::destroy);
                    if (result.timedOut || result.exitCode != code || !result.output.contains("OWNED_GROUP_OK"))
                        throw new AssertionError("进程组退出码或输出错误");
                }
                report("GROUP_EXIT " + code);
            }
            try (IsolatedInstallProcess process = IsolatedInstallProcess.start(new ProcessBuilder(
                    "/system/bin/sh", "-c", "sleep 30 & printf 'CHILD=%s\\n' $!; wait").redirectErrorStream(true), temporary,getTargetContext())) {
                BoundedProcessRunner.Result result = BoundedProcessRunner.collect(process, 300, 8192, Compat::destroy);
                if (!result.timedOut || !result.output.contains("CHILD=")) throw new AssertionError("没有触发真实子进程超时");
                report("GROUP_TIMEOUT_CLEANED " + result.output);
            }
            ProotBootstrap proot = new ProotBootstrap(getTargetContext()); proot.ensureRuntimeFiles();
            try (IsolatedInstallProcess process = (IsolatedInstallProcess) proot.execRootfsForColdInstall(
                    "sleep 30 & printf 'PROROOT_CHILD=%s\\n' $!; wait")) {
                BoundedProcessRunner.Result result = BoundedProcessRunner.collect(process, 1000, 8192, Compat::destroy);
                if (!result.timedOut || !result.output.contains("PROROOT_CHILD=")) throw new AssertionError("真实 proroot 子进程未参与超时测试");
                report("PROROOT_GROUP_TIMEOUT_CLEANED " + result.output);
            }
            final Throwable[] failure = {null};
            java.util.concurrent.CountDownLatch ready = new java.util.concurrent.CountDownLatch(1);
            Thread worker = new Thread(() -> {
                try (IsolatedInstallProcess process = IsolatedInstallProcess.start(new ProcessBuilder(
                        "/system/bin/sh", "-c", "sleep 30 & wait").redirectErrorStream(true), temporary,getTargetContext())) {
                    ready.countDown();
                    try {
                        BoundedProcessRunner.collect(process, 30000, 8192, Compat::destroy);
                        throw new AssertionError("安装没有中断");
                    } catch (InterruptedException expected) { report("GROUP_INTERRUPTED_CLEANED"); }
                } catch (Throwable error) { failure[0] = error; ready.countDown(); }
            });
            worker.start();
            if (!ready.await(3, java.util.concurrent.TimeUnit.SECONDS)) throw new AssertionError("中断测试未启动");
            worker.interrupt(); worker.join(7000);
            if (worker.isAlive() || failure[0] != null) throw new AssertionError("中断未回收", failure[0]);
            if (!Compat.isAlive(unrelated)) throw new AssertionError("误伤其他进程");
            report("UNRELATED_PROCESS_PRESERVED");
        } finally { Compat.destroy(unrelated); }
    }
    private String run(ProotBootstrap proot, Context app, String runtime, String command, long timeout) throws Exception {
        Process process = startExplicit(proot, app, runtime, command);
        BoundedProcessRunner.Result result = BoundedProcessRunner.collect(process, timeout, 256 * 1024, Compat::destroy);
        if (result.timedOut || result.exitCode != 0) throw new AssertionError(runtime + " exit=" + result.exitCode + " " + result.output);
        return result.output;
    }
    private Process startExplicit(ProotBootstrap proot, Context app, String runtime, String command) throws Exception {
        if (runtime.equals("proot")) return proot.execRootfsForInstall(command);
        ContainerRuntime rt = runtime.equals("proroot")
                ? new ContainerRuntime.Proroot(app, ContainerRuntime.Proroot.defaultDir(app))
                : new ContainerRuntime.Proot(app, new File(app.getApplicationInfo().nativeLibraryDir, "libproot.so"));
        java.lang.reflect.Method method = ProotBootstrap.class.getDeclaredMethod("startRootfs", String.class, ContainerRuntime.class, boolean.class);
        method.setAccessible(true);
        return (Process) method.invoke(proot, command, rt, false);
    }
    private void links(ProotBootstrap proot, Context app) throws Exception {
        String guest = "/root/.dsha-startup-link-probe-" + SystemClock.elapsedRealtime();
        File fixture = new File(proot.getRootfsDir(), guest.substring(1));
        File target = new File(fixture, "target"); target.mkdirs();
        Compat.write(new File(target, "package.json"), "{\"name\":\"dsha-audit\",\"type\":\"module\",\"exports\":\"./index.js\"}".getBytes(StandardCharsets.UTF_8));
        Compat.write(new File(target, "index.js"), "export default 42;".getBytes(StandardCharsets.UTF_8));
        String node = "import fs from 'node:fs'; import {createRequire} from 'node:module';"
                + "const r=createRequire(import.meta.url);"
                + "for(const op of [()=>fs.statSync('./node_modules/dsha-audit/package.json').size,()=>fs.realpathSync('./node_modules/dsha-audit'),()=>r.resolve('dsha-audit')])"
                + "{try{console.log('CHECK',op())}catch(e){console.log('CHECK_ERROR',e.code)}}"
                + "try{console.log('IMPORT', (await import('dsha-audit')).default)}catch(e){console.log('IMPORT_ERROR',e.code,e.message)}";
        try {
            for (String creator : new String[]{"host", "proot", "proroot"}) {
                for (String kind : new String[]{"absolute", "relative"}) {
                    String name = creator + "-" + kind;
                    File folder = new File(fixture, name); new File(folder, "node_modules").mkdirs();
                    Compat.write(new File(folder, "loader.mjs"), node.getBytes(StandardCharsets.UTF_8));
                    String link = guest + "/" + name + "/node_modules/dsha-audit";
                    String value = kind.equals("absolute") ? guest + "/target" : "../../target";
                    if (creator.equals("host")) android.system.Os.symlink(value, new File(folder, "node_modules/dsha-audit").getAbsolutePath());
                    else run(proot, app, creator, "python3 -B -c " + ShellQuote.arg("import os;os.symlink('" + value + "','" + link + "')"), 20000);
                    for (String reader : new String[]{"proot", "proroot"})
                        report(name + " reader=" + reader + "\n" + run(proot, app, reader,
                                "cd " + ShellQuote.arg(guest + "/" + name) + "; node loader.mjs", 20000));
                }
            }
            for (String reader : new String[]{"proot", "proroot"}) report("ACTUAL_PROFILE " + reader + "\n" + run(proot, app, reader,
                    "cd /root/.dsh/profiles/web; node --input-type=module -e " + ShellQuote.arg(
                    "try{await import('dsh-app-integration');console.log('APP_INTEGRATION_IMPORT_OK')}catch(e){console.log(e.code,e.message)}"), 20000));
        } finally { deleteFixture(fixture); }
    }
    private void deleteFixture(File file) throws Exception {
        if (android.system.OsConstants.S_ISDIR(android.system.Os.lstat(file.getAbsolutePath()).st_mode)) {
            File[] children = file.listFiles(); if (children == null) throw new IllegalStateException("无法清理测试目录");
            for (File child : children) deleteFixture(child);
        }
        if (!file.delete()) throw new IllegalStateException("无法清理测试文件");
    }
}
