package com.deepseekharness.app.ui;

import android.app.*;
import android.content.*;
import android.net.Uri;
import android.os.Bundle;
import android.view.accessibility.AccessibilityNodeInfo;
import androidx.appcompat.app.AppCompatActivity;
import com.deepseekharness.app.util.Constants;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.BooleanSupplier;

/** 独立 HTTP 页面 + 浏览器真实 IndexedDB/下载/Activity 重建，不接触用户会话。 */
public final class WebRegressionAudit extends Instrumentation {
    private Bundle arguments;
    private Activity page;
    private Fixture server;
    private WebDownloadModel downloader;
    private void check(boolean ok,String why) { if (!ok) throw new AssertionError(why); }
    private void phase(String text) { Bundle b=new Bundle(); b.putString("phase",text);sendStatus(1,b); }
    @Override public void onCreate(Bundle args) { arguments=args;super.onCreate(args);start(); }
    private void until(BooleanSupplier check,long ms,String reason) throws Exception {
        long end=System.currentTimeMillis()+ms;
        while(!check.getAsBoolean()&&System.currentTimeMillis()<end)Thread.sleep(100);
        check(check.getAsBoolean(),reason);
    }
    private Object field(Object owner,String name) {
        try { java.lang.reflect.Field f=owner.getClass().getDeclaredField(name);f.setAccessible(true);return f.get(owner); }
        catch(Exception e){throw new RuntimeException(e);}
    }
    private void shell(String command) throws Exception {
        try(android.os.ParcelFileDescriptor fd=getUiAutomation().executeShellCommand(command);InputStream in=new android.os.ParcelFileDescriptor.AutoCloseInputStream(fd)){while(in.read()!=-1){}}
    }
    private byte[] read(InputStream in) throws IOException { try(in;ByteArrayOutputStream out=new ByteArrayOutputStream()){byte[] b=new byte[65536];int n;while((n=in.read(b))!=-1)out.write(b,0,n);return out.toByteArray();} }
    private Activity open(String type) throws Exception {
        server.reports.remove("loaded");
        Intent intent=new Intent().setClassName(getTargetContext(),type).putExtra("url",server.base+"?owned=web-check").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        ActivityMonitor monitor=addMonitor(type,null,false);
        Activity activity;
        try {
            runOnMainSync(()->getTargetContext().startActivity(intent));
            activity=waitForMonitorWithTimeout(monitor,15000);
            check(activity!=null,"浏览器页面 15 秒内未打开");
        } finally {removeMonitor(monitor);}
        until(()->server.reports.containsKey("loaded"),30000,"独立网页未加载");
        return activity;
    }
    private String command(String command,String key) throws Exception {
        server.reports.remove(key);server.command=command;
        try { until(()->server.reports.containsKey(key),12000,"网页未完成："+command); }
        catch(AssertionError error){throw new AssertionError(error.getMessage()+" / "+server.reports,error);}
        return server.reports.get(key);
    }
    private boolean clickSave(AccessibilityNodeInfo node) {
        if(node==null)return false;
        String id=String.valueOf(node.getViewIdResourceName());String text=String.valueOf(node.getText());
        if(node.isClickable()&&node.isEnabled()&&(id.endsWith(":id/button1")||text.equals("保存")||text.equals("SAVE")||text.equals("Save")))return node.performAction(AccessibilityNodeInfo.ACTION_CLICK);
        for(int i=0;i<node.getChildCount();i++)if(clickSave(node.getChild(i)))return true;
        return false;
    }
    private WebDownloadModel model() {
        WebDownloads value=(WebDownloads)field(page,"downloads");return value.model;
    }
    @Override public void onStart() {
        Bundle result=new Bundle();String type=arguments.getString("engine","gecko").equals("standard")?"com.deepseekharness.app.ui.WebPreviewActivity":"com.deepseekharness.app.ui.GeckoPreviewActivity";
        android.content.SharedPreferences prefs=getTargetContext().getSharedPreferences(Constants.PREFS,0);
        try {
            shell("input keyevent 224");shell("wm dismiss-keyguard");shell("am start -W -n com.dsh.client/com.deepseekharness.app.ui.MainActivity");
            server=new Fixture();
            page=open(type);
            command("seed","seeded");
            Object engine=field(page,type.endsWith("WebPreviewActivity")?"webView":"session");
            ActivityMonitor monitor=addMonitor(type,null,false);
            runOnMainSync(()->page.recreate());
            Activity recreated=waitForMonitorWithTimeout(monitor,20000);removeMonitor(monitor);
            check(recreated!=null,"旋转式重建未创建新页面");page=recreated;
            check(field(page,type.endsWith("WebPreviewActivity")?"webView":"session")==engine,"旋转重建丢失浏览会话");
            org.json.JSONObject retained=new org.json.JSONObject(command("retained","retained"));
            check(retained.getInt("images")==1&&retained.getString("text").equals("旋转前未发送文字")&&Math.abs(retained.getDouble("scroll")-713)<1,"旋转丢失草稿或阅读位置："+retained);
            phase("旋转重建通过：浏览会话、未发图片、文字、阅读位置保留");
            for(String layer:new String[]{"modal","drawer","preview"}) {
                command(layer,layer+"Ready");
                runOnMainSync(()->((AppCompatActivity)page).getOnBackPressedDispatcher().onBackPressed());
                until(()->server.reports.containsKey(layer+"Closed"),5000,"返回未关闭"+layer);
                check(!page.isFinishing(),"关闭"+layer+"时退出页面");
            }
            phase("系统返回通过：弹层、抽屉、详情预览逐层关闭");
            runOnMainSync(()->page.finish());Thread.sleep(500);
            page=open(type);
            org.json.JSONObject restored=new org.json.JSONObject(command("restored","restored"));
            check(restored.getInt("images")==1&&restored.getString("bytes").equals("owned-image-bytes-123"),"新浏览会话未恢复图片字节："+restored);
            check(Math.abs(restored.getDouble("scroll")-713)<1,"新浏览会话未恢复阅读位置："+restored+" / "+server.reports);
            phase("浏览会话重新创建通过：真实 IndexedDB 图片字节和阅读位置恢复");
            server.command="download";
            until(()->model().pickerOpen,12000,"网页下载未打开系统保存位置选择器："+model().state.getValue());
            Thread.sleep(700);
            check(clickSave(getUiAutomation().getRootInActiveWindow()),"找不到系统保存按钮");
            until(()->model().lastSavedUri()!=null,15000,"SAF 保存未完成："+model().state.getValue());
            Uri saved=model().lastSavedUri();
            check(Arrays.equals(read(getTargetContext().getContentResolver().openInputStream(saved)),Fixture.PAYLOAD),"SAF 文件字节不同");
            check(android.provider.DocumentsContract.deleteDocument(getTargetContext().getContentResolver(),saved),"未删除本次测试下载");
            phase("网页下载通过：真实点击导出、SAF 选择保存、完整字节回读、清理测试输出");
            server.command="blobDownload";
            until(()->model().pickerOpen,12000,"Blob 下载未打开保存选择器："+model().state.getValue());
            Thread.sleep(600);check(clickSave(getUiAutomation().getRootInActiveWindow()),"找不到 Blob 文件保存按钮");
            until(()->model().lastSavedUri()!=null&&!model().lastSavedUri().equals(saved),15000,"Blob 保存未完成");
            Uri blob=model().lastSavedUri();
            check(new String(read(getTargetContext().getContentResolver().openInputStream(blob)),StandardCharsets.UTF_8).equals("owned-blob-download"),"Blob 下载字节不同");
            check(android.provider.DocumentsContract.deleteDocument(getTargetContext().getContentResolver(),blob),"未删除测试 Blob 文件");
            phase("网页 Blob 导出通过：临时文件流式保存和字节校验");
            testUpload();
            command("deleteImages","deleted");runOnMainSync(()->page.finish());Thread.sleep(400);page=open(type);
            check(command("cleared","cleared").equals("0"),"已删除图片被重新恢复");
            command("cleanup","cleanup");
            phase("图片移除后重建没有复活已删除附件");
            testDownloads();
            result.putString("result","PASS "+type);
        } catch(Throwable e){result.putString("failure",android.util.Log.getStackTraceString(e));}
        finally {
            if(page!=null)runOnMainSync(()->page.finish());
            if(downloader!=null)runOnMainSync(()->{downloader.cancel();downloader.discard();});
            if(server!=null)server.close();
        }
        finish(result.containsKey("failure")?1:0,result);
    }
    private void testUpload() throws Exception {
        String image="iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+jfZkAAAAASUVORK5CYII=";
        File folder=new File(getTargetContext().getCacheDir(),"updates");folder.mkdirs();
        File file=new File(folder,"web-owned-upload-"+UUID.randomUUID()+".png");
        try(FileOutputStream out=new FileOutputStream(file)){out.write(android.util.Base64.decode(image,android.util.Base64.DEFAULT));}
        Uri uri=androidx.core.content.FileProvider.getUriForFile(getTargetContext(),getTargetContext().getPackageName()+".updates",file);
        IntentFilter filter=new IntentFilter();filter.addAction(Intent.ACTION_GET_CONTENT);filter.addAction(Intent.ACTION_OPEN_DOCUMENT);
        filter.addCategory(Intent.CATEGORY_OPENABLE);filter.addDataType("*/*");
        ActivityMonitor chooser=addMonitor(filter,new ActivityResult(Activity.RESULT_OK,new Intent().setData(uri)),true);
        try {
            org.json.JSONObject position=new org.json.JSONObject(command("upload","uploadReady"));
            android.view.View container=(android.view.View)field(page,"container");int[] origin=new int[2];runOnMainSync(()->container.getLocationOnScreen(origin));
            float ratio=(float)(container.getWidth()/position.getDouble("width"));
            float x=origin[0]+(float)position.getDouble("x")*ratio,y=origin[1]+(float)position.getDouble("y")*ratio;
            check(y>origin[1]&&y<origin[1]+container.getHeight(),"测试上传按钮不在网页可见区域");
            long now=android.os.SystemClock.uptimeMillis();
            android.view.MotionEvent down=android.view.MotionEvent.obtain(now,now,android.view.MotionEvent.ACTION_DOWN,x,y,0);
            android.view.MotionEvent up=android.view.MotionEvent.obtain(now,now+60,android.view.MotionEvent.ACTION_UP,x,y,0);
            down.setSource(android.view.InputDevice.SOURCE_TOUCHSCREEN);up.setSource(android.view.InputDevice.SOURCE_TOUCHSCREEN);
            try {getUiAutomation().injectInputEvent(down,true);getUiAutomation().injectInputEvent(up,true);}finally{down.recycle();up.recycle();}
            until(()->server.reports.containsKey("uploaded"),12000,"文件选择未完成 / monitor="+chooser.getHits());
            check(server.reports.get("uploaded").equals(image),"网页文件选择回传的字节不同");
            check(chooser.getHits()>0,"未经过浏览器原生文件选择回调");
            check(WebUploads.fallback(new Intent(Intent.ACTION_OPEN_DOCUMENT).setType("image/*").putExtra(Intent.EXTRA_ALLOW_MULTIPLE,true))
                    .getBooleanExtra(Intent.EXTRA_ALLOW_MULTIPLE,false),"备用选择器丢失多选请求");
            try { WebUploads.copy(getTargetContext(),Collections.nCopies(21,uri));throw new AssertionError("超过 20 个上传文件未拒绝"); }catch(IOException expected){}
            try { WebUploads.copy(getTargetContext(),List.of(Uri.parse("file:///invalid/private")));throw new AssertionError("非授权本地路径未拒绝"); }catch(IOException expected){}
            phase("文件上传通过：真实内核选择回调、内容 URI 复制、PNG 字节回读、多选上限与路径限制");
        } finally {removeMonitor(chooser);file.delete();}
    }
    private void testDownloads() throws Exception {
        runOnMainSync(()->downloader=new WebDownloadModel((Application)getTargetContext().getApplicationContext()));
        runOnMainSync(()->downloader.download(server.base,server.base+"truncated","owned-cookie=secret","bad.zip",-1,null));
        until(()->!downloader.isBusy(),6000,"不完整下载未结束");
        until(()->downloader.state.getValue().phase().equals("error"),2000,"不完整下载被接受");
        runOnMainSync(()->downloader.download(server.base,server.base+"redirect","owned-cookie=secret","bad.zip",-1,null));
        until(()->!downloader.isBusy(),6000,"外部跳转未结束");
        check(!downloader.canRetrySave(),"外部跳转留下可保存文件");
        runOnMainSync(()->downloader.download(server.base,server.base+"slow",null,"cancel.zip",-1,null));
        until(()->downloader.state.getValue().bytes()>0,6000,"慢速下载没有进度");
        runOnMainSync(()->downloader.cancel());
        until(()->!downloader.isBusy(),6000,"取消没有终止下载");
        check(!downloader.canRetrySave(),"取消留下可保存文件");
        phase("失败边界通过：不完整响应拒绝、跨服务跳转拒绝、真实流下载取消与缓存清理");
        runOnMainSync(()->downloader.download(server.base,server.base+"file.zip",null,"restored.zip",-1,null));
        until(()->downloader.canRetrySave()&&!downloader.isBusy(),6000,"恢复样本未下载完成");
        Bundle state=new Bundle();runOnMainSync(()->downloader.saveState(state));
        runOnMainSync(()->{downloader=new WebDownloadModel((Application)getTargetContext().getApplicationContext());downloader.restoreState(state);});
        check(downloader.canRetrySave(),"重建下载模型丢失完整缓存");
        runOnMainSync(()->downloader.save(Uri.parse("content://dsha-invalid-owned-test/not-writable")));
        until(()->!downloader.isBusy(),5000,"失败写入未结束");
        check(downloader.canRetrySave(),"写入失败丢失了可重试文件");
        File destination=new File(new File(getTargetContext().getCacheDir(),"updates"),"web-owned-save-"+UUID.randomUUID()+".zip");
        Uri destinationUri=androidx.core.content.FileProvider.getUriForFile(getTargetContext(),getTargetContext().getPackageName()+".updates",destination);
        try {
            runOnMainSync(()->downloader.save(destinationUri));
            until(()->downloader.lastSavedUri()!=null&&!downloader.isBusy(),6000,"更换位置未保存成功");
            check(Arrays.equals(read(new FileInputStream(destination)),Fixture.PAYLOAD),"缓存重建后文件字节错误");
            check(!downloader.canRetrySave(),"保存完成未清理缓存");
        }finally{destination.delete();}
        phase("保存恢复通过：下载模型重建、输出位置失败后保留缓存、更换位置与最终摘要校验");
    }
    private final class Fixture implements AutoCloseable {
        static final byte[] PAYLOAD="PK\u0003\u0004DSHA-independent-export-123456789".getBytes(StandardCharsets.UTF_8);
        final ServerSocket socket=new ServerSocket(0,16,InetAddress.getByName("127.0.0.1"));
        final String base="http://127.0.0.1:"+socket.getLocalPort()+"/";
        final Map<String,String> reports=new ConcurrentHashMap<>();
        final ExecutorService workers=Executors.newCachedThreadPool();
        volatile String command="";volatile boolean closed;
        final String name="DSHA-web-check-"+UUID.randomUUID()+".zip";
        Fixture() throws IOException {workers.submit(()->{while(!closed)try{Socket accepted=socket.accept();workers.submit(()->handle(accepted));}catch(IOException e){if(!closed)throw new RuntimeException(e);}});}
        private void handle(Socket client){
            try(client){
                client.setSoTimeout(5000);BufferedReader reader=new BufferedReader(new InputStreamReader(client.getInputStream(),StandardCharsets.US_ASCII));
                String first=reader.readLine();if(first==null)return;String path=first.split(" ")[1];String line;while((line=reader.readLine())!=null&&!line.isEmpty()){}
                String content="text/plain; charset=utf-8";byte[] bytes;
                String extra="",status="200 OK";
                if(path.startsWith("/report?")){Uri uri=Uri.parse(base.substring(0,base.length()-1)+path);reports.put(uri.getQueryParameter("key"),uri.getQueryParameter("value"));bytes="ok".getBytes(StandardCharsets.UTF_8);}
                else if(path.equals("/command"))bytes=command.getBytes(StandardCharsets.UTF_8);
                else if(path.equals("/client.js")){bytes=read(getTargetContext().getAssets().open("app-integration/client.js"));content="application/javascript";}
                else if(path.equals("/file.zip")){bytes=PAYLOAD;content="application/zip";extra="Content-Disposition: attachment; filename=\""+name+"\"\r\n";}
                else if(path.equals("/redirect")){status="302 Found";extra="Location: http://127.0.0.1:1/rejected\r\n";bytes=new byte[0];}
                else if(path.equals("/truncated")){bytes=PAYLOAD;}
                else if(path.equals("/slow")){bytes=new byte[1024*1024];}
                else {bytes=read(getContext().getAssets().open("web-state-fixture.html"));content="text/html; charset=utf-8";}
                OutputStream out=client.getOutputStream();long length=path.equals("/truncated")?bytes.length+100:bytes.length;
                out.write(("HTTP/1.1 "+status+"\r\nContent-Type: "+content+"\r\nContent-Length: "+length+"\r\nCache-Control: no-store\r\nConnection: close\r\n"+extra+"\r\n").getBytes(StandardCharsets.US_ASCII));
                if(path.equals("/slow")){for(int i=0;i<bytes.length;i+=4096){out.write(bytes,i,4096);out.flush();Thread.sleep(30);}}
                else out.write(bytes);out.flush();
            }catch(Exception ignored){}
        }
        @Override public void close(){closed=true;try{socket.close();}catch(IOException ignored){}workers.shutdownNow();}
    }
}
