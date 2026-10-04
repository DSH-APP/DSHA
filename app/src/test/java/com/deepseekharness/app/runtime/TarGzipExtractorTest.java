package com.deepseekharness.app.runtime;

import com.deepseekharness.app.backup.*;
import org.junit.*;
import org.junit.rules.TemporaryFolder;
import java.io.*;
import java.nio.file.Files;
import java.util.*;
import static org.junit.Assert.*;

public class TarGzipExtractorTest {
  @Rule public TemporaryFolder temp = new TemporaryFolder();

  static class LinkFs implements BackupFileSystem {
    final JvmBackupFileSystem disk = new JvmBackupFileSystem();
    final Map<String, String> links = new HashMap<>();
    boolean failLink, failMode;

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
      return disk.read(f, n);
    }

    public OutputStream create(File f) throws IOException {
      return disk.create(f);
    }

    public void directory(File f) throws IOException {
      disk.directory(f);
    }

    public void move(File a, File b) throws IOException {
      disk.move(a, b);
    }

    public void delete(File f) throws IOException {
      disk.delete(f);
    }

    public void syncDirectory(File f) throws IOException {
      disk.syncDirectory(f);
    }

    public void mode(File f, int mode) throws IOException {
      if (failMode) throw new IOException("INJECTED_MODE_FAILURE");
      disk.mode(f, mode);
    }

