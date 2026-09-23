package com.deepseekharness.app.backup;

import java.io.*;
import java.util.*;

/** 文件系统和 SAF 共用的数据源接口；物理位置留在宿主，归档只记录逻辑根与相对路径。 */
public interface BackupSource {
    String id();
    String scope();
    boolean external();
    default String displayLocation(){return "";}
    default boolean containsLocal(File file){return false;}
    interface Visit { void item(Item item)throws IOException; }
    final class Item {
        public final String path,kind,token,target,reason;
        public final long size;
        public final int mode;
        public Item(String path,String kind,long size,String token,String target,String reason){this(path,kind,size,token,target,reason,kind.equals("DIRECTORY")?0700:0600);}
        public Item(String path,String kind,long size,String token,String target,String reason,int mode){this.path=path;this.kind=kind;this.size=size;this.token=token;this.target=target;this.reason=reason;this.mode=mode&0777;}
    }
    void walk(Visit visitor,BackupControl control)throws IOException;
    InputStream open(Item item)throws IOException;
    void verify(Item item,String sha256,BackupControl control)throws IOException;
    default String category(Item item){return scope();}
    default Map<String,Object> description(){Map<String,Object> value=new LinkedHashMap<>();value.put("id",id());value.put("scope",scope());value.put("external",external());return value;}
}
