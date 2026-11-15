package com.deepseekharness.app.ui;

import android.content.ClipData;
import android.content.Intent;
import android.net.Uri;

/** File sharing always opens ACTION_SEND; a chooser launch is not a delivery receipt. */
public final class NativeFileShare {
  private NativeFileShare() {}

  public static Intent chooser(Uri uri, String name, String mime, String title) {
    if (uri == null || !"content".equals(uri.getScheme()))
      throw new IllegalArgumentException("FILE_SHARE_URI_UNAVAILABLE");
    String type = mime == null || !mime.contains("/") ? "application/octet-stream" : mime;
    Intent send =
        new Intent(Intent.ACTION_SEND)
            .setType(type)
            .putExtra(Intent.EXTRA_STREAM, uri)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
    send.setClipData(new ClipData(name, new String[] {type}, new ClipData.Item(uri)));
    Intent chooser =
        Intent.createChooser(send, title).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
    chooser.setClipData(send.getClipData());
    return chooser;
  }
}
