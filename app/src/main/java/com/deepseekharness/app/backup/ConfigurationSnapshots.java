package com.deepseekharness.app.backup;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** 宿主六文件检查点。正文逐文件保存，日志在 rootfs 外；不读取会话、工作区或 Keystore。 */
public final class ConfigurationSnapshots {
    public static final List<String> FILES=List.of("profiles/web/package.json","profiles/web/cordis.patch.yml","profiles/web/pnpm-workspace.yaml",
            "profiles/web/pnpm-lock.yaml","settings.yaml","cordis.patch.yml");
    public static final String STORE="startup-config-snapshots",OPERATIONS="startup-config-operations";
    private static final int LIMIT=LegacyConfigurationSnapshot.FILE_LIMIT;
    private final BackupFileSystem fs;private final File files,dsh,store,operations;
    private final HostDataTransaction.Fault fault;
    private static final HostDataTransaction.Settings NATIVE_UNCHANGED=new HostDataTransaction.Settings(){
        public Map<String,Object> current(){return Collections.emptyMap();}public void apply(Map<String,Object> values)throws IOException{if(!values.isEmpty())throw new IOException("RECOVERY_NATIVE_SETTINGS");}
    };
    public ConfigurationSnapshots(BackupFileSystem fs,File files,File dsh){this(fs,files,dsh,null);}
    public ConfigurationSnapshots(BackupFileSystem fs,File files,File dsh,HostDataTransaction.Fault fault){this.fs=fs;this.files=files;this.dsh=dsh;this.fault=fault;store=new File(files,STORE);operations=new File(files,OPERATIONS);}
    private static String uuid(String value)throws IOException{if(value==null||!value.matches("[a-f0-9]{8}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{12}"))throw new IOException("RECOVERY_ID");return value;}
    private void directory(File file)throws IOException{var state=fs.stat(file);if(state.type.equals("MISSING"))fs.directory(file);else if(!state.type.equals("DIRECTORY"))throw new IOException("RECOVERY_LINK");}
    private void ensureStore()throws IOException{directory(store);directory(operations);}
    private File document(String relative)throws IOException{
        if(!FILES.contains(relative))throw new IOException("RECOVERY_FORMAT");File cursor=dsh;
        String[] parts=relative.split("/");
        for(int i=0;i<parts.length;i++){
            var parent=fs.stat(cursor);if(parent.type.equals("MISSING"))return new File(dsh,relative);
            if(!parent.type.equals("DIRECTORY"))throw new IOException("RECOVERY_LINK");cursor=new File(cursor,parts[i]);
        }return cursor;
    }
    private byte[] readData(String name)throws IOException{
        File file=document(name);var state=fs.stat(file);if(state.type.equals("MISSING"))return null;
        if(!state.type.equals("FILE"))throw new IOException("RECOVERY_LINK");if(state.size>LIMIT)throw new IOException("RECOVERY_SIZE");return fs.small(file,LIMIT);
    }
    static Map<String,Object> entry(byte[] bytes){return bytes==null?Map.of("present",false,"size",0L,"sha256",""):
            Map.of("present",true,"size",(long)bytes.length,"sha256",BackupArchive.hex(BackupArchive.sha().digest(bytes)));}
    private Map<String,Object> signatures(BackupControl control)throws IOException{
        if(!fs.stat(dsh).type.equals("DIRECTORY"))throw new IOException("RECOVERY_DATA_UNAVAILABLE");
        Map<String,Object> result=new LinkedHashMap<>();for(String name:FILES){control.check();result.put(name,entry(readData(name)));}return result;
    }
    private Map<String,Object> capture(String startup,BackupControl control)throws IOException{
        ensureStore();if(fs.list(store).size()>72)throw new IOException("RECOVERY_RETENTION_LIMIT");
        if(!fs.stat(dsh).type.equals("DIRECTORY"))throw new IOException("RECOVERY_DATA_UNAVAILABLE");
        String id=UUID.randomUUID().toString();File folder=fs.child(store,id);fs.directory(folder);Map<String,Object> content=new LinkedHashMap<>();
        for(int i=0;i<FILES.size();i++){control.check();byte[] bytes=readData(FILES.get(i));content.put(FILES.get(i),entry(bytes));
            if(bytes!=null)try(OutputStream out=fs.create(fs.child(folder,String.valueOf(i)))){out.write(bytes);}}
        if(!content.equals(signatures(control)))throw new IOException("RECOVERY_CHANGED");
        Map<String,Object> doc=new LinkedHashMap<>();doc.put("version",2L);doc.put("id",id);doc.put("created",System.currentTimeMillis());doc.put("dshVersion",com.deepseekharness.app.util.Constants.DSH_VERSION);doc.put("files",content);
        if(startup!=null)doc.put("startupId",startup);
        try(OutputStream out=fs.create(fs.child(folder,"metadata.json"))){out.write(BackupJson.write(doc,16384));}fs.syncDirectory(folder);fs.syncDirectory(store);return load(id,control);
    }
    @SuppressWarnings("unchecked") private Map<String,Object> entries(Map<String,Object> doc)throws IOException{
        if(!(doc.get("files") instanceof Map))throw new IOException("RECOVERY_FORMAT");Map<String,Object> records=(Map<String,Object>)doc.get("files");
        if(!records.keySet().equals(new HashSet<>(FILES)))throw new IOException("RECOVERY_FORMAT");
        for(Object value:records.values()){
            if(!(value instanceof Map))throw new IOException("RECOVERY_FORMAT");Map<String,Object> row=(Map<String,Object>)value;
            long size=BackupJson.number(row,"size");String hash=BackupJson.string(row,"sha256");if(!(row.get("present") instanceof Boolean)||size<0||size>LIMIT
                    ||Boolean.TRUE.equals(row.get("present"))&&!hash.matches("[a-f0-9]{64}")||Boolean.FALSE.equals(row.get("present"))&&(size!=0||!hash.isEmpty()))throw new IOException("RECOVERY_FORMAT");
        }return records;
    }
    private Map<String,Object> load(String id,BackupControl control)throws IOException{
        File folder=fs.child(store,uuid(id));Map<String,Object> doc=BackupJson.read(fs.small(fs.child(folder,"metadata.json"),16384),16384);
        if(BackupJson.number(doc,"version")!=2||!id.equals(BackupJson.string(doc,"id"))||BackupJson.number(doc,"created")<0)throw new IOException("RECOVERY_FORMAT");
        Map<String,Object> rows=entries(doc);
        for(int i=0;i<FILES.size();i++){
            @SuppressWarnings("unchecked") Map<String,Object> row=(Map<String,Object>)rows.get(FILES.get(i));File file=fs.child(folder,String.valueOf(i));
            if(Boolean.TRUE.equals(row.get("present"))){control.check();byte[] bytes=fs.small(file,LIMIT);if(!entry(bytes).equals(row))throw new IOException("RECOVERY_CHECKSUM");}
            else if(!fs.stat(file).type.equals("MISSING"))throw new IOException("RECOVERY_CHECKSUM");
        }return doc;
    }
    private boolean slot(String value){return value!=null&&(value.equals("candidate")||value.matches("(?:healthy|before)-[1-3]"));}
    private Map<String,Object> readCatalog(File path)throws IOException{
        if(fs.stat(path).type.equals("MISSING"))return new LinkedHashMap<>();Map<String,Object> result=BackupJson.read(fs.small(path,4096),4096);
        for(var entry:result.entrySet())if(!slot(entry.getKey())||!(entry.getValue() instanceof String))throw new IOException("RECOVERY_CATALOG");else uuid((String)entry.getValue());return result;
    }
    private Map<String,Object> catalog()throws IOException{
        if(fs.stat(store).type.equals("MISSING"))return new LinkedHashMap<>();File current=fs.child(store,"catalog.json");
        return readCatalog(fs.stat(current).type.equals("MISSING")?fs.child(store,"catalog.json.previous"):current);
    }
    private void catalog(Map<String,Object> values)throws IOException{
        File previous=fs.child(store,"catalog.json.previous"),current=fs.child(store,"catalog.json");
        if(!fs.stat(previous).type.equals("MISSING")){readCatalog(previous);if(fs.stat(current).type.equals("MISSING"))fs.move(previous,current);else{readCatalog(current);fs.delete(previous);fs.syncDirectory(store);}}
        fs.atomic(store,"catalog.json",BackupJson.write(values,4096));
    }
    private String save(Map<String,Object> doc,String kind,BackupControl control)throws IOException{
        return save(doc,kind,control,Collections.emptySet());
    }
    private String save(Map<String,Object> doc,String kind,BackupControl control,Collection<?> pinned)throws IOException{
        Map<String,Object> index=catalog();String target=null;long oldest=Long.MAX_VALUE;
        for(int i=1;i<=3;i++){String key=kind+"-"+i;long time=index.containsKey(key)?BackupJson.number(load((String)index.get(key),control),"created"):-1;
            if(time<oldest){target=key;oldest=time;}}
        if(target==null)throw new IOException("RECOVERY_CATALOG");index.put(target,doc.get("id"));catalog(index);trim(index,pinned,control);return target;
    }
    private void trim(Map<String,Object> index,BackupControl control)throws IOException{
        trim(index,Collections.emptySet(),control);
    }
    private void trim(Map<String,Object> index,Collection<?> pinned,BackupControl control)throws IOException{
        Set<Object> keep=new HashSet<>(index.values());keep.addAll(pinned);
        for(String id:fs.list(store)){if(!id.matches("[a-f0-9-]{36}")||keep.contains(id))continue;
            // 只有拥有完整元数据且校验成功的本机检查点可以轮换，异常原件留下供救援。
            try{load(id,control);}catch(IOException unreadable){continue;}fs.removeOwned(store,id);}
    }
    private File legacy(String slot)throws IOException{
        if(!slot.matches("(?:healthy|before)-[1-3]|pending"))throw new IOException("RECOVERY_SLOT");
        File root=new File(dsh,"dsha-startup-checkpoints");return new File(root,slot+".json");
    }
    private Map<String,Object> legacy(File file,LegacyConfigurationSnapshot.Data data,BackupControl control)throws IOException{
        // 与历史检查点相同，含链接的配置/检查点路径保持原样并拒绝写入。
        if(!file.getAbsolutePath().startsWith(files.getAbsolutePath()+File.separator))throw new IOException("RECOVERY_LINK");
        File current=files;String relative=file.getAbsolutePath().substring(files.getAbsolutePath().length()+1);
        for(String part:relative.split(java.util.regex.Pattern.quote(File.separator))){current=new File(current,part);if(fs.stat(current).type.equals("LINK"))throw new IOException("RECOVERY_LINK");}
        var state=fs.stat(file);if(!state.type.equals("FILE")||state.size>LegacyConfigurationSnapshot.DOCUMENT_LIMIT)throw new IOException("RECOVERY_SIZE");
        try(InputStream input=fs.read(file,state)){return LegacyConfigurationSnapshot.read(input,data,control);}
    }
    private Map<String,Object> metadata(String slot,Map<String,Object> doc)throws IOException{
        List<String> present=new ArrayList<>(),missing=new ArrayList<>();for(var entry:entries(doc).entrySet()){
            if(Boolean.TRUE.equals(((Map<?,?>)entry.getValue()).get("present")))present.add(entry.getKey());else missing.add(entry.getKey());}
        return Map.of("slot",slot,"id",doc.get("id"),"created",doc.get("created"),"dshVersion",doc.getOrDefault("dshVersion","unknown"),
                "files",present,"missing",missing,"healthy",slot.contains("healthy-"));
    }
    public Map<String,Object> list(BackupControl control)throws IOException{
        List<Map<String,Object>> result=new ArrayList<>();Map<String,Object> index=catalog();
        for(String kind:List.of("healthy","before"))for(int i=1;i<=3;i++){
            String slot=kind+"-"+i;if(index.containsKey(slot))try{result.add(metadata(slot,load((String)index.get(slot),control)));}catch(IOException invalid){result.add(Map.of("slot",slot,"invalid",true));}
            File legacy=legacy(slot);if(!fs.stat(legacy).type.equals("MISSING"))try{result.add(metadata("legacy-"+slot,legacy(legacy,null,control)));}catch(IOException invalid){result.add(Map.of("slot","legacy-"+slot,"invalid",true));}
        }return Map.of("status","ok","snapshots",result,"pending",pending());
    }
    public void prepare(String startup,BackupControl control)throws IOException{
        if(pending())throw new IOException("RECOVERY_PENDING");if(startup==null||startup.length()>128)throw new IOException("RECOVERY_FORMAT");Map<String,Object> doc=capture(startup,control),index=catalog();index.put("candidate",doc.get("id"));catalog(index);trim(index,control);
    }
    public Map<String,Object> healthy(String startup,BackupControl control)throws IOException{
        if(pending())throw new IOException("RECOVERY_PENDING");Map<String,Object> index=catalog();Object id=index.get("candidate");if(!(id instanceof String))throw new IOException("RECOVERY_CHANGED");
        Map<String,Object> doc=load((String)id,control);if(!Objects.equals(startup,doc.get("startupId"))||!entries(doc).equals(signatures(control)))throw new IOException("RECOVERY_CHANGED");
        String slot=save(doc,"healthy",control);return Map.of("status","ok","snapshot",metadata(slot,doc));
    }
    public String before(BackupControl control)throws IOException{return save(capture(null,control),"before",control);}
    private HostDataTransaction transaction(File operation)throws IOException{return new HostDataTransaction(fs,operation,id->{
        if(!id.matches("config-[0-5]"))throw new IOException("RECOVERY_FORMAT");return document(FILES.get(Integer.parseInt(id.substring(7))));},NATIVE_UNCHANGED,fault);}
    private File operation()throws IOException{
        ensureStore();if(fs.list(operations).size()>=BackupLimits.TRANSACTION_RECORDS)throw new IOException("RECOVERY_RETENTION_LIMIT");File result=fs.child(operations,UUID.randomUUID().toString());fs.directory(result);fs.directory(new File(result,"candidate"));return result;
    }
    private void change(Map<String,byte[]> defaults,Map<String,Object> snapshot,boolean legacySource,String legacySlot,String expectedId,BackupControl control)throws IOException{
        if(!pendingNative().isEmpty())throw new IOException("RECOVERY_PENDING");
        Map<String,String> original=new LinkedHashMap<>();for(String name:FILES)original.put(name,BackupTree.digest(fs,document(name),control));
        Collection<?> pinned=snapshot!=null&&!legacySource?Collections.singleton(snapshot.get("id")):Collections.emptySet();
        save(capture(null,control),"before",control,pinned);File operation=operation();
        Set<String> selected=new LinkedHashSet<>();String legacyHash=null;
        if(defaults!=null){for(var entry:defaults.entrySet()){int ordinal=FILES.indexOf(entry.getKey());if(ordinal<0)throw new IOException("RECOVERY_FORMAT");selected.add(entry.getKey());
            if(entry.getValue()!=null)try(OutputStream out=fs.create(fs.child(operation,"candidate/config-"+ordinal))){out.write(entry.getValue());}}}
        else if(legacySource){
            File originalInput=legacy(legacySlot),fixed=fs.child(operation,"legacy-source.json");var beforeInput=fs.stat(originalInput);
            if(!beforeInput.type.equals("FILE")||beforeInput.size>LegacyConfigurationSnapshot.DOCUMENT_LIMIT)throw new IOException("RECOVERY_SIZE");
            var hash=BackupArchive.sha();long total=0;try(InputStream in=fs.read(originalInput,beforeInput);OutputStream out=fs.create(fixed)){
                byte[] buffer=new byte[65536];int n;while((n=in.read(buffer))!=-1){control.check();total=BackupLimits.add(total,n,LegacyConfigurationSnapshot.DOCUMENT_LIMIT);out.write(buffer,0,n);hash.update(buffer,0,n);}}
            legacyHash=BackupArchive.hex(hash.digest());
            Map<String,Object> current=legacy(fixed,(name,bytes)->{selected.add(name);if(bytes!=null)try(OutputStream out=fs.create(fs.child(operation,"candidate/config-"+FILES.indexOf(name)))){out.write(bytes);}},control);
            if(!expectedId.equals(current.get("id"))||!entries(current).equals(entries(snapshot)))throw new IOException("RECOVERY_CHANGED");snapshot=current;
        }else{
            String id=BackupJson.string(snapshot,"id");snapshot=load(id,control);selected.addAll(FILES);
            Map<String,Object> rows=entries(snapshot);for(int i=0;i<FILES.size();i++)if(Boolean.TRUE.equals(((Map<?,?>)rows.get(FILES.get(i))).get("present")))BackupTree.copy(fs,fs.child(store,id+"/"+i),fs.child(operation,"candidate/config-"+i),control);
        }
        if(legacySlot!=null&&legacySlot.equals("pending")){
            try(OutputStream out=fs.create(fs.child(operation,"legacy.json"))){out.write(BackupJson.write(Map.of("sha256",legacyHash),1024));}
        }
        Map<String,String> before=new LinkedHashMap<>();List<String> roots=new ArrayList<>();Set<String> removals=new HashSet<>();
        for(String name:selected){int ordinal=FILES.indexOf(name);String id="config-"+ordinal;File target=document(name);
            if(fs.stat(target).type.equals("LINK"))throw new IOException("RECOVERY_LINK");fs.parents(dsh,name);roots.add(id);before.put(id,original.get(name));
            if(fs.stat(fs.child(operation,"candidate/"+id)).type.equals("MISSING"))removals.add(id);
        }
        if(legacySource)try(InputStream input=fs.read(legacy(legacySlot),fs.stat(legacy(legacySlot)))){if(!legacyHash.equals(BackupArchive.digest(input,control)))throw new IOException("RECOVERY_CHANGED");}
        HostDataTransaction transaction=transaction(operation);transaction.prepare(roots,before,Collections.emptyMap(),control,removals);transaction.commit(control);
        finishLegacy(operation,control);
    }
    public void create(String target,BackupControl control)throws IOException{
        if(pending())throw new IOException("RECOVERY_PENDING");Map<String,byte[]> desired=new LinkedHashMap<>();
        if(target.equals("web")){
            Map<String,Object> manifest=Map.of("name","dsh-profile-web","private",true,"dependencies",Collections.emptyMap(),"dsh",Map.of("profile",Map.of("bundles",List.of("@deepseek-ai/dsh-base","@deepseek-ai/dsh-web-app"),"patchReload","startup")));
            desired.put(FILES.get(0),BackupJson.write(manifest,16384));desired.put(FILES.get(1),"[]\n".getBytes(StandardCharsets.UTF_8));
        }else if(target.equals("settings.yaml")||target.equals("cordis.patch.yml"))desired.put(target,(target.equals("settings.yaml")?"{}\n":"[]\n").getBytes(StandardCharsets.UTF_8));
        else throw new IOException("RECOVERY_FORMAT");change(desired,null,false,null,null,control);
    }
    public void restore(String slot,String id,BackupControl control)throws IOException{
        if(slot==null||id==null)throw new IOException("RECOVERY_FORMAT");
        if(pending())throw new IOException("RECOVERY_PENDING");
        if(slot.startsWith("legacy-")){String original=slot.substring(7);Map<String,Object> snapshot=legacy(legacy(original),null,control);if(!id.equals(snapshot.get("id")))throw new IOException("RECOVERY_CHANGED");change(null,snapshot,true,original,id,control);}
        else{if(!slot(slot)||slot.equals("candidate"))throw new IOException("RECOVERY_SLOT");Map<String,Object> index=catalog();if(!id.equals(index.get(slot)))throw new IOException("RECOVERY_CHANGED");change(null,load(id,control),false,null,id,control);}
    }
    private boolean mark(File directory,String name)throws IOException{
        File file=fs.child(directory,name);if(fs.stat(file).type.equals("MISSING"))return false;
        if(!(directory.getName()+"\n"+name+"\n").equals(new String(fs.small(file,256),StandardCharsets.UTF_8)))throw new IOException("RECOVERY_FORMAT");return true;
    }
    public List<File> pendingNative()throws IOException{
        if(fs.stat(operations).type.equals("MISSING"))return Collections.emptyList();List<String> entries=fs.list(operations);if(entries.size()>BackupLimits.TRANSACTION_RECORDS)throw new IOException("RECOVERY_RETENTION_LIMIT");
        List<File> pending=new ArrayList<>();for(String id:entries){File directory=fs.child(operations,uuid(id));if(mark(directory,"switching")&&!mark(directory,"finalized")&&!mark(directory,"rolled-back"))pending.add(directory);}
        if(pending.size()>1)throw new IOException("RECOVERY_MULTIPLE_TRANSACTIONS");return pending;
    }
    public boolean pending()throws IOException{return !pendingNative().isEmpty()||!fs.stat(legacy("pending")).type.equals("MISSING")||!fs.stat(new File(files,"startup-config-repair.pending")).type.equals("MISSING");}
    private void finishLegacy(File operation,BackupControl control)throws IOException{
        File receipt=fs.child(operation,"legacy.json");if(fs.stat(receipt).type.equals("MISSING")||!mark(operation,"finalized"))return;File old=legacy("pending");if(fs.stat(old).type.equals("MISSING"))return;
        String wanted=BackupJson.string(BackupJson.read(fs.small(receipt,1024),1024),"sha256"),actual;try(InputStream input=fs.read(old,fs.stat(old))){actual=BackupArchive.digest(input,control);}
        if(!wanted.matches("[a-f0-9]{64}"))throw new IOException("RECOVERY_FORMAT");
        if(wanted.equals(actual))fs.move(old,fs.child(operation,"legacy-pending-original.json"));
    }
    public String recover(BackupControl control)throws IOException{
        List<File> interrupted=pendingNative();boolean repaired=!interrupted.isEmpty();
        for(File pending:interrupted)transaction(pending).recover();
        if(!fs.stat(operations).type.equals("MISSING"))for(String id:fs.list(operations))finishLegacy(fs.child(operations,uuid(id)),control);
        File old=legacy("pending");if(!fs.stat(old).type.equals("MISSING")){
            Map<String,Object> snapshot=legacy(old,null,control);change(null,snapshot,true,"pending",BackupJson.string(snapshot,"id"),control);
            repaired=true;
        }
        File marker=new File(files,"startup-config-repair.pending");if(!fs.stat(marker).type.equals("MISSING")){
            // 没有旧日志时只解除旧入口标记，不猜测哪份历史数据应覆盖当前文件。
            if(!fs.stat(marker).type.equals("FILE"))throw new IOException("RECOVERY_FORMAT");ensureStore();
            File retained=fs.child(store,"retained-markers");directory(retained);fs.move(marker,fs.child(retained,UUID.randomUUID()+".bin"));
            return repaired?"RECOVERY_ROLLED_BACK":"RECOVERY_MARKER_RETAINED";
        }
        return repaired?"RECOVERY_ROLLED_BACK":"RECOVERY_NO_DATA_CHANGED";
    }
    public static boolean blocked(File files){
        try{File root=files.getCanonicalFile();var fs=new AndroidBackupFileSystem();return new ConfigurationSnapshots(fs,root,new UserDataLayout(fs,root).current()).pending();}
        catch(IOException error){return true;}
    }
}
