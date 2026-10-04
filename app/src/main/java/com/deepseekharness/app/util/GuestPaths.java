package com.deepseekharness.app.util;

import java.io.File;
import java.io.IOException;

/** Translate only targets belonging to the explicit runtime root; never bind /data globally. */
public final class GuestPaths {
  public static final String ROOT_RELATIVE = "linux/ubuntu";

  private GuestPaths() {}

  public static File rootfs(File files) {
    return new File(files, ROOT_RELATIVE);
  }

  public static String toGuest(File rootfs, File target) throws IOException {
    if (rootfs == null || target == null) throw new IOException("PLUGIN_GRAPH_TARGET");
    String root = rootfs.getCanonicalPath();
    String value = target.getCanonicalPath();
    String prefix = root.endsWith(File.separator) ? root : root + File.separator;
    if (!value.startsWith(prefix)) throw new IOException("PLUGIN_GRAPH_TARGET");
    String relative = value.substring(prefix.length()).replace(File.separatorChar, '/');
    if (relative.isEmpty() || relative.indexOf('\0') >= 0)
      throw new IOException("PLUGIN_GRAPH_TARGET");
    return "/" + relative;
  }
}
