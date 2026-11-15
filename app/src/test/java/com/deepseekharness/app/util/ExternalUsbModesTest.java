package com.deepseekharness.app.util;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.deepseekharness.app.util.ExternalUsbModes.Iface;
import com.deepseekharness.app.util.ExternalUsbModes.Mode;
import java.util.List;
import org.junit.Test;

/** USB 模式识别：接口三元组优先，私有下载模式只认出不放行。 */
public class ExternalUsbModesTest {

  private static final Iface MTP = new Iface(0x06, 0x01, 0x01);
  private static final Iface ADB = new Iface(0xFF, 0x42, 0x01);
  private static final Iface FASTBOOT = new Iface(0xFF, 0x42, 0x03);

  @Test
  public void compositeMtpPlusAdbIsAdb() {
    ExternalUsbModes.Result r = ExternalUsbModes.classify(0x22D9, 0x2769, List.of(MTP, ADB));
    assertEquals(Mode.ADB, r.mode());
    assertEquals("OPPO/OnePlus/realme", r.vendor());
    assertTrue(r.mode().toolchain);
  }

  @Test
  public void fastbootIsVendorIndependent() {
    // 一加/OPPO 机型 fastboot 下常以 Google VID 18D1:D00D 枚举
    assertEquals(
        Mode.FASTBOOT, ExternalUsbModes.classify(0x18D1, 0xD00D, List.of(FASTBOOT)).mode());
    assertEquals(
        Mode.FASTBOOT, ExternalUsbModes.classify(0x1234, 0x5678, List.of(FASTBOOT)).mode());
  }

  @Test
  public void vendorDownloadModesAreRecognizedButNotToolchain() {
    assertEquals(Mode.QUALCOMM_EDL, ExternalUsbModes.classify(0x05C6, 0x9008, List.of()).mode());
    assertEquals(Mode.MTK_BROM, ExternalUsbModes.classify(0x0E8D, 0x0003, null).mode());
    assertEquals(Mode.MTK_PRELOADER, ExternalUsbModes.classify(0x0E8D, 0x2000, null).mode());
    assertEquals(Mode.UNISOC_DOWNLOAD, ExternalUsbModes.classify(0x1782, 0x4D00, null).mode());
    assertEquals(Mode.SAMSUNG_ODIN, ExternalUsbModes.classify(0x04E8, 0x685D, null).mode());
    for (Mode m :
        new Mode[] {Mode.QUALCOMM_EDL, Mode.MTK_BROM, Mode.MTK_PRELOADER, Mode.SAMSUNG_ODIN})
      assertFalse(m.toolchain);
  }

  @Test
  public void mtpOnlyAsksForUsbDebugging() {
    ExternalUsbModes.Result r = ExternalUsbModes.classify(0x2717, 0xFF48, List.of(MTP));
    assertEquals(Mode.MTP_OR_OTHER, r.mode());
    assertEquals("Xiaomi", r.vendor());
  }

  @Test
  public void unknownVendorShowsHex() {
    assertEquals("VID 0ABC", ExternalUsbModes.vendorName(0x0ABC));
  }
}
