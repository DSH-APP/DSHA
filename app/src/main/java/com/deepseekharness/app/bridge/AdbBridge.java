package com.deepseekharness.app.bridge;

import android.content.Context;
import android.util.Base64;

import com.deepseekharness.app.runtime.ProotBootstrap;
import com.deepseekharness.app.util.SensitiveData;
import com.deepseekharness.app.util.AdbResult;
import com.deepseekharness.app.util.ShellQuote;
import com.deepseekharness.app.core.ConfigStore;
import com.deepseekharness.app.BackupManager;
import com.deepseekharness.app.util.AdbEnvironmentTask;
import com.deepseekharness.app.util.EnvironmentTaskGate;
import com.deepseekharness.app.util.InstallProcess;
import com.deepseekharness.app.util.Compat;
import com.deepseekharness.app.util.AdbWheelCache;
import com.deepseekharness.app.util.FileIntegrity;
import com.deepseekharness.app.runtime.TarGzipExtractor;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

/**
 * ADB 无线配对桥（绕过 Shizuku，通道直连设备 adbd）。
 * 把 assets 里的 adb-pair.py / adb-shell.py / adb-setup.sh 注入 rootfs，
 * 在容器内用 TLS1.3-PSK + SPAKE2 完成「无线调试配对 → 直连 adbd」，拿到 uid=2000(shell)。
 */
public final class AdbBridge {

    private static final String[] SCRIPTS = {"adb-pair.py", "adb-shell.py", "adb-setup.sh"};
    /** assets 脚本版本：每次改脚本 +1，旧 APK 的残留脚本会因版本不符被强制重注入。
     *  15：单次确认、有限时连接、执行后不重放、真实退出码、本机 mDNS。 */
    private static final String SCRIPT_VERSION = "15";
    private static final Object SETTINGS_LOCK = new Object();
    private static final java.util.concurrent.atomic.AtomicBoolean PAIRING = new java.util.concurrent.atomic.AtomicBoolean();

    public static boolean isPairing() { return PAIRING.get(); }

    private AdbBridge() {
    }

    /** 完整配对/验证/后台探活的同步范围；内部公开方法自动复用当前 owner。 */
    public static <T> T runEnvironmentTask(Context ctx, String kind,
                                          EnvironmentTaskGate.Operation<T> operation) throws Exception {
        Context app = ctx.getApplicationContext();
        return AdbEnvironmentTask.run(kind,
                () -> BackupManager.hasPendingMaintenance(app.getFilesDir()) || BackupManager.isRestoring(), operation);
    }

    private static String environmentResult(ProotBootstrap proot, String kind,
                                              EnvironmentTaskGate.Operation<String> operation) {
        try {
            File files = proot.getRootfsDir().getParentFile().getParentFile();
            return AdbEnvironmentTask.run(kind,
                    () -> BackupManager.hasPendingMaintenance(files) || BackupManager.isRestoring(), operation);
        } catch (AdbEnvironmentTask.Busy e) {
            return "ENVIRONMENT_BUSY: " + e.getMessage();
        } catch (Exception e) {
            if (e instanceof InterruptedException) Thread.currentThread().interrupt();
            return "ADB_ERROR: " + SensitiveData.redact(String.valueOf(e));
        }
    }

    /** 调用方已经持有环境凭据；包括超时回收在内，进程确实退出后才结束操作。 */
    private static String execOwned(ProotBootstrap proot, String command, long timeoutMs) {
        if (!EnvironmentTaskGate.ownsCurrentThread()) throw new IllegalStateException("ADB 命令缺少环境任务凭据");
        if (Thread.currentThread().isInterrupted()) return "ADB_CANCELLED: 操作已取消，未启动命令";
        StringBuilder output = new StringBuilder();
        try (com.deepseekharness.app.core.RuntimeTasks work = com.deepseekharness.app.core.RuntimeTasks.begin()) {
            int exit = InstallProcess.read(proot.execRootfsForInstall(command), timeoutMs, true,
                    () -> Thread.currentThread().isInterrupted(), line -> {
                        if (output.length() < 131072) output.append(line).append('\n');
                    }, Compat::destroy);
            if (exit != 0) output.append("[ADB_PROCESS_EXIT=").append(exit).append("]\n");
        } catch (Exception error) {
            InstallProcess.CleanupFailure cleanup = InstallProcess.cleanupFailure(error);
            if (cleanup != null) {
                android.util.Log.w("DSHA-ADB", "ADB 进程仍在回收，继续保留环境任务凭据");
                while (!cleanup.awaitExit(1000)) cleanup.retry();
            }
            if (error instanceof InterruptedException) Thread.currentThread().interrupt();
            output.append("ADB_ERROR: ").append(SensitiveData.redact(String.valueOf(error))).append('\n');
        }
        return SensitiveData.redact(output.toString());
    }

