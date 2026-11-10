package com.deepseekharness.app.util;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

/**
 * 纯逻辑：选择 Shizuku 兼容管理器。
 *
 * <p>声明了 API_V23 权限的管理器优先；否则按固定白名单顺序取第一个已安装项。白名单新增包时必须同步
 * AndroidManifest 的 {@code <queries>}，否则 Android 11+ 包可见性会让安装检查查不到。
 */
public final class ShizukuManagers {
  public enum Kind {
    SHIZUKU_PROTOCOL,
    STELLAR,
    NONE
  }

  /** Stellar 的 applicationId；来源见 docs/design/onboarding-20261031/external-authorizations.md §2。 */
  public static final String STELLAR = "roro.stellar.manager";

  private static final List<Candidate> WHITELIST = List.of(new Candidate(STELLAR, Kind.STELLAR));
  private static final List<String> KNOWN_PACKAGES = packagesOf(WHITELIST);

  private ShizukuManagers() {}

  /** 兼容管理器白名单包名，顺序即优先级；调用方据此逐包检查是否已安装。 */
  public static List<String> knownPackages() {
    return KNOWN_PACKAGES;
  }

  private static List<String> packagesOf(List<Candidate> order) {
    List<String> packages = new ArrayList<>(order.size());
    for (Candidate candidate : order) packages.add(candidate.packageName());
    return List.copyOf(packages);
  }

  public static boolean isStellar(String packageName) {
    return STELLAR.equals(packageName);
  }

  /** 选中的管理器；未安装任何管理器时 packageName 为 null、kind 为 NONE。 */
  public record Selection(String packageName, Kind kind) {}

  public static Selection choose(String permissionOwner, Collection<String> installed) {
    return choose(permissionOwner, installed, WHITELIST);
  }

  /** 可替换白名单的重载，供单测覆盖优先级顺序；生产调用只用固定白名单。 */
  static Selection choose(
      String permissionOwner, Collection<String> installed, List<Candidate> order) {
    if (permissionOwner != null && !permissionOwner.isBlank())
      return new Selection(permissionOwner, Kind.SHIZUKU_PROTOCOL);
    if (installed != null && order != null)
      for (Candidate candidate : order)
        if (candidate != null
            && candidate.packageName() != null
            && installed.contains(candidate.packageName()))
          return new Selection(candidate.packageName(), candidate.kind());
    return new Selection(null, Kind.NONE);
  }

  /** 白名单条目；kind 由协议兼容性决定，不能只凭包名推断。 */
  record Candidate(String packageName, Kind kind) {}
}
