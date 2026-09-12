package com.deepseekharness.app.core;

import android.app.*;
import android.os.*;
import android.content.*;
import com.deepseekharness.app.BackupManager;
import com.deepseekharness.app.util.*;
import java.net.*;
import java.io.*;
import java.util.*;

/** 非调试真机：占用首选端口，确认动态端口、鉴权、备用重用和两种运行时。 */
public final class WebPortAudit extends Instrumentation {
    private int checks;
    private Bundle args;
    private final StringBuilder log=new StringBuilder();
    private final Set<String> historyIds=new HashSet<>();
    private void check(boolean ok,String why){checks++;if(!ok)throw new AssertionError(why);}
    private void note(String value){log.append(value).append('\n');Bundle out=new Bundle();out.putString("stream",value+"\n");sendStatus(0,out);}
    @Override public void onCreate(Bundle args){super.onCreate(args);this.args=args;start();}
    private ServerSocket listen(int port)throws Exception{ServerSocket socket=new ServerSocket();socket.setReuseAddress(true);socket.bind(new InetSocketAddress("127.0.0.1",port));return socket;}
    private int start(HarnessController c,boolean safe)throws Exception{
        check(safe?c.startWebSafely(null):c.startWeb(null),"启动未入队");
        historyIds.add(c.startupDiagnostics().recordId());
        long end=SystemClock.elapsedRealtime()+90000;
        while(c.getWebAuthUrl().isEmpty()&&!c.config().isStartupRecoveryRequested()&&SystemClock.elapsedRealtime()<end)Thread.sleep(100);
        check(!c.getWebAuthUrl().isEmpty(),"没有鉴权地址："+c.startupDiagnostics().snapshot().log);
        int actual=URI.create(c.getWebAuthUrl()).getPort();check(actual==c.getWebPort(),"实际端口与鉴权不一致");
        check(c.exchangeDshAuthCookie()!=null,"实际端口鉴权失败");
        return actual;
    }
    private void stopped(HarnessController c)throws Exception{BackupManager.stopWebForMaintenance(c);check(c.isWebStoppedForMaintenance(),"已验证的 Web 未退出");}
    @Override public void onStart(){
        Bundle result=new Bundle();HarnessController c=HarnessController.get(getTargetContext());
        SharedPreferences prefs=getTargetContext().getSharedPreferences(Constants.PREFS,0);Map<String,?> before=prefs.getAll();
        ServerSocket owner=null,second=null;Activity page=null;
        Map<File,byte[]> savedFiles=new LinkedHashMap<>();
        try{
            try(var fd=getUiAutomation().executeShellCommand("am start -W -n com.dsh.client/com.deepseekharness.app.ui.MainActivity");var in=new ParcelFileDescriptor.AutoCloseInputStream(fd)){while(in.read()!=-1){}}
            long end=SystemClock.elapsedRealtime()+120000;
            while((!c.isEnvironmentReady()||BackupManager.isEnvironmentTaskBusy())&&SystemClock.elapsedRealtime()<end)Thread.sleep(150);
            check(c.isEnvironmentReady()&&!BackupManager.isEnvironmentTaskBusy(),"环境未就绪");stopped(c);
            File history=new File(getTargetContext().getFilesDir(),"startup-history");File[] records=history.listFiles();
            if(records!=null)for(File file:records)if(file.getName().matches("[0-9a-f-]{36}\\.properties"))savedFiles.put(file,Compat.readAllBytes(file));
            File checkpoint=new File(c.proot().getRootfsDir(),"root/.dsh/dsha-startup-checkpoints");
            for(String name:new String[]{"healthy-1.json","healthy-2.json","healthy-3.json","before-1.json","before-2.json","before-3.json","candidate.json","pending.json","skip-healthy"}){
                File file=new File(checkpoint,name);check(!Compat.isSymbolicLink(file),"不修改重定向的用户快照");savedFiles.put(file,file.isFile()?Compat.readAllBytes(file):null);
            }
            File failure=new File(getTargetContext().getFilesDir(),"last-startup-failure.log");savedFiles.put(failure,failure.isFile()?Compat.readAllBytes(failure):null);
            for(String runtime:new String[]{"proot","proroot"}){
                c.config().setProroot(runtime.equals("proroot"));
                owner=listen(WebPortPolicy.available(3080)?3080:0);int preferred=owner.getLocalPort();c.config().setPort(String.valueOf(preferred));
                prefs.edit().remove("web_fallback_for").remove("web_fallback_port").commit();
                int actual=start(c,false);check(actual!=preferred,"端口冲突没有避让");check(c.config().getPortInt()==preferred,"覆盖了首选端口");
                check(c.config().fallbackWebPort(preferred)==actual,"未记住成功备用端口");
                // 配置编辑不应把已运行服务的鉴权与保活指回另一个端口。
                c.config().setPort("39081");check(c.getWebPort()==actual&&c.exchangeDshAuthCookie()!=null,"修改配置影响当前鉴权端口");c.config().setPort(String.valueOf(preferred));
                owner.setSoTimeout(150);try{Socket unexpected=owner.accept();unexpected.close();throw new AssertionError("向占用者发起了连接");}catch(SocketTimeoutException expected){}
                if(runtime.equals("proot")){
                    String cookie=c.exchangeDshAuthCookie();String name=com.deepseekharness.app.BuildConfig.LOW_ANDROID?"com.deepseekharness.app.ui.GeckoPreviewActivity":"com.deepseekharness.app.ui.WebPreviewActivity";
                    ActivityMonitor monitor=addMonitor(name,null,false);
                    try{runOnMainSync(()->getTargetContext().startActivity(com.deepseekharness.app.ui.WebPreviewActivity.intent(getTargetContext(),c.getWebAuthUrl(),cookie).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)));
                        page=waitForMonitorWithTimeout(monitor,15000);check(page!=null,"动态端口网页未打开");
                    }finally{removeMonitor(monitor);}
                    end=SystemClock.elapsedRealtime()+45000;while(!c.startupDiagnostics().snapshot().browserReady&&SystemClock.elapsedRealtime()<end)Thread.sleep(100);
                    check(c.startupDiagnostics().snapshot().browserReady,"动态端口网页未就绪");Activity closing=page;runOnMainSync(closing::finish);page=null;
                }
                stopped(c);check(!owner.isClosed(),"停止误关占用者");
                check(start(c,true)==actual,"安全启动未重用空闲备用端口");stopped(c);
                if(runtime.equals("proot")){
                    second=listen(actual);int replacement=start(c,false);check(replacement!=actual&&replacement!=preferred,"备用端口被占用后没有重新分配");stopped(c);second.close();second=null;
                }
                owner.close();owner=null;check(start(c,false)==preferred,"释放首选端口后没有恢复使用");stopped(c);
                note(runtime+": preferred="+preferred+" fallback="+actual+"; auth, safe restart, preferred restore and owned stop PASS");
            }
            result.putString("result","PASS");result.putInt("checks",checks);
        }catch(Throwable error){result.putString("failure",SensitiveData.redact(android.util.Log.getStackTraceString(error)));}
        finally{
            if(page!=null){Activity closing=page;runOnMainSync(closing::finish);}
            try{stopped(c);}catch(Exception error){result.putString("failure","最终停止失败："+SensitiveData.redact(String.valueOf(error)));}
            try{if(owner!=null)owner.close();if(second!=null)second.close();}catch(Exception ignored){}
            try{
                var writer=StartupDiagnostics.class.getDeclaredField("historyIo");writer.setAccessible(true);
                ((java.util.concurrent.ExecutorService)writer.get(c.startupDiagnostics())).submit(()->{}).get(15,java.util.concurrent.TimeUnit.SECONDS);
                for(String id:historyIds)new File(getTargetContext().getFilesDir(),"startup-history/"+id+".properties").delete();
                for(var entry:savedFiles.entrySet()){
                    if(entry.getValue()==null){if(entry.getKey().exists()&&!entry.getKey().delete())throw new IOException("无法清理测试记录");}
                    else Compat.write(entry.getKey(),entry.getValue());
                }
            }catch(Exception error){result.putString("failure","记录还原失败："+SensitiveData.redact(String.valueOf(error)));}
            SharedPreferences.Editor edit=prefs.edit();
            for(String key:new String[]{Constants.KEY_PORT,Constants.KEY_CONTAINER_RUNTIME,"web_fallback_for","web_fallback_port","web_startup_recovery_requested","web_consecutive_failures","web_failure_stage","web_failure_reason"}){
                Object value=before.get(key);if(value==null)edit.remove(key);else if(value instanceof String)edit.putString(key,(String)value);else if(value instanceof Integer)edit.putInt(key,(Integer)value);else if(value instanceof Boolean)edit.putBoolean(key,(Boolean)value);
            }edit.commit();
            try{Compat.write(new File(getTargetContext().getCacheDir(),"web-port-audit.log"),log.toString());}catch(Exception ignored){}
            finish(result.containsKey("failure")?Activity.RESULT_CANCELED:Activity.RESULT_OK,result);
        }
    }
}
