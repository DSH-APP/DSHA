package com.deepseekharness.app.util;

import com.deepseekharness.app.backup.BackupFileSystem;
import com.deepseekharness.app.backup.UserDataLayout;
import java.io.File;
import java.io.IOException;

/** ADB 缓存只写本次应用私有根；Android 别名与 guest 数据链接先解析，再用 NOFOLLOW 发布。 */
public final class AdbWheelPaths {
  public final File files, rootfs, home, wheels, archive, site, stagingParent;
  private final BackupFileSystem fs;
  private final ColdInstallPackages.HostRoot host;
  private final BackupFileSystem.Node rootIdentity;
  private final UserDataLayout layout;
  private final UserDataLayout.Home selected;
  private final DocumentPaths paths;
  private final String homeId;

  public static AdbWheelPaths bind(
      BackupFileSystem fs, File appData, File declaredFiles, File requestedRoot)
      throws IOException {
    return new AdbWheelPaths(fs, appData, declaredFiles, requestedRoot);
  }

  private AdbWheelPaths(BackupFileSystem fs, File appData, File declaredFiles, File requestedRoot)
      throws IOException {
    this.fs = fs;
    host = ColdInstallPackages.bindPrivateFiles(fs, appData, declaredFiles);
    files = host.files;
    if (requestedRoot == null
        || !requestedRoot
                .getAbsoluteFile()
                .equals(new File(declaredFiles, GuestPaths.ROOT_RELATIVE).getAbsoluteFile())
            && !requestedRoot
                .getAbsoluteFile()
                .equals(new File(files, GuestPaths.ROOT_RELATIVE).getAbsoluteFile()))
      throw new IOException("ADB_RUNTIME_ROOT");
    rootfs = fs.child(files, GuestPaths.ROOT_RELATIVE);
    rootIdentity = fs.stat(rootfs);
    if (!rootIdentity.type.equals("DIRECTORY")) throw new IOException("ADB_RUNTIME_ROOT_TYPE");
    layout = new UserDataLayout(fs, files);
    selected = layout.selected();
    homeId = selected == UserDataLayout.Home.STABLE ? UserDataLayout.STABLE : UserDataLayout.LEGACY;
    paths =
        new DocumentPaths(
            files,
            file -> {
              var before = fs.stat(file);
              if (!before.type.equals("LINK")) return null;
              String target = fs.readLink(file);
              if (target == null || target.length() > 2048 || target.indexOf('\0') >= 0)
                throw new IOException("ADB_LINK_FORMAT");
              if (target.startsWith("/data/")
                  && !DocumentPaths.within(files, new File(target).getCanonicalFile()))
                throw new IOException("ADB_LINK_OUTSIDE_AUTHORITY");
              if (!before.same(fs.stat(file))) throw new IOException("ADB_LINK_CHANGED");
              return target;
            });
    home = paths.resolve(homeId, true);
    File boundary =
        selected == UserDataLayout.Home.STABLE ? fs.child(files, UserDataLayout.STABLE) : rootfs;
    if (!DocumentPaths.within(boundary, home) || home.equals(rootfs))
      throw new IOException("ADB_DATA_LINK_OUTSIDE_SELECTED_ROOT");
    if (DocumentPaths.within(rootfs, home)) {
      String guest =
          home.getPath().substring(rootfs.getPath().length()).replace(File.separatorChar, '/');
      if (guest.matches("^/(?:sdcard|storage|proc|sys|dev|system|apex)(?:/.*)?$"))
        throw new IOException("ADB_DATA_EXTERNAL_BINDING");
    }
    wheels = new File(home, "wheels");
    archive = new File(home, "adb-wheels.tar.gz");
    site = paths.resolve(GuestPaths.ROOT_RELATIVE + "/usr/lib/python3/dist-packages", true);
    if (!DocumentPaths.within(rootfs, site) || site.equals(rootfs))
      throw new IOException("ADB_PYTHON_LINK_OUTSIDE_RUNTIME");
    stagingParent = rootfs.getParentFile();
    verify();
  }

  /** 解析不赋予全局跟随链接权限；实际写入仍由原文件系统逐父链 NOFOLLOW。 */
  public void verify() throws IOException {
    host.verify(fs);
    var current = fs.stat(rootfs);
    if (!current.type.equals("DIRECTORY")
        || current.device != rootIdentity.device
        || !current.key.equals(rootIdentity.key)
        || selected != layout.selected()
        || !home.equals(paths.resolve(homeId, true))
        || !site.equals(
            paths.resolve(GuestPaths.ROOT_RELATIVE + "/usr/lib/python3/dist-packages", true)))
      throw new IOException("ADB_WHEEL_LOCATION_CHANGED");
    var data = fs.stat(home);
    if (!data.type.equals("MISSING") && !data.type.equals("DIRECTORY"))
      throw new IOException("ADB_DATA_LOCATION_TYPE");
    // 已解析出的路径不能再含链接；首次准备可能缺少 Python 的多级目录。
    checked(home);
    checked(site);
  }

  private void checked(File target) throws IOException {
    if (!DocumentPaths.within(files, target) || files.equals(target))
      throw new IOException("ADB_WHEEL_PATH_OUTSIDE_AUTHORITY");
    String relative =
        target.getPath().substring(files.getPath().length() + 1).replace(File.separatorChar, '/');
    if (!ManagedInstallPath.valid(relative)) throw new IOException("ADB_WHEEL_PATH_FORMAT");
    File at = files;
    for (String part : relative.split("/")) {
      var parent = fs.stat(at);
      // 已存在的前缀必须安全，缺少的后缀由原 NOFOLLOW 发布器逐级创建。
      // fs.child 要求全部父目录在位，不能在首次安装前用它验证尚未创建的目录。
      if (parent.type.equals("MISSING")) return;
      if (!parent.type.equals("DIRECTORY")) throw new IOException("ADB_WHEEL_PARENT_TYPE");
      at = new File(at, part);
    }
    var leaf = fs.stat(at);
    if (!leaf.type.equals("MISSING") && !leaf.type.equals("DIRECTORY"))
      throw new IOException("ADB_WHEEL_DIRECTORY_TYPE");
  }

  /** 只描述实有条目，不把不存在/空目录叫作已保留缓存。 */
  public String cacheState() throws IOException {
    verify();
    var state = fs.stat(wheels);
    if (state.type.equals("MISSING")) return "CACHE_MISSING";
    if (!state.type.equals("DIRECTORY")) return "CACHE_PATH_" + state.type;
    var entries = fs.list(wheels);
    if (entries.isEmpty()) return "CACHE_EMPTY";
    int count = 0;
    for (String name : entries) if (name.endsWith(".whl")) count++;
    return "CACHE_ENTRIES=" + entries.size() + ",WHEELS=" + count;
  }
}
