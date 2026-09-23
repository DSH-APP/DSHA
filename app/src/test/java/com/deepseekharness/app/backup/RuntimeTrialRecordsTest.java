package com.deepseekharness.app.backup;

import java.io.File;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import static org.junit.Assert.*;

public class RuntimeTrialRecordsTest {
    @Rule public TemporaryFolder temporary=new TemporaryFolder();
    private final JvmBackupFileSystem fs=new JvmBackupFileSystem();

    @Test public void thirtyThreeConsecutiveTrialsRotateWithoutPermanentLimit()throws Exception{
        File home=temporary.newFolder("runtime-trials");
        for(int i=0;i<33;i++){
            RuntimeTrialRecords.prepareForNew(fs,home);
            complete(home,id(i),i%3==0);
            RuntimeTrialRecords.pruneClosed(fs,home);
        }
        List<String> entries=fs.list(home);int succeeded=0,failed=0;
        for(String id:entries){
            File operation=fs.child(home,id);
            if(fs.stat(fs.child(operation,"failure.json")).type.equals("FILE"))failed++;else succeeded++;
        }
        assertEquals(RuntimeTrialRecords.KEEP_SUCCEEDED,succeeded);
        assertEquals(RuntimeTrialRecords.KEEP_FAILED,failed);
        RuntimeTrialRecords.prepareForNew(fs,home);
    }

    @Test public void interruptedTrialIsPreservedUntilRecoveryThenRotationContinues()throws Exception{
        File home=temporary.newFolder("interrupted-trials");
        for(int i=0;i<12;i++)complete(home,id(i),false);
        String pending=id(99);File operation=fs.child(home,pending);fs.directory(operation);
        File payload=fs.child(operation,"payload");fs.directory(payload);write(payload,"evidence.txt","keep");

        assertEquals(1,RuntimeTrialRecords.pruneClosed(fs,home));
        assertTrue(fs.stat(operation).type.equals("DIRECTORY"));
        assertTrue(fs.stat(fs.child(payload,"evidence.txt")).type.equals("FILE"));
        IOException blocked=assertThrows(IOException.class,()->RuntimeTrialRecords.prepareForNew(fs,home));
        assertEquals("TRIAL_RECOVERY_REQUIRED",blocked.getMessage());

        // 对应 RuntimeTrial.recoverPending：确认进程退出后写 closed，再删除本次 payload。
        write(operation,"closed",pending);fs.removeOwned(operation,"payload");
        assertEquals(0,RuntimeTrialRecords.pruneClosed(fs,home));
        RuntimeTrialRecords.prepareForNew(fs,home);
        assertTrue(fs.stat(operation).type.equals("DIRECTORY"));
    }

    @Test public void malformedMarkerPreventsAnyAutomaticDeletion()throws Exception{
        File home=temporary.newFolder("invalid-trials");
        for(int i=0;i<12;i++)complete(home,id(i),false);
        File bad=fs.child(home,id(77));fs.directory(bad);write(bad,"closed","not-the-id");
        int before=fs.list(home).size();
        IOException failure=assertThrows(IOException.class,()->RuntimeTrialRecords.pruneClosed(fs,home));
        assertEquals("TRIAL_MARKER",failure.getMessage());
        assertEquals(before,fs.list(home).size());
    }

    @Test public void latestFailureReturnsOnlyNewestClosedRecord()throws Exception{
        File home=temporary.newFolder("latest-trial-failure");
        String older=id(1),newer=id(2),pending=id(3);
        complete(home,older,true);assertTrue(fs.child(fs.child(home,older),"failure.json").delete());write(fs.child(home,older),"failure.json","{\"error\":\"OLDER\"}");
        complete(home,newer,true);assertTrue(fs.child(fs.child(home,newer),"failure.json").delete());write(fs.child(home,newer),"failure.json","{\"error\":\"NEWER\"}");
        File pendingDirectory=fs.child(home,pending);fs.directory(pendingDirectory);write(pendingDirectory,"failure.json","{\"error\":\"PENDING\"}");
        assertTrue(fs.child(fs.child(home,older),"closed").setLastModified(1_000));
        assertTrue(fs.child(fs.child(home,newer),"closed").setLastModified(2_000));

        assertEquals("{\"error\":\"NEWER\"}",new String(RuntimeTrialRecords.latestFailure(fs,home),StandardCharsets.US_ASCII));
        assertTrue(fs.stat(pendingDirectory).type.equals("DIRECTORY"));
    }

    private void complete(File home,String id,boolean failed)throws Exception{
        File operation=fs.child(home,id);fs.directory(operation);write(operation,"intent.json","{}");
        if(failed)write(operation,"failure.json","{}");
        write(operation,"closed",id);
    }
    private void write(File directory,String name,String value)throws Exception{
        try(OutputStream out=fs.create(fs.child(directory,name))){out.write(value.getBytes(StandardCharsets.US_ASCII));}
        fs.syncDirectory(directory);
    }
    private static String id(int value){return String.format("00000000-0000-0000-0000-%012d",value);}
}
