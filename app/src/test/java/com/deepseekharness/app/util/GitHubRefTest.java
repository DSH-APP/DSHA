package com.deepseekharness.app.util;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/** GitHubRef：常见链接形态的解析不变式（纯逻辑安全网）。 */
public class GitHubRefTest {

  @Test
  public void shorthand() {
    GitHubRef r = GitHubRef.parse("owner/repo");
    assertTrue(r.valid);
    assertEquals("owner", r.owner);
    assertEquals("repo", r.repo);
    assertEquals("", r.subdir);
  }

  @Test
  public void fullUrl() {
    GitHubRef r = GitHubRef.parse("https://github.com/mexiaosqwq/dsh-web-mobile");
    assertTrue(r.valid);
    assertEquals("mexiaosqwq", r.owner);
    assertEquals("dsh-web-mobile", r.repo);
    assertEquals("", r.subdir);
  }

  @Test
  public void wwwUrl() {
    GitHubRef r = GitHubRef.parse("http://www.github.com/owner/repo");
    assertTrue(r.valid);
    assertEquals("owner", r.owner);
    assertEquals("repo", r.repo);
  }

  @Test
  public void treeWithSubdir() {
    GitHubRef r = GitHubRef.parse("https://github.com/owner/mono/tree/main/packages/foo-bar");
    assertTrue(r.valid);
    assertEquals("owner", r.owner);
    assertEquals("mono", r.repo);
    assertEquals("packages/foo-bar", r.subdir);
  }

  @Test
  public void treeNoSubdir() {
    GitHubRef r = GitHubRef.parse("https://github.com/owner/repo/tree/dev");
    assertTrue(r.valid);
    assertEquals("owner", r.owner);
    assertEquals("repo", r.repo);
    assertEquals("", r.subdir);
  }

  @Test
  public void sshForm() {
    GitHubRef r = GitHubRef.parse("git@github.com:owner/repo.git");
    assertTrue(r.valid);
    assertEquals("owner", r.owner);
    assertEquals("repo", r.repo);
  }

  @Test
  public void gitSuffix() {
    GitHubRef r = GitHubRef.parse("https://github.com/owner/repo.git");
    assertTrue(r.valid);
    assertEquals("repo", r.repo);
  }

  @Test
  public void archiveLink() {
    GitHubRef r = GitHubRef.parse("https://github.com/owner/repo/archive/refs/heads/main.tar.gz");
    assertTrue(r.valid);
    assertEquals("owner", r.owner);
    assertEquals("repo", r.repo);
  }

  @Test
  public void queryAndFragmentIgnored() {
    GitHubRef r = GitHubRef.parse("https://github.com/owner/repo?tab=readme-ov-file#readme");
    assertTrue(r.valid);
    assertEquals("owner", r.owner);
    assertEquals("repo", r.repo);
  }

  @Test
  public void trailingSlash() {
    GitHubRef r = GitHubRef.parse("https://github.com/owner/repo/");
    assertTrue(r.valid);
    assertEquals("owner", r.owner);
    assertEquals("repo", r.repo);
  }

  @Test
  public void invalidInputs() {
    assertFalse(GitHubRef.parse(null).valid);
    assertFalse(GitHubRef.parse("").valid);
    assertFalse(GitHubRef.parse("   ").valid);
    assertFalse(GitHubRef.parse("justonepart").valid);
  }

  @Test
  public void sameRepoCaseInsensitive() {
    GitHubRef a = GitHubRef.parse("https://github.com/Owner/Repo");
    GitHubRef b = GitHubRef.parse("owner/repo");
    assertTrue(a.valid && b.valid);
    assertTrue(a.sameRepo(b));
  }
}
