package com.deepseekharness.app.plugins;

import static org.junit.Assert.*;

import android.os.Looper;
import com.deepseekharness.app.backup.BackupFileSystem;
import com.deepseekharness.app.backup.JvmBackupFileSystem;
import com.deepseekharness.app.backup.UserDataLayout;
import com.deepseekharness.app.plugins.PluginChangeDetector.Kind;
import com.deepseekharness.app.plugins.PluginChangeDetector.WatchPath;
import java.io.File;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.LongSupplier;
import org.junit.Rule;
import org.junit.After;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34, manifest = Config.NONE, application = android.app.Application.class)
public final class PluginBackendAdapterTest {
  @Rule public TemporaryFolder temporary = new TemporaryFolder();

  @After
  public void closeHostFiles() {
    PluginHostFiles.close();
  }

  /** 解析若出现初始化、递归枚举或写盘，测试直接失败。 */
  private static class ReadOnlyFiles extends JvmBackupFileSystem {
    int stats;

    @Override
    public Node stat(File path) throws IOException {
      stats++;
      return PluginHostFiles.stat(path);
    }

    @Override
    public List<String> list(File path) {
      throw new AssertionError("来源解析不得扫描目录内容");
    }

    @Override
    public OutputStream create(File path) {
      throw new AssertionError("不得创建文件");
    }

    @Override
    public void directory(File path) {
      throw new AssertionError("不得初始化环境");
    }

    @Override
    public void move(File source, File target) {
      throw new AssertionError("不得移动原件");
    }

    @Override
    public void delete(File path) {
      throw new AssertionError("不得删除原件");
    }

    @Override
    public void mode(File path, int mode) {
      throw new AssertionError("不得改变权限");
    }

    @Override
    public void symlink(String target, File link) {
      throw new AssertionError("不得建立链接");
    }

    @Override
    public void syncDirectory(File path) {
      throw new AssertionError("不得写目录");
    }
  }

  private static File directory(File root, String relative) throws IOException {
    return Files.createDirectories(new File(root, relative).toPath()).toFile();
  }

  private static File file(File root, String relative, byte[] bytes) throws IOException {
    Path path = new File(root, relative).toPath();
    Files.createDirectories(path.getParent());
    Files.write(path, bytes);
    return path.toFile();
  }

  private static File text(File root, String relative, String text) throws IOException {
    return file(root, relative, text.getBytes(StandardCharsets.UTF_8));
  }

  private File files() throws IOException {
    File root = temporary.newFolder();
    directory(root, "linux/ubuntu/root/.dsh");
    return root;
  }

  private PluginBackendAdapter adapter(File files, BackupFileSystem fs, LongSupplier generation) {
    return new PluginBackendAdapter(fs, files, new File(files, "linux/ubuntu"), generation);
  }

  @Test
  public void constructorDoesNoIoAndLegacyResolutionExposesActualDeviceAndInode() throws Exception {
    File files = files();
    ReadOnlyFiles fs = new ReadOnlyFiles();
    PluginBackendAdapter adapter = adapter(files, fs, () -> 8);
    assertEquals(0, fs.stats);
    PluginBackendAdapter.ResolvedSource result = adapter.resolveSource();
    File data = new File(files, UserDataLayout.LEGACY);
    assertEquals(data.getCanonicalFile(), result.identity.dataRoot);
    assertEquals(new File(files, "linux/ubuntu").getCanonicalFile(), result.identity.rootfs);
    assertEquals(fs.stat(data).key, result.identity.dataRootKey);
    assertEquals(fs.stat(data).device, result.identity.dataRootDevice);
    assertEquals(fs.stat(result.identity.rootfs).key, result.identity.rootfsKey);
    assertEquals(fs.stat(result.identity.rootfs).device, result.identity.rootfsDevice);
    assertEquals(8, result.identity.webGeneration);
    assertTrue(result.identity.runtimeMarkerSha256.matches("[a-f0-9]{64}"));
    assertThrows(UnsupportedOperationException.class, () -> result.observedPaths.clear());
  }

