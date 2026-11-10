package com.deepseekharness.app.saf;

import android.content.ContentResolver;
import android.database.Cursor;
import android.net.Uri;
import android.os.CancellationSignal;
import android.os.OperationCanceledException;
import android.os.ParcelFileDescriptor;
import android.provider.DocumentsContract;
import androidx.core.provider.DocumentsContractCompat;
import java.io.ByteArrayOutputStream;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/**
 * 一条已授权 tree 上的只读/写入操作。
 *
 * <p>路径是相对授权根的普通路径（{@code /files/config.json}），逐段用
 * {@link DocumentsContract#buildChildDocumentsUriUsingTree} + 显示名解析成文档 id，
 * 因此不依赖各家 Provider 自己的 id 规则（MT 注入的 Provider、网盘、系统文档源都能用）。
 * 解析失败不猜、不跨根、不允许 {@code ..}。
 *
 * <p>一次公开操作共用一个截止时间：内部 {@code resolve()} / {@code entry()} 接受外层
 * {@link Deadline}，不再各自计时（嵌套截止会互相清除 callback，等于没有截止）。每个
 * deadline 有独立 token，结束时只移除自己的 callback，不会清掉别的操作。
 *
 * <p>目标 Provider 可能很慢、离线甚至故意不响应；{@code query/openFileDescriptor} 都带
 * {@link CancellationSignal}。{@code createDocument}/{@code deleteDocument} 在框架层没有
 * signal 参数，无法中断，只能在返回后检查截止并映射为 TIMEOUT。
 */
public final class SafTree {
  /** Provider 的父文档列（AOSP DocumentsContract.Document 中同名常量为 hidden API）。 */
  private static final String COLUMN_PARENT_DOCUMENT_ID = "parent_document_id";

  /** 单次列目录上限；超过只给前 limit 条，并报告被截断。 */
  public static final int MAX_ENTRIES = 500;

  /** 单次读取上限（与 /app/readfile 的 256 KiB 对齐）。 */
  public static final int MAX_READ = 256 * 1024;

  /** 单次写入上限。 */
  public static final long MAX_WRITE = 32L * 1024 * 1024;

  /**
   * 跨进程调用的统一截止时间：可中断调用在截止时取消并返回 TIMEOUT；createDocument 等不可中断
   * 调用只能在返回后复核截止，Provider 完全忽略取消时后台线程仍会被占用到它返回为止。
   */
  public static final long DEADLINE_MS = 15000L;

  /** 删除确认 token 登记：一次性、绑定 treeId/规范化路径/探测时的目标身份与 60 秒有效期。 */
  private static final java.util.concurrent.ConcurrentHashMap<String, Pending> PENDING_DELETES =
      new java.util.concurrent.ConcurrentHashMap<>();

  private static volatile long CONFIRM_TTL_NANOS =
      java.util.concurrent.TimeUnit.SECONDS.toNanos(60);

  /** 探测返回的一次性确认凭据。 */
  public static final class PendingDelete {
    public final String token;
    public final String hint;

    PendingDelete(String token, String hint) {
      this.token = token;
      this.hint = hint;
    }
  }

  private static final class Pending {
    final String tree;
    final String path;
    final String docId;
    final long size;
    final long modified;
    final long expiresAt;

    Pending(String tree, String path, String docId, long size, long modified, long expiresAt) {
      this.tree = tree;
      this.path = path;
      this.docId = docId;
      this.size = size;
      this.modified = modified;
      this.expiresAt = expiresAt;
    }
  }

  private static final String[] COLUMNS = {
    DocumentsContract.Document.COLUMN_DOCUMENT_ID,
    DocumentsContract.Document.COLUMN_DISPLAY_NAME,
    DocumentsContract.Document.COLUMN_MIME_TYPE,
    DocumentsContract.Document.COLUMN_SIZE,
    DocumentsContract.Document.COLUMN_LAST_MODIFIED
  };

  /** 单个 deadline 的看门狗：真实时间计时，不占用主线程消息队列。 */
  private static final ScheduledExecutorService WATCHDOG =
      Executors.newSingleThreadScheduledExecutor(
          runnable -> {
            Thread thread = new Thread(runnable, "saf-deadline");
            thread.setDaemon(true);
            return thread;
          });

