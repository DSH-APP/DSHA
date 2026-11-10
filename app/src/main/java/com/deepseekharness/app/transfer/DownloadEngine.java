package com.deepseekharness.app.transfer;

import static com.deepseekharness.app.transfer.DownloadTask.Status;

import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.SystemClock;
import android.provider.MediaStore;
import androidx.core.content.ContextCompat;
import androidx.lifecycle.LiveData;
import androidx.lifecycle.MutableLiveData;
import com.deepseekharness.app.backup.BackupControl;
import com.deepseekharness.app.core.HarnessController;
import com.deepseekharness.app.util.FileIntegrity;
import com.deepseekharness.app.util.PreviewPageSession;
import com.deepseekharness.app.util.WebPreviewPolicy;
import java.io.*;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.json.JSONArray;
import org.json.JSONObject;

/** Application-owned, serial, restart-safe file downloads. Nothing here owns a Web page. */
public final class DownloadEngine {
  public static final class State {
    public final List<DownloadTask> tasks;
    public final boolean busy;
    public final String activeId;

    State(List<DownloadTask> tasks, boolean busy, String activeId) {
      this.tasks = Collections.unmodifiableList(tasks);
      this.busy = busy;
      this.activeId = activeId;
    }
  }

  private static DownloadEngine instance;

  public static synchronized DownloadEngine get(Context context) {
    if (instance == null) instance = new DownloadEngine(context.getApplicationContext());
    return instance;
  }

  private final Context context;
  private final File privateFiles;
  private final File root;
  private final Object gate = new Object();
  private final LinkedHashMap<String, Record> records = new LinkedHashMap<>();
  private final LinkedHashSet<String> pendingReleases = new LinkedHashSet<>();
  private final ExecutorService worker = Executors.newSingleThreadExecutor();
  private final ExecutorService releaseWorker = Executors.newSingleThreadExecutor();
  private final MutableLiveData<State> state = new MutableLiveData<>();
  private String activeId = "";
  private boolean draining;
  private boolean releasing;
  private long admissionGeneration;
  private BackupControl activeControl;
  private volatile HttpURLConnection activeConnection;

  private static final class Record {
    String id, destination, message = "", uri = "", displayName, location = "", mime;
    String pendingUri = "", pendingKind = "", targetUri = "";
    TransferArtifact artifact;
    Status status = Status.QUEUED;
    long downloaded, speed, createdAt = System.currentTimeMillis();
    boolean local;

    DownloadTask snapshot() {
      return new DownloadTask(
          id,
          artifact,
          destination,
          status,
          downloaded,
          speed,
          message,
          uri,
          displayName,
          location,
          mime,
          createdAt);
    }
  }

  private DownloadEngine(Context context) {
    this.context = context;
    File trusted;
    try {
      trusted = context.getFilesDir().getCanonicalFile();
    } catch (IOException failure) {
      trusted = context.getFilesDir().getAbsoluteFile();
    }
    privateFiles = trusted;
    root = new File(privateFiles, "transfers");
    try {
      TransferFiles.directory(privateFiles, "transfers");
    } catch (IOException failure) {
      /* enqueue will report the controlled storage error */
    }
    restore();
    restoreReleases();
    HarnessController.get(context).addReadyWebPageListener(this::flushReleases);
    emit();
  }

  public LiveData<State> state() {
    return state;
  }

  public boolean isBusy() {
    synchronized (gate) {
      return busy();
    }
  }

  public void onRuntimeAvailable() {
    flushReleases();
  }

  public String enqueue(TransferArtifact artifact, String destination) throws Exception {
    Objects.requireNonNull(artifact);
    Record record = create(artifact, destination, false);
    long ticket;
    synchronized (gate) {
      if (records.size() >= 200) throw new IOException("FILE_TASK_LIMIT");
      for (Record other : records.values())
        if (other.artifact.id.equals(artifact.id)) throw new IOException("FILE_DUPLICATE_ARTIFACT");
      save(record);
      records.put(record.id, record);
      ticket = ++admissionGeneration;
      emit();
    }
    startService(ticket);
    return record.id;
  }

