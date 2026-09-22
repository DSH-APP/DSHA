package com.deepseekharness.app.backup;

import android.content.Context;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import androidx.lifecycle.LiveData;
import androidx.lifecycle.MutableLiveData;
import com.deepseekharness.app.BackupManager;
import com.deepseekharness.app.BuildConfig;
import com.deepseekharness.app.core.ConfigStore;
import com.deepseekharness.app.core.HarnessController;
import java.io.*;
import java.util.*;

/** 应用级宿主作业。密码只在工作线程内存中，状态记录与产物均在 rootfs 外。 */
public final class NativeBackupJobs {
    public static final class State {
        public final String id,stage,result,error,artifact;
        public final long entries,bytes;
        public final boolean busy;
        State(String id,String stage,String result,String error,String artifact,long entries,long bytes,boolean busy){this.id=id;this.stage=stage;this.result=result;this.error=error;this.artifact=artifact;this.entries=entries;this.bytes=bytes;this.busy=busy;}
    }
    private static NativeBackupJobs instance;
    public static synchronized NativeBackupJobs get(Context context){if(instance==null)instance=new NativeBackupJobs(context.getApplicationContext());return instance;}
    private final Context context;
    private final BackupFileSystem fs=new AndroidBackupFileSystem();
    private final MutableLiveData<State> changes=new MutableLiveData<>();
    private final Handler main=new Handler(Looper.getMainLooper());
    private final WorkerQuiescence workers=new WorkerQuiescence();
    private State state=new State("","IDLE","","","",0,0,false);
    private BackupControl control;
    private File task;
    private long lastProgress;
    private Boolean decision;
    private final Object approval=new Object();
    private NativeBackupJobs(Context context){this.context=context;loadLastState();changes.postValue(state);}
    private void loadLastState(){
        try {
            File root=new File(context.getFilesDir().getCanonicalFile(),"host-backup-operations");if(fs.stat(root).type.equals("MISSING"))return;
            long newest=-1;List<String> names=fs.list(root);if(names.size()>BackupLimits.TRANSACTION_RECORDS)throw new IOException("OPERATION_LIMIT");
            for(String name:names){if(!name.matches("[a-f0-9-]{36}"))continue;File owner=fs.child(root,name),record=fs.child(owner,"operation.json");
                if(fs.stat(record).type.equals("MISSING"))record=fs.child(owner,"operation.json.previous");if(fs.stat(record).type.equals("MISSING"))continue;
                Map<String,Object> value=BackupJson.read(fs.small(record,16384),16384);if(!name.equals(BackupJson.string(value,"id"))||BackupJson.number(value,"version")!=1)throw new IOException("OPERATION_RECORD");
                long time=value.get("updatedAt") instanceof Number?((Number)value.get("updatedAt")).longValue():fs.stat(record).modified*1000;
                String artifact=BackupJson.string(value,"artifact");if(!artifact.isEmpty()&&!artifact.equals("portable.dshbak"))throw new IOException("OPERATION_RECORD");
                if(time>=newest){newest=time;boolean busy=Boolean.TRUE.equals(value.get("busy"));state=new State(name,busy?"INTERRUPTED":BackupJson.string(value,"stage"),BackupJson.string(value,"result"),busy?"PASSWORD_NOT_RETAINED":BackupJson.string(value,"error"),artifact,BackupJson.number(value,"entries"),BackupJson.number(value,"bytes"),false);}
            }
        }catch(IOException error){state=new State("","FAILED_RETAINED","","OPERATION_RECORD_UNREADABLE","",0,0,false);}
    }
    public LiveData<State> changes(){return changes;}
    public synchronized State state(){return state;}
    /** 只读小型记录供界面选择；选择后的工作线程还必须复核完整加密文件摘要。 */
    public static final class Copies {
        public final List<VerifiedBackupCopy> valid;public final Map<String,String> unreadable;
        Copies(List<VerifiedBackupCopy> valid,Map<String,String> unreadable){this.valid=Collections.unmodifiableList(valid);this.unreadable=Collections.unmodifiableMap(unreadable);}
    }
    public Copies verifiedCopies()throws IOException{
        File parent=operations();List<String> names=fs.list(parent);if(names.size()>BackupLimits.TRANSACTION_RECORDS)throw new IOException("OPERATION_LIMIT");
        List<VerifiedBackupCopy> result=new ArrayList<>();Map<String,String> unreadable=new LinkedHashMap<>();
        for(String name:names){
            if(!name.matches("[a-f0-9-]{36}"))continue;File directory=fs.child(parent,name);
            if(fs.stat(new File(directory,"verified.json")).type.equals("MISSING"))continue;
            try{result.add(VerifiedBackupCopy.inspect(fs,parent,name));}catch(IOException error){unreadable.put(name,BackupErrorCode.from(error));}
        }
        result.sort((a,b)->Long.compare(b.created,a.created));return new Copies(result,unreadable);
    }
    public synchronized boolean reexport(String sourceId,Uri destination,String name){
        if(state.busy||workers.isClosed()||AutomaticBackups.factoryResetPending(context))return false;
        final VerifiedBackupCopy source;
        try{
            File parent=operations();source=VerifiedBackupCopy.inspect(fs,parent,sourceId);
            if(fs.list(parent).size()>=BackupLimits.TRANSACTION_RECORDS)throw new IOException("RETAINED_OPERATION_LIMIT");
            task=new File(parent,UUID.randomUUID().toString());fs.directory(task);control=new BackupControl(this::progress);
            fs.atomic(task,"reexport.json",BackupJson.write(Map.of("version",1L,"source",sourceId,"sha256",source.sha256),4096));
            update("VERIFYING","","","",source.entries,source.bytes,true);foreground();
        }catch(IOException error){beginFailed(error);return false;}
        BackupControl cancellation=control;
        return launch(()->{
            try{
                source.verify(fs,cancellation);update("EXPORTING","","","",source.entries,source.bytes,true);
                boolean readback=copyToDocument(source.artifact,source.sha256,destination,name,cancellation);String result=source.result(readback);
                if(result.equals("COMPLETE")){
                    File catalogue=new File(context.getFilesDir().getCanonicalFile(),"host-backup-catalogue");if(fs.stat(catalogue).type.equals("MISSING"))fs.directory(catalogue);
                    fs.atomic(catalogue,"latest.json",BackupJson.write(Map.of("operation",source.id,"sha256",source.sha256,"uri",destination.toString(),"name",name),16384));
                    new ConfigStore(context).recordVerifiedNativeBackup(destination.toString(),name,source.scope);
                }
                update("FINISHED",result,"","",source.entries,source.bytes,false);
            }catch(Exception error){failed(error);}finally{synchronized(this){if(control==cancellation)control=null;}}
        },"host-backup-reexport",null);
    }
    public Map<String,Object> preview(String id)throws IOException{
        if(!id.equals(state().id)||!state().stage.equals("PREVIEW"))throw new IOException("PREVIEW_CHANGED");
        return BackupJson.read(fs.small(new File(operations(),id+"/preview.json"),BackupLimits.MANIFEST),BackupLimits.MANIFEST);
    }
    public synchronized void cancel(){if(control!=null)control.cancel();synchronized(approval){approval.notifyAll();}}

