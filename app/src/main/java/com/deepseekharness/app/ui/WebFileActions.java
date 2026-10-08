package com.deepseekharness.app.ui;

import android.webkit.WebView;
import androidx.webkit.WebViewCompat;
import androidx.webkit.WebViewFeature;
import com.deepseekharness.app.util.WebPreviewPolicy;
import com.deepseekharness.app.util.WebTransferPolicy;
import java.util.Collections;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;
import org.json.JSONObject;

/** 仅当前正式顶层页面可提交 Blob；不接收本机路径或向 iframe 暴露 Java 对象。 */
final class WebFileActions {
  private WebFileActions() {}

  static void attach(
      WebView view,
      String base,
      BooleanSupplier current,
      Supplier<WebBlobDownload> transfer,
      java.util.function.LongSupplier navigation) {
    if (!WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER)) return;
    WebViewCompat.removeWebMessageListener(view, "DshaFiles");
    WebViewCompat.addWebMessageListener(
        view,
        "DshaFiles",
        Collections.singleton(base.substring(0, base.length() - 1)),
        (source, message, origin, mainFrame, reply) -> {
          if (!mainFrame
              || !current.getAsBoolean()
              || !WebPreviewPolicy.sameService(base, origin.toString())
              || !WebPreviewPolicy.sameService(base, source.getUrl())) return;
          String id = "";
          try {
            String text = message.getData();
            if (text == null || text.length() > 8192)
              throw new IllegalArgumentException("FILE_REQUEST");
            JSONObject request = new JSONObject(text);
            id = request.getString("id");
            if (!id.matches("[a-f0-9]{32}")) throw new IllegalArgumentException("FILE_REQUEST_ID");
            String action = request.getString("action"), url = request.getString("url");
            if (!(action.equals("share") || action.equals("save"))
                || !url.startsWith("blob:")
                || !WebPreviewPolicy.pageDownload(base, url))
              throw new IllegalArgumentException("FILE_REQUEST_ORIGIN");
            long bytes = request.getLong("bytes");
            WebTransferPolicy.checkSize(
                bytes,
                bytes,
                action.equals("share") ? 32L * 1024 * 1024 : WebTransferPolicy.DOWNLOAD_LIMIT,
                true);
            final String requestId = id;
            final long document = navigation.getAsLong();
            transfer
                .get()
                .start(
                    base,
                    url,
                    request.getString("name"),
                    action.equals("share"),
                    request.optString("mime"),
                    bytes,
                    (ok, detail) -> {
                      if (!current.getAsBoolean() || navigation.getAsLong() != document) return;
                      try {
                        reply.postMessage(
                            new JSONObject()
                                .put("id", requestId)
                                .put("ok", ok)
                                .put("detail", detail)
                                .toString());
                      } catch (Exception ignored) {
                      }
                    });
          } catch (Exception failure) {
            try {
              reply.postMessage(
                  new JSONObject()
                      .put("id", id)
                      .put("ok", false)
                      .put("detail", "FILE_REQUEST_REJECTED")
                      .toString());
            } catch (Exception ignored) {
            }
          }
        });
  }
}
