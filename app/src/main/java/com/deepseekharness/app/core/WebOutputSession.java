package com.deepseekharness.app.core;

import com.deepseekharness.app.util.DshAuthLog;
import com.deepseekharness.app.util.WebPortPolicy;
import java.io.*;
import java.nio.charset.StandardCharsets;

/** One output reader; emits evidence but owns no lifecycle, fallback or stop decision. */
final class WebOutputSession {
  interface Events {
    boolean current();

    void output(String redacted);

    boolean authenticated();

    void authentication(String officialUrl);

    void readFailure(IOException failure);

    void exited(int exitCode);
  }

  private final int listenPort;

  WebOutputSession(int listenPort) {
    this.listenPort = listenPort;
  }

  void drain(Process process, Events events) {
    StringBuilder scan = new StringBuilder();
    DshAuthLog log = new DshAuthLog();
    try (Reader in = new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8)) {
      char[] buffer = new char[8192];
      int count;
      while ((count = in.read(buffer)) != -1) {
        String chunk = new String(buffer, 0, count);
        if (!events.current()) continue;
        scan.append(chunk);
        if (scan.length() > 64_384) scan.delete(0, scan.length() - 64_384);
        events.output(log.append(chunk));
        if (events.current() && !events.authenticated()) {
          String url = WebPortPolicy.authentication(scan.toString(), listenPort);
          if (url != null) events.authentication(url);
        }
      }
    } catch (IOException | RuntimeException failure) {
      if (events.current()) reportReadFailure(events, failure);
    } finally {
      if (events.current())
        try {
          events.output(log.finish());
        } catch (RuntimeException failure) {
          reportReadFailure(events, failure);
        }
    }
    // EOF and read errors do not prove exit. Await this exact Process before emitting exit.
    try {
      int code = process.waitFor();
      events.exited(code);
    } catch (InterruptedException interrupted) {
      Thread.currentThread().interrupt();
    }
  }

  private static void reportReadFailure(Events events, Exception failure) {
    try {
      events.readFailure(
          failure instanceof IOException
              ? (IOException) failure
              : new IOException("WEB_OUTPUT_CALLBACK_FAILED", failure));
    } catch (RuntimeException diagnosticFailure) {
      /* A failed sink still cannot bypass exact Process exit observation. */
    }
  }
}
