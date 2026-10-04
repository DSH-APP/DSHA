package com.deepseekharness.app.util;

import org.junit.Test;
import static org.junit.Assert.*;

public class ManagedInstallPathTest {
  @Test
  public void acceptsAssetAndGuestRelativeTargets() {
    assertTrue(ManagedInstallPath.valid("builtin-plugins/dsh-web-mobile/lib/client.js"));
    assertTrue(ManagedInstallPath.valid("root/.dsh/plugin-manager.py"));
    assertTrue(ManagedInstallPath.valid("etc/profile.d/dsha-runtime-env.sh"));
  }

  @Test
  public void rejectsAbsoluteTraversalAndAmbiguousSpellings() {
    for (String path :
        new String[] {
          null,
          "",
          "/root/key",
          "../key",
          "root/../key",
          "root/./key",
          "root//key",
          "root/key/",
          "root\\key",
          "C:/key",
          "root/\0key"
        }) assertFalse(String.valueOf(path), ManagedInstallPath.valid(path));
  }
}
