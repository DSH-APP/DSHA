package com.deepseekharness.app.runtime;

import android.system.ErrnoException;
import android.system.Os;
import android.system.OsConstants;
import com.deepseekharness.app.backup.AndroidBackupFileSystem;
import com.deepseekharness.app.backup.BackupFileSystem;
import com.deepseekharness.app.util.Compat;
import com.deepseekharness.app.util.ProcessIdentity;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;

/** Durable birth evidence for bounded proroot sessions only. No PID is signalled from recovery. */
public final class BoundedGuestSessions {
  private static final String HOME = "bounded-guest-active";
  private static final java.util.concurrent.ConcurrentHashMap<String, Operation> active =
      new java.util.concurrent.ConcurrentHashMap<>();

  private BoundedGuestSessions() {}

  public static final class Operation {
    private final BackupFileSystem fs;
    private final File home, entry;
    private final String id;
    private volatile boolean uncertain;

    private Operation(BackupFileSystem fs, File home, File entry, String id) {
      this.fs = fs;
      this.home = home;
      this.entry = entry;
      this.id = id;
    }

    public String id() {
      return id;
    }

    /** Called by the session launcher owner before the first guest handshake byte. */
    void beforeLaunch(ProcessIdentity identity) throws IOException {
      if (identity == null
          || !identity.ownsSession()
          || identity.parent != android.os.Process.myPid())
        throw new IOException("BOUNDED_GUEST_IDENTITY");
      for (String member : fs.list(entry))
        if (member.equals("identity")
            || member.startsWith("identity.tmp-")
            || member.startsWith("identity.previous"))
          throw new IOException("BOUNDED_GUEST_IDENTITY_ALREADY_WRITTEN");
      String value =
          id + "\n" + identity.pid + " " + identity.started + " " + identity.session + "\n";
      fs.atomic(entry, "identity", value.getBytes(StandardCharsets.US_ASCII));
      fs.syncDirectory(entry);
    }

    /** Only called after the owner confirmed the entire group and supervisor exited. */
    void completed() throws IOException {
      try {
        remove(entry, fs);
        fs.syncDirectory(home);
        active.remove(id, this);
      } catch (IOException failure) {
        uncertain = true;
        throw failure;
      }
    }

    void uncertain() {
      uncertain = true;
    }
  }

  public static Operation begin(File filesDir) throws IOException {
    return begin(new AndroidBackupFileSystem(), filesDir.getCanonicalFile());
  }

  static Operation begin(BackupFileSystem fs, File filesDir) throws IOException {
    File files = filesDir.getCanonicalFile();
    File home = fs.child(files, HOME);
    if (fs.stat(home).type.equals("MISSING")) fs.directory(home);
    if (!fs.stat(home).type.equals("DIRECTORY")) throw new IOException("BOUNDED_GUEST_RECORD_ROOT");
    String id = UUID.randomUUID().toString();
    File entry = fs.child(home, id);
    fs.directory(entry);
    fs.atomic(entry, "intent", (id + "\n").getBytes(StandardCharsets.US_ASCII));
    fs.syncDirectory(home);
    Operation operation = new Operation(fs, home, entry, id);
    active.put(id, operation);
    return operation;
  }

  /** Read-only startup/maintenance gate: even an unfinished intent requires recovery. */
  public static String firstPending(File filesDir) throws IOException {
    return firstPending(new AndroidBackupFileSystem(), filesDir.getCanonicalFile());
  }

  static String firstPending(BackupFileSystem fs, File filesDir) throws IOException {
    String first = null;
    for (Inspection item : inspect(fs, filesDir)) {
      if (first == null && item.state.equals("INVALID")) first = item.id;
      if (first == null && item.state.equals("PENDING")) first = item.id;
    }
    return first;
  }

  public record Inspection(String id, String state, String reason) {}

  public static List<Inspection> inspect(File filesDir) throws IOException {
    return inspect(new AndroidBackupFileSystem(), filesDir.getCanonicalFile());
  }

