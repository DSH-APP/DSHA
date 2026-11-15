package com.deepseekharness.app.ui;

import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import android.system.ErrnoException;
import android.system.Os;
import android.system.OsConstants;
import com.deepseekharness.app.backup.BackupControl;
import com.deepseekharness.app.backup.DocumentStreams;
import com.deepseekharness.app.util.WebTransferPolicy;
import com.deepseekharness.app.util.WebUploadDiskBudget;
import com.deepseekharness.app.util.WebUploadSourceRetry;
import com.deepseekharness.app.util.WebUploadStream;
import java.io.*;
import java.util.*;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/** 复制用户明确选择的内容，不接受任意本地路径；两种浏览内核共用上限。 */
public final class WebUploads {
  private WebUploads() {}

  private static final ScheduledExecutorService CLEANER =
      Executors.newSingleThreadScheduledExecutor(
          task -> {
            Thread thread = new Thread(task, "web-upload-cache-cleaner");
            thread.setDaemon(true);
            return thread;
          });
  private static final Set<String> ACTIVE_SESSION_ROOTS =
      Collections.synchronizedSet(new HashSet<>());
  // 同进程的所有缓存写入共用门禁；源 Provider 的读取不占这把锁。
  private static final WebUploadDiskBudget UPLOAD_VOLUME_WRITES = new WebUploadDiskBudget();

  /**
   * One bounded cache owned by a retained page. The directory is removed only after that page
   * closes.
   */
  public static final class Session implements AutoCloseable {
    private final File cacheRoot;
    private final File root;
    private final com.deepseekharness.app.util.WebUploadSessionBudget budget =
        new com.deepseekharness.app.util.WebUploadSessionBudget();
    private final Set<BackupControl> activeCopies =
        Collections.newSetFromMap(new IdentityHashMap<>());
    private boolean closed;
    private boolean cleanupQueued;

    public Session(File cacheDirectory) {
      cacheRoot = cacheDirectory;
      root = new File(new File(cacheRoot, "web-uploads"), UUID.randomUUID().toString());
      ACTIVE_SESSION_ROOTS.add(root.getAbsolutePath());
      CLEANER.execute(() -> cleanOrphanedSessions(cacheRoot));
    }

    public Batch copy(Context context, List<Uri> uris) throws IOException {
      return copy(context, uris, new BackupControl(null));
    }

