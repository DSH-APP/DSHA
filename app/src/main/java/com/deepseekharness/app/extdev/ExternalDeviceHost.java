package com.deepseekharness.app.extdev;

import android.content.Context;
import android.content.SharedPreferences;
import com.deepseekharness.app.RootShell;
import com.deepseekharness.app.core.HarnessController;
import com.deepseekharness.app.util.ExternalDevicePolicy;
import com.deepseekharness.app.util.ExternalFastbootFacts;
import com.deepseekharness.app.util.ExternalFocusEvents;
import com.deepseekharness.app.util.ExternalImageProof;
import com.deepseekharness.app.util.ExternalImportQuota;
import com.deepseekharness.app.util.ExternalInstallPlan;
import com.deepseekharness.app.util.ExternalRateLimit;
import com.deepseekharness.app.util.ExternalStepOutput;
import com.deepseekharness.app.util.ExternalUsbModes;
import com.deepseekharness.app.util.ExternalPreflight;
import com.deepseekharness.app.util.ExternalTargetBinding;
import com.deepseekharness.app.util.ExternalWatchPolicy;
import com.deepseekharness.app.util.SensitiveData;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.json.JSONArray;
import org.json.JSONObject;

/** Dedicated, opt-in host for one external Android target (USB or wireless adb). Never falls back to host shell. */
public final class ExternalDeviceHost {
  private static final String PREFS = "dsha_external_device_grants";
  private static final String ENABLED = "external_device_enabled_v1";
  private static final String SERIAL = "target_serial_v1";
  private static final String PRODUCT = "target_product_v1";
  private static final String FINGERPRINT = "target_fingerprint_v1";
  private static final String EPOCH = "target_epoch_v1";
  private static final long ADB_TIMEOUT_MS = 30_000;
  private static final long OBSERVE_TIMEOUT_MS = 30_000;

  /** 外接观察的进程内限速：每次 watch 会拉起一次专属 adb server，连续高频调用会占用 root helper 槽位。 */
  private static final ExternalRateLimit WATCH_RATE_LIMIT = new ExternalRateLimit(250);

  private static final java.util.concurrent.atomic.AtomicLong OBSERVE_SEQUENCE =
      new java.util.concurrent.atomic.AtomicLong();
  private static final Object IMPORT_LOCK = new Object();
  private static final Object TARGET_LOCK = new Object();
  private static Object targetRevision = new Object();
  private static boolean targetWriteUnconfirmed;
  private final Context context;
  private final int adbPort = ephemeralPort();
  private final SharedPreferences prefs;
  private final File importRoot;
  private final java.util.function.BooleanSupplier requestCurrent;
  private final Object observedRevision;
  private final long observedEpoch;
  private final ExternalTargetBinding observedTarget;

  public ExternalDeviceHost(Context context) {
    this(context, null);
  }

  /** A bridge operation captures one target before its confirmation dialog is shown. */
  public ExternalDeviceHost(Context context, java.util.function.BooleanSupplier requestCurrent) {
    this.context = context.getApplicationContext();
    prefs = this.context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    importRoot = new File(this.context.getFilesDir(), "external-device-import");
    this.requestCurrent = requestCurrent;
    synchronized (TARGET_LOCK) {
      observedRevision = targetRevision;
      observedEpoch = requestCurrent == null ? 0 : prefs.getLong(EPOCH, 0);
      observedTarget = requestCurrent == null ? null : binding();
    }
  }

  public ExternalTargetBinding confirmationTarget() {
    return observedTarget;
  }

  private boolean dispatchCurrent() {
    try {
      if (requestCurrent != null && !requestCurrent.getAsBoolean()) return false;
      synchronized (TARGET_LOCK) {
        return targetCurrentLocked();
      }
    } catch (RuntimeException unreadable) {
      return false;
    }
  }

  private boolean targetCurrentLocked() {
    if (!enabled()) return false;
    if (requestCurrent == null) return true;
    ExternalTargetBinding now = binding();
    return observedRevision == targetRevision
        && observedEpoch == prefs.getLong(EPOCH, 0)
        && (observedTarget == null
            ? now == null && !prefs.contains(SERIAL)
            : now != null
                && now.epoch == observedTarget.epoch
                && now.matches(
                    observedTarget.serial, observedTarget.product, observedTarget.fingerprint));
  }

  public boolean enabled() {
    synchronized (TARGET_LOCK) {
      return !targetWriteUnconfirmed && prefs.getBoolean(ENABLED, false);
    }
  }

  public boolean setEnabled(boolean value) {
    boolean saved;
    synchronized (TARGET_LOCK) {
      var edit = prefs.edit().putBoolean(ENABLED, value);
      if (targetWriteUnconfirmed) forgetBinding(edit);
      saved = commitTargetChange(edit);
    }
    // 关闭能力即清空一次性导入中转区；原件仍在 Download，清理失败不影响开关结果。
    if (saved && !value) {
      try {
        reclaimImports(true);
      } catch (IOException ignored) {
        // 下次导入前会按过期规则再次回收。
      }
    }
    return saved;
  }

  public ExternalTargetBinding binding() {
    synchronized (TARGET_LOCK) {
      if (targetWriteUnconfirmed || !prefs.contains(SERIAL)) return null;
      try {
        return new ExternalTargetBinding(
            prefs.getString(SERIAL, ""),
            prefs.getString(PRODUCT, ""),
            prefs.getString(FINGERPRINT, ""),
            prefs.getLong(EPOCH, 0));
      } catch (RuntimeException invalid) {
        return null;
      }
    }
  }

  public boolean forgetTarget() {
    synchronized (TARGET_LOCK) {
      var edit = prefs.edit();
      if (targetWriteUnconfirmed) edit.putBoolean(ENABLED, false);
      forgetBinding(edit);
      return commitTargetChange(edit);
    }
  }

  private void forgetBinding(SharedPreferences.Editor edit) {
    long old = prefs.getLong(EPOCH, 0);
    edit.remove(SERIAL)
        .remove(PRODUCT)
        .remove(FINGERPRINT)
        .putLong(EPOCH, old >= 0 && old < Long.MAX_VALUE ? old + 1 : Long.MAX_VALUE);
  }

  /** commit can publish new process-local preferences before it reports a disk failure. */
  private boolean commitTargetChange(SharedPreferences.Editor edit) {
    targetRevision = new Object();
    targetWriteUnconfirmed = true;
    try {
      boolean saved = edit.commit();
      targetWriteUnconfirmed = !saved;
      return saved;
    } catch (RuntimeException unavailable) {
      return false;
    }
  }

  /**
   * 导入中转目录。Android 16 的 getFilesDir() 可能返回 /data/user/0 别名（指向 /data/data 的系统链接）：祖先别名
   * 允许，只拒绝叶子自身是符号链接；返回 canonical，供后续 startsWith 比较与 root shell 使用。
   */
  public File importRoot() throws IOException {
    return com.deepseekharness.app.util.ExternalImportRoot.resolve(importRoot);
  }

  /** 列出导入槽位（NOFOLLOW 判定普通文件），all=true 回收全部，否则只回收过期条目；返回剩余条目。 */
  private List<ExternalImportQuota.Entry> reclaimImports(boolean all) throws IOException {
    synchronized (IMPORT_LOCK) {
      File root = importRoot();
      String[] names = root.list();
      List<ExternalImportQuota.Entry> entries = new ArrayList<>();
      if (names == null) throw new IOException("IMPORT_DIRECTORY");
      long now = System.currentTimeMillis();
      for (String name : names) {
        if (!ExternalImportQuota.isImportName(name)) continue;
        java.nio.file.Path path = new File(root, name).toPath();
        java.nio.file.attribute.BasicFileAttributes attributes =
            java.nio.file.Files.readAttributes(
                path,
                java.nio.file.attribute.BasicFileAttributes.class,
                java.nio.file.LinkOption.NOFOLLOW_LINKS);
        long modified = attributes.lastModifiedTime().toMillis();
        boolean regular = attributes.isRegularFile();
        // 只删本目录里的普通文件：链接 / 目录保持原样，由配额检查拒绝后续导入。
        if (regular && (all || ExternalImportQuota.expired(modified, now))) {
          java.nio.file.Files.deleteIfExists(path);
          continue;
        }
        entries.add(new ExternalImportQuota.Entry(name, attributes.size(), modified, regular));
      }
      return entries;
    }
  }

  /** Copies a user-selected image from shared Download into an App-private UUID slot. */
  public JSONObject importImage(String sourcePath) throws Exception {
    // 桥每次请求新建 Host 实例，配额检查与写入必须用类级锁串行，否则并发导入可同时通过配额。
    synchronized (IMPORT_LOCK) {
      return importImageLocked(sourcePath);
    }
  }

