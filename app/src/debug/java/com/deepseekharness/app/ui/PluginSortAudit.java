package com.deepseekharness.app.ui;

import android.app.*;
import android.os.*;
import android.content.*;
import android.view.*;
import android.view.accessibility.AccessibilityNodeInfo;
import android.widget.*;
import androidx.lifecycle.MutableLiveData;
import com.deepseekharness.app.R;
import com.deepseekharness.app.core.*;
import com.deepseekharness.app.util.*;
import org.json.JSONObject;
import java.util.*;

/** 使用独立列表验证真实排序菜单，不安装、更新或删除设备上的插件。 */
public final class PluginSortAudit extends Instrumentation {
    public static final class Page extends PluginFragment { @Override void syncInstalledState() { } }
    private volatile FragmentSessionTestActivity page;
    private int checks;
    @Override public void onCreate(Bundle value){super.onCreate(value);start();}
    @Override public void callActivityOnCreate(Activity activity,Bundle value){super.callActivityOnCreate(activity,value);activity.getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON|WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON);}
    @Override public void callActivityOnResume(Activity activity){super.callActivityOnResume(activity);if(activity instanceof FragmentSessionTestActivity)page=(FragmentSessionTestActivity)activity;}
    private void check(boolean value,String why){checks++;if(!value)throw new AssertionError(why);}
    private void ui(Runnable action){Throwable[] failure={null};runOnMainSync(()->{try{action.run();}catch(Throwable e){failure[0]=e;}});if(failure[0]!=null)throw new AssertionError(failure[0]);waitForIdleSync();}
    private void mount(){ui(()->page.getSupportFragmentManager().beginTransaction().replace(R.id.fragment_container,new Page()).commitNow());}
    private void awaitPage()throws Exception{
        long deadline=SystemClock.elapsedRealtime()+20000;
        while((page==null||!page.getLifecycle().getCurrentState().isAtLeast(androidx.lifecycle.Lifecycle.State.RESUMED)||page.getSupportFragmentManager().isStateSaved())&&SystemClock.elapsedRealtime()<deadline)Thread.sleep(80);
        check(page!=null&&!page.getSupportFragmentManager().isStateSaved(),"测试页面未恢复到可操作状态");
    }
    private AccessibilityNodeInfo find(AccessibilityNodeInfo node,String value){if(node==null)return null;if(node.isVisibleToUser()&&value.equals(String.valueOf(node.getText())))return node;for(int i=0;i<node.getChildCount();i++){var result=find(node.getChild(i),value);if(result!=null)return result;}return null;}
    private void choose(String value)throws Exception{
        Thread.sleep(220);var node=find(getUiAutomation().getRootInActiveWindow(),value);check(node!=null,"排序选项不可见："+value);
        android.graphics.Rect bounds=new android.graphics.Rect();node.getBoundsInScreen(bounds);long now=SystemClock.uptimeMillis();
        sendPointerSync(MotionEvent.obtain(now,now,MotionEvent.ACTION_DOWN,bounds.centerX(),bounds.centerY(),0));sendPointerSync(MotionEvent.obtain(now,now+45,MotionEvent.ACTION_UP,bounds.centerX(),bounds.centerY(),0));Thread.sleep(220);waitForIdleSync();
    }
    private Object field(Object object,String name)throws Exception{var f=object.getClass().getDeclaredField(name);f.setAccessible(true);return f.get(object);}
    private String names()throws Exception{
        var f=PluginFragment.class.getDeclaredField("visibleItems");f.setAccessible(true);
        List<?> items=(List<?>)f.get(page.getSupportFragmentManager().findFragmentById(R.id.fragment_container));List<String> names=new ArrayList<>();for(Object item:items)names.add(((PluginRepository.Item)item).name);return String.join(",",names);
    }
    private void language(String value)throws Exception{
        var monitor=addMonitor(FragmentSessionTestActivity.class.getName(),null,false);ui(()->LanguageController.select(page,value));
        var next=(FragmentSessionTestActivity)waitForMonitorWithTimeout(monitor,15000);removeMonitor(monitor);check(next!=null,"语言切换未重建");waitForIdleSync();awaitPage();
    }
    private void screenshot(String name)throws Exception{
        android.graphics.Bitmap bitmap=getUiAutomation().takeScreenshot();check(bitmap!=null,"无法获取截图");
        java.io.File folder=new java.io.File(getTargetContext().getCacheDir(),"plugin-sort-audit");folder.mkdirs();
        try(var out=new java.io.FileOutputStream(new java.io.File(folder,name+".png"))){bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG,100,out);}finally{bitmap.recycle();}
    }
    @Override public void onStart(){
        Bundle result=new Bundle();var prefs=getTargetContext().getSharedPreferences(Constants.PREFS,0);boolean hadSort=prefs.contains("plugin_sort_order"),hadLanguage=prefs.contains("ui_language");String previousSort=prefs.getString("plugin_sort_order","NAME_ASC"),previousLanguage=new ConfigStore(getTargetContext()).getUiLanguage();
        try{
            try(var fd=getUiAutomation().executeShellCommand("am start -W -n com.dsh.client/com.deepseekharness.app.ui.MainActivity");var input=new ParcelFileDescriptor.AutoCloseInputStream(fd)){while(input.read()!=-1){}}
            long deadline=SystemClock.elapsedRealtime()+120000;var controller=HarnessController.get(getTargetContext());while((!controller.isEnvironmentReady()||com.deepseekharness.app.BackupManager.isEnvironmentTaskBusy())&&SystemClock.elapsedRealtime()<deadline)Thread.sleep(100);
            check(controller.isEnvironmentReady(),"环境更新未完成");
            ui(()->{new ConfigStore(getTargetContext()).setPluginSort(PluginSort.Mode.NAME_ASC);LanguageController.select(getTargetContext(),"zh");});
            Thread.sleep(500);startActivitySync(new Intent(getTargetContext(),FragmentSessionTestActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));waitForIdleSync();awaitPage();mount();
            PluginRepository repository=new androidx.lifecycle.ViewModelProvider(page).get(PluginRepository.class);
            var constructor=PluginRepository.Item.class.getDeclaredConstructor(JSONObject.class);constructor.setAccessible(true);List<PluginRepository.Item> items=new ArrayList<>();
            String[] names={"Zulu","alpha","plugin10","plugin2"};boolean[] enabled={true,false,true,false},updates={false,false,true,true};
            for(int i=0;i<names.length;i++)items.add(constructor.newInstance(new JSONObject().put("name",names[i]).put("available",true).put("enabled",enabled[i]).put("updateAvailable",updates[i]).put("builtin",i==1)));
            var stateConstructor=PluginRepository.State.class.getDeclaredConstructor(List.class,boolean.class,String.class);stateConstructor.setAccessible(true);
            PluginRepository.State ready=stateConstructor.newInstance(items,false,"");
            @SuppressWarnings("unchecked") MutableLiveData<PluginRepository.State> states=(MutableLiveData<PluginRepository.State>)field(repository,"state");ui(()->states.setValue(ready));
            int[] labels={R.string.plugin_sort_az,R.string.plugin_sort_za,R.string.plugin_sort_enabled,R.string.plugin_sort_updates};
            String[] expected={"alpha,plugin2,plugin10,Zulu","Zulu,plugin10,plugin2,alpha","plugin10,Zulu,alpha,plugin2","plugin2,plugin10,alpha,Zulu"};
            for(String lang:new String[]{"zh","en"}){
                if(!lang.equals(new ConfigStore(page).getUiLanguage()))language(lang);
                ui(()->page.findViewById(R.id.btnInstalled).performClick());
                for(int index=0;index<labels.length;index++){
                    ui(()->page.findViewById(R.id.btnSort).performClick());if(index==0)screenshot(lang+"-sort-menu");choose(page.getString(labels[index]));
                    check(names().equals(expected[index]),"实际列表排序错误："+names());
                    check(new ConfigStore(page).getPluginSort()==PluginSort.Mode.values()[index],"排序没有记住");
                    check(((TextView)page.findViewById(R.id.btnSort)).getText().toString().equals(page.getString(labels[index])),"按钮没有显示当前排序");
                    check(page.findViewById(R.id.btnSort).getHeight()==page.findViewById(R.id.btnPluginUpdates).getHeight(),"操作框高度不一致");
                }
                screenshot(lang+"-management");
                ui(()->((EditText)page.findViewById(R.id.pluginSearch)).setText("plugin"));check(names().equals("plugin2,plugin10"),"搜索与排序未组合");
                ui(()->((EditText)page.findViewById(R.id.pluginSearch)).setText(""));
                ui(()->((CheckBox)page.findViewById(R.id.chkHideBuiltin)).setChecked(true));check(!names().contains("alpha"),"过滤内置插件失效");
                mount();ui(()->page.findViewById(R.id.btnInstalled).performClick());check(names().equals(expected[3]),"重新打开页面丢失排序");
            }
            PluginRepository.State busy=stateConstructor.newInstance(items,true,"");ui(()->states.setValue(busy));
            check(!page.findViewById(R.id.btnPluginUpdates).isEnabled()&&page.findViewById(R.id.btnSort).isEnabled(),"忙碌时更新必须禁用，排序仍可用");
            result.putString("result","PASS");result.putInt("checks",checks);
        }catch(Throwable error){result.putString("failure",android.util.Log.getStackTraceString(error));}
        finally{
            if(page!=null)ui(page::finish);
            ui(()->{var edit=prefs.edit();if(hadSort)edit.putString("plugin_sort_order",previousSort);else edit.remove("plugin_sort_order");if(hadLanguage)edit.putString("ui_language",previousLanguage);else edit.remove("ui_language");edit.commit();LanguageController.apply(getTargetContext());});
            finish(result.containsKey("failure")?1:0,result);
        }
    }
}
