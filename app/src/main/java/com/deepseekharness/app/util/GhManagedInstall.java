package com.deepseekharness.app.util;

/**
 * 受管 GitHub CLI 的固定输入与 guest 路径。
 *
 * <p>这些常量必须与 {@code tools/gh-cli/packages.lock.json} 及 {@code app/src/main/assets/gh-wrapper.sh}
 * 的 pin 完全一致；{@code tools/test-gh-provision-contract.py} 逐项核对，任一漂移在打包前失败。
 * 本类只描述事实，不读取文件、不执行命令，也不保存任何凭据。
 */
public final class GhManagedInstall {
  /** 本页只管理 GitHub.com；其他主机只作为提示，不提供登录或退出。 */
  public static final String HOST = "github.com";

  /** 受管 gh 二进制在运行根内的相对路径。 */
  public static final String BIN = "opt/gh/bin/gh";

  /** gh CLI 的包装入口（负责自举、代理与 GH_CONFIG_DIR）。 */
  public static final String WRAPPER = "root/dsh-bin/gh";

  /** gh 写登录状态的文件；本页只读取其中的非密钥元数据。 */
  public static final String HOSTS = "root/.config/gh/hosts.yml";

  /** 固定输入版本（与发布归档成员一致）。 */
  public static final String VERSION = "2.103.3";

  /** 固定输入二进制的字节数；包装脚本用同一数值做快速完整性检查。 */
  public static final long BINARY_BYTES = 38_142_112L;

  /** 固定输入二进制的 SHA-256（完整校验时使用，日志与 UI 不输出文件内容）。 */
  public static final String BINARY_SHA256 =
      "a82a52e1bbe05bcd485f8cbbd5aa67e11a76fce6bdad394a2d179e1c24cfe367";

  /** hosts.yml 读取上限；gh 的实际文件只有几百字节。 */
  public static final int MAX_HOSTS_BYTES = 64 * 1024;

  /** 受管 gh 的可用性；{@link #ENV_NOT_READY} 时不检查文件本身。 */
  public enum State {
    ENV_NOT_READY,
    AVAILABLE,
    MISSING,
    SIZE_MISMATCH,
    NOT_REGULAR,
    UNREADABLE
  }

  private GhManagedInstall() {}

  /**
   * 纯判定受管二进制状态；文件 IO 由调用方完成并保证路径在运行根内。
   *
   * @param environmentReady 运行环境是否就绪（未就绪时不把“文件缺失”当成损坏）
   * @param exists 路径是否存在
   * @param regularFile 存在时是否为普通文件（不跟随符号链接）
   * @param bytes 普通文件的字节数
   */
  public static State state(
      boolean environmentReady, boolean exists, boolean regularFile, long bytes) {
    if (!environmentReady) return State.ENV_NOT_READY;
    if (!exists) return State.MISSING;
    if (!regularFile) return State.NOT_REGULAR;
    return bytes == BINARY_BYTES ? State.AVAILABLE : State.SIZE_MISMATCH;
  }
}
