package com.deepseekharness.app.backup;
import java.io.*;

/** 只接收测试父进程创建的私有夹具；在已同步的边界等待父进程真实强杀。 */
public final class ConfigResetCrashProcess {
    public static void main(String[] args)throws Exception{
        File root=new File(args[0]);HostDataTransaction.Fault fault=name->{
            if(name.equals(args[1])){
                try(FileOutputStream out=new FileOutputStream(new File(root,"reached-"+name))){out.write(1);out.getFD().sync();}
                for(;;)try{Thread.sleep(1000);}catch(InterruptedException ignored){}
            }
        };
        if(Boolean.parseBoolean(args[2]))NativeConfigurationReset.recover(NativeConfigurationResetTest.FS,root,NativeConfigurationResetTest.operation(root),NativeConfigurationResetTest.settings(root),fault);
        else NativeConfigurationResetTest.reset(root,fault);
    }
}
