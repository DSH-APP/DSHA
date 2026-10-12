package com.deepseekharness.app.util;

import static org.junit.Assert.*;

import org.junit.Test;

/**
 * #140 问题 1 的回归契约：写操作「执行前被拒（动作没落地）」与「执行后目标/运行状态已变（动作可能已经落地）」
 * 必须在响应上分开。调用方仅凭响应就能判断能不能重试，不必先 see 复核、也不必读日志。
 */
public class ScreenActionResultTest {

  @Test
  public void preExecutionRefusalKeepsTheNotExecutedCode() {
    // 前置拒绝的语义是「没有动作落地」，错误码与历史行为逐字一致。
    assertEquals(
        "{\"ok\":false,\"error\":\"SCREEN_TARGET_CHANGED\"}", ScreenActionResult.notExecuted());
  }

  @Test
  public void executedActionKeepsItsRealResultInsteadOfTheNotExecutedCode() {
    String result = "{\"ok\":true,\"generation\":\"g1\",\"frameSeq\":12}";
    String response = ScreenActionResult.executed(result, true, false);
    assertFalse("执行后校验失败不能再复用「未执行」错误码", response.contains(ScreenActionResult.NOT_EXECUTED_ERROR));
    assertEquals(
        "{\"ok\":true,\"generation\":\"g1\",\"frameSeq\":12,\"targetChangedAfterExecution\":true}",
        response);
  }

  @Test
  public void runChangeAfterExecutionUsesItsOwnMarker() {
    assertEquals(
        "{\"ok\":true,\"runChangedAfterExecution\":true}",
        ScreenActionResult.executed("{\"ok\":true}", false, true));
  }

  @Test
  public void bothChangesAreMarkedSeparately() {
    assertEquals(
        "{\"ok\":true,\"targetChangedAfterExecution\":true,\"runChangedAfterExecution\":true}",
        ScreenActionResult.executed("{\"ok\":true}", true, true));
  }

  @Test
  public void unchangedPostExecutionCheckReturnsTheResultVerbatim() {
    String result = "{\"ok\":true,\"generation\":\"g1\"}";
    assertEquals(result, ScreenActionResult.executed(result, false, false));
  }

  @Test
  public void failedActionResultIsNotRelabelledAsExecuted() {
    // 动作本身失败（注入被拒/帧过期）时不能贴「已执行」标记：那会让调用方以为动作落了地。
    String rejected = "{\"ok\":false,\"error\":\"INPUT_REJECTED\"}";
    assertEquals(rejected, ScreenActionResult.executed(rejected, true, true));
  }

  @Test
  public void nonObjectResultIsReturnedVerbatim() {
    for (String raw :
        new String[] {"[ERR] boom", "", "[{\"ok\":true}]", "{\"ok\":true", "not json"})
      assertEquals(raw, ScreenActionResult.executed(raw, true, true));
    assertNull(ScreenActionResult.executed(null, true, true));
  }

  @Test
  public void objectWithoutTopLevelOkIsReturnedVerbatim() {
    for (String raw : new String[] {"{}", "{\"detail\":{\"ok\":true}}", "{\"error\":\"X\"}"})
      assertEquals(raw, ScreenActionResult.executed(raw, true, true));
  }

  @Test
  public void markerIsNotDuplicatedWhenTheManagerAlreadyMarkedIt() {
    // 管理器层与桥层都会判一次执行后状态，两层叠加不能写出重复键。
    String once = "{\"ok\":true,\"targetChangedAfterExecution\":true}";
    assertEquals(once, ScreenActionResult.executed(once, true, false));
    assertEquals(
        "{\"ok\":true,\"targetChangedAfterExecution\":true,\"runChangedAfterExecution\":true}",
        ScreenActionResult.executed(once, true, true));
  }

  @Test
  public void nestedOrEscapedOkDoesNotCountAsExecuted() {
    assertEquals(
        "{\"msg\":\"\\\"ok\\\":true\"}",
        ScreenActionResult.executed("{\"msg\":\"\\\"ok\\\":true\"}", true, true));
    // 字符串值里被转义的 \"ok\":false 不是顶层键：顶层 ok=true，标记照常追加。
    assertEquals(
        "{\"ok\":true,\"text\":\"say \\\"ok\\\":false\",\"runChangedAfterExecution\":true}",
        ScreenActionResult.executed(
            "{\"ok\":true,\"text\":\"say \\\"ok\\\":false\"}", false, true));
  }

  @Test
  public void whitespaceAroundTopLevelKeysIsTolerated() {
    assertEquals(
        "{ \"ok\" : true , \"frameSeq\" : 3 ,\"targetChangedAfterExecution\":true}",
        ScreenActionResult.executed("{ \"ok\" : true , \"frameSeq\" : 3 }", true, false));
  }

  @Test
  public void notExecutedReplyIsTheStableCodeWithoutAnyExceptionClassName() {
    // 主屏无障碍路径把它当用户可见文本回给调用方：只能是稳定码，不能是 Java 异常类名（#145 评审 Q3）。
    assertEquals(
        "[ERR] " + ScreenActionResult.NOT_EXECUTED_ERROR, ScreenActionResult.NOT_EXECUTED_REPLY);
    assertEquals("[ERR] SCREEN_TARGET_CHANGED", ScreenActionResult.NOT_EXECUTED_REPLY);
    assertFalse(ScreenActionResult.NOT_EXECUTED_REPLY.contains("Exception"));
    assertFalse(ScreenActionResult.NOT_EXECUTED_REPLY.contains("."));
  }
}
