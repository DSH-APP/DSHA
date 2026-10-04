package com.deepseekharness.app.util;

import static org.junit.Assert.*;
import java.util.LinkedHashMap;
import org.junit.Test;

public final class StorageCategoryLabelsTest {
  @Test
  public void reorderedCategoriesKeepTheirOwnIdentity() {
    var shuffled = new LinkedHashMap<String, String>();
    for (String key : new String[] {"cache", "user-data-v5", "host-backup-operations", "linux"})
      shuffled.put(key, StorageCategoryLabels.label(key, true));
    assertEquals("App cache", shuffled.get("cache"));
    assertEquals("Persistent user data", shuffled.get("user-data-v5"));
    assertEquals("Backup copies", shuffled.get("host-backup-operations"));
    assertEquals("Current Linux and DSH", shuffled.get("linux"));
    assertEquals("应用缓存", StorageCategoryLabels.label("cache", false));
  }

  @Test
  public void unknownKeyIsDisplayedVerbatimInBothLanguages() {
    assertEquals("user custom 文件", StorageCategoryLabels.label("user custom 文件", false));
    assertEquals("user custom 文件", StorageCategoryLabels.label("user custom 文件", true));
    assertEquals("", StorageCategoryLabels.label("", true));
  }
}
