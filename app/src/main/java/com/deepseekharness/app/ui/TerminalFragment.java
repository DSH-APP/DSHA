package com.deepseekharness.app.ui;

import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.KeyEvent;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.EditorInfo;
import android.widget.EditText;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import com.deepseekharness.app.R;
import com.deepseekharness.app.core.HarnessController;
import com.deepseekharness.app.runtime.ProotBootstrap;
import com.deepseekharness.app.util.Compat;
import com.deepseekharness.app.util.SensitiveData;
import com.deepseekharness.app.util.TerminalSession;
import com.deepseekharness.app.util.EnvironmentTaskGate;
import com.deepseekharness.app.util.ProcessTermination;
import com.deepseekharness.app.BackupManager;
import java.io.IOException;
import java.lang.ref.WeakReference;

/** 简易终端页面；持久 shell 的发送、退出和取消由无视图引用的 TerminalSession 负责。 */
public class TerminalFragment extends Fragment {
    private static final String HEADER = "Ubuntu 24.04 · 回车执行 · 中止会重启 shell · 交互输入请用 PTY\n";
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static final StringBuilder buffer = new StringBuilder();
    private static volatile TerminalSession session;
    private static WeakReference<TerminalFragment> bound = new WeakReference<>(null);
    private EditText inputEdit;
    private TextView outputText, cancelButton;
    private ScrollView scrollView;
    private boolean outputScheduled;
    private final Runnable renderOutput = this::renderOutput;

