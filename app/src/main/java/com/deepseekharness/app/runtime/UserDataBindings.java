package com.deepseekharness.app.runtime;
import java.io.*;
import java.util.*;
import com.deepseekharness.app.backup.*;

/** guest 数据绑定和受管脚本覆盖使用同一清单；不改变历史 L2S 的宿主绝对路径绑定。 */
final class UserDataBindings {
    private UserDataBindings(){}
    static void append(List<String> argv,File rootfs){
        try{File files=rootfs.getParentFile().getParentFile().getCanonicalFile();
            for(String[] bind:new UserDataLayout(new AndroidBackupFileSystem(),files).binds(rootfs)){argv.add("-b");argv.add(bind[0]+":"+bind[1]);}
            // 本机回执与独立快照位于 rootfs 外，不接受用户归档携带的状态。
            File migration=new File(files,"rc1-migration-state");
            if(com.deepseekharness.app.util.Compat.isSymbolicLink(migration)
                    ||(!migration.isDirectory()&&!migration.mkdirs()))throw new IOException("MIGRATION_STATE_UNAVAILABLE");
            argv.add("-b");argv.add(migration.getAbsolutePath()+":/run/dsha-rc1-state");
        }catch(IOException error){throw new IllegalStateException("DATA_LOCATION_UNREADABLE",error);}
    }
}