  /** 失败原因；{@code code} 是给桥与界面共用的大写短码，别直接展示给用户。 */
  public static final class Failure extends Exception {
    public final String code;

    public Failure(String code, String detail) {
      super(detail == null || detail.isEmpty() ? code : code + ": " + detail);
      this.code = code;
    }
  }

  public static final class Entry {
    public final String docId;
    public final String name;
    public final String mime;
    public final boolean directory;
    public final long size;
    public final long modified;

    Entry(String docId, String name, String mime, long size, long modified) {
      this.docId = docId;
      this.name = name;
      this.mime = mime;
      this.directory = DocumentsContract.Document.MIME_TYPE_DIR.equals(mime);
      this.size = size;
      this.modified = modified;
    }
  }

  /** 一页目录：列表 + 是否因超过 limit 被截断。 */
  public static final class Page {
    public final List<Entry> entries;
    public final boolean truncated;

    Page(List<Entry> entries, boolean truncated) {
      this.entries = entries;
      this.truncated = truncated;
    }
  }

  /**
   * 一次公开操作共享的取消信号与截止时间；内层调用只复用，不再单独计时。
   *
   * <p>每个 deadline 持有独立的挂起任务标记（{@link ScheduledFuture}），结束时只取消
   * 自己那一个，等价于 {@code postDelayed(cancel, token, DEADLINE_MS)} +
   * {@code removeCallbacksAndMessages(token)}，不会像 {@code null} 那样清掉别的操作。
   */
  static final class Deadline implements AutoCloseable {
    final CancellationSignal signal = new CancellationSignal();
    private final ScheduledFuture<?> timeout;

    Deadline(long budgetMs) {
      timeout = WATCHDOG.schedule(signal::cancel, budgetMs, TimeUnit.MILLISECONDS);
    }

    @Override
    public void close() {
      timeout.cancel(false);
    }

    /** 可中断调用返回后立刻复核：超过截止就按 TIMEOUT 处理，别把过期结果当成功。 */
    void check() throws OperationCanceledException {
      if (signal.isCanceled()) throw new OperationCanceledException();
    }
  }

  /** 包内复用（SafGrants 的 label 查询也走同一套截止机制）。 */
  static Deadline newDeadline(long budgetMs) {
    return new Deadline(budgetMs);
  }

  @FunctionalInterface
  private interface Timed<T> {
    T run(Deadline deadline) throws Failure;
  }

  private final ContentResolver resolver;
  private final Uri tree;
  private final String rootId;
  private final long deadlineMs;

  public SafTree(ContentResolver resolver, Uri tree) throws Failure {
    this(resolver, tree, DEADLINE_MS);
  }

  /** 包内测试入口：缩短截止时间，验证 TIMEOUT / 取消路径。 */
  SafTree(ContentResolver resolver, Uri tree, long deadlineMs) throws Failure {
    if (resolver == null || tree == null) throw new Failure("NOT_TREE", null);
    if (!"content".equals(tree.getScheme()) || !DocumentsContractCompat.isTreeUri(tree))
      throw new Failure("NOT_TREE", null);
    if (deadlineMs <= 0) throw new Failure("BAD_DEADLINE", Long.toString(deadlineMs));
    this.resolver = resolver;
    this.tree = tree;
    this.deadlineMs = deadlineMs;
    String id;
    try {
      id = DocumentsContract.getTreeDocumentId(tree);
    } catch (Throwable bad) {
      throw new Failure("NOT_TREE", null);
    }
    if (id == null || id.isEmpty()) throw new Failure("NOT_TREE", null);
    this.rootId = id;
  }

  public Uri uri() {
    return tree;
  }

  /** 授权根自身的条目信息（应用名之外再拿目录名与可读性）。 */
  public Entry root() throws Failure {
    return one("root", deadline -> entry(deadline, rootId));
  }

  /** 列目录：目录在前、同类按名字排序；超过 limit 截断并在 {@link Page#truncated} 里报告。 */
  public Page list(String path, int limit) throws Failure {
    int cap = Math.max(1, Math.min(limit <= 0 ? MAX_ENTRIES : limit, MAX_ENTRIES));
    return one(
        "list:" + path,
        deadline -> {
          Page page = children(deadline, resolve(deadline, path), cap);
          page.entries.sort(
              (left, right) -> {
                if (left.directory != right.directory) return left.directory ? -1 : 1;
                return left.name.compareToIgnoreCase(right.name);
              });
          return page;
        });
  }

