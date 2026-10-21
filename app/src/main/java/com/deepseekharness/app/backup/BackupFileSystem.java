package com.deepseekharness.app.backup;

import java.io.*;
import java.util.*;

/** 窄文件系统边界：生产实现使用 Android lstat/NOFOLLOW/fstat，JVM 使用实际临时文件验证。 */
public interface BackupFileSystem {
  final class Node {
    public final String type, key;
    public final com.deepseekharness.app.util.FileKind kind;
    public final long size, modified, device;
    public final int mode;

    public Node(String type, String key, long size, long modified, long device, int mode) {
      this.kind = com.deepseekharness.app.util.FileKind.fromWire(type);
      this.type = kind.wire();
      this.key = key;
      this.size = size;
      this.modified = modified;
      this.device = device;
      this.mode = mode;
    }

    public boolean same(Node other) {
      return other != null
          && type.equals(other.type)
          && key.equals(other.key)
          && size == other.size
          && modified == other.modified
          && device == other.device
          && mode == other.mode;
    }
  }

  Node stat(File file) throws IOException;

  String readLink(File file) throws IOException;

  List<String> list(File directory) throws IOException;

  InputStream read(File file, Node expected) throws IOException;

  OutputStream create(File file) throws IOException;

  void directory(File file) throws IOException;

  void move(File source, File target) throws IOException;

  void delete(File file) throws IOException;

  void syncDirectory(File directory) throws IOException;

  void mode(File file, int mode) throws IOException;

  void symlink(String target, File link) throws IOException;

  /** 仅用于已退出使用的事务私有树；实现可恢复本 UID 目录的遍历权限。 */
  default void prepareOwnedRemoval(File directory) throws IOException {}

  default File child(File root, String relative) throws IOException {
    BackupLimits.path(relative);
    File current = root.getAbsoluteFile();
    if (stat(current).kind != com.deepseekharness.app.util.FileKind.DIRECTORY)
      throw new IOException("UNSAFE_ROOT");
    if (relative.isEmpty()) return current;
    String[] parts = relative.split("/");
    for (int i = 0; i < parts.length; i++) {
      if (stat(current).kind != com.deepseekharness.app.util.FileKind.DIRECTORY)
        throw new IOException("PARENT_LINK_OR_MISSING");
      current = new File(current, parts[i]);
    }
    return current;
  }

  default void parents(File root, String relative) throws IOException {
    BackupLimits.path(relative);
    String[] parts = relative.split("/");
    File at = root;
    if (!stat(at).type.equals("DIRECTORY")) throw new IOException("UNSAFE_ROOT");
    for (int i = 0; i < parts.length - 1; i++) {
      File next = new File(at, parts[i]);
      Node node = stat(next);
      if (node.type.equals("MISSING")) directory(next);
      else if (!node.type.equals("DIRECTORY")) throw new IOException("PARENT_LINK");
      at = next;
    }
  }

  default void removeOwned(File root, String relative) throws IOException {
    if (relative.isEmpty()) throw new IOException("REFUSE_ROOT_DELETE");
    File target = child(root, relative);
    Node node = stat(target);
    if (node.type.equals("MISSING")) return;
    if (node.type.equals("DIRECTORY")) {
      prepareOwnedRemoval(target);
      for (String name : list(target))
        removeOwned(root, relative.isEmpty() ? name : relative + "/" + name);
    }
    if (relative.isEmpty()) throw new IOException("REFUSE_ROOT_DELETE");
    delete(target);
  }

  default byte[] small(File file, int limit) throws IOException {
    Node before = stat(file);
    if (!before.type.equals("FILE") || before.size > limit) throw new IOException("METADATA_LIMIT");
    try (InputStream in = read(file, before);
        ByteArrayOutputStream out = new ByteArrayOutputStream()) {
      byte[] buffer = new byte[4096];
      int n;
      while ((n = in.read(buffer)) != -1) {
        if (out.size() + n > limit) throw new IOException("METADATA_LIMIT");
        out.write(buffer, 0, n);
      }
      return out.toByteArray();
    }
  }

