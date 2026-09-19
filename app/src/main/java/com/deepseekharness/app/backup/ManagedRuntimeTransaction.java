package com.deepseekharness.app.backup;

import com.deepseekharness.app.util.ManagedRuntimeLayout;
import java.io.*;
import java.util.*;

/** 受管更新复用宿主多根事务。用户目录不进入切换清单，健康验证结束前不提交。 */
public final class ManagedRuntimeTransaction implements HostDataTransaction.Targets {
    public static final String HOME="host-runtime-operations";
    private final BackupFileSystem fs;
    private final File files,directory;
    private final Map<String,String> paths=new LinkedHashMap<>();
    private Map<String,Object> mapping;
    private final HostDataTransaction transaction;
    private static final HostDataTransaction.Settings UNCHANGED=new HostDataTransaction.Settings(){
        public Map<String,Object> current(){return Collections.emptyMap();}
        public void apply(Map<String,Object> value)throws IOException{if(!value.isEmpty())throw new IOException("RUNTIME_SETTINGS_FORBIDDEN");}
    };
    public interface HealthCheck { Map<String,Object> verify()throws IOException; }
    private ManagedRuntimeTransaction(BackupFileSystem fs,File files,File directory,HostDataTransaction.Fault fault)throws IOException{
        this.fs=fs;this.files=files.getAbsoluteFile();this.directory=directory;
        File log=fs.child(directory,"runtime-mapping.json");
        if(!fs.stat(log).type.equals("MISSING")){
            mapping=BackupJson.read(fs.small(log,BackupLimits.MANIFEST),BackupLimits.MANIFEST);
            if(BackupJson.number(mapping,"version")!=1||!directory.getName().equals(BackupJson.string(mapping,"id")))throw new IOException("RUNTIME_MAPPING");
            Object records=mapping.get("paths");if(!(records instanceof Map)||((Map<?,?>)records).isEmpty()||((Map<?,?>)records).size()>2048)throw new IOException("RUNTIME_MAPPING");
            Set<String> unique=new HashSet<>();for(var entry:((Map<?,?>)records).entrySet()){
                if(!(entry.getKey() instanceof String)||!((String)entry.getKey()).matches("runtime-[0-9]{1,4}")||!(entry.getValue() instanceof String))throw new IOException("RUNTIME_MAPPING");
                String path=(String)entry.getValue();if(!ManagedRuntimeLayout.allowed(path)||path.equals("linux/.runtime-health.json")||!unique.add(path))throw new IOException("RUNTIME_MAPPING");paths.put((String)entry.getKey(),path);
            }
            new RuntimeDescriptor(object(mapping,"candidateDescriptor"));
            if(BackupJson.number(mapping,"createdAt")<=0)throw new IOException("RUNTIME_MAPPING");
        }
        transaction=new HostDataTransaction(fs,directory,this,UNCHANGED,fault);
    }
    public static ManagedRuntimeTransaction create(BackupFileSystem fs,File files)throws IOException{
        return create(fs,files,null);
    }
    public static ManagedRuntimeTransaction create(BackupFileSystem fs,File files,HostDataTransaction.Fault fault)throws IOException{
        File home=fs.child(files,HOME);if(fs.stat(home).type.equals("MISSING"))fs.directory(home);
        if(!pending(fs,files).isEmpty())throw new IOException("RUNTIME_RECOVERY_REQUIRED");
        if(fs.list(home).size()>=32)throw new IOException("RUNTIME_RETENTION_LIMIT");
        File entry=fs.child(home,UUID.randomUUID().toString());fs.directory(entry);fs.directory(new File(entry,"candidate"));fs.directory(new File(entry,"stage"));
        return new ManagedRuntimeTransaction(fs,files,entry,fault);
    }
    public File directory(){return directory;}
    public File stage(){return new File(directory,"stage");}
    public static ManagedRuntimeTransaction open(BackupFileSystem fs,File files,String id,HostDataTransaction.Fault fault)throws IOException{
        if(!id.matches("[a-f0-9]{8}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{12}"))throw new IOException("RUNTIME_OPERATION_ID");
        return new ManagedRuntimeTransaction(fs,files,fs.child(files,HOME+"/"+id),fault);
    }
    @Override public File resolve(String root)throws IOException{
        String path=paths.get(root);if(path==null)throw new IOException("UNKNOWN_RUNTIME_ROOT");
        return fs.child(files,path);
    }
    @SuppressWarnings("unchecked") private static Map<String,Object> object(Map<String,Object> value,String key)throws IOException{
        if(!(value.get(key) instanceof Map))throw new IOException("RUNTIME_MAPPING");return (Map<String,Object>)value.get(key);
    }
    public void prepare(List<String> managed,RuntimeDescriptor candidate,RuntimeDescriptor previous,Map<String,Object> previousHealth,BackupControl control)throws IOException{
        if(mapping!=null||managed.isEmpty()||managed.size()>2048)throw new IOException("RUNTIME_PLAN");Set<String> unique=new HashSet<>();
        for(String path:managed){if(path.equals("linux/.runtime-health.json"))continue;
            if(!ManagedRuntimeLayout.allowed(path)||!unique.add(path))throw new IOException("RUNTIME_PATH_FORBIDDEN");paths.put("runtime-"+paths.size(),path);}
        Map<String,String> before=new LinkedHashMap<>();
        for(var entry:paths.entrySet()){
            fs.parents(files,entry.getValue());File source=fs.child(stage(),entry.getValue());
            before.put(entry.getKey(),BackupTree.digest(fs,resolve(entry.getKey()),control));fs.move(source,transaction.candidate(entry.getKey()));
        }
        // 旧回执绑定的是当时通过试运行的字节，不把后续用户修改过的受管目录冒充健康副本。
        if(!previousBytesVerified(previousHealth,before))previousHealth=null;
        saveMapping(candidate,previous,previousHealth,"");
        transaction.prepare(new ArrayList<>(paths.keySet()),before,Collections.emptyMap(),control);
    }
    private boolean previousBytesVerified(Map<String,Object> receipt,Map<String,String> before)throws IOException{
        if(receipt==null||!(receipt.get("managedHashes") instanceof Map))return false;
        Map<?,?> expected=(Map<?,?>)receipt.get("managedHashes");if(expected.isEmpty()||!paths.values().containsAll(expected.keySet()))return false;
        for(var path:paths.entrySet())if(!fs.stat(resolve(path.getKey())).type.equals("MISSING")&&!before.get(path.getKey()).equals(expected.get(path.getValue())))return false;
        return true;
    }
    private void saveMapping(RuntimeDescriptor candidate,RuntimeDescriptor previous,Map<String,Object> previousHealth,String source)throws IOException{
        mapping=new LinkedHashMap<>();mapping.put("version",1L);mapping.put("id",directory.getName());mapping.put("paths",paths);
        mapping.put("candidateDescriptor",candidate.json());mapping.put("previousDescriptor",previous==null?Collections.emptyMap():previous.json());
        mapping.put("previousHealth",previousHealth==null?Collections.emptyMap():previousHealth);mapping.put("source",source);mapping.put("createdAt",System.currentTimeMillis());
        try(OutputStream out=fs.create(fs.child(directory,"runtime-mapping.json"))){out.write(BackupJson.write(mapping,BackupLimits.MANIFEST));}fs.syncDirectory(directory);
    }
    public void commit(BackupControl control,HealthCheck check,java.util.function.BooleanSupplier mayRecover)throws IOException{
        RuntimeDescriptor expected=new RuntimeDescriptor(object(mapping,"candidateDescriptor"));
        transaction.commit(control,new HostDataTransaction.CommitCheck(){
            public void verify()throws IOException{
                Map<String,Object> proof=check.verify();if(!RuntimeDescriptor.healthy(proof,expected.id()))throw new IOException("RUNTIME_HEALTH_INCOMPLETE");
                try(OutputStream out=fs.create(fs.child(directory,"health.json"))){out.write(BackupJson.write(proof,512*1024));}fs.syncDirectory(directory);
            }
            public boolean mayRecover(){return mayRecover.getAsBoolean();}
        });
    }
    public void recover()throws IOException{transaction.recover();}
    public static List<ManagedRuntimeTransaction> pending(BackupFileSystem fs,File files)throws IOException{
        List<ManagedRuntimeTransaction> result=new ArrayList<>();for(ManagedRuntimeTransaction item:all(fs,files)){
            if(item.marker("switching")&&!item.marker("finalized")&&!item.marker("rolled-back"))result.add(item);
        }if(result.size()>1)throw new IOException("MULTIPLE_RUNTIME_TRANSACTIONS");return result;
    }
    public static boolean blocked(File files){try{return !pending(new AndroidBackupFileSystem(),files.getCanonicalFile()).isEmpty();}catch(IOException error){return true;}}
    private static List<ManagedRuntimeTransaction> all(BackupFileSystem fs,File files)throws IOException{
        File home=fs.child(files,HOME);if(fs.stat(home).type.equals("MISSING"))return Collections.emptyList();
        List<String> children=fs.list(home);if(children.size()>64)throw new IOException("RUNTIME_RETENTION_LIMIT");List<ManagedRuntimeTransaction> result=new ArrayList<>();
        for(String id:children){if(!id.matches("[a-f0-9]{8}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{12}"))throw new IOException("RUNTIME_OPERATION_ID");result.add(new ManagedRuntimeTransaction(fs,files,fs.child(home,id),null));}
        return result;
    }
    private boolean marker(String name)throws IOException{
        File file=fs.child(directory,name);if(fs.stat(file).type.equals("MISSING"))return false;
        if(!(directory.getName()+"\n"+name+"\n").equals(new String(fs.small(file,256),java.nio.charset.StandardCharsets.UTF_8)))throw new IOException("RUNTIME_MARKER");return true;
    }
    private void markerWrite(String name)throws IOException{try(OutputStream out=fs.create(fs.child(directory,name))){out.write((directory.getName()+"\n"+name+"\n").getBytes(java.nio.charset.StandardCharsets.UTF_8));}fs.syncDirectory(directory);}
    private RuntimeDescriptor previousDescriptor()throws IOException{
        Map<String,Object> value=object(mapping,"previousDescriptor");return value.isEmpty()?null:new RuntimeDescriptor(value);
    }
    public boolean hasHealthyPrevious()throws IOException{
        if(mapping==null||!marker("finalized")||marker("previous-cleaned"))return false;RuntimeDescriptor previous=previousDescriptor();
        return previous!=null&&RuntimeDescriptor.healthy(object(mapping,"previousHealth"),previous.id());
    }
    public static final class RollbackOption {
        public final String operationId,dshVersion,runtimeId;
        public final long createdAt;
        private RollbackOption(ManagedRuntimeTransaction source,RuntimeDescriptor previous)throws IOException{
            operationId=source.directory.getName();runtimeId=previous.id();dshVersion=BackupJson.string(previous.json(),"dshVersion");createdAt=BackupJson.number(source.mapping,"createdAt");
        }
    }
    private boolean canRollback(RuntimeDescriptor expected,RuntimeDescriptor current)throws IOException{
        if(current==null||!hasHealthyPrevious())return false;
        RuntimeDescriptor candidate=new RuntimeDescriptor(object(mapping,"candidateDescriptor")),previous=previousDescriptor();
        // 一次只回退直接前代，确保清单包含当前版本新增的受管文件；不恢复任何用户数据快照。
        return candidate.id().equals(current.id())&&previous.compatible(expected)&&previous.canReadDataWrittenBy(current);
    }
    public static List<RollbackOption> rollbackOptions(BackupFileSystem fs,File files,RuntimeDescriptor expected,RuntimeDescriptor current)throws IOException{
        if(!pending(fs,files).isEmpty())throw new IOException("RUNTIME_RECOVERY_REQUIRED");List<RollbackOption> result=new ArrayList<>();
        for(var item:all(fs,files))if(item.canRollback(expected,current))result.add(new RollbackOption(item,item.previousDescriptor()));
        result.sort((a,b)->Long.compare(b.createdAt,a.createdAt));return result;
    }
    @SuppressWarnings("unchecked") public static ManagedRuntimeTransaction prepareRollback(BackupFileSystem fs,File files,String sourceId,
            RuntimeDescriptor expected,RuntimeDescriptor current,Map<String,Object> currentHealth,BackupControl control)throws IOException{
        ManagedRuntimeTransaction source=open(fs,files,sourceId,null);if(!source.canRollback(expected,current))throw new IOException("RUNTIME_ROLLBACK_INCOMPATIBLE");
        Map<String,Object> plan=BackupJson.read(fs.small(fs.child(source.directory,"plan.json"),BackupLimits.MANIFEST),BackupLimits.MANIFEST);
        if(!sourceId.equals(BackupJson.string(plan,"id"))||!(plan.get("roots") instanceof List))throw new IOException("RUNTIME_PLAN");
        List<?> rows=(List<?>)plan.get("roots");if(rows.size()!=source.paths.size())throw new IOException("RUNTIME_PLAN");
        Set<String> checked=new HashSet<>();long bytes=0;
        for(Object object:rows){if(!(object instanceof Map))throw new IOException("RUNTIME_PLAN");Map<String,Object> row=(Map<String,Object>)object;
            String id=BackupJson.string(row,"root");if(!source.paths.containsKey(id)||!checked.add(id)||!(row.get("had") instanceof Boolean))throw new IOException("RUNTIME_PLAN");
            File old=fs.child(source.directory,"previous/"+id);if(!BackupJson.string(row,"before").equals(BackupTree.digest(fs,old,control)))throw new IOException("RETAINED_RUNTIME_CHANGED");
            bytes=BackupLimits.add(bytes,treeBytes(fs,old,control,0,new int[]{0}),BackupLimits.BYTES);
        }
        if(files.getUsableSpace()<bytes+64L*1024*1024)throw new IOException("NO_SPACE");
        ManagedRuntimeTransaction next=create(fs,files);next.paths.putAll(source.paths);Map<String,String> before=new LinkedHashMap<>();Set<String> removals=new HashSet<>();
        for(Object object:rows){Map<String,Object> row=(Map<String,Object>)object;String id=BackupJson.string(row,"root");
            fs.parents(files,next.paths.get(id));before.put(id,BackupTree.digest(fs,next.resolve(id),control));
            File old=fs.child(source.directory,"previous/"+id);
            if(Boolean.TRUE.equals(row.get("had")))BackupTree.copy(fs,old,next.transaction.candidate(id),control);else removals.add(id);
        }
        if(!next.previousBytesVerified(currentHealth,before))currentHealth=null;
        next.saveMapping(source.previousDescriptor(),current,currentHealth,sourceId);
        next.transaction.prepare(new ArrayList<>(next.paths.keySet()),before,Collections.emptyMap(),control,removals);return next;
    }
    private static long treeBytes(BackupFileSystem fs,File file,BackupControl control,int depth,int[] count)throws IOException{
        control.check();if(depth>BackupLimits.DEPTH||++count[0]>BackupLimits.ENTRIES)throw new IOException("RUNTIME_LIMIT");var node=fs.stat(file);
        if(node.type.equals("FILE"))return node.size;if(!node.type.equals("DIRECTORY"))return 0;long result=0;
        for(String name:fs.list(file))result=BackupLimits.add(result,treeBytes(fs,fs.child(file,name),control,depth+1,count),BackupLimits.BYTES);return result;
    }
    public static void trimOlder(BackupFileSystem fs,File files,RuntimeDescriptor active,Map<String,Object> health)throws IOException{
        if(!RuntimeDescriptor.healthy(health,active.id())||!pending(fs,files).isEmpty())return;
        List<ManagedRuntimeTransaction> healthy=new ArrayList<>();for(var item:all(fs,files))if(item.hasHealthyPrevious()){
            // 旧读写格式不兼容时不能回退，但不应因此永久保留所有旧系统组件。
            // 清理只处理有健康记录、逐根摘要仍与切换前一致的受管树。
            // 回执只证明当时的内容。计入可保留/清理的数量前，重新核对现有原件。
            try{if(item.retainedBytesUnchanged())healthy.add(item);}catch(IOException retained){/* 无法核验时保留原件。 */}
        }
        healthy.sort((a,b)->Long.compare(((Number)b.mapping.get("createdAt")).longValue(),((Number)a.mapping.get("createdAt")).longValue()));
        // 最近两份已验证旧运行时保留；未知/失败原件永远不作为自动清理对象。
        for(int i=2;i<healthy.size();i++){var item=healthy.get(i);if(!item.retainedBytesUnchanged())continue;fs.removeOwned(item.directory,"previous");item.markerWrite("previous-cleaned");}
    }
    private boolean retainedBytesUnchanged()throws IOException{
        Map<String,Object> plan=BackupJson.read(fs.small(fs.child(directory,"plan.json"),BackupLimits.MANIFEST),BackupLimits.MANIFEST);
        if(!directory.getName().equals(plan.get("id"))||!(plan.get("roots") instanceof List))return false;
        List<?> rows=(List<?>)plan.get("roots");if(rows.size()!=paths.size())return false;
        File previous=fs.child(directory,"previous");if(!fs.stat(previous).type.equals("DIRECTORY"))return false;
        Set<String> checked=new HashSet<>();BackupControl control=new BackupControl(null);
        for(Object value:rows){if(!(value instanceof Map))return false;Map<?,?> row=(Map<?,?>)value;
            if(!(row.get("root") instanceof String))return false;String id=(String)row.get("root");
            if(!paths.containsKey(id)||!checked.add(id)||!(row.get("before") instanceof String))return false;
            if(!row.get("before").equals(BackupTree.digest(fs,fs.child(previous,id),control)))return false;
        }
        // 清单外的文件可能是用户唯一原件，不能随整个 previous 目录一起删除。
        return checked.containsAll(fs.list(previous));
    }
}
