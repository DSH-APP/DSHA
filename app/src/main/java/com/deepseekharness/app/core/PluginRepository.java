package com.deepseekharness.app.core;

import android.app.Application;
import android.content.Context;
import android.net.Uri;
import android.provider.DocumentsContract;
import androidx.annotation.NonNull;
import androidx.lifecycle.AndroidViewModel;
import androidx.lifecycle.LiveData;
import androidx.lifecycle.MutableLiveData;
import com.deepseekharness.app.plugins.PluginOperationQueue.Type;
import com.deepseekharness.app.runtime.ProotBootstrap;
import com.deepseekharness.app.util.PluginSource;
import com.deepseekharness.app.util.SensitiveData;
import com.deepseekharness.app.util.ShellQuote;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import org.json.JSONArray;
import org.json.JSONObject;

/** 应用范围的插件操作状态；任务只持有 Application，切页/旋转不会丢失结果。 */
public final class PluginRepository extends AndroidViewModel {
  private static final long MAX_ARCHIVE = 256L * 1024 * 1024;
  private static final ExecutorService IO = Executors.newSingleThreadExecutor();
  private final java.util.concurrent.Executor io;
  private final long queryMillis;
  private final com.deepseekharness.app.plugins.PluginRegistry registry =
      new com.deepseekharness.app.plugins.PluginRegistry();
  private final com.deepseekharness.app.plugins.PluginOperationQueue.Queue<PendingWork> operations =
      new com.deepseekharness.app.plugins.PluginOperationQueue.Queue<>();
  private final com.deepseekharness.app.plugins.PluginBackendAdapter backend;
  private static final java.util.concurrent.ScheduledExecutorService SIGNALS =
      Executors.newSingleThreadScheduledExecutor(
          r -> {
            Thread thread = new Thread(r, "plugin-signals");
            thread.setDaemon(true);
            return thread;
          });
  private final AtomicBoolean working = new AtomicBoolean();
  private final AtomicBoolean runtimeProjectionPending = new AtomicBoolean();
  private final AtomicBoolean runtimeProjectionQueued = new AtomicBoolean();
  private final AtomicLong installedRevision = new AtomicLong();
  private final MutableLiveData<State> state;
  private volatile List<Item> items = Collections.emptyList();
  private volatile boolean itemsKnown;
  private volatile boolean safeMode;
  private volatile boolean installationSucceeded;
  private volatile PluginTask activeTask;
  private volatile PendingWork activeWork;
  private volatile boolean queryRunning;
  private volatile PendingWork pendingQuery;
  private final java.util.Set<String> changedTargets =
      java.util.concurrent.ConcurrentHashMap.newKeySet();
  private volatile ResumeWeb pendingResume;

  private static final class ResumeWeb {
    final long generation, cancellationRevision;

    ResumeWeb(long generation, long cancellationRevision) {
      this.generation = generation;
      this.cancellationRevision = cancellationRevision;
    }

    boolean matches(HarnessController controller, HarnessSessionState session) {
      return controller.getWebGeneration() == generation
          && session.startProtection.cancellationRevision() == cancellationRevision;
    }
  }

  private static final class Invalidated extends IOException {
    Invalidated(String message) {
      super(message);
    }
  }

  private static final class CancelConfirmed extends IOException {
    CancelConfirmed(String message) {
      super(message);
    }
  }

  private volatile Listener listener;
  private volatile String lastEnvironmentNotice = "";
  private volatile String lastRuntimeNotice = "";
  private final android.os.Handler main = new android.os.Handler(android.os.Looper.getMainLooper());
  private Preview installedPreview;
  private volatile Preview preparedPreview;
  private final java.util.LinkedHashMap<String, Preview> operationPreviews =
      new java.util.LinkedHashMap<>();

  public synchronized Preview operationPreview(String id) {
    return operationPreviews.get(id);
  }

  private synchronized void rememberPreview(String id, Preview value) {
    operationPreviews.put(id, value);
    while (operationPreviews.size() > 64)
      operationPreviews.remove(operationPreviews.keySet().iterator().next());
  }

  private final MutableLiveData<Preview> preview = new MutableLiveData<>();
  private volatile List<PendingReview> pendingReviews = Collections.emptyList();

  public interface Listener {
    void invalidated();

    void sourceResolved(com.deepseekharness.app.plugins.PluginBackendAdapter.ResolvedSource source);

    void queryFinished();
  }

  public void setListener(Listener listener) {
    this.listener = listener;
  }

  public com.deepseekharness.app.plugins.PluginRegistry registry() {
    return registry;
  }

  public com.deepseekharness.app.plugins.PluginOperationQueue.Operation operation(String id) {
    return operations.get(id);
  }

  public static final class PendingReview {
    public final String id, label;

    public PendingReview(JSONObject value) throws Exception {
      id = value.getString("previewId");
      if (!id.matches("[a-f0-9]{32}")) throw new IOException("插件预览标识无效");
      JSONArray names = value.getJSONArray("names");
      List<String> text = new ArrayList<>();
      for (int i = 0; i < names.length(); i++) text.add(names.getString(i));
      label = String.join("、", text);
    }
  }

  public static final class Item {
    private final JSONObject snapshot;
    public final String name, description, version, source, metadataError;
    public final String stableKey, contentKey, profile, scope, sessionId, pluginId;
    public final long runtimeGeneration;
    public final boolean restartRequired, runtimeStale;
    public final String location, origin;
    public final boolean detected;
    public final boolean dynamic;
    public final String latestVersion,
        updatePreviewId,
        updateMessage,
        rollbackVersion,
        compatibility;
    public final String loadState, runtimeState;
    public final boolean updateAvailable, updatable;
    public final boolean enabled,
        builtin,
        official,
        available,
        exportable,
        deletable,
        runtimeProvided,
        runtimeLocated;

    public Item(JSONObject json) {
      try {
        snapshot = new JSONObject(json.toString());
      } catch (org.json.JSONException malformed) {
        throw new IllegalArgumentException("PLUGIN_ITEM_INVALID", malformed);
      }
      name = json.optString("name");
      description = json.optString("description");
      metadataError = json.optString("metadataError");
      version = json.optString("version");
      source = json.optString("source");
      location = json.optString("location");
      detected = json.optBoolean("detected");
      dynamic = json.optBoolean("dynamic");
      enabled = json.optBoolean("enabled");
      // Names are identities, not an npm-scope classifier. Only the signed
      // registry and its one explicit historical alias own the builtin role.
      builtin =
          json.optBoolean("builtin")
              && (com.deepseekharness.app.util.BuiltinPlugins.SIGNED_BUILTINS.contains(name)
                  || "@deepseek-ai/dsh-experimental-auto-review".equals(name));
      runtimeProvided = json.optBoolean("runtimeProvided");
      runtimeLocated = json.optBoolean("runtimeLocated", runtimeProvided);
      origin = builtin ? "dsha-signed" : runtimeProvided ? "dsh-managed" : "user";
      official =
          json.optBoolean("official")
              && com.deepseekharness.app.util.BuiltinPluginRegistry.OFFICIAL.contains(name);
      available = json.optBoolean("available");
      exportable = json.optBoolean("exportable");
      deletable = json.optBoolean("deletable");
      latestVersion = json.optString("latestVersion");
      updatePreviewId = json.optString("updatePreviewId");
      updateMessage = json.optString("updateMessage");
      rollbackVersion = json.optString("rollbackVersion");
      compatibility = json.optString("compatibility");
      updateAvailable = json.optBoolean("updateAvailable");
      updatable = json.optBoolean("updatable");
      loadState = json.optString("loadState");
      runtimeState = json.optString("runtimeState", "unknown");
      runtimeGeneration = json.optLong("runtimeGeneration", -1);
      runtimeStale = json.optBoolean("runtimeStale", "unknown".equals(runtimeState));
      restartRequired = json.optBoolean("restartRequired");
      profile = json.optString("profile", "web");
      scope = json.optString("scope", "application");
      sessionId = json.optString("sessionId");
      pluginId = json.optString("pluginId");
      // 仅归并已有官方历史别名；显示来源、版本和位置均不作为条目身份。
      stableKey =
          dynamic
              ? key("dynamic", Long.toString(runtimeGeneration), sessionId, pluginId)
              : key(
                  "installed",
                  profile,
                  scope,
                  com.deepseekharness.app.plugins.PluginIdentity.canonical(name));
      contentKey =
          key(
              name,
              description,
              metadataError,
              version,
              source,
              location,
              origin,
              Boolean.toString(detected),
              Boolean.toString(dynamic),
              Boolean.toString(enabled),
              Boolean.toString(builtin),
              Boolean.toString(official),
              Boolean.toString(available),
              Boolean.toString(exportable),
              Boolean.toString(deletable),
              Boolean.toString(runtimeProvided),
              Boolean.toString(runtimeLocated),
              Boolean.toString(updateAvailable),
              Boolean.toString(updatable),
              latestVersion,
              updatePreviewId,
              updateMessage,
              rollbackVersion,
              compatibility,
              loadState,
              runtimeState,
              Long.toString(runtimeGeneration),
              Boolean.toString(runtimeStale),
              Boolean.toString(restartRequired));
    }

    private static String key(String... parts) {
      StringBuilder key = new StringBuilder();
      for (String part : parts) key.append(part.length()).append(':').append(part);
      return key.toString();
    }

    public Item withIntent(boolean enabled) throws Exception {
      return new Item(
          new JSONObject(snapshot.toString()).put("enabled", enabled).put("restartRequired", true));
    }

    public Item withRuntime(String runtimeState, long generation) throws Exception {
      return withRuntime(runtimeState, generation, "unknown".equals(runtimeState));
    }