  private JSONObject importImageLocked(String sourcePath) throws Exception {
    if (!enabled()) throw new IOException("[DISABLED] 外接设备能力已关闭");
    if (sourcePath == null || sourcePath.isEmpty()) throw new IOException("IMPORT_SOURCE_MISSING");
    File source = new File(sourcePath).getCanonicalFile();
    File download =
        new File(android.os.Environment.getExternalStorageDirectory(), "Download")
            .getCanonicalFile();
    if (!source.isFile() || !source.getPath().startsWith(download.getPath() + File.separator))
      throw new IOException("IMPORT_SOURCE_MUST_BE_DOWNLOAD");
    if (source.length() <= 0 || source.length() > 4L * 1024 * 1024 * 1024)
      throw new IOException("IMPORT_SIZE");
    // 总配额：先回收过期副本，未过期的不为腾位置删除，放不下就拒绝。
    ExternalImportQuota.Plan quota =
        ExternalImportQuota.plan(
            reclaimImports(false), source.length(), System.currentTimeMillis());
    if (!quota.allowed()) throw new IOException(quota.rejection);
    String safeName = source.getName().replaceAll("[^A-Za-z0-9._-]", "_");
    if (safeName.isEmpty() || safeName.length() > 128) safeName = "image.bin";
    ExternalImageProof sourceBefore = ExternalImageProof.verify(source, download);
    File root = importRoot();
    File destination = new File(root, java.util.UUID.randomUUID().toString() + "-" + safeName);
    long sourceSize = source.length();
    try (java.io.InputStream input =
            new java.io.BufferedInputStream(new java.io.FileInputStream(source));
        java.io.OutputStream output =
            new java.io.BufferedOutputStream(new java.io.FileOutputStream(destination))) {
      com.deepseekharness.app.util.FileIntegrity.copy(
          input,
          output,
          4L * 1024 * 1024 * 1024,
          () -> {
            File current = source.getCanonicalFile();
            if (!current.equals(source) || current.length() != sourceSize)
              throw new IOException("IMPORT_SOURCE_CHANGED");
          });
    } catch (Throwable error) {
      // The destination is disposable and never becomes a user-visible backup artifact.
      destination.delete();
      throw error;
    }
    ExternalImageProof sourceAfter = ExternalImageProof.verify(source, download);
    if (sourceBefore.size != sourceAfter.size || !sourceBefore.sha256.equals(sourceAfter.sha256)) {
      destination.delete();
      throw new IOException("IMPORT_SOURCE_CHANGED");
    }
    ExternalImageProof proof = ExternalImageProof.verify(destination, root);
    return new JSONObject()
        .put("file", destination.getName())
        .put("name", proof.name)
        .put("size", proof.size)
        .put("sha256", proof.sha256)
        .put("imageType", proof.type);
  }

  public File importedFile(String name) throws IOException {
    if (name == null || !name.matches("[0-9a-f-]{36}-[A-Za-z0-9._-]{1,128}"))
      throw new IOException("IMPORT_FILE_NAME");
    File root = importRoot();
    File file = new File(root, name).getCanonicalFile();
    if (!file.getPath().startsWith(root.getPath() + File.separator) || !file.isFile())
      throw new IOException("IMPORT_FILE_MISSING");
    return file;
  }

  public JSONObject status() throws Exception {
    JSONObject result = new JSONObject();
    boolean active = enabled();
    result.put("enabled", active);
    result.put("rootEnabled", RootShell.enabled(context));
    result.put("rootPresent", active && RootShell.present());
    result.put("rootVerified", active && rootVerified());
    result.put(
        "transport", binding() != null && binding().wireless() ? "wireless-root" : "usb-root");
    result.put("flashExecution", "disabled-tool-proof-required");
    ExternalTargetBinding binding = binding();
    result.put("bound", binding != null);
    if (binding != null) {
      result.put("serial", binding.serial);
      result.put("product", binding.product);
      result.put("fingerprint", binding.fingerprint);
      result.put("epoch", binding.epoch);
    }
    return result;
  }

  /**
   * USB 枚举（UsbManager，无需 root，可认出 9008 / BROM 等非 adb 模式）+ 本次 adb 会话可见设备。adb 不可用时只返回
   * USB 列表与原因，不抛出。
   */
  public JSONObject discover() throws Exception {
    if (!enabled()) throw new IOException("[DISABLED] 外接设备能力已关闭");
    JSONObject result = new JSONObject();
    result.put("usb", usbDevices());
    try {
      requireReady();
    } catch (IOException notReady) {
      result.put("devices", new JSONArray());
      result.put("adbUnavailable", SensitiveData.redact(String.valueOf(notReady.getMessage())));
      return result;
    }
    String output = runAdb("devices", "-l");
    JSONArray devices = new JSONArray();
    for (String line : output.split("\\R")) {
      String text = line.trim();
      if (text.isEmpty() || text.startsWith("List of devices")) continue;
      String[] fields = text.split("\\s+");
      if (fields.length < 2 || !ExternalDevicePolicy.isAdbTarget(fields[0])) continue;
      JSONObject device = new JSONObject();
      device.put("serial", fields[0]);
      device.put("state", fields[1]);
      device.put("usb", ExternalDevicePolicy.isUsbSerial(fields[0]));
      device.put("details", text);
      devices.put(device);
    }
    result.put("devices", devices);
    result.put("output", SensitiveData.redact(output));
    result.put("note", "无线目标先 operation=connect（必要时先 pair），本次会话内才会出现在 adb 列表");
    return result;
  }

  /** 宿主 USB 主机口上的设备与识别出的模式（序列号需逐设备授权，这里不读）。 */
  private JSONArray usbDevices() {
    JSONArray list = new JSONArray();
    try {
      android.hardware.usb.UsbManager usb =
          (android.hardware.usb.UsbManager) context.getSystemService(Context.USB_SERVICE);
      if (usb == null) return list;
      for (android.hardware.usb.UsbDevice device : usb.getDeviceList().values()) {
        List<ExternalUsbModes.Iface> ifaces = new ArrayList<>();
        for (int i = 0; i < device.getInterfaceCount(); i++) {
          android.hardware.usb.UsbInterface it = device.getInterface(i);
          ifaces.add(
              new ExternalUsbModes.Iface(
                  it.getInterfaceClass(), it.getInterfaceSubclass(), it.getInterfaceProtocol()));
        }
        ExternalUsbModes.Result mode =
            ExternalUsbModes.classify(device.getVendorId(), device.getProductId(), ifaces);
        list.put(
            new JSONObject()
                .put("vid", String.format(java.util.Locale.ROOT, "%04x", device.getVendorId()))
                .put("pid", String.format(java.util.Locale.ROOT, "%04x", device.getProductId()))
                .put("vendor", mode.vendor())
                .put("mode", mode.mode().id)
                .put("toolchain", mode.mode().toolchain)
                .put("hint", mode.hint()));
      }
    } catch (Throwable error) {
      // USB 枚举失败不影响 adb 列表。
    }
    return list;
  }

  /** 无线：连接目标（本次会话结束即断开，后续每次操作自动重连）。 */
  public JSONObject connect(String target) throws Exception {
    requireReady();
    if (!ExternalDevicePolicy.isWirelessTarget(target) || hostAddress(target))
      throw new IOException("[POLICY_BLOCKED] WIRELESS_TARGET_IS_HOST_OR_INVALID");
    List<ExternalStepOutput.Step> steps =
        runAdbSteps(null, List.of(List.of("connect", target)), 30_000, false);
    boolean ok =
        !steps.isEmpty() && steps.get(0).ok() && ExternalStepOutput.connected(steps.get(0).body());
    return new JSONObject()
        .put("target", target)
        .put("connected", ok)
        .put("output", steps.isEmpty() ? "" : SensitiveData.redact(steps.get(0).body()));
  }

  /** 无线：配对。配对码只进 argv，不写日志与返回值。 */
  public JSONObject pair(String value) throws Exception {
    requireReady();
    String[] parts = ExternalDevicePolicy.pairTarget(value);
    if (parts == null || hostAddress(parts[0]))
      throw new IOException("[POLICY_BLOCKED] WIRELESS_PAIR_ARGS");
    List<ExternalStepOutput.Step> steps =
        runAdbSteps(null, List.of(List.of("pair", parts[0], parts[1])), 60_000, false);
    boolean ok =
        !steps.isEmpty() && steps.get(0).ok() && ExternalStepOutput.paired(steps.get(0).body());
    return new JSONObject().put("target", parts[0]).put("paired", ok);
  }

  /** 目标 IP 是否是本机任一网卡地址（含热点 / VPN）。无法枚举时按是本机处理，宁可拒绝。 */
  private static boolean hostAddress(String target) {
    String ip = target.substring(0, target.lastIndexOf(':'));
    try {
      java.util.Enumeration<java.net.NetworkInterface> all =
          java.net.NetworkInterface.getNetworkInterfaces();
      if (all == null) return true;
      for (java.net.NetworkInterface nic : java.util.Collections.list(all))
        for (java.net.InetAddress address : java.util.Collections.list(nic.getInetAddresses()))
          if (ip.equals(address.getHostAddress())) return true;
      return false;
    } catch (Throwable error) {
      return true;
    }
  }

