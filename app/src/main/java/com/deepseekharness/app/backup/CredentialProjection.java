package com.deepseekharness.app.backup;

import java.io.File;
import java.io.IOException;
import java.io.StringReader;
import java.io.StringWriter;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.HashSet;
import java.util.Set;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.DumperOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;
import org.yaml.snakeyaml.nodes.MappingNode;
import org.yaml.snakeyaml.nodes.Node;
import org.yaml.snakeyaml.nodes.ScalarNode;
import org.yaml.snakeyaml.nodes.SequenceNode;
import org.yaml.snakeyaml.nodes.Tag;
import org.yaml.snakeyaml.events.DocumentStartEvent;
import org.yaml.snakeyaml.representer.Representer;
import org.yaml.snakeyaml.resolver.Resolver;
import java.util.regex.Pattern;

/** Data-only credential projection; parser failures never include secret-bearing YAML diagnostics. */
public final class CredentialProjection {
  public static final String FILE = ".credentials.yaml";
  public static final int LIMIT = 1024 * 1024;
  private static final String MACHINE_RECORD = "client-connection/";
  private static final Pattern CORE_BOOL =
      Pattern.compile("^(?:true|True|TRUE|false|False|FALSE)$");
  private static final Pattern CORE_INT =
      Pattern.compile("^(?:[-+]?[0-9]+|0o[0-7]+|0x[0-9a-fA-F]+)$");
  private static final Pattern CORE_FLOAT =
      Pattern.compile(
          "^(?:[-+]?(?:[0-9]+\\.[0-9]*|\\.[0-9]+)(?:[eE][-+]?[0-9]+)?|[-+]?[0-9]+[eE][-+]?[0-9]+|[-+]?\\.(?:inf|Inf|INF)|\\.(?:nan|NaN|NAN))$");

  /** The host never constructs scalar values; this resolver also keeps the rc2 CORE 1.2 tags. */
  private static final class CoreResolver extends Resolver {
    @Override
    protected void addImplicitResolvers() {
      addImplicitResolver(Tag.BOOL, CORE_BOOL, "tTfF");
      addImplicitResolver(Tag.INT, CORE_INT, "-+0123456789");
      addImplicitResolver(Tag.FLOAT, CORE_FLOAT, "-+0123456789.");
      addImplicitResolver(Tag.NULL, Pattern.compile("^(?:~|null|Null|NULL|)$"), "~nN\0");
    }
  }

  private CredentialProjection() {}

  public static byte[] portable(byte[] source) throws IOException {
    if (source == null || source.length > LIMIT)
      throw new IOException("CREDENTIAL_PROJECTION_LIMIT");
    String text;
    try {
      text =
          StandardCharsets.UTF_8
              .newDecoder()
              .onMalformedInput(CodingErrorAction.REPORT)
              .onUnmappableCharacter(CodingErrorAction.REPORT)
              .decode(ByteBuffer.wrap(source))
              .toString();
    } catch (CharacterCodingException invalid) {
      throw new IOException("CREDENTIAL_PROJECTION_ENCODING");
    }
    if (text.trim().isEmpty()) return source.clone();
    LoaderOptions options = new LoaderOptions();
    options.setAllowDuplicateKeys(false);
    options.setMaxAliasesForCollections(0);
    options.setNestingDepthLimit(32);
    options.setCodePointLimit(LIMIT);
    options.setAllowRecursiveKeys(false);
    options.setProcessComments(true);
    DumperOptions output = new DumperOptions();
    output.setProcessComments(true);
    Yaml yaml =
        new Yaml(
            new SafeConstructor(options),
            new Representer(output),
            output,
            options,
            new CoreResolver());
    try {
      int events = 0;
      for (var event : yaml.parse(new StringReader(text))) {
        if (++events > 100_000) throw new IOException("CREDENTIAL_PROJECTION_LIMIT");
        if (event instanceof DocumentStartEvent
            && ((DocumentStartEvent) event).getVersion() != null)
          throw new IOException("CREDENTIAL_PROJECTION_SCHEMA");
      }
      Node document = yaml.compose(new StringReader(text));
      if (document == null
          || document instanceof MappingNode
              && document.getTag().equals(Tag.COMMENT)
              && ((MappingNode) document).getValue().isEmpty()) return source.clone();
      if (document.getTag().equals(Tag.NULL)) {
        if (!(document instanceof ScalarNode)
            || !((ScalarNode) document).getValue().matches("(?:~|null|Null|NULL|)"))
          throw new IOException("CREDENTIAL_PROJECTION_TAG");
        return source.clone();
      }
      if (!(document instanceof MappingNode)) throw new IOException("CREDENTIAL_PROJECTION_FORMAT");
      check(document, Collections.newSetFromMap(new IdentityHashMap<>()), 0, new int[] {0});
      schema((MappingNode) document);
      if (field((MappingNode) document, "version") == null) return source.clone();
      Node records = null;
      for (var pair : ((MappingNode) document).getValue())
        if (((ScalarNode) pair.getKeyNode()).getValue().equals("records"))
          records = pair.getValueNode();
      if (records == null || records.getTag().equals(Tag.NULL)) return source.clone();
      if (!(records instanceof MappingNode)) throw new IOException("CREDENTIAL_PROJECTION_FORMAT");
      boolean removed =
          ((MappingNode) records)
              .getValue()
              .removeIf(
                  pair -> ((ScalarNode) pair.getKeyNode()).getValue().startsWith(MACHINE_RECORD));
      if (!removed) return source.clone();
      StringWriter rendered = new StringWriter();
      yaml.serialize(document, rendered);
      byte[] projected = rendered.toString().getBytes(StandardCharsets.UTF_8);
      if (projected.length > LIMIT) throw new IOException("CREDENTIAL_PROJECTION_LIMIT");
      return projected;
    } catch (IOException failure) {
      throw failure;
    } catch (RuntimeException invalid) {
      // SnakeYAML errors often quote the source. Keep only a stable failure code.
      throw new IOException("CREDENTIAL_PROJECTION_FAILED");
    }
  }

