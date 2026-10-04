package com.deepseekharness.app.ui;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Shared test-only HTTP server; resource reads come from the invoking instrumentation. */
public final class WebBrowserFixture implements AutoCloseable {
  public interface Loader {
    byte[] read(String resource) throws IOException;
  }

  public static final byte[] PAYLOAD =
      "PK\u0003\u0004DSHA-independent-export-123456789".getBytes(StandardCharsets.UTF_8);
  private final ServerSocket socket;
  private final Loader loader;
  private final ExecutorService workers = Executors.newCachedThreadPool();
  private final String name = "DSHA-web-check-" + UUID.randomUUID() + ".zip";
  public final String base;
  public final Map<String, String> reports = new ConcurrentHashMap<>();
  public volatile String command = "";
  private volatile boolean closed;

  public WebBrowserFixture(Loader loader) throws IOException {
    this.loader = loader;
    socket = new ServerSocket(0, 16, InetAddress.getByName("127.0.0.1"));
    base = "http://127.0.0.1:" + socket.getLocalPort() + "/";
    workers.submit(
        () -> {
          while (!closed) {
            try {
              Socket accepted = socket.accept();
              workers.submit(() -> handle(accepted));
            } catch (IOException failure) {
              if (!closed) throw new IllegalStateException(failure);
            }
          }
        });
  }

  private static String parameter(String path, String key) throws IOException {
    int query = path.indexOf('?');
    if (query < 0) return null;
    for (String field : path.substring(query + 1).split("&")) {
      String[] pair = field.split("=", 2);
      if (URLDecoder.decode(pair[0], "UTF-8").equals(key))
        return pair.length == 1 ? "" : URLDecoder.decode(pair[1], "UTF-8");
    }
    return null;
  }

  private void handle(Socket client) {
    try (client) {
      client.setSoTimeout(5000);
      BufferedReader reader =
          new BufferedReader(
              new InputStreamReader(client.getInputStream(), StandardCharsets.US_ASCII));
      String first = reader.readLine();
      if (first == null) return;
      String path = first.split(" ")[1], line;
      while ((line = reader.readLine()) != null && !line.isEmpty()) {}
      String content = "text/plain; charset=utf-8", extra = "", status = "200 OK";
      byte[] bytes;
      if (path.startsWith("/report?")) {
        reports.put(parameter(path, "key"), parameter(path, "value"));
        bytes = "ok".getBytes(StandardCharsets.UTF_8);
      } else if (path.equals("/command")) bytes = command.getBytes(StandardCharsets.UTF_8);
      else if (path.equals("/client.js")) {
        bytes = loader.read("client");
        content = "application/javascript";
      } else if (path.equals("/file.zip")) {
        bytes = PAYLOAD;
        content = "application/zip";
        extra = "Content-Disposition: attachment; filename=\"" + name + "\"\r\n";
      } else if (path.equals("/redirect")) {
        status = "302 Found";
        extra = "Location: http://127.0.0.1:1/rejected\r\n";
        bytes = new byte[0];
      } else if (path.equals("/truncated")) bytes = PAYLOAD;
      else if (path.equals("/slow")) bytes = new byte[1024 * 1024];
      else {
        bytes = loader.read("page");
        content = "text/html; charset=utf-8";
      }
      OutputStream output = client.getOutputStream();
      long length = path.equals("/truncated") ? bytes.length + 100 : bytes.length;
      output.write(
          ("HTTP/1.1 "
                  + status
                  + "\r\nContent-Type: "
                  + content
                  + "\r\nContent-Length: "
                  + length
                  + "\r\nCache-Control: no-store\r\nConnection: close\r\n"
                  + extra
                  + "\r\n")
              .getBytes(StandardCharsets.US_ASCII));
      if (path.equals("/slow")) {
        for (int index = 0; index < bytes.length; index += 4096) {
          output.write(bytes, index, 4096);
          output.flush();
          Thread.sleep(30);
        }
      } else output.write(bytes);
      output.flush();
    } catch (Exception expectedDisconnect) {
      // Truncation and cancellation deliberately disconnect this private fixture.
    }
  }

  @Override
  public void close() {
    closed = true;
    try {
      socket.close();
    } catch (IOException alreadyClosed) {
    }
    workers.shutdownNow();
  }
}