  public Entry stat(String path) throws Failure {
    return one("stat:" + path, deadline -> entry(deadline, resolve(deadline, path)));
  }

  /**
   * 读一段字节：{@code offset} 用于大文件分段读，{@code max} 上限 {@link #MAX_READ}。
   * 负偏移直接报参数错误；非可 seek 的 Provider 也能工作（按块丢弃前缀，不依赖 skip）。
   */
  public byte[] read(String path, long offset, int max) throws Failure {
    if (offset < 0) throw new Failure("BAD_OFFSET", Long.toString(offset));
    int cap = Math.max(1, Math.min(max <= 0 ? MAX_READ : max, MAX_READ));
    return one(
        "read:" + path,
        deadline -> {
          Entry entry = entry(deadline, resolve(deadline, path));
          if (entry.directory) throw new Failure("IS_DIRECTORY", entry.name);
          // 不可 seek 的 Provider 只能从文件头读掉 offset 字节；先用 stat 的大小拒绝越界请求，
          // 大小未知时按桥支持的最大单文件（32 MiB）兜底，别让一个离谱的 offset 白读整份文件。
          if (entry.size >= 0 ? offset > entry.size : offset > MAX_WRITE)
            throw new Failure("OFFSET_OUT_OF_RANGE", Long.toString(offset));
          try (ParcelFileDescriptor descriptor =
              resolver.openFileDescriptor(documentUri(entry.docId), "r", deadline.signal)) {
            deadline.check();
            if (descriptor == null) throw new Failure("UNREADABLE", entry.name);
            try (FileInputStream input = new FileInputStream(descriptor.getFileDescriptor())) {
              byte[] scratch = new byte[8192];
              long remaining = offset;
              while (remaining > 0) {
                deadline.check();
                int read = input.read(scratch, 0, (int) Math.min(scratch.length, remaining));
                if (read <= 0) throw new Failure("OFFSET_OUT_OF_RANGE", Long.toString(offset));
                remaining -= read;
              }
              ByteArrayOutputStream out = new ByteArrayOutputStream(cap);
              byte[] buffer = new byte[Math.min(cap, 65536)];
              while (out.size() < cap) {
                deadline.check();
                int read = input.read(buffer, 0, Math.min(buffer.length, cap - out.size()));
                if (read <= 0) break;
                out.write(buffer, 0, read);
              }
              deadline.check();
              return out.toByteArray();
            }
          } catch (OperationCanceledException canceled) {
            throw canceled;
          } catch (SecurityException denied) {
            throw new Failure("PERMISSION_DENIED", null);
          } catch (FileNotFoundException missing) {
            deadline.check();
            throw new Failure("NOT_FOUND", entry.name);
          } catch (IOException error) {
            deadline.check();
            throw new Failure("IO_ERROR", error.getMessage());
          }
        });
  }

  /** 一次写入的目标：已有文档或刚创建的文档。 */
  private static final class WriteTarget {
    final Uri uri;
    final String docId;
    final boolean created;

    WriteTarget(Uri uri, String docId, boolean created) {
      this.uri = uri;
      this.docId = docId;
      this.created = created;
    }
  }

  /** 覆盖写；{@code create} 为真时目标不存在会按路径新建（父目录必须已存在）。写完核对长度。 */
  public long write(String path, byte[] data, boolean create) throws Failure {
    final byte[] payload = data == null ? new byte[0] : data;
    if (payload.length > MAX_WRITE) throw new Failure("TOO_LARGE", Long.toString(payload.length));
    String clean = normalize(path);
    if (clean.isEmpty()) throw new Failure("BAD_PATH", path);
    return one(
        "write:" + clean,
        deadline -> {
          WriteTarget target = prepareWriteTarget(deadline, clean, create);
          try {
            long written =
                writePayload(
                    deadline,
                    target,
                    new java.io.ByteArrayInputStream(payload),
                    payload.length,
                    clean);
            verifyWritten(deadline, target, clean, written);
            return written;
          } catch (Failure | RuntimeException error) {
            // 新建文档写到一半失败时清理半截文档；已有文档不在这里删除（原地覆盖，无法回滚）。
            if (target.created) deleteQuietly(target.uri);
            throw error;
          }
        });
  }

