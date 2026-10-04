package com.deepseekharness.app.bridge;

/** Keeps clipboard content usable while hiding previews on supporting Android versions. */
public final class SensitiveClipboard {
  private SensitiveClipboard() {}

  public static android.content.ClipData text(CharSequence label, CharSequence value) {
    android.content.ClipData clip = android.content.ClipData.newPlainText(label, value);
    if (android.os.Build.VERSION.SDK_INT >= 24) {
      android.os.PersistableBundle extras = new android.os.PersistableBundle();
      extras.putBoolean("android.content.extra.IS_SENSITIVE", true);
      clip.getDescription().setExtras(extras);
    }
    return clip;
  }
}
