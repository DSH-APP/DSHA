package com.deepseekharness.app.ui;

import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.os.Environment;
import android.provider.Settings;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.CompoundButton;
import android.widget.TextView;
import android.widget.Toast;
import androidx.fragment.app.Fragment;
import com.deepseekharness.app.DshaAccessibilityService;
import com.deepseekharness.app.R;
import com.deepseekharness.app.RootShell;
import com.deepseekharness.app.ShizukuShell;
import com.deepseekharness.app.core.ConfigStore;
import com.deepseekharness.app.core.DeviceGrants;
import com.deepseekharness.app.core.HarnessController;
import com.deepseekharness.app.util.Constants;
import com.deepseekharness.app.util.GrantSummary;
import com.deepseekharness.app.util.ScreenModeFeedback;
import com.deepseekharness.app.util.UiText;
import com.google.android.material.materialswitch.MaterialSwitch;

/**
 * 设备能力授权的首页：全局确认、系统权限与独立敏感能力。
 *
 * <p>设备执行通道与屏幕操作已各自独立成页（{@link DeviceChannelsFragment} /
 * {@link ScreenOperationFragment}），本页只保留两行入口与状态摘要；入口状态来自
 * {@link GrantSummary} 的只读判据。
 */
public final class DeviceGrantsFragment extends Fragment {
  private MaterialSwitch sms;
  private TextView smsStatus;
  private MaterialSwitch privilegedExecution;
  private TextView clipboardTitle, clipboardStatus;
  private android.widget.RadioGroup clipboardModes;
  private android.widget.RadioButton[] clipboardModeButtons;
  private TextView channelsEntryStatus, screenEntryStatus;

  private boolean updating;
  private int columns = 1;

  /** 折叠披露状态：旋转/切页/每秒刷新都保留；用户折叠后不再被默认展开覆盖。 */
  private DisclosureCard.Memory disclosure = new DisclosureCard.Memory();

  /** B2：同屏折叠说明只允许一段展开；仍走 DisclosureCard 的后果行与读屏语义。 */
  private Accordion accordion;

  private FullPermissionControls fullPermissions;
  private java.util.concurrent.ExecutorService safExecutor;
  private int safStatusGeneration;

  private final android.os.Handler main = new android.os.Handler(android.os.Looper.getMainLooper());
  private final Runnable refreshEntriesRunnable =
      new Runnable() {
        @Override
        public void run() {
          if (!isResumed() || getView() == null) return;
          refreshEntries();
          if (fullPermissions != null) fullPermissions.refresh();
          main.postDelayed(this, 1000);
        }
      };

