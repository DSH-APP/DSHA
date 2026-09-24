package com.deepseekharness.app.vscreen;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import com.deepseekharness.app.BuildConfig;
import org.json.JSONObject;
import java.net.HttpURLConnection;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.concurrent.*;
import com.deepseekharness.app.util.ShellQuote;

/** App 持有认证连接和心跳；动作失败绝不换通道重放。 */
public final class VirtualScreenManager {
    private static final Object LOCK = new Object();
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final ScheduledExecutorService WORKER = Executors.newSingleThreadScheduledExecutor(r -> { Thread t=new Thread(r,"vscreen-lifecycle");t.setDaemon(true);return t; });
    private static volatile Context context;
    private static volatile String token="",channel="",generation="",lastError="";
    private static volatile int port;
    private static ScheduledFuture<?> heartbeat;
    private VirtualScreenManager() {}
    public static boolean supported(Context c){return !BuildConfig.LOW_ANDROID && android.os.Build.VERSION.SDK_INT>=30;}
    public static String channel(){return channel;}
    public static String generation(){return generation;}
    public static String error(){return lastError;}

    public static JSONObject start(Context ctx,String orientation){ return start(ctx,orientation,false); }
    public static JSONObject start(Context ctx,String orientation,boolean foreground){
        if(BuildConfig.LOW_ANDROID)return failure("VSCREEN_UNSUPPORTED_LOW");
        if(!supported(ctx))return failure("VSCREEN_API_30_REQUIRED");
        if(!"portrait".equals(orientation)&&!"landscape".equals(orientation))return failure("INVALID_ORIENTATION");
        synchronized(LOCK){
            context=ctx.getApplicationContext();
            int w="landscape".equals(orientation)?1792:1008,h="landscape".equals(orientation)?1008:1792;
            // 已有会话只改显示尺寸；不能以探测失败触发隐式重新启动。
            if(!token.isEmpty()){JSONObject value=remember(request("/vscreen/create","width="+w+"&height="+h));if(value.optBoolean("ok"))VirtualScreenForeground.present();return value;}
            VirtualScreenChannel selected=VirtualScreenChannels.choose(context);
            if(selected==null)return failure("DEVICE_CHANNEL_UNAVAILABLE");
            token=randomToken();port=8800+RANDOM.nextInt(100);channel=selected.id();
            try{
                String command="/system/bin/app_process "+ShellQuote.arg("-Djava.class.path="+context.getApplicationInfo().sourceDir)
                        +" /system/bin com.deepseekharness.app.vscreen.VirtualScreenCore --launch --port "+port+" --token "+token;
                String result=selected.start(context,command);
                if(result==null||!result.contains("[EXIT=0]")||!result.contains("DSHA_VSCREEN_STARTED")){
                    // 尝试清理可能已经启动的 core；不再执行 start。
                    stopLocked();return failure("VSCREEN_START_RESULT_UNKNOWN");
                }
                JSONObject created=remember(request("/vscreen/create","width="+w+"&height="+h));
                if(!created.optBoolean("ok")){stopLocked();return created;}
                heartbeat=WORKER.scheduleWithFixedDelay(()->{
                    synchronized(LOCK){
                        if(token.isEmpty())return;
                        VirtualScreenChannel current=VirtualScreenChannels.find(channel);
                        if(current==null||!current.available(context)){stopLocked();return;}
                        JSONObject ping=request("/vscreen/ping","");
                        if(!ping.optBoolean("ok"))stopLocked();
                    }
                },10,10,TimeUnit.SECONDS);
                VirtualScreenForeground.present();
                return created;
            }catch(Throwable e){stopLocked();return failure("VSCREEN_START_"+e.getClass().getSimpleName());}
        }
    }
    public static JSONObject status(Context c){synchronized(LOCK){return remember(request("/vscreen/status",""));}}
    public static JSONObject preview(Context c){synchronized(LOCK){return remember(request("/vscreen/preview",""));}}
    public static JSONObject previewSince(Context c,String gen,long seq){synchronized(LOCK){return remember(request("/vscreen/preview","since="+seq+"&generation="+encode(gen)));}}
    public static JSONObject touch(Context c,String gen,long seq,String stroke,int action,float x,float y){synchronized(LOCK){if(!generation.equals(gen)||gen.isEmpty())return failure("STALE_GENERATION");return request("/vscreen/touch","generation="+encode(gen)+"&frameSeq="+seq+"&stroke="+stroke+"&action="+action+"&x="+x+"&y="+y);}}
    public static JSONObject editor(Context c,String gen,String operation,JSONObject args){synchronized(LOCK){
        if(!generation.equals(gen)||gen.isEmpty())return failure("STALE_GENERATION");JSONObject info=status(c);if(!info.optBoolean("ok"))return info;
        return com.deepseekharness.app.DshaAccessibilityService.virtualControl(info.optInt("displayId",-1),operation,args);
    }}
    public static JSONObject tree(Context c){synchronized(LOCK){JSONObject frame=preview(c);if(!frame.optBoolean("ok"))return frame;JSONObject tree=com.deepseekharness.app.DshaAccessibilityService.virtualControl(frame.optInt("displayId",-1),"tree",new JSONObject());try{tree.put("generation",generation).put("frameSeq",frame.optLong("frameSeq")).put("displayId",frame.optInt("displayId"));}catch(Exception ignored){}return tree;}}
    public static JSONObject node(Context c,String gen,long seq,String node,String action,String text){synchronized(LOCK){JSONObject check=action(c,"check",gen,seq,"");if(!check.optBoolean("ok"))return check;try{return com.deepseekharness.app.DshaAccessibilityService.virtualControl(check.optInt("displayId",-1),"node",new JSONObject().put("nodeId",node).put("action",action).put("text",text));}catch(Exception e){return failure("INVALID_NODE_ACTION");}}}
    public static JSONObject launch(Context c,String pkg){
        synchronized(LOCK){
            if(pkg==null||!pkg.matches("[A-Za-z][A-Za-z0-9_]*(?:\\.[A-Za-z0-9_]+)+"))return failure("INVALID_PACKAGE");
            return remember(request("/vscreen/launch","package="+encode(pkg)));
        }
    }
    public static JSONObject action(Context c,String action,String observedGeneration,long seq,String params){
        synchronized(LOCK){
            if(!generation.equals(observedGeneration)||generation.isEmpty())return failure("STALE_GENERATION");
            return request("/vscreen/"+action,"generation="+encode(observedGeneration)+"&frameSeq="+seq+(params.isEmpty()?"":"&"+params));
        }
    }
    public static JSONObject type(Context c,String observedGeneration,long seq,String text){
        if(text==null||text.isEmpty()||text.length()>2000)return failure("INVALID_TEXT");
        synchronized(LOCK){
            if(text.matches("[\\x20-\\x7e]+"))return action(c,"type",observedGeneration,seq,"text="+encode(text));
            if(!com.deepseekharness.app.DshaAccessibilityService.isConnected())return failure("ACCESSIBILITY_REQUIRED_FOR_UNICODE");
            JSONObject checked=action(c,"check",observedGeneration,seq,"");
            if(!checked.optBoolean("ok"))return checked;
            String result=com.deepseekharness.app.DshaAccessibilityService.virtualInput(checked.optInt("displayId",-1),text);
            return result.equals("OK")?checked:failure(result);
        }
    }
    public static void stop(Context c){synchronized(LOCK){stopLocked();}}
    public static void revoke(){WORKER.execute(()->{synchronized(LOCK){stopLocked();}});}
    private static void stopLocked(){
        if(heartbeat!=null){heartbeat.cancel(false);heartbeat=null;}
        if(!token.isEmpty())request("/vscreen/close","");
        token="";channel="";generation="";
        VirtualScreenAccessibility.clear();
        VirtualScreenForeground.stopped();
        new android.os.Handler(android.os.Looper.getMainLooper()).post(VirtualScreenOverlayController::hide);
    }
    public static String bridge(Context c,String route,String query){
        String name=com.deepseekharness.app.util.VirtualScreenRoutes.operation(route);
        if(name.isEmpty())return failure("UNKNOWN_ROUTE").toString();
        String gen=value(query,"generation","");long seq;
        try{seq=Long.parseLong(value(query,"frameSeq","-1"));}catch(NumberFormatException e){return failure("INVALID_FRAME").toString();}
        JSONObject result;
        switch(name){
            case "create":result=start(c,value(query,"orientation","portrait"),true);break;
            case "status":result=status(c);break;
            case "launch":result=launch(c,value(query,"package",""));break;
            case "tree":result=tree(c);break;
            case "node":result=node(c,gen,seq,value(query,"nodeId",""),value(query,"action",""),value(query,"text",""));break;
            case "editor":try{result=editor(c,gen,"editor",new JSONObject());}catch(Exception e){result=failure("ACCESSIBILITY_OPERATION_UNKNOWN");}break;
            case "edit":try{result=editor(c,gen,"edit",new JSONObject().put("editorId",value(query,"editorId","")).put("text",value(query,"text","")).put("start",longValue(query,"start",0)).put("end",longValue(query,"end",0)));}catch(Exception e){result=failure("ACCESSIBILITY_OPERATION_UNKNOWN");}break;
            case "submit":try{result=editor(c,gen,"submit",new JSONObject().put("editorId",value(query,"editorId","")));}catch(Exception e){result=failure("ACCESSIBILITY_OPERATION_UNKNOWN");}break;
            case "touch":result=touch(c,gen,seq,value(query,"stroke",""),(int)longValue(query,"action",-1),floatValue(query,"x",0),floatValue(query,"y",0));break;
            case "preview":case "see":
                result=preview(c);
                if(result.optBoolean("ok")){
                    try{result.put("tree",com.deepseekharness.app.DshaAccessibilityService.virtualDump(result.optInt("displayId",-1)));}catch(Exception ignored){}
                }break;
            case "tap":case "swipe":case "key":result=action(c,name,gen,seq,query);break;
            case "type":result=type(c,gen,seq,value(query,"text",""));break;
            case "close":stop(c);result=success();break;
            default:result=failure("UNKNOWN_ROUTE");
        }
        return result.toString();
    }
    private static JSONObject remember(JSONObject value){
        if(value.optBoolean("ok")&&value.has("generation"))generation=value.optString("generation");
        try{value.put("channel",channel);}catch(Exception ignored){}return value;
    }
    private static JSONObject request(String route,String query){
        if(token.isEmpty())return failure("VSCREEN_NOT_RUNNING");
        HttpURLConnection c=null;
        try{
            c=(HttpURLConnection)new java.net.URL("http://127.0.0.1:"+port+route+(query.isEmpty()?"":"?"+query)).openConnection();
            c.setRequestProperty("Authorization","Bearer "+token);c.setConnectTimeout(2000);c.setReadTimeout(12000);
            int code=c.getResponseCode();java.io.InputStream stream=code>=400?c.getErrorStream():c.getInputStream();
            if(stream==null)return failure("VSCREEN_HTTP_"+code);
            java.io.ByteArrayOutputStream out=new java.io.ByteArrayOutputStream();
            try(java.io.InputStream in=stream){byte[] b=new byte[8192];int n;while((n=in.read(b))>=0){if(out.size()+n>4*1024*1024)return failure("VSCREEN_RESPONSE_LIMIT");out.write(b,0,n);}}
            JSONObject result=new JSONObject(out.toString("UTF-8"));if(!result.optBoolean("ok"))lastError=result.optString("error");return result;
        }catch(Exception e){return failure("VSCREEN_CONNECTION_"+e.getClass().getSimpleName());}
        finally{if(c!=null)c.disconnect();}
    }
    public static Bitmap previewBitmap(JSONObject value){
        try{byte[] b=android.util.Base64.decode(value.getString("previewB64"),android.util.Base64.DEFAULT);return BitmapFactory.decodeByteArray(b,0,b.length);}catch(Exception e){return null;}
    }
    public static String encode(String v){try{return URLEncoder.encode(v,"UTF-8");}catch(Exception e){return "";}}
    private static String value(String query,String key,String fallback){return com.deepseekharness.app.util.Query.param(query,key,fallback);}
    private static long longValue(String q,String k,long d){try{return Long.parseLong(value(q,k,String.valueOf(d)));}catch(Exception e){return d;}}
    private static float floatValue(String q,String k,float d){try{return Float.parseFloat(value(q,k,String.valueOf(d)));}catch(Exception e){return d;}}
    private static String randomToken(){byte[] b=new byte[24];RANDOM.nextBytes(b);StringBuilder s=new StringBuilder();for(byte v:b)s.append(String.format(java.util.Locale.ROOT,"%02x",v&255));return s.toString();}
    private static JSONObject success(){JSONObject j=new JSONObject();try{j.put("ok",true);}catch(Exception ignored){}return j;}
    private static JSONObject failure(String code){lastError=code;JSONObject j=new JSONObject();try{j.put("ok",false).put("error",code);}catch(Exception ignored){}return j;}
}

