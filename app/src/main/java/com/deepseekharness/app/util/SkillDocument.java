package com.deepseekharness.app.util;

import java.io.IOException;
import java.io.StringReader;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.LinkedHashMap;
import java.util.regex.Pattern;
import org.yaml.snakeyaml.DumperOptions;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;
import org.yaml.snakeyaml.nodes.MappingNode;
import org.yaml.snakeyaml.nodes.Node;
import org.yaml.snakeyaml.nodes.ScalarNode;
import org.yaml.snakeyaml.nodes.SequenceNode;
import org.yaml.snakeyaml.nodes.Tag;
import org.yaml.snakeyaml.representer.Representer;
import org.yaml.snakeyaml.resolver.Resolver;

/** rc2 SKILL.md 的只读输入检查；保存原字节，不解释或执行正文中的命令。 */
public final class SkillDocument {
  public static final int MAX_BYTES = 1024 * 1024;
  public static final int MAX_NAME_LENGTH = 128;
  private static final Pattern NAME = Pattern.compile("[a-z0-9]+(?:-[a-z0-9]+)*");
  private static final Pattern BOOL = Pattern.compile("^(?:true|True|TRUE|false|False|FALSE)$");
  private static final Pattern INTEGER =
      Pattern.compile("^(?:[-+]?[0-9]+|0o[0-7]+|0x[0-9a-fA-F]+)$");
  private static final Pattern FLOAT =
      Pattern.compile(
          "^(?:[-+]?(?:[0-9]+\\.[0-9]*|\\.[0-9]+)(?:[eE][-+]?[0-9]+)?|[-+]?[0-9]+[eE][-+]?[0-9]+|[-+]?\\.(?:inf|Inf|INF)|\\.(?:nan|NaN|NAN))$");

  private static final class CoreResolver extends Resolver {
    @Override
    protected void addImplicitResolvers() {
      addImplicitResolver(Tag.BOOL, BOOL, "tTfF");
      addImplicitResolver(Tag.INT, INTEGER, "-+0123456789");
      addImplicitResolver(Tag.FLOAT, FLOAT, "-+0123456789.");
      addImplicitResolver(Tag.NULL, Pattern.compile("^(?:~|null|Null|NULL|)$"), "~nN\0");
    }
  }

  public final String name, description, text, body;
  public final boolean modelInvocable, userInvocable;

  private SkillDocument(
      String name,
      String description,
      String text,
      String body,
      boolean modelInvocable,
      boolean userInvocable) {
    this.name = name;
    this.description = description;
    this.text = text;
    this.body = body;
    this.modelInvocable = modelInvocable;
    this.userInvocable = userInvocable;
  }

  public static void filename(String name) throws IOException {
    if (!"SKILL.md".equals(name)) throw new IOException("SKILL_FILENAME");
  }

  public static SkillDocument parse(byte[] bytes) throws IOException {
    if (bytes == null || bytes.length == 0 || bytes.length > MAX_BYTES)
      throw new IOException("SKILL_SIZE");
    String text;
    try {
      text =
          StandardCharsets.UTF_8
              .newDecoder()
              .onMalformedInput(CodingErrorAction.REPORT)
              .onUnmappableCharacter(CodingErrorAction.REPORT)
              .decode(ByteBuffer.wrap(bytes))
              .toString();
    } catch (CharacterCodingException invalid) {
      throw new IOException("SKILL_ENCODING");
    }
    if (text.indexOf('\0') >= 0) throw new IOException("SKILL_ENCODING");
    int first = text.indexOf('\n');
    if (first < 0 || !line(text, 0, first).equals("---"))
      throw new IOException("SKILL_FRONTMATTER");
    int start = first + 1, closing = -1, body = text.length();
    for (int at = start; at <= text.length(); ) {
      int next = text.indexOf('\n', at), end = next < 0 ? text.length() : next;
      if (line(text, at, end).equals("---")) {
        closing = at;
        body = next < 0 ? text.length() : next + 1;
        break;
      }
      if (next < 0) break;
      at = next + 1;
    }
    if (closing < 0) throw new IOException("SKILL_FRONTMATTER");
    LoaderOptions options = new LoaderOptions();
    options.setAllowDuplicateKeys(false);
    options.setMaxAliasesForCollections(0);
    options.setNestingDepthLimit(24);
    options.setCodePointLimit(MAX_BYTES);
    options.setAllowRecursiveKeys(false);
    DumperOptions output = new DumperOptions();
    Yaml yaml =
        new Yaml(
            new SafeConstructor(options),
            new Representer(output),
            output,
            options,
            new CoreResolver());
    try {
      Node root = yaml.compose(new StringReader(text.substring(start, closing)));
      if (!(root instanceof MappingNode)) throw new IOException("SKILL_FRONTMATTER");
      check(root, Collections.newSetFromMap(new IdentityHashMap<>()), 0, new int[] {0});
      Map<String, Node> fields = new LinkedHashMap<>();
      for (var pair : ((MappingNode) root).getValue())
        fields.put(((ScalarNode) pair.getKeyNode()).getValue(), pair.getValueNode());
      String name = string(fields.get("name")), description = string(fields.get("description"));
      if (name == null || description == null) throw new IOException("SKILL_REQUIRED_FIELDS");
      if (name.length() > MAX_NAME_LENGTH || !NAME.matcher(name).matches())
        throw new IOException("SKILL_NAME");
      for (String legacy :
          new String[] {"disableModelInvocation", "modelInvocable", "userInvocable"})
        if (fields.containsKey(legacy)) throw new IOException("SKILL_INVOCATION");
      Boolean disabled = bool(fields.get("disable-model-invocation"));
      Boolean user = bool(fields.get("user-invocable"));
      return new SkillDocument(
          name,
          description,
          text,
          text.substring(body).trim(),
          !Boolean.TRUE.equals(disabled),
          !Boolean.FALSE.equals(user));
    } catch (IOException invalid) {
      throw invalid;
    } catch (RuntimeException invalid) {
      // YAML 诊断可能带用户正文；只向界面传递固定错误码。
      throw new IOException("SKILL_FRONTMATTER");
    }
  }

