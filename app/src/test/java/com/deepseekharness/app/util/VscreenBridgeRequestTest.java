package com.deepseekharness.app.util;

import org.junit.Test;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import static org.junit.Assert.*;

public class VscreenBridgeRequestTest {
  static final class Actions implements VscreenBridgeRequest.Actions<String> {
    String called, gen, id, text;
    int start, end;

    public String create(String value) {
      return called = "create";
    }

    public String status() {
      return called = "status";
    }

    public String launch(String value) {
      return called = "launch";
    }

    public String tree() {
      return called = "tree";
    }

    public String node(String g, long f, String n, String a, String t) {
      gen = g;
      id = n;
      text = t;
      return called = "node:" + a;
    }

    public String editor(String g, String mode, String e, String t, int s, int n) {
      gen = g;
      id = e;
      text = t;
      start = s;
      end = n;
      return called = mode;
    }

    public String touch(String g, long f, String s, int a, float x, float y) {
      return called = "touch";
    }

    public String preview() {
      return called = "preview";
    }

    public String action(String mode, String g, long frame, String query) {
      return called = mode;
    }

    public String type(String g, long f, String t) {
      return called = "type";
    }

    public String close() {
      return called = "close";
    }
  }

  @Test
  public void operationSpecificProductionDispatcherCallsGetEditAndSubmitSeparately()
      throws Exception {
    Actions actions = new Actions();
    assertEquals(
        "editor",
        VscreenBridgeRequest.query("/app/vscreen/editor", "generation=gen").execute(actions));
    assertEquals(
        "edit",
        VscreenBridgeRequest.json(
                "/app/vscreen/edit", Map.of("generation", "gen", "editorId", "3:42", "text", "正文"))
            .execute(actions));
    assertEquals("gen", actions.gen);
    assertEquals("3:42", actions.id);
    assertEquals("正文", actions.text);
    assertEquals(2, actions.start);
    assertEquals(2, actions.end);
    assertEquals(
        "submit",
        VscreenBridgeRequest.json(
                "/app/vscreen/submit", Map.of("generation", "gen", "editorId", "3:42"))
            .execute(actions));
    assertEquals(
        "edit",
        VscreenBridgeRequest.query(
                "/app/vscreen/editor",
                "generation=gen&editorId=3%3A42&operation=edit&text=old&start=1")
            .execute(actions));
    assertEquals(1, actions.start);
    assertEquals(1, actions.end);
  }

  @Test
  public void clickDoesNotRequireTextButSetTextRequiresItsRealPayload() throws Exception {
    Actions actions = new Actions();
    assertEquals(
        "node:click",
        VscreenBridgeRequest.json(
                "/app/vscreen/node",
                Map.of("generation", "gen", "frameSeq", 1, "nodeId", "node", "action", "click"))
            .execute(actions));
    assertThrows(
        HttpProtocol.Failure.class,
        () ->
            VscreenBridgeRequest.json(
                "/app/vscreen/node",
                Map.of(
                    "generation", "gen", "frameSeq", 1, "nodeId", "node", "action", "set_text")));
    assertThrows(
        HttpProtocol.Failure.class,
        () ->
            VscreenBridgeRequest.json(
                "/app/vscreen/edit",
                Map.of("generation", "gen", "editorId", "e", "text", "x", "start", 2)));
    assertThrows(
        HttpProtocol.Failure.class,
        () ->
            VscreenBridgeRequest.json(
                "/app/vscreen/edit", Map.of("generation", "gen", "editorId", "e", "text", 7)));
  }

  static HttpProtocol.Head post(int length, String type) throws Exception {
    return HttpProtocol.parse(
        "POST /app/vscreen/edit HTTP/1.1\r\nHost: 127.0.0.1:3090\r\nContent-Type: "
            + type
            + "\r\nContent-Length: "
            + length
            + "\r\n\r\n",
        true);
  }

