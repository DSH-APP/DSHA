package com.deepseekharness.app.util;

import com.deepseekharness.app.backup.BackupFileSystem;
import com.deepseekharness.app.backup.JvmBackupFileSystem;
import com.deepseekharness.app.backup.UserDataLayout;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.util.HashMap;
import java.util.Map;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import static org.junit.Assert.*;

public class AdbWheelPathsTest {
  @Rule public TemporaryFolder temporary = new TemporaryFolder();

  private static final class PlatformAlias extends File {
    final File physical;

    PlatformAlias(File physical) {
      super("/data/data/com.dsh.clienu");
      this.physical = physical;
    }

    public boolean isAbsolute() {
      return true;
    }

    public File getCanonicalFile() throws IOException {
      return physical.getCanonicalFile();
    }
  }

  private static final class FrameworkFiles extends File {
    final PlatformAlias alias;

    FrameworkFiles(PlatformAlias alias) {
      super(alias, "files");
      this.alias = alias;
    }

    public boolean isAbsolute() {
      return true;
    }

    public File getParentFile() {
      return alias;
    }
  }

  /** Windows 上保留实际文件读写，模拟 guest 绝对链接和 Android 严格父链契约。 */
  private static final class StrictFs extends JvmBackupFileSystem {
    final Map<File, String> links = new HashMap<>();
    File blockedAlias, replaced;

    private void parents(File file) throws IOException {
      if (blockedAlias != null
          && DocumentPaths.within(blockedAlias.getAbsoluteFile(), file.getAbsoluteFile()))
        throw new IOException("PARENT_LINK");
      for (File at = file.getParentFile(); at != null; at = at.getParentFile())
        if (links.containsKey(at)) throw new IOException("PARENT_LINK");
    }

    public BackupFileSystem.Node stat(File file) throws IOException {
      if (links.containsKey(file))
        return new BackupFileSystem.Node("LINK", "link:" + file, 1, 1, 1, 0700);
      var node = super.stat(file);
      return file.equals(replaced)
          ? new BackupFileSystem.Node(
              node.type, "replacement", node.size, node.modified, node.device, node.mode)
          : node;
    }

    public String readLink(File file) throws IOException {
      parents(file);
      return links.containsKey(file) ? links.get(file) : super.readLink(file);
    }

    public InputStream read(File file, BackupFileSystem.Node node) throws IOException {
      parents(file);
      return super.read(file, node);
    }

    public OutputStream create(File file) throws IOException {
      parents(file);
      return super.create(file);
    }

    public void directory(File file) throws IOException {
      parents(file);
      super.directory(file);
    }
  }

  private File data() throws IOException {
    File data = temporary.newFolder();
    Files.createDirectories(new File(data, "files/linux/ubuntu/root/.dsh").toPath());
    Files.createDirectories(
        new File(data, "files/linux/ubuntu/usr/lib/python3/dist-packages").toPath());
    return data.getCanonicalFile();
  }

  private AdbWheelPaths bind(StrictFs fs, File data) throws IOException {
    File files = new File(data, "files");
    return AdbWheelPaths.bind(fs, data, files, new File(files, GuestPaths.ROOT_RELATIVE));
  }

  @Test
  public void coldPythonDirectoryCanBePreparedWithoutTreatingMissingParentsAsLinks()
      throws Exception {
    File data = data();
    StrictFs fs = new StrictFs();
    File python = new File(data, "files/linux/ubuntu/usr/lib/python3");
    Files.delete(new File(python, "dist-packages").toPath());
    Files.delete(python.toPath());
    var paths = bind(fs, data);
    assertEquals(new File(python, "dist-packages"), paths.site);
    assertFalse(python.exists());
    fs.parents(paths.rootfs, "usr/lib/python3/dist-packages/probe.py");
    assertTrue(paths.site.isDirectory());
    paths.verify();
    fs.links.put(python, "/sdcard/foreign");
    assertThrows(IOException.class, paths::verify);
  }

