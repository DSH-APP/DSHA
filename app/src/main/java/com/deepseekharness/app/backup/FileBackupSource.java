package com.deepseekharness.app.backup;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** 已由宿主数据定位层授权的物理根。内部链接保留元数据；数据热目录的链接由定位层单独展开为根。 */
public class FileBackupSource implements BackupSource {
    public interface Policy {
        String exclusion(String relative,BackupFileSystem.Node node)throws IOException;
        default String before(String relative)throws IOException{return "";}
    }
    private final BackupFileSystem fs;
    private final File root;
    private final String id,scope;
    private final boolean external;
    private final Policy policy;
    private final GuestDataResolver links;
    public FileBackupSource(BackupFileSystem fs,String id,String scope,File root,boolean external,Policy policy)throws IOException{
        this(fs,id,scope,root,external,policy,null);
    }
    public FileBackupSource(BackupFileSystem fs,String id,String scope,File root,boolean external,Policy policy,GuestDataResolver links)throws IOException{
        this.fs=fs;this.id=BackupLimits.root(id);this.scope=scope;this.root=root.getAbsoluteFile();this.external=external;this.policy=policy==null?(p,n)->"":policy;
        this.links=links;
    }
    @Override public String id(){return id;}
    @Override public String scope(){return scope;}
    @Override public boolean external(){return external;}
    @Override public String displayLocation(){return root.getAbsolutePath();}
    @Override public boolean containsLocal(File file){String path=file.getAbsolutePath(),prefix=root.getAbsolutePath();return path.equals(prefix)||path.startsWith(prefix+File.separator);}
    protected String classify(String path){return scope;}
    @Override public String category(Item item){return classify(item.path);}
    protected File locate(String relative)throws IOException{
        BackupLimits.path(relative);File physical=relative.isEmpty()?root:fs.child(root,relative);
        GuestDataResolver.Resolved mapped=links==null?null:links.resolveL2sFile(physical);return mapped==null?physical:mapped.file;
    }
    private String token(String relative,BackupFileSystem.Node node,List<String> listing)throws IOException{
        java.security.MessageDigest digest=BackupArchive.sha();
        File at=root;BackupFileSystem.Node anchor=fs.stat(at);put(digest,anchor.key+":"+anchor.mode);
        if(!relative.isEmpty())for(String part:relative.split("/")){
            if(!fs.stat(at).type.equals("DIRECTORY"))throw new IOException("PARENT_LINK");at=new File(at,part);BackupFileSystem.Node state=fs.stat(at);put(digest,state.key+":"+state.mode);
        }
        put(digest,node.type+":"+node.key+":"+node.size+":"+node.modified+":"+node.mode);
        if(links!=null&&node.type.equals("FILE")){GuestDataResolver.Resolved mapped=links.resolveL2sFile(at);if(mapped!=null)put(digest,mapped.proof);}
        if(listing!=null)for(String name:listing)put(digest,name);
        if(node.type.equals("LINK"))put(digest,fs.readLink(at));
        return BackupArchive.hex(digest.digest());
    }
    private static void put(java.security.MessageDigest digest,String value){byte[] bytes=value.getBytes(StandardCharsets.UTF_8);digest.update((byte)(bytes.length>>24));digest.update((byte)(bytes.length>>16));digest.update((byte)(bytes.length>>8));digest.update((byte)bytes.length);digest.update(bytes);}
    @Override public void walk(Visit visitor,BackupControl control)throws IOException{walk("",visitor,control,new int[]{0,0});}
    private void walk(String relative,Visit visitor,BackupControl control,int[] count)throws IOException{
        control.check();if(++count[0]>BackupLimits.ENTRIES)throw new IOException("ENTRY_LIMIT");BackupLimits.path(relative);
        String early=policy.before(relative);if(!early.isEmpty()){visitor.item(new Item(relative,"EXCLUDED",0,"","",early));return;}
        File file;BackupFileSystem.Node node;List<String> children=null;String target="",fingerprint,excluded;
        try {
            file=locate(relative);node=fs.stat(file);
            excluded=policy.exclusion(relative,node);
            if(node.type.equals("DIRECTORY")&&excluded.isEmpty()){children=fs.list(file);count[1]=(int)BackupLimits.add(count[1],children.size(),BackupLimits.ENTRIES);}
            if(node.type.equals("LINK"))target=fs.readLink(file);
            fingerprint=token(relative,node,children);
        }catch(InterruptedIOException error){throw error;}catch(IOException error){visitor.item(new Item(relative,"UNREADABLE",0,"","",code(error)));return;}
        if(!excluded.isEmpty()){visitor.item(new Item(relative,"EXCLUDED",0,"","",excluded));return;}
        if(node.type.equals("SPECIAL")){visitor.item(new Item(relative,"UNREADABLE",0,"","","SPECIAL_FILE"));return;}
        visitor.item(new Item(relative,node.type,node.type.equals("FILE")?node.size:0,fingerprint,target,"",node.mode));
        if(children!=null)for(String child:children){BackupLimits.path(child);if(child.contains("/"))throw new IOException("INVALID_CHILD");walk(relative.isEmpty()?child:relative+"/"+child,visitor,control,count);}
    }
    private static String code(IOException error){String message=error.getMessage();return message!=null&&message.matches("[A-Z_0-9]{2,80}")?message:"SOURCE_UNREADABLE";}
    private BackupFileSystem.Node current(Item item)throws IOException{
        File path=locate(item.path);BackupFileSystem.Node state=fs.stat(path);List<String> children=state.type.equals("DIRECTORY")?fs.list(path):null;
        if(!item.kind.equals(state.type)||!item.token.equals(token(item.path,state,children)))throw new IOException("SOURCE_CHANGED");return state;
    }
    @Override public InputStream open(Item item)throws IOException{
        BackupFileSystem.Node expected=current(item);InputStream input=fs.read(locate(item.path),expected);
        return new FilterInputStream(input){boolean closed;@Override public void close()throws IOException{if(closed)return;closed=true;try{super.close();}finally{current(item);}}};
    }
    @Override public void verify(Item item,String expected,BackupControl control)throws IOException{
        if(item.kind.equals("EXCLUDED"))return;current(item);
        if(item.kind.equals("FILE"))try(InputStream input=open(item)){if(!expected.equals(BackupArchive.digest(input,control)))throw new IOException("SOURCE_CHANGED");}
    }
}
