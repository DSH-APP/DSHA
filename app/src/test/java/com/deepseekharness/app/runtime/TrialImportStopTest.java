package com.deepseekharness.app.runtime;

import static org.junit.Assert.*;
import com.deepseekharness.app.backup.BackupJson;
import com.deepseekharness.app.backup.JvmBackupFileSystem;
import com.deepseekharness.app.util.WebPidIdentity;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

public class TrialImportStopTest {
  @Rule public TemporaryFolder temporary = new TemporaryFolder();
  private final JvmBackupFileSystem fs = new JvmBackupFileSystem();
  private final String id = "01234567-89ab-cdef-0123-456789abcdef";
  private final String nonce = id.replace("-", "");
  private final String guest = "/root/.dsha-runtime-trial-" + nonce;
  private final String profile = "dsha-recovery-" + nonce.substring(0, 16);
  private File operation, payload;

  private void prepare() throws Exception {
    operation = new File(temporary.newFolder(), id);
    assertTrue(operation.mkdir());
    payload = new File(operation, "payload");
    assertTrue(payload.mkdir());
    Files.write(
        new File(operation, "intent.json").toPath(),
        BackupJson.write(
            Map.of("id", id, "nonce", nonce, "profile", profile, "runtimeId", "a".repeat(64)),
            16384));
    Files.writeString(new File(operation, "launched").toPath(), id);
    Files.writeString(new File(payload, ".dsha-web.pid").toPath(), "123\n");
    Files.writeString(new File(payload, ".dsha-web.identity").toPath(), "123 99\n");
  }

  private WebPidIdentity identity(long born) {
    String[] fields = new String[22];
    Arrays.fill(fields, "0");
    fields[0] = "S";
    fields[1] = "10";
    fields[2] = "123";
    fields[3] = "123";
    fields[19] = String.valueOf(born);
    return WebPidIdentity.parse("123 (node) " + String.join(" ", fields), 123);
  }

  private final class Kernel implements TrialImportStop.Io {
    int signals, captures, relatedChecks;
    long time;
    long born = 99;
    int uid = 42;
    boolean denied, gone, wrongCwd, keepAlive, relatedUnknown, changeBirth, mutateRecord;
    String command =
        String.join(
                "\0",
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
                "0")
            + "\0";
    Map<String, String> environment =
        new HashMap<>(
            Map.of(
                "HOME",
                guest + "/isolated-user-home",
                "DSH_HOME",
                guest + "/home",
                "DSHA_RUNTIME_TRIAL_NONCE",
                nonce));

    public int appUid() {
      return 42;
    }

    public TrialImportStop.Snapshot capture(int pid) throws IOException {
      assertEquals(123, pid);
      captures++;
      if (mutateRecord && captures == 2)
        Files.writeString(new File(payload, ".dsha-web.identity").toPath(), "123 100\n");
      if (gone || signals > 0 && !keepAlive)
        return new TrialImportStop.Snapshot(TrialImportStop.State.GONE, null, -1, "", Map.of(), "");
      return new TrialImportStop.Snapshot(
          denied ? TrialImportStop.State.DENIED : TrialImportStop.State.LIVE,
          identity(changeBirth && captures > 1 ? 100 : born),
          uid,
          command,
          environment,
          wrongCwd ? operation.getCanonicalPath() : payload.getCanonicalPath());
    }

    public void term(int pid) {
      assertEquals(123, pid);
      signals++;
    }

    public void confirmNoRelated(String actualNonce, String actualGuest, File actualPayload)
        throws IOException {
      assertEquals(nonce, actualNonce);
      assertEquals(guest, actualGuest);
      assertEquals(payload, actualPayload);
      relatedChecks++;
      if (relatedUnknown) throw new IOException("TRIAL_CONTEXT_INSPECTION_UNCONFIRMED");
    }

    public long now() {
      return time;
    }

    public void pause() {
      time += 1000;
    }
  }

  @Test
  public void exactOwnedRecordedImportIsTerminatedAndRequiresRelatedExitProof() throws Exception {
    prepare();
    Kernel kernel = new Kernel();
    assertTrue(TrialImportStop.stop(fs, operation, payload, kernel));
    assertEquals(1, kernel.signals);
    assertEquals(1, kernel.relatedChecks);
    assertFalse(new File(operation, "closed").exists());
    assertTrue(payload.exists());
  }

