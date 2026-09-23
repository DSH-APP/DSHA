package com.deepseekharness.app.backup;

import com.google.gson.stream.*;
import com.google.gson.Strictness;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** 仅解析数据类型；拒绝重复键、超限深度和尾随内容，不进行任意类型反序列化。 */
public final class BackupJson {
    private BackupJson() { }
    public static Map<String,Object> read(byte[] bytes,int limit) throws IOException {
        if(bytes.length>limit)throw new IOException("METADATA_LIMIT");
        try(JsonReader reader=new JsonReader(new InputStreamReader(new ByteArrayInputStream(bytes),StandardCharsets.UTF_8))){
            reader.setStrictness(Strictness.STRICT);Object value=readValue(reader,0,new int[]{0});
            if(!(value instanceof Map)||reader.peek()!=JsonToken.END_DOCUMENT)throw new IOException("MANIFEST_FORMAT");
            @SuppressWarnings("unchecked") Map<String,Object> object=(Map<String,Object>)value;return object;
        }catch(IllegalStateException|NumberFormatException e){throw new IOException("MANIFEST_FORMAT",e);}
    }
    private static Object readValue(JsonReader reader,int depth,int[] fields)throws IOException{
        if(depth>BackupLimits.DEPTH||++fields[0]>100_000)throw new IOException("METADATA_LIMIT");
        switch(reader.peek()){
            case BEGIN_OBJECT:{Map<String,Object> values=new LinkedHashMap<>();reader.beginObject();while(reader.hasNext()){
                String key=reader.nextName();if(key.length()>2048||values.containsKey(key))throw new IOException("DUPLICATE_METADATA");
                values.put(key,readValue(reader,depth+1,fields));}reader.endObject();return values;}
            case BEGIN_ARRAY:{List<Object> values=new ArrayList<>();reader.beginArray();while(reader.hasNext())values.add(readValue(reader,depth+1,fields));reader.endArray();return values;}
            case STRING:{String value=reader.nextString();if(value.length()>BackupLimits.RECORD)throw new IOException("METADATA_LIMIT");return value;}
            case NUMBER:{String number=reader.nextString();if(number.length()>128)throw new IOException("NUMBER_LIMIT");
                try{return Long.parseLong(number);}catch(NumberFormatException integral){java.math.BigDecimal decimal=new java.math.BigDecimal(number);if(Math.abs((long)decimal.scale())>1000)throw new IOException("NUMBER_LIMIT");return decimal;}}
            case BOOLEAN:return reader.nextBoolean();
            case NULL:reader.nextNull();return null;
            default:throw new IOException("MANIFEST_FORMAT");
        }
    }
    public static Object readValue(JsonReader reader)throws IOException{return readValue(reader,0,new int[]{0});}
    public static byte[] write(Map<String,?> value,int limit)throws IOException{
        if(limit<0)throw new IOException("METADATA_LIMIT");ByteArrayOutputStream out=new ByteArrayOutputStream(Math.min(limit,8192));
        OutputStream bounded=new OutputStream(){int written;public void write(int value)throws IOException{if(written>=limit)throw new IOException("METADATA_LIMIT");out.write(value);written++;}
            public void write(byte[] bytes,int offset,int count)throws IOException{if(count>limit-written)throw new IOException("METADATA_LIMIT");out.write(bytes,offset,count);written+=count;}};
        try(JsonWriter writer=new JsonWriter(new OutputStreamWriter(bounded,StandardCharsets.UTF_8))){writeValue(writer,value,0,new int[]{0});}
        return out.toByteArray();
    }
    private static void writeValue(JsonWriter writer,Object value,int depth,int[] fields)throws IOException{
        if(depth>BackupLimits.DEPTH||++fields[0]>100_000)throw new IOException("METADATA_LIMIT");
        if(value==null){writer.nullValue();return;}
        if(value instanceof Map){writer.beginObject();for(var row:((Map<?,?>)value).entrySet()){if(!(row.getKey() instanceof String)||((String)row.getKey()).length()>2048)throw new IOException("METADATA_LIMIT");writer.name((String)row.getKey());writeValue(writer,row.getValue(),depth+1,fields);}writer.endObject();}
        else if(value instanceof Iterable){writer.beginArray();for(Object item:(Iterable<?>)value)writeValue(writer,item,depth+1,fields);writer.endArray();}
        else if(value instanceof Boolean)writer.value((Boolean)value);
        else if(value instanceof Number)writer.value((Number)value);
        else if(value instanceof String){if(((String)value).length()>BackupLimits.RECORD)throw new IOException("METADATA_LIMIT");writer.value((String)value);}
        else throw new IOException("MANIFEST_TYPE");
    }
    public static String string(Map<String,?> object,String key)throws IOException{Object v=object.get(key);if(!(v instanceof String))throw new IOException("MANIFEST_FIELD_"+key);return (String)v;}
    public static long number(Map<String,?> object,String key)throws IOException{Object v=object.get(key);if(!(v instanceof Number))throw new IOException("MANIFEST_FIELD_"+key);
        try{return v instanceof java.math.BigDecimal?((java.math.BigDecimal)v).longValueExact():((Number)v).longValue();}catch(ArithmeticException error){throw new IOException("MANIFEST_FIELD_"+key,error);}}
}
