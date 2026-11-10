package com.deepseekharness.app.backup;

import static org.junit.Assert.*;
import android.content.Context;
import java.io.File;
import java.io.IOException;
import java.nio.file.*;
import java.util.*;
import org.junit.*;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

/**
 * 一键备份先定位、停 Web 时桥会删除 .bridge_port 并原子重写 .bridge_status。
 * 机器状态整项排除，不能因此让整份备份以 DATA_LOCATION_CHANGED 失败；用户数据仍失败关闭。
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34, manifest = Config.NONE, application = android.app.Application.class)
public final class NativeDataLocationsProofTest {
  private Context context;
  private File dsh;

  @Before
  public void setUp() throws Exception {
    context = org.robolectric.RuntimeEnvironment.getApplication();
    File files = context.getFilesDir().getCanonicalFile();
    dsh = new File(files, "linux/ubuntu/root/.dsh");
    assertTrue(new File(dsh, "sessions").mkdirs());
    Files.writeString(new File(dsh, "sessions/one.jsonl").toPath(), "{}\n");
    Files.writeString(new File(dsh, "settings.yaml").toPath(), "a: 1\n");
    Files.writeString(new File(dsh, ".bridge_status").toPath(), "ok port=3091\n");
    Files.writeString(new File(dsh, ".bridge_port").toPath(), "3091");
  }

  private NativeDataLocations.Located locate() throws IOException {
    var selection = new NativeDataLocations.Selection();
    selection.scope = "application";
    return new NativeDataLocations(context, new JvmBackupFileSystem()).locate(selection);
  }

  private static BackupSource source(NativeDataLocations.Located located, String name) {
    String id = NativeDataLocations.id(name);
    for (BackupSource source : located.sources) if (source.id().equals(id)) return source;
    throw new AssertionError("missing source " + name);
  }

  private static List<BackupSource.Item> walk(BackupSource source) throws IOException {
    List<BackupSource.Item> items = new ArrayList<>();
    source.walk(items::add, new BackupControl(null));
    return items;
  }

  /** 与 AndroidBackupFileSystem.atomic 相同：写临时文件后 rename，替换 inode。 */
  private static void replace(File file, String text) throws IOException {
    Path tmp = file.toPath().resolveSibling(file.getName() + ".tmp");
    Files.writeString(tmp, text);
    Files.move(tmp, file.toPath(), StandardCopyOption.ATOMIC_MOVE);
  }

  @Test
  public void bridgeStateRewrittenAfterLocateStaysExcluded() throws Exception {
    var located = locate();
    replace(new File(dsh, ".bridge_status"), "stopped\n");
    Files.delete(new File(dsh, ".bridge_port").toPath());
    for (String name : List.of(".bridge_status", ".bridge_port")) {
      List<BackupSource.Item> items = walk(source(located, name));
      assertEquals(1, items.size());
      assertEquals("EXCLUDED", items.get(0).kind);
      assertEquals("DEVICE_SPECIFIC_STATE", items.get(0).reason);
    }
  }

  @Test
  public void userDataReplacedAfterLocateStillFailsClosed() throws Exception {
    var located = locate();
    replace(new File(dsh, "settings.yaml"), "a: 2\n");
    List<BackupSource.Item> items = walk(source(located, "settings.yaml"));
    assertEquals("UNREADABLE", items.get(0).kind);
    assertEquals("DATA_LOCATION_CHANGED", items.get(0).reason);
  }
}