  static List<Inspection> inspect(BackupFileSystem fs, File filesDir) throws IOException {
    File home = fs.child(filesDir.getCanonicalFile(), HOME);
    if (fs.stat(home).type.equals("MISSING")) return List.of();
    if (!fs.stat(home).type.equals("DIRECTORY")) throw new IOException("BOUNDED_GUEST_RECORD_ROOT");
    List<String> entries = fs.list(home);
    if (entries.size() > 128) throw new IOException("BOUNDED_GUEST_RECORD_LIMIT");
    java.util.ArrayList<Inspection> result = new java.util.ArrayList<>();
    for (String id : entries) {
      try {
        File entry = validateEntry(fs, home, id);
        Operation running = active.get(id);
        if (running != null && !running.uncertain) {
          result.add(new Inspection(id, "ACTIVE", "OWNED_CURRENT_INVOCATION"));
          continue;
        }
        String reason =
            fs.stat(fs.child(entry, "identity")).type.equals("MISSING")
                ? "LAUNCH_HANDSHAKE_NOT_RECORDED"
                : "RECORDED_SESSION_REQUIRES_REAP";
        result.add(new Inspection(id, "PENDING", reason));
      } catch (IOException | RuntimeException invalid) {
        result.add(
            new Inspection(
                id,
                "INVALID",
                com.deepseekharness.app.util.SensitiveData.redact(
                    String.valueOf(invalid.getMessage()))));
      }
    }
    return List.copyOf(result);
  }

  /** Under an exclusive maintenance fence, remove only records whose whole session is proven empty. */
  public static void reapExited(File filesDir) throws IOException {
    reapExited(new AndroidBackupFileSystem(), filesDir.getCanonicalFile());
  }

  static void reapExited(BackupFileSystem fs, File filesDir) throws IOException {
    reapExited(fs, filesDir, BoundedGuestSessions::sessionEmpty);
  }

  interface SessionProbe {
    boolean empty(int pid, long started) throws IOException;
  }

  static void reapExited(BackupFileSystem fs, File filesDir, SessionProbe probe)
      throws IOException {
    File home = fs.child(filesDir.getCanonicalFile(), HOME);
    if (fs.stat(home).type.equals("MISSING")) return;
    if (!fs.stat(home).type.equals("DIRECTORY")) throw new IOException("BOUNDED_GUEST_RECORD_ROOT");
    List<String> entries = fs.list(home);
    if (entries.size() > 128) throw new IOException("BOUNDED_GUEST_RECORD_LIMIT");
    java.util.ArrayList<String> blocked = new java.util.ArrayList<>();
    for (String id : entries) {
      try {
        File entry = validateEntry(fs, home, id);
        File identity = fs.child(entry, "identity");
        if (fs.stat(identity).type.equals("MISSING")) {
          // The launcher may not receive DSHA_START before this record is durable.
          remove(entry, fs);
          continue;
        }
        String value = new String(fs.small(identity, 128), StandardCharsets.US_ASCII);
        String[] lines = value.split("\n", -1);
        if (lines.length != 3
            || !id.equals(lines[0])
            || !lines[2].isEmpty()
            || !lines[1].matches("[1-9][0-9]* [1-9][0-9]* [1-9][0-9]*"))
          throw new IOException("BOUNDED_GUEST_IDENTITY_RECORD");
        String[] parts = lines[1].split(" ");
        int pid;
        long started;
        int session;
        try {
          pid = Integer.parseInt(parts[0]);
          started = Long.parseLong(parts[1]);
          session = Integer.parseInt(parts[2]);
        } catch (NumberFormatException invalid) {
          throw new IOException("BOUNDED_GUEST_IDENTITY_RECORD", invalid);
        }
        if (pid < 2 || session != pid || !probe.empty(pid, started))
          throw new IOException("BOUNDED_GUEST_SESSION_LIVE_OR_UNKNOWN:" + id);
        remove(entry, fs);
      } catch (java.io.InterruptedIOException cancelled) {
        throw cancelled;
      } catch (IOException | RuntimeException failure) {
        if (Thread.currentThread().isInterrupted()) {
          java.io.InterruptedIOException cancelled =
              new java.io.InterruptedIOException("CANCELLED");
          cancelled.initCause(failure);
          throw cancelled;
        }
        // 单条损坏记录不阻止清理后续已证明为空的会话。
        // 原件仍在活动目录并继续阻止维护；移动到隔离区
        // 不能证明未经核验的 guest 已经退出。
        blocked.add(
            id
                + ":"
                + com.deepseekharness.app.util.SensitiveData.redact(
                    String.valueOf(failure.getMessage())));
      }
    }
    fs.syncDirectory(home);
    if (!blocked.isEmpty())
      throw new IOException("BOUNDED_GUEST_RECORDS_RETAINED:" + String.join(",", blocked));
  }

