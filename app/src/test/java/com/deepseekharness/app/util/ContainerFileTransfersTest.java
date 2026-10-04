package com.deepseekharness.app.util;

import com.deepseekharness.app.backup.*;
import org.junit.*;
import org.junit.rules.TemporaryFolder;
import java.io.*;
import java.nio.file.Files;
import java.util.*;
import static org.junit.Assert.*;

public class ContainerFileTransfersTest {
  @Rule public TemporaryFolder temporary = new TemporaryFolder();

  static class Fs implements BackupFileSystem {
    final JvmBackupFileSystem disk = new JvmBackupFileSystem();
    final Map<String, String> links = new HashMap<>();
    Runnable beforeRead, beforeMove;
    boolean failCreate;
    int largestRead;

    public Node stat(File f) throws IOException {
      return links.containsKey(f.getPath())
          ? new Node("LINK", "link:" + f, 0, 0, 1, 0700)
          : disk.stat(f);
    }

    public String readLink(File f) throws IOException {
      return links.containsKey(f.getPath()) ? links.get(f.getPath()) : disk.readLink(f);
    }

    public List<String> list(File f) throws IOException {
      return disk.list(f);
    }

    public InputStream read(File f, Node n) throws IOException {
      if (beforeRead != null) {
        Runnable action = beforeRead;
        beforeRead = null;
        action.run();
      }
      if (!n.same(stat(f))) throw new IOException("SOURCE_CHANGED");
      return new FilterInputStream(disk.read(f, n)) {
        public int read(byte[] b, int off, int len) throws IOException {
          largestRead = Math.max(largestRead, len);
          return super.read(b, off, len);
        }
      };
    }

    public OutputStream create(File f) throws IOException {
      if (failCreate) throw new IOException("NO_SPACE");
      return disk.create(f);
    }

    public void directory(File f) throws IOException {
      disk.directory(f);
    }

    public void move(File a, File b) throws IOException {
      if (beforeMove != null) {
        Runnable action = beforeMove;
        beforeMove = null;
        action.run();
      }
      disk.move(a, b);
    }

    public void delete(File f) throws IOException {
      disk.delete(f);
    }

    public void syncDirectory(File f) throws IOException {
      disk.syncDirectory(f);
    }

    public void mode(File f, int mode) throws IOException {
      disk.mode(f, mode);
    }

    public void symlink(String target, File f) throws IOException {
      links.put(f.getPath(), target);
    }
  }

  Fs fs;
  File app, files, root, cache, storage, stable;
  List<String[]> binds;
  List<File[]> aliases;

  @Before
  public void setup() throws Exception {
    fs = new Fs();
    app = temporary.newFolder();
    files = new File(app, "files");
    root = new File(files, "linux/ubuntu");
    cache = new File(app, "cache");
    storage = temporary.newFolder();
    stable = new File(files, "user-data-v5/dsh");
    for (File f : List.of(root, cache, stable, new File(root, "root/.dsh")))
      Files.createDirectories(f.toPath());
    binds = Collections.singletonList(new String[] {stable.getPath(), "/root/.dsh"});
    aliases = Collections.singletonList(new File[] {new File(app, "framework-files"), files});
  }

  File put(File folder, String name, String text) throws Exception {
    File file = new File(folder, name);
    Files.createDirectories(file.getParentFile().toPath());
    Files.writeString(file.toPath(), text);
    return file;
  }

  ContainerFileTransfers path(String guest) throws Exception {
    return new ContainerFileTransfers(
        fs, root, storage, binds, aliases, List.of(files, cache, storage), guest, () -> {});
  }

  @Test
  public void realPluginPushPullUsesStableHomeAndLeavesLegacyAndSourceOriginals() throws Exception {
    File source = put(cache, "plugin-import.bin", "actual plugin package bytes"),
        legacy = put(new File(root, "root/.dsh"), "keep", "legacy original");
    path("/root/.dsh/plugin-upload-fixture.bin").push(source);
    assertEquals(
        "actual plugin package bytes",
        Files.readString(new File(stable, "plugin-upload-fixture.bin").toPath()));
    File destination = put(cache, "plugin-export.tar.gz", "prior target");
    path("/root/.dsh/plugin-upload-fixture.bin").pull(destination);
    assertEquals("actual plugin package bytes", Files.readString(destination.toPath()));
    assertEquals("legacy original", Files.readString(legacy.toPath()));
    assertTrue(source.isFile());
  }

