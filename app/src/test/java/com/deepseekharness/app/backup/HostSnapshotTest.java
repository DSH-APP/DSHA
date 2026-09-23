package com.deepseekharness.app.backup;

import org.junit.*;
import org.junit.rules.TemporaryFolder;
import java.io.*;
import java.nio.file.*;
import java.util.*;
import static org.junit.Assert.*;

public class HostSnapshotTest {
    @Rule public TemporaryFolder temporary=new TemporaryFolder();
    private final BackupFileSystem fs=new JvmBackupFileSystem();
    @Test public void copiesHiddenFilesCustomDependenciesAndProjectBytes()throws Exception{
        File source=temporary.newFolder(),task=temporary.newFolder(),archive=new File(task,"archive");
        Files.createDirectories(new File(source,"node_modules/custom").toPath());Files.writeString(new File(source,"node_modules/custom/source.js").toPath(),"user modified");Files.writeString(new File(source,".env").toPath(),"owned secret");
        var root=new FileBackupSource(fs,"project","projects",source,false,null);
        Map<String,Object> result=HostSnapshot.create(fs,List.of(root),task,archive,BackupArchiveTest.summary(),false,new BackupControl(null));assertEquals("QUIESCENT",result.get("integrity"));
        List<String> names=new ArrayList<>();try(InputStream in=new FileInputStream(archive)){BackupArchive.read(in,new BackupArchive.Visitor(){public OutputStream payload(int n,BackupArchive.Record r){names.add(r.path);return null;}},new BackupControl(null));}
        assertTrue(names.contains(".env"));assertTrue(names.contains("node_modules/custom/source.js"));
    }
    @Test public void missingRootIsNotCompleteAndRescueRecordsPartial()throws Exception{
        File task=temporary.newFolder();var missing=new FileBackupSource(fs,"dsh","application",new File(task,"missing"),true,null);
        File failedTask=temporary.newFolder(),partialTask=temporary.newFolder();
        assertThrows(IOException.class,()->HostSnapshot.create(fs,List.of(missing),failedTask,new File(failedTask,"failed"),BackupArchiveTest.summary(),false,new BackupControl(null)));
        Map<String,Object> result=HostSnapshot.create(fs,List.of(missing),partialTask,new File(partialTask,"partial"),BackupArchiveTest.summary(),true,new BackupControl(null));assertEquals("PARTIAL",result.get("integrity"));
    }
    @Test public void sameSizeAndMtimeChangesAreCaughtBySecondRead()throws Exception{
        File source=temporary.newFolder(),task=temporary.newFolder(),file=new File(source,"a");Files.writeString(file.toPath(),"old");
        BackupSource root=new FileBackupSource(fs,"dsh","application",source,true,null){@Override public void verify(Item item,String hash,BackupControl control)throws IOException{
            if(item.kind.equals("FILE")){var time=Files.getLastModifiedTime(file.toPath());Files.writeString(file.toPath(),"new");Files.setLastModifiedTime(file.toPath(),time);}super.verify(item,hash,control);}};
        assertThrows(IOException.class,()->HostSnapshot.create(fs,List.of(root),task,new File(task,"archive"),BackupArchiveTest.summary(),false,new BackupControl(null)));
    }
    @Test public void cancellationDoesNotReplaceExistingSuccessfulCopy()throws Exception{
        File source=temporary.newFolder(),task=temporary.newFolder(),latest=new File(task,"latest");Files.writeString(latest.toPath(),"previous validated backup");Files.write(new File(source,"large").toPath(),new byte[100000]);
        BackupControl control=new BackupControl(null);control.cancel();
        assertThrows(IOException.class,()->HostSnapshot.create(fs,List.of(new FileBackupSource(fs,"dsh","application",source,false,null)),task,new File(task,"partial"),BackupArchiveTest.summary(),false,control));
        assertEquals("previous validated backup",Files.readString(latest.toPath()));
    }
}
