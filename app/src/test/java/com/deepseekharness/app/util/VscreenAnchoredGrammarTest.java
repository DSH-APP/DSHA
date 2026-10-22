package com.deepseekharness.app.util;

import static org.junit.Assert.*;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.Test;

public final class VscreenAnchoredGrammarTest {
  private static final String ID = "aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee";

  private Map<String, Object> base() {
    var body = new LinkedHashMap<String, Object>();
    body.put("coreInstance", ID);
    body.put("generation", "display-one");
    body.put("frameSeq", 10L);
    return body;
  }

  @Test
  public void explicitPostAnchorAndClickPreserveOriginalSequence() throws Exception {
    var anchor = base();
    anchor.put("observationId", ID);
    anchor.put("x", 12);
    anchor.put("y", 20);
    var request = VscreenBridgeRequest.json("/app/vscreen/anchor", anchor);
    assertEquals("/app/vscreen/anchor", request.route());
    assertEquals("10", request.parameters.get("frameSeq"));
    var click = base();
    click.put("anchorId", ID);
    assertEquals(
        "/app/vscreen/anchored-click",
        VscreenBridgeRequest.json("/app/vscreen/anchored-click", click).route());
    assertTrue(VscreenBridgeRequest.postRoute("/app/vscreen/anchored-click"));
    assertTrue(VirtualScreenRoutes.mutates("anchored-click"));
    assertFalse(VirtualScreenRoutes.mutates("anchor"));
  }

  @Test
  public void missingIdentityGuessedTokenAndFractionalCoordinatesAreRejected() throws Exception {
    var body = base();
    body.put("observationId", ID);
    body.put("x", 1.5);
    body.put("y", 2);
    assertThrows(
        HttpProtocol.Failure.class, () -> VscreenBridgeRequest.json("/app/vscreen/anchor", body));
    body.put("x", 1);
    body.put("observationId", "NOT-A-LEASE");
    assertThrows(
        HttpProtocol.Failure.class, () -> VscreenBridgeRequest.json("/app/vscreen/anchor", body));
    body.put("observationId", ID);
    body.remove("coreInstance");
    assertThrows(
        HttpProtocol.Failure.class, () -> VscreenBridgeRequest.json("/app/vscreen/anchor", body));
  }

  @Test
  public void legacyTapGrammarIsStillExplicitFrameAndNoImplicitAnchor() throws Exception {
    var body = base();
    body.put("x", 1);
    body.put("y", 2);
    var request = VscreenBridgeRequest.json("/app/vscreen/tap", body);
    assertEquals(VscreenBridgeRequest.Operation.TAP, request.operation);
    body.put("anchorId", ID);
    assertThrows(
        HttpProtocol.Failure.class, () -> VscreenBridgeRequest.json("/app/vscreen/tap", body));
  }
}
