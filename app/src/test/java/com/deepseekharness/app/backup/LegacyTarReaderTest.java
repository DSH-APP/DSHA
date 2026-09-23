package com.deepseekharness.app.backup;

import org.junit.Test;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.zip.GZIPOutputStream;
import static org.junit.Assert.*;

public class LegacyTarReaderTest {
    static byte[] tar(String path,char type,byte[] body)throws Exception{
        byte[] header=new byte[512];byte[] name=path.getBytes(StandardCharsets.UTF_8);System.arraycopy(name,0,header,0,name.length);
        put(header,100,"0000644\0");put(header,124,String.format("%011o\0",body.length));header[156]=(byte)type;Arrays.fill(header,148,156,(byte)' ');
        int checksum=0;for(byte b:header)checksum+=b&255;put(header,148,String.format("%06o\0 ",checksum));
        ByteArrayOutputStream out=new ByteArrayOutputStream();out.write(header);out.write(body);out.write(new byte[(512-body.length%512)%512]);out.write(new byte[1024]);return out.toByteArray();
    }
    static void put(byte[] out,int at,String text){byte[] bytes=text.getBytes(StandardCharsets.US_ASCII);System.arraycopy(bytes,0,out,at,bytes.length);}
    static byte[] gzip(byte[] raw)throws Exception{ByteArrayOutputStream out=new ByteArrayOutputStream();try(var zip=new GZIPOutputStream(out)){zip.write(raw);}return out.toByteArray();}
    @Test public void readsSyntheticLegacyPayloadOnHost()throws Exception{
        byte[] body="对话原件".getBytes(StandardCharsets.UTF_8),input=gzip(tar(".dsh/sessions/a",'0',body));ByteArrayOutputStream copied=new ByteArrayOutputStream();
        LegacyTarReader.read(new ByteArrayInputStream(input),(entry,data)->{assertEquals(".dsh/sessions/a",entry.path);assertEquals(0644,entry.mode);data.transferTo(copied);},new BackupControl(null));assertArrayEquals(body,copied.toByteArray());
    }
    @Test public void crcTruncationAndTrailingGarbageReject()throws Exception{
        byte[] bytes=gzip(tar(".dsh/settings.yaml",'0',new byte[3]));byte[] changed=bytes.clone();changed[changed.length-8]^=1;
        for(byte[] bad:List.of(changed,Arrays.copyOf(bytes,bytes.length-1),Arrays.copyOf(bytes,bytes.length+1)))
            assertThrows(IOException.class,()->LegacyTarReader.read(new ByteArrayInputStream(bad),(e,in)->{},new BackupControl(null)));
    }
    @Test public void absoluteTraversalSpecialAndBadChecksumReject()throws Exception{
        for(byte[] bad:List.of(tar("../escape",'0',new byte[0]),tar("/root/escape",'0',new byte[0]),tar("device",'3',new byte[0])))
            assertThrows(IOException.class,()->LegacyTarReader.read(new ByteArrayInputStream(bad),(e,in)->{},new BackupControl(null)));
        byte[] bad=tar("good",'0',new byte[0]);bad[0]^=1;assertThrows(IOException.class,()->LegacyTarReader.read(new ByteArrayInputStream(bad),(e,in)->{},new BackupControl(null)));
    }
    @Test public void failedHeaderAndVisitorCloseOwnedInflaterAndInput()throws Exception{
        for(byte[] source:List.of(new byte[]{31,(byte)139,8},gzip(tar("data",'0',new byte[3])))){
            boolean[] closed={false};InputStream input=new ByteArrayInputStream(source){public void close(){closed[0]=true;}};
            assertThrows(IOException.class,()->LegacyTarReader.read(input,(entry,data)->{throw new IOException("INJECTED_VISITOR");},new BackupControl(null)));
            assertTrue(closed[0]);
        }
    }
}
