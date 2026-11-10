package com.deepseekharness.app.runtime;

import static org.junit.Assert.*;

import com.deepseekharness.app.util.WebSessionProof;
import java.io.File;
import java.nio.file.Files;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

/** 证明版接回后按已核验的出生身份补写证据，且绝不覆盖其它实例的 PID 记录。 */
public final class WebSessionEvidenceAttestTest {
  private static final String BOOT = "12345678-1234-1234-1234-123456789abc";
  private static final WebSessionProof.Saved SAVED =
      new WebSessionProof.Saved(
          BOOT,
          7,
          BOOT,
          "ab".repeat(32),
          42,
          123,
          "node /usr/local/bin/dsh web",
          41,
          122,
          "launcher");

  @Rule public TemporaryFolder temporary = new TemporaryFolder();

  private static final class Kernel extends WebProcessManager {
    private final File root;
    private final WebSessionProof.Saved saved;
    SessionObservation observed = SessionObservation.LIVE;

    Kernel(File root, WebSessionProof.Saved saved) {
      super(null);
      this.root = root;
      this.saved = saved;
    }

    @Override
    File root() {
      return root;
    }

    @Override
    String recoveryBoot() {
      return saved.boot();
    }

    @Override
    public SessionObservation observeRecordedSession(WebSessionProof.Saved value) {
      return observed;
    }
  }

  private Kernel kernel() throws java.io.IOException {
    return new Kernel(temporary.newFolder(), SAVED);
  }

  @Test
  public void missingEvidenceIsReconstructedFromTheProvenBirth() throws Exception {
    Kernel kernel = kernel();
    assertTrue(kernel.attestRecoveredSession(SAVED));
    assertEquals("42", Files.readString(new File(kernel.root(), ".dsha-web.pid").toPath()).trim());
    assertEquals(
        "42 123", Files.readString(new File(kernel.root(), ".dsha-web.identity").toPath()).trim());
  }

  @Test
  public void matchingEvidenceIsLeftUntouched() throws Exception {
    Kernel kernel = kernel();
    Files.writeString(new File(kernel.root(), ".dsha-web.pid").toPath(), "42\n");
    Files.writeString(new File(kernel.root(), ".dsha-web.identity").toPath(), "42 123\n");
    assertTrue(kernel.attestRecoveredSession(SAVED));
    assertEquals("42\n", Files.readString(new File(kernel.root(), ".dsha-web.pid").toPath()));
    assertEquals(
        "42 123\n", Files.readString(new File(kernel.root(), ".dsha-web.identity").toPath()));
  }

  @Test
  public void anotherInstancePidRecordIsNeverOverwritten() throws Exception {
    Kernel kernel = kernel();
    Files.writeString(new File(kernel.root(), ".dsha-web.pid").toPath(), "999\n");
    assertFalse(kernel.attestRecoveredSession(SAVED));
    assertEquals("999\n", Files.readString(new File(kernel.root(), ".dsha-web.pid").toPath()));
    assertFalse(new File(kernel.root(), ".dsha-web.identity").exists());
  }

  @Test
  public void staleIdentityForTheSamePidIsRewritten() throws Exception {
    Kernel kernel = kernel();
    Files.writeString(new File(kernel.root(), ".dsha-web.pid").toPath(), "42\n");
    Files.writeString(new File(kernel.root(), ".dsha-web.identity").toPath(), "42 999\n");
    assertTrue(kernel.attestRecoveredSession(SAVED));
    assertEquals(
        "42 123", Files.readString(new File(kernel.root(), ".dsha-web.identity").toPath()).trim());
  }

  @Test
  public void unprovenSessionWritesNothing() throws Exception {
    Kernel kernel = kernel();
    kernel.observed = WebProcessManager.SessionObservation.UNKNOWN;
    assertFalse(kernel.attestRecoveredSession(SAVED));
    assertFalse(new File(kernel.root(), ".dsha-web.pid").exists());
    assertFalse(new File(kernel.root(), ".dsha-web.identity").exists());
  }
}
