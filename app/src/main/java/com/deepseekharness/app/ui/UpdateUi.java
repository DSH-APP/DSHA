package com.deepseekharness.app.ui;

import android.content.Context;
import android.content.res.ColorStateList;
import android.view.View;
import android.widget.RadioGroup;
import android.widget.TextView;
import androidx.core.content.ContextCompat;
import com.deepseekharness.app.R;
import com.deepseekharness.app.core.UpdateRepository;
import com.deepseekharness.app.util.UiText;
import com.deepseekharness.app.util.UpdateActionPolicy;
import com.deepseekharness.app.util.UpdatePolicy;
import java.util.Locale;

/**
 * 更新页的状态与操作排版；下载、校验仍由原有更新引擎负责。
 *
 * <p>主按钮 {@link UpdateProgressButton} 原位切换检查 / 下载 / 核验 / 安装，按钮本身就是进度条；何时显示什么由
 * {@link UpdateActionPolicy} 推导。页面不提供取消，下载通知栏仍保留“取消”。
 */
final class UpdateUi {
  private UpdateUi() {}

  /** Last display boundary for old persisted failures; technical details stay in diagnostics. */
  static String status(String message) {
    String rendered = com.deepseekharness.app.util.UiStateText.render(message);
    if (rendered.matches(
        "(?s).*(?:java[x]?\\.[\\w.$]*(?:Exception|Error)|\\n\\s*at \\S+\\([^\\n]*\\)).*"))
      return UiText.choose(
          "更新操作未完成，请重试或在浏览器查看版本。",
          "The update could not be completed. Retry or view releases in the browser.");
    return rendered;
  }

  static void render(View root, UpdateRepository.State state, boolean installing) {
    render(root, state, installing, installing);
  }

  /**
   * @param installing 安装交接未完成（重新校验中，或已校验待页面领取）
   * @param verifyingInstall 安装前重新校验中
   */
  static void render(
      View root, UpdateRepository.State state, boolean installing, boolean verifyingInstall) {
    render(root, state, installing, verifyingInstall, false);
  }

  static UpdateActionPolicy.Action render(
      View root,
      UpdateRepository.State state,
      boolean installing,
      boolean verifyingInstall,
      boolean installError) {
    UpdateActionPolicy.Action action =
        UpdateActionPolicy.resolve(
            stage(state.stage),
            state.release != null,
            state.apk != null,
            state.downloaded,
            state.total,
            verifyingInstall,
            installing && !verifyingInstall,
            installError);
    Context context = root.getContext();
    TextView status = root.findViewById(R.id.update_status);
    status.setText(status(state.message));
    status.setTextColor(
        ContextCompat.getColor(
            context,
            action.tone == UpdateActionPolicy.Tone.BAD ? R.color.shell_bad : R.color.shell_text));
    root.findViewById(R.id.update_status_dot)
        .setBackgroundTintList(
            ColorStateList.valueOf(ContextCompat.getColor(context, tone(action.tone))));
    View recheck = root.findViewById(R.id.update_check);
    recheck.setVisibility(action.recheck ? View.VISIBLE : View.GONE);
    recheck.setEnabled(action.recheck);
    UpdateProgressButton button = root.findViewById(R.id.update_action);
    button.bind(action, label(action, state));
    RadioGroup channels = root.findViewById(R.id.update_channels);
    for (int i = 0; i < channels.getChildCount(); i++)
      channels.getChildAt(i).setEnabled(action.channelEnabled);
    release(root, state.release);
    return action;
  }

  static UpdateActionPolicy.Stage stage(UpdateRepository.Stage stage) {
    return stage == null
        ? UpdateActionPolicy.Stage.IDLE
        : UpdateActionPolicy.Stage.valueOf(stage.name());
  }

  private static int tone(UpdateActionPolicy.Tone tone) {
    switch (tone) {
      case ACCENT:
        return R.color.shell_accent;
      case GOOD:
        return R.color.shell_good;
      case BAD:
        return R.color.shell_bad;
      default:
        return R.color.shell_text_muted;
    }
  }

  /** 按钮文字：数字与单位不翻译，句子走文案目录。 */
  static String label(UpdateActionPolicy.Action action, UpdateRepository.State state) {
    switch (action.label) {
      case DOWNLOAD:
        {
          long bytes =
              state.total > 0 ? state.total : state.release == null ? 0 : state.release.bytes;
          return bytes > 0 ? UiText.format("下载更新 · %s", mib(bytes)) : UiText.text("下载更新");
        }
      case RESUME:
        return action.percent >= 0
            ? UiText.format("继续下载 · 已下载 %d%%", action.percent)
            : UiText.text("继续下载");
      case INSTALL:
        return UiText.text("安装更新");
      case CHECKING:
        return UiText.text("正在检查…");
      case DOWNLOADING:
        return action.percent >= 0
            ? String.format(
                Locale.ROOT,
                "%d%% · %.1f / %.1f MiB",
                action.percent,
                state.downloaded / 1048576.0,
                state.total / 1048576.0)
            : UiText.text("正在下载");
      case VERIFYING:
        return UiText.text("正在核验签名…");
      case REVERIFYING:
        return UiText.text("正在重新校验安装包…");
      case OPENING_INSTALLER:
        return UiText.text("即将打开系统安装器");
      default:
        return UiText.text("检查更新");
    }
  }

  static String mib(long bytes) {
    return String.format(Locale.ROOT, "%.1f MiB", bytes / 1048576.0);
  }

  static String channelName(Context context, String channel) {
    return context.getString(
        UpdatePolicy.PREVIEW.equals(channel) ? R.string.ui_m0215 : R.string.ui_m0173);
  }

  static String releaseTitle(Context context, UpdatePolicy.Release release) {
    return channelName(context, release.channel) + " " + release.version;
  }

  static String releaseMeta(UpdatePolicy.Release release) {
    return "build" + release.versionCode + " · " + mib(release.bytes);
  }

  /** 候选卡（手机）与右栏（平板横屏）共用：标题、build·大小、更新记录；没有候选时右栏显示空态。 */
  private static void release(View root, UpdatePolicy.Release release) {
    root.findViewById(R.id.update_release_panel)
        .setVisibility(release != null ? View.VISIBLE : View.GONE);
    Context context = root.getContext();
    String title = release == null ? "" : releaseTitle(context, release);
    String meta = release == null ? "" : releaseMeta(release);
    String notes = release == null || release.notes == null ? "" : release.notes.trim();
    text(root, R.id.update_release_title, title);
    text(root, R.id.update_release_meta, meta);
    text(root, R.id.update_notes, notes);
    text(root, R.id.update_pane_title, title);
    text(root, R.id.update_pane_meta, meta);
    View empty = root.findViewById(R.id.update_pane_empty);
    if (empty == null) return;
    empty.setVisibility(release == null ? View.VISIBLE : View.GONE);
    for (int id : new int[] {R.id.update_pane_title, R.id.update_pane_meta, R.id.update_notes})
      root.findViewById(id).setVisibility(release == null ? View.GONE : View.VISIBLE);
  }

  private static void text(View root, int id, CharSequence value) {
    TextView view = root.findViewById(id);
    if (view != null) view.setText(value);
  }
}
