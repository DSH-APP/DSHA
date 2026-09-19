package com.deepseekharness.app.backup;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** 启动只读取固定数量的小日志；不解析用户项目、不运行任何容器命令。 */
public final class HostPendingTransactions {
    private HostPendingTransactions() { }
    public static List<File> pending(BackupFileSystem fs,File files)throws IOException{
        File root=new File(files,"host-backup-operations");if(fs.stat(root).type.equals("MISSING"))return Collections.emptyList();
        List<String> names=fs.list(root);if(names.size()>64)throw new IOException("TRANSACTION_LIMIT");List<File> result=new ArrayList<>();
        for(String name:names){if(!name.matches("[a-f0-9-]{36}"))continue;File directory=fs.child(root,name);
            boolean switching=marker(fs,directory,"switching"),finalized=marker(fs,directory,"finalized"),rolledBack=marker(fs,directory,"rolled-back");
            if(switching&&!finalized&&!rolledBack){if(!fs.stat(new File(directory,"plan.json")).type.equals("FILE"))throw new IOException("TRANSACTION_PLAN_MISSING");result.add(directory);}
        }return result;
    }
    private static boolean marker(BackupFileSystem fs,File directory,String name)throws IOException{
        File file=fs.child(directory,name);if(fs.stat(file).type.equals("MISSING"))return false;
        if(!(directory.getName()+"\n"+name+"\n").equals(new String(fs.small(file,256),StandardCharsets.UTF_8)))throw new IOException("TRANSACTION_MARKER");return true;
    }
    public static boolean blocked(File files){try{return !pending(new AndroidBackupFileSystem(),files.getCanonicalFile()).isEmpty();}catch(IOException error){return true;}}
}
