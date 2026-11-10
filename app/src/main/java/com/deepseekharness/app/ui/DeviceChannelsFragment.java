package com.deepseekharness.app.ui;

import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.CompoundButton;
import android.widget.TextView;
import android.widget.Toast;
import androidx.fragment.app.Fragment;
import com.deepseekharness.app.DeviceBridgeService;
import com.deepseekharness.app.R;
import com.deepseekharness.app.RootShell;
import com.deepseekharness.app.ShizukuShell;
import com.deepseekharness.app.bridge.AdbBridge;
import com.deepseekharness.app.core.ConfigStore;
import com.deepseekharness.app.core.HarnessController;
import com.deepseekharness.app.util.Constants;
import com.deepseekharness.app.util.DeviceShellPolicy;
import com.deepseekharness.app.util.UiText;
import com.google.android.material.materialswitch.MaterialSwitch;

/**
 * 设备执行通道（独立页面）：Root / Shizuku·Stellar / ADB / 外接设备，以及 root 命令拦截档位。
 *
 * <p>从设备能力授权页拆出：入口仍在该页，通道的开关、验证、配对与风险确认都在这里完成。
 */
public final class DeviceChannelsFragment extends Fragment {
  private boolean updating;
  private int adbStatusRequest;
  private MaterialSwitch externalDevice;
  private TextView externalDeviceStatus;
  private android.widget.RadioGroup rootModes;
  private android.widget.RadioButton[] rootModeButtons;

  /** 折叠披露状态：旋转/切页/每秒刷新都保留；用户折叠后不再被默认展开覆盖。 */
  private DisclosureCard.Memory disclosure = new DisclosureCard.Memory();

  private final DisclosureCard.Model channelsModel = new DisclosureCard.Model();
  private Accordion accordion;

  private final android.os.Handler main = new android.os.Handler(android.os.Looper.getMainLooper());
  private final Runnable refreshConnections =
      new Runnable() {
        @Override
        public void run() {
          if (!isResumed() || getView() == null) return;
          refreshChannelLabels();
          main.postDelayed(this, 1000);
        }
      };

  @Override
  public View onCreateView(LayoutInflater inflater, ViewGroup container, Bundle saved) {
    View view = inflater.inflate(R.layout.fragment_device_channels, container, false);
    PressFeedback.attachButtons(view);
    Context ctx = requireContext();
    if (saved != null) disclosure = DisclosureCard.Memory.restore(saved, "disclosure");
    accordion = new Accordion(disclosure);
    bindChannelsDisclosure(ctx, view);

    CompoundButton root = view.findViewById(R.id.config_root_shell);
    root.setContentDescription(getString(R.string.ui_m0062));
    view.findViewById(R.id.device_root_title).setOnClickListener(v -> root.toggle());
    root.setChecked(new ConfigStore(ctx).isRootShellAllowed());
    root.setOnCheckedChangeListener(
        (button, enabled) -> {
          if (updating) return;
          new ConfigStore(ctx).setRootShellAllowed(enabled);
          if (!enabled)
            com.deepseekharness.app.vscreen.VirtualScreenManager.from(ctx)
                .revokeFor("ROOT_DISABLED");
          else
            // 放宽到 root 属于高风险档位：展开通道说明，把后果与拦截范围直接显示出来。
            expandChannelsDisclosure(view);
          syncSettings();
          refreshChannelLabels();
        });
    addRootModeControls(ctx, view);
    refreshRootPolicy();
    android.widget.Button rootVerify = view.findViewById(R.id.device_root_verify);
    rootVerify.setText(UiText.choose("验证", "Verify"));
    rootVerify.setContentDescription(getString(R.string.ui_m0170));
    rootVerify.setOnClickListener(
        button -> {
          if (!RootShell.enabled(ctx)) {
            toast(UiText.text("请先启用「允许 Root Shell」"));
            return;
          }
          if (!RootShell.present()) {
            toast(UiText.text("未找到 su，请检查 root 授权"));
            return;
          }
          verify(
              button,
              view.findViewById(R.id.device_root_status),
              () -> RootShell.exec(ctx.getApplicationContext(), "id", -1));
        });
    view.findViewById(R.id.device_shizuku_open).setOnClickListener(button -> openShizuku());
    view.findViewById(R.id.workspace_shizuku_auth)
        .setOnClickListener(
            button -> {
              if (!ShizukuShell.isAvailable()) {
                button.setEnabled(false);
                com.deepseekharness.app.ShizukuManagerCompat.reconnect(
                    requireActivity(),
                    () -> {
                      if (!isAdded()) return;
                      button.setEnabled(true);
                      if (ShizukuShell.isAvailable())
                        ShizukuShell.requestPermission(
                            (code, result) -> {
                              if (isAdded()) refreshChannelLabels();
                            });
                      else toast(ShizukuShell.userStatus(ctx));
                      refreshChannelLabels();
                    });
                return;
              }
              if (ShizukuShell.hasPermission()) {
                verify(
                    button,
                    view.findViewById(R.id.workspace_shizuku_status),
                    () -> ShizukuShell.exec("id"));
              } else
                ShizukuShell.requestPermission(
                    (code, result) -> {
                      if (isAdded()) refreshChannelLabels();
                    });
            });
    CompoundButton adb = view.findViewById(R.id.config_adb_enable);
    TextView adbTitle = view.findViewById(R.id.config_adb_title);
    adbTitle.setText(UiText.choose("ADB 设备通道", "ADB device channel"));
    adbTitle.setOnClickListener(v -> adb.toggle());
    adb.setContentDescription(getString(R.string.ui_m0090));
    adb.setChecked(pref(ctx, "adb_enabled", false));
    adb.setOnCheckedChangeListener(
        (button, enabled) -> {
          if (updating) return;
          com.deepseekharness.app.HttpShellService.smsPolicyChanged(ctx);
          DeviceBridgeService.adbIntentChanging();
          savePreference("adb_enabled", enabled);
          if (!enabled)
            com.deepseekharness.app.vscreen.VirtualScreenManager.from(ctx)
                .revokeFor("ADB_DISABLED");
          if (enabled) {
            DeviceBridgeService.apply(ctx);
            if (getActivity() instanceof MainActivity)
              ((MainActivity) getActivity()).requestLocalNetwork();
          } else ctx.stopService(new Intent(ctx, DeviceBridgeService.class));
          syncSettings();
          refreshAdbStatus(view.findViewById(R.id.config_adb_status));
        });
    android.widget.Button pair = view.findViewById(R.id.config_adb_pair);
    pair.setText(UiText.choose("配对", "Pair"));
    pair.setContentDescription(getString(R.string.ui_m0031));
    pair.setOnClickListener(
        button -> {
          if (android.os.Build.VERSION.SDK_INT < 30) {
            new DshaDialogBuilder(ctx)
                .setTitle(UiText.text("当前系统没有配对码接口"))
                .setMessage(
                    UiText.text(
                        "无线调试配对码需要 Android 11+。Android 6—10 可使用 Shizuku、已授权的 root，或由电脑开启 ADB TCP 通道。"))
                .setPositiveButton(UiText.text("知道了"), null)
                .show();
          } else startActivity(new Intent(ctx, AdbPairActivity.class));
        });
    addExternalDeviceControls(ctx, view);
    refreshChannelLabels();
    return view;
  }

