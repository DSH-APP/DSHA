package com.deepseekharness.app.vscreen;

import android.os.SystemClock;
import android.view.InputDevice;
import android.view.InputEvent;
import android.view.KeyEvent;
import android.view.MotionEvent;
import java.lang.reflect.Method;

/** 仅供特权 core：直接提交限定显示上的输入事件，避免每个动作再启动 input 进程。 */
@androidx.annotation.RequiresApi(30)
final class VirtualScreenInput {
    private final Object manager;
    private final Method inject,setDisplay;
    private final int display;
    private String stroke="";private long downAt,lastAt;private float x,y;
    VirtualScreenInput(int display)throws Exception {
        this.display=display;
        Class<?> type=Class.forName("android.hardware.input.InputManager");
        manager=type.getMethod("getInstance").invoke(null);
        inject=type.getMethod("injectInputEvent",InputEvent.class,int.class);
        setDisplay=InputEvent.class.getMethod("setDisplayId",int.class);
    }
    synchronized boolean touch(String id,int action,float x,float y)throws Exception {
        if(action==MotionEvent.ACTION_DOWN){
            if(!stroke.isEmpty())cancel();
            stroke=id;downAt=SystemClock.uptimeMillis();
        } else if(stroke.isEmpty()||!stroke.equals(id))return false;
        this.x=x;this.y=y;lastAt=SystemClock.uptimeMillis();
        MotionEvent event=MotionEvent.obtain(downAt,lastAt,action,x,y,0);
        event.setSource(InputDevice.SOURCE_TOUCHSCREEN);
        try{return send(event);}finally{event.recycle();if(action==MotionEvent.ACTION_UP||action==MotionEvent.ACTION_CANCEL)stroke="";}
    }
    synchronized boolean active(){return !stroke.isEmpty();}
    synchronized void expire(){if(active()&&SystemClock.uptimeMillis()-lastAt>3000)try{cancel();}catch(Exception ignored){stroke="";}}
    synchronized void cancel()throws Exception {if(active())touch(stroke,MotionEvent.ACTION_CANCEL,x,y);}
    boolean key(int code)throws Exception {
        long at=SystemClock.uptimeMillis();
        KeyEvent down=new KeyEvent(at,at,KeyEvent.ACTION_DOWN,code,0,0,KeyEvent.KEYCODE_UNKNOWN,0,0,InputDevice.SOURCE_KEYBOARD);
        KeyEvent up=new KeyEvent(at,at,KeyEvent.ACTION_UP,code,0,0,KeyEvent.KEYCODE_UNKNOWN,0,0,InputDevice.SOURCE_KEYBOARD);
        boolean accepted=send(down);return send(up)&&accepted;
    }
    private boolean send(InputEvent event)throws Exception {setDisplay.invoke(event,display);return Boolean.TRUE.equals(inject.invoke(manager,event,0));}
}
