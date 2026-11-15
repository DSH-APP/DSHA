package com.deepseekharness.app.util;

import static org.junit.Assert.*;

import java.util.List;
import org.junit.Test;

public class RuntimePreparedProofTest {
  private static final String APK = "/data/app/~~x/base.apk:123456:1700000000000";
  private static final String STAMP = "2049:1234|2049:5678:120:1700000000:12:1700000000:12:33188";
  private static final List<String> FILES =
      List.of("usr/local/share/dsha/managed-assets-v2", "root/.dsh/plugin-manager.py");

  @Test
  public void roundTripsExactApkStampAndManagedFiles() {
    String text = RuntimePreparedProof.format(APK, STAMP, FILES);
    RuntimePreparedProof.Proof proof = RuntimePreparedProof.parse(text);
    assertNotNull(proof);
    assertEquals(APK, proof.apk);
    assertEquals(STAMP, proof.stamp);
    assertEquals(FILES, proof.files);
  }

  @Test
  public void rejectsMalformedOrUnsafeProof() {
    String valid = RuntimePreparedProof.format(APK, STAMP, FILES);
    assertNull(RuntimePreparedProof.parse(null));
    assertNull(RuntimePreparedProof.parse(""));
    assertNull(RuntimePreparedProof.parse(valid.replace("DSHA_RUNTIME_PREPARED_V1", "OTHER")));
    assertNull(RuntimePreparedProof.parse(valid.replace("files 2", "files 3")));
    assertNull(RuntimePreparedProof.parse(valid.replace("files 2", "files 1")));
    assertNull(
        RuntimePreparedProof.parse(valid.replace("root/.dsh/plugin-manager.py", "../escape.py")));
    assertNull(RuntimePreparedProof.parse(valid.replaceFirst("(?m)^stamp [0-9]+ ", "stamp 59 ")));
    assertNull(RuntimePreparedProof.parse(valid + "trailing\n"));
    assertNull(RuntimePreparedProof.parse("x".repeat(RuntimePreparedProof.MAX_BYTES + 1)));
  }

  @Test
  public void formatRejectsUnsafeInputs() {
    assertThrows(
        IllegalArgumentException.class, () -> RuntimePreparedProof.format("", STAMP, FILES));
    assertThrows(
        IllegalArgumentException.class, () -> RuntimePreparedProof.format(APK, STAMP, List.of()));
    assertThrows(
        IllegalArgumentException.class,
        () -> RuntimePreparedProof.format(APK, STAMP, List.of("root/.dsh/a", "root/.dsh/a")));
    assertThrows(
        IllegalArgumentException.class,
        () -> RuntimePreparedProof.format(APK, STAMP, List.of("/absolute/path")));
    assertThrows(
        IllegalArgumentException.class,
        () -> RuntimePreparedProof.format(APK, STAMP, List.of("a\nb")));
    assertThrows(
        IllegalArgumentException.class,
        () -> RuntimePreparedProof.format(APK, STAMP, List.of("x".repeat(241))));
  }

  @Test
  public void relativePathGrammarStaysInsideTheRuntimeRoot() {
    assertTrue(RuntimePreparedProof.validRelative("root/.dsh/plugin-manager.py"));
    assertTrue(RuntimePreparedProof.validRelative("usr/local/share/dsha/managed-assets-v2"));
    assertFalse(RuntimePreparedProof.validRelative(""));
    assertFalse(RuntimePreparedProof.validRelative("/root/.dsh/x"));
    assertFalse(RuntimePreparedProof.validRelative("root//x"));
    assertFalse(RuntimePreparedProof.validRelative("root/./x"));
    assertFalse(RuntimePreparedProof.validRelative("root/../x"));
    assertFalse(RuntimePreparedProof.validRelative("root\\x"));
    assertFalse(RuntimePreparedProof.validRelative("root/中文.js"));
    assertFalse(RuntimePreparedProof.validRelative("root/x y.js"));
  }
}
