package com.deepseekharness.app.runtime;

import android.os.ParcelFileDescriptor;
import java.io.ByteArrayOutputStream;
import java.io.Closeable;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;

/** 本次解压的有界写回与只读身份检查；不持有跨调用的 native 句柄。 */
public final class NativeStorage {
  private static final int MAX_DIRECTORY_IDENTITIES = 128, MAX_PATH_BYTES = 4096;
  private static final int MAX_SMALL_FILE_BYTES = 32768, MAX_LEAF_BYTES = 255;
  public static final int TREE_BATCH_COUNT = 32, TREE_STAT_FIELDS = 6;
  public static final int TREE_SMALL_FILE_BYTES = 32768, TREE_PAYLOAD_BYTES = 1048576;

  static {
    System.loadLibrary("termux");
  }

  private NativeStorage() {}

  private static native int flushFiles(int[] descriptors);

  private static native int verifyDirectoryIdentities(
      byte[] paths, long[] identities, long[] rootStat);

  private static native int prepareSmallFileBytes(
      int parent,
      long parentDevice,
      long parentInode,
      byte[] leaf,
      byte[] bytes,
      int count,
      int mode,
      long[] state);

  private static native int finishSmallFileBytes(
      int parent,
      long parentDevice,
      long parentInode,
      byte[] leaf,
      int descriptor,
      long device,
      long inode,
      long[] stat);

  private static native int closeSmallFileDescriptor(int descriptor);

  private static native int statChildrenBatchBytes(
      int parent,
      long parentDevice,
      long parentInode,
      byte[] names,
      int count,
      long[] statOut,
      Runnable cancellation);

  private static native int readSmallBatchBytes(
      int parent,
      long parentDevice,
      long parentInode,
      byte[] names,
      int count,
      long[] expected,
      byte[] payload,
      int[] offsets,
      Runnable cancellation);

  /** 每个实际成员、读取结果及 EOF 保留原 Java 取消检查，native 不接管控制状态。 */
  @FunctionalInterface
  public interface ReadControl {
    void check() throws IOException;
  }

  private static final class ReadFailure extends RuntimeException {
    final IOException source;

    ReadFailure(IOException source) {
      super(source);
      this.source = source;
    }
  }

  private static Runnable readCancellation(ReadControl control) {
    return () -> {
      try {
        control.check();
      } catch (IOException error) {
        throw new ReadFailure(error);
      }
    };
  }

  private static byte[] treeNames(String[] names, int count) throws IOException {
    if (names == null || count <= 0 || count > TREE_BATCH_COUNT || names.length < count)
      throw new IOException("TREE_READ_ARGUMENT");
    ByteArrayOutputStream encoded = new ByteArrayOutputStream(count * 32);
    for (int i = 0; i < count; i++) {
      String name = names[i];
      if (name == null
          || name.isEmpty()
          || name.equals(".")
          || name.equals("..")
          || name.indexOf('/') >= 0
          || name.indexOf('\\') >= 0
          || name.indexOf('\0') >= 0
          || name.indexOf('\n') >= 0
          || name.indexOf('\r') >= 0
          || (name.length() >= 2
              && name.charAt(1) == ':'
              && ((name.charAt(0) >= 'A' && name.charAt(0) <= 'Z')
                  || (name.charAt(0) >= 'a' && name.charAt(0) <= 'z'))))
        throw new IOException("TREE_READ_ARGUMENT");
      try {
        ByteBuffer bytes =
            StandardCharsets.UTF_8
                .newEncoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .encode(CharBuffer.wrap(name));
        if (bytes.remaining() > MAX_LEAF_BYTES) throw new IOException("TREE_READ_ARGUMENT");
        while (bytes.hasRemaining()) encoded.write(bytes.get());
        encoded.write(0);
      } catch (CharacterCodingException error) {
        throw new IOException("TREE_READ_ARGUMENT", error);
      }
    }
    return encoded.toByteArray();
  }

  private static IOException treeFailure(int result) {
    return result > 0 ? new IOException("SOURCE_CHANGED") : fileFailure(result);
  }

  /** 当前固定父目录的一次实际 stat；tuple 为 dev/ino/size/mtime秒/权限/kind。 */
  public static void statChildrenAt(
      int parent,
      long parentDevice,
      long parentInode,
      String[] names,
      int count,
      long[] statOut,
      ReadControl control)
      throws IOException {
    if (parent < 0
        || control == null
        || statOut == null
        || count <= 0
        || count > TREE_BATCH_COUNT
        || statOut.length < count * TREE_STAT_FIELDS
        || statOut.length > TREE_BATCH_COUNT * TREE_STAT_FIELDS)
      throw new IOException("TREE_READ_ARGUMENT");
    control.check();
    byte[] encoded = treeNames(names, count);
    try {
      int result =
          statChildrenBatchBytes(
              parent,
              parentDevice,
              parentInode,
              encoded,
              count,
              statOut,
              readCancellation(control));
      if (result != 0) throw treeFailure(result);
    } catch (ReadFailure failure) {
      throw failure.source;
    }
    control.check();
  }

