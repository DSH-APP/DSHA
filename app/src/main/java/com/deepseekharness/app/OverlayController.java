package com.deepseekharness.app;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 屏幕顶部的流式悬浮条：把 agent 正在生成的内容像歌词一样实时显示出来，
 * 顺带承担危险命令的「就地批准」。
 *
 * <p><b>为什么是自绘悬浮窗，而不是「状态栏歌词」。</b> 真正的状态栏歌词没有公开接口：
 * 免 root 能做到的只有 Flyme / exTHmUI 这类认
 * {@code FLAG_ALWAYS_SHOW_TICKER + FLAG_ONLY_UPDATE_TICKER} 的 ROM，其余机型都要靠
 * StatusBarLyric 这类 Xposed 模块 hook 系统界面。DSHA 主打免 ROOT，不能把核心功能压在
 * root 上，所以走 {@code TYPE_APPLICATION_OVERLAY}：一次性授权、全 ROM 通用、样式自控。
 *
 * <p><b>不用 Service。</b> 悬浮窗只需要 {@code WindowManager} 和一个 View，而调用方
 * （{@link HttpShellService}）本身就活在前台服务进程里 —— 再包一层只会多一份 Android 8+
 * 的后台启动限制要伺候。所以这里是纯静态控制器，生命周期跟着 App 进程。
 *
 * <p><b>为什么把守门人的批准也放这儿。</b> 危险命令确认原来有两条渠道：通知（权威）和
 * 前台弹窗（快捷）。可 agent 干活时用户往往并不在 App 里 —— 要么拉下通知栏找那条通知，
 * 要么切回 App。悬浮条本来就浮在最上层且此刻正显示着这条命令，就地给两个按钮是最短路径。
 * 它是<b>第三条渠道</b>而不是替代：所有渠道共用同一个 epoch + latch，谁先点谁生效。
 *
 * <p><b>多实例</b>：dsh 可以同时跑多个会话，各自都在吐字。按 sessionKey 分桶，
 * 只渲染最后活跃的那一路，多路并发时加短标识前缀，否则两路输出会交织成乱码。
 */
final class OverlayController {

    // ---------- 配置项（都在「配置」页的「悬浮条外观」里，这里只给默认值） ----------
    static final String K_ENABLED = "overlay_stream";
    static final String K_LINES = "overlay_lines";              // 1..6 行
    /** 悬浮条字号（sp）。允许调得很小 —— 一行能塞多少字全看这个。 */
    static final String K_TEXT_SP = "overlay_text_sp";
    static final int DEF_TEXT_SP = 12;
    static final String K_HOLD = "overlay_hold_sec";            // 2..60 秒
    static final String K_ALPHA = "overlay_alpha";              // 20..100 %
    static final String K_BG = "overlay_bg";                    // 预设底色索引
    static final String K_REASONING = "overlay_show_reasoning";  // 显示思考过程
    static final String K_COMMAND = "overlay_show_command";      // 工具调用带上命令原文
    static final String K_CONFIRM = "overlay_confirm";           // 危险命令就地批准
    static final String K_REPLY = "overlay_reply";               // 回复完成后就地回话

    // 默认 3 行：1 行永远只看得到最后半句，流式内容根本读不了 —— 这个功能的用处
    // 就是扫一眼 agent 在说什么，太窄等于没有。
    static final int DEF_LINES = 3;
    static final int DEF_HOLD = 6;
    static final int DEF_ALPHA = 85;

    /** 预设底色（不做取色器：悬浮条只需要「在任何壁纸上都读得清」，几个深色够用）。 */
    static final int[] BG_PRESETS = {
            0x11141A,   // 深灰蓝（默认）
            0x000000,   // 纯黑
            0x0D1B2A,   // 深海蓝
            0x102A17,   // 深墨绿
            0x1E1030,   // 深紫
    };
    static final String[] BG_NAMES = {"深灰蓝", "纯黑", "深海蓝", "深墨绿", "深紫"};

    /** 每行按多少字符估算。宽度由系统折行决定，这里只用来决定「留多少尾部内容」。 */
    /** 每行的显示宽度见 {@link OverlayLines#DEFAULT_WIDTH}。下面两个是缓冲上限：
     *  超过 RAW_CAP 就一次砍到 RAW_KEEP —— 绝不能每来一个字砍一个字，那样分行的
     *  起点每次都挪一格、前面的行跟着重排，就又变回「文字不停往左滚」了。 */
    private static final int RAW_CAP = 1200;
    private static final int RAW_KEEP = 800;
    private static final int MAX_SESSIONS = 8;

