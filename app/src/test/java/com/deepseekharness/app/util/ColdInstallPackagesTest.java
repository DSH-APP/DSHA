package com.deepseekharness.app.util;

import com.deepseekharness.app.backup.JvmBackupFileSystem;
import com.deepseekharness.app.backup.BackupFileSystem;
import org.junit.*;
import org.junit.rules.TemporaryFolder;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.util.*;
import static org.junit.Assert.*;

public class ColdInstallPackagesTest {
  @Rule public TemporaryFolder temporary = new TemporaryFolder();
  private final JvmBackupFileSystem fs = new JvmBackupFileSystem();

  private File slot() throws Exception {
    File root = temporary.newFolder();
    for (String name :
        new String[] {"a_1~2_arm64.deb", "SHA256SUMS", "version.txt", "packages.txt"})
      Files.writeString(new File(root, name).toPath(), "frozen:" + name);
    return root;
  }

  @Test
  public void byteChangesAndExtraMembersCannotReuseFrozenIdentity() throws Exception {
    File slot = slot();
    String original = ColdInstallPackages.fingerprint(fs, slot);
    assertEquals(original, ColdInstallPackages.fingerprint(fs, slot));
    Files.writeString(new File(slot, "a_1~2_arm64.deb").toPath(), "different");
    assertNotEquals(original, ColdInstallPackages.fingerprint(fs, slot));
    Files.writeString(new File(slot, "foreign").toPath(), "keep");
    assertThrows(IOException.class, () -> ColdInstallPackages.fingerprint(fs, slot));
  }

  @Test
  public void sourceDirectoriesAndMissingControlFilesAreRejected() throws Exception {
    File slot = slot();
    File metadata = new File(slot, "SHA256SUMS");
    assertTrue(metadata.delete());
    assertThrows(IOException.class, () -> ColdInstallPackages.fingerprint(fs, slot));
    Files.writeString(metadata.toPath(), "hash");
    File packet = new File(slot, "a_1~2_arm64.deb");
    assertTrue(packet.delete());
    assertTrue(packet.mkdir());
    assertEquals(
        "COLD_PACKAGE_FILE_TYPE",
        assertThrows(IOException.class, () -> ColdInstallPackages.fingerprint(fs, slot))
            .getMessage());
  }

  private static final class PlatformAlias extends File {
    File destination;