  @Test
  public void locationRecordSelectsStableRootEvenWhileLegacyStillExists() throws Exception {
    File files = files();
    File stable = directory(files, UserDataLayout.STABLE);
    file(files, UserDataLayout.RECORD, UserDataLayout.record(UserDataLayout.Home.STABLE));
    PluginBackendAdapter.ResolvedSource resolved =
        adapter(files, new ReadOnlyFiles(), () -> 1).resolveSource();
    assertEquals(stable, resolved.identity.dataRoot);
    assertTrue(
        resolved.observedPaths.stream()
            .anyMatch(path -> path.path.equals(new File(stable, "profiles")) && path.recursive));
    assertFalse(
        resolved.observedPaths.stream()
            .anyMatch(
                path -> path.path.equals(new File(files, UserDataLayout.LEGACY + "/profiles"))));
  }

  @Test
  public void previousLocationRecordIsReadWithoutRecoveringOrWritingIt() throws Exception {
    File files = files();
    File stable = directory(files, UserDataLayout.STABLE);
    File previous =
        file(
            files,
            UserDataLayout.RECORD + ".previous",
            UserDataLayout.record(UserDataLayout.Home.STABLE));
    assertEquals(
        stable, adapter(files, new ReadOnlyFiles(), () -> 0).resolveSource().identity.dataRoot);
    assertTrue(previous.isFile());
    assertFalse(new File(files, UserDataLayout.RECORD).exists());
  }

  @Test
  public void ambiguousLocationAndMissingEnvironmentNeverBecomeAnEmptyInstallation()
      throws Exception {
    File files = files();
    directory(files, UserDataLayout.STABLE);
    IOException ambiguous =
        assertThrows(
            IOException.class, () -> adapter(files, new ReadOnlyFiles(), () -> 0).resolveSource());
    assertEquals("DATA_LOCATION_AMBIGUOUS", ambiguous.getMessage());
    File absent = temporary.newFolder();
    assertThrows(
        IOException.class, () -> adapter(absent, new ReadOnlyFiles(), () -> 0).resolveSource());
    assertEquals(0, absent.list().length);
  }

  @Test
  public void markerContentChangeWithSameSizeAndMtimeChangesSource() throws Exception {
    File files = files();
    File marker = text(files, "linux/.runtime-descriptor.json", "runtime-one");
    FileTime time = Files.getLastModifiedTime(marker.toPath());
    PluginBackendAdapter adapter = adapter(files, new ReadOnlyFiles(), () -> 0);
    PluginSourceIdentity before = adapter.resolveSource().identity;
    Files.writeString(marker.toPath(), "runtime-two");
    Files.setLastModifiedTime(marker.toPath(), time);
    PluginSourceIdentity after = adapter.resolveSource().identity;
    assertEquals(before.rootfsKey, after.rootfsKey);
    assertEquals(before.dataRootKey, after.dataRootKey);
    assertNotEquals(before.runtimeMarkerSha256, after.runtimeMarkerSha256);
    assertNotEquals(before, after);
  }

  @Test
  public void changedDirectoryAndMarkerMtimeAloneDoNotPretendToBeContentValidation()
      throws Exception {
    File files = files();
    File marker = text(files, "linux/.offline-identity", "same-content");
    PluginBackendAdapter adapter = adapter(files, new ReadOnlyFiles(), () -> 0);
    PluginSourceIdentity before = adapter.resolveSource().identity;
    Files.setLastModifiedTime(marker.toPath(), FileTime.fromMillis(1000));
    Files.setLastModifiedTime(before.dataRoot.toPath(), FileTime.fromMillis(2000));
    Files.setLastModifiedTime(before.rootfs.toPath(), FileTime.fromMillis(3000));
    PluginSourceIdentity after = adapter.resolveSource().identity;
    assertEquals(before, after);
    assertEquals(before.hashCode(), after.hashCode());
  }