    public Batch copy(Context context, List<Uri> uris, BackupControl control) throws IOException {
      if (uris == null || uris.isEmpty())
        throw new IOException(com.deepseekharness.app.util.UiText.text("未选择文件"));
      if (uris.size() > WebTransferPolicy.UPLOAD_COUNT)
        throw new IOException(com.deepseekharness.app.util.UiText.text("一次最多上传 20 个文件"));
      com.deepseekharness.app.util.WebUploadSessionBudget.Reservation reservation = null;
      File batchDirectory = null;
      boolean ready = false;
      try (com.deepseekharness.app.core.RuntimeTasks ignored =
          com.deepseekharness.app.core.RuntimeTasks.begin()) {
        register(control);
        checkCancelled(control);
        reservation = budget.beginBatch(uris.size());
        ensureOpen();
        ensureOwnedDirectory(cacheRoot, null);
        ensureOwnedDirectory(root.getParentFile(), cacheRoot);
        ensureOwnedDirectory(root, root.getParentFile());
        batchDirectory = new File(root, UUID.randomUUID().toString());
        ensureOwnedDirectory(batchDirectory, root);
        writeWithSpace(context, batchDirectory, 0, () -> {});
        final File spaceDirectory = batchDirectory;
        final com.deepseekharness.app.util.WebUploadSessionBudget.Reservation copyReservation =
            reservation;
        ArrayList<File> files = new ArrayList<>();
        long total = 0;
        for (Uri uri : uris) {
          checkCancelled(control);
          if (uri == null || !"content".equals(uri.getScheme()))
            throw new IOException(com.deepseekharness.app.util.UiText.text("不支持的文件来源，请通过系统文件应用选择"));
          String name = "upload.bin";
          try (android.database.Cursor cursor =
              DocumentStreams.optionalQuery(
                  context.getContentResolver(),
                  uri,
                  new String[] {android.provider.OpenableColumns.DISPLAY_NAME},
                  control)) {
            if (cursor != null && cursor.moveToFirst())
              name = WebTransferPolicy.fileName(cursor.getString(0));
          }
          File directory = new File(batchDirectory, UUID.randomUUID().toString());
          ensureOwnedDirectory(directory, batchDirectory);
          File file = new File(directory, name);
          files.add(file);
          // 源先进入资源作用域；输出打开失败也会关闭源。AutoCloseOutputStream 明确接管 PFD。
          try (InputStream source = openSource(context, uri, control);
              OutputStream out =
                  new ParcelFileDescriptor.AutoCloseOutputStream(openNoFollow(file))) {
            total =
                WebUploadStream.copy(
                    source,
                    total,
                    WebTransferPolicy.UPLOAD_LIMIT,
                    WebTransferPolicy.UPLOAD_FILE_LIMIT,
                    (buffer, count) -> {
                      writeWithSpace(
                          context,
                          spaceDirectory,
                          count,
                          () -> {
                            checkCancelled(control);
                            budget.addBytes(copyReservation, count);
                            out.write(buffer, 0, count);
                          });
                    },
                    () -> checkCancelled(control));
          }
        }
        checkCancelled(control);
        budget.finishCopy(reservation);
        ready = true;
        return new Batch(this, reservation, batchDirectory, files);
      } catch (Exception error) {
        if (error instanceof WebUploadStream.LimitExceededException) {
          boolean fileLimit = ((WebUploadStream.LimitExceededException) error).fileLimit();
          throw new IOException(
              com.deepseekharness.app.util.UiText.format(
                  fileLimit ? "单个文件超过 %s" : "本次上传超过 %s",
                  fileSize(
                      context,
                      fileLimit
                          ? WebTransferPolicy.UPLOAD_FILE_LIMIT
                          : WebTransferPolicy.UPLOAD_LIMIT)),
              error);
        }
        if (error instanceof IOException) throw (IOException) error;
        if (error
            instanceof com.deepseekharness.app.util.WebUploadSessionBudget.LimitExceededException)
          throw new IOException(
              sessionLimitMessage(
                  ((com.deepseekharness.app.util.WebUploadSessionBudget.LimitExceededException)
                          error)
                      .limit(),
                  context),
              error);
        if (error
            instanceof com.deepseekharness.app.util.WebUploadSessionBudget.SessionClosedException)
          throw new InterruptedIOException("UPLOAD_SESSION_CLOSED");
        throw new IOException(com.deepseekharness.app.util.UiText.text("无法读取文件，请检查授权后重新选择"), error);
      } finally {
        unregister(control);
        if (!ready && reservation != null) {
          boolean removed = batchDirectory == null || deleteOwnedTree(root, batchDirectory);
          if (removed) budget.rollback(reservation);
          else retainReservation(reservation);
        }
        cleanupIfClosed();
      }
    }

    public boolean isClosed() {
      return budget.isClosed();
    }

    private String sessionLimitMessage(String limit, Context context) {
      if ("FILES".equals(limit))
        return com.deepseekharness.app.util.UiText.format(
            "浏览器会话上传的文件数已达 %s 个，请重新打开对话后再试",
            com.deepseekharness.app.util.WebUploadSessionBudget.MAX_SESSION_FILES);
      return com.deepseekharness.app.util.UiText.format(
          "浏览器会话上传缓存已达 %s，请重新打开对话后再试",
          fileSize(context, com.deepseekharness.app.util.WebUploadSessionBudget.MAX_SESSION_BYTES));
    }

    private static String fileSize(Context context, long bytes) {
      return android.text.format.Formatter.formatFileSize(context, bytes);
    }

    private static void writeWithSpace(
        Context context, File directory, long count, WebUploadDiskBudget.Write write)
        throws IOException {
      try {
        UPLOAD_VOLUME_WRITES.write(count, directory::getUsableSpace, write);
      } catch (IllegalArgumentException insufficient) {
        throw new IOException(
            com.deepseekharness.app.util.UiText.format(
                "存储空间不足，上传缓存需保留 %s 可用空间", fileSize(context, WebTransferPolicy.UPLOAD_RESERVE)));
      }
    }

