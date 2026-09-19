package com.deepseekharness.app.backup;

import org.junit.*;
import org.junit.rules.TemporaryFolder;
import java.io.*;
import java.nio.file.*;
import java.util.*;
import static org.junit.Assert.*;

public class NativeRestorePlanTest {
    @Rule public TemporaryFolder temporary=new TemporaryFolder();
    private final BackupFileSystem fs=new JvmBackupFileSystem();
    @Test public void inspectionAndCandidateDoNotTouchActiveDataAndKeepUnmentionedFiles()throws Exception{
        File task=temporary.newFolder(),active=temporary.newFolder(),archive=new File(task,"archive");Files.write(archive.toPath(),BackupArchiveTest.archive());
        Files.createDirectories(new File(active,"sessions").toPath());Files.writeString(new File(active,"sessions/kept.jsonl").toPath(),"newer untouched session");
        Files.writeString(new File(active,"settings.yaml").toPath(),"untouched settings");String before=BackupTree.digest(fs,active,new BackupControl(null));
        var plan=NativeRestorePlan.inspect(fs,task,archive,root->new NativeRestorePlan.Target(active,active,true),Set.of("application"),new BackupControl(null));
        assertEquals(before,BackupTree.digest(fs,active,new BackupControl(null)));assertEquals(List.of("dsh"),plan.buildCandidates(true,new BackupControl(null)));
        assertEquals(before,BackupTree.digest(fs,active,new BackupControl(null)));assertEquals("newer untouched session",Files.readString(new File(task,"candidate/dsh/sessions/kept.jsonl").toPath()));
        assertEquals("untouched settings",Files.readString(new File(task,"candidate/dsh/settings.yaml").toPath()));
        assertEquals("对话字节\n",Files.readString(new File(task,"candidate/dsh/sessions/a.jsonl").toPath()));
    }
    @Test public void footerFailureNeverProducesActiveWrites()throws Exception{
        File task=temporary.newFolder(),active=temporary.newFolder(),archive=new File(task,"archive");Files.writeString(new File(active,"data").toPath(),"old");byte[] data=BackupArchiveTest.archive();data[data.length-1]^=1;Files.write(archive.toPath(),data);
        assertThrows(IOException.class,()->NativeRestorePlan.inspect(fs,task,archive,r->new NativeRestorePlan.Target(active,active,true),Set.of("application"),new BackupControl(null)));
        assertEquals("old",Files.readString(new File(active,"data").toPath()));assertFalse(new File(task,"candidate").exists());
    }
    @Test public void unknownSelectedScopeDoesNotBecomeApplicationRestore()throws Exception{
        File task=temporary.newFolder(),archive=new File(task,"archive");Files.write(archive.toPath(),BackupArchiveTest.archive());
        assertThrows(IOException.class,()->NativeRestorePlan.inspect(fs,task,archive,r->{throw new AssertionError("must not resolve target");},Set.of("unknown"),new BackupControl(null)));
    }
    @Test public void relativeLinkEscapesAreRejected()throws Exception{
        assertThrows(IOException.class,()->NativeRestorePlan.relativeTarget("a","../outside"));assertEquals("folder/file",NativeRestorePlan.relativeTarget("folder/link","file"));
    }
}
