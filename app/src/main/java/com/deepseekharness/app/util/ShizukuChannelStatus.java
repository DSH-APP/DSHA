package com.deepseekharness.app.util;

/**
 * 纯逻辑：根据 Shizuku 客户端可观察的状态，决定授权卡片显示哪一步、主按钮做什么。
 *
 * <p>真机（TB321FU + Stellar 1.1）核实：Stellar 只装在机主 user 0，但它的服务端会把兼容 Binder 推送给 应用分身（user
 * 900/901）里的 DSHA；分身里点「授权」时 Stellar 在分身用户启动授权页， 管理器不在该用户，于是静默失败、没有任何回调。
 * 这种情况必须提示到机主空间的管理器里授权，而不是反复发起注定超时的请求。
 */
public final class ShizukuChannelStatus {
  /** Android 每个用户的 UID 区间（UserHandle.PER_USER_RANGE）。 */
  public static final int PER_USER_RANGE = 100_000;

  /** 最近一次用户点击授权的结果；只用于文案，不参与任何权限判定。 */
  public enum Outcome {
    NONE,
    DENIED,
    TIMEOUT
  }

  public enum Step {
    /** 当前用户看不到任何管理器，也没有收到服务 Binder。 */
    INSTALL,
    /** 当前用户能看到管理器，服务 Binder 尚未送达：需要启动服务 / 重新连接。 */
    CONNECT,
    /** 分身或工作资料中既无管理器也无 Binder：需在机主空间启动服务，且管理器需覆盖本用户。 */
    PROFILE_NO_SERVICE,
    /** Binder 已送达、未授权，且授权页可以在当前用户弹出：点击后发起单次请求。 */
    REQUEST,
    /** Binder 已送达、未授权，但当前用户没有管理器：授权页弹不出来，只能去机主空间的管理器里授权。 */
    AUTHORIZE_IN_OWNER,
    /** 已授权，正在绑定设备 Shell。 */
    BINDING,
    /** 已授权，绑定失败（有错误信息）。 */
    BIND_FAILED,
    /** 已授权且设备 Shell 已连接。 */
    READY
  }

  private ShizukuChannelStatus() {}

  /** uid → Android 用户号；负数视为未知，按机主处理以免误判成分身。 */
  public static int userIdOf(int uid) {
    return uid < 0 ? 0 : uid / PER_USER_RANGE;
  }

  /** 非 0 用户（应用分身、工作资料、次要用户）。 */
  public static boolean isProfileUser(int uid) {
    return userIdOf(uid) != 0;
  }

  /**
   * @param binderAlive Shizuku.pingBinder()
   * @param granted Shizuku.checkSelfPermission() 的真实结果（由服务端按 uid 判定）
   * @param bound 设备 Shell UserService 已连接
   * @param bindFailed 已授权但绑定报错
   * @param managerVisible 当前用户能解析到管理器包
   * @param profileUser 当前进程不在 user 0
   */
  public static Step step(
      boolean binderAlive,
      boolean granted,
      boolean bound,
      boolean bindFailed,
      boolean managerVisible,
      boolean profileUser) {
    if (!binderAlive) {
      if (managerVisible) return Step.CONNECT;
      return profileUser ? Step.PROFILE_NO_SERVICE : Step.INSTALL;
    }
    if (!granted) return managerVisible ? Step.REQUEST : Step.AUTHORIZE_IN_OWNER;
    if (bound) return Step.READY;
    return bindFailed ? Step.BIND_FAILED : Step.BINDING;
  }

  /** 只有 REQUEST 步骤允许在用户点击后发起 Shizuku.requestPermission；其余步骤发起只会静默超时。 */
  public static boolean canRequestPermission(Step step) {
    return step == Step.REQUEST;
  }

  /** 已授权但未连接时，可以在界面恢复时补一次绑定（不是申请权限）。 */
  public static boolean shouldEnsureBound(Step step) {
    return step == Step.BINDING || step == Step.BIND_FAILED;
  }
}
