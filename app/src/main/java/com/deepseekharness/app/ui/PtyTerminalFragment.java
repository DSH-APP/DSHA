package com.deepseekharness.app.ui;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.InputMethodManager;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;

import com.deepseekharness.app.PtySession;
import com.deepseekharness.app.R;
import com.deepseekharness.app.core.HarnessController;
import com.deepseekharness.app.util.SensitiveData;
import com.termux.terminal.TerminalSession;
import com.termux.view.TerminalView;
import com.termux.view.TerminalViewClient;

/**
 * 真 PTY 终端页：Termux 拆出的 {@code TerminalView}（Apache 2.0）接在 {@link PtySession} 上，
 * vim / htop / tmux / top 都能跑（旧简易终端没有 TERM / 光标定位，TUI 全是乱码）。
 *
 * <p>两页并存、可随时互切：右上角「简易」退回旧终端页（万一 PTY 在某些机型出问题，
 * 不至于连命令行都没了）。选择记在 {@link #KEY_PTY}，MainActivity 按它决定挂哪一页。
 *
 * <p>会话是 <b>static</b> 的：切到别的页面再回来，历史与正在跑的程序都还在。
 * 真正结束只发生在 {@link #shutdown()}（App 退出时 MainActivity 调）。
 */
public final class PtyTerminalFragment extends Fragment
        implements TerminalViewClient, PtySession.Listener {

    /** true = 用这一页，false = 用旧的简易终端。默认 true。 */
    public static final String KEY_PTY = "term_pty";
    private static final String KEY_FONT_PX = "term_font_px";
    private static final int FONT_MIN_SP = 8;
    private static final int FONT_MAX_SP = 24;
    private static final int FONT_DEF_SP = 13;

    /**
     * 扩展键：{@code {显示, 要发的序列}}，序列为 null 表示这是个状态键（Ctrl / Alt）。
     * 手机软键盘没有 Esc、Ctrl 和方向键，缺了这排 TUI 基本没法用。
     */
    private static final String[][] KEYS = {
            {"ESC", "\033"},
            {"TAB", "\t"},
            {"CTRL", null},
            {"ALT", null},
            {"↑", "\033[A"},
            {"↓", "\033[B"},
            {"←", "\033[D"},
            {"→", "\033[C"},
            {"^C", "\003"},
            {"^D", "\004"},
            {"^Z", "\032"},
            {"|", "|"},
            {"~", "~"},
            {"/", "/"},
            {"-", "-"},
    };

    /** 会话跨页面存活，所以是静态的（与旧终端的 static shell 同思路）。 */
    private static volatile PtySession session;

    private final Handler main = new Handler(Looper.getMainLooper());

    private HarnessController c;
    private TerminalView view;
    private TextView title;
    private PtySession attachedSession;
    private boolean ctrlDown;
    private boolean altDown;
    private TextView ctrlBtn;
    private TextView altBtn;

    /** 供 MainActivity 决定挂哪一页。 */
    public static boolean preferred(Context ctx) {
        return prefs(ctx).getBoolean(KEY_PTY, true);
    }

    private static SharedPreferences prefs(Context ctx) {
        return ctx.getSharedPreferences("deepseekharness", Context.MODE_PRIVATE);
    }

    /** App 退出时收掉会话，别在容器里留一个孤儿 bash。 */
    public static void shutdown() {
        PtySession s = session;
        if (s != null) {
            try {
                s.finish();
                if (!s.isRunning() && session == s) session = null;
            } catch (Throwable error) {
                android.util.Log.w("DSHA", "终端尚未停止，保留会话与环境保护：" + SensitiveData.redact(String.valueOf(error)));
            }
        }
    }

    /** 维护时先结束持久终端，等待真实退出与工作锁释放；不能阻塞主线程回调。 */
    public static void shutdownAndWait(long timeoutMs) throws java.io.IOException, InterruptedException {
        if (Looper.myLooper() == Looper.getMainLooper()) throw new java.io.IOException("请在维护线程等待终端退出");
        PtySession terminal = session;
        if (terminal != null) {
            terminal.finishAndWait(timeoutMs);
            if (session == terminal) session = null;
        }
    }

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        return inflater.inflate(R.layout.fragment_pty_terminal, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View root, @Nullable Bundle savedInstanceState) {
        c = new HarnessController(requireContext());
        view = root.findViewById(R.id.pty_view);
        title = root.findViewById(R.id.pty_title);

        view.setTerminalViewClient(this);
        applyFontSize(fontSp());
        buildExtraKeys(root.findViewById(R.id.pty_keys));

        root.findViewById(R.id.pty_font_dec).setOnClickListener(v -> bumpFont(-1));
        root.findViewById(R.id.pty_font_inc).setOnClickListener(v -> bumpFont(+1));
        root.findViewById(R.id.pty_simple).setOnClickListener(v -> switchToSimple());

        if (!c.proot().isEnvironmentReady()) {
            title.setText("环境未就绪 —— 先到「安装」页装完再回来");
            return;
        }
        attachOrStart();
    }

    /** 已有会话就接回去（切页面回来不丢历史），没有就起一个。 */
    private void attachOrStart() {
        PtySession s = session;
        if (s != null && s.isRunning()) {
            attachedSession = s;
            s.attachListener(this);
            view.attachSession(s.session());
            title.setText(displayTitle(s.session()));
            return;
        }
        try {
            if (com.deepseekharness.app.BackupManager.isEnvironmentTaskBusy())
                throw new IllegalStateException("正在维护环境，请完成后再启动终端");
            // 初始 80x24 只是占位：attachSession 之后 TerminalView 会按控件实测的字宽
            // 重新算行列并通知 PTY（否则 TUI 的边框会错位）。
            PtySession ns = PtySession.start(c.proot(), 80, 24, this);
            session = ns;
            attachedSession = ns;
            ns.attachListener(this);
            view.attachSession(ns.session());
            title.setText("Ubuntu · PTY");
        } catch (Throwable e) {
            String safe = SensitiveData.redact(String.valueOf(e));
            title.setText("终端启动失败：" + safe);
            com.deepseekharness.app.core.DiagnosticLog.record(requireContext(), "PTY_START", safe);
            android.util.Log.w("DSHA", "PTY 启动失败：" + safe);
        }
    }

    private String displayTitle(TerminalSession s) {
        String t = s == null ? null : s.getTitle();
        return t == null || t.trim().isEmpty()
                ? "Ubuntu · PTY" : SensitiveData.redact(t.trim());
    }

    // ==================== 扩展键 ====================

    private void buildExtraKeys(LinearLayout box) {
        if (box == null) return;
        box.removeAllViews();
        for (String[] k : KEYS) {
            TextView b = new TextView(requireContext());
            b.setText(k[0]);
            b.setMinHeight(dp(44)); b.setMinWidth(dp(44));
            b.setGravity(Gravity.CENTER);
            b.setBackgroundResource(R.drawable.bg_chip);
            b.setTextColor(getResources().getColor(R.color.text_secondary));
            b.setTypeface(android.graphics.Typeface.MONOSPACE);
            b.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
            int padH = dp(11), padV = dp(7);
            b.setPadding(padH, padV, padH, padV);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
            lp.setMarginEnd(dp(6));
            b.setLayoutParams(lp);
            final String seq = k[1];
            final String label = k[0];
            if (seq == null) {
                if ("CTRL".equals(label)) ctrlBtn = b;
                else altBtn = b;
                b.setOnClickListener(v -> toggleModifier(label));
            } else {
                b.setOnClickListener(v -> send(seq));
            }
            box.addView(b);
        }
        paintModifiers();
    }

    /** Ctrl / Alt 是状态键：按一下亮起来，下一个字符带上这个修饰键（TerminalView 会来问）。 */
    private void toggleModifier(String which) {
        if ("CTRL".equals(which)) ctrlDown = !ctrlDown;
        else altDown = !altDown;
        paintModifiers();
        showKeyboard();
    }

    private void paintModifiers() {
        if (ctrlBtn != null) {
            ctrlBtn.setTextColor(getResources().getColor(
                    ctrlDown ? R.color.ok : R.color.text_secondary));
        }
        if (altBtn != null) {
            altBtn.setTextColor(getResources().getColor(
                    altDown ? R.color.ok : R.color.text_secondary));
        }
    }

    private void send(String seq) {
        PtySession s = session;
        if (s == null || !s.isRunning()) {
            Toast.makeText(requireContext(), "会话已结束，切走再回来可重开", Toast.LENGTH_SHORT).show();
            return;
        }
        s.write(seq);
        // 修饰键是一次性的：发完就灭，跟物理键盘的手感一致
        if (ctrlDown || altDown) {
            ctrlDown = false;
            altDown = false;
            paintModifiers();
        }
    }

    // ==================== 字号与切换 ====================

    private int fontSp() {
        int v = prefs(requireContext()).getInt(KEY_FONT_PX, FONT_DEF_SP);
        return Math.max(FONT_MIN_SP, Math.min(FONT_MAX_SP, v));
    }

    private void bumpFont(int delta) {
        int next = Math.max(FONT_MIN_SP, Math.min(FONT_MAX_SP, fontSp() + delta));
        prefs(requireContext()).edit().putInt(KEY_FONT_PX, next).apply();
        applyFontSize(next);
    }

    private void applyFontSize(int sp) {
        if (view == null) return;
        // TerminalView.setTextSize 收的是 px；行列数由它自己按字宽重算并同步给 PTY
        view.setTextSize((int) TypedValue.applyDimension(
                TypedValue.COMPLEX_UNIT_SP, sp, getResources().getDisplayMetrics()));
    }

    private void switchToSimple() {
        prefs(requireContext()).edit().putBoolean(KEY_PTY, false).apply();
        Toast.makeText(requireContext(), "已切到简易终端（PTY 会话仍在后台）", Toast.LENGTH_SHORT).show();
        try {
            // 容器 id 动态取，不硬编码 MainActivity 的布局细节
            int containerId = ((ViewGroup) requireView().getParent()).getId();
            getParentFragmentManager().beginTransaction()
                    .replace(containerId, new TerminalFragment())
                    .commit();
        } catch (Throwable e) {
            Toast.makeText(requireContext(), "请退出终端页再进来", Toast.LENGTH_SHORT).show();
        }
    }

    private void showKeyboard() {
        if (view == null) return;
        view.requestFocus();
        InputMethodManager im = (InputMethodManager)
                requireContext().getSystemService(Context.INPUT_METHOD_SERVICE);
        if (im != null) im.showSoftInput(view, 0);
    }

    private int dp(int v) {
        return (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v,
                getResources().getDisplayMetrics());
    }

    // ==================== PtySession.Listener ====================

    @Override
    public void onDestroyView() {
        PtySession s = attachedSession;
        if (s != null) s.detachListener(this);
        attachedSession = null;
        main.removeCallbacksAndMessages(null);
        redrawPending.set(false);
        view = null;
        title = null;
        c = null;
        super.onDestroyView();
    }

    /** PTY 每来一小段输出就回调一次（几十字节一次很常见），无节流地 post 会把主线程
     *  队列灌满 —— 「新终端越用越卡」就是这么来的。用一个 pending 标志把连续多次输出
     *  合并成一次重绘：反正屏幕只需要显示最后的状态。 */
    private final java.util.concurrent.atomic.AtomicBoolean redrawPending =
            new java.util.concurrent.atomic.AtomicBoolean(false);

    @Override
    public void onOutput() {
        if (!redrawPending.compareAndSet(false, true)) return;
        final TerminalView target = view;
        if (target == null) {
            redrawPending.set(false);
            return;
        }
        target.postOnAnimation(() -> {
            redrawPending.set(false);
            if (!isAdded() || view != target || target.getWindowToken() == null) return;
            target.onScreenUpdated();
        });
    }

    @Override
    public void onTitle(String t) {
        final TextView target = title;
        main.post(() -> {
            if (!isAdded() || target == null || title != target) return;
            if (t != null && !t.trim().isEmpty()) {
                target.setText(SensitiveData.redact(t.trim()));
            }
        });
    }

    @Override
    public void onExit(int status) {
        final TextView target = title;
        final PtySession exited = attachedSession;
        main.post(() -> {
            if (isAdded() && target != null && title == target) {
                target.setText("会话已结束（退出码 " + status + "）");
            }
        });
        if (session == exited) session = null;
    }

    @Override
    public void onCopy(String text) {
        final TerminalView target = view;
        main.post(() -> {
            try {
                if (!isAdded() || target == null || view != target) return;
                android.content.ClipboardManager cm = (android.content.ClipboardManager)
                        requireContext().getSystemService(Context.CLIPBOARD_SERVICE);
                if (cm != null && text != null) {
                    cm.setPrimaryClip(android.content.ClipData.newPlainText("term",
                            SensitiveData.redact(text)));
                    Toast.makeText(requireContext(), "已复制", Toast.LENGTH_SHORT).show();
                }
            } catch (Throwable ignored) {
            }
        });
    }

    @Override
    public void onPasteRequest() {
        final TerminalView target = view;
        main.post(() -> {
            try {
                if (!isAdded() || target == null || view != target) return;
                android.content.ClipboardManager cm = (android.content.ClipboardManager)
                        requireContext().getSystemService(Context.CLIPBOARD_SERVICE);
                if (cm == null || cm.getPrimaryClip() == null
                        || cm.getPrimaryClip().getItemCount() == 0) return;
                CharSequence cs = cm.getPrimaryClip().getItemAt(0).coerceToText(requireContext());
                if (cs != null && cs.length() > 0) send(cs.toString());
            } catch (Throwable ignored) {
            }
        });
    }

    @Override
    public void onBell() {
        final TerminalView target = view;
        main.post(() -> {
            try {
                if (!isAdded() || target == null || view != target) return;
                android.os.Vibrator v = (android.os.Vibrator)
                        requireContext().getSystemService(Context.VIBRATOR_SERVICE);
                if (v != null) v.vibrate(30);   // 响铃改成震一下：手机上「响」多半是骚扰
            } catch (Throwable ignored) {
            }
        });
    }

    // ==================== TerminalViewClient ====================

    @Override
    public float onScale(float scale) {
        // 双指缩放调字号：返回值是库要的「当前字号」，它拿去继续算下一次缩放
        if (scale < 0.9f || scale > 1.1f) {
            bumpFont(scale > 1f ? +1 : -1);
        }
        return fontSp();
    }

    @Override
    public void onSingleTapUp(MotionEvent e) {
        showKeyboard();
    }

    @Override
    public boolean shouldBackButtonBeMappedToEscape() {
        return false;   // 返回键就该是返回键，映射成 Esc 会让人退不出去
    }

    @Override
    public boolean shouldEnforceCharBasedInput() {
        return true;    // 对中文输入法友好：逐字符提交，避免候选词把整行吞掉
    }

    @Override
    public boolean shouldUseCtrlSpaceWorkaround() {
        return false;
    }

    @Override
    public boolean isTerminalViewSelected() {
        return true;
    }

    @Override
    public void copyModeChanged(boolean copyMode) {
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent e, TerminalSession s) {
        return false;   // 交给 TerminalView 默认处理
    }

    @Override
    public boolean onKeyUp(int keyCode, KeyEvent e) {
        return false;
    }

    @Override
    public boolean onLongPress(MotionEvent e) {
        return false;   // 返回 false 才会走库自带的文本选择
    }

    @Override
    public boolean readControlKey() {
        return ctrlDown;
    }

    @Override
    public boolean readAltKey() {
        return altDown;
    }

    @Override
    public boolean readShiftKey() {
        return false;
    }

    @Override
    public boolean readFnKey() {
        return false;
    }

    @Override
    public boolean onCodePoint(int codePoint, boolean ctrlDownFromKeyboard, TerminalSession s) {
        return false;   // 交给库处理（它会来问 readControlKey / readAltKey）
    }

    @Override
    public void onEmulatorSet() {
        onOutput();
    }

    @Override
    public void logError(String tag, String message) {
        android.util.Log.e("DSHA-ptyview", SensitiveData.redact(tag + ": " + message));
    }

    @Override
    public void logWarn(String tag, String message) {
        android.util.Log.w("DSHA-ptyview", SensitiveData.redact(tag + ": " + message));
    }

    @Override
    public void logInfo(String tag, String message) {
        android.util.Log.i("DSHA-ptyview", SensitiveData.redact(tag + ": " + message));
    }

    @Override
    public void logDebug(String tag, String message) {
        android.util.Log.d("DSHA-ptyview", SensitiveData.redact(tag + ": " + message));
    }

    @Override
    public void logVerbose(String tag, String message) {
    }

    @Override
    public void logStackTraceWithMessage(String tag, String message, Exception e) {
        android.util.Log.w("DSHA-ptyview", SensitiveData.redact(tag + ": " + message
                + " " + String.valueOf(e)));
    }

    @Override
    public void logStackTrace(String tag, Exception e) {
        android.util.Log.w("DSHA-ptyview", SensitiveData.redact(tag + ": " + String.valueOf(e)));
    }
}
