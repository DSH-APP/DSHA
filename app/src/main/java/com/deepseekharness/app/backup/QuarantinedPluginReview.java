package com.deepseekharness.app.backup;

import android.content.Context;
import java.io.*;
import java.util.*;

/** 把已隔离恢复的完整依赖组复制到私有候选；源组只读，启用仍需现有审阅流程。 */
public final class QuarantinedPluginReview {
    private QuarantinedPluginReview() { }
    public static String[] preparePreset(Context context,String key,BackupControl control)throws IOException{
        var fs=new AndroidBackupFileSystem();File files=context.getFilesDir().getCanonicalFile();
        RetainedCatalogue catalog=new RetainedCatalogue(fs,files,new UserDataLayout(fs,files).current());var entry=catalog.resolve(key);
        if(entry.kind!=RetainedCatalogue.Kind.PRESET)throw new IOException("PRESET_CONVERSION_REQUIRED");
        String before=BackupTree.digest(fs,entry.source,control);String node="package-"+NativeDataLocations.hash(before);
        File parent=fs.child(files,"linux/ubuntu/root/dsha-native-plugin-reviews");if(fs.stat(parent).type.equals("MISSING"))fs.directory(parent);
        if(fs.list(parent).size()>=64)throw new IOException("PLUGIN_QUARANTINE_LIMIT");String group=UUID.randomUUID().toString();File target=new File(parent,group);fs.directory(target);
        fs.parents(target,"packages/"+node);File bundle=fs.child(target,"packages/"+node);BackupTree.copy(fs,entry.source,bundle,control);
        if(!before.equals(BackupTree.digest(fs,entry.source,control))||!before.equals(BackupTree.digest(fs,bundle,control)))throw new IOException("PLUGIN_QUARANTINE_CHANGED");
        if(!entry.status.equals("QUARANTINED")){
            if(!entry.displayName.matches("[a-z0-9][a-z0-9-]{0,63}"))throw new IOException("PRESET_ID_REQUIRES_REVIEW");
            byte[] original=fs.small(new File(bundle,"agent.cordis.yml"),512*1024);String agent=new String(original,java.nio.charset.StandardCharsets.UTF_8);
            if(agent.isBlank())throw new IOException("PRESET_CONFIGURATION_EMPTY");
            StringBuilder patch=new StringBuilder("- insert:\n    - id: preset-").append(entry.displayName).append("\n      name: '@deepseek-ai/dsh-agent-preset'\n      config:\n        id: ").append(entry.displayName).append("\n        name: '").append(entry.displayName).append("'\n        plugins:\n");
            for(String line:agent.split("\\R",-1))patch.append("          ").append(line).append('\n');
            fs.atomic(bundle,"package.json",BackupJson.write(Map.of("name","dsha-legacy-preset-"+entry.displayName,"version","0.0.0-dsha-migration","private",true,"type","module","dsh",Map.of("bundle",Map.of("patch","./cordis.patch.yml")),"dependencies",Map.of("@deepseek-ai/dsh-agent-preset",com.deepseekharness.app.util.Constants.DSH_VERSION)),16384));
            fs.atomic(bundle,"cordis.patch.yml",patch.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8));
        }
        Map<String,Object> pkg=BackupJson.read(fs.small(new File(bundle,"package.json"),BackupLimits.MANIFEST),BackupLimits.MANIFEST);
        if(pkg.get("dependencies") instanceof Map){var managed=new CurrentManagedPackages(context);for(Object value:((Map<?,?>)pkg.get("dependencies")).keySet()){
            if(!(value instanceof String))throw new IOException("PLUGIN_GRAPH_NAME");String name=(String)value;BackupLimits.path(name);
            File dependency=managed.verifiedCurrent(name);if(dependency==null)continue;
            fs.parents(bundle,"node_modules/"+name);File link=fs.child(bundle,"node_modules/"+name);
            String guest="/"+dependency.getAbsolutePath().substring(new File(files,"linux/ubuntu").getAbsolutePath().length()+1).replace(File.separatorChar,'/');
            if(fs.stat(link).type.equals("MISSING"))fs.symlink(guest,link);
        }}
        fs.atomic(target,"source-receipt.json",BackupJson.write(Map.of("version",1L,"source",key,"sourceSha256",before,"node",node,"executed",false),16384));
        return new String[]{group,node};
    }
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