    PlatformAlias(File destination) {
      super("/data/data/com.dsh.clienu");
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

  private static final class FrameworkFiles extends File {
    final PlatformAlias data;

    FrameworkFiles(PlatformAlias data) {
      super(data, "files");
      this.data = data;
    }

    @Override
    public boolean isAbsolute() {
      return true;
    }

    @Override
    public File getParentFile() {
      return data;
    }
  }

  /** Reproduce Android's differing lstat vs descriptor traversal contract on real fixture bytes. */
  private static final class StrictParents implements BackupFileSystem {
    final JvmBackupFileSystem delegate = new JvmBackupFileSystem();
    final PlatformAlias alias;
    File blocked;
    final Map<String, String> identity = new HashMap<>();

    StrictParents(PlatformAlias alias) {
      this.alias = alias;
    }

    String absolute(File file) {
      return file.getAbsoluteFile().getPath();
    }

    File mapped(File file) {
      String path = absolute(file), prefix = absolute(alias);
      return path.startsWith(prefix + File.separator)
          ? new File(alias.destination, path.substring(prefix.length() + 1))
          : file;
    }

    void anchored(File file) throws IOException {
      if (absolute(file).startsWith(absolute(alias) + File.separator))
        throw new IOException("PARENT_LINK");
      for (File at = file.getParentFile(); at != null; at = at.getParentFile())
        if (stat(at).type.equals("LINK")) throw new IOException("PARENT_LINK");
    }

    @Override
    public Node stat(File file) throws IOException {
      if (blocked != null && absolute(blocked).equals(absolute(file)))
        return new Node("LINK", "link", 0, 0, 1, 0700);
      Node node = delegate.stat(mapped(file));
      String key = identity.get(absolute(file));
      return key == null
          ? node
          : new Node(node.type, key, node.size, node.modified, node.device, node.mode);
    }

    @Override
    public List<String> list(File file) throws IOException {
      anchored(file);
      return delegate.list(file);
    }

    @Override
    public InputStream read(File file, Node expected) throws IOException {
      anchored(file);
      return delegate.read(file, expected);
    }

    @Override
    public OutputStream create(File file) throws IOException {
      anchored(file);
      return delegate.create(file);
    }

    @Override
    public void directory(File file) throws IOException {
      anchored(file);
      delegate.directory(file);
    }

    @Override
    public void move(File source, File target) throws IOException {
      anchored(source);
      anchored(target);
      delegate.move(source, target);
    }

    @Override
    public void delete(File file) throws IOException {
      anchored(file);
      delegate.delete(file);
    }

    @Override
    public String readLink(File file) throws IOException {
      anchored(file);
      return delegate.readLink(file);
    }

    @Override
    public void syncDirectory(File file) throws IOException {
      anchored(file);
      delegate.syncDirectory(file);
    }

    @Override
    public void mode(File file, int mode) throws IOException {
      anchored(file);
      delegate.mode(file, mode);
    }

    @Override
    public void symlink(String target, File link) throws IOException {
      anchored(link);
      delegate.symlink(target, link);
    }
  }

  private File privateTree(File data) throws Exception {
    File files = new File(data, "files");
    Files.createDirectories(new File(files, "linux/ubuntu/root").toPath());
    return files;
  }

  private void populate(File target) throws Exception {
    assertTrue(target.mkdir());
    for (String name :
        new String[] {"a_1~2_arm64.deb", "SHA256SUMS", "packages.txt", "version.txt"})
      Files.writeString(new File(target, name).toPath(), "frozen:" + name);
  }

  @Test
  public void frameworkPlatformAliasIsResolvedBeforeStrictDescriptorTraversalForClonePackage()
      throws Exception {
    File data = temporary.newFolder("com.dsh.clienu"), files = privateTree(data);
    var alias = new PlatformAlias(data);
    var declared = new FrameworkFiles(alias);
    var strict = new StrictParents(alias);
    String name = ".dsha-bundled-tools-" + UUID.randomUUID();
    File real = new File(files, "linux/ubuntu/root/" + name);
    populate(real);
    File raw = new File(declared, "linux/ubuntu/root/" + name);
    assertEquals("DIRECTORY", strict.stat(raw).type);
    assertEquals(
        "PARENT_LINK",
        assertThrows(IOException.class, () -> ColdInstallPackages.fingerprint(strict, raw))
            .getMessage());
    var bound = ColdInstallPackages.bindPrivateFiles(strict, alias, declared);
    assertEquals(files.getCanonicalFile(), bound.files);
    assertEquals(real, ColdInstallPackages.ownedSlot(strict, bound.files, name));
    assertEquals(
        ColdInstallPackages.fingerprint(fs, real),
        ColdInstallPackages.fingerprint(strict, bound, name));
  }

  @Test
  public void writableFilesAndGuestParentLinksRemainRejected() throws Exception {
    File data = temporary.newFolder(), files = privateTree(data);
    var alias = new PlatformAlias(data);
    var declared = new FrameworkFiles(alias);
    var strict = new StrictParents(alias);
    strict.blocked = files;
    assertEquals(
        "COLD_PACKAGE_FILES_LINK_OR_MISSING",
        assertThrows(
                IOException.class,
                () -> ColdInstallPackages.bindPrivateFiles(strict, alias, declared))
            .getMessage());
    strict.blocked = null;
    var bound = ColdInstallPackages.bindPrivateFiles(strict, alias, declared);
    for (String parent : List.of("linux", "linux/ubuntu", "linux/ubuntu/root")) {
      strict.blocked = new File(files, parent);
      assertThrows(
          IOException.class,
          () ->
              ColdInstallPackages.ownedSlot(
                  strict, bound.files, ".dsha-bundled-tools-" + UUID.randomUUID()));
    }
    strict.blocked = null;
  }

  @Test
  public void anotherDataAuthorityAndReplacedFilesDirectoryCannotRebaseTheSlot() throws Exception {
    File data = temporary.newFolder(), files = privateTree(data), other = temporary.newFolder();
    privateTree(other);
    var alias = new PlatformAlias(data);
    var declared = new FrameworkFiles(alias);
    var strict = new StrictParents(alias);
    var wrong = new PlatformAlias(other);
    assertThrows(
        IOException.class, () -> ColdInstallPackages.bindPrivateFiles(strict, wrong, declared));
    // Android lstat supplies dev+inode. Windows' JDK can return a null
    // fileKey, so model the two native identities for this replacement.
    strict.identity.put(strict.absolute(files), "native-inode-before");
    var bound = ColdInstallPackages.bindPrivateFiles(strict, alias, declared);
    Files.writeString(new File(files, "new-diagnostic.txt").toPath(), "ordinary owned diagnostic");
    bound.verify(strict);
    alias.destination = other;
    assertEquals(
        "COLD_PACKAGE_HOST_ROOT_CHANGED",
        assertThrows(IOException.class, () -> bound.verify(strict)).getMessage());
    alias.destination = data;
    Files.move(files.toPath(), new File(data, "files.retained").toPath());
    Files.createDirectory(files.toPath());
    strict.identity.put(strict.absolute(files), "native-inode-after");
    assertEquals(
        "COLD_PACKAGE_HOST_ROOT_CHANGED",
        assertThrows(IOException.class, () -> bound.verify(strict)).getMessage());
    assertEquals(
        "ordinary owned diagnostic",
        Files.readString(new File(data, "files.retained/new-diagnostic.txt").toPath()));
  }
}
