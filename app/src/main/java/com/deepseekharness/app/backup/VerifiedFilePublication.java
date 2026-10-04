package com.deepseekharness.app.backup;

import com.deepseekharness.app.util.FileIntegrity;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.FilterInputStream;
import java.io.OutputStream;
import java.util.UUID;

/** Copies verified bytes to an exclusive sibling before publishing; existing targets stay intact. */
public final class VerifiedFilePublication {
  private VerifiedFilePublication() {}

  public static void publish(
      BackupFileSystem fs, File source, File target, FileIntegrity.Result expected)
      throws IOException {
    publish(fs, source, target, expected, new BackupControl(null));
  }

  public static void publish(
      BackupFileSystem fs,
      File source,
      File target,
      FileIntegrity.Result expected,
      BackupControl control)
      throws IOException {
    control.check();
    if (expected == null || expected.size < 0) throw new IOException("EXPORT_EXPECTED_INVALID");
    var before = fs.stat(source);
    if (!before.type.equals("FILE") || before.size != expected.size)
      throw new IOException("EXPORT_SOURCE_CHANGED");
    File parent = target.getAbsoluteFile().getParentFile();
    target = fs.child(parent, target.getName());
    if (!fs.stat(target).type.equals("MISSING")) throw new IOException("EXPORT_TARGET_EXISTS");
    File part = fs.child(parent, ".dsha-export-" + UUID.randomUUID() + ".part");
    boolean created = false;
    try {
      try (InputStream input = checked(fs.read(source, before), control)) {
        OutputStream owned = fs.create(part);
        created = true;
        try (OutputStream output = owned) {
          if (!expected.matches(FileIntegrity.copy(input, output, expected.size)))
            throw new IOException("EXPORT_SOURCE_CHANGED");
        }
      }
      if (!before.same(fs.stat(source))) throw new IOException("EXPORT_SOURCE_CHANGED");
      var staged = fs.stat(part);
      try (InputStream input = checked(fs.read(part, staged), control)) {
        if (!expected.matches(FileIntegrity.copy(input, null, expected.size)))
          throw new IOException("EXPORT_DIGEST_MISMATCH");
      }
      if (!staged.same(fs.stat(part))) throw new IOException("EXPORT_STAGE_CHANGED");
      control.check();
      fs.move(part, target);
      fs.syncDirectory(parent);
      if (!staged.same(fs.stat(target))) throw new IOException("EXPORT_PUBLISHED_CHANGED");
    } finally {
      if (created && !fs.stat(part).type.equals("MISSING")) fs.delete(part);
    }
  }

  private static InputStream checked(InputStream input, BackupControl control) {
    return new FilterInputStream(input) {
      @Override
      public int read(byte[] buffer, int offset, int count) throws IOException {
        control.check();
        int read = in.read(buffer, offset, count);
        control.check();
        return read;
      }

      @Override
      public int read() throws IOException {
        control.check();
        int read = in.read();
        control.check();
        return read;
      }
    };
  }
}
