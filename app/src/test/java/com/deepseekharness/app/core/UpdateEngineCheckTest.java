package com.deepseekharness.app.core;

import static org.junit.Assert.*;

import android.app.Application;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.Signature;
import android.content.pm.SigningInfo;
import android.os.Looper;
import com.deepseekharness.app.BuildConfig;
import com.deepseekharness.app.util.UiText;
import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.cert.CertPathValidatorException;
import javax.net.ssl.SSLHandshakeException;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.LooperMode;
import org.robolectric.shadow.api.Shadow;
import org.robolectric.shadows.ShadowSigningInfo;

/** Real engine, signing identity, state and retry; only remote HTTP responses are synthetic. */
@RunWith(UpdateEngineCheckTest.NoApkRunner.class)
@Config(sdk = 34, manifest = Config.NONE, application = Application.class)
@LooperMode(LooperMode.Mode.PAUSED)
public final class UpdateEngineCheckTest {
  public static final class NoApkRunner extends RobolectricTestRunner {
    public NoApkRunner(Class<?> type) throws org.junit.runners.model.InitializationError {
      super(type);
    }

    @Override
    protected java.util.Properties getBuildSystemApiProperties() {
      return new java.util.Properties();
    }
  }

  private Application app;
  private String signer;

  @Before
  public void prepareIdentity() throws Exception {
    app = RuntimeEnvironment.getApplication();
    app.getSharedPreferences("dsha-updates", 0)
        .edit()
        .clear()
        .putString("channel", "preview")
        .commit();
    Signature signature = new Signature("01020304");
    PackageInfo installed = app.getPackageManager().getPackageInfo(app.getPackageName(), 0);
    installed.signingInfo = Shadow.newInstanceOf(SigningInfo.class);
    Shadow.<ShadowSigningInfo>extract(installed.signingInfo)
        .setSignatures(new Signature[] {signature});
    Shadow.<org.robolectric.shadows.ShadowPackageManager>extract(app.getPackageManager())
        .installPackage(installed);
    StringBuilder hex = new StringBuilder();
    for (byte value : MessageDigest.getInstance("SHA-256").digest(signature.toByteArray()))
      hex.append(String.format(java.util.Locale.ROOT, "%02x", value & 255));
    signer = hex.toString();
    UiText.setLanguage("zh");
  }

  @After
  public void resetLanguage() {
    UiText.setLanguage("zh");
  }

  private String feed(String releases) {
    return "{\"schemaVersion\":1,\"packageName\":\""
        + app.getPackageName()
        + "\",\"certificateSha256\":\""
        + signer
        + "\",\"releases\":"
        + releases
        + "}";
  }

  private String release() {
    return "{\"versionCode\":"
        + (BuildConfig.VERSION_CODE + 1)
        + ",\"version\":\"0.2.2-preview\",\"channel\":\"preview\",\"artifacts\":[{"
        + "\"flavor\":\"standard\",\"minSdk\":30,\"abi\":\"arm64-v8a\","
        + "\"url\":\"https://dsha.cc/downloads/next.apk\",\"sha256\":\""
        + "a".repeat(64)
        + "\",\"bytes\":100}]}";
  }

  private UpdateRepository.State check(UpdateEngine engine) throws Exception {
    engine.check();
    assertEquals(UpdateRepository.Stage.CHECKING, engine.state().getValue().stage);
    long deadline = System.nanoTime() + 5_000_000_000L;
    while (engine.state().getValue().busy && System.nanoTime() < deadline) {
      Shadow.<org.robolectric.shadows.ShadowLooper>extract(Looper.getMainLooper()).idle();
      Thread.sleep(5);
    }
    UpdateRepository.State done = engine.state().getValue();
    assertFalse("update check must finish", done.busy);
    assertEquals(UpdateRepository.Stage.IDLE, done.stage);
    return done;
  }

  @Test
  public void absentFeedAndNoPublishedReleaseHaveNormalEmptyStates() throws Exception {
    for (int status : new int[] {404, 410, 200}) {
      Response source = new Response(status, feed("[]"));
      UpdateRepository.State state = check(new UpdateEngine(app, source));
      assertEquals("暂无可用更新", state.message);
      assertNull(state.release);
      assertTrue(source.disconnected);
      assertEquals(status == 200, source.bodyOpened);
    }
  }

  @Test
  public void legacyApplicationAndMissingAssetNeverBecomeInstallCandidates() throws Exception {
    for (String body :
        new String[] {
          feed("[" + release() + "]").replace(app.getPackageName(), "com.dsh.client"),
          feed("[{\"versionCode\":9999,\"artifacts\":[]}]"),
          feed("[" + release().replace("arm64-v8a", "x86_64") + "]")
        }) {
      UpdateRepository.State state = check(new UpdateEngine(app, new Response(200, body)));
      assertEquals("暂无可用更新", state.message);
      assertNull(state.release);
    }
  }

