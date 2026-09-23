package com.deepseekharness.app.backup;

import org.junit.*;
import org.junit.rules.TemporaryFolder;
import java.io.*;
import java.nio.file.*;
import java.util.*;
import static org.junit.Assert.*;

/** 全部夹具只含配置与对话字节，不提供 Bash、Python、Node 或 proot。 */
public class ConfigurationSnapshotsTest {
    @Rule public TemporaryFolder temp=new TemporaryFolder();
    static File dsh(File root){return new File(root,"linux/ubuntu/root/.dsh");}
    static ConfigurationSnapshots engine(File root,HostDataTransaction.Fault fault){return new ConfigurationSnapshots(new JvmBackupFileSystem(),root,dsh(root),fault);}
    static void put(File root,String relative,String value)throws Exception{File file=new File(root,relative);Files.createDirectories(file.getParentFile().toPath());Files.writeString(file.toPath(),value);}
    static void fixture(File root)throws Exception{
        Files.createDirectories(dsh(root).toPath());for(int i=0;i<ConfigurationSnapshots.FILES.size();i++)put(dsh(root),ConfigurationSnapshots.FILES.get(i),"original-"+i);
        put(dsh(root),"sessions/chat","untouched conversation");put(root,"native-credential","unchanged local ciphertext");
    }
    @SuppressWarnings("unchecked") static List<Map<String,Object>> rows(ConfigurationSnapshots engine)throws Exception{return (List<Map<String,Object>>)engine.list(new BackupControl(null)).get("snapshots");}
    static void untouched(File root)throws Exception{assertEquals("untouched conversation",Files.readString(new File(dsh(root),"sessions/chat").toPath()));assertEquals("unchanged local ciphertext",Files.readString(new File(root,"native-credential").toPath()));}
    @Test public void nativeCaptureAndRepairWorkWithoutAnyGuestRuntime()throws Exception{
        File root=temp.newFolder();fixture(root);var engine=engine(root,null);var control=new BackupControl(null);
        engine.prepare("startup-one",control);engine.healthy("startup-one",control);Map<String,Object> saved=rows(engine).get(0);
        engine.create("settings.yaml",control);assertEquals("{}\n",Files.readString(new File(dsh(root),"settings.yaml").toPath()));untouched(root);
        engine.restore((String)saved.get("slot"),(String)saved.get("id"),control);assertEquals("original-4",Files.readString(new File(dsh(root),"settings.yaml").toPath()));untouched(root);
        assertTrue(new File(root,ConfigurationSnapshots.STORE).isDirectory());assertFalse(new File(root,"linux/ubuntu/bin/bash").exists());
    }
    @Test public void healthyCheckpointMustMatchThePreStartCapture()throws Exception{
        File root=temp.newFolder();fixture(root);var engine=engine(root,null);engine.prepare("old-generation",new BackupControl(null));
        assertThrows(IOException.class,()->engine.healthy("new-generation",new BackupControl(null)));
        put(dsh(root),"settings.yaml","changed after startup capture");assertThrows(IOException.class,()->engine.healthy("old-generation",new BackupControl(null)));
        assertTrue(rows(engine).isEmpty());untouched(root);
    }
    @Test public void resettingOnlyWebPreservesSharedSettingsAndConversations()throws Exception{
        File root=temp.newFolder();fixture(root);var engine=engine(root,null);engine.create("web",new BackupControl(null));
        assertEquals("original-4",Files.readString(new File(dsh(root),"settings.yaml").toPath()));assertEquals("original-5",Files.readString(new File(dsh(root),"cordis.patch.yml").toPath()));
        var data=BackupJson.read(Files.readAllBytes(new File(dsh(root),ConfigurationSnapshots.FILES.get(0)).toPath()),16384);assertEquals(Collections.emptyMap(),data.get("dependencies"));untouched(root);
    }
    @Test public void selectedOldestBeforeSnapshotIsPinnedDuringNewSafetyCapture()throws Exception{
        File root=temp.newFolder();fixture(root);var engine=engine(root,null);Map<String,Object> oldest=null;
        for(int i=0;i<3;i++){put(dsh(root),"settings.yaml","before-"+i);engine.before(new BackupControl(null));if(i==0)oldest=rows(engine).get(0);Thread.sleep(2);}
        put(dsh(root),"settings.yaml","current config");engine.restore((String)oldest.get("slot"),(String)oldest.get("id"),new BackupControl(null));
        assertEquals("before-0",Files.readString(new File(dsh(root),"settings.yaml").toPath()));untouched(root);
    }
    static String legacy(File root,String slot,byte[] settings)throws Exception{
        Map<String,Object> files=new LinkedHashMap<>();for(int i=0;i<ConfigurationSnapshots.FILES.size();i++){
            byte[] bytes=i==4?settings:("legacy-"+i).getBytes(java.nio.charset.StandardCharsets.UTF_8);
            files.put(ConfigurationSnapshots.FILES.get(i),Map.of("data",Base64.getEncoder().encodeToString(bytes),"sha256",BackupArchive.hex(BackupArchive.sha().digest(bytes))));
        }
        String id="a".repeat(32);Map<String,Object> snapshot=Map.of("version",1,"id",id,"created",1L,"dshVersion","0.1.5-rc.2","files",files);
        put(dsh(root),"dsha-startup-checkpoints/"+slot+".json",new com.google.gson.Gson().toJson(snapshot));return id;
    }
    @Test public void legacyBase64CheckpointLargerThanOneMetadataRecordStillRestores()throws Exception{
        File root=temp.newFolder();fixture(root);byte[] body=new byte[100000];Arrays.fill(body,(byte)'x');String id=legacy(root,"healthy-1",body);
        var engine=engine(root,null);assertTrue(rows(engine).stream().anyMatch(row->row.get("slot").equals("legacy-healthy-1")));
        engine.restore("legacy-healthy-1",id,new BackupControl(null));assertArrayEquals(body,Files.readAllBytes(new File(dsh(root),"settings.yaml").toPath()));untouched(root);
    }
    @Test public void finalizedLegacyRepairDoesNotReplayOverLaterConfiguration()throws Exception{
        File root=temp.newFolder();fixture(root);legacy(root,"pending","legacy-settings".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        var failing=engine(root,boundary->{if(boundary.equals("finalized"))throw new IOException("INJECTED_AFTER_COMMIT");});
        assertThrows(IOException.class,()->failing.recover(new BackupControl(null)));assertEquals("legacy-settings",Files.readString(new File(dsh(root),"settings.yaml").toPath()));
        put(dsh(root),"settings.yaml","newer user configuration");engine(root,null).recover(new BackupControl(null));
        assertEquals("newer user configuration",Files.readString(new File(dsh(root),"settings.yaml").toPath()));assertFalse(engine(root,null).pending());untouched(root);
    }
    @Test public void staleMarkerWithoutJournalNeverInventsConfiguration()throws Exception{
        File root=temp.newFolder();put(root,"startup-config-repair.pending","");var engine=engine(root,null);
        assertEquals("RECOVERY_MARKER_RETAINED",engine.recover(new BackupControl(null)));assertFalse(dsh(root).exists());assertFalse(engine.pending());
        assertEquals(1,new JvmBackupFileSystem().list(new File(root,ConfigurationSnapshots.STORE+"/retained-markers")).size());
    }
    @Test public void corruptedPrivateCopyAndPathDirectiveRejectBeforeActiveWrites()throws Exception{
        File root=temp.newFolder();fixture(root);var engine=engine(root,null);engine.before(new BackupControl(null));Map<String,Object> row=rows(engine).get(0);
        put(root,ConfigurationSnapshots.STORE+"/"+row.get("id")+"/4","tampered");
        assertThrows(IOException.class,()->engine.restore((String)row.get("slot"),(String)row.get("id"),new BackupControl(null)));
        assertThrows(IOException.class,()->engine.create("../../outside",new BackupControl(null)));assertEquals("original-4",Files.readString(new File(dsh(root),"settings.yaml").toPath()));untouched(root);
    }
    @Test public void realKillDuringConfigurationCommitAndRollbackConverges()throws Exception{
        File root=temp.newFolder();fixture(root);kill(root,"new-config-4",false);assertTrue(engine(root,null).pending());
        kill(root,"rollback-new-config-4",true);engine(root,null).recover(new BackupControl(null));engine(root,null).recover(new BackupControl(null));
        assertEquals("original-4",Files.readString(new File(dsh(root),"settings.yaml").toPath()));assertFalse(engine(root,null).pending());untouched(root);
    }
    private void kill(File root,String boundary,boolean recover)throws Exception{
        String binary=new File(System.getProperty("java.home"),"bin/"+(System.getProperty("os.name").startsWith("Windows")?"java.exe":"java")).getPath();Set<String> classpath=new LinkedHashSet<>();
        for(Class<?> type:List.of(ConfigurationSnapshotsTest.class,ConfigurationSnapshots.class,com.google.gson.stream.JsonReader.class,org.junit.Assert.class))classpath.add(new File(type.getProtectionDomain().getCodeSource().getLocation().toURI()).getPath());
        Process child=new ProcessBuilder(binary,"-cp",String.join(File.pathSeparator,classpath),ConfigurationCrashProcess.class.getName(),root.getPath(),boundary,Boolean.toString(recover))
                .redirectErrorStream(true).redirectOutput(new File(root,"child-"+boundary+".log")).start();
        File marker=new File(root,"reached-"+boundary);long deadline=System.currentTimeMillis()+15000;
        try{while(!marker.exists()&&child.isAlive()&&System.currentTimeMillis()<deadline)Thread.sleep(20);assertTrue("未到强杀边界："+boundary,marker.exists());}
        finally{child.destroyForcibly();assertTrue(child.waitFor(10,java.util.concurrent.TimeUnit.SECONDS));}
    }
}