  /** Called on the Blob worker: ownership moves only after a full private copy and SHA. */
  public String enqueueLocal(File source, String name, String mime, String destination)
      throws Exception {
    File allowed =
        TransferFiles.FS.child(context.getCacheDir().getCanonicalFile(), "web-downloads");
    File canonical = source.getCanonicalFile();
    if (!new File(allowed, source.getName()).equals(canonical)
        || !allowed.equals(canonical.getParentFile())
        || !TransferFiles.FS.stat(allowed).type.equals("DIRECTORY")
        || !TransferFiles.FS.stat(canonical).type.equals("FILE"))
      throw new IOException("FILE_LOCAL_SOURCE_REJECTED");
    long size = canonical.length();
    if (size < 0 || size > TransferArtifact.MAX_BYTES || !TransferArtifact.safeName(name))
      throw new IOException("FILE_LIMIT_5GB");
    String id = UUID.randomUUID().toString();
    File dir = ensureTaskDir(id);
    if (root.getUsableSpace() < TransferProtocol.requiredSpace(size, 0, true))
      throw new IOException("FILE_SPACE_INSUFFICIENT");
    File partial = new File(dir, "partial");
    FileIntegrity.Result integrity;
    try (InputStream in = TransferFiles.read(canonical);
        OutputStream out = TransferFiles.write(partial, 0)) {
      integrity = FileIntegrity.copy(in, out, size);
    }
    if (integrity.size != size || canonical.length() != size)
      throw new IOException("FILE_LOCAL_SOURCE_CHANGED");
    TransferArtifact artifact =
        new TransferArtifact(
            "local-" + id,
            name,
            mime == null || mime.isBlank() ? "application/octet-stream" : mime,
            size,
            integrity.sha256,
            "local-" + integrity.sha256);
    Record record = create(artifact, destination, true);
    record.id = id;
    record.downloaded = size;
    long ticket;
    synchronized (gate) {
      if (records.size() >= 200) throw new IOException("FILE_TASK_LIMIT");
      save(record);
      records.put(id, record);
      ticket = ++admissionGeneration;
      emit();
    }
    startService(ticket);
    return id;
  }

  private Record create(TransferArtifact artifact, String destination, boolean local)
      throws IOException {
    if (!Set.of("downloads", "gallery", "saveas", "share").contains(destination))
      throw new IOException("FILE_DESTINATION_INVALID");
    Record record = new Record();
    record.id = UUID.randomUUID().toString();
    record.artifact = artifact;
    record.displayName = artifact.name;
    record.mime = artifact.mime;
    record.destination = destination;
    record.message = "等待下载";
    record.local = local;
    return record;
  }

  public void resume(String id) throws Exception {
    long ticket;
    synchronized (gate) {
      Record record = require(id);
      if (record.status == Status.CANCELLED) throw new IOException("FILE_TASK_CANCELLED");
      if (record.status == Status.COMPLETE || running(record.status) || record.id.equals(activeId))
        return;
      if (record.status == Status.WAITING_DESTINATION && record.targetUri.isEmpty()) return;
      record.status = Status.QUEUED;
      record.message = "等待继续";
      save(record);
      ticket = ++admissionGeneration;
      emit();
    }
    startService(ticket);
  }

  /** Only a newly created ACTION_CREATE_DOCUMENT URI may be supplied here. */
  public void provideSaveTarget(String id, Uri uri) throws Exception {
    if (uri == null || !"content".equals(uri.getScheme()))
      throw new IOException("FILE_SAVE_TARGET_INVALID");
    long ticket;
    synchronized (gate) {
      Record record = require(id);
      if (!record.destination.equals("saveas") || record.status != Status.WAITING_DESTINATION)
        throw new IOException("FILE_SAVE_TARGET_STATE");
      record.targetUri = uri.toString();
      record.status = Status.QUEUED;
      record.message = "等待另存为";
      save(record);
      ticket = ++admissionGeneration;
      emit();
    }
    startService(ticket);
  }

  public void pause(String id) {
    stop(id, false, "已暂停，下载进度已保留");
  }

  public void cancel(String id) {
    stop(id, true, "已取消。需要再次下载请从网页重新发起");
  }