  /**
   * 流式写入：push 用 64 KiB 缓冲边读边写，不再把 32 MiB 整份读进内存。
   * {@code expectedLength} 为 -1 表示未知（只受 {@link #MAX_WRITE} 限制）。
   */
  public long writeStream(
      String path, java.io.InputStream input, long expectedLength, boolean create) throws Failure {
    if (input == null) throw new Failure("NO_SOURCE", null);
    if (expectedLength > MAX_WRITE) throw new Failure("TOO_LARGE", Long.toString(expectedLength));
    String clean = normalize(path);
    if (clean.isEmpty()) throw new Failure("BAD_PATH", path);
    return one(
        "write:" + clean,
        deadline -> {
          WriteTarget target = prepareWriteTarget(deadline, clean, create);
          try {
            long written = writePayload(deadline, target, input, expectedLength, clean);
            verifyWritten(deadline, target, clean, written);
            return written;
          } catch (Failure | RuntimeException error) {
            if (target.created) deleteQuietly(target.uri);
            throw error;
          }
        });
  }

  private WriteTarget prepareWriteTarget(Deadline deadline, String clean, boolean create)
      throws Failure {
    String docId = null;
    try {
      docId = resolve(deadline, clean);
    } catch (Failure missing) {
      // 大目录里列表会被截断；create=true 时把它当作「未找到」，走 Provider 的 createDocument。
      boolean absent = "NOT_FOUND".equals(missing.code) || "SCAN_LIMIT".equals(missing.code);
      if (!absent || !create) throw missing;
    }
    if (docId != null) {
      Entry existing = entry(deadline, docId);
      if (existing.directory) throw new Failure("IS_DIRECTORY", existing.name);
      return new WriteTarget(documentUri(docId), docId, false);
    }
    int cut = clean.lastIndexOf('/');
    String parentPath = cut <= 0 ? "" : clean.substring(0, cut);
    String name = cut < 0 ? clean : clean.substring(cut + 1);
    if (name.isEmpty() || ".".equals(name) || "..".equals(name))
      throw new Failure("BAD_PATH", clean);
    String parentId = resolve(deadline, parentPath);
    Uri parent = documentUri(parentId);
    Uri target;
    try {
      target = DocumentsContract.createDocument(resolver, parent, mimeOf(name), name);
    } catch (SecurityException denied) {
      throw new Failure("PERMISSION_DENIED", null);
    } catch (FileNotFoundException missing) {
      deadline.check();
      throw new Failure("NOT_FOUND", parentPath);
    }
    try {
      // createDocument 在框架层没有 CancellationSignal：返回后立刻复核截止。
      deadline.check();
      if (target == null) throw new Failure("CREATE_FAILED", name);
      // 新建文档直接校验返回 URI；不依赖父目录列表（列表可能截断或短暂延迟）。
      if (!createdChildOf(deadline, parent, target, name)) throw new Failure("BAD_CHILD_URI", name);
      return new WriteTarget(target, DocumentsContract.getDocumentId(target), true);
    } catch (Failure | RuntimeException error) {
      // 返回 URI 未通过归属校验时可能是既有文档，不能删；宁可不清理也不能造成数据丢失。
      throw error;
    }
  }

