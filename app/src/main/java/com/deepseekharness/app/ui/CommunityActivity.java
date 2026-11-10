package com.deepseekharness.app.ui;

import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.webkit.WebResourceRequest;
import android.webkit.RenderProcessGoneDetail;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;
import androidx.appcompat.app.AppCompatActivity;
import com.deepseekharness.app.R;
import com.deepseekharness.app.util.UiText;

/** 社区内容使用真实网站；独立 WebView 不注入本机桥、模型凭据或文件访问能力。 */
public final class CommunityActivity extends AppCompatActivity {
  private WebView browser;
  private TextView status;
  private boolean pageFailed;
  private LinearLayout root;

  @android.annotation.SuppressLint("SetJavaScriptEnabled")
  @Override
  protected void onCreate(Bundle saved) {
    super.onCreate(saved);
    root = new LinearLayout(this);
    root.setOrientation(LinearLayout.VERTICAL);
    root.setBackgroundColor(getColor(R.color.surface));
    LinearLayout bar = new LinearLayout(this);
    bar.setGravity(android.view.Gravity.CENTER_VERTICAL);
    Button back = new Button(this);
    back.setText(UiText.choose("返回", "Back"));
    back.setBackgroundResource(R.drawable.bg_action_plain);
    back.setTextColor(getColor(R.color.primary));
    bar.addView(back);
    TextView title = new TextView(this);
    title.setText(UiText.choose("社区插件生态", "Community plugins"));
    title.setTextSize(19);
    title.setTextColor(getColor(R.color.text));
    bar.addView(title, new LinearLayout.LayoutParams(0, -2, 1));
    Button reload = new Button(this);
    reload.setText(UiText.choose("重试", "Retry"));
    reload.setBackgroundResource(R.drawable.bg_action_plain);
    reload.setTextColor(getColor(R.color.primary));
    bar.addView(reload);
    root.addView(bar);
    status = new TextView(this);
    status.setPadding(16, 8, 16, 8);
    status.setTextColor(getColor(R.color.text_muted));
    root.addView(status);
    createBrowser();
    setContentView(root);
    back.setOnClickListener(v -> leave());
    reload.setOnClickListener(
        v -> {
          if (browser == null) createBrowser();
          browser.loadUrl(com.deepseekharness.app.util.Constants.SITE_URL);
        });
    getOnBackPressedDispatcher()
        .addCallback(
            this,
            new androidx.activity.OnBackPressedCallback(true) {
              public void handleOnBackPressed() {
                leave();
              }
            });
    if (saved == null || browser.restoreState(saved) == null)
      browser.loadUrl(com.deepseekharness.app.util.Constants.SITE_URL);
  }

  @android.annotation.SuppressLint("SetJavaScriptEnabled")
  private void createBrowser() {
    browser = new WebView(this);
    browser.setBackgroundColor(getColor(R.color.surface));
    browser.getSettings().setJavaScriptEnabled(true);
    browser.getSettings().setDomStorageEnabled(true);
    browser.getSettings().setAllowFileAccess(false);
    browser.getSettings().setAllowContentAccess(false);
    browser.getSettings().setMixedContentMode(android.webkit.WebSettings.MIXED_CONTENT_NEVER_ALLOW);
    browser.setWebViewClient(
        new WebViewClient() {
          @Override
          public boolean onRenderProcessGone(WebView view, RenderProcessGoneDetail detail) {
            if (view != browser) return true;
            browser = null;
            pageFailed = true;
            root.removeView(view);
            view.destroy();
            status.setVisibility(android.view.View.VISIBLE);
            status.setText(UiText.text("网页渲染进程已退出"));
            return true;
          }

          @Override
          public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
            return request.isForMainFrame() && navigate(request.getUrl());
          }

          @Override
          public boolean shouldOverrideUrlLoading(WebView view, String url) {
            return navigate(Uri.parse(url));
          }

          @Override
          public void onPageStarted(WebView view, String url, android.graphics.Bitmap icon) {
            if (view != browser) return;
            pageFailed = false;
            status.setVisibility(android.view.View.VISIBLE);
            status.setText(UiText.choose("正在连接社区…", "Connecting to the community…"));
          }

          @Override
          public void onPageFinished(WebView view, String url) {
            if (view == browser && !pageFailed) status.setVisibility(android.view.View.GONE);
          }

          @Override
          public void onReceivedError(
              WebView view, WebResourceRequest request, android.webkit.WebResourceError error) {
            if (view == browser && request.isForMainFrame()) {
              pageFailed = true;
              status.setVisibility(android.view.View.VISIBLE);
              status.setText(
                  UiText.choose(
                      "社区暂时无法连接，请检查网络后重试。", "Unable to connect. Check your network and retry."));
            }
          }
        });
    root.addView(browser, new LinearLayout.LayoutParams(-1, 0, 1));
  }

  private boolean navigate(Uri uri) {
    String scheme = uri.getScheme(), host = uri.getHost();
    if ("https".equals(scheme) && ("dsha.cc".equals(host) || "www.dsha.cc".equals(host)))
      return false;
    try {
      if ("dsha".equals(scheme)) {
        startActivity(new Intent(Intent.ACTION_VIEW, uri).setPackage(getPackageName()));
        return true;
      }
      if ("https".equals(scheme) || "http".equals(scheme))
        startActivity(new Intent(Intent.ACTION_VIEW, uri));
    } catch (RuntimeException error) {
      status.setVisibility(android.view.View.VISIBLE);
      status.setText(UiText.choose("无法打开此链接。", "Unable to open this link."));
    }
    return true;
  }

  private void leave() {
    if (browser != null && browser.canGoBack()) browser.goBack();
    else finish();
  }

  @Override
  protected void onSaveInstanceState(Bundle out) {
    if (browser != null) browser.saveState(out);
    super.onSaveInstanceState(out);
  }

  @Override
  protected void onDestroy() {
    if (browser != null) {
      WebView previous = browser;
      browser = null;
      root.removeView(previous);
      previous.stopLoading();
      previous.destroy();
    }
    super.onDestroy();
  }
}
