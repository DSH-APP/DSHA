package com.deepseekharness.app.backup;

import java.io.File;
import java.io.IOException;

/** Read-only validation for host maintenance targets; a leaf link is only its private directory entry. */
public final class PrivateMaintenancePaths {
  private PrivateMaintenancePaths() {}

  public static File target(BackupFileSystem fs, File files, File target) throws IOException {
    File root = files.getAbsoluteFile(), value = target.getAbsoluteFile();
    String prefix = root.getPath() + File.separator;
    if (!value.getPath().startsWith(prefix))
      throw new IOException("MAINTENANCE_EXTERNAL_PRESERVED");
    String relative = value.getPath().substring(prefix.length()).replace(File.separatorChar, '/');
    BackupLimits.path(relative);
    if (relative.isEmpty()) throw new IOException("MAINTENANCE_PRIVATE_ROOT");
    BackupFileSystem.Node identity = fs.stat(root);
    if (!identity.type.equals("DIRECTORY")) throw new IOException("MAINTENANCE_PRIVATE_ROOT");
    File at = root;
    String[] parts = relative.split("/");
    boolean missing = false;
    for (int i = 0; i < parts.length; i++) {
      at = new File(at, parts[i]);
      if (missing) continue;
      BackupFileSystem.Node node = fs.stat(at);
      if (node.type.equals("MISSING")) {
        missing = true;
        continue;
      }
      if (i < parts.length - 1 && !node.type.equals("DIRECTORY"))
        throw new IOException("MAINTENANCE_PARENT_LINK_OR_SPECIAL");
      if (node.device != identity.device) throw new IOException("MAINTENANCE_FILESYSTEM_BOUNDARY");
    }
    return value;
  }
}
