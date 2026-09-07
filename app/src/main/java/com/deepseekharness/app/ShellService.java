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
    public ShellService() { this(30_000); }
    /** 包内测试可缩短等待；Binder 入口始终使用默认的 30 秒。 */
    ShellService(long timeoutMillis) {
        if (timeoutMillis <= 0 || timeoutMillis > 30_000) throw new IllegalArgumentException("无效的命令期限");
        this.timeoutMillis = timeoutMillis;
    }

    @Override
    public String exec(String cmd) {
        try {
            ProcessBuilder pb = new ProcessBuilder("sh", "-c", cmd).redirectErrorStream(true);
            Map<String, String> env = pb.environment();
            String oldPath = env.get("PATH");
            env.put("PATH", (oldPath == null || oldPath.isEmpty() ? "" : oldPath + ":") + "/system/bin:/system/xbin:/sbin:/vendor/bin");
            Process p = pb.start();
            BoundedProcessRunner.Result result = BoundedProcessRunner.collect(p, timeoutMillis, 262_144, Compat::destroy);
            String output = result.output + (result.truncated ? "\n[OUTPUT_TRUNCATED] 输出超过 256 KiB，已截断" : "");
            String duration = timeoutMillis % 1000 == 0 ? timeoutMillis / 1000 + "s" : timeoutMillis + "ms";
            return output + (result.timedOut ? "\n[EXIT=timeout] 命令执行超时(" + duration + ")，已终止本次进程"
                    : "\n[EXIT=" + result.exitCode + "]");
        }
        catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return "[EXIT=interrupted] 命令等待已中断，本次进程已关闭";
        }
        catch (Throwable e) {
            return "ERROR: " + SensitiveData.redact(String.valueOf(e));
        }
    }
}
