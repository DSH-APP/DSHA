package com.deepseekharness.app.backup;

import java.io.*;

/** 已知/可能存在但不可读的数据根；绝不将它变成空目录或首次安装证明。 */
public final class UnavailableBackupSource implements BackupSource {
    private final String id,scope,reason;private final boolean missing;
    public UnavailableBackupSource(String id,String scope,String reason,boolean missing){this.id=id;this.scope=scope;this.reason=reason;this.missing=missing;}
    public String id(){return id;}public String scope(){return scope;}public boolean external(){return true;}
    public void walk(Visit visit,BackupControl control)throws IOException{control.check();visit.item(new Item("",missing?"MISSING":"UNREADABLE",0,"","",reason));}
    public InputStream open(Item item)throws IOException{throw new IOException(reason);}
    public void verify(Item item,String hash,BackupControl control)throws IOException{throw new IOException(reason);}
}