    public void symlink(String to, File f) throws IOException {
      if (failLink) throw new IOException("INJECTED_LINK_FAILURE");
      links.put(f.getPath(), to);
    }
  }

  private byte[] entry(String name, char kind, String target, byte[] body) throws Exception {
    byte[] header = new byte[512];
    put(header, 0, name);
    put(header, 100, "0000755");
    put(header, 124, String.format("%011o", body.length));
    header[156] = (byte) kind;
    put(header, 157, target);
    Arrays.fill(header, 148, 156, (byte) ' ');
    int sum = 0;
    for (byte b : header) sum += b & 255;
    put(header, 148, String.format("%06o", sum));
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    out.write(header);
    out.write(body);
    out.write(new byte[(512 - body.length % 512) % 512]);
    return out.toByteArray();
  }

  private void put(byte[] bytes, int at, String value) {
    byte[] data = value.getBytes(java.nio.charset.StandardCharsets.UTF_8);
    System.arraycopy(data, 0, bytes, at, data.length);
  }

  private byte[] archive(byte[]... entries) throws Exception {
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    for (byte[] e : entries) out.write(e);
    out.write(new byte[1024]);
    return out.toByteArray();
  }

  private void extract(byte[] bytes, File root, LinkFs fs) throws Exception {
    TarGzipExtractor.extractSelected(new ByteArrayInputStream(bytes), root, 0, name -> true, fs);
  }

  @Test
  public void fileNamesCannotEscapeTheGrantedRoot() throws Exception {
    File root = temp.newFolder(), outside = new File(root.getParentFile(), "outside");
    for (String name :
        new String[] {"../outside", "/outside", "inside/../../outside", "C:\\outside"}) {
      assertThrows(
          IOException.class,
          () -> extract(archive(entry(name, '0', "", new byte[] {1})), root, new LinkFs()));
      assertFalse(outside.exists());
      assertEquals(0, Objects.requireNonNull(root.list()).length);
    }
  }

  @Test
  public void oversizedDeclaredFileIsRejectedBeforeAnyOutputIsCreated() throws Exception {
    File root = temp.newFolder();
    byte[] declared = entry("oversized", '0', "", new byte[0]);
    put(declared, 124, "100000000001"); // 8 GiB + 1, with no body allocation.
    Arrays.fill(declared, 148, 156, (byte) ' ');
    int checksum = 0;
    for (byte value : declared) checksum += value & 255;
    put(declared, 148, String.format("%06o", checksum));
    IOException failure =
        assertThrows(IOException.class, () -> extract(archive(declared), root, new LinkFs()));
    assertTrue(failure.getMessage(), failure.getMessage().contains("LIMIT"));
    assertEquals(0, Objects.requireNonNull(root.list()).length);
  }

  @Test
  public void absoluteGuestLinksAndEquivalentExistingLinksAreSafeAndIdempotent() throws Exception {
    File root = temp.newFolder();
    LinkFs fs = new LinkFs();
    byte[] bytes =
        archive(
            entry("usr/bin/tool", '0', "", "actual bytes".getBytes()),
            entry("bin", '2', "/usr/bin", new byte[0]));
    extract(bytes, root, fs);
    assertEquals("actual bytes", Files.readString(new File(root, "usr/bin/tool").toPath()));
    assertEquals("usr/bin", fs.links.get(new File(root, "bin").getPath()));
    fs.links.put(new File(root, "bin").getPath(), "./usr/bin");
    extract(bytes, root, fs);
    assertEquals("actual bytes", Files.readString(new File(root, "usr/bin/tool").toPath()));
  }

  @Test
  public void chainedSymlinkEscapeCannotPublishAnyLinksOrOutsideFile() throws Exception {
    File root = temp.newFolder(), outside = new File(root.getParentFile(), "escape-owned-fixture");
    LinkFs fs = new LinkFs();
    byte[] bytes = archive(entry("a", '2', ".", new byte[0]), entry("b", '2', "a/..", new byte[0]));
    assertThrows(IOException.class, () -> extract(bytes, root, fs));
    assertTrue(fs.links.isEmpty());
    assertFalse(outside.exists());
  }

  @Test
  public void validLinkIndirectionIsNotFlattenedDuringExtraction() throws Exception {
    File root = temp.newFolder();
    LinkFs fs = new LinkFs();
    extract(
        archive(
            entry("usr/bin/tool", '0', "", new byte[] {1}),
            entry("usr/bin/current", '2', "tool", new byte[0]),
            entry("usr/bin/next", '2', "current", new byte[0]),
            entry("usr/local/bin/entry", '2', "/usr/bin/next", new byte[0])),
        root,
        fs);
    assertEquals("current", fs.links.get(new File(root, "usr/bin/next").getPath()));
    assertEquals(
        "../../../usr/bin/next", fs.links.get(new File(root, "usr/local/bin/entry").getPath()));
  }

  @Test
  public void preexistingParentAndFileLinksNeverBecomeWriteTargets() throws Exception {
    File root = temp.newFolder(), outside = temp.newFile();
    Files.writeString(outside.toPath(), "untouched");
    LinkFs fs = new LinkFs();
    fs.links.put(new File(root, "p").getPath(), outside.getParent());
    assertThrows(
        IOException.class, () -> extract(archive(entry("p/x", '0', "", new byte[] {1})), root, fs));
    assertEquals("untouched", Files.readString(outside.toPath()));
    fs.links.clear();
    fs.links.put(new File(root, "leaf").getPath(), outside.getPath());
    assertThrows(
        IOException.class,
        () -> extract(archive(entry("leaf", '0', "", new byte[] {1})), root, fs));
    assertEquals("untouched", Files.readString(outside.toPath()));
  }

  @Test
  public void linkAndModeFailuresIncludeActualPathInsteadOfSilentSuccess() throws Exception {
    File root = temp.newFolder();
    LinkFs fs = new LinkFs();
    fs.failLink = true;
    IOException link =
        assertThrows(
            IOException.class,
            () -> extract(archive(entry("bin", '2', "usr/bin", new byte[0])), root, fs));
    assertTrue(link.getMessage(), link.getMessage().contains("bin:LINK"));
    fs.failLink = false;
    fs.failMode = true;
    IOException mode =
        assertThrows(
            IOException.class,
            () -> extract(archive(entry("tool", '0', "", new byte[] {1})), root, fs));
    assertTrue(mode.getMessage(), mode.getMessage().contains("tool:FILE"));
  }

  @Test
  public void hardlinksCannotReadOutsideAndUnsupportedTypesCannotBeSkipped() throws Exception {
    File root = temp.newFolder();
    LinkFs fs = new LinkFs();
    assertThrows(
        IOException.class,
        () -> extract(archive(entry("hard", '1', "../outside", new byte[0])), root, fs));
    IOException special =
        assertThrows(
            IOException.class,
            () -> extract(archive(entry("device", '3', "", new byte[0])), root, fs));
    assertTrue(special.getMessage(), special.getMessage().contains("device:type="));
  }

  @Test
  public void footerTruncationAndCorruptionNeverReportSuccessfulExtraction() throws Exception {
    File root = temp.newFolder();
    LinkFs fs = new LinkFs();
    byte[] data = archive(entry("file", '0', "", new byte[] {1, 2, 3}));
    assertThrows(IOException.class, () -> extract(Arrays.copyOf(data, data.length - 1), root, fs));
    data[148] ^= 1;
    assertThrows(IOException.class, () -> extract(data, root, fs));
  }
}
