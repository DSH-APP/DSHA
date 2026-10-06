package com.deepseekharness.app.util;

import static org.junit.Assert.*;
import java.io.IOException;
import org.junit.Test;

public final class WorkspaceDocumentIdsTest {
  @Test
  public void workspaceNamesBecomeLegacyOpaqueIdsWithoutHostPaths() throws Exception {
    assertEquals(
        "linux/ubuntu/root/deepseek-harness",
        WorkspaceDocumentIds.forGuestDirectory("/root/deepseek-harness"));
    assertEquals(
        "linux/ubuntu/root/作品 100%/final.pdf",
        WorkspaceDocumentIds.forGuestDirectory("/root/作品 100%/final.pdf"));
    assertEquals(
        "linux/ubuntu/root/project",
        WorkspaceDocumentIds.forGuestDirectory("/root//build/.././project/"));
    assertEquals("linux/ubuntu", WorkspaceDocumentIds.forGuestDirectory("/"));
    assertEquals("/root/作品 100%", WorkspaceDocumentIds.location("linux/ubuntu/root/作品 100%"));
    assertEquals("root", WorkspaceDocumentIds.parent("linux"));
    assertEquals("linux/ubuntu/root", WorkspaceDocumentIds.parent("linux/ubuntu/root/project"));
    assertEquals("DSHA", WorkspaceDocumentIds.location("root"));
  }

  @Test
  public void malformedOrEscapingIdsNeverReachAProvider() throws Exception {
    for (String id :
        new String[] {
          null,
          "",
          "/etc",
          "linux//ubuntu",
          "linux/../secret",
          "linux/./ubuntu",
          "linux\\ubuntu",
          "C:/secret",
          "bad\0name"
        }) {
      try {
        WorkspaceDocumentIds.checked(id);
        fail("Accepted " + id);
      } catch (IOException expected) {
      }
    }
    for (String path :
        new String[] {
          null,
          "root/project",
          "/../secret",
          "/root/../../secret",
          "/root\\secret",
          "/root/C:/secret",
          "/root/bad\0name"
        }) {
      try {
        WorkspaceDocumentIds.forGuestDirectory(path);
        fail("Accepted " + path);
      } catch (IOException expected) {
      }
    }
  }
}
