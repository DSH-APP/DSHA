package com.deepseekharness.app.util;

import org.junit.Test;
import static org.junit.Assert.*;

public class FileKindTest {
  @Test
  public void wireNamesRemainExactAndUnknownNamesAreRejected() {
    for (FileKind kind : FileKind.values()) assertSame(kind, FileKind.fromWire(kind.wire()));
    for (String unknown : new String[] {"file", "DIRECTORY ", "SYMLINK", "arbitrary", null})
      assertThrows(IllegalArgumentException.class, () -> FileKind.fromWire(unknown));
  }

  @Test
  public void currentNodeAndSourceItemsCarryTypedValuesWithoutChangingWireFields() {
    var node =
        new com.deepseekharness.app.backup.BackupFileSystem.Node("FILE", "key", 1, 1, 1, 0600);
    var item =
        new com.deepseekharness.app.backup.BackupSource.Item(
            "path", "DIRECTORY", 0, "token", "", "");
    assertSame(FileKind.FILE, node.kind);
    assertEquals("FILE", node.type);
    assertSame(FileKind.DIRECTORY, item.type);
    assertEquals("DIRECTORY", item.kind);
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new com.deepseekharness.app.backup.BackupSource.Item(
                "path", "unexpected", 0, "", "", ""));
  }
}
