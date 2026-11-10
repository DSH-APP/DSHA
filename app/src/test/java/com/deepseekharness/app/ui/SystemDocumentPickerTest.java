package com.deepseekharness.app.ui;

import static org.junit.Assert.*;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.ResolveInfo;
import android.net.Uri;
import java.util.ArrayList;
import java.util.List;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.shadow.api.Shadow;
import org.robolectric.shadows.ShadowPackageManager;
import org.robolectric.annotation.Config;

/** 目录选择意图必须指向系统 DocumentsUI；默认处理程序被 OEM 改写时要可识别。 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34, application = android.app.Application.class)
public final class SystemDocumentPickerTest {
  private static final String DOCUMENTS_UI = "com.android.documentsui";
  private static final String GOOGLE_DOCUMENTS_UI = "com.google.android.documentsui";
  private static final String OEM_PICKER = "com.oplus.filemanager";

  private final Context context = RuntimeEnvironment.getApplication();
  private final Intent tree = new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE);
  private ShadowPackageManager shadow;

  @Before
  public void setUp() {
    shadow = Shadow.extract(context.getPackageManager());
  }

  /** 让指定处理程序参与解析；列表顺序就是默认解析结果（Robolectric 取第一个）。 */
  private ResolveInfo install(String pkg, String activity, String label, boolean exported) {
    PackageInfo info = new PackageInfo();
    info.packageName = pkg;
    ApplicationInfo application = new ApplicationInfo();
    application.packageName = pkg;
    application.nonLocalizedLabel = label;
    info.applicationInfo = application;
    shadow.installPackage(info);
    ActivityInfo activityInfo = new ActivityInfo();
    activityInfo.packageName = pkg;
    activityInfo.name = activity;
    activityInfo.exported = exported;
    activityInfo.enabled = true;
    activityInfo.applicationInfo = application;
    ResolveInfo resolve = new ResolveInfo();
    resolve.activityInfo = activityInfo;
    return resolve;
  }

  private void handlers(ResolveInfo... resolve) {
    List<ResolveInfo> list = new ArrayList<>();
    for (ResolveInfo item : resolve) list.add(item);
    shadow.addResolveInfoForIntent(tree, list);
  }

  @Test
  public void detectsOemOverrideAndTargetsSystemDocumentsUi() {
    handlers(
        install(OEM_PICKER, OEM_PICKER + ".Picker", "文件管理", true),
        install(DOCUMENTS_UI, DOCUMENTS_UI + ".picker.PickActivity", "文件", true));
    SystemDocumentPicker.Status status = SystemDocumentPicker.inspect(context);
    assertTrue(status.hasSystem());
    assertTrue(status.overridden());
    assertEquals(OEM_PICKER, status.defaultPackage);
    assertEquals("文件管理", status.defaultName());
    Intent forced = SystemDocumentPicker.preferSystem(context, new Intent(tree));
    assertNotNull(forced.getComponent());
    assertEquals(DOCUMENTS_UI, forced.getComponent().getPackageName());
    assertEquals(DOCUMENTS_UI + ".picker.PickActivity", forced.getComponent().getClassName());
  }

  @Test
  public void googleDocumentsUiIsAlsoSystem() {
    handlers(install(GOOGLE_DOCUMENTS_UI, GOOGLE_DOCUMENTS_UI + ".PickActivity", "Files", true));
    SystemDocumentPicker.Status status = SystemDocumentPicker.inspect(context);
    assertTrue(status.hasSystem());
    assertFalse(status.overridden());
    Intent forced = SystemDocumentPicker.preferSystem(context, new Intent(tree));
    assertEquals(GOOGLE_DOCUMENTS_UI, forced.getComponent().getPackageName());
  }

  @Test
  public void keepsDefaultResolutionWhenNoSystemPickerIsInstalled() {
    handlers(install(OEM_PICKER, OEM_PICKER + ".Picker", "文件管理", true));
    SystemDocumentPicker.Status status = SystemDocumentPicker.inspect(context);
    assertFalse(status.hasSystem());
    assertFalse(status.overridden());
    Intent fallback = SystemDocumentPicker.preferSystem(context, new Intent(tree));
    assertNull(fallback.getComponent());
  }

  @Test
  public void ignoresSystemPickerThatIsNotExported() {
    handlers(
        install(OEM_PICKER, OEM_PICKER + ".Picker", "文件管理", true),
        install(DOCUMENTS_UI, DOCUMENTS_UI + ".picker.PickActivity", "文件", false));
    SystemDocumentPicker.Status status = SystemDocumentPicker.inspect(context);
    assertFalse(status.hasSystem());
    assertNull(SystemDocumentPicker.preferSystem(context, new Intent(tree)).getComponent());
  }

  @Test
  public void resolverIsNotCountedAsOemOverride() {
    handlers(
        install("android", "android.content.IntentResolver", "选择器", true),
        install(DOCUMENTS_UI, DOCUMENTS_UI + ".picker.PickActivity", "文件", true));
    SystemDocumentPicker.Status status = SystemDocumentPicker.inspect(context);
    assertTrue(status.hasSystem());
    assertFalse(status.overridden());
    Intent forced = SystemDocumentPicker.preferSystem(context, new Intent(tree));
    assertEquals(DOCUMENTS_UI, forced.getComponent().getPackageName());
  }

  @Test
  public void contractTargetsSystemPickerAndParsesOnlyOkResult() {
    handlers(install(DOCUMENTS_UI, DOCUMENTS_UI + ".picker.PickActivity", "文件", true));
    SystemDocumentPicker.TreeContract contract = new SystemDocumentPicker.TreeContract();
    Intent intent = contract.createIntent(context, null);
    assertNotNull(intent.getComponent());
    assertEquals(DOCUMENTS_UI, intent.getComponent().getPackageName());
    assertNull(intent.getParcelableExtra(android.provider.DocumentsContract.EXTRA_INITIAL_URI));
    Uri uri = Uri.parse("content://com.example.documents/tree/root");
    Intent result = new Intent().setData(uri);
    assertEquals(uri, contract.parseResult(android.app.Activity.RESULT_OK, result));
    assertNull(contract.parseResult(android.app.Activity.RESULT_CANCELED, result));
    assertNull(contract.parseResult(android.app.Activity.RESULT_OK, null));
  }

  @Test
  public void initialUriIsForwardedForBrowsingPickersThatNeedIt() {
    handlers(install(DOCUMENTS_UI, DOCUMENTS_UI + ".picker.PickActivity", "文件", true));
    Uri initial = Uri.parse("content://com.android.externalstorage.documents/root/primary");
    Intent intent = new SystemDocumentPicker.TreeContract().createIntent(context, initial);
    assertEquals(
        initial, intent.getParcelableExtra(android.provider.DocumentsContract.EXTRA_INITIAL_URI));
    assertEquals(
        new ComponentName(DOCUMENTS_UI, DOCUMENTS_UI + ".picker.PickActivity"),
        intent.getComponent());
  }
}
