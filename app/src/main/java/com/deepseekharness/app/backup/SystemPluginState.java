package com.deepseekharness.app.backup;

import com.deepseekharness.app.util.BuiltinPlugins;
import java.io.*;
import java.util.*;

/** 只把签名系统插件的启停选择写入候选 profile；源码和链接由当前 APK 重建。 */
final class SystemPluginState {
    private SystemPluginState() { }

    @SuppressWarnings("unchecked")
    static Map<String,Object> fromGraph(Map<String,Object> graph)throws IOException{
        Object value=graph.get("systemPlugins");if(value==null)return Collections.emptyMap();
        if(!(value instanceof Map))throw new IOException("PLUGIN_SYSTEM_STATE_FORMAT");return (Map<String,Object>)value;
    }

    @SuppressWarnings("unchecked")
    static long apply(BackupFileSystem fs,File profiles,Map<String,Object> state,BackupControl control,boolean createWeb)throws IOException{
        if(state.size()>512)throw new IOException("PLUGIN_SYSTEM_STATE_LIMIT");long applied=0;
        if(fs.stat(profiles).type.equals("MISSING"))fs.directory(profiles);else if(!fs.stat(profiles).type.equals("DIRECTORY"))throw new IOException("PLUGIN_SYSTEM_PROFILE_TYPE");
        for(var profileRow:state.entrySet()){
            control.check();String profile=profileRow.getKey();BackupLimits.path(profile);if(profile.isEmpty()||profile.contains("/"))throw new IOException("PLUGIN_GRAPH_PROFILE");
            if(!(profileRow.getValue() instanceof Map))throw new IOException("PLUGIN_SYSTEM_STATE_FORMAT");Map<String,Object> entries=(Map<String,Object>)profileRow.getValue();
            if(entries.size()>BuiltinPlugins.SIGNED_BUILTINS.size())throw new IOException("PLUGIN_SYSTEM_STATE_LIMIT");File owner=new File(profiles,profile),file=new File(owner,"package.json");
            if(!fs.stat(file).type.equals("FILE")){
                if(!createWeb||!profile.equals("web")||!fs.stat(owner).type.equals("MISSING"))throw new IOException("PLUGIN_SYSTEM_PROFILE_MISSING");
                fs.directory(owner);Map<String,Object> initial=new LinkedHashMap<>();initial.put("name","dsh-profile-web");initial.put("private",true);initial.put("dependencies",new LinkedHashMap<>());
                initial.put("dsh",new LinkedHashMap<>(Map.of("profile",new LinkedHashMap<>(Map.of("bundles",new ArrayList<>(List.of("@deepseek-ai/dsh-base","@deepseek-ai/dsh-web-app")),"patchReload","startup")))));
                try(OutputStream output=fs.create(file)){output.write(BackupJson.write(initial,BackupLimits.MANIFEST));}
            }
            Map<String,Object> metadata=BackupJson.read(fs.small(file,BackupLimits.MANIFEST),BackupLimits.MANIFEST);
            Map<String,Object> dependencies=metadata.get("dependencies") instanceof Map?(Map<String,Object>)metadata.get("dependencies"):new LinkedHashMap<>();metadata.put("dependencies",dependencies);
            Map<String,Object> dsh=metadata.get("dsh") instanceof Map?(Map<String,Object>)metadata.get("dsh"):new LinkedHashMap<>();metadata.put("dsh",dsh);
            Map<String,Object> profileMetadata=dsh.get("profile") instanceof Map?(Map<String,Object>)dsh.get("profile"):new LinkedHashMap<>();dsh.put("profile",profileMetadata);
            List<Object> bundles=new ArrayList<>();Object current=profileMetadata.get("bundles");if(current instanceof List)bundles.addAll((List<?>)current);else if(current!=null)throw new IOException("PLUGIN_SYSTEM_STATE_FORMAT");
            for(var stateRow:entries.entrySet()){
                String plugin=stateRow.getKey();if(!BuiltinPlugins.SIGNED_BUILTINS.contains(plugin)||!(stateRow.getValue() instanceof Map))throw new IOException("PLUGIN_SYSTEM_STATE_NAME");Map<?,?> choice=(Map<?,?>)stateRow.getValue();
                if(!(choice.get("enabled") instanceof Boolean)||!(choice.get("disabled") instanceof Boolean))throw new IOException("PLUGIN_SYSTEM_STATE_FORMAT");boolean disabled=Boolean.TRUE.equals(choice.get("disabled")),enabled=Boolean.TRUE.equals(choice.get("enabled"))&&!disabled;
                bundles.removeIf(plugin::equals);if(enabled)bundles.add(plugin);dependencies.put(plugin,"link:/root/dsha-"+(plugin.startsWith("dsh-")?plugin.substring(4):plugin));
                String payload="node_modules/"+plugin;File old=new File(owner,payload);if(!fs.stat(old).type.equals("MISSING"))fs.removeOwned(owner,payload);
                String marker=payload+".disabled";File markerFile=new File(owner,marker);BackupFileSystem.Node markerType=fs.stat(markerFile);
                if(disabled){if(markerType.type.equals("MISSING")){fs.parents(owner,marker);try(OutputStream output=fs.create(markerFile)){output.flush();}}else if(!markerType.type.equals("FILE"))throw new IOException("PLUGIN_SYSTEM_STATE_MARKER");}
                else if(markerType.type.equals("FILE"))fs.delete(markerFile);else if(!markerType.type.equals("MISSING"))throw new IOException("PLUGIN_SYSTEM_STATE_MARKER");applied++;
            }
            profileMetadata.put("bundles",bundles);profileMetadata.put("patchReload","startup");fs.atomic(owner,"package.json",BackupJson.write(metadata,BackupLimits.MANIFEST));
        }return applied;
    }
}
