package com.deepseekharness.app.backup;

import android.content.ContentResolver;
import android.content.res.AssetFileDescriptor;
import android.database.Cursor;
import android.database.CursorWrapper;
import android.net.Uri;
import android.os.CancellationSignal;
import android.os.OperationCanceledException;
import java.io.*;
import java.util.concurrent.atomic.AtomicBoolean;

/** SAF 的打开、查询和流读写都接入取消；不在界面线程等待远端 Provider 响应取消。 */
public final class DocumentStreams {
    private DocumentStreams() { }
    private static void closeQuietly(AutoCloseable value){if(value!=null)try{value.close();}catch(Exception ignored){}}
    private static AutoCloseable onCancel(BackupControl control,Runnable action){
        AtomicBoolean started=new AtomicBoolean();return control.onCancel(()->{
            if(!started.compareAndSet(false,true))return;
            Thread worker=new Thread(()->{try{action.run();}catch(RuntimeException ignored){}},"document-io-cancel");worker.setDaemon(true);worker.start();
        });
    }
    private static IOException io(BackupControl control,Exception error)throws IOException{
        if(control.isCancelled()||error instanceof OperationCanceledException){InterruptedIOException cancelled=new InterruptedIOException("CANCELLED");cancelled.initCause(error);return cancelled;}
        if(error instanceof SecurityException)return new IOException("SAF_PERMISSION_REVOKED",error);
        return error instanceof IOException?(IOException)error:new IOException("SAF_IO_FAILURE",error);
    }
    public static InputStream input(ContentResolver resolver,Uri uri,BackupControl control)throws IOException{
        CancellationSignal signal=new CancellationSignal();AutoCloseable opening=onCancel(control,signal::cancel);
        AssetFileDescriptor descriptor=null;InputStream stream=null;AutoCloseable reading=null;
        try{
            control.check();descriptor=resolver.openAssetFileDescriptor(uri,"r",signal);
            if(descriptor==null)throw new IOException("SAF_UNREADABLE");stream=descriptor.createInputStream();
            InputStream owned=stream;AssetFileDescriptor asset=descriptor;reading=onCancel(control,()->closeQuietly(owned));AutoCloseable registered=reading;
            control.check();
            return new FilterInputStream(owned){private boolean closed;
                @Override public int read()throws IOException{control.check();try{int value=in.read();control.check();return value;}catch(IOException error){throw io(control,error);}}
                @Override public int read(byte[] data,int offset,int count)throws IOException{control.check();try{int value=in.read(data,offset,count);control.check();return value;}catch(IOException error){throw io(control,error);}}
                @Override public void close()throws IOException{if(closed)return;closed=true;try{super.close();}catch(IOException error){throw io(control,error);}finally{closeQuietly(asset);closeQuietly(registered);}}
            };
        }catch(IOException|RuntimeException error){closeQuietly(reading);closeQuietly(stream);closeQuietly(descriptor);throw io(control,error);}
        finally{closeQuietly(opening);}
    }
    public static OutputStream output(ContentResolver resolver,Uri uri,BackupControl control)throws IOException{
        CancellationSignal signal=new CancellationSignal();AutoCloseable opening=onCancel(control,signal::cancel);
        AssetFileDescriptor descriptor=null;OutputStream stream=null;AutoCloseable writing=null;
        try{
            control.check();descriptor=resolver.openAssetFileDescriptor(uri,"w",signal);
            if(descriptor==null)throw new IOException("SAF_WRITE_FAILED");stream=descriptor.createOutputStream();
            OutputStream owned=stream;AssetFileDescriptor asset=descriptor;writing=onCancel(control,()->closeQuietly(owned));AutoCloseable registered=writing;
            control.check();
            return new FilterOutputStream(owned){private boolean closed;
                @Override public void write(int value)throws IOException{control.check();try{out.write(value);control.check();}catch(IOException error){throw io(control,error);}}
                @Override public void write(byte[] data,int offset,int count)throws IOException{control.check();try{out.write(data,offset,count);control.check();}catch(IOException error){throw io(control,error);}}
                @Override public void flush()throws IOException{control.check();try{out.flush();}catch(IOException error){throw io(control,error);}}
                @Override public void close()throws IOException{if(closed)return;closed=true;try{super.close();}catch(IOException error){throw io(control,error);}finally{closeQuietly(asset);closeQuietly(registered);}}
            };
        }catch(IOException|RuntimeException error){closeQuietly(writing);closeQuietly(stream);closeQuietly(descriptor);throw io(control,error);}
        finally{closeQuietly(opening);}
    }
    public static Cursor query(ContentResolver resolver,Uri uri,String[] columns,BackupControl control)throws IOException{
        CancellationSignal signal=new CancellationSignal();AutoCloseable querying=onCancel(control,signal::cancel);Cursor cursor=null;AutoCloseable reading=null;
        try{
            control.check();cursor=resolver.query(uri,columns,null,null,null,signal);if(cursor==null)throw new IOException("SAF_UNREADABLE");
            Cursor owned=cursor;reading=onCancel(control,()->closeQuietly(owned));AutoCloseable registered=reading;control.check();
            return new CursorWrapper(owned){private boolean closed;
                @Override public boolean moveToNext(){if(control.isCancelled())throw new OperationCanceledException();return super.moveToNext();}
                @Override public boolean moveToFirst(){if(control.isCancelled())throw new OperationCanceledException();return super.moveToFirst();}
                @Override public void close(){if(closed)return;closed=true;try{super.close();}finally{closeQuietly(registered);closeQuietly(querying);}}
            };
        }catch(IOException|RuntimeException error){closeQuietly(reading);closeQuietly(querying);closeQuietly(cursor);throw io(control,error);}
    }
}
