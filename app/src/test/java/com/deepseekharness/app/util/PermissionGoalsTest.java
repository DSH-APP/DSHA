package com.deepseekharness.app.util;

import static org.junit.Assert.*;

import java.util.List;
import java.util.Set;
import org.junit.Test;

public class PermissionGoalsTest {
  @Test
  public void noGoalsRecommendsNothingAndKeepsDefaultOrder() {
    assertTrue(PermissionGoals.recommended(Set.of()).isEmpty());
    assertTrue(PermissionGoals.recommended(null).isEmpty());
    assertEquals(
        List.of("notify", "battery", "storage", "a11y", "overlay", "channel"),
        PermissionGoals.otherCards(Set.of()));
    assertEquals(0, PermissionGoals.recommendedCount(Set.of(PermissionGoals.BASE)));
  }

  @Test
  public void screenGoalRecommendsAllThreePhoneTaskPrerequisites() {
    Set<String> rec = PermissionGoals.recommended(Set.of(PermissionGoals.SCREEN));
    assertEquals(Set.of("a11y", "overlay", "notify"), rec);
    // 推荐项按默认顺序排列，不受用途内部顺序影响。
    assertEquals(
        List.of("notify", "a11y", "overlay"),
        PermissionGoals.recommendedCards(Set.of(PermissionGoals.SCREEN)));
  }

  @Test
  public void overlappingGoalsDeduplicateRecommendations() {
    Set<String> goals = Set.of(PermissionGoals.SCREEN, PermissionGoals.BACKGROUND);
    assertEquals(4, PermissionGoals.recommendedCount(goals));
    assertTrue(PermissionGoals.recommended(goals).contains(PermissionGoals.BATTERY));
  }

  @Test
  public void recommendedAndOtherCardsPartitionEveryCardOnce() {
    Set<String> files = Set.of(PermissionGoals.FILES);
    assertEquals(List.of("storage"), PermissionGoals.recommendedCards(files));
    assertEquals(
        List.of("notify", "battery", "a11y", "overlay", "channel"),
        PermissionGoals.otherCards(files));
    // 同组的其他项不会被“顺带”推荐：只选后台用途时，存储不在推荐里。
    Set<String> bg = Set.of(PermissionGoals.BACKGROUND);
    assertEquals(List.of("notify", "battery"), PermissionGoals.recommendedCards(bg));
    // 只选“对话、写代码”：没有推荐项，全部可选。
    assertTrue(PermissionGoals.recommendedCards(Set.of(PermissionGoals.BASE)).isEmpty());
    assertEquals(6, PermissionGoals.otherCards(Set.of(PermissionGoals.BASE)).size());
    assertTrue(PermissionGoals.cardsFor("unknown").isEmpty());
    assertTrue(PermissionGoals.cardsFor(PermissionGoals.BASE).isEmpty());
    for (Set<String> goals :
        List.of(Set.<String>of(), files, bg, Set.copyOf(PermissionGoals.goals()))) {
      List<String> all = new java.util.ArrayList<>(PermissionGoals.recommendedCards(goals));
      all.addAll(PermissionGoals.otherCards(goals));
      assertEquals(PermissionGoals.cards().size(), all.size());
      assertEquals(Set.copyOf(PermissionGoals.cards()), Set.copyOf(all));
    }
  }

  @Test
  public void recommendedProgressCountsChannelAsEitherShizukuOrRoot() {
    Set<String> goals = Set.of(PermissionGoals.SHELL, PermissionGoals.SCREEN);
    // 存储、Shizuku、Root、无障碍、通知、悬浮窗、电池
    boolean[] none = new boolean[7];
    assertEquals(0, PermissionGoals.recommendedDone(goals, none));
    assertEquals(4, PermissionGoals.recommendedMissing(goals, none).size());
    boolean[] rootAndA11y = {false, false, true, true, false, false, false};
    assertEquals(2, PermissionGoals.recommendedDone(goals, rootAndA11y));
    assertEquals(
        List.of("overlay", "notify"),
        PermissionGoals.recommendedMissing(goals, rootAndA11y).stream()
            .filter(c -> !c.equals("a11y") && !c.equals("channel"))
            .toList());
    assertFalse(PermissionGoals.satisfied("unknown", rootAndA11y));
    assertFalse(PermissionGoals.satisfied(PermissionGoals.STORAGE, null));
  }
}
