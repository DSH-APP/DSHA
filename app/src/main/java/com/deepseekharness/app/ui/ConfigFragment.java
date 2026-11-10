package com.deepseekharness.app.ui;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.os.Environment;
import android.provider.Settings;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.CompoundButton;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;

import com.deepseekharness.app.DeviceBridgeService;
import com.deepseekharness.app.DshaAccessibilityService;
import com.deepseekharness.app.OverlayController;
import com.deepseekharness.app.R;
import com.deepseekharness.app.bridge.AdbBridge;
import com.deepseekharness.app.core.ConfigStore;
import com.deepseekharness.app.core.HarnessController;
import com.deepseekharness.app.util.ConfigDraft;
import com.deepseekharness.app.util.Constants;
import com.deepseekharness.app.util.UiText;

/**
 * 配置子页：接口、显示与运行行为；设备授权由独立页面管理。
 * 所有开关都落到 ConfigStore / SharedPreferences，并真正影响启动与预览。
 */
public class ConfigFragment extends Fragment {
  // Settings 没有在所有 compileSdk stub 中暴露该常量，使用公开的 action 字符串保持 API 26+ 兼容。
  private static final String ACTION_PICTURE_IN_PICTURE_SETTINGS =
      "android.settings.PICTURE_IN_PICTURE_SETTINGS";
  private View pictureSettings;
  private TextView pictureStatus;
  private CompoundButton pictureEnabled;
  private boolean renderingPictureInPicture;
  private CompoundButton entryWeb;
  private boolean renderingEntryTarget;
  private View root;
  private ConfigDraft savedDraft;
  private final DisclosureCard.Memory disclosure = new DisclosureCard.Memory();
  private final DisclosureCard.Model pageDisclosure = new DisclosureCard.Model();
  private final DisclosureCard.Model entryDisclosure = new DisclosureCard.Model();
  private static final java.util.concurrent.atomic.AtomicBoolean savingLanPassword =
      new java.util.concurrent.atomic.AtomicBoolean();

