package com.deepseekharness.app.backup;
import java.io.*;
import java.nio.file.Files;
public final class EnvironmentCrashProcess {
    public static void main(String[] args)throws Exception{
        File files=new File(args[0]);var task=EnvironmentRebuildTransaction.open(new JvmBackupFileSystem(),files,args[1],phase->{
            if(!phase.equals(args[2]))return;File part=new File(files,"signal-"+phase);try(FileOutputStream out=new FileOutputStream(part)){out.write(1);out.getFD().sync();}
            Files.move(part.toPath(),new File(files,"reached-"+phase).toPath());for(;;)try{Thread.sleep(1000);}catch(InterruptedException ignored){}
        });if(Boolean.parseBoolean(args[3]))task.rollback();else task.begin();
    }
}
