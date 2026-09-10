package com.deepseekharness.app.ui;

import android.app.Activity;
import android.app.Instrumentation;
import android.graphics.Rect;
import android.view.KeyEvent;
import android.view.View;
import android.view.accessibility.AccessibilityNodeInfo;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;
import com.deepseekharness.app.R;
import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import org.json.JSONArray;
import org.json.JSONObject;

/** 仅调试包：在真实网页中验证原生窗口边界和键盘事件，不调用模型。 */
final class InputInsetsAudit {
    private static void require(boolean condition,String message) {if(!condition)throw new AssertionError(message);}
    private static AccessibilityNodeInfo editor(AccessibilityNodeInfo node) {
        if(node==null)return null;
        if(node.isVisibleToUser()&&!node.isPassword()&&(node.isEditable()||"android.widget.EditText".contentEquals(node.getClassName())))return node;
        for(int i=0;i<node.getChildCount();i++) {AccessibilityNodeInfo found=editor(node.getChild(i));if(found!=null)return found;}
        return null;
    }
    private static AccessibilityNodeInfo editor(Instrumentation test) {
        AccessibilityNodeInfo found=editor(test.getUiAutomation().getRootInActiveWindow());
        require(found!=null,"找不到可见的网页编辑区");found.refresh();return found;
    }
    private static String accessibleText(AccessibilityNodeInfo node) {
        if(node==null)return "";
        node.refresh();
        if(node.getText()!=null&&node.getText().length()>0)return node.getText().toString();
        StringBuilder value=new StringBuilder();
        for(int i=0;i<node.getChildCount();i++){
            String part=accessibleText(node.getChild(i));
            if(!part.isEmpty()){if(value.length()>0)value.append('\n');value.append(part);}
        }
        return value.toString();
    }
    private static JSONObject snapshot(Instrumentation test,Activity page,File folder,String name,boolean keyboard) throws Exception {
        JSONObject result=new JSONObject();
        Throwable[] failure={null};
        test.runOnMainSync(()->{
            try {
                View decor=page.getWindow().getDecorView(),content=page.findViewById(android.R.id.content);
                View web=page.findViewById(R.id.web_container);
                WindowInsetsCompat insets=ViewCompat.getRootWindowInsets(decor);
                require(insets!=null,"窗口未报告 Insets");
                Insets status=insets.getInsetsIgnoringVisibility(WindowInsetsCompat.Type.statusBars()|WindowInsetsCompat.Type.displayCutout());
                Insets ime=insets.getInsets(WindowInsetsCompat.Type.ime());
                Rect bounds=new Rect();require(web.getGlobalVisibleRect(bounds),"网页不可见");
                result.put("name",name).put("webBounds",bounds.flattenToString()).put("windowHeight",decor.getHeight())
                        .put("statusTop",status.top).put("imeBottom",ime.bottom).put("imeVisible",insets.isVisible(WindowInsetsCompat.Type.ime()))
                        .put("paddingTop",content.getPaddingTop()).put("paddingBottom",content.getPaddingBottom());
                require(bounds.top>=status.top,"网页顶端进入状态栏");
                require(!keyboard||insets.isVisible(WindowInsetsCompat.Type.ime()),"输入法未显示");
                require(!keyboard||bounds.bottom<=decor.getHeight()-ime.bottom,"网页底端进入输入法");
            } catch(Throwable error){failure[0]=error;}
        });
        android.graphics.Bitmap screen=test.getUiAutomation().takeScreenshot();
        require(screen!=null,"截图失败");
        try(FileOutputStream out=new FileOutputStream(new File(folder,name+".png"))){screen.compress(android.graphics.Bitmap.CompressFormat.PNG,100,out);}
        try(FileOutputStream out=new FileOutputStream(new File(folder,name+".json"))){out.write(result.toString(2).getBytes(StandardCharsets.UTF_8));}
        if(failure[0]!=null)throw new AssertionError(name,failure[0]);
        return result;
    }
    private static String js(Instrumentation test,android.webkit.WebView web,String script) throws Exception {
        java.util.concurrent.CompletableFuture<String> value=new java.util.concurrent.CompletableFuture<>();
        test.runOnMainSync(()->web.evaluateJavascript(script,value::complete));return value.get(15,java.util.concurrent.TimeUnit.SECONDS);
    }
    private static String text(Instrumentation test,android.webkit.WebView web) throws Exception {
        if(web==null)return accessibleText(editor(test));
        return new JSONArray("["+js(test,web,"document.querySelector('[data-composer-input]').innerText")+"]").getString(0);
    }
    private static void focus(Instrumentation test,android.webkit.WebView web) throws Exception {
        if(web==null){
            Rect bounds=new Rect();editor(test).getBoundsInScreen(bounds);
            require(!bounds.isEmpty(),"Gecko 编辑区未报告可点击边界");
            long at=android.os.SystemClock.uptimeMillis();
            test.sendPointerSync(android.view.MotionEvent.obtain(at,at,android.view.MotionEvent.ACTION_DOWN,bounds.exactCenterX(),bounds.exactCenterY(),0));
            test.sendPointerSync(android.view.MotionEvent.obtain(at,at+80,android.view.MotionEvent.ACTION_UP,bounds.exactCenterX(),bounds.exactCenterY(),0));
            return;
        }
        JSONArray point=new JSONArray(js(test,web,"(()=>{const r=document.querySelector('[data-composer-input]').getBoundingClientRect();return [r.x+r.width/2,r.y+r.height/2,innerWidth]})()"));
        int[] location=new int[2];test.runOnMainSync(()->web.getLocationOnScreen(location));
        float scale=web.getWidth()/(float)point.getDouble(2),x=location[0]+(float)point.getDouble(0)*scale,y=location[1]+(float)point.getDouble(1)*scale;
        long at=android.os.SystemClock.uptimeMillis();
        test.sendPointerSync(android.view.MotionEvent.obtain(at,at,android.view.MotionEvent.ACTION_DOWN,x,y,0));
        test.sendPointerSync(android.view.MotionEvent.obtain(at,at+80,android.view.MotionEvent.ACTION_UP,x,y,0));
    }
    private static void input(Instrumentation test,android.view.inputmethod.InputConnection connection,String value) throws Exception {
        if(connection==null){
            if(value==null){
                android.os.Bundle args=new android.os.Bundle();args.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,"");
                require(editor(test).performAction(AccessibilityNodeInfo.ACTION_SET_TEXT,args),"Gecko 测试文字无法清理");
            }else throw new AssertionError("没有可写的测试输入连接");
            Thread.sleep(700);return;
        }
        boolean[] ok={false};
        test.runOnMainSync(()->ok[0]=value==null?connection.deleteSurroundingText(10000,10000):connection.commitText(value,1));
        require(ok[0],"输入法连接未接受文字操作");Thread.sleep(400);
    }
    static void run(Instrumentation test,Activity page,File folder,String engine,android.webkit.WebView web) throws Exception {
        JSONArray states=new JSONArray();
        Thread.sleep(1200);
        states.put(snapshot(test,page,folder,engine+"-input-before",false));
        String previous=text(test,web).trim();
        boolean previousTest=previous.replaceAll("[^A-Za-z]","").matches("DSHAinputfirst(second)?|dshainputfirst(dshainputsecond)?")
                ||previous.matches("大厦input.*rst大厦input色从的");
        require(previous.isEmpty()||"null".equals(previous)||previousTest,"当前有未发送草稿，保留现场");
        // 先验证原生输入事件，再由真实触屏拉起厂商输入法；避免厂商拼音自动纠正改写固定断言文本。
        if(web==null)editor(test).performAction(AccessibilityNodeInfo.ACTION_CLICK);else focus(test,web);
        Thread.sleep(1500);
        android.view.inputmethod.InputConnection[] connections={null};
        android.view.inputmethod.EditorInfo info=new android.view.inputmethod.EditorInfo();
        View browser=((android.view.ViewGroup)page.findViewById(R.id.web_container)).getChildAt(0);
        test.runOnMainSync(()->connections[0]=browser.onCreateInputConnection(info));
        require(connections[0]!=null,"网页输入法连接未创建");
        require((info.imeOptions&android.view.inputmethod.EditorInfo.IME_MASK_ACTION)!=android.view.inputmethod.EditorInfo.IME_ACTION_SEND,"输入法仍声明发送动作");
        if(previousTest)input(test,connections[0],null);
        input(test,connections[0],"dshainputfirst");
        // 通过实际输入法连接发送回车，避免测试用的物理按键先进入厂商拼音候选缓冲区。
        test.runOnMainSync(()->{
            connections[0].sendKeyEvent(new KeyEvent(KeyEvent.ACTION_DOWN,KeyEvent.KEYCODE_ENTER));
            connections[0].sendKeyEvent(new KeyEvent(KeyEvent.ACTION_UP,KeyEvent.KEYCODE_ENTER));
        });
        Thread.sleep(400);
        input(test,connections[0],"dshainputsecond");
        Thread.sleep(1200);
        String text=text(test,web);
        if(web==null){focus(test,web);Thread.sleep(1200);}
        text=text(test,web);
        // Gecko AX 将换行表示为 \n；WebView 可能合并段落为空格，仍要求两行文本都留在输入区。
        states.put(snapshot(test,page,folder,engine+"-input-keyboard",true));
        require(text.contains("dshainputfirst")&&text.contains("dshainputsecond"),"回车后草稿丢失或发生发送");
        require(web==null||text.contains("\n"),"真实 WebView 草稿没有换行");
        test.sendKeyDownUpSync(KeyEvent.KEYCODE_BACK);Thread.sleep(1000);
        states.put(snapshot(test,page,folder,engine+"-input-hidden",false));
        focus(test,web);Thread.sleep(1000);
        states.put(snapshot(test,page,folder,engine+"-input-reopened",true));
        input(test,web==null?null:connections[0],null);
        require(text(test,web).trim().isEmpty()||"null".equals(text(test,web)),"测试草稿未清理");
        test.sendKeyDownUpSync(KeyEvent.KEYCODE_BACK);Thread.sleep(500);
        JSONObject result=new JSONObject().put("engine",engine).put("text",text).put("imeOptions",info.imeOptions).put("states",states).put("pass",true);
        try(FileOutputStream out=new FileOutputStream(new File(folder,engine+"-input.json"))){out.write(result.toString(2).getBytes(StandardCharsets.UTF_8));}
    }
}
