package com.deepseekharness.app.ui;

import android.content.ClipboardManager;
import android.content.Context;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;
import androidx.appcompat.app.AppCompatActivity;
import com.deepseekharness.app.R;
import com.deepseekharness.app.nethealth.NetworkHealthMonitor;
import com.deepseekharness.app.util.NetworkHealthPolicy;
import com.deepseekharness.app.util.NetworkHealthPolicy.Verdict;
import com.deepseekharness.app.util.UiText;
import java.util.List;

/** 诊断页「网络健康」底部面板：当前结论、最近事件、立即体检与复制报告。只读，不做修复。 */
final class NetworkHealthSheet {
  private static final int RECENT = 10;

  private NetworkHealthSheet() {}

  static void show(AppCompatActivity activity) {
    NetworkHealthMonitor monitor = NetworkHealthMonitor.ensureStarted(activity);
    CardPage page = new CardPage(activity, activity.getString(R.string.nh_title), "", true);
    var dialog = CardSheet.create(activity, page);

    LinearLayout summary = page.card();
    TextView headline = page.text(title(monitor.lastVerdict()), 15, R.color.shell_text);
    headline.setTypeface(null, android.graphics.Typeface.BOLD);
    summary.addView(headline);
    TextView detail = page.text(detail(monitor.lastVerdict()), 12, R.color.shell_text_secondary);
    detail.setPadding(0, page.dp(6), 0, 0);
    detail.setLineSpacing(page.dp(2), 1);
    summary.addView(detail);

    page.label(UiText.choose("最近事件", "Recent events"));
    LinearLayout list = page.card();
    renderEvents(page, list, monitor.events());

    String[] report = {""};
    Button[] copy = new Button[1];
    Button check = page.button(page.footer, UiText.choose("立即体检", "Check now"), true, () -> {});
    check.setOnClickListener(
        v -> {
          check.setEnabled(false);
          check.setText(UiText.text("正在检查…"));
          monitor.checkNow(
              result ->
                  activity.runOnUiThread(
                      () -> {
                        if (activity.isFinishing() || activity.isDestroyed()) return;
                        check.setEnabled(true);
                        check.setText(UiText.choose("立即体检", "Check now"));
                        if (result == null) {
                          Toast.makeText(
                                  activity,
                                  UiText.choose("体检失败，请稍后重试", "Check failed. Try again later."),
                                  Toast.LENGTH_LONG)
                              .show();
                          return;
                        }
                        report[0] = result.text;
                        headline.setText(title(result.verdict));
                        detail.setText(detail(result.verdict) + "\n\n" + result.evidence);
                        renderEvents(page, list, monitor.events());
                      }));
        });
    copy[0] =
        page.button(
            page.footer,
            UiText.choose("复制体检报告", "Copy check report"),
            false,
            () -> copy(activity, report[0], monitor.events()));
    page.button(page.footer, UiText.text("关闭"), false, dialog::dismiss)
        .setBackgroundResource(android.R.color.transparent);
    CardSheet.show(dialog, activity);
  }

  private static void renderEvents(
      CardPage page, LinearLayout list, List<NetworkHealthPolicy.Event> events) {
    list.removeAllViews();
    List<NetworkHealthPolicy.Event> recent = NetworkHealthPolicy.recent(events, RECENT);
    if (recent.isEmpty()) {
      TextView empty =
          page.text(UiText.choose("暂无异常记录。", "No issues recorded."), 13, R.color.shell_text_muted);
      empty.setPadding(0, page.dp(8), 0, page.dp(8));
      list.addView(empty);
      return;
    }
    java.text.DateFormat format =
        java.text.DateFormat.getDateTimeInstance(
            java.text.DateFormat.SHORT, java.text.DateFormat.SHORT);
    for (NetworkHealthPolicy.Event event : recent) {
      String time = format.format(new java.util.Date(event.at));
      String when = event.count > 1 ? time + " ×" + event.count : time;
      String title = title(event.verdict), evidence = event.evidence;
      page.entry(
          list,
          title,
          when,
          R.drawable.ic_onb_alert,
          () -> CardSheet.show(page.context, title, when + "\n\n" + evidence));
    }
  }

  private static void copy(Context context, String report, List<NetworkHealthPolicy.Event> events) {
    try {
      ClipboardManager clipboard =
          (ClipboardManager) context.getSystemService(Context.CLIPBOARD_SERVICE);
      if (clipboard == null) throw new IllegalStateException("clipboard");
      String text = report.isEmpty() ? "" : report + "\n";
      text += NetworkHealthPolicy.serialize(NetworkHealthPolicy.recent(events, 50));
      clipboard.setPrimaryClip(
          com.deepseekharness.app.bridge.SensitiveClipboard.text(UiText.text("DSHA 诊断"), text));
      Toast.makeText(
              context,
              UiText.choose(
                  "报告已复制，请在公开分享前查看内容", "Report copied. Review its content before public sharing"),
              Toast.LENGTH_SHORT)
          .show();
    } catch (RuntimeException error) {
      Toast.makeText(context, UiText.text("复制失败，可尝试导出报告"), Toast.LENGTH_LONG).show();
    }
  }

  static String title(Verdict verdict) {
    return switch (verdict) {
      case OK -> UiText.choose("未发现网络异常", "No network issues found");
      case FGS_MISSING -> UiText.choose("后台保护未开启", "Background protection is off");
      case SYSTEM_BLOCKED ->
          UiText.choose("系统正在限制后台联网", "System is restricting background network");
      case DNS_STALE -> UiText.choose("容器 DNS 已过期", "Container DNS is out of date");
      case EGRESS_FAILED -> UiText.choose("无法连接模型服务", "Cannot reach the model service");
      case SUSPECT_FROZEN -> UiText.choose("疑似在后台被冻结", "Possibly frozen in the background");
    };
  }

  static String detail(Verdict verdict) {
    return switch (verdict) {
      case OK ->
          UiText.choose(
              "这里只记录检测结果，不会自动修复或重启。点「立即体检」查看当前状态。",
              "Results are only recorded; nothing is repaired or restarted automatically. Tap Check now for the current state.");
      case FGS_MISSING ->
          UiText.choose(
              "DSH 仍在运行，但后台保护未开启，切到后台可能断网。回到 DSHA 即可重新开启。",
              "DSH is still running without background protection, so it may lose network in the background. Return to DSHA to turn it back on.");
      case SYSTEM_BLOCKED ->
          UiText.choose(
              "系统阻止了 DSHA 联网。回到 DSHA 通常可恢复；如果经常发生，请在电池设置中允许后台运行。",
              "The system blocked DSHA from the network. Returning to DSHA usually restores it; if this happens often, allow background use in battery settings.");
      case DNS_STALE ->
          UiText.choose(
              "容器仍在使用上一个网络的 DNS 地址，切换网络后会自动刷新。",
              "The container still uses the previous network's DNS server. It is refreshed automatically after the network changes.");
      case EGRESS_FAILED ->
          UiText.choose(
              "手机有网络，但连不上模型服务。可能是 VPN、代理或服务端问题。",
              "The phone has a network but cannot reach the model service. A VPN, proxy or the service itself may be the cause.");
      case SUSPECT_FROZEN ->
          UiText.choose(
              "DSHA 在后台有一段时间没有运行，可能被系统或省电策略冻结。",
              "DSHA did not run for a while in the background and may have been frozen by the system or a power-saving policy.");
    };
  }
}
