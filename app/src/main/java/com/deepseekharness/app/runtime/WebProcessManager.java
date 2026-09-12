package com.deepseekharness.app.runtime;

import android.system.ErrnoException;
import android.system.Os;
import android.system.OsConstants;
import com.deepseekharness.app.util.Compat;
import com.deepseekharness.app.util.SensitiveData;
import com.deepseekharness.app.util.WebPidIdentity;
import com.deepseekharness.app.util.WebProcSel;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

/** 宿主侧统一 Web 停止与维护判据；只给经身份核验的 PID 发信号。 */
public class WebProcessManager {
    enum Kind { GONE, WEB, OTHER, DENIED }
    static final class ProcessState {
        final Kind kind; final WebPidIdentity identity; final String command;
        ProcessState(Kind kind, WebPidIdentity identity, String command) { this.kind = kind; this.identity = identity; this.command = command; }
    }
    private static final class ProcessInspectionException extends IOException {
        ProcessInspectionException(IOException cause) { super(cause.getMessage(), cause); }
    }
    private final ProotBootstrap proot;
    public WebProcessManager(ProotBootstrap proot) { this.proot = proot; }
    private File root() { return new File(proot.getRootfsDir(), "root"); }
    private File pidFile() { return new File(root(), ".dsha-web.pid"); }
    private File identityFile() { return new File(root(), ".dsha-web.identity"); }
    private String pidRecord() throws IOException {
        File file = pidFile();
        if (Compat.isSymbolicLink(file)) throw new IOException(com.deepseekharness.app.util.UiText.text("Web PID 文件异常，未终止任何进程"));
        if (!file.exists()) return null;
        if (!file.isFile() || file.length() > 32) throw new IOException(com.deepseekharness.app.util.UiText.text("Web PID 文件异常，未终止任何进程"));
        String value = Compat.readAll(file);
        if (WebProcSel.parsePid(value) < 0) throw new IOException(com.deepseekharness.app.util.UiText.text("Web PID 无效，未终止任何进程"));
        return value;
    }
    private void sentinel() throws IOException {
        File file = new File(root(), ".dsha-stopped");
        if (Compat.isSymbolicLink(file) || !file.exists() && !file.createNewFile())
            throw new IOException(com.deepseekharness.app.util.UiText.text("无法写入停止标记，尚未停止 Web"));
    }
    private static String readProc(int pid, String name) throws IOException {
        try (FileInputStream input = new FileInputStream("/proc/" + pid + "/" + name)) {
            byte[] bytes = new byte[16384]; int count = input.read(bytes);
            if (count == bytes.length) throw new IOException(com.deepseekharness.app.util.UiText.text("进程信息超过核验上限"));
            return count <= 0 ? "" : new String(bytes, 0, count, StandardCharsets.UTF_8);
        }
    }
    /** 包内测试可模拟 stat/cmdline 之间发生退出；生产仍直接读取内核。 */
    String readProcessFile(int pid, String name) throws IOException { return readProc(pid, name); }
    /** 包内缝供测试注入系统读取失败；生产始终从内核取证。 */
    ProcessState inspect(int pid) throws IOException {
        IOException failure = null;
        for (int attempt = 0; attempt < 3; attempt++) {
            try { return inspectOnce(pid); }
            catch (IOException error) {
                failure = error;
                if (attempt < 2) try { Thread.sleep(10); }
                catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt(); throw new IOException(com.deepseekharness.app.util.UiText.text("进程核验被中断"), interrupted);
                }
            }
        }
        throw new ProcessInspectionException(failure);
    }

    private ProcessState inspectOnce(int pid) throws IOException {
        try { Os.kill(pid, 0); }
        catch (ErrnoException error) {
            if (error.errno == OsConstants.ESRCH) return new ProcessState(Kind.GONE, null, "");
            if (error.errno == OsConstants.EPERM || error.errno == OsConstants.EACCES) return new ProcessState(Kind.DENIED, null, "");
            throw new IOException(com.deepseekharness.app.util.UiText.text("检查 Web 进程失败（PID ") + pid + "，errno=" + error.errno + com.deepseekharness.app.util.UiText.text("）"), error);
        }
        try {
            WebPidIdentity identity = WebPidIdentity.parse(readProcessFile(pid, "stat"), pid);
            if (identity == null) throw new IOException(com.deepseekharness.app.util.UiText.text("内核进程身份无法解析"));
            if (identity.exited()) return new ProcessState(Kind.GONE, identity, "");
            String command = readProcessFile(pid, "cmdline");
            if (command.isEmpty()) throw new IOException(com.deepseekharness.app.util.UiText.text("进程命令行暂不可读"));
            return new ProcessState(WebProcSel.looksLikeWeb(command) ? Kind.WEB : Kind.OTHER, identity, command);
        } catch (IOException error) {
            try { Os.kill(pid, 0); }
            catch (ErrnoException gone) { if (gone.errno == OsConstants.ESRCH) return new ProcessState(Kind.GONE, null, ""); }
            // /proc/stat 为活态之后，退出可能先清空 cmdline，再进入僵尸态；kill(pid,0) 仍成功。
            // 再读内核状态确认退出，不把空命令行当成活进程，也不据此给未知进程发信号。
            try {
                WebPidIdentity after = WebPidIdentity.parse(readProcessFile(pid, "stat"), pid);
                if (after != null && after.exited()) return new ProcessState(Kind.GONE, after, "");
            } catch (IOException ignored) { }
            throw new IOException(com.deepseekharness.app.util.UiText.text("无法核验本应用进程（PID ") + pid + "）：" + error.getMessage(), error);
        }
    }
    private boolean changedIdentity(int pid, ProcessState state) throws IOException {
        File file = identityFile();
        if (Compat.isSymbolicLink(file)) throw new IOException(com.deepseekharness.app.util.UiText.text("Web 身份记录异常，原环境保留"));
        if (!file.exists()) return false;
        if (!file.isFile() || file.length() > 80) throw new IOException(com.deepseekharness.app.util.UiText.text("Web 身份记录无效"));
        String saved = Compat.readAll(file).trim();
        return saved.startsWith(pid + " ") && (state.identity == null || !state.identity.matches(saved));
    }
    /** 只读确认同 UID 可控进程；绝不按名称批量停止，也不依赖端口反查。 */
    boolean hasOwnedWeb() throws IOException {
        String[] entries = new File("/proc").list();
        if (entries == null) throw new IOException(com.deepseekharness.app.util.UiText.text("无法读取本应用进程清单，原环境保留"));
        for (String value : entries) {
            int pid = WebProcSel.parsePid(value);
            if (pid < 0 || pid == android.os.Process.myPid()) continue;
            if (inspect(pid).kind == Kind.WEB) return true;
        }
        return false;
    }
    /** 仅隔离仍与本次读取一致的旧记录；保留最后一份编号供诊断。 */
    private void retire(String record) throws IOException {
        if (record == null || !record.equals(pidRecord())) return;
        File stale = new File(root(), ".dsha-web.pid.stale");
        if (Compat.isSymbolicLink(stale)) throw new IOException(com.deepseekharness.app.util.UiText.text("旧 PID 保留位置异常"));
        try { Os.rename(pidFile().getAbsolutePath(), stale.getAbsolutePath()); }
        catch (ErrnoException error) { throw new IOException(com.deepseekharness.app.util.UiText.text("无法隔离旧 Web 进程记录"), error); }
        File identity = identityFile();
        if (Compat.isSymbolicLink(identity)) throw new IOException(com.deepseekharness.app.util.UiText.text("Web 身份记录异常"));
        if (identity.isFile() && !identity.delete()) throw new IOException(com.deepseekharness.app.util.UiText.text("无法清理旧 Web 身份记录"));
    }
    public boolean isRunning() {
        try { String record = pidRecord(); return record != null && inspect(WebProcSel.parsePid(record)).kind == Kind.WEB; }
        catch (IOException error) { return false; }
    }
    /** 权限错误本身不构成放行依据，还须确认启动器已退出且没有本应用 Web。 */
    public boolean confirmStopped(boolean trackedProcessAlive) throws IOException {
        if (trackedProcessAlive) return false;
        if (!root().isDirectory()) return true;
        sentinel();
        String record = pidRecord();
        try {
            if (record != null && inspect(WebProcSel.parsePid(record)).kind == Kind.WEB) return false;
            if (hasOwnedWeb()) return false;
        } catch (ProcessInspectionException transientState) { return false; }
        retire(record);
        return true;
    }
    /** 在鉴权就绪时记录 PID 的启动时刻，后续复用该编号的进程不继承停止权限。 */
    public void recordIdentity() {
        try {
            String record = pidRecord(); if (record == null) return;
            ProcessState state = inspect(WebProcSel.parsePid(record));
            if (state.kind != Kind.WEB || state.identity == null || !WebProcSel.maySignalWeb(state.command)) return;
            if (!record.equals(pidRecord())) return;
            File target = identityFile(), temp = new File(root(), ".dsha-web.identity.tmp");
            if (Compat.isSymbolicLink(target) || Compat.isSymbolicLink(temp)) return;
            Compat.write(temp, state.identity.record());
            Os.rename(temp.getAbsolutePath(), target.getAbsolutePath());
        } catch (Exception error) { android.util.Log.w("DSHA", com.deepseekharness.app.util.UiText.text("Web 身份记录未写入，将使用完整进程核验"), error); }
    }
    public String stop() {
        if (!root().isDirectory()) return "";
        long retryUntil = android.os.SystemClock.elapsedRealtime() + 3000;
        while (true) {
        try {
            sentinel();
            String record = pidRecord();
            if (record == null) return hasOwnedWeb() ? com.deepseekharness.app.util.UiText.text("发现仍在运行的 Web，但缺少对应 PID 记录；原环境保留") : "";
            int pid = WebProcSel.parsePid(record); ProcessState state = inspect(pid);
            if (state.kind != Kind.WEB || changedIdentity(pid, state)) {
                if (hasOwnedWeb()) return com.deepseekharness.app.util.UiText.text("旧 PID 已失效，但本应用仍有 Web 进程运行；原环境保留");
                retire(record); return "";
            }
            if (!WebProcSel.maySignalWeb(state.command)) return com.deepseekharness.app.util.UiText.text("Web 启动脚本仍在退出，已保留容器启动器");
            ProcessState again = inspect(pid);
            if (!state.identity.sameProcess(again.identity) || !WebProcSel.maySignalWeb(again.command) || !record.equals(pidRecord()))
                return com.deepseekharness.app.util.UiText.text("Web 进程身份已变化，未终止其他进程，请重试");
            Os.kill(pid, OsConstants.SIGTERM);
            long deadline = android.os.SystemClock.elapsedRealtime() + 3000;
            do {
                ProcessState current = inspect(pid);
                if (current.kind != Kind.WEB || !state.identity.sameProcess(current.identity)) {
                    if (hasOwnedWeb()) return com.deepseekharness.app.util.UiText.text("仍有 Web 进程未退出，原环境保留");
                    retire(record); return "";
                }
                Thread.sleep(50);
            } while (android.os.SystemClock.elapsedRealtime() < deadline);
            return com.deepseekharness.app.util.UiText.text("已请求停止，Web 尚未退出；稍后可重试，未强杀容器启动器");
        } catch (ProcessInspectionException error) {
            // exec/退出释放地址空间时，cmdline 可能较长时间为空。沿用停止等待窗口重新取证，
            // 不能把第一次瞬态读取失败作为最终结果，更不能直接给未核验进程补 SIGKILL。
            if (android.os.SystemClock.elapsedRealtime() >= retryUntil)
                return com.deepseekharness.app.util.UiText.text("停止 Web 未完成：") + SensitiveData.redact(String.valueOf(error.getMessage()));
            try { Thread.sleep(50); }
            catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); return com.deepseekharness.app.util.UiText.text("停止等待被中断，请检查 Web 状态"); }
        } catch (InterruptedException error) { Thread.currentThread().interrupt(); return com.deepseekharness.app.util.UiText.text("停止等待被中断，请检查 Web 状态"); }
        catch (Exception error) { return com.deepseekharness.app.util.UiText.text("停止 Web 未完成：") + SensitiveData.redact(String.valueOf(error.getMessage())); }
        }
    }
}
