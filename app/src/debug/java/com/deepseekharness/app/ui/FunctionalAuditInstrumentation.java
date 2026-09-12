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
    @Override public void callActivityOnCreate(Activity activity, Bundle state) {
        super.callActivityOnCreate(activity, state);
        if (activity instanceof WebPreviewActivity || activity.getClass().getSimpleName().equals("GeckoPreviewActivity")) {
            activity.getWindow().addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
                    | android.view.WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON);
        }
    }
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
    private boolean matchesLabel(String wanted,CharSequence value) {
        String actual=String.valueOf(value);if(wanted.equals(actual))return true;
        switch(wanted){
            case "继续":return "Continue".equals(actual);
            case "稍后配置":return java.util.Arrays.asList("Later","Not now","Set up later","Skip for now","Configure later").contains(actual);
            case "发送消息":return "Send message".equals(actual);
            case "选择工作区":return "Select workspace".equals(actual)||"Choose workspace".equals(actual);
            default:return false;
        }
    }
    private boolean visibleLabel(android.view.accessibility.AccessibilityNodeInfo node,String label) {
        if(node==null)return false;
        if(node.isVisibleToUser()&&(matchesLabel(label,node.getText())
                ||matchesLabel(label,node.getContentDescription())))return true;
        for(int i=0;i<node.getChildCount();i++)if(visibleLabel(node.getChild(i),label))return true;
        return false;
    }
    private boolean clickVisibleLabel(android.view.accessibility.AccessibilityNodeInfo node,String label) {
        if(node==null)return false;
        if (!getTargetContext().getPackageName().contentEquals(String.valueOf(node.getPackageName()))) return false;
        if(node.isVisibleToUser()&&(matchesLabel(label,node.getText())
                ||matchesLabel(label,node.getContentDescription()))) {
            android.graphics.Rect bounds = new android.graphics.Rect(); node.getBoundsInScreen(bounds);
            // Gecko 部分节点的 AX 点击会返回成功却不触发网页处理器，直接模拟可见位置的触摸。
            if(bounds.isEmpty())return false;
            long at=android.os.SystemClock.uptimeMillis();
            android.view.MotionEvent down=android.view.MotionEvent.obtain(at,at,android.view.MotionEvent.ACTION_DOWN,bounds.exactCenterX(),bounds.exactCenterY(),0);
            android.view.MotionEvent up=android.view.MotionEvent.obtain(at,at+80,android.view.MotionEvent.ACTION_UP,bounds.exactCenterX(),bounds.exactCenterY(),0);
            down.setSource(android.view.InputDevice.SOURCE_TOUCHSCREEN);up.setSource(android.view.InputDevice.SOURCE_TOUCHSCREEN);
            try{return getUiAutomation().injectInputEvent(down,true)&&getUiAutomation().injectInputEvent(up,true);}
            finally{down.recycle();up.recycle();}
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
            prefs.edit().putString(Constants.KEY_API_KEY,previous.getString("encryptedKey")).commit();
            if(previous.has("port"))config.setPort(previous.getString("port"));
            if(previous.has("geckoPresent")) {
                SharedPreferences.Editor engine=prefs.edit();
                if(previous.getBoolean("geckoPresent"))engine.putBoolean(Constants.KEY_GECKO_CORE,previous.getBoolean("geckoCore"));
                else engine.remove(Constants.KEY_GECKO_CORE);
                engine.commit();
            }
            if(previous.has("languagePresent")){SharedPreferences.Editor language=prefs.edit();if(previous.getBoolean("languagePresent"))language.putString("ui_language",previous.getString("language"));else language.remove("ui_language");language.commit();}
            SharedPreferences.Editor edit=prefs.edit();if(previous.has("count"))edit.putInt("backup_launch_count",previous.getInt("count"));else edit.remove("backup_launch_count");edit.commit();
            require(checkpoint.delete(),"测试状态恢复文件无法清理");
        } catch(Exception e) { result.putString("failure","无法恢复上次测试配置："+e.getClass().getSimpleName());finish(1,result);return; }
        String oldKey=prefs.getString(Constants.KEY_API_KEY,"");
        long beforeBackup=config.getLastBackupSuccess();
        Object oldCount=prefs.getAll().get("backup_launch_count");
        String oldPort=config.getPort();
        boolean hadGecko=prefs.contains(Constants.KEY_GECKO_CORE),oldGecko=prefs.getBoolean(Constants.KEY_GECKO_CORE,false);
        boolean hadLanguage=prefs.contains("ui_language");String oldLanguage=config.getUiLanguage();
        Activity webPage=null,main=null; boolean started=false,changedKey=false;
        File keyFile=new File(folder,"test-key");
        try {
            require(!controller.isWebRunning()&&!controller.isStarting(),"Web 已在运行，保留现场，稍后再测");
            org.json.JSONObject checkpointState=new org.json.JSONObject().put("encryptedKey",oldKey);
            checkpointState.put("port",oldPort).put("geckoPresent",hadGecko).put("geckoCore",oldGecko);
            checkpointState.put("languagePresent",hadLanguage).put("language",oldLanguage);
            if(oldCount!=null)checkpointState.put("count",oldCount);
            try(FileOutputStream out=new FileOutputStream(checkpoint)){out.write(checkpointState.toString().getBytes(StandardCharsets.UTF_8));out.getFD().sync();}
            boolean geckoMode="gecko".equals(args.getString("mode"))||"gecko-input".equals(args.getString("mode"))||"gecko-preview".equals(args.getString("mode"))||"gecko-manual".equals(args.getString("mode"));
            if(geckoMode)prefs.edit().putBoolean(Constants.KEY_GECKO_CORE,true).commit();
            if(args.containsKey("language"))runOnMainSync(()->LanguageController.select(app,args.getString("language")));
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
            if(geckoMode) {
                String gecko="com.deepseekharness.app.ui.GeckoPreviewActivity";
                ActivityMonitor monitor=addMonitor(gecko,null,false);
                try {
                    Intent intent=WebPreviewActivity.intent(app,controller.getWebAuthUrl(),controller.exchangeDshAuthCookie()).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                    runOnMainSync(()->app.startActivity(intent));
                    webPage=waitForMonitorWithTimeout(monitor,15000);
                    require(webPage!=null,"兼容版未从实际对话入口进入 Gecko");
                } finally {removeMonitor(monitor);}
                Activity geckoPage=webPage;boolean mounted=false;long stableAt=0;
                deadline=System.currentTimeMillis()+90000;
                while(System.currentTimeMillis()<deadline) {
                    boolean[] loaded={false};runOnMainSync(()->loaded[0]=geckoPage.findViewById(R.id.web_progress).getVisibility()!=android.view.View.VISIBLE
                            &&geckoPage.findViewById(R.id.web_error_panel).getVisibility()!=android.view.View.VISIBLE);
                    android.view.accessibility.AccessibilityNodeInfo root=getUiAutomation().getRootInActiveWindow();
                    if(clickVisibleLabel(root,"继续")){stableAt=0;note("已通过预览版提示");Thread.sleep(300);continue;}
                    // 没有原生凭据的新浏览器会提示配置；实际点击稍后配置，不写入测试 Key。
                    if(clickVisibleLabel(root,"稍后配置")){stableAt=0;note("首次凭据提示已通过实际稍后配置按钮关闭");Thread.sleep(300);continue;}
                    // 空工作区的发送按钮未启用，Gecko 也将折叠侧栏标为不可见；核验实际可见的选择入口和编辑区域。
                    boolean controls=loaded[0]&&!visibleLabel(root,"Loading plugins...")
                            &&(visibleLabel(root,"选择工作区")||visibleLabel(root,"发送消息"))&&visibleEditor(root);
                    // AX 控件可早于启动遮罩的最后一帧出现；等提示与绘制稳定，再截图确认实际画面。
                    if(controls) {
                        if(stableAt==0)stableAt=System.currentTimeMillis();
                        if(System.currentTimeMillis()-stableAt>=3000){mounted=true;break;}
                    } else stableAt=0;
                    Thread.sleep(250);
                }
                android.graphics.Bitmap screenshot=getUiAutomation().takeScreenshot();
                if(screenshot!=null)try(FileOutputStream out=new FileOutputStream(new File(folder,"gecko-live.png"))){screenshot.compress(android.graphics.Bitmap.CompressFormat.PNG,100,out);}
                require(mounted,"Gecko 未完成真实 dsh 鉴权、页面渲染与交互控件挂载");
                if ("gecko-manual".equals(args.getString("mode"))) {
                    note("Gecko 触摸验收页面已就绪");
                    File finish = new File(folder, "gecko-finish");
                    long until = System.currentTimeMillis() + 300000;
                    while (!finish.exists() && System.currentTimeMillis() < until) Thread.sleep(200);
                    require(finish.exists(), "Gecko 触摸验收未在限时内结束"); finish.delete();
                }
                if ("gecko-preview".equals(args.getString("mode"))) {
                    require(clickVisibleLabel(getUiAutomation().getRootInActiveWindow(), "打开侧边栏")
                            || clickVisibleLabel(getUiAutomation().getRootInActiveWindow(), "打开目录"), "Gecko 侧边栏入口不可见");
                    Thread.sleep(700);
                    String previewFolder = args.getString("preview_folder", "DSHA-alpha2-preview-check");
                    String previewFile = args.getString("preview_file", "alpha2-preview.pdf");
                    String sessionLabel = args.getString("session_label", "你好");
                    boolean openedUngrouped = false;
                    for (String label : new String[]{sessionLabel, "文件浏览", previewFolder, previewFile}) {
                        if (label.equals(previewFolder) && visibleLabel(getUiAutomation().getRootInActiveWindow(), previewFile)) continue;
                        long until = System.currentTimeMillis() + 10000; boolean clicked = false;
                        while (System.currentTimeMillis() < until) {
                            if (clickVisibleLabel(getUiAutomation().getRootInActiveWindow(), "稍后配置")) { Thread.sleep(300); continue; }
                            if (clickVisibleLabel(getUiAutomation().getRootInActiveWindow(), label)) { clicked = true; break; }
                            if (label.equals(sessionLabel) && !openedUngrouped)
                                openedUngrouped = clickVisibleLabel(getUiAutomation().getRootInActiveWindow(), "未分组");
                            Thread.sleep(200);
                        }
                        require(clicked, "Gecko 未找到预览入口：" + label);
                        if (label.equals(sessionLabel)) {
                            Thread.sleep(80);clickVisibleLabel(getUiAutomation().getRootInActiveWindow(), label);
                        }
                        Thread.sleep(700);
                    }
                    Thread.sleep(3000);
                    android.graphics.Bitmap preview = getUiAutomation().takeScreenshot();
                    require(preview != null, "Gecko PDF 截图失败");
                    try (FileOutputStream out = new FileOutputStream(new File(folder, "gecko-pdf.png"))) { preview.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, out); }
                    note("Gecko 文件面板及 PDF 预览截图已保存");
                    for (int i = 0; i < 2; i++) { clickVisibleLabel(getUiAutomation().getRootInActiveWindow(), "关闭"); Thread.sleep(150); }
                    clickVisibleLabel(getUiAutomation().getRootInActiveWindow(), "收起右侧边栏");
                }
                if("gecko-input".equals(args.getString("mode"))) InputInsetsAudit.run(this,webPage,folder,"gecko",null);
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
            if("input".equals(args.getString("mode"))) {
                int stable=0;
                for(int attempts=0;attempts<40;attempts++) {
                    String prepared=js(view,"(()=>{const shown=e=>e.getBoundingClientRect().width>0;for(const b of document.querySelectorAll('button')){if(shown(b)&&['继续','稍后配置'].includes(b.innerText.trim())){b.click();return false;}}return !!document.querySelector('[data-composer-input]')&&!Array.from(document.querySelectorAll('input[type=password]')).some(shown);})()");
                    stable="true".equals(prepared)?stable+1:0;
                    if(stable>=6)break;
                    Thread.sleep(500);
                }
                require(stable>=6,"首次提示未关闭，不能进行输入测试");
                InputInsetsAudit.run(this,webPage,folder,"webview",view);
                result.putString("result","PASS：系统 WebView 真机回车换行与系统栏/输入法安全区");return;
            }
            write("controls.json",js(view,"Array.from(document.querySelectorAll('button,textarea,input,[contenteditable]')).filter(e=>e.getBoundingClientRect().width>0).map(e=>({tag:e.tagName,role:e.getAttribute('role'),label:e.getAttribute('aria-label'),placeholder:e.getAttribute('placeholder'),type:e.type,text:e.tagName==='BUTTON'?e.innerText.slice(0,70):'',cls:typeof e.className==='string'?e.className:''})).slice(0,65)"));
            js(view,"window.__auditRpc=async function(method,args){const rpcId=crypto.randomUUID();const r=await fetch('/api/'+method,{method:'POST',headers:{'content-type':'application/json'},body:JSON.stringify({type:'client-request',rpcId,method,payload:{args}})});const data=await r.json();if(!r.ok||!data.result?.ok)throw Error(JSON.stringify(data));return data.result.value;};");
            String catalog=asyncJs(view,"return await window.__auditRpc('session/modelCatalog',{});");
            write("catalog.json",catalog);require(new org.json.JSONObject(catalog).optBoolean("ok"),"模型目录接口未成功");
            note("页面可交互控件与模型目录已记录");
            if("auth".equals(args.getString("mode"))) {
                int alternate;
                try(java.net.ServerSocket probe=new java.net.ServerSocket(0,1,java.net.InetAddress.getLoopbackAddress())){alternate=probe.getLocalPort();}
                for(String port:new String[]{String.valueOf(alternate),oldPort}) {
                    runOnMainSync(()->app.startActivity(new Intent(app,MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK|Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)));
                    controller.stopWeb();config.setPort(port);
                    require(controller.startWeb(this::note),"更换端口后未接受启动");
                    long reconnect=System.currentTimeMillis()+90000;
                    while(controller.getWebAuthUrl().isEmpty()&&System.currentTimeMillis()<reconnect)Thread.sleep(200);
                    require(!controller.getWebAuthUrl().isEmpty(),"新代服务未就绪");
                    runOnMainSync(()->app.startActivity(new Intent(app,WebPreviewActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK|Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)));
                    boolean recovered=false;
                    while(System.currentTimeMillis()<reconnect) {
                        // 切换端口时生产页会重建 WebView；读取当前视图，不能向已销毁的旧实例发脚本。
                        WebView active=(WebView)field.get(webPage);
                        if(active==null){Thread.sleep(200);continue;}
                        String status;
                        try {status=js(active,"location.port==='"+port+"'&&document.documentElement.getAttribute('data-dsha-integration')==='ready'");}
                        catch(TimeoutException rebuilding){Thread.sleep(200);continue;}
                        if("true".equals(status)){recovered=true;break;}Thread.sleep(200);
                    }
                    require(recovered,"保留的网页未更新到新端口并重新鉴权");
                    note("后台返回预览页，端口 "+port+" 的新代鉴权和页面就绪通过");
                }
            }
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
                        try{
                            if(job.has("tap")) {
                                org.json.JSONObject point=job.getJSONObject("tap");
                                double cssWidth=point.getDouble("width"),cssX=point.getDouble("x"),cssY=point.getDouble("y");
                                int[] frame=new int[4];
                                runOnMainSync(()->{int[] p=new int[2];view.getLocationOnScreen(p);frame[0]=p[0];frame[1]=p[1];frame[2]=view.getWidth();frame[3]=view.getHeight();});
                                double scale=frame[2]/cssWidth;
                                require(cssWidth>0&&Double.isFinite(scale)&&cssX>=0&&cssX<cssWidth&&cssY>=0&&cssY*scale<frame[3],"触摸点必须位于本次网页内");
                                float x=(float)(frame[0]+cssX*scale),y=(float)(frame[1]+cssY*scale);
                                long at=android.os.SystemClock.uptimeMillis();
                                sendPointerSync(android.view.MotionEvent.obtain(at,at,android.view.MotionEvent.ACTION_DOWN,x,y,0));
                                sendPointerSync(android.view.MotionEvent.obtain(at,at+80,android.view.MotionEvent.ACTION_UP,x,y,0));
                                write(name+".json","{\"ok\":true,\"value\":true}");
                            } else write(name+".json",asyncJs(view,job.getString("script")));
                        }
                        catch(Exception e){write(name+".json",new org.json.JSONObject().put("failure",e.toString()).toString());}
                    }
                    Thread.sleep(200);
                }
            }
            if ("runtime".equals(args.getString("mode"))) {
                require(config.getLastBackupSuccess() == beforeBackup, "启动不应自动创建备份");
                note("启动未触发自动备份");
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
        } catch(Throwable error) {
            result.putString("failure",SensitiveData.redact(android.util.Log.getStackTraceString(error)));
            android.graphics.Bitmap failed = getUiAutomation().takeScreenshot();
            if (failed != null) try(FileOutputStream out = new FileOutputStream(new File(folder,"failed-web.png"))) { failed.compress(android.graphics.Bitmap.CompressFormat.PNG,100,out); } catch(Exception ignored) { }
        }
        finally {
            if(webPage!=null){Activity page=webPage;runOnMainSync(page::finish);}
            if(started){controller.stopWeb();app.stopService(new Intent(app,HarnessService.class));}
            long stopDeadline=System.currentTimeMillis()+15000;
            while(controller.isStopping()&&System.currentTimeMillis()<stopDeadline)try{Thread.sleep(100);}catch(InterruptedException ignored){}
            if(changedKey)prefs.edit().putString(Constants.KEY_API_KEY,oldKey).commit();
            config.setPort(oldPort);
            SharedPreferences.Editor engine=prefs.edit();
            if(hadGecko)engine.putBoolean(Constants.KEY_GECKO_CORE,oldGecko);else engine.remove(Constants.KEY_GECKO_CORE);
            engine.commit();
            SharedPreferences.Editor language=prefs.edit();if(hadLanguage)language.putString("ui_language",oldLanguage);else language.remove("ui_language");language.commit();runOnMainSync(()->LanguageController.apply(app));
            SharedPreferences.Editor edit=prefs.edit();if(oldCount==null)edit.remove("backup_launch_count");else edit.putInt("backup_launch_count",(Integer)oldCount);edit.commit();
            keyFile.delete();
            checkpoint.delete();
            try{write("run.log",log.toString());}catch(Exception ignored){}
            finish(result.containsKey("failure")?1:0,result);
        }
    }
}
