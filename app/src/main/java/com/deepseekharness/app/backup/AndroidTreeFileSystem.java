package com.deepseekharness.app.backup;

import android.os.ParcelFileDescriptor;
import android.system.ErrnoException;
import android.system.Os;
import android.system.OsConstants;
import android.system.StructStat;
import com.deepseekharness.app.runtime.NativeStorage;
import java.io.Closeable;
import java.io.File;
import java.io.IOException;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;

/** 私有树遍历的一次性目录会话：逐层 NOFOLLOW 核验，复用有界目录 FD，不重复从 / 打开。 */
final class AndroidTreeFileSystem extends AndroidBackupFileSystem implements Closeable {
  private static final int O_PATH = 0x200000, O_CLOEXEC = 0x80000;
  private final File root;
  private final ParcelFileDescriptor rootHandle;
  private final Node rootIdentity;
  private final LinkedHashMap<String, Held> cache = new LinkedHashMap<>(64, .75f, true);

  private static final class Held {
    final ParcelFileDescriptor handle;
    final Node identity;
    final String fdPath;
    final long inode;

    Held(ParcelFileDescriptor handle, Node identity) {
      this.handle = handle;
      this.identity = identity;
      fdPath = "/proc/self/fd/" + handle.getFd();
      inode = Long.parseLong(identity.key.substring(identity.key.indexOf(':') + 1));
    }

    String path(String name) {
      return name.isEmpty() ? fdPath : fdPath + "/" + name;
    }
  }

  interface LeafReadPort {
    void stat(
        int parent,
        long device,
        long inode,
        String[] names,
        int count,
        long[] output,
        NativeStorage.ReadControl control)
        throws IOException;

    int read(
        int parent,
        long device,
        long inode,
        String[] names,
        int count,
        long[] expected,
        byte[] payload,
        int[] offsets,
        NativeStorage.ReadControl control)
        throws IOException;
  }

  private static final LeafReadPort NATIVE_LEAVES =
      new LeafReadPort() {
        public void stat(
            int parent,
            long device,
            long inode,
            String[] names,
            int count,
            long[] output,
            NativeStorage.ReadControl control)
            throws IOException {
          NativeStorage.statChildrenAt(parent, device, inode, names, count, output, control);
        }

        public int read(
            int parent,
            long device,
            long inode,
            String[] names,
            int count,
            long[] expected,
            byte[] payload,
            int[] offsets,
            NativeStorage.ReadControl control)
            throws IOException {
          return NativeStorage.readSmallAt(
              parent, device, inode, names, count, expected, payload, offsets, control);
        }
      };

  /** 本遍、同一父 FD 下的当前 tuple；只供本次分派与读取，不能作为跨遍摘要缓存。 */
  static final class LeafBatch {
    static final int MEMBERS = 32, SMALL_BYTES = 32768, PAYLOAD_BYTES = 1048576, STRIDE = 6;
    private final LeafReadPort io;
    private final int parent;
    private final long device, inode;
    private final String[] names;
    private final long[] tuples;
    private final Node[] nodes;

    LeafBatch(
        LeafReadPort io,
        int parent,
        long device,
        long inode,
        String[] names,
        int count,
        BackupControl control)
        throws IOException {
      if (io == null || count < 1 || count > MEMBERS || names == null || names.length < count)
        throw new IOException("TREE_LEAF_BATCH_ARGUMENT");
      this.io = io;
      this.parent = parent;
      this.device = device;
      this.inode = inode;
      this.names = Arrays.copyOf(names, count);
      tuples = new long[count * STRIDE];
      nodes = new Node[count];
      for (String name : this.names) {
        BackupLimits.path(name);
        if (name.isEmpty() || name.contains("/")) throw new IOException("INVALID_CHILD");
      }
      control.check();
      io.stat(parent, device, inode, this.names, count, tuples, control::check);
      control.check();
      for (int entry = 0; entry < count; entry++) nodes[entry] = observed(tuples, entry);
    }

