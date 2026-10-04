package com.deepseekharness.app.util;

import org.junit.Test;
import static org.junit.Assert.*;

public class StartupPageGateTest {
  final Object view = new Object();
  final String url = "http://127.0.0.1:3087/";
  final StartupPageGate gate = new StartupPageGate(view, 9, url);

  boolean event(
      Object source,
      long generation,
      String current,
      String token,
      String page,
      String doc,
      long sequence,
      boolean currentDocument,
      boolean fatal,
      boolean ready) {
    return gate.accept(
        source,
        generation,
        current,
        gate.source(),
        token,
        page,
        doc,
        sequence,
        currentDocument,
        fatal,
        ready);
  }

  @Test
  public void currentProvenDocumentCanReportFatalOnlyOnce() {
    assertTrue(event(view, 9, url, gate.nonce(), url, "document-fixture", 0, true, false, false));
    assertTrue(event(view, 9, url, gate.nonce(), url, "document-fixture", 1, true, true, false));
    assertFalse(event(view, 9, url, gate.nonce(), url, "document-fixture", 2, true, true, false));
  }

  @Test
  public void staleViewGenerationDocumentOriginOrNonceNeverTriggers() {
    assertFalse(
        event(new Object(), 9, url, gate.nonce(), url, "document-fixture", 0, true, true, false));
    assertFalse(event(view, 10, url, gate.nonce(), url, "document-fixture", 0, true, true, false));
    assertFalse(event(view, 9, url, "incorrect", url, "document-fixture", 0, true, true, false));
    assertFalse(
        event(
            view,
            9,
            url,
            gate.nonce(),
            "http://127.0.0.1:3080/",
            "document-fixture",
            0,
            true,
            true,
            false));
    assertFalse(event(view, 9, url, gate.nonce(), url, "old-document", 0, false, true, false));
  }

  @Test
  public void readyDocumentIgnoresFatalButAProvenNewDocumentCanReport() {
    assertTrue(event(view, 9, url, gate.nonce(), url, "document-first", 0, true, false, true));
    assertFalse(event(view, 9, url, gate.nonce(), url, "document-first", 1, true, true, false));
    assertTrue(event(view, 9, url, gate.nonce(), url, "document-second", 0, true, true, false));
  }

  @Test
  public void duplicateSequencesAndWrongObserverSourceAreRejected() {
    assertTrue(event(view, 9, url, gate.nonce(), url, "document-fixture", 3, true, false, false));
    assertFalse(event(view, 9, url, gate.nonce(), url, "document-fixture", 3, true, false, false));
    assertFalse(
        gate.accept(
            view, 9, url, url, gate.nonce(), url, "document-fixture", 4, true, true, false));
  }
}
