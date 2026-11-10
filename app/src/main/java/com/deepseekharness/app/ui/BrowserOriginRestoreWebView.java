package com.deepseekharness.app.ui;

import android.content.Context;
import android.net.Uri;
import android.webkit.*;
import android.view.View;
import com.deepseekharness.app.util.WebPreviewPolicy;
import org.json.JSONObject;

/** Only the signed dedicated document may receive the one-use transport nonce. */
final class BrowserOriginRestoreWebView implements BrowserOriginRestoreActivity.Surface {
  private WebView view;
  private String base, configuration;
  private boolean delivered, closed, authenticating;

  BrowserOriginRestoreWebView(Context context) {
    view = new WebView(context);
    view.getSettings().setJavaScriptEnabled(true);
    view.getSettings().setDomStorageEnabled(true);
    view.getSettings().setAllowFileAccess(false);
    view.getSettings().setAllowContentAccess(false);
    view.getSettings().setAllowFileAccessFromFileURLs(false);
    view.getSettings().setAllowUniversalAccessFromFileURLs(false);
    view.setWebViewClient(
        new WebViewClient() {
          @Override
          public boolean onRenderProcessGone(WebView source, RenderProcessGoneDetail detail) {
            if (source != view) return true;
            retire(false);
            if (context instanceof BrowserOriginRestoreActivity owner)
              owner.rendererGone(BrowserOriginRestoreWebView.this);
            return true;
          }

          @Override
          public boolean shouldOverrideUrlLoading(WebView source, String url) {
            return !allowed(url);
          }

          @Override
          public boolean shouldOverrideUrlLoading(WebView source, WebResourceRequest request) {
            return !allowed(request.getUrl().toString());
          }

          @Override
          public void onPageFinished(WebView source, String url) {
            if (!closed
                && authenticating
                && WebPreviewPolicy.sameService(base, source.getUrl())
                && "/".equals(Uri.parse(source.getUrl()).getPath())
                && Uri.parse(source.getUrl()).getEncodedQuery() == null) {
              authenticating = false;
              source.loadUrl(base + "__dsha/browser-origin-restore");
              return;
            }
            if (closed || delivered || !allowed(url) || !allowed(source.getUrl())) return;
            delivered = true;
            source.evaluateJavascript(
                "if(document.body?.dataset.dshaOriginRestore==='1')window.dispatchEvent(new CustomEvent('dsha-origin-restore-start',{detail:"
                    + JSONObject.quote(configuration)
                    + "}));",
                null);
          }
        });
  }

  private boolean allowed(String url) {
    return url != null
        && base != null
        && WebPreviewPolicy.sameService(base, url)
        && ("/__dsha/browser-origin-restore".equals(Uri.parse(url).getPath())
            || authenticating && "/".equals(Uri.parse(url).getPath()));
  }

  public View view() {
    return view;
  }

  public void load(String url, String configuration) {
    if (closed || view == null) throw new IllegalStateException("RESTORE_RENDERER_CLOSED");
    base = WebPreviewPolicy.loopbackBaseUrl(url);
    if (base == null) throw new IllegalArgumentException("RESTORE_DOCUMENT_ORIGIN");
    this.configuration = configuration;
    authenticating = "/".equals(Uri.parse(url).getPath());
    delivered = false;
    view.loadUrl(url);
  }

  public void close() {
    retire(true);
  }

  private void retire(boolean stopLoading) {
    if (closed) return;
    closed = true;
    WebView previous = view;
    view = null;
    configuration = null;
    if (previous == null) return;
    if (previous.getParent() instanceof android.view.ViewGroup parent) parent.removeView(previous);
    try {
      if (stopLoading) previous.stopLoading();
    } finally {
      previous.destroy();
    }
  }
}