    private static Node observed(long[] tuple, int entry) throws IOException {
      int at = entry * STRIDE;
      long kind = tuple[at + 5], size = tuple[at + 2], permissions = tuple[at + 4];
      if (kind == 0) {
        for (int field = 0; field < STRIDE; field++)
          if (tuple[at + field] != 0) throw new IOException("TREE_LEAF_STAT_INVALID");
        return new Node("MISSING", "", 0, 0, 0, 0);
      }
      if (kind < 1 || kind > 4 || size < 0 || permissions < 0 || permissions > 0777)
        throw new IOException("TREE_LEAF_STAT_INVALID");
      String type = kind == 1 ? "FILE" : kind == 2 ? "DIRECTORY" : kind == 3 ? "LINK" : "SPECIAL";
      return new Node(
          type, tuple[at] + ":" + tuple[at + 1], size, tuple[at + 3], tuple[at], (int) permissions);
    }

    int size() {
      return names.length;
    }

    String name(int entry) {
      return names[entry];
    }

    Node node(int entry) {
      return nodes[entry];
    }

    boolean small(int entry) {
      return nodes[entry].type.equals("FILE") && nodes[entry].size <= SMALL_BYTES;
    }

    byte[][] digestSmall(
        int first, int count, byte[] payload, MessageDigest hash, BackupControl control)
        throws IOException {
      if (first < 0
          || count < 1
          || first > names.length - count
          || count > MEMBERS
          || payload == null
          || payload.length > PAYLOAD_BYTES) throw new IOException("TREE_LEAF_BATCH_ARGUMENT");
      long declared = 0;
      for (int entry = first; entry < first + count; entry++) {
        if (!small(entry)) throw new IOException("TREE_LEAF_BATCH_ARGUMENT");
        declared = BackupLimits.add(declared, nodes[entry].size, payload.length);
      }
      String[] selected = Arrays.copyOfRange(names, first, first + count);
      long[] expected = Arrays.copyOfRange(tuples, first * STRIDE, (first + count) * STRIDE);
      int[] offsets = new int[count + 1];
      control.check();
      int used =
          io.read(
              parent, device, inode, selected, count, expected, payload, offsets, control::check);
      control.check();
      if (used != declared || offsets[0] != 0 || offsets[count] != used)
        throw new IOException("TREE_LEAF_BYTES_INVALID");
      byte[][] result = new byte[count][];
      for (int entry = 0; entry < count; entry++) {
        int offset = offsets[entry], end = offsets[entry + 1];
        if (offset < 0 || end < offset || end > used || end - offset != nodes[first + entry].size)
          throw new IOException("TREE_LEAF_BYTES_INVALID");
        control.check();
        hash.update(payload, offset, end - offset);
        result[entry] = hash.digest();
      }
      return result;
    }
  }

  AndroidTreeFileSystem(File root) throws IOException {
    this.root = root.getAbsoluteFile();
    try {
      rootHandle = owned(this.root, O_PATH | O_CLOEXEC | OsConstants.O_NOFOLLOW, 0);
      Node opened;
      try {
        opened = node(Os.fstat(rootHandle.getFileDescriptor()));
        if (!opened.type.equals("DIRECTORY") || !sameIdentity(opened, super.stat(this.root)))
          throw new IOException("FORMAT_ROOT_CHANGED");
      } catch (IOException | ErrnoException error) {
        rootHandle.close();
        throw error;
      }
      rootIdentity = opened;
    } catch (ErrnoException error) {
      throw failure(error);
    }
  }

  private static boolean sameIdentity(Node a, Node b) {
    return a.type.equals(b.type) && a.device == b.device && a.key.equals(b.key);
  }

  private String relative(File file) throws IOException {
    String path = file.getAbsolutePath();
    if (path.equals(root.getPath())) return "";
    if (!path.startsWith(root.getPath() + "/")) throw new IOException("FORMAT_OUTSIDE_ROOT");
    return BackupLimits.path(path.substring(root.getPath().length() + 1));
  }

