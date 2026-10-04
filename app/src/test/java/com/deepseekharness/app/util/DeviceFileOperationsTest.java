package com.deepseekharness.app.util;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InterruptedIOException;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.Test;

/** 实际编排器的目录句柄故障夹具；不充当 Android/FUSE 内核验证。 */
public class DeviceFileOperationsTest {
  private static final String ROOT = "/data/local/tmp";

  static final class Node {
    String type;
    byte[] bytes = new byte[0];

    Node(String type) {
      this.type = type;
    }
  }

  static final class Memory implements DeviceFileOperations.Access {
    final Map<String, Node> nodes = new LinkedHashMap<>();
    final List<String> effects = new ArrayList<>();
    Runnable beforeWrite = () -> {}, beforeDelete = () -> {};
    boolean interruptRead;

    Memory() {
      put(ROOT, "DIRECTORY");
    }

    Node put(String path, String type) {
      Node node = new Node(type);
      nodes.put(path, node);
      return node;
    }

    void file(String path, String value) {
      put(path, "FILE").bytes = value.getBytes(java.nio.charset.StandardCharsets.UTF_8);
    }

    String content(String path) {
      return new String(nodes.get(path).bytes, java.nio.charset.StandardCharsets.UTF_8);
    }

    @Override
    public DeviceFileOperations.Entry open(String raw, boolean write, boolean parents)
        throws IOException {
      String path = DeviceShellPolicy.normalize(raw);
      if (write) DeviceFileOperations.writeRoot(path);
      String parent = path.substring(0, path.lastIndexOf('/'));
      if (!nodes.containsKey(parent)) {
        if (!parents) throw new IOException("PARENT_MISSING");
        List<String> missing = new ArrayList<>();
        String at = parent;
        while (!nodes.containsKey(at)) {
          missing.add(at);
          at = at.substring(0, at.lastIndexOf('/'));
        }
        for (int i = missing.size() - 1; i >= 0; i--) put(missing.get(i), "DIRECTORY");
      }
      return new Handle(this, path, write);
    }

    @Override
    public void validate(String raw, boolean write, boolean parents) throws IOException {
      String path = DeviceShellPolicy.normalize(raw);
      if (write) DeviceFileOperations.writeRoot(path);
      String current = path;
      while (!current.equals(ROOT) && !current.isEmpty()) {
        Node node = nodes.get(current);
        if (node != null && (node.type.equals("LINK") || node.type.equals("SPECIAL")))
          throw new IOException("LINK_OR_SPECIAL_FILE");
        current = current.substring(0, current.lastIndexOf('/'));
      }
    }
  }

  static final class Handle implements DeviceFileOperations.Entry {
    final Memory fs;
    final String path, parent;
    final Node parentIdentity;
    final boolean write;
    Node identity;
    boolean closed;

    Handle(Memory fs, String path, boolean write) throws IOException {
      this.fs = fs;
      this.path = path;
      this.write = write;
      parent = path.substring(0, path.lastIndexOf('/'));
      parentIdentity = fs.nodes.get(parent);
      if (parentIdentity == null || !parentIdentity.type.equals("DIRECTORY"))
        throw new IOException("PARENT_LINK_OR_MISSING");
      identity = fs.nodes.get(path);
    }

    void verify() throws IOException {
      DeviceFileOperations.cancelled();
      if (closed || fs.nodes.get(parent) != parentIdentity || fs.nodes.get(path) != identity)
        throw new IOException("TARGET_CHANGED");
    }

    @Override
    public String path() {
      return path;
    }

    @Override
    public String name() {
      return path.substring(path.lastIndexOf('/') + 1);
    }

    @Override
    public String type() throws IOException {
      verify();
      return identity == null ? "MISSING" : identity.type;
    }

    @Override
    public DeviceFileOperations.Entry child(String name) throws IOException {
      if (!type().equals("DIRECTORY")) throw new IOException("DIRECTORY_REQUIRED");
      return new Handle(fs, path + "/" + name, write);
    }

