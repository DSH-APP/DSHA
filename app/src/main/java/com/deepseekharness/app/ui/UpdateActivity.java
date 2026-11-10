package com.deepseekharness.app.ui;

import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.view.View;
import android.widget.RadioGroup;
import android.widget.TextView;
import android.widget.Toast;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.FileProvider;
import androidx.lifecycle.ViewModelProvider;
import com.deepseekharness.app.BuildConfig;
import com.deepseekharness.app.R;
import com.deepseekharness.app.core.UpdateRepository;
import com.deepseekharness.app.util.CurrentReleaseNotes;
import com.deepseekharness.app.util.UpdatePolicy;

/** 更新由用户选择通道和确认安装；下载任务不依赖页面生命周期。 */
public final class UpdateActivity extends AppCompatActivity {
  private UpdateRepository repository;
  private boolean resumeInstall;
  private boolean installDispatchReady;
  private boolean versionExpanded;
  // 平板横屏右栏标签页；手机与平板竖屏为 null。
  private RadioGroup paneTabs;
  private boolean paneChosen;
  private boolean selectingPane;

  @Override
  protected void onCreate(Bundle saved) {
    super.onCreate(saved);
    setContentView(R.layout.activity_update);
    PressFeedback.attachButtons(findViewById(android.R.id.content));
    repository = new ViewModelProvider(this).get(UpdateRepository.class);
    resumeInstall = saved != null && saved.getBoolean("resumeInstall");
    repository.restoreInterruptedInstall(saved != null && saved.getBoolean("installPending"));
    versionExpanded = saved != null && saved.getBoolean("versionExpanded");
    paneChosen = saved != null && saved.getBoolean("paneChosen");
    ((TextView) findViewById(R.id.update_current)).setText(BuildConfig.VERSION_NAME);
    ((TextView) findViewById(R.id.update_code)).setText(String.valueOf(BuildConfig.VERSION_CODE));
    String buildLabel =
        getString(
            R.string.ui134_changelog_sub,
            CurrentReleaseNotes.versionLabel(BuildConfig.VERSION_NAME),
            BuildConfig.VERSION_CODE);
    ((TextView) findViewById(R.id.update_changelog_summary)).setText(buildLabel);
    ((TextView) findViewById(R.id.update_version_line)).setText(buildLabel);
    ((TextView) findViewById(R.id.update_edition))
        .setText(com.deepseekharness.app.util.UiText.choose("标准版", "Standard"));
    bindVersionDetails();
    RadioGroup channels = findViewById(R.id.update_channels);
    channels.check(
        UpdatePolicy.PREVIEW.equals(repository.channel())
            ? R.id.update_preview
            : R.id.update_stable);
    // 切换通道即重新检查（UpdateEngine.setChannel）；检查中切换会作废旧检查，下载 / 校验时由 UpdateUi 锁定两段按钮。
    channels.setOnCheckedChangeListener(
        (g, id) ->
            repository.setChannel(
                id == R.id.update_preview ? UpdatePolicy.PREVIEW : UpdatePolicy.STABLE));
    findViewById(R.id.update_back).setOnClickListener(v -> finish());
    ((TextView) findViewById(R.id.update_dsh))
        .setText(com.deepseekharness.app.util.Constants.DSH_VERSION);
    findViewById(R.id.update_runtime).setOnClickListener(v -> showRuntimePlan());
    findViewById(R.id.update_runtime_rollback)
        .setOnClickListener(v -> RuntimeRecoveryUi.show(this));

    paneTabs = findViewById(R.id.update_pane_tabs);
    if (paneTabs != null) bindPane(saved);
    else centerColumn();
    findViewById(R.id.update_changelog)
        .setOnClickListener(
            v -> {
              if (paneTabs != null) choosePane(R.id.update_pane_current);
              else CardSheet.show(this, getString(R.string.ui134_changelog), bundledChangelog());
            });
    findViewById(R.id.update_release_detail)
        .setOnClickListener(
            v -> {
              UpdateRepository.State state = repository.state().getValue();
              if (state == null || state.release == null) return;
              if (paneTabs != null) choosePane(R.id.update_pane_candidate);
              else showRelease(state.release);
            });
    findViewById(R.id.update_check).setOnClickListener(v -> repository.check());
    UpdateProgressButton action = findViewById(R.id.update_action);
    action.setOnClickListener(
        v -> {
          switch (action.action().command) {
            case CHECK:
              repository.check();
              break;
            case DOWNLOAD:
              download();
              break;
            case INSTALL:
              install();
              break;
            default:
              break;
          }
        });
    findViewById(R.id.update_browser)
        .setOnClickListener(
            v -> {
              UpdateRepository.State state = repository.state().getValue();
              AboutDialog.openBrowser(
                  this,
                  state != null && state.release != null
                      ? state.release.pageUrl
                      : com.deepseekharness.app.util.Constants.REPOSITORY_URL + "/releases");
            });
    repository.state().observe(this, this::renderState);
    repository
        .installation()
        .observe(
            this,
            state -> {
              renderState(repository.state().getValue());
              dispatchInstall();
            });
    if (saved == null && !repository.hasTask()) repository.check();
  }