  @Test
  public void replacingDataDirectoryAtSamePathChangesItsIdentity() throws Exception {
    File files = files();
    PluginBackendAdapter adapter = adapter(files, new ReadOnlyFiles(), () -> 0);
    PluginSourceIdentity before = adapter.resolveSource().identity;
    FileTime time = Files.getLastModifiedTime(before.dataRoot.toPath());
    Files.move(before.dataRoot.toPath(), before.dataRoot.toPath().resolveSibling("retained-data"));
    Files.createDirectory(before.dataRoot.toPath());
    Files.setLastModifiedTime(before.dataRoot.toPath(), time);
    PluginSourceIdentity after = adapter.resolveSource().identity;
    assertEquals(before.dataRoot, after.dataRoot);
    assertNotEquals(before.dataRootKey, after.dataRootKey);
    assertNotEquals(before, after);
  }

  @Test
  public void replacingRuntimeRootIsVisibleEvenWithStableUserData() throws Exception {
    File files = files();
    directory(files, UserDataLayout.STABLE);
    file(files, UserDataLayout.RECORD, UserDataLayout.record(UserDataLayout.Home.STABLE));
    PluginBackendAdapter adapter = adapter(files, new ReadOnlyFiles(), () -> 0);
    PluginSourceIdentity before = adapter.resolveSource().identity;
    Files.move(before.rootfs.toPath(), before.rootfs.toPath().resolveSibling("retained-ubuntu"));
    Files.createDirectory(before.rootfs.toPath());
    PluginSourceIdentity after = adapter.resolveSource().identity;
    assertEquals(before.dataRootKey, after.dataRootKey);
    assertNotEquals(before.rootfsKey, after.rootfsKey);
    assertNotEquals(before, after);
  }

  @Test
  public void webGenerationChangeDuringResolutionRejectsMixedSource() throws Exception {
    File files = files();
    AtomicInteger generation = new AtomicInteger();
    IOException failure =
        assertThrows(
            IOException.class,
            () -> adapter(files, new ReadOnlyFiles(), generation::incrementAndGet).resolveSource());
    assertEquals("PLUGIN_SOURCE_CHANGED", failure.getMessage());
  }

  @Test
  public void changedSelectionRecordDuringReadIsRejected() throws Exception {
    File files = files();
    directory(files, UserDataLayout.STABLE);
    File record =
        file(files, UserDataLayout.RECORD, UserDataLayout.record(UserDataLayout.Home.LEGACY));
    ReadOnlyFiles fs =
        new ReadOnlyFiles() {
          int recordReads;

          @Override
          public byte[] small(File path, int limit) throws IOException {
            byte[] bytes = super.small(path, limit);
            if (path.equals(record) && ++recordReads == 2)
              Files.write(record.toPath(), UserDataLayout.record(UserDataLayout.Home.STABLE));
            return bytes;
          }
        };
    IOException failure =
        assertThrows(IOException.class, () -> adapter(files, fs, () -> 0).resolveSource());
    assertEquals("PLUGIN_SOURCE_CHANGED", failure.getMessage());
  }

  @Test
  public void unsafeWatchRootsAndParentLinksNeverFollowExternalTrees() throws Exception {
    File files = files(), external = temporary.newFolder();
    Path link = new File(files, UserDataLayout.LEGACY + "/plugin-src").toPath();
    PluginHostFiles.symlink(link, external.toPath());
    IOException failure =
        assertThrows(
            IOException.class, () -> adapter(files, new ReadOnlyFiles(), () -> 0).resolveSource());
    assertEquals("PLUGIN_SOURCE_UNSAFE_PATH", failure.getMessage());
    File other = files();
    PluginHostFiles.symlink(new File(other, "linux/ubuntu/usr").toPath(), external.toPath());
    assertThrows(
        IOException.class, () -> adapter(other, new ReadOnlyFiles(), () -> 0).resolveSource());
    assertEquals(0, external.list().length);
  }

