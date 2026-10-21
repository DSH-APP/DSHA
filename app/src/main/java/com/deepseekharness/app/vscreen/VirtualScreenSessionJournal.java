package com.deepseekharness.app.vscreen;

import com.deepseekharness.app.util.AppIdentity;
import java.io.File;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import org.json.JSONObject;

/** 本机秘密与清理凭据；不在guest/用户备份中，旧代次落盘不能覆盖新代次。 */
@androidx.annotation.RequiresApi(30)
final class VirtualScreenSessionJournal implements VirtualScreenJournal {
  private final Path path;
  private final String installation;
  private long revision = -1;

  VirtualScreenSessionJournal(File files) throws IOException {
    if (files == null) throw new IOException("VSCREEN_JOURNAL_ROOT_UNAVAILABLE");
    installation = com.deepseekharness.app.core.NioInstallationIdentity.loadOrCreate(files);
    path = files.toPath().resolve("vscreen-session-v1.json");
  }

  public synchronized JSONObject load() throws Exception {
    if (!Files.exists(path, LinkOption.NOFOLLOW_LINKS)) return null;
    if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)
        || Files.isSymbolicLink(path)
        || Files.size(path) > 8192) throw new IOException("VSCREEN_JOURNAL_INVALID");
    ByteBuffer bytes = ByteBuffer.allocate(8193);
    try (FileChannel input =
        FileChannel.open(path, StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS)) {
      while (bytes.hasRemaining() && input.read(bytes) >= 0) {}
    }
    if (bytes.position() > 8192) throw new IOException("VSCREEN_JOURNAL_LIMIT");
    JSONObject value =
        new JSONObject(new String(bytes.array(), 0, bytes.position(), StandardCharsets.UTF_8));
    if (value.optInt("schema") != 1
        || !installation.equals(value.optString("installation"))
        || !AppIdentity.applicationId().equals(value.optString("applicationId")))
      throw new IOException("VSCREEN_JOURNAL_FOREIGN");
    return value;
  }

  public synchronized boolean save(long next, JSONObject value) throws Exception {
    if (next < revision) return false;
    value
        .put("schema", 1)
        .put("installation", installation)
        .put("applicationId", AppIdentity.applicationId());
    byte[] bytes = value.toString().getBytes(StandardCharsets.UTF_8);
    if (bytes.length > 8192) throw new IOException("VSCREEN_JOURNAL_LIMIT");
    Path temporary = path.resolveSibling(path.getFileName() + ".pending");
    if (Files.isSymbolicLink(temporary)) throw new IOException("VSCREEN_JOURNAL_LINK");
    try (FileChannel output =
        FileChannel.open(
            temporary,
            StandardOpenOption.CREATE,
            StandardOpenOption.TRUNCATE_EXISTING,
            StandardOpenOption.WRITE,
            LinkOption.NOFOLLOW_LINKS)) {
      var permissions =
          Files.getFileAttributeView(
              temporary,
              java.nio.file.attribute.PosixFileAttributeView.class,
              LinkOption.NOFOLLOW_LINKS);
      if (permissions != null)
        permissions.setPermissions(
            java.nio.file.attribute.PosixFilePermissions.fromString("rw-------"));
      else if (File.separatorChar == '/') throw new IOException("VSCREEN_JOURNAL_PERMISSIONS");
      // Windows host checks exercise bytes/ordering only, never Android permission acceptance.
      ByteBuffer buffer = ByteBuffer.wrap(bytes);
      while (buffer.hasRemaining()) output.write(buffer);
      output.force(true);
    }
    Files.move(
        temporary, path, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
    revision = next;
    return true;
  }
}
