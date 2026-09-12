package com.deepseekharness.app.util;

import java.io.*;
import java.util.*;

/** 最近五次启动的小型诊断记录；只访问独立目录，不遍历工作区、会话或插件。 */
public final class StartupHistoryStore {
    private final File directory;
    public StartupHistoryStore(File files) {
        // Android /data/user/0 是 /data/data 的宿主别名；先固定可信的父目录，再拒绝记录目录本身的软链。
        File base;
        try { base=files.getCanonicalFile(); } catch(IOException error) { base=files.getAbsoluteFile(); }
        directory=new File(base,"startup-history");
    }
    public static final class Entry {
        public final String id,status,stage,log,reason;
        public final long started,elapsed;
        public final boolean safe;
        Entry(String id,Properties p,String language) {
            this.id=id; status=p.getProperty("status","interrupted");
            started=Long.parseLong(p.getProperty("started","0"));elapsed=Long.parseLong(p.getProperty("elapsed","0"));
            safe=Boolean.parseBoolean(p.getProperty("safe","false"));
            stage=p.getProperty(language+"Stage","");log=p.getProperty(language+"Log","");reason=p.getProperty("reason","");
        }
    }
    public synchronized void save(String id,long started,String status,String reason,StartupTrace.Snapshot zh,StartupTrace.Snapshot en) throws IOException {
        if(!id.matches("[0-9a-f-]{36}"))throw new IOException("Invalid startup record ID");
        if(!directory.isDirectory() && !directory.mkdirs())throw new IOException("Cannot create startup history");
        if(!directory.getCanonicalFile().equals(directory.getAbsoluteFile()))throw new IOException("Startup history cannot be a symbolic link");
        Properties p=new Properties();p.setProperty("status",status);p.setProperty("started",Long.toString(started));
        p.setProperty("elapsed",Long.toString(zh.elapsedMs));p.setProperty("safe",Boolean.toString(zh.safe));
        p.setProperty("reason",SensitiveData.redact(reason));
        p.setProperty("zhStage",zh.stage);p.setProperty("enStage",en.stage);
        p.setProperty("zhLog",zh.log);p.setProperty("enLog",en.log);
        File temporary=new File(directory,id+".tmp"),target=new File(directory,id+".properties");
        if(!temporary.getCanonicalFile().equals(temporary.getAbsoluteFile()) || !target.getCanonicalFile().equals(target.getAbsoluteFile()))throw new IOException("Invalid startup record path");
        try(FileOutputStream out=new FileOutputStream(temporary)) { p.store(out,"DSHA startup diagnostics");out.getFD().sync(); }
        if(!temporary.renameTo(target))throw new IOException("Cannot commit startup record");
        List<Entry> records=list("zh");
        for(int i=5;i<records.size();i++) { File old=new File(directory,records.get(i).id+".properties");if(!old.delete())throw new IOException("Cannot rotate startup record"); }
    }
    public synchronized List<Entry> list(String language) {
        List<Entry> result=new ArrayList<>();
        File[] files=directory.listFiles((dir,name)->name.matches("[0-9a-f-]{36}\\.properties"));
        if(files!=null)for(File file:files)try {
            if(!file.getCanonicalFile().equals(file.getAbsoluteFile())||!file.isFile()||file.length()>1024*1024)continue;
            Properties p=new Properties();try(FileInputStream in=new FileInputStream(file)){p.load(in);}
            result.add(new Entry(file.getName().substring(0,36),p,language));
        } catch(IOException|RuntimeException ignored) { }
        result.sort((a,b)->Long.compare(b.started,a.started));return result;
    }
}
