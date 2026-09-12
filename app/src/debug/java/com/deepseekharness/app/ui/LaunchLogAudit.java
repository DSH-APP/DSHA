package com.deepseekharness.app.ui;

import android.app.*;
import android.content.*;
import android.os.Bundle;
import android.view.*;
import android.widget.*;
import com.deepseekharness.app.R;
import java.lang.reflect.Method;

/** #61：真实启动 Fragment 的长日志、独立滚动、短窗口和横屏布局回归。 */
public final class LaunchLogAudit extends Instrumentation {
    private FragmentSessionTestActivity page;
    private LaunchFragment fragment;
    private Method update;
    private int checks;
    @Override public void onCreate(Bundle args) { super.onCreate(args);start(); }
    private void check(boolean ok,String why) { checks++;if(!ok)throw new AssertionError(why); }
    private void ui(Runnable action) {
        Throwable[] error={null};runOnMainSync(()->{try{action.run();}catch(Throwable t){error[0]=t;}});
        if(error[0]!=null)throw new AssertionError(error[0]);
    }
    private void log(int lines) throws Exception {
        StringBuilder text=new StringBuilder();for(int i=0;i<lines;i++)text.append("验收日志 ").append(i).append("：检查不执行任何模型请求\n");
        ui(()->{try{update.invoke(fragment,text.toString());}catch(Exception e){throw new IllegalStateException(e);}});
        Thread.sleep(180);waitForIdleSync();
    }
    private android.graphics.Rect rect(View view,View root) {
        int[] at=new int[2],origin=new int[2];view.getLocationOnScreen(at);root.getLocationOnScreen(origin);
        return new android.graphics.Rect(at[0]-origin[0],at[1]-origin[1],at[0]-origin[0]+view.getWidth(),at[1]-origin[1]+view.getHeight());
    }
    private void actualBounds() throws Exception {
        log(1000);ui(()->{
            View root=fragment.requireView(),controls=root.findViewById(R.id.launch_controls),scroll=root.findViewById(R.id.launch_log_scroll);
            check(rect(controls,root).top>=0&&rect(controls,root).bottom<=root.getHeight(),"实际窗口操作栏越界");
            check(scroll.getHeight()>0&&rect(scroll,root).bottom<=root.getHeight(),"实际窗口日志越界");
            check(root.getScrollY()==0,"实际窗口发生整页滚动");
        });
    }
    private void rotate(int orientation) throws Exception {
        ActivityMonitor monitor=addMonitor(FragmentSessionTestActivity.class.getName(),null,false);
        ui(()->page.setRequestedOrientation(orientation));
        FragmentSessionTestActivity next=(FragmentSessionTestActivity)waitForMonitorWithTimeout(monitor,15000);removeMonitor(monitor);
        check(next!=null,"方向变化未重建测试宿主");page=next;Thread.sleep(600);
        ui(()->{fragment=(LaunchFragment)page.getSupportFragmentManager().findFragmentById(R.id.fragment_container);
            fragment.requireView().setLayoutParams(new FrameLayout.LayoutParams(-1,-1));});
        actualBounds();
    }
    private void keyboard() throws Exception {
        int[] before={0};EditText[] editor={null};
        ui(()->{
            View root=fragment.requireView();root.setLayoutParams(new FrameLayout.LayoutParams(-1,-1));
            page.getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
            editor[0]=new EditText(page);editor[0].setSingleLine();editor[0].setHint("仅用于输入法布局验收");
            FrameLayout.LayoutParams lp=new FrameLayout.LayoutParams(-1,Math.round(48*root.getResources().getDisplayMetrics().density),Gravity.BOTTOM);
            ((FrameLayout)page.findViewById(R.id.fragment_container)).addView(editor[0],lp);
        });Thread.sleep(350);ui(()->before[0]=fragment.requireView().getHeight());
        ui(()->{editor[0].requestFocus();((android.view.inputmethod.InputMethodManager)page.getSystemService(Context.INPUT_METHOD_SERVICE))
                .showSoftInput(editor[0],android.view.inputmethod.InputMethodManager.SHOW_IMPLICIT);});
        boolean[] visible={false};for(int i=0;i<40;i++){Thread.sleep(150);ui(()->visible[0]=page.getWindow().getDecorView().getRootWindowInsets().isVisible(WindowInsets.Type.ime()));if(visible[0])break;}
        check(visible[0],"真实输入法未弹出");ui(()->check(fragment.requireView().getHeight()<before[0],"输入法未缩小实际窗口"));actualBounds();
        ui(()->{((android.view.inputmethod.InputMethodManager)page.getSystemService(Context.INPUT_METHOD_SERVICE)).hideSoftInputFromWindow(editor[0].getWindowToken(),0);
            ((FrameLayout)editor[0].getParent()).removeView(editor[0]);});Thread.sleep(500);actualBounds();
    }
    private void profile(int width,int height) throws Exception {
        View root=fragment.requireView();float density=root.getResources().getDisplayMetrics().density;
        ui(()->root.setLayoutParams(new FrameLayout.LayoutParams(Math.round(width*density),Math.round(height*density))));
        Thread.sleep(250);waitForIdleSync();log(1);
        View controls=root.findViewById(R.id.launch_controls);ScrollView scroll=root.findViewById(R.id.launch_log_scroll);
        android.graphics.Rect[] anchor={null};ui(()->anchor[0]=rect(controls,root));
        for(int lines:new int[]{1,50,1000}) {
            log(lines);
            ui(()->{
                check(rect(controls,root).equals(anchor[0]),"日志 "+lines+" 行移动了操作栏 "+width+"x"+height);
                check(anchor[0].top>=0&&anchor[0].bottom<=root.getHeight(),"操作栏超出窗口");
                check(scroll.getHeight()>0&&rect(scroll,root).bottom<=root.getHeight(),"日志没有被剩余高度约束");
                check(root.getScrollY()==0&&root.findViewById(R.id.launch_sections).getScrollY()==0,"整页产生了滚动");
            });
        }
        int[] historical={0};ui(()->{scroll.scrollTo(0,100);historical[0]=scroll.getScrollY();check(historical[0]>0,"日志无法独立滚动");});
        log(1001);ui(()->check(scroll.getScrollY()==historical[0],"上翻阅读历史时被自动拉到底部"));
        ui(()->scroll.scrollTo(0,Math.max(0,scroll.getChildAt(0).getHeight()-scroll.getHeight())));
        log(1002);ui(()->check(scroll.getScrollY()==Math.max(0,scroll.getChildAt(0).getHeight()-scroll.getHeight()),"回到底部后未继续跟随"));
        Bundle progress=new Bundle();progress.putString("stream","PASS "+width+"x"+height+"dp：1/50/1000 行、顶部按钮、独立滚动与跟随\n");sendStatus(0,progress);
    }
    @Override public void onStart() {
        Bundle result=new Bundle();Context app=getTargetContext().getApplicationContext();
        try {
            try(android.os.ParcelFileDescriptor fd=getUiAutomation().executeShellCommand("am start -W -n com.dsh.client/com.deepseekharness.app.ui.MainActivity");
                java.io.InputStream in=new android.os.ParcelFileDescriptor.AutoCloseInputStream(fd)){while(in.read()!=-1){}}
            ActivityMonitor monitor=addMonitor(FragmentSessionTestActivity.class.getName(),null,false);
            ui(()->app.startActivity(new Intent(app,FragmentSessionTestActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)));
            page=(FragmentSessionTestActivity)waitForMonitorWithTimeout(monitor,15000);removeMonitor(monitor);check(page!=null,"宿主未打开");
            fragment=new LaunchFragment();
            ui(()->page.getSupportFragmentManager().beginTransaction().replace(R.id.fragment_container,fragment)
                    .setMaxLifecycle(fragment,androidx.lifecycle.Lifecycle.State.STARTED).commitNow());
            update=LaunchFragment.class.getDeclaredMethod("updateLog",String.class);update.setAccessible(true);
            for(int[] size:new int[][]{{392,680},{392,300},{720,260},{392,680}})profile(size[0],size[1]);
            ui(()->fragment.requireView().setLayoutParams(new FrameLayout.LayoutParams(-1,-1)));
            rotate(android.content.pm.ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE);
            rotate(android.content.pm.ActivityInfo.SCREEN_ORIENTATION_PORTRAIT);
            keyboard();
            boolean[] clicked={false};ui(()->{
                View button=fragment.requireView().findViewById(R.id.launch_start);
                button.setOnClickListener(v->clicked[0]=true);button.setEnabled(true);button.performClick();
                check(clicked[0],"长日志后启动按钮不可点击");
            });
            result.putString("result","PASS");result.putInt("checks",checks);
        } catch(Throwable error) {result.putString("failure",android.util.Log.getStackTraceString(error));}
        finally {if(page!=null)ui(page::finish);finish(result.containsKey("failure")?1:0,result);}
    }
}
