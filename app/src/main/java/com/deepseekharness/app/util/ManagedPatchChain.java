package com.deepseekharness.app.util;

import java.io.IOException;
import java.util.List;

/** Rebuild a whole module patch chain in memory; no partial canonical restore is published. */
public final class ManagedPatchChain {
  public record Patch(String before, String after) {}

  public record Recipe(
      String id, List<Patch> patches, List<String> markers, List<Patch> previousPatches) {
    public Recipe(String id, List<Patch> patches, List<String> markers) {
      this(id, patches, markers, List.of());
    }

    public Recipe {
      patches = List.copyOf(patches);
      markers = List.copyOf(markers);
      previousPatches = List.copyOf(previousPatches);
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
      // A predecessor is an exact signed replacement set, not a search/replace
      // heuristic. Only a complete old form can be restored in memory. Partial
      // or modified managed modules still require the bundled canonical path.
      if (!recipe.previousPatches().isEmpty() && complete(updated, recipe.previousPatches())) {
        for (int at = recipe.previousPatches().size() - 1; at >= 0; at--) {
          Patch previous = recipe.previousPatches().get(at);
          updated = updated.replace(previous.after(), previous.before());
        }
      } else if (previousFragment(updated, recipe)) {
        throw new IllegalArgumentException("PATCH_PREDECESSOR_PARTIAL");
      }
      for (Patch patch : recipe.patches())
        updated = ExactTextPatch.apply(updated, patch.before(), patch.after());
    }
    return updated;
  }

  private static boolean previousFragment(String source, Recipe recipe) {
    for (Patch previous : recipe.previousPatches()) {
      if (source.contains(previous.after())
          && recipe.patches().stream()
              .noneMatch(current -> current.after().equals(previous.after()))) return true;
    }
    return false;
  }

  private static boolean complete(String source, List<Patch> patches) {
    for (Patch patch : patches) {
      if (patch.before().isEmpty()
          || patch.after().isEmpty()
          || patch.before().equals(patch.after())
          || count(source, patch.after()) != 1
          || count(source, patch.before()) != count(patch.after(), patch.before())) return false;
    }
    return true;
  }

  private static int count(String text, String part) {
    int found = 0, position = 0;
    while ((position = text.indexOf(part, position)) >= 0) {
      found++;
      position += part.length();
    }
    return found;
  }
}
