package com.localcaption.instant;

import android.app.Instrumentation;
import android.content.Context;
import android.os.*;
import org.json.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;

/** Runs the production inference and streaming pipeline on the connected phone. */
public class OfflineTest extends Instrumentation {
    private Bundle options;
    @Override public void onCreate(Bundle args) { super.onCreate(args);DeviceModelCatalog.initialize(getTargetContext());options=args;start(); }
    @Override public void onStart(){new Thread(()->{String id=options.getString("model");if(id!=null)getTargetContext().getSharedPreferences("engine",0).edit().putString("npuModel",id).commit();if("translation-extension".equals(options.getString("mode")))TranslationExtensionVerification.run(this,options);else if("recovery-ui".equals(options.getString("mode")))RecoveryUiVerification.run(this,options);else if("robustness".equals(options.getString("mode")))RobustnessVerification.run(this,options);else if("upstream-runtime".equals(options.getString("mode")))UpstreamRuntimeVerification.run(this,options);else if("portable".equals(options.getString("mode")))PortableVerification.run(this);else if("readiness".equals(options.getString("mode"))||"soak".equals(options.getString("mode")))ReleaseReadinessVerification.run(this,options);else if("interface-languages".equals(options.getString("mode")))InterfaceLanguageVerification.run(this,options);else if("overlay-idle".equals(options.getString("mode")))OverlayIdleVerification.run(this,options);else if("overlay".equals(options.getString("mode")))OverlayVerification.run(this,options);else if("npu-options".equals(options.getString("mode")))NpuTranslationOptionsVerification.run(this,options);else if("qwen".equals(options.getString("mode")))QwenVerification.run(this,options);else if("model-choice".equals(options.getString("mode")))ModelChoiceVerification.run(this,options);else if("auto-recognition".equals(options.getString("mode")))AutoRecognitionVerification.run(this,options);else if("system-speech".equals(options.getString("mode")))SystemSpeechVerification.run(this,options);else if("whisper-diagnostics".equals(options.getString("mode")))WhisperDiagnostics.run(this,options);else if("device-language".equals(options.getString("mode")))DeviceLanguageVerification.run(this,options);else if("adaptive".equals(options.getString("mode")))AdaptiveTranslationVerification.run(this,options);else if("translation".equals(options.getString("mode")))TranslationVerification.run(this,options);else if("management".equals(options.getString("mode")))ModelManagementTest.run(this);else if("benchmark".equals(options.getString("mode")))NpuBenchmark.run(this,options);else if("switch".equals(options.getString("mode")))NpuBenchmark.switchModels(this);else if("playback".equals(options.getString("mode"))||"mic".equals(options.getString("mode")))verifyPlayback();else verify();},"offline-verification").start();}
    private static double wer(String reference,String hypothesis){
        String[] a=reference.toLowerCase(java.util.Locale.ROOT).replaceAll("[^a-z0-9 ]"," ").trim().split("\\s+"),b=hypothesis.toLowerCase(java.util.Locale.ROOT).replaceAll("[^a-z0-9 ]"," ").trim().split("\\s+");
        int[][] d=new int[a.length+1][b.length+1];for(int i=0;i<=a.length;i++)d[i][0]=i;for(int j=0;j<=b.length;j++)d[0][j]=j;
        for(int i=1;i<=a.length;i++)for(int j=1;j<=b.length;j++)d[i][j]=Math.min(d[i-1][j]+1,Math.min(d[i][j-1]+1,d[i-1][j-1]+(a[i-1].equals(b[j-1])?0:1)));
        return (double)d[a.length][b.length]/a.length;
    }
    private void verifyPlayback() {
        Bundle result=new Bundle();JSONObject report=new JSONObject();android.media.MediaPlayer player=new android.media.MediaPlayer();
        try {
            Context c=getTargetContext();c.startActivity(new android.content.Intent(c,MainActivity.class).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK));
            long until=SystemClock.elapsedRealtime()+90000;
            while(!CaptionState.S.running&&SystemClock.elapsedRealtime()<until)Thread.sleep(100);
            String source="mic".equals(options.getString("mode"))?"麦克风":"手机声音";
            require(CaptionState.S.running&&CaptionState.S.source.equals(source),"未启动声音捕获："+source);
            report.put("source",source);
            Thread.sleep(800);
            android.content.res.AssetFileDescriptor noise=getContext().getAssets().openFd("environment/rain.wav");
            player.setAudioAttributes(new android.media.AudioAttributes.Builder().setUsage(android.media.AudioAttributes.USAGE_MEDIA).setContentType(android.media.AudioAttributes.CONTENT_TYPE_MUSIC).setAllowedCapturePolicy(android.media.AudioAttributes.ALLOW_CAPTURE_BY_ALL).build());
            player.setDataSource(noise.getFileDescriptor(),noise.getStartOffset(),noise.getLength());noise.close();player.prepare();player.start();
            while(player.isPlaying())Thread.sleep(100);
            Thread.sleep(500);final int[] noiseLines={0};runOnMainSync(()->noiseLines[0]=CaptionState.S.lines.size());
            require(noiseLines[0]==0,"实际手机环境音生成了字幕");report.put("rainOnlyLines",noiseLines[0]);
            player.reset();
            android.content.res.AssetFileDescriptor fd=getContext().getAssets().openFd("english.wav");
            player.setAudioAttributes(new android.media.AudioAttributes.Builder().setUsage(android.media.AudioAttributes.USAGE_MEDIA).setContentType(android.media.AudioAttributes.CONTENT_TYPE_SPEECH).setAllowedCapturePolicy(android.media.AudioAttributes.ALLOW_CAPTURE_BY_ALL).build());
            player.setDataSource(fd.getFileDescriptor(),fd.getStartOffset(),fd.getLength());fd.close();player.prepare();
            long start=SystemClock.elapsedRealtime();player.start();long firstZh=-1;
            while(player.isPlaying()||SystemClock.elapsedRealtime()-start<14000){final String[] transcript={""};runOnMainSync(()->transcript[0]=CaptionState.S.transcript());if(firstZh<0&&transcript[0].matches("(?s).*[\\p{IsHan}].*"))firstZh=SystemClock.elapsedRealtime()-start;Thread.sleep(100);}
            Thread.sleep(1200);final String[] transcript={""};runOnMainSync(()->transcript[0]=CaptionState.S.transcript());
            require(transcript[0].toLowerCase(java.util.Locale.ROOT).contains("welcome"),"实际手机音频未捕获 welcome: "+transcript[0]);require(firstZh>=0,"手机声音未出现中文字幕");
            report.put("passed",true).put("firstChineseFromPlaybackStartMs",firstZh).put("transcript",transcript[0]);
            try(FileOutputStream out=new FileOutputStream(new File(c.getExternalFilesDir(null),"playback-verification.json"))){out.write(report.toString(2).getBytes(StandardCharsets.UTF_8));}
            int hold=Math.max(0,Math.min(120,Integer.parseInt(options.getString("holdSeconds","0"))));long holdUntil=SystemClock.elapsedRealtime()+hold*1000L;while(CaptionState.S.running&&SystemClock.elapsedRealtime()<holdUntil)Thread.sleep(100);c.stopService(new android.content.Intent(c,CaptionService.class));result.putString("stream",report.toString(2));finish(0,result);
        }catch(Throwable e){result.putString("stream",e.toString());finish(1,result);}finally{player.release();}
    }
    private void verify() {
        Bundle result=new Bundle();JSONObject report=new JSONObject();
        try {
            Context c=getTargetContext();LocalEngine engine=LocalEngine.get(c);engine.prepare();
            long until=SystemClock.elapsedRealtime()+300000;
            while(!CaptionState.S.ready&&SystemClock.elapsedRealtime()<until){Thread.sleep(100);if(!CaptionState.S.preparing&&!CaptionState.S.ready)throw new IllegalStateException(CaptionState.S.status);}
            require(CaptionState.S.ready,"模型未就绪: "+CaptionState.S.status);
            report.put("engine",engine.engineName());
            report.put("internetPermission",c.checkSelfPermission(android.Manifest.permission.INTERNET)==android.content.pm.PackageManager.PERMISSION_GRANTED);
            JSONArray translations=new JSONArray();ArrayList<Long> ms=new ArrayList<>();
            String[] inputs={"Hello everyone, welcome to our meeting.","Please speak slowly.","We will discuss the new project today.","Thank you for your help.","Where is the nearest train station?","The meeting starts at nine o'clock.","Can you help me find the hotel?","I would like a glass of water."};
            for(String en:inputs){long t=SystemClock.elapsedRealtime();String zh=engine.translateBlocking(en);long dt=SystemClock.elapsedRealtime()-t;ms.add(dt);require(zh.matches("(?s).*[\\p{IsHan}].*"),"翻译未返回中文");translations.put(new JSONObject().put("en",en).put("zh",zh).put("ms",dt));}
            Collections.sort(ms);report.put("translations",translations);report.put("translationMedianMs",ms.get(ms.size()/2));report.put("translationMaxMs",ms.get(ms.size()-1));
            byte[] pcm=waveData();StringBuilder recognized=new StringBuilder();long cpu=SystemClock.elapsedRealtime();
            recognized.append(engine.recognizeNpuBlocking(shorts(pcm)));
            long decodeMs=SystemClock.elapsedRealtime()-cpu;double seconds=pcm.length/32000.0;
            require(recognized.toString().toLowerCase(java.util.Locale.ROOT).contains("welcome"),"语音测试未识别 welcome: "+recognized);
            report.put("recognized",recognized.toString());report.put("audioSeconds",seconds);report.put("decodeMs",decodeMs);report.put("recognitionRealTimeFactor",decodeMs/(seconds*1000));
            report.put("speechOnlyFilter",verifySpeechOnly(engine,pcm));
            engine.cancelPending();CaptionState.MAIN.post(()->CaptionState.S.clear());
            long start=SystemClock.elapsedRealtime();long firstEn=-1,firstZh=-1;int pos=0;
            try(CaptionPipeline pipeline=new CaptionPipeline(engine,450)){
                short[] b=new short[1600];
                while(pos<pcm.length){int n=Math.min(b.length,(pcm.length-pos)/2);if(n==0)break;for(int i=0;i<n;i++){b[i]=(short)((pcm[pos]&255)|(pcm[pos+1]<<8));pos+=2;}pipeline.accept(b,n);
                    long due=start+pos/32;long wait=due-SystemClock.elapsedRealtime();if(wait>0)Thread.sleep(wait);
                    final String[] latest={"",""};runOnMainSync(()->{if(!CaptionState.S.lines.isEmpty()){CaptionState.Line l=CaptionState.S.lines.get(0);latest[0]=l.en;latest[1]=l.zh;}});
                    if(firstEn<0&&!latest[0].isEmpty())firstEn=SystemClock.elapsedRealtime()-start;
                    if(firstZh<0&&!latest[1].isEmpty())firstZh=SystemClock.elapsedRealtime()-start;
                }
                pipeline.finish();require(pipeline.awaitIdle(120000),"ASR 队列未完成");runOnMainSync(()->{});require(pipeline.droppedSegments()==0,"队列丢弃语音");
                report.put("vadAverageFrameMs",pipeline.vadInferenceMs());
            }
            Thread.sleep(1500);final String[] transcript={""};runOnMainSync(()->transcript[0]=CaptionState.S.transcript());
            require(transcript[0].matches("(?s).*[\\p{IsHan}].*"),"流水线没有中文字幕");
            report.put("firstEnglishFromAudioStartMs",firstEn);report.put("firstChineseFromAudioStartMs",firstZh);report.put("streamingTranscript",transcript[0]);report.put("passed",true);
            writeReport(report);result.putString("stream",report.toString(2));finish(0,result);
        }catch(Throwable e){try{report.put("passed",false);report.put("error",e.toString());writeReport(report);}catch(Exception ignored){}result.putString("stream",report.toString());finish(1,result);}
    }
    private byte[] waveData() throws Exception {
        return waveData("english.wav");
    }
    private byte[] waveData(String asset) throws Exception {
        byte[] all;try(InputStream in=getContext().getAssets().open(asset)){all=in.readAllBytes();}
        int p=12;while(p+8<=all.length){String chunk=new String(all,p,4,StandardCharsets.US_ASCII);int n=(all[p+4]&255)|((all[p+5]&255)<<8)|((all[p+6]&255)<<16)|((all[p+7]&255)<<24);p+=8;if(chunk.equals("data"))return java.util.Arrays.copyOfRange(all,p,p+n);p+=n+(n&1);}throw new IOException("WAV data chunk missing");
    }
    private JSONArray verifySpeechOnly(LocalEngine engine,byte[] speechBytes) throws Exception {
        JSONArray scenes=new JSONArray();
        String[] names={"silence","fan_noise","impacts","tone","rain","clock","vacuum","keyboard","dog","engine"};
        for(String name:names){
            short[] data;
            if(name.equals("silence")) data=new short[16000*6];
            else if(name.equals("fan_noise")){data=new short[16000*6];java.util.Random random=new java.util.Random(12);double low=0;for(int i=0;i<data.length;i++){low=0.93*low+0.07*random.nextGaussian()*7000;data[i]=(short)low;}}
            else if(name.equals("impacts")){data=new short[16000*6];java.util.Random random=new java.util.Random(8);for(int start=0;start<data.length-400;start+=8000)for(int j=0;j<320;j++)data[start+j]=(short)(random.nextGaussian()*10000*Math.exp(-j/60.0));}
            else if(name.equals("tone")){data=new short[16000*6];for(int i=0;i<data.length;i++)data[i]=(short)(10000*Math.sin(2*Math.PI*440*i/16000));}
            else data=shorts(waveData("environment/"+name+".wav"));
            JSONObject scene=scene(engine,name,data);scenes.put(scene);require(scene.getInt("lines")==0,"非人声生成了字幕: "+scene);
        }
        short[] speech=shorts(speechBytes),noise=shorts(waveData("environment/rain.wav"));
        double speechEnergy=0,noiseEnergy=0;for(short s:speech)speechEnergy+=(double)s*s;for(short s:noise)noiseEnergy+=(double)s*s;
        double scale=Math.sqrt(speechEnergy/speech.length/(noiseEnergy/noise.length))/Math.sqrt(10); // +10 dB speech-to-noise ratio.
        short[] mixed=new short[speech.length];for(int i=0;i<mixed.length;i++)mixed[i]=(short)Math.max(-32768,Math.min(32767,Math.round(speech[i]+noise[i%noise.length]*scale)));
        JSONObject noisy=scene(engine,"speech_with_rain_10db",mixed);scenes.put(noisy);require(noisy.getString("transcript").toLowerCase(java.util.Locale.ROOT).contains("welcome"),"有背景雨声时漏掉人声: "+noisy);
        short[] quiet=speech.clone();for(int i=0;i<quiet.length;i++)quiet[i]=(short)(quiet[i]/4);
        JSONObject low=scene(engine,"quiet_speech_minus12db",quiet);scenes.put(low);require(low.getString("transcript").toLowerCase(java.util.Locale.ROOT).contains("welcome"),"轻声测试漏掉人声: "+low);
        return scenes;
    }
    private JSONObject scene(LocalEngine engine,String name,short[] data) throws Exception {
        return scene(engine,name,data,false);
    }
    private JSONObject scene(LocalEngine engine,String name,short[] data,boolean paced) throws Exception {
        engine.cancelPending();runOnMainSync(()->CaptionState.S.clear());
        JSONObject report=new JSONObject().put("scene",name);
        try(CaptionPipeline pipeline=new CaptionPipeline(engine,450)){
            long start=SystemClock.elapsedRealtime();
            for(int pos=0;pos<data.length;pos+=800){int n=Math.min(800,data.length-pos);short[] buffer=java.util.Arrays.copyOfRange(data,pos,pos+n);pipeline.accept(buffer,n);if(paced){long left=start+(pos+n)/16-SystemClock.elapsedRealtime();if(left>0)Thread.sleep(left);}}
            pipeline.finish();require(pipeline.awaitIdle(120000),"ASR 队列未完成");runOnMainSync(()->{});require(pipeline.droppedSegments()==0,"队列丢弃语音");report.put("admittedFrames",pipeline.admittedFrames());report.put("vadAverageFrameMs",pipeline.vadInferenceMs());
        }
        Thread.sleep(400);runOnMainSync(()->{try{report.put("lines",CaptionState.S.lines.size());report.put("transcript",CaptionState.S.transcript());}catch(Exception e){throw new RuntimeException(e);}});
        return report;
    }
    private static short[] shorts(byte[] pcm){short[] out=new short[pcm.length/2];for(int i=0;i<out.length;i++)out[i]=(short)((pcm[i*2]&255)|(pcm[i*2+1]<<8));return out;}
    private void writeReport(JSONObject report) throws Exception {try(FileOutputStream out=new FileOutputStream(new File(getTargetContext().getExternalFilesDir(null),"verification.json"))){out.write(report.toString(2).getBytes(StandardCharsets.UTF_8));}}
    private static void require(boolean ok,String why){if(!ok)throw new AssertionError(why);}
}
