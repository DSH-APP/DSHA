package com.deepseekharness.app.saf;

import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.UriPermission;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.pm.ProviderInfo;
import android.database.Cursor;
import android.net.Uri;
import android.os.OperationCanceledException;
import android.provider.DocumentsContract;
import androidx.core.provider.DocumentsContractCompat;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * 跨应用文件访问的授权簿。
 *
 * <p>系统 {@link ContentResolver#getPersistedUriPermissions()} 是唯一真相；本地偏好记四件事：
 * 哪些 tree 是本功能加的、每棵树是否允许助手写入、本功能新拿到的读/写位，以及授权时的
 * 应用名/目录名缓存。持久 URI 授权按「App + URI」记在系统里、不区分功能，所以撤销时只能
 * 释放本功能自己拿到的那部分；备份、日志导出等其它功能对同一目录已有的授权必须保留。
 *
 * <p>取授权前先写一条「待确认」记录，确认成功后再清掉；进程在 take 与 remember 之间被杀时，
 * 下次访问会按记录里的「原有位」把这次新拿到的位还回去。释放失败时记录会保留并在下次访问
 * 重试，不会留下系统不认识的持久授权占额度。
 *
 * <p>锁内只做 prefs / 系统券快照，不查询目标 Provider：label 在 add 时缓存，列表缺失时在
 * 锁外用带 CancellationSignal 与 15 秒截止的查询补一次。这样慢 Provider 不会在持有锁时
 * 卡住桥请求，也不会把界面主线程拖成 ANR。
 */
public final class SafGrants {
  /** 授权数量上限：足够覆盖日常几个应用，又不至于把页面和桥接列表撑爆。 */
  public static final int MAX_TREES = 8;

  private static final String PREFS = "saf_grants";
  private static final String KEY_URIS = "uris";
  private static final String KEY_WRITABLE = "writable:";

  /** 本功能为这个 tree 新拿到的位（"r"/"w"/"rw"）；系统里原本就有的位不记。 */
  static final String KEY_ACQUIRED = "acquired:";

  /** 授权时缓存的应用名 / 目录名：列表与桥只读缓存，缺失才在锁外补查。 */
  private static final String KEY_APP = "app:";

  static final String KEY_LABEL = "label:";

  /** 待确认的 tree（take 之前写入，remember 成功后删除）。 */
  static final String KEY_PENDING = "pending";

  /** 待确认记录：take 之前系统里已有的位与本次可能拿到的位，用于启动时只释放新增部分。 */
  static final String KEY_PENDING_BEFORE = "pending-before:";

  static final String KEY_PENDING_INTENT = "pending-intent:";
  static final int READ = 1;
  static final int WRITE = 2;

  /** 包内测试可缩短 label 查询截止时间。 */
  static long labelDeadlineMs = SafTree.DEADLINE_MS;

  private static final Object LOCK = new Object();

  private SafGrants() {}

  /** 一条已授权目录；{@code valid=false} 表示系统里的授权已经没了（应用卸载或用户在系统里撤销）。 */
  public static final class Grant {
    public final String id;
    public final Uri uri;
    public final String app;
    public final String label;

    /** 用户在本页允许助手写入（偏好开关），且系统里确实有写权限。 */
    public final boolean writable;

    /** 系统里确有写权限（与用户偏好无关）。 */
    public final boolean canWrite;

    /** 系统里确有读权限。 */
    public final boolean readable;

    /** 系统里读或写至少还剩一个。 */
    public final boolean valid;

    Grant(
        String id,
        Uri uri,
        String app,
        String label,
        boolean writable,
        boolean canWrite,
        boolean readable,
        boolean valid) {
      this.id = id;
      this.uri = uri;
      this.app = app;
      this.label = label;
      this.writable = writable;
      this.canWrite = canWrite;
      this.readable = readable;
      this.valid = valid;
    }

    /** 「应用 · 目录」；查不到时退回 URI 尾段，保证行上永远有可读的名字。 */
    public String summary() {
      String name = label == null || label.isEmpty() ? uri.getLastPathSegment() : label;
      return app == null || app.isEmpty() ? name : app + " · " + name;
    }
  }

  /** 申请持久授权的结局：{@code added=true} 时已登记，{@code failure} 为空。 */
  public static final class AddResult {
    public final boolean added;
    public final boolean readable;
    public final boolean writable;
    public final String failure;

    AddResult(boolean added, boolean readable, boolean writable, String failure) {
      this.added = added;
      this.readable = readable;
      this.writable = writable;
      this.failure = failure;
    }

    static AddResult failed(String failure) {
      return new AddResult(false, false, false, failure);
    }
  }

  /** 给桥接层用的稳定短 id：授权 URI 的摘要前 12 位，agent 只认 id、不认 authority。 */
  public static String id(Uri uri) {
    if (uri == null) return "";
    try {
      byte[] hash =
          MessageDigest.getInstance("SHA-256")
              .digest(uri.toString().getBytes(StandardCharsets.UTF_8));
      StringBuilder out = new StringBuilder(12);
      for (int i = 0; i < 6; i++) out.append(String.format(Locale.ROOT, "%02x", hash[i]));
      return out.toString();
    } catch (Exception unavailable) {
      return String.format(Locale.ROOT, "%08x", uri.toString().hashCode());
    }
  }

  /** 界面列表：允许在锁外为缺 label 的条目做一次带截止的补查。 */
  public static List<Grant> list(Context context) {
    return listInternal(context, true);
  }

  /** 桥接列表：只读缓存，绝不查询目标 Provider；慢 Provider 不会占住 http-shell worker。 */
  public static List<Grant> listLight(Context context) {
    return listInternal(context, false);
  }

  private static List<Grant> listInternal(Context context, boolean resolveMissingLabels) {
    List<UriPermission> persisted;
    Set<String> saved;
    SharedPreferences prefs = prefs(context);
    synchronized (LOCK) {
      cleanupPendingLocked(context);
      saved = new HashSet<>(prefs.getStringSet(KEY_URIS, new HashSet<>()));
      persisted = persisted(context);
    }
    List<Grant> grants = new ArrayList<>();
    for (String value : saved) {
      Uri uri;
      try {
        uri = Uri.parse(value);
      } catch (Throwable bad) {
        continue;
      }
      if (!documentsTree(uri)) continue;
      int flags = persisted == null ? 0 : flagsOf(persisted, uri);
      String app = prefs.getString(KEY_APP + uri, "");
      String label = prefs.getString(KEY_LABEL + uri, "");
      if (resolveMissingLabels && (app.isEmpty() || label.isEmpty())) {
        String freshApp = app.isEmpty() ? appLabel(context, uri) : app;
        String freshLabel = label.isEmpty() ? documentLabel(context, uri) : label;
        if (!freshApp.isEmpty() || !freshLabel.isEmpty()) {
          cacheLabels(prefs, uri, freshApp, freshLabel);
          if (app.isEmpty()) app = freshApp;
          if (label.isEmpty()) label = freshLabel;
        }
      }
      grants.add(grant(prefs, uri, flags, app, label));
    }
    grants.sort(Comparator.comparing(grant -> grant.summary().toLowerCase(Locale.ROOT)));
    return grants;
  }

  public static Grant find(Context context, String id) {
    if (id == null || id.isEmpty()) return null;
    for (Grant grant : list(context)) if (grant.id.equals(id)) return grant;
    return null;
  }

  /** 桥接层查找结果：区分「没有这条授权」与「系统授权状态不可读」。 */
  public static final class Lookup {
    public final Grant grant;
    public final String failure;

    Lookup(Grant grant, String failure) {
      this.grant = grant;
      this.failure = failure;
    }
  }

  /** 桥接层用的轻量查找：不查应用名与目录名（那要跨进程问 Provider），只验系统券还在。 */
  public static Lookup lookup(Context context, String id) {
    if (id == null || id.isEmpty()) return new Lookup(null, "NO_GRANT");
    SharedPreferences prefs = prefs(context);
    List<UriPermission> persisted;
    Set<String> saved;
    synchronized (LOCK) {
      cleanupPendingLocked(context);
      saved = new HashSet<>(prefs.getStringSet(KEY_URIS, new HashSet<>()));
      persisted = persisted(context);
    }
    if (persisted == null) return new Lookup(null, "PERMISSION_STATE_UNAVAILABLE");
    if (saved.isEmpty()) return new Lookup(null, "NO_GRANT");
    for (String value : saved) {
      Uri uri;
      try {
        uri = Uri.parse(value);
      } catch (Throwable bad) {
        continue;
      }
      if (!documentsTree(uri) || !id(uri).equals(id)) continue;
      return new Lookup(
          grant(
              prefs,
              uri,
              flagsOf(persisted, uri),
              prefs.getString(KEY_APP + uri, ""),
              prefs.getString(KEY_LABEL + uri, "")),
          null);
    }
    return new Lookup(null, "NO_GRANT");
  }

  /** 兼容旧调用：只取授权本身；需要区分系统状态时用 {@link #lookup}. */
  public static Grant pick(Context context, String id) {
    Lookup lookup = lookup(context, id);
    return lookup.grant;
  }

  /**
   * 取持久授权并登记。
   *
   * <p>先查系统里已有的位，只把<b>本次新拿到</b>的位记成本功能所有；数量满额、Provider 拒绝或
   * 进程中途死亡时都只释放这部分，备份等其它功能对同一 URI 的授权不受影响。
   */
  public static AddResult add(Context context, Uri tree, boolean wantWrite) {
    if (!documentsTree(tree)) return AddResult.failed("NOT_TREE");
    if (ownProvider(context, tree)) return AddResult.failed("OWN_PROVIDER");
    AddResult result;
    synchronized (LOCK) {
      cleanupPendingLocked(context);
      SharedPreferences prefs = prefs(context);
      Set<String> saved = new HashSet<>(prefs.getStringSet(KEY_URIS, new HashSet<>()));
      if (!saved.contains(tree.toString()) && saved.size() >= MAX_TREES)
        return AddResult.failed("LIMIT");
      ContentResolver resolver = context.getContentResolver();
      List<UriPermission> persisted = persisted(context);
      if (persisted == null) return AddResult.failed("PERMISSION_STATE_UNAVAILABLE");
      int before = flagsOf(persisted, tree);
      int intent = READ | (wantWrite ? WRITE : 0);
      // take 之前先把「原有位 + 本次可能新增的位」落盘：进程现在被杀也能在下次访问时清理。
      // 写盘失败时不能继续 take，否则会留下一张永远没人清理的系统授权。
      Set<String> pending = new HashSet<>(prefs.getStringSet(KEY_PENDING, new HashSet<>()));
      if (!prefs
          .edit()
          .putStringSet(KEY_PENDING, with(pending, tree.toString()))
          .putString(KEY_PENDING_BEFORE + tree, Integer.toString(before))
          .putString(KEY_PENDING_INTENT + tree, Integer.toString(intent))
          .commit()) return AddResult.failed("STATE_UNAVAILABLE");
      int taken = takePersistable(resolver, tree, wantWrite);
      if (taken == 0) {
        List<UriPermission> now = persistedList(resolver);
        taken = now == null ? 0 : flagsOf(now, tree);
      }
      if (taken == 0) {
        clearPendingLocked(prefs, tree);
        return AddResult.failed("NO_PERMISSION");
      }
      int acquired = (taken & ~before) | parse(prefs.getString(KEY_ACQUIRED + tree, ""));
      if ((taken & READ) == 0) {
        // 路径解析依赖子项 query；只给写权限的树无法 push/mkdir/rm，添加时直接拒绝。
        int rollback = taken & ~before & intent;
        if (releaseFlags(resolver, tree, rollback)) {
          clearPendingLocked(prefs, tree);
          return AddResult.failed("WRITE_ONLY");
        }
        // 系统拒绝释放时保留 pending 与归属记录，下次访问继续重试，避免留下无记录写授权。
        prefs
            .edit()
            .putString(KEY_ACQUIRED + tree, flags(rollback))
            .putString(KEY_PENDING_BEFORE + tree, Integer.toString(before))
            .putString(KEY_PENDING_INTENT + tree, Integer.toString(intent))
            .commit();
        return AddResult.failed("WRITE_ONLY_PENDING");
      }
      boolean writable = (taken & WRITE) != 0;
      saved.add(tree.toString());
      boolean committed =
          prefs
              .edit()
              .putStringSet(KEY_URIS, saved)
              .putBoolean(KEY_WRITABLE + tree, writable)
              .putString(KEY_ACQUIRED + tree, flags(acquired))
              .putStringSet(KEY_PENDING, without(pending, tree.toString()))
              .remove(KEY_PENDING_BEFORE + tree)
              .remove(KEY_PENDING_INTENT + tree)
              .commit();
      if (!committed) {
        // 待确认记录仍在盘上；本次新拿到的位由下次访问按记录清理，不要假装登记成功。
        return AddResult.failed("STATE_UNAVAILABLE");
      }
      result = new AddResult(true, (taken & READ) != 0, writable, null);
    }
    // Provider 查询（目录名）必须在锁外做；界面在后台线程调用 add。
    cacheLabels(prefs(context), tree, appLabel(context, tree), documentLabel(context, tree));
    return result;
  }

  /**
   * 撤销授权：只退本功能新拿到的位，再删本地记录；系统券已不在时也把本地记录清掉。
   * 释放失败时保留 acquired 与待确认记录，下次访问继续重试。
   */
  public static void forget(Context context, Uri tree) {
    if (tree == null) return;
    synchronized (LOCK) {
      cleanupPendingLocked(context);
      SharedPreferences prefs = prefs(context);
      int acquired = parse(prefs.getString(KEY_ACQUIRED + tree, ""));
      boolean released = releaseFlags(context.getContentResolver(), tree, acquired);
      Set<String> saved = new HashSet<>(prefs.getStringSet(KEY_URIS, new HashSet<>()));
      saved.remove(tree.toString());
      Set<String> pending = new HashSet<>(prefs.getStringSet(KEY_PENDING, new HashSet<>()));
      SharedPreferences.Editor editor =
          prefs
              .edit()
              .putStringSet(KEY_URIS, saved)
              .remove(KEY_WRITABLE + tree)
              .remove(KEY_APP + tree)
              .remove(KEY_LABEL + tree);
      if (!released && acquired != 0) {
        // 系统拒绝了释放：保留归属记录与待确认项，下次打开页面或调用桥时重试。
        editor
            .putStringSet(KEY_PENDING, with(pending, tree.toString()))
            .putString(KEY_PENDING_BEFORE + tree, "0")
            .putString(KEY_PENDING_INTENT + tree, Integer.toString(acquired));
      } else {
        editor
            .remove(KEY_ACQUIRED + tree)
            .putStringSet(KEY_PENDING, without(pending, tree.toString()))
            .remove(KEY_PENDING_BEFORE + tree)
            .remove(KEY_PENDING_INTENT + tree);
      }
      editor.commit();
    }
  }

  public static void setWritable(Context context, Uri tree, boolean writable) {
    if (tree == null) return;
    prefs(context).edit().putBoolean(KEY_WRITABLE + tree, writable).apply();
  }

  /** 选到 DSHA 自己的 documents 根会形成自环：读取方和被读方是同一个进程。 */
  public static boolean ownProvider(Context context, Uri tree) {
    return tree != null && (context.getPackageName() + ".documents").equals(tree.getAuthority());
  }

  public static boolean documentsTree(Uri uri) {
    return uri != null
        && "content".equals(uri.getScheme())
        && DocumentsContractCompat.isTreeUri(uri);
  }

  // ---------------------------------------------------------------- 授权位与待确认记录

  /**
   * 只释放本功能新拿到的位。{@code flags} 按位组合；某些系统只允许按单一类型释放，
   * 因此组合失败后逐个重试（只写授权也必须在第二次释放成功）。
   *
   * @return 所有请求的位都已释放（或本来就不需要释放）
   */
  static boolean releaseFlags(ContentResolver resolver, Uri tree, int flags) {
    if (resolver == null || tree == null || flags == 0) return true;
    if (strictRelease(resolver, tree, flags)) return true;
    boolean released = true;
    if ((flags & READ) != 0) released &= strictRelease(resolver, tree, READ);
    if ((flags & WRITE) != 0) released &= strictRelease(resolver, tree, WRITE);
    return released;
  }

  private static boolean strictRelease(ContentResolver resolver, Uri tree, int flags) {
    try {
      resolver.releasePersistableUriPermission(tree, intentFlags(flags));
      return true;
    } catch (Throwable rejected) {
      return false;
    }
  }

  /** 依次尝试「读写一起 → 只要读 → 只要写」，返回 take 之后系统里实际存在的位。 */
  private static int takePersistable(ContentResolver resolver, Uri tree, boolean wantWrite) {
    int[] attempts = wantWrite ? new int[] {READ | WRITE, READ, WRITE} : new int[] {READ};
    for (int attempt : attempts) {
      try {
        resolver.takePersistableUriPermission(tree, intentFlags(attempt));
      } catch (Throwable rejected) {
        continue;
      }
      List<UriPermission> persisted = persistedList(resolver);
      return persisted == null ? attempt : flagsOf(persisted, tree);
    }
    return 0;
  }

  /**
   * 清理进程在 take 与 remember 之间死亡、或释放失败留下的待确认授权：只释放「当前有、
   * take 之前没有、且本次尝试可能拿到」的位，其它功能原有的位保持不动。释放失败的记录
   * 保留到下次访问继续重试。
   */
  public static void cleanupPending(Context context) {
    synchronized (LOCK) {
      cleanupPendingLocked(context);
    }
  }

  private static void cleanupPendingLocked(Context context) {
    SharedPreferences prefs = prefs(context);
    Set<String> pending = new HashSet<>(prefs.getStringSet(KEY_PENDING, new HashSet<>()));
    if (pending.isEmpty()) return;
    List<UriPermission> persisted = persisted(context);
    // 系统状态不可读时先保留记录，下次还能安全重试；宁占一条记录也不误撤别人的授权。
    if (persisted == null) return;
    ContentResolver resolver = context.getContentResolver();
    Set<String> saved = new HashSet<>(prefs.getStringSet(KEY_URIS, new HashSet<>()));
    for (String value : new ArrayList<>(pending)) {
      Uri uri;
      try {
        uri = Uri.parse(value);
      } catch (Throwable bad) {
        pending.remove(value);
        prefs.edit().putStringSet(KEY_PENDING, pending).commit();
        continue;
      }
      // 待确认记录里存的是位掩码的十进制（不是 acquired 的 "r"/"w" 缩写）。
      int before = number(prefs.getString(KEY_PENDING_BEFORE + uri, "0"), 0);
      int intent = number(prefs.getString(KEY_PENDING_INTENT + uri, "1"), READ);
      int release = flagsOf(persisted, uri) & ~before & intent;
      if (!releaseFlags(resolver, uri, release)) continue; // 保留记录，下次重试
      pending.remove(value);
      SharedPreferences.Editor editor =
          prefs
              .edit()
              .putStringSet(KEY_PENDING, pending)
              .remove(KEY_PENDING_BEFORE + uri)
              .remove(KEY_PENDING_INTENT + uri);
      if (!saved.contains(value)) editor.remove(KEY_ACQUIRED + uri);
      editor.commit();
    }
  }

  private static void clearPendingLocked(SharedPreferences prefs, Uri tree) {
    Set<String> pending = new HashSet<>(prefs.getStringSet(KEY_PENDING, new HashSet<>()));
    prefs
        .edit()
        .putStringSet(KEY_PENDING, without(pending, tree.toString()))
        .remove(KEY_PENDING_BEFORE + tree)
        .remove(KEY_PENDING_INTENT + tree)
        .commit();
  }

  private static Set<String> with(Set<String> values, String value) {
    Set<String> copy = new HashSet<>(values);
    copy.add(value);
    return copy;
  }

  private static Set<String> without(Set<String> values, String value) {
    Set<String> copy = new HashSet<>(values);
    copy.remove(value);
    return copy;
  }

  private static String flags(int flags) {
    StringBuilder out = new StringBuilder();
    if ((flags & READ) != 0) out.append('r');
    if ((flags & WRITE) != 0) out.append('w');
    return out.toString();
  }

  private static int parse(String flags) {
    int value = 0;
    if (flags != null) {
      if (flags.indexOf('r') >= 0) value |= READ;
      if (flags.indexOf('w') >= 0) value |= WRITE;
    }
    return value;
  }

  /** 待确认记录里的位掩码是十进制；坏数据按 fallback 处理，不误释放。 */
  private static int number(String value, int fallback) {
    try {
      return Integer.parseInt(value.trim());
    } catch (Throwable bad) {
      return fallback;
    }
  }

  private static int intentFlags(int flags) {
    int value = 0;
    if ((flags & READ) != 0) value |= Intent.FLAG_GRANT_READ_URI_PERMISSION;
    if ((flags & WRITE) != 0) value |= Intent.FLAG_GRANT_WRITE_URI_PERMISSION;
    return value;
  }

  private static int flagsOf(List<UriPermission> persisted, Uri uri) {
    int flags = 0;
    for (UriPermission permission : persisted) {
      if (!uri.equals(permission.getUri())) continue;
      if (permission.isReadPermission()) flags |= READ;
      if (permission.isWritePermission()) flags |= WRITE;
    }
    return flags;
  }

  private static Grant grant(
      SharedPreferences prefs, Uri uri, int flags, String app, String label) {
    boolean canWrite = (flags & WRITE) != 0;
    boolean readable = (flags & READ) != 0;
    return new Grant(
        id(uri),
        uri,
        app,
        label,
        canWrite && prefs.getBoolean(KEY_WRITABLE + uri, true),
        canWrite,
        readable,
        canWrite || readable);
  }

  private static List<UriPermission> persisted(Context context) {
    try {
      return context.getContentResolver().getPersistedUriPermissions();
    } catch (Throwable unavailable) {
      // 取不到系统授权时按「未知」处理；调用方不得据此释放授权，避免误伤其它功能。
      return null;
    }
  }

  /** 供 take 之后复核用：与 {@link #persisted(Context)} 不同，失败按「未知」返回 null。 */
  private static List<UriPermission> persistedList(ContentResolver resolver) {
    try {
      return resolver.getPersistedUriPermissions();
    } catch (Throwable unavailable) {
      return null;
    }
  }

  private static SharedPreferences prefs(Context context) {
    return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
  }

  private static void cacheLabels(SharedPreferences prefs, Uri uri, String app, String label) {
    SharedPreferences.Editor editor = prefs.edit();
    boolean changed = false;
    if (app != null && !app.isEmpty()) {
      editor.putString(KEY_APP + uri, app);
      changed = true;
    }
    if (label != null && !label.isEmpty()) {
      editor.putString(KEY_LABEL + uri, label);
      changed = true;
    }
    if (changed) editor.apply();
  }

  /** 授权的 Provider 属于哪个应用：MT 注入的 Provider 跑在目标应用里，所以这里显示的就是目标应用名。 */
  private static String appLabel(Context context, Uri uri) {
    try {
      PackageManager manager = context.getPackageManager();
      ProviderInfo provider = manager.resolveContentProvider(uri.getAuthority(), 0);
      if (provider != null && provider.applicationInfo != null)
        return String.valueOf(manager.getApplicationLabel(provider.applicationInfo));
      // 没有 DOCUMENTS_PROVIDER intent 声明时 resolveContentProvider 可能看不到对方；
      // 退回按 action 查询（只用于显示名，不改变授权范围）。
      android.content.Intent documents =
          new android.content.Intent("android.content.action.DOCUMENTS_PROVIDER");
      for (android.content.pm.ResolveInfo resolved :
          manager.queryIntentContentProviders(documents, 0))
        if (resolved.providerInfo != null
            && uri.getAuthority().equals(resolved.providerInfo.authority)
            && resolved.providerInfo.applicationInfo != null)
          return String.valueOf(manager.getApplicationLabel(resolved.providerInfo.applicationInfo));
    } catch (Throwable ignored) {
      return "";
    }
    return "";
  }

  /**
   * 目录名：目标 Provider 的查询带 CancellationSignal 与截止时间。响应取消的 Provider
   * 会在截止时返回空串；若 Provider 在 binder 内完全忽略取消，调用线程仍可能等到它返回——
   * 因此本查询只发生在 add/list 的后台线程，桥侧 listLight 从不查询 Provider。
   */
  static String documentLabel(Context context, Uri uri) {
    SafTree.Deadline deadline = SafTree.newDeadline(labelDeadlineMs);
    try {
      String docId = DocumentsContract.getTreeDocumentId(uri);
      Uri document = DocumentsContract.buildDocumentUriUsingTree(uri, docId);
      try (Cursor cursor =
          context
              .getContentResolver()
              .query(
                  document,
                  new String[] {DocumentsContract.Document.COLUMN_DISPLAY_NAME},
                  null,
                  null,
                  null,
                  deadline.signal)) {
        deadline.check();
        if (cursor != null && cursor.moveToFirst() && !cursor.isNull(0)) return cursor.getString(0);
      }
    } catch (OperationCanceledException canceled) {
      return "";
    } catch (Throwable ignored) {
      // 目标应用不在（或进程没起来）时退回到 URI 尾段。
    } finally {
      deadline.close();
    }
    return "";
  }
}
