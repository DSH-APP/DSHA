package com.deepseekharness.app.util;

import org.junit.Test;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.Assert.*;

public class ManagedPatchChainTest {
  @Test
  public void canonicalRepairReappliesEveryPatchForTheModuleAndIsIdempotent() throws Exception {
    var chain =
        List.of(
            new ManagedPatchChain.Recipe(
                "composer",
                List.of(new ManagedPatchChain.Patch("composer-old", "composer-new")),
                List.of()),
            new ManagedPatchChain.Recipe(
                "queue",
                List.of(new ManagedPatchChain.Patch("queue-old", "queue-new")),
                List.of()));
    AtomicInteger reads = new AtomicInteger();
    String original = "composer-new incompatible-queue";
    String updated =
        ManagedPatchChain.apply(
            original,
            chain,
            () -> {
              reads.incrementAndGet();
              return "composer-old queue-old";
            });
    assertEquals("composer-new queue-new", updated);
    assertEquals(1, reads.get());
    assertEquals(
        updated,
        ManagedPatchChain.apply(
            updated,
            chain,
            () -> {
              throw new AssertionError("no restore for current chain");
            }));
    assertEquals("composer-new incompatible-queue", original);
  }

  @Test
  public void failedFullChainCannotReturnAPartialRestore() {
    var chain =
        List.of(
            new ManagedPatchChain.Recipe(
                "one", List.of(new ManagedPatchChain.Patch("before", "after")), List.of()),
            new ManagedPatchChain.Recipe(
                "two", List.of(new ManagedPatchChain.Patch("missing", "changed")), List.of()));
    assertThrows(
        java.io.IOException.class, () -> ManagedPatchChain.apply("damaged", chain, () -> "before"));
  }
}