  public JSONObject inspectTarget(String serial) throws Exception {
    requireReady();
    if (!ExternalDevicePolicy.isAdbTarget(serial)) throw new IOException("ADB_TARGET_REQUIRED");
    boolean wireless = ExternalDevicePolicy.isWirelessTarget(serial);
    if (wireless && hostAddress(serial))
      throw new IOException("[POLICY_BLOCKED] WIRELESS_TARGET_IS_HOST");
    String[] props = {
      "ro.serialno",
      "ro.product.device",
      "ro.product.model",
      "ro.build.fingerprint",
      "ro.build.version.release"
    };
    Map<String, String> values = readProps(serial, props);
    if (values.get("ro.product.device").isEmpty() || values.get("ro.build.fingerprint").isEmpty())
      throw new IOException("TARGET_IDENTITY_INCOMPLETE");
    // 无线可能连回本机自己：设备代号与构建指纹都与本机相同就拒绝（同型号同版本的另一台请改用 USB）。
    if (wireless
        && values.get("ro.product.device").equals(android.os.Build.DEVICE)
        && values.get("ro.build.fingerprint").equals(android.os.Build.FINGERPRINT))
      throw new IOException("[POLICY_BLOCKED] WIRELESS_TARGET_MAY_BE_HOST");
    long epoch = nextTargetEpoch();
    ExternalTargetBinding candidate =
        new ExternalTargetBinding(
            serial, values.get("ro.product.device"), values.get("ro.build.fingerprint"), epoch);
    JSONObject result = new JSONObject();
    result.put("serial", serial);
    result.put("wireless", wireless);
    result.put("product", candidate.product);
    result.put("model", values.get("ro.product.model"));
    result.put("fingerprint", candidate.fingerprint);
    result.put("android", values.get("ro.build.version.release"));
    result.put("epoch", candidate.epoch);
    result.put("warning", "Android 属性可被目标 root 模块伪装；刷写前必须另外通过 bootloader getvar 预检");
    return result;
  }

  /** 一次会话读多个属性；任一步失败或未知即抛出。 */
  private Map<String, String> readProps(String serial, String... props) throws Exception {
    List<List<String>> steps = new ArrayList<>();
    for (String prop : props) steps.add(List.of("shell", "getprop", prop));
    List<ExternalStepOutput.Step> results = runAdbSteps(serial, steps, 30_000, false);
    if (results.size() != props.length) throw new IOException("TARGET_READ_INCOMPLETE");
    Map<String, String> values = new LinkedHashMap<>();
    for (int i = 0; i < props.length; i++) {
      ExternalStepOutput.Step step = results.get(i);
      if (!step.ok())
        throw new IOException(
            step.unknown() ? "[EXECUTION_UNKNOWN] TARGET_READ" : "TARGET_READ_FAILED");
      values.put(props[i], step.body().trim());
    }
    return values;
  }

  public boolean bind(JSONObject candidate) throws Exception {
    requireConfirmationScope();
    requireReady();
    String serial = candidate.getString("serial");
    String product = candidate.getString("product");
    String fingerprint = candidate.getString("fingerprint");
    long epoch = candidate.getLong("epoch");
    JSONObject current = inspectTarget(serial);
    if (!product.equals(current.getString("product"))
        || !fingerprint.equals(current.getString("fingerprint"))
        || epoch != current.getLong("epoch")) throw new IOException("TARGET_CHANGED_BEFORE_BIND");
    return commitInspectedBinding(new ExternalTargetBinding(serial, product, fingerprint, epoch));
  }

  private boolean commitInspectedBinding(ExternalTargetBinding candidate) throws IOException {
    requireConfirmationScope();
    synchronized (TARGET_LOCK) {
      if (!targetCurrentLocked() || candidate.epoch != nextTargetEpoch())
        throw new IOException("TARGET_CHANGED_BEFORE_BIND");
      return commitTargetChange(
          prefs
              .edit()
              .putString(SERIAL, candidate.serial)
              .putString(PRODUCT, candidate.product)
              .putString(FINGERPRINT, candidate.fingerprint)
              .putLong(EPOCH, candidate.epoch));
    }
  }

  private long nextTargetEpoch() throws IOException {
    long previous = prefs.getLong(EPOCH, 0);
    if (previous < 0 || previous == Long.MAX_VALUE)
      throw new IOException("EXTERNAL_TARGET_EPOCH_UNAVAILABLE");
    return previous + 1;
  }

  private void requireConfirmationScope() throws IOException {
    if (requestCurrent == null || !dispatchCurrent())
      throw new IOException("EXTERNAL_AUTHORITY_CHANGED_BEFORE_DISPATCH");
  }

  public JSONObject operate(String operation, String partition, String value, boolean confirmed)
      throws Exception {
    requireConfirmationScope();
    requireReady();
    ExternalDevicePolicy.Plan plan = ExternalDevicePolicy.plan(operation, partition, value);
    if (!plan.allowed()) throw new IOException("[POLICY_BLOCKED] " + plan.reason);
    if (plan.risk == ExternalDevicePolicy.Risk.L3)
      throw new IOException("[POLICY_BLOCKED] L3 操作没有开放");
    if (plan.risk != ExternalDevicePolicy.Risk.L0 && !confirmed)
      throw new IOException("[CONFIRMATION_REQUIRED]");
    switch (plan.operation) {
      case DEVICES:
        return discover();
      case CONNECT:
        return connect(plan.value);
      case PAIR:
        return pair(plan.value);
      case INSTALL:
        // 安装必须走 describeInstall → 原生确认 → install(...)，确认绑定文件摘要与目标代次。
        throw new IOException("[CONFIRMATION_REQUIRED] 安装请使用 operation=install 的专用确认流程");
      default:
        break;
    }
    ExternalTargetBinding target = requireBinding();
    switch (plan.operation) {
      case GETVAR:
        requireUsb(target);
        return fastbootFacts(target);
      case REBOOT:
        return reboot(target, plan.value);
      default:
        break;
    }
    verifyBinding(target);
    switch (plan.operation) {
      case STATUS:
        return statusTarget(target);
      case SHELL:
        return shell(target, plan.value);
      case SCREENSHOT:
        throw new IOException("[NOT_SUPPORTED] 截图导出将在文件传输组件开放");
      case FLASH:
      case SIDELOAD:
      case SET_ACTIVE:
        throw new IOException("[POLICY_BLOCKED] 刷写执行未开放；只提供只读预检与刷写计划（dry-run）");
      default:
        throw new IOException("[POLICY_BLOCKED] 操作未实现");
    }
  }

  private static void requireUsb(ExternalTargetBinding target) throws IOException {
    if (target.wireless()) throw new IOException("[POLICY_BLOCKED] fastboot 与刷写只支持 USB 目标");
  }

  /**
   * 目标在 adb 模式用 {@code adb reboot}；已在 bootloader / fastbootd 时用 {@code fastboot reboot}（按已绑定
   * 序列号匹配，fastboot 下读不到 Android 属性）。不支持 edl / sideload。
   */
  private JSONObject reboot(ExternalTargetBinding target, String value) throws Exception {
    String mode = rebootArgument(value);
    if (!target.wireless() && fastbootVisible(target.serial)) {
      List<String> argv = new ArrayList<>(toolchain().fastbootPrefix(target.serial, "reboot"));
      if (!mode.isEmpty()) argv.add(mode);
      String output = run(argv, toolchain().environment(), 60_000);
      return new JSONObject()
          .put("serial", target.serial)
          .put("via", "fastboot")
          .put("output", SensitiveData.redact(output));
    }
    verifyBinding(target);
    List<String> command = new ArrayList<>(List.of("reboot"));
    if (!mode.isEmpty()) command.add(mode);
    List<ExternalStepOutput.Step> steps =
        runAdbSteps(target.serial, List.of(command), 60_000, false);
    ExternalStepOutput.Step step = steps.isEmpty() ? null : steps.get(0);
    return new JSONObject()
        .put("serial", target.serial)
        .put("via", "adb")
        .put("state", step == null || step.unknown() ? "UNKNOWN" : step.ok() ? "SENT" : "FAILED")
        .put("output", step == null ? "" : SensitiveData.redact(step.body()));
  }

  private boolean fastbootVisible(String serial) throws Exception {
    Toolchain tools = toolchain();
    String connected = run(tools.fastbootPrefix(serial, "devices"), tools.environment(), 30_000);
    return listsSerial(connected, serial);
  }

  /** fastboot devices 输出中是否有以该序列号开头的行；String#lines 需 API 33，这里按行切分以兼容 minSdk 30。 */
  private static boolean listsSerial(String output, String serial) {
    for (String line : output.split("\r?\n|\r", -1)) {
      String trimmed = line.trim();
      if (trimmed.startsWith(serial + "\t") || trimmed.startsWith(serial + " ")) return true;
    }
    return false;
  }

  private static final long INSTALL_TIMEOUT_MS = 600_000;
  private static final long MAX_UNPACK_BYTES = 4L * 1024 * 1024 * 1024;

  /** 安装前的只读描述：种类、风险级、摘要。确认框据此显示；install() 执行前按同一摘要与代次复核。 */
  public JSONObject describeInstall(String importName) throws Exception {
    requireReady();
    ExternalTargetBinding target = requireBinding();
    File file = importedFile(importName);
    ExternalImageProof proof = ExternalImageProof.verify(file, importRoot());
    ExternalInstallPlan.Kind kind = kindOf(file);
    if (kind == ExternalInstallPlan.Kind.AAB)
      throw new IOException("[NOT_SUPPORTED] AAB 是发布格式，请先用 bundletool 生成 APKS");
    if (kind == ExternalInstallPlan.Kind.UNKNOWN) throw new IOException("[NOT_SUPPORTED] 不认识的安装文件");
    String level = kind == ExternalInstallPlan.Kind.MODULE_ZIP ? "L2" : "L1";
    return new JSONObject()
        .put("serial", target.serial)
        .put("product", target.product)
        .put("epoch", target.epoch)
        .put("wireless", target.wireless())
        .put("file", file.getName())
        .put("name", displayName(file.getName()))
        .put("size", proof.size)
        .put("sha256", proof.sha256)
        .put("kind", kind.name())
        .put("level", level);
  }

