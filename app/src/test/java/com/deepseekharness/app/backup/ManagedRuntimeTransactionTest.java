package com.deepseekharness.app.backup;

import com.deepseekharness.app.util.ManagedRuntimeLayout;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import java.io.*;
import java.nio.file.Files;
import java.util.*;
import static org.junit.Assert.*;

/** 真实私有文件验证：试运行失败、退出不明、保留旧版本和非目标数据均覆盖。 */
public class ManagedRuntimeTransactionTest {
    @Rule public TemporaryFolder temp=new TemporaryFolder();
    private final BackupFileSystem fs=new JvmBackupFileSystem();
    private static final String RUNTIME=ManagedRuntimeLayout.ROOT+ManagedRuntimeLayout.DSH;
    static RuntimeDescriptor descriptor(char id)throws Exception{var json=RuntimeDescriptorTest.value();json.put("inputs",RuntimeDescriptorTest.inputs(id));RuntimeDescriptorTest.identify(json);return new RuntimeDescriptor(json);}
    static Map<String,Object> health(RuntimeDescriptor descriptor){var proof=new LinkedHashMap<String,Object>();proof.put("runtimeId",descriptor.id());proof.put("nonce","1".repeat(32));proof.put("port",3080L);proof.put("confirmedAt",1L);
        for(String check:List.of("assets","nativeModules","process","authentication","localApi","renderer","dataRead","dataWrite","storageFreshReopened","processExited"))proof.put(check,true);return proof;}
    private void put(File root,String path,String text)throws Exception{File target=new File(root,path);Files.createDirectories(target.getParentFile().toPath());Files.writeString(target.toPath(),text);}
    private String read(File root,String path)throws Exception{return Files.readString(new File(root,path).toPath());}
    private ManagedRuntimeTransaction prepare(char old,char next)throws Exception{
        put(temp.getRoot(),RUNTIME+"/lib/bin.js",String.valueOf(old));put(temp.getRoot(),"linux/ubuntu/root/.dsh/sessions/chat","original chat");
        var task=ManagedRuntimeTransaction.create(fs,temp.getRoot());put(task.stage(),RUNTIME+"/lib/bin.js",String.valueOf(next));
        var previous=health(descriptor(old));previous.put("managedHashes",Map.of(RUNTIME,BackupTree.digest(fs,new File(temp.getRoot(),RUNTIME),new BackupControl(null))));
        task.prepare(List.of(RUNTIME),descriptor(next),descriptor(old),previous,new BackupControl(null));return task;
    }
    @Test public void webFailureRestoresOldFilesAndRetainsFailedCandidate()throws Exception{
        var task=prepare('a','b');assertThrows(IOException.class,()->task.commit(new BackupControl(null),()->{throw new IOException("WEB_FAILED");},()->true));
        assertEquals("a",read(temp.getRoot(),RUNTIME+"/lib/bin.js"));assertEquals("b",read(task.directory(),"failed/runtime-0/lib/bin.js"));
        assertEquals("original chat",read(temp.getRoot(),"linux/ubuntu/root/.dsh/sessions/chat"));assertTrue(ManagedRuntimeTransaction.pending(fs,temp.getRoot()).isEmpty());
    }
    @Test public void nodeVersionAloneCannotFinalize()throws Exception{
        var task=prepare('a','b');assertThrows(IOException.class,()->task.commit(new BackupControl(null),()->Map.of("runtimeId","b".repeat(64),"nativeModules",true),()->true));
        assertEquals("a",read(temp.getRoot(),RUNTIME+"/lib/bin.js"));assertFalse(new File(task.directory(),"finalized").exists());
    }
    @Test public void unconfirmedGuestExitKeepsBothTreesAndBlocksNewUpdate()throws Exception{
        var task=prepare('a','b');assertThrows(IOException.class,()->task.commit(new BackupControl(null),()->{throw new IOException("TRIAL_PROCESS_UNCONFIRMED");},()->false));
        assertEquals("b",read(temp.getRoot(),RUNTIME+"/lib/bin.js"));assertEquals("a",read(task.directory(),"previous/runtime-0/lib/bin.js"));
        assertThrows(IOException.class,()->ManagedRuntimeTransaction.create(fs,temp.getRoot()));
        var pending=ManagedRuntimeTransaction.pending(fs,temp.getRoot());assertEquals(1,pending.size());pending.get(0).recover();assertEquals("a",read(temp.getRoot(),RUNTIME+"/lib/bin.js"));
    }
    @Test public void lateUserDataNeverRollsBackAfterHealthCommit()throws Exception{
        var task=prepare('a','b');var next=descriptor('b');task.commit(new BackupControl(null),()->health(next),()->true);
        assertTrue(task.hasHealthyPrevious());put(temp.getRoot(),"linux/ubuntu/root/.dsh/sessions/chat","new chat");task.recover();
        assertEquals("new chat",read(temp.getRoot(),"linux/ubuntu/root/.dsh/sessions/chat"));assertEquals("b",read(temp.getRoot(),RUNTIME+"/lib/bin.js"));
        ManagedRuntimeTransaction.trimOlder(fs,temp.getRoot(),next,health(next));assertEquals("a",read(task.directory(),"previous/runtime-0/lib/bin.js"));
    }
    @Test public void healthCheckCannotMutateManagedCandidate()throws Exception{
        var task=prepare('a','b');var next=descriptor('b');assertThrows(IOException.class,()->task.commit(new BackupControl(null),()->{
            Files.writeString(new File(temp.getRoot(),RUNTIME+"/lib/bin.js").toPath(),"unexpected mutation");return health(next);
        },()->true));
        assertEquals("unexpected mutation",read(temp.getRoot(),RUNTIME+"/lib/bin.js"));assertEquals("a",read(task.directory(),"previous/runtime-0/lib/bin.js"));
        assertEquals(1,ManagedRuntimeTransaction.pending(fs,temp.getRoot()).size());
    }
    private List<ManagedRuntimeTransaction> committedHistory()throws Exception{
        List<ManagedRuntimeTransaction> history=new ArrayList<>();
        for(int index=0;index<4;index++){
            char old=(char)('a'+index),next=(char)(old+1);var task=prepare(old,next);var candidate=descriptor(next);
            task.commit(new BackupControl(null),()->health(candidate),()->true);
            File mappingFile=new File(task.directory(),"runtime-mapping.json");
            var mapping=BackupJson.read(Files.readAllBytes(mappingFile.toPath()),BackupLimits.MANIFEST);
            mapping.put("createdAt",100L+index);Files.write(mappingFile.toPath(),BackupJson.write(mapping,BackupLimits.MANIFEST));
            history.add(task);
        }return history;
    }
    @Test public void cleanupRechecksBytesAndRetainsTwoVerifiedPredecessors()throws Exception{
        var history=committedHistory();put(history.get(0).directory(),"previous/runtime-0/lib/bin.js","user edited original");
        var active=descriptor('e');ManagedRuntimeTransaction.trimOlder(fs,temp.getRoot(),active,health(active));
        assertEquals("user edited original",read(history.get(0).directory(),"previous/runtime-0/lib/bin.js"));
        assertFalse(new File(history.get(1).directory(),"previous").exists());
        assertEquals("c",read(history.get(2).directory(),"previous/runtime-0/lib/bin.js"));
        assertEquals("d",read(history.get(3).directory(),"previous/runtime-0/lib/bin.js"));
    }
    @Test public void incompatibleHealthyVersionsDoNotAccumulateForever()throws Exception{
        var history=committedHistory();var value=RuntimeDescriptorTest.value();value.put("dataWrite","dsh-v4");value.put("dataRead",List.of("dsh-v4"));RuntimeDescriptorTest.identify(value);var active=new RuntimeDescriptor(value);
        assertFalse(descriptor('a').canReadDataWrittenBy(active));ManagedRuntimeTransaction.trimOlder(fs,temp.getRoot(),active,health(active));
        assertFalse(new File(history.get(0).directory(),"previous").exists());assertFalse(new File(history.get(1).directory(),"previous").exists());
        assertTrue(new File(history.get(2).directory(),"previous").exists());assertTrue(new File(history.get(3).directory(),"previous").exists());
        assertEquals("original chat",read(temp.getRoot(),"linux/ubuntu/root/.dsh/sessions/chat"));
    }
    @Test public void changedNewerCopiesCannotJustifyDeletingLastHealthyCopies()throws Exception{
        var history=committedHistory();for(int index=2;index<4;index++)put(history.get(index).directory(),"previous/runtime-0/lib/bin.js","changed");
        var active=descriptor('e');ManagedRuntimeTransaction.trimOlder(fs,temp.getRoot(),active,health(active));
        for(var task:history)assertTrue(new File(task.directory(),"previous").isDirectory());
        assertEquals("a",read(history.get(0).directory(),"previous/runtime-0/lib/bin.js"));
        assertEquals("b",read(history.get(1).directory(),"previous/runtime-0/lib/bin.js"));
    }
    @Test public void unknownFileInsideRetainedTreePreventsAutomaticCleanup()throws Exception{
        var history=committedHistory();put(history.get(0).directory(),"previous/unique-project.txt","only copy");
        var active=descriptor('e');ManagedRuntimeTransaction.trimOlder(fs,temp.getRoot(),active,health(active));
        assertEquals("only copy",read(history.get(0).directory(),"previous/unique-project.txt"));
        assertEquals("a",read(history.get(0).directory(),"previous/runtime-0/lib/bin.js"));
    }
    @Test public void runtimeMappingCannotWriteUserTargets()throws Exception{
        var task=ManagedRuntimeTransaction.create(fs,temp.getRoot());put(task.stage(),"linux/ubuntu/root/.dsh/sessions/chat","incoming");
        assertThrows(IOException.class,()->task.prepare(List.of("linux/ubuntu/root/.dsh/sessions"),descriptor('b'),null,null,new BackupControl(null)));
        assertFalse(new File(temp.getRoot(),"linux/ubuntu/root/.dsh/sessions").exists());
    }
    @Test public void historyBeyondLegacyThirtyTwoStillAllowsNewTransaction()throws Exception{
        File home=new File(temp.getRoot(),ManagedRuntimeTransaction.HOME);assertTrue(home.mkdirs());
        for(int i=0;i<32;i++)assertTrue(new File(home,UUID.randomUUID().toString()).mkdirs());
        var task=ManagedRuntimeTransaction.create(fs,temp.getRoot());
        assertNotNull(task);assertEquals(33,fs.list(home).size());
    }
    @Test public void processDeathAtHealthAndAgainDuringRollbackRetainsOriginal()throws Exception{
        var task=prepare('a','b');killAt(task,"validation-complete",false);
        assertTrue(new File(task.directory(),"health.json").isFile());assertFalse(new File(task.directory(),"finalized").exists());
        killAt(task,"rollback-new-runtime-0",true);
        var pending=ManagedRuntimeTransaction.pending(fs,temp.getRoot());assertEquals(1,pending.size());pending.get(0).recover();pending.get(0).recover();
        assertEquals("a",read(temp.getRoot(),RUNTIME+"/lib/bin.js"));assertEquals("original chat",read(temp.getRoot(),"linux/ubuntu/root/.dsh/sessions/chat"));
        assertEquals("b",read(task.directory(),"failed/runtime-0/lib/bin.js"));
    }
    @Test public void explicitCompatibleRollbackKeepsMessagesWrittenByNewVersion()throws Exception{
        var task=prepare('a','b');var current=descriptor('b');task.commit(new BackupControl(null),()->health(current),()->true);
        put(temp.getRoot(),"linux/ubuntu/root/.dsh/sessions/chat","message written by b");
        var options=ManagedRuntimeTransaction.rollbackOptions(fs,temp.getRoot(),current,current);assertEquals(1,options.size());
        var rollback=ManagedRuntimeTransaction.prepareRollback(fs,temp.getRoot(),options.get(0).operationId,current,current,health(current),new BackupControl(null));
        var old=descriptor('a');rollback.commit(new BackupControl(null),()->health(old),()->true);
        assertEquals("a",read(temp.getRoot(),RUNTIME+"/lib/bin.js"));assertEquals("message written by b",read(temp.getRoot(),"linux/ubuntu/root/.dsh/sessions/chat"));
        assertEquals("b",read(rollback.directory(),"previous/runtime-0/lib/bin.js"));
    }
    @Test public void incompatibleLauncherOrTamperedRetainedRuntimeCannotRollBack()throws Exception{
        var task=prepare('a','b');var current=descriptor('b');task.commit(new BackupControl(null),()->health(current),()->true);
        var changed=current.json();changed.put("launcherContract","NEXT_CONTRACT");RuntimeDescriptorTest.identify(changed);var nextApk=new RuntimeDescriptor(changed);
        assertTrue(ManagedRuntimeTransaction.rollbackOptions(fs,temp.getRoot(),nextApk,current).isEmpty());
        assertThrows(IOException.class,()->ManagedRuntimeTransaction.prepareRollback(fs,temp.getRoot(),task.directory().getName(),nextApk,current,health(current),new BackupControl(null)));
        put(task.directory(),"previous/runtime-0/lib/bin.js","tampered");
        assertThrows(IOException.class,()->ManagedRuntimeTransaction.prepareRollback(fs,temp.getRoot(),task.directory().getName(),current,current,health(current),new BackupControl(null)));
        assertEquals("b",read(temp.getRoot(),RUNTIME+"/lib/bin.js"));assertEquals(1,fs.list(new File(temp.getRoot(),ManagedRuntimeTransaction.HOME)).size());
    }
    @Test public void rollbackRemovesOnlyNewManagedAliasAndKeepsBothVersions()throws Exception{
        String alias="linux/ubuntu/usr/local/bin/tsserver";put(temp.getRoot(),RUNTIME+"/lib/bin.js","a");
        var previous=health(descriptor('a'));previous.put("managedHashes",Map.of(RUNTIME,BackupTree.digest(fs,new File(temp.getRoot(),RUNTIME),new BackupControl(null))));
        var task=ManagedRuntimeTransaction.create(fs,temp.getRoot());put(task.stage(),RUNTIME+"/lib/bin.js","b");put(task.stage(),alias,"new managed alias");
        task.prepare(List.of(RUNTIME,alias),descriptor('b'),descriptor('a'),previous,new BackupControl(null));var current=descriptor('b');task.commit(new BackupControl(null),()->health(current),()->true);
        var rollback=ManagedRuntimeTransaction.prepareRollback(fs,temp.getRoot(),task.directory().getName(),current,current,health(current),new BackupControl(null));
        var old=descriptor('a');rollback.commit(new BackupControl(null),()->health(old),()->true);
        assertFalse(new File(temp.getRoot(),alias).exists());assertEquals("new managed alias",read(rollback.directory(),"previous/runtime-1"));assertEquals("a",read(temp.getRoot(),RUNTIME+"/lib/bin.js"));
    }
    private void killAt(ManagedRuntimeTransaction task,String phase,boolean recovery)throws Exception{
        File marker=new File(temp.getRoot(),"reached-"+phase);Set<String> classpath=new LinkedHashSet<>();
        for(Class<?> type:List.of(ManagedRuntimeTransactionTest.class,HostDataTransaction.class,com.google.gson.stream.JsonReader.class,org.junit.Assert.class))classpath.add(new File(type.getProtectionDomain().getCodeSource().getLocation().toURI()).getPath());
        File binary=new File(System.getProperty("java.home"),"bin/"+(System.getProperty("os.name").startsWith("Windows")?"java.exe":"java"));
        Process child=new ProcessBuilder(binary.getPath(),"-cp",String.join(File.pathSeparator,classpath),HostRuntimeCrashProcess.class.getName(),temp.getRoot().getPath(),task.directory().getName(),phase,Boolean.toString(recovery))
                .redirectErrorStream(true).redirectOutput(new File(temp.getRoot(),"runtime-child-"+phase+".log")).start();
        long until=System.currentTimeMillis()+15000;try{
            while(!marker.exists()&&child.isAlive()&&System.currentTimeMillis()<until)Thread.sleep(20);
            assertTrue("未到真实强杀边界："+phase,marker.exists());
        }finally{child.destroyForcibly();assertTrue(child.waitFor(10,java.util.concurrent.TimeUnit.SECONDS));}
    }
}
