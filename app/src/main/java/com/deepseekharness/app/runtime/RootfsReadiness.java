package com.deepseekharness.app.runtime;

import android.system.Os;
import android.system.OsConstants;
import com.deepseekharness.app.util.DocumentPaths;
import com.deepseekharness.app.util.RootfsBootstrap;
import java.io.File;
import java.io.IOException;

/** 解析实际 guest Bash ELF 与加载器，并保留正常 guest 绝对链接。 */
final class RootfsReadiness {
  static boolean ready(File root) {
    try {
      DocumentPaths paths =
          new DocumentPaths(
              root.getParentFile().getParentFile(),
              file -> {
                try {
                  return OsConstants.S_ISLNK(Os.lstat(file.getPath()).st_mode)
                      ? Os.readlink(file.getPath())
                      : null;
                } catch (android.system.ErrnoException error) {
                  if (error.errno == OsConstants.ENOENT) return null;
                  throw new IOException(error);
                }
              });
      return RootfsBootstrap.ready(
          path -> {
            File resolved = paths.resolve("linux/ubuntu" + path, true);
            try {
              int mode = Os.lstat(resolved.getPath()).st_mode;
              if (OsConstants.S_ISREG(mode) && (mode & 0111) == 0)
                throw new IOException("Missing executable mode");
            } catch (android.system.ErrnoException error) {
              throw new IOException(error);
            }
            return resolved;
          });
    } catch (IOException error) {
      return false;
    }
  }

  private RootfsReadiness() {}
}
