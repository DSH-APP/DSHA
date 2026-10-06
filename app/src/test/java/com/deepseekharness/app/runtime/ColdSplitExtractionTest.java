package com.deepseekharness.app.runtime;

import static org.junit.Assert.*;

import com.deepseekharness.app.backup.BackupFileSystem;
import com.deepseekharness.app.backup.JvmBackupFileSystem;
import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

public class ColdSplitExtractionTest {
  private static final String MODULES = "usr/local/lib/node_modules";
  private static final String DSH = MODULES + "/@deepseek-ai/dsh";
  private static final Set<String> BASE =
      Set.of(MODULES + "/npm/package.json", "bin/bash", "usr/local/bin", "usr/local/share");
  private static final Set<String> RUNTIME =
      Set.of(
          DSH + "/lib/bin.js",
          DSH + "/node_modules/pkg/index.js",
          MODULES + "/pkg",
          "usr/local/bin/dsh",
          "usr/local/share/dsha/dsh-runtime.version");

  @Rule public TemporaryFolder temporary = new TemporaryFolder();

  private static final class Fs extends JvmBackupFileSystem {
    final Map<String, String> keys = new ConcurrentHashMap<>(), links = new ConcurrentHashMap<>();
    final Map<String, Integer> modes = new ConcurrentHashMap<>();
    final List<String> moves = new ArrayList<>();

    @Override
    public Node stat(File file) throws IOException {
      Node actual = super.stat(file);
      String name = file.getAbsolutePath();
      return new Node(
          links.containsKey(name) ? "LINK" : actual.type,
          actual.type.equals("MISSING")
              ? ""
              : keys.computeIfAbsent(name, ignored -> UUID.randomUUID().toString()),
          actual.size,
          actual.modified,
          actual.device,
          modes.getOrDefault(name, 0700));
    }

    @Override
    public void symlink(String target, File file) throws IOException {
      try (OutputStream out = create(file)) {
        out.write(target.getBytes(java.nio.charset.StandardCharsets.UTF_8));
      }
      links.put(file.getAbsolutePath(), target);
    }

    @Override
    public String readLink(File file) throws IOException {
      String target = links.get(file.getAbsolutePath());
      if (target == null) throw new IOException("NOT_LINK");
      return target;
    }

    @Override
    public void move(File source, File target) throws IOException {
      super.move(source, target);
      moves.add(source.getPath());
      relocate(keys, source, target);
      relocate(links, source, target);
      relocate(modes, source, target);
    }

    private static <T> void relocate(Map<String, T> map, File source, File target) {
      String before = source.getAbsolutePath(), after = target.getAbsolutePath();
      for (var entry : new HashMap<>(map).entrySet())
        if (entry.getKey().equals(before) || entry.getKey().startsWith(before + File.separator)) {
          map.remove(entry.getKey());
          map.put(after + entry.getKey().substring(before.length()), entry.getValue());
        }
    }

    @Override
    public void delete(File file) throws IOException {
      super.delete(file);
      keys.remove(file.getAbsolutePath());
      links.remove(file.getAbsolutePath());
      modes.remove(file.getAbsolutePath());
    }
  }

