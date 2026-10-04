package com.deepseekharness.app.backup;

import java.io.*;
import java.util.Map;

/** A new readable export from a verified old copy; old ciphertext is always retained. */
public final class PasswordFreeBackupExport {
  public final File artifact;
  public final String sha256;
  public final Map<String, Object> manifest;

  private PasswordFreeBackupExport(File artifact, String sha256, Map<String, Object> manifest) {
    this.artifact = artifact;
    this.sha256 = sha256;
    this.manifest = manifest;
  }

  public static PasswordFreeBackupExport prepare(
      BackupFileSystem fs,
      VerifiedBackupCopy source,
      File operation,
      char[] originalPassword,
      BackupControl control)
      throws IOException {
    source.verify(fs, control);
    File decrypted = fs.child(operation, "reexport-authenticated.dshdata"),
        output = fs.child(operation, "reexport-plain.dshbak");
    File input = source.artifact;
    IOException failed = null;
    try {
      if (source.passwordProtected(fs)) {
        if (originalPassword == null) throw new IOException("ORIGINAL_BACKUP_PASSWORD_REQUIRED");
        java.security.MessageDigest checked = BackupArchive.sha();
        try (InputStream encrypted =
                new java.security.DigestInputStream(
                    fs.read(source.artifact, fs.stat(source.artifact)), checked);
            OutputStream plaintext = fs.create(decrypted)) {
          PortableBackupCrypto.decrypt(encrypted, plaintext, originalPassword, control);
        }
        if (!source.sha256.equals(BackupArchive.hex(checked.digest())))
          throw new IOException("VERIFIED_COPY_CHANGED");
        input = decrypted;
      }
      Map<String, Object> manifest;
      java.security.MessageDigest checked = BackupArchive.sha();
      try (InputStream old =
              new java.security.DigestInputStream(fs.read(input, fs.stat(input)), checked);
          OutputStream fresh = fs.create(output)) {
        manifest = BackupArchive.copyUnencrypted(old, fresh, control);
      }
      if (input.equals(source.artifact)
          && !source.sha256.equals(BackupArchive.hex(checked.digest())))
        throw new IOException("VERIFIED_COPY_CHANGED");
      try (InputStream fresh = fs.read(output, fs.stat(output))) {
        BackupArchive.read(fresh, null, control);
      }
      String hash;
      try (InputStream fresh = fs.read(output, fs.stat(output))) {
        hash = BackupArchive.digest(fresh, control);
      }
      return new PasswordFreeBackupExport(output, hash, manifest);
    } catch (IOException failure) {
      failed = failure;
      try {
        if (fs.stat(output).type.equals("FILE")) fs.delete(output);
      } catch (IOException cleanup) {
        failure.addSuppressed(cleanup);
      }
      throw failure;
    } finally {
      try {
        if (fs.stat(decrypted).type.equals("FILE")) fs.delete(decrypted);
      } catch (IOException cleanup) {
        if (failed != null) failed.addSuppressed(cleanup);
        else throw cleanup;
      }
    }
  }
}
