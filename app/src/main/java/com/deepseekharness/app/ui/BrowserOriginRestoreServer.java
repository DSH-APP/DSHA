package com.deepseekharness.app.ui;

import android.content.Context;
import android.os.SystemClock;
import com.deepseekharness.app.backup.AndroidBackupFileSystem;
import com.deepseekharness.app.backup.BackupFileSystem;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.*;
import org.json.*;

/** An exclusive, short-lived old-origin reader. Only a native-delivered nonce can transfer data. */
final class BrowserOriginRestoreServer implements AutoCloseable {
  private static final long LIMIT = 256L * 1024 * 1024;
  private final BackupFileSystem fs;
  private final java.util.function.LongSupplier clock;
  private static final Object CANDIDATE_LOCK = new Object();
  private static final long STORAGE_LIMIT = 1024L * 1024 * 1024;
  private final File directory;
  private final ServerSocket listener;
  private final String origin, target, secret, html;
  private final Runnable captured;
  private final java.util.function.BooleanSupplier current;
  private final java.util.function.Consumer<JSONObject> finished;
  private volatile boolean closed;
  private volatile Socket active;
  private volatile long activeDeadline;
  private JSONObject manifest;
  private int nextPart;
  private final java.util.List<byte[]> partDigests = new ArrayList<>();
  private boolean ready;
  private boolean responseStarted;
  private String snapshotId;

  BrowserOriginRestoreServer(
      Context context,
      int port,
      String targetBase,
      java.util.function.BooleanSupplier current,
      Runnable captured,
      java.util.function.Consumer<JSONObject> finished)
      throws Exception {
    this(
        new AndroidBackupFileSystem(),
        privateFiles(context),
        checkedPort(port),
        targetBase,
        asset(context, "app-integration/browser-origin-restore.html")
            .replace(
                "/*DSHA_ORIGIN_STATE_SCRIPT*/",
                asset(context, "app-integration/browser-origin-state.js")),
        SystemClock::elapsedRealtime,
        current,
        captured,
        finished,
        randomSecret());
  }

  private static File privateFiles(Context context) throws IOException {
    var information = context.getApplicationInfo();
    if (information == null || information.dataDir == null)
      throw new IOException("COLD_PACKAGE_HOST_ROOT");
    return privateFiles(
        new AndroidBackupFileSystem(), new File(information.dataDir), context.getFilesDir());
  }

  /** Resolve only Android's declared app-data alias; writable descendants retain NOFOLLOW. */
  static File privateFiles(BackupFileSystem fs, File applicationData, File frameworkFiles)
      throws IOException {
    var host =
        com.deepseekharness.app.util.ColdInstallPackages.bindPrivateFiles(
            fs, applicationData, frameworkFiles);
    host.verify(fs);
    return host.files;
  }

  /** Only fixed public codes cross into diagnostics; messages may contain private paths or tokens. */
  static String failureCode(Throwable failure) {
    String code = failure.getMessage();
    if (code != null
        && Set.of(
                "COLD_PACKAGE_HOST_ROOT",
                "COLD_PACKAGE_FILES_LINK_OR_MISSING",
                "COLD_PACKAGE_HOST_ROOT_CHANGED",
                "PARENT_LINK",
                "PARENT_LINK_OR_MISSING",
                "UNSAFE_ROOT",
                "UNSAFE_HOST_PATH",
                "PERMISSION_DENIED",
                "NO_SPACE",
                "SOURCE_CHANGED",
                "RESTORE_CANCELLED",
                "RESTORE_PREVIEW_CLOSE_UNCONFIRMED",
                "RESTORE_PORT",
                "RESTORE_PORT_OR_NONCE",
                "RESTORE_TARGET",
                "RESTORE_BUDGET_CATALOGUE",
                "RESTORE_BUDGET_UNKNOWN_RECORD",
                "RESTORE_BUDGET_MEMBERS",
                "RESTORE_BUDGET_UNKNOWN_MEMBER",
                "RESTORE_BUDGET_STORAGE",
                "RESTORE_BUDGET_ACTIVE_RECORDS")
            .contains(code)) return code;
    if (failure instanceof BindException) return "RESTORE_PORT_OCCUPIED";
    if (failure instanceof FileNotFoundException) return "RESTORE_ASSET_UNAVAILABLE";
    if (failure instanceof SecurityException) return "PERMISSION_DENIED";
    return "RESTORE_PREPARE_FAILED";
  }

