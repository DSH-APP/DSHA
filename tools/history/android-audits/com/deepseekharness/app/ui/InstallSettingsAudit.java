package com.deepseekharness.app.ui;

import android.app.*;
import android.content.*;
import android.os.Bundle;
import android.widget.*;
import androidx.fragment.app.Fragment;
import com.deepseekharness.app.R;
import com.deepseekharness.app.core.ConfigStore;
import com.deepseekharness.app.core.InstallRepository;
import com.deepseekharness.app.util.Constants;
import com.deepseekharness.app.util.EnvironmentTaskGate;
import com.deepseekharness.app.util.InstallTask;
import java.lang.reflect.*;

/** 发布构建中验证真实 Fragment 的滚动与备份开关；只写模拟日志和可恢复的布尔偏好。 */
public final class InstallSettingsAudit extends Instrumentation {
    private FragmentSessionTestActivity page;
    private int checks;
    @Override public void onCreate(Bundle args) { super.onCreate(args); start(); }
    private void check(boolean ok, String reason) { checks++; if (!ok) throw new AssertionError(reason); }
    private void ui(Runnable action) {
        Throwable[] error={null};runOnMainSync(()->{try{action.run();}catch(Throwable t){error[0]=t;}});
        if(error[0]!=null)throw new AssertionError(error[0]);
    }
    private void attach(Fragment fragment) {
        ui(()->page.getSupportFragmentManager().beginTransaction().replace(R.id.fragment_container,fragment).commitNow());
    }
    private void render(InstallFragment fragment) throws Exception {
        Method method=InstallFragment.class.getDeclaredMethod("render");method.setAccessible(true);
        ui(()->{try{method.invoke(fragment);}catch(Exception e){throw new IllegalStateException(e);}});
        Thread.sleep(700);waitForIdleSync();
    }
    @Override public void onStart() {
        Bundle result=new Bundle();Context app=getTargetContext().getApplicationContext();
        SharedPreferences prefs=app.getSharedPreferences(Constants.PREFS,0);
        boolean existed=prefs.contains(Constants.KEY_BACKUP_KEY);ConfigStore config=new ConfigStore(app);
        boolean original=config.isBackupKey();
        try {
            check(!EnvironmentTaskGate.isBusy(),"真实环境任务尚未结束");
            try(android.os.ParcelFileDescriptor fd=getUiAutomation().executeShellCommand(
                    "am start -W -n com.dsh.client/com.deepseekharness.app.ui.MainActivity --ez limited_entry true");
                java.io.InputStream in=new android.os.ParcelFileDescriptor.AutoCloseInputStream(fd)){while(in.read()!=-1){}}
            ActivityMonitor monitor=addMonitor(FragmentSessionTestActivity.class.getName(),null,false);
            ui(()->app.startActivity(new Intent(app,FragmentSessionTestActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)));
            page=(FragmentSessionTestActivity)waitForMonitorWithTimeout(monitor,15000);removeMonitor(monitor);
            check(page!=null,"测试宿主未打开");
            Constructor<InstallRepository> ctor=InstallRepository.class.getDeclaredConstructor(Context.class);ctor.setAccessible(true);
            InstallRepository isolated=ctor.newInstance(app);
            Field taskField=InstallRepository.class.getDeclaredField("task");taskField.setAccessible(true);
            InstallTask task=(InstallTask)taskField.get(isolated);
            InstallFragment install=new InstallFragment();attach(install);
            Field repo=InstallFragment.class.getDeclaredField("repository");repo.setAccessible(true);repo.set(install,isolated);
            render(install);
            ScrollView outer=page.findViewById(R.id.install_scroll);
            ui(()->check(outer.getScrollY()==0,"首次进入安装页没有置顶"));
            for(int i=0;i<100;i++)task.append("独立日志第 "+i+" 行，不运行安装任务");
            render(install);
            ui(()->check(outer.getScrollY()==0,"追加日志使页面跳到了中间"));
            int[] position={0};ui(()->{outer.scrollTo(0,200);position[0]=outer.getScrollY();check(position[0]>0,"测试页面不足以滚动");});
            task.append("用户正在阅读页面下方时追加日志");render(install);
            ui(()->check(outer.getScrollY()==position[0],"日志更新抢走用户阅读位置"));
            attach(new Fragment());InstallFragment next=new InstallFragment();attach(next);repo.set(next,isolated);render(next);
            ui(()->check(((ScrollView)page.findViewById(R.id.install_scroll)).getScrollY()==0,"重新进入安装页沿用了日志焦点位置"));
            ConfigFragment configPage=new ConfigFragment();attach(configPage);
            attach(new WorkspaceFragment());
            check(page.findViewById(R.id.workspace_backup)!=null,"缺少合并后的备份与恢复入口");
            check(page.findViewById(R.id.workspace_storage)!=null,"缺少存储与文件入口");
            check(config.isBackupKey()==original,"进入数据页修改了历史备份偏好");
            attach(configPage);check(config.isBackupKey()==original,"进入配置页覆盖了备份设置");
            // 校验真实保存按钮不会覆盖已移出的偏好；表单原值保持，测试不导出凭据。
            ui(()->page.findViewById(R.id.config_save).performClick());
            check(config.isBackupKey()==original,"保存配置覆盖了备份设置");
            attach(new WorkspaceFragment());
            check(config.isBackupKey()==original,"重建备份页修改了历史备份偏好");
            result.putString("result","PASS");result.putInt("checks",checks);
        } catch(Throwable error) {result.putString("failure",android.util.Log.getStackTraceString(error));}
        finally {
            SharedPreferences.Editor edit=prefs.edit();if(existed)edit.putBoolean(Constants.KEY_BACKUP_KEY,original);else edit.remove(Constants.KEY_BACKUP_KEY);edit.commit();
            if(page!=null)ui(page::finish);
            finish(result.containsKey("failure")?1:0,result);
        }
    }
}