  @Nullable
  @Override
  public View onCreateView(
      @NonNull LayoutInflater inflater,
      @Nullable ViewGroup container,
      @Nullable Bundle savedInstanceState) {
    View v = inflater.inflate(R.layout.fragment_config, container, false);
    PressFeedback.attachButtons(v);
    ConfigStore c = new ConfigStore(requireContext());
    Context ctx = requireContext();
    root = v;
    android.widget.RadioGroup dns = v.findViewById(R.id.config_dns_mode);
    dns.check(
        "ipv4".equals(c.getDnsMode())
            ? R.id.dns_ipv4
            : "native".equals(c.getDnsMode()) ? R.id.dns_native : R.id.dns_auto);
    ((TextView) v.findViewById(R.id.dns_auto)).setText(UiText.choose("自动", "Auto"));
    ((TextView) v.findViewById(R.id.dns_native)).setText(UiText.choose("原生", "Native"));

    Button workspace = v.findViewById(R.id.config_workspace_entry);
    workspace.setText(UiText.text("数据与备份"));
    workspace.setOnClickListener(x -> open(new WorkspaceFragment()));

    EditText port = v.findViewById(R.id.config_port);
    CompoundButton automaticPort = v.findViewById(R.id.config_automatic_port);
    v.findViewById(R.id.config_restore_port_drafts)
        .setOnClickListener(x -> BrowserOriginRestore.show(requireContext()));
    CompoundButton checkUpdate = v.findViewById(R.id.config_check_update);
    CompoundButton desktop = v.findViewById(R.id.config_desktop_mode);
    bindEntryTarget(v);
    pictureEnabled = v.findViewById(R.id.config_picture_in_picture);
    pictureEnabled.setChecked(c.isPictureInPictureEnabled());
    pictureEnabled.setOnCheckedChangeListener(
        (button, enabled) -> {
          if (renderingPictureInPicture) return;
          c.setPictureInPictureEnabled(enabled);
          renderPictureInPictureStatus();
        });
    pictureSettings = v.findViewById(R.id.config_picture_in_picture_settings);
    ((TextView) pictureSettings).setText(UiText.choose("系统设置", "System"));
    pictureStatus = v.findViewById(R.id.config_picture_in_picture_status);
    pictureSettings.setOnClickListener(x -> openPictureInPictureSettings());
    bindRowToggle(v, R.id.config_picture_in_picture_text, pictureEnabled);
    renderPictureInPictureStatus();
    CompoundButton staticLoader = v.findViewById(R.id.config_static_loader),
        noSeccomp = v.findViewById(R.id.config_no_seccomp);
    staticLoader.setChecked(c.isProrootStaticLoader());
    noSeccomp.setChecked(c.isProotSeccompDisabled());
    CompoundButton proroot = v.findViewById(R.id.config_proroot);
    CompoundButton lan = v.findViewById(R.id.config_lan_mode);
    CompoundButton overlay = v.findViewById(R.id.config_overlay_stream);
    Button save = v.findViewById(R.id.config_save);
    Button undo = v.findViewById(R.id.config_undo);
    undo.setText(UiText.choose("撤销改动", "Undo"));

    // 运行时兼容选项：默认折叠，摘要显示当前值；偏离默认值时标「已改」。
    View advHeader = v.findViewById(R.id.config_adv_header);
    View advBody = v.findViewById(R.id.config_adv_body);
    ((TextView) v.findViewById(R.id.config_adv_badge)).setText(UiText.choose("已改", "Changed"));
    advHeader.setOnClickListener(
        x -> {
          advBody.setVisibility(advBody.getVisibility() == View.VISIBLE ? View.GONE : View.VISIBLE);
          renderCompatibility();
        });

    // 这里只编辑运行选项；模型与凭据统一由 Web UI 管理。
    port.setText(c.getPort());
    automaticPort.setChecked("auto".equals(c.getWebPortMode()));
    port.setEnabled(!automaticPort.isChecked());
    checkUpdate.setChecked(c.isCheckUpdate());
    desktop.setChecked(c.isDesktopMode());
    CompoundButton gecko = v.findViewById(R.id.config_gecko_core);
    gecko.setVisibility(View.GONE);
    v.findViewById(R.id.config_gecko_hint).setVisibility(View.GONE);
    gecko.setChecked(c.isGeckoCore());
    // 网页内核目前只有 System WebView 一项，作为桌面布局行的副文案显示，不再给出单选下拉。
    ((TextView) v.findViewById(R.id.config_core_choice))
        .setText(UiText.choose("网页内核", "Browser engine: System WebView"));
    proroot.setChecked(c.isProroot());
    lan.setChecked(c.isLanMode());
    TextView lanPasswordStatus = v.findViewById(R.id.config_lan_password_status);
    renderLanPassword(c.hasLanPassword(), false);
    v.findViewById(R.id.config_lan_password)
        .setOnClickListener(x -> showLanPasswordDialog(c, lanPasswordStatus));
    overlay.setChecked(pref(ctx, "overlay_stream", false));
    ((TextView) v.findViewById(R.id.config_overlay_style))
        .setText(UiText.choose("外观与行为", "Appearance"));
    ((TextView) v.findViewById(R.id.config_repo_link))
        .setText(UiText.choose("项目主页", "Project page"));
    ((TextView) v.findViewById(R.id.config_runtime_title)).setText(UiText.text("运行方式"));
    DshaSelectView runtime =
        bindChoice(
            v,
            R.id.config_runtime_choice,
            UiText.choose("运行方式", "Runtime"),
            new String[] {
              UiText.choose("proot（默认，稳定）", "proot (default, stable)"),
              UiText.choose("proroot（可选，实验）", "proroot (optional, experimental)")
            },
            c.isProroot() ? 1 : 0,
            index -> {
              proroot.setChecked(index == 1);
              renderRuntimeDescription();
            });
    // 行内值显示：去掉下拉框底与内边距，整行点按打开同一个底部选择面板。
    runtime.setBackground(null);
    runtime.setPadding(0, 0, 0, 0);
    v.findViewById(R.id.config_runtime_row).setOnClickListener(x -> runtime.performClick());
    renderRuntimeDescription();

    // 悬浮条外观与行为（照 1.1.9.1：底色预设 + 不透明度/行数/字号/停留 + 行为开关）
    v.findViewById(R.id.config_overlay_style).setOnClickListener(x -> showOverlayStyleDialog());

    v.findViewById(R.id.config_repo_link).setOnClickListener(x -> openRepo(ctx));

    // 多句说明行内只留第一句，完整内容展开后才创建（替代 ⓘ）；文案仍是原有已翻译资源。
    TextView pageHint = v.findViewById(R.id.config_page_hint);
    com.deepseekharness.app.util.SummaryText.Split pageSplit =
        com.deepseekharness.app.util.SummaryText.split(getString(R.string.ui2_runtime_config));
    pageHint.setText(pageSplit.summary);
    pageDisclosure.key = "config-page";
    pageDisclosure.detail = pageSplit.full;
    // 说明行嵌在水平标签行里：详情容器插到该行之后的竖向列容器。
    DisclosureCard.bind(
        pageHint,
        pageDisclosure,
        disclosure,
        null,
        (android.view.View) pageHint.getParent().getParent());

    // 标题列点按与开关等价；无障碍焦点落在开关上，读出标题与副文案。
    bindRowToggle(v, R.id.config_automatic_port_text, automaticPort);
    bindRowToggle(v, R.id.config_lan_text, lan);
    bindRowToggle(v, R.id.config_desktop_text, desktop);
    bindRowToggle(v, R.id.config_overlay_text, overlay);

    savedDraft = storedDraft(c);
    Runnable validateInputs =
        () -> {
          boolean valid = portError() == null;
          save.setEnabled(valid);
          if (valid) port.setError(null);
          renderDraft();
        };
    android.text.TextWatcher inputWatcher =
        new android.text.TextWatcher() {
          public void beforeTextChanged(CharSequence s, int start, int count, int after) {}

          public void onTextChanged(CharSequence s, int start, int before, int count) {
            validateInputs.run();
          }

          public void afterTextChanged(android.text.Editable text) {}
        };
    automaticPort.setOnCheckedChangeListener(
        (button, checked) -> {
          port.setEnabled(!checked);
          renderPort();
          validateInputs.run();
        });
    port.addTextChangedListener(inputWatcher);
    CompoundButton.OnCheckedChangeListener draftChanged = (button, checked) -> renderDraft();
    for (CompoundButton b :
        new CompoundButton[] {proroot, staticLoader, noSeccomp, desktop, checkUpdate, overlay})
      b.setOnCheckedChangeListener(draftChanged);
    lan.setOnCheckedChangeListener(
        (button, checked) -> {
          renderLanPassword(new ConfigStore(requireContext()).hasLanPassword(), false);
          renderDraft();
        });
    dns.setOnCheckedChangeListener((group, id) -> renderDraft());
    undo.setOnClickListener(
        x -> {
          restoreDraft(savedDraft);
          toast(UiText.choose("已撤销未保存的改动", "Unsaved changes discarded"));
        });
    renderPort();
    validateInputs.run();
    save.setOnClickListener(
        x -> {
          final int chosenPort;
          try {
            chosenPort =
                automaticPort.isChecked()
                    ? c.getPortInt()
                    : com.deepseekharness.app.util.ConfigInput.port(port.getText().toString());
          } catch (IllegalArgumentException e) {
            port.setError(e.getMessage());
            port.requestFocus();
            return;
          }
          port.setError(null);
          com.deepseekharness.app.util.EnvironmentTaskGate.Lease saving =
              com.deepseekharness.app.util.EnvironmentTaskGate.tryAcquire(
                  com.deepseekharness.app.util.UiText.text("保存配置"));
          if (saving == null) {
            toast(
                com.deepseekharness.app.util.UiText.format(
                    "正在%s，完成后再保存配置",
                    com.deepseekharness.app.util.EnvironmentTaskGate.activeKind()));
            return;
          }
          try {
            saving.run(
                () -> {
                  c.setWebPort(automaticPort.isChecked() ? "auto" : "fixed", chosenPort);
                  c.setCheckUpdate(checkUpdate.isChecked());
                  c.setDesktopMode(desktop.isChecked());
                  if (false) c.setGeckoCore(gecko.isChecked());
                  c.setProroot(proroot.isChecked());
                  c.setProrootStaticLoader(staticLoader.isChecked());
                  c.setProotSeccompDisabled(noSeccomp.isChecked());
                  c.setDnsMode(
                      dns.getCheckedRadioButtonId() == R.id.dns_ipv4
                          ? "ipv4"
                          : dns.getCheckedRadioButtonId() == R.id.dns_native ? "native" : "auto");
                  c.setLanMode(lan.isChecked());
                  boolean overlayEnabled = overlay.isChecked();
                  setPref(ctx, "overlay_stream", overlayEnabled);
                  // The settings switch is also the lifecycle switch for the
                  // WindowManager overlay.  Previously only the preference changed;
                  // an already visible bar stayed alive after disabling and a newly
                  // enabled bar did not receive the refreshed style until a later
                  // process event, which looked like the control did nothing.
                  if (!overlayEnabled) OverlayController.teardown(ctx.getApplicationContext());
                  else if (OverlayController.permitted(ctx))
                    OverlayController.applyStyleNow(ctx.getApplicationContext());
                  boolean lanApplied = applyLanMode(ctx, lan.isChecked());
                  int lanMessage =
                      lanSettingsMessage(lan.isChecked(), c.hasLanPassword(), lanApplied);
                  if (lanMessage != 0) Toast.makeText(ctx, lanMessage, Toast.LENGTH_LONG).show();
                  if (lan.isChecked() && getActivity() instanceof MainActivity)
                    ((MainActivity) getActivity()).requestLocalNetwork();
                  Toast.makeText(
                          ctx,
                          com.deepseekharness.app.util.UiText.text(
                              "已保存；网页显示选项重新进入对话生效，端口与运行时需重启 Web"),
                          Toast.LENGTH_LONG)
                      .show();
                  if (overlay.isChecked() && !OverlayController.permitted(ctx))
                    openOverlayPermission();
                  return null;
                });
            savedDraft = storedDraft(c);
            renderDraft();
          } catch (Exception e) {
            toast(
                com.deepseekharness.app.util.UiText.format(
                    "配置保存未完成：%s",
                    com.deepseekharness.app.util.SensitiveData.redact(String.valueOf(e))));
          } finally {
            saving.close();
          }
        });

    // 详情窗格足够宽时两列；按实际宽度判断，分屏/双栏/旋转都适用。
    View columns = v.findViewById(R.id.config_columns);
    columns.addOnLayoutChangeListener(
        (view, l, t, r, b, ol, ot, orr, ob) -> {
          if (r - l != orr - ol) view.post(this::applyColumns);
        });
    // 页尾技术说明（B3）：运行方式 / 端口 / DNS 与配置存放位置；展开后才创建明细。
    ConfigStore techConfig = new ConfigStore(requireContext());
    String runtimeMode = techConfig.isProroot() ? "proroot" : "proot";
    String portMode =
        techConfig.getRequestedWebPort() > 0
            ? String.valueOf(techConfig.getRequestedWebPort())
            : com.deepseekharness.app.util.UiText.text("自动");
    String dnsMode =
        "ipv4".equals(techConfig.getDnsMode())
            ? "IPv4"
            : "native".equals(techConfig.getDnsMode())
                ? com.deepseekharness.app.util.UiText.text("原生")
                : com.deepseekharness.app.util.UiText.text("自动");
    TechNote.Model tech = new TechNote.Model();
    tech.key = "config-tech";
    tech.detail =
        com.deepseekharness.app.util.UiText.format(
            "运行方式：%s · 端口：%s\nDNS：%s\n配置保存在应用私有目录，本机 API Key 默认不参与导出。",
            runtimeMode, portMode, dnsMode);
    TechNote.attach((LinearLayout) v.findViewById(R.id.config_column_end), tech, disclosure, null);
    return v;
  }

