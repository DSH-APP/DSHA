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
    private int checks;
    private double minimum=100;
    private final List<String> failures=new ArrayList<>();
    private final java.util.IdentityHashMap<View,Integer> painted=new java.util.IdentityHashMap<>();
    private File folder;
    @Override public void onCreate(Bundle value) { super.onCreate(value);args=value;start(); }
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
    private void save(View root,String name) throws Exception {
        ui(()->{
            Bitmap image=Bitmap.createBitmap(root.getWidth(),root.getHeight(),Bitmap.Config.ARGB_8888);
            root.draw(new Canvas(image));
            try(FileOutputStream out=new FileOutputStream(new File(folder,name+".png"))){image.compress(Bitmap.CompressFormat.PNG,100,out);}
            catch(Exception e){throw new RuntimeException(e);}finally{image.recycle();}
        });
    }
    private void navigation() throws Exception {
        shell("am start -W -n com.dsh.client/com.deepseekharness.app.ui.MainActivity");
        until(()->MainActivity.current!=null && MainActivity.current.getLifecycle().getCurrentState().isAtLeast(androidx.lifecycle.Lifecycle.State.RESUMED),"主页面未就绪");
        ConfigStore store=new ConfigStore(getTargetContext());String port=store.getPort();
        ui(()->((com.google.android.material.bottomnavigation.BottomNavigationView)MainActivity.current.findViewById(R.id.bottom_nav)).setSelectedItemId(R.id.nav_settings));
        until(()->MainActivity.current.findViewById(R.id.settings_tabs)!=null,"设置页未打开");
        ui(()->((ViewGroup)MainActivity.current.findViewById(R.id.settings_tabs)).getChildAt(2).performClick());
        until(()->MainActivity.current.findViewById(R.id.config_save)!=null,"配置页未打开");
        require(((TextView)MainActivity.current.findViewById(R.id.app_title)).getText().toString().equals("配置"),"子页标题错误");
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
    @Override public void onStart() {
        Bundle result=new Bundle();ConfigStore store=new ConfigStore(getTargetContext());String original=store.getUiTheme();
        folder=new File(getTargetContext().getCacheDir(),"layout-audit");folder.mkdirs();
        Activity active=null;
        try {
            shell("am start -W -n com.dsh.client/com.deepseekharness.app.ui.MainActivity");
            int[][] profiles={{320,640},{360,720},{392,760},{320,640},{360,720},{320,400}};
            String[] scenes={"launch_idle","launch_error","fragment_settings","fragment_config","fragment_workspace","fragment_install","plugins_market","plugins_installed","update_idle","update_busy","update_ready","update_error","activity_diagnostics","activity_plugin_install","fragment_pty_terminal","fragment_terminal"};
            boolean quick="quick".equals(args.getString("mode"));
            boolean polish="polish".equals(args.getString("mode"));
            for(boolean dark:new boolean[]{false,true}) {
                ui(()->AppCompatDelegate.setDefaultNightMode(dark?AppCompatDelegate.MODE_NIGHT_YES:AppCompatDelegate.MODE_NIGHT_NO));
                for(int n=0;n<profiles.length;n++) {
                    if(quick && n!=0 && n!=3 && n!=5)continue;
                    width=profiles[n][0];height=profiles[n][1];scale=n>=3?1.3f:1f;
                    LayoutPreviewActivity screen=(LayoutPreviewActivity)startActivitySync(new Intent(getTargetContext(),LayoutPreviewActivity.class).setAction("layout."+System.nanoTime()).putExtra("scene",scenes[0]).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
                    active=screen;
                    for(String scene:scenes) {
                        if(polish && !scene.equals("fragment_settings") && !scene.startsWith("plugins") && !scene.startsWith("update"))continue;
                        ui(()->screen.showScene(scene));
                        until(()->screen.canvas.getWidth()>0 && screen.canvas.getHeight()>0,"布局未完成");
                        require(Math.abs(screen.getResources().getConfiguration().fontScale-scale)<0.01,"字体倍率未应用");
                        String name=(dark?"dark":"light")+"-"+width+"x"+height+"-font"+scale+"-"+scene;
                        ui(()->{painted.clear();audit(screen.canvas,name);});
                        if(scene.equals("fragment_config"))require(screen.canvas.findViewById(R.id.config_save).getBottom()<=screen.canvas.getHeight(),"保存按钮超出视口");
                        if(scene.equals("update_idle"))require(screen.canvas.findViewById(R.id.update_install).getVisibility()==View.GONE,"无更新时仍展示安装操作");
                        if(scene.equals("update_busy"))require(screen.canvas.findViewById(R.id.update_cancel).getVisibility()==View.VISIBLE && !screen.canvas.findViewById(R.id.update_download).isEnabled(),"下载状态操作错误");
                        if(n==0 || n==1 || n==3 || n==5)save(screen.canvas,name);
                    }
                    ui(screen::finish);active=null;
                    Bundle phase=new Bundle();phase.putString("phase",(dark?"黑夜":"白天")+" "+width+"x"+height+" 字体 "+scale+" 已检查，待修正 "+failures.size());sendStatus(1,phase);
                    try(FileOutputStream out=new FileOutputStream(new File(folder,"partial.txt"))){out.write(String.join("\n",failures).getBytes(java.nio.charset.StandardCharsets.UTF_8));}
                }
            }
            navigation();
            require(failures.isEmpty(),String.join("\n",failures));
            result.putString("result","PASS");result.putString("coverage",checks+" 文字状态，最低对比度 "+minimum+"；布局/触摸/重叠检查及真实子页返回、日夜切换、未保存输入保留通过");
        }catch(Throwable error){result.putString("result","FAIL: "+error);}
        finally {
            if(active!=null){Activity done=active;try{ui(done::finish);}catch(Exception ignored){}}
            try{ui(()->{store.setUiTheme(original);ThemeController.apply(getTargetContext());});}catch(Exception ignored){}
            try(FileOutputStream out=new FileOutputStream(new File(folder,"report.txt"))){out.write(("checks="+checks+" minimum="+minimum+"\n"+String.join("\n",failures)+"\n"+result).getBytes(java.nio.charset.StandardCharsets.UTF_8));}catch(Exception ignored){}
        }
        finish("PASS".equals(result.getString("result"))?Activity.RESULT_OK:Activity.RESULT_CANCELED,result);
    }
}
