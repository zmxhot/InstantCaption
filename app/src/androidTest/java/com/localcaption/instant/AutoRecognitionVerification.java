package com.localcaption.instant;

import android.app.Instrumentation;
import android.os.Bundle;
import android.os.SystemClock;
import org.json.*;
import java.io.*;
import java.util.*;

final class AutoRecognitionVerification {
    static void run(Instrumentation test,Bundle options){
        JSONObject report=new JSONObject();Throwable failure=null;LocalEngine engine=null;
        android.os.PowerManager.WakeLock wake=test.getTargetContext().getSystemService(android.os.PowerManager.class).newWakeLock(android.os.PowerManager.PARTIAL_WAKE_LOCK,"instant:auto-recognition-test");wake.acquire(180000);
        try{
            android.content.Context context=test.getTargetContext();
            context.startActivity(new android.content.Intent(context,MainActivity.class).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK));
            report.put("googleTranslationSdkAbsence",TranslationVerification.googleDependencyCheck(context));
            report.put("device",android.os.Build.MODEL).put("internetPermission",context.checkSelfPermission(android.Manifest.permission.INTERNET)==android.content.pm.PackageManager.PERMISSION_GRANTED);
            JSONObject files=files(context);report.put("speechFilesBefore",files);
            engine=LocalEngine.get(context);final LocalEngine current=engine;
            require(engine.sourceLanguage().equals("en")&&engine.targetLanguage().equals("zh"),"Select English to Chinese before running this test");
            current.prepare();await(test,current);
            report.put("recognitionBackend",engine.recognitionBackend()).put("systemState",engine.systemSpeechState())
                .put("translationBackend",engine.translationBackend()).put("mainModelLabel",Ui.modelSummary(context,engine));
            require("opus".equals(engine.translationBackend()),"Text translation did not use OPUS");
            require(!Ui.modelSummary(context,engine).contains("Google"),"Main label exposed Google as translation model");
            if(engine.usesSystemSpeech())require(!engine.hasLocalSpeechRuntime()&&engine.requiredSpeechModel()==null,"System recognition loaded or requested a duplicate speech model");
            else require(engine.selectedModel()==NpuModel.SENSEVOICE||engine.selectedModel()==NpuModel.SENSEVOICE_CPU,"Automatic English fallback did not use SenseVoice");
            short[] audio=NpuBenchmark.wave(test,"english.wav");
            JSONObject stream=NpuBenchmark.stream(test,engine,audio,true);report.put("automaticStream",stream);
            require(stream.getString("english").toLowerCase(Locale.ROOT).contains("meeting")&&stream.getString("chinese").contains("会议"),"Automatic stream did not recognize and translate the meeting fixture");
            JSONArray scenes=new JSONArray();report.put("environmentOnlyScenes",scenes);
            for(String name:new String[]{"silence","rain","clock","vacuum","keyboard","dog","engine"}){
                idle(engine);short[] noise=name.equals("silence")?new short[80000]:NpuBenchmark.wave(test,"environment/"+name+".wav");
                JSONObject scene=NpuBenchmark.stream(test,engine,noise,false).put("scene",name);scenes.put(scene);require(scene.getInt("lines")==0,"Noise produced subtitles: "+name);
            }
            idle(engine);
            try(CaptionPipeline pipeline=new CaptionPipeline(engine,450)){
                test.runOnMainSync(()->current.setLanguages("ja","ja"));require(engine.sourceLanguage().equals("en"),"Language changed during an active recognition session");
            }
            idle(engine);report.put("activeSessionGuard",true);
            engine.systemSpeechFailed("Injected unavailable system service; device Google services unchanged");
            require(!engine.usesSystemSpeech(),"Fallback still routed to system");
            require(engine.selectedModel()==NpuModel.SENSEVOICE||engine.selectedModel()==NpuModel.SENSEVOICE_CPU,"Fallback did not choose SenseVoice");
            report.put("injectedSystemUnavailable",new JSONObject().put("scope","Injected unavailability, not a physical no-GMS phone test")
                .put("backend",engine.recognitionBackend()).put("stream",NpuBenchmark.stream(test,engine,audio,true)));
            idle(engine);test.runOnMainSync(current::recheckSpeech);await(test,engine);
            JSONObject after=files(context);require(files.toString().equals(after.toString()),"Automatic routing changed speech model files");
            report.put("speechFilesAfter",after).put("noExtraSpeechFilesDownloaded",true).put("restoredBackend",engine.recognitionBackend());
        }catch(Throwable error){failure=error;}
        finally{
            if(engine!=null)try{idle(engine);final LocalEngine current=engine;test.runOnMainSync(current::recheckSpeech);await(test,engine);}catch(Throwable restore){if(failure==null)failure=restore;else failure.addSuppressed(restore);}
        }
        try{report.put("passed",failure==null);if(failure!=null)report.put("error",android.util.Log.getStackTraceString(failure));
            try(FileOutputStream out=new FileOutputStream(new File(test.getTargetContext().getExternalFilesDir(null),"auto-recognition-verification.json"))){out.write(report.toString(2).getBytes(java.nio.charset.StandardCharsets.UTF_8));}
        }catch(Exception ignored){}
        if(wake.isHeld())wake.release();
        Bundle result=new Bundle();result.putString("stream",report.toString());test.finish(failure==null?0:1,result);
    }
    private static JSONObject files(android.content.Context c)throws Exception{
        JSONObject output=new JSONObject();for(NpuModel model:NpuModel.values()){
            JSONObject files=new JSONObject();File root=ModelStore.root(c,model);File[] entries=root.listFiles();
            if(entries!=null){Arrays.sort(entries,Comparator.comparing(File::getName));for(File entry:entries)if(entry.isFile())files.put(entry.getName(),entry.length());}
            output.put(model.id,files);
        }return output;
    }
    private static void await(Instrumentation test,LocalEngine engine)throws Exception{
        long until=SystemClock.elapsedRealtime()+120000;
        while(!CaptionState.S.ready||CaptionState.S.preparing){test.runOnMainSync(()->{});if(!CaptionState.S.preparing&&!CaptionState.S.ready)throw new AssertionError(CaptionState.S.status);if(SystemClock.elapsedRealtime()>until)throw new AssertionError("Preparation timeout");Thread.sleep(30);}
    }
    private static void idle(LocalEngine engine)throws Exception{long until=SystemClock.elapsedRealtime()+30000;while(engine.configurationBusy()){if(SystemClock.elapsedRealtime()>until)throw new AssertionError("Recognition cleanup timed out");Thread.sleep(20);}}
    private static void require(boolean ok,String message){if(!ok)throw new AssertionError(message);}
}
