package com.deepseekharness.app.util;

import org.junit.Test;
import static org.junit.Assert.*;

public class VirtualScreenInputPolicyTest {
  @Test
  public void reportsMissingMembersInLookupOrder() {
    assertEquals("", VirtualScreenInputPolicy.missing(true, true, true, true));
    assertEquals(
        "InputManager.getInstance,InputEvent.setDisplayId(int)",
        VirtualScreenInputPolicy.missing(false, false, true, false));
    assertEquals(
        "InputManager.getInstance,InputManager.injectInputEvent(InputEvent,int),InputEvent.setDisplayId(int)",
        VirtualScreenInputPolicy.missing(false, false, false, false));
  }

  @Test
  public void distinguishesFoundButUncallableMembers() {
    // 查得到但调用不成立（新系统要求 ActivityThread 上下文）：不能与「查不到」混为一谈，
    // 也不能让同一个成员在清单里出现两次。
    assertEquals(
        "InputManager.getInstance(invoke-failed)",
        VirtualScreenInputPolicy.missing(true, false, true, true));
    // 「查不到」与「调用不成立」互斥，同一个成员不会出现两次
    assertEquals(
        "InputManager.getInstance,InputManager.injectInputEvent(InputEvent,int)",
        VirtualScreenInputPolicy.missing(false, false, false, true));
  }

  @Test
  public void keepsCommandBudgetWellUnderTheHeartbeatBudget() {
    assertEquals(2500L, VirtualScreenInputPolicy.commandTimeoutMs(0));
    assertEquals(4000L, VirtualScreenInputPolicy.commandTimeoutMs(1500));
    assertEquals(5500L, VirtualScreenInputPolicy.commandTimeoutMs(3000));
    assertEquals(6000L, VirtualScreenInputPolicy.commandTimeoutMs(99999));
  }

  @Test
  public void picksReflectionThenCommandThenNothing() {
    assertEquals("reflection", VirtualScreenInputPolicy.backend(true, true, true, false));
    assertEquals("reflection", VirtualScreenInputPolicy.backend(true, true, true, true));
    // 任意一个隐藏成员缺失都不能用反射后端 —— 这正是 issue #113 的连坐点
    assertEquals("input-command", VirtualScreenInputPolicy.backend(false, true, true, true));
    assertEquals("input-command", VirtualScreenInputPolicy.backend(true, false, true, true));
    assertEquals("input-command", VirtualScreenInputPolicy.backend(true, true, false, true));
    // 命令也不可用时不是「反射降级」，而是没有后端：创建仍要成功，只是不能注入输入
    assertEquals("unavailable", VirtualScreenInputPolicy.backend(false, true, true, false));
  }

  @Test
  public void buildsDisplayScopedCommandArguments() {
    assertArrayEquals(
        new String[] {"/system/bin/input", "-d", "7", "tap", "100.5", "200.0"},
        VirtualScreenInputPolicy.tapArgs(7, 100.5f, 200f));
    assertArrayEquals(
        new String[] {"/system/bin/input", "-d", "3", "swipe", "1.0", "2.0", "30.0", "40.0", "300"},
        VirtualScreenInputPolicy.swipeArgs(3, 1f, 2f, 30f, 40f, 300));
    assertArrayEquals(
        new String[] {"/system/bin/input", "-d", "3", "keyevent", "4"},
        VirtualScreenInputPolicy.keyArgs(3, 4));
  }

  @Test
  public void clampsDurationAndFormatsCoordinatesLocaleIndependently() {
    assertEquals(1, VirtualScreenInputPolicy.duration(0));
    assertEquals(1, VirtualScreenInputPolicy.duration(-5));
    assertEquals(3000, VirtualScreenInputPolicy.duration(99999));
    assertEquals(300, VirtualScreenInputPolicy.duration(300));
    assertEquals("0.0", VirtualScreenInputPolicy.coordinate(Float.NaN));
    assertEquals("0.0", VirtualScreenInputPolicy.coordinate(Float.POSITIVE_INFINITY));
    assertEquals("0.0", VirtualScreenInputPolicy.coordinate(-5f));
    assertEquals("12.3", VirtualScreenInputPolicy.coordinate(12.34f));
    // 向下取整：core 用 n < edge 校验，四舍五入会得到正好等于显示边缘的 1008.0
    assertEquals("1007.9", VirtualScreenInputPolicy.coordinate(1007.96f));
    assertEquals("2.9", VirtualScreenInputPolicy.coordinate(2.9999f));
  }

  @Test
  public void classifiesTapByMovementOnly() {
    assertTrue(VirtualScreenInputPolicy.isTap(10f, 10f, 10f, 10f));
    assertTrue(VirtualScreenInputPolicy.isTap(10f, 10f, 11f, 11f));
    assertFalse(VirtualScreenInputPolicy.isTap(10f, 10f, 40f, 10f));
  }
}
