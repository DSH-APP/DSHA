package com.deepseekharness.app.runtime;

import android.content.Context;
import com.deepseekharness.app.backup.*;
import com.deepseekharness.app.core.RuntimeTasks;
import com.deepseekharness.app.util.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** 当前可信 profile 的隔离设置服务。归档只作为数据输入，绝不作为启动配置加载。 */
public final class ProfileSettingsTrial {
    private ProfileSettingsTrial() { }
    public static final class Result {
        public final Map<String,Object> review;
        public final byte[] patch;
        Result(Map<String,Object> review, byte[] patch) { this.review=review; this.patch=patch; }
    }
    private static void write(BackupFileSystem fs, File root, String name, byte[] bytes) throws IOException {
        fs.parents(root,name);File target=fs.child(root,name);try(OutputStream out=fs.create(target)){out.write(bytes);}fs.syncDirectory(target.getParentFile());
    }
    public static Result run(Context context, ProotBootstrap proot, File home, String profile, byte[] currentPatch,
                             byte[] incoming, Map<String,Object> request, BackupControl control) throws IOException {
        if(!com.deepseekharness.app.BackupManager.isDataTaskOwner())throw new IOException("SETTINGS_REQUIRES_MAINTENANCE");
        if(!ProfileConfigPath.profile(profile))throw new IOException("PROFILE_NAME");
        var fs=new AndroidBackupFileSystem();File files=context.getFilesDir().getCanonicalFile(),records=new File(files,"runtime-trials");
        if(fs.stat(records).type.equals("MISSING"))fs.directory(records);RuntimeTrialRecords.prepareForNew(fs,records);
        String id=UUID.randomUUID().toString(),nonce=id.replace("-",""),guest="/root/.dsha-runtime-trial-"+nonce,trialProfile="dsha-recovery-"+nonce.substring(0,16);
        File operation=fs.child(records,id);fs.directory(operation);File payload=fs.child(operation,"payload");fs.directory(payload);
        try {
        File original=new File(home,"profiles/"+profile),manifest=new File(original,"package.json");
        if(!fs.stat(manifest).type.equals("FILE"))throw new IOException("PROFILE_NOT_AVAILABLE");
        Map<String,Object> pkg=BackupJson.read(fs.small(manifest,BackupLimits.MANIFEST),BackupLimits.MANIFEST);
        @SuppressWarnings("unchecked") Map<String,Object> dsh=(Map<String,Object>)pkg.get("dsh");
        if(dsh==null||!(dsh.get("profile") instanceof Map))throw new IOException("PROFILE_MANIFEST");
        @SuppressWarnings("unchecked") Map<String,Object> config=(Map<String,Object>)dsh.get("profile");
        Object value=config.get("bundles");if(!(value instanceof List))throw new IOException("PROFILE_MANIFEST");
        List<Object> bundles=new ArrayList<>((List<?>)value);if(!bundles.contains("dsha-profile-settings-review"))bundles.add("dsha-profile-settings-review");config.put("bundles",bundles);
        @SuppressWarnings("unchecked") Map<String,Object> dependencies=(Map<String,Object>)pkg.computeIfAbsent("dependencies",k->new LinkedHashMap<>());
        dependencies.put("dsha-profile-settings-review","link:"+guest+"/plugin");
        String profileRoot="home/profiles/"+trialProfile;
        write(fs,payload,profileRoot+"/package.json",BackupJson.write(pkg,BackupLimits.MANIFEST));
        write(fs,payload,profileRoot+"/cordis.patch.yml",currentPatch);
        // 当前已启用插件的字节来自本机原 profile，归档声明/源码不进入此处。
        File modules=new File(original,"node_modules");
        File trialModules=new File(payload,profileRoot+"/node_modules");fs.directory(trialModules);
        if(fs.stat(modules).type.equals("DIRECTORY")){
            List<String> names=fs.list(modules);if(names.size()>4096)throw new IOException("PLUGIN_GRAPH_LIMIT");
            for(String name:names){BackupLimits.path(name);if(name.contains("/")||name.equals("dsha-profile-settings-review"))throw new IOException("SETTINGS_TRIAL_PLUGIN_CONFLICT");
                fs.symlink("/root/.dsh/profiles/"+profile+"/node_modules/"+name,new File(trialModules,name));}
        }
        fs.symlink(guest+"/plugin",new File(payload,profileRoot+"/node_modules/dsha-profile-settings-review"));
        File globalPatch=new File(home,"cordis.patch.yml");
        if(fs.stat(globalPatch).type.equals("FILE"))write(fs,payload,"home/cordis.patch.yml",fs.small(globalPatch,BackupLimits.MANIFEST));
        write(fs,payload,"plugin/package.json",BackupJson.write(Map.of("name","dsha-profile-settings-review","version","1.0.0","type","module","main","index.js","dsh",Map.of("bundle",Map.of("patch","./cordis.patch.yml"))),16384));
        try(InputStream in=context.getAssets().open("profile-settings-review.js");ByteArrayOutputStream bytes=new ByteArrayOutputStream()){
            byte[] chunk=new byte[8192];int n;while((n=in.read(chunk))!=-1){if(bytes.size()+n>BackupLimits.MANIFEST)throw new IOException("SETTINGS_TRIAL_ASSET_LIMIT");bytes.write(chunk,0,n);}write(fs,payload,"plugin/index.js",bytes.toByteArray());
        }
        write(fs,payload,"plugin/cordis.patch.yml","- insert:\n    - id: dsha-profile-settings-review\n      name: dsha-profile-settings-review\n".getBytes(StandardCharsets.UTF_8));
        fs.symlink("/usr/local/lib/node_modules/@deepseek-ai/dsh/node_modules",new File(payload,"plugin/node_modules"));
        Map<String,Object> invocation=new LinkedHashMap<>(request);invocation.put("nonce",nonce);
        write(fs,payload,"request.json",BackupJson.write(invocation,BackupLimits.MANIFEST));write(fs,payload,"incoming.yml",incoming);
        fs.directory(new File(payload,"isolated-home"));
        write(fs,operation,"intent.json",BackupJson.write(Map.of("id",id,"nonce",nonce,"purpose","profile-settings","profile",trialProfile,"sourceProfile",profile),16384));
        WebProcessManager manager=new WebProcessManager(proot,payload);RuntimeTasks lease=RuntimeTasks.begin();Process process=null;
        try {
            String command="export DSH_HOME="+ShellQuote.arg(guest+"/home")+"; export HOME="+ShellQuote.arg(guest+"/isolated-home")
                    +"; export DSHA_PROFILE_SETTINGS_DIRECTORY="+ShellQuote.arg(guest)+"; export DSHA_PROFILE_SETTINGS_NONCE="+nonce+"; export DSHA_RUNTIME_TRIAL_NONCE="+nonce
                    +"; export BROWSER=true; unset DEEPSEEK_API_KEY; cd "+ShellQuote.arg(guest)
                    +"; printf '%s\\n' $$ > .dsha-web.pid; IFS= read -r DSHA_STAT < /proc/$$/stat; DSHA_FIELDS=$"+"{DSHA_STAT##*) }; set -- $DSHA_FIELDS; printf '%s %s\\n' $$ $"+"{20} > .dsha-web.identity; "
                    +"exec /usr/local/bin/node /usr/local/lib/node_modules/@deepseek-ai/dsh/lib/bin.js --profile "+ShellQuote.arg(trialProfile)+" --no-open --host 127.0.0.1 --port 0";
            write(fs,operation,"launched",id.getBytes(StandardCharsets.US_ASCII));process=proot.execRootfsForTrial(command,payload,guest,"proot");
            File result=new File(payload,"result.json");long started=System.currentTimeMillis();byte[] drain=new byte[8192];
            while(!fs.stat(result).type.equals("FILE")) {
                control.check();InputStream output=process.getInputStream();int available=output.available();if(available>0)output.read(drain,0,Math.min(drain.length,available));
                if(ProcessTermination.exited(process))throw new IOException("SETTINGS_TRIAL_EXITED");
                control.report(System.currentTimeMillis()-started>60000?"TRIAL_STILL_WAITING":"SETTINGS_VALIDATING",0,0);
                try{Thread.sleep(150);}catch(InterruptedException cancelled){Thread.currentThread().interrupt();throw new InterruptedIOException("CANCELLED");}
            }
            Map<String,Object> review=BackupJson.read(fs.small(result,BackupLimits.MANIFEST),BackupLimits.MANIFEST);
            if(!nonce.equals(review.get("nonce")))throw new IOException("SETTINGS_TRIAL_GENERATION");
            if("FAILED".equals(review.get("status")))throw new IOException("SETTINGS_VALIDATION_FAILED:"+review.get("error"));
            return new Result(review,fs.small(new File(payload,profileRoot+"/cordis.patch.yml"),BackupLimits.MANIFEST));
        } catch(IOException|RuntimeException error) {
            fs.atomic(operation,"failure.json",BackupJson.write(Map.of("runtimeMode","proot","error",BackupErrorCode.from(error),"output","","failedAt",System.currentTimeMillis()),16384));throw error;
        } finally {
            boolean exited=process==null;
            if(process!=null){try{String stopped=manager.stop();if(stopped.isEmpty())ProcessTermination.awaitExit(process,3000);exited=stopped.isEmpty()&&manager.confirmTrackedTrialStopped(process);}catch(IOException|RuntimeException ignored){}}
            if(!exited){lease.retainUntilExit(new CheckedExit(process,manager));throw new IOException("SETTINGS_TRIAL_PROCESS_UNCONFIRMED");}
            lease.close();write(fs,operation,"closed",id.getBytes(StandardCharsets.US_ASCII));fs.removeOwned(operation,"payload");RuntimeTrialRecords.pruneClosed(fs,records);
        }
        } catch(IOException|RuntimeException preparationFailure) {
            // 启动前失败没有 guest，关闭本次空现场，避免一次无效 profile 阻塞以后检查。
            if(fs.stat(new File(operation,"launched")).type.equals("MISSING")) {
                if(fs.stat(new File(operation,"closed")).type.equals("MISSING"))write(fs,operation,"closed",id.getBytes(StandardCharsets.US_ASCII));
                fs.removeOwned(operation,"payload");
            }
            throw preparationFailure;
        }
    }
    private static final class CheckedExit extends Process {
        final Process process;final WebProcessManager manager;CheckedExit(Process process,WebProcessManager manager){this.process=process;this.manager=manager;}
        public int exitValue(){int result=process.exitValue();try{if(!manager.confirmTrackedTrialStopped(process))throw new IllegalThreadStateException();}catch(IOException error){throw new IllegalThreadStateException();}return result;}
        public int waitFor()throws InterruptedException{for(;;){try{return exitValue();}catch(IllegalThreadStateException waiting){Thread.sleep(100);}}}
        public InputStream getInputStream(){return process.getInputStream();}public InputStream getErrorStream(){return process.getErrorStream();}public OutputStream getOutputStream(){return process.getOutputStream();}public void destroy(){try{manager.stop();}catch(Exception ignored){}}
    }
}