  /** 独立外接目标能力：默认关闭、不继承本机 ADB/全局授权，首版只开放 USB root 调试与只读预检。 */
  private void addExternalDeviceControls(Context ctx, View page) {
    // 外接设备属于设备通道：作为通道卡的最后一行，与 Root / Shizuku / ADB 同一行式；长说明收进标题 ⓘ。
    android.widget.LinearLayout card = page.findViewById(R.id.channels_card);
    View divider = new View(ctx);
    android.widget.LinearLayout.LayoutParams dividerParams =
        new android.widget.LinearLayout.LayoutParams(-1, dp(1));
    dividerParams.topMargin = dp(6);
    divider.setBackgroundColor(ctx.getColor(R.color.shell_line));
    card.addView(divider, dividerParams);
    android.widget.LinearLayout row = new android.widget.LinearLayout(ctx);
    row.setOrientation(android.widget.LinearLayout.HORIZONTAL);
    row.setGravity(android.view.Gravity.CENTER_VERTICAL);
    row.setMinimumHeight(getResources().getDimensionPixelSize(R.dimen.grants_channel_row));
    android.widget.LinearLayout text = new android.widget.LinearLayout(ctx);
    text.setOrientation(android.widget.LinearLayout.VERTICAL);
    text.setPadding(0, dp(6), 0, dp(6));
    TextView title = new TextView(ctx, null, 0, R.style.ShellRowTitle);
    String titleText = UiText.choose("外接设备（实验性）", "External device (experimental)");
    title.setText(titleText);
    title.setMaxLines(2);
    text.addView(title, new android.widget.LinearLayout.LayoutParams(-2, -2));
    bindChannelsLabelInfo(
        title,
        "external-device",
        UiText.choose(
            "实验性，未经真机验收。独立授权另一台手机（USB 或无线调试），绑定后可受控调试、重启和确认后安装；刷机只做只读预检，不执行刷写。",
            "Experimental, not yet device-verified. Authorize another phone (USB or wireless debugging); once bound it allows controlled debugging, reboot and confirmed installs. Flashing is read-only preflight only."),
        false);
    title.setMinHeight(0);
    // 状态与标题叠成左列：开关先量，状态胶囊在剩余宽度内换行，长译文不会把开关挤出屏幕（T125）。
    externalDeviceStatus = new TextView(ctx, null, 0, R.style.ShellChannelStatusPill);
    android.widget.LinearLayout.LayoutParams statusParams =
        new android.widget.LinearLayout.LayoutParams(-2, -2);
    statusParams.topMargin = dp(3);
    text.addView(externalDeviceStatus, statusParams);
    row.addView(text, new android.widget.LinearLayout.LayoutParams(0, -2, 1f));
    externalDevice = new MaterialSwitch(ctx);
    externalDevice.setContentDescription(UiText.choose("允许外接设备能力", "Allow external-device access"));
    externalDevice.setChecked(new com.deepseekharness.app.extdev.ExternalDeviceHost(ctx).enabled());
    android.widget.LinearLayout.LayoutParams switchParams =
        new android.widget.LinearLayout.LayoutParams(-2, -2);
    switchParams.setMarginStart(dp(4));
    row.addView(externalDevice, switchParams);
    card.addView(row, new android.widget.LinearLayout.LayoutParams(-1, -2));
    externalDevice.setOnCheckedChangeListener(
        (button, enabled) -> {
          if (updating) return;
          if (!enabled) {
            if (!new com.deepseekharness.app.extdev.ExternalDeviceHost(ctx).setEnabled(false))
              toast(UiText.text("设置保存失败，请重试"));
            refreshExternalDeviceStatus();
            return;
          }
          new DshaDialogBuilder(ctx)
              .setTitle(UiText.text("开启外接设备能力？"))
              .setMessage(
                  UiText.choose(
                      "实验性功能。这会允许 DSHA 通过已授权的 root 访问 USB 或无线调试目标。它不会开启本机刷机，也不会自动授权或刷写另一台手机。",
                      "Experimental. This allows DSHA to access a USB or wireless-debugging target through authorized root. It does not enable host flashing or automatically authorize or flash the target."))
              .setPositiveButton(
                  UiText.choose("开启", "Enable"),
                  (dialog, which) -> {
                    if (!new com.deepseekharness.app.extdev.ExternalDeviceHost(ctx)
                        .setEnabled(true)) {
                      updating = true;
                      externalDevice.setChecked(false);
                      updating = false;
                      toast(UiText.text("设置保存失败，请重试"));
                    }
                    refreshExternalDeviceStatus();
                  })
              .setNegativeButton(
                  UiText.choose("取消", "Cancel"),
                  (dialog, which) -> {
                    updating = true;
                    externalDevice.setChecked(false);
                    updating = false;
                  })
              .show();
        });
    refreshExternalDeviceStatus();
  }

