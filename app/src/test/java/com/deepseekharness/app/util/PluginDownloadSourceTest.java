package com.deepseekharness.app.util;

import org.junit.Test;
import static org.junit.Assert.*;

public class PluginDownloadSourceTest {
  @Test
  public void missingOrUnsupportedPreferenceUsesAuto() {
    assertEquals(PluginDownloadSource.AUTO, PluginDownloadSource.parse(null));
    assertEquals(PluginDownloadSource.AUTO, PluginDownloadSource.parse("https://unknown.example"));
    assertEquals(PluginDownloadSource.AUTO, PluginDownloadSource.parse("mirror; exit"));
  }

  @Test
  public void storedChoicesRoundTripToFiniteCommandValues() {
    for (PluginDownloadSource value : PluginDownloadSource.values()) {
      assertSame(value, PluginDownloadSource.parse(value.value));
      assertTrue(value.value.matches("auto|official|mirror"));
    }
  }
}
