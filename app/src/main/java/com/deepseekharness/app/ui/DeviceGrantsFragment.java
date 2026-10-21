package com.deepseekharness.app.ui;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
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
import com.deepseekharness.app.DeviceBridgeService;
import com.deepseekharness.app.DshaAccessibilityService;
import com.deepseekharness.app.R;
import com.deepseekharness.app.RootShell;
import com.deepseekharness.app.ShizukuShell;
import com.deepseekharness.app.bridge.AdbBridge;
import com.deepseekharness.app.core.ConfigStore;
import com.deepseekharness.app.core.DeviceGrants;
import com.deepseekharness.app.core.HarnessController;
import com.deepseekharness.app.util.Constants;
import com.google.android.material.materialswitch.MaterialSwitch;

/** 设备通道、系统权限与能力预授权的唯一设置页；沿用历史偏好键。 */
public final class DeviceGrantsFragment extends Fragment {
  private MaterialSwitch sms;
  private TextView smsStatus;
  private TextView screenPolicyStatus;
  private android.widget.Button screenPause;
  private android.widget.Button screenRules, screenRevoke;
  private android.widget.RadioGroup screenModes;
  private boolean updating;
  private int adbStatusRequest;
  private FullPermissionControls fullPermissions;
  private final android.os.Handler main = new android.os.Handler(android.os.Looper.getMainLooper());
  private final Runnable refreshConnections =
      new Runnable() {
        @Override
        public void run() {
          if (!isResumed() || getView() == null) return;
          refreshChannelLabels();
          if (fullPermissions != null) fullPermissions.refresh();
          main.postDelayed(this, 1000);
        }
      };