  private void refreshExternalDeviceStatus() {
    if (externalDeviceStatus == null || !isAdded()) return;
    final Context ctx = requireContext().getApplicationContext();
    new Thread(
            () -> {
              String text;
              try {
                org.json.JSONObject state =
                    new com.deepseekharness.app.extdev.ExternalDeviceHost(ctx).status();
                text =
                    state.optBoolean("enabled", false)
                        ? state.optBoolean("rootVerified", false)
                            ? state.optBoolean("bound", false)
                                ? UiText.choose("已绑定", "Bound")
                                : UiText.choose("未绑定", "Not bound")
                            : UiText.choose("未验证", "Unverified")
                        : UiText.choose("已关闭", "Off");
              } catch (Throwable error) {
                text = UiText.choose("状态不可用", "Unavailable");
              }
              final String displayText = text;
              main.post(
                  () -> {
                    if (isAdded() && externalDeviceStatus != null)
                      externalDeviceStatus.setText(displayText);
                  });
            },
            "external-device-status")
        .start();
  }

  private static final DeviceShellPolicy.Mode[] ROOT_MODES = {
    DeviceShellPolicy.Mode.STRICT, DeviceShellPolicy.Mode.RELAXED, DeviceShellPolicy.Mode.FULL
  };

  /** Root 命令拦截三段：严格 / 放宽 / 完全放宽。点放宽档直接进入红色确认（勾选三项 + 10 秒）。 */
  private void addRootModeControls(Context ctx, View page) {
    rootModes = page.findViewById(R.id.device_root_modes);
    String[] names = {
      UiText.choose("严格", "Strict"),
      UiText.choose("放宽", "Relaxed"),
      UiText.choose("完全放宽", "Fully relaxed")
    };
    String[] spoken = {
      UiText.format(
          "%s, %s",
          UiText.text("严格（默认）"), UiText.choose("仅放行白名单内的命令", "Only allowlisted commands run")),
      UiText.format(
          "%s, %s",
          UiText.choose("放宽", "Relaxed"),
          UiText.choose("允许任意命令，仍拦截不可逆操作", "Any command; irreversible operations still blocked")),
      UiText.format(
          "%s, %s",
          UiText.choose("完全放宽", "Fully relaxed"),
          UiText.choose("不按命令内容拦截", "No command-content checks"))
    };
    rootModeButtons =
        GrantsUi.buildSegment(
            ctx,
            rootModes,
            names,
            spoken,
            new int[] {R.color.shell_good, R.color.shell_warn, R.color.shell_bad},
            index -> pickRootMode(ROOT_MODES[index]));
  }