  private void write(Fs fs, File root, String name) throws IOException {
    fs.parents(root, name);
    try (OutputStream output = fs.create(fs.child(root, name))) {
      output.write(name.getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }
  }

  private Set<String> extract(Fs fs, File base, InputStream input, File root) throws IOException {
    while (input.read() != -1) {}
    if (root.equals(base)) {
      for (String name : BASE)
        if (name.equals("usr/local/bin") || name.equals("usr/local/share"))
          fs.parents(root, name + "/unused");
        else write(fs, root, name);
      return BASE;
    }
    for (String name : RUNTIME) {
      fs.parents(root, name);
      File file = fs.child(root, name);
      if (name.equals(MODULES + "/pkg")) fs.symlink("@deepseek-ai/dsh/node_modules/pkg", file);
      else if (name.equals("usr/local/bin/dsh"))
        fs.symlink("../lib/node_modules/@deepseek-ai/dsh/lib/bin.js", file);
      else write(fs, root, name);
    }
    Set<String> manifest = new HashSet<>(RUNTIME);
    manifest.addAll(
        List.of(
            "usr",
            "usr/local",
            "usr/local/lib",
            MODULES,
            MODULES + "/@deepseek-ai",
            DSH,
            "usr/local/bin",
            "usr/local/share",
            "usr/local/share/dsha"));
    return manifest;
  }

  private File base(File operation) throws IOException {
    File root = new File(operation, "linux/ubuntu");
    Files.createDirectories(root.toPath());
    return root;
  }

  @Test
  public void independentWorkersAssembleWithoutCopyingFilesOrReplacingCandidateRoot()
      throws Exception {
    Fs fs = new Fs();
    File operation = temporary.newFolder(), base = base(operation);
    String rootKey = fs.stat(base).key;
    Map<String, String> inode = new ConcurrentHashMap<>();
    ColdSplitExtraction.run(
        fs,
        base,
        operation,
        new ByteArrayInputStream(new byte[] {1}),
        new ByteArrayInputStream(new byte[] {2}),
        2,
        null,
        (input, root) -> {
          Set<String> paths = extract(fs, base, input, root);
          for (String name : paths) {
            var node = fs.stat(fs.child(root, name));
            if (!node.type.equals("DIRECTORY") || name.startsWith(MODULES))
              inode.put(name, node.key);
          }
          return paths;
        });
    assertEquals(rootKey, fs.stat(base).key);
    assertEquals(4, fs.moves.size());
    for (String name : inode.keySet())
      assertEquals(inode.get(name), fs.stat(fs.child(base, name)).key);
    assertEquals(
        "@deepseek-ai/dsh/node_modules/pkg", fs.readLink(fs.child(base, MODULES + "/pkg")));
    assertArrayEquals(new String[] {"linux"}, operation.list());
  }

  @Test
  public void exactMemberProofRejectsUnknownAndMissingChildrenIncludingNestedDirectories()
      throws Exception {
    SignedAssetMembers proof = new SignedAssetMembers(List.of("deep/inside/file", "deep/link"));
    proof.verify("deep", List.of("inside", "link"));
    proof.verify("deep/inside", List.of("file"));
    for (List<String> actual :
        List.of(List.of("file", "unknown"), List.<String>of(), List.of("file", "file")))
      assertThrows(IOException.class, () -> proof.verify("deep/inside", actual));
  }

  @Test
  public void unknownFilesModeChangesAndSignedMemberConflictsKeepBothCandidates() throws Exception {
    for (String fault : List.of("unknown", "mode", "overlap", "bin")) {
      Fs fs = new Fs();
      File operation = temporary.newFolder(), base = base(operation);
      IOException failure =
          assertThrows(
              IOException.class,
              () ->
                  ColdSplitExtraction.run(
                      fs,
                      base,
                      operation,
                      new ByteArrayInputStream(new byte[0]),
                      new ByteArrayInputStream(new byte[0]),
                      0,
                      null,
                      (input, root) -> {
                        Set<String> actual = new HashSet<>(extract(fs, base, input, root));
                        if (root.equals(base)) {
                          if (fault.equals("unknown") || fault.equals("overlap")) {
                            String extra = MODULES + "/extra";
                            write(fs, root, extra);
                            if (fault.equals("overlap")) actual.add(extra);
                          }
                        } else if (fault.equals("mode")) {
                          fs.modes.put(fs.child(root, "usr/local").getAbsolutePath(), 0711);
                        } else if (fault.equals("bin")) {
                          String extra = "usr/local/bin/unknown";
                          fs.symlink("dsh", fs.child(root, extra));
                          actual.add(extra);
                        }
                        return actual;
                      }));
      assertNotNull(failure);
      assertTrue(fs.moves.isEmpty());
      assertTrue(new File(base, MODULES + "/npm/package.json").isFile());
      assertEquals(2, operation.list().length);
    }
  }

  @Test
  public void cancellationCannotReturnUntilBothWorkersExitAndCloseTheirInputs() throws Exception {
    Fs fs = new Fs();
    File operation = temporary.newFolder(), base = base(operation);
    CountDownLatch started = new CountDownLatch(2), release = new CountDownLatch(1);
    AtomicInteger closed = new AtomicInteger(), exited = new AtomicInteger();
    AtomicReference<Throwable> result = new AtomicReference<>();
    Thread owner =
        new Thread(
            () -> {
              try {
                ColdSplitExtraction.run(
                    fs,
                    base,
                    operation,
                    closeCount(closed),
                    closeCount(closed),
                    0,
                    null,
                    (input, root) -> {
                      started.countDown();
                      for (; ; ) {
                        try {
                          release.await();
                          break;
                        } catch (InterruptedException ignored) {
                          // 模拟仍在收敛的文件同步；取消不能把线程仍存活当作退出。
                        }
                      }
                      exited.incrementAndGet();
                      throw new IOException("WORKER_CLOSED");
                    });
              } catch (Throwable failure) {
                result.set(failure);
              }
            });
    owner.start();
    try {
      assertTrue(started.await(2, TimeUnit.SECONDS));
      owner.interrupt();
      Thread.sleep(50);
      assertTrue(owner.isAlive());
      assertEquals(0, closed.get());
    } finally {
      release.countDown();
      owner.join(3000);
    }
    assertFalse(owner.isAlive());
    assertEquals(2, exited.get());
    assertEquals(2, closed.get());
    assertTrue(result.get() instanceof IOException);
    assertEquals("CANCELLED", result.get().getMessage());
    assertTrue(owner.isInterrupted());
    assertTrue(fs.moves.isEmpty());
  }

  private InputStream closeCount(AtomicInteger closedCount) {
    return new ByteArrayInputStream(new byte[0]) {
      boolean closed;

      @Override
      public void close() {
        if (!closed) closedCount.incrementAndGet();
        closed = true;
      }
    };
  }
}
