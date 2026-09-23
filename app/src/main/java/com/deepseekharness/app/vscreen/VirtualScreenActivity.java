package com.deepseekharness.app.vscreen;

import android.graphics.Bitmap;
import android.os.*;
import android.view.*;
import android.widget.*;
import androidx.appcompat.app.AppCompatActivity;
import com.deepseekharness.app.R;
import com.deepseekharness.app.util.UiText;
import org.json.JSONObject;
import java.util.concurrent.*;

/** 以预览为主的虚拟屏控制页：单指直接触控、双指缩放，其他操作折叠。 */
public final class VirtualScreenActivity extends AppCompatActivity {
    private final Handler main=new Handler(Looper.getMainLooper());
    private final ExecutorService worker=Executors.newSingleThreadExecutor();
    private TextView state,detail;private VirtualScreenPreviewView preview;private EditText packageInput,textInput;
    private View packagePanel,textPanel;private SeekBar size;private boolean visible,polling,busy;private long frameSeq=-1;private String generation="";
    private final Runnable poll=this::readFrame;
    private static String t(String zh,String en){return UiText.choose(zh,en);}
    @Override protected void onCreate(Bundle saved){
        super.onCreate(saved);
        LinearLayout root=new LinearLayout(this);root.setOrientation(LinearLayout.VERTICAL);root.setPadding(dp(12),dp(10),dp(12),dp(12));root.setBackgroundResource(R.color.surface);
        root.addView(button(t("返回","Back"),this::finish),new LinearLayout.LayoutParams(-1,dp(40)));
        state=label(t("创建或复用虚拟屏","Create or reuse virtual screen"),14);root.addView(state);
        detail=label("",11);root.addView(detail);
        preview=new VirtualScreenPreviewView(this);preview.listener=new VirtualScreenPreviewView.Touch(){
            public void input(String gen,long seq,float x1,float y1,float x2,float y2,int ms,boolean tap){run(()->VirtualScreenManager.action(VirtualScreenActivity.this,tap?"tap":"swipe",gen,seq,tap?"x="+x2+"&y="+y2:"x1="+x1+"&y1="+y1+"&x2="+x2+"&y2="+y2+"&ms="+ms));}
            public void stream(String gen,long seq,String stroke,int action,float x,float y){worker.execute(()->VirtualScreenManager.touch(VirtualScreenActivity.this,gen,seq,stroke,action,x,y));}
        };
        int initial=Math.max(280,Math.min(720,(int)(getResources().getDisplayMetrics().heightPixels/getResources().getDisplayMetrics().density*0.62f)));
        FrameLayout frame=new FrameLayout(this);frame.setPadding(0,dp(4),0,dp(4));frame.addView(preview,new FrameLayout.LayoutParams(-1,dp(initial)));root.addView(frame,new LinearLayout.LayoutParams(-1,dp(initial+8)));
        size=new SeekBar(this);size.setMax(100);size.setProgress(Math.round((initial-280)*100f/440));size.setContentDescription(t("调整预览大小","Adjust preview size"));size.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener(){public void onProgressChanged(SeekBar b,int v,boolean from){int h=280+Math.round(v*4.4f);frame.getLayoutParams().height=dp(h+8);frame.requestLayout();}public void onStartTrackingTouch(SeekBar b){}public void onStopTrackingTouch(SeekBar b){}});root.addView(size,new LinearLayout.LayoutParams(-1,dp(32)));
        LinearLayout orientation=new LinearLayout(this);orientation.addView(button(t("竖屏","Portrait"),()->create("portrait")),new LinearLayout.LayoutParams(0,dp(40),1));orientation.addView(button(t("横屏","Landscape"),()->create("landscape")),new LinearLayout.LayoutParams(0,dp(40),1));root.addView(orientation);
        LinearLayout tools=new LinearLayout(this);tools.setGravity(Gravity.CENTER);tools.addView(button(t("应用","App"),()->toggle(packagePanel)),new LinearLayout.LayoutParams(0,dp(40),1));tools.addView(button(t("键盘","Keyboard"),()->toggle(textPanel)),new LinearLayout.LayoutParams(0,dp(40),1));tools.addView(button(t("控件树","AI tree"),this::showTree),new LinearLayout.LayoutParams(0,dp(40),1));tools.addView(button(t("关闭","Close"),()->{VirtualScreenManager.stop(this);preview.clear();}),new LinearLayout.LayoutParams(0,dp(40),1));root.addView(tools);
        LinearLayout apps=new LinearLayout(this);apps.setOrientation(LinearLayout.HORIZONTAL);packageInput=edit(t("选择应用后会自动填入包名","Choose an app to fill its package"));apps.addView(packageInput,new LinearLayout.LayoutParams(0,dp(44),1));apps.addView(button(t("选择","Choose"),this::chooseApp),new LinearLayout.LayoutParams(dp(76),dp(44)));apps.addView(button(t("启动","Launch"),()->{String pkg=packageInput.getText().toString().trim();run(()->VirtualScreenManager.launch(this,pkg));}),new LinearLayout.LayoutParams(dp(76),dp(44)));packagePanel=apps;apps.setVisibility(View.GONE);root.addView(apps);
        LinearLayout keyboard=new LinearLayout(this);keyboard.setOrientation(LinearLayout.HORIZONTAL);textInput=edit(t("本机键盘输入（中文需无障碍）","Keyboard input (accessibility for Unicode)"));keyboard.addView(textInput,new LinearLayout.LayoutParams(0,dp(44),1));keyboard.addView(button(t("发送","Send"),()->{String text=textInput.getText().toString(),gen=generation;long seq=frameSeq;run(()->VirtualScreenManager.type(this,gen,seq,text));}),new LinearLayout.LayoutParams(dp(88),dp(44)));Button enter=button("↵",()->{String gen=generation;long seq=frameSeq;run(()->VirtualScreenManager.action(this,"key",gen,seq,"keycode=66"));});keyboard.addView(enter,new LinearLayout.LayoutParams(dp(44),dp(44)));textPanel=keyboard;keyboard.setVisibility(View.GONE);root.addView(keyboard);
        if(!VirtualScreenManager.supported(this))state.setText(com.deepseekharness.app.BuildConfig.LOW_ANDROID
                ? t("兼容版暂不支持虚拟屏，请使用标准版","Virtual screen is not available in the Low build yet; use Standard")
                : t("虚拟屏需要 Android 11 及以上","Requires Android 11 or later"));
        setContentView(root);
    }
    private void toggle(View view){view.setVisibility(view.getVisibility()==View.VISIBLE?View.GONE:View.VISIBLE);}
    private void chooseApp(){worker.execute(()->{var intent=new android.content.Intent(android.content.Intent.ACTION_MAIN).addCategory(android.content.Intent.CATEGORY_LAUNCHER);var apps=getPackageManager().queryIntentActivities(intent,0);apps.sort(java.util.Comparator.comparing(x->x.loadLabel(getPackageManager()).toString()));String[] labels=new String[apps.size()];for(int i=0;i<apps.size();i++)labels[i]=apps.get(i).loadLabel(getPackageManager())+"\n"+apps.get(i).activityInfo.packageName;main.post(()->new com.deepseekharness.app.ui.DshaDialogBuilder(this).setTitle(t("选择已安装应用","Choose installed app")).setItems(labels,(d,i)->packageInput.setText(apps.get(i).activityInfo.packageName)).setNegativeButton(t("取消","Cancel"),null).show());});}
    private void showTree(){worker.execute(()->{JSONObject r=VirtualScreenManager.tree(this);main.post(()->new com.deepseekharness.app.ui.DshaDialogBuilder(this).setTitle(t("控件树（给 AI 使用）","Accessibility tree for AI")).setMessage(r.toString()).setPositiveButton(t("关闭","Close"),null).show());});}
    private EditText edit(String hint){EditText e=new EditText(this);e.setSingleLine(true);e.setTextSize(12);e.setHint(hint);e.setMinHeight(dp(44));e.setPadding(dp(8),0,dp(8),0);return e;}
    private TextView label(String text,int sp){TextView v=new TextView(this);v.setText(text);v.setTextSize(sp);v.setTextColor(getColor(R.color.text));v.setPadding(0,dp(3),0,dp(3));return v;}
    private Button button(String text,Runnable click){Button b=new androidx.appcompat.widget.AppCompatButton(this);b.setText(text);b.setAllCaps(false);b.setTextSize(12);b.setMinHeight(dp(40));b.setPadding(dp(5),0,dp(5),0);b.setBackgroundResource(R.drawable.bg_btn);b.setTextColor(getColorStateList(R.color.button_text));b.setOnClickListener(v->click.run());return b;}
    private void create(String orientation){run(()->VirtualScreenManager.start(this,orientation));}
    private interface Work {JSONObject get()throws Exception;}
    private void run(Work action){if(busy)return;busy=true;worker.execute(()->{JSONObject result;try{result=action.get();}catch(Exception e){result=new JSONObject();try{result.put("error",e.getClass().getSimpleName());}catch(Exception ignored){}}JSONObject out=result;main.post(()->{busy=false;if(isDestroyed())return;state.setText(out.optBoolean("ok")?t("操作完成","Done"):message(out.optString("error")));if(visible){main.removeCallbacks(poll);main.post(poll);}});});}
    private void readFrame(){if(!visible||polling||preview==null)return;polling=true;worker.execute(()->{long at=SystemClock.elapsedRealtime();JSONObject value=VirtualScreenManager.previewSince(this,generation,frameSeq);Bitmap bitmap=VirtualScreenManager.previewBitmap(value);long latency=SystemClock.elapsedRealtime()-at;main.post(()->{polling=false;if(isDestroyed()||!visible){if(bitmap!=null)bitmap.recycle();return;}if(bitmap!=null){preview.frame(bitmap,value);generation=value.optString("generation");frameSeq=value.optLong("frameSeq");detail.setText(value.optString("package")+" · "+value.optInt("width")+"×"+value.optInt("height")+" · "+VirtualScreenManager.channel()+" · "+latency+" ms");}main.postDelayed(poll,250);});});}
    private String message(String code){if(code.equals("STALE_FRAME")||code.equals("STALE_GENERATION"))return t("画面已变化，请看最新画面后再操作","The view changed. Observe again.");if(code.equals("DEVICE_CHANNEL_UNAVAILABLE"))return t("请先在设备能力授权中连接通道","Connect a device channel first");if(code.equals("ACCESSIBILITY_REQUIRED_FOR_UNICODE"))return t("中文输入需要开启无障碍服务","Enable accessibility for Unicode input");return t("未完成：","Not completed: ")+code;}
    private int dp(int value){return Math.round(value*getResources().getDisplayMetrics().density);}
    @Override protected void onResume(){super.onResume();visible=true;main.post(poll);}
    @Override protected void onPause(){visible=false;main.removeCallbacks(poll);super.onPause();}
    @Override protected void onDestroy(){visible=false;main.removeCallbacksAndMessages(null);if(preview!=null)preview.cancelStream();worker.shutdown();super.onDestroy();}
}

