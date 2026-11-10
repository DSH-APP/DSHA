package com.deepseekharness.app.ui;

import static org.junit.Assert.*;

import android.os.Bundle;
import android.os.Looper;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ScrollView;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import com.deepseekharness.app.DshaApp;
import com.deepseekharness.app.R;
import com.deepseekharness.app.util.UiLanguagePreference;
import java.util.ArrayList;
import java.util.List;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.LooperMode;
import org.robolectric.shadows.ShadowDialog;

/** 语言选择网格：11 个选项（跟随系统 + 十种语言）都建出来，且在平板与手机尺寸下都能完整排下。 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34, application = LanguagePickerTest.App.class)
@LooperMode(LooperMode.Mode.PAUSED)
public final class LanguagePickerTest {
  public static final class App extends DshaApp {
    @Override
    public void onCreate() {}
  }

  public static final class Host extends AppCompatActivity {
    @Override
    protected void onCreate(Bundle saved) {
      setTheme(R.style.Theme_DeepseekHarness);
      super.onCreate(saved);
      setContentView(new FrameLayout(this));
    }
  }

  private static void collect(View view, List<View> out) {
    if (view.getTag() instanceof String && view.isClickable()) out.add(view);
    if (view instanceof ViewGroup group)
      for (int i = 0; i < group.getChildCount(); i++) collect(group.getChildAt(i), out);
  }

  private static ScrollView findScroll(View view) {
    if (view instanceof ScrollView scroll) return scroll;
    if (view instanceof ViewGroup group)
      for (int i = 0; i < group.getChildCount(); i++) {
        ScrollView found = findScroll(group.getChildAt(i));
        if (found != null) return found;
      }
    return null;
  }

  private void assertAllOptionsFit() {
    Host host = Robolectric.buildActivity(Host.class).setup().visible().get();
    LanguagePicker.show(host, UiLanguagePreference.SYSTEM);
    org.robolectric.shadow.api.Shadow.<org.robolectric.shadows.ShadowLooper>extract(
            Looper.getMainLooper())
        .idle();
    AlertDialog dialog = (AlertDialog) ShadowDialog.getLatestDialog();
    assertNotNull(dialog);
    View decor = dialog.getWindow().getDecorView();
    List<View> tiles = new ArrayList<>();
    collect(decor, tiles);
    List<String> values = new ArrayList<>();
    for (View tile : tiles) values.add((String) tile.getTag());
    List<String> expected = new ArrayList<>();
    expected.add(UiLanguagePreference.SYSTEM);
    expected.addAll(UiLanguagePreference.LANGUAGES);
    assertEquals(expected, values);
    ScrollView scroll = findScroll(decor);
    assertNotNull(scroll);
    View content = scroll.getChildAt(0);
    // 内容不超过可视高度：最后一格（Français）不需要滚动就能看到。
    assertTrue(
        "content " + content.getHeight() + " > viewport " + scroll.getHeight(),
        content.getHeight() <= scroll.getHeight());
    for (View tile : tiles) assertTrue(tile.getHeight() > 0);
    dialog.dismiss();
  }

  @Test
  @Config(qualifiers = "zh-rCN-w800dp-h1280dp-xhdpi")
  public void tabletShowsEveryOptionWithoutScrolling() {
    assertAllOptionsFit();
  }

  @Test
  @Config(qualifiers = "zh-rCN-w360dp-h640dp-xhdpi")
  public void smallPhoneShowsEveryOptionWithoutScrolling() {
    assertAllOptionsFit();
  }

  @Test
  @Config(qualifiers = "en-rUS-w400dp-h800dp-xhdpi")
  public void phoneShowsEveryOptionWithoutScrolling() {
    assertAllOptionsFit();
  }
}