  @Override
  public View onCreateView(LayoutInflater inflater, ViewGroup container, Bundle saved) {
    View view = inflater.inflate(R.layout.fragment_device_grants, container, false);
    PressFeedback.attachButtons(view);
    safExecutor = java.util.concurrent.Executors.newSingleThreadExecutor();
    Context ctx = requireContext();
    columns = 1;
    ((TextView) view.findViewById(R.id.grants_global_label))
        .setText(UiText.choose("全局权限", "Global permission"));
    if (saved != null) disclosure = DisclosureCard.Memory.restore(saved, "disclosure");
    accordion = new Accordion(disclosure);
    fullPermissions =
        new FullPermissionControls(
            this,
            view.findViewById(R.id.grants_global_slot),
            accordion.state(),
            this::refreshNativePolicyControls,
            () -> accordion.onToggle("global-confirm"));
    accordion.register("global-confirm", () -> fullPermissions.refresh());
    // 设备执行通道 / 屏幕操作改为入口：两个子页各自持有原卡片的控件与确认流程。
    channelsEntryStatus = view.findViewById(R.id.channels_entry_status);
    screenEntryStatus = view.findViewById(R.id.screen_entry_status);
    // 长译文（320dp / 1.3 倍字体）允许状态换行，不靠省略号截断入口摘要。
    for (TextView status : new TextView[] {channelsEntryStatus, screenEntryStatus}) {
      status.setMaxLines(3);
      status.setEllipsize(null);
    }
    view.findViewById(R.id.channels_entry)
        .setOnClickListener(
            row -> SettingsPanes.open(this, new DeviceChannelsFragment(), "device-channels"));
    view.findViewById(R.id.screen_entry)
        .setOnClickListener(
            row -> SettingsPanes.open(this, new ScreenOperationFragment(), "screen-operation"));
    // 全部文件访问：整行与状态词都直接打开系统「所有文件访问」页（与其它系统权限行一致）。
    view.findViewById(R.id.config_all_files).setOnClickListener(v -> openAllFilesAccess(ctx));
    // 跨应用文件访问：授权管理在子页，行上只报数量与失效数。
    ((TextView) view.findViewById(R.id.config_saf_access_title))
        .setText(UiText.choose("跨应用文件访问", "Cross-app files"));
    view.findViewById(R.id.config_saf_access)
        .setOnClickListener(
            button -> SettingsPanes.open(this, new SafAccessFragment(), "saf-access"));
    view.findViewById(R.id.config_battery_opt).setOnClickListener(button -> openBatteryOpt(ctx));
    // 电池优化行只留标题，说明收进系统设置页。
    View batteryHint = view.findViewById(R.id.config_battery_opt_hint);
    if (batteryHint != null) batteryHint.setVisibility(View.GONE);
    sms = view.findViewById(R.id.device_sms);
    smsStatus = view.findViewById(R.id.device_sms_status);
    sms.setOnCheckedChangeListener(
        (button, enabled) -> {
          if (updating) return;
          updateSms();
          if (!enabled) {
            saveSms(false);
            return;
          }
          new DshaDialogBuilder(ctx, true)
              .setTitle(UiText.text("持续允许读取短信？"))
              .setMessage(
                  UiText.text(
                      "当前 DSHA 环境内的助手和插件将能通过已授权的 root 或 ADB 查询短信，包括正文、号码和可能存在的验证码。\n\n后续短信查询不再逐条询问。关闭后立即阻止新查询。"))
              .setPositiveButton(UiText.text("允许短信读取"), (dialog, which) -> saveSms(true))
              .setNegativeButton(UiText.text("取消"), null)
              .show();
        });
    updateSms();
    privilegedExecution = view.findViewById(R.id.device_privileged_execution);
    if (privilegedExecution != null) {
      privilegedExecution.setText(UiText.choose("特权执行（实验）", "Privileged input (experimental)"));
      privilegedExecution.setChecked(new DeviceGrants(ctx).privilegedExecutionAllowed());
      privilegedExecution.setOnCheckedChangeListener(
          (button, enabled) -> {
            if (updating) return;
            if (!enabled) {
              savePrivilegedExecution(false);
              return;
            }
            new DshaDialogBuilder(ctx, true)
                .setTitle(UiText.choose("允许特权执行？", "Allow privileged input?"))
                .setMessage(
                    UiText.choose(
                        "实体屏助手可以绕过无障碍，直接抓取屏幕并在当前手机任务期间注入点击、滑动和按键，用于游戏等自绘界面。\n\n默认关闭、可随时关闭；动作还要求屏幕操作确认已允许目标应用，密码、支付和系统安全界面一律拒绝。实验阶段只保证 root 通道。",
                        "The physical-screen assistant can bypass accessibility, capture the screen and inject taps, swipes and keys while a phone task is active — for games and other self-drawn UIs.\n\nOff by default and revocable at any time. Actions still need screen-control permission for the target app; password, payment and system-security screens are always refused. The experimental phase only guarantees the root channel."))
                .setPositiveButton(
                    UiText.choose("允许特权执行", "Allow privileged input"),
                    (dialog, which) -> savePrivilegedExecution(true))
                .setNegativeButton(UiText.text("取消"), null)
                .show();
          });
    }
    addClipboardControls(ctx, view);
    CompoundButton sensors = view.findViewById(R.id.config_cap_sensors);
    sensors.setChecked(pref(ctx, "cap_sensors", false));
    sensors.setOnCheckedChangeListener(
        (button, enabled) -> {
          if (!updating) savePreference("cap_sensors", enabled);
        });
    CompoundButton location = view.findViewById(R.id.config_cap_location);
    location.setChecked(pref(ctx, "cap_location", false));
    location.setOnCheckedChangeListener(
        (button, enabled) -> {
          if (updating) return;
          if (fullPermissions != null) fullPermissions.cancelForLocationChange();
          savePreference("cap_location", enabled);
          if (enabled
              && ctx.checkSelfPermission(android.Manifest.permission.ACCESS_COARSE_LOCATION)
                  != android.content.pm.PackageManager.PERMISSION_GRANTED)
            requestPermissions(
                new String[] {
                  android.Manifest.permission.ACCESS_FINE_LOCATION,
                  android.Manifest.permission.ACCESS_COARSE_LOCATION
                },
                104);
        });
    adaptColumns(view);
    refreshEntries();
    return view;
  }

