package com.deepseekharness.app.util;

import com.deepseekharness.app.backup.BackupFileSystem;
import java.io.File;
import java.io.IOException;

/** Resolve only Android's framework data alias; the no_backup child remains NOFOLLOW. */
public final class WebSessionStorage {
  private WebSessionStorage() {}

  public static final class Home {
    public final File directory;
    private final File declaredData, declaredHome, authority;
    private final BackupFileSystem.Node dataIdentity, homeIdentity;

    private Home(BackupFileSystem fs, File data, File home) throws IOException {
      if (data == null
          || home == null
          || !data.isAbsolute()
          || !home.isAbsolute()
          || !"no_backup".equals(home.getName())
          || home.getParentFile() == null) throw new IOException("WEB_SESSION_PRIVATE_DIRECTORY");
      declaredData = data;
      declaredHome = home;
      authority = data.getCanonicalFile();
      if (!authority.equals(home.getParentFile().getCanonicalFile()))
        throw new IOException("WEB_SESSION_PRIVATE_DIRECTORY");
      dataIdentity = fs.stat(authority);
      if (!dataIdentity.type.equals("DIRECTORY"))
        throw new IOException("WEB_SESSION_PRIVATE_DIRECTORY");
      directory = fs.child(authority, "no_backup");
      homeIdentity = fs.stat(directory);
      if (!homeIdentity.type.equals("DIRECTORY"))
        throw new IOException("WEB_SESSION_PRIVATE_DIRECTORY");
      verify(fs);
    }

    public void verify(BackupFileSystem fs) throws IOException {
      if (!authority.equals(declaredData.getCanonicalFile())
          || !authority.equals(declaredHome.getParentFile().getCanonicalFile())
          || !sameDirectory(dataIdentity, fs.stat(authority))
          || !sameDirectory(homeIdentity, fs.stat(directory)))
        throw new IOException("WEB_SESSION_PRIVATE_DIRECTORY_CHANGED");
    }

    private static boolean sameDirectory(
        BackupFileSystem.Node before, BackupFileSystem.Node after) {
      return before.type.equals("DIRECTORY")
          && after.type.equals("DIRECTORY")
          && before.device == after.device
          && before.key.equals(after.key);
    }
  }

  public static Home bind(BackupFileSystem fs, File data, File home) throws IOException {
    return new Home(fs, data, home);
  }
}