  /** 档位分段始终显示已保存值；只有确认保存后才会切换。 */
  private void pickRootMode(DeviceShellPolicy.Mode mode) {
    if (!isAdded()) return;
    final Context ctx = requireContext();
    DeviceShellPolicy.Mode current = new ConfigStore(ctx).rootPolicyMode();
    refreshRootPolicy();
    if (!RootShell.enabled(ctx)) {
      toast(UiText.text("请先启用「允许 Root Shell」"));
      return;
    }
    if (mode != current) confirmRootPolicy(mode);
  }

  /** 当前档位按已保存值选中；放宽 / 完全放宽时显示红色警告框。Root 未启用时分段变淡。 */
  private void refreshRootPolicy() {
    if (rootModeButtons == null || !isAdded()) return;
    Context ctx = requireContext();
    DeviceShellPolicy.Mode mode = new ConfigStore(ctx).rootPolicyMode();
    boolean enabled = RootShell.enabled(ctx);
    updating = true;
    for (int i = 0; i < rootModeButtons.length; i++)
      rootModeButtons[i].setChecked(ROOT_MODES[i] == mode);
    updating = false;
    rootModes.setAlpha(enabled ? 1f : 0.5f);
    View page = getView();
    TextView warning = page == null ? null : page.findViewById(R.id.device_root_policy_warning);
    if (warning == null) return;
    boolean relaxed = enabled && mode != DeviceShellPolicy.Mode.STRICT;
    boolean wasHidden = warning.getVisibility() != View.VISIBLE;
    warning.setVisibility(relaxed ? View.VISIBLE : View.GONE);
    if (!relaxed) return;
    boolean full = mode == DeviceShellPolicy.Mode.FULL;
    String detail =
        full
            ? UiText.choose(
                "严重风险：已完全放宽。助手和插件可用 root 执行任意命令，包括格式化、刷机、清除数据等不可逆操作，可能导致数据丢失或系统损坏。不用时请改回严格。",
                "Severe risk: fully relaxed. The assistant and plugins can run any root command, including irreversible ones such as formatting, flashing or wiping data. This may cause data loss or system damage. Switch back to strict when not needed.")
            : UiText.choose(
                "高风险：已放宽。助手和插件可用 root 执行白名单以外的任意命令；仅拦截格式化、刷机、清除数据等不可逆操作。不用时请改回严格。",
                "High risk: relaxed. The assistant and plugins can run root commands beyond the allowlist; only irreversible operations such as formatting, flashing or wiping data are blocked. Switch back to strict when not needed.");
    // 行内只留一句（含「严重风险 / 高风险」与后果），点按看完整原文；读屏直接读完整原文。
    // 档位切换时淡入新结论（审计 C-02）；从隐藏变为可见时整块淡入。
    UiMotion.crossFadeText(
        warning,
        full
            ? UiText.choose(
                "严重风险：root 可执行任意命令，含格式化、刷机等不可逆操作",
                "Severe risk: root can run any command, including irreversible ones")
            : UiText.choose(
                "高风险：root 可执行白名单外命令，仅拦截不可逆操作",
                "High risk: root can run commands beyond the allowlist"));
    if (wasHidden) UiMotion.reveal(warning);
    warning.setTag(R.id.device_root_policy_warning, detail);
    warning.setContentDescription(detail);
  }

  /** 「设备执行通道」折叠说明：Root / Shizuku / ADB 的作用与拦截范围；展开才创建详情。 */
  private void bindChannelsDisclosure(Context ctx, View page) {
    TextView label = page.findViewById(R.id.grants_channels_label);
    if (label == null) return;
    String shizuku =
        com.deepseekharness.app.ShizukuManagerCompat.isStellar(ctx)
            ? UiText.choose(
                "已识别 Stellar（Shizuku 兼容管理器）。请在 Stellar 中启动服务并开启「Shizuku 兼容层」，再返回授权；手机重启后通常需要重新启动服务。",
                "Stellar (a Shizuku-compatible manager) is detected. Start its service and enable the Shizuku compatibility layer, then authorize here. Restart the service after reboot.")
            : getString(R.string.ui134_shizuku_hint);
    String full =
        UiText.format(
            "Root: %s\n\n%s\n\nShizuku: %s\n\nADB: %s",
            getString(R.string.ui134_root_hint),
            UiText.choose(
                "命令拦截档位仅对 root 通道生效；Shizuku/ADB 通用执行器仍按严格档。",
                "Command filtering levels apply only to the root channel; Shizuku/ADB general executors stay strict."),
            shizuku,
            getString(R.string.ui134_adb_hint));
    channelsModel.key = "channels";
    channelsModel.detail = full;
    // 默认收起：正文只留通道行与单行状态，长说明点标签再展开。
    channelsModel.defaultExpanded = false;
    DisclosureCard.bind(
        label, channelsModel, accordion.state(), () -> accordion.onToggle("channels"));
    accordion.register("channels", () -> bindChannelsDisclosure(ctx, page));
  }

