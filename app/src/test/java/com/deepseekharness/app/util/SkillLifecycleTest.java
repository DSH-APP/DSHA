package com.deepseekharness.app.util;

import static org.junit.Assert.*;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import org.junit.Test;

public final class SkillLifecycleTest {
  private static final long DAY = SkillLifecycle.DAY_MILLIS;

  @Test
  public void togglesSingleFileNamesBothWaysAndRejectsOthers() throws Exception {
    assertEquals("notes.md.disabled", SkillLifecycle.toggledFile("notes.md"));
    assertEquals("notes.md", SkillLifecycle.toggledFile("notes.md.disabled"));
    assertTrue(SkillLifecycle.disabledFile("notes.md.disabled"));
    assertFalse(SkillLifecycle.disabledFile(".md.disabled"));
    assertFalse(SkillLifecycle.disabledFile("notes.md"));
    for (String bad : new String[] {"notes.txt", ".md", ".hidden.md", "a/b.md", "", ".."})
      try {
        SkillLifecycle.toggledFile(bad);
        fail(bad);
      } catch (IOException expected) {
        // 不是可切换的单文件技能。
      }
  }

  @Test
  public void retentionDefaultsToThirtyAndRejectsOutOfRangeInput() {
    assertEquals(30, SkillLifecycle.DEFAULT_RETENTION_DAYS);
    assertEquals(30, SkillLifecycle.retention(0));
    assertEquals(30, SkillLifecycle.retention(366));
    assertEquals(7, SkillLifecycle.retention(7));
    assertEquals(365, SkillLifecycle.retention(365));
    assertEquals(14, SkillLifecycle.parseRetention(" 14 "));
    assertEquals(1, SkillLifecycle.parseRetention("1"));
    for (String bad : new String[] {"", "0", "366", "-3", "1e2", "７", "abc", "1000", null})
      assertEquals(bad, -1, SkillLifecycle.parseRetention(bad));
  }

  @Test
  public void expiresOnlyAfterTheRetentionWindowAndNeverOnClockRollback() {
    long deleted = 1_000_000L * DAY;
    assertFalse(SkillLifecycle.expired(deleted, deleted + 30 * DAY - 1, 30));
    assertTrue(SkillLifecycle.expired(deleted, deleted + 30 * DAY, 30));
    assertTrue(SkillLifecycle.expired(deleted, deleted + 7 * DAY, 7));
    assertFalse(SkillLifecycle.expired(deleted, deleted - DAY, 1));
    assertFalse(SkillLifecycle.expired(0, deleted, 1));
    assertEquals(30, SkillLifecycle.remainingDays(deleted, deleted, 30));
    assertEquals(30, SkillLifecycle.remainingDays(deleted, deleted + 1, 30));
    assertEquals(1, SkillLifecycle.remainingDays(deleted, deleted + 30 * DAY - 1, 30));
    assertEquals(0, SkillLifecycle.remainingDays(deleted, deleted + 31 * DAY, 30));
    assertEquals(30, SkillLifecycle.remainingDays(deleted, deleted - 5 * DAY, 30));
  }

  @Test
  public void trashMetaRoundTripsAndRejectsTampering() throws Exception {
    var meta =
        SkillLifecycle.TrashMeta.of(
            "web-scraper", "web-scraper", SkillLifecycle.Kind.DIRECTORY, 42);
    var decoded = SkillLifecycle.TrashMeta.decode(meta.encode());
    assertEquals("web-scraper", decoded.member);
    assertEquals(SkillLifecycle.Kind.DIRECTORY, decoded.kind);
    assertEquals(42, decoded.deletedAt);
    String good = new String(meta.encode(), StandardCharsets.UTF_8);
    for (String bad :
        new String[] {
          good.replace("member=web-scraper", "member=../outside"),
          good.replace("member=web-scraper", "member=.system"),
          good.replace("name=web-scraper", "name=Web Scraper"),
          good.replace("version=1", "version=2"),
          good.replace("kind=directory", "kind=link"),
          good.replace("deleted=42", "deleted=soon"),
          good + "member=again\n",
          good.substring(0, good.length() - 1),
          ""
        })
      try {
        SkillLifecycle.TrashMeta.decode(bad.getBytes(StandardCharsets.UTF_8));
        fail(bad);
      } catch (IOException expected) {
        assertEquals("SKILL_TRASH_META", expected.getMessage());
      }
    try {
      SkillLifecycle.TrashMeta.of("notes.txt", "notes", SkillLifecycle.Kind.FILE, 1);
      fail();
    } catch (IOException expected) {
      // 单文件技能必须是 .md 或 .md.disabled。
    }
  }
}