  @Test
  public void unsafeDataSelectionAndRootOutsidePrivateDirectoryAreRejected() throws Exception {
    File files = files(), external = temporary.newFolder();
    directory(files, "user-data-v5");
    PluginHostFiles.symlink(new File(files, UserDataLayout.STABLE).toPath(), external.toPath());
    file(files, UserDataLayout.RECORD, UserDataLayout.record(UserDataLayout.Home.STABLE));
    assertThrows(
        IOException.class, () -> adapter(files, new ReadOnlyFiles(), () -> 0).resolveSource());
    assertThrows(
        IOException.class,
        () ->
            new PluginBackendAdapter(new ReadOnlyFiles(), files, external, () -> 0)
                .resolveSource());
  }

  @Test
  public void unreadableSourceIsNotSilentlyConvertedToMissing() throws Exception {
    File files = files();
    ReadOnlyFiles fs =
        new ReadOnlyFiles() {
          @Override
          public Node stat(File path) throws IOException {
            if (path.getName().equals(UserDataLayout.RECORD))
              throw new IOException("PERMISSION_DENIED");
            return super.stat(path);
          }
        };
    IOException failure =
        assertThrows(IOException.class, () -> adapter(files, fs, () -> 0).resolveSource());
    assertEquals("PERMISSION_DENIED", failure.getMessage());
  }

  @Test
  public void missingAndEmptyMarkerHaveDistinctIdentityAndLargeMarkerIsRejected() throws Exception {
    File files = files();
    PluginBackendAdapter adapter = adapter(files, new ReadOnlyFiles(), () -> 0);
    PluginSourceIdentity missing = adapter.resolveSource().identity;
    File marker = text(files, "linux/.runtime-descriptor.json", "");
    assertNotEquals(missing, adapter.resolveSource().identity);
    try (java.io.RandomAccessFile oversized = new java.io.RandomAccessFile(marker, "rw")) {
      oversized.setLength(2 * 1024 * 1024 + 1);
    }
    IOException failure = assertThrows(IOException.class, adapter::resolveSource);
    assertEquals("METADATA_LIMIT", failure.getMessage());
  }

  @Test
  public void watchesSeparateRuntimeHeartbeatAndCoverActualMutableInputs() throws Exception {
    File files = files();
    PluginBackendAdapter.ResolvedSource resolved =
        adapter(files, new ReadOnlyFiles(), () -> 0).resolveSource();
    File data = resolved.identity.dataRoot, root = resolved.identity.rootfs;
    for (String relative :
        new String[] {
          "plugin-src", "node_modules", "profiles", "plugin-previews", "plugin-install-operations"
        }) assertWatch(resolved.observedPaths, new File(data, relative), Kind.CONTENT, true);
    assertWatch(
        resolved.observedPaths, new File(data, "plugin-activations.json"), Kind.CONTENT, false);
    assertWatch(
        resolved.observedPaths, new File(data, "plugin-safe-mode.json"), Kind.CONTENT, false);
    assertWatch(
        resolved.observedPaths, new File(root, "usr/local/lib/node_modules"), Kind.CONTENT, true);
    assertWatch(
        resolved.observedPaths,
        new File(root, "root/.dsha-web-activity.json"),
        Kind.RUNTIME_PROJECTION,
        false);
    assertWatch(resolved.observedPaths, root, Kind.SOURCE_IDENTITY, false);
    assertWatch(resolved.observedPaths, data, Kind.SOURCE_IDENTITY, false);
    assertWatch(
        resolved.observedPaths,
        new File(files, UserDataLayout.RECORD),
        Kind.SOURCE_IDENTITY,
        false);
    for (WatchPath watch : resolved.observedPaths) {
      assertTrue(
          watch.path.equals(watch.trustedRoot)
              || watch
                  .path
                  .getAbsolutePath()
                  .startsWith(watch.trustedRoot.getAbsolutePath() + File.separator));
    }
  }

