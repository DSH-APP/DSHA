package com.deepseekharness.app.ui;

import android.app.*;
import android.content.*;
import android.os.Bundle;
import android.webkit.WebView;
import com.deepseekharness.app.HarnessService;
import com.deepseekharness.app.core.HarnessController;
import com.deepseekharness.app.util.Constants;
import java.io.*;
import java.util.concurrent.atomic.AtomicBoolean;

/** 真实 dsh 客户端适配加载检查；不创建会话、不发送消息、不改变插件状态。 */
public final class RealWebInstrumentation extends Instrumentation {
    @Override public void onCreate(Bundle args){super.onCreate(args);start();}
    private void check(boolean ok,String why){if(!ok)throw new AssertionError(why);}
    @Override public void onStart(){
        Bundle output=new Bundle();Context app=getTargetContext().getApplicationContext();
        HarnessController controller=HarnessController.get(app);Activity page=null;boolean started=false;
        SharedPreferences prefs=app.getSharedPreferences(Constants.PREFS,0);Object count=prefs.getAll().get("backup_launch_count");
        try {
            check(!controller.isStarting()&&!controller.isWebRunning(),"用户服务正在运行，保留现场");
            try(android.os.ParcelFileDescriptor fd=getUiAutomation().executeShellCommand("am start -W -n com.dsh.client/com.deepseekharness.app.ui.MainActivity");InputStream in=new android.os.ParcelFileDescriptor.AutoCloseInputStream(fd)){while(in.read()!=-1){}}
            check(controller.startWeb(null),"真实 Web 启动未接受");started=true;
            runOnMainSync(()->androidx.core.content.ContextCompat.startForegroundService(app,new Intent(app,HarnessService.class)));
            long deadline=System.currentTimeMillis()+90000;
            while(controller.getWebAuthUrl().isEmpty()&&System.currentTimeMillis()<deadline)Thread.sleep(200);
            check(!controller.getWebAuthUrl().isEmpty(),"真实 Web 启动失败："+controller.config().getWebFailureReason());
            page=startActivitySync(WebPreviewActivity.intent(app,controller.getWebAuthUrl(),controller.exchangeDshAuthCookie()).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            java.lang.reflect.Field field=WebPreviewActivity.class.getDeclaredField("webView");field.setAccessible(true);WebView view=(WebView)field.get(page);
            check(view!=null,"系统 WebView 未创建");AtomicBoolean ready=new AtomicBoolean();deadline=System.currentTimeMillis()+60000;
            while(!ready.get()&&System.currentTimeMillis()<deadline){
                runOnMainSync(()->view.evaluateJavascript("(function(){if(window.__DSH_BOOT_READY__)window.__DSH_BOOT_READY__.promise.then(function(){window.__dshaBootCheck=true});return document.documentElement.getAttribute('data-dsha-integration')==='ready' && !!window.__dshaBootCheck && !!document.querySelector('[data-shell-overlay]');})()",value->ready.set("true".equals(value))));Thread.sleep(300);
            }
            check(ready.get(),"真实 dsh 0.1.2-rc.1 页面未完成启动信号、页面渲染或应用状态适配");
            output.putString("result","PASS：真实 dsh 启动信号已完成、页面渲染及客户端适配就绪，未发送消息");
        }catch(Throwable error){output.putString("failure",android.util.Log.getStackTraceString(error));}
        finally{
            if(page!=null){Activity closing=page;runOnMainSync(closing::finish);}
            if(started){controller.stopWeb();app.stopService(new Intent(app,HarnessService.class));}
            SharedPreferences.Editor restore=prefs.edit();if(count==null)restore.remove("backup_launch_count");else restore.putInt("backup_launch_count",(Integer)count);restore.commit();
            finish(output.containsKey("failure")?1:0,output);
        }
    }
}
