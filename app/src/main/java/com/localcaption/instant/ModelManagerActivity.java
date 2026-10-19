package com.localcaption.instant;

import android.app.*;
import android.content.*;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.InsetDrawable;
import android.graphics.drawable.RippleDrawable;
import android.os.*;
import android.view.*;
import android.widget.*;
import java.io.File;
import java.util.*;

/** Separate catalogs: text -> translated text, and audio -> recognized text. */
public class ModelManagerActivity extends BaseActivity implements CaptionState.Observer {
    private static final int SURFACE=0xFF1B2A37,AMBER=0xFFFFD18A;
    private LinearLayout list,transfer;
    private TextView direction,summaryTitle,summary,explanation,status,progressTitle,progressDetail;
    private Button textTab,speechTab,source,cancel,resume,forget,allFilter,installedFilter;
    private String importModel;
    private ProgressBar bar;
    private ScrollView scroll;
    private int tab;
    private boolean installedOnly,otherLanguages;
    private String digest="";
    private static final class ModelRow { Button use,more; }
    @Override public void onCreate(Bundle b){
        super.onCreate(b);tab=b==null?getIntent().getIntExtra("tab",0):b.getInt("tab");
        importModel=b==null?null:b.getString("importModel");
        installedOnly=b!=null&&b.getBoolean("installedOnly");otherLanguages=b!=null&&b.getBoolean("otherLanguages");
        build();CaptionState.S.observe(this);changed();
        String raw=b==null?getIntent().getStringExtra("importRawModel"):null;
        if(QwenModel.known(raw)&&QwenModel.fromId(raw).isSupported(this)&&!LocalEngine.get(this).configurationBusy())importFile(raw);
    }
    private void add(LinearLayout parent,View view,int margin){LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(-1,-2);p.topMargin=dp(margin);parent.addView(view,p);}
    private TextView label(String value,int size,int color){TextView v=Ui.text(this,value,size,color);v.setIncludeFontPadding(false);v.setLineSpacing(0,1);return v;}
    private android.graphics.drawable.Drawable controlBackground(int color,int stroke,int inset){
        GradientDrawable shape=Ui.bg(this,color,9);if(stroke!=Color.TRANSPARENT)shape.setStroke(dp(1),stroke);
        return new RippleDrawable(ColorStateList.valueOf(0x2282F4C5),new InsetDrawable(shape,0,dp(inset),0,dp(inset)),null);
    }
    private Button button(String text,boolean primary){
        Button b=Ui.button(this,text,primary?Ui.MINT:SURFACE,primary?Ui.BG:Ui.WHITE);
        b.setMinHeight(dp(48));b.setMinWidth(dp(48));b.setIncludeFontPadding(false);
        b.setBackground(controlBackground(primary?Ui.MINT:SURFACE,Color.TRANSPARENT,4));b.setPadding(dp(12),dp(6),dp(12),dp(6));return b;
    }
    private void enabled(View v,boolean on){v.setEnabled(on);v.setAlpha(on?1f:.45f);}
    private LinearLayout card(LinearLayout parent){LinearLayout box=Ui.column(this);box.setPadding(dp(12),dp(10),dp(12),dp(10));box.setBackground(Ui.bg(this,Ui.CARD,12));add(parent,box,6);return box;}
    private void build(){
        LinearLayout root=Ui.root(this);Ui.title(this,root,tr("模型中心","Model center"));
        direction=label("",12,Ui.MUTED);direction.setPadding(dp(4),0,0,dp(6));root.addView(direction);
        TextView processor=label(getString(R.string.processor_model_list,DeviceProfile.detect(this).processorName()),11,Ui.MUTED);processor.setPadding(dp(4),0,0,dp(4));root.addView(processor);
        LinearLayout tabs=Ui.row(this);tabs.setBackground(Ui.bg(this,Ui.CARD,12));tabs.setPadding(dp(4),0,dp(4),0);textTab=button(tr("文本翻译","Text translation"),true);speechTab=button(tr("语音识别","Speech recognition"),false);
        boolean largeType=getResources().getConfiguration().fontScale>=1.3f;
        if(largeType)tabs.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams tp=new LinearLayout.LayoutParams(largeType?-1:0,-2,largeType?0:1);tp.setMarginEnd(largeType?0:dp(4));tabs.addView(textTab,tp);tabs.addView(speechTab,new LinearLayout.LayoutParams(largeType?-1:0,-2,largeType?0:1));root.addView(tabs);
        textTab.setOnClickListener(v->switchTab(0));speechTab.setOnClickListener(v->switchTab(1));
        scroll=new ScrollView(this);scroll.setClipToPadding(false);LinearLayout body=Ui.column(this);body.setPadding(0,dp(6),0,dp(20));scroll.addView(body);root.addView(scroll,new LinearLayout.LayoutParams(-1,0,1));
        LinearLayout active=Ui.column(this);active.setPadding(dp(12),dp(10),dp(12),dp(10));GradientDrawable activeBg=Ui.bg(this,0xFF122723,14);activeBg.setStroke(dp(1),0xFF31594C);active.setBackground(activeBg);LinearLayout.LayoutParams activeParams=new LinearLayout.LayoutParams(-1,-2);activeParams.topMargin=dp(8);root.addView(active,root.getChildCount()-1,activeParams);summaryTitle=label("",11,Ui.MINT);active.addView(summaryTitle);summary=label("",14,Ui.WHITE);summary.setTypeface(null,Typeface.BOLD);add(active,summary,4);explanation=label("",11,Ui.MUTED);add(active,explanation,2);
        LinearLayout filters=Ui.row(this);allFilter=button(tr("可用模型","Available"),true);installedFilter=button(tr("已下载","Downloaded"),false);
        allFilter.setTextSize(12);installedFilter.setTextSize(12);LinearLayout.LayoutParams fp=new LinearLayout.LayoutParams(-2,-2);fp.setMarginEnd(dp(6));filters.addView(allFilter,fp);filters.addView(installedFilter,new LinearLayout.LayoutParams(-2,-2));add(body,filters,2);
        allFilter.setOnClickListener(v->{installedOnly=false;digest="";changed();scroll.scrollTo(0,0);});installedFilter.setOnClickListener(v->{installedOnly=true;digest="";changed();scroll.scrollTo(0,0);});
        list=Ui.column(this);body.addView(list);
        source=button("",false);source.setGravity(Gravity.START|Gravity.CENTER_VERTICAL);source.setOnClickListener(v->chooseSource());add(body,source,10);
        status=label("",12,AMBER);add(body,status,12);
        transfer=Ui.column(this);transfer.setPadding(dp(12),dp(10),dp(12),dp(10));transfer.setBackground(Ui.bg(this,Ui.CARD,14));
        progressTitle=label("",13,Ui.WHITE);transfer.addView(progressTitle);bar=new ProgressBar(this,null,android.R.attr.progressBarStyleHorizontal);bar.setMax(100);bar.setProgressTintList(ColorStateList.valueOf(Ui.MINT));add(transfer,bar,8);
        progressDetail=label("",11,Ui.MUTED);add(transfer,progressDetail,4);cancel=button(getString(R.string.pause_transfer),false);cancel.setOnClickListener(v->startService(new Intent(this,ModelDownloadService.class).setAction("cancel")));add(transfer,cancel,8);
        resume=button(getString(R.string.resume_transfer),true);resume.setOnClickListener(v->DownloadActions.resume(this));add(transfer,resume,4);
        forget=button(getString(R.string.discard_queue),false);forget.setOnClickListener(v->{DownloadJournal.clear(this);ModelStore.phase="";ModelStore.error="";changed();});add(transfer,forget,2);root.addView(transfer);
    }
    private void switchTab(int next){tab=next;installedOnly=false;digest="";changed();scroll.scrollTo(0,0);}
    private void selected(Button b,boolean on){boolean filter=b==allFilter||b==installedFilter;b.setBackground(controlBackground(on?0xFF203B34:Color.TRANSPARENT,Color.TRANSPARENT,filter?8:4));b.setPadding(dp(12),dp(6),dp(12),dp(6));b.setTextColor(on?Ui.MINT:Ui.MUTED);b.setSelected(on);}
    private void heading(String title,String detail){TextView h=label(title,13,Ui.WHITE);h.setTypeface(null,Typeface.BOLD);h.setPadding(dp(2),dp(8),0,dp(3));list.addView(h);if(!detail.isEmpty()){TextView d=label(detail,11,Ui.MUTED);d.setPadding(dp(2),0,dp(2),dp(2));list.addView(d);}}
    private ModelRow row(String name,String tags,String detail,String action,boolean primary,boolean active,Runnable press,java.util.function.Consumer<View> more){
        ModelRow controls=new ModelRow();LinearLayout box=card(list),line=Ui.row(this);
        if(active){GradientDrawable background=Ui.bg(this,0xFF122723,12);background.setStroke(dp(1),0xFF31594C);box.setBackground(background);}
        LinearLayout words=Ui.column(this);words.setPaddingRelative(0,0,dp(8),0);TextView title=label(name,15,Ui.WHITE);title.setTypeface(null,Typeface.BOLD);words.addView(title);add(words,label(tags,11,active?0xFFA7CDBC:Ui.MUTED),4);line.addView(words,new LinearLayout.LayoutParams(0,-2,1));
        Button use=button(action,false);controls.use=use;use.setTextSize(12);use.setMinWidth(dp(56));use.setMaxWidth(dp(100));use.setMaxLines(3);use.setPadding(dp(8),dp(4),dp(8),dp(4));use.setContentDescription(name+" · "+action);
        use.setTextColor(active||primary?Ui.MINT:Ui.WHITE);use.setBackground(controlBackground(active?Color.TRANSPARENT:0xFF1B2D32,active?Color.TRANSPARENT:0xFF30454B,8));use.setPadding(dp(8),dp(4),dp(8),dp(4));enabled(use,press!=null&&!LocalEngine.get(this).configurationBusy());if(active)use.setAlpha(1f);
        use.setOnClickListener(v->{if(press!=null&&!LocalEngine.get(this).configurationBusy())press.run();});line.addView(use,new LinearLayout.LayoutParams(-2,-2));
        if(more!=null){Button menu=Ui.button(this,"⋮",Color.TRANSPARENT,Ui.MUTED);controls.more=menu;menu.setTextSize(android.util.TypedValue.COMPLEX_UNIT_DIP,22);menu.setPadding(0,0,0,0);menu.setIncludeFontPadding(false);menu.setContentDescription(name+tr(" · 更多操作"," · more options"));menu.setOnClickListener(v->more.accept(v));line.addView(menu,new LinearLayout.LayoutParams(dp(48),dp(48)));}box.addView(line);
        if(!detail.isEmpty()){TextView description=label(detail,11,Ui.MUTED);add(box,description,4);}
        return controls;
    }
    private boolean retained(NpuModel m){return ModelStore.root(this,m).exists()||ModelStore.compiledRoot(this,m).exists()||new File(ModelStore.downloadRoot(this),m.id).exists();}
    private boolean retainedText(String target){return TranslationModel.root(this,target).exists()||new File(ModelStore.downloadRoot(this),TranslationModel.id(target)).exists();}
    private boolean retainedQwen(QwenModel m){return m.modelRoot(this).exists()||new File(ModelStore.downloadRoot(this),m.id).exists();}
    private boolean retainedQwen(){for(QwenModel m:QwenModel.values())if(retainedQwen(m))return true;return false;}
    private String speechReason(NpuModel m,LocalEngine engine){
        if(!DeviceProfile.detect(this).supports(m))return tr("此 NPU 文件不适配本机芯片","This NPU file does not match this chipset");
        if(!m.supportsLanguage(engine.sourceLanguage()))return tr("不支持当前说话语言","Does not support the current spoken language");
        if(engine.recognitionUsesEnglishBridge()&&!m.supportsEnglishTranslation())return tr("当前翻译路线需要 Whisper 先将语音转成英语","This translation route needs Whisper speech-to-English first");
        return "";
    }
    private void speechRows(LocalEngine engine){
        DeviceProfile device=DeviceProfile.detect(this);
        if(!installedOnly){
            Button mode=button(getString(R.string.recognition_mode)+"  ▾",false);mode.setGravity(Gravity.START|Gravity.CENTER_VERTICAL);enabled(mode,!engine.configurationBusy());
            mode.setOnClickListener(v->{PopupMenu menu=new PopupMenu(this,v);
                menu.getMenu().add((engine.automaticSpeech()?"✓  ":"")+tr("自动选择","Automatic")).setOnMenuItemClickListener(item->{engine.selectSpeechChoice("auto");return true;});
                menu.getMenu().add(("system".equals(engine.speechChoice())?"✓  ":"")+tr("系统离线识别","System offline recognition")).setEnabled(!engine.recognitionUsesEnglishBridge()).setOnMenuItemClickListener(item->{engine.selectSpeechChoice("system");return true;});
                menu.getMenu().add(tr("系统语音包与检查","System speech pack and checks")).setEnabled(!engine.recognitionUsesEnglishBridge()).setOnMenuItemClickListener(item->{systemMenu(v);return true;});menu.show();});add(list,mode,10);
        }
        ArrayList<NpuModel> models=new ArrayList<>();for(NpuModel m:NpuModel.values())if((device.supports(m)||retained(m))&&(!installedOnly||retained(m)))models.add(m);
        models.sort(Comparator.comparingInt((NpuModel m)->!device.supports(m)?3:!engine.isModelCompatible(m)?2:m.isNpu?0:1).thenComparingInt(m->device.modelPriority(m.id)));
        int group=-1;boolean npuRecommended=false;
        for(NpuModel m:models){
            int rank=!device.supports(m)?3:!engine.isModelCompatible(m)?2:m.isNpu?0:1;
            if(group!=rank){group=rank;heading(rank==0?tr("NPU 模型 · 优先推荐","NPU models · recommended first"):rank==1?tr("CPU 模型","CPU models"):rank==2?tr("其他语言 / 翻译路线","Other languages / routes"):tr("不适配本机的已存文件","Stored files incompatible with this device"),"");}
            boolean installed=ModelStore.installed(this,m),picked=m.id.equals(engine.speechChoice()),using=CaptionState.S.ready&&!engine.usesSystemSpeech()&&engine.selectedModel()==m;
            boolean recommendation=rank==0&&!npuRecommended;if(rank==0)npuRecommended=true;
            String tags=(m.isNpu?"NPU":"CPU")+" · "+Ui.size(ModelStore.bytes(this,m))+" · "+(installed?tr("已安装","Installed"):tr("未安装","Not installed"));
            if(recommendation)tags+=tr(" · 推荐"," · Recommended");if(m.isNpu&&device.documentedNpu())tags+=" · "+getString(R.string.npu_documented_short);if(picked&&!using)tags+=tr(" · 已选择"," · Selected");
            String detail=speechReason(m,engine);if(detail.isEmpty())detail=m==NpuModel.SENSEVOICE||m==NpuModel.SENSEVOICE_CPU?tr("中 / 英 / 日 / 韩 / 粤语识别","Chinese, English, Japanese, Korean and Cantonese"):m==NpuModel.NEMOTRON?tr("英语流式识别","Streaming English recognition"):m.isWhisper()?tr("多国语音识别 · 可用于英语中转","Multilingual speech recognition · English bridge available"):tr("中英文流式识别","Streaming Chinese and English recognition");
            String action=!device.supports(m)?tr("不支持","Unsupported"):!installed?tr("下载","Download"):!engine.isModelCompatible(m)?tr("不适用","Unavailable"):picked?(using?tr("使用中","In use"):tr("重试","Retry")):tr("使用","Use");
            Runnable press=!device.supports(m)?null:!installed?()->downloadSpeech(m):!engine.isModelCompatible(m)?null:picked?(using?null:engine::prepare):()->engine.selectSpeechChoice(m.id);
            row(m.label(this),tags,detail,action,!installed||(!picked&&rank<2),using,press,v->speechMenu(v,m));
        }
        if(models.isEmpty())heading(tr("暂无已下载的识别模型","No downloaded speech models"),tr("切换到“可用模型”查看下载列表。","Open Available to browse downloads."));
    }
    private void textRows(LocalEngine engine){
        DeviceProfile device=DeviceProfile.detect(this);
        int group=-1;
        String recommended=ModelCompatibility.recommended(device,engine.sourceLanguage(),engine.targetLanguage());
        ArrayList<QwenModel> textModels=new ArrayList<>(Arrays.asList(QwenModel.values()));textModels.sort(Comparator.comparingInt((QwenModel m)->m.isCpu()?1:0).thenComparingInt(m->m.id.equals(recommended)?-1:device.modelPriority(m.id)));
        for(QwenModel m:textModels)if((m.isSupported(device)||retainedQwen(m))&&(!installedOnly||retainedQwen(m))){
            int next=m.isCpu()?1:0;if(group!=next){heading(next==0?tr("NPU 文本翻译 · 优先推荐","NPU text translation · recommended first"):getString(R.string.cpu_direct_models),"");group=next;}
            qwenRow(engine,m);
        }
        if(!device.supportsText("opus")&&!installedOnly)return;
        heading(tr("CPU 文本翻译 · OPUS-MT","CPU text translation · OPUS-MT"),tr("按字幕语言下载 · 非英语来源需 Whisper 中转","Per caption language · non-English speech needs Whisper"));
        String current=engine.targetLanguage();
        if(TranslationModel.supportsTarget(current)&&(!installedOnly||retainedText(current)))textRow(current,engine);
        ArrayList<String> other=new ArrayList<>();for(String target:TranslationModel.targets())if(!target.equals(current)&&(!installedOnly||retainedText(target)))other.add(target);
        if(!other.isEmpty()){
            Button toggle=button((otherLanguages?"▾  ":"▸  ")+tr("其他语言包","Other language packs")+" · "+other.size(),false);toggle.setGravity(Gravity.START|Gravity.CENTER_VERTICAL);toggle.setOnClickListener(v->{otherLanguages=!otherLanguages;digest="";changed();});add(list,toggle,6);
            if(otherLanguages)for(String target:other)textRow(target,engine);
        }
        if(installedOnly&&other.isEmpty()&&!retainedText(current)&&!retainedQwen())heading(tr("暂无已下载的翻译模型","No downloaded translation models"),tr("切换到“可用模型”查看下载列表。","Open Available to browse downloads."));
    }
    private void qwenRow(LocalEngine engine,QwenModel m){
        boolean supported=m.isSupported(this),installed=m.isInstalled(this),picked=m.id.equals(engine.translationChoice());
        boolean using=m.id.equals(engine.translationBackend())&&engine.translationReady();
        boolean passthrough=SpeechLanguage.canonical(engine.sourceLanguage()).equals(engine.targetLanguage());
        boolean language=m.supportsLanguages(engine.sourceLanguage(),engine.targetLanguage());
        String tags=m.processor()+" · "+Ui.size(m.installedBytes(this))+" · "+(installed?tr("已安装","Installed"):tr("未安装","Not installed"));
        if(supported&&m.id.equals(ModelCompatibility.recommended(DeviceProfile.detect(this),engine.sourceLanguage(),engine.targetLanguage())))tags+=tr(" · 推荐"," · Recommended");if(!m.isCpu()&&DeviceProfile.detect(this).documentedNpu())tags+=" · "+getString(R.string.npu_documented_short);if(m==QwenModel.SMALL||m.isLiteRt())tags+=" · "+getString(R.string.experimental);if(picked&&!using)tags+=tr(" · 已选择"," · Selected");
        String action=!supported?tr("不支持","Unsupported"):!installed?(m.requiresAuthorization()?getString(R.string.authorized_download):tr("下载","Download")):!language?tr("不适用","Unavailable"):using?tr("使用中","In use"):picked&&passthrough?tr("已选择","Selected"):tr("使用","Use");
        Runnable press=!supported||using||(installed&&(!language||picked&&passthrough))?null:!installed?()->{try{download(m.label+" · "+m.processor(),m.id,m.manifest(this).getJSONObject("download").getLong("size"));}catch(Exception e){info(tr("无法下载","Cannot download"),e.getMessage());}}:()->engine.selectTranslationChoice(m.id);
        row(m.label,tags,!supported?m.compatibility(this):!language?getString(R.string.model_language_unsupported):m.description(this),action,!using,using,press,v->{
            PopupMenu menu=new PopupMenu(this,v);menu.getMenu().add(tr("模型详情","Model details")).setOnMenuItemClickListener(item->{info(m.label,getString(R.string.text_model_runtime_details,m.isCpu()?"CPU / llama.cpp":m.isLiteRt()?"NPU / LiteRT-LM":"Hexagon NPU / QAIRT",m.isCpu()?"Q4_K_M":m.isLiteRt()?"4-bit":"W4A16",Ui.size(m.installedBytes(this)))+"\n"+m.compatibility(this)+"\n"+RuntimeHealth.label(this,m.id)+"\n"+getString(R.string.quality_scope)+"\n\n"+ModelNotice.details(this,m.id));return true;});
            if(m.isLiteRt()||m.isCpu())menu.getMenu().add(R.string.import_model_file).setEnabled(supported&&!engine.configurationBusy()).setOnMenuItemClickListener(item->{importFile(m.id);return true;});
            menu.getMenu().add(R.string.import_model).setEnabled(supported&&!engine.configurationBusy()).setOnMenuItemClickListener(item->{importFolder(m.id);return true;});
            if(retainedQwen(m))menu.getMenu().add(tr("删除模型及缓存","Remove model and cache")).setEnabled(!engine.configurationBusy()).setOnMenuItemClickListener(item->{confirmRemove(m.label,m.id);return true;});menu.show();
        });
    }
    private void textRow(String target,LocalEngine engine){
        boolean installed=TranslationModel.installed(this,target),picked=target.equals(engine.targetLanguage())&&!engine.usesQwenTranslation();
        boolean using=picked&&"opus".equals(engine.translationBackend())&&engine.translationReady();
        String tags="CPU · "+Ui.size(TranslationModel.bytes(this,target))+" · "+(installed?tr("已安装","Installed"):tr("未安装","Not installed"));if(picked&&!using)tags+=tr(" · 当前字幕语言"," · Current caption language");
        boolean passthrough=target.equals(engine.targetLanguage())&&SpeechLanguage.canonical(engine.sourceLanguage()).equals(target);
        boolean supported=DeviceProfile.detect(this).supportsText("opus");
        String action=!supported?tr("不支持","Unsupported"):!installed?tr("下载","Download"):using?tr("使用中","In use"):passthrough?tr("仅转写","Transcribe"):tr("使用","Use");
        ModelRow controls=row("OPUS-MT",tags,SpeechLanguage.direction(this,"en",target),action,!using,using,!supported||using||(installed&&passthrough)?null:!installed?()->downloadText(target):()->useText(target),v->textMenu(v,target));
        controls.use.setContentDescription(TranslationModel.label(this,target)+" · "+action);controls.more.setContentDescription(TranslationModel.label(this,target)+tr(" · 更多操作"," · more options"));
    }
    private void useText(String target){
        LocalEngine engine=LocalEngine.get(this);if(engine.configurationBusy())return;
        if(target.equals(engine.targetLanguage())){engine.selectTranslationChoice("opus");return;}
        new AlertDialog.Builder(this).setTitle(tr("使用翻译语言包","Use translation language pack"))
            .setMessage(tr("字幕语言将改为","Caption language will change to ")+SpeechLanguage.label(this,target)+tr("。说话语言保持不变。",". The spoken language stays the same."))
            .setNegativeButton(tr("取消","Cancel"),null).setPositiveButton(tr("使用","Use"),(d,i)->engine.selectTranslationChoice("opus",target)).show();
    }
    private void textMenu(View anchor,String target){PopupMenu menu=new PopupMenu(this,anchor);menu.getMenu().add(tr("模型详情","Model details")).setOnMenuItemClickListener(item->{info(TranslationModel.label(this,target),tr("用途：文本翻译\n运行方式：CPU\n安装体积：","Task: text translation\nProcessor: CPU\nInstalled size: ")+Ui.size(TranslationModel.bytes(this,target))+"\n\n"+ModelNotice.details(this,TranslationModel.id(target)));return true;});
        menu.getMenu().add(R.string.import_model).setEnabled(!LocalEngine.get(this).configurationBusy()).setOnMenuItemClickListener(item->{importFolder(TranslationModel.id(target));return true;});
        if(retainedText(target))menu.getMenu().add(tr("删除模型及缓存","Remove model and cache")).setEnabled(!LocalEngine.get(this).configurationBusy()).setOnMenuItemClickListener(item->{confirmRemove(TranslationModel.label(this,target),TranslationModel.id(target));return true;});menu.show();}
    private void speechMenu(View anchor,NpuModel model){PopupMenu menu=new PopupMenu(this,anchor);menu.getMenu().add(tr("模型详情","Model details")).setOnMenuItemClickListener(item->{info(model.label(this),tr("用途：语音识别\n运行方式：","Task: speech recognition\nProcessor: ")+(model.isNpu?"NPU (QNN HTP)":"CPU")+"\n"+DeviceProfile.detect(this).reason(this,model)+"\n\n"+RuntimeHealth.label(this,model.id)+"\n"+getString(R.string.quality_scope)+"\n\n"+ModelNotice.details(this,model.id));return true;});
        menu.getMenu().add(R.string.import_model).setEnabled(DeviceProfile.detect(this).supports(model)&&!LocalEngine.get(this).configurationBusy()).setOnMenuItemClickListener(item->{importFolder(model.id);return true;});
        if(retained(model))menu.getMenu().add(tr("删除模型及缓存","Remove model and cache")).setEnabled(!LocalEngine.get(this).configurationBusy()).setOnMenuItemClickListener(item->{confirmRemove(model.label(this),model.id);return true;});menu.show();}
    private void systemMenu(View anchor){LocalEngine engine=LocalEngine.get(this);PopupMenu menu=new PopupMenu(this,anchor);menu.getMenu().add(tr("重新检查系统识别","Recheck system recognition")).setEnabled(!engine.configurationBusy()).setOnMenuItemClickListener(item->{engine.selectSpeechChoice("system");if(!engine.configurationBusy())engine.recheckSpeech();return true;});
        if(engine.canDownloadSystemSpeech())menu.getMenu().add(tr("准备系统离线语音包","Prepare system speech pack")).setEnabled(!engine.configurationBusy()).setOnMenuItemClickListener(item->{new AlertDialog.Builder(this).setTitle(tr("准备系统离线语音包","Prepare system speech pack")).setMessage(tr("由系统显示下载大小并安装当前语言离线包。","The system displays the size and installs the offline pack for this language.")).setNegativeButton(tr("取消","Cancel"),null).setPositiveButton(tr("继续","Continue"),(d,i)->engine.downloadSystemSpeech()).show();return true;});menu.show();}
    private void info(String title,String detail){AlertDialog dialog=new AlertDialog.Builder(this).setTitle(title).setMessage(detail).setPositiveButton(tr("知道了","OK"),null).show();TextView text=dialog.findViewById(android.R.id.message);if(text!=null){android.text.util.Linkify.addLinks(text,android.text.util.Linkify.WEB_URLS);text.setMovementMethod(android.text.method.LinkMovementMethod.getInstance());}}
    private int source(){return AppPrefs.prefs(this).getInt("downloadSource",0);}
    private void chooseSource(){new AlertDialog.Builder(this).setTitle(tr("下载通道","Download channel")).setSingleChoiceItems(new String[]{tr("自动切换 · 推荐","Automatic fallback · recommended"),tr("仅官方源","Official only"),tr("备用镜像","Backup mirrors")},source(),(d,i)->{AppPrefs.prefs(this).edit().putInt("downloadSource",i).apply();DownloadJournal.changeSource(this,i);d.dismiss();changed();}).setNeutralButton(tr("通道说明","About channels"),(d,i)->info(tr("下载通道","Download channels"),tr("官方：GitHub / Hugging Face / Qualcomm AWS\n备用：gh-proxy.org、ghfast.top / hf-mirror.com；Qwen 使用 AWS 备用入口\n支持断点续传，安装前校验 SHA-256。系统语音包由系统管理。","Official: GitHub / Hugging Face / Qualcomm AWS\nBackup: gh-proxy.org, ghfast.top / hf-mirror.com; alternate AWS endpoint for Qwen\nResumable downloads with SHA-256 verification. System speech packs are managed by the system."))).setNegativeButton(tr("取消","Cancel"),null).show();}
    private void downloadText(String target){download(TranslationModel.label(this,target),TranslationModel.id(target),TranslationModel.bytes(this,target));}
    private void downloadSpeech(NpuModel model){try{download(model.label(this),model.id,ModelStore.spec(this,model).getJSONObject("download").getLong("size"));}catch(Exception e){info(tr("无法下载","Cannot download"),e.getMessage());}}
    private void download(String label,String id,long bytes){DownloadActions.confirm(this,new String[]{id});}
    private void confirmRemove(String label,String id){new AlertDialog.Builder(this).setTitle(tr("删除模型？","Remove model?")).setMessage(label+tr("\n删除此模型及下载缓存。再次使用时需要重新下载。","\nRemove this model and its download cache. Download it again before using it.")).setNegativeButton(tr("保留","Keep"),null).setPositiveButton(tr("删除","Remove"),(d,i)->start(id,true)).show();}
    private void start(String id,boolean deleting){if(LocalEngine.get(this).configurationBusy())return;startForegroundService(new Intent(this,ModelDownloadService.class).putExtra("model",id).putExtra("delete",deleting).putExtra("source",source()));}
    @Override public void changed(){
        if(list==null)return;LocalEngine engine=LocalEngine.get(this);boolean busy=engine.configurationBusy();
        selected(textTab,tab==0);selected(speechTab,tab==1);selected(allFilter,!installedOnly);selected(installedFilter,installedOnly);
        Ui.setText(direction,SpeechLanguage.direction(this,engine.sourceLanguage(),engine.targetLanguage()));
        Ui.setText(summaryTitle,tab==0?tr("当前文本翻译","Current text translation"):tr("当前语音识别","Current speech recognition"));
        Ui.setText(summary,tab==0?Ui.translationSelection(this,engine):engine.speechChoiceLabel()+(engine.automaticSpeech()?"":tr(" · 手动"," · manual")));
        explanation.setVisibility(tab==1?View.VISIBLE:View.GONE);Ui.setText(explanation,engine.engineName());
        StringBuilder state=new StringBuilder().append(tab).append(installedOnly).append(otherLanguages).append(busy).append(engine.sourceLanguage()).append(engine.targetLanguage()).append(engine.speechChoice()).append(engine.recognitionBackend()).append(engine.translationBackend()).append(engine.translationChoice()).append(CaptionState.S.ready).append(engine.canDownloadSystemSpeech());
        for(QwenModel m:QwenModel.values())state.append(m.id).append(retainedQwen(m)).append(m.isInstalled(this));
        for(NpuModel m:NpuModel.values())state.append(m.id).append(retained(m)).append(ModelStore.installed(this,m));for(String target:TranslationModel.targets())state.append(target).append(retainedText(target)).append(TranslationModel.installed(this,target));
        if(!digest.equals(state.toString())){digest=state.toString();list.removeAllViews();if(tab==0)textRows(engine);else speechRows(engine);}
        Ui.setText(source,tr("下载通道：","Download channel: ")+ModelStore.channelName(this,source())+"  ›");enabled(source,!busy);
        boolean attention=CaptionState.S.preparing||!CaptionState.S.ready;status.setVisibility(attention?View.VISIBLE:View.GONE);Ui.setText(status,Ui.status(this,CaptionState.S.status));
        boolean waiting=DownloadJournal.hasJobs(this)&&!ModelStore.active;resume.setVisibility(waiting?View.VISIBLE:View.GONE);resume.setEnabled(!busy);forget.setVisibility(waiting?View.VISIBLE:View.GONE);forget.setEnabled(!busy);
        boolean show=waiting||ModelStore.active||ModelStore.phase.equals("failed")||ModelStore.phase.equals("cancelled");transfer.setVisibility(show?View.VISIBLE:View.GONE);
        if(show){Ui.setText(progressTitle,waiting?getString(R.string.transfer_paused):ModelStore.phaseText(this)+" · "+ModelStore.jobLabel(this));String detail=ModelStore.total>0?Ui.size(ModelStore.downloaded)+" / "+Ui.size(ModelStore.total):"";if(!ModelStore.channel.isEmpty())detail+=" · "+ModelStore.channel;if(!ModelStore.error.isEmpty())detail+="\n"+ModelStore.error;Ui.setText(progressDetail,detail);bar.setVisibility(ModelStore.active?View.VISIBLE:View.GONE);bar.setIndeterminate(!ModelStore.phase.equals("download")||ModelStore.total==0);if(ModelStore.total>0)bar.setProgress((int)(100*ModelStore.downloaded/ModelStore.total));cancel.setVisibility(ModelStore.active&&!ModelStore.phase.equals("delete")?View.VISIBLE:View.GONE);}
    }
    private void importFolder(String id){
        new AlertDialog.Builder(this).setTitle(R.string.import_model).setMessage(R.string.import_help).setNegativeButton(android.R.string.cancel,null).setPositiveButton(tr("继续","Continue"),(d,w)->{
            importModel=id;startActivityForResult(new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION|Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION),44);
        }).show();
    }
    private void importFile(String id){
        importModel=id;startActivityForResult(new Intent(Intent.ACTION_OPEN_DOCUMENT).setType("*/*").addCategory(Intent.CATEGORY_OPENABLE).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION|Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION),45);
    }
    @Override protected void onActivityResult(int request,int result,Intent data){
        super.onActivityResult(request,result,data);
        if((request==44||request==45)&&result==RESULT_OK&&data!=null&&data.getData()!=null&&DownloadJournal.known(importModel)){
            try{getContentResolver().takePersistableUriPermission(data.getData(),Intent.FLAG_GRANT_READ_URI_PERMISSION);
                DownloadJournal.enqueue(this,new String[]{importModel},0,false,data.getData().toString());DownloadActions.resume(this);
            }catch(Exception e){info(getString(R.string.import_model),e.getMessage());}
        }
    }
    @Override protected void onResume(){super.onResume();changed();}
    @Override protected void onSaveInstanceState(Bundle b){b.putString("importModel",importModel);b.putInt("tab",tab);b.putBoolean("installedOnly",installedOnly);b.putBoolean("otherLanguages",otherLanguages);super.onSaveInstanceState(b);}
    @Override protected void onDestroy(){CaptionState.S.unobserve(this);super.onDestroy();}
}