  /**
   * 执行安装。{@code expectedSha256} / {@code expectedEpoch} 来自确认时的 describeInstall：文件或目标在确认后发生
   * 任何变化都拒绝。步骤结果未知时立即停止并上报，不重放、不猜测会话状态。
   */
  public JSONObject install(String importName, String expectedSha256, long expectedEpoch)
      throws Exception {
    requireConfirmationScope();
    requireReady();
    ExternalTargetBinding target = requireBinding();
    if (target.epoch != expectedEpoch)
      throw new IOException("TARGET_BINDING_CHANGED_AFTER_CONFIRM");
    File file = importedFile(importName);
    ExternalImageProof proof = ExternalImageProof.verify(file, importRoot());
    if (!proof.sha256.equals(expectedSha256))
      throw new IOException("IMPORT_FILE_CHANGED_AFTER_CONFIRM");
    verifyBinding(target);
    ExternalInstallPlan.Kind kind = kindOf(file);
    String serial = target.serial;
    JSONObject result =
        new JSONObject().put("serial", serial).put("kind", kind.name()).put("sha256", proof.sha256);
    JSONArray log = new JSONArray();
    result.put("steps", log);
    File unpack = null;
    try {
      switch (kind) {
        case APK:
          {
            boolean root = probeRoot(serial).root;
            result.put("root", root);
            return finish(
                result,
                runPlan(serial, ExternalInstallPlan.apk(serial, file.getPath(), root), log));
          }
        case SPLIT_BUNDLE:
        case XAPK:
          {
            unpack = newUnpackDir();
            Bundle bundle = unpackBundle(file, unpack, kind == ExternalInstallPlan.Kind.XAPK);
            boolean root = probeRoot(serial).root;
            result
                .put("root", root)
                .put("splits", bundle.apks.size())
                .put("obbs", bundle.obbs.size());
            String state =
                root
                    ? sessionInstall(serial, bundle, log)
                    : runPlan(
                        serial,
                        ExternalInstallPlan.splitsWithoutRoot(serial, paths(bundle.apks)),
                        log);
            if (!state.equals("DONE") || bundle.obbs.isEmpty()) return finish(result, state);
            for (Obb obb : bundle.obbs) {
              state =
                  runPlan(
                      serial,
                      ExternalInstallPlan.obb(
                          serial, obb.packageName, obb.file.getPath(), obb.name),
                      log);
              if (!state.equals("DONE")) break;
            }
            return finish(result, state);
          }
        case MODULE_ZIP:
          {
            Probe probe = probeRoot(serial);
            result.put("root", probe.root).put("manager", probe.manager.name());
            return finish(
                result,
                runPlan(
                    serial,
                    ExternalInstallPlan.module(serial, file.getPath(), probe.manager),
                    log));
          }
        case PLAIN_ZIP:
          return finish(
              result, runPlan(serial, ExternalInstallPlan.plainZip(serial, file.getPath()), log));
        default:
          throw new IOException("[NOT_SUPPORTED] 不支持的安装文件");
      }
    } finally {
      if (unpack != null) deleteUnpack(unpack);
    }
  }

  private static JSONObject finish(JSONObject result, String state) throws Exception {
    result.put("state", state);
    if (state.equals("UNKNOWN")) result.put("note", "存在结果未知的步骤；请先在目标机上核对实际状态，不要直接重试");
    return result;
  }

  /** 顺序执行一组计划步骤（同一会话）；返回 DONE / FAILED / UNKNOWN。 */
  private String runPlan(String serial, List<ExternalInstallPlan.Step> plan, JSONArray log)
      throws Exception {
    List<List<String>> commands = new ArrayList<>();
    for (ExternalInstallPlan.Step step : plan) commands.add(clientTail(serial, step.argv()));
    List<ExternalStepOutput.Step> results =
        runAdbSteps(serial, commands, INSTALL_TIMEOUT_MS, false);
    return record(plan, results, log);
  }

  private static String record(
      List<ExternalInstallPlan.Step> plan, List<ExternalStepOutput.Step> results, JSONArray log)
      throws Exception {
    for (int i = 0; i < plan.size(); i++) {
      ExternalStepOutput.Step step = i < results.size() ? results.get(i) : null;
      String state =
          step == null
              ? "NOT_RUN"
              : step.unknown() ? "UNKNOWN" : step.ok() && !pmFailure(step.body()) ? "OK" : "FAILED";
      log.put(
          new JSONObject()
              .put("note", plan.get(i).note())
              .put("level", plan.get(i).level())
              .put("state", state)
              .put("output", step == null ? "" : clip(SensitiveData.redact(step.body()))));
      if (state.equals("UNKNOWN")) return "UNKNOWN";
      if (!state.equals("OK")) return "FAILED";
    }
    return "DONE";
  }

  /** pm / adb install 在部分系统上失败也返回 0，按输出再判一次。 */
  private static boolean pmFailure(String body) {
    return body != null
        && (body.contains("Failure [")
            || body.startsWith("Error:")
            || body.contains("adb: failed to install"));
  }

  private static String clip(String value) {
    return value.length() <= 2000 ? value : value.substring(0, 2000) + "…";
  }

  /** root 下的 pm 会话：推送 + install-create → 解析会话号 → install-write × N + commit；确定失败时 abandon。 */
  private String sessionInstall(String serial, Bundle bundle, JSONArray log) throws Exception {
    long total = 0;
    long[] sizes = new long[bundle.apks.size()];
    for (int i = 0; i < sizes.length; i++) {
      sizes[i] = bundle.apks.get(i).length();
      total += sizes[i];
    }
    List<ExternalInstallPlan.Step> prepare =
        ExternalInstallPlan.sessionPrepare(serial, paths(bundle.apks), total);
    List<List<String>> commands = new ArrayList<>();
    for (ExternalInstallPlan.Step step : prepare) commands.add(clientTail(serial, step.argv()));
    List<ExternalStepOutput.Step> results =
        runAdbSteps(serial, commands, INSTALL_TIMEOUT_MS, false);
    String state = record(prepare, results, log);
    if (!state.equals("DONE")) return state;
    long session = ExternalInstallPlan.parseSessionId(results.get(results.size() - 1).body());
    if (session < 0) return "FAILED";
    List<ExternalInstallPlan.Step> write = ExternalInstallPlan.sessionSteps(serial, session, sizes);
    commands = new ArrayList<>();
    for (ExternalInstallPlan.Step step : write) commands.add(clientTail(serial, step.argv()));
    results = runAdbSteps(serial, commands, INSTALL_TIMEOUT_MS, false);
    state = record(write, results, log);
    // 只有 commit 之前确定失败才放弃会话；未知时不碰，会话由系统超时回收。
    boolean commitRan = results.size() >= write.size() - 1;
    if (state.equals("FAILED") && !commitRan) {
      ExternalInstallPlan.Step abandon = ExternalInstallPlan.sessionAbandon(serial, session);
      record(
          List.of(abandon),
          runAdbSteps(serial, List.of(clientTail(serial, abandon.argv())), 60_000, false),
          log);
    }
    return state;
  }

  private static final class Probe {
    final boolean root;
    final ExternalInstallPlan.RootManager manager;

    Probe(boolean root, ExternalInstallPlan.RootManager manager) {
      this.root = root;
      this.manager = manager;
    }
  }

  /** 目标 root 与管理器探测（只读，逐条执行，失败继续）。 */
  private Probe probeRoot(String serial) throws Exception {
    List<List<String>> commands = new ArrayList<>();
    for (List<String> argv : ExternalInstallPlan.detectCommands(serial))
      commands.add(clientTail(serial, argv));
    List<ExternalStepOutput.Step> r = runAdbSteps(serial, commands, 60_000, true);
    boolean root = r.size() > 0 && r.get(0).ok() && r.get(0).body().trim().equals("0");
    if (!root) return new Probe(false, ExternalInstallPlan.RootManager.NONE);
    return new Probe(
        true,
        ExternalInstallPlan.detect(
            r.size() > 1 && r.get(1).ok(),
            r.size() > 2 && r.get(2).ok(),
            r.size() > 3 && r.get(3).ok()));
  }

  /** 计划 argv 形如 {@code adb -s SERIAL ...}，转成会话内的客户端尾部参数。 */
  private static List<String> clientTail(String serial, List<String> argv) throws IOException {
    if (argv.size() < 4
        || !argv.get(0).equals("adb")
        || !argv.get(1).equals("-s")
        || !argv.get(2).equals(serial)) throw new IOException("INSTALL_PLAN_SHAPE");
    return argv.subList(3, argv.size());
  }

