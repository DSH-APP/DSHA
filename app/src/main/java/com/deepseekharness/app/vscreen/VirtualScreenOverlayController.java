package com.deepseekharness.app.vscreen;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.PixelFormat;
import android.os.*;
import android.provider.Settings;
import android.view.*;
import android.widget.*;
import org.json.JSONObject;

/** 独立可拖动悬浮预览；复用相同帧解码器，不占用文本悬浮条。 */
public final class VirtualScreenOverlayController {
    private static final Handler MAIN=new Handler(Looper.getMainLooper());
    private static WindowManager manager;private static LinearLayout panel;private static ImageView image;
    private static WindowManager.LayoutParams params;private static Bitmap shown;
    private static long revision;private static float downX,downY;private static int startX,startY;
    private VirtualScreenOverlayController(){}
    public static void show(Context context){
        if(android.os.Build.VERSION.SDK_INT<30)return;
        if(!Settings.canDrawOverlays(context)){
            Toast.makeText(context,com.deepseekharness.app.util.UiText.choose("允许悬浮窗后，可再次点悬浮预览。","Allow overlays, then tap Float preview again."),Toast.LENGTH_LONG).show();
            context.startActivity(new android.content.Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,android.net.Uri.parse("package:"+context.getPackageName())).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK));return;
        }
        if(panel!=null){hide();return;}
        Context app=context.getApplicationContext();manager=(WindowManager)app.getSystemService(Context.WINDOW_SERVICE);
        panel=new LinearLayout(app);panel.setOrientation(LinearLayout.VERTICAL);panel.setBackgroundColor(0xee101218);
        TextView handle=new TextView(app);handle.setText(com.deepseekharness.app.util.UiText.choose("虚拟屏 · 拖动此处 · 点此隐藏","Virtual screen · drag / tap to hide"));handle.setTextColor(0xffffffff);handle.setTextSize(12);handle.setPadding(dp(app,8),dp(app,8),dp(app,8),dp(app,8));panel.addView(handle);
        image=new ImageView(app);image.setScaleType(ImageView.ScaleType.FIT_CENTER);panel.addView(image,new LinearLayout.LayoutParams(-1,dp(app,300)));
        params=new WindowManager.LayoutParams(dp(app,200),-2,WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,PixelFormat.TRANSLUCENT);
        params.gravity=Gravity.TOP|Gravity.LEFT;params.x=dp(app,8);params.y=dp(app,80);
        handle.setOnTouchListener((v,e)->{
            if(e.getAction()==MotionEvent.ACTION_DOWN){downX=e.getRawX();downY=e.getRawY();startX=params.x;startY=params.y;return true;}
            if(e.getAction()==MotionEvent.ACTION_MOVE){params.x=Math.max(0,startX+(int)(e.getRawX()-downX));params.y=Math.max(0,startY+(int)(e.getRawY()-downY));try{manager.updateViewLayout(panel,params);}catch(RuntimeException ignored){}return true;}
            if(e.getAction()==MotionEvent.ACTION_UP&&Math.hypot(e.getRawX()-downX,e.getRawY()-downY)<12){hide();v.performClick();}return true;
        });
        try{manager.addView(panel,params);long session=++revision;poll(app,session);}
        catch(RuntimeException e){hide();Toast.makeText(context,com.deepseekharness.app.util.UiText.choose("无法显示悬浮预览","Unable to show overlay"),Toast.LENGTH_SHORT).show();}
    }
    private static void poll(Context app,long session){
        if(panel==null||revision!=session)return;
        new Thread(()->{
            JSONObject result=VirtualScreenManager.preview(app);Bitmap bitmap=VirtualScreenManager.previewBitmap(result);
            MAIN.post(()->{
                if(panel==null||revision!=session){if(bitmap!=null)bitmap.recycle();return;}
                if(bitmap!=null){Bitmap old=shown;shown=bitmap;image.setImageBitmap(bitmap);if(old!=null)old.recycle();}
                if("VSCREEN_NOT_RUNNING".equals(result.optString("error"))){hide();return;}
                MAIN.postDelayed(()->poll(app,session),500);
            });
        },"vscreen-overlay").start();
    }
    public static void hide(){
        revision++;if(panel!=null)try{manager.removeView(panel);}catch(RuntimeException ignored){}
        panel=null;image=null;manager=null;if(shown!=null){shown.recycle();shown=null;}
    }
    private static int dp(Context c,int v){return Math.round(v*c.getResources().getDisplayMetrics().density);}
}

