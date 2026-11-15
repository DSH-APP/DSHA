package com.deepseekharness.app.ui;

import static org.junit.Assert.*;

import android.app.Application;
import android.content.Intent;
import android.net.Uri;
import com.deepseekharness.app.transfer.DownloadTask;
import com.deepseekharness.app.util.UiText;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

/** Actual Android intent construction used by persistent local and remote file sharing. */
@RunWith(NativeFileShareTest.NoApkRunner.class)
@Config(sdk = 34, manifest = Config.NONE, application = Application.class)
public final class NativeFileShareTest {
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
  public void shareUsesSendWithExactUriMimeAndTemporaryReadGrant() {
    Uri uri =
        Uri.parse("content://io.github.sunsetrne.dsha.updates/transfers/share/task/report.txt");
    Intent chooser = NativeFileShare.chooser(uri, "report.txt", "text/plain", "Share file");
    assertEquals(Intent.ACTION_CHOOSER, chooser.getAction());
    Intent send = chooser.getParcelableExtra(Intent.EXTRA_INTENT, Intent.class);
    assertNotNull(send);
    assertEquals(Intent.ACTION_SEND, send.getAction());
    assertEquals("text/plain", send.getType());
    assertEquals(uri, send.getParcelableExtra(Intent.EXTRA_STREAM, Uri.class));
    assertEquals(uri, send.getClipData().getItemAt(0).getUri());
    assertEquals(uri, chooser.getClipData().getItemAt(0).getUri());
    assertEquals(
        Intent.FLAG_GRANT_READ_URI_PERMISSION,
        send.getFlags() & Intent.FLAG_GRANT_READ_URI_PERMISSION);
    assertEquals(0, send.getFlags() & Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
    assertEquals(0, chooser.getFlags() & Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
  }

  @Test
  public void pageDestinationLaunchTargetsOnlyTheRequestedPrivateActivityTask() {
    Intent intent =
        FileTransfersActivity.destinationIntent(
            RuntimeEnvironment.getApplication(), "task-1", "share", "owner-1");
    assertEquals(FileTransfersActivity.class.getName(), intent.getComponent().getClassName());
    assertEquals("task-1", intent.getStringExtra("transfer_explicit_task"));
    assertEquals("share", intent.getStringExtra("transfer_explicit_action"));
    assertEquals("owner-1", intent.getStringExtra("transfer_explicit_document"));
    assertNull(intent.getData());
  }

  @Test
  public void rawFilesystemAndNetworkUrisCannotBeShared() {
    for (String value :
        new String[] {
          "file:///private/stage", "https://foreign.invalid/file", "blob:http://127.0.0.1/x"
        })
      assertThrows(
          IllegalArgumentException.class,
          () -> NativeFileShare.chooser(Uri.parse(value), "file", "text/plain", "Share"));
  }

  @Test
  public void requestHeaderFollowsOnlyItsExactTaskAndDestination() {
    for (DownloadTask.Status status : DownloadTask.Status.values()) {
      assertEquals(
          "",
          FileTransfersActivity.requestStatusNotice(
              "current", "saveas", "old", "saveas", status, "old.txt"));
      assertEquals(
          "",
          FileTransfersActivity.requestStatusNotice(
              "current", "saveas", "current", "share", status, "other.txt"));
      assertEquals(
          "",
          FileTransfersActivity.requestStatusNotice(
              null, "saveas", "old", "saveas", status, "old.txt"));
    }
    assertEquals(
        "已保存文件：report.txt",
        FileTransfersActivity.requestStatusNotice(
            "current", "saveas", "current", "saveas", DownloadTask.Status.COMPLETE, "report.txt"));
    assertEquals(
        "文件操作已取消。",
        FileTransfersActivity.requestStatusNotice(
            "current", "saveas", "current", "saveas", DownloadTask.Status.CANCELLED, "report.txt"));
    assertTrue(
        FileTransfersActivity.requestStatusNotice(
                "current", "saveas", "current", "saveas", DownloadTask.Status.FAILED, "report.txt")
            .contains("失败"));
    assertEquals(
        "",
        FileTransfersActivity.requestStatusNotice(
            "current",
            "saveas",
            "current",
            "saveas",
            DownloadTask.Status.DOWNLOADING,
            "report.txt"));
    String language = UiText.language();
    try {
      UiText.setLanguage("en");
      assertEquals(
          "File saved: report.txt",
          FileTransfersActivity.requestStatusNotice(
              "current",
              "saveas",
              "current",
              "saveas",
              DownloadTask.Status.COMPLETE,
              "report.txt"));
    } finally {
      UiText.setLanguage(language);
    }
  }

  @Test
  public void completedShareHeaderNeverClaimsRecipientDelivery() {
    String message =
        FileTransfersActivity.requestStatusNotice(
            "current", "share", "current", "share", DownloadTask.Status.COMPLETE, "report.txt");
    assertTrue(message.contains("已就绪"));
    assertTrue(message.contains("不代表对方已收到"));
    assertFalse(message.contains("已保存"));
    assertFalse(message.contains("已送达"));
  }
}
