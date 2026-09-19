package com.deepseekharness.app.backup;

import java.io.*;
import java.nio.*;
import java.security.SecureRandom;
import java.util.Arrays;
import org.bouncycastle.crypto.*;
import org.bouncycastle.crypto.digests.SHA256Digest;
import org.bouncycastle.crypto.engines.AESEngine;
import org.bouncycastle.crypto.generators.PKCS5S2ParametersGenerator;
import org.bouncycastle.crypto.modes.GCMBlockCipher;
import org.bouncycastle.crypto.params.*;

/** 标准 PBKDF2-HMAC-SHA256 与 AES-256-GCM；明文只能进入私有隔离文件，doFinal 成功后才可预检。 */
public final class PortableBackupCrypto {
    static final byte[] MAGIC={'D','S','H','A','B','A','K','5'};
    public static final int ITERATIONS=600_000, MAX_ITERATIONS=1_200_000, HEADER_SIZE=48;
    private PortableBackupCrypto() { }
    public static void encrypt(InputStream plain,OutputStream target,char[] password,BackupControl control)throws IOException{
        byte[] salt=new byte[16],nonce=new byte[12];SecureRandom random=new SecureRandom();random.nextBytes(salt);random.nextBytes(nonce);
        byte[] header=ByteBuffer.allocate(HEADER_SIZE).order(ByteOrder.BIG_ENDIAN).put(MAGIC).putInt(5).putInt(ITERATIONS).putInt(1).put(salt).put(nonce).array();
        target.write(header);crypt(true,plain,target,password,header,control);
    }
    public static void decrypt(InputStream source,OutputStream isolatedPlaintext,char[] password,BackupControl control)throws IOException{
        byte[] header=new byte[HEADER_SIZE];new DataInputStream(source).readFully(header);
        ByteBuffer fields=ByteBuffer.wrap(header).order(ByteOrder.BIG_ENDIAN);byte[] magic=new byte[8];fields.get(magic);
        int version=fields.getInt(),iterations=fields.getInt(),algorithm=fields.getInt();
        if(!Arrays.equals(magic,MAGIC)||version!=5||algorithm!=1||iterations<ITERATIONS||iterations>MAX_ITERATIONS)
            throw new IOException("CRYPTO_HEADER");
        crypt(false,source,isolatedPlaintext,password,header,control);
    }
    private static void crypt(boolean encrypt,InputStream input,OutputStream output,char[] password,byte[] header,BackupControl control)throws IOException{
        if(password==null||password.length<12||password.length>1024)throw new IOException("PASSWORD_LENGTH");
        byte[] encoded=PBEParametersGenerator.PKCS5PasswordToUTF8Bytes(password),key=null;
        byte[] in=new byte[65536],out=new byte[65568];
        try {
            control.check();ByteBuffer fields=ByteBuffer.wrap(header).order(ByteOrder.BIG_ENDIAN);int iterations=fields.getInt(12);
            PKCS5S2ParametersGenerator kdf=new PKCS5S2ParametersGenerator(new SHA256Digest());
            kdf.init(encoded,Arrays.copyOfRange(header,20,36),iterations);key=((KeyParameter)kdf.generateDerivedParameters(256)).getKey();
            GCMBlockCipher cipher=new GCMBlockCipher(new AESEngine());
            cipher.init(encrypt,new AEADParameters(new KeyParameter(key),128,Arrays.copyOfRange(header,36,48),header));
            long total=0;int n;
            while((n=input.read(in))!=-1){control.check();total=BackupLimits.add(total,n,BackupLimits.BYTES+256L*1024*1024);
                int count=cipher.processBytes(in,0,n,out,0);if(count>0)output.write(out,0,count);control.report(encrypt?"ENCRYPTING":"AUTHENTICATING",0,total);}
            int count=cipher.doFinal(out,0);if(count>0)output.write(out,0,count);output.flush();
        }catch(InvalidCipherTextException error){throw new IOException("AUTHENTICATION_FAILED",error);}
        finally{Arrays.fill(encoded,(byte)0);if(key!=null)Arrays.fill(key,(byte)0);Arrays.fill(in,(byte)0);Arrays.fill(out,(byte)0);}
    }
}