  private static ExternalInstallPlan.Kind kindOf(File file) throws IOException {
    String name = file.getName().toLowerCase(java.util.Locale.ROOT);
    List<String> entries = null;
    if (name.endsWith(".zip")) entries = zipEntries(file);
    return ExternalInstallPlan.kindOf(name, entries);
  }

  private static List<String> zipEntries(File file) throws IOException {
    List<String> names = new ArrayList<>();
    try (java.util.zip.ZipFile zip = new java.util.zip.ZipFile(file)) {
      java.util.Enumeration<? extends java.util.zip.ZipEntry> all = zip.entries();
      while (all.hasMoreElements()) {
        if (names.size() >= 10_000) throw new IOException("ZIP_TOO_MANY_ENTRIES");
        names.add(all.nextElement().getName());
      }
    }
    return names;
  }

  private static String displayName(String importName) {
    return importName.matches("[0-9a-f-]{36}-.+") ? importName.substring(37) : importName;
  }

  private static final class Obb {
    final File file;
    final String packageName, name;

    Obb(File file, String packageName, String name) {
      this.file = file;
      this.packageName = packageName;
      this.name = name;
    }
  }

  private static final class Bundle {
    final List<File> apks = new ArrayList<>();
    final List<Obb> obbs = new ArrayList<>();
  }

  /** 解出分包与 OBB 到私有临时目录；文件名由我们生成，不使用压缩包里的路径（防 zip-slip）。 */
  private static Bundle unpackBundle(File archive, File dir, boolean xapk) throws IOException {
    Bundle bundle = new Bundle();
    long budget = MAX_UNPACK_BYTES;
    try (java.util.zip.ZipFile zip = new java.util.zip.ZipFile(archive)) {
      List<String> names = new ArrayList<>();
      java.util.Enumeration<? extends java.util.zip.ZipEntry> all = zip.entries();
      while (all.hasMoreElements()) {
        if (names.size() >= 10_000) throw new IOException("ZIP_TOO_MANY_ENTRIES");
        names.add(all.nextElement().getName());
      }
      List<String> apks = ExternalInstallPlan.bundleApkEntries(names);
      if (apks.isEmpty()) throw new IOException("[NOT_SUPPORTED] 压缩包中没有可安装的 APK，或分包超过上限");
      for (int i = 0; i < apks.size(); i++) {
        File out = new File(dir, String.format(java.util.Locale.ROOT, "s%03d.apk", i));
        budget -= extract(zip, zip.getEntry(apks.get(i)), out, budget);
        bundle.apks.add(out);
      }
      if (xapk) {
        for (String name : names) {
          String[] obb = ExternalInstallPlan.xapkObb(name);
          if (obb == null) continue;
          if (bundle.obbs.size() >= ExternalInstallPlan.MAX_OBBS)
            throw new IOException("XAPK_TOO_MANY_OBB");
          File out =
              new File(dir, String.format(java.util.Locale.ROOT, "o%03d.obb", bundle.obbs.size()));
          budget -= extract(zip, zip.getEntry(name), out, budget);
          bundle.obbs.add(new Obb(out, obb[0], obb[1]));
        }
      }
    }
    return bundle;
  }

  private static long extract(
      java.util.zip.ZipFile zip, java.util.zip.ZipEntry entry, File out, long budget)
      throws IOException {
    if (entry == null || entry.isDirectory()) throw new IOException("ZIP_ENTRY_MISSING");
    long written = 0;
    try (InputStream input = zip.getInputStream(entry);
        java.io.OutputStream output = new java.io.FileOutputStream(out)) {
      byte[] buffer = new byte[256 * 1024];
      int count;
      while ((count = input.read(buffer)) != -1) {
        written += count;
        if (written > budget) throw new IOException("UNPACK_TOO_LARGE");
        output.write(buffer, 0, count);
      }
    }
    if (written <= 0) throw new IOException("ZIP_ENTRY_EMPTY");
    return written;
  }

  private File newUnpackDir() throws IOException {
    File root = importRoot();
    String[] stale = root.list((dir, name) -> name.startsWith("unpack-"));
    if (stale != null) for (String name : stale) deleteUnpack(new File(root, name));
    File dir = new File(root, "unpack-" + java.util.UUID.randomUUID());
    if (!dir.mkdir()) throw new IOException("UNPACK_DIRECTORY");
    return dir;
  }

  /** 只删本目录下的普通文件，链接不跟随。 */
  private static void deleteUnpack(File dir) {
    try {
      java.nio.file.Path path = dir.toPath();
      if (!java.nio.file.Files.isDirectory(path, java.nio.file.LinkOption.NOFOLLOW_LINKS)) return;
      try (java.nio.file.DirectoryStream<java.nio.file.Path> children =
          java.nio.file.Files.newDirectoryStream(path)) {
        for (java.nio.file.Path child : children)
          if (java.nio.file.Files.isRegularFile(child, java.nio.file.LinkOption.NOFOLLOW_LINKS))
            java.nio.file.Files.deleteIfExists(child);
      }
      java.nio.file.Files.deleteIfExists(path);
    } catch (IOException ignored) {
      // 下次安装前再清理。
    }
  }

  private static List<String> paths(List<File> files) {
    List<String> out = new ArrayList<>();
    for (File file : files) out.add(file.getPath());
    return out;
  }

  public JSONObject preflight(String partition, File image) throws Exception {
    requireReady();
    ExternalTargetBinding target = requireBinding();
    requireUsb(target);
    if (!ExternalDevicePolicy.supportedFlashPartition(partition))
      throw new IOException("FLASH_PARTITION_BLOCKED");
    ExternalImageProof proof = ExternalImageProof.verify(image, importRoot());
    ExternalFastbootFacts facts = readFastbootFacts(target, partition);
    String serial = facts.get("serialno");
    if (serial == null) serial = facts.get("serial-number");
    if (serial == null) serial = target.serial;
    String product = facts.get("product");
    if (product == null) product = target.product;
    boolean unlockedKnown = facts.get("unlocked") != null || facts.get("secure") != null;
    boolean unlocked = facts.yes("unlocked") || "no".equalsIgnoreCase(facts.get("secure"));
    int battery = -1;
    try {
      String raw = facts.get("battery-level");
      if (raw != null) battery = Integer.parseInt(raw.replaceAll("[^0-9-]", ""));
    } catch (NumberFormatException ignored) {
    }
    long size = facts.size(partition);
    ExternalPreflight.Facts preflightFacts =
        new ExternalPreflight.Facts(
            serial,
            product,
            true,
            facts.yes("is-userspace"),
            unlocked,
            unlockedKnown,
            battery,
            facts.rollback(partition),
            -1,
            size);
    ExternalPreflight.Result check =
        ExternalPreflight.check(target.serial, partition, proof.size, preflightFacts);
    JSONObject result = new JSONObject();
    result.put("state", check.allowed ? "PREFLIGHT_PASS_READ_ONLY" : "BLOCKED");
    result.put("allowed", false);
    result.put("preflightPassed", check.allowed);
    result.put("serial", target.serial);
    result.put("product", product);
    result.put("partition", partition);
    result.put("image", proof.name);
    result.put("size", proof.size);
    result.put("sha256", proof.sha256);
    result.put("imageType", proof.type);
    result.put("reasons", new JSONArray(check.reasons));
    // 刷写计划（dry-run）：给出将要执行的 argv 与执行器裁决，证明命令形状正确且执行边界仍拒绝写入。
    Toolchain tools = toolchain();
    List<String> plannedFlash =
        com.deepseekharness.app.util.ExternalFastbootPlan.flash(
            tools.loader,
            tools.libraryPath,
            tools.fastboot,
            target.serial,
            partition,
            proof.file.getPath());
    result.put("dryRunArgv", new JSONArray(plannedFlash.subList(3, plannedFlash.size())));
    result.put(
        "executorVerdict",
        String.valueOf(
            com.deepseekharness.app.util.ExternalToolPolicy.rejection(
                plannedFlash, expectedRoot())));
    result.put("executed", false);
    result.put("limitations", "目标电量与镜像 rollback index 未由可验证的官方清单提供；getprop 不作解锁状态证据；预检通过也不会刷写");
    return result;
  }

  private JSONObject statusTarget(ExternalTargetBinding target) throws Exception {
    JSONObject result = new JSONObject();
    result.put("serial", target.serial);
    result.put("product", target.product);
    result.put("fingerprint", target.fingerprint);
    result.put("epoch", target.epoch);
    result.put("verified", true);
    result.put("usb", !target.wireless());
    result.put("shellId", runAdb(target, "shell", "id").trim());
    return result;
  }

  private JSONObject shell(ExternalTargetBinding target, String command) throws Exception {
    List<String> args = ExternalDebugCommand.argv(command);
    String[] tail = new String[args.size() + 1];
    tail[0] = "shell";
    for (int i = 0; i < args.size(); i++) tail[i + 1] = args.get(i);
    return runAdbResult(target, tail);
  }

  // ================= 外接设备只读观察（watch / 动作后 settle） =================

