package com.deepseekharness.app.recovery;

import android.content.Context;
import com.deepseekharness.app.BackupManager;
import com.deepseekharness.app.backup.*;
import com.deepseekharness.app.core.EnvironmentMaintenance;
import com.deepseekharness.app.core.HarnessController;
import com.deepseekharness.app.core.BackupTask;
import com.deepseekharness.app.core.StartupRepairs;
import com.deepseekharness.app.util.HttpProtocol;
import com.deepseekharness.app.util.RecoveryRepairPlan;
import com.deepseekharness.app.util.RecoveryBrokerProtocol;
import com.deepseekharness.app.util.SensitiveData;
import com.deepseekharness.app.util.ProfileConfigPath;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.*;
import java.util.concurrent.*;

/** 应急 DSH 只读/提案桥。网络端永远不能确认写入；确认只由本机原生页面调用。 */
public final class RecoveryRepairBroker implements AutoCloseable {
  private static final int LIMIT = 262144, MAX_PLANS = 32;
  private final Context context;
  private final File files;
  private final BackupFileSystem fs = new AndroidBackupFileSystem();
  private final String instanceId, token;
  private final long generation;
  private final ServerSocket listener;
  private final ThreadPoolExecutor workers =
      new ThreadPoolExecutor(2, 2, 0, TimeUnit.MILLISECONDS, new ArrayBlockingQueue<>(8));
  private final Set<Socket> sockets = ConcurrentHashMap.newKeySet();
  private final Map<String, RecoveryRepairPlan> candidates = new LinkedHashMap<>();
  private volatile boolean closed;
  private volatile boolean writeBlocked;
  private volatile String writeBlockedReason = "";

  private RecoveryRepairBroker(Context context, String instanceId, long generation)
      throws IOException {
    if (instanceId == null || !instanceId.matches("[a-f0-9]{32}") || generation < 1)
      throw new IOException("REPAIR_SESSION_INVALID");
    this.context = context.getApplicationContext();
    files = context.getFilesDir().getCanonicalFile();
    this.instanceId = instanceId;
    this.generation = generation;
    byte[] secret = new byte[32];
    new SecureRandom().nextBytes(secret);
    token = RecoveryRepairPlan.digest(secret);
    listener = new ServerSocket();
    listener.bind(new InetSocketAddress(InetAddress.getByName("127.0.0.1"), 0), 8);
    Thread accept = new Thread(this::accept, "dsha-recovery-broker");
    accept.setDaemon(true);
    accept.start();
  }

  public static RecoveryRepairBroker createSession(
      Context context, String instanceId, long generation) throws IOException {
    return new RecoveryRepairBroker(context, instanceId, generation);
  }

  public int port() {
    return listener.getLocalPort();
  }

  public String token() {
    return token;
  }

  /** 同一应急代次不能重新授予写权限；重新启动并核验身份后建立新 broker。 */
  public synchronized void blockWrites(String reason) {
    if (writeBlocked) return;
    writeBlockedReason =
        RecoveryBrokerProtocol.redact(
            reason == null || reason.isEmpty() ? "PROCESS_IDENTITY_UNCONFIRMED" : reason);
    writeBlocked = true;
  }

  public void setReadOnly(String reason) {
    blockWrites(reason);
  }

  public String readOnlyReason() {
    return writeBlocked ? writeBlockedReason : "";
  }

  public static int activeNativeRepairs() {
    return RecoveryRepairPlan.activeNativeRepairs();
  }

  private void requireWritable() throws IOException {
    RecoveryRepairPlan.requireWritable(writeBlocked, writeBlockedReason);
  }

  private void requireOpen() throws IOException {
    if (closed) throw new IOException("REPAIR_SESSION_CLOSED");
  }

  private static JSONObject json(Object... values) {
    Map<String, Object> result = new LinkedHashMap<>();
    for (int i = 0; i < values.length; i += 2) result.put((String) values[i], values[i + 1]);
    return new JSONObject(result);
  }

  private static String safe(Throwable error) {
    String text = SensitiveData.redact(error.getMessage());
    return text == null ? error.getClass().getSimpleName() : text;
  }

