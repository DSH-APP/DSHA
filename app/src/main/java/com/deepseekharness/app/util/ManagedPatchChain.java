package com.deepseekharness.app.util;

import java.io.IOException;
import java.util.List;

/** Rebuild a whole module patch chain in memory; no partial canonical restore is published. */
public final class ManagedPatchChain {
  public record Patch(String before, String after) {}

  public record Recipe(String id, List<Patch> patches, List<String> markers) {
    public Recipe {
      patches = List.copyOf(patches);
      markers = List.copyOf(markers);
    }
  }

  public interface Canonical {
    String read() throws IOException;
  }

  private ManagedPatchChain() {}

  public static String apply(String source, List<Recipe> chain, Canonical canonical)
      throws IOException {
    try {
      return applyAll(source, chain);
    } catch (IllegalArgumentException mismatch) {
      String restored = canonical.read();
      if (restored == null) throw new IOException("PATCH_CANONICAL_MISSING", mismatch);
      try {
        return applyAll(restored, chain);
      } catch (IllegalArgumentException invalid) {
        throw new IOException("PATCH_CHAIN_MISMATCH", invalid);
      }
    }
  }

  private static String applyAll(String source, List<Recipe> chain) {
    String updated = source;
    for (Recipe recipe : chain) {
      if (!recipe.markers().isEmpty() && recipe.markers().stream().allMatch(updated::contains))
        continue;
      for (Patch patch : recipe.patches())
        updated = ExactTextPatch.apply(updated, patch.before(), patch.after());
    }
    return updated;
  }
}
