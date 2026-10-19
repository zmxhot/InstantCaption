package com.localcaption.instant;

import android.content.Context;
import java.util.*;

/** Plans a complete, installed route. It never downloads or changes languages. */
final class ModelRecovery {
    record Plan(String text,String speech){}
    static Plan find(Context c,DeviceProfile device,String source,String target,String currentText,String currentSpeech,
                     boolean textFailed,Set<String> rejectedText,Set<String> rejectedSpeech){
        return find(c,device,source,target,currentText,currentSpeech,textFailed,rejectedText,rejectedSpeech,false);
    }
    static Plan find(Context c,DeviceProfile device,String source,String target,String currentText,String currentSpeech,
                     boolean textFailed,Set<String> rejectedText,Set<String> rejectedSpeech,boolean systemReady){
        List<String> texts=new ArrayList<>();
        if(!textFailed)texts.add(currentText);
        // Prefer a small portable fallback after a hardware/runtime failure.
        texts.add("opus");texts.add(QwenModel.HYMT2.id);texts.add(QwenModel.ID);texts.add(QwenModel.GEMMA4.id);texts.add(QwenModel.LARGE.id);texts.add(QwenModel.SMALL.id);texts.add(QwenModel.GEMMA3.id);
        for(String text:texts){
            if(rejectedText.contains(text)||!ModelCompatibility.supports(device,source,target,text)||!textInstalled(c,source,target,text))continue;
            boolean bridge=ModelCompatibility.bridge(source,target,text);
            if(systemReady&&!bridge&&!rejectedSpeech.contains("system")&&("auto".equals(currentSpeech)||"system".equals(currentSpeech)))return new Plan(text,"system");
            List<NpuModel> speech=new ArrayList<>();
            if(!"system".equals(currentSpeech)&&!"auto".equals(currentSpeech))speech.add(NpuModel.fromId(currentSpeech));
            speech.add(NpuModel.SENSEVOICE_CPU);speech.addAll(device.downloadableModels(source));
            for(NpuModel model:speech)if(model!=null&&!rejectedSpeech.contains(model.id)&&device.supports(model)&&model.supportsLanguage(source)
                &&(!bridge||model.supportsEnglishTranslation())&&ModelStore.installed(c,model))return new Plan(text,model.id);
        }
        return null;
    }
    static boolean textInstalled(Context c,String source,String target,String choice){
        if(SpeechLanguage.canonical(source).equals(target))return true;
        QwenModel model=QwenModel.fromId(choice);
        return model!=null?model.isInstalled(c):"opus".equals(choice)&&("en".equals(target)||TranslationModel.installed(c,target));
    }
    static String label(Plan plan){return (QwenModel.known(plan.text)?QwenModel.fromId(plan.text).label:"OPUS-MT")+" + "+("system".equals(plan.speech)?"System offline":NpuModel.fromId(plan.speech).label);}
}
