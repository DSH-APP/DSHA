package com.deepseekharness.app.backup;

import java.util.*;

/** 区分数据格式与 APK/runtime 版本；只把相同读写实现作为有限的兼容证据。 */
public final class DataFormatEvidence {
    private DataFormatEvidence() { }
    private static final List<String> INPUTS=List.of("dsh-runtime.bin","agent-preset-patch.json","session-interaction-patch.json","persona-compat-patch.json");
    public static Map<String,Object> unknown(RuntimeDescriptor observed){
        Map<String,Object> value=new LinkedHashMap<>();value.put("schema",1L);value.put("status","unknown");value.put("formatId","");value.put("formatEpoch","");
        value.put("observedRuntimeId",observed==null?"":observed.id());value.put("observedReaderWriter",observed==null?"":fingerprint(observed.json()));
        value.put("evidence","NO_COMPLETE_FORMAT_INSPECTION");return value;
    }
    public static String fingerprint(Map<String,Object> descriptor){
        if(!(descriptor.get("inputs") instanceof Map))return "";Map<?,?> source=(Map<?,?>)descriptor.get("inputs");
        StringBuilder values=new StringBuilder();for(String name:INPUTS){Object value=source.get(name);if(!(value instanceof String)||!((String)value).matches("[a-f0-9]{64}"))return "";values.append(name).append(':').append(value).append('\n');}
        return BackupArchive.hex(BackupArchive.sha().digest(values.toString().getBytes(java.nio.charset.StandardCharsets.US_ASCII)));
    }
    public static boolean sameReaderWriter(Map<String,Object> first,Map<String,Object> second){String a=fingerprint(first);return !a.isEmpty()&&a.equals(fingerprint(second));}
    public static String restoreWarning(Map<String,Object> manifest){
        Object value=manifest.get("dataCompatibility");
        if(!(value instanceof Map))return "DATA_FORMAT_UNCONFIRMED";
        Map<?,?> evidence=(Map<?,?>)value;
        if(!(evidence.get("schema") instanceof Number)||((Number)evidence.get("schema")).longValue()!=1)return "DATA_FORMAT_METADATA_UNSUPPORTED";
        // 发件人的声明不成为本机读取验证；保持原有明确范围的恢复，同时提示未知。
        return "DATA_FORMAT_UNCONFIRMED";
    }
}
