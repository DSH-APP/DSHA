package com.deepseekharness.app.backup;

import android.content.Context;
import java.io.*;
import java.util.*;

/** 只统计实际目录，不跟随链接；清理范围是可再生缓存和经过原规则核验的旧受管副本。 */
public final class StorageMaintenance {
    private StorageMaintenance() { }
    private static final List<String> CACHE=List.of("runtime-startup","functional-audit","adb-flow-audit","install-audit","layout-audit","plugin-sort-audit","log-panel-audit",
            "WebView","GPUCache","Code Cache","GrShaderCache","DawnCache");
    public static final class Size { public long bytes; public int unreadable; }
    public static Map<String,Size> inspect(Context context)throws IOException{
        var fs=new AndroidBackupFileSystem();File files=context.getFilesDir().getCanonicalFile();Map<String,Size> result=new LinkedHashMap<>();
        for(String name:List.of("linux","host-runtime-operations","host-environment-operations","runtime-updates","host-backup-operations","mozilla","user-data-v5"))result.put(name,size(fs,new File(files,name)));
        result.put("cache",size(fs,context.getCacheDir().getCanonicalFile()));return result;
    }
    private static Size size(BackupFileSystem fs,File file){Size total=new Size();count(fs,file,0,total);return total;}
    private static void count(BackupFileSystem fs,File file,int depth,Size total){
        try{
            if(depth>128)throw new IOException("STORAGE_DEPTH");var node=fs.stat(file);
            if(node.type.equals("FILE")){total.bytes=Math.addExact(total.bytes,node.size);return;}
            if(!node.type.equals("DIRECTORY"))return;
            // 统计只取 lstat 元数据，不读取文件正文、不跟随链接；不重复为每级打开锚点描述符。
            File[] children=file.listFiles();if(children==null){total.unreadable++;return;}
            for(File child:children)count(fs,child,depth+1,total);
        }catch(IOException|ArithmeticException unavailable){total.unreadable++;}
    }
    private static long removeCache(BackupFileSystem fs,File root,String relative)throws IOException{
        if(!com.deepseekharness.app.util.RegenerableCachePaths.safeRelative(relative))throw new IOException("CACHE_PATH_FORBIDDEN");
        File at=root;
        String[] parts=relative.split("/");
        for(int i=0;i<parts.length-1;i++){
            at=new File(at,parts[i]);var node=fs.stat(at);
            if(node.type.equals("MISSING"))return 0;
            if(!node.type.equals("DIRECTORY"))throw new IOException("CACHE_PARENT_TYPE");
        }
        File target=new File(root,relative);long before=size(fs,target).bytes;
        fs.removeOwned(root,relative);
        return Math.max(0,before-size(fs,target).bytes);
    }
    public static long clean(Context context)throws Exception{
        var controller=com.deepseekharness.app.core.HarnessController.get(context);
        return com.deepseekharness.app.BackupManager.runSnapshotTask(controller,()->{
            if(NativeBackupJobs.get(context).state().busy||!AutomaticBackups.idleForOwner(context))throw new IOException("STOP_DSH_AND_TERMINALS_FIRST");
            try(var barrier=com.deepseekharness.app.core.RuntimeTasks.tryEnterMaintenance()){
                if(barrier==null)throw new IOException("STOP_DSH_AND_TERMINALS_FIRST");
                var fs=new AndroidBackupFileSystem();File cache=context.getCacheDir().getCanonicalFile();long before=size(fs,cache).bytes;
                for(String name:CACHE)fs.removeOwned(cache,name);
                long regenerated=0;
                File rootfs=controller.proot().getRootfsDir().getCanonicalFile();
                for(String path:com.deepseekharness.app.util.RegenerableCachePaths.rootfs())regenerated+=removeCache(fs,rootfs,path);
                // WebView 的 Cookie、Local Storage 和 IndexedDB 位于其它目录；这里只删可重建的渲染/HTTP 缓存。
                File data=context.getFilesDir().getCanonicalFile().getParentFile();
                for(String path:com.deepseekharness.app.util.RegenerableCachePaths.webViewData())regenerated+=removeCache(fs,data,path);
                File files=context.getFilesDir().getCanonicalFile();long old=size(fs,new File(files,"host-runtime-operations")).bytes;
                ManagedRuntimeTransaction.trimOlder(fs,files,controller.proot().installedRuntimeDescriptor(),controller.proot().runtimeHealth());
                long environments=size(fs,new File(files,EnvironmentRebuildTransaction.HOME)).bytes;
                EnvironmentRebuildTransaction.cleanupCompleted(fs,files,new BackupControl(null));
                return before-size(fs,cache).bytes+regenerated+old-size(fs,new File(files,"host-runtime-operations")).bytes+environments-size(fs,new File(files,EnvironmentRebuildTransaction.HOME)).bytes;
            }
        });
    }
}
