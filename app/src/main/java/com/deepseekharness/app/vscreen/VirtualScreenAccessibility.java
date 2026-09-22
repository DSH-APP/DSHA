package com.deepseekharness.app.vscreen;

import android.accessibilityservice.AccessibilityService;
import android.graphics.Rect;
import android.os.Bundle;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.accessibility.AccessibilityWindowInfo;
import org.json.*;
import java.util.*;

/** 所有查询和动作均限定在虚拟 displayId，不使用主屏的 activeWindow。 */
public final class VirtualScreenAccessibility {
    private static final Map<String,AccessibilityNodeInfo> nodes=new LinkedHashMap<>();
    private static int treeDisplay=-1;private static long treeAt;
    private VirtualScreenAccessibility(){}
    public static synchronized JSONObject run(AccessibilityService service,int displayId,String operation,JSONObject args){
        AccessibilityNodeInfo root=null,target=null;
        try {
            if(service==null)return fail("ACCESSIBILITY_UNAVAILABLE");
            if(android.os.Build.VERSION.SDK_INT<30||displayId<=0)return fail("INVALID_DISPLAY");
            var all=service.getWindowsOnAllDisplays();var windows=all.get(displayId);
            try{
                if(windows!=null)for(var window:windows){
                    if(window.getType()!=AccessibilityWindowInfo.TYPE_APPLICATION)continue;
                    AccessibilityNodeInfo candidate=window.getRoot();if(candidate==null)continue;
                    if(root==null||window.isFocused()){if(root!=null)root.recycle();root=candidate;}else candidate.recycle();
                    if(window.isFocused())break;
                }
            }finally{for(int i=0;i<all.size();i++)for(var window:all.valueAt(i))window.recycle();}
            if(root==null)return fail("VIRTUAL_WINDOW_UNAVAILABLE");
            if(operation.equals("tree")){
                clear();treeDisplay=displayId;treeAt=android.os.SystemClock.elapsedRealtime();JSONArray tree=new JSONArray();walk(root,tree,0);
                return ok().put("nodes",tree);
            }
            if(operation.equals("node")){
                if(displayId!=treeDisplay||android.os.SystemClock.elapsedRealtime()-treeAt>30000)return fail("STALE_TREE");
                target=nodes.remove(args.optString("nodeId"));if(target==null||!target.refresh()||target.getWindowId()!=root.getWindowId())return fail("STALE_NODE");
                String action=args.optString("action");boolean result;
                switch(action){
                    case "click":result=target.performAction(AccessibilityNodeInfo.ACTION_CLICK);break;
                    case "long_click":result=target.performAction(AccessibilityNodeInfo.ACTION_LONG_CLICK);break;
                    case "scroll_forward":result=target.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD);break;
                    case "scroll_backward":result=target.performAction(AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD);break;
                    case "focus":result=target.performAction(AccessibilityNodeInfo.ACTION_FOCUS);break;
                    case "set_text":Bundle b=new Bundle();String value=args.optString("text");if(value.length()>16000)return fail("TEXT_LIMIT");b.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,value);result=target.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT,b);break;
                    default:return fail("INVALID_NODE_ACTION");
                }
                return result?ok():fail("NODE_ACTION_REJECTED");
            }
            target=root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT);
            if(target==null||!target.isEditable())return fail("VIRTUAL_INPUT_NOT_FOCUSED");
            if(target.isPassword())return fail("PASSWORD_INPUT_UNAVAILABLE");
            String id=target.getWindowId()+":"+target.hashCode();
            if(operation.equals("editor"))return ok().put("editorId",id).put("text",target.getText()==null?"":target.getText().toString()).put("start",target.getTextSelectionStart()).put("end",target.getTextSelectionEnd()).put("inputType",target.getInputType()).put("multiline",target.isMultiLine());
            if(!id.equals(args.optString("editorId")))return fail("EDITOR_CHANGED");
            if(operation.equals("submit"))return target.performAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_IME_ENTER.getId())?ok():fail("IME_ACTION_UNAVAILABLE");
            if(!operation.equals("edit"))return fail("INVALID_EDITOR_OPERATION");
            String text=args.optString("text");int start=args.optInt("start",text.length()),end=args.optInt("end",start);
            if(text.length()>16000||start<0||end<0||start>text.length()||end>text.length())return fail("INVALID_EDITOR_RANGE");
            Bundle b=new Bundle();b.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,text);
            if(!target.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT,b))return fail("EDITOR_WRITE_REJECTED");
            b=new Bundle();b.putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_START_INT,start);b.putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_END_INT,end);
            boolean selected=target.performAction(AccessibilityNodeInfo.ACTION_SET_SELECTION,b);
            return ok().put("selectionApplied",selected);
        }catch(Throwable error){return fail("ACCESSIBILITY_OPERATION_UNKNOWN");}
        finally{if(target!=null)target.recycle();if(root!=null)root.recycle();}
    }
    public static synchronized void clear(){for(var node:nodes.values())node.recycle();nodes.clear();treeDisplay=-1;}
    private static void walk(AccessibilityNodeInfo node,JSONArray tree,int depth)throws JSONException {
        if(depth>24||tree.length()>=200)return;
        String text=node.isPassword()?"":String.valueOf(node.getText()==null?"":node.getText());
        if(!text.isEmpty()||node.isClickable()||node.isEditable()||node.isScrollable()){
            String id=UUID.randomUUID().toString();nodes.put(id,AccessibilityNodeInfo.obtain(node));Rect rect=new Rect();node.getBoundsInScreen(rect);
            tree.put(new JSONObject().put("nodeId",id).put("text",text.substring(0,Math.min(text.length(),1000))).put("description",node.isPassword()?"":String.valueOf(node.getContentDescription()==null?"":node.getContentDescription())).put("clickable",node.isClickable()).put("editable",node.isEditable()).put("scrollable",node.isScrollable()).put("bounds",new JSONArray(new int[]{rect.left,rect.top,rect.right,rect.bottom})));
        }
        for(int i=0;i<node.getChildCount()&&tree.length()<200;i++){AccessibilityNodeInfo child=node.getChild(i);if(child!=null)try{walk(child,tree,depth+1);}finally{child.recycle();}}
    }
    private static JSONObject ok()throws JSONException{return new JSONObject().put("ok",true);}
    private static JSONObject fail(String error){try{return new JSONObject().put("ok",false).put("error",error);}catch(JSONException e){return new JSONObject();}}
}
