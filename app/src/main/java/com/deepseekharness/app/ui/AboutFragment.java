package com.deepseekharness.app.ui;

import android.os.Bundle;
import android.view.*;
import android.widget.*;
import android.content.Intent;
import androidx.fragment.app.Fragment;
import com.deepseekharness.app.*;
import com.deepseekharness.app.util.UiText;

public final class AboutFragment extends Fragment {
  @Override
  public View onCreateView(LayoutInflater inflater, ViewGroup container, Bundle saved) {
    CardPage ui = new CardPage(requireContext(), UiText.choose("关于 DSHA", "About DSHA"), "");
    android.widget.FrameLayout emblem = new android.widget.FrameLayout(requireContext());
    android.widget.ImageView mark = new android.widget.ImageView(requireContext());
    mark.setImageResource(R.drawable.ic_ui2_prompt);
    mark.setBackgroundResource(R.drawable.bg_ui2_mark);
    mark.setPadding(ui.dp(14), ui.dp(14), ui.dp(14), ui.dp(14));
    emblem.addView(
        mark, new android.widget.FrameLayout.LayoutParams(ui.dp(64), ui.dp(64), Gravity.CENTER));
    ui.content.addView(emblem, new LinearLayout.LayoutParams(-1, ui.dp(84)));
    TextView brand = ui.text("DSHA", 30, R.color.text);
    brand.setTypeface(null, android.graphics.Typeface.BOLD);
    brand.setGravity(Gravity.CENTER);
    brand.setPadding(0, ui.dp(16), 0, ui.dp(10));
    ui.content.addView(brand);
    TextView version =
        ui.text(
            "DeepSeek Harness for Android\n"
                + BuildConfig.VERSION_NAME
                + " · "
                + BuildConfig.VERSION_CODE,
            12,
            R.color.text_secondary);
    version.setGravity(Gravity.CENTER);
    version.setPadding(0, 0, 0, ui.dp(24));
    ui.content.addView(version);
    LinearLayout metadata = ui.card();
    ui.kv(metadata, UiText.choose("包名", "Package"), BuildConfig.APPLICATION_ID);
    ui.kv(
        metadata,
        UiText.choose("当前版本", "Edition"),
        BuildConfig.LOW_ANDROID
            ? UiText.choose("兼容版 · Android 6+", "Compatibility · Android 6+")
            : UiText.choose("标准版 · Android 11+", "Standard · Android 11+"));
    ui.kv(metadata, "DSH", com.deepseekharness.app.util.Constants.DSH_VERSION);
    ui.kv(metadata, UiText.choose("架构", "Architecture"), "arm64-v8a");
    ui.kv(metadata, UiText.choose("开源许可", "License"), "MIT");
    ui.label(UiText.choose("了解更多", "Learn more"));
    LinearLayout links = ui.card();
    ui.entry(
        links,
        UiText.choose("项目仓库", "Repository"),
        android.net.Uri.parse(AboutDialog.GITHUB_URL).getHost()
            + android.net.Uri.parse(AboutDialog.GITHUB_URL).getPath(),
        R.drawable.ic_ui_link,
        () -> AboutDialog.openBrowser(requireContext(), AboutDialog.GITHUB_URL));
    ui.entry(
        links,
        UiText.choose("交流与反馈", "Community and feedback"),
        UiText.format("QQ群 %s · 问题反馈", AboutDialog.QQ_GROUP),
        R.drawable.ic_ui_chat,
        () ->
            new DshaDialogBuilder(requireContext())
                .setTitle(UiText.choose("交流与反馈", "Community and feedback"))
                .setItems(
                    new String[] {
                      UiText.choose("打开 QQ 群", "Open QQ group"),
                      UiText.choose("打开问题反馈", "Open issue tracker")
                    },
                    (d, i) -> {
                      if (i == 0) AboutDialog.openQQGroup(requireContext());
                      else
                        AboutDialog.openBrowser(
                            requireContext(), AboutDialog.GITHUB_URL + "/issues");
                    })
                .show());
    ui.entry(
        links,
        UiText.choose("开源许可", "Open-source licenses"),
        UiText.choose("DSHA 与随包第三方组件", "DSHA and bundled components"),
        R.drawable.ic_recovery_document,
        this::showLicenses);
    ui.footer.setVisibility(View.GONE);
    return ui.root;
  }

  private void showLicenses() {
    try {
      String[] listed = requireContext().getAssets().list("licenses");
      if (listed == null || listed.length > 128) throw new java.io.IOException("LICENSE_INDEX");
      String[] names =
          java.util.Arrays.stream(listed)
              .filter(name -> name.matches("[A-Za-z0-9_.-]+\\.txt"))
              .sorted()
              .toArray(String[]::new);
      new DshaDialogBuilder(requireContext())
          .setTitle(UiText.choose("随包许可全文", "Bundled license texts"))
          .setItems(names, (dialog, index) -> showLicense(names[index]))
          .setNegativeButton(android.R.string.cancel, null)
          .show();
    } catch (java.io.IOException failure) {
      Toast.makeText(
              requireContext(),
              UiText.choose("无法读取随包许可清单。", "Unable to read the bundled license list."),
              Toast.LENGTH_LONG)
          .show();
    }
  }

  private void showLicense(String name) {
    final View owner = getView();
    final android.content.res.AssetManager assets = requireContext().getAssets();
    new Thread(
            () -> {
              String content;
              try (var in = assets.open("licenses/" + name);
                  var out = new java.io.ByteArrayOutputStream()) {
                byte[] bytes = new byte[8192];
                int count;
                while ((count = in.read(bytes)) != -1) {
                  if (out.size() + count > 1024 * 1024)
                    throw new java.io.IOException("LICENSE_SIZE");
                  out.write(bytes, 0, count);
                }
                content = out.toString("UTF-8");
              } catch (java.io.IOException failure) {
                content = UiText.choose("无法读取此许可全文。", "Unable to read this license text.");
              }
              final String text = content;
              new android.os.Handler(android.os.Looper.getMainLooper())
                  .post(
                      () -> {
                        if (!isAdded() || getView() != owner) return;
                        CardSheet.show(requireContext(), name, text);
                      });
            },
            "license-read")
        .start();
  }
}
