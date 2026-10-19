package com.deepseekharness.app.ui;

import android.os.Bundle;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;
import androidx.appcompat.app.AppCompatActivity;
import com.deepseekharness.app.core.ConfigStore;
import com.deepseekharness.app.core.HarnessController;
import com.deepseekharness.app.util.UiText;
import com.deepseekharness.app.util.WebPreviewPolicy;
import org.json.JSONObject;

/** A dedicated signed migration document. No production page bridge is attached. */
public final class BrowserOriginRestoreActivity extends AppCompatActivity {
  public interface Surface extends AutoCloseable {
    View view();

    void load(String url, String configuration);

    void close();
  }

  private volatile BrowserOriginRestoreServer server;
  private Surface surface;
  private TextView status;
  private String authUrl;
  private String ticket;
  private LinearLayout container;

  @Override
  public void onCreate(Bundle state) {
    super.onCreate(state);
    container = new LinearLayout(this);
    container.setOrientation(LinearLayout.VERTICAL);
    container.setBackgroundColor(getColor(com.deepseekharness.app.R.color.surface));
    UiNavigation.addHeader(
        this, container, UiText.choose("恢复旧端口草稿", "Restore previous-port drafts"), this::finish);
    status = new TextView(this);
    status.setTextSize(14);
    status.setTextColor(getColor(com.deepseekharness.app.R.color.text));
    status.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
    status.setTextIsSelectable(true);
    status.setPadding(20, 20, 20, 20);
    container.addView(status);
    setContentView(container);
    authUrl = getIntent().getStringExtra("current-auth-url");
    ticket = getIntent().getStringExtra("restore-ticket");
    int oldPort = getIntent().getIntExtra("old-port", -1);
    var identity = HarnessController.get(this).getReadyWebPageIdentity();
    if (identity == null
        || !identity.authUrl().equals(authUrl)
        || !new ConfigStore(this).webOriginPorts().contains(oldPort)) {
      status.setText(
          UiText.choose("运行实例已变化，请返回后重试。", "The running instance changed. Return and retry."));
      return;
    }
    String base = WebPreviewPolicy.loopbackBaseUrl(authUrl);
    if (base == null || android.net.Uri.parse(base).getPort() == oldPort) {
      finish();
      return;
    }
    status.setText(
        UiText.choose("正在核对旧端口与保留候选…", "Checking the previous port and retained candidates…"));
    new Thread(
            () -> {
              try {
                BrowserOriginRestore.awaitClosed(
                    ticket,
                    () ->
                        !isFinishing()
                            && !isDestroyed()
                            && identity.equals(
                                HarnessController.get(this).getReadyWebPageIdentity()));
                server =
                    new BrowserOriginRestoreServer(
                        this,
                        oldPort,
                        base,
                        () -> {
                          var current = HarnessController.get(this).getReadyWebPageIdentity();
                          return !isFinishing() && !isDestroyed() && identity.equals(current);
                        },
                        () -> runOnUiThread(this::restoreTarget),
                        result ->
                            runOnUiThread(
                                () -> {
                                  if (isFinishing()
                                      || isDestroyed()
                                      || !identity.equals(
                                          HarnessController.get(this).getReadyWebPageIdentity()))
                                    return;
                                  int written = result.optInt("applied"),
                                      conflicts = result.optInt("conflicts"),
                                      unknown = result.optInt("unknown");
                                  status.setText(
                                      UiText.format(
                                          "恢复结果：写入 %d，冲突 %d，未知 %d。候选与原数据已保留，请重新进入对话页读取。",
                                          written, conflicts, unknown));
                                }));
                runOnUiThread(
                    () -> {
                      if (isFinishing() || isDestroyed()) {
                        server.close();
                        return;
                      }
                      try {
                        surface =
                            BrowserOriginRestoreFactory.create(
                                this, getIntent().getBooleanExtra("gecko", false));
                        container.addView(surface.view(), new LinearLayout.LayoutParams(-1, 0, 1));
                        status.setText(
                            UiText.choose(
                                "正在读取旧端口的数据候选；原数据不会删除。",
                                "Reading previous-port candidates; source data will be retained."));
                        surface.load(
                            server.origin() + "/__dsha/browser-origin-restore",
                            configuration("capture"));
                      } catch (Exception failure) {
                        if (server != null) server.close();
                        status.setText(
                            UiText.choose(
                                "恢复页面无法打开，候选已保留。",
                                "The recovery page could not open. Candidates were retained."));
                      }
                    });
              } catch (java.net.BindException occupied) {
                runOnUiThread(
                    () ->
                        status.setText(
                            UiText.choose(
                                "旧端口正被占用，草稿仍保留。请待端口释放后从“恢复旧端口草稿”重试。",
                                "The previous port is occupied. Drafts were retained. Retry after the port is released.")));
              } catch (Exception failure) {
                runOnUiThread(
                    () ->
                        status.setText(
                            "RESTORE_PREVIEW_CLOSE_UNCONFIRMED".equals(failure.getMessage())
                                ? UiText.choose(
                                    "未确认旧网页已关闭，恢复尚未开始。请关闭其它对话页后重试。",
                                    "Previous pages have not confirmed closure. Restore has not started. Close other conversation pages and retry.")
                                : failure.getMessage() != null
                                        && failure.getMessage().startsWith("RESTORE_BUDGET")
                                    ? UiText.choose(
                                        "恢复候选达到保留上限，旧草稿仍保留。请保留原图并联系维护者检查候选。",
                                        "Retained recovery candidates reached their limit. Old drafts remain. Keep original images and contact the maintainer to inspect candidates.")
                                    : UiText.choose(
                                        "恢复未开始，旧数据仍保留。请返回后重试。",
                                        "Restore did not start. Source data was retained. Return and retry.")));
              }
            },
            "dsha-origin-restore-prepare")
        .start();
  }

  private String configuration(String mode) throws org.json.JSONException {
    return new JSONObject()
        .put("mode", mode)
        .put("language", UiText.language())
        .put("transport", server.origin())
        .put("secret", server.secret())
        .toString();
  }

  private void restoreTarget() {
    if (isFinishing() || isDestroyed() || server == null || surface == null) return;
    var identity = HarnessController.get(this).getReadyWebPageIdentity();
    if (identity == null || !identity.authUrl().equals(authUrl)) {
      status.setText(
          UiText.choose(
              "运行实例已变化；旧数据与候选已保留，请返回后重试。",
              "The running instance changed. Source and candidates were retained. Return and retry."));
      return;
    }
    try {
      // Official rc2 exchanges launch tokens only on '/'. The dedicated document receives no token.
      surface.load(authUrl, configuration("restore"));
    } catch (Exception failure) {
      status.setText(
          UiText.choose("候选已保存，恢复尚未完成。", "Candidates were saved; restore is incomplete."));
    }
  }

  @Override
  protected void onDestroy() {
    if (surface != null) surface.close();
    if (server != null) server.close();
    BrowserOriginRestore.end(ticket);
    super.onDestroy();
  }
}
