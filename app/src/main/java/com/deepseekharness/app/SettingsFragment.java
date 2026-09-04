package com.deepseekharness.app;

import android.os.Bundle;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.fragment.app.Fragment;

import java.util.function.Supplier;

public class SettingsFragment extends Fragment {

    private HarnessController c;

    private static final TabOption[] TAB_OPTIONS = {
            new TabOption("安装", "分步安装 rootfs / 工具 / Node / harness", InstallFragment::new),
            new TabOption("配置", "API key · 端口 · 模型 · 沙箱模式", ConfigFragment::new),
            new TabOption("工作区", "工作目录 · 文件共享 · 备份恢复 · Shizuku", WorkspaceFragment::new),
    };

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        return inflater.inflate(R.layout.fragment_settings, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        c = HarnessController.get(requireContext());
        // 类型写 ViewGroup 而不是 LinearLayout：这个容器要换成 GlassCard（BlurView 的子类，
        // 也就是 FrameLayout），findViewById 的泛型隐式 cast 会直接 ClassCastException
        // —— 之前就是这么崩的。下面添子 View 用的 LinearLayout.LayoutParams 仍然成立，
        // 因为 GlassCard 把 addView 转发给它内部那个竖向 LinearLayout。
        android.view.ViewGroup tabs = view.findViewById(R.id.settings_tabs);
        for (int i = 0; i < TAB_OPTIONS.length; i++) {
            if (i > 0) {
                View divider = new View(requireContext());
                divider.setLayoutParams(new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, 1));
                divider.setBackgroundColor(requireContext().getColor(R.color.line));
                tabs.addView(divider);
            }
            tabs.addView(buildRow(i));
        }

