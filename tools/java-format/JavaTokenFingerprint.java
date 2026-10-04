package dsha.tools;

import com.sun.tools.javac.parser.Scanner;
import com.sun.tools.javac.parser.ScannerFactory;
import com.sun.tools.javac.parser.Tokens.Token;
import com.sun.tools.javac.parser.Tokens.TokenKind;
import com.sun.tools.javac.util.Context;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
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

  public static void main(String[] args) throws Exception {
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