    public Item withRuntime(String runtimeState, long generation, boolean stale) throws Exception {
      return new Item(
          new JSONObject(snapshot.toString())
              .put("runtimeState", runtimeState)
              .put("runtimeGeneration", generation)
              .put("runtimeStale", stale));
    }

    public Item withRestartRequired(boolean required) throws Exception {
      return new Item(new JSONObject(snapshot.toString()).put("restartRequired", required));
    }
  }

  public static final class Preview {
    public final String id, description, confirmation, action;
    public final com.deepseekharness.app.plugins.PluginSourceIdentity source;

    Preview(JSONObject json) throws Exception {
      this(json, null);
    }

    Preview(JSONObject json, com.deepseekharness.app.plugins.PluginSourceIdentity source)
        throws Exception {
      this.source = source;
      id = json.getString("previewId");
      if (!id.matches("[a-f0-9]{32}")) throw new IOException("插件预览标识无效");
      confirmation = json.getString("confirmationSha256");
      action = json.optString("action", "install");
      if (!confirmation.matches("[a-f0-9]{64}")) throw new IOException("插件预览标识无效");
      data = new JSONObject(json.toString());
      JSONArray packages = data.getJSONArray("items");
      for (int i = 0; i < packages.length(); i++) packages.getJSONObject(i).getString("name");
      description = description();
    }

    private final JSONObject data;

    public boolean blocked() {
      JSONArray items = data.optJSONArray("items");
      if (items != null)
        for (int i = 0; i < items.length(); i++) {
          JSONObject item = items.optJSONObject(i);
          if (item != null
              && (item.optBoolean("existingConflict")
                  || item.optJSONArray("missingDependencies") != null
                      && item.optJSONArray("missingDependencies").length() > 0)) return true;
        }
      return false;
    }

    public String description() {
      JSONObject json = data;
      StringBuilder text = new StringBuilder();
      JSONArray packages = json.optJSONArray("items");
      for (int i = 0; i < packages.length(); i++) {
        JSONObject pkg = packages.optJSONObject(i);
        text.append(pkg.optString("name"))
            .append(" · ")
            .append(pkg.optString("version"))
            .append(com.deepseekharness.app.util.UiText.text("\n作者："))
            .append(pkg.optString("author", com.deepseekharness.app.util.UiText.text("未注明")))
            .append("\n")
            .append(pkg.optString("description"))
            .append("\n")
            .append(
                com.deepseekharness.app.util.UiStateText.render(
                    pkg.optString("compatibilityMessage")))
            .append("\n\n");
        JSONArray dependencies = pkg.optJSONArray("resolvedDependencies");
        text.append(com.deepseekharness.app.util.UiText.text("实际依赖数量："))
            .append(pkg.optInt("dependencyCount"))
            .append('\n');
        if (dependencies != null)
          for (int at = 0; at < Math.min(50, dependencies.length()); at++) {
            JSONObject dependency = dependencies.optJSONObject(at);
            if (dependency != null)
              text.append("  ")
                  .append(dependency.optString("name"))
                  .append(" · ")
                  .append(dependency.optString("version"))
                  .append('\n');
          }
        if (pkg.optInt("dependencyCount") > 50)
          text.append(com.deepseekharness.app.util.UiText.text("摘要显示前 50 项；确认时仍核对完整依赖内容。\n"));
        String dependencyState = pkg.optString("dependencyState");
        if (dependencyState.equals("legacy-unknown"))
          text.append(
              com.deepseekharness.app.util.UiText.text("历史依赖信息不足；本次核对现存源码和实际依赖，不重新解析旧版本。\n"));
        if (dependencyState.equals("modified-original"))
          text.append(
              com.deepseekharness.app.util.UiText.choose(
                  "现存内容与旧依赖记录不同；已按本次内容重新核对。\n",
                  "Existing content differs from the old dependency record; the current content was checked.\n"));
        String locked = pkg.optString("dependencyLockSha256");
        if (!locked.isEmpty())
          text.append("pnpm · ")
              .append(
                  pkg.optString("packageManagerVersion").equals("not-executed")
                      ? com.deepseekharness.app.util.UiText.choose(
                          "随包依赖，未运行包管理器", "Bundled dependencies; package manager not run")
                      : pkg.optString("packageManagerVersion"))
              .append("\nSHA-256: ")
              .append(locked)
              .append('\n');
        JSONObject claims = pkg.optJSONObject("authorDependencyClaims");
        if (claims != null && claims.length() > 0)
          text.append(
                  com.deepseekharness.app.util.UiText.choose(
                      "作者声明（未作为本机验证结论）：", "Author claims (not local verification): "))
              .append(claims)
              .append('\n');
        JSONArray missing = pkg.optJSONArray("missingDependencies");
        if (missing != null && missing.length() > 0)
          text.append(com.deepseekharness.app.util.UiText.text("缺失依赖："))
              .append(missing)
              .append('\n');
        if (pkg.optBoolean("existingConflict"))
          text.append(
              com.deepseekharness.app.util.UiText.text("当前已有同名插件，现有版本不会被覆盖。请先到插件管理处理冲突。\n"));
        text.append('\n');
      }
      text.append(com.deepseekharness.app.util.UiText.text("来源："))
          .append(
              json.optString("source").isEmpty()
                  ? com.deepseekharness.app.util.UiText.text("本地插件包")
                  : json.optString("source"))
          .append("\nSHA-256：")
          .append(json.optString("sha256"))
          .append(
              json.optString("contentsSha256").isEmpty()
                  ? ""
                  : com.deepseekharness.app.util.UiText.format(
                      "\n已准备内容 SHA-256：%s", json.optString("contentsSha256")))
          .append(
              action.equals("install")
                  ? com.deepseekharness.app.util.UiText.choose(
                      "\n\n安装后默认启用；Web 启动时加载，同名更新保留上一版。",
                      "\n\nEnabled by default after installation; Web loads it on startup. Updates retain the previous version.")
                  : com.deepseekharness.app.util.UiText.choose(
                      "\n\n正在处理启用或回退；启动 Web 后生效。",
                      "\n\nApplying enable or rollback; the change takes effect on Web startup."))
          .append(
              com.deepseekharness.app.util.UiText.choose(
                  "\n插件将在 DSHA 的权限范围内运行。", "\nThe plugin runs with DSHA's permissions."));
      return SensitiveData.redact(text.toString());
    }
  }

  public static final class State {
    /** 没有新的成功 / 失败结果；普通进度和目录更新都用它。 */
    public static final int RESULT_NONE = 0;

    /** 最近一次任务成功。 */
    public static final int RESULT_SUCCESS = 1;

    /** 最近一次任务失败，或一次明确的用户操作错误。 */
    public static final int RESULT_FAILURE = 2;

    public final List<Item> items;
    public final boolean busy;
    public final String message;
    public final boolean cancellable;
    public final int percent;
    public final int result;
    public final com.deepseekharness.app.plugins.PluginRegistry.Snapshot catalog;
    public final List<com.deepseekharness.app.plugins.PluginOperationQueue.Operation> operations;

    State(List<Item> items, boolean busy, String message) {
      this(items, busy, message, false, -1);
    }

    State(List<Item> items, boolean busy, String message, boolean cancellable, int percent) {
      this.items = items;
      this.busy = busy;
      this.message = message;
      this.cancellable = cancellable;
      this.percent = percent;
      this.result = RESULT_NONE;
      this.catalog = null;
      this.operations = Collections.emptyList();
    }

    /** 保留原签名供既有反射测试使用；默认没有新结果。 */
    private State(
        com.deepseekharness.app.plugins.PluginRegistry.Snapshot catalog,
        List<com.deepseekharness.app.plugins.PluginOperationQueue.Operation> operations,
        boolean busy,
        String message,
        boolean cancellable,
        int percent) {
      this(catalog, operations, busy, message, cancellable, percent, RESULT_NONE);
    }

    private State(
        com.deepseekharness.app.plugins.PluginRegistry.Snapshot catalog,
        List<com.deepseekharness.app.plugins.PluginOperationQueue.Operation> operations,
        boolean busy,
        String message,
        boolean cancellable,
        int percent,
        int result) {
      this.catalog = catalog;
      this.items = catalog.items;
      this.operations = operations;
      this.busy = busy;
      this.message = message;
      this.cancellable = cancellable;
      this.percent = percent;
      this.result = result;
    }
  }

  public PluginRepository(@NonNull Application app) {
    this(app, IO);
  }

  PluginRepository(@NonNull Application app, java.util.concurrent.Executor io) {
    this(app, io, com.deepseekharness.app.runtime.GuestScriptBudget.QUERY_TIMEOUT_MS);
  }

  PluginRepository(@NonNull Application app, java.util.concurrent.Executor io, long queryMillis) {
    super(app);
    this.io = java.util.Objects.requireNonNull(io);
    if (queryMillis < 1) throw new IllegalArgumentException("PLUGIN_QUERY_BUDGET");
    this.queryMillis = queryMillis;
    backend =
        new com.deepseekharness.app.plugins.PluginBackendAdapter(
            app, () -> HarnessController.get(app).getWebGeneration());
    state =
        new MutableLiveData<>(
            new State(
                registry.snapshot(), operations.snapshot(), false, "选择链接安装或导入本地插件包", false, -1));
  }

  public static PluginRepository get(Context context) {
    return com.deepseekharness.app.DshaApp.from(context).pluginRepository();
  }

  public LiveData<State> state() {
    return state;
  }

  public boolean isBusy() {
    return working.get();
  }

  public boolean hasKnownItems() {
    return itemsKnown;
  }

  public long installedRevision() {
    return installedRevision.get();
  }

