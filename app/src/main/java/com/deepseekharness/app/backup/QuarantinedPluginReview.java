package com.deepseekharness.app.backup;

import android.content.Context;
import java.io.*;
import java.util.*;

/** 把已隔离恢复的完整依赖组复制到私有候选；源组只读，启用仍需现有审阅流程。 */
public final class QuarantinedPluginReview {
    private QuarantinedPluginReview() { }
    public static String prepare(Context context,String operation,String node,BackupControl control)throws IOException{
        if(!operation.matches("[a-f0-9]{8}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{12}")||!node.matches("package-[a-f0-9]{20}"))throw new IOException("PLUGIN_QUARANTINE_ID");
        var fs=new AndroidBackupFileSystem();File files=context.getFilesDir().getCanonicalFile(),source=fs.child(files,"plugin-imports/"+operation);
        if(!fs.stat(fs.child(source,"packages/"+node+"/package.json")).type.equals("FILE"))throw new IOException("PLUGIN_QUARANTINE_SOURCE");
        File parent=fs.child(files,"linux/ubuntu/root/dsha-native-plugin-reviews");if(fs.stat(parent).type.equals("MISSING"))fs.directory(parent);
        if(fs.list(parent).size()>=64)throw new IOException("PLUGIN_QUARANTINE_LIMIT");String id=UUID.randomUUID().toString();File target=fs.child(parent,id);
        String before=BackupTree.digest(fs,source,control);BackupTree.copy(fs,source,target,control);
        if(!before.equals(BackupTree.digest(fs,source,control))||!before.equals(BackupTree.digest(fs,target,control)))throw new IOException("PLUGIN_QUARANTINE_CHANGED");
        File report=new File(target,"restore-graph.json");
        if(fs.stat(report).type.equals("FILE")){
            Map<String,Object> metadata=BackupJson.read(fs.small(report,BackupLimits.MANIFEST),BackupLimits.MANIFEST);
            if(!(metadata.get("sourceGraph") instanceof Map))throw new IOException("PLUGIN_QUARANTINE_GRAPH");
            @SuppressWarnings("unchecked") Map<String,Object> graph=(Map<String,Object>)metadata.get("sourceGraph");
            new PluginRestoreGraph(fs,target,new CurrentManagedPackages(context)::verified).rebuild(graph,control);
        }
        fs.atomic(target,"source-receipt.json",BackupJson.write(Map.of("version",1L,"operation",operation,"sourceSha256",before,"node",node,"executed",false),4096));
        return id;
    }
}