  private long writePayload(
      Deadline deadline,
      WriteTarget target,
      java.io.InputStream input,
      long expectedLength,
      String clean)
      throws Failure {
    long total = 0;
    try (ParcelFileDescriptor descriptor =
        resolver.openFileDescriptor(target.uri, "wt", deadline.signal)) {
      deadline.check();
      if (descriptor == null) throw new Failure("UNWRITABLE", null);
      try (FileOutputStream output = new FileOutputStream(descriptor.getFileDescriptor())) {
        byte[] buffer = new byte[65536];
        int read;
        while ((read = input.read(buffer)) > 0) {
          // 大块 I/O 分段写，每段之间复核取消/截止。
          deadline.check();
          if (total + read > MAX_WRITE) throw new Failure("TOO_LARGE", Long.toString(total + read));
          output.write(buffer, 0, read);
          total += read;
        }
        output.flush();
        deadline.check();
        if (expectedLength >= 0 && total != expectedLength)
          throw new Failure("SIZE_MISMATCH", total + "/" + expectedLength);
        return total;
      }
    } catch (OperationCanceledException canceled) {
      throw canceled;
    } catch (SecurityException denied) {
      throw new Failure("PERMISSION_DENIED", null);
    } catch (FileNotFoundException missing) {
      deadline.check();
      throw new Failure("NOT_FOUND", clean);
    } catch (IOException error) {
      deadline.check();
      throw new Failure("IO_ERROR", error.getMessage());
    }
  }

  /** 写后核对：Provider 可能中途失败或静默截断，落盘长度必须与请求一致。 */
  private void verifyWritten(Deadline deadline, WriteTarget target, String clean, long length)
      throws Failure {
    try {
      Entry after = entry(deadline, target.docId != null ? target.docId : resolve(deadline, clean));
      if (after.size >= 0 && after.size != length)
        throw new Failure("WRITE_INCOMPLETE", after.size + "/" + length);
    } catch (Failure verify) {
      if ("WRITE_INCOMPLETE".equals(verify.code)) throw verify;
      // 查不到新文档：当作 Provider 报告不一致，不让调用方误以为成功。
      throw new Failure("WRITE_UNVERIFIED", verify.getMessage());
    }
  }

  public void mkdir(String path) throws Failure {
    String clean = normalize(path);
    if (clean.isEmpty()) throw new Failure("BAD_PATH", path);
    int cut = clean.lastIndexOf('/');
    String parentPath = cut <= 0 ? "" : clean.substring(0, cut);
    String name = cut < 0 ? clean : clean.substring(cut + 1);
    if (name.isEmpty() || ".".equals(name) || "..".equals(name))
      throw new Failure("BAD_PATH", clean);
    one(
        "mkdir:" + clean,
        deadline -> {
          String parentId = resolve(deadline, parentPath);
          Uri parent = documentUri(parentId);
          Uri created;
          try {
            created =
                DocumentsContract.createDocument(
                    resolver, parent, DocumentsContract.Document.MIME_TYPE_DIR, name);
          } catch (SecurityException denied) {
            throw new Failure("PERMISSION_DENIED", null);
          } catch (FileNotFoundException missing) {
            deadline.check();
            throw new Failure("NOT_FOUND", parentPath);
          }
          try {
            // createDocument 没有 signal：返回后复核截止。
            deadline.check();
            if (created == null) throw new Failure("CREATE_FAILED", name);
            // 与 write 相同：直接校验 Provider 返回的 URI（不查父列表）。
            if (!createdChildOf(deadline, parent, created, name))
              throw new Failure("BAD_CHILD_URI", name);
            return null;
          } catch (Failure | RuntimeException error) {
            // 同 write：归属校验失败时返回 URI 可能是既有目录，不能删。
            throw error;
          }
        });
  }

  /** 删除文件/空目录；空路径、根路径与解析回根目录的请求一律拒绝。 */
  /**
   * 删除探测：不执行删除，登记一次性 token 并返回目标路径与（目录）子项数，由调用方展示后
   * 再用同一 token 调用 {@link #deleteConfirmed}；单条 confirm 参数不能放行。
   */
  public PendingDelete delete(String path) throws Failure {
    String clean = normalize(path);
    if (clean.isEmpty()) throw new Failure("BAD_PATH", "empty path; root is never deletable");
    return one(
        "delete-probe:" + clean,
        deadline -> {
          String docId = resolve(deadline, clean);
          if (docId.equals(rootId))
            throw new Failure("BAD_PATH", "root of the granted tree is not deletable");
          Entry entry = entry(deadline, docId);
          String hint = clean;
          if (entry.directory) {
            Page children = children(deadline, docId, MAX_ENTRIES + 1);
            hint +=
                children.truncated
                    ? " (超过 " + MAX_ENTRIES + " 个子项)"
                    : " (" + children.entries.size() + " 个子项)";
          }
          String token = java.util.UUID.randomUUID().toString().replace("-", "");
          long now = System.nanoTime();
          prunePending(now);
          PENDING_DELETES.put(
              token,
              new Pending(
                  tree.toString(),
                  clean,
                  docId,
                  entry.size,
                  entry.modified,
                  now + CONFIRM_TTL_NANOS));
          return new PendingDelete(token, hint);
        });
  }