    private static String execOwned(ProotBootstrap proot, String command) {
        return execOwned(proot, command, 60_000);
    }

    public static boolean injected(ProotBootstrap proot) {
        return "YES".equals(environmentResult(proot, "检查 ADB 脚本", () -> injectedState(proot)));
    }

    private static String injectedState(ProotBootstrap proot) {
        String r = execOwned(proot,
                "test -f /root/.dsh/script-version && cat /root/.dsh/script-version || echo NO");
        if (r == null) return "UNKNOWN";
        String v = r.trim();
        if (v.isEmpty()) return "UNKNOWN";
        return SCRIPT_VERSION.equals(v) ? "YES" : "NO";
    }

    /** 幂等注入：把三个 assets 脚本 base64 写入 /root/.dsh/ 并加执行位 + 写版本标记。 */
    public static String inject(Context ctx, ProotBootstrap proot) {
        return environmentResult(proot, "更新 ADB 脚本", () -> injectOwned(ctx, proot));
    }

    private static String injectOwned(Context ctx, ProotBootstrap proot) {
        StringBuilder cmds = new StringBuilder("set -e; mkdir -p /root/.dsh; ");
        for (String name : SCRIPTS) {
            String content = readAsset(ctx, name);
            if (content.isEmpty()) return "SCRIPTS_MISSING: 随包缺少 " + name;
            String b64 = Base64.encodeToString(content.getBytes(StandardCharsets.UTF_8), Base64.NO_WRAP);
            cmds.append("printf '%s' '").append(b64).append("' | base64 -d > /root/.dsh/").append(name).append(".new")
                    .append("; chmod +x /root/.dsh/").append(name).append(".new; mv -f /root/.dsh/")
                    .append(name).append(".new /root/.dsh/").append(name).append("; ");
        }
        cmds.append("printf '%s' '").append(SCRIPT_VERSION).append("' > /root/.dsh/script-version; ");
        return execOwned(proot, cmds.toString());
    }

    private static String setup(ProotBootstrap proot) {
        return execOwned(proot, "bash /root/.dsh/adb-setup.sh 2>&1", 180_000);
    }

    /** 幂等准备：注入脚本 + wheels + glibc python + Java 解包 wheels（适配 Android 无网、无 pip）。 */
    public static String ensureReady(Context ctx, ProotBootstrap proot) {
        return ensureReady(ctx, proot, s -> { });
    }

    public static String ensureReady(Context ctx, ProotBootstrap proot, java.util.function.Consumer<String> progress) {
        return environmentResult(proot, "准备 ADB 环境", () -> ensureReadyOwned(ctx, proot, progress));
    }

    private static String ensureReadyOwned(Context ctx, ProotBootstrap proot, java.util.function.Consumer<String> progress) {
        if (Thread.currentThread().isInterrupted()) return "ADB_CANCELLED: 环境准备已取消";
        StringBuilder sb = new StringBuilder();
        progress.accept("正在同步 ADB 授权设置…");
        String settings = applySettings(ctx, proot);
        if (!AdbResult.marker(settings, "SETTINGS_APPLIED")) return settings;
        progress.accept("正在检查 ADB 脚本版本…");
        if (!injected(proot)) {
            sb.append(inject(ctx, proot)).append('\n');
            if (!injected(proot)) return "SCRIPTS_FAILED: ADB 脚本更新未完成\n" + sb;
        }
        progress.accept("正在检查 Ubuntu Python…");
        if (!proot.ensureGlibcPython()) {
            return "GLIBC_PY_INSTALL_FAIL: 无法安装 Ubuntu Python3，请先修复基础环境\n" + sb;
        }
        progress.accept("正在检查离线 ADB 依赖…");
        if (!depsOk(proot)) {
            progress.accept("正在从 APK 补齐缺少的 wheel，保留已恢复的修改版与额外文件…");
            String cache = injectWheels(ctx, proot);
            sb.append(cache).append('\n');
            if (!AdbResult.marker(cache, "WHEELS_CACHE_READY")) return sb.toString();
            progress.accept("正在校验所有缓存 wheel 并安装；损坏缓存会原样保留并报告…");
            String extracted = extractWheelsJava(proot);
            sb.append(extracted).append('\n');
            if (!AdbResult.marker(extracted, "WHEELS_JAVA_EXTRACTED")) return sb.toString();
        }
        if (keyPresent(proot) && depsOk(proot) && wrapperPresent(proot)) {
            return sb.append("SETUP_DONE\n").toString();
        }
        progress.accept("正在安装离线依赖与新版 ADB 入口，最长等待 3 分钟…");
        sb.append(setup(proot)).append('\n');
        if (!keyPresent(proot) || !depsOk(proot) || !wrapperPresent(proot)) {
            return "SETUP_FAILED: 安装后验证未通过，请检查以下输出\n"
                    + sb.toString().replace("SETUP_DONE", "SETUP_INCOMPLETE");
        }
        return sb.toString();
    }