    private InputStream openSource(Context context, Uri uri, BackupControl control)
        throws IOException {
      try {
        return WebUploadSourceRetry.open(
            () -> DocumentStreams.input(context.getContentResolver(), uri, control),
            () -> checkCancelled(control),
            millis -> awaitSourceRetry(control, millis));
      } catch (FileNotFoundException missing) {
        // Provider 异常可能含 URI、文件名或路径；诊断只保存固定事件。
        android.util.Log.w("DSHA", "WEB_UPLOAD_SOURCE_UNAVAILABLE attempts=2");
        throw new IOException(com.deepseekharness.app.util.UiText.text("文件暂不可读，请重新选择"));
      }
    }

    private void awaitSourceRetry(BackupControl control, long millis) throws IOException {
      long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(millis);
      while (System.nanoTime() < deadline) {
        checkCancelled(control);
        try {
          Thread.sleep(
              Math.min(
                  25L, Math.max(1L, TimeUnit.NANOSECONDS.toMillis(deadline - System.nanoTime()))));
        } catch (InterruptedException interrupted) {
          Thread.currentThread().interrupt();
          throw new InterruptedIOException("UPLOAD_CANCELLED");
        }
      }
      checkCancelled(control);
    }

    private void ensureOwnedDirectory(File directory, File parent) throws IOException {
      if (parent != null) ensureNoSymlinkDirectory(parent);
      ensureNoSymlinkDirectory(cacheRoot);
      if (!directory.exists() && !directory.mkdir() && !isDirectoryNoFollow(directory))
        throw new IOException(com.deepseekharness.app.util.UiText.text("无法创建上传缓存"));
      if (!isDirectoryNoFollow(directory)
          || !canonicalWithin(parent == null ? cacheRoot : parent, directory))
        throw new IOException("UPLOAD_CACHE_PATH_UNSAFE");
    }

    private void ensureNoSymlinkDirectory(File directory) throws IOException {
      if (directory == null || !isDirectoryNoFollow(directory))
        throw new IOException("UPLOAD_CACHE_DIRECTORY_UNSAFE");
    }

    private ParcelFileDescriptor openNoFollow(File file) throws IOException {
      java.io.FileDescriptor descriptor;
      try {
        descriptor =
            Os.open(
                file.getAbsolutePath(),
                OsConstants.O_WRONLY
                    | OsConstants.O_CREAT
                    | OsConstants.O_EXCL
                    | OsConstants.O_NOFOLLOW,
                0600);
      } catch (ErrnoException error) {
        throw new IOException("UPLOAD_CACHE_FILE_UNSAFE", error);
      }
      ParcelFileDescriptor owned;
      try {
        owned = ParcelFileDescriptor.dup(descriptor);
      } catch (IOException | RuntimeException error) {
        try {
          Os.close(descriptor);
        } catch (ErrnoException ignored) {
        }
        throw error;
      }
      try {
        Os.close(descriptor);
      } catch (ErrnoException error) {
        try {
          owned.close();
        } catch (IOException ignored) {
        }
        throw new IOException("UPLOAD_CACHE_FILE_DESCRIPTOR_CLOSE", error);
      }
      return owned;
    }

    private synchronized void ensureOpen() throws InterruptedIOException {
      if (closed || budget.isClosed()) throw new InterruptedIOException("UPLOAD_SESSION_CLOSED");
    }

    private void checkCancelled(BackupControl control) throws InterruptedIOException {
      control.check();
      ensureOpen();
    }

    private void register(BackupControl control) throws IOException {
      synchronized (this) {
        if (closed || budget.isClosed()) {
          control.cancel();
          throw new InterruptedIOException("UPLOAD_SESSION_CLOSED");
        }
        activeCopies.add(control);
      }
    }

    private synchronized void unregister(BackupControl control) {
      activeCopies.remove(control);
    }

    private boolean commit(
        com.deepseekharness.app.util.WebUploadSessionBudget.Reservation reservation) {
      return budget.commit(reservation);
    }

