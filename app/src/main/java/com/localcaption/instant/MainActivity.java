package com.localcaption.instant;

import android.Manifest;
import android.content.*;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.Typeface;
import android.media.projection.MediaProjectionManager;
import android.os.*;
import android.view.*;
import android.widget.*;
import java.util.LinkedHashMap;
import java.util.Map;

/** Caption-first screen. Configuration and model storage live in Settings. */
public class MainActivity extends BaseActivity implements CaptionState.Observer {
    public static boolean visible;
    private TextView status,count,empty,direction,models;
    private Button start,mic,phone,retry,recover;
    private ProgressBar meter;
    private LinearLayout rows,header,sources,toolbar,focusHeader,footer;
    private ScrollView scroll;
    private View landscapeControls;
    private boolean playback,focus;
    private int startColor;
    private final Map<Long,Row> rowViews=new LinkedHashMap<>();
    private String lastDigest="";
    private static final class Row { LinearLayout view;TextView en,zh,tag; }
    @Override public void onCreate(Bundle b){
        super.onCreate(b);
        if(!AppPrefs.prefs(this).contains("setupComplete"))AppPrefs.prefs(this).edit().putBoolean("setupComplete",getSharedPreferences("engine",0).contains("speechSource")).apply();
        if(!ModelDownloadService.working)DownloadJournal.state(this,false);
        playback=AppPrefs.legacy(this).getBoolean("playback",false);focus=b!=null&&b.getBoolean("focus");
        CaptionState.S.overlay=AppPrefs.overlay(this);build();CaptionState.S.observe(this);changed();LocalEngine.get(this).prepare();
        if(b==null&&!AppPrefs.prefs(this).getBoolean("setupComplete",false))startActivity(new Intent(this,SetupActivity.class));
    }
    private Button button(String s,int c,int tc){return Ui.button(this,s,c,tc);}
    private void build(){
        rowViews.clear();lastDigest="";startColor=0;landscapeControls=null;LinearLayout root=Ui.root(this);
        boolean largeType=getResources().getConfiguration().fontScale>=1.3f||getResources().getConfiguration().screenWidthDp<340;
        header=largeType?Ui.column(this):Ui.row(this);TextView brand=Ui.text(this,tr("瞬译","Instant Caption"),22,Ui.WHITE);brand.setTypeface(null,Typeface.BOLD);brand.setMaxLines(1);brand.setMinHeight(dp(48));brand.setAutoSizeTextTypeUniformWithConfiguration(16,22,1,android.util.TypedValue.COMPLEX_UNIT_SP);header.addView(brand,new LinearLayout.LayoutParams(largeType?-1:0,-2,largeType?0:1));
        LinearLayout actions=largeType?Ui.row(this):header;
        Button full=button(tr("全屏","Focus"),Color.TRANSPARENT,Ui.MINT);full.setOnClickListener(v->setFocus(true));full.setMaxLines(1);full.setAutoSizeTextTypeUniformWithConfiguration(10,14,1,android.util.TypedValue.COMPLEX_UNIT_SP);actions.addView(full,new LinearLayout.LayoutParams(largeType?0:dp(64),-2,largeType?1:0));
        Button settings=button(tr("设置","Settings"),Ui.CARD,Ui.WHITE);settings.setOnClickListener(v->startActivity(new Intent(this,SettingsActivity.class)));settings.setMaxLines(1);settings.setAutoSizeTextTypeUniformWithConfiguration(10,14,1,android.util.TypedValue.COMPLEX_UNIT_SP);actions.addView(settings,new LinearLayout.LayoutParams(largeType?0:dp(88),-2,largeType?1:0));if(largeType)header.addView(actions,new LinearLayout.LayoutParams(-1,-2));
        LinearLayout.LayoutParams headerLayout=new LinearLayout.LayoutParams(-1,-2);headerLayout.bottomMargin=dp(12);root.addView(header,headerLayout);
        focusHeader=Ui.row(this);Button exit=button(tr("‹ 退出全屏","‹ Exit focus"),Color.TRANSPARENT,Ui.MINT);exit.setOnClickListener(v->setFocus(false));focusHeader.addView(exit,new LinearLayout.LayoutParams(0,-2,1));fontButtons(focusHeader);root.addView(focusHeader);
        sources=Ui.row(this);sources.setPadding(dp(4),dp(4),dp(4),dp(4));sources.setBackground(Ui.bg(this,Ui.CARD,14));mic=button(tr("麦克风","Microphone"),Ui.CARD,Ui.WHITE);phone=button(tr("手机声音","Device audio"),Ui.CARD,Ui.WHITE);mic.setOnClickListener(v->select(false));phone.setOnClickListener(v->select(true));
        LinearLayout.LayoutParams source=new LinearLayout.LayoutParams(0,-2,1);source.setMarginEnd(dp(6));sources.addView(mic,source);sources.addView(phone,new LinearLayout.LayoutParams(0,-2,1));root.addView(sources);
        toolbar=Ui.row(this);direction=Ui.text(this,"",12,Ui.MUTED);toolbar.addView(direction,new LinearLayout.LayoutParams(0,-2,1));count=Ui.text(this,"",11,Ui.MUTED);toolbar.addView(count);fontButtons(toolbar);root.addView(toolbar);
        scroll=new ScrollView(this);scroll.setFillViewport(true);scroll.setBackground(Ui.bg(this,0xFF101923,16));scroll.setClipToPadding(false);
        rows=Ui.column(this);rows.setPadding(dp(16),dp(12),dp(16),dp(12));empty=Ui.text(this,getString(R.string.empty_caption),16,Ui.MUTED);empty.setGravity(Gravity.CENTER);empty.setLineSpacing(dp(4),1);empty.setCompoundDrawablesWithIntrinsicBounds(0,R.drawable.ic_caption_preview,0,0);empty.setCompoundDrawablePadding(dp(16));empty.setPadding(dp(8),dp(12),dp(8),dp(12));rows.addView(empty,new LinearLayout.LayoutParams(-1,-2));scroll.addView(rows);root.addView(scroll,new LinearLayout.LayoutParams(-1,0,1));
        footer=Ui.column(this);models=Ui.text(this,"",11,Ui.MUTED);models.setPadding(dp(2),dp(10),dp(2),dp(4));models.setLineSpacing(dp(2),1);footer.addView(models);status=Ui.text(this,"",12,Ui.MINT);status.setMaxLines(2);status.setPadding(dp(2),dp(3),dp(2),dp(7));footer.addView(status);
        meter=new ProgressBar(this,null,android.R.attr.progressBarStyleHorizontal);meter.setMax(100);meter.setProgressTintList(android.content.res.ColorStateList.valueOf(Ui.MINT));footer.addView(meter,new LinearLayout.LayoutParams(-1,dp(3)));
        recover=button("",Ui.CARD,Ui.MINT);recover.setOnClickListener(v->{LocalEngine e=LocalEngine.get(this);if(e.recoveryPlan()!=null)e.recoverInstalledModels();else startActivity(new Intent(this,ModelManagerActivity.class));});footer.addView(recover,new LinearLayout.LayoutParams(-1,-2));
        retry=button(tr("打开模型管理 / 重试","Models / Retry"),Ui.CARD,Ui.MINT);retry.setOnClickListener(v->{LocalEngine engine=LocalEngine.get(this);if(engine.hasModelFailure())engine.retryModels();else if(!engine.translationAvailable())startActivity(new Intent(this,SetupActivity.class));else if(!ModelStore.installed(this,engine.selectedModel())||!engine.translationReady())startActivity(new Intent(this,SetupActivity.class));else engine.prepare();});footer.addView(retry,new LinearLayout.LayoutParams(-1,-2));root.addView(footer);
        start=button("",Ui.MINT,Ui.BG);start.setMinHeight(dp(56));start.setTextSize(17);start.setTypeface(null,Typeface.BOLD);start.setOnClickListener(v->{if(CaptionState.S.running)stopService(new Intent(this,CaptionService.class));else begin();});LinearLayout.LayoutParams bp=new LinearLayout.LayoutParams(-1,-2);bp.topMargin=dp(7);root.addView(start,bp);
        if(getResources().getConfiguration().screenWidthDp>=600&&getResources().getConfiguration().screenHeightDp<450){
            // Keep a useful subtitle area on a landscape phone, even with model/status controls.
            root.removeView(sources);root.removeView(toolbar);root.removeView(scroll);root.removeView(footer);
            LinearLayout split=Ui.row(this);ScrollView controls=new ScrollView(this);LinearLayout column=Ui.column(this);column.addView(sources);column.addView(footer);controls.addView(column);landscapeControls=controls;
            LinearLayout.LayoutParams left=new LinearLayout.LayoutParams(0,-1,.38f);left.setMarginEnd(dp(12));split.addView(controls,left);
            LinearLayout captions=Ui.column(this);captions.addView(toolbar);captions.addView(scroll,new LinearLayout.LayoutParams(-1,0,1));split.addView(captions,new LinearLayout.LayoutParams(0,-1,.62f));root.addView(split,root.indexOfChild(start),new LinearLayout.LayoutParams(-1,0,1));
        }
        select(playback);setFocus(focus);
    }
    private void fontButtons(LinearLayout row){
        Button minus=button("A−",Color.TRANSPARENT,Ui.MUTED),plus=button("A+",Color.TRANSPARENT,Ui.WHITE);
        minus.setMaxLines(1);plus.setMaxLines(1);
        minus.setAutoSizeTextTypeUniformWithConfiguration(10,14,1,android.util.TypedValue.COMPLEX_UNIT_SP);plus.setAutoSizeTextTypeUniformWithConfiguration(10,14,1,android.util.TypedValue.COMPLEX_UNIT_SP);
        minus.setContentDescription(tr("缩小字幕","Smaller captions"));plus.setContentDescription(tr("放大字幕","Larger captions"));
        minus.setOnClickListener(v->AppPrefs.put(this,"font",Math.max(20,AppPrefs.font(this)-2)));plus.setOnClickListener(v->AppPrefs.put(this,"font",Math.min(44,AppPrefs.font(this)+2)));
        row.addView(minus,new LinearLayout.LayoutParams(dp(48),dp(48)));row.addView(plus,new LinearLayout.LayoutParams(dp(48),dp(48)));
    }
    private void setFocus(boolean on){
        focus=on;header.setVisibility(on?View.GONE:View.VISIBLE);sources.setVisibility(on?View.GONE:View.VISIBLE);toolbar.setVisibility(on?View.GONE:View.VISIBLE);focusHeader.setVisibility(on?View.VISIBLE:View.GONE);footer.setVisibility(on?View.GONE:View.VISIBLE);
        if(landscapeControls!=null)landscapeControls.setVisibility(on?View.GONE:View.VISIBLE);
        if(Build.VERSION.SDK_INT>=30){WindowInsetsController controller=getWindow().getInsetsController();if(controller!=null){controller.setSystemBarsBehavior(WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);if(on)controller.hide(WindowInsets.Type.systemBars());else controller.show(WindowInsets.Type.systemBars());}}
        else getWindow().getDecorView().setSystemUiVisibility(on?View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY|View.SYSTEM_UI_FLAG_FULLSCREEN|View.SYSTEM_UI_FLAG_HIDE_NAVIGATION:View.SYSTEM_UI_FLAG_VISIBLE);
    }
    @Override public void onBackPressed(){if(focus)setFocus(false);else super.onBackPressed();}
    @Override protected void onSaveInstanceState(Bundle b){b.putBoolean("focus",focus);super.onSaveInstanceState(b);}
    @Override public void onConfigurationChanged(android.content.res.Configuration c){super.onConfigurationChanged(c);build();changed();}
    private void select(boolean mode){
        if(CaptionState.S.running)return;playback=mode;AppPrefs.legacy(this).edit().putBoolean("playback",mode).apply();
        mic.setSelected(!mode);phone.setSelected(mode);if(Build.VERSION.SDK_INT>=30){mic.setStateDescription(mode?tr("未选择","Not selected"):tr("已选择","Selected"));phone.setStateDescription(mode?tr("已选择","Selected"):tr("未选择","Not selected"));}
        mic.setBackground(Ui.bg(this,mode?Ui.CARD:0xFF204239,12));mic.setTextColor(mode?Ui.MUTED:Ui.MINT);phone.setBackground(Ui.bg(this,mode?0xFF204239:Ui.CARD,12));phone.setTextColor(mode?Ui.MINT:Ui.MUTED);
    }
    private void begin(){
        if(ModelStore.active)return;
        if(playback&&!AppPrefs.prefs(this).getBoolean("captureExplained",false)){
            new android.app.AlertDialog.Builder(this).setTitle(tr("手机声音","Device audio")).setMessage(R.string.playback_permission).setNegativeButton(android.R.string.cancel,null).setPositiveButton(tr("继续","Continue"),(d,w)->{AppPrefs.prefs(this).edit().putBoolean("captureExplained",true).apply();begin();}).show();return;
        }
        if(!CaptionState.S.ready){LocalEngine.get(this).prepare();return;}
        if(checkSelfPermission(Manifest.permission.RECORD_AUDIO)!=PackageManager.PERMISSION_GRANTED){requestPermissions(Build.VERSION.SDK_INT>=33?new String[]{Manifest.permission.RECORD_AUDIO,Manifest.permission.POST_NOTIFICATIONS}:new String[]{Manifest.permission.RECORD_AUDIO},10);return;}
        if(playback){MediaProjectionManager manager=getSystemService(MediaProjectionManager.class);Intent request=Build.VERSION.SDK_INT>=34?manager.createScreenCaptureIntent(android.media.projection.MediaProjectionConfig.createConfigForDefaultDisplay()):manager.createScreenCaptureIntent();startActivityForResult(request,20);}else launch(null);
    }
    private void launch(Intent data){Intent i=new Intent(this,CaptionService.class).setAction(CaptionService.START).putExtra("playback",playback).putExtra("interval",AppPrefs.fast(this)?450:1000);if(data!=null)i.putExtra("projectionData",data);try{startForegroundService(i);}catch(Exception e){CaptionState.S.status(tr("无法开始：","Unable to start: ")+e.getMessage());}}
    @Override public void onRequestPermissionsResult(int req,String[] p,int[] grants){super.onRequestPermissionsResult(req,p,grants);if(req==10&&grants.length>0){if(grants[0]==PackageManager.PERMISSION_GRANTED)begin();else CaptionState.S.status("需要麦克风权限才能识别语音");}}
    @Override protected void onActivityResult(int req,int result,Intent data){super.onActivityResult(req,result,data);if(req==20){if(result==RESULT_OK&&data!=null)launch(data);else CaptionState.S.status("已取消手机声音授权");}}
    @Override protected void onResume(){super.onResume();visible=true;CaptionState.S.overlay=AppPrefs.overlay(this)&&android.provider.Settings.canDrawOverlays(this);CaptionState.S.changed();getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);}
    @Override protected void onPause(){visible=false;CaptionState.S.changed();super.onPause();}
    @Override protected void onDestroy(){CaptionState.S.unobserve(this);super.onDestroy();}
    @Override public void changed(){
        if(status==null)return;CaptionState s=CaptionState.S;LocalEngine engine=LocalEngine.get(this);Ui.setText(direction,SpeechLanguage.direction(this,engine.sourceLanguage(),engine.targetLanguage()));Ui.setText(status,s.ready&&!s.running&&!s.preparing&&s.status.startsWith("离线模型就绪")?tr("已就绪 · 可离线使用","Ready · works offline"):Ui.status(this,s.status));Ui.setText(models,Ui.modelSummary(this,engine));meter.setProgress(s.level);meter.setVisibility(s.running?View.VISIBLE:View.GONE);
        start.setEnabled((s.ready&&!ModelStore.active)||s.running);start.setAlpha(start.isEnabled()?1:0.5f);Ui.setText(start,s.running?tr("■ 停止字幕","■ Stop captions"):s.ready?tr("开始实时字幕","Start live captions"):tr("请先准备模型","Prepare a model first"));int color=s.running?0xFF663B3B:Ui.MINT;if(startColor!=color){startColor=color;start.setBackground(Ui.bg(this,color,14));}start.setTextColor(s.running?Ui.WHITE:Ui.BG);mic.setEnabled(!s.running);phone.setEnabled(!s.running);retry.setVisibility(!s.ready&&!s.preparing?View.VISIBLE:View.GONE);retry.setEnabled(!engine.configurationBusy());Ui.setText(retry,engine.translationAvailable()?tr("打开模型管理 / 重试","Models / Retry"):tr("更改说话 / 字幕语言","Change speech / caption languages"));
        boolean failed=engine.hasModelFailure()&&!s.preparing;
        recover.setVisibility(failed?View.VISIBLE:View.GONE);recover.setEnabled(!engine.configurationBusy()&&RuntimeHealth.thermal(this)<PowerManager.THERMAL_STATUS_CRITICAL);
        if(failed){status.setMaxLines(4);Ui.setText(status,engine.recoveryMessage());ModelRecovery.Plan plan=engine.recoveryPlan();Ui.setText(recover,plan==null?getString(R.string.recovery_models):getString(R.string.recovery_use,ModelRecovery.label(plan)));Ui.setText(retry,getString(R.string.recovery_retry));}else status.setMaxLines(2);
        int n=0;StringBuilder digest=new StringBuilder().append(AppPrefs.font(this)).append(AppPrefs.english(this));for(CaptionState.Line l:s.lines)if(Ui.content(l.en)||Ui.content(l.zh)){n++;digest.append(l.id).append(l.en).append(l.zh).append(l.problem).append(l.done);}Ui.setText(count,n==0?"":n+tr(" 句"," lines"));
        if(digest.toString().equals(lastDigest))return;lastDigest=digest.toString();boolean follow=rows.getHeight()-scroll.getHeight()-scroll.getScrollY()<dp(160);
        java.util.Iterator<Map.Entry<Long,Row>> it=rowViews.entrySet().iterator();while(it.hasNext()){Map.Entry<Long,Row> e=it.next();boolean found=false;for(CaptionState.Line l:s.lines)if(l.id==e.getKey()&&(Ui.content(l.en)||Ui.content(l.zh))){found=true;break;}if(!found){rows.removeView(e.getValue().view);it.remove();}}
        rows.setGravity(n==0?Gravity.CENTER:Gravity.TOP);if(n==0){if(empty.getParent()==null)rows.addView(empty,new LinearLayout.LayoutParams(-1,-2));return;}if(empty.getParent()!=null)rows.removeView(empty);
        for(CaptionState.Line l:s.lines){if(!Ui.content(l.en)&&!Ui.content(l.zh))continue;Row r=rowViews.get(l.id);if(r==null){r=new Row();r.view=Ui.column(this);r.view.setPadding(0,0,0,dp(14));r.tag=Ui.text(this,"",10,Ui.MINT);r.zh=Ui.text(this,"",30,Ui.WHITE);r.zh.setTypeface(null,Typeface.BOLD);r.zh.setLineSpacing(dp(3),1);r.en=Ui.text(this,"",14,Ui.MUTED);r.en.setPadding(0,dp(5),0,0);r.view.addView(r.tag);r.view.addView(r.zh);r.view.addView(r.en);rows.addView(r.view);rowViews.put(l.id,r);}Ui.setText(r.tag,tr("● 实时草稿","● Live draft"));r.tag.setVisibility(l.done?View.GONE:View.VISIBLE);Ui.setText(r.zh,l.zh.isEmpty()?(l.problem.isEmpty()?tr("正在翻译…","Translating…"):l.problem):l.zh);r.zh.setTextSize(AppPrefs.font(this));Ui.setText(r.en,l.en);r.en.setVisibility(AppPrefs.english(this)?View.VISIBLE:View.GONE);}
        if(follow)scroll.post(()->scroll.fullScroll(View.FOCUS_DOWN));
    }
}