    @Nullable @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle state) {
        return inflater.inflate(R.layout.fragment_terminal, container, false);
    }

    @Override public void onViewCreated(@NonNull View view, @Nullable Bundle state) {
        inputEdit = view.findViewById(R.id.term_input);
        outputText = view.findViewById(R.id.term_output);
        scrollView = view.findViewById(R.id.term_scroll);
        cancelButton = view.findViewById(R.id.term_ctrlc);
        bound = new WeakReference<>(this);
        TerminalSession terminal = getSession(HarnessController.get(requireContext()).proot());
        cancelButton.setOnClickListener(v -> { if (!refuseDuringMaintenance()) terminal.cancelAndRestart(); });
        view.findViewById(R.id.term_clear).setOnClickListener(v -> {
            buffer.setLength(0);
            renderOutput();
        });
        View pty = view.findViewById(R.id.term_pty);
        if (pty != null) pty.setOnClickListener(v -> switchToPty());
        inputEdit.setOnEditorActionListener((v, action, event) -> {
            if (event != null) {
                if (event.getKeyCode() != KeyEvent.KEYCODE_ENTER) return false;
                if (event.getAction() == KeyEvent.ACTION_DOWN && event.getRepeatCount() == 0) sendCommand();
                return true; // 消费抬起/长按重复，不能把一次实体回车发送两遍。
            }
            if (action == EditorInfo.IME_ACTION_SEND || action == EditorInfo.IME_ACTION_GO
                    || action == EditorInfo.IME_ACTION_DONE) { sendCommand(); return true; }
            return false;
        });
        renderOutput();
        renderState(terminal.state());
        if (!refuseDuringMaintenance()) terminal.ensureStarted();
    }

    private static synchronized TerminalSession getSession(ProotBootstrap proot) {
        if (session == null) {
            session = new TerminalSession(terminalBackend(proot), text -> MAIN.post(() -> appendRaw(stripAnsi(text))), ignored -> MAIN.post(() -> {
                TerminalFragment fragment = bound.get();
                if (fragment != null && session != null) fragment.renderState(session.state());
            }));
        }
        return session;
    }

    /** 调试入口可创建自己的会话，复用实际开关进程逻辑，不接管用户的静态 shell/输出。 */
    static TerminalSession.Backend terminalBackend(ProotBootstrap proot) {
        return new TerminalSession.Backend() {
                @Override public AutoCloseable beginLifetime() {
                    return com.deepseekharness.app.core.RuntimeTasks.beginDetached();
                }
                @Override public Process open() throws Exception {
                    try (EnvironmentTaskGate.Lease lease = acquireTerminalLease(proot, "启动简易终端")) {
                        return lease.run(() -> {
                            checkPendingMaintenance(proot);
                            if (!proot.isEnvironmentReady()) throw new IOException("环境未就绪，请先到「安装」页完成安装");
                            try { return proot.execRootfsInteractive(); }
                            catch (RuntimeException | Error error) {
                                // 下层现已保留异步计数，但异常仍未向调用方返回 Process 句柄。
                                // 本层同样保守保留登记，不以“未返回”推断“从未创建”。
                                throw new TerminalSession.UncertainStart(error);
                            }
                        });
                    }
                }

                @Override public void write(Process process, String text) throws IOException {
                    try (EnvironmentTaskGate.Lease lease = acquireTerminalLease(proot, "终端命令发送")) {
                        lease.run(() -> {
                            checkPendingMaintenance(proot);
                            TerminalSession.Backend.super.write(process, text);
                            return null;
                        });
                    } catch (IOException error) { throw error; }
                    catch (Exception error) { throw new IOException("终端命令发送失败", error); }
                }

                @Override public void terminate(Process process, long group) throws Exception {
                    if (group > 1) {
                        // proot 不隔离 PID；只给本会话 setsid 组发信号。维护时也能停止，
                        // 不另起 proot / prepare 脚本触碰正在移动的 rootfs。
                        if (group > Integer.MAX_VALUE) throw new IOException("终端进程组无效");
                        try { android.system.Os.kill(-(int) group, android.system.OsConstants.SIGKILL); }
                        catch (android.system.ErrnoException error) {
                            if (error.errno != android.system.OsConstants.ESRCH)
                                throw new IOException("无法中止终端进程组", error);
                        }
                    }
                    // 未完成握手时尚无用户命令；握手完成后先杀组，再回收本会话启动器。
                    // Compat.destroy 由通用回收协作者核验进程身份并执行 proot 清理/强杀；
                    // 不能把信号已发送等同于退出，也不能因线程中断提前放行新会话。
                    if (!ProcessTermination.awaitExit(process, 1000)) Compat.destroy(process);
                    if (!ProcessTermination.awaitExit(process, 1000)) throw new IOException("旧终端启动器尚未退出");
                    try { process.getOutputStream().close(); } catch (IOException ignored) { }
                    try { process.getInputStream().close(); } catch (IOException ignored) { }
                    try { process.getErrorStream().close(); } catch (IOException ignored) { }
                }
        };
    }

    private static EnvironmentTaskGate.Lease acquireTerminalLease(ProotBootstrap proot, String kind) throws IOException {
        String blocked = terminalBlockMessage(proot);
        if (!blocked.isEmpty()) throw new TerminalSession.EnvironmentUnavailable(blocked);
        EnvironmentTaskGate.Lease lease = EnvironmentTaskGate.tryAcquire(kind);
        if (lease == null) throw new TerminalSession.EnvironmentUnavailable("已有环境任务启动，请稍后再打开终端");
        return lease;
    }

    private static void checkPendingMaintenance(ProotBootstrap proot) throws IOException {
        if (BackupManager.hasPendingMaintenance(proot.getRootfsDir().getParentFile().getParentFile()) || BackupManager.isRestoring())
            throw new TerminalSession.EnvironmentUnavailable("环境维护尚未完成，请先恢复维护，再打开终端");
    }

    static String terminalBlockMessage(ProotBootstrap proot) {
        if (BackupManager.hasPendingMaintenance(proot.getRootfsDir().getParentFile().getParentFile()))
            return "上次环境维护未完成，请到安装与修复页恢复维护，再打开终端";
        if (BackupManager.isEnvironmentTaskBusy()) {
            String kind = EnvironmentTaskGate.activeKind();
            return (kind.isEmpty() ? "正在执行环境任务" : "正在" + kind) + "，请稍后再打开终端或发送命令";
        }
        return "";
    }

    private boolean refuseDuringMaintenance() {
        String blocked = terminalBlockMessage(HarnessController.get(requireContext()).proot());
        if (blocked.isEmpty()) return false;
        Toast.makeText(requireContext(), blocked, Toast.LENGTH_LONG).show();
        if (inputEdit != null) inputEdit.setHint(blocked);
        return true;
    }

    private void sendCommand() {
        if (inputEdit == null) return;
        String command = inputEdit.getText().toString();
        if (command.trim().isEmpty()) return;
        if (refuseDuringMaintenance()) return;
        if (session != null && session.submit(command)) inputEdit.setText("");
        else Toast.makeText(requireContext(), "命令未发送：请检查长度（最多 16K 字符）及空字符，输入已保留", Toast.LENGTH_LONG).show();
    }

    private void renderState(TerminalSession.State state) {
        if (inputEdit == null || cancelButton == null) return;
        cancelButton.setText(state == TerminalSession.State.STOPPING ? "中止中…"
                : state == TerminalSession.State.FAILED ? "重试重启" : "中止并重启");
        cancelButton.setEnabled(state != TerminalSession.State.STOPPING);
        switch (state) {
            case STARTING: inputEdit.setHint("会话启动中，输入命令可排队"); break;
            case BUSY: inputEdit.setHint("命令执行中，新命令将排队"); break;
            case STOPPING: inputEdit.setHint("中止并重启中，新命令将排队"); break;
            case STOPPED: inputEdit.setHint("会话已退出，输入命令自动启动"); break;
            case FAILED: inputEdit.setHint("会话失败，待发命令保留；点重试重启"); break;
            default: inputEdit.setHint("输入命令，回车执行");
        }
    }

    private void switchToPty() {
        if (refuseDuringMaintenance()) return;
        requireContext().getSharedPreferences("deepseekharness", 0).edit().putBoolean("term_pty", true).apply();
        try {
            int containerId = ((ViewGroup) requireView().getParent()).getId();
            getParentFragmentManager().beginTransaction().replace(containerId, new PtyTerminalFragment()).commit();
        } catch (RuntimeException error) {
            Toast.makeText(requireContext(), "请退出终端页再进来", Toast.LENGTH_SHORT).show();
        }
    }

    /** 输出和绑定都只在主线程更新；旧页面不能清掉新页面的绑定。 */
    private static void appendRaw(String text) {
        if (text == null || text.isEmpty()) return;
        buffer.append(SensitiveData.redact(text));
        if (buffer.length() > 300000) buffer.delete(0, buffer.length() - 100000);
        TerminalFragment fragment = bound.get();
        if (fragment != null && !fragment.outputScheduled) {
            fragment.outputScheduled = true;
            MAIN.postDelayed(fragment.renderOutput, 32);
        }
    }

    private void renderOutput() {
        outputScheduled = false;
        if (outputText == null || scrollView == null) return;
        String show = buffer.length() > 100000 ? "…（输出过长已截断）\n" + buffer.substring(buffer.length() - 100000)
                : buffer.length() == 0 ? HEADER : buffer.toString();
        outputText.setText(SensitiveData.redact(show));
        ScrollView scroll = scrollView;
        scroll.post(() -> { if (scrollView == scroll) scroll.fullScroll(View.FOCUS_DOWN); });
    }

    private static String stripAnsi(String text) {
        return text.replaceAll("\\x1B\\[[0-9;?]*[a-zA-Z]", "").replaceAll("\\x1B\\][^\\x07]*\\x07", "")
                .replaceAll("\\x1B[()][0-9A-B]", "");
    }

    @Override public void onDestroyView() {
        if (bound.get() == this) bound.clear();
        MAIN.removeCallbacks(renderOutput);
        outputScheduled = false;
        inputEdit = null;
        outputText = null;
        cancelButton = null;
        scrollView = null;
        super.onDestroyView();
    }

    public static void shutdownShell() {
        TerminalSession terminal = session;
        if (terminal != null) terminal.shutdown();
    }

    /** 数据维护已取得全局 Lease 后，在替换目录前调用；不能从主线程等待。 */
    public static void shutdownShellAndWait(long timeoutMs) throws IOException, InterruptedException {
        if (Looper.myLooper() == Looper.getMainLooper()) throw new IOException("请在维护任务线程等待终端退出");
        TerminalSession terminal = session;
        if (terminal != null && !terminal.shutdownAndWait(timeoutMs))
            throw new IOException("未能确认简易终端已关闭；请勿移动或清除环境目录");
    }

    public static void inject(String text) {
        if (text != null && !text.isEmpty()) MAIN.post(() -> appendRaw(text + (text.endsWith("\n") ? "" : "\n")));
    }
}