    private static final Object LOCK = new Object();
    /** sessionKey → 该会话当前显示的文本。 */
    private static final Map<String, String> BUFFERS = new LinkedHashMap<>();

    /** sessionKey → 最后活跃时间。判断「是否真有多路在并发说话」用。
     *  <b>不能拿 BUFFERS.size() 当判据</b> —— 那里会留着切过的会话、跑完没清的桶，
     *  于是单开一路也永远带着个 [xx] 前缀（用户报的「最前面莫名其妙一个 [2d]」）。 */
    private static final Map<String, Long> LAST_SEEN = new LinkedHashMap<>();
    private static final long MULTI_WINDOW_MS = 15_000;

    private static Handler main;
    private static WindowManager wm;
    private static LinearLayout root;
    private static TextView label;
    private static LinearLayout confirmRow;
    private static TextView confirmHint;
    /** 就地回话：输入栏那一行、输入框本身，以及用户已按发送、等着插件来取的文本。 */
    private static LinearLayout replyRow;
    private static android.widget.EditText replyInput;
    /** 队列而不是单个字段：用户可能连按两次发送，后一条不该把前一条挤掉。
     *  插件取走即出队，取不到就是 EMPTY —— 桥那一侧不做等待，长轮询在插件里做。 */
    private static final java.util.ArrayDeque<String> PENDING_REPLIES = new java.util.ArrayDeque<>();
    private static final int MAX_PENDING_REPLIES = 8;
    /** 输入栏正显示 —— 与 confirming 同性质：这期间不许自动淡出、不许被流式内容顶掉。 */
    private static boolean replying;
    private static Runnable hideTask;
    private static String activeKey = "";
    /** 确认进行中：这期间不自动淡出、也不让流式内容盖掉命令。 */
    private static volatile boolean confirming;

    private OverlayController() {
    }

    private static SharedPreferences prefs(Context ctx) {
        return ctx.getSharedPreferences("deepseekharness", Context.MODE_PRIVATE);
    }

    /** 用户是否已授予悬浮窗权限。没权限时一切 push 直接丢弃，不弹系统弹窗骚扰。 */
    static boolean permitted(Context ctx) {
        try {
            return Settings.canDrawOverlays(ctx);
        } catch (Throwable e) {
            return false;
        }
    }

    /** 总开关。默认关闭 —— 屏幕上实时显示 AI 输出，旁边的人也看得见。 */
    static boolean enabled(Context ctx) {
        try {
            return prefs(ctx).getBoolean(K_ENABLED, false);
        } catch (Throwable e) {
            return false;
        }
    }

    static boolean showReasoning(Context ctx) {
        try {
            return prefs(ctx).getBoolean(K_REASONING, false);
        } catch (Throwable e) {
            return false;
        }
    }

    static boolean showCommand(Context ctx) {
        try {
            return prefs(ctx).getBoolean(K_COMMAND, true);
        } catch (Throwable e) {
            return true;
        }
    }

    static boolean confirmOnOverlay(Context ctx) {
        try {
            return prefs(ctx).getBoolean(K_CONFIRM, true);
        } catch (Throwable e) {
            return true;
        }
    }

    private static int lines(Context ctx) {
        return clamp(prefs(ctx).getInt(K_LINES, DEF_LINES), 1, 6);
    }

    private static int holdMs(Context ctx) {
        return clamp(prefs(ctx).getInt(K_HOLD, DEF_HOLD), 2, 60) * 1000;
    }

    private static int alphaPct(Context ctx) {
        return clamp(prefs(ctx).getInt(K_ALPHA, DEF_ALPHA), 20, 100);
    }

    private static int bgColor(Context ctx) {
        int idx = clamp(prefs(ctx).getInt(K_BG, 0), 0, BG_PRESETS.length - 1);
        int a = Math.round(alphaPct(ctx) * 255f / 100f);
        return (a << 24) | (BG_PRESETS[idx] & 0xFFFFFF);
    }

