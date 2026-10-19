package com.localcaption.instant;

import android.animation.*;
import android.content.Context;
import android.graphics.*;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.view.*;
import android.view.accessibility.AccessibilityNodeInfo;
import android.widget.*;

/** A single movable window. A tucked window occupies only the edge handle's touch area. */
@android.annotation.SuppressLint("RtlHardcoded") // Left/right docking uses physical screen edges in every locale.
final class FloatingCaptionOverlay {
    private final Context context;
    private final WindowManager windows;
    private final Rect safe=new Rect();
    private Surface surface;
    private FloatingCaptionPanel panel;
    private TextView translated,recognized,handle;
    private WindowManager.LayoutParams params;
    private ValueAnimator animation;
    private int edge; // -1 left, 0 expanded, +1 right
    private float horizontal=.5f,centerY=.72f;
    private boolean dragging;
    private String latestText="",latestSource="";
    private static final int HIDE_LEFT=0x01020001,HIDE_RIGHT=0x01020002;

    FloatingCaptionOverlay(Context context){this.context=context;windows=context.getSystemService(WindowManager.class);}
    private int dp(float n){return Ui.dp(context,n);}
    void show(String text,String source){
        latestText=text;latestSource=source;
        if(surface==null)attach();
        updateText();
        if(!dragging&&animation==null)place();
    }
    void hide(){
        cancelAnimation();dragging=false;
        if(surface!=null){try{windows.removeViewImmediate(surface);}catch(IllegalArgumentException ignored){}surface=null;}
    }
    void configurationChanged(){boolean visible=surface!=null;hide();if(visible)show(latestText,latestSource);}
    private void readBounds(){
        if(Build.VERSION.SDK_INT>=30){
            WindowMetrics metrics=windows.getCurrentWindowMetrics();safe.set(metrics.getBounds());
            Insets i=metrics.getWindowInsets().getInsetsIgnoringVisibility(WindowInsets.Type.systemBars()|WindowInsets.Type.displayCutout());
            safe.set(safe.left+i.left,safe.top+i.top,safe.right-i.right,safe.bottom-i.bottom);
        }else{
            android.util.DisplayMetrics metrics=new android.util.DisplayMetrics();windows.getDefaultDisplay().getRealMetrics(metrics);
            safe.set(0,dp(28),metrics.widthPixels,metrics.heightPixels-dp(32));
        }
    }
    private void attach(){
        readBounds();surface=new Surface();
        panel=new FloatingCaptionPanel(context,false);translated=panel.translated;recognized=panel.recognized;
        surface.addView(panel,new FrameLayout.LayoutParams(-1,-2));
        handle=label("",28,Ui.MINT);handle.setGravity(Gravity.CENTER);
        surface.addView(handle,new FrameLayout.LayoutParams(dp(32),dp(64),Gravity.LEFT));
        surface.setOnClickListener(v->{if(edge!=0)expand();});
        params=new WindowManager.LayoutParams(1,1,WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE|WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL|
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN|WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,PixelFormat.TRANSLUCENT);
        params.gravity=Gravity.TOP|Gravity.LEFT;params.setTitle("Instant Caption floating subtitles");
        if(Build.VERSION.SDK_INT>=30)params.setFitInsetsTypes(0);
        updateText();measureAndPosition();
        try{windows.addView(surface,params);}catch(RuntimeException e){surface=null;throw e;}
    }
    private TextView label(String text,int size,int color){TextView v=Ui.text(context,text,size,color);v.setIncludeFontPadding(false);return v;}
    private void updateText(){
        if(surface==null)return;
        panel.bind(latestText,latestSource);
    }
    private void measureAndPosition(){
        boolean tucked=edge!=0;panel.setVisibility(tucked?View.GONE:View.VISIBLE);handle.setVisibility(tucked?View.VISIBLE:View.GONE);
        if(tucked){
            Ui.setText(handle,edge<0?"›":"‹");
            FrameLayout.LayoutParams hp=(FrameLayout.LayoutParams)handle.getLayoutParams();hp.gravity=edge<0?Gravity.LEFT:Gravity.RIGHT;handle.setLayoutParams(hp);
            GradientDrawable bg=Ui.bg(context,0xF20B1118,0);float r=dp(16);bg.setCornerRadii(edge<0?new float[]{0,0,r,r,r,r,0,0}:new float[]{r,r,0,0,0,0,r,r});handle.setBackground(bg);
        }
        surface.setContentDescription(tucked?Ui.t(context,"展开悬浮字幕","Expand floating captions"):null);
        params.width=tucked?dp(48):Math.min(safe.width()-dp(24),dp(520));
        surface.measure(View.MeasureSpec.makeMeasureSpec(params.width,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(safe.height(),View.MeasureSpec.AT_MOST));
        params.height=tucked?dp(64):Math.min(safe.height(),surface.getMeasuredHeight());
        params.x=tucked?(edge<0?safe.left:safe.right-params.width):safe.left+dp(12)+Math.round(horizontal*Math.max(0,safe.width()-params.width-dp(24)));
        params.y=clamp(safe.top+Math.round(centerY*safe.height())-params.height/2,safe.top,safe.bottom-params.height);
    }
    private void place(){
        if(surface==null)return;
        int x=params.x,y=params.y,w=params.width,h=params.height;measureAndPosition();
        if(x!=params.x||y!=params.y||w!=params.width||h!=params.height)updateWindow();
    }
    private void updateWindow(){if(surface!=null&&surface.isAttachedToWindow())windows.updateViewLayout(surface,params);}
    private static int clamp(int n,int lo,int hi){return Math.max(lo,Math.min(n,Math.max(lo,hi)));}
    private void rememberPosition(){
        centerY=Math.max(0,Math.min(1,(params.y+params.height/2f-safe.top)/safe.height()));
        if(edge==0){int range=safe.width()-params.width-dp(24);if(range>0)horizontal=Math.max(0,Math.min(1,(params.x-safe.left-dp(12))/(float)range));}
    }
    private void cancelAnimation(){if(animation!=null){ValueAnimator old=animation;animation=null;old.cancel();}if(surface!=null)panel.setTranslationX(0);}
    private void dock(int side){
        if(surface==null)return;cancelAnimation();rememberPosition();
        // Animate the remaining visible part away, then resize the actual window to 48 dp.
        ValueAnimator next=ValueAnimator.ofFloat(0,side<0?safe.left-params.x-params.width:safe.right-params.x);
        animation=next;next.setDuration(150);next.addUpdateListener(a->{if(surface!=null)panel.setTranslationX((float)a.getAnimatedValue());});
        next.addListener(new AnimatorListenerAdapter(){@Override public void onAnimationEnd(Animator a){if(animation!=a||surface==null)return;animation=null;panel.setTranslationX(0);edge=side;measureAndPosition();updateWindow();}});next.start();
    }
    private void expand(){
        if(surface==null)return;cancelAnimation();horizontal=edge<0?0:1;edge=0;updateText();measureAndPosition();updateWindow();
    }
    private final class Surface extends FrameLayout {
        private float startX,startY;
        private int windowX,windowY,pointerId;
        private boolean moved;
        Surface(){super(context);setClickable(true);setFocusable(true);setClipChildren(false);}
        @Override public boolean onInterceptTouchEvent(MotionEvent e){return true;}
        @Override public boolean onTouchEvent(MotionEvent event){
            int action=event.getActionMasked();
            if(action==MotionEvent.ACTION_DOWN){cancelAnimation();dragging=true;moved=false;pointerId=event.getPointerId(0);startX=event.getRawX();startY=event.getRawY();windowX=params.x;windowY=params.y;return true;}
            if(!dragging)return true;
            if(action==MotionEvent.ACTION_POINTER_DOWN||action==MotionEvent.ACTION_CANCEL){dragging=false;rememberPosition();place();return true;}
            int index=event.findPointerIndex(pointerId);if(index<0)return true;
            float dx=event.getRawX(index)-startX,dy=event.getRawY(index)-startY;
            if(Math.hypot(dx,dy)>ViewConfiguration.get(context).getScaledTouchSlop())moved=true;
            if(action==MotionEvent.ACTION_MOVE&&moved){
                params.x=edge==0?clamp(windowX+Math.round(dx),safe.left-params.width+dp(48),safe.right-dp(48)):edge<0?safe.left:safe.right-params.width;
                params.y=clamp(windowY+Math.round(dy),safe.top,safe.bottom-params.height);updateWindow();return true;
            }
            if(action==MotionEvent.ACTION_UP){
                dragging=false;
                if(!moved){performClick();return true;}
                rememberPosition();
                boolean horizontalIntent=Math.abs(dx)>=dp(40)&&Math.abs(dx)>.65f*Math.abs(dy);
                if(edge==0&&horizontalIntent&&dx<0&&params.x<=safe.left+dp(20))dock(-1);
                else if(edge==0&&horizontalIntent&&dx>0&&params.x+params.width>=safe.right-dp(20))dock(1);
                else if(edge!=0&&horizontalIntent&&dx*edge<0)expand();
                else place();
                return true;
            }
            return true;
        }
        @Override public boolean performClick(){super.performClick();return true;}
        @Override public void onInitializeAccessibilityNodeInfo(AccessibilityNodeInfo info){
            super.onInitializeAccessibilityNodeInfo(info);
            if(edge==0){info.addAction(new AccessibilityNodeInfo.AccessibilityAction(HIDE_LEFT,Ui.t(context,"隐藏到左边缘","Hide at left edge")));info.addAction(new AccessibilityNodeInfo.AccessibilityAction(HIDE_RIGHT,Ui.t(context,"隐藏到右边缘","Hide at right edge")));}
        }
        @Override public boolean performAccessibilityAction(int action,android.os.Bundle args){
            if(action==HIDE_LEFT||action==HIDE_RIGHT){dock(action==HIDE_LEFT?-1:1);return true;}return super.performAccessibilityAction(action,args);
        }
    }
}
