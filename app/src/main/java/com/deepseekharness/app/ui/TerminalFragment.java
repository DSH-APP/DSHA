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
import com.deepseekharness.app.util.TerminalTabs;
import com.deepseekharness.app.util.EnvironmentTaskGate;
import com.deepseekharness.app.util.ProcessTermination;
import com.deepseekharness.app.BackupManager;
import java.io.IOException;
import java.lang.ref.WeakReference;

/** 简易终端页面；持久 shell 的发送、退出和取消由无视图引用的 TerminalSession 负责。 */
public class TerminalFragment extends Fragment {
    private static final String HEADER = "Ubuntu 24.04 · 回车执行 · 中止会重启 shell · 交互输入请用 PTY\n";
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static final TerminalTabs<ShellTab> sessions = new TerminalTabs<>();
    private static final class ShellTab {
        final StringBuilder buffer = new StringBuilder();
        final TerminalSession session;
        String draft = "";
        int cursorStart, cursorEnd;
        ShellTab(ProotBootstrap proot) {
            session = new TerminalSession(terminalBackend(proot), text -> MAIN.post(() -> appendRaw(this,stripAnsi(text))), ignored -> MAIN.post(this::refreshState));
        }
        private void refreshState(){TerminalFragment fragment=bound.get();if(fragment!=null&&fragment.active==this)fragment.renderState(session.state());}
    }
    private static WeakReference<TerminalFragment> bound = new WeakReference<>(null);
    private EditText inputEdit;
    private TextView outputText, cancelButton;
    private ScrollView scrollView;
    private ShellTab active;
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
        if(isResumed())bound = new WeakReference<>(this);
        cancelButton.setOnClickListener(v -> { if (active!=null&&!refuseDuringMaintenance()) active.session.cancelAndRestart(); });
        view.findViewById(R.id.term_clear).setOnClickListener(v -> {
            if(active!=null)active.buffer.setLength(0);
            renderOutput();
        });
        View pty = view.findViewById(R.id.term_pty);
        if (pty != null) pty.setOnClickListener(v -> switchToPty());
        view.findViewById(R.id.terminal_new).setOnClickListener(v->newTerminal());
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
        if(!sessions.wasInitialized())newTerminal();else attachSelected();
    }

    private void newTerminal() {
        if(refuseDuringMaintenance())return;
        saveDraft();ShellTab tab=new ShellTab(HarnessController.get(requireContext()).proot());sessions.add(tab);attachSelected();
        requireView().findViewById(R.id.terminal_new).setEnabled(false);tab.session.ensureStarted();
    }
    private void saveDraft(){if(active!=null&&inputEdit!=null){active.draft=inputEdit.getText().toString();active.cursorStart=inputEdit.getSelectionStart();active.cursorEnd=inputEdit.getSelectionEnd();}}
    private void attachSelected() {
        if(getView()==null)return;saveDraft();var tab=sessions.current();ShellTab selected=tab==null?null:tab.value;
        boolean changed=active!=selected;active=selected;
        if(changed){inputEdit.setText(active==null?"":active.draft);
            if(active!=null)inputEdit.setSelection(Math.max(0,Math.min(active.cursorStart,active.draft.length())),Math.max(0,Math.min(active.cursorEnd,active.draft.length())));}
        inputEdit.setEnabled(tab!=null&&!tab.isClosing());
        cancelButton.setEnabled(tab!=null&&!tab.isClosing());
        if(active!=null)renderState(active.session.state());else inputEdit.setHint(com.deepseekharness.app.util.UiText.text("点击「新建」打开终端"));
        renderOutput();renderTabs();
    }
    private void renderTabs() {
        if(getView()==null)return;
        TerminalTabBar.render(getView(),sessions,new TerminalTabBar.Actions(){
            public void select(long id){saveDraft();if(sessions.select(id))attachSelected();}
            public void close(long id){closeTerminal(id);}
        });
    }
    private void closeTerminal(long id) {
        var tab=sessions.find(id);if(tab==null||!sessions.beginClose(id))return;attachSelected();
        android.content.Context app=requireContext().getApplicationContext();
        new Thread(()->{
            String failure=null;
            try{if(!tab.value.session.disposeAndWait(5000))throw new IOException(com.deepseekharness.app.util.UiText.text("尚未确认终端退出"));sessions.remove(id);}
            catch(Exception error){sessions.closeFailed(id);failure=SensitiveData.redact(String.valueOf(error.getMessage()));}
            final String problem=failure;MAIN.post(()->{
                if(problem!=null)Toast.makeText(app,com.deepseekharness.app.util.UiText.text("关闭失败，会话仍保留：")+problem,Toast.LENGTH_LONG).show();
                TerminalFragment fragment=bound.get();if(fragment!=null&&fragment.getView()!=null)fragment.attachSelected();
            });
        },"dsha-close-simple-terminal").start();
    }

    /** 调试入口可创建自己的会话，复用实际开关进程逻辑，不接管用户的静态 shell/输出。 */
    static TerminalSession.Backend terminalBackend(ProotBootstrap proot) {
        return new TerminalSession.Backend() {
                @Override public AutoCloseable beginLifetime() {
                    return com.deepseekharness.app.core.RuntimeTasks.beginDetached();
                }
                @Override public Process open() throws Exception {
                    try (EnvironmentTaskGate.Lease lease = acquireTerminalLease(proot, com.deepseekharness.app.util.UiText.text("启动简易终端"))) {
                        return lease.run(() -> {
                            checkPendingMaintenance(proot);
                            if (!proot.isEnvironmentReady()) throw new IOException(com.deepseekharness.app.util.UiText.text("环境未就绪，请先到「安装」页完成安装"));
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
                    try (EnvironmentTaskGate.Lease lease = acquireTerminalLease(proot, com.deepseekharness.app.util.UiText.text("终端命令发送"))) {
                        lease.run(() -> {
                            checkPendingMaintenance(proot);
                            TerminalSession.Backend.super.write(process, text);
                            return null;
                        });
                    } catch (IOException error) { throw error; }
                    catch (Exception error) { throw new IOException(com.deepseekharness.app.util.UiText.text("终端命令发送失败"), error); }
                }

                @Override public void terminate(Process process, long group) throws Exception {
                    if (group > 1) {
                        // proot 不隔离 PID；只给本会话 setsid 组发信号。维护时也能停止，
                        // 不另起 proot / prepare 脚本触碰正在移动的 rootfs。
                        if (group > Integer.MAX_VALUE) throw new IOException(com.deepseekharness.app.util.UiText.text("终端进程组无效"));
                        try { android.system.Os.kill(-(int) group, android.system.OsConstants.SIGKILL); }
                        catch (android.system.ErrnoException error) {
                            if (error.errno != android.system.OsConstants.ESRCH)
                                throw new IOException(com.deepseekharness.app.util.UiText.text("无法中止终端进程组"), error);
                        }
                    }
                    // 未完成握手时尚无用户命令；握手完成后先杀组，再回收本会话启动器。
                    // Compat.destroy 由通用回收协作者核验进程身份并执行 proot 清理/强杀；
                    // 不能把信号已发送等同于退出，也不能因线程中断提前放行新会话。
                    if (!ProcessTermination.awaitExit(process, 1000)) Compat.destroy(process);
                    if (!ProcessTermination.awaitExit(process, 1000)) throw new IOException(com.deepseekharness.app.util.UiText.text("旧终端启动器尚未退出"));
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
        if (lease == null) throw new TerminalSession.EnvironmentUnavailable(com.deepseekharness.app.util.UiText.text("已有环境任务启动，请稍后再打开终端"));
        return lease;
    }

    private static void checkPendingMaintenance(ProotBootstrap proot) throws IOException {
        if (BackupManager.hasPendingMaintenance(proot.getRootfsDir().getParentFile().getParentFile()) || BackupManager.isRestoring())
            throw new TerminalSession.EnvironmentUnavailable(com.deepseekharness.app.util.UiText.text("环境维护尚未完成，请先恢复维护，再打开终端"));
    }

    static String terminalBlockMessage(ProotBootstrap proot) {
        if (BackupManager.hasPendingMaintenance(proot.getRootfsDir().getParentFile().getParentFile()))
            return com.deepseekharness.app.util.UiText.text("上次环境维护未完成，请到安装与修复页恢复维护，再打开终端");
        if (BackupManager.isEnvironmentTaskBusy()) {
            String kind = EnvironmentTaskGate.activeKind();
            return (kind.isEmpty() ? com.deepseekharness.app.util.UiText.text("正在执行环境任务") : com.deepseekharness.app.util.UiText.text("正在") + kind) + com.deepseekharness.app.util.UiText.text("，请稍后再打开终端或发送命令");
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
        var selected=sessions.current();
        if (active != null && selected!=null && !selected.isClosing() && active.session.submit(command)) inputEdit.setText("");
        else Toast.makeText(requireContext(), com.deepseekharness.app.util.UiText.text("命令未发送：请检查长度（最多 16K 字符）及空字符，输入已保留"), Toast.LENGTH_LONG).show();
    }

    private void renderState(TerminalSession.State state) {
        if (inputEdit == null || cancelButton == null) return;
        if(getView()!=null)getView().findViewById(R.id.terminal_new).setEnabled(state!=TerminalSession.State.STARTING);
        cancelButton.setText(state == TerminalSession.State.STOPPING ? com.deepseekharness.app.util.UiText.text("中止中…")
                : state == TerminalSession.State.FAILED ? com.deepseekharness.app.util.UiText.text("重试重启") : com.deepseekharness.app.util.UiText.text("中止并重启"));
        cancelButton.setEnabled(state != TerminalSession.State.STOPPING);
        var selected=sessions.current();
        if(selected!=null&&selected.isClosing()){inputEdit.setEnabled(false);cancelButton.setEnabled(false);inputEdit.setHint(com.deepseekharness.app.util.UiText.text("正在关闭此终端…"));return;}
        switch (state) {
            case STARTING: inputEdit.setHint(com.deepseekharness.app.util.UiText.text("会话启动中，输入命令可排队")); break;
            case BUSY: inputEdit.setHint(com.deepseekharness.app.util.UiText.text("命令执行中，新命令将排队")); break;
            case STOPPING: inputEdit.setHint(com.deepseekharness.app.util.UiText.text("中止并重启中，新命令将排队")); break;
            case STOPPED: inputEdit.setHint(com.deepseekharness.app.util.UiText.text("会话已退出，输入命令自动启动")); break;
            case FAILED: inputEdit.setHint(com.deepseekharness.app.util.UiText.text("会话失败，待发命令保留；点重试重启")); break;
            default: inputEdit.setHint(com.deepseekharness.app.util.UiText.text("输入命令，回车执行"));
        }
    }

    private void switchToPty() {
        if (refuseDuringMaintenance()) return;
        requireContext().getSharedPreferences("deepseekharness", 0).edit().putBoolean("term_pty", true).apply();
        try {
            int containerId = ((ViewGroup) requireView().getParent()).getId();
            UiMotion.page(requireContext(),getParentFragmentManager().beginTransaction()).replace(containerId, new PtyTerminalFragment()).commit();
        } catch (RuntimeException error) {
            Toast.makeText(requireContext(), com.deepseekharness.app.util.UiText.text("请退出终端页再进来"), Toast.LENGTH_SHORT).show();
        }
    }

    /** 输出和绑定都只在主线程更新；旧页面不能清掉新页面的绑定。 */
    private static void appendRaw(ShellTab tab,String text) {
        if (text == null || text.isEmpty()) return;
        StringBuilder buffer=tab.buffer;
        buffer.append(SensitiveData.redact(text));
        if (buffer.length() > 300000) buffer.delete(0, buffer.length() - 100000);
        TerminalFragment fragment = bound.get();
        if (fragment != null && fragment.active==tab && !fragment.outputScheduled) {
            fragment.outputScheduled = true;
            MAIN.postDelayed(fragment.renderOutput, 32);
        }
    }

    private void renderOutput() {
        outputScheduled = false;
        if (outputText == null || scrollView == null) return;
        if(active==null){outputText.setText(com.deepseekharness.app.util.UiText.text("暂无终端\n点击「新建」打开一个终端"));return;}
        StringBuilder buffer=active.buffer;
        String show = buffer.length() > 100000 ? com.deepseekharness.app.util.UiText.text("…（输出过长已截断）\n") + buffer.substring(buffer.length() - 100000)
                : buffer.length() == 0 ? HEADER : buffer.toString();
        outputText.setText(SensitiveData.redact(show));
        ScrollView scroll = scrollView;
        scroll.post(() -> { if (scrollView == scroll) scroll.fullScroll(View.FOCUS_DOWN); });
    }

    private static String stripAnsi(String text) {
        return text.replaceAll("\\x1B\\[[0-9;?]*[a-zA-Z]", "").replaceAll("\\x1B\\][^\\x07]*\\x07", "")
                .replaceAll("\\x1B[()][0-9A-B]", "");
    }

    @Override public void onResume(){super.onResume();bound=new WeakReference<>(this);if(inputEdit!=null)attachSelected();}
    @Override public void onPause(){saveDraft();if(bound.get()==this)bound.clear();super.onPause();}
    @Override public void onDestroyView() {
        saveDraft();active=null;
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
        for(var tab:sessions.snapshot())tab.value.session.shutdown();
    }

    /** 数据维护已取得全局 Lease 后，在替换目录前调用；不能从主线程等待。 */
    public static void shutdownShellAndWait(long timeoutMs) throws IOException, InterruptedException {
        if (Looper.myLooper() == Looper.getMainLooper()) throw new IOException(com.deepseekharness.app.util.UiText.text("请在维护任务线程等待终端退出"));
        for(var tab:sessions.snapshot()){
            if(!tab.value.session.disposeAndWait(timeoutMs))throw new IOException(com.deepseekharness.app.util.UiText.text("未能确认简易终端已关闭；请勿移动或清除环境目录"));
            sessions.remove(tab.id);
        }
    }

    public static void inject(String text) {
        if (text != null && !text.isEmpty()) MAIN.post(() -> {
            var tab=sessions.current();if(tab!=null)appendRaw(tab.value,text + (text.endsWith("\n") ? "" : "\n"));
        });
    }
}