  /** Host seam: real sockets/parser/persistence, explicit file/syscall adapter and monotonic clock. */
  BrowserOriginRestoreServer(
      BackupFileSystem fs,
      File authority,
      int port,
      String targetBase,
      String html,
      java.util.function.LongSupplier clock,
      java.util.function.BooleanSupplier current,
      Runnable captured,
      java.util.function.Consumer<JSONObject> finished,
      String secret)
      throws Exception {
    if (port < 0 || port > 65535 || !secret.matches("[a-f0-9]{64}"))
      throw new IOException("RESTORE_PORT_OR_NONCE");
    URI destination = new URI(targetBase);
    if (!"http".equals(destination.getScheme())
        || !"127.0.0.1".equals(destination.getHost())
        || destination.getPort() < 1
        || destination.getPort() > 65535
        || !"/".equals(destination.getRawPath())
        || destination.getRawQuery() != null
        || destination.getRawFragment() != null
        || destination.getRawUserInfo() != null) throw new IOException("RESTORE_TARGET");
    this.fs = fs;
    this.clock = clock;
    this.html = html;
    this.target = targetBase.substring(0, targetBase.length() - 1);
    this.current = current;
    this.captured = captured;
    this.finished = finished;
    this.secret = secret;
    listener = new ServerSocket();
    try {
      listener.setReuseAddress(false);
      listener.bind(new InetSocketAddress(InetAddress.getByName("127.0.0.1"), port), 4);
      listener.setSoTimeout(500);
    } catch (Exception failure) {
      listener.close();
      throw failure;
    }
    origin = "http://127.0.0.1:" + listener.getLocalPort();
    try {
      fs.parents(authority, "host-browser-origin-restores/placeholder");
      File root = fs.child(authority, "host-browser-origin-restores");
      synchronized (CANDIDATE_LOCK) {
        checkQuota(root, null, 0);
        directory = fs.child(root, UUID.randomUUID().toString());
        fs.directory(directory);
        fs.syncDirectory(root);
      }
      fs.atomic(
          directory,
          "source.json",
          new JSONObject()
              .put("version", 1)
              .put("source", origin)
              .put("target", target)
              .toString()
              .getBytes(StandardCharsets.UTF_8));
    } catch (Exception failure) {
      listener.close();
      throw failure;
    }
    Thread thread = new Thread(this::serve, "dsha-origin-restore");
    thread.setDaemon(true);
    thread.start();
    Thread watcher =
        new Thread(
            () -> {
              while (!closed) {
                if (!current.getAsBoolean()) {
                  close();
                  return;
                }
                Socket socket = active;
                long deadline = activeDeadline;
                if (socket != null && deadline > 0 && clock.getAsLong() >= deadline)
                  try {
                    socket.close();
                  } catch (IOException ignored) {
                  }
                try {
                  Thread.sleep(100);
                } catch (InterruptedException interrupted) {
                  Thread.currentThread().interrupt();
                  close();
                  return;
                }
              }
            },
            "dsha-origin-restore-deadline");
    watcher.setDaemon(true);
    watcher.start();
  }

  private static int checkedPort(int port) throws IOException {
    if (port < 1 || port > 65535) throw new IOException("RESTORE_PORT");
    return port;
  }

  private static String randomSecret() {
    byte[] random = new byte[32];
    new SecureRandom().nextBytes(random);
    StringBuilder key = new StringBuilder();
    for (byte b : random) key.append(String.format(java.util.Locale.ROOT, "%02x", b & 255));
    return key.toString();
  }