  /** 通道行标题旁的小说明（外接设备）：同样走折叠披露，保留标题原有样式。 */
  private void bindChannelsLabelInfo(
      TextView label, String key, CharSequence full, boolean defaultExpanded) {
    GrantsUi.bindLabelInfo(label, key, full, defaultExpanded, accordion);
  }

  /** Root 开关放宽到高风险档位时直接展开通道说明，把后果与拦截范围显示出来。 */
  private void expandChannelsDisclosure(View page) {
    TextView label = page == null ? null : page.findViewById(R.id.grants_channels_label);
    if (label == null) return;
    accordion.open("channels");
    DisclosureCard.bind(
        label, channelsModel, accordion.state(), () -> accordion.onToggle("channels"));
  }

  @Override
  public void onSaveInstanceState(@androidx.annotation.NonNull Bundle outState) {
    super.onSaveInstanceState(outState);
    disclosure.save(outState, "disclosure");
  }

  private void savePreference(String key, boolean enabled) {
    boolean success =
        requireContext()
            .getSharedPreferences(Constants.PREFS, 0)
            .edit()
            .putBoolean(key, enabled)
            .commit();
    if (success && !enabled) com.deepseekharness.app.DeviceSense.revoke(requireContext(), key);
    if (!success) toast(UiText.text("设置保存失败，请重试"));
  }

  private void syncSettings() {
    final Context app = requireContext().getApplicationContext();
    final View page = getView();
    new Thread(
            () -> {
              String result = AdbBridge.applySettings(app, HarnessController.get(app).proot());
              main.post(
                  () -> {
                    if (!isAdded() || page == null || getView() != page) return;
                    TextView status = page.findViewById(R.id.config_guard_status);
                    status.setVisibility(View.VISIBLE);
                    status.setText(
                        UiText.format(
                            "原生授权即时生效；%s", result.replaceFirst("^SETTINGS_[A-Z]+: ", "")));
                  });
            },
            "device-settings")
        .start();
  }

  private void verify(View button, TextView status, java.util.function.Supplier<String> probe) {
    final View page = getView();
    button.setEnabled(false);
    status.setText(UiText.text("正在验证设备通道…"));
    status.setTag(Boolean.TRUE);
    new Thread(
            () -> {
              String output;
              try {
                output = probe.get();
              } catch (Throwable error) {
                output = com.deepseekharness.app.util.SensitiveData.redact(String.valueOf(error));
              }
              final String result = output;
              main.post(
                  () -> {
                    if (!isAdded() || getView() != page) return;
                    button.setEnabled(true);
                    status.setTag(null);
                    new DshaDialogBuilder(requireContext())
                        .setTitle(UiText.text("设备通道验证"))
                        .setMessage(UiText.raw(result))
                        .setPositiveButton(UiText.text("知道了"), null)
                        .show();
                    refreshChannelLabels();
                  });
            },
            "device-channel-probe")
        .start();
  }

  private void openShizuku() {
    Intent launch = com.deepseekharness.app.ShizukuManagerCompat.launchIntent(requireContext());
    if (launch == null) {
      toast(
          UiText.choose(
              "尚未安装 Shizuku 或 Stellar，请先安装并在管理器中启动服务",
              "Shizuku or Stellar is not installed; install one and start its service first."));
      return;
    }
    try {
      startActivity(launch);
    } catch (RuntimeException e) {
      toast(UiText.format("无法打开 Shizuku：%s", e.getClass().getSimpleName()));
    }
  }

