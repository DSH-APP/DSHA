package com.deepseekharness.app.util;

import static org.junit.Assert.*;

import java.util.ArrayList;
import java.util.List;
import org.junit.Test;

/** 敏感界面分类器（T119）：优先级、误伤取向与「读不出来不判安全」。 */
public final class SensitiveScreenClassifierTest {
  private static SensitiveScreenClassifier.Node node(
      String text,
      String description,
      String hint,
      String viewId,
      boolean password,
      boolean editable) {
    return new SensitiveScreenClassifier.Node(
        text, description, hint, viewId, "android.widget.EditText", password, editable, 0);
  }

  @Test
  public void passwordFieldWinsOverEveryOtherSignal() {
    var nodes =
        List.of(
            node("支付金额 100 元", "", "", "", false, false),
            node("", "", "", "com.example:id/password", true, true));
    var result = SensitiveScreenClassifier.classify("com.example.news", nodes);
    assertNotNull(result);
    assertEquals(SensitiveScreenPolicy.Category.PASSWORD, result.category);
    assertEquals("PASSWORD_FIELD", result.basis);
  }

  @Test
  public void passwordNodeTextIsDiscardedBeforePolicy() {
    var node = node("hunter2", "密码", "输入密码", "", true, true);
    assertEquals("", node.text);
    assertEquals("", node.description);
    assertEquals("", node.hint);
  }

  @Test
  public void otpKeywordsNeedAnEditableField() {
    var editable = List.of(node("请输入验证码", "", "", "", false, true));
    assertEquals(
        SensitiveScreenPolicy.Category.OTP,
        SensitiveScreenClassifier.classify("com.example.app", editable).category);

    var staticText = List.of(node("请输入验证码", "", "", "", false, false));
    assertNull(SensitiveScreenClassifier.classify("com.example.app", staticText));
  }

  @Test
  public void financeAppsArePaymentButChatAppsOnlyOnPaymentText() {
    assertEquals(
        SensitiveScreenPolicy.Category.PAYMENT,
        SensitiveScreenClassifier.classify(
                "com.icbc.mobile", List.of(node("首页", "", "", "", false, false)))
            .category);
    assertEquals(
        "PAYMENT_APP",
        SensitiveScreenClassifier.classify(
                "com.icbc.mobile", List.of(node("首页", "", "", "", false, false)))
            .basis);
    // 微信不能整包判支付，否则聊天也每次确认。
    assertNull(
        SensitiveScreenClassifier.classify(
            "com.tencent.mm", List.of(node("微信", "", "", "", false, false))));
    var paymentInWechat =
        List.of(node("确认支付 ¥12.00", "", "", "com.tencent.mm:id/pay", false, false));
    assertEquals(
        SensitiveScreenPolicy.Category.PAYMENT,
        SensitiveScreenClassifier.classify("com.tencent.mm", paymentInWechat).category);
  }

  @Test
  public void systemSecurityNeedsBothPackageAndPageWords() {
    var page = List.of(node("无障碍", "已关闭", "", "com.android.settings:id/row", false, false));
    assertEquals(
        SensitiveScreenPolicy.Category.SYSTEM_SECURITY,
        SensitiveScreenClassifier.classify("com.android.settings", page).category);
    var account = List.of(node("账户", "", "", "", false, false));
    assertEquals(
        SensitiveScreenPolicy.Category.SYSTEM_SECURITY,
        SensitiveScreenClassifier.classify("com.android.settings", account).category);
    // 普通设置首页（没有安全/账户等关键词）不整包拦截。
    assertNull(
        SensitiveScreenClassifier.classify(
            "com.android.settings", List.of(node("Wi‑Fi", "", "", "", false, false))));
    // 非系统包出现相同词也不升级为系统安全页。
    assertNull(
        SensitiveScreenClassifier.classify(
            "com.example.notes", List.of(node("账户", "", "", "", false, false))));
  }

  @Test
  public void unreadableTreeOnlyClassifiesKnownSensitiveApps() {
    assertEquals(
        SensitiveScreenPolicy.Category.SECURE_UNKNOWN,
        SensitiveScreenClassifier.unreadableSensitiveApp("com.icbc.mobile").category);
    assertNull(
        "empty package must not create a targetless prompt",
        SensitiveScreenClassifier.unreadableSensitiveApp(""));
    assertNull(SensitiveScreenClassifier.unreadableSensitiveApp("com.example.notes"));
  }

  @Test
  public void emptyEvidenceNeverClaimsSafety() {
    assertNull(SensitiveScreenClassifier.classify("com.example.app", new ArrayList<>()));
    assertNull(SensitiveScreenClassifier.classify("com.example.app", null));
  }
}
