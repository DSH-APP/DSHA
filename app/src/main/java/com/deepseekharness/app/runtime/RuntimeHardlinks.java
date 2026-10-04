package com.deepseekharness.app.runtime;

import android.content.Context;
import com.deepseekharness.app.backup.AndroidBackupFileSystem;
import com.deepseekharness.app.util.Compat;
import java.io.File;
import java.io.IOException;

/** 在独占的宿主槽位中验证硬链接，不使用可预测的用户文件名。 */
final class RuntimeHardlinks {
  private final Context context;
  private final File rootfs;
  private final RuntimeHostPorts ports;
  private Boolean supported;

  RuntimeHardlinks(Context context, File rootfs, RuntimeHostPorts ports) {
    this.context = context;
    this.rootfs = rootfs;
    this.ports = ports;
  }

  synchronized boolean supported() {
    if (supported != null) return supported;
    var fs = new AndroidBackupFileSystem();
    File root = null;
    String slot = ".dsha-linkprobe-" + java.util.UUID.randomUUID();
    boolean valid = false;
    try {
      root = RuntimeAssetFiles.root(context, rootfs);
      File directory = fs.child(root, slot);
      if (!fs.stat(directory).type.equals("MISSING")) throw new IOException("LINK_PROBE_COLLISION");
      fs.directory(directory);
      fs.atomic(directory, "source", new byte[] {'o', 'k'});
      File source = fs.child(directory, "source"), target = fs.child(directory, "linked");
      Compat.link(source, target);
      var a = fs.stat(source);
      var b = fs.stat(target);
      valid =
          a.type.equals("FILE")
              && b.type.equals("FILE")
              && a.size == 2
              && b.size == 2
              && a.device == b.device
              && a.key.equals(b.key);
    } catch (IOException | RuntimeException error) {
      ports.record("HARDLINK_PROBE_UNAVAILABLE", error.getClass().getSimpleName());
    } finally {
      if (root != null)
        try {
          fs.removeOwned(root, slot);
        } catch (IOException error) {
          ports.record("HARDLINK_PROBE_RETAINED", error.getClass().getSimpleName());
        }
    }
    supported = valid;
    return valid;
  }
}
