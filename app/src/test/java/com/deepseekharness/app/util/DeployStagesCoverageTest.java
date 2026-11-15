package com.deepseekharness.app.util;

import static org.junit.Assert.*;

import com.deepseekharness.app.util.DeployStages.Mode;
import com.deepseekharness.app.util.DeployStages.Phase;
import com.deepseekharness.app.util.DeployStages.Step;
import org.junit.After;
import org.junit.Test;

/** DeployStages 补充覆盖（UI-B）：更新 / 重建阶段、英文模板前缀、视图计数与全部标签。 */
public class DeployStagesCoverageTest {
  @After
  public void resetLanguage() {
    UiText.setLanguage("zh");
  }

  @Test
  public void updateAndRebuildProgressLinesMatchTheirStages() {
    UiText.setLanguage("zh");
    assertEquals(0, DeployStages.match(Mode.UPDATE, "正在停止 Web，等待运行队列完成…"));
    assertEquals(1, DeployStages.match(Mode.UPDATE, "正在解压新版 dsh（保留现有 Ubuntu 与个人目录）…"));
    assertEquals(2, DeployStages.match(Mode.UPDATE, "正在准备新版内置插件和界面适配…"));
    assertEquals(3, DeployStages.match(Mode.UPDATE, "新版运行时准备完成，正在保留并切换受管文件…"));
    assertEquals(4, DeployStages.match(Mode.UPDATE, "正在核验文件锁…"));
    assertEquals(1, DeployStages.match(Mode.REBUILD, "正在由 Android 保护应用数据与个人目录；不执行旧环境…"));
    assertEquals(2, DeployStages.match(Mode.REBUILD, "正在解压内置环境… 42%"));
    assertEquals(2, DeployStages.match(Mode.REBUILD, "解压 Ubuntu 与 Node"));
    assertEquals(2, DeployStages.match(Mode.REBUILD, "解压与离线安装完成"));
    assertEquals(3, DeployStages.match(Mode.REBUILD, "正在恢复本机数据并逐文件校验…"));
    assertEquals(4, DeployStages.match(Mode.REBUILD, "正在核验原生模块…"));
    assertEquals(-1, DeployStages.match(Mode.UPDATE, null));
    assertEquals(-1, DeployStages.match(Mode.GENERIC, "准备解压"));
    assertEquals(5, DeployStages.stages(Mode.UPDATE).size());
    assertEquals(5, DeployStages.stages(Mode.REBUILD).size());
    assertTrue(DeployStages.stages(Mode.GENERIC).isEmpty());
    assertTrue(DeployStages.stages(null).isEmpty());
  }

  @Test
  public void englishInterfaceMatchesCatalogTranslationsAndTemplates() {
    UiText.setLanguage("en");
    assertEquals(
        1,
        DeployStages.match(
            Mode.UPDATE,
            "Extracting updated dsh (preserving existing Ubuntu and personal directories)…"));
    assertEquals(
        3,
        DeployStages.match(
            Mode.UPDATE, "New runtime prepared. Preserving and switching managed files…"));
    // 带 %s 的模板只取固定前缀。
    assertEquals(2, DeployStages.match(Mode.REBUILD, "Extracting the bundled environment… 10%"));
    assertEquals(
        3, DeployStages.match(Mode.REBUILD, "Restoring local data and verifying every file…"));
    assertEquals(0, DeployStages.match(Mode.INSTALL, "Preparing…"));
    // 语言切换前留下的中文进度行在英文界面同样识别。
    assertEquals(2, DeployStages.match(Mode.INSTALL, "解压 dsh 与内置依赖"));
    assertEquals(
        Mode.REBUILD,
        DeployStages.infer(Mode.UPDATE, "Restoring local data and verifying every file…"));
  }

  @Test
  public void viewCountersAndSubLabelsFollowTheCurrentStage() {
    UiText.setLanguage("zh");
    DeployStages.View running = DeployStages.view(Mode.INSTALL, Phase.RUNNING, 3, "未知进度行", "", 9);
    assertEquals(3, running.current);
    assertEquals(Step.DONE, running.steps[2]);
    assertEquals(Step.CURRENT, running.steps[3]);
    assertEquals(Step.TODO, running.steps[4]);
    assertEquals(3, running.completed());
    assertEquals("4 / 8", running.counter());
    assertEquals("安装 Python 与 pnpm", running.sub());
    assertEquals(9, running.taskId);
    assertTrue(running.visible());
    DeployStages.View pending = DeployStages.view(Mode.UPDATE, Phase.PENDING, 1, "", "", 9);
    assertEquals(Step.FAILED, pending.steps[1]);
    assertEquals("", pending.counter());
    assertEquals("", pending.sub());
    // 超出范围的已到达阶段被夹到最后一段。
    DeployStages.View clamped = DeployStages.view(Mode.UPDATE, Phase.RUNNING, 99, "", "", 9);
    assertEquals(4, clamped.current);
    DeployStages.View generic = DeployStages.view(Mode.GENERIC, Phase.RUNNING, 2, "x", "", 9);
    assertEquals(-1, generic.current);
    assertEquals(0, generic.total());
    assertEquals("", generic.counter());
    assertEquals("维护中", generic.label());
    DeployStages.View idle = DeployStages.idle();
    assertFalse(idle.visible());
    assertEquals("", idle.label());
    assertEquals(0, idle.completed());
  }

  @Test
  public void labelsAndTitlesCoverEveryModeAndPhase() {
    UiText.setLanguage("zh");
    for (Mode mode : Mode.values()) {
      assertFalse(DeployStages.title(mode).isEmpty());
      for (Phase phase : Phase.values())
        assertEquals(phase == Phase.IDLE, DeployStages.label(mode, phase).isEmpty());
    }
    assertEquals("环境已就绪", DeployStages.label(Mode.INSTALL, Phase.SUCCEEDED));
    assertEquals("环境已重建", DeployStages.label(Mode.REBUILD, Phase.SUCCEEDED));
    assertEquals("重建未完成", DeployStages.label(Mode.REBUILD, Phase.FAILED));
    assertEquals("维护未完成", DeployStages.label(Mode.GENERIC, Phase.FAILED));
    assertEquals("环境维护", DeployStages.title(null));
    assertEquals("", DeployStages.label(null, null));
    UiText.setLanguage("en");
    assertEquals("Runtime updated", DeployStages.label(Mode.UPDATE, Phase.SUCCEEDED));
    assertEquals("Deployment incomplete", DeployStages.label(Mode.INSTALL, Phase.FAILED));
    assertEquals(
        "Previous maintenance incomplete", DeployStages.label(Mode.INSTALL, Phase.PENDING));
    assertEquals("Runtime rebuild", DeployStages.title(Mode.REBUILD));
  }
}
