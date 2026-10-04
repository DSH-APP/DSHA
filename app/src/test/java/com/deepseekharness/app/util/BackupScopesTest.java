package com.deepseekharness.app.util;

import org.junit.Test;
import static org.junit.Assert.*;

public class BackupScopesTest {
  @Test
  public void hostScopesAreImmutableAndDoNotReplaceLegacyIntegerIds() {
    assertEquals(5, BackupScopes.ALL.size());
    assertTrue(BackupScopes.ALL.contains(BackupScopes.APPLICATION));
    assertFalse(BackupScopes.ALL.contains("full"));
    assertThrows(UnsupportedOperationException.class, () -> BackupScopes.ALL.add("untrusted"));
  }
}
