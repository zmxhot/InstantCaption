package com.localcaption.instant;

import android.app.Instrumentation;
import android.content.*;
import android.os.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.util.*;
import org.json.*;

/** Device integration tests use a separate preference/file namespace; no installed model is deleted. */
final class ReleaseReadinessVerification {
    static void require(boolean ok,String message){if(!ok)throw new AssertionError(message);}
    static void run(Instrumentation test,Bundle options){
        Bundle result=new Bundle();JSONObject report=new JSONObject();Context real=test.getTargetContext();
        File root=new File(real.getExternalFilesDir(null),"qa-release");root.mkdirs();
        Context isolated=new ContextWrapper(real){
            @Override public File getExternalFilesDir(String type){return root;}
            @Override public SharedPreferences getSharedPreferences(String name,int mode){return real.getSharedPreferences("qa-release-"+name,mode);}
        };
        try{
            require(!ModelStore.active&&!CaptionState.S.running,"Stop active capture/download before verification");
            if("soak".equals(options.getString("mode"))){soak(test,options,report);}
            else {
                compatibility(report);report.put("chipsetCatalog",ChipsetCatalogVerification.run(real));report.put("deviceModelJson",DeviceModelCatalogVerification.run(real));backlog(report);journal(isolated,report);files(isolated,root,report);
                JSONObject diagnostic=new JSONObject(Diagnostics.export(real));
                require(diagnostic.length()==22,"Diagnostics allowlist changed: "+diagnostic.length());
                String[] forbidden={"transcript","audio","ip","serial","fingerprint","tree","account","logs"};
                for(String key:forbidden)require(!diagnostic.has(key),"Private field in diagnostics: "+key);
                report.put("diagnosticsAllowlist",true);
                for(String tag:AppLanguage.TAGS){if(tag.isEmpty())continue;android.content.res.Configuration cfg=new android.content.res.Configuration(real.getResources().getConfiguration());cfg.setLocale(Locale.forLanguageTag(tag));Context locale=real.createConfigurationContext(cfg);require(!locale.getString(R.string.setup_title).isEmpty(),"Missing setup locale: "+tag);require(locale.getString(R.string.download_summary,"DOWNLOAD","SPACE").contains("SPACE"),"Broken placeholders: "+tag);}
                report.put("interfaceLocales",10);
                ModelManagementTest.networkChecks(root,report);
            }
            report.put("passed",true);
        }catch(Throwable e){try{report.put("passed",false).put("error",android.util.Log.getStackTraceString(e));}catch(Exception ignored){}}
        finally{DownloadJournal.state(isolated,false);DownloadJournal.clear(isolated);ModelStore.cancel=false;try{ModelStore.erase(real,root,real.getExternalFilesDir(null));}catch(Exception ignored){}}
        try{Files.write(new File(real.getExternalFilesDir(null),"release-"+options.getString("mode","readiness")+".json").toPath(),report.toString(2).getBytes(StandardCharsets.UTF_8));}catch(Exception ignored){}
        result.putString("stream",report.toString());test.finish(report.optBoolean("passed")?0:1,result);
    }
    private static void compatibility(JSONObject report)throws Exception{
        DeviceProfile s25=new DeviceProfile(36,new String[]{"arm64-v8a"},"SM8750",12_000_000_000L,false);
        DeviceProfile mid=new DeviceProfile(34,new String[]{"arm64-v8a"},"MT6983",6_000_000_000L,false);
        DeviceProfile low=new DeviceProfile(29,new String[]{"arm64-v8a"},"",3_000_000_000L,true);
        DeviceProfile x86=new DeviceProfile(36,new String[]{"x86_64"},"SM8750",16_000_000_000L,false);
        require(ModelCompatibility.supports(s25,"en","ja",QwenModel.ID)==BuildConfig.NPU_BUILD,"Build-specific Qwen eligibility incorrect");
        require(!ModelCompatibility.supports(mid,"en","ja","opus"),"Unsupported Japanese target accepted");
        require(ModelCompatibility.supports(mid,"ja","en","opus"),"Whisper English pivot missing");
        require(ModelCompatibility.supports(low,"es","zh","opus"),"Low RAM pivot missing");
        require(!ModelCompatibility.supports(x86,"en","zh","opus"),"x86 incorrectly allowed");
        require(!QwenModel.BALANCED.isSupported(mid),"Qwen allowed on wrong SoC");
        require(ModelCompatibility.supports(mid,"yue","zh","opus"),"Cantonese transcription missing");
        require(!ModelCompatibility.supports(mid,"yue","en","opus"),"Unsupported Cantonese pivot accepted");
        require(!ModelCompatibility.supports(low,"en","zh",QwenModel.ID),"Low RAM Qwen accepted");
        require(ModelCompatibility.forSelection(mid,"en","zh",QwenModel.ID).equals(QwenModel.HYMT2.id),"Portable direct recommendation did not recover");
        require(ModelCompatibility.supports(mid,"en","ja",QwenModel.HYMT2.id),"Portable Japanese target missing");
        require(ModelCompatibility.supports(mid,"ko","zh",QwenModel.HYMT2.id)&&!ModelCompatibility.bridge("ko","zh",QwenModel.HYMT2.id),"Korean direct route missing");
        require(!ModelCompatibility.supports(low,"en","zh",QwenModel.HYMT2.id),"Large CPU model accepted on low-memory device");
        require(!ModelCompatibility.supports(mid,"en","sv",QwenModel.HYMT2.id),"Unsupported HY-MT2 target offered");
        require(mid.recommendedModel("ja")==NpuModel.SENSEVOICE_CPU&&mid.recommendedModel("ko")==NpuModel.SENSEVOICE_CPU,"Direct CJK speech recommendation missing");
        require(mid.recommendedEnglishBridge("ja").isWhisper(),"English pivot lost its compatible speech model");
        require(ModelCompatibility.recommended(s25,"ja","zh").equals(BuildConfig.NPU_BUILD?QwenModel.GEMMA4.id:QwenModel.HYMT2.id),"Measured direct model recommendation lost");
        require(ModelCompatibility.forSelection(s25,"en","zh",QwenModel.ID).equals(BuildConfig.NPU_BUILD?QwenModel.ID:QwenModel.HYMT2.id),"Explicit compatible selection was overwritten");
        report.put("deviceLanguageRoutes",14);
    }
    private static void backlog(JSONObject report)throws Exception{
        TranslationBacklog queue=new TranslationBacklog();
        for(int i=0;i<8;i++)require(queue.offer(i,"draft",1,1,1000+i),"Queue rejected below limit");
        require(!queue.offer(9,"overflow",1,1,2000),"Queue grew beyond limit");
        require(queue.offer(0,"final",2,1,3000),"Revision rejected at capacity");
        TranslationBacklog.Request first=queue.poll();require(first.id==0&&first.revision==2&&first.queuedAt==1000,"Revision changed ordering or deadline");
        queue.offer(0,"newer",3,1,4000);require(queue.superseded(first),"Obsolete draft was not canceled");
        require(TranslationBacklog.MAX_WAIT_MS==15000,"Queue age limit changed");queue.clear();require(queue.size()==0,"Clear failed");report.put("boundedQueueAndDraftCancellation",true);
    }
    private static void journal(Context c,JSONObject report)throws Exception{
        DownloadJournal.clear(c);DownloadJournal.enqueue(c,new String[]{QwenModel.ID,"sensevoice"},0,false,null);
        require(DownloadJournal.jobs(c).length()==2,"Jobs not persisted");
        DownloadJournal.enqueue(c,new String[]{QwenModel.ID},2,true,null);
        JSONObject job=DownloadJournal.first(c);require(job.getInt("source")==2&&job.getBoolean("metered")&&DownloadJournal.jobs(c).length()==2,"Resume policy not updated");
        DownloadJournal.state(c,true);require(DownloadJournal.running(c),"Active job marker missing");DownloadJournal.state(c,false);
        require(DownloadJournal.hasJobs(c)&&!DownloadJournal.running(c),"Paused queue lost");
        DownloadJournal.completed(c,QwenModel.ID);require(DownloadJournal.jobs(c).length()==1,"Completion erased other jobs");
        boolean bad=false;try{DownloadJournal.enqueue(c,new String[]{"../unknown"},0,false,null);}catch(IllegalArgumentException e){bad=true;}require(bad,"Unknown model accepted");
        DownloadJournal.clear(c);report.put("durableQueuePauseResumeDedup",true);
    }
    private static void files(Context c,File root,JSONObject report)throws Exception{
        File work=new File(root,"model-downloads/"+QwenModel.ID),old=new File(work,"previous");old.mkdirs();Files.write(new File(old,"sentinel").toPath(),"original".getBytes(StandardCharsets.UTF_8));
        ModelStore.recoverPrevious(c,QwenModel.ID);require(new File(QwenModel.BALANCED.modelRoot(c),"sentinel").isFile(),"Interrupted atomic install not restored");
        java.lang.reflect.Method extract=ModelStore.class.getDeclaredMethod("extract",InputStream.class,File.class,long.class,JSONObject.class);extract.setAccessible(true);
        byte[] data="licensed model fixture".getBytes(StandardCharsets.UTF_8);StringBuilder sha=new StringBuilder();for(byte b:MessageDigest.getInstance("SHA-256").digest(data))sha.append(String.format(Locale.ROOT,"%02x",b&255));
        JSONObject spec=new JSONObject().put("name","fixture.bin").put("size",data.length).put("sha256",sha.toString());
        File target=new File(root,"fixture.bin");extract.invoke(null,new ByteArrayInputStream(data),target,(long)data.length,spec);require(Arrays.equals(data,Files.readAllBytes(target.toPath())),"Valid import rejected");
        data[0]^=1;boolean rejected=false;try{extract.invoke(null,new ByteArrayInputStream(data),target,(long)data.length,spec);}catch(java.lang.reflect.InvocationTargetException e){rejected=e.getCause() instanceof IOException;}require(rejected,"Same-size corrupt import accepted");
        boolean escape=false;try{ModelStore.erase(c,new File(root,"../outside"),root);}catch(IOException e){escape=true;}require(escape,"Unsafe delete accepted");
        File rawModel=new File(root,"model.litertlm"),rawArchive=new File(root,"raw.part"),rawStage=new File(root,"raw-stage");rawStage.mkdirs();
        byte[] modelBytes="Tiny test model".getBytes(StandardCharsets.UTF_8);Files.write(rawArchive.toPath(),modelBytes);
        StringBuilder rawHash=new StringBuilder();for(byte value:MessageDigest.getInstance("SHA-256").digest(modelBytes))rawHash.append(String.format(java.util.Locale.ROOT,"%02x",value&255));String digest=rawHash.toString();
        JSONObject rawSpec=new JSONObject().put("download",new JSONObject().put("format","raw"))
            .put("files",new JSONArray().put(new JSONObject().put("name",rawModel.getName()).put("size",modelBytes.length).put("sha256",digest)));
        ModelStore.unpackTextArchive(rawArchive,rawStage,rawSpec);
        require(Arrays.equals(modelBytes,Files.readAllBytes(new File(rawStage,rawModel.getName()).toPath())),"Raw model installation corrupted bytes");
        modelBytes[0]^=1;Files.write(rawArchive.toPath(),modelBytes);boolean rawRejected=false;
        try{ModelStore.unpackTextArchive(rawArchive,rawStage,rawSpec);}catch(IOException expected){rawRejected=true;}
        require(rawRejected,"Corrupt raw model accepted");
        rawSpec.getJSONArray("files").put(new JSONObject().put("name","second.bin").put("size",1).put("sha256",digest));rawRejected=false;
        try{ModelStore.unpackTextArchive(rawArchive,rawStage,rawSpec);}catch(IOException expected){rawRejected=true;}
        require(rawRejected,"Multi-file raw manifest accepted");report.put("rawModelInstallAndIntegrity",true);
        report.put("atomicRecovery",true).put("importHashRejectsCorruption",true).put("pathContainment",true);
    }
    private static void soak(Instrumentation test,Bundle options,JSONObject report)throws Exception{
        Context c=test.getTargetContext();LocalEngine engine=LocalEngine.get(c);
        String originalText=engine.translationChoice(),originalSpeech=engine.speechChoice();
        PowerManager.WakeLock wake=c.getSystemService(PowerManager.class).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK,"instant:soak-test");wake.acquire(65*60000L);
        try{
        String textChoice=options.getString("textModel",originalText);
        test.runOnMainSync(()->require(engine.selectTranslationChoice(textChoice),"Soak model selection rejected"));engine.prepare();long deadline=SystemClock.elapsedRealtime()+180000;
        while(!CaptionState.S.ready&&SystemClock.elapsedRealtime()<deadline){Thread.sleep(100);if(!CaptionState.S.preparing&&!CaptionState.S.ready)throw new IllegalStateException(CaptionState.S.status);}
        require(CaptionState.S.ready,"Models not ready");byte[] wave;try(InputStream in=test.getContext().getAssets().open("english.wav")){wave=in.readAllBytes();}
        int pos=12,start=-1,length=0;while(pos+8<=wave.length){int n=(wave[pos+4]&255)|((wave[pos+5]&255)<<8)|((wave[pos+6]&255)<<16)|((wave[pos+7]&255)<<24);if(new String(wave,pos,4,StandardCharsets.US_ASCII).equals("data")){start=pos+8;length=n;break;}pos+=8+n+(n&1);}require(start>0,"Fixture missing PCM");
        short[] audio=new short[length/2];for(int i=0;i<audio.length;i++)audio[i]=(short)((wave[start+2*i]&255)|(wave[start+2*i+1]<<8));
        int minutes=Math.min(60,Math.max(1,Integer.parseInt(options.getString("minutes","30"))));long begin=SystemClock.elapsedRealtime(),end=begin+minutes*60000L;JSONArray iterations=new JSONArray();long peakHeap=0;
        boolean uncached="true".equals(options.getString("uncached","false"));
        report.put("translationCacheClearedPerIteration",uncached);
        report.put("version",BuildConfig.VERSION_NAME).put("batteryBefore",battery(c)).put("iterations",iterations).put("speechModel",engine.recognitionBackend()).put("textModel",engine.translationChoice());
        long peakPss=0;int screenOffIterations=0,backgroundIterations=0;
        while(SystemClock.elapsedRealtime()<end){
            if(uncached)clearTranslationCache(engine);
            long t=SystemClock.elapsedRealtime();JSONObject stream=NpuBenchmark.stream(test,engine,audio,true);
            String asr=stream.getString("english");int[] errors=NpuBenchmark.wer("Hello everyone welcome to our meeting we will discuss the new project today please speak slowly thank you for your help",asr);
            require(errors[0]/(double)errors[1]<=0.25,"ASR drift during soak: "+asr);
            require(!stream.getString("chinese").trim().isEmpty(),"Empty translation during soak");
            require(engine.translationsIdle()&&CaptionState.S.skippedSegments==0,"Translation queue failed to drain");
            int heat=RuntimeHealth.thermal(c);require(heat<PowerManager.THERMAL_STATUS_CRITICAL,"Critical thermal status");
            long heap=Runtime.getRuntime().totalMemory()-Runtime.getRuntime().freeMemory();peakHeap=Math.max(peakHeap,heap);
            Debug.MemoryInfo memory=new Debug.MemoryInfo();Debug.getMemoryInfo(memory);peakPss=Math.max(peakPss,memory.getTotalPss());
            boolean screenOff=!c.getSystemService(PowerManager.class).isInteractive(),background=BaseActivity.visibleActivities==0;
            if(screenOff)screenOffIterations++;if(background)backgroundIterations++;
            iterations.put(new JSONObject().put("firstSourceMs",stream.getLong("firstEnglishMs")).put("firstSubtitleMs",stream.getLong("firstChineseMs")).put("wordErrors",errors[0]).put("thermal",heat).put("availableMemory",RuntimeHealth.availableMemory(c)).put("pssKb",memory.getTotalPss()).put("nativeHeap",Debug.getNativeHeapAllocatedSize()).put("screenOff",screenOff).put("background",background));
            report.put("elapsedMs",SystemClock.elapsedRealtime()-begin).put("peakPssKb",peakPss).put("peakJavaHeap",peakHeap).put("screenOffIterations",screenOffIterations).put("backgroundIterations",backgroundIterations).put("batteryAfter",battery(c));
            RobustnessVerification.write(c,"release-soak-progress.json",report);
            long wait=15000-(SystemClock.elapsedRealtime()-t);if(wait>0)Thread.sleep(Math.min(wait,15000));
        }
        report.put("elapsedMs",SystemClock.elapsedRealtime()-begin).put("iterations",iterations).put("peakJavaHeap",peakHeap).put("speechModel",engine.recognitionBackend()).put("textModel",engine.translationChoice());
        }finally{try{RobustnessVerification.restore(test,engine,originalText,originalSpeech);}finally{if(wake.isHeld())wake.release();}}
    }
    private static JSONObject battery(Context c)throws Exception{
        Intent b=c.registerReceiver(null,new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
        return b==null?new JSONObject():new JSONObject().put("level",b.getIntExtra(BatteryManager.EXTRA_LEVEL,-1)).put("scale",b.getIntExtra(BatteryManager.EXTRA_SCALE,-1)).put("temperatureTenthsC",b.getIntExtra(BatteryManager.EXTRA_TEMPERATURE,-1)).put("plugged",b.getIntExtra(BatteryManager.EXTRA_PLUGGED,-1));
    }
    private static void clearTranslationCache(LocalEngine engine)throws Exception{
        java.lang.reflect.Field routerField=LocalEngine.class.getDeclaredField("translator");routerField.setAccessible(true);
        java.lang.reflect.Field activeField=TranslationRouter.class.getDeclaredField("active");activeField.setAccessible(true);
        Object backend=activeField.get(routerField.get(engine));
        if(backend==null)throw new AssertionError("No active translator");
        if(backend instanceof QwenTranslator||backend instanceof MarianTranslator||backend instanceof LiteRtTranslator){
            java.lang.reflect.Field cache=backend.getClass().getDeclaredField("cache");cache.setAccessible(true);
            synchronized(backend){((java.util.Map<?,?>)cache.get(backend)).clear();}
        }else throw new AssertionError("Uncached soak requires a text translation model");
    }
}
