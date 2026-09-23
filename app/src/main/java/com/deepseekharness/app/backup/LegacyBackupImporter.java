package com.deepseekharness.app.backup;

import com.google.gson.stream.*;
import com.google.gson.Strictness;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** v1/v2 的包内链接快照及 v3/v4 摘要清单适配；只在私有槽位转换成 v5，不调用 Python。 */
public final class LegacyBackupImporter {
    private static final String MANIFEST=".dsha-backup-manifest.json";
    private static final Set<String> HOT=new HashSet<>(Arrays.asList("sessions","storages","attachments","settings.yaml"));
    private static final class Stored {
        LegacyTarReader.Member member;File file;String sha;
        Stored(LegacyTarReader.Member m,File file,String sha){member=m;this.file=file;this.sha=sha;}
    }
    private final BackupFileSystem fs;private final File task;
    private final TreeMap<String,Stored> members=new TreeMap<>();private final Map<String,Map<String,Object>> inventory=new LinkedHashMap<>();
    private final Set<String> manifests=new HashSet<>();private final List<String> warnings=new ArrayList<>();
    private int count;private long metadata;
    public LegacyBackupImporter(BackupFileSystem fs,File task){this.fs=fs;this.task=task;}
    public Map<String,Object> convert(File input,File output,String filename,BackupControl control)throws IOException{
        File slots=new File(task,"legacy-payload");fs.directory(slots);
        try(InputStream raw=fs.read(input,fs.stat(input))){LegacyTarReader.read(raw,(member,data)->{
            if(++count>BackupLimits.ENTRIES)throw new IOException("ENTRY_LIMIT");metadata=BackupLimits.add(metadata,member.path.length()*2L+128,BackupLimits.METADATA);
            File file=null;String hash="";
            if(member.type.equals("FILE")){file=fs.child(slots,String.valueOf(count));try(OutputStream out=fs.create(file)){byte[] buffer=new byte[65536];int n;while((n=data.read(buffer))!=-1){control.check();out.write(buffer,0,n);}}
                try(InputStream bytes=fs.read(file,fs.stat(file))){hash=BackupArchive.digest(bytes,control);}}
            members.put(member.path,new Stored(member,file,hash));
            if(member.path.equals(MANIFEST)||member.path.endsWith("/"+MANIFEST)||member.path.endsWith("backup-manifest.json"))manifests.add(member.path);
            else {
                if(member.type.equals("FILE"))inventory.put(member.path,new LinkedHashMap<>(Map.of("size",member.size,"sha256",hash)));
                else if(member.type.equals("DIRECTORY"))inventory.put(member.path,new LinkedHashMap<>(Map.of("directory",true)));
                else inventory.put(member.path,new LinkedHashMap<>(Map.of("link",member.target)));
                String parent=member.path;while(parent.contains("/")){parent=parent.substring(0,parent.lastIndexOf('/'));inventory.putIfAbsent(parent,new LinkedHashMap<>(Map.of("directory",true)));}
            }
        },control);}
        Map<String,Object> legacy=readManifest();long version=legacy.get("formatVersion") instanceof Number?((Number)legacy.get("formatVersion")).longValue():1;
        if(version<1||version>4)throw new IOException("UNSUPPORTED_LEGACY_VERSION");
        String scope=legacy.get("scope") instanceof String?(String)legacy.get("scope"):scopeFromName(filename);
        if(!Set.of("full","sessions","settings","plugins").contains(scope))throw new IOException("UNKNOWN_BACKUP_SCOPE");
        String base=findDsh(),snapshot=findSnapshot();
        Map<String,String> roots=new LinkedHashMap<>();
        for(String path:members.keySet())if(path.startsWith(base)){String tail=path.substring(base.length());if(tail.isEmpty())continue;String name=tail.split("/",2)[0];
            if(!name.equals(MANIFEST)&&!name.equals(".dsha-pub")&&!name.equals("DSHA-README.txt"))roots.putIfAbsent(name,base+name);}
        if(!snapshot.isEmpty())for(String name:HOT)if(has(snapshot+name))roots.put(name,snapshot+name);
        List<Map<String,Object>> descriptions=new ArrayList<>();
        try(OutputStream out=fs.create(output)){
            BackupArchive.Writer writer=new BackupArchive.Writer(out,control);
            for(var root:roots.entrySet()){
                String name=root.getKey(),category=NativeDataLocations.classify(name);if(!scope.equals("full")&&!scope.equals(category))throw new IOException("LEGACY_SCOPE_MISMATCH");
                String id=NativeDataLocations.id(name);descriptions.add(new LinkedHashMap<>(Map.of("id",id,"scope",category,"logicalKind","dsh-child","name",name)));
                writeTree(writer,root.getValue(),id,"",category,new HashSet<>(),control);
            }
            Stored nativeConfig=members.get(".dsha-native-config.json");if(nativeConfig!=null){if(!scope.equals("full")&&!scope.equals("settings"))throw new IOException("LEGACY_SCOPE_MISMATCH");
                descriptions.add(new LinkedHashMap<>(Map.of("id","native-settings","scope","settings")));writeTree(writer,".dsha-native-config.json","native-settings","","settings",new HashSet<>(),control);}
            // 内联依赖的原件作为隔离插件包保留；实际依赖图仍附在清单中供恢复器重建。
            Object graph=legacy.get("pluginDependencyGraph");if(graph instanceof Map)for(Object key:((Map<?,?>)graph).keySet()){
                if(!(key instanceof String)||!((String)key).matches("[a-f0-9]{20}"))throw new IOException("LEGACY_PLUGIN_GRAPH");
                if(!(((Map<?,?>)graph).get(key) instanceof Map))throw new IOException("LEGACY_PLUGIN_GRAPH");
                @SuppressWarnings("unchecked") Map<String,Object> node=(Map<String,Object>)((Map<?,?>)graph).get(key);
                String name=BackupJson.string(node,"name"),prefix=".dsha-plugin-src/.deps/"+key;validateGraphNode((Map<?,?>)graph,node,prefix,name);
                String id="package-"+key;descriptions.add(new LinkedHashMap<>(Map.of("id",id,"scope","plugins","logicalKind","plugin-package","name",name)));
                writeTree(writer,prefix,id,"","plugins",new HashSet<>(),control);
            }
            if(has(".dsha-plugin-src")){
                descriptions.add(new LinkedHashMap<>(Map.of("id","legacy-plugins","scope","plugins","logicalKind","legacy-plugin-store","name","legacy-plugins")));
                writer.add(BackupArchive.Record.of("legacy-plugins","","DIRECTORY","plugins",0,""),null);
                for(String child:children(".dsha-plugin-src"))if(!child.equals(".deps")||!(graph instanceof Map))writeTree(writer,".dsha-plugin-src/"+child,"legacy-plugins",child,"plugins",new HashSet<>(),control);
                if(!(graph instanceof Map))warnings.add("LEGACY_PLUGIN_GRAPH_UNAVAILABLE");
            }
            if(has(".dsha-workdir")){
                String id="project-"+NativeDataLocations.hash("legacy-workdir");descriptions.add(new LinkedHashMap<>(Map.of("id",id,"scope","projects","logicalKind","project","name","legacy-workdir")));
                writeTree(writer,".dsha-workdir",id,"","projects",new HashSet<>(),control);
            }
            if(descriptions.isEmpty())throw new IOException("NO_LEGACY_DATA");
            Map<String,Object> summary=new LinkedHashMap<>();summary.put("operation","EXPORT");summary.put("integrity",warnings.isEmpty()?"EXTERNAL_CHECKED":"PARTIAL");summary.put("roots",descriptions);
            summary.put("createdAt",System.currentTimeMillis());summary.put("appVersion",String.valueOf(legacy.getOrDefault("appVersion","legacy-unknown")));summary.put("runtime",String.valueOf(legacy.getOrDefault("dshVersion","unknown")));
            summary.put("dataFormat","LEGACY_"+version);summary.put("sensitivePolicy","LEGACY_PLAINTEXT");summary.put("plugins",Map.of("legacyVersion",version,"declarations",legacy.getOrDefault("plugins",Collections.emptyList()),"dependencyGraph",graph==null?Collections.emptyMap():graph));
            summary.put("legacyConfirmationRequired",true);summary.put("warnings",warnings);return writer.finish(summary);
        }
    }
    private Map<String,Object> readManifest()throws IOException{
        if(manifests.isEmpty()){warnings.add("LEGACY_WITHOUT_MANIFEST");return new LinkedHashMap<>();}
        if(manifests.size()!=1)throw new IOException("CONFLICTING_MANIFESTS");Stored member=members.get(manifests.iterator().next());
        if(member.file==null||member.member.size>BackupLimits.METADATA)throw new IOException("LEGACY_MANIFEST_LIMIT");
        Map<String,Object> result=new LinkedHashMap<>();Set<String> keys=new HashSet<>();boolean checked=false;
        try(InputStream in=fs.read(member.file,fs.stat(member.file));JsonReader reader=new JsonReader(new InputStreamReader(in,StandardCharsets.UTF_8))){
            reader.setStrictness(Strictness.STRICT);reader.beginObject();while(reader.hasNext()){
                String key=reader.nextName();if(!keys.add(key))throw new IOException("DUPLICATE_METADATA");
                if(key.equals("inventory")){
                    Set<String> remaining=new HashSet<>(inventory.keySet());reader.beginObject();while(reader.hasNext()){
                        String name=reader.nextName();if(!remaining.remove(name))throw new IOException("LEGACY_INVENTORY_MISMATCH");Object value=BackupJson.readValue(reader);
                        if(!inventory.get(name).equals(value))throw new IOException("LEGACY_INVENTORY_MISMATCH");}
                    reader.endObject();if(!remaining.isEmpty())throw new IOException("LEGACY_EXTRA_MEMBERS");checked=true;
                }else result.put(key,BackupJson.readValue(reader));
            }reader.endObject();if(reader.peek()!=JsonToken.END_DOCUMENT)throw new IOException("LEGACY_MANIFEST_TRAILING");
        }
        long version=result.get("formatVersion") instanceof Number?((Number)result.get("formatVersion")).longValue():1;
        if(version>=3&&!checked)throw new IOException("LEGACY_INVENTORY_MISSING");
        if(version>=3)for(Stored stored:members.values())if(stored.member.type.equals("LINK")||stored.member.type.equals("HARDLINK"))throw new IOException("LEGACY_UNVERIFIED_LINK");return result;
    }
    private String scopeFromName(String filename)throws IOException{
        if(filename==null)throw new IOException("LEGACY_SCOPE_CONFIRMATION_REQUIRED");
        if(filename.startsWith("DSHA-sessions-"))return "sessions";if(filename.startsWith("DSHA-settings-"))return "settings";if(filename.startsWith("DSHA-plugins-"))return "plugins";
        if(filename.startsWith("DSHA-backup-")){warnings.add("LEGACY_SCOPE_FROM_FILENAME");return "full";}throw new IOException("LEGACY_SCOPE_CONFIRMATION_REQUIRED");
    }
    private String findDsh()throws IOException{
        Set<String> roots=new TreeSet<>();for(String path:members.keySet()){String[] parts=path.split("/");for(int i=0;i<parts.length;i++)if(parts[i].equals(".dsh")){roots.add(String.join("/",Arrays.copyOf(parts,i+1))+"/");break;}}
        if(roots.size()>1)throw new IOException("LEGACY_MULTIPLE_DATA_ROOTS");if(!roots.isEmpty())return roots.iterator().next();
        for(String path:members.keySet())if(path.matches("(?:sessions|storages|profiles|settings\\.yaml)(?:/.*)?"))return "";throw new IOException("NO_LEGACY_DATA");
    }
    private String findSnapshot()throws IOException{
        Set<String> roots=new TreeSet<>();for(String path:members.keySet()){int index=("/"+path).indexOf("/.dsha-pub/");if(index>=0)roots.add(path.substring(0,index)+".dsha-pub/");if(path.endsWith(".dsha-pub"))roots.add(path+"/");}
        if(roots.size()>1)throw new IOException("LEGACY_MULTIPLE_SNAPSHOTS");return roots.isEmpty()?"":roots.iterator().next();
    }
    private boolean has(String name){return members.containsKey(name)||members.ceilingKey(name+"/")!=null&&members.ceilingKey(name+"/").startsWith(name+"/");}
    private Set<String> children(String source){String prefix=source+"/";Set<String> result=new TreeSet<>();for(String path:members.tailMap(prefix).keySet()){
        if(!path.startsWith(prefix))break;String child=path.substring(prefix.length()).split("/",2)[0];if(!child.isEmpty())result.add(child);}return result;}
    private void writeTree(BackupArchive.Writer writer,String source,String root,String relative,String scope,Set<String> followed,BackupControl control)throws IOException{
        control.check();BackupLimits.path(relative);Stored stored=members.get(source);String type=stored==null?"DIRECTORY":stored.member.type;
        if(type.equals("LINK")||type.equals("HARDLINK")){
            if(!followed.add(source)||followed.size()>BackupLimits.LINKS)throw new IOException("LEGACY_LINK_CYCLE");String target=stored.member.target;
            if(target.startsWith("/"))throw new IOException("LEGACY_LINK_PAYLOAD_MISSING");
            String resolved=type.equals("HARDLINK")?BackupLimits.path(target):NativeRestorePlan.relativeTarget(source,target);if(!has(resolved))throw new IOException("LEGACY_LINK_PAYLOAD_MISSING");
            writeTree(writer,resolved,root,relative,scope,followed,control);followed.remove(source);return;
        }
        if(type.equals("FILE")){
            var values=new LinkedHashMap<>(BackupArchive.Record.of(root,relative,"FILE",scope,stored.member.size,stored.sha).metadata);values.put("mode",stored.member.mode);
            try(InputStream input=fs.read(stored.file,fs.stat(stored.file))){writer.add(new BackupArchive.Record(values),input);}return;
        }
        var values=new LinkedHashMap<>(BackupArchive.Record.of(root,relative,"DIRECTORY",scope,0,"").metadata);values.put("mode",stored==null?0700:stored.member.mode);
        writer.add(new BackupArchive.Record(values),null);
        for(String child:children(source))writeTree(writer,source+"/"+child,root,relative.isEmpty()?child:relative+"/"+child,scope,followed,control);
    }
    private void validateGraphNode(Map<?,?> graph,Map<String,Object> node,String prefix,String name)throws IOException{
        if(!name.matches("(?:@[A-Za-z0-9._-]+/)?[A-Za-z0-9._-]+")||!(node.get("links") instanceof Map))throw new IOException("LEGACY_PLUGIN_GRAPH");
        for(var edge:((Map<?,?>)node.get("links")).entrySet())if(!(edge.getKey() instanceof String)||!(edge.getValue() instanceof String)||!graph.containsKey(edge.getValue()))throw new IOException("LEGACY_PLUGIN_GRAPH");
        Stored packageJson=members.get(prefix+"/package.json");if(packageJson==null||packageJson.file==null)throw new IOException("LEGACY_PLUGIN_PAYLOAD_MISSING");
        Map<String,Object> metadata=BackupJson.read(fs.small(packageJson.file,BackupLimits.MANIFEST),BackupLimits.MANIFEST);if(!name.equals(metadata.get("name")))throw new IOException("LEGACY_PLUGIN_NAME_MISMATCH");
    }
}
