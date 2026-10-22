package com.deepseekharness.app.backup;

import org.junit.Test;
import static org.junit.Assert.*;

public class PrivateRootsTest {
  @Test
  public void diagnosticsAndTransactionProofsStayPrivateButUserDocumentRootDoesNotChange() {
    assertTrue(PrivateRoots.top("cold-install-diagnostics.txt"));
    assertTrue(PrivateRoots.top("diagnostic-events.txt"));
    assertTrue(PrivateRoots.top("diagnostic-events.1.txt"));
    assertTrue(PrivateRoots.top("startup-history"));
    assertTrue(PrivateRoots.top("recovery-maintenance-tools"));
    assertFalse(PrivateRoots.top("recovery-maintenance-tools-notes"));
    assertTrue(PrivateRoots.top("host-backup-completed-v1.json"));
    assertTrue(PrivateRoots.top(".dsha-plugin-task-user.json"));
    assertTrue(PrivateRoots.top("cold-install-operations"));
    assertFalse(PrivateRoots.top("host-credential-retained"));
    assertFalse(PrivateRoots.top("linux"));
    assertFalse(PrivateRoots.top("my-project"));
    assertFalse(PrivateRoots.top(""));
  }
}