  private void stop(String id, boolean cancelled, String message) {
    synchronized (gate) {
      Record record = records.get(id);
      if (record == null || record.status == Status.COMPLETE || record.status == Status.CANCELLED)
        return;
      record.status = cancelled ? Status.CANCELLED : Status.PAUSED;
      record.message = message;
      record.speed = 0;
      if (id.equals(activeId) && activeControl != null) activeControl.cancel();
      saveQuietly(record);
      emit();
      if (cancelled) releaseWhenUnreferenced(record.artifact);
    }
  }

  public void pauseAll(String reason) {
    synchronized (gate) {
      for (Record record : records.values()) {
        if (!running(record.status)) continue;
        record.status = Status.PAUSED;
        record.speed = 0;
        record.message = reason;
        saveQuietly(record);
      }
      if (activeControl != null) activeControl.cancel();
      emit();
    }
  }

  /** Never delete a published destination, including a share URI. */
  public void remove(String id) throws Exception {
    synchronized (gate) {
      Record record = require(id);
      if (id.equals(activeId) || running(record.status)) throw new IOException("FILE_TASK_BUSY");
      // Persist retirement before removing the only task journal that also carries this intent.
      if (!record.local) {
        pendingReleases.add(record.artifact.id);
        saveReleasesRequired();
        flushReleases();
      }
      cleanupPending(record);
      File partial = partial(record);
      if (!TransferFiles.FS.stat(partial).type.equals("MISSING")) TransferFiles.FS.delete(partial);
      File staged = new File(taskDir(id), "share-pending");
      if (!TransferFiles.FS.stat(staged).type.equals("MISSING")) TransferFiles.FS.delete(staged);
      for (String suffix : List.of("", ".previous")) {
        File file = new File(taskDir(id), "task.json" + suffix);
        if (!TransferFiles.FS.stat(file).type.equals("MISSING")) TransferFiles.FS.delete(file);
      }
      records.remove(id);
      taskDir(id).delete();
      emit();
    }
  }

  public JSONObject snapshot(String taskId) throws Exception {
    synchronized (gate) {
      if (taskId != null && !taskId.isEmpty()) return json(require(taskId));
      JSONArray list = new JSONArray();
      for (Record record : records.values()) list.put(json(record));
      return new JSONObject().put("tasks", list).put("busy", busy());
    }
  }

  private JSONObject json(Record record) throws Exception {
    return new JSONObject()
        .put("id", record.id)
        .put("artifact", record.artifact.toJson())
        .put("destination", record.destination)
        .put("status", record.status.name())
        .put("downloaded", record.downloaded)
        .put("total", record.artifact.bytes)
        .put("speedBytesPerSecond", record.speed)
        .put("message", record.message)
        .put("uri", record.uri)
        .put("displayName", record.displayName)
        .put("location", record.location)
        .put("mime", record.mime)
        .put("createdAt", record.createdAt);
  }

  private void startService(long ticket) {
    flushReleases();
    try {
      ContextCompat.startForegroundService(context, new Intent(context, DownloadService.class));
    } catch (RuntimeException denied) {
      serviceGone(ticket, "下载服务未能启动，请返回文件页继续");
    }
  }

  /** Called only after the independent dataSync service has entered foreground. */
  public long runQueued() {
    long ticket;
    synchronized (gate) {
      ticket = admissionGeneration;
      if (draining) return ticket;
      draining = true;
    }
    worker.execute(this::drain);
    return ticket;
  }

  public void serviceGone(long ownedAdmission, String reason) {
    synchronized (gate) {
      // A retiring Service must not pause a newer user admission awaiting onStartCommand.
      if (ownedAdmission > 0 && ownedAdmission == admissionGeneration) pauseAll(reason);
    }
  }

