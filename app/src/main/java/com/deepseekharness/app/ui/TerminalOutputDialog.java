package com.deepseekharness.app.ui;

import android.app.Dialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.TextView;
import android.widget.Toast;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.DialogFragment;
import androidx.fragment.app.FragmentManager;
import androidx.lifecycle.MutableLiveData;
import androidx.lifecycle.ViewModel;
import androidx.lifecycle.ViewModelProvider;
import com.deepseekharness.app.R;
import com.deepseekharness.app.util.TerminalOutputSnapshot;
import com.deepseekharness.app.util.UiText;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;

/** 所有操作都使用同一份预览快照；旋转保留快照，进程重建不另取其他会话输出。 */
public final class TerminalOutputDialog extends DialogFragment {
  private static final String TAG = "terminal-output";
  private TerminalOutputSnapshot initialSnapshot;
  private OutputModel model;
  private Button copyButton, shareButton, saveButton;
  private TextView preview, note;
  private final ActivityResultLauncher<String> saveDocument =
      registerForActivityResult(
          new ActivityResultContracts.CreateDocument("text/plain"), this::save);

  public static void show(FragmentManager manager, TerminalOutputSnapshot snapshot) {
    if (manager.isStateSaved() || manager.findFragmentByTag(TAG) != null) return;
    TerminalOutputDialog dialog = new TerminalOutputDialog();
    dialog.initialSnapshot = snapshot;
    dialog.show(manager, TAG);
  }

  /** 只保存在内存；终端输出不写入 SavedState，也不在进程死亡后自动重新捕获。 */
  public static final class OutputModel extends ViewModel {
    TerminalOutputSnapshot snapshot;
    String pendingText;
    boolean history;
    final MutableLiveData<String> state = new MutableLiveData<>("idle");
  }

  @Override
  public void onCreate(@Nullable Bundle saved) {
    super.onCreate(saved);
    model = new ViewModelProvider(this).get(OutputModel.class);
    if (model.snapshot == null) model.snapshot = initialSnapshot;
    initialSnapshot = null;
    model.state.observe(
        this,
        state -> {
          if ("saved".equals(state) || "failed".equals(state)) {
            Toast.makeText(
                    requireContext(),
                    "saved".equals(state)
                        ? UiText.choose("终端输出已保存", "Terminal output saved")
                        : UiText.choose("无法保存终端输出", "Unable to save terminal output"),
                    Toast.LENGTH_LONG)
                .show();
            model.state.setValue("idle");
          }
          updateActions();
        });
  }

  @NonNull
  @Override
  public Dialog onCreateDialog(@Nullable Bundle saved) {
    if (model.snapshot == null) {
      return new DshaDialogBuilder(requireContext())
          .setTitle(UiText.choose("终端输出", "Terminal output"))
          .setMessage(UiText.choose("预览已失效，请重新打开。", "This preview expired. Open it again."))
          .setPositiveButton(UiText.choose("关闭", "Close"), null)
          .create();
    }
    CardPage page =
        new CardPage(
            requireContext(),
            UiText.choose("终端输出", "Terminal output"),
            model.snapshot.sessionName,
            true);
    RadioGroup scope = new RadioGroup(requireContext());
    scope.setOrientation(LinearLayout.VERTICAL);
    RadioButton screen = scopeButton(page, UiText.choose("当前可见屏幕", "Visible screen"));
    RadioButton history = scopeButton(page, UiText.choose("已保留的历史输出", "Retained output history"));
    scope.addView(screen);
    scope.addView(history);
    scope.check(model.history ? history.getId() : screen.getId());
    page.content.addView(scope);
    note = page.text("", 12, R.color.shell_text_secondary);
    note.setPadding(0, page.dp(8), 0, page.dp(12));
    page.content.addView(note);
    preview = page.text("", 13, R.color.shell_text);
    preview.setTypeface(Typeface.MONOSPACE);
    preview.setTextDirection(View.TEXT_DIRECTION_LTR);
    preview.setTextIsSelectable(true);
    preview.setLineSpacing(page.dp(2), 1);
    page.content.addView(preview, new LinearLayout.LayoutParams(-1, -2));
    scope.setOnCheckedChangeListener(
        (group, checked) -> {
          model.history = checked == history.getId();
          render();
          page.scroll.scrollTo(0, 0);
        });
    // 短屏只固定一行动作；保存入口与范围选择同在可滚动正文内。
    saveButton =
        page.button(page.content, UiText.choose("保存文本…", "Save text…"), false, this::pickSave);
    page.content.removeView(saveButton);
    page.content.addView(saveButton, page.content.indexOfChild(note));
    LinearLayout actions = new LinearLayout(requireContext());
    actions.setGravity(Gravity.CENTER);
    page.footer.addView(actions, new LinearLayout.LayoutParams(-1, -2));
    copyButton = footerButton(page, actions, UiText.choose("复制", "Copy"), this::copy);
    shareButton = footerButton(page, actions, UiText.choose("分享", "Share"), this::share);
    footerButton(page, actions, UiText.choose("关闭", "Close"), this::dismiss);
    render();
    return CardSheet.create(requireContext(), page);
  }

