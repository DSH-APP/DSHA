package com.deepseekharness.app.backup;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.util.Map;

/** Projects a verified credential file without changing it; raw and projected bytes bind each item. */
public final class CredentialFileBackupSource implements BackupSource {
  private final BackupSource original;

  public CredentialFileBackupSource(BackupSource original) {
    this.original = original;
  }

  public String id() {
    return original.id();
  }

  public String scope() {
    return original.scope();
  }

  public boolean external() {
    return original.external();
  }

  public String displayLocation() {
    return original.displayLocation();
  }

  public boolean containsLocal(File file) {
    return original.containsLocal(file);
  }

  public Map<String, Object> description() {
    return original.description();
  }

  public String category(Item item) {
    return original.category(item);
  }

  private byte[] raw(Item item, BackupControl control) throws IOException {
    try (InputStream input = original.open(item);
        ByteArrayOutputStream output = new ByteArrayOutputStream()) {
      byte[] buffer = new byte[8192];
      int count;
      while ((count = input.read(buffer)) != -1) {
        control.check();
        if (count > CredentialProjection.LIMIT - output.size())
          throw new IOException("CREDENTIAL_PROJECTION_LIMIT");
        output.write(buffer, 0, count);
      }
      return output.toByteArray();
    }
  }

  private static String hash(byte[] bytes) {
    return BackupArchive.hex(BackupArchive.sha().digest(bytes));
  }

  public void walk(Visit visitor, BackupControl control) throws IOException {
    original.walk(
        item -> {
          if (item.kind.equals("MISSING")
              || item.kind.equals("UNREADABLE")
              || item.kind.equals("EXCLUDED")) {
            visitor.item(item);
            return;
          }
          if (!item.kind.equals("FILE") || !item.path.isEmpty())
            throw new IOException("CREDENTIAL_PROJECTION_SOURCE_TYPE");
          byte[] raw = raw(item, control), portable = CredentialProjection.portable(raw);
          visitor.item(
              new Item(
                  "",
                  "FILE",
                  portable.length,
                  item.token + ":" + hash(raw) + ":" + hash(portable),
                  "",
                  "",
                  item.mode));
        },
        control);
  }

  public InputStream open(Item item) throws IOException {
    String[] token = item.token.split(":", -1);
    if (token.length != 3 || !token[1].matches("[a-f0-9]{64}") || !token[2].matches("[a-f0-9]{64}"))
      throw new IOException("CREDENTIAL_PROJECTION_ITEM");
    Item source = new Item("", "FILE", 0, token[0], "", "", item.mode);
    byte[] raw = raw(source, new BackupControl(null));
    if (!token[1].equals(hash(raw))) throw new IOException("CREDENTIAL_PROJECTION_SOURCE_CHANGED");
    byte[] portable = CredentialProjection.portable(raw);
    if (portable.length != item.size || !token[2].equals(hash(portable)))
      throw new IOException("CREDENTIAL_PROJECTION_SOURCE_CHANGED");
    return new ByteArrayInputStream(portable);
  }

  public void verify(Item item, String sha256, BackupControl control) throws IOException {
    if (item.kind.equals("EXCLUDED")) return;
    try (InputStream input = open(item)) {
      if (!sha256.equals(BackupArchive.digest(input, control)))
        throw new IOException("CREDENTIAL_PROJECTION_SOURCE_CHANGED");
    }
  }
}
