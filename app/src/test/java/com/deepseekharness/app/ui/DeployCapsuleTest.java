package com.deepseekharness.app.ui;

import static org.junit.Assert.*;

import android.content.res.Configuration;
import android.view.View;
import android.widget.TextView;
import com.deepseekharness.app.DshaApp;
import com.deepseekharness.app.util.DeployStages;
import com.deepseekharness.app.util.DeployStages.Mode;
import com.deepseekharness.app.util.DeployStages.Phase;
import com.deepseekharness.app.util.UiText;
import java.util.ArrayList;
import java.util.List;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

/** 部署胶囊 / 进度卡：各阶段的可见性、文案与无障碍描述；窄屏不显示阶段副标签。 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34, application = DeployCapsuleTest.App.class)
public final class DeployCapsuleTest {
  public static final class App extends DshaApp {
    @Override
    public void onCreate() {}
  }

  private String previousLanguage;

  @Before
  public void chinese() {
    previousLanguage = UiText.language();
    UiText.setLanguage("zh");
  }

  @After
  public void restore() {
    UiText.setLanguage(previousLanguage);
  }

  private static DeployStages.View view(Mode mode, Phase phase, String detail) {
    return DeployStages.view(mode, phase, -1, detail, detail, 42);
  }

  private static DeployCapsule capsule() {
    return new DeployCapsule(RuntimeEnvironment.getApplication());
  }

  private static List<String> texts(View root) {
    List<String> found = new ArrayList<>();
    collect(root, found);
    return found;
  }

  private static void collect(View view, List<String> found) {
    if (view instanceof TextView && view.getVisibility() == View.VISIBLE) {
      CharSequence text = ((TextView) view).getText();
      if (text != null && text.length() > 0) found.add(text.toString());
    }
    if (view instanceof android.view.ViewGroup group)
      for (int i = 0; i < group.getChildCount(); i++)
        if (group.getChildAt(i).getVisibility() == View.VISIBLE)
          collect(group.getChildAt(i), found);
  }

  @Test
  public void idleCapsuleIsHidden() {
    DeployCapsule capsule = capsule();
    assertEquals(View.GONE, capsule.getVisibility());
    capsule.render(DeployStages.idle());
    assertEquals(View.GONE, capsule.getVisibility());
    capsule.render(null);
    assertEquals(View.GONE, capsule.getVisibility());
  }

  @Test
  @Config(qualifiers = "w411dp-h891dp")
  public void runningInstallShowsLabelStageAndCounter() {
    DeployCapsule capsule = capsule();
    capsule.render(view(Mode.INSTALL, Phase.RUNNING, "解压 Ubuntu 与 Node"));
    assertEquals(View.VISIBLE, capsule.getVisibility());
    List<String> shown = texts(capsule);
    assertTrue(shown.toString(), shown.contains("部署中"));
    assertTrue(shown.toString(), shown.contains("· 解压 Ubuntu 与 Node"));
    assertTrue(shown.toString(), shown.contains("2/8"));
    assertEquals("运行环境部署：部署中，解压 Ubuntu 与 Node，点按查看详情", capsule.getContentDescription().toString());
  }

  @Test
  @Config(qualifiers = "w360dp-h740dp")
  public void narrowScreenHidesTheStageSubLabelButKeepsTheDescription() {
    DeployCapsule capsule = capsule();
    capsule.render(view(Mode.UPDATE, Phase.RUNNING, "正在准备新版内置插件和界面适配…"));
    List<String> shown = texts(capsule);
    assertTrue(shown.toString(), shown.contains("更新中"));
    assertFalse(shown.toString(), shown.contains("· 准备内置插件与界面适配"));
    assertTrue(shown.toString(), shown.contains("3/5"));
    assertTrue(capsule.getContentDescription().toString().contains("准备内置插件与界面适配"));
  }

  @Test
  @Config(qualifiers = "w411dp-h891dp")
  public void finishedStatesUseTheirLabelsWithoutCounters() {
    DeployCapsule capsule = capsule();
    capsule.render(view(Mode.UPDATE, Phase.SUCCEEDED, "dsh 与内置插件更新完成"));
    assertEquals(List.of("运行时已更新"), texts(capsule));
    assertEquals("运行时更新：运行时已更新，点按查看详情", capsule.getContentDescription().toString());

    capsule.render(view(Mode.UPDATE, Phase.FAILED, "运行时更新未完成"));
    assertEquals(List.of("更新未完成", "· 点此查看"), texts(capsule));

    capsule.render(view(Mode.INSTALL, Phase.FAILED, "准备解压"));
    assertEquals(List.of("部署未完成", "· 点此查看"), texts(capsule));
    assertEquals("运行环境部署：部署未完成，点按查看详情", capsule.getContentDescription().toString());

    capsule.render(view(Mode.INSTALL, Phase.UNREADY, "解压与离线安装完成"));
    assertEquals(List.of("环境检查未通过", "· 点此查看"), texts(capsule));

    capsule.render(view(Mode.UPDATE, Phase.PENDING, ""));
    assertEquals(List.of("上次维护未完成", "· 点此查看"), texts(capsule));
    assertEquals(View.VISIBLE, capsule.getVisibility());
  }

  @Test
  public void openCallbackOnlyRunsWhileVisible() {
    DeployCapsule capsule = capsule();
    int[] opened = {0};
    capsule.setOnOpen(() -> opened[0]++);
    capsule.performClick();
    assertEquals(0, opened[0]);
    capsule.render(view(Mode.INSTALL, Phase.RUNNING, "准备解压"));
    capsule.performClick();
    assertEquals(1, opened[0]);
  }

  @Test
  @Config(qualifiers = "w411dp-h891dp")
  public void englishCapsuleUsesWholeSentences() {
    UiText.setLanguage("en");
    DeployCapsule capsule = capsule();
    capsule.render(view(Mode.INSTALL, Phase.RUNNING, "Extract Ubuntu and Node"));
    List<String> shown = texts(capsule);
    assertTrue(shown.toString(), shown.contains("Deploying"));
    assertTrue(shown.toString(), shown.contains("· Extract Ubuntu and Node"));
    assertTrue(shown.toString(), shown.contains("2/8"));
    assertEquals(
        "Runtime deployment: Deploying, Extract Ubuntu and Node. Tap for details",
        capsule.getContentDescription().toString());
  }

  @Test
  public void idleCardIsDashedAndNotClickable() {
    DeployCard card = new DeployCard(RuntimeEnvironment.getApplication());
    card.render(DeployStages.idle());
    assertFalse(card.isClickable());
    List<String> shown = texts(card);
    assertTrue(shown.toString(), shown.contains("运行环境"));
    assertTrue(shown.toString(), shown.contains("未开始"));
    assertTrue(shown.toString(), shown.contains("点「开始部署」后在后台部署，期间可以继续完成引导。"));
  }

  @Test
  public void runningCardShowsCounterPillStageLineAndMoreLink() {
    DeployCard card = new DeployCard(RuntimeEnvironment.getApplication());
    int[] opened = {0};
    card.setOnOpen(() -> opened[0]++);
    card.render(view(Mode.INSTALL, Phase.RUNNING, "解压 Ubuntu 与 Node"));
    assertTrue(card.isClickable());
    List<String> shown = texts(card);
    assertTrue(shown.toString(), shown.contains("运行环境部署"));
    assertTrue(shown.toString(), shown.contains("2 / 8"));
    assertTrue(shown.toString(), shown.contains("第 2 / 8 步 · 解压 Ubuntu 与 Node"));
    assertTrue(shown.toString(), shown.contains("阶段与维护记录"));
    card.performClick();
    assertEquals(1, opened[0]);
    card.render(view(Mode.UPDATE, Phase.SUCCEEDED, ""));
    shown = texts(card);
    assertTrue(shown.toString(), shown.contains("已更新"));
    assertTrue(shown.toString(), shown.contains("新版运行时已通过隔离试运行。"));
  }

  @Test
  public void configurationWidthDrivesNarrowRule() {
    Configuration configuration =
        RuntimeEnvironment.getApplication().getResources().getConfiguration();
    assertTrue(configuration.screenWidthDp > 0);
  }

  /** 运行中只有当前阶段播放加载动画（分段流光 + 阶段圆环）；完成 / 失败后全部为静态。 */
  @Test
  public void onlyTheRunningStageShowsLoadingAnimation() {
    OnboardingKit kit = new OnboardingKit(RuntimeEnvironment.getApplication());
    DeployStages.View running = DeployStages.view(Mode.INSTALL, Phase.RUNNING, 2, "", "", 7);
    int current = -1;
    for (int i = 0; i < running.steps.length; i++)
      if (running.steps[i] == DeployStages.Step.CURRENT) current = i;
    assertTrue("运行中应有当前阶段", current >= 0);
    StageSegmentBar bar = (StageSegmentBar) kit.segmentBar(running);
    android.widget.LinearLayout list = kit.stageList(running);
    for (int i = 0; i < running.total(); i++) {
      boolean expected = i == current;
      assertEquals(expected, bar.shimmering(i));
      assertEquals(expected, hasSpinner(list.getChildAt(i)));
    }
    for (Phase phase : new Phase[] {Phase.SUCCEEDED, Phase.FAILED, Phase.PENDING}) {
      DeployStages.View done = DeployStages.view(Mode.INSTALL, phase, 2, "", "", 7);
      StageSegmentBar doneBar = (StageSegmentBar) kit.segmentBar(done);
      android.widget.LinearLayout doneList = kit.stageList(done);
      for (int i = 0; i < done.total(); i++) {
        assertFalse(doneBar.shimmering(i));
        assertFalse(hasSpinner(doneList.getChildAt(i)));
      }
    }
  }

  private static boolean hasSpinner(View view) {
    if (view instanceof BootSpinnerView) return true;
    if (view instanceof android.view.ViewGroup group)
      for (int i = 0; i < group.getChildCount(); i++)
        if (hasSpinner(group.getChildAt(i))) return true;
    return false;
  }
}