  /** 消费一次性 token 并执行删除；目录会连同内容递归删除。 */
  public void deleteConfirmed(String path, String token) throws Failure {
    String clean = normalize(path);
    if (clean.isEmpty()) throw new Failure("BAD_PATH", "empty path; root is never deletable");
    if (token == null || token.isEmpty())
      throw new Failure("CONFIRM_REQUIRED", "missing one-time token");
    Pending pending = PENDING_DELETES.remove(token);
    if (pending == null) throw new Failure("CONFIRM_REQUIRED", "token not found or consumed");
    if (System.nanoTime() - pending.expiresAt > 0)
      throw new Failure("CONFIRM_EXPIRED", "token expired; probe again");
    if (!pending.tree.equals(tree.toString()) || !pending.path.equals(clean))
      throw new Failure("CONFIRM_MISMATCH", "token is bound to another tree/path");
    one(
        "delete:" + clean,
        deadline -> {
          String docId = resolve(deadline, clean);
          if (docId.equals(rootId))
            throw new Failure("BAD_PATH", "root of the granted tree is not deletable");
          Entry entry = entry(deadline, docId);
          if (!docId.equals(pending.docId)
              || entry.size != pending.size
              || entry.modified != pending.modified)
            throw new Failure("CONFIRM_MISMATCH", "target changed since probe; probe again");
          // 先预检所有目录层；任何一层超过 500 子项就在删除前拒绝，避免半删。
          preflightTree(deadline, docId, clean);
          deleteTree(deadline, docId, clean);
          return null;
        });
  }

  /** 递归删除前的只读预检：任何一层子项超过分页上限就拒绝，不先删任何文件。 */
  private void preflightTree(Deadline deadline, String docId, String path) throws Failure {
    Entry entry = entry(deadline, docId);
    if (!entry.directory) return;
    Page children = children(deadline, docId, MAX_ENTRIES);
    if (children.truncated) throw new Failure("SCAN_LIMIT", path);
    for (Entry child : children.entries) {
      if (child.directory) preflightTree(deadline, child.docId, path + "/" + child.name);
    }
  }

  /** 先完整检查一层子项再删除，避免 Provider 只支持有限分页时留下半删目录。 */
  private void deleteTree(Deadline deadline, String docId, String path) throws Failure {
    Entry entry = entry(deadline, docId);
    if (entry.directory) {
      Page children = children(deadline, docId, MAX_ENTRIES + 1);
      if (children.truncated) throw new Failure("SCAN_LIMIT", path);
      for (Entry child : children.entries) {
        String childPath = path + "/" + child.name;
        if (child.directory) deleteTree(deadline, child.docId, childPath);
        else deleteDocument(deadline, child.docId, childPath);
      }
    }
    deleteDocument(deadline, docId, path);
  }

  private void deleteDocument(Deadline deadline, String docId, String path) throws Failure {
    try {
      boolean deleted = DocumentsContract.deleteDocument(resolver, documentUri(docId));
      deadline.check();
      if (!deleted) throw new Failure("DELETE_FAILED", path);
    } catch (SecurityException denied) {
      throw new Failure("PERMISSION_DENIED", null);
    } catch (FileNotFoundException missing) {
      deadline.check();
      throw new Failure("NOT_FOUND", path);
    }
  }

  private static void prunePending(long now) {
    PENDING_DELETES.entrySet().removeIf(value -> now - value.getValue().expiresAt > 0);
    // 满了按最早过期时间淘汰，不做「一刀切清空」——清空会让并发的确认请求被无差别打扰。
    while (PENDING_DELETES.size() >= 128) {
      String oldestKey = null;
      long oldest = Long.MAX_VALUE;
      for (java.util.Map.Entry<String, Pending> entry : PENDING_DELETES.entrySet()) {
        if (entry.getValue().expiresAt < oldest) {
          oldest = entry.getValue().expiresAt;
          oldestKey = entry.getKey();
        }
      }
      if (oldestKey == null) break;
      PENDING_DELETES.remove(oldestKey);
    }
  }

