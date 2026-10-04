package com.deepseekharness.app.util;

/**
 * 把任意字符串安全地变成一个 shell 参数（POSIX 单引号法）。
 *
 * <p>为什么值得单独成一个类：这里是一条<b>安全边界</b>。插件名、仓库地址、路径这些值
 * 有相当一部分来自插件市场索引或用户输入，最终会拼进 {@code bash -c} 的命令串里跑进
 * 容器。转义写错一次，恶意或畸形的市场条目就能在 rootfs 里执行任意命令。
 *
 * <p>它原先内联在 {@code HarnessController} 的插件段里，插件那块搬进
 * {@code PluginController} 时如果不抽出来，两边就会各自留一份拷贝 —— 那正是本项目
 * 反复栽跟头的模式（同一份判断散落两处，改了一处忘了另一处）。抽成纯逻辑类之后，
 * JVM 单元测试也能直接对它下断言。
 */
public final class ShellQuote {

  private ShellQuote() {}

  /**
   * 单引号包裹，内部的单引号按 POSIX 惯例拆成 {@code '\''}。
   *
   * <p>单引号内除了单引号本身，shell 不做任何解释 —— {@code $}、反引号、{@code ;}、
   * 换行、通配符全部按字面量传递，所以只要正确处理单引号这一个字符就够了。
   *
   * <p>{@code null} 给出空参数 {@code ''} 而不是抛异常：调用点大多在拼命令串的中间，
   * 抛异常会把整条操作打断，而空参数会让命令自己失败并带上可读的错误输出。
   */
  public static String arg(String v) {
    if (v == null) return "''";
    return "'" + v.replace("'", "'\\''") + "'";
  }
}
