package com.deepseekharness.app.backup;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 同一事务内复用未变化树的摘要：先用只读的签名判断树是否变化，未变化时直接返回已算出的全量
 * SHA-256，避免对刚解压的候选树重复读取每个文件的内容。
 *
 * <p><b>复用证据的契约</b>：签名 = 相对路径、类型、权限、大小、mtime、设备、inode、链接目标，加上
 * {@link BackupFileSystem.Node#ctime}。ctime 没有可用的恢复接口（{@code utimensat} 只能改 atime/mtime），
 * 因此它是一条写入者伪造不了的见证：等长改写 + 恢复 mtime 会改变 ctime，签名随之不同，仍走全量哈希并
 * 照旧报 {@code COLD_PREPARED_CHANGED} / {@code COLD_ORIGINAL_CHANGED}。
 *
 * <p>登记（即允许后续复用）要求两次签名遍历都满足两条封印，任一不满足就放弃复用：
 *
 * <ul>
 *   <li><b>见证可用</b>：树内每个节点都提供了 ctime（{@code ctime != 0}）。实现没给见证时（例如只用
 *       6 参数构造 {@code Node} 的文件系统）直接放弃复用。
 *   <li><b>遍历所在秒严格晚于树内最新 ctime 的秒</b>：Android 的 {@code StructStat} 在 API 34 之前只有
 *       秒级 ctime，秒级粒度存在同秒别名。要求「全量摘要之前的那次遍历」与「登记时的那次遍历」都晚于
 *       树内最新 ctime 所在秒之后，任何晚于它们的写入都必然把 ctime 推到更晚的秒，别名窗口随之关闭。
 *       只封印登记时的那次是不够的：写入者若与摘要前的那次遍历落在同一秒，秒级 ctime 不变，两次指纹会
 *       照旧相等，缓存里存下的摘要在发布前就已经失效。
 *   <li>代价：刚写完就立刻摘要的树不会被登记。冷安装里最后一次写入（解压 / 装包）与 {@code prepared()}
 *       之间隔着 guest 退出与校验，正常满足；夹具级小树与紧邻写入则放弃复用，宁可多读一遍内容。
 * </ul>
 *
 * <p><b>强度边界</b>：复用建立的是「元数据 + ctime 未变」的结论，不是「又读了一遍内容」。它依赖两条前提：
 * 写入者与 App 同 UID 且没有设置 ctime 的接口（内核不提供），以及墙钟不回拨。真正的写入屏障证明不在本类
 * 职责内。
 *
 * <p><b>收益未在真机测量</b>：本机（非设备）405 MiB / 15301 文件上逐文件 SHA-256 约 5.9 s、只读元数据
 * 遍历约 0.232 s；据此按候选树 794 MiB / 29567 节点折算单次全量约 11.5 s，属折算值而非实测。真机首装
 * 总时长未测，不得据此承诺「省 N 秒」。
 */
public final class TreeDigestCache {
  private static final class Entry {
    final String signature, digest;

    Entry(String signature, String digest) {
      this.signature = signature;
      this.digest = digest;
    }
  }

  /** 一次签名遍历的结果：指纹，以及该指纹能否充当复用证据（见类注释的两条封印条件）。 */
  private static final class Signature {
    final String hash;
    final boolean reusable;

    Signature(String hash, boolean reusable) {
      this.hash = hash;
      this.reusable = reusable;
    }
  }

  /** 遍历期间收集的见证：树内最新 ctime 所在秒，以及见证是否完整。 */
  private static final class Witness {
    long newestSecond;
    boolean complete = true;

    void observe(BackupFileSystem.Node node) {
      if (node.ctime == 0) complete = false;
      else newestSecond = Math.max(newestSecond, node.ctime / 1_000_000_000L);
    }

    /** 封印成立 = 见证完整，且此刻的秒严格晚于树内最新 ctime 的秒。 */
    boolean sealed() {
      return TreeDigestCache.sealed(complete, newestSecond, System.currentTimeMillis());
    }
  }

  /**
   * 封印规则本身（独立成纯函数便于直接断言）：见证必须完整，且 {@code nowMillis} 所在秒严格晚于树内最新
   * ctime 所在秒。秒级 ctime 下这是唯一能关掉同秒别名的条件，因此 {@code nowSecond == ctimeSecond} 必须
   * 判为未封印。
   */
  static boolean sealed(boolean complete, long newestSecond, long nowMillis) {
    return complete && nowMillis / 1000L > newestSecond;
  }

  private final Map<String, Entry> entries = new HashMap<>();

  /**
   * 返回与 {@link BackupTree#digest} 完全相同的摘要；仅在签名与缓存一致、且两侧签名都能充当复用证据时
   * 跳过内容读取。非目录根不缓存，直接委派，保持原有语义。
   */
  public String digest(BackupFileSystem fs, File root, BackupControl control) throws IOException {
    if (!fs.stat(root).type.equals("DIRECTORY")) return BackupTree.digest(fs, root, control);
    String key = root.getCanonicalPath();
    Signature before = signature(fs, root, control);
    Entry cached = entries.get(key);
    if (cached != null && before.reusable && cached.signature.equals(before.hash))
      return cached.digest;
    String digest = BackupTree.digest(fs, root, control);
    // 前后指纹一致、且两次遍历都已封印时才登记：只封印登记时的那次挡不住「与摘要前那次遍历同秒落笔」的
    // 写入者——秒级 ctime 不变会让两次指纹照旧相等，缓存里存下的摘要在发布前就已失效。
    Signature after = signature(fs, root, control);
    if (before.reusable && after.reusable && before.hash.equals(after.hash))
      entries.put(key, new Entry(after.hash, digest));
    return digest;
  }

  /** 只读元数据 + ctime 的树指纹；遍历顺序、界限与 {@link BackupTree#digest} 一致。 */
  private static Signature signature(BackupFileSystem fs, File root, BackupControl control)
      throws IOException {
    MessageDigest hash = BackupArchive.sha();
    Witness witness = new Witness();
    walk(fs, root, "", hash, control, new long[] {0, 0, 0}, witness, 0);
    return new Signature(BackupArchive.hex(hash.digest()), witness.sealed());
  }

  private static void walk(
      BackupFileSystem fs,
      File file,
      String relative,
      MessageDigest hash,
      BackupControl control,
      long[] limits,
      Witness witness,
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
    field(hash, Long.toString(node.ctime));
    witness.observe(node);
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
            witness,
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
