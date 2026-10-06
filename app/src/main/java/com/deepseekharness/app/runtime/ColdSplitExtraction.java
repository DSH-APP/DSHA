package com.deepseekharness.app.runtime;

import com.deepseekharness.app.backup.BackupControl;
import com.deepseekharness.app.backup.BackupFileSystem;
import com.deepseekharness.app.backup.BackupLimits;
import java.io.File;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BiConsumer;

/** 只在已授予的全新冷候选内并行；两个写入会话全部退出后才组装签名树。 */
final class ColdSplitExtraction {
  private static final String MODULES = "usr/local/lib/node_modules";
  private static final String DSH = MODULES + "/@deepseek-ai/dsh";
  private static final String BIN = "usr/local/bin", SHARE = "usr/local/share";
  private static final String VERSION = SHARE + "/dsha/dsh-runtime.version";
  private static final List<String> SCAFFOLD =
      List.of("", "usr", "usr/local", "usr/local/lib", BIN, SHARE);

  interface Extractor {
    Set<String> extract(InputStream input, File root) throws IOException;
  }

  private ColdSplitExtraction() {}

  static Set<String> extractSigned(InputStream input, File root) throws IOException {
    Set<String> paths = new LinkedHashSet<>();
    try (var fs = new AndroidTrustedAssetFileSystem(root, true)) {
      TarGzipExtractor.extractSelected(
          input,
          root,
          0,
          name -> {
            fs.signedMember(name);
            paths.add(name);
            return true;
          },
          fs);
    }
    return Collections.unmodifiableSet(paths);
  }

  static void run(
      BackupFileSystem fs,
      File base,
      File operation,
      InputStream baseInput,
      InputStream runtimeInput,
      long total,
      BiConsumer<Long, Long> progress,
      Extractor extractor)
      throws IOException {
    new BackupControl(null).check();
    BackupFileSystem.Node baseIdentity = fs.stat(base), operationIdentity = fs.stat(operation);
    if (!baseIdentity.type.equals("DIRECTORY")
        || !operationIdentity.type.equals("DIRECTORY")
        || baseIdentity.device != operationIdentity.device
        || !fs.list(base).isEmpty()) throw new IOException("COLD_SPLIT_ROOT_SCOPE");
    File runtime = fs.child(operation, "split-dsh-" + UUID.randomUUID());
    if (!fs.stat(runtime).type.equals("MISSING")) throw new IOException("COLD_SPLIT_ROOT_EXISTS");
    fs.directory(runtime);
    BackupFileSystem.Node runtimeIdentity = fs.stat(runtime);
    if (!runtimeIdentity.type.equals("DIRECTORY") || runtimeIdentity.device != baseIdentity.device)
      throw new IOException("COLD_SPLIT_ROOT_SCOPE");
    AtomicLong read = new AtomicLong();
    CountDownLatch completed = new CountDownLatch(2);
    Worker first = new Worker(base, baseInput, extractor, read, completed);
    Worker second = new Worker(runtime, runtimeInput, extractor, read, completed);
    Throwable ownerFailure = null;
    boolean interrupted = Thread.currentThread().isInterrupted();
    try {
      first.thread.start();
      second.thread.start();
      while (!completed.await(100, TimeUnit.MILLISECONDS)) {
        if (progress != null) progress.accept(read.get(), total);
        if (first.failure != null || second.failure != null) {
          first.thread.interrupt();
          second.thread.interrupt();
          break;
        }
      }
    } catch (InterruptedException error) {
      interrupted = true;
      ownerFailure = new IOException("CANCELLED", error);
    } catch (RuntimeException | Error error) {
      ownerFailure = error;
    } finally {
      if (ownerFailure != null || interrupted) {
        first.thread.interrupt();
        second.thread.interrupt();
      }
      // countDown 只证明 finally 已运行；实际 join 之后才能释放调用者的安装租约。
      interrupted |= join(first.thread, second.thread);
      if (interrupted) Thread.currentThread().interrupt();
    }
    if (interrupted && ownerFailure == null) ownerFailure = new IOException("CANCELLED");
    Throwable failed = ownerFailure != null ? ownerFailure : first.failure;
    if (failed == null) failed = second.failure;
    if (failed != null) {
      if (first.failure != null && first.failure != failed) failed.addSuppressed(first.failure);
      if (second.failure != null && second.failure != failed) failed.addSuppressed(second.failure);
      rethrow(failed);
    }
    if (progress != null) progress.accept(read.get(), total);
    requireIdentity(fs, operation, operationIdentity);
    assemble(fs, base, baseIdentity, runtime, runtimeIdentity, first.paths, second.paths);
  }

