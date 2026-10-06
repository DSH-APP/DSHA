package com.deepseekharness.app.runtime;

import com.deepseekharness.app.backup.BackupFileSystem;
import com.deepseekharness.app.backup.BackupJson;
import com.deepseekharness.app.util.Ids;
import com.deepseekharness.app.util.WebPidIdentity;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Map;

/** Only the app's recorded build160 trial --import argv, never a general Node selector. */
final class TrialImportStop {
  enum State {
    GONE,
    LIVE,
    DENIED
  }

  record Snapshot(
      State state,
      WebPidIdentity identity,
      int uid,
      String command,
      Map<String, String> environment,
      String cwd) {}

  interface Io {
    int appUid();

    Snapshot capture(int pid) throws IOException;

    void term(int pid) throws IOException;

    void confirmNoRelated(String nonce, String guest, File payload) throws IOException;

    long now();

    void pause() throws IOException;
  }

  private TrialImportStop() {}

  static boolean stop(BackupFileSystem fs, File operation, File payload, Io io) throws IOException {
    checkInterrupted();
    String id = operation.getName();
    if (!id.matches(Ids.UUID_PATTERN)
        || !payload.equals(fs.child(operation, "payload"))
        || !payload.getCanonicalFile().equals(payload.getAbsoluteFile()))
      throw new IOException("TRIAL_RECORD_DIRECTORY");
    File intent = fs.child(operation, "intent.json");
    if (!fs.stat(intent).type.equals("FILE")) return false;
    byte[] intentBytes = fs.small(intent, 16384);
    Map<String, Object> value = BackupJson.read(intentBytes, 16384);
    String nonce = id.replace("-", ""), profile = "dsha-recovery-" + nonce.substring(0, 16);
    if (!id.equals(BackupJson.string(value, "id"))
        || !nonce.equals(BackupJson.string(value, "nonce"))
        || !profile.equals(BackupJson.string(value, "profile"))
        || !BackupJson.string(value, "runtimeId").matches("[a-f0-9]{64}"))
      throw new IOException("TRIAL_INTENT_BINDING");
    File launched = fs.child(operation, "launched");
    byte[] launchedBytes = fs.small(launched, 128);
    if (!id.equals(new String(launchedBytes, StandardCharsets.US_ASCII)))
      throw new IOException("TRIAL_MARKER");
    File pidFile = fs.child(payload, ".dsha-web.pid");
    if (fs.stat(pidFile).type.equals("MISSING")) return false;
    File identityFile = fs.child(payload, ".dsha-web.identity");
    if (!fs.stat(pidFile).type.equals("FILE") || !fs.stat(identityFile).type.equals("FILE"))
      throw new IOException("TRIAL_LEGACY_IMPORT_RECORD_UNCONFIRMED");
    byte[] pidBytes = fs.small(pidFile, 32), identityBytes = fs.small(identityFile, 80);
    String pidText = new String(pidBytes, StandardCharsets.US_ASCII).trim();
    String saved = new String(identityBytes, StandardCharsets.US_ASCII).trim();
    int pid;
    try {
      if (!pidText.matches("[1-9][0-9]*") || !saved.matches(pidText + " [1-9][0-9]*"))
        throw new NumberFormatException();
      pid = Integer.parseInt(pidText);
      if (pid < 2 || Long.parseLong(saved.substring(saved.indexOf(' ') + 1)) <= 0)
        throw new NumberFormatException();
    } catch (NumberFormatException invalid) {
      throw new IOException("TRIAL_LEGACY_IMPORT_RECORD_UNCONFIRMED", invalid);
    }
    String guest = "/root/.dsha-runtime-trial-" + nonce;
    Snapshot before = io.capture(pid);
    if (before.state == State.GONE) {
      io.confirmNoRelated(nonce, guest, payload);
      return true;
    }
    if (!matches(before, saved, profile, nonce, guest, payload, io.appUid())) return false;
    Snapshot again = io.capture(pid);
    if (!Arrays.equals(pidBytes, fs.small(pidFile, 32))
        || !Arrays.equals(identityBytes, fs.small(identityFile, 80))
        || !Arrays.equals(intentBytes, fs.small(intent, 16384))
        || !Arrays.equals(launchedBytes, fs.small(launched, 128))
        || !matches(again, saved, profile, nonce, guest, payload, io.appUid())
        || !before.identity.sameProcess(again.identity))
      throw new IOException("TRIAL_LEGACY_IMPORT_IDENTITY_CHANGED");
    checkInterrupted();
    io.term(pid);
    long deadline = io.now() + 3000;
    do {
      Snapshot current = io.capture(pid);
      boolean originalGone =
          current.state == State.GONE
              || current.identity != null && !current.identity.matches(saved);
      if (originalGone) {
        io.confirmNoRelated(nonce, guest, payload);
        return true;
      }
      if (!matches(current, saved, profile, nonce, guest, payload, io.appUid()))
        throw new IOException("TRIAL_LEGACY_IMPORT_EXIT_UNCONFIRMED");
      io.pause();
    } while (io.now() < deadline);
    throw new IOException("TRIAL_LEGACY_IMPORT_EXIT_UNCONFIRMED");
  }

  private static void checkInterrupted() throws java.io.InterruptedIOException {
    if (Thread.currentThread().isInterrupted())
      throw new java.io.InterruptedIOException("CANCELLED");
  }

  private static boolean matches(
      Snapshot state,
      String saved,
      String profile,
      String nonce,
      String guest,
      File payload,
      int uid)
      throws IOException {
    if (state.state != State.LIVE
        || state.uid != uid
        || state.identity == null
        || !state.identity.matches(saved)
        || !payload.getCanonicalPath().equals(state.cwd)
        || !nonce.equals(state.environment.get("DSHA_RUNTIME_TRIAL_NONCE"))
        || !(guest + "/home").equals(state.environment.get("DSH_HOME"))
        || !(guest + "/isolated-user-home").equals(state.environment.get("HOME"))) return false;
    String[] args = state.command.split("\u0000");
    return Arrays.equals(
        args,
        new String[] {
          "/usr/local/bin/node",
          "--import",
          guest + "/runtime-entry.mjs",
          "/usr/local/lib/node_modules/@deepseek-ai/dsh/lib/bin.js",
          "--profile",
          profile,
          "--no-open",
          "--host",
          "127.0.0.1",
          "--port",
          "0"
        });
  }
}
