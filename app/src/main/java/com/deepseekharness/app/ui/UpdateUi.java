package com.deepseekharness.app.ui;

import android.view.View;
import android.widget.Button;
import android.widget.ProgressBar;
import android.widget.RadioGroup;
import android.widget.TextView;
import com.deepseekharness.app.R;
import com.deepseekharness.app.core.UpdateRepository;

/** 更新页的状态与操作排版；下载、校验仍由原有更新引擎负责。 */
final class UpdateUi {
    private UpdateUi() { }
    static void render(View root, UpdateRepository.State state, boolean installing) {
        render(root, state, installing, installing);
    }
    static void render(View root, UpdateRepository.State state, boolean installing, boolean verifyingInstall) {
        boolean busy = state.busy || installing;
        boolean showBytes = state.total > 0 && !verifyingInstall
                && (state.stage == UpdateRepository.Stage.IDLE || state.stage == UpdateRepository.Stage.DOWNLOADING);
        boolean indeterminate = verifyingInstall || state.stage != UpdateRepository.Stage.DOWNLOADING || state.total <= 0;
        RadioGroup channels = root.findViewById(R.id.update_channels);
        ((TextView) root.findViewById(R.id.update_status)).setText(com.deepseekharness.app.util.UiStateText.render(state.message));
        ((TextView) root.findViewById(R.id.update_notes)).setText(state.release == null ? ""
                : state.release.version + " · " + String.format(java.util.Locale.ROOT, "%.2f MiB", state.release.bytes / 1048576.0) + "\n\n" + state.release.notes);
        ProgressBar progress = root.findViewById(R.id.update_progress);
        progress.setVisibility(state.busy || verifyingInstall ? View.VISIBLE : View.GONE);
        progress.setIndeterminate(indeterminate);
        if (!indeterminate) progress.setProgress((int) (state.downloaded * 100 / state.total));
        ((TextView) root.findViewById(R.id.update_bytes)).setText(showBytes ? String.format(java.util.Locale.ROOT,
                "%.1f / %.1f MiB", state.downloaded / 1048576.0, state.total / 1048576.0) : "");
        root.findViewById(R.id.update_release_panel).setVisibility(state.release != null ? android.view.View.VISIBLE : android.view.View.GONE);
        root.findViewById(R.id.update_bytes).setVisibility(showBytes ? View.VISIBLE : View.GONE);
        root.findViewById(R.id.update_download).setVisibility(state.release != null && state.apk == null ? android.view.View.VISIBLE : android.view.View.GONE);
        root.findViewById(R.id.update_install).setVisibility(state.apk != null ? android.view.View.VISIBLE : android.view.View.GONE);
        Button check = root.findViewById(R.id.update_check);
        check.setEnabled(!busy);
        check.setText(state.release == null ? com.deepseekharness.app.util.UiText.text("检查更新") : com.deepseekharness.app.util.UiText.text("重新检查"));
        check.setBackgroundResource(state.release == null ? R.drawable.bg_btn_primary : R.drawable.bg_btn);
        check.setTextColor(androidx.core.content.ContextCompat.getColorStateList(root.getContext(),state.release == null ? R.color.button_primary_text : R.color.button_text));
        root.findViewById(R.id.update_download).setEnabled(!busy && state.release != null);
        ((Button) root.findViewById(R.id.update_download)).setText(state.downloaded > 0 && state.apk == null ? com.deepseekharness.app.util.UiText.text("继续下载") : com.deepseekharness.app.util.UiText.text("下载更新"));
        root.findViewById(R.id.update_install).setEnabled(!busy && state.apk != null);
        root.findViewById(R.id.update_cancel).setVisibility(state.busy && !installing ? View.VISIBLE : View.GONE);
        for (int i = 0; i < channels.getChildCount(); i++) channels.getChildAt(i).setEnabled(!busy);

    }
}
