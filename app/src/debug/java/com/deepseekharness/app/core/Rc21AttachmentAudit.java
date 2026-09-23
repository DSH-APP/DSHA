package com.deepseekharness.app.core;

import android.app.*;
import android.os.*;
import java.io.*;
import java.util.*;
import com.deepseekharness.app.BackupManager;
import com.deepseekharness.app.util.*;

/** 真实 arm64 Node 存储模块：私有目录与 Android FUSE 上的文件/图片落盘，不发送聊天请求。 */
public final class Rc21AttachmentAudit extends Instrumentation {
    private Bundle args;
    @Override public void onCreate(Bundle args){super.onCreate(args);this.args=args;start();}
    private void copy(String asset,File target)throws Exception{
        target.getParentFile().mkdirs();try(var in=getTargetContext().getAssets().open(asset);var out=new FileOutputStream(target)){
            byte[] buffer=new byte[65536];int n;while((n=in.read(buffer))!=-1)out.write(buffer,0,n);
        }
    }
    private void run(HarnessController c,String command)throws Exception{
        java.lang.Process process;
        if("proroot".equals(args.getString("runtime"))) {
            var rt=new com.deepseekharness.app.runtime.ContainerRuntime.Proroot(getTargetContext(),
                    com.deepseekharness.app.runtime.ContainerRuntime.Proroot.defaultDir(getTargetContext()));
            var method=c.proot().getClass().getDeclaredMethod("startRootfs",String.class,com.deepseekharness.app.runtime.ContainerRuntime.class,boolean.class);
            method.setAccessible(true);process=(java.lang.Process)method.invoke(c.proot(),command,rt,false);
        }else process=c.proot().execRootfsForInstall(command);
        var result=BoundedProcessRunner.collect(process,180000,64000,Compat::destroy);
        Bundle progress=new Bundle();progress.putString("output",SensitiveData.redact(result.output));sendStatus(1,progress);
        if(result.timedOut||result.exitCode!=0)throw new AssertionError("command exit="+result.exitCode);
    }
    @Override public void onStart(){
        Bundle result=new Bundle();HarnessController c=HarnessController.get(getTargetContext());File fixture=null;
        try {
            BackupManager.runDataTask(c,()->EnvironmentMaintenance.update(c,text->{}));
            String guest="/root/dsha-rc21-audit-"+UUID.randomUUID();fixture=new File(c.proot().getRootfsDir(),guest.substring(1));
            copy("test-attachment-store.mjs",new File(fixture,"test-attachment-store.mjs"));
            for(String asset:new String[]{"backup-engine.py","backup-plugin-graph.py","register-builtin-plugins.py","environment-data.py"})
                copy(asset,new File(fixture,"app/src/main/assets/"+asset));
            copy("rc21-test-backup.py",new File(fixture,"tools/test-backup-engine.py"));
            copy("rc21-test-personal.py",new File(fixture,"tools/test-environment-data.py"));
            run(c,"node "+ShellQuote.arg(guest+"/test-attachment-store.mjs")+" "+ShellQuote.arg(guest+"/private"));
            File external=getTargetContext().getExternalFilesDir(null);
            if(external==null)throw new IOException("External app storage is unavailable");
            String shared=external.getPath().replaceFirst("^/storage/emulated/[0-9]+", "/sdcard");
            // 与报告相同：私有 DSH_HOME 下的 attachments 链接到 FUSE；不遍历无权列出的 Android/data 父目录。
            run(c,"node "+ShellQuote.arg(guest+"/test-attachment-store.mjs")+" "+ShellQuote.arg(shared)+" "+ShellQuote.arg(guest+"/linked"));
            if(!"proroot".equals(args.getString("runtime"))) {
                run(c,"python3 -B "+ShellQuote.arg(guest+"/tools/test-backup-engine.py"));
                run(c,"python3 -B "+ShellQuote.arg(guest+"/tools/test-environment-data.py"));
            }
            result.putString("result","PASS "+args.getString("runtime","proot"));
        }catch(Throwable error){result.putString("failure",SensitiveData.redact(android.util.Log.getStackTraceString(error)));}
        finally {
            if(fixture!=null)try{EnvironmentMaintenance.deleteTree(fixture);}catch(Exception error){result.putString("cleanup",error.toString());}
            finish(result.containsKey("failure")?1:0,result);
        }
    }
}