  @Test
  public void frameworkAliasIsNormalizedBeforeStrictParentOperations() throws Exception {
    File data = data();
    PlatformAlias alias = new PlatformAlias(data);
    FrameworkFiles framework = new FrameworkFiles(alias);
    StrictFs fs = new StrictFs();
    fs.blockedAlias = alias;
    var paths =
        AdbWheelPaths.bind(fs, alias, framework, new File(framework, GuestPaths.ROOT_RELATIVE));
    assertEquals(new File(data, "files/linux/ubuntu/root/.dsh/wheels"), paths.wheels);
    assertEquals("CACHE_MISSING", paths.cacheState());
    fs.directory(paths.wheels);
    assertEquals("CACHE_EMPTY", paths.cacheState());
    try (OutputStream out = fs.create(new File(paths.wheels, "actual.whl"))) {
      out.write(7);
    }
    assertEquals("CACHE_ENTRIES=1,WHEELS=1", paths.cacheState());
    assertThrows(
        IOException.class, () -> fs.directory(new File(framework, "linux/ubuntu/root/.dsh/other")));
  }

  @Test
  public void guestAbsoluteAndRelativeHomeLinksResolveInsideSameRuntime() throws Exception {
    File data = data();
    StrictFs fs = new StrictFs();
    File link = new File(data, "files/linux/ubuntu/root/.dsh");
    File actual = new File(data, "files/linux/ubuntu/home/dsh");
    Files.createDirectories(actual.toPath());
    for (String target : new String[] {"/home/dsh", "../home/dsh"}) {
      fs.links.put(link, target);
      var paths = bind(fs, data);
      assertEquals(actual, paths.home);
      assertEquals(new File(actual, "wheels"), paths.wheels);
      paths.verify();
    }
    fs.links.put(link, "/root/.dsh");
    assertThrows(IOException.class, () -> bind(fs, data));
  }

  @Test
  public void stableDataBindingDoesNotWriteTheLegacyManagedScriptDirectory() throws Exception {
    File data = data();
    File files = new File(data, "files"), stable = new File(files, UserDataLayout.STABLE);
    Files.createDirectories(stable.toPath());
    Files.write(
        new File(files, UserDataLayout.RECORD).toPath(),
        UserDataLayout.record(UserDataLayout.Home.STABLE));
    StrictFs fs = new StrictFs();
    var paths = bind(fs, data);
    assertEquals(stable, paths.home);
    fs.directory(paths.wheels);
    assertFalse(new File(files, UserDataLayout.LEGACY + "/wheels").exists());
    assertEquals("CACHE_EMPTY", paths.cacheState());
  }

  @Test
  public void externalBindingsForeignHostsAndLinkCyclesNeverCreateCache() throws Exception {
    File data = data();
    StrictFs fs = new StrictFs();
    File link = new File(data, "files/linux/ubuntu/root/.dsh");
    for (String target :
        new String[] {
          "/sdcard/Documents/dshdata",
          "../sdcard/dshdata",
          "/data/user/0/other.app/files/data",
          new File(data.getParentFile(), "other-app").getPath()
        }) {
      fs.links.put(link, target);
      assertThrows(target, IOException.class, () -> bind(fs, data));
    }
    assertFalse(new File(link, "wheels").exists());
    assertFalse(new File(data, "files/linux/ubuntu/sdcard").exists());
  }

  @Test
  public void changedRuntimeOrHomeLinkInvalidatesThePreparedLocation() throws Exception {
    File data = data();
    StrictFs fs = new StrictFs();
    var paths = bind(fs, data);
    fs.replaced = paths.rootfs;
    assertThrows(IOException.class, paths::verify);
    fs.replaced = null;
    File another = new File(paths.rootfs, "home/another");
    Files.createDirectories(another.toPath());
    fs.links.put(new File(paths.rootfs, "root/.dsh"), "/home/another");
    assertThrows(IOException.class, paths::verify);
  }
}
