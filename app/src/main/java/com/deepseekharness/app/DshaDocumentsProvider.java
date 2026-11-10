package com.deepseekharness.app;

import android.database.Cursor;
import android.database.MatrixCursor;
import android.database.sqlite.SQLiteException;
import android.os.CancellationSignal;
import android.os.Handler;
import android.os.Looper;
import android.os.ParcelFileDescriptor;
import android.provider.DocumentsContract;
import android.provider.DocumentsProvider;
import android.system.ErrnoException;
import android.system.Os;
import android.system.OsConstants;
import android.webkit.MimeTypeMap;
import com.deepseekharness.app.core.RuntimeTasks;
import com.deepseekharness.app.util.DocumentPaths;
import java.io.File;
import java.io.FileDescriptor;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InterruptedIOException;
import java.nio.file.DirectoryIteratorException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/** 标准 SAF 文件接口；旧文档编号保持有效，读写仅限已授权的 DSHA 私有目录。 */
public class DshaDocumentsProvider extends DocumentsProvider {
  private static final String ROOT_ID = "dsha-root";
  private static final String[] ROOT_COLUMNS = {
    "root_id", "mime_types", "flags", "title", "summary", "document_id", "icon", "query_args"
  };
  private static final String[] DOCUMENT_COLUMNS = {
    "document_id", "mime_type", "_display_name", "last_modified", "flags", "_size"
  };
  private DocumentPaths paths;
  private com.deepseekharness.app.backup.UserDataLayout dataLayout;
  private final com.deepseekharness.app.backup.AndroidBackupFileSystem mutations =
      new com.deepseekharness.app.backup.AndroidBackupFileSystem();
  private String authority;
  private static final ThreadLocal<CancellationSignal> LOCAL_QUERY_SIGNAL = new ThreadLocal<>();

  /** DocumentsProvider's final Bundle query drops the child-query cancellation signal.
   * Same-process callers keep their signal on the synchronous provider call stack instead.
   * External SAF clients still have the framework's child-query cancellation limitation. */
  public static AutoCloseable withQueryCancellation(CancellationSignal signal) {
    CancellationSignal previous = LOCAL_QUERY_SIGNAL.get();
    LOCAL_QUERY_SIGNAL.set(signal);
    return () -> {
      if (previous == null) LOCAL_QUERY_SIGNAL.remove();
      else LOCAL_QUERY_SIGNAL.set(previous);
    };
  }

  private static void checkQueryCancellation() throws InterruptedIOException {
    CancellationSignal signal = LOCAL_QUERY_SIGNAL.get();
    if (signal != null) signal.throwIfCanceled();
    if (Thread.currentThread().isInterrupted()) throw new InterruptedIOException("CANCELLED");
  }

  // 测试子类只替换目录，生产入口不接受任意宿主路径。
  protected File documentBase() {
    return getContext().getFilesDir();
  }

  protected com.deepseekharness.app.backup.BackupFileSystem documentFileSystem() {
    return new com.deepseekharness.app.backup.AndroidBackupFileSystem();
  }

  @Override
  public void attachInfo(android.content.Context context, android.content.pm.ProviderInfo info) {
    authority = info.authority;
    super.attachInfo(context, info);
  }

  @Override
  public boolean onCreate() {
    try {
      dataLayout =
          new com.deepseekharness.app.backup.UserDataLayout(
              documentFileSystem(), documentBase().getCanonicalFile());
      paths = new DocumentPaths(documentBase(), this::link, dataLayout.documents());
      return true;
    } catch (IOException error) {
      android.util.Log.e("DshaDocs", com.deepseekharness.app.util.UiText.text("文件接口初始化失败"), error);
      return false;
    }
  }

  @Override
  public Cursor querySearchDocuments(String root, String[] projection, android.os.Bundle args)
      throws FileNotFoundException {
    // 新版系统只转交 Bundle；空参数安全返回空结果，不在框架默认实现里崩溃。
    String query = args == null ? "" : args.getString(DocumentsContract.QUERY_ARG_DISPLAY_NAME, "");
    MatrixCursor result = (MatrixCursor) querySearchDocuments(root, query, projection);
    android.os.Bundle extras = new android.os.Bundle();
    extras.putStringArray(
        android.content.ContentResolver.EXTRA_HONORED_ARGS,
        args != null && args.containsKey(DocumentsContract.QUERY_ARG_DISPLAY_NAME)
            ? new String[] {DocumentsContract.QUERY_ARG_DISPLAY_NAME}
            : new String[0]);
    result.setExtras(extras);
    return result;
  }

