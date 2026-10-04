package com.deepseekharness.app.util;

import com.deepseekharness.app.backup.*;
import java.io.*;

/** Fixed private share/export copy; the source descriptor identity is checked before publication. */
public final class BridgeFileSnapshot {
  private BridgeFileSnapshot() {}

  public static FileIntegrity.Result copy(
      BackupFileSystem fs, File source, File destination, long limit) throws IOException {
    var before = fs.stat(source);
    if (!before.type.equals("FILE") || before.size > limit)
      throw new IOException("BRIDGE_SOURCE_TYPE_OR_LIMIT");
    boolean created = false;
    try {
      FileIntegrity.Result result;
      try (InputStream input = fs.read(source, before)) {
        OutputStream owned = fs.create(destination);
        created = true;
        try (OutputStream output = owned) {
          result = FileIntegrity.copy(input, output, limit);
        }
      }
      if (result.size != before.size || !before.same(fs.stat(source)))
        throw new IOException("SOURCE_CHANGED");
      fs.syncDirectory(destination.getParentFile());
      return result;
    } catch (IOException error) {
      try {
        if (created && fs.stat(destination).type.equals("FILE")) fs.delete(destination);
      } catch (IOException cleanup) {
        error.addSuppressed(cleanup);
      }
      throw error;
    }
  }
}
