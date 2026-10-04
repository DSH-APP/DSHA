package com.deepseekharness.app.util;

import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

/** GitHub 引用；保留分支/标签和子目录，不能把任意网站误当作 owner/repo。 */
public final class GitHubRef {
  public final String owner, repo, subdir, revision, kind, treePath, archiveUrl;
  public final boolean valid;

  private GitHubRef(
      String owner,
      String repo,
      String revision,
      String subdir,
      String kind,
      String treePath,
      String archiveUrl) {
    this.owner = owner;
    this.repo = repo;
    this.revision = revision;
    this.subdir = subdir;
    this.kind = kind;
    this.treePath = treePath;
    this.archiveUrl = archiveUrl;
    this.valid = !owner.isEmpty() && !repo.isEmpty();
  }

  public static GitHubRef invalid() {
    return new GitHubRef("", "", "", "", "", "", "");
  }

  public static GitHubRef parse(String input) {
    if (input == null || input.trim().isEmpty()) return invalid();
    String value = input.trim();
    try {
      String host = "github.com", path;
      if (value.startsWith("git@github.com:")) {
        path = value.substring("git@github.com:".length());
        if (path.contains("#") || path.contains("?")) return invalid();
      } else {
        if (value.matches("(?i)^(www\\.)?github\\.com/.*")
            || value.startsWith("codeload.github.com/")) value = "https://" + value;
        if (value.contains("://")) {
          URI uri = new URI(value);
          if (!("https".equalsIgnoreCase(uri.getScheme())
                  || "http".equalsIgnoreCase(uri.getScheme()))
              || uri.getUserInfo() != null
              || uri.getPort() != -1) return invalid();
          host = uri.getHost() == null ? "" : uri.getHost().toLowerCase(java.util.Locale.ROOT);
          if (!(host.equals("github.com")
              || host.equals("www.github.com")
              || host.equals("codeload.github.com"))) return invalid();
          path = uri.getRawPath();
        } else {
          if (value.contains(":") || value.contains("@")) return invalid();
          path = value.split("[?#]", 2)[0];
        }
      }
      path = path.replaceFirst("^/", "").replaceFirst("/+$", "");
      String[] p = path.split("/", -1);
      if (p.length < 2) return invalid();
      for (int i = 0; i < p.length; i++) {
        p[i] = URLDecoder.decode(p[i].replace("+", "%2B"), StandardCharsets.UTF_8.name());
        if (p[i].isEmpty()
            || p[i].equals(".")
            || p[i].equals("..")
            || p[i].contains("\\")
            || p[i].matches(".*[\\p{Cntrl}].*")) return invalid();
      }
      String owner = p[0], repo = p[1].replaceFirst("\\.git$", "");
      if (!owner.matches("[A-Za-z0-9][A-Za-z0-9-]{0,38}")
          || !repo.matches("[A-Za-z0-9_.-]+")
          || repo.equals(".")
          || repo.equals("..")) return invalid();
      if (p.length == 2 && !host.equals("codeload.github.com"))
        return new GitHubRef(owner, repo, "HEAD", "", "head", "", "");
      if (p.length >= 4 && (p[2].equals("tree") || p[2].equals("blob"))) {
        int end = p.length - (p[2].equals("blob") ? 1 : 0);
        if (end < 4) return invalid();
        String tail = String.join("/", Arrays.copyOfRange(p, 3, end));
        if (!safeRelative(tail)) return invalid();
        return new GitHubRef(
            owner, repo, p[3], String.join("/", Arrays.copyOfRange(p, 4, end)), "tree", tail, "");
      }
      if (p.length == 4 && p[2].equals("releases") && p[3].equals("latest"))
        return new GitHubRef(owner, repo, "latest", "", "release", "", "");
      if (p.length >= 5 && p[2].equals("releases") && p[3].equals("tag")) {
        String tag = String.join("/", Arrays.copyOfRange(p, 4, p.length));
        if (!safeRelative(tag)) return invalid();
        return new GitHubRef(owner, repo, tag, "", "release", "", "");
      }
      boolean archive = p.length >= 4 && p[2].equals("archive");
      boolean release = p.length >= 6 && p[2].equals("releases") && p[3].equals("download");
      boolean latest =
          p.length >= 6
              && p[2].equals("releases")
              && p[3].equals("latest")
              && p[4].equals("download");
      boolean codeload =
          host.equals("codeload.github.com")
              && p.length >= 4
              && (p[2].equals("tar.gz") || p[2].equals("zip"));
      if (archive || release || latest || codeload) {
        if (!safeRelative(String.join("/", p))) return invalid();
        String url =
            value.contains("://")
                ? value.replaceFirst("(?i)^http:", "https:")
                : "https://github.com/" + path;
        return new GitHubRef(owner, repo, "", "", "archive", "", url);
      }
    } catch (Exception ignored) {
    }
    return invalid();
  }

  private static boolean safeRelative(String path) {
    for (String part : path.split("/", -1))
      if (part.isEmpty() || part.equals(".") || part.equals("..")) return false;
    return true;
  }

  public boolean sameRepo(GitHubRef other) {
    return other != null
        && valid
        && other.valid
        && owner.equalsIgnoreCase(other.owner)
        && repo.equalsIgnoreCase(other.repo);
  }

  @Override
  public String toString() {
    return valid
        ? owner + "/" + repo + (treePath.isEmpty() ? "" : "/tree/" + treePath)
        : "(invalid)";
  }
}
