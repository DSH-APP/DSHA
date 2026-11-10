package com.deepseekharness.app.saf;

import static org.junit.Assert.*;

import android.app.Application;
import android.content.ContentProvider;
import android.content.ContentValues;
import android.content.pm.ProviderInfo;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;
import android.content.res.AssetFileDescriptor;
import android.os.Bundle;
import android.os.CancellationSignal;
import android.os.OperationCanceledException;
import android.os.ParcelFileDescriptor;
import android.provider.DocumentsContract;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowContentResolver;

/**
 * SafTree 的边界、超时与错误码映射。
 *
 * <p>用受控 FakeProvider 覆盖：空/根路径删除被拒、offset 越界、createDocument 返回树外 URI、
 * 目录超过扫描上限、取消映射 TIMEOUT、SecurityException/PROVIDER_ERROR 映射，以及
 * 「Provider 很慢时 write/mkdir 必须在本地截止时间内返回 TIMEOUT」。
 */
@RunWith(SafTreeTest.NoApkRunner.class)
@Config(sdk = 34, manifest = Config.NONE, application = Application.class)
public final class SafTreeTest {
  private static final String AUTH = "test.dsha.saftree";
  private static final String TREE = "content://" + AUTH + "/tree/ROOT";
  private static final long SHORT_DEADLINE_MS = 300;

  public static final class NoApkRunner extends RobolectricTestRunner {
    public NoApkRunner(Class<?> type) throws org.junit.runners.model.InitializationError {
      super(type);
    }

    @Override
    protected Properties getBuildSystemApiProperties() {
      return new Properties();
    }
  }

  /** 一个最小的 DocumentsProvider：只实现测试需要的查询/创建/打开。 */
  public static final class FakeProvider extends ContentProvider {
    final Map<String, String[]> documents = new HashMap<>();
    final Map<String, File> files = new HashMap<>();
    File storage;
    Uri treeUri = Uri.parse(TREE);
    String lastCreatedId;
    final List<Uri> deleted = new ArrayList<>();
    final List<Uri> created = new ArrayList<>();
    int queries;
    volatile String failure; // cancel / security / runtime
    volatile boolean slowCreate;
    volatile boolean outsideCreate;
    volatile boolean delayChildrenAfterCreate;
    volatile boolean failChildrenAfterCreate;
    volatile String forcedChildId;
    volatile boolean hideParentColumn;
    volatile boolean isChildSupported = true;
    final java.util.Set<String> hiddenChildren = new java.util.HashSet<>();
    final java.util.Map<String, String> createdParents = new java.util.HashMap<>();
    volatile boolean slowOpen;
    volatile boolean oversizedListing;

    @Override
    public boolean onCreate() {
      return true;
    }

    @Override
    public Cursor query(
        Uri uri, String[] projection, String selection, String[] args, String order) {
      return query(uri, projection, selection, args, order, null);
    }

    @Override
    public Cursor query(
        Uri uri,
        String[] projection,
        String selection,
        String[] args,
        String order,
        CancellationSignal signal) {
      queries++;
      if ("cancel".equals(failure)) throw new OperationCanceledException();
      if ("security".equals(failure)) throw new SecurityException("denied");
      if ("runtime".equals(failure)) throw new IllegalStateException("provider boom");
      String[] columns =
          projection == null
              ? new String[] {
                DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                DocumentsContract.Document.COLUMN_MIME_TYPE,
                DocumentsContract.Document.COLUMN_SIZE,
                DocumentsContract.Document.COLUMN_LAST_MODIFIED
              }
              : projection;
      MatrixCursor cursor = new MatrixCursor(columns);
      List<String> segments = uri.getPathSegments();
      boolean childQuery =
          !segments.isEmpty() && "children".equals(segments.get(segments.size() - 1));
      if (childQuery) {
        String parent = segments.size() < 2 ? "" : segments.get(segments.size() - 2);
        List<String> names = new ArrayList<>();
        for (Map.Entry<String, String[]> row : documents.entrySet())
          if (parent.equals(row.getValue()[0]) && !hiddenChildren.contains(row.getKey()))
            names.add(row.getKey());
        if (oversizedListing) {
          for (int i = 0; i < SafTree.MAX_ENTRIES + 1; i++)
            cursor.addRow(row(columns, "filler-" + i, "filler-" + i, "text/plain", -1L));
        } else {
          for (String name : names) cursor.addRow(rowOf(columns, name));
        }
      } else {
        String id;
        try {
          id = DocumentsContract.getDocumentId(uri);
        } catch (Throwable malformed) {
          id = segments.isEmpty() ? "" : segments.get(segments.size() - 1);
        }
        String[] doc = documents.get(id);
        if (doc == null) return cursor;
        if (hideParentColumn && contains(columns, COLUMN_PARENT)) {
          Object[] values = rowOf(columns, id);
          for (int i = 0; i < columns.length; i++)
            if (COLUMN_PARENT.equals(columns[i])) values[i] = null;
          cursor.addRow(values);
        } else {
          cursor.addRow(rowOf(columns, id));
        }
      }
      return cursor;
    }

