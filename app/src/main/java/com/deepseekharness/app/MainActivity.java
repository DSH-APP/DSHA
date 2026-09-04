package com.deepseekharness.app;

import android.Manifest;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.PowerManager;
import android.provider.Settings;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.CheckBox;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.fragment.app.Fragment;

import com.google.android.material.bottomnavigation.BottomNavigationView;

public class MainActivity extends AppCompatActivity {

    /** 当前前台 Activity（HttpShellService 用它弹确认框）；null = 不在前台 */
    public static volatile MainActivity current = null;

    /** 自动恢复弹窗的「手动选择」出口。分区存储下 SAF 是读取「别的安装写进
     *  Download/DSHA 的备份」唯一不需要权限、也一定能成的办法（issue #22）。
     *  注册必须发生在 Activity 进入 STARTED 之前，所以放在字段初始化里。 */
    private final androidx.activity.result.ActivityResultLauncher<String[]> backupPicker =
            registerForActivityResult(
                    new androidx.activity.result.contract.ActivityResultContracts.OpenDocument(),
                    uri -> {
                        if (uri != null) {
                            HarnessController.get(this).restorePickedUri(this, uri);
                        }
                    });

    /** 供 HarnessController 的恢复弹窗调用：打开系统文件选择器挑备份包。 */
    public void pickBackupForRestore() {
        try {
            // MediaStore 给 tar.gz 记的 MIME 各家 ROM 不一，多给几个并兜 */*，
            // 否则用户会看到「没有可选文件」。
            backupPicker.launch(new String[]{"application/gzip", "application/x-gzip",
                    "application/x-tar", "application/octet-stream", "*/*"});
        } catch (Throwable e) {
            Toast.makeText(this, "无法打开文件选择器：" + e, Toast.LENGTH_LONG).show();
        }
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        DshaTheme.apply(this);          // 必须在 super 之前：晚了窗口属性已经按旧主题解析完
        // 背景图这里刻意**不**走 window 层：主界面把它放进 BlurTarget 里的 ImageView
        // （见 setupGlass），否则玻璃栏糊不到它。window 背景仍留给其他三个 Activity 用。
        super.onCreate(savedInstanceState);
        // 崩溃捕获已统一在 DshaApp 安装一次（防止 Activity 重建导致重复/覆盖 handler）

        // 首次启动进入引导页
        // 静态标志跨 Activity 存活：若上个 Activity 在恢复弹窗显示期间被销毁，
        // dismiss 回调不会触发，标志会永久卡在 true，权限弹窗从此再也不弹。
        HarnessController.restoreFlowActive = false;
        SharedPreferences prefs = getSharedPreferences("deepseekharness", MODE_PRIVATE);
        if (!prefs.getBoolean("welcomed", false)) {
            startActivity(new Intent(this, WelcomeActivity.class));
            finish();
            return;
        }

        if (!getIntent().getBooleanExtra("skip_extract", false)) {
            ProotBootstrap proot = new ProotBootstrap(this);
            if (!proot.isOfflineExtracted()) {
                startActivity(new Intent(this, ExtractActivity.class));
                finish();
                return;
            }
        }

        setContentView(R.layout.activity_main);

        // 升级自动备份（幂等；rootfs 未就绪时内部自动跳过）
        HarnessController.get(this).upgradeGuard();
        // 每启动 5 次自动备份一次（固定名覆盖；与手动备份独立）
        HarnessController.get(this).maybeAutoBackupOnLaunch();
        // 检测 dsh 新版本 → 自动重跑⑥（安全守卫/补丁/内置插件适配新版本）
        HarnessController.get(this).maybeAutoReinstallGuardOnDshUpdate();
        // ADB 链路自动体检+自愈（打开即用：脚本/依赖/包装命令/连接，缺啥修啥）
        HarnessController.get(this).maybeAdbSelfHeal();
        // dsh 子包依赖完整性自愈（npmmirror 镜像元数据不一致导致 Cannot find module）
        HarnessController.get(this).maybeHealDshDeps();
        // write 工具悬空链接自愈（proot l2s 与 dsh 的 link 发布冲突；幂等秒回）
        HarnessController.get(this).maybeFixFsWrite();
        // 空 pets 目录清理（deepseek-pet 插件空目录会崩插件树）
        HarnessController.get(this).maybeCleanEmptyPets();
        // 会话损坏自愈（中途强杀导致 SQLite 写一半 → 历史加载失败）
        HarnessController.get(this).maybeHealSessionCorruption();
        // 步骤⑥版本对比：内置插件/补丁有更新时自动重跑（无需手动重装⑥）
        HarnessController.get(this).maybeRefreshStep6();
        // 内置插件注册自愈：⑥ 可能跑在 profile 生成之前（那时注册会被静默跳过），
        // 所以每次开 App 都校验一遍「设备引导插件是否真的注册进 bundles」
        HarnessController.get(this).ensureBuiltinPluginsReady();
        // 崩溃自愈提示：上次异常退出时读 crash.log 告知原因（不阻塞使用）
        showCrashRecoveryNotice();
        // 全新环境可恢复检测。走到这里 rootfs 一定已解压（skip_extract=true 来自
        // ExtractActivity，否则上面 isOfflineExtracted() 不通过就已跳走），所以不再限定
        // skip_extract —— 首启那次弹窗被用户划掉/进程被杀后，下次开 App 还有机会补上
        // （issue #22）。方法内部只在 .dsh 尚无用户数据时才弹，不会覆盖已有数据。
        HarnessController.get(this).maybePromptRestore(this);
        // 上次重解压没走完 → 数据保护目录还在，问用户要不要把数据恢复回来。
        // **必须排在升级提示之前**：数据没归位就再提示重解压，只会把同一个失败重复一遍。
        HarnessController.get(this).maybeOfferPreservedDataRecovery(this);
        // 离线包升级感知：APK 内置新离线包 → 提示重解压（数据自动保留）。
        // 放外面：正常启动（rootfs 已解压）也要检测，方法内部自带
        // isOfflineExtracted() 保护（首启未解压时静默）。
        HarnessController.get(this).maybeOfferOfflineUpgrade(this);

        requestPermissions();
        requestBatteryOptimization();
        maybeShowBackupReminder();
        maybeCheckUpdate();
        // ADB 默认关。只有用户在配置里勾选后才会拉设备桥。
        DeviceBridgeService.apply(this);

        BottomNavigationView nav = findViewById(R.id.bottom_nav);
        View about = findViewById(R.id.btn_about);
        if (about != null) {
            about.setOnClickListener(v -> AboutDialog.show(this));
        }

        final androidx.viewpager2.widget.ViewPager2 pager = findViewById(R.id.pager);
        pager.setAdapter(new androidx.viewpager2.adapter.FragmentStateAdapter(this) {
            @Override
            public int getItemCount() {
                return 4;
            }

            @NonNull
            @Override
            public Fragment createFragment(int pos) {
                switch (pos) {
                    case 0:
                        return new LaunchFragment();
                    case 1:
                        return new PluginFragment();
                    case 2:
                        return new SettingsFragment();
                    default:
                        // 两套终端并存：默认 PTY 那套（跑得了 vim / htop / tmux），页内点「简易」
                        // 可以退回旧的 TextView 版本 —— 新终端万一在某些机型上出问题，
                        // 用户不至于连命令行都没了。选择记在 PtyTerminalFragment.KEY_PTY。
                        return PtyTerminalFragment.preferred(MainActivity.this)
                                ? new PtyTerminalFragment() : new TerminalFragment();
                }
            }
        });
        // 手势滑动和点底栏最终都汇到这里，标题、图标、底栏选中态、插件页输入框都在这更新。
        pager.registerOnPageChangeCallback(
                new androidx.viewpager2.widget.ViewPager2.OnPageChangeCallback() {
                    @Override
                    public void onPageSelected(int position) {
                        onTabShown(position);
                    }
                });
        nav.setOnItemSelectedListener(item -> {
            // 在二级页时点底栏：先退出二级页，再切 tab。
            closeSecondary();
            // smoothScroll=true 才是真平移（跟手那套滚动的程序化版本）。
            pager.setCurrentItem(tabIndex(item.getItemId()), true);
            return true;
        });
        setupGlass();
        // 二级页退栈时收尾：内容容器淡出隐藏、pager 淡回来、面包屑的「· 子标题」淡出。
        getSupportFragmentManager().addOnBackStackChangedListener(() -> {
            if (getSupportFragmentManager().getBackStackEntryCount() != 0) return;
            final View sec = findViewById(R.id.fragment_container);
            View pg = findViewById(R.id.pager);
            if (sec != null) {
                sec.animate().alpha(0f).setDuration(160)
                        .withEndAction(() -> sec.setVisibility(View.GONE)).start();
            }
            if (pg != null) pg.animate().alpha(1f).setDuration(160).start();
            clearBreadcrumb(true);
        });
        // 卡片透明度要覆盖**所有层级**的 Fragment。原先只在 switchFragment 里调一次，
        // 于是二级页面全漏了 —— 工作区、备份恢复、终端子页、市场详情，一共五处
        // beginTransaction 分散在四个 Fragment 里。逐处去补必然再漏（以后新增页面也一样），
        // 所以挂在 FragmentManager 的生命周期回调上：View 一创建就套。
        // 第二个参数 true = 连子 FragmentManager 里的 Fragment 一起管。
        getSupportFragmentManager().registerFragmentLifecycleCallbacks(
                new androidx.fragment.app.FragmentManager.FragmentLifecycleCallbacks() {
                    @Override
                    public void onFragmentViewCreated(
                            androidx.fragment.app.FragmentManager fm,
                            androidx.fragment.app.Fragment f, View v, Bundle s) {
                        // 作用在 decorView 而不是这个 Fragment 的 v —— 顶栏、底栏、以及它们里面的
                        // logo 圆和「关于」按钮都不属于任何 Fragment，只处理 v 的话那些地方永远
                        // 不透明（就是左上角那个圆一直很实的原因）。
                        //
                        // **同步**应用，不要 post：这个回调发生在 View 树建好、首帧绘制之前，
                        // 这时候改 alpha 用户看不到过程。post 到下一帧的话，第一帧是不透明的、
                        // 第二帧才变透明 —— 那就是「打开二级页面会闪一下」的原因。
                        DshaGlass.apply(getWindow().getDecorView());
                        padForBars(f, v);
                    }
                }, true);
        if (savedInstanceState == null) {
            // 长按启动器图标的快捷方式会带 goto 进来，指定落到哪个页面。
            // 认不出的值就走默认（启动页）—— 外部传进来的东西不能直接当 id 用。
            String go = getIntent() == null ? null : getIntent().getStringExtra("goto");
            int start = R.id.nav_launch;
            if ("terminal".equals(go)) start = R.id.nav_terminal;
            else if ("plugins".equals(go)) start = R.id.nav_plugins;
            nav.setSelectedItemId(start);
        }
    }