  /**
   * 只读观察：在同一个专属 adb 会话里连续采样目标的 events 缓冲区（采样之间不再启动 adb server），拿不到变化就返回
   * UNCHANGED。不修改目标、不需要原生确认；UNCERTAIN 只代表本次没拿到可用样本，可直接重新观察，不是动作的
   * UNKNOWN。真机测量（OnePlus PJD110 / Android 16）见 docs/design/extdev-observation-20261110.md。
   */
  public JSONObject watch(String mode, String since, int samples, int quietSamples)
      throws Exception {
    return observe(mode, since, samples, quietSamples, true);
  }

  /** 动作执行后附带观察（observe=1）：不参与 watch 限速；失败只写 observationUnavailable，不改动作状态。 */
  public void attachObservation(JSONObject actionResult, int samples) {
    try {
      JSONObject observation =
          observe(
              "events",
              "",
              samples,
              Math.min(ExternalWatchPolicy.DEFAULT_QUIET_SAMPLES, Math.max(1, samples - 1)),
              false);
      String state = observation.optString("state");
      if ("CHANGED".equals(state) || "UNCHANGED".equals(state)) {
        actionResult.put("observation", observation);
        if (observation.has("settle"))
          actionResult.put("settle", observation.optJSONObject("settle"));
        return;
      }
      observationUnavailable(actionResult, observation.optString("error", "OBSERVE_UNCERTAIN"));
    } catch (Throwable error) {
      observationUnavailable(actionResult, SensitiveData.redact(String.valueOf(error)));
    }
  }

  private static void observationUnavailable(JSONObject result, String code) {
    try {
      result.put("observationUnavailable", code);
      result.put("nextAction", "OBSERVE_WITH_WATCH");
      result.put("automaticRetry", false);
    } catch (org.json.JSONException ignored) {
      // 回执本身是 JSONObject；写失败不能影响动作结果。
    }
  }

  private JSONObject observe(
      String mode, String since, int samples, int quietSamples, boolean throttle) throws Exception {
    String normalizedMode =
        mode == null ? "events" : mode.trim().toLowerCase(java.util.Locale.ROOT);
    if (!normalizedMode.equals("events") && !normalizedMode.equals("window"))
      throw new IOException("OBSERVE_MODE");
    if (samples < ExternalWatchPolicy.MIN_SAMPLES || samples > ExternalWatchPolicy.MAX_SAMPLES)
      throw new IOException("OBSERVE_SAMPLES_RANGE");
    String cursor = since == null ? "" : since.trim();
    if (!ExternalWatchPolicy.validSince(cursor)) throw new IOException("OBSERVE_SINCE");
    int quiet =
        quietSamples < 1 || quietSamples > samples
            ? ExternalWatchPolicy.DEFAULT_QUIET_SAMPLES
            : quietSamples;
    quiet = Math.min(quiet, Math.max(1, samples - 1));
    if (throttle) {
      long wait = WATCH_RATE_LIMIT.acquire(android.os.SystemClock.elapsedRealtime());
      if (wait > 0) {
        return new JSONObject()
            .put("state", "NOT_SENT")
            .put("error", "RATE_LIMITED")
            .put("retryAfterMs", wait)
            .put("automaticRetry", false)
            .put("note", "上一次观察刚结束；本次未发送任何目标命令，稍后再调用。");
      }
    }
    requireConfirmationScope();
    requireReady();
    ExternalTargetBinding target = requireBinding();
    int planned = normalizedMode.equals("window") ? 1 : samples;
    List<List<String>> steps = new ArrayList<>();
    steps.add(List.of("shell", "getprop", "ro.product.device"));
    steps.add(List.of("shell", "getprop", "ro.build.fingerprint"));
    for (int i = 0; i < planned; i++)
      steps.add(
          normalizedMode.equals("window")
              ? List.of("shell", "dumpsys", "window")
              : focusSampleStep());
    long started = android.os.SystemClock.elapsedRealtime();
    List<ExternalStepOutput.Step> results;
    try {
      results = runAdbSteps(target.serial, steps, OBSERVE_TIMEOUT_MS, false);
    } catch (IOException error) {
      String message = String.valueOf(error.getMessage());
      if (message.contains("[EXECUTION_UNKNOWN]"))
        return uncertain(target, normalizedMode, samples, "OBSERVE_SESSION_UNKNOWN", cursor);
      throw error;
    }
    if (results.size() < 2)
      return uncertain(target, normalizedMode, samples, "TARGET_READ_INCOMPLETE", cursor);
    ExternalStepOutput.Step device = results.get(0);
    ExternalStepOutput.Step fingerprint = results.get(1);
    if (device.unknown() || fingerprint.unknown())
      return uncertain(target, normalizedMode, samples, "TARGET_READ_UNKNOWN", cursor);
    if (!device.ok() || !fingerprint.ok()) throw new IOException("TARGET_READ_FAILED");
    if (!target.product.equals(device.body().trim())
        || !target.fingerprint.equals(fingerprint.body().trim()))
      throw new IOException("TARGET_IDENTITY_CHANGED");
    boolean partial = results.size() < steps.size();
    JSONObject observation =
        normalizedMode.equals("window")
            ? windowObservation(target, cursor, results, started, partial)
            : eventObservation(target, cursor, samples, quiet, results, started, partial);
    ExternalTargetBinding current = binding();
    if (current == null
        || current.epoch != target.epoch
        || !current.matches(target.serial, target.product, target.fingerprint))
      throw new IOException("TARGET_BINDING_CHANGED");
    return observation;
  }

  /**
   * 单次事件采样。刻意不带 {@code -t}：真机实测（Android 16）logcat 的 {@code -t} 先截取原始缓冲区尾部再过滤，
   * 带它会得到空结果；不带时过滤只返回匹配标签的事件，输出约 16 KB。
   */
  private static List<String> focusSampleStep() {
    return List.of(
        "shell",
        "logcat",
        "-d",
        "-b",
        "events",
        "-s",
        "wm_resume_activity:V",
        "wm_set_resumed_activity:V",
        "am_focused_activity:V",
        "am_resume_activity:V",
        "-v",
        "epoch");
  }

  private JSONObject eventObservation(
      ExternalTargetBinding target,
      String since,
      int samples,
      int quiet,
      List<ExternalStepOutput.Step> results,
      long started,
      boolean partial)
      throws Exception {
    List<List<ExternalFocusEvents.Event>> parsed = new ArrayList<>();
    boolean sampleUnknown = false;
    for (int i = 0; i < samples; i++) {
      int index = 2 + i;
      if (index >= results.size()) {
        parsed.add(null);
        partial = true;
        continue;
      }
      ExternalStepOutput.Step step = results.get(index);
      if (step.unknown()) sampleUnknown = true;
      if (!step.ok()) {
        parsed.add(null);
        partial = true;
        continue;
      }
      parsed.add(ExternalFocusEvents.parse(step.body()));
    }
    ExternalWatchPolicy.Outcome outcome =
        ExternalWatchPolicy.analyze(parsed, target.epoch, "events", since, quiet);
    JSONObject result = baseObservation(target, "events");
    result.put("state", outcome.state().name());
    result.put("changed", outcome.state() == ExternalWatchPolicy.State.CHANGED);
    result.put("sawChange", outcome.sawChange());
    result.put("partial", partial || outcome.partial());
    result.put("sampleUnknown", sampleUnknown);
    result.put("samples", outcome.samples());
    result.put("plannedSamples", samples);
    result.put("changedAtSample", outcome.changedAtSample());
    result.put("newEvents", outcome.newEvents());
    result.put("elapsedMs", android.os.SystemClock.elapsedRealtime() - started);
    if (!since.isEmpty()) result.put("sinceDigest", since);
    putSnapshot(result, outcome.foreground());
    result.put("digest", outcome.digest());
    if (outcome.state() == ExternalWatchPolicy.State.UNCERTAIN) {
      result.put("error", sampleUnknown ? "OBSERVE_SAMPLE_UNKNOWN" : "FOCUS_EVENTS_UNAVAILABLE");
      result.put("nextAction", "USE_MODE_WINDOW");
      result.put("automaticRetry", false);
      result.put("note", "只读观察未拿到可用事件样本；没有修改目标，可直接重新观察，不要据此重放任何动作。");
      return result;
    }
    result.put("settle", settle(outcome, quiet));
    result.put(
        "nextAction",
        outcome.state() == ExternalWatchPolicy.State.CHANGED ? "READ_STATE_OR_ACT" : "WATCH_AGAIN");
    result.put("note", "只读观察；digest 是前台包/Activity 的不可逆摘要，不含屏幕文本。UNCHANGED 只代表本窗口内没看到变化。");
    return result;
  }

  private static JSONObject settle(ExternalWatchPolicy.Outcome outcome, int quiet)
      throws Exception {
    return new JSONObject()
        .put("settled", outcome.settled())
        .put("sawChange", outcome.sawChange())
        .put("newEvents", outcome.newEvents())
        .put("samples", outcome.samples())
        .put("changedAtSample", outcome.changedAtSample())
        .put("quietRun", outcome.quietRun())
        .put("quietSamples", quiet)
        .put("source", "logcat-event");
  }