    private static boolean contains(String[] values, String wanted) {
      for (String value : values) if (wanted.equals(value)) return true;
      return false;
    }

    private String parentId(Uri parent) {
      return parent == null ? "" : DocumentsContract.getDocumentId(parent);
    }

    private Object[] rowOf(String[] columns, String id) {
      String[] doc = documents.get(id);
      long size = files.containsKey(id) ? files.get(id).length() : Long.parseLong(doc[3]);
      return row(columns, id, doc[1], doc[2], size);
    }

    private Object[] row(String[] columns, String id, String name, String mime, long size) {
      Object[] values = new Object[columns.length];
      for (int i = 0; i < columns.length; i++) {
        values[i] =
            switch (columns[i]) {
              case DocumentsContract.Document.COLUMN_DOCUMENT_ID -> id;
              case DocumentsContract.Document.COLUMN_DISPLAY_NAME -> name;
              case DocumentsContract.Document.COLUMN_MIME_TYPE -> mime;
              case DocumentsContract.Document.COLUMN_SIZE -> size;
              case DocumentsContract.Document.COLUMN_LAST_MODIFIED -> 0L;
              case COLUMN_PARENT -> documents.containsKey(id) ? documents.get(id)[0] : null;
              default -> null;
            };
      }
      return values;
    }

    @Override
    public Bundle call(String method, String arg, Bundle extras) {
      Bundle result = new Bundle();
      if ("android:createDocument".equals(method)) {
        if (slowCreate) sleep(700);
        Uri parent = extras.getParcelable("uri");
        String childId = "OTHER";
        Uri createBase = treeUri;
        if (forcedChildId != null) {
          childId = forcedChildId;
        } else if (!outsideCreate) {
          childId = "new-" + created.size();
          lastCreatedId = childId;
        } else {
          createBase = Uri.parse("content://" + AUTH + "/tree/OTHER");
        }
        Uri created = DocumentsContract.buildDocumentUriUsingTree(createBase, childId);
        if (forcedChildId == null && !outsideCreate && storage != null) {
          String display = extras.getString("_display_name", "new.txt");
          String mime = extras.getString("mime_type", "text/plain");
          File file = new File(storage, "created-" + childId);
          try {
            if (!file.createNewFile()) throw new IllegalStateException("fixture file");
          } catch (java.io.IOException error) {
            throw new IllegalStateException(error);
          }
          files.put(childId, file);
          documents.put(childId, new String[] {parentId(parent), display, mime, "0"});
          createdParents.put(childId, parentId(parent));
        }
        this.created.add(created);
        result.putParcelable("uri", created);
        if (delayChildrenAfterCreate && !outsideCreate) hiddenChildren.add(childId);
        if (failChildrenAfterCreate) failure = "runtime";
        return result;
      }
      if ("android:isChildDocument".equals(method)) {
        Uri parent = extras.getParcelable("uri");
        Uri child = extras.getParcelable("android.content.extra.TARGET_URI");
        if (child == null) child = extras.getParcelable("targetParentDocumentUri");
        if (child == null) child = extras.getParcelable("targetUri");
        String parentId = parent == null ? "" : DocumentsContract.getDocumentId(parent);
        String childId = child == null ? "" : DocumentsContract.getDocumentId(child);
        result.putBoolean(
            "result",
            isChildSupported && child != null && parentId.equals(createdParents.get(childId)));
        return result;
      }
      if ("android:deleteDocument".equals(method)) {
        deleted.add(extras.getParcelable("uri"));
        return result;
      }
      throw new IllegalArgumentException(method);
    }