    /** 保存配置后调用；仅同步授权标记，不启动命令、不读取任何密钥。 */
    public static String applySettings(Context ctx, ProotBootstrap proot) {
        return environmentResult(proot, "同步 ADB 授权", () -> applySettingsOwned(ctx, proot));
    }

    private static String applySettingsOwned(Context ctx, ProotBootstrap proot) {
        synchronized (SETTINGS_LOCK) {
            if (!proot.isEnvironmentReady()) return "SETTINGS_PENDING: 环境未就绪，下次准备 ADB 时同步授权设置";
            try {
                ConfigStore config = new ConfigStore(ctx.getApplicationContext());
                File dir = new File(proot.getRootfsDir(), "root/.dsh");
                if (!dir.isDirectory() && !dir.mkdirs()) throw new java.io.IOException("无法创建 ADB 设置目录");
                // 先收回旧授权，再按当前偏好写入；新安装缺少禁用标记时默认确认。
                setFlag(new File(dir, "allow-root-shell"), config.isRootShellAllowed());
                setFlag(new File(dir, "confirm-shell-disabled"), !config.isConfirmShell());
                setFlag(new File(dir, "confirm-shell-enabled"), config.isConfirmShell());
                return "SETTINGS_APPLIED: ADB 授权设置已同步";
            } catch (Exception e) {
                return "SETTINGS_FAILED: ADB 授权设置同步失败：" + SensitiveData.redact(String.valueOf(e));
            }
        }
    }

    private static void setFlag(File file, boolean enabled) throws java.io.IOException {
        android.util.AtomicFile atomic = new android.util.AtomicFile(file);
        if (!enabled) {
            atomic.delete();
            if (file.exists()) throw new java.io.IOException("不能移除授权标记 " + file.getName());
            return;
        }
        FileOutputStream out = null;
        try {
            out = atomic.startWrite();
            out.write("1\n".getBytes(StandardCharsets.UTF_8));
            atomic.finishWrite(out);
        } catch (java.io.IOException e) {
            if (out != null) atomic.failWrite(out);
            throw e;
        }
    }

    /**
     * 用 Java 把 wheels（zip）解包到 glibc python 的 dist-packages。
     * adb_shell_wifi / spake2 / cryptography 是 manylinux(glibc) 轮子，bionic 的 Termux python
     * 加载不了；glibc python + 本方法 = 与 1.1.9.1（rootfs 预装 glibc python3）等效。
     */
    public static String extractWheelsJava(ProotBootstrap proot) {
        return environmentResult(proot, "安装 ADB 离线依赖", () -> extractWheelsOwned(proot));
    }

    private static String extractWheelsOwned(ProotBootstrap proot) {
        try {
            return AdbWheelCache.install(new File(proot.getRootfsDir(), "root/.dsh/wheels"),
                    new File(proot.getRootfsDir(), "usr/lib/python3/dist-packages"),
                    proot.getRootfsDir().getParentFile());
        } catch (Exception e) {
            return "WHEELS_EXTRACT_FAIL: " + SensitiveData.redact(String.valueOf(e));
        }
    }

    private static boolean wrapperPresent(ProotBootstrap proot) {
        String r = execOwned(proot, "test -x /root/dsh-bin/adb-shell && grep -q '^# DSHA_ADB_SCRIPT_VERSION="
                + SCRIPT_VERSION + "$' /root/dsh-bin/adb-shell && echo YES || echo NO");
        return r != null && r.contains("YES");
    }

