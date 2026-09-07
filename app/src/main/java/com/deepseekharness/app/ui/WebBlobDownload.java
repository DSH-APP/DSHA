package com.deepseekharness.app.ui;

import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.webkit.WebMessage;
import android.webkit.WebMessagePort;
import android.webkit.WebView;
import com.deepseekharness.app.util.WebPreviewPolicy;
import com.deepseekharness.app.util.WebTransferPolicy;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.json.JSONObject;

/** 顶层同源 MessagePort 流式读取 Blob；不向任意 iframe 暴露 JavaScriptInterface。 */
public final class WebBlobDownload implements AutoCloseable {
    private final WebView view;
    private final WebDownloadModel model;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService writer = Executors.newSingleThreadExecutor();
    private Pipe active;
    private WebMessagePort port;
    public WebBlobDownload(WebView view, WebDownloadModel model) { this.view=view;this.model=model; }
    public void start(String base, String url, String name) {
        if (!WebPreviewPolicy.sameService(base,view.getUrl()) || !WebPreviewPolicy.pageDownload(base,url)) return;
        if (model.isBusy() || model.canRetrySave() || model.pickerOpen) return;
        try {
            Pipe pipe = new Pipe(); active=pipe;
            if (!model.download(base,url,null,name,-1,pipe.input)) { pipe.fail("下载未启动"); return; }
            String script;
            try (InputStream in=view.getContext().getAssets().open("web-integration/blob.js");ByteArrayOutputStream out=new ByteArrayOutputStream()) {
                byte[] b=new byte[4096];int n;while((n=in.read(b))!=-1)out.write(b,0,n);script=new String(out.toByteArray(),StandardCharsets.UTF_8);
            }
            view.evaluateJavascript(script, ignored -> {
                if (active!=pipe || !WebPreviewPolicy.sameService(base,view.getUrl())) { pipe.fail("网页已离开"); return; }
                WebMessagePort[] channel=view.createWebMessageChannel();port=channel[0];
                port.setWebMessageCallback(new WebMessagePort.WebMessageCallback() {
                    @Override public void onMessage(WebMessagePort source, WebMessage message) {
                        if (active!=pipe || source!=port) return;
                        String data=message.getData();
                        if(data==null||data.length()>40000){pipe.fail("网页文件消息无效");return;}
                        writer.execute(()->accept(pipe,source,data));
                    }
                });
                view.postWebMessage(new WebMessage("dsha-blob-port",new WebMessagePort[]{channel[1]}),Uri.parse(base));
                try { port.postMessage(new WebMessage(new JSONObject().put("type","blob").put("url",url).toString())); }
                catch(Exception e){pipe.fail("网页文件传输失败");}
                checkTimeout(pipe);
            });
        } catch(Exception error) { if(active!=null)active.fail("无法打开网页文件"); }
    }
    private void accept(Pipe pipe,WebMessagePort source,String json) {
        try {
            JSONObject value=new JSONObject(json);String type=value.optString("type");pipe.last=android.os.SystemClock.elapsedRealtime();
            if(type.equals("chunk")) {
                if(value.getInt("sequence")!=pipe.sequence++)throw new IOException("网页文件顺序错误");
                byte[] bytes=android.util.Base64.decode(value.getString("data"),android.util.Base64.NO_WRAP);
                pipe.bytes+=bytes.length;WebTransferPolicy.checkSize(pipe.bytes,-1,WebTransferPolicy.DOWNLOAD_LIMIT,false);
                pipe.output.write(bytes);
                main.post(()->{if(active==pipe&&port==source)try{source.postMessage(new WebMessage("{\"type\":\"ack\"}"));}catch(Exception e){pipe.fail("网页已关闭");}});
            } else if(type.equals("done")) {
                if(value.getLong("bytes")!=pipe.bytes)throw new IOException("网页文件不完整");
                pipe.complete=true;pipe.output.close();main.post(()->{if(port==source){port.close();port=null;}});
            } else if(type.equals("error"))throw new IOException(value.optString("message","网页文件读取失败"));
            else throw new IOException("未知网页文件消息");
        } catch(Exception error){pipe.fail(error.getMessage());}
    }
    private void checkTimeout(Pipe pipe) {
        main.postDelayed(()->{
            if(active!=pipe||pipe.complete||pipe.error!=null)return;
            if(android.os.SystemClock.elapsedRealtime()-pipe.last>30000)pipe.fail("网页文件传输超时");
            else checkTimeout(pipe);
        },5000);
    }
    @Override public void close(){if(active!=null)active.fail("网页已关闭");active=null;if(port!=null){port.close();port=null;}writer.shutdownNow();}
    private static final class Pipe {
        volatile IOException error;volatile boolean complete;volatile long last=android.os.SystemClock.elapsedRealtime();
        long bytes;int sequence;
        final PipedOutputStream output=new PipedOutputStream();
        final InputStream input;
        Pipe() throws IOException {
            PipedInputStream raw=new PipedInputStream(output,65536);
            input=new FilterInputStream(raw) {
                @Override public int read(byte[] b,int o,int n)throws IOException{
                    if(error!=null)throw error;int count=super.read(b,o,n);
                    if(error!=null)throw error;if(count<0&&!complete)throw new IOException("网页文件未传完");return count;
                }
                @Override public void close()throws IOException{super.close();output.close();}
            };
        }
        void fail(String why){error=new IOException(why);try{output.close();}catch(IOException ignored){}}
    }
}
