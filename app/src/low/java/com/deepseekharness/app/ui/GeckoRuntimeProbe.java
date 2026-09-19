package com.deepseekharness.app.ui;

import androidx.fragment.app.FragmentActivity;
import android.view.*;
import android.widget.FrameLayout;
import org.mozilla.geckoview.*;

/** Android 6/7 的候选验证使用随包 Gecko，而不是强行要求旧系统 WebView。 */
public final class GeckoRuntimeProbe {
    private GeckoRuntimeProbe() { }
    public static AutoCloseable open(FragmentActivity activity,String url,java.util.function.Consumer<String> failed){
        java.util.concurrent.atomic.AtomicBoolean active=new java.util.concurrent.atomic.AtomicBoolean(true);
        GeckoSession session=new GeckoSession();session.setContentDelegate(new GeckoSession.ContentDelegate(){
            @Override public void onCrash(GeckoSession session){if(active.get())failed.accept("TRIAL_RENDERER_GONE");}
            @Override public void onKill(GeckoSession session){if(active.get())failed.accept("TRIAL_RENDERER_GONE");}
        });session.open(GeckoRuntime.getDefault(activity));GeckoView view=new GeckoView(activity);view.setSession(session);
        FrameLayout box=new FrameLayout(activity);box.addView(view,new FrameLayout.LayoutParams(-1,-1));box.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS);
        ViewGroup root=activity.findViewById(android.R.id.content);root.addView(box,0,new ViewGroup.LayoutParams(Math.max(1,root.getWidth()),Math.max(1,root.getHeight())));session.loadUri(url);
        return ()->activity.runOnUiThread(()->{active.set(false);session.close();root.removeView(box);});
    }
}
