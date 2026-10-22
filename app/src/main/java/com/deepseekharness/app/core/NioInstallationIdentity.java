package com.deepseekharness.app.core;

import com.deepseekharness.app.util.AppInstallIdentity;
import com.deepseekharness.app.util.AppIdentity;

import java.io.File;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.UUID;

/** Local installation identity; it is never imported from a user archive. */
@androidx.annotation.RequiresApi(26)
public final class NioInstallationIdentity {
  private static final String FILE_NAME = AppInstallIdentity.FILE_NAME;
  private static final String PREFIX = "DSHA-INSTALLATION/1\n";

  private NioInstallationIdentity() {}

  public static String loadOrCreate(File appFilesDirectory) throws IOException {
    synchronized (AppInstallIdentity.class) {
      return load(appFilesDirectory);
    }
  }

  private static String load(File appFilesDirectory) throws IOException {
    Path root = appFilesDirectory.toPath();
    if (!Files.isDirectory(root, LinkOption.NOFOLLOW_LINKS) || Files.isSymbolicLink(root))
      throw new IOException("INSTALLATION_IDENTITY_ROOT_INVALID");
    Path path = root.resolve(FILE_NAME);
    String id = UUID.randomUUID().toString();
    byte[] bytes =
        (PREFIX + AppIdentity.applicationId() + "\n" + id + "\n").getBytes(StandardCharsets.UTF_8);
    // Android may deny FileStore/mount-table discovery even for our own files.
    // The provider's NOFOLLOW attribute view supplies the creation contract
    // without enumerating mounts; Unix creation still requires private mode.
    var permissions =
        Files.getFileAttributeView(
            root, java.nio.file.attribute.PosixFileAttributeView.class, LinkOption.NOFOLLOW_LINKS);
    if (permissions == null && File.separatorChar == '/')
      throw new IOException("INSTALLATION_IDENTITY_PERMISSIONS");
    try (FileChannel channel =
        FileChannel.open(
            path,
            java.util.Set.of(
                StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS),
            permissions != null
                ? new java.nio.file.attribute.FileAttribute<?>[] {
                  java.nio.file.attribute.PosixFilePermissions.asFileAttribute(
                      java.nio.file.attribute.PosixFilePermissions.fromString("rw-------"))
                }
                : new java.nio.file.attribute.FileAttribute<?>[0])) {
      ByteBuffer buffer = ByteBuffer.wrap(bytes);
      while (buffer.hasRemaining()) channel.write(buffer);
      channel.force(true);
    } catch (FileAlreadyExistsException existing) {
      // A partial or foreign record is an error, never a reason to mint another identity.
    }
    if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)
        || Files.isSymbolicLink(path)
        || Files.size(path) > 256) throw new IOException("INSTALLATION_IDENTITY_INVALID");
    byte[] stored;
    try (FileChannel channel =
        FileChannel.open(path, StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS)) {
      ByteBuffer buffer = ByteBuffer.allocate(257);
      while (buffer.hasRemaining() && channel.read(buffer) >= 0) {}
      if (buffer.position() > 256) throw new IOException("INSTALLATION_IDENTITY_INVALID");
      stored = new byte[buffer.position()];
      buffer.flip();
      buffer.get(stored);
    }
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