  private void showRuntimePlan() {
    CardPage page =
        new CardPage(
            this,
            getString(R.string.ui2_managed_update),
            com.deepseekharness.app.util.UiText.choose(
                "查看当前环境与包内运行组件。", "Review the current environment and bundled components."));
    android.widget.LinearLayout metadata = page.card();
    page.kv(metadata, "Ubuntu", bundledBase());
    page.kv(metadata, "DSH", com.deepseekharness.app.util.Constants.DSH_VERSION);
    page.kv(
        metadata,
        com.deepseekharness.app.util.UiText.choose("个人数据", "Personal data"),
        com.deepseekharness.app.util.UiText.choose("保持原位", "Kept in place"));
    metadata.addView(
        page.text(
            com.deepseekharness.app.util.UiText.choose(
                "检查后展示实际差异；确认更新时停止 Web 与终端，准备候选并完成隔离试运行。",
                "Review actual differences first. Updating stops Web and terminals, prepares a candidate and verifies it in an isolated trial."),
            12,
            R.color.text_secondary));
    var dialog = CardSheet.create(this, page);
    page.button(
        page.footer,
        com.deepseekharness.app.util.UiText.choose("检查更新计划", "Review update plan"),
        true,
        () -> {
          dialog.dismiss();
          startActivity(new Intent(this, ExtractActivity.class).putExtra("review_only", true));
        });
    page.button(
        page.footer,
        com.deepseekharness.app.util.UiText.choose("取消", "Cancel"),
        false,
        dialog::dismiss);
    CardSheet.show(dialog, this);
  }

  private String bundledBase() {
    try (java.io.InputStream input = getAssets().open("offline-rootfs.version")) {
      byte[] bytes = new byte[64];
      int count = input.read(bytes);
      return count > 0
          ? new String(bytes, 0, count, java.nio.charset.StandardCharsets.UTF_8).trim()
          : "—";
    } catch (java.io.IOException unavailable) {
      return "—";
    }
  }

  private String bundledChangelog() {
    try (java.io.InputStream input = getResources().openRawResource(R.raw.changelog);
        java.io.ByteArrayOutputStream output = new java.io.ByteArrayOutputStream()) {
      byte[] block = new byte[2048];
      int count;
      while ((count = input.read(block)) != -1) {
        if (output.size() + count > 16384) throw new java.io.IOException("CHANGELOG_LIMIT");
        output.write(block, 0, count);
      }
      String notes = output.toString("UTF-8");
      if (!CurrentReleaseNotes.matches(BuildConfig.VERSION_NAME, BuildConfig.VERSION_CODE, notes))
        com.deepseekharness.app.core.DiagnosticLog.record(
            this, "changelog", "Bundled notes do not match the installed build");
      return currentChangelog(notes);
    } catch (java.io.IOException | android.content.res.Resources.NotFoundException failure) {
      com.deepseekharness.app.core.DiagnosticLog.record(this, "changelog", failure.toString());
      return currentChangelog(null);
    }
  }

  private String currentChangelog(String notes) {
    return CurrentReleaseNotes.render(
        BuildConfig.VERSION_NAME,
        BuildConfig.VERSION_CODE,
        notes,
        com.deepseekharness.app.util.UiText.language());
  }

