package com.deepseekharness.app.vscreen;

import static org.junit.Assert.*;

import com.deepseekharness.app.util.VirtualScreenCoreIdentity;
import com.deepseekharness.app.util.VirtualScreenInputResult;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

/** Core 侧白名单解析与创建参数：生产路由/鉴权/解析不变，只替换显示适配器。 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34, manifest = Config.NONE, application = android.app.Application.class)
public final class VirtualScreenCoreDisplayPresetTest {
  @Test
  public void presetCreateAppliesWhitelistSizeAndUnknownFallsBackToDefault() throws Exception {
    var birth =
        VirtualScreenCoreIdentity.parse(
            "123:456:2000:11111111-2222-3333-4444-555555555555:aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee");
    AtomicLong clock = new AtomicLong(100);
    AtomicInteger exits = new AtomicInteger(), displays = new AtomicInteger(50);
    List<int[]> created = new ArrayList<>();
    var control =
        VirtualScreenCore.bindControlForHost(
            birth,
            (width, height) -> {
              created.add(new int[] {width, height});
              return new VirtualScreenDisplaySession(
                  width,
                  height,
                  displays.incrementAndGet(),
                  VirtualScreenInput.commandForTest(
                      displays.get(), (argv, timeout) -> VirtualScreenInputResult.REJECTED));
            },
            () -> "com.example.app",
            clock::get,
            exits::incrementAndGet);
    try {
      var transport = new VirtualScreenTransport();
      var endpoint = control.handshake();

      JSONObject compat =
          transport.request(endpoint, "/vscreen/create", "preset=compat-720&orientation=portrait");
      assertTrue(compat.toString(), compat.optBoolean("ok"));
      assertEquals(720, compat.getInt("width"));
      assertEquals(1600, compat.getInt("height"));
      assertEquals(320, compat.getInt("density"));
      assertEquals("compat-720", compat.getString("preset"));
      assertEquals(360, compat.getInt("logicalWidth"));
      assertEquals(800, compat.getInt("logicalHeight"));
      assertArrayEquals(new int[] {720, 1600}, created.get(created.size() - 1));

      // 同规格重复 create 复用当前代次，不重建显示。
      JSONObject same =
          transport.request(endpoint, "/vscreen/create", "preset=compat-720&orientation=portrait");
      assertEquals(compat.getString("generation"), same.getString("generation"));

      // 旋转 = 按同一预设重建为横屏（尺寸交换，dpi 与预设 id 不变）。
      JSONObject landscape =
          transport.request(endpoint, "/vscreen/create", "preset=compat-720&orientation=landscape");
      assertEquals(1600, landscape.getInt("width"));
      assertEquals(720, landscape.getInt("height"));
      assertEquals("landscape", landscape.getString("orientation"));
      assertEquals("compat-720", landscape.getString("preset"));
      assertEquals(320, landscape.getInt("density"));

      // 未知 id 回退默认白名单，而不是把任意预设名当自定义尺寸。
      JSONObject unknown =
          transport.request(endpoint, "/vscreen/create", "preset=qhd-1440&orientation=portrait");
      assertEquals(1008, unknown.getInt("width"));
      assertEquals(1792, unknown.getInt("height"));
      assertEquals("default", unknown.getString("preset"));
      assertEquals(504, unknown.getInt("logicalWidth"));
      assertEquals(896, unknown.getInt("logicalHeight"));

      // 旧调用（不带 preset）保持 phoneSize() 归一化与 320 dpi，默认行为不变。
      JSONObject legacy = transport.request(endpoint, "/vscreen/create", "width=720&height=1520");
      assertEquals(720, legacy.getInt("width"));
      assertEquals(1280, legacy.getInt("height"));
      assertEquals(320, legacy.getInt("density"));
      assertEquals("custom", legacy.getString("preset"));
    } finally {
      control.close();
    }
  }
}
