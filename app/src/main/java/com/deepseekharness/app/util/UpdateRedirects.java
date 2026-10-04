package com.deepseekharness.app.util;

import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.URL;

/** A single bounded redirect chain; unknown results never select a different source. */
public final class UpdateRedirects {
  private UpdateRedirects() {}

  public interface Open {
    HttpURLConnection apply(String url) throws Exception;
  }

  public interface Check {
    void active() throws IOException;
  }

  public static HttpURLConnection open(
      String original, long offset, String userAgent, Open open, Check check) throws Exception {
    if (offset < 0 || !UpdateLocations.feed(original) && !UpdateLocations.artifact(original))
      throw new IOException("UPDATE_SOURCE_NOT_ALLOWED");
    String target = original;
    for (int hop = 0; hop < 6; hop++) {
      check.active();
      if (!UpdateLocations.redirect(original, target))
        throw new IOException("UPDATE_REDIRECT_NOT_ALLOWED");
      HttpURLConnection conn = open.apply(target);
      boolean keep = false;
      try {
        conn.setConnectTimeout(15000);
        conn.setReadTimeout(30000);
        conn.setInstanceFollowRedirects(false);
        conn.setRequestProperty("User-Agent", userAgent);
        conn.setRequestProperty("Accept-Encoding", "identity");
        if (offset > 0) conn.setRequestProperty("Range", "bytes=" + offset + "-");
        int code = conn.getResponseCode();
        check.active();
        if (code == 301 || code == 302 || code == 303 || code == 307 || code == 308) {
          String location = conn.getHeaderField("Location");
          if (location == null || location.length() > 8192)
            throw new IOException("UPDATE_REDIRECT_LOCATION");
          String next = new URL(new URL(target), location).toString();
          if (!UpdateLocations.redirect(original, next))
            throw new IOException("UPDATE_REDIRECT_NOT_ALLOWED");
          target = next;
          continue;
        }
        if (code != 200 && !(offset > 0 && code == 206)) throw new IOException("HTTP " + code);
        keep = true;
        return conn;
      } finally {
        if (!keep) conn.disconnect();
      }
    }
    throw new IOException("UPDATE_REDIRECT_LIMIT");
  }
}