  protected String link(File file) throws IOException {
    try {
      return Os.readlink(file.getPath());
    } catch (ErrnoException error) {
      if (error.errno == OsConstants.EINVAL
          || error.errno == OsConstants.ENOENT
          || error.errno == OsConstants.ENOTDIR) return null;
      throw new IOException(com.deepseekharness.app.util.UiText.text("无法检查文件链接"), error);
    }
  }

  private static FileNotFoundException failure(Exception error) {
    String message = error.getMessage();
    if ("DOCUMENT_LIST_BUSY".equals(message))
      message =
          com.deepseekharness.app.util.UiText.choose(
              "打开的目录过多，请关闭其他文件窗口后重试。",
              "Too many folders are open. Close other file windows and retry.");
    else if ("DOCUMENT_LIST_STORAGE".equals(message) || error instanceof SQLiteException)
      message =
          com.deepseekharness.app.util.UiText.choose(
              "无法暂存目录列表，请检查手机可用存储空间后重试。",
              "The folder listing could not be cached. Check available device storage and retry.");
    FileNotFoundException failure = new FileNotFoundException(message);
    failure.initCause(error);
    return failure;
  }

  private RuntimeTasks lease(boolean detached) throws FileNotFoundException {
    try {
      return detached ? RuntimeTasks.beginDetached() : RuntimeTasks.begin();
    } catch (IllegalStateException error) {
      throw failure(
          new IOException(com.deepseekharness.app.util.UiText.text("环境正在维护，请稍后重试"), error));
    }
  }

  private File resolve(String id, boolean followLast) throws IOException {
    if (paths == null) throw new IOException(com.deepseekharness.app.util.UiText.text("文件接口未初始化"));
    File resolved = paths.resolve(id, followLast);
    if (dataLayout.privateDocument(resolved))
      throw new IOException(
          com.deepseekharness.app.util.UiText.choose(
              "此目录为应用内部数据保护记录", "This directory contains private data protection records"));
    return resolved;
  }

  private File existing(String id) throws IOException {
    File file = resolve(id, true);
    if (!file.exists())
      throw new IOException(com.deepseekharness.app.util.UiText.format("文件不存在或软链接目标已失效：%s", id));
    return file;
  }

  private MatrixCursor documents(String[] projection, String id) {
    MatrixCursor result = new MatrixCursor(projection == null ? DOCUMENT_COLUMNS : projection);
    result.setNotificationUri(
        getContext().getContentResolver(), DocumentsContract.buildDocumentUri(authority, id));
    return result;
  }

  @Override
  public Cursor queryRoots(String[] projection) {
    MatrixCursor result = new MatrixCursor(projection == null ? ROOT_COLUMNS : projection);
    MatrixCursor.RowBuilder row = result.newRow();
    for (String column : result.getColumnNames()) {
      Object value;
      switch (column) {
        case "root_id":
          value = ROOT_ID;
          break;
        case "document_id":
          value = "root";
          break;
        case "title":
          value = "DSHA";
          break;
        case "summary":
          value = com.deepseekharness.app.util.UiText.text("容器文件与个人数据");
          break;
        case "mime_types":
          value = "*/*";
          break;
        case "query_args":
          value = DocumentsContract.QUERY_ARG_DISPLAY_NAME;
          break;
        case "flags":
          value =
              DocumentsContract.Root.FLAG_SUPPORTS_CREATE
                  | DocumentsContract.Root.FLAG_SUPPORTS_IS_CHILD
                  | DocumentsContract.Root.FLAG_SUPPORTS_SEARCH;
          break;
        case "icon":
          value = R.mipmap.ic_launcher;
          break;
        default:
          value = null;
      }
      row.add(value);
    }
    return result;
  }

  @Override
  public Cursor queryDocument(String id, String[] projection) throws FileNotFoundException {
    try (RuntimeTasks ignored = lease(false)) {
      MatrixCursor result = documents(projection, id);
      include(result, id);
      return result;
    } catch (IOException error) {
      throw failure(error);
    }
  }

