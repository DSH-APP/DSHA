package com.deepseekharness.app.util;

import org.junit.Test;
import java.util.List;
import static org.junit.Assert.*;

public class BridgeCredentialAuthTest {
  @Test
  public void queryCookieWrongAndDuplicateCredentialsCannotAuthorize() {
    String key = "fixture";
    assertTrue(BridgeCredentialAuth.authorized("/app/ui/dump", List.of(key), key));
    for (String target :
        List.of(
            "/app/ui/dump?token=fixture",
            "/app/ui/dump?%74oken=fixture",
            "/app/ui/dump?TOKEN=fixture"))
      assertFalse(BridgeCredentialAuth.authorized(target, List.of(key), key));
    assertFalse(BridgeCredentialAuth.authorized("/app/ui/dump", List.of(), key));
    assertFalse(BridgeCredentialAuth.authorized("/app/ui/dump", List.of(key, key), key));
    assertFalse(BridgeCredentialAuth.authorized("/app/ui/dump", List.of("wrong"), key));
    assertTrue(
        BridgeCredentialAuth.authorized("/exec?cmd=echo%20ok&xtoken=unrelated", List.of(key), key));
  }
}