    @Override
    public List<String> names() throws IOException {
      verify();
      List<String> result = new ArrayList<>();
      for (String candidate : fs.nodes.keySet())
        if (candidate.startsWith(path + "/") && candidate.indexOf('/', path.length() + 1) < 0)
          result.add(candidate.substring(path.length() + 1));
      return result;
    }

    @Override
    public InputStream read() throws IOException {
      if (!type().equals("FILE")) throw new IOException("REGULAR_REQUIRED");
      return new ByteArrayInputStream(identity.bytes) {
        @Override
        public synchronized int read(byte[] bytes, int offset, int length) {
          int size = super.read(bytes, offset, length);
          if (fs.interruptRead) Thread.currentThread().interrupt();
          return size;
        }
      };
    }

    @Override
    public OutputStream write() throws IOException {
      fs.beforeWrite.run();
      verify();
      if (!write || identity != null && !identity.type.equals("FILE"))
        throw new IOException("WRITE_TYPE");
      if (identity == null) identity = fs.put(path, "FILE");
      Node target = identity;
      fs.effects.add("write:" + path);
      return new ByteArrayOutputStream() {
        @Override
        public void close() {
          target.bytes = toByteArray();
        }
      };
    }

    @Override
    public void directory() throws IOException {
      verify();
      if (!write || identity != null) throw new IOException("FILE_EXISTS");
      identity = fs.put(path, "DIRECTORY");
      fs.effects.add("mkdir:" + path);
    }

    @Override
    public void touch() throws IOException {
      verify();
      if (!write || identity != null && !identity.type.equals("FILE"))
        throw new IOException("TOUCH_TYPE");
      if (identity == null) identity = fs.put(path, "FILE");
      fs.effects.add("touch:" + path);
    }

    @Override
    public void metadataTo(DeviceFileOperations.Entry target) throws IOException {
      verify();
      ((Handle) target).verify();
      fs.effects.add("metadata:" + target.path());
    }

    @Override
    public void delete() throws IOException {
      fs.beforeDelete.run();
      verify();
      if (!names().isEmpty()) throw new IOException("DIRECTORY_NOT_EMPTY");
      fs.nodes.remove(path);
      identity = null;
      fs.effects.add("delete:" + path);
    }

    @Override
    public void move(DeviceFileOperations.Entry entry, boolean noClobber) throws IOException {
      Handle target = (Handle) entry;
      verify();
      target.verify();
      if (!write || !target.write || identity == null) throw new IOException("MOVE_INVALID");
      if (noClobber && target.identity != null) throw new IOException("FILE_EXISTS");
      fs.nodes.remove(path);
      fs.nodes.put(target.path, identity);
      target.identity = identity;
      identity = null;
      fs.effects.add("move:" + path + ":" + target.path);
    }

    @Override
    public void close() {
      closed = true;
    }
  }

  void run(Memory fs, String command) throws IOException {
    DeviceFileOperations.execute(DeviceShellPolicy.inspect(command), fs);
  }

  @Test
  public void rootsAndProtectedPaths() throws Exception {
    assertEquals(ROOT, DeviceFileOperations.writeRoot(ROOT + "/new"));
    assertEquals("/storage/emulated/0", DeviceFileOperations.writeRoot("/sdcard/Download/a"));
    assertEquals(
        "/storage/emulated/10", DeviceFileOperations.writeRoot("/storage/emulated/10/Download/a"));
    assertEquals(
        "/storage/ABCD-1234", DeviceFileOperations.writeRoot("/storage/ABCD-1234/Download/a"));
    for (String path :
        new String[] {"/", ROOT, "/sdcard/Pictures/a", "/sdcard/DCIM/a", "/sdcard/Android/data/a"})
      assertThrows(IOException.class, () -> DeviceFileOperations.writeRoot(path));
  }