  /** 版本一行可展开完整版本、版本码与 DSH；展开块里可复制。 */
  private void bindVersionDetails() {
    View toggle = findViewById(R.id.update_version_toggle);
    toggle.setOnClickListener(
        v -> {
          versionExpanded = !versionExpanded;
          applyVersionExpanded();
        });
    toggle.setAccessibilityDelegate(
        new View.AccessibilityDelegate() {
          @Override
          public void onInitializeAccessibilityNodeInfo(
              View host, android.view.accessibility.AccessibilityNodeInfo info) {
            super.onInitializeAccessibilityNodeInfo(host, info);
            info.addAction(
                versionExpanded
                    ? android.view.accessibility.AccessibilityNodeInfo.AccessibilityAction
                        .ACTION_COLLAPSE
                    : android.view.accessibility.AccessibilityNodeInfo.AccessibilityAction
                        .ACTION_EXPAND);
          }

          @Override
          public boolean performAccessibilityAction(View host, int action, Bundle args) {
            if (action == android.view.accessibility.AccessibilityNodeInfo.ACTION_EXPAND
                || action == android.view.accessibility.AccessibilityNodeInfo.ACTION_COLLAPSE) {
              host.performClick();
              return true;
            }
            return super.performAccessibilityAction(host, action, args);
          }
        });
    findViewById(R.id.update_copy_version)
        .setOnClickListener(
            v -> {
              android.content.ClipboardManager clipboard =
                  getSystemService(android.content.ClipboardManager.class);
              if (clipboard == null) return;
              clipboard.setPrimaryClip(
                  android.content.ClipData.newPlainText(
                      "DSHA",
                      BuildConfig.VERSION_NAME
                          + " / build"
                          + BuildConfig.VERSION_CODE
                          + " / DSH "
                          + com.deepseekharness.app.util.Constants.DSH_VERSION));
              Toast.makeText(
                      this, com.deepseekharness.app.util.UiText.text("已复制版本信息"), Toast.LENGTH_SHORT)
                  .show();
            });
    applyVersionExpanded();
  }

  private void applyVersionExpanded() {
    findViewById(R.id.update_version_details)
        .setVisibility(versionExpanded ? View.VISIBLE : View.GONE);
    findViewById(R.id.update_version_chevron).setRotation(versionExpanded ? 180f : 0f);
  }

  /** 平板横屏右栏：候选版本 / 本版本两个标签页；有候选时默认看候选，用户切过之后不再自动切换。 */
  private void bindPane(Bundle saved) {
    String current = bundledChangelog();
    int split = current.indexOf('\n');
    ((TextView) findViewById(R.id.update_pane_current_title))
        .setText(split > 0 ? current.substring(0, split).trim() : current.trim());
    ((TextView) findViewById(R.id.update_pane_current_notes))
        .setText(split > 0 ? current.substring(split + 1).trim() : "");
    int initial =
        saved != null
            ? saved.getInt("pane", R.id.update_pane_candidate)
            : R.id.update_pane_candidate;
    // 左栏两行与右栏内容对应：选中行用 activated 软底，卡片按圆角裁切。
    for (int id : new int[] {R.id.update_release_detail, R.id.update_changelog}) {
      View row = findViewById(id);
      row.setBackgroundResource(R.drawable.bg_update_pane_row);
      ((View) row.getParent()).setClipToOutline(true);
    }
    paneTabs.check(initial);
    applyPane(initial);
    paneTabs.setOnCheckedChangeListener(
        (group, id) -> {
          if (!selectingPane) paneChosen = true;
          applyPane(id);
        });
  }

  private void choosePane(int id) {
    paneChosen = true;
    paneTabs.check(id);
  }

  private void autoPane(int id) {
    if (paneChosen || paneTabs.getCheckedRadioButtonId() == id) return;
    selectingPane = true;
    paneTabs.check(id);
    selectingPane = false;
  }

  private void applyPane(int id) {
    boolean candidate = id != R.id.update_pane_current;
    findViewById(R.id.update_pane_candidate_body)
        .setVisibility(candidate ? View.VISIBLE : View.GONE);
    findViewById(R.id.update_pane_current_body).setVisibility(candidate ? View.GONE : View.VISIBLE);
    findViewById(R.id.update_release_detail).setActivated(candidate);
    findViewById(R.id.update_changelog).setActivated(!candidate);
  }

  /** 平板竖屏（600–839dp）：单栏收成居中约 640dp，不随屏宽拉满。 */
  private void centerColumn() {
    View scroll = findViewById(R.id.update_scroll);
    View content = findViewById(R.id.update_content);
    int base = content.getPaddingStart();
    scroll.addOnLayoutChangeListener(
        (v, l, t, r, b, ol, ot, or, ob) -> {
          int column = Math.round(640 * getResources().getDisplayMetrics().density);
          int side = Math.max(base, (v.getWidth() - column) / 2);
          if (side != content.getPaddingStart())
            v.post(
                () ->
                    content.setPaddingRelative(
                        side, content.getPaddingTop(), side, content.getPaddingBottom()));
        });
  }

