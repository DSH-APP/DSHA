package com.deepseekharness.app.backup;

import org.junit.*;
import org.junit.rules.TemporaryFolder;
import java.io.*;
import java.nio.file.*;
import java.util.*;
import static org.junit.Assert.*;

public class NativeConfigurationResetTest {
    @Rule public TemporaryFolder temporary=new TemporaryFolder();
    static final JvmBackupFileSystem FS=new JvmBackupFileSystem();
    static final String SETTINGS=UserDataLayout.LEGACY+"/settings.yaml",ENV="linux/ubuntu/root/project/.env";
    static void put(File root,String path,String text)throws Exception{File file=new File(root,path);Files.createDirectories(file.getParentFile().toPath());Files.writeString(file.toPath(),text);}
    static String read(File root,String path)throws Exception{return Files.readString(new File(root,path).toPath());}
    static void fixture(File root)throws Exception{
        put(root,SETTINGS,"model: original\n");put(root,ENV,"ORIGINAL=value\n");
        put(root,"native-state.json","{\"credential\":\"device-local-reference\"}");
        put(root,UserDataLayout.LEGACY+"/sessions/keep","conversation bytes");put(root,"linux/ubuntu/root/project/keep","project bytes");
    }
    static HostDataTransaction.Settings settings(File root){return new HostDataTransaction.Settings(){
        public Map<String,Object> current()throws IOException{return BackupJson.read(FS.small(new File(root,"native-state.json"),4096),4096);}
        public void apply(Map<String,Object> values)throws IOException{FS.atomic(root,"native-state.json",BackupJson.write(values,4096));}
    };}
    static File reset(File root,HostDataTransaction.Fault fault)throws IOException{return NativeConfigurationReset.reset(FS,root,"/root/project","DEEPSEEK_API_KEY='test-only'\n".getBytes(java.nio.charset.StandardCharsets.UTF_8),settings(root),fault,new BackupControl(null));}
    static File operation(File root)throws IOException{List<String> items=FS.list(new File(root,"host-backup-operations"));assertEquals(1,items.size());return new File(root,"host-backup-operations/"+items.get(0));}
    static void originals(File root)throws Exception{
        assertEquals("model: original\n",read(root,SETTINGS));assertEquals("ORIGINAL=value\n",read(root,ENV));
        assertEquals("conversation bytes",read(root,UserDataLayout.LEGACY+"/sessions/keep"));assertEquals("project bytes",read(root,"linux/ubuntu/root/project/keep"));
        assertEquals(Map.of("credential","device-local-reference"),settings(root).current());
    }
    @Test public void resetUsesActualWorkspaceAndRetainsOriginalsWithoutGuest()throws Exception{
        File root=temporary.newFolder();fixture(root);File operation=reset(root,null);
        assertEquals("{}\n",read(root,SETTINGS));assertEquals("DEEPSEEK_API_KEY='test-only'\n",read(root,ENV));
        assertEquals("ORIGINAL=value\n",read(operation,"previous/environment"));assertEquals("model: original\n",read(operation,"previous/settings"));
        assertFalse(new File(root,"linux/ubuntu/root/root/project").exists());assertFalse(new File(root,"linux/ubuntu/bin/bash").exists());
        assertEquals("conversation bytes",read(root,UserDataLayout.LEGACY+"/sessions/keep"));assertEquals(Map.of("credential","device-local-reference"),settings(root).current());
        put(root,SETTINGS,"later setting");put(root,ENV,"later environment");NativeConfigurationReset.recover(FS,root,operation,settings(root),null);
        assertEquals("later setting",read(root,SETTINGS));assertEquals("later environment",read(root,ENV));
    }
    @Test public void stableHomeKeepsUnusedLegacySettings()throws Exception{
        File root=temporary.newFolder();fixture(root);put(root,UserDataLayout.STABLE+"/settings.yaml","stable original");new UserDataLayout(FS,root).choose(UserDataLayout.Home.STABLE);
        File operation=reset(root,null);assertEquals("{}\n",read(root,UserDataLayout.STABLE+"/settings.yaml"));assertEquals("model: original\n",read(root,SETTINGS));assertEquals("stable original",read(operation,"previous/settings"));
    }
    @Test public void everyFileAndSettingsFailureRestoresOldBytes()throws Exception{
        for(String phase:List.of("old-settings","new-settings","old-environment","new-environment","settings-write","validation-complete")){
            File root=temporary.newFolder();fixture(root);
            try{reset(root,name->{if(name.equals(phase))throw new IOException("injected");});fail(phase);}catch(IOException expected){assertEquals("injected",expected.getMessage());}
            originals(root);assertTrue(HostPendingTransactions.pending(FS,root).isEmpty());
        }
    }
    @Test public void invalidWorkspaceAndTamperedMappingCannotMoveFiles()throws Exception{
        for(String workspace:List.of("/sdcard/project","/root/../etc","/etc","/")){
            File root=temporary.newFolder();fixture(root);
            try{NativeConfigurationReset.reset(FS,root,workspace,new byte[0],settings(root),null,new BackupControl(null));fail(workspace);}catch(IOException expected){}
            originals(root);
        }
        File root=temporary.newFolder();fixture(root);File operation=reset(root,null);
        put(operation,"config-reset.json",new String(BackupJson.write(Map.of("version",1L,"id",operation.getName(),"home","LEGACY","workspace","/etc"),4096),java.nio.charset.StandardCharsets.UTF_8));
        String before=read(root,ENV);try{NativeConfigurationReset.recover(FS,root,operation,settings(root),null);fail();}catch(IOException expected){}
        assertEquals(before,read(root,ENV));
    }
    @Test public void realDeathDuringResetAndRollbackConverges()throws Exception{
        for(String phase:List.of("old-settings","old-environment","new-environment","settings-write")){
            File root=temporary.newFolder();fixture(root);kill(root,phase,false);
            if(phase.equals("new-environment"))kill(root,"rollback-new-environment",true);
            NativeConfigurationReset.recover(FS,root,operation(root),settings(root),null);originals(root);
            assertTrue(HostPendingTransactions.pending(FS,root).isEmpty());
        }
    }
    private void kill(File root,String phase,boolean recover)throws Exception{
        String binary=new File(System.getProperty("java.home"),"bin/"+(System.getProperty("os.name").startsWith("Windows")?"java.exe":"java")).getPath();
        Set<String> classes=new LinkedHashSet<>();for(Class<?> type:List.of(NativeConfigurationResetTest.class,NativeConfigurationReset.class,com.google.gson.stream.JsonReader.class,org.junit.Assert.class))classes.add(new File(type.getProtectionDomain().getCodeSource().getLocation().toURI()).getPath());
        Process child=new ProcessBuilder(binary,"-cp",String.join(File.pathSeparator,classes),ConfigResetCrashProcess.class.getName(),root.getPath(),phase,String.valueOf(recover)).redirectErrorStream(true).redirectOutput(new File(root,"child-"+phase+".log")).start();
        long deadline=System.currentTimeMillis()+15000;File reached=new File(root,"reached-"+phase);
        while(!reached.exists()&&child.isAlive()&&System.currentTimeMillis()<deadline)Thread.sleep(20);
        try{assertTrue("Did not reach "+phase,reached.isFile());}finally{child.destroyForcibly();assertTrue(child.waitFor(10,java.util.concurrent.TimeUnit.SECONDS));}
    }
}
