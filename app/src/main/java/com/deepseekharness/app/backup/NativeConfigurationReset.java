package com.deepseekharness.app.backup;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** 两个明确配置文件的宿主重置；复用数据事务与中断门禁，原配置保留在 previous 中。 */
public final class NativeConfigurationReset implements HostDataTransaction.Targets {
    private static final String RECORD="config-reset.json";
    private final BackupFileSystem fs;
    private final File files,directory;
    private final UserDataLayout.Home home;
    private final String workspace;
    private NativeConfigurationReset(BackupFileSystem fs,File files,File directory,UserDataLayout.Home home,String workspace)throws IOException{
        this.fs=fs;this.files=files.getAbsoluteFile();this.directory=directory.getAbsoluteFile();this.home=home;this.workspace=normalizeWorkspace(workspace);
        if(!directory.getParentFile().getAbsoluteFile().equals(new File(this.files,"host-backup-operations"))
                ||!directory.getName().matches("[a-f0-9]{8}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{12}"))throw new IOException("RESET_OPERATION_PATH");
    }
    /** 这里不猜测外部挂载和链接；不能在未知目的地写入重置配置。 */
    static String normalizeWorkspace(String value)throws IOException{
        if(value==null||value.isEmpty())throw new IOException("RESET_WORKSPACE_UNAVAILABLE");
        String path=value.startsWith("/")?value.substring(1):value.equals("root")?value:"root/"+value;
        while(path.endsWith("/"))path=path.substring(0,path.length()-1);
        BackupLimits.path(path);
        if(!path.equals("root")&&!path.startsWith("root/"))throw new IOException("RESET_WORKSPACE_OUTSIDE_PRIVATE_HOME");
        return path;
    }
    @Override public File resolve(String id)throws IOException{
        String relative;
        if(id.equals("settings"))relative=(home==UserDataLayout.Home.LEGACY?UserDataLayout.LEGACY:UserDataLayout.STABLE)+"/settings.yaml";
        else if(id.equals("environment"))relative="linux/ubuntu/"+workspace+"/.env";
        else throw new IOException("RESET_TARGET");
        File target=fs.child(files,relative);var node=fs.stat(target);
        if(!node.type.equals("FILE")&&!node.type.equals("MISSING"))throw new IOException("RESET_CONFIG_LINK_OR_SPECIAL");
        if(node.size>1024*1024)throw new IOException("RESET_CONFIG_SIZE");
        if(fs.stat(target.getParentFile()).device!=fs.stat(directory).device)throw new IOException("RESET_FILESYSTEM_BOUNDARY");
        return target;
    }
    public static File reset(BackupFileSystem fs,File files,String workdir,byte[] environment,
                             HostDataTransaction.Settings settings,HostDataTransaction.Fault fault,BackupControl control)throws IOException{
        if(environment==null||environment.length>1024*1024)throw new IOException("RESET_CONFIG_SIZE");
        String workspace=normalizeWorkspace(workdir);
        File parent=new File(files,"host-backup-operations");if(fs.stat(parent).type.equals("MISSING"))fs.directory(parent);
        if(fs.list(parent).size()>=32)throw new IOException("RETAINED_OPERATION_LIMIT");
        File owned=new File(parent,UUID.randomUUID().toString());fs.directory(owned);
        var reset=new NativeConfigurationReset(fs,files,owned,new UserDataLayout(fs,files).selected(),"/"+workspace);
        List<String> roots=List.of("settings","environment");Map<String,String> before=new LinkedHashMap<>();
        for(String root:roots)before.put(root,BackupTree.digest(fs,reset.resolve(root),control));
        try(OutputStream out=fs.create(new File(owned,RECORD))){out.write(BackupJson.write(Map.of("version",1L,"id",owned.getName(),"home",reset.home.name(),"workspace","/"+workspace),4096));}
        fs.syncDirectory(owned);
        File candidate=new File(owned,"candidate");fs.directory(candidate);
        try(OutputStream out=fs.create(new File(candidate,"settings"))){out.write("{}\n".getBytes(StandardCharsets.UTF_8));}
        try(OutputStream out=fs.create(new File(candidate,"environment"))){out.write(environment);}
        HostDataTransaction transaction=new HostDataTransaction(fs,owned,reset,settings,fault);
        transaction.prepare(roots,before,settings.current(),control);transaction.commit(control);return owned;
    }
    public static boolean owns(BackupFileSystem fs,File directory)throws IOException{
        var state=fs.stat(new File(directory,RECORD));if(state.type.equals("MISSING"))return false;
        if(!state.type.equals("FILE"))throw new IOException("RESET_OPERATION_RECORD");return true;
    }
    public static void recover(BackupFileSystem fs,File files,File directory,HostDataTransaction.Settings settings,HostDataTransaction.Fault fault)throws IOException{
        Map<String,Object> record=BackupJson.read(fs.small(new File(directory,RECORD),4096),4096);
        if(BackupJson.number(record,"version")!=1||!directory.getName().equals(BackupJson.string(record,"id")))throw new IOException("RESET_OPERATION_RECORD");
        UserDataLayout.Home home;try{home=UserDataLayout.Home.valueOf(BackupJson.string(record,"home"));}catch(IllegalArgumentException error){throw new IOException("RESET_OPERATION_RECORD",error);}
        var reset=new NativeConfigurationReset(fs,files,directory,home,BackupJson.string(record,"workspace"));
        new HostDataTransaction(fs,directory,reset,settings,fault).recover();
    }
}
