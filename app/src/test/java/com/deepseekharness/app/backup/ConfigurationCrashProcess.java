package com.deepseekharness.app.backup;
import java.io.*;
import java.nio.file.Files;

/** 等待父测试在真实进程边界强制终止；不访问夹具之外的用户数据。 */
public final class ConfigurationCrashProcess {
    public static void main(String[] args)throws Exception{
        File root=new File(args[0]);var engine=ConfigurationSnapshotsTest.engine(root,boundary->{
            if(!boundary.equals(args[1]))return;File part=new File(root,"signal-"+boundary);
            try(FileOutputStream out=new FileOutputStream(part)){out.write(1);out.getFD().sync();}Files.move(part.toPath(),new File(root,"reached-"+boundary).toPath());
            for(;;)try{Thread.sleep(1000);}catch(InterruptedException ignored){}
        });
        if(Boolean.parseBoolean(args[2]))engine.recover(new BackupControl(null));else engine.create("settings.yaml",new BackupControl(null));
    }
}