  private JSONObject windowObservation(
      ExternalTargetBinding target,
      String since,
      List<ExternalStepOutput.Step> results,
      long started,
      boolean partial)
      throws Exception {
    JSONObject result = baseObservation(target, "window");
    ExternalStepOutput.Step step = results.size() > 2 ? results.get(2) : null;
    if (step == null || !step.ok()) {
      result.put("state", "UNCERTAIN");
      result.put("changed", false);
      result.put("sawChange", false);
      result.put("partial", true);
      result.put(
          "error", step != null && step.unknown() ? "WINDOW_READ_UNKNOWN" : "WINDOW_READ_FAILED");
      result.put("nextAction", "REPORT_TO_USER");
      result.put("automaticRetry", false);
      result.put("note", "只读窗口采样失败；没有修改目标。");
      return result;
    }
    ExternalFocusEvents.Snapshot snapshot = ExternalFocusEvents.parseWindowDump(step.body());
    if (snapshot == null) {
      result.put("state", "UNCERTAIN");
      result.put("changed", false);
      result.put("sawChange", false);
      result.put("partial", partial);
      result.put("error", "WINDOW_STATE_UNAVAILABLE");
      result.put("nextAction", "REPORT_TO_USER");
      result.put("automaticRetry", false);
      result.put("note", "dumpsys window 未给出 mCurrentFocus / mFocusedApp；没有修改目标。");
      return result;
    }
    String digest =
        ExternalFocusEvents.digest(
            target.epoch, "window", snapshot.packageName(), snapshot.activity());
    String state = since.isEmpty() ? "OBSERVED" : digest.equals(since) ? "UNCHANGED" : "CHANGED";
    result.put("state", state);
    result.put("changed", "CHANGED".equals(state));
    result.put("sawChange", false);
    result.put("partial", partial);
    result.put("samples", 1);
    result.put("plannedSamples", 1);
    result.put("elapsedMs", android.os.SystemClock.elapsedRealtime() - started);
    putSnapshot(result, snapshot);
    result.put("digest", digest);
    if (!since.isEmpty()) result.put("sinceDigest", since);
    result.put(
        "settle",
        new JSONObject()
            .put("settled", false)
            .put("samples", 1)
            .put("source", "dumpsys-window")
            .put("note", "单次权威采样，不判断稳定；需要等待页面变化请用 mode=events。"));
    result.put(
        "nextAction",
        since.isEmpty()
            ? "STORE_DIGEST_THEN_WATCH"
            : "CHANGED".equals(state) ? "READ_STATE_OR_ACT" : "WATCH_AGAIN");
    result.put("note", "只读窗口采样；digest 是前台包/Activity 的不可逆摘要，不含屏幕文本。");
    return result;
  }

  private JSONObject uncertain(
      ExternalTargetBinding target, String mode, int samples, String error, String since)
      throws Exception {
    JSONObject result = baseObservation(target, mode);
    result.put("state", "UNCERTAIN");
    result.put("changed", false);
    result.put("sawChange", false);
    result.put("samples", 0);
    result.put("plannedSamples", mode.equals("window") ? 1 : samples);
    result.put("error", error);
    result.put("nextAction", mode.equals("window") ? "REPORT_TO_USER" : "USE_MODE_WINDOW");
    result.put("automaticRetry", false);
    if (!since.isEmpty()) result.put("sinceDigest", since);
    result.put("note", "只读观察没有拿到可用样本；没有修改目标，也没有动作需要重放。");
    return result;
  }

  private JSONObject baseObservation(ExternalTargetBinding target, String mode) throws Exception {
    return new JSONObject()
        .put("kind", "dsha-extdev-observation-v1")
        .put("mode", mode)
        .put("serial", target.serial)
        .put("product", target.product)
        .put("epoch", target.epoch)
        .put("sequence", OBSERVE_SEQUENCE.incrementAndGet())
        .put("timestampMs", System.currentTimeMillis());
  }

  private static void putSnapshot(JSONObject result, ExternalFocusEvents.Snapshot snapshot)
      throws Exception {
    if (snapshot == null || !snapshot.known()) {
      result.put("foregroundPackage", "");
      result.put("foregroundActivity", "");
      result.put("component", "");
      return;
    }
    result.put("foregroundPackage", snapshot.packageName());
    result.put("foregroundActivity", snapshot.activity());
    result.put("component", snapshot.component());
    if (snapshot.deviceTimeMs() > 0) result.put("deviceTimeMs", snapshot.deviceTimeMs());
  }

  private JSONObject runAdbResult(ExternalTargetBinding target, String... args) throws Exception {
    JSONObject result = new JSONObject();
    result.put("serial", target.serial);
    result.put("product", target.product);
    result.put("output", SensitiveData.redact(runAdb(target, args)));
    return result;
  }

  private ExternalFastbootFacts readFastbootFacts(ExternalTargetBinding target, String partition)
      throws Exception {
    StringBuilder output = new StringBuilder();
    for (String key :
        new String[] {
          "product",
          "serialno",
          "unlocked",
          "secure",
          "is-userspace",
          "battery-level",
          "partition-size:" + partition,
          "rollback-index:" + partition,
          "current-slot",
          "slot-count"
        }) output.append(runFastboot(target.serial, "getvar", key)).append('\n');
    return ExternalFastbootFacts.parse(output.toString());
  }

  private JSONObject fastbootFacts(ExternalTargetBinding target) throws Exception {
    String output = runFastboot(target.serial, "getvar", "all");
    ExternalFastbootFacts facts = ExternalFastbootFacts.parse(output);
    JSONObject values = new JSONObject();
    for (Map.Entry<String, String> item : facts.values.entrySet())
      values.put(item.getKey(), item.getValue());
    return new JSONObject()
        .put("serial", target.serial)
        .put("product", target.product)
        .put("facts", values);
  }

  private String runAdb(String... command) throws Exception {
    requireReady();
    return runAdbSession(null, command);
  }

  private String runAdb(ExternalTargetBinding target, String... command) throws Exception {
    requireReady();
    verifyBinding(target);
    List<ExternalStepOutput.Step> steps =
        runAdbSteps(target.serial, List.of(List.of(command)), ADB_TIMEOUT_MS, false);
    ExternalStepOutput.Step step = steps.isEmpty() ? null : steps.get(0);
    if (step == null || step.unknown()) return "[EXECUTION_UNKNOWN] 外接命令结果未知，不会自动重试\n[EXIT=125]";
    if (!step.ok())
      throw new IllegalStateException(
          "EXTERNAL_COMMAND_FAILED: " + SensitiveData.redact(step.body()));
    return step.body().trim();
  }

  /**
   * 每条客户端命令都在同一 root 子进程里配一个本次专属的 nodaemon server（专用 HOME + 端口，只绑 loopback），
   * 命令结束即回收；不再调用 start-server / kill-server，也不留常驻 root server。
   */
  private String runAdbSession(String serial, String... command) throws Exception {
    Toolchain tools = toolchain();
    List<String> argv = new ArrayList<>(tools.adbPrefix());
    argv.add("-P");
    argv.add(Integer.toString(tools.port));
    if (serial != null) {
      argv.add("-s");
      argv.add(serial);
    }
    java.util.Collections.addAll(argv, command);
    String output;
    try {
      output =
          RootShell.execExternalAdbSession(
              context,
              tools.adbServer(),
              argv,
              tools.environment(),
              ADB_TIMEOUT_MS,
              expectedRoot(),
              this::dispatchCurrent);
    } catch (IOException error) {
      return "[POLICY_BLOCKED] " + error.getMessage() + "\n[EXIT=126]";
    }
    return checked(output);
  }

  private String runFastboot(String serial, String... command) throws Exception {
    Toolchain tools = toolchain();
    String connected = run(tools.fastbootPrefix(serial, "devices"), tools.environment(), 30_000);
    if (!listsSerial(connected, serial)) throw new IOException("FASTBOOT_TARGET_NOT_CONNECTED");
    List<String> argv = new ArrayList<>(tools.fastbootPrefix(serial));
    for (String item : command) argv.add(item);
    return run(argv, tools.environment(), 60_000);
  }

  private String run(List<String> argv, Map<String, String> environment, long timeout) {
    String output;
    try {
      output =
          RootShell.execExternal(
              context, argv, environment, timeout, expectedRoot(), this::dispatchCurrent);
    } catch (IOException error) {
      return "[POLICY_BLOCKED] " + error.getMessage() + "\n[EXIT=126]";
    }
    return checked(output);
  }

  /** 统一处理退出标记：UNKNOWN 原样上交（不得重放），非 0 抛出，0 返回正文。 */
  private String checked(String output) {
    if (hasUnknown(output)) return output;
    int marker = output == null ? -1 : output.lastIndexOf("\n[EXIT=");
    if (marker < 0) marker = output == null ? -1 : output.lastIndexOf("[EXIT=");
    if (marker < 0 && output != null) marker = output.lastIndexOf("\n[EXIT=");
    if (marker >= 0) {
      String status = output.substring(marker).trim();
      if (!status.equals("[EXIT=0]"))
        throw new IllegalStateException("EXTERNAL_COMMAND_FAILED: " + SensitiveData.redact(output));
      String body = output.substring(0, marker);
      if (body.endsWith("\n")) body = body.substring(0, body.length() - 1);
      return body.trim();
    }
    return output == null ? "" : output.trim();
  }