    /** APK 归档先进入临时目录，再按名称补缺；不覆盖恢复出来的 wheel 或缓存归档。 */
    private static String injectWheels(Context ctx, ProotBootstrap proot) {
        File stage = new File(ctx.getCacheDir(), "adb-wheel-bundle-" + java.util.UUID.randomUUID());
        String boundary;
        try {
            if (!stage.mkdir()) throw new java.io.IOException("无法创建 APK wheel 临时目录");
            boundary = stage.getCanonicalPath();
        } catch (Exception e) { return "WHEELS_INJECT_FAIL: " + SensitiveData.redact(String.valueOf(e)); }
        String result, cleanup = "";
        try {
            File archive = new File(stage, "adb-wheels.bundle");
            InputStream source;
            try { source = ctx.getAssets().open("adb-wheels.bin"); }
            catch (java.io.IOException missingBin) { source = ctx.getAssets().open("adb-wheels.tar.gz"); }
            try (InputStream in = source; FileOutputStream out = new FileOutputStream(archive)) {
                FileIntegrity.copy(in, out, 128L * 1024 * 1024);
            }
            File bundled = new File(stage, "wheels");
            if (!bundled.mkdir()) throw new java.io.IOException("无法创建 wheel 解包目录");
            TarGzipExtractor.extract(archive, bundled);
            AdbWheelCache.Merge report = AdbWheelCache.fillMissing(bundled,
                    new File(proot.getRootfsDir(), "root/.dsh/wheels"), archive,
                    new File(proot.getRootfsDir(), "root/.dsh/adb-wheels.tar.gz"));
            result = report.message();
        } catch (Exception e) {
            result = "WHEELS_INJECT_FAIL: " + SensitiveData.redact(String.valueOf(e))
                    + "；已有缓存原样保留，未用 APK 覆盖";
        } finally {
            try { AdbWheelCache.removeStage(stage, boundary); }
            catch (java.io.IOException e) { cleanup = "\nWHEELS_STAGE_CLEANUP_WARN: " + SensitiveData.redact(String.valueOf(e)); }
        }
        return result + cleanup;
    }

    private static boolean keyPresent(ProotBootstrap proot) {
        String r = execOwned(proot, "test -f /root/.dsh/adbkeys/adbkey && test -f /root/.dsh/adbkeys/adbkey.pub && echo YES || echo NO");
        return r != null && r.contains("YES");
    }

    private static boolean depsOk(ProotBootstrap proot) {
        String r = execOwned(proot, "python3 -c 'import adb_shell_wifi; from spake2.spake2 import Spake2_Alice, Spake2_Bob' 2>/dev/null && echo YES || echo NO", 60_000);
        return r != null && r.contains("YES");
    }

    /** 单次配对。pairPort 为空时脚本内尝试 mdns 发现；host 为 App 解析出的真实 IP。 */
    public static String pair(ProotBootstrap proot, String code, String pairPort, String connectPort, String host) {
        return environmentResult(proot, "ADB 配对与连接验证", () -> pairOwned(proot, code, pairPort, connectPort, host));
    }

    private static String pairOwned(ProotBootstrap proot, String code, String pairPort, String connectPort, String host) {
        if (!AdbResult.code(code)) return "INVALID_CODE: 配对码必须恰好为 6 位数字";
        if (!PAIRING.compareAndSet(false, true)) return "PAIR_BUSY: 已有配对正在进行，请等待结果";
        try {
            String c = "python3 -u /root/.dsh/adb-pair.py --code " + ShellQuote.arg(code)
                    + pairOptions(pairPort, connectPort, host);
            String out = execOwned(proot, c, 120_000);
            if (AdbResult.pairState(out) == AdbResult.PairState.CONNECTED) {
                out += "\n" + grantSecureSettings(proot);
            }
            return SensitiveData.redact(out == null ? "PAIR_ERROR: 配对进程没有返回结果" : out);
        } catch (Exception e) {
            return "PAIR_ERROR: " + SensitiveData.redact(String.valueOf(e));
        } finally {
            PAIRING.set(false);
        }
    }

    public static String verify(ProotBootstrap proot, String connectPort, String host) {
        return environmentResult(proot, "验证 ADB 连接", () -> verifyOwned(proot, connectPort, host));
    }

