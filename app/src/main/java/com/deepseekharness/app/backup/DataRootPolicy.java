package com.deepseekharness.app.backup;

import com.deepseekharness.app.util.ManagedRuntimeLayout;
import java.util.*;

/** 本机状态不从用户归档恢复；可执行配置/受管工具只能导入隔离区，不覆盖活跃入口。 */
public final class DataRootPolicy {
    private DataRootPolicy(){}
    private static final Set<String> MACHINE=Set.of(".plugins.lock","bridge-token",".dsha-bridge-token",".dsha-web.pid",".dsha-web.identity",".dsha-stopped",
            "dsha-startup-checkpoints",".offline-extracted",".offline-identity",".offline-version",".runtime-descriptor.json",".runtime-health.json",
            "plugin-activations.json","plugin-safe-mode.json",".dsha-rc1-restore-generation");
    public static boolean machine(String name){return MACHINE.contains(name);}
    public static boolean directData(String name){return Set.of("sessions","storages","attachments","settings.yaml",".credentials.yaml",".env",".dsha-apikey").contains(name);}
    public static boolean quarantineCode(String name){
        return name.equals("cordis.patch.yml")||ManagedRuntimeLayout.paths().contains("root/.dsh/"+name)
                ||name.matches(".*\\.(?:js|cjs|mjs|py|sh)");
    }
}
