package com.deepseekharness.app.backup;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.HashSet;
import java.util.Set;

import static org.junit.Assert.*;

public class QuarantinedPluginReviewTest {
  @Rule public TemporaryFolder temp = new TemporaryFolder();

  @Test
  public void existingReviewsDoNotPreventTheNextCandidate() throws Exception {
    var fs = new JvmBackupFileSystem();
    File parent = new File(temp.getRoot(), "reviews");
    Set<String> names = new HashSet<>();
    for (int i = 0; i < 65; i++) {
      File slot = QuarantinedPluginReview.createReviewSlot(fs, parent);
      assertTrue(slot.isDirectory());
      assertTrue(names.add(slot.getName()));
    }
    assertEquals(65, fs.list(parent).size());
    for (String name : names) assertTrue(new File(parent, name).isDirectory());
  }

  @Test
  public void nonDirectoryReviewParentIsRejectedWithoutChangingIt() throws Exception {
    var fs = new JvmBackupFileSystem();
    File parent = new File(temp.getRoot(), "reviews");
    Files.writeString(parent.toPath(), "keep this file");
    assertThrows(IOException.class, () -> QuarantinedPluginReview.createReviewSlot(fs, parent));
    assertEquals("keep this file", Files.readString(parent.toPath()));
  }
}
