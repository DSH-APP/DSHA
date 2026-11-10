package com.deepseekharness.app.backup;

import static org.junit.Assert.*;

import android.app.Application;
import android.system.ErrnoException;
import com.deepseekharness.app.DocumentRowsSnapshotTest;
import java.io.*;
import java.nio.file.Files;
import java.util.List;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.junit.runner.RunWith;
import org.robolectric.annotation.Config;

/** Real Android exception types; no Android or user filesystem path is probed. */
@RunWith(DocumentRowsSnapshotTest.NoApkRunner.class)
@Config(sdk = 34, manifest = Config.NONE, application = Application.class)
public final class BackupOperationErrnoDiagnosticsTest {
  @Rule public TemporaryFolder temporary = new TemporaryFolder();

  @Test
  public void wrappedAndroidErrnoSurvivesAsNumberWithoutFunctionOrMessage() throws Exception {
    File files = temporary.newFolder();
    Files.createDirectory(new File(files, "host-backup-operations").toPath());
    for (int errno : new int[] {1, 13, 24, 28, 40}) {
      IOException failure =
          new IOException(
              "SYNTHETIC_PRIVATE_OUTER", new ErrnoException("SYNTHETIC_PRIVATE_CALL", errno));
      BackupFileSystem fs =
          new JvmBackupFileSystem() {
            @Override
            public List<String> list(File directory) throws IOException {
              throw failure;
            }
          };
      String report = BackupOperationDiagnostics.describe(fs, files);
      assertTrue(
          report.contains(
              "activeFailureStage=DIRECTORY_LIST activeFailureType=IO activeFailureErrno="
                  + errno));
      assertFalse(report.contains("SYNTHETIC_PRIVATE"));
      assertTrue(report.contains("complete=false"));
    }
  }

  @Test
  public void invalidAndroidErrnoIsNotReportedAsAnOsFailureNumber() throws Exception {
    File files = temporary.newFolder();
    Files.createDirectory(new File(files, "host-backup-operations").toPath());
    BackupFileSystem fs =
        new JvmBackupFileSystem() {
          @Override
          public List<String> list(File directory) throws IOException {
            throw new IOException(new ErrnoException("SYNTHETIC_PRIVATE_CALL", 999999));
          }
        };
    String report = BackupOperationDiagnostics.describe(fs, files);
    assertTrue(report.contains("activeFailureErrno=-1"));
    assertFalse(report.contains("999999"));
    assertFalse(report.contains("SYNTHETIC_PRIVATE"));
  }
}
