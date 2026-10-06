package com.deepseekharness.app.backup;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

/** 事务候选及被覆盖原件的摘要；链接只记录本体，不沿链接遍历。 */
public final class BackupTree {
  private BackupTree() {}

  public static String digest(BackupFileSystem fs, File root, BackupControl control)
      throws IOException {
    if (fs.getClass() == AndroidBackupFileSystem.class && fs.stat(root).type.equals("DIRECTORY")) {
      try (AndroidTreeFileSystem tree = new AndroidTreeFileSystem(root)) {
        return digestTree(tree, root, control);
      }
    }
    return digestTree(fs, root, control);
  }

  private static String digestTree(BackupFileSystem fs, File root, BackupControl control)
      throws IOException {
    TreeHash hash = new TreeHash();
    long[] limits = {0, 0, 0};
    visit(
        fs,
        root,
        "",
        hash,
        BackupArchive.sha(),
        control,
        limits,
        new byte[65536],
        null,
        new ReadScratch(),
        null);
    return BackupArchive.hex(hash.finish());
  }

  private static final class ReadScratch {
    private byte[] payload;

    byte[] bytes() {
      if (payload == null) payload = new byte[AndroidTreeFileSystem.LeafBatch.PAYLOAD_BYTES];
      return payload;
    }
  }

  /** 私有候选复制；不跟随链接，也不使用硬链接。复制前后独立核验源与候选字节。 */
  public static void copy(BackupFileSystem fs, File source, File target, BackupControl control)
      throws IOException {
    String from = source.getAbsolutePath(), to = target.getAbsolutePath();
    if (to.equals(from)
        || to.startsWith(from + File.separator)
        || from.startsWith(to + File.separator)) throw new IOException("COPY_OVERLAP");
    String before = digest(fs, source, control);
    copyNode(fs, source, target, control, new long[] {0, 0, 0}, 0);
    if (!before.equals(digest(fs, source, control)) || !before.equals(digest(fs, target, control)))
      throw new IOException("COPY_VERIFICATION");
  }

  private static void copyNode(
      BackupFileSystem fs,
      File source,
      File target,
      BackupControl control,
      long[] limits,
      int depth)
      throws IOException {
    control.check();
    if (depth > BackupLimits.DEPTH || ++limits[0] > BackupLimits.ENTRIES)
      throw new IOException("COPY_LIMIT");
    if (!fs.stat(target).type.equals("MISSING")) throw new IOException("COPY_TARGET_EXISTS");
    BackupFileSystem.Node node = fs.stat(source);
    if (node.type.equals("DIRECTORY")) {
      fs.directory(target);
      var children = fs.list(source);
      limits[2] = BackupLimits.add(limits[2], children.size(), BackupLimits.ENTRIES);
      for (String name : children) {
        BackupLimits.path(name);
        if (name.contains("/")) throw new IOException("INVALID_CHILD");
        copyNode(fs, new File(source, name), new File(target, name), control, limits, depth + 1);
      }
      fs.mode(target, node.mode);
    } else if (node.type.equals("FILE")) {
      try (InputStream in = fs.read(source, node);
          OutputStream out = fs.create(target)) {
        byte[] bytes = new byte[65536];
        int count;
        while ((count = in.read(bytes)) != -1) {
          control.check();
          limits[1] = BackupLimits.add(limits[1], count, BackupLimits.BYTES);
          out.write(bytes, 0, count);
          control.report("COPYING_CANDIDATE", limits[0], limits[1]);
        }
      }
      fs.mode(target, node.mode);
    } else if (node.type.equals("LINK")) fs.symlink(fs.readLink(source), target);
    else if (!node.type.equals("MISSING")) throw new IOException("SPECIAL_FILE");
    if (!node.same(fs.stat(source))) throw new IOException("SOURCE_CHANGED");
  }

  /** 保持原有长度前缀字段格式。按有界缓冲合并更新，避免每个长度字节各进一次 SHA。 文件 SHA 直接编码为原有 64 字节小写 hex 字段，不建立中间字符串和 UTF-8 数组。 */
  private static final class TreeHash {
    final MessageDigest digest = BackupArchive.sha();
    final byte[] fields = new byte[65536];
    int count;

    private void flush() {
      if (count == 0) return;
      digest.update(fields, 0, count);
      count = 0;
    }

    private void ensure(int length) {
      if (length > fields.length - count) flush();
    }

    private void length(int length) {
      ensure(4);
      fields[count++] = (byte) (length >> 24);
      fields[count++] = (byte) (length >> 16);
      fields[count++] = (byte) (length >> 8);
      fields[count++] = (byte) length;
    }

    private void append(String value) {
      byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
      length(bytes.length);
      if (bytes.length > fields.length) {
        flush();
        digest.update(bytes);
        return;
      }
      ensure(bytes.length);
      System.arraycopy(bytes, 0, fields, count, bytes.length);
      count += bytes.length;
    }

    void node(String relative, BackupFileSystem.Node node) {
      append(relative);
      append(node.type);
      if (node.type.equals("FILE") || node.type.equals("DIRECTORY"))
        append(Integer.toOctalString(node.mode));
    }

    void text(String value) {
      append(value);
    }

    void file(byte[] sha) {
      length(sha.length * 2);
      ensure(sha.length * 2);
      String digits = "0123456789abcdef";
      for (byte value : sha) {
        int unsigned = value & 255;
        fields[count++] = (byte) digits.charAt(unsigned >>> 4);
        fields[count++] = (byte) digits.charAt(unsigned & 15);
      }
    }

