package com.deepseekharness.app.backup;

import android.content.Context;
import android.os.Environment;
import com.deepseekharness.app.core.ConfigStore;
import com.deepseekharness.app.core.HarnessController;
import com.deepseekharness.app.runtime.ProfileSettingsTrial;
import com.deepseekharness.app.util.ProfileConfigPath;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** profile 普通设置的预检、选择、事务与真实服务读回；导入代码继续保留在隔离区。 */
public final class ProfileSettingsTransaction implements HostDataTransaction.Targets {
    private static final String RECORD="profile-settings.json";
    private final Context context;private final BackupFileSystem fs;private final File files,task,home;private final String profile;
    private final Map<String,Object> record;
    private ProfileSettingsTransaction(Context context,File task)throws IOException {
        this.context=context.getApplicationContext();fs=new AndroidBackupFileSystem();files=context.getFilesDir().getCanonicalFile();this.task=task;
        if(!task.getParentFile().equals(new File(files,"host-backup-operations"))||!task.getName().matches("[a-f0-9-]{36}"))throw new IOException("SETTINGS_TRANSACTION_PATH");
        record=BackupJson.read(fs.small(new File(task,RECORD),BackupLimits.MANIFEST),BackupLimits.MANIFEST);
        profile=BackupJson.string(record,"profile");if(!ProfileConfigPath.profile(profile)||!task.getName().equals(record.get("id")))throw new IOException("SETTINGS_TRANSACTION_RECORD");
        UserDataLayout.Home chosen;try{chosen=UserDataLayout.Home.valueOf(BackupJson.string(record,"home"));}catch(IllegalArgumentException error){throw new IOException("SETTINGS_TRANSACTION_RECORD");}
        home=resolveHome(context,fs,files,new UserDataLayout(fs,files).root(chosen));
    }
    public static File resolveHome(Context context,BackupFileSystem fs,File files,File home)throws IOException {
        File publicRoot=Environment.getExternalStorageDirectory().getCanonicalFile();
        return new GuestDataResolver(fs,new File(files,"linux/ubuntu"),publicRoot,List.of(new File(files,"user-data-v5"),new File(publicRoot,"Documents/dshdata"))).resolve(home).file;
    }
    @Override public File resolve(String id)throws IOException {
        if(id.equals("environment")&&record.get("workspace") instanceof String){
            String workspace=NativeConfigurationReset.normalizeWorkspace((String)record.get("workspace"));
            return fs.child(files,"linux/ubuntu/"+workspace+"/.env");
        }
        if(!id.equals("profile-patch"))throw new IOException("SETTINGS_TRANSACTION_TARGET");
        return fs.child(home,"profiles/"+profile+"/cordis.patch.yml");
    }
    private HostDataTransaction.Settings nativeSettings(){return new HostDataTransaction.Settings(){
        public Map<String,Object> current(){return new ConfigStore(context).hostSettingsState();}
        public void apply(Map<String,Object> values)throws IOException{new ConfigStore(context).applyHostSettings(values);}
    };}
    private byte[] patch()throws IOException {File path=resolve("profile-patch");return fs.stat(path).type.equals("MISSING")?"[]\n".getBytes(StandardCharsets.UTF_8):fs.small(path,BackupLimits.MANIFEST);}
    private String profileDigest(BackupControl control)throws IOException {
        String identity=BackupTree.digest(fs,fs.child(home,"profiles/"+profile),control)+BackupTree.digest(fs,new File(home,"cordis.patch.yml"),control);
        var runtime=HarnessController.get(context).proot().installedRuntimeDescriptor();if(runtime==null)throw new IOException("RUNTIME_DESCRIPTOR_MISSING");
        return NativeDataLocations.hash(identity+runtime.id());
    }
    public static Map<String,Object> preview(Context context,String retainedKey,BackupControl control)throws Exception {
        return com.deepseekharness.app.BackupManager.runDataTask(HarnessController.get(context),()-> {
            var fs=new AndroidBackupFileSystem();File files=context.getFilesDir().getCanonicalFile();
            RetainedCatalogue catalog=new RetainedCatalogue(fs,files,new UserDataLayout(fs,files).current());var entry=catalog.resolve(retainedKey);
            if(entry.kind!=RetainedCatalogue.Kind.SETTINGS||entry.source==null)throw new IOException("SETTINGS_RETAINED_SOURCE");
            File patch=new File(entry.source,"cordis.patch.yml");byte[] incoming=fs.stat(patch).type.equals("FILE")?fs.small(patch,BackupLimits.MANIFEST):"[]\n".getBytes(StandardCharsets.UTF_8);
            return prepare(context,entry.displayName,incoming,retainedKey,"preview",control);
        });
    }
    private static Map<String,Object> prepare(Context context,String profile,byte[] incoming,String source,String mode,BackupControl control)throws IOException {
        var fs=new AndroidBackupFileSystem();File files=context.getFilesDir().getCanonicalFile(),parent=new File(files,"host-backup-operations");
        if(fs.stat(parent).type.equals("MISSING"))fs.directory(parent);if(fs.list(parent).size()>=BackupLimits.TRANSACTION_RECORDS)throw new IOException("RETAINED_OPERATION_LIMIT");
        String id=UUID.randomUUID().toString();File task=new File(parent,id);fs.directory(task);
        Map<String,Object> record=new LinkedHashMap<>();record.put("version",1L);record.put("id",id);record.put("profile",profile);record.put("home",new UserDataLayout(fs,files).selected().name());record.put("source",source);record.put("mode",mode);
        fs.atomic(task,"incoming.yml",incoming);record.put("incomingHash",BackupTree.digest(fs,new File(task,"incoming.yml"),control));
        fs.atomic(task,RECORD,BackupJson.write(record,BackupLimits.MANIFEST));
        ProfileSettingsTransaction operation=new ProfileSettingsTransaction(context,task);
        String before=operation.profileDigest(control);record.put("profileBefore",before);record.put("patchBefore",BackupTree.digest(fs,operation.resolve("profile-patch"),control));
        ProfileSettingsTrial.Result trial=ProfileSettingsTrial.run(context,HarnessController.get(context).proot(),operation.home,profile,operation.patch(),incoming,Map.of("mode",mode),control);
        if(!before.equals(operation.profileDigest(control)))throw new IOException("TARGET_CHANGED");
        record.put("review",trial.review);fs.atomic(task,RECORD,BackupJson.write(record,BackupLimits.MANIFEST));
        Map<String,Object> output=new LinkedHashMap<>(trial.review);output.put("operation",id);output.put("profile",profile);return output;
    }
    public static Map<String,Object> apply(Context context,String operation,Set<String> selected,BackupControl control)throws Exception {
        if(selected.isEmpty())throw new IOException("SETTINGS_SELECTION_EMPTY");
        return com.deepseekharness.app.BackupManager.runDataTask(HarnessController.get(context),()->open(context,operation).commit(selected,control));
    }
    @SuppressWarnings("unchecked") private Map<String,Object> commit(Set<String> selected,BackupControl control)throws IOException {
        boolean reset="reset".equals(record.get("mode"));
        if(selected.isEmpty()&&!reset)throw new IOException("SETTINGS_SELECTION_EMPTY");
        if(!record.get("profileBefore").equals(profileDigest(control)))throw new IOException("TARGET_CHANGED");
        Map<String,Object> preview=(Map<String,Object>)record.get("review");Set<String> allowed=new HashSet<>();
        for(Object value:(List<?>)preview.get("items"))allowed.add(BackupJson.string((Map<String,Object>)value,"id"));
        if(!allowed.containsAll(selected))throw new IOException("SETTINGS_SELECTION_CHANGED");
        byte[] incoming=fs.small(new File(task,"incoming.yml"),BackupLimits.MANIFEST);
        if(!record.get("incomingHash").equals(BackupTree.digest(fs,new File(task,"incoming.yml"),control)))throw new IOException("INPUT_CHANGED");
        String mode="reset".equals(record.get("mode"))?"reset":"apply";
        ProfileSettingsTrial.Result prepared=ProfileSettingsTrial.run(context,HarnessController.get(context).proot(),home,profile,patch(),incoming,Map.of("mode",mode,"selected",new ArrayList<>(selected)),control);
        if(!record.get("profileBefore").equals(profileDigest(control)))throw new IOException("TARGET_CHANGED");
        File candidate=new File(task,"candidate");fs.directory(candidate);try(OutputStream out=fs.create(new File(candidate,"profile-patch"))){out.write(prepared.patch);}
        var settings=nativeSettings();HostDataTransaction transaction=new HostDataTransaction(fs,task,this,settings,null);
        List<String> roots=new ArrayList<>(List.of("profile-patch"));Map<String,String> before=new LinkedHashMap<>();before.put("profile-patch",(String)record.get("patchBefore"));
        if(record.containsKey("workspace")){
            roots.add("environment");before.put("environment",(String)record.get("environmentBefore"));
            try(OutputStream out=fs.create(new File(candidate,"environment"))){out.write(fs.small(new File(task,"environment-input"),1024*1024));}
        }
        transaction.prepare(roots,before,settings.current(),control);
        transaction.commit(control,new HostDataTransaction.CommitCheck(){
            public void verify()throws IOException {
                ProfileSettingsTrial.run(context,HarnessController.get(context).proot(),home,profile,patch(),incoming,Map.of("mode","verify","expected",prepared.review.get("items")),control);
            }
            public boolean mayRecover(){return !com.deepseekharness.app.core.RuntimeTasks.hasOtherTasks();}
        });
        fs.atomic(task,"settings-readback.json",BackupJson.write(prepared.review,BackupLimits.MANIFEST));return prepared.review;
    }
    private static ProfileSettingsTransaction open(Context context,String id)throws IOException {
        if(id==null||!id.matches("[a-f0-9]{8}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{12}"))throw new IOException("SETTINGS_TRANSACTION_ID");
        return new ProfileSettingsTransaction(context,new File(context.getFilesDir().getCanonicalFile(),"host-backup-operations/"+id));
    }
    @SuppressWarnings("unchecked") public static File reset(Context context,String workspace,byte[] environment,BackupControl control)throws IOException {
        Map<String,Object> preview=prepare(context,"web","[]\n".getBytes(StandardCharsets.UTF_8),"current-profile","reset",control);
        Set<String> selected=new LinkedHashSet<>();for(Object value:(List<?>)preview.get("items"))selected.add(BackupJson.string((Map<String,Object>)value,"id"));
        ProfileSettingsTransaction transaction=open(context,(String)preview.get("operation"));
        transaction.record.put("workspace",NativeConfigurationReset.normalizeWorkspace(workspace));
        transaction.record.put("environmentBefore",BackupTree.digest(transaction.fs,transaction.resolve("environment"),control));
        transaction.fs.atomic(transaction.task,"environment-input",environment);
        transaction.fs.atomic(transaction.task,RECORD,BackupJson.write(transaction.record,BackupLimits.MANIFEST));
        transaction.commit(selected,control);
        return transaction.task;
    }
    public static boolean owns(BackupFileSystem fs,File directory)throws IOException{return fs.stat(new File(directory,RECORD)).type.equals("FILE");}
    public static void recover(Context context,File task)throws IOException {var operation=new ProfileSettingsTransaction(context,task);new HostDataTransaction(operation.fs,task,operation,operation.nativeSettings(),null).recover();}
}
