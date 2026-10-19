package com.localcaption.instant;

import android.app.*;
import android.content.*;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Bundle;
import android.provider.Settings;
import android.view.*;
import android.widget.*;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;

public class SettingsActivity extends BaseActivity implements CaptionState.Observer {
    private LinearLayout content;
    private Button model,speechModels,language,sourceLanguage,targetLanguage,export,clear;
    private TextView translationStatus,about;
    private Button appearance;
    private Switch fast;
    private boolean refreshing;
    @Override public void onCreate(Bundle b){super.onCreate(b);build();CaptionState.S.observe(this);changed();}
    private void build(){
        LinearLayout root=Ui.root(this);Ui.title(this,root,tr("设置","Settings"));ScrollView scroll=new ScrollView(this);scroll.setFillViewport(true);content=Ui.column(this);content.setPadding(0,0,0,dp(20));scroll.addView(content);root.addView(scroll,new LinearLayout.LayoutParams(-1,0,1));
        section(tr("识别与翻译语言","Speech and translation languages"));
        sourceLanguage=button("");sourceLanguage.setOnClickListener(v->LanguagePicker.show(this,false));add(sourceLanguage,48);
        targetLanguage=button("");targetLanguage.setOnClickListener(v->LanguagePicker.show(this,true));add(targetLanguage,48);
        hint(tr("语言和模型选择会保存。请先停止字幕再修改。","Language and model choices are saved. Stop captions before making changes."));
        section(tr("识别与翻译模型","Speech and translation models"));
        model=button("");model.setGravity(Gravity.START|Gravity.CENTER_VERTICAL);model.setMinHeight(dp(64));model.setPadding(dp(12),dp(12),dp(12),dp(12));model.setOnClickListener(v->startActivity(new Intent(this,ModelManagerActivity.class).putExtra("tab",0)));add(model,-2);
        speechModels=button("");speechModels.setGravity(Gravity.START|Gravity.CENTER_VERTICAL);speechModels.setOnClickListener(v->startActivity(new Intent(this,ModelManagerActivity.class).putExtra("tab",1)));speechModels.setMinHeight(dp(64));speechModels.setPadding(dp(12),dp(10),dp(12),dp(10));add(speechModels,-2);
        translationStatus=Ui.text(this,"",12,Ui.MINT);translationStatus.setPadding(dp(2),dp(5),dp(2),dp(5));content.addView(translationStatus);
        section(tr("字幕显示","Caption display"));
        appearance=button("");appearance.setGravity(Gravity.START|Gravity.CENTER_VERTICAL);appearance.setPadding(dp(14),dp(12),dp(14),dp(12));appearance.setOnClickListener(v->startActivity(new Intent(this,AppearanceActivity.class)));add(appearance,64);
        fast=toggle(tr("极速字幕","Fast captions"),tr("更频繁更新草稿；停止字幕后可修改","Updates drafts more often; stop captions before changing"),AppPrefs.fast(this),(v,on)->{if(!refreshing)AppPrefs.put(this,"fast",on);});
        section(tr("界面语言","App language"));
        language=button("");language.setOnClickListener(v->chooseLanguage());add(language,48);
        hint(tr("默认跟随系统；不支持的系统语言使用英文。界面、语音和字幕语言分别设置。","Follows your system language by default, with English as the fallback. App, speech and subtitle languages are set independently."));
        section(tr("字幕记录","Session transcript"));
        LinearLayout actions=Ui.row(this);export=button(tr("导出字幕","Export"));export.setOnClickListener(v->startActivityForResult(new Intent(Intent.ACTION_CREATE_DOCUMENT).setType("text/plain").addCategory(Intent.CATEGORY_OPENABLE).putExtra(Intent.EXTRA_TITLE,"captions.txt"),30));clear=button(tr("清空字幕","Clear"));clear.setOnClickListener(v->new AlertDialog.Builder(this).setMessage(tr("清空本次会话字幕？","Clear the session transcript?")).setNegativeButton(tr("取消","Cancel"),null).setPositiveButton(tr("清空","Clear"),(d,i)->CaptionState.S.clear()).show());LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(0,-2,1);p.setMarginEnd(dp(8));actions.addView(export,p);actions.addView(clear,new LinearLayout.LayoutParams(0,-2,1));content.addView(actions);
        hint(tr("字幕仅保留在本次进程中，最多 80 句。","Captions are kept for this app session only, up to 80 lines."));
        section(tr("关于","About"));
        Button setup=button(getString(R.string.setup_title)+"  ›");setup.setOnClickListener(v->startActivity(new Intent(this,SetupActivity.class)));add(setup,48);
        Button privacy=button(getString(R.string.privacy_title));privacy.setOnClickListener(v->new AlertDialog.Builder(this).setTitle(R.string.privacy_title).setMessage(R.string.privacy_summary).setPositiveButton(android.R.string.ok,null).show());add(privacy,48);
        Button diagnostics=button(getString(R.string.diagnostics_title));diagnostics.setOnClickListener(v->new AlertDialog.Builder(this).setTitle(R.string.diagnostics_title).setMessage(R.string.diagnostics_notice).setNegativeButton(android.R.string.cancel,null).setPositiveButton(R.string.export_action,(d,w)->startActivityForResult(new Intent(Intent.ACTION_CREATE_DOCUMENT).setType("application/json").addCategory(Intent.CATEGORY_OPENABLE).putExtra(Intent.EXTRA_TITLE,"instant-caption-diagnostics.json"),31)).show());add(diagnostics,48);
        Button help=button(tr("使用说明与第三方许可","Help and third-party licenses"));help.setOnClickListener(v->help());add(help,48);
        about=Ui.text(this,"",12,Ui.MUTED);about.setLineSpacing(dp(3),1);about.setPadding(dp(2),dp(7),dp(2),dp(5));content.addView(about);
    }
    private Button button(String s){Button b=Ui.button(this,s,Ui.CARD,Ui.WHITE);b.setGravity(Gravity.START|Gravity.CENTER_VERTICAL);b.setPadding(dp(14),dp(10),dp(14),dp(10));return b;}
    private void add(View v,int height){LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(-1,-2);if(height>0)v.setMinimumHeight(dp(height));p.topMargin=dp(6);content.addView(v,p);}
    private void section(String s){TextView t=Ui.text(this,s,13,Ui.MINT);t.setTypeface(null,Typeface.BOLD);t.setPadding(dp(2),dp(18),0,dp(4));content.addView(t);}
    private void hint(String s){TextView t=Ui.text(this,s,12,Ui.MUTED);t.setLineSpacing(dp(3),1);t.setPadding(dp(2),dp(7),dp(2),dp(5));content.addView(t);}
    private Switch toggle(String name,String description,boolean checked,android.widget.CompoundButton.OnCheckedChangeListener listener){
        LinearLayout row=Ui.row(this);row.setPadding(dp(10),dp(8),dp(8),dp(8));row.setBackground(Ui.bg(this,Ui.CARD,12));LinearLayout labels=Ui.column(this);TextView title=Ui.text(this,name,14,Ui.WHITE);labels.addView(title);TextView detail=Ui.text(this,description,11,Ui.MUTED);detail.setPadding(0,dp(5),dp(8),0);labels.addView(detail);row.addView(labels,new LinearLayout.LayoutParams(0,-2,1));Switch control=new Switch(this);control.setContentDescription(name);control.setChecked(checked);control.setOnCheckedChangeListener(listener);row.addView(control,new LinearLayout.LayoutParams(dp(52),dp(48)));add(row,-2);return control;
    }
    private void chooseLanguage(){
        String[] tags=AppLanguage.TAGS,names=AppLanguage.NAMES.clone();names[0]=tr("跟随系统（默认）","System default");String current=AppPrefs.language(this);int selected=AppLanguage.index(current);
        ArrayAdapter<String> adapter=new ArrayAdapter<String>(this,android.R.layout.simple_list_item_1,names){@Override public View getView(int i,View v,ViewGroup p){TextView t=(TextView)super.getView(i,v,p);Ui.setText(t,(selected==i?"✓  ":"")+names[i]);t.setTextColor(selected==i?Ui.MINT:Ui.WHITE);return t;}};
        new AlertDialog.Builder(this).setTitle(tr("界面语言","App language")).setAdapter(adapter,(d,i)->{if(selected!=i){AppPrefs.language(this,tags[i]);if(android.os.Build.VERSION.SDK_INT<33)recreate();}}).setNegativeButton(tr("取消","Cancel"),null).show();
    }
    private void chooseSpeechLanguage(boolean target){
        if(LocalEngine.get(this).configurationBusy())return;
        LocalEngine engine=LocalEngine.get(this);SpeechLanguage[] options=target?SpeechLanguage.targets():SpeechLanguage.values();String current=target?engine.targetLanguage():engine.sourceLanguage();String[] labels=new String[options.length];for(int i=0;i<options.length;i++)labels[i]=options[i].label(this);
        ArrayAdapter<String> adapter=new ArrayAdapter<String>(this,android.R.layout.simple_list_item_1,labels){@Override public View getView(int i,View v,ViewGroup p){TextView text=(TextView)super.getView(i,v,p);boolean selected=current.equals(options[i].id);Ui.setText(text,(selected?"✓  ":"")+labels[i]);text.setTextColor(selected?Ui.MINT:Ui.WHITE);return text;}};
        new AlertDialog.Builder(this).setTitle(target?tr("字幕语言","Subtitle language"):tr("说话语言","Spoken language")).setAdapter(adapter,(d,i)->{if(target)engine.setTargetLanguage(options[i].id);else engine.setSourceLanguage(options[i].id);changed();}).setNegativeButton(tr("取消","Cancel"),null).show();
    }
    @Override protected void onResume(){super.onResume();changed();}
    @Override public void changed(){if(model==null)return;LocalEngine engine=LocalEngine.get(this);boolean blocked=engine.configurationBusy();
        Ui.setText(model,Ui.settingSummary(this,getString(R.string.text_model_settings_label),Ui.translationSelection(this,engine)));
        Ui.setText(speechModels,Ui.settingSummary(this,getString(R.string.speech_model_settings_label),engine.engineName()));
        Ui.setText(appearance,Ui.settingSummary(this,getString(R.string.caption_appearance),getString(R.string.caption_appearance_hint)));
        String route=Ui.modelSummary(this,engine);if(!engine.translationAvailable()||engine.recognitionUsesEnglishBridge())route+="\n"+Ui.offlineRouteExplanation(this,engine);Ui.setText(translationStatus,route);translationStatus.setVisibility(!engine.translationAvailable()||engine.recognitionUsesEnglishBridge()?View.VISIBLE:View.GONE);
        Ui.setText(sourceLanguage,tr("说话语言：","Spoken language: ")+SpeechLanguage.label(this,engine.sourceLanguage())+"  ▾");sourceLanguage.setEnabled(!blocked);
        Ui.setText(targetLanguage,tr("字幕语言：","Subtitle language: ")+SpeechLanguage.label(this,engine.targetLanguage())+"  ▾");targetLanguage.setEnabled(!blocked);
        fast.setEnabled(!CaptionState.S.running);export.setEnabled(!CaptionState.S.lines.isEmpty());clear.setEnabled(!CaptionState.S.running&&!CaptionState.S.lines.isEmpty());String lang=AppPrefs.language(this);Ui.setText(language,tr("界面语言：","App language: ")+AppLanguage.label(this,lang));
        String info=tr("瞬译 ","Instant Caption ")+BuildConfig.VERSION_NAME+"\n"+getString(R.string.about_local_models);Ui.setText(about,info);
    }
    @Override protected void onDestroy(){CaptionState.S.unobserve(this);super.onDestroy();}
    @Override protected void onActivityResult(int req,int result,Intent data){super.onActivityResult(req,result,data);if((req==30||req==31)&&result==RESULT_OK&&data!=null){try(OutputStream out=getContentResolver().openOutputStream(data.getData())){out.write((req==31?Diagnostics.export(this):CaptionState.S.transcript()).getBytes(StandardCharsets.UTF_8));Toast.makeText(this,req==31?getString(R.string.diagnostics_title):tr("字幕已导出","Transcript exported"),Toast.LENGTH_SHORT).show();}catch(Exception e){Toast.makeText(this,e.getMessage(),Toast.LENGTH_LONG).show();}}}
    private void help(){
        String message=getString(R.string.help_current_models);
        AlertDialog dialog=new AlertDialog.Builder(this).setTitle(tr("使用说明","Help")).setMessage(message).setPositiveButton(tr("知道了","OK"),null).setNeutralButton(tr("许可","Licenses"),(d,i)->licenses()).show();linkify(dialog.findViewById(android.R.id.message));
    }
    private void licenses(){
        String[] names={"licenses/LLAMA-CPP-LICENSE.txt","licenses/HY-MT2-NOTICE.txt","licenses/HY-MT2-LICENSE.txt","licenses/LITERT-LM-APACHE-2.0.txt","licenses/GEMMA3-NOTICE.txt","licenses/NPU-MULTI-MODEL-NOTICE.txt","licenses/GENIEX-BSD-3-Clause.txt","licenses/GENIEX-0.8.0-POM.xml","licenses/FunASR-MODEL-LICENSE.txt","licenses/CC-BY-4.0.txt","KOTLIN-LICENSE.txt","QWEN-LICENSE.txt","SHERPA-ONNX-LICENSE.txt","X-ASR-LICENSE.txt","licenses/Nemotron-LICENSE.txt","licenses/SenseVoice-LICENSE.txt","licenses/Zipformer-LICENSE.txt","licenses/SILERO-LICENSE.txt","licenses/COMMONS-LICENSE.txt","licenses/OPUS-MT-LICENSE.txt","licenses/OPUS-MULTILINGUAL-NOTICE.txt","licenses/ONNXRUNTIME-LICENSE.txt","licenses/whisper-MIT.txt","licenses/QWEN-NPU-NOTICE.txt"};
        java.util.TreeSet<String> all=new java.util.TreeSet<>(java.util.Arrays.asList(names));
        try{for(String name:getAssets().list("licenses"))if(name.endsWith(".txt")||name.endsWith(".xml")||name.endsWith(".json"))all.add("licenses/"+name);}catch(Exception e){Toast.makeText(this,e.getMessage(),Toast.LENGTH_LONG).show();}
        String[] files=all.toArray(new String[0]);
        new AlertDialog.Builder(this).setTitle(tr("第三方许可","Third-party licenses")).setItems(files,(d,i)->{try(java.io.InputStream in=getAssets().open(files[i])){licensePage(files[i],NpuRecognizer.readText(in),0);}catch(Exception e){Toast.makeText(this,e.getMessage(),Toast.LENGTH_LONG).show();}}).setNegativeButton(tr("关闭","Close"),null).show();
    }
    private void licensePage(String name,String text,int page){
        final int size=12000,pages=Math.max(1,(text.length()+size-1)/size);
        AlertDialog.Builder builder=new AlertDialog.Builder(this).setTitle(name.substring(name.lastIndexOf('/')+1)+" ("+(page+1)+"/"+pages+")")
            .setMessage(text.substring(page*size,Math.min(text.length(),(page+1)*size))).setPositiveButton(android.R.string.ok,null);
        if(page>0)builder.setNegativeButton("‹",(d,i)->licensePage(name,text,page-1));
        if(page+1<pages)builder.setNeutralButton("›",(d,i)->licensePage(name,text,page+1));
        AlertDialog dialog=builder.show();linkify(dialog.findViewById(android.R.id.message));
    }
    private void linkify(TextView text){if(text==null)return;android.text.util.Linkify.addLinks(text,android.text.util.Linkify.WEB_URLS);text.setMovementMethod(android.text.method.LinkMovementMethod.getInstance());text.setLinkTextColor(Ui.MINT);}
}
