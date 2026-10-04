package com.deepseekharness.app.util;

import com.deepseekharness.app.backup.BackupJson;
import java.io.*;
import java.net.*;
import java.nio.*;
import java.nio.charset.*;
import java.util.*;

/** Actual virtual-screen HTTP grammar, body budget and operation dispatch; no Android API. */
public final class VscreenBridgeRequest {
  public static final int MAX_BODY = 128 * 1024;
  public static final int BODY_TIMEOUT_MS = 10000;

  public enum Operation {
    CREATE,
    STATUS,
    LAUNCH,
    TREE,
    NODE,
    EDITOR,
    EDIT,
    SUBMIT,
    TOUCH,
    PREVIEW,
    SEE,
    TAP,
    SWIPE,
    KEY,
    TYPE,
    CLOSE
  }

  public final Operation operation;
  public final Map<String, String> parameters;

  private VscreenBridgeRequest(Operation operation, Map<String, String> parameters)
      throws HttpProtocol.Failure {
    this.operation = operation;
    this.parameters = Collections.unmodifiableMap(new LinkedHashMap<>(parameters));
    validate();
  }

  public interface Actions<T> {
    T create(String orientation);

    T status();

    T launch(String pkg);

    T tree();

    T node(String generation, long frame, String id, String action, String text);

    T editor(String generation, String mode, String editorId, String text, int start, int end);

    T touch(String generation, long frame, String stroke, int action, float x, float y);

    T preview();

    T action(String mode, String generation, long frame, String query);

    T type(String generation, long frame, String text);

    T close();
  }

  public <T> T execute(Actions<T> actions) {
    String gen = value("generation", ""), text = value("text", "");
    long frame = number("frameSeq", -1);
    switch (operation) {
      case CREATE:
        return actions.create(value("orientation", "portrait"));
      case STATUS:
        return actions.status();
      case LAUNCH:
        return actions.launch(value("package", ""));
      case TREE:
        return actions.tree();
      case NODE:
        return actions.node(gen, frame, value("nodeId", ""), value("action", ""), text);
      case EDITOR:
        return actions.editor(gen, "editor", "", "", 0, 0);
      case EDIT:
        return actions.editor(
            gen,
            "edit",
            value("editorId", ""),
            text,
            (int) number("start", text.length()),
            (int) number("end", number("start", text.length())));
      case SUBMIT:
        return actions.editor(gen, "submit", value("editorId", ""), "", 0, 0);
      case TOUCH:
        return actions.touch(
            gen,
            frame,
            value("stroke", ""),
            (int) number("action", -1),
            Float.parseFloat(value("x", "0")),
            Float.parseFloat(value("y", "0")));
      case PREVIEW:
      case SEE:
        return actions.preview();
      case TAP:
      case SWIPE:
      case KEY:
        return actions.action(operation.name().toLowerCase(Locale.ROOT), gen, frame, query());
      case TYPE:
        return actions.type(gen, frame, text);
      case CLOSE:
        return actions.close();
      default:
        throw new IllegalStateException("UNKNOWN_ROUTE");
    }
  }

  public static boolean postRoute(String route) {
    return Set.of(
            "/app/vscreen/edit", "/app/vscreen/submit", "/app/vscreen/node", "/app/vscreen/type")
        .contains(route);
  }

  public static Map<String, Object> readPost(
      HttpProtocol.Head head, InputStream input, Socket socket) throws IOException {
    String route = head.target.split("\\?", 2)[0];
    if (!head.method.equals("POST") || !postRoute(route))
      throw new HttpProtocol.Failure(405, "VSCREEN_POST_ROUTE");
    if (head.target.contains("?"))
      throw new HttpProtocol.Failure(400, "VSCREEN_POST_QUERY_AMBIGUOUS");
    HttpProtocol.Body body = head.requestBody();
    if (body.kind != HttpProtocol.Kind.FIXED || body.length < 1)
      throw new HttpProtocol.Failure(411, "VSCREEN_BODY_LENGTH_REQUIRED");
    if (body.length > MAX_BODY) throw new HttpProtocol.Failure(413, "VSCREEN_BODY_LIMIT");
    List<String> type = head.values("Content-Type");
    if (type.size() != 1
        || !type.get(0).matches("(?i)application/json(?:\\s*;\\s*charset=utf-8)?")
        || !head.value("Content-Encoding").isEmpty())
      throw new HttpProtocol.Failure(415, "VSCREEN_JSON_REQUIRED");
    long until = HttpProtocol.deadline(BODY_TIMEOUT_MS);
    if (!head.value("Expect").isEmpty() && socket != null) {
      socket
          .getOutputStream()
          .write("HTTP/1.1 100 Continue\r\n\r\n".getBytes(StandardCharsets.US_ASCII));
      socket.getOutputStream().flush();
    }
    ByteArrayOutputStream output = new ByteArrayOutputStream((int) body.length);
    byte[] bytes = new byte[8192];
    long left = body.length;
    while (left > 0) {
      long remaining = until - System.nanoTime();
      if (remaining <= 0) throw new SocketTimeoutException("VSCREEN_BODY_DEADLINE");
      if (socket != null) socket.setSoTimeout((int) Math.max(1, (remaining + 999999) / 1000000));
      int count = input.read(bytes, 0, (int) Math.min(left, bytes.length));
      if (count < 0) throw new HttpProtocol.Failure(400, "VSCREEN_BODY_TRUNCATED");
      if (count == 0) continue;
      output.write(bytes, 0, count);
      left -= count;
    }
    byte[] bodyBytes = output.toByteArray();
    try {
      StandardCharsets.UTF_8
          .newDecoder()
          .onMalformedInput(CodingErrorAction.REPORT)
          .onUnmappableCharacter(CodingErrorAction.REPORT)
          .decode(ByteBuffer.wrap(bodyBytes));
    } catch (CharacterCodingException bad) {
      throw new HttpProtocol.Failure(400, "VSCREEN_BODY_UTF8");
    }
    try {
      return BackupJson.read(bodyBytes, MAX_BODY);
    } catch (IOException invalid) {
      throw new HttpProtocol.Failure(400, "VSCREEN_BODY_JSON");
    }
  }