  @Override
  public void onViewStateRestored(@Nullable Bundle savedInstanceState) {
    super.onViewStateRestored(savedInstanceState);
    // 系统恢复的开关/输入可能晚于 onCreateView，重新渲染一次派生文案与未保存数。
    renderPort();
    renderRuntimeDescription();
    if (root != null) {
      ConfigStore c = new ConfigStore(requireContext());
      renderLanPassword(c.hasLanPassword(), false);
    }
    renderDraft();
  }

  /** 标题列（含副文案）点按时切换同一行的开关；开关的无障碍描述同步为标题 + 副文案。 */
  private void bindRowToggle(View root, int textColumn, CompoundButton target) {
    View column = root.findViewById(textColumn);
    column.setOnClickListener(
        x -> {
          if (target.isEnabled()) target.toggle();
        });
    describe(column, target);
  }

  private static void describe(View column, CompoundButton target) {
    if (!(column instanceof ViewGroup)) return;
    StringBuilder text = new StringBuilder();
    ViewGroup group = (ViewGroup) column;
    for (int i = 0; i < group.getChildCount(); i++) {
      View child = group.getChildAt(i);
      if (child instanceof TextView
          && child.getVisibility() == View.VISIBLE
          && ((TextView) child).getText().length() > 0) {
        if (text.length() > 0) text.append(UiText.text("，"));
        text.append(((TextView) child).getText());
      }
    }
    target.setContentDescription(text.toString());
  }

