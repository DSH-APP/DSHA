package com.deepseekharness.app.backup;

import java.io.IOException;
import java.util.*;

/** 完整、兼容和最新是不同状态；未认证的旧描述不能冒充可回退运行时。 */
public final class RuntimeDescriptor {
    private static final List<String> IDENTITY_FIELDS=Arrays.asList("baseVersion","dshVersion","launcherContract","launcherInputs","bridgeProtocol","dataRead","dataWrite","inputs");
    private final Map<String,Object> value;
    public RuntimeDescriptor(Map<String,Object> value)throws IOException{
        if(BackupJson.number(value,"version")!=1||!BackupJson.string(value,"runtimeId").matches("[a-f0-9]{64}"))throw new IOException("RUNTIME_DESCRIPTOR");
        if(!BackupJson.string(value,"baseVersion").matches("[0-9]{1,9}")||!BackupJson.string(value,"dshVersion").matches("[A-Za-z0-9._+-]{1,80}"))throw new IOException("RUNTIME_DESCRIPTOR");
        if(!(value.get("inputs") instanceof Map)||!(value.get("dataRead") instanceof List))throw new IOException("RUNTIME_DESCRIPTOR");
        if(!BackupJson.string(value,"launcherContract").matches("[A-Z0-9_]{1,80}")||BackupJson.number(value,"bridgeProtocol")<1||BackupJson.number(value,"bridgeProtocol")>1000
                ||!BackupJson.string(value,"dataWrite").matches("[a-z0-9._+-]{1,80}")||((List<?>)value.get("dataRead")).size()>32)throw new IOException("RUNTIME_DESCRIPTOR");
        for(Object entry:(List<?>)value.get("dataRead"))if(!(entry instanceof String)||!((String)entry).matches("[a-z0-9._+-]{1,80}"))throw new IOException("RUNTIME_DESCRIPTOR");
        if(!((List<?>)value.get("dataRead")).contains(value.get("dataWrite")))throw new IOException("RUNTIME_DATA_CONTRACT");
        if(!value.get("runtimeId").equals(calculateId(value)))throw new IOException("RUNTIME_DESCRIPTOR_CHECKSUM");
        @SuppressWarnings("unchecked") Map<String,Object> frozen=(Map<String,Object>)freeze(BackupJson.read(BackupJson.write(value,BackupLimits.MANIFEST),BackupLimits.MANIFEST));this.value=frozen;
    }
    /** 与构建端 sort_keys / ensure_ascii 的紧凑 JSON 一致；诊断 APK 版本不进入身份。 */
    public static String calculateId(Map<String,Object> value)throws IOException{
        Map<String,Object> fields=new TreeMap<>();for(String key:IDENTITY_FIELDS)if(value.containsKey(key))fields.put(key,sorted(value.get(key)));
        String json=new String(BackupJson.write(fields,BackupLimits.MANIFEST),java.nio.charset.StandardCharsets.UTF_8);StringBuilder ascii=new StringBuilder(json.length());
        for(int i=0;i<json.length();i++){char c=json.charAt(i);if(c>127)ascii.append(String.format(Locale.ROOT,"\\u%04x",(int)c));else ascii.append(c);}
        return BackupArchive.hex(BackupArchive.sha().digest(ascii.toString().getBytes(java.nio.charset.StandardCharsets.US_ASCII)));
    }
    private static Object sorted(Object value)throws IOException{
        if(value instanceof Map){Map<String,Object> result=new TreeMap<>();for(var row:((Map<?,?>)value).entrySet()){if(!(row.getKey() instanceof String))throw new IOException("RUNTIME_DESCRIPTOR");result.put((String)row.getKey(),sorted(row.getValue()));}return result;}
        if(value instanceof List){List<Object> result=new ArrayList<>();for(Object child:(List<?>)value)result.add(sorted(child));return result;}return value;
    }
    private static Object freeze(Object value){
        if(value instanceof Map){Map<String,Object> result=new LinkedHashMap<>();for(var row:((Map<?,?>)value).entrySet())result.put((String)row.getKey(),freeze(row.getValue()));return Collections.unmodifiableMap(result);}
        if(value instanceof List){List<Object> result=new ArrayList<>();for(Object child:(List<?>)value)result.add(freeze(child));return Collections.unmodifiableList(result);}return value;
    }
    public String id(){return (String)value.get("runtimeId");}
    public boolean latest(RuntimeDescriptor expected){return id().equals(expected.id())&&Objects.equals(value.get("inputs"),expected.value.get("inputs"))&&compatible(expected);}
    public boolean compatible(RuntimeDescriptor expected){
        return Objects.equals(value.get("baseVersion"),expected.value.get("baseVersion"))
                &&Objects.equals(value.get("launcherContract"),expected.value.get("launcherContract"))
                &&Objects.equals(value.get("bridgeProtocol"),expected.value.get("bridgeProtocol"))
                &&((List<?>)expected.value.get("dataRead")).contains(value.get("dataWrite"));
    }
    public Map<String,Object> json(){return new LinkedHashMap<>(value);}
    public boolean canReadDataWrittenBy(RuntimeDescriptor current){return ((List<?>)value.get("dataRead")).contains(current.value.get("dataWrite"))&&DataFormatEvidence.sameReaderWriter(value,current.value);}
    public static boolean healthy(Map<String,Object> receipt,String runtimeId){
        if(receipt==null||!runtimeId.equals(receipt.get("runtimeId"))||!(receipt.get("nonce") instanceof String)||!((String)receipt.get("nonce")).matches("[a-f0-9]{32}"))return false;
        try{long port=BackupJson.number(receipt,"port");if(port<1||port>65535||BackupJson.number(receipt,"confirmedAt")<=0)return false;}catch(IOException invalid){return false;}
        for(String check:Arrays.asList("assets","nativeModules","process","authentication","localApi","renderer","dataRead","dataWrite","storageFreshReopened","processExited"))if(!Boolean.TRUE.equals(receipt.get(check)))return false;
        return true;
    }
}