    private static int clamp(int v, int lo, int hi) {
        return v < lo ? lo : (v > hi ? hi : v);
    }

    // ================= 流式内容 =================

    /**
     * 推一段内容到悬浮条。
     *
     * @param kind {@code delta} 追加 · {@code text} 整行替换 · {@code tool} 工具状态
     *             · {@code reasoning} 思考过程 · {@code done} 本轮收尾 · {@code clear} 立刻收起
     */
    static void push(Context ctx, String sessionKey, String kind, String text) {
        if (ctx == null || !enabled(ctx) || !permitted(ctx)) return;
        // 悬浮条是贴在屏幕最顶层、旁人也看得见的东西，而它显示的是 AI 输出流与命令原文
        // —— 里面完全可能带着 API key 或 auth URL。所以这里脱敏，代价是 AI 正好在讲
        // OAuth 之类内容时会看到 <redacted>；瞥一眼进度少看到一个 token 不影响使用，
        // 完整内容在 WebUI 里照旧。（与终端复制回调刻意不脱敏的取舍不同：那边用户是要
        // 把内容拿走去用，改写等于给他一个假的粘贴结果。）
        text = SensitiveData.redact(text);
        final String k = kind == null ? "delta" : kind;
        if ("reasoning".equals(k) && !showReasoning(ctx)) return;
        final String key = sessionKey == null || sessionKey.isEmpty() ? "-" : sessionKey;
        // 确认进行中：命令和按钮不能被流式内容顶掉（用户正要点它）
        if (confirming && !"clear".equals(k)) return;

        String line;
        synchronized (LOCK) {
            if ("clear".equals(k)) {
                BUFFERS.remove(key);
                if (key.equals(activeKey)) activeKey = "";
                hideNow();
                return;
            }
            String prev = BUFFERS.get(key);
            if (prev == null) prev = "";
            String next;
            if ("delta".equals(k)) {
                next = prev + (text == null ? "" : text);
            } else if ("done".equals(k)) {
                next = prev;                       // 留最后一句自然淡出
            } else if ("reasoning".equals(k)) {
                next = "💭 " + (text == null ? "" : text);
            } else {
                next = text == null ? "" : text;   // text / tool
            }
            next = capRaw(collapse(next));
            BUFFERS.put(key, next);
            while (BUFFERS.size() > MAX_SESSIONS) {
                BUFFERS.remove(BUFFERS.keySet().iterator().next());
            }
            activeKey = key;
            LAST_SEEN.put(key, System.currentTimeMillis());
            while (LAST_SEEN.size() > MAX_SESSIONS) {
                LAST_SEEN.remove(LAST_SEEN.keySet().iterator().next());
            }
            // 这里存的是**未分行的原文**：分行留给主线程做（见 showStream），
            // 只有那边拿得到 label 的实测宽度与字号。
            line = next;
        }
        showStream(ctx, key, line);
    }

    /** 会话标识压成两三个字符，多路并发时用来分辨谁在说话。 */
    private static String shortTag(String key) {
        String s = key.replaceAll("[^0-9A-Za-z\\u4e00-\\u9fa5]", "");
        if (s.isEmpty()) return "[·]";
        return "[" + s.substring(Math.max(0, s.length() - 2)) + "]";
    }

    /** 换行与连续空白压成单空格：多行是靠系统折行，原样塞进去会看起来像卡住。 */
    private static String collapse(String s) {
        return s == null ? "" : s.replaceAll("\\s+", " ").trim();
    }

    /** 缓冲上限：超过 RAW_CAP 就一次砍到 RAW_KEEP，并尽量从词边界开始。
     *  为什么不逐字砍 —— 分行起点每挪一格，前面所有行就重排一次，那正是要修的毛病。 */
    private static String capRaw(String s) {
        if (s == null) return "";
        if (s.length() <= RAW_CAP) return s;
        int from = s.length() - RAW_KEEP;
        int sp = s.indexOf(' ', from);
        if (sp > 0 && sp - from < 40) from = sp + 1;
        return s.substring(from);
    }

    // ================= 就地回话 =================

    /** 用户开了「回复完成后可就地回话」吗。 */
    static boolean replyEnabled(Context ctx) {
        try {
            return ctx.getSharedPreferences("deepseekharness", Context.MODE_PRIVATE)
                    .getBoolean(K_REPLY, false);
        } catch (Throwable e) {
            return false;
        }
    }