  /** 入口状态摘要：通道 = Root 档位 + Shizuku 状态；屏幕 = 无障碍 + 暂停 + 确认方式。 */
  private void refreshEntries() {
    if (!isAdded() || channelsEntryStatus == null || screenEntryStatus == null) return;
    Context ctx = requireContext();
    GrantSummary.Item root =
        GrantSummary.root(
            RootShell.enabled(ctx), RootShell.present(), new ConfigStore(ctx).rootPolicyMode());
    GrantSummary.Item shizuku = GrantSummary.shizuku(ShizukuShell.step(ctx));
    String channels = UiText.format("%s · %s", summaryText(root.label), summaryText(shizuku.label));
    grantSummary(channelsEntryStatus, channels, worstTone(root.tone, shizuku.tone));
    channelsEntryStatus.setContentDescription(
        UiText.format("%s: %s", UiText.text("设备执行通道"), channels));

    GrantSummary.Item screen = summaryScreen(ctx);
    String screenText = summaryText(screen.label);
    grantSummary(screenEntryStatus, screenText, screen.tone);
    screenEntryStatus.setContentDescription(
        UiText.format("%s: %s", UiText.text("屏幕操作"), screenText));
  }

  private void grantSummary(TextView view, String text, GrantSummary.Tone tone) {
    UiMotion.crossFadeText(view, text);
    view.setTextColor(requireContext().getColor(summaryTone(tone)));
  }

  /** 两个通道状态合在一起时取更差的一档：红 > 橙 > 中性 > 绿。 */
  private static GrantSummary.Tone worstTone(GrantSummary.Tone first, GrantSummary.Tone second) {
    return rank(first) >= rank(second) ? first : second;
  }

  private static int rank(GrantSummary.Tone tone) {
    return switch (tone) {
      case BAD -> 3;
      case WARN -> 2;
      case NEUTRAL -> 1;
      case GOOD -> 0;
    };
  }

  private static GrantSummary.Item summaryScreen(Context ctx) {
    boolean enabled = "YES".equals(DshaAccessibilityService.enabledState(ctx));
    return GrantSummary.screen(
        enabled,
        enabled && DshaAccessibilityService.connected(),
        com.deepseekharness.app.HttpShellService.screenPaused(),
        new DeviceGrants(ctx).screenPolicy().mode);
  }

  private static int summaryTone(GrantSummary.Tone tone) {
    return switch (tone) {
      case GOOD -> R.color.shell_good;
      case WARN -> R.color.shell_warn;
      case BAD -> R.color.shell_bad;
      case NEUTRAL -> R.color.shell_text_muted;
    };
  }