  public JSONObject diagnostics() throws IOException {
    requireOpen();
    List<Map<String, Object>> stages = new ArrayList<>();
    for (String path :
        List.of(
            "linux/ubuntu/bin/bash",
            "linux/ubuntu/usr/bin/bash",
            "linux/ubuntu/usr/local/bin/node",
            "user-data-v5/dsh",
            "linux/ubuntu/root/.dsh")) {
      try {
        var node = fs.stat(new File(files, path));
        stages.add(Map.of("item", path, "type", node.type, "bytes", node.size));
      } catch (IOException error) {
        stages.add(Map.of("item", path, "error", safe(error)));
      }
    }
    String data;
    try {
      data = dataGeneration();
    } catch (IOException error) {
      data = "unavailable: " + safe(error);
    }
    String linuxType, linuxError = "";
    try {
      linuxType = fs.stat(new File(files, "linux")).type;
    } catch (IOException error) {
      linuxType = "UNREADABLE";
      linuxError = safe(error);
    }
    boolean environmentReady = HarnessController.get(context).isEnvironmentReady();
    JSONObject startup = startup();
    var maintenance = BackupTask.get(context).snapshot();
    return json(
        "status",
        "ok",
        "instanceId",
        instanceId,
        "generation",
        generation,
        "pendingMaintenance",
        com.deepseekharness.app.core.MaintenanceCoordinator.pending(files),
        "currentEnvironmentReady",
        environmentReady,
        "currentLinuxType",
        linuxType,
        "currentLinuxError",
        linuxError,
        "dataGeneration",
        data,
        "stages",
        stages,
        "startup",
        startup,
        "writeBlocked",
        writeBlocked,
        "writeBlockedReason",
        writeBlockedReason,
        "maintenance",
        json(
            "id",
            maintenance.id,
            "kind",
            maintenance.kind,
            "stage",
            maintenance.lastStage,
            "status",
            maintenance.status.name(),
            "reason",
            SensitiveData.redact(maintenance.detail)),
        "originals",
        "本次诊断只读；历史维护可能已回切或保留原件，写入必须在原生界面逐次确认 / This diagnostic is read-only; earlier maintenance may have rolled back or retained originals. Each write requires native confirmation");
  }

  private JSONObject startup() {
    var snapshot = HarnessController.get(context).startupDiagnostics().snapshot();
    JSONArray history = new JSONArray();
    var entries = HarnessController.get(context).startupDiagnostics().history();
    for (int i = 0; i < entries.size() && i < 5; i++) {
      var entry = entries.get(i);
      history.put(
          json(
              "id",
              entry.id,
              "status",
              entry.status,
              "stage",
              RecoveryBrokerProtocol.redact(entry.stage),
              "reason",
              RecoveryBrokerProtocol.redact(entry.reason),
              "log",
              RecoveryBrokerProtocol.redact(entry.log)));
    }
    Map<String, String> issues = new LinkedHashMap<>();
    for (var issue : snapshot.issues.entrySet())
      issues.put(
          RecoveryBrokerProtocol.redact(issue.getKey()),
          RecoveryBrokerProtocol.redact(issue.getValue()));
    return json(
        "stage",
        snapshot.stage,
        "log",
        RecoveryBrokerProtocol.redact(snapshot.log),
        "issues",
        new JSONObject(issues),
        "browserReady",
        snapshot.browserReady,
        "history",
        history);
  }

  private Map<String, String> profiles() throws IOException {
    Map<String, String> result = new LinkedHashMap<>();
    File root = home();
    File parent = fs.child(root, "profiles");
    if (fs.stat(parent).type.equals("MISSING")) return result;
    List<String> names = fs.list(parent);
    if (names.size() > 128) throw new IOException("REPAIR_PROFILE_LIMIT");
    for (String name : names) {
      if (!ProfileConfigPath.profile(name)) continue;
      File dir = fs.child(parent, name);
      if (!fs.stat(dir).type.equals("DIRECTORY")) continue;
      result.put(
          "profile-"
              + RecoveryRepairPlan.digest(name.getBytes(StandardCharsets.UTF_8)).substring(0, 24),
          name);
    }
    return result;
  }

  private File home() throws IOException {
    return ProfileSettingsTransaction.resolveHome(
        context, fs, files, new UserDataLayout(fs, files).current());
  }