  public void invalidateInstalledState() {
    installedRevision.incrementAndGet();
    registry.invalidate();
    Listener current = listener;
    if (current != null) main.post(current::invalidated);
  }

  public boolean isSafeMode() {
    return safeMode;
  }

  public boolean installationSucceeded() {
    return installationSucceeded;
  }

  public String installedDescription() {
    return installedPreview == null ? "" : installedPreview.description();
  }

  public String inspectCapabilities(Item item, java.util.function.Consumer<String> shown) {
    final String[] details = {""};
    return submit(
        Type.CHECK_UPDATES,
        item,
        null,
        com.deepseekharness.app.util.UiText.choose(
            "正在读取插件能力声明…", "Reading plugin capability declarations…"),
        withoutCatalogChange(
            proot -> {
              JSONObject output =
                  result(runManager(proot, "capabilities " + ShellQuote.arg(item.name)));
              details[0] = capabilityDescription(output.optJSONObject("capabilities"));
              return operationMessage(output);
            }),
        () -> shown.accept(details[0]));
  }

  public static String capabilityDescription(JSONObject report) {
    var text = new StringBuilder();
    text.append(
        com.deepseekharness.app.util.UiText.choose(
            "\n静态能力信息（声明与源码线索，不代表权限或安全保证）\n",
            "\nStatic capabilities (declarations and source references, not permissions or safety guarantees)\n"));
    if (report == null)
      return text.append(
              com.deepseekharness.app.util.UiText.choose(
                  "历史记录无此信息；可在详情中重新检查。\n",
                  "No information in this historical record; inspect again from plugin details.\n"))
          .toString();
    for (String key : new String[] {"clientInject", "scripts", "pnpmfiles"}) {
      String label =
          switch (key) {
            case "clientInject" ->
                com.deepseekharness.app.util.UiText.choose(
                    "声明的客户端服务：", "Declared client services: ");
            case "scripts" ->
                com.deepseekharness.app.util.UiText.choose("脚本声明：", "Script declarations: ");
            default -> "pnpmfile: ";
          };
      text.append(label).append(report.optJSONArray(key)).append('\n');
    }
    text.append(
            com.deepseekharness.app.util.UiText.choose(
                "已读依赖 manifests / 脚本声明：", "Dependency manifests read / script declarations: "))
        .append(report.optInt("inspectedDependencyManifests"))
        .append(" / ")
        .append(report.optInt("dependencyScriptCount"))
        .append('\n');
    JSONArray findings = report.optJSONArray("findings");
    if (findings != null)
      for (int i = 0; i < Math.min(findings.length(), 200); i++) {
        JSONObject finding = findings.optJSONObject(i);
        if (finding == null) continue;
        text.append(
                finding.optString("kind").equals("server-inject")
                    ? com.deepseekharness.app.util.UiText.choose(
                        "声明的服务依赖：", "Declared service dependency: ")
                    : com.deepseekharness.app.util.UiText.choose(
                        "设备桥调用线索：", "Device bridge source reference: "))
            .append(finding.optString("value"))
            .append(" · ")
            .append(finding.optString("file"))
            .append(':')
            .append(finding.optInt("line"))
            .append('\n');
      }
    JSONArray unknown = report.optJSONArray("unknown");
    if (unknown != null && unknown.length() > 0) {
      text.append(com.deepseekharness.app.util.UiText.choose("未确认范围：\n", "Unconfirmed scope:\n"));
      for (int i = 0; i < Math.min(unknown.length(), 200); i++)
        text.append("  ").append(capabilityUnknown(unknown.optString(i))).append('\n');
    }
    text.append("DSH ")
        .append(report.optString("runtimeDsh"))
        .append(" · ")
        .append(report.optString("parser"))
        .append('\n');
    return text.toString();
  }

  private static String capabilityUnknown(String value) {
    String code = value, prefix = "";
    int separator = value.lastIndexOf(':');
    if (separator >= 0 && !value.startsWith("entry not inspected:")) {
      prefix = value.substring(0, separator) + ": ";
      code = value.substring(separator + 1);
    }
    String translated =
        switch (code) {
          case "patch references and dynamically loaded modules are not followed" ->
              com.deepseekharness.app.util.UiText.choose(
                  "未跟踪补丁引用与动态加载模块",
                  "Patch references and dynamically loaded modules were not followed");
          case "dynamic-inject", "non-literal client inject" ->
              com.deepseekharness.app.util.UiText.choose(
                  "动态服务声明未确认", "Dynamic service declarations are unconfirmed");
          case "reexported-inject" ->
              com.deepseekharness.app.util.UiText.choose(
                  "重导出的服务声明未确认", "Reexported service declarations are unconfirmed");
          case "dynamic-bridge" ->
              com.deepseekharness.app.util.UiText.choose(
                  "动态设备桥调用未确认", "Dynamic device bridge calls are unconfirmed");
          case "node-limit", "finding-limit" ->
              com.deepseekharness.app.util.UiText.choose(
                  "达到静态分析预算", "Static analysis budget reached");
          case "unparsed-source" ->
              com.deepseekharness.app.util.UiText.choose("源码未能解析", "Source could not be parsed");
          case "no declared server entry" ->
              com.deepseekharness.app.util.UiText.choose("未声明服务入口", "No server entry declared");
          case "invalid script declaration" ->
              com.deepseekharness.app.util.UiText.choose("脚本声明格式无效", "Invalid script declaration");
          case "dependency scripts were not inspected",
                  "dependency script coverage incomplete",
                  "dependency manifest coverage incomplete" ->
              com.deepseekharness.app.util.UiText.choose(
                  "依赖脚本声明未完整检查", "Dependency script declarations were not fully inspected");
          case "AST parser unavailable or budget exceeded" ->
              com.deepseekharness.app.util.UiText.choose(
                  "静态解析器不可用或超出预算", "Static parser unavailable or budget exceeded");
          case "package changed during inspection", "package input not inspected" ->
              com.deepseekharness.app.util.UiText.choose(
                  "包声明输入未能确认", "Package declaration input could not be confirmed");
          default ->
              value.startsWith("entry not inspected:")
                  ? com.deepseekharness.app.util.UiText.format(
                      "入口未检查：%s", value.substring("entry not inspected:".length()).trim())
                  : value;
        };
    return prefix + translated;
  }

  public LiveData<Preview> preview() {
    return preview;
  }

  public List<PendingReview> pendingReviews() {
    return pendingReviews;
  }

  public String openPendingReview(String id) {
    if (id == null || !id.matches("[a-f0-9]{32}")) return "";
    return submit(
        Type.CONFIRM_PREVIEW,
        null,
        null,
        com.deepseekharness.app.util.UiText.choose(
            "正在恢复未完成的插件安装…", "Resuming the pending plugin installation…"),
        proot -> receivePreview(result(runManager(proot, "show-preview " + ShellQuote.arg(id)))),
        null);
  }

  public String reviewRestored(String operation, String node) {
    return submit(
        Type.REVIEW_RESTORED,
        null,
        null,
        com.deepseekharness.app.util.UiText.choose("正在准备隔离插件…", "Preparing the retained plugin…"),
        proot -> {
          String group =
              com.deepseekharness.app.backup.QuarantinedPluginReview.prepare(
                  getApplication(),
                  operation,
                  node,
                  new com.deepseekharness.app.backup.BackupControl(null));
          return receivePreview(
              result(
                  runManager(
                      proot,
                      "review-restored " + ShellQuote.arg(group) + " " + ShellQuote.arg(node))));
        },
        null);
  }

  public String reviewLegacyPreset(String retainedKey) {
    return submit(
        Type.REVIEW_LEGACY,
        null,
        null,
        com.deepseekharness.app.util.UiText.choose(
            "正在准备旧预设候选与当前依赖…", "Preparing the legacy preset and current dependencies…"),
        proot -> {
          String[] candidate =
              com.deepseekharness.app.backup.QuarantinedPluginReview.preparePreset(
                  getApplication(),
                  retainedKey,
                  new com.deepseekharness.app.backup.BackupControl(null));
          return receivePreview(
              result(
                  runManager(
                      proot,
                      "review-restored "
                          + ShellQuote.arg(candidate[0])
                          + " "
                          + ShellQuote.arg(candidate[1]))));
        },
        null);
  }

  public void selectionMessage(String message) {
    SIGNALS.execute(() -> DiagnosticLog.record(getApplication(), "FILE_SELECTION", message));
    if (!working.get()) publish(message, false, -1, State.RESULT_FAILURE);
  }

  private interface Work {
    String run(ProotBootstrap proot) throws Exception;

    default boolean maintenance() {
      return false;
    }

    default boolean includesItems() {
      return false;
    }

    default boolean query() {
      return false;
    }

    default boolean changesCatalog() {
      return !query();
    }
  }

  /** 脚本在同一进程中返回变更后的真实列表，避免再次启动容器与重复扫描。 */
  private Work withItems(String command) {
    return withItems(command, false);
  }

  private Work withItems(String command, boolean query) {
    return new Work() {
      @Override
      public boolean query() {
        return query;
      }

      @Override
      public boolean includesItems() {
        return true;
      }

      @Override
      public String run(ProotBootstrap proot) throws Exception {
        JSONObject output = result(runManager(proot, command));
        items = readItems(proot, output);
        itemsKnown = true;
        return output.getString("message");
      }
    };
  }

  private String submit(String progress, Work work) {
    return submit(progress, work, null);
  }

  private String submit(String progress, Work work, Runnable onSuccess) {
    return submit(progress, work, onSuccess, true);
  }

  private String submit(String progress, Work work, Runnable onSuccess, boolean cancellable) {
    return submit(progress, work, onSuccess, cancellable, null);
  }

