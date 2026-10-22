package com.deepseekharness.app.core;

import android.content.Context;
import com.deepseekharness.app.backup.AndroidBackupFileSystem;
import com.deepseekharness.app.util.AppInstallIdentity;
import com.deepseekharness.app.util.ColdInstallPackages;
import java.io.File;
import java.io.IOException;

/** API23-safe application identity storage: anchored lstat/O_EXCL/NOFOLLOW, 0600 and fsync. */
public final class InstallationIdentity {
  private InstallationIdentity() {}

  public static String loadOrCreate(Context context) throws IOException {
    var fs = new AndroidBackupFileSystem();
    var host =
        ColdInstallPackages.bindPrivateFiles(
            fs, new File(context.getApplicationInfo().dataDir), context.getFilesDir());
    String id = AppInstallIdentity.loadOrCreate(fs, host.files);
    host.verify(fs);
    return id;
  }
}
