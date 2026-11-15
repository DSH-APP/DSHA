package com.deepseekharness.app.util;

import static org.junit.Assert.*;

import com.deepseekharness.app.util.TerminalExtraKeys.Profile;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.Test;

/** 终端快捷按键模型；只验证纯逻辑，不涉及 Android 布局或真机 IME。 */
public class TerminalExtraKeysTest {
  private static List<String> row(String... actions) {
    return new ArrayList<>(Arrays.asList(actions));
  }

  @Test
  public void profileBoundaryIs599PhoneAnd600Tablet() {
    assertEquals(Profile.PHONE, TerminalExtraKeys.profileFor(-1));
    assertEquals(Profile.PHONE, TerminalExtraKeys.profileFor(0));
    assertEquals(Profile.PHONE, TerminalExtraKeys.profileFor(599));
    assertEquals(Profile.TABLET, TerminalExtraKeys.profileFor(600));
    assertEquals(Profile.TABLET, TerminalExtraKeys.profileFor(1440));
  }

  @Test
  public void phoneDefaultsMatchFrozenSevenPlusSevenLayout() {
    List<List<String>> rows = TerminalExtraKeys.defaults(Profile.PHONE);
    assertEquals(2, rows.size());
    assertEquals(7, rows.get(0).size());
    assertEquals(7, rows.get(1).size());
    assertEquals(
        Arrays.asList(
            row("ESC", "SLASH", "DASH", "HOME", "UP", "END", "PGUP"),
            row("TAB", "CTRL", "ALT", "LEFT", "DOWN", "RIGHT", "PGDN")),
        rows);
  }

  @Test
  public void tabletDefaultsMatchFrozenEightPlusEightLayout() {
    List<List<String>> rows = TerminalExtraKeys.defaults(Profile.TABLET);
    assertEquals(2, rows.size());
    assertEquals(8, rows.get(0).size());
    assertEquals(8, rows.get(1).size());
    assertEquals(
        Arrays.asList(
            row("ESC", "TAB", "CTRL", "ALT", "LEFT", "DOWN", "UP", "RIGHT"),
            row("CC", "CD", "CZ", "HOME", "END", "PIPE", "SLASH", "DASH")),
        rows);
  }

  @Test
  public void defaultsAndFallbacksAreMutableDeepCopies() {
    List<List<String>> first = TerminalExtraKeys.defaults(Profile.PHONE);
    first.get(0).set(0, "TAB");
    first.get(1).remove(0);
    first.add(row("HOME"));
    List<List<String>> second = TerminalExtraKeys.defaults(Profile.PHONE);
    assertEquals("ESC", second.get(0).get(0));
    assertEquals(7, second.get(1).size());
    assertEquals(2, second.size());
    assertNotSame(first, second);
    assertNotSame(first.get(0), second.get(0));

    List<List<String>> fallback = TerminalExtraKeys.decode("", Profile.TABLET);
    fallback.get(0).clear();
    fallback.add(row("ESC"));
    List<List<String>> third = TerminalExtraKeys.decode("", Profile.TABLET);
    assertEquals(8, third.get(0).size());
    assertEquals(2, third.size());
    assertNotSame(fallback, third);
    assertNotSame(fallback.get(0), third.get(0));
  }

  @Test
  public void encodeJoinsSanitizedRowsAndEmptyInputsBecomeEmptyString() {
    assertEquals("", TerminalExtraKeys.encode(null));
    assertEquals("", TerminalExtraKeys.encode(new ArrayList<>()));
    assertEquals("", TerminalExtraKeys.encode(Arrays.asList(row(), row())));
    assertEquals(
        "ESC,TAB|CTRL", TerminalExtraKeys.encode(Arrays.asList(row(" ESC ", "TAB"), row("CTRL"))));
    assertEquals(
        "ESC,SLASH,DASH,HOME,UP,END,PGUP|TAB,CTRL,ALT,LEFT,DOWN,RIGHT,PGDN",
        TerminalExtraKeys.encode(TerminalExtraKeys.defaults(Profile.PHONE)));
    assertEquals(
        "ESC,TAB,CTRL,ALT,LEFT,DOWN,UP,RIGHT|CC,CD,CZ,HOME,END,PIPE,SLASH,DASH",
        TerminalExtraKeys.encode(TerminalExtraKeys.defaults(Profile.TABLET)));
  }