  private Held directory(String name) throws IOException, ErrnoException {
    if (!sameIdentity(rootIdentity, super.stat(root))) throw new IOException("FORMAT_ROOT_CHANGED");
    Held at = new Held(rootHandle, rootIdentity);
    if (name.isEmpty()) return at;
    String prefix = "";
    for (String part : name.split("/")) {
      prefix = prefix.isEmpty() ? part : prefix + "/" + part;
      Node current = node(Os.lstat(at.path(part)));
      if (!current.type.equals("DIRECTORY")) throw new IOException("PARENT_LINK");
      Held next = cache.get(prefix);
      if (next == null) {
        java.io.FileDescriptor fd =
            Os.open(at.path(part), O_PATH | O_CLOEXEC | OsConstants.O_NOFOLLOW, 0);
        try {
          Node opened = node(Os.fstat(fd));
          if (!sameIdentity(current, opened)) throw new IOException("FORMAT_SOURCE_CHANGED");
          next = new Held(ParcelFileDescriptor.dup(fd), opened);
        } finally {
          Os.close(fd);
        }
        cache.put(prefix, next);
        if (cache.size() > 64) {
          var oldest = cache.entrySet().iterator();
          Held evicted = oldest.next().getValue();
          oldest.remove();
          evicted.handle.close();
        }
      } else if (!sameIdentity(current, next.identity)) {
        throw new IOException("FORMAT_SOURCE_CHANGED");
      }
      at = next;
    }
    return at;
  }

  private String path(File file) throws IOException, ErrnoException {
    String name = relative(file);
    if (name.isEmpty()) throw new IOException("REFUSE_ROOT_DELETE");
    int slash = name.lastIndexOf('/');
    return directory(slash < 0 ? "" : name.substring(0, slash)).path(file.getName());
  }

  /**
   * 只读摘要按 DFS 持有当前目录及父目录，不为每个叶子重新核对全部祖先。 每层进入和退出均核对 FD 与父目录内的目录项；根额外核对宿主路径。 FD 数量由摘要路径的 DEPTH
   * 限额约束，退出立即关闭，不占用删除会话的缓存。
   */
  final class DigestDirectory implements Closeable {
    private final Held held;
    private final DigestDirectory parent;
    private final String name;
    private boolean closed;

    private DigestDirectory(Held held, DigestDirectory parent, String name) {
      this.held = held;
      this.parent = parent;
      this.name = name;
    }

    private String childPath(String name) throws IOException {
      if (closed) throw new IOException("TREE_SCOPE_CLOSED");
      BackupLimits.path(name);
      if (name.isEmpty() || name.contains("/")) throw new IOException("INVALID_CHILD");
      return held.path(name);
    }

    Node stat(String name) throws IOException {
      try {
        return node(Os.lstat(childPath(name)));
      } catch (ErrnoException error) {
        if (error.errno == OsConstants.ENOENT) return new Node("MISSING", "", 0, 0, 0, 0);
        throw failure(error);
      }
    }

    String readLink(String name) throws IOException {
      try {
        return Os.readlink(childPath(name));
      } catch (ErrnoException error) {
        throw failure(error);
      }
    }

    LeafBatch batch(List<String> children, int first, BackupControl control) throws IOException {
      if (closed) throw new IOException("TREE_SCOPE_CLOSED");
      int count = Math.min(LeafBatch.MEMBERS, children.size() - first);
      if (first < 0 || count < 1) throw new IOException("TREE_LEAF_BATCH_ARGUMENT");
      String[] names = new String[count];
      for (int entry = 0; entry < count; entry++) names[entry] = children.get(first + entry);
      return new LeafBatch(
          NATIVE_LEAVES,
          held.handle.getFd(),
          held.identity.device,
          held.inode,
          names,
          count,
          control);
    }

