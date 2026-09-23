package com.deepseekharness.app.backup;

import com.deepseekharness.app.util.Constants;
import java.util.*;

/** 系统迁移使用独立、定型白名单；不会迁移运行状态、权限、密钥或自动备份偏好。 */
public final class PortablePreferenceProjection {
    private PortablePreferenceProjection(){}
    private static final Set<String> TEXT=Set.of(Constants.KEY_PORT,Constants.KEY_WORKDIR,Constants.KEY_PERMISSION_MODE,
            "ui_theme","ui_language","plugin_sort_order","dns_mode",Constants.KEY_PICTURE_IN_PICTURE_LAYOUT);
    private static final Set<String> FLAGS=Set.of(Constants.KEY_CONFIRM_SHELL,Constants.KEY_DESKTOP_MODE,Constants.KEY_CHECK_UPDATE,"runtime_eco_mode",Constants.KEY_PICTURE_IN_PICTURE);
    public static boolean transferable(String key){return TEXT.contains(key)||FLAGS.contains(key);}
    public static Map<String,Object> project(Map<String,?> values){
        Map<String,Object> result=new LinkedHashMap<>();for(String key:TEXT){Object value=values.get(key);if(value instanceof String&&((String)value).length()<=2048&&((String)value).indexOf('\0')<0)result.put(key,value);}
        for(String key:FLAGS){Object value=values.get(key);if(value instanceof Boolean)result.put(key,value);}return result;
    }
    public static boolean mayImport(Map<String,?> active,Map<String,?> portable){
        if(!(portable.get("projectionVersion") instanceof Number)||((Number)portable.get("projectionVersion")).longValue()!=1)return false;
        for(String key:active.keySet())if(transferable(key))return false;return true;
    }
}
