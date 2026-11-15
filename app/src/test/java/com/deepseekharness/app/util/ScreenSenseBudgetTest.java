package com.deepseekharness.app.util;

import static org.junit.Assert.*;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.List;
import org.junit.Test;

/**
 * 用真实 {@link ScreenSenseCodec} 编码 tools/screen-eval 生成的固定合成样本。摘要与 screen_eval.py 的 GOLDEN
 * 一致，任何一侧单独改编码都会失败；字符上限是回归闸，不是真机 token 或性能结论。
 */
public final class ScreenSenseBudgetTest {
  private static final String COMPACT_60_SHA256 =
      "c1ab04a8907d77ec863373f2ffc75e35d96620aba70aa8288f8f9f18103b80c0";
  private static final ScreenTarget TARGET = new ScreenTarget(0, 7, "com.example.chat");

  private static List<ScreenSenseState.Element> fixture() throws Exception {
    List<ScreenSenseState.Element> rows = new ArrayList<>();
    try (InputStream input =
            ScreenSenseBudgetTest.class.getResourceAsStream("screen-sense-synthetic-200.tsv");
        BufferedReader reader =
            new BufferedReader(
                new InputStreamReader(
                    java.util.Objects.requireNonNull(input, "fixture missing"),
                    StandardCharsets.UTF_8))) {
      for (String line; (line = reader.readLine()) != null; ) {
        if (line.isEmpty() || line.startsWith("#")) continue;
        String[] parts = line.split("\t", -1);
        assertEquals(line, 9, parts.length);
        rows.add(
            new ScreenSenseState.Element(
                parts[0],
                parts[1],
                parts[2],
                parts[3],
                parts[4],
                false,
                Integer.parseInt(parts[5]),
                Integer.parseInt(parts[6]),
                Integer.parseInt(parts[7]),
                Integer.parseInt(parts[8])));
      }
    }
    assertEquals(200, rows.size());
    return rows;
  }

  private static String encode(List<ScreenSenseState.Element> elements) {
    return ScreenSenseCodec.encode(
        new ScreenSenseState(
            1,
            "scope",
            TARGET,
            1080,
            2400,
            false,
            -1,
            "tree",
            elements.size(),
            false,
            "",
            elements));
  }

  private static String sha256(String value) throws Exception {
    byte[] digest =
        MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
    StringBuilder result = new StringBuilder();
    for (byte b : digest) result.append(String.format(java.util.Locale.ROOT, "%02x", b));
    return result.toString();
  }

  @Test
  public void realCodecMatchesOfflineEvaluatorGolden() throws Exception {
    assertEquals(COMPACT_60_SHA256, sha256(encode(fixture().subList(0, 60))));
  }

  @Test
  public void compactFullStateStaysWithinCharacterRegressionCeilings() throws Exception {
    List<ScreenSenseState.Element> rows = fixture();
    int[][] ceilings = {{60, 1000}, {120, 1900}, {200, 3200}};
    for (int[] ceiling : ceilings) {
      int chars = encode(rows.subList(0, ceiling[0])).length();
      assertTrue(ceiling[0] + " elements: " + chars + " chars", chars <= ceiling[1]);
    }
  }

  @Test
  public void textOnlyElementsCarryNoPlaceholderToken() {
    String encoded =
        encode(
            List.of(
                new ScreenSenseState.Element("k000", "T", "收藏", "", "", false, 0, 0, 0, 0),
                new ScreenSenseState.Element("k001", "B", "发送", "!", "c", false, 0, 0, 0, 0)));
    String[] lines = encoded.split("\n");
    assertEquals("k000 T \"收藏\"", lines[1]);
    assertEquals("k001 B \"发送\" !c", lines[2]);
  }

  @Test
  public void qualityDistinguishesEmptyPartialAndOk() {
    var element = new ScreenSenseState.Element("k000", "T", "a", "", "", false, 0, 0, 0, 0);
    var empty =
        new ScreenSenseState(1, "s", TARGET, 1, 1, false, -1, "tree", 3, false, "", List.of());
    var partialEmpty =
        new ScreenSenseState(1, "s", TARGET, 1, 1, false, -1, "tree", 3, true, "time", List.of());
    var ok =
        new ScreenSenseState(
            1, "s", TARGET, 1, 1, false, -1, "tree", 3, false, "", List.of(element));
    assertEquals("empty", empty.quality());
    assertEquals("partial", partialEmpty.quality());
    assertEquals("ok", ok.quality());
    assertTrue(ScreenSenseCodec.encode(empty).startsWith("S1 com.example.chat 1x1 q=empty "));
    assertTrue(
        ScreenSenseCodec.encode(partialEmpty)
            .contains(" q=partial f=0 ime=? src=tree v=3 why=time"));
  }

  @Test
  public void metadataReportsMonotonicTimingAndUnknownOnClockRegression() {
    var state =
        new ScreenSenseState(1, "s", TARGET, 1, 1, false, -1, "tree", 3, true, "nodes", List.of());
    ScreenSenseMetadata meta = ScreenSenseMetadata.of(state, 100, 130, 145);
    assertEquals("tree", meta.source);
    assertEquals("partial", meta.quality);
    assertTrue(meta.partial);
    assertEquals("nodes", meta.truncationReason);
    assertEquals(30, meta.readMs);
    assertEquals(15, meta.ageMs);
    ScreenSenseMetadata regressed = ScreenSenseMetadata.of(state, 100, 90, 80);
    assertEquals(ScreenSenseMetadata.UNKNOWN, regressed.readMs);
    assertEquals(ScreenSenseMetadata.UNKNOWN, regressed.ageMs);
    assertEquals(ScreenSenseMetadata.UNKNOWN, ScreenSenseMetadata.of(state, -1, 5, 6).readMs);
    try {
      ScreenSenseMetadata.of(null, 0, 0, 0);
      fail("missing state must be rejected");
    } catch (IllegalArgumentException expected) {
      assertEquals("SCREEN_SENSE_STATE_MISSING", expected.getMessage());
    }
  }
}