    /**
     * agent 说完一轮（{@code kind=done}）→ 在条子下面露出输入栏。
     *
     * <p><b>为什么只在说完之后。</b>agent 正在生成时插话，dsh 那边要么排队要么打断，
     * 两种都不是用户按下发送时期待的事；而「它说完了、我看一眼、直接接一句」是这个
     * 功能真正的用处 —— 用户此刻在别的 App 里，不必切回来。
     *
     * <p>不自动聚焦：一露出来就弹输入法会把用户当前在做的事（看视频、打字）打断。
     * 要等用户主动点输入框 —— 那一下才是「我要回话」的明确意思。
     */
    static void showReply(Context ctx) {
        if (ctx == null || !enabled(ctx) || !permitted(ctx) || !replyEnabled(ctx)) return;
        if (confirming) return;              // 批准优先，别把两组控件同时摆出来
        mainHandler().post(() -> {
            try {
                ensureView(ctx);
                if (root == null || replyRow == null || replyInput == null) return;
                replying = true;
                replyRow.setVisibility(View.VISIBLE);
                root.setVisibility(View.VISIBLE);
                if (hideTask != null) mainHandler().removeCallbacks(hideTask);   // 等用户，不淡出

                // 点输入框才真正接焦点。窗口默认带 FLAG_NOT_FOCUSABLE（否则悬浮条一出现
                // 就抢走当前 App 的输入焦点），而输入法只对可聚焦窗口弹出 ——
                // 所以这里临时摘掉那个 flag，收工再加回去。
                replyInput.setOnClickListener(v -> setFocusable(ctx, true));
                replyInput.setOnEditorActionListener((v, actionId, ev) -> {
                    if (actionId == android.view.inputmethod.EditorInfo.IME_ACTION_SEND) {
                        submitReply(ctx);
                        return true;
                    }
                    return false;
                });
                android.view.View send = root.findViewById(R.id.overlay_reply_send);
                if (send != null) send.setOnClickListener(v -> submitReply(ctx));
            } catch (Throwable e) {
                android.util.Log.w("DSHA", "悬浮条输入栏显示失败: "
                        + SensitiveData.redact(String.valueOf(e)));
                replying = false;
            }
        });
    }

    /** 把输入框里的话入队，交给插件下次轮询时取走。空文本当作「收起」。 */
    private static void submitReply(Context ctx) {
        if (replyInput == null) return;
        String t = String.valueOf(replyInput.getText()).trim();
        if (!t.isEmpty()) {
            synchronized (LOCK) {
                // 满了丢最旧的：留着一堆过期的话没有意义，用户等的是最近这句
                while (PENDING_REPLIES.size() >= MAX_PENDING_REPLIES) PENDING_REPLIES.pollFirst();
                PENDING_REPLIES.addLast(t);
            }
            replyInput.setText("");
        }
        dismissReply(ctx);
    }

    /** 收起输入栏并交还焦点。下一轮 done 时会再露出来。 */
    static void dismissReply(Context ctx) {
        mainHandler().post(() -> {
            try {
                replying = false;
                setFocusable(ctx, false);
                if (replyRow != null) replyRow.setVisibility(View.GONE);
                // 收起后按正常规则淡出，不要一直挂在屏幕上
                scheduleHide(ctx);
            } catch (Throwable ignored) {
            }
        });
    }

    /**
     * 插件取走一条待发文本；没有就返回 null。
     *
     * <p>取走即出队 —— 这条通路上「取到了但没发出去」和「没取到」对用户是同一件事
     * （话丢了），而重复发送是更糟的结果（agent 会答两遍）。所以选不重发。
     */
    static String takePendingReply() {
        synchronized (LOCK) {
            return PENDING_REPLIES.pollFirst();
        }
    }

