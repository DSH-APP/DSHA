package com.deepseekharness.app.util;

import static org.junit.Assert.*;
import java.io.IOException;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import java.security.cert.CertPathValidatorException;
import javax.net.ssl.SSLHandshakeException;
import javax.net.ssl.SSLException;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

public class UpdateFailuresTest {
  @Before
  public void chinese() {
    UiText.setLanguage("zh");
  }

  @After
  public void resetLanguage() {
    UiText.setLanguage("zh");
  }

  @Test
  public void nestedTrustAnchorFailureIsRecoverableAndNeverClaimsNoUpdate() {
    SSLHandshakeException handshake = new SSLHandshakeException("java secret debug detail");
    handshake.initCause(
        new CertPathValidatorException("Trust anchor for certification path not found"));
    IOException wrapper = new IOException("token=synthetic-secret", handshake);
    assertEquals(UpdateFailures.Kind.CERTIFICATE, UpdateFailures.kind(wrapper));
    String message = UpdateFailures.message(wrapper);
    assertTrue(message.contains("证书"));
    assertTrue(message.contains("日期时间"));
    assertTrue(message.contains("切换网络"));
    assertFalse(message.contains("暂无"));
    assertFalse(message.contains("Trust anchor"));
    assertFalse(message.contains("synthetic-secret"));
    assertFalse(message.contains("Exception"));
  }

  @Test
  public void dnsTimeoutAndServerFailureRemainDifferentFromEmptyResults() {
    assertEquals(UpdateFailures.Kind.TIMEOUT, UpdateFailures.kind(new SocketTimeoutException()));
    assertEquals(UpdateFailures.Kind.OFFLINE, UpdateFailures.kind(new UnknownHostException()));
    assertEquals(
        UpdateFailures.Kind.HTTP, UpdateFailures.kind(new UpdateFailures.HttpFailure(503)));
    for (Throwable failure :
        new Throwable[] {
          new SocketTimeoutException(),
          new UnknownHostException(),
          new UpdateFailures.HttpFailure(503),
          new UpdateFailures.InvalidManifest()
        }) assertFalse(UpdateFailures.message(failure).contains("暂无"));
  }

  @Test
  public void malformedMetadataGetsItsOwnFixedMessage() {
    assertEquals(
        UpdateFailures.Kind.MANIFEST,
        UpdateFailures.kind(new UpdateFailures.InvalidManifest(new IOException("untrusted body"))));
    assertTrue(UpdateFailures.message(new UpdateFailures.InvalidManifest()).contains("更新信息"));
  }

  @Test
  public void unknownFailuresCannotInjectRemoteTextOrJavaClassNames() {
    String message =
        UpdateFailures.message(
            new IOException("https://user:password@evil/?token=secret\njava.Exception"));
    assertFalse(message.contains("password"));
    assertFalse(message.contains("Exception"));
    assertTrue(message.contains("重试"));
  }

  @Test
  public void transportResetDoesNotBlameTheCertificateAndLocalVerificationStaysUseful() {
    assertEquals(
        UpdateFailures.Kind.TLS, UpdateFailures.kind(new SSLException("connection reset")));
    assertFalse(UpdateFailures.message(new SSLException("connection reset")).contains("证书"));
    String safe = "SHA-256 校验失败，已清理损坏文件，请重新下载";
    assertEquals(safe, UpdateFailures.message(new IOException(safe)));
  }

  @Test
  public void cyclicCausesAreBounded() {
    IOException a = new IOException("a"), b = new IOException("b");
    a.initCause(b);
    b.initCause(a);
    assertEquals(UpdateFailures.Kind.OTHER, UpdateFailures.kind(a));
  }

  @Test
  public void englishMessageAlsoContainsActionWithoutRawException() {
    UiText.setLanguage("en");
    String message = UpdateFailures.message(new CertPathValidatorException("Trust anchor"));
    assertTrue(message.contains("certificate"));
    assertTrue(message.contains("date and time"));
    assertFalse(message.contains("Trust anchor"));
  }
}
