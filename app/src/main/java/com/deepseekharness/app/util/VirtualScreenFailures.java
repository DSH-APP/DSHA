package com.deepseekharness.app.util;

import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Set;

/**
 * 虚拟屏 core 的稳定错误码与根因摘要。
 *
 * <p>对外错误码只表达「发生了什么、下一步做什么」，绝不把异常类名当语义：
 * {@code VSCREEN_NoSuchMethodException} 这种外泄对调用方没有任何可操作信息（issue #113）。
 * 但根因摘要必须一起给出——{@code NoSuchMethodException} 的 message 里本来就写着缺失的类名
 * 与方法签名，只留类名等于把唯一能定位的信息扔掉。
 *
 * <p>与 #134 的 {@code VirtualScreenErrors} 职责重叠：若那份先合入，本类应与它合并成一份。
 */
public final class VirtualScreenFailures {
  private VirtualScreenFailures() {}

  public static final String PERMISSION_DENIED = "VSCREEN_PERMISSION_DENIED";
  public static final String DISPLAY_SERVICE_UNAVAILABLE = "VSCREEN_DISPLAY_SERVICE_UNAVAILABLE";
  public static final String NATIVE_API_UNAVAILABLE = "VSCREEN_NATIVE_API_UNAVAILABLE";
  public static final String INVALID_REQUEST = "VSCREEN_INVALID_DISPLAY_REQUEST";
  public static final String DISPLAY_STATE = "VSCREEN_DISPLAY_STATE";
  public static final String DISPLAY_IO = "VSCREEN_DISPLAY_IO";
  public static final String OPERATION_FAILED = "VSCREEN_OPERATION_FAILED";

  /** 根因摘要上限：够放下一个方法签名，又不至于把响应与日志刷爆。 */
  public static final int MAX_CAUSE = 200;

  /**
   * 稳定码：**先认最深层根因**——issue #113 里真正可操作的失败被包装在
   * {@code IllegalStateException}/{@code InvocationTargetException} 里面，
   * 只看最外层会把它误报成「状态不对」。根因认不出来时，再沿链取第一个能识别的类别。
   */
  public static String code(Throwable error) {
    List<Throwable> chain = chain(error);
    if (chain.isEmpty()) return OPERATION_FAILED;
    String root = classify(chain.get(chain.size() - 1));
    if (root != null) return root;
    for (Throwable current : chain) {
      String classified = classify(current);
      if (classified != null) return classified;
    }
    return OPERATION_FAILED;
  }

  /**
   * 根因摘要：最深层原因的类名 + message，折叠空白、过脱敏、截断到 {@link #MAX_CAUSE}。
   * 只有类名没有 message 时返回类名本身；没有异常时返回空串。
   */
  public static String cause(Throwable error) {
    Throwable root = null;
    for (Throwable current : chain(error)) root = current;
    if (root == null) return "";
    String message = root.getMessage();
    String text =
        message == null || message.isBlank()
            ? root.getClass().getSimpleName()
            : root.getClass().getSimpleName() + ": " + message;
    text = SensitiveData.redact(text).replaceAll("\\s+", " ").trim();
    return text.length() > MAX_CAUSE ? text.substring(0, MAX_CAUSE) + "…" : text;
  }

  /** 单个异常的类别；认不出来返回 null，由调用方决定回退顺序。 */
  private static String classify(Throwable error) {
    if (error instanceof NoSuchMethodException
        || error instanceof NoSuchFieldException
        || error instanceof ClassNotFoundException
        || error instanceof LinkageError) return NATIVE_API_UNAVAILABLE;
    if (error instanceof SecurityException) return PERMISSION_DENIED;
    String name = error.getClass().getSimpleName();
    if (name.equals("RemoteException") || name.equals("DeadObjectException"))
      return DISPLAY_SERVICE_UNAVAILABLE;
    if (error instanceof IllegalArgumentException) return INVALID_REQUEST;
    if (error instanceof IllegalStateException) return DISPLAY_STATE;
    if (error instanceof java.io.IOException) return DISPLAY_IO;
    return null;
  }

  /** 有界、去环的异常链，保证坏异常（自指 cause）不会把调用方转死。 */
  private static List<Throwable> chain(Throwable error) {
    List<Throwable> chain = new ArrayList<>();
    Set<Throwable> seen = Collections.newSetFromMap(new IdentityHashMap<>());
    for (Throwable current = error; current != null && chain.size() < 16 && seen.add(current);
        current = current.getCause()) chain.add(current);
    return chain;
  }
}
