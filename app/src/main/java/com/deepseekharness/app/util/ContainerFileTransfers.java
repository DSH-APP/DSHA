package com.deepseekharness.app.util;

import com.deepseekharness.app.backup.*;
import java.io.*;
import java.util.*;

/** Guest/host transfer uses approved mappings, verified descriptors and sibling publication. */
public final class ContainerFileTransfers {
  public interface Check {
    void unchanged() throws IOException;
  }

  private final BackupFileSystem fs;
  private final GuestDataResolver guest, host;
  private final List<File> guestRoots, hostRoots;
  private final String path;
  private final Check check;

  public ContainerFileTransfers(
      BackupFileSystem fs,
      File rootfs,
      File storage,
      List<String[]> binds,
      List<File[]> aliases,
      List<File> hosts,
      String path,
      Check check)
      throws IOException {
    this.fs = fs;
    this.path = path;
    this.check = check;
    guestRoots = new ArrayList<>();
    guestRoots.add(rootfs);
    hostRoots = new ArrayList<>(hosts);
    if (storage != null) guestRoots.add(storage);
    for (String[] bind : binds) guestRoots.add(new File(bind[0]));
    List<String[]> sorted = new ArrayList<>(binds);
    sorted.sort((a, b) -> Integer.compare(b[1].length(), a[1].length()));
    GuestDataResolver.Alias platform =
        value -> {
          for (File[] alias : aliases)
            if (DocumentPaths.within(alias[0], value))
              return append(alias[1], value.getPath().substring(alias[0].getPath().length()));
          return value;
        };
    guest =
        new GuestDataResolver(
            fs,
            rootfs,
            storage,
            guestRoots,
            value -> {
              value = platform.rewrite(value);
              for (String[] bind : sorted) {
                File logical = new File(rootfs, bind[1].replaceFirst("^/", ""));
                if (DocumentPaths.within(logical, value))
                  return append(
                      new File(bind[0]), value.getPath().substring(logical.getPath().length()));
              }
              return value;
            });
    host = new GuestDataResolver(fs, rootfs, storage, hostRoots, platform);
    String relative = path.startsWith("/") ? path.substring(1) : path;
    BackupLimits.path(relative);
    if (relative.isEmpty()) throw new IOException("TRANSFER_GUEST_ROOT");
  }

  private static File append(File base, String suffix) {
    while (suffix.startsWith(File.separator) || suffix.startsWith("/"))
      suffix = suffix.substring(1);
    return suffix.isEmpty() ? base : new File(base, suffix);
  }

  private File guestPath() throws IOException {
    return guest.guest(path.startsWith("/") ? path : "/" + path);
  }

  public void push(File source) throws IOException {
    copy(host, source, guest, guestPath(), guestRoots);
  }

  public void pull(File destination) throws IOException {
    copy(guest, guestPath(), host, destination, hostRoots);
  }

  private File authority(File target, List<File> roots) throws IOException {
    File best = null;
    for (File root : roots)
      if (DocumentPaths.within(root, target)
          && !root.equals(target)
          && fs.stat(root).type.equals("DIRECTORY")
          && (best == null || root.getPath().length() > best.getPath().length())) best = root;
    if (best == null || !fs.stat(best).type.equals("DIRECTORY"))
      throw new IOException("TRANSFER_TARGET_AUTHORITY");
    return best;
  }

  private static void sameResolution(
      GuestDataResolver resolver, File logical, GuestDataResolver.Resolved expected)
      throws IOException {
    var now = resolver.resolve(logical);
    if (!now.file.equals(expected.file) || !now.proof.equals(expected.proof))
      throw new IOException("TRANSFER_PATH_CHANGED");
  }

  private void sameParent(File parent, BackupFileSystem.Node expected) throws IOException {
    var now = fs.stat(parent);
    if (!now.type.equals("DIRECTORY")
        || now.device != expected.device
        || !now.key.equals(expected.key)) throw new IOException("TRANSFER_PARENT_CHANGED");
  }

  private void copy(
      GuestDataResolver reads,
      File source,
      GuestDataResolver writes,
      File destination,
      List<File> roots)
      throws IOException {
    check.unchanged();
    var input = reads.resolve(source);
    var sourceNode = fs.stat(input.file);
    if (!sourceNode.type.equals("FILE") || sourceNode.size < 0)
      throw new IOException("TRANSFER_SOURCE_NOT_FILE");
    var output = writes.resolve(destination);
    File root = authority(output.file, roots);
    String relative =
        output
            .file
            .getPath()
            .substring(root.getPath().length() + 1)
            .replace(File.separatorChar, '/');
    fs.parents(root, relative);
    output = writes.resolve(destination);
    var before = fs.stat(output.file);
    if (!before.type.equals("MISSING") && !before.type.equals("FILE"))
      throw new IOException("TRANSFER_TARGET_TYPE");
    File parent = output.file.getParentFile(),
        stage = fs.child(parent, ".dsha-transfer-part-" + UUID.randomUUID()),
        previous = null;
    BackupFileSystem.Node staged = null;
    boolean published = false, created = false;
    var parentIdentity = fs.stat(parent);
    try {
      FileIntegrity.Result copied;
      try (InputStream in = fs.read(input.file, sourceNode)) {
        OutputStream owned = fs.create(stage);
        created = true;
        try (OutputStream out = owned) {
          copied = FileIntegrity.copy(in, out, sourceNode.size);
        }
      }
      if (before.type.equals("FILE")) fs.mode(stage, before.mode);
      staged = fs.stat(stage);
      if (copied.size != sourceNode.size || !sourceNode.same(fs.stat(input.file)))
        throw new IOException("TRANSFER_SOURCE_CHANGED");
      sameResolution(reads, source, input);
      sameResolution(writes, destination, output);
      check.unchanged();
      sameParent(parent, parentIdentity);
      if (!before.same(fs.stat(output.file))) throw new IOException("TRANSFER_TARGET_CHANGED");
      if (before.type.equals("FILE")) {
        previous = fs.child(parent, ".dsha-transfer-previous-" + UUID.randomUUID());
        fs.move(output.file, previous);
        sameParent(parent, parentIdentity);
        if (!before.same(fs.stat(previous))) throw new IOException("TRANSFER_TARGET_CHANGED");
      }
      fs.move(stage, output.file);
      published = true;
      fs.syncDirectory(parent);
      sameParent(parent, parentIdentity);
      if (!staged.same(fs.stat(output.file))) throw new IOException("TRANSFER_PUBLISHED_CHANGED");
      check.unchanged();
      if (previous != null) {
        if (!before.same(fs.stat(previous))) throw new IOException("TRANSFER_RETAINED_CHANGED");
        fs.delete(previous);
        fs.syncDirectory(parent);
      }
    } catch (IOException error) {
      // No unknown destination is replaced during rollback. If publication
      // already happened, keep both the published copy and retained original.
      if (!published && previous != null && fs.stat(output.file).type.equals("MISSING"))
        try {
          fs.move(previous, output.file);
          fs.syncDirectory(parent);
        } catch (IOException rollback) {
          error.addSuppressed(rollback);
        }
      throw error;
    } finally {
      if (created
          && fs.stat(stage).type.equals("FILE")
          && (staged == null || staged.same(fs.stat(stage)))) fs.delete(stage);
    }
  }
}