  /** 入口状态词映射；包内可见，供 DeviceGrantsLayoutTest 直接核对十语种词义。 */
  static String summaryText(GrantSummary.Label label) {
    return switch (label) {
      case GLOBAL_ON -> UiText.choose("已开启", "On");
      case GLOBAL_OFF -> ScreenModeFeedback.offStateWord();
      case GLOBAL_ATTENTION -> UiText.choose("需处理", "Check");
      case GLOBAL_UPDATING -> UiText.choose("更新中", "Updating");
      case ROOT_OFF -> UiText.choose("已关闭", "Off");
      case ROOT_NO_SU -> UiText.choose("未找到 su", "No su");
      case ROOT_STRICT -> UiText.choose("严格", "Strict");
      case ROOT_RELAXED -> UiText.choose("放宽", "Relaxed");
      case ROOT_FULL -> UiText.choose("完全放宽", "Fully relaxed");
      case SHIZUKU_READY -> UiText.choose("已连接", "Connected");
      case SHIZUKU_NOT_AUTHORIZED -> UiText.choose("未授权", "Not authorized");
      case SHIZUKU_BINDING -> UiText.choose("连接中", "Connecting");
      case SHIZUKU_FAILED -> UiText.choose("连接失败", "Failed");
      case SHIZUKU_NOT_RUNNING -> UiText.choose("未运行", "Not running");
      case SCREEN_NOT_SET -> UiText.choose("未设置", "Not set up");
      case SCREEN_CONNECTING -> UiText.choose("连接中", "Connecting");
      case SCREEN_PAUSED -> UiText.choose("已暂停", "Paused");
      case SCREEN_MANUAL -> UiText.choose("全部手动", "Ask always");
      case SCREEN_CUSTOM -> UiText.choose("按应用", "By app");
      case SCREEN_ALLOW_ALL -> UiText.choose("全部允许", "Allow all");
    };
  }

  // ===== 平板 / 宽窗格：按实际宽度分栏 =====

  /**
   * 内容宽 ≥640dp 时把敏感能力移到右栏，并把内容收进上限宽度居中；回到窄宽度时按
   * 手机顺序（全局 / 入口 / 系统 / 敏感）放回左栏。只在列数变化时搬动视图。
   */
  private void adaptColumns(View page) {
    View scroll = page.findViewById(R.id.grants_scroll);
    View content = page.findViewById(R.id.grants_content);
    int base = getResources().getDimensionPixelSize(R.dimen.grants_page_pad);
    scroll.addOnLayoutChangeListener(
        (v, l, t, r, b, ol, ot, or, ob) -> {
          if (r - l == or - ol) return;
          float density = getResources().getDisplayMetrics().density;
          int widthDp = Math.round((r - l) / density);
          int baseDp = Math.round(base / density);
          int side = Math.round(GrantSummary.sidePadding(widthDp, baseDp) * density);
          int wanted = GrantSummary.columns(widthDp - 2 * baseDp);
          v.post(
              () -> {
                if (getView() != page) return;
                if (side != content.getPaddingStart())
                  content.setPaddingRelative(
                      side, content.getPaddingTop(), side, content.getPaddingBottom());
                if (wanted != columns) applyColumns(page, wanted);
              });
        });
  }

  private void applyColumns(View page, int count) {
    columns = count;
    android.view.ViewGroup start = page.findViewById(R.id.grants_col_start);
    android.view.ViewGroup end = page.findViewById(R.id.grants_col_end);
    int[] order =
        count == 2
            ? new int[] {
              R.id.grants_section_global, R.id.grants_section_entries, R.id.grants_section_system
            }
            : new int[] {
              R.id.grants_section_global,
              R.id.grants_section_entries,
              R.id.grants_section_system,
              R.id.grants_section_sensitive
            };
    int[] right = count == 2 ? new int[] {R.id.grants_section_sensitive} : new int[0];
    place(page, start, order);
    place(page, end, right);
    end.setVisibility(count == 2 ? View.VISIBLE : View.GONE);
  }