    /**
     * 格式化专用：先封住所有手动/自动入口，再取消现有任务，并等待 worker 的
     * 整个 finally 完成。state.busy 会在 finally 之前变为 false，不能单独作为空闲证据。
     */
    public void beginFactoryResetQuiescence(BackupControl waiting,long timeoutMillis)throws IOException{
        workers.closeGate();
        try{
            // cancel() 同时充当 NativeBackupJobs monitor 的屏障：已经进入的启动方法
            // 必须先完成线程登记或失败收尾，之后本调用才会继续。
            cancel();
            workers.awaitIdle(waiting,timeoutMillis);
            synchronized(this){if(state.busy)throw new IOException("NATIVE_BACKUP_STATE_STILL_BUSY");}
        }catch(IOException|RuntimeException failure){workers.reopen();throw failure;}
    }

    /** 格式化失败且尚需继续使用当前数据时重新开放入口。 */
    public void abortFactoryResetQuiescence(){workers.reopen();}

    /** 磁盘作业记录已被格式化删除；同步清掉单例中的旧引用后再开放入口。 */
    public void completeFactoryResetQuiescence(){
        synchronized(this){
            if(workers.active()!=0)throw new IllegalStateException("NATIVE_BACKUP_WORKER_STILL_ACTIVE");
            control=null;task=null;decision=null;lastProgress=0;
            state=new State("","IDLE","","","",0,0,false);
            State reset=state;main.post(()->changes.setValue(reset));
        }
        workers.reopen();
    }
    public boolean decide(String id,boolean accept){synchronized(approval){if(!state().id.equals(id)||!state().stage.equals("PREVIEW")||decision!=null)return false;decision=accept;approval.notifyAll();return true;}}
    private File operations()throws IOException{
        File files=context.getFilesDir().getCanonicalFile();File directory=fs.child(files,"host-backup-operations");
        if(fs.stat(directory).type.equals("MISSING"))fs.directory(directory);if(!fs.stat(directory).type.equals("DIRECTORY"))throw new IOException("OPERATION_DIRECTORY");return directory;
    }
    private synchronized void update(String stage,String result,String error,String artifact,long entries,long bytes,boolean busy)throws IOException{
        State next=new State(task==null?"":task.getName(),stage,result,error,artifact,entries,bytes,busy);
        Map<String,Object> record=new LinkedHashMap<>();record.put("version",1L);record.put("id",next.id);record.put("stage",stage);record.put("result",result);record.put("error",error);
        record.put("artifact",artifact);record.put("entries",entries);record.put("bytes",bytes);record.put("busy",busy);
        record.put("updatedAt",System.currentTimeMillis());
        if(task!=null)fs.atomic(task,"operation.json",BackupJson.write(record,16384));state=next;main.post(()->changes.setValue(next));
    }
    private void progress(String phase,long entries,long bytes)throws IOException{
        long now=android.os.SystemClock.elapsedRealtime();if(now-lastProgress<350)return;lastProgress=now;update(phase,"","","",entries,bytes,true);
    }
    private void foreground()throws IOException{
        DataProtectionService.start(context);
    }
    private synchronized void beginFailed(Exception error){
        if(task!=null&&state.busy&&task.getName().equals(state.id)){
            try{update("FAILED_RETAINED","",code(error),"",0,0,false);}catch(IOException failed){state=new State(task.getName(),"FAILED_RETAINED","",code(error),"",0,0,false);}
        }else state=new State("","FAILED_RETAINED","",code(error),"",0,0,false);
        control=null;State terminal=state;main.post(()->changes.setValue(terminal));
    }
    private boolean launch(Runnable work,String name,char[] password){
        WorkerQuiescence.Lease lease=workers.tryBegin();
        if(lease==null){if(password!=null)Arrays.fill(password,'\0');beginFailed(new IOException("FACTORY_RESET_QUIESCING"));return false;}
        try{
            new Thread(()->{try(WorkerQuiescence.Lease ignored=lease){work.run();}},name).start();
            return true;
        }catch(RuntimeException|Error error){
            lease.close();if(password!=null)Arrays.fill(password,'\0');
            beginFailed(new IOException("WORKER_UNAVAILABLE",error));return false;
        }
    }
    private synchronized void failed(Exception error){
        boolean cancelled=error instanceof InterruptedIOException||"CANCELLED".equals(code(error))||control!=null&&control.isCancelled();
        String stage=cancelled&&!HostPendingTransactions.blocked(context.getFilesDir())?"CANCELLED":"FAILED_RETAINED";
        try{update(stage,"",code(error),"",0,0,false);}catch(IOException failure){state=new State(task==null?"":task.getName(),"FAILED_RETAINED","","STATE_PERSISTENCE_FAILED","",0,0,false);State terminal=state;main.post(()->changes.setValue(terminal));}
    }
    public synchronized boolean export(NativeDataLocations.Selection selection,char[] suppliedPassword,Uri destination,String expectedName,boolean rescue){
        return exportInternal(selection,suppliedPassword,destination,expectedName,rescue,false);
    }
    public synchronized boolean exportAutomatic(char[] password){
        try{if(!AutomaticBackups.enabled(context)||!AutomaticBackups.idle(context))return false;}catch(IOException unavailable){return false;}
        return exportInternal(new NativeDataLocations.Selection(),password,null,"",false,true);
    }
    private synchronized boolean exportInternal(NativeDataLocations.Selection selection,char[] suppliedPassword,Uri destination,String expectedName,boolean rescue,boolean automatic){
        if(state.busy||workers.isClosed()||AutomaticBackups.factoryResetPending(context))return false;
        if(!rescue&&BackupManager.hasPendingMaintenance(context.getFilesDir()))return false;
        if(suppliedPassword==null||suppliedPassword.length<12||suppliedPassword.length>1024)return false;
        char[] password=suppliedPassword.clone();
        NativeDataLocations.Selection frozen=new NativeDataLocations.Selection();frozen.scope=selection.scope;frozen.includeApiKey=selection.includeApiKey;
        frozen.retainedKey=selection.retainedKey;
        if(frozen.retainedKey!=null&&!frozen.retainedKey.isEmpty()&&!rescue){Arrays.fill(password,'\0');return false;}
        frozen.guestProjects.addAll(selection.guestProjects);
        try{for(BackupSource source:selection.documentProjects)frozen.documentProjects.add(source instanceof SafBackupSource?((SafBackupSource)source).copyForOperation():source);}
        catch(IOException error){Arrays.fill(password,'\0');return false;}
        try {
            File parent=operations();if(fs.list(parent).size()>=BackupLimits.TRANSACTION_RECORDS)throw new IOException("RETAINED_OPERATION_LIMIT");
            task=new File(parent,UUID.randomUUID().toString());fs.directory(task);
            control=new BackupControl(this::progress);update("PREPARING","","","",0,0,true);if(!automatic)foreground();
        }catch(IOException error){Arrays.fill(password,'\0');beginFailed(error);return false;}
        File owned=task;BackupControl cancellation=control;
        return launch(()->{
            File plain=new File(owned,"snapshot.dshdata"),encrypted=new File(owned,"portable.dshbak");
            try {
                NativeDataLocations locations=new NativeDataLocations(context);NativeDataLocations.Located located=locations.locate(frozen,cancellation);
                Map<String,Object> provenance=new LinkedHashMap<>();provenance.put("operation",rescue?"RESCUE":"EXPORT");provenance.put("createdAt",System.currentTimeMillis());
                provenance.put("requestedScope",frozen.scope);if(automatic)provenance.put("automatic",true);
                if(frozen.retainedKey!=null&&!frozen.retainedKey.isEmpty())provenance.put("retainedSource",frozen.retainedKey);
                provenance.put("appVersion",BuildConfig.VERSION_NAME);provenance.put("runtime",com.deepseekharness.app.util.Constants.DSH_VERSION);
                provenance.put("dataFormat","UNINSPECTED");provenance.put("sensitivePolicy","PASSWORD_ENCRYPTED");provenance.put("plugins",located.plugins==null?Collections.emptyMap():located.plugins.description());
                RuntimeDescriptor observed=null;try{if(frozen.retainedKey==null||frozen.retainedKey.isEmpty())observed=HarnessController.get(context).proot().installedRuntimeDescriptor();}catch(IOException unavailable){}
                provenance.put("dataCompatibility",DataFormatEvidence.unknown(observed));
                HostSnapshot.FinalCheck check=located.plugins==null?null:located.plugins::verify;
                Map<String,Object> summary;
                if(rescue)summary=HostSnapshot.create(fs,located.sources,owned,plain,provenance,true,cancellation,check);
                else if(automatic)summary=BackupManager.runSnapshotTask(HarnessController.get(context),()->{
                    if(!AutomaticBackups.enabled(context)||!AutomaticBackups.idleForOwner(context))throw new IOException("AUTOMATIC_BACKUP_DEFERRED");
                    // 当前线程已有快照任务；维护屏障只允许本线程，不停止任何运行中的任务。
                    try(var idle=com.deepseekharness.app.core.RuntimeTasks.tryEnterMaintenance()){
                        if(idle==null)throw new IOException("AUTOMATIC_BACKUP_DEFERRED");
                        return HostSnapshot.create(fs,located.sources,owned,plain,provenance,false,cancellation,check);
                    }
                });
                else summary=BackupManager.runDataTask(HarnessController.get(context),()->HostSnapshot.create(fs,located.sources,owned,plain,provenance,false,cancellation,check));
                try(InputStream input=fs.read(plain,fs.stat(plain))){BackupArchive.read(input,null,cancellation);}
                String plainHash;try(InputStream input=fs.read(plain,fs.stat(plain))){plainHash=BackupArchive.digest(input,cancellation);}
                update("ENCRYPTING","","","",BackupJson.number(summary,"entries"),BackupJson.number(summary,"bytes"),true);
                try(InputStream input=fs.read(plain,fs.stat(plain));OutputStream output=fs.create(encrypted)){PortableBackupCrypto.encrypt(input,output,password,cancellation);}
                java.security.MessageDigest authenticated=BackupArchive.sha();
                try(InputStream input=fs.read(encrypted,fs.stat(encrypted));OutputStream verify=new java.security.DigestOutputStream(new OutputStream(){public void write(int b){}public void write(byte[] b,int o,int n){}},authenticated)){
                    PortableBackupCrypto.decrypt(input,verify,password,cancellation);
                }
                if(!plainHash.equals(BackupArchive.hex(authenticated.digest())))throw new IOException("PRIVATE_ARTIFACT_VERIFICATION");
                String artifactHash;try(InputStream input=fs.read(encrypted,fs.stat(encrypted))){artifactHash=BackupArchive.digest(input,cancellation);}
                Map<String,Object> verification=new LinkedHashMap<>(summary);verification.put("encryptedSha256",artifactHash);verification.put("encryptedBytes",fs.stat(encrypted).size);
                fs.atomic(owned,"verified.json",BackupJson.write(verification,BackupLimits.MANIFEST));fs.delete(plain);
                update("EXPORTING","","","portable.dshbak",BackupJson.number(summary,"entries"),fs.stat(encrypted).size,true);
                boolean readback=automatic||copyToDocument(encrypted,artifactHash,destination,expectedName,cancellation);
                String quality=BackupJson.string(summary,"integrity");String result=!readback?"WRITTEN_UNVERIFIED":quality.equals("PARTIAL")?"PARTIAL_RESCUE":quality.equals("BEST_EFFORT")?"BEST_EFFORT_RESCUE":"COMPLETE";
                if(result.equals("COMPLETE")&&located.plugins!=null&&!Boolean.TRUE.equals(located.plugins.description().get("complete")))result="DATA_SAVED_PLUGIN_WARNINGS";
                if(!automatic&&result.equals("COMPLETE")){
                    File catalogue=new File(context.getFilesDir().getCanonicalFile(),"host-backup-catalogue");if(fs.stat(catalogue).type.equals("MISSING"))fs.directory(catalogue);
                    fs.atomic(catalogue,"latest.json",BackupJson.write(Map.of("operation",owned.getName(),"sha256",artifactHash,"uri",destination.toString(),"name",expectedName),16384));
                    new ConfigStore(context).recordVerifiedNativeBackup(destination.toString(),expectedName,frozen.scope);
                }
                if(automatic){
                    AutomaticBackups.completed(context,owned.getName());
                    try{AutomaticBackups.prune(context,cancellation);}catch(IOException retained){AutomaticBackups.recordError(context,code(retained));}
                }
                update("FINISHED",result,"","portable.dshbak",BackupJson.number(summary,"entries"),fs.stat(encrypted).size,false);
            }catch(Exception error){try{update(error instanceof InterruptedIOException||cancellation.isCancelled()||"CANCELLED".equals(code(error))?"CANCELLED":"FAILED","",code(error),fs.stat(new File(owned,"verified.json")).type.equals("FILE")?"portable.dshbak":"",0,0,false);}catch(IOException retained){synchronized(this){state=new State(owned.getName(),"FAILED_RETAINED","","STATE_PERSISTENCE_FAILED","",0,0,false);}main.post(()->changes.setValue(state()));}}
            finally{Arrays.fill(password,'\0');try{if(!fs.stat(plain).type.equals("MISSING"))fs.delete(plain);}catch(IOException ignored){}synchronized(this){if(control==cancellation)control=null;}}
        },"host-data-export",password);
    }
    private boolean copyToDocument(File source,String expected,Uri target,String name,BackupControl cancellation)throws IOException{
        if(target==null||!"content".equals(target.getScheme())||name==null||!name.matches("DSHA-data-v5-[a-f0-9-]{36}\\.dshbak"))throw new IOException("EXPORT_DESTINATION");
        if(target.toString().equals(new ConfigStore(context).getLastBackupUri()))throw new IOException("EXISTING_BACKUP_DESTINATION");
        try(android.database.Cursor cursor=DocumentStreams.query(context.getContentResolver(),target,new String[]{android.provider.OpenableColumns.DISPLAY_NAME,android.provider.OpenableColumns.SIZE},cancellation)){
            if(cursor==null||!cursor.moveToFirst()||!name.equals(cursor.getString(0))||!cursor.isNull(1)&&cursor.getLong(1)>0)throw new IOException("DESTINATION_NOT_NEW");
        }catch(SecurityException error){throw new IOException("SAF_PERMISSION_REVOKED",error);}
        try(InputStream input=fs.read(source,fs.stat(source));OutputStream output=DocumentStreams.output(context.getContentResolver(),target,cancellation)){
            if(output==null)throw new IOException("SAF_WRITE_FAILED");byte[] buffer=new byte[65536];long bytes=0;int n;
            while((n=input.read(buffer))!=-1){cancellation.check();output.write(buffer,0,n);bytes+=n;cancellation.report("EXPORTING",0,bytes);}output.flush();
        }catch(SecurityException error){throw new IOException("SAF_PERMISSION_REVOKED",error);}
        try(InputStream input=DocumentStreams.input(context.getContentResolver(),target,cancellation)){
            if(input==null)return false;String actual=BackupArchive.digest(input,cancellation);if(!actual.equals(expected))throw new IOException("DESTINATION_CHECKSUM");return true;
        }catch(SecurityException unsupported){return false;}
        catch(IOException error){if(error instanceof InterruptedIOException||"DESTINATION_CHECKSUM".equals(error.getMessage()))throw error;return false;}
    }
    public static String code(Exception error){return BackupErrorCode.from(error);}