  /** 全部成员成功核验后才回填实际 payload；SHA 仍由调用者逐文件计算。 */
  public static int readSmallAt(
      int parent,
      long parentDevice,
      long parentInode,
      String[] names,
      int count,
      long[] expected,
      byte[] payload,
      int[] offsets,
      ReadControl control)
      throws IOException {
    if (parent < 0
        || control == null
        || expected == null
        || payload == null
        || offsets == null
        || count <= 0
        || count > TREE_BATCH_COUNT
        || expected.length < count * TREE_STAT_FIELDS
        || expected.length > TREE_BATCH_COUNT * TREE_STAT_FIELDS
        || payload.length > TREE_PAYLOAD_BYTES
        || offsets.length < count + 1
        || offsets.length > TREE_BATCH_COUNT + 1) throw new IOException("TREE_READ_ARGUMENT");
    control.check();
    byte[] encoded = treeNames(names, count);
    try {
      int result =
          readSmallBatchBytes(
              parent,
              parentDevice,
              parentInode,
              encoded,
              count,
              expected,
              payload,
              offsets,
              readCancellation(control));
      if (result != 0) throw treeFailure(result);
    } catch (ReadFailure failure) {
      throw failure.source;
    }
    control.check();
    return offsets[count];
  }

  static void flush(int[] descriptors) throws IOException {
    int result = flushFiles(descriptors);
    if (result < 0) throw new IOException("FILESYSTEM_" + -result);
  }

  private static IOException fileFailure(int status) {
    if (status > 0)
      return new IOException(
          status == 1
              ? "FILE_TYPE"
              : status == 2
                  ? "SOURCE_CHANGED"
                  : status == 3 ? "TAR_PARENT_CHANGED" : "TAR_TARGET_CHANGED");
    int errno = -status;
    return new IOException(
        errno == 1 || errno == 13
            ? "PERMISSION_DENIED"
            : errno == 28 ? "NO_SPACE" : "FILESYSTEM_" + errno);
  }

  /** 仅用于已完整读取的新建签名小成员；创建后的失败保留出生身份交给原发布边界清理。 */
  static SmallFile prepareSmallFile(
      int parent,
      long parentDevice,
      long parentInode,
      String leaf,
      byte[] bytes,
      int count,
      int mode)
      throws IOException {
    if (parent < 0
        || bytes == null
        || count < 0
        || count > MAX_SMALL_FILE_BYTES
        || count > bytes.length
        || leaf == null
        || leaf.isEmpty()
        || leaf.equals(".")
        || leaf.equals("..")
        || leaf.indexOf('/') >= 0
        || leaf.indexOf('\0') >= 0) throw new IOException("SMALL_FILE_ARGUMENT");
    final byte[] name;
    try {
      ByteBuffer encoded =
          StandardCharsets.UTF_8
              .newEncoder()
              .onMalformedInput(CodingErrorAction.REPORT)
              .onUnmappableCharacter(CodingErrorAction.REPORT)
              .encode(CharBuffer.wrap(leaf));
      if (encoded.remaining() > MAX_LEAF_BYTES) throw new IOException("SMALL_FILE_ARGUMENT");
      name = new byte[encoded.remaining()];
      encoded.get(name);
    } catch (CharacterCodingException error) {
      throw new IOException("SMALL_FILE_ARGUMENT", error);
    }
    long[] state = new long[7];
    state[0] = -1;
    int status =
        prepareSmallFileBytes(
            parent, parentDevice, parentInode, name, bytes, count, mode & 0777, state);
    ParcelFileDescriptor handle = null;
    try {
      if (state[0] >= 0) handle = ParcelFileDescriptor.adoptFd((int) state[0]);
      return new SmallFile(parent, parentDevice, parentInode, name, handle, status, state);
    } catch (RuntimeException | Error failure) {
      try {
        if (handle != null) handle.close();
        else if (state[0] >= 0) {
          int closing = closeSmallFileDescriptor((int) state[0]);
          if (closing != 0) failure.addSuppressed(fileFailure(closing));
        }
      } catch (IOException closing) {
        failure.addSuppressed(closing);
      }
      throw failure;
    }
  }

  /** 写 FD 只有这个 PFD 所有者；native 不改名、不删除，也不保留句柄。 */
  static final class SmallFile implements Closeable {
    final ParcelFileDescriptor handle;
    final boolean identified;
    final long device, inode, size, modified;
    final int permissions;
    private final int parent, status;
    private final long parentDevice, parentInode;
    private final byte[] leaf;
    private final long[] finished = new long[5];
    private boolean closed;