    private static String verifyOwned(ProotBootstrap proot, String connectPort, String host) {
        try {
            String out = execOwned(proot, "python3 -u /root/.dsh/adb-pair.py --verify-only"
                    + pairOptions("", connectPort, host), 60_000);
            if (AdbResult.pairState(out) == AdbResult.PairState.CONNECTED) out += "\n" + grantSecureSettings(proot);
            return SensitiveData.redact(out == null ? "CONNECT_WARN: 验证没有返回结果" : out);
        } catch (Exception e) {
            return "CONNECT_WARN: " + SensitiveData.redact(String.valueOf(e));
        }
    }

    private static String pairOptions(String pairPort, String connectPort, String host) {
        StringBuilder out = new StringBuilder();
        int pp = AdbResult.port(pairPort), cp = AdbResult.port(connectPort);
        if (pp > 0) out.append(" --port ").append(pp);
        if (cp > 0) out.append(" --connect-port ").append(cp);
        if (host != null && !host.trim().isEmpty()) {
            if (!localAddresses().contains(host.trim())) throw new IllegalArgumentException("请填写本机无线调试页面的 IP 地址");
            out.append(" --host ").append(ShellQuote.arg(host.trim()));
        }
        return out.toString();
    }

    /** 配对成功后通过 adb shell（uid=2000）给本 App 授予 WRITE_SECURE_SETTINGS，
     *  之后开机广播可自动开启无线调试（保活依赖）。 */
    private static String grantSecureSettings(ProotBootstrap proot) {
        try {
            String pkg = "com.dsh.client";
            String r = execOwned(proot, "DSH_INTERNAL=1 python3 /root/.dsh/adb-shell.py --connect-timeout 15 --timeout 10 pm grant "
                    + pkg + " android.permission.WRITE_SECURE_SETTINGS 2>&1", 45_000);
            android.util.Log.i("DSHA-ADB", "WRITE_SECURE_SETTINGS 授权结果: " + SensitiveData.redact(r));
            return r != null && r.trim().endsWith("[EXIT=0]") ? "KEEPALIVE_OK: 已允许自动恢复无线调试"
                    : "KEEPALIVE_WARN: 连接已验证，但自动恢复授权未完成；重启后可能需手动打开无线调试\n" + SensitiveData.redact(r);
        } catch (Throwable t) {
            android.util.Log.w("DSHA-ADB", "WRITE_SECURE_SETTINGS 授权失败: "
                    + SensitiveData.redact(String.valueOf(t)));
            return "KEEPALIVE_WARN: 自动恢复授权未完成；重启后可能需手动打开无线调试";
        }
    }

    /** 状态快照：key/deps/connect_port（供 UI 展示）。 */
    public static String status(ProotBootstrap proot) {
        return environmentResult(proot, "读取 ADB 状态", () -> statusOwned(proot));
    }

    private static String statusOwned(ProotBootstrap proot) {
        String cmd = "K=$(test -f /root/.dsh/adbkeys/adbkey && echo YES || echo NO); "
                + "D=$(python3 -c 'import adb_shell_wifi; from spake2.spake2 import Spake2_Alice, Spake2_Bob' 2>/dev/null && echo YES || echo NO); "
                + "P=$(test -f /root/.dsh/adbkeys/connect_port && cat /root/.dsh/adbkeys/connect_port || echo -); "
                + "echo 'key='$K' deps='$D' port='$P";
        return execOwned(proot, cmd, 60_000);
    }

    /** 后台探测也使用同一进程回收与环境任务范围。 */
    public static String probe(ProotBootstrap proot, Endpoint endpoint) {
        return environmentResult(proot, "ADB 后台探活", () -> {
            String options = endpoint == null ? "" : " --host " + ShellQuote.arg(endpoint.host) + " --port " + endpoint.port;
            return execOwned(proot, "DSH_INTERNAL=1 python3 /root/.dsh/adb-shell.py"
                    + " --connect-timeout 20 --timeout 10" + options + " id 2>&1", 60_000);
        });
    }

    public static java.util.Set<String> localAddresses() {
        java.util.Set<String> result = new java.util.HashSet<>();
        result.add("127.0.0.1");
        result.add("::1");
        try {
            java.util.Enumeration<java.net.NetworkInterface> interfaces = java.net.NetworkInterface.getNetworkInterfaces();
            while (interfaces != null && interfaces.hasMoreElements()) {
                java.net.NetworkInterface ni = interfaces.nextElement();
                if (!ni.isUp()) continue;
                java.util.Enumeration<java.net.InetAddress> addresses = ni.getInetAddresses();
                while (addresses.hasMoreElements()) result.add(addresses.nextElement().getHostAddress());
            }
        } catch (Exception ignored) { }
        return result;
    }

