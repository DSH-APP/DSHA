package com.deepseekharness.app.backup;
import org.junit.*;
import org.junit.rules.TemporaryFolder;
import java.io.*;
import java.nio.file.*;
import static org.junit.Assert.*;

public class RetiredEnvironmentCleanupTest {
    @Rule public TemporaryFolder temp=new TemporaryFolder();
    private final JvmBackupFileSystem fs=new JvmBackupFileSystem();
    private EnvironmentRebuildTransaction committed(File files)throws Exception{
        NativeEnvironmentRebuildTest.fixture(files);var task=NativeEnvironmentRebuildTest.prepared(files);task.begin();
        NativeEnvironmentRebuildTest.put(files,"linux/ubuntu/usr/bin/system-tool","new system");
        new MaintenanceDataSnapshot(fs,files,task.directory(),new File(files,"public")).restore(new File(files,"linux/ubuntu"),new BackupControl(null));
        task.dataRestored(NativeEnvironmentRebuildTest.hash(new File(task.directory(),"data.dshdata")));
        var descriptor=ManagedRuntimeTransactionTest.descriptor('b');task.commit(descriptor,ManagedRuntimeTransactionTest.health(descriptor));return task;
    }
    @Test public void cleanupKeepsProtectedDataAndCurrentEdits()throws Exception{
        File files=temp.newFolder();var task=committed(files);task.sealRetired(new BackupControl(null));
        NativeEnvironmentRebuildTest.put(files,"linux/ubuntu/root/.dsh/sessions/a","new message");
        assertTrue(task.cleanupRetired(new BackupControl(null)));assertFalse(new File(task.directory(),"previous-linux").exists());
        assertEquals("new message",Files.readString(new File(files,"linux/ubuntu/root/.dsh/sessions/a").toPath()));
        assertTrue(new File(task.directory(),"data.dshdata").isFile());assertFalse(task.cleanupRetired(new BackupControl(null)));
    }
    @Test public void oldRecordsWithoutProofRemain()throws Exception{
        File files=temp.newFolder();var task=committed(files);assertFalse(task.cleanupRetired(new BackupControl(null)));assertTrue(new File(task.directory(),"previous-linux").exists());
    }
    @Test public void changedOldTreeOrArchivePreventsRemoval()throws Exception{
        File files=temp.newFolder();var task=committed(files);task.sealRetired(new BackupControl(null));
        NativeEnvironmentRebuildTest.put(task.directory(),"previous-linux/ubuntu/root/new-original","keep me");
        assertThrows(IOException.class,()->task.cleanupRetired(new BackupControl(null)));assertTrue(new File(task.directory(),"previous-linux/ubuntu/root/new-original").exists());
        Files.writeString(new File(task.directory(),"data.dshdata").toPath(),"damaged");assertThrows(IOException.class,()->task.cleanupRetired(new BackupControl(null)));
    }
    @Test public void uncommittedTreeAndRootDeletionAreRejected()throws Exception{
        File files=temp.newFolder();NativeEnvironmentRebuildTest.fixture(files);var task=NativeEnvironmentRebuildTest.prepared(files);task.begin();
        assertThrows(IOException.class,()->task.sealRetired(new BackupControl(null)));assertFalse(task.cleanupRetired(new BackupControl(null)));
        assertThrows(IOException.class,()->fs.removeOwned(files,""));assertTrue(new File(task.directory(),"previous-linux").exists());
    }
}