  private RadioButton scopeButton(CardPage page, String text) {
    RadioButton button = new androidx.appcompat.widget.AppCompatRadioButton(requireContext());
    button.setId(View.generateViewId());
    button.setText(text);
    button.setTextSize(14);
    button.setMinHeight(page.dp(48));
    button.setLayoutParams(new RadioGroup.LayoutParams(-1, -2));
    return button;
  }

  private Button footerButton(CardPage page, LinearLayout parent, String label, Runnable action) {
    Button button = page.button(parent, label, false, action);
    LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(0, -2, 1);
    params.setMargins(page.dp(3), 0, page.dp(3), 0);
    button.setLayoutParams(params);
    button.setMinHeight(page.dp(48));
    return button;
  }

  @Override
  public void onStart() {
    super.onStart();
    if (model.snapshot != null && getDialog() != null && getDialog().getWindow() != null) {
      getDialog().getWindow().setGravity(Gravity.BOTTOM);
      getDialog().getWindow().setLayout(-1, -2);
    }
  }

  private TerminalOutputSnapshot.Part selected() {
    return model.history ? model.snapshot.history : model.snapshot.screen;
  }

  private void render() {
    TerminalOutputSnapshot.Part part = selected();
    preview.setText(
        part.text().isBlank()
            ? part.truncated()
                ? UiText.choose("输出过长，无法安全生成预览。", "Output is too long to preview safely.")
                : UiText.choose("暂无终端输出", "No terminal output yet")
            : part.text());
    String message =
        UiText.choose("内容已固定为打开预览时的快照。", "Content is a snapshot taken when this preview opened.");
    if (part.truncated() && !part.text().isBlank())
      message =
          UiText.format(
              "%s\n%s",
              message,
              UiText.choose(
                  "输出较长，仅保留最近的部分（最多 64K 字符）。",
                  "Long output: only the most recent portion is retained (up to 64K characters)."));
    note.setText(message);
    updateActions();
  }

  private void updateActions() {
    if (copyButton == null || model == null || model.snapshot == null) return;
    boolean available = !selected().text().isBlank();
    copyButton.setEnabled(available);
    shareButton.setEnabled(available);
    saveButton.setEnabled(available && "idle".equals(model.state.getValue()));
  }

  private void copy() {
    try {
      ClipboardManager clipboard =
          (ClipboardManager) requireContext().getSystemService(Context.CLIPBOARD_SERVICE);
      if (clipboard == null) throw new IllegalStateException("CLIPBOARD_UNAVAILABLE");
      clipboard.setPrimaryClip(
          ClipData.newPlainText(model.snapshot.sessionName, selected().text()));
      Toast.makeText(requireContext(), UiText.text("已复制"), Toast.LENGTH_SHORT).show();
    } catch (RuntimeException error) {
      Toast.makeText(
              requireContext(),
              UiText.choose("无法复制终端输出", "Unable to copy terminal output"),
              Toast.LENGTH_LONG)
          .show();
    }
  }

  private void share() {
    try {
      Intent intent =
          new Intent(Intent.ACTION_SEND)
              .setType("text/plain")
              .putExtra(Intent.EXTRA_TEXT, selected().text());
      startActivity(Intent.createChooser(intent, UiText.choose("分享终端输出", "Share terminal output")));
    } catch (RuntimeException error) {
      Toast.makeText(
              requireContext(),
              UiText.choose("无法打开分享面板", "Unable to open the share picker"),
              Toast.LENGTH_LONG)
          .show();
    }
  }

  private void pickSave() {
    if (!"idle".equals(model.state.getValue())) return;
    model.pendingText = selected().text();
    model.state.setValue("picking");
    try {
      RecentsPrivacyGuard.launch(
          saveDocument, "terminal-output-" + System.currentTimeMillis() + ".txt");
    } catch (RuntimeException error) {
      model.pendingText = null;
      model.state.setValue("idle");
      Toast.makeText(requireContext(), UiText.text("无法打开保存位置选择器，请启用系统文件应用后重试"), Toast.LENGTH_LONG)
          .show();
    }
  }

  private void save(Uri uri) {
    String text = model.pendingText;
    model.pendingText = null;
    if (uri == null || text == null) {
      model.state.setValue("idle");
      return;
    }
    model.state.setValue("saving");
    Context context = requireContext().getApplicationContext();
    OutputModel target = model;
    new Thread(
            () -> {
              try (OutputStream stream = context.getContentResolver().openOutputStream(uri, "wt")) {
                if (stream == null) throw new java.io.IOException("OUTPUT_UNAVAILABLE");
                stream.write(text.getBytes(StandardCharsets.UTF_8));
                stream.flush();
              } catch (Exception error) {
                target.state.postValue("failed");
                return;
              }
              target.state.postValue("saved");
            },
            "dsha-terminal-output-save")
        .start();
  }

  @Override
  public void onDestroyView() {
    copyButton = shareButton = saveButton = null;
    preview = note = null;
    super.onDestroyView();
  }
}
