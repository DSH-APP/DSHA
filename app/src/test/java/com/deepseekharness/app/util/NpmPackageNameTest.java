package com.deepseekharness.app.util;

import org.junit.Test;
import static org.junit.Assert.*;

public class NpmPackageNameTest {
  @Test
  public void archiveNamesPreserveHistoricalCaseAndRefusePathAliases() {
    assertTrue(NpmPackageName.validArchive("@Vendor/Plugin.v1"));
    assertTrue(NpmPackageName.validArchive("a".repeat(214)));
    for (String invalid :
        new String[] {
          null, "", ".", "..", "@a/.", "@./a", "x..y", "../x", "a".repeat(215), "@a/x/y"
        }) assertFalse(String.valueOf(invalid), NpmPackageName.validArchive(invalid));
  }
}
