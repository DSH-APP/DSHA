package com.deepseekharness.app.runtime;

import com.deepseekharness.app.backup.*;
import java.io.*;
import java.util.*;
import java.util.function.Predicate;

/** Signed APK assets only; user imports use their authenticated archive protocols. */
public final class TarGzipExtractor {
  private TarGzipExtractor() {}

  public static void extract(File archive, File dest) throws IOException {
    try (InputStream in = new FileInputStream(archive)) {
      extractAuto(in, dest, 0);
    }
  }

  public static void extractAuto(InputStream in, File dest, int strip) throws IOException {
    extractSelected(in, dest, strip, name -> true);
  }

  public static void extractTar(InputStream in, File dest, int strip) throws IOException {
    extractAuto(in, dest, strip);
  }

  public static void extractSelected(
      InputStream in, File dest, int strip, Predicate<String> selected) throws IOException {
    extractSelected(in, dest, strip, selected, new AndroidBackupFileSystem());
  }

  static void extractSelected(
      InputStream in, File dest, int strip, Predicate<String> selected, BackupFileSystem fs)
      throws IOException {
    if (strip < 0 || strip > 64) throw new IOException("TAR_STRIP");
    ArchiveFileBoundary files = new ArchiveFileBoundary(fs, dest);
    Set<String> projected = new HashSet<>();
    LegacyTarReader.readTrustedAssets(
        in,
        (m, data) -> {
          String path = m.path;
          for (int i = 0; i < strip; i++) {
            int slash = path.indexOf('/');
            path = slash < 0 ? "" : path.substring(slash + 1);
          }
          if (path.isEmpty() || !selected.test(path)) {
            return;
          }
          if (!projected.add(path)) throw new IOException("TAR_DUPLICATE_PROJECTED_PATH:" + path);
          String link = m.target;
          if (m.type.equals("HARDLINK"))
            for (int i = 0; i < strip; i++) {
              int slash = link.indexOf('/');
              link = slash < 0 ? "" : link.substring(slash + 1);
            }
          LegacyTarReader.Member target =
              new LegacyTarReader.Member(path, m.type, link, m.size, m.mode);
          try {
            if (target.type.equals("FILE")) {
              if (target.size > 8L * 1024 * 1024 * 1024) throw new IOException("TAR_FILE_LIMIT");
              files.file(target, data);
            } else if (target.type.equals("DIRECTORY")) files.directory(target);
            else files.links.put(target.path, target);
          } catch (IOException error) {
            throw new IOException(
                "TAR_ENTRY_FAILURE:" + path + ":" + target.type + ":" + error.getMessage(), error);
          }
        },
        new BackupControl(null));
    files.finish();
  }
}