  public static VscreenBridgeRequest json(String route, Map<String, Object> body)
      throws HttpProtocol.Failure {
    Map<String, String> values = new LinkedHashMap<>();
    for (var entry : body.entrySet()) {
      boolean numeric =
          Set.of("frameSeq", "x", "y", "x1", "y1", "x2", "y2", "ms", "start", "end", "keycode")
                  .contains(entry.getKey())
              || entry.getKey().equals("action") && route.equals("/app/vscreen/touch");
      Object value = entry.getValue();
      if (numeric ? !(value instanceof Number) : !(value instanceof String))
        throw new HttpProtocol.Failure(400, "INVALID_ARGUMENT_TYPE");
      values.put(entry.getKey(), String.valueOf(value));
    }
    return create(route, values);
  }

  public static VscreenBridgeRequest query(String route, String query) throws HttpProtocol.Failure {
    if (query.length() > 512 * 1024) throw new HttpProtocol.Failure(413, "VSCREEN_QUERY_LIMIT");
    Map<String, String> values = new LinkedHashMap<>();
    for (String pair : query.split("&")) {
      if (pair.isEmpty()) continue;
      String[] pieces = pair.split("=", 2);
      try {
        String key = URLDecoder.decode(pieces[0], "UTF-8"),
            value = URLDecoder.decode(pieces.length == 2 ? pieces[1] : "", "UTF-8");
        if (values.putIfAbsent(key, value) != null)
          throw new HttpProtocol.Failure(400, "DUPLICATE_ARGUMENT");
      } catch (IllegalArgumentException | UnsupportedEncodingException invalid) {
        throw new HttpProtocol.Failure(400, "INVALID_ARGUMENT_ENCODING");
      }
    }
    return create(route, values);
  }

  private static VscreenBridgeRequest create(String route, Map<String, String> values)
      throws HttpProtocol.Failure {
    String name = VirtualScreenRoutes.operation(route);
    if (name.isEmpty()) throw new HttpProtocol.Failure(404, "UNKNOWN_ROUTE");
    // Read compatibility for the previous MCP editor endpoint, without mistaking edit/submit for
    // get.
    if (name.equals("editor")) {
      String op = values.getOrDefault("operation", values.getOrDefault("op", "get"));
      if (values.containsKey("op")
          && values.containsKey("operation")
          && !values.get("op").equals(values.get("operation")))
        throw new HttpProtocol.Failure(400, "INVALID_EDITOR_OPERATION");
      if (!Set.of("get", "edit", "submit").contains(op))
        throw new HttpProtocol.Failure(400, "INVALID_EDITOR_OPERATION");
      name = op.equals("get") ? "editor" : op;
      values.remove("operation");
      values.remove("op");
    }
    return new VscreenBridgeRequest(Operation.valueOf(name.toUpperCase(Locale.ROOT)), values);
  }

  public String route() {
    return "/app/vscreen/" + operation.name().toLowerCase(Locale.ROOT);
  }

  public String query() {
    StringJoiner query = new StringJoiner("&");
    for (var row : parameters.entrySet())
      try {
        query.add(
            URLEncoder.encode(row.getKey(), "UTF-8")
                + "="
                + URLEncoder.encode(row.getValue(), "UTF-8"));
      } catch (UnsupportedEncodingException impossible) {
        throw new AssertionError(impossible);
      }
    return query.toString();
  }

  private String value(String name, String fallback) {
    return parameters.getOrDefault(name, fallback);
  }

