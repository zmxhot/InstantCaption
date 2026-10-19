package com.localcaption.instant;

import android.app.Instrumentation;
import android.content.*;
import android.os.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.CancellationException;
import org.json.*;

final class RobustnessVerification {
    static void run(Instrumentation test,Bundle options){
        Context c=test.getTargetContext();JSONObject report=new JSONObject();LocalEngine engine=LocalEngine.get(c);
        String text=engine.translationChoice(),speech=engine.speechChoice();Throwable failure=null;
        try{
            require(!CaptionState.S.running&&!ModelStore.active,"Stop capture/download first");
            guardChecks(report);
            try(LiteRtTranslator gemma=new LiteRtTranslator(c,"en","zh",QwenModel.GEMMA4,32)){
                String input="Our meeting starts at nine in the morning. Please bring the blue folder and the project report. The first train leaves at ten. We will discuss the budget after lunch. The final access code is 7890.";
                long start=SystemClock.elapsedRealtime();String output=gemma.translate(input,()->true);
                require(gemma.cappedGenerations>0,"Output limit was not exercised");
                require(output.contains("7890"),"Last clause was lost: "+output);
                report.put("forcedOutputLimit",new JSONObject().put("limit",32).put("detectedLimits",gemma.cappedGenerations).put("lastDecodeTokens",gemma.lastDecodeTokens).put("output",output).put("ms",SystemClock.elapsedRealtime()-start));
            }
            test.runOnMainSync(()->require(engine.selectTranslationChoice(QwenModel.GEMMA4.id),"Cannot select Gemma"));await(test,engine);
            String longText="The meeting begins at nine. Please bring the project report. ".repeat(14)+"The final access code is 7890.";
            String translated=engine.translateBlocking(longText);require(translated.contains("7890"),"Long input lost its tail");
            report.put("longInput",new JSONObject().put("characters",longText.length()).put("tailPreserved",true));
            test.runOnMainSync(()->engine.modelFailed(LocalEngine.FailureStage.TEXT,new UnsatisfiedLinkError("Injected QA runtime failure")));
            ModelRecovery.Plan plan=engine.recoveryPlan();require(plan!=null&&!plan.text().equals(QwenModel.GEMMA4.id),"Recovery reused the failed model");
            require(!CaptionState.S.ready&&engine.hasModelFailure(),"Failure did not block capture");
            test.runOnMainSync(()->require(engine.recoverInstalledModels(),"Recovery action rejected"));await(test,engine);
            require(engine.translationChoice().equals(plan.text())&&!engine.hasModelFailure(),"Recovery did not load the advertised model");
            String output=engine.translateBlocking("The shop closes at six.");require(output.matches("(?s).*[\\p{IsHan}].*"),"Fallback did not translate");
            report.put("textRecovery",new JSONObject().put("text",plan.text()).put("speech",plan.speech()).put("output",output));
            String failedSpeech=engine.recognitionBackend();
            test.runOnMainSync(()->engine.modelFailed(LocalEngine.FailureStage.SPEECH,new IllegalStateException("Injected QA ASR failure")));
            ModelRecovery.Plan speechPlan=engine.recoveryPlan();require(speechPlan!=null&&!speechPlan.speech().equals(failedSpeech),"Recovery reused failed ASR");
            test.runOnMainSync(()->require(engine.recoverInstalledModels(),"Speech recovery rejected"));await(test,engine);
            String recognized=engine.recognizeNpuBlocking(NpuBenchmark.wave(test,"english.wav"));require(recognized.toLowerCase(Locale.ROOT).contains("welcome"),"Recovered ASR failed");
            report.put("speechRecovery",new JSONObject().put("speech",engine.recognitionBackend()).put("output",recognized));
            require(!ModelStore.active,"Recovery initiated a download");
            // Missing/unsupported routes are never advertised as installed fallbacks.
            Set<String> rejected=new HashSet<>();rejected.add("opus");for(QwenModel m:QwenModel.values())rejected.add(m.id);
            require(ModelRecovery.find(c,DeviceProfile.detect(c),"en","zh",text,speech,true,rejected,Set.of())==null,"Unavailable fallback advertised");
            Set<String> rejectedAsr=new HashSet<>();for(NpuModel m:NpuModel.values())rejectedAsr.add(m.id);
            ModelRecovery.Plan systemPlan=ModelRecovery.find(c,DeviceProfile.detect(c),"en","zh",QwenModel.GEMMA4.id,"auto",true,Set.of(QwenModel.GEMMA4.id),rejectedAsr,true);
            require(systemPlan!=null&&systemPlan.speech().equals("system"),"Ready system speech was not reused");
            require(ModelRecovery.find(c,DeviceProfile.detect(c),"en","zh",QwenModel.GEMMA4.id,"auto",true,Set.of(QwenModel.GEMMA4.id),rejectedAsr,false)==null,"Unavailable system service advertised");
            require(ModelFailure.classify(new IllegalStateException(new ModelFailure(ModelFailure.Kind.HOT)))==ModelFailure.Kind.HOT,"Nested thermal cause lost");
            require(ModelFailure.classify(new OutOfMemoryError("QA"))==ModelFailure.Kind.MEMORY,"Memory cause lost");
            report.put("systemReuseAndFailureCategories",true);
            for(String tag:AppLanguage.TAGS){if(tag.isEmpty())continue;android.content.res.Configuration cfg=new android.content.res.Configuration(c.getResources().getConfiguration());cfg.setLocale(Locale.forLanguageTag(tag));Context l=c.createConfigurationContext(cfg);require(l.getString(R.string.recovery_use,"MODEL").contains("MODEL"),"Broken recovery locale: "+tag);}
            report.put("recoveryLocales",10);
        }catch(Throwable e){failure=e;}finally{
            try{restore(test,engine,text,speech);report.put("restoredText",engine.translationChoice()).put("restoredSpeech",engine.speechChoice());}catch(Throwable e){if(failure==null)failure=e;else failure.addSuppressed(e);}
        }
        try{report.put("passed",failure==null);if(failure!=null)report.put("error",android.util.Log.getStackTraceString(failure));write(c,"robustness-verification.json",report);}catch(Exception ignored){}
        Bundle result=new Bundle();result.putString("stream",report.toString());test.finish(failure==null?0:1,result);
    }
    static void guardChecks(JSONObject report)throws Exception{
        String input="A😀word sentence with a final number 7890. ".repeat(30).trim();List<String> leaves=new ArrayList<>();int[] retries={0};
        String result=CompleteTranslation.translate(input,()->true,(part,current)->{
            require(!Character.isLowSurrogate(part.charAt(0))&&!Character.isHighSurrogate(part.charAt(part.length()-1)),"Broken surrogate");
            if(part.length()>55){retries[0]++;throw new CompleteTranslation.OutputLimit();}leaves.add(part);return part;
        });
        require(String.join("",leaves).equals(input),"Split lost or duplicated source characters");require(result.contains("7890")&&retries[0]>0,"Retry did not execute");
        boolean limited=false;try{CompleteTranslation.translate("A model that never stops producing text",()->true,(p,current)->{throw new CompleteTranslation.OutputLimit();});}catch(ModelFailure e){limited=e.kind==ModelFailure.Kind.LIMIT;}require(limited,"Unbounded retry or partial output");
        int[] calls={0};boolean cancelled=false;try{CompleteTranslation.translate("A long input that needs splitting into smaller parts",()->calls[0]<1,(p,current)->{calls[0]++;throw new CompleteTranslation.OutputLimit();});}catch(CancellationException e){cancelled=true;}require(cancelled&&calls[0]==1,"Canceled retry continued");
        boolean empty=false;try{CompleteTranslation.translate("text",()->true,(p,current)->"");}catch(ModelFailure e){empty=true;}require(empty,"Empty output accepted");
        report.put("splitCoverageUnicodeBoundedRetryCancellation",true);
    }
    static void await(Instrumentation test,LocalEngine engine)throws Exception{
        test.runOnMainSync(engine::prepare);long end=SystemClock.elapsedRealtime()+180000;
        while(!CaptionState.S.ready&&SystemClock.elapsedRealtime()<end){Thread.sleep(100);if(!CaptionState.S.preparing&&!CaptionState.S.ready)throw new AssertionError(CaptionState.S.status);}
        require(CaptionState.S.ready,"Preparation timed out");
    }
    static void restore(Instrumentation test,LocalEngine engine,String text,String speech)throws Exception{
        long end=SystemClock.elapsedRealtime()+60000;while(engine.configurationBusy()&&SystemClock.elapsedRealtime()<end)Thread.sleep(50);
        test.runOnMainSync(()->require(engine.selectTranslationChoice(text),"Restore text rejected"));await(test,engine);
        test.runOnMainSync(()->require(engine.selectSpeechChoice(speech),"Restore speech rejected"));await(test,engine);
    }
    static void write(Context c,String name,JSONObject report)throws Exception{try(FileOutputStream out=new FileOutputStream(new File(c.getExternalFilesDir(null),name))){out.write(report.toString(2).getBytes(StandardCharsets.UTF_8));}}
    static void require(boolean ok,String message){if(!ok)throw new AssertionError(message);}
}
