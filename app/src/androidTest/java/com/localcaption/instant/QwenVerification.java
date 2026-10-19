package com.localcaption.instant;

import android.app.Instrumentation;
import android.content.*;
import android.os.*;
import org.json.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** Real phone inference, shared QAIRT regression, direct language routes and paced captions. */
final class QwenVerification {
    static void run(Instrumentation test,Bundle options){
        Context c=test.getTargetContext();LocalEngine e=LocalEngine.get(c);JSONObject report=new JSONObject();Throwable failure=null;
        String originalSpeech=e.speechChoice(),originalSource=e.sourceLanguage(),originalTarget=e.targetLanguage();
        android.os.PowerManager.WakeLock wake=c.getSystemService(android.os.PowerManager.class).newWakeLock(android.os.PowerManager.PARTIAL_WAKE_LOCK,"instant:qwen-test");wake.acquire(600000);
        try{
            c.startActivity(new Intent(c,MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            report.put("device",Build.MODEL).put("soc",Build.SOC_MODEL).put("originalSpeechChoice",originalSpeech)
                .put("internetPermission",c.checkSelfPermission(android.Manifest.permission.INTERNET)==android.content.pm.PackageManager.PERMISSION_GRANTED);
            require(QwenModel.installed(c),"Qwen download is incomplete");
            require(QwenModel.supported(new DeviceProfile(35,new String[]{"arm64-v8a"},"SM8750",7_500_000_000L,false)),"Nominal 8 GB phone was rejected");
            require(!QwenModel.supported(new DeviceProfile(35,new String[]{"arm64-v8a"},"SM8950",12_000_000_000L,false)),"Uncatalogued chipset accepted");
            require(!QwenModel.supported(new DeviceProfile(34,new String[]{"arm64-v8a"},"SM8750",12_000_000_000L,false)),"Unsupported Android accepted");
            require(!QwenModel.supported(new DeviceProfile(35,new String[]{"arm64-v8a"},"SM8750",5_500_000_000L,false)),"Low memory accepted");
            report.put("deviceEligibilityGuards",true);
            require("en".equals(originalSource)&&"zh".equals(originalTarget),"English to Chinese required for this fixture");
            e.prepare();ready(test,e);
            if("smoke".equals(options.getString("phase"))){
                require(e.usesQwenTranslation(),"Expected Qwen choice");
                report.put("qwen",samples(e,new String[]{"Hello everyone, welcome to our meeting.","Please speak slowly.","Thank you for your help.","The meeting has been moved from Monday to Wednesday.","I don't think we should cancel the order.","Could you turn the music down a little?","If it rains tomorrow, we'll move the event indoors.","The total is thirty-five dollars, including tax.","I'm looking forward to seeing you again.","Please don't unplug the cable until the update is complete."},true));
                test.runOnMainSync(()->e.setLanguages("ja","zh"));ready(test,e);String japanese=e.translateBlocking("昨日、駅で財布をなくしました。");require(japanese.contains("钱包")||japanese.contains("錢包"),"Japanese text was not translated: "+japanese);report.put("japaneseDirect",japanese);
                test.runOnMainSync(()->e.setLanguages("en","zh"));ready(test,e);test.runOnMainSync(()->e.selectSpeechChoice("auto"));ready(test,e);
                report.put("automaticCaptionStream",NpuBenchmark.stream(test,e,NpuBenchmark.wave(test,"english.wav"),true));idle(e);test.runOnMainSync(()->e.selectSpeechChoice(originalSpeech));ready(test,e);
                report.put("senseVoiceCaptionStream",NpuBenchmark.stream(test,e,NpuBenchmark.wave(test,"english.wav"),true));idle(e);
                require(e.translateBlocking("The shop closes at six.").contains("六"),"Tokenizer regression");report.put("npuEvidence",npuEvidence());
            }else if("prompts".equals(options.getString("phase"))){
                test.runOnMainSync(()->e.selectTranslationChoice(QwenModel.ID));ready(test,e);promptStudy(e,report);
            }else if("restart".equals(options.getString("phase"))){
                require(e.usesQwenTranslation()&&QwenModel.ID.equals(e.translationBackend()),"Qwen selection did not survive restart");
                report.put("restartPersistsQwen",true).put("translation",e.translateBlocking("The train will arrive in five minutes."));
            }else{
                test.runOnMainSync(()->require(e.selectTranslationChoice("opus"),"Cannot select OPUS"));ready(test,e);
                String[] inputs={"Hello everyone, welcome to our meeting.","Please speak slowly.","Thank you for your help.","The meeting has been moved from Monday to Wednesday.","I don't think we should cancel the order.","Could you turn the music down a little?","If it rains tomorrow, we'll move the event indoors.","The total is thirty-five dollars, including tax.","I'm looking forward to seeing you again.","Please don't unplug the cable until the update is complete."};
                report.put("opus",samples(e,inputs,false));
                long start=SystemClock.elapsedRealtime();test.runOnMainSync(()->require(e.selectTranslationChoice(QwenModel.ID),"Cannot select Qwen"));ready(test,e);
                report.put("qwenSwitchAndPrepareMs",SystemClock.elapsedRealtime()-start).put("qwen",samples(e,inputs,true));
                require(!e.recognitionUsesEnglishBridge(),"Qwen still uses an English bridge");
                report.put("npuEvidence",npuEvidence());
                short[] audio=NpuBenchmark.wave(test,"english.wav");JSONArray switches=new JSONArray();report.put("recognitionCoexistence",switches);
                for(NpuModel m:NpuModel.values()){
                    if(!ModelStore.installed(c,m)||!e.isModelCompatible(m))continue;
                    test.runOnMainSync(()->require(e.selectSpeechChoice(m.id),"Cannot select "+m.id));ready(test,e);
                    long t=SystemClock.elapsedRealtime();String recognized=e.recognizeNpuBlocking(audio);long asr=SystemClock.elapsedRealtime()-t;
                    require(recognized.toLowerCase(Locale.ROOT).contains("meeting"),"Recognition regression: "+m.id+": "+recognized);
                    String translated=e.translateBlocking("This is a translation test with "+m.label+".");require(translated.matches("(?s).*[\\p{IsHan}].*"),"No Chinese after "+m.id);
                    switches.put(new JSONObject().put("speechModel",m.id).put("recognized",recognized).put("asrFixtureMs",asr).put("translation",translated).put("translationBackend",e.translationBackend()));
                }
                JSONArray routes=new JSONArray();report.put("directTextRoutes",routes);
                String[][] pairs={{"ja","zh","昨日、駅で財布をなくしました。"},{"ja","zh","予約をキャンセルするのではなく、来週に変更したいです。"},{"es","en","El tren llegará en cinco minutos."},{"zh","ja","请不要取消订单，我只是想修改收货地址。"},{"ko","zh","회의가 월요일에서 수요일로 변경되었습니다."}};
                for(String[] pair:pairs){test.runOnMainSync(()->e.setLanguages(pair[0],pair[1]));ready(test,e);require(!e.recognitionUsesEnglishBridge(),"English bridge retained");long t=SystemClock.elapsedRealtime();String out=e.translateBlocking(pair[2]);routes.put(new JSONObject().put("source",pair[0]).put("target",pair[1]).put("input",pair[2]).put("output",out).put("ms",SystemClock.elapsedRealtime()-t).put("recognitionBackend",e.recognitionBackend()));require(!out.isEmpty(),"Empty direct translation");}
                test.runOnMainSync(()->e.setLanguages("en","zh"));ready(test,e);
                test.runOnMainSync(()->e.selectSpeechChoice("auto"));ready(test,e);
                report.put("automaticRecognitionBackend",e.recognitionBackend());
                report.put("automaticCaptionStream",NpuBenchmark.stream(test,e,audio,true));idle(e);
                test.runOnMainSync(()->e.selectSpeechChoice("sensevoice"));ready(test,e);
                report.put("senseVoiceCaptionStream",NpuBenchmark.stream(test,e,audio,true));idle(e);
                JSONArray noise=new JSONArray();report.put("environmentOnly",noise);
                for(String scene:new String[]{"silence","rain","clock","vacuum","keyboard","dog","engine"}){
                    short[] pcm=scene.equals("silence")?new short[80000]:NpuBenchmark.wave(test,"environment/"+scene+".wav");
                    JSONObject s=NpuBenchmark.stream(test,e,pcm,false).put("scene",scene);noise.put(s);require(s.getInt("lines")==0,"Environment caption: "+scene);idle(e);
                }
                try(CaptionPipeline pipeline=new CaptionPipeline(e,450)){
                    test.runOnMainSync(()->{require(!e.selectTranslationChoice("opus"),"Translation switched during capture");require(!e.selectSpeechChoice("auto"),"Speech switched during capture");});
                    require(!ModelStore.reserveQwen(c,"delete"),"Qwen removal allowed during capture");
                }idle(e);report.put("captureGuards",true);
                QwenTranslator translator=qwen(e);java.util.concurrent.atomic.AtomicInteger calls=new java.util.concurrent.atomic.AtomicInteger();boolean cancelled=false;
                try{translator.translate("Although the weather was getting worse, we decided to continue our journey to the station before the last train departed.",()->calls.incrementAndGet()<6);}catch(java.util.concurrent.CancellationException expected){cancelled=true;}
                require(cancelled,"Obsolete translation was not cancelled");String recovery=e.translateBlocking("The shop closes at six.");report.put("cancellationRecoveryOutput",recovery);require(recovery.contains("六")||recovery.contains("6"),"Cancellation corrupted next translation: "+recovery);report.put("cancellationRecovery",true);
                report.put("googleTranslationSdkAbsence",TranslationVerification.googleDependencyCheck(c));
            }
        }catch(Throwable error){failure=error;}
        finally{
            try{idle(e);test.runOnMainSync(()->e.setLanguages(originalSource,originalTarget));ready(test,e);test.runOnMainSync(()->e.selectTranslationChoice(QwenModel.ID));ready(test,e);test.runOnMainSync(()->e.selectSpeechChoice(originalSpeech));ready(test,e);report.put("finalSpeechChoice",e.speechChoice()).put("finalTranslation",e.translationBackend());}catch(Throwable restore){if(failure==null)failure=restore;else failure.addSuppressed(restore);}
            if(wake.isHeld())wake.release();
        }
        try{report.put("passed",failure==null);if(failure!=null)report.put("error",android.util.Log.getStackTraceString(failure));try(FileOutputStream out=new FileOutputStream(new File(c.getExternalFilesDir(null),"qwen-verification"+("restart".equals(options.getString("phase"))?"-restart":"")+".json"))){out.write(report.toString(2).getBytes(StandardCharsets.UTF_8));}}catch(Exception ignored){}
        Bundle result=new Bundle();result.putString("stream",report.toString());test.finish(failure==null?0:1,result);
    }
    private static JSONArray samples(LocalEngine e,String[] inputs,boolean qwen)throws Exception{
        JSONArray rows=new JSONArray();for(String input:inputs){long t=SystemClock.elapsedRealtime();String out=e.translateBlocking(input);JSONObject row=new JSONObject().put("input",input).put("output",out).put("ms",SystemClock.elapsedRealtime()-t);if(qwen){QwenTranslator q=qwen(e);row.put("firstTokenMs",q.firstTokenMs).put("profile",String.valueOf(q.lastProfile));}rows.put(row);require(out.matches("(?s).*[\\p{IsHan}].*"),"No Chinese translation");}return rows;
    }
    private static QwenTranslator qwen(LocalEngine e)throws Exception{java.lang.reflect.Field f=LocalEngine.class.getDeclaredField("translator");f.setAccessible(true);Object router=f.get(e);f=TranslationRouter.class.getDeclaredField("active");f.setAccessible(true);return (QwenTranslator)f.get(router);}
    private static void promptStudy(LocalEngine e,JSONObject report)throws Exception{
        QwenTranslator q=qwen(e);java.lang.reflect.Field f=QwenTranslator.class.getDeclaredField("llm");f.setAccessible(true);com.geniex.sdk.jni.Llm llm=(com.geniex.sdk.jni.Llm)f.get(q);f=QwenTranslator.class.getDeclaredField("handle");f.setAccessible(true);long h=f.getLong(q);
        String[] systems={"Translate the user's English text into Simplified Chinese. Output only the translation. Do not answer questions or add explanations.","你是专业的同声传译员。将用户输入完整翻译为自然、准确的简体中文，只输出译文，不要解释或回答问题。人名和产品名保留原文，其他词语都要翻译。","请把下面的文字翻译成简体中文，只输出翻译结果。","You are an English to Simplified Chinese translator. Translate the entire text into fluent Chinese. Translate all ordinary words and numbers, preserve only names. Return only the translation."};
        String[] inputs={"If it rains tomorrow, we'll move the event indoors.","The total is thirty-five dollars, including tax.","The shop closes at six.","Please don't unplug the cable until the update is complete.","This is a translation test with Nemotron Speech Streaming 0.6B.","This is a translation test with Zipformer 中英双语.","I don't think we should cancel the order.","予約をキャンセルするのではなく、来週に変更したいです。","We will discuss the new project today."};
        JSONArray all=new JSONArray();report.put("promptStudy",all);
        for(String system:systems){JSONArray runs=new JSONArray();all.put(new JSONObject().put("system",system).put("samples",runs));for(String input:inputs){
            llm.reset(h);String prompt="<|im_start|>system\n"+system+"<|im_end|>\n<|im_start|>user\n"+input+"<|im_end|>\n<|im_start|>assistant\n<think>\n\n</think>\n\n";
            com.geniex.sdk.bean.GenerationConfig config=new com.geniex.sdk.bean.GenerationConfig();config.setMaxTokens(192);com.geniex.sdk.bean.SamplerConfig sampler=new com.geniex.sdk.bean.SamplerConfig();sampler.setTemperature(0);sampler.setTopK(1);config.setSamplerConfig(sampler);
            long start=SystemClock.elapsedRealtime();com.geniex.sdk.bean.LlmGenerateResult result=llm.generate(h,prompt,config,null);runs.put(new JSONObject().put("input",input).put("output",result.getFullText()).put("ms",SystemClock.elapsedRealtime()-start));
        }}
    }
    private static JSONObject npuEvidence()throws Exception{
        JSONArray fds=new JSONArray();File[] all=new File("/proc/self/fd").listFiles();if(all!=null)for(File file:all)try{String path=android.system.Os.readlink(file.getPath());if(path.contains("fastrpc"))fds.put(path);}catch(Exception ignored){}
        JSONArray maps=new JSONArray();try(BufferedReader r=new BufferedReader(new FileReader("/proc/self/maps"))){String line;Set<String> seen=new HashSet<>();while((line=r.readLine())!=null)if(line.contains("libQnn")||line.contains("libgeniex")){String path=line.substring(line.lastIndexOf(' ')+1);if(seen.add(path))maps.put(path);}}
        require(fds.length()>0,"No open FastRPC NPU device");return new JSONObject().put("runtime","QAIRT 2.45 / Hexagon NPU").put("deviceDescriptors",fds).put("libraries",maps);
    }
    private static void ready(Instrumentation test,LocalEngine e)throws Exception{long until=SystemClock.elapsedRealtime()+180000;while(true){test.runOnMainSync(()->{});if(CaptionState.S.ready&&!CaptionState.S.preparing)return;if(!CaptionState.S.preparing)throw new AssertionError(CaptionState.S.status);if(SystemClock.elapsedRealtime()>until)throw new AssertionError("Model preparation timeout");Thread.sleep(40);}}
    private static void idle(LocalEngine e)throws Exception{long until=SystemClock.elapsedRealtime()+60000;while(e.configurationBusy()){if(SystemClock.elapsedRealtime()>until)throw new AssertionError("Pipeline cleanup timeout");Thread.sleep(30);}}
    private static void require(boolean condition,String message){if(!condition)throw new AssertionError(message);}
}
