package com.deepseekharness.app.util;

import java.util.List;

/**
 * 「导出可在电脑上继续使用的数据包」的<b>唯一</b>策略真源（纯逻辑，不碰 Android）。
 *
 * <p>手机上的 {@code $DSH_HOME} 里混着三类东西：用户数据（会话 / 附件 / 设置层）、
 * DSHA 的随包资产（{@code *.py} / {@code *.cjs} / 受管运行时契约）、以及设备凭据
 * （{@code .bridge_token} / {@code .credentials.yaml} 里的浏览器会话密钥）。
 * 搬到电脑上以后，后两类不是「冗余」而是<b>有害</b>：随包脚本会让 PC 端的 dsh 起不来，
 * 桥凭据送出去等于交出设备控制权。所以这里用<b>白名单</b>：只有 {@link #included()}
 * 列出的路径进包，别的一律不进 —— 不靠黑名单去猜还有什么该排除。
 *
 * <p>配套的离线实现与自检在 {@code tools/pc-migration/}：手机端
 * {@code dsha-portable.py}、电脑端 {@code dsha-portable-restore.mjs}、
 * 端到端断言 {@code selftest.mjs}。本类是该策略的 App 侧镜像，供界面与桥端点复用，
 * 不重复实现打包。
 */
public final class PortableExportPlan {

  /** 归档名前缀与扩展名；改名会让老版本的识别逻辑失效。 */
  public static final String ARCHIVE_PREFIX = "DSHA-portable-";

  public static final String ARCHIVE_EXTENSION = ".tar.gz";

  /** 便携包里的顶层条目：home 恢复进 $DSH_HOME，workspace 落到用户指定目录。 */
  public static final String HOME_DIR = "home";
  public static final String WORKSPACE_DIR = "workspace";
  public static final String MANIFEST_NAME = "manifest.json";

  /** 凭据文件名与「必须剔除」的记录前缀（浏览器会话签名密钥）。 */
  public static final String CREDENTIALS_FILE = ".credentials.yaml";

  public static final String MACHINE_RECORD_PREFIX = "client-connection/";

  /** 可重建、或带绝对路径与版本绑定的缓存；恢复端一律丢弃。 */
  public static final String PROJECTION_CACHE = "storages/session_projcache";

  private PortableExportPlan() {}

  /** 白名单：只有这些相对 {@code $DSH_HOME} 的路径进包。 */
  public static List<String> included() {
    return List.of(
        "sessions", "storages/workspace.json", "attachments", "profiles/%s/cordis.patch.yml",
        "llm-deepseek");
  }

  /** 排除清单及理由；理由要能直接进 manifest 与界面，不能只说「不需要」。 */
  public static List<String[]> excluded() {
    return List.of(
        new String[] {PROJECTION_CACHE, "投影缓存可重建；identity.cwd 是绝对路径，且 version 绑定 dsh 版本"},
        new String[] {"profiles/*/node_modules", "设备侧 pnpm 链接；PC 端由 PC 的 dsh 自己装"},
        new String[] {"profiles/*/package.json", "含 link:/root/dsha-* 设备绝对路径，PC 上必然失效"},
        new String[] {"node_modules", "DSHA 塞进 $DSH_HOME 的随包依赖；PC 端 dsh 自带"},
        new String[] {"cache", "可再生"},
        new String[] {".bridge_token / .bridge_headers / .bridge_status", "3090 桥的本机共享凭据；泄露即等于交出设备控制权"},
        new String[] {".anonymous-user-id", "harness-home 级匿名身份；迁移会让两台机器共用一个 id"},
        new String[] {".runtime-descriptor.json / .runtime-health.json", "设备侧受管运行时契约与健康回执"},
        new String[] {"*.py / *.cjs / builtin-plugins.json / runtime-tools.json", "全部是 DSHA 随包脚本与受管资产，不是用户数据"});
  }

  /** 这条相对路径会不会进包（白名单语义：认不出来就不进）。 */
  public static boolean selectable(String relativePath) {
    if (relativePath == null || relativePath.isEmpty()) return false;
    String normalized = relativePath.replace('\\', '/');
    if (normalized.startsWith("/") || normalized.contains("..")) return false;
    if (normalized.endsWith(CREDENTIALS_FILE)) return true; // 仍受凭据开关约束
    for (String rule : included()) {
      String prefix = rule.replace("%s", "");
      if (prefix.endsWith("/cordis.patch.yml")) {
        if (normalized.startsWith("profiles/") && normalized.endsWith("/cordis.patch.yml")) return true;
        continue;
      }
      if (normalized.equals(rule) || normalized.startsWith(rule + "/")) return true;
    }
    return false;
  }

  /** 凭据默认排除；用户显式要求时才带上，且仍要剔除 {@link #MACHINE_RECORD_PREFIX}。 */
  public static boolean credentialsIncludedByDefault() {
    return false;
  }

  public static boolean mustPruneCredentialRecord(String recordName) {
    return recordName != null && recordName.startsWith(MACHINE_RECORD_PREFIX);
  }

  /** 归档名；标识只允许安全字符，避免路径穿越。 */
  public static String archiveName(String identifier) {
    if (identifier == null || !identifier.matches("[A-Za-z0-9][A-Za-z0-9._-]{0,80}"))
      throw new IllegalArgumentException(UiText.text("便携包标识无效"));
    return ARCHIVE_PREFIX + identifier + ARCHIVE_EXTENSION;
  }

  /** 包里附带的中文恢复说明；内容是给用户照着做的，不允许只写「见文档」。 */
  public static String readme(
      String dshVersion, int sessionCount, boolean credentialsIncluded, String workspacePath) {
    String credentials =
        credentialsIncluded
            ? UiText.text(
                "本包包含 .credentials.yaml，但已字段级剔除 client-connection/*（浏览器会话密钥）；留下的只有你自己的 API Key 引用，权限已设为 600。")
            : UiText.text("本包不含 .credentials.yaml —— 到电脑上第一次启动 dsh 时重新填一次 API Key。");
    return UiText.text("在电脑上继续用这份数据")
        + "\n\n"
        + UiText.format("手机上的 dsh 版本：%s；会话 %d 个。", dshVersion, sessionCount)
        + "\n\n"
        + UiText.text("1. 电脑上安装同版本 dsh：npm install -g @deepseek-ai/dsh")
        + "\n"
        + UiText.text("2. 先空跑看它打算干什么（默认不改文件）：node dsha-portable-restore.mjs plan --package <包名>")
        + "\n"
        + UiText.text("3. 确认后执行：node dsha-portable-restore.mjs apply --package <包名> --workspace-dir <目录>")
        + "\n"
        + UiText.text("4. 起界面：dsh web --no-open")
        + "\n\n"
        + credentials
        + "\n\n"
        + UiText.format(
            "会话日志里记着手机上的绝对工作目录；电脑上要「接着聊」而不是只看历史，请在恢复时加 --rewrite-cwd。手机上的工作区：%s",
            workspacePath == null || workspacePath.isEmpty() ? UiText.text("（未导出）") : workspacePath);
  }

  /** 归档名是否像本方案产出的包（桥端点与界面做特性检测用）。 */
  public static boolean looksLikePortableArchive(String name) {
    return name != null
        && name.startsWith(ARCHIVE_PREFIX)
        && name.endsWith(ARCHIVE_EXTENSION)
        && name.length() > ARCHIVE_PREFIX.length() + ARCHIVE_EXTENSION.length();
  }
}
