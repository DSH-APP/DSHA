package com.deepseekharness.app.runtime;

import com.deepseekharness.app.backup.*;
import java.io.*;
import java.util.*;

/** Signed-asset extraction authority; descendant writes never follow existing links. */
final class ArchiveFileBoundary {
  final BackupFileSystem fs;
  final File root;
  final BackupFileSystem.Node identity;
  final byte[] copyBuffer = new byte[262144];
  final Map<String, LegacyTarReader.Member> links = new LinkedHashMap<>();
  final Map<String, Integer> directories = new LinkedHashMap<>();
  final BackupControl control = new BackupControl(null);

  ArchiveFileBoundary(BackupFileSystem fs, File granted) throws IOException {
    this.fs = fs;
    if (!granted.isDirectory() && !granted.mkdirs())
      throw new IOException("TAR_ROOT_CREATE:" + granted);
    if (!fs.stat(granted).type.equals("DIRECTORY"))
      throw new IOException("TAR_ROOT_LINK:" + granted);
    root = granted.getCanonicalFile();
    identity = fs.stat(root);
    verify();
  }

  void verify() throws IOException {
    var now = fs.stat(root);
    if (!now.type.equals("DIRECTORY")
        || now.device != identity.device
        || !now.key.equals(identity.key)) throw new IOException("TAR_ROOT_CHANGED:" + root);
  }

  File target(String path, boolean parents) throws IOException {
    verify();
    BackupLimits.path(path);
    if (fs instanceof TrustedAssetFileSystem trusted) return trusted.target(path, parents);
    File at = root;
    String[] parts = path.split("/");
    for (int i = 0; i < parts.length - 1; i++) {
      at = new File(at, parts[i]);
      var n = fs.stat(at);
      if (n.type.equals("MISSING") && parents) {
        fs.directory(at);
        n = fs.stat(at);
      }
      if (!n.type.equals("DIRECTORY"))
        throw new IOException("TAR_PARENT_LINK_OR_TYPE:" + path + ":" + parts[i] + ":" + n.type);
    }
    return new File(at, parts[parts.length - 1]);
  }

  void directory(LegacyTarReader.Member m) throws IOException {
    File out = target(m.path, true);
    var n = fs.stat(out);
    if (n.type.equals("MISSING")) fs.directory(out);
    else if (!n.type.equals("DIRECTORY"))
      throw new IOException("TAR_DIRECTORY_CONFLICT:" + m.path + ":" + n.type);
    directories.put(m.path, m.mode);
  }

  void file(LegacyTarReader.Member m, InputStream data) throws IOException {
    if (fs instanceof TrustedAssetFileSystem trusted) {
      try (var entry = trusted.fileEntry(m.path)) {
        writeFile(m, data);
      }
    } else writeFile(m, data);
  }

  private void writeFile(LegacyTarReader.Member m, InputStream data) throws IOException {
    File out = target(m.path, true);
    var before = fs.stat(out);
    if (!before.type.equals("MISSING") && !before.type.equals("FILE"))
      throw new IOException("TAR_FILE_CONFLICT:" + m.path + ":" + before.type);
    // 全新候选成员以 EXCL/NOFOLLOW 独占创建，失败只删除本次 inode。
    // 已有成员仍使用 staged + previous 回切，不能截断原件来节省改名。
    File staged =
        before.type.equals("MISSING")
            ? out
            : new File(out.getParentFile(), ".dsha-tar-part-" + UUID.randomUUID());
    BackupFileSystem.Node owned = null;
    boolean published = false;
    try {
      boolean small =
          before.type.equals("MISSING")
              && m.size >= 0
              && m.size <= TrustedAssetFileSystem.SMALL_FILE_BYTES
              && fs instanceof TrustedAssetFileSystem trusted
              && trusted.supportsSmallFiles();
      int smallBytes = small ? readSmallFile(m, data) : 0;
      OutputStream opened =
          small
              ? ((TrustedAssetFileSystem) fs)
                  .prepareSmallFile(staged, copyBuffer, smallBytes, m.mode)
              : fs instanceof TrustedAssetFileSystem trusted
                  ? trusted.createWithMode(staged, m.mode)
                  : fs.create(staged);
      try (OutputStream stream = opened) {
        owned =
            opened instanceof TrustedAssetFileSystem.CreatedFile created
                ? created.identity
                : fs.stat(staged);
        if (!owned.type.equals("FILE")) throw new IOException("TAR_CREATED_FILE_TYPE:" + m.path);
        if (!small) {
          long bytes = 0;
          int n;
          while ((n = data.read(copyBuffer)) != -1) {
            bytes = BackupLimits.add(bytes, n, m.size);
            stream.write(copyBuffer, 0, n);
          }
          if (bytes != m.size) throw new IOException("TAR_TRUNCATED:" + m.path);
        }
      }
      verify();
      if (before.type.equals("FILE")) {
        if (!before.same(fs.stat(out))) throw new IOException("TAR_TARGET_CHANGED:" + m.path);
        File previous = new File(out.getParentFile(), ".dsha-tar-previous-" + UUID.randomUUID());
        fs.move(out, previous);
        try {
          fs.move(staged, out);
        } catch (IOException error) {
          if (fs.stat(out).type.equals("MISSING")) fs.move(previous, out);
          throw error;
        }
        fs.delete(previous);
      } else {
        var now = fs.stat(out);
        if (!now.type.equals("FILE") || now.device != owned.device || !now.key.equals(owned.key))
          throw new IOException("TAR_TARGET_CHANGED:" + m.path);
      }
      if (!(fs instanceof TrustedAssetFileSystem)) fs.mode(out, m.mode);
      fs.syncDirectory(out.getParentFile());
      verify();
      published = true;
    } finally {
      if (!published && owned != null) {
        var remaining = fs.stat(staged);
        if (remaining.type.equals("FILE")
            && remaining.device == owned.device
            && remaining.key.equals(owned.key)) fs.delete(staged);
      }
    }
  }

