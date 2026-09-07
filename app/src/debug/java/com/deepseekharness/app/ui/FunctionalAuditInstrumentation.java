package com.deepseekharness.app.ui;

import android.app.Activity;
import android.app.Instrumentation;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.webkit.WebView;
import com.deepseekharness.app.R;
import com.deepseekharness.app.HarnessService;
import com.deepseekharness.app.core.ConfigStore;
import com.deepseekharness.app.core.HarnessController;
import com.deepseekharness.app.util.Constants;
import com.deepseekharness.app.util.SensitiveData;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.*;

/** 调试包独有的实际流程检查。凭据只从应用私有临时文件读取，结束时恢复原值。 */
public final class FunctionalAuditInstrumentation extends Instrumentation {
    private Bundle args;
    private final StringBuilder log = new StringBuilder();
    private File folder;
    @Override public void onCreate(Bundle args) { super.onCreate(args); this.args=args; start(); }
    private void require(boolean value,String why) { if(!value)throw new AssertionError(why); }
    private synchronized void note(String message) {
        log.append(SensitiveData.redact(message)).append('\n');
        try{write("run.log",log.toString());}catch(Exception ignored){}
    }
    private String js(WebView view,String script) throws Exception {
        CompletableFuture<String> answer=new CompletableFuture<>();
        runOnMainSync(()->view.evaluateJavascript(script,answer::complete));
        return answer.get(15,TimeUnit.SECONDS);
    }
    private String asyncJs(WebView view,String body) throws Exception {
        js(view,"window.__auditResult=null;(async()=>{"+body+"})().then(v=>window.__auditResult={ok:true,value:v},e=>window.__auditResult={ok:false,error:String(e)});");
        long end=System.currentTimeMillis()+30000;
        String value="null";
        while(System.currentTimeMillis()<end) {
            value=js(view,"window.__auditResult");
            if(!"null".equals(value)) return value;
            Thread.sleep(100);
        }
        throw new AssertionError("网页操作30秒内未返回");
    }
    private void shell(String command) throws Exception {
        try(android.os.ParcelFileDescriptor fd=getUiAutomation().executeShellCommand(command);
            InputStream in=new android.os.ParcelFileDescriptor.AutoCloseInputStream(fd)) { while(in.read()!=-1){} }
    }
    private void write(String name,String text) throws Exception {
        try(FileOutputStream out=new FileOutputStream(new File(folder,name))) { out.write(SensitiveData.redact(text).getBytes(StandardCharsets.UTF_8)); }
    }
    private boolean visibleLabel(android.view.accessibility.AccessibilityNodeInfo node,String label) {
        if(node==null)return false;
        if(node.isVisibleToUser()&&(label.contentEquals(String.valueOf(node.getText()))
                ||label.contentEquals(String.valueOf(node.getContentDescription()))))return true;
        for(int i=0;i<node.getChildCount();i++)if(visibleLabel(node.getChild(i),label))return true;
        return false;
    }
    private boolean clickVisibleLabel(android.view.accessibility.AccessibilityNodeInfo node,String label) {
        if(node==null)return false;
        if(node.isVisibleToUser()&&(label.contentEquals(String.valueOf(node.getText()))
                ||label.contentEquals(String.valueOf(node.getContentDescription())))) {
            for(int level=0;node!=null&&level<3;level++,node=node.getParent())
                if(node.isClickable())return node.performAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_CLICK);
            return false;
        }
        for(int i=0;i<node.getChildCount();i++)if(clickVisibleLabel(node.getChild(i),label))return true;
        return false;
    }
    private boolean visibleEditor(android.view.accessibility.AccessibilityNodeInfo node) {
        if(node==null)return false;
        if(node.isVisibleToUser()&&"android.widget.EditText".contentEquals(node.getClassName()))return true;
        for(int i=0;i<node.getChildCount();i++)if(visibleEditor(node.getChild(i)))return true;
        return false;
    }
    @Override public void onStart() {
        Bundle result=new Bundle(); Context app=getTargetContext().getApplicationContext();
        folder=new File(app.getCacheDir(),"functional-audit");folder.mkdirs();
        HarnessController controller=HarnessController.get(app); ConfigStore config=controller.config();
        SharedPreferences prefs=app.getSharedPreferences(Constants.PREFS,0);
        File checkpoint=new File(folder,"restore-state.json");
        if(checkpoint.isFile()) try(FileInputStream in=new FileInputStream(checkpoint)) {
            byte[] raw=new byte[(int)checkpoint.length()];require(in.read(raw)==raw.length,"测试状态恢复文件损坏");
            org.json.JSONObject previous=new org.json.JSONObject(new String(raw,StandardCharsets.UTF_8));
            prefs.edit().putString(Constants.KEY_API_KEY,previous.getString("encryptedKey"))
                    .putString(Constants.KEY_AUTO_BACKUP,previous.getString("interval")).commit();
            SharedPreferences.Editor edit=prefs.edit();if(previous.has("count"))edit.putInt("backup_launch_count",previous.getInt("count"));else edit.remove("backup_launch_count");edit.commit();
            require(checkpoint.delete(),"测试状态恢复文件无法清理");
        } catch(Exception e) { result.putString("failure","无法恢复上次测试配置："+e.getClass().getSimpleName());finish(1,result);return; }
        String oldKey=prefs.getString(Constants.KEY_API_KEY,"");
        int oldInterval=config.getAutoBackupLaunches();
        long beforeBackup=config.getLastBackupSuccess();
        Object oldCount=prefs.getAll().get("backup_launch_count");
        Activity webPage=null,main=null; boolean started=false,changedKey=false;
        File keyFile=new File(folder,"test-key");
        try {
            require(!controller.isWebRunning()&&!controller.isStarting(),"Web 已在运行，保留现场，稍后再测");
            org.json.JSONObject checkpointState=new org.json.JSONObject().put("encryptedKey",oldKey).put("interval",String.valueOf(oldInterval));
            if(oldCount!=null)checkpointState.put("count",oldCount);
            try(FileOutputStream out=new FileOutputStream(checkpoint)){out.write(checkpointState.toString().getBytes(StandardCharsets.UTF_8));out.getFD().sync();}
            if("runtime".equals(args.getString("mode"))) {
                require(!keyFile.exists(),"带临时测试凭据时禁止创建用户备份");
                require(oldInterval>0,"自动备份已关闭，请使用显式隔离夹具验证");
                prefs.edit().putInt("backup_launch_count",oldInterval-1).commit();
            } else config.setAutoBackupLaunches(0);
            if(keyFile.isFile()) {
                require(keyFile.length()>10&&keyFile.length()<256,"私有测试凭据未完整写入");
                byte[] keyBytes;try(FileInputStream in=new FileInputStream(keyFile)) { keyBytes=new byte[(int)keyFile.length()];require(in.read(keyBytes)==keyBytes.length,"测试凭据读取失败"); }
                require(config.saveApiKey(new String(keyBytes,StandardCharsets.UTF_8).trim()),"测试凭据加密失败");
                require(config.getApiKey().equals(new String(keyBytes,StandardCharsets.UTF_8).trim()),"测试凭据加密读回失败");
                java.util.Arrays.fill(keyBytes,(byte)0);changedKey=true;require(keyFile.delete(),"测试临时文件未清理");
            }
            shell("am start -W -n com.dsh.client/com.deepseekharness.app.ui.MainActivity");
            note("主页面已请求打开");
            long mainDeadline=System.currentTimeMillis()+15000;
            while(MainActivity.current==null&&System.currentTimeMillis()<mainDeadline)Thread.sleep(100);
            main=MainActivity.current;require(main!=null,"主页面未就绪");
            long start=System.currentTimeMillis();
            require(controller.startWeb(this::note),"启动请求未接受");started=true;
            runOnMainSync(()->androidx.core.content.ContextCompat.startForegroundService(app,new Intent(app,HarnessService.class)));
            long deadline=start+100000;
            while(controller.getWebAuthUrl().isEmpty()&&System.currentTimeMillis()<deadline)Thread.sleep(200);
            require(!controller.getWebAuthUrl().isEmpty(),"Web 启动失败："+config.getWebFailureReason());
            note("Web 启动耗时ms="+(System.currentTimeMillis()-start));
            if("gecko".equals(args.getString("mode"))) {
                String gecko="com.deepseekharness.app.ui.GeckoPreviewActivity";
                ActivityMonitor monitor=addMonitor(gecko,null,false);
                try {
                    Intent intent=WebPreviewActivity.intent(app,controller.getWebAuthUrl(),controller.exchangeDshAuthCookie()).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                    runOnMainSync(()->app.startActivity(intent));
                    webPage=waitForMonitorWithTimeout(monitor,15000);
                    require(webPage!=null,"兼容版未从实际对话入口进入 Gecko");
                } finally {removeMonitor(monitor);}
                Activity geckoPage=webPage;boolean mounted=false;
                deadline=System.currentTimeMillis()+90000;
                while(System.currentTimeMillis()<deadline) {
                    boolean[] loaded={false};runOnMainSync(()->loaded[0]=geckoPage.findViewById(R.id.web_progress).getVisibility()!=android.view.View.VISIBLE
                            &&geckoPage.findViewById(R.id.web_error_panel).getVisibility()!=android.view.View.VISIBLE);
                    android.view.accessibility.AccessibilityNodeInfo root=getUiAutomation().getRootInActiveWindow();
                    // 没有原生凭据的新浏览器会提示配置；实际点击稍后配置，不写入测试 Key。
                    if(clickVisibleLabel(root,"稍后配置")){note("首次凭据提示已通过实际稍后配置按钮关闭");Thread.sleep(300);continue;}
                    // 空工作区的发送按钮未启用，Gecko 也将折叠侧栏标为不可见；核验实际可见的选择入口和编辑区域。
                    if(loaded[0]&&visibleLabel(root,"选择工作区")&&visibleLabel(root,"标准模式")&&visibleEditor(root)){mounted=true;break;}
                    Thread.sleep(250);
                }
                android.graphics.Bitmap screenshot=getUiAutomation().takeScreenshot();
                if(screenshot!=null)try(FileOutputStream out=new FileOutputStream(new File(folder,"gecko-live.png"))){screenshot.compress(android.graphics.Bitmap.CompressFormat.PNG,100,out);}
                require(mounted,"Gecko 未完成真实 dsh 鉴权、页面渲染与交互控件挂载");
                note("Gecko 真实入口、鉴权和页面交互控件通过；未发送模型请求");
                result.putString("result","PASS：真实 Gecko 进入、鉴权与页面渲染");return;
            }
            webPage=startActivitySync(WebPreviewActivity.intent(app,controller.getWebAuthUrl(),controller.exchangeDshAuthCookie()).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            java.lang.reflect.Field field=WebPreviewActivity.class.getDeclaredField("webView");field.setAccessible(true);
            WebView view=(WebView)field.get(webPage);require(view!=null,"系统 WebView 未创建");
            deadline=System.currentTimeMillis()+90000;boolean ready=false;
            while(System.currentTimeMillis()<deadline) {
                String state=js(view,"(function(){if(window.__DSH_BOOT_READY__)window.__DSH_BOOT_READY__.promise.then(()=>window.__auditBoot=true);return !!window.__auditBoot&&document.documentElement.getAttribute('data-dsha-integration')==='ready';})()");
                if("true".equals(state)){ready=true;break;}Thread.sleep(250);
            }
            require(ready,"页面未完成启动与原生适配");note("Web UI 启动与鉴权通过");
            write("controls.json",js(view,"Array.from(document.querySelectorAll('button,textarea,input,[contenteditable]')).filter(e=>e.getBoundingClientRect().width>0).map(e=>({tag:e.tagName,role:e.getAttribute('role'),label:e.getAttribute('aria-label'),placeholder:e.getAttribute('placeholder'),type:e.type,text:e.tagName==='BUTTON'?e.innerText.slice(0,70):'',cls:typeof e.className==='string'?e.className:''})).slice(0,65)"));
            js(view,"window.__auditRpc=async function(method,args){const rpcId=crypto.randomUUID();const r=await fetch('/api/'+method,{method:'POST',headers:{'content-type':'application/json'},body:JSON.stringify({type:'client-request',rpcId,method,payload:{args}})});const data=await r.json();if(!r.ok||!data.result?.ok)throw Error(JSON.stringify(data));return data.result.value;};");
            String catalog=asyncJs(view,"return await window.__auditRpc('session/modelCatalog',{});");
            write("catalog.json",catalog);require(new org.json.JSONObject(catalog).optBoolean("ok"),"模型目录接口未成功");
            note("页面可交互控件与模型目录已记录");
            if("interactive".equals(args.getString("mode"))) {
                note("测试命令接收已就绪");
                long controlDeadline=System.currentTimeMillis()+900000;
                File command=new File(folder,"command.json");
                while(System.currentTimeMillis()<controlDeadline) {
                    if(command.isFile()) {
                        org.json.JSONObject job;
                        try(FileInputStream input=new FileInputStream(command)) {
                            byte[] bytes=new byte[(int)command.length()];require(input.read(bytes)==bytes.length,"测试命令写入不完整");
                            job=new org.json.JSONObject(new String(bytes,StandardCharsets.UTF_8));
                        }
                        require(command.delete(),"测试命令无法消费");
                        String name=job.getString("id");require(name.matches("[a-zA-Z0-9_-]{1,40}"),"测试命令ID不合法");
                        if(job.optBoolean("finish")){write(name+".json","{\"finished\":true}");break;}
                        try{write(name+".json",asyncJs(view,job.getString("script")));}
                        catch(Exception e){write(name+".json",new org.json.JSONObject().put("failure",e.toString()).toString());}
                    }
                    Thread.sleep(200);
                }
            }
            if ("runtime".equals(args.getString("mode"))) {
                require(config.getLastBackupSuccess() > beforeBackup, "启动前自动备份未完成");
                note("自动备份已校验：" + config.getLastBackupName());
                result.putString("automatic_backup", config.getLastBackupName());
                long generation=controller.getWebGeneration();
                try(com.deepseekharness.app.util.EnvironmentTaskGate.Lease guard=
                        com.deepseekharness.app.util.EnvironmentTaskGate.tryAcquire("测试互斥")) {
                    require(guard!=null,"启动任务未释放环境锁");
                    require(!controller.startWeb(this::note),"已有环境任务时仍允许启动 Web");
                    require(controller.getWebGeneration()==generation,"拒绝的启动修改了正在运行的代次");
                }
                com.deepseekharness.app.core.BackupTask task=com.deepseekharness.app.core.BackupTask.get(app);
                require(task.backup(com.deepseekharness.app.util.BackupScope.FULL),"备份页面任务未接受");
                deadline=System.currentTimeMillis()+120000;
                while(task.busy()&&System.currentTimeMillis()<deadline)Thread.sleep(100);
                require(!task.busy(),"真实备份任务未完成");
                require(task.snapshot().status==com.deepseekharness.app.util.BackupTaskState.Status.SUCCEEDED,
                        "真实备份失败："+task.snapshot().detail);
                require(controller.isWebRunning()&&controller.getWebGeneration()==generation,"备份中断了 Web");
                note("Web 运行中的真实备份通过："+config.getLastBackupName());
                result.putString("live_backup",config.getLastBackupName());
                final boolean[] stopped={false};
                com.deepseekharness.app.BackupManager.runDataTask(controller,()->{
                    require(!controller.isStarting()&&!controller.isStopping()&&!controller.isWebRunning(),
                            "维护回调进入时 Web 仍在运行");
                    require(com.deepseekharness.app.BackupManager.isDataTaskOwner(),"停止屏障未交付任务所有权");
                    stopped[0]=true;return null;
                });
                require(stopped[0],"真实停止屏障未返回");
                require(!com.deepseekharness.app.BackupManager.isEnvironmentTaskBusy(),"维护完成未释放环境锁");
                note("真实 Web PID/停止队列屏障通过；未覆盖用户数据");
            }
            result.putString("result","PASS：真实 Web 启动、鉴权、页面就绪；详见私有测试输出");
        } catch(Throwable error) { result.putString("failure",SensitiveData.redact(android.util.Log.getStackTraceString(error))); }
        finally {
            if(webPage!=null){Activity page=webPage;runOnMainSync(page::finish);}
            if(started){controller.stopWeb();app.stopService(new Intent(app,HarnessService.class));}
            long stopDeadline=System.currentTimeMillis()+15000;
            while(controller.isStopping()&&System.currentTimeMillis()<stopDeadline)try{Thread.sleep(100);}catch(InterruptedException ignored){}
            if(changedKey)prefs.edit().putString(Constants.KEY_API_KEY,oldKey).commit();
            config.setAutoBackupLaunches(oldInterval);
            SharedPreferences.Editor edit=prefs.edit();if(oldCount==null)edit.remove("backup_launch_count");else edit.putInt("backup_launch_count",(Integer)oldCount);edit.commit();
            keyFile.delete();
            checkpoint.delete();
            try{write("run.log",log.toString());}catch(Exception ignored){}
            finish(result.containsKey("failure")?1:0,result);
        }
    }
}
