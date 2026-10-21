package com.deepseekharness.app.util;

/** 本机授权失效与远端退出分开表示；三个未知状态都禁止重新派发启动。 */
public enum VirtualScreenLifecycleState {
  STOPPED,
  STARTING,
  READY,
  START_UNKNOWN,
  DISCONNECTED,
  CLOSING,
  CLOSE_UNKNOWN;

  public boolean cleanupPending() {
    return this == START_UNKNOWN
        || this == DISCONNECTED
        || this == CLOSING
        || this == CLOSE_UNKNOWN;
  }
}