  private File targetFile(String id) throws IOException {
    if ("legacy-settings".equals(id)) return fs.child(home(), "settings.yaml");
    if ("global-patch".equals(id)) return fs.child(home(), "cordis.patch.yml");
    if (id.startsWith("manifest-")) {
      String profile = profiles().get(id.substring(9));
      if (profile == null) throw new IOException("REPAIR_TARGET_UNKNOWN");
      return fs.child(home(), "profiles/" + profile + "/package.json");
    }
    String profile = profiles().get(id);
    if (profile == null) throw new IOException("REPAIR_TARGET_UNKNOWN");
    return fs.child(home(), "profiles/" + profile + "/cordis.patch.yml");
  }

  public JSONArray targets() throws IOException {
    requireOpen();
    JSONArray rows = new JSONArray();
    rows.put(
        json(
            "id",
            "maintenance",
            "label",
            "中断维护 / Interrupted maintenance",
            "actions",
            new JSONArray(List.of("recover-maintenance"))));
    rows.put(
        json(
            "id",
            "runtime",
            "label",
            "签名 APK 运行时与 CLI 依赖 / Signed APK runtime and CLI dependencies",
            "actions",
            new JSONArray(List.of("repair-runtime", "repair-cli-dependencies"))));
    rows.put(
        json(
            "id",
            "startup-log",
            "label",
            "最近启动阶段与原始错误 / Recent startup stages and errors",
            "actions",
            new JSONArray()));
    rows.put(
        json(
            "id",
            "maintenance-log",
            "label",
            "最近维护阶段与失败原因 / Last maintenance stage and failure",
            "actions",
            new JSONArray()));
    try {
      rows.put(
          json(
              "id",
              "legacy-settings",
              "label",
              "旧版设置（只读） / Legacy settings (read only)",
              "actions",
              new JSONArray()));
      rows.put(
          json(
              "id",
              "configuration-web",
              "label",
              "损坏 Web Profile 的宿主重建 / Host repair of a broken Web Profile",
              "actions",
              new JSONArray(List.of("new-web-profile"))));
      rows.put(
          json(
              "id",
              "global-patch",
              "label",
              "全局配置补丁 / Global configuration patch",
              "actions",
              new JSONArray(List.of("new-global-patch"))));
      for (var profile : profiles().entrySet()) {
        rows.put(
            json(
                "id",
                profile.getKey(),
                "label",
                profile.getValue() + " profile 设置 / settings",
                "profile",
                profile.getValue(),
                "actions",
                new JSONArray(List.of("profile-settings"))));
        rows.put(
            json(
                "id",
                "manifest-" + profile.getKey(),
                "label",
                profile.getValue() + " 插件构成（只读） / plugin composition (read only)",
                "actions",
                new JSONArray()));
      }
    } catch (IOException error) {
      rows.put(json("id", "data-unavailable", "label", safe(error), "actions", new JSONArray()));
    }
    return rows;
  }

  private byte[] source(String id) throws IOException {
    if (id.equals("maintenance")) return dataGeneration().getBytes(StandardCharsets.UTF_8);
    if (id.equals("startup-log")) return startup().toString().getBytes(StandardCharsets.UTF_8);
    if (id.equals("maintenance-log")) {
      var state = BackupTask.get(context).snapshot();
      return json(
              "id",
              state.id,
              "kind",
              state.kind,
              "stage",
              state.lastStage,
              "status",
              state.status.name(),
              "reason",
              SensitiveData.redact(state.detail))
          .toString()
          .getBytes(StandardCharsets.UTF_8);
    }
    if (id.equals("configuration-web")) {
      ByteArrayOutputStream documents = new ByteArrayOutputStream();
      for (String relative :
          List.of("profiles/web/package.json", "profiles/web/cordis.patch.yml")) {
        File file = new File(home(), relative);
        var node = fs.stat(file);
        documents.write(
            ("\n--- " + relative + " [" + node.type + "] ---\n").getBytes(StandardCharsets.UTF_8));
        if (node.type.equals("MISSING")) continue;
        else if (node.type.equals("FILE")) documents.write(fs.small(file, LIMIT));
        else throw new IOException("REPAIR_TARGET_LINK_OR_SPECIAL");
      }
      return documents.toByteArray();
    }
    if (id.equals("runtime")) {
      File file =
          new File(files, "linux/ubuntu/usr/local/lib/node_modules/@deepseek-ai/dsh/package.json");
      var node = fs.stat(file);
      if (node.type.equals("MISSING")) return "RUNTIME_MISSING".getBytes(StandardCharsets.UTF_8);
      return fs.small(file, LIMIT);
    }
    File file = targetFile(id);
    var node = fs.stat(file);
    if (node.type.equals("MISSING")) return new byte[0];
    if (!node.type.equals("FILE")) throw new IOException("REPAIR_TARGET_LINK_OR_SPECIAL");
    return fs.small(file, LIMIT);
  }

