package com.deepseekharness.app.util;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import java.io.File;
import java.io.FileInputStream;
import java.nio.file.Files;
import static org.junit.Assert.*;

/** 所有创建/改名/清理由 JUnit 的专属临时目录承载，绝不读取实际用户环境。 */
public class MaintenanceTransactionTest {
    @Rule public TemporaryFolder fixture = new TemporaryFolder();
    private File oldEnvironment() throws Exception {
        File linux = new File(fixture.getRoot(), "linux"); assertTrue(linux.mkdir());
        Files.write(new File(linux, "private-session").toPath(), "old-session".getBytes()); return linux;
    }
    private MaintenanceTransaction verified() throws Exception {
        MaintenanceTransaction task = MaintenanceTransaction.create(fixture.getRoot());
        Files.write(task.archive().toPath(), "fixture-archive".getBytes());
        try (FileInputStream in = new FileInputStream(task.archive())) { task.verify(FileIntegrity.copy(in, null, 100).sha256); }
        return task;
    }
    @Test public void failedVerificationCannotMoveOriginal() throws Exception {
        File linux = oldEnvironment(); MaintenanceTransaction task = MaintenanceTransaction.create(fixture.getRoot());
        Files.write(task.archive().toPath(), "bad".getBytes());
        assertThrows(java.io.IOException.class, () -> task.verify("not-a-hash"));
        assertThrows(java.io.IOException.class, () -> task.begin(false));
        assertTrue(new File(linux, "private-session").isFile());
    }
    @Test public void extractionOrRestoreFailurePreservesBothTreesAndArchive() throws Exception {
        File linux = oldEnvironment(); MaintenanceTransaction task = verified(); task.begin(false);
        assertFalse(linux.exists()); assertTrue(linux.mkdir());
        Files.write(new File(linux, "new-partial").toPath(), "partial".getBytes());
        task.rollback();
        assertEquals("old-session", Files.readString(new File(linux, "private-session").toPath()));
        assertTrue(new File(task.directory(), "failed-linux/new-partial").exists());
        assertTrue(task.archive().exists()); assertNull(MaintenanceTransaction.pending(fixture.getRoot()));
    }
    @Test public void deathAfterRenameIsRecoverableByNewInstance() throws Exception {
        File linux = oldEnvironment(); MaintenanceTransaction task = verified(); task.begin(false);
        MaintenanceTransaction recovered = MaintenanceTransaction.pending(fixture.getRoot());
        assertNotNull(recovered); recovered.rollback();
        assertTrue(new File(linux, "private-session").exists()); assertTrue(task.archive().exists());
    }
    @Test public void deathBeforeRenameKeepsEnvironment() throws Exception {
        File linux = oldEnvironment(); MaintenanceTransaction task = verified();
        Files.writeString(new File(task.directory(), "intent.properties").toPath(), "hadEnvironment=true\n");
        MaintenanceTransaction.pending(fixture.getRoot()).rollback();
        assertTrue(new File(linux, "private-session").exists());
    }
    @Test public void deathDuringRollbackIsIdempotent() throws Exception {
        File linux = oldEnvironment(); MaintenanceTransaction task = verified(); task.begin(false);
        assertTrue(linux.mkdir()); assertTrue(linux.renameTo(new File(task.directory(), "failed-linux")));
        // 崩溃在新目录已保留、旧目录未回切之间。
        MaintenanceTransaction.pending(fixture.getRoot()).rollback();
        assertTrue(new File(linux, "private-session").exists());
    }
    @Test public void successKeepsOldTreeAndSnapshotWithoutPendingWork() throws Exception {
        File linux = oldEnvironment(); MaintenanceTransaction task = verified(); task.begin(false);
        assertTrue(linux.mkdir()); task.commit();
        assertTrue(new File(task.directory(), "previous-linux/private-session").exists());
        assertTrue(task.archive().exists()); assertNull(MaintenanceTransaction.pending(fixture.getRoot()));
        task.rollback(); assertFalse(new File(linux, "private-session").exists());
    }
    @Test public void onlyVerifiedCommittedMigrationMayReleasePreviousRuntime() throws Exception {
        File linux = oldEnvironment(); MaintenanceTransaction task = verified(); task.begin(false);
        assertTrue(linux.mkdir());
        final int[] calls = {0};
        MaintenanceTransaction.TreeCleaner cleaner = root -> {
            calls[0]++;
            assertEquals(new File(task.directory(), "previous-linux"), root);
            assertTrue(new File(root, "private-session").delete()); assertTrue(root.delete());
        };
        assertFalse(task.cleanup(cleaner)); assertEquals(0, calls[0]);
        task.dataPreserved("a".repeat(64));
        assertFalse(task.cleanup(cleaner)); assertEquals(0, calls[0]);
        task.commit();
        Files.writeString(task.personalArchive().toPath(), "personal-snapshot");
        assertTrue(task.cleanup(cleaner)); assertEquals(1, calls[0]);
        assertFalse(task.personalArchive().exists()); assertTrue(task.archive().isFile());
        assertTrue(task.cleanup(cleaner)); assertEquals(1, calls[0]);
    }
    @Test public void interruptedCleanupRetriesAndNeverRollsBackCommittedData() throws Exception {
        File linux = oldEnvironment(); MaintenanceTransaction task = verified(); task.begin(false);
        assertTrue(linux.mkdir()); task.dataPreserved("a".repeat(64)); task.commit();
        assertThrows(java.io.IOException.class, () -> task.cleanup(root -> { throw new java.io.IOException("disk busy"); }));
        assertNull(MaintenanceTransaction.pending(fixture.getRoot()));
        task.rollback(); assertTrue(linux.isDirectory());
        MaintenanceTransaction.cleanupCompleted(fixture.getRoot(), root -> {
            assertTrue(new File(root, "private-session").delete()); assertTrue(root.delete());
        });
        assertTrue(new File(task.directory(), "cleaned").isFile()); assertTrue(task.archive().isFile());
    }
    @Test public void historicalCommitWithoutPersonalDataProofIsNeverCleaned() throws Exception {
        oldEnvironment(); MaintenanceTransaction task = verified(); task.begin(false); task.commit();
        assertFalse(task.cleanup(root -> fail("不能删除历史个人目录")));
    }
    @Test public void freshInstallNeverTreatsExistingLinuxAsDisposable() throws Exception {
        File linux = oldEnvironment(); MaintenanceTransaction task = MaintenanceTransaction.create(fixture.getRoot());
        assertThrows(java.io.IOException.class, () -> task.begin(true));
        assertTrue(new File(linux, "private-session").exists());
    }
    @Test public void freshFailureKeepsFailedFilesAndCanRetry() throws Exception {
        MaintenanceTransaction task = MaintenanceTransaction.create(fixture.getRoot()); task.begin(true);
        assertTrue(new File(fixture.getRoot(), "linux").mkdir());
        task.rollback(); assertTrue(new File(task.directory(), "failed-linux").exists());
        assertFalse(new File(fixture.getRoot(), "linux").exists());
        assertNull(MaintenanceTransaction.pending(fixture.getRoot()));
    }
    @Test public void unknownIntentFailsClosed() throws Exception {
        File linux = oldEnvironment(); MaintenanceTransaction task = verified();
        Files.writeString(new File(task.directory(), "intent.properties").toPath(), "truncated");
        assertThrows(java.io.IOException.class, () -> MaintenanceTransaction.pending(fixture.getRoot()).rollback());
        assertTrue(new File(linux, "private-session").exists());
    }
    @Test public void rollbackNeverOverwritesExistingPreservedTree() throws Exception {
        File linux = oldEnvironment(); MaintenanceTransaction task = verified(); task.begin(false);
        assertTrue(linux.mkdir()); assertTrue(new File(task.directory(), "failed-linux").mkdir());
        assertThrows(java.io.IOException.class, task::rollback);
        assertTrue(new File(task.directory(), "previous-linux/private-session").exists()); assertTrue(linux.exists());
    }
}
