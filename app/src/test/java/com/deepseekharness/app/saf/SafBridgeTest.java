package com.deepseekharness.app.saf;

import static org.junit.Assert.*;

import android.app.Application;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ProviderInfo;
import android.net.Uri;
import java.io.File;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.HashSet;
import java.util.Properties;
import java.util.Set;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowContentResolver;

/**
 * /app/saf/* 桥接端点的权限门与参数校验。
 *
 * <p>HttpShellService 的 SAF handler 是私有的，这里用反射构造最薄的 BridgeRequest 直接调用，
 * 覆盖 agent 真正会撞到的分支：未知/失效授权、只读授权、写开关关闭、rm 的 confirm 与空路径、
 * push/pull 的绝对路径要求。Provider 数据面由 {@link SafTreeTest} 覆盖。
 */
@RunWith(SafBridgeTest.NoApkRunner.class)
@Config(
    sdk = 34,
    manifest = Config.NONE,
    application = Application.class,
    shadows = {SafTestOs.FakeOs.class, SafTestOs.FakePfd.class})
public final class SafBridgeTest {
  private static final String TREE = "content://test.dsha.safbridge/tree/ROOT";

  public static final class NoApkRunner extends RobolectricTestRunner {
    public NoApkRunner(Class<?> type) throws org.junit.runners.model.InitializationError {
      super(type);
    }

    @Override
    protected Properties getBuildSystemApiProperties() {
      return new Properties();
    }
  }

  private Application app;
  private HttpShellBridge bridge;
  private Uri tree;
  private SafTreeTest.FakeProvider provider;
  private File rootfs;

  /** 反射包装：只调用 SAF handler，不启动监听。 */
  private static final class HttpShellBridge {
    final com.deepseekharness.app.HttpShellService service;
    final Constructor<?> requestConstructor;
    final Method safTree;
    final Method trees;
    final Method rm;
    final Method push;
    final Method pull;

    HttpShellBridge(Application app) throws Exception {
      service = new com.deepseekharness.app.HttpShellService(app);
      Class<?> request = Class.forName("com.deepseekharness.app.HttpShellService$BridgeRequest");
      Constructor<?> found = null;
      for (Constructor<?> candidate : request.getDeclaredConstructors())
        if (candidate.getParameterCount() == 8) found = candidate;
      assertNotNull(found);
      requestConstructor = found;
      requestConstructor.setAccessible(true);
      safTree = access("safTree", request, boolean.class);
      trees = access("appSafTrees");
      rm = access("appSafRemove", request);
      push = access("appSafPush", request);
      pull = access("appSafPull", request);
    }

    private static Method access(String name, Class<?>... types) throws Exception {
      Method method = com.deepseekharness.app.HttpShellService.class.getDeclaredMethod(name, types);
      method.setAccessible(true);
      return method;
    }

    Object request(String path) throws Exception {
      return requestConstructor.newInstance(path, "", "", false, null, null, null, null);
    }

    Object safTree(String path, boolean writable) throws Exception {
      try {
        return safTree.invoke(service, request(path), writable);
      } catch (InvocationTargetException wrapped) {
        throw (Exception) wrapped.getCause();
      }
    }
  }

  @Before
  public void setUp() throws Exception {
    app = RuntimeEnvironment.getApplication();
    SafTestOs.reset();
    SafLocalFiles.setDirectoryListerForTests(fd -> new String[0]);
    app.getSharedPreferences("saf_grants", Context.MODE_PRIVATE).edit().clear().commit();
    tree = Uri.parse(TREE);
    rootfs = Files.createTempDirectory("dsha-saf-bridge-").toFile();
    Files.createDirectories(new File(rootfs, "root").toPath());
    setRootfsFixture(rootfs);
    provider = new SafTreeTest.FakeProvider();
    provider.storage = new File(rootfs, "provider");
    Files.createDirectories(provider.storage.toPath());
    provider.treeUri = tree;
    provider.documents.put(
        "ROOT",
        new String[] {"", "root", android.provider.DocumentsContract.Document.MIME_TYPE_DIR, "0"});
    File existing = new File(provider.storage, "file.txt");
    Files.write(existing.toPath(), "hello".getBytes(StandardCharsets.UTF_8));
    provider.documents.put("file.txt", new String[] {"ROOT", "file.txt", "text/plain", "5"});
    provider.files.put("file.txt", existing);
    ProviderInfo info = new ProviderInfo();
    info.authority = tree.getAuthority();
    info.exported = true;
    info.grantUriPermissions = true;
    provider.attachInfo(app, info);
    ShadowContentResolver.registerProviderInternal(tree.getAuthority(), provider);
    bridge = new HttpShellBridge(app);
  }