  private static boolean join(Thread first, Thread second) {
    boolean interrupted = false;
    for (Thread worker : new Thread[] {first, second})
      while (worker.isAlive())
        try {
          worker.join(100);
        } catch (InterruptedException ignored) {
          interrupted = true;
          first.interrupt();
          second.interrupt();
        }
    return interrupted;
  }

  private static void rethrow(Throwable error) throws IOException {
    if (error instanceof IOException io) throw io;
    if (error instanceof RuntimeException runtime) throw runtime;
    if (error instanceof Error fatal) throw fatal;
    throw new IOException("COLD_SPLIT_EXTRACTION", error);
  }

  private static final class Worker implements Runnable {
    final Thread thread;
    final File root;
    final InputStream source;
    final Extractor extractor;
    final AtomicLong read;
    final CountDownLatch completed;
    volatile Set<String> paths;
    volatile Throwable failure;

    Worker(
        File root, InputStream source, Extractor extractor, AtomicLong read, CountDownLatch done) {
      this.root = root;
      this.source = source;
      this.extractor = extractor;
      this.read = read;
      completed = done;
      thread = new Thread(this, "DSHA-cold-" + root.getName());
    }

    @Override
    public void run() {
      try (InputStream input =
          new FilterInputStream(source) {
            @Override
            public int read() throws IOException {
              int value = in.read();
              if (value >= 0) read.incrementAndGet();
              return value;
            }

            @Override
            public int read(byte[] bytes, int offset, int length) throws IOException {
              int count = in.read(bytes, offset, length);
              if (count > 0) read.addAndGet(count);
              return count;
            }
          }) {
        paths = extractor.extract(input, root);
      } catch (IOException | RuntimeException | Error error) {
        failure = error;
      } finally {
        completed.countDown();
      }
    }
  }