  private void drain() {
    while (true) {
      Record record;
      BackupControl control;
      synchronized (gate) {
        record =
            records.values().stream()
                .filter(r -> r.status == Status.QUEUED)
                .findFirst()
                .orElse(null);
        if (record == null) {
          draining = false;
          activeId = "";
          activeControl = null;
          emit();
          return;
        }
        activeId = record.id;
        activeControl = control = new BackupControl(null);
        record.status = Status.DOWNLOADING;
        record.message = "正在准备下载";
        saveQuietly(record);
        emit();
      }
      try {
        execute(record, control);
      } catch (Exception error) {
        synchronized (gate) {
          if (record.status != Status.COMPLETE
              && record.status != Status.CANCELLED
              && record.status != Status.PAUSED) {
            record.status = Status.FAILED;
            record.message = safeError(error);
          }
          record.speed = 0;
          try {
            cleanupPending(record);
          } catch (Exception ignored) {
          }
          saveQuietly(record);
        }
      } finally {
        synchronized (gate) {
          activeConnection = null;
          activeId = "";
          activeControl = null;
          emit();
        }
      }
    }
  }

  private void execute(Record record, BackupControl control) throws Exception {
    ensureTaskDir(record.id);
    File partial = partial(record);
    if (recoverPublication(record, control)) return;
    cleanupPending(record);
    long offset = TransferFiles.size(partial);
    if (offset > record.artifact.bytes) throw new IOException("FILE_PARTIAL_INVALID");
    if (root.getUsableSpace() < TransferProtocol.requiredSpace(record.artifact.bytes, offset, true))
      throw new IOException("FILE_SPACE_INSUFFICIENT");
    if (offset < record.artifact.bytes) {
      if (record.local) throw new IOException("FILE_LOCAL_COPY_MISSING");
      RuntimeSource source = currentSource(control);
      stage(record, Status.DOWNLOADING, "正在核验源文件，较大文件可能需要数分钟", control);
      TransferArtifact resolved = resolve(source, record.artifact, control);
      record.artifact.requireSame(resolved);
      stage(record, Status.DOWNLOADING, "源文件已核验，正在连接下载", control);
      download(source, record, partial, offset, control);
    }
    if (record.artifact.bytes == 0 && TransferFiles.FS.stat(partial).type.equals("MISSING"))
      try (OutputStream empty = TransferFiles.write(partial, 0)) {
        empty.flush();
      }
    stage(record, Status.VERIFYING, "正在校验完整文件", control);
    try (InputStream in = TransferFiles.read(partial)) {
      TransferPublisher.require(
          record.artifact, FileIntegrity.copy(in, null, record.artifact.bytes, control::check));
    }
    if (record.destination.equals("saveas") && record.targetUri.isEmpty()) {
      synchronized (gate) {
        control.check();
        record.downloaded = record.artifact.bytes;
        record.status = Status.WAITING_DESTINATION;
        record.message = "下载已校验，请选择保存位置";
        save(record);
      }
      return;
    }
    stage(record, Status.PUBLISHING, "正在写入目标并读回校验", control);
    TransferPublisher.publish(
        context,
        partial,
        record.artifact,
        record.destination,
        record.id,
        record.targetUri.isEmpty() ? null : Uri.parse(record.targetUri),
        control,
        gate,
        new TransferPublisher.Journal() {
          public void pending(Uri uri, String kind) throws Exception {
            synchronized (gate) {
              control.check();
              record.pendingUri = uri.toString();
              record.pendingKind = kind;
              save(record);
            }
          }

          public void complete(TransferPublisher.Result result) throws Exception {
            finishPublication(record, result);
          }
        });
  }

  private record RuntimeSource(String base, String cookie, PreviewPageSession.Identity identity) {}

  private RuntimeSource currentSource(BackupControl control) throws IOException {
    control.check();
    HarnessController harness = HarnessController.get(context);
    PreviewPageSession.Identity identity = harness.getReadyWebPageIdentity();
    if (identity == null) throw new IOException("FILE_RUNTIME_NOT_READY");
    String base = WebPreviewPolicy.loopbackBaseUrl(identity.authUrl());
    if (base == null) throw new IOException("FILE_RUNTIME_NOT_READY");
    String cookie = harness.exchangeDshAuthCookie();
    if (cookie == null
        || cookie.isEmpty()
        || cookie.indexOf('\r') >= 0
        || cookie.indexOf('\n') >= 0) throw new IOException("FILE_AUTH_REQUIRED");
    RuntimeSource source = new RuntimeSource(base, cookie, identity);
    checkSource(source, control);
    return source;
  }

