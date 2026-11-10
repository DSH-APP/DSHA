package com.deepseekharness.app.plugins;

import com.deepseekharness.app.core.PluginRepository.Item;
import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.json.JSONArray;
import org.json.JSONObject;

/** 当前 Web 的只读运行证据；不把磁盘配置、历史激活记录或快照缺席当作加载结论。 */
public final class PluginRuntimeProjection {
  /**
   * Web 心跳每 2 秒写一次；允许事件循环被长任务占住时连续错过若干次心跳。代次与实例已由调用方
   * 核对，年龄只用于排除写方已停止的残留快照，不能把一次卡顿当成新的加载结论。
   */
  public static final long MAX_AGE_MILLIS = 30_000;

  private static final int MAX_INSTALLED = 512, MAX_DYNAMIC = 256;

  private PluginRuntimeProjection() {}

  /**
   * 运行证据快照的固定路径。Android 的 /data/user/0 是 /data/data 的系统别名，读取器逐段
   * NOFOLLOW 检查会把合法别名判成 PARENT_LINK；先规范化可信运行根，最终文件仍由读取器
   * NOFOLLOW 打开，不在这里解析最终文件。
   */
  public static File snapshotPath(File rootfs) throws IOException {
    if (rootfs == null) throw new IOException("RUNTIME_ROOT_MISSING");
    return new File(rootfs.getCanonicalFile(), "root/.dsha-web-activity.json");
  }

  public static List<Item> apply(
      List<Item> items, JSONObject activity, long generation, String instance, long now)
      throws Exception {
    boolean fresh = valid(activity, generation, instance, now);
    Map<String, JSONObject> installed = new HashMap<>();
    Set<String> ambiguous = new HashSet<>();
    JSONArray rows = fresh ? activity.optJSONArray("installedPlugins") : null;
    if (rows != null && rows.length() <= MAX_INSTALLED) {
      for (int i = 0; i < rows.length(); i++) {
        JSONObject row = rows.optJSONObject(i);
        if (row == null || row.optString("name").isEmpty()) continue;
        String key = PluginIdentity.key(row.optString("profile", "web"), row.optString("name"));
        if (installed.put(key, row) != null) ambiguous.add(key);
      }
    }
    List<Item> result = new ArrayList<>();
    Set<String> keys = new HashSet<>();
    JSONArray dynamic = fresh ? activity.optJSONArray("plugins") : null;
    boolean dynamicKnown = dynamic != null && dynamic.length() <= MAX_DYNAMIC;
    for (Item item : items) {
      if (!keys.add(item.stableKey)) throw new IOException("PLUGIN_DUPLICATE_IDENTITY");
      if (item.dynamic) {
        if (!dynamicKnown && item.runtimeGeneration == generation)
          result.add(item.withRuntime(item.runtimeState, generation, true));
        continue;
      }
      Item next = stale(item, generation);
      String key = PluginIdentity.key(item.profile, item.name);
      JSONObject observed = ambiguous.contains(key) ? null : installed.get(key);
      if (observed != null && "application".equals(item.scope)) {
        String version = observed.optString("version"), state = observed.optString("state");
        // 历史包装壳只由 patch 插入官方包：快照里的壳版本永远对不上官方包版本，不能据此判旧模块冒充。
        boolean legacyShim = PluginIdentity.legacyShim(item.name, observed.optString("name"));
        if (!version.isEmpty()
            && (legacyShim || version.equals(item.version))
            && confirmedState(state)) {
          next = item.withRuntime(state, generation, false);
          if (!"queued".equals(item.loadState)
              && ((item.enabled && "loaded".equals(state))
                  || (!item.enabled && ("unloaded".equals(state) || "disabled".equals(state)))))
            next = next.withRestartRequired(false);
        } else if (!version.isEmpty() && !legacyShim && !version.equals(item.version)) {
          // 当前进程仍持有前一版模块；不能让相同名字替新版领取加载成功。
          next = item.withRuntime("unknown", generation, true).withRestartRequired(true);
        }
      }
      result.add(next);
    }
    if (dynamicKnown) {
      Set<String> dynamicKeys = new HashSet<>();
      List<Item> nextDynamic = new ArrayList<>();
      for (int i = 0; i < dynamic.length(); i++) {
        JSONObject row = dynamic.optJSONObject(i);
        if (row == null) continue;
        String session = row.optString("sessionId"), pluginId = row.optString("id");
        if (session.isEmpty() || pluginId.isEmpty()) continue;
        Item item =
            new Item(
                new JSONObject()
                    .put("name", row.optString("name"))
                    .put("dynamic", true)
                    .put("sessionId", session)
                    .put("pluginId", pluginId)
                    .put("runtimeGeneration", generation)
                    .put("runtimeStale", false)
                    .put(
                        "runtimeState",
                        row.optString("state", row.optBoolean("active") ? "active" : "inactive"))
                    .put("version", row.optString("version"))
                    .put("available", true)
                    .put("enabled", row.optBoolean("active"))
                    .put("location", "会话 " + session.substring(Math.max(0, session.length() - 8)))
                    .put("description", "会话内临时插件，请在 Web 页面管理；重启后需重新定义，不作为安装包备份。"));
        if (!dynamicKeys.add(item.stableKey)) throw new IOException("PLUGIN_DUPLICATE_IDENTITY");
        nextDynamic.add(item);
      }
      result.addAll(nextDynamic);
    }
    return Collections.unmodifiableList(result);
  }

  /** 同版本同代目录刷新只更新安装事实，不能抹掉上次实际运行证据。 */
  public static Item retainRuntime(Item fresh, Item previous, long generation) throws Exception {
    if (previous == null
        || !fresh.stableKey.equals(previous.stableKey)
        || !fresh.version.equals(previous.version)
        || !fresh.origin.equals(previous.origin)
        || previous.runtimeGeneration != generation)
      return fresh.withRuntime("unknown", generation, true);
    return fresh.withRuntime(previous.runtimeState, generation, previous.runtimeStale);
  }

  private static Item stale(Item item, long generation) throws Exception {
    if (item.runtimeGeneration != generation)
      return item.withRuntime("unknown", generation, true).withRestartRequired(false);
    return item.withRuntime(item.runtimeState, generation, true);
  }

  private static boolean valid(JSONObject activity, long generation, String instance, long now) {
    return rejection(activity, generation, instance, now).isEmpty();
  }

  /**
   * 返回拒绝原因码（空串表示可用）。调用方用它记录本轮为什么没有采纳运行证据；没有原因码时
   * 界面只能显示“未确认”，无法区分读失败、代次变化和心跳过期。
   */
  public static String rejection(JSONObject activity, long generation, String instance, long now) {
    if (activity == null) return "NO_ACTIVITY";
    if (instance == null || instance.isEmpty()) return "NO_INSTANCE";
    if (generation < 1) return "NO_GENERATION";
    long at = activity.optLong("at", -1);
    if (at <= 0) return "NO_TIME";
    if (now < at) return "FUTURE_TIME";
    if (now - at > MAX_AGE_MILLIS) return "EXPIRED";
    if (!Long.toString(generation).equals(activity.optString("generation"))) return "GENERATION";
    if (!instance.equals(activity.optString("instance"))) return "INSTANCE";
    return "";
  }

  private static boolean confirmedState(String value) {
    return switch (value) {
      case "loaded", "failed", "loading", "pending", "waiting", "unloaded", "disabled" -> true;
      default -> false;
    };
  }
}
