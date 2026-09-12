package com.deepseekharness.app;

import com.deepseekharness.app.util.SensitiveData;

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
public final class OverlayController {

    // ---------- 配置项（都在「配置」页的「悬浮条外观」里，这里只给默认值） ----------
    public static final String K_ENABLED = "overlay_stream";
    public static final String K_LINES = "overlay_lines";              // 1..6 行
    /** 悬浮条字号（sp）。允许调得很小 —— 一行能塞多少字全看这个。 */
    public static final String K_TEXT_SP = "overlay_text_sp";
    public static final int DEF_TEXT_SP = 12;
    public static final String K_HOLD = "overlay_hold_sec";            // 2..60 秒
    public static final String K_ALPHA = "overlay_alpha";              // 20..100 %
    public static final String K_BG = "overlay_bg";                    // 预设底色索引
    public static final String K_REASONING = "overlay_show_reasoning";  // 显示思考过程
    public static final String K_COMMAND = "overlay_show_command";      // 工具调用带上命令原文
    public static final String K_CONFIRM = "overlay_confirm";           // 危险命令就地批准

    // 默认 3 行：1 行永远只看得到最后半句，流式内容根本读不了 —— 这个功能的用处
    // 就是扫一眼 agent 在说什么，太窄等于没有。
    public static final int DEF_LINES = 3;
    public static final int DEF_HOLD = 6;
    public static final int DEF_ALPHA = 85;

    /** 预设底色（不做取色器：悬浮条只需要「在任何壁纸上都读得清」，几个深色够用）。 */
    public static final int[] BG_PRESETS = {
            0x11141A,   // 深灰蓝（默认）
            0x000000,   // 纯黑
            0x0D1B2A,   // 深海蓝
            0x102A17,   // 深墨绿
            0x1E1030,   // 深紫
    };
    public static final String[] BG_NAMES = {"深灰蓝", "纯黑", "深海蓝", "深墨绿", "深紫"};

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
    public static boolean permitted(Context ctx) {
        try {
            return Settings.canDrawOverlays(ctx);
        } catch (Throwable e) {
            return false;
        }
    }

    /** 总开关。默认关闭 —— 屏幕上实时显示 AI 输出，旁边的人也看得见。 */
    public static boolean enabled(Context ctx) {
        try {
            return prefs(ctx).getBoolean(K_ENABLED, false);
        } catch (Throwable e) {
            return false;
        }
    }

    public static boolean showReasoning(Context ctx) {
        try {
            return prefs(ctx).getBoolean(K_REASONING, false);
        } catch (Throwable e) {
            return false;
        }
    }

    public static boolean showCommand(Context ctx) {
        try {
            return prefs(ctx).getBoolean(K_COMMAND, true);
        } catch (Throwable e) {
            return true;
        }
    }

    public static boolean confirmOnOverlay(Context ctx) {
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
    public static void push(Context ctx, String sessionKey, String kind, String text) {
        if (ctx == null || !enabled(ctx) || !permitted(ctx)) return;
        // Overlay content is display-only. Keep any credentials in streamed
        // diagnostics out of both the transient buffer and the window.
        text = SensitiveData.redact(text);
        final String k = kind == null ? "delta" : kind;
        if("tool".equals(k))text=com.deepseekharness.app.util.UiText.toolStatus(text);
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
        final String text = com.deepseekharness.app.util.UiText.text("⚠ 请求执行：") + collapse(cmd);
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
                android.util.Log.w("DSHA", com.deepseekharness.app.util.UiText.text("悬浮条确认显示失败: ")
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
                android.util.Log.w("DSHA", com.deepseekharness.app.util.UiText.text("悬浮条更新失败: ")
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
                android.util.Log.w("DSHA", com.deepseekharness.app.util.UiText.text("悬浮条更新失败: ")
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
                if (confirming) return;      // 有待批准的命令时不许自己消失
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
    public static void applyStyleNow(Context ctx) {
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
        hint.setText(com.deepseekharness.app.util.UiText.text("守门人：这条命令要执行吗？"));
        hint.setTextColor(0xFFFFC66D);
        hint.setTextSize(11f);
        hint.setVisibility(View.GONE);
        box.addView(hint);

        LinearLayout row = new LinearLayout(app);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setVisibility(View.GONE);
        row.addView(actionButton(app, R.id.overlay_confirm_allow, com.deepseekharness.app.util.UiText.text("允许"), 0xFF2E7D32));
        row.addView(actionButton(app, R.id.overlay_confirm_deny, com.deepseekharness.app.util.UiText.text("拒绝"), 0xFF8E2A2A));
        box.addView(row);

        // 点条子本身收起（确认时不收 —— 那两个按钮才是出口）
        box.setOnClickListener(v -> {
            if (!confirming) v.setVisibility(View.GONE);
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
            applyStyle(app);
        } catch (Throwable e) {
            // 权限被撤或某些 ROM 拒绝 → 安静降级，不影响 agent 干活
            android.util.Log.w("DSHA", com.deepseekharness.app.util.UiText.text("悬浮条创建失败（权限被撤？）: ")
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