  private Work withoutCatalogChange(Work work) {
    return new Work() {
      @Override
      public String run(ProotBootstrap proot) throws Exception {
        return work.run(proot);
      }

      @Override
      public boolean changesCatalog() {
        return false;
      }
    };
  }

  private String submit(Type type, Item target, Boolean desired, String progress, Work work) {
    return submit(type, target, desired, progress, work, null, true, null);
  }

  private String submit(
      Type type, Item target, Boolean desired, String progress, Work work, Runnable onSuccess) {
    return submit(type, target, desired, progress, work, onSuccess, true, null);
  }

  private String submit(
      Type type,
      Item target,
      Boolean desired,
      String progress,
      Work work,
      Runnable onSuccess,
      boolean cancellable) {
    return submit(type, target, desired, progress, work, onSuccess, cancellable, null);
  }

  /** 只登记内存意图；队首在后台才取得环境凭据。 */
  private String submit(
      String progress, Work work, Runnable onSuccess, boolean cancellable, Runnable onAccepted) {
    return submit(progress, work, onSuccess, cancellable, onAccepted, false);
  }

  private String submit(
      String progress,
      Work work,
      Runnable onSuccess,
      boolean cancellable,
      Runnable onAccepted,
      boolean automatic) {
    return submit(
        com.deepseekharness.app.plugins.PluginOperationQueue.Type.INSPECT,
        null,
        null,
        progress,
        work,
        onSuccess,
        cancellable,
        onAccepted,
        "scope:web:INSPECT",
        "",
        automatic);
  }

  private static final class PendingWork {
    final Work work;
    final Runnable success;
    final String progress;
    final boolean cancellable;
    final Item target;
    final boolean automatic;
    final AtomicBoolean cancelled = new AtomicBoolean();
    final com.deepseekharness.app.plugins.PluginSourceIdentity source;
    final com.deepseekharness.app.runtime.GuestScriptBudget queryBudget;
    volatile String id;
    volatile Preview candidate;
    volatile com.deepseekharness.app.plugins.PluginSourceIdentity executionSource;
    volatile boolean catalogReceiptHandled, catalogReceiptRecorded;
    volatile RuntimeTasks runtimeWork;
    volatile boolean committed, dispatched;
    volatile boolean resumeWeb;
    volatile long resumeGeneration = -1;
    volatile long resumeCancellationRevision = -1;

    PendingWork(
        Work work,
        Runnable success,
        String progress,
        boolean cancellable,
        Item target,
        com.deepseekharness.app.plugins.PluginSourceIdentity source,
        long queryMillis,
        boolean automatic) {
      this.work = work;
      this.success = success;
      this.progress = progress;
      this.cancellable = cancellable;
      this.target = target;
      this.automatic = automatic;
      this.source = source;
      queryBudget =
          work.query()
              ? new com.deepseekharness.app.runtime.GuestScriptBudget(
                  queryMillis, cancelled::get, "PLUGIN_QUERY")
              : null;
    }
  }

  private String submit(
      com.deepseekharness.app.plugins.PluginOperationQueue.Type type,
      Item target,
      Boolean desired,
      String progress,
      Work work,
      Runnable onSuccess,
      boolean cancellable,
      Runnable onAccepted) {
    return submit(
        type,
        target,
        desired,
        progress,
        work,
        onSuccess,
        cancellable,
        onAccepted,
        "scope:web:" + type.name(),
        "",
        false);
  }

  private String submit(
      Type type,
      Item target,
      Boolean desired,
      String progress,
      Work work,
      Runnable onSuccess,
      boolean cancellable,
      Runnable onAccepted,
      String inputIdentity,
      String expectedVersion) {
    return submit(
        type,
        target,
        desired,
        progress,
        work,
        onSuccess,
        cancellable,
        onAccepted,
        inputIdentity,
        expectedVersion,
        false);
  }

  private String submit(
      Type type,
      Item target,
      Boolean desired,
      String progress,
      Work work,
      Runnable onSuccess,
      boolean cancellable,
      Runnable onAccepted,
      String inputIdentity,
      String expectedVersion,
      boolean automatic) {
    PendingWork request =
        new PendingWork(
            work,
            onSuccess,
            progress,
            cancellable,
            target,
            registry.snapshot().source,
            queryMillis,
            automatic);
    if (work.query()) {
      synchronized (this) {
        if (pendingQuery != null || queryRunning) return "";
        request.id = UUID.randomUUID().toString().replace("-", "");
        pendingQuery = request;
      }
    } else {
      var entry =
          operations.enqueue(
              type,
              target == null ? inputIdentity : target.stableKey,
              desired,
              target == null ? expectedVersion : target.version,
              request.source,
              request);
      request.id = entry.operationId;
      if (entry.operation().phase
          == com.deepseekharness.app.plugins.PluginOperationQueue.Phase.REJECTED) {
        publish("插件任务队列已满，请等待已有任务完成", false, -1, State.RESULT_FAILURE);
        return request.id;
      }
    }
    if (onAccepted != null) onAccepted.run();
    publish("已接收，排队中：" + progress, cancellable, -1);
    scheduleWorker();
    return request.id;
  }

  private void publish(String message, boolean cancellable, int percent) {
    publish(message, cancellable, percent, State.RESULT_NONE);
  }

  private void publish(String message, boolean cancellable, int percent, int result) {
    Runnable publication =
        () ->
            state.setValue(
                new State(
                    registry.snapshot(),
                    operations.snapshot(),
                    working.get() || operations.hasPending() || pendingQuery != null,
                    message,
                    cancellable,
                    percent,
                    result));
    if (android.os.Looper.myLooper() == android.os.Looper.getMainLooper()) publication.run();
    else main.post(publication);
  }

  public void publishCatalog(String message) {
    adoptCatalog();
    publish(message, false, -1);
  }

  /** 运行身份失效可在任务途中发布，但不能覆盖执行器尚未提交的工作列表。 */
  public void publishCatalogState() {
    State shown = state.getValue();
    publish(
        shown == null ? "" : shown.message,
        shown != null && shown.cancellable,
        shown == null ? -1 : shown.percent,
        shown == null ? State.RESULT_NONE : shown.result);
  }

  private void adoptCatalog() {
    var snapshot = registry.snapshot();
    items = snapshot.items;
    itemsKnown = snapshot.known;
    safeMode = snapshot.safeMode;
    pendingReviews = snapshot.pendingReviews;
  }

  private void scheduleWorker() {
    if (!working.compareAndSet(false, true)) return;
    try {
      io.execute(this::drain);
    } catch (RuntimeException rejected) {
      working.set(false);
      var entry = operations.poll();
      if (entry != null)
        operations.complete(
            entry.operationId,
            com.deepseekharness.app.plugins.PluginOperationQueue.Phase.FAILED,
            "插件任务未开始：" + SensitiveData.redact(String.valueOf(rejected)));
      synchronized (this) {
        pendingQuery = null;
      }
      publish("插件后台执行器不可用，请重试", false, -1, State.RESULT_FAILURE);
      Listener current = listener;
      if (current != null) main.post(current::queryFinished);
    }
  }

  /** 单消费者，每一轮完整结束后才取下一项；等待期间没有执行凭据。 */
  private void drain() {
    PendingWork request;
    var entry = operations.poll();
    if (entry != null) request = entry.work;
    else
      synchronized (this) {
        request = pendingQuery;
        pendingQuery = null;
        queryRunning = request != null;
      }
    if (request == null) {
      working.set(false);
      // 释放 CAS 后复查，覆盖取消唯一等待项与并发入队之间的丢唤醒窗口。
      if (operations.hasPending() || pendingQuery != null) scheduleWorker();
      State shown = state.getValue();
      publish(
          shown == null ? "" : shown.message,
          false,
          -1,
          shown == null ? State.RESULT_NONE : shown.result);
      return;
    }
    activeWork = request;
    executeRequest(request);
  }

  /** 仅用于 UI 提示；真实磁盘和维护核验由队首后台执行。 */
  public String environmentBlockMessage() {
    return lastEnvironmentNotice;
  }

