package com.deepseekharness.app.runtime;
import android.app.*;
import android.os.*;
import java.io.*;
import com.deepseekharness.app.core.*;
import com.deepseekharness.app.util.*;

/** 只读取环境身份和维护记录，不输出凭据、会话或用户文件。 */
public final class TabletEnvironmentAudit extends Instrumentation {
    private Bundle args;
    @Override public void onCreate(Bundle args){super.onCreate(args);this.args=args;start();}
    @Override public void onStart(){
        Bundle result=new Bundle();StringBuilder out=new StringBuilder();
        try {
            var app=getTargetContext();var proot=new ProotBootstrap(app);var task=BackupTask.get(app).snapshot();
            String mode=args.getString("mode","collect");
            if(mode.equals("review")||mode.equals("upgrade"))
                try(var fd=getUiAutomation().executeShellCommand("am start -W -n com.dsh.client/com.deepseekharness.app.ui.MainActivity --ez limited_entry true");var input=new ParcelFileDescriptor.AutoCloseInputStream(fd)){while(input.read()!=-1){}}
            if(mode.equals("tests")) {
                String guest="/tmp/dsha-migration-audit-"+java.util.UUID.randomUUID();
                File fixture=new File(proot.getRootfsDir(),guest.substring(1));
                copyAsset("tablet-tests/test-environment-data.py",new File(fixture,"tools/test-environment-data.py"));
                copyAsset("environment-data.py",new File(fixture,"app/src/main/assets/environment-data.py"));
                var execution=BoundedProcessRunner.collect(proot.execRootfsForInstall("python3 -B "+ShellQuote.arg(guest+"/tools/test-environment-data.py")),120000,64000,Compat::destroy);
                result.putString("testOutput",SensitiveData.redact(execution.output));
                if(execution.exitCode!=0||execution.timedOut)throw new AssertionError("Linux migration tests failed");
                deleteFixture(fixture);
            }
            if(mode.equals("review")) {
                getUiAutomation();
                Activity activity=startActivitySync(new android.content.Intent(app,com.deepseekharness.app.ui.ExtractActivity.class).putExtra("review_only",true).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK));
                waitForIdleSync();String[] detail={""};runOnMainSync(()->detail[0]=((android.widget.TextView)activity.findViewById(com.deepseekharness.app.R.id.extract_detail)).getText().toString());
                if(!detail[0].contains(".l2s")||!detail[0].contains("Permission denied"))throw new AssertionError("上次失败原因没有展示");
                result.putString("review","PASS: last failure visible");runOnMainSync(activity::finish);
            }
            if(mode.equals("upgrade")) {
                getUiAutomation();boolean[] accepted={false};runOnMainSync(()->accepted[0]=BackupTask.get(app).updateEnvironment());
                if(!accepted[0])throw new AssertionError("升级任务未接受");
                long id=BackupTask.get(app).snapshot().id;
                Activity activity=startActivitySync(new android.content.Intent(app,com.deepseekharness.app.ui.ExtractActivity.class).putExtra("data_task_id",id).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK));
                long deadline=android.os.SystemClock.elapsedRealtime()+900000;String previous="";
                while(BackupTask.get(app).busy()&&android.os.SystemClock.elapsedRealtime()<deadline){
                    String detail=BackupTask.get(app).snapshot().detail;
                    if(!previous.equals(detail)){Bundle progress=new Bundle();progress.putString("progress",SensitiveData.redact(detail));sendStatus(1,progress);previous=detail;}
                    Thread.sleep(250);
                }
                task=BackupTask.get(app).snapshot();
                if(task.status!=BackupTaskState.Status.SUCCEEDED||!proot.isEnvironmentReady())throw new AssertionError(SensitiveData.redact(task.detail));
                result.putString("upgrade","PASS: validated migration to "+proot.environmentIdentity());runOnMainSync(activity::finish);
            }
            out.append("TASK ").append(task.status).append(" ").append(task.kind).append("\n").append(task.detail).append("\n");
            out.append("READY ").append(proot.isEnvironmentReady()).append(" EXPECTED ").append(proot.environmentIdentity()).append("\n");
            for(String name:new String[]{"linux/.offline-extracted","linux/.offline-identity","linux/.offline-version"}) {
                File file=new File(app.getFilesDir(),name);out.append(name).append(": ").append(file.exists()?Compat.readAll(file):"MISSING").append("\n");
            }
            for(String parent:new String[]{"","linux","maintenance","runtime-updates"}) {
                File dir=new File(app.getFilesDir(),parent);File[] files=dir.listFiles();if(files==null)continue;
                for(File file:files)out.append(parent).append('/').append(file.getName()).append(file.isDirectory()?" DIR":" "+file.length()).append("\n");
            }
            for(String name:new String[]{"bin/bash","usr/local/bin/node","usr/bin/python3","etc/resolv.conf","usr/local/share/dsha/dsh-runtime.version"}) {
                File file=new File(proot.getRootfsDir(),name);out.append(name).append(" exists=").append(file.exists()).append(" size=").append(file.length()).append("\n");
            }
            var config=new ConfigStore(app);out.append("WEB_FAILURE ").append(config.getWebFailureStage()).append(' ').append(config.getWebFailureReason()).append("\n");
            result.putString("report",SensitiveData.redact(out.toString()));result.putString("result","PASS");
        }catch(Throwable error){result.putString("failure",SensitiveData.redact(android.util.Log.getStackTraceString(error)));}
        finish(result.containsKey("failure")?1:0,result);
    }
    private void copyAsset(String asset,File target)throws Exception{
        target.getParentFile().mkdirs();try(var input=getTargetContext().getAssets().open(asset);var output=new FileOutputStream(target)){byte[] buffer=new byte[8192];int n;while((n=input.read(buffer))!=-1)output.write(buffer,0,n);}
    }
    private void deleteFixture(File file)throws Exception{
        if(!file.getCanonicalPath().startsWith(new File(new ProotBootstrap(getTargetContext()).getRootfsDir(),"tmp").getCanonicalPath()+File.separator+"dsha-migration-audit-"))throw new IOException("测试清理边界无效");
        if(file.isDirectory()&&!Compat.isSymbolicLink(file)){File[] children=file.listFiles();if(children!=null)for(File child:children)deleteFixture(child);}
        if(!file.delete())throw new IOException("测试临时文件清理失败");
    }
}