  @Test
  public void wrongBirthUidNonceProfileOrPreloadNeverSignals() throws Exception {
    prepare();
    for (int variation = 0; variation < 8; variation++) {
      Kernel kernel = new Kernel();
      if (variation == 0) kernel.born = 100;
      if (variation == 1) kernel.uid = 43;
      if (variation == 2) kernel.environment.put("DSHA_RUNTIME_TRIAL_NONCE", "b".repeat(32));
      if (variation == 3)
        kernel.command = kernel.command.replace(profile, "dsha-recovery-" + "b".repeat(16));
      if (variation == 4)
        kernel.command = kernel.command.replace("runtime-entry.mjs", "foreign.mjs");
      if (variation == 5) kernel.environment.put("HOME", "/root");
      if (variation == 6) kernel.environment.put("DSH_HOME", "/root/.dsh");
      if (variation == 7) kernel.wrongCwd = true;
      assertFalse(TrialImportStop.stop(fs, operation, payload, kernel));
      assertEquals(0, kernel.signals);
    }
  }

  @Test
  public void changingBirthOrRecordsDuringSecondCaptureNeverSignals() throws Exception {
    prepare();
    Kernel birthChanged = new Kernel();
    birthChanged.changeBirth = true;
    assertThrows(
        IOException.class, () -> TrialImportStop.stop(fs, operation, payload, birthChanged));
    assertEquals(0, birthChanged.signals);
    Kernel kernel = new Kernel();
    kernel.mutateRecord = true;
    Kernel changed = kernel;
    assertThrows(IOException.class, () -> TrialImportStop.stop(fs, operation, payload, changed));
    assertEquals(0, kernel.signals);
  }

  @Test
  public void deniedIdentityOrMissingBirthCannotGrantSignalPermission() throws Exception {
    prepare();
    Kernel kernel = new Kernel();
    kernel.denied = true;
    assertFalse(TrialImportStop.stop(fs, operation, payload, kernel));
    assertEquals(0, kernel.signals);
    assertTrue(new File(payload, ".dsha-web.identity").delete());
    assertThrows(
        IOException.class, () -> TrialImportStop.stop(fs, operation, payload, new Kernel()));
  }

  @Test
  public void liveOrUnreadableRelatedGuestPreventsSuccessfulRecovery() throws Exception {
    prepare();
    Kernel live = new Kernel();
    live.keepAlive = true;
    assertThrows(IOException.class, () -> TrialImportStop.stop(fs, operation, payload, live));
    assertEquals(1, live.signals);
    Kernel unknown = new Kernel();
    unknown.relatedUnknown = true;
    assertThrows(IOException.class, () -> TrialImportStop.stop(fs, operation, payload, unknown));
    assertEquals(1, unknown.signals);
    Kernel goneUnknown = new Kernel();
    goneUnknown.gone = true;
    goneUnknown.relatedUnknown = true;
    assertThrows(
        IOException.class, () -> TrialImportStop.stop(fs, operation, payload, goneUnknown));
    assertEquals(0, goneUnknown.signals);
    assertTrue(payload.exists());
    assertFalse(new File(operation, "closed").exists());
  }

  @Test
  public void cancellationNeverSignalsOrClosesTheRecord() throws Exception {
    prepare();
    Kernel kernel = new Kernel();
    Thread.currentThread().interrupt();
    try {
      assertThrows(
          java.io.InterruptedIOException.class,
          () -> TrialImportStop.stop(fs, operation, payload, kernel));
      assertEquals(0, kernel.signals);
      assertFalse(new File(operation, "closed").exists());
      assertTrue(payload.exists());
    } finally {
      Thread.interrupted();
    }
  }

  @Test
  public void missingPidAndUnboundIntentStayUnconfirmed() throws Exception {
    prepare();
    assertTrue(new File(payload, ".dsha-web.pid").delete());
    Kernel kernel = new Kernel();
    assertFalse(TrialImportStop.stop(fs, operation, payload, kernel));
    assertEquals(0, kernel.signals);
    Files.writeString(
        new File(operation, "intent.json").toPath(),
        "{\"id\":\"wrong\",\"nonce\":\"wrong\",\"profile\":\"wrong\",\"runtimeId\":\""
            + "a".repeat(64)
            + "\"}");
    assertThrows(
        IOException.class, () -> TrialImportStop.stop(fs, operation, payload, new Kernel()));
  }
}