  private static String line(String text, int start, int end) {
    return text.substring(start, end > start && text.charAt(end - 1) == '\r' ? end - 1 : end);
  }

  private static String string(Node node) {
    return node instanceof ScalarNode
            && node.getTag().equals(Tag.STR)
            && !((ScalarNode) node).getValue().isEmpty()
        ? ((ScalarNode) node).getValue()
        : null;
  }

  private static Boolean bool(Node node) throws IOException {
    if (node == null) return null;
    if (!(node instanceof ScalarNode)) throw new IOException("SKILL_INVOCATION");
    String value = ((ScalarNode) node).getValue();
    if (node.getTag().equals(Tag.INT) || node.getTag().equals(Tag.FLOAT)) {
      try {
        double number =
            value.startsWith("0x")
                ? Long.parseLong(value.substring(2), 16)
                : value.startsWith("0o")
                    ? Long.parseLong(value.substring(2), 8)
                    : Double.parseDouble(value);
        if (number == 1) return true;
        if (number == 0) return false;
      } catch (NumberFormatException ignored) {
        // 不可表示的数字不属于官方布尔约定。
      }
    } else if (node.getTag().equals(Tag.STR) || node.getTag().equals(Tag.BOOL)) {
      switch (value.toLowerCase(Locale.ROOT)) {
        case "true", "yes", "on", "1":
          return true;
        case "false", "no", "off", "0":
          return false;
      }
    }
    throw new IOException("SKILL_INVOCATION");
  }

  private static void check(Node node, Set<Node> seen, int depth, int[] count) throws IOException {
    if (node == null
        || depth > 24
        || ++count[0] > 16_384
        || node.getAnchor() != null
        || !seen.add(node)) throw new IOException("SKILL_FRONTMATTER");
    if (node instanceof MappingNode) {
      if (!node.getTag().equals(Tag.MAP)) throw new IOException("SKILL_FRONTMATTER");
      Set<String> keys = new HashSet<>();
      for (var pair : ((MappingNode) node).getValue()) {
        if (!(pair.getKeyNode() instanceof ScalarNode)
            || !pair.getKeyNode().getTag().equals(Tag.STR)
            || !keys.add(((ScalarNode) pair.getKeyNode()).getValue()))
          throw new IOException("SKILL_FRONTMATTER");
        check(pair.getKeyNode(), seen, depth + 1, count);
        check(pair.getValueNode(), seen, depth + 1, count);
      }
    } else if (node instanceof SequenceNode) {
      if (!node.getTag().equals(Tag.SEQ)) throw new IOException("SKILL_FRONTMATTER");
      for (Node child : ((SequenceNode) node).getValue()) check(child, seen, depth + 1, count);
    } else if (node instanceof ScalarNode) {
      if (!Set.of(Tag.STR, Tag.NULL, Tag.BOOL, Tag.INT, Tag.FLOAT).contains(node.getTag()))
        throw new IOException("SKILL_FRONTMATTER");
      String value = ((ScalarNode) node).getValue();
      if (node.getTag().equals(Tag.BOOL) && !BOOL.matcher(value).matches()
          || node.getTag().equals(Tag.INT) && !INTEGER.matcher(value).matches()
          || node.getTag().equals(Tag.FLOAT) && !FLOAT.matcher(value).matches()
          || node.getTag().equals(Tag.NULL) && !value.matches("(?:~|null|Null|NULL|)"))
        throw new IOException("SKILL_FRONTMATTER");
    } else throw new IOException("SKILL_FRONTMATTER");
  }
}
