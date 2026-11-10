package com.deepseekharness.app.assistant;

import android.content.Context;
import com.deepseekharness.app.core.HarnessController;
import com.deepseekharness.app.ui.BrowserConversationRoute;
import com.deepseekharness.app.util.PreviewPageSession;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import org.json.JSONObject;

/** Calls only the already-running, generation-bound local Host; never starts Ubuntu or Web. */
public final class LocalAssistantBackend implements AssistantBackend {
  private final Context context;
  private final HarnessController harness;
  public final long generation;
  private final String authUrl;
  private final PreviewPageSession.Identity page;
  private String cookie;
  private final String controlConnection;

  public LocalAssistantBackend(Context context) throws IOException {
    this.context = context.getApplicationContext();
    harness = HarnessController.get(context);
    page = harness.getReadyWebPageIdentity();
    if (page == null) {
      com.deepseekharness.app.core.DiagnosticLog.record(
          context,
          "ASSISTANT_CONNECT",
          "LOCAL_NOT_READY generation="
              + harness.getWebGeneration()
              + " starting="
              + harness.isStarting()
              + " restoring="
              + harness.isRestoringWebSession()
              + " unresolved="
              + harness.hasUnresolvedWebSession());
      throw new IOException("LOCAL_DSH_NOT_READY");
    }
    generation = page.generation();
    authUrl = page.authUrl();
    String connection = com.deepseekharness.app.HttpShellService.currentScreenConnection(context);
    controlConnection = connection == null ? "" : connection;
  }

  @Override
  public boolean current() {
    return page.equals(harness.getReadyWebPageIdentity());
  }

  @Override
  public boolean ownsControlBinding(
      com.deepseekharness.app.control.ControlTaskAuthority.Binding value) {
    return value != null
        && value.owner() == com.deepseekharness.app.control.ControlTaskAuthority.Owner.LOCAL_DSH
        && !controlConnection.isEmpty()
        && controlConnection.equals(value.connection())
        && current();
  }

  @Override
  public JSONObject call(String method, JSONObject args) throws IOException {
    if (!current()) throw new IOException("LOCAL_DSH_GENERATION_CHANGED");
    HttpURLConnection connection = null;
    try {
      URI ready = URI.create(authUrl);
      if (!"http".equals(ready.getScheme()) || !"127.0.0.1".equals(ready.getHost()))
        throw new IOException("LOCAL_DSH_ORIGIN_INVALID");
      if (cookie == null) cookie = harness.exchangeDshAuthCookie();
      if (cookie == null || cookie.isBlank() || !current())
        throw new IOException("LOCAL_DSH_AUTH_UNAVAILABLE");
      connection =
          (HttpURLConnection)
              new URI("http", null, "127.0.0.1", ready.getPort(), "/api/dsha-assistant", null, null)
                  .toURL()
                  .openConnection();
      connection.setInstanceFollowRedirects(false);
      connection.setConnectTimeout(4000);
      connection.setReadTimeout(12000);
      connection.setRequestMethod("POST");
      connection.setDoOutput(true);
      connection.setRequestProperty("Content-Type", "application/json");
      connection.setRequestProperty("Cookie", cookie);
      byte[] request =
          new JSONObject()
              .put("method", method)
              .put("args", args)
              .toString()
              .getBytes(StandardCharsets.UTF_8);
      connection.setFixedLengthStreamingMode(request.length);
      try (var output = connection.getOutputStream()) {
        output.write(request);
      }
      int status = connection.getResponseCode();
      if (status != 200)
        com.deepseekharness.app.core.DiagnosticLog.record(
            context,
            "ASSISTANT_CONNECT",
            "LOCAL_HTTP status=" + status + " generation=" + generation);
      if (status == 404) throw new IOException("LOCAL_ASSISTANT_API_UNAVAILABLE");
      if (status == 401 || status == 403) {
        cookie = null;
        throw new IOException("LOCAL_DSH_AUTH_UNAVAILABLE");
      }
      try (var input = status == 200 ? connection.getInputStream() : connection.getErrorStream()) {
        if (input == null) throw new IOException("LOCAL_DSH_EMPTY_RESPONSE");
        var bytes = new ByteArrayOutputStream();
        byte[] block = new byte[8192];
        int count;
        while ((count = input.read(block)) != -1) {
          if (bytes.size() + count > 524288) throw new IOException("LOCAL_DSH_RESPONSE_TOO_LARGE");
          bytes.write(block, 0, count);
        }
        if (!current()) throw new IOException("LOCAL_DSH_GENERATION_CHANGED");
        JSONObject result = new JSONObject(bytes.toString(StandardCharsets.UTF_8.name()));
        if (result.optBoolean("rejected"))
          throw new AssistantBackend.Rejected(result.optString("code"));
        if (status != 200 || !result.optBoolean("ok"))
          throw new IOException(result.optString("code", "LOCAL_DSH_REQUEST_FAILED"));
        return result.getJSONObject("value");
      }
    } catch (IOException failure) {
      throw failure;
    } catch (Exception failure) {
      throw new IOException("LOCAL_DSH_PROTOCOL_ERROR", failure);
    } finally {
      if (connection != null) connection.disconnect();
    }
  }

  @Override
  public void returnConversation(AssistantBinding binding) throws IOException {
    if (!current() || binding.generation() != generation)
      throw new IOException("LOCAL_DSH_GENERATION_CHANGED");
    context.startActivity(
        BrowserConversationRoute.conversation(
            context, generation, page.instanceId(), binding.sessionId()));
  }
}