    @Override
    public ParcelFileDescriptor openFile(Uri uri, String mode) {
      String docId = DocumentsContract.getDocumentId(uri);
      File file = files.get(docId);
      if (file == null) return null;
      int flags =
          mode.contains("w")
              ? ParcelFileDescriptor.MODE_WRITE_ONLY
                  | ParcelFileDescriptor.MODE_CREATE
                  | ParcelFileDescriptor.MODE_TRUNCATE
              : ParcelFileDescriptor.MODE_READ_ONLY;
      try {
        return ParcelFileDescriptor.open(file, flags);
      } catch (java.io.FileNotFoundException error) {
        return null;
      }
    }

    @Override
    public AssetFileDescriptor openAssetFile(Uri uri, String mode, CancellationSignal signal) {
      if (slowOpen) {
        CountDownLatch release = new CountDownLatch(1);
        if (signal != null) signal.setOnCancelListener(release::countDown);
        try {
          release.await(5, TimeUnit.SECONDS);
        } catch (InterruptedException interrupted) {
          Thread.currentThread().interrupt();
        }
        throw new OperationCanceledException();
      }
      ParcelFileDescriptor descriptor = openFile(uri, mode);
      return descriptor == null
          ? null
          : new AssetFileDescriptor(descriptor, 0, AssetFileDescriptor.UNKNOWN_LENGTH);
    }

    @Override
    public String getType(Uri uri) {
      return "text/plain";
    }

    @Override
    public Uri insert(Uri uri, ContentValues values) {
      throw new UnsupportedOperationException();
    }

    @Override
    public int delete(Uri uri, String selection, String[] args) {
      deleted.add(uri);
      return 1;
    }

    @Override
    public int update(Uri uri, ContentValues values, String selection, String[] args) {
      throw new UnsupportedOperationException();
    }
  }

  private static final String ROOT = "ROOT";
  private static final String COLUMN_PARENT = "parent_document_id";

  private FakeProvider provider;
  private SafTree tree;

  @Before
  public void setUp() throws Exception {
    provider = new FakeProvider();
    provider.documents.put(
        ROOT, new String[] {"", "root", DocumentsContract.Document.MIME_TYPE_DIR, "0"});
    provider.documents.put(
        "subdir", new String[] {ROOT, "subdir", DocumentsContract.Document.MIME_TYPE_DIR, "0"});
    provider.documents.put("subdir/child", new String[] {"subdir", "child", "text/plain", "1"});
    provider.documents.put(
        "emptydir", new String[] {ROOT, "emptydir", DocumentsContract.Document.MIME_TYPE_DIR, "0"});
    ProviderInfo info = new ProviderInfo();
    info.authority = AUTH;
    info.exported = true;
    info.grantUriPermissions = true;
    provider.attachInfo(RuntimeEnvironment.getApplication(), info);
    provider.storage = Files.createTempDirectory("dsha-saf-tree-").toFile();
    File existing = new File(provider.storage, "file.txt");
    Files.write(existing.toPath(), "hello".getBytes(StandardCharsets.UTF_8));
    provider.files.put("file.txt", existing);
    provider.documents.put("file.txt", new String[] {ROOT, "file.txt", "text/plain", "5"});
    ShadowContentResolver.registerProviderInternal(AUTH, provider);
    tree =
        new SafTree(
            RuntimeEnvironment.getApplication().getContentResolver(),
            Uri.parse(TREE),
            SHORT_DEADLINE_MS);
  }

  private static void sleep(long ms) {
    try {
      Thread.sleep(ms);
    } catch (InterruptedException interrupted) {
      Thread.currentThread().interrupt();
    }
  }

  @Test
  public void deleteEmptyOrRootPathIsRejectedBeforeAnyProviderCall() {
    SafTree.Failure empty = assertThrows(SafTree.Failure.class, () -> tree.delete(""));
    assertEquals("BAD_PATH", empty.code);
    SafTree.Failure slash = assertThrows(SafTree.Failure.class, () -> tree.delete("/"));
    assertEquals("BAD_PATH", slash.code);
    assertEquals(0, provider.queries);
  }

  @Test
  public void offsetBeyondReportedSizeIsRejectedWithoutOpeningFile() {
    SafTree.Failure failure =
        assertThrows(SafTree.Failure.class, () -> tree.read("file.txt", 11, 10));
    assertEquals("OFFSET_OUT_OF_RANGE", failure.code);
  }

  @Test
  public void emptyDirectoryProbeReturnsTokenThenDeletes() throws Exception {
    SafTree.PendingDelete pending = tree.delete("emptydir");
    assertTrue("未确认前不能删除", provider.deleted.isEmpty());
    assertTrue(pending.token.length() >= 16);

    tree.deleteConfirmed("emptydir", pending.token);
    assertEquals(1, provider.deleted.size());
  }