        String version = "unknown";
        try {
            version = requireContext().getPackageManager()
                    .getPackageInfo(requireContext().getPackageName(), 0).versionName;
        } catch (Exception ignored) {
        }
        TextView ver = view.findViewById(R.id.settings_ver);
        ver.setText("DSHA v" + version + " · MIT License");
        TextView updateSub = view.findViewById(R.id.settings_update_sub);
        updateSub.setText("当前 v" + version + " · 从 GitHub Releases 检查");
        view.findViewById(R.id.settings_about).setOnClickListener(v -> AboutDialog.show(requireContext()));
        view.findViewById(R.id.settings_update).setOnClickListener(v -> checkUpdate());
        View selfTest = view.findViewById(R.id.settings_selftest);
        if (selfTest != null) selfTest.setOnClickListener(v -> runSelfTest());
        View reextract = view.findViewById(R.id.settings_reextract);
        if (reextract != null) reextract.setOnClickListener(v -> confirmReextract());
    }

    /** 重新解压内置环境：容器被弄坏之后的自助修复入口。
     *
     *  <p>为什么要单独有个入口：这个动作原先只能从「发现新版内置环境」那个弹窗进去，
     *  而弹窗只在 APK 内置包比已解压的新时才出现。于是环境一旦被弄坏，用户就只剩
     *  卸载重装 —— 而卸载会把 rootfs 连对话记录一起带走。真机上真的发生过：一次全局
     *  npm 安装中途断了，把 /usr/local/lib/node_modules 连 npm 自己一起带走，之后
     *  安装步骤只报一个裸 127，用户无路可走。
     *
     *  <p>数据保护在重解压流程里（ProotBootstrap 会先备份 .dsh 与各工作区 .env，
     *  解压完再还原），所以这里只负责把话说清楚：什么会保留、什么会回到出厂状态。 */
    private void confirmReextract() {
        new AlertDialog.Builder(requireContext())
                .setTitle("重新解压内置环境")
                .setMessage("用 APK 里自带的环境覆盖当前容器，约数分钟。\n\n"
                        + "会保留：配置、API Key、对话记录（自动备份后还原）。\n"
                        + "会回到出厂状态：自己在容器里额外装的东西（apt 包、全局 npm 包、插件）。\n\n"
                        + "适用场景：dsh 或 npm 不见了、安装步骤报 127、环境怎么修都不对。")
                .setPositiveButton("重新解压", (d, w) -> {
                    try {
                        android.content.Intent i =
                                new android.content.Intent(requireContext(), ExtractActivity.class);
                        i.putExtra("force_extract", true);
                        i.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK
                                | android.content.Intent.FLAG_ACTIVITY_CLEAR_TASK);
                        startActivity(i);
                    } catch (Throwable t) {
                        DshaSnack.showLong(SettingsFragment.this, "打不开解压页：" + t);
                    }
                })
                .setNegativeButton("算了", null)
                .show();
    }

    /** 一键自检 & 修补：后台跑检查并顺手修掉能自动修的（补链接 / 修空壳 /
     *  修坏掉的 patch.yml 等），结果用可滚动弹窗展示（可一键复制发给开发者） */
    private void runSelfTest() {
        DshaSnack.show(SettingsFragment.this, "正在自检并修补，约 10~30 秒…");
        final android.content.Context app = requireContext().getApplicationContext();
        new Thread(() -> {
            // 运行时兼容性要第一个查，而且必须在 App 侧查：selftest.py 本身是 python，
            // 而这类故障会让容器里所有 python 一启动就 abort —— 自检自己就是第一个
            // 受害者，用户只会看到一屏寄存器。先用不依赖 python 的探针过一遍、
            // 命中就当场切回 proot，再跑常规自检（这时它已经能起来了）。
            final HarnessController hc = HarnessController.get(app);
            final String health = hc.checkRuntimeHealthAndHeal();
            // 崩溃记录放最前面：有人报「闪退」时这是唯一能自证的东西。没有记录也是信息 ——
            // 说明不是 Java 层异常，而是内存不足被杀 / native 崩溃 / ANR 强杀。
            final String crash = DshaApp.recentCrashSummary(app, 3);
            // 桥与悬浮条这条链只有 App 侧查得到（服务状态、悬浮窗权限、token 两侧对比），
            // 而且必须端到端真发一次请求 —— selftest.py 跑在容器里，既看不到 host 侧的
            // 监听 socket（用户实测 ss「没监听」其实是误报），也读不到 App 的偏好与权限。
            // 放在常规自检前面：后面不少项都依赖桥能通。
            final String bridge = hc.selfCheckBridgeChain();
            final String report = crash + health + bridge + hc.runSelfTest();
            // 自检要 10~30 秒，回来时用户很可能已经离开设置页。getActivity() 判空
            // 之后再调 requireActivity() 等于白判 —— 两次调用之间就是那个窗口，
            // 而它抛的 IllegalStateException 落在这个后台线程上就是整进程崩。
            android.app.Activity act = getActivity();
            if (act == null) return;
            act.runOnUiThread(() -> {
                if (isAdded()) showSelfTestDialog(report);
            });
        }, "dsha-selftest").start();
    }
    private void showSelfTestDialog(final String report) {
        TextView body = new TextView(requireContext());
        body.setText(report);
        body.setTypeface(android.graphics.Typeface.MONOSPACE);
        body.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11);
        body.setTextColor(requireContext().getColor(R.color.text));
        body.setTextIsSelectable(true);
        body.setPadding(dp(16), dp(8), dp(16), dp(8));
        android.widget.ScrollView scroll = new android.widget.ScrollView(requireContext());
        scroll.addView(body);
        // 弹窗高度设上限，报告长了也不会把按钮顶出屏幕
        scroll.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(420)));
        new AlertDialog.Builder(requireContext())
                .setTitle("自检 & 修补结果")
                .setView(scroll)
                .setPositiveButton("复制", (d, w) -> {
                    android.content.ClipboardManager cm = (android.content.ClipboardManager)
                            requireContext().getSystemService(android.content.Context.CLIPBOARD_SERVICE);
                    if (cm != null) {
                        cm.setPrimaryClip(android.content.ClipData.newPlainText("DSHA 自检 & 修补", report));
                        DshaSnack.show(SettingsFragment.this, "已复制自检 & 修补结果");
                    }
                })
                .setNegativeButton("关闭", null)
                .show();
    }

    private LinearLayout buildRow(final int index) {
        TabOption opt = TAB_OPTIONS[index];
        LinearLayout row = new LinearLayout(requireContext());
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(15), dp(15), dp(15), dp(15));
        TypedValue tv = new TypedValue();
        requireContext().getTheme().resolveAttribute(android.R.attr.selectableItemBackground, tv, true);
        row.setBackgroundResource(tv.resourceId);
        row.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        LinearLayout body = new LinearLayout(requireContext());
        body.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        body.setLayoutParams(blp);

        TextView title = new TextView(requireContext());
        title.setText(opt.title);
        title.setTextSize(14);
        title.setTextColor(requireContext().getColor(R.color.text));
        title.setTypeface(title.getTypeface(), android.graphics.Typeface.BOLD);

        TextView sub = new TextView(requireContext());
        sub.setText(opt.sub);
        sub.setTextSize(12);
        sub.setTextColor(requireContext().getColor(R.color.text_muted));
        LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        slp.topMargin = dp(2);
        sub.setLayoutParams(slp);

        body.addView(title);
        body.addView(sub);

        TextView chev = new TextView(requireContext());
        chev.setText("›");
        chev.setTextSize(18);
        // 用主题槽而不是写死的 @color —— 写死的话换配色（樱花 / 壁纸取色）时这个箭头不跟着变。
        android.util.TypedValue muted = new android.util.TypedValue();
        chev.setTextColor(requireContext().getTheme().resolveAttribute(R.attr.dshaTextMuted, muted, true)
                ? muted.data : requireContext().getColor(R.color.text_muted));

        row.addView(body);
        row.addView(chev);
        row.setOnClickListener(v -> {
            androidx.fragment.app.Fragment target = opt.factory.get();
            requireActivity().getSupportFragmentManager().beginTransaction()
                    // crossfade：淡入淡出**完整重叠**，任何一帧都有内容盖着背景图。
                    // 不用 MaterialSharedAxis —— 它是「旧页缩小淡出、新页放大淡入」，
                    // 中间那段间隙会露出背景图原图（底衬是透明的，见图层契约 ②）。
                    // 四个参数依次是 enter / exit / popEnter / popExit，
                    // 后两个不填按返回键就没有动效。
                    .setCustomAnimations(R.anim.dsha_fade_in, R.anim.dsha_fade_out,
                            R.anim.dsha_fade_in, R.anim.dsha_fade_out)
                    .setReorderingAllowed(true)
                    .replace(R.id.fragment_container, target)
                    .addToBackStack("settings")
                    .commit();
        });
        return row;
    }

    private void checkUpdate() {
        DshaSnack.show(SettingsFragment.this, "正在检查更新…");
        // 网络请求最长 16 秒（两个源各 8 秒），这段时间里用户很可能已经离开设置页。
        // 所以凡是需要 Fragment 还 attached 的东西，全在这里（UI 线程、必然 attached）
        // 先取好：requireContext() 一旦在后台线程或延迟到 UI 回调里执行，
        // 抛的是 IllegalStateException —— 在后台线程上抛就是整个进程崩。
        final android.content.Context appCtx = requireContext().getApplicationContext();
        String v;
        try {
            v = appCtx.getPackageManager().getPackageInfo(appCtx.getPackageName(), 0).versionName;
        } catch (Exception e) {
            v = "?";
        }
        final String cur = v;
        new Thread(() -> {
            String tag = UpdateChecker.checkLatestVersion();
            // 原来这里是 if (!isAdded()) return; requireActivity()... ——
            // 两句之间仍有窗口，且 lambda 内部又用了三次 requireContext()。
            android.app.Activity act = getActivity();
            if (act == null || !isAdded()) return;
            act.runOnUiThread(() -> {
                if (tag == null) {
                    Toast.makeText(appCtx, "检查失败，请稍后再试", Toast.LENGTH_SHORT).show();
                    return;
                }
                if (!UpdateChecker.isNewer(tag, cur)) {
                    Toast.makeText(appCtx, "当前 v" + cur + " 已是最新", Toast.LENGTH_SHORT).show();
                    return;
                }
                // 更新前自动存档：检测到新版先静默备份一次（同一版本只备份一次）
                c.backupBeforeUpdate(tag);
                // 弹窗必须用 Activity context，而这一刻可能已经 detach 了
                android.app.Activity a2 = getActivity();
                if (a2 == null || a2.isFinishing() || !isAdded()) {
                    Toast.makeText(appCtx, "发现新版本 " + tag, Toast.LENGTH_LONG).show();
                    return;
                }
                new AlertDialog.Builder(a2)
                        .setTitle("发现新版本 " + tag)
                        .setMessage("当前版本 v" + cur + "\n是否前往下载？")
                        .setPositiveButton("更新", (d, w) -> AboutDialog.openBrowser(
                                appCtx, "https://github.com/qiannianhuanxiang/DSHA/releases/latest"))
                        .setNegativeButton("取消", null)
                        .show();
            });
        }).start();
    }

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }

    private static final class TabOption {
        final String title;
        final String sub;
        final Supplier<Fragment> factory;

        TabOption(String title, String sub, Supplier<Fragment> factory) {
            this.title = title;
            this.sub = sub;
            this.factory = factory;
        }
    }
}
