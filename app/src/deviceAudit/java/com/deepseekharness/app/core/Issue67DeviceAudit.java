package com.deepseekharness.app.core;

import android.app.*;
import android.os.Bundle;
import android.view.View;
import com.deepseekharness.app.*;
import com.deepseekharness.app.backup.*;
import com.deepseekharness.app.ui.ExtractActivity;
import com.deepseekharness.app.util.*;
import java.io.*;
import java.util.*;

/** #67 的真实 proot 插件定位、旧 persona、维护导航与非调试 PTY 回归；只操作验收安装。 */
public final class Issue67DeviceAudit extends Instrumentation {
    private final List<Map<String,Object>> tests=new ArrayList<>();private final AndroidBackupFileSystem fs=new AndroidBackupFileSystem();
    private void check(boolean value,String message)throws IOException{if(!value)throw new IOException(message);}
    private void passed(String name){tests.add(Map.of("name",name,"status","PASS"));Bundle value=new Bundle();value.putString("case",name);value.putString("status","PASS");sendStatus(1,value);}
    private void put(File directory,String name,byte[] bytes)throws IOException{fs.parents(directory,name);try(OutputStream out=fs.create(new File(directory,name))){out.write(bytes);}}
    private static void field(Object target,String name,Object value)throws Exception{var field=target.getClass().getDeclaredField(name);field.setAccessible(true);field.set(target,value);}
    private static void render(ExtractActivity activity)throws Exception{var method=ExtractActivity.class.getDeclaredMethod("render");method.setAccessible(true);method.invoke(activity);}
    private void ui(Checked task)throws Exception{Throwable[] error={null};runOnMainSync(()->{try{task.run();}catch(Throwable e){error[0]=e;}});if(error[0]!=null)throw new Exception(error[0]);}
    private interface Checked { void run()throws Exception; }
    @Override public void onCreate(Bundle args){super.onCreate(args);start();}
    @Override public void onStart(){Bundle result=new Bundle();Activity screen=null;HarnessController controller=null;BackupTaskState model=null;BackupTaskState.Snapshot original=null;File descriptor=null,parked=null;Boolean originalProroot=null;
        final PtySession[] terminal={null};
        final java.util.concurrent.atomic.AtomicReference<String> terminalEvidence=new java.util.concurrent.atomic.AtomicReference<>();
        try{
            var context=getTargetContext();DeviceAuditSupport.requireIsolated(context);screen=DeviceAuditSupport.open(this,DeviceAuditActivity.class);controller=HarnessController.get(context);HarnessController active=controller;
            check(controller.isEnvironmentReady()&&EnvironmentAccess.runtimeLatest(controller),"RUN_UPDATED_RUNTIME_AUDIT_FIRST");originalProroot=controller.config().isProroot();controller.config().setProroot(false);
            File files=context.getFilesDir().getCanonicalFile(),root=new File(files,"linux/ubuntu");String nonce=UUID.randomUUID().toString();
            File fixture=new File(root,"root/issue67-"+nonce);fs.directory(fixture);String guest="/root/issue67-"+nonce;
            List<String> names=new ArrayList<>();for(int i=0;i<17;i++)names.add("dsha67-"+nonce.substring(0,8)+"-"+i);
            BackupManager.runDataTask(controller,()->{
                for(String name:names){File plugin=new File(root,"usr/local/lib/node_modules/"+name);fs.directory(plugin);
                    put(plugin,"package.json",BackupJson.write(Map.of("name",name,"version","1.0.0","type","module","exports",Map.of(".","./index.js"),"dsh",Map.of("bundle",Map.of("patch","cordis.patch.yml"))),4096));
                    put(plugin,"cordis.patch.yml","[]\n".getBytes());put(plugin,"index.js","throw Error('observer must not execute this plugin');\n".getBytes());}
                put(fixture,"home/profiles/web/package.json",BackupJson.write(Map.of("dsh",Map.of("profile",Map.of("bundles",names))),4096));
                String preset="- id: persona\n  name: '@deepseek-ai/dsh-persona'\n  config:\n    text: |-\n      Keep this persona\n      保留原始提示词\n";
                put(fixture,".agent-presets/liangshen/agent.cordis.yml",preset.getBytes(java.nio.charset.StandardCharsets.UTF_8));
                String script="import fs from 'node:fs';import{createRequire}from'node:module';const r=createRequire('/usr/local/lib/node_modules/@deepseek-ai/dsh/package.json');"
                        +"const text=fs.readFileSync('./.agent-presets/liangshen/agent.cordis.yml','utf8');const rows=r('js-yaml').load(text);"
                        +"const {Config}=await import('file:///usr/local/lib/node_modules/@deepseek-ai/dsh/node_modules/@deepseek-ai/dsh-persona/lib/index.js');"
                        +"if(Config(rows[0].config).prefix!=='Keep this persona\\n保留原始提示词')throw Error('legacy persona text lost');"
                        +"if(Config({prefix:'new',text:'old'}).prefix!=='new')throw Error('modern prefix changed');let rejected=false;try{Config({})}catch{rejected=true}if(!rejected)throw Error('missing persona accepted');"
                        +"if(fs.readFileSync('./.agent-presets/liangshen/agent.cordis.yml','utf8')!==text)throw Error('user copy changed');console.log('DSHA_ISSUE67_NODE_PASS');";
                put(fixture,"entry.mjs",script.getBytes(java.nio.charset.StandardCharsets.UTF_8));
                String command="cd "+ShellQuote.arg(guest)+"; export DSH_HOME="+ShellQuote.arg(guest+"/home")+"; export DSHA_STARTUP_PROFILE=web; node --import /root/.dsh/startup-observer.cjs ./entry.mjs";
                String output=active.proot().execAndReadWithProot(command,60000);check(output.contains("DSHA_ISSUE67_NODE_PASS"),"ISSUE67_NODE_FAILED:"+output);
                Set<String> located=new HashSet<>();for(String line:output.split("\n"))if(line.startsWith("[DSHA_STARTUP] ")){var event=BackupJson.read(line.substring(15).getBytes(java.nio.charset.StandardCharsets.UTF_8),65536);String name=String.valueOf(event.get("plugin"));
                    if(names.contains(name)){check(!event.get("type").equals("issue"),"FALSE_PLUGIN_ERROR:"+name);if(event.get("type").equals("plugin"))located.add(name);}}
                check(located.size()==17,"GLOBAL_PLUGINS_NOT_ALL_FOUND");return null;
            });passed("global_plugins_and_legacy_persona_work_under_real_proot");
            BackupTask task=BackupTask.get(context);check(!task.busy(),"TEST_REQUIRES_IDLE_TASK");var state=BackupTask.class.getDeclaredField("state");state.setAccessible(true);model=(BackupTaskState)state.get(task);original=model.snapshot();
            long id=model.start("更新运行环境");model.update(id,BackupTaskState.Status.SUCCEEDED,"dsh 与内置插件更新完成");
            Bundle extras=new Bundle();extras.putBoolean("review_only",true);extras.putLong("data_task_id",id);
            ExtractActivity page=(ExtractActivity)DeviceAuditSupport.open(this,ExtractActivity.class,extras);screen=page;
            try(var lease=EnvironmentTaskGate.tryAcquire("检查插件")){
                check(lease!=null,"TEST_LEASE_UNAVAILABLE");ui(()->{field(page,"lastRender","");render(page);check(page.findViewById(R.id.extract_bar).getVisibility()==View.GONE,"GENERIC_QUERY_LOOKS_LIKE_MAINTENANCE");});
            }passed("plugin_query_is_not_displayed_as_environment_maintenance");
            descriptor=new File(files,"linux/.runtime-descriptor.json");parked=new File(fixture,"saved-runtime-descriptor.json");File originalDescriptor=descriptor,savedDescriptor=parked;
            BackupManager.runDataTask(controller,()->{fs.move(originalDescriptor,savedDescriptor);return null;});
            check(!controller.isEnvironmentReady(),"READINESS_FIXTURE_STILL_READY");
            ui(()->{field(page,"automaticEntry",true);field(page,"lastRender","");render(page);});Thread.sleep(1200);
            check(context.getSystemService(android.os.PowerManager.class).isInteractive(),"TEST_DEVICE_ASLEEP");
            Activity foreground=DeviceAuditSupport.foreground(this);
            check(foreground==page,"SUCCESS_WITHOUT_READINESS_RESTARTED_NAVIGATION: foreground="+(foreground==null?"null":foreground.getClass().getSimpleName())+", finishing="+page.isFinishing()+", destroyed="+page.isDestroyed());check(task.snapshot().id==id&&!task.busy(),"MAINTENANCE_RESTARTED");
            ui(()->field(page,"automaticEntry",false));
            BackupManager.runDataTask(controller,()->{fs.move(savedDescriptor,originalDescriptor);return null;});parked=null;
            ui(()->{field(page,"automaticEntry",false);page.finish();});screen=null;passed("success_without_readiness_does_not_loop_or_restart_task");
            ui(()->{
                try(var lease=EnvironmentTaskGate.tryAcquire("终端验收")){check(lease!=null,"TERMINAL_LEASE");lease.run(()->{terminal[0]=PtySession.start(active.proot(),80,24,null);return null;});}
            });check(terminal[0].isRunning(),"TERMINAL_DID_NOT_START");
            Thread diagnostic=new Thread(()->{try{Thread.sleep(700);if(!terminal[0].isRunning())return;StringBuilder text=new StringBuilder();int pid=terminal[0].session().getPid();text.append("pid=").append(pid).append('\n');
                var pendingField=PtySession.class.getDeclaredField("pending");pendingField.setAccessible(true);
                for(Object item:(Set<?>)pendingField.get(null)){PtySession pending=(PtySession)item;var birthField=PtySession.class.getDeclaredField("identity");birthField.setAccessible(true);ProcessIdentity birth=(ProcessIdentity)birthField.get(pending);
                    text.append("pending pid=").append(pending.session()==null?-1:pending.session().getPid()).append(" birth=").append(birth==null?"null":birth.pid+":"+birth.started+":"+birth.session).append('\n');
                    if(birth!=null)try{text.append(Compat.readAll(new File("/proc/"+birth.pid+"/stat")));}catch(Exception error){text.append(error.getClass().getSimpleName()).append(':').append(error.getMessage()).append('\n');}}
                for(var entry:Thread.getAllStackTraces().entrySet())if(entry.getKey().getName().equals("main")||entry.getKey().getName().contains("[pid=")||entry.getKey().getName().contains("Instrumentation")){text.append('\n').append(entry.getKey().getName()).append(' ').append(entry.getKey().getState()).append('\n');for(var frame:entry.getValue())text.append(frame).append('\n');}
                terminalEvidence.set(text.toString());}catch(Exception error){terminalEvidence.set(error.toString());}},"issue67-pty-evidence");diagnostic.setDaemon(true);diagnostic.start();
            BackupManager.runDataTask(controller,()->{check(!terminal[0].isRunning(),"MAINTENANCE_DID_NOT_STOP_PTY");check(!RuntimeTasks.hasOtherTasks(),"TERMINAL_WORK_LOCK_RETAINED");return null;});
            passed("terminal_starts_after_maintenance_and_native_barrier_closes_it");
            ui(()->{terminal[0]=PtySession.start(active.proot(),80,24,null);terminal[0].write("unset HISTFILE; printf 'DSHA_67_%s\\n' READY\n");});
            String[] transcript={""};long until=android.os.SystemClock.elapsedRealtime()+15000;
            do{Thread.sleep(100);ui(()->transcript[0]=terminal[0].session().getEmulator().getScreen().getTranscriptText());}while(!transcript[0].contains("DSHA_67_READY")&&terminal[0].isRunning()&&android.os.SystemClock.elapsedRealtime()<until);
            check(transcript[0].contains("DSHA_67_READY"),"TERMINAL_COMMAND_FAILED:"+transcript[0]);
            BackupManager.runDataTask(controller,()->{check(!terminal[0].isRunning(),"LIVE_PTY_RETAINED");return null;});passed("live_terminal_command_and_maintenance_exit_are_verified");
        }catch(Throwable error){result.putString("failure",SensitiveData.redact(android.util.Log.getStackTraceString(error)));}
        finally{
            if(terminalEvidence.get()!=null)result.putString("terminalDiagnostic",SensitiveData.redact(terminalEvidence.get()));
            if(terminal[0]!=null)try{terminal[0].finishAndWait(10000);}catch(Exception error){result.putString("cleanupFailure",error.toString());}
            if(parked!=null&&controller!=null)try{File source=parked,target=descriptor;BackupManager.runDataTask(controller,()->{if(fs.stat(source).type.equals("FILE"))fs.move(source,target);return null;});}catch(Exception error){result.putString("cleanupFailure",error.toString());}
            if(model!=null&&original!=null)model.restore(original.id,original.kind,original.status,original.detail);
            if(controller!=null&&originalProroot!=null)controller.config().setProroot(originalProroot);
            if(screen!=null){Activity closed=screen;runOnMainSync(closed::finish);}
            Map<String,Object> report=Map.of("status",result.containsKey("failure")||result.containsKey("cleanupFailure")?"FAIL":"PASS","tests",tests,"nonDebuggable",true,"flavor",BuildConfig.FLAVOR);
            try{result.putString("report",new String(BackupJson.write(report,65536),java.nio.charset.StandardCharsets.UTF_8));}catch(Exception error){result.putString("failure",error.toString());}
            finish("PASS".equals(report.get("status"))?0:1,result);
        }
    }
}
