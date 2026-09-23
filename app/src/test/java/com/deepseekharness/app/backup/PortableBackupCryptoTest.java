package com.deepseekharness.app.backup;

import org.junit.Test;
import java.io.*;
import java.nio.*;
import java.util.*;
import static org.junit.Assert.*;

public class PortableBackupCryptoTest {
    private final char[] password="Correct horse 备份 2026".toCharArray();
    private byte[] encrypted()throws Exception{ByteArrayOutputStream out=new ByteArrayOutputStream();PortableBackupCrypto.encrypt(new ByteArrayInputStream(BackupArchiveTest.archive()),out,password,new BackupControl(null));return out.toByteArray();}
    @Test public void roundtripAndUniqueSaltNonce()throws Exception{
        byte[] first=encrypted(),second=encrypted();assertFalse(Arrays.equals(first,second));
        ByteArrayOutputStream plain=new ByteArrayOutputStream();PortableBackupCrypto.decrypt(new ByteArrayInputStream(first),plain,password,new BackupControl(null));
        assertArrayEquals(BackupArchiveTest.archive(),plain.toByteArray());
    }
    @Test public void wrongPasswordTamperingAndTruncationNeverAuthenticate()throws Exception{
        byte[] bytes=encrypted();assertThrows(IOException.class,()->PortableBackupCrypto.decrypt(new ByteArrayInputStream(bytes),new ByteArrayOutputStream(),"wrong password!".toCharArray(),new BackupControl(null)));
        byte[] altered=bytes.clone();altered[altered.length-22]^=1;
        assertThrows(IOException.class,()->PortableBackupCrypto.decrypt(new ByteArrayInputStream(altered),new ByteArrayOutputStream(),password,new BackupControl(null)));
        byte[] cut=Arrays.copyOf(bytes,bytes.length-1);
        assertThrows(IOException.class,()->PortableBackupCrypto.decrypt(new ByteArrayInputStream(cut),new ByteArrayOutputStream(),password,new BackupControl(null)));
    }
    @Test public void maliciousKdfBoundsRejectBeforeDeriving()throws Exception{
        byte[] bytes=new byte[48];System.arraycopy(PortableBackupCrypto.MAGIC,0,bytes,0,8);ByteBuffer.wrap(bytes).putInt(8,5).putInt(12,Integer.MAX_VALUE).putInt(16,1);
        assertThrows(IOException.class,()->PortableBackupCrypto.decrypt(new ByteArrayInputStream(bytes),new ByteArrayOutputStream(),password,new BackupControl(null)));
    }
    @Test public void cancellationStopsBeforeWritingPayload()throws Exception{
        BackupControl control=new BackupControl(null);control.cancel();assertThrows(InterruptedIOException.class,()->PortableBackupCrypto.encrypt(new ByteArrayInputStream(new byte[9]),new ByteArrayOutputStream(),password,control));
    }
    @Test public void independentOpenSslFixedVectorAndJceInteroperate()throws Exception{
        Map<String,Object> vector;try(InputStream in=getClass().getResourceAsStream("/backups/v5-aesgcm-vector.json")){assertNotNull(in);vector=BackupJson.read(in.readAllBytes(),16384);}
        var hex=java.util.HexFormat.of();byte[] encoded=hex.parseHex((String)vector.get("archiveHex")),expected=hex.parseHex((String)vector.get("plaintextHex"));
        char[] fixturePassword=((String)vector.get("password")).toCharArray();ByteArrayOutputStream plain=new ByteArrayOutputStream();
        PortableBackupCrypto.decrypt(new ByteArrayInputStream(encoded),plain,fixturePassword,new BackupControl(null));assertArrayEquals(expected,plain.toByteArray());
        byte[] header=hex.parseHex((String)vector.get("headerHex"));var specification=new javax.crypto.spec.PBEKeySpec(fixturePassword,Arrays.copyOfRange(header,20,36),600000,256);
        byte[] key=javax.crypto.SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(specification).getEncoded();assertEquals(vector.get("derivedKeyHex"),hex.formatHex(key));
        var cipher=javax.crypto.Cipher.getInstance("AES/GCM/NoPadding");cipher.init(javax.crypto.Cipher.DECRYPT_MODE,new javax.crypto.spec.SecretKeySpec(key,"AES"),new javax.crypto.spec.GCMParameterSpec(128,Arrays.copyOfRange(header,36,48)));cipher.updateAAD(header);
        assertArrayEquals(expected,cipher.doFinal(Arrays.copyOfRange(encoded,48,encoded.length)));specification.clearPassword();Arrays.fill(key,(byte)0);Arrays.fill(fixturePassword,'\0');
    }
}