    /** 摘要直接持有、读取和关闭本次 raw FD；不再为每个文件 dup 与建立自动关闭流。 */
    byte[] digestFile(
        String name, Node expected, MessageDigest digest, byte[] buffer, BackupControl control)
        throws IOException {
      if (!expected.type.equals("FILE")) throw new IOException("FILE_TYPE");
      String path = childPath(name);
      try {
        java.io.FileDescriptor raw =
            Os.open(
                path,
                OsConstants.O_RDONLY | O_CLOEXEC | OsConstants.O_NOFOLLOW | OsConstants.O_NONBLOCK,
                0);
        try {
          if (!sameFile(expected, Os.fstat(raw))) throw new IOException("SOURCE_CHANGED");
          long total = 0;
          int count;
          while ((count = Os.read(raw, buffer, 0, buffer.length)) != 0) {
            control.check();
            total = BackupLimits.add(total, count, BackupLimits.BYTES + 256L * 1024 * 1024);
            digest.update(buffer, 0, count);
          }
          control.check();
          if (total != expected.size
              || !sameFile(expected, Os.fstat(raw))
              || !sameFile(expected, Os.lstat(path))) throw new IOException("SOURCE_CHANGED");
          return digest.digest();
        } finally {
          Os.close(raw);
        }
      } catch (ErrnoException error) {
        throw failure(error);
      }
    }

    DigestDirectory directory(String name, Node expected) throws IOException {
      if (!expected.type.equals("DIRECTORY")) throw new IOException("DIRECTORY_TYPE");
      try {
        java.io.FileDescriptor raw =
            Os.open(childPath(name), O_PATH | O_CLOEXEC | OsConstants.O_NOFOLLOW, 0);
        ParcelFileDescriptor handle;
        try {
          if (!expected.same(node(Os.fstat(raw)))) throw new IOException("SOURCE_CHANGED");
          handle = ParcelFileDescriptor.dup(raw);
        } finally {
          Os.close(raw);
        }
        DigestDirectory result = new DigestDirectory(new Held(handle, expected), this, name);
        try {
          result.verify();
          return result;
        } catch (IOException error) {
          try {
            handle.close();
          } catch (IOException closing) {
            error.addSuppressed(closing);
          }
          throw error;
        }
      } catch (ErrnoException error) {
        throw failure(error);
      }
    }

    List<String> list() throws IOException {
      verify();
      String[] names = new File(held.path("")).list();
      if (names == null) throw new IOException("DIRECTORY_UNREADABLE");
      if (names.length > BackupLimits.ENTRIES) throw new IOException("ENTRY_LIMIT");
      verify();
      List<String> result = Arrays.asList(names);
      Collections.sort(result);
      return result;
    }

    private void verify() throws IOException {
      if (closed) throw new IOException("TREE_SCOPE_CLOSED");
      try {
        if (!held.identity.same(node(Os.fstat(held.handle.getFileDescriptor())))
            || !held.identity.same(parent == null ? rootStat() : parent.stat(name)))
          throw new IOException("SOURCE_CHANGED");
      } catch (ErrnoException error) {
        throw failure(error);
      }
    }

    @Override
    public void close() throws IOException {
      if (closed) return;
      try {
        verify();
      } finally {
        closed = true;
        if (parent != null) held.handle.close();
      }
    }
  }

  private static boolean sameFile(Node expected, StructStat actual) {
    return OsConstants.S_ISREG(actual.st_mode)
        && expected.device == actual.st_dev
        && expected.key.equals(actual.st_dev + ":" + actual.st_ino)
        && expected.size == actual.st_size
        && expected.modified == actual.st_mtime
        && expected.mode == (actual.st_mode & 0777);
  }

  private Node rootStat() throws IOException {
    return super.stat(root);
  }

  DigestDirectory digestRoot(Node expected) throws IOException {
    if (!expected.type.equals("DIRECTORY") || !sameIdentity(rootIdentity, expected))
      throw new IOException("FORMAT_ROOT_CHANGED");
    DigestDirectory result = new DigestDirectory(new Held(rootHandle, expected), null, "");
    result.verify();
    return result;
  }

  @Override
  public Node stat(File file) throws IOException {
    // 元数据观察与原宿主边界一致，单次 lstat；实际打开、列举和删除仍走固定 FD 的逐层 NOFOLLOW。
    relative(file);
    return super.stat(file);
  }

  @Override
  public String readLink(File file) throws IOException {
    try {
      return Os.readlink(path(file));
    } catch (ErrnoException error) {
      throw failure(error);
    }
  }