    public synchronized boolean prepareRestore(Uri uri,char[] supplied,Set<String> selected,boolean includeKey){
        return prepareRestore(uri,supplied,selected,includeKey,"PRIVATE");
    }
    public synchronized boolean prepareRestore(Uri uri,char[] supplied,Set<String> selected,boolean includeKey,String projects){
        return prepareRestoreInput(uri,null,null,supplied,selected,includeKey,projects);
    }
    public synchronized boolean prepareRestoreCopy(String id,char[] supplied,Set<String> selected,boolean includeKey,String projects){
        try{return prepareRestoreInput(null,VerifiedBackupCopy.inspect(fs,operations(),id),null,supplied,selected,includeKey,projects);}
        catch(IOException error){beginFailed(error);return false;}
    }
    public synchronized boolean prepareRestoreTree(String key,Set<String> selected,String projects){
        try{
            File files=context.getFilesDir().getCanonicalFile();var catalogue=new RetainedCatalogue(fs,files,new UserDataLayout(fs,files).current());var source=catalogue.resolve(key);
            if(source.source==null||source.part.equals("encrypted"))return false;
            return prepareRestoreInput(null,null,source,null,selected,false,projects);
        }catch(IOException error){beginFailed(error);return false;}
    }
    private synchronized boolean prepareRestoreInput(Uri uri,VerifiedBackupCopy retained,RetainedCatalogue.Entry retainedTree,char[] supplied,Set<String> selected,boolean includeKey,String projects){
        if(state.busy||workers.isClosed()||AutomaticBackups.factoryResetPending(context)||(retained==null&&retainedTree==null&&(uri==null||!"content".equals(uri.getScheme()))))return false;
        if(BackupManager.hasPendingMaintenance(context.getFilesDir()))return false;
        if(!NativeDataLocations.SCOPES.containsAll(selected)||selected.isEmpty())return false;
        if(!Set.of("PRIVATE","GUEST_HOME").contains(projects))return false;
        char[] password=supplied==null?null:supplied.clone();Set<String> scopes=new HashSet<>(selected);
        try{File parent=operations();if(fs.list(parent).size()>=BackupLimits.TRANSACTION_RECORDS)throw new IOException("RETAINED_OPERATION_LIMIT");task=new File(parent,UUID.randomUUID().toString());fs.directory(task);
            control=new BackupControl(this::progress);decision=null;update("COPYING_INPUT","","","",0,0,true);foreground();}
        catch(IOException error){if(password!=null)Arrays.fill(password,'\0');beginFailed(error);return false;}
        File owned=task;BackupControl cancellation=control;
        return launch(()->{
            File input=new File(owned,"input.archive"),plain=new File(owned,"restore.dshdata");
            try {
                if(retainedTree!=null){
                    File files=context.getFilesDir().getCanonicalFile();var catalogue=new RetainedCatalogue(fs,files,new UserDataLayout(fs,files).current());
                    Map<String,Object> provenance=new LinkedHashMap<>();provenance.put("operation","RESCUE");provenance.put("purpose","RESTORE_PREVIEW");provenance.put("createdAt",System.currentTimeMillis());
                    provenance.put("appVersion",BuildConfig.VERSION_NAME);provenance.put("runtime","unknown");provenance.put("dataFormat","UNINSPECTED");
                    provenance.put("sensitivePolicy","APP_PRIVATE_STAGING");provenance.put("retainedSource",retainedTree.key());provenance.put("requestedScope",retainedTree.scope);
                    provenance.put("dataCompatibility",DataFormatEvidence.unknown(null));provenance.put("plugins",Collections.emptyMap());
                    HostSnapshot.create(fs,catalogue.sources(retainedTree),owned,plain,provenance,true,cancellation,null);
                }else{
                if(retained!=null)retained.verify(fs,cancellation);
                try(InputStream source=retained==null?DocumentStreams.input(context.getContentResolver(),uri,cancellation):fs.read(retained.artifact,fs.stat(retained.artifact));OutputStream out=fs.create(input)){
                    if(source==null)throw new IOException("INPUT_UNREADABLE");byte[] buffer=new byte[65536];long bytes=0;int n;
                    while((n=source.read(buffer))!=-1){cancellation.check();bytes=BackupLimits.add(bytes,n,BackupLimits.BYTES+256L*1024*1024);out.write(buffer,0,n);cancellation.report("COPYING_INPUT",0,bytes);}
                }
                if(retained!=null)try(InputStream copied=fs.read(input,fs.stat(input))){if(!retained.sha256.equals(BackupArchive.digest(copied,cancellation)))throw new IOException("RETAINED_COPY_CHANGED");}
                byte[] signature=new byte[8];try(InputStream source=fs.read(input,fs.stat(input))){new DataInputStream(source).readFully(signature);}
                if(Arrays.equals(signature,PortableBackupCrypto.MAGIC)){
                    update("AUTHENTICATING","","","",0,fs.stat(input).size,true);
                    try(InputStream source=fs.read(input,fs.stat(input));OutputStream out=fs.create(plain)){PortableBackupCrypto.decrypt(source,out,password,cancellation);}
                }else {
                    String filename=null;try(android.database.Cursor cursor=DocumentStreams.query(context.getContentResolver(),uri,new String[]{android.provider.OpenableColumns.DISPLAY_NAME},cancellation)){
                        if(cursor!=null&&cursor.moveToFirst())filename=cursor.getString(0);
                    }
                    new LegacyBackupImporter(fs,owned).convert(input,plain,filename,cancellation);
                }
                }
                if(password!=null)Arrays.fill(password,'\0');
                NativeRestoreTargets mapping=new NativeRestoreTargets(context,owned,projects);
                NativeRestorePlan plan=NativeRestorePlan.inspect(fs,owned,plain,mapping,scopes,cancellation);
                String hash;try(InputStream source=fs.read(plain,fs.stat(plain))){hash=BackupArchive.digest(source,cancellation);}
                Map<String,Object> preview=new LinkedHashMap<>(plan.manifest);preview.put("inputSha256",hash);preview.put("restoreRoots",new ArrayList<>(plan.groups.keySet()));preview.put("conflicts",plan.previewConflicts());preview.put("warnings",plan.warnings);preview.put("projectDestination",projects);
                fs.atomic(owned,"preview.json",BackupJson.write(preview,BackupLimits.MANIFEST));
                update("PREVIEW","CONFIRM_STOP_AND_RESTORE","","",BackupJson.number(plan.manifest,"entries"),BackupJson.number(plan.manifest,"bytes"),true);
                synchronized(approval){while(decision==null){cancellation.check();try{approval.wait(500);}catch(InterruptedException error){Thread.currentThread().interrupt();throw new InterruptedIOException("CANCELLED");}}if(!decision)throw new InterruptedIOException("CANCELLED");}
                update("STOPPING_WRITERS","","","",0,0,true);
                BackupManager.runDataTask(HarnessController.get(context),()->{
                    try(InputStream source=fs.read(plain,fs.stat(plain))){if(!hash.equals(BackupArchive.digest(source,cancellation)))throw new IOException("INPUT_CHANGED");}
                    List<String> roots=plan.buildCandidates(true,cancellation);mapping.prepareLayout(roots,plan.before,new File(owned,"candidate"));mapping.prepareParents(roots);
                    if(roots.contains("plugin-store")&&(scopes.contains("application")||scopes.contains("plugins"))&&plan.manifest.get("plugins") instanceof Map){
                        @SuppressWarnings("unchecked") Map<String,Object> graph=(Map<String,Object>)plan.manifest.get("plugins");
                        new PluginRestoreGraph(fs,new File(owned,"candidate/plugin-store"),new CurrentManagedPackages(context)::verified).rebuild(graph,cancellation);
                        // 系统插件源码仍留在当前签名 APK；这里只把用户的启停选择
                        // 合入活跃 profile 的独立事务候选。提交后首次启动会由严格
                        // registerBuiltinPlugins 再生当前 APK 链接，旧备份字节不会参与。
                        mapping.prepareSystemPluginState(graph,roots,plan.before,new File(owned,"candidate"),cancellation);
                    }
                    Map<String,Object> proposed=mapping.proposedSettings(new File(owned,"candidate"),includeKey);
                    HostDataTransaction transaction=new HostDataTransaction(fs,owned,mapping,mapping,null);transaction.prepare(roots,plan.before,proposed,cancellation);
                    update("COMMITTING","","","",0,0,true);transaction.commit(cancellation);return null;
                });
                boolean plugins=plan.groups.containsKey("plugin-store");
                boolean keyAvailable;
                try { keyAvailable=new ConfigStore(context).readApiKey().state==com.deepseekharness.app.util.CredentialRead.State.AVAILABLE; }
                catch(Throwable ignored) { keyAvailable=false; }
                // 明确区分「用户选择不带 key」与「勾选后仍未恢复」，避免再次出现
                // 只显示“数据已恢复”却让用户下一次对话才发现凭据为空的假成功。
                String result;
                if(includeKey&&!keyAvailable) result="DATA_RESTORED_API_KEY_MISSING";
                else if(!includeKey&&!keyAvailable) result="DATA_RESTORED_API_KEY_OMITTED";
                else result=plugins?"DATA_RESTORED_PLUGINS_QUARANTINED":plan.warnings.isEmpty()?"DATA_RESTORED":"DATA_RESTORED_WITH_WARNINGS";
                update("FINISHED",result,"","",BackupJson.number(plan.manifest,"entries"),BackupJson.number(plan.manifest,"bytes"),false);
            }catch(Exception error){failed(error);}
            finally{if(password!=null)Arrays.fill(password,'\0');synchronized(this){if(control==cancellation)control=null;}}
        },"host-data-restore",password);
    }
    public synchronized boolean recoverPending(){
        if(state.busy||workers.isClosed()||AutomaticBackups.factoryResetPending(context))return false;
        List<File> pending;try{pending=HostPendingTransactions.pending(fs,context.getFilesDir().getCanonicalFile());if(pending.isEmpty())return false;if(pending.size()!=1)throw new IOException("MULTIPLE_TRANSACTIONS");task=pending.get(0);control=new BackupControl(this::progress);update("STOPPING_WRITERS","","","",0,0,true);foreground();}
        catch(IOException error){beginFailed(error);return false;}
        File owned=task;BackupControl cancellation=control;
        return launch(()->{try{
            BackupManager.runDataTask(HarnessController.get(context),()->com.deepseekharness.app.core.EnvironmentMaintenance.recover(HarnessController.get(context)));
            update("FINISHED","RECOVERED_INTERRUPTED_COMMIT","","",0,0,false);
        }catch(Exception error){failed(error);}
        finally{synchronized(this){if(control==cancellation)control=null;}}},"host-data-recovery",null);
    }
}
