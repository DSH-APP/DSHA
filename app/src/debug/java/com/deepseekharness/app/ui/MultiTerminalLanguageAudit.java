package com.deepseekharness.app.ui;

import android.app.*;
import android.content.*;
import android.os.Bundle;
import android.widget.*;
import com.deepseekharness.app.R;
import com.deepseekharness.app.PtySession;
import com.deepseekharness.app.core.ConfigStore;
import com.deepseekharness.app.core.HarnessController;
import com.deepseekharness.app.util.TerminalTabs;
import com.deepseekharness.app.util.TerminalSession;
import com.deepseekharness.app.util.Constants;
import java.lang.reflect.Field;
import java.util.*;

/** 非调试包验证独立真实终端和语言重建；只关闭本次创建的会话，不读取或修改用户命令。 */
public final class MultiTerminalLanguageAudit extends Instrumentation {
    private FragmentSessionTestActivity page;
    private Bundle args;
    private int checks;
    private final List<TerminalTabs.Tab<PtySession>> ownedPty=new ArrayList<>();
    private final List<TerminalTabs.Tab<Object>> ownedSimple=new ArrayList<>();
    private final List<String> evidence=new ArrayList<>();
    @Override public void onCreate(Bundle value){super.onCreate(value);args=value;start();}
    @Override public void callActivityOnCreate(Activity activity,Bundle value){super.callActivityOnCreate(activity,value);
        activity.getWindow().addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON|android.view.WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON);}
    private void check(boolean ok,String why){checks++;if(!ok)throw new AssertionError(why);}
    private void ui(Runnable action){Throwable[] error={null};runOnMainSync(()->{try{action.run();}catch(Throwable t){error[0]=t;}});if(error[0]!=null)throw new AssertionError(error[0]);}
    private static Object field(Object target,String name)throws Exception{
        Field f=(target instanceof Class?(Class<?>)target:target.getClass()).getDeclaredField(name);f.setAccessible(true);return f.get(target instanceof Class?null:target);
    }
    @SuppressWarnings("unchecked") private static <T> TerminalTabs<T> tabs(Class<?> type)throws Exception{return (TerminalTabs<T>)field(type,"sessions");}
    private void mount(androidx.fragment.app.Fragment fragment){ui(()->page.getSupportFragmentManager().beginTransaction().replace(R.id.fragment_container,fragment).commitNow());waitForIdleSync();}
    private void click(int id){ui(()->check(page.findViewById(id).performClick(),"控件未接受点击："+id));waitForIdleSync();}
    private <T> void tabAction(TerminalTabs<T> tabs,long id,boolean close){
        int index=-1;var snapshot=tabs.snapshot();for(int i=0;i<snapshot.size();i++)if(snapshot.get(i).id==id)index=i;
        check(index>=0,"标签不存在");final int at=index;
        ui(()->check(((LinearLayout)((LinearLayout)page.findViewById(R.id.terminal_tabs)).getChildAt(at)).getChildAt(close?1:0).performClick(),"标签操作未执行"));waitForIdleSync();
    }
    private <T> void waitRemoved(TerminalTabs<T> tabs,long id)throws Exception{
        long end=System.currentTimeMillis()+20000;while(tabs.find(id)!=null&&System.currentTimeMillis()<end)Thread.sleep(100);check(tabs.find(id)==null,"关闭未确认或标签未移除："+id);
    }
    private String transcript(PtySession session){String[] text={""};ui(()->text[0]=session.session().getEmulator().getScreen().getTranscriptText());return text[0];}
    private void waitPty(PtySession session,String marker)throws Exception{
        long end=System.currentTimeMillis()+15000;while(!transcript(session).contains(marker)&&System.currentTimeMillis()<end)Thread.sleep(100);check(transcript(session).contains(marker),"真实 PTY 命令未产生预期回执："+marker);
    }
    private void language(String id)throws Exception{
        ConfigStore config=new ConfigStore(getTargetContext());if(id.equals(config.getUiLanguage()))return;
        ActivityMonitor monitor=addMonitor(FragmentSessionTestActivity.class.getName(),null,false);
        ui(()->LanguageController.select(page,id));
        FragmentSessionTestActivity next=(FragmentSessionTestActivity)waitForMonitorWithTimeout(monitor,20000);removeMonitor(monitor);
        check(next!=null,"切换语言未重建活动界面");page=next;waitForIdleSync();Thread.sleep(300);
        check(id.equals(page.getResources().getConfiguration().getLocales().get(0).getLanguage()),"资源语言未更新");
    }
    private void pty()throws Exception{
        TerminalTabs<PtySession> tabs=tabs(PtyTerminalFragment.class);Set<Long> before=new HashSet<>();for(var t:tabs.snapshot())before.add(t.id);
        mount(new PtyTerminalFragment());
        if(tabs.current()==null||before.contains(tabs.current().id))click(R.id.terminal_new);
        ownedPty.add(tabs.current());click(R.id.terminal_new);ownedPty.add(tabs.current());click(R.id.terminal_new);ownedPty.add(tabs.current());
        check(new HashSet<>(Arrays.asList(ownedPty.get(0).value.session().getPid(),ownedPty.get(1).value.session().getPid(),ownedPty.get(2).value.session().getPid())).size()==3,"PTY 没有三个独立进程");
        String[] dirs={"/tmp","/var/tmp","/root"};
        for(int i=0;i<3;i++){final int n=i;ui(()->ownedPty.get(n).value.write("unset HISTFILE; export DSHA_MULTI=P"+n+"; cd "+dirs[n]+"; printf 'MULTI_READY=%s:%s\\n' \"$DSHA_MULTI\" \"$PWD\"\n"));waitPty(ownedPty.get(i).value,"MULTI_READY=P"+i+":"+dirs[i]);}
        tabAction(tabs,ownedPty.get(1).id,false);
        int[] pids=ownedPty.stream().mapToInt(t->t.value.session().getPid()).toArray();language("en");
        check(((TextView)page.findViewById(R.id.terminal_new)).getText().toString().contains("New"),"英文新建按钮未显示");
        for(int i=0;i<3;i++)check(ownedPty.get(i).value.isRunning()&&ownedPty.get(i).value.session().getPid()==pids[i],"语言切换重启了 PTY");
        check(tabs.current().id==ownedPty.get(1).id,"语言切换丢失当前 PTY 标签");
        tabAction(tabs,ownedPty.get(0).id,true);waitRemoved(tabs,ownedPty.get(0).id);
        check(ownedPty.get(1).value.isRunning()&&ownedPty.get(2).value.isRunning(),"关闭一个 PTY 影响了其他会话");
        ui(()->ownedPty.get(1).value.write("printf 'AFTER_CLOSE=%s:%s\\n' \"$DSHA_MULTI\" \"$PWD\"\n"));waitPty(ownedPty.get(1).value,"AFTER_CLOSE=P1:/var/tmp");
        language("zh");check(((TextView)page.findViewById(R.id.terminal_new)).getText().toString().contains("新建"),"返回中文失败");
        for(int i=1;i<3;i++){tabAction(tabs,ownedPty.get(i).id,true);waitRemoved(tabs,ownedPty.get(i).id);}
        if(before.isEmpty()){check(tabs.current()==null,"关闭最后一个 PTY 后仍选中会话");check(page.findViewById(R.id.pty_empty).getVisibility()==android.view.View.VISIBLE,"未展示空终端状态");}
        if(before.isEmpty()) {
            click(R.id.terminal_new);var fresh=tabs.current();ownedPty.add(fresh);
            check(fresh.number==1 && fresh.id>ownedPty.get(2).id,"重新打开 PTY 应显示 1 且身份不复用");
            check(((TextView)((LinearLayout)((LinearLayout)page.findViewById(R.id.terminal_tabs)).getChildAt(0)).getChildAt(0)).getText().toString().equals("终端 1"),"PTY 显示编号没有归一");
            tabAction(tabs,fresh.id,true);waitRemoved(tabs,fresh.id);
        }
        evidence.add("PTY：三个真实进程、独立 cwd/export/回滚缓冲、关闭一个不影响另两个、语言双向切换保留 PID 与选中标签");
    }
    private String simpleOutput(Object value)throws Exception{return field(value,"buffer").toString();}
    private TerminalSession simpleSession(Object value)throws Exception{return (TerminalSession)field(value,"session");}
    private void waitSimpleReady(Object value)throws Exception{long end=System.currentTimeMillis()+20000;while(simpleSession(value).state()!=TerminalSession.State.READY&&System.currentTimeMillis()<end)Thread.sleep(100);check(simpleSession(value).state()==TerminalSession.State.READY,"简易终端尚未就绪："+simpleState(value));}
    private String simpleState(Object value)throws Exception{TerminalSession session=simpleSession(value);Object active=field(session,"active");return session.state()+" group="+(active==null?"none":field(active,"group"));}
    private void waitSimple(Object value,String marker)throws Exception{long end=System.currentTimeMillis()+15000;while(!simpleOutput(value).contains(marker)&&System.currentTimeMillis()<end)Thread.sleep(100);
        String output=com.deepseekharness.app.util.SensitiveData.redact(simpleOutput(value));
        check(output.contains(marker),"简易终端命令未执行："+marker+"; "+simpleState(value)+"\n"+output.substring(Math.max(0,output.length()-4000)));}
    private void simple()throws Exception{
        TerminalTabs<Object> tabs=tabs(TerminalFragment.class);Set<Long> before=new HashSet<>();for(var t:tabs.snapshot())before.add(t.id);
        mount(new TerminalFragment());if(tabs.current()==null||before.contains(tabs.current().id))click(R.id.terminal_new);
        ownedSimple.add(tabs.current());waitSimpleReady(tabs.current().value);waitForIdleSync();click(R.id.terminal_new);ownedSimple.add(tabs.current());
        check(ownedSimple.get(0).id!=ownedSimple.get(1).id&&ownedSimple.get(0).value!=ownedSimple.get(1).value,"新建未产生独立的简易终端标签");
        waitSimpleReady(ownedSimple.get(1).value);
        for(int i=0;i<2;i++){simpleSession(ownedSimple.get(i).value).submit("export DSHA_MULTI=S"+i+"; cd /tmp; printf 'SIMPLE_READY=%s:%s\\n' \"$DSHA_MULTI\" \"$PWD\"");waitSimple(ownedSimple.get(i).value,"SIMPLE_READY=S"+i+":/tmp");}
        evidence.add("Simple before language: "+simpleState(ownedSimple.get(0).value)+" / "+simpleState(ownedSimple.get(1).value));
        String draft="echo 用户自己的中文命令";ui(()->{EditText input=page.findViewById(R.id.term_input);input.setText(draft);input.setSelection(6);});
        tabAction(tabs,ownedSimple.get(0).id,false);language("en");tabAction(tabs,ownedSimple.get(1).id,false);
        evidence.add("Simple after language: "+simpleState(ownedSimple.get(0).value)+" / "+simpleState(ownedSimple.get(1).value));
        ui(()->{EditText input=page.findViewById(R.id.term_input);check(draft.equals(input.getText().toString()),"语言切换或标签切换改变了命令草稿");check(input.getSelectionStart()==6,"命令光标未保留");});
        tabAction(tabs,ownedSimple.get(0).id,true);waitRemoved(tabs,ownedSimple.get(0).id);
        evidence.add("Simple after closing first: "+simpleState(ownedSimple.get(1).value));
        simpleSession(ownedSimple.get(1).value).submit("printf 'SIMPLE_SURVIVES=%s\\n' \"$DSHA_MULTI\"");waitSimple(ownedSimple.get(1).value,"SIMPLE_SURVIVES=S1");
        tabAction(tabs,ownedSimple.get(1).id,true);waitRemoved(tabs,ownedSimple.get(1).id);
        if(before.isEmpty())check(tabs.current()==null&&!page.findViewById(R.id.term_input).isEnabled(),"简易终端关闭后仍可输入");
        if(before.isEmpty()) {
            click(R.id.terminal_new);var fresh=tabs.current();ownedSimple.add(fresh);waitSimpleReady(fresh.value);
            check(fresh.number==1 && fresh.id>ownedSimple.get(1).id,"重新打开简易终端应显示 1 且身份不复用");
            tabAction(tabs,fresh.id,true);waitRemoved(tabs,fresh.id);
        }
        evidence.add("简易终端：两个独立 shell、输出分离、中文命令草稿与光标跨语言保留、单独关闭和线程释放");
    }
    @Override public void onStart(){
        Bundle result=new Bundle();Context app=getTargetContext();var prefs=app.getSharedPreferences(Constants.PREFS,0);boolean had=prefs.contains("ui_language");String original=new ConfigStore(app).getUiLanguage();
        try{
            try(var fd=getUiAutomation().executeShellCommand("am start -W -n com.dsh.client/com.deepseekharness.app.ui.MainActivity");var input=new android.os.ParcelFileDescriptor.AutoCloseInputStream(fd)){while(input.read()!=-1){}}
            long ready=System.currentTimeMillis()+120000;while((!HarnessController.get(app).proot().isEnvironmentReady()||com.deepseekharness.app.BackupManager.isEnvironmentTaskBusy())&&System.currentTimeMillis()<ready)Thread.sleep(200);
            check(HarnessController.get(app).proot().isEnvironmentReady(),"环境尚未就绪");
            check(!com.deepseekharness.app.BackupManager.isEnvironmentTaskBusy(),"维护尚未安全收尾");
            ActivityMonitor monitor=addMonitor(FragmentSessionTestActivity.class.getName(),null,false);
            ui(()->app.startActivity(new Intent(app,FragmentSessionTestActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)));
            page=(FragmentSessionTestActivity)waitForMonitorWithTimeout(monitor,15000);removeMonitor(monitor);check(page!=null,"测试宿主未打开");
            pty();simple();result.putString("result","PASS");result.putInt("checks",checks);result.putString("evidence",String.join("\n",evidence));
        }catch(Throwable error){result.putString("failure",android.util.Log.getStackTraceString(error));}
        finally{
            result.putString("evidence",String.join("\n",evidence));
            try{TerminalTabs<PtySession> p=tabs(PtyTerminalFragment.class);for(var t:ownedPty)if(t!=null){t.value.finishAndWait(5000);p.remove(t.id);}
                TerminalTabs<Object> s=tabs(TerminalFragment.class);for(var t:ownedSimple)if(t!=null&&simpleSession(t.value).disposeAndWait(5000))s.remove(t.id);
            }catch(Exception error){result.putString("cleanup",error.toString());}
            if(page!=null)ui(page::finish);
            ui(()->{if(had)prefs.edit().putString("ui_language",original).commit();else prefs.edit().remove("ui_language").commit();LanguageController.apply(app);});
            finish(result.containsKey("failure")?1:0,result);
        }
    }
}
