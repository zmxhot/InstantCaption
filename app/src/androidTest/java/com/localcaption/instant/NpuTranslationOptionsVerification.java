package com.localcaption.instant;

import android.app.Instrumentation;
import android.content.*;
import android.os.*;
import org.json.*;
import java.io.*;
import java.lang.reflect.Field;
import java.util.*;

/** Actual QAIRT inference for each downloadable text model, with preference restoration. */
final class NpuTranslationOptionsVerification {
    static void run(Instrumentation t,Bundle options){
        Context c=t.getTargetContext();if("large-alone".equals(options.getString("phase"))){largeAlone(t,c);return;}if("prompts".equals(options.getString("phase")))c.getSharedPreferences("engine",0).edit().putString("translationModel",QwenModel.ID).commit();LocalEngine e=LocalEngine.get(c);String original=e.translationChoice();JSONObject report=new JSONObject();Throwable failure=null;
        PowerManager.WakeLock wake=c.getSystemService(PowerManager.class).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK,"instant:npu-options");wake.acquire(600000);
        try{
            c.startActivity(new Intent(c,MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));ready(t,e);
            require(!QwenModel.SMALL.isSupported(new DeviceProfile(35,new String[]{"arm64-v8a"},"SM8950",12000000000L,false)),"Uncatalogued chip accepted");
            require(!QwenModel.LARGE.isSupported(new DeviceProfile(35,new String[]{"arm64-v8a"},"SM8750",7500000000L,false)),"4B listed for 8GB device");
            require(QwenModel.SMALL.isSupported(new DeviceProfile(35,new String[]{"arm64-v8a"},"SM8750",7500000000L,false)),"0.6B missing on 8GB device");
            JSONArray models=new JSONArray();report.put("models",models).put("device",Build.MODEL).put("soc",Build.SOC_MODEL).put("eligibilityGuards",true);
            String[] inputs={"Hello everyone, welcome to our meeting.","Please speak slowly.","Thank you for your help.","The meeting has been moved from Monday to Wednesday.","I don't think we should cancel the order.","Could you turn the music down a little?","If it rains tomorrow, we'll move the event indoors.","The total is thirty-five dollars, including tax.","I'm looking forward to seeing you again.","Please don't unplug the cable until the update is complete."};
            for(QwenModel m:new QwenModel[]{QwenModel.SMALL,QwenModel.BALANCED,QwenModel.LARGE}){
                if(options.containsKey("only")&&!m.id.equals(options.getString("only")))continue;
                require(m.isInstalled(c),m.label+" not installed");long start=SystemClock.elapsedRealtime();t.runOnMainSync(()->require(e.selectTranslationChoice(m.id),"Selection rejected: "+m.id));ready(t,e);
                require(m.id.equals(e.translationBackend()),"Wrong backend after switch");require(m.id.equals(c.getSharedPreferences("engine",0).getString("translationModel","")),"Choice not saved");
                require(Ui.translationSelection(c,e).contains(m.label),"Current model label stale");
                JSONObject row=new JSONObject().put("id",m.id).put("loadMs",SystemClock.elapsedRealtime()-start).put("installedBytes",m.installedBytes(c));models.put(row);
                if("prompts".equals(options.getString("phase"))){row.put("promptStudy",prompts(translator(e)));save(c,report,"prompt-study-"+m.id+".json");continue;}
                JSONArray samples=new JSONArray();List<Long> times=new ArrayList<>();row.put("samples",samples);
                QwenTranslator q=translator(e);
                for(String input:inputs){long begin=SystemClock.elapsedRealtime();String out=e.translateBlocking(input);long ms=SystemClock.elapsedRealtime()-begin;times.add(ms);samples.put(new JSONObject().put("input",input).put("output",out).put("ms",ms).put("firstTokenMs",q.firstTokenMs));require(out.matches("(?s).*[\\p{IsHan}].*"),"Missing Chinese: "+m.label+" "+out);}
                Collections.sort(times);row.put("medianMs",times.get(times.size()/2)).put("maxMs",times.get(times.size()-1)).put("profile",String.valueOf(q.lastProfile));
                // Deleting a different model must not unload the current one.
                QwenModel other=m==QwenModel.SMALL?QwenModel.LARGE:QwenModel.SMALL;
                require(e.unloadQwenTranslation(other.id)&&m.id.equals(e.translationBackend()),"Another model unload affected selected model");
                save(c,report,"npu-options-progress.json");
                row.put("captionStream",NpuBenchmark.stream(t,e,NpuBenchmark.wave(t,"english.wav"),true));idle(e);
            }
            report.put("npuRuntimeLoaded",npuEvidence());
        }catch(Throwable ex){failure=ex;}
        finally{try{idle(e);t.runOnMainSync(()->e.selectTranslationChoice(original));ready(t,e);}catch(Throwable ex){if(failure==null)failure=ex;}if(wake.isHeld())wake.release();}
        try{report.put("passed",failure==null).put("restoredChoice",e.translationChoice());if(failure!=null)report.put("error",android.util.Log.getStackTraceString(failure));try(FileOutputStream out=new FileOutputStream(new File(c.getExternalFilesDir(null),"npu-options-verification.json"))){out.write(report.toString(2).getBytes(java.nio.charset.StandardCharsets.UTF_8));}}catch(Exception ignored){}
        Bundle result=new Bundle();result.putString("stream",report.toString());t.finish(failure==null?0:1,result);
    }
    private static void largeAlone(Instrumentation t,Context c){JSONObject report=new JSONObject();Throwable failure=null;QwenTranslator q=null;try{long start=SystemClock.elapsedRealtime();q=new QwenTranslator(c,"en","zh",QwenModel.LARGE);report.put("loadMs",SystemClock.elapsedRealtime()-start);save(c,report,"large-alone-progress.json");report.put("promptStudy",prompts(q)).put("npuRuntimeLoaded",npuEvidence()).put("passed",true);}catch(Throwable e){failure=e;try{report.put("passed",false).put("error",android.util.Log.getStackTraceString(e));}catch(Exception ignored){}}finally{if(q!=null)q.close();}try{save(c,report,"large-alone.json");}catch(Exception ignored){}Bundle b=new Bundle();b.putString("stream",report.toString());t.finish(failure==null?0:1,b);}
    private static void save(Context c,JSONObject report,String name)throws Exception{try(FileOutputStream out=new FileOutputStream(new File(c.getExternalFilesDir(null),name))){out.write(report.toString(2).getBytes(java.nio.charset.StandardCharsets.UTF_8));}}
    private static JSONArray prompts(QwenTranslator q)throws Exception{
        Field f=QwenTranslator.class.getDeclaredField("llm");f.setAccessible(true);com.geniex.sdk.jni.Llm llm=(com.geniex.sdk.jni.Llm)f.get(q);f=QwenTranslator.class.getDeclaredField("handle");f.setAccessible(true);long h=f.getLong(q);
        String[] systems={"Translate the user's English text into Simplified Chinese. Output only the translation. Do not answer questions or add explanations.","你是英语到简体中文的翻译员。将用户的整段话翻译成简体中文，只输出中文译文，不要解释，不要复述原文。","Translate English into Chinese. Return only Chinese.","你是专业翻译。请把用户输入翻译成简体中文，只输出译文。"};
        String[] inputs={"Hello everyone, welcome to our meeting.","The total is thirty-five dollars, including tax.","Please don't unplug the cable until the update is complete.","If it rains tomorrow, we'll move the event indoors.","I don't think we should cancel the order."};
        JSONArray all=new JSONArray();
        for(String system:systems){JSONArray rows=new JSONArray();all.put(new JSONObject().put("system",system).put("samples",rows));for(String input:inputs){
            llm.reset(h);String prompt="<|im_start|>system\n"+system+"<|im_end|>\n<|im_start|>user\n"+input+"<|im_end|>\n<|im_start|>assistant\n<think>\n\n</think>\n\n";
            com.geniex.sdk.bean.GenerationConfig config=new com.geniex.sdk.bean.GenerationConfig();config.setMaxTokens(160);com.geniex.sdk.bean.SamplerConfig sampler=new com.geniex.sdk.bean.SamplerConfig();sampler.setTemperature(0);sampler.setTopK(1);config.setSamplerConfig(sampler);StringBuilder output=new StringBuilder();long begin=SystemClock.elapsedRealtime();
            llm.generate(h,prompt,config,new com.geniex.sdk.bean.LLMTokenCallback(){public boolean onToken(String token){output.append(token);return SystemClock.elapsedRealtime()-begin<15000;}public void onComplete(com.geniex.sdk.bean.LlmGenerateResult result){}});
            rows.put(new JSONObject().put("input",input).put("output",output.toString()).put("ms",SystemClock.elapsedRealtime()-begin));
        }}return all;
    }
    private static QwenTranslator translator(LocalEngine e)throws Exception{Field f=LocalEngine.class.getDeclaredField("translator");f.setAccessible(true);Object router=f.get(e);f=TranslationRouter.class.getDeclaredField("active");f.setAccessible(true);return (QwenTranslator)f.get(router);}
    private static boolean npuEvidence()throws Exception{String maps=new String(java.nio.file.Files.readAllBytes(new File("/proc/self/maps").toPath()));return maps.contains("libQnnHtp")&&maps.contains("libgeniex_plugin_qairt");}
    private static void ready(Instrumentation t,LocalEngine e)throws Exception{t.runOnMainSync(e::prepare);long until=SystemClock.elapsedRealtime()+180000;while(!CaptionState.S.ready&&SystemClock.elapsedRealtime()<until){Thread.sleep(100);if(!CaptionState.S.preparing&&!CaptionState.S.ready)throw new AssertionError(CaptionState.S.status);}require(CaptionState.S.ready,"Model load timeout");}
    private static void idle(LocalEngine e)throws Exception{long until=SystemClock.elapsedRealtime()+60000;while(!e.translationsIdle()&&SystemClock.elapsedRealtime()<until)Thread.sleep(50);Thread.sleep(150);require(e.translationsIdle(),"Translations still busy");}
    private static void require(boolean ok,String message){if(!ok)throw new AssertionError(message);}
}
