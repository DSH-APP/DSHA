package com.deepseekharness.app.runtime;

import android.content.Context;
import com.deepseekharness.app.util.AndroidGroupMap;
import com.deepseekharness.app.util.Compat;
import java.io.*;
import java.nio.charset.StandardCharsets;

/** Rootfs group adaptation; launcher and application lifecycle never depend on a fixed app UID. */
final class AndroidGroups {
  static void prepare(Context context, File requested) throws IOException {
    File root = RuntimeAssetFiles.root(context, requested), group = new File(root, "etc/group");
    if (!group.isFile() || Compat.isSymbolicLink(group) || group.length() > 1024 * 1024)
      throw new IOException("ANDROID_GROUP_FILE_UNAVAILABLE");
    String current = Compat.readAll(group), status = Compat.readAll(new File("/proc/self/status"));
    String updated =
        AndroidGroupMap.appendMissing(
            current, android.os.Process.myUid(), AndroidGroupMap.groups(status));
    if (!updated.equals(current))
      RuntimeAssetFiles.write(context, group, updated.getBytes(StandardCharsets.UTF_8), false);
    File bashrc = new File(root, "etc/bash.bashrc");
    if (!bashrc.isFile() || Compat.isSymbolicLink(bashrc) || bashrc.length() > 1024 * 1024)
      throw new IOException("ANDROID_BASHRC_UNAVAILABLE");
    String before = Compat.readAll(bashrc),
        after = before.replace("$(groups) ", "$(groups 2>/dev/null) ");
    if (!before.equals(after))
      RuntimeAssetFiles.write(context, bashrc, after.getBytes(StandardCharsets.UTF_8), false);
  }

  private AndroidGroups() {}
}
