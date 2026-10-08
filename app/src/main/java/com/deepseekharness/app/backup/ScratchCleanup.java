package com.deepseekharness.app.backup;

import com.deepseekharness.app.util.ScratchPaths;
import java.io.File;
import java.io.IOException;
import java.util.List;

/**
 * 临时文件目录的清理执行体：只删 {@link ScratchPaths} 显式声明的目标。
 *
 * <p>每条删除都先解析绝对路径并逐级核验父链（{@link ScratchPaths#resolve} /
 * {@link ScratchPaths#verifyOwnedFile}），核验失败只记 skipped/failed，不换路径重试。
 * 只依赖 {@link BackupFileSystem}，因此可以用 JVM 文件系统真跑删除边界。
 */
public final class ScratchCleanup {
  private ScratchCleanup() {}

  /** 每条动作的去向：deleted / absent / skipped / failed。 */
  public interface Sink {
    void record(String action, String relative, long bytes, String reason);
  }

  /**
   * 清空声明目录，并删除本 App 在 guest /tmp 里自己命名的临时文件。
   *
   * @return 实际释放的字节数（按清理前后重新统计，不采信删除前的估算）
   */
  public static long run(BackupFileSystem fs, File rootfs, Sink sink) {
    long freed = 0;
    for (String relative : ScratchPaths.DIRECTORY_WHITELIST) {
      try {
        File target = ScratchPaths.resolve(rootfs, relative);
        if (fs.stat(target).type.equals("MISSING")) {
          sink.record("absent", relative, 0, "");
          continue;
        }
        long before = bytes(fs, target);
        fs.removeOwned(rootfs, relative);
        long released = Math.max(0, before - bytes(fs, target));
        freed += released;
        sink.record("deleted", relative, released, "");
      } catch (IOException failure) {
        sink.record("skipped", relative, 0, code(failure));
      }
    }
    for (String owned : ScratchPaths.OWNED_FILE_WHITELIST) {
      String directory = owned.substring(0, owned.indexOf('/'));
      File at;
      try {
        // 先核验承载目录本身，避免透过链接去列别人的目录。
        at = ScratchPaths.verifyOwnedDirectory(rootfs, directory);
      } catch (IOException refusal) {
        sink.record("skipped", directory, 0, code(refusal));
        continue;
      }
      List<String> names;
      try {
        names = fs.list(at);
      } catch (IOException unavailable) {
        sink.record("absent", directory, 0, "");
        continue;
      }
      for (String name : names) {
        if (!ScratchPaths.ownedTemporary(name)) continue;
        String relative = directory + "/" + name;
        try {
          File target = ScratchPaths.verifyOwnedFile(rootfs, relative);
          long before = bytes(fs, target);
          fs.removeOwned(rootfs, relative);
          long released = Math.max(0, before - bytes(fs, target));
          freed += released;
          sink.record("deleted", relative, released, "");
        } catch (IOException failure) {
          sink.record("failed", relative, 0, code(failure));
        }
      }
    }
    return freed;
  }

  /** 有界统计（不跟随链接）；读不到的条目按 0 计入，不影响删除结果。 */
  static long bytes(BackupFileSystem fs, File file) {
    return bytes(fs, file, 0);
  }

  private static long bytes(BackupFileSystem fs, File file, int depth) {
    try {
      if (depth > 64) return 0;
      var node = fs.stat(file);
      if (node.type.equals("FILE")) return Math.max(0, node.size);
      if (!node.type.equals("DIRECTORY")) return 0;
      long total = 0;
      for (String name : fs.list(file)) total += bytes(fs, new File(file, name), depth + 1);
      return total;
    } catch (IOException | ArithmeticException unavailable) {
      return 0;
    }
  }

  private static String code(IOException failure) {
    String message = failure.getMessage();
    return message == null ? failure.getClass().getSimpleName() : message;
  }
}
