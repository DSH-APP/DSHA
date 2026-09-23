package com.deepseekharness.app.backup;

import android.app.Instrumentation;
import android.content.*;
import android.content.pm.ApplicationInfo;
import android.os.*;
import android.system.Os;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/** 只在独立包的 UUID 夹具目录执行；使用真正的 Android 文件描述符和进程死亡。 */
public final class BackupDeviceAudit extends Instrumentation {
    static final BackupFileSystem FS=new AndroidBackupFileSystem();
    static final String TX="11111111-2222-4333-8444-555555555555";
    private final List<Map<String,Object>> results=new ArrayList<>();
    private File base;
    interface Case { void run()throws Exception; }
    static void check(boolean value,String message){if(!value)throw new AssertionError(message);}
    static void put(File root,String path,String value)throws IOException{FS.parents(root,path);try(OutputStream out=FS.create(FS.child(root,path))){out.write(value.getBytes(StandardCharsets.UTF_8));}}
    static String read(File file)throws IOException{return new String(FS.small(file,1024*1024),StandardCharsets.UTF_8);}
    static String hash(File file)throws IOException{try(InputStream in=FS.read(file,FS.stat(file))){return BackupArchive.digest(in,new BackupControl(null));}}
    private File dir(String name)throws IOException{File f=FS.child(base,name);FS.directory(f);return f;}
    private void run(String name,Case test){long start=SystemClock.elapsedRealtime();Map<String,Object> r=new LinkedHashMap<>();r.put("name",name);
        try{test.run();r.put("status","PASS");}catch(Throwable e){r.put("status","FAIL");r.put("error",android.util.Log.getStackTraceString(e));}
        r.put("durationMs",SystemClock.elapsedRealtime()-start);results.add(r);Bundle b=new Bundle();b.putString("case",name);b.putString("status",String.valueOf(r.get("status")));if(r.containsKey("error"))b.putString("failure",String.valueOf(r.get("error")));sendStatus(1,b);
    }
    private static void rejects(Case test,String reason)throws Exception{try{test.run();}catch(IOException e){if(reason==null||e.toString().contains(reason))return;throw e;}throw new AssertionError("未拒绝："+reason);}
    static Map<String,Object> summary(){return new LinkedHashMap<>(Map.of("operation","EXPORT","integrity","QUIESCENT","createdAt",System.currentTimeMillis(),"appVersion","rc2.1-device-fixture","runtime","synthetic","dataFormat","UNINSPECTED","sensitivePolicy","ENCRYPTED","plugins",Map.of()));}
    @Override public void onCreate(Bundle args){super.onCreate(args);start();}
    @Override public void onStart(){Bundle done=new Bundle();
        try{
            check(getTargetContext().getPackageName().equals("com.dsh.client.rc21audit"),"拒绝在用户安装内运行夹具");
            File files=getTargetContext().getFilesDir().getCanonicalFile();base=new File(files,"device-audit-"+UUID.randomUUID());FS.directory(base);
            run("non_debuggable",()->check((getTargetContext().getApplicationInfo().flags&ApplicationInfo.FLAG_DEBUGGABLE)==0,"必须是非调试构建"));
            run("native_io_modes_and_sync",()->{File d=dir("io");put(d,"a","原生字节");check(read(new File(d,"a")).equals("原生字节"),"字节不同");FS.mode(new File(d,"a"),0700);check(FS.stat(new File(d,"a")).mode==0700,"模式不同");FS.move(new File(d,"a"),new File(d,"b"));check(FS.list(d).equals(List.of("b")),"目录内容");FS.syncDirectory(d);});
            run("nofollow_parent_and_leaf",()->{File d=dir("links"),outside=dir("outside");put(outside,"unique","must stay");FS.symlink(outside.getPath(),new File(d,"parent"));rejects(()->put(d,"parent/escape","bad"),null);FS.symlink(new File(outside,"unique").getPath(),new File(d,"leaf"));rejects(()->FS.read(new File(d,"leaf"),FS.stat(new File(outside,"unique"))).close(),null);FS.removeOwned(d,"parent");check(read(new File(outside,"unique")).equals("must stay"),"删除链接影响目标");});
            run("snapshot_crypto_restore",()->snapshot());
            run("missing_source_partial_rescue",()->{File d=dir("rescue"),partial=dir("partial-rescue"),missing=new File(base,"missing");var source=new FileBackupSource(FS,"data","application",missing,false,null);rejects(()->HostSnapshot.create(FS,List.of(source),d,new File(d,"strict"),summary(),false,new BackupControl(null)),null);check(HostSnapshot.create(FS,List.of(source),partial,new File(partial,"partial"),summary(),true,new BackupControl(null)).get("integrity").equals("PARTIAL"),"救援等级");});
            run("old_l2s_bytes_and_cycles",()->{File d=dir("l2s");put(d,"rootfs/.l2s/id","old hardlink payload");FS.parents(d,"rootfs/root/data");FS.symlink("/data/data/com.dsh.client/files/linux/ubuntu/.l2s/id",new File(d,"rootfs/root/data"));var resolver=new GuestDataResolver(FS,new File(d,"rootfs"),null,List.of());check(read(resolver.resolveL2sFile(new File(d,"rootfs/root/data")).file).equals("old hardlink payload"),"旧 L2S 字节");FS.symlink("loop",new File(d,"rootfs/root/loop"));rejects(()->resolver.resolve(new File(d,"rootfs/root/loop")),"LINK_LOOP");});
            run("native_rebuild_without_guest",()->rebuild());
            run("transaction_commit_preserves_later_data",()->{File d=dir("commit");fixture(d);transaction(d,null).commit(new BackupControl(null));FS.delete(new File(d,"active/sessions/data"));put(d,"active/sessions/data","later conversation");transaction(d,null).recover();check(read(new File(d,"active/sessions/data")).equals("later conversation"),"迟到恢复覆盖新数据");check(read(new File(d,TX+"/previous/sessions/data")).equals("old"),"原件未保留");});
            for(String phase:List.of("old-sessions","new-sessions","settings-write","settings-committed"))run("kill_"+phase,()->{File d=dir("kill-"+phase);fixture(d);kill(d,phase,false);if(phase.equals("new-sessions"))kill(d,"rollback-new-sessions",true);transaction(d,null).recover();transaction(d,null).recover();check(read(new File(d,"active/sessions/data")).equals("old"),"强杀后原件");check(settings(d).current().get("value").equals("old"),"强杀后偏好");});
            run("descriptor_handles_do_not_leak",()->{File d=dir("fds");put(d,"a","value");int before=new File("/proc/self/fd").list().length;for(int n=0;n<250;n++){read(new File(d,"a"));FS.list(d);put(d,"output","bytes");FS.delete(new File(d,"output"));}int after=new File("/proc/self/fd").list().length;check(after-before<12,"文件描述符泄漏："+before+" -> "+after);});
            Map<String,Object> report=new LinkedHashMap<>();report.put("device",android.os.Build.MODEL);report.put("api",android.os.Build.VERSION.SDK_INT);report.put("pageSize",Os.sysconf(android.system.OsConstants._SC_PAGESIZE));report.put("flavor",com.deepseekharness.app.BuildConfig.FLAVOR);report.put("debuggable",com.deepseekharness.app.BuildConfig.DEBUG);report.put("tests",results);report.put("fixture",base.getPath());report.put("status",results.stream().anyMatch(r->r.get("status").equals("FAIL"))?"FAIL":"PASS");
            byte[] json=BackupJson.write(report,1024*1024);try(OutputStream out=FS.create(new File(base,"result.json"))){out.write(json);}done.putString("report",new String(json,StandardCharsets.UTF_8));
        }catch(Throwable e){done.putString("failure",android.util.Log.getStackTraceString(e));}
        finish(done.containsKey("failure")||results.stream().anyMatch(r->r.get("status").equals("FAIL"))?1:0,done);
    }
    private void snapshot()throws Exception{
        File source=dir("source"),task=dir("snapshot"),archive=new File(task,"data");put(source,".env","fixture secret");put(source,"node_modules/custom/source.js","unique source");put(source,"a.txt","对话附件");
        var root=new FileBackupSource(FS,"project","projects",source,false,null);var preview=BackupPreview.inspect(List.of(root),base.getUsableSpace(),new BackupControl(null));check(preview.files==3&&preview.bytes>0,"范围大小预览");
        HostSnapshot.create(FS,List.of(root),task,archive,summary(),false,new BackupControl(null));File cipher=new File(task,"encrypted"),plain=new File(task,"authenticated");char[] password="仅供夹具测试-password-123".toCharArray();
        try(InputStream in=FS.read(archive,FS.stat(archive));OutputStream out=FS.create(cipher)){PortableBackupCrypto.encrypt(in,out,password,new BackupControl(null));}
        try(InputStream in=FS.read(cipher,FS.stat(cipher));OutputStream out=FS.create(plain)){PortableBackupCrypto.decrypt(in,out,password,new BackupControl(null));}check(hash(archive).equals(hash(plain)),"加密往返");
        rejects(()->{try(InputStream in=FS.read(cipher,FS.stat(cipher));OutputStream out=FS.create(new File(task,"wrong-password"))){PortableBackupCrypto.decrypt(in,out,"wrong-password-123".toCharArray(),new BackupControl(null));}},"AUTHENTICATION_FAILED");Arrays.fill(password,'\0');
        File restore=dir(UUID.randomUUID().toString()),destination=new File(base,"restored-project");var plan=NativeRestorePlan.inspect(FS,restore,plain,m->new NativeRestorePlan.Target(destination,null,false),Set.of("projects"),new BackupControl(null));check(FS.stat(destination).type.equals("MISSING"),"预检写入目标");var roots=plan.buildCandidates(false,new BackupControl(null));new HostDataTransaction(FS,restore,id->destination,settings(restore),null).prepare(roots,plan.before,Map.of("value","new"),new BackupControl(null));
        new HostDataTransaction(FS,restore,id->destination,settings(restore),null).commit(new BackupControl(null));check(read(new File(destination,"node_modules/custom/source.js")).equals("unique source"),"依赖源码");check(read(new File(destination,".env")).equals("fixture secret"),"隐藏文件");
    }
    static HostDataTransaction.Settings settings(File root){return new HostDataTransaction.Settings(){public Map<String,Object> current()throws IOException{File file=new File(root,"native-settings");return FS.stat(file).type.equals("MISSING")?Map.of():BackupJson.read(FS.small(file,4096),4096);}public void apply(Map<String,Object> value)throws IOException{FS.atomic(root,"native-settings",BackupJson.write(value,4096));}};}
    static HostDataTransaction transaction(File root,HostDataTransaction.Fault fault)throws IOException{return new HostDataTransaction(FS,new File(root,TX),id->{if(!id.equals("sessions"))throw new IOException("UNKNOWN_TARGET");return new File(root,"active/sessions");},settings(root),fault);}
    static void fixture(File root)throws IOException{put(root,"active/sessions/data","old");put(root,TX+"/candidate/sessions/data","new");settings(root).apply(Map.of("value","old"));transaction(root,null).prepare(List.of("sessions"),Map.of("sessions",BackupTree.digest(FS,new File(root,"active/sessions"),new BackupControl(null))),Map.of("value","new"),new BackupControl(null));}
    private void kill(File root,String phase,boolean rollback)throws Exception{
        CountDownLatch dead=new CountDownLatch(1),connected=new CountDownLatch(1);java.util.concurrent.atomic.AtomicReference<Throwable> failure=new java.util.concurrent.atomic.AtomicReference<>();
        ServiceConnection connection=new ServiceConnection(){public void onServiceConnected(ComponentName name,IBinder binder){try{binder.linkToDeath(dead::countDown,0);Parcel p=Parcel.obtain();try{p.writeString(root.getPath());p.writeString(phase);p.writeInt(rollback?1:0);binder.transact(1,p,null,IBinder.FLAG_ONEWAY);}finally{p.recycle();}}catch(Throwable e){failure.set(e);}finally{connected.countDown();}}public void onServiceDisconnected(ComponentName name){} };
        Class<?> service=switch(phase){case "old-sessions"->BackupCrashService.Old.class;case "new-sessions"->BackupCrashService.New.class;case "rollback-new-sessions"->BackupCrashService.Rollback.class;case "settings-write"->BackupCrashService.SettingsWrite.class;case "settings-committed"->BackupCrashService.SettingsCommitted.class;default->throw new IOException("UNKNOWN_CRASH_PHASE");};
        check(getTargetContext().bindService(new Intent(getTargetContext(),service),connection,Context.BIND_AUTO_CREATE),"绑定故障进程");
        try{check(connected.await(15,TimeUnit.SECONDS),"故障进程连接超时");if(failure.get()!=null)throw new IOException(failure.get());check(dead.await(40,TimeUnit.SECONDS),"未确认故障进程死亡");check(read(new File(root,"reached-"+phase)).equals(phase),"未到达持久化边界");}finally{getTargetContext().unbindService(connection);}
    }
    private void rebuild()throws Exception{File d=dir("rebuild");put(d,"linux/ubuntu/root/.dsh/sessions/a","conversation");put(d,"linux/ubuntu/root/.cache/custom","unique cache");put(d,"linux/ubuntu/root/project/.env","project value");put(d,"linux/ubuntu/usr/bin/tool","old system");
        var tx=EnvironmentRebuildTransaction.create(FS,d,null);var data=new MaintenanceDataSnapshot(FS,d,tx.directory(),null);data.capture("/root/project","device-fixture",new BackupControl(null));tx.prepare(hash(data.archive()),hash(data.mapping()));tx.begin();put(d,"linux/ubuntu/usr/bin/tool","new system");data.restore(new File(d,"linux/ubuntu"),new BackupControl(null));check(read(new File(d,"linux/ubuntu/root/.dsh/sessions/a")).equals("conversation"),"宿主恢复对话");check(read(new File(d,"linux/ubuntu/usr/bin/tool")).equals("new system"),"旧系统被当作数据恢复");tx.rollback();check(read(new File(d,"linux/ubuntu/usr/bin/tool")).equals("old system"),"回切原系统");}
}
