package com.deepseekharness.app.ui;

import android.content.Context;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;
import androidx.appcompat.app.AppCompatActivity;
import com.deepseekharness.app.R;
import com.deepseekharness.app.RootShell;
import com.deepseekharness.app.ShizukuShell;
import com.deepseekharness.app.core.ConfigStore;
import com.deepseekharness.app.util.UiText;

/**
 * 引导里的设备命令通道：只推荐一台 Shizuku 兼容管理器（有 Stellar 就显示 Stellar）。
 * Root 与 ADB 留在设置页。协议仍是 Shizuku API，不接入 Stellar 原生权限。
 */
final class PermissionChannelCard {
  static final String SHIZUKU = "shizuku", ROOT = "root", ADB = "adb";

  private PermissionChannelCard() {}

  static boolean shizukuReady() {
    return ShizukuShell.isAvailable() && ShizukuShell.hasPermission() && ShizukuShell.isReady();
  }

  /**
   * Root 仅在本地开关已开且最近一次验证已由当前 su 响应时视为就绪。RootShell 属于受管运行时启动输入，
   * 不能为界面新增接口，这里只比对它渲染出的“已响应”整句（当前语言与中文原文两种写法）。
   * 该结果是进程内缓存，只用于显示，不作为执行门禁。
   */
  static boolean rootReady(Context context) {
    if (!RootShell.enabled(context)) return false;
    String status = RootShell.status(context);
    // RootShell 用模板 "su：%s\n<已响应>" 渲染；取当前语言模板换行后的固定部分比对。
    String template = UiText.format("su：%s\nroot 通道已响应 · 设备命令仍受策略保护", "\u0000");
    int split = template.indexOf('\n');
    String responded = split < 0 ? template : template.substring(split + 1);
    return status.contains("root 通道已响应 · 设备命令仍受策略保护")
        || (!responded.isEmpty() && status.contains(responded));
  }

  /** 构建卡片；refresh 在状态可能变化后由面板调用以整体重绘。 */
  static PermissionCard.Model model(
      AppCompatActivity activity,
      OnboardingKit kit,
      String tab,
      boolean environmentReady,
      boolean recommended,
      java.util.function.Consumer<String> selectTab,
      Runnable refresh) {
    Context ctx = activity;
    boolean shizuku = shizukuReady(), root = rootReady(ctx);
    PermissionCard.Model m = new PermissionCard.Model();
    m.icon = R.drawable.ic_terminal;
    boolean stellar = com.deepseekharness.app.ShizukuManagerCompat.isStellar(ctx);
    boolean hasManager = com.deepseekharness.app.ShizukuManagerCompat.manager(ctx) != null;
    m.title = UiText.choose("设备执行通道", "Device command channel");
    m.why =
        UiText.choose(
            "通过 Stellar 的 Shizuku 兼容层执行设备命令。Root 与 ADB 在设置中配置。",
            "Runs device commands through Stellar's Shizuku compatibility layer. Root and ADB are in Settings.");
    m.ok = shizuku || root;
    m.pill =
        shizuku
            ? (stellar
                ? UiText.choose("Stellar 已授权", "Stellar authorized")
                : UiText.choose("Shizuku 已授权", "Shizuku authorized"))
            : hasManager
                ? UiText.choose("未授权", "Not authorized")
                : UiText.choose("未安装 Stellar", "Stellar not installed");
    m.tone = m.ok ? OnboardingKit.Tone.GOOD : OnboardingKit.Tone.NEUTRAL;
    m.recommended = recommended;

    LinearLayout body = kit.column();
    shizukuPanel(activity, kit, body, refresh, m);
    m.extra = body;
    return m;
  }

