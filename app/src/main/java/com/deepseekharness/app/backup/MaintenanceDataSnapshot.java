package com.deepseekharness.app.backup;

import com.deepseekharness.app.util.ManagedRuntimeLayout;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** 本机环境重建的数据副本：保留原有目录关系，不归档 Ubuntu/Node 系统树。 */
public final class MaintenanceDataSnapshot {
    private static final Set<String> SYSTEM=Set.of("bin","sbin","lib","lib64","usr","etc","var","tmp","run","proc","dev","sys","root",".l2s","lost+found","sdcard","storage","system","apex");
    private static final Set<String> EXTERNAL=Set.of("proc","dev","sys","sdcard","storage","system","apex");
    private static final Set<String> HOT=Set.of("sessions","storages","attachments","settings.yaml");
    private final BackupFileSystem fs;private final File files,rootfs,task,publicRoot;private final GuestDataResolver resolver;
    private final List<BackupSource> sources=new ArrayList<>();private final Map<String,Object> destinations=new LinkedHashMap<>();
    private final Map<File,String> registries=new LinkedHashMap<>();
    public MaintenanceDataSnapshot(BackupFileSystem fs,File files,File task,File publicRoot){this.fs=fs;this.files=files;this.task=task;this.publicRoot=publicRoot;rootfs=new File(files,"linux/ubuntu");
        resolver=new GuestDataResolver(fs,rootfs,publicRoot,List.of(new File(files,"user-data-v5"),new File(publicRoot,"Documents/dshdata")));}
    public File archive(){return new File(task,"data.dshdata");}
    public File mapping(){return new File(task,"data-roots.json");}
    private static String id(String relative){return "local-"+NativeDataLocations.hash(relative);}
    private static String join(String base,String child){return child.isEmpty()?base:base+"/"+child;}
    private static boolean placeholder(String path){return path.matches("data/(?:data|user(?:_de)?/[0-9]+)/com\\.dsh\\.client/files/linux/ubuntu/\\.l2s(?:/.*)?");}
    private boolean managed(String path){return ManagedRuntimeLayout.paths().stream().anyMatch(root->path.equals(root)||path.startsWith(root+"/"));}
    private boolean machine(String relative){String leaf=new File(relative).getName();
        String checkpoints="root/.dsh/dsha-startup-checkpoints";
        if(relative.equals(checkpoints))return false;
        if(relative.startsWith(checkpoints+"/"))return !relative.substring(checkpoints.length()+1).matches("(?:healthy|before)-[1-3]\\.json");
        String parent=relative.contains("/")?relative.substring(0,relative.lastIndexOf('/')):"";
        return (parent.equals("root")||parent.equals("root/.dsh"))&&DataRootPolicy.machine(leaf)
            ||parent.equals("root")&&leaf.matches("\\.dsha-(?:backup|maintenance|personal)(?:-input)?-[a-f0-9-]{36}\\.(?:tar\\.gz|json)(?:\\.part)?")
            ||relative.equals("root/.dsha-bundled-tools")||relative.equals("root/.dsha-bundled-before-maintenance");}
    private void add(String target,File source,boolean omitHot,boolean preserveOnly)throws IOException{
        add(target,source,omitHot,preserveOnly,false);
    }
    private void add(String target,File source,boolean omitHot,boolean preserveOnly,boolean materialize)throws IOException{
        BackupLimits.path(target);String id=id(target);if(destinations.containsKey(id))return;
        var actual=materialize?resolver.resolve(source):new GuestDataResolver.Resolved(source.getAbsoluteFile(),"DIRECT");boolean outside=!GuestDataResolver.within(rootfs,actual.file);
        if(fs.stat(actual.file).type.equals("MISSING"))throw new IOException("MAINTENANCE_SOURCE_MISSING");
        sources.add(new FileBackupSource(fs,id,"application",actual.file,outside,new FileBackupSource.Policy(){
            public String before(String relative){return placeholder(join(target,relative))?"EXTERNAL_MOUNT_PLACEHOLDER":"";}
            public String exclusion(String relative,BackupFileSystem.Node node){
                String full=join(target,relative);if(omitHot&&!relative.isEmpty()&&HOT.contains(relative.split("/",2)[0]))return "HOT_DATA_SEPARATE";
                return machine(full)?"MACHINE_STATE_NOT_MIGRATED":"";
            }
        },resolver){
            @Override protected File locate(String relative)throws IOException{
                if(materialize){var current=resolver.resolve(source);if(!current.file.equals(actual.file)||!current.proof.equals(actual.proof))throw new IOException("DATA_LOCATION_CHANGED");}
                return super.locate(relative);
            }
        });
        destinations.put(id,Map.of("target",target,"preservedOnly",preserveOnly,"external",outside,"sourceProof",actual.proof));
    }
    private void select(String workdir,BackupControl control)throws IOException{
        var rootState=fs.stat(rootfs);if(rootState.type.equals("MISSING"))return;if(!rootState.type.equals("DIRECTORY"))throw new IOException("MAINTENANCE_ROOT_TYPE");
        UserDataLayout layout=new UserDataLayout(fs,files);File data=layout.current();
        // 宿主持久根不随 linux 替换，保持原位；旧 guest 根按数据目录逐项保护。
        if(layout.selected()==UserDataLayout.Home.LEGACY){
            var state=fs.stat(data);
            if(!state.type.equals("MISSING")){
                add("root/.dsh",data,true,false,true);
                File actual=resolver.resolve(data).file;
                for(String name:HOT)if(!fs.stat(new File(actual,name)).type.equals("MISSING"))add("root/.dsh/"+name,new File(data,name),false,false,true);
            }
        }
        File home=new File(rootfs,"root");if(fs.stat(home).type.equals("DIRECTORY"))for(String name:fs.list(home)){
            control.check();if(name.equals(".dsh"))continue;String relative="root/"+name;
            add(relative,new File(home,name),false,managed(relative));
        }
        for(String name:fs.list(rootfs)){control.check();if(!SYSTEM.contains(name))add(name,new File(rootfs,name),false,false);}
        File global=new File(rootfs,"usr/local/lib/node_modules");
        if(fs.stat(global).type.equals("DIRECTORY"))for(String name:fs.list(global)){
            if(name.equals("npm"))continue;File directory=new File(global,name);
            if(name.startsWith("@")&&fs.stat(directory).type.equals("DIRECTORY"))for(String child:fs.list(directory))global("usr/local/lib/node_modules/"+name+"/"+child);
            else global("usr/local/lib/node_modules/"+name);
        }
        List<String> workspaces=new ArrayList<>();if(workdir!=null&&!workdir.isEmpty())workspaces.add(workdir.startsWith("/")?workdir:"/root/"+workdir);
        File registry=resolver.resolve(new File(data,"storages/workspace.json")).file;
        if(fs.stat(registry).type.equals("FILE")){
            var metadata=registry(registry);Object tables=metadata.get("tables"),rows=tables instanceof Map?((Map<?,?>)tables).get("workspaces"):null;
            if(rows instanceof Map)for(Object row:((Map<?,?>)rows).values())if(row instanceof Map&&((Map<?,?>)row).get("path") instanceof String)workspaces.add((String)((Map<?,?>)row).get("path"));
        }
        File records=resolver.resolve(new File(data,"storages/workspace/workspaces")).file;
        if(fs.stat(records).type.equals("DIRECTORY"))for(String name:fs.list(records)){
            control.check();if(!name.endsWith(".json"))continue;var row=registry(fs.child(records,name));
            Object record=row.get("record");if(record instanceof Map&&((Map<?,?>)record).get("path") instanceof String)workspaces.add((String)((Map<?,?>)record).get("path"));
            if(workspaces.size()>20000)throw new IOException("WORKSPACE_LIMIT");
        }
        for(String path:workspaces){
            control.check();if(!path.startsWith("/"))throw new IOException("WORKSPACE_PATH");String relative=path.substring(1);BackupLimits.path(relative);if(relative.isEmpty())throw new IOException("WORKSPACE_SYSTEM_ROOT");
            String first=relative.split("/",2)[0];if(EXTERNAL.contains(first)||relative.equals("root"))continue;
            if(relative.equals("usr/local")||relative.equals("usr/local/lib")||relative.equals("usr/local/lib/node_modules")||relative.equals("var/lib")||SYSTEM.contains(relative))throw new IOException("WORKSPACE_SYSTEM_ROOT");
            if(destinations.values().stream().anyMatch(value->{String existing=(String)((Map<?,?>)value).get("target");return relative.equals(existing)||relative.startsWith(existing+"/");}))continue;
            if(!fs.stat(new File(rootfs,relative)).type.equals("MISSING"))add(relative,new File(rootfs,relative),false,managed(relative));
        }
    }
    private Map<String,Object> registry(File file)throws IOException{
        byte[] bytes=fs.small(file,BackupLimits.MANIFEST);registries.put(file,BackupArchive.hex(BackupArchive.sha().digest(bytes)));return BackupJson.read(bytes,BackupLimits.MANIFEST);
    }
    private void global(String relative)throws IOException{
        if(relative.equals("usr/local/lib/node_modules/@deepseek-ai/dsh"))return;File source=new File(rootfs,relative);
        if(fs.stat(source).type.equals("LINK")){
            try{var actual=resolver.resolve(source).file;if(GuestDataResolver.within(new File(rootfs,ManagedRuntimeLayout.DSH),actual))return;}
            catch(IOException unknown){ /* 只保存链接本身，不为了维护读取未授权目标。 */ }
        }
        add(relative,source,false,false);
    }
    public Map<String,Object> capture(String workdir,String appVersion,BackupControl control)throws IOException{
        select(workdir,control);
        // 本机凭据与设备授权原位保留，不受手动导出 API Key 选项影响。
        sources.add(new ValueBackupSource("native-in-place","settings",()->"{\"nativeState\":\"PRESERVED_IN_PLACE\"}".getBytes(StandardCharsets.UTF_8)));
        destinations.put("native-in-place",Map.of("target","","preservedOnly",true,"external",false,"sourceProof",""));
        Map<String,Object> provenance=new LinkedHashMap<>();provenance.put("operation","MAINTENANCE");provenance.put("createdAt",System.currentTimeMillis());provenance.put("appVersion",appVersion);
        provenance.put("runtime",com.deepseekharness.app.util.Constants.DSH_VERSION);provenance.put("dataFormat","LOCAL_ORIGINAL_LAYOUT");provenance.put("sensitivePolicy","PRIVATE_LOCAL_NATIVE_STATE_IN_PLACE");provenance.put("plugins",Map.of("layout","original-tree","executed",false));
        var summary=HostSnapshot.create(fs,sources,task,archive(),provenance,false,control,check->{
            for(var record:registries.entrySet()){check.check();try(InputStream input=fs.read(record.getKey(),fs.stat(record.getKey()))){if(!record.getValue().equals(BackupArchive.digest(input,check)))throw new IOException("WORKSPACE_REGISTRY_CHANGED");}}
        });
        try(InputStream in=fs.read(archive(),fs.stat(archive()))){BackupArchive.read(in,null,control);}
        String hash;try(InputStream in=fs.read(archive(),fs.stat(archive()))){hash=BackupArchive.digest(in,control);}
        try(OutputStream out=fs.create(mapping())){out.write(BackupJson.write(Map.of("version",1L,"archiveSha256",hash,"roots",destinations),BackupLimits.MANIFEST));}
        return summary;
    }
    @SuppressWarnings("unchecked") public void restore(File newRoot,BackupControl control)throws IOException{
        Map<String,Object> map=BackupJson.read(fs.small(mapping(),BackupLimits.MANIFEST),BackupLimits.MANIFEST);
        if(BackupJson.number(map,"version")!=1||!(map.get("roots") instanceof Map))throw new IOException("MAINTENANCE_DATA_MAPPING");
        String hash;try(InputStream in=fs.read(archive(),fs.stat(archive()))){hash=BackupArchive.digest(in,control);}if(!hash.equals(map.get("archiveSha256")))throw new IOException("MAINTENANCE_DATA_CHECKSUM");
        Map<String,Object> roots=(Map<String,Object>)map.get("roots");
        File bundled=new File(task,"bundled-data-links");if(fs.stat(bundled).type.equals("MISSING"))fs.directory(bundled);
        for(String name:HOT)if(roots.containsKey(id("root/.dsh/"+name))){
            File target=new File(newRoot,"root/.dsh/"+name);if(fs.stat(target).type.equals("LINK"))fs.move(target,fs.child(bundled,name));
        }
        try(InputStream in=fs.read(archive(),fs.stat(archive()))){BackupArchive.read(in,new BackupArchive.Visitor(){
            public OutputStream payload(int ordinal,BackupArchive.Record record)throws IOException{
                var target=target(record);if(target==null||!record.kind.equals("FILE"))return null;
                var current=fs.stat(target);if(current.type.equals("DIRECTORY"))throw new IOException("MAINTENANCE_DATA_CONFLICT");if(!current.type.equals("MISSING"))fs.delete(target);return fs.create(target);
            }
            private File target(BackupArchive.Record record)throws IOException{
                Object row=roots.get(record.root);if(!(row instanceof Map))throw new IOException("MAINTENANCE_DATA_MAPPING");Map<String,Object> mapping=(Map<String,Object>)row;
                if(Boolean.TRUE.equals(mapping.get("preservedOnly"))||record.kind.equals("EXCLUDED")||record.kind.equals("MISSING")||record.kind.equals("UNREADABLE"))return null;
                String base=BackupJson.string(mapping,"target"),relative=record.path.isEmpty()?base:base+"/"+record.path;BackupLimits.path(relative);
                if(!id(base).equals(record.root))throw new IOException("MAINTENANCE_DATA_MAPPING");
                if(relative.isEmpty()||managed(relative)||placeholder(relative)||machine(relative))return null;
                fs.parents(newRoot,relative);return fs.child(newRoot,relative);
            }
            public void verified(int ordinal,BackupArchive.Record record)throws IOException{
                File target=target(record);if(target==null)return;var existing=fs.stat(target);
                if(record.kind.equals("DIRECTORY")){if(existing.type.equals("MISSING"))fs.directory(target);else if(!existing.type.equals("DIRECTORY"))throw new IOException("MAINTENANCE_DATA_CONFLICT");}
                else if(record.kind.equals("LINK")){
                    if(existing.type.equals("DIRECTORY")){if(!fs.list(target).isEmpty())throw new IOException("MAINTENANCE_DATA_CONFLICT");fs.delete(target);}
                    else if(!existing.type.equals("MISSING"))fs.delete(target);fs.symlink(BackupJson.string(record.metadata,"target"),target);
                }else if(record.kind.equals("FILE")){
                    try(InputStream data=fs.read(target,fs.stat(target))){if(!record.sha256.equals(BackupArchive.digest(data,control)))throw new IOException("MAINTENANCE_DATA_CHECKSUM");}
                    if(record.metadata.get("mode") instanceof Number)fs.mode(target,((Number)record.metadata.get("mode")).intValue());
                }
            }
        },control);}
    }
}
