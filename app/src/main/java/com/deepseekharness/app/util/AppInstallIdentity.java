package com.deepseekharness.app.util;

import com.deepseekharness.app.backup.BackupFileSystem;
import java.io.File;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.UUID;

/** Local installation identity; Android storage is supplied through the API21 NOFOLLOW adapter. */
public final class AppInstallIdentity {
  public static final String FILE_NAME = "app-installation-identity-v1";
  private static final String PREFIX = "DSHA-INSTALLATION/1\n";

  private AppInstallIdentity() {}

  public static synchronized String loadOrCreate(BackupFileSystem fs, File directory)
      throws IOException {
    File root = directory.getAbsoluteFile();
    BackupFileSystem.Node originalRoot = fs.stat(root);
    if (!originalRoot.type.equals("DIRECTORY"))
      throw new IOException("INSTALLATION_IDENTITY_ROOT_INVALID");
    File record = fs.child(root, FILE_NAME);
    if (fs.stat(record).type.equals("MISSING")) {
      // Exclusive creation never replaces a partial, foreign or raced record.
      byte[] bytes = encode(UUID.randomUUID().toString());
      try (OutputStream output = fs.create(record)) {
        fs.mode(record, 0600);
        output.write(bytes);
      }
      fs.syncDirectory(root);
    }
    BackupFileSystem.Node file = fs.stat(record);
    if (!file.type.equals("FILE") || file.size < 1 || file.size > 256 || (file.mode & 0077) != 0)
      throw new IOException("INSTALLATION_IDENTITY_INVALID");
    String identity = decode(fs.small(record, 256));
    BackupFileSystem.Node currentRoot = fs.stat(root);
    if (!currentRoot.type.equals("DIRECTORY")
        || !originalRoot.key.equals(currentRoot.key)
        || originalRoot.device != currentRoot.device
        || !file.same(fs.stat(record))) throw new IOException("INSTALLATION_IDENTITY_CHANGED");
    return identity;
  }

  public static byte[] encode(String id) {
    if (!UUID.fromString(id).toString().equals(id))
      throw new IllegalArgumentException("INSTALLATION_IDENTITY_INVALID");
    return (PREFIX + AppIdentity.applicationId() + "\n" + id + "\n")
        .getBytes(StandardCharsets.UTF_8);
  }

  public static String decode(byte[] stored) throws IOException {
    if (stored == null || stored.length > 256)
      throw new IOException("INSTALLATION_IDENTITY_INVALID");
    String value = new String(stored, StandardCharsets.UTF_8);
    String expected = PREFIX + AppIdentity.applicationId() + "\n";
    if (!value.startsWith(expected) || !value.endsWith("\n"))
      throw new IOException("INSTALLATION_IDENTITY_INVALID");
    String found = value.substring(expected.length(), value.length() - 1);
    try {
      if (!UUID.fromString(found).toString().equals(found)) throw new IllegalArgumentException();
    } catch (IllegalArgumentException invalid) {
      throw new IOException("INSTALLATION_IDENTITY_INVALID", invalid);
    }
    return found;
  }
}
