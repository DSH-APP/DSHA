package com.deepseekharness.app.backup;

import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.provider.DocumentsContract;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** 已授权的 SAF 树；不假设可 seek/rename，不需要所有文件访问权限。目录 ID 只在本机使用。 */
public final class SafBackupSource implements BackupSource {
    private static final String[] COLUMNS={DocumentsContract.Document.COLUMN_DOCUMENT_ID,DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_MIME_TYPE,DocumentsContract.Document.COLUMN_SIZE,DocumentsContract.Document.COLUMN_LAST_MODIFIED};
    private final Context context;private final Uri tree;private final String id,scope,label;
    private final Map<String,String> documents=new HashMap<>();private long keys;
    private long discoveredMetadata;
    private String capturedName="";
    private BackupControl control=new BackupControl(null);
    private static final class Row {
        String id,name,mime;long size,modified;
        boolean directory(){return DocumentsContract.Document.MIME_TYPE_DIR.equals(mime);}
        String token(){return id+"\n"+mime+"\n"+size+"\n"+modified;}
    }
    public SafBackupSource(Context context,Uri tree,String id,String scope,String label)throws IOException{
        if(!"content".equals(tree.getScheme())||!androidx.core.provider.DocumentsContractCompat.isTreeUri(tree))throw new IOException("SAF_TREE_REQUIRED");
        this.context=context.getApplicationContext();this.tree=tree;this.id=BackupLimits.root(id);this.scope=scope;this.label=label;
    }
    public String id(){return id;}public String scope(){return scope;}public boolean external(){return true;}
    @Override public String displayLocation(){return tree.toString();}
    public SafBackupSource copyForOperation()throws IOException{return new SafBackupSource(context,tree,id,scope,label);}
    private Uri document(String id){return DocumentsContract.buildDocumentUriUsingTree(tree,id);}
    private List<Row> query(Uri uri)throws IOException{
        try(Cursor cursor=DocumentStreams.query(context.getContentResolver(),uri,COLUMNS,control)){
            if(cursor==null)throw new IOException("SAF_UNREADABLE");List<Row> rows=new ArrayList<>();long metadata=0;
            while(cursor.moveToNext()){
                control.check();if(rows.size()>=BackupLimits.ENTRIES)throw new IOException("ENTRY_LIMIT");Row row=new Row();
                row.id=cursor.getString(0);row.name=cursor.getString(1);row.mime=cursor.getString(2);row.size=cursor.isNull(3)?-1:cursor.getLong(3);row.modified=cursor.isNull(4)?-1:cursor.getLong(4);
                if(row.id==null||row.id.length()>2048||row.name==null||row.name.length()>2048)throw new IOException("SAF_METADATA_LIMIT");
                metadata=BackupLimits.add(metadata,(row.id.length()+row.name.length())*2L,4L*1024*1024);rows.add(row);
            }return rows;
        }catch(android.os.OperationCanceledException error){throw new InterruptedIOException("CANCELLED");}
        catch(SecurityException error){throw new IOException("SAF_PERMISSION_REVOKED",error);}
    }
    private Row row(String id)throws IOException{List<Row> rows=query(document(id));if(rows.size()!=1||!rows.get(0).id.equals(id))throw new IOException("SAF_DOCUMENT_CHANGED");return rows.get(0);}
    private List<Row> children(String id)throws IOException{
        List<Row> children=query(DocumentsContract.buildChildDocumentsUriUsingTree(tree,id));children.sort(Comparator.comparing(r->r.name));return children;
    }
    private String proof(Row row,List<Row> children){StringBuilder value=new StringBuilder(row.token());if(children!=null)for(Row child:children)value.append('\n').append(child.id).append(':').append(child.name);
        return BackupArchive.hex(BackupArchive.sha().digest(value.toString().getBytes(StandardCharsets.UTF_8)));}
    @Override public void walk(Visit visitor,BackupControl control)throws IOException{
        this.control=control;documents.clear();keys=0;discoveredMetadata=0;walk(DocumentsContract.getTreeDocumentId(tree),"",visitor,new HashSet<>());
    }
    private void walk(String docId,String path,Visit visitor,Set<String> ancestors)throws IOException{
        BackupLimits.path(path);control.check();if(documents.size()>=BackupLimits.ENTRIES||ancestors.contains(docId))throw new IOException("SAF_TREE_CYCLE");
        keys=BackupLimits.add(keys,(path.length()+docId.length())*2L,8L*1024*1024);if(documents.put(path,docId)!=null)throw new IOException("DUPLICATE_PATH");
        Row row;List<Row> childRows=null;
        try{row=row(docId);if(path.isEmpty())capturedName=row.name;if(row.directory()){childRows=children(docId);
            for(Row child:childRows)discoveredMetadata=BackupLimits.add(discoveredMetadata,(child.id.length()+child.name.length())*2L,8L*1024*1024);}}
        catch(InterruptedIOException error){throw error;}catch(IOException error){visitor.item(new Item(path,"UNREADABLE",0,"","","SAF_PERMISSION_OR_READ_FAILURE"));return;}
        visitor.item(new Item(path,row.directory()?"DIRECTORY":"FILE",row.directory()?0:row.size,proof(row,childRows),"",""));
        if(childRows!=null){ancestors.add(docId);for(Row child:childRows){BackupLimits.path(child.name);if(child.name.isEmpty()||child.name.contains("/"))throw new IOException("SAF_NAME");
                walk(child.id,path.isEmpty()?child.name:path+"/"+child.name,visitor,ancestors);}ancestors.remove(docId);}
    }
    private String current(Item item)throws IOException{
        String id=documents.get(item.path);if(id==null)throw new IOException("SAF_DOCUMENT_CHANGED");Row current=row(id);
        if(!item.token.equals(proof(current,current.directory()?children(id):null)))throw new IOException("SOURCE_CHANGED");return id;
    }
    @Override public InputStream open(Item item)throws IOException{
        String id=current(item);InputStream input=DocumentStreams.input(context.getContentResolver(),document(id),control);
        return new FilterInputStream(input){boolean closed;public void close()throws IOException{if(closed)return;closed=true;super.close();current(item);}};
    }
    public void verify(Item item,String hash,BackupControl control)throws IOException{current(item);if(item.kind.equals("FILE"))try(InputStream input=open(item)){if(!hash.equals(BackupArchive.digest(input,control)))throw new IOException("SOURCE_CHANGED");}}
    @Override public Map<String,Object> description(){Map<String,Object> value=BackupSource.super.description();value.put("logicalKind","project");value.put("name",capturedName.isEmpty()?label:capturedName);value.put("source","SAF");return value;}
}