  private static Node field(MappingNode map, String key) {
    for (var pair : map.getValue())
      if (((ScalarNode) pair.getKeyNode()).getValue().equals(key)) return pair.getValueNode();
    return null;
  }

  private static void fields(MappingNode map, Set<String> allowed) throws IOException {
    for (var pair : map.getValue())
      if (!allowed.contains(((ScalarNode) pair.getKeyNode()).getValue()))
        throw new IOException("CREDENTIAL_PROJECTION_FIELDS");
  }

  private static boolean string(Node node, boolean nonempty) {
    return node instanceof ScalarNode
        && node.getTag().equals(Tag.STR)
        && (!nonempty || !((ScalarNode) node).getValue().isEmpty());
  }

  private static double number(ScalarNode scalar) throws IOException {
    try {
      String value = scalar.getValue();
      if (scalar.getTag().equals(Tag.INT))
        return value.startsWith("0x")
            ? new java.math.BigInteger(value.substring(2), 16).doubleValue()
            : value.startsWith("0o")
                ? new java.math.BigInteger(value.substring(2), 8).doubleValue()
                : new java.math.BigInteger(value, 10).doubleValue();
      return Double.parseDouble(value);
    } catch (NumberFormatException invalid) {
      throw new IOException("CREDENTIAL_PROJECTION_NUMBER");
    }
  }

  /** Exact admitted rc2 layout, plus its recognized pre-release flat-reference reader. */
  private static void schema(MappingNode root) throws IOException {
    if (root.getValue().isEmpty()) return;
    Node version = field(root, "version");
    if (version == null) {
      for (var pair : root.getValue())
        if (!((ScalarNode) pair.getKeyNode()).getValue().matches("[A-Za-z_][A-Za-z0-9_]*")
            || !string(pair.getValueNode(), true))
          throw new IOException("CREDENTIAL_PROJECTION_SCHEMA");
      return;
    }
    if (!(version instanceof ScalarNode)
        || !Set.of(Tag.INT, Tag.FLOAT).contains(version.getTag())
        || number((ScalarNode) version) != 1) throw new IOException("CREDENTIAL_PROJECTION_SCHEMA");
    fields(root, Set.of("version", "refs", "records"));
    Node refs = field(root, "refs");
    if (refs != null && !refs.getTag().equals(Tag.NULL)) {
      if (!(refs instanceof MappingNode)) throw new IOException("CREDENTIAL_PROJECTION_SCHEMA");
      for (var pair : ((MappingNode) refs).getValue())
        if (!((ScalarNode) pair.getKeyNode()).getValue().matches("[A-Za-z_][A-Za-z0-9_]*")
            || !string(pair.getValueNode(), true))
          throw new IOException("CREDENTIAL_PROJECTION_SCHEMA");
    }
    Node records = field(root, "records");
    if (records == null || records.getTag().equals(Tag.NULL)) return;
    if (!(records instanceof MappingNode)) throw new IOException("CREDENTIAL_PROJECTION_SCHEMA");
    for (var pair : ((MappingNode) records).getValue()) {
      if (!((ScalarNode) pair.getKeyNode()).getValue().matches("[a-z][a-z0-9-]*/[a-z][a-z0-9-]*")
          || !(pair.getValueNode() instanceof MappingNode))
        throw new IOException("CREDENTIAL_PROJECTION_SCHEMA");
      MappingNode record = (MappingNode) pair.getValueNode();
      Node kind = field(record, "kind");
      if (!string(kind, true)) throw new IOException("CREDENTIAL_PROJECTION_SCHEMA");
      String name = ((ScalarNode) kind).getValue();
      if (name.equals("api-key")) {
        fields(record, Set.of("kind", "key", "env"));
        Node key = field(record, "key"), env = field(record, "env");
        if (key != null && !string(key, true))
          throw new IOException("CREDENTIAL_PROJECTION_SCHEMA");
        if (env != null) {
          if (!(env instanceof MappingNode)) throw new IOException("CREDENTIAL_PROJECTION_SCHEMA");
          for (var value : ((MappingNode) env).getValue())
            if (!((ScalarNode) value.getKeyNode()).getValue().matches("[A-Za-z_][A-Za-z0-9_]*")
                || !string(value.getValueNode(), true))
              throw new IOException("CREDENTIAL_PROJECTION_SCHEMA");
        }
      } else if (name.equals("grant")) {
        fields(record, Set.of("kind", "payload"));
        Node payload = field(record, "payload");
        if (payload == null) throw new IOException("CREDENTIAL_PROJECTION_SCHEMA");
        json(payload);
      } else throw new IOException("CREDENTIAL_PROJECTION_SCHEMA");
    }
  }

