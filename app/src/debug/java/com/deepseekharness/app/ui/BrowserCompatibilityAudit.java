package com.deepseekharness.app.ui;

import android.app.*;
import android.os.*;
import android.content.*;
import android.webkit.*;
import com.deepseekharness.app.core.*;
import com.deepseekharness.app.BackupManager;
import com.deepseekharness.app.util.*;
import org.json.*;
import java.io.*;
import java.util.*;
import java.util.concurrent.*;

/** 真实 WebView 删除缺失 API 后仅依靠服务端 HTML 前导补齐；不使用原生兼容注入。 */
public final class BrowserCompatibilityAudit extends Instrumentation {
    private int checks;
    private boolean headerAudit;
    private void check(boolean value,String message){checks++;if(!value)throw new AssertionError(message);}
    private void shell(String command)throws Exception{try(var fd=getUiAutomation().executeShellCommand(command);var in=new ParcelFileDescriptor.AutoCloseInputStream(fd)){while(in.read()!=-1){}}}
    @Override public void onCreate(Bundle args){super.onCreate(args);headerAudit=args!=null&&"header".equals(args.getString("mode"));start();}
    private void header(WebView view,Bundle result)throws Exception{
        String preset="document.querySelector('[data-dsha-agent-preset=header]')";
        long end=SystemClock.elapsedRealtime()+30000;
        while(!"true".equals(evaluate(view,"Boolean("+preset+")"))&&SystemClock.elapsedRealtime()<end)Thread.sleep(150);
        check("true".equals(evaluate(view,"Boolean("+preset+")")),"平板会话预设没有出现");
        for(int width:new int[]{1260,820,390,1260}) {
            runOnMainSync(()->view.setLayoutParams(new android.widget.FrameLayout.LayoutParams(Math.round(width*view.getResources().getDisplayMetrics().density),-1)));
            Thread.sleep(450);waitForIdleSync();
            String geometry=evaluate(view,"(()=>{const p="+preset+",f=document.querySelector('header [data-mobile-nav=files]');if(!p||!f)return false;const a=p.getBoundingClientRect(),b=f.getBoundingClientRect(),h=p.closest('header').getBoundingClientRect();return getComputedStyle(p).borderTopWidth==='0px'&&getComputedStyle(f).display!=='none'&&b.width>=28&&a.right<=b.left+1&&b.right<=innerWidth+1&&(innerWidth<1024||a.left>h.left+h.width/2);})()");
            check("true".equals(geometry),"预设/文件布局不符，宽度="+width+" "+evaluate(view,"(()=>{let p="+preset+",f=document.querySelector('header [data-mobile-nav=files]');return JSON.stringify({width:innerWidth,coarse:matchMedia('(pointer: coarse)').matches,border:p&&getComputedStyle(p).borderTopWidth,preset:p&&p.getBoundingClientRect().toJSON(),files:f&&f.getBoundingClientRect().toJSON(),display:f&&getComputedStyle(f).display})})()"));
        }
        evaluate(view,preset+".click()");Thread.sleep(300);
        check("true".equals(evaluate(view,"Boolean(document.querySelector('[role=menu]'))")),"预设菜单未打开");
        check("true".equals(evaluate(view,"document.querySelectorAll('[role^=menuitem], [role=option]').length>=4")),"四项预设不可用");
        evaluate(view,"document.activeElement.dispatchEvent(new KeyboardEvent('keydown',{key:'Escape',bubbles:true}))");Thread.sleep(200);
        evaluate(view,"document.querySelector('header [data-mobile-nav=files]').click()");
        end=SystemClock.elapsedRealtime()+10000;String visible="(()=>{let p=document.querySelector('[data-sidebar-right-panel][data-sidebar-right-open]');return Boolean(p&&p.getBoundingClientRect().width>100)})()";
        while(!"true".equals(evaluate(view,visible))&&SystemClock.elapsedRealtime()<end)Thread.sleep(150);
        check("true".equals(evaluate(view,visible)),"文件按钮未打开实际文件面板");
        result.putString("header","PASS: 1260/820/390 widths; no default border; right alignment; menu and files panel work");
    }
    private String evaluate(WebView view,String script)throws Exception{
        CountDownLatch latch=new CountDownLatch(1);String[] value={""};runOnMainSync(()->view.evaluateJavascript(script,result->{value[0]=result;latch.countDown();}));
        check(latch.await(10,TimeUnit.SECONDS),"JS 检查未返回");return value[0];
    }
    private void pdfWorker(WebView view, HarnessController controller) throws Exception {
        File module = new File(controller.proot().getRootfsDir(), "usr/local/lib/node_modules/@deepseek-ai/dsh/node_modules/@deepseek-ai/dsh-client-ui-sidebar-documentpreview/lib/client.js");
        String source = Compat.readAll(module);
        String marker = "var _dsh_pdf_worker_default = ";
        int start = source.indexOf(marker), end = source.indexOf(";\n", start);
        check(start >= 0 && end > start, "锁定的 PDF Worker 未找到");
        // 这是 JavaScript 字面量，交由 JS 引擎解码；Java 正则把其中的 NEL 当成断行。
        String workerLiteral = source.substring(start + marker.length(), end);
        String compat;
        try (var in = getTargetContext().getAssets().open("web-integration/es-compat.js")) {
            var out = new ByteArrayOutputStream(); byte[] buffer = new byte[8192]; int count;
            while ((count = in.read(buffer)) != -1) out.write(buffer, 0, count);
            compat = out.toString("UTF-8").trim().replace("\n", " ");
        }
        String prefix = "delete self.Iterator;delete Promise.withResolvers;\n" + compat + "\n";
        String suffix = "\npostMessage({dshaPdfAudit:true,ok:typeof Iterator==='function'&&typeof Iterator.prototype.join==='function'&&new Set([1,2]).values().filter(x=>x>1).toArray()[0]===2});";
        evaluate(view, "(()=>{window.__dshaPdfWorker=null;const u=URL.createObjectURL(new Blob(["+JSONObject.quote(prefix)+","+workerLiteral+","+JSONObject.quote(suffix)+"],{type:'text/javascript'})),w=new Worker(u,{type:'module'});const done=v=>{window.__dshaPdfWorker=v;w.terminate();URL.revokeObjectURL(u)};w.onmessage=e=>{if(e.data&&e.data.dshaPdfAudit)done(e.data.ok?'PASS':'FAIL')};w.onerror=e=>done('ERROR '+e.message);setTimeout(()=>{if(!window.__dshaPdfWorker)done('TIMEOUT')},10000)})()");
        long until = SystemClock.elapsedRealtime() + 12000; String state;
        do { Thread.sleep(150); state = evaluate(view, "window.__dshaPdfWorker"); } while ("null".equals(state) && SystemClock.elapsedRealtime() < until);
        check("\"PASS\"".equals(state), "真实 PDF Worker 初始化失败：" + state);
    }
    @Override public void callActivityOnCreate(Activity page,Bundle state){super.callActivityOnCreate(page,state);page.getWindow().addFlags(android.view.WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED|android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON|android.view.WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON);}
    @Override public void onStart(){
        Bundle result=new Bundle();HarnessController c=HarnessController.get(getTargetContext());Activity host=null;WebView[] browser={null};androidx.webkit.ScriptHandler[] erase={null};
        var prefs=getTargetContext().getSharedPreferences(Constants.PREFS,0);Map<String,?> before=prefs.getAll();String record="";
        Map<File,byte[]> records=new LinkedHashMap<>();
        try {
            shell("input keyevent 224");shell("am start -W -n com.dsh.client/com.deepseekharness.app.ui.MainActivity");
            long end=SystemClock.elapsedRealtime()+120000;while((!c.isEnvironmentReady()||BackupManager.isEnvironmentTaskBusy())&&SystemClock.elapsedRealtime()<end)Thread.sleep(150);
            check(c.isEnvironmentReady()&&!BackupManager.isEnvironmentTaskBusy(),"环境未就绪");BackupManager.stopWebForMaintenance(c);
            File directory=new File(getTargetContext().getFilesDir(),"startup-history");File[] existing=directory.listFiles();if(existing!=null)for(File file:existing)if(file.getName().endsWith(".properties"))records.put(file,Compat.readAllBytes(file));
            c.config().setProroot(false);check(c.startWeb(null),"正常启动未入队");record=c.startupDiagnostics().recordId();
            end=SystemClock.elapsedRealtime()+90000;while(c.getWebAuthUrl().isEmpty()&&SystemClock.elapsedRealtime()<end&&!c.config().isStartupRecoveryRequested())Thread.sleep(100);
            check(!c.getWebAuthUrl().isEmpty(),"正常启动失败");String cookie=c.exchangeDshAuthCookie();check(cookie!=null,"鉴权失败");
            String base=WebPreviewPolicy.loopbackBaseUrl(c.getWebAuthUrl());
            File index=new File(c.proot().getRootfsDir(),"usr/local/lib/node_modules/@deepseek-ai/dsh/node_modules/@deepseek-ai/dsh-web-frontend/dist/index.html");
            String html=Compat.readAll(index);check(html.indexOf("DSHA_BROWSER_COMPAT_BEGIN")<html.indexOf("<script type=\"module\""),"HTML 补丁未在应用脚本之前");
            check(html.contains("AbortSignal"),"网页入口没有取消信号补丁");
            host=startActivitySync(new Intent(getTargetContext(),FragmentSessionTestActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));Activity activity=host;
            List<String> errors=Collections.synchronizedList(new ArrayList<>());
            runOnMainSync(()->{
                WebView view=new WebView(activity);browser[0]=view;view.getSettings().setJavaScriptEnabled(true);view.getSettings().setDomStorageEnabled(true);view.setWebViewClient(new WebViewClient());
                view.setWebChromeClient(new WebChromeClient(){@Override public boolean onConsoleMessage(ConsoleMessage message){if(message.messageLevel()==ConsoleMessage.MessageLevel.ERROR)errors.add(message.message());return true;}});
                activity.setContentView(view);
                erase[0]=androidx.webkit.WebViewCompat.addDocumentStartJavaScript(view,"Object.defineProperty(AbortSignal,'any',{value:undefined,writable:true,configurable:true});Object.defineProperty(AbortSignal,'timeout',{value:undefined,writable:true,configurable:true});Object.defineProperty(crypto,'randomUUID',{value:undefined,writable:true,configurable:true});delete window.Iterator;window.__dshaMissingBeforeBootstrap=typeof AbortSignal.any==='undefined'&&typeof AbortSignal.timeout==='undefined';",Collections.singleton(base.substring(0,base.length()-1)));
            });
            CountDownLatch readyCookie=new CountDownLatch(1);runOnMainSync(()->CookieManager.getInstance().setCookie(base,cookie+"; Path=/; HttpOnly; SameSite=Strict",ok->readyCookie.countDown()));check(readyCookie.await(10,TimeUnit.SECONDS),"Cookie 未写入");
            for(int round=0;round<2;round++){
                runOnMainSync(()->browser[0].loadUrl(base));
                end=SystemClock.elapsedRealtime()+60000;boolean ready=false;
                while(SystemClock.elapsedRealtime()<end){
                    ready="true".equals(evaluate(browser[0],"Boolean(window.__dshaMissingBeforeBootstrap&&typeof AbortSignal.any==='function'&&typeof AbortSignal.timeout==='function'&&typeof crypto.randomUUID==='function'&&typeof Iterator==='function'&&typeof Iterator.prototype.filter==='function'&&!document.querySelector('[data-dsh-boot]')&&(document.querySelector('[data-composer-input]')||document.getElementById('root')?.children.length))"));
                    if(ready)break;Thread.sleep(250);
                }
                check(ready,"删除原生接口后的真实网页未就绪："+errors);
                check("true".equals(evaluate(browser[0],"(()=>{const a=new AbortController(),b=new AbortController(),s=AbortSignal.any([a.signal,b.signal]);b.abort('expected-source-reason');return s.aborted&&s.reason==='expected-source-reason'})()")),"取消原因未传递");
                evaluate(browser[0],"window.__dshaTimeout=AbortSignal.timeout(0)");
                long timerDeadline=SystemClock.elapsedRealtime()+3000; boolean timedOut=false;
                while(SystemClock.elapsedRealtime()<timerDeadline){
                    timedOut="true".equals(evaluate(browser[0],"window.__dshaTimeout.aborted&&window.__dshaTimeout.reason.name==='TimeoutError'"));
                    if(timedOut)break;Thread.sleep(50);
                }
                check(timedOut,"超时取消未完成");
            }
            check(errors.stream().noneMatch(text->text.contains("AbortSignal")||text.contains("randomUUID")||text.contains("withResolvers")||text.contains("Iterator")||text.contains("Failed to load plugins")||text.contains("failed to import loader")),"仍有兼容接口错误："+errors);
            if(headerAudit)header(browser[0],result);
            pdfWorker(browser[0], c);
            result.putString("pdfWorker", "PASS: actual locked PDF.js module Worker starts after erasing Iterator and Promise.withResolvers");
            result.putString("result","PASS");result.putInt("checks",checks);result.putString("coverage","真实页面及重载：起始删除 any/timeout/randomUUID/Iterator，仅 HTML 前导恢复，取消原因和异步超时通过");
        }catch(Throwable error){result.putString("failure",SensitiveData.redact(android.util.Log.getStackTraceString(error)));}
        finally {
            Activity activity=host;runOnMainSync(()->{if(erase[0]!=null)erase[0].remove();if(browser[0]!=null)browser[0].destroy();if(activity!=null)activity.finish();});
            try {BackupManager.stopWebForMaintenance(c);var writer=StartupDiagnostics.class.getDeclaredField("historyIo");writer.setAccessible(true);((ExecutorService)writer.get(c.startupDiagnostics())).submit(()->{}).get(10,TimeUnit.SECONDS);
                if(!record.isEmpty())new File(getTargetContext().getFilesDir(),"startup-history/"+record+".properties").delete();for(var entry:records.entrySet())Compat.write(entry.getKey(),entry.getValue());
            }catch(Exception error){result.putString("failure","清理失败："+error.getClass().getSimpleName());}
            var edit=prefs.edit();for(String key:new String[]{Constants.KEY_CONTAINER_RUNTIME,"web_fallback_for","web_fallback_port","web_startup_recovery_requested","web_consecutive_failures","web_failure_stage","web_failure_reason"}){
                Object value=before.get(key);if(value==null)edit.remove(key);else if(value instanceof String)edit.putString(key,(String)value);else if(value instanceof Boolean)edit.putBoolean(key,(Boolean)value);else if(value instanceof Integer)edit.putInt(key,(Integer)value);
            }edit.commit();finish(result.containsKey("failure")?Activity.RESULT_CANCELED:Activity.RESULT_OK,result);
        }
    }
}
