package com.deepseekharness.app.ui;

import android.app.*;
import android.content.Intent;
import android.os.Bundle;
import android.widget.TextView;
import com.deepseekharness.app.R;
import com.deepseekharness.app.core.PluginRepository;

/** 真实插件页面触发检测，核验列表已同步；不安装或启停第三方插件。 */
public final class PluginListAudit extends Instrumentation {
    @Override public void onCreate(Bundle args) { super.onCreate(args); start(); }
    @Override public void onStart() {
        Bundle result=new Bundle(); FragmentSessionTestActivity page=null;
        try {
            try(android.os.ParcelFileDescriptor fd=getUiAutomation().executeShellCommand("am start -W -n com.dsh.client/com.deepseekharness.app.ui.MainActivity");
                java.io.InputStream in=new android.os.ParcelFileDescriptor.AutoCloseInputStream(fd)){while(in.read()!=-1){}}
            ActivityMonitor monitor=addMonitor(FragmentSessionTestActivity.class.getName(),null,false);
            runOnMainSync(()->getTargetContext().startActivity(new Intent(getTargetContext(),FragmentSessionTestActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)));
            page=(FragmentSessionTestActivity)waitForMonitorWithTimeout(monitor,15000);removeMonitor(monitor);
            if(page==null)throw new AssertionError("插件测试宿主未打开");
            FragmentSessionTestActivity host=page;PluginFragment fragment=new PluginFragment();
            runOnMainSync(()->host.getSupportFragmentManager().beginTransaction().replace(R.id.fragment_container,fragment).commitNow());
            PluginRepository[] repository={null};
            runOnMainSync(()->repository[0]=new androidx.lifecycle.ViewModelProvider(host).get(PluginRepository.class));
            long deadline=System.currentTimeMillis()+90000;
            while((repository[0].isBusy()||repository[0].state().getValue().items.isEmpty())&&System.currentTimeMillis()<deadline)Thread.sleep(200);
            if(repository[0].isBusy())throw new AssertionError("初次检测尚未结束");
            runOnMainSync(()->fragment.requireView().findViewById(R.id.btnRefresh).performClick());
            deadline=System.currentTimeMillis()+90000;while(repository[0].isBusy()&&System.currentTimeMillis()<deadline)Thread.sleep(200);waitForIdleSync();
            PluginRepository.State state=repository[0].state().getValue();
            if(state.busy||!state.message.startsWith("插件检测完成")||state.message.contains("列表未能同步"))throw new AssertionError(state.message);
            int builtins=0;for(PluginRepository.Item item:state.items)if(item.builtin){builtins++;if(!item.available)throw new AssertionError("内置组件不可用："+item.name);}
            if(builtins!=4)throw new AssertionError("内置组件数量错误："+builtins);
            String[] shown={null};runOnMainSync(()->shown[0]=((TextView)fragment.requireView().findViewById(R.id.statusText)).getText().toString());
            if(!shown[0].equals(state.message))throw new AssertionError("页面未显示实际检测结果");
            result.putString("result","PASS：真实页面初次检测及手动刷新成功，四项内置组件可用，列表已同步");
        }catch(Throwable error){result.putString("failure",android.util.Log.getStackTraceString(error));}
        finally{if(page!=null){FragmentSessionTestActivity host=page;runOnMainSync(host::finish);}finish(result.containsKey("failure")?1:0,result);}
    }
}
