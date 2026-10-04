package com.deepseekharness.app;

import android.system.ErrnoException;
import android.system.Os;
import android.system.OsConstants;
import java.io.File;
import java.io.IOException;

/** Deletes only a named debug fixture directly beneath its supplied cache root. */
public final class OwnedAuditFiles {
  private OwnedAuditFiles() {}

  public static void remove(File cacheRoot, File target, String prefix) throws IOException {
    File parent = cacheRoot.getCanonicalFile(), candidate = target.getAbsoluteFile();
    if (!candidate.getParentFile().getCanonicalFile().equals(parent)
        || !candidate.getName().startsWith(prefix)
        || prefix.isEmpty()) throw new IOException("AUDIT_FIXTURE_OUTSIDE_CACHE");
    erase(candidate);
  }

  private static void erase(File file) throws IOException {
    try {
      int mode = Os.lstat(file.getPath()).st_mode;
      if (OsConstants.S_ISDIR(mode)) {
        Os.chmod(file.getPath(), mode | 0700);
        File[] children = file.listFiles();
        if (children == null) throw new IOException("AUDIT_FIXTURE_UNREADABLE:" + file);
        for (File child : children) erase(child);
      }
      if (!file.delete()) throw new IOException("AUDIT_FIXTURE_DELETE_FAILED:" + file);
    } catch (ErrnoException error) {
      if (error.errno != OsConstants.ENOENT)
        throw new IOException("AUDIT_FIXTURE_STAT_FAILED:" + file, error);
    }
  }
}
