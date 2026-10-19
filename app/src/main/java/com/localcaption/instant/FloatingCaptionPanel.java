package com.localcaption.instant;

import android.content.Context;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.text.TextUtils;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;

/** Shared by the floating window and its live settings preview. */
final class FloatingCaptionPanel extends LinearLayout {
    final TextView translated,recognized;
    private final TextView title;
    private int lastHeight=-1;
    FloatingCaptionPanel(Context c,boolean preview){
        super(c);setOrientation(VERTICAL);
        setLayoutDirection(TextUtils.getLayoutDirectionFromLocale(AppLanguage.locale(c)));
        setPadding(dp(14),dp(10),dp(14),dp(12));
        GradientDrawable bg=Ui.bg(c,0xEF0B1118,18);bg.setStroke(dp(1),0x605DCEAE);setBackground(bg);
        title=label(preview?c.getString(R.string.overlay_preview):Ui.t(c,"● 瞬译 · 拖至边缘隐藏","● Captions · drag to an edge to hide"),11,Ui.MINT);addView(title);
        translated=label("",AppPrefs.overlayFont(c),0xFFFFFFFF);translated.setTypeface(null,Typeface.BOLD);
        LayoutParams tp=new LayoutParams(-1,-2);tp.topMargin=dp(6);addView(translated,tp);
        recognized=label("",16,0xFFB2C0CF);LayoutParams sp=new LayoutParams(-1,-2);sp.topMargin=dp(5);addView(recognized,sp);
        translated.setEllipsize(TextUtils.TruncateAt.END);recognized.setEllipsize(TextUtils.TruncateAt.END);
    }
    private int dp(float value){return Ui.dp(getContext(),value);}
    private TextView label(String text,int size,int color){TextView v=Ui.text(getContext(),text,size,color);v.setIncludeFontPadding(false);return v;}
    void bind(String text,String source){
        Ui.setText(translated,text);Ui.setText(recognized,source);
        translated.setTextSize(AppPrefs.overlayFont(getContext()));
        recognized.setTextSize(Math.max(16,AppPrefs.overlayFont(getContext())*.65f));
        float alpha=1-AppPrefs.overlayTransparency(getContext())/100f;
        translated.setAlpha(alpha);recognized.setAlpha(alpha);
        recognized.setVisibility(AppPrefs.overlaySource(getContext())&&!TextUtils.isEmpty(source)?View.VISIBLE:View.GONE);
        int height=AppPrefs.overlayHeight(getContext());if(height!=lastHeight){lastHeight=height;requestLayout();}
    }
    @Override protected void onMeasure(int widthSpec,int heightSpec){
        int maxHeight=MeasureSpec.getMode(heightSpec)==MeasureSpec.UNSPECIFIED
            ?Math.max(dp(120),getResources().getDisplayMetrics().heightPixels-dp(80)):MeasureSpec.getSize(heightSpec);
        int width=Math.max(0,MeasureSpec.getSize(widthSpec)-getPaddingLeft()-getPaddingRight());
        title.measure(MeasureSpec.makeMeasureSpec(width,MeasureSpec.EXACTLY),MeasureSpec.makeMeasureSpec(0,MeasureSpec.UNSPECIFIED));
        boolean source=recognized.getVisibility()==View.VISIBLE;
        int base=getPaddingTop()+getPaddingBottom()+title.getMeasuredHeight()+dp(6)+(source?dp(5):0);
        int targetLine=Math.max(1,translated.getLineHeight()),sourceLine=Math.max(1,recognized.getLineHeight());
        // Measure actual glyph rows: fallback fonts and nonlinear system font scaling can
        // make a first line taller than getLineHeight() suggests.
        int targetMinimum=measureText(translated,1,width),sourceMinimum=source?measureText(recognized,1,width):0;
        int requested=AppPrefs.overlayHeight(getContext());
        // Large system fonts may need more room than the requested minimum; always stay on screen.
        int height=requested==0?maxHeight:Math.min(maxHeight,Math.max(dp(requested),base+targetMinimum+sourceMinimum));
        int room=Math.max(0,height-base),sourceHeight=0;
        if(source){
            int lines=Math.max(1,Math.min(requested==0?2:8,(room-targetMinimum)/Math.max(1,3*sourceLine)));
            sourceHeight=measureText(recognized,lines,width);
            while(lines>1&&sourceHeight>room-targetMinimum)sourceHeight=measureText(recognized,--lines,width);
        }
        int lines=Math.max(1,Math.min(requested==0?6:30,(room-sourceHeight)/targetLine));
        int targetHeight=measureText(translated,lines,width);
        while(lines>1&&targetHeight>room-sourceHeight)targetHeight=measureText(translated,--lines,width);
        super.onMeasure(widthSpec,MeasureSpec.makeMeasureSpec(height,requested==0?MeasureSpec.AT_MOST:MeasureSpec.EXACTLY));
    }
    private int measureText(TextView view,int lines,int width){view.setMaxLines(lines);view.measure(MeasureSpec.makeMeasureSpec(width,MeasureSpec.EXACTLY),MeasureSpec.makeMeasureSpec(0,MeasureSpec.UNSPECIFIED));return view.getMeasuredHeight();}
}