  private void checkQuota(File root, File receiving, long incoming) throws Exception {
    List<String> records = fs.list(root);
    if (records.size() > 512) throw new IOException("RESTORE_BUDGET_CATALOGUE");
    int pending = 0;
    long reserved = incoming;
    for (String name : records) {
      File record = fs.child(root, name);
      if (!fs.stat(record).type.equals("DIRECTORY"))
        throw new IOException("RESTORE_BUDGET_UNKNOWN_RECORD");
      if (receiving != null && record.equals(receiving)) continue;
      if (!fs.stat(fs.child(record, "restore-result.json")).type.equals("FILE")) pending++;
      long actual = 0, parts = 0;
      List<String> members = fs.list(record);
      if (members.size() > 10260) throw new IOException("RESTORE_BUDGET_MEMBERS");
      for (String member : members) {
        File file = fs.child(record, member);
        var stat = fs.stat(file);
        if (!stat.type.equals("FILE")) throw new IOException("RESTORE_BUDGET_UNKNOWN_MEMBER");
        actual += stat.size;
        if (member.startsWith("part-")) parts += stat.size;
      }
      File saved = fs.child(record, "manifest.json");
      if (fs.stat(saved).type.equals("FILE")) {
        JSONArray lengths =
            new JSONObject(new String(fs.small(saved, 16 * 1024 * 1024), StandardCharsets.UTF_8))
                .getJSONArray("parts");
        long plan = 0;
        for (int i = 0; i < lengths.length(); i++) plan += lengths.getLong(i);
        actual += Math.max(0, plan - parts);
      }
      reserved += actual;
      if (reserved > STORAGE_LIMIT) throw new IOException("RESTORE_BUDGET_STORAGE");
    }
    if (pending >= com.deepseekharness.app.backup.BackupLimits.HOST_OPERATION_ACTIVE_RECORDS)
      throw new IOException("RESTORE_BUDGET_ACTIVE_RECORDS");
    if (reserved > STORAGE_LIMIT) throw new IOException("RESTORE_BUDGET_STORAGE");
  }

  String origin() {
    return origin;
  }

  String secret() {
    return secret;
  }

  private static String asset(Context context, String name) throws IOException {
    try (InputStream input = context.getAssets().open(name);
        ByteArrayOutputStream out = new ByteArrayOutputStream()) {
      byte[] buffer = new byte[8192];
      int n;
      while ((n = input.read(buffer)) != -1) out.write(buffer, 0, n);
      return out.toString("UTF-8");
    }
  }

  private void serve() {
    while (!closed && current.getAsBoolean())
      try (Socket socket = listener.accept()) {
        active = socket;
        socket.setSoTimeout(5000);
        try {
          request(socket);
        } catch (Exception failure) {
          if (!closed && !responseStarted)
            respond(
                socket,
                failure.getMessage() != null && failure.getMessage().startsWith("RESTORE_BUDGET")
                    ? 507
                    : 400,
                null,
                target);
        }
      } catch (Exception ignored) {
        /* failed candidates are retained, never treated as committed */
      } finally {
        active = null;
        activeDeadline = 0;
      }
    close();
  }

