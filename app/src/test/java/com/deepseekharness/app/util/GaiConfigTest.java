package com.deepseekharness.app.util;

import static org.junit.Assert.*;
import org.junit.Test;

public class GaiConfigTest {
  @Test
  public void automaticAndIpv4ModesPreferIpv4ButPreserveFallbackTable() {
    String comments = "# Ubuntu defaults\n#precedence ::/0 40\n";
    String configured = GaiConfig.reconcile(comments, "auto");
    assertTrue(configured.startsWith(comments));
    assertTrue(configured.contains("precedence ::ffff:0:0/96 100\n"));
    assertTrue(configured.contains("precedence ::/0 40\n"));
    assertTrue(configured.contains("precedence ::1/128 50\n"));
    assertEquals(configured, GaiConfig.reconcile(configured, "auto"));
    assertEquals(configured, GaiConfig.reconcile(configured, "ipv4"));
    assertEquals(comments, GaiConfig.reconcile(configured, "native"));
    assertEquals(configured, GaiConfig.reconcile(comments, null));
  }

  @Test
  public void userRulesAndModifiedManagedBlockAreNeverOverwritten() {
    for (String original :
        new String[] {
          "precedence ::/0 99\n",
          "label ::/0 9\n",
          "reload yes\n",
          "unknown directive\n",
          "# A comment without final newline"
        }) {
      assertEquals(original, GaiConfig.reconcile(original, "auto"));
      assertEquals(original, GaiConfig.reconcile(original, "native"));
    }
    String modified = GaiConfig.reconcile("", "auto").replace("/96 100", "/96 90");
    assertEquals(modified, GaiConfig.reconcile(modified, "auto"));
    assertEquals(modified, GaiConfig.reconcile(modified, "native"));
  }

  @Test
  public void nativeRemovesOnlyExactOwnedBlockAndPreservesUserBytes() {
    String comments = "# keep\r\n";
    String configured =
        GaiConfig.reconcile(comments, "auto").replace("\n", "\r\n").replace("\r\r\n", "\r\n");
    assertEquals(comments, GaiConfig.reconcile(configured, "native"));
    String withRule = GaiConfig.reconcile("", "auto") + "precedence ::/0 77\n";
    assertEquals("precedence ::/0 77\n", GaiConfig.reconcile(withRule, "auto"));
    assertEquals("", GaiConfig.reconcile(GaiConfig.reconcile("", "auto"), "native"));
  }
}
