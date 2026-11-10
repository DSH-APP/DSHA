package com.deepseekharness.app.runtime;

import static org.junit.Assert.*;

import android.system.ErrnoException;
import android.system.OsConstants;
import android.system.StructStat;
import android.system.StructTimespec;
import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

/** 同一次 lstat 的真实 Android 元数据类型；不依赖宿主文件系统的时间精度或软链接权限。 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34, manifest = Config.NONE, application = android.app.Application.class)
public final class RuntimeAssetStampAndroidTest {
  private static final File ROOT = new File("synthetic-runtime");
  private static final File ASSET = new File(ROOT, "managed-script.js");

  private static StructStat node(
      long device, long inode, int mode, long size, long modifiedNanos, long changedNanos) {
    return new StructStat(
        device,
        inode,
        mode,
        1,
        10000,
        10000,
        0,
        size,
        new StructTimespec(10, 0),
        new StructTimespec(20, modifiedNanos),
        new StructTimespec(30, changedNanos),
        4096,
        1);
  }

  private static StructStat directory() {
    return node(1, 2, OsConstants.S_IFDIR | 0755, 4096, 0, 0);
  }

  private static StructStat regular() {
    return node(1, 3, OsConstants.S_IFREG | 0644, 100, 1, 2);
  }

  private static String stamp(StructStat file) {
    return RuntimeAssetStamp.read(
        ROOT, List.of(ASSET), path -> path.equals(ROOT) ? directory() : file);
  }

  @Test
  public void oneMetadataReadPerFixedFileAndNoFollowupFileProbes() {
    List<File> files = new ArrayList<>();
    for (int i = 0; i < 90; i++)
      files.add(
          new File(ROOT, "managed-" + i) {
            @Override
            public boolean isFile() {
              throw new AssertionError("Redundant or following stat");
            }

            @Override
            public long lastModified() {
              throw new AssertionError("Redundant timestamp stat");
            }
          });
    AtomicInteger calls = new AtomicInteger();
    String result =
        RuntimeAssetStamp.read(
            ROOT,
            files,
            path -> {
              calls.incrementAndGet();
              return path.equals(ROOT) ? directory() : regular();
            });
    assertNotNull(result);
    assertEquals(91, calls.get());
  }

  @Test
  public void sameSizeSameSecondWritesStillInvalidateThePreparedStamp() {
    String original = stamp(regular());
    assertNotEquals(original, stamp(node(1, 3, OsConstants.S_IFREG | 0644, 100, 2, 2)));
    assertNotEquals(original, stamp(node(1, 3, OsConstants.S_IFREG | 0644, 100, 1, 3)));
    assertEquals(original, stamp(regular()));
  }

  @Test
  public void deviceInodeSizeAndPermissionsRemainPartOfTheIdentity() {
    String original = stamp(regular());
    assertNotEquals(original, stamp(node(2, 3, OsConstants.S_IFREG | 0644, 100, 1, 2)));
    assertNotEquals(original, stamp(node(1, 4, OsConstants.S_IFREG | 0644, 100, 1, 2)));
    assertNotEquals(original, stamp(node(1, 3, OsConstants.S_IFREG | 0644, 101, 1, 2)));
    assertNotEquals(original, stamp(node(1, 3, OsConstants.S_IFREG | 0755, 100, 1, 2)));
    assertNotEquals(
        original,
        RuntimeAssetStamp.read(
            ROOT,
            List.of(ASSET),
            path ->
                path.equals(ROOT)
                    ? node(1, 7, OsConstants.S_IFDIR | 0755, 4096, 0, 0)
                    : regular()));
  }

  @Test
  public void linksDirectoriesAndSpecialAssetsNeverReusePreparedState() {
    for (int type : new int[] {OsConstants.S_IFLNK, OsConstants.S_IFDIR, OsConstants.S_IFIFO})
      assertNull(stamp(node(1, 3, type | 0644, 100, 1, 2)));
    assertNull(
        RuntimeAssetStamp.read(
            ROOT,
            List.of(ASSET),
            path ->
                path.equals(ROOT)
                    ? node(1, 2, OsConstants.S_IFLNK | 0755, 4096, 0, 0)
                    : regular()));
  }

  @Test
  public void missingUnreadableAndIoErrorsAllInvalidatePreparedState() {
    for (int errno : new int[] {OsConstants.ENOENT, OsConstants.EACCES, OsConstants.EIO})
      assertNull(
          RuntimeAssetStamp.read(
              ROOT,
              List.of(ASSET),
              path -> {
                if (path.equals(ROOT)) return directory();
                throw new ErrnoException("lstat", errno);
              }));
  }

  @Test
  public void unavailableEntryDiagnosticUsesTheSameReadThatRejectedTheSnapshot() {
    File second = new File(ROOT, "second-managed-script.js");
    AtomicInteger calls = new AtomicInteger();
    var result =
        RuntimeAssetStamp.inspect(
            ROOT,
            List.of(ASSET, second),
            path -> {
              calls.incrementAndGet();
              if (path.equals(ROOT)) return directory();
              if (path.equals(ASSET)) return regular();
              throw new ErrnoException("lstat", OsConstants.EACCES);
            });
    assertNull(result.value);
    assertEquals(1, result.unavailableEntry);
    assertEquals(3, calls.get());
  }
}
