package com.deepseekharness.app.ui;

import android.app.Activity;
import android.app.Instrumentation;
import android.content.Context;
import android.content.ContextWrapper;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Bundle;
import com.deepseekharness.app.R;
import com.deepseekharness.app.core.DiagnosticLog;
import com.deepseekharness.app.core.ErrorLogRepository;
import com.deepseekharness.app.util.Compat;
import java.io.File;
import java.io.InputStream;
import java.util.concurrent.atomic.AtomicReference;

/** 调试专用：假日志验证脱敏，真实下载按钮、URI、MIME、查看与另存为入口。 */
public final class UiPolishAudit extends Instrumentation {
    private int checks;
    private void check(boolean ok, String why) { if (!ok) throw new AssertionError(why); checks++; }
    private void ui(Runnable action) {
        AtomicReference<Throwable> error = new AtomicReference<>();
        runOnMainSync(() -> { try { action.run(); } catch (Throwable e) { error.set(e); } });
        if (error.get() != null) throw new AssertionError(error.get());
    }
    @Override public void onCreate(Bundle args) { super.onCreate(args); start(); }
    @Override public void callActivityOnCreate(Activity activity, Bundle state) {
        super.callActivityOnCreate(activity, state);
        activity.getWindow().addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON | android.view.WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON);
    }
    @Override public void onStart() {
        Bundle result = new Bundle(); DiagnosticActivity page = null; File fakeBase = null;
        try {
            Context app = getTargetContext().getApplicationContext();
            File folder = new File(app.getCacheDir(), "ui-polish-audit"); folder.mkdirs();
            File base = new File(folder, "fixture"); base.mkdirs(); fakeBase = base;
            Context fake = new ContextWrapper(app) {
                @Override public Context getApplicationContext() { return this; }
                @Override public File getFilesDir() { return base; }
                @Override public android.content.SharedPreferences getSharedPreferences(String name, int mode) { return super.getSharedPreferences("ui_polish_fixture_"+name, mode); }
            };
            File web = new File(base, "linux/ubuntu/root/dsh-web.log"); web.getParentFile().mkdirs();
            Compat.write(web, "earlier output\n".repeat(40000) + "Authorization: Bearer ui-polish-private-value\nAPI_KEY=sk-12345678901234567890\n最新错误：连接超时\n");
            DiagnosticLog.record(fake, "TEST", "token=ui-polish-secret; example failure");
            String collected = ErrorLogRepository.collect(fake);
            check(collected.contains("最新错误：连接超时") && collected.contains("example failure"), "日志包含运行错误与应用记录");
            check(!collected.contains("ui-polish-private-value") && !collected.contains("12345678901234567890") && !collected.contains("ui-polish-secret"), "导出统一脱敏");
            check(collected.length() < 300000, "大日志有界读取");
            // Android 16 会拦截后台 Activity 启动；先通过测试 shell 将自己的主页面置前。
            try (android.os.ParcelFileDescriptor descriptor = getUiAutomation().executeShellCommand("am start -W -n com.dsh.client/com.deepseekharness.app.ui.MainActivity");
                 InputStream input = new android.os.ParcelFileDescriptor.AutoCloseInputStream(descriptor)) { while (input.read() != -1) { } }
            page = (DiagnosticActivity) startActivitySync(DiagnosticActivity.downloadLogs(app).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            DiagnosticActivity current = page;
            AtomicReference<ErrorLogRepository> modelRef = new AtomicReference<>();
            ui(() -> modelRef.set(new androidx.lifecycle.ViewModelProvider(current).get(ErrorLogRepository.class)));
            ErrorLogRepository model = modelRef.get();
            long end = System.currentTimeMillis() + 20000;
            while ((model.state.getValue() == null || model.state.getValue().busy || model.state.getValue().uri == null) && System.currentTimeMillis() < end) Thread.sleep(100);
            ErrorLogRepository.State state = model.state.getValue();
            check(state != null && !state.busy && state.uri != null && state.message.startsWith("已保存"), "真实下载完成：" + (state == null ? "空" : state.message));
            check("text/plain".equals(app.getContentResolver().getType(state.uri)), "下载文件 MIME 为文本");
            byte[] bytes;
            try (InputStream input = app.getContentResolver().openInputStream(state.uri); java.io.ByteArrayOutputStream output = new java.io.ByteArrayOutputStream()) {
                byte[] buffer = new byte[8192]; int n; while ((n=input.read(buffer))!=-1) output.write(buffer,0,n); bytes=output.toByteArray();
            }
            String saved = new String(bytes, java.nio.charset.StandardCharsets.UTF_8);
            check(saved.startsWith("DSHA 错误日志") && saved.contains("最近运行输出"), "实际下载 URI 可读且正文完整");
            check(bytes.length < 400000, "导出文件大小有界");
            AtomicReference<ErrorLogRepository> reloaded = new AtomicReference<>();
            ui(() -> reloaded.set(new ErrorLogRepository((android.app.Application) app)));
            check(state.uri.equals(reloaded.get().state.getValue().uri), "重开后保留查看上次日志入口");
            IntentFilter viewing = new IntentFilter(Intent.ACTION_VIEW); viewing.addDataType("text/plain"); viewing.addDataScheme("content");
            ActivityMonitor monitor = addMonitor(viewing, new ActivityResult(Activity.RESULT_CANCELED, null), true);
            try { ui(() -> current.findViewById(R.id.diagnostic_logs_status).performClick()); check(monitor.getHits()==1, "查看按钮打开正确的文本 URI"); }
            finally { removeMonitor(monitor); }
            IntentFilter creating = new IntentFilter(Intent.ACTION_CREATE_DOCUMENT); creating.addDataType("text/plain");
            monitor = addMonitor(creating, new ActivityResult(Activity.RESULT_CANCELED, null), true);
            try { ui(() -> current.findViewById(R.id.diagnostic_logs_save_as).performClick()); check(monitor.getHits()==1, "另存为使用系统文档选择器"); }
            finally { removeMonitor(monitor); }
            check(state.uri.equals(model.state.getValue().uri), "取消另存为不改变已下载文件");
            android.graphics.Bitmap image = getUiAutomation().takeScreenshot();
            if (image != null) try (java.io.FileOutputStream output = new java.io.FileOutputStream(new File(folder,"downloaded-logs.png"))) { image.compress(android.graphics.Bitmap.CompressFormat.PNG,100,output); image.recycle(); }
            org.json.JSONObject proof = new org.json.JSONObject().put("uri",state.uri.toString()).put("bytes",bytes.length).put("assertions",checks).put("result","PASS");
            Compat.write(new File(folder,"report.json"),proof.toString(2));
            result.putString("result","PASS"); result.putInt("assertions",checks);
        } catch (Throwable error) { result.putString("failure", String.valueOf(error)); }
        finally {
            if (page != null) { DiagnosticActivity current=page; ui(current::finish); }
            if (fakeBase != null) clearFixture(fakeBase);
        }
        finish(result.containsKey("failure")?1:0,result);
    }
    private static void clearFixture(File file) {
        if (file.isDirectory() && !Compat.isSymbolicLink(file)) { File[] children=file.listFiles();if(children!=null)for(File child:children)clearFixture(child); }
        file.delete();
    }
}