  default void atomic(File directory, String name, byte[] bytes) throws IOException {
    BackupLimits.path(name);
    if (name.contains("/") || bytes == null) throw new IOException("RECORD_NAME");
    File target = child(directory, name), previous = child(directory, name + ".previous");

    // Recover the two durable rename boundaries of the previous atomic write.
    // If the process died after target -> previous, restore that last complete
    // record before attempting this write. If target already exists, the prior
    // publication may have completed before its previous-file cleanup; keep
    // that older record under a unique name rather than blocking every future
    // update or silently discarding an unverified file.
    Node current = stat(target), old = stat(previous);
    boolean recoveredPrevious = false;
    if (!old.type.equals("MISSING")) {
      if (current.type.equals("MISSING")) {
        if (!old.type.equals("FILE")) throw new IOException("PREVIOUS_RECORD_UNREADABLE");
        restoreRecordCopy(previous, old, target);
        recoveredPrevious = true;
        current = stat(target);
        old = stat(previous);
      }
      if (!recoveredPrevious && !old.type.equals("MISSING")) {
        File retained = child(directory, name + ".previous-retained-" + UUID.randomUUID());
        move(previous, retained);
        syncDirectory(directory);
      }
    }
    current = stat(target);
    if (current.type.equals("FILE")
        && current.size == bytes.length
        && sameBytes(this, target, current, bytes)) {
      if (recoveredPrevious && !stat(previous).type.equals("MISSING")) {
        File retained = child(directory, name + ".previous-retained-" + UUID.randomUUID());
        move(previous, retained);
        syncDirectory(directory);
      }
      return;
    }
    if (!current.type.equals("MISSING") && !current.type.equals("FILE"))
      throw new IOException("RECORD_TARGET_TYPE");

    File part = child(directory, name + ".tmp-" + UUID.randomUUID());
    Node ownedPart = null;
    try {
      OutputStream opened = create(part);
      try (OutputStream out = opened) {
        ownedPart = stat(part);
        out.write(bytes);
      }
      current = stat(target);
      if (!current.type.equals("MISSING") && !current.type.equals("FILE"))
        throw new IOException("RECORD_TARGET_TYPE");
      if (recoveredPrevious && !stat(previous).type.equals("MISSING")) {
        File retained = child(directory, name + ".previous-retained-" + UUID.randomUUID());
        move(previous, retained);
        syncDirectory(directory);
      }
      // move 不覆盖；已有完整记录只进入固定 previous 槽位后才发布。
      if (!current.type.equals("MISSING")) move(target, previous);
      move(part, target);
      syncDirectory(directory);
      if (!current.type.equals("MISSING")) {
        delete(previous);
        syncDirectory(directory);
      }
    } finally {
      Node remaining = stat(part);
      if (ownedPart != null
          && remaining.type.equals("FILE")
          && ownedPart.key.equals(remaining.key)
          && ownedPart.device == remaining.device) delete(part);
    }
  }

  private void restoreRecordCopy(File source, Node expected, File target) throws IOException {
    if (!expected.type.equals("FILE") || expected.size > BackupLimits.BYTES)
      throw new IOException("PREVIOUS_RECORD_UNREADABLE");
    File temporary =
        child(target.getParentFile(), target.getName() + ".restore-" + UUID.randomUUID());
    long copied = 0;
    Node ownedTemporary = null;
    try {
      try (InputStream input = read(source, expected)) {
        OutputStream opened = create(temporary);
        try (OutputStream output = opened) {
          ownedTemporary = stat(temporary);
          byte[] buffer = new byte[8192];
          int count;
          while ((count = input.read(buffer)) != -1) {
            copied = BackupLimits.add(copied, count, BackupLimits.BYTES);
            output.write(buffer, 0, count);
          }
        }
      }
      if (copied != expected.size) throw new IOException("PREVIOUS_RECORD_CHANGED");
      move(temporary, target);
      syncDirectory(target.getParentFile());
    } finally {
      Node remaining = stat(temporary);
      if (ownedTemporary != null
          && remaining.type.equals("FILE")
          && ownedTemporary.key.equals(remaining.key)
          && ownedTemporary.device == remaining.device) delete(temporary);
    }
  }

  private static boolean sameBytes(BackupFileSystem fs, File file, Node expected, byte[] wanted)
      throws IOException {
    try (InputStream input = fs.read(file, expected)) {
      byte[] buffer = new byte[8192];
      int offset = 0;
      while (offset < wanted.length) {
        int count = input.read(buffer, 0, Math.min(buffer.length, wanted.length - offset));
        if (count < 0) return false;
        for (int i = 0; i < count; i++) if (buffer[i] != wanted[offset + i]) return false;
        offset += count;
      }
      return input.read() == -1;
    }
  }
}
