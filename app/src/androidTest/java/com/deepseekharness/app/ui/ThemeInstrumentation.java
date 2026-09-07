package com.deepseekharness.app.ui;

import android.app.Activity;
import android.app.Instrumentation;
import android.content.Context;
import android.content.Intent;
import android.content.res.Configuration;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.drawable.Drawable;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.TextView;
import androidx.core.graphics.ColorUtils;
import com.deepseekharness.app.R;
import com.deepseekharness.app.core.ConfigStore;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.function.BooleanSupplier;

/** 检查真实控件的背景/文字状态，复现默认按钮与大字体的可读性问题。 */
public final class ThemeInstrumentation extends Instrumentation {
    private Bundle args;
    private Activity page;
    private int checked;
    private double minimum = 100;
    private final List<String> failures = new ArrayList<>();
    @Override public void onCreate(Bundle args) { super.onCreate(args); this.args = args; start(); }
    private void require(boolean ok, String message) { if (!ok) throw new AssertionError(message); }
    private void until(BooleanSupplier ready, String reason) throws Exception {
        long deadline = System.currentTimeMillis()+30000;
        while (!ready.getAsBoolean() && System.currentTimeMillis()<deadline) Thread.sleep(75);
        require(ready.getAsBoolean(), reason);
    }
    private void shell(String command) throws Exception {
        try (android.os.ParcelFileDescriptor p = getUiAutomation().executeShellCommand(command);
             InputStream input = new android.os.ParcelFileDescriptor.AutoCloseInputStream(p)) { while (input.read() != -1) { } }
    }
    private void phase(String message) { Bundle b = new Bundle(); b.putString("phase",message); sendStatus(1,b); }
    private void screenshot(String name) throws Exception {
        getUiAutomation().waitForIdle(200,3000);
        Bitmap bitmap = getUiAutomation().takeScreenshot();
        require(bitmap != null,"截图失败");
        File folder = new File(getTargetContext().getCacheDir(),"theme-verification"); folder.mkdirs();
        try (FileOutputStream output = new FileOutputStream(new File(folder,name+".png"))) { bitmap.compress(Bitmap.CompressFormat.PNG,100,output); }
        bitmap.recycle();
    }
    private int background(View view, int under) {
        if (view.getParent() instanceof View) under = background((View)view.getParent(),under);
        Drawable drawable = view.getBackground();
        if (drawable == null) return under;
        Drawable copy = drawable.getConstantState() == null ? drawable : drawable.getConstantState().newDrawable(view.getResources()).mutate();
        Bitmap bitmap = Bitmap.createBitmap(40,40,Bitmap.Config.ARGB_8888);
        android.graphics.Rect old = new android.graphics.Rect(copy.getBounds());
        copy.setState(view.getDrawableState()); copy.setBounds(0,0,40,40); copy.draw(new Canvas(bitmap));
        int paint = bitmap.getPixel(20,20); copy.setBounds(old); bitmap.recycle();
        return ColorUtils.compositeColors(paint,under);
    }
    private String id(View view) {
        try { return view.getResources().getResourceEntryName(view.getId()); }
        catch (Exception ignored) { return view.getClass().getSimpleName(); }
    }
    private void color(TextView text, String where, boolean hint) {
        int surface = text.getContext().getColor(R.color.surface);
        int bg = background(text,surface), fg = hint ? text.getCurrentHintTextColor() : text.getCurrentTextColor();
        int alpha = Math.round(Color.alpha(fg)*text.getAlpha());
        fg = ColorUtils.compositeColors(ColorUtils.setAlphaComponent(fg,alpha),bg);
        double ratio = ColorUtils.calculateContrast(fg,bg);
        minimum = Math.min(minimum,ratio); checked++;
        if (ratio < 4.5) failures.add(where+"/"+id(text)+(hint?" hint":"")+" enabled="+text.isEnabled()+" contrast="+ratio+" fg="+Integer.toHexString(fg)+" bg="+Integer.toHexString(bg));
    }
    private void audit(View view, String where) {
        if (view.getVisibility() != View.VISIBLE) return;
        if (view instanceof TextView) {
            TextView text = (TextView)view;
            if (text.getText().length()>0) {
                color(text,where,false);
                android.text.Layout layout = text.getLayout();
                if (layout != null) for (int line=0;line<layout.getLineCount();line++)
                    if (layout.getEllipsisCount(line)>0) failures.add(where+"/"+id(text)+" 文字被省略");
                if (layout != null && layout.getHeight() > text.getHeight()-text.getCompoundPaddingTop()-text.getCompoundPaddingBottom()+2)
                    failures.add(where+"/"+id(text)+" 高度裁切 "+layout.getHeight()+"/"+text.getHeight());
            }
            if (text.getHint()!=null && text.getHint().length()>0) color(text,where,true);
            if (text instanceof Button && text.getText().length()>0) {
                boolean enabled = text.isEnabled(); text.setEnabled(false); color(text,where,false); text.setEnabled(enabled);
            }
        }
        if (view instanceof ViewGroup) for (int i=0;i<((ViewGroup)view).getChildCount();i++) audit(((ViewGroup)view).getChildAt(i),where);
    }
    private void measure(View root, int width) {
        int height = Math.round(800*root.getResources().getDisplayMetrics().density);
        root.measure(View.MeasureSpec.makeMeasureSpec(width,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(height,View.MeasureSpec.EXACTLY));
        root.layout(0,0,width,root.getMeasuredHeight());
    }
    private void layouts(Activity host, boolean dark) {
        Configuration config = new Configuration(host.getResources().getConfiguration());
        config.uiMode = config.uiMode & ~Configuration.UI_MODE_NIGHT_MASK | (dark?Configuration.UI_MODE_NIGHT_YES:Configuration.UI_MODE_NIGHT_NO);
        config.fontScale = 1.3f;
        Context configured = host.createConfigurationContext(config);
        androidx.appcompat.view.ContextThemeWrapper theme = new androidx.appcompat.view.ContextThemeWrapper(configured,R.style.Theme_DeepseekHarness);
        LayoutInflater inflater = LayoutInflater.from(host).cloneInContext(theme);
        String[] names = {"activity_diagnostics","activity_extract","activity_main","activity_plugin_install","activity_update","activity_web_preview","activity_welcome","dialog_remind_backup","fragment_config","fragment_install","fragment_launch","fragment_plugins","fragment_pty_terminal","fragment_settings","fragment_terminal","fragment_workspace","item_plugin","welcome_page1","welcome_page2","welcome_page3"};
        for (String name : names) {
            int resource = theme.getResources().getIdentifier(name,"layout",host.getPackageName());
            View view = inflater.inflate(resource,null,false);
            measure(view,Math.round(360*theme.getResources().getDisplayMetrics().density));
            audit(view,(dark?"dark":"light")+"/large-font/"+name);
        }
        Button platform = new Button(theme); platform.setText("动态原生按钮"); measure(platform,600); audit(platform,(dark?"dark":"light")+"/platform");
    }
    private void backupPreference() throws Exception {
        Context isolated = new android.content.ContextWrapper(getTargetContext()) {
            @Override public android.content.SharedPreferences getSharedPreferences(String name,int mode) {
                return super.getSharedPreferences("theme-verification-"+name,mode);
            }
        };
        ConfigStore store = new ConfigStore(isolated);
        try {
            store.setUiTheme("dark");
            require("dark".equals(store.exportBackupSettings().getString("uiTheme")),"主题未进入设置备份");
            store.beginRestoreSettings();
            store.importBackupSettings(new org.json.JSONObject().put("uiTheme","light"));
            require("light".equals(store.getUiTheme()),"设置恢复未应用主题");
            store.finishRestoreSettings(true);
            require("dark".equals(store.getUiTheme()),"主题恢复失败时不能回滚");
            store.importBackupSettings(new org.json.JSONObject());
            require("dark".equals(store.getUiTheme()),"旧备份丢失字段时覆盖了当前主题");
        } finally { isolated.getSharedPreferences(com.deepseekharness.app.util.Constants.PREFS,0).edit().clear().commit(); }
    }
    @Override public void onStart() {
        Bundle output = new Bundle(); ConfigStore config = new ConfigStore(getTargetContext());
        String original = config.getUiTheme();
        try {
            shell("am start -W -n com.dsh.client/com.deepseekharness.app.ui.MainActivity");
            page = startActivitySync(new Intent(getTargetContext(),MainActivity.class).setAction("theme.test."+System.nanoTime()).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            if ("probe".equals(args.getString("mode"))) {
                require("dark".equals(config.getUiTheme()) && ThemeController.isDark(page),"重新启动未保留黑夜主题");
                backupPreference();
                runOnMainSync(() -> ThemeController.select(page,"system"));
                output.putString("result","PASS: 新进程应用已保存的黑夜主题；独立设置备份/恢复/回滚通过；已恢复跟随系统");
            } else if ("lifecycle".equals(args.getString("mode"))) {
                require("dark".equals(config.getUiTheme()) && ThemeController.isDark(page),"重新启动丢失主题选择");
                MainActivity current = MainActivity.current;
                runOnMainSync(() -> ((com.google.android.material.bottomnavigation.BottomNavigationView)current.findViewById(R.id.bottom_nav)).setSelectedItemId(R.id.nav_settings));
                until(() -> MainActivity.current.findViewById(R.id.settings_tabs)!=null,"设置页未加载");
                runOnMainSync(() -> audit(MainActivity.current.findViewById(android.R.id.content),"actual-settings"));
                screenshot("dark-settings");
                java.lang.reflect.Field field = PtyTerminalFragment.class.getDeclaredField("session");field.setAccessible(true);
                require(field.get(null)==null,"已有用户终端，不能覆盖验收");
                require(PtyTerminalFragment.preferred(getTargetContext()),"当前选择简易终端，需使用独立 PTY 验收");
                runOnMainSync(() -> ((com.google.android.material.bottomnavigation.BottomNavigationView)MainActivity.current.findViewById(R.id.bottom_nav)).setSelectedItemId(R.id.nav_terminal));
                until(() -> { try { return field.get(null)!=null && ((com.deepseekharness.app.PtySession)field.get(null)).isRunning(); } catch (Exception e) { return false; } },"终端未启动");
                com.deepseekharness.app.PtySession terminal = (com.deepseekharness.app.PtySession)field.get(null);
                MainActivity old = MainActivity.current;
                runOnMainSync(() -> old.findViewById(R.id.btn_theme).performClick());
                until(() -> MainActivity.current!=null && MainActivity.current!=old && !ThemeController.isDark(MainActivity.current),"终端页主题未切换");
                require(field.get(null)==terminal && terminal.isRunning(),"主题切换中断了终端");
                runOnMainSync(() -> ThemeController.select(MainActivity.current,"dark"));
                until(() -> MainActivity.current!=null && ThemeController.isDark(MainActivity.current),"黑夜主题未恢复");
                require(field.get(null)==terminal && terminal.isRunning(),"再次切换中断了终端");
                runOnMainSync(PtyTerminalFragment::shutdown);
                require(failures.isEmpty(),String.join("\n",failures));
                output.putString("result","PASS: 主题进程持久化、真实设置页文字、主题切换两次保持同一运行中 PTY");
            } else {
                for (boolean dark : new boolean[]{false,true}) {
                    until(() -> MainActivity.current != null && MainActivity.current.getLifecycle().getCurrentState().isAtLeast(androidx.lifecycle.Lifecycle.State.RESUMED)
                            && !MainActivity.current.getSupportFragmentManager().isStateSaved(), "主页面尚未回到前台");
                    MainActivity current = MainActivity.current;
                    runOnMainSync(() -> ((com.google.android.material.bottomnavigation.BottomNavigationView)current.findViewById(R.id.bottom_nav)).setSelectedItemId(R.id.nav_settings));
                    until(() -> MainActivity.current.findViewById(R.id.settings_tabs)!=null,"外观设置未加载");
                    runOnMainSync(() -> ThemeController.select(MainActivity.current,dark?"dark":"light"));
                    until(() -> MainActivity.current!=null && ThemeController.isDark(MainActivity.current)==dark && config.getUiTheme().equals(dark?"dark":"light"),"主题未生效");
                    page = MainActivity.current;
                    runOnMainSync(() -> layouts(page,dark));
                    page = startActivitySync(new Intent(getTargetContext(),UpdateActivity.class).setAction("theme.update."+System.nanoTime()).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
                    until(() -> page.findViewById(R.id.update_back).getWidth()>0,"更新页未布局");
                    runOnMainSync(() -> audit(page.findViewById(android.R.id.content),(dark?"dark":"light")+"/actual-update"));
                    screenshot(dark?"dark-update":"light-update");
                    Activity old = page; runOnMainSync(old::finish); page=MainActivity.current;
                    until(() -> MainActivity.current != null && MainActivity.current.getLifecycle().getCurrentState().isAtLeast(androidx.lifecycle.Lifecycle.State.RESUMED), "更新页返回未完成");
                    phase((dark?"黑夜":"白天")+"主题：检查布局、按钮状态与更新页");
                }
                require(failures.isEmpty(),String.join("\n",failures));
                // 顶部按钮切换后再切回，仍保留当前设置页。
                MainActivity before = MainActivity.current;
                runOnMainSync(() -> before.findViewById(R.id.btn_theme).performClick());
                until(() -> MainActivity.current!=before && !ThemeController.isDark(MainActivity.current),"顶部切换无效");
                require(MainActivity.current.findViewById(R.id.settings_tabs)!=null,"切换丢失当前页面");
                runOnMainSync(() -> ThemeController.select(MainActivity.current,"dark"));
                until(() -> ThemeController.isDark(MainActivity.current),"持久性检查准备失败");
                output.putString("result","PASS");
                output.putString("coverage",checked+" 项真实控件文字状态；最低对比度 "+minimum+"；20 个布局×两主题×1.3 字体、实际更新页、顶部切换与页面保留");
            }
        } catch (Throwable error) {
            output.putString("result","FAIL: "+error);
            runOnMainSync(() -> ThemeController.select(getTargetContext(),original));
        } finally {
            File folder = new File(getTargetContext().getCacheDir(),"theme-verification");folder.mkdirs();
            try (FileOutputStream out = new FileOutputStream(new File(folder,"contrast-"+args.getString("mode","all")+".txt"))) {
                out.write(("checked="+checked+" minimum="+minimum+"\n"+String.join("\n",failures)).getBytes(java.nio.charset.StandardCharsets.UTF_8));
            } catch (Exception ignored) { }
        }
        finish(output.getString("result","").startsWith("PASS")?Activity.RESULT_OK:Activity.RESULT_CANCELED,output);
    }
}
