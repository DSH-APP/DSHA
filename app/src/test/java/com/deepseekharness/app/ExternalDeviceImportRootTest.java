package com.deepseekharness.app;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import android.app.Application;
import android.content.Context;
import android.content.ContextWrapper;
import com.deepseekharness.app.extdev.ExternalDeviceHost;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.junit.runner.RunWith;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

/** Android 16 上 getFilesDir() 返回 /data/user/0 别名时，外接设备初始化不能误报 IMPORT_PATH_ALIAS。 */
@RunWith(com.deepseekharness.app.core.PluginRepositoryQueryLifecycleTest.NoApkRunner.class)
@Config(sdk = 34, manifest = Config.NONE, application = Application.class)
public final class ExternalDeviceImportRootTest {
  @Rule public final TemporaryFolder temporary = new TemporaryFolder();

  private Application app;

  @Before
  public void setUp() {
    app = RuntimeEnvironment.getApplication();
  }

  /** 真实布局：<real-data>/files；上下文按 Android 16 返回 <alias-data>/files（alias-data 是指向 real-data 的链接）。 */
  private Context aliasedContext(File realData) throws Exception {
    File aliasData = new File(temporary.getRoot(), "alias-data");
    Files.createSymbolicLink(aliasData.toPath(), realData.toPath());
    assertTrue(Files.isSymbolicLink(aliasData.toPath()));
    Context aliased =
        new ContextWrapper(app) {
          @Override
          public Context getApplicationContext() {
            return this;
          }

          @Override
          public File getFilesDir() {
            return new File(aliasData, "files");
          }
        };
    return aliased;
  }

  @Test
  public void symlinkedAncestorIsNotTreatedAsImportPathAlias() throws Exception {
    File realData = temporary.newFolder("real-data");
    File realFiles = new File(realData, "files");
    assertTrue(realFiles.mkdirs());
    Context aliased = aliasedContext(realData);

    File root = new ExternalDeviceHost(aliased).importRoot();

    assertTrue(root.isDirectory());
    assertEquals(new File(realFiles, "external-device-import").getCanonicalFile(), root);
    assertFalse(root.getPath().startsWith(new File(temporary.getRoot(), "alias-data").getPath()));
  }

  @Test
  public void leafSymlinkStillRejected() throws Exception {
    File realData = temporary.newFolder("real-data");
    File realFiles = new File(realData, "files");
    assertTrue(realFiles.mkdirs());
    File outside = temporary.newFolder("outside");
    File leaf = new File(realFiles, "external-device-import");
    Files.createSymbolicLink(leaf.toPath(), outside.toPath());

    IOException error =
        assertThrows(
            IOException.class, () -> new ExternalDeviceHost(aliasedContext(realData)).importRoot());

    assertEquals("IMPORT_PATH_ALIAS", error.getMessage());
  }
}
