package com.deepseekharness.app.backup;

import java.io.*;
import java.util.*;

/** 先完整验证到私有槽位，再生成候选树；构建计划期间不覆盖活跃目录或修改设置。 */
public final class NativeRestorePlan {
    public static final class Target {
        public final File write,read;
        public final boolean merge;
        public final String group,prefix;
        public final File memberRead;
        public Target(File write,File read,boolean merge){this(write,read,merge,null,"",null);}
        public Target(File write,File read,boolean merge,String group,String prefix,File memberRead){this.write=write;this.read=read;this.merge=merge;this.group=group;this.prefix=prefix;this.memberRead=memberRead;}
    }
    public interface Mapping {
        Target target(Map<String,Object> root)throws IOException;
        default String skippedReason(Map<String,Object> root){return "ROOT_REQUIRES_DESTINATION";}
    }
    public final Map<String,Object> manifest;
    public final Map<String,Target> targets=new LinkedHashMap<>(),groups=new LinkedHashMap<>();
    private final Map<String,String> rootGroups=new LinkedHashMap<>(),memberBefore=new LinkedHashMap<>();
    public final Map<String,String> before=new LinkedHashMap<>(),readBefore=new LinkedHashMap<>();
    public final List<String> warnings=new ArrayList<>();
    public long conflicts;
    private final BackupFileSystem fs;
    private final File task,index;
    private NativeRestorePlan(BackupFileSystem fs,File task,Map<String,Object> manifest){this.fs=fs;this.task=task;this.index=new File(task,"restore-records");this.manifest=manifest;}
    public static NativeRestorePlan inspect(BackupFileSystem fs,File task,File archive,Mapping mapping,Set<String> selected,BackupControl control)throws IOException{
        File payload=new File(task,"payload");fs.directory(payload);File index=new File(task,"restore-records");Map<String,Object> manifest;
        Set<String> materializable=new HashSet<>();
        try(InputStream input=fs.read(archive,fs.stat(archive));DataOutputStream records=new DataOutputStream(fs.create(index))){
            manifest=BackupArchive.read(input,new BackupArchive.Visitor(){
                public OutputStream payload(int ordinal,BackupArchive.Record record)throws IOException{return record.kind.equals("FILE")?fs.create(fs.child(payload,String.valueOf(ordinal))):null;}
                public void verified(int ordinal,BackupArchive.Record record)throws IOException{
                    if(record.kind.equals("FILE")||record.kind.equals("DIRECTORY")||record.kind.equals("LINK"))materializable.add(record.root);
                    Map<String,Object> row=new LinkedHashMap<>(record.metadata);row.put("ordinal",(long)ordinal);byte[] data=BackupJson.write(row,BackupLimits.RECORD);records.writeInt(data.length);records.write(data);}
            },control);
        }
        NativeRestorePlan plan=new NativeRestorePlan(fs,task,manifest);
        @SuppressWarnings("unchecked") List<Map<String,Object>> roots=(List<Map<String,Object>>)manifest.get("roots");
        for(Map<String,Object> root:roots){String scope=BackupJson.string(root,"scope");if(!selected.contains("application")&&!selected.contains(scope))continue;
            if(!materializable.contains(BackupJson.string(root,"id"))){plan.warnings.add("UNAVAILABLE_MEMBERS_NOT_DELETED");continue;}
            Target target=mapping.target(root);if(target==null){plan.warnings.add(mapping.skippedReason(root));continue;}
            String id=BackupLimits.root(BackupJson.string(root,"id")),group=target.group==null?id:BackupLimits.root(target.group);BackupLimits.path(target.prefix);
            plan.targets.put(id,target);plan.rootGroups.put(id,group);
            Target existing=plan.groups.putIfAbsent(group,target);if(existing!=null&&!existing.write.equals(target.write))throw new IOException("TARGET_GROUP_CONFLICT");
            if(existing==null){plan.before.put(group,BackupTree.digest(fs,target.write,control));plan.readBefore.put(group,target.read==null?"":BackupTree.digest(fs,target.read,control));}
            if(target.memberRead!=null)plan.memberBefore.put(id,BackupTree.digest(fs,target.memberRead,control));
        }
        if(plan.targets.isEmpty())throw new IOException("NO_RECOVERABLE_SCOPE");
        if(selected.contains("application")||selected.contains("sessions")||selected.contains("settings"))plan.warnings.add(DataFormatEvidence.restoreWarning(manifest));
        return plan;
    }
    private interface Each { void record(Map<String,Object> value)throws IOException; }
    public long previewConflicts()throws IOException{
        long[] count={0};each(row->{BackupArchive.Record record=new BackupArchive.Record(row);Target target=targets.get(record.root);if(target==null||!record.kind.equals("FILE"))return;
            File base=target.memberRead!=null?target.memberRead:target.prefix.isEmpty()?target.read:null;if(base==null||fs.stat(base).type.equals("MISSING"))return;
            try{File existing=record.path.isEmpty()?base:fs.child(base,record.path);if(!fs.stat(existing).type.equals("MISSING"))count[0]++;}
            catch(IOException error){warnings.add("TARGET_LINK_REQUIRES_REVIEW");}
        });return count[0];
    }
    private void each(Each action)throws IOException{
        try(DataInputStream in=new DataInputStream(fs.read(index,fs.stat(index)))){int first;while((first=in.read())!=-1){int size=(first<<24)|(in.readUnsignedByte()<<16)|(in.readUnsignedByte()<<8)|in.readUnsignedByte();
            if(size<2||size>BackupLimits.RECORD)throw new IOException("RESTORE_INDEX");byte[] data=new byte[size];in.readFully(data);action.record(BackupJson.read(data,BackupLimits.RECORD));}}
    }
    public List<String> buildCandidates(boolean overwrite,BackupControl control)throws IOException{
        File candidate=new File(task,"candidate");if(fs.stat(candidate).type.equals("MISSING"))fs.directory(candidate);
        long[] copied={0,0,0};
        for(var root:groups.entrySet()){
            Target target=root.getValue();if(!before.get(root.getKey()).equals(BackupTree.digest(fs,target.write,control)))throw new IOException("TARGET_CHANGED");
            if(target.read!=null&&!readBefore.get(root.getKey()).equals(BackupTree.digest(fs,target.read,control)))throw new IOException("TARGET_CHANGED");
            if(target.merge&&target.read!=null&&!fs.stat(target.read).type.equals("MISSING"))copyTree(target.read,fs.child(candidate,root.getKey()),control,0,copied);
        }
        for(var root:targets.entrySet()){
            Target target=root.getValue();if(target.prefix.isEmpty()||target.memberRead==null||!target.merge)continue;
            if(!memberBefore.get(root.getKey()).equals(BackupTree.digest(fs,target.memberRead,control)))throw new IOException("TARGET_CHANGED");
            File groupRoot=fs.child(candidate,rootGroups.get(root.getKey()));if(fs.stat(groupRoot).type.equals("MISSING"))fs.directory(groupRoot);
            fs.parents(groupRoot,target.prefix);File member=fs.child(groupRoot,target.prefix);
            if(fs.stat(member).type.equals("LINK")){fs.delete(member);if(!fs.stat(target.memberRead).type.equals("MISSING"))copyTree(target.memberRead,member,control,0,copied);}
        }
        Set<String> present=new LinkedHashSet<>();List<Map<String,Object>> links=new ArrayList<>();long[] counts={0,0};
        each(row->{control.check();BackupArchive.Record record=new BackupArchive.Record(row);if(!targets.containsKey(record.root))return;
            if(record.kind.equals("MISSING")||record.kind.equals("UNREADABLE")||record.kind.equals("EXCLUDED")){warnings.add("UNAVAILABLE_MEMBERS_NOT_DELETED");return;}
            String group=rootGroups.get(record.root);Target destination=targets.get(record.root);String relative=joined(destination.prefix,record.path);
            File root=fs.child(candidate,group);present.add(group);
            if(record.kind.equals("LINK")){if(links.size()>=16384)throw new IOException("LINK_LIMIT");links.add(row);return;}
            if(!relative.isEmpty()&&fs.stat(root).type.equals("MISSING"))fs.directory(root);
            if(!relative.isEmpty())fs.parents(root,relative);File target=relative.isEmpty()?root:fs.child(root,relative);BackupFileSystem.Node existing=fs.stat(target);
            if(record.kind.equals("DIRECTORY")){if(existing.type.equals("MISSING"))fs.directory(target);else if(!existing.type.equals("DIRECTORY"))throw new IOException("TARGET_TYPE_CONFLICT");return;}
            if(!existing.type.equals("MISSING")){conflicts++;if(!overwrite)return;if(existing.type.equals("DIRECTORY"))throw new IOException("TARGET_TYPE_CONFLICT");fs.delete(target);}
            File source=fs.child(new File(task,"payload"),String.valueOf(BackupJson.number(row,"ordinal")));
            try(InputStream in=fs.read(source,fs.stat(source));OutputStream out=fs.create(target)){copy(in,out,control,counts);}
            int mode=row.get("mode") instanceof Number?((Number)row.get("mode")).intValue():0600;fs.mode(target,(mode&0100)|0600);
            String hash;try(InputStream in=fs.read(target,fs.stat(target))){hash=BackupArchive.digest(in,control);}if(!hash.equals(record.sha256))throw new IOException("RESTORED_CHECKSUM");
            control.report("PREPARING_RESTORE",++counts[0],counts[1]);
        });
        Map<String,String> linkTargets=new LinkedHashMap<>();
        for(Map<String,Object> row:links){String root=BackupJson.string(row,"root"),path=BackupJson.string(row,"path"),target=BackupJson.string(row,"target");
            if(path.isEmpty()||target.startsWith("/")||target.indexOf('\\')>=0){saveLinkMetadata(row);continue;}
            String normalized=relativeTarget(path,target),group=rootGroups.get(root),prefix=targets.get(root).prefix;linkTargets.put(group+"/"+joined(prefix,path),group+"/"+joined(prefix,normalized));
        }
        for(var row:links){String original=BackupJson.string(row,"root"),rootId=rootGroups.get(original),path=joined(targets.get(original).prefix,BackupJson.string(row,"path")),key=rootId+"/"+path;if(!linkTargets.containsKey(key))continue;
            String target=linkTargets.get(key);Set<String> visited=new HashSet<>();visited.add(key);int hops=0;
            while(linkTargets.containsKey(target)){if(!visited.add(target)||++hops>BackupLimits.LINKS)throw new IOException("ARCHIVE_LINK_CYCLE");target=linkTargets.get(target);}
            if(target.equals(rootId+"/")||key.startsWith(target+"/"))throw new IOException("ARCHIVE_LINK_CYCLE");
            File root=fs.child(candidate,rootId);if(fs.stat(root).type.equals("MISSING"))fs.directory(root);
            File resolved=fs.child(root,target.substring(rootId.length()+1));if(fs.stat(resolved).type.equals("MISSING")||fs.stat(resolved).type.equals("LINK")){saveLinkMetadata(row);continue;}
            fs.parents(root,path);File link=fs.child(root,path);if(!fs.stat(link).type.equals("MISSING")){conflicts++;if(!overwrite)continue;if(fs.stat(link).type.equals("DIRECTORY"))throw new IOException("TARGET_TYPE_CONFLICT");fs.delete(link);}
            fs.symlink(BackupJson.string(row,"target"),link);
        }
        List<String> roots=new ArrayList<>();for(String id:present)if(!fs.stat(fs.child(candidate,id)).type.equals("MISSING"))roots.add(id);
        if(roots.isEmpty())throw new IOException("NO_RECOVERABLE_PAYLOAD");return roots;
    }
    private static String joined(String prefix,String path){return prefix.isEmpty()?path:path.isEmpty()?prefix:prefix+"/"+path;}
    private void saveLinkMetadata(Map<String,Object> row)throws IOException{
        File folder=new File(task,"unresolved-links");if(fs.stat(folder).type.equals("MISSING"))fs.directory(folder);
        File note=fs.child(folder,BackupJson.number(row,"ordinal")+".json");try(OutputStream out=fs.create(note)){out.write(BackupJson.write(row,BackupLimits.RECORD));}warnings.add("LINK_METADATA_RETAINED");
    }
    static String relativeTarget(String path,String target)throws IOException{
        List<String> parts=new ArrayList<>();int slash=path.lastIndexOf('/');if(slash>=0)parts.addAll(Arrays.asList(path.substring(0,slash).split("/")));
        for(String part:target.split("/",-1)){if(part.isEmpty()||part.equals("."))continue;if(part.equals("..")){if(parts.isEmpty())throw new IOException("ARCHIVE_LINK_ESCAPE");parts.remove(parts.size()-1);}else parts.add(part);}
        String normalized=String.join("/",parts);BackupLimits.path(normalized);return normalized;
    }
    private void copyTree(File source,File target,BackupControl control,int depth,long[] counts)throws IOException{
        if(depth>BackupLimits.DEPTH||++counts[0]>BackupLimits.ENTRIES)throw new IOException("PATH_DEPTH");control.check();BackupFileSystem.Node node=fs.stat(source);
        if(node.type.equals("DIRECTORY")){fs.directory(target);var children=fs.list(source);counts[2]=BackupLimits.add(counts[2],children.size(),BackupLimits.ENTRIES);
            for(String name:children){BackupLimits.path(name);if(name.contains("/"))throw new IOException("INVALID_CHILD");copyTree(new File(source,name),new File(target,name),control,depth+1,counts);}}
        else if(node.type.equals("LINK"))fs.symlink(fs.readLink(source),target);
        else if(node.type.equals("FILE")){try(InputStream in=fs.read(source,node);OutputStream out=fs.create(target)){copy(in,out,control,counts);}fs.mode(target,node.mode);}
        else throw new IOException("TARGET_UNREADABLE");
    }
    private static void copy(InputStream in,OutputStream out,BackupControl control,long[] counts)throws IOException{byte[] buffer=new byte[65536];int n;while((n=in.read(buffer))!=-1){control.check();counts[1]=BackupLimits.add(counts[1],n,BackupLimits.BYTES);out.write(buffer,0,n);}}
}
