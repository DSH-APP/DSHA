package com.deepseekharness.app.backup;

import org.junit.*;
import org.junit.rules.TemporaryFolder;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import static org.junit.Assert.*;

/** 依据历史代码建立的最小合成样本，不冒充真实用户升级数据。 */
public class LegacyBackupImporterTest {
    @Rule public TemporaryFolder temporary=new TemporaryFolder();
    private final BackupFileSystem fs=new JvmBackupFileSystem();
    private byte[] bundle(Map<String,byte[]> files,int version,boolean corrupt)throws Exception{
        Map<String,Object> inventory=new LinkedHashMap<>();List<byte[]> entries=new ArrayList<>();Set<String> dirs=new TreeSet<>();
        for(String path:files.keySet()){String parent=path;while(parent.contains("/")){parent=parent.substring(0,parent.lastIndexOf('/'));dirs.add(parent);}}
        for(String dir:dirs){inventory.put(dir,Map.of("directory",true));entries.add(LegacyTarReaderTest.tar(dir,'5',new byte[0]));}
        for(var row:files.entrySet()){inventory.put(row.getKey(),Map.of("size",(long)row.getValue().length,"sha256",BackupArchive.hex(BackupArchive.sha().digest(row.getValue()))));entries.add(LegacyTarReaderTest.tar(row.getKey(),'0',row.getValue()));}
        if(version>0){if(corrupt)inventory.put(".dsh/sessions/a",Map.of("size",3L,"sha256","0".repeat(64)));
            Map<String,Object> manifest=new LinkedHashMap<>();manifest.put("formatVersion",version);manifest.put("scope","full");manifest.put("appVersion","synthetic");if(version>=3)manifest.put("inventory",inventory);if(version==4)manifest.put("pluginDependencyGraph",Map.of());
            entries.add(LegacyTarReaderTest.tar(".dsha-backup-manifest.json",'0',BackupJson.write(manifest,BackupLimits.MANIFEST)));}
        ByteArrayOutputStream bytes=new ByteArrayOutputStream();for(byte[] entry:entries)bytes.write(entry,0,entry.length-1024);bytes.write(new byte[1024]);return LegacyTarReaderTest.gzip(bytes.toByteArray());
    }
    @Test public void syntheticVersionsOneThroughFourConvertWithoutRuntime()throws Exception{
        for(int version=0;version<=4;version++){
            File task=temporary.newFolder(),input=new File(task,"input"),output=new File(task,"converted");Files.write(input.toPath(),bundle(Map.of(".dsh/sessions/a","old conversation".getBytes(StandardCharsets.UTF_8)),version,false));
            Map<String,Object> result=new LegacyBackupImporter(fs,task).convert(input,output,"DSHA-backup-synthetic.tar.gz",new BackupControl(null));
            assertTrue(Boolean.TRUE.equals(result.get("legacyConfirmationRequired")));ByteArrayOutputStream payload=new ByteArrayOutputStream();
            try(InputStream in=new FileInputStream(output)){BackupArchive.read(in,new BackupArchive.Visitor(){public OutputStream payload(int n,BackupArchive.Record record){return record.kind.equals("FILE")?payload:null;}},new BackupControl(null));}
            assertEquals("old conversation",payload.toString("UTF-8"));
        }
    }
    @Test public void missingInventoryAndUnknownScopesFailClosed()throws Exception{
        File task=temporary.newFolder(),input=new File(task,"input");Files.write(input.toPath(),bundle(Map.of(".dsh/sessions/a",new byte[3]),4,true));
        assertThrows(IOException.class,()->new LegacyBackupImporter(fs,task).convert(input,new File(task,"bad"),"DSHA-backup-test.tar.gz",new BackupControl(null)));
        File other=temporary.newFolder(),raw=new File(other,"raw");Files.write(raw.toPath(),bundle(Map.of(".dsh/sessions/a",new byte[3]),0,false));
        assertThrows(IOException.class,()->new LegacyBackupImporter(fs,other).convert(raw,new File(other,"out"),"unknown.tar.gz",new BackupControl(null)));
    }
}
