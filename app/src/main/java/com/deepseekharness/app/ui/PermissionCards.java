package com.deepseekharness.app.ui;

import android.view.View;
import androidx.appcompat.app.AppCompatActivity;
import com.deepseekharness.app.R;
import com.deepseekharness.app.util.PermissionGoals;
import com.deepseekharness.app.util.UiText;

/** 通知、电池优化、存储、无障碍、悬浮窗五张卡片的文案与按钮（状态原文尽量沿用现有页面）。 */
final class PermissionCards {
  private PermissionCards() {}

  /** s 的顺序见 PermissionPanel.states()。 */
  static PermissionCard.Model model(PermissionPanel panel, String id, boolean[] s) {
    switch (id) {
      case PermissionGoals.NOTIFY:
        return notify(panel, s[4]);
      case PermissionGoals.BATTERY:
        return battery(panel, s[6]);
      case PermissionGoals.STORAGE:
        return storage(panel, s[0]);
      case PermissionGoals.A11Y:
        return accessibility(panel);
      default:
        return overlay(panel, s[5]);
    }
  }

  private static PermissionCard.Model base(int icon, String title, String why, String... unlocks) {
    PermissionCard.Model m = new PermissionCard.Model();
    m.icon = icon;
    m.title = title;
    m.why = why;
    m.unlocks = unlocks;
    return m;
  }

  private static void state(PermissionCard.Model m, boolean ok, String okPill, String offPill) {
    m.ok = ok;
    m.pill = ok ? okPill : offPill;
    m.tone = ok ? OnboardingKit.Tone.GOOD : OnboardingKit.Tone.NEUTRAL;
  }

  private static PermissionCard.Model notify(PermissionPanel panel, boolean ok) {
    OnboardingKit kit = panel.kit();
    AppCompatActivity a = panel.activity();
    PermissionCard.Model m =
        base(
            R.drawable.ic_onb_bell,
            UiText.choose("通知", "Notifications"),
            UiText.choose(
                "在通知栏显示部署进度、更新下载和手机任务的停止按钮。",
                "Shows deployment progress, update downloads and the phone-task stop button."),
            UiText.choose("部署进度", "Progress"),
            UiText.choose("任务停止按钮", "Stop button"));
    boolean exhausted = panel.notificationRequestExhausted();
    // Android 11 / 12 没有运行时申请框：未开启只表示通知总开关被关闭，只能去系统设置打开。
    boolean settingsOnly = !PermissionPanel.notificationPromptAvailable();
    state(
        m,
        ok,
        UiText.choose("已开启", "On"),
        exhausted && !ok ? UiText.choose("已拒绝", "Denied") : UiText.choose("未开启", "Off"));
    if (!ok && exhausted) m.tone = OnboardingKit.Tone.WARN;
    m.outside =
        settingsOnly || exhausted
            ? UiText.choose(
                "在系统通知设置中允许 DSH-A-Su。", "Allow DSH-A-Su in system notification settings.")
            : UiText.choose("会弹出系统权限框。", "Android will show a permission prompt.");
    m.actions =
        new View[] {
          ok
              ? kit.ghost(
                  UiText.choose("系统通知设置", "Notification settings"),
                  v -> PermissionActions.openNotificationSettings(a))
              : exhausted || settingsOnly
                  ? kit.small(
                      UiText.choose("去系统通知设置", "Open notification settings"),
                      true,
                      v -> PermissionActions.openNotificationSettings(a))
                  : kit.small(
                      UiText.choose("允许通知", "Allow notifications"),
                      true,
                      v -> panel.requestNotifications())
        };
    if (!ok) {
      boolean viaSettings = exhausted || settingsOnly;
      m.inline =
          PermissionCard.inlineAction(
              kit,
              viaSettings ? UiText.choose("去设置", "Settings") : UiText.choose("允许", "Allow"),
              viaSettings
                  ? UiText.choose("去系统通知设置", "Open notification settings")
                  : UiText.choose("允许通知", "Allow notifications"),
              viaSettings
                  ? v -> PermissionActions.openNotificationSettings(a)
                  : v -> panel.requestNotifications());
      if (exhausted)
        m.alert = UiText.choose("系统不再弹框，需在系统设置中开启", "Android won't ask again; enable in settings");
      else if (settingsOnly)
        m.alert = UiText.choose("通知已关闭，需在系统设置中开启", "Notifications are off; enable in settings");
    }
    return m;
  }