    /**
     * 临时切换窗口能不能接焦点。
     *
     * <p>输入法只对可聚焦窗口弹出，而悬浮条平时必须是 NOT_FOCUSABLE —— 否则它一出现
     * 就把用户当前 App 的输入焦点抢走（正在微信打字会突然打不动）。
     * 所以只在用户点了输入框那一下摘掉 flag，发送或收起时立刻加回去。
     *
     * <p>{@code SOFT_INPUT_STATE_VISIBLE} 让输入法跟着这次 update 一起弹出来，
     * 不必再手动调 showSoftInput（那个在悬浮窗上时好时坏，取决于 ROM）。
     */
    private static void setFocusable(Context ctx, boolean on) {
        if (root == null || wm == null) return;
        try {
            android.view.ViewGroup.LayoutParams raw = root.getLayoutParams();
            if (!(raw instanceof WindowManager.LayoutParams)) return;
            WindowManager.LayoutParams lp = (WindowManager.LayoutParams) raw;
            if (on) {
                lp.flags &= ~WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE;
                lp.softInputMode = WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE
                        | WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE;
            } else {
                lp.flags |= WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE;
                lp.softInputMode = WindowManager.LayoutParams.SOFT_INPUT_STATE_UNSPECIFIED;
                if (replyInput != null) replyInput.clearFocus();
            }
            wm.updateViewLayout(root, lp);
        } catch (Throwable e) {
            android.util.Log.w("DSHA", "悬浮条焦点切换失败: "
                    + SensitiveData.redact(String.valueOf(e)));
        }
    }

    // ================= 危险命令就地批准 =================

    /**
     * 在悬浮条上请求批准。第三条渠道，与通知、前台弹窗并行 —— 谁先点谁生效
     * （epoch + latch 由 {@link HttpShellService} 收口，这里只管回调）。
     *
     * <p>确认期间不自动淡出，也不让流式内容覆盖：用户正要点的东西不能被顶掉。
     */
    static void askConfirm(Context ctx, String cmd, Runnable onAllow, Runnable onDeny) {
        if (ctx == null || !enabled(ctx) || !permitted(ctx) || !confirmOnOverlay(ctx)) return;
        confirming = true;
        final String text = "⚠ 请求执行：" + collapse(cmd);
        mainHandler().post(() -> {
            try {
                ensureView(ctx);
                if (label == null || confirmRow == null) return;
                // 命令可能很长，确认时多给几行看清楚（比配置的行数多，但不超过 8）
                int cmdLines = Math.max(3, Math.min(8, lines(ctx) + 2));
                label.setMaxLines(cmdLines);
                // 命令按宽度切好再显示，而且取**开头**几行：交给系统折行会被 maxLines
                // 从尾部截掉，而用户正要判断「这条命令能不能跑」——rm -rf 这种关键部分
                // 恰好在最前面
                label.setText(OverlayLines.firstLines(text, cmdLines, OverlayLines.DEFAULT_WIDTH));
                confirmRow.setVisibility(View.VISIBLE);
                if (confirmHint != null) confirmHint.setVisibility(View.VISIBLE);
                root.setVisibility(View.VISIBLE);
                if (hideTask != null) mainHandler().removeCallbacks(hideTask);   // 等用户，不淡出

                confirmRow.findViewById(R.id.overlay_confirm_allow).setOnClickListener(v -> {
                    finishConfirm(ctx);
                    if (onAllow != null) onAllow.run();
                });
                confirmRow.findViewById(R.id.overlay_confirm_deny).setOnClickListener(v -> {
                    finishConfirm(ctx);
                    if (onDeny != null) onDeny.run();
                });
            } catch (Throwable e) {
                android.util.Log.w("DSHA", "悬浮条确认显示失败: "
                        + SensitiveData.redact(String.valueOf(e)));
                confirming = false;
            }
        });
    }

    /** 别的渠道（通知/弹窗）已经决定了，或者请求超时 → 收掉按钮。 */
    static void dismissConfirm(Context ctx) {
        if (!confirming) return;
        finishConfirm(ctx);
    }

    private static void finishConfirm(Context ctx) {
        confirming = false;
        mainHandler().post(() -> {
            try {
                if (confirmRow != null) confirmRow.setVisibility(View.GONE);
                if (confirmHint != null) confirmHint.setVisibility(View.GONE);
                if (label != null) label.setMaxLines(lines(ctx));
                scheduleHide(ctx);
            } catch (Throwable ignored) {
            }
        });
    }

    // ================= 窗口 =================