  @Override
  public Cursor queryChildDocuments(String id, String[] projection, String sortOrder)
      throws FileNotFoundException {
    try (RuntimeTasks ignored = lease(false)) {
      checkQueryCancellation();
      File dir = existing(id);
      if (!dir.isDirectory())
        throw new IOException(com.deepseekharness.app.util.UiText.text("所选文档不是目录"));
      try (DocumentRowsSnapshot snapshot = new DocumentRowsSnapshot(getContext().getCacheDir());
          DirectoryStream<Path> entries = Files.newDirectoryStream(dir.toPath())) {
        for (Path entry : entries) {
          checkQueryCancellation();
          try {
            snapshot.add(values(paths.child(id, entry.getFileName().toString()), DOCUMENT_COLUMNS));
          } catch (IOException unavailable) {
            /* 并发移除或不允许访问的外部链接，不伪报可读文件。 */
          }
        }
        checkQueryCancellation();
        Cursor result = snapshot.finish(projection, LOCAL_QUERY_SIGNAL.get());
        try {
          result.setNotificationUri(
              getContext().getContentResolver(),
              DocumentsContract.buildChildDocumentsUri(authority, id));
          return result;
        } catch (RuntimeException error) {
          result.close();
          throw error;
        }
      }
    } catch (IOException | DirectoryIteratorException | SQLiteException | SecurityException error) {
      throw failure(error);
    }
  }

  @Override
  public boolean isChildDocument(String parent, String child) {
    try (RuntimeTasks ignored = lease(false)) {
      resolve(child, true);
      return paths.childOf(parent, child);
    } catch (IOException error) {
      return false;
    }
  }

  @Override
  public String getDocumentType(String id) throws FileNotFoundException {
    try (RuntimeTasks ignored = lease(false)) {
      return mime(existing(id));
    } catch (IOException error) {
      throw failure(error);
    }
  }

  @Override
  public Cursor querySearchDocuments(String root, String query, String[] projection)
      throws FileNotFoundException {
    try (RuntimeTasks ignored = lease(false)) {
      MatrixCursor result = documents(projection, "root");
      if (!ROOT_ID.equals(root) || query == null || query.trim().isEmpty()) return result;
      String wanted = query.trim().toLowerCase(Locale.ROOT);
      ArrayDeque<String> pending = new ArrayDeque<>();
      pending.add("root");
      Set<String> visited = new HashSet<>();
      int entries = 0;
      while (!pending.isEmpty() && result.getCount() < 200 && entries < 20_000) {
        checkQueryCancellation();
        String parent = pending.removeFirst();
        try {
          File dir = resolve(parent, true);
          if (!visited.add(dir.getPath())) continue;
          try (DirectoryStream<Path> children = Files.newDirectoryStream(dir.toPath())) {
            for (Path entry : children) {
              checkQueryCancellation();
              if (++entries > 20_000 || result.getCount() >= 200) break;
              String name = entry.getFileName().toString();
              try {
                String id = paths.child(parent, name);
                File file = resolve(id, true);
                if (name.toLowerCase(Locale.ROOT).contains(wanted)) include(result, id);
                if (file.isDirectory()) pending.addLast(id);
              } catch (IOException unavailable) {
              }
            }
          }
        } catch (InterruptedIOException cancelled) {
          throw cancelled;
        } catch (IOException | DirectoryIteratorException unavailable) {
          // A disappearing or unreadable queued directory must not discard unrelated matches.
        }
      }
      return result;
    } catch (IOException error) {
      throw failure(error);
    }
  }

  @Override
  public ParcelFileDescriptor openDocument(String id, String mode, CancellationSignal signal)
      throws FileNotFoundException {
    RuntimeTasks task = lease(true);
    ParcelFileDescriptor fd = null;
    try {
      if (signal != null) signal.throwIfCanceled();
      File file = existing(id);
      if (!file.isFile())
        throw new IOException(com.deepseekharness.app.util.UiText.text("目录或特殊文件不能直接打开"));
      int parsed = ParcelFileDescriptor.parseMode("rt".equals(mode) ? "r" : mode);
      // 在 fd 身份校验之前不能截断，避免父目录并发替换造成越权。
      int openMode =
          parsed & ~ParcelFileDescriptor.MODE_TRUNCATE & ~ParcelFileDescriptor.MODE_CREATE;
      boolean writing = (parsed & ParcelFileDescriptor.MODE_WRITE_ONLY) != 0;
      // isFile() is only an observation: the runtime may replace that pathname with a FIFO
      // before open(). Pin a NONBLOCK, NOFOLLOW regular descriptor before the listener-backed
      // open, so that replacement cannot indefinitely block a Binder thread and its lease.
      try (ParcelFileDescriptor pinned = pinRegular(file, parsed)) {
        fd =
            ParcelFileDescriptor.open(
                new File("/proc/self/fd/" + pinned.getFd()),
                openMode,
                new Handler(Looper.getMainLooper()),
                error -> {
                  try {
                    if (writing) changed(id);
                  } finally {
                    task.close();
                  }
                });
      }
      String actual = Os.readlink("/proc/self/fd/" + fd.getFd());
      if (!actual.equals(file.getAbsolutePath())
          || !DocumentPaths.within(paths.base(), new File(actual)))
        throw new IOException(com.deepseekharness.app.util.UiText.text("文件位置已改变，请刷新目录后重试"));
      if (signal != null) signal.throwIfCanceled();
      if ((parsed & ParcelFileDescriptor.MODE_TRUNCATE) != 0)
        Os.ftruncate(fd.getFileDescriptor(), 0);
      return fd;
    } catch (Exception error) {
      if (fd != null)
        try {
          fd.close();
        } catch (IOException ignored) {
        }
      task.close();
      if (error instanceof android.os.OperationCanceledException)
        throw (android.os.OperationCanceledException) error;
      throw failure(error);
    }
  }