    private SmallFile(
        int parent,
        long parentDevice,
        long parentInode,
        byte[] leaf,
        ParcelFileDescriptor handle,
        int status,
        long[] state) {
      this.parent = parent;
      this.parentDevice = parentDevice;
      this.parentInode = parentInode;
      this.leaf = leaf;
      this.handle = handle;
      this.status = status;
      identified = state[1] == 1;
      device = state[2];
      inode = state[3];
      size = state[4];
      modified = state[5];
      permissions = (int) state[6];
    }

    /** 调用者必须先登记 identified 出生身份；失败也由原 finally 按此 inode 清理。 */
    void check() throws IOException {
      if (status != 0) throw fileFailure(status);
      if (closed || handle == null || !identified) throw new IOException("FILE_TYPE");
    }

    /** 必须在原 queueSync 时间点之后调用；返回本轮 dev/ino/size/mtime秒/权限。 */
    long[] finish() throws IOException {
      check();
      int result =
          finishSmallFileBytes(
              parent, parentDevice, parentInode, leaf, handle.getFd(), device, inode, finished);
      if (result != 0) throw fileFailure(result);
      return finished;
    }

    @Override
    public void close() throws IOException {
      if (closed) return;
      closed = true;
      if (handle != null) handle.close();
    }
  }

  /** 缓存同一解压会话的完整祖先链；每次核验仍按原顺序逐项 lstat。 */
  static final class DirectoryIdentities {
    private final byte[] paths;
    private final long[] identities;
    private final long[] rootStat = new long[3];
    private boolean verified;

    DirectoryIdentities(String[] absolutePaths, long[] devices, long[] inodes) throws IOException {
      if (absolutePaths == null
          || devices == null
          || inodes == null
          || absolutePaths.length == 0
          || absolutePaths.length > MAX_DIRECTORY_IDENTITIES
          || devices.length != absolutePaths.length
          || inodes.length != absolutePaths.length) throw new IOException("UNSAFE_HOST_PATH");
      ByteArrayOutputStream encoded = new ByteArrayOutputStream();
      identities = new long[absolutePaths.length * 2];
      for (int i = 0; i < absolutePaths.length; i++) {
        String path = absolutePaths[i];
        if (path == null || path.indexOf('\0') >= 0) throw new IOException("UNSAFE_HOST_PATH");
        if (i == 0) {
          if (!path.equals("/")) throw new IOException("UNSAFE_HOST_PATH");
        } else {
          String prefix = i == 1 ? "/" : absolutePaths[i - 1] + "/";
          if (!path.startsWith(prefix)) throw new IOException("UNSAFE_HOST_PATH");
          String part = path.substring(prefix.length());
          if (part.isEmpty() || part.equals(".") || part.equals("..") || part.indexOf('/') >= 0)
            throw new IOException("UNSAFE_HOST_PATH");
        }
        ByteBuffer bytes;
        try {
          bytes =
              StandardCharsets.UTF_8
                  .newEncoder()
                  .onMalformedInput(CodingErrorAction.REPORT)
                  .onUnmappableCharacter(CodingErrorAction.REPORT)
                  .encode(CharBuffer.wrap(path));
        } catch (CharacterCodingException error) {
          throw new IOException("UNSAFE_HOST_PATH", error);
        }
        if (bytes.remaining() >= MAX_PATH_BYTES) throw new IOException("UNSAFE_HOST_PATH");
        while (bytes.hasRemaining()) encoded.write(bytes.get());
        encoded.write(0);
        identities[i * 2] = devices[i];
        identities[i * 2 + 1] = inodes[i];
      }
      paths = encoded.toByteArray();
    }

    /** 返回 -1 表示一致，否则是首个缺失、类型或身份改变的祖先序号。 */
    int verify() throws IOException {
      verified = false;
      int result = verifyDirectoryIdentities(paths, identities, rootStat);
      if (result < 0) {
        int errno = -result;
        throw new IOException(
            errno == 1 || errno == 13
                ? "PERMISSION_DENIED"
                : errno == 28 ? "NO_SPACE" : "FILESYSTEM_" + errno);
      }
      verified = result == 0;
      return result - 1;
    }

    long rootSize() {
      requireVerified();
      return rootStat[0];
    }

    long rootModified() {
      requireVerified();
      return rootStat[1];
    }

    int rootPermissions() {
      requireVerified();
      return (int) rootStat[2];
    }

    private void requireVerified() {
      if (!verified) throw new IllegalStateException("DIRECTORY_IDENTITIES_NOT_VERIFIED");
    }
  }
}