    public static final class Endpoint {
        public final String host;
        public final int port;
        Endpoint(String host, int port) { this.host = host; this.port = port; }
    }

    /** 后台调用。每轮独立、串行解析，结束后停止发现，迟到回调不能污染新一轮。 */
    public static Endpoint discover(Context ctx, String type, long timeoutMs,
                                     java.util.function.Consumer<String> progress) {
        android.net.nsd.NsdManager manager = (android.net.nsd.NsdManager) ctx.getSystemService(Context.NSD_SERVICE);
        if (manager == null) { progress.accept("系统没有端口发现服务，请手动填写端口"); return null; }
        java.util.Set<String> local = localAddresses();
        java.util.concurrent.CountDownLatch done = new java.util.concurrent.CountDownLatch(1);
        java.util.concurrent.atomic.AtomicBoolean closed = new java.util.concurrent.atomic.AtomicBoolean();
        java.util.concurrent.atomic.AtomicReference<Endpoint> result = new java.util.concurrent.atomic.AtomicReference<>();
        class Discovery implements android.net.nsd.NsdManager.DiscoveryListener {
            final java.util.ArrayDeque<android.net.nsd.NsdServiceInfo> queue = new java.util.ArrayDeque<>();
            boolean resolving;
            @Override public void onDiscoveryStarted(String t) { if (closed.get()) stop(); }
            @Override public void onDiscoveryStopped(String t) { }
            @Override public void onStartDiscoveryFailed(String t, int error) {
                if (!closed.get()) progress.accept("端口发现未启动（错误 " + error + "），可手动填写端口");
                done.countDown();
            }
            @Override public void onStopDiscoveryFailed(String t, int error) { }
            @Override public synchronized void onServiceFound(android.net.nsd.NsdServiceInfo info) {
                if (closed.get() || queue.size() >= 32) return;
                queue.add(info);
                next();
            }
            @Override public synchronized void onServiceLost(android.net.nsd.NsdServiceInfo info) {
                queue.removeIf(item -> item.getServiceName().equals(info.getServiceName()));
            }
            synchronized void next() {
                if (closed.get() || resolving || queue.isEmpty()) return;
                resolving = true;
                android.net.nsd.NsdServiceInfo info = queue.remove();
                try {
                    manager.resolveService(info, new android.net.nsd.NsdManager.ResolveListener() {
                        @Override public void onResolveFailed(android.net.nsd.NsdServiceInfo i, int error) {
                            if (!closed.get()) progress.accept("一个服务解析失败（错误 " + error + "），继续查找本机端口…");
                            resolved();
                        }
                        @Override public void onServiceResolved(android.net.nsd.NsdServiceInfo i) {
                            if (!closed.get() && i.getHost() != null && i.getPort() > 0 && i.getPort() <= 65535
                                    && local.contains(i.getHost().getHostAddress())) {
                                result.compareAndSet(null, new Endpoint(i.getHost().getHostAddress(), i.getPort()));
                                done.countDown();
                            }
                            resolved();
                        }
                    });
                } catch (RuntimeException e) {
                    resolving = false;
                    next();
                }
            }
            synchronized void resolved() { resolving = false; next(); }
            void stop() {
                try { manager.stopServiceDiscovery(this); } catch (RuntimeException ignored) { }
            }
        }
        Discovery discovery = new Discovery();
        try {
            manager.discoverServices(type, android.net.nsd.NsdManager.PROTOCOL_DNS_SD, discovery);
            if (!done.await(timeoutMs, java.util.concurrent.TimeUnit.MILLISECONDS))
                progress.accept("未在时限内发现本机端口，请确认系统配对弹窗仍打开，或手动填写端口");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (RuntimeException e) {
            progress.accept("端口发现失败：" + SensitiveData.redact(String.valueOf(e)));
        } finally {
            closed.set(true);
            discovery.stop();
        }
        return result.get();
    }

    private static String readAsset(Context ctx, String name) {
        try {
            java.io.InputStream in = ctx.getAssets().open(name);
            java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream();
            byte[] buf = new byte[16384];
            int n;
            while ((n = in.read(buf)) != -1) bos.write(buf, 0, n);
            in.close();
            // 资产在 Windows 检出时可能是 CRLF，注入容器后 bash 认不了 \r → 统一转 LF
            return bos.toString("UTF-8").replace("\r\n", "\n").replace("\r", "\n");
        } catch (Exception e) {
            return "";
        }
    }
}
