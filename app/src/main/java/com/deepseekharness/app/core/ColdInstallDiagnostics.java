package com.deepseekharness.app.core;

import android.content.Context;
import android.util.AtomicFile;
import com.deepseekharness.app.util.Compat;
import com.deepseekharness.app.util.SensitiveData;
import com.deepseekharness.app.util.TextLogTail;
import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;

/** 冷安装记录独立于 Web 日志，日志受限的 ROM 上也可由错误报告导出。 */
public final class ColdInstallDiagnostics {
    private static final Object LOCK = new Object();
    private ColdInstallDiagnostics() { }
    public static void stage(Context context, String stage) {
        context.getSharedPreferences("dsha-install-diagnostics",0).edit().putString("stage",stage).commit();
        record(context,"STAGE",stage);
    }
    public static void failure(Context context, Throwable failure) {
        String stage=context.getSharedPreferences("dsha-install-diagnostics",0).getString("stage","环境准备");
        String detail=SensitiveData.redact(String.valueOf(failure));
        new ConfigStore(context).recordEnvironmentFailure(stage,detail);
        record(context,"FAILED",stage+"\n"+detail);
    }
    public static void record(Context context, String kind, String message) {
        synchronized(LOCK) {
            String safe=SensitiveData.redact(message);
            DiagnosticLog.record(context,"COLD_INSTALL",kind+" · "+safe.substring(0,Math.min(300,safe.length())));
            File file=new File(context.getFilesDir(),"cold-install-diagnostics.txt");
            AtomicFile atomic=new AtomicFile(file);FileOutputStream output=null;
            try {
                if(Compat.isSymbolicLink(file))return;
                String previous=file.isFile()?TextLogTail.read(file,96*1024):"";
                String line="\n["+System.currentTimeMillis()+"] "+kind+"\n"+safe+"\n";
                output=atomic.startWrite();output.write((previous+line).getBytes(StandardCharsets.UTF_8));atomic.finishWrite(output);
            }catch(Exception error){if(output!=null)atomic.failWrite(output);}
        }
    }
    public static String read(Context context) {
        synchronized(LOCK) {
            File file=new File(context.getFilesDir(),"cold-install-diagnostics.txt");
            try{return file.isFile()&&!Compat.isSymbolicLink(file)?SensitiveData.redact(TextLogTail.read(file,128*1024)):
                    com.deepseekharness.app.util.UiText.choose("暂无冷安装记录\n","No cold-install record\n");}
            catch(Exception error){return "INSTALL_DIAGNOSTIC_UNREADABLE: "+error.getClass().getSimpleName();}
        }
    }
}
