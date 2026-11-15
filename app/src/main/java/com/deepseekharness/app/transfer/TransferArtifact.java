package com.deepseekharness.app.transfer;

import java.io.IOException;
import java.util.Locale;
import org.json.JSONObject;

/** Public, credential-free identity supplied by the authenticated file broker. */
public final class TransferArtifact {
  public static final long MAX_BYTES = 5_000_000_000L;
  public final String id, name, mime, sha256, version;
  public final long bytes;

  public TransferArtifact(
      String id, String name, String mime, long bytes, String sha256, String version)
      throws IOException {
    if (id == null || !id.matches("[a-zA-Z0-9_-]{1,256}"))
      throw new IOException("FILE_ARTIFACT_ID");
    if (!safeName(name)) throw new IOException("FILE_NAME_INVALID");
    if (mime == null
        || mime.length() > 160
        || !mime.matches("[a-zA-Z0-9!#$&^_.+-]+/[a-zA-Z0-9!#$&^_.+-]+"))
      throw new IOException("FILE_MIME_INVALID");
    if (bytes < 0 || bytes > MAX_BYTES) throw new IOException("FILE_LIMIT_5GB");
    if (sha256 == null || !sha256.matches("[a-fA-F0-9]{64}"))
      throw new IOException("FILE_SHA256_INVALID");
    if (version == null
        || version.isEmpty()
        || version.length() > 512
        || version.chars().anyMatch(c -> c < 32 || c == 127))
      throw new IOException("FILE_VERSION_INVALID");
    this.id = id;
    this.name = name;
    this.mime = mime.toLowerCase(Locale.ROOT);
    this.bytes = bytes;
    this.sha256 = sha256.toLowerCase(Locale.ROOT);
    this.version = version;
  }

  public static boolean safeName(String value) {
    return value != null
        && !value.isBlank()
        && value.length() <= 240
        && value.getBytes(java.nio.charset.StandardCharsets.UTF_8).length <= 240
        && !value.equals(".")
        && !value.equals("..")
        && value.chars().noneMatch(c -> c < 32 || c == 127 || c == '/' || c == '\\');
  }

  public static TransferArtifact fromJson(JSONObject value) throws Exception {
    if (value == null) throw new IOException("FILE_ARTIFACT_MISSING");
    java.util.Set<String> expected =
        java.util.Set.of("id", "name", "mime", "bytes", "sha256", "version");
    java.util.Iterator<String> keys = value.keys();
    while (keys.hasNext())
      if (!expected.contains(keys.next())) throw new IOException("FILE_ARTIFACT_FIELDS");
    for (String key : expected) {
      Object field = value.get(key);
      if (key.equals("bytes")) {
        if (!(field instanceof Integer) && !(field instanceof Long))
          throw new IOException("FILE_SIZE_INVALID");
      } else if (!(field instanceof String)) throw new IOException("FILE_ARTIFACT_FIELDS");
    }
    return new TransferArtifact(
        value.getString("id"),
        value.getString("name"),
        value.getString("mime"),
        value.getLong("bytes"),
        value.getString("sha256"),
        value.getString("version"));
  }

  public JSONObject toJson() throws Exception {
    return new JSONObject()
        .put("id", id)
        .put("name", name)
        .put("mime", mime)
        .put("bytes", bytes)
        .put("sha256", sha256)
        .put("version", version);
  }

  public void requireSame(TransferArtifact other) throws IOException {
    if (other == null
        || !id.equals(other.id)
        || bytes != other.bytes
        || !sha256.equals(other.sha256)
        || !version.equals(other.version)) throw new IOException("FILE_SOURCE_CHANGED");
  }
}