  @Test
  public void nonEmptyDirectoryTokenDeletesContentsRecursively() throws Exception {
    SafTree.PendingDelete pending = tree.delete("subdir");
    assertTrue("未确认前不能删除", provider.deleted.isEmpty());
    assertTrue(pending.hint.contains("子项"));

    tree.deleteConfirmed("subdir", pending.token);
    assertEquals("先删子项再删目录", 2, provider.deleted.size());
  }

  @Test
  public void fileDeleteAlsoUsesOneTimeToken() throws Exception {
    SafTree.PendingDelete pending = tree.delete("file.txt");
    assertTrue("文件未确认前不能删除", provider.deleted.isEmpty());

    tree.deleteConfirmed("file.txt", pending.token);
    assertEquals(1, provider.deleted.size());
  }

  @Test
  public void confirmWithoutTokenOrWithWrongTokenIsRejected() {
    assertThrows(SafTree.Failure.class, () -> tree.deleteConfirmed("file.txt", ""));
    assertThrows(SafTree.Failure.class, () -> tree.deleteConfirmed("file.txt", "not-a-real-token"));
    assertTrue(provider.deleted.isEmpty());
  }

  @Test
  public void tokenIsOneTimeAndBoundToPath() throws Exception {
    SafTree.PendingDelete pending = tree.delete("file.txt");
    assertThrows(SafTree.Failure.class, () -> tree.deleteConfirmed("emptydir", pending.token));
    SafTree.PendingDelete again = tree.delete("file.txt");
    tree.deleteConfirmed("file.txt", again.token);
    SafTree.Failure consumed =
        assertThrows(SafTree.Failure.class, () -> tree.deleteConfirmed("file.txt", again.token));
    assertEquals("CONFIRM_REQUIRED", consumed.code);
  }

  @Test
  public void expiredTokenIsRejected() throws Exception {
    SafTree.setConfirmTtlForTests(-1);
    SafTree.PendingDelete pending = tree.delete("file.txt");
    try {
      SafTree.Failure expired =
          assertThrows(
              SafTree.Failure.class, () -> tree.deleteConfirmed("file.txt", pending.token));
      assertEquals("CONFIRM_EXPIRED", expired.code);
    } finally {
      SafTree.setConfirmTtlForTests(java.util.concurrent.TimeUnit.SECONDS.toNanos(60));
    }
    assertTrue(provider.deleted.isEmpty());
  }

  @Test
  public void writeInOversizedParentStillSucceeds() throws Exception {
    provider.oversizedListing = true;
    tree.write("new.txt", "ok".getBytes(StandardCharsets.UTF_8), true);
    assertEquals(
        "ok",
        new String(
            Files.readAllBytes(provider.files.get(provider.lastCreatedId).toPath()),
            StandardCharsets.UTF_8));
  }

  @Test
  public void mkdirInOversizedParentStillSucceeds() throws Exception {
    provider.oversizedListing = true;
    tree.mkdir("newdir");
    assertFalse(provider.documents.isEmpty());
  }

  @Test
  public void delayedParentListingStillAcceptsCreatedDocument() throws Exception {
    provider.delayChildrenAfterCreate = true;
    tree.write("new.txt", "lag".getBytes(StandardCharsets.UTF_8), true);
    assertEquals(
        "lag",
        new String(
            Files.readAllBytes(provider.files.get(provider.lastCreatedId).toPath()),
            StandardCharsets.UTF_8));
  }

  @Test
  public void providerFailureDuringOwnershipCheckFailsClosedWithoutDeleting() throws Exception {
    provider.failChildrenAfterCreate = true;
    SafTree.Failure failure =
        assertThrows(SafTree.Failure.class, () -> tree.write("new.txt", new byte[] {1}, true));
    // 归属校验查询失败时失败关闭；返回 URI 可能是既有文档，不能删除。
    assertEquals("BAD_CHILD_URI", failure.code);
    assertTrue("失败路径不能删除不确定归属的文档", provider.deleted.isEmpty());
  }

