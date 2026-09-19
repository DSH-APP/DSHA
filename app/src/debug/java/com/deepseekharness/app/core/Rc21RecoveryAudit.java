package com.deepseekharness.app.core;

import android.app.*;
import android.content.*;
import android.os.*;
import java.io.*;
import java.util.*;
import com.deepseekharness.app.BackupManager;
import com.deepseekharness.app.util.*;

/** 发布配置下隔离构造缺 Bash 的旧环境；只改测试私有目录，验证数据保护和失败回切。 */
public final class Rc21RecoveryAudit extends Instrumentation {
    private int checks;
    private void check(boolean ok,String message){checks++;if(!ok)throw new AssertionError(message);}
    private void write(File file,String text)throws Exception{file.getParentFile().mkdirs();Compat.write(file,text);}
    private void report(String text){Bundle result=new Bundle();result.putString("progress",SensitiveData.redact(text));sendStatus(1,result);}
    @Override public void onCreate(Bundle args){super.onCreate(args);start();}
    @Override public void onStart(){
        Bundle result=new Bundle();File base=new File(getTargetContext().getCacheDir(),"rc21-recovery-"+UUID.randomUUID());
        try {
            BackupManager.stopWebForMaintenance(HarnessController.get(getTargetContext()));
            Context isolated=new ContextWrapper(getTargetContext()){
                @Override public Context getApplicationContext(){return this;}
                @Override public File getFilesDir(){File f=new File(base,"files");f.mkdirs();return f;}
                @Override public File getCacheDir(){File f=new File(base,"cache");f.mkdirs();return f;}
                @Override public SharedPreferences getSharedPreferences(String name,int mode){return super.getSharedPreferences(base.getName()+"-"+name,mode);}
            };
            HarnessController c=new HarnessController(isolated);
            File root=c.proot().getRootfsDir();
            write(new File(root,"root/.dsh/sessions/owned/session.jsonl"),"{\"message\":\"owned conversation\"}\n");
            write(new File(root,"root/.dsh/settings.yaml"),"model: owned-model\n");
            write(new File(root,"root/.dsh/storages/workspace.json"),"{\"tables\":{\"workspaces\":{}}}");
            write(new File(root,"root/.dsh/profiles/web/package.json"),"{\"dependencies\":{}}");
            write(new File(root,"root/.dsh/attachments/owned.txt"),"owned attachment");
            write(new File(root,"root/project/owned.txt"),"owned project");
            write(new File(root,"usr/bin/bash"),"not an ELF shell");
            write(new File(root.getParentFile(),".offline-extracted"),c.proot().environmentIdentity());
            write(new File(root.getParentFile(),".offline-identity"),c.proot().environmentIdentity());
            c.config().setWorkdir("/root/project");
            check(!c.proot().hasBash()&&!c.isEnvironmentReady(),"损坏启动入口不能因旧标记而就绪");
            check(!c.startWebSafely(null),"损坏系统不得尝试创建安全配置");
            File archive=new File(base,"conversations.tar.gz"),personal=new File(base,"personal.tar.gz");
            BackupManager.runDataTask(c,()->{
                report("保护缺少 Bash 的环境数据");
                String hash=BackupManager.createMaintenanceBackup(c,archive);
                check(hash.matches("[0-9a-f]{64}")&&archive.length()>0,"独立工具未生成数据备份");
                EnvironmentDataBackup.Snapshot data=EnvironmentDataBackup.snapshot(c,personal,this::report);
                check(data.unpackedBytes>0&&personal.isFile(),"独立工具未保护个人项目");
                check(!new File(root,"bin/bash").exists(),"备份不应篡改或修补旧系统");
                return null;
            });
            try {
                BackupManager.runDataTask(c,()->EnvironmentMaintenance.rebuild(c,this::report,p->{throw new IOException("EXPECTED_AFTER_SWITCH");}));
                throw new AssertionError("应注入新环境解压失败");
            } catch(IOException expected){check(expected.getMessage().contains("EXPECTED_AFTER_SWITCH"),expected.toString());}
            check(Compat.readAll(new File(root,"root/.dsh/sessions/owned/session.jsonl")).contains("owned conversation"),"失败回切丢失对话");
            check(Compat.readAll(new File(root,"root/project/owned.txt")).equals("owned project"),"失败回切丢失项目");
            report("执行真实重建，确认只恢复个人数据");
            BackupManager.runDataTask(c,()->EnvironmentMaintenance.rebuild(c,this::report));
            check(c.isEnvironmentReady(),"新系统未通过就绪检查");
            check(Compat.readAll(new File(root,"root/.dsh/sessions/owned/session.jsonl")).contains("owned conversation"),"重建丢失对话");
            check(Compat.readAll(new File(root,"root/.dsh/attachments/owned.txt")).equals("owned attachment"),"重建丢失附件");
            check(Compat.readAll(new File(root,"root/project/owned.txt")).equals("owned project"),"重建丢失项目");
            File packageFile=new File(root,"usr/local/lib/node_modules/@deepseek-ai/dsh/package.json");
            check(packageFile.renameTo(new File(packageFile.getPath()+".owned-test")),"无法构造受管 dsh 缺失");
            check(c.proot().canUpdateManagedRuntime(),"仅 dsh 损坏不应触发 Ubuntu 重建");
            int backups=new File(isolated.getFilesDir(),"maintenance").list().length;
            BackupManager.runDataTask(c,()->EnvironmentMaintenance.update(c,this::report));
            check(packageFile.isFile()&&c.isEnvironmentReady(),"受管运行时修复未完成");
            check(new File(isolated.getFilesDir(),"maintenance").list().length==backups,"同基础升级不应创建重建安全备份");
            check(Compat.readAll(new File(root,"root/project/owned.txt")).equals("owned project"),"受管更新丢失项目");
            result.putString("result","PASS");result.putInt("checks",checks);
        }catch(Throwable error){result.putString("failure",SensitiveData.redact(android.util.Log.getStackTraceString(error)));}
        finally {result.putString("fixture",base.getPath());finish(result.containsKey("failure")?1:0,result);}
    }
}
