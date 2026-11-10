package com.deepseekharness.app.util;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.util.List;
import java.util.Set;
import org.junit.Test;

public final class ShizukuManagersTest {
  @Test
  public void permissionDeclarerWinsOverCompatibleManager() {
    ShizukuManagers.Selection selected =
        ShizukuManagers.choose("moe.shizuku.privileged.api", Set.of(ShizukuManagers.STELLAR));
    assertEquals("moe.shizuku.privileged.api", selected.packageName());
    assertEquals(ShizukuManagers.Kind.SHIZUKU_PROTOCOL, selected.kind());
  }

  @Test
  public void stellarIsSelectedWithoutPermissionDeclarer() {
    ShizukuManagers.Selection selected =
        ShizukuManagers.choose(null, Set.of(ShizukuManagers.STELLAR));
    assertEquals(ShizukuManagers.STELLAR, selected.packageName());
    assertEquals(ShizukuManagers.Kind.STELLAR, selected.kind());
    assertTrue(ShizukuManagers.knownPackages().contains(ShizukuManagers.STELLAR));
  }

  @Test
  public void blankPermissionOwnerFallsBackToWhitelist() {
    for (String owner : new String[] {"", " ", "\t"}) {
      ShizukuManagers.Selection selected =
          ShizukuManagers.choose(owner, Set.of(ShizukuManagers.STELLAR));
      assertEquals(ShizukuManagers.STELLAR, selected.packageName());
      assertEquals(ShizukuManagers.Kind.STELLAR, selected.kind());
    }
  }

  @Test
  public void nothingInstalledSelectsNone() {
    for (String owner : new String[] {null, "", " "}) {
      ShizukuManagers.Selection selected = ShizukuManagers.choose(owner, Set.of());
      assertNull(selected.packageName());
      assertEquals(ShizukuManagers.Kind.NONE, selected.kind());
    }
    // 未知应用不算已安装的兼容管理器。
    assertEquals(
        ShizukuManagers.Kind.NONE,
        ShizukuManagers.choose(null, Set.of("example.unknown.manager")).kind());
    // 输入集合缺失时安全降级，不抛异常。
    assertEquals(ShizukuManagers.Kind.NONE, ShizukuManagers.choose(null, null).kind());
  }

  @Test
  public void whitelistOrderDecidesAmongInstalledManagers() {
    List<ShizukuManagers.Candidate> order =
        List.of(
            new ShizukuManagers.Candidate("example.first", ShizukuManagers.Kind.STELLAR),
            new ShizukuManagers.Candidate("example.second", ShizukuManagers.Kind.STELLAR));
    ShizukuManagers.Selection both =
        ShizukuManagers.choose(null, Set.of("example.second", "example.first", "other.app"), order);
    assertEquals("example.first", both.packageName());
    assertFalse("example.second".equals(both.packageName()));
    // 白名单第一项未安装时取下一个，不会越过顺序。
    assertEquals(
        "example.second",
        ShizukuManagers.choose(null, Set.of("example.second", "other.app"), order).packageName());
    // 已安装集合里没有白名单包时仍为 NONE。
    assertEquals(
        ShizukuManagers.Kind.NONE, ShizukuManagers.choose(null, Set.of("other.app"), order).kind());
  }

  @Test
  public void stellarPackageIdentity() {
    assertTrue(ShizukuManagers.isStellar(ShizukuManagers.STELLAR));
    assertFalse(ShizukuManagers.isStellar("moe.shizuku.privileged.api"));
    assertFalse(ShizukuManagers.isStellar(null));
  }
}