  @Test
  public void tokenCannotBeConsumedConcurrently() throws Exception {
    SafTree.PendingDelete pending = tree.delete("file.txt");
    java.util.concurrent.CountDownLatch start = new java.util.concurrent.CountDownLatch(1);
    java.util.concurrent.atomic.AtomicInteger ok = new java.util.concurrent.atomic.AtomicInteger();
    java.util.concurrent.atomic.AtomicInteger rejected =
        new java.util.concurrent.atomic.AtomicInteger();
    Runnable consume =
        () -> {
          try {
            start.await();
            tree.deleteConfirmed("file.txt", pending.token);
            ok.incrementAndGet();
          } catch (Exception error) {
            rejected.incrementAndGet();
          }
        };
    Thread first = new Thread(consume);
    Thread second = new Thread(consume);
    first.start();
    second.start();
    start.countDown();
    first.join(5000);
    second.join(5000);
    assertEquals("同一 token 只能一个线程成功", 1, ok.get());
    assertEquals("另一个线程必须被拒", 1, rejected.get());
  }

  @Test
  public void tokenCannotBeReusedAcrossTrees() throws Exception {
    SafTree.PendingDelete pending = tree.delete("file.txt");
    SafTree other =
        new SafTree(
            org.robolectric.RuntimeEnvironment.getApplication().getContentResolver(),
            android.net.Uri.parse("content://" + AUTH + "/tree/OTHER"));
    SafTree.Failure failure =
        assertThrows(SafTree.Failure.class, () -> other.deleteConfirmed("file.txt", pending.token));
    assertEquals("CONFIRM_MISMATCH", failure.code);
  }

  @Test
  public void targetChangeBetweenProbeAndConfirmIsRejected() throws Exception {
    SafTree.PendingDelete pending = tree.delete("file.txt");
    File target = provider.files.get("file.txt");
    Files.write(target.toPath(), "changed".getBytes(StandardCharsets.UTF_8));
    SafTree.Failure failure =
        assertThrows(SafTree.Failure.class, () -> tree.deleteConfirmed("file.txt", pending.token));
    assertEquals("CONFIRM_MISMATCH", failure.code);
  }

  @Test
  public void pendingTokensAreEvictedByAgeNotClearedWholesale() throws Exception {
    java.util.List<SafTree.PendingDelete> issued = new java.util.ArrayList<>();
    for (int i = 0; i < 130; i++) issued.add(tree.delete("file.txt"));
    assertTrue("pending 数量必须有界", SafTree.pendingCountForTests() <= 128);
    SafTree.Failure oldest =
        assertThrows(
            SafTree.Failure.class, () -> tree.deleteConfirmed("file.txt", issued.get(0).token));
    assertEquals("CONFIRM_REQUIRED", oldest.code);
    tree.deleteConfirmed("file.txt", issued.get(issued.size() - 1).token);
    assertEquals(1, provider.deleted.size());
  }

  @Test
  public void recursiveDeleteRejectsOversizedTreeBeforeAnyDeletion() throws Exception {
    SafTree.PendingDelete pending = tree.delete("subdir");
    provider.oversizedListing = true;
    SafTree.Failure failure =
        assertThrows(SafTree.Failure.class, () -> tree.deleteConfirmed("subdir", pending.token));
    assertEquals("SCAN_LIMIT", failure.code);
    assertTrue("预检失败不能先删任何子项", provider.deleted.isEmpty());
  }

  @Test
  public void createdChildWithWrongParentOrNameIsRejectedWithoutDeletingExisting()
      throws Exception {
    provider.forcedChildId = "file.txt";
    SafTree.Failure failure =
        assertThrows(
            SafTree.Failure.class, () -> tree.write("subdir/new.txt", new byte[] {1}, true));
    assertEquals("BAD_CHILD_URI", failure.code);
    assertTrue("不能删除既有文档", provider.deleted.isEmpty());
    assertEquals(
        "hello",
        new String(
            Files.readAllBytes(provider.files.get("file.txt").toPath()), StandardCharsets.UTF_8));
  }

  @Test
  public void createdChildRootIsRejectedWithoutDeletingAnything() throws Exception {
    provider.forcedChildId = "ROOT";
    SafTree.Failure failure =
        assertThrows(
            SafTree.Failure.class, () -> tree.write("subdir/new.txt", new byte[] {1}, true));
    assertEquals("BAD_CHILD_URI", failure.code);
    assertTrue(provider.deleted.isEmpty());
  }

  @Test
  public void createdChildWithSameParentButWrongNameIsRejected() throws Exception {
    provider.forcedChildId = "subdir/child";
    SafTree.Failure failure =
        assertThrows(
            SafTree.Failure.class, () -> tree.write("subdir/new.txt", new byte[] {1}, true));
    assertEquals("BAD_CHILD_URI", failure.code);
    assertTrue("同父目录的既有 sibling 也不能被当成新建文档", provider.deleted.isEmpty());
  }

