package com.deepseekharness.app.ui;

import android.app.*;
import android.os.*;
import android.content.res.Configuration;
import android.graphics.Bitmap;
import android.view.*;
import android.widget.*;
import com.deepseekharness.app.*;
import com.deepseekharness.app.backup.*;
import com.deepseekharness.app.core.ConfigStore;
import java.io.*;
import java.util.*;

/** 独立非调试安装的真实 Activity、双语/主题/短视口与重建验收，不改设备全局字体或分辨率。 */
public final class NativeDataUiAudit extends Instrumentation {
    private float scale=1;
    private int width=320,height=640,checks;
    private final List<Map<String,Object>> cases=new ArrayList<>();
    private File screenshots;
    @Override public void onCreate(Bundle args){super.onCreate(args);start();}
    @Override public void callActivityOnCreate(Activity activity,Bundle state){
        if(activity instanceof NativeDataActivity || activity instanceof RetainedDataActivity){
            Configuration config=new Configuration(activity.getResources().getConfiguration());config.fontScale=scale;if(width>0){config.screenWidthDp=width;config.screenHeightDp=height;}
            activity.getResources().updateConfiguration(config,activity.getResources().getDisplayMetrics());
        }
        super.callActivityOnCreate(activity,state);
        if((activity instanceof NativeDataActivity || activity instanceof RetainedDataActivity)&&width>0){float density=activity.getResources().getDisplayMetrics().density;activity.getWindow().setGravity(Gravity.TOP|Gravity.LEFT);activity.getWindow().setLayout(Math.round(width*density),Math.round(height*density));}
    }
    private void require(boolean condition,String text){if(!condition)throw new AssertionError(text);}
    private void ui(Runnable work)throws Exception{Throwable[] error={null};runOnMainSync(()->{try{work.run();}catch(Throwable e){error[0]=e;}});if(error[0]!=null)throw new Exception(error[0]);}
    private void inspect(View view,boolean english){
        if(view.getVisibility()!=View.VISIBLE)return;
        if(view instanceof TextView){TextView text=(TextView)view;String value=text.getText().toString();
            if(!value.isEmpty()){
                if(english)require(!value.matches("(?s).*[\\p{IsHan}].*"),"ENGLISH_CONTAINS_CHINESE:"+value);
                android.text.Layout layout=text.getLayout();
                if(layout!=null){require(layout.getHeight()<=text.getHeight()-text.getCompoundPaddingTop()-text.getCompoundPaddingBottom()+2,"TEXT_CLIPPED:"+value);
                    for(int i=0;i<layout.getLineCount();i++)require(layout.getEllipsisCount(i)==0,"TEXT_ELLIPSIZED:"+value);}
                if(text instanceof Button){require((text.getGravity()&Gravity.VERTICAL_GRAVITY_MASK)==Gravity.CENTER_VERTICAL,"BUTTON_ALIGNMENT:"+value);require(!text.getIncludeFontPadding(),"BUTTON_FONT_PADDING:"+value);}
                checks++;
            }
        }
        if(view instanceof ViewGroup){ViewGroup group=(ViewGroup)view;for(int i=0;i<group.getChildCount();i++)inspect(group.getChildAt(i),english);}
    }
    private ScrollView scroll(Activity activity){ViewGroup content=activity.findViewById(android.R.id.content);return (ScrollView)content.getChildAt(0);}
    private <T extends View>T first(View view,Class<T> type){if(type.isInstance(view))return type.cast(view);if(view instanceof ViewGroup){ViewGroup group=(ViewGroup)view;for(int i=0;i<group.getChildCount();i++){T found=first(group.getChildAt(i),type);if(found!=null)return found;}}return null;}
    private void save(Activity activity,String name)throws Exception{
        require(DeviceAuditSupport.foreground(this)==activity,"TEST_UI_NOT_FOREGROUND");
        Bitmap[] captured={null};ui(()->{View view=scroll(activity);captured[0]=Bitmap.createBitmap(view.getWidth(),view.getHeight(),Bitmap.Config.ARGB_8888);android.graphics.Canvas canvas=new android.graphics.Canvas(captured[0]);canvas.translate(-view.getScrollX(),-view.getScrollY());view.draw(canvas);});Bitmap bitmap=captured[0];
        try(OutputStream output=new FileOutputStream(new File(screenshots,name+".png"))){require(bitmap.compress(Bitmap.CompressFormat.PNG,100,output),"SCREENSHOT_WRITE");}finally{bitmap.recycle();}
    }
    @Override public void onStart(){Bundle result=new Bundle();Activity screen=null;ConfigStore store=new ConfigStore(getTargetContext());String oldLanguage=store.getUiLanguage(),oldTheme=store.getUiTheme();
        Configuration original=new Configuration(getTargetContext().getResources().getConfiguration());
        try{
            DeviceAuditSupport.requireIsolated(getTargetContext());DeviceAuditSupport.open(this,DeviceAuditActivity.class);
            screenshots=new File(getTargetContext().getExternalFilesDir(null),"device-ui-"+UUID.randomUUID());require(screenshots.mkdirs(),"SCREENSHOT_DIRECTORY");
            for(String language:new String[]{"zh","en"})for(boolean dark:new boolean[]{false,true})for(boolean compact:new boolean[]{false,true}){
                ui(()->{LanguageController.select(getTargetContext(),language);ThemeController.select(getTargetContext(),dark?"dark":"light");});Thread.sleep(2000);
                scale=compact?1.3f:1f;height=compact?400:640;
                NativeDataActivity active=(NativeDataActivity)DeviceAuditSupport.open(this,NativeDataActivity.class);screen=active;
                String name=language+"-"+(dark?"dark":"light")+"-320x"+height+"-font"+scale;
                waitForIdleSync();Thread.sleep(200);
                require(Math.abs(active.getResources().getConfiguration().fontScale-scale)<.01,"FONT_NOT_APPLIED");
                ui(()->{require(ThemeController.isDark(active)==dark,"THEME_NOT_APPLIED");inspect(scroll(active),language.equals("en"));});
                save(active,name+"-top");
                ui(()->{scroll(active).setSmoothScrollingEnabled(false);scroll(active).fullScroll(View.FOCUS_DOWN);});waitForIdleSync();
                require(scroll(active).getScrollY()>0&&!scroll(active).canScrollVertically(1),"DATA_PAGE_BOTTOM_UNREACHABLE");save(active,name+"-bottom");
                // ViewModel 保留用户选择；重建不得新增或重新启动作业。
                NativeBackupJobs.State before=NativeBackupJobs.get(getTargetContext()).state();
                ui(()->{first(scroll(active),Spinner.class).setSelection(4);first(scroll(active),CheckBox.class).setChecked(true);});waitForIdleSync();
                ui(active::recreate);
                long until=SystemClock.elapsedRealtime()+10000;Activity recreated;
                do{Thread.sleep(100);recreated=DeviceAuditSupport.foreground(this);}while((recreated==null||recreated==active)&&SystemClock.elapsedRealtime()<until);
                require(recreated instanceof NativeDataActivity && recreated!=active,"ACTIVITY_NOT_RECREATED");
                Activity current=recreated;screen=current;waitForIdleSync();
                ui(()->{require(first(scroll(current),Spinner.class).getSelectedItemPosition()==4,"SCOPE_LOST_ON_RECREATION");require(first(scroll(current),CheckBox.class).isChecked(),"KEY_CHOICE_LOST_ON_RECREATION");});
                require(NativeBackupJobs.get(getTargetContext()).state()==before,"RECREATION_STARTED_JOB");
                ui(current::finish);screen=null;
                cases.add(Map.of("name",name,"status","PASS"));Bundle status=new Bundle();status.putString("case",name);status.putString("status","PASS");sendStatus(1,status);
                RetainedDataActivity retained=(RetainedDataActivity)DeviceAuditSupport.open(this,RetainedDataActivity.class);screen=retained;
                long listDeadline=SystemClock.elapsedRealtime()+10000;
                while(first(scroll(retained),CheckBox.class)==null&&SystemClock.elapsedRealtime()<listDeadline)Thread.sleep(100);
                require(first(scroll(retained),CheckBox.class)!=null,"RETAINED_FIXTURE_LIST_MISSING");waitForIdleSync();
                ui(()->inspect(scroll(retained),language.equals("en")));save(retained,name+"-retained-top");
                ui(()->{first(scroll(retained),CheckBox.class).setChecked(true);scroll(retained).fullScroll(View.FOCUS_DOWN);});waitForIdleSync();
                require(!scroll(retained).canScrollVertically(1),"RETAINED_BOTTOM_UNREACHABLE");save(retained,name+"-retained-bottom");
                ui(retained::recreate);long retainedDeadline=SystemClock.elapsedRealtime()+10000;Activity rebuilt;
                do{Thread.sleep(100);rebuilt=DeviceAuditSupport.foreground(this);}while((rebuilt==null||rebuilt==retained)&&SystemClock.elapsedRealtime()<retainedDeadline);
                require(rebuilt instanceof RetainedDataActivity&&rebuilt!=retained,"RETAINED_NOT_RECREATED");screen=rebuilt;Activity rebuiltRetained=rebuilt;waitForIdleSync();
                ui(()->require(first(scroll(rebuiltRetained),CheckBox.class).isChecked(),"RETAINED_SELECTION_LOST"));
                require(NativeBackupJobs.get(getTargetContext()).state()==before,"RETAINED_RECREATION_STARTED_JOB");
                ui(rebuiltRetained::finish);screen=null;cases.add(Map.of("name",name+"-retained","status","PASS"));
            }
            width=-1;height=-1;scale=1;
            NativeDataActivity rotation=(NativeDataActivity)DeviceAuditSupport.open(this,NativeDataActivity.class);screen=rotation;
            ui(()->first(scroll(rotation),Spinner.class).setSelection(4));waitForIdleSync();
            NativeBackupJobs.State beforeRotation=NativeBackupJobs.get(getTargetContext()).state();
            for(int orientation:new int[]{android.content.pm.ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE,android.content.pm.ActivityInfo.SCREEN_ORIENTATION_PORTRAIT}){
                Activity prior=screen;ui(()->prior.setRequestedOrientation(orientation));long deadline=SystemClock.elapsedRealtime()+15000;Activity current;
                do{Thread.sleep(100);current=DeviceAuditSupport.foreground(this);}while((!(current instanceof NativeDataActivity)||current.getResources().getConfiguration().orientation!=(orientation==0?Configuration.ORIENTATION_LANDSCAPE:Configuration.ORIENTATION_PORTRAIT))&&SystemClock.elapsedRealtime()<deadline);
                require(current instanceof NativeDataActivity,"ROTATION_NOT_RECREATED");screen=current;Activity currentScreen=current;waitForIdleSync();
                require(current.getResources().getConfiguration().orientation==(orientation==0?Configuration.ORIENTATION_LANDSCAPE:Configuration.ORIENTATION_PORTRAIT),"ORIENTATION_NOT_APPLIED");
                ui(()->{require(first(scroll(currentScreen),Spinner.class).getSelectedItemPosition()==4,"ROTATION_LOST_SCOPE");inspect(scroll(currentScreen),true);});
                require(beforeRotation==NativeBackupJobs.get(getTargetContext()).state(),"ROTATION_STARTED_JOB");save(currentScreen,"physical-orientation-"+orientation);
                cases.add(Map.of("name","physical-orientation-"+orientation,"status","PASS"));
            }
        }catch(Throwable error){result.putString("failure",com.deepseekharness.app.util.SensitiveData.redact(android.util.Log.getStackTraceString(error)));}
        finally{
            if(screen!=null){Activity close=screen;runOnMainSync(close::finish);}
            try{ui(()->{getTargetContext().getResources().updateConfiguration(original,getTargetContext().getResources().getDisplayMetrics());LanguageController.select(getTargetContext(),oldLanguage);ThemeController.select(getTargetContext(),oldTheme);});}catch(Exception error){result.putString("cleanupFailure",error.toString());}
            Map<String,Object> report=new LinkedHashMap<>();report.put("status",result.containsKey("failure")||result.containsKey("cleanupFailure")?"FAIL":"PASS");report.put("tests",cases);report.put("textChecks",checks);report.put("nonDebuggable",true);report.put("flavor",BuildConfig.FLAVOR);
            report.put("captureType","Android View.draw; constrained viewport plus physical orientation");if(screenshots!=null)report.put("screenshots",screenshots.getAbsolutePath());
            try{result.putString("report",new String(BackupJson.write(report,65536),java.nio.charset.StandardCharsets.UTF_8));}catch(Exception error){result.putString("failure",error.toString());}
            finish("PASS".equals(report.get("status"))?0:1,result);
        }
    }
}
