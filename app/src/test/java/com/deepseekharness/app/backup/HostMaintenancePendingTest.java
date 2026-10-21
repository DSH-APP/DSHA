package com.deepseekharness.app.backup;

import static org.junit.Assert.*;
import java.io.File;
import java.io.IOException;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.Test;

/** Exercises the production read-only domain sequence with synthetic probes only. */
public class HostMaintenancePendingTest {
  private static final File FILES = new File("synthetic-files");

  @Test
  public void productionInventoryHasEightRecoverableDomainsAndExactWeights() {
    assertEquals(
        List.of(
            "cold-install",
            "legacy-maintenance",
            "legacy-runtime",
            "managed-runtime",
            "startup-configuration",
            "plugin-install",
            "environment-rebuild",
            "host-data"),
        HostMaintenancePending.RECOVERABLE.stream().map(domain -> domain.name).toList());
    assertEquals(
        List.of(false, false, false, true, false, false, false, true),
        HostMaintenancePending.RECOVERABLE.stream().map(domain -> domain.countEach).toList());
    assertFalse(
        HostMaintenancePending.RECOVERABLE.stream()
            .anyMatch(domain -> domain.name.equals("bounded-guest")));
  }

  @Test
  public void ordinaryGateReturnsFirstDomainAndDoesNotProbeLaterOnes() {
    AtomicInteger later = new AtomicInteger();
    var domains =
        List.of(
            new HostMaintenancePending.Domain("legacy-runtime", root -> List.of(), false),
            new HostMaintenancePending.Domain(
                "plugin-install", root -> List.of("first", "second"), false),
            new HostMaintenancePending.Domain(
                "host-data",
                root -> {
                  later.incrementAndGet();
                  return List.of("later");
                },
                true));
    var check = HostMaintenancePending.inspectRecoverable(FILES, domains);
    assertTrue(check.blocked);
    assertEquals("plugin-install", check.domain);
    assertEquals("first", check.operation);
    assertEquals(0, later.get());
  }

  @Test
  public void pluginCandidatesCountAsOneButHostAndManagedCountEach() throws Exception {
    var plugin =
        new HostMaintenancePending.Domain("plugin-install", root -> List.of("a", "b", "c"), false);
    assertFalse(HostMaintenancePending.moreThanOneRecoverable(FILES, List.of(plugin)));
    var legacy = new HostMaintenancePending.Domain("legacy-runtime", root -> List.of("old"), false);
    assertTrue(HostMaintenancePending.moreThanOneRecoverable(FILES, List.of(plugin, legacy)));
    var host = new HostMaintenancePending.Domain("host-data", root -> List.of("a", "b"), true);
    var runtime =
        new HostMaintenancePending.Domain("managed-runtime", root -> List.of("a", "b"), true);
    assertTrue(HostMaintenancePending.moreThanOneRecoverable(FILES, List.of(host)));
    assertTrue(HostMaintenancePending.moreThanOneRecoverable(FILES, List.of(runtime)));
  }

  @Test
  public void multipleCountStopsAtTwoAndUnreadableStillFailsClosed() throws Exception {
    AtomicInteger later = new AtomicInteger();
    var first = new HostMaintenancePending.Domain("host-data", root -> List.of("a", "b"), true);
    var unreadable =
        new HostMaintenancePending.Domain(
            "startup-configuration",
            root -> {
              later.incrementAndGet();
              throw new IOException("damaged journal");
            },
            false);
    assertTrue(HostMaintenancePending.moreThanOneRecoverable(FILES, List.of(first, unreadable)));
    assertEquals(0, later.get());
    assertThrows(
        IOException.class,
        () -> HostMaintenancePending.moreThanOneRecoverable(FILES, List.of(unreadable)));
    var display = HostMaintenancePending.inspectRecoverable(FILES, List.of(unreadable));
    assertTrue(display.blocked);
    assertEquals("startup-configuration", display.domain);
    assertEquals("unknown", display.operation);
    assertTrue(display.error.contains("damaged journal"));
  }
}
