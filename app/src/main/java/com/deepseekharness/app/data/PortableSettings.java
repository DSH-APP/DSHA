package com.deepseekharness.app.data;

import android.content.Context;
import android.content.SharedPreferences;
import com.deepseekharness.app.util.Constants;
import com.deepseekharness.app.backup.PortablePreferenceProjection;
import java.util.*;

/** 系统迁移只接触明确白名单投影；原偏好键继续使用，不把设备授权和 Keystore 密文带到新设备。 */
public final class PortableSettings {
    public static final String NAME="dsha-portable-settings";
    private static SharedPreferences.OnSharedPreferenceChangeListener listener;
    private static volatile String error="";
    private static final java.util.concurrent.ExecutorService writer=java.util.concurrent.Executors.newSingleThreadExecutor(work->{Thread thread=new Thread(work,"portable-settings");thread.setDaemon(true);return thread;});
    private PortableSettings() { }
    public static synchronized void initialize(Context context){
        if(listener!=null)return;
        try{
            SharedPreferences active=context.getSharedPreferences(Constants.PREFS,0),portable=context.getSharedPreferences(NAME,0);
            if(PortablePreferenceProjection.mayImport(active.getAll(),portable.getAll())){
                SharedPreferences.Editor restore=active.edit();copy(portable.getAll(),restore);
                // 失败时保留原投影；不能用尚未落盘的活跃偏好清空最后的迁移来源。
                if(!restore.commit()){error="SETTINGS_PROJECTION_FAILED";return;}
            }
            listener=(preferences,key)->{if(key==null||PortablePreferenceProjection.transferable(key))writer.execute(()->write(preferences,portable));};
            active.registerOnSharedPreferenceChangeListener(listener);writer.execute(()->write(active,portable));
        }catch(RuntimeException failure){error="SETTINGS_PROJECTION_UNAVAILABLE";}
    }

    /**
     * 应用内格式化不会重启进程，因此需先解除旧监听、排空旧写入，再建立空投影。
     * 否则格式化前排队的异步投影可能在清理后把旧设置写回来。
     */
    public static synchronized void resetForFreshStart(Context context)throws java.io.IOException{
        SharedPreferences active=context.getSharedPreferences(Constants.PREFS,0),portable=context.getSharedPreferences(NAME,0);
        if(listener!=null){active.unregisterOnSharedPreferenceChangeListener(listener);listener=null;}
        try{writer.submit(()->{}).get(10,java.util.concurrent.TimeUnit.SECONDS);}
        catch(Exception failure){throw new java.io.IOException("SETTINGS_PROJECTION_DRAIN_FAILED",failure);}
        if(!active.edit().clear().commit()||!portable.edit().clear().commit())
            throw new java.io.IOException("SETTINGS_PROJECTION_CLEAR_FAILED");
        error="";initialize(context);
    }
    private static void write(SharedPreferences active,SharedPreferences portable){try{SharedPreferences.Editor out=portable.edit().clear().putInt("projectionVersion",1);copy(active.getAll(),out);error=out.commit()?"":"SETTINGS_PROJECTION_FAILED";}catch(RuntimeException failure){error="SETTINGS_PROJECTION_UNAVAILABLE";}}
    private static void copy(Map<String,?> input,SharedPreferences.Editor output){
        for(var entry:PortablePreferenceProjection.project(input).entrySet()){Object value=entry.getValue();if(value instanceof String)output.putString(entry.getKey(),(String)value);else if(value instanceof Boolean)output.putBoolean(entry.getKey(),(Boolean)value);}
    }
    public static boolean transferable(String key){return PortablePreferenceProjection.transferable(key);}
    public static String errorCode(){return error;}
}
