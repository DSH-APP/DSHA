package com.deepseekharness.app.util;

/** Stable on-disk operation kinds; old translated labels remain readable. */
public final class BackupTaskKinds {
  public static final String BACKUP = "DSHA_BACKUP_V1",
      RESTORE = "DSHA_RESTORE_V1",
      REBUILD = "DSHA_REBUILD_V1",
      UPDATE = "DSHA_UPDATE_RUNTIME_V1",
      RECOVER = "DSHA_RECOVER_MAINTENANCE_V1",
      FACTORY_RESET = "DSHA_FACTORY_RESET_V1",
      ROLLBACK = "DSHA_ROLLBACK_RUNTIME_V1",
      SELECT_HOME = "DSHA_SELECT_HOME_V1",
      RESET = "DSHA_RESET_CONFIG_V1",
      REPAIR = "DSHA_REPAIR_STARTUP_V1",
      REMOVE_PLUGIN = "DSHA_REMOVE_FAULTY_PLUGIN_V1",
      RECORD = "DSHA_TASK_RECORD_V1";
  private static final String[][] ROWS = {
    {BACKUP, "创建备份", "Create backup"},
    {RESTORE, "恢复备份", "Restore backup"},
    {REBUILD, "重建环境", "Rebuild environment"},
    {UPDATE, "更新运行环境", "Update runtime environment"},
    {RECOVER, "恢复中断维护", "Resume interrupted maintenance"},
    {FACTORY_RESET, "格式化 DSHA", "Format DSHA"},
    {ROLLBACK, "回退兼容运行时", "Roll back compatible runtime"},
    {SELECT_HOME, "选择数据目录", "Select data directory"},
    {RESET, "重置配置", "Reset settings"},
    {REPAIR, "修复启动配置", "Repair startup configuration"},
    {REMOVE_PLUGIN, "卸载故障插件", "Remove faulty plugin"},
    {RECORD, "任务记录", "Task record"}
  };

  private BackupTaskKinds() {}

  public static String normalize(String value) {
    if (value == null) return "";
    for (String[] row : ROWS) for (String known : row) if (known.equals(value)) return row[0];
    return value;
  }

  public static String display(String value, boolean english) {
    String id = normalize(value);
    for (String[] row : ROWS) if (row[0].equals(id)) return row[english ? 2 : 1];
    return null;
  }

  public static boolean is(String expected, String value) {
    return expected.equals(normalize(value));
  }
}