  @Test
  public void persistentSourceEqualityExcludesOnlyWebGeneration() throws Exception {
    File files = files();
    PluginSourceIdentity first =
        adapter(files, new ReadOnlyFiles(), () -> 1).resolveSource().identity;
    PluginSourceIdentity nextWeb =
        adapter(files, new ReadOnlyFiles(), () -> 2).resolveSource().identity;
    assertTrue(first.sameDataSource(nextWeb));
    assertFalse(first.sameDataSource(null));
    assertNotEquals(first, nextWeb);
    text(files, "linux/.offline-identity", "changed-runtime");
    PluginSourceIdentity nextRuntime =
        adapter(files, new ReadOnlyFiles(), () -> 1).resolveSource().identity;
    assertFalse(first.sameDataSource(nextRuntime));
    assertNotEquals(first, nextRuntime);
  }

  @Test
  public void frameworkAuthorityAllowsPlatformAliasButDoesNotCanonicalizeWritableFiles()
      throws Exception {
    File authority = temporary.newFolder();
    File files = directory(authority, "files");
    directory(files, UserDataLayout.LEGACY);
    File alias = new File(temporary.newFolder(), "framework-alias");
    PluginHostFiles.symlink(alias.toPath(), authority.toPath());
    File declaredFiles = new File(alias, "files");
    ReadOnlyFiles fs = new ReadOnlyFiles();
    PluginBackendAdapter adapter =
        new PluginBackendAdapter(
            fs, alias, declaredFiles, new File(declaredFiles, "linux/ubuntu"), () -> 5);
    assertEquals(0, fs.stats);
    assertEquals(new File(files, UserDataLayout.LEGACY), adapter.resolveSource().identity.dataRoot);
    assertEquals(new File(files, "linux/ubuntu"), adapter.resolveSource().identity.rootfs);
  }

  @Test
  public void frameworkFilesSymlinkIsRejectedBeforeExternalContentsAreObserved() throws Exception {
    File authority = temporary.newFolder(), external = files();
    File filesLink = new File(authority, "files");
    PluginHostFiles.symlink(filesLink.toPath(), external.toPath());
    ReadOnlyFiles fs =
        new ReadOnlyFiles() {
          @Override
          public Node stat(File path) throws IOException {
            if (path.equals(external)
                || path.getPath().startsWith(external.getPath() + File.separator))
              throw new AssertionError("不得观察外部链接目标");
            return super.stat(path);
          }
        };
    PluginBackendAdapter adapter =
        new PluginBackendAdapter(
            fs, authority, filesLink, new File(filesLink, "linux/ubuntu"), () -> 0);
    IOException error = assertThrows(IOException.class, adapter::resolveSource);
    assertEquals("COLD_PACKAGE_FILES_LINK_OR_MISSING", error.getMessage());
    assertThrows(IOException.class, adapter::environmentBlockMessage);
  }

  @Test
  public void declaredFilesOutsideFrameworkAuthorityAreRejected() throws Exception {
    File authority = temporary.newFolder(), otherAuthority = temporary.newFolder();
    File otherFiles = directory(otherAuthority, "files");
    directory(otherFiles, UserDataLayout.LEGACY);
    PluginBackendAdapter adapter =
        new PluginBackendAdapter(
            new ReadOnlyFiles(),
            authority,
            otherFiles,
            new File(otherFiles, "linux/ubuntu"),
            () -> 0);
    IOException error = assertThrows(IOException.class, adapter::resolveSource);
    assertEquals("COLD_PACKAGE_HOST_ROOT", error.getMessage());
  }

