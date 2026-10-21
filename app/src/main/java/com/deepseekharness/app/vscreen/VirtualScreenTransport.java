package com.deepseekharness.app.vscreen;

import com.deepseekharness.app.util.VirtualScreenRoutes;
import com.deepseekharness.app.util.VirtualScreenHandshake;
import com.deepseekharness.app.util.HttpProtocol;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import org.json.JSONObject;

/** 唯一 core HTTP 传输；错误不重试，不将写入后的断连伪装成未执行。 */
final class VirtualScreenTransport {
  private static final int MAX_RESPONSE = 4 * 1024 * 1024;
  private static final int RESPONSE_DEADLINE_MS = 12_000;

  JSONObject request(int port, String token, String route, String query) {
    return failure("VSCREEN_CORE_IDENTITY_REQUIRED", VirtualScreenRoutes.mutatesCore(route));
  }

  JSONObject request(VirtualScreenHandshake endpoint, String route, String query) {
    int port = endpoint.port;
    String token = endpoint.token;
    if (token == null || token.isEmpty() || port < 1 || port > 65535)
      return failure("VSCREEN_NOT_RUNNING", false);
    // HttpURLConnection may transparently retry a GET after EOF, even after the peer committed it.
    // One socket, one write, no pool/redirect/reconnect: a lost action reply remains UNKNOWN.
    try (Socket socket = new Socket()) {
      String target = route + (query.isEmpty() ? "" : "?" + query);
      boolean observationRoute =
          route.equals("/vscreen/observation-region")
              || route.equals("/vscreen/observation-release");
      if ((!route.matches("/vscreen/[a-z]+") && !observationRoute) || target.length() > 24_000)
        throw new IOException("VSCREEN_REQUEST_TARGET_INVALID");
      for (int i = 0; i < target.length(); i++)
        if (target.charAt(i) <= 32 || target.charAt(i) >= 127 || target.charAt(i) == '#')
          throw new IOException("VSCREEN_REQUEST_TARGET_INVALID");
      if (!token.matches("[!-~]{1,256}")) throw new IOException("VSCREEN_REQUEST_TOKEN_INVALID");
      socket.connect(new InetSocketAddress(InetAddress.getByName("127.0.0.1"), port), 2000);
      long deadline = HttpProtocol.deadline(RESPONSE_DEADLINE_MS);
      VirtualScreenPeerProof.Session peer = VirtualScreenPeerProof.client(socket, endpoint);
      OutputStream request = socket.getOutputStream();
      request.write(
          ("GET "
                  + target
                  + " HTTP/1.1\r\nHost: 127.0.0.1:"
                  + port
                  + "\r\n"
                  + VirtualScreenPeerProof.HEADER
                  + ": "
                  + peer.clientProof("GET", target)
                  + "\r\nConnection: close\r\n\r\n")
              .getBytes(StandardCharsets.US_ASCII));
      request.flush();
      InputStream input = socket.getInputStream();
      HttpProtocol.Head head =
          HttpProtocol.readHead(
              input,
              socket,
              new HttpProtocol.Limits(1024, 4096, 16384, 32, RESPONSE_DEADLINE_MS),
              deadline,
              false);
      if (head == null
          || head.status < 200
          || head.status >= 300 && head.status != 401
          || head.values("Content-Length").size() != 1
          || !head.values("Transfer-Encoding").isEmpty()
          || !head.values("Content-Encoding").isEmpty())
        throw new IOException("VSCREEN_RESPONSE_BOUNDARY_INVALID");
      long length = Long.parseLong(head.value("Content-Length"));
      if (length > MAX_RESPONSE)
        return failure("VSCREEN_RESPONSE_LIMIT", VirtualScreenRoutes.mutatesCore(route));
      ByteArrayOutputStream output = new ByteArrayOutputStream((int) length);
      byte[] buffer = new byte[8192];
      while (length > 0) {
        long remaining = deadline - System.nanoTime();
        if (remaining <= 0) throw new SocketTimeoutException("VSCREEN_RESPONSE_DEADLINE");
        socket.setSoTimeout((int) Math.max(1, (remaining + 999999L) / 1_000_000L));
        int count = input.read(buffer, 0, (int) Math.min(buffer.length, length));
        if (count < 0) throw new EOFException("VSCREEN_RESPONSE_INCOMPLETE");
        output.write(buffer, 0, count);
        length -= count;
      }
      return new JSONObject(output.toString(StandardCharsets.UTF_8.name()));
    } catch (Exception unavailable) {
      return failure(
          VirtualScreenRoutes.mutatesCore(route)
              ? "INPUT_RESULT_UNKNOWN"
              : "VSCREEN_TRANSPORT_UNAVAILABLE",
          VirtualScreenRoutes.mutatesCore(route));
    }
  }

  static JSONObject failure(String code, boolean unknown) {
    JSONObject value = new JSONObject();
    try {
      value.put("ok", false).put("error", code);
      if (unknown) value.put("executionUnknown", true);
    } catch (Exception ignored) {
    }
    return value;
  }
}
