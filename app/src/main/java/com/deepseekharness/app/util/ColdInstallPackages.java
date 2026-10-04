package com.deepseekharness.app.util;

import com.deepseekharness.app.backup.BackupFileSystem;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collections;

/** Bind the selected installer to one signed, extracted package slot, never a later download. */
public final class ColdInstallPackages {
  private ColdInstallPackages() {}

  public static final class HostRoot {
    public final File files;
    private final File declaredData, declaredFiles, authority;
    private final BackupFileSystem.Node authorityIdentity, filesIdentity;

    private HostRoot(BackupFileSystem fs, File appData, File providedFiles) throws IOException {
      files = privateFiles(fs, appData, providedFiles);
      declaredData = appData;
      declaredFiles = providedFiles;
      authority = appData.getCanonicalFile();
      authorityIdentity = fs.stat(authority);
      filesIdentity = fs.stat(files);
      verify(fs);
    }

    private static boolean sameDirectory(
        BackupFileSystem.Node expected, BackupFileSystem.Node current) {
      return current.type.equals("DIRECTORY")
          && expected.type.equals("DIRECTORY")
          && expected.device == current.device
          && expected.key.equals(current.key);
    }

    public void verify(BackupFileSystem fs) throws IOException {
      if (!authority.equals(files.getParentFile())
          || !authority.equals(declaredData.getCanonicalFile())
          || !authority.equals(declaredFiles.getParentFile().getCanonicalFile())
          || !sameDirectory(authorityIdentity, fs.stat(authority))
          || !sameDirectory(filesIdentity, fs.stat(files)))
        throw new IOException("COLD_PACKAGE_HOST_ROOT_CHANGED");
    }
  }

  public static HostRoot bindPrivateFiles(BackupFileSystem fs, File appData, File providedFiles)
      throws IOException {
    return new HostRoot(fs, appData, providedFiles);
  }

  /** Resolve only the framework-provided app data authority, never writable guest descendants. */
  public static File privateFiles(BackupFileSystem fs, File appData, File providedFiles)
      throws IOException {
    if (appData == null
        || providedFiles == null
        || !appData.isAbsolute()
        || !providedFiles.isAbsolute()
        || !"files".equals(providedFiles.getName()))
      throw new IOException("COLD_PACKAGE_HOST_ROOT");
    File authority = appData.getCanonicalFile();
    File parent = providedFiles.getParentFile();
    if (parent == null
        || !authority.equals(parent.getCanonicalFile())
        || !fs.stat(authority).type.equals("DIRECTORY"))
      throw new IOException("COLD_PACKAGE_HOST_ROOT");
    // /data/data <-> /data/user/0 belongs to Android. files/linux/... belongs
    // to the app and must still pass the original NOFOLLOW filesystem guard.
    File files = fs.child(authority, "files");
    if (!fs.stat(files).type.equals("DIRECTORY"))
      throw new IOException("COLD_PACKAGE_FILES_LINK_OR_MISSING");
    return files;
  }

  public static File ownedSlot(BackupFileSystem fs, File privateFiles, String name)
      throws IOException {
    if (name == null || !name.matches("\\.dsha-bundled-tools-" + Ids.UUID_PATTERN))
      throw new IOException("COLD_PACKAGE_SLOT_NAME");
    return fs.child(privateFiles, GuestPaths.ROOT_RELATIVE + "/root/" + name);
  }

  public static String fingerprint(BackupFileSystem fs, HostRoot host, String name)
      throws IOException {
    host.verify(fs);
    String sha = fingerprint(fs, ownedSlot(fs, host.files, name));
    host.verify(fs);
    return sha;
  }

  public static File ownedSlot(
      BackupFileSystem fs,
      HostRoot host,
      com.deepseekharness.app.backup.ColdInstallTransaction.Candidate candidate,
      String name)
      throws IOException {
    if (name == null || !name.matches("\\.dsha-bundled-tools-" + Ids.UUID_PATTERN))
      throw new IOException("COLD_PACKAGE_SLOT_NAME");
    candidate.verify(fs, host);
    return fs.child(candidate.root(), "root/" + name);
  }

  public static String fingerprint(
      BackupFileSystem fs,
      HostRoot host,
      com.deepseekharness.app.backup.ColdInstallTransaction.Candidate candidate,
      String name)
      throws IOException {
    candidate.verify(fs, host);
    String sha = fingerprint(fs, ownedSlot(fs, host, candidate, name));
    candidate.verify(fs, host);
    return sha;
  }

  public static String fingerprint(BackupFileSystem fs, File slot) throws IOException {
    var root = fs.stat(slot);
    if (!root.type.equals("DIRECTORY")) throw new IOException("COLD_PACKAGE_SLOT_TYPE");
    var names = new ArrayList<>(fs.list(slot));
    Collections.sort(names);
    if (names.size() < 4
        || names.size() > 128
        || !names.containsAll(java.util.List.of("SHA256SUMS", "packages.txt", "version.txt")))
      throw new IOException("COLD_PACKAGE_SLOT_CONTENTS");
    MessageDigest digest;
    try {
      digest = MessageDigest.getInstance("SHA-256");
    } catch (NoSuchAlgorithmException impossible) {
      throw new AssertionError(impossible);
    }
    long total = 0;
    for (String name : names) {
      if (!name.matches("[A-Za-z0-9_.+~%-]+")
          || !(name.endsWith(".deb")
              || java.util.Set.of("SHA256SUMS", "packages.txt", "version.txt").contains(name)))
        throw new IOException("COLD_PACKAGE_SLOT_CONTENTS");
      File file = fs.child(slot, name);
      var before = fs.stat(file);
      if (!before.type.equals("FILE") || before.size < 1 || before.size > 512L * 1024 * 1024)
        throw new IOException("COLD_PACKAGE_FILE_TYPE");
      digest.update((name + "\n" + before.size + "\n").getBytes(StandardCharsets.UTF_8));
      long read = 0;
      try (InputStream input = fs.read(file, before)) {
        byte[] bytes = new byte[65536];
        int count;
        while ((count = input.read(bytes)) != -1) {
          if (Thread.currentThread().isInterrupted())
            throw new java.io.InterruptedIOException("CANCELLED");
          read += count;
          total += count;
          if (read > before.size || total > 512L * 1024 * 1024)
            throw new IOException("COLD_PACKAGE_SLOT_LIMIT");
          digest.update(bytes, 0, count);
        }
      }
      if (read != before.size || !before.same(fs.stat(file)))
        throw new IOException("COLD_PACKAGE_CHANGED");
    }
    var afterNames = new ArrayList<>(fs.list(slot));
    Collections.sort(afterNames);
    if (!root.same(fs.stat(slot)) || !names.equals(afterNames))
      throw new IOException("COLD_PACKAGE_CHANGED");
    StringBuilder sha = new StringBuilder();
    for (byte value : digest.digest())
      sha.append(String.format(java.util.Locale.ROOT, "%02x", value & 255));
    return sha.toString();
  }
}