  private long number(String name, long fallback) {
    return parameters.containsKey(name) ? Long.parseLong(parameters.get(name)) : fallback;
  }

  private void need(String name, int length) throws HttpProtocol.Failure {
    String value = parameters.get(name);
    if (value == null || value.isEmpty() || value.length() > length)
      throw new HttpProtocol.Failure(400, "INVALID_" + name);
  }

  private void integer(String name, long min, long max, boolean required)
      throws HttpProtocol.Failure {
    if (!parameters.containsKey(name)) {
      if (required) throw new HttpProtocol.Failure(400, "INVALID_" + name);
      return;
    }
    try {
      long value = Long.parseLong(parameters.get(name));
      if (value < min || value > max) throw new NumberFormatException();
    } catch (NumberFormatException invalid) {
      throw new HttpProtocol.Failure(400, "INVALID_" + name);
    }
  }

  private void coordinate(String name) throws HttpProtocol.Failure {
    need(name, 80);
    try {
      float value = Float.parseFloat(parameters.get(name));
      if (Float.isNaN(value) || Float.isInfinite(value) || value < 0)
        throw new NumberFormatException();
    } catch (NumberFormatException invalid) {
      throw new HttpProtocol.Failure(400, "INVALID_" + name);
    }
  }

  private void validate() throws HttpProtocol.Failure {
    Set<String> allowed = new HashSet<>();
    switch (operation) {
      case CREATE:
        allowed.add("orientation");
        if (!Set.of("portrait", "landscape").contains(value("orientation", "portrait")))
          throw new HttpProtocol.Failure(400, "INVALID_orientation");
        break;
      case LAUNCH:
        allowed.add("package");
        need("package", 160);
        if (!parameters.get("package").matches("[A-Za-z][A-Za-z0-9_]*(?:\\.[A-Za-z0-9_]+)+"))
          throw new HttpProtocol.Failure(400, "INVALID_package");
        break;
      case STATUS:
      case TREE:
      case PREVIEW:
      case SEE:
      case CLOSE:
        break;
      case EDITOR:
      case EDIT:
      case SUBMIT:
        allowed.addAll(List.of("generation", "editorId", "text", "start", "end"));
        need("generation", 80);
        if (operation != Operation.EDITOR) need("editorId", 160);
        if (operation == Operation.EDIT) {
          if (!parameters.containsKey("text") || parameters.get("text").length() > 16000)
            throw new HttpProtocol.Failure(400, "INVALID_text");
          integer("start", 0, parameters.get("text").length(), false);
          integer("end", 0, parameters.get("text").length(), false);
        }
        break;
      default:
        allowed.addAll(List.of("generation", "frameSeq"));
        need("generation", 80);
        integer("frameSeq", 1, Long.MAX_VALUE, true);
        if (operation == Operation.NODE) {
          allowed.addAll(List.of("nodeId", "action", "text"));
          need("nodeId", 80);
          if (!Set.of(
                  "click", "long_click", "scroll_forward", "scroll_backward", "focus", "set_text")
              .contains(value("action", ""))) throw new HttpProtocol.Failure(400, "INVALID_action");
          if (value("action", "").equals("set_text") && !parameters.containsKey("text")
              || value("text", "").length() > 16000)
            throw new HttpProtocol.Failure(400, "INVALID_text");
        } else if (operation == Operation.TYPE) {
          allowed.add("text");
          need("text", 2000);
        } else if (operation == Operation.KEY) {
          allowed.add("keycode");
          integer("keycode", 0, 100, true);
          if (!Set.of("3", "4", "66", "67").contains(value("keycode", "")))
            throw new HttpProtocol.Failure(400, "INVALID_keycode");
        } else if (operation == Operation.TAP) {
          allowed.addAll(List.of("x", "y"));
          coordinate("x");
          coordinate("y");
        } else if (operation == Operation.SWIPE) {
          allowed.addAll(List.of("x1", "y1", "x2", "y2", "ms"));
          for (String key : List.of("x1", "y1", "x2", "y2")) coordinate(key);
          integer("ms", 50, 3000, false);
        } else if (operation == Operation.TOUCH) {
          allowed.addAll(List.of("stroke", "action", "x", "y"));
          need("stroke", 64);
          if (!value("stroke", "").matches("[a-f0-9-]{16,64}"))
            throw new HttpProtocol.Failure(400, "INVALID_stroke");
          integer("action", 0, 3, true);
          coordinate("x");
          coordinate("y");
        }
    }
    if (!allowed.containsAll(parameters.keySet()))
      throw new HttpProtocol.Failure(400, "UNKNOWN_ARGUMENT");
    if (parameters.containsKey("text") && parameters.get("text").length() > 16000)
      throw new HttpProtocol.Failure(400, "INVALID_text");
  }
}
