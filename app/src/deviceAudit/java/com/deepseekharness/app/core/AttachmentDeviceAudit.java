package com.deepseekharness.app.core;

import android.app.*;
import android.os.Bundle;
import com.deepseekharness.app.*;
import com.deepseekharness.app.backup.*;
import com.deepseekharness.app.util.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** 复用已有附件夹具；只用独立审计包的私有与 FUSE 合成目录，不发送聊天。 */
public final class AttachmentDeviceAudit extends Instrumentation {
    @Override public void onCreate(Bundle args){super.onCreate(args);start();}
    @Override public void onStart(){Bundle result=new Bundle();Activity screen=null;List<Map<String,Object>> cases=new ArrayList<>();
        try{
            var context=getTargetContext();DeviceAuditSupport.requireIsolated(context);screen=DeviceAuditSupport.open(this,DeviceAuditActivity.class);
            HarnessController controller=HarnessController.get(context);if(!controller.isEnvironmentReady())throw new IOException("AUDIT_RUNTIME_NOT_READY");
            String guest="/root/attachment-audit-"+UUID.randomUUID();File fixture=new File(controller.proot().getRootfsDir().getCanonicalFile(),guest.substring(1));
            if(!fixture.mkdir())throw new IOException("AUDIT_DIRECTORY");
            try(InputStream input=context.getAssets().open("device-checks/tools/test-attachment-store.mjs");OutputStream out=new FileOutputStream(new File(fixture,"test.mjs"))){byte[] b=new byte[8192];int n;while((n=input.read(b))!=-1)out.write(b,0,n);}
            File external=context.getExternalFilesDir(null);if(external==null)throw new IOException("AUDIT_EXTERNAL_UNAVAILABLE");
            String shared=external.getAbsolutePath().replaceFirst("^/storage/emulated/[0-9]+","/sdcard");
            BackupManager.runDataTask(controller,()->{
                for(String name:List.of("private","fuse")){
                    String command="node "+ShellQuote.arg(guest+"/test.mjs")+" "+ShellQuote.arg(name.equals("private")?guest+"/private":shared)+(name.equals("fuse")?" "+ShellQuote.arg(guest+"/linked"):"");
                    String output=controller.proot().execAndReadWithProot(command+" && printf '\\nDSHA_ATTACHMENT_AUDIT_PASS\\n'",180000);
                    if(!output.contains("DSHA_ATTACHMENT_AUDIT_PASS")||!output.contains("ATTACHMENT_AUDIT_PASS checks="))throw new IOException("ATTACHMENT_FAILED:"+SensitiveData.redact(output));
                    cases.add(Map.of("name",name,"status","PASS","output",SensitiveData.redact(output)));Bundle progress=new Bundle();progress.putString("case",name);progress.putString("status","PASS");sendStatus(1,progress);
                }return null;
            });
        }catch(Throwable error){result.putString("failure",SensitiveData.redact(android.util.Log.getStackTraceString(error)));}
        finally{if(screen!=null){Activity close=screen;runOnMainSync(close::finish);}try{result.putString("report",new String(BackupJson.write(Map.of("status",result.containsKey("failure")?"FAIL":"PASS","flavor",BuildConfig.FLAVOR,"nonDebuggable",true,"tests",cases),65536),StandardCharsets.UTF_8));}catch(IOException error){result.putString("failure",error.toString());}finish(result.containsKey("failure")?1:0,result);}
    }
}