  private static ParcelFileDescriptor pinRegular(File file, int parsed)
      throws IOException, ErrnoException {
    int access = parsed & ParcelFileDescriptor.MODE_READ_WRITE;
    int flags =
        access == ParcelFileDescriptor.MODE_READ_ONLY
            ? OsConstants.O_RDONLY
            : access == ParcelFileDescriptor.MODE_WRITE_ONLY
                ? OsConstants.O_WRONLY
                : OsConstants.O_RDWR;
    FileDescriptor raw =
        Os.open(
            file.getPath(),
            flags | OsConstants.O_NONBLOCK | OsConstants.O_NOFOLLOW | OsConstants.O_CLOEXEC,
            0);
    try {
      if (!OsConstants.S_ISREG(Os.fstat(raw).st_mode))
        throw new IOException(com.deepseekharness.app.util.UiText.text("目录或特殊文件不能直接打开"));
      return ParcelFileDescriptor.dup(raw);
    } finally {
      Os.close(raw);
    }
  }

  @Override
  public synchronized String createDocument(String parent, String mimeType, String name)
      throws FileNotFoundException {
    try (RuntimeTasks ignored = lease(false)) {
      DocumentPaths.checkName(name);
      File dir = existing(parent);
      if (!dir.isDirectory())
        throw new IOException(com.deepseekharness.app.util.UiText.text("只能在目录内创建文件"));
      for (int suffix = 0; suffix < 1000; suffix++) {
        String candidate = suffix == 0 ? name : uniqueName(name, suffix);
        String id = paths.child(parent, candidate);
        File file = resolve(id, false);
        try {
          // Resolve validates the logical document; the actual operation must not
          // follow a parent replaced after that check. Reuse the host's pinned,
          // NOFOLLOW descriptor walk and exclusive creation.
          if (DocumentsContract.Document.MIME_TYPE_DIR.equals(mimeType)) mutations.directory(file);
          else try (java.io.OutputStream output = mutations.create(file)) {}
          changed(id);
          return id;
        } catch (IOException error) {
          if (!alreadyExists(error)) throw error;
        }
      }
      throw new IOException(com.deepseekharness.app.util.UiText.text("同名文件过多，请更换名称"));
    } catch (IOException error) {
      throw failure(error);
    }
  }

  private static boolean alreadyExists(IOException error) {
    for (Throwable cause = error; cause != null; cause = cause.getCause())
      if (cause instanceof ErrnoException && ((ErrnoException) cause).errno == OsConstants.EEXIST)
        return true;
    return false;
  }

  private static String uniqueName(String name, int suffix) {
    int dot = name.lastIndexOf('.');
    return dot > 0
        ? name.substring(0, dot) + " (" + suffix + ")" + name.substring(dot)
        : name + " (" + suffix + ")";
  }

  @Override
  public synchronized String renameDocument(String id, String name) throws FileNotFoundException {
    try (RuntimeTasks ignored = lease(false)) {
      DocumentPaths.checkName(name);
      if (paths.anchor(id))
        throw new IOException(com.deepseekharness.app.util.UiText.text("环境入口目录不能重命名"));
      String next = paths.child(paths.parent(id), name);
      if (next.equals(id)) return null;
      File source = resolve(id, false), target = resolve(next, false);
      if (target.exists() || link(target) != null)
        throw new IOException(com.deepseekharness.app.util.UiText.text("已存在同名文件"));
      List<String> oldIds = descendants(id);
      mutations.move(source, target);
      for (String old : oldIds) if (!old.equals(id)) revokeDocumentPermission(old);
      changed(id);
      changed(next);
      return next;
    } catch (IOException error) {
      throw failure(error);
    }
  }

