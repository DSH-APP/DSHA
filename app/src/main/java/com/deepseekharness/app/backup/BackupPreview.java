package com.deepseekharness.app.backup;
import java.io.*;
import java.util.*;

/** 仅读取所选根的条目与尺寸；不停止服务、不写用户数据，不把未知尺寸算作零。 */
public final class BackupPreview {
    public static final class Root {
        public String id,name,scope,location;public boolean external;public long files,bytes,unknown,unreadable,excluded;
    }
    public static final class Report {
        public final List<Root> roots=new ArrayList<>();public final Map<String,Long> exclusions=new LinkedHashMap<>();
        public long files,bytes,unknown,unreadable,entries,freeBytes;
    }
    private BackupPreview(){}
    public static Report inspect(List<BackupSource> sources,long freeBytes,BackupControl control)throws IOException{
        if(sources.size()>512)throw new IOException("ROOTS_LIMIT");Report report=new Report();report.freeBytes=freeBytes;
        for(BackupSource original:sources){BackupSource source=original instanceof SafBackupSource?((SafBackupSource)original).copyForOperation():original;
            Root root=new Root();root.id=source.id();root.name=String.valueOf(source.description().getOrDefault("name",source.id()));root.scope=source.scope();root.location=source.displayLocation();root.external=source.external();
            source.walk(item->{
                control.check();report.entries=BackupLimits.add(report.entries,1,BackupLimits.ENTRIES);
                switch(item.kind){
                    case "FILE":root.files++;report.files++;if(item.size<0){root.unknown++;report.unknown++;}else{root.bytes=BackupLimits.add(root.bytes,item.size,BackupLimits.BYTES);report.bytes=BackupLimits.add(report.bytes,item.size,BackupLimits.BYTES);}break;
                    case "MISSING":case "UNREADABLE":root.unreadable++;report.unreadable++;break;
                    case "EXCLUDED":root.excluded++;String reason=item.reason.isEmpty()?"SCOPE_EXCLUSION":item.reason;report.exclusions.put(reason,report.exclusions.getOrDefault(reason,0L)+1);break;
                    default:break;
                }
                control.report("PREVIEWING",report.entries,report.bytes);
            },control);report.roots.add(root);
        }return report;
    }
}
