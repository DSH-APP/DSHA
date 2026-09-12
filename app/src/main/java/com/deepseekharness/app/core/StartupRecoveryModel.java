package com.deepseekharness.app.core;

import android.app.Application;
import androidx.lifecycle.*;
import com.deepseekharness.app.util.*;
import org.json.*;
import java.util.*;

/** 页面重建保留读取任务；修复执行权仍归应用级 BackupTask。 */
public final class StartupRecoveryModel extends AndroidViewModel {
    public final MutableLiveData<JSONObject> contents=new MutableLiveData<>();
    public final MutableLiveData<String> error=new MutableLiveData<>("");
    public final MutableLiveData<Boolean> loading=new MutableLiveData<>(false);
    public List<StartupHistoryStore.Entry> attempts=Collections.emptyList();
    private volatile boolean working;
    public StartupRecoveryModel(Application app){super(app);}
    public void load() {
        if(working)return;working=true;loading.setValue(true);
        new Thread(()->{
            try {
                HarnessController c=HarnessController.get(getApplication());
                attempts=c.startupDiagnostics().history();
                JSONObject out=new JSONObject().put("snapshots",new JSONArray()).put("items",new JSONArray());
                if(c.isEnvironmentReady()&&!com.deepseekharness.app.BackupManager.hasPendingMaintenance(c)) {
                    EnvironmentTaskGate.Lease lease=EnvironmentTaskGate.tryAcquire("读取启动恢复记录");
                    if(lease==null)throw new java.io.IOException("RECOVERY_BUSY");
                    try(lease) { lease.run(()->{
                        c.proot().ensureRuntimeFiles();
                        JSONObject snapshots=StartupRepairs.list(c);out.put("snapshots",snapshots.getJSONArray("snapshots"));
                        out.put("pending",snapshots.optBoolean("pending"));
                        // 配置损坏时仍显示诊断与配置快照；插件清单单独报告读取失败。
                        try { out.put("items",StartupRepairs.plugins(c).getJSONArray("items")); }
                        catch(Exception failure) { out.put("pluginError",SensitiveData.redact(String.valueOf(failure.getMessage()))); }
                        return null;
                    }); }
                }
                error.postValue("");contents.postValue(out);
            } catch(Exception failure) { error.postValue(SensitiveData.redact(String.valueOf(failure.getMessage()))); }
            finally { working=false;loading.postValue(false); }
        },"startup-recovery-read").start();
    }
}
