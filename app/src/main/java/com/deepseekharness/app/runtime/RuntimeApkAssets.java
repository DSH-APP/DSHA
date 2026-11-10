package com.deepseekharness.app.runtime;

import android.content.Context;
import android.system.Os;
import android.system.OsConstants;
import com.deepseekharness.app.backup.BackupArchive;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Reuses immutable APK input bytes, never a proof about mutable published launcher files. */
final class RuntimeApkAssets {
  private static final Cache CACHE = new Cache();
  private static final List<String> PRELOADS =
      List.of("bridge-token-compat.cjs", "native-consent.cjs", "bridge-port.cjs");

  private RuntimeApkAssets() {}

  static Snapshot get(Context context) throws IOException {
    var assets = context.getAssets();
    return CACHE.get(
        new Source() {
          public Object owner() {
            return assets;
          }

          public String identity() {
            try {
              var info = context.getApplicationInfo();
              StringBuilder result = new StringBuilder();
              if (!appendApk(result, info.sourceDir)) return null;
              if (info.splitSourceDirs != null)
                for (String split : info.splitSourceDirs)
                  if (!appendApk(result, split)) return null;
              return result.toString();
            } catch (Exception unavailable) {
              // Unverifiable or writable inputs retain the uncached AssetManager path.
              return null;
            }
          }

          public InputStream open(String name) throws IOException {
            return assets.open(name);
          }
        });
  }

  private static boolean appendApk(StringBuilder result, String path) throws Exception {
    if (path == null) return false;
    File apk = new File(path), parent = apk.getParentFile();
    if (parent == null || !apk.getCanonicalPath().equals(path)) return false;
    var file = Os.lstat(path);
    var directory = Os.lstat(parent.getPath());
    int uid = android.os.Process.myUid();
    if (!OsConstants.S_ISREG(file.st_mode)
        || !OsConstants.S_ISDIR(directory.st_mode)
        || !immutableToApp(file.st_uid, file.st_mode, uid)
        || !immutableToApp(directory.st_uid, directory.st_mode, uid)
        || file.st_size < 1) return false;
    result
        .append(path.length())
        .append(':')
        .append(path)
        .append(':')
        .append(directory.st_dev)
        .append(':')
        .append(directory.st_ino)
        .append(':')
        .append(directory.st_uid)
        .append(':')
        .append(directory.st_mode)
        .append(':')
        .append(file.st_dev)
        .append(':')
        .append(file.st_ino)
        .append(':')
        .append(file.st_size)
        .append(':')
        .append(file.st_mtime)
        .append(':')
        .append(file.st_mtim.tv_nsec)
        .append(':')
        .append(file.st_ctime)
        .append(':')
        .append(file.st_ctim.tv_nsec)
        .append(':')
        .append(file.st_uid)
        .append(':')
        .append(file.st_mode)
        .append(';');
    return true;
  }

  static boolean immutableToApp(int ownerUid, int mode, int appUid) {
    // An app-owned read-only file can be chmod'ed; writable parents can replace an APK leaf.
    return ownerUid != appUid && (mode & 0022) == 0;
  }

  interface Source {
    Object owner();

    /** Null means no immutable APK identity could be established. */
    String identity();

    InputStream open(String name) throws IOException;
  }

  static final class Snapshot {
    final Map<String, byte[]> preloads;
    final String preloadId;
    final String packageProofSha256;

    Snapshot(Map<String, byte[]> preloads, String preloadId, String packageProofSha256) {
      this.preloads = java.util.Collections.unmodifiableMap(preloads);
      this.preloadId = preloadId;
      this.packageProofSha256 = packageProofSha256;
    }
  }

  static final class Cache {
    private Object owner;
    private String identity;
    private Snapshot snapshot;

    synchronized Snapshot get(Source source) throws IOException {
      Object currentOwner = source.owner();
      String before = source.identity();
      if (before != null && currentOwner == owner && before.equals(identity)) return snapshot;
      // A new identity or uncertain input cannot resurrect an older cached proof after failure.
      owner = null;
      identity = null;
      snapshot = null;
      var bytes = new LinkedHashMap<String, byte[]>();
      var digest = BackupArchive.sha();
      for (String name : PRELOADS) {
        byte[] content = read(source, name, 512 * 1024);
        bytes.put(name, content);
        digest.update(name.getBytes(java.nio.charset.StandardCharsets.US_ASCII));
        digest.update(content);
      }
      String preloadId = BackupArchive.hex(digest.digest());
      String proof =
          BackupArchive.hex(
              BackupArchive.sha()
                  .digest(read(source, "managed-package-proofs.json", 2 * 1024 * 1024)));
      Snapshot loaded = new Snapshot(bytes, preloadId, proof);
      if (before != null) {
        if (currentOwner != source.owner() || !before.equals(source.identity()))
          throw new IOException("LAUNCHER_APK_ASSETS_CHANGED");
        owner = currentOwner;
        identity = before;
        snapshot = loaded;
      }
      return loaded;
    }

    private static byte[] read(Source source, String name, int limit) throws IOException {
      try (InputStream input = source.open(name);
          ByteArrayOutputStream output = new ByteArrayOutputStream()) {
        byte[] buffer = new byte[8192];
        int count;
        while ((count = input.read(buffer)) != -1) {
          if (output.size() + count > limit)
            throw new IOException(
                name.equals("managed-package-proofs.json")
                    ? "PACKAGE_PROOFS_LIMIT"
                    : "LAUNCHER_ASSET_LIMIT");
          output.write(buffer, 0, count);
        }
        return output.toByteArray();
      }
    }
  }
}
