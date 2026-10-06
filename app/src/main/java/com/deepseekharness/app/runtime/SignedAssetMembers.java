package com.deepseekharness.app.runtime;

import com.deepseekharness.app.backup.BackupLimits;
import java.io.IOException;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/** 本次签名成员与隐式父目录的直接成员集合；不把路径存在当作完整证明。 */
final class SignedAssetMembers {
  private final Map<String, Set<String>> children = new HashMap<>();

  SignedAssetMembers(Collection<String> paths) throws IOException {
    children.put("", new HashSet<>());
    for (String path : paths) {
      BackupLimits.path(path);
      if (path.isEmpty()) continue;
      String at = path;
      while (!at.isEmpty()) {
        int slash = at.lastIndexOf('/');
        String parent = slash < 0 ? "" : at.substring(0, slash);
        String name = at.substring(slash + 1);
        children.computeIfAbsent(parent, ignored -> new HashSet<>()).add(name);
        at = parent;
      }
    }
  }

  Set<String> names(String directory) {
    Set<String> names = children.get(directory);
    return names == null ? java.util.Collections.emptySet() : names;
  }

  void verify(String directory, Collection<String> actual) throws IOException {
    Set<String> seen = new HashSet<>(actual);
    if (seen.size() != actual.size() || !names(directory).equals(seen))
      throw new IOException("TAR_DIRECTORY_MEMBERS_CHANGED:" + directory);
  }
}