  private static View tabs(
      OnboardingKit kit,
      String current,
      boolean shizuku,
      boolean root,
      java.util.function.Consumer<String> select) {
    LinearLayout bar = kit.row();
    bar.setBackgroundResource(R.drawable.bg_onb_tabs);
    bar.setPadding(kit.dp(3), kit.dp(3), kit.dp(3), kit.dp(3));
    String[][] items = {
      {SHIZUKU, UiText.choose("Shizuku / Stellar", "Shizuku / Stellar"), shizuku ? "1" : ""}
    };
    for (String[] item : items) {
      boolean selected = item[0].equals(current);
      TextView tab =
          kit.bold(
              item[1] + (item[2].isEmpty() ? "" : " ●"),
              12.5f,
              selected ? R.color.shell_text : R.color.shell_text_muted);
      tab.setGravity(android.view.Gravity.CENTER);
      tab.setSingleLine(true);
      tab.setEllipsize(android.text.TextUtils.TruncateAt.END);
      tab.setMinHeight(kit.dp(36));
      tab.setPadding(kit.dp(8), 0, kit.dp(8), 0);
      tab.setBackgroundResource(R.drawable.bg_onb_tab);
      tab.setSelected(selected);
      tab.setClickable(true);
      tab.setFocusable(true);
      tab.setContentDescription(
          item[1]
              + (item[2].isEmpty() ? "" : UiText.choose("，已就绪", ", ready"))
              + (selected ? UiText.choose("，已选中", ", selected") : ""));
      tab.setOnClickListener(v -> select.accept(item[0]));
      bar.addView(tab, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
    }
    return bar;
  }

  private static TextView statusBox(OnboardingKit kit, String text, boolean ok) {
    TextView status = kit.text(text, 12.5f, ok ? R.color.shell_good : R.color.shell_text_secondary);
    status.setBackgroundResource(R.drawable.bg_onb_status_box);
    status.setPadding(kit.dp(10), kit.dp(8), kit.dp(10), kit.dp(8));
    status.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
    return status;
  }

  /** 识别到 Stellar 时按实际管理器命名按钮；协议仍是 Shizuku 兼容层。 */
  private static String authorizeLabel(Context context) {
    return com.deepseekharness.app.ShizukuManagerCompat.isStellar(context)
        ? UiText.choose("授权 Stellar", "Authorize Stellar")
        : UiText.text("授权 Shizuku");
  }

  private static String verifyLabel(Context context) {
    return com.deepseekharness.app.ShizukuManagerCompat.isStellar(context)
        ? UiText.choose("验证 Stellar 连接", "Verify Stellar connection")
        : UiText.text("验证 Shizuku 连接");
  }

  /** 通道未就绪时主操作移到行内（m.inline），展开区只留状态、说明与“打开管理器”。 */
  private static void shizukuPanel(
      AppCompatActivity activity,
      OnboardingKit kit,
      LinearLayout panel,
      Runnable refresh,
      PermissionCard.Model m) {
    boolean ok = shizukuReady();
    com.deepseekharness.app.util.ShizukuChannelStatus.Step step = ShizukuShell.step(activity);
    // 在管理器里授权后回到 DSHA 时，只补一次设备 Shell 绑定；这不是申请权限。
    if (com.deepseekharness.app.util.ShizukuChannelStatus.shouldEnsureBound(step))
      ShizukuShell.ensureBound(activity);
    panel.addView(statusBox(kit, ShizukuShell.userStatus(activity), ok));
    boolean stellar = com.deepseekharness.app.ShizukuManagerCompat.isStellar(activity);
    boolean hasManager = com.deepseekharness.app.ShizukuManagerCompat.manager(activity) != null;
    if (!ok)
      kit.add(
          panel,
          kit.hint(
              hasManager
                  ? (stellar
                      ? UiText.choose(
                          "在 Stellar 中启动服务并开启「Shizuku 兼容层」，再回来授权。",
                          "In Stellar, start the service and enable the Shizuku compatibility layer, then authorize here.")
                      : UiText.choose(
                          "已识别 Shizuku，可直接授权。", "Shizuku is installed; authorize it here."))
                  : UiText.choose(
                      "推荐安装 Stellar 并开启「Shizuku 兼容层」；已有 Shizuku 也可以。",
                      "Install Stellar and enable its Shizuku compatibility layer; Shizuku also works."),
              OnboardingKit.Tone.ACCENT),
          8);
    Button primary;
    if (!hasManager)
      primary =
          kit.small(
              UiText.choose("安装 Stellar", "Install Stellar"),
              true,
              v -> PermissionActions.openStellarInstall(activity));
    else if (!ShizukuShell.isAvailable())
      primary =
          kit.small(
              authorizeLabel(activity),
              true,
              v -> {
                v.setEnabled(false);
                com.deepseekharness.app.ShizukuManagerCompat.reconnect(
                    activity,
                    () -> {
                      if (activity.isFinishing() || activity.isDestroyed()) return;
                      v.setEnabled(true);
                      if (ShizukuShell.isAvailable())
                        ShizukuShell.requestPermission(
                            (code, result) -> activity.runOnUiThread(refresh));
                      else PermissionActions.toast(activity, ShizukuShell.userStatus(activity));
                      refresh.run();
                    });
              });
    else if (step == com.deepseekharness.app.util.ShizukuChannelStatus.Step.AUTHORIZE_IN_OWNER)
      // 分身 / 工作资料里没有管理器：授权只能在机主空间完成，这里只重新读取真实状态。
      primary = kit.small(UiText.choose("刷新状态", "Refresh"), true, v -> refresh.run());
    else if (!ShizukuShell.hasPermission())
      primary =
          kit.small(
              authorizeLabel(activity),
              true,
              v ->
                  ShizukuShell.requestPermission(
                      (code, result) -> activity.runOnUiThread(refresh)));
    else
      primary =
          kit.small(
              verifyLabel(activity),
              false,
              v -> PermissionActions.verify(activity, v, () -> ShizukuShell.exec("id"), refresh));
    Button open =
        kit.small(
            hasManager
                ? (stellar
                    ? UiText.choose("打开 Stellar", "Open Stellar")
                    : UiText.choose("打开 Shizuku", "Open Shizuku"))
                : UiText.choose("我已有 Shizuku", "I already have Shizuku"),
            false,
            v -> {
              if (hasManager) PermissionActions.openShizukuManager(activity);
              else
                PermissionActions.toast(
                    activity,
                    UiText.choose(
                        "本机还没识别到 Shizuku。装好并启动服务后回到这里刷新。",
                        "Shizuku is not on this device yet. Install it, start the service, then return here and refresh."));
            });
    if (!m.ok) {
      m.inline = PermissionCard.compact(kit, primary);
      kit.add(panel, kit.actionRow(open), 10);
    } else kit.add(panel, kit.actionRow(primary, open), 10);
  }

  private static void rootPanel(
      AppCompatActivity activity, OnboardingKit kit, LinearLayout panel, Runnable refresh) {
    Context ctx = activity;
    boolean enabled = RootShell.enabled(ctx);
    LinearLayout line = kit.row();
    line.setMinimumHeight(kit.dp(48));
    TextView label = kit.bold(UiText.text("允许 Root Shell"), 14, R.color.shell_text);
    line.addView(label, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
    var toggle = new com.google.android.material.materialswitch.MaterialSwitch(ctx);
    toggle.setChecked(enabled);
    toggle.setContentDescription(UiText.text("允许 Root Shell"));
    toggle.setOnCheckedChangeListener(
        (button, on) -> {
          new ConfigStore(ctx).setRootShellAllowed(on);
          if (!on)
            com.deepseekharness.app.vscreen.VirtualScreenManager.from(ctx)
                .revokeFor("ROOT_DISABLED");
          Context app = ctx.getApplicationContext();
          new Thread(
                  () -> {
                    try {
                      com.deepseekharness.app.bridge.AdbBridge.applySettings(
                          app, com.deepseekharness.app.core.HarnessController.get(app).proot());
                    } catch (RuntimeException ignored) {
                      // 与设备授权页一致：同步结果只用于提示，失败不影响本地开关。
                    }
                  },
                  "onboarding-root-sync")
              .start();
          refresh.run();
        });
    line.addView(toggle);
    panel.addView(line);
    panel.addView(statusBox(kit, RootShell.status(ctx), rootReady(ctx)), kit.topMargin(4));
    // 行内只留第一句（root 可直接用 su、无需 ADB 配对），严格档与不回退说明挂 ⓘ。
    TextView rootHint =
        kit.text(
            UiText.choose(
                "手机已 root 时可直接使用 su，无需 ADB 配对。首次执行请在 root 管理器中授权 DSHA。开关打开且找到 su 后命令会先走 root，管理器拒绝时不会自动改用 Shizuku；引导中固定为严格档，放宽只在 设置 → 设备能力授权 中调整。",
                "On a rooted phone su works without ADB pairing; approve DSHA in your root manager on first use. Once enabled and su is found, commands go through root first and do not fall back to Shizuku if the manager denies. Onboarding keeps the strict level; relax it only in Settings → Device access."),
            12,
            R.color.shell_text_muted);
    InfoHint.attach(rootHint, label.getText());
    kit.add(panel, rootHint, 8);
    if (enabled) {
      Button verify =
          kit.small(
              UiText.choose("验证 root 授权", "Verify root access"),
              true,
              v -> {
                if (!RootShell.present()) {
                  PermissionActions.toast(activity, UiText.text("未找到 su，请检查 root 授权"));
                  return;
                }
                PermissionActions.verify(
                    activity,
                    v,
                    () -> RootShell.exec(ctx.getApplicationContext(), "id", -1),
                    () -> {
                      refresh.run();
                      // 开关打开且找到 su 时命令先走 root，管理器拒绝不会自动改用 Shizuku。
                      if (RootShell.enabled(ctx) && !rootReady(ctx))
                        PermissionActions.toast(
                            activity,
                            UiText.choose(
                                "root 未响应：可在 root 管理器中允许 DSHA，或关闭「允许 Root Shell」改用 Shizuku / Stellar。",
                                "Root did not respond: allow DSHA in your root manager, or turn off Allow Root Shell and use Shizuku / Stellar instead."));
                    });
              });
      kit.add(panel, kit.actionRow(verify), 10);
    }
  }

  /** ADB 状态只读：与 DeviceGrantsFragment.computeAdbStatus 同一判据（不发起连接或配对）。 */
  private static String adbStatus(Context app) {
    try {
      if (!com.deepseekharness.app.DeviceBridgeService.isAdbEnabled(app))
        return UiText.text("ADB 已关闭");
      String bridge = com.deepseekharness.app.DeviceBridgeService.adbState;
      if ("need_pair".equals(bridge)) return UiText.text("配对已失效，请重新配对。");
      if ("reconnecting".equals(bridge)) return UiText.format("正在重连无线调试…%s", "");
      if ("connected".equals(bridge))
        return UiText.choose("连接已验证 · ADB 通道可用", "Connection verified · ADB channel available");
      return UiText.text("尚未配对");
    } catch (RuntimeException unknown) {
      return UiText.text("尚未配对");
    }
  }

  private static void adbPanel(
      AppCompatActivity activity, OnboardingKit kit, LinearLayout panel, boolean environmentReady) {
    boolean connected =
        environmentReady
            && "connected".equals(com.deepseekharness.app.DeviceBridgeService.adbState);
    panel.addView(
        statusBox(
            kit,
            environmentReady
                ? adbStatus(activity.getApplicationContext())
                : UiText.choose(
                    "运行环境就绪后可配对：配对和连接验证依赖内置环境。",
                    "Pairing becomes available when the runtime is ready: pairing and verification use the bundled environment."),
            connected));
    kit.add(
        panel,
        kit.hint(
            UiText.choose(
                "连接验证通过后，DSHA 会给自己授予 WRITE_SECURE_SETTINGS，用于开机后自动打开无线调试；配对与 ADB 通道开关在 设置 → 设备能力授权 中管理。",
                "After the connection is verified, DSHA grants itself WRITE_SECURE_SETTINGS so wireless debugging can be re-enabled after boot. Pairing and the ADB channel switch live in Settings → Device access."),
            OnboardingKit.Tone.ACCENT),
        8);
    Button pair =
        kit.small(
            UiText.choose("ADB 无线配对", "ADB wireless pairing"),
            true,
            v -> {
              try {
                activity.startActivity(new android.content.Intent(activity, AdbPairActivity.class));
              } catch (RuntimeException failure) {
                PermissionActions.toast(activity, UiText.format("打开设置失败：%s", failure.getMessage()));
              }
            });
    pair.setEnabled(environmentReady);
    kit.add(panel, kit.actionRow(pair), 10);
  }
}