  @Test
  public void actualLoopbackHttpHeadAndBufferedBodyPreserve16000ChineseCharacters()
      throws Exception {
    String text = "中".repeat(16000),
        json = "{\"generation\":\"gen\",\"editorId\":\"3:42\",\"text\":\"" + text + "\"}";
    byte[] body = json.getBytes(StandardCharsets.UTF_8);
    try (ServerSocket server = new ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"));
        Socket client = new Socket("127.0.0.1", server.getLocalPort());
        Socket accepted = server.accept()) {
      String header =
          "POST /app/vscreen/edit HTTP/1.1\r\nHost: 127.0.0.1:3090\r\nContent-Type: application/json; charset=utf-8\r\nContent-Length: "
              + body.length
              + "\r\n\r\n";
      client.getOutputStream().write(header.getBytes(StandardCharsets.US_ASCII));
      client.getOutputStream().write(body);
      client.getOutputStream().flush();
      InputStream in = new BufferedInputStream(accepted.getInputStream(), 8192);
      var head =
          HttpProtocol.readHead(
              in, accepted, HttpProtocol.BRIDGE, HttpProtocol.deadline(5000), true);
      var request =
          VscreenBridgeRequest.json(
              "/app/vscreen/edit", VscreenBridgeRequest.readPost(head, in, accepted));
      Actions actions = new Actions();
      assertEquals("edit", request.execute(actions));
      assertEquals(text, actions.text);
      assertEquals(16000, actions.start);
    }
    String get =
        "GET /app/vscreen/edit?generation=gen&editorId=3%3A42&text="
            + URLEncoder.encode(text, "UTF-8")
            + " HTTP/1.1\r\nHost: 127.0.0.1:3090\r\n\r\n";
    assertEquals(
        431, assertThrows(HttpProtocol.Failure.class, () -> HttpProtocol.parse(get, true)).status);
    assertEquals(
        98304,
        HttpProtocol.BRIDGE.firstLine); // Existing 8192-character command allowance unchanged.
  }

  @Test
  public void oversizedTruncatedDuplicateJsonAndAmbiguousBodyAreRejectedBeforeDispatch()
      throws Exception {
    InputStream forbidden =
        new InputStream() {
          public int read() {
            throw new AssertionError("must reject length before reading");
          }
        };
    assertEquals(
        413,
        assertThrows(
                HttpProtocol.Failure.class,
                () ->
                    VscreenBridgeRequest.readPost(
                        post(VscreenBridgeRequest.MAX_BODY + 1, "application/json"),
                        forbidden,
                        null))
            .status);
    byte[] duplicate = "{\"text\":\"a\",\"text\":\"b\"}".getBytes(StandardCharsets.UTF_8);
    assertEquals(
        400,
        assertThrows(
                HttpProtocol.Failure.class,
                () ->
                    VscreenBridgeRequest.readPost(
                        post(duplicate.length, "application/json"),
                        new ByteArrayInputStream(duplicate),
                        null))
            .status);
    assertEquals(
        400,
        assertThrows(
                HttpProtocol.Failure.class,
                () ->
                    VscreenBridgeRequest.readPost(
                        post(10, "application/json"),
                        new ByteArrayInputStream(new byte[] {1}),
                        null))
            .status);
    assertEquals(
        415,
        assertThrows(
                HttpProtocol.Failure.class,
                () -> VscreenBridgeRequest.readPost(post(1, "text/plain"), forbidden, null))
            .status);
    assertThrows(
        HttpProtocol.Failure.class,
        () ->
            VscreenBridgeRequest.query(
                "/app/vscreen/editor", "generation=gen&operation=get&op=submit"));
    assertThrows(
        HttpProtocol.Failure.class,
        () ->
            VscreenBridgeRequest.query(
                "/app/vscreen/unknown/edit", "generation=gen&editorId=e&text=x"));
    assertThrows(
        HttpProtocol.Failure.class,
        () ->
            VscreenBridgeRequest.json(
                "/app/vscreen/edit",
                Map.of(
                    "generation",
                    "gen",
                    "editorId",
                    "e",
                    "text",
                    "x",
                    "expectedPackage",
                    "other")));
  }
}
