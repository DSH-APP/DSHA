package com.deepseekharness.app.backup;

import com.deepseekharness.app.util.BuiltinPlugins;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** 在隔离树内重建依赖与命令链接，保留声明原件；从不执行插件或安装钩子。 */
public final class PluginRestoreGraph {
    public interface Managed { File verified(String name,String proof)throws IOException; }
    private final BackupFileSystem fs;private final File store;private final Managed managed;
    private final Map<String,Map<String,Object>> nodes=new LinkedHashMap<>();
    private final Map<String,File> locations=new LinkedHashMap<>();
    private final Map<String,Object> profiles=new LinkedHashMap<>(),shared=new LinkedHashMap<>(),systemPluginState=new LinkedHashMap<>(),originalLinks=new LinkedHashMap<>();
    private final Set<String> warnings=new LinkedHashSet<>(),ignoredSystemPlugins=new LinkedHashSet<>(),ignoredSystemNodes=new LinkedHashSet<>();private long links;
    private long appliedSystemStates;
    public PluginRestoreGraph(BackupFileSystem fs,File store,Managed managed){this.fs=fs;this.store=store;this.managed=managed;}
    private static String name(String value)throws IOException{
        if(value==null||value.length()>214||!value.matches("(?:@[A-Za-z0-9._-]+/)?[A-Za-z0-9._-]+")||value.equals(".")||value.contains(".."))throw new IOException("PLUGIN_GRAPH_NAME");return value;
    }
    private static String id(String value)throws IOException{if(value==null||!value.matches("package-[a-f0-9]{20}"))throw new IOException("PLUGIN_GRAPH_ID");return value;}
    @SuppressWarnings("unchecked") private static Map<String,Object> object(Object value)throws IOException{if(!(value instanceof Map))throw new IOException("PLUGIN_GRAPH_FORMAT");return (Map<String,Object>)value;}
    private static String text(Map<String,Object> map,String key)throws IOException{return BackupJson.string(map,key);}
    private void normalize(Map<String,Object> description)throws IOException{
        if(description.containsKey("nodes")){
            long version=BackupJson.number(description,"version");if(version!=1&&version!=2)throw new IOException("PLUGIN_GRAPH_VERSION");
            for(var row:object(description.get("nodes")).entrySet())nodes.put(id(row.getKey()),new LinkedHashMap<>(object(row.getValue())));
            if(version==2){profiles.putAll(object(description.get("profiles")));shared.putAll(object(description.get("shared")));Object state=description.get("systemPlugins");if(state!=null)systemPluginState.putAll(object(state));}
            else warnings.add("LEGACY_PROFILE_BINDINGS_UNAVAILABLE");
            if(!Boolean.TRUE.equals(description.get("complete")))warnings.add("SOURCE_GRAPH_INCOMPLETE");
        }else if(description.containsKey("dependencyGraph")){
            Map<String,Object> legacy=object(description.get("dependencyGraph"));
            for(var row:legacy.entrySet()){
                if(!row.getKey().matches("[a-f0-9]{20}"))throw new IOException("PLUGIN_GRAPH_ID");var value=object(row.getValue());Map<String,Object> edges=new LinkedHashMap<>();
                for(var edge:object(value.get("links")).entrySet())edges.put(name(edge.getKey()),id("package-"+String.valueOf(edge.getValue())));
                String key="package-"+row.getKey();Map<String,Object> node=new LinkedHashMap<>();node.put("name",name(text(value,"name")));node.put("managed",false);node.put("sourceRoot",key);node.put("edges",edges);nodes.put(key,node);
                if(value.get("missing") instanceof List&&!((List<?>)value.get("missing")).isEmpty())warnings.add("SOURCE_GRAPH_INCOMPLETE");
            }
            Object declarations=description.get("declarations");if(declarations instanceof List)for(Object record:(List<?>)declarations){
                var row=object(record);if(!row.containsKey("node"))continue;
                String profile=text(row,"profile");BackupLimits.path(profile);if(profile.isEmpty()||profile.contains("/"))throw new IOException("PLUGIN_GRAPH_PROFILE");
                var bindings=profiles.containsKey(profile)?object(profiles.get(profile)):new LinkedHashMap<String,Object>();
                bindings.put(name(text(row,"name")),id("package-"+text(row,"node")));profiles.put(profile,bindings);
            }
            if(nodes.isEmpty())warnings.add("LEGACY_PLUGIN_GRAPH_UNAVAILABLE");
        }else warnings.add("LEGACY_PLUGIN_GRAPH_UNAVAILABLE");
        discardSystemPlugins();
        if(nodes.size()>4096||profiles.size()>512)throw new IOException("PLUGIN_GRAPH_LIMIT");
    }
    private void discardSystemPlugins()throws IOException{
        for(var row:new ArrayList<>(nodes.entrySet())){
            String packageName=name(text(row.getValue(),"name"));if(!BuiltinPlugins.system(packageName))continue;
            ignoredSystemPlugins.add(packageName);ignoredSystemNodes.add(row.getKey());nodes.remove(row.getKey());
        }
        for(var node:nodes.values())node.put("edges",withoutSystem(object(node.get("edges"))));
        cleanBindings(profiles);Map<String,Object> cleanShared=withoutSystem(shared);shared.clear();shared.putAll(cleanShared);
    }
    private Map<String,Object> withoutSystem(Map<String,Object> bindings)throws IOException{
        Map<String,Object> clean=new LinkedHashMap<>();for(var edge:bindings.entrySet()){
            String alias=name(edge.getKey());if(BuiltinPlugins.system(alias)){ignoredSystemPlugins.add(alias);continue;}String target=id(String.valueOf(edge.getValue()));
            if(ignoredSystemNodes.contains(target)){ignoredSystemPlugins.add(alias);continue;}
            clean.put(alias,target);
        }return clean;
    }
    private void cleanBindings(Map<String,Object> groups)throws IOException{
        for(String group:new ArrayList<>(groups.keySet()))groups.put(group,withoutSystem(object(groups.get(group))));
    }
    public Map<String,Object> rebuild(Map<String,Object> description,BackupControl control)throws IOException{
        if(!fs.stat(store).type.equals("DIRECTORY"))throw new IOException("PLUGIN_STORE_MISSING");normalize(description);
        for(String key:ignoredSystemNodes){File payload=new File(store,"packages/"+key);if(fs.stat(payload).type.equals("DIRECTORY"))fs.removeOwned(store,"packages/"+key);}
        long edges=0;
        for(var row:nodes.entrySet()){
            control.check();Map<String,Object> node=row.getValue();String packageName=name(text(node,"name"));
            if(!(node.get("managed") instanceof Boolean)||!Boolean.TRUE.equals(node.get("managed"))&&!row.getKey().equals(node.get("sourceRoot")))throw new IOException("PLUGIN_GRAPH_FORMAT");
            Map<String,Object> bindings=object(node.get("edges"));edges=BackupLimits.add(edges,bindings.size(),20000);
            for(var edge:bindings.entrySet())if(!nodes.containsKey(id(String.valueOf(edge.getValue()))))throw new IOException("PLUGIN_GRAPH_REFERENCE");else name(edge.getKey());
            File directory;
            if(Boolean.TRUE.equals(node.get("managed"))){
                String proof=text(node,"proof");if(!proof.matches("[a-f0-9]{64}"))throw new IOException("PLUGIN_GRAPH_PROOF");directory=managed==null?null:managed.verified(packageName,proof);
                if(directory==null){warnings.add("MANAGED_DEPENDENCY_UNAVAILABLE");continue;}
            }else{
                directory=fs.child(store,"packages/"+row.getKey());
                if(!fs.stat(directory).type.equals("DIRECTORY"))throw new IOException("PLUGIN_GRAPH_PAYLOAD_MISSING");
                Map<String,Object> actual=BackupJson.read(fs.small(fs.child(directory,"package.json"),BackupLimits.MANIFEST),BackupLimits.MANIFEST);
                if(!packageName.equals(actual.get("name")))throw new IOException("PLUGIN_GRAPH_PAYLOAD_MISMATCH");
                if(Boolean.TRUE.equals(node.get("managedOverride")))warnings.add("MANAGED_OVERRIDE_RETAINED_FOR_REVIEW");
            }
            locations.put(row.getKey(),directory);
        }
        for(var row:nodes.entrySet())if(!Boolean.TRUE.equals(row.getValue().get("managed"))){
            File owner=locations.get(row.getKey());if(owner!=null)bind(owner,object(row.getValue().get("edges")),"packages/"+row.getKey(),control);
        }
        for(var row:profiles.entrySet()){
            String profile=row.getKey();BackupLimits.path(profile);if(profile.isEmpty()||profile.contains("/"))throw new IOException("PLUGIN_GRAPH_PROFILE");
            File profiles=fs.child(store,"profiles");if(fs.stat(profiles).type.equals("MISSING")){warnings.add("PROFILE_DECLARATION_UNAVAILABLE");continue;}
            File owner=fs.child(profiles,profile);if(!fs.stat(owner).type.equals("DIRECTORY")){warnings.add("PROFILE_DECLARATION_UNAVAILABLE");continue;}
            bind(owner,object(row.getValue()),"profiles/"+profile,control);
        }
        if(!shared.isEmpty()){
            File owner=fs.child(store,"shared");if(fs.stat(owner).type.equals("MISSING"))fs.directory(owner);bind(owner,shared,"shared",control);
        }
        applySystemPluginState(control);
        Map<String,Object> report=new LinkedHashMap<>();report.put("version",1L);report.put("nodes",(long)locations.size());report.put("links",links);report.put("warnings",new ArrayList<>(warnings));
        report.put("relationshipsRebuilt",warnings.isEmpty());report.put("quarantined",true);report.put("executed",false);report.put("sourceGraph",description);report.put("originalLinks",originalLinks);report.put("ignoredSystemPlugins",new ArrayList<>(ignoredSystemPlugins));report.put("systemPluginStatesApplied",appliedSystemStates);
        fs.atomic(store,"restore-graph.json",BackupJson.write(report,BackupLimits.MANIFEST));return report;
    }
    private void applySystemPluginState(BackupControl control)throws IOException{
        if(systemPluginState.size()>512)throw new IOException("PLUGIN_SYSTEM_STATE_LIMIT");
        File profileRoot=new File(store,"profiles");
        for(var profileRow:systemPluginState.entrySet()){
            control.check();String profile=profileRow.getKey();BackupLimits.path(profile);if(profile.isEmpty()||profile.contains("/"))throw new IOException("PLUGIN_GRAPH_PROFILE");
            Map<String,Object> entries=object(profileRow.getValue());if(entries.size()>BuiltinPlugins.SIGNED_BUILTINS.size())throw new IOException("PLUGIN_SYSTEM_STATE_LIMIT");
            File owner=new File(profileRoot,profile),file=new File(owner,"package.json");
            if(!fs.stat(owner).type.equals("DIRECTORY")||!fs.stat(file).type.equals("FILE")){warnings.add("PROFILE_DECLARATION_UNAVAILABLE");continue;}
            byte[] original=fs.small(file,BackupLimits.MANIFEST);Map<String,Object> metadata=BackupJson.read(original,BackupLimits.MANIFEST);
            Map<String,Object> dependencies=metadata.get("dependencies") instanceof Map?object(metadata.get("dependencies")):new LinkedHashMap<>();metadata.put("dependencies",dependencies);
            Map<String,Object> dsh=metadata.get("dsh") instanceof Map?object(metadata.get("dsh")):new LinkedHashMap<>();metadata.put("dsh",dsh);
            Map<String,Object> profileMetadata=dsh.get("profile") instanceof Map?object(dsh.get("profile")):new LinkedHashMap<>();dsh.put("profile",profileMetadata);
            List<Object> bundles=new ArrayList<>();Object current=profileMetadata.get("bundles");if(current instanceof List)bundles.addAll((List<?>)current);else if(current!=null)throw new IOException("PLUGIN_SYSTEM_STATE_FORMAT");
            for(var stateRow:entries.entrySet()){
                String plugin=name(stateRow.getKey());if(!BuiltinPlugins.SIGNED_BUILTINS.contains(plugin))throw new IOException("PLUGIN_SYSTEM_STATE_NAME");Map<String,Object> state=object(stateRow.getValue());
                if(!(state.get("enabled") instanceof Boolean)||!(state.get("disabled") instanceof Boolean))throw new IOException("PLUGIN_SYSTEM_STATE_FORMAT");
                boolean disabled=Boolean.TRUE.equals(state.get("disabled")),enabled=Boolean.TRUE.equals(state.get("enabled"))&&!disabled;bundles.removeIf(plugin::equals);if(enabled)bundles.add(plugin);
                dependencies.put(plugin,"link:/root/dsha-"+(plugin.startsWith("dsh-")?plugin.substring(4):plugin));
                String payload="node_modules/"+plugin;File old=new File(owner,payload);if(!fs.stat(old).type.equals("MISSING"))fs.removeOwned(owner,payload);
                String marker=payload+".disabled";File markerFile=new File(owner,marker);BackupFileSystem.Node markerType=fs.stat(markerFile);
                if(disabled){if(markerType.type.equals("MISSING")){fs.parents(owner,marker);try(OutputStream output=fs.create(markerFile)){/* empty marker */}}else if(!markerType.type.equals("FILE"))throw new IOException("PLUGIN_SYSTEM_STATE_MARKER");}
                else if(markerType.type.equals("FILE"))fs.delete(markerFile);else if(!markerType.type.equals("MISSING"))throw new IOException("PLUGIN_SYSTEM_STATE_MARKER");
                appliedSystemStates++;
            }
            profileMetadata.put("bundles",bundles);saveDeclaration(owner,original,metadata,"profiles/"+profile+"/system-plugins");
        }
    }
    private void saveDeclaration(File owner,byte[] original,Map<String,Object> metadata,String logical)throws IOException{
        String saved="original-declarations/"+logical+"/package.json";fs.parents(store,saved);File savedFile=fs.child(store,saved);
        if(fs.stat(savedFile).type.equals("MISSING"))try(OutputStream output=fs.create(savedFile)){output.write(original);}
        else if(!fs.stat(savedFile).type.equals("FILE"))throw new IOException("PLUGIN_ORIGINAL_DECLARATION_TYPE");
        fs.atomic(owner,"package.json",BackupJson.write(metadata,BackupLimits.MANIFEST));
    }
    private void bind(File owner,Map<String,Object> bindings,String logical,BackupControl control)throws IOException{
        if(bindings.size()>20000)throw new IOException("PLUGIN_GRAPH_LIMIT");Map<String,String> replacements=new LinkedHashMap<>();
        for(var edge:bindings.entrySet()){
            control.check();String alias=name(edge.getKey()),key=id(String.valueOf(edge.getValue()));if(!nodes.containsKey(key))throw new IOException("PLUGIN_GRAPH_REFERENCE");
            File target=locations.get(key);if(target==null){warnings.add("DEPENDENCY_NOT_REBUILT");continue;}
            String relative="node_modules/"+alias;fs.parents(owner,relative);File link=fs.child(owner,relative);
            if(link(link,target,logical+"/"+relative))replacements.put(alias,"link:"+(GuestDataResolver.within(store,target)?relative(owner,target):target.getAbsolutePath()));
            bins(owner,target,logical,alias,control);
            control.report("REBUILDING_PLUGIN_GRAPH",locations.size(),++links);
        }
        rewrite(owner,replacements,logical);
    }
    private boolean link(File link,File target,String logical)throws IOException{
        var existing=fs.stat(link);String desired=GuestDataResolver.within(store,target)?relative(link.getParentFile(),target):target.getAbsolutePath();
        if(existing.type.equals("LINK")){
            String old=fs.readLink(link);if(old.equals(desired))return true;originalLinks.put(logical,old);fs.delete(link);
        }else if(!existing.type.equals("MISSING")){warnings.add("EXISTING_PLUGIN_CONTENT_RETAINED");return false;}
        fs.symlink(desired,link);return true;
    }
    private void bins(File owner,File target,String logical,String alias,BackupControl control)throws IOException{
        Map<String,Object> metadata=BackupJson.read(fs.small(fs.child(target,"package.json"),BackupLimits.MANIFEST),BackupLimits.MANIFEST);Object value=metadata.get("bin");
        if(value==null)return;Map<String,Object> entries=value instanceof String?Map.of(alias.substring(alias.lastIndexOf('/')+1),value):object(value);
        if(entries.size()>256)throw new IOException("PLUGIN_BIN_LIMIT");
        for(var entry:entries.entrySet()){
            control.check();String command=entry.getKey();if(!command.matches("[A-Za-z0-9_.-]{1,128}")||command.equals(".")||command.equals("..")||!(entry.getValue() instanceof String))throw new IOException("PLUGIN_BIN_PATH");
            String path=(String)entry.getValue();while(path.startsWith("./"))path=path.substring(2);BackupLimits.path(path);if(path.isEmpty())throw new IOException("PLUGIN_BIN_PATH");
            File executable=fs.child(target,path);if(!fs.stat(executable).type.equals("FILE")){warnings.add("PLUGIN_BIN_REQUIRES_REVIEW");continue;}
            fs.parents(owner,"node_modules/.bin/"+command);link(fs.child(owner,"node_modules/.bin/"+command),executable,logical+"/node_modules/.bin/"+command);
            if(GuestDataResolver.within(store,executable))fs.mode(executable,fs.stat(executable).mode|0100);
        }
    }
    private void rewrite(File owner,Map<String,String> replacements,String logical)throws IOException{
        File file=fs.child(owner,"package.json");if(fs.stat(file).type.equals("MISSING"))return;
        byte[] original=fs.small(file,BackupLimits.MANIFEST);Map<String,Object> metadata=BackupJson.read(original,BackupLimits.MANIFEST);boolean changed=false;
        for(String field:List.of("dependencies","optionalDependencies","peerDependencies"))if(metadata.get(field) instanceof Map){
            Map<String,Object> values=object(metadata.get(field));for(var entry:replacements.entrySet())if(values.containsKey(entry.getKey())&&!entry.getValue().equals(values.get(entry.getKey()))){values.put(entry.getKey(),entry.getValue());changed=true;}
        }
        if(!changed)return;
        String saved="original-declarations/"+logical+"/package.json";fs.parents(store,saved);
        if(fs.stat(fs.child(store,saved)).type.equals("MISSING"))try(OutputStream output=fs.create(fs.child(store,saved))){output.write(original);}
        else if(!fs.stat(fs.child(store,saved)).type.equals("FILE"))throw new IOException("PLUGIN_ORIGINAL_DECLARATION_TYPE");
        fs.atomic(owner,"package.json",BackupJson.write(metadata,BackupLimits.MANIFEST));
    }
    static String relative(File from,File target)throws IOException{
        String[] left=from.getAbsolutePath().replace(File.separatorChar,'/').split("/"),right=target.getAbsolutePath().replace(File.separatorChar,'/').split("/");int common=0;
        while(common<left.length&&common<right.length&&left[common].equals(right[common]))common++;
        if(common==0)throw new IOException("PLUGIN_LINK_FILESYSTEM");List<String> parts=new ArrayList<>();for(int i=common;i<left.length;i++)parts.add("..");for(int i=common;i<right.length;i++)parts.add(right[i]);return parts.isEmpty()?".":String.join("/",parts);
    }
}