  private void checkSource(RuntimeSource source, BackupControl control) throws IOException {
    control.check();
    if (!source.identity().equals(HarnessController.get(context).getReadyWebPageIdentity()))
      throw new IOException("FILE_RUNTIME_CHANGED");
  }

  private HttpURLConnection connect(RuntimeSource source, String path, BackupControl control)
      throws Exception {
    checkSource(source, control);
    HttpURLConnection connection =
        (HttpURLConnection) new URL(source.base() + path).openConnection();
    connection.setInstanceFollowRedirects(false);
    connection.setConnectTimeout(TransferProtocol.CONNECT_TIMEOUT_MS);
    connection.setReadTimeout(TransferProtocol.CONTENT_READ_TIMEOUT_MS);
    connection.setRequestProperty("Cookie", source.cookie());
    connection.setRequestProperty("Accept-Encoding", "identity");
    connection.setRequestProperty("Origin", source.base().substring(0, source.base().length() - 1));
    activeConnection = connection;
    return connection;
  }

  private TransferArtifact resolve(
      RuntimeSource source, TransferArtifact artifact, BackupControl control) throws Exception {
    HttpURLConnection connection = connect(source, "api/dsha/files/resolve", control);
    // resolve rereads the source SHA before replying; 5 GB may exceed the content idle timeout.
    connection.setReadTimeout(TransferProtocol.SOURCE_VERIFY_READ_TIMEOUT_MS);
    try (AutoCloseable cancel = control.onCancel(() -> disconnectAsync(connection))) {
      connection.setRequestMethod("POST");
      connection.setDoOutput(true);
      byte[] data =
          new JSONObject()
              .put("id", artifact.id)
              .put("sha256", artifact.sha256)
              .put("bytes", artifact.bytes)
              .toString()
              .getBytes(StandardCharsets.UTF_8);
      connection.setRequestProperty("Content-Type", "application/json");
      connection.setFixedLengthStreamingMode(data.length);
      try (OutputStream out = connection.getOutputStream()) {
        out.write(data);
      }
      if (connection.getResponseCode() != 200)
        throw new IOException("FILE_HTTP_" + connection.getResponseCode());
      ByteArrayOutputStream response = new ByteArrayOutputStream();
      try (InputStream in = connection.getInputStream()) {
        byte[] buffer = new byte[4096];
        int n;
        while ((n = in.read(buffer)) >= 0) {
          checkSource(source, control);
          if (response.size() + n > 16_384) throw new IOException("FILE_MANIFEST_TOO_LARGE");
          response.write(buffer, 0, n);
        }
      }
      checkSource(source, control);
      JSONObject value = new JSONObject(response.toString(StandardCharsets.UTF_8.name()));
      // resolve also returns a relative contentUrl; never use that value to choose a host.
      value.remove("contentUrl");
      return TransferArtifact.fromJson(value);
    } finally {
      connection.disconnect();
      activeConnection = null;
    }
  }

  private void download(
      RuntimeSource source, Record record, File partial, long requested, BackupControl control)
      throws Exception {
    HttpURLConnection connection =
        connect(source, "api/dsha/files/content?id=" + Uri.encode(record.artifact.id), control);
    connection.setRequestProperty("Range", "bytes=" + requested + "-");
    connection.setRequestProperty("If-Range", '"' + record.artifact.sha256 + '"');
    try (AutoCloseable cancel = control.onCancel(() -> disconnectAsync(connection))) {
      long offset =
          TransferProtocol.responseOffset(
              connection.getResponseCode(),
              requested,
              record.artifact.bytes,
              connection.getHeaderField("Content-Range"),
              connection.getHeaderField("Content-Length"),
              connection.getHeaderField("ETag"),
              record.artifact.sha256);
      long last = SystemClock.elapsedRealtime(), lastBytes = offset;
      try (InputStream in = connection.getInputStream();
          OutputStream out = TransferFiles.write(partial, offset)) {
        byte[] buffer = new byte[65_536];
        int n;
        try {
          while ((n = in.read(buffer)) >= 0) {
            checkSource(source, control);
            if (n > record.artifact.bytes - offset) throw new IOException("FILE_LENGTH_INVALID");
            out.write(buffer, 0, n);
            offset += n;
            long now = SystemClock.elapsedRealtime();
            if (now - last >= 500 || offset == record.artifact.bytes) {
              synchronized (gate) {
                control.check();
                record.downloaded = offset;
                record.speed = (offset - lastBytes) * 1000 / Math.max(1, now - last);
                record.message = "正在下载";
                save(record);
                emit();
              }
              last = now;
              lastBytes = offset;
            }
          }
        } finally {
          out.flush();
        }
      }
      if (offset != record.artifact.bytes) throw new IOException("FILE_TRANSFER_INTERRUPTED");
      checkSource(source, control);
    } finally {
      connection.disconnect();
      activeConnection = null;
    }
  }

