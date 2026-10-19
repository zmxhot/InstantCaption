package com.localcaption.instant;

import android.content.Context;
import com.k2fsa.sherpa.onnx.*;
import java.io.File;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;

/** Shared streaming facade for dedicated QNN and portable CPU speech models. */
public final class NpuRecognizer implements AutoCloseable {
    public final NpuModel model;
    public final String sourceLanguage;
    public final boolean englishBridge;
    private final OnlineRecognizer online;
    private final OfflineRecognizer offline;
    private boolean released;
    public NpuRecognizer(Context context,NpuModel selected) throws Exception {
        this(context,selected,"en",false);
    }
    public NpuRecognizer(Context context,NpuModel selected,String language,boolean englishBridge) throws Exception {
        model=selected;sourceLanguage=SpeechLanguage.fromId(language).id;this.englishBridge=englishBridge;
        if(!DeviceProfile.detect(context).supports(model))throw new IllegalStateException("此语音模型不适用于当前芯片");
        if(!model.supportsLanguage(sourceLanguage)||(englishBridge&&!model.supportsEnglishTranslation()))throw new IllegalStateException("此语音模型不支持当前语言或英语中转模式");
        File root=ModelStore.root(context,model);
        org.json.JSONObject manifest=ModelStore.spec(context,model);
        org.json.JSONArray files=manifest.getJSONArray("files");
        for(int i=0;i<files.length();i++){org.json.JSONObject f=files.getJSONObject(i);if(new File(root,f.getString("name")).length()!=f.getLong("size"))throw new IllegalStateException(model.label+" 文件缺失或不完整："+f.getString("name"));}
        if(!model.isNpu){
            OfflineModelConfig config=new OfflineModelConfig();config.setProvider("cpu");config.setNumThreads(Math.max(1,Math.min(3,Runtime.getRuntime().availableProcessors())));config.setDebug(false);
            if(model.isWhisper()){
                String size=model==NpuModel.WHISPER_TINY?"tiny":"base";
                OfflineWhisperModelConfig whisper=new OfflineWhisperModelConfig();
                whisper.setEncoder(new File(root,size+"-encoder.int8.onnx").getAbsolutePath());
                whisper.setDecoder(new File(root,size+"-decoder.int8.onnx").getAbsolutePath());
                whisper.setLanguage(sourceLanguage);whisper.setTask(englishBridge?"translate":"transcribe");
                // The multilingual models repeat/hallucinate with the English 1000-frame default.
                // Sherpa recommends 300 frames; keep the default for English transcription.
                if(!sourceLanguage.equals("en"))whisper.setTailPaddings(300);
                config.setWhisper(whisper);config.setTokens(new File(root,size+"-tokens.txt").getAbsolutePath());config.setModelType("whisper");
            }else{
                OfflineSenseVoiceModelConfig sense=new OfflineSenseVoiceModelConfig();sense.setModel(new File(root,"model.int8.onnx").getAbsolutePath());sense.setLanguage(sourceLanguage);sense.setUseInverseTextNormalization(true);
                config.setSenseVoice(sense);config.setTokens(new File(root,"tokens.txt").getAbsolutePath());config.setModelType("sense_voice");
            }
            OfflineRecognizerConfig recognizerConfig=new OfflineRecognizerConfig();recognizerConfig.setModelConfig(config);
            offline=new OfflineRecognizer(null,recognizerConfig);online=null;return;
        }
        String libs=context.getApplicationInfo().nativeLibraryDir;
        OnlineRecognizer.prependAdspLibraryPath(libs);
        QnnConfig qnn=new QnnConfig();qnn.setBackendLib(new File(libs,"libQnnHtp.so").getAbsolutePath());qnn.setSystemLib(new File(libs,"libQnnSystem.so").getAbsolutePath());
        if(model==NpuModel.SENSEVOICE){
            qnn.setContextBinary(new File(root,"model.bin").getAbsolutePath());
            OfflineSenseVoiceModelConfig sense=new OfflineSenseVoiceModelConfig();sense.setQnnConfig(qnn);sense.setLanguage(sourceLanguage);sense.setUseInverseTextNormalization(true);
            OfflineModelConfig config=new OfflineModelConfig();config.setSenseVoice(sense);config.setTokens(new File(root,"tokens.txt").getAbsolutePath());config.setProvider("qnn");config.setModelType("sense_voice");config.setDebug(true);
            OfflineRecognizerConfig recognizerConfig=new OfflineRecognizerConfig();recognizerConfig.setModelConfig(config);
            offline=new OfflineRecognizer(null,recognizerConfig);online=null;
        }else{
            OnlineTransducerModelConfig transducer=new OnlineTransducerModelConfig();
            if(model==NpuModel.NEMOTRON){
                // Android cannot execute libraries from emulated external storage.
                File compiled=ModelStore.compiledRoot(context,model);if(!compiled.isDirectory()&&!compiled.mkdirs())throw new java.io.IOException("无法创建 NPU 缓存");
                String[] parts={"encoder","decoder","joiner"};boolean cached=true;for(String part:parts)cached&=new File(compiled,part+".bin").length()>1024;
                if(!cached){
                    for(String part:parts){File source=new File(root,"lib"+part+".so"),dest=new File(compiled,"lib"+part+".so");if(dest.length()!=source.length())Files.copy(source.toPath(),dest.toPath(),StandardCopyOption.REPLACE_EXISTING);}
                    transducer.setEncoder(new File(compiled,"libencoder.so").getAbsolutePath());transducer.setDecoder(new File(compiled,"libdecoder.so").getAbsolutePath());transducer.setJoiner(new File(compiled,"libjoiner.so").getAbsolutePath());
                }
                qnn.setContextBinary(new File(compiled,"encoder.bin")+","+new File(compiled,"decoder.bin")+","+new File(compiled,"joiner.bin"));
            }else qnn.setContextBinary(new File(root,"encoder.bin")+","+new File(root,"decoder.bin")+","+new File(root,"joiner.bin"));
            transducer.setQnnConfig(qnn);
            OnlineModelConfig config=new OnlineModelConfig();config.setTransducer(transducer);config.setTokens(new File(root,"tokens.txt").getAbsolutePath());config.setProvider("qnn");config.setModelType(model==NpuModel.NEMOTRON?"nemo_transducer":"zipformer");config.setDebug(true);
            OnlineRecognizerConfig recognizerConfig=new OnlineRecognizerConfig();recognizerConfig.setModelConfig(config);recognizerConfig.setEnableEndpoint(false);
            online=new OnlineRecognizer(null,recognizerConfig);offline=null;
            if(model==NpuModel.NEMOTRON){File cache=ModelStore.compiledRoot(context,model);for(String part:new String[]{"encoder","decoder","joiner"}){if(new File(cache,part+".bin").length()>1024){File bootstrap=new File(cache,"lib"+part+".so");if(bootstrap.isFile())bootstrap.delete();}}}
        }
    }
    public synchronized Stream createStream(){if(released)throw new IllegalStateException("NPU 模型已关闭");return new Stream(online==null?null:online.createStream(""));}
    static String readText(java.io.InputStream in) throws java.io.IOException{java.io.ByteArrayOutputStream out=new java.io.ByteArrayOutputStream();byte[] buffer=new byte[8192];int n;while((n=in.read(buffer))!=-1)out.write(buffer,0,n);return out.toString(java.nio.charset.StandardCharsets.UTF_8.name());}
    private static String clean(String text){String result=text.replaceAll("<\\|[^|]*\\|>","").trim();return result.matches("(?i)^[\\[(](music|applause|laughter|silence|noise|音乐|掌声|笑声)[\\])]$")?"":result;}
    public final class Stream implements AutoCloseable {
        private final OnlineStream stream;
        private final float[] buffered=model.streaming?null:new float[model.segmentSamples];
        private int bufferedSamples,nextDecode=model.isWhisper()?32000:20000,lastDecodedSamples;
        private String hypothesis="";
        private boolean closed;
        private int lastDecodeMs;
        Stream(OnlineStream stream){this.stream=stream;}
        public void accept(short[] data,int length){synchronized(NpuRecognizer.this){check();float[] f=new float[length];for(int i=0;i<length;i++)f[i]=data[i]/32768f;
            if(stream!=null){stream.acceptWaveform(f,16000);drain();}
            else{if(bufferedSamples+length>buffered.length)throw new IllegalArgumentException("短句超过模型窗口");System.arraycopy(f,0,buffered,bufferedSamples,length);bufferedSamples+=length;if(bufferedSamples>=nextDecode){decodeWindow();nextDecode=bufferedSamples+(model.isWhisper()?24000:12000);}}
        }}
        private void check(){if(closed||released)throw new IllegalStateException("NPU 流已关闭");}
        private void drain(){long start=android.os.SystemClock.elapsedRealtime();boolean decoded=false;while(online.isReady(stream)){online.decode(stream);decoded=true;}if(decoded)lastDecodeMs=(int)(android.os.SystemClock.elapsedRealtime()-start);}
        private void decodeWindow(){if(bufferedSamples==0||lastDecodedSamples==bufferedSamples)return;long start=android.os.SystemClock.elapsedRealtime();OfflineStream s=offline.createStream();try{s.acceptWaveform(java.util.Arrays.copyOf(buffered,bufferedSamples),16000);offline.decode(s);hypothesis=clean(offline.getResult(s).getText());lastDecodedSamples=bufferedSamples;}finally{s.release();}lastDecodeMs=(int)(android.os.SystemClock.elapsedRealtime()-start);}
        public String text(){synchronized(NpuRecognizer.this){check();return stream==null?hypothesis:clean(online.getResult(stream).getText());}}
        public int lastDecodeMs(){return lastDecodeMs;}
        public void finish(){synchronized(NpuRecognizer.this){check();if(stream!=null){stream.acceptWaveform(new float[12800],16000);stream.inputFinished();drain();}else decodeWindow();}}
        @Override public void close(){synchronized(NpuRecognizer.this){if(!closed){closed=true;if(stream!=null)stream.release();}}}
    }
    public String recognize(short[] data){StringBuilder text=new StringBuilder();int window=model.streaming?data.length:model.segmentSamples;for(int start=0;start<data.length;start+=window){try(Stream s=createStream()){int end=Math.min(data.length,start+window);for(int p=start;p<end;p+=512){int n=Math.min(512,end-p);s.accept(java.util.Arrays.copyOfRange(data,p,p+n),n);}s.finish();text.append(s.text()).append(' ');}}return text.toString().trim();}
    @Override public synchronized void close(){if(!released){released=true;if(online!=null)online.release();if(offline!=null)offline.release();}}
}