  public JSONObject read(String id) throws IOException {
    requireOpen();
    byte[] bytes = source(id);
    String text = new String(bytes, StandardCharsets.UTF_8),
        redacted = RecoveryBrokerProtocol.redact(text);
    return json(
        "targetId",
        id,
        "sourceSha256",
        RecoveryRepairPlan.digest(bytes),
        "dataGeneration",
        dataGeneration(),
        "text",
        redacted,
        "redacted",
        !text.equals(redacted),
        "bytes",
        bytes.length);
  }

  /** 小型本机记录和目标出生身份组成代次；不扫描会话、项目或完整运行时。 */
  private String dataGeneration() throws IOException {
    StringBuilder proof = new StringBuilder();
    for (String relative :
        List.of(
            "linux/ubuntu",
            "user-data-v5/dsh",
            "linux/ubuntu/root/.dsh",
            UserDataLayout.RECORD,
            UserDataLayout.RECORD + ".previous")) {
      File file = new File(files, relative);
      var node = fs.stat(file);
      proof
          .append(relative)
          .append(':')
          .append(node.type)
          .append(':')
          .append(node.key)
          .append('\n');
      if (node.type.equals("FILE"))
        proof.append(RecoveryRepairPlan.digest(fs.small(file, 16384))).append('\n');
    }
    for (String root :
        List.of(
            "maintenance",
            "runtime-updates",
            "host-runtime-operations",
            "host-backup-operations",
            "host-environment-operations",
            "startup-config-operations")) {
      File parent = new File(files, root);
      var node = fs.stat(parent);
      proof.append(root).append(':').append(node.type).append('\n');
      if (!node.type.equals("DIRECTORY")) continue;
      List<String> names = new ArrayList<>(fs.list(parent));
      boolean archivedRoot =
          List.of(
                  "host-backup-operations",
                  "startup-config-operations",
                  "host-runtime-operations",
                  "host-environment-operations")
              .contains(root);
      boolean hostTerminal =
          root.equals("host-backup-operations") || root.equals("startup-config-operations");
      if (archivedRoot) names.remove(HostOperationArchive.COMPLETED);
      int bounded = 0;
      for (String name : names) {
        File item = fs.child(parent, name);
        var current = fs.stat(item);
        if (current.type.equals("DIRECTORY")
            && (hostTerminal && HostOperationArchive.isTerminal(fs, item, name)
                || root.equals("host-runtime-operations")
                    && com.deepseekharness.app.backup.ManagedRuntimeTransaction.isTerminalRecord(
                        fs, files, name)
                || root.equals("host-environment-operations")
                    && com.deepseekharness.app.backup.EnvironmentRebuildTransaction
                        .isTerminalRecord(fs, files, name))) continue;
        if (++bounded > 256) throw new IOException("REPAIR_JOURNAL_LIMIT");
        proof
            .append(name)
            .append(':')
            .append(current.key)
            .append(':')
            .append(current.modified)
            .append('\n');
        if (current.type.equals("DIRECTORY"))
          for (String marker :
              List.of("plan.json", "switching", "finalized", "rolled-back", "state.json")) {
            File evidence = fs.child(item, marker);
            var state = fs.stat(evidence);
            proof
                .append(marker)
                .append(':')
                .append(state.type)
                .append(':')
                .append(state.key)
                .append(':')
                .append(state.size)
                .append(':')
                .append(state.modified)
                .append('\n');
            if (state.type.equals("FILE") && state.size <= 16384)
              proof.append(RecoveryRepairPlan.digest(fs.small(evidence, 16384))).append('\n');
          }
      }
    }
    return RecoveryRepairPlan.digest(proof.toString().getBytes(StandardCharsets.UTF_8));
  }

