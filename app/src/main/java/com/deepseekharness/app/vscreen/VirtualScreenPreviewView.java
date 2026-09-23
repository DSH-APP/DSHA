package com.deepseekharness.app.vscreen;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Matrix;
import android.view.MotionEvent;
import android.view.ScaleGestureDetector;
import androidx.appcompat.widget.AppCompatImageView;
import org.json.JSONObject;

/** 预览矩阵同时用于显示和逆向映射，黑边不能变成虚拟屏坐标。 */
final class VirtualScreenPreviewView extends AppCompatImageView {
    interface Touch { void input(String generation,long seq,float x1,float y1,float x2,float y2,int ms,boolean tap); void stream(String generation,long seq,String stroke,int action,float x,float y); }
    private final Matrix matrix=new Matrix(),inverse=new Matrix();
    private final ScaleGestureDetector scale;
    private Bitmap bitmap;
    private float zoom=1,offsetX,offsetY,downX,downY,lastX,lastY;
    private int screenW,screenH;
    private String generation="";
    private long seq=-1,downAt;
    private boolean touching,transformed,pan,streaming;private String stroke="";private long lastStream;
    Touch listener;
    VirtualScreenPreviewView(Context c){
        super(c);setScaleType(ScaleType.MATRIX);setBackgroundColor(0xff101218);
        scale=new ScaleGestureDetector(c,new ScaleGestureDetector.SimpleOnScaleGestureListener(){
            @Override public boolean onScale(ScaleGestureDetector d){zoom=Math.max(1,Math.min(4,zoom*d.getScaleFactor()));transformed=true;fit();return true;}
        });
        setContentDescription(com.deepseekharness.app.util.UiText.choose("虚拟屏预览，双指缩放","Virtual screen preview, pinch to zoom"));
    }
    void pan(boolean enabled){pan=enabled;}
    void cancelStream(){if(streaming&&listener!=null)listener.stream(generation,seq,stroke,MotionEvent.ACTION_CANCEL,0,0);streaming=false;}
    void frame(Bitmap next,JSONObject value){
        if(touching){next.recycle();return;}
        if(screenW!=value.optInt("width")||screenH!=value.optInt("height")){zoom=1;offsetX=offsetY=0;}
        screenW=value.optInt("width");screenH=value.optInt("height");seq=value.optLong("frameSeq",-1);generation=value.optString("generation");
        Bitmap old=bitmap;bitmap=next;setImageBitmap(next);fit();if(old!=null&&old!=next)old.recycle();
    }
    void clear(){setImageDrawable(null);if(bitmap!=null)bitmap.recycle();bitmap=null;seq=-1;}
    @Override protected void onSizeChanged(int w,int h,int oldW,int oldH){super.onSizeChanged(w,h,oldW,oldH);fit();}
    private void fit(){
        if(bitmap==null)return;
        float factor=Math.min(getWidth()/(float)bitmap.getWidth(),getHeight()/(float)bitmap.getHeight())*zoom;
        float maxX=Math.max(0,(bitmap.getWidth()*factor-getWidth())/2),maxY=Math.max(0,(bitmap.getHeight()*factor-getHeight())/2);
        offsetX=Math.max(-maxX,Math.min(maxX,offsetX));offsetY=Math.max(-maxY,Math.min(maxY,offsetY));
        matrix.reset();matrix.postScale(factor,factor);matrix.postTranslate((getWidth()-bitmap.getWidth()*factor)/2+offsetX,(getHeight()-bitmap.getHeight()*factor)/2+offsetY);setImageMatrix(matrix);
    }
    private float[] point(float x,float y){
        if(bitmap==null||!matrix.invert(inverse))return null;
        float[] p={x,y};inverse.mapPoints(p);
        if(p[0]<0||p[1]<0||p[0]>=bitmap.getWidth()||p[1]>=bitmap.getHeight())return null;
        p[0]=p[0]*screenW/bitmap.getWidth();p[1]=p[1]*screenH/bitmap.getHeight();return p;
    }
    @Override public boolean onTouchEvent(MotionEvent e){
        scale.onTouchEvent(e);
        switch(e.getActionMasked()){
            case MotionEvent.ACTION_DOWN:
                touching=true;transformed=false;downX=lastX=e.getX();downY=lastY=e.getY();downAt=android.os.SystemClock.elapsedRealtime();stroke=java.util.UUID.randomUUID().toString();streaming=!pan&&seq>0;if(streaming&&listener!=null){float[] p=point(downX,downY);if(p!=null)listener.stream(generation,seq,stroke,MotionEvent.ACTION_DOWN,p[0],p[1]);}getParent().requestDisallowInterceptTouchEvent(true);return true;
            case MotionEvent.ACTION_POINTER_DOWN:cancelStream();transformed=true;return true;
            case MotionEvent.ACTION_MOVE:
                if(pan||e.getPointerCount()>1){cancelStream();offsetX+=e.getX()-lastX;offsetY+=e.getY()-lastY;transformed=true;fit();}
                else if(streaming&&android.os.SystemClock.elapsedRealtime()-lastStream>24&&listener!=null){float[] p=point(e.getX(),e.getY());if(p!=null)listener.stream(generation,seq,stroke,MotionEvent.ACTION_MOVE,p[0],p[1]);lastStream=android.os.SystemClock.elapsedRealtime();}
                lastX=e.getX();lastY=e.getY();return true;
            case MotionEvent.ACTION_UP:
                if(streaming&&listener!=null){float[] p=point(e.getX(),e.getY());if(p!=null)listener.stream(generation,seq,stroke,MotionEvent.ACTION_UP,p[0],p[1]);streaming=false;}
                touching=false;getParent().requestDisallowInterceptTouchEvent(false);
                if(!transformed&&!pan&&listener!=null){
                    float[] from=point(downX,downY),to=point(e.getX(),e.getY());
                    if(from!=null&&to!=null&&seq>0){
                        boolean tap=Math.hypot(e.getX()-downX,e.getY()-downY)<android.view.ViewConfiguration.get(getContext()).getScaledTouchSlop();
                        listener.input(generation,seq,from[0],from[1],to[0],to[1],(int)Math.max(50,Math.min(3000,android.os.SystemClock.elapsedRealtime()-downAt)),tap);
                        performClick();
                    }
                }return true;
            case MotionEvent.ACTION_CANCEL:cancelStream();touching=false;getParent().requestDisallowInterceptTouchEvent(false);return true;
            default:return true;
        }
    }
    @Override public boolean performClick(){super.performClick();return true;}
}

