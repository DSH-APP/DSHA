package com.deepseekharness.app.ui;

import android.content.Context;
import android.widget.LinearLayout;
import android.widget.TextView;
import androidx.fragment.app.Fragment;
import com.deepseekharness.app.R;
import com.deepseekharness.app.core.DeviceGrants;
import com.deepseekharness.app.util.NativeConsentPolicy;
import com.deepseekharness.app.util.UiText;
import com.google.android.material.materialswitch.MaterialSwitch;

/** Local DSHA ASK confirmation toggle. It never requests or changes a device/system capability. */
final class FullPermissionControls implements AutoCloseable {
  private final Context app;
  private final Runnable policyChanged;
  private final MaterialSwitch global;
  private final TextView status;
  private final LinearLayout recoveryChoices;
  private final androidx.appcompat.widget.AppCompatButton rebuild;
  private final androidx.appcompat.widget.AppCompatButton diagnostics;
  private boolean rendering, closed;

  FullPermissionControls(Fragment owner, LinearLayout host, Runnable policyChanged) {
    app = owner.requireContext().getApplicationContext();
    this.policyChanged = policyChanged;
    Context ctx = owner.requireContext();
    LinearLayout card = new LinearLayout(ctx);
    card.setOrientation(LinearLayout.VERTICAL);
    card.setPadding(20, 20, 20, 20);
    card.setBackgroundResource(R.drawable.bg_card);
    TextView heading = new TextView(ctx);
    heading.setText(UiText.choose("全局权限", "Global permission"));
    heading.setTextSize(18);
    heading.setTextColor(ctx.getColor(R.color.text));
    card.addView(heading);
    global = new MaterialSwitch(ctx);
    global.setText(UiText.choose("不再询问 DSHA 安全确认", "Skip DSHA safety confirmations"));
    card.addView(global);
    TextView hint = new TextView(ctx);
    hint.setText(
        UiText.choose(
            "开启后，已获准能力的操作不再询问 DSHA 安全确认；关闭后恢复各项确认规则。此开关仅保存在本机，不随备份迁移。文件访问、Root、ADB、短信、定位、传感器和 Android 系统权限仍按各自授权设置生效，独立拒绝或暂停仍然有效。需要填写的业务内容、恢复范围和保存位置仍由你选择。",
            "When enabled, operations using authorized capabilities skip DSHA safety confirmations. Turning it off restores confirmation rules. This choice stays on this device and is excluded from backups. File access, Root, ADB, SMS, location, sensors and Android permissions keep their separate grants; explicit denial or pause remains effective. Business inputs, restore scope and save destinations still require your selection."));
    hint.setTextSize(12);
    hint.setTextColor(ctx.getColor(R.color.text_secondary));
    card.addView(hint);
    status = new TextView(ctx);
    status.setTextSize(12);
    status.setTextColor(ctx.getColor(R.color.text_secondary));
    card.addView(status);
    recoveryChoices = new LinearLayout(ctx);
    for (boolean enabled : new boolean[] {false, true}) {
      var choice = new androidx.appcompat.widget.AppCompatButton(ctx);
      choice.setText(
          enabled
              ? UiText.choose("重新选择开启并保存", "Choose on and save")
              : UiText.choose("重新选择关闭并保存", "Choose off and save"));
      choice.setOnClickListener(
          view -> {
            if (!closed) save(enabled);
          });
      recoveryChoices.addView(choice, new LinearLayout.LayoutParams(0, -2, 1));
    }
    card.addView(recoveryChoices);
    rebuild = new androidx.appcompat.widget.AppCompatButton(ctx);
    rebuild.setText(UiText.choose("重新设置本机确认", "Set up local confirmation again"));
    rebuild.setOnClickListener(
        view -> {
          if (closed) return;
          var candidate = new DeviceGrants(app).prepareConsentRecovery();
          if (candidate == null) {
            refresh();
            return;
          }
          // This is a user choice about local authority and must never skip its native
          // confirmation.
          new DshaDialogBuilder(ctx, false)
              .setTitle(UiText.choose("重新设置本机确认", "Set up local confirmation again"))
              .setMessage(
                  UiText.choose(
                      "保留当前异常记录，并将本机全局确认设置重建为关闭。旧的排队确认会失效；不会恢复旧授权。完成后如需免除 DSHA 确认，请自行开启开关。其他能力、数据和文件不受影响。",
                      "Keep the current invalid record and rebuild local global confirmation as off. Old queued confirmations become invalid; no old grant is restored. You can then enable the switch yourself. Other capability settings, data and files are unaffected."))
              .setNegativeButton(UiText.choose("取消", "Cancel"), null)
              .setPositiveButton(
                  UiText.choose("保留原件并重建为关闭", "Keep original and rebuild as off"),
                  (dialog, which) -> {
                    if (closed) return;
                    new DeviceGrants(app).rebuildConsentDisabled(candidate);
                    policyChanged.run();
                    refresh();
                  })
              .show();
        });
    card.addView(rebuild);
    diagnostics = new androidx.appcompat.widget.AppCompatButton(ctx);
    diagnostics.setText(UiText.choose("诊断详情", "Diagnostic details"));
    diagnostics.setOnClickListener(
        view -> {
          if (closed) return;
          CardSheet.show(
              ctx,
              UiText.choose("全局权限诊断", "Global permission diagnostics"),
              diagnosticText(new DeviceGrants(app).consentSnapshot()));
        });
    card.addView(diagnostics);
    global.setOnCheckedChangeListener(
        (button, enabled) -> {
          if (rendering || closed) return;
          save(enabled);
        });
    host.addView(card, 2, new LinearLayout.LayoutParams(-1, -2));
    refresh();
  }