  public synchronized JSONObject propose(JSONObject request) throws IOException {
    requireOpen();
    if (candidates.size() >= MAX_PLANS) throw new IOException("REPAIR_PLAN_LIMIT");
    String action = request.optString("action"),
        target = request.optString("targetId"),
        content = request.optString("content", "");
    boolean recover = action.equals("recover-maintenance") && target.equals("maintenance"),
        runtime = action.equals("repair-runtime") && target.equals("runtime"),
        cliDependencies = action.equals("repair-cli-dependencies") && target.equals("runtime");
    boolean settings = action.equals("profile-settings") && profiles().containsKey(target);
    boolean newWeb = action.equals("new-web-profile") && target.equals("configuration-web"),
        newGlobal = action.equals("new-global-patch") && target.equals("global-patch");
    if (!recover && !runtime && !cliDependencies && !settings && !newWeb && !newGlobal)
      throw new IOException("REPAIR_ACTION_TARGET");
    if (!recover && com.deepseekharness.app.core.MaintenanceCoordinator.pending(files))
      throw new IOException("REPAIR_RECOVER_PENDING_FIRST");
    if (!settings && !content.isEmpty()) throw new IOException("REPAIR_ACTION_CONTENT");
    if (settings
        && (content.contains("***") || !content.equals(RecoveryBrokerProtocol.redact(content))))
      throw new IOException("REPAIR_CREDENTIALS_USE_NATIVE_SETTINGS");
    String source = RecoveryRepairPlan.digest(source(target)), data = dataGeneration();
    if (!source.equals(request.optString("sourceSha256"))
        || !data.equals(request.optString("dataGeneration")))
      throw new IOException("REPAIR_SOURCE_CHANGED");
    var plan =
        new RecoveryRepairPlan(instanceId, generation, action, target, source, data, content);
    candidates.put(plan.id, plan);
    return render(plan, true);
  }

  private synchronized RecoveryRepairPlan find(String id) throws IOException {
    RecoveryRepairPlan p = candidates.get(id);
    if (p == null) throw new IOException("REPAIR_PLAN_UNKNOWN");
    return p;
  }

  private JSONObject render(RecoveryRepairPlan p, boolean full) throws IOException {
    JSONObject result =
        json(
            "id",
            p.id,
            "planId",
            p.id,
            "kind",
            p.action,
            "action",
            p.action,
            "targetId",
            p.target,
            "status",
            p.state().name(),
            "state",
            p.state().name(),
            "sourceSha256",
            p.sourceSha256,
            "sourceSha",
            p.sourceSha256,
            "dataGeneration",
            p.dataGeneration,
            "reason",
            p.message(),
            "message",
            p.message(),
            "nativeConfirmationRequired",
            true,
            "writeBlocked",
            writeBlocked,
            "writeBlockedReason",
            writeBlockedReason,
            "sessionClosed",
            closed);
    if (full && !closed)
      try {
        boolean maintenanceAction = p.action.equals("recover-maintenance"),
            runtimeAction = p.action.equals("repair-runtime"),
            cliDependencyAction = p.action.equals("repair-cli-dependencies");
        String before =
            maintenanceAction
                ? maintenancePreview().toString(2)
                : (runtimeAction || cliDependencyAction)
                    ? runtimePreview().toString(2)
                    : RecoveryBrokerProtocol.redact(
                        new String(source(p.target), StandardCharsets.UTF_8));
        result.put("before", before);
        result.put(
            "after",
            maintenanceAction
                ? com.deepseekharness.app.util.UiText.text(
                    "1. 停止正式 Web 与终端并核验进程身份；无法确认退出即停止。\n2. 重新读取中断维护记录，按唯一已识别事务执行原有恢复/回切接口；多份冲突或损坏记录保持原样。\n3. 当前没有待恢复事务时只核验状态，不猜测要覆盖哪份数据。\n4. 原件、失败候选与维护记录继续保留；已提交事务不得覆盖后来的对话或配置。\n5. 完成后仍须重试正式 Web 并验证实际可用；本次确认不代表网页已恢复。")
                : runtimeAction
                    ? com.deepseekharness.app.util.UiText.text(
                        "1. 停止正式 Web 与终端，取得原有数据维护屏障。\n2. 从当前签名 APK 准备运行时；同一基础环境执行受管更新，基础环境确需重建时先由宿主保护个人数据。\n3. 先校验候选，再执行隔离启动、存储和网页连接试验；未通过即按原事务保留现场或回切。\n4. 对话、配置、插件原件按现有事务保护；不会从应急模型下载程序或执行其代码。\n5. 当前原件及回切记录保存在应用私有 host-runtime-operations / host-environment-operations；成功后仍须核验正式 Web。")
                    : cliDependencyAction
                        ? com.deepseekharness.app.util.UiText.text(
                            "1. 停止正式 Web 与终端，取得原有数据维护屏障。\n2. 仅从当前签名 APK 的受管 DSH/Node 依赖树恢复缺失或损坏的 CLI 依赖；不接受包名、版本、路径或命令参数。\n3. 按现有运行时事务校验摘要、隔离启动、存储、网页连接和插件加载；任何检查失败均保留原件并回切。\n4. 不执行 npm 生命周期脚本，不从应急模型下载代码，不直接修改用户 node_modules。\n5. 完成后仍须重试正式 Web，并以诊断结果确认 CLI 已就绪。")
                        : p.action.equals("new-web-profile")
                            ? com.deepseekharness.app.util.UiText.text(
                                "profiles/web/package.json → 官方基础 bundles：dsh-base + dsh-web-app\nprofiles/web/cordis.patch.yml → []\n旧插件构成与设置保存在配置快照；既有会话与凭据保持原位。")
                            : p.action.equals("new-global-patch")
                                ? com.deepseekharness.app.util.UiText.text(
                                    "cordis.patch.yml → []\n对全部 Profile 的全局覆盖生效；原文保存在配置快照。")
                                : SensitiveData.redact(p.content));
        result.put(
            "description",
            p.action.equals("recover-maintenance")
                ? com.deepseekharness.app.util.UiText.text("按原事务恢复中断维护，保留原件。")
                : p.action.equals("repair-runtime")
                    ? com.deepseekharness.app.util.UiText.text("从当前签名 APK 重建受管运行时，完成隔离试运行。")
                    : p.action.equals("repair-cli-dependencies")
                        ? com.deepseekharness.app.util.UiText.text(
                            "从当前签名 APK 恢复受管 CLI/Node 依赖并完成隔离试运行；不执行任意 shell。")
                        : p.action.startsWith("new-")
                            ? com.deepseekharness.app.util.UiText.text(
                                "宿主直接保留原件并创建基础配置，无需先启动损坏 DSH。已存在的插件源码保留，恢复使用须审阅。")
                            : com.deepseekharness.app.util.UiText.text(
                                "只应用经当前 DSH schema 验证的普通设置；插件构成与凭据不由此入口修改。"));
      } catch (org.json.JSONException error) {
        throw new IOException("REPAIR_JSON", error);
      }
    return result;
  }