  static void assemble(
      BackupFileSystem fs,
      File base,
      BackupFileSystem.Node baseIdentity,
      File runtime,
      BackupFileSystem.Node runtimeIdentity,
      Set<String> basePaths,
      Set<String> runtimePaths)
      throws IOException {
    BackupControl control = new BackupControl(null);
    control.check();
    requireIdentity(fs, base, baseIdentity);
    requireIdentity(fs, runtime, runtimeIdentity);
    SignedAssetMembers baseMembers = new SignedAssetMembers(basePaths);
    SignedAssetMembers runtimeMembers = new SignedAssetMembers(runtimePaths);
    if (!baseMembers.names(MODULES).equals(Set.of("npm"))
        || runtimeMembers.names(MODULES).contains("npm")
        || !runtimePaths.contains(DSH + "/lib/bin.js")
        || !runtimePaths.contains(VERSION)
        || !runtimePaths.contains(BIN + "/dsh")) throw new IOException("COLD_SPLIT_CONTRACT");
    for (String path : basePaths)
      if (path.startsWith(MODULES + "/")
          && !path.equals(MODULES + "/npm")
          && !path.startsWith(MODULES + "/npm/")) throw new IOException("COLD_SPLIT_BASE_MODULES");
    for (String path : runtimePaths) verifyRuntimeMember(fs, runtime, path, runtimeMembers);
    for (String path : List.of(DSH + "/lib/bin.js", VERSION)) {
      var node = fs.stat(fs.child(runtime, path));
      if (!node.type.equals("FILE") || node.size <= 0)
        throw new IOException("COLD_SPLIT_REQUIRED_FILE:" + path);
    }
    Map<String, BackupFileSystem.Node> scaffold = new HashMap<>();
    for (String path : SCAFFOLD) {
      control.check();
      File to = path.isEmpty() ? runtime : fs.child(runtime, path);
      var node = fs.stat(to);
      if (!node.type.equals("DIRECTORY")) throw new IOException("COLD_SPLIT_SCAFFOLD:" + path);
      runtimeMembers.verify(path, fs.list(to));
      scaffold.put(path, node);
      File old = path.isEmpty() ? base : fs.child(base, path);
      var before = fs.stat(old);
      if (!before.type.equals("DIRECTORY") || before.mode != node.mode)
        throw new IOException("COLD_SPLIT_DIRECTORY_MODE:" + path);
    }
    File oldModules = fs.child(base, MODULES), newModules = fs.child(runtime, MODULES);
    var oldModulesIdentity = fs.stat(oldModules);
    var newModulesIdentity = fs.stat(newModules);
    if (!oldModulesIdentity.type.equals("DIRECTORY")
        || !newModulesIdentity.type.equals("DIRECTORY")
        || oldModulesIdentity.mode != newModulesIdentity.mode)
      throw new IOException("COLD_SPLIT_DIRECTORY_MODE:" + MODULES);
    baseMembers.verify(MODULES, fs.list(oldModules));
    runtimeMembers.verify(MODULES, fs.list(newModules));
    fs.list(fs.child(base, MODULES + "/npm"));
    List<String> leaves = new ArrayList<>();
    for (String name : runtimeMembers.names(BIN)) leaves.add(BIN + "/" + name);
    for (String name : runtimeMembers.names(SHARE)) leaves.add(SHARE + "/" + name);
    Map<String, BackupFileSystem.Node> sources = new HashMap<>();
    for (String path : leaves) {
      File source = fs.child(runtime, path), target = fs.child(base, path);
      var node = fs.stat(source);
      if (!node.type.equals(path.startsWith(BIN + "/") ? "LINK" : "DIRECTORY")
          || !fs.stat(target).type.equals("MISSING"))
        throw new IOException("COLD_SPLIT_TARGET_CONFLICT:" + path);
      sources.put(path, node);
    }
    File npm = fs.child(base, MODULES + "/npm"), stagedNpm = fs.child(runtime, MODULES + "/npm");
    var npmIdentity = fs.stat(npm);
    if (!npmIdentity.type.equals("DIRECTORY") || !fs.stat(stagedNpm).type.equals("MISSING"))
      throw new IOException("COLD_SPLIT_NPM_CONFLICT");
    move(fs, npm, stagedNpm, npmIdentity, control);
    requireIdentity(fs, oldModules, oldModulesIdentity);
    if (!fs.list(oldModules).isEmpty()) throw new IOException("COLD_SPLIT_MODULES_NOT_EMPTY");
    fs.delete(oldModules);
    fs.syncDirectory(oldModules.getParentFile());
    move(fs, newModules, oldModules, newModulesIdentity, control);
    for (String path : leaves)
      move(fs, fs.child(runtime, path), fs.child(base, path), sources.get(path), control);
    requireIdentity(fs, base, baseIdentity);
    requireIdentity(fs, runtime, runtimeIdentity);
    List<String> empty = new ArrayList<>(SCAFFOLD);
    empty.sort((a, b) -> Integer.compare(b.length(), a.length()));
    for (String path : empty) {
      control.check();
      File at = path.isEmpty() ? runtime : fs.child(runtime, path);
      requireIdentity(fs, at, scaffold.get(path));
      if (!fs.list(at).isEmpty()) throw new IOException("COLD_SPLIT_RETAINED_MEMBER:" + path);
      fs.delete(at);
      fs.syncDirectory(at.getParentFile());
    }
    requireIdentity(fs, base, baseIdentity);
  }