  @Override
  public synchronized void deleteDocument(String id) throws FileNotFoundException {
    try (RuntimeTasks ignored = lease(false)) {
      if (paths.anchor(id))
        throw new IOException(com.deepseekharness.app.util.UiText.text("环境入口目录不能删除"));
      List<String> ids = descendants(id);
      for (int i = ids.size() - 1; i >= 0; i--) {
        String child = ids.get(i);
        File file = resolve(child, false);
        mutations.delete(file);
        revokeDocumentPermission(child);
      }
      changed(id);
    } catch (IOException error) {
      changed(id);
      throw failure(error);
    }
  }

  /** 先列出本次范围；最终软链接只移除链接本身，不递归目标。 */
  private List<String> descendants(String id) throws IOException {
    List<String> ids = new ArrayList<>();
    ids.add(id);
    for (int i = 0; i < ids.size(); i++) {
      File file = resolve(ids.get(i), false);
      if (link(file) != null) continue;
      if (!file.exists())
        throw new IOException(com.deepseekharness.app.util.UiText.format("文件不存在：%s", ids.get(i)));
      if (!file.isDirectory()) continue;
      try (DirectoryStream<Path> children = Files.newDirectoryStream(file.toPath())) {
        for (Path child : children) {
          if (ids.size() >= 100_000)
            throw new IOException(com.deepseekharness.app.util.UiText.text("目录项目过多，请分批操作"));
          ids.add(paths.child(ids.get(i), child.getFileName().toString()));
        }
      } catch (DirectoryIteratorException error) {
        throw error.getCause();
      }
    }
    return ids;
  }

  private void changed(String id) {
    try {
      getContext()
          .getContentResolver()
          .notifyChange(DocumentsContract.buildDocumentUri(authority, id), null);
      String parent = paths.parent(id);
      getContext()
          .getContentResolver()
          .notifyChange(DocumentsContract.buildDocumentUri(authority, parent), null);
      getContext()
          .getContentResolver()
          .notifyChange(DocumentsContract.buildChildDocumentsUri(authority, parent), null);
    } catch (Exception ignored) {
    }
  }

  private void include(MatrixCursor result, String id) throws IOException {
    result.addRow(values(id, result.getColumnNames()));
  }

  private Object[] values(String id, String[] columns) throws IOException {
    File entry = resolve(id, false), file = resolve(id, true);
    boolean symbolic = link(entry) != null;
    if (!file.exists() && !symbolic)
      throw new IOException(com.deepseekharness.app.util.UiText.text("文件已移除"));
    int flags = 0;
    if (!paths.anchor(id) && entry.getParentFile().canWrite())
      flags |=
          DocumentsContract.Document.FLAG_SUPPORTS_DELETE
              | DocumentsContract.Document.FLAG_SUPPORTS_RENAME;
    if (file.canWrite())
      flags |=
          file.isDirectory()
              ? DocumentsContract.Document.FLAG_DIR_SUPPORTS_CREATE
              : file.isFile() ? DocumentsContract.Document.FLAG_SUPPORTS_WRITE : 0;
    Object[] row = new Object[columns.length];
    for (int i = 0; i < columns.length; i++) {
      String column = columns[i];
      Object value;
      switch (column) {
        case "document_id":
          value = paths.id(id);
          break;
        case "_display_name":
          value = paths.relative(id).isEmpty() ? "DSHA" : new File(id).getName();
          break;
        case "mime_type":
          value = mime(file);
          break;
        case "last_modified":
          value = file.lastModified();
          break;
        case "_size":
          value = file.isFile() ? file.length() : null;
          break;
        case "flags":
          value = flags;
          break;
        default:
          value = null;
      }
      row[i] = value;
    }
    return row;
  }

  private static String mime(File file) {
    if (file.isDirectory()) return DocumentsContract.Document.MIME_TYPE_DIR;
    String name = file.getName().toLowerCase(Locale.ROOT);
    int dot = name.lastIndexOf('.');
    String mime =
        dot >= 0
            ? MimeTypeMap.getSingleton().getMimeTypeFromExtension(name.substring(dot + 1))
            : null;
    if (mime != null) return mime;
    if (name.endsWith(".zstd")) return "application/zstd";
    return "application/octet-stream";
  }
}
