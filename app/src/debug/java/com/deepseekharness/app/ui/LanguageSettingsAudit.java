package com.deepseekharness.app.ui;

import android.app.*;
import android.content.*;
import android.os.Bundle;
import android.view.*;
import android.view.accessibility.AccessibilityNodeInfo;
import android.widget.*;
import com.deepseekharness.app.R;
import com.deepseekharness.app.core.ConfigStore;
import com.deepseekharness.app.util.Constants;
import com.deepseekharness.app.util.UiText;
import java.util.*;

/** 真实设置入口、英文页面与返回中文。忽略用户输入及历史日志，不导出任何配置值。 */
public final class LanguageSettingsAudit extends Instrumentation {
    public static final class CachedPluginFragment extends PluginFragment { @Override void syncInstalledState() { } }
    private FragmentSessionTestActivity page;
    private int checks;
    private final List<String> untranslated=new ArrayList<>();
    @Override public void onCreate(Bundle args){super.onCreate(args);start();}
    @Override public void callActivityOnCreate(Activity activity,Bundle value){super.callActivityOnCreate(activity,value);activity.getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON|WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON);}
    private void ui(Runnable action){Throwable[] error={null};runOnMainSync(()->{try{action.run();}catch(Throwable t){error[0]=t;}});if(error[0]!=null)throw new AssertionError(error[0]);}
    private void check(boolean good,String why){checks++;if(!good)throw new AssertionError(why);}
    private void mount(androidx.fragment.app.Fragment fragment)throws Exception{ui(()->page.getSupportFragmentManager().beginTransaction().replace(R.id.fragment_container,fragment).commitNow());Thread.sleep(600);waitForIdleSync();}
    private AccessibilityNodeInfo find(AccessibilityNodeInfo node,String label){
        if(node==null)return null;if(node.isVisibleToUser()&&label.contentEquals(String.valueOf(node.getText())))return node;
        for(int i=0;i<node.getChildCount();i++){AccessibilityNodeInfo found=find(node.getChild(i),label);if(found!=null)return found;}return null;
    }
    private void choose(String label)throws Exception{
        ui(()->page.findViewById(R.id.settings_language).performClick());Thread.sleep(250);
        AccessibilityNodeInfo option=find(getUiAutomation().getRootInActiveWindow(),label);check(option!=null,"语言选项不可见："+label);
        android.graphics.Rect rect=new android.graphics.Rect();option.getBoundsInScreen(rect);
        ActivityMonitor monitor=addMonitor(FragmentSessionTestActivity.class.getName(),null,false);
        long now=android.os.SystemClock.uptimeMillis();sendPointerSync(MotionEvent.obtain(now,now,MotionEvent.ACTION_DOWN,rect.centerX(),rect.centerY(),0));sendPointerSync(MotionEvent.obtain(now,now+60,MotionEvent.ACTION_UP,rect.centerX(),rect.centerY(),0));
        FragmentSessionTestActivity next=(FragmentSessionTestActivity)waitForMonitorWithTimeout(monitor,20000);removeMonitor(monitor);check(next!=null,"语言选择后未重建界面");page=next;Thread.sleep(350);
    }
    private boolean historical(int id){return id==R.id.launch_log||id==R.id.install_log||id==R.id.workspace_info||id==R.id.workspace_backup_status||id==R.id.workspace_share_status||id==R.id.pluginName||id==R.id.pluginDesc;}
    private void scan(View view,String scene){
        if(view.getVisibility()!=View.VISIBLE)return;
        if(view instanceof TextView && !(view instanceof EditText)&&!historical(view.getId())){
            TextView text=(TextView)view;String value=text.getText().toString();
            if(value.matches("(?s).*[\\p{IsHan}].*")){
                String id;try{id=view.getResources().getResourceEntryName(view.getId());}catch(Exception e){id=view.getClass().getSimpleName();}
                untranslated.add(scene+"/"+id+": "+com.deepseekharness.app.util.SensitiveData.redact(value).substring(0,Math.min(180,value.length())));
            }
            if(text.getLayout()!=null&&view.getHeight()>0&&view.isClickable())check(text.getLayout().getHeight()+text.getCompoundPaddingTop()+text.getCompoundPaddingBottom()<=view.getHeight()+3,"英文操作文字被纵向裁切："+scene);
        }
        if(view instanceof ViewGroup)for(int i=0;i<((ViewGroup)view).getChildCount();i++)scan(((ViewGroup)view).getChildAt(i),scene);
    }
    @Override public void onStart(){
        Bundle result=new Bundle();Context app=getTargetContext();var prefs=app.getSharedPreferences(Constants.PREFS,0);boolean had=prefs.contains("ui_language");String original=new ConfigStore(app).getUiLanguage();
        try{
            try(var fd=getUiAutomation().executeShellCommand("am start -W -n com.dsh.client/com.deepseekharness.app.ui.MainActivity");var input=new android.os.ParcelFileDescriptor.AutoCloseInputStream(fd)){while(input.read()!=-1){}}
            long end=System.currentTimeMillis()+120000;while(com.deepseekharness.app.BackupManager.isEnvironmentTaskBusy()&&System.currentTimeMillis()<end)Thread.sleep(200);
            ui(()->{new ConfigStore(app).setUiLanguage("zh");LanguageController.apply(app);});
            ActivityMonitor monitor=addMonitor(FragmentSessionTestActivity.class.getName(),null,false);ui(()->app.startActivity(new Intent(app,FragmentSessionTestActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)));
            page=(FragmentSessionTestActivity)waitForMonitorWithTimeout(monitor,15000);removeMonitor(monitor);check(page!=null,"测试宿主未打开");
            mount(new SettingsFragment());check(((TextView)page.findViewById(R.id.settings_language)).getText().toString().contains("简体中文"),"默认中文未显示");
            choose("English");check("en".equals(new ConfigStore(app).getUiLanguage()),"英语选择未保存");
            check("Settings".equals(page.getString(R.string.nav_settings)),"资源没有切换成英文");
            for(androidx.fragment.app.Fragment fragment:new androidx.fragment.app.Fragment[]{new SettingsFragment(),new ConfigFragment(),new WorkspaceFragment(),new InstallFragment(),new DeviceGrantsFragment(),new PluginFragment()}){
                mount(fragment);ui(()->scan(fragment.requireView(),fragment.getClass().getSimpleName()));
            }
            var repository=new androidx.lifecycle.ViewModelProvider(page).get(com.deepseekharness.app.core.PluginRepository.class);
            long endRefresh=System.currentTimeMillis()+30000;while(repository.isBusy()&&System.currentTimeMillis()<endRefresh)Thread.sleep(100);
            String detection="插件检测完成；新检测到的插件可开启开关加入 Web，变更后重启 Web 生效";
            ui(()->repository.selectionMessage(UiText.text(detection)));
            mount(new SettingsFragment());choose("Simplified Chinese");check("zh".equals(new ConfigStore(app).getUiLanguage()),"中文选择未保存");check("设置".equals(page.getString(R.string.nav_settings)),"未返回中文资源");
            mount(new CachedPluginFragment());
            check(detection.equals(((TextView)page.findViewById(R.id.statusText)).getText().toString()),"英文缓存的检测结果没有回到中文");
            check(untranslated.isEmpty(),"未翻译的应用控件：\n"+String.join("\n",untranslated));result.putString("result","PASS");result.putInt("checks",checks);
        }catch(Throwable error){result.putString("failure",android.util.Log.getStackTraceString(error));}
        finally{if(page!=null)ui(page::finish);ui(()->{if(had)prefs.edit().putString("ui_language",original).commit();else prefs.edit().remove("ui_language").commit();LanguageController.apply(app);});finish(result.containsKey("failure")?1:0,result);}
    }
}
