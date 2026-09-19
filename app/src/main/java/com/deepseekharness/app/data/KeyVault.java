package com.deepseekharness.app.data;

import android.content.Context;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.Base64;
import com.deepseekharness.app.util.CredentialRead;

import java.nio.charset.StandardCharsets;
import java.security.KeyStore;

import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

/**
 * API Key 的加密存储：Android Keystore + AES/GCM，密钥不出 Keystore。
 *
 * <p>原版内联在 {@code HarnessController} 的 getOrCreateKey/encryptKey/decryptKey 里，
 * 这里独立成 {@code data} 层的一个类，让「凭据怎么加密」有单一归属，也能被单测
 * 之外的地方复用。IV 前置、Base64(NO_WRAP) 编码，与历史存储格式保持一致。
 */
public class KeyVault {

    private static final String KEYSTORE = "AndroidKeyStore";
    private static final String ALIAS = "dsh_api_key";
    private static final int GCM_TAG_BITS = 128;
    private static final int IV_BYTES = 12;

    private final Context ctx;

    public KeyVault(Context ctx) {
        this.ctx = ctx;
    }

    private SecretKey getOrCreateKey() throws Exception {
        KeyStore ks = KeyStore.getInstance(KEYSTORE);
        ks.load(null);
        if (ks.containsAlias(ALIAS)) {
            return ((KeyStore.SecretKeyEntry) ks.getEntry(ALIAS, null)).getSecretKey();
        }
        KeyGenerator kg = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE);
        kg.init(new KeyGenParameterSpec.Builder(ALIAS,
                KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setRandomizedEncryptionRequired(true)
                .build());
        return kg.generateKey();
    }

    /** 加密。失败返回空串（宁可丢一次写入，也不要明文落盘）。 */
    public String encrypt(String plain) {
        if (plain == null || plain.isEmpty()) return "";
        try {
            Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
            c.init(Cipher.ENCRYPT_MODE, getOrCreateKey());
            byte[] iv = c.getIV();
            byte[] ct = c.doFinal(plain.getBytes(StandardCharsets.UTF_8));
            byte[] out = new byte[iv.length + ct.length];
            System.arraycopy(iv, 0, out, 0, iv.length);
            System.arraycopy(ct, 0, out, iv.length, ct.length);
            return Base64.encodeToString(out, Base64.NO_WRAP);
        } catch (Exception e) {
            return "";
        }
    }

    /** 兼容旧调用名；失败明确抛出状态，不能静默改成未配置。 */
    public String decrypt(String stored) {
        return read(stored).requireValue();
    }

    /** 宿主备份明确区分未设置与旧设备密钥不可用；解密时绝不生成替代主密钥。 */
    public String decryptChecked(String stored) throws java.io.IOException {
        try{return read(stored).requireValue();}
        catch(CredentialRead.Unavailable unavailable){throw new java.io.IOException(unavailable.getMessage());}
    }
    public CredentialRead read(String stored) {
        if(stored==null||stored.isEmpty())return CredentialRead.missing();
        try {
            if(android.os.Build.VERSION.SDK_INT>=24){android.os.UserManager users=ctx.getSystemService(android.os.UserManager.class);
                if(users!=null&&!users.isUserUnlocked())return CredentialRead.failed(CredentialRead.Reason.DEVICE_LOCKED);}
            KeyStore store=KeyStore.getInstance(KEYSTORE);store.load(null);
            java.security.Key key=store.getKey(ALIAS,null);if(key==null)return CredentialRead.failed(CredentialRead.Reason.KEY_MISSING);
            byte[] all=Base64.decode(stored,Base64.NO_WRAP);if(all.length<IV_BYTES+16)return CredentialRead.failed(CredentialRead.Reason.UNREADABLE);
            Cipher cipher=Cipher.getInstance("AES/GCM/NoPadding");cipher.init(Cipher.DECRYPT_MODE,key,new GCMParameterSpec(GCM_TAG_BITS,all,0,IV_BYTES));
            return CredentialRead.available(new String(cipher.doFinal(all,IV_BYTES,all.length-IV_BYTES),StandardCharsets.UTF_8));
        }catch(Exception error){return classify(error);}
    }

    /** 格式化时移除本应用的凭据密钥；密文偏好会由调用方独立同步清空。 */
    public boolean clearForFactoryReset() {
        try {
            KeyStore store = KeyStore.getInstance(KEYSTORE);
            store.load(null);
            if (store.containsAlias(ALIAS)) store.deleteEntry(ALIAS);
            return !store.containsAlias(ALIAS);
        } catch (Exception unavailable) {
            return false;
        }
    }
    static CredentialRead classify(Throwable error){
            // 仅按平台明确分类细分；认证失败不等于“密文损坏”。不保留含供应商细节的原异常。
            java.util.Set<Throwable> seen=java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>());
            for(Throwable item=error;item!=null&&seen.size()<8&&seen.add(item);item=item.getCause()){
                if(item instanceof android.security.keystore.KeyPermanentlyInvalidatedException)return CredentialRead.failed(CredentialRead.Reason.KEY_INVALIDATED);
                if(item instanceof android.security.keystore.UserNotAuthenticatedException)return CredentialRead.failed(CredentialRead.Reason.AUTHENTICATION_REQUIRED);
                if(android.os.Build.VERSION.SDK_INT>=33&&Api33.transientFailure(item))return CredentialRead.failed(CredentialRead.Reason.TRANSIENT_STORE);
            }
            return CredentialRead.failed(CredentialRead.Reason.UNREADABLE);
    }
    @androidx.annotation.RequiresApi(33)
    private static final class Api33 {
        static boolean transientFailure(Throwable failure){return failure instanceof android.security.KeyStoreException&&((android.security.KeyStoreException)failure).isTransientFailure();}
    }
}