  private int readSmallFile(LegacyTarReader.Member m, InputStream data) throws IOException {
    int bytes = 0, n;
    // 多留一个字节沿用正文预算检查；共享提取缓冲，不为每个短成员分配数组。
    while ((n = data.read(copyBuffer, bytes, (int) m.size + 1 - bytes)) != -1) {
      control.check();
      bytes = (int) BackupLimits.add(bytes, n, m.size);
    }
    if (bytes != m.size) throw new IOException("TAR_TRUNCATED:" + m.path);
    control.check();
    return bytes;
  }

  String resolve(String name, String raw, boolean hard) throws IOException {
    if (raw == null || raw.isEmpty() || raw.indexOf((char) 92) >= 0 || raw.indexOf((char) 0) >= 0)
      throw new IOException("TAR_LINK_TARGET:" + name);
    String parent = hard ? "" : name.contains("/") ? name.substring(0, name.lastIndexOf('/')) : "";
    ArrayDeque<String> pending = new ArrayDeque<>(), stack = new ArrayDeque<>();
    if (!raw.startsWith("/") && !parent.isEmpty())
      for (String s : parent.split("/")) stack.addLast(s);
    for (String s : raw.split("/")) pending.addLast(s);
    int hops = 0;
    while (!pending.isEmpty()) {
      String part = pending.removeFirst();
      if (part.isEmpty() || part.equals(".")) continue;
      if (part.equals("..")) {
        if (stack.isEmpty()) throw new IOException("TAR_LINK_ESCAPE:" + name);
        stack.removeLast();
        continue;
      }
      stack.addLast(part);
      String candidate = String.join("/", stack);
      var link = links.get(candidate);
      if (link != null) {
        if (++hops > 40) throw new IOException("TAR_LINK_CYCLE:" + name);
        stack.removeLast();
        if (link.type.equals("HARDLINK") || link.target.startsWith("/")) stack.clear();
        String[] replacement = link.target.split("/");
        for (int i = replacement.length - 1; i >= 0; i--) pending.addFirst(replacement[i]);
      } else if (fs.stat(new File(root, candidate)).type.equals("LINK"))
        throw new IOException("TAR_EXISTING_TARGET_LINK:" + name + ":" + candidate);
    }
    return String.join("/", stack);
  }

  void finish() throws IOException {
    for (var link : links.values()) {
      control.check();
      resolve(link.path, link.target, link.type.equals("HARDLINK"));
    }
    for (var link : links.values()) {
      try {
        control.check();
        File out = target(link.path, true);
        String resolved = resolve(link.path, link.target, link.type.equals("HARDLINK"));
        File to = resolved.isEmpty() ? root : new File(root, resolved);
        var existing = fs.stat(out);
        if (link.type.equals("HARDLINK")) {
          var source = fs.stat(to);
          if (!source.type.equals("FILE"))
            throw new IOException("TAR_HARDLINK_SOURCE:" + link.path + ":" + source.type);
          try (InputStream data = fs.read(to, source)) {
            file(new LegacyTarReader.Member(link.path, "FILE", "", source.size, link.mode), data);
          }
        } else {
          // Validate the final location, but preserve link indirection: flattening
          // next -> current -> tool would break a later legitimate current update.
          String relative = link.target;
          if (relative.startsWith("/")) {
            int depth = link.path.split("/").length - 1;
            StringBuilder guestRoot = new StringBuilder();
            for (int i = 0; i < depth; i++) guestRoot.append("../");
            while (relative.startsWith("/")) relative = relative.substring(1);
            relative = guestRoot + (relative.isEmpty() ? "." : relative);
          }
          if (existing.type.equals("LINK")) {
            String old = fs.readLink(out);
            if (old.startsWith("/") || !resolve(link.path, old, false).equals(resolved))
              throw new IOException("TAR_SYMLINK_CONFLICT:" + link.path);
          } else if (existing.type.equals("MISSING")) fs.symlink(relative, out);
          else throw new IOException("TAR_SYMLINK_CONFLICT:" + link.path + ":" + existing.type);
        }
      } catch (IOException error) {
        throw new IOException(
            "TAR_LINK_FAILURE:" + link.path + ":" + link.type + ":" + error.getMessage(), error);
      }
    }
    if (fs instanceof TrustedAssetFileSystem trusted) trusted.finishFiles();
    List<String> paths = new ArrayList<>(directories.keySet());
    paths.sort((a, b) -> Integer.compare(b.length(), a.length()));
    for (String path : paths)
      try {
        control.check();
        fs.mode(target(path, false), directories.get(path));
      } catch (IOException error) {
        throw new IOException("TAR_DIRECTORY_MODE:" + path + ":" + error.getMessage(), error);
      }
    control.check();
    if (fs instanceof TrustedAssetFileSystem trusted) trusted.finishDirectoryModes();
    verify();
  }
}