  private JSONObject runtimePreview() throws IOException {
    JSONArray checks = new JSONArray();
    for (String relative :
        List.of(
            "linux/ubuntu/bin/bash",
            "linux/ubuntu/usr/bin/bash",
            "linux/ubuntu/usr/local/bin/node",
            "linux/ubuntu/usr/local/lib/node_modules/@deepseek-ai/dsh/package.json")) {
      try {
        var node = fs.stat(new File(files, relative));
        checks.put(json("项目", relative, "类型", node.type, "大小", node.size));
      } catch (IOException error) {
        checks.put(json("项目", relative, "读取结果", safe(error)));
      }
    }
    return json(
        "动作",
        "从当前签名 APK 修复正式运行时",
        "内置DSH",
        com.deepseekharness.app.util.Constants.DSH_VERSION,
        "存在未完成维护",
        com.deepseekharness.app.core.MaintenanceCoordinator.pending(files),
        "实际文件状态",
        checks,
        "原件状态",
        "尚未修改；摘要和数据代次将在确认及停止屏障内重新核对。");
  }

  private JSONObject maintenancePreview() throws IOException {
    var task = BackupTask.get(context).snapshot();
    JSONArray records = new JSONArray();
    int omitted = 0;
    for (String kind :
        List.of(
            "maintenance",
            "runtime-updates",
            "host-runtime-operations",
            "host-backup-operations",
            "host-environment-operations",
            "startup-config-operations")) {
      try {
        File directory = new File(files, kind);
        if (!fs.stat(directory).type.equals("DIRECTORY")) continue;
        List<String> names = new ArrayList<>(fs.list(directory));
        boolean archivedRoot =
            List.of(
                    "host-backup-operations",
                    "startup-config-operations",
                    "host-runtime-operations",
                    "host-environment-operations")
                .contains(kind);
        boolean hostTerminal =
            kind.equals("host-backup-operations") || kind.equals("startup-config-operations");
        if (archivedRoot) names.remove(HostOperationArchive.COMPLETED);
        int bounded = 0;
        for (String id : names) {
          File journal = fs.child(directory, id);
          if (!fs.stat(journal).type.equals("DIRECTORY")) continue;
          if (hostTerminal && HostOperationArchive.isTerminal(fs, journal, id)
              || kind.equals("host-runtime-operations")
                  && com.deepseekharness.app.backup.ManagedRuntimeTransaction.isTerminalRecord(
                      fs, files, id)
              || kind.equals("host-environment-operations")
                  && com.deepseekharness.app.backup.EnvironmentRebuildTransaction.isTerminalRecord(
                      fs, files, id)) continue;
          if (++bounded > 256) throw new IOException("REPAIR_JOURNAL_LIMIT");
          JSONArray markers = new JSONArray();
          boolean finished = false;
          for (String stage :
              List.of(
                  "plan.json",
                  "verified",
                  "prepared",
                  "switching",
                  "begun",
                  "files-committed",
                  "settings-committed",
                  "finalized",
                  "committed",
                  "rolling-back",
                  "rolled-back")) {
            var state = fs.stat(fs.child(journal, stage));
            if (!state.type.equals("MISSING")) {
              markers.put(stage + " [" + state.type + "]");
              if (List.of("finalized", "committed", "rolled-back").contains(stage)) finished = true;
            }
          }
          if (finished) {
            omitted++;
            continue;
          }
          if (records.length() >= 24) {
            omitted++;
            continue;
          }
          records.put(json("类型", kind, "事务ID", id, "已观察标记", markers));
        }
      } catch (IOException error) {
        records.put(json("类型", kind, "读取结果", safe(error)));
      }
    }
    return json(
        "动作",
        "恢复中断维护",
        "存在未完成维护",
        com.deepseekharness.app.core.MaintenanceCoordinator.pending(files),
        "最后记录任务",
        task.kind,
        "最后阶段",
        task.lastStage,
        "任务状态",
        task.status.name(),
        "任务详情",
        RecoveryBrokerProtocol.redact(task.detail),
        "事务记录",
        records,
        "未列出的历史或超限条目",
        omitted,
        "说明",
        "以上是只读现场摘要；恢复接口会独立核验日志内容，不能仅凭文件名断定已提交或可回切。原件尚未修改。");
  }