  @Override
  public View onCreateView(LayoutInflater inflater, ViewGroup container, Bundle saved) {
    View view = inflater.inflate(R.layout.fragment_device_grants, container, false);
    Context ctx = requireContext();
    fullPermissions =
        new FullPermissionControls(
            this,
            (android.widget.LinearLayout) ((android.widget.ScrollView) view).getChildAt(0),
            this::refreshNativePolicyControls);
    for (int id :
        new int[] {
          R.id.device_root_status, R.id.workspace_shizuku_status, R.id.config_adb_status
        }) {
      TextView detail = view.findViewById(id);
      detail.setOnClickListener(
          v ->
              CardSheet.show(
                  requireContext(),
                  com.deepseekharness.app.util.UiText.choose("设备通道状态", "Device channel status"),
                  detail.getText().toString()));
    }
    ((TextView) view.findViewById(R.id.computer_use_hint))
        .setText(
            com.deepseekharness.app.util.UiText.choose(
                "让助手读屏、点击和输入。先完成屏幕操作设置，再在对话中描述任务。",
                "Let the assistant observe, tap and type. Set up screen control, then describe the task in a conversation."));
    view.findViewById(R.id.computer_use_accessibility)
        .setOnClickListener(
            button ->
                startActivity(new Intent(requireContext(), AccessibilitySetupActivity.class)));
    android.widget.LinearLayout screenCard =
        (android.widget.LinearLayout) view.findViewById(R.id.computer_use_hint).getParent();
    addScreenPolicyControls(ctx, screenCard);
    android.widget.Button revoke = new androidx.appcompat.widget.AppCompatButton(ctx);
    screenRevoke = revoke;
    revoke.setText(
        com.deepseekharness.app.util.UiText.choose(
            "暂停屏幕操作并撤销预授权", "Pause screen actions and revoke preauthorizations"));
    revoke.setTextSize(12);
    revoke.setAllCaps(false);
    revoke.setBackgroundResource(R.drawable.bg_btn);
    screenCard.addView(revoke, new android.widget.LinearLayout.LayoutParams(-1, -2));
    revoke.setOnClickListener(
        button -> {
          boolean persisted =
              new DeviceGrants(ctx)
                  .setScreenPolicy(
                      com.deepseekharness.app.util.ScreenConfirmationPolicy.Mode.MANUAL,
                      java.util.Map.of());
          com.deepseekharness.app.HttpShellService.setScreenPaused(ctx, true);
          com.deepseekharness.app.HttpShellService.revokeScreenGrant(ctx);
          refreshScreenPolicy();
          toast(
              persisted
                  ? com.deepseekharness.app.util.UiText.choose(
                      "已暂停屏幕操作并撤销预授权；可点恢复后继续",
                      "Screen actions are paused and preauthorizations revoked. Use Resume to continue")
                  : com.deepseekharness.app.util.UiText.text("授权保存失败，请重试"));
        });
    android.widget.Button virtualScreen = new androidx.appcompat.widget.AppCompatButton(ctx);
    virtualScreen.setText(
        com.deepseekharness.app.util.UiText.choose("打开虚拟屏", "Open virtual screen"));
    virtualScreen.setAllCaps(false);
    virtualScreen.setTextSize(12);
    virtualScreen.setBackgroundResource(R.drawable.bg_btn);
    virtualScreen.setOnClickListener(
        button ->
            startActivity(
                new Intent(ctx, com.deepseekharness.app.vscreen.VirtualScreenActivity.class)));
    if (com.deepseekharness.app.vscreen.VirtualScreenManager.supported(ctx)) {
      android.widget.TextView virtualHint = new android.widget.TextView(ctx);
      virtualHint.setText(
          com.deepseekharness.app.util.UiText.choose(
              "虚拟屏需要已连接的设备通道。无障碍不会自动创建，请打开虚拟屏后点“创建竖屏”或“创建横屏”；页内有使用说明。",
              "A virtual screen requires a connected device channel. Accessibility does not create it automatically. Open virtual screen, then tap Create portrait or Create landscape. Instructions are available on that page."));
      virtualHint.setTextSize(12);
      virtualHint.setTextColor(ctx.getColor(R.color.text_secondary));
      screenCard.addView(virtualHint, new android.widget.LinearLayout.LayoutParams(-1, -2));
      screenCard.addView(virtualScreen, new android.widget.LinearLayout.LayoutParams(-1, -2));
    }

    CompoundButton root = view.findViewById(R.id.config_root_shell);
    root.setChecked(new ConfigStore(ctx).isRootShellAllowed());
    root.setOnCheckedChangeListener(
        (button, enabled) -> {
          if (updating) return;
          new ConfigStore(ctx).setRootShellAllowed(enabled);
          if (!enabled) com.deepseekharness.app.vscreen.VirtualScreenManager.from(ctx).revoke();
          syncSettings();
          refreshChannelLabels();
        });
    view.findViewById(R.id.device_root_verify)
        .setOnClickListener(
            button -> {
              if (!RootShell.enabled(ctx)) {
                toast(com.deepseekharness.app.util.UiText.text("请先启用「允许 Root Shell」"));
                return;
              }
              if (!RootShell.present()) {
                toast(
                    com.deepseekharness.app.util.UiText.text(
                        "未找到 su；请确认手机已 root，且 root 管理器允许 DSHA 使用"));
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
    CompoundButton adb = view.findViewById(R.id.config_adb_enable);
    adb.setChecked(pref(ctx, "adb_enabled", false));
    adb.setOnCheckedChangeListener(
        (button, enabled) -> {
          if (updating) return;
          com.deepseekharness.app.HttpShellService.smsPolicyChanged(ctx);
          DeviceBridgeService.adbIntentChanging();
          savePreference("adb_enabled", enabled);
          if (!enabled) com.deepseekharness.app.vscreen.VirtualScreenManager.from(ctx).revoke();
          if (enabled) {
            DeviceBridgeService.apply(ctx);
            if (getActivity() instanceof MainActivity)
              ((MainActivity) getActivity()).requestLocalNetwork();
          } else ctx.stopService(new Intent(ctx, DeviceBridgeService.class));
          syncSettings();
          refreshAdbStatus(view.findViewById(R.id.config_adb_status));
        });
    view.findViewById(R.id.config_adb_pair)
        .setOnClickListener(
            button -> {
              if (android.os.Build.VERSION.SDK_INT < 30) {
                new com.deepseekharness.app.ui.DshaDialogBuilder(ctx)
                    .setTitle(com.deepseekharness.app.util.UiText.text("当前系统没有配对码接口"))
                    .setMessage(
                        com.deepseekharness.app.util.UiText.text(
                            "无线调试配对码需要 Android 11+。Android 6—10 可使用 Shizuku、已授权的 root，或由电脑开启 ADB TCP 通道。"))
                    .setPositiveButton(com.deepseekharness.app.util.UiText.text("知道了"), null)
                    .show();
              } else startActivity(new Intent(ctx, AdbPairActivity.class));
            });
    view.findViewById(R.id.config_all_files).setOnClickListener(button -> openAllFilesAccess(ctx));
    view.findViewById(R.id.config_battery_opt).setOnClickListener(button -> openBatteryOpt(ctx));
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
          new com.deepseekharness.app.ui.DshaDialogBuilder(ctx, true)
              .setTitle(com.deepseekharness.app.util.UiText.text("持续允许读取短信？"))
              .setMessage(
                  com.deepseekharness.app.util.UiText.text(
                      "当前 DSHA 环境内的助手和插件将能通过已授权的 root 或 ADB 查询短信，包括正文、号码和可能存在的验证码。\n\n后续短信查询不再逐条询问。关闭后立即阻止新查询。"))
              .setPositiveButton(
                  com.deepseekharness.app.util.UiText.text("允许短信读取"),
                  (dialog, which) -> saveSms(true))
              .setNegativeButton(com.deepseekharness.app.util.UiText.text("取消"), null)
              .show();
        });
    updateSms();
    return view;
  }

  private void addScreenPolicyControls(Context ctx, android.widget.LinearLayout card) {
    screenPolicyStatus = new TextView(ctx);
    screenPolicyStatus.setTextSize(12);
    card.addView(screenPolicyStatus, new android.widget.LinearLayout.LayoutParams(-1, -2));
    screenModes = new android.widget.RadioGroup(ctx);
    String[] labels = {
      com.deepseekharness.app.util.UiText.choose("全部手动", "Confirm every action"),
      com.deepseekharness.app.util.UiText.choose("按应用自定义", "Customize by app"),
      com.deepseekharness.app.util.UiText.choose("全部允许", "Allow all")
    };
    for (int index = 0; index < labels.length; index++) {
      var radio = new androidx.appcompat.widget.AppCompatRadioButton(ctx);
      radio.setId(android.view.View.generateViewId());
      radio.setText(labels[index]);
      radio.setTag(com.deepseekharness.app.util.ScreenConfirmationPolicy.Mode.values()[index]);
      screenModes.addView(radio);
    }
    card.addView(screenModes, new android.widget.LinearLayout.LayoutParams(-1, -2));
    screenModes.setOnCheckedChangeListener(
        (group, checked) -> {
          if (updating) return;
          var selected = group.findViewById(checked);
          if (selected == null) return;
          var grants = new DeviceGrants(ctx);
          if (!grants.setScreenPolicy(
              (com.deepseekharness.app.util.ScreenConfirmationPolicy.Mode) selected.getTag(),
              grants.screenPolicy().rules))
            toast(com.deepseekharness.app.util.UiText.text("授权保存失败，请重试"));
          refreshScreenPolicy();
        });
    var rules = new androidx.appcompat.widget.AppCompatButton(ctx);
    screenRules = rules;
    rules.setText(
        com.deepseekharness.app.util.UiText.choose("编辑应用确认规则", "Edit app confirmation rules"));
    rules.setAllCaps(false);
    rules.setBackgroundResource(R.drawable.bg_btn);
    rules.setOnClickListener(button -> showScreenRules());
    card.addView(rules, new android.widget.LinearLayout.LayoutParams(-1, -2));
    screenPause = new androidx.appcompat.widget.AppCompatButton(ctx);
    screenPause.setAllCaps(false);
    screenPause.setBackgroundResource(R.drawable.bg_btn);
    screenPause.setOnClickListener(
        button -> {
          com.deepseekharness.app.HttpShellService.setScreenPaused(
              ctx, !com.deepseekharness.app.HttpShellService.screenPaused());
          refreshScreenPolicy();
        });
    card.addView(screenPause, new android.widget.LinearLayout.LayoutParams(-1, -2));
    refreshScreenPolicy();
  }

  private void refreshScreenPolicy() {
    if (screenModes == null || !isAdded()) return;
    var policy = new DeviceGrants(requireContext()).screenPolicy();
    boolean global = new DeviceGrants(requireContext()).consentPolicy().globalAllow();
    updating = true;
    for (int i = 0; i < screenModes.getChildCount(); i++) {
      var radio = (android.widget.RadioButton) screenModes.getChildAt(i);
      radio.setChecked(radio.getTag() == policy.mode);
      radio.setEnabled(true);
    }
    updating = false;
    if (screenRules != null) screenRules.setEnabled(true);
    if (screenRevoke != null) screenRevoke.setEnabled(true);
    screenPause.setEnabled(true);
    screenPolicyStatus.setText(
        global
            ? com.deepseekharness.app.util.UiText.choose(
                "全局权限已开启：已授权能力免 DSHA 安全确认。可编辑下列保留规则；关闭全局后按这些规则确认。独立撤销或暂停仍有效。",
                "Global permission is on: authorized capabilities skip DSHA confirmation. You can edit the retained rules below; they apply when global permission is off. Explicit revocation or pause remains effective.")
            : com.deepseekharness.app.util.UiText.choose(
                "仅设置 DSHA 的屏幕确认；不会授予系统权限。全部允许包含敏感界面；自定义中未配置的应用仍询问。规则保存在本机，不随备份迁移。",
                "These rules only control DSHA confirmations and do not grant system permissions. Allow all includes sensitive screens. Unconfigured apps still ask in custom mode. Rules stay on this device and are excluded from backups."));
    screenPause.setText(
        com.deepseekharness.app.HttpShellService.screenPaused()
            ? com.deepseekharness.app.util.UiText.choose(
                "恢复助手屏幕操作", "Resume assistant screen actions")
            : com.deepseekharness.app.util.UiText.choose(
                "暂停助手屏幕操作", "Pause assistant screen actions"));
  }

  private void refreshNativePolicyControls() {
    refreshScreenPolicy();
    View page = getView();
    if (page == null || !isAdded()) return;
    updating = true;
    ((CompoundButton) page.findViewById(R.id.config_root_shell))
        .setChecked(new ConfigStore(requireContext()).isRootShellAllowed());
    ((CompoundButton) page.findViewById(R.id.config_adb_enable))
        .setChecked(pref(requireContext(), "adb_enabled", false));
    ((CompoundButton) page.findViewById(R.id.config_cap_sensors))
        .setChecked(pref(requireContext(), "cap_sensors", false));
    ((CompoundButton) page.findViewById(R.id.config_cap_location))
        .setChecked(pref(requireContext(), "cap_location", false));

    updating = false;
    updateSms();
  }

  private void showScreenRules() {
    var policy = new DeviceGrants(requireContext()).screenPolicy();
    String[] packages = policy.rules.keySet().toArray(new String[0]);
    String[] labels = new String[packages.length];
    for (int i = 0; i < packages.length; i++)
      labels[i] =
          packages[i]
              + " · "
              + (policy.rules.get(packages[i])
                      == com.deepseekharness.app.util.ScreenConfirmationPolicy.Rule.ALLOW
                  ? com.deepseekharness.app.util.UiText.choose("允许", "Allow")
                  : com.deepseekharness.app.util.UiText.choose("询问", "Ask"));
    new DshaDialogBuilder(requireContext())
        .setTitle(com.deepseekharness.app.util.UiText.choose("应用确认规则", "App confirmation rules"))
        .setItems(labels, (dialog, which) -> editScreenRule(packages[which]))
        .setPositiveButton(
            com.deepseekharness.app.util.UiText.choose("添加应用", "Add app"),
            (dialog, which) -> editScreenRule(""))
        .setNegativeButton(com.deepseekharness.app.util.UiText.text("取消"), null)
        .show();
  }

  private void editScreenRule(String existing) {
    var ctx = requireContext();
    var content = new android.widget.LinearLayout(ctx);
    content.setOrientation(android.widget.LinearLayout.VERTICAL);
    var pkg = new androidx.appcompat.widget.AppCompatEditText(ctx);
    pkg.setSingleLine(true);
    pkg.setHint(com.deepseekharness.app.util.UiText.choose("精确应用包名", "Exact app package name"));
    pkg.setText(existing);
    pkg.setEnabled(existing.isEmpty());
    content.addView(pkg);
    var allow = new MaterialSwitch(ctx);
    allow.setText(
        com.deepseekharness.app.util.UiText.choose(
            "允许该应用免确认（含敏感界面）", "Allow this app without confirmation, including sensitive screens"));
    allow.setChecked(
        new DeviceGrants(ctx).screenPolicy().rules.get(existing)
            == com.deepseekharness.app.util.ScreenConfirmationPolicy.Rule.ALLOW);
    content.addView(allow);
    var scroll = new android.widget.ScrollView(ctx);
    scroll.setFillViewport(true);
    scroll.addView(content);
    var builder =
        new DshaDialogBuilder(ctx)
            .setTitle(
                com.deepseekharness.app.util.UiText.choose(
                    "编辑应用确认规则", "Edit app confirmation rules"))
            .setView(scroll)
            .setPositiveButton(
                com.deepseekharness.app.util.UiText.text("保存"),
                (dialog, which) -> {
                  String key = pkg.getText().toString().trim();
                  if (!com.deepseekharness.app.util.ScreenConfirmationPolicy.validPackage(key)) {
                    toast(
                        com.deepseekharness.app.util.UiText.choose(
                            "应用包名无效，规则未保存", "Invalid package name. Rule was not saved"));
                    return;
                  }
                  var grants = new DeviceGrants(ctx);
                  var current = grants.screenPolicy();
                  var rules = new java.util.LinkedHashMap<>(current.rules);
                  rules.put(
                      key,
                      allow.isChecked()
                          ? com.deepseekharness.app.util.ScreenConfirmationPolicy.Rule.ALLOW
                          : com.deepseekharness.app.util.ScreenConfirmationPolicy.Rule.ASK);
                  if (!grants.setScreenPolicy(current.mode, rules))
                    toast(com.deepseekharness.app.util.UiText.text("授权保存失败，请重试"));
                  refreshScreenPolicy();
                })
            .setNegativeButton(com.deepseekharness.app.util.UiText.text("取消"), null);
    if (!existing.isEmpty())
      builder.setNeutralButton(
          com.deepseekharness.app.util.UiText.choose("删除规则", "Remove rule"),
          (dialog, which) -> {
            var grants = new DeviceGrants(ctx);
            var current = grants.screenPolicy();
            var rules = new java.util.LinkedHashMap<>(current.rules);
            rules.remove(existing);
            if (!grants.setScreenPolicy(current.mode, rules))
              toast(com.deepseekharness.app.util.UiText.text("授权保存失败，请重试"));
            refreshScreenPolicy();
          });
    builder.show();
  }

  private void savePreference(String key, boolean enabled) {
    boolean success =
        requireContext()
            .getSharedPreferences(Constants.PREFS, 0)
            .edit()
            .putBoolean(key, enabled)
            .commit();
    if (success && !enabled) com.deepseekharness.app.DeviceSense.revoke(requireContext(), key);
    if (!success) toast(com.deepseekharness.app.util.UiText.text("设置保存失败，请重试"));
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
                        com.deepseekharness.app.util.UiText.format(
                            "原生授权即时生效；%s", result.replaceFirst("^SETTINGS_[A-Z]+: ", "")));
                  });
            },
            "device-settings")
        .start();
  }

  private void verify(View button, TextView status, java.util.function.Supplier<String> probe) {
    final View page = getView();
    button.setEnabled(false);
    status.setText(com.deepseekharness.app.util.UiText.text("正在验证设备通道…"));
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
                    new com.deepseekharness.app.ui.DshaDialogBuilder(requireContext())
                        .setTitle(com.deepseekharness.app.util.UiText.text("设备通道验证"))
                        .setMessage(com.deepseekharness.app.util.UiText.raw(result))
                        .setPositiveButton(com.deepseekharness.app.util.UiText.text("知道了"), null)
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
      toast(com.deepseekharness.app.util.UiText.text("尚未安装 Shizuku，请安装后启动服务"));
      return;
    }
    try {
      startActivity(launch);
    } catch (RuntimeException e) {
      toast(
          com.deepseekharness.app.util.UiText.format(
              "无法打开 Shizuku：%s", e.getClass().getSimpleName()));
    }
  }

  private void refreshChannelLabels() {
    View page = getView();
    if (page == null) return;
    TextView shizuku = page.findViewById(R.id.workspace_shizuku_status);
    TextView root = page.findViewById(R.id.device_root_status);
    if (shizuku.getTag() == null)
      shizuku.setText(
          com.deepseekharness.app.util.UiStateText.render(
              ShizukuShell.userStatus(requireContext())));
    ((TextView) page.findViewById(R.id.workspace_shizuku_auth))
        .setText(
            ShizukuShell.hasPermission()
                ? com.deepseekharness.app.util.UiText.text("验证 Shizuku 连接")
                : com.deepseekharness.app.util.UiText.text("授权 Shizuku"));
    if (root.getTag() == null) root.setText(RootShell.status(requireContext()));
  }

  private void saveSms(boolean enabled) {
    boolean saved = new DeviceGrants(requireContext()).setSmsReadAllowed(enabled);
    updateSms();
    toast(
        !saved
            ? com.deepseekharness.app.util.UiText.text("授权保存失败，请重试")
            : enabled
                ? com.deepseekharness.app.util.UiText.text("短信查询已预授权")
                : com.deepseekharness.app.util.UiText.choose("已关闭短信读取", "SMS reading disabled"));
  }

  private void updateSms() {
    if (sms == null || !isAdded()) return;
    boolean allowed = new DeviceGrants(requireContext()).smsReadAllowed();
    updating = true;
    sms.setChecked(allowed);
    updating = false;
    smsStatus.setText(
        allowed
            ? com.deepseekharness.app.util.UiText.text("已预授权 · 关闭可撤销")
            : com.deepseekharness.app.util.UiText.choose(
                "已关闭 · 查询会被阻止", "Off · queries are blocked"));
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
          .setTitle(
              com.deepseekharness.app.util.UiText.choose(
                  "需要系统定位权限", "Location permission required"))
          .setMessage(
              com.deepseekharness.app.util.UiText.choose(
                  "位置读取保持关闭。请在系统应用权限中允许定位，再返回这里开启；如果系统定位服务关闭，也需要先开启系统定位。",
                  "Location reading remains off. Allow location in system app permissions, then return and enable it here. System location services must also be enabled."))
          .setNegativeButton(com.deepseekharness.app.util.UiText.choose("暂不开启", "Not now"), null)
          .setPositiveButton(
              com.deepseekharness.app.util.UiText.choose("打开系统权限设置", "Open app permissions"),
              (dialog, which) -> {
                try {
                  startActivity(
                      new Intent(
                          Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                          android.net.Uri.parse("package:" + requireContext().getPackageName())));
                } catch (RuntimeException missing) {
                  toast(
                      com.deepseekharness.app.util.UiText.choose(
                          "请在系统设置中打开 DSHA 应用权限", "Open DSHA permissions in system settings"));
                }
              })
          .show();
    }
  }

  @Override
  public void onResume() {
    super.onResume();
    refreshNativePolicyControls();
    if (fullPermissions != null) fullPermissions.resume();
    ShizukuShell.ensureBound(requireContext());
    updateSms();
    main.removeCallbacks(refreshConnections);
    main.post(refreshConnections);
    View page = getView();
    if (page != null) {
      refreshAllFilesStatus(page.findViewById(R.id.config_all_files_status));
      refreshA11yStatus(page.findViewById(R.id.config_a11y_status));
      refreshAdbStatus(page.findViewById(R.id.config_adb_status));
    }
  }

  @Override
  public void onPause() {
    if (fullPermissions != null) fullPermissions.pause();
    main.removeCallbacks(refreshConnections);
    super.onPause();
  }

  @Override
  public void onDestroyView() {
    if (fullPermissions != null) {
      fullPermissions.close();
      fullPermissions = null;
    }
    adbStatusRequest++;
    sms = null;
    smsStatus = null;
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
      if (!pref(app, "adb_enabled", false))
        return com.deepseekharness.app.util.UiText.text("ADB 已关闭。开启后才会保持连接。");
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
        return com.deepseekharness.app.util.UiText.text("配对已失效，请重新配对。");
      } else if ("reconnecting".equals(bridge)) {
        return com.deepseekharness.app.util.UiText.format(
            "正在重连无线调试…%s", (detail.isEmpty() ? "" : "\n" + detail));
      } else if ("connected".equals(bridge)) {
        return com.deepseekharness.app.util.UiText.format(
            "连接已验证 · 端口 %s%s", port, (detail.isEmpty() ? "" : "\n" + detail));
      } else if (key) {
        return com.deepseekharness.app.util.UiText.format(
            "配对密钥已保存，连接尚未验证。%s", (detail.isEmpty() ? "" : "\n" + detail));
      } else {
        return com.deepseekharness.app.util.UiText.text("尚未配对：点下方「ADB 无线配对」，配对后还会验证连接。");
      }
    } catch (Throwable e) {
      return com.deepseekharness.app.util.UiText.format("ADB 状态读取失败：%s", e.getMessage());
    }
  }

  private void openBatteryOpt(Context ctx) {
    try {
      Intent i =
          new Intent(
              Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
              Uri.parse("package:" + ctx.getPackageName()));
      startActivity(i);
    } catch (Exception e) {
      toast(com.deepseekharness.app.util.UiText.text("无法打开电池优化设置"));
    }
  }

  /** 「所有文件访问」入口：Android 11+ 跳系统 MANAGE 设置；Android 6-10 请求 WRITE_EXTERNAL_STORAGE。 */
  private void openAllFilesAccess(Context ctx) {
    if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
      try {
        Intent i =
            new Intent(
                Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                Uri.parse("package:" + ctx.getPackageName()));
        startActivity(i);
      } catch (Throwable e) {
        try {
          startActivity(new Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION));
        } catch (Throwable e2) {
          toast(com.deepseekharness.app.util.UiText.format("打开设置失败：%s", e2.getMessage()));
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
        toast(com.deepseekharness.app.util.UiText.text("存储权限已授予，容器可访问手机存储"));
      }
      return;
    }
    toast(com.deepseekharness.app.util.UiText.text("当前系统无需存储权限"));
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
    status.setText(
        granted
            ? com.deepseekharness.app.util.UiText.choose(
                "已开启：可读写系统允许的共享存储；其他应用私有目录仍受系统限制",
                "Allowed: shared storage permitted by Android; other apps' private directories remain restricted")
            : com.deepseekharness.app.util.UiText.text("未开启：仅能访问 App 私有目录；去系统设置开启后可访问全部文件"));
    try {
      status.setTextColor(
          granted
              ? getResources().getColor(R.color.primary, null)
              : getResources().getColor(R.color.err, null));
    } catch (Throwable ignored) {
    }
  }

  /** 分别显示系统开关与本次实际连接；开启开关不代表服务已经绑定。 */
  private void refreshA11yStatus(TextView status) {
    if (status == null) return;
    String st = DshaAccessibilityService.enabledState(requireContext());
    boolean enabled = "YES".equals(st);
    boolean connected = enabled && DshaAccessibilityService.connected();
    if (enabled) {
      status.setText(
          connected
              ? com.deepseekharness.app.util.UiText.choose(
                  "已连接，可以在对话中使用 Computer Use。",
                  "Connected. Computer Use is available in conversations.")
              : com.deepseekharness.app.util.UiText.choose(
                  "开关已开启，正在等待系统连接；可重新开关服务。",
                  "Enabled, waiting for the system to connect. Try toggling the service."));
    } else if ("NO".equals(st)) {
      status.setText(com.deepseekharness.app.util.UiText.text("未开启 · 点上方按两步完成设置"));
    } else {
      status.setText(com.deepseekharness.app.util.UiText.text("尚未连接 · 点上方查看设置步骤"));
    }
    try {
      status.setTextColor(
          getResources()
              .getColor(
                  connected ? R.color.primary : enabled ? R.color.text_muted : R.color.err, null));
    } catch (Throwable ignored) {
    }
  }
}
