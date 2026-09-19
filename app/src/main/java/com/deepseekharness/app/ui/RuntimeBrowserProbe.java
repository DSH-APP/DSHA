package com.deepseekharness.app.ui;

import android.content.Context;
import android.os.*;
import android.view.*;
import android.webkit.*;
import android.widget.FrameLayout;
import com.deepseekharness.app.ForegroundActivity;
import com.deepseekharness.app.backup.BackupControl;
import java.io.IOException;
import java.lang.ref.WeakReference;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/** 界面重建只重挂浏览内核；试运行进程仍属于宿主维护任务，不由 Activity 管理。 */
public final class RuntimeBrowserProbe {
    private RuntimeBrowserProbe() { }
    public static com.deepseekharness.app.runtime.RuntimeTrial.Renderer open(Context context,String authUrl,String baseUrl,String cookie,BackupControl control)throws Exception{
        AtomicBoolean closed=new AtomicBoolean(),attached=new AtomicBoolean();AtomicReference<Exception> failure=new AtomicReference<>();
        Handler main=new Handler(Looper.getMainLooper());
        final class Surface implements Runnable {
            WeakReference<androidx.fragment.app.FragmentActivity> owner=new WeakReference<>(null);AutoCloseable view;
            void detach(){if(view!=null)try{view.close();}catch(Exception ignored){}view=null;owner.clear();}
            public void run(){
                if(owner.get()!=null&&(owner.get().isDestroyed()||owner.get().isFinishing()))detach();
                if(closed.get()){detach();return;}var activity=ForegroundActivity.current();
                if(!ForegroundActivity.isResumed(activity)){main.postDelayed(this,500);return;}
                try{
                    if(owner.get()!=activity){
                        detach();owner=new WeakReference<>(activity);
                        java.util.function.Consumer<String> failed=code->failure.set(new IOException(code));
                        if(PreviewFallback.preferred(activity))view=gecko(activity,authUrl,failed);
                        else try{view=webView(activity,baseUrl,cookie,failed);}
                        catch(RuntimeException|LinkageError error){if(!com.deepseekharness.app.BuildConfig.LOW_ANDROID)throw error;view=gecko(activity,authUrl,failed);}
                        attached.set(true);
                    }
                    main.postDelayed(this,500);
                }catch(Exception|LinkageError error){failure.set(new IOException("TRIAL_BROWSER_UNAVAILABLE",error));detach();}
            }
        }
        Surface surface=new Surface();main.post(surface);
        com.deepseekharness.app.runtime.RuntimeTrial.Renderer close=new com.deepseekharness.app.runtime.RuntimeTrial.Renderer(){
            public void close(){closed.set(true);main.removeCallbacks(surface);main.post(surface::detach);}
            public void check()throws IOException{if(failure.get()!=null)throw new IOException("TRIAL_BROWSER_UNAVAILABLE",failure.get());}
        };
        try{while(!attached.get()&&failure.get()==null){control.check();Thread.sleep(100);}if(failure.get()!=null)throw failure.get();return close;}
        catch(Exception error){close.close();throw error;}
    }
    private static AutoCloseable gecko(androidx.fragment.app.FragmentActivity activity,String url,java.util.function.Consumer<String> failed)throws Exception{
        return (AutoCloseable)Class.forName("com.deepseekharness.app.ui.GeckoRuntimeProbe").getMethod("open",androidx.fragment.app.FragmentActivity.class,String.class,java.util.function.Consumer.class).invoke(null,activity,url,failed);
    }
    private static AutoCloseable webView(androidx.fragment.app.FragmentActivity activity,String url,String cookie,java.util.function.Consumer<String> failed){
        FrameLayout container=new FrameLayout(activity);WebView view=new WebView(activity);AtomicBoolean alive=new AtomicBoolean(true);
        try{
            var settings=view.getSettings();settings.setJavaScriptEnabled(true);settings.setDomStorageEnabled(true);settings.setAllowFileAccess(false);settings.setAllowContentAccess(false);
            settings.setMixedContentMode(WebSettings.MIXED_CONTENT_NEVER_ALLOW);view.setWebViewClient(new WebViewClient(){
                @Override public boolean onRenderProcessGone(WebView gone,RenderProcessGoneDetail detail){
                    if(alive.getAndSet(false))failed.accept("TRIAL_RENDERER_GONE");container.removeView(gone);gone.destroy();return true;
                }
            });
            container.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS);container.setFocusable(false);container.setClickable(false);
            container.addView(view,new FrameLayout.LayoutParams(-1,-1));ViewGroup root=activity.findViewById(android.R.id.content);
            root.addView(container,0,new ViewGroup.LayoutParams(Math.max(1,root.getWidth()),Math.max(1,root.getHeight())));
            CookieManager manager=CookieManager.getInstance();manager.setAcceptCookie(true);manager.setAcceptThirdPartyCookies(view,false);
            manager.setCookie(url,cookie+"; Path=/; HttpOnly; SameSite=Strict",ok->{if(alive.get()&&Boolean.TRUE.equals(ok))view.loadUrl(url);});
            return ()->{alive.set(false);view.stopLoading();root.removeView(container);view.destroy();};
        }catch(RuntimeException|LinkageError error){view.destroy();throw error;}
    }
}

