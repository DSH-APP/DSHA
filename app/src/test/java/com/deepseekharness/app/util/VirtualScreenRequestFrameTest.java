package com.deepseekharness.app.util;

import static org.junit.Assert.*;

import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.Test;

/** 语义操作（node）与坐标/文本输入的帧号要求不同：前者与"当前第几帧"无关（issue #117）。 */
public final class VirtualScreenRequestFrameTest {
  private static Map<String, Object> node() {
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("generation", "gen-1");
    body.put("nodeId", "7:12345");
    body.put("action", "click");
    return body;
  }

  @Test
  public void nodeDoesNotRequireAFrameNumber() throws Exception {
    VscreenBridgeRequest request = VscreenBridgeRequest.json("/app/vscreen/node", node());
    assertEquals(VscreenBridgeRequest.Operation.NODE, request.operation);
    // 缺省帧号必须是"没有帧号"，不能变成 0 之类的假值。
    assertEquals(-1L, (long) request.execute(new Recorder()));
  }

  @Test
  public void nodeStillAcceptsAnExplicitFrameNumberForOlderCallers() throws Exception {
    Map<String, Object> body = node();
    body.put("frameSeq", 42);
    assertEquals(
        42L, (long) VscreenBridgeRequest.json("/app/vscreen/node", body).execute(new Recorder()));
  }

  @Test
  public void coordinateAndTextInputStillRequireAFrameNumber() {
    for (String query : new String[] {"generation=gen-1&x=1&y=2", "generation=gen-1&text=hi"}) {
      try {
        VscreenBridgeRequest.query("/app/vscreen/tap", query);
        fail("tap 缺帧号必须被拒：" + query);
      } catch (HttpProtocol.Failure failure) {
        assertEquals(400, failure.status);
        assertEquals("INVALID_frameSeq", failure.getMessage());
      }
    }
  }

  private static final class Recorder implements VscreenBridgeRequest.Actions<Long> {
    public Long create(String orientation) {
      return -2L;
    }

    public Long status() {
      return -2L;
    }

    public Long launch(String pkg) {
      return -2L;
    }

    public Long tree() {
      return -2L;
    }

    public Long node(String generation, long frame, String id, String action, String text) {
      assertEquals("gen-1", generation);
      assertEquals("7:12345", id);
      assertEquals("click", action);
      return frame;
    }

    public Long editor(
        String generation, String mode, String editorId, String text, int start, int end) {
      return -2L;
    }

    public Long touch(String generation, long frame, String stroke, int action, float x, float y) {
      return -2L;
    }

    public Long preview() {
      return -2L;
    }

    public Long action(String mode, String generation, long frame, String query) {
      return -2L;
    }

    public Long type(String generation, long frame, String text) {
      return -2L;
    }

    public Long close() {
      return -2L;
    }
  }
}
