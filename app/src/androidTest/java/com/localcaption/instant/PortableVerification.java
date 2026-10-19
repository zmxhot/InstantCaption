package com.localcaption.instant;

import android.app.Instrumentation;
import android.content.Context;
import android.os.*;
import java.io.*;
import org.json.*;

/** Runs the unmodified upstream CPU runtime combination with Qualcomm libraries absent. */
final class PortableVerification {
    static void run(Instrumentation test){Bundle result=new Bundle();JSONObject report=new JSONObject();Context c=test.getTargetContext();
        try{
            if(BuildConfig.NPU_BUILD)throw new AssertionError("Expected portable build");
            if(new File(c.getApplicationInfo().nativeLibraryDir,"libQnnHtp.so").exists())throw new AssertionError("Unexpected QNN native library");
            JSONArray speech=new JSONArray();
            for(NpuModel model:new NpuModel[]{NpuModel.SENSEVOICE_CPU,NpuModel.WHISPER_TINY}){
                long start=SystemClock.elapsedRealtime();
                try(NpuRecognizer recognizer=new NpuRecognizer(c,model,"en",false)){
                    long loaded=SystemClock.elapsedRealtime();String text=recognizer.recognize(NpuBenchmark.wave(test,"english.wav"));
                    int[] wer=NpuBenchmark.wer("Hello everyone welcome to our meeting we will discuss the new project today please speak slowly thank you for your help",text);
                    if(wer[0]/(double)wer[1]>.25)throw new AssertionError("CPU recognition failed: "+text);
                    speech.put(new JSONObject().put("model",model.id).put("loadMs",loaded-start).put("decodeMs",SystemClock.elapsedRealtime()-loaded).put("wer",wer[0]/(double)wer[1]).put("text",text));
                }
            }
            long t=SystemClock.elapsedRealtime();
            try(MarianTranslator translator=new MarianTranslator(TranslationModel.root(c,"zh"),TranslationModel.spec(c,"zh"))){
                String text=translator.translate("Thank you for your help.",()->true);
                if(!text.matches("(?s).*[\\p{IsHan}].*"))throw new AssertionError("CPU translation returned no Chinese");
                report.put("translation",text).put("translationLoadAndInferMs",SystemClock.elapsedRealtime()-t);
            }
            report.put("speech",speech).put("qualcommNativeLibrariesAbsent",true).put("passed",true);
        }catch(Throwable e){try{report.put("passed",false).put("error",android.util.Log.getStackTraceString(e));}catch(Exception ignored){}}
        try(FileOutputStream out=new FileOutputStream(new File(c.getExternalFilesDir(null),"portable-verification.json"))){out.write(report.toString(2).getBytes(java.nio.charset.StandardCharsets.UTF_8));}catch(Exception ignored){}
        result.putString("stream",report.toString());test.finish(report.optBoolean("passed")?0:1,result);
    }
}