  /** 按给定顺序把分组放进栏内；每栏第一个分组不留顶部间距，保证两栏顶端对齐。 */
  private void place(View page, android.view.ViewGroup column, int[] ids) {
    int gap = getResources().getDimensionPixelSize(R.dimen.grants_section_gap);
    for (int i = 0; i < ids.length; i++) {
      View section = page.findViewById(ids[i]);
      if (section.getParent() != null)
        ((android.view.ViewGroup) section.getParent()).removeView(section);
      android.widget.LinearLayout.LayoutParams params =
          new android.widget.LinearLayout.LayoutParams(-1, -2);
      params.topMargin = i == 0 ? 0 : gap;
      column.addView(section, params);
    }
  }

  private void refreshNativePolicyControls() {
    View page = getView();
    if (page == null || !isAdded()) return;
    updating = true;
    ((CompoundButton) page.findViewById(R.id.config_cap_sensors))
        .setChecked(pref(requireContext(), "cap_sensors", false));
    ((CompoundButton) page.findViewById(R.id.config_cap_location))
        .setChecked(pref(requireContext(), "cap_location", false));
    updating = false;
    updateSms();
  }

  private void addClipboardControls(Context ctx, View page) {
    clipboardTitle = page.findViewById(R.id.device_clipboard_title);
    clipboardStatus = page.findViewById(R.id.device_clipboard_status);
    clipboardModes = page.findViewById(R.id.device_clipboard_modes);
    clipboardTitle.setText(UiText.choose("读剪贴板", "Read clipboard"));
    clipboardTitle.setContentDescription(
        UiText.choose(
            "读剪贴板：允许助手读取系统剪贴板内容",
            "Read clipboard: let the assistant read system clipboard content"));
    String[] names = {
      ScreenModeFeedback.offStateWord(),
      UiText.choose("每次询问", "Ask each time"),
      UiText.choose("预授权", "Pre-authorized")
    };
    String[] spoken = {
      UiText.choose("关闭，读取会被阻止", "Off, reads are blocked"),
      UiText.choose("每次读取前询问", "Ask before each read"),
      UiText.choose("直接读取，可随时撤销", "Read directly, revocable at any time")
    };
    var modes = com.deepseekharness.app.util.ClipboardReadPolicy.Mode.values();
    clipboardModeButtons =
        GrantsUi.buildSegment(
            ctx,
            clipboardModes,
            names,
            spoken,
            new int[] {R.color.shell_good, R.color.shell_warn, R.color.shell_accent},
            index -> pickClipboardMode(modes[index]));
    refreshClipboardMode();
  }

  private void pickClipboardMode(com.deepseekharness.app.util.ClipboardReadPolicy.Mode mode) {
    if (!isAdded()) return;
    var grants = new DeviceGrants(requireContext());
    var current = grants.clipboardReadMode();
    refreshClipboardMode();
    if (current == mode) return;
    if (mode == com.deepseekharness.app.util.ClipboardReadPolicy.Mode.ALLOW) {
      new DshaDialogBuilder(requireContext(), true)
          .setTitle(UiText.text("持续允许读取剪贴板？"))
          .setMessage(
              UiText.choose(
                  "助手和插件可读取当前系统剪贴板内容。DSHA 不把内容写入桥日志，但剪贴板本身可能含有密码、验证码或私人信息。之后可在此页撤销。",
                  "The assistant and plugins may read the system clipboard. DSHA does not write the content to bridge logs, but the clipboard may contain passwords, codes, or private data. Revoke this here at any time."))
          .setPositiveButton(
              UiText.choose("预授权读取", "Pre-authorize reads"),
              (dialog, which) -> saveClipboardMode(mode))
          .setNegativeButton(UiText.choose("取消", "Cancel"), null)
          .show();
    } else saveClipboardMode(mode);
  }

  private void saveClipboardMode(com.deepseekharness.app.util.ClipboardReadPolicy.Mode mode) {
    boolean saved = new DeviceGrants(requireContext()).setClipboardReadMode(mode);
    refreshClipboardMode();
    if (!saved) toast(UiText.text("授权保存失败，请重试"));
  }

