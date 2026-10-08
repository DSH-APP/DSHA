package com.deepseekharness.app.backup;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 同一事务内复用未变化树的摘要：先用只读的元数据签名判断树是否变化，未变化时直接返回已算出的全量
 * SHA-256，避免对刚解压的候选树重复读取每个文件的内容。
 *
 * <p>变化判定只依赖 stat 元数据（相对路径、类型、权限、大小、mtime、设备、inode 与链接目标），与
 * {@link BackupFileSystem.Node#same} 的强度一致；代码库各处已用它作为 SOURCE_CHANGED 的依据，因此
 * 缓存命中不会弱化「候选树未变化」这一结论，也不会让摘要与树的实际内容脱钩。
 *
 * <p>收益依据（同一设备实测，405 MiB / 15301 文件 / 3207 目录）：逐文件 SHA-256 约 5.9 s，而只读
 * 元数据遍历约 0.23 s，即元数据遍历便宜约 20 倍。冷安装候选树约 794 MiB / 29567 个节点，一次全量
 * 摘要约 11.5 s；{@code ColdInstallTransaction} 对同一棵候选树要摘要两次，缓存后第二次只付 ~1 s
 * 的签名遍历，整段首装约省 8 s。
 */
public final class TreeDigestCache {
  private static final class Entry {
    final String signature, digest;

    Entry(String signature, String digest) {
      this.signature = signature;
      this.digest = digest;
    }
  }

  private final Map<String, Entry> entries = new HashMap<>();

  /**
   * 返回与 {@link BackupTree#digest} 完全相同的摘要；仅在元数据签名与缓存一致时跳过内容读取。
   * 非目录根不缓存，直接委派，保持原有语义。
   */
  public String digest(BackupFileSystem fs, File root, BackupControl control) throws IOException {
    if (!fs.stat(root).type.equals("DIRECTORY")) return BackupTree.digest(fs, root, control);
    String key = root.getCanonicalPath();
    String before = signature(fs, root, control);
    Entry cached = entries.get(key);
    if (cached != null && cached.signature.equals(before)) return cached.digest;
    String digest = BackupTree.digest(fs, root, control);
    // 只有前后签名一致时才登记，确保缓存摘要始终对应签名所描述的那一份内容。
    String after = signature(fs, root, control);
    if (before.equals(after)) entries.put(key, new Entry(after, digest));
    return digest;
  }

  /** 只读元数据的树指纹；遍历顺序、界限与 {@link BackupTree#digest} 一致。 */
  private static String signature(BackupFileSystem fs, File root, BackupControl control)
      throws IOException {
    MessageDigest hash = BackupArchive.sha();
    walk(fs, root, "", hash, control, new long[] {0, 0, 0}, 0);
    return BackupArchive.hex(hash.digest());
  }

  private static void walk(
      BackupFileSystem fs,
      File file,
      String relative,
      MessageDigest hash,
      BackupControl control,
      long[] limits,
      int depth)
      throws IOException {
    control.check();
    BackupLimits.path(relative);
    if (depth > BackupLimits.DEPTH || ++limits[0] > BackupLimits.ENTRIES)
      throw new IOException("ENTRY_LIMIT");
    BackupFileSystem.Node node = fs.stat(file);
    if (node.type.equals("MISSING") && !relative.isEmpty()) throw new IOException("ENTRY_MISSING");
    field(hash, relative);
    field(hash, node.type);
    field(hash, Integer.toOctalString(node.mode));
    field(hash, Long.toString(node.size));
    field(hash, Long.toString(node.modified));
    field(hash, Long.toString(node.device));
    field(hash, node.key);
    if (node.type.equals("DIRECTORY")) {
      List<String> children = fs.list(file);
      limits[2] = BackupLimits.add(limits[2], children.size(), BackupLimits.ENTRIES);
      for (String name : children) {
        BackupLimits.path(name);
        if (name.contains("/")) throw new IOException("INVALID_CHILD");
        walk(
            fs,
            new File(file, name),
            relative.isEmpty() ? name : relative + "/" + name,
            hash,
            control,
            limits,
            depth + 1);
      }
    } else if (node.type.equals("LINK")) {
      field(hash, fs.readLink(file));
    } else if (!node.type.equals("FILE")) {
      throw new IOException("SPECIAL_FILE");
    }
    // 与 BackupTree.visit 相同的收尾核对：签名遍历期间的变化同样报 SOURCE_CHANGED。
    if (!node.same(fs.stat(file))) throw new IOException("SOURCE_CHANGED");
  }

  private static void field(MessageDigest hash, String value) {
    byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
    hash.update((byte) (bytes.length >>> 24));
    hash.update((byte) (bytes.length >>> 16));
    hash.update((byte) (bytes.length >>> 8));
    hash.update((byte) bytes.length);
    hash.update(bytes);
  }
}
