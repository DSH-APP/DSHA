package com.deepseekharness.app.runtime;

import com.deepseekharness.app.backup.BackupFileSystem;
import java.io.File;
import java.io.FilterOutputStream;
import java.io.IOException;
import java.io.OutputStream;

/** 仅供签名内置资产的解压会话；用户备份仍使用原文件层的逐项耐久策略。 */
interface TrustedAssetFileSystem {
  int SMALL_FILE_BYTES = 32768;

  /** 出生身份来自实际打开的文件描述符，不能用打开后的路径 stat 临时认领。 */
  abstract class CreatedFile extends FilterOutputStream {
    final BackupFileSystem.Node identity;

    CreatedFile(OutputStream output, BackupFileSystem.Node identity) {
      super(output);
      this.identity = identity;
    }

    @Override
    public void write(byte[] bytes, int offset, int count) throws IOException {
      out.write(bytes, offset, count);
    }
  }

  File target(String path, boolean createParents) throws IOException;

  /** 一次文件发布共用固定父目录身份，结束时复核名称仍绑定本次目录。 */
  default java.io.Closeable fileEntry(String path) throws IOException {
    return () -> {};
  }

  /** 在同一个已核验的描述符上写入、设置权限并同步，不能按文件名重新认领。 */
  CreatedFile createWithMode(File file, int mode) throws IOException;

  /** 仅对缺失的短成员启用；输入完整读取后仍由 CreatedFile.close 完成同步和末次核验。 */
  default boolean supportsSmallFiles() {
    return false;
  }

  /**
   * 返回已经写入的本次 inode；创建后的写入失败延后到 close，调用者先登记 identity 再清理。
   * 独占创建失败不得认领名称上的其他文件，也不得删除或替换目标。
   */
  default CreatedFile prepareSmallFile(File file, byte[] bytes, int count, int mode)
      throws IOException {
    throw new IOException("TAR_SMALL_FILE_UNSUPPORTED");
  }

  /** 限制目录权限之前完整复核本次输出及父目录，并同步所有变更目录。 */
  void finishFiles() throws IOException;

  /** 目录权限可能禁止重新遍历；使用修改权限前持有的描述符完成同步。 */
  default void finishDirectoryModes() throws IOException {}
}
