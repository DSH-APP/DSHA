package com.deepseekharness.app.backup;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** 插件日志归入既有维护门禁；只读探测不执行旧树中的任何程序。 */
public final class PluginInstallJournals {
    public static final String DIRECTORY="plugin-install-operations";
    private PluginInstallJournals() { }
    public static List<File> pending(BackupFileSystem fs,File home)throws IOException{
        File directory=new File(home,DIRECTORY);if(fs.stat(directory).type.equals("MISSING"))return Collections.emptyList();
        if(!fs.stat(directory).type.equals("DIRECTORY"))throw new IOException("PLUGIN_JOURNAL_DIRECTORY");
        List<String> names=fs.list(directory);if(names.size()>256)throw new IOException("PLUGIN_JOURNAL_LIMIT");List<File> result=new ArrayList<>();
        for(String id:names){
            if(!id.matches("[a-f0-9]{8}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{12}"))throw new IOException("PLUGIN_JOURNAL_ID");
            File entry=fs.child(directory,id);if(!fs.stat(entry).type.equals("DIRECTORY"))throw new IOException("PLUGIN_JOURNAL_DIRECTORY");
            if(marker(fs,entry,"committed")||marker(fs,entry,"rolled-back"))continue;
            if(!fs.stat(new File(entry,"plan.json")).type.equals("MISSING"))result.add(entry);
        }return result;
    }
    private static boolean marker(BackupFileSystem fs,File directory,String name)throws IOException{
        File marker=new File(directory,name);if(fs.stat(marker).type.equals("MISSING"))return false;
        if(!(directory.getName()+"\n"+name+"\n").equals(new String(fs.small(marker,128),StandardCharsets.US_ASCII)))throw new IOException("PLUGIN_JOURNAL_MARKER");return true;
    }
    public static boolean blocked(File files){
        try{var fs=new AndroidBackupFileSystem();return !pending(fs,new UserDataLayout(fs,files.getCanonicalFile()).current()).isEmpty();}
        catch(IOException error){return true;}
    }
    public static String recover(com.deepseekharness.app.core.HarnessController controller)throws Exception{
        if(!com.deepseekharness.app.BackupManager.isDataTaskOwner()||com.deepseekharness.app.core.RuntimeTasks.hasOtherTasks())throw new IOException("PLUGIN_RECOVERY_REQUIRES_MAINTENANCE");
        controller.proot().ensureRuntimeFiles();var fs=new AndroidBackupFileSystem();
        String id=UUID.randomUUID().toString();File proof=new File(controller.proot().getRootfsDir().getCanonicalFile(),"root/.dsha-plugin-recovery-"+id);
        try{
            try(OutputStream out=fs.create(proof)){out.write("RECOVER_PLUGIN_OPERATIONS".getBytes(StandardCharsets.US_ASCII));}
            String text=controller.proot().execAndReadWithProot("python3 /root/.dsh/plugin-manager.py recover-installs "+com.deepseekharness.app.util.ShellQuote.arg(id),120000);
            var value=new org.json.JSONObject(com.deepseekharness.app.util.PluginOutput.resultJson(text));
            if(!"ok".equals(value.optString("status")))throw new IOException(value.optString("message","PLUGIN_RECOVERY_FAILED"));
            return com.deepseekharness.app.util.UiText.text("中断的插件操作已恢复，旧版本、依赖与失败候选均保留。");
        }finally{if(!fs.stat(proof).type.equals("MISSING"))fs.delete(proof);}
    }
}
