package com.localcaption.instant;

import android.app.Activity;
import android.app.AlertDialog;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.TextView;

/** Unavailable routes are explained before a language can be selected. */
final class LanguagePicker {
    static void show(Activity activity,boolean target){
        LocalEngine engine=LocalEngine.get(activity);if(engine.configurationBusy())return;
        SpeechLanguage[] options=target?SpeechLanguage.targets():SpeechLanguage.values();
        String[] labels=new String[options.length];boolean[] supported=new boolean[options.length];
        DeviceProfile device=DeviceProfile.detect(activity);
        for(int i=0;i<options.length;i++){
            String source=target?engine.sourceLanguage():options[i].id,destination=target?options[i].id:engine.targetLanguage();
            String choice=ModelCompatibility.forSelection(device,source,destination,engine.translationChoice());supported[i]=choice!=null;
            String detail=choice==null?Ui.t(activity,"不支持","Unsupported"):SpeechLanguage.canonical(source).equals(destination)?Ui.t(activity,"仅转写","Transcribe"):QwenModel.known(choice)?QwenModel.fromId(choice).label:"en".equals(destination)?"Whisper → English":"OPUS-MT";
            labels[i]=options[i].label(activity)+" · "+detail;
        }
        ArrayAdapter<String> adapter=new ArrayAdapter<String>(activity,android.R.layout.simple_list_item_1,labels){
            @Override public boolean areAllItemsEnabled(){return false;}
            @Override public boolean isEnabled(int p){return supported[p];}
            @Override public View getView(int p,View v,ViewGroup parent){TextView text=(TextView)super.getView(p,v,parent);text.setTextColor(supported[p]?Ui.WHITE:Ui.MUTED);text.setAlpha(supported[p]?1f:.55f);return text;}
        };
        new AlertDialog.Builder(activity).setTitle(target?Ui.t(activity,"字幕语言","Subtitle language"):Ui.t(activity,"说话语言","Spoken language")).setAdapter(adapter,(d,i)->{
            if(target)engine.setTargetLanguage(options[i].id);else engine.setSourceLanguage(options[i].id);
        }).setNegativeButton(android.R.string.cancel,null).show();
    }
}