    private void rollback(
        com.deepseekharness.app.util.WebUploadSessionBudget.Reservation reservation,
        File directory) {
      if (deleteOwnedTree(root, directory)) budget.rollback(reservation);
      else retainReservation(reservation);
      cleanupIfClosed();
    }

    private void retainReservation(
        com.deepseekharness.app.util.WebUploadSessionBudget.Reservation reservation) {
      budget.retainFailedCopy(reservation);
      android.util.Log.w("DSHA", "WEB_UPLOAD_BATCH_CLEANUP_RETAINED");
    }

    private void cleanupIfClosed() {
      synchronized (this) {
        if (!closed || !budget.canDeleteOwnedFiles() || cleanupQueued) return;
        cleanupQueued = true;
      }
      CLEANER.execute(() -> cleanupAttempt(0));
    }

    private void cleanupAttempt(int attempt) {
      boolean removed = deleteOwnedTree(cacheRoot, root);
      boolean retry = false;
      synchronized (this) {
        cleanupQueued = false;
        if (removed) ACTIVE_SESSION_ROOTS.remove(root.getAbsolutePath());
        else if (attempt < 3) {
          cleanupQueued = true;
          retry = true;
        }
      }
      if (retry)
        CLEANER.schedule(
            () -> cleanupAttempt(attempt + 1), 250L * (attempt + 1), TimeUnit.MILLISECONDS);
      else if (!removed)
        android.util.Log.w("DSHA", "WEB_UPLOAD_CACHE_CLEANUP_RETAINED attempts=" + (attempt + 1));
    }

    @Override
    public void close() {
      List<BackupControl> copies;
      synchronized (this) {
        if (!closed) {
          closed = true;
          budget.close();
        }
        copies = new ArrayList<>(activeCopies);
      }
      // DocumentStreams 的回调异步取消 Provider 和关闭流，界面线程不等待远端关闭。
      for (BackupControl control : copies) control.cancel();
      cleanupIfClosed();
    }
  }

  /** A copied batch is temporary until its original live page accepts the result. */
  public static final class Batch implements AutoCloseable {
    private final Session session;
    private final com.deepseekharness.app.util.WebUploadSessionBudget.Reservation reservation;
    private final File directory;
    private final List<File> files;
    private boolean done;

    private Batch(
        Session session,
        com.deepseekharness.app.util.WebUploadSessionBudget.Reservation reservation,
        File directory,
        List<File> files) {
      this.session = session;
      this.reservation = reservation;
      this.directory = directory;
      this.files = Collections.unmodifiableList(new ArrayList<>(files));
    }

    public List<File> files() {
      return files;
    }

    public synchronized boolean commit() {
      if (done) return false;
      if (!session.commit(reservation)) return false;
      done = true;
      return true;
    }

    @Override
    public synchronized void close() {
      if (done) return;
      done = true;
      session.rollback(reservation, directory);
    }
  }

  /** 系统多选只返回 ClipData；保留顺序、去重，并回退到单选结果。 */
  public static Uri[] parseChooserResult(int resultCode, Intent data) {
    if (resultCode != android.app.Activity.RESULT_OK || data == null) return null;
    LinkedHashSet<Uri> selected = new LinkedHashSet<>();
    android.content.ClipData clip = data.getClipData();
    if (clip != null)
      for (int i = 0; i < clip.getItemCount(); i++) {
        Uri uri = clip.getItemAt(i).getUri();
        if (uri != null) selected.add(uri);
      }
    if (selected.isEmpty() && data.getData() != null) selected.add(data.getData());
    // 只解析系统内容授权，不将 file:// 等宿主路径交给网页。
    for (Uri uri : selected) if (!"content".equals(uri.getScheme())) return null;
    return selected.isEmpty() ? null : selected.toArray(new Uri[0]);
  }

  public static Intent fallback(Intent original) {
    String alternative =
        Intent.ACTION_GET_CONTENT.equals(original.getAction())
            ? Intent.ACTION_OPEN_DOCUMENT
            : Intent.ACTION_GET_CONTENT;
    return new Intent(original)
        .setAction(alternative)
        .addCategory(Intent.CATEGORY_OPENABLE)
        .setComponent(null)
        .setPackage(null)
        .setFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
  }

