package com.deepseekharness.app.util;

import org.junit.Test;
import static org.junit.Assert.*;

public class MicrophonePolicyTest {
  @Test
  public void exactLoopbackOriginAndPort() {
    assertTrue(MicrophonePolicy.trustedOrigin("http://127.0.0.1:3080", "http://127.0.0.1:3080/"));
    assertTrue(
        MicrophonePolicy.trustedOrigin("http://localhost:4141/chat", "http://localhost:4141/"));
    assertFalse(MicrophonePolicy.trustedOrigin("http://localhost:3080", "http://127.0.0.1:3080/"));
    assertFalse(MicrophonePolicy.trustedOrigin("http://127.0.0.1:3081", "http://127.0.0.1:3080/"));
  }

  @Test
  public void externalOpaqueAndConfusedOriginsAreRejected() {
    for (String value :
        new String[] {
          null,
          "",
          "https://127.0.0.1:3080",
          "http://127.0.0.1.evil:3080",
          "http://user@127.0.0.1:3080",
          "http://127.0.0.1",
          "http://127.0.0.1:65536",
          "file:///root",
          "blob:http://127.0.0.1:3080/a",
          "http://localhost\\@127.0.0.1:3080"
        }) assertFalse(value, MicrophonePolicy.trustedOrigin(value, "http://127.0.0.1:3080/"));
    assertFalse(MicrophonePolicy.trustedOrigin("http://127.0.0.1:3080", "http://evil:3080/"));
  }

  @Test
  public void onlyMicrophoneResource() {
    String audio = "android.webkit.resource.AUDIO_CAPTURE";
    assertTrue(MicrophonePolicy.audioOnly(new String[] {audio}));
    for (String[] resources :
        new String[][] {
          null,
          new String[0],
          {"android.webkit.resource.VIDEO_CAPTURE"},
          {audio, "android.webkit.resource.VIDEO_CAPTURE"},
          {audio, audio},
          {"unknown"}
        }) assertFalse(MicrophonePolicy.audioOnly(resources));
  }

  @Test
  public void cancelledSystemRequestMustDrainBeforeAnother() {
    var gate = new MicrophoneRequestGate();
    long first = gate.begin();
    gate.cancel();
    assertEquals(-1, gate.begin());
    assertFalse(gate.resolve(first, true, true));
    long next = gate.begin();
    assertTrue(next > first);
    assertFalse(gate.resolve(first, true, true));
    assertEquals(-1, gate.begin());
    assertTrue(gate.resolve(next, true, true));
  }

  @Test
  public void denialAndLostDocumentNeverGrant() {
    var gate = new MicrophoneRequestGate();
    long ticket = gate.begin();
    assertFalse(gate.resolve(ticket, false, true));
    ticket = gate.begin();
    assertFalse(gate.resolve(ticket, true, false));
    ticket = gate.begin();
    assertFalse(gate.resolve(0, true, true));
    assertTrue(gate.resolve(ticket, true, true));
    assertFalse(gate.resolve(ticket, true, true));
  }
}