  private void showRelease(UpdatePolicy.Release release) {
    String notes = release.notes == null ? "" : release.notes.trim();
    CardSheet.show(
        this,
        UpdateUi.releaseTitle(this, release),
        UpdateUi.releaseMeta(release) + (notes.isEmpty() ? "" : "\n\n" + notes));
  }

  private void download() {
    if (android.os.Build.VERSION.SDK_INT >= 33
        && checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS)
            != android.content.pm.PackageManager.PERMISSION_GRANTED)
      requestPermissions(new String[] {android.Manifest.permission.POST_NOTIFICATIONS}, 104);
    else repository.download();
  }

  private void renderState(UpdateRepository.State state) {
    if (state == null) return;
    UpdateRepository.InstallState install = repository.installation().getValue();
    UpdateUi.render(
        findViewById(android.R.id.content),
        state,
        install.pending(),
        install.verifying,
        install.error != null);
    if (paneTabs != null)
      autoPane(state.release != null ? R.id.update_pane_candidate : R.id.update_pane_current);
    if (install.pending()) {
      ((TextView) findViewById(R.id.update_status))
          .setText(
              install.verifying
                  ? com.deepseekharness.app.util.UiText.text("正在重新校验安装包…")
                  : com.deepseekharness.app.util.UiText.text("校验完成，返回此页面后继续安装"));
    } else if (install.error != null) {
      ((TextView) findViewById(R.id.update_status))
          .setText(
              com.deepseekharness.app.util.UiText.format(
                  "%s\n%s", UpdateUi.status(state.message), UpdateUi.status(install.error)));
    }
  }

  private void install() {
    if (repository.installationPending()) return;
    try {
      if (android.os.Build.VERSION.SDK_INT >= 26
          && !getPackageManager().canRequestPackageInstalls()) {
        resumeInstall = true;
        startActivity(
            new Intent(
                android.provider.Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                Uri.parse("package:" + getPackageName())));
        return;
      }
      repository.requestInstall();
    } catch (Exception error) {
      resumeInstall = false;
      showInstallError(error);
    }
  }

  private void dispatchInstall() {
    if (!installDispatchReady
        || isFinishing()
        || isDestroyed()
        || getSupportFragmentManager().isStateSaved()) return;
    java.io.File apk = repository.takeInstallReady();
    if (apk == null) return;
    try {
      Uri uri = FileProvider.getUriForFile(this, getPackageName() + ".updates", apk);
      startActivity(
          new Intent(Intent.ACTION_VIEW)
              .setDataAndType(uri, "application/vnd.android.package-archive")
              .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION));
    } catch (Exception error) {
      showInstallError(error);
    }
  }

  private void showInstallError(Exception error) {
    com.deepseekharness.app.core.DiagnosticLog.record(this, "APP_UPDATE_INSTALL", error.toString());
    repository.installFailed(
        com.deepseekharness.app.util.UiText.choose(
            "无法打开系统安装器，请重试或在浏览器下载安装。",
            "Unable to open the system installer. Retry or download in the browser."));
    Toast.makeText(this, repository.installation().getValue().error, Toast.LENGTH_LONG).show();
  }

  @Override
  public void onRequestPermissionsResult(int request, String[] permissions, int[] results) {
    super.onRequestPermissionsResult(request, permissions, results);
    if (request == 104) repository.download();
  }

  @Override
  protected void onSaveInstanceState(Bundle saved) {
    saved.putBoolean("resumeInstall", resumeInstall);
    saved.putBoolean("installPending", repository.installationPending());
    saved.putBoolean("versionExpanded", versionExpanded);
    saved.putBoolean("paneChosen", paneChosen);
    if (paneTabs != null) saved.putInt("pane", paneTabs.getCheckedRadioButtonId());
    super.onSaveInstanceState(saved);
  }

  @Override
  protected void onResume() {
    super.onResume();
    if (resumeInstall) {
      resumeInstall = false;
      if (android.os.Build.VERSION.SDK_INT < 26 || getPackageManager().canRequestPackageInstalls())
        install();
      else
        Toast.makeText(
                this, com.deepseekharness.app.util.UiText.text("未允许安装更新，可稍后重试"), Toast.LENGTH_SHORT)
            .show();
    }
  }

  @Override
  protected void onPostResume() {
    super.onPostResume();
    installDispatchReady = true;
    dispatchInstall();
  }

  @Override
  protected void onPause() {
    installDispatchReady = false;
    super.onPause();
  }
}
