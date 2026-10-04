package com.deepseekharness.app.core;

import java.io.IOException;
import java.util.*;
import org.junit.Test;
import static org.junit.Assert.*;

public class StartupPipelineTest {
  static final class Fixture
      implements StartupPipeline.Runtime, StartupPipeline.Diagnostics, StartupPipeline.Actions {
    final List<String> calls = new ArrayList<>();
    boolean current = true;
    String failure = "";

    void step(String name) throws IOException {
      calls.add(name);
      if (name.equals(failure)) throw new IOException(name);
    }

    public void credentials() throws Exception {
      step("credentials");
    }

    public void stopLan() {
      calls.add("lan-stop");
    }

    public String stopWeb() throws IOException {
      step("stop");
      return "";
    }

    public void tools() throws Exception {
      step("tools");
    }

    public boolean ready() {
      return true;
    }

    public boolean offlineBundle() {
      return true;
    }

    public void extract() throws Exception {
      step("extract");
    }

    public String migration(String id) throws Exception {
      step("migration");
      return "DSHA_RC1_MIGRATION={\"status\":\"already\",\"protectionComplete\":true}";
    }

    public String register() throws Exception {
      step("register");
      return "BUILTIN_REGISTER_OK";
    }

    public String legacyPlugins() throws Exception {
      step("legacy");
      return "ok";
    }

    public String recordId() {
      return "fixture";
    }

    public void message(long g, String s) {}

    public void issue(long g, String n, String s) {
      calls.add("issue");
    }

    public boolean current() {
      return current;
    }

    public void stage(String value) {}

    public void status(String value) {}

    public void releasePrior() {
      calls.add("release-prior");
    }

    public void recoverData() throws Exception {
      step("recover");
    }

    public void cleanup() throws IOException {
      step("cleanup");
    }

    public void beforeLaunch(String id) throws IOException {
      step("activate");
    }

    public String safeProfile() throws IOException {
      step("safe-profile");
      return "dsha-recovery-0123456789abcdef";
    }

    public boolean unlockStartup() throws IOException {
      step("unlock");
      return current;
    }

    public void checkpoint(String id) throws Exception {
      step("checkpoint");
    }
  }

  @Test
  public void ordinaryPreparationIsOrderedAndDoesNotOwnProcessLaunch() throws Exception {
    Fixture f = new Fixture();
    assertEquals("web", new StartupPipeline(f, f).prepare(1, false, f));
    assertEquals(
        List.of(
            "credentials",
            "lan-stop",
            "stop",
            "release-prior",
            "tools",
            "recover",
            "cleanup",
            "migration",
            "register",
            "legacy",
            "activate",
            "unlock",
            "checkpoint"),
        f.calls);
  }

  @Test
  public void credentialsOrMigrationFailureCannotPublishOrLaunch() throws Exception {
    for (String stage : List.of("credentials", "migration")) {
      Fixture f = new Fixture();
      f.failure = stage;
      assertThrows(IOException.class, () -> new StartupPipeline(f, f).prepare(1, false, f));
      assertFalse(f.calls.contains("unlock"));
      assertFalse(f.calls.contains("activate"));
      if (stage.equals("credentials")) assertEquals(List.of("credentials"), f.calls);
    }
  }

  @Test
  public void safeProfileSkipsUserPluginActivationAndSnapshots() throws Exception {
    Fixture f = new Fixture();
    assertEquals("dsha-recovery-0123456789abcdef", new StartupPipeline(f, f).prepare(2, true, f));
    assertFalse(f.calls.contains("register"));
    assertFalse(f.calls.contains("legacy"));
    assertFalse(f.calls.contains("activate"));
    assertFalse(f.calls.contains("checkpoint"));
    assertTrue(f.calls.contains("migration"));
    assertTrue(f.calls.contains("unlock"));
  }

  @Test
  public void staleGenerationPerformsNoSteps() throws Exception {
    Fixture f = new Fixture();
    f.current = false;
    assertNull(new StartupPipeline(f, f).prepare(1, false, f));
    assertTrue(f.calls.isEmpty());
  }
}