  private void executeRequest(PendingWork request) {
    com.deepseekharness.app.util.EnvironmentTaskGate.Lease lease = null;
    RuntimeTasks runtimeWork = null;
    PluginTask task = null;
    java.util.concurrent.ScheduledFuture<?> poll = null;
    com.deepseekharness.app.plugins.PluginRegistry.Query query = null;
    boolean success = false;
    boolean receiptConfirmed = false;
    String confirmedMessage = "";
    String message = request.progress;
    var phase = com.deepseekharness.app.plugins.PluginOperationQueue.Phase.FAILED;
    try (var budget = request.queryBudget == null ? null : request.queryBudget.enter()) {
      if (request.queryBudget != null) request.queryBudget.remainingMillis();
      if (request.cancelled.get()) throw new IOException("已取消");
      HarnessController controller = HarnessController.get(getApplication());
      lastEnvironmentNotice = environmentBlockOnWorker(controller);
      if (!lastEnvironmentNotice.isEmpty()) throw new IOException(lastEnvironmentNotice);
      boolean queryRequest = request.work.query();
      lease =
          queryRequest
              ? com.deepseekharness.app.util.EnvironmentTaskGate.tryAcquireShared("插件查询")
              : com.deepseekharness.app.util.EnvironmentTaskGate.tryAcquire("插件操作");
      if (lease == null)
        throw new IOException(
            queryRequest ? "已有环境维护或插件修改任务，本次列表读取未执行，请稍后刷新" : "已有环境任务启动，本次任务未执行，请重试");
      runtimeWork = RuntimeTasks.beginDetached("插件管理");
      request.runtimeWork = runtimeWork;
      runtimeWork.describe(request.progress);
      ProotBootstrap proot = controller.proot();
      if (!proot.isEnvironmentReady()) throw new IOException("环境未就绪，请先完成解压 / 安装");
      var beforeResolution = registry.snapshot().source;
      var resolved = lease.run(backend::resolveSource);
      if (!java.util.Objects.equals(beforeResolution, registry.snapshot().source)
          && !resolved.identity.equals(registry.snapshot().source))
        throw new IOException("插件来源观察已被更新；旧请求未执行");
      registry.updateSource(resolved.identity);
      if (!request.work.query()
          && request.source != null
          && !request.source.sameDataSource(resolved.identity)) {
        phase = com.deepseekharness.app.plugins.PluginOperationQueue.Phase.INVALIDATED;
        throw new IOException("插件任务所属数据根或环境已变化；旧任务未执行，请重新提交");
      }
      adoptCatalog();
      Listener current = listener;
      if (current != null) main.post(() -> current.sourceResolved(resolved));
      if (request.work.query()) query = registry.beginQuery(resolved.identity);
      if (request.target != null) {
        if (changedTargets.contains(request.target.stableKey))
          throw new Invalidated("前序任务需要核对此插件；旧版本请求未执行，请在核对后重新提交");
        requireTarget(request.target);
      }
      request.executionSource = resolved.identity;
      operations.bindExecutionSource(request.id, resolved.identity);
      task = new PluginTask(proot.getRootfsDir(), request.id);
      activeTask = task;
      if (request.cancelled.get()) task.cancel();
      PluginTask observedTask = task;
      RuntimeTasks observedRuntime = runtimeWork;
      operations.progress(
          request.id,
          com.deepseekharness.app.plugins.PluginOperationQueue.Phase.RUNNING,
          request.progress,
          -1,
          null,
          task.id);
      poll =
          SIGNALS.scheduleWithFixedDelay(
              () -> poll(request, observedTask, observedRuntime),
              0,
              400,
              java.util.concurrent.TimeUnit.MILLISECONDS);
      DiagnosticLog.record(getApplication(), "PLUGIN_START", request.progress);
      PluginTask executingTask = task;
      message =
          lease.run(
              () -> {
                if (MaintenanceCoordinator.pending(controller)
                    || MaintenanceCoordinator.isExclusive())
                  throw new IOException("环境维护尚未完成，请先恢复维护，再刷新或操作插件");
                executingTask.check();
                if (request.queryBudget != null) request.queryBudget.remainingMillis();
                if (!resolved.identity.equals(backend.resolveSource().identity))
                  throw new IOException("插件执行环境已变化；未执行旧请求");
                if (request.work.maintenance()) {
                  try (RuntimeTasks synchronous = RuntimeTasks.begin()) {
                    request.runtimeWork.close();
                    return MaintenanceCoordinator.exclusive(
                        controller, () -> request.work.run(proot));
                  }
                }
                return request.work.run(proot);
              });
      receiptConfirmed = !request.work.query();
      confirmedMessage = message;
      var after = lease.run(backend::resolveSource);
      if (!resolved.identity.sameDataSource(after.identity)) {
        phase = com.deepseekharness.app.plugins.PluginOperationQueue.Phase.UNCONFIRMED;
        throw new IOException("执行期间数据根或环境变化；结果待核验，未自动重放");
      }
      if (request.work.query()) {
        if (!resolved.identity.equals(after.identity)
            || !registry.completeQuery(query, items, pendingReviews, safeMode)) {
          if (resolved.identity.equals(registry.snapshot().source))
            registry.updateSource(after.identity);
          throw new IOException("插件列表来源已变化，旧查询结果已丢弃");
        }
      } else if (request.catalogReceiptHandled) {
        if (!request.catalogReceiptRecorded) throw new IOException("操作回执已确认，当前目录来源已变化，需重新核对显示结果");
      } else if (request.work.includesItems()) {
        if (!registry.recordConfirmedItems(after.identity, items))
          throw new IOException("操作回执已确认，当前目录来源已变化，需重新核对显示结果");
      }
      if (request.work.query()) changedTargets.clear();
      adoptCatalog();
      success = true;
      phase = com.deepseekharness.app.plugins.PluginOperationQueue.Phase.SUCCEEDED;
    } catch (Exception error) {
      message = failureMessage(error);
      if (receiptConfirmed) {
        registry.failSource(message);
        success = true;
        phase = com.deepseekharness.app.plugins.PluginOperationQueue.Phase.SUCCEEDED;
        message = confirmedMessage + "；后续目录核对未完成，操作回执已保留";
      } else if (message.contains("UNKNOWN")
          || message.contains("UNCONFIRMED")
          || message.contains("未确认")
          || message.contains("待核验")
          || (!request.work.query()
              && request.dispatched
              && !(error instanceof CancelConfirmed)
              && (request.cancelled.get()
                  || message.contains("TIMEOUT")
                  || message.contains("INTERRUPTED")))) {
        phase = com.deepseekharness.app.plugins.PluginOperationQueue.Phase.UNCONFIRMED;
        message = "插件操作结果待核验；未自动重放：" + message;
      } else if (error instanceof Invalidated) {
        phase = com.deepseekharness.app.plugins.PluginOperationQueue.Phase.INVALIDATED;
      } else if (request.cancelled.get()) {
        phase = com.deepseekharness.app.plugins.PluginOperationQueue.Phase.CANCELLED;
      }
      if (query != null) registry.failQuery(query, message);
      else if (request.work.query()) registry.failSource(message);
      adoptCatalog();
    } finally {
      if (poll != null) poll.cancel(false);
    }
    // 信号读取、取消写入和结束清理均在后台；清理先于释放凭据。
    try {
      if (task != null) task.close();
    } catch (RuntimeException cleanupFailed) {
      publish(
          "插件清理未确认，保留环境占用：" + SensitiveData.redact(String.valueOf(cleanupFailed)),
          false,
          -1,
          State.RESULT_FAILURE);
      return;
    }
    if (runtimeWork != null) runtimeWork.close();
    if (lease != null) lease.close();
    activeTask = null;
    activeWork = null;
    queryRunning = false;
    operations.complete(request.id, phase, message);
    DiagnosticLog.record(getApplication(), "PLUGIN_RESULT", message);
    boolean completed = success;
    int result =
        completed
            ? State.RESULT_SUCCESS
            : phase == com.deepseekharness.app.plugins.PluginOperationQueue.Phase.CANCELLED
                ? State.RESULT_NONE
                : State.RESULT_FAILURE;
    String resultMessage = message;
    main.post(
        () -> {
          working.set(false);
          publish(resultMessage, false, -1, result);
          if (completed && request.success != null) request.success.run();
          if (completed && request.committed && request.resumeWeb)
            pendingResume =
                new ResumeWeb(request.resumeGeneration, request.resumeCancellationRevision);
          if ((completed || request.dispatched)
              && !request.work.query()
              && request.work.changesCatalog()) invalidateInstalledState();
          Listener current = listener;
          if (request.work.query() && current != null) current.queryFinished();
          if (operations.hasPending() || pendingQuery != null) scheduleWorker();
          else resumeAfterQueue();
          if (runtimeProjectionPending.getAndSet(false)) refreshRuntimeProjection();
        });
  }

  /** 批次结束后才恢复 Web；内部启动不能抢占后继已接收任务的执行凭据。 */
  private void resumeAfterQueue() {
    ResumeWeb resume = pendingResume;
    pendingResume = null;
    if (resume == null) return;
    HarnessController controller = HarnessController.get(getApplication());
    HarnessSessionState session =
        com.deepseekharness.app.DshaApp.from(getApplication()).harnessSession();
    synchronized (session.lifecycle) {
      if (resume.matches(controller, session)
          && !controller.isStarting()
          && !controller.isStopping()) {
        if (!controller.startWeb(ignored -> {}))
          publish("插件已安装并启用；Web 未自动重启，请在启动页重试", false, -1, State.RESULT_SUCCESS);
      }
    }
  }

  private String environmentBlockOnWorker(HarnessController controller) {
    if (MaintenanceCoordinator.pending(controller)) return "上次环境维护未完成，请到安装与修复页恢复维护，再刷新或操作插件";
    if (MaintenanceCoordinator.isEnvironmentTaskBusy()) return "正在执行环境任务，请等待完成后重试插件操作";
    if (controller.isStarting() || controller.isStopping()) return "Web 正在切换运行状态，请等待完成后重试插件操作";
    return "";
  }

  private void requireTarget(Item target) throws IOException {
    if (target.dynamic) throw new IOException("会话临时插件请在对应 Web 会话中管理");
    Item current = null;
    for (Item candidate : items)
      if (candidate.stableKey.equals(target.stableKey)) current = candidate;
    if (current == null
        || !current.version.equals(target.version)
        || !current.origin.equals(target.origin))
      throw new Invalidated("插件目标或版本已变化，本次任务未执行，请核对后重新提交");
  }

  private void poll(PendingWork request, PluginTask task, RuntimeTasks runtimeWork) {
    if (activeWork != request || activeTask != task) return;
    JSONObject detail = task.read();
    long total = detail.optLong("total"), current = detail.optLong("current");
    int percent = total > 0 ? (int) Math.min(100, current * 100 / total) : -1;
    String message =
        request.cancelled.get()
            ? "正在取消，请等待当前提交完成或临时文件清理…"
            : detail.optString("message", request.progress);
    runtimeWork.describe(message);
    String stage = detail.optString("stage");
    var phase =
        stage.contains("commit")
            ? com.deepseekharness.app.plugins.PluginOperationQueue.Phase.COMMITTING
            : com.deepseekharness.app.plugins.PluginOperationQueue.Phase.RUNNING;
    operations.progress(request.id, phase, message, percent, null);
    main.post(
        () -> {
          if (activeWork != request || activeTask != task) return;
          publish(
              message,
              request.cancellable
                  && !request.cancelled.get()
                  && (request.work.query() || detail.optBoolean("cancellable", true)),
              percent);
        });
  }

