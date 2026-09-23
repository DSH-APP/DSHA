package com.deepseekharness.app.backup;

import android.content.Context;
import java.io.*;
import java.util.*;

/** 只将签名 APK 清单内且实际字节匹配的当前包作为受管依赖，不信任归档自带的包名。 */
public final class CurrentManagedPackages {
    private final BackupFileSystem fs=new AndroidBackupFileSystem();private final File root;private final Map<String,Object> expected;
    public CurrentManagedPackages(Context context)throws IOException{
        root=new File(context.getFilesDir().getCanonicalFile(),"linux/ubuntu/usr/local/lib/node_modules/@deepseek-ai/dsh/node_modules");
        try(InputStream input=context.getAssets().open("managed-package-proofs.json");ByteArrayOutputStream output=new ByteArrayOutputStream()){
            byte[] buffer=new byte[8192];int n;while((n=input.read(buffer))!=-1){if(output.size()+n>BackupLimits.MANIFEST)throw new IOException("PACKAGE_PROOFS_LIMIT");output.write(buffer,0,n);}
            Object packages=BackupJson.read(output.toByteArray(),BackupLimits.MANIFEST).get("packages");if(!(packages instanceof Map))throw new IOException("PACKAGE_PROOFS_FORMAT");
            @SuppressWarnings("unchecked") Map<String,Object> values=(Map<String,Object>)packages;expected=values;
        }
    }
    public File verified(String name,String proof)throws IOException{
        if(!proof.equals(expected.get(name)))return null;BackupLimits.path(name);
        try{
            if(fs.stat(root).type.equals("MISSING"))return null;
            File packageRoot=fs.child(root,name);if(!fs.stat(packageRoot).type.equals("DIRECTORY"))return null;
            return proof.equals(ManagedPackageProof.digest(fs,packageRoot,new BackupControl(null)))?packageRoot:null;
        }catch(IOException unavailable){return null;}
    }
}