  private boolean hasUnknown(String result) {
    return result != null && result.contains("[EXECUTION_UNKNOWN]");
  }

  private ExternalTargetBinding requireBinding() throws IOException {
    if (requestCurrent != null) requireConfirmationScope();
    ExternalTargetBinding target = requestCurrent == null ? binding() : observedTarget;
    if (target == null) throw new IOException("TARGET_NOT_BOUND");
    return target;
  }

  private void verifyBinding(ExternalTargetBinding target) throws Exception {
    ExternalTargetBinding current = binding();
    if (current == null
        || !current.matches(target.serial, target.product, target.fingerprint)
        || current.epoch != target.epoch) throw new IOException("TARGET_BINDING_CHANGED");
    Map<String, String> identity =
        readProps(target.serial, "ro.product.device", "ro.build.fingerprint");
    if (!target.product.equals(identity.get("ro.product.device"))
        || !target.fingerprint.equals(identity.get("ro.build.fingerprint")))
      throw new IOException("TARGET_IDENTITY_CHANGED");
  }

  /**
   * 同一专属 server 内执行多步。目标为无线时先 connect 并核对输出；有目标时再 wait-for-device，避免新 server 尚未
   * 枚举到设备。返回值只含调用方给出的步骤（前置步骤失败时抛出）。
   */
  private List<ExternalStepOutput.Step> runAdbSteps(
      String serial, List<List<String>> commands, long timeoutMs, boolean continueOnFailure)
      throws Exception {
    Toolchain tools = toolchain();
    List<List<String>> steps = new ArrayList<>();
    int lead = 0;
    if (serial != null && ExternalDevicePolicy.isWirelessTarget(serial)) {
      steps.add(clientArgv(tools, null, List.of("connect", serial)));
      lead++;
    }
    if (serial != null) {
      steps.add(clientArgv(tools, serial, List.of("wait-for-device")));
      lead++;
    }
    for (List<String> command : commands) steps.add(clientArgv(tools, serial, command));
    String nonce = nonce();
    String output =
        RootShell.execExternalAdbSteps(
            context,
            tools.adbServer(),
            steps,
            tools.environment(),
            timeoutMs,
            expectedRoot(),
            importRoot().getPath(),
            nonce,
            continueOnFailure,
            this::dispatchCurrent);
    List<ExternalStepOutput.Step> parsed = ExternalStepOutput.parse(output, nonce);
    if (parsed.isEmpty()) {
      String reason = SensitiveData.redact(ExternalStepOutput.preamble(output, nonce)).trim();
      if (reason.contains("[EXECUTION_UNKNOWN]"))
        throw new IOException("[EXECUTION_UNKNOWN] " + reason);
      throw new IOException(reason.isEmpty() ? "EXTERNAL_ADB_SESSION_FAILED" : reason);
    }
    for (int i = 0; i < lead; i++) {
      ExternalStepOutput.Step step = i < parsed.size() ? parsed.get(i) : null;
      boolean connectStep = i == 0 && lead == 2;
      boolean ok =
          step != null && step.ok() && (!connectStep || ExternalStepOutput.connected(step.body()));
      if (!ok) {
        if (step != null && step.unknown())
          throw new IOException("[EXECUTION_UNKNOWN] TARGET_NOT_READY");
        throw new IOException(connectStep ? "WIRELESS_CONNECT_FAILED" : "TARGET_NOT_READY");
      }
    }
    return parsed.subList(lead, parsed.size());
  }

  private static List<String> clientArgv(Toolchain tools, String serial, List<String> command) {
    List<String> argv = new ArrayList<>(tools.adbPrefix());
    argv.add("-P");
    argv.add(Integer.toString(tools.port));
    if (serial != null) {
      argv.add("-s");
      argv.add(serial);
    }
    argv.addAll(command);
    return argv;
  }

  private static String nonce() {
    byte[] bytes = new byte[16];
    new java.security.SecureRandom().nextBytes(bytes);
    StringBuilder value = new StringBuilder();
    for (byte b : bytes) value.append(String.format(java.util.Locale.ROOT, "%02x", b & 0xff));
    return value.toString();
  }

  private String runAdbRaw(String serial, String... command) throws Exception {
    return runAdbSession(serial, command);
  }

  private boolean rootVerified() {
    if (!RootShell.enabled(context) || !RootShell.present()) return false;
    String result = RootShell.exec(context, "id", -1);
    return result != null && result.contains("uid=0") && result.endsWith("[EXIT=0]");
  }

  private void requireReady() throws Exception {
    if (!enabled()) throw new IOException("[DISABLED] 外接设备能力已关闭");
    if (!RootShell.enabled(context))
      throw new IOException("[NO_PERMISSION] 请到设置 → 设备能力授权开启 Root Shell");
    if (!rootVerified()) throw new IOException("[NO_PERMISSION] su 未授予 DSHA root；请在 Root 管理器中授权");
    toolchain();
  }

  private String expectedRoot() throws IOException {
    return HarnessController.get(context).proot().getRootfsDir().getCanonicalPath();
  }

  private Toolchain toolchain() throws IOException {
    File root = HarnessController.get(context).proot().getRootfsDir().getCanonicalFile();
    File loader =
        first(
            root,
            "usr/lib/ld-linux-aarch64.so.1",
            "usr/lib/aarch64-linux-gnu/ld-linux-aarch64.so.1",
            "lib/ld-linux-aarch64.so.1",
            "lib/aarch64-linux-gnu/ld-linux-aarch64.so.1");
    File adb = new File(root, "usr/bin/adb");
    File fastboot = new File(root, "usr/bin/fastboot");
    File libs = new File(root, "usr/lib/aarch64-linux-gnu");
    if (loader == null || !adb.isFile() || !fastboot.isFile() || !libs.isDirectory())
      throw new IOException(
          "EXTERNAL_TOOLCHAIN_UNAVAILABLE: Ubuntu adb/fastboot 或 glibc loader 缺失");
    String proofFailure = ExternalToolProof.verify(context, root);
    if (proofFailure != null) throw new IOException("[POLICY_BLOCKED] 外接工具完整性未通过：" + proofFailure);
    File data = importRoot();
    File home = new File(data, "adb-home");
    if (!home.isDirectory() && !home.mkdirs()) throw new IOException("EXTERNAL_ADB_HOME");
    String rootPath = root.getCanonicalPath() + File.separator;
    for (File tool : new File[] {loader, adb, fastboot, libs})
      if (!tool.getCanonicalPath().startsWith(rootPath))
        throw new IOException("EXTERNAL_TOOL_PATH_ESCAPE");
    String libraryPath =
        libs.getAbsolutePath() + ":" + new File(root, "lib/aarch64-linux-gnu").getAbsolutePath();
    Map<String, String> env =
        Map.of(
            "HOME", home.getAbsolutePath(), "ANDROID_ADB_SERVER_PORT", Integer.toString(adbPort));
    return new Toolchain(
        loader.getAbsolutePath(),
        libraryPath,
        adb.getAbsolutePath(),
        fastboot.getAbsolutePath(),
        env,
        adbPort);
  }

  private static int ephemeralPort() {
    try (java.net.ServerSocket socket = new java.net.ServerSocket(0)) {
      socket.setReuseAddress(true);
      return socket.getLocalPort();
    } catch (IOException error) {
      return 40000 + (int) (System.nanoTime() % 20000);
    }
  }

  private static String rebootArgument(String value) throws IOException {
    switch (value) {
      case "system":
        return "";
      case "bootloader":
        return "bootloader";
      case "recovery":
        return "recovery";
      case "fastboot":
        return "fastboot";
      case "fastbootd":
        return "fastboot";
      default:
        throw new IOException("REBOOT_TARGET");
    }
  }

  private File first(File root, String... paths) throws IOException {
    for (String path : paths) {
      File candidate = new File(root, path);
      if (candidate.isFile()
          && candidate.getCanonicalPath().startsWith(root.getCanonicalPath() + File.separator))
        return candidate.getCanonicalFile();
    }
    return null;
  }

  private static final class Toolchain {
    final String loader, libraryPath, adb, fastboot;
    final Map<String, String> environment;
    final int port;

    Toolchain(
        String loader,
        String libraryPath,
        String adb,
        String fastboot,
        Map<String, String> environment,
        int port) {
      this.loader = loader;
      this.libraryPath = libraryPath;
      this.adb = adb;
      this.fastboot = fastboot;
      this.environment = environment;
      this.port = port;
    }

    Map<String, String> environment() {
      return environment;
    }

    List<String> adbPrefix() {
      return new ArrayList<>(List.of(loader, "--library-path", libraryPath, adb));
    }

    /** 前台 nodaemon server：不自我 re-exec（否则 ld.so 参数错位），默认只监听 127.0.0.1，禁止 -a。 */
    List<String> adbServer() {
      List<String> args = adbPrefix();
      args.add("-P");
      args.add(Integer.toString(port));
      args.add("nodaemon");
      args.add("server");
      return args;
    }

    List<String> fastbootPrefix(String serial, String... operation) {
      List<String> args =
          new ArrayList<>(List.of(loader, "--library-path", libraryPath, fastboot, "-s", serial));
      java.util.Collections.addAll(args, operation);
      return args;
    }
  }
}