  private void refreshChannelLabels() {
    View page = getView();
    if (page == null) return;
    TextView shizuku = page.findViewById(R.id.workspace_shizuku_status);
    TextView root = page.findViewById(R.id.device_root_status);
    if (shizuku.getTag() == null) {
      String fullShizuku = ShizukuShell.userStatus(requireContext());
      shizuku.setText(
          com.deepseekharness.app.util.UiStateText.render(shortenChannelStatus(fullShizuku)));
      shizuku.setContentDescription(fullShizuku);
    }
    // 识别到 Stellar（Shizuku 兼容管理器）时按实际管理器命名；协议仍是 Shizuku 兼容层。
    boolean stellar = com.deepseekharness.app.ShizukuManagerCompat.isStellar(requireContext());
    // 行内按钮只放短动词；完整含义（验证 Stellar 连接 / 授权 Shizuku …）交给读屏。
    TextView auth = page.findViewById(R.id.workspace_shizuku_auth);
    boolean granted = ShizukuShell.hasPermission();
    auth.setText(granted ? UiText.choose("验证", "Verify") : UiText.choose("授权", "Authorize"));
    auth.setContentDescription(
        granted
            ? stellar
                ? UiText.choose("验证 Stellar 连接", "Verify Stellar connection")
                : UiText.text("验证 Shizuku 连接")
            : stellar
                ? UiText.choose("授权 Stellar", "Authorize Stellar")
                : UiText.text("授权 Shizuku"));
    TextView open = page.findViewById(R.id.device_shizuku_open);
    open.setText(UiText.choose("打开", "Open"));
    open.setContentDescription(
        stellar ? UiText.choose("打开 Stellar", "Open Stellar") : getString(R.string.ui_m0125));
    TextView title = page.findViewById(R.id.device_shizuku_title);
    if (title != null)
      title.setText(stellar ? "Shizuku / Stellar" : getString(R.string.ui_brand_shizuku));
    if (root.getTag() == null) root.setText(rootStatusWithoutPolicy(requireContext()));
    refreshRootPolicy();
  }

  /** 通道状态行只显示短状态；完整原因放在读屏描述里，避免长句在两行里挤满。 */
  private static String shortenChannelStatus(String full) {
    if (full == null) return "";
    if (full.contains("兼容层") || full.contains("compatibility layer"))
      return UiText.choose("已安装 Stellar · 兼容层未连接", "Stellar installed · layer not connected");
    return full;
  }

  /**
   * RootShell.status 末行是拦截档位；本页档位已单独显示，这里去掉重复的那一行。紧凑行只显示一行，
   * 剩余各行用「 · 」接成一行，避免「su 路径」占满后把授权状态截掉；点按仍看全文。
   */
  private static String rootStatusWithoutPolicy(Context ctx) {
    if (!RootShell.enabled(ctx)) return UiText.choose("未启用", "Not enabled");
    String status = RootShell.status(ctx);
    int cut = status.lastIndexOf('\n');
    String shown = RootShell.enabled(ctx) && cut > 0 ? status.substring(0, cut) : status;
    return shown.trim().replaceAll("\\s*\\n\\s*", " · ");
  }

