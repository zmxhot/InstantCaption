package com.localcaption.instant;

import android.app.Instrumentation;
import android.content.Context;
import android.os.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import org.json.*;

/** Executes the real shipped engines without changing the user's model selection. */
final class UpstreamRuntimeVerification {
    static void run(Instrumentation test,Bundle options){
        Context c=test.getTargetContext();JSONObject report=new JSONObject();Throwable failure=null;
        PowerManager.WakeLock wake=c.getSystemService(PowerManager.class).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK,"instant:upstream-test");wake.acquire(600000);
        try{
            require(!CaptionState.S.running&&!ModelStore.active,"Capture/download is active");
            report.put("device",Build.MODEL).put("soc",Build.SOC_MODEL).put("version",BuildConfig.VERSION_NAME);
            if(!"gemma-only".equals(options.getString("phase"))){
                long start=SystemClock.elapsedRealtime();
                try(QwenTranslator q=new QwenTranslator(c,"en","zh",QwenModel.BALANCED)){
                    String output=q.translate("The meeting has been moved from Monday to Wednesday.",()->true);
                    require(output.contains("三"),"Qwen regression: "+output);
                    report.put("qwenRegression",new JSONObject().put("output",output).put("loadAndRunMs",SystemClock.elapsedRealtime()-start));
                }
                JSONArray additional=new JSONArray();report.put("additionalRuntimeRegression",additional);
                for(QwenModel m:new QwenModel[]{QwenModel.SMALL,QwenModel.LARGE})if(m.isInstalled(c)){
                    start=SystemClock.elapsedRealtime();try(QwenTranslator q=new QwenTranslator(c,"en","zh",m)){
                        String output=q.translate("Please speak slowly.",()->true);require(output.matches("(?s).*[\\p{IsHan}].*"),m.id+" failed");
                        additional.put(new JSONObject().put("model",m.id).put("output",output).put("loadAndRunMs",SystemClock.elapsedRealtime()-start));
                    }
                }
                for(NpuModel m:new NpuModel[]{NpuModel.X_ASR,NpuModel.ZIPFORMER,NpuModel.NEMOTRON})if(ModelStore.installed(c,m)){
                    start=SystemClock.elapsedRealtime();try(NpuRecognizer r=new NpuRecognizer(c,m,"en",false)){
                        String output=r.recognize(NpuBenchmark.wave(test,"english.wav"));
                        int[] errors=NpuBenchmark.wer("Hello everyone welcome to our meeting we will discuss the new project today please speak slowly thank you for your help",output);
                        double wer=errors[0]/(double)errors[1];require(wer<=0.25,m.id+" WER regression: "+output);
                        additional.put(new JSONObject().put("model",m.id).put("output",output).put("wordErrorRate",wer).put("loadAndRunMs",SystemClock.elapsedRealtime()-start));
                    }
                }
                start=SystemClock.elapsedRealtime();
                try(NpuRecognizer r=new NpuRecognizer(c,NpuModel.SENSEVOICE,"en",false)){
                    String output=r.recognize(NpuBenchmark.wave(test,"english.wav"));
                    int[] errors=NpuBenchmark.wer("Hello everyone welcome to our meeting we will discuss the new project today please speak slowly thank you for your help",output);
                    double wer=errors[0]/(double)errors[1];require(wer<=0.25,"SenseVoice WER regression: "+output);
                    report.put("senseVoiceRegression",new JSONObject().put("output",output).put("wordErrorRate",wer).put("loadAndRunMs",SystemClock.elapsedRealtime()-start));
                }
            }
            report.put("availableMemoryBeforeGemma",RuntimeHealth.availableMemory(c));
            report.put("gemmaPath",QwenModel.GEMMA4.entry(c).getAbsolutePath()).put("gemmaFileBytes",QwenModel.GEMMA4.entry(c).length());
            long start=SystemClock.elapsedRealtime();
            try(LiteRtTranslator gemma=new LiteRtTranslator(c,"en","zh",QwenModel.GEMMA4)){
                report.put("gemmaLoadMs",SystemClock.elapsedRealtime()-start);
                JSONArray samples=new JSONArray();report.put("gemmaSamples",samples);
                for(String input:new String[]{"Hello everyone, welcome to our meeting.","Please speak slowly.","The meeting has been moved from Monday to Wednesday.","I don't think we should cancel the order.","Where is the nearest train station?"}){
                    start=SystemClock.elapsedRealtime();String output=gemma.translate(input,()->true);
                    samples.put(new JSONObject().put("input",input).put("output",output).put("ms",SystemClock.elapsedRealtime()-start));
                    require(output.matches("(?s).*[\\p{IsHan}].*"),"No Chinese translation: "+output);
                }
                boolean cancelled=false;try{gemma.translate("Cancelled translation",()->false);}catch(java.util.concurrent.CancellationException expected){cancelled=true;}
                require(cancelled,"Cancellation guard failed");
                java.util.concurrent.atomic.AtomicInteger calls=new java.util.concurrent.atomic.AtomicInteger();cancelled=false;
                try{gemma.translate("Although the weather was getting worse, we decided to continue our journey to the station before the last train departed.",()->calls.incrementAndGet()<6);}catch(java.util.concurrent.CancellationException expected){cancelled=true;}
                require(cancelled,"In-flight cancellation failed");
                String recovered=gemma.translate("The shop closes at six.",()->true);require(recovered.contains("六")||recovered.contains("6"),"Cancellation corrupted output: "+recovered);
                report.put("cancellationRecovery",recovered).put("npuEvidence",evidence());
            }
            // Exercise production selection, current-model label and the complete caption pipeline.
            LocalEngine local=LocalEngine.get(c);String original=local.translationChoice();
            try{
                test.runOnMainSync(()->require(local.selectTranslationChoice(QwenModel.GEMMA4.id),"Gemma selection rejected"));
                awaitReady(test,local);require(local.translationBackend().equals(QwenModel.GEMMA4.id),"Wrong active text backend");
                require(Ui.translationSelection(c,local).contains(QwenModel.GEMMA4.label),"Current model label is stale");
                report.put("gemmaCaptionPipeline",NpuBenchmark.stream(test,local,NpuBenchmark.wave(test,"english.wav"),true));
            }finally{
                long end=SystemClock.elapsedRealtime()+60000;while(local.configurationBusy()&&SystemClock.elapsedRealtime()<end)Thread.sleep(50);
                test.runOnMainSync(()->require(local.selectTranslationChoice(original),"Restore rejected"));awaitReady(test,local);
                report.put("restoredTextModel",local.translationChoice());
            }
        }catch(Throwable error){failure=error;}finally{if(wake.isHeld())wake.release();}
        try{report.put("passed",failure==null);if(failure!=null)report.put("error",android.util.Log.getStackTraceString(failure));
            try(FileOutputStream out=new FileOutputStream(new File(c.getExternalFilesDir(null),"upstream-runtime-verification.json"))){out.write(report.toString(2).getBytes(StandardCharsets.UTF_8));}
        }catch(Exception ignored){}
        Bundle result=new Bundle();result.putString("stream",report.toString());test.finish(failure==null?0:1,result);
    }
    private static JSONObject evidence()throws Exception{
        JSONArray devices=new JSONArray(),libraries=new JSONArray();Set<String> seen=new HashSet<>();
        for(File file:Objects.requireNonNull(new File("/proc/self/fd").listFiles()))try{String path=android.system.Os.readlink(file.getPath());if(path.contains("fastrpc"))devices.put(path);}catch(Exception ignored){}
        try(BufferedReader reader=new BufferedReader(new FileReader("/proc/self/maps"))){String line;while((line=reader.readLine())!=null)if(line.contains("libQnn")||line.contains("libLiteRt")||line.contains("liblitertlm")){String path=line.substring(line.lastIndexOf(' ')+1);if(seen.add(path))libraries.put(path);}}
        require(devices.length()>0,"No NPU device open");return new JSONObject().put("devices",devices).put("libraries",libraries);
    }
    private static void require(boolean ok,String message){if(!ok)throw new AssertionError(message);}
    private static void awaitReady(Instrumentation test,LocalEngine e)throws Exception{
        long end=SystemClock.elapsedRealtime()+180000;
        test.runOnMainSync(e::prepare);
        while(!CaptionState.S.ready&&SystemClock.elapsedRealtime()<end){Thread.sleep(100);if(!CaptionState.S.preparing&&!CaptionState.S.ready)throw new AssertionError(CaptionState.S.status);}
        require(CaptionState.S.ready,"Model initialization timed out");
    }
}
