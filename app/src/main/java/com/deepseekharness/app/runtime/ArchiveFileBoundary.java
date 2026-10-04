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
    File out = target(m.path, true);
    var before = fs.stat(out);
    if (!before.type.equals("MISSING") && !before.type.equals("FILE"))
      throw new IOException("TAR_FILE_CONFLICT:" + m.path + ":" + before.type);
    File staged = new File(out.getParentFile(), ".dsha-tar-part-" + UUID.randomUUID());
    try {
      try (OutputStream stream = fs.create(staged)) {
        long bytes = 0;
        int n;
        while ((n = data.read(copyBuffer)) != -1) {
          bytes = BackupLimits.add(bytes, n, m.size);
          stream.write(copyBuffer, 0, n);
        }
        if (bytes != m.size) throw new IOException("TAR_TRUNCATED:" + m.path);
      }
      verify();
      if (!before.same(fs.stat(out))) throw new IOException("TAR_TARGET_CHANGED:" + m.path);
      if (before.type.equals("FILE")) {
        File previous = new File(out.getParentFile(), ".dsha-tar-previous-" + UUID.randomUUID());
        fs.move(out, previous);
        try {
          fs.move(staged, out);
        } catch (IOException error) {
          if (fs.stat(out).type.equals("MISSING")) fs.move(previous, out);
          throw error;
        }
        fs.delete(previous);
      } else fs.move(staged, out);
      fs.mode(out, m.mode);
      fs.syncDirectory(out.getParentFile());
      verify();
    } finally {
      if (fs.stat(staged).type.equals("FILE")) fs.delete(staged);
    }
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
    for (var link : links.values()) resolve(link.path, link.target, link.type.equals("HARDLINK"));
    for (var link : links.values()) {
      try {
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
    List<String> paths = new ArrayList<>(directories.keySet());
    paths.sort((a, b) -> Integer.compare(b.length(), a.length()));
    for (String path : paths)
      try {
        fs.mode(target(path, false), directories.get(path));
      } catch (IOException error) {
        throw new IOException("TAR_DIRECTORY_MODE:" + path + ":" + error.getMessage(), error);
      }
    verify();
  }
}
