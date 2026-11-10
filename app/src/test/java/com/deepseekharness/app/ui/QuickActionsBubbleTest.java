package com.deepseekharness.app.ui;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;

import android.content.Context;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.TextView;
import androidx.appcompat.view.ContextThemeWrapper;
import com.deepseekharness.app.DshaApp;
import com.deepseekharness.app.R;
import com.deepseekharness.app.util.QuickActions;
import com.deepseekharness.app.util.UiLanguagePreference;
import com.deepseekharness.app.util.UiText;
import java.util.ArrayList;
import java.util.List;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

/** 顶栏「+」气泡内容：文案、启动页专属项与点击分派，不依赖真实 PopupWindow 窗口。 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34, application = QuickActionsBubbleTest.App.class)
public final class QuickActionsBubbleTest {

  public static final class App extends DshaApp {
    @Override
    public void onCreate() {}
  }

  private Context context;

  @Before
  public void setUp() {
    UiText.setLanguage(UiLanguagePreference.ZH);
    context =
        new ContextThemeWrapper(RuntimeEnvironment.getApplication(), R.style.Theme_DeepseekHarness);
  }

  private View inflate() {
    return LayoutInflater.from(context).inflate(R.layout.popup_quick_actions, null, false);
  }

  private static String label(View root, int id) {
    TextView view = root.findViewById(id);
    assertNotNull(view);
    return view.getText().toString();
  }

  @Test
  public void launchPageShowsFourRowsAndDispatchesActions() {
    View root = inflate();
    List<QuickActions.Action> actions = new ArrayList<>();
    QuickActionsBubble.bind(root, true, actions::add, () -> {});

    assertEquals("下载记录", label(root, R.id.quick_action_downloads_label));
    assertEquals("关于 DSHA", label(root, R.id.quick_action_about_label));
    assertEquals("运行环境 · 检查与维护", label(root, R.id.quick_action_environment_label));
    assertEquals("访问地址", label(root, R.id.quick_action_address_label));
    assertEquals("查看本机与局域网地址", label(root, R.id.quick_action_address_sub));
    assertEquals(View.VISIBLE, root.findViewById(R.id.quick_action_environment).getVisibility());
    assertEquals(View.VISIBLE, root.findViewById(R.id.quick_action_address).getVisibility());
    assertEquals(
        View.VISIBLE, root.findViewById(R.id.quick_action_divider_downloads).getVisibility());
    assertEquals(View.VISIBLE, root.findViewById(R.id.quick_action_divider_about).getVisibility());
    assertEquals(
        View.VISIBLE, root.findViewById(R.id.quick_action_divider_environment).getVisibility());

    root.findViewById(R.id.quick_action_downloads).performClick();
    root.findViewById(R.id.quick_action_address).performClick();
    assertEquals(List.of(QuickActions.Action.DOWNLOADS, QuickActions.Action.ADDRESS), actions);
  }

  @Test
  public void otherPagesHideLaunchOnlyRows() {
    View root = inflate();
    List<QuickActions.Action> actions = new ArrayList<>();
    QuickActionsBubble.bind(root, false, actions::add, () -> {});

    assertEquals(View.GONE, root.findViewById(R.id.quick_action_environment).getVisibility());
    assertEquals(View.GONE, root.findViewById(R.id.quick_action_address).getVisibility());
    // 两项之间保留一根分隔线，末项之后不再有。
    assertEquals(
        View.VISIBLE, root.findViewById(R.id.quick_action_divider_downloads).getVisibility());
    assertEquals(View.GONE, root.findViewById(R.id.quick_action_divider_about).getVisibility());
    assertEquals(
        View.GONE, root.findViewById(R.id.quick_action_divider_environment).getVisibility());
    assertEquals("下载记录", label(root, R.id.quick_action_downloads_label));
    assertEquals("关于 DSHA", label(root, R.id.quick_action_about_label));
  }
}