  private void stage(Record record, Status status, String message, BackupControl control)
      throws Exception {
    synchronized (gate) {
      control.check();
      record.status = status;
      record.message = message;
      record.speed = 0;
      save(record);
      emit();
    }
  }

  private static boolean running(Status status) {
    return status == Status.QUEUED
        || status == Status.DOWNLOADING
        || status == Status.VERIFYING
        || status == Status.PUBLISHING;
  }

  private boolean busy() {
    return !activeId.isEmpty() || records.values().stream().anyMatch(r -> running(r.status));
  }

  private void emit() {
    synchronized (gate) {
      List<DownloadTask> tasks = new ArrayList<>();
      for (Record record : records.values()) tasks.add(record.snapshot());
      Collections.reverse(tasks);
      state.postValue(new State(tasks, busy(), activeId));
    }
  }

  private Record require(String id) throws IOException {
    Record record = records.get(id);
    if (record == null) throw new IOException("FILE_TASK_NOT_FOUND");
    return record;
  }

  private File taskDir(String id) throws IOException {
    if (id == null || !id.matches("[a-f0-9-]{36}")) throw new IOException("FILE_TASK_ID");
    return new File(root, id);
  }

  private File ensureTaskDir(String id) throws IOException {
    taskDir(id);
    TransferFiles.directory(privateFiles, "transfers");
    return TransferFiles.directory(root, id);
  }

  private File partial(Record record) throws IOException {
    return new File(taskDir(record.id), "partial");
  }

  private File journal(String id) throws IOException {
    return new File(taskDir(id), "task.json");
  }

  private void save(Record record) throws Exception {
    File dir = ensureTaskDir(record.id);
    JSONObject value =
        json(record)
            .put("local", record.local)
            .put("pendingUri", record.pendingUri)
            .put("pendingKind", record.pendingKind)
            .put("targetUri", record.targetUri);
    TransferFiles.FS.atomic(dir, "task.json", value.toString().getBytes(StandardCharsets.UTF_8));
  }

  private void saveQuietly(Record record) {
    try {
      save(record);
    } catch (Exception failure) {
      record.message = "记录写入失败，进度文件保留";
    }
  }

  private void restore() {
    File[] dirs = root.listFiles();
    if (dirs == null) return;
    Arrays.sort(dirs, Comparator.comparing(File::getName));
    for (File dir : dirs) {
      if (records.size() >= 200 || !dir.getName().matches("[a-f0-9-]{36}")) continue;
      try {
        if (!TransferFiles.FS.stat(dir).type.equals("DIRECTORY")) continue;
        File journal = new File(dir, "task.json");
        if (TransferFiles.FS.stat(journal).type.equals("MISSING"))
          journal = new File(dir, "task.json.previous");
        byte[] data = TransferFiles.FS.small(journal, 32_768);
        JSONObject value = new JSONObject(new String(data, StandardCharsets.UTF_8));
        Record record =
            create(
                TransferArtifact.fromJson(value.getJSONObject("artifact")),
                value.getString("destination"),
                value.optBoolean("local"));
        record.id = dir.getName();
        if (!record.id.equals(value.getString("id"))) continue;
        record.status = Status.valueOf(value.getString("status"));
        record.uri = value.optString("uri");
        record.location = value.optString("location");
        record.displayName = value.optString("displayName", record.artifact.name);
        record.mime = value.optString("mime", record.artifact.mime);
        record.createdAt = value.getLong("createdAt");
        record.pendingUri = value.optString("pendingUri");
        record.pendingKind = value.optString("pendingKind");
        record.targetUri = value.optString("targetUri");
        record.message = value.optString("message");
        record.downloaded = TransferFiles.size(partial(record));
        if (record.status == Status.COMPLETE) record.downloaded = record.artifact.bytes;
        if (running(record.status)) {
          record.status = Status.PAUSED;
          record.message = "应用或手机已重启，进度保留，请手动继续";
        }
        records.put(record.id, record);
      } catch (Exception ignored) {
        // Keep malformed/unknown journals and private bytes for diagnosis, never bulk-delete them.
      }
    }
  }