    /** 底栏四个页面的顺序，和 ViewPager2 的 position 一一对应：启动 0、插件 1、设置 2、终端 3。 */
    private static int tabIndex(int id) {
        if (id == R.id.nav_launch) return 0;
        if (id == R.id.nav_plugins) return 1;
        if (id == R.id.nav_terminal) return 3;
        return 2;
    }

    /** 当前所在的 tab。 */
    private int curTab = 0;

    private static String titleOf(int pos) {
        switch (pos) {
            case 0: return "启动";
            case 1: return "市场";
            case 3: return "终端";
            default: return "设置";
        }
    }

    private static int iconOf(int pos) {
        switch (pos) {
            case 0: return R.drawable.ic_launch;
            case 1: return R.drawable.ic_plugins;
            case 3: return R.drawable.ic_terminal;
            default: return R.drawable.ic_settings;
        }
    }

    private static int navIdOf(int pos) {
        switch (pos) {
            case 0: return R.id.nav_launch;
            case 1: return R.id.nav_plugins;
            case 3: return R.id.nav_terminal;
            default: return R.id.nav_settings;
        }
    }

    /** 某个 tab 成为当前页时的收尾。手势滑动和点底栏都会走到这里。 */
    private void onTabShown(int pos) {
        curTab = pos;
        // GitHub 链接输入框只在插件页有意义：切走就藏起来并清空，
        // 否则它会顶着别的页面的标题栏，还留着上次的内容。
        android.widget.EditText ghIn = findViewById(R.id.appbar_github_input);
        View spacer = findViewById(R.id.appbar_spacer);
        if (ghIn != null) {
            boolean onPlugins = pos == 1;
            ghIn.setVisibility(onPlugins ? View.VISIBLE : View.GONE);
            if (spacer != null) spacer.setVisibility(onPlugins ? View.GONE : View.VISIBLE);
            if (!onPlugins) ghIn.setText("");
        }
        // 底栏选中态跟上手势滑动。用 setChecked 而不是 setSelectedItemId ——
        // 后者会再触发一次 OnItemSelectedListener，那里又去 setCurrentItem，兜成一个圈。
        BottomNavigationView nav = findViewById(R.id.bottom_nav);
        if (nav != null) {
            android.view.MenuItem mi = nav.getMenu().findItem(navIdOf(pos));
            if (mi != null && !mi.isChecked()) mi.setChecked(true);
        }
        setAppTitle(titleOf(pos), iconOf(pos));
        clearBreadcrumb(false);
    }

