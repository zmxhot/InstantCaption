package com.localcaption.instant;

import android.app.Instrumentation;
import android.os.Bundle;
import android.os.SystemClock;
import org.json.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** Reproducible same-device benchmark of the actual subtitle pipeline. */
final class NpuBenchmark {
    static void switchModels(Instrumentation test){
        Bundle result=new Bundle();JSONObject report=new JSONObject();JSONArray runs=new JSONArray();
        try{
            LocalEngine engine=LocalEngine.get(test.getTargetContext());engine.prepare();waitReady();
            for(NpuModel model:new NpuModel[]{NpuModel.X_ASR,NpuModel.NEMOTRON,NpuModel.SENSEVOICE,NpuModel.ZIPFORMER,NpuModel.X_ASR}){
                long start=SystemClock.elapsedRealtime();test.runOnMainSync(()->engine.selectModel(model));waitReady();
                String text=engine.recognizeNpuBlocking(wave(test,"english.wav"));int[] errors=wer("Hello everyone welcome to our meeting we will discuss the new project today please speak slowly thank you for your help",text);if((double)errors[0]/errors[1]>0.25)throw new IllegalStateException(model.label+" 切换后识别异常："+text);
                if(!engine.selectedModel().id.equals(test.getTargetContext().getSharedPreferences("engine",0).getString("npuModel","")))throw new IllegalStateException("选择没有保存");
                JSONArray devices=new JSONArray();File[] descriptors=new File("/proc/self/fd").listFiles();if(descriptors!=null)for(File fd:descriptors){try{String link=android.system.Os.readlink(fd.getAbsolutePath());if(link.contains("fastrpc-cdsp"))devices.put(link);}catch(Exception ignored){}}
                if(devices.length()==0)throw new IllegalStateException("没有发现 NPU RPC 设备句柄");
                JSONObject run=new JSONObject().put("model",model.id).put("switchAndDecodeMs",SystemClock.elapsedRealtime()-start).put("english",text).put("npuRpcDevices",devices);runs.put(run);progress(test,run);
            }
            report.put("model","switch-verification").put("runs",runs).put("passed",true);write(test,report);result.putString("stream",report.toString(2));test.finish(0,result);
        }catch(Throwable e){result.putString("stream",runs+"\n"+e);test.finish(1,result);}
    }
    static void waitReady() throws Exception{long until=SystemClock.elapsedRealtime()+600000;while(!CaptionState.S.ready&&SystemClock.elapsedRealtime()<until){Thread.sleep(100);if(!CaptionState.S.preparing&&!CaptionState.S.ready)throw new IllegalStateException(CaptionState.S.status);}if(!CaptionState.S.ready)throw new IllegalStateException("模型未就绪");}
    static void run(Instrumentation test,Bundle options){
        JSONObject report=new JSONObject();Bundle result=new Bundle();
        try{
            LocalEngine engine=LocalEngine.get(test.getTargetContext());long load=SystemClock.elapsedRealtime();engine.prepare();
            long until=load+600000;while(!CaptionState.S.ready&&SystemClock.elapsedRealtime()<until){Thread.sleep(100);if(!CaptionState.S.preparing&&!CaptionState.S.ready)throw new IllegalStateException(CaptionState.S.status);}
            if(!CaptionState.S.ready)throw new IllegalStateException("模型加载超时");
            report.put("model",engine.selectedModel().id).put("engine",engine.engineName()).put("loadMs",SystemClock.elapsedRealtime()-load).put("streamingModel",engine.selectedModel().streaming);
            report.put("device",android.os.Build.MODEL).put("internetPermission",test.getTargetContext().checkSelfPermission(android.Manifest.permission.INTERNET)==android.content.pm.PackageManager.PERMISSION_GRANTED);
            String fixtureText;try(InputStream in=test.getContext().getAssets().open("benchmark/fixtures.json")){fixtureText=new String(in.readAllBytes(),StandardCharsets.UTF_8);}
            JSONArray fixtures=new JSONArray(fixtureText),runs=new JSONArray();int rawErrors=0,streamErrors=0,words=0;
            // Warm native execution and translator before measuring steady-state latency.
            engine.recognizeNpuBlocking(wave(test,"english.wav"));engine.translateBlocking("Hello everyone");
            int limit=options.containsKey("limit")?Integer.parseInt(options.getString("limit")):fixtures.length();
            for(int i=0;i<Math.min(limit,fixtures.length());i++){
                JSONObject fixture=fixtures.getJSONObject(i);short[] audio=wave(test,fixture.getString("asset"));String reference=fixture.getString("reference");
                long t=SystemClock.elapsedRealtime();String raw=engine.recognizeNpuBlocking(audio);long rawMs=SystemClock.elapsedRealtime()-t;
                JSONObject run=stream(test,engine,audio,true);int[] rawWer=wer(reference,raw),streamWer=wer(reference,run.getString("english"));
                run.put("asset",fixture.getString("asset")).put("reference",reference).put("rawEnglish",raw).put("rawDecodeMs",rawMs).put("rawErrors",rawWer[0]).put("words",rawWer[1]).put("streamErrors",streamWer[0]).put("streamWER",(double)streamWer[0]/streamWer[1]);
                runs.put(run);rawErrors+=rawWer[0];streamErrors+=streamWer[0];words+=rawWer[1];
                progress(test,new JSONObject().put("model",engine.selectedModel().id).put("sample",i+1).put("streamWER",run.getDouble("streamWER")).put("firstChineseMs",run.getLong("firstChineseMs")));
            }
            JSONArray latencyRepeats=new JSONArray();for(int i=0;i<3;i++)latencyRepeats.put(stream(test,engine,wave(test,"english.wav"),true));
            JSONArray negatives=new JSONArray();for(String name:new String[]{"silence","rain","clock","vacuum","keyboard","dog","engine"}){
                short[] audio=name.equals("silence")?new short[80000]:wave(test,"environment/"+name+".wav");JSONObject scene=stream(test,engine,audio,false);scene.put("scene",name);negatives.put(scene);if(scene.getInt("lines")!=0)throw new IllegalStateException("环境音误触发："+name);
            }
            report.put("fixtures",runs).put("latencyRepeats",latencyRepeats).put("negativeScenes",negatives).put("referenceWords",words).put("rawWordErrors",rawErrors).put("streamWordErrors",streamErrors).put("rawWER",(double)rawErrors/words).put("streamWER",(double)streamErrors/words).put("passed",true);
            report.put("limitations","Small English fixture set; LibriSpeech reading plus synthetic speech and one radio sample. WER measures English ASR, not Chinese translation quality. First-caption delay is from start of input audio and includes silence, VAD, ASR and local translation. 3 warm repeats; not a thermal/endurance test.");
            write(test,report);result.putString("stream",report.toString(2));test.finish(0,result);
        }catch(Throwable e){try{report.put("passed",false).put("error",e.toString());write(test,report);}catch(Exception ignored){}result.putString("stream",report.toString());test.finish(1,result);}
    }
    static JSONObject stream(Instrumentation test,LocalEngine engine,short[] audio,boolean paced) throws Exception{
        engine.cancelPending();test.runOnMainSync(()->CaptionState.S.clear());
        final long[] first={-1,-1};long start=SystemClock.elapsedRealtime();
        CaptionState.Observer observer=()->{for(CaptionState.Line line:CaptionState.S.lines){if(first[0]<0&&!line.en.isEmpty())first[0]=SystemClock.elapsedRealtime()-start;if(first[1]<0&&!line.zh.isEmpty())first[1]=SystemClock.elapsedRealtime()-start;}};
        CaptionState.S.observe(observer);long admitted;
        try(CaptionPipeline pipeline=new CaptionPipeline(engine,450)){
            for(int p=0;p<audio.length;p+=800){int n=Math.min(800,audio.length-p);pipeline.accept(Arrays.copyOfRange(audio,p,p+n),n);if(paced){long wait=start+(p+n)/16-SystemClock.elapsedRealtime();if(wait>0)Thread.sleep(wait);}}
            pipeline.finish();if(!pipeline.awaitIdle(120000))throw new IllegalStateException("识别队列超时");admitted=pipeline.admittedFrames();
            long deadline=SystemClock.elapsedRealtime()+15000;while(!engine.translationsIdle()&&SystemClock.elapsedRealtime()<deadline)Thread.sleep(20);test.runOnMainSync(()->{});
        }finally{CaptionState.S.unobserve(observer);}
        StringBuilder en=new StringBuilder(),zh=new StringBuilder();final int[] count={0};test.runOnMainSync(()->{count[0]=CaptionState.S.lines.size();for(CaptionState.Line l:CaptionState.S.lines){en.append(l.en).append(' ');zh.append(l.zh).append('\n');}});
        return new JSONObject().put("audioSeconds",audio.length/16000.0).put("elapsedMs",SystemClock.elapsedRealtime()-start).put("firstEnglishMs",first[0]).put("firstChineseMs",first[1]).put("english",en.toString().trim()).put("chinese",zh.toString().trim()).put("lines",count[0]).put("admittedFrames",admitted);
    }
    static short[] wave(Instrumentation test,String asset) throws Exception{
        byte[] all;try(InputStream in=test.getContext().getAssets().open(asset)){all=in.readAllBytes();}int p=12;
        while(p+8<=all.length){String name=new String(all,p,4,StandardCharsets.US_ASCII);int n=(all[p+4]&255)|((all[p+5]&255)<<8)|((all[p+6]&255)<<16)|((all[p+7]&255)<<24);p+=8;if(name.equals("data")){short[] pcm=new short[n/2];for(int i=0;i<pcm.length;i++)pcm[i]=(short)((all[p+2*i]&255)|(all[p+2*i+1]<<8));return pcm;}p+=n+(n&1);}throw new IOException("Missing WAV data");
    }
    static int[] wer(String reference,String text){String[] a=normalize(reference),b=normalize(text);int[][] d=new int[a.length+1][b.length+1];for(int i=0;i<=a.length;i++)d[i][0]=i;for(int j=0;j<=b.length;j++)d[0][j]=j;for(int i=1;i<=a.length;i++)for(int j=1;j<=b.length;j++)d[i][j]=Math.min(d[i-1][j]+1,Math.min(d[i][j-1]+1,d[i-1][j-1]+(a[i-1].equals(b[j-1])?0:1)));return new int[]{d[a.length][b.length],a.length};}
    static String[] normalize(String text){
        String s=text.toLowerCase(Locale.ROOT).replace('\u2019','\'');
        String[][] contractions={{"we're","we are"},{"we'll","we will"},{"it's","it is"},{"that's","that is"},{"i'm","i am"},{"don't","do not"},{"can't","can not"},{"won't","will not"}};
        for(String[] pair:contractions)s=s.replace(pair[0],pair[1]);
        java.util.regex.Matcher matcher=java.util.regex.Pattern.compile("\\b\\d+\\b").matcher(s);StringBuffer out=new StringBuffer();
        while(matcher.find()){String value=matcher.group();try{int n=Integer.parseInt(value);if(n<1000000)value=numberWords(n);}catch(NumberFormatException ignored){}matcher.appendReplacement(out,java.util.regex.Matcher.quoteReplacement(value));}matcher.appendTail(out);
        s=out.toString().replaceAll("[^a-z0-9 ]"," ").trim();return s.isEmpty()?new String[0]:s.split("\\s+");
    }
    static String numberWords(int n){String[] ones={"zero","one","two","three","four","five","six","seven","eight","nine","ten","eleven","twelve","thirteen","fourteen","fifteen","sixteen","seventeen","eighteen","nineteen"};String[] tens={"","","twenty","thirty","forty","fifty","sixty","seventy","eighty","ninety"};if(n<20)return ones[n];if(n<100)return tens[n/10]+(n%10==0?"":" "+ones[n%10]);if(n<1000)return ones[n/100]+" hundred"+(n%100==0?"":" "+numberWords(n%100));return numberWords(n/1000)+" thousand"+(n%1000==0?"":" "+numberWords(n%1000));}
    static void progress(Instrumentation test,JSONObject value){Bundle b=new Bundle();b.putString("stream",value+"\n");test.sendStatus(0,b);}
    static void write(Instrumentation test,JSONObject report) throws Exception{String id=report.optString("model","error");File dest=new File(test.getTargetContext().getExternalFilesDir(null),"benchmark-"+id+".json");try(FileOutputStream out=new FileOutputStream(dest)){out.write(report.toString(2).getBytes(StandardCharsets.UTF_8));}}
}