  @Override
  public java.io.InputStream read(File file, Node expected) throws IOException {
    try {
      return readAt(path(file), expected, () -> stat(file));
    } catch (ErrnoException error) {
      throw failure(error);
    }
  }

  private interface ReadStat {
    Node stat() throws IOException;
  }

  private java.io.InputStream readAt(String path, Node expected, ReadStat after)
      throws IOException {
    if (!expected.type.equals("FILE")) throw new IOException("FILE_TYPE");
    try {
      java.io.FileDescriptor raw =
          Os.open(path, OsConstants.O_RDONLY | O_CLOEXEC | OsConstants.O_NOFOLLOW, 0);
      ParcelFileDescriptor handle;
      try {
        if (!expected.same(node(Os.fstat(raw)))) throw new IOException("SOURCE_CHANGED");
        handle = ParcelFileDescriptor.dup(raw);
      } finally {
        Os.close(raw);
      }
      java.io.FileDescriptor fd = handle.getFileDescriptor();
      return new java.io.FilterInputStream(new ParcelFileDescriptor.AutoCloseInputStream(handle)) {
        boolean closed;

        @Override
        public void close() throws IOException {
          if (closed) return;
          closed = true;
          try {
            if (!expected.same(node(Os.fstat(fd))) || !expected.same(after.stat()))
              throw new IOException("SOURCE_CHANGED");
          } catch (ErrnoException error) {
            throw failure(error);
          } finally {
            super.close();
          }
        }
      };
    } catch (ErrnoException error) {
      throw failure(error);
    }
  }

  @Override
  public List<String> list(File file) throws IOException {
    try {
      Held held = directory(relative(file));
      Node before = node(Os.fstat(held.handle.getFileDescriptor()));
      String[] names = new File(held.path("")).list();
      if (names == null) throw new IOException("DIRECTORY_UNREADABLE");
      if (names.length > BackupLimits.ENTRIES) throw new IOException("ENTRY_LIMIT");
      if (!before.same(node(Os.fstat(held.handle.getFileDescriptor()))) || !before.same(stat(file)))
        throw new IOException("SOURCE_CHANGED");
      List<String> result = Arrays.asList(names);
      Collections.sort(result);
      return result;
    } catch (ErrnoException error) {
      throw failure(error);
    }
  }

  @Override
  public void prepareOwnedRemoval(File file) throws IOException {
    try {
      Held held = directory(relative(file));
      var stat = Os.fstat(held.handle.getFileDescriptor());
      if (stat.st_uid != android.os.Process.myUid())
        throw new IOException("RETIRED_DIRECTORY_NOT_OWNED");
      if ((stat.st_mode & 0700) != 0700) Os.chmod(held.path(""), (stat.st_mode & 0777) | 0700);
    } catch (ErrnoException error) {
      throw failure(error);
    }
  }

  @Override
  public void delete(File file) throws IOException {
    try {
      String name = relative(file);
      Os.remove(path(file));
      Held removed = cache.remove(name);
      if (removed != null) removed.handle.close();
    } catch (ErrnoException error) {
      throw failure(error);
    }
  }

  @Override
  public void syncDirectory(File file) throws IOException {
    try {
      Held held = directory(relative(file));
      java.io.FileDescriptor fd = Os.open(held.path(""), OsConstants.O_RDONLY | O_CLOEXEC, 0);
      try {
        if (!sameIdentity(held.identity, node(Os.fstat(fd))))
          throw new IOException("FORMAT_SOURCE_CHANGED");
        Os.fsync(fd);
      } finally {
        Os.close(fd);
      }
    } catch (ErrnoException error) {
      throw failure(error);
    }
  }

  @Override
  public void close() throws IOException {
    IOException failure = null;
    for (Held held : cache.values())
      try {
        held.handle.close();
      } catch (IOException error) {
        if (failure == null) failure = error;
        else failure.addSuppressed(error);
      }
    cache.clear();
    try {
      rootHandle.close();
    } catch (IOException error) {
      if (failure == null) failure = error;
      else failure.addSuppressed(error);
    }
    if (failure != null) throw failure;
  }
}
