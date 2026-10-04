package com.deepseekharness.app.util;

import java.net.URI;
import java.util.UUID;

/** Browser-observer provenance/replay guard. Same-origin scripts are not isolated by this nonce. */
public final class StartupPageGate {
  private final Object owner;
  private final long generation;
  private final String base, nonce;
  private String document = "";
  private long sequence = -1;
  private boolean terminal;

  public StartupPageGate(Object owner, long generation, String base) {
    this.owner = owner;
    this.generation = generation;
    this.base = base;
    nonce = UUID.randomUUID().toString().replace("-", "");
  }

  public String nonce() {
    return nonce;
  }

  public String source() {
    return base + ".dsha-page-observer/" + nonce + ".js";
  }

  public boolean owns(Object source) {
    return source == owner;
  }

  private static String document(String url) {
    try {
      URI uri = new URI(url);
      return new URI(uri.getScheme(), null, uri.getHost(), uri.getPort(), uri.getPath(), null, null)
          .toString();
    } catch (Exception invalid) {
      return "";
    }
  }

  public synchronized boolean accept(
      Object source,
      long current,
      String currentUrl,
      String sourceUrl,
      String token,
      String eventUrl,
      String id,
      long seq,
      boolean verifiedDocument,
      boolean fatal,
      boolean ready) {
    if (source != owner
        || generation <= 0
        || current != generation
        || !verifiedDocument
        || !nonce.equals(token)
        || sourceUrl != null && !source().equals(sourceUrl)
        || !WebPreviewPolicy.sameService(base, currentUrl)
        || !document(currentUrl).equals(document(eventUrl))
        || id == null
        || !id.matches("[A-Za-z0-9_-]{8,128}")
        || seq < 0
        || seq > 501) return false;
    if (!document.equals(id)) {
      document = id;
      sequence = -1;
      terminal = false;
    }
    if (seq <= sequence || fatal && terminal) return false;
    sequence = seq;
    if (fatal || ready) terminal = true;
    return true;
  }
}