  /** 放宽档位会扩大助手与插件的 root 权限，保存前必须再确认一次风险。 */
  private void confirmRootPolicy(DeviceShellPolicy.Mode mode) {
    final Context ctx = requireContext();
    if (mode == DeviceShellPolicy.Mode.STRICT) {
      saveRootPolicy(mode);
      return;
    }
    boolean full = mode == DeviceShellPolicy.Mode.FULL;
    int bad = ctx.getColor(R.color.shell_bad);
    // 高危确认：红色标题与警告框；点空白处不关闭，只能明确点「取消」或返回键。
    android.text.SpannableString title =
        new android.text.SpannableString(UiText.text("确认放宽 root 命令拦截？"));
    title.setSpan(
        new android.text.style.ForegroundColorSpan(bad),
        0,
        title.length(),
        android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
    TextView warning = new TextView(ctx);
    warning.setBackgroundResource(R.drawable.bg_root_policy_warning);
    warning.setTextColor(bad);
    warning.setTextSize(14);
    warning.setTypeface(warning.getTypeface(), android.graphics.Typeface.BOLD);
    warning.setLineSpacing(dp(3), 1f);
    warning.setPadding(dp(14), dp(12), dp(14), dp(12));
    warning.setCompoundDrawablesRelativeWithIntrinsicBounds(R.drawable.ic_section_warning, 0, 0, 0);
    warning.setCompoundDrawablePadding(dp(10));
    warning.setCompoundDrawableTintList(android.content.res.ColorStateList.valueOf(bad));
    warning.setText(
        full
            ? UiText.choose(
                "完全放宽后不按命令拦截；可执行格式化、刷机、清除数据等不可逆操作，可能造成数据丢失或系统损坏。",
                "Severe risk: fully relaxed mode does not inspect command content. The assistant and plugins can run irreversible root operations such as formatting, flashing or wiping data, which may cause data loss or system damage.")
            : UiText.choose(
                "放宽后可用 root 执行白名单外命令；格式化、刷机、清除数据仍被拦截。",
                "High risk: relaxed mode lets the assistant and plugins run root commands beyond the allowlist; irreversible operations such as formatting, flashing or wiping data stay blocked."));
    TextView scope = new TextView(ctx);
    scope.setTextSize(12);
    scope.setTextColor(ctx.getColor(R.color.shell_text_secondary));
    scope.setText(
        UiText.choose(
            "仅对 root 通道；Shizuku/ADB 仍严格",
            "Applies to the root channel only; Shizuku/ADB stay strict. You can switch back to strict anytime."));
    android.widget.LinearLayout body = new android.widget.LinearLayout(ctx);
    body.setOrientation(android.widget.LinearLayout.VERTICAL);
    body.setPadding(dp(24), dp(8), dp(24), 0);
    body.addView(warning, new android.widget.LinearLayout.LayoutParams(-1, -2));
    android.widget.LinearLayout.LayoutParams scopeParams =
        new android.widget.LinearLayout.LayoutParams(-1, -2);
    scopeParams.topMargin = dp(10);
    body.addView(scope, scopeParams);
    // 免责确认：三项都勾选，且十秒等待结束，确认按钮才可用。
    String[] acknowledgements = {
      UiText.choose(
          "我了解：放宽后由助手或插件执行的 root 命令可能损坏数据或系统，且可能无法恢复。",
          "I understand that root commands run by the assistant or plugins may damage data or the system, possibly beyond recovery."),
      UiText.choose(
          "我已备份重要数据，并会在不需要时改回严格。",
          "I have backed up important data and will switch back to strict when it is no longer needed."),
      UiText.choose(
          "由此产生的后果由我自行承担，DSHA 及其开发者不承担责任。",
          "I accept the consequences myself; DSHA and its developers are not responsible.")
    };
    java.util.List<android.widget.CheckBox> checks = new java.util.ArrayList<>();
    for (String acknowledgement : acknowledgements) {
      android.widget.CheckBox check = new androidx.appcompat.widget.AppCompatCheckBox(ctx);
      check.setText(acknowledgement);
      check.setTextSize(13);
      check.setTextColor(ctx.getColor(R.color.shell_text));
      check.setButtonTintList(android.content.res.ColorStateList.valueOf(bad));
      check.setMinHeight(dp(44));
      android.widget.LinearLayout.LayoutParams checkParams =
          new android.widget.LinearLayout.LayoutParams(-1, -2);
      checkParams.topMargin = dp(checks.isEmpty() ? 12 : 4);
      body.addView(check, checkParams);
      checks.add(check);
    }
    android.widget.ScrollView scroll = new android.widget.ScrollView(ctx);
    scroll.addView(body);
    String confirmLabel =
        full
            ? UiText.choose("仍要完全放宽", "Fully relax anyway")
            : UiText.choose("仍要放宽", "Relax anyway");
    long startedAt = android.os.SystemClock.elapsedRealtime();
    int waitSeconds = com.deepseekharness.app.util.ConfirmCountdown.ROOT_RELAX_SECONDS;
    java.util.function.BooleanSupplier allChecked =
        () -> {
          for (android.widget.CheckBox check : checks) if (!check.isChecked()) return false;
          return true;
        };
    androidx.appcompat.app.AlertDialog dialog =
        new DshaDialogBuilder(ctx)
            .setTitle(title)
            .setView(scroll)
            // 取消放在最右（拇指位、默认安全）；高危确认放在左侧，需要刻意去点。
            .setPositiveButton(UiText.text("取消"), null)
            .setNegativeButton(
                confirmLabel,
                (d, which) -> {
                  // 双保险：保存前再核对等待与勾选，不只依赖按钮可用状态。
                  if (allChecked.getAsBoolean()
                      && com.deepseekharness.app.util.ConfirmCountdown.remainingSeconds(
                              startedAt, android.os.SystemClock.elapsedRealtime(), waitSeconds)
                          == 0) saveRootPolicy(mode);
                })
            .create();
    dialog.setCanceledOnTouchOutside(false);
    dialog.show();
    android.widget.Button confirm =
        dialog.getButton(android.content.DialogInterface.BUTTON_NEGATIVE);
    if (confirm == null) return;
    confirm.setTextColor(bad);
    // 十秒强制等待：倒计时显示在确认按钮上；「取消」与返回键始终可用。
    Runnable tick =
        new Runnable() {
          @Override
          public void run() {
            if (!dialog.isShowing()) return;
            int left =
                com.deepseekharness.app.util.ConfirmCountdown.remainingSeconds(
                    startedAt, android.os.SystemClock.elapsedRealtime(), waitSeconds);
            boolean ready = left == 0 && allChecked.getAsBoolean();
            confirm.setEnabled(ready);
            confirm.setAlpha(ready ? 1f : 0.45f);
            confirm.setText(left > 0 ? UiText.raw(confirmLabel + " (" + left + ")") : confirmLabel);
            // 勾选变化也会调用本方法：先清掉已排队的一次，保证只有一个倒计时循环。
            confirm.removeCallbacks(this);
            if (left > 0) confirm.postDelayed(this, 250);
          }
        };
    for (android.widget.CheckBox check : checks)
      check.setOnCheckedChangeListener((button, checked) -> tick.run());
    tick.run();
    dialog.setOnDismissListener(d -> confirm.removeCallbacks(tick));
  }

  private int dp(int value) {
    return GrantsUi.dp(requireContext(), value);
  }

  private void saveRootPolicy(DeviceShellPolicy.Mode mode) {
    if (!isAdded()) return;
    final Context ctx = requireContext();
    new ConfigStore(ctx).setRootPolicyMode(mode);
    refreshRootPolicy();
    refreshChannelLabels();
    toast(UiText.format("已保存拦截档位：%s", RootShell.policyLabel(ctx)));
  }

  @Override
  public void onResume() {
    super.onResume();
    ShizukuShell.ensureBound(requireContext());
    refreshChannelLabels();
    refreshExternalDeviceStatus();
    View page = getView();
    if (page != null) refreshAdbStatus(page.findViewById(R.id.config_adb_status));
    main.removeCallbacks(refreshConnections);
    main.post(refreshConnections);
  }

  @Override
  public void onPause() {
    main.removeCallbacks(refreshConnections);
    super.onPause();
  }

  @Override
  public void onDestroyView() {
    adbStatusRequest++;
    rootModes = null;
    rootModeButtons = null;
    externalDevice = null;
    externalDeviceStatus = null;
    super.onDestroyView();
  }

  private boolean pref(Context ctx, String key, boolean fallback) {
    return ctx.getSharedPreferences(Constants.PREFS, 0).getBoolean(key, fallback);
  }

  private void toast(String value) {
    if (getContext() != null) Toast.makeText(getContext(), value, Toast.LENGTH_LONG).show();
  }

  /** 后台读 ADB 通道真实状态（key/deps/端口 + 保活服务的连接状态），刷到状态栏。 */
  private void refreshAdbStatus(final TextView status) {
    final Context app = requireContext().getApplicationContext();
    final View page = getView();
    final android.app.Activity activity = getActivity();
    if (activity == null) return;
    final int request = ++adbStatusRequest;
    new Thread(
            () -> {
              final String text = computeAdbStatus(app);
              activity.runOnUiThread(
                  () -> {
                    if (request != adbStatusRequest
                        || status == null
                        || getView() != page
                        || !isAdded()) return;
                    status.setText(com.deepseekharness.app.util.UiText.status(text));
                    if (text.startsWith("环境任务进行中") && isResumed())
                      status.postDelayed(
                          () -> {
                            if (request == adbStatusRequest && getView() == page && isResumed())
                              refreshAdbStatus(status);
                          },
                          1500);
                  });
            },
            "adb-status")
        .start();
  }

  private String computeAdbStatus(Context app) {
    try {
      if (!pref(app, "adb_enabled", false)) return UiText.text("ADB 已关闭");
      String bridge = DeviceBridgeService.adbState;
      String detail = DeviceBridgeService.adbDetail == null ? "" : DeviceBridgeService.adbDetail;
      HarnessController hc = HarnessController.get(app);
      String st = hc.proot().isEnvironmentReady() ? AdbBridge.status(hc.proot()) : "env:not_ready";
      if (st.startsWith("ENVIRONMENT_BUSY") || "environment_busy".equals(bridge))
        return "环境任务进行中，ADB 暂停检查，完成后自动重试。" + (detail.isEmpty() ? "" : "\n" + detail);
      boolean key = st.contains("key=YES");
      String port = "?";
      int p = st.indexOf("port=");
      if (p >= 0) port = st.substring(p + 5).trim();
      if ("need_pair".equals(bridge)) {
        return UiText.text("配对已失效，请重新配对。");
      } else if ("reconnecting".equals(bridge)) {
        return UiText.format("正在重连无线调试…%s", (detail.isEmpty() ? "" : "\n" + detail));
      } else if ("connected".equals(bridge)) {
        return UiText.format("连接已验证 · 端口 %s%s", port, (detail.isEmpty() ? "" : "\n" + detail));
      } else if (key) {
        return UiText.format("配对密钥已保存，连接尚未验证。%s", (detail.isEmpty() ? "" : "\n" + detail));
      } else {
        return UiText.choose(
            "尚未配对：点「配对」，配对后还会验证连接。",
            "Not paired: tap Pair; the connection is verified after pairing.");
      }
    } catch (Throwable e) {
      return UiText.format("ADB 状态读取失败：%s", e.getMessage());
    }
  }
}