  public void cancelRefresh() {
    synchronized (this) {
      if (pendingQuery != null) pendingQuery.cancelled.set(true);
    }
    PendingWork request = activeWork;
    if (request != null && request.work.query()) cancelRequest(request);
  }

  public void cancelTask() {
    PendingWork request = activeWork;
    if (request == null)
      synchronized (this) {
        request = pendingQuery;
      }
    if (request == null) {
      var waiting = operations.current();
      if (waiting != null) cancelOperation(waiting.operationId);
      return;
    }
    cancelRequest(request);
  }

  private void cancelRequest(PendingWork request) {
    if (!request.cancellable) return;
    request.cancelled.set(true);
    PluginTask task = activeTask;
    if (task == null || activeWork != request) return;
    SIGNALS.execute(
        () -> {
          if (activeWork != request || activeTask != task) return;
          try {
            task.cancel();
          } catch (IOException error) {
            if (activeWork == request) publish(error.getMessage(), false, -1, State.RESULT_FAILURE);
          }
        });
  }

  public boolean cancelOperation(String id) {
    if (operations.cancelQueued(id)) {
      publish("已取消排队中的插件任务", false, -1);
      return true;
    }
    PendingWork request = activeWork;
    if (request == null || !request.id.equals(id)) return false;
    cancelRequest(request);
    return request.cancelled.get();
  }

  private String runManager(ProotBootstrap proot, String args) throws IOException {
    PluginTask task = activeTask;
    if (activeWork != null && activeWork.cancelled.get()) throw new IOException("已取消");
    if (task != null) task.check();
    try {
      if (activeWork != null) activeWork.dispatched = true;
      return com.deepseekharness.app.util.GuestCommandOutcome.requireCompleted(
          proot.runPluginManagerResult(
              args,
              task == null ? "" : task.id,
              new ConfigStore(getApplication()).getPluginDownloadSource()),
          "PLUGIN_MANAGER");
    } catch (InterruptedException interrupted) {
      Thread.currentThread().interrupt();
      throw new java.io.InterruptedIOException("PLUGIN_MANAGER_INTERRUPTED");
    }
  }

  public void refresh() {
    submit("正在检测 Web、终端和本地安装的插件…", withItems("refresh", true), null, true);
  }

  /** 前台/空闲补偿触发的低优先级查询；用户动作可以让路取消。 */
  public void refreshAutomatic() {
    submit("正在后台检测已安装插件…", withItems("refresh", true), null, true, null, true);
  }

  public boolean isQueryRunning() {
    return queryRunning;
  }

  public boolean hasPendingAutomaticQuery() {
    synchronized (this) {
      return pendingQuery != null && pendingQuery.automatic;
    }
  }

  /** 取消尚未执行的自动查询；不触碰用户显式刷新或正在提交的插件修改。 */
  public boolean cancelAutomaticQueryForUserAction() {
    boolean cancelled = false;
    synchronized (this) {
      PendingWork queued = pendingQuery;
      if (queued != null && queued.automatic) {
        queued.cancelled.set(true);
        cancelled = true;
      }
    }
    PendingWork active = activeWork;
    if (active != null && active.automatic && active.work.query()) {
      cancelRequest(active);
      cancelled = true;
    }
    return cancelled;
  }

  public String install(PluginSource source) {
    return inspect(source, "", "", "");
  }

  public String inspect(PluginSource source, String sha256, String name, String version) {
    return submit(
        Type.INSTALL,
        null,
        null,
        "正在下载并解析：" + source.description(),
        proot -> {
          JSONObject request =
              new JSONObject()
                  .put("command", source.command())
                  .put("sha256", sha256)
                  .put("name", name)
                  .put("version", version);
          return receivePreview(
              result(runManager(proot, "inspect " + ShellQuote.arg(request.toString()))));
        },
        null,
        true,
        () -> installationSucceeded = false,
        inputIdentity(source.command()),
        version);
  }

  private static String inputIdentity(String input) {
    try {
      byte[] digest =
          java.security.MessageDigest.getInstance("SHA-256")
              .digest(input.getBytes(java.nio.charset.StandardCharsets.UTF_8));
      StringBuilder key = new StringBuilder("input:");
      for (byte value : digest)
        key.append(String.format(java.util.Locale.ROOT, "%02x", value & 255));
      return key.toString();
    } catch (java.security.NoSuchAlgorithmException impossible) {
      throw new AssertionError(impossible);
    }
  }

  private String receivePreview(JSONObject output) throws Exception {
    if (!"ok".equals(output.optString("status")))
      throw new IOException(output.optString("message"));
    if ((activeWork != null && activeWork.cancelled.get())
        || (activeTask != null && activeTask.requested())) {
      Preview candidate = new Preview(output.getJSONObject("preview"), registry.snapshot().source);
      try {
        if (candidate.source == null
            || !candidate.source.sameDataSource(backend.resolveSource().identity))
          throw new IOException("插件候选来源已变化，未清理其他数据根的候选");
        discardPreviewConfirmed(HarnessController.get(getApplication()).proot(), candidate.id);
      } catch (Exception failure) {
        preview.postValue(candidate);
        throw new IOException(
            "已请求取消，但临时包清理未确认；预览原件仍可重试清理："
                + SensitiveData.redact(String.valueOf(failure.getMessage())),
            failure);
      }
      throw new CancelConfirmed("已取消解析并清理临时包");
    }
    Preview candidate = new Preview(output.getJSONObject("preview"), registry.snapshot().source);
    PendingWork request = activeWork;
    if (request == null) throw new IOException("PLUGIN_OPERATION_ID_REQUIRED");
    request.candidate = candidate;
    rememberPreview(request.id, candidate);
    preparedPreview = candidate;
    preview.postValue(candidate);
    return commitCandidate(HarnessController.get(getApplication()), candidate, request);
  }

  /** 解析和提交属于同一个 operationId；下一项不能抢换候选。 */
  private String commitCandidate(
      HarnessController controller, Preview selected, PendingWork request) throws Exception {
    ProotBootstrap proot = controller.proot();
    var before = backend.resolveSource().identity;
    if (selected.source != null && !selected.source.sameDataSource(before))
      throw new IOException("插件候选所属环境已变化；原件保留，请重新解析");
    long generation = controller.getWebGeneration();
    HarnessSessionState session =
        com.deepseekharness.app.DshaApp.from(getApplication()).harnessSession();
    long cancellationRevision = session.startProtection.cancellationRevision();
    ResumeWeb previousResume = pendingResume;
    boolean resumeWeb =
        (!controller.getWebAuthUrl().isEmpty() && !controller.startupDiagnostics().snapshot().safe)
            || (previousResume != null && previousResume.matches(controller, session));
    operations.progress(
        request.id,
        com.deepseekharness.app.plugins.PluginOperationQueue.Phase.COMMITTING,
        "正在提交插件并启用…",
        -1,
        selected.id);
    String message;
    try (RuntimeTasks synchronous = RuntimeTasks.begin()) {
      request.runtimeWork.close();
      message =
          MaintenanceCoordinator.exclusive(
              controller,
              () -> {
                if (!before.sameDataSource(backend.resolveSource().identity))
                  throw new IOException("插件提交前环境已变化；候选保留，未执行旧请求");
                JSONArray packages = selected.data.getJSONArray("items");
                for (int i = 0; i < packages.length(); i++)
                  changedTargets.add(new Item(packages.getJSONObject(i)).stableKey);
                JSONObject result =
                    result(
                        runManager(
                            proot,
                            "install-preview "
                                + ShellQuote.arg(selected.id)
                                + " "
                                + ShellQuote.arg(selected.confirmation)));
                String confirmed = operationMessage(result);
                request.committed = true;
                installationSucceeded = true;
                installedPreview = selected;
                return confirmed;
              });
    }
    // 内部维护只应增加一次停止意图；重复用户 Stop 也会使 revision 不匹配。
    request.resumeWeb = resumeWeb;
    request.resumeGeneration = generation + 1;
    request.resumeCancellationRevision = cancellationRevision + 1;
    preparedPreview = null;
    preview.postValue(null);
    return message;
  }

  public String confirmPreview() {
    Preview selected = preparedPreview != null ? preparedPreview : preview.getValue();
    if (selected == null) return "";
    return submit(
        com.deepseekharness.app.plugins.PluginOperationQueue.Type.CONFIRM_PREVIEW,
        null,
        null,
        "正在核验并提交插件候选…",
        proot -> commitCandidate(HarnessController.get(getApplication()), selected, activeWork),
        null,
        true,
        () -> installationSucceeded = false);
  }

  public String discardPreview() {
    Preview old = preview.getValue();
    if (old == null) return "";
    return submit(
        Type.DISCARD_PREVIEW,
        null,
        null,
        "正在清理插件安装预览…",
        proot -> {
          discardPreviewConfirmed(proot, old.id);
          return "插件安装预览已取消";
        },
        () -> {
          if (preparedPreview == old) preparedPreview = null;
          if (preview.getValue() == old) preview.setValue(null);
        },
        false);
  }

  private static void discardPreviewConfirmed(ProotBootstrap proot, String id) throws Exception {
    requireDiscardResult(proot.runPluginManagerResult("discard-preview " + ShellQuote.arg(id), ""));
  }

