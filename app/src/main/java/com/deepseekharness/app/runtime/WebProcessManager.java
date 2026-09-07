package com.deepseekharness.app.runtime;

import android.system.ErrnoException;
import android.system.Os;
import android.system.OsConstants;
import com.deepseekharness.app.util.Compat;
import com.deepseekharness.app.util.SensitiveData;
import com.deepseekharness.app.util.WebProcSel;
import java.io.File;
import java.io.FileInputStream;
import java.nio.charset.StandardCharsets;

/** 宿主侧按 PID 停止 Web；先写哨兵，不依赖可运行的 bash 或另起一个容器。 */
public class WebProcessManager {
    private final ProotBootstrap proot;
    public WebProcessManager(ProotBootstrap proot) { this.proot = proot; }

    /** 空串表示已退出；失败返回明确原因，绝不按端口或名称批量终止进程。 */
    public String stop() {
        File root = new File(proot.getRootfsDir(), "root");
        if (!root.isDirectory()) return ""; // 首次安装不能因停止检查而生成一个“半环境”。
        try {
            File sentinel = new File(proot.getRootfsDir(), WebProcSel.pidFileRel(WebProcSel.STOP_SENTINEL));
            if (Compat.isSymbolicLink(sentinel) || !sentinel.exists() && !sentinel.createNewFile())
                return "无法写入停止标记，尚未停止 Web";
            File pidFile = new File(proot.getRootfsDir(), WebProcSel.pidFileRel(WebProcSel.PID_WEB));
            if (Compat.isSymbolicLink(pidFile)) return "Web PID 文件异常，未终止任何进程";
            if (!pidFile.exists()) return "";
            if (!pidFile.isFile() || pidFile.length() > 32)
                return "Web PID 文件异常，未终止任何进程";
            int pid = WebProcSel.parsePid(new String(Compat.readAllBytes(pidFile), StandardCharsets.UTF_8));
            if (pid < 0) return "Web PID 无效，未终止任何进程";
            if (!alive(pid)) return "";
            File cmdline = new File("/proc/" + pid + "/cmdline");
            if (cmdline.canRead()) {
                byte[] bytes = new byte[8192]; int count;
                try (FileInputStream in = new FileInputStream(cmdline)) { count = in.read(bytes); }
                if (count > 0 && !WebProcSel.looksLikeWeb(new String(bytes, 0, count, StandardCharsets.UTF_8).replace('\0', ' ')))
                    return "PID 指向的进程不是 dsh Web，已保留；请检查环境状态";
            }
            Os.kill(pid, OsConstants.SIGTERM);
            long deadline = android.os.SystemClock.elapsedRealtime() + 3000;
            do {
                if (!alive(pid)) return "";
                Thread.sleep(50);
            } while (android.os.SystemClock.elapsedRealtime() < deadline);
            return "已请求停止，Web 尚未退出；稍后可重试，未强杀容器启动器";
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();return "停止等待被中断，请检查 Web 状态";
        } catch (Exception e) { return "停止 Web 失败：" + SensitiveData.redact(String.valueOf(e)); }
    }

    private static boolean alive(int pid) throws ErrnoException {
        try { Os.kill(pid, 0);return true; }
        catch (ErrnoException e) { if (e.errno == OsConstants.ESRCH) return false;throw e; }
    }
}