  private void refreshClipboardMode() {
    if (clipboardModes == null || !isAdded()) return;
    var mode = new DeviceGrants(requireContext()).clipboardReadMode();
    var modes = com.deepseekharness.app.util.ClipboardReadPolicy.Mode.values();
    updating = true;
    for (int i = 0; i < clipboardModeButtons.length; i++)
      clipboardModeButtons[i].setChecked(modes[i] == mode);
    updating = false;
    clipboardStatus.setText(
        switch (mode) {
          case OFF -> UiText.choose("已关闭", "Off");
          case ASK -> UiText.choose("每次读取前询问", "Ask before each read");
          case ALLOW -> UiText.choose("已预授权 · 可随时撤销", "Pre-authorized · revocable");
        });
    clipboardStatus.setTextColor(
        requireContext()
            .getColor(
                mode == com.deepseekharness.app.util.ClipboardReadPolicy.Mode.ALLOW
                    ? R.color.primary
                    : R.color.text_muted));
  }

  private void saveSms(boolean enabled) {
    boolean saved = new DeviceGrants(requireContext()).setSmsReadAllowed(enabled);
    updateSms();
    toast(
        !saved
            ? UiText.text("授权保存失败，请重试")
            : enabled ? UiText.text("短信查询已预授权") : UiText.choose("已关闭短信读取", "SMS reading disabled"));
  }

  private void updateSms() {
    if (sms == null || !isAdded()) return;
    boolean allowed = new DeviceGrants(requireContext()).smsReadAllowed();
    updating = true;
    sms.setChecked(allowed);
    updating = false;
    smsStatus.setText(
        allowed ? UiText.text("已预授权 · 关闭可撤销") : UiText.choose("已关闭", "Off · queries are blocked"));
    smsStatus.setTextColor(
        requireContext().getColor(allowed ? R.color.primary : R.color.text_muted));
  }

  @Override
  public void onRequestPermissionsResult(int request, String[] permissions, int[] results) {
    super.onRequestPermissionsResult(request, permissions, results);
    if (request != 104 || !isAdded()) return;
    boolean granted =
        requireContext().checkSelfPermission(android.Manifest.permission.ACCESS_COARSE_LOCATION)
                == android.content.pm.PackageManager.PERMISSION_GRANTED
            || requireContext()
                    .checkSelfPermission(android.Manifest.permission.ACCESS_FINE_LOCATION)
                == android.content.pm.PackageManager.PERMISSION_GRANTED;
    if (!granted) {
      savePreference("cap_location", false);
      if (getView() != null)
        ((CompoundButton) getView().findViewById(R.id.config_cap_location)).setChecked(false);
      new DshaDialogBuilder(requireContext())
          .setTitle(UiText.choose("需要系统定位权限", "Location permission required"))
          .setMessage(
              UiText.choose(
                  "位置读取保持关闭。请在系统应用权限中允许定位，再返回这里开启；如果系统定位服务关闭，也需要先开启系统定位。",
                  "Location reading remains off. Allow location in system app permissions, then return and enable it here. System location services must also be enabled."))
          .setNegativeButton(UiText.choose("暂不开启", "Not now"), null)
          .setPositiveButton(
              UiText.choose("打开系统权限设置", "Open app permissions"),
              (dialog, which) -> {
                try {
                  startActivity(
                      new Intent(
                          Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                          android.net.Uri.parse("package:" + requireContext().getPackageName())));
                } catch (RuntimeException missing) {
                  toast(UiText.choose("去设置", "Open DSHA permissions in system settings"));
                }
              })
          .show();
    }
  }