  private void request(Socket socket) throws Exception {
    responseStarted = false;
    requireCurrent();
    InputStream input = socket.getInputStream();
    long deadline = clock.getAsLong() + 15000;
    activeDeadline = deadline;
    ByteArrayOutputStream head = new ByteArrayOutputStream();
    int previous = 0, matched = 0;
    while (matched < 4) {
      if (head.size() >= 16384 || clock.getAsLong() > deadline)
        throw new IOException("RESTORE_HEADER_LIMIT");
      int b = input.read();
      if (b < 0) throw new EOFException();
      head.write(b);
      matched =
          (matched == 0 && b == 13)
              ? 1
              : (matched == 1 && b == 10)
                  ? 2
                  : (matched == 2 && b == 13) ? 3 : (matched == 3 && b == 10) ? 4 : 0;
      previous = b;
    }
    String[] lines = head.toString("ISO-8859-1").split("\r\n");
    String[] first = lines[0].split(" ");
    if (first.length != 3) throw new IOException("RESTORE_REQUEST");
    String method = first[0], path = first[1].split("\\?", 2)[0];
    Map<String, String> headers = new HashMap<>();
    for (int i = 1; i < lines.length; i++) {
      int colon = lines[i].indexOf(':');
      if (colon <= 0) throw new IOException("RESTORE_HEADER");
      String name = lines[i].substring(0, colon).toLowerCase(java.util.Locale.ROOT);
      if (headers.put(name, lines[i].substring(colon + 1).trim()) != null)
        throw new IOException("RESTORE_DUPLICATE_HEADER");
    }
    if (!headers.getOrDefault("host", "").equals("127.0.0.1:" + listener.getLocalPort())) {
      respond(socket, 403, null, null);
      return;
    }
    String requesting = headers.get("origin");
    if (requesting != null && !requesting.equals(origin) && !requesting.equals(target)) {
      respond(socket, 403, null, null);
      return;
    }
    if (method.equals("OPTIONS")) {
      respond(socket, 204, null, requesting);
      return;
    }
    if (method.equals("GET") && path.equals("/__dsha/browser-origin-restore")) {
      respond(socket, 200, html.getBytes(StandardCharsets.UTF_8), null, "text/html; charset=utf-8");
      return;
    }
    if (!MessageDigest.isEqual(
        secret.getBytes(StandardCharsets.US_ASCII),
        headers.getOrDefault("x-dsha-restore", "").getBytes(StandardCharsets.US_ASCII))) {
      respond(socket, 403, null, null);
      return;
    }
    requireCurrent();
    boolean upload =
        method.equals("POST")
            && (path.equals("/manifest") || path.startsWith("/part/") || path.equals("/captured"));
    boolean read =
        method.equals("GET")
            && (path.equals("/manifest") || path.startsWith("/part/") || path.equals("/snapshot"));
    boolean restore =
        method.equals("POST") && (path.startsWith("/verify/") || path.equals("/finished"));
    if ((upload && !origin.equals(requesting))
        || ((read || restore) && !target.equals(requesting))) {
      respond(socket, 403, null, requesting);
      return;
    }
    if (method.equals("GET") && path.equals("/lease")) {
      respond(socket, 200, "{}".getBytes(StandardCharsets.UTF_8), requesting);
      return;
    }
    if (method.equals("GET") && ready && path.equals("/snapshot")) {
      respond(
          socket,
          200,
          new JSONObject().put("snapshot", snapshotId).toString().getBytes(StandardCharsets.UTF_8),
          requesting);
      return;
    }
    if (headers.containsKey("transfer-encoding")) {
      respond(socket, 400, null, requesting);
      return;
    }
    if (method.equals("GET") && ready && path.equals("/manifest")) {
      respond(
          socket,
          200,
          fs.small(fs.child(directory, "manifest.json"), 16 * 1024 * 1024),
          requesting);
      return;
    }
    if (method.equals("GET") && ready && path.matches("/part/[0-9]+")) {
      int index = Integer.parseInt(path.substring(6));
      if (index < 0 || index >= nextPart) throw new IOException("RESTORE_PART");
      File file = fs.child(directory, "part-" + index);
      var node = fs.stat(file);
      headers(socket, 200, node.size, requesting, "application/octet-stream");
      long transferDeadline = clock.getAsLong() + 120000;
      activeDeadline = transferDeadline;
      try (InputStream part = fs.read(file, node)) {
        copy(part, socket.getOutputStream(), node.size, transferDeadline);
      }
      return;
    }
    if (!method.equals("POST")) {
      respond(socket, 405, null, requesting);
      return;
    }
    long size = Long.parseLong(headers.getOrDefault("content-length", "-1"));
    if (size < 0 || size > LIMIT) throw new IOException("RESTORE_SIZE");
    socket.setSoTimeout(20000);
    long bodyDeadline = clock.getAsLong() + 120000;
    activeDeadline = bodyDeadline;
    if (path.matches("/verify/[0-9]+") && ready) {
      int part = Integer.parseInt(path.substring(8));
      if (part < 0 || part >= nextPart || size != manifest.getJSONArray("parts").getLong(part)) {
        respond(socket, 409, null, requesting);
        return;
      }
      MessageDigest digest = MessageDigest.getInstance("SHA-256");
      copy(
          new java.security.DigestInputStream(input, digest),
          new OutputStream() {
            public void write(int b) {}

            public void write(byte[] b, int o, int n) {}
          },
          size,
          bodyDeadline);
      respond(
          socket,
          MessageDigest.isEqual(digest.digest(), partDigests.get(part)) ? 200 : 409,
          "{}".getBytes(StandardCharsets.UTF_8),
          requesting);
      return;
    } else if (path.equals("/manifest") && manifest == null) {
      if (size > 16 * 1024 * 1024) throw new IOException("RESTORE_METADATA_LIMIT");
      ByteArrayOutputStream bytes = new ByteArrayOutputStream();
      copy(input, bytes, size, bodyDeadline);
      JSONObject value = new JSONObject(bytes.toString("UTF-8"));
      if (value.has("partSha256")) throw new IOException("RESTORE_METADATA_FIELD");
      validate(value);
      synchronized (CANDIDATE_LOCK) {
        long reservation = size * 2 + 4096;
        JSONArray wanted = value.getJSONArray("parts");
        for (int i = 0; i < wanted.length(); i++) reservation += wanted.getLong(i);
        reservation += wanted.length() * 80L;
        checkQuota(directory.getParentFile(), directory, reservation);
        requireCurrent();
        fs.atomic(directory, "manifest.json", bytes.toByteArray());
        manifest = value;
      }
    } else if (path.equals("/part/" + nextPart) && manifest != null && !ready) {
      long expected = manifest.getJSONArray("parts").getLong(nextPart);
      if (size != expected) throw new IOException("RESTORE_PART_LENGTH");
      requireCurrent();
      File file = fs.child(directory, "part-" + nextPart);
      MessageDigest digest = MessageDigest.getInstance("SHA-256");
      try (OutputStream output = fs.create(file)) {
        copy(input, new java.security.DigestOutputStream(output, digest), size, bodyDeadline);
      }
      var node = fs.stat(file);
      if (node.size != size) throw new IOException("RESTORE_PART_READBACK");
      MessageDigest checked = MessageDigest.getInstance("SHA-256");
      try (InputStream check = fs.read(file, node)) {
        copy(
            new java.security.DigestInputStream(check, checked),
            new OutputStream() {
              public void write(int b) {}

              public void write(byte[] b, int o, int n) {}
            },
            size,
            0);
      }
      byte[] checksum = digest.digest();
      if (!MessageDigest.isEqual(checksum, checked.digest()))
        throw new IOException("RESTORE_PART_DIGEST");
      partDigests.add(checksum);
      fs.syncDirectory(directory);
      nextPart++;
    } else if (path.equals("/captured")
        && manifest != null
        && nextPart == manifest.getJSONArray("parts").length()
        && !ready
        && size <= 4096) {
      copy(input, new ByteArrayOutputStream(), size, bodyDeadline);
      JSONArray checksums = new JSONArray();
      for (byte[] digest : partDigests) {
        StringBuilder hex = new StringBuilder();
        for (byte b : digest) hex.append(String.format(java.util.Locale.ROOT, "%02x", b & 255));
        checksums.put(hex.toString());
      }
      requireCurrent();
      manifest.put("partSha256", checksums);
      // Preserve source JSON spelling (including lone-surrogate escapes and number spelling).
      String original =
          new String(
                  fs.small(fs.child(directory, "manifest.json"), 16 * 1024 * 1024),
                  StandardCharsets.UTF_8)
              .trim();
      byte[] completeManifest =
          (original.substring(0, original.length() - 1)
                  + ",\"partSha256\":"
                  + checksums.toString()
                  + "}")
              .getBytes(StandardCharsets.UTF_8);
      fs.atomic(directory, "manifest.json", completeManifest);
      StringBuilder identity = new StringBuilder();
      for (byte b : MessageDigest.getInstance("SHA-256").digest(completeManifest))
        identity.append(String.format(java.util.Locale.ROOT, "%02x", b & 255));
      snapshotId = identity.toString();
      fs.atomic(directory, "captured.json", "{\"complete\":true}".getBytes(StandardCharsets.UTF_8));
      ready = true;
      respond(socket, 200, "{}".getBytes(StandardCharsets.UTF_8), requesting);
      captured.run();
      return;
    } else if (path.equals("/finished") && ready && size <= 4096) {
      ByteArrayOutputStream bytes = new ByteArrayOutputStream();
      copy(input, bytes, size, bodyDeadline);
      JSONObject result = new JSONObject(bytes.toString("UTF-8"));
      if (!snapshotId.equals(result.optString("snapshot"))
          || !result.optString("candidate").matches("[a-f0-9]{64}")) {
        respond(socket, 409, null, requesting);
        return;
      }
      for (String name : new String[] {"applied", "conflicts", "unknown"})
        if (result.getInt(name) < 0 || result.getInt(name) > 10000)
          throw new IOException("RESTORE_RESULT");
      requireCurrent();
      fs.atomic(directory, "restore-result.json", bytes.toByteArray());
      requireCurrent();
      finished.accept(result);
    } else {
      respond(socket, 409, null, requesting);
      return;
    }
    respond(socket, 200, "{}".getBytes(StandardCharsets.UTF_8), requesting);
  }