  private static boolean deleteOwnedTree(File sessionRoot, File path) {
    if (path == null) return true;
    if (sessionRoot == null || !lexicallyWithin(sessionRoot, path)) return false;
    try {
      File cacheRoot =
          sessionRoot.getParentFile() == null ? null : sessionRoot.getParentFile().getParentFile();
      if (cacheRoot == null || !isDirectoryNoFollow(cacheRoot)) return false;
      File sessions = sessionRoot.getParentFile();
      if (!existsNoFollow(sessions)) return true;
      if (!isDirectoryNoFollow(sessions) || !canonicalWithin(cacheRoot, sessions)) return false;
      android.system.StructStat rootStat = statNoFollow(sessionRoot);
      if (rootStat == null) return true;
      if (isSymlink(rootStat)) return path.equals(sessionRoot) && sessionRoot.delete();
      if (!isDirectoryNoFollow(sessionRoot) || !canonicalWithin(sessions, sessionRoot))
        return false;
      return deleteEntry(sessionRoot, path);
    } catch (IOException | SecurityException error) {
      return false;
    }
  }

  /**
   * A process death can skip ViewModel cleanup; only UUID roots from this private cache are
   * eligible.
   */
  private static void cleanOrphanedSessions(File cacheRoot) {
    File sessions = new File(cacheRoot, "web-uploads");
    try {
      if (!isDirectoryNoFollow(cacheRoot)
          || !isDirectoryNoFollow(sessions)
          || !canonicalWithin(cacheRoot, sessions)) return;
      File[] entries = sessions.listFiles();
      if (entries == null) return;
      for (File entry : entries) {
        if (!com.deepseekharness.app.util.Ids.uuid(entry.getName())) continue;
        if (ACTIVE_SESSION_ROOTS.contains(entry.getAbsolutePath())) continue;
        if (!deleteOwnedTree(entry, entry))
          android.util.Log.w("DSHA", "WEB_UPLOAD_ORPHAN_CLEANUP_RETAINED");
      }
    } catch (IOException | SecurityException error) {
      android.util.Log.w("DSHA", "WEB_UPLOAD_ORPHAN_SCAN_FAILED");
    }
  }

  private static boolean deleteEntry(File root, File path) throws IOException {
    if (!lexicallyWithin(root, path)) return false;
    android.system.StructStat state = statNoFollow(path);
    if (state == null) return true;
    if (isSymlink(state)) return path.delete() || statNoFollow(path) == null;
    if (!isDirectoryNoFollow(root) || !canonicalWithin(root, path)) return false;
    if (isDirectory(state)) {
      File[] children = path.listFiles();
      if (children == null) return false;
      for (File child : children) if (!deleteEntry(root, child)) return false;
    }
    return path.delete() || statNoFollow(path) == null;
  }

  private static boolean existsNoFollow(File path) throws IOException {
    return statNoFollow(path) != null;
  }

  private static boolean isDirectoryNoFollow(File path) throws IOException {
    android.system.StructStat state = statNoFollow(path);
    return state != null && isDirectory(state);
  }

  private static android.system.StructStat statNoFollow(File path) throws IOException {
    try {
      return Os.lstat(path.getAbsolutePath());
    } catch (ErrnoException error) {
      if (error.errno == OsConstants.ENOENT || error.errno == OsConstants.ENOTDIR) return null;
      throw new IOException("UPLOAD_CACHE_STAT", error);
    }
  }

  private static boolean isDirectory(android.system.StructStat state) {
    return (state.st_mode & OsConstants.S_IFMT) == OsConstants.S_IFDIR;
  }

  private static boolean isSymlink(android.system.StructStat state) {
    return (state.st_mode & OsConstants.S_IFMT) == OsConstants.S_IFLNK;
  }

  private static boolean canonicalWithin(File base, File child) throws IOException {
    String parent = base.getCanonicalPath(), target = child.getCanonicalPath();
    return target.equals(parent) || target.startsWith(parent + File.separator);
  }

  private static boolean lexicallyWithin(File base, File child) {
    String parent = base.getAbsoluteFile().getPath(), target = child.getAbsoluteFile().getPath();
    return target.equals(parent) || target.startsWith(parent + File.separator);
  }
}