  /**
   * 跨应用文件访问：副标题只报数量；具体目录、可写开关与撤销都在子页。
   *
   * <p>每个授权都可能要跨进程问目标 Provider 的目录名，必须在后台线程查：慢/离线应用
   * 不能把设置页主线程拖成 ANR。只有最新一代结果能落到界面上。
   */
  private void refreshSafAccessStatus(TextView view) {
    if (view == null) return;
    java.util.concurrent.ExecutorService running = safExecutor;
    if (running == null) return;
    int generation = ++safStatusGeneration;
    android.content.Context app = requireContext().getApplicationContext();
    running.execute(
        () -> {
          var grants = com.deepseekharness.app.saf.SafGrants.list(app);
          String text;
          if (grants.isEmpty()) {
            text = UiText.choose("未授权", "Not granted");
          } else {
            int valid = 0, writable = 0, stale = 0;
            for (var grant : grants) {
              if (!grant.valid) {
                stale++;
                continue;
              }
              valid++;
              if (grant.writable) writable++;
            }
            text =
                stale > 0
                    ? UiText.format(
                        UiText.choose(
                            "已授权 %d 个，%d 个已失效：点此处理", "%d granted, %d expired: tap to review"),
                        valid,
                        stale)
                    : UiText.format(
                        UiText.choose(
                            "已授权 %d 个文件夹（可写 %d 个）：点此管理",
                            "%d folders granted (%d writable): tap to manage"),
                        valid,
                        writable);
          }
          String result = text;
          main.post(
              () -> {
                if (generation != safStatusGeneration || getView() == null) return;
                view.setText(result);
              });
        });
  }

  /** 特权执行开关：保存失败恢复原状态；关闭时立即丢弃 Core 连接（Core 自身空闲退出）。 */
  private void savePrivilegedExecution(boolean allowed) {
    boolean saved = new DeviceGrants(requireContext()).setPrivilegedExecutionAllowed(allowed);
    if (!saved) {
      updating = true;
      if (privilegedExecution != null) privilegedExecution.setChecked(!allowed);
      updating = false;
      Toast.makeText(
              requireContext(),
              UiText.choose("保存特权执行设置失败", "Could not save the privileged input setting"),
              Toast.LENGTH_SHORT)
          .show();
      return;
    }
    if (!allowed) {
      try {
        com.deepseekharness.app.vscreen.PhysicalScreenManager.from(requireContext()).revoke();
      } catch (RuntimeException ignored) {
        // 关闭能力本身已生效；丢弃连接失败不影响下一次请求的 fail-closed 判断。
      }
    }
  }

  @Override
  public void onResume() {
    super.onResume();
    refreshNativePolicyControls();
    if (fullPermissions != null) fullPermissions.resume();
    // 入口摘要要读取 Shizuku 通道状态：恢复页面时重新绑定一次（通道页内还会各自保活）。
    ShizukuShell.ensureBound(requireContext());
    updateSms();
    if (privilegedExecution != null) {
      updating = true;
      privilegedExecution.setChecked(
          new DeviceGrants(requireContext()).privilegedExecutionAllowed());
      updating = false;
    }
    refreshEntries();
    main.removeCallbacks(refreshEntriesRunnable);
    main.post(refreshEntriesRunnable);
    View page = getView();
    if (page != null) {
      refreshAllFilesStatus(page.findViewById(R.id.config_all_files_status));
      refreshSafAccessStatus(page.findViewById(R.id.config_saf_access_status));
    }
    refreshClipboardMode();
  }

  @Override
  public void onPause() {
    if (fullPermissions != null) fullPermissions.pause();
    main.removeCallbacks(refreshEntriesRunnable);
    super.onPause();
  }

  @Override
  public void onSaveInstanceState(@androidx.annotation.NonNull Bundle outState) {
    super.onSaveInstanceState(outState);
    disclosure.save(outState, "disclosure");
  }

  @Override
  public void onDestroyView() {
    if (fullPermissions != null) {
      fullPermissions.close();
      fullPermissions = null;
    }
    sms = null;
    smsStatus = null;
    privilegedExecution = null;
    clipboardTitle = null;
    clipboardStatus = null;
    clipboardModes = null;
    clipboardModeButtons = null;
    channelsEntryStatus = null;
    screenEntryStatus = null;
    safStatusGeneration++;
    java.util.concurrent.ExecutorService running = safExecutor;
    safExecutor = null;
    if (running != null) running.shutdownNow();
    super.onDestroyView();
  }

