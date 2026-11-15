package com.deepseekharness.app.util;

import static org.junit.Assert.*;

import com.deepseekharness.app.backup.BackupJson;
import java.io.InputStream;
import java.util.*;
import org.junit.Test;

public class PluginJournalNamesTest {
  /** 与 tools/test-plugin-transactions.py 读取同一份夹具，保证 Java / Python 两侧分类一致。 */
  @Test
  @SuppressWarnings("unchecked")
  public void sharedFixtureMatchesJavaClassifier() throws Exception {
    byte[] bytes;
    try (InputStream in = getClass().getResourceAsStream("/plugins/journal-names.json")) {
      assertNotNull("shared fixture missing", in);
      bytes = in.readAllBytes();
    }
    List<Object> cases = (List<Object>) BackupJson.read(bytes, 64 * 1024).get("cases");
    assertTrue(cases.size() >= 10);
    Set<PluginJournalNames.Kind> seen = EnumSet.noneOf(PluginJournalNames.Kind.class);
    for (Object item : cases) {
      Map<String, Object> value = (Map<String, Object>) item;
      String name = (String) value.get("name");
      PluginJournalNames.Kind expected =
          PluginJournalNames.Kind.valueOf((String) value.get("kind"));
      assertEquals(name, expected, PluginJournalNames.kind(name));
      seen.add(expected);
    }
    assertEquals(EnumSet.allOf(PluginJournalNames.Kind.class), seen);
  }

  @Test
  public void everyAtomicWriteResidueOfTheLegacyProofIsRecognised() {
    String id = UUID.randomUUID().toString();
    for (String suffix :
        new String[] {"", ".previous", ".tmp-" + id, ".previous-retained-" + id, ".restore-" + id})
      assertEquals(
          suffix,
          PluginJournalNames.Kind.LEGACY_PROOF,
          PluginJournalNames.kind(PluginJournalNames.LEGACY_PROOF + suffix));
    assertEquals(PluginJournalNames.Kind.FOREIGN, PluginJournalNames.kind(null));
    assertEquals(PluginJournalNames.Kind.TRANSACTION, PluginJournalNames.kind(id));
  }
}