  @Test
  public void createDocumentOutsideTreeIsRejectedWithoutDeletingAnything() throws Exception {
    provider.outsideCreate = true;
    SafTree.Failure failure =
        assertThrows(SafTree.Failure.class, () -> tree.write("new.txt", new byte[] {1}, true));
    assertEquals("BAD_CHILD_URI", failure.code);
    assertTrue("归属校验失败不能删除任何文档", provider.deleted.isEmpty());
  }

  @Test
  public void fallbackIsChildDocumentAcceptsCreatedChildWhenParentColumnIsHidden()
      throws Exception {
    provider.hideParentColumn = true;
    tree.write("new.txt", "ok".getBytes(StandardCharsets.UTF_8), true);
    assertEquals(
        "ok",
        new String(
            Files.readAllBytes(provider.files.get(provider.lastCreatedId).toPath()),
            StandardCharsets.UTF_8));
  }

  @Test
  public void unsupportedParentAndIsChildFailsClosed() throws Exception {
    provider.hideParentColumn = true;
    provider.isChildSupported = false;
    SafTree.Failure failure =
        assertThrows(SafTree.Failure.class, () -> tree.write("new.txt", new byte[] {1}, true));
    assertEquals("BAD_CHILD_URI", failure.code);
    assertTrue("失败关闭时不能删除返回的文档", provider.deleted.isEmpty());
  }

  @Test
  public void oversizedDirectoryReportsScanLimitInsteadOfNotFound() {
    provider.oversizedListing = true;
    SafTree.Failure failure = assertThrows(SafTree.Failure.class, () -> tree.stat("missing.txt"));
    assertEquals("SCAN_LIMIT", failure.code);
  }

  @Test
  public void cancellationIsMappedToTimeoutNotProviderError() {
    provider.failure = "cancel";
    SafTree.Failure failure = assertThrows(SafTree.Failure.class, () -> tree.list("", 10));
    assertEquals("TIMEOUT", failure.code);
  }

  @Test
  public void securityAndProviderErrorsKeepTheirCodes() {
    provider.failure = "security";
    assertEquals(
        "PERMISSION_DENIED", assertThrows(SafTree.Failure.class, () -> tree.list("", 10)).code);
    provider.failure = "runtime";
    assertEquals(
        "PROVIDER_ERROR", assertThrows(SafTree.Failure.class, () -> tree.list("", 10)).code);
  }

  @Test
  public void slowWriteReturnsTimeoutWithinDeadline() {
    provider.slowOpen = true;
    long started = System.nanoTime();
    SafTree.Failure failure =
        assertThrows(SafTree.Failure.class, () -> tree.write("file.txt", new byte[] {1, 2}, false));
    long elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);
    assertEquals("TIMEOUT", failure.code);
    assertTrue("必须在本地截止时间附近返回，实际 " + elapsedMs + "ms", elapsedMs < 5000);
  }

  @Test
  public void writeAndReadSucceedThroughProviderFile() throws Exception {
    long written = tree.write("new.txt", "abc".getBytes(StandardCharsets.UTF_8), true);
    assertEquals(3, written);
    assertArrayEquals("abc".getBytes(StandardCharsets.UTF_8), tree.read("new.txt", 0, 16));
    assertEquals(
        "abc",
        new String(
            Files.readAllBytes(provider.files.get(provider.lastCreatedId).toPath()),
            StandardCharsets.UTF_8));
  }

  @Test
  public void existingFileOverwriteAndReadBackSucceed() throws Exception {
    assertEquals(5, tree.read("file.txt", 0, 16).length);
    tree.write("file.txt", "hi".getBytes(StandardCharsets.UTF_8), false);
    assertArrayEquals("hi".getBytes(StandardCharsets.UTF_8), tree.read("file.txt", 0, 16));
  }

  @Test
  public void slowCreateDocumentIsDetectedAfterItReturns() {
    provider.outsideCreate = false;
    provider.slowCreate = true;
    long started = System.nanoTime();
    SafTree.Failure failure = assertThrows(SafTree.Failure.class, () -> tree.mkdir("newdir"));
    long elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);
    assertEquals("TIMEOUT", failure.code);
    assertTrue("返回后必须复核截止，实际 " + elapsedMs + "ms", elapsedMs < 5000);
    assertTrue("超时后归属未确认的返回 URI 不能删除", provider.deleted.isEmpty());
  }
}
