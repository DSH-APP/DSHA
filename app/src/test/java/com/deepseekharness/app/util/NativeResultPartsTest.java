package com.deepseekharness.app.util;

import static org.junit.Assert.*;

import org.junit.Test;

/** 数据页结果卡字段拼装：计数行与主/投影错误合并顺序，空串不占行。 */
public final class NativeResultPartsTest {
  @Test
  public void metaUsesExistingTemplateAndKeepsBytesVerbatim() {
    assertEquals("3 项 · 1.5 MB", NativeResultParts.meta(3, "1.5 MB"));
    assertEquals("1 项 · 2 B", NativeResultParts.meta(1, "2 B"));
    assertEquals("0 项 · ", NativeResultParts.meta(0, ""));
  }

  @Test
  public void problemsKeepsMainErrorBeforeProjectionError() {
    assertEquals("导出失败：权限不足\n投影失败：目录不可写", NativeResultParts.problems("导出失败：权限不足", "投影失败：目录不可写"));
  }

  @Test
  public void problemsDropsEmptySide() {
    assertEquals("导出失败：权限不足", NativeResultParts.problems("导出失败：权限不足", "  "));
    assertEquals("投影失败：目录不可写", NativeResultParts.problems(null, "投影失败：目录不可写"));
    assertEquals("", NativeResultParts.problems(null, null));
    assertEquals("", NativeResultParts.problems("", ""));
  }
}