  @Test
  public void helperArgvTreatsQuotedNamesAsDataAndRejectsOtherOperations() {
    String path = ROOT + "/an apostrophe's ; $name";
    var plan = DeviceShellPolicy.inspectFileArguments(List.of("touch", path));
    assertEquals(DeviceShellPolicy.Kind.FILE, plan.kind);
    assertEquals(path, plan.operands.get(0));
    for (List<String> args :
        List.of(
            List.of("sh", "-c", "touch " + ROOT + "/x"),
            List.of("touch", ROOT + "/x\nmore"),
            List.of("touch", "/sdcard/Pictures/x")))
      assertEquals(DeviceShellPolicy.Kind.DENY, DeviceShellPolicy.inspectFileArguments(args).kind);
  }

  @Test
  @SuppressWarnings("unchecked")
  public void exportedRuleTableCannotMutateTheNativePolicy() {
    var rules = DeviceShellPolicy.pathRules();
    List<String> protectedPaths = (List<String>) rules.get("protected");
    List<String> smsRoots = (List<String>) rules.get("smsReadRoots");
    assertThrows(UnsupportedOperationException.class, () -> protectedPaths.set(0, "/allowed"));
    assertThrows(UnsupportedOperationException.class, () -> smsRoots.set(0, "/allowed"));
    assertFalse(DeviceShellPolicy.writeAllowed("/sdcard/DCIM/file"));
    assertTrue(
        DeviceShellPolicy.smsProviderPath(
            "/data_mirror/data_ce/null/0/com.android.providers.telephony/databases/mmssms.db"));
  }

  @Test
  public void copyMoveAndDeleteUseEntryEffects() throws Exception {
    Memory fs = new Memory();
    fs.file(ROOT + "/source", "original");
    run(fs, "cp " + ROOT + "/source " + ROOT + "/copy");
    assertEquals("original", fs.content(ROOT + "/copy"));
    run(fs, "mv " + ROOT + "/copy " + ROOT + "/moved");
    assertFalse(fs.nodes.containsKey(ROOT + "/copy"));
    run(fs, "rm " + ROOT + "/moved");
    assertFalse(fs.nodes.containsKey(ROOT + "/moved"));
    assertEquals("original", fs.content(ROOT + "/source"));
  }

  @Test
  public void mkdirTouchAndNoClobberPreserveExpectedSemantics() throws Exception {
    Memory fs = new Memory();
    run(fs, "mkdir -p " + ROOT + "/nested/a");
    run(fs, "touch " + ROOT + "/nested/a/file");
    assertEquals("FILE", fs.nodes.get(ROOT + "/nested/a/file").type);
    fs.file(ROOT + "/source", "source");
    fs.file(ROOT + "/target", "keep");
    run(fs, "cp -n " + ROOT + "/source " + ROOT + "/target");
    assertEquals("keep", fs.content(ROOT + "/target"));
    assertThrows(IOException.class, () -> run(fs, "mkdir " + ROOT + "/nested/a"));
    run(fs, "rm -f " + ROOT + "/missing");
  }

  @Test
  public void recursiveCopyAndRemoveKeepDirectoryBoundary() throws Exception {
    Memory fs = new Memory();
    fs.put(ROOT + "/source", "DIRECTORY");
    fs.file(ROOT + "/source/file", "bytes");
    run(fs, "cp -R " + ROOT + "/source " + ROOT + "/copy");
    assertEquals("bytes", fs.content(ROOT + "/copy/file"));
    run(fs, "rm -r " + ROOT + "/copy");
    assertFalse(fs.nodes.containsKey(ROOT + "/copy"));
  }

  @Test
  public void destinationLinkReplacementAndParentReplacementBlockBeforeWrite() throws Exception {
    for (boolean parent : new boolean[] {false, true}) {
      Memory fs = new Memory();
      fs.file(ROOT + "/source", "source");
      fs.put(ROOT + "/folder", "DIRECTORY");
      fs.file(ROOT + "/folder/target", "old");
      fs.file(ROOT + "/protected", "protected");
      fs.beforeWrite = () -> fs.put(parent ? ROOT + "/folder" : ROOT + "/folder/target", "LINK");
      assertThrows(
          IOException.class, () -> run(fs, "cp " + ROOT + "/source " + ROOT + "/folder/target"));
      assertEquals("protected", fs.content(ROOT + "/protected"));
      assertTrue(fs.effects.isEmpty());
    }
  }