  public synchronized JSONArray plans() throws IOException {
    JSONArray rows = new JSONArray();
    for (var p : candidates.values()) rows.put(render(p, false));
    return rows;
  }

  public JSONObject preview(String planId) throws IOException {
    return render(find(planId), true);
  }

  public JSONObject result(String planId) throws IOException {
    return render(find(planId), false);
  }

  public JSONObject reject(String planId) throws IOException {
    var p = find(planId);
    p.reject();
    return render(p, false);
  }

  /** 只能由原生用户确认调用；网络路由和应急工具没有该能力。 */
  public JSONObject confirm(String planId) throws Exception {
    RecoveryRepairPlan p = find(planId);
    requireOpen();
    requireWritable();
    try (RecoveryRepairPlan.NativeRepairLease nativeRepair =
        p.beginNative(
            instanceId,
            generation,
            p.nonce,
            RecoveryRepairPlan.digest(source(p.target)),
            dataGeneration())) {
      try {
        String outcome =
            com.deepseekharness.app.core.MaintenanceCoordinator.exclusive(
                HarnessController.get(context),
                () -> {
                  // 已原生确认的修复独立于应急网页生命周期；close 只撤销后续提案/确认能力。
                  requireWritable();
                  if (!p.sourceSha256.equals(RecoveryRepairPlan.digest(source(p.target)))
                      || !p.dataGeneration.equals(dataGeneration()))
                    throw new IOException("REPAIR_SOURCE_CHANGED");
                  if (!p.action.equals("recover-maintenance")
                      && com.deepseekharness.app.core.MaintenanceCoordinator.pending(files))
                    throw new IOException("REPAIR_RECOVER_PENDING_FIRST");
                  HarnessController controller = HarnessController.get(context);
                  if (p.action.equals("recover-maintenance"))
                    return EnvironmentMaintenance.recover(controller);
                  if (p.action.equals("repair-runtime")
                      || p.action.equals("repair-cli-dependencies"))
                    return EnvironmentMaintenance.update(controller, ignored -> {});
                  if (p.action.equals("new-web-profile") || p.action.equals("new-global-patch")) {
                    StartupRepairs.checkpoint(
                        controller,
                        json(
                            "command",
                            "new",
                            "target",
                            p.action.equals("new-web-profile") ? "web" : "cordis.patch.yml"));
                    return "基础配置已由宿主事务创建并核对，原件保留；请重试正式启动确认实际可用。 / Base configuration created and checked by host transaction; originals retained. Retry normal startup to verify usability.";
                  }
                  String profile = profiles().get(p.target);
                  if (profile == null) throw new IOException("REPAIR_TARGET_UNKNOWN");
                  ProfileSettingsTransaction.repair(
                      context,
                      profile,
                      p.content.getBytes(StandardCharsets.UTF_8),
                      new BackupControl(null));
                  return "设置已通过当前 schema、宿主事务和服务读回验证；原件保留。 / Settings passed schema, transaction and service readback; originals retained.";
                });
        p.complete(true, SensitiveData.redact(outcome));
      } catch (Exception error) {
        p.complete(false, safe(error));
        throw error;
      }
      return render(p, false);
    }
  }