    /**
     * 流式内容的显示。分行与会话标识都在这里做 —— 只有主线程拿得到控件的实测宽度。
     *
     * <p>原先是在 push（后台线程）里按固定 30 半角切好再送过来。那个宽度在不同屏宽、
     * 不同系统字体缩放下都会错：宽了系统会再折一次，行数超出 maxLines 就被从尾部截掉；
     * 窄了则白留一截空白。用户报的「换行也很奇怪」就是这个。
     */
    private static void showStream(Context ctx, String key, String raw) {
        mainHandler().post(() -> {
            try {
                ensureView(ctx);
                if (label != null) {
                    int maxLines = lines(ctx);
                    label.setMaxLines(maxLines);
                    CharSequence body = tailLines(ctx, label, raw, maxLines);
                    // 会话标识只在**最近真有多路在说话**时才贴，而且贴在可见的第一行
                    if (multiActive()) body = shortTag(key) + " " + body;
                    label.setText(body);
                }
                if (root != null) {
                    applyStyle(ctx);
                    if (root.getVisibility() != View.VISIBLE) root.setVisibility(View.VISIBLE);
                }
                scheduleHide(ctx);
            } catch (Throwable e) {
                android.util.Log.w("DSHA", "悬浮条更新失败: "
                        + SensitiveData.redact(String.valueOf(e)));
            }
        });
    }

    /** 悬浮条宽度：屏幕的 94%。定死而不用 WRAP_CONTENT —— 包裹内容会让条子随文字多少
     *  左右抖动，而且分行算容量时只能拿到「上一次内容的宽度」，越切越碎。 */
    private static int overlayWidthPx(Context ctx) {
        try {
            int w = ctx.getResources().getDisplayMetrics().widthPixels;
            if (w > 0) return (int) (w * 0.94f);
        } catch (Throwable ignored) {
        }
        return dp(ctx, 320);
    }

    /** 当前字号（sp）。 */
    private static int textSp(Context ctx) {
        int v = prefs(ctx).getInt(K_TEXT_SP, DEF_TEXT_SP);
        return Math.max(6, Math.min(28, v));
    }

    /**
     * 按**真实渲染宽度**取末尾若干行。
     *
     * <p>不再自己数格子。比例字体里 i 和 W 差一倍多，emoji 又是另一套宽度，
     * 「半角算 1 格、CJK 算 2 格」只是个粗略近似 —— 用户实测的说法「有的字符大有的字符
     * 小」完全对。StaticLayout 就是 TextView 内部用的那个排版器，让它来断行，结果必然
     * 与实际显示一致；我们只负责把前面多出来的行切掉。字号也自动跟着算，因为宽度是从
     * {@code tv.getPaint()} 量的，那份 paint 里就带着当前字号。
     */
    private static CharSequence tailLines(Context ctx, TextView tv, String raw, int maxLines) {
        if (raw == null || raw.isEmpty()) return "";
        try {
            int width = overlayWidthPx(ctx) - tv.getPaddingLeft() - tv.getPaddingRight()
                    - dp(ctx, 24);                       // 容器自己的左右 padding
            if (width < dp(ctx, 40)) width = dp(ctx, 40);
            android.text.StaticLayout sl = android.text.StaticLayout.Builder
                    .obtain(raw, 0, raw.length(), tv.getPaint(), width)
                    .setIncludePad(false)
                    .build();
            int n = sl.getLineCount();
            if (n <= maxLines) return raw;
            return raw.substring(sl.getLineStart(n - maxLines));
        } catch (Throwable e) {
            // 兜底：老的格数估算。不精确，但总比整段不显示好
            return OverlayLines.lastLines(raw, maxLines, OverlayLines.DEFAULT_WIDTH);
        }
    }

    /** 最近 {@link #MULTI_WINDOW_MS} 内是否真有两路以上在说话。 */
    private static boolean multiActive() {
        long now = System.currentTimeMillis();
        int n = 0;
        synchronized (LOCK) {
            for (Long t : LAST_SEEN.values()) {
                if (t != null && now - t <= MULTI_WINDOW_MS) n++;
            }
        }
        return n > 1;
    }

    private static void show(Context ctx, String line, boolean sticky) {
        mainHandler().post(() -> {
            try {
                ensureView(ctx);
                if (label != null) {
                    label.setMaxLines(lines(ctx));
                    label.setText(line);
                }
                if (root != null) {
                    applyStyle(ctx);
                    if (root.getVisibility() != View.VISIBLE) root.setVisibility(View.VISIBLE);
                }
                if (!sticky) scheduleHide(ctx);
            } catch (Throwable e) {
                android.util.Log.w("DSHA", "悬浮条更新失败: "
                        + SensitiveData.redact(String.valueOf(e)));
            }
        });
    }

