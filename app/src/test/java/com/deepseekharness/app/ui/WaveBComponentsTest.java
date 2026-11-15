package com.deepseekharness.app.ui;

import static org.junit.Assert.*;

import android.content.ContentResolver;
import android.os.Looper;
import android.os.SystemClock;
import android.provider.Settings;
import android.view.ContextThemeWrapper;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.LinearLayout;
import android.widget.TextView;
import androidx.appcompat.widget.AppCompatButton;
import com.deepseekharness.app.DshaApp;
import com.deepseekharness.app.R;
import com.deepseekharness.app.util.UiText;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.LooperMode;
import org.robolectric.shadow.api.Shadow;
import org.robolectric.shadows.ShadowLooper;

/**
 * Wave B 组件：按压（禁用/关动画不缩放）、加载按钮锁宽与恢复、数值滚动、内容淡换、
 * 卡片层级/徽标、选中卡单选与多选、多选操作栏计数。
 */
@RunWith(RobolectricTestRunner.class)
@Config(
    sdk = 34,
    application = WaveBComponentsTest.App.class,
    qualifiers = "zh-rCN-w360dp-h740dp-mdpi")
@LooperMode(LooperMode.Mode.PAUSED)
public final class WaveBComponentsTest {
  public static final class App extends DshaApp {
    @Override
    public void onCreate() {}
  }

  private ContentResolver resolver;
  private ContextThemeWrapper context;
  private String previousLanguage;

  @Before
  public void setUp() {
    // UiText 是测试 JVM 内的全局静态：其他类（含启动真实 DshaApp 的类）可能把语言留成系统
    // 语言（CI 为 en），这里固定中文断言环境并在 @After 还原，避免依赖执行顺序。
    previousLanguage = UiText.language();
    UiText.setLanguage("zh");
    resolver = RuntimeEnvironment.getApplication().getContentResolver();
    animationScales(1f);
    context =
        new ContextThemeWrapper(RuntimeEnvironment.getApplication(), R.style.Theme_DeepseekHarness);
  }

  @After
  public void tearDown() {
    UiText.setLanguage(previousLanguage);
  }

  private void animationScales(float value) {
    Settings.Global.putFloat(resolver, Settings.Global.ANIMATOR_DURATION_SCALE, value);
    Settings.Global.putFloat(resolver, Settings.Global.TRANSITION_ANIMATION_SCALE, value);
  }

  private static void idle(long millis) {
    Shadow.<ShadowLooper>extract(Looper.getMainLooper()).idleFor(Duration.ofMillis(millis));
  }

  private static Button button(String text) {
    Button button = new AppCompatButton(RuntimeEnvironment.getApplication());
    button.setText(text);
    return button;
  }

  private static void touch(View view, int action) {
    long now = SystemClock.uptimeMillis();
    MotionEvent event = MotionEvent.obtain(now, now, action, 5f, 5f, 0);
    view.dispatchTouchEvent(event);
    event.recycle();
  }

  @Test
  public void pressFeedbackScalesEnabledButtonAndSkipsDisabled() {
    animationScales(0f);
    Button button = button("开始");
    PressFeedback.attach(button, PressFeedback.Variant.BUTTON);
    PressFeedback.attach(button, PressFeedback.Variant.BUTTON); // 幂等
    touch(button, MotionEvent.ACTION_DOWN);
    assertEquals("关闭动画时保持静态", 1f, button.getScaleX(), 0.001f);

    animationScales(1f);
    PressFeedback.disabled(button, false, null);
    touch(button, MotionEvent.ACTION_DOWN);
    idle(200);
    assertTrue("启用按钮按下有缩放", button.getScaleX() < 1f);
    touch(button, MotionEvent.ACTION_UP);
    idle(300);
    assertEquals(1f, button.getScaleX(), 0.001f);

    PressFeedback.disabled(button, true, "正在运行");
    assertEquals("正在运行", button.getStateDescription().toString());
    float before = button.getScaleX();
    touch(button, MotionEvent.ACTION_DOWN);
    idle(200);
    assertEquals("禁用不缩放", before, button.getScaleX(), 0.001f);
    assertFalse(button.isEnabled());
  }

  @Test
  public void pressFeedbackDisabledCoversLoadAndRestore() {
    Button button = button("备份");
    PressFeedback.disabled(button, true, "先结束当前任务");
    assertFalse(button.isEnabled());
    assertEquals("先结束当前任务", button.getStateDescription().toString());
    PressFeedback.disabled(button, false, null);
    assertTrue(button.isEnabled());
    assertNull(button.getStateDescription());
  }

