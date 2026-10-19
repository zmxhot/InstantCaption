package com.localcaption.instant;

import android.app.Instrumentation;
import android.content.*;
import android.os.*;
import org.json.*;
import java.io.*;
import java.util.*;

/** Runs real model switches and proves independent download/deletion targeting. */
final class ModelChoiceVerification {
    static void run(Instrumentation test,Bundle options){
        Context c=test.getTargetContext();JSONObject report=new JSONObject();Throwable failure=null;
        LocalEngine engine=LocalEngine.get(c);String original=engine.speechChoice();boolean restart="restart".equals(options.getString("phase"));
        try{
            c.startActivity(new Intent(c,MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            require("en".equals(engine.sourceLanguage())&&"zh".equals(engine.targetLanguage()),"English to Chinese required");
            engine.prepare();ready(test,engine);
            if("french".equals(options.getString("phase"))){
                require(TranslationModel.installed(c,"fr"),"French pack download did not finish");
                test.runOnMainSync(()->engine.setTargetLanguage("fr"));ready(test,engine);String french=engine.translateBlocking("Thank you for your help.");require(french.toLowerCase(Locale.ROOT).contains("merci"),"Downloaded French model did not translate");
                test.runOnMainSync(()->engine.setTargetLanguage("zh"));ready(test,engine);require(original.equals(engine.speechChoice()),"Speech selection changed after returning to Chinese");
                report.put("downloadedFrenchTranslation",french).put("restoredTarget",engine.targetLanguage()).put("restoredSpeechChoice",engine.speechChoice());
            }else if(restart){
                require("x-asr".equals(engine.speechChoice())&&!engine.usesSystemSpeech(),"Manual selection lost after process restart");
                require(engine.selectedModel()==NpuModel.X_ASR,"Restart loaded a different model");
                report.put("manualChoiceSurvivesRestart",true).put("recognitionBackend",engine.recognitionBackend()).put("translationBackend",engine.translationBackend());
                test.runOnMainSync(()->engine.selectSpeechChoice("auto"));ready(test,engine);
                report.put("returnedToAutomatic",engine.automaticSpeech());
            }else{
                report.put("originalChoice",original);JSONArray runs=new JSONArray();report.put("switches",runs);
                short[] audio=NpuBenchmark.wave(test,"english.wav");
                for(NpuModel model:NpuModel.values()){
                    if(!engine.isModelCompatible(model)||!ModelStore.installed(c,model))continue;
                    long start=SystemClock.elapsedRealtime();final boolean[] changed={false};test.runOnMainSync(()->changed[0]=engine.selectSpeechChoice(model.id));require(changed[0],"Selection rejected: "+model.id);ready(test,engine);
                    require(!engine.usesSystemSpeech()&&engine.selectedModel()==model,"Manual choice overridden: "+model.id);
                    require(model.id.equals(c.getSharedPreferences("engine",0).getString("speechChoice:en:zh","")),"Manual choice not saved");
                    String text=engine.recognizeNpuBlocking(audio);require(text!=null&&!text.trim().isEmpty(),"Selected model returned no recognition");
                    String translated=engine.translateBlocking("Welcome to our meeting.");require(translated.contains("会议"),"Text translation changed with speech model");
                    runs.put(new JSONObject().put("speechModel",model.id).put("processor",model.isNpu?"NPU":"CPU").put("textTranslation",engine.translationBackend()).put("recognized",text).put("translated",translated).put("switchAndInferMs",SystemClock.elapsedRealtime()-start));
                }
                test.runOnMainSync(()->engine.selectSpeechChoice("x-asr"));ready(test,engine);
                try(CaptionPipeline pipeline=new CaptionPipeline(engine,450)){
                    test.runOnMainSync(()->require(!engine.selectSpeechChoice("auto"),"Selection allowed during capture session"));
                    require(!ModelStore.reserveTranslation(c,"download","de"),"Download allowed during capture session");
                }
                report.put("activeSessionGuards",true);
                if(TranslationModel.installed(c,"de")){
                    test.runOnMainSync(()->engine.setTargetLanguage("de"));ready(test,engine);String german=engine.translateBlocking("Thank you for your help.");require(german.toLowerCase(Locale.ROOT).contains("dank"),"German model not loaded");
                    test.runOnMainSync(()->engine.setTargetLanguage("zh"));ready(test,engine);require("x-asr".equals(engine.speechChoice()),"Per-pair manual choice was lost");
                    report.put("translationLanguageSwitch",new JSONObject().put("german",german).put("restoredSpeechChoice",engine.speechChoice()));
                }
                String spare="es";File temp=TranslationModel.root(c,spare),cache=new File(ModelStore.downloadRoot(c),TranslationModel.id(spare));
                require(!temp.exists()&&!cache.exists(),"Test requires unused Spanish storage; existing files preserved");
                require(temp.mkdirs(),"Cannot create isolated deletion fixture");try(FileOutputStream out=new FileOutputStream(new File(temp,"qa-marker"))){out.write(1);}
                require(ModelStore.reserveTranslation(c,"delete",spare),"Non-current translation job rejected");
                require("es".equals(ModelStore.translationJobTarget)&&"zh".equals(engine.targetLanguage()),"Job changed subtitle target");
                ModelStore.runTranslation(c,true,0);require("deleted".equals(ModelStore.phase)&&!temp.exists(),"Explicit non-current deletion did not complete");
                require(TranslationModel.installed(c,"zh")&&engine.translationReady()&&CaptionState.S.ready,"Non-current deletion affected active translation");
                report.put("nonCurrentTranslationDeletionIsolated",true).put("activeTranslationPreserved",engine.translateBlocking("Please speak slowly."));
                require(TranslationModel.targetFromId("opus-mt-en-../../") == null,"Unsafe model ID accepted");
                require(TranslationModel.targetFromId("opus-mt-en-fr").equals("fr"),"Non-current model ID rejected");
                java.lang.reflect.Method network=ModelManagementTest.class.getDeclaredMethod("networkChecks",File.class,JSONObject.class);network.setAccessible(true);
                File workspace=new File(c.getCacheDir(),"qa-choice-network");require(workspace.mkdirs()||workspace.isDirectory(),"Cannot create network fixture folder");
                network.invoke(null,workspace,report);ModelStore.erase(c,workspace,c.getCacheDir());
                report.put("savedForRestart","x-asr");
            }
        }catch(Throwable error){failure=error;}
        if(failure!=null)try{test.runOnMainSync(()->{if(!engine.configurationBusy()){engine.setLanguages("en","zh");}});}catch(Throwable ignored){}
        try{report.put("passed",failure==null);if(failure!=null)report.put("error",android.util.Log.getStackTraceString(failure));
            try(FileOutputStream out=new FileOutputStream(new File(c.getExternalFilesDir(null),restart?"model-choice-restart.json":"model-choice-verification.json"))){out.write(report.toString(2).getBytes(java.nio.charset.StandardCharsets.UTF_8));}
        }catch(Exception ignored){}
        Bundle result=new Bundle();result.putString("stream",report.toString());test.finish(failure==null?0:1,result);
    }
    private static void ready(Instrumentation test,LocalEngine engine)throws Exception{
        long until=SystemClock.elapsedRealtime()+180000;
        while(true){test.runOnMainSync(()->{});if(CaptionState.S.ready&&!CaptionState.S.preparing)return;if(!CaptionState.S.preparing&&!CaptionState.S.ready)throw new AssertionError(CaptionState.S.status);if(SystemClock.elapsedRealtime()>until)throw new AssertionError("Model load timed out");Thread.sleep(40);}
    }
    private static void require(boolean ok,String why){if(!ok)throw new AssertionError(why);}
}