  private static void json(Node value) throws IOException {
    if (value instanceof ScalarNode && Set.of(Tag.INT, Tag.FLOAT).contains(value.getTag())) {
      double number = number((ScalarNode) value);
      if (Double.isNaN(number) || Double.isInfinite(number))
        throw new IOException("CREDENTIAL_PROJECTION_NUMBER");
    } else if (value instanceof MappingNode) {
      for (var pair : ((MappingNode) value).getValue()) json(pair.getValueNode());
    } else if (value instanceof SequenceNode) {
      for (var child : ((SequenceNode) value).getValue()) json(child);
    }
  }

  private static void check(Node node, Set<Node> seen, int depth, int[] count) throws IOException {
    if (node == null || depth > 32 || ++count[0] > 100_000)
      throw new IOException("CREDENTIAL_PROJECTION_LIMIT");
    if (node.getAnchor() != null || !seen.add(node))
      throw new IOException("CREDENTIAL_PROJECTION_ALIAS");
    if (node instanceof MappingNode) {
      if (!node.getTag().equals(Tag.MAP)) throw new IOException("CREDENTIAL_PROJECTION_TAG");
      Set<String> keys = new HashSet<>();
      for (var pair : ((MappingNode) node).getValue()) {
        if (!(pair.getKeyNode() instanceof ScalarNode)
            || !pair.getKeyNode().getTag().equals(Tag.STR))
          throw new IOException("CREDENTIAL_PROJECTION_KEY");
        if (!keys.add(((ScalarNode) pair.getKeyNode()).getValue()))
          throw new IOException("CREDENTIAL_PROJECTION_DUPLICATE");
        check(pair.getKeyNode(), seen, depth + 1, count);
        check(pair.getValueNode(), seen, depth + 1, count);
      }
    } else if (node instanceof SequenceNode) {
      if (!node.getTag().equals(Tag.SEQ)) throw new IOException("CREDENTIAL_PROJECTION_TAG");
      for (Node child : ((SequenceNode) node).getValue()) check(child, seen, depth + 1, count);
    } else if (node instanceof ScalarNode) {
      if (!Set.of(Tag.STR, Tag.NULL, Tag.BOOL, Tag.INT, Tag.FLOAT).contains(node.getTag()))
        throw new IOException("CREDENTIAL_PROJECTION_TAG");
      String value = ((ScalarNode) node).getValue();
      if (node.getTag().equals(Tag.BOOL) && !CORE_BOOL.matcher(value).matches()
          || node.getTag().equals(Tag.INT) && !CORE_INT.matcher(value).matches()
          || node.getTag().equals(Tag.FLOAT) && !CORE_FLOAT.matcher(value).matches()
          || node.getTag().equals(Tag.NULL) && !value.matches("(?:~|null|Null|NULL|)"))
        throw new IOException("CREDENTIAL_PROJECTION_TAG");
    } else throw new IOException("CREDENTIAL_PROJECTION_TAG");
  }

  /** Only the selected incoming credential file is projected, after authenticated staging. */
  public static void restoreCandidate(
      BackupFileSystem fs, File candidateHome, boolean credentialsSelected, BackupControl control)
      throws IOException {
    if (!credentialsSelected) return;
    control.check();
    File file = fs.child(candidateHome, FILE);
    var before = fs.stat(file);
    if (before.type.equals("MISSING")) return;
    if (!before.type.equals("FILE")) throw new IOException("CREDENTIAL_PROJECTION_SOURCE_TYPE");
    byte[] portable = portable(fs.small(file, LIMIT));
    control.check();
    if (!before.same(fs.stat(file))) throw new IOException("CREDENTIAL_PROJECTION_SOURCE_CHANGED");
    fs.atomic(candidateHome, FILE, portable);
    fs.mode(file, before.mode);
  }
}
