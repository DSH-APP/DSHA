package com.deepseekharness.app.vscreen;

import android.app.Activity;
import android.graphics.Bitmap;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.ViewGroup;
import android.widget.*;
import com.deepseekharness.app.R;
import com.deepseekharness.app.util.UiText;
import java.lang.ref.WeakReference;

/** DSHA 前台内部的预览小窗；不需要系统悬浮窗权限，也不抢走当前对话。 */
public final class VirtualScreenForeground {
    private static final Handler MAIN=new Handler(Looper.getMainLooper());
    private static WeakReference<Activity> host=new WeakReference<>(null);
    private static LinearLayout panel;private static ViewGroup parent;private static ImageView image;
    private static Bitmap bitmap;private static long epoch;private static String hiddenGeneration="";
    private VirtualScreenForeground(){}
    public static void resume(Activity activity){host=new WeakReference<>(activity);activity.getWindow().getDecorView().post(VirtualScreenForeground::sync);}
    public static void pause(Activity activity){if(host.get()==activity){detach();host.clear();}}
    public static void present(){MAIN.post(()->{hiddenGeneration="";sync();});}
    public static void stopped(){MAIN.post(()->{hiddenGeneration="";detach();});}
    private static void sync(){
        Activity activity=host.get();String generation=VirtualScreenManager.generation();
        if(activity==null||activity.isFinishing()||activity.isDestroyed()||activity instanceof VirtualScreenActivity||generation.isEmpty()||hiddenGeneration.equals(generation)){detach();return;}
        if(panel!=null)return;
        if(!(activity.findViewById(android.R.id.content) instanceof FrameLayout))return;
        parent=activity.findViewById(android.R.id.content);
        panel=new LinearLayout(activity);panel.setOrientation(LinearLayout.VERTICAL);panel.setBackgroundResource(R.drawable.bg_card);panel.setElevation(dp(activity,12));
        LinearLayout bar=new LinearLayout(activity);bar.setGravity(Gravity.CENTER_VERTICAL);
        TextView title=new TextView(activity);title.setText(UiText.choose("虚拟屏 · 拖动","Virtual screen · drag"));title.setTextSize(12);title.setTextColor(activity.getColor(R.color.text));title.setPadding(dp(activity,8),dp(activity,8),0,dp(activity,8));bar.addView(title,new LinearLayout.LayoutParams(0,-2,1));
        TextView hide=new TextView(activity);hide.setText("−");hide.setTextSize(22);hide.setTextColor(activity.getColor(R.color.text));hide.setGravity(Gravity.CENTER);hide.setContentDescription(UiText.choose("隐藏预览","Hide preview"));bar.addView(hide,new LinearLayout.LayoutParams(dp(activity,40),dp(activity,40)));
        hide.setOnClickListener(v->{hiddenGeneration=VirtualScreenManager.generation();detach();});panel.addView(bar);
        image=new ImageView(activity);image.setScaleType(ImageView.ScaleType.FIT_CENTER);image.setBackgroundColor(0xff101218);image.setContentDescription(UiText.choose("打开虚拟屏控制","Open virtual screen controls"));panel.addView(image,new LinearLayout.LayoutParams(-1,dp(activity,260)));
        image.setOnClickListener(v->activity.startActivity(new android.content.Intent(activity,VirtualScreenActivity.class)));
        FrameLayout.LayoutParams layout=new FrameLayout.LayoutParams(dp(activity,172),-2,Gravity.TOP|Gravity.END);layout.topMargin=dp(activity,64);layout.rightMargin=dp(activity,10);parent.addView(panel,layout);
        title.setOnTouchListener(new android.view.View.OnTouchListener(){float x,y,startX,startY;
            public boolean onTouch(android.view.View v,MotionEvent event){
                if(panel==null)return true;
                if(event.getAction()==MotionEvent.ACTION_DOWN){x=event.getRawX();y=event.getRawY();startX=panel.getTranslationX();startY=panel.getTranslationY();return true;}
                if(event.getAction()==MotionEvent.ACTION_MOVE){panel.setTranslationX(Math.max(-panel.getLeft(),Math.min(parent.getWidth()-panel.getRight(),startX+event.getRawX()-x)));panel.setTranslationY(Math.max(-panel.getTop(),Math.min(parent.getHeight()-panel.getBottom(),startY+event.getRawY()-y)));return true;}
                if(event.getAction()==MotionEvent.ACTION_UP)v.performClick();return true;
            }});
        poll(activity.getApplicationContext(),++epoch);
    }
    private static void poll(android.content.Context app,long current){
        if(panel==null||epoch!=current)return;
        new Thread(()->{var result=VirtualScreenManager.preview(app);Bitmap next=VirtualScreenManager.previewBitmap(result);
            MAIN.post(()->{
                if(panel==null||epoch!=current){if(next!=null)next.recycle();return;}
                if(VirtualScreenManager.generation().isEmpty()){if(next!=null)next.recycle();detach();return;}
                if(next!=null){Bitmap old=bitmap;bitmap=next;image.setImageBitmap(next);if(old!=null)old.recycle();}
                MAIN.postDelayed(()->poll(app,current),500);
            });
        },"vscreen-foreground").start();
    }
    private static void detach(){epoch++;if(panel!=null&&parent!=null)parent.removeView(panel);panel=null;parent=null;image=null;if(bitmap!=null){bitmap.recycle();bitmap=null;}}
    private static int dp(android.content.Context context,int value){return Math.round(value*context.getResources().getDisplayMetrics().density);}
}