    byte[] finish() {
      flush();
      return digest.digest();
    }
  }

  /** 每遍树复用文件 SHA 引擎，字段仍保存各文件独立的完整 SHA-256。 */
  private static byte[] fileDigest(
      InputStream input, MessageDigest digest, BackupControl control, byte[] buffer)
      throws IOException {
    long total = 0;
    int count;
    while ((count = input.read(buffer)) != -1) {
      control.check();
      total = BackupLimits.add(total, count, BackupLimits.BYTES + 256L * 1024 * 1024);
      digest.update(buffer, 0, count);
    }
    return digest.digest();
  }

  private static void visit(
      BackupFileSystem fs,
      File file,
      String relative,
      TreeHash hash,
      MessageDigest fileHash,
      BackupControl control,
      long[] limits,
      byte[] buffer,
      AndroidTreeFileSystem.DigestDirectory parent,
      ReadScratch scratch,
      BackupFileSystem.Node known)
      throws IOException {
    control.check();
    BackupLimits.path(relative);
    if (++limits[0] > BackupLimits.ENTRIES) throw new IOException("ENTRY_LIMIT");
    BackupFileSystem.Node before =
        known != null ? known : parent == null ? fs.stat(file) : parent.stat(file.getName());
    hash.node(relative, before);
    if (before.type.equals("FILE")) {
      limits[1] = BackupLimits.add(limits[1], before.size, BackupLimits.BYTES);
      if (parent != null) {
        hash.file(parent.digestFile(file.getName(), before, fileHash, buffer, control));
        // digestFile 已在关闭 FD 前核对同一文件的 fstat 与固定父目录内的 lstat。
        return;
      }
      try (InputStream in = fs.read(file, before)) {
        hash.file(fileDigest(in, fileHash, control, buffer));
      }
    } else if (before.type.equals("DIRECTORY")) {
      try (AndroidTreeFileSystem.DigestDirectory directory =
          fs instanceof AndroidTreeFileSystem tree
              ? parent == null ? tree.digestRoot(before) : parent.directory(file.getName(), before)
              : null) {
        var children = directory == null ? fs.list(file) : directory.list();
        limits[2] = BackupLimits.add(limits[2], children.size(), BackupLimits.ENTRIES);
        if (directory != null)
          visitChildren(
              fs, file, relative, hash, fileHash, control, limits, buffer, directory, scratch,
              children);
        else
          for (String name : children) {
            BackupLimits.path(name);
            if (name.contains("/")) throw new IOException("INVALID_CHILD");
            visit(
                fs,
                new File(file, name),
                relative.isEmpty() ? name : relative + "/" + name,
                hash,
                fileHash,
                control,
                limits,
                buffer,
                directory,
                scratch,
                null);
          }
      }
      // scope.close 已核对目录 FD 和父目录项；根同时核对宿主路径。
      if (fs instanceof AndroidTreeFileSystem) return;
    } else if (before.type.equals("LINK"))
      hash.text(parent == null ? fs.readLink(file) : parent.readLink(file.getName()));
    else if (!before.type.equals("MISSING")) throw new IOException("SPECIAL_FILE");
    if (!before.same(parent == null ? fs.stat(file) : parent.stat(file.getName())))
      throw new IOException("SOURCE_CHANGED");
  }

  /** 本遍的新鲜批量 tuple 先过原 Java 预算，最多 1MiB 的小 FILE 才进入串行 native 读取。 */
  private static void visitChildren(
      BackupFileSystem fs,
      File file,
      String relative,
      TreeHash hash,
      MessageDigest fileHash,
      BackupControl control,
      long[] limits,
      byte[] buffer,
      AndroidTreeFileSystem.DigestDirectory directory,
      ReadScratch scratch,
      java.util.List<String> children)
      throws IOException {
    for (int first = 0; first < children.size(); first += AndroidTreeFileSystem.LeafBatch.MEMBERS) {
      AndroidTreeFileSystem.LeafBatch batch = directory.batch(children, first, control);
      int index = 0;
      while (index < batch.size()) {
        control.check();
        if (!batch.small(index)) {
          String name = batch.name(index);
          visit(
              fs,
              new File(file, name),
              relative.isEmpty() ? name : relative + "/" + name,
              hash,
              fileHash,
              control,
              limits,
              buffer,
              directory,
              scratch,
              batch.node(index));
          index++;
          continue;
        }
        int count = 1;
        while (index + count < batch.size() && batch.small(index + count)) count++;
        for (int entry = index; entry < index + count; entry++) {
          control.check();
          String name = batch.name(entry), path = relative.isEmpty() ? name : relative + "/" + name;
          BackupLimits.path(path);
          if (++limits[0] > BackupLimits.ENTRIES) throw new IOException("ENTRY_LIMIT");
          limits[1] = BackupLimits.add(limits[1], batch.node(entry).size, BackupLimits.BYTES);
        }
        byte[][] digests = batch.digestSmall(index, count, scratch.bytes(), fileHash, control);
        for (int entry = 0; entry < count; entry++) {
          String name = batch.name(index + entry);
          hash.node(relative.isEmpty() ? name : relative + "/" + name, batch.node(index + entry));
          hash.file(digests[entry]);
        }
        index += count;
      }
    }
  }
}