  private static void verifyRuntimeMember(
      BackupFileSystem fs, File root, String path, SignedAssetMembers members) throws IOException {
    BackupLimits.path(path);
    if (path.startsWith(DSH + "/")) return;
    var node = fs.stat(fs.child(root, path));
    if (node.type.equals("DIRECTORY")) {
      if (path.equals(DSH)
          || SCAFFOLD.contains(path)
          || path.equals(MODULES)
          || path.equals(SHARE + "/dsha")) return;
      if (path.startsWith(MODULES + "/")
          && path.substring(MODULES.length() + 1).matches("@[a-z0-9][a-z0-9._-]*")
          && !members.names(path).isEmpty()) return;
    }
    if (path.equals(VERSION) && node.type.equals("FILE")) return;
    if (Set.of(BIN + "/dsh", BIN + "/tsc", BIN + "/tsserver").contains(path)
        && node.type.equals("LINK")
        && resolved(path, fs.readLink(fs.child(root, path))).startsWith(DSH + "/")) return;
    if (path.startsWith(MODULES + "/")) {
      String name = path.substring(MODULES.length() + 1);
      if (!name.equals("npm")
          && !name.equals("@deepseek-ai/dsh")
          && name.matches("(?:@[a-z0-9][a-z0-9._-]*/)?[a-z0-9][a-z0-9._-]*")
          && node.type.equals("LINK")
          && resolved(path, fs.readLink(fs.child(root, path))).startsWith(DSH + "/node_modules/"))
        return;
    }
    throw new IOException("COLD_SPLIT_RUNTIME_MEMBER:" + path);
  }

  private static String resolved(String name, String target) throws IOException {
    if (target.isEmpty() || target.indexOf('\\') >= 0 || target.indexOf('\0') >= 0)
      throw new IOException("COLD_SPLIT_LINK");
    List<String> parts = new ArrayList<>();
    if (!target.startsWith("/")) {
      String parent = name.substring(0, name.lastIndexOf('/'));
      Collections.addAll(parts, parent.split("/"));
    }
    for (String part : target.split("/")) {
      if (part.isEmpty() || part.equals(".")) continue;
      if (part.equals("..")) {
        if (parts.isEmpty()) throw new IOException("COLD_SPLIT_LINK_ESCAPE");
        parts.remove(parts.size() - 1);
      } else parts.add(part);
    }
    return String.join("/", parts);
  }

  private static void move(
      BackupFileSystem fs,
      File from,
      File to,
      BackupFileSystem.Node expected,
      BackupControl control)
      throws IOException {
    control.check();
    var before = fs.stat(from);
    if (!sameIdentity(expected, before)
        || expected.mode != before.mode
        || !fs.stat(to).type.equals("MISSING"))
      throw new IOException("COLD_SPLIT_MOVE_CHANGED:" + from);
    fs.move(from, to);
    var after = fs.stat(to);
    if (!sameIdentity(expected, after)
        || expected.mode != after.mode
        || !fs.stat(from).type.equals("MISSING"))
      throw new IOException("COLD_SPLIT_MOVE_CHANGED:" + to);
    fs.syncDirectory(from.getParentFile());
    fs.syncDirectory(to.getParentFile());
  }

  private static boolean sameIdentity(BackupFileSystem.Node a, BackupFileSystem.Node b) {
    return a != null && a.type.equals(b.type) && a.device == b.device && a.key.equals(b.key);
  }

  private static void requireIdentity(
      BackupFileSystem fs, File file, BackupFileSystem.Node expected) throws IOException {
    var actual = fs.stat(file);
    if (!expected.type.equals("DIRECTORY")
        || !sameIdentity(expected, actual)
        || expected.mode != actual.mode) throw new IOException("COLD_SPLIT_ROOT_CHANGED:" + file);
  }
}