  private boolean pref(Context ctx, String key, boolean fallback) {
    return ctx.getSharedPreferences(Constants.PREFS, 0).getBoolean(key, fallback);
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

  private void toast(String value) {
    if (getContext() != null) Toast.makeText(getContext(), value, Toast.LENGTH_LONG).show();
  }

  private void openBatteryOpt(Context ctx) {
    openBatterySettings(ctx, this::startActivity, () -> toast(UiText.text("无法打开电池优化设置")));
  }

  /**
   * 只在用户点击后导航到系统设置；不申请或自动修改电池优化白名单。
   *
   * <p>ColorOS 16 的「耗电管理」页（PowerControlActivity）不对外导出，只能先打开本应用「关于 /
   * 应用详情」，再由用户点「耗电管理」。AOSP 的应用电池使用情况页作为次选。
   */
  static void openBatterySettings(
      Context context, java.util.function.Consumer<Intent> open, Runnable failed) {
    android.net.Uri app = android.net.Uri.parse("package:" + context.getPackageName());
    try {
      open.accept(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, app));
      return;
    } catch (RuntimeException unavailable) {
      // 部分 ROM 拦截应用详情。
    }
    try {
      open.accept(new Intent("android.settings.VIEW_ADVANCED_POWER_USAGE_DETAIL", app));
      return;
    } catch (RuntimeException unavailable) {
      // 没有应用电池页时再退回总名单。
    }
    try {
      open.accept(new Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS));
    } catch (RuntimeException unavailable) {
      failed.run();
    }
  }

  /** 「所有文件访问」入口：Android 11+ 跳系统 MANAGE 设置；Android 6-10 请求 WRITE_EXTERNAL_STORAGE。 */
  private void openAllFilesAccess(Context ctx) {
    if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
      try {
        Intent i =
            new Intent(
                Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                android.net.Uri.parse("package:" + ctx.getPackageName()));
        startActivity(i);
      } catch (Throwable e) {
        try {
          startActivity(new Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION));
        } catch (Throwable e2) {
          toast(UiText.format("打开设置失败：%s", e2.getMessage()));
        }
      }
      return;
    }
    if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.M) {
      // Android 6-10：运行时请求 WRITE_EXTERNAL_STORAGE（Android 10 作用域存储下尽力而为）
      if (ctx.checkSelfPermission(android.Manifest.permission.WRITE_EXTERNAL_STORAGE)
          != android.content.pm.PackageManager.PERMISSION_GRANTED) {
        requestPermissions(new String[] {android.Manifest.permission.WRITE_EXTERNAL_STORAGE}, 501);
      } else {
        toast(UiText.text("存储权限已授予，容器可访问手机存储"));
      }
      return;
    }
    toast(UiText.text("当前系统无需存储权限"));
  }

  /** 刷新「所有文件访问权限」状态行（含从系统设置返回后的更新）。 */
  private void refreshAllFilesStatus(TextView status) {
    if (status == null) return;
    boolean granted;
    if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
      granted = Environment.isExternalStorageManager();
    } else if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.M) {
      granted =
          requireContext().checkSelfPermission(android.Manifest.permission.WRITE_EXTERNAL_STORAGE)
              == android.content.pm.PackageManager.PERMISSION_GRANTED;
    } else {
      granted = true;
    }
    status.setText(granted ? UiText.choose("已开启", "On") : UiText.choose("未开启", "Off"));
    try {
      status.setTextColor(
          granted
              // 调色板色经主题属性引用：必须带页面主题取色，不能传 null。
              ? requireContext().getColor(R.color.primary)
              : requireContext().getColor(R.color.err));
    } catch (Throwable ignored) {
    }
  }
}
