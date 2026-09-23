package com.deepseekharness.app.backup;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** 新建独立基础 profile 只需宿主文件 I/O；既有 web profile 和用户声明保持原位。 */
public final class NativeSafeProfile {
    private NativeSafeProfile(){}
    public static String create(BackupFileSystem fs,File dsh,String nonce)throws IOException{
        if(nonce==null||!nonce.matches("[a-f0-9]{16}"))throw new IOException("RECOVERY_PROFILE_ID");
        if(!fs.stat(dsh).type.equals("DIRECTORY"))throw new IOException("RECOVERY_DATA_UNAVAILABLE");
        fs.parents(dsh,"profiles/placeholder");File profiles=fs.child(dsh,"profiles");String name="dsha-recovery-"+nonce;
        File root=fs.child(profiles,name);fs.directory(root);
        Map<String,Object> manifest=Map.of("name",name,"private",true,"dependencies",Collections.emptyMap(),
                "dsh",Map.of("profile",Map.of("bundles",List.of("@deepseek-ai/dsh-base","@deepseek-ai/dsh-web-app"),"patchReload","startup")));
        Map<String,byte[]> content=new LinkedHashMap<>();content.put("package.json",BackupJson.write(manifest,16384));
        content.put("cordis.patch.yml","[]\n".getBytes(StandardCharsets.UTF_8));
        content.put("pnpm-workspace.yaml","packages:\n  - .\n\nnodeLinker: hoisted\nautoInstallPeers: false\n".getBytes(StandardCharsets.UTF_8));
        for(var entry:content.entrySet())try(OutputStream out=fs.create(fs.child(root,entry.getKey()))){out.write(entry.getValue());}
        fs.syncDirectory(root);fs.syncDirectory(profiles);return name;
    }
}
