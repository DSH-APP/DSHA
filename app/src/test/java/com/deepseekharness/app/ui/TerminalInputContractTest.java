package com.deepseekharness.app.ui;

import static org.junit.Assert.*;
import com.termux.terminal.KeyHandler;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.charset.StandardCharsets;
import javax.xml.parsers.DocumentBuilderFactory;
import org.junit.Test;
import org.w3c.dom.Element;

/** 输入依赖行为与生产接线合同；不冒充 Android 真机交互测试。 */
public class TerminalInputContractTest {
  private static Path root() {
    Path path = Path.of("").toAbsolutePath();
    while (path != null && !Files.isRegularFile(path.resolve("app/src/main/AndroidManifest.xml")))
      path = path.getParent();
    if (path == null) throw new AssertionError("Cannot locate DSHA source tree");
    return path;
  }

  private static String source(String name) throws Exception {
    return Files.readString(
            root().resolve("app/src/main/java/com/deepseekharness/app/ui/" + name + ".java"),
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
  public void applicationCursorKeysUseNativeDependencyModes() {
    // Android key values are the public platform protocol constants, no Android runtime needed.
    assertEquals("\u001b[A", KeyHandler.getCode(19, 0, false, false));
    assertEquals("\u001bOA", KeyHandler.getCode(19, 0, true, false));
    assertEquals("\u001bOB", KeyHandler.getCode(20, 0, true, false));
    assertEquals("\u001bOD", KeyHandler.getCode(21, 0, true, false));
    assertEquals("\u001bOC", KeyHandler.getCode(22, 0, true, false));
    assertEquals("\u001b[1;5A", KeyHandler.getCode(19, KeyHandler.KEYMOD_CTRL, true, false));
    assertEquals("\u001b[1;3D", KeyHandler.getCode(21, KeyHandler.KEYMOD_ALT, false, false));
  }

  @Test
  public void specialKeysAndCodePointsConsumeModifiersAfterSnapshot() throws Exception {
    String pty = source("PtyTerminalFragment");
    String key = method(pty, "boolean onKeyDown(int keyCode");
    assertTrue(key.contains("KeyHandler.getCode"));
    assertTrue(key.contains("isCursorKeysApplicationMode()"));
    assertTrue(key.contains("isKeypadApplicationMode()"));
    assertTrue(key.contains("session.write(sequence); clearModifiers()"));
    String cp = method(pty, "boolean onCodePoint(int cp");
    assertTrue(cp.contains("clearModifiers()"));
    assertFalse(method(pty, "boolean readControlKey()").contains("clear"));
    assertFalse(method(pty, "boolean readAltKey()").contains("clear"));
    assertTrue(pty.contains("setOnFocusChangeListener"));
    assertTrue(method(pty, "void onPause()").contains("clearModifiers()"));
  }

  @Test
  public void clipboardConfirmationIsIdentityBoundAndUsesEmulatorPaste() throws Exception {
    String paste = method(source("PtyTerminalFragment"), "void confirmPaste(");
    assertTrue(paste.contains("TerminalInput.preparePaste"));
    assertTrue(paste.contains("new DshaDialogBuilder"));
    assertTrue(paste.contains("TerminalInput.sameTarget"));
    assertTrue(paste.contains("emulator.paste(prepared.text)"));
    assertFalse(paste.contains(".write("));
    assertTrue(source("PtyTerminalFragment").contains("BUTTON_TERTIARY"));
  }

  @Test
  public void simplePasteOnlyEditsDraftAndEnterDispatchesOnce() throws Exception {
    String simple = source("TerminalFragment");
    String paste = method(simple, "void paste()");
    assertTrue(paste.contains("TerminalInput.sameTarget"));
    assertTrue(paste.replace(" ", "").contains("inputEdit.getText().replace"));
    assertFalse(paste.contains(".submit("));
    assertFalse(paste.contains("sendCommand()"));
    String created = method(simple, "void onViewCreated(");
    assertTrue(
        created.contains(
            "event.getAction() == KeyEvent.ACTION_DOWN && event.getRepeatCount() == 0"));
    assertTrue(method(simple, "void confirmRestart()").contains("new DshaDialogBuilder"));
    assertTrue(method(simple, "void confirmRestart()").contains("cancelAndRestart()"));
  }

  @Test
  public void multilineSimpleDraftHasExplicitSendAndNativeImeAction() throws Exception {
    String simple = source("TerminalFragment");
    String connection = method(simple, "InputConnection onCreateInputConnection(");
    assertTrue(connection.contains("super.onCreateInputConnection(attributes)"));
    assertTrue(connection.contains("~EditorInfo.IME_FLAG_NO_ENTER_ACTION"));
    assertTrue(connection.contains("EditorInfo.IME_ACTION_SEND"));
    assertTrue(simple.contains("sendButton.setOnClickListener(v -> sendCommand())"));
    assertTrue(simple.contains("findViewById(R.id.term_send).setEnabled(allowed)"));
    var factory = DocumentBuilderFactory.newInstance();
    factory.setNamespaceAware(true);
    var doc =
        factory
            .newDocumentBuilder()
            .parse(root().resolve("app/src/main/res/layout/fragment_terminal.xml").toFile());
    String ns = "http://schemas.android.com/apk/res/android";
    var texts = doc.getElementsByTagName("TextView");
    Element send = null;
    for (int i = 0; i < texts.getLength(); i++) {
      Element candidate = (Element) texts.item(i);
      if ("@+id/term_send".equals(candidate.getAttributeNS(ns, "id"))) send = candidate;
    }
    assertNotNull(send);
    assertEquals("48dp", send.getAttributeNS(ns, "minHeight"));
    assertEquals("48dp", send.getAttributeNS(ns, "minWidth"));
    assertFalse(send.hasAttributeNS(ns, "visibility"));
  }

  @Test
  public void lifecycleOnlyDetachesAndCancelsUnstartedCreation() throws Exception {
    for (String name : new String[] {"PtyTerminalFragment", "TerminalFragment"}) {
      String value = source(name);
      for (String lifecycle : new String[] {"void onPause()", "void onDestroyView()"}) {
        String body = method(value, lifecycle);
        assertFalse(body.contains("shutdown"));
        assertFalse(body.contains("closeSession"));
        assertFalse(body.contains("cancelAndRestart"));
        assertFalse(body.contains(".finish("));
      }
      assertTrue(method(value, "void onPause()").contains("creating.cancel()"));
      assertTrue(value.contains("terminal_skip_initial_create"));
      assertTrue(value.contains("requestFromVisible"));
      assertTrue(
          value.contains("implements TerminalPane") || value.contains("implements TerminalPane,"));
    }
  }

  @Test
  public void staleNativeViewsCannotDeliverOldImeToCurrentSession() throws Exception {
    String pty = source("PtyTerminalFragment");
    assertFalse(pty.contains("extends TerminalView")); // upstream class is final
    assertTrue(pty.contains("class NativeClient implements TerminalViewClient"));
    assertTrue(pty.contains("active && view == target && inputAllowed()"));
    assertTrue(method(pty, "void replaceNativeView()").contains("nativeClient.active = false"));
    assertTrue(method(pty, "void onPause()").contains("nativeClient.active = false"));
    assertTrue(pty.contains("OWNER.isPtyInputAllowed"));
    assertTrue(source("TerminalFragment").contains("OWNER.isSimpleInputAllowed"));
  }

  /** T12：逐码点输入与切换会话只读缓存结论，不在主线程反复重算运行时描述符（粘贴 / 关闭标签 ANR）。 */
  @Test
  public void ptyHotPathsReadCachedReadinessOnly() throws Exception {
    String pty = source("PtyTerminalFragment");
    assertTrue(method(pty, "boolean inputAllowed()").contains("readiness.ready()"));
    assertTrue(method(pty, "void attachSelected()").contains("readiness.ready()"));
    assertFalse(pty.contains("isEnvironmentReady()"));
    String cache = source("TerminalReadiness");
    // 全量检查仍是唯一结论来源；维护进行中不得复用缓存。
    assertTrue(cache.contains("proot::isEnvironmentReady"));
    assertTrue(cache.contains("task.maintenanceBusy() ? 1 : 0"));
  }

  @Test
  public void defaultContentHasNoPersistentTitleOrTabChrome() throws Exception {
    for (String name : new String[] {"fragment_pty_terminal", "fragment_terminal"}) {
      var builder = DocumentBuilderFactory.newInstance();
      builder.setNamespaceAware(true);
      var doc =
          builder
              .newDocumentBuilder()
              .parse(root().resolve("app/src/main/res/layout/" + name + ".xml").toFile());
      Element outer = doc.getDocumentElement();
      String ns = "http://schemas.android.com/apk/res/android";
      assertEquals("@color/terminal_surface", outer.getAttributeNS(ns, "background"));
      assertFalse(outer.hasAttributeNS(ns, "paddingStart"));
      var includes = doc.getElementsByTagName("include");
      assertEquals(1, includes.getLength());
      assertEquals(
          "gone", ((Element) includes.item(0).getParentNode()).getAttributeNS(ns, "visibility"));
    }
    String pty = source("PtyTerminalFragment");
    // 附加键已改为可配置模型：默认布局在 TerminalExtraKeys，视图只负责等宽单行渲染。
    assertTrue(pty.contains("row.setMinimumWidth(dp(44 * actions.size()))"));
    assertTrue(pty.contains("dp(48)"));
    assertTrue(pty.contains("button.setSingleLine(true)"));
    assertTrue(pty.contains("setAutoSizeTextTypeUniformWithConfiguration"));
    assertTrue(pty.contains("TerminalExtraKeys.decode("));
    assertTrue(pty.contains("TerminalExtraKeys.label(action)"));
    assertFalse(pty.contains("String[][] rows = {"));
  }

  @Test
  public void closeActionsAreHostConfirmedRatherThanHiddenDirectClosures() throws Exception {
    for (String name : new String[] {"PtyTerminalFragment", "TerminalFragment"}) {
      String value = source(name);
      assertTrue(value.contains(".requestCloseSession("));
      assertTrue(
          method(value, "void closeSession(long id)").replace(" ", "").contains("Async(id,5000"));
      assertFalse(method(value, "void closeSession(long id)").contains("beginClose"));
    }
  }
}