  @Test
  public void loadingActionLocksWidthAndRestores() {
    Button button = button("开始备份");
    LinearLayout host = new LinearLayout(context);
    host.addView(
        button, new LinearLayout.LayoutParams(240, LinearLayout.LayoutParams.WRAP_CONTENT));
    button.measure(
        View.MeasureSpec.makeMeasureSpec(240, View.MeasureSpec.EXACTLY),
        View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED));
    button.layout(0, 0, button.getMeasuredWidth(), button.getMeasuredHeight());
    int width = button.getMeasuredWidth();
    assertTrue(LoadingAction.start(button));
    assertTrue(LoadingAction.busy(button));
    assertFalse("重复开始被拒绝", LoadingAction.start(button));
    assertFalse(button.isEnabled());
    assertTrue(button.getCompoundDrawablesRelative()[0] instanceof LoadingAction.SpinnerDrawable);
    assertTrue("忙碌期锁宽", button.getMinWidth() >= width);
    assertEquals("进行中", button.getStateDescription().toString());
    LoadingAction.stop(button);
    assertFalse(LoadingAction.busy(button));
    assertTrue(button.isEnabled());
    assertEquals("开始备份", button.getText().toString());
    assertNull(button.getCompoundDrawablesRelative()[0]);
  }

  @Test
  public void loadingActionRunExecutesAndRestores() {
    Button button = button("部署");
    AtomicBoolean ran = new AtomicBoolean();
    assertTrue(LoadingAction.run(button, () -> ran.set(true)));
    assertTrue(ran.get());
    assertTrue(button.isEnabled());
    assertFalse(LoadingAction.busy(button));
  }

  @Test
  public void rollingNumberAnimatesUntilTargetAndRespectsDisabledAnimations() {
    TextView view = new TextView(context);
    animationScales(0f);
    RollingNumber.bytes(view, 1024L * 1024L);
    assertEquals("1.0 MB", view.getText().toString());

    animationScales(1f);
    RollingNumber.bytes(view, 2L * 1024L * 1024L);
    idle(400);
    assertEquals("2.0 MB", view.getText().toString());
  }

  @Test
  public void fadeSwapContentRunsRebuildAndKeepsVisible() {
    LinearLayout host = new LinearLayout(context);
    animationScales(0f);
    AtomicInteger runs = new AtomicInteger();
    FadeSwap.content(
        host,
        () -> {
          runs.incrementAndGet();
          host.addView(new TextView(context));
        });
    assertEquals(1, runs.get());
    assertEquals(1f, host.getAlpha(), 0.001f);
    assertEquals(1, host.getChildCount());

    animationScales(1f);
    FadeSwap.content(
        host,
        () -> {
          runs.incrementAndGet();
          host.removeAllViews();
          host.addView(new TextView(context));
        });
    idle(400);
    assertEquals(2, runs.get());
    assertEquals(1f, host.getAlpha(), 0.001f);
  }

  @Test
  public void cardTokensApplyTierAndBadge() {
    LinearLayout card = new LinearLayout(context);
    CardTokens.tier(card, CardTokens.Tier.FILLED);
    assertNotNull(card.getBackground());
    assertEquals(0f, card.getElevation(), 0.001f);
    CardTokens.tier(card, CardTokens.Tier.ELEVATED);
    assertTrue(card.getElevation() > 0f);

    assertEquals("3", CardTokens.badge(context, 3, CardTokens.Tone.ACCENT).getText().toString());
    assertEquals("99+", CardTokens.badge(context, 150, CardTokens.Tone.BAD).getText().toString());
    View dot = CardTokens.dot(context, CardTokens.Tone.GOOD);
    CardTokens.setDotTone(dot, CardTokens.Tone.WARN);
    assertNotNull(dot.getBackground());
  }

  @Test
  public void selectableCardSingleAndMultiSelection() {
    AtomicBoolean clicked = new AtomicBoolean();
    SelectableCard.Model single = new SelectableCard.Model();
    single.title = "提示条";
    single.selected = false;
    View cell = SelectableCard.single(context, single, () -> clicked.set(true));
    cell.performClick();
    assertTrue(clicked.get());
    SelectableCard.setSelected(cell, true);
    assertTrue(SelectableCard.isSelected(cell));
    assertEquals("已选中", cell.getStateDescription().toString());

    SelectableCard.Model multi = new SelectableCard.Model();
    multi.title = "记录 A";
    multi.multi = true;
    View multiCell = SelectableCard.multi(context, multi, null);
    CheckBox box = findCheckBox(multiCell);
    assertNotNull("多选卡显示勾选框", box);
    SelectableCard.setSelected(multiCell, true);
    assertTrue(box.isChecked());
  }

  @Test
  public void selectionActionBarShowsCountAndHidesWhenEmpty() {
    LinearLayout footer = new LinearLayout(context);
    footer.setOrientation(LinearLayout.VERTICAL);
    SelectionActionBar.Bar bar = SelectionActionBar.attach(footer);
    assertNotNull(bar);
    Button action = bar.action("检查所选记录", true, null);
    bar.update(0);
    assertEquals(View.GONE, bar.actions.getVisibility());
    assertTrue(bar.count.getText().toString().isEmpty());

    bar.update(3);
    assertEquals(View.VISIBLE, bar.actions.getVisibility());
    assertEquals("已选中", bar.count.getText().toString());
    View badge = bar.header.getChildAt(0);
    assertTrue(badge instanceof TextView);
    assertEquals("3", ((TextView) badge).getText().toString());
    assertEquals(View.VISIBLE, action.getVisibility());
  }

  private static CheckBox findCheckBox(View view) {
    if (view instanceof CheckBox box) return box;
    if (view instanceof ViewGroup group)
      for (int i = 0; i < group.getChildCount(); i++) {
        CheckBox found = findCheckBox(group.getChildAt(i));
        if (found != null) return found;
      }
    return null;
  }
}
