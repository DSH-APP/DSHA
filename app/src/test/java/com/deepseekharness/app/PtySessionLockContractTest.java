package com.deepseekharness.app;

import static org.junit.Assert.*;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.Test;

/**
 * T14：关闭 PTY 时 finishAndWait 持有会话监视器最长数秒；主线程调用的监听 / 输入问题方法
 * 必须走独立锁，否则关闭标签即 ANR，用户点「关闭应用」会连同 DSH 环境一起结束。
 */
public class PtySessionLockContractTest {
  private static String source() throws Exception {
    Path path = Path.of("").toAbsolutePath();
    while (path != null && !Files.isRegularFile(path.resolve("app/src/main/AndroidManifest.xml")))
      path = path.getParent();
    if (path == null) throw new AssertionError("Cannot locate DSHA source tree");
    return Files.readString(
            path.resolve("app/src/main/java/com/deepseekharness/app/PtySession.java"),
            StandardCharsets.UTF_8)
        .replaceAll("\\s+", " ");
  }

  private static String method(String source, String signature) {
    int start = source.indexOf(signature);
    assertTrue("Missing method " + signature, start >= 0);
    int open = source.indexOf('{', start), level = 1, end = open + 1;
    for (; end < source.length() && level != 0; end++) {
      if (source.charAt(end) == '{') level++;
      if (source.charAt(end) == '}') level--;
    }
    return source.substring(open + 1, end - 1);
  }

  @Test
  public void mainThreadMethodsDoNotShareTheCloseMonitor() throws Exception {
    String pty = source();
    // 关闭核验仍在会话监视器内串行。
    assertTrue(pty.contains("public synchronized void finishAndWait(long timeoutMs)"));
    for (String signature :
        new String[] {
          "void attachListener(Listener l)",
          "int inputProblem()",
          "void acknowledgeInputProblem(int reason)",
          "void onInputError(TerminalSession source, int reason)"
        }) {
      assertFalse(signature, pty.contains("synchronized " + signature));
      assertTrue(signature, method(pty, signature).contains("synchronized (inputState)"));
    }
  }

  @Test
  public void listenerCallbacksRunOutsideTheStateLock() throws Exception {
    String pty = source();
    for (String signature :
        new String[] {
          "void attachListener(Listener l)", "void onInputError(TerminalSession source, int reason)"
        }) {
      String body = method(pty, signature);
      int lock = body.indexOf("synchronized (inputState)");
      int lockEnd = body.indexOf('}', lock);
      int callback = body.indexOf("onInputProblem(");
      assertTrue(signature, callback > lockEnd);
    }
  }
}
