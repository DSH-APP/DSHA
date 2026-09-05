package com.deepseekharness.app;

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

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

/**
 * 内置终端：直接挂到 proot 的持久 bash 会话上。
 * 历史输出与 bash 会话跨页面保留（静态持有，切页/返回不清空），仅「清理」按钮手动清空。
 */
public class TerminalFragment extends Fragment {

    private HarnessController c;
    private EditText inputEdit;
    private TextView outputText;
    private ScrollView scrollView;

    // ===== 静态：跨 Fragment 重建保留 =====
    private static volatile Process shell;
    private static volatile boolean running = false;
    private static volatile Thread readerThread;
    /** 防止后台启动期间重复起 proot 交互进程（快速切页+输入触发并发 startShell） */
    private static volatile boolean shellStarting = false;
    private static final StringBuilder buffer = new StringBuilder();
    private static volatile TextView boundOutput; // 当前绑定的输出视图；null=无界面（会话继续后台跑）

    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        return inflater.inflate(R.layout.fragment_terminal, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        c = HarnessController.get(requireContext());
        inputEdit = view.findViewById(R.id.term_input);
        outputText = view.findViewById(R.id.term_output);
        scrollView = view.findViewById(R.id.term_scroll);
        // 绑定当前 UI（历史/后续输出写到这里）
        boundOutput = outputText;

        TextView ctrlcBtn = view.findViewById(R.id.term_ctrlc);
        ctrlcBtn.setOnClickListener(v -> {
            Process p = shell;
            if (p != null && p.isAlive()) {
                try {
                    p.getOutputStream().write(3); // Ctrl+C
                    p.getOutputStream().flush();
                } catch (IOException ignored) {
                }
            }
        });
        TextView clearBtn = view.findViewById(R.id.term_clear);
        clearBtn.setOnClickListener(v -> {
            buffer.setLength(0);
            outputText.setText("Ubuntu 24.04 · 回车执行 · 中止 · exit 退出\n");
        });
        View ptyBtn = view.findViewById(R.id.term_pty);
        if (ptyBtn != null) ptyBtn.setOnClickListener(v -> switchToPty());
        inputEdit.setOnEditorActionListener((v, actionId, event) -> {
            if (actionId == EditorInfo.IME_ACTION_SEND
                    || actionId == EditorInfo.IME_ACTION_GO
                    || actionId == EditorInfo.IME_ACTION_DONE
                    || (event != null && event.getKeyCode() == KeyEvent.KEYCODE_ENTER)) {
                sendCommand();
                return true;
            }
            return false;
        });

        // 重绘历史（静态 buffer 切页后仍在）
        String show = buffer.length() == 0 ? "" : buffer.toString();
        outputText.setText(show.isEmpty() ? "Ubuntu 24.04 · 回车执行 · 中止 · exit 退出" : show);
        scrollView.post(() -> scrollView.fullScroll(View.FOCUS_DOWN));

        // 环境检查 + 起 shell 全部丢后台线程：isHarnessInstalled() / ensureDangerGuard()
        // 都可能起 proot 子进程（1~3 秒），在 UI 线程同步跑会卡住页面打开
        startShell();
    }

    /** 切回真 PTY 终端 —— 这一页是兜底的简易版，vim / htop / tmux 得用那一套。 */
    private void switchToPty() {
        requireContext()
                .getSharedPreferences("deepseekharness", android.content.Context.MODE_PRIVATE)
                .edit().putBoolean(PtyTerminalFragment.KEY_PTY, true).apply();
        try {
            // 容器 id 动态取，不把 MainActivity 的布局细节写死在这里
            int containerId = ((ViewGroup) requireView().getParent()).getId();
            getParentFragmentManager().beginTransaction()
                    .replace(containerId, new PtyTerminalFragment())
                    .commit();
        } catch (Throwable e) {
            android.widget.Toast.makeText(requireContext(),
                    "请退出终端页再进来", android.widget.Toast.LENGTH_SHORT).show();
        }
    }