  private void validate(JSONObject value) throws Exception {
    if (value.getInt("version") != 1 || !origin.equals(value.getString("source")))
      throw new IOException("RESTORE_SOURCE");
    JSONArray rows = value.getJSONArray("rows"),
        images = value.getJSONArray("images"),
        parts = value.getJSONArray("parts");
    if (rows.length() > 4096 || images.length() > 512 || parts.length() > 10240)
      throw new IOException("RESTORE_COUNT");
    Set<String> keys = new HashSet<>();
    for (int i = 0; i < rows.length(); i++) {
      JSONObject row = rows.getJSONObject(i);
      String key = row.getString("key");
      if (!key.equals("dsha.reading-position")
          && !key.startsWith("dsh.conversation.")
          && !key.startsWith("dsh.sidebar-right.v1.")) throw new IOException("RESTORE_KEY");
      if (key.length() > 560 || row.getString("value").length() > 2 * 1024 * 1024 || !keys.add(key))
        throw new IOException("RESTORE_KEY_LIMIT");
    }
    long total = 0;
    for (int i = 0; i < parts.length(); i++) {
      long size = parts.getLong(i);
      if (size < 0 || size > LIMIT) throw new IOException("RESTORE_PART_SIZE");
      total += size;
      if (total > LIMIT) throw new IOException("RESTORE_TOTAL_SIZE");
    }
    Set<Integer> referenced = new HashSet<>();
    Set<String> sessions = new HashSet<>();
    for (int i = 0; i < images.length(); i++) {
      JSONObject image = images.getJSONObject(i);
      String id = image.getString("id"), revision = image.getString("revision");
      if (id.isEmpty()
          || id.length() > 512
          || revision.isEmpty()
          || revision.length() > 160
          || !sessions.add(id)) throw new IOException("RESTORE_IMAGE_ID");
      JSONArray files = image.getJSONArray("files");
      if (files.length() > 20) throw new IOException("RESTORE_IMAGE_COUNT");
      long imageBytes = 0;
      for (int j = 0; j < files.length(); j++) {
        JSONObject file = files.getJSONObject(j);
        int part = file.getInt("part");
        if (part < 0
            || part >= parts.length()
            || !referenced.add(part)
            || file.getLong("bytes") != parts.getLong(part)
            || !file.getString("type").startsWith("image/"))
          throw new IOException("RESTORE_IMAGE_PART");
        imageBytes += file.getLong("bytes");
      }
      if (image.getLong("bytes") != imageBytes) throw new IOException("RESTORE_IMAGE_BYTES");
    }
    if (referenced.size() != parts.length()) throw new IOException("RESTORE_UNUSED_PART");
  }