  @Test
  public void nullBlankAndInvalidOnlyInputFallsBackToDefaults() {
    List<List<String>> phone = TerminalExtraKeys.defaults(Profile.PHONE);
    List<List<String>> tablet = TerminalExtraKeys.defaults(Profile.TABLET);
    assertEquals(phone, TerminalExtraKeys.decode(null, Profile.PHONE));
    assertEquals(phone, TerminalExtraKeys.decode("", Profile.PHONE));
    assertEquals(phone, TerminalExtraKeys.decode("   ", Profile.PHONE));
    assertEquals(phone, TerminalExtraKeys.decode("|", Profile.PHONE));
    assertEquals(phone, TerminalExtraKeys.decode("|||", Profile.PHONE));
    assertEquals(phone, TerminalExtraKeys.decode("NOPE,BOGUS|", Profile.PHONE));
    assertEquals(phone, TerminalExtraKeys.decode(", ,", Profile.PHONE));
    assertEquals(tablet, TerminalExtraKeys.decode(null, Profile.TABLET));
    assertEquals(tablet, TerminalExtraKeys.decode("unknown,|,invalid", Profile.TABLET));
  }

  @Test
  public void decodeNormalizesCaseAndWhitespace() {
    assertEquals(
        Arrays.asList(row("ESC", "TAB"), row("CTRL")),
        TerminalExtraKeys.decode(" esc , tab | ctrl ", Profile.PHONE));
    assertEquals(
        Arrays.asList(row("EQUAL", "QUESTION"), row("SLASH", "DASH")),
        TerminalExtraKeys.decode("equal,question|slash,dash", Profile.PHONE));
  }

  @Test
  public void decodeDropsUnknownAndBlankActionsAndKeepsValidRows() {
    assertEquals(
        Arrays.asList(row("ESC", "TAB"), row("CTRL")),
        TerminalExtraKeys.decode("ESC,NOPE,,TAB|BOGUS,CTRL", Profile.PHONE));
    assertEquals(Arrays.asList(row("ESC")), TerminalExtraKeys.decode("ESC|BOGUS", Profile.PHONE));
    assertEquals(
        Arrays.asList(row("HOME")), TerminalExtraKeys.decode("NOPE|HOME|ALSO-NOPE", Profile.PHONE));
  }

  @Test
  public void decodeEnforcesRowKeyAndDuplicateLimits() {
    List<String> twelve = TerminalExtraKeys.catalog().subList(0, 12);
    List<List<String>> capped = TerminalExtraKeys.decode(String.join(",", twelve), Profile.PHONE);
    assertEquals(1, capped.size());
    assertEquals(twelve.subList(0, 10), capped.get(0));

    assertEquals(
        Arrays.asList(row("ESC"), row("TAB")),
        TerminalExtraKeys.decode("ESC|TAB|HOME", Profile.PHONE));
    assertEquals(
        Arrays.asList(row("ESC", "TAB")),
        TerminalExtraKeys.decode("ESC,ESC,TAB|TAB,ESC", Profile.PHONE));
  }

  @Test
  public void decodeAcceptsSingleRowAndTwoFullRowsAtTheBoundary() {
    assertEquals(
        Arrays.asList(row("ESC", "TAB")), TerminalExtraKeys.decode("ESC,TAB", Profile.PHONE));
    List<String> catalog = TerminalExtraKeys.catalog();
    String twenty =
        String.join(",", catalog.subList(0, 10)) + "|" + String.join(",", catalog.subList(10, 20));
    List<List<String>> decoded = TerminalExtraKeys.decode(twenty, Profile.PHONE);
    assertEquals(2, decoded.size());
    assertEquals(10, decoded.get(0).size());
    assertEquals(10, decoded.get(1).size());
    assertEquals(catalog.subList(0, 10), decoded.get(0));
    assertEquals(catalog.subList(10, 20), decoded.get(1));
  }

  @Test
  public void encodeDecodeRoundTripsDefaultsAndCustomRows() {
    for (Profile profile : Profile.values()) {
      List<List<String>> defaults = TerminalExtraKeys.defaults(profile);
      assertEquals(defaults, TerminalExtraKeys.decode(TerminalExtraKeys.encode(defaults), profile));
    }
    List<List<String>> custom =
        Arrays.asList(row("CTRL", "CC"), row("PIPE", "SLASH", "DASH", "TILDE"));
    assertEquals(
        custom, TerminalExtraKeys.decode(TerminalExtraKeys.encode(custom), Profile.TABLET));
  }

