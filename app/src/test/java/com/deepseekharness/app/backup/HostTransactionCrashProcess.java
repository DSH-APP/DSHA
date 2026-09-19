package com.deepseekharness.app.backup;

import java.io.*;

/** 等待测试父进程真正强制结束，不以抛异常模拟进程死亡。只使用父测试新建的目录。 */
public final class HostTransactionCrashProcess {
    public static void main(String[] args)throws Exception{
        File base=new File(args[0]);HostDataTransaction transaction=HostDataTransactionTest.open(base,name->{
            if(name.equals(args[1])){File part=new File(base,"signal-"+name);try(FileOutputStream out=new FileOutputStream(part)){out.write(1);out.getFD().sync();}
                java.nio.file.Files.move(part.toPath(),new File(base,"reached-"+name).toPath());
                for(;;)try{Thread.sleep(1000);}catch(InterruptedException ignored){}}
        });
        if(Boolean.parseBoolean(args[2]))transaction.recover();else transaction.commit(new BackupControl(null));
    }
}
