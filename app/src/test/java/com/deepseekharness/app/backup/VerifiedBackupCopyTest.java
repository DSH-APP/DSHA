package com.deepseekharness.app.backup;

import org.junit.*;
import org.junit.rules.TemporaryFolder;
import java.io.*;
import java.nio.file.*;
import java.util.*;
import static org.junit.Assert.*;

/** 本机验证记录的重读测试；实际 AEAD 与归档完整预检另由往返测试覆盖。 */
public class VerifiedBackupCopyTest {
    @Rule public TemporaryFolder temporary=new TemporaryFolder();
    private final JvmBackupFileSystem fs=new JvmBackupFileSystem();
    private File fixture(File operations,String quality,Map<String,Object> extras)throws Exception{
        File operation=new File(operations,UUID.randomUUID().toString());fs.directory(operation);File file=new File(operation,"portable.dshbak");
        try(OutputStream output=fs.create(file)){PortableBackupCrypto.encrypt(new ByteArrayInputStream("owned verified bytes".getBytes()),output,"test-only-password".toCharArray(),new BackupControl(null));}
        String hash;try(InputStream in=fs.read(file,fs.stat(file))){hash=BackupArchive.digest(in,new BackupControl(null));}
        Map<String,Object> record=new LinkedHashMap<>(Map.of("encryptedSha256",hash,"encryptedBytes",file.length(),"entries",1L,"createdAt",7L,"integrity",quality,"requestedScope","projects"));record.putAll(extras);
        try(OutputStream out=fs.create(new File(operation,"verified.json"))){out.write(BackupJson.write(record,4096));}return operation;
    }
    @Test public void rereadCopyRetainsPasswordCiphertextAndScope()throws Exception{
        File operations=temporary.newFolder(),operation=fixture(operations,"QUIESCENT",Map.of("plugins",Map.of()));var copy=VerifiedBackupCopy.inspect(fs,operations,operation.getName());
        byte[] before=Files.readAllBytes(copy.artifact.toPath());copy.verify(fs,new BackupControl(null));assertArrayEquals(before,Files.readAllBytes(copy.artifact.toPath()));
        assertEquals("projects",copy.scope);assertEquals("COMPLETE",copy.result(true));assertEquals("WRITTEN_UNVERIFIED",copy.result(false));
    }
    @Test public void sameSizeCorruptionAndUnknownIdentifiersAreRejected()throws Exception{
        File operations=temporary.newFolder(),operation=fixture(operations,"QUIESCENT",Map.of());var copy=VerifiedBackupCopy.inspect(fs,operations,operation.getName());
        byte[] data=Files.readAllBytes(copy.artifact.toPath());data[data.length-1]^=1;Files.write(copy.artifact.toPath(),data);
        try{copy.verify(fs,new BackupControl(null));fail();}catch(IOException expected){assertEquals("VERIFIED_COPY_CHANGED",expected.getMessage());}
        for(String id:List.of("../elsewhere","------------------------------------",operation.getName()+"/.."))try{VerifiedBackupCopy.inspect(fs,operations,id);fail(id);}catch(IOException expected){}
    }
    @Test public void reexportCannotUpgradeRescueOrPluginWarningToComplete()throws Exception{
        File operations=temporary.newFolder();File partial=fixture(operations,"PARTIAL",Map.of()),best=fixture(operations,"BEST_EFFORT",Map.of()),warning=fixture(operations,"QUIESCENT",Map.of("plugins",Map.of("complete",false)));
        assertEquals("PARTIAL_RESCUE",VerifiedBackupCopy.inspect(fs,operations,partial.getName()).result(true));
        assertEquals("BEST_EFFORT_RESCUE",VerifiedBackupCopy.inspect(fs,operations,best.getName()).result(true));
        assertEquals("DATA_SAVED_PLUGIN_WARNINGS",VerifiedBackupCopy.inspect(fs,operations,warning.getName()).result(true));
    }
    @Test public void earlierPrivateCopiesUseDeclaredRootsAndRejectUnknownScope()throws Exception{
        File operations=temporary.newFolder(),operation=fixture(operations,"QUIESCENT",Map.of());
        File record=new File(operation,"verified.json");Map<String,Object> data=BackupJson.read(fs.small(record,4096),4096);data.remove("requestedScope");
        data.put("roots",List.of(Map.of("scope","sessions")));Files.write(record.toPath(),BackupJson.write(data,4096));
        assertEquals("sessions",VerifiedBackupCopy.inspect(fs,operations,operation.getName()).scope);
        data.put("roots",List.of(Map.of("scope","unknown")));Files.write(record.toPath(),BackupJson.write(data,4096));
        try{VerifiedBackupCopy.inspect(fs,operations,operation.getName());fail();}catch(IOException expected){assertEquals("VERIFIED_COPY_SCOPE",expected.getMessage());}
    }
}