  static void requireDiscardResult(
      com.deepseekharness.app.util.BoundedProcessRunner.Result execution) throws Exception {
    String text =
        com.deepseekharness.app.util.GuestCommandOutcome.requireCompleted(
            execution, "PLUGIN_DISCARD_PREVIEW");
    com.deepseekharness.app.util.PluginOutput.requireDiscardSuccess(text);
  }

  public String checkUpdates(Item item) {
    return submit(
        Type.CHECK_UPDATES,
        item,
        null,
        "正在检查插件版本…",
        proot ->
            operationMessage(
                result(
                    runManager(
                        proot,
                        "check-updates" + (item == null ? "" : " " + ShellQuote.arg(item.name))))));
  }

  public String prepareUpdate(Item item) {
    if (!item.updateAvailable) return "";
    return submit(
        Type.UPDATE,
        item,
        null,
        "正在下载并核对更新包…",
        proot -> {
          return receivePreview(
              result(runManager(proot, "prepare-update " + ShellQuote.arg(item.name))));
        },
        null,
        true);
  }

  public String rollback(Item item) {
    return submit(
        Type.ROLLBACK,
        item,
        null,
        "正在回退 " + item.name + "…",
        proot ->
            receivePreview(
                result(
                    runManager(
                        proot,
                        "rollback "
                            + ShellQuote.arg(item.name)
                            + " "
                            + ShellQuote.arg(item.rollbackVersion)))),
        null);
  }

  public String safeMode(boolean enable, Runnable afterSuccess) {
    return submit(
        Type.SAFE_MODE,
        null,
        enable,
        enable ? "正在暂时停用第三方插件…" : "正在恢复此前启用的第三方插件…",
        proot -> {
          JSONObject output = result(runManager(proot, "safe-mode " + (enable ? "on" : "off")));
          if (!"ok".equals(output.optString("status")))
            throw new IOException(output.optString("message"));
          return output.getString("message");
        },
        afterSuccess);
  }

  public String importArchive(Uri uri) {
    return submit(
        Type.IMPORT,
        null,
        null,
        com.deepseekharness.app.util.UiText.choose("正在解析本地插件包…", "Resolving local plugin package…"),
        proot -> {
          File temporary =
              File.createTempFile("plugin-import-", ".bin", getApplication().getCacheDir());
          String container = "/root/.dsh/plugin-upload-" + UUID.randomUUID() + ".bin";
          try {
            try (InputStream in = getApplication().getContentResolver().openInputStream(uri);
                OutputStream out = new FileOutputStream(temporary)) {
              copy(in, out);
            }
            if (!proot.pushFileIntoContainer(temporary, container))
              throw new IOException("插件包写入容器失败");
            JSONObject request =
                new JSONObject().put("command", "file " + ShellQuote.arg(container));
            return receivePreview(
                result(runManager(proot, "inspect " + ShellQuote.arg(request.toString()))));
          } finally {
            temporary.delete();
            cleanup(proot, container);
          }
        },
        null,
        true,
        () -> installationSucceeded = false,
        inputIdentity(uri.toString()),
        "");
  }

  public String exportArchives(List<String> names, Uri uri) {
    List<String> selectedNames = List.copyOf(names);
    return submit(
        Type.EXPORT,
        null,
        null,
        "正在打包并保存插件…",
        withoutCatalogChange(
            proot -> {
              File temporary =
                  File.createTempFile("plugin-export-", ".tar.gz", getApplication().getCacheDir());
              String container = "/root/.dsh/plugin-export-" + UUID.randomUUID() + ".tar.gz";
              boolean saved = false;
              try {
                JSONObject output =
                    result(
                        runManager(
                            proot,
                            "export "
                                + ShellQuote.arg(new JSONArray(selectedNames).toString())
                                + " "
                                + ShellQuote.arg(container)));
                if (!"ok".equals(output.optString("status")))
                  throw new IOException(output.optString("message"));
                if (!proot.pullFileFromContainer(container, temporary))
                  throw new IOException("插件包取回失败");
                try (InputStream in = new FileInputStream(temporary);
                    OutputStream out =
                        getApplication().getContentResolver().openOutputStream(uri, "wt")) {
                  copy(in, out);
                }
                saved = true;
                return "已将 " + selectedNames.size() + " 个插件导出到所选位置，可在另一台 DSHA 中导入";
              } finally {
                temporary.delete();
                cleanup(proot, container);
                if (!saved) {
                  try {
                    DocumentsContract.deleteDocument(getApplication().getContentResolver(), uri);
                  } catch (Exception ignored) {
                  }
                }
              }
            }));
  }

  public String setEnabled(Item item, boolean enable) {
    return submit(
        com.deepseekharness.app.plugins.PluginOperationQueue.Type.SET_ENABLED,
        item,
        enable,
        "正在" + (enable ? "启用 " : "禁用 ") + item.name,
        new Work() {
          @Override
          public boolean includesItems() {
            return true;
          }

          @Override
          public String run(ProotBootstrap proot) throws Exception {
            JSONObject output =
                result(
                    runManager(
                        proot,
                        (enable ? "enable-state " : "disable-state ") + ShellQuote.arg(item.name)));
            requireIntentReceipt(output, item.name, enable);
            recordTargetReceipt(item, enable);
            return output.getString("message");
          }
        },
        null,
        false,
        null);
  }

  /** 回执先落入展示快照；其后的只读失败不能撤销已确认的启停或删除。 */
  private void recordTargetReceipt(Item target, Boolean enabled) throws Exception {
    PendingWork request = activeWork;
    if (request == null || request.executionSource == null)
      throw new IOException("PLUGIN_RECEIPT_WITHOUT_EXECUTION");
    request.catalogReceiptHandled = true;
    synchronized (registry) {
      var catalog = registry.snapshot();
      if (!request.executionSource.sameDataSource(catalog.source)) return;
      // 只 Web 换代仍可保存配置事实，但先退休旧进程的运行证据。
      registry.updateWebGeneration(HarnessController.get(getApplication()).getWebGeneration());
      catalog = registry.snapshot();
      List<Item> next = new ArrayList<>();
      boolean found = false;
      for (Item existing : catalog.items) {
        if (!existing.dynamic && existing.stableKey.equals(target.stableKey)) {
          if (found
              || !existing.version.equals(target.version)
              || !existing.origin.equals(target.origin)) return;
          found = true;
          if (enabled != null) next.add(existing.withIntent(enabled));
        } else next.add(existing);
      }
      if (!found) return;
      request.catalogReceiptRecorded = registry.recordConfirmedItems(catalog.source, next);
      if (request.catalogReceiptRecorded) adoptCatalog();
    }
  }

  static void requireIntentReceipt(JSONObject output, String name, boolean enable)
      throws Exception {
    if (!"ok".equals(output.optString("status"))
        || !name.equals(output.optString("name"))
        || !(output.opt("enabled") instanceof Boolean)
        || output.getBoolean("enabled") != enable
        || !Boolean.TRUE.equals(output.opt("intentVerified")))
      throw new IOException("插件启停结果未确认；未自动重试，请刷新核对实际状态");
  }

  public String delete(Item item) {
    if (item.dynamic) {
      selectionMessage(
          com.deepseekharness.app.util.UiText.text("临时插件请在对应 Web 会话的 Cordis 插件管理中删除。"));
      return "";
    }
    if (!item.deletable) return "";
    return submit(
        Type.DELETE,
        item,
        null,
        "正在删除 " + item.name + "…",
        new Work() {
          @Override
          public boolean includesItems() {
            return true;
          }

          @Override
          public String run(ProotBootstrap proot) throws Exception {
            JSONObject output =
                result(
                    runManager(
                        proot,
                        "delete "
                            + ShellQuote.arg(item.name)
                            + " "
                            + ShellQuote.arg(item.version)));
            requireDeletionReceipt(output, item.name);
            recordTargetReceipt(item, null);
            return output.getString("message");
          }
        },
        null,
        true,
        null);
  }

  static void requireDeletionReceipt(JSONObject output, String name) throws Exception {
    if ("PLUGIN_TARGET_VERSION_CHANGED".equals(output.optString("message")))
      throw new Invalidated("插件目标版本已变化，旧版本删除请求未执行");
    if (!"ok".equals(output.optString("status"))
        || !name.equals(output.optString("name"))
        || !Boolean.TRUE.equals(output.opt("removed"))
        || !Boolean.TRUE.equals(output.opt("deletionVerified")))
      throw new IOException("插件删除结果未确认；未自动重试，请刷新核对实际状态");
  }

  private List<Item> readItems(ProotBootstrap proot, JSONObject output) throws Exception {
    if (!"ok".equals(output.optString("status")))
      throw new IOException(output.optString("message"));
    JSONArray array = output.getJSONArray("items");
    List<Item> next = new ArrayList<>();
    long generation = HarnessController.get(getApplication()).getWebGeneration();
    java.util.Map<String, Item> previous = new java.util.HashMap<>();
    for (Item item : registry.snapshot().items) previous.put(item.stableKey, item);
    for (int i = 0; i < array.length(); i++) {
      JSONObject value = new JSONObject(array.getJSONObject(i).toString());
      Item item = new Item(value);
      Item old = previous.get(item.stableKey);
      boolean restart =
          "queued".equals(item.loadState)
              || (old != null
                  && old.restartRequired
                  && old.runtimeGeneration == generation
                  && old.version.equals(item.version));
      item = new Item(value.put("restartRequired", restart));
      item =
          com.deepseekharness.app.plugins.PluginRuntimeProjection.retainRuntime(
              item, old, generation);
      if (!com.deepseekharness.app.util.BuiltinPlugins.internal(item.name)) next.add(item);
    }
    safeMode = output.optBoolean("safeMode");
    List<PendingReview> pending = new ArrayList<>();
    JSONArray stored = output.optJSONArray("pendingReviews");
    if (stored != null)
      for (int i = 0; i < Math.min(128, stored.length()); i++)
        pending.add(new PendingReview(stored.getJSONObject(i)));
    pendingReviews = Collections.unmodifiableList(pending);
    for (Item old : registry.snapshot().items)
      if (old.dynamic && old.runtimeGeneration == generation) next.add(old);
    return projectRuntime(proot, next);
  }

