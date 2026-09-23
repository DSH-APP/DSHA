package com.deepseekharness.app.backup;

import android.content.Context;
import com.deepseekharness.app.util.BuiltinPlugins;
import java.io.*;
import java.util.*;

/** 只读取已安装源码和 package.json；不执行插件、npm 或安装钩子。无法确认的内容保留并报告。 */
public final class NativePluginGraph {
    private final BackupFileSystem fs;
    private final GuestDataResolver resolver;
    private final File dsh,global;
    private final File rootfs;
    private String runtimeProof="";
    private final Map<String,String> expected=new HashMap<>(),managedChecks=new LinkedHashMap<>(),covered=new LinkedHashMap<>(),coveredPackages=new LinkedHashMap<>();
    private final Map<String,String> aliases=new LinkedHashMap<>();
    private final Map<String,String> metadataChecks=new LinkedHashMap<>();
    private final Map<String,Object> nodes=new LinkedHashMap<>();
    private final Map<String,Object> profiles=new LinkedHashMap<>(),shared=new LinkedHashMap<>(),systemPlugins=new LinkedHashMap<>();
    private final Set<String> visited=new HashSet<>();
    private final Set<String> warnings=new LinkedHashSet<>();
    private int visiting,edgeCount;
    public final List<BackupSource> sources=new ArrayList<>();
    public NativePluginGraph(Context context,BackupFileSystem fs,GuestDataResolver resolver,File dsh,File global,File rootfs)throws IOException{
        this.fs=fs;this.resolver=resolver;this.dsh=dsh;this.global=global;this.rootfs=rootfs;
        try(InputStream input=context.getAssets().open("managed-package-proofs.json");ByteArrayOutputStream out=new ByteArrayOutputStream()){
            byte[] buffer=new byte[8192];int n;while((n=input.read(buffer))!=-1){if(out.size()+n>BackupLimits.MANIFEST)throw new IOException("PACKAGE_PROOFS_LIMIT");out.write(buffer,0,n);}
            Map<String,Object> proof=BackupJson.read(out.toByteArray(),BackupLimits.MANIFEST);runtimeProof=BackupJson.string(proof,"archiveSha256");Object packages=proof.get("packages");if(!(packages instanceof Map))throw new IOException("PACKAGE_PROOFS_FORMAT");
            for(var row:((Map<?,?>)packages).entrySet())if(row.getKey() instanceof String&&row.getValue() instanceof String)expected.put((String)row.getKey(),(String)row.getValue());
        }
    }
    NativePluginGraph(BackupFileSystem fs,GuestDataResolver resolver,File dsh,File global,File rootfs,Map<String,String> proofs){
        this.fs=fs;this.resolver=resolver;this.dsh=dsh;this.global=global;this.rootfs=rootfs;this.expected.putAll(proofs);runtimeProof="synthetic-test";
    }
    public void collect(BackupControl control)throws IOException{
        File profileRoot=new File(dsh,"profiles");
        if(fs.stat(profileRoot).type.equals("DIRECTORY"))for(String profile:fs.list(profileRoot)){
            BackupLimits.path(profile);if(profile.contains("/")||profile.length()>128)throw new IOException("PROFILE_NAME");
            File packageFile=new File(profileRoot,profile+"/package.json");if(!fs.stat(packageFile).type.equals("FILE"))continue;
            Map<String,Object> metadata;
            try{metadata=metadata(packageFile);}catch(IOException error){warnings.add("PROFILE_METADATA_UNREADABLE");continue;}
            Map<String,Object> bindings=new LinkedHashMap<>();profiles.put(profile,bindings);
            Object deps=metadata.get("dependencies");Set<String> bundles=bundles(metadata);Map<String,Object> state=new LinkedHashMap<>();
            for(String system:BuiltinPlugins.SIGNED_BUILTINS){
                File marker=new File(profileRoot,profile+"/node_modules/"+system+".disabled");boolean declared=deps instanceof Map&&((Map<?,?>)deps).containsKey(system),enabled=bundles.contains(system),disabled=fs.stat(marker).type.equals("FILE");
                if(declared||enabled||disabled){state.put(system,Map.of("enabled",enabled,"disabled",disabled));excludeSystem(new File(profileRoot,profile+"/node_modules/"+system));}
            }
            if(!state.isEmpty())systemPlugins.put(profile,state);
            if(deps instanceof Map)for(Object key:((Map<?,?>)deps).keySet()){
                if(!(key instanceof String)||!packageName((String)key)){warnings.add("PLUGIN_NAME_INVALID");continue;}
                if(BuiltinPlugins.system((String)key))continue;
                File source=find(new File(profileRoot,profile),(String)key);if(source==null)warnings.add("PLUGIN_SOURCE_MISSING");else{String id=visit(source,control);if(id!=null)binding(bindings,(String)key,id);}
            }
            collectDirectory(new File(profileRoot,profile+"/node_modules"),bindings,control);
        }
        collectDirectory(new File(dsh,"node_modules"),shared,control);
        collectDetachedSystemCopies(new File(dsh,"plugin-src"));
        collectDetachedSystemCopies(new File(dsh,"plugin-history"));
    }
    private void collectDetachedSystemCopies(File directory)throws IOException{
        if(!fs.stat(directory).type.equals("DIRECTORY"))return;
        for(String child:fs.list(directory)){
            BackupLimits.path(child);if(child.contains("/"))throw new IOException("PLUGIN_NAME_INVALID");File entry=new File(directory,child);
            boolean system=BuiltinPlugins.SIGNED_BUILTINS.contains(child);
            for(File manifest:Arrays.asList(new File(entry,"package.json"),new File(entry,"package/package.json"))){
                if(system||!fs.stat(manifest).type.equals("FILE"))continue;
                try{Object value=metadata(manifest).get("name");system=value instanceof String&&BuiltinPlugins.system((String)value);}catch(IOException unreadable){/* 普通用户草稿由常规根照常备份。 */}
            }
            if(system)excludeSystem(entry);
        }
    }
    @SuppressWarnings("unchecked") private Set<String> bundles(Map<String,Object> metadata){
        Set<String> result=new LinkedHashSet<>();Object dsh=metadata.get("dsh");if(!(dsh instanceof Map))return result;Object profile=((Map<?,?>)dsh).get("profile");if(!(profile instanceof Map))return result;Object value=((Map<?,?>)profile).get("bundles");if(!(value instanceof List))return result;
        for(Object item:(List<?>)value)if(item instanceof String)result.add((String)item);return result;
    }
    private void excludeSystem(File alias){
        try{GuestDataResolver.Resolved resolved=resolver.resolve(alias);if(fs.stat(resolved.file).type.equals("DIRECTORY"))covered.put(alias.getAbsolutePath(),directoryProof(alias));}
        catch(IOException unreadable){warnings.add("SYSTEM_PLUGIN_ALIAS_UNREADABLE");}
    }
    private Map<String,Object> metadata(File file)throws IOException{
        byte[] bytes=fs.small(file,BackupLimits.MANIFEST);metadataChecks.put(file.getAbsolutePath(),BackupArchive.hex(BackupArchive.sha().digest(bytes)));
        return BackupJson.read(bytes,BackupLimits.MANIFEST);
    }
    private File find(File owner,String name)throws IOException{
        for(File base:Arrays.asList(new File(owner,"node_modules"),new File(dsh,"node_modules"),global)){
            File candidate=new File(base,name);GuestDataResolver.Resolved resolved=resolver.resolve(candidate);
            if(fs.stat(new File(resolved.file,"package.json")).type.equals("FILE")){aliases.put(candidate.getAbsolutePath(),resolved.proof);return candidate;}
        }return null;
    }
    private void collectDirectory(File directory,Map<String,Object> bindings,BackupControl control)throws IOException{
        File actual;
        try{GuestDataResolver.Resolved resolved=resolver.resolve(directory);actual=resolved.file;aliases.put(directory.getAbsolutePath(),resolved.proof);}catch(IOException error){warnings.add("PLUGIN_DIRECTORY_UNREADABLE");return;}
        if(fs.stat(actual).type.equals("MISSING"))return;
        if(!fs.stat(actual).type.equals("DIRECTORY")){warnings.add("PLUGIN_DIRECTORY_TYPE");return;}
        boolean complete=true;for(String name:fs.list(actual)){
            if(name.equals(".bin"))continue;
            if(name.endsWith(".disabled")&&fs.stat(new File(actual,name)).type.equals("FILE")){complete=false;continue;}
            File child=new File(actual,name);if(name.startsWith("@")&&fs.stat(child).type.equals("DIRECTORY")){
                for(String scoped:fs.list(child)){String fullName=name+"/"+scoped;if(BuiltinPlugins.system(fullName)){excludeSystem(new File(child,scoped));continue;}String id=visit(new File(child,scoped),control);complete&=id!=null;if(id!=null&&packageName(fullName))binding(bindings,fullName,id);}
            }else{if(BuiltinPlugins.system(name)){excludeSystem(child);continue;}String id=visit(child,control);complete&=id!=null;if(id!=null&&packageName(name))binding(bindings,name,id);}
        }
        // .bin 可能包含用户包装脚本，因此目录只有不存在 .bin 时才整体排除；否则逐依赖节点排除。
        if(complete&&fs.stat(new File(actual,".bin")).type.equals("MISSING"))covered.put(directory.getAbsolutePath(),directoryProof(directory));
    }
    private String visit(File source,BackupControl control)throws IOException{
        if(++visiting>BackupLimits.DEPTH){visiting--;throw new IOException("PLUGIN_GRAPH_DEPTH");}
        try{return visitPackage(source,control);}finally{visiting--;}
    }
    private void binding(Map<String,Object> bindings,String name,String id)throws IOException{
        if(!bindings.containsKey(name)&&++edgeCount>20000)throw new IOException("PLUGIN_GRAPH_LIMIT");bindings.put(name,id);
    }
    private String visitPackage(File source,BackupControl control)throws IOException{
        control.check();File actual;
        try{actual=resolver.resolve(source).file;}catch(IOException error){warnings.add("PLUGIN_LINK_UNREADABLE");return null;}
        String key=actual.getAbsolutePath(),id="package-"+NativeDataLocations.hash(key);if(visited.contains(key))return nodes.containsKey(id)?id:null;
        if(visited.size()>=4096)throw new IOException("PLUGIN_GRAPH_LIMIT");visited.add(key);
        Map<String,Object> metadata;
        try{metadata=metadata(new File(actual,"package.json"));}catch(IOException error){warnings.add("PLUGIN_METADATA_UNREADABLE");return null;}
        Object named=metadata.get("name");if(!(named instanceof String)||!packageName((String)named)){warnings.add("PLUGIN_NAME_INVALID");return null;}
        String name=(String)named;Object version=metadata.get("version");if(!(version instanceof String)||((String)version).length()>128){warnings.add("PLUGIN_VERSION_INVALID");version="unknown";}
        if(BuiltinPlugins.system(name)){covered.put(source.getAbsolutePath(),directoryProof(source));return null;}
        Map<String,Object> node=new LinkedHashMap<>();node.put("name",name);node.put("version",version);node.put("edges",new LinkedHashMap<String,Object>());nodes.put(id,node);
        String actualProof=ManagedPackageProof.digest(fs,actual,control),proof=expected.get(name);
        if(proof!=null&&proof.equals(actualProof)){
            node.put("managed",true);node.put("proof",proof);managedChecks.put(key,proof);coveredPackages.put(key,directoryProof(actual));return id;
        }
        node.put("managed",false);node.put("sourceRoot",id);node.put("proof",actualProof);
        File owned=new File(rootfs,"usr/local/lib/node_modules/@deepseek-ai/dsh/node_modules/"+name);
        node.put("managedOverride",expected.containsKey(name)&&owned.getAbsoluteFile().equals(actual.getAbsoluteFile()));
        @SuppressWarnings("unchecked") Map<String,Object> edges=(Map<String,Object>)node.get("edges");
        for(String group:Arrays.asList("dependencies","optionalDependencies","peerDependencies")){
            Object value=metadata.get(group);if(!(value instanceof Map))continue;
            for(Object dependency:((Map<?,?>)value).keySet()){
                if(!(dependency instanceof String)||!packageName((String)dependency)){warnings.add("DEPENDENCY_NAME_INVALID");continue;}
                if(BuiltinPlugins.system((String)dependency))continue;
                File found=find(actual,(String)dependency);if(found==null){if(!group.equals("optionalDependencies"))warnings.add("DEPENDENCY_MISSING");continue;}
                String target=visit(found,control);if(target!=null)binding(edges,(String)dependency,target);
            }
        }
        collectDirectory(new File(actual,"node_modules"),edges,control);
        sources.add(new FileBackupSource(fs,id,"plugins",actual,!GuestDataResolver.within(rootfs,actual),(path,state)->path.isEmpty()?"":exclusion(new File(actual,path)),resolver){
            @Override public Map<String,Object> description(){Map<String,Object> value=super.description();value.put("logicalKind","plugin-package");value.put("name",name);return value;}
        });coveredPackages.put(key,directoryProof(actual));return id;
    }
    public String exclusion(File path)throws IOException{
        BackupFileSystem.Node type=fs.stat(path);if(!type.type.equals("DIRECTORY")&&!type.type.equals("LINK"))return "";
        String expected=covered.get(path.getAbsolutePath());File checked=path;
        if(expected==null){checked=resolver.resolve(path).file;expected=coveredPackages.get(checked.getAbsolutePath());}if(expected==null)return "";
        if(!expected.equals(directoryProof(checked)))throw new IOException("PLUGIN_GRAPH_CHANGED");return "DEPENDENCIES_CAPTURED_IN_GRAPH";
    }
    private String directoryProof(File directory)throws IOException{
        GuestDataResolver.Resolved actual=resolver.resolve(directory);var hash=BackupArchive.sha();hash.update(actual.proof.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        for(String child:fs.list(actual.file)){hash.update((byte)'\n');hash.update(child.getBytes(java.nio.charset.StandardCharsets.UTF_8));}
        return BackupArchive.hex(hash.digest()).substring(0,20);
    }
    public void verify(BackupControl control)throws IOException{
        for(var entry:metadataChecks.entrySet()){
            control.check();byte[] current=fs.small(new File(entry.getKey()),BackupLimits.MANIFEST);
            if(!entry.getValue().equals(BackupArchive.hex(BackupArchive.sha().digest(current))))throw new IOException("PLUGIN_METADATA_CHANGED");
        }
        for(var entry:aliases.entrySet())if(!entry.getValue().equals(resolver.resolve(new File(entry.getKey())).proof))throw new IOException("PLUGIN_GRAPH_CHANGED");
        for(var entry:covered.entrySet())if(!entry.getValue().equals(directoryProof(new File(entry.getKey()))))throw new IOException("PLUGIN_GRAPH_CHANGED");
        for(var entry:coveredPackages.entrySet())if(!entry.getValue().equals(directoryProof(new File(entry.getKey()))))throw new IOException("PLUGIN_GRAPH_CHANGED");
        for(var entry:managedChecks.entrySet())if(!entry.getValue().equals(ManagedPackageProof.digest(fs,new File(entry.getKey()),control)))throw new IOException("MANAGED_PACKAGE_CHANGED");
    }
    public Map<String,Object> description(){Map<String,Object> value=new LinkedHashMap<>();value.put("version",2L);value.put("runtimeProof",runtimeProof);value.put("nodes",nodes);value.put("profiles",profiles);value.put("shared",shared);value.put("systemPlugins",systemPlugins);value.put("warnings",new ArrayList<>(warnings));value.put("complete",warnings.isEmpty());return value;}
    private static boolean packageName(String name){return name.length()<=214&&name.matches("(?:@[A-Za-z0-9._-]+/)?[A-Za-z0-9._-]+")&&!name.contains("..");}
}