  /** 无障碍描述用的分句连接；分隔符走现有已翻译的「，」。 */
  private static String join(CharSequence... parts) {
    StringBuilder text = new StringBuilder();
    for (CharSequence part : parts) {
      if (part == null || part.length() == 0) continue;
      if (text.length() > 0) text.append(UiText.text("，"));
      text.append(part);
    }
    return text.toString();
  }

  private void redescribe(int textColumn, int switchId) {
    if (root == null) return;
    describe(root.findViewById(textColumn), root.findViewById(switchId));
  }

  /** 自动端口副文案与固定端口行显隐。 */
  private void renderPort() {
    if (root == null) return;
    CompoundButton automatic = root.findViewById(R.id.config_automatic_port);
    TextView hint = root.findViewById(R.id.config_port_hint);
    View row = root.findViewById(R.id.config_port_row);
    String saved =
        savedDraft != null ? savedDraft.port : new ConfigStore(requireContext()).getPort();
    if (automatic.isChecked()) {
      hint.setText(UiText.format("首选 %s，占用时自动换用", saved));
      hint.setVisibility(View.VISIBLE);
      row.setVisibility(View.GONE);
    } else {
      hint.setText("");
      hint.setVisibility(View.GONE);
      row.setVisibility(View.VISIBLE);
    }
    redescribe(R.id.config_automatic_port_text, R.id.config_automatic_port);
  }

  private void renderRuntimeDescription() {
    if (root == null) return;
    DshaSelectView runtime = root.findViewById(R.id.config_runtime_choice);
    Object selected = runtime.getSelectedItem();
    root.findViewById(R.id.config_runtime_row)
        .setContentDescription(
            join(UiText.text("运行方式"), selected == null ? "" : String.valueOf(selected)));
  }

  /** 局域网副文案、设密码按钮与开启后的未加密提示。{@code saving} 时显示保存中。 */
  private void renderLanPassword(boolean hasPassword, boolean saving) {
    if (root == null) return;
    boolean enabled = ((CompoundButton) root.findViewById(R.id.config_lan_mode)).isChecked();
    TextView status = root.findViewById(R.id.config_lan_password_status);
    if (saving) {
      status.setText(R.string.lan_password_saving);
      status.setTextColor(requireContext().getColor(R.color.shell_text_secondary));
    } else {
      status.setText(
          hasPassword
              ? UiText.choose("已设密码 · 用户名 dsha", "Password set · user dsha")
              : UiText.choose("未设密码 · 用户名 dsha", "No password · user dsha"));
      status.setTextColor(
          requireContext()
              .getColor(
                  !hasPassword && enabled ? R.color.shell_warn : R.color.shell_text_secondary));
    }
    ((TextView) root.findViewById(R.id.config_lan_password))
        .setText(
            hasPassword ? UiText.choose("改密码", "Change") : UiText.choose("设密码", "Set password"));
    TextView hint = root.findViewById(R.id.config_lan_hint);
    hint.setVisibility(enabled ? View.VISIBLE : View.GONE);
    String unencrypted = getString(R.string.ui_lan_unencrypted);
    hint.setText(
        hasPassword
            ? unencrypted
            : UiText.format(
                "%s %s",
                UiText.choose("未设密码时监听不会启动。", "Without a password the listener won't start."),
                unencrypted));
    redescribe(R.id.config_lan_text, R.id.config_lan_mode);
  }

