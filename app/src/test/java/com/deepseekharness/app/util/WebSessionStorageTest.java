package com.deepseekharness.app.util;

import static org.junit.Assert.*;
import com.deepseekharness.app.backup.BackupFileSystem;
import com.deepseekharness.app.backup.JvmBackupFileSystem;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

public final class WebSessionStorageTest {
  @Rule public TemporaryFolder temporary = new TemporaryFolder();

  private static final class PlatformAlias extends File {
    File destination;

    PlatformAlias(File destination) {
      super("/data/user/0/dsh.android.client");
      this.destination = destination;
    }

    @Override
    public boolean isAbsolute() {
      return true;
    }

    @Override
    public File getCanonicalFile() throws IOException {
      return destination.getCanonicalFile();
    }
  }

  private static final class FrameworkHome extends File {
    final PlatformAlias parent;

    FrameworkHome(PlatformAlias parent) {
      super(parent, "no_backup");
      this.parent = parent;
    }

    @Override
    public boolean isAbsolute() {
      return true;
    }

    @Override
    public File getParentFile() {
      return parent;
    }
  }

  /** lstat resolves platform parents; an anchored descriptor walk still refuses their symlink. */
  private static final class StrictParents extends JvmBackupFileSystem {
    final PlatformAlias alias;
    File blocked;
    boolean replaced;

    StrictParents(PlatformAlias alias) {
      this.alias = alias;
    }

    File mapped(File path) {
      String prefix = alias.getAbsolutePath() + File.separator, value = path.getAbsolutePath();
      return value.startsWith(prefix)
          ? new File(alias.destination, value.substring(prefix.length()))
          : path;
    }

    void anchored(File path) throws IOException {
      if (path.getAbsolutePath().startsWith(alias.getAbsolutePath() + File.separator))
        throw new IOException("PARENT_LINK");
    }

    @Override
    public BackupFileSystem.Node stat(File path) throws IOException {
      if (blocked != null && blocked.equals(path)) return new Node("LINK", "link", 0, 0, 1, 0700);
      Node result = super.stat(mapped(path));
      if (replaced && path.getName().equals("no_backup"))
        return new Node(
            result.type, "replacement", result.size, result.modified, result.device, result.mode);
      return result;
    }

    @Override
    public OutputStream create(File path) throws IOException {
      anchored(path);
      return super.create(path);
    }

    @Override
    public InputStream read(File path, Node identity) throws IOException {
      anchored(path);
      return super.read(path, identity);
    }

    @Override
    public void move(File source, File target) throws IOException {
      anchored(source);
      anchored(target);
      super.move(source, target);
    }

    @Override
    public void syncDirectory(File path) throws IOException {
      anchored(path);
      super.syncDirectory(path);
    }
  }

  @Test
  public void frameworkAliasReproducesOldFailureAndBoundDirectoryPublishesExactBytes()
      throws Exception {
    File data = temporary.newFolder(), real = new File(data, "no_backup");
    assertTrue(real.mkdir());
    var alias = new PlatformAlias(data);
    var declared = new FrameworkHome(alias);
    var fs = new StrictParents(alias);
    byte[] sealed = "fixture-encrypted-session".getBytes(java.nio.charset.StandardCharsets.UTF_8);
    assertEquals(
        "PARENT_LINK",
        assertThrows(IOException.class, () -> fs.atomic(declared, "web-session.enc", sealed))
            .getMessage());
    var bound = WebSessionStorage.bind(fs, alias, declared);
    assertEquals(real.getCanonicalFile(), bound.directory);
    fs.atomic(bound.directory, "web-session.enc", sealed);
    bound.verify(fs);
    assertArrayEquals(sealed, fs.small(new File(real, "web-session.enc"), 32768));
  }

  @Test
  public void writableNoBackupChildLinkAndForeignFrameworkParentStayRejected() throws Exception {
    File data = temporary.newFolder(), real = new File(data, "no_backup");
    assertTrue(real.mkdir());
    var alias = new PlatformAlias(data);
    var fs = new StrictParents(alias);
    fs.blocked = real;
    assertThrows(
        IOException.class, () -> WebSessionStorage.bind(fs, alias, new FrameworkHome(alias)));
    fs.blocked = null;
    File foreign = temporary.newFolder();
    assertTrue(new File(foreign, "no_backup").mkdir());
    assertThrows(
        IOException.class, () -> WebSessionStorage.bind(fs, alias, new File(foreign, "no_backup")));
    assertFalse(new File(foreign, "no_backup/web-session.enc").exists());
  }

  @Test
  public void changedFrameworkAuthorityAndReplacedPrivateDirectoryCannotKeepBinding()
      throws Exception {
    File data = temporary.newFolder();
    Files.createDirectory(new File(data, "no_backup").toPath());
    var alias = new PlatformAlias(data);
    var fs = new StrictParents(alias);
    var home = WebSessionStorage.bind(fs, alias, new FrameworkHome(alias));
    fs.replaced = true;
    assertEquals(
        "WEB_SESSION_PRIVATE_DIRECTORY_CHANGED",
        assertThrows(IOException.class, () -> home.verify(fs)).getMessage());
    fs.replaced = false;
    alias.destination = temporary.newFolder();
    assertThrows(IOException.class, () -> home.verify(fs));
  }
}
