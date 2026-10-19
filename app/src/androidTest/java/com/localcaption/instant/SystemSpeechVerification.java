package com.localcaption.instant;

import android.app.Instrumentation;
import android.os.Bundle;
import android.os.SystemClock;
import org.json.*;
import java.io.*;

final class SystemSpeechVerification{
    static void run(Instrumentation test,Bundle options){
        JSONObject report=new JSONObject();Throwable failure=null;
        android.os.PowerManager.WakeLock wake=test.getTargetContext().getSystemService(android.os.PowerManager.class).newWakeLock(android.os.PowerManager.PARTIAL_WAKE_LOCK,"instant:system-speech-test");wake.acquire(150000);
        Thread observer=new Thread(()->{try{Thread.sleep(25000);StringBuilder stacks=new StringBuilder();for(java.util.Map.Entry<Thread,StackTraceElement[]> entry:Thread.getAllStackTraces().entrySet())if(entry.getKey().getName().equals("main")||entry.getKey().getName().contains("verification")){stacks.append(entry.getKey().getName()).append('\n');for(StackTraceElement frame:entry.getValue())stacks.append(frame).append('\n');}try(FileOutputStream out=new FileOutputStream(new File(test.getTargetContext().getExternalFilesDir(null),"system-speech-threads.txt"))){out.write(stacks.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8));}}catch(Exception ignored){}},"speech-probe-observer");observer.start();
        try{
            String language=options.getString("language","en");
            report.put("googleServices",SystemSpeech.googleServicesPresent(test.getTargetContext()));
            SystemSpeech.Support support=SystemSpeech.probe(test.getTargetContext(),language);
            if("true".equals(options.getString("download","false"))&&!support.ready){Bundle progress=new Bundle();progress.putString("progress","Preparing system pack "+language);test.sendStatus(0,progress);SystemSpeech.download(test.getTargetContext(),support);support=SystemSpeech.probe(test.getTargetContext(),language);report.put("explicitSystemPackPreparation",true);}
            report.put("ready",support.ready).put("reason",support.reason).put("language",support.language).put("installed",new JSONArray(support.installed)).put("downloadable",new JSONArray(support.downloadable)).put("pending",new JSONArray(support.pending));
            Bundle progress=new Bundle();progress.putString("progress",report.toString());test.sendStatus(0,progress);
            if(support.ready){
                short[] audio=NpuBenchmark.wave(test,"english.wav");long start=SystemClock.elapsedRealtime();JSONArray updates=new JSONArray();
                try(SystemSpeech.Stream stream=new SystemSpeech.Stream(test.getTargetContext(),support.language,text->{synchronized(updates){try{updates.put(new JSONObject().put("text",text).put("ms",SystemClock.elapsedRealtime()-start));}catch(JSONException e){throw new IllegalStateException(e);}}})){
                    for(int p=0;p<audio.length;p+=800){int n=Math.min(800,audio.length-p);stream.accept(java.util.Arrays.copyOfRange(audio,p,p+n),n);if("true".equals(options.getString("paced","false"))){long wait=start+(p+n)/16-SystemClock.elapsedRealtime();if(wait>0)Thread.sleep(wait);}}
                    String words=stream.finish();report.put("recognized",words).put("elapsedMs",SystemClock.elapsedRealtime()-start).put("updates",updates);
                    if(!words.toLowerCase(java.util.Locale.ROOT).contains("meeting"))throw new AssertionError("Injected audio was not recognized");
                }
            }
            report.put("passed",true);
        }catch(Throwable e){failure=e;try{report.put("passed",false).put("error",android.util.Log.getStackTraceString(e));}catch(Exception ignored){}}
        try(FileOutputStream out=new FileOutputStream(new File(test.getTargetContext().getExternalFilesDir(null),"system-speech-verification.json"))){out.write(report.toString(2).getBytes(java.nio.charset.StandardCharsets.UTF_8));}catch(Exception ignored){}
        observer.interrupt();if(wake.isHeld())wake.release();Bundle result=new Bundle();result.putString("stream",report.toString());test.finish(failure==null?0:1,result);
    }
}
