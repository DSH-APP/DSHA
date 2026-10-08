package com.deepseekharness.app.runtime;

import com.deepseekharness.app.backup.BackupArchive;
import com.deepseekharness.app.backup.BackupFileSystem;
import java.io.File;
import java.io.IOException;
import java.util.Arrays;

/** 替换受管网页适配前保留现场原件；同摘要复用，不自动删除或执行历史脚本。 */
final class ManagedOverlayEdits {
  private static final int LIMIT = 32 * 1024 * 1024;
  static final String DIRECTORY = "runtime-overlay-edits";

  private ManagedOverlayEdits() {}

  static boolean client(String relative) {
    return relative.endsWith("/root/dsha-app-integration/client.js");
  }

  static File retain(
      BackupFileSystem fs, File authority, File source, BackupFileSystem.Node expected)
      throws IOException {
    if (!expected.type.equals("FILE")) return null;
    if (expected.size > LIMIT) throw new IOException("OVERLAY_EDIT_LIMIT");
    byte[] bytes = fs.small(source, LIMIT);
    if (!expected.same(fs.stat(source))) throw new IOException("OVERLAY_EDIT_CHANGED");
    String digest = BackupArchive.hex(BackupArchive.sha().digest(bytes));
    String relative = DIRECTORY + "/dsha-app-integration-client-" + digest + ".js";
    fs.parents(authority, relative);
    File retained = fs.child(authority, relative);
    var existing = fs.stat(retained);
    if (existing.type.equals("MISSING")) {
      fs.atomic(retained.getParentFile(), retained.getName(), bytes);
      fs.syncDirectory(retained.getParentFile());
    } else if (!existing.type.equals("FILE")
        || existing.size != bytes.length
        || !Arrays.equals(fs.small(retained, LIMIT), bytes)) {
      throw new IOException("OVERLAY_EDIT_CONFLICT");
    }
    if (!Arrays.equals(fs.small(retained, LIMIT), bytes) || !expected.same(fs.stat(source)))
      throw new IOException("OVERLAY_EDIT_CHANGED");
    return retained;
  }
}