    private static void scheduleHide(Context ctx) {
        Handler h = mainHandler();
        if (hideTask != null) h.removeCallbacks(hideTask);
        hideTask = OverlayController::hideNow;
        h.postDelayed(hideTask, holdMs(ctx));
    }

    private static void hideNow() {
        mainHandler().post(() -> {
            try {
                // 有待批准的命令、或用户正在打字时不许自己消失
                if (confirming || replying) return;
                if (root != null) root.setVisibility(View.GONE);
            } catch (Throwable ignored) {
            }
        });
    }

    /** 彻底移除窗口（关开关 / 撤权限 / 改样式后重建时用）。 */
    static void teardown(Context ctx) {
        mainHandler().post(() -> {
            synchronized (LOCK) {
                BUFFERS.clear();
                activeKey = "";
            }
            confirming = false;
            try {
                if (wm != null && root != null) wm.removeViewImmediate(root);
            } catch (Throwable ignored) {
            }
            root = null;
            label = null;
            confirmRow = null;
            confirmHint = null;
            wm = null;
        });
    }

    /** 配置改了之后立刻看到效果（底色/透明度/行数都能热应用）。 */
    static void applyStyleNow(Context ctx) {
        mainHandler().post(() -> {
            try {
                if (root == null) return;
                applyStyle(ctx);
                if (label != null) label.setMaxLines(lines(ctx));
            } catch (Throwable ignored) {
            }
        });
    }

    private static void applyStyle(Context ctx) {
        if (root == null) return;
        GradientDrawable bg = new GradientDrawable();
        bg.setCornerRadius(dp(ctx, 16));
        bg.setColor(bgColor(ctx));
        root.setBackground(bg);
        // 字号每次显示都重新应用：用户在配置页拉完滑块，下一条内容就是新字号，
        // 不必重启 App。断行宽度是从 paint 量的，所以它跟着自动变。
        if (label != null) label.setTextSize(textSp(ctx));
    }

    private static Handler mainHandler() {
        if (main == null) main = new Handler(Looper.getMainLooper());
        return main;
    }

    private static void ensureView(Context ctx) {
        if (root != null) return;
        Context app = ctx.getApplicationContext();
        wm = (WindowManager) app.getSystemService(Context.WINDOW_SERVICE);
        if (wm == null) return;

        LinearLayout box = new LinearLayout(app);
        box.setOrientation(LinearLayout.VERTICAL);
        int padH = dp(app, 12), padV = dp(app, 6);
        box.setPadding(padH, padV, padH, padV);
        box.setVisibility(View.GONE);

        TextView tv = new TextView(app);
        tv.setTextColor(Color.WHITE);
        tv.setTextSize(textSp(app));
        tv.setMaxLines(DEF_LINES);
        // 不设 ellipsize：分行由我们自己按宽度算好（见 OverlayLines），文本不会超宽。
        // 原先设的是 TruncateAt.START —— 那是单行时代「新字始终在右边可见」的做法，
        // 放到多行 TextView 上行为很怪（会把前面的行截掉一截），看着就是「换行很奇怪」。
        box.addView(tv, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));

        TextView hint = new TextView(app);
        hint.setText("守门人：这条命令要执行吗？");
        hint.setTextColor(0xFFFFC66D);
        hint.setTextSize(11f);
        hint.setVisibility(View.GONE);
        box.addView(hint);

        LinearLayout row = new LinearLayout(app);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setVisibility(View.GONE);
        row.addView(actionButton(app, R.id.overlay_confirm_allow, "允许", 0xFF2E7D32));
        row.addView(actionButton(app, R.id.overlay_confirm_deny, "拒绝", 0xFF8E2A2A));
        box.addView(row);