  @Test
  public void sourceLinksAndSelfCopyNeverTruncate() throws Exception {
    Memory fs = new Memory();
    fs.file(ROOT + "/source", "source");
    fs.put(ROOT + "/link", "LINK");
    assertThrows(IOException.class, () -> run(fs, "cp " + ROOT + "/link " + ROOT + "/copy"));
    assertThrows(IOException.class, () -> run(fs, "cp " + ROOT + "/source " + ROOT + "/source"));
    assertEquals("source", fs.content(ROOT + "/source"));
    assertTrue(fs.effects.isEmpty());
  }

  @Test
  public void recursiveTargetInsideSourceAndLinksCannotBeTraversed() throws Exception {
    Memory fs = new Memory();
    fs.put(ROOT + "/folder", "DIRECTORY");
    fs.put(ROOT + "/folder/link", "LINK");
    assertThrows(
        IOException.class, () -> run(fs, "cp -R " + ROOT + "/folder " + ROOT + "/folder/copy"));
    assertThrows(IOException.class, () -> run(fs, "rm -r " + ROOT + "/folder"));
    assertTrue(fs.nodes.containsKey(ROOT + "/folder/link"));
    assertTrue(fs.effects.isEmpty());
  }

  @Test
  public void cancellationBeforeAndDuringCopyNeverReturnsSuccess() throws Exception {
    Memory fs = new Memory();
    fs.file(ROOT + "/source", "source");
    try {
      Thread.currentThread().interrupt();
      assertThrows(InterruptedIOException.class, () -> run(fs, "touch " + ROOT + "/target"));
      assertTrue(fs.effects.isEmpty());
      Thread.interrupted();
      fs.interruptRead = true;
      assertThrows(
          InterruptedIOException.class,
          () -> run(fs, "cp " + ROOT + "/source " + ROOT + "/target"));
      assertEquals("source", fs.content(ROOT + "/source"));
    } finally {
      Thread.interrupted();
    }
  }

  @Test
  public void sameNameAppearingAfterMissingSnapshotCannotBeOverwritten() throws Exception {
    Memory fs = new Memory();
    fs.file(ROOT + "/source", "source");
    fs.beforeWrite = () -> fs.file(ROOT + "/target", "racer");
    assertThrows(IOException.class, () -> run(fs, "cp " + ROOT + "/source " + ROOT + "/target"));
    assertEquals("racer", fs.content(ROOT + "/target"));
    assertTrue(fs.effects.isEmpty());
  }

  @Test
  public void copyPreserveMetadataAndRemoveEmptyDirectoryAreExplicit() throws Exception {
    Memory fs = new Memory();
    fs.file(ROOT + "/source", "source");
    run(fs, "cp -p " + ROOT + "/source " + ROOT + "/target");
    assertTrue(fs.effects.contains("metadata:" + ROOT + "/target"));
    fs.put(ROOT + "/empty", "DIRECTORY");
    run(fs, "rm -d " + ROOT + "/empty");
    assertFalse(fs.nodes.containsKey(ROOT + "/empty"));
  }

  @Test
  public void mixedDeleteBatchWithKnownLinkPerformsNoEarlierDeletion() throws Exception {
    Memory fs = new Memory();
    fs.file(ROOT + "/safe", "keep");
    fs.put(ROOT + "/link", "LINK");
    assertThrows(IOException.class, () -> run(fs, "rm " + ROOT + "/safe " + ROOT + "/link"));
    assertEquals("keep", fs.content(ROOT + "/safe"));
    assertTrue(fs.effects.isEmpty());
  }
}
