package com.deepseekharness.app.runtime;

import static org.junit.Assert.*;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.Test;

/** Cache only signed APK input; disk-overlay mutation remains covered by Android launcher tests. */
public class RuntimeApkAssetsTest {
  @Test
  public void cacheRequiresForeignOwnerAndNoGroupOrOtherWritePermission() {
    assertTrue(RuntimeApkAssets.immutableToApp(1000, 0100644, 10234));
    assertTrue(RuntimeApkAssets.immutableToApp(1000, 0040755, 10234));
    assertFalse(RuntimeApkAssets.immutableToApp(10234, 0100444, 10234));
    assertFalse(RuntimeApkAssets.immutableToApp(1000, 0100664, 10234));
    assertFalse(RuntimeApkAssets.immutableToApp(1000, 0040777, 10234));
  }

  private static final class Source implements RuntimeApkAssets.Source {
    Object owner = new Object();
    String identity = "apk:inode:42:size:100:mtime:1:mode:0644";
    int opens;
    String replaceOnOpen;
    final Map<String, byte[]> files = new LinkedHashMap<>();

    Source() {
      for (String name :
          new String[] {
            "bridge-token-compat.cjs",
            "native-consent.cjs",
            "bridge-port.cjs",
            "managed-package-proofs.json"
          }) files.put(name, (name + " original").getBytes(StandardCharsets.UTF_8));
    }

    public Object owner() {
      return owner;
    }

    public String identity() {
      return identity;
    }

    public InputStream open(String name) throws IOException {
      opens++;
      if (replaceOnOpen != null) {
        identity = replaceOnOpen;
        replaceOnOpen = null;
      }
      byte[] bytes = files.get(name);
      if (bytes == null) throw new IOException("FIXTURE_MISSING");
      return new ByteArrayInputStream(bytes);
    }
  }

  @Test
  public void immutableIdentityAvoidsRepeatedAssetReadsAndHashes() throws Exception {
    var cache = new RuntimeApkAssets.Cache();
    var source = new Source();
    var first = cache.get(source);
    for (int i = 0; i < 20; i++) assertSame(first, cache.get(source));
    assertEquals(4, source.opens);
    assertEquals(3, first.preloads.size());
    assertArrayEquals(source.files.get("bridge-port.cjs"), first.preloads.get("bridge-port.cjs"));
    String expected =
        java.util.HexFormat.of()
            .formatHex(
                java.security.MessageDigest.getInstance("SHA-256")
                    .digest(source.files.get("managed-package-proofs.json")));
    assertEquals(expected, first.packageProofSha256);
  }

  @Test
  public void changedApkIdentityReloadsContentAndProof() throws Exception {
    var cache = new RuntimeApkAssets.Cache();
    var source = new Source();
    var first = cache.get(source);
    source.identity += ":replacement";
    source.files.put("native-consent.cjs", "new consent".getBytes(StandardCharsets.UTF_8));
    source.files.put("managed-package-proofs.json", "new proof".getBytes(StandardCharsets.UTF_8));
    var next = cache.get(source);
    assertNotEquals(first.preloadId, next.preloadId);
    assertNotEquals(first.packageProofSha256, next.packageProofSha256);
    assertEquals(8, source.opens);
  }

  @Test
  public void anotherAssetManagerCannotReuseIdenticalPathMetadata() throws Exception {
    var cache = new RuntimeApkAssets.Cache();
    var source = new Source();
    var first = cache.get(source);
    source.owner = new Object();
    assertNotSame(first, cache.get(source));
    assertEquals(8, source.opens);
  }

  @Test
  public void writableOrUnverifiableInputsStayUncached() throws Exception {
    var cache = new RuntimeApkAssets.Cache();
    var source = new Source();
    source.identity = null;
    var first = cache.get(source);
    source.files.put("native-consent.cjs", "mutable".getBytes(StandardCharsets.UTF_8));
    var next = cache.get(source);
    assertNotEquals(first.preloadId, next.preloadId);
    assertEquals(8, source.opens);
  }

  @Test
  public void replacementDuringReadCannotPublishMixedSnapshot() throws Exception {
    var cache = new RuntimeApkAssets.Cache();
    var source = new Source();
    source.replaceOnOpen = "replacement";
    var error = assertThrows(IOException.class, () -> cache.get(source));
    assertEquals("LAUNCHER_APK_ASSETS_CHANGED", error.getMessage());
    var first = cache.get(source);
    assertSame(first, cache.get(source));
    assertEquals(8, source.opens);
  }

  @Test
  public void failedReplacementDoesNotResurrectPreviousSnapshot() throws Exception {
    var cache = new RuntimeApkAssets.Cache();
    var source = new Source();
    var first = cache.get(source);
    source.identity = "new";
    byte[] old = source.files.remove("managed-package-proofs.json");
    assertThrows(IOException.class, () -> cache.get(source));
    source.identity = "apk:inode:42:size:100:mtime:1:mode:0644";
    source.files.put("managed-package-proofs.json", old);
    assertNotSame(first, cache.get(source));
    assertEquals(12, source.opens);
  }

  @Test
  public void oversizedInputIsRejectedWithoutCreatingCache() throws Exception {
    var cache = new RuntimeApkAssets.Cache();
    var source = new Source();
    source.files.put("bridge-token-compat.cjs", new byte[512 * 1024 + 1]);
    assertEquals(
        "LAUNCHER_ASSET_LIMIT",
        assertThrows(IOException.class, () -> cache.get(source)).getMessage());
    source.files.put("bridge-token-compat.cjs", new byte[1]);
    source.files.put("managed-package-proofs.json", new byte[2 * 1024 * 1024 + 1]);
    assertEquals(
        "PACKAGE_PROOFS_LIMIT",
        assertThrows(IOException.class, () -> cache.get(source)).getMessage());
  }
}
