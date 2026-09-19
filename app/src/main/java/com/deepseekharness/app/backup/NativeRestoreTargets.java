package com.deepseekharness.app.backup;

import android.content.Context;
import android.os.Environment;
import com.deepseekharness.app.core.ConfigStore;
import com.deepseekharness.app.util.BuiltinPlugins;
import java.io.*;
import java.util.*;

/** 本机映射决定所有写入目标；归档提供的名称只作为受限逻辑叶名，不提供宿主绝对路径。 */
public final class NativeRestoreTargets implements NativeRestorePlan.Mapping,HostDataTransaction.Targets,HostDataTransaction.Settings {
    private final Context context;
    private final BackupFileSystem fs;
    private final File files,rootfs,task,homeWrite,homeRead;
    private final GuestDataResolver resolver;
    private final String homeMode;
    private final String projectDestination;
    private final String layoutBefore;
    private final Map<String,File> known=new LinkedHashMap<>();
    public NativeRestoreTargets(Context context,File task)throws IOException{
        this(context,task,"PRIVATE");
    }
    public NativeRestoreTargets(Context context,File task,String requestedProjects)throws IOException{
        this.context=context.getApplicationContext();this.task=task;fs=new AndroidBackupFileSystem();files=context.getFilesDir().getCanonicalFile();rootfs=new File(files,"linux/ubuntu");
        File publicRoot=Environment.getExternalStorageDirectory().getCanonicalFile(),publicData=new File(publicRoot,"Documents/dshdata"),stable=new File(files,"user-data-v5/dsh"),legacy=new File(rootfs,"root/.dsh");
        resolver=new GuestDataResolver(fs,rootfs,publicRoot,Arrays.asList(publicData,new File(files,"user-data-v5")));
        UserDataLayout layout=new UserDataLayout(fs,files);layoutBefore=BackupTree.digest(fs,new File(files,UserDataLayout.RECORD),new BackupControl(null));
        File mapping=new File(task,"mapping.json");
        if(fs.stat(mapping).type.equals("FILE")){
            Map<String,Object> value=BackupJson.read(fs.small(mapping,4096),4096);if(!task.getName().equals(BackupJson.string(value,"id")))throw new IOException("RESTORE_MAPPING");
            homeMode=BackupJson.string(value,"home");if(!homeMode.equals("STABLE")&&!homeMode.equals("LEGACY"))throw new IOException("RESTORE_MAPPING");
            projectDestination=value.get("projects") instanceof String?(String)value.get("projects"):"PRIVATE";
        }else{
            homeMode=layout.selected()==UserDataLayout.Home.STABLE||!fs.stat(new File(rootfs,"root")).type.equals("DIRECTORY")?"STABLE":"LEGACY";
            projectDestination=requestedProjects;
            if(!Set.of("PRIVATE","GUEST_HOME").contains(projectDestination))throw new IOException("PROJECT_DESTINATION");
            try(OutputStream out=fs.create(mapping)){out.write(BackupJson.write(Map.of("id",task.getName(),"home",homeMode,"projects",projectDestination),4096));}
        }
        if(!Set.of("PRIVATE","GUEST_HOME").contains(projectDestination))throw new IOException("PROJECT_DESTINATION");
        homeWrite=homeMode.equals("STABLE")?stable:legacy;
        File preferred=layout.current();File actual=resolver.resolve(preferred).file;
        if(fs.stat(actual).type.equals("MISSING")&&!fs.stat(publicData).type.equals("MISSING"))actual=publicData;
        homeRead=fs.stat(actual).type.equals("MISSING")?null:actual;
        known.put("dsh-home",homeWrite);known.put("native-settings",new File(files,"host-native-settings-state.json"));
        known.put("plugin-store",new File(files,"plugin-imports/"+task.getName()));
        known.put("system-plugin-profiles",new File(homeWrite,"profiles"));
        known.put("data-layout",new File(files,UserDataLayout.RECORD));
    }
    @Override public NativeRestorePlan.Target target(Map<String,Object> root)throws IOException{
        String id=BackupLimits.root(BackupJson.string(root,"id")),scope=BackupJson.string(root,"scope");
        if(id.equals("native-settings")){if(!scope.equals("settings"))throw new IOException("ROOT_SCOPE");return new NativeRestorePlan.Target(known.get(id),null,false);}
        String kind=BackupJson.string(root,"logicalKind"),name=BackupJson.string(root,"name");
        if(kind.equals("legacy-plugin-store")&&id.equals("legacy-plugins")&&scope.equals("plugins")){
            return quarantine("legacy-plugins");
        }
        if(kind.equals("dsh-child")){
            BackupLimits.path(name);if(name.isEmpty()||name.contains("/")||!id.equals(NativeDataLocations.id(name))||!scope.equals(NativeDataLocations.classify(name)))throw new IOException("ROOT_MAPPING");
            if(DataRootPolicy.machine(name))return null;
            if(scope.equals("plugins")||DataRootPolicy.quarantineCode(name)||!DataRootPolicy.directData(name)){
                return quarantine(name.equals("profiles")?"profiles":"declarations/"+name);
            }
            File member=homeRead==null?null:resolver.resolve(new File(homeRead,name)).file;
            return new NativeRestorePlan.Target(homeWrite,homeRead,true,"dsh-home",name,member);
        }
        if(kind.equals("plugin-package")){
            if(!scope.equals("plugins")||!id.matches("package-[a-f0-9]{20}")||!name.matches("(?:@[A-Za-z0-9._-]+/)?[A-Za-z0-9._-]+"))throw new IOException("PLUGIN_MAPPING");
            if(BuiltinPlugins.system(name))return null;
            return quarantine("packages/"+id);
        }
        if(kind.equals("project")){
            if(!scope.equals("projects")||!id.matches("project-[a-f0-9]{20}"))throw new IOException("PROJECT_MAPPING");
            File target=project(id);known.put(id,target);return new NativeRestorePlan.Target(target,null,false);
        }
        throw new IOException("UNKNOWN_LOGICAL_ROOT");
    }
    private NativeRestorePlan.Target quarantine(String prefix){return new NativeRestorePlan.Target(known.get("plugin-store"),null,false,"plugin-store",prefix,null);}
    @Override public File resolve(String root)throws IOException{
        BackupLimits.root(root);File value=known.get(root);if(value!=null)return value;
        if(root.matches("package-[a-f0-9]{20}"))return new File(files,"plugin-imports/"+task.getName()+"/packages/"+root);
        if(root.matches("project-[a-f0-9]{20}"))return project(root);
        if(root.matches("data-[a-f0-9]{20}")||root.equals("profiles"))return new File(files,"plugin-imports/"+task.getName()+"/"+root);
        if(root.equals("legacy-plugins"))return new File(files,"plugin-imports/"+task.getName()+"/legacy-plugins");
        throw new IOException("UNKNOWN_LOCAL_TARGET");
    }
    public long prepareSystemPluginState(Map<String,Object> graph,List<String> roots,Map<String,String> before,File candidates,BackupControl control)throws IOException{
        Map<String,Object> state=SystemPluginState.fromGraph(graph);if(state.isEmpty())return 0;
        if(roots.contains("dsh-home"))return SystemPluginState.apply(fs,new File(candidates,"dsh-home/profiles"),state,control,true);
        String id="system-plugin-profiles";File target=new File(homeWrite,"profiles"),candidate=new File(candidates,id),source=target;
        if(fs.stat(source).type.equals("MISSING")&&homeRead!=null)source=new File(homeRead,"profiles");
        known.put(id,target);before.put(id,BackupTree.digest(fs,target,control));
        if(fs.stat(source).type.equals("DIRECTORY"))BackupTree.copy(fs,source,candidate,control);else if(fs.stat(source).type.equals("MISSING"))fs.directory(candidate);else throw new IOException("PLUGIN_SYSTEM_PROFILE_TYPE");
        long applied=SystemPluginState.apply(fs,candidate,state,control,true);roots.add(id);return applied;
    }
    private File project(String id){return new File(files,(projectDestination.equals("GUEST_HOME")?"linux/ubuntu/root/restored-projects/":"user-data-v5/restored-projects/")+task.getName()+"/"+id);}
    @Override public String skippedReason(Map<String,Object> root){
        if(root.get("name") instanceof String&&BuiltinPlugins.system((String)root.get("name")))return "CURRENT_APK_SYSTEM_PLUGIN";
        return root.get("name") instanceof String&&DataRootPolicy.machine((String)root.get("name"))?"MACHINE_STATE_RETAINED_IN_INPUT":"ROOT_REQUIRES_DESTINATION";
    }
    public void prepareParents(Collection<String> roots)throws IOException{
        for(String id:roots){File file=resolve(id);if(!GuestDataResolver.within(files,file))throw new IOException("TARGET_OUTSIDE_APP");String relative=file.getPath().substring(files.getPath().length()+1);fs.parents(files,relative);}
    }
    @Override public Map<String,Object> current(){return new ConfigStore(context).hostSettingsState();}
    @Override public void apply(Map<String,Object> values)throws IOException{new ConfigStore(context).applyHostSettings(values);}
    public Map<String,Object> proposedSettings(File candidate,boolean includeKey)throws IOException{
        File nativeData=new File(candidate,"native-settings");
        boolean hasNative=!fs.stat(nativeData).type.equals("MISSING");
        try{
            org.json.JSONObject incoming=!hasNative
                    ? new org.json.JSONObject()
                    : new org.json.JSONObject(new String(fs.small(nativeData,BackupLimits.MANIFEST),java.nio.charset.StandardCharsets.UTF_8));
            // 旧版离线包备份把凭据放在 .dsh/.dsha-apikey，而不是
            // .dsha-native-config.json。LegacyBackupImporter 会把它还原到
            // dsh-home 候选树；在用户明确勾选「恢复原生 API Key」时，
            // 将其转换为当前 SharedPreferences/Keystore 配置，避免恢复后
            // 页面显示成功但实际 API Key 仍为空（issue #22）。
            File legacyKey=new File(candidate,"dsh-home/.dsha-apikey");
            boolean hasLegacyKey=fs.stat(legacyKey).type.equals("FILE");
            if(includeKey&&!incoming.has("apiKey")&&hasLegacyKey){
                    String key=new String(fs.small(legacyKey,16*1024),java.nio.charset.StandardCharsets.UTF_8).trim();
                    if(!key.isEmpty()&&key.length()>=8&&!containsWhitespace(key))incoming.put("apiKey",key);
            }
            // 原生配置已经在 prepareHostSettings 中加密并读回验证。无论用户是否
            // 选择恢复 Key，都不把旧归档里的明文 .dsha-apikey 发布回活跃数据树。
            if(hasLegacyKey)fs.delete(legacyKey);
            if(!hasNative&&!incoming.has("apiKey"))return current();
            Map<String,Object> prepared=new ConfigStore(context).prepareHostSettings(incoming,includeKey);
            if(hasNative)fs.delete(nativeData);
            try(OutputStream out=fs.create(nativeData)){out.write(BackupJson.write(prepared,BackupLimits.MANIFEST));}
            return prepared;
        }catch(org.json.JSONException error){throw new IOException("SETTINGS_FORMAT",error);}
    }
    private static boolean containsWhitespace(String value){
        for(int i=0;i<value.length();i++)if(Character.isWhitespace(value.charAt(i)))return true;
        return false;
    }
    public void prepareLayout(List<String> roots,Map<String,String> expected,File candidates)throws IOException{
        if(!roots.contains("dsh-home"))return;
        try(OutputStream out=fs.create(fs.child(candidates,"data-layout"))){out.write(UserDataLayout.record(UserDataLayout.Home.valueOf(homeMode)));}
        roots.add("data-layout");expected.put("data-layout",layoutBefore);
    }
}