  private void renderCompatibility() {
    if (root == null) return;
    CompoundButton staticLoader = root.findViewById(R.id.config_static_loader);
    CompoundButton noSeccomp = root.findViewById(R.id.config_no_seccomp);
    View body = root.findViewById(R.id.config_adv_body);
    boolean open = body.getVisibility() == View.VISIBLE;
    boolean changed = !staticLoader.isChecked() || noSeccomp.isChecked();
    root.findViewById(R.id.config_adv_badge).setVisibility(changed ? View.VISIBLE : View.GONE);
    root.findViewById(R.id.config_adv_chevron).setRotation(open ? 90f : 0f);
    String summary =
        UiText.format(
            "静态加载器 %s · seccomp 加速 %s",
            staticLoader.isChecked() ? UiText.text("开") : UiText.text("关"),
            noSeccomp.isChecked() ? UiText.text("已禁用") : UiText.text("开"));
    ((TextView) root.findViewById(R.id.config_adv_summary)).setText(summary);
    View header = root.findViewById(R.id.config_adv_header);
    header.setContentDescription(
        changed
            ? join(getString(R.string.ui2_runtime_compat), summary, UiText.choose("已改", "Changed"))
            : join(getString(R.string.ui2_runtime_compat), summary));
    androidx.core.view.ViewCompat.setStateDescription(
        header, open ? UiText.choose("已展开", "Expanded") : UiText.choose("已收起", "Collapsed"));
  }

  private void renderOverlayStatus() {
    if (root == null) return;
    Context ctx = requireContext();
    boolean on = ((CompoundButton) root.findViewById(R.id.config_overlay_stream)).isChecked();
    TextView status = root.findViewById(R.id.config_overlay_status);
    boolean needsPermission = on && !OverlayController.permitted(ctx);
    status.setVisibility(needsPermission ? View.VISIBLE : View.GONE);
    status.setText(
        needsPermission
            ? UiText.choose("保存后需授予悬浮窗权限", "Grant overlay permission after saving")
            : "");
    redescribe(R.id.config_overlay_text, R.id.config_overlay_stream);
  }

  /** 端口校验错误；自动端口时不校验输入框。 */
  @Nullable
  private String portError() {
    if (root == null) return null;
    if (((CompoundButton) root.findViewById(R.id.config_automatic_port)).isChecked()) return null;
    try {
      com.deepseekharness.app.util.ConfigInput.port(
          ((EditText) root.findViewById(R.id.config_port)).getText().toString());
      return null;
    } catch (IllegalArgumentException invalid) {
      return invalid.getMessage();
    }
  }

  private ConfigDraft storedDraft(ConfigStore c) {
    return new ConfigDraft(
        c.isProroot(),
        "auto".equals(c.getWebPortMode()),
        c.getPort(),
        c.getDnsMode(),
        c.isLanMode(),
        c.isProrootStaticLoader(),
        c.isProotSeccompDisabled(),
        c.isDesktopMode(),
        c.isCheckUpdate(),
        pref(requireContext(), "overlay_stream", false));
  }

  @Nullable
  private ConfigDraft currentDraft() {
    if (root == null) return null;
    int dns =
        ((android.widget.RadioGroup) root.findViewById(R.id.config_dns_mode))
            .getCheckedRadioButtonId();
    return new ConfigDraft(
        checked(R.id.config_proroot),
        checked(R.id.config_automatic_port),
        ((EditText) root.findViewById(R.id.config_port)).getText().toString(),
        dns == R.id.dns_ipv4 ? "ipv4" : dns == R.id.dns_native ? "native" : "auto",
        checked(R.id.config_lan_mode),
        checked(R.id.config_static_loader),
        checked(R.id.config_no_seccomp),
        checked(R.id.config_desktop_mode),
        checked(R.id.config_check_update),
        checked(R.id.config_overlay_stream));
  }

  private boolean checked(int id) {
    return ((CompoundButton) root.findViewById(id)).isChecked();
  }

