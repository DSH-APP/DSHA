package com.deepseekharness.app.bridge;

import static org.junit.Assert.*;

import android.app.Application;
import java.util.Arrays;
import java.util.Base64;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.GraphicsMode;

@RunWith(BridgeImageDecoderTest.NoApkRunner.class)
@Config(sdk = 34, manifest = Config.NONE, application = Application.class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
public class BridgeImageDecoderTest {
  public static final class NoApkRunner extends RobolectricTestRunner {
    public NoApkRunner(Class<?> type) throws org.junit.runners.model.InitializationError {
      super(type);
    }

    @Override
    protected java.util.Properties getBuildSystemApiProperties() {
      return new java.util.Properties();
    }
  }

  @Test
  public void acceptsDecodablePngAndRejectsForgedOrTruncatedContent() {
    byte[] png =
        Base64.getDecoder()
            .decode(
                "iVBORw0KGgoAAAANSUhEUgAAAAIAAAACCAIAAAD91JpzAAAAEklEQVR4nGNUqTjCwMDAxAAGABAKAWR5s70uAAAAAElFTkSuQmCC");
    assertTrue(BridgeImageDecoder.valid(png, "image/png"));
    assertFalse(BridgeImageDecoder.valid(png, "image/jpeg"));
    assertFalse(BridgeImageDecoder.valid(Arrays.copyOf(png, 8), "image/png"));
    assertFalse(BridgeImageDecoder.valid("not a picture".getBytes(), "image/png"));
  }
}
