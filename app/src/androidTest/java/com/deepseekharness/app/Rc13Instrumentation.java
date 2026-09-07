package com.deepseekharness.app;

import android.app.Activity;
import android.app.Application;
import android.app.Instrumentation;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Bundle;
import android.widget.EditText;
import android.widget.TextView;
import androidx.core.content.FileProvider;
import com.deepseekharness.app.core.UpdateRepository;
import com.deepseekharness.app.core.UpdateEngine;
import com.deepseekharness.app.core.PluginRepository;
import com.deepseekharness.app.ui.MainActivity;
import com.deepseekharness.app.util.PluginSource;
import com.deepseekharness.app.ui.DiagnosticActivity;
import com.deepseekharness.app.util.UpdatePolicy;
import java.io.File;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.util.function.BooleanSupplier;

/** 真机集成验收：实际报告导出、Android APK 签名解析和下载取消重试。不会安装测试 APK。 */
public final class Rc13Instrumentation extends Instrumentation {
    private Bundle args;
    @Override public void onCreate(Bundle args) { super.onCreate(args); this.args=args; start(); }
    private void check(boolean ok, String message) { if (!ok) throw new AssertionError(message); }
    private void until(BooleanSupplier ready, String message) throws Exception {
        long end=System.currentTimeMillis()+60000;
        while (!ready.getAsBoolean() && System.currentTimeMillis()<end) Thread.sleep(100);
        check(ready.getAsBoolean(),message);
    }
    @Override public void onStart() {
        Bundle result=new Bundle(); Activity activity=null;
        try {
            Bundle progress=new Bundle();progress.putString("phase","开始真机验收");sendStatus(1,progress);
            if ("diagnostics".equals(args.getString("scenario"))) {
                // MIUI 限制后台直接打开页面，先通过测试框架的 Shell 通道显示主界面。
                try(android.os.ParcelFileDescriptor command=getUiAutomation().executeShellCommand("am start -W -n com.dsh.client/com.deepseekharness.app.ui.MainActivity");
                    java.io.InputStream input=new android.os.ParcelFileDescriptor.AutoCloseInputStream(command)) { while(input.read()!=-1) { } }
                activity=startActivitySync(new Intent(getTargetContext(),DiagnosticActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
                progress.putString("phase","诊断页面已打开");sendStatus(1,progress);
                Activity page=activity;
                until(()->((TextView)page.findViewById(R.id.diagnostic_report)).getText().toString().contains("Node:"),"诊断未完成");
                String sample="复现：打开测试插件。api_key=rc13secretFORTEST123456\nAuthorization: Bearer rc13privateTOKEN123456";
                runOnMainSync(()->{((EditText)page.findViewById(R.id.diagnostic_steps)).setText(sample);page.findViewById(R.id.diagnostic_copy).performClick();});
                String[] copied={""};
                runOnMainSync(()->{ClipboardManager clip=(ClipboardManager)page.getSystemService(Context.CLIPBOARD_SERVICE);copied[0]=clip.getPrimaryClip().getItemAt(0).coerceToText(page).toString();});
                check(!copied[0].contains("rc13secret")&&!copied[0].contains("rc13private"),"复制报告泄露测试凭据");
                check(copied[0].contains("复现：打开测试插件")&&copied[0].contains("版本："),"复制报告缺少信息");
                File folder=new File(getTargetContext().getCacheDir(),"updates");folder.mkdirs();
                File output=new File(folder,"rc13-diagnostic-check.txt");
                check(!output.exists()||output.delete(),"无法清理前一次测试报告");
                android.net.Uri uri=FileProvider.getUriForFile(page,page.getPackageName()+".updates",output);
                IntentFilter filter=new IntentFilter(Intent.ACTION_CREATE_DOCUMENT);filter.addCategory(Intent.CATEGORY_OPENABLE);filter.addDataType("text/plain");
                ActivityMonitor monitor=addMonitor(filter,new ActivityResult(Activity.RESULT_OK,new Intent().setData(uri)),true);
                try {runOnMainSync(()->page.findViewById(R.id.diagnostic_export).performClick());until(()->output.isFile()&&output.length()>0,"导出未写入文件");}
                finally {removeMonitor(monitor);}
                check(new String(Files.readAllBytes(output.toPath()),StandardCharsets.UTF_8).equals(copied[0]),"导出与复制内容不一致");
                result.putString("diagnostics","PASS: 环境报告、复制脱敏、文件导出一致");
                runOnMainSync(()->page.findViewById(R.id.diagnostic_repair).performClick());
                until(()->((TextView)page.findViewById(R.id.diagnostic_report)).getText().toString().startsWith("证书、Python、npm 与 pnpm 已修复"),"网络工具修复未通过");
                result.putString("repair","PASS: 随包证书、npm、pnpm、Python 修复并可启动");
            } else if ("plugins".equals(args.getString("scenario"))) {
                check("true".equals(args.getString("ownedPlugin")),"仅允许操作本次验收添加的插件");
                try(android.os.ParcelFileDescriptor command=getUiAutomation().executeShellCommand("am start -W -n com.dsh.client/com.deepseekharness.app.ui.MainActivity");
                    java.io.InputStream input=new android.os.ParcelFileDescriptor.AutoCloseInputStream(command)) {while(input.read()!=-1) { }}
                activity=startActivitySync(new Intent(getTargetContext(),MainActivity.class).setAction("dsha.test."+System.nanoTime()).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
                MainActivity page=(MainActivity)activity;
                PluginRepository repository=new androidx.lifecycle.ViewModelProvider(page).get(PluginRepository.class);
                String name="dsh-subagent-model-picker";
                runOnMainSync(repository::refresh);until(()->!repository.state().getValue().busy,"读取插件超时");
                check(plugin(repository,name)!=null,"本次添加的验收插件不存在");
                phase("检查 npm 固定旧版本安装预览");
                runOnMainSync(()->repository.inspect(PluginSource.parse(name+"@0.1.0"),"",name,"0.1.0"));
                until(()->!repository.state().getValue().busy,"解析旧版本超时");
                check(repository.preview().getValue()!=null,"解析失败："+repository.state().getValue().message);
                check(plugin(repository,name).version.equals("0.1.1"),"确认前插件已改变");
                runOnMainSync(repository::confirmPreview);until(()->!repository.state().getValue().busy,"安装旧版本超时");
                check(plugin(repository,name).version.equals("0.1.0"),repository.state().getValue().message);
                phase("真实 npm 更新检查、安装与上一版回退");
                runOnMainSync(()->repository.checkUpdates(plugin(repository,name)));until(()->!repository.state().getValue().busy,"检查更新超时");
                check(plugin(repository,name).updateAvailable,"未发现更新："+plugin(repository,name).updateMessage);
                runOnMainSync(()->repository.prepareUpdate(plugin(repository,name)));until(()->!repository.state().getValue().busy,"读取更新超时");
                check(repository.preview().getValue()!=null,"没有更新预览");
                runOnMainSync(repository::confirmPreview);until(()->!repository.state().getValue().busy,"更新安装超时");
                check(repository.installationSucceeded()&&plugin(repository,name).version.equals("0.1.1"),repository.state().getValue().message);
                check(plugin(repository,name).rollbackVersion.equals("0.1.0"),"未保留上一版");
                runOnMainSync(()->repository.rollback(plugin(repository,name)));until(()->!repository.state().getValue().busy,"回退超时");
                check(plugin(repository,name).version.equals("0.1.0"),"回退未恢复旧版");
                // 回退后旧预览已被消费，入口应自动重新取包并再次确认。
                runOnMainSync(()->repository.prepareUpdate(plugin(repository,name)));until(()->!repository.state().getValue().busy,"重新生成更新预览超时");
                check(repository.preview().getValue()!=null,"回退后更新入口失效");
                runOnMainSync(repository::confirmPreview);until(()->!repository.state().getValue().busy,"再次更新超时");
                check(plugin(repository,name).version.equals("0.1.1"),"再次更新失败");
                check(!plugin(repository,name).updateAvailable&&!plugin(repository,name).updateMessage.equals("有新版本"),"更新完成后仍误报旧的更新提示");
                File folder=new File(getTargetContext().getCacheDir(),"updates");folder.mkdirs();
                File bad=new File(folder,"rc13-bad-plugin.zip");Files.write(bad.toPath(),new byte[]{1,2,3});
                runOnMainSync(()->repository.importArchive(android.net.Uri.fromFile(bad)));until(()->!repository.state().getValue().busy,"错误包处理超时");
                check(plugin(repository,name).version.equals("0.1.1")&&repository.state().getValue().message.contains("失败"),"错误导入改变了现有插件");
                phase("安全模式禁用与恢复状态");
                java.util.Map<String,Boolean> enabled=new java.util.LinkedHashMap<>();
                for(PluginRepository.Item item:repository.state().getValue().items)enabled.put(item.name,item.enabled);
                try {
                    runOnMainSync(()->repository.safeMode(true,null));until(()->!repository.state().getValue().busy,"安全模式超时");
                    check(repository.isSafeMode()&&!plugin(repository,name).enabled,"第三方插件未停用");
                } finally {
                    runOnMainSync(()->repository.safeMode(false,null));until(()->!repository.state().getValue().busy,"恢复安全模式超时");
                }
                for(PluginRepository.Item item:repository.state().getValue().items)check(enabled.get(item.name)==item.enabled,"插件启用状态未恢复");
                runOnMainSync(()->{
                    ((com.google.android.material.bottomnavigation.BottomNavigationView)page.findViewById(R.id.bottom_nav)).setSelectedItemId(R.id.nav_plugins);
                    page.getSupportFragmentManager().executePendingTransactions();
                    page.findViewById(R.id.btnInstalled).performClick();
                    ((EditText)page.findViewById(R.id.pluginSearch)).setText(name);
                });
                waitForIdleSync();
                runOnMainSync(()->((androidx.core.widget.NestedScrollView)page.findViewById(R.id.pluginScroll)).fullScroll(android.view.View.FOCUS_DOWN));
                waitForIdleSync();
                android.graphics.Bitmap screen=getUiAutomation().takeScreenshot();
                try(java.io.OutputStream output=new java.io.FileOutputStream(new File(folder,"rc13-plugin-verified.png"))){screen.compress(android.graphics.Bitmap.CompressFormat.PNG,100,output);}
                result.putString("plugins","PASS: npm 更新、确认边界、回退、失效预览刷新、错误包保留现状、安全模式恢复");
            } else if ("webview-cleanup".equals(args.getString("scenario"))) {
                check("true".equals(args.getString("ownedPlugin")),"必须明确本次验收插件归属");
                try(android.os.ParcelFileDescriptor command=getUiAutomation().executeShellCommand("am start -W -n com.dsh.client/com.deepseekharness.app.ui.MainActivity");
                    java.io.InputStream input=new android.os.ParcelFileDescriptor.AutoCloseInputStream(command)) {while(input.read()!=-1) { }}
                MainActivity page=(MainActivity)startActivitySync(new Intent(getTargetContext(),MainActivity.class).setAction("dsha.test."+System.nanoTime()).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
                activity=page;
                PluginRepository repository=new androidx.lifecycle.ViewModelProvider(page).get(PluginRepository.class);
                runOnMainSync(repository::refresh);until(()->!repository.state().getValue().busy,"读取插件超时");
                runOnMainSync(()->repository.safeMode(false,null));until(()->!repository.state().getValue().busy,"恢复插件状态超时");
                check(!repository.isSafeMode()&&plugin(repository,"dsh-infinite-gen-3").enabled,"原有插件未恢复启用");
                PluginRepository.Item owned=plugin(repository,"dsh-subagent-model-picker");
                if(owned!=null){runOnMainSync(()->repository.delete(owned));until(()->!repository.state().getValue().busy,"删除验收插件超时");}
                check(plugin(repository,"dsh-subagent-model-picker")==null,"验收插件仍存在");
                com.deepseekharness.app.core.HarnessController controller=com.deepseekharness.app.core.HarnessController.get(page);
                com.deepseekharness.app.core.ConfigStore config=new com.deepseekharness.app.core.ConfigStore(page);
                boolean previousGecko=config.isGeckoCore();
                Activity browserPage=null;
                try {
                    phase("恢复原有插件后启动系统 WebView");
                    controller.stopWeb();
                    check(controller.startWeb(message->{}),"启动未接受");
                    until(()->!controller.getWebAuthUrl().isEmpty(),"Web 启动超时");
                    config.setGeckoCore(false);
                    browserPage=startActivitySync(com.deepseekharness.app.ui.WebPreviewActivity.intent(page,controller.getWebAuthUrl(),controller.exchangeDshAuthCookie()).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
                    Field field=com.deepseekharness.app.ui.WebPreviewActivity.class.getDeclaredField("webView");field.setAccessible(true);
                    android.webkit.WebView view=(android.webkit.WebView)field.get(browserPage);
                    check(view!=null,"系统 WebView 未创建");
                    java.util.concurrent.atomic.AtomicBoolean loaded=new java.util.concurrent.atomic.AtomicBoolean();
                    long deadline=System.currentTimeMillis()+60000;
                    while(!loaded.get()&&System.currentTimeMillis()<deadline){
                        runOnMainSync(()->view.evaluateJavascript("document.body && !document.body.innerText.includes('Loading plugins') && (document.body.innerText.includes('探索未至之境') || document.body.innerText.includes('添加一个 API Key 开始使用'))",value->loaded.set("true".equals(value))));
                        Thread.sleep(250);
                    }
                    check(loaded.get(),"系统 WebView 未显示完整 dsh 界面");
                    java.util.concurrent.CountDownLatch frame=new java.util.concurrent.CountDownLatch(1);
                    runOnMainSync(()->view.postVisualStateCallback(1,new android.webkit.WebView.VisualStateCallback(){@Override public void onComplete(long id){frame.countDown();}}));
                    check(frame.await(10,java.util.concurrent.TimeUnit.SECONDS),"WebView 绘制未完成");
                    waitForIdleSync();
                    Thread.sleep(500);
                    File file=new File(getTargetContext().getCacheDir(),"updates/rc13-system-webview.png");
                    try(java.io.OutputStream output=new java.io.FileOutputStream(file)){getUiAutomation().takeScreenshot().compress(android.graphics.Bitmap.CompressFormat.PNG,100,output);}
                    result.putString("webview","PASS: 原有插件恢复后正常启动、系统 WebView 鉴权并渲染 dsh");
                } finally {
                    config.setGeckoCore(previousGecko);
                    if(browserPage!=null){Activity closing=browserPage;runOnMainSync(closing::finish);}
                    controller.stopWeb();
                    getTargetContext().stopService(new Intent(getTargetContext(),HarnessService.class));
                }
                result.putString("cleanup","PASS: 安全模式关闭、原有插件启用、验收插件删除、内核设置恢复、Web 停止");
            } else {
                Application app=(Application)getTargetContext().getApplicationContext();
                UpdateRepository repository=new UpdateRepository(app);
                UpdateEngine updateEngine=UpdateEngine.get(app);
                File directory=new File(args.getString("fixtures"));
                File valid=new File(directory,"valid.apk");
                String hash=hex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(valid.toPath())));
                String url=args.getString("url","https://dsha.cc/unused-test.apk");
                UpdatePolicy.Release release=new UpdatePolicy.Release(114,"1.2.0-validation",UpdatePolicy.PREVIEW,
                        BuildConfig.LOW_ANDROID?"low":"standard",BuildConfig.LOW_ANDROID?23:30,"arm64-v8a",url,hash,valid.length(),"验收用测试包","https://dsha.cc/download/");
                Method validate=UpdateEngine.class.getDeclaredMethod("validatePackage",File.class,UpdatePolicy.Release.class);validate.setAccessible(true);
                validate.invoke(updateEngine,valid,release);
                for(String name:new String[]{"wrong-signature.apk","wrong-package.apk","wrong-flavor.apk"}){
                    boolean rejected=false;try{validate.invoke(updateEngine,new File(directory,name),release);}catch(InvocationTargetException e){rejected=e.getCause() instanceof java.io.IOException;}
                    check(rejected,"错误 APK 未被拒绝："+name);
                }
                result.putString("packageValidation","PASS: 有效新版本、签名不符、包名不符、版本分支不符");
                if(args.containsKey("url")){
                    check(UpdatePolicy.PREVIEW.equals(updateEngine.channel()),"预览下载夹具需要先选择预览通道");
                    Field candidate=UpdateEngine.class.getDeclaredField("candidate");candidate.setAccessible(true);candidate.set(updateEngine,release);
                    Field candidateChannel=UpdateEngine.class.getDeclaredField("candidateChannel");candidateChannel.setAccessible(true);candidateChannel.set(updateEngine,UpdatePolicy.PREVIEW);
                    Field verifiedApk=UpdateEngine.class.getDeclaredField("verifiedApk");verifiedApk.setAccessible(true);verifiedApk.set(updateEngine,null);
                    runOnMainSync(repository::download);
                    until(()->repository.state().getValue().downloaded>0||!repository.state().getValue().busy,"下载未响应");
                    check(repository.state().getValue().downloaded>0,"下载未显示进度："+repository.state().getValue().message);
                    repository.cancel();until(()->!repository.state().getValue().busy,"取消未结束");
                    check(repository.state().getValue().apk==null&&repository.state().getValue().message.contains("取消"),"取消仍留下可安装 APK");
                    runOnMainSync(repository::download);until(()->!repository.state().getValue().busy,"重试下载超时");
                    check(repository.state().getValue().apk!=null,"重试失败："+repository.state().getValue().message);
                    check(hex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(repository.installableApk().toPath()))).equals(hash),"下载文件与实际包不一致");
                    android.net.Uri installUri=FileProvider.getUriForFile(app,app.getPackageName()+".updates",repository.installableApk());
                    check(app.getPackageManager().resolveActivity(new Intent(Intent.ACTION_VIEW).setDataAndType(installUri,"application/vnd.android.package-archive").addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION),0)!=null,"没有系统 APK 安装器");
                    candidate.set(updateEngine,new UpdatePolicy.Release(114,"1.2.0-validation",UpdatePolicy.PREVIEW,release.flavor,release.minSdk,"arm64-v8a",url,"0000000000000000000000000000000000000000000000000000000000000000",valid.length(),"验收",release.pageUrl));
                    runOnMainSync(repository::download);until(()->!repository.state().getValue().busy,"错误摘要下载超时");
                    check(repository.state().getValue().apk==null&&repository.state().getValue().message.contains("SHA-256"),"错误摘要未阻止安装");
                    result.putString("download","PASS: HTTPS、进度、取消、重试、摘要及 Android 包校验");
                }
            }
            result.putString("result","PASS");
        } catch(Throwable error){result.putString("result","FAIL: "+error);}
        finally {if(activity!=null){Activity page=activity;runOnMainSync(page::finish);}}
        finish("PASS".equals(result.getString("result"))?Activity.RESULT_OK:Activity.RESULT_CANCELED,result);
    }
    private void phase(String message){Bundle progress=new Bundle();progress.putString("phase",message);sendStatus(1,progress);}
    private PluginRepository.Item plugin(PluginRepository repository,String name){for(PluginRepository.Item item:repository.state().getValue().items)if(item.name.equals(name))return item;return null;}
    private static String hex(byte[] bytes){StringBuilder result=new StringBuilder();for(byte value:bytes)result.append(String.format(java.util.Locale.ROOT,"%02x",value&255));return result.toString();}
}
