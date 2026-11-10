package com.deepseekharness.app.ui;

import android.webkit.WebView;
import androidx.webkit.WebViewCompat;
import androidx.webkit.WebViewFeature;
import com.deepseekharness.app.transfer.DownloadEngine;
import com.deepseekharness.app.transfer.TransferArtifact;
import com.deepseekharness.app.util.BrowserThemeGate;
import com.deepseekharness.app.util.WebPreviewPolicy;
import com.deepseekharness.app.util.WebTransferPolicy;
import java.util.Collections;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;
import org.json.JSONObject;

/** 当前正式顶层页面的文件桥；持久任务不归页面所有，Blob 仍须先完整暂存。 */
final class WebFileActions {
  private WebFileActions() {}

  private static final java.util.Map<String, BooleanSupplier> destinations =
      new java.util.HashMap<>();

  static boolean destinationCurrent(String token) {
    BooleanSupplier owner = destinations.get(token);
    try {
      return owner != null && owner.getAsBoolean();
    } catch (RuntimeException retired) {
      return false;
    }
  }

  static void retireDestination(String token) {
    destinations.remove(token);
  }

  static String destinationToken(BooleanSupplier current) {
    if (destinations.size() >= 16) throw new IllegalStateException("FILE_DESTINATION_BUSY");
    String token = java.util.UUID.randomUUID().toString();
    destinations.put(token, current);
    return token;
  }

  static void attach(
      WebView view,
      String base,
      BooleanSupplier current,
      Supplier<WebBlobDownload> transfer,
      java.util.function.LongSupplier navigation,
      Supplier<BrowserThemeGate> binding) {
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
            BrowserThemeGate page = binding.get();
            JSONObject identity = request.getJSONObject("binding");
            if (!matchesBinding(
                page,
                source,
                identity.getString("nonce"),
                identity.getLong("generation"),
                identity.getString("instance")))
              throw new IllegalArgumentException("FILE_REQUEST_DOCUMENT");
            final String requestId = id;
            final long document = navigation.getAsLong();
            String action = request.getString("action");
            if (action.equals("artifact")) {
              // Only the opaque manifest crosses this bridge. The engine resolves
              // current service identity and credentials itself, never from JS.
              TransferArtifact artifact =
                  TransferArtifact.fromJson(request.getJSONObject("artifact"));
              String destination = request.getString("destination");
              if (destination.equals("share") && artifact.bytes > 32L * 1024 * 1024)
                throw new IllegalArgumentException("FILE_SHARE_LIMIT_32MIB");
              if ((destination.equals("share") || destination.equals("saveas"))
                  && !source.hasWindowFocus())
                throw new IllegalStateException("FILE_DESTINATION_NOT_FOREGROUND");
              if ((destination.equals("share") || destination.equals("saveas"))
                  && destinations.size() >= 16)
                throw new IllegalStateException("FILE_DESTINATION_BUSY");
              String taskId =
                  DownloadEngine.get(source.getContext()).enqueue(artifact, destination);
              if (destination.equals("share") || destination.equals("saveas")) {
                String token =
                    destinationToken(
                        () ->
                            current.getAsBoolean()
                                && navigation.getAsLong() == document
                                && binding.get() == page);
                try {
                  source
                      .getContext()
                      .startActivity(
                          FileTransfersActivity.destinationIntent(
                              source.getContext(), taskId, destination, token));
                } catch (RuntimeException unavailable) {
                  retireDestination(token);
                  throw new IllegalStateException("FILE_DESTINATION_UNAVAILABLE");
                }
              }
              reply.postMessage(
                  new JSONObject()
                      .put("id", id)
                      .put("ok", true)
                      .put("result", new JSONObject().put("status", "queued").put("taskId", taskId))
                      .toString());
              return;
            }
            if (action.equals("open-downloads")) {
              android.content.Context context = source.getContext();
              context.startActivity(FileTransfersActivity.intent(context));
              reply.postMessage(
                  new JSONObject()
                      .put("id", id)
                      .put("ok", true)
                      .put("result", new JSONObject().put("status", "opened"))
                      .toString());
              return;
            }
            if (action.equals("downloads-status")) {
              String taskId = request.optString("taskId");
              if (!taskId.isEmpty() && !taskId.matches("[A-Za-z0-9_-]{1,128}"))
                throw new IllegalArgumentException("FILE_TASK_ID");
              JSONObject snapshot = DownloadEngine.get(source.getContext()).snapshot(taskId);
              org.json.JSONArray tasks = snapshot.optJSONArray("tasks");
              if (tasks == null) tasks = new org.json.JSONArray().put(snapshot);
              org.json.JSONArray publicTasks = new org.json.JSONArray();
              for (int index = 0; index < tasks.length(); index++)
                publicTasks.put(publicStatus(tasks.getJSONObject(index)));
              reply.postMessage(
                  new JSONObject()
                      .put("id", id)
                      .put("ok", true)
                      .put("result", new JSONObject().put("tasks", publicTasks))
                      .toString());
              return;
            }
            String url = request.getString("url");
            if (!(action.equals("share") || action.equals("save") || action.equals("gallery"))
                || !url.startsWith("blob:")
                || !WebPreviewPolicy.pageDownload(base, url))
              throw new IllegalArgumentException("FILE_REQUEST_ORIGIN");
            if (action.equals("gallery") && !request.getString("mime").startsWith("image/"))
              throw new IllegalArgumentException("FILE_IMAGE_REQUIRED");
            long bytes = request.getLong("bytes");
            WebTransferPolicy.checkSize(
                bytes,
                bytes,
                action.equals("share") ? 32L * 1024 * 1024 : WebTransferPolicy.DOWNLOAD_LIMIT,
                true);
            transfer
                .get()
                .start(
                    base,
                    url,
                    request.getString("name"),
                    action,
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
                      .put("detail", rejection(failure))
                      .toString());
            } catch (Exception ignored) {
            }
          }
        });
  }

  private static String rejection(Exception failure) {
    String message = failure.getMessage();
    return message != null && message.matches("[A-Z][A-Z0-9_]{1,80}")
        ? message
        : "FILE_REQUEST_REJECTED";
  }

  static boolean matchesBinding(
      BrowserThemeGate page, Object source, String nonce, long generation, String instance) {
    return page != null
        && page.owns(source)
        && page.nonce().equals(nonce)
        && page.identity().generation() == generation
        && page.identity().instanceId().equals(instance);
  }

  /** Querying progress never exposes private stages, credential material or destination grants. */
  private static JSONObject publicStatus(JSONObject source) throws org.json.JSONException {
    JSONObject result = new JSONObject();
    for (String key :
        new String[] {
          "id",
          "destination",
          "status",
          "downloaded",
          "total",
          "speedBytesPerSecond",
          "displayName",
          "mime",
          "createdAt"
        }) {
      if (source.has(key)) result.put(key, source.get(key));
    }
    return result;
  }
}