  @Test
  public void frameworkFilesInodeIsRecheckedAtResolutionBoundary() throws Exception {
    File authority = temporary.newFolder(), files = directory(authority, "files");
    directory(files, UserDataLayout.LEGACY);
    AtomicInteger reads = new AtomicInteger();
    LongSupplier generation =
        () -> {
          if (reads.incrementAndGet() == 2) {
            try {
              Files.move(files.toPath(), new File(authority, "retained-files").toPath());
              Files.createDirectory(files.toPath());
            } catch (IOException error) {
              throw new java.io.UncheckedIOException(error);
            }
          }
          return 1;
        };
    PluginBackendAdapter adapter =
        new PluginBackendAdapter(
            new ReadOnlyFiles(), authority, files, new File(files, "linux/ubuntu"), generation);
    IOException error = assertThrows(IOException.class, adapter::resolveSource);
    assertEquals("COLD_PACKAGE_HOST_ROOT_CHANGED", error.getMessage());
  }

  @Test
  public void injectedTrustedRootStillRejectsAWritableFilesSymlink() throws Exception {
    File external = files();
    File link = new File(temporary.newFolder(), "files");
    PluginHostFiles.symlink(link.toPath(), external.toPath());
    IOException error =
        assertThrows(
            IOException.class, () -> adapter(link, new ReadOnlyFiles(), () -> 0).resolveSource());
    assertEquals("PLUGIN_SOURCE_ROOT_UNAVAILABLE", error.getMessage());
  }

  @Test
  public void installationAndManagedMarkersUseTheirActualDistinctHostDirectories()
      throws Exception {
    File files = files(), root = new File(files, "linux/ubuntu");
    PluginBackendAdapter adapter = adapter(files, new ReadOnlyFiles(), () -> 0);
    PluginBackendAdapter.ResolvedSource initial = adapter.resolveSource();
    for (String name :
        new String[] {
          ".offline-extracted", ".offline-version", ".offline-identity", ".runtime-descriptor.json"
        }) {
      assertWatch(
          initial.observedPaths, new File(files, "linux/" + name), Kind.SOURCE_IDENTITY, false);
      assertFalse(
          initial.observedPaths.stream().anyMatch(w -> w.path.equals(new File(root, name))));
    }
    for (String relative :
        new String[] {
          "usr/local/share/dsha/dsh-runtime.version",
          "usr/local/share/dsha/managed-assets-v2",
          "root/.dsh/script-version",
          "root/.dsh/builtin-plugins.json",
          "root/.dsh/managed-package-proofs.json"
        })
      assertWatch(initial.observedPaths, new File(root, relative), Kind.SOURCE_IDENTITY, false);
    assertFalse(
        initial.observedPaths.stream()
            .anyMatch(w -> w.path.getName().equals(".runtime-health.json")));
    text(root, ".offline-identity", "wrong-directory");
    text(files, "linux/.runtime-health.json", "not-the-health-store");
    assertEquals(initial.identity, adapter.resolveSource().identity);
    text(files, "linux/.offline-identity", "actual-base-marker");
    PluginSourceIdentity installed = adapter.resolveSource().identity;
    assertNotEquals(initial.identity, installed);
    text(root, "usr/local/share/dsha/managed-assets-v2", "actual-managed-marker");
    assertNotEquals(installed, adapter.resolveSource().identity);
  }

  @Test
  public void nativeResolverRejectsMainThreadBeforeAnyFilesystemOrGenerationRead()
      throws Exception {
    assertEquals(Looper.getMainLooper(), Looper.myLooper());
    AtomicInteger generationReads = new AtomicInteger();
    PluginBackendAdapter adapter =
        new PluginBackendAdapter(
            RuntimeEnvironment.getApplication(), generationReads::incrementAndGet);
    IOException failure = assertThrows(IOException.class, adapter::resolveSource);
    assertEquals("PLUGIN_SOURCE_REQUIRES_BACKGROUND", failure.getMessage());
    assertEquals(0, generationReads.get());
  }

  private static void assertWatch(List<WatchPath> paths, File path, Kind kind, boolean recursive) {
    assertTrue(
        path.getPath(),
        paths.stream()
            .anyMatch(
                watch ->
                    watch.path.equals(path) && watch.kind == kind && watch.recursive == recursive));
  }
}
