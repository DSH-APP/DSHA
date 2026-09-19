package com.deepseekharness.app.backup;

import org.junit.Test;
import java.io.*;
import java.util.*;
import static org.junit.Assert.*;

public class BackupArchiveTest {
    static Map<String,Object> summary(){Map<String,Object> m=new LinkedHashMap<>();m.put("operation","EXPORT");m.put("integrity","QUIESCENT");
        m.put("createdAt",1L);m.put("appVersion","test");m.put("runtime","test");m.put("dataFormat","dsh-v3");m.put("sensitivePolicy","ENCRYPTED");
        m.put("plugins",Collections.emptyMap());m.put("roots",Collections.singletonList(new LinkedHashMap<>(Map.of("id","dsh","scope","application"))));return m;}
    static byte[] archive()throws Exception{ByteArrayOutputStream bytes=new ByteArrayOutputStream();var writer=new BackupArchive.Writer(bytes,new BackupControl(null));
        byte[] file="对话字节\n".getBytes(java.nio.charset.StandardCharsets.UTF_8);String hash=BackupArchive.hex(BackupArchive.sha().digest(file));
        writer.add(BackupArchive.Record.of("dsh","","DIRECTORY","application",0,""),null);
        writer.add(BackupArchive.Record.of("dsh","sessions/a.jsonl","FILE","sessions",file.length,hash),new ByteArrayInputStream(file));writer.finish(summary());return bytes.toByteArray();}
    @Test public void roundtripStreamsExactBytesAndSummary()throws Exception{
        ByteArrayOutputStream payload=new ByteArrayOutputStream();var m=BackupArchive.read(new ByteArrayInputStream(archive()),new BackupArchive.Visitor(){
            public OutputStream payload(int id,BackupArchive.Record r){return r.kind.equals("FILE")?payload:null;}},new BackupControl(null));
        assertEquals("对话字节\n",payload.toString("UTF-8"));assertEquals(2L,m.get("entries"));
    }
    @Test public void truncationAtEveryBoundaryNeverPasses()throws Exception{
        byte[] complete=archive();for(int cut=0;cut<complete.length;cut++){final int length=cut;
            assertThrows(IOException.class,()->BackupArchive.read(new ByteArrayInputStream(Arrays.copyOf(complete,length)),null,new BackupControl(null)));}
    }
    @Test public void corruptedPayloadAndTrailingBytesAreRejected()throws Exception{
        byte[] bytes=archive();bytes[bytes.length-1]^=1;assertThrows(IOException.class,()->BackupArchive.read(new ByteArrayInputStream(bytes),null,new BackupControl(null)));
        byte[] extra=Arrays.copyOf(archive(),archive().length+1);assertThrows(IOException.class,()->BackupArchive.read(new ByteArrayInputStream(extra),null,new BackupControl(null)));
    }
    @Test public void pathsDuplicatesAndTypeConflictsAreRejected()throws Exception{
        for(String path:new String[]{"../escape","/absolute","a/../b","a\\b","C:/bad","a\0b","a//b"})
            assertThrows(IOException.class,()->BackupArchive.Record.of("dsh",path,"DIRECTORY","application",0,""));
        var writer=new BackupArchive.Writer(new ByteArrayOutputStream(),new BackupControl(null));writer.add(BackupArchive.Record.of("dsh","A","DIRECTORY","application",0,""),null);
        assertThrows(IOException.class,()->writer.add(BackupArchive.Record.of("dsh","a","DIRECTORY","application",0,""),null));
        var rootFile=new BackupArchive.Writer(new ByteArrayOutputStream(),new BackupControl(null));
        rootFile.add(BackupArchive.Record.of("dsh","","MISSING","application",0,""),null);
        assertThrows(IOException.class,()->rootFile.add(BackupArchive.Record.of("dsh","child","DIRECTORY","application",0,""),null));
    }
    @Test public void partialMustNotClaimFullAndUnknownScopesFail()throws Exception{
        var writer=new BackupArchive.Writer(new ByteArrayOutputStream(),new BackupControl(null));writer.add(BackupArchive.Record.of("dsh","","UNREADABLE","application",0,""),null);
        assertThrows(IOException.class,()->writer.finish(summary()));assertThrows(IOException.class,()->BackupArchive.Record.of("dsh","","DIRECTORY","unknown",0,""));
    }
    @Test public void duplicateJsonFieldsAndDeepInputAreRejected()throws Exception{
        assertThrows(IOException.class,()->BackupJson.read("{\"scope\":\"sessions\",\"scope\":\"full\"}".getBytes(),1000));
        assertThrows(IOException.class,()->BackupJson.read(("{\"a\":"+"[".repeat(66)+"0"+"]".repeat(66)+"}").getBytes(),1000));
    }
    @Test public void narrowlyDeclaredRootCannotSmuggleAnotherScope()throws Exception{
        var writer=new BackupArchive.Writer(new ByteArrayOutputStream(),new BackupControl(null));
        writer.add(BackupArchive.Record.of("dsh","","DIRECTORY","sessions",0,""),null);
        writer.add(BackupArchive.Record.of("dsh","settings.yaml","FILE","settings",0,BackupArchive.hex(BackupArchive.sha().digest(new byte[0]))),new ByteArrayInputStream(new byte[0]));
        var information=summary();information.put("roots",List.of(Map.of("id","dsh","scope","sessions")));
        assertThrows(IOException.class,()->writer.finish(information));
    }
}
