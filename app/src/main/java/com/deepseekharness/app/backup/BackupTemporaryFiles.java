package com.deepseekharness.app.backup;

import java.io.File;
import java.io.IOException;

/** Owned staging files only; try-with-resources preserves the primary failure during cleanup. */
final class BackupTemporaryFiles implements AutoCloseable {
  private final BackupFileSystem fs;
  private final File[] files;

  BackupTemporaryFiles(BackupFileSystem fs, File... files) {
    this.fs = fs;
    this.files = files.clone();
  }

  @Override
  public void close() throws IOException {
    IOException failure = null;
    for (File file : files) {
      try {
        String type = fs.stat(file).type;
        if (type.equals("MISSING")) continue;
        if (!type.equals("FILE")) throw new IOException("TEMPORARY_FILE_TYPE");
        fs.delete(file);
      } catch (IOException error) {
        if (failure == null) failure = error;
        else if (failure != error) failure.addSuppressed(error);
      }
    }
    if (failure != null) throw failure;
  }
}
