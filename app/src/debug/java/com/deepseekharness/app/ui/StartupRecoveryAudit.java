package com.deepseekharness.app.ui;

import android.app.*;
import android.os.*;
import android.content.*;
import android.view.*;
import android.widget.*;
import com.deepseekharness.app.core.*;
import com.deepseekharness.app.util.*;
import com.deepseekharness.app.BackupManager;
import com.deepseekharness.app.R;
import org.json.*;
import java.io.*;
import java.util.*;

/** 非调试真机：真实启动、跨语言旧日志、故障进入恢复及配置回退；不发送模型请求。 */
public final class StartupRecoveryAudit extends Instrumentation {
    private int checks;
    private Bundle args;
    private final StringBuilder evidence=new StringBuilder();
    private final List<Activity> opened=new ArrayList<>();
    private void check(boolean value,String message){checks++;if(!value)throw new AssertionError(message);}
    private void note(String message){evidence.append(SensitiveData.redact(message)).append('\n');Bundle out=new Bundle();out.putString("stream",SensitiveData.redact(message)+"\n");sendStatus(0,out);}
    @Override public void onCreate(Bundle args){super.onCreate(args);this.args=args;start();}
    private Activity open(Class<? extends Activity> type)throws Exception{
        try(var fd=getUiAutomation().executeShellCommand("input keyevent 224");var in=new ParcelFileDescriptor.AutoCloseInputStream(fd)){while(in.read()!=-1){}}
        ActivityMonitor monitor=addMonitor(type.getName(),null,false);
        try{
            if(type==MainActivity.class){try(var fd=getUiAutomation().executeShellCommand("am start -W -n com.dsh.client/com.deepseekharness.app.ui.MainActivity");var in=new ParcelFileDescriptor.AutoCloseInputStream(fd)){while(in.read()!=-1){}}}
            else runOnMainSync(()->getTargetContext().startActivity(new Intent(getTargetContext(),type).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)));
            Activity activity=waitForMonitorWithTimeout(monitor,15000);check(activity!=null,"页面未打开 "+type);opened.add(activity);
            runOnMainSync(()->activity.getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON|WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON|WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED));return activity;
        }finally{removeMonitor(monitor);}
    }
    private void idle(HarnessController c)throws Exception{
        long until=SystemClock.elapsedRealtime()+180000;
        while((!c.isEnvironmentReady()||BackupManager.isEnvironmentTaskBusy())&&SystemClock.elapsedRealtime()<until)Thread.sleep(150);
        check(c.isEnvironmentReady()&&!BackupManager.isEnvironmentTaskBusy(),"运行环境尚未空闲");
    }
    private void auth(HarnessController c)throws Exception{
        long until=SystemClock.elapsedRealtime()+60000;
        while(c.getWebAuthUrl().isEmpty()&&SystemClock.elapsedRealtime()<until&&!c.config().isStartupRecoveryRequested())Thread.sleep(100);
        check(!c.getWebAuthUrl().isEmpty(),"启动未完成："+c.startupDiagnostics().snapshot().log);
    }
    private void task(BackupTask task)throws Exception{
        long until=SystemClock.elapsedRealtime()+60000;
        while(task.busy()&&SystemClock.elapsedRealtime()<until)Thread.sleep(100);
        check(!task.busy(),"修复任务未结束");check(task.snapshot().status==BackupTaskState.Status.SUCCEEDED,"修复失败："+task.snapshot().detail);
    }
    private void shot(String name)throws Exception{
        android.graphics.Bitmap bitmap=getUiAutomation().takeScreenshot();if(bitmap==null)return;
        try(FileOutputStream out=new FileOutputStream(new File(getTargetContext().getCacheDir(),name+".png"))){bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG,100,out);}finally{bitmap.recycle();}
    }
    private View find(View view,String value){
        if(view instanceof TextView && value.equals(((TextView)view).getText().toString())){View action=view;while(!action.isClickable()&&action.getParent() instanceof View)action=(View)action.getParent();return action;}
        if(view instanceof ViewGroup)for(int i=0;i<((ViewGroup)view).getChildCount();i++){View found=find(((ViewGroup)view).getChildAt(i),value);if(found!=null)return found;}
        return null;
    }
    @Override public void onStart(){
        Bundle result=new Bundle();HarnessController c=HarnessController.get(getTargetContext());
        android.content.SharedPreferences prefs=getTargetContext().getSharedPreferences(Constants.PREFS,0);
        Map<String,?> settings=prefs.getAll();Map<File,byte[]> originals=new LinkedHashMap<>();Activity page=null;
        Set<String> ownHistory=new HashSet<>();
        File[] ownPluginFiles=new File[0];
        try{
            open(MainActivity.class);idle(c);BackupManager.stopWebForMaintenance(c);
            File home=new File(c.proot().getRootfsDir(),"root/.dsh");
            for(String name:new String[]{"profiles/web/package.json","profiles/web/cordis.patch.yml","profiles/web/pnpm-workspace.yaml","profiles/web/pnpm-lock.yaml","settings.yaml","cordis.patch.yml"}){
                File file=new File(home,name);check(!Compat.isSymbolicLink(file),"测试不改软链接配置");originals.put(file,file.isFile()?Compat.readAllBytes(file):null);
            }
            for(String name:new String[]{"healthy-1.json","healthy-2.json","healthy-3.json","before-1.json","before-2.json","before-3.json","pending.json","skip-healthy","candidate.json"}) {
                File file=new File(home,"dsha-startup-checkpoints/"+name);originals.put(file,file.isFile()?Compat.readAllBytes(file):null);
            }
            for(String name:new String[]{"last-startup-failure.log","startup-config-repair.pending"}){File file=new File(getTargetContext().getFilesDir(),name);originals.put(file,file.isFile()?Compat.readAllBytes(file):null);}
            File sources=new File(home,"plugin-sources.json");originals.put(sources,sources.isFile()?Compat.readAllBytes(sources):null);
            File[] records=new File(getTargetContext().getFilesDir(),"startup-history").listFiles();
            if(records!=null)for(File file:records)if(file.getName().matches("[0-9a-f-]{36}\\.properties"))originals.put(file,Compat.readAllBytes(file));
            runOnMainSync(()->LanguageController.select(getTargetContext(),"zh"));Thread.sleep(800);
            check(c.startWeb(null),"正常启动未接受");ownHistory.add(c.startupDiagnostics().recordId());auth(c);long generation=c.getWebGeneration();
            check(c.startupDiagnostics().snapshot().log.contains("配置检查："),"缺少真实配置检查输出");
            runOnMainSync(()->LanguageController.select(getTargetContext(),"en"));Thread.sleep(1000);
            check(c.getWebGeneration()==generation,"切语言重启了 Web");
            String log=c.startupDiagnostics().snapshot().log;
            String han=log.lines().filter(line->line.matches(".*[\\p{IsHan}].*")).collect(java.util.stream.Collectors.joining("\n"));
            note("EN_REMAINING_HAN\n"+han);check(han.isEmpty(),"英文旧日志仍有应用中文");
            check(log.contains("Configuration check:"),"配置检查没有翻译");
            try(EnvironmentTaskGate.Lease lease=EnvironmentTaskGate.tryAcquire("插件操作")){
                check(lease!=null,"测试未取得普通任务锁");Thread.sleep(1400);
                runOnMainSync(()->check(MainActivity.current.findViewById(R.id.environment_recovery_banner).getVisibility()==View.GONE,"普通任务仍冒充环境维护"));
            }
            shot("startup-recovery-English-log");
            String cookie=c.exchangeDshAuthCookie();check(cookie!=null,"Cookie 鉴权失败");
            String activityName=com.deepseekharness.app.BuildConfig.LOW_ANDROID?"com.deepseekharness.app.ui.GeckoPreviewActivity":WebPreviewActivity.class.getName();
            ActivityMonitor browser=addMonitor(activityName,null,false);
            try{runOnMainSync(()->getTargetContext().startActivity(WebPreviewActivity.intent(getTargetContext(),c.getWebAuthUrl(),cookie).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)));
                page=waitForMonitorWithTimeout(browser,15000);check(page!=null,"网页未打开");
            }finally{removeMonitor(browser);}
            long until=SystemClock.elapsedRealtime()+45000;
            while(!c.startupDiagnostics().snapshot().browserReady&&SystemClock.elapsedRealtime()<until)Thread.sleep(150);
            check(c.startupDiagnostics().snapshot().browserReady,"真实网页未就绪");Thread.sleep(1500);idle(c);
            JSONObject listing=StartupRepairs.list(c);JSONArray rows=listing.getJSONArray("snapshots");JSONObject healthy=null;
            for(int i=0;i<rows.length();i++){JSONObject row=rows.getJSONObject(i);if(row.optString("slot").startsWith("healthy")&&!row.optBoolean("invalid")&&(healthy==null||row.optLong("created")>healthy.optLong("created")))healthy=row;}
            check(healthy!=null,"健康启动未生成配置快照");note("HEALTHY_SNAPSHOT "+healthy.getString("slot"));
            Activity closing=page;runOnMainSync(closing::finish);page=null;Thread.sleep(700);BackupManager.stopWebForMaintenance(c);
            File manifest=new File(home,"profiles/web/package.json");
            BackupManager.runDataTask(c,()->{Compat.write(manifest,"{");return null;});
            ActivityMonitor recovery=addMonitor(StartupRecoveryActivity.class.getName(),null,false);
            Activity recovered;
            try{check(c.startWeb(null),"故障启动未接受");ownHistory.add(c.startupDiagnostics().recordId());recovered=waitForMonitorWithTimeout(recovery,45000);check(recovered!=null,"配置损坏未自动进入恢复页");opened.add(recovered);}
            finally{removeMonitor(recovery);}
            check(c.config().isStartupRecoveryRequested(),"没有保存恢复原因");check(!c.canAutoRestart(),"明确配置失败仍触发自动重启");
            idle(c);Thread.sleep(1200);shot("startup-recovery-auto");
            check("{".equals(Compat.readAll(manifest)),"失败时偷偷覆盖原配置");
            BackupTask repair=BackupTask.get(getTargetContext());
            check(repair.repairStartup(new JSONObject().put("command","restore").put("slot",healthy.getString("slot")).put("id",healthy.getString("id"))),"恢复快照任务未接受");task(repair);
            check(!StartupRepairs.pending(getTargetContext()),"恢复成功后仍被标为维护中");
            check(!"{".equals(Compat.readAll(manifest)),"配置没有真正恢复");
            check(repair.repairStartup(new JSONObject().put("command","new").put("target","settings.yaml")),"新建配置任务未接受");task(repair);
            check("{}".equals(Compat.readAll(new File(home,"settings.yaml")).trim()),"没有真正新建默认配置");
            JSONObject restore=new JSONObject().put("command","restore").put("slot",healthy.getString("slot")).put("id",healthy.getString("id"));
            check(repair.repairStartup(restore),"还原测试前配置未接受");task(repair);
            String fixture="dsh-startup-audit-"+UUID.randomUUID().toString().substring(0,8);
            File fixtureDir=new File(home,"plugin-src/"+fixture);check(fixtureDir.mkdir(),"无法创建独立验收插件");
            File pkg=new File(fixtureDir,"package.json"),patch=new File(fixtureDir,"cordis.patch.yml");ownPluginFiles=new File[]{pkg,patch,fixtureDir};
            Compat.write(pkg,new JSONObject().put("name",fixture).put("version","1.0.0").put("dsh",new JSONObject().put("bundle",new JSONObject().put("patch","cordis.patch.yml"))).toString());Compat.write(patch,"[]\n");
            BackupManager.runDataTask(c,()->{
                JSONObject doc=new JSONObject(Compat.readAll(manifest));JSONObject dependencies=doc.optJSONObject("dependencies");if(dependencies==null){dependencies=new JSONObject();doc.put("dependencies",dependencies);}
                dependencies.put(fixture,"link:/root/.dsh/plugin-src/"+fixture);doc.getJSONObject("dsh").getJSONObject("profile").getJSONArray("bundles").put(fixture);Compat.write(manifest,doc.toString());return null;
            });
            runOnMainSync(()->find(recovered.getWindow().getDecorView(),"Refresh recovery records").performClick());
            long pluginWait=SystemClock.elapsedRealtime()+30000;
            while(find(recovered.getWindow().getDecorView(),fixture+" · Remove")==null&&SystemClock.elapsedRealtime()<pluginWait)Thread.sleep(100);
            View remove=find(recovered.getWindow().getDecorView(),fixture+" · Remove");check(remove!=null,"恢复页未列出实际验收插件");
            long previous=repair.snapshot().id;runOnMainSync(remove::performClick);Thread.sleep(250);
            var nodes=getUiAutomation().getRootInActiveWindow().findAccessibilityNodeInfosByText("Continue");
            check(!nodes.isEmpty(),"卸载确认按钮未显示");check(nodes.get(0).performAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_CLICK),"卸载确认点击失败");
            pluginWait=SystemClock.elapsedRealtime()+4000;while(repair.snapshot().id==previous&&SystemClock.elapsedRealtime()<pluginWait)Thread.sleep(100);
            check(repair.snapshot().id!=previous,"卸载没有启动应用级任务");task(repair);
            check(!fixtureDir.exists(),"插件实体没有卸载");check(!Compat.readAll(manifest).contains(fixture),"插件清单仍包含已卸载项目");
            note("RECOVERY_PLUGIN_UNINSTALL_OK");
            runOnMainSync(recovered::finish);Thread.sleep(700);check(c.startWeb(null),"修复后未接受启动");ownHistory.add(c.startupDiagnostics().recordId());auth(c);
            check(!c.config().isStartupRecoveryRequested(),"成功重试没有解除恢复状态");
            note("POST_REPAIR_AUTH_OK");
            long historyWait=SystemClock.elapsedRealtime()+3000;
            while(c.startupDiagnostics().history().size()<3&&SystemClock.elapsedRealtime()<historyWait)Thread.sleep(100);
            check(c.startupDiagnostics().history().stream().anyMatch(entry->"failed".equals(entry.status)),"重试覆盖了失败快照");
            result.putString("result","PASS");result.putInt("checks",checks);
        }catch(Throwable error){result.putString("failure",SensitiveData.redact(android.util.Log.getStackTraceString(error)));}
        finally{
            if(page!=null){Activity closing=page;runOnMainSync(closing::finish);}
            try{BackupManager.stopWebForMaintenance(c);
                java.lang.reflect.Field writer=StartupDiagnostics.class.getDeclaredField("historyIo");writer.setAccessible(true);
                ((java.util.concurrent.ScheduledExecutorService)writer.get(c.startupDiagnostics())).submit(()->{}).get(10,java.util.concurrent.TimeUnit.SECONDS);
                for(String id:ownHistory)new File(getTargetContext().getFilesDir(),"startup-history/"+id+".properties").delete();
                BackupManager.runDataTask(c,()->{
                for(Map.Entry<File,byte[]> entry:originals.entrySet()){
                    if(entry.getValue()==null){if(entry.getKey().exists()&&!entry.getKey().delete())throw new IOException("无法删除测试创建的配置");}
                    else Compat.write(entry.getKey(),entry.getValue());
                }return null;
            });for(Map.Entry<File,byte[]> entry:originals.entrySet())check(entry.getValue()==null?!entry.getKey().exists():Arrays.equals(entry.getValue(),Compat.readAllBytes(entry.getKey())),"原配置未恢复");}
            catch(Throwable error){result.putString("failure","清理失败："+SensitiveData.redact(String.valueOf(error)));}
            for(File file:ownPluginFiles)if(file.exists()&&!file.delete())result.putString("failure","无法清理验收插件文件："+file.getName());
            for(Activity activity:opened)if(!(activity instanceof MainActivity))runOnMainSync(()->{if(!activity.isFinishing())activity.finish();});
            SharedPreferences.Editor restore=prefs.edit();
            for(String key:new String[]{"ui_language","web_startup_recovery_requested","web_consecutive_failures","web_failure_stage","web_failure_reason"}){
                Object value=settings.get(key);if(value==null)restore.remove(key);else if(value instanceof String)restore.putString(key,(String)value);else if(value instanceof Boolean)restore.putBoolean(key,(Boolean)value);else if(value instanceof Integer)restore.putInt(key,(Integer)value);
            }restore.commit();runOnMainSync(()->LanguageController.apply(getTargetContext()));
            try{Compat.write(new File(getTargetContext().getCacheDir(),"startup-recovery-audit.log"),evidence.toString());}catch(Exception ignored){}
            finish(result.containsKey("failure")?Activity.RESULT_CANCELED:Activity.RESULT_OK,result);
        }
    }
}