  /** 保存条状态：端口错误 > 未保存数 > 已是最新；同时刷新折叠摘要与悬浮条提示。 */
  private void renderDraft() {
    if (root == null) return;
    renderCompatibility();
    renderOverlayStatus();
    ConfigDraft current = currentDraft();
    int changes = current == null ? 0 : current.changesFrom(savedDraft);
    String error = portError();
    TextView status = root.findViewById(R.id.config_save_status);
    View undo = root.findViewById(R.id.config_undo);
    if (error != null) {
      // 错误态：文字与颜色都走 120–150ms 过渡，不再瞬切（审计 D-03）。
      UiMotion.crossFadeTextColor(status, requireContext().getColor(R.color.shell_bad));
      UiMotion.crossFadeText(status, UiText.choose("端口无效，修正后才能保存", "Invalid port; fix it to save"));
      undo.setVisibility(changes > 0 ? View.VISIBLE : View.GONE);
      return;
    }
    UiMotion.crossFadeTextColor(status, requireContext().getColor(R.color.shell_text_secondary));
    if (changes > 0) {
      android.text.SpannableStringBuilder text = new android.text.SpannableStringBuilder();
      String count = UiText.format("%d 项未保存", changes);
      text.append(count);
      text.setSpan(
          new android.text.style.StyleSpan(android.graphics.Typeface.BOLD),
          0,
          count.length(),
          android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
      text.setSpan(
          new android.text.style.ForegroundColorSpan(requireContext().getColor(R.color.shell_text)),
          0,
          count.length(),
          android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
      text.append("  ")
          .append(UiText.choose("端口与运行时需重启 Web", "Port and runtime apply after restarting Web"));
      UiMotion.crossFadeText(status, text);
      undo.setVisibility(View.VISIBLE);
    } else {
      UiMotion.crossFadeText(status, UiText.choose("配置已是最新", "Settings are up to date"));
      undo.setVisibility(View.GONE);
    }
  }

  private void restoreDraft(@Nullable ConfigDraft d) {
    if (root == null || d == null) return;
    DshaSelectView runtime = root.findViewById(R.id.config_runtime_choice);
    runtime.setSelection(d.proroot ? 1 : 0);
    ((CompoundButton) root.findViewById(R.id.config_proroot)).setChecked(d.proroot);
    ((CompoundButton) root.findViewById(R.id.config_automatic_port)).setChecked(d.automaticPort);
    EditText port = root.findViewById(R.id.config_port);
    port.setText(d.port);
    port.setError(null);
    ((android.widget.RadioGroup) root.findViewById(R.id.config_dns_mode))
        .check(
            "ipv4".equals(d.dnsMode)
                ? R.id.dns_ipv4
                : "native".equals(d.dnsMode) ? R.id.dns_native : R.id.dns_auto);
    ((CompoundButton) root.findViewById(R.id.config_lan_mode)).setChecked(d.lan);
    ((CompoundButton) root.findViewById(R.id.config_static_loader)).setChecked(d.staticLoader);
    ((CompoundButton) root.findViewById(R.id.config_no_seccomp)).setChecked(d.seccompDisabled);
    ((CompoundButton) root.findViewById(R.id.config_desktop_mode)).setChecked(d.desktop);
    ((CompoundButton) root.findViewById(R.id.config_check_update)).setChecked(d.checkUpdate);
    ((CompoundButton) root.findViewById(R.id.config_overlay_stream)).setChecked(d.overlay);
    renderPort();
    renderRuntimeDescription();
    renderDraft();
  }

  /** 内容宽度 ≥ config_two_column_dp 时左右两列；超过最大内容宽度时两侧留白居中。 */
  private void applyColumns() {
    if (root == null) return;
    LinearLayout columns = root.findViewById(R.id.config_columns);
    View start = root.findViewById(R.id.config_column_start);
    View end = root.findViewById(R.id.config_column_end);
    float density = getResources().getDisplayMetrics().density;
    int width = columns.getWidth();
    if (width == 0) return;
    int widthDp = Math.round(width / density);
    boolean two = widthDp >= getResources().getInteger(R.integer.config_two_column_dp);
    int basePad = getResources().getDimensionPixelSize(R.dimen.config_page_pad_h);
    int maxContent =
        Math.round(getResources().getInteger(R.integer.config_max_content_dp) * density);
    int side = two ? Math.max(Math.round(28 * density), (width - maxContent) / 2) : basePad;
    if (columns.getPaddingStart() != side || columns.getPaddingEnd() != side)
      columns.setPaddingRelative(side, columns.getPaddingTop(), side, columns.getPaddingBottom());
    int orientation = two ? LinearLayout.HORIZONTAL : LinearLayout.VERTICAL;
    if (columns.getOrientation() == orientation) return;
    columns.setOrientation(orientation);
    int gap = getResources().getDimensionPixelSize(R.dimen.config_column_gap);
    LinearLayout.LayoutParams a = (LinearLayout.LayoutParams) start.getLayoutParams();
    LinearLayout.LayoutParams b = (LinearLayout.LayoutParams) end.getLayoutParams();
    a.width = two ? 0 : ViewGroup.LayoutParams.MATCH_PARENT;
    b.width = two ? 0 : ViewGroup.LayoutParams.MATCH_PARENT;
    a.weight = two ? 1f : 0f;
    b.weight = two ? 1f : 0f;
    a.setMarginEnd(two ? gap / 2 : 0);
    b.setMarginStart(two ? gap / 2 : 0);
    start.setLayoutParams(a);
    end.setLayoutParams(b);
  }

  @Override
  public void onResume() {
    super.onResume();
    renderPictureInPictureStatus();
    // Web 页内的“启动目标”插件可能已改写该值；回到本页时按宿主真实值重绘。
    renderEntryTarget();
  }

  /** 启动目标即时生效于下一次桌面图标启动；不保存其它字段，也不启动或停止 Web。 */
  private void bindEntryTarget(View root) {
    entryWeb = root.findViewById(R.id.config_entry_web);
    entryWeb.setText(
        com.deepseekharness.app.util.UiText.choose("从桌面图标直接进入 Web", "Open Web from the app icon"));
    TextView entryHint = root.findViewById(R.id.config_entry_web_hint);
    String entryDetail =
        com.deepseekharness.app.util.UiText.choose(
            "开启后点击桌面图标或常驻通知先显示加载页，Web 就绪即进入；在 Web 里返回到底会回到桌面。应用被系统回收后，离开前停在 Web 就回到 Web。环境需要维护或启动失败时仍进入主界面。也可在 Web 的「设置 → 启动目标」中修改；禁用 dsh-launch-target 插件不会改变这里的选择。",
            "When on, the app icon and the ongoing notification show a loading screen and open Web once it is ready; going back from the first Web page returns to the home screen. After the system reclaims the app, it returns to Web if you left from Web. Maintenance or startup failures still open the main screen. You can also change this in Web under Settings → Launch target; disabling the dsh-launch-target plugin keeps this choice.");
    // 行内只留行为摘要；系统回收、维护例外与插件说明展开后再创建。
    entryHint.setText(
        com.deepseekharness.app.util.UiText.choose(
            "开启后从图标直接进入 Web，返回回到桌面", "Open Web straight from the icon; Back returns to home"));
    entryDisclosure.key = "config-entry";
    entryDisclosure.detail = entryDetail;
    DisclosureCard.bind(entryHint, entryDisclosure, disclosure, null);
    entryWeb.setOnCheckedChangeListener(
        (button, enabled) -> {
          if (renderingEntryTarget) return;
          boolean saved =
              new ConfigStore(button.getContext())
                  .setEntryTarget(
                      enabled
                          ? com.deepseekharness.app.util.EntryTarget.WEB
                          : com.deepseekharness.app.util.EntryTarget.MAIN);
          if (!saved)
            Toast.makeText(
                    button.getContext(),
                    com.deepseekharness.app.util.UiText.choose(
                        "启动目标未能保存，请重试", "The launch target was not saved. Try again."),
                    Toast.LENGTH_SHORT)
                .show();
          renderEntryTarget();
        });
    renderEntryTarget();
  }

  private void renderEntryTarget() {
    Context ctx = getContext();
    if (ctx == null || entryWeb == null) return;
    renderingEntryTarget = true;
    entryWeb.setChecked(
        com.deepseekharness.app.util.EntryTarget.WEB.equals(new ConfigStore(ctx).getEntryTarget()));
    renderingEntryTarget = false;
  }

  @Override
  public void onDestroyView() {
    entryWeb = null;
    pictureSettings = null;
    pictureStatus = null;
    pictureEnabled = null;
    root = null;
    super.onDestroyView();
  }

  private void openPictureInPictureSettings() {
    Context ctx = getContext();
    if (ctx == null) return;
    if (!PictureInPictureActivity.supported(ctx)) {
      Toast.makeText(ctx, R.string.picture_in_picture_unsupported, Toast.LENGTH_LONG).show();
      return;
    }
    Uri app = Uri.parse("package:" + ctx.getPackageName());
    try {
      startActivity(new Intent(ACTION_PICTURE_IN_PICTURE_SETTINGS, app));
    } catch (RuntimeException unavailable) {
      try {
        startActivity(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, app));
      } catch (RuntimeException ignored) {
        Toast.makeText(ctx, R.string.picture_in_picture_settings_unavailable, Toast.LENGTH_LONG)
            .show();
      }
    }
  }

  private void renderPictureInPictureStatus() {
    Context ctx = getContext();
    if (ctx == null || pictureSettings == null || pictureStatus == null) return;
    boolean supported = PictureInPictureActivity.supported(ctx);
    if (pictureEnabled != null) {
      renderingPictureInPicture = true;
      pictureEnabled.setChecked(new ConfigStore(ctx).isPictureInPictureEnabled());
      pictureEnabled.setEnabled(supported);
      renderingPictureInPicture = false;
    }
    pictureSettings.setEnabled(supported);
    pictureSettings.setAlpha(supported ? 1f : 0.55f);
    boolean enabled = new ConfigStore(ctx).isPictureInPictureEnabled();
    boolean allowed = supported && enabled && PictureInPictureActivity.allowed(ctx);
    // 行内只显示短状态；完整说明（原有已翻译资源）交给开关的无障碍描述。
    pictureStatus.setText(
        !supported
            ? UiText.choose("设备不支持", "Not supported")
            : !enabled
                ? UiText.text("已关闭")
                : allowed
                    ? UiText.choose("系统已允许", "Allowed by system")
                    : UiText.choose("系统已关闭小窗", "Blocked by system"));
    pictureStatus.setTextColor(
        ctx.getColor(
            supported && enabled && !allowed ? R.color.shell_warn : R.color.shell_text_secondary));
    if (pictureEnabled != null)
      pictureEnabled.setContentDescription(
          join(
              getString(R.string.picture_in_picture_enable),
              getString(
                  !supported
                      ? R.string.picture_in_picture_unsupported
                      : !enabled
                          ? R.string.picture_in_picture_opted_out
                          : allowed
                              ? R.string.picture_in_picture_allowed
                              : R.string.picture_in_picture_disabled)));
  }

  private void openOverlayPermission() {
    try {
      startActivity(
          new Intent(
              Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
              Uri.parse("package:" + requireContext().getPackageName())));
    } catch (Exception e) {
      toast(com.deepseekharness.app.util.UiText.text("请授予悬浮窗权限"));
    }
  }

  private void showLanPasswordDialog(ConfigStore config, TextView status) {
    if (savingLanPassword.get()) {
      Toast.makeText(requireContext(), R.string.lan_password_saving, Toast.LENGTH_SHORT).show();
      return;
    }
    Context context = requireContext();
    LinearLayout fields = new LinearLayout(context);
    fields.setOrientation(LinearLayout.VERTICAL);
    int padding = (int) (20 * getResources().getDisplayMetrics().density);
    fields.setPadding(padding, 0, padding, 0);
    EditText password = new EditText(context);
    password.setHint(R.string.lan_password_new);
    password.setInputType(
        android.text.InputType.TYPE_CLASS_TEXT
            | android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD);
    password.setImportantForAutofill(View.IMPORTANT_FOR_AUTOFILL_NO);
    EditText confirmation = new EditText(context);
    confirmation.setHint(R.string.lan_password_confirm);
    confirmation.setInputType(
        android.text.InputType.TYPE_CLASS_TEXT
            | android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD);
    confirmation.setImportantForAutofill(View.IMPORTANT_FOR_AUTOFILL_NO);
    fields.addView(password);
    fields.addView(confirmation);
    var dialog =
        new DshaDialogBuilder(context)
            .setTitle(R.string.lan_password_button)
            .setMessage(R.string.lan_password_prompt)
            .setView(fields)
            .setNegativeButton(R.string.lan_password_cancel, null)
            .setPositiveButton(R.string.lan_password_save, null)
            .create();
    dialog.show();
    dialog
        .getButton(android.content.DialogInterface.BUTTON_POSITIVE)
        .setOnClickListener(
            ignored -> {
              String value = password.getText().toString();
              if (!com.deepseekharness.app.util.LanPassword.validPassword(value)
                  || !value.equals(confirmation.getText().toString())) {
                confirmation.setError(getString(R.string.lan_password_invalid));
                return;
              }
              if (!savingLanPassword.compareAndSet(false, true)) return;
              password.setText("");
              confirmation.setText("");
              dialog.dismiss();
              renderLanPassword(config.hasLanPassword(), true);
              Context app = context.getApplicationContext();
              new Thread(
                      () -> {
                        boolean saved = false;
                        try {
                          config.setLanPassword(value);
                          saved = true;
                        } catch (RuntimeException ignoredFailure) {
                          // Do not log exception details: the input is a secret.
                        }
                        final boolean success = saved;
                        new android.os.Handler(android.os.Looper.getMainLooper())
                            .post(
                                () -> {
                                  boolean enabled = config.isLanMode();
                                  boolean applied = success && applyLanMode(app, enabled);
                                  savingLanPassword.set(false);
                                  if (!isAdded() || getView() == null) return;
                                  renderLanPassword(config.hasLanPassword(), false);
                                  int message = lanPasswordSavedMessage(success, enabled, applied);
                                  Toast.makeText(app, message, Toast.LENGTH_LONG).show();
                                });
                      },
                      "dsha-lan-password")
                  .start();
            });
  }

  // Submission is not proof of a successful asynchronous bind. The Launch page
  // observes the actual listener and network state.
  static int lanSettingsMessage(boolean enabled, boolean hasPassword, boolean submitted) {
    if (!enabled) return submitted ? 0 : R.string.lan_apply_failed;
    if (!hasPassword) return R.string.lan_password_missing;
    return submitted ? R.string.lan_apply_check : R.string.lan_apply_failed;
  }

  static int lanPasswordSavedMessage(boolean saved, boolean enabled, boolean submitted) {
    if (!saved) return R.string.lan_password_failed;
    if (!submitted) return R.string.lan_password_listener_failed;
    return enabled ? R.string.lan_password_saved_check : R.string.lan_password_waiting;
  }

  /** Submit the desired state; successful submission does not mean binding succeeded. */
  private static boolean applyLanMode(Context context, boolean on) {
    try {
      if (!on) {
        com.deepseekharness.app.LanProxyService.stopLanListener();
        com.deepseekharness.app.HarnessService.refreshPowerMode(context);
        return true;
      }
      com.deepseekharness.app.HarnessService.ensureLanForeground(context);
      HarnessController hc = HarnessController.get(context);
      long gen = hc.getWebGeneration();
      if (gen <= 0 || !com.deepseekharness.app.LanProxyService.hasDshAuth(gen)) return true;
      com.deepseekharness.app.LanProxyService.start(
          hc.proot().getRootfsDir().getAbsolutePath(), context, hc.getWebPort(), gen);
      return true;
    } catch (RuntimeException failure) {
      return false;
    }
  }

  private void open(Fragment f) {
    // 设置双栏里留在右侧详情窗格；单栏或从启动页进入时仍整页推进。
    SettingsPanes.open(this, f, null);
  }

  private void showOverlayStyleDialog() {
    SettingsPanes.open(this, new OverlayFragment(), "config");
  }

  private void openRepo(Context ctx) {
    try {
      startActivity(
          new Intent(
              Intent.ACTION_VIEW,
              Uri.parse(com.deepseekharness.app.util.Constants.REPOSITORY_URL)));
    } catch (Exception e) {
      toast(com.deepseekharness.app.util.UiText.text("无法打开浏览器"));
    }
  }

  private boolean pref(Context ctx, String k, boolean def) {
    return ctx.getSharedPreferences(Constants.PREFS, Context.MODE_PRIVATE).getBoolean(k, def);
  }

  private void setPref(Context ctx, String k, boolean v) {
    ctx.getSharedPreferences(Constants.PREFS, Context.MODE_PRIVATE).edit().putBoolean(k, v).apply();
  }

  private void toast(String s) {
    Context context = getContext();
    if (context != null) Toast.makeText(context, s, Toast.LENGTH_SHORT).show();
  }

  private DshaSelectView bindChoice(
      View root,
      int id,
      String prompt,
      String[] labels,
      int selected,
      java.util.function.IntConsumer action) {
    DshaSelectView choice = root.findViewById(id);
    choice.setPrompt(prompt);
    android.widget.ArrayAdapter<String> adapter =
        new android.widget.ArrayAdapter<>(requireContext(), R.layout.item_config_value, labels);
    choice.setAdapter(adapter);
    choice.setSelection(selected);
    choice.setOnItemSelectedListener(
        new android.widget.AdapterView.OnItemSelectedListener() {
          public void onNothingSelected(android.widget.AdapterView<?> parent) {}

          public void onItemSelected(
              android.widget.AdapterView<?> parent, View view, int position, long item) {
            action.accept(position);
          }
        });
    return choice;
  }
}
