import com.deepseekharness.app.ui.WebBrowserFixture;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.Socket;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;

/** Exercises the shared server's real TCP responses; no Android browser or activity is involved. */
public final class WebBrowserFixtureProbe {
  private record Response(String status, Map<String, String> headers, byte[] body) {}
  private static int checks;
  private static void check(boolean ok, String reason) {
    if (!ok) throw new AssertionError(reason);
    checks++;
  }
  private static String line(InputStream input) throws Exception {
    ByteArrayOutputStream output = new ByteArrayOutputStream();
    for (int value; (value = input.read()) != -1;) {
      if (value == '\n') return output.toString(StandardCharsets.US_ASCII).replace("\r", "");
      output.write(value);
    }
    throw new AssertionError("incomplete fixture headers");
  }
  private static Response get(WebBrowserFixture fixture, String path) throws Exception {
    URI origin = URI.create(fixture.base);
    try (Socket socket = new Socket(origin.getHost(), origin.getPort())) {
      socket.setSoTimeout(3000);
      socket.getOutputStream().write(("GET " + path + " HTTP/1.1\r\nHost: 127.0.0.1\r\n\r\n")
          .getBytes(StandardCharsets.US_ASCII));
      InputStream input = socket.getInputStream();
      String status = line(input);
      Map<String, String> headers = new HashMap<>();
      for (String header; !(header = line(input)).isEmpty();) {
        int colon = header.indexOf(':');
        headers.put(header.substring(0, colon).toLowerCase(java.util.Locale.ROOT), header.substring(colon + 1).trim());
      }
      byte[] body = path.equals("/slow") ? input.readNBytes(4096) : input.readAllBytes();
      return new Response(status, headers, body);
    }
  }
  public static void main(String[] args) throws Exception {
    try (WebBrowserFixture fixture = new WebBrowserFixture(resource ->
        (resource.equals("client") ? "client-source" : "shared-page").getBytes(StandardCharsets.UTF_8))) {
      check(new String(get(fixture, "/").body(), StandardCharsets.UTF_8).equals("shared-page"), "shared page loader");
      check(new String(get(fixture, "/client.js").body(), StandardCharsets.UTF_8).equals("client-source"), "target client loader");
      get(fixture, "/report?key=loaded&value=two%20words");
      check("two words".equals(fixture.reports.get("loaded")), "query decoding");
      fixture.command = "seed";
      check(new String(get(fixture, "/command").body(), StandardCharsets.UTF_8).equals("seed"), "command delivery");
      Response file = get(fixture, "/file.zip");
      check(Arrays.equals(WebBrowserFixture.PAYLOAD, file.body()), "complete file bytes");
      check(file.headers().get("content-disposition").contains("attachment; filename=\"DSHA-web-check-"), "download file name");
      Response redirect = get(fixture, "/redirect");
      check(redirect.status().contains("302") && redirect.headers().get("location").equals("http://127.0.0.1:1/rejected"), "redirect boundary");
      Response truncated = get(fixture, "/truncated");
      check(Long.parseLong(truncated.headers().get("content-length")) == truncated.body().length + 100, "intentional truncation remains a real response");
      Response slow = get(fixture, "/slow");
      check(slow.body().length == 4096 && Long.parseLong(slow.headers().get("content-length")) == 1024 * 1024, "cancellable streaming response");
    }
    System.out.println("PASS shared browser HTTP fixture: " + checks + " actual TCP checks; no Android browser evidence");
  }
}