  private void save(boolean enabled) {
    new DeviceGrants(app).setGlobalAllowed(enabled);
    policyChanged.run();
    refresh();
  }

  void refresh() {
    if (closed) return;
    var snapshot = new DeviceGrants(app).consentSnapshot();
    rendering = true;
    global.setVisibility(
        snapshot.hasSwitchValue() ? android.view.View.VISIBLE : android.view.View.GONE);
    try {
      if (snapshot.hasSwitchValue())
        global.setChecked(snapshot.readState() == NativeConsentPolicy.ReadState.ON);
    } finally {
      rendering = false;
    }
    recoveryChoices.setVisibility(
        snapshot.readState()
                    == com.deepseekharness.app.util.NativeConsentPolicy.ReadState.SAVE_FAILED
                || snapshot.readState()
                    == com.deepseekharness.app.util.NativeConsentPolicy.ReadState.RECONFIRM_REQUIRED
            ? android.view.View.VISIBLE
            : android.view.View.GONE);
    rebuild.setVisibility(
        snapshot.canRebuild() ? android.view.View.VISIBLE : android.view.View.GONE);
    String description =
        switch (snapshot.readState()) {
          case ON ->
              UiText.choose(
                  "已开启：免 DSHA 安全确认；各项能力授权保持独立",
                  "On: skip DSHA confirmation; capability grants remain separate");
          case OFF ->
              UiText.choose("已关闭：按各项 DSHA 确认规则", "Off: follow individual DSHA confirmation rules");
          case UNCONFIGURED ->
              UiText.choose(
                  "已关闭（默认）：按各项 DSHA 确认规则",
                  "Off (default): follow individual DSHA confirmation rules");
          case MALFORMED ->
              UiText.choose(
                  "本机确认设置记录损坏：未重置记录或恢复旧授权",
                  "Local confirmation record is malformed; no reset or grant restored");
          case READ_UNAVAILABLE ->
              UiText.choose(
                  "暂时无法读取本机确认设置；这不代表你已关闭",
                  "Cannot read local confirmation settings; this does not mean you turned them off");
          case CHANGING ->
              UiText.choose(
                  "正在更新本机确认设置；旧请求已失效",
                  "Updating local confirmation settings; old requests are invalid");
          case SAVE_FAILED ->
              UiText.choose(
                  "本次选择保存或核对失败：确认豁免未生效；不会自动恢复旧授权",
                  "Saving or checking this selection failed; confirmation bypass is unavailable; no old grant restored");
          case RECONFIRM_REQUIRED ->
              UiText.choose(
                  "本机设置现已可读；旧确认授权仍已失效，请重新选择并保存",
                  "Local settings are readable again; old consent remains invalid. Choose and save again");
        };
    status.setText(description);
  }

  static String diagnosticText(NativeConsentPolicy.Snapshot snapshot) {
    return UiText.format(
        "%s / %s / revision=%s\n%s%s",
        snapshot.readState(),
        snapshot.saveState(),
        snapshot.revision(),
        UiText.choose(
            "revision 用于使旧请求失效，不代表开关状态。普通启动、停止和界面刷新不会修改本机确认设置。",
            "The revision invalidates old requests; it is not the switch state. Normal startup, stopping and UI refresh do not change this local setting."),
        snapshot.saveState() == NativeConsentPolicy.SaveState.CACHE_VERIFIED
            ? UiText.choose(
                "\n仅同进程缓存核对，未验证重开后的落盘值",
                "\nSame-process cache checked; persistence after reopening is unverified")
            : "");
  }

  void pause() {}

  void resume() {
    refresh();
  }

  void cancelForLocationChange() {
    refresh();
  }

  @Override
  public void close() {
    closed = true;
  }
}