    /** 面包屑的「· 子标题」淡入。 */
    private void setBreadcrumb(String sub) {
        android.widget.TextView dot = findViewById(R.id.app_title_dot);
        android.widget.TextView s = findViewById(R.id.app_title_sub);
        if (s != null) {
            s.setText(sub);
            s.animate().alpha(1f).setDuration(180).start();
        }
        if (dot != null) dot.animate().alpha(1f).setDuration(180).start();
    }

    /** 面包屑淡出。切 tab 时不带动画（那是瞬间换页，渐变反而拖影）。 */
    private void clearBreadcrumb(boolean animated) {
        android.widget.TextView dot = findViewById(R.id.app_title_dot);
        android.widget.TextView s = findViewById(R.id.app_title_sub);
        for (View v : new View[]{dot, s}) {
            if (v == null) continue;
            if (animated) v.animate().alpha(0f).setDuration(160).start();
            else { v.animate().cancel(); v.setAlpha(0f); }
        }
    }

    /** 「标题飞到面包屑位置」：设置页点进二级页时的转场。
     *
     *  <p>效果：pager 淡出、被点那行的标题文字平移放大到顶栏「设置」后面、
     *  「·」与它一起淡入，然后二级页元素淡入。返回时「· 子标题」淡出消失。
     *
     *  <p><b>为什么自己做而不用 Fragment 的共享元素转场。</b>共享元素要求源和目标都在
     *  参与这次 transaction 的 fragment 里，而顶栏标题属于 Activity 的布局 ——
     *  addSharedElement 找不到它。把顶栏搬进每个 fragment 是更大的改动（四页各复制一份栏、
     *  还要重接 BlurView 的取样层级），不值得。所以用替身 TextView 飞过去。
     *
     *  <p><b>上一版为什么会闪。</b>它把内容容器整体 animate 到 alpha=0，然后在飞行结束的
     *  withEndAction 里立刻 setAlpha(1f) —— 那一刻旧页还在容器里（replace 还没提交），
     *  于是旧页整块瞬间全亮。这一版不再碰任何已有 view 的 alpha 恢复：pager 淡出后就一直
     *  是 0，直到退栈时才淡回来；新页在自己的容器里从 0 淡入。
     */
    void flyTitleTo(final android.widget.TextView src, final String sub, final Fragment next) {
        final android.widget.TextView dst = findViewById(R.id.app_title_sub);
        final android.widget.FrameLayout root = findViewById(android.R.id.content);
        if (src == null || dst == null || root == null) {
            setBreadcrumb(sub);
            openSecondary(next);
            return;
        }
        // 先把文字放进去（alpha 还是 0，看不见），让它参与一次布局 ——
        // 否则拿到的是上一次的宽度，终点位置会偏。
        dst.setText(sub);
        dst.post(() -> {
            int[] a = new int[2], b = new int[2], r = new int[2];
            src.getLocationInWindow(a);
            dst.getLocationInWindow(b);
            root.getLocationInWindow(r);

            final android.widget.TextView ghost = new android.widget.TextView(this);
            ghost.setText(src.getText());
            ghost.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, src.getTextSize());
            ghost.setTextColor(src.getCurrentTextColor());
            ghost.setTypeface(src.getTypeface());
            ghost.setMaxLines(1);
            android.widget.FrameLayout.LayoutParams lp =
                    new android.widget.FrameLayout.LayoutParams(
                            android.widget.FrameLayout.LayoutParams.WRAP_CONTENT,
                            android.widget.FrameLayout.LayoutParams.WRAP_CONTENT);
            lp.leftMargin = a[0] - r[0];
            lp.topMargin = a[1] - r[1];
            ghost.setLayoutParams(lp);
            root.addView(ghost);

            // 目标字号 / 源字号 = 放大倍数。pivot 放左上角，缩放才不会把位移带偏。
            final float scale = src.getTextSize() > 0 ? dst.getTextSize() / src.getTextSize() : 1f;
            ghost.setPivotX(0f);
            ghost.setPivotY(0f);
            final int dx = (b[0] - r[0]) - lp.leftMargin;
            final int dy = (b[1] - r[1]) - lp.topMargin
                    + (dst.getHeight() - Math.round(src.getHeight() * scale)) / 2;

            View pg = findViewById(R.id.pager);
            if (pg != null) pg.animate().alpha(0f).setDuration(180).start();
            // 「·」跟着飞行一起淡入，落地时刚好显形
            android.widget.TextView dot = findViewById(R.id.app_title_dot);
            if (dot != null) dot.animate().alpha(1f).setDuration(240).start();

            ghost.animate()
                    .translationX(dx).translationY(dy)
                    .scaleX(scale).scaleY(scale)
                    .setDuration(280)
                    .setInterpolator(new android.view.animation.PathInterpolator(0.2f, 0f, 0f, 1f))
                    .withEndAction(() -> {
                        root.removeView(ghost);
                        dst.setAlpha(1f);
                        openSecondary(next);
                    })
                    .start();
        });
    }

    /** 打开二级页：装进叠在 pager 上的 fragment_container，并淡入。 */
    void openSecondary(Fragment next) {
        final View sec = findViewById(R.id.fragment_container);
        if (sec != null) {
            sec.setVisibility(View.VISIBLE);
            sec.setAlpha(0f);
            sec.animate().alpha(1f).setDuration(180).start();
        }
        View pg = findViewById(R.id.pager);
        if (pg != null && pg.getAlpha() > 0f) pg.animate().alpha(0f).setDuration(180).start();
        getSupportFragmentManager().beginTransaction()
                .setReorderingAllowed(true)
                .replace(R.id.fragment_container, next)
                .addToBackStack("secondary")
                .commit();
    }

    /** 关掉二级页（点底栏时用）。没有二级页就什么都不做。 */
    void closeSecondary() {
        if (getSupportFragmentManager().getBackStackEntryCount() == 0) return;
        getSupportFragmentManager().popBackStack(null,
                androidx.fragment.app.FragmentManager.POP_BACK_STACK_INCLUSIVE);
    }

    /** 给 pager 里的页面补上避开顶栏底栏的 padding。
     *
     *  <p>padding 不能加在 ViewPager2 上：那样 item 的高度会被限制在两条栏之间，
     *  内容再也穿不到半透明的栏后面 —— 玻璃栏糊的就是滚动内容，穿不过去这效果就没了。
     *  所以加在每个页面自己的 root 上，并 clipToPadding=false 放开绘制。
     *
     *  <p>二级页不处理：它们在 fragment_container 里，那个容器自带 padding。
     *  判据是 {@code f.getId()} —— fragment 的 id 等于它所在容器的 id，
     *  用 replace(R.id.fragment_container, …) 装进去的就等于那个 id，
     *  而 FragmentStateAdapter 给 pager 建的容器是另一个动态 id。 */
    private void padForBars(Fragment f, View v) {
        if (v == null || f == null || f.getId() == R.id.fragment_container) return;
        int top = getResources().getDimensionPixelSize(R.dimen.app_bar_height);
        int bot = getResources().getDimensionPixelSize(R.dimen.bottom_nav_height);
        // 栏是悬浮的圆角块，占位 = 它自己的 margin + 栏高；再留一份 margin 当内容与栏的间距。
        int m = getResources().getDimensionPixelSize(R.dimen.bar_margin);
        v.setPadding(v.getPaddingLeft(), v.getPaddingTop() + top + m * 2,
                v.getPaddingRight(), v.getPaddingBottom() + bot + m * 2);
        if (v instanceof android.view.ViewGroup) {
            ((android.view.ViewGroup) v).setClipToPadding(false);
        }
    }

    /** 显示/隐藏底部导航栏（WebView 全屏时隐藏） */
    public void setBottomNavVisible(boolean visible) {
        BottomNavigationView nav = findViewById(R.id.bottom_nav);
        if (nav != null) nav.setVisibility(visible ? View.VISIBLE : View.GONE);
        // 藏的必须是外层那个 BlurView：内层 app_bar 设 GONE 的话，
        // 玻璃容器还占着 56dp，界面上就多出一条空白。
        View bar = findViewById(R.id.top_glass);
        if (bar != null) bar.setVisibility(visible ? View.VISIBLE : View.GONE);
    }

    /** 顶栏与底栏的真玻璃。
     *
     *  <p>上一版是「半透明色块 + 把整张背景图糊一遍」，差在两点：模糊是全局静态的
     *  （不随内容滚动变化），而色块压在清晰内容上没有层次。BlurView 做的是
     *  「只糊这块区域背后的东西、并且实时跟着变」—— 也就是 CSS backdrop-filter 的行为，
     *  API 31+ 由系统 RenderThread 走 RenderEffect 完成，快照零开销。
     *
     *  <p>{@code setFrameClearDrawable} 是必须的：内容区有大片透明，不先铺一层不透明底
     *  会得到"半透明的模糊"，看着发灰。 */
    private void setupGlass() {
        try {
            DshaBackground.applyTo(findViewById(R.id.app_background));
            if (!DshaGlass.enabled(this)) return;    // 玻璃没开：栏保持不透明，连快照都不记
            eightbitlab.com.blurview.BlurTarget target = findViewById(R.id.blur_target);
            if (target == null) return;

            int overlay = DshaGlass.overlayColor(this, 0.82f);
            int[] ids = {R.id.top_glass, R.id.bottom_glass};
            for (int id : ids) {
                eightbitlab.com.blurview.BlurView bv = findViewById(id);
                if (bv == null) continue;
                // 圆角浮块：透明填充 + 圆角 + 四周描边（bg_bar_glass），
                // 再 clipToOutline 让 BlurView 的实时模糊按同一个圆角裁掉四个角。
                // 上一版只改了描边、没裁材质，于是「描边是圆的、模糊是方的」——
                // 反馈说的「材质作用范围为矩形」就是这个。outline 从 background 取，
                // 所以那张图必须是单个带 corners 的 shape（LayerDrawable 的 outline 不可靠）。
                bv.setBackgroundResource(R.drawable.bg_bar_glass);
                bv.setOutlineProvider(android.view.ViewOutlineProvider.BACKGROUND);
                bv.setClipToOutline(true);
                // scaleFactor 5：栏是常驻的，每帧都要重算，降采样狠一点省 GPU；
                // 反正模糊本身就不需要精确。半径与噪点由用户在外观面板里调。
                bv.setupWith(target, 5f, DshaGlass.noise(this))
                        .setFrameClearDrawable(getWindow().getDecorView().getBackground())
                        .setBlurRadius(DshaGlass.radius(this))
                        .setOverlayColor(overlay);
            }
        } catch (Throwable t) {
            android.util.Log.w("DSHA", "玻璃栏初始化失败（退化成普通栏，功能不受影响）: " + t);
        }
    }


    /** 顶栏：标题 + 当前模块图标。
     *
     *  <p>图标原先写死在布局里（{@code src="@drawable/ic_terminal"}），所以不管在哪一页
     *  都显示终端那个；着色用的是 ImageView 的 {@code android:tint="?attr/…"}，
     *  那个属性引主题属性并不保证解析，换主题时颜色也不跟着走。两件事一起改成代码控制。 */
    private void setAppTitle(String title, int iconRes) {
        android.widget.TextView t = findViewById(R.id.app_title);
        if (t != null) t.setText(title);
        android.widget.ImageView ic = findViewById(R.id.app_bar_icon);
        if (ic != null) {
            ic.setImageResource(iconRes);
            android.util.TypedValue tv = new android.util.TypedValue();
            if (getTheme().resolveAttribute(R.attr.dshaPrimary, tv, true)) {
                int c = tv.resourceId != 0
                        ? androidx.core.content.ContextCompat.getColor(this, tv.resourceId)
                        : tv.data;
                ic.setColorFilter(c);
            }
        }
    }

    /** 崩溃自愈提示：上次有未处理崩溃时，读 crash.log 首条摘要告知用户（不阻塞，仅提示） */
    private void showCrashRecoveryNotice() {
        try {
            // 同一份 crash.log 只提醒一次（24h 去重，不删除日志本体，保留取证）
            SharedPreferences prefs = getSharedPreferences("deepseekharness", MODE_PRIVATE);
            final java.io.File f = new java.io.File(getFilesDir(), "crash.log");
            if (!f.isFile() || f.length() == 0) return;
            if (System.currentTimeMillis() - prefs.getLong("crash_notice_shown", 0) < 24L * 3600 * 1000) {
                return;
            }
            prefs.edit().putLong("crash_notice_shown", System.currentTimeMillis()).apply();
            String all = new String(java.nio.file.Files.readAllBytes(f.toPath()),
                    java.nio.charset.StandardCharsets.UTF_8);
            if (all.trim().isEmpty()) return;
            // 只取最后一条崩溃的异常类型/消息（首个堆栈帧）
            String[] blocks = all.split("===== ");
            String last = blocks[blocks.length - 1];
            String summary = "";
            for (String line : last.split("\n")) {
                String t = line.trim();
                if (t.startsWith("java.") || t.startsWith("android.") || t.startsWith("kotlin.")) {
                    summary = t.length() > 180 ? t.substring(0, 180) : t;
                    break;
                }
            }
            final String info = summary.isEmpty() ? "发生异常" : summary;
            // 不影响提示：已读内容归档到 crash.log.prev（本轮 crash.log 保留供反复查看）
            try {
                java.io.File prev = new java.io.File(getFilesDir(), "crash.log.prev");
                //noinspection ResultOfMethodCallIgnored
                prev.delete();
                //noinspection ResultOfMethodCallIgnored
                f.renameTo(prev);
            } catch (Throwable ignored) {
            }
            // 延迟 1.2 秒再弹：等主界面画完，不然对话框会和启动动画抢焦点。
            // 但延迟期间 Activity 可能已经被退掉（崩溃恢复场景下用户往往会立刻再退一次），
            // 那时 new AlertDialog.Builder(this).show() 会抛 BadTokenException，
            // 用户看到的是「刚从崩溃恢复又崩一次」。
            new android.os.Handler(android.os.Looper.getMainLooper()).postDelayed(() -> {
                if (isFinishing() || isDestroyed()) return;
                new AlertDialog.Builder(this)
                    .setTitle("上次异常退出")
                    .setMessage("DSHA 上次运行发生了未处理异常，已自动恢复。\n\n" + info
                            + "\n\n如果问题反复出现，请把内置终端里 `cat /data/data/com.dsh.client/files/crash.log.prev`（或 crash.log）的内容发给开发者。")
                    .setPositiveButton("知道了", null)
                    .show();
            }, 1200);
        } catch (Throwable ignored) {
        }
    }

    /** 自动申请所需权限：通知（前台服务需要）+ 电池优化白名单（保活） */
    private void requestPermissions() {
        if (Build.VERSION.SDK_INT >= 33
                && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, 100);
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions,
                                           @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == 100 && grantResults.length > 0
                && grantResults[0] != PackageManager.PERMISSION_GRANTED) {
            // 用户拒绝通知权限：ADB 配对卡/任务完成提醒无法显示，给一次引导提示
            android.widget.Toast.makeText(this,
                    "未授予通知权限：ADB 配对卡片与任务完成提醒将不可用。\n可到系统设置 → 应用 → DSHA → 通知 开启。",
                    android.widget.Toast.LENGTH_LONG).show();
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        // 用户回到 App 时催一次 ADB 探测：这一刻往往正要用它（内部有防抖与单飞）
        try {
            if (DeviceBridgeService.isAdbEnabled(this)) {
                DeviceBridgeService.kickNow(this, "回到 App");
            }
        } catch (Throwable ignored) {
        }
        current = this;
        TaskNotifier.appInForeground = true;
        // 从「所有文件访问」设置页返回时能立刻发现已授予 → 补跑一次数据迁移
        maybeRequestAllFilesAccess();
    }

    /** 申请「所有文件访问」（All Files Access）。
     *
     *  为什么需要：会话/设置/附件要迁到 /sdcard/Documents/dshdata 才能做到
     *  **卸载重装不丢**。而 Android 11+ 下没有这个权限就写不进公开目录，
     *  迁移脚本只会静默跳过 —— 用户以为数据安全了，其实还在私有目录里，
     *  一卸载全没。
     *
     *  这是特殊权限，不能用运行时弹窗授予，必须跳系统设置页由用户手动开。
     *  所以：说清理由 → 跳设置页 → 回来后自动补跑迁移。
     *  用户拒绝也不纠缠（只问一次），但自检里会持续提示风险。 */
    /** 供恢复流程结束后调用：那时才轮到我们问权限。 */
    void recheckAllFilesAccess() {
        maybeRequestAllFilesAccess();
    }

    private void maybeRequestAllFilesAccess() {
        try {
            if (Build.VERSION.SDK_INT < 30) {
                // 「老系统本来就能直写公共目录」这句话只对了一半：WRITE_EXTERNAL_STORAGE
                // 从 API 23 起就是运行时权限，不申请就是没有；Android 10 还额外受分区存储
                // 限制，靠清单里的 requestLegacyExternalStorage 退回旧行为。
                // 不申请的后果是「手机存储」工作区挂得上、进去却是空的或者 Permission denied。
                // 这里用系统弹窗一次问完（不像 11+ 得跳设置页手动拉开关）。
                SharedPreferences sp = getSharedPreferences("deepseekharness", MODE_PRIVATE);
                if (!sp.getBoolean("asked_legacy_storage", false)
                        && checkSelfPermission(android.Manifest.permission.WRITE_EXTERNAL_STORAGE)
                        != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                    sp.edit().putBoolean("asked_legacy_storage", true).apply();
                    requestPermissions(new String[]{
                            android.Manifest.permission.WRITE_EXTERNAL_STORAGE,
                            android.Manifest.permission.READ_EXTERNAL_STORAGE}, 4201);
                }
                return;
            }
            // 恢复弹窗优先：它和我们要的是同一个权限，用户刚重装时恢复数据更紧急。
            // 直接 return 而不标记 asked_all_files —— 否则「只问一次」的额度
            // 会被这次让路白白用掉，等恢复流程结束就再也不问了。
            if (HarnessController.restoreFlowActive) return;
            SharedPreferences prefs = getSharedPreferences("deepseekharness", MODE_PRIVATE);
            if (android.os.Environment.isExternalStorageManager()) {
                // 已授予：如果之前因为没权限跳过过迁移，这里补跑一次（幂等、失败无感）
                if (!prefs.getBoolean("public_data_migrated", false)) {
                    prefs.edit().putBoolean("public_data_migrated", true).apply();
                    final HarnessController hc = HarnessController.get(this);
                    new Thread(() -> {
                        try {
                            hc.migratePublicDataNow();
                        } catch (Throwable ignored) {
                        }
                    }, "dsha-migrate-after-grant").start();
                    // 授权后备份就能被枚举到了（#32 实测：授权前 0 个、授权后 14 个全可读）。
                    // 之前因为看不见而给出的「手动选择」提示，现在可以换成真正的恢复建议。
                    try {
                        prefs.edit().remove("restore_prompt_declined").apply();
                        hc.maybePromptRestore(this);
                    } catch (Throwable ignored) {
                    }
                }
                return;
            }
            // 未授予且已经问过 → 不再打扰（自检里仍会报「卸载会丢数据」）
            if (prefs.getBoolean("asked_all_files", false)) return;
            // 正在结束的 Activity 上 show() 会抛 BadTokenException（本项目踩过一次）
            if (isFinishing() || isDestroyed()) return;
            prefs.edit().putBoolean("asked_all_files", true).apply();
            new androidx.appcompat.app.AlertDialog.Builder(this)
                    .setTitle("让对话数据卸载重装不丢")
                    .setMessage("需要「所有文件访问」权限，把会话、设置、附件存到\n"
                            + "内部存储/Documents/dshdata\n\n"
                            + "· 卸载 App 或换机重装后数据仍在\n"
                            + "· 文件管理器里可以直接看到和备份\n"
                            + "· API Key 不会存进去（仍留在 App 私有区并加密）\n\n"
                            + "不开也能正常使用，但数据只存在 App 私有目录里，卸载即丢失。")
                    .setPositiveButton("去开启", (d, w) -> {
                        try {
                            Intent i = new Intent(
                                    Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION);
                            i.setData(Uri.parse("package:" + getPackageName()));
                            startActivity(i);
                        } catch (Throwable e) {
                            // 个别 ROM 没有这个页面：退到应用详情页，用户仍能找到开关
                            try {
                                Intent i2 = new Intent(
                                        Settings.ACTION_APPLICATION_DETAILS_SETTINGS);
                                i2.setData(Uri.parse("package:" + getPackageName()));
                                startActivity(i2);
                            } catch (Throwable ignored) {
                            }
                        }
                    })
                    .setNegativeButton("以后再说", null)
                    .show();
        } catch (Throwable ignored) {
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        current = null;
        TaskNotifier.appInForeground = false;
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        // 只在「真正退出」（finishing）时关闭终端持久 shell（防进程泄漏）；
        // 旋转屏幕/配置变化触发 onDestroy 时 isFinishing()=false，保留会话（否则转屏即丢终端）
        if (isFinishing()) {
            TerminalFragment.shutdownShell();
            PtyTerminalFragment.shutdown();
        }
    }

    private void requestBatteryOptimization() {
        try {
            SharedPreferences prefs = getSharedPreferences("deepseekharness", MODE_PRIVATE);
            if (prefs.getBoolean("asked_battery", false)) return;
            PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
            if (pm != null && !pm.isIgnoringBatteryOptimizations(getPackageName())) {
                prefs.edit().putBoolean("asked_battery", true).apply();
                Intent i = new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS);
                i.setData(Uri.parse("package:" + getPackageName()));
                startActivity(i);
            }
        } catch (Exception ignored) {
        }
    }

    // ================= 检查更新 =================
    /** 后台静默检查 GitHub Releases；发现新版弹窗（取消 = 本次忽略该版本） */
    private void maybeCheckUpdate() {
        final SharedPreferences prefs = getSharedPreferences("deepseekharness", MODE_PRIVATE);
        if (!prefs.getBoolean("check_update", true)) return;
        final String ignored = prefs.getString("ignored_version", "");
        final String current;
        try {
            current = getPackageManager().getPackageInfo(getPackageName(), 0).versionName;
        } catch (Exception e) {
            return;
        }
        new Thread(() -> {
            String tag = UpdateChecker.checkLatestVersion();
            boolean apkNewer = tag != null && !tag.equals(ignored)
                    && UpdateChecker.isNewer(tag, current);
            if (!apkNewer) {
                // 应用本体已是最新 → 顺带看脚本层有没有小更新。
                // 只拉几 KB 的清单做比对，**不下载**任何脚本：更新动作留给用户手动确认，
                // 因为清单目前还没有签名，静默更新一条远程代码通道不合适。
                maybeHintRuntimeUpdate();
                return;
            }
            // 更新前自动存档：检测到新版先静默备份一次（同一版本只备份一次），
            // 防覆盖安装/下载期间出意外丢数据
            HarnessController.get(this).backupBeforeUpdate(tag);
            runOnUiThread(() -> new AlertDialog.Builder(this)
                    .setTitle("发现新版本 " + tag)
                    .setMessage("当前版本 v" + current + "\n是否前往下载？")
                    .setPositiveButton("更新", (d, w) -> AboutDialog.openBrowser(
                            this, "https://github.com/qiannianhuanxiang/DSHA/releases/latest"))
                    .setNegativeButton("取消", (d, w) -> prefs.edit()
                            .putString("ignored_version", tag).apply())
                    .show());
        }).start();
    }

    /** 脚本层有更新时温和提示一次，引导用户自己去配置页更新（不自动下载）。
     *
     *  纯手动的问题是用户根本想不起来去点；自动下载的问题是清单还没签名。
     *  折中就是这里：自动**检查**（几 KB），提示一次，动作仍由用户发起。
     *  同一批更新只提示一次 —— 用待更新文件名的指纹记住，别每次启动都烦人。 */
    private void maybeHintRuntimeUpdate() {
        try {
            RuntimeUpdater.Result probe = RuntimeUpdater.checkAndApply(
                    getApplicationContext(), HarnessController.get(this), true);
            if (probe.updated <= 0) return;
            StringBuilder key = new StringBuilder();
            for (String f : probe.changed) {
                key.append(f).append('|');
            }
            String fp = Integer.toHexString(key.toString().hashCode());
            final android.content.SharedPreferences sp = getSharedPreferences(
                    "deepseekharness", MODE_PRIVATE);
            if (fp.equals(sp.getString("runtime_hint_fp", ""))) return;   // 这批已经提过
            runOnUiThread(() -> {
                if (isFinishing() || isDestroyed()) return;
                new AlertDialog.Builder(this)
                        .setTitle("有脚本更新可用")
                        .setMessage(probe.updated + " 个脚本有新版本（合计通常只有几十 KB，"
                                + "不用重下整个应用）。\n\n"
                                + "到「配置」页点「检查脚本更新」即可查看具体改了哪些文件并更新。\n"
                                + "不更新也能正常使用。")
                        .setPositiveButton("知道了", (d, w) -> sp.edit()
                                .putString("runtime_hint_fp", fp).apply())
                        .setNegativeButton("不再提示这批", (d, w) -> sp.edit()
                                .putString("runtime_hint_fp", fp).apply())
                        .show();
            });
        } catch (Throwable e) {
            android.util.Log.w("DSHA", "脚本更新检查失败（忽略）: " + e);
        }
    }

    // ================= 备份提醒 =================
    // 提醒频率分级：默认每 6 次 → 勾选"少提醒我"依次升级为 15 / 30 / 100 次
    private static final int[] REMIND_INTERVALS = {6, 15, 30, 100};

    private void maybeShowBackupReminder() {
        SharedPreferences prefs = getSharedPreferences("deepseekharness", MODE_PRIVATE);
        int count = prefs.getInt("launch_count", 0) + 1;
        int level = prefs.getInt("reminder_level", 0);
        int last = prefs.getInt("last_reminded", 0);
        prefs.edit().putInt("launch_count", count).apply();
        int interval = REMIND_INTERVALS[Math.min(level, REMIND_INTERVALS.length - 1)];
        if (count - last < interval) return;

        View box = LayoutInflater.from(this).inflate(R.layout.dialog_remind_backup, null);
        CheckBox lessCb = box.findViewById(R.id.remind_less);
        String[] labels = {
                "少提醒我（改为每 15 次提醒）",
                "少提醒我（改为每 30 次提醒）",
                "少提醒我（改为每 100 次提醒）"
        };
        if (level < labels.length) {
            lessCb.setText(labels[level]);
        } else {
            lessCb.setVisibility(View.GONE);
        }
        new AlertDialog.Builder(this)
                .setTitle("建议备份数据")
                .setMessage("已启动 " + count + " 次，建议把配置和对话记录导出到\n"
                        + "Download/DSHA 备份，防止意外丢失。")
                .setView(box)
                .setPositiveButton("立即备份", (d, w) -> {
                    confirmReminder(prefs, level, lessCb, count);
                    startBackup();
                })
                .setNegativeButton("取消", (d, w) ->
                        confirmReminder(prefs, level, lessCb, count))
                .show();
    }

    private void confirmReminder(SharedPreferences prefs, int level,
                                 CheckBox lessCb, int count) {
        if (lessCb != null && lessCb.isChecked()) {
            prefs.edit().putInt("reminder_level", level + 1).apply();
        }
        prefs.edit().putInt("last_reminded", count).apply();
    }

    /** 后台执行全量备份，完成后弹窗告知目录并可复制路径 */
    private void startBackup() {
        Toast.makeText(this, "正在备份，请稍候…", Toast.LENGTH_SHORT).show();
        new Thread(() -> {
            String path = BackupManager.backupToExternal(this, HarnessController.get(this));
            runOnUiThread(() -> {
                if (path == null) {
                    // 别再猜原因：BackupManager 已经把真实失败原因记下来了
                    String why = BackupManager.lastError();
                    new AlertDialog.Builder(this)
                            .setTitle("备份失败")
                            .setMessage(why.isEmpty() ? "未知原因，请查看 logcat" : why)
                            .setPositiveButton("知道了", null)
                            .show();
                    return;
                }
                new AlertDialog.Builder(this)
                        .setTitle("备份完成")
                        .setMessage("已导出到：\n" + path)
                        .setPositiveButton("复制路径", (d, w) -> {
                            ClipboardManager cm = (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
                            if (cm != null) {
                                cm.setPrimaryClip(ClipData.newPlainText("backup", path));
                                Toast.makeText(this, "路径已复制", Toast.LENGTH_SHORT).show();
                            }
                        })
                        .setNegativeButton("好", null)
                        .show();
            });
        }).start();
    }
}
