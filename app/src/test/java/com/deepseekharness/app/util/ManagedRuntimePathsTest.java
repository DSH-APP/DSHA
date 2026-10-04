package com.deepseekharness.app.util;

import java.io.*;
import java.nio.file.Files;
import java.util.*;
import org.junit.*;
import org.junit.rules.TemporaryFolder;
import static org.junit.Assert.*;

public class ManagedRuntimePathsTest {
  @Rule public TemporaryFolder temporary = new TemporaryFolder();

  private static final class View implements ManagedRuntimePaths.View {
    final Set<String> links = new HashSet<>();
    final Map<String, String> targets = new HashMap<>();
    final Set<String> unreadable = new HashSet<>();

    public File[] list(File directory) {
      return unreadable.contains(directory.getAbsolutePath()) ? null : directory.listFiles();
    }

    public boolean symbolic(File file) {
      return links.contains(file.getAbsolutePath());
    }

    public boolean exists(File file) {
      return file.exists();
    }

    public String canonical(File file) throws IOException {
      return targets.getOrDefault(file.getAbsolutePath(), file.getCanonicalPath());
    }

    void link(File file, File target) throws IOException {
      Files.createDirectories(file.getParentFile().toPath());
      Files.writeString(file.toPath(), "host-shape link fixture");
      links.add(file.getAbsolutePath());
      targets.put(file.getAbsolutePath(), target.getCanonicalPath());
    }
  }

  private File root() throws IOException {
    File root = temporary.newFolder();
    Files.createDirectories(new File(root, ManagedRuntimeLayout.DSH).toPath());
    Files.createDirectories(new File(root, "usr/local/bin").toPath());
    return root;
  }

  @Test
  public void installedAndStagedSetsAreEquivalentWithTwoDistinctRoots() throws Exception {
    File live = root(), stage = root();
    View view = new View();
    for (String name : List.of("alpha", "@scope/beta")) {
      String relative = "usr/local/lib/node_modules/" + name;
      view.link(
          new File(live, relative),
          new File(live, ManagedRuntimeLayout.DSH + "/node_modules/" + name));
      view.link(
          new File(stage, relative),
          new File(stage, ManagedRuntimeLayout.DSH + "/node_modules/" + name));
    }
    view.link(
        new File(live, "usr/local/bin/dsh"),
        new File(live, ManagedRuntimeLayout.DSH + "/lib/bin.js"));
    view.link(
        new File(stage, "usr/local/bin/dsh"),
        new File(stage, ManagedRuntimeLayout.DSH + "/lib/bin.js"));
    Set<String> installed = new LinkedHashSet<>(ManagedRuntimePaths.enumerate(live, live, view));
    Set<String> staged = new LinkedHashSet<>(ManagedRuntimePaths.enumerate(stage, live, view));
    assertEquals(installed, staged);
    assertTrue(staged.contains("linux/ubuntu/usr/local/lib/node_modules/@scope/beta"));
    Set<String> expected = new LinkedHashSet<>();
    for (String path : ManagedRuntimeLayout.paths()) expected.add("linux/ubuntu/" + path);
    expected.addAll(
        List.of(
            "linux/ubuntu/usr/local/lib/node_modules/alpha",
            "linux/ubuntu/usr/local/lib/node_modules/@scope/beta",
            "linux/ubuntu/usr/local/bin/dsh",
            "linux/.offline-identity",
            "linux/.offline-extracted",
            "linux/.offline-version",
            "linux/.runtime-descriptor.json"));
    assertEquals(expected, staged);
  }

  @Test
  public void stageNeverOverridesLiveRegularOrUserOwnedAlias() throws Exception {
    File live = root(), stage = root(), outside = temporary.newFolder();
    View view = new View();
    for (String name : List.of("regular", "user-link", "new-alias"))
      view.link(
          new File(stage, "usr/local/lib/node_modules/" + name),
          new File(stage, ManagedRuntimeLayout.DSH + "/node_modules/" + name));
    File regular = new File(live, "usr/local/lib/node_modules/regular");
    Files.writeString(regular.toPath(), "user-original");
    File user = new File(live, "usr/local/lib/node_modules/user-link");
    view.link(user, new File(outside, "user-original"));
    List<String> paths = ManagedRuntimePaths.enumerate(stage, live, view);
    assertFalse(paths.contains("linux/ubuntu/usr/local/lib/node_modules/regular"));
    assertFalse(paths.contains("linux/ubuntu/usr/local/lib/node_modules/user-link"));
    assertTrue(paths.contains("linux/ubuntu/usr/local/lib/node_modules/new-alias"));
    assertEquals("user-original", Files.readString(regular.toPath()));
    assertTrue(user.exists());
  }

  @Test
  public void unreadableScopeCannotProducePartialManagedProof() throws Exception {
    File live = root(), stage = root();
    View view = new View();
    File scope = new File(stage, "usr/local/lib/node_modules/@scope");
    Files.createDirectories(scope.toPath());
    view.unreadable.add(scope.getAbsolutePath());
    assertThrows(IOException.class, () -> ManagedRuntimePaths.enumerate(stage, live, view));
    view.unreadable.clear();
    view.unreadable.add(new File(stage, "usr/local/lib/node_modules").getAbsolutePath());
    assertThrows(IOException.class, () -> ManagedRuntimePaths.enumerate(stage, live, view));
  }

  @Test
  public void linkedScopeOrUnmanagedNpmAliasIsRejectedBeforeScopeTraversal() throws Exception {
    File live = root(), stage = root();
    View view = new View();
    File npm = new File(stage, "usr/local/lib/node_modules/npm");
    view.link(npm, new File(live, "outside"));
    assertThrows(IOException.class, () -> ManagedRuntimePaths.enumerate(stage, live, view));
    Files.delete(npm.toPath());
    view.links.remove(npm.getAbsolutePath());
    File scope = new File(stage, "usr/local/lib/node_modules/@scope");
    view.link(scope, new File(live, "outside"));
    assertThrows(IOException.class, () -> ManagedRuntimePaths.enumerate(stage, live, view));
  }
}
