package com.deepseekharness.app.util;

import com.deepseekharness.app.backup.BackupJson;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Set;

/** 迁移输出只接受一条完整结构化回执；错误文字中的 status 不能授予启动许可。 */
public final class Rc1MigrationResult {
    private Rc1MigrationResult() { }
    private static final String PREFIX="DSHA_RC1_MIGRATION=";
    public static Map<String,Object> parse(String output)throws IOException {
        Map<String,Object> result=null;
        if(output!=null)for(String line:output.split("\\r?\\n"))if(line.startsWith(PREFIX)) {
            if(result!=null)throw new IOException("RC1_MIGRATION_DUPLICATE_RESULT");
            result=BackupJson.read(line.substring(PREFIX.length()).getBytes(StandardCharsets.UTF_8),65536);
        }
        if(result==null)throw new IOException("RC1_MIGRATION_RESULT_MISSING");
        return result;
    }
    public static boolean allowsStart(Map<String,Object> value) {
        String status=String.valueOf(value.get("status"));
        return "skipped".equals(status)&&"DSH_MISSING".equals(value.get("reason"))
                || Set.of("prepared","already").contains(status)&&Boolean.TRUE.equals(value.get("protectionComplete"));
    }
}
