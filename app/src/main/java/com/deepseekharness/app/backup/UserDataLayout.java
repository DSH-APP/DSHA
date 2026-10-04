package com.deepseekharness.app.backup;

import com.deepseekharness.app.util.DocumentPaths;
import com.deepseekharness.app.util.ManagedRuntimeLayout;
import java.io.*;
import java.util.*;

/** 统一旧 guest 数据根与宿主持久根；选择只读小型记录，不扫描用户文件。 */
public final class UserDataLayout {
  public static final String
      LEGACY = com.deepseekharness.app.util.GuestPaths.ROOT_RELATIVE + "/root/.dsh",
      STABLE = "user-data-v5/dsh",
      RECORD = PrivateRoots.DATA_LOCATION;

  public enum Home {
    LEGACY,
    STABLE
  }

  private final BackupFileSystem fs;
  private final File files;

  public UserDataLayout(BackupFileSystem fs, File files) {
    this.fs = fs;
    this.files = files.getAbsoluteFile();
  }

  public File root(Home home) {
    return new File(files, home == Home.STABLE ? STABLE : LEGACY);
  }

  public Home selected() throws IOException {
    File record = new File(files, RECORD);
    if (fs.stat(record).type.equals("MISSING")) record = new File(files, RECORD + ".previous");
    if (!fs.stat(record).type.equals("MISSING")) {
      Map<String, Object> value = BackupJson.read(fs.small(record, 4096), 4096);
      if (BackupJson.number(value, "version") != 1) throw new IOException("DATA_LOCATION_RECORD");
      try {
        return Home.valueOf(BackupJson.string(value, "home"));
      } catch (IllegalArgumentException invalid) {
        throw new IOException("DATA_LOCATION_RECORD");
      }
    }
    var stable = fs.stat(root(Home.STABLE));
    if (stable.type.equals("MISSING")) return Home.LEGACY;
    if (!stable.type.equals("DIRECTORY")) throw new IOException("DATA_LOCATION_UNREADABLE");
    if (!fs.stat(root(Home.LEGACY)).type.equals("MISSING"))
      throw new IOException("DATA_LOCATION_AMBIGUOUS");
    return Home.STABLE;
  }

  public File current() throws IOException {
    return root(selected());
  }

  public static byte[] record(Home home) throws IOException {
    return BackupJson.write(Map.of("version", 1L, "home", home.name()), 4096);
  }

  public void choose(Home home) throws IOException {
    if (!fs.stat(root(home)).type.equals("DIRECTORY"))
      throw new IOException("DATA_LOCATION_UNREADABLE");
    fs.list(root(home));
    File previous = new File(files, RECORD + ".previous"), current = new File(files, RECORD);
    if (!fs.stat(previous).type.equals("MISSING")) {
      BackupJson.read(fs.small(previous, 4096), 4096);
      if (fs.stat(current).type.equals("MISSING")) fs.move(previous, current);
      else fs.delete(previous);
    }
    fs.atomic(files, RECORD, record(home));
  }

  /** 运行时必须看到已选择数据；不存在/失权不能自动创建一个同名空根。 */
  public List<String[]> binds(File rootfs) throws IOException {
    if (selected() != Home.STABLE) return Collections.emptyList();
    File data = root(Home.STABLE);
    if (!fs.stat(data).type.equals("DIRECTORY")) throw new IOException("DATA_LOCATION_UNREADABLE");
    List<String[]> result = new ArrayList<>();
    result.add(new String[] {data.getAbsolutePath(), "/root/.dsh"});
    for (String path : ManagedRuntimeLayout.paths())
      if (path.startsWith("root/.dsh/")) {
        File source = new File(rootfs, path);
        if (fs.stat(source).type.equals("FILE"))
          result.add(new String[] {source.getAbsolutePath(), "/" + path});
      }
    return result;
  }

  private boolean managed(String suffix) {
    return ManagedRuntimeLayout.paths().contains("root/.dsh/" + suffix);
  }

  public DocumentPaths.Alias documents() {
    return new DocumentPaths.Alias() {
      public String rewrite(String relative) throws IOException {
        if (!(relative.equals(LEGACY) || relative.startsWith(LEGACY + "/"))
            || selected() != Home.STABLE) return relative;
        String suffix = relative.equals(LEGACY) ? "" : relative.substring(LEGACY.length() + 1);
        if (managed(suffix)) return relative;
        if (!fs.stat(root(Home.STABLE)).type.equals("DIRECTORY"))
          throw new IOException("DATA_LOCATION_UNREADABLE");
        return STABLE + (suffix.isEmpty() ? "" : "/" + suffix);
      }

      public boolean child(String parent, String child, File actual) throws IOException {
        if (selected() != Home.STABLE || !child.startsWith(LEGACY + "/") && !child.equals(LEGACY))
          return false;
        boolean ancestor = parent.isEmpty() || LEGACY.startsWith(parent + "/");
        return ancestor && DocumentPaths.within(root(Home.STABLE), actual);
      }
    };
  }

  public boolean privateDocument(File path) {
    String relative =
        path.getAbsolutePath()
            .substring(files.getAbsolutePath().length())
            .replace(File.separatorChar, '/');
    while (relative.startsWith("/")) relative = relative.substring(1);
    String first = relative.split("/", 2)[0];
    if (PrivateRoots.top(first)) return true;
    if (com.deepseekharness.app.util.CredentialPaths.deniedRelative(relative)) return true;
    int guest = relative.indexOf("linux/ubuntu/");
    if (guest == 0
        && com.deepseekharness.app.util.CredentialPaths.deniedRelative(
            relative.substring("linux/ubuntu/".length()))) return true;
    if (path.getName().startsWith(".dsha-plugin-task-")
        || path.getName().startsWith(".dsha-plugin-recovery-")) return true;
    try {
      File home = current();
      if (GuestDataResolver.within(home, path) && !home.equals(path)) {
        String child =
            path.getAbsolutePath()
                .substring(home.getAbsolutePath().length() + 1)
                .replace(File.separatorChar, '/')
                .split("/", 2)[0];
        return PrivateRoots.DSH.contains(child);
      }
    } catch (IOException unavailable) {
      return true;
    }
    return false;
  }
}
