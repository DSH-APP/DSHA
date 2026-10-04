package com.deepseekharness.app.ui;

import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import android.system.ErrnoException;
import android.system.Os;
import android.system.OsConstants;
import com.deepseekharness.app.util.WebTransferPolicy;
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

  /** One bounded cache owned by a retained page. The directory is removed only after that page closes. */
  public static final class Session implements AutoCloseable {
    private final File cacheRoot;
    private final File root;
    private final com.deepseekharness.app.util.WebUploadSessionBudget budget =
        new com.deepseekharness.app.util.WebUploadSessionBudget();
    private final Set<InputStream> activeInputs =
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
      if (uris == null || uris.isEmpty())
        throw new IOException(com.deepseekharness.app.util.UiText.text("未选择文件"));
      if (uris.size() > WebTransferPolicy.UPLOAD_COUNT)
        throw new IOException(com.deepseekharness.app.util.UiText.text("一次最多上传 20 个文件"));
      com.deepseekharness.app.util.WebUploadSessionBudget.Reservation reservation = null;
      File batchDirectory = null;
      boolean ready = false;
      try (com.deepseekharness.app.core.RuntimeTasks ignored =
          com.deepseekharness.app.core.RuntimeTasks.begin()) {
        reservation = budget.beginBatch(uris.size());
        ensureOpen();
        ensureOwnedDirectory(cacheRoot, null);
        ensureOwnedDirectory(root.getParentFile(), cacheRoot);
        ensureOwnedDirectory(root, root.getParentFile());
        batchDirectory = new File(root, UUID.randomUUID().toString());
        ensureOwnedDirectory(batchDirectory, root);
        ArrayList<File> files = new ArrayList<>();
        long total = 0;
        for (Uri uri : uris) {
          ensureNotInterrupted();
          if (uri == null || !"content".equals(uri.getScheme()))
            throw new IOException(com.deepseekharness.app.util.UiText.text("不支持的文件来源，请通过系统文件应用选择"));
          String name = "upload.bin";
          try (android.database.Cursor cursor =
              context
                  .getContentResolver()
                  .query(
                      uri,
                      new String[] {android.provider.OpenableColumns.DISPLAY_NAME},
                      null,
                      null,
                      null)) {
            if (cursor != null && cursor.moveToFirst())
              name = WebTransferPolicy.fileName(cursor.getString(0));
          }
          File directory = new File(batchDirectory, UUID.randomUUID().toString());
          ensureOwnedDirectory(directory, batchDirectory);
          File file = new File(directory, name);
          files.add(file);
          InputStream opened = context.getContentResolver().openInputStream(uri);
          if (opened == null)
            throw new IOException(com.deepseekharness.app.util.UiText.text("没有文件读取权限，请重新选择"));
          InputStream in = register(opened);
          try {
            ParcelFileDescriptor descriptor = openNoFollow(file);
            try (InputStream source = in;
                ParcelFileDescriptor owned = descriptor;
                OutputStream out = new FileOutputStream(owned.getFileDescriptor())) {
              byte[] buffer = new byte[65536];
              int n;
              while ((n = source.read(buffer)) != -1) {
                ensureNotInterrupted();
                total += n;
                if (total > WebTransferPolicy.UPLOAD_LIMIT)
                  throw new IOException(com.deepseekharness.app.util.UiText.text("本次上传超过 256 MiB"));
                try {
                  budget.addBytes(reservation, n);
                } catch (
                    com.deepseekharness.app.util.WebUploadSessionBudget.LimitExceededException
                        limit) {
                  throw new IOException(sessionLimitMessage(limit.limit()), limit);
                } catch (
                    com.deepseekharness.app.util.WebUploadSessionBudget.SessionClosedException
                        cancelled) {
                  throw new InterruptedIOException("UPLOAD_SESSION_CLOSED");
                }
                out.write(buffer, 0, n);
              }
            }
          } finally {
            unregister(in);
          }
        }
        budget.finishCopy(reservation);
        ready = true;
        return new Batch(this, reservation, batchDirectory, files);
      } catch (Exception error) {
        if (error instanceof IOException) throw (IOException) error;
        if (error
            instanceof com.deepseekharness.app.util.WebUploadSessionBudget.LimitExceededException)
          throw new IOException(
              sessionLimitMessage(
                  ((com.deepseekharness.app.util.WebUploadSessionBudget.LimitExceededException)
                          error)
                      .limit()),
              error);
        if (error
            instanceof com.deepseekharness.app.util.WebUploadSessionBudget.SessionClosedException)
          throw new InterruptedIOException("UPLOAD_SESSION_CLOSED");
        throw new IOException(com.deepseekharness.app.util.UiText.text("无法读取文件，请检查授权后重新选择"), error);
      } finally {
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

    private String sessionLimitMessage(String limit) {
      return com.deepseekharness.app.util.UiText.text("浏览器会话上传缓存已达 512 MiB 或 40 个文件，请重新打开对话后再试");
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

    private void ensureNotInterrupted() throws InterruptedIOException {
      if (Thread.currentThread().isInterrupted())
        throw new InterruptedIOException("UPLOAD_CANCELLED");
      ensureOpen();
    }

    private InputStream register(InputStream input) throws IOException {
      synchronized (this) {
        if (closed || budget.isClosed()) {
          try {
            input.close();
          } catch (IOException ignored) {
          }
          throw new InterruptedIOException("UPLOAD_SESSION_CLOSED");
        }
        activeInputs.add(input);
      }
      return input;
    }

    private synchronized void unregister(InputStream input) {
      activeInputs.remove(input);
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
      try {
        budget.finishCopy(reservation);
      } catch (IllegalStateException alreadyFinished) {
      }
      if (!budget.commit(reservation)) budget.rollback(reservation);
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
      List<InputStream> streams;
      synchronized (this) {
        if (!closed) {
          closed = true;
          budget.close();
        }
        streams = new ArrayList<>(activeInputs);
      }
      for (InputStream stream : streams)
        try {
          stream.close();
        } catch (IOException ignored) {
        }
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

  /** A process death can skip ViewModel cleanup; only UUID roots from this private cache are eligible. */
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
      android.util.Log.w("DSHA", "WEB_UPLOAD_ORPHAN_SCAN_FAILED", error);
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
