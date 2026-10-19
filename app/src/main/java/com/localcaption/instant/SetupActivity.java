package com.localcaption.instant;

import android.app.AlertDialog;
import android.content.Intent;
import android.os.Bundle;
import android.widget.*;
import java.util.ArrayList;

/** The same guided setup is available on first launch and from Settings. */
public class SetupActivity extends BaseActivity implements CaptionState.Observer {
    private TextView device,plan,status,space;
    private Button source,target,recommend,download,done,check;
    private final ArrayList<String> missing=new ArrayList<>();
    @Override public void onCreate(Bundle state){super.onCreate(state);build();CaptionState.S.observe(this);changed();LocalEngine.get(this).prepare();}
    private void build(){
        LinearLayout root=Ui.root(this);Ui.title(this,root,getString(R.string.setup_title));ScrollView scroll=new ScrollView(this);LinearLayout body=Ui.column(this);body.setPadding(0,0,0,dp(20));scroll.addView(body);root.addView(scroll,new LinearLayout.LayoutParams(-1,0,1));
        add(body,Ui.text(this,getString(R.string.setup_intro),13,Ui.MUTED));
        LinearLayout languages=group(body,"01",Ui.t(this,"识别与翻译语言","Speech and translation languages"));
        source=button(languages,"",()->LanguagePicker.show(this,false));target=button(languages,"",()->LanguagePicker.show(this,true));
        LinearLayout models=group(body,"02",Ui.t(this,"识别与翻译模型","Speech and translation models"));
        device=Ui.text(this,"",12,Ui.MUTED);add(models,device);plan=Ui.text(this,"",14,Ui.WHITE);plan.setLineSpacing(dp(4),1);add(models,plan);
        recommend=button(models,getString(R.string.recommend_models),()->LocalEngine.get(this).useRecommendedConfiguration());
        button(models,Ui.t(this,"模型中心","Model center"),()->startActivity(new Intent(this,ModelManagerActivity.class)));
        LinearLayout ready=group(body,"03",getString(R.string.download_models));space=Ui.text(this,"",13,Ui.MUTED);add(ready,space);status=Ui.text(this,"",12,Ui.MINT);add(ready,status);
        download=button(ready,getString(R.string.download_models),()->{if(DownloadJournal.hasJobs(this))DownloadActions.resume(this);else DownloadActions.confirm(this,missing.toArray(new String[0]));});download.setTextColor(Ui.MINT);
        check=button(ready,getString(R.string.self_check),this::selfCheck);
        button(body,getString(R.string.privacy_title),()->new AlertDialog.Builder(this).setTitle(R.string.privacy_title).setMessage(R.string.privacy_summary).setPositiveButton(android.R.string.ok,null).show());
        done=button(body,getString(R.string.setup_complete),()->{AppPrefs.prefs(this).edit().putBoolean("setupComplete",true).apply();finish();});done.setBackground(Ui.bg(this,Ui.MINT,14));done.setTextColor(Ui.BG);done.setMinHeight(dp(56));
    }
    private LinearLayout group(LinearLayout parent,String number,String title){LinearLayout box=Ui.column(this);box.setPadding(dp(14),dp(14),dp(14),dp(12));box.setBackground(Ui.bg(this,Ui.CARD,16));TextView label=Ui.text(this,number+"  "+title,13,Ui.MINT);label.setTypeface(null,android.graphics.Typeface.BOLD);box.addView(label);add(parent,box);return box;}
    private void add(LinearLayout parent,android.view.View v){LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(-1,-2);p.topMargin=dp(12);parent.addView(v,p);}
    private Button button(LinearLayout parent,String title,Runnable action){Button button=Ui.button(this,title,0xFF1B2A37,Ui.WHITE);button.setMinHeight(dp(48));button.setOnClickListener(v->action.run());add(parent,button);return button;}
    @Override public void changed(){
        if(plan==null)return;LocalEngine engine=LocalEngine.get(this);boolean busy=engine.configurationBusy();
        Ui.setText(source,Ui.t(this,"说话语言：","Spoken language: ")+SpeechLanguage.label(this,engine.sourceLanguage()));
        Ui.setText(target,Ui.t(this,"字幕语言：","Subtitle language: ")+SpeechLanguage.label(this,engine.targetLanguage()));
        source.setEnabled(!busy);target.setEnabled(!busy);recommend.setEnabled(!busy&&ModelCompatibility.recommended(DeviceProfile.detect(this),engine.sourceLanguage(),engine.targetLanguage())!=null);
        Ui.setText(device,DeviceProfile.detect(this).summary(this));Ui.setText(plan,Ui.modelSummary(this,engine));
        missing.clear();long bytes=0;
        if(engine.translationAvailable()){
            if(!engine.usesSystemSpeech()&&!ModelStore.installed(this,engine.selectedModel())&&engine.isModelCompatible(engine.selectedModel()))missing.add(engine.selectedModel().id);
            if(!SpeechLanguage.canonical(engine.sourceLanguage()).equals(engine.targetLanguage())){
                QwenModel q=QwenModel.fromId(engine.translationChoice());if(q!=null&&!q.isInstalled(this))missing.add(q.id);
                if(q==null&&!"en".equals(engine.targetLanguage())&&TranslationModel.supportsTarget(engine.targetLanguage())&&!TranslationModel.installed(this,engine.targetLanguage()))missing.add(TranslationModel.id(engine.targetLanguage()));
            }
        }
        try{for(String id:missing)bytes+=DownloadActions.downloadBytes(this,id);}catch(Exception ignored){}
        Ui.setText(space,getString(R.string.setup_space,Ui.size(bytes)));
        Ui.setText(status,ModelStore.active?ModelStore.phaseText(this):!engine.translationAvailable()?getString(R.string.route_unavailable):!missing.isEmpty()&&!CaptionState.S.preparing?getString(R.string.missing_models):Ui.status(this,CaptionState.S.status));
        Ui.setText(download,getString(DownloadJournal.hasJobs(this)?R.string.resume_transfer:R.string.download_models));download.setEnabled(!busy&&(DownloadJournal.hasJobs(this)||!missing.isEmpty()));
        done.setEnabled(CaptionState.S.ready&&!busy);check.setEnabled(CaptionState.S.ready&&!busy);
    }
    private void selfCheck(){
        LocalEngine engine=LocalEngine.get(this);if(engine.configurationBusy()||!CaptionState.S.ready)return;
        CaptionState.S.preparing=true;CaptionState.S.changed();
        new Thread(()->{
            String result;
            try(SpeechDetector detector=engine.speechDetector()){
                float probability=detector.probability(new short[SpeechDetector.FRAME]);
                if(!Float.isFinite(probability))throw new IllegalStateException("Invalid voice detector output");
                String sample=engine.sourceLanguage().equals("zh")?"你好，谢谢你的帮助。":engine.sourceLanguage().equals("ja")?"こんにちは。ありがとうございます。":engine.sourceLanguage().equals("ko")?"안녕하세요. 감사합니다.":engine.sourceLanguage().equals("es")?"Hola, gracias por tu ayuda.":"123";
                if(engine.sourceLanguage().equals("en"))sample="Hello. Thank you for your help.";
                result=sample+"\n\n"+engine.translateBlocking(sample);
            }catch(Exception | LinkageError e){result=e.toString();}
            String message=result;CaptionState.MAIN.post(()->{CaptionState.S.preparing=false;CaptionState.S.changed();if(!isFinishing()&&!isDestroyed())new AlertDialog.Builder(this).setTitle(R.string.self_check).setMessage(message).setPositiveButton(android.R.string.ok,null).show();});
        },"local-model-check").start();
    }
    @Override protected void onDestroy(){CaptionState.S.unobserve(this);super.onDestroy();}
}
