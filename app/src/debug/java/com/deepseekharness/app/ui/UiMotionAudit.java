package com.deepseekharness.app.ui;
import android.app.*;
import android.os.*;
import android.view.*;
import com.deepseekharness.app.R;
import java.util.*;

/** 非调试窗口的帧耗时；只访问设置及其子页，不修改用户配置或执行终端命令。 */
public final class UiMotionAudit extends Instrumentation {
    @Override public void onCreate(Bundle args){super.onCreate(args);start();}
    private void ui(Runnable work){runOnMainSync(work);waitForIdleSync();}
    private void settle() throws Exception {Thread.sleep(260);waitForIdleSync();}
    @Override public void onStart(){
        Bundle result=new Bundle();List<Long> frames=Collections.synchronizedList(new ArrayList<>());
        HandlerThread thread=new HandlerThread("motion-audit");thread.start();
        Window window=null;Window.OnFrameMetricsAvailableListener listener=null;
        try {
            try(var fd=getUiAutomation().executeShellCommand("am start -W -n com.dsh.client/com.deepseekharness.app.ui.MainActivity");var input=new ParcelFileDescriptor.AutoCloseInputStream(fd)){while(input.read()!=-1){}}
            long end=System.currentTimeMillis()+20000;while(MainActivity.current==null&&System.currentTimeMillis()<end)Thread.sleep(100);
            MainActivity activity=MainActivity.current;if(activity==null)throw new AssertionError("主页面未就绪");
            ui(()->((com.google.android.material.bottomnavigation.BottomNavigationView)activity.findViewById(R.id.bottom_nav)).setSelectedItemId(R.id.nav_settings));settle();
            window=activity.getWindow();listener=(w,metrics,dropped)->frames.add(metrics.getMetric(FrameMetrics.TOTAL_DURATION));
            window.addOnFrameMetricsAvailableListener(listener,new Handler(thread.getLooper()));
            for(int round=0;round<4;round++) {
                for(int index:new int[]{0,1,2,3}) {
                    final int row=index;
                    ui(()->((ViewGroup)activity.findViewById(R.id.settings_tabs)).getChildAt(row).performClick());settle();
                    ui(()->activity.findViewById(R.id.sub_back).performClick());settle();
                }
                if(round==0)frames.clear();
            }
            window.removeOnFrameMetricsAvailableListener(listener);listener=null;
            List<Long> samples; synchronized(frames){samples=new ArrayList<>(frames);}Collections.sort(samples);
            if(samples.size()<20)throw new AssertionError("有效帧样本不足");
            result.putString("result","PASS");result.putInt("frames",samples.size());
            result.putDouble("p95_ms",samples.get((int)(samples.size()*0.95))/1e6);
            result.putDouble("max_ms",samples.get(samples.size()-1)/1e6);
            result.putLong("over_32ms",samples.stream().filter(n->n>32000000L).count());
        }catch(Throwable error){result.putString("failure",android.util.Log.getStackTraceString(error));}
        finally{if(window!=null&&listener!=null)window.removeOnFrameMetricsAvailableListener(listener);thread.quitSafely();finish(result.containsKey("failure")?1:0,result);}
    }
}
