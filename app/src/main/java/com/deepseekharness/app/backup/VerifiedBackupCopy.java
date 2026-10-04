package com.deepseekharness.app.backup;

import java.io.*;
import java.util.*;

/** 复核本机先前完成的私有备份产物（可选密码加密）；不是对任意输入包的发送者认证。 */
public final class VerifiedBackupCopy {
  public final String id, sha256, integrity, scope;
  public final File artifact;
  public final long bytes, entries, created;
  public final Map<String, Object> metadata;

  private VerifiedBackupCopy(String id, File artifact, Map<String, Object> metadata)
      throws IOException {
    this.id = id;
    this.artifact = artifact;
    this.metadata = metadata;
    sha256 = BackupJson.string(metadata, "encryptedSha256");
    bytes = BackupJson.number(metadata, "encryptedBytes");
    entries = BackupJson.number(metadata, "entries");
    integrity = BackupJson.string(metadata, "integrity");
    created =
        metadata.get("createdAt") instanceof Number
            ? ((Number) metadata.get("createdAt")).longValue()
            : 0;
    Set<String> scopes = com.deepseekharness.app.util.BackupScopes.ALL;
    Object requested = metadata.get("requestedScope"), actual = metadata.get("roots");
    Set<String> present = new LinkedHashSet<>();
    if (metadata.containsKey("requestedScope") && !(requested instanceof String))
      throw new IOException("VERIFIED_COPY_SCOPE");
    if (requested == null) {
      if (!(actual instanceof List) || ((List<?>) actual).isEmpty())
        throw new IOException("VERIFIED_COPY_SCOPE");
      for (Object item : (List<?>) actual) {
        if (!(item instanceof Map) || !(((Map<?, ?>) item).get("scope") instanceof String))
          throw new IOException("VERIFIED_COPY_SCOPE");
        present.add((String) ((Map<?, ?>) item).get("scope"));
      }
      if (!scopes.containsAll(present)) throw new IOException("VERIFIED_COPY_SCOPE");
    }
    scope =
        requested instanceof String
            ? (String) requested
            : present.size() == 1 ? present.iterator().next() : "application";
    if (!scopes.contains(scope)) throw new IOException("VERIFIED_COPY_SCOPE");
    if (!sha256.matches("[a-f0-9]{64}")
        || bytes < 64
        || bytes > BackupLimits.BYTES + 256L * 1024 * 1024
        || entries < 0
        || entries > BackupLimits.ENTRIES
        || !Set.of("QUIESCENT", "EXTERNAL_CHECKED", "PARTIAL", "BEST_EFFORT").contains(integrity))
      throw new IOException("VERIFIED_COPY_RECORD");
  }

  public static VerifiedBackupCopy inspect(BackupFileSystem fs, File operations, String id)
      throws IOException {
    if (!id.matches(com.deepseekharness.app.util.Ids.UUID_PATTERN))
      throw new IOException("VERIFIED_COPY_ID");
    File directory = HostOperationArchive.locate(fs, operations, id),
        artifact = fs.child(directory, "portable.dshbak");
    var copy =
        new VerifiedBackupCopy(
            id,
            artifact,
            BackupJson.read(
                fs.small(fs.child(directory, "verified.json"), BackupLimits.MANIFEST),
                BackupLimits.MANIFEST));
    var file = fs.stat(artifact);
    if (!file.type.equals("FILE") || file.size != copy.bytes)
      throw new IOException("VERIFIED_COPY_CHANGED");
    return copy;
  }

  public void verify(BackupFileSystem fs, BackupControl control) throws IOException {
    try (InputStream input = fs.read(artifact, fs.stat(artifact))) {
      byte[] header = new byte[8];
      new DataInputStream(input).readFully(header);
      if (!Arrays.equals(header, PortableBackupCrypto.MAGIC) && !BackupArchive.hasMagic(header))
        throw new IOException("VERIFIED_COPY_FORMAT");
    }
    try (InputStream input = fs.read(artifact, fs.stat(artifact))) {
      if (!sha256.equals(BackupArchive.digest(input, control)))
        throw new IOException("VERIFIED_COPY_CHANGED");
    }
  }

  public boolean passwordProtected(BackupFileSystem fs) throws IOException {
    try (InputStream input = fs.read(artifact, fs.stat(artifact))) {
      byte[] header = new byte[8];
      new DataInputStream(input).readFully(header);
      if (Arrays.equals(header, PortableBackupCrypto.MAGIC)) return true;
      if (BackupArchive.hasMagic(header)) return false;
      throw new IOException("VERIFIED_COPY_FORMAT");
    }
  }

  public String result(boolean readback) {
    if (!readback) return "WRITTEN_UNVERIFIED";
    if (integrity.equals("PARTIAL")) return "PARTIAL_RESCUE";
    if (integrity.equals("BEST_EFFORT")) return "BEST_EFFORT_RESCUE";
    if (metadata.get("plugins") instanceof Map
        && !((Map<?, ?>) metadata.get("plugins")).isEmpty()
        && !Boolean.TRUE.equals(((Map<?, ?>) metadata.get("plugins")).get("complete")))
      return "DATA_SAVED_PLUGIN_WARNINGS";
    return "COMPLETE";
  }
}