        // 就地回话那一行：输入框 + 发送。默认 GONE，只在 agent 说完一轮（turn/end）时露出来。
        LinearLayout rr = new LinearLayout(app);
        rr.setOrientation(LinearLayout.HORIZONTAL);
        rr.setGravity(Gravity.CENTER_VERTICAL);
        rr.setVisibility(View.GONE);
        android.widget.EditText in = new android.widget.EditText(app);
        in.setId(R.id.overlay_reply_input);
        in.setHint("回一句…");
        in.setHintTextColor(0x99FFFFFF);
        in.setTextColor(Color.WHITE);
        in.setTextSize(13f);
        in.setMaxLines(3);
        in.setSingleLine(false);
        // IME_ACTION_SEND：软键盘上直接出「发送」，不用去点右边那个按钮。
        in.setImeOptions(android.view.inputmethod.EditorInfo.IME_ACTION_SEND);
        in.setInputType(android.text.InputType.TYPE_CLASS_TEXT
                | android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE);
        GradientDrawable inBg = new GradientDrawable();
        inBg.setCornerRadius(dp(app, 10));
        inBg.setColor(0x33FFFFFF);
        in.setBackground(inBg);
        int ip = dp(app, 8);
        in.setPadding(ip, dp(app, 4), ip, dp(app, 4));
        LinearLayout.LayoutParams inLp = new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        inLp.topMargin = dp(app, 4);
        inLp.rightMargin = dp(app, 8);
        rr.addView(in, inLp);
        rr.addView(actionButton(app, R.id.overlay_reply_send, "发送", 0xFF1E5AA8));
        box.addView(rr);

        // 点条子本身收起（确认时不收 —— 那两个按钮才是出口）
        box.setOnClickListener(v -> {
            // 回话期间也不收：用户正要打字，点空白处把整条收掉等于把输入清了
            if (!confirming && !replying) v.setVisibility(View.GONE);
        });

        WindowManager.LayoutParams lp = new WindowManager.LayoutParams();
        lp.type = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                ? WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                : WindowManager.LayoutParams.TYPE_PHONE;
        // NOT_FOCUSABLE：不抢输入焦点（否则输入法会被顶掉），但触摸照样收得到，
        //                所以确认按钮可以点。
        // NOT_TOUCH_MODAL：条子以外的触摸原样传给下面的应用。
        lp.flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL;
        lp.format = android.graphics.PixelFormat.TRANSLUCENT;
        // 宽度定死（屏幕 94%）而不是 WRAP_CONTENT：包裹内容会让条子随文字多少左右抖动，
        // 更要命的是分行算容量时只拿得到「上一次内容的宽度」—— 内容少→宽度小→切得更碎→
        // 内容更少，一路收敛到每行六个字（用户实测）。宽度稳定，容量才算得准。
        lp.width = overlayWidthPx(app);
        lp.height = WindowManager.LayoutParams.WRAP_CONTENT;
        lp.gravity = Gravity.TOP | Gravity.CENTER_HORIZONTAL;
        lp.y = dp(app, 34);     // 贴状态栏下沿：不遮时钟与刘海，也不抢下拉手势

        try {
            wm.addView(box, lp);
            root = box;
            label = tv;
            confirmRow = row;
            confirmHint = hint;
            replyRow = rr;
            replyInput = in;
            applyStyle(app);
        } catch (Throwable e) {
            // 权限被撤或某些 ROM 拒绝 → 安静降级，不影响 agent 干活
            android.util.Log.w("DSHA", "悬浮条创建失败（权限被撤？）: "
                    + SensitiveData.redact(String.valueOf(e)));
            root = null;
            label = null;
            confirmRow = null;
            confirmHint = null;
        }
    }

    private static TextView actionButton(Context ctx, int id, String text, int color) {
        TextView b = new TextView(ctx);
        b.setId(id);
        b.setText(text);
        b.setTextColor(Color.WHITE);
        b.setTextSize(13f);
        b.setGravity(Gravity.CENTER);
        int ph = dp(ctx, 16), pv = dp(ctx, 6);
        b.setPadding(ph, pv, ph, pv);
        GradientDrawable bg = new GradientDrawable();
        bg.setCornerRadius(dp(ctx, 12));
        bg.setColor(color);
        b.setBackground(bg);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(ctx, 4);
        lp.rightMargin = dp(ctx, 8);
        b.setLayoutParams(lp);
        return b;
    }

    private static int dp(Context ctx, int v) {
        return Math.round(v * ctx.getResources().getDisplayMetrics().density);
    }
}
