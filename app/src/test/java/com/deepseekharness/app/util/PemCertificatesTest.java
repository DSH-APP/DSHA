package com.deepseekharness.app.util;

import static org.junit.Assert.*;
import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.security.cert.CertificateException;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.junit.Test;

public final class PemCertificatesTest {
  private byte[] bundle() throws Exception {
    File defaults = new File("src/main/assets");
    if (!defaults.isDirectory()) defaults = new File("app/src/main/assets");
    return Files.readAllBytes(
        new File(System.getProperty("dsha.assetsDir", defaults.getPath()), "ca-certificates.crt")
            .toPath());
  }

  private String firstBlock() throws Exception {
    String text = new String(bundle(), StandardCharsets.US_ASCII);
    return text.substring(
        text.indexOf("-----BEGIN CERTIFICATE-----"),
        text.indexOf("-----END CERTIFICATE-----") + "-----END CERTIFICATE-----".length());
  }

  private List<X509Certificate> parse(String text) throws Exception {
    return PemCertificates.read(new ByteArrayInputStream(text.getBytes(StandardCharsets.US_ASCII)));
  }

  @Test
  public void realBundleKeepsEveryOriginalRootAndNeverUsesAnEmptyFallback() throws Exception {
    byte[] pem = bundle();
    List<X509Certificate> parsed = PemCertificates.read(new ByteArrayInputStream(pem));
    assertEquals(121, parsed.size());
    Set<String> before = new HashSet<>(), after = new HashSet<>();
    for (java.security.cert.Certificate certificate :
        // Robolectric may install Android Conscrypt globally, whose bulk parser is the defect
        // being fixed. Use the JDK's independent bulk parser as the original-root reference.
        CertificateFactory.getInstance("X.509", "SUN")
            .generateCertificates(new ByteArrayInputStream(pem)))
      before.add(
          java.util.Arrays.toString(
              MessageDigest.getInstance("SHA-256").digest(certificate.getEncoded())));
    for (X509Certificate certificate : parsed)
      after.add(
          java.util.Arrays.toString(
              MessageDigest.getInstance("SHA-256").digest(certificate.getEncoded())));
    assertFalse(before.isEmpty());
    assertEquals(before, after);
  }

  @Test
  public void mozillaCommentsAndWindowsNewlinesDoNotHideCertificates() throws Exception {
    String one = firstBlock();
    assertEquals(
        2,
        parse(
                "# Mozilla root description\r\n\r\n"
                    + one.replace("\n", "\r\n")
                    + "\r\n# Second root\r\n"
                    + one
                    + "\n")
            .size());
  }

  @Test
  public void emptyCommentOnlyAndTruncatedBundlesFailClosed() throws Exception {
    for (String text :
        new String[] {
          "",
          "\n# comment only\n",
          "-----BEGIN CERTIFICATE-----\nMIIB\n",
          firstBlock() + "\n-----BEGIN CERTIFICATE-----\nMIIB\n"
        }) assertThrows(CertificateException.class, () -> parse(text));
  }

  @Test
  public void malformedBlocksCannotSilentlyDiscardBadOrUnrecognizedContent() throws Exception {
    for (String text :
        new String[] {
          "garbage\n" + firstBlock(),
          "-----BEGIN CERTIFICATE-----\nnot*a*certificate\n-----END CERTIFICATE-----",
          "-----BEGIN CERTIFICATE-----\nQUJD\n-----END CERTIFICATE-----",
          firstBlock() + "\n-----END CERTIFICATE-----",
          firstBlock() + "\nsecret=unrecognized"
        }) assertThrows(CertificateException.class, () -> parse(text));
  }

  @Test
  public void totalBlockAndCertificateCountHaveIndependentBounds() throws Exception {
    assertThrows(CertificateException.class, () -> parse("#" + "x".repeat(1024 * 1024)));
    assertThrows(
        CertificateException.class,
        () ->
            parse(
                "-----BEGIN CERTIFICATE-----\n"
                    + "A".repeat(16384)
                    + "\n-----END CERTIFICATE-----"));
    assertThrows(CertificateException.class, () -> parse((firstBlock() + "\n").repeat(513)));
  }

  @Test
  public void callerRetainsStreamOwnershipAndBinaryInputIsRejected() throws Exception {
    boolean[] closed = {false};
    InputStream input =
        new ByteArrayInputStream(firstBlock().getBytes(StandardCharsets.US_ASCII)) {
          @Override
          public void close() {
            closed[0] = true;
          }
        };
    assertEquals(1, PemCertificates.read(input).size());
    assertFalse(closed[0]);
    assertThrows(
        CertificateException.class,
        () -> PemCertificates.read(new ByteArrayInputStream(new byte[] {0, (byte) 255})));
  }
}