  /** 测试观察 pending 数量（只读）。 */
  static int pendingCountForTests() {
    return PENDING_DELETES.size();
  }

  /** 测试清理：避免跨用例的待确认登记互相影响。 */
  static void clearPendingDeletesForTests() {
    PENDING_DELETES.clear();
  }

  /** 测试缩短/恢复 token TTL。 */
  static void setConfirmTtlForTests(long nanos) {
    CONFIRM_TTL_NANOS = nanos;
  }

  public static String normalize(String path) throws Failure {
    try {
      return SafPaths.normalize(path);
    } catch (IllegalArgumentException error) {
      String message = String.valueOf(error.getMessage());
      String code =
          message != null && message.startsWith("PATH_TOO_DEEP") ? "PATH_TOO_DEEP" : "BAD_PATH";
      throw new Failure(code, path);
    }
  }

  /**
   * 新建文档的归属校验：同一 authority/tree、非空 documentId、Provider 明确它是请求父项的直接子项，
   * 且返回文档的显示名与请求一致。优先查 COLUMN_PARENT_DOCUMENT_ID + COLUMN_DISPLAY_NAME；
   * Provider 不返回 parent 列时用 isChildDocument；两者都不支持则失败关闭。
   * 不依赖父目录列表，因此大目录/列表延迟仍能新建。
   */
  private boolean createdChildOf(Deadline deadline, Uri parent, Uri child, String expectedName) {
    if (parent == null || child == null) return false;
    if (!tree.getAuthority().equals(child.getAuthority())) return false;
    try {
      String treeId = DocumentsContract.getTreeDocumentId(tree);
      String childTreeId = DocumentsContract.getTreeDocumentId(child);
      String parentId = DocumentsContract.getDocumentId(parent);
      String childId = DocumentsContract.getDocumentId(child);
      if (treeId == null || childTreeId == null || !treeId.equals(childTreeId)) return false;
      if (parentId == null || childId == null || childId.isEmpty()) return false;
      if (childId.equals(parentId)) return false;
      String displayed = null;
      String actualParent = null;
      try (android.database.Cursor cursor =
          resolver.query(
              child,
              new String[] {
                COLUMN_PARENT_DOCUMENT_ID, DocumentsContract.Document.COLUMN_DISPLAY_NAME
              },
              null,
              null,
              null,
              deadline.signal)) {
        deadline.check();
        if (cursor != null && cursor.moveToFirst()) {
          int parentIndex = cursor.getColumnIndex(COLUMN_PARENT_DOCUMENT_ID);
          int nameIndex = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_DISPLAY_NAME);
          if (nameIndex >= 0 && !cursor.isNull(nameIndex)) displayed = cursor.getString(nameIndex);
          if (parentIndex >= 0 && !cursor.isNull(parentIndex))
            actualParent = cursor.getString(parentIndex);
        }
      } catch (OperationCanceledException canceled) {
        throw canceled;
      } catch (Throwable unsupportedColumn) {
        // 列不可用，继续用 isChildDocument。
      }
      // 显示名必须与请求名一致：防止 Provider 返回同一父项下的既有 sibling/任意文档。
      if (displayed == null || !displayed.equals(expectedName)) return false;
      // a) parent 列可用时以它为准（明确的 mismatch 不降级）。
      if (actualParent != null) return parentId.equals(actualParent);
      // b) Provider 不返回 parent 列时用 isChildDocument；false/异常失败关闭。
      return DocumentsContract.isChildDocument(resolver, parent, child);
    } catch (OperationCanceledException canceled) {
      throw canceled;
    } catch (Throwable malformedOrUnsupported) {
      return false;
    }
  }

  /** 公开操作入口：整次操作共用一个截止时间；取消统一映射成 TIMEOUT。 */
  private <T> T one(String op, Timed<T> body) throws Failure {
    Deadline deadline = new Deadline(deadlineMs);
    try {
      T value = body.run(deadline);
      deadline.check();
      return value;
    } catch (OperationCanceledException canceled) {
      throw new Failure("TIMEOUT", op);
    } catch (SecurityException denied) {
      throw new Failure("PERMISSION_DENIED", null);
    } catch (RuntimeException error) {
      // Provider 可能把取消包装成别的运行时异常；截止已到就统一按 TIMEOUT 上报。
      try {
        deadline.check();
      } catch (OperationCanceledException canceled) {
        throw new Failure("TIMEOUT", op);
      }
      throw error;
    } finally {
      deadline.close();
    }
  }

  private String resolve(Deadline deadline, String path) throws Failure {
    String clean = normalize(path);
    if (clean.isEmpty()) return rootId;
    String docId = rootId;
    for (String segment : clean.split("/")) docId = child(deadline, docId, segment);
    return docId;
  }

  private String child(Deadline deadline, String parentId, String name) throws Failure {
    Page page = children(deadline, parentId, MAX_ENTRIES);
    for (Entry entry : page.entries) if (entry.name.equals(name)) return entry.docId;
    if (page.truncated) throw new Failure("SCAN_LIMIT", name);
    throw new Failure("NOT_FOUND", name);
  }

  private Entry entry(Deadline deadline, String docId) throws Failure {
    deadline.check();
    try (Cursor cursor =
        resolver.query(documentUri(docId), COLUMNS, null, null, null, deadline.signal)) {
      deadline.check();
      if (cursor == null || !cursor.moveToFirst()) throw new Failure("NOT_FOUND", docId);
      return row(cursor);
    } catch (OperationCanceledException canceled) {
      throw canceled;
    } catch (SecurityException denied) {
      throw new Failure("PERMISSION_DENIED", null);
    } catch (IllegalArgumentException unsupported) {
      throw new Failure("UNSUPPORTED", unsupported.getMessage());
    } catch (RuntimeException error) {
      deadline.check();
      throw new Failure("PROVIDER_ERROR", error.getMessage());
    }
  }

  private Page children(Deadline deadline, String parentId, int limit) throws Failure {
    Uri uri = DocumentsContract.buildChildDocumentsUriUsingTree(tree, parentId);
    deadline.check();
    try (Cursor cursor = resolver.query(uri, COLUMNS, null, null, null, deadline.signal)) {
      deadline.check();
      if (cursor == null) return new Page(new ArrayList<>(), false);
      List<Entry> entries = new ArrayList<>();
      boolean truncated = false;
      while (cursor.moveToNext()) {
        if (entries.size() >= limit) {
          truncated = true;
          break;
        }
        entries.add(row(cursor));
      }
      return new Page(entries, truncated);
    } catch (OperationCanceledException canceled) {
      throw canceled;
    } catch (SecurityException denied) {
      throw new Failure("PERMISSION_DENIED", null);
    } catch (IllegalArgumentException unsupported) {
      throw new Failure("UNSUPPORTED", unsupported.getMessage());
    } catch (RuntimeException error) {
      deadline.check();
      throw new Failure("PROVIDER_ERROR", error.getMessage());
    }
  }

  private Entry row(Cursor cursor) {
    String docId = cursor.isNull(0) ? "" : cursor.getString(0);
    String name = cursor.isNull(1) ? "" : cursor.getString(1);
    String mime = cursor.isNull(2) ? "" : cursor.getString(2);
    long size = cursor.isNull(3) ? -1L : cursor.getLong(3);
    long modified = cursor.isNull(4) ? -1L : cursor.getLong(4);
    return new Entry(docId, name, mime, size, modified);
  }

  private Uri documentUri(String docId) {
    return DocumentsContract.buildDocumentUriUsingTree(tree, docId);
  }

  /** 拒绝时删掉刚建的文档；删除失败不掩盖原始拒绝原因。 */
  private void deleteQuietly(Uri document) {
    try {
      DocumentsContract.deleteDocument(resolver, document);
    } catch (Throwable ignored) {
      // Provider 拒绝清理时也按失败返回，调用方不会拿到树外 URI。
    }
  }

  /** 委托 {@link SafPaths#mimeOf}。 */
  public static String mimeOf(String name) {
    return SafPaths.mimeOf(name);
  }

  /** 委托 {@link SafPaths#looksText}。 */
  public static boolean looksText(byte[] data) {
    return SafPaths.looksText(data);
  }
}
