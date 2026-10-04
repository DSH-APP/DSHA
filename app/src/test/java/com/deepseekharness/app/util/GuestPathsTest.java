package com.deepseekharness.app.util;

import java.io.File;
import java.io.IOException;
import org.junit.Test;
import static org.junit.Assert.*;

public class GuestPathsTest {
  @Test
  public void translatesManagedPathAndRejectsSiblingPrefixEscape() throws Exception {
    File root = new File("build/guest-path-fixture/linux/ubuntu");
    assertEquals(
        "/usr/local/lib/node_modules/@scope/helper/cli.js",
        GuestPaths.toGuest(
            root, new File(root, "usr/local/lib/node_modules/@scope/helper/cli.js")));
    assertThrows(
        IOException.class,
        () -> GuestPaths.toGuest(root, new File(root.getParentFile(), "ubuntu-other/secret")));
    assertThrows(IOException.class, () -> GuestPaths.toGuest(root, new File(root, "../../secret")));
    assertThrows(IOException.class, () -> GuestPaths.toGuest(null, new File(root, "helper")));
  }
}
