package dsha.tools;

import com.sun.tools.javac.parser.Scanner;
import com.sun.tools.javac.parser.ScannerFactory;
import com.sun.tools.javac.parser.Tokens.Token;
import com.sun.tools.javac.parser.Tokens.TokenKind;
import com.sun.tools.javac.util.Context;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;

/** Uses the JDK 17 lexer to compare exact non-comment tokens, including literal spelling. */
public final class JavaTokenFingerprint {
  private JavaTokenFingerprint() {}

  private static String fingerprint(String source) throws Exception {
    Scanner scanner = ScannerFactory.instance(new Context()).newScanner(source, false);
    MessageDigest digest = MessageDigest.getInstance("SHA-256");
    scanner.nextToken();
    for (Token token = scanner.token(); token.kind != TokenKind.EOF; token = scanner.token()) {
      if (token.kind == TokenKind.ERROR) {
        throw new IllegalArgumentException("JAVA_TOKEN_ERROR");
      }
      byte[] kind = token.kind.name().getBytes(StandardCharsets.UTF_8);
      byte[] spelling = source.substring(token.pos, token.endPos).getBytes(StandardCharsets.UTF_8);
      digest.update(ByteBuffer.allocate(4).putInt(kind.length).array());
      digest.update(kind);
      digest.update(ByteBuffer.allocate(4).putInt(spelling.length).array());
      digest.update(spelling);
      scanner.nextToken();
    }
    return HexFormat.of().formatHex(digest.digest());
  }

  private static String read(String path) throws Exception {
    return new String(Files.readAllBytes(Path.of(path)), StandardCharsets.UTF_8);
  }

  /**
   * Batch mode: each stdin line is "beforePath\tafterPath"; prints "beforeSha afterSha" per line,
   * or "ERROR" when either side cannot be lexed. One JVM serves a whole formatter batch.
   */
  private static void pairs() throws Exception {
    String input = new String(System.in.readAllBytes(), StandardCharsets.UTF_8);
    StringBuilder output = new StringBuilder();
    for (String line : input.split("\n", -1)) {
      if (line.isEmpty()) {
        continue;
      }
      int tab = line.indexOf('\t');
      if (tab < 0 || line.indexOf('\t', tab + 1) >= 0) {
        throw new IllegalArgumentException("JAVA_TOKEN_PAIR_LINE");
      }
      try {
        String before = fingerprint(read(line.substring(0, tab)));
        String after = fingerprint(read(line.substring(tab + 1)));
        output.append(before).append(' ').append(after).append('\n');
      } catch (RuntimeException error) {
        output.append("ERROR\n");
      }
    }
    System.out.print(output);
  }

  public static void main(String[] args) throws Exception {
    if (args.length == 1 && args[0].equals("--pairs")) {
      pairs();
      return;
    }
    if (args.length != 0) {
      throw new IllegalArgumentException("JAVA_TOKEN_USAGE");
    }
    String input = new String(System.in.readAllBytes(), StandardCharsets.UTF_8);
    int separator = input.indexOf('\0');
    if (separator < 0 || input.indexOf('\0', separator + 1) >= 0) {
      throw new IllegalArgumentException("JAVA_TOKEN_TWO_SOURCES_REQUIRED");
    }
    String before = fingerprint(input.substring(0, separator));
    String after = fingerprint(input.substring(separator + 1));
    System.out.println(before);
    System.out.println(after);
    if (!before.equals(after)) {
      System.exit(1);
    }
  }
}
