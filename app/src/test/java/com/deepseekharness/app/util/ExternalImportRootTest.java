package com.deepseekharness.app.util;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

public class ExternalImportRootTest {
  @Rule public final TemporaryFolder temporary = new TemporaryFolder();

  @Test
  public void createsMissingDirectoryAndReturnsCanonicalPath() throws Exception {
    File configured = new File(temporary.getRoot(), "external-device-import");
    assertFalse(configured.exists());
    File root = ExternalImportRoot.resolve(configured);
    assertTrue(root.isDirectory());
    assertEquals(configured.getCanonicalFile(), root);
  }

  /** Android 16 回归：/data/user/0 这类系统祖先别名不能再被判成 IMPORT_PATH_ALIAS。 */
  @Test
  public void allowsSymlinkedAncestorAndReturnsRealPath() throws Exception {
    File realParent = temporary.newFolder("real-parent");
    File aliasParent = new File(temporary.getRoot(), "alias-parent");
    assertTrue(
        Files.isSymbolicLink(Files.createSymbolicLink(aliasParent.toPath(), realParent.toPath())));
    File configured = new File(aliasParent, "external-device-import");
    assertFalse(configured.getAbsoluteFile().equals(configured.getCanonicalFile()));

    File root = ExternalImportRoot.resolve(configured);

    assertTrue(root.isDirectory());
    assertEquals(new File(realParent, "external-device-import").getCanonicalFile(), root);
  }

  @Test
  public void rejectsLeafSymbolicLinkToDirectory() throws Exception {
    File outside = temporary.newFolder("outside");
    File leaf = new File(temporary.getRoot(), "external-device-import");
    Files.createSymbolicLink(leaf.toPath(), outside.toPath());

    IOException error = assertThrows(IOException.class, () -> ExternalImportRoot.resolve(leaf));

    assertEquals("IMPORT_PATH_ALIAS", error.getMessage());
  }

  @Test
  public void rejectsLeafSymbolicLinkToMissingTarget() throws Exception {
    File leaf = new File(temporary.getRoot(), "external-device-import");
    Files.createSymbolicLink(leaf.toPath(), new File(temporary.getRoot(), "missing").toPath());

    IOException error = assertThrows(IOException.class, () -> ExternalImportRoot.resolve(leaf));

    assertEquals("IMPORT_PATH_ALIAS", error.getMessage());
  }

  @Test
  public void rejectsRegularFileAtLeaf() throws Exception {
    File leaf = temporary.newFile("external-device-import");

    IOException error = assertThrows(IOException.class, () -> ExternalImportRoot.resolve(leaf));

    assertEquals("IMPORT_DIRECTORY", error.getMessage());
  }

  @Test
  public void rejectsNullConfiguredRoot() {
    IOException error = assertThrows(IOException.class, () -> ExternalImportRoot.resolve(null));
    assertEquals("IMPORT_DIRECTORY", error.getMessage());
  }
}
