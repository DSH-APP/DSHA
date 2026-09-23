package com.deepseekharness.app.backup;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;

/** 明确历史映射下的只读链接解析；不借宿主 canonicalPath 跟随到另一台设备或未知公开目录。 */
public final class GuestDataResolver {
    public static final class Resolved {
        public final File file;public final String proof;
        Resolved(File file,String proof){this.file=file;this.proof=proof;}
    }
    private final BackupFileSystem fs;
    private final File rootfs,publicRoot;
    private final List<File> allowed;
    public GuestDataResolver(BackupFileSystem fs,File rootfs,File publicRoot,List<File> approvedRoots){
        this.fs=fs;this.rootfs=rootfs.getAbsoluteFile();this.publicRoot=publicRoot==null?null:publicRoot.getAbsoluteFile();allowed=new ArrayList<>();
        allowed.add(this.rootfs);for(File path:approvedRoots)allowed.add(path.getAbsoluteFile());
    }
    public Resolved resolve(File input)throws IOException{
        File target=normalize(input);if(!approved(target))throw new IOException("UNAPPROVED_SOURCE");
        MessageDigest proof=BackupArchive.sha();int hops=0;
        for(;;){File top=target;while(top.getParentFile()!=null)top=top.getParentFile();
            String relative=target.getPath().substring(top.getPath().length());File cursor=top;boolean followed=false;
            for(String part:relative.split(java.util.regex.Pattern.quote(File.separator))){if(part.isEmpty())continue;
                cursor=new File(cursor,part);BackupFileSystem.Node node=fs.stat(cursor);
                proof.update((node.type+":"+node.key+"\n").getBytes(StandardCharsets.UTF_8));
                if(node.type.equals("LINK")){
                    if(++hops>BackupLimits.LINKS)throw new IOException("LINK_LOOP");String link=fs.readLink(cursor);
                    if(link.length()>2048||link.indexOf('\0')>=0)throw new IOException("LINK_FORMAT");
                    proof.update(link.getBytes(StandardCharsets.UTF_8));File resolved=map(cursor,link);
                    String suffix=target.getPath().substring(cursor.getPath().length());target=normalize(new File(resolved.getPath()+suffix));
                    if(!approved(target))throw new IOException("LINK_OUTSIDE_APPROVED_ROOT");followed=true;break;
                }
                if(node.type.equals("MISSING"))return new Resolved(target,BackupArchive.hex(proof.digest()));
            }
            if(!followed)return new Resolved(target,BackupArchive.hex(proof.digest()));
        }
    }
    /** 仅展开明确映射到本次 rootfs/.l2s 的文件链接，普通项目链接仍保留元数据。 */
    public Resolved resolveL2sFile(File input)throws IOException{
        if(!fs.stat(input).type.equals("LINK"))return null;
        String link=fs.readLink(input);if(!Arrays.asList(link.replace('\\','/').split("/")).contains(".l2s"))return null;
        File first=map(input,link),storage=new File(rootfs,".l2s");
        if(!within(storage,first))return null;
        Resolved resolved=resolve(input);
        if(!within(storage,resolved.file)||!fs.stat(resolved.file).type.equals("FILE"))throw new IOException("L2S_PAYLOAD_UNREADABLE");
        return resolved;
    }
    public File guest(String path)throws IOException{
        if(!path.startsWith("/")||path.indexOf('\0')>=0||path.contains("/../"))throw new IOException("GUEST_PATH");
        if(publicRoot!=null&&(path.equals("/sdcard")||path.startsWith("/sdcard/")))return normalize(new File(publicRoot,path.substring(7).replaceFirst("^/", "")));
        if(publicRoot!=null&&path.matches("^/storage/emulated/[0-9]+(?:/.*)?$"))return normalize(new File(publicRoot,path.replaceFirst("^/storage/emulated/[0-9]+/?", "")));
        if(path.matches("^/(?:proc|sys|dev|system|apex)(?:/.*)?$"))throw new IOException("EXTERNAL_BINDING");
        return normalize(new File(rootfs,path.substring(1)));
    }
    private File map(File source,String target)throws IOException{
        if(!target.startsWith("/")&&!new File(target).isAbsolute())return normalize(new File(source.getParentFile(),target));
        File physical=normalize(new File(target));if(approved(physical))return physical;
        // 旧 L2S 链的宿主别名只重映射到本次读取的 rootfs，绝不读取该字面路径。
        String legacy=target.replaceFirst("^/data/(?:data|user(?:_de)?/[0-9]+)/com\\.dsh\\.client/files/linux/ubuntu(?=/|$)", "");
        if(!legacy.equals(target))return normalize(new File(rootfs,legacy.replaceFirst("^/", "")));
        if(publicRoot!=null){String publicPath=target.replaceFirst("^/(?:sdcard|storage/emulated/[0-9]+)(?=/|$)", "");
            if(!publicPath.equals(target))return normalize(new File(publicRoot,publicPath.replaceFirst("^/", "")));}
        if(within(rootfs,source)){
            if(target.matches("^/(?:proc|sys|dev|system|apex)(?:/.*)?$"))throw new IOException("EXTERNAL_BINDING");
            return normalize(new File(rootfs,target.replaceFirst("^/", "")));
        }
        throw new IOException("LINK_OUTSIDE_APPROVED_ROOT");
    }
    private boolean approved(File file){for(File root:allowed)if(within(root,file))return true;return false;}
    static boolean within(File base,File file){String a=base.getAbsolutePath(),b=file.getAbsolutePath();return b.equals(a)||b.startsWith(a+File.separator);}
    static File normalize(File file)throws IOException{
        File absolute=file.getAbsoluteFile(),top=absolute;while(top.getParentFile()!=null)top=top.getParentFile();
        String relative=absolute.getPath().substring(top.getPath().length());List<String> parts=new ArrayList<>();
        for(String part:relative.split(java.util.regex.Pattern.quote(File.separator))){if(part.isEmpty()||part.equals("."))continue;
            if(part.equals("..")){if(parts.isEmpty())throw new IOException("LINK_TRAVERSAL");parts.remove(parts.size()-1);}else parts.add(part);}
        File normalized=top;for(String part:parts)normalized=new File(normalized,part);return normalized;
    }
}
