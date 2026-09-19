package com.deepseekharness.app.core;

import android.app.Application;
import android.content.Intent;
import androidx.lifecycle.AndroidViewModel;
import androidx.lifecycle.MutableLiveData;
import com.deepseekharness.app.util.ModelConfiguration;
import com.deepseekharness.app.util.UiText;
import com.google.gson.*;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import java.util.concurrent.*;

/** 原生表单通过 DSH 自己的设置与凭据 API 保存；不创建网页或另存一套模型设置。 */
public final class DshModelRepository extends AndroidViewModel {
    public final MutableLiveData<JsonObject> data=new MutableLiveData<>();
    public final MutableLiveData<String> message=new MutableLiveData<>("");
    public final MutableLiveData<Boolean> busy=new MutableLiveData<>(false);
    public final MutableLiveData<Long> savedRevision=new MutableLiveData<>(0L);
    public final MutableLiveData<ModelDiscovery> modelDiscovery=new MutableLiveData<>();
    private final ExecutorService io=Executors.newSingleThreadExecutor();
    private final HarnessController controller;
    private volatile boolean closed;
    private volatile HttpURLConnection active;
    private String base,cookie;private long generation,saveSerial,discoverySerial;
    public static final class ModelDiscovery {
        public final long serial;public final JsonArray models;
        ModelDiscovery(long serial,JsonArray models){this.serial=serial;this.models=models;}
    }
    public DshModelRepository(Application app){super(app);controller=HarnessController.get(app);}
    public void load(){submit(()->{connect();read();message.postValue(t("模型设置已同步","Model settings synced"));});}
    private void connect() throws Exception {
        if(controller.getWebAuthUrl().isEmpty()){
            if(!controller.isEnvironmentReady())throw new IOException(t("请先完成环境安装。","Install the environment first."));
            if(!controller.isStarting()&&!controller.startWeb(null))throw new IOException(t("当前无法连接配置服务，请稍后重试。","Cannot connect to settings right now. Retry later."));
            Intent service=new Intent(getApplication(),com.deepseekharness.app.HarnessService.class);
            if(android.os.Build.VERSION.SDK_INT>=26)getApplication().startForegroundService(service);else getApplication().startService(service);
            message.postValue(t("正在准备本机配置服务，表单将在这里显示…","Preparing local settings. The form will appear here…"));
            while(!closed&&controller.getWebAuthUrl().isEmpty()&&controller.isStarting())Thread.sleep(350);
        }
        if(closed)throw new InterruptedException();
        generation=controller.getWebGeneration();String url=controller.getWebAuthUrl();
        if(url.isEmpty())throw new IOException(t("配置服务未就绪，请查看启动日志后重试。","Settings are not ready. Check startup logs and retry."));
        URI parsed=URI.create(url);if(!"127.0.0.1".equals(parsed.getHost())||!"http".equals(parsed.getScheme()))throw new IOException("LOCAL_SETTINGS_ORIGIN");
        base="http://127.0.0.1:"+parsed.getPort();cookie=controller.exchangeDshAuthCookie();
        if(cookie==null||cookie.isEmpty())throw new IOException(t("本机鉴权未完成，请重试。","Local authentication incomplete. Retry."));
        checkGeneration();
    }
    private void checkGeneration() throws IOException {if(closed||generation!=controller.getWebGeneration()||controller.getWebAuthUrl().isEmpty())throw new IOException(t("服务已改变，请重新读取后保存；草稿仍保留。","Service changed. Reload before saving; your draft is retained."));}
    private JsonElement rpc(String method,JsonObject args) throws Exception {
        checkGeneration();String id=UUID.randomUUID().toString();JsonObject request=new JsonObject(),payload=new JsonObject();payload.add("args",args);
        request.addProperty("type","client-request");request.addProperty("rpcId",id);request.addProperty("method",method);request.add("payload",payload);
        HttpURLConnection connection=(HttpURLConnection)new URL(base+"/api/"+method).openConnection();active=connection;
        try{
            connection.setInstanceFollowRedirects(false);connection.setConnectTimeout(8000);connection.setReadTimeout(20000);connection.setRequestMethod("POST");connection.setDoOutput(true);
            connection.setRequestProperty("Cookie",cookie);connection.setRequestProperty("Origin",base);connection.setRequestProperty("Content-Type","application/json");
            byte[] body=request.toString().getBytes(StandardCharsets.UTF_8);connection.setFixedLengthStreamingMode(body.length);
            try(OutputStream out=connection.getOutputStream()){out.write(body);}
            if(connection.getResponseCode()!=200)throw new IOException("HTTP "+connection.getResponseCode());
            ByteArrayOutputStream bytes=new ByteArrayOutputStream();try(InputStream in=connection.getInputStream()){byte[] buffer=new byte[8192];int n;while((n=in.read(buffer))!=-1){if(bytes.size()+n>4*1024*1024)throw new IOException("SETTINGS_RESPONSE_LIMIT");bytes.write(buffer,0,n);}}
            JsonObject response=JsonParser.parseString(bytes.toString("UTF-8")).getAsJsonObject();
            if(!"server-response".equals(ModelConfiguration.text(response,"type"))||!id.equals(ModelConfiguration.text(response,"rpcId")))throw new IOException("SETTINGS_RESPONSE_MISMATCH");
            JsonObject result=response.getAsJsonObject("result");if(!result.get("ok").getAsBoolean()){
                JsonObject error=ModelConfiguration.object(result.get("error"));String code=ModelConfiguration.text(error,"code");
                if("llm/discoverModels".equals(method)){String detail=ModelConfiguration.text(error,"message");throw new IOException(t("无法获取可用模型：","Could not fetch available models: ")+(detail.isEmpty()?code:detail));}
                throw new IOException(code.contains("conflict")?t("配置已被其他页面修改，请重新读取后再保存。","Settings changed elsewhere. Reload before saving."):t("DSH 拒绝了此配置，请检查协议、模型与字段格式。","DSH refused this configuration. Check protocol, models and field formats.")+" ("+code+")");
            }
            checkGeneration();return result.has("value")?result.get("value"):JsonNull.INSTANCE;
        } finally {active=null;connection.disconnect();}
    }
    private void read() throws Exception {
        JsonObject result=new JsonObject();result.add("settings",rpc("settings/describe",new JsonObject()));result.add("providers",rpc("llm/listConfigurableProviders",new JsonObject()));
        result.addProperty("generation",generation);data.postValue(result);
    }
    public void save(String namespace,JsonArray operations,long expectedRevision,long openedGeneration,String keyRef,String key){
        submit(()->{
            if(openedGeneration!=generation)throw new IOException(t("请重新读取设置后保存。","Reload settings before saving."));checkGeneration();
            boolean settingsWritten=false;
            if(!operations.isEmpty()){
                JsonObject args=new JsonObject();args.addProperty("ns",namespace);args.add("ops",operations);args.addProperty("expectedRevision",expectedRevision);
                rpc("settings/mutate",args);settingsWritten=true;
            }
            if(!key.isEmpty()){
                try{JsonObject args=new JsonObject();args.addProperty("ref",keyRef);args.addProperty("value",key);rpc("credentials/set",args);
                    JsonObject describe=new JsonObject();describe.add("refs",ModelConfiguration.path(keyRef));JsonObject info=rpc("credentials/describe",describe).getAsJsonObject().getAsJsonObject(keyRef);
                    if(info==null||!info.has("configured")||!info.get("configured").getAsBoolean())throw new IOException("KEY_NOT_CONFIRMED");
                }catch(Exception failure){read();throw new IOException(settingsWritten?t("连接设置已保存，但密钥未确认保存。请重新打开该提供方补填密钥。","Connection saved, but the key was not confirmed. Reopen the provider and enter the key again."):t("密钥保存未确认，请重试。","Key save unconfirmed. Retry."));}
            }
            read();message.postValue(t("已保存，Web UI 使用同一份配置。","Saved. Web UI uses this same configuration."));savedRevision.postValue(++saveSerial);
        });
    }
    /** 使用 DSH 自己的 provider discovery；一次性密钥只在本轮 RPC 参数中传递。 */
    public void discoverModels(String namespace,String provider,String endpoint,String protocol,String key){
        submit(()->{
            checkGeneration();message.postValue(t("正在获取服务商的可用模型…","Fetching available models from the provider…"));
            JsonObject request=new JsonObject();
            if(provider!=null&&!provider.isEmpty())request.addProperty("provider",provider);
            if(endpoint!=null&&!endpoint.isEmpty())request.addProperty("baseURL",endpoint);
            if(protocol!=null&&!protocol.isEmpty())request.addProperty("api",protocol);
            if(key!=null&&!key.isEmpty())request.addProperty("apiKey",key);
            JsonObject args=new JsonObject();args.addProperty("settingsNs",namespace);args.add("request",request);
            JsonElement value=rpc("llm/discoverModels",args);if(!value.isJsonArray())throw new IOException("MODEL_DISCOVERY_RESPONSE");
            JsonArray found=value.getAsJsonArray().deepCopy();modelDiscovery.postValue(new ModelDiscovery(++discoverySerial,found));
            message.postValue(found.isEmpty()?t("服务商没有返回可用模型。","The provider returned no available models."):t("已获取可用模型，请选择要加入目录的项目。","Available models fetched. Choose which ones to add."));
        });
    }
    private interface Work{void run() throws Exception;}
    private void submit(Work work){if(Boolean.TRUE.equals(busy.getValue())||closed)return;busy.setValue(true);message.setValue(t("正在同步…","Syncing…"));io.execute(()->{try{work.run();}catch(InterruptedException cancelled){Thread.currentThread().interrupt();}catch(Exception failure){message.postValue(com.deepseekharness.app.util.SensitiveData.redact(String.valueOf(failure.getMessage())));}finally{busy.postValue(false);}});}
    private static String t(String zh,String en){return UiText.choose(zh,en);}
    @Override protected void onCleared(){closed=true;HttpURLConnection connection=active;if(connection!=null)connection.disconnect();io.shutdownNow();cookie=null;super.onCleared();}
}