  @Test
  public void sanitizeTrimsDeduplicatesCapsAndDropsEmptyRows() {
    List<List<String>> messy = new ArrayList<>();
    messy.add(
        row(
            "ESC", "TAB", "ESC", null, " ", "HOME", "END", "PGUP", "PGDN", "UP", "DOWN", "LEFT",
            "RIGHT"));
    messy.add(new ArrayList<>());
    messy.add(null);
    messy.add(row("", "  "));
    messy.add(row("CTRL", "ESC"));
    messy.add(row("ALT"));

    List<List<String>> safe = TerminalExtraKeys.sanitize(messy);
    assertEquals(2, safe.size());
    assertEquals(
        row("ESC", "TAB", "HOME", "END", "PGUP", "PGDN", "UP", "DOWN", "LEFT", "RIGHT"),
        safe.get(0));
    assertEquals(row("CTRL"), safe.get(1));
  }

  @Test
  public void sanitizeHandlesNullAndNeverMutatesOrSharesInputRows() {
    List<List<String>> empty = TerminalExtraKeys.sanitize(null);
    assertTrue(empty.isEmpty());
    empty.add(row("ESC"));

    List<List<String>> input = new ArrayList<>();
    input.add(row("ESC", " ", null, "TAB"));
    input.add(row("ESC", "HOME"));
    List<List<String>> safe = TerminalExtraKeys.sanitize(input);
    assertEquals(Arrays.asList(row("ESC", "TAB"), row("HOME")), safe);
    assertEquals(2, input.size());
    assertEquals(4, input.get(0).size());
    assertEquals(" ", input.get(0).get(1));
    assertNotSame(input.get(0), safe.get(0));
  }

  @Test
  public void catalogMatchesFrozenActionOrderWithoutSharingInternals() {
    List<String> expected =
        Arrays.asList(
            "ESC",
            "TAB",
            "CTRL",
            "ALT",
            "LEFT",
            "RIGHT",
            "UP",
            "DOWN",
            "HOME",
            "END",
            "PGUP",
            "PGDN",
            "CC",
            "CD",
            "CZ",
            "CL",
            "PIPE",
            "SLASH",
            "DASH",
            "TILDE",
            "COLON",
            "SEMI",
            "STAR",
            "AMP",
            "EQUAL",
            "PLUS",
            "QUESTION");
    assertEquals(expected, TerminalExtraKeys.catalog());
    assertEquals(expected.size(), new HashSet<>(TerminalExtraKeys.catalog()).size());

    List<String> copy = TerminalExtraKeys.catalog();
    copy.remove(0);
    assertEquals(27, TerminalExtraKeys.catalog().size());
    assertEquals("ESC", TerminalExtraKeys.catalog().get(0));
    for (List<String> row : TerminalExtraKeys.defaults(Profile.PHONE))
      assertTrue(TerminalExtraKeys.catalog().containsAll(row));
    for (List<String> row : TerminalExtraKeys.defaults(Profile.TABLET))
      assertTrue(TerminalExtraKeys.catalog().containsAll(row));
  }

  @Test
  public void labelsMatchFrozenShortcutsForEveryCatalogAction() {
    Map<String, String> expected = new LinkedHashMap<>();
    expected.put("ESC", "Esc");
    expected.put("TAB", "Tab");
    expected.put("CTRL", "Ctrl");
    expected.put("ALT", "Alt");
    expected.put("LEFT", "←");
    expected.put("RIGHT", "→");
    expected.put("UP", "↑");
    expected.put("DOWN", "↓");
    expected.put("HOME", "Home");
    expected.put("END", "End");
    expected.put("PGUP", "PgUp");
    expected.put("PGDN", "PgDn");
    expected.put("CC", "^C");
    expected.put("CD", "^D");
    expected.put("CZ", "^Z");
    expected.put("CL", "^L");
    expected.put("PIPE", "|");
    expected.put("SLASH", "/");
    expected.put("DASH", "-");
    expected.put("TILDE", "~");
    expected.put("COLON", ":");
    expected.put("SEMI", ";");
    expected.put("STAR", "*");
    expected.put("AMP", "&");
    expected.put("EQUAL", "=");
    expected.put("PLUS", "+");
    expected.put("QUESTION", "?");
    assertEquals(TerminalExtraKeys.catalog(), new ArrayList<>(expected.keySet()));
    for (Map.Entry<String, String> entry : expected.entrySet()) {
      assertEquals(entry.getValue(), TerminalExtraKeys.label(entry.getKey()));
      assertFalse(TerminalExtraKeys.label(entry.getKey()).isEmpty());
    }
    assertEquals("", TerminalExtraKeys.label(null));
  }

