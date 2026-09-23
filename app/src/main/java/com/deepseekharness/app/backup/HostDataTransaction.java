package com.deepseekharness.app.backup;

import java.io.*;
import java.util.*;

/** rootfs 外的可收敛多根事务；目标仅由本机可信映射返回，日志没有任意绝对删除路径。 */
public final class HostDataTransaction {
    public interface Targets { File resolve(String rootId)throws IOException; }
    public interface Settings {
        Map<String,Object> current()throws IOException;
        /** 接收已预先加密并校验的本机配置值；不得在文件切换之后才生成密钥。 */
        void apply(Map<String,Object> values)throws IOException;
    }
    public interface Fault { void boundary(String name)throws IOException; }
    public interface CommitCheck {
        /** 文件已切换、普通写任务仍被屏障阻止；返回前必须确认验证子进程已退出。 */
        void verify()throws IOException;
        /** 退出不明时不得自动移动正在使用的树。 */
        default boolean mayRecover(){return true;}
    }
    private final BackupFileSystem fs;
    private final File directory;
    private final Targets targets;
    private final Settings settings;
    private final Fault fault;
    public HostDataTransaction(BackupFileSystem fs,File directory,Targets targets,Settings settings,Fault fault)throws IOException{
        if(!directory.getName().matches("[a-f0-9]{8}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{12}"))throw new IOException("TRANSACTION_ID");
        this.fs=fs;this.directory=directory;this.targets=targets;this.settings=settings;this.fault=fault==null?n->{}:fault;
        if(!fs.stat(directory).type.equals("DIRECTORY"))throw new IOException("TRANSACTION_DIRECTORY");
    }
    public File candidate(String id)throws IOException{BackupLimits.root(id);return fs.child(directory,"candidate/"+id);}
    private File previous(String id)throws IOException{return fs.child(directory,"previous/"+BackupLimits.root(id));}
    private File failed(String id)throws IOException{return fs.child(directory,"failed/"+BackupLimits.root(id));}
    private boolean has(String name)throws IOException{
        File file=fs.child(directory,name);BackupFileSystem.Node node=fs.stat(file);if(node.type.equals("MISSING"))return false;
        if(!node.type.equals("FILE"))throw new IOException("TRANSACTION_MARKER");
        if(!name.equals("plan.json")){
            String expected=directory.getName()+"\n"+name+"\n";
            if(!expected.equals(new String(fs.small(file,256),java.nio.charset.StandardCharsets.UTF_8)))throw new IOException("TRANSACTION_MARKER");
        }return true;
    }
    private void mark(String name)throws IOException{
        if(has(name))return;try(OutputStream out=fs.create(fs.child(directory,name))){out.write((directory.getName()+"\n"+name+"\n").getBytes(java.nio.charset.StandardCharsets.UTF_8));}
        fs.syncDirectory(directory);fault.boundary(name);
    }
    public void prepare(List<String> roots,Map<String,String> expectedBefore,Map<String,Object> proposedSettings,BackupControl control)throws IOException{
        prepare(roots,expectedBefore,proposedSettings,control,Collections.emptySet());
    }
    public void prepare(List<String> roots,Map<String,String> expectedBefore,Map<String,Object> proposedSettings,BackupControl control,Set<String> removals)throws IOException{
        if(roots.isEmpty()||roots.size()>2048||new HashSet<>(roots).size()!=roots.size()||!roots.containsAll(removals)||has("plan.json"))throw new IOException("TRANSACTION_PLAN");
        for(String child:new String[]{"previous","failed"})if(fs.stat(new File(directory,child)).type.equals("MISSING"))fs.directory(new File(directory,child));
        Map<String,Object> plan=new LinkedHashMap<>();plan.put("version",1L);plan.put("id",directory.getName());plan.put("settingsBefore",normalized(settings.current()));plan.put("settingsAfter",normalized(proposedSettings));
        List<Map<String,Object>> rows=new ArrayList<>();Set<String> uniqueTargets=new HashSet<>();
        for(String root:roots){control.check();BackupLimits.root(root);File target=targets.resolve(root);
            String path=target.getAbsolutePath();for(String seen:uniqueTargets)if(path.equals(seen)||path.startsWith(seen+File.separator)||seen.startsWith(path+File.separator))throw new IOException("OVERLAPPING_TARGETS");uniqueTargets.add(path);
            String before=BackupTree.digest(fs,target,control),expected=expectedBefore.get(root);
            if(!before.equals(expected))throw new IOException("TARGET_CHANGED");
            File candidate=candidate(root);boolean candidatePresent=!fs.stat(candidate).type.equals("MISSING");
            if(candidatePresent==removals.contains(root))throw new IOException("CANDIDATE_MISSING");
            Map<String,Object> row=new LinkedHashMap<>();row.put("root",root);row.put("before",before);row.put("after",BackupTree.digest(fs,candidate,control));row.put("had",!fs.stat(target).type.equals("MISSING"));row.put("candidatePresent",candidatePresent);rows.add(row);
        }
        plan.put("roots",rows);byte[] bytes=BackupJson.write(plan,BackupLimits.MANIFEST);
        try(OutputStream out=fs.create(fs.child(directory,"plan.json"))){out.write(bytes);}fs.syncDirectory(directory);mark("verified");
    }
    private static Map<String,Object> normalized(Map<String,Object> value)throws IOException{return BackupJson.read(BackupJson.write(value,BackupLimits.MANIFEST),BackupLimits.MANIFEST);}
    private Map<String,Object> plan()throws IOException{
        Map<String,Object> plan=BackupJson.read(fs.small(fs.child(directory,"plan.json"),BackupLimits.MANIFEST),BackupLimits.MANIFEST);
        if(BackupJson.number(plan,"version")!=1||!directory.getName().equals(BackupJson.string(plan,"id")))throw new IOException("TRANSACTION_PLAN");
        if(!(plan.get("settingsBefore") instanceof Map)||!(plan.get("settingsAfter") instanceof Map))throw new IOException("TRANSACTION_SETTINGS");rows(plan);return plan;
    }
    @SuppressWarnings("unchecked") private List<Map<String,Object>> rows(Map<String,Object> plan)throws IOException{
        Object value=plan.get("roots");if(!(value instanceof List)||((List<?>)value).isEmpty()||((List<?>)value).size()>2048)throw new IOException("TRANSACTION_PLAN");
        Set<String> roots=new HashSet<>(),paths=new HashSet<>();List<Map<String,Object>> rows=new ArrayList<>();
        for(Object row:(List<?>)value){if(!(row instanceof Map))throw new IOException("TRANSACTION_PLAN");Map<String,Object> r=(Map<String,Object>)row;
            String id=BackupLimits.root(BackupJson.string(r,"root"));if(!roots.add(id))throw new IOException("TRANSACTION_PLAN");
            for(String key:new String[]{"before","after"})if(!BackupJson.string(r,key).matches("[a-f0-9]{64}"))throw new IOException("TRANSACTION_PLAN");
            if(!(r.get("had") instanceof Boolean)||r.containsKey("candidatePresent")&&!(r.get("candidatePresent") instanceof Boolean))throw new IOException("TRANSACTION_PLAN");String path=targets.resolve(id).getAbsolutePath();
            for(String seen:paths)if(path.equals(seen)||path.startsWith(seen+File.separator)||seen.startsWith(path+File.separator))throw new IOException("TRANSACTION_PLAN");paths.add(path);rows.add(r);
        }return rows;
    }
    public void commit(BackupControl control)throws IOException{commit(control,()->{});}
    @SuppressWarnings("unchecked") public void commit(BackupControl control,CommitCheck validation)throws IOException{
        Map<String,Object> plan=plan();if(has("finalized"))return;if(has("rolling-back")||has("rolled-back"))throw new IOException("TRANSACTION_ROLLING_BACK");
        if(!has("verified")||has("switching"))throw new IOException("TRANSACTION_RECOVERY_REQUIRED");
        List<Map<String,Object>> rows=rows(plan);
        // 最后一次可直接取消的边界；后续错误必须先回滚或保留现场再释放屏障。
        for(var row:rows){String id=BackupJson.string(row,"root");if(!BackupJson.string(row,"before").equals(BackupTree.digest(fs,targets.resolve(id),control)))throw new IOException("TARGET_CHANGED");
            if(!BackupJson.string(row,"after").equals(BackupTree.digest(fs,candidate(id),control)))throw new IOException("CANDIDATE_CHANGED");}
        if(!plan.get("settingsBefore").equals(normalized(settings.current())))throw new IOException("SETTINGS_CHANGED");control.check();mark("switching");
        try {
            for(var row:rows){String id=BackupJson.string(row,"root");File target=targets.resolve(id);
                if((Boolean)row.get("had")){fs.move(target,previous(id));fault.boundary("old-"+id);}
                if(!Boolean.FALSE.equals(row.get("candidatePresent")))fs.move(candidate(id),target);fault.boundary("new-"+id);
            }
            mark("files-committed");settings.apply((Map<String,Object>)plan.get("settingsAfter"));fault.boundary("settings-write");mark("settings-committed");
            BackupControl finishing=new BackupControl(control.progress);
            for(var row:rows)if(!BackupJson.string(row,"after").equals(BackupTree.digest(fs,targets.resolve(BackupJson.string(row,"root")),finishing)))throw new IOException("COMMIT_VERIFICATION");
            if(!plan.get("settingsAfter").equals(normalized(settings.current())))throw new IOException("SETTINGS_COMMIT_VERIFICATION");
            validation.verify();fault.boundary("validation-complete");
            for(var row:rows)if(!BackupJson.string(row,"after").equals(BackupTree.digest(fs,targets.resolve(BackupJson.string(row,"root")),finishing)))throw new IOException("VALIDATION_CHANGED_MANAGED_DATA");
            mark("finalized");
        }catch(IOException failure){if(validation.mayRecover())try{recover();}catch(IOException retained){failure.addSuppressed(retained);}throw failure;}
    }
    @SuppressWarnings("unchecked") public void recover()throws IOException{
        if(has("finalized")||has("rolled-back"))return;Map<String,Object> plan=plan();List<Map<String,Object>> rows=rows(plan);
        if(!has("switching"))return;mark("rolling-back");Collections.reverse(rows);BackupControl control=new BackupControl(null);
        for(var row:rows){String id=BackupJson.string(row,"root");File target=targets.resolve(id),old=previous(id),failed=failed(id);boolean had=(Boolean)row.get("had");
            String current=BackupTree.digest(fs,target,control),before=BackupJson.string(row,"before"),after=BackupJson.string(row,"after");
            if(!fs.stat(old).type.equals("MISSING")){
                if(!before.equals(BackupTree.digest(fs,old,control)))throw new IOException("ORIGINAL_CHANGED");
                if(!fs.stat(target).type.equals("MISSING")){
                    if(!after.equals(current)||!fs.stat(failed).type.equals("MISSING"))throw new IOException("FAILED_RETAINED");fs.move(target,failed);fault.boundary("rollback-new-"+id);
                }
                fs.move(old,target);fault.boundary("rollback-old-"+id);
            }else if(!had){
                if(!fs.stat(target).type.equals("MISSING")){if(!after.equals(current)||!fs.stat(failed).type.equals("MISSING"))throw new IOException("FAILED_RETAINED");fs.move(target,failed);fault.boundary("rollback-new-"+id);}
            }else if(!before.equals(current))throw new IOException("ORIGINAL_MISSING");
        }
        Map<String,Object> before=(Map<String,Object>)plan.get("settingsBefore"),after=(Map<String,Object>)plan.get("settingsAfter"),current=normalized(settings.current());
        if(!current.equals(before)&&!current.equals(after))throw new IOException("SETTINGS_CHANGED");
        if(!current.equals(before)){settings.apply(before);fault.boundary("rollback-settings");}mark("rolled-back");
    }
    public boolean finalized()throws IOException{return has("finalized");}
    public File directory(){return directory;}
}
