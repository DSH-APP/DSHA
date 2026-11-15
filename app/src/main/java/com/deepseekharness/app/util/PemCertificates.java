package com.deepseekharness.app.util;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.cert.CertificateException;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.List;

/** Read the existing Mozilla PEM bundle identically on Android and desktop certificate providers. */
public final class PemCertificates {
  private static final int MAX_BYTES = 1024 * 1024, MAX_BLOCK = 16384, MAX_CERTIFICATES = 512;
  private static final String BEGIN = "-----BEGIN CERTIFICATE-----";
  private static final String END = "-----END CERTIFICATE-----";

  private PemCertificates() {}

  /** The caller owns the stream. Empty, truncated and malformed bundles fail closed. */
  public static List<X509Certificate> read(InputStream input)
      throws IOException, CertificateException {
    ByteArrayOutputStream bytes = new ByteArrayOutputStream();
    byte[] buffer = new byte[8192];
    int count;
    while ((count = input.read(buffer)) != -1) {
      if (count == 0) {
        int value = input.read();
        if (value == -1) break;
        if (bytes.size() == MAX_BYTES) throw new CertificateException("CA_BUNDLE_TOO_LARGE");
        bytes.write(value);
      } else {
        if (count > MAX_BYTES - bytes.size()) throw new CertificateException("CA_BUNDLE_TOO_LARGE");
        bytes.write(buffer, 0, count);
      }
    }
    byte[] raw = bytes.toByteArray();
    for (byte value : raw)
      if (value < 0 || value < 32 && value != '\r' && value != '\n' && value != '\t')
        throw new CertificateException("CA_BUNDLE_INVALID_TEXT");
    CertificateFactory factory = CertificateFactory.getInstance("X.509");
    ArrayList<X509Certificate> certificates = new ArrayList<>();
    StringBuilder block = null;
    for (String rawLine : new String(raw, StandardCharsets.US_ASCII).split("\r?\n", -1)) {
      String line = rawLine.trim();
      if (block == null) {
        if (line.isEmpty() || line.startsWith("#")) continue;
        if (!BEGIN.equals(line)) throw new CertificateException("CA_BUNDLE_INVALID_PEM");
        block = new StringBuilder(BEGIN).append('\n');
      } else if (END.equals(line)) {
        block.append(END).append('\n');
        if (block.length() > MAX_BLOCK || certificates.size() == MAX_CERTIFICATES)
          throw new CertificateException("CA_BUNDLE_LIMIT");
        // Conscrypt generateCertificates() may silently return no certificates when a PEM file
        // starts with Mozilla comments. Give generateCertificate() exactly one complete block.
        certificates.add(
            (X509Certificate)
                factory.generateCertificate(
                    new ByteArrayInputStream(
                        block.toString().getBytes(StandardCharsets.US_ASCII))));
        block = null;
      } else {
        if (line.isEmpty() || !line.matches("[A-Za-z0-9+/=]+"))
          throw new CertificateException("CA_BUNDLE_INVALID_PEM");
        if (line.length() + block.length() + 1 > MAX_BLOCK)
          throw new CertificateException("CA_BUNDLE_LIMIT");
        block.append(line).append('\n');
      }
    }
    if (block != null || certificates.isEmpty())
      throw new CertificateException("CA_BUNDLE_EMPTY_OR_TRUNCATED");
    return certificates;
  }
}