  private List<Item> projectRuntime(ProotBootstrap proot, List<Item> persistent) throws Exception {
    HarnessController controller = HarnessController.get(getApplication());
    long generation = controller.getWebGeneration();
    var identity = controller.getReadyWebPageIdentity();
    long now = System.currentTimeMillis();
    String rejection = "";
    JSONObject snapshot = null;
    if (identity == null) {
      rejection = "IDENTITY_MISSING";
    } else if (identity.generation() != generation) {
      rejection = "IDENTITY_GENERATION";
    } else {
      try {
        var fs = new com.deepseekharness.app.backup.AndroidBackupFileSystem();
        // 写方每 2 秒用同目录 rename 发布新快照；读取只要求打开描述符内字节一致，
        // 不把路径在读取期间换 inode 当成失败，否则会和正常心跳长期竞争。
        // 运行根先规范化：Android 的 /data/user/0 是 /data/data 的系统别名，NOFOLLOW
        // 逐段检查会把合法别名判成 PARENT_LINK；最终快照文件仍由读取器 NOFOLLOW 打开。
        snapshot =
            new JSONObject(
                new String(
                    fs.currentSnapshot(
                        com.deepseekharness.app.plugins.PluginRuntimeProjection.snapshotPath(
                            proot.getRootfsDir()),
                        256 * 1024),
                    java.nio.charset.StandardCharsets.UTF_8));
        rejection =
            com.deepseekharness.app.plugins.PluginRuntimeProjection.rejection(
                snapshot, generation, identity.instanceId(), now);
      } catch (Exception error) {
        // 同代暂不可读时保留上次证据并标待核验，不把未知替换成空目录。
        rejection =
            "READ="
                + com.deepseekharness.app.util.SensitiveData.redact(
                    String.valueOf(error.getMessage()));
      }
    }
    List<Item> projected =
        com.deepseekharness.app.plugins.PluginRuntimeProjection.apply(
            persistent, snapshot, generation, identity == null ? "" : identity.instanceId(), now);
    if (generation != controller.getWebGeneration()
        || !java.util.Objects.equals(identity, controller.getReadyWebPageIdentity()))
      throw new IOException("PLUGIN_RUNTIME_CHANGED");
    noticeRuntime(projected, rejection);
    return projected;
  }

  /** 只在结论变化时写活动日志；每轮查询重复同一原因不会刷屏。 */
  private void noticeRuntime(List<Item> projected, String rejection) {
    int total = 0, unconfirmed = 0;
    for (Item item : projected)
      if (!item.dynamic) {
        total++;
        if ("unknown".equals(item.runtimeState)) unconfirmed++;
      }
    String notice =
        rejection.isEmpty()
            ? (unconfirmed == 0 ? "" : "快照已采纳，仍有 " + unconfirmed + "/" + total + " 个未确认")
            : "快照未采纳："
                + rejection
                + (unconfirmed == 0 ? "" : "（" + unconfirmed + "/" + total + " 未确认）");
    if (notice.equals(lastRuntimeNotice)) return;
    lastRuntimeNotice = notice;
    DiagnosticLog.record(
        getApplication(), "PLUGIN_RUNTIME", notice.isEmpty() ? "当前 Web 加载证据已全部确认" : notice);
  }

  /** READY 探测只有只读原生检查，不运行任何插件脚本。 */
  public com.deepseekharness.app.plugins.PluginBackendAdapter.ResolvedSource probeSource()
      throws IOException {
    HarnessController controller = HarnessController.get(getApplication());
    String blocked = environmentBlockOnWorker(controller);
    lastEnvironmentNotice = blocked;
    if (!blocked.isEmpty() || !controller.proot().isEnvironmentReady()) return null;
    com.deepseekharness.app.util.EnvironmentTaskGate.Lease lease =
        com.deepseekharness.app.util.EnvironmentTaskGate.tryAcquireShared("插件来源核对");
    if (lease == null) return null;
    try (lease) {
      return backend.resolveSource();
    }
  }

  /** 活动心跳只投影已有运行摘要，不触发完整列表或 guest 启动。 */
  public void refreshRuntimeProjection() {
    if (!registry.snapshot().known) return;
    if (working.get()) {
      runtimeProjectionPending.set(true);
      markRuntimeUnverified();
      return;
    }
    if (!runtimeProjectionQueued.compareAndSet(false, true)) return;
    io.execute(
        () -> {
          try {
            if (working.get()) {
              runtimeProjectionPending.set(true);
              markRuntimeUnverified();
              return;
            }
            var lease =
                com.deepseekharness.app.util.EnvironmentTaskGate.tryAcquireShared("插件运行状态读取");
            if (lease == null) {
              markRuntimeUnverified();
              return;
            }
            try (lease) {
              lease.run(
                  () -> {
                    var before = backend.resolveSource().identity;
                    if (!before.equals(registry.snapshot().source)) return null;
                    List<Item> projected =
                        projectRuntime(
                            HarnessController.get(getApplication()).proot(),
                            registry.snapshot().items);
                    if (before.equals(backend.resolveSource().identity)
                        && registry.updateRuntimeItems(before, projected)) {
                      adoptCatalog();
                      publishCatalogState();
                    }
                    return null;
                  });
            }
          } catch (Exception ignored) {
            markRuntimeUnverified();
          } finally {
            runtimeProjectionQueued.set(false);
          }
        });
  }

  private void markRuntimeUnverified() {
    var catalog = registry.snapshot();
    if (catalog.source == null) return;
    try {
      List<Item> pending = new ArrayList<>();
      for (Item item : catalog.items)
        pending.add(item.withRuntime(item.runtimeState, item.runtimeGeneration, true));
      if (registry.updateRuntimeItems(catalog.source, pending)) publishCatalogState();
    } catch (Exception ignored) {
      // 不可读不会产生当前加载成功的证据，保留下一次生命周期/心跳补偿。
    }
  }

  private static JSONObject result(String output) throws Exception {
    String text = output == null ? "" : output.trim();
    String result = com.deepseekharness.app.util.PluginOutput.resultJson(text);
    if (result.isEmpty()) throw new IOException(shortError(text));
    JSONObject json = new JSONObject(result);
    if (!json.has("status") || !json.has("message")) throw new IOException("插件操作返回了不完整的结果");
    // error/partial 也保留脚本给出的完整原因；不能靠输出中出现一次 OK 判断整个操作成功。
    json.put("message", SensitiveData.redact(json.getString("message")));
    return json;
  }

  private static String operationMessage(JSONObject result) throws Exception {
    String status = result.getString("status");
    String message =
        ("partial".equals(status) ? "部分完成：\n" : "error".equals(status) ? "安装失败：\n" : "")
            + result.getString("message");
    if (!"ok".equals(status)) throw new IOException(message);
    return message;
  }

  private static String shortError(String text) {
    if (text == null || text.isEmpty()) return "没有收到插件管理结果";
    if (text.equals("ENV_NOT_READY")) return "环境未就绪，请先完成解压 / 安装";
    String safe = SensitiveData.redact(text.trim());
    return safe.length() <= 800 ? safe : safe.substring(safe.length() - 800);
  }

  private static String failureMessage(Exception error) {
    String detail = String.valueOf(error.getMessage());
    Throwable cause = error.getCause();
    for (int depth = 0; cause != null && depth < 8; depth++, cause = cause.getCause()) {
      String nested = String.valueOf(cause.getMessage());
      if (nested.equals("PLUGIN_QUERY_CANCELLED")
          || nested.equals("PLUGIN_QUERY_TIMEOUT")
          || nested.equals("PLUGIN_INTENT_TIMEOUT")
          || nested.equals("PLUGIN_INTENT_CANCELLED")) {
        detail = nested;
        break;
      }
    }
    if ("PLUGIN_QUERY_CANCELLED".equals(detail)) return "已取消插件列表读取";
    if ("PLUGIN_QUERY_TIMEOUT".equals(detail)) return "插件列表读取已超时，保留上次结果；请刷新重试";
    if ("PLUGIN_INTENT_TIMEOUT".equals(detail) || "PLUGIN_INTENT_CANCELLED".equals(detail))
      return "插件启停结果未确认；未自动重试，请刷新核对实际状态";
    if ("PREPARATION_REQUIRED".equals(detail) || "ENV_PREPARATION_REQUIRED".equals(detail))
      return "运行工具或资产不完整，请先到「安装与修复」完成环境准备；上次插件列表已保留";
    return "操作失败：" + SensitiveData.redact(detail);
  }

  private void copy(InputStream in, OutputStream out) throws IOException {
    if (in == null || out == null) throw new IOException("无法打开所选文件，请重新选择位置");
    long size = 0;
    byte[] bytes = new byte[65536];
    int count;
    while ((count = in.read(bytes)) != -1) {
      if (activeWork != null && activeWork.cancelled.get()) throw new IOException("已取消");
      if (activeTask != null) activeTask.check();
      size += count;
      if (size > MAX_ARCHIVE) throw new IOException("插件包不能超过 256 MiB");
      out.write(bytes, 0, count);
    }
    if (size == 0) throw new IOException("插件包为空");
  }

  private static void cleanup(ProotBootstrap proot, String path) {
    // 只清理由本类生成的单个暂存文件，不递归清理插件目录。
    new File(proot.getRootfsDir(), path.substring(1)).delete();
  }
}
