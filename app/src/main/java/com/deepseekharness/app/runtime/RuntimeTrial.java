package com.deepseekharness.app.runtime;

import android.content.Context;
import com.deepseekharness.app.backup.*;
import com.deepseekharness.app.core.RuntimeTasks;
import com.deepseekharness.app.util.*;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** 隔离 profile 与数据的真实试运行；鉴权缓慢、息屏或界面暂不可用只保持等待。 */
public final class RuntimeTrial {
    private RuntimeTrial() { }
    public interface StaticCheck { void verify()throws IOException; }
    public interface Renderer extends AutoCloseable { void check()throws IOException; }
    private static final String HOME="runtime-trials";
    private static void write(BackupFileSystem fs,File directory,String name,byte[] bytes)throws IOException{
        fs.parents(directory,name);try(OutputStream out=fs.create(fs.child(directory,name))){out.write(bytes);}fs.syncDirectory(fs.child(directory,name).getParentFile());
    }
    private static byte[] asset(Context context,String name)throws IOException{
        try(InputStream in=context.getAssets().open(name);ByteArrayOutputStream out=new ByteArrayOutputStream()){
            byte[] b=new byte[8192];int n;while((n=in.read(b))!=-1){if(out.size()+n>512*1024)throw new IOException("TRIAL_ASSET_LIMIT");out.write(b,0,n);}return out.toByteArray();
        }
    }
    public static Map<String,Object> verify(Context context,ProotBootstrap proot,BackupControl control,StaticCheck checks)throws IOException{
        if(!com.deepseekharness.app.BackupManager.isDataTaskOwner())throw new IOException("TRIAL_REQUIRES_MAINTENANCE");
        checks.verify();control.check();
        String requested=proot.runtime().id();
        try{return verifyOnce(context,proot,control,requested);}
        catch(TrialExit error){
            // verifyOnce 的 finally 已核验 guest 全部退出并解除本轮工作锁；未确认退出会以另一异常阻止此处。
            if(!WebRuntimeFallback.shouldRetry(requested,false,error.hadAuth,false,!control.isCancelled(),error.code)
                    ||RuntimeTasks.hasOtherTasks())throw error;
            control.report("TRIAL_COMPATIBILITY_RETRY",0,0);
            Map<String,Object> proof=verifyOnce(context,proot,control,"proot");
            proof.put("fallbackFrom",requested);proof.put("fallbackExitCode",(long)error.code);return proof;
        }
    }
    private static Map<String,Object> verifyOnce(Context context,ProotBootstrap proot,BackupControl control,String runtimeMode)throws IOException{
        RuntimeDescriptor expected=proot.installedRuntimeDescriptor();
        if(expected==null)throw new IOException("RUNTIME_DESCRIPTOR_MISSING");
        BackupFileSystem fs=new AndroidBackupFileSystem();File files=context.getFilesDir().getCanonicalFile(),records=fs.child(files,HOME);
        if(fs.stat(records).type.equals("MISSING"))fs.directory(records);
        RuntimeTrialRecords.prepareForNew(fs,records);
        String id=UUID.randomUUID().toString(),nonce=id.replace("-",""),guest="/root/.dsha-runtime-trial-"+nonce,profile="dsha-recovery-"+nonce.substring(0,16);
        File operation=fs.child(records,id);fs.directory(operation);File root=fs.child(operation,"payload");fs.directory(root);
        write(fs,operation,"intent.json",BackupJson.write(Map.of("id",id,"nonce",nonce,"runtimeId",expected.id(),"profile",profile),16384));
        write(fs,root,"plugin/package.json",("{\"name\":\"dsha-runtime-check\",\"version\":\"1.0.0\",\"type\":\"module\",\"main\":\"index.js\",\"dsh\":{\"bundle\":{\"patch\":\"./cordis.patch.yml\"}}}").getBytes(StandardCharsets.UTF_8));
        write(fs,root,"plugin/index.js",asset(context,"runtime-trial-plugin.js"));write(fs,root,"plugin/page.js",asset(context,"runtime-trial-page.js"));
        write(fs,root,"plugin/cordis.patch.yml","- insert:\n    - id: dsha-runtime-check\n      name: dsha-runtime-check\n".getBytes(StandardCharsets.UTF_8));
        String profileRoot="home/profiles/"+profile;
        Map<String,Object> profileJson=Map.of("name",profile,"private",true,"dependencies",Map.of("dsha-runtime-check","link:"+guest+"/plugin"),"dsh",Map.of("profile",Map.of("bundles",List.of("@deepseek-ai/dsh-base","@deepseek-ai/dsh-web-app","dsha-runtime-check"),"patchReload","startup")));
        write(fs,root,profileRoot+"/package.json",BackupJson.write(profileJson,16384));write(fs,root,profileRoot+"/cordis.patch.yml","[]\n".getBytes(StandardCharsets.UTF_8));
        write(fs,root,profileRoot+"/pnpm-workspace.yaml","packages:\n  - .\nnodeLinker: hoisted\nautoInstallPeers: false\n".getBytes(StandardCharsets.UTF_8));
        write(fs,root,"home/settings.yaml","{}\n".getBytes(StandardCharsets.UTF_8));fs.directory(fs.child(root,profileRoot+"/node_modules"));
        fs.directory(fs.child(root,"isolated-user-home"));
        fs.symlink(guest+"/plugin",fs.child(root,profileRoot+"/node_modules/dsha-runtime-check"));
        fs.symlink("/usr/local/lib/node_modules/@deepseek-ai/dsh/node_modules/@deepseek-ai",fs.child(root,profileRoot+"/node_modules/@deepseek-ai"));
        fs.symlink("/usr/local/lib/node_modules/@deepseek-ai/dsh/node_modules",fs.child(root,"plugin/node_modules"));
        WebProcessManager manager=new WebProcessManager(proot,root);RuntimeTasks work=RuntimeTasks.begin();Process process=null;Renderer browser=null;
        Map<String,Object> proof=null;Output captured=null;
        try {
            String command="export DSH_HOME="+ShellQuote.arg(guest+"/home")+"; export HOME="+ShellQuote.arg(guest+"/isolated-user-home")+"; export DSHA_RUNTIME_TRIAL_NONCE="+nonce+"; export BROWSER=true; export DSH_CONFIRM=1; unset DEEPSEEK_API_KEY; cd "+ShellQuote.arg(guest)+"; "
                    +"printf '%s\\n' $$ > .dsha-web.pid; IFS= read -r DSHA_STAT < /proc/$$/stat; DSHA_FIELDS=$"+"{DSHA_STAT##*) }; set -- $DSHA_FIELDS; printf '%s %s\\n' $$ $"+"{20} > .dsha-web.identity; "
                    +"exec /usr/local/bin/node /usr/local/lib/node_modules/@deepseek-ai/dsh/lib/bin.js --profile "+profile+" --no-open --host 127.0.0.1 --port 0";
            write(fs,operation,"launched",id.getBytes(StandardCharsets.US_ASCII));
            process=proot.execRootfsForTrial(command,root,guest,runtimeMode);Process active=process;Output output=new Output(process);captured=output;
            long started=System.currentTimeMillis();
            while(output.auth==null){control.check();output.drain();if(ProcessTermination.exited(process))throw output.exited();
                report(control,started,"TRIAL_STARTING");pause(150);}
            DshAuthUrl.Parsed url=output.auth;int port=URI.create(url.loopbackBaseUrl).getPort();DshAuthSession.Result exchange;
            do{control.check();output.drain();exchange=DshAuthSession.exchange(url.authUrl,port,()->!control.isCancelled()&&!ProcessTermination.exited(active));
                if(exchange.status==DshAuthSession.Status.EXPIRED||exchange.status==DshAuthSession.Status.INVALID_RESPONSE)throw new IOException("TRIAL_AUTHENTICATION_FAILED");
                report(control,started,"TRIAL_AUTHENTICATING");
            }while(!exchange.ready()&&!ProcessTermination.exited(active));
            if(!exchange.ready())throw new IOException("TRIAL_AUTHENTICATION_FAILED");
            Map<String,Object> status;
            for(;;){
                control.check();output.drain();if(ProcessTermination.exited(active))throw output.exited();
                status=status(url.loopbackBaseUrl+"dsha-runtime-trial/"+nonce,exchange.cookie);
                if(status!=null){
                    if(!nonce.equals(status.get("nonce"))||BackupJson.number(status,"port")!=port)throw new IOException("TRIAL_GENERATION_MISMATCH");
                    if(!BackupJson.string(status,"failure").isEmpty())throw new IOException("TRIAL_RENDERER_FAILED");
                    for(String check:List.of("dataRead","dataWrite","storageReopened","storageFreshReopened","sessionReopened"))if(!Boolean.TRUE.equals(status.get(check)))throw new IOException("TRIAL_DATA_FAILED");
                    File payload=fs.child(root,"home/trial-data/probe.json");
                    try(InputStream input=fs.read(payload,fs.stat(payload))){if(!BackupArchive.digest(input,control).equals(status.get("hash")))throw new IOException("TRIAL_DATA_FAILED");}
                    if(browser==null)try{browser=com.deepseekharness.app.ui.RuntimeBrowserProbe.open(context,url.authUrl,url.loopbackBaseUrl,exchange.cookie,control);}
                    catch(Exception error){if(error instanceof InterruptedException)Thread.currentThread().interrupt();throw new IOException("TRIAL_BROWSER_UNAVAILABLE",error);}
                    browser.check();if(Boolean.TRUE.equals(status.get("renderer")))break;
                }
                report(control,started,"TRIAL_RENDERING");pause(350);
            }
            proof=new LinkedHashMap<>();proof.put("runtimeId",expected.id());proof.put("nonce",nonce);proof.put("port",(long)port);proof.put("confirmedAt",System.currentTimeMillis());
            proof.put("runtimeMode",runtimeMode);
            for(String name:List.of("assets","nativeModules","process","authentication","localApi","renderer","dataRead","dataWrite"))proof.put(name,true);
            proof.put("storageFreshReopened",true);
            return proof;
        }catch(IOException|RuntimeException failure){
            Map<String,Object> detail=new LinkedHashMap<>();detail.put("runtimeId",expected.id());detail.put("error",BackupErrorCode.from(failure));
            detail.put("runtimeMode",runtimeMode);detail.put("failedAt",System.currentTimeMillis());
            detail.put("output",captured==null?"":captured.diagnostics());detail.put("exitCode",process!=null&&ProcessTermination.exited(process)?process.exitValue():"running-or-not-started");
            try{write(fs,operation,"failure.json",BackupJson.write(detail,65536));}catch(IOException recording){failure.addSuppressed(recording);}
            throw failure;
        }finally{
            if(browser!=null)try{browser.close();}catch(Exception ignored){}
            IOException stopping=null;
            if(process!=null){
                try{String stopped=manager.stop();
                    if(stopped.isEmpty())ProcessTermination.awaitExit(process,3000);
                    if(!stopped.isEmpty()||!manager.confirmTrackedTrialStopped(process))
                    throw new IOException(stopped.isEmpty()?"TRIAL_PROCESS_UNCONFIRMED":"TRIAL_PROCESS_UNCONFIRMED: "+SensitiveData.redact(stopped));}
                catch(RuntimeException|IOException error){stopping=new IOException("TRIAL_PROCESS_UNCONFIRMED",error);}
            }
            if(stopping!=null){work.retainUntilExit(new CheckedExit(process,manager));throw stopping;}
            if(proof!=null)proof.put("processExited",true);
            work.close();write(fs,operation,"closed",id.getBytes(StandardCharsets.US_ASCII));
            // 只清理确认退出后的本次测试数据，保留小型记录，不碰用户 profile。
            fs.removeOwned(operation,"payload");RuntimeTrialRecords.pruneClosed(fs,records);
        }
    }
    private static void report(BackupControl control,long started,String phase)throws IOException{control.report(System.currentTimeMillis()-started>60000?"TRIAL_STILL_WAITING":phase,0,0);}
    private static void pause(long millis)throws IOException{try{Thread.sleep(millis);}catch(InterruptedException error){Thread.currentThread().interrupt();throw new InterruptedIOException("CANCELLED");}}
    private static final class TrialExit extends IOException {
        final int code;final boolean hadAuth;
        TrialExit(int code,boolean hadAuth,String detail){super("TRIAL_PROCESS_EXITED: exit="+code+"\n"+detail);this.code=code;this.hadAuth=hadAuth;}
    }
    private static final class Output {
        final Process process;final ByteArrayOutputStream line=new ByteArrayOutputStream();final StringBuilder tail=new StringBuilder();DshAuthUrl.Parsed auth;
        Output(Process process){this.process=process;}
        String diagnostics(){return SensitiveData.redact(tail.toString()+new String(line.toByteArray(),StandardCharsets.UTF_8));}
        IOException exited(){return new TrialExit(process.exitValue(),auth!=null,diagnostics());}
        void drain()throws IOException{
            InputStream input=process.getInputStream();byte[] bytes=new byte[8192];int budget=256*1024;
            while(input.available()>0&&budget>0){int size=input.read(bytes,0,Math.min(bytes.length,Math.min(input.available(),budget)));if(size<0)break;budget-=size;
                for(int i=0;i<size;i++){if(bytes[i]=='\n'){String text=line.toString("UTF-8");line.reset();tail.append(SensitiveData.redact(text)).append('\n');if(tail.length()>16384)tail.delete(0,tail.length()-16384);DshAuthUrl.Parsed parsed=DshAuthUrl.fromStartupOutput(text);if(parsed!=null&&auth==null)auth=parsed;
                    // 隔离 profile 的网页、鉴权、存储和 renderer 都会在下方实际复验。
                    // 可选 loader 项失败不能抢先把健康运行时误判为失败；只有本轮自有
                    // 检查插件无法导入/应用时才立即停止，并保留脱敏后的真实原因。
                    if(RuntimeTrialOutputPolicy.ownedPluginFailure(text))throw new IOException(
                            UiText.choose("隔离检查插件无法启动，详细原因：\n", "The isolated verification plugin could not start. Details:\n")
                                    +SensitiveData.redact(text),new IOException("TRIAL_PLUGIN_FAILED"));
                }else{if(line.size()>=65536)throw new IOException("TRIAL_OUTPUT_LIMIT");line.write(bytes[i]);}}
            }
        }
    }
    /** 不能只以 proot 启动器退出解除维护保护，仍须经过共用 Web 身份检查。 */
    private static final class CheckedExit extends Process {
        final Process process;final WebProcessManager manager;CheckedExit(Process process,WebProcessManager manager){this.process=process;this.manager=manager;}
        public int exitValue(){int code=process.exitValue();try{if(!manager.confirmTrackedTrialStopped(process))throw new IllegalThreadStateException("TRIAL_PROCESS_UNCONFIRMED");}
            catch(IOException error){throw new IllegalThreadStateException("TRIAL_PROCESS_UNCONFIRMED");}return code;}
        public int waitFor()throws InterruptedException{for(;;){try{return exitValue();}catch(IllegalThreadStateException waiting){Thread.sleep(100);}}}
        public InputStream getInputStream(){return process.getInputStream();}public InputStream getErrorStream(){return process.getErrorStream();}public OutputStream getOutputStream(){return process.getOutputStream();}
        public void destroy(){manager.stop();}
    }
    /** 诊断页和错误日志只读取最近一条已关闭试运行的小型脱敏记录。 */
    public static String latestFailure(Context context){
        try{
            BackupFileSystem fs=new AndroidBackupFileSystem();File home=fs.child(context.getFilesDir().getCanonicalFile(),HOME);
            byte[] bytes=RuntimeTrialRecords.latestFailure(fs,home);if(bytes.length==0)return "";
            Map<String,Object> value=BackupJson.read(bytes,64*1024);String mode=BackupJson.string(value,"runtimeMode");
            String error=BackupJson.string(value,"error"),output=BackupJson.string(value,"output");Object exit=value.get("exitCode");
            return SensitiveData.redact("runtimeMode="+(mode.isEmpty()?"unknown":mode)+"\nerror="+(error.isEmpty()?"unknown":error)
                    +"\nexitCode="+(exit==null?"unknown":String.valueOf(exit))+(output.isEmpty()?"":"\n"+output));
        }catch(Exception error){return "TRIAL_DIAGNOSTIC_UNAVAILABLE: "+BackupErrorCode.from(error);}
    }
    public static void recoverPending(Context context,ProotBootstrap proot)throws IOException{
        BackupFileSystem fs=new AndroidBackupFileSystem();File home=fs.child(context.getFilesDir().getCanonicalFile(),HOME);if(fs.stat(home).type.equals("MISSING"))return;
        List<String> entries=fs.list(home);if(entries.size()>64)throw new IOException("TRIAL_RETENTION_LIMIT");
        for(String id:entries){
            if(!id.matches("[a-f0-9]{8}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{12}"))throw new IOException("TRIAL_DIRECTORY");
            File entry=fs.child(home,id),closed=fs.child(entry,"closed");if(!fs.stat(closed).type.equals("MISSING")){
                if(!id.equals(new String(fs.small(closed,128),StandardCharsets.US_ASCII)))throw new IOException("TRIAL_MARKER");
                if(!fs.stat(fs.child(entry,"payload")).type.equals("MISSING"))fs.removeOwned(entry,"payload");continue;
            }
            File root=fs.child(entry,"payload");
            if(!fs.stat(fs.child(entry,"launched")).type.equals("MISSING")){
                if(fs.stat(fs.child(root,".dsha-web.pid")).type.equals("MISSING")&&fs.stat(fs.child(root,".dsha-web.pid.stale")).type.equals("MISSING"))throw new IOException("TRIAL_PROCESS_UNCONFIRMED");
                WebProcessManager manager=new WebProcessManager(proot,root);String stopped=manager.stop();if(!stopped.isEmpty()||!manager.confirmStopped(false))throw new IOException("TRIAL_PROCESS_UNCONFIRMED");
            }
            write(fs,entry,"closed",id.getBytes(StandardCharsets.US_ASCII));fs.removeOwned(entry,"payload");
        }
        RuntimeTrialRecords.pruneClosed(fs,home);
    }
    private static Map<String,Object> status(String address,String cookie)throws IOException{
        HttpURLConnection connection=(HttpURLConnection)new URL(address).openConnection(Proxy.NO_PROXY);connection.setConnectTimeout(2500);connection.setReadTimeout(2500);connection.setInstanceFollowRedirects(false);connection.setRequestProperty("Cookie",cookie);
        try{int code=connection.getResponseCode();if(code==401||code==403)throw new IOException("TRIAL_AUTHENTICATION_FAILED");if(code!=200)return null;
            try(InputStream in=connection.getInputStream();ByteArrayOutputStream out=new ByteArrayOutputStream()){
                byte[] b=new byte[2048];int n;while((n=in.read(b))!=-1){if(out.size()+n>16384)throw new IOException("TRIAL_RESPONSE_LIMIT");out.write(b,0,n);}return BackupJson.read(out.toByteArray(),16384);}}
        catch(java.net.SocketTimeoutException|java.net.ConnectException waiting){return null;}finally{connection.disconnect();}
    }
}

