package com.deepseekharness.app.runtime;

import android.os.ParcelFileDescriptor;
import android.system.ErrnoException;
import android.system.Os;
import android.system.OsConstants;
import android.system.StructStat;
import com.deepseekharness.app.backup.BackupControl;
import com.deepseekharness.app.backup.BackupFileSystem;
import com.deepseekharness.app.backup.BackupLimits;
import java.io.Closeable;
import java.io.File;
import java.io.FileDescriptor;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** 签名资产的短期解压会话：固定根描述符、有界父目录缓存、成功返回前批量同步目录。 */
final class AndroidTrustedAssetFileSystem
    implements BackupFileSystem, TrustedAssetFileSystem, Closeable {
  private static final int O_PATH = 0x200000, O_CLOEXEC = 0x80000, CACHED_DIRECTORIES = 64;
  private static final OutputStream PREPARED_OUTPUT =
      new OutputStream() {
        @Override
        public void write(int value) throws IOException {
          throw new IOException("TAR_SMALL_FILE_ALREADY_WRITTEN");
        }

        @Override
        public void write(byte[] bytes, int offset, int count) throws IOException {
          throw new IOException("TAR_SMALL_FILE_ALREADY_WRITTEN");
        }
      };
  private final File root, granted;
  private final boolean exactMembers;
  private final Set<String> signedMembers = new LinkedHashSet<>();
  private final String rootPrefix;
  private final ParcelFileDescriptor rootHandle;
  private final Node rootIdentity;
  private final Directory rootDirectory;
  private final NativeStorage.DirectoryIdentities rootIdentities;
  private final Map<File, StructStat> anchors = new LinkedHashMap<>();
  private final Map<String, StructStat> directories = new LinkedHashMap<>();
  private final Map<String, Node> written = new LinkedHashMap<>();
  private final Map<String, String> writtenLinks = new LinkedHashMap<>();
  private final Set<String> dirtyDirectories = new LinkedHashSet<>();
  private final LinkedHashMap<String, Directory> cache = new LinkedHashMap<>(64, .75f, true);
  private final BackupControl control = new BackupControl(null);
  private final List<ParcelFileDescriptor> pendingSync = new ArrayList<>(128);
  private Directory activeParent;
  private String activeName, activePath;
  private File activeTarget, activeParentFile;

  @Override
  public Closeable fileEntry(String name) throws IOException {
    BackupLimits.path(name);
    if (activeParent != null) throw new IOException("TAR_ENTRY_REENTRY");
    String parentName = parent(name);
    Directory checked = directoryHandle(parentName, true, false);
    activeParent =
        new Directory(
            ParcelFileDescriptor.dup(checked.handle.getFileDescriptor()),
            checked.identity,
            checked.stat);
    activeName = parentName;
    activePath = name;
    activeTarget = new File(root, name);
    activeParentFile = activeTarget.getParentFile();
    return () -> {
      Directory held = activeParent;
      activeParent = null;
      activeName = null;
      activePath = null;
      activeTarget = null;
      activeParentFile = null;
      try {
        Directory current = directoryHandle(parentName, false, false);
        if (!identity(held.identity, current.identity))
          throw new IOException("TAR_PARENT_CHANGED:" + parentName);
      } finally {
        held.handle.close();
      }
    };
  }

  /** 仅由签名 tar 的成员回调登记；读取链接目标时观察到的额外目录不能获得成员身份。 */
  void signedMember(String name) {
    if (!exactMembers) throw new IllegalStateException("TAR_MEMBERS_NOT_SEALED");
    signedMembers.add(name);
  }

  private void queueSync(FileDescriptor descriptor) throws IOException {
    pendingSync.add(ParcelFileDescriptor.dup(descriptor));
    if (pendingSync.size() == 128) flushPending();
  }

  private void flushPending() throws IOException {
    if (pendingSync.isEmpty()) return;
    IOException failure = null;
    try {
      control.check();
      int[] descriptors = new int[pendingSync.size()];
      for (int i = 0; i < descriptors.length; i++) descriptors[i] = pendingSync.get(i).getFd();
      NativeStorage.flush(descriptors);
    } catch (IOException error) {
      failure = error;
    } finally {
      for (ParcelFileDescriptor handle : pendingSync)
        try {
          handle.close();
        } catch (IOException error) {
          if (failure == null) failure = error;
          else failure.addSuppressed(error);
        }
      pendingSync.clear();
    }
    if (failure != null) throw failure;
  }

  private static final class Directory {
    final ParcelFileDescriptor handle;
    final Node identity;
    final StructStat stat;
    final String fixedPath;

    Directory(ParcelFileDescriptor handle, Node identity, StructStat stat) {
      this.handle = handle;
      this.identity = identity;
      this.stat = stat;
      fixedPath = "/proc/self/fd/" + handle.getFd();
    }

    String path(String leaf) {
      return leaf.isEmpty() ? fixedPath : fixedPath + "/" + leaf;
    }
  }

  AndroidTrustedAssetFileSystem(File destination) throws IOException {
    this(destination, false);
  }

  AndroidTrustedAssetFileSystem(File destination, boolean exactMembers) throws IOException {
    this.exactMembers = exactMembers;
    granted = destination.getAbsoluteFile();
    if (!granted.isDirectory() && !granted.mkdirs())
      throw new IOException("TAR_ROOT_CREATE:" + granted);
    try {
      if (!OsConstants.S_ISDIR(Os.lstat(granted.getPath()).st_mode))
        throw new IOException("TAR_ROOT_LINK:" + granted);
      if (exactMembers) {
        String[] names = granted.list();
        if (names == null || names.length != 0) throw new IOException("TAR_SEALED_ROOT_NOT_EMPTY");
      }
      // 仅规范化调用者授予的应用目录别名；归档子路径绝不 canonicalize。
      root = granted.getCanonicalFile();
      rootPrefix = root.getPath() + "/";
      FileDescriptor fd = Os.open("/", O_PATH | O_CLOEXEC, 0);
      try {
        File at = new File("/");
        anchors.put(at, Os.fstat(fd));
        for (String part : root.getPath().substring(1).split("/")) {
          if (part.isEmpty() || part.equals(".") || part.equals(".."))
            throw new IOException("UNSAFE_HOST_PATH");
          FileDescriptor next = null;
          try {
            try (ParcelFileDescriptor handle = ParcelFileDescriptor.dup(fd)) {
              next =
                  Os.open(
                      "/proc/self/fd/" + handle.getFd() + "/" + part,
                      O_PATH | O_CLOEXEC | OsConstants.O_NOFOLLOW,
                      0);
            }
            StructStat n = Os.fstat(next);
            if (!OsConstants.S_ISDIR(n.st_mode)) throw new IOException("PARENT_LINK");
            Os.close(fd);
            fd = next;
            next = null;
            at = new File(at, part);
            anchors.put(at, n);
          } finally {
            if (next != null) Os.close(next);
          }
        }
        StructStat rootStat = Os.fstat(fd);
        rootIdentity = node(rootStat);
        rootHandle = ParcelFileDescriptor.dup(fd);
        rootDirectory = new Directory(rootHandle, rootIdentity, rootStat);
        String[] paths = new String[anchors.size()];
        long[] devices = new long[anchors.size()], inodes = new long[anchors.size()];
        int index = 0;
        for (var anchor : anchors.entrySet()) {
          paths[index] = anchor.getKey().getPath();
          devices[index] = anchor.getValue().st_dev;
          inodes[index++] = anchor.getValue().st_ino;
        }
        try {
          rootIdentities = new NativeStorage.DirectoryIdentities(paths, devices, inodes);
        } catch (IOException error) {
          rootHandle.close();
          throw error;
        }
      } finally {
        Os.close(fd);
      }
    } catch (ErrnoException error) {
      throw failure(error);
    }
  }

  private static Node node(StructStat stat) {
    String type =
        OsConstants.S_ISREG(stat.st_mode)
            ? "FILE"
            : OsConstants.S_ISDIR(stat.st_mode)
                ? "DIRECTORY"
                : OsConstants.S_ISLNK(stat.st_mode) ? "LINK" : "SPECIAL";
    return new Node(
        type,
        stat.st_dev + ":" + stat.st_ino,
        stat.st_size,
        stat.st_mtime,
        stat.st_dev,
        stat.st_mode & 0777);
  }

  private static boolean identity(Node a, Node b) {
    return a.type.equals(b.type) && a.device == b.device && a.key.equals(b.key);
  }

  private static boolean identity(StructStat expected, StructStat actual) {
    return actual != null
        && OsConstants.S_ISDIR(expected.st_mode)
        && OsConstants.S_ISDIR(actual.st_mode)
        && expected.st_dev == actual.st_dev
        && expected.st_ino == actual.st_ino;
  }

  private static IOException failure(ErrnoException error) {
    return new IOException(
        error.errno == OsConstants.EACCES || error.errno == OsConstants.EPERM
            ? "PERMISSION_DENIED"
            : error.errno == OsConstants.ENOSPC ? "NO_SPACE" : "FILESYSTEM_" + error.errno,
        error);
  }

  private static StructStat rawStat(String path) throws IOException {
    try {
      return Os.lstat(path);
    } catch (ErrnoException error) {
      if (error.errno == OsConstants.ENOENT) return null;
      throw failure(error);
    }
  }

  private static Node lstat(String path) throws IOException {
    StructStat stat = rawStat(path);
    return stat == null ? new Node("MISSING", "", 0, 0, 0, 0) : node(stat);
  }

  private Node verifyRoot() throws IOException {
    if (rootIdentities.verify() >= 0) throw new IOException("TAR_ROOT_CHANGED:" + root);
    return new Node(
        "DIRECTORY",
        rootIdentity.key,
        rootIdentities.rootSize(),
        rootIdentities.rootModified(),
        rootIdentity.device,
        rootIdentities.rootPermissions());
  }

  private String relative(File file) throws IOException {
    // 只复用当前成员已完整校验的精确名称；其他调用仍逐项执行原路径检查。
    if (activeParent != null) {
      if (file.equals(activeTarget)) return activePath;
      if (file.equals(activeParentFile)) return activeName;
    }
    String path = file.getAbsolutePath();
    if (path.equals(root.getPath()) || path.equals(granted.getPath())) return "";
    if (!path.startsWith(rootPrefix)) throw new IOException("TAR_HOST_PATH_OUTSIDE_ROOT");
    return BackupLimits.path(path.substring(rootPrefix.length()));
  }

  private static String parent(String path) {
    int slash = path.lastIndexOf('/');
    return slash < 0 ? "" : path.substring(0, slash);
  }

  private Directory directoryHandle(String relative, boolean create, boolean allowMissing)
      throws IOException {
    if (activeParent != null && relative.equals(activeName)) return activeParent;
    verifyRoot();
    Directory at = rootDirectory;
    if (relative.isEmpty()) return at;
    String prefix = "";
    for (String part : relative.split("/")) {
      String before = prefix;
      prefix = prefix.isEmpty() ? part : prefix + "/" + part;
      StructStat n = rawStat(at.path(part));
      if (n == null && create) {
        try {
          Os.mkdir(at.path(part), 0700);
        } catch (ErrnoException error) {
          throw failure(error);
        }
        dirtyDirectories.add(before);
        n = rawStat(at.path(part));
      }
      if (n == null && allowMissing) return null;
      if (n == null || !OsConstants.S_ISDIR(n.st_mode))
        throw new IOException("TAR_PARENT_LINK_OR_TYPE:" + prefix);
      StructStat known = directories.putIfAbsent(prefix, n);
      if (directories.size() > BackupLimits.ENTRIES) throw new IOException("TAR_DIRECTORY_LIMIT");
      if (known != null && !identity(known, n))
        throw new IOException("TAR_PARENT_CHANGED:" + prefix);
      Directory held = cache.get(prefix);
      if (held == null) {
        try {
          FileDescriptor fd =
              Os.open(at.path(part), O_PATH | O_CLOEXEC | OsConstants.O_NOFOLLOW, 0);
          try {
            StructStat opened = Os.fstat(fd);
            if (!identity(n, opened)) throw new IOException("TAR_PARENT_CHANGED:" + prefix);
            held = new Directory(ParcelFileDescriptor.dup(fd), node(opened), opened);
          } finally {
            Os.close(fd);
          }
        } catch (ErrnoException error) {
          throw failure(error);
        }
        cache.put(prefix, held);
        if (cache.size() > CACHED_DIRECTORIES) {
          var oldest = cache.entrySet().iterator();
          Directory evicted = oldest.next().getValue();
          oldest.remove();
          evicted.handle.close();
        }
      } else if (!identity(n, held.stat)) throw new IOException("TAR_PARENT_CHANGED:" + prefix);
      at = held;
    }
    return at;
  }

  private String path(File file) throws IOException {
    String name = relative(file);
    if (name.isEmpty()) throw new IOException("TAR_REFUSE_ROOT_WRITE");
    return directoryHandle(parent(name), false, false).path(file.getName());
  }

  private ParcelFileDescriptor open(File file, int flags, int mode) throws IOException {
    try {
      FileDescriptor raw = Os.open(path(file), flags | O_CLOEXEC | OsConstants.O_NOFOLLOW, mode);
      try {
        return ParcelFileDescriptor.dup(raw);
      } finally {
        Os.close(raw);
      }
    } catch (ErrnoException error) {
      throw failure(error);
    }
  }

  @Override
  public File target(String name, boolean createParents) throws IOException {
    if (activeParent != null && name.equals(activePath)) return activeTarget;
    BackupLimits.path(name);
    directoryHandle(parent(name), createParents, false);
    return new File(root, name);
  }

  @Override
  public Node stat(File file) throws IOException {
    String name = relative(file);
    if (name.isEmpty()) {
      if (activeParent == null) return verifyRoot();
      Node observed = lstat(root.getPath());
      if (!identity(rootIdentity, observed)) throw new IOException("TAR_ROOT_CHANGED:" + root);
      return observed;
    }
    Directory directory = directoryHandle(parent(name), false, true);
    return directory == null
        ? new Node("MISSING", "", 0, 0, 0, 0)
        : lstat(directory.path(file.getName()));
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
  public List<String> list(File file) throws IOException {
    Node expected = stat(file);
    if (!expected.type.equals("DIRECTORY")) throw new IOException("DIRECTORY_TYPE");
    Directory held = directoryHandle(relative(file), false, false);
    String[] names = new File(held.path("")).list();
    if (names == null) throw new IOException("DIRECTORY_UNREADABLE");
    if (names.length > BackupLimits.ENTRIES) throw new IOException("ENTRY_LIMIT");
    List<String> result = Arrays.asList(names);
    Collections.sort(result);
    if (!expected.same(stat(file))) throw new IOException("SOURCE_CHANGED");
    return result;
  }

  @Override
  public InputStream read(File file, Node expected) throws IOException {
    if (!expected.type.equals("FILE")) throw new IOException("FILE_TYPE");
    ParcelFileDescriptor handle = open(file, OsConstants.O_RDONLY, 0);
    FileDescriptor fd = handle.getFileDescriptor();
    InputStream input = new ParcelFileDescriptor.AutoCloseInputStream(handle);
    try {
      if (!expected.same(node(Os.fstat(fd)))) throw new IOException("SOURCE_CHANGED");
    } catch (IOException | ErrnoException error) {
      try {
        input.close();
      } catch (IOException closing) {
        error.addSuppressed(closing);
      }
      throw error instanceof ErrnoException errno ? failure(errno) : (IOException) error;
    }
    return new FilterInputStream(input) {
      boolean closed;

      @Override
      public void close() throws IOException {
        if (closed) return;
        closed = true;
        try {
          if (!expected.same(node(Os.fstat(fd))) || !expected.same(stat(file)))
            throw new IOException("SOURCE_CHANGED");
        } catch (ErrnoException error) {
          throw failure(error);
        } finally {
          super.close();
        }
      }
    };
  }

  @Override
  public OutputStream create(File file) throws IOException {
    return createWithMode(file, 0600);
  }

  @Override
  public boolean supportsSmallFiles() {
    return true;
  }

  @Override
  public CreatedFile prepareSmallFile(File file, byte[] bytes, int count, int mode)
      throws IOException {
    if (activeParent == null
        || !file.equals(activeTarget)
        || bytes == null
        || count < 0
        || count > SMALL_FILE_BYTES
        || count > bytes.length) throw new IOException("TAR_SMALL_FILE_SCOPE");
    String name = activePath, parentName = activeName;
    NativeStorage.SmallFile prepared =
        NativeStorage.prepareSmallFile(
            activeParent.handle.getFd(),
            activeParent.stat.st_dev,
            activeParent.stat.st_ino,
            file.getName(),
            bytes,
            count,
            mode);
    if (!prepared.identified) {
      try (prepared) {
        prepared.check();
        throw new IOException("TAR_CREATED_FILE_TYPE:" + name);
      }
    }
    Node owned =
        new Node(
            "FILE",
            prepared.device + ":" + prepared.inode,
            prepared.size,
            prepared.modified,
            prepared.device,
            prepared.permissions);
    // 已创建的短成员即使写入失败也先把真实出生身份交回边界，close 再抛出失败。
    return new CreatedFile(PREPARED_OUTPUT, owned) {
      boolean closed;

      @Override
      public void close() throws IOException {
        if (closed) return;
        closed = true;
        try (prepared) {
          control.check();
          prepared.check();
          queueSync(prepared.handle.getFileDescriptor());
          // 必须在原有 128 FD 组的同步入队之后，才作完整 fd/名称末次核验。
          long[] finished = prepared.finish();
          Node complete =
              new Node(
                  "FILE",
                  finished[0] + ":" + finished[1],
                  finished[2],
                  finished[3],
                  finished[0],
                  (int) finished[4]);
          written.put(name, complete);
          dirtyDirectories.add(parentName);
        }
      }
    };
  }

  @Override
  public CreatedFile createWithMode(File file, int mode) throws IOException {
    ParcelFileDescriptor handle =
        open(file, OsConstants.O_WRONLY | OsConstants.O_CREAT | OsConstants.O_EXCL, 0600);
    FileDescriptor fd = handle.getFileDescriptor();
    OutputStream output = new ParcelFileDescriptor.AutoCloseOutputStream(handle);
    final Node opened;
    try {
      opened = node(Os.fstat(fd));
      if (!opened.type.equals("FILE")) throw new IOException("FILE_TYPE");
    } catch (IOException | ErrnoException error) {
      try {
        output.close();
      } catch (IOException closing) {
        error.addSuppressed(closing);
      }
      throw error instanceof ErrnoException errno ? failure(errno) : (IOException) error;
    }
    return new CreatedFile(output, opened) {
      boolean closed;

      @Override
      public void close() throws IOException {
        if (closed) return;
        closed = true;
        try {
          flush();
          if (!identity(opened, node(Os.fstat(fd)))) throw new IOException("SOURCE_CHANGED");
          Os.fchmod(fd, mode & 0777);
          queueSync(fd);
          Node finished = node(Os.fstat(fd));
          if (!finished.same(stat(file))) throw new IOException("TAR_TARGET_CHANGED:" + file);
          written.put(relative(file), finished);
          dirtyDirectories.add(parent(relative(file)));
        } catch (ErrnoException error) {
          throw failure(error);
        } finally {
          super.close();
        }
      }
    };
  }

  @Override
  public void directory(File file) throws IOException {
    try {
      Os.mkdir(path(file), 0700);
      String name = relative(file);
      directoryHandle(name, false, false);
      dirtyDirectories.add(parent(name));
    } catch (ErrnoException error) {
      throw failure(error);
    }
  }

  @Override
  public void move(File from, File to) throws IOException {
    String source = relative(from), target = relative(to);
    Directory a = directoryHandle(parent(source), false, false);
    // 临时 dup 固定两侧父目录，LRU 淘汰不能关闭正在执行的 rename 身份。
    try (ParcelFileDescriptor held = ParcelFileDescriptor.dup(a.handle.getFileDescriptor())) {
      String sourcePath = "/proc/self/fd/" + held.getFd() + "/" + from.getName();
      Directory b = directoryHandle(parent(target), false, false);
      String targetPath = b.path(to.getName());
      Node sourceNode = lstat(sourcePath);
      if (sourceNode.type.equals("MISSING")
          || sourceNode.device != b.identity.device
          || !lstat(targetPath).type.equals("MISSING")) throw new IOException("UNSAFE_MOVE");
      Os.rename(sourcePath, targetPath);
      Node expected = written.remove(source);
      if (expected != null) written.put(target, expected);
      dirtyDirectories.add(parent(source));
      dirtyDirectories.add(parent(target));
    } catch (ErrnoException error) {
      throw failure(error);
    }
  }

  @Override
  public void delete(File file) throws IOException {
    try {
      Os.remove(path(file));
      written.remove(relative(file));
      writtenLinks.remove(relative(file));
      dirtyDirectories.add(parent(relative(file)));
    } catch (ErrnoException error) {
      throw failure(error);
    }
  }

  @Override
  public void mode(File file, int mode) throws IOException {
    Node expected = stat(file);
    if (!expected.type.equals("FILE") && !expected.type.equals("DIRECTORY"))
      throw new IOException("MODE_TYPE");
    try (ParcelFileDescriptor handle =
        open(file, OsConstants.O_RDONLY | OsConstants.O_NONBLOCK, 0)) {
      FileDescriptor fd = handle.getFileDescriptor();
      if (!expected.same(node(Os.fstat(fd)))) throw new IOException("SOURCE_CHANGED");
      Os.fchmod(fd, mode & 0777);
      queueSync(fd);
      if (!node(Os.fstat(fd)).same(stat(file))) throw new IOException("SOURCE_CHANGED");
    } catch (ErrnoException error) {
      throw failure(error);
    }
  }

  @Override
  public void symlink(String target, File file) throws IOException {
    try {
      String path = path(file), name = relative(file);
      Os.symlink(target, path);
      Node created = lstat(path);
      if (!created.type.equals("LINK") || !target.equals(Os.readlink(path)))
        throw new IOException("TAR_LINK_CHANGED:" + name);
      written.put(name, created);
      writtenLinks.put(name, target);
      dirtyDirectories.add(parent(relative(file)));
    } catch (ErrnoException error) {
      throw failure(error);
    }
  }

  @Override
  public void syncDirectory(File file) throws IOException {
    String name = relative(file);
    directoryHandle(name, false, false);
    dirtyDirectories.add(name);
  }

  @Override
  public void finishFiles() throws IOException {
    // 不能仅以缓存命中宣称完整。检查每个实际父目录与已写文件，之后才收敛目录同步。
    SignedAssetMembers members = null;
    if (exactMembers) {
      members = new SignedAssetMembers(signedMembers);
      verifyMembers(rootDirectory, "", members);
    }
    for (var entry : directories.entrySet()) {
      control.check();
      if (members == null) {
        if (!identity(node(entry.getValue()), stat(new File(root, entry.getKey()))))
          throw new IOException("TAR_PARENT_CHANGED:" + entry.getKey());
      } else {
        Directory held = directoryHandle(entry.getKey(), false, false);
        if (!identity(entry.getValue(), held.stat))
          throw new IOException("TAR_PARENT_CHANGED:" + entry.getKey());
        verifyMembers(held, entry.getKey(), members);
      }
    }
    Map<String, List<String>> byParent = new LinkedHashMap<>();
    for (String name : written.keySet())
      byParent.computeIfAbsent(parent(name), ignored -> new ArrayList<>()).add(name);
    for (var group : byParent.entrySet()) {
      control.check();
      Directory held = directoryHandle(group.getKey(), false, false);
      try (ParcelFileDescriptor fixed = ParcelFileDescriptor.dup(held.handle.getFileDescriptor())) {
        for (String name : group.getValue()) {
          control.check();
          String path = "/proc/self/fd/" + fixed.getFd() + "/" + new File(name).getName();
          if (!written.get(name).same(lstat(path)))
            throw new IOException("TAR_TARGET_CHANGED:" + name);
          String link = writtenLinks.get(name);
          if (link != null)
            try {
              if (!link.equals(Os.readlink(path)))
                throw new IOException("TAR_LINK_CHANGED:" + name);
            } catch (ErrnoException error) {
              throw failure(error);
            }
        }
        if (!identity(held.identity, directoryHandle(group.getKey(), false, false).identity))
          throw new IOException("TAR_PARENT_CHANGED:" + group.getKey());
      }
    }
    for (String name : new ArrayList<>(dirtyDirectories)) {
      control.check();
      Directory held = directoryHandle(name, false, false);
      try {
        FileDescriptor fd = Os.open(held.path(""), OsConstants.O_RDONLY | O_CLOEXEC, 0);
        try {
          if (!identity(held.identity, node(Os.fstat(fd))))
            throw new IOException("TAR_PARENT_CHANGED:" + name);
          queueSync(fd);
        } finally {
          Os.close(fd);
        }
      } catch (ErrnoException error) {
        throw failure(error);
      }
    }
    dirtyDirectories.clear();
    flushPending();
    verifyRoot();
  }

  private void verifyMembers(Directory held, String name, SignedAssetMembers members)
      throws IOException {
    control.check();
    try {
      Node before = node(Os.fstat(held.handle.getFileDescriptor()));
      if (!identity(held.identity, before)) throw new IOException("TAR_PARENT_CHANGED:" + name);
      String[] actual = new File(held.path("")).list();
      if (actual == null) throw new IOException("DIRECTORY_UNREADABLE");
      if (actual.length > BackupLimits.ENTRIES) throw new IOException("ENTRY_LIMIT");
      members.verify(name, Arrays.asList(actual));
      if (!before.same(node(Os.fstat(held.handle.getFileDescriptor()))))
        throw new IOException("TAR_PARENT_CHANGED:" + name);
    } catch (ErrnoException error) {
      throw failure(error);
    }
  }

  @Override
  public void finishDirectoryModes() throws IOException {
    flushPending();
    verifyRoot();
  }

  @Override
  public void close() throws IOException {
    IOException failure = null;
    // 失败/取消不补发同步成功；只关闭尚未提交的短期描述符。
    for (ParcelFileDescriptor handle : pendingSync)
      try {
        handle.close();
      } catch (IOException error) {
        if (failure == null) failure = error;
        else failure.addSuppressed(error);
      }
    pendingSync.clear();
    if (activeParent != null) {
      try {
        activeParent.handle.close();
      } catch (IOException error) {
        if (failure == null) failure = error;
        else failure.addSuppressed(error);
      }
      activeParent = null;
      activeName = null;
      activePath = null;
      activeTarget = null;
      activeParentFile = null;
    }
    for (Directory directory : cache.values())
      try {
        directory.handle.close();
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