  private static PermissionCard.Model battery(PermissionPanel panel, boolean ok) {
    OnboardingKit kit = panel.kit();
    AppCompatActivity a = panel.activity();
    PermissionCard.Model m =
        base(
            R.drawable.ic_onb_battery,
            UiText.choose("电池优化", "Battery optimization"),
            UiText.choose(
                "减少系统在后台结束 Web 和终端。",
                "Android stops Web and the terminal less often in the background."),
            UiText.choose("后台更稳", "Steadier in background"));
    var battery = PermissionActions.batteryState(a);
    boolean unrestricted =
        battery == com.deepseekharness.app.util.BatteryBackgroundState.State.UNRESTRICTED;
    m.ok = ok;
    m.pill =
        unrestricted
            ? UiText.choose("不受限制", "Unrestricted")
            : ok
                ? UiText.choose("已允许后台", "Background allowed")
                : UiText.choose("后台受限", "Restricted");
    m.tone = ok ? OnboardingKit.Tone.GOOD : OnboardingKit.Tone.WARN;
    // “优化”档已计入开启，但长时间息屏仍可能被推迟：只在这一档给出改法。
    m.status =
        ok && !unrestricted
            ? UiText.choose(
                "长时间运行建议在电池页改为“不受限制”。",
                "For long runs, set it to Unrestricted on the battery page.")
            : null;
    m.outside =
        UiText.choose(
            "会打开应用详情。ColorOS 点「耗电管理」→「完全允许后台行为」。",
            "Opens App info. On ColorOS: Battery usage → Allow all background activity.");
    m.actions =
        new View[] {
          unrestricted
              ? kit.ghost(
                  UiText.choose("打开应用详情", "Open app info"),
                  v -> PermissionActions.openAppDetails(a))
              : kit.small(
                  UiText.choose("打开耗电管理", "Open battery usage"),
                  true,
                  v -> PermissionActions.openAppDetails(a))
        };
    if (!ok) {
      m.inline =
          PermissionCard.inlineAction(
              kit,
              UiText.choose("去设置", "Settings"),
              UiText.choose("打开耗电管理", "Open battery usage"),
              v -> PermissionActions.openAppDetails(a));
      m.alert = UiText.choose("后台受限，可能被系统结束", "Restricted; may be stopped");
    }
    return m;
  }

  private static PermissionCard.Model storage(PermissionPanel panel, boolean ok) {
    OnboardingKit kit = panel.kit();
    AppCompatActivity a = panel.activity();
    PermissionCard.Model m =
        base(
            R.drawable.ic_onb_folder,
            UiText.choose("存储读写", "Storage access"),
            UiText.choose(
                "读写 /sdcard 下的项目和文件。不开也能用，仅限私有目录。",
                "Read and write projects under /sdcard. Without it, only private folders work."),
            UiText.choose("读写 /sdcard", "Use /sdcard"));
    state(m, ok, UiText.choose("已开启", "On"), UiText.choose("未开启", "Off"));
    m.outside = UiText.choose("在「所有文件访问」中允许 DSH-A-Su。", "Allow DSH-A-Su under All files access.");
    if (ok)
      m.note =
          UiText.choose(
              "已在运行的 Web 与终端重启后生效。", "Restart a running Web session or terminal to apply.");
    m.actions =
        new View[] {
          ok
              ? kit.ghost(
                  UiText.choose("系统设置", "System settings"),
                  v -> PermissionActions.openAllFilesAccess(a))
              : kit.small(
                  UiText.choose("去开启所有文件访问", "Allow all files access"),
                  true,
                  v -> PermissionActions.openAllFilesAccess(a))
        };
    if (!ok)
      m.inline =
          PermissionCard.inlineAction(
              kit,
              UiText.choose("去开启", "Enable"),
              UiText.choose("去开启所有文件访问", "Allow all files access"),
              v -> PermissionActions.openAllFilesAccess(a));
    return m;
  }

