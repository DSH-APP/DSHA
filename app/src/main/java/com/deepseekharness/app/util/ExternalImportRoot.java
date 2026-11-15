package com.deepseekharness.app.util;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;

/**
 * 外接设备导入中转目录的路径解析（纯逻辑，无 Android import）。
 *
 * <p>Android 16 上 {@code getFilesDir()} 可能返回 {@code /data/user/0/...}，而它是指向 {@code /data/data/...}
 * 的系统符号链接。旧实现在 {@code ExternalDeviceHost.importRoot()} 里要求 canonical 与 absolute 完全相同，
 * 于是把这个系统祖先别名误判成路径逃逸，抛出 {@code IMPORT_PATH_ALIAS}；外接设备初始化
 * （{@code requireReady() → toolchain()}）随后整体失败，表现为「已 USB 枚举到设备但 adb 不可用」。
 *
 * <p>这里的判据放行祖先别名，只拒绝叶子自身是符号链接的情况：导入目录被替换成指向别处的链接时，
 * 后续按 canonical 根做的 {@code startsWith} 与摘要核验会失去意义。返回值统一是 canonical 路径，
 * 调用方后续比较必须使用同一形态。
 */
public final class ExternalImportRoot {
  private ExternalImportRoot() {}

  public static File resolve(File configured) throws IOException {
    if (configured == null) throw new IOException("IMPORT_DIRECTORY");
    Path path = configured.toPath();
    // NOFOLLOW 只针对叶子：祖先按系统实际解析，允许 /data/user/0 这类系统别名。
    if (Files.isSymbolicLink(path)) throw new IOException("IMPORT_PATH_ALIAS");
    if (!Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS)) {
      if (!configured.mkdirs()) throw new IOException("IMPORT_DIRECTORY");
      // 创建与复核之间被换成链接时同样拒绝，不返回被劫持的目录。
      if (Files.isSymbolicLink(path)) throw new IOException("IMPORT_PATH_ALIAS");
    }
    return configured.getCanonicalFile();
  }
}
