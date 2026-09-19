package com.deepseekharness.app.backup;

import java.io.*;
import java.nio.file.Files;

/** 合成运行时夹具：父 JVM 对本进程执行真实强杀，健康回执为合成值而非真机证明。 */
public final class HostRuntimeCrashProcess {
    public static void main(String[] args)throws Exception{
        File base=new File(args[0]);var task=ManagedRuntimeTransaction.open(new JvmBackupFileSystem(),base,args[1],phase->{
            if(!phase.equals(args[2]))return;
            File part=new File(base,"signal-"+phase);try(FileOutputStream out=new FileOutputStream(part)){out.write(1);out.getFD().sync();}
            Files.move(part.toPath(),new File(base,"reached-"+phase).toPath());for(;;)try{Thread.sleep(1000);}catch(InterruptedException ignored){}
        });
        var descriptor=ManagedRuntimeTransactionTest.descriptor('b');
        if(Boolean.parseBoolean(args[3]))task.recover();else task.commit(new BackupControl(null),()->ManagedRuntimeTransactionTest.health(descriptor),()->true);
    }
}
