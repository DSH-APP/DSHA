package com.deepseekharness.app.ui;

import static org.junit.Assert.*;

import android.app.Dialog;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.TextView;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.view.ContextThemeWrapper;
import com.deepseekharness.app.DshaApp;
import com.deepseekharness.app.R;
import com.deepseekharness.app.util.UiLanguagePreference;
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
import org.robolectric.annotation.LooperMode;
import org.robolectric.shadows.ShadowDialog;

/** 应急修复确认流：选择与预览共用一个对话框，返回只退一层，执行按钮按提案状态出现。 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34, application = RecoveryRepairSheetTest.App.class)
@LooperMode(LooperMode.Mode.PAUSED)
public final class RecoveryRepairSheetTest {
  public static final class App extends DshaApp {
    @Override
    public void onCreate() {}
  }

  private final List<Integer> chosen = new ArrayList<>();
  private final List<String> confirmed = new ArrayList<>(), rejected = new ArrayList<>();
  private RecoveryRepairSheet sheet;
  private String previous;

  @Before
  public void setUp() {
    previous = UiText.language();
    UiText.setLanguage(UiLanguagePreference.ZH);
    sheet =
        new RecoveryRepairSheet(
            new ContextThemeWrapper(
                RuntimeEnvironment.getApplication(), R.style.Theme_DeepseekHarness),
            new RecoveryRepairSheet.Listener() {
              public void onChoice(int index) {
                chosen.add(index);
              }

              public void onConfirm(String proposalId) {
                confirmed.add(proposalId);
              }

              public void onReject(String proposalId) {
                rejected.add(proposalId);
              }
            });
  }

  @After
  public void tearDown() {
    sheet.dismiss();
    UiText.setLanguage(previous);
  }

  @Test
  public void choicesAndPreviewReuseOneDialogAndBackPopsOneLayer() {
    sheet.showChoices(new String[] {"恢复中断维护", "修复受管运行时"});
    AlertDialog dialog = latestDialog();
    assertEquals(1, ShadowDialog.getShownDialogs().size());
    findButton(dialog, "恢复中断维护").performClick();
    assertEquals(List.of(0), chosen);

    sheet.showPreview("proposal-1", previewRows(), "", true, true);
    assertSame("预览沿用同一个对话框实例", dialog, latestDialog());
    assertNotNull(findButton(dialog, "保存原件并执行"));
    assertNotNull(findButton(dialog, "拒绝提案"));

    click(findButton(dialog, "返回"));
    assertTrue("预览返回选择时对话框仍在", dialog.isShowing());
    assertNotNull(findButton(dialog, "恢复中断维护"));

    click(findButton(dialog, "返回"));
    assertFalse(dialog.isShowing());
  }

  @Test
  public void directPreviewBackClosesInsteadOfReturningToChoices() {
    sheet.showPreview("plan-9", previewRows(), "", true, false);
    AlertDialog dialog = latestDialog();
    click(findButton(dialog, "返回"));
    assertFalse(dialog.isShowing());
  }

  @Test
  public void blockedProposalShowsReasonWithoutExecutionButtons() {
    sheet.showPreview("blocked-1", previewRows(), "源文件已变化，无法执行。", false, false);
    AlertDialog dialog = latestDialog();
    assertNotNull(findText(dialog, "源文件已变化，无法执行。"));
    assertNull(findButton(dialog, "保存原件并执行"));
    assertNull(findButton(dialog, "拒绝提案"));
  }

  @Test
  public void repeatedChoiceTapCreatesOnlyOneProposal() {
    sheet.showChoices(new String[] {"恢复中断维护"});
    Button choice = findButton(latestDialog(), "恢复中断维护");
    choice.performClick();
    choice.performClick();
    assertEquals("等待预览时不能重复生成提案", 1, chosen.size());
  }

  @Test
  public void applyAndRejectDismissBeforeNotifying() {
    sheet.showPreview("p1", previewRows(), "", true, false);
    AlertDialog dialog = latestDialog();
    findButton(dialog, "保存原件并执行").performClick();
    assertEquals(List.of("p1"), confirmed);
    assertFalse(dialog.isShowing());

    sheet.showPreview("p2", previewRows(), "", true, false);
    AlertDialog second = latestDialog();
    assertSame("重新打开仍复用同一个对话框", dialog, second);
    findButton(second, "拒绝提案").performClick();
    assertEquals(List.of("p2"), rejected);
    assertFalse(second.isShowing());
  }

  @Test
  public void previewNeedsAnIdBeforeOfferingExecution() {
    sheet.showPreview("", previewRows(), "", true, false);
    AlertDialog dialog = latestDialog();
    assertNull("没有提案 ID 时不能出现执行入口", findButton(dialog, "保存原件并执行"));
  }

  private static List<String[]> previewRows() {
    return List.of(
        new String[] {"操作", "恢复中断维护"},
        new String[] {"目标", "web-profile"},
        new String[] {"原件 SHA-256", "abc123"});
  }

  private static AlertDialog latestDialog() {
    Dialog latest = ShadowDialog.getLatestDialog();
    assertTrue(latest instanceof AlertDialog);
    return (AlertDialog) latest;
  }

  private static void click(Button button) {
    assertNotNull(button);
    button.performClick();
  }

  private static Button findButton(AlertDialog dialog, String text) {
    return findButton(dialog.getWindow().getDecorView(), text);
  }

  private static Button findButton(View view, String text) {
    if (view instanceof Button button && text.contentEquals(button.getText())) return button;
    if (view instanceof ViewGroup group) {
      for (int index = 0; index < group.getChildCount(); index++) {
        Button found = findButton(group.getChildAt(index), text);
        if (found != null) return found;
      }
    }
    return null;
  }

  private static TextView findText(AlertDialog dialog, String text) {
    return findText(dialog.getWindow().getDecorView(), text);
  }

  private static TextView findText(View view, String text) {
    if (view instanceof TextView shown && text.contentEquals(shown.getText())) return shown;
    if (view instanceof ViewGroup group) {
      for (int index = 0; index < group.getChildCount(); index++) {
        TextView found = findText(group.getChildAt(index), text);
        if (found != null) return found;
      }
    }
    return null;
  }
}
