package com.deepseekharness.app.ui;

import static org.junit.Assert.*;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;
import androidx.appcompat.view.ContextThemeWrapper;
import com.deepseekharness.app.DshaApp;
import com.deepseekharness.app.R;
import com.deepseekharness.app.util.UiLanguagePreference;
import com.deepseekharness.app.util.UiText;
import com.deepseekharness.app.util.WebCrashReport;
import java.util.ArrayList;
import java.util.List;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

/** 对话页错误面板（docs/design/web-crash-20261102）：每种结论都有文案、按钮与五行状态，十种语言都不回落中文。 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34, application = WebCrashPanelTest.App.class)
public final class WebCrashPanelTest {
  public static final class App extends DshaApp {
    @Override
    public void onCreate() {}
  }

  private View root;
  private WebCrashPanel panel;
  private final List<WebCrashReport.Action> actions = new ArrayList<>();
  private String previous;

  @Before
  public void setUp() {
    previous = UiText.language();
    UiText.setLanguage(UiLanguagePreference.ZH);
    build();
  }

  @After
  public void tearDown() {
    UiText.setLanguage(previous);
  }

  private void build() {
    var context =
        new ContextThemeWrapper(RuntimeEnvironment.getApplication(), R.style.Theme_DeepseekHarness);
    root = LayoutInflater.from(context).inflate(R.layout.activity_web_preview, null, false);
    View errors = root.findViewById(R.id.web_error_panel);
    panel =
        new WebCrashPanel(
            errors,
            root.findViewById(R.id.web_error_title),
            root.findViewById(R.id.web_error_detail),
            root::findViewById,
            actions::add);
  }

  private String text(int id) {
    return ((TextView) root.findViewById(id)).getText().toString();
  }

  private static boolean hasHan(String value) {
    return value
        .codePoints()
        .anyMatch(c -> Character.UnicodeScript.of(c) == Character.UnicodeScript.HAN);
  }

  @Test
  public void everyKindRendersTitleButtonsAndFiveStateRows() {
    for (WebCrashReport.Kind kind : WebCrashReport.Kind.values()) {
      panel.show(kind, "原标题", "原说明", new WebCrashReport.Facts(), "ENTRY", true);
      assertEquals(
          kind.name(), View.VISIBLE, root.findViewById(R.id.web_error_panel).getVisibility());
      assertFalse(kind.name(), text(R.id.web_error_title).isEmpty());
      assertFalse(kind.name(), text(R.id.web_retry).isEmpty());
      assertFalse(kind.name(), text(R.id.web_error_secondary).isEmpty());
      assertNotEquals(kind.name(), text(R.id.web_retry), text(R.id.web_error_secondary));
      LinearLayout rows = root.findViewById(R.id.web_error_state);
      int items = 0;
      for (int i = 0; i < rows.getChildCount(); i++)
        if (rows.getChildAt(i) instanceof LinearLayout) items++;
      assertEquals(kind.name(), 5, items);
      assertEquals(kind.name(), "ENTRY", text(R.id.web_error_details_text));
    }
  }

  @Test
  public void onlyUnclassifiedKindsKeepTheCallerText() {
    panel.show(WebCrashReport.Kind.GENERIC, "原标题", "原说明", null, "", true);
    assertEquals("原标题", text(R.id.web_error_title));
    panel.show(WebCrashReport.Kind.EXITED, "原标题", "原说明", null, "", true);
    assertEquals("DSH 服务意外退出", text(R.id.web_error_title));
  }

  @Test
  public void serviceDownDisablesBrowserAndWaitDisablesPrimary() {
    panel.show(WebCrashReport.Kind.RESTARTING, null, null, null, "", true);
    assertFalse(root.findViewById(R.id.web_error_browser).isEnabled());
    assertFalse(((Button) root.findViewById(R.id.web_retry)).isEnabled());
    panel.show(WebCrashReport.Kind.RENDER_CRASH, null, null, null, "", true);
    assertTrue(root.findViewById(R.id.web_error_browser).isEnabled());
    assertTrue(((Button) root.findViewById(R.id.web_retry)).isEnabled());
    assertEquals(WebCrashReport.Action.RELOAD, panel.primaryAction());
  }

  @Test
  public void rowsThatDuplicateAButtonAreHiddenWithTheirDividers() {
    panel.show(WebCrashReport.Kind.EXITED, null, null, null, "", true);
    assertEquals(View.GONE, root.findViewById(R.id.web_error_logs).getVisibility());
    panel.show(WebCrashReport.Kind.BLOCKED, null, null, null, "", true);
    assertEquals(View.GONE, root.findViewById(R.id.web_error_recovery).getVisibility());
    assertEquals(View.GONE, root.findViewById(R.id.web_error_recovery_divider).getVisibility());
    ViewGroup more = root.findViewById(R.id.web_error_more);
    View last = null;
    for (int i = 0; i < more.getChildCount(); i++)
      if (more.getChildAt(i).getVisibility() == View.VISIBLE) last = more.getChildAt(i);
    assertNotNull(last);
    assertNotSame(View.class, last.getClass());
  }

  @Test
  public void secondaryAndBackReportActions() {
    panel.show(WebCrashReport.Kind.VERIFY, null, null, null, "", false);
    root.findViewById(R.id.web_error_secondary).performClick();
    root.findViewById(R.id.web_error_back).performClick();
    assertEquals(WebCrashReport.Action.HOME, actions.get(0));
    assertNull(actions.get(1));
  }

  @Test
  public void everyLanguageTranslatesEveryKindAndRow() {
    for (String language : UiLanguagePreference.LANGUAGES) {
      if (language.equals(UiLanguagePreference.ZH) || language.equals(UiLanguagePreference.ZH_HANT))
        continue;
      boolean kanji = language.equals(UiLanguagePreference.JA);
      UiText.setLanguage(language);
      build();
      WebCrashReport.Facts facts = new WebCrashReport.Facts();
      facts.exitCode = 137;
      facts.failures = 3;
      facts.errorCode = -2;
      facts.httpStatus = 401;
      facts.autoRestart = true;
      for (WebCrashReport.Kind kind : WebCrashReport.Kind.values()) {
        if (kind == WebCrashReport.Kind.GENERIC) continue;
        panel.show(kind, null, null, facts, "", true);
        if (kanji) {
          // 日文正当使用汉字：只核对标题已换成日文，而不是中文原文。
          String ja = text(R.id.web_error_title);
          UiText.setLanguage(UiLanguagePreference.ZH);
          assertNotEquals(kind.name(), WebCrashPanel.copy(kind)[0], ja);
          UiText.setLanguage(language);
          continue;
        }
        for (int id :
            new int[] {
              R.id.web_error_title,
              R.id.web_error_detail,
              R.id.web_retry,
              R.id.web_error_secondary,
              R.id.web_error_state_label,
              R.id.web_error_details_title
            }) assertFalse(language + ":" + kind + ":" + id, hasHan(text(id)));
        LinearLayout rows = root.findViewById(R.id.web_error_state);
        for (int i = 0; i < rows.getChildCount(); i++)
          assertNoHan(language + ":" + kind + ":row" + i, rows.getChildAt(i));
      }
    }
  }

  private static void assertNoHan(String where, View view) {
    if (view instanceof TextView)
      assertFalse(where, hasHan(((TextView) view).getText().toString()));
    if (view instanceof ViewGroup)
      for (int i = 0; i < ((ViewGroup) view).getChildCount(); i++)
        assertNoHan(where, ((ViewGroup) view).getChildAt(i));
  }
}
