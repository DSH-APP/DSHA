package com.deepseekharness.app.ui;

import android.app.*;
import android.content.*;
import android.graphics.*;
import android.os.*;
import android.view.*;
import android.widget.*;
import com.deepseekharness.app.R;
import com.deepseekharness.app.core.ConfigStore;
import java.util.*;

/** 用长文本和真实触摸验证内外滚动；关于入口只截获 Intent，不打开外部应用。 */
public final class LogPanelAudit extends Instrumentation {
    private int checks;
    private LayoutPreviewActivity page;
    @Override public void onCreate(Bundle args){super.onCreate(args);start();}
    @Override public void callActivityOnCreate(Activity activity,Bundle state){super.callActivityOnCreate(activity,state);activity.getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON|WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON|WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED);}
    private void check(boolean value,String message){checks++;if(!value)throw new AssertionError(message);}
    private void ui(Runnable work){Throwable[] error={null};runOnMainSync(()->{try{work.run();}catch(Throwable e){error[0]=e;}});if(error[0]!=null)throw new AssertionError(error[0]);waitForIdleSync();}
    private int dp(float value){return Math.round(value*page.getResources().getDisplayMetrics().density);}
    private void swipe(View view,boolean upward)throws Exception{
        Rect rect=new Rect();ui(()->check(view.getGlobalVisibleRect(rect)&&rect.height()>dp(40),"日志框不在可触摸区域"));
        float x=rect.left+rect.width()*0.4f,from=rect.top+rect.height()*(upward?0.78f:0.22f),to=rect.top+rect.height()*(upward?0.22f:0.78f);
        long down=SystemClock.uptimeMillis();sendPointerSync(MotionEvent.obtain(down,down,MotionEvent.ACTION_DOWN,x,from,0));
        for(int i=1;i<=12;i++){Thread.sleep(24);sendPointerSync(MotionEvent.obtain(down,SystemClock.uptimeMillis(),MotionEvent.ACTION_MOVE,x,from+(to-from)*i/12f,0));}
        Thread.sleep(150);sendPointerSync(MotionEvent.obtain(down,SystemClock.uptimeMillis(),MotionEvent.ACTION_MOVE,x,to,0));sendPointerSync(MotionEvent.obtain(down,SystemClock.uptimeMillis(),MotionEvent.ACTION_UP,x,to,0));Thread.sleep(250);waitForIdleSync();
    }
    private void screenshot(View view,String name)throws Exception{
        ui(()->{
            Bitmap image=Bitmap.createBitmap(view.getWidth(),view.getHeight(),Bitmap.Config.ARGB_8888);Canvas canvas=new Canvas(image);canvas.translate(-view.getScrollX(),-view.getScrollY());view.draw(canvas);
            java.io.File folder=new java.io.File(getTargetContext().getCacheDir(),"log-panel-audit");folder.mkdirs();
            try(var out=new java.io.FileOutputStream(new java.io.File(folder,name+".png"))){image.compress(Bitmap.CompressFormat.PNG,100,out);}catch(Exception e){throw new RuntimeException(e);}finally{image.recycle();}
        });
    }
    private void logs(String scene,int outerId,int innerId,int textId,String prefix)throws Exception{
        ui(()->page.showScene(scene));TextView text=page.findViewById(textId);LogScrollView inner=page.findViewById(innerId);ScrollView outer=page.findViewById(outerId);
        StringBuilder data=new StringBuilder();for(int i=0;i<220;i++)data.append(String.format(Locale.ROOT,"LINE %03d · sample diagnostic output\n",i));String original=data.toString();
        ui(()->{text.setText(original);outer.setSmoothScrollingEnabled(false);});
        ui(()->{Rect bounds=new Rect(0,0,inner.getWidth(),inner.getHeight());outer.offsetDescendantRectToMyCoords(inner,bounds);outer.scrollBy(0,bounds.top-dp(8));inner.scrollTo(0,dp(400));});
        check(inner.getHeight()<=dp(220)&&inner.getHeight()>=dp(144),"日志没有限制高度");
        check(text.getHeight()>inner.getHeight()*3,"测试文本不足以验证滚动");
        int outside=outer.getScrollY(),inside=inner.getScrollY();swipe(inner,true);
        check(inner.getScrollY()>inside,"手指上滑没有滚动日志");check(outer.getScrollY()==outside,"外层页面抢走日志滑动");
        inside=inner.getScrollY();swipe(inner,false);check(inner.getScrollY()<inside,"手指下滑没有滚动日志");check(outer.getScrollY()==outside,"反向滚动带动了外层页面");
        check(!inner.shouldFollowEnd(),"阅读历史日志时不应跟随新输出");int reading=inner.getScrollY();ui(()->text.append("LAST MARKER\n"));check(inner.getScrollY()==reading,"追加文本改变阅读位置");
        ui(()->inner.scrollTo(0,text.getHeight()));check(inner.shouldFollowEnd(),"末尾跟随未启用");
        ui(()->{text.append("TAIL ONE\nTAIL TWO\n");inner.followEndAfterLayout();});Thread.sleep(100);waitForIdleSync();check(!inner.canScrollVertically(1),"新增日志没有跟随到末尾");check(outer.getScrollY()==outside,"追加日志把外层页面拉走");
        ui(()->inner.scrollTo(0,0));outside=outer.getScrollY();check(outside>0,"页面没有边缘接力空间");swipe(inner,false);check(outer.getScrollY()<outside,"日志到顶部后无法继续滚动页面");
        check(text.getText().toString().equals(original+"LAST MARKER\nTAIL ONE\nTAIL TWO\n"),"报告内容被裁剪或改写");check(text.isTextSelectable(),"日志失去长按复制能力");
        ui(()->{outer.scrollTo(0,outer.getChildAt(0).getHeight());inner.scrollTo(0,0);});screenshot(page.canvas,prefix+"-"+scene);
    }
    private void about(String prefix)throws Exception{
        List<Intent> intents=new ArrayList<>();Context context=new ContextWrapper(page){@Override public void startActivity(Intent intent){intents.add(intent);}};
        androidx.appcompat.app.AlertDialog[] dialog={null};
        try{
            ui(()->dialog[0]=AboutDialog.show(context));Thread.sleep(220);waitForIdleSync();
            View root=dialog[0].getWindow().getDecorView(),close=root.findViewById(R.id.about_close);ScrollView content=root.findViewById(R.id.about_content_scroll);
            check(close.getParent()!=root.findViewById(R.id.about_github).getParent(),"关闭按钮仍与外部入口混在同一排");
            int[] before=new int[2],after=new int[2];ui(()->{close.getLocationOnScreen(before);content.scrollTo(0,content.getChildAt(0).getHeight());close.getLocationOnScreen(after);});
            check(Arrays.equals(before,after),"滚动关于信息时关闭按钮被带走");check(close.getWidth()>root.getWidth()*0.6,"关闭按钮没有独立占据底部操作区");
            ui(()->root.findViewById(R.id.about_github).performClick());ui(()->root.findViewById(R.id.about_qq).performClick());
            check(intents.size()==2&&AboutDialog.GITHUB_URL.equals(intents.get(0).getDataString())&&intents.get(1).getDataString().contains(AboutDialog.QQ_GROUP),"链接卡片没有打开原入口");
            screenshot(root,prefix+"-about");ui(close::performClick);check(!dialog[0].isShowing(),"关闭入口失效");
        }finally{if(dialog[0]!=null)ui(()->dialog[0].dismiss());}
    }
    @Override public void onStart(){
        Bundle result=new Bundle();ConfigStore store=new ConfigStore(getTargetContext());String language=store.getUiLanguage(),theme=store.getUiTheme();
        try{
            try(var fd=getUiAutomation().executeShellCommand("am start -W -n com.dsh.client/com.deepseekharness.app.ui.MainActivity");var input=new ParcelFileDescriptor.AutoCloseInputStream(fd)){while(input.read()!=-1){}}
            long ready=SystemClock.elapsedRealtime()+120000;var controller=com.deepseekharness.app.core.HarnessController.get(getTargetContext());
            while((!controller.isEnvironmentReady()||com.deepseekharness.app.BackupManager.isEnvironmentTaskBusy())&&SystemClock.elapsedRealtime()<ready)Thread.sleep(100);
            check(controller.isEnvironmentReady(),"环境尚未更新完成");
            ui(()->{store.setUiTheme("light");ThemeController.apply(getTargetContext());});
            for(String lang:new String[]{"zh","en"})for(boolean compact:new boolean[]{false,true}){
                ui(()->LanguageController.select(getTargetContext(),lang));Thread.sleep(300);
                LayoutAuditInstrumentation.width=compact?320:360;LayoutAuditInstrumentation.height=compact?400:720;LayoutAuditInstrumentation.scale=compact?1.3f:1f;
                page=(LayoutPreviewActivity)startActivitySync(new Intent(getTargetContext(),LayoutPreviewActivity.class).setAction("logs."+System.nanoTime()).putExtra("scene","fragment_install").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));waitForIdleSync();
                String prefix=lang+(compact?"-compact":"-normal");
                Bundle phase=new Bundle();phase.putString("phase",prefix);sendStatus(1,phase);
                logs("fragment_install",R.id.install_scroll,R.id.install_log_scroll,R.id.install_log,prefix);
                logs("activity_diagnostics",R.id.diagnostic_page_scroll,R.id.diagnostic_report_scroll,R.id.diagnostic_report,prefix);about(prefix);ui(page::finish);page=null;
            }
            result.putString("result","PASS");result.putInt("checks",checks);
        }catch(Throwable error){result.putString("failure",android.util.Log.getStackTraceString(error));}
        finally{if(page!=null)ui(page::finish);ui(()->{store.setUiTheme(theme);ThemeController.apply(getTargetContext());LanguageController.select(getTargetContext(),language);});finish(result.containsKey("failure")?1:0,result);}
    }
}