  private void accept() {
    while (!closed)
      try {
        Socket socket = listener.accept();
        if (!socket.getInetAddress().isLoopbackAddress() || sockets.size() >= 10) {
          socket.close();
          continue;
        }
        sockets.add(socket);
        try {
          workers.execute(() -> serve(socket));
        } catch (RejectedExecutionException full) {
          sockets.remove(socket);
          socket.close();
        }
      } catch (IOException failure) {
        if (!closed) close();
      }
  }

  private void serve(Socket socket) {
    try {
      InputStream input = socket.getInputStream();
      socket.setSoTimeout(5000);
      var head =
          HttpProtocol.readHead(
              input,
              socket,
              new HttpProtocol.Limits(1024, 2048, 8192, 20, 5000),
              HttpProtocol.deadline(5000),
              true);
      if (head == null) return;
      RecoveryBrokerProtocol.validate(head, port(), token);
      var body = head.requestBody();
      byte[] bytes = new byte[(int) body.length];
      int offset = 0;
      long deadline = System.nanoTime() + 5_000_000_000L;
      while (offset < bytes.length) {
        long remaining = deadline - System.nanoTime();
        if (remaining <= 0) throw new IOException("REPAIR_BODY_TIMEOUT");
        socket.setSoTimeout((int) Math.max(1, remaining / 1_000_000));
        int got = input.read(bytes, offset, bytes.length - offset);
        if (got < 0) throw new EOFException();
        offset += got;
      }
      JSONObject request = new JSONObject(BackupJson.read(bytes, LIMIT));
      Object result;
      switch (head.target) {
        case "/v1/diagnostics":
          result = diagnostics();
          break;
        case "/v1/targets":
          result = targets();
          break;
        case "/v1/read":
          result = read(request.optString("targetId"));
          break;
        case "/v1/propose":
          result = propose(request);
          break;
        case "/v1/result":
          result = result(request.optString("planId"));
          break;
        default:
          throw new IOException("REPAIR_ROUTE_UNKNOWN");
      }
      respond(socket, 200, result.toString());
    } catch (Exception error) {
      try {
        respond(socket, 400, json("status", "error", "message", safe(error)).toString());
      } catch (IOException ignored) {
      }
    } finally {
      sockets.remove(socket);
      try {
        socket.close();
      } catch (IOException ignored) {
      }
    }
  }

  private static void respond(Socket socket, int code, String text) throws IOException {
    byte[] body = text.getBytes(StandardCharsets.UTF_8);
    OutputStream output = socket.getOutputStream();
    output.write(
        ("HTTP/1.1 "
                + code
                + (code == 200 ? " OK" : " Bad Request")
                + "\r\nContent-Type: application/json; charset=utf-8\r\nCache-Control: no-store\r\nConnection: close\r\nContent-Length: "
                + body.length
                + "\r\n\r\n")
            .getBytes(StandardCharsets.US_ASCII));
    output.write(body);
    output.flush();
  }

  @Override
  public synchronized void close() {
    if (closed) return;
    closed = true;
    for (var p : candidates.values()) p.expire();
    try {
      listener.close();
    } catch (IOException ignored) {
    }
    for (Socket socket : sockets)
      try {
        socket.close();
      } catch (IOException ignored) {
      }
    sockets.clear();
    workers.shutdownNow();
  }
}