  private static File validateEntry(BackupFileSystem fs, File home, String id) throws IOException {
    if (!id.matches(com.deepseekharness.app.util.Ids.UUID_PATTERN))
      throw new IOException("BOUNDED_GUEST_RECORD_ID");
    File entry = fs.child(home, id);
    if (!fs.stat(entry).type.equals("DIRECTORY"))
      throw new IOException("BOUNDED_GUEST_RECORD_TYPE");
    File intent = fs.child(entry, "intent");
    List<String> members = fs.list(entry);
    boolean hasIntent = !fs.stat(intent).type.equals("MISSING");
    if (hasIntent
        && !(id + "\n").equals(new String(fs.small(intent, 64), StandardCharsets.US_ASCII)))
      throw new IOException("BOUNDED_GUEST_INTENT");
    for (String name : members) {
      if (name.equals("intent") || name.equals("identity")) continue;
      if (!knownTemporary(fs, entry, id, name))
        throw new IOException("BOUNDED_GUEST_RECORD_MEMBER");
    }
    if (!hasIntent && members.contains("identity"))
      throw new IOException("BOUNDED_GUEST_INTENT_MISSING");
    return entry;
  }

  private static boolean knownTemporary(BackupFileSystem fs, File entry, String id, String name)
      throws IOException {
    boolean intent = name.matches("intent\\.tmp-" + com.deepseekharness.app.util.Ids.UUID_PATTERN);
    boolean identity =
        name.matches("identity\\.tmp-" + com.deepseekharness.app.util.Ids.UUID_PATTERN);
    if (!intent && !identity) return false;
    File file = fs.child(entry, name);
    BackupFileSystem.Node node = fs.stat(file);
    if (!node.type.equals("FILE") || node.size > 128)
      throw new IOException("BOUNDED_GUEST_TEMP_TYPE_OR_SIZE");
    String value = new String(fs.small(file, 128), StandardCharsets.US_ASCII);
    String prefix = id + "\n";
    if (intent) return prefix.startsWith(value);
    if (value.length() <= prefix.length()) return prefix.startsWith(value);
    return value.startsWith(prefix) && value.substring(prefix.length()).matches("[0-9 ]*\\n?");
  }

  private static void remove(File entry, BackupFileSystem fs) throws IOException {
    validateEntry(fs, entry.getParentFile(), entry.getName());
    File identity = fs.child(entry, "identity"), intent = fs.child(entry, "intent");
    if (!fs.stat(identity).type.equals("MISSING")) fs.delete(identity);
    for (String name : fs.list(entry))
      if (name.startsWith("intent.tmp-") || name.startsWith("identity.tmp-"))
        fs.delete(fs.child(entry, name));
    if (!fs.stat(intent).type.equals("MISSING")) fs.delete(intent);
    fs.syncDirectory(entry);
    fs.delete(entry);
  }

  private static boolean sessionEmpty(int session, long started) throws IOException {
    File[] entries = new File("/proc").listFiles();
    if (entries == null) throw new IOException("BOUNDED_GUEST_PROC_UNREADABLE");
    for (File entry : entries) {
      String name = entry.getName();
      if (!name.matches("[1-9][0-9]*")) continue;
      int pid;
      try {
        pid = Integer.parseInt(name);
      } catch (NumberFormatException ignored) {
        continue;
      }
      int actual = NativeProcess.sessionId(pid);
      if (actual == -OsConstants.ESRCH) continue;
      if (actual < 0) {
        if (pid == session || owned(entry))
          throw new IOException("BOUNDED_GUEST_SESSION_UNREADABLE");
        continue;
      }
      if (actual != session) continue;
      if (!owned(entry)) throw new IOException("BOUNDED_GUEST_SESSION_OWNER_UNKNOWN");
      ProcessIdentity member =
          ProcessIdentity.inSession(Compat.readAll(new File(entry, "stat")), pid, session);
      if (member == null) throw new IOException("BOUNDED_GUEST_SESSION_IDENTITY_UNKNOWN");
      if (pid == session && member.started != started)
        throw new IOException("BOUNDED_GUEST_LEADER_REUSED");
      if (!member.exited()) return false;
    }
    return true;
  }

  private static boolean owned(File proc) throws IOException {
    try {
      return Os.stat(proc.getAbsolutePath()).st_uid == android.os.Process.myUid();
    } catch (ErrnoException error) {
      if (error.errno == OsConstants.ENOENT || error.errno == OsConstants.ESRCH) return false;
      throw new IOException("BOUNDED_GUEST_OWNER_UNREADABLE", error);
    }
  }
}