  private void cleanupPending(Record record) throws Exception {
    if (record.pendingUri.isEmpty()) return;
    Uri uri = Uri.parse(record.pendingUri);
    if ("media".equals(record.pendingKind)) {
      // A crash between IS_PENDING=0 and saving COMPLETE must never delete the saved file.
      try (android.database.Cursor cursor =
          context
              .getContentResolver()
              .query(uri, new String[] {MediaStore.MediaColumns.IS_PENDING}, null, null, null)) {
        if (cursor != null && cursor.moveToFirst() && cursor.getInt(0) == 1)
          context.getContentResolver().delete(uri, null, null);
      }
    }
    // SAF has no pending bit: preserve uncertain documents rather than delete user-visible bytes.
    record.pendingUri = record.pendingKind = "";
    if (record.destination.equals("saveas")) record.targetUri = "";
  }

  private boolean recoverPublication(Record record, BackupControl control) throws Exception {
    if (!"media".equals(record.pendingKind) || record.pendingUri.isEmpty()) return false;
    Uri uri = Uri.parse(record.pendingUri);
    String name = record.artifact.name;
    try (android.database.Cursor cursor =
        context
            .getContentResolver()
            .query(
                uri,
                new String[] {
                  MediaStore.MediaColumns.IS_PENDING, MediaStore.MediaColumns.DISPLAY_NAME
                },
                null,
                null,
                null)) {
      if (cursor == null || !cursor.moveToFirst() || cursor.getInt(0) != 0) return false;
      name = cursor.getString(1);
    }
    try (InputStream in =
        com.deepseekharness.app.backup.DocumentStreams.input(
            context.getContentResolver(), uri, control)) {
      TransferPublisher.require(
          record.artifact, FileIntegrity.copy(in, null, record.artifact.bytes, control::check));
    }
    synchronized (gate) {
      control.check();
      finishPublication(
          record,
          new TransferPublisher.Result(
              uri,
              name,
              record.artifact.mime,
              record.destination.equals("gallery") ? "Pictures/DSHA" : "Download/DSHA"));
    }
    return true;
  }

  private void finishPublication(Record record, TransferPublisher.Result result) throws Exception {
    record.uri = result.uri().toString();
    record.displayName = result.name();
    record.mime = result.mime();
    record.location = result.location();
    record.pendingUri = record.pendingKind = record.targetUri = "";
    record.downloaded = record.artifact.bytes;
    record.speed = 0;
    record.status = Status.COMPLETE;
    record.message = "文件已保存并校验";
    save(record);
    try {
      File partial = partial(record);
      if (TransferFiles.FS.stat(partial).type.equals("FILE")) TransferFiles.FS.delete(partial);
    } catch (IOException ignored) {
      /* The durable published file remains the successful result. */
    }
    releaseWhenUnreferenced(record.artifact);
  }

  /** Releasing a broker manifest is retention bookkeeping, never part of publication success. */
  private void releaseWhenUnreferenced(TransferArtifact artifact) {
    if (artifact.id.startsWith("local-")) return;
    synchronized (gate) {
      pendingReleases.add(artifact.id);
      saveReleases();
    }
    flushReleases();
  }

  private void flushReleases() {
    Set<String> attempted;
    synchronized (gate) {
      if (releasing || pendingReleases.isEmpty()) return;
      releasing = true;
      attempted = new HashSet<>(pendingReleases);
    }
    releaseWorker.execute(
        () -> {
          try {
            releasePending();
          } finally {
            synchronized (gate) {
              releasing = false;
              if (pendingReleases.stream().anyMatch(id -> !attempted.contains(id))) flushReleases();
            }
          }
        });
  }

