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
  public void invalidStripDoesNotCreateAnExtractionSessionOrDirectory() throws Exception {
    File root = new File(temp.newFolder(), "uncreated");
    for (int strip : new int[] {-1, 65}) {
      IOException failure =
          assertThrows(
              IOException.class,
              () ->
                  TarGzipExtractor.extractSelected(
                      new ByteArrayInputStream(new byte[0]), root, strip, name -> true));
      assertEquals("TAR_STRIP", failure.getMessage());
      assertFalse(root.exists());
    }
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

  @Test
  public void freshFilesUseOneExclusiveCreateWithoutStagingRenames() throws Exception {
    File root = temp.newFolder();
    int[] operations = new int[3];
    LinkFs fs =
        new LinkFs() {
          @Override
          public OutputStream create(File file) throws IOException {
            operations[0]++;
            assertFalse(file.getName().startsWith(".dsha-tar-part-"));
            return super.create(file);
          }

          @Override
          public void move(File from, File to) throws IOException {
            operations[1]++;
            super.move(from, to);
          }

          @Override
          public void syncDirectory(File directory) throws IOException {
            operations[2]++;
            super.syncDirectory(directory);
          }
        };
    byte[][] members = new byte[40][];
    for (int i = 0; i < members.length; i++)
      members[i] = entry("files/f" + i, '0', "", new byte[] {(byte) i});
    extract(archive(members), root, fs);
    assertArrayEquals(new int[] {40, 0, 40}, operations);
    for (int i = 0; i < members.length; i++)
      assertArrayEquals(
          new byte[] {(byte) i}, Files.readAllBytes(new File(root, "files/f" + i).toPath()));
  }

  @Test
  public void existingFilesKeepStagingAndRollbackWhenPublicationFails() throws Exception {
    File root = temp.newFolder();
    File original = new File(root, "tool");
    Files.writeString(original.toPath(), "original");
    int[] moves = {0};
    LinkFs fs =
        new LinkFs() {
          @Override
          public OutputStream create(File file) throws IOException {
            assertTrue(file.getName().startsWith(".dsha-tar-part-"));
            return super.create(file);
          }

          @Override
          public void move(File from, File to) throws IOException {
            moves[0]++;
            if (from.getName().startsWith(".dsha-tar-part-"))
              throw new IOException("INJECTED_PUBLICATION_FAILURE");
            super.move(from, to);
          }
        };
    IOException failure =
        assertThrows(
            IOException.class,
            () -> extract(archive(entry("tool", '0', "", "new".getBytes())), root, fs));
    assertTrue(failure.getMessage(), failure.getMessage().contains("PUBLICATION_FAILURE"));
    assertEquals(3, moves[0]);
    assertEquals("original", Files.readString(original.toPath()));
    assertArrayEquals(new String[] {"tool"}, root.list());
  }

  @Test
  public void copyFailureRemovesOnlyTheFileCreatedByThisMember() throws Exception {
    File root = temp.newFolder();
    LinkFs fs =
        new LinkFs() {
          @Override
          public OutputStream create(File file) throws IOException {
            return new FilterOutputStream(super.create(file)) {
              @Override
              public void write(byte[] bytes, int offset, int count) throws IOException {
                out.write(bytes, offset, Math.min(count, 1));
                throw new IOException("INJECTED_COPY_FAILURE");
              }
            };
          }
        };
    IOException failure =
        assertThrows(
            IOException.class,
            () -> extract(archive(entry("tool", '0', "", new byte[] {1, 2})), root, fs));
    assertTrue(failure.getMessage(), failure.getMessage().contains("COPY_FAILURE"));
    assertEquals(0, Objects.requireNonNull(root.list()).length);
  }

  @Test
  public void replacementDuringCloseCannotBeDeletedAsOurPartialFile() throws Exception {
    File root = temp.newFolder();
    LinkFs fs =
        new LinkFs() {
          int generation;

          @Override
          public Node stat(File file) throws IOException {
            Node actual = super.stat(file);
            // Windows BasicFileAttributes 没有 inode；夹具显式注入文件层的出生身份。
            return file.getName().equals("tool") && actual.type.equals("FILE")
                ? new Node(
                    actual.type,
                    actual.key + ":" + generation,
                    actual.size,
                    actual.modified,
                    actual.device,
                    actual.mode)
                : actual;
          }

          @Override
          public OutputStream create(File file) throws IOException {
            return new FilterOutputStream(super.create(file)) {
              @Override
              public void close() throws IOException {
                super.close();
                Files.move(file.toPath(), new File(root, "owned-retained").toPath());
                Files.writeString(file.toPath(), "replacement");
                generation++;
              }
            };
          }
        };
    IOException failure =
        assertThrows(
            IOException.class,
            () -> extract(archive(entry("tool", '0', "", new byte[] {1})), root, fs));
    assertTrue(failure.getMessage(), failure.getMessage().contains("TARGET_CHANGED"));
    assertEquals("replacement", Files.readString(new File(root, "tool").toPath()));
    assertArrayEquals(
        new byte[] {1}, Files.readAllBytes(new File(root, "owned-retained").toPath()));
  }

  @Test
  public void interruptDuringCopyDoesNotPublishOrReportSuccess() throws Exception {
    File root = temp.newFolder();
    LinkFs fs =
        new LinkFs() {
          @Override
          public OutputStream create(File file) throws IOException {
            return new FilterOutputStream(super.create(file)) {
              @Override
              public void write(byte[] bytes, int offset, int count) throws IOException {
                out.write(bytes, offset, count);
                Thread.currentThread().interrupt();
              }
            };
          }
        };
    try {
      IOException failure =
          assertThrows(
              IOException.class,
              () -> extract(archive(entry("tool", '0', "", new byte[300_000])), root, fs));
      assertTrue(failure.getMessage(), failure.getMessage().contains("CANCELLED"));
      assertTrue(Thread.currentThread().isInterrupted());
      assertEquals(0, Objects.requireNonNull(root.list()).length);
    } finally {
      Thread.interrupted();
    }
  }

  static class TrustedFs extends LinkFs implements TrustedAssetFileSystem {
    final File root;
    final List<String> phases = new ArrayList<>();
    final Set<File> pending = new HashSet<>();
    boolean failFinish;

    TrustedFs(File root) {
      this.root = root;
    }

    @Override
    public File target(String path, boolean createParents) throws IOException {
      if (createParents) parents(root, path);
      return child(root, path);
    }

    @Override
    public CreatedFile createWithMode(File file, int mode) throws IOException {
      OutputStream opened = super.create(file);
      return new CreatedFile(opened, stat(file)) {
        @Override
        public void close() throws IOException {
          super.close();
          disk.mode(file, mode);
          phases.add("file-mode:" + file.getName());
        }
      };
    }

    @Override
    public void mode(File file, int mode) throws IOException {
      phases.add("late-mode:" + file.getName());
      super.mode(file, mode);
    }

    @Override
    public void syncDirectory(File directory) {
      pending.add(directory);
    }

    @Override
    public void finishFiles() throws IOException {
      phases.add("finish-files");
      if (failFinish) throw new IOException("INJECTED_BATCH_SYNC_FAILURE");
      for (File directory : pending) disk.syncDirectory(directory);
      pending.clear();
    }
  }

  static class SmallFs extends TrustedFs {
    final List<String> prepared = new ArrayList<>();
    boolean failPrepared;

    SmallFs(File root) {
      super(root);
    }

    @Override
    public boolean supportsSmallFiles() {
      return true;
    }

    @Override
    public CreatedFile prepareSmallFile(File file, byte[] bytes, int count, int mode)
        throws IOException {
      prepared.add(file.getName());
      CreatedFile opened = super.createWithMode(file, mode);
      opened.write(bytes, 0, count);
      return new CreatedFile(opened, opened.identity) {
        @Override
        public void close() throws IOException {
          super.close();
          if (failPrepared) throw new IOException("INJECTED_SMALL_PREPARE_FAILURE");
        }
      };
    }
  }

  @Test
  public void missingSmallMembersUsePreparedPathWhileExistingAndLargeFilesKeepStreaming()
      throws Exception {
    File root = temp.newFolder();
    Files.writeString(new File(root, "existing").toPath(), "original");
    SmallFs fs = new SmallFs(root);
    byte[] shortBytes = new byte[TrustedAssetFileSystem.SMALL_FILE_BYTES];
    byte[] largeBytes = new byte[shortBytes.length + 1];
    Arrays.fill(shortBytes, (byte) 7);
    Arrays.fill(largeBytes, (byte) 9);
    extract(
        archive(
            entry("empty", '0', "", new byte[0]),
            entry("short", '0', "", shortBytes),
            entry("large", '0', "", largeBytes),
            entry("existing", '0', "", new byte[] {3})),
        root,
        fs);
    assertEquals(Arrays.asList("empty", "short"), fs.prepared);
    assertArrayEquals(shortBytes, Files.readAllBytes(new File(root, "short").toPath()));
    assertArrayEquals(largeBytes, Files.readAllBytes(new File(root, "large").toPath()));
    assertArrayEquals(new byte[] {3}, Files.readAllBytes(new File(root, "existing").toPath()));
    assertEquals(0, new File(root, "empty").length());
  }

  @Test
  public void incompleteOrCancelledSmallInputCreatesNoFile() throws Exception {
    File root = temp.newFolder();
    SmallFs fs = new SmallFs(root);
    ArchiveFileBoundary boundary = new ArchiveFileBoundary(fs, root);
    IOException truncated =
        assertThrows(
            IOException.class,
            () ->
                boundary.file(
                    new LegacyTarReader.Member("short", "FILE", "", 3),
                    new ByteArrayInputStream(new byte[] {1, 2})));
    assertTrue(truncated.getMessage(), truncated.getMessage().contains("TRUNCATED"));
    try {
      InputStream cancelled =
          new ByteArrayInputStream(new byte[] {1, 2, 3}) {
            @Override
            public synchronized int read(byte[] bytes, int offset, int count) {
              int n = super.read(bytes, offset, count);
              Thread.currentThread().interrupt();
              return n;
            }
          };
      IOException failure =
          assertThrows(
              IOException.class,
              () -> boundary.file(new LegacyTarReader.Member("short", "FILE", "", 3), cancelled));
      assertTrue(failure.getMessage(), failure.getMessage().contains("CANCELLED"));
    } finally {
      Thread.interrupted();
    }
    assertTrue(fs.prepared.isEmpty());
    assertEquals(0, Objects.requireNonNull(root.list()).length);
  }

  @Test
  public void preparedFailureCleansOnlyItsOwnedFileAndExclusiveCreateRaceIsRetained()
      throws Exception {
    File root = temp.newFolder();
    SmallFs fs = new SmallFs(root);
    fs.failPrepared = true;
    IOException failed =
        assertThrows(
            IOException.class,
            () -> extract(archive(entry("short", '0', "", new byte[] {1})), root, fs));
    assertTrue(failed.getMessage(), failed.getMessage().contains("SMALL_PREPARE_FAILURE"));
    assertEquals(0, Objects.requireNonNull(root.list()).length);
    SmallFs raced =
        new SmallFs(root) {
          @Override
          public CreatedFile prepareSmallFile(File file, byte[] bytes, int count, int mode)
              throws IOException {
            Files.writeString(file.toPath(), "racing owner");
            throw new IOException("INJECTED_EXCLUSIVE_CREATE_RACE");
          }
        };
    IOException conflict =
        assertThrows(
            IOException.class,
            () -> extract(archive(entry("short", '0', "", new byte[] {1})), root, raced));
    assertTrue(conflict.getMessage(), conflict.getMessage().contains("EXCLUSIVE_CREATE_RACE"));
    assertEquals("racing owner", Files.readString(new File(root, "short").toPath()));
  }

  @Test
  public void trustedSessionCompletesFileModesAndBatchSyncBeforeDirectoryPermissions()
      throws Exception {
    File root = temp.newFolder();
    TrustedFs fs = new TrustedFs(root);
    byte[] bytes =
        archive(
            entry("d", '5', "", new byte[0]),
            entry("d/one", '0', "", new byte[] {1}),
            entry("d/two", '0', "", new byte[] {2}));
    extract(bytes, root, fs);
    assertEquals(
        Arrays.asList("file-mode:one", "file-mode:two", "finish-files", "late-mode:d"), fs.phases);
    assertTrue(fs.pending.isEmpty());
  }

  @Test
  public void batchSyncFailureAndCorruptGzipNeverFinishSuccessfully() throws Exception {
    File root = temp.newFolder();
    byte[] bytes =
        archive(entry("d", '5', "", new byte[0]), entry("d/one", '0', "", new byte[] {1}));
    TrustedFs failing = new TrustedFs(root);
    failing.failFinish = true;
    IOException failure = assertThrows(IOException.class, () -> extract(bytes, root, failing));
    assertTrue(failure.getMessage(), failure.getMessage().contains("BATCH_SYNC_FAILURE"));
    assertFalse(failing.phases.contains("late-mode:d"));

    ByteArrayOutputStream encoded = new ByteArrayOutputStream();
    try (java.util.zip.GZIPOutputStream gzip = new java.util.zip.GZIPOutputStream(encoded)) {
      gzip.write(bytes);
    }
    byte[] corrupt = encoded.toByteArray();
    corrupt[corrupt.length - 8] ^= 1;
    TrustedFs crc = new TrustedFs(temp.newFolder());
    IOException trailer = assertThrows(IOException.class, () -> extract(corrupt, crc.root, crc));
    assertTrue(trailer.getMessage(), trailer.getMessage().contains("GZIP_TRAILER"));
    assertFalse(crc.phases.contains("finish-files"));
  }

  @Test
  public void trustedCleanupUsesOpenedDescriptorIdentityInsteadOfRecognizingAReplacement()
      throws Exception {
    File root = temp.newFolder();
    TrustedFs fs =
        new TrustedFs(root) {
          int generation;

          @Override
          public Node stat(File file) throws IOException {
            Node actual = super.stat(file);
            return file.getName().equals("tool") && actual.type.equals("FILE")
                ? new Node(
                    actual.type,
                    actual.key + ":" + generation,
                    actual.size,
                    actual.modified,
                    actual.device,
                    actual.mode)
                : actual;
          }

          @Override
          public CreatedFile createWithMode(File file, int mode) throws IOException {
            CreatedFile opened = super.createWithMode(file, mode);
            Files.move(file.toPath(), new File(root, "owned-retained").toPath());
            Files.writeString(file.toPath(), "replacement");
            generation++;
            return opened;
          }
        };
    IOException failure =
        assertThrows(
            IOException.class,
            () -> extract(archive(entry("tool", '0', "", new byte[] {1})), root, fs));
    assertTrue(failure.getMessage(), failure.getMessage().contains("TARGET_CHANGED"));
    assertEquals("replacement", Files.readString(new File(root, "tool").toPath()));
    assertArrayEquals(
        new byte[] {1}, Files.readAllBytes(new File(root, "owned-retained").toPath()));
  }
}
