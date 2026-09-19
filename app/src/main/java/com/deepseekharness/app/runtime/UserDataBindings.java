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
        }catch(IOException error){throw new IllegalStateException("DATA_LOCATION_UNREADABLE",error);}
    }
}
