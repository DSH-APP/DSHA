package com.deepseekharness.app.backup;

import java.io.*;

/** 小型可迁移设置投影；不能把整个归档或文件树装入此接口。 */
public final class ValueBackupSource implements BackupSource {
    public interface Value { byte[] read()throws IOException; }
    private final String id,scope;
    private final Value value;
    public ValueBackupSource(String id,String scope,Value value){this.id=id;this.scope=scope;this.value=value;}
    public String id(){return id;}public String scope(){return scope;}public boolean external(){return false;}
    private byte[] bytes()throws IOException{byte[] bytes=value.read();if(bytes.length>BackupLimits.MANIFEST)throw new IOException("METADATA_LIMIT");return bytes;}
    public void walk(Visit visitor,BackupControl control)throws IOException{
        byte[] bytes;try{bytes=bytes();}catch(IOException error){visitor.item(new Item("","UNREADABLE",0,"","","SETTINGS_UNAVAILABLE"));return;}
        visitor.item(new Item("","FILE",bytes.length,BackupArchive.hex(BackupArchive.sha().digest(bytes)),"",""));
    }
    public InputStream open(Item item)throws IOException{byte[] bytes=bytes();if(!item.token.equals(BackupArchive.hex(BackupArchive.sha().digest(bytes))))throw new IOException("SETTINGS_CHANGED");return new ByteArrayInputStream(bytes);}
    public void verify(Item item,String hash,BackupControl control)throws IOException{try(InputStream input=open(item)){if(!hash.equals(BackupArchive.digest(input,control)))throw new IOException("SETTINGS_CHANGED");}}
}