  private void releasePending() {
    List<String> ids;
    synchronized (gate) {
      ids = new ArrayList<>(pendingReleases);
    }
    for (String id : ids) {
      synchronized (gate) {
        if (records.values().stream()
            .anyMatch(
                other ->
                    other.artifact.id.equals(id)
                        && other.status != Status.COMPLETE
                        && other.status != Status.CANCELLED)) continue;
      }
      HttpURLConnection connection = null;
      try {
        BackupControl control = new BackupControl(null);
        RuntimeSource source = currentSource(control);
        // Do not install this best-effort request as the active transfer connection.
        checkSource(source, control);
        connection =
            (HttpURLConnection) new URL(source.base() + "api/dsha/files/release").openConnection();
        connection.setConnectTimeout(TransferProtocol.RELEASE_TIMEOUT_MS);
        connection.setReadTimeout(TransferProtocol.RELEASE_TIMEOUT_MS);
        connection.setInstanceFollowRedirects(false);
        connection.setRequestMethod("POST");
        connection.setRequestProperty("Cookie", source.cookie());
        connection.setRequestProperty(
            "Origin", source.base().substring(0, source.base().length() - 1));
        connection.setRequestProperty("Content-Type", "application/json");
        connection.setDoOutput(true);
        byte[] body = new JSONObject().put("id", id).toString().getBytes(StandardCharsets.UTF_8);
        connection.setFixedLengthStreamingMode(body.length);
        try (OutputStream out = connection.getOutputStream()) {
          out.write(body);
        }
        int status = connection.getResponseCode();
        if (status == 200 || status == 404 || status == 410) {
          synchronized (gate) {
            pendingReleases.remove(id);
            saveReleases();
          }
        } else return;
      } catch (Exception ignored) {
        return; /* Persist the intent until current runtime/auth returns. */
      } finally {
        if (connection != null) connection.disconnect();
      }
    }
  }

  private void saveReleases() {
    try {
      saveReleasesRequired();
    } catch (Exception ignored) {
      /* Task journals also retain COMPLETE/CANCELLED release intent. */
    }
  }

  private void saveReleasesRequired() throws Exception {
    TransferFiles.FS.atomic(
        root,
        "releases.json",
        new JSONArray(pendingReleases).toString().getBytes(StandardCharsets.UTF_8));
  }

  private void restoreReleases() {
    try {
      File file = new File(root, "releases.json");
      if (TransferFiles.FS.stat(file).type.equals("MISSING"))
        file = new File(root, "releases.json.previous");
      JSONArray values =
          new JSONArray(
              new String(TransferFiles.FS.small(file, 1024 * 1024), StandardCharsets.UTF_8));
      for (int index = 0; index < values.length(); index++) {
        String id = values.getString(index);
        if (id.matches("[a-zA-Z0-9_-]{1,256}")) pendingReleases.add(id);
      }
    } catch (Exception ignored) {
    }
    for (Record record : records.values())
      if (!record.local && (record.status == Status.COMPLETE || record.status == Status.CANCELLED))
        pendingReleases.add(record.artifact.id);
  }

  private static String safeError(Exception error) {
    for (Throwable cause = error; cause != null; cause = cause.getCause()) {
      if (cause instanceof android.system.ErrnoException errno
          && (errno.errno == android.system.OsConstants.ENOSPC
              || errno.errno == android.system.OsConstants.EDQUOT))
        return "FILE_SPACE_INSUFFICIENT";
      String text = cause.getMessage();
      if (text != null
          && (text.equals("NO_SPACE")
              || text.contains("ENOSPC")
              || text.contains("No space left on device"))) return "FILE_SPACE_INSUFFICIENT";
    }
    String code = error.getMessage();
    if (code != null && code.matches("FILE_[A-Z0-9_]{1,80}")) return code;
    if (error instanceof SecurityException) return "FILE_PERMISSION_DENIED";
    return "FILE_TRANSFER_FAILED";
  }

  private static void disconnectAsync(HttpURLConnection connection) {
    Thread close = new Thread(connection::disconnect, "file-download-cancel");
    close.setDaemon(true);
    close.start();
  }
}