  @Test
  public void guestAbsoluteLinkAndFrameworkPrivateAliasRemainSupported() throws Exception {
    File source = put(new File(files, "imports"), "package.bin", "platform alias bytes");
    fs.links.put(new File(root, "root/shortcut").getPath(), "/root/.dsh");
    File declared = new File(app, "framework-files/imports/package.bin");
    path("/root/shortcut/linked.bin").push(declared);
    assertEquals("platform alias bytes", Files.readString(new File(stable, "linked.bin").toPath()));
    assertTrue(source.isFile());
  }

  @Test
  public void privateGuestPersonalStorageLinkIsBoundedToGrantedStorage() throws Exception {
    File source = put(cache, "source", "personal bytes");
    fs.links.put(new File(root, "root/phone").getPath(), "/sdcard");
    path("/root/phone/project/file.txt").push(source);
    assertEquals(
        "personal bytes", Files.readString(new File(storage, "project/file.txt").toPath()));
  }

  @Test
  public void externalRelativeEscapeAndMalformedGuestPathNeverWriteOutside() throws Exception {
    File source = put(cache, "source", "input"), outside = put(app, "outside-original", "keep");
    fs.links.put(new File(root, "root/escape").getPath(), "../../../../outside-original");
    assertThrows(IOException.class, () -> path("/root/escape").push(source));
    assertEquals("keep", Files.readString(outside.toPath()));
    assertThrows(IOException.class, () -> path("/root/../../outside"));
    assertThrows(IOException.class, () -> path("../outside"));
  }

  @Test
  public void danglingReadFailsButSafeBoundedWriteKeepsTheLinkObject() throws Exception {
    fs.links.put(new File(root, "root/dangling").getPath(), "missing-local.txt");
    File destination = put(cache, "destination", "keep");
    assertThrows(IOException.class, () -> path("/root/dangling").pull(destination));
    assertEquals("keep", Files.readString(destination.toPath()));
    path("/root/dangling").push(put(cache, "source", "new bytes"));
    assertEquals("new bytes", Files.readString(new File(root, "root/missing-local.txt").toPath()));
    assertEquals("missing-local.txt", fs.links.get(new File(root, "root/dangling").getPath()));
  }

  @Test
  public void descriptorReplacementRaceAndCreationFailureKeepDestination() throws Exception {
    File source = put(cache, "source", "source bytes"),
        destination = put(stable, "target", "keep destination");
    fs.beforeRead = () -> fs.links.put(source.getPath(), "outside");
    assertThrows(IOException.class, () -> path("/root/.dsh/target").push(source));
    assertEquals("keep destination", Files.readString(destination.toPath()));
    fs.links.clear();
    fs.failCreate = true;
    assertThrows(IOException.class, () -> path("/root/.dsh/target").push(source));
    assertEquals("keep destination", Files.readString(destination.toPath()));
  }

  @Test
  public void replacementBetweenValidationAndRenameIsRetainedAndNotOverwritten() throws Exception {
    File source = put(cache, "source", "candidate bytes"),
        destination = put(stable, "target", "old");
    fs.beforeMove =
        () -> {
          try {
            Files.writeString(destination.toPath(), "replacement original remains");
          } catch (IOException e) {
            throw new AssertionError(e);
          }
        };
    assertThrows(IOException.class, () -> path("/root/.dsh/target").push(source));
    assertEquals("replacement original remains", Files.readString(destination.toPath()));
  }

  @Test
  public void multiMegabyteTransferIsStreamedAndMappingChangeDoesNotDeleteOriginal()
      throws Exception {
    File source = new File(cache, "large");
    byte[] bytes = new byte[7 * 1024 * 1024];
    new Random(1).nextBytes(bytes);
    Files.write(source.toPath(), bytes);
    path("/root/.dsh/large").push(source);
    assertArrayEquals(bytes, Files.readAllBytes(new File(stable, "large").toPath()));
    assertTrue(fs.largestRead <= 262144);
    File dest = put(stable, "keep", "old target");
    int[] calls = {0};
    var boundary =
        new ContainerFileTransfers(
            fs,
            root,
            storage,
            binds,
            aliases,
            List.of(files, cache, storage),
            "/root/.dsh/keep",
            () -> {
              if (++calls[0] > 1) throw new IOException("DOMAIN_CHANGED");
            });
    assertThrows(IOException.class, () -> boundary.push(source));
    assertEquals("old target", Files.readString(dest.toPath()));
  }
}