  @Test
  public void certificateFailureIsUnknownThenARetryCanConfirmNoUpdate() throws Exception {
    Response source = new Response(200, feed("[]"));
    source.failure = new SSLHandshakeException("CertPathValidatorException: Trust anchor");
    source.failure.initCause(
        new CertPathValidatorException("Trust anchor for certification path not found"));
    UpdateEngine engine = new UpdateEngine(app, source);
    UpdateRepository.State failed = check(engine);
    assertTrue(failed.message.startsWith("暂时无法确认更新"));
    assertTrue(failed.message.contains("证书"));
    assertFalse(failed.message.contains("暂无"));
    assertFalse(failed.message.contains("Exception"));
    source.failure = null;
    assertEquals("暂无可用更新", check(engine).message);
  }

  @Test
  public void serverFailureKeepsTheLastVerifiedChannelCandidate() throws Exception {
    Response source = new Response(200, feed("[" + release() + "]"));
    UpdateEngine engine = new UpdateEngine(app, source);
    UpdateRepository.State available = check(engine);
    assertNotNull(available.release);
    source.status = 503;
    UpdateRepository.State failed = check(engine);
    assertSame(available.release, failed.release);
    assertEquals("preview", failed.sourceChannel);
    assertTrue(failed.message.contains("暂时无法确认更新"));
    assertTrue(failed.message.contains("上次结果已保留"));
    assertFalse(failed.message.contains("暂无可用更新"));
  }

  private UpdateRepository.State await(UpdateEngine engine) throws Exception {
    long deadline = System.nanoTime() + 5_000_000_000L;
    while (engine.state().getValue().busy && System.nanoTime() < deadline) {
      Shadow.<org.robolectric.shadows.ShadowLooper>extract(Looper.getMainLooper()).idle();
      Thread.sleep(5);
    }
    UpdateRepository.State done = engine.state().getValue();
    assertFalse("update check must finish", done.busy);
    return done;
  }

  @Test
  public void switchingChannelWhileCheckingSupersedesTheStaleCheck() throws Exception {
    Gated source = new Gated(feed("[" + release() + "]"));
    UpdateEngine engine = new UpdateEngine(app, source);
    engine.check();
    assertTrue(source.entered.await(5, java.util.concurrent.TimeUnit.SECONDS));
    engine.setChannel("stable");
    UpdateRepository.State switching = engine.state().getValue();
    assertEquals("切换不能被检查卡住", "stable", engine.channel());
    assertEquals(UpdateRepository.Stage.CHECKING, switching.stage);
    assertTrue(switching.message.startsWith("正在检查稳定版更新"));
    source.gate.countDown();
    UpdateRepository.State done = await(engine);
    assertEquals("作废的检查结束后按新通道重查一次", 2, source.opened.get());
    assertEquals("stable", done.channel);
    assertNull("预览通道的旧结果不能写成候选", done.sourceChannel);
    assertNull(done.release);
    assertEquals("暂无可用更新", done.message);
  }

  @Test
  public void cancelAfterSwitchingDoesNotStartAnotherCheck() throws Exception {
    Gated source = new Gated(feed("[" + release() + "]"));
    UpdateEngine engine = new UpdateEngine(app, source);
    engine.check();
    assertTrue(source.entered.await(5, java.util.concurrent.TimeUnit.SECONDS));
    engine.setChannel("stable");
    engine.cancel();
    source.gate.countDown();
    UpdateRepository.State done = await(engine);
    assertEquals(1, source.opened.get());
    assertNull(done.release);
  }

  /** 首个请求在 open 内等待放行，模拟慢网络下的检查。 */
  private static final class Gated implements UpdateEngine.Transport {
    final java.util.concurrent.CountDownLatch entered = new java.util.concurrent.CountDownLatch(1);
    final java.util.concurrent.CountDownLatch gate = new java.util.concurrent.CountDownLatch(1);
    final java.util.concurrent.atomic.AtomicInteger opened =
        new java.util.concurrent.atomic.AtomicInteger();
    final Response response;

    Gated(String body) {
      response = new Response(200, body);
    }

    @Override
    public HttpURLConnection open(String target, long offset) throws Exception {
      if (opened.incrementAndGet() == 1) {
        entered.countDown();
        assertTrue(gate.await(5, java.util.concurrent.TimeUnit.SECONDS));
      }
      return response.open(target, offset);
    }
  }

  private static final class Response implements UpdateEngine.Transport {
    int status;
    final String body;
    Exception failure;
    boolean disconnected, bodyOpened;

    Response(int status, String body) {
      this.status = status;
      this.body = body;
    }

    @Override
    public HttpURLConnection open(String target, long offset) throws Exception {
      assertEquals(UpdateEngine.FEED, target);
      assertEquals(0, offset);
      if (failure != null) throw failure;
      return new HttpURLConnection(new URL(target)) {
        @Override
        public int getResponseCode() {
          return status;
        }

        @Override
        public InputStream getInputStream() {
          bodyOpened = true;
          return new ByteArrayInputStream(body.getBytes(StandardCharsets.UTF_8));
        }

        @Override
        public void disconnect() {
          disconnected = true;
        }

        @Override
        public boolean usingProxy() {
          return false;
        }

        @Override
        public void connect() {}
      };
    }
  }
}
