package com.deepseekharness.app.ui;

import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.provider.Settings;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.CompoundButton;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;

import com.deepseekharness.app.BuildConfig;
import com.deepseekharness.app.R;
import com.deepseekharness.app.core.ConfigStore;
import com.deepseekharness.app.util.EnvironmentTaskGate;
import com.deepseekharness.app.util.SensitiveData;
import com.deepseekharness.app.util.UiText;

import java.util.concurrent.atomic.AtomicInteger;

/** 网页显示子页：网页内核、桌面版布局与系统画中画设置入口。 */
public class WebDisplayFragment extends Fragment {
  // Settings 没有在所有 compileSdk stub 中暴露该常量，使用公开的 action 字符串保持 API 26+ 兼容。
  private static final String ACTION_PICTURE_IN_PICTURE_SETTINGS =
      "android.settings.PICTURE_IN_PICTURE_SETTINGS";
  private View pictureSettings;
  private TextView pictureStatus;

  @Nullable
  @Override
  public View onCreateView(
      @NonNull LayoutInflater inflater,
      @Nullable ViewGroup container,
      @Nullable Bundle savedInstanceState) {
    View v = inflater.inflate(R.layout.fragment_web_display, container, false);
    Context ctx = requireContext();
    ConfigStore c = new ConfigStore(ctx);
    CompoundButton desktop = v.findViewById(R.id.web_desktop);
    Button save = v.findViewById(R.id.web_save);
    desktop.setChecked(c.isDesktopMode());

    v.findViewById(R.id.web_gecko_hint)
        .setVisibility(BuildConfig.LOW_ANDROID ? View.VISIBLE : View.GONE);
    AtomicInteger coreIndex = new AtomicInteger(BuildConfig.LOW_ANDROID && c.isGeckoCore() ? 1 : 0);
    bindChoice(
        v,
        R.id.web_core_choice,
        UiText.choose("网页内核", "Browser engine"),
        BuildConfig.LOW_ANDROID
            ? new String[] {UiText.choose("自动选择内核", "Automatic engine"), "Gecko"}
            : new String[] {"System WebView"},
        coreIndex.get(),
        coreIndex::set);

    pictureSettings = v.findViewById(R.id.web_pip_settings);
    pictureStatus = v.findViewById(R.id.web_pip_status);
    pictureSettings.setOnClickListener(x -> openPictureInPictureSettings());
    renderPictureInPictureStatus();

    save.setOnClickListener(
        x -> {
          EnvironmentTaskGate.Lease saving = EnvironmentTaskGate.tryAcquire(UiText.text("保存配置"));
          if (saving == null) {
            toast(UiText.format("正在%s，完成后再保存配置", EnvironmentTaskGate.activeKind()));
            return;
          }
          try {
            saving.run(
                () -> {
                  c.setDesktopMode(desktop.isChecked());
                  if (BuildConfig.LOW_ANDROID) c.setGeckoCore(coreIndex.get() == 1);
                  Context context = getContext();
                  if (context != null)
                    Toast.makeText(
                            context,
                            UiText.choose(
                                "已保存；重新进入对话生效", "Saved. Re-enter the conversation to apply."),
                            Toast.LENGTH_LONG)
                        .show();
                  return null;
                });
          } catch (Exception e) {
            toast(UiText.format("配置保存未完成：%s", SensitiveData.redact(String.valueOf(e))));
          } finally {
            saving.close();
          }
        });
    return v;
  }

  @Override
  public void onResume() {
    super.onResume();
    renderPictureInPictureStatus();
  }

  @Override
  public void onDestroyView() {
    pictureSettings = null;
    pictureStatus = null;
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
    pictureSettings.setEnabled(supported);
    pictureSettings.setAlpha(supported ? 1f : 0.55f);
    pictureStatus.setText(
        !supported
            ? R.string.picture_in_picture_unsupported
            : PictureInPictureActivity.allowed(ctx)
                ? R.string.picture_in_picture_allowed
                : R.string.picture_in_picture_disabled);
  }

  private void toast(String s) {
    Context context = getContext();
    if (context != null) Toast.makeText(context, s, Toast.LENGTH_SHORT).show();
  }

  private void bindChoice(
      View root,
      int id,
      String prompt,
      String[] labels,
      int selected,
      java.util.function.IntConsumer action) {
    DshaSelectView choice = root.findViewById(id);
    choice.setPrompt(prompt);
    choice.setAdapter(new ArrayAdapter<>(requireContext(), R.layout.item_data_choice, labels));
    choice.setSelection(selected);
    choice.setOnItemSelectedListener(
        new AdapterView.OnItemSelectedListener() {
          public void onNothingSelected(AdapterView<?> parent) {}

          public void onItemSelected(AdapterView<?> parent, View view, int position, long item) {
            action.accept(position);
          }
        });
  }
}
