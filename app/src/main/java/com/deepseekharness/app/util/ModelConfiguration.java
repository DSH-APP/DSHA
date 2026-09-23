package com.deepseekharness.app.util;

import com.google.gson.*;
import java.net.URI;
import java.util.*;

/** 原生模型表单只写改动的字段，保留 DSH 的其他设置、模型能力与并发修订。 */
public final class ModelConfiguration {
    private ModelConfiguration() { }
    public static JsonObject object(JsonElement value) {
        return value != null && value.isJsonObject() ? value.getAsJsonObject() : new JsonObject();
    }
    public static String text(JsonObject value,String key) {
        JsonElement item=value.get(key);return item!=null&&item.isJsonPrimitive()?item.getAsString():"";
    }
    public static JsonElement at(JsonElement value,JsonArray path) {
        for(JsonElement key:path){if(value==null||!value.isJsonObject())return JsonNull.INSTANCE;value=value.getAsJsonObject().get(key.getAsString());}
        return value==null?JsonNull.INSTANCE:value;
    }
    public static JsonArray path(String... parts){JsonArray out=new JsonArray();for(String part:parts)out.add(part);return out;}
    public static JsonObject schemaNode(JsonObject schema,JsonArray path){
        JsonObject refs=object(schema.get("refs")),node=object(refs.get(text(schema,"uid")));
        for(JsonElement part:path){JsonElement id="dict".equals(text(node,"type"))?node.get("inner"):object(node.get("dict")).get(part.getAsString());node=object(id==null?null:refs.get(id.getAsString()));}
        return node;
    }
    public static List<String> choices(JsonObject schema,JsonArray path){
        JsonObject node=schemaNode(schema,path),refs=object(schema.get("refs"));List<String> out=new ArrayList<>();
        JsonArray list=node.has("list")?node.getAsJsonArray("list"):new JsonArray();
        for(JsonElement id:list){JsonObject item=object(refs.get(id.getAsString()));if("const".equals(text(item,"type"))&&item.has("value")&&item.get("value").isJsonPrimitive())out.add(item.get("value").getAsString());}
        return out;
    }
    public static JsonArray diff(JsonArray path,JsonObject original,JsonObject edited){
        JsonArray ops=new JsonArray();Set<String> keys=new LinkedHashSet<>(original.keySet());keys.addAll(edited.keySet());
        for(String key:keys){JsonElement before=original.get(key),after=edited.get(key);if(Objects.equals(before,after))continue;
            JsonObject op=new JsonObject();op.addProperty("op",after==null?"unset":"set");JsonArray location=path.deepCopy();location.add(key);op.add("path",location);if(after!=null)op.add("value",after.deepCopy());ops.add(op);}
        return ops;
    }
    public static void validateRoute(String route){if(!route.matches("[a-z][a-z0-9]*(?:-[a-z0-9]+)*"))throw new IllegalArgumentException("ROUTE");}
    /** 请求头只接受合法名称和值，大小写不同的同名项也不能互相覆盖。 */
    public static JsonObject headers(JsonArray rows) {
        JsonObject result=new JsonObject();Set<String> names=new HashSet<>();int bytes=0;
        if(rows==null||rows.size()>32)throw new IllegalArgumentException("HEADERS_LIMIT");
        for(JsonElement row:rows){
            JsonObject entry=object(row);String name=text(entry,"name").trim(),value=text(entry,"value");
            if(name.isEmpty()&&value.isEmpty())continue;
            if(name.length()>128||!name.matches("[!#$%&'*+.^_`|~0-9A-Za-z-]+"))throw new IllegalArgumentException("HEADERS_NAME");
            String lower=name.toLowerCase(Locale.ROOT);
            if(!names.add(lower))throw new IllegalArgumentException("HEADERS_DUPLICATE");
            if(Set.of("host","content-length","connection","transfer-encoding","proxy-authorization","cookie").contains(lower))throw new IllegalArgumentException("HEADERS_TRANSPORT");
            for(int i=0;i<value.length();i++){char c=value.charAt(i);if(c<32&&c!='\t'||c>255||c==127)throw new IllegalArgumentException("HEADERS_VALUE");}
            bytes+=name.length()+value.length();if(bytes>16384)throw new IllegalArgumentException("HEADERS_LIMIT");
            result.addProperty(name,value);
        }
        return result;
    }
    public static JsonArray headerRows(JsonObject headers){
        JsonArray rows=new JsonArray();headers.entrySet().forEach(e->{JsonObject row=new JsonObject();row.addProperty("name",e.getKey());row.addProperty("value",e.getValue().getAsString());rows.add(row);});return rows;
    }
    public static void validateUrl(String url,boolean required){
        if(url.isEmpty()&&!required)return;
        try{URI parsed=URI.create(url);if(!Set.of("https","http").contains(parsed.getScheme())||parsed.getHost()==null||parsed.getUserInfo()!=null||parsed.getFragment()!=null)throw new IllegalArgumentException();}
        catch(RuntimeException invalid){throw new IllegalArgumentException("URL");}
    }
    public static JsonArray validateModels(String json,boolean required){
        JsonElement parsed;try{parsed=JsonParser.parseString(json);}catch(RuntimeException invalid){throw new IllegalArgumentException("MODELS");}
        if(!parsed.isJsonArray())throw new IllegalArgumentException("MODELS");JsonArray models=parsed.getAsJsonArray();
        if(required&&models.isEmpty())throw new IllegalArgumentException("MODEL_REQUIRED");Set<String> ids=new HashSet<>();
        for(JsonElement item:models){if(!item.isJsonObject())throw new IllegalArgumentException("MODELS");JsonObject model=item.getAsJsonObject();String id=text(model,"id");
            if(id.trim().isEmpty()||!id.equals(id.trim())||!ids.add(id))throw new IllegalArgumentException("MODEL_ID");
            for(String number:List.of("contextWindow","maxTokens")){if(!model.has(number))continue;try{double value=model.get(number).getAsDouble();if(!Double.isFinite(value)||value<1||Math.floor(value)!=value)throw new IllegalArgumentException();}catch(RuntimeException invalid){throw new IllegalArgumentException("MODEL_CAPACITY");}}
        }return models;
    }
    /**
     * 把 DSH 发现的候选模型合并进当前草稿。已有条目始终优先，因此用户补过的
     * 容量、图片能力和兼容字段不会被一次目录查询冲掉。
     */
    public static JsonArray mergeDiscoveredModels(JsonArray current,JsonArray discovered,Set<String> selectedIds){
        JsonArray merged=current==null?new JsonArray():current.deepCopy();Set<String> known=new LinkedHashSet<>();
        for(JsonElement item:merged)if(item.isJsonObject())known.add(text(item.getAsJsonObject(),"id"));
        if(discovered==null||selectedIds==null||selectedIds.isEmpty())return merged;
        for(JsonElement item:discovered){
            if(!item.isJsonObject())continue;JsonObject candidate=item.getAsJsonObject();String id=text(candidate,"id");
            if(!selectedIds.contains(id)||id.isEmpty()||!id.equals(id.trim())||known.contains(id))continue;
            JsonObject adopted=new JsonObject();adopted.addProperty("id",id);
            copyString(candidate,adopted,"name");copyPositiveInteger(candidate,adopted,"contextWindow");copyPositiveInteger(candidate,adopted,"maxTokens");
            JsonElement modalities=candidate.get("inputModalities");if(validModalities(modalities))adopted.add("input",modalities.deepCopy());
            merged.add(adopted);known.add(id);
        }
        return validateModels(merged.toString(),false);
    }
    private static void copyString(JsonObject from,JsonObject to,String key){JsonElement value=from.get(key);if(value!=null&&value.isJsonPrimitive()&&value.getAsJsonPrimitive().isString()&&!value.getAsString().isEmpty())to.addProperty(key,value.getAsString());}
    private static void copyPositiveInteger(JsonObject from,JsonObject to,String key){JsonElement value=from.get(key);if(value==null||!value.isJsonPrimitive()||!value.getAsJsonPrimitive().isNumber())return;try{double number=value.getAsDouble();if(Double.isFinite(number)&&number>=1&&Math.floor(number)==number)to.addProperty(key,value.getAsLong());}catch(RuntimeException ignored){}}
    private static boolean validModalities(JsonElement value){
        if(value==null||!value.isJsonArray()||value.getAsJsonArray().isEmpty())return false;
        for(JsonElement item:value.getAsJsonArray())if(!item.isJsonPrimitive()||!Set.of("text","image").contains(item.getAsString()))return false;
        return true;
    }
    public static String keyReference(String provider){return "DSHA_"+provider.toUpperCase(Locale.ROOT).replaceAll("[^A-Z0-9]+","_")+"_API_KEY";}
}
