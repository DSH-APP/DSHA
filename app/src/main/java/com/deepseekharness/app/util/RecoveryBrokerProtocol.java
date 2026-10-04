package com.deepseekharness.app.util;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Set;

/** 应急桥的固定路由与认证规则；确认、路径和任意命令不属于网络协议。 */
public final class RecoveryBrokerProtocol {
  private static final Set<String> ROUTES =
      Set.of("/v1/diagnostics", "/v1/targets", "/v1/read", "/v1/propose", "/v1/result");

  private RecoveryBrokerProtocol() {}

  public static void validate(HttpProtocol.Head head, int port, String token) throws IOException {
    if (head == null
        || !head.method.equals("POST")
        || !head.value("Origin").isEmpty()
        || head.values("Authorization").size() != 1
        || token == null
        || !token.matches("[a-f0-9]{64}")
        || !MessageDigest.isEqual(
            ("Bearer " + token).getBytes(StandardCharsets.US_ASCII),
            head.value("Authorization").getBytes(StandardCharsets.US_ASCII)))
      throw new IOException("REPAIR_UNAUTHORIZED");
    if (!head.value("Host").equals("127.0.0.1:" + port)) throw new IOException("REPAIR_HOST");
    if (!ROUTES.contains(head.target)) throw new IOException("REPAIR_ROUTE_UNKNOWN");
    var body = head.requestBody();
    if (body.kind != HttpProtocol.Kind.FIXED || body.length > 262144 || body.length < 2)
      throw new IOException("REPAIR_BODY_LIMIT");
  }

  /** YAML 多行凭据也整段隐藏；普通原文保留给诊断。 */
  public static String redact(String input) {
    if (input == null) return "";
    StringBuilder output = new StringBuilder();
    int hiddenIndent = -1;
    for (String line : input.split("\n", -1)) {
      int indent = 0;
      while (indent < line.length() && (line.charAt(indent) == ' ' || line.charAt(indent) == '\t'))
        indent++;
      if (hiddenIndent >= 0 && !line.trim().isEmpty() && indent > hiddenIndent) continue;
      if (!line.trim().isEmpty()) hiddenIndent = -1;
      if (line.matches(
          "(?i)^[ \\t]*[\"']?(?:[a-z0-9_]*api[_-]?key|authorization|proxy-authorization|cookie|set-cookie|access[_-]?token|refresh[_-]?token|token|password|passwd|secret)[\"']?[ \\t]*:[ \\t]*[|>][+-]?[ \\t]*(?:#.*)?$")) {
        output.append(line.substring(0, line.indexOf(':') + 1)).append(" ***");
        hiddenIndent = indent;
      } else output.append(SensitiveData.redact(line));
      output.append('\n');
    }
    if (output.length() > 0) output.setLength(output.length() - 1);
    return output.toString();
  }
}
