package com.deepseekharness.app.core;

import static org.junit.Assert.*;

import com.deepseekharness.app.util.BackupScope;
import com.deepseekharness.app.util.BackupScopes;
import org.junit.Test;

public class BackupTaskScopeTest {
  @Test
  public void historicalIntegerScopesMapToExactNativeScopesWithoutIncludingCredentials() {
    int[] legacy = {
      BackupScope.FULL, BackupScope.SESSIONS, BackupScope.SETTINGS, BackupScope.PLUGINS
    };
    String[] nativeScope = {
      BackupScopes.APPLICATION, BackupScopes.SESSIONS, BackupScopes.SETTINGS, BackupScopes.PLUGINS
    };
    for (int i = 0; i < legacy.length; i++) {
      var selection = BackupTask.nativeBackupSelection(legacy[i]);
      assertEquals(nativeScope[i], selection.scope);
      assertFalse(selection.includeApiKey);
      assertTrue(selection.guestProjects.isEmpty());
      assertTrue(selection.documentProjects.isEmpty());
    }
    assertThrows(IllegalArgumentException.class, () -> BackupTask.nativeBackupSelection(-1));
    assertThrows(IllegalArgumentException.class, () -> BackupTask.nativeBackupSelection(4));
  }
}