  private void requireCurrent() throws IOException {
    if (closed || !current.getAsBoolean()) {
      close();
      throw new IOException("RESTORE_INSTANCE_CHANGED");
    }
  }

  private void copy(InputStream input, OutputStream output, long size, long deadline)
      throws IOException {
    byte[] buffer = new byte[32768];
    long left = size;
    while (left > 0) {
      requireCurrent();
      if (deadline > 0 && clock.getAsLong() > deadline)
        throw new IOException("RESTORE_BODY_DEADLINE");
      int n = input.read(buffer, 0, (int) Math.min(left, buffer.length));
      if (n < 0) throw new EOFException();
      output.write(buffer, 0, n);
      left -= n;
    }
    if (deadline > 0 && clock.getAsLong() >= deadline)
      throw new IOException("RESTORE_BODY_DEADLINE");
    output.flush();
  }

  private void headers(Socket socket, int code, long size, String origin, String type)
      throws IOException {
    responseStarted = true;
    String cors =
        origin == null
            ? ""
            : "Access-Control-Allow-Origin: "
                + origin
                + "\r\nAccess-Control-Allow-Headers: X-DSHA-Restore, Content-Type\r\nAccess-Control-Allow-Methods: GET, POST, OPTIONS\r\nVary: Origin\r\n";
    String response =
        "HTTP/1.1 "
            + code
            + " "
            + (code == 200 ? "OK" : "Response")
            + "\r\nConnection: close\r\nCache-Control: no-store\r\nContent-Type: "
            + type
            + "\r\nContent-Length: "
            + size
            + "\r\n"
            + cors
            + "X-Content-Type-Options: nosniff\r\n\r\n";
    socket.getOutputStream().write(response.getBytes(StandardCharsets.US_ASCII));
  }

  private void respond(Socket socket, int code, byte[] bytes, String origin) throws IOException {
    respond(socket, code, bytes, origin, "application/json");
  }

  private void respond(Socket socket, int code, byte[] bytes, String origin, String type)
      throws IOException {
    headers(socket, code, bytes == null ? 0 : bytes.length, origin, type);
    if (bytes != null) socket.getOutputStream().write(bytes);
    socket.getOutputStream().flush();
  }

  @Override
  public void close() {
    closed = true;
    try {
      listener.close();
    } catch (IOException ignored) {
    }
    Socket socket = active;
    if (socket != null)
      try {
        socket.close();
      } catch (IOException ignored) {
      }
  }
}
