package com.deepseekharness.app.ui;

import android.app.Activity;
import android.app.Instrumentation;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Rect;
import android.graphics.drawable.Drawable;
import android.os.Bundle;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.TextView;
import androidx.appcompat.app.AppCompatDelegate;
import androidx.core.graphics.ColorUtils;
import com.deepseekharness.app.R;
import com.deepseekharness.app.core.ConfigStore;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.function.BooleanSupplier;

/** 仅调试包：原生布局尺寸/截图检查与安全导航回归，不进入正式 APK。 */
public final class LayoutAuditInstrumentation extends Instrumentation {
    static int width=360,height=720;
    static float scale=1;
    private Bundle args;
    private int checks, centered, colors;
    private double minimum=100;
    private final List<String> failures=new ArrayList<>();
    private final java.util.IdentityHashMap<View,Integer> painted=new java.util.IdentityHashMap<>();
    private File folder;
    @Override public void onCreate(Bundle value) { super.onCreate(value);args=value;start(); }
    @Override public void callActivityOnCreate(Activity activity, Bundle value) {
        super.callActivityOnCreate(activity,value);
        activity.getWindow().addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
                | android.view.WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON
                | android.view.WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED);
    }
    private void require(boolean value,String reason) { if(!value)throw new AssertionError(reason); }
    private void until(BooleanSupplier ready,String reason) throws Exception {
        long end=System.currentTimeMillis()+30000;
        while(!ready.getAsBoolean() && System.currentTimeMillis()<end)Thread.sleep(50);
        require(ready.getAsBoolean(),reason);
    }
    private void shell(String command) throws Exception {
        try(android.os.ParcelFileDescriptor p=getUiAutomation().executeShellCommand(command);
            InputStream input=new android.os.ParcelFileDescriptor.AutoCloseInputStream(p)){while(input.read()!=-1){} }
    }
    private void ui(Runnable work) throws Exception {
        Throwable[] error={null};runOnMainSync(()->{try{work.run();}catch(Throwable e){error[0]=e;}});
        if(error[0]!=null)throw new Exception("界面检查失败",error[0]);
    }
    private String id(View v) {try{return v.getResources().getResourceEntryName(v.getId());}catch(Exception e){return v.getClass().getSimpleName();} }
    private int background(View v,int base) {
        Integer known=painted.get(v);if(known!=null)return known;
        if(v.getParent() instanceof View)base=background((View)v.getParent(),base);
        Drawable d=v.getBackground();if(d==null){painted.put(v,base);return base;}
        Drawable copy=d.getConstantState()==null?d:d.getConstantState().newDrawable(v.getResources()).mutate();
        Rect previous=new Rect(copy.getBounds());Bitmap b=Bitmap.createBitmap(40,40,Bitmap.Config.ARGB_8888);
        copy.setState(v.getDrawableState());copy.setBounds(0,0,40,40);copy.draw(new Canvas(b));
        int color=b.getPixel(20,20);b.recycle();copy.setBounds(previous);
        int result=ColorUtils.compositeColors(color,base);painted.put(v,result);return result;
    }
    private void audit(View v,String name) {
        if(v.getVisibility()!=View.VISIBLE)return;
        float density=v.getResources().getDisplayMetrics().density;
        if(v instanceof TextView) {
            if("style".equals(args.getString("mode"))) alignment((TextView)v,name);
            TextView t=(TextView)v;
            if(t.getText().length()>0 || t.getHint()!=null && t.getHint().length()>0) {
                int bg=background(v,v.getContext().getColor(R.color.surface));
                int fg=t.getText().length()>0?t.getCurrentTextColor():t.getCurrentHintTextColor();
                fg=ColorUtils.compositeColors(ColorUtils.setAlphaComponent(fg,Math.round(Color.alpha(fg)*v.getAlpha())),bg);
                double contrast=ColorUtils.calculateContrast(fg,bg);minimum=Math.min(minimum,contrast);checks++;
                if(contrast<4.5)failures.add(name+" / "+id(v)+" 对比度 "+contrast);
                android.text.Layout lines=t.getLayout();
                if(lines!=null && !(t instanceof EditText) && lines.getHeight()>t.getHeight()-t.getCompoundPaddingTop()-t.getCompoundPaddingBottom()+2)
                    failures.add(name+" / "+id(v)+" 文字高度裁切 "+lines.getHeight()+"/"+t.getHeight());
                if(lines!=null && t.getEllipsize()==null && !(t instanceof EditText))
                    for(int i=0;i<lines.getLineCount();i++)if(lines.getLineMax(i)>t.getWidth()-t.getCompoundPaddingLeft()-t.getCompoundPaddingRight()+2)
                        failures.add(name+" / "+id(v)+" 文字宽度溢出");
            }
        }
        if(v.isClickable() && v.isEnabled() && v.getWidth()>0 && v.getHeight()>0
                && !(v instanceof TextView && ((TextView)v).isTextSelectable() && !(v instanceof EditText))
                && !(v instanceof com.termux.view.TerminalView) && !(v instanceof android.webkit.WebView)) {
            if(v.getWidth()/density<43.5f || v.getHeight()/density<43.5f)
                failures.add(name+" / "+id(v)+" 触摸区域过小 "+Math.round(v.getWidth()/density)+"x"+Math.round(v.getHeight()/density));
        }
        if(v instanceof ViewGroup) {
            ViewGroup group=(ViewGroup)v;
            for(int i=0;i<group.getChildCount();i++)audit(group.getChildAt(i),name);
            // 同一行的操作不应相互覆盖；容器与其内部控件不在此比较。
            for(int i=0;i<group.getChildCount();i++)for(int j=i+1;j<group.getChildCount();j++) {
                View a=group.getChildAt(i),b=group.getChildAt(j);
                if(a.getVisibility()!=View.VISIBLE || b.getVisibility()!=View.VISIBLE || !a.isClickable() || !b.isClickable())continue;
                Rect ra=new Rect(a.getLeft(),a.getTop(),a.getRight(),a.getBottom()),rb=new Rect(b.getLeft(),b.getTop(),b.getRight(),b.getBottom());
                if(Rect.intersects(ra,rb))failures.add(name+" / "+id(a)+" 与 "+id(b)+" 重叠");
            }
        }
    }
    private boolean centeredAction(TextView view) {
        if(view instanceof android.widget.CompoundButton || view instanceof EditText)return false;
        if(view instanceof android.widget.Button)return true;
        int id=view.getId();
        return id==R.id.settings_language || id==R.id.config_overlay_style || id==R.id.config_workspace_entry
                || id==R.id.environment_recovery_banner || id==R.id.pty_title || id==R.id.btnMarket || id==R.id.btnInstalled
                || id==R.id.btnRefresh || id==R.id.btnPluginPaste || id==R.id.btnPluginInstall || id==R.id.btnImport
                || id==R.id.btnExport || id==R.id.pluginActions || id==R.id.pty_font_dec || id==R.id.pty_font_inc || id==R.id.pty_simple;
    }
    private void alignment(TextView view,String scene) {
        android.text.Layout layout=view.getLayout();
        if(!centeredAction(view)||layout==null||view.getText().length()==0||view.getWidth()==0)return;
        centered++;
        if(view.getText().toString().matches("[A-Za-z]+") && layout.getLineCount()>1)
            failures.add(scene+" / "+id(view)+" 按钮单词被拆行："+view.getText());
        float tolerance=1.5f*view.getResources().getDisplayMetrics().density;
        float expectedY=view.getHeight()/2f;
        float actualY=view.getTotalPaddingTop()+layout.getHeight()/2f;
        if(Math.abs(expectedY-actualY)>tolerance)failures.add(scene+" / "+id(view)+" 文字上下不居中 "+(actualY-expectedY));
        float left=Float.MAX_VALUE,right=-Float.MAX_VALUE;
        for(int i=0;i<layout.getLineCount();i++){left=Math.min(left,layout.getLineLeft(i));right=Math.max(right,layout.getLineRight(i));}
        float expectedX=(view.getCompoundPaddingLeft()+view.getWidth()-view.getCompoundPaddingRight())/2f;
        float actualX=view.getCompoundPaddingLeft()+(left+right)/2f-view.getScrollX();
        if(Math.abs(expectedX-actualX)>tolerance)failures.add(scene+" / "+id(view)+" 文字左右不居中 "+(actualX-expectedX));
    }
    private Bitmap paint(android.content.Context context,int resource,int[] state) {
        Drawable drawable=context.getDrawable(resource).mutate();drawable.setState(state);drawable.jumpToCurrentState();
        Bitmap bitmap=Bitmap.createBitmap(160,80,Bitmap.Config.ARGB_8888);drawable.setBounds(0,0,160,80);drawable.draw(new Canvas(bitmap));return bitmap;
    }
    private void palette(android.content.Context context) {
        for(int[] state:new int[][]{{android.R.attr.state_enabled},{-android.R.attr.state_enabled}}) {
            for(int[] pair:new int[][]{{R.drawable.bg_card,R.drawable.bg_polished_card},{R.drawable.bg_btn_primary,R.drawable.bg_polished_primary}}) {
                Bitmap expected=paint(context,pair[0],state),actual=paint(context,pair[1],state);
                try {require(expected.sameAs(actual),"同类框的背景像素不一致");colors++;}finally{expected.recycle();actual.recycle();}
            }
            int expected=androidx.core.content.ContextCompat.getColorStateList(context,R.color.button_primary_text).getColorForState(state,0);
            int actual=androidx.core.content.ContextCompat.getColorStateList(context,R.color.polished_primary_text).getColorForState(state,0);
            require(expected==actual,"同类主按钮的文字颜色不一致");colors++;
        }
        Bitmap card=paint(context,R.drawable.bg_card,new int[]{android.R.attr.state_enabled});
        try { for(int x:new int[]{40,80,120})for(int y:new int[]{20,40,60}){
            require(card.getPixel(x,y)==context.getColor(R.color.card),"卡片底色存在渐变色差");colors++;
        }}finally{card.recycle();}
    }
    private void save(View root,String name) throws Exception {
        ui(()->{
            Bitmap image=Bitmap.createBitmap(root.getWidth(),root.getHeight(),Bitmap.Config.ARGB_8888);
            Canvas canvas=new Canvas(image);canvas.translate(-root.getScrollX(),-root.getScrollY());root.draw(canvas);
            try(FileOutputStream out=new FileOutputStream(new File(folder,name+".png"))){image.compress(Bitmap.CompressFormat.PNG,100,out);}
            catch(Exception e){throw new RuntimeException(e);}finally{image.recycle();}
        });
    }
    private TextView findText(View view, String label) {
        if (view instanceof TextView && com.deepseekharness.app.util.UiText.text(label).contentEquals(((TextView)view).getText())) return (TextView)view;
        if (view instanceof ViewGroup) for (int i=0;i<((ViewGroup)view).getChildCount();i++) {
            TextView match=findText(((ViewGroup)view).getChildAt(i),label);if(match!=null)return match;
        }
        return null;
    }
    private void overlay(LayoutPreviewActivity screen, String name) throws Exception {
        android.content.Context context=new android.content.ContextWrapper(screen) {
            @Override public android.content.SharedPreferences getSharedPreferences(String key,int mode) {
                return screen.getSharedPreferences("overlay_layout_fixture_"+key,mode);
            }
        };
        android.content.SharedPreferences prefs=context.getSharedPreferences(com.deepseekharness.app.util.Constants.PREFS,0);
        prefs.edit().clear().commit();
        java.util.Map<String,?> before=prefs.getAll();
        androidx.appcompat.app.AlertDialog[] dialog={null};
        try {
            ui(()->dialog[0]=OverlayStyleDialog.show(context));waitForIdleSync();Thread.sleep(120);
            View root=dialog[0].getWindow().getDecorView();
            ui(()->{painted.clear();audit(root,name+"-overlay");});
            require(root.getHeight()/root.getResources().getDisplayMetrics().density<=height,"悬浮条弹窗超出高度");
            save(root,name+"-overlay");
            ui(()->findText(root,"预览").performClick());waitForIdleSync();
            require(prefs.getAll().equals(before),"预览不得保存设置");
            ui(()->findText(root,"取消").performClick());
            require(prefs.getAll().equals(before),"取消不得保存设置");
            ui(()->dialog[0]=OverlayStyleDialog.show(context));waitForIdleSync();
            ui(()->findText(dialog[0].getWindow().getDecorView(),"保存").performClick());
            require(prefs.contains(com.deepseekharness.app.OverlayController.K_ALPHA),"保存必须写入原有偏好键");
        } finally { if(dialog[0]!=null)ui(()->dialog[0].dismiss());prefs.edit().clear().commit(); }
    }
    private void navigation() throws Exception {
        shell("am start -W -n com.dsh.client/com.deepseekharness.app.ui.MainActivity");
        until(()->MainActivity.current!=null && MainActivity.current.getLifecycle().getCurrentState().isAtLeast(androidx.lifecycle.Lifecycle.State.RESUMED),"主页面未就绪");
        ConfigStore store=new ConfigStore(getTargetContext());String port=store.getPort();
        ui(()->((com.google.android.material.bottomnavigation.BottomNavigationView)MainActivity.current.findViewById(R.id.bottom_nav)).setSelectedItemId(R.id.nav_settings));
        until(()->MainActivity.current.findViewById(R.id.settings_tabs)!=null,"设置页未打开");
        ui(()->((ViewGroup)MainActivity.current.findViewById(R.id.settings_tabs)).getChildAt(1).performClick());
        until(()->MainActivity.current.findViewById(R.id.config_save)!=null,"配置页未打开");
        require(((TextView)MainActivity.current.findViewById(R.id.app_title)).getText().toString().equals(com.deepseekharness.app.util.UiText.text("配置")),"子页标题错误");
        require(MainActivity.current.findViewById(R.id.sub_back).getVisibility()==View.VISIBLE,"返回入口缺失");
        ui(()->((EditText)MainActivity.current.findViewById(R.id.config_port)).setText("39081"));
        MainActivity before=MainActivity.current;
        ui(()->before.findViewById(R.id.btn_theme).performClick());
        until(()->MainActivity.current!=null && MainActivity.current!=before && MainActivity.current.findViewById(R.id.config_save)!=null,"主题切换丢失子页");
        require(((EditText)MainActivity.current.findViewById(R.id.config_port)).getText().toString().equals("39081"),"主题切换丢失未保存输入");
        ui(()->MainActivity.current.findViewById(R.id.sub_back).performClick());
        until(()->MainActivity.current.findViewById(R.id.settings_tabs)!=null,"返回设置失败");
        require(MainActivity.current.findViewById(R.id.sub_back).getVisibility()==View.GONE,"根页面仍显示子页返回");
        require(store.getPort().equals(port),"未点击保存却修改了真实配置");
    }
    private void dialogs(LayoutPreviewActivity screen,String name) throws Exception {
        for(int kind=0;kind<4;kind++) {
            final int type=kind;
            androidx.appcompat.app.AlertDialog[] shown={null};
            try {
                ui(()->{
                    if(type==3){shown[0]=AboutDialog.show(screen);return;}
                    DshaDialogBuilder builder=new DshaDialogBuilder(screen);
                    String cancel=com.deepseekharness.app.util.UiText.text("取消");
                    if(type==0)builder.setTitle(com.deepseekharness.app.util.UiText.choose("界面语言","Interface language"))
                        .setSingleChoiceItems(new String[]{com.deepseekharness.app.util.UiText.choose("简体中文","Simplified Chinese"),"English"},0,(d,w)->{});
                    else if(type==1)builder.setTitle(com.deepseekharness.app.util.UiText.text("持续允许读取短信？"))
                        .setMessage(com.deepseekharness.app.util.UiText.text("当前 DSHA 环境内的助手和插件将能通过已授权的 root 或 ADB 查询短信，包括正文、号码和可能存在的验证码。\n\n后续短信查询不再逐条询问。可随时回到本页关闭，恢复逐次确认。"))
                        .setPositiveButton(com.deepseekharness.app.util.UiText.text("允许短信读取"),(d,w)->{});
                    else builder.setTitle(com.deepseekharness.app.util.UiText.text("导出插件包"))
                        .setMultiChoiceItems(new String[]{"example-plugin-one","example-plugin-two"},new boolean[]{false,true},(d,w,c)->{})
                        .setPositiveButton(com.deepseekharness.app.util.UiText.text("导出"),(d,w)->{});
                    shown[0]=builder.setNegativeButton(cancel,null).show();
                });
                waitForIdleSync();Thread.sleep(220);
                View root=shown[0].getWindow().getDecorView();
                ui(()->{painted.clear();audit(root,name+"-dialog"+type);});
                save(root,name+"-dialog"+type);
            } finally { if(shown[0]!=null)ui(()->shown[0].dismiss()); }
        }
    }
    @Override public void onStart() {
        Bundle result=new Bundle();ConfigStore store=new ConfigStore(getTargetContext());String original=store.getUiTheme();
        String originalLanguage=store.getUiLanguage();boolean hadLanguage=getTargetContext().getSharedPreferences(com.deepseekharness.app.util.Constants.PREFS,0).contains("ui_language");
        folder=new File(getTargetContext().getCacheDir(),"layout-audit");folder.mkdirs();
        Activity active=null;
        try {
            boolean devices="devices".equals(args.getString("mode"));
            shell("am start -W -n com.dsh.client/com.deepseekharness.app.ui.MainActivity"+(devices?" --ez limited_entry true":""));
            if(!devices) {
                long readyDeadline=System.currentTimeMillis()+120000;
                com.deepseekharness.app.core.HarnessController controller=com.deepseekharness.app.core.HarnessController.get(getTargetContext());
                while((!controller.isEnvironmentReady() || com.deepseekharness.app.BackupManager.isEnvironmentTaskBusy()) && System.currentTimeMillis()<readyDeadline)Thread.sleep(150);
                require(controller.isEnvironmentReady() && !com.deepseekharness.app.BackupManager.isEnvironmentTaskBusy(),"环境更新未完成，布局验收尚未开始");
            }
            if(args.containsKey("language"))ui(()->LanguageController.select(getTargetContext(),args.getString("language")));
            Thread.sleep(700);waitForIdleSync();
            int[][] profiles={{320,640},{360,720},{392,760},{320,640},{360,720},{320,400}};
            String[] scenes={"launch_idle","launch_error","fragment_settings","fragment_config","fragment_workspace","fragment_install","plugins_market","plugins_installed","update_idle","update_busy","update_ready","update_error","activity_diagnostics","activity_plugin_install","fragment_pty_terminal","fragment_terminal"};
            if(devices)scenes=new String[]{"activity_adb_pair","fragment_install","fragment_device_grants","fragment_config"};
            boolean style="style".equals(args.getString("mode"));
            if(style)scenes=new String[]{"launch_idle","launch_error","fragment_settings","fragment_config","fragment_workspace","fragment_install","plugins_market","plugins_installed","update_idle","update_busy","update_ready","update_error","activity_diagnostics","activity_plugin_install","fragment_pty_terminal","fragment_terminal","fragment_device_grants","activity_adb_pair","activity_extract","activity_welcome","welcome_page1","welcome_page2","welcome_page3"};
            boolean recovery="recovery".equals(args.getString("mode"));
            if(recovery)scenes=new String[]{"recovery_empty","recovery_records"};
            boolean quick="quick".equals(args.getString("mode")) || style || recovery;
            boolean polish="polish".equals(args.getString("mode"));
            boolean interactions="interactions".equals(args.getString("mode"));
            for(boolean dark:new boolean[]{false,true}) {
                ui(()->AppCompatDelegate.setDefaultNightMode(dark?AppCompatDelegate.MODE_NIGHT_YES:AppCompatDelegate.MODE_NIGHT_NO));
                Thread.sleep(500);waitForIdleSync();
                for(int n=0;n<profiles.length;n++) {
                    if(quick && n!=0 && n!=3 && n!=5)continue;
                    if(interactions && n!=0 && n!=2 && n!=3)continue;
                    width=profiles[n][0];height=profiles[n][1];scale=n>=3?1.3f:1f;
                    LayoutPreviewActivity screen=(LayoutPreviewActivity)startActivitySync(new Intent(getTargetContext(),LayoutPreviewActivity.class).setAction("layout."+System.nanoTime()).putExtra("scene",scenes[0]).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
                    active=screen;
                    if(style)ui(()->palette(screen));
                    for(String scene:scenes) {
                        if(polish && !scene.equals("fragment_settings") && !scene.startsWith("plugins") && !scene.startsWith("update"))continue;
                        if(interactions && !scene.equals("fragment_settings") && !scene.startsWith("plugins") && !scene.startsWith("launch") && !scene.equals("activity_diagnostics"))continue;
                        ui(()->screen.showScene(scene));
                        waitForIdleSync();
                        until(()->screen.canvas.getWidth()>0 && screen.canvas.getHeight()>0,"布局未完成："+scene);
                        require(Math.abs(screen.getResources().getConfiguration().fontScale-scale)<0.01,"字体倍率未应用");
                        String name=(dark?"dark":"light")+"-"+width+"x"+height+"-font"+scale+"-"+scene;
                        ui(()->{painted.clear();audit(screen.canvas,name);});
                        if(style && scene.equals("fragment_config"))require(findText(screen.canvas,"备份")==null,"配置页仍有多余的备份框");
                        if(scene.equals("fragment_config"))require(screen.canvas.findViewById(R.id.config_save).getBottom()<=screen.canvas.getHeight(),"保存按钮超出视口");
                        if(scene.startsWith("plugins")) require(((TextView) screen.canvas.findViewById(R.id.btnRefresh)).getLineCount()==1,"检测插件按钮不得竖排换行");
                        if(style && scene.equals("plugins_installed")) {
                            for(int label:new int[]{R.string.plugin_sort_az,R.string.plugin_sort_za,R.string.plugin_sort_enabled,R.string.plugin_sort_updates}) {
                                ui(()->((TextView)screen.canvas.findViewById(R.id.btnSort)).setText(label));waitForIdleSync();
                                ui(()->{painted.clear();audit(screen.canvas.findViewById(R.id.btnSort),name+"-sort");});
                                require(screen.canvas.findViewById(R.id.btnSort).getHeight()==screen.canvas.findViewById(R.id.btnPluginUpdates).getHeight(),"排序与更新按钮高度不一致");
                            }
                            ui(()->((TextView)screen.canvas.findViewById(R.id.btnSort)).setText(R.string.plugin_sort_az));waitForIdleSync();
                        }
                        if(scene.equals("update_idle"))require(screen.canvas.findViewById(R.id.update_install).getVisibility()==View.GONE,"无更新时仍展示安装操作");
                        if(scene.equals("update_busy"))require(screen.canvas.findViewById(R.id.update_cancel).getVisibility()==View.VISIBLE && !screen.canvas.findViewById(R.id.update_download).isEnabled(),"下载状态操作错误");
                        if(n==0 || n==1 || n==3 || n==5)save(screen.canvas,name);
                        if(style && n==5 && scene.equals("welcome_page1")) {
                            android.widget.ScrollView scroll=(android.widget.ScrollView)screen.canvas;
                            require(scroll.canScrollVertically(1),"短屏欢迎页不能滚动查看完整文案");
                            ui(()->{scroll.setSmoothScrollingEnabled(false);scroll.fullScroll(View.FOCUS_DOWN);});waitForIdleSync();
                            require(scroll.getScrollY()>0,"欢迎页没有实际滚动到底部");save(screen.canvas,name+"-bottom");
                        }
                    }
                    if(devices || style)overlay(screen,(dark?"dark":"light")+"-"+width+"x"+height+"-font"+scale);
                    if(style)dialogs(screen,(dark?"dark":"light")+"-"+width+"x"+height+"-font"+scale);
                    ui(screen::finish);active=null;
                    Bundle phase=new Bundle();phase.putString("phase",(dark?"黑夜":"白天")+" "+width+"x"+height+" 字体 "+scale+" 已检查，待修正 "+failures.size());sendStatus(1,phase);
                    try(FileOutputStream out=new FileOutputStream(new File(folder,"partial.txt"))){out.write(String.join("\n",failures).getBytes(java.nio.charset.StandardCharsets.UTF_8));}
                }
            }
            if(!devices && !"recovery".equals(args.getString("mode")))navigation();
            require(failures.isEmpty(),String.join("\n",failures));
            result.putString("result","PASS");result.putString("coverage",checks+" 文字状态，"+centered+" 居中检查，"+colors+" 颜色像素检查，最低对比度 "+minimum
                    +("recovery".equals(args.getString("mode"))?"；恢复页空记录与有记录状态、短屏/大字体/日夜排版通过":devices?"；设备页面布局/触摸/重叠、悬浮条预览和取消不保存、独立偏好保存通过":"；布局/触摸/重叠检查及真实子页返回、日夜切换、未保存输入保留通过"));
        }catch(Throwable error){result.putString("result","FAIL: "+android.util.Log.getStackTraceString(error));}
        finally {
            if(active!=null){Activity done=active;try{ui(done::finish);}catch(Exception ignored){}}
            try{ui(()->{store.setUiTheme(original);ThemeController.apply(getTargetContext());});}catch(Exception ignored){}
            try{ui(()->{var preferences=getTargetContext().getSharedPreferences(com.deepseekharness.app.util.Constants.PREFS,0);if(hadLanguage)preferences.edit().putString("ui_language",originalLanguage).commit();else preferences.edit().remove("ui_language").commit();LanguageController.apply(getTargetContext());});}catch(Exception ignored){}
            try(FileOutputStream out=new FileOutputStream(new File(folder,"report.txt"))){out.write(("checks="+checks+" centered="+centered+" colors="+colors+" minimum="+minimum+"\n"+String.join("\n",failures)+"\n"+result).getBytes(java.nio.charset.StandardCharsets.UTF_8));}catch(Exception ignored){}
        }
        finish("PASS".equals(result.getString("result"))?Activity.RESULT_OK:Activity.RESULT_CANCELED,result);
    }
}
