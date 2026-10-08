package com.deepseekharness.app.ui;

import org.junit.Test;
import static org.junit.Assert.*;

public class WebUploadChooserTest {
  @Test
  public void unrestrictedAttachmentsUseAllFileTypes() {
    for (String[] types : new String[][] {null, {}, {"", null}, {"  "}, {"*/*"}, {"*"}}) {
      assertFalse(WebUploadChooser.onlyImages(types));
      assertEquals("*/*", WebUploadChooser.mimeType(types));
    }
  }

  @Test
  public void pictureOnlyRequestsKeepTheirOriginalPicker() {
    assertTrue(WebUploadChooser.onlyImages(new String[] {"image/*"}));
    assertTrue(WebUploadChooser.onlyImages(new String[] {" IMAGE/JPEG ,image/png", ""}));
    assertFalse(WebUploadChooser.onlyImages(new String[] {"image/jpeg", "application/pdf"}));
  }

  @Test
  public void documentFiltersPreserveMixedTypesAndNormalizeBlanks() {
    String[] types = {" application/pdf , image/jpeg", "IMAGE/PNG", "image/jpeg", null, " "};
    assertArrayEquals(new String[] {"application/pdf", "image/jpeg", "image/png"},
        WebUploadChooser.normalized(types));
    assertEquals("*/*", WebUploadChooser.mimeType(types));
    assertEquals("application/pdf", WebUploadChooser.mimeType(new String[] {" APPLICATION/PDF ", ""}));
    assertEquals("image/*", WebUploadChooser.mimeType(new String[] {"image/*"}));
  }

  @Test
  public void explicitWildcardDoesNotLeaveARestrictiveExtraFilter() {
    for (String[] types : new String[][] {{"image/png", "*/*"}, {"*", "application/pdf"}}) {
      assertEquals("*/*", WebUploadChooser.mimeType(types));
      assertArrayEquals(new String[] {"*/*"}, WebUploadChooser.normalized(types));
      assertFalse(WebUploadChooser.onlyImages(types));
    }
  }
}
