package com.deepseekharness.app.ui;

import android.app.*;
import android.content.*;
import android.os.Bundle;
import android.widget.*;
import com.deepseekharness.app.R;
import com.deepseekharness.app.core.ConfigStore;
import com.deepseekharness.app.util.Constants;
import com.deepseekharness.app.util.EnvironmentTaskGate;
import java.io.InputStream;

/** 原生配置表单回归：无效输入不得写入另一字段；保存不产生静默失败。 */
public final class ConfigurationAuditInstrumentation extends Instrumentation {
    private int checks;
    @Override public void onCreate(Bundle args) { super.onCreate(args);start(); }
    private void check(boolean ok,String why){checks++;if(!ok)throw new AssertionError(why);}
    private void ui(Runnable work) {
        Throwable[] error={null};runOnMainSync(()->{try{work.run();}catch(Throwable t){error[0]=t;}});
        if(error[0]!=null)throw new AssertionError("配置界面断言失败",error[0]);
    }
    @Override public void onStart() {
        Context app=getTargetContext().getApplicationContext();Bundle out=new Bundle();
        SharedPreferences prefs=app.getSharedPreferences(Constants.PREFS,0);
        ConfigStore config=new ConfigStore(app);String key=config.getApiKey();
        Object stored=prefs.getAll().get(Constants.KEY_API_KEY);String port=config.getPort();
        FragmentSessionTestActivity page=null;
        try {
            check(!EnvironmentTaskGate.isBusy(),"有真实环境任务，请稍后测试");
            try(android.os.ParcelFileDescriptor fd=getUiAutomation().executeShellCommand("am start -W -n com.dsh.client/com.deepseekharness.app.ui.MainActivity");
                InputStream in=new android.os.ParcelFileDescriptor.AutoCloseInputStream(fd)){while(in.read()!=-1){}}
            ActivityMonitor monitor=addMonitor(FragmentSessionTestActivity.class.getName(),null,false);
            runOnMainSync(()->app.startActivity(new Intent(app,FragmentSessionTestActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)));
            page=(FragmentSessionTestActivity)waitForMonitorWithTimeout(monitor,15000);removeMonitor(monitor);
            check(page!=null,"配置测试宿主未打开");
            FragmentSessionTestActivity host=page;
            ui(()->host.getSupportFragmentManager().beginTransaction().replace(R.id.fragment_container,new ConfigFragment()).commitNow());
            EditText api=host.findViewById(R.id.config_api_key),input=host.findViewById(R.id.config_port);
            ui(()->{api.setText("AUDIT_UNSAVED");input.setText("3090");host.findViewById(R.id.config_save).performClick();});
            check(input.getError()!=null,"占用桥接端口没有字段错误");
            check(config.getPort().equals(port)&&config.getApiKey().equals(key),"无效端口导致其他配置被保存");
            ui(()->input.setText(port));
            try(EnvironmentTaskGate.Lease held=EnvironmentTaskGate.tryAcquire("配置互斥验收")) {
                check(held!=null,"无法取得测试互斥");
                ui(()->host.findViewById(R.id.config_save).performClick());
                check(config.getApiKey().equals(key),"环境任务期间配置仍被写入");
            }
            long began=android.os.SystemClock.elapsedRealtime();
            ui(()->{api.setText(key);host.findViewById(R.id.config_save).performClick();});
            out.putLong("save_ms",android.os.SystemClock.elapsedRealtime()-began);
            check(config.getApiKey().equals(key)&&config.getPort().equals(port),"有效保存改变了原值");
            check(input.getError()==null,"保存成功后旧错误仍显示");
            check(((TextView)host.findViewById(R.id.config_guard_status)).getText().toString().contains("已同步"),"设备授权同步缺少结果");
            check(!host.findViewById(R.id.config_translate).isEnabled(),"未提供的翻译仍是可点击设置");
            check(!EnvironmentTaskGate.isBusy(),"配置保存未释放环境锁");
            out.putString("result","PASS");out.putInt("checks",checks);
        } catch(Throwable e) {out.putString("failure",android.util.Log.getStackTraceString(e));}
        finally {
            config.setPort(port);
            SharedPreferences.Editor edit=prefs.edit();if(stored==null)edit.remove(Constants.KEY_API_KEY);else edit.putString(Constants.KEY_API_KEY,(String)stored);edit.commit();
            if(page!=null){Activity closing=page;runOnMainSync(closing::finish);}
            finish(out.containsKey("failure")?1:0,out);
        }
    }
}