  private static PermissionCard.Model accessibility(PermissionPanel panel) {
    OnboardingKit kit = panel.kit();
    AppCompatActivity a = panel.activity();
    String state = PermissionActions.accessibilityState(a);
    boolean connected = panel.accessibilityConnectedForDisplay();
    PermissionCard.Model m =
        base(
            R.drawable.ic_onb_hand,
            UiText.choose("无障碍", "Accessibility"),
            UiText.choose(
                "让助手读屏、点按和输入，用于手机任务。",
                "Lets the assistant read the screen, tap and type for phone tasks."),
            UiText.choose("读屏与点按", "Read and tap"));
    String service = a.getString(R.string.accessibility_service_label);
    if (connected) {
      state(m, true, UiText.choose("已连接", "Connected"), "");
      m.actions =
          new View[] {
            kit.ghost(
                UiText.choose("无障碍设置", "Accessibility settings"),
                v -> PermissionActions.openAccessibility(a))
          };
    } else if ("YES".equals(state)) {
      state(m, false, "", UiText.choose("等待连接", "Waiting"));
      m.tone = OnboardingKit.Tone.WARN;
      m.inline =
          PermissionCard.inlineAction(
              kit, UiText.choose("检查", "Check"), UiText.text("检查连接"), v -> panel.refresh());
      m.alert = UiText.choose("已开启，等待系统连接", "On, waiting for the system");
      m.actions =
          new View[] {
            kit.small(UiText.text("检查连接"), true, v -> panel.refresh()),
            kit.ghost(
                UiText.choose("重新打开无障碍设置", "Reopen accessibility settings"),
                v -> PermissionActions.openAccessibility(a))
          };
    } else {
      boolean unknown = !"NO".equals(state);
      state(m, false, "", unknown ? UiText.choose("无法确认", "Unknown") : UiText.choose("未开启", "Off"));
      m.actions =
          new View[] {
            kit.small(
                UiText.choose("去开启屏幕操作", "Enable screen control"),
                true,
                v -> PermissionActions.openAccessibility(a))
          };
      m.inline =
          PermissionCard.inlineAction(
              kit,
              unknown ? UiText.choose("查看", "Check") : UiText.choose("去开启", "Enable"),
              UiText.choose("去开启屏幕操作", "Enable screen control"),
              v -> PermissionActions.openAccessibility(a));
      if (unknown) m.alert = UiText.choose("暂时无法确认状态", "Status unavailable");
    }
    m.outside =
        String.format(
            java.util.Locale.ROOT,
            UiText.choose(
                "在无障碍设置中开启「%s」。提示“受限设置”时，先在应用详情右上角菜单中允许。",
                "Turn on “%s” in accessibility settings. If restricted settings are reported, allow them from the app-info menu first."),
            service);
    return m;
  }

  private static PermissionCard.Model overlay(PermissionPanel panel, boolean ok) {
    OnboardingKit kit = panel.kit();
    AppCompatActivity a = panel.activity();
    PermissionCard.Model m =
        base(
            R.drawable.ic_onb_layers,
            UiText.choose("悬浮窗", "Overlay"),
            UiText.choose(
                "显示悬浮条、命令批准和手机任务状态窗。",
                "Shows the floating bar, command approvals and the phone-task window."),
            UiText.choose("悬浮条与状态窗", "Floating bar"));
    state(m, ok, UiText.choose("已允许", "Allowed"), UiText.choose("未允许", "Not allowed"));
    m.outside =
        UiText.choose("在“显示在其他应用上层”中允许 DSH-A-Su。", "Allow DSH-A-Su under Display over other apps.");
    m.note =
        UiText.choose(
            "悬浮条开关在 设置 → 悬浮条。", "Turn on the floating bar in Settings → Floating status.");
    m.actions =
        new View[] {
          ok
              ? kit.ghost(
                  UiText.choose("系统设置", "System settings"), v -> PermissionActions.openOverlay(a))
              : kit.small(
                  UiText.choose("去允许悬浮窗", "Allow overlay"),
                  true,
                  v -> PermissionActions.openOverlay(a))
        };
    if (!ok)
      m.inline =
          PermissionCard.inlineAction(
              kit,
              UiText.choose("去允许", "Allow"),
              UiText.choose("去允许悬浮窗", "Allow overlay"),
              v -> PermissionActions.openOverlay(a));
    return m;
  }
}
