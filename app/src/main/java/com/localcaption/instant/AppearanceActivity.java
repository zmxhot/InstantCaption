package com.localcaption.instant;

import android.content.Intent;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Bundle;
import android.provider.Settings;
import android.view.*;
import android.widget.*;

/** Caption styling has its own preview, independent of language and model settings. */
public class AppearanceActivity extends BaseActivity {
    private LinearLayout body;
    private Button mainTab,floatingTab;
    private ScrollView scroll;
    private Switch overlay;
    private FloatingCaptionPanel overlayPreview;
    private TextView mainPreview,sourcePreview;
    private boolean floating,refreshing;
    @Override public void onCreate(Bundle saved){super.onCreate(saved);floating=saved==null?getIntent().getBooleanExtra("floating",false):saved.getBoolean("floating");build();}
    private void build(){
        LinearLayout root=Ui.root(this);Ui.title(this,root,getString(R.string.caption_appearance));
        LinearLayout tabs=Ui.row(this);tabs.setBackground(Ui.bg(this,Ui.CARD,14));tabs.setPadding(dp(4),dp(4),dp(4),dp(4));
        mainTab=Ui.button(this,getString(R.string.caption_main_screen),Ui.CARD,Ui.WHITE);floatingTab=Ui.button(this,tr("悬浮字幕","Floating captions"),Ui.CARD,Ui.WHITE);
        boolean stacked=getResources().getConfiguration().fontScale>=1.3f;if(stacked)tabs.setOrientation(LinearLayout.VERTICAL);
        tabs.addView(mainTab,new LinearLayout.LayoutParams(stacked?-1:0,-2,stacked?0:1));tabs.addView(floatingTab,new LinearLayout.LayoutParams(stacked?-1:0,-2,stacked?0:1));root.addView(tabs);
        mainTab.setOnClickListener(v->select(false));floatingTab.setOnClickListener(v->select(true));
        scroll=new ScrollView(this);scroll.setClipToPadding(false);body=Ui.column(this);body.setPadding(0,dp(12),0,dp(20));scroll.addView(body);root.addView(scroll,new LinearLayout.LayoutParams(-1,0,1));render();
    }
    private void select(boolean next){if(floating==next)return;floating=next;render();scroll.scrollTo(0,0);}
    private void render(){
        body.removeAllViews();overlayPreview=null;mainPreview=null;sourcePreview=null;overlay=null;
        selected(mainTab,!floating);selected(floatingTab,floating);
        LinearLayout controls=Ui.column(this);controls.setPadding(dp(14),dp(4),dp(14),dp(4));controls.setBackground(Ui.bg(this,Ui.CARD,16));body.addView(controls);
        if(floating){
            overlay=toggle(controls,tr("悬浮字幕","Floating captions"),tr("整块可拖动 · 拖到屏幕边缘隐藏，点击把手展开","Drag anywhere · tuck at an edge, tap the handle to expand"),AppPrefs.overlay(this),(v,on)->{
                if(refreshing)return;AppPrefs.put(this,"overlay",on);CaptionState.S.overlay=on&&Settings.canDrawOverlays(this);CaptionState.S.changed();
                if(on&&!Settings.canDrawOverlays(this))startActivity(new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,Uri.parse("package:"+getPackageName())));
            });divider(controls);
            toggle(controls,getString(R.string.overlay_source),getString(R.string.overlay_source_hint),AppPrefs.overlaySource(this),(v,on)->{AppPrefs.put(this,"overlaySource",on);preview();});divider(controls);
            slider(controls,"overlayFont",tr("悬浮字幕字号","Floating caption size"),"",18,AppPrefs.overlayFont(this)-20,false);divider(controls);
            slider(controls,"overlayTransparency",getString(R.string.overlay_transparency),getString(R.string.overlay_transparency_hint),100,AppPrefs.overlayTransparency(this),false);divider(controls);
            int height=AppPrefs.overlayHeight(this);slider(controls,"overlayHeight",getString(R.string.overlay_height),getString(R.string.overlay_height_hint),29,height==0?0:1+(height-120)/10,true);
            overlayPreview=new FloatingCaptionPanel(this,true);add(overlayPreview,12);
        }else{
            toggle(controls,tr("显示识别文字","Show recognized text"),getString(R.string.source_main_only),AppPrefs.english(this),(v,on)->{AppPrefs.put(this,"english",on);preview();});divider(controls);
            slider(controls,"font",tr("主界面字幕字号","Main caption size"),"",24,AppPrefs.font(this)-20,false);
            LinearLayout card=Ui.column(this);card.setPadding(dp(16),dp(16),dp(16),dp(16));card.setBackground(Ui.bg(this,0xFF101923,16));
            TextView heading=Ui.text(this,getString(R.string.caption_preview),12,Ui.MINT);card.addView(heading);
            mainPreview=Ui.text(this,"",AppPrefs.font(this),Ui.WHITE);mainPreview.setTypeface(null,Typeface.BOLD);mainPreview.setPadding(0,dp(12),0,dp(6));card.addView(mainPreview);
            sourcePreview=Ui.text(this,"Welcome. Translated captions appear here.",16,Ui.MUTED);card.addView(sourcePreview);add(card,12);
        }
        preview();
    }
    private void selected(Button button,boolean on){button.setBackground(Ui.bg(this,on?0xFF203B34:Ui.CARD,11));button.setTextColor(on?Ui.MINT:Ui.MUTED);button.setSelected(on);}
    private void add(View v,int margin){LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(-1,-2);p.topMargin=dp(margin);body.addView(v,p);}
    private void divider(LinearLayout parent){View line=new View(this);line.setBackgroundColor(0xFF26333F);parent.addView(line,new LinearLayout.LayoutParams(-1,dp(1)));}
    private Switch toggle(LinearLayout parent,String name,String hint,boolean value,CompoundButton.OnCheckedChangeListener change){
        LinearLayout row=Ui.row(this);row.setPadding(0,dp(10),0,dp(10));LinearLayout text=Ui.column(this);text.setPaddingRelative(0,0,dp(10),0);text.addView(Ui.text(this,name,14,Ui.WHITE));TextView detail=Ui.text(this,hint,11,Ui.MUTED);detail.setPadding(0,dp(4),0,0);text.addView(detail);row.addView(text,new LinearLayout.LayoutParams(0,-2,1));
        Switch control=new Switch(this);control.setContentDescription(name);control.setChecked(value);control.setOnCheckedChangeListener(change);row.addView(control,new LinearLayout.LayoutParams(dp(52),dp(48)));parent.addView(row);return control;
    }
    private void slider(LinearLayout parent,String key,String name,String hint,int max,int current,boolean height){
        LinearLayout box=Ui.column(this);box.setPadding(0,dp(12),0,dp(6));LinearLayout row=Ui.row(this);TextView title=Ui.text(this,name,14,Ui.WHITE);row.addView(title,new LinearLayout.LayoutParams(0,-2,1));TextView value=Ui.text(this,"",13,Ui.MINT);row.addView(value);box.addView(row);
        if(!hint.isEmpty()){TextView text=Ui.text(this,hint,11,Ui.MUTED);text.setPadding(0,dp(4),0,dp(2));box.addView(text);}
        SeekBar seek=new SeekBar(this);seek.setContentDescription(name);seek.setMax(max);seek.setProgress(current);seek.setPadding(dp(5),0,dp(5),0);box.addView(seek,new LinearLayout.LayoutParams(-1,dp(44)));parent.addView(box);
        Ui.setText(value,valueLabel(key,sliderValue(key,current)));
        seek.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener(){public void onProgressChanged(SeekBar bar,int progress,boolean user){int selected=sliderValue(key,progress);Ui.setText(value,valueLabel(key,selected));if(user){AppPrefs.put(AppearanceActivity.this,key,selected);preview();}}public void onStartTrackingTouch(SeekBar bar){}public void onStopTrackingTouch(SeekBar bar){}});
    }
    private int sliderValue(String key,int progress){return key.equals("overlayHeight")?(progress==0?0:110+progress*10):key.equals("overlayTransparency")?progress:20+progress;}
    private String valueLabel(String key,int value){return key.equals("overlayHeight")&&value==0?getString(R.string.overlay_height_auto):value+(key.equals("overlayTransparency")?"%":"");}
    private void preview(){String text=tr("欢迎，这里显示翻译字幕。","Welcome. Translated captions appear here.");if(overlayPreview!=null)overlayPreview.bind(text,"Welcome. Translated captions appear here.");if(mainPreview!=null){Ui.setText(mainPreview,text);mainPreview.setTextSize(AppPrefs.font(this));sourcePreview.setVisibility(AppPrefs.english(this)?View.VISIBLE:View.GONE);}}
    @Override protected void onResume(){super.onResume();if(overlay!=null){refreshing=true;if(AppPrefs.overlay(this)&&!Settings.canDrawOverlays(this))AppPrefs.put(this,"overlay",false);CaptionState.S.overlay=AppPrefs.overlay(this)&&Settings.canDrawOverlays(this);overlay.setChecked(CaptionState.S.overlay);refreshing=false;}}
    @Override protected void onSaveInstanceState(Bundle out){out.putBoolean("floating",floating);super.onSaveInstanceState(out);}
}