    private void startShell() {
        Process p = shell;
        if (p != null && p.isAlive() && readerThread != null && readerThread.isAlive()) {
            return; // 会话已在后台跑，本页只是重新绑定输出视图
        }
        // 防并发：已有启动线程则直接返回（否则快速操作会起多个 proot 进程）
        if (shellStarting) return;
        shellStarting = true;
        // 后台线程：环境检查 + 守卫 + 起 proot 交互进程（都较慢），不卡 UI 线程
        new Thread(() -> {
            try {
                if (!c.isHarnessInstalled()) {
                    mainHandler.post(() -> appendLine("环境未安装，请先到「安装」页完成安装"));
                    return;
                }
            } catch (Throwable ignored) {
            }
            // 危险确认包装器缺失则自动补装（后台静默，不阻塞终端打开）
            try {
                c.ensureDangerGuard();
            } catch (Throwable ignored) {
            }
            // 起进程与读输出**必须分成两段 catch**。以前它们共用一个，于是每次
            // 正常关闭终端（切页、退出、重启会话）都会看到
            //   终端启动失败：read interrupted by close() on another thread
            // —— 那是另一个线程 close 了流之后 read 抛出来的，属于正常关闭路径，
            // 却被报成启动失败，用户以为终端坏了。真正的启动失败只有第一段会抛。
            try {
                shell = c.getProot().execRootfsInteractive();
            } catch (Exception e) {
                final String why = e.getMessage() == null ? e.toString() : e.getMessage();
                mainHandler.post(() -> appendLine("终端启动失败：" + why));
                shellStarting = false;
                return;
            }
            running = true;
            try {
                // 用 InputStreamReader 流式解码：固定 8192 字节块按 UTF-8 硬解会切断
                // 多字节字符（中文 3 字节）产生乱码 �；Reader 内部缓冲正确处理跨边界
                java.io.Reader reader = new java.io.InputStreamReader(
                        shell.getInputStream(), StandardCharsets.UTF_8);
                char[] cbuf = new char[4096];
                int n;
                while (running && (n = reader.read(cbuf)) != -1) {
                    final String chunk = stripAnsi(new String(cbuf, 0, n));
                    mainHandler.post(() -> appendRaw(chunk));
                }
                mainHandler.post(() -> appendLine("\n[会话已退出]"));
            } catch (Exception e) {
                // running 已经是 false = 我们自己停的；带 "interrupted by close" 的
                // 同样是自己关流导致的。两种都不是故障。
                final boolean selfStopped = !running;
                final String msg = e.getMessage() == null ? e.toString() : e.getMessage();
                mainHandler.post(() -> {
                    if (selfStopped || msg.contains("interrupted by close")) {
                        appendLine("\n[会话已关闭]");
                    } else {
                        appendLine("\n[会话中断：" + msg + "]");
                    }
                });
            } finally {
                running = false;
                shellStarting = false;
            }
        }, "term-read").start();
    }

    private void sendCommand() {
        String cmd = inputEdit.getText().toString().trim();
        if (cmd.isEmpty()) return;
        inputEdit.setText("");
        appendLine("$ " + cmd);
        Process p = shell;
        if (p == null || !p.isAlive()) {
            appendLine("会话未运行，正在重启…");
            startShell();
            return;
        }
        try {
            p.getOutputStream().write((cmd + "\n").getBytes(StandardCharsets.UTF_8));
            p.getOutputStream().flush();
        } catch (IOException e) {
            appendLine("发送失败：" + e.getMessage());
        }
    }

    private void appendLine(String s) {
        appendRaw(s + "\n");
    }

    /** 始终写静态 buffer（切页后继续累积，否则历史丢）；有绑定视图才刷 UI */
    private void appendRaw(String s) {
        if (s == null || s.isEmpty()) return;
        buffer.append(s);
        if (buffer.length() > 300000) {
            // 保留最近 10 万字符（不能整体清空，否则历史全丢）
            buffer.delete(0, buffer.length() - 100000);
        }
        TextView out = boundOutput;
        if (out == null) return;
        String show = buffer.length() > 100000
                ? "…（输出过长已截断）\n" + buffer.substring(buffer.length() - 100000)
                : buffer.toString();
        out.setText(show);
        ScrollView sv = scrollView;
        if (sv != null) {
            sv.post(() -> sv.fullScroll(View.FOCUS_DOWN));
        }
    }

    /** 去掉 ANSI 转义序列（保留可读文本） */
    private static String stripAnsi(String s) {
        return s.replaceAll("\\x1B\\[[0-9;?]*[a-zA-Z]", "")
                .replaceAll("\\x1B\\][^\\x07]*\\x07", "")
                .replaceAll("\\x1B[()][0-9A-B]", "");
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        // 只解绑视图，不杀会话、不清 buffer —— 换页/返回历史保留
        boundOutput = null;
    }

    /** 关闭持久 shell 会话（App 退出时调用，防进程泄漏）。会话跨页保留期间不调用。 */
    public static void shutdownShell() {
        Process p = shell;
        if (p != null) {
            try {
                p.getOutputStream().write(("exit\n").getBytes(StandardCharsets.UTF_8));
                p.getOutputStream().flush();
            } catch (IOException ignored) {
            }
            try {
                p.destroyForcibly();
            } catch (Throwable ignored) {
            }
        }
        running = false;
        shell = null;
        shellStarting = false; // 重置启动锁（防止启动中被销毁导致下次永远无法启动）
    }

    /** 外部注入文本到终端 buffer（ADB 配对失败日志等）。跨线程安全，终端页可见可复制。 */
    public static void inject(String text) {
        if (text == null || text.isEmpty()) return;
        android.os.Handler h = new android.os.Handler(android.os.Looper.getMainLooper());
        h.post(() -> {
            synchronized (TerminalFragment.class) {
                buffer.append(text);
                if (!text.endsWith("\n")) buffer.append('\n');
                // 与 appendRaw 保持一致：超限保留尾部 10 万字符（不能整体清空，否则历史全丢）
                if (buffer.length() > 300000) {
                    buffer.delete(0, buffer.length() - 100000);
                }
                TextView out = boundOutput;
                if (out == null) return;
                String show = buffer.length() > 100000
                        ? "…（输出过长已截断）\n" + buffer.substring(buffer.length() - 100000)
                        : buffer.toString();
                out.setText(show);
                // 滚动到底（从视图层级找 ScrollView 父级）
                android.view.ViewParent p = out.getParent();
                while (p != null && !(p instanceof ScrollView)) p = p.getParent();
                if (p instanceof ScrollView) {
                    final ScrollView sv = (ScrollView) p;
                    sv.post(() -> sv.fullScroll(View.FOCUS_DOWN));
                }
            }
        });
    }
}
