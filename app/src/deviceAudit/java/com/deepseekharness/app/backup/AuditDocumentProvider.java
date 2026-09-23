package com.deepseekharness.app.backup;

import android.content.*;
import android.database.*;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import android.provider.OpenableColumns;
import java.io.*;
import java.util.*;
import java.util.concurrent.*;

/** 非导出的真实 ContentProvider：目标仅为本轮私有文件，可模拟无 seek 和授权撤销。 */
public final class AuditDocumentProvider extends ContentProvider {
    static final Map<String, Document> DOCUMENTS = new ConcurrentHashMap<>();
    static final class Document {
        final File file; final String name, behavior;
        final CountDownLatch written = new CountDownLatch(1);
        final CountDownLatch opened = new CountDownLatch(1);
        volatile ParcelFileDescriptor peer;
        volatile IOException failure;
        Document(File file, String name, String behavior) { this.file=file; this.name=name; this.behavior=behavior; }
    }
    static Uri create(Context context, File directory, String behavior) throws IOException {
        com.deepseekharness.app.DeviceAuditSupport.requireIsolated(context);
        File owned=directory.getCanonicalFile(), files=context.getFilesDir().getCanonicalFile();
        if (!owned.getParentFile().equals(files) || !owned.getName().matches("device-workflow-[a-f0-9-]{36}")) throw new IOException("TEST_DESTINATION_PATH");
        String id=UUID.randomUUID().toString(), name="DSHA-data-v5-"+id+".dshbak";
        DOCUMENTS.put(id,new Document(new File(owned,name),name,behavior));
        return Uri.parse("content://"+context.getPackageName()+".deviceaudit/"+id);
    }
    static Document document(Uri uri) throws FileNotFoundException {
        Document value=DOCUMENTS.get(uri.getLastPathSegment());
        if (value==null) throw new FileNotFoundException("TEST_DOCUMENT_UNKNOWN"); return value;
    }
    @Override public boolean onCreate() { return true; }
    @Override public String getType(Uri uri) { return "application/octet-stream"; }
    @Override public Cursor query(Uri uri,String[] projection,String selection,String[] args,String order) {
        try {
            Document value=document(uri);
            String[] columns=projection==null?new String[]{OpenableColumns.DISPLAY_NAME,OpenableColumns.SIZE}:projection;
            MatrixCursor cursor=new MatrixCursor(columns); Object[] row=new Object[columns.length];
            for(int i=0;i<columns.length;i++) row[i]=columns[i].equals(OpenableColumns.DISPLAY_NAME)?value.name:columns[i].equals(OpenableColumns.SIZE)?value.file.length():null;
            cursor.addRow(row); return cursor;
        } catch(IOException error) { throw new IllegalArgumentException(error); }
    }
    @Override public ParcelFileDescriptor openFile(Uri uri,String mode) throws FileNotFoundException {
        Document value=document(uri);
        if(value.behavior.equals("blocked-read")||value.behavior.equals("blocked-write"))try{
            ParcelFileDescriptor[] pipe=ParcelFileDescriptor.createReliablePipe();boolean reading=value.behavior.equals("blocked-read");
            value.peer=pipe[reading?1:0];value.opened.countDown();return pipe[reading?0:1];
        }catch(IOException error){throw new FileNotFoundException(error.toString());}
        if(mode.contains("w")) {
            if(value.behavior.equals("deny-write")) throw new SecurityException("TEST_ACCESS_REVOKED");
            if(value.behavior.equals("cancel")) NativeBackupJobs.get(getContext()).cancel();
            try {
                ParcelFileDescriptor[] pipe=ParcelFileDescriptor.createReliablePipe();
                new Thread(() -> {
                    try(InputStream input=new ParcelFileDescriptor.AutoCloseInputStream(pipe[0]); OutputStream output=new FileOutputStream(value.file)) {
                        byte[] buffer=new byte[8192]; int n; while((n=input.read(buffer))!=-1) output.write(buffer,0,n);
                    } catch(IOException error) { value.failure=error; }
                    finally { value.written.countDown(); }
                },"audit-document-pipe").start();
                return pipe[1];
            } catch(IOException error) { throw new FileNotFoundException(error.toString()); }
        }
        if(value.behavior.equals("no-readback")) throw new SecurityException("TEST_READ_PERMISSION_REVOKED");
        try {
            if(!value.written.await(30,TimeUnit.SECONDS)||value.failure!=null) throw new IOException("TEST_DOCUMENT_WRITE_FAILED");
            return ParcelFileDescriptor.open(value.file,ParcelFileDescriptor.MODE_READ_ONLY);
        } catch(Exception error) { throw new FileNotFoundException(error.toString()); }
    }
    private void blocked(Document document,android.os.CancellationSignal signal){
        CountDownLatch cancelled=new CountDownLatch(1);if(signal!=null)signal.setOnCancelListener(cancelled::countDown);document.opened.countDown();
        try{if(!cancelled.await(20,TimeUnit.SECONDS))throw new IllegalStateException("TEST_PROVIDER_DID_NOT_RECEIVE_CANCEL");}
        catch(InterruptedException error){Thread.currentThread().interrupt();throw new IllegalStateException(error);}
        throw new android.os.OperationCanceledException();
    }
    @Override public android.content.res.AssetFileDescriptor openAssetFile(Uri uri,String mode,android.os.CancellationSignal signal)throws FileNotFoundException{
        Document value=document(uri);if(value.behavior.equals("blocked-open"))blocked(value,signal);
        if(value.behavior.equals("slice"))return new android.content.res.AssetFileDescriptor(ParcelFileDescriptor.open(value.file,ParcelFileDescriptor.MODE_READ_ONLY),6,7);
        return new android.content.res.AssetFileDescriptor(openFile(uri,mode),0,android.content.res.AssetFileDescriptor.UNKNOWN_LENGTH);
    }
    @Override public android.content.res.AssetFileDescriptor openTypedAssetFile(Uri uri,String mime,android.os.Bundle options,android.os.CancellationSignal signal)throws FileNotFoundException{
        return openAssetFile(uri,"r",signal);
    }
    @Override public Cursor query(Uri uri,String[] projection,String selection,String[] args,String order,android.os.CancellationSignal signal){
        try{Document value=document(uri);if(value.behavior.equals("blocked-query"))blocked(value,signal);}
        catch(FileNotFoundException error){throw new IllegalArgumentException(error);}
        return query(uri,projection,selection,args,order);
    }
    @Override public Uri insert(Uri uri,ContentValues values) { throw new UnsupportedOperationException(); }
    @Override public int update(Uri uri,ContentValues values,String selection,String[] args) { throw new UnsupportedOperationException(); }
    @Override public int delete(Uri uri,String selection,String[] args) { throw new UnsupportedOperationException(); }
}