  @After
  public void tearDown() throws Exception {
    SafLocalFiles.setDirectoryListerForTests(null);
    setRootfsFixture(null);
    try {
      app.getContentResolver()
          .releasePersistableUriPermission(
              tree, Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
    } catch (Throwable ignored) {
    }
  }

  private String register(boolean writablePref, int flags) {
    ContentResolver resolver = app.getContentResolver();
    resolver.takePersistableUriPermission(tree, flags);
    Set<String> saved = new HashSet<>();
    saved.add(tree.toString());
    app.getSharedPreferences("saf_grants", Context.MODE_PRIVATE)
        .edit()
        .putStringSet("uris", saved)
        .putBoolean("writable:" + tree, writablePref)
        .putString("acquired:" + tree, "rw")
        .putString(SafGrants.KEY_LABEL + tree, "Folder")
        .commit();
    return SafGrants.id(tree);
  }

  @Test
  public void treesReturnsRegisteredGrantWithoutProviderQueries() {
    String id =
        register(
            true, Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
    String json = (String) invoke(bridge.trees, bridge.service);
    assertTrue(json, json.contains(id));
    assertTrue(json, json.contains("\"readable\":true"));
    assertTrue(json, json.contains("\"writable\":true"));
    assertTrue(json, json.contains("\"valid\":true"));
    assertTrue(json, json.contains("Folder"));
  }

  @Test
  public void rmRequiresOneTimeTokenAndRejectsEmptyPath() {
    String id =
        register(
            true, Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
    String probe =
        (String)
            invoke(bridge.rm, bridge.service, req("/app/saf/rm?tree=" + id + "&path=file.txt"));
    assertTrue(probe, probe.startsWith("ERROR: NEEDS_CONFIRM"));
    java.util.regex.Matcher matcher =
        java.util.regex.Pattern.compile("confirm=([0-9a-f]+)").matcher(probe);
    assertTrue(probe, matcher.find());
    String token = matcher.group(1);

    String empty =
        (String)
            invoke(bridge.rm, bridge.service, req("/app/saf/rm?confirm=1&tree=" + id + "&path="));
    assertTrue(empty, empty.startsWith("ERROR: BAD_PATH"));
    String bypass =
        (String)
            invoke(
                bridge.rm,
                bridge.service,
                req("/app/saf/rm?confirm=1&tree=" + id + "&path=file.txt"));
    assertTrue("已有 pending 时 confirm=1 必须要求 token", bypass.startsWith("ERROR: CONFIRM_REQUIRED"));
    String wrong =
        (String)
            invoke(
                bridge.rm,
                bridge.service,
                req("/app/saf/rm?tree=" + id + "&path=file.txt&confirm=deadbeef"));
    assertTrue(wrong, wrong.startsWith("ERROR: CONFIRM_REQUIRED"));
    String confirmed =
        (String)
            invoke(
                bridge.rm,
                bridge.service,
                req("/app/saf/rm?tree=" + id + "&path=file.txt&confirm=" + token));
    assertTrue(confirmed, confirmed.contains("\"ok\":true"));
    String consumed =
        (String)
            invoke(
                bridge.rm,
                bridge.service,
                req("/app/saf/rm?tree=" + id + "&path=file.txt&confirm=" + token));
    assertTrue(consumed, consumed.startsWith("ERROR: CONFIRM_REQUIRED"));
  }

  @Test
  public void confirmOneIsAlwaysRejected() {
    String id =
        register(
            true, Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
    SafTree.clearPendingDeletesForTests();
    String confirmOne =
        (String)
            invoke(
                bridge.rm,
                bridge.service,
                req("/app/saf/rm?confirm=1&tree=" + id + "&path=file.txt"));
    assertTrue("confirm=1 一律 CONFIRM_REQUIRED", confirmOne.startsWith("ERROR: CONFIRM_REQUIRED"));
  }

  @Test
  public void unknownRevokedReadOnlyAndWriteDisabledGrantsAreRefused() throws Exception {
    SafTree.Failure unknown =
        assertThrows(
            SafTree.Failure.class, () -> bridge.safTree("/app/saf/ls?tree=deadbeef&path=", false));
    assertEquals("NO_GRANT", unknown.code);

    String id =
        register(
            true, Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
    app.getContentResolver()
        .releasePersistableUriPermission(
            tree, Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
    SafTree.Failure revoked =
        assertThrows(
            SafTree.Failure.class,
            () -> bridge.safTree("/app/saf/ls?tree=" + id + "&path=", false));
    assertEquals("PERMISSION_DENIED", revoked.code);

    register(true, Intent.FLAG_GRANT_READ_URI_PERMISSION);
    SafTree.Failure readOnlyWrite =
        assertThrows(
            SafTree.Failure.class,
            () -> bridge.safTree("/app/saf/push?tree=" + id + "&path=x&src=/root/x", true));
    assertEquals("PERMISSION_DENIED", readOnlyWrite.code);
    // 读操作仍可用（返回 SafTree 实例而不是抛错）。
    assertNotNull(bridge.safTree("/app/saf/ls?tree=" + id + "&path=", false));

    register(false, Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
    SafTree.Failure disabled =
        assertThrows(
            SafTree.Failure.class,
            () -> bridge.safTree("/app/saf/push?tree=" + id + "&path=x&src=/root/x", true));
    assertEquals("READ_ONLY", disabled.code);
  }

  @Test
  public void pushAndPullRequireAbsoluteContainerPaths() {
    String id =
        register(
            true, Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
    String push =
        (String)
            invoke(
                bridge.push,
                bridge.service,
                req("/app/saf/push?tree=" + id + "&path=files/x&src=relative.txt"));
    assertTrue(push, push.contains("ABSOLUTE_ONLY"));
    String pull =
        (String)
            invoke(
                bridge.pull,
                bridge.service,
                req("/app/saf/pull?tree=" + id + "&path=files/x&dest=relative.txt"));
    assertTrue(pull, pull.contains("ABSOLUTE_ONLY"));
  }

  private static void setRootfsFixture(File value) throws Exception {
    Field field =
        com.deepseekharness.app.HttpShellService.class.getDeclaredField("safRootfsFixture");
    field.setAccessible(true);
    field.set(null, value);
  }

  @Test
  public void pushSucceedsThroughFixtureRootfs() throws Exception {
    String id =
        register(
            true, Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
    File source = new File(rootfs, "root/src.txt");
    Files.write(source.toPath(), "hello".getBytes(StandardCharsets.UTF_8));
    String json =
        (String)
            invoke(
                bridge.push,
                bridge.service,
                req("/app/saf/push?tree=" + id + "&path=new.txt&src=/root/src.txt"));
    assertTrue(json, json.contains("\"ok\":true"));
    File created = provider.files.get(provider.lastCreatedId);
    assertNotNull(created);
    assertEquals("hello", new String(Files.readAllBytes(created.toPath()), StandardCharsets.UTF_8));
  }

  @Test
  public void pullSucceedsThroughFixtureRootfs() throws Exception {
    String id =
        register(
            true, Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
    String json =
        (String)
            invoke(
                bridge.pull,
                bridge.service,
                req("/app/saf/pull?tree=" + id + "&path=file.txt&dest=/root/out.txt"));
    assertTrue(json, json.contains("\"ok\":true"));
    File pulled = new File(rootfs, "root/out.txt");
    assertTrue(pulled.isFile());
    assertEquals("hello", new String(Files.readAllBytes(pulled.toPath()), StandardCharsets.UTF_8));
  }

  @Test
  public void repeatedPushesLeaveNoContainerFileDescriptors() throws Exception {
    String id =
        register(
            true, Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
    File source = new File(rootfs, "root/loop.txt");
    Files.write(source.toPath(), "x".getBytes(StandardCharsets.UTF_8));
    int baseline = SafTestOs.RAW_HANDLES.size();
    for (int i = 0; i < 100; i++) {
      String json =
          (String)
              invoke(
                  bridge.push,
                  bridge.service,
                  req("/app/saf/push?tree=" + id + "&path=new.txt&src=/root/loop.txt"));
      assertTrue(json, json.contains("\"ok\":true"));
      int current = SafTestOs.RAW_HANDLES.size();
      assertTrue("第 " + i + " 次 push 后容器 fd 从 " + baseline + " 增到 " + current, current == baseline);
    }
    assertTrue(
        "100 次 push 后容器 fd 必须回基线: raw="
            + SafTestOs.RAW_HANDLES.size()
            + " files="
            + SafTestOs.RAW_FILES.size(),
        SafTestOs.RAW_HANDLES.size() == baseline);
  }

  @Test
  public void pushMissingSourceFails() {
    String id =
        register(
            true, Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
    String json =
        (String)
            invoke(
                bridge.push,
                bridge.service,
                req("/app/saf/push?tree=" + id + "&path=new.txt&src=/root/missing.txt"));
    assertTrue(json, json.startsWith("ERROR:"));
  }

  private Object req(String path) {
    try {
      return bridge.request(path);
    } catch (Exception error) {
      throw new AssertionError(error);
    }
  }

  private static Object invoke(Method method, Object target, Object... args) {
    try {
      return method.invoke(target, args);
    } catch (InvocationTargetException wrapped) {
      Throwable cause = wrapped.getCause();
      if (cause instanceof RuntimeException) throw (RuntimeException) cause;
      throw new AssertionError(cause);
    } catch (ReflectiveOperationException error) {
      throw new AssertionError(error);
    }
  }
}
