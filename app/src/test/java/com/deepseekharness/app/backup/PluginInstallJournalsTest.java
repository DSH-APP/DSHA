package com.deepseekharness.app.backup;

import org.junit.*;
import org.junit.rules.TemporaryFolder;
import java.io.*;
import java.nio.file.Files;
import java.util.*;
import static org.junit.Assert.*;

public class PluginInstallJournalsTest {
    @Rule public TemporaryFolder temp=new TemporaryFolder();
    private final BackupFileSystem fs=new JvmBackupFileSystem();
    private File operation()throws Exception{File result=new File(temp.getRoot(),PluginInstallJournals.DIRECTORY+"/"+UUID.randomUUID());assertTrue(result.mkdirs());return result;}
    @Test public void onlyPreparedUnfinishedOperationsBlockMaintenance()throws Exception{
        File work=operation();assertTrue(PluginInstallJournals.pending(fs,temp.getRoot()).isEmpty());
        Files.writeString(new File(work,"plan.json").toPath(),"{}");assertEquals(List.of(work),PluginInstallJournals.pending(fs,temp.getRoot()));
        Files.writeString(new File(work,"committed").toPath(),work.getName()+"\ncommitted\n");assertTrue(PluginInstallJournals.pending(fs,temp.getRoot()).isEmpty());
        Files.writeString(new File(temp.getRoot(),"new-user-content").toPath(),"later data");assertTrue(PluginInstallJournals.pending(fs,temp.getRoot()).isEmpty());
        assertEquals("later data",Files.readString(new File(temp.getRoot(),"new-user-content").toPath()));
    }
    @Test public void malformedMarkersAndUnknownDirectoriesAreNotEmptyState()throws Exception{
        File work=operation();Files.writeString(new File(work,"committed").toPath(),"untrusted");
        assertThrows(IOException.class,()->PluginInstallJournals.pending(fs,temp.getRoot()));
    }
    @Test public void recoveredJournalRemainsInspectableWithoutBlockingNewWork()throws Exception{
        File work=operation();Files.writeString(new File(work,"plan.json").toPath(),"{}");
        Files.writeString(new File(work,"rolled-back").toPath(),work.getName()+"\nrolled-back\n");
        Files.writeString(new File(work,"retained-original").toPath(),"original");
        assertTrue(PluginInstallJournals.pending(fs,temp.getRoot()).isEmpty());assertEquals("original",Files.readString(new File(work,"retained-original").toPath()));
    }
}