  @Test
  public void everyCatalogActionHasAnExplanatoryDescription() {
    for (String action : TerminalExtraKeys.catalog()) {
      String description = TerminalExtraKeys.description(action);
      assertNotNull(description);
      assertFalse(action, description.trim().isEmpty());
      assertNotEquals(action, description);
      assertNotEquals(TerminalExtraKeys.label(action), description);
    }
    assertEquals("", TerminalExtraKeys.description(null));
  }

  @Test
  public void descriptionsFollowTheApplicationLanguage() {
    String original = UiText.language();
    try {
      UiText.setLanguage("zh");
      String chinese = TerminalExtraKeys.description("LEFT");
      UiText.setLanguage("en");
      String english = TerminalExtraKeys.description("LEFT");
      assertTrue(chinese.contains("方向键"));
      assertTrue(english.contains("Arrow key"));
      assertNotEquals(chinese, english);
    } finally {
      UiText.setLanguage(original);
    }
  }

  @Test
  public void preferenceKeysAndLimitsAreFrozenAndDistinct() {
    assertEquals("deepseekharness", TerminalExtraKeys.PREFS);
    assertEquals(2, TerminalExtraKeys.MAX_ROWS);
    assertEquals(10, TerminalExtraKeys.MAX_KEYS_PER_ROW);
    assertEquals("term_extra_keys_phone", TerminalExtraKeys.prefKey(Profile.PHONE));
    assertEquals("term_extra_keys_tablet", TerminalExtraKeys.prefKey(Profile.TABLET));
    assertNotEquals(
        TerminalExtraKeys.prefKey(Profile.PHONE), TerminalExtraKeys.prefKey(Profile.TABLET));
  }

  @Test
  public void modifiersAndControlCharactersAreClassified() {
    Set<String> modifiers = new HashSet<>(Arrays.asList("CTRL", "ALT"));
    Set<String> controls = new HashSet<>(Arrays.asList("CC", "CD", "CZ", "CL"));
    for (String action : TerminalExtraKeys.catalog()) {
      assertEquals(action, modifiers.contains(action), TerminalExtraKeys.isModifier(action));
      assertEquals(action, controls.contains(action), TerminalExtraKeys.isControl(action));
    }
    assertFalse(TerminalExtraKeys.isModifier("ctrl"));
    assertFalse(TerminalExtraKeys.isModifier("ESC"));
    assertFalse(TerminalExtraKeys.isModifier(null));
    assertFalse(TerminalExtraKeys.isControl("CTRL"));
    assertFalse(TerminalExtraKeys.isControl("ESC"));
    assertFalse(TerminalExtraKeys.isControl(null));
  }

  @Test
  public void everyCatalogKeyHasExactlyOneVisualStyle() {
    Map<String, TerminalExtraKeys.KeyStyle> expected = new LinkedHashMap<>();
    for (String action : Arrays.asList("CTRL", "ALT"))
      expected.put(action, TerminalExtraKeys.KeyStyle.MODIFIER);
    for (String action : Arrays.asList("ESC", "TAB", "HOME", "END", "PGUP", "PGDN"))
      expected.put(action, TerminalExtraKeys.KeyStyle.FUNCTION);
    for (String action : Arrays.asList("LEFT", "RIGHT", "UP", "DOWN"))
      expected.put(action, TerminalExtraKeys.KeyStyle.ARROW);
    for (String action : Arrays.asList("CC", "CD", "CZ", "CL"))
      expected.put(action, TerminalExtraKeys.KeyStyle.CONTROL);
    for (String action : TerminalExtraKeys.catalog()) {
      TerminalExtraKeys.KeyStyle style = TerminalExtraKeys.style(action);
      // 外观分类必须与语义分类一致：修饰键与控制字符不能被画成普通符号。
      assertEquals(
          action,
          TerminalExtraKeys.isModifier(action),
          style == TerminalExtraKeys.KeyStyle.MODIFIER);
      assertEquals(
          action, TerminalExtraKeys.isControl(action), style == TerminalExtraKeys.KeyStyle.CONTROL);
      assertEquals(action, expected.getOrDefault(action, TerminalExtraKeys.KeyStyle.SYMBOL), style);
    }
    assertEquals(TerminalExtraKeys.KeyStyle.SYMBOL, TerminalExtraKeys.style(null));
    assertEquals(TerminalExtraKeys.KeyStyle.SYMBOL, TerminalExtraKeys.style("esc"));
  }
}
