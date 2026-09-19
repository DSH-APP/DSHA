package com.deepseekharness.app.backup;

import java.io.*;
import java.util.*;

/** 原始异常只归类，不把文件路径、配置正文或凭据拼进界面和任务记录。 */
public final class BackupErrorCode {
    private BackupErrorCode(){}
    public static String from(Throwable error){
        Set<Throwable> visited=Collections.newSetFromMap(new IdentityHashMap<>());
        for(Throwable at=error;at!=null&&visited.add(at)&&visited.size()<=16;at=at.getCause()){
            String message=at.getMessage();
            if(message!=null&&message.matches("[A-Z_0-9]{2,100}"))return message;
            if(at instanceof InterruptedIOException)return "CANCELLED";
            if("android.os.OperationCanceledException".equals(at.getClass().getName()))return "CANCELLED";
            if(at instanceof SecurityException)return "PERMISSION_DENIED";
            if(at instanceof EOFException)return "ARCHIVE_TRUNCATED";
            if("android.system.ErrnoException".equals(at.getClass().getName()))try{
                String code=errno(at.getClass().getField("errno").getInt(at));if(!code.equals("IO_FAILURE"))return code;
            }catch(ReflectiveOperationException ignored){}
            if(message!=null){
                if(message.matches("(?s).*\\bENOSPC\\b.*"))return "NO_SPACE";
                if(message.matches("(?s).*\\b(?:EACCES|EPERM)\\b.*"))return "PERMISSION_DENIED";
                if(message.startsWith("MANIFEST_FIELD_"))return "MANIFEST_FORMAT";
            }
        }return "IO_FAILURE";
    }
    static String errno(int value){return value==28?"NO_SPACE":value==13||value==1?"PERMISSION_DENIED":"IO_FAILURE";}
}
