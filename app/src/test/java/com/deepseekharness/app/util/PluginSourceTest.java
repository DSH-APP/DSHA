package com.deepseekharness.app.util;

import org.junit.Test;
import static org.junit.Assert.*;

public class PluginSourceTest {
  @Test
  public void npmNamesStayQuotedAndNeverBecomeShellOptions() {
    assertEquals("npm '@scope/demo@1.2.3'", PluginSource.parse("@scope/demo@1.2.3").command());
    assertEquals("npm 'dsh-demo@latest'", PluginSource.parse("npm:dsh-demo@latest").command());
    assertEquals("npm 'dsh-demo'", PluginSource.parse("dsh-demo").command());
    for (String input :
        new String[] {"npm:--help", "npm:../pkg", "npm:a;id", "npm:a$(id)", "npm:a b"}) {
      try {
        PluginSource.parse(input);
        fail(input);
      } catch (IllegalArgumentException expected) {
      }
    }
  }

  @Test
  public void keepsBranchDirectoryAndEncodedSlash() {
    PluginSource source =
        PluginSource.parse("https://github.com/o/r/tree/feature%2Fandroid/packages/plugin");
    assertEquals("feature/android/packages/plugin", source.github.treePath);
    assertTrue(source.command().contains("feature/android/packages/plugin"));
    assertEquals(
        "dev/packages/plugin",
        PluginSource.parse("https://github.com/o/r/blob/dev/packages/plugin/package.json")
            .github
            .treePath);
  }

  @Test
  public void archivesAndReleaseAssetsNeverFallBackToHead() {
    assertEquals(
        "release 'o' 'r' 'v2'",
        PluginSource.parse("https://github.com/o/r/releases/tag/v2").command());
    assertEquals(
        "release 'o' 'r' 'latest'",
        PluginSource.parse("https://github.com/o/r/releases/latest").command());
    for (String link :
        new String[] {
          "https://github.com/o/r/archive/refs/tags/v2.tar.gz",
          "https://github.com/o/r/releases/download/v2/plugin.zip",
          "https://github.com/o/r/releases/latest/download/plugin.tgz",
          "https://codeload.github.com/o/r/tar.gz/refs/heads/dev",
          "https://plugins.example.com/plugin.tgz?download=1"
        }) {
      assertEquals(link, PluginSource.parse(link).url);
      assertTrue(PluginSource.parse(link).command().startsWith("download "));
    }
  }

  @Test
  public void recognizesSingleLinkInShareText() {
    assertEquals("o", PluginSource.parse("分享插件：[点这里](https://github.com/o/r)，试一下").github.owner);
    assertEquals("r", PluginSource.parse("git@github.com:o/r.git").github.repo);
    assertEquals("r", PluginSource.parse("o/r").github.repo);
  }

  @Test
  public void rejectsUnsupportedHostsAmbiguityAndTraversal() {
    for (String invalid :
        new String[] {
          "https://example.com/o/r",
          "git@evil.com:o/r",
          "https://github.com.evil.test/o/r",
          "https://evil@github.com/o/r",
          "https://github.com/o/r/issues/1",
          "https://github.com/o/r/tree/main/%2e%2e/root",
          "../r",
          "https://github.com/o/r https://github.com/o/other",
          "file:///plugin.zip",
          "https://github.com/o/r/blob/main",
          "https://github.com:443/o/r"
        }) {
      try {
        PluginSource.parse(invalid);
        fail(invalid);
      } catch (IllegalArgumentException expected) {
      }
    }
  }
}
