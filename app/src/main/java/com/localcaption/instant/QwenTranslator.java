package com.localcaption.instant;

import android.content.Context;
import android.os.SystemClock;
import com.geniex.sdk.GenieXSdk;
import com.geniex.sdk.jni.Llm;
import com.geniex.sdk.bean.*;
import java.io.File;
import java.util.LinkedHashMap;
import java.util.concurrent.CancellationException;
import java.util.function.BooleanSupplier;

/** QAIRT uses the Hexagon NPU. No remote inference or CPU LLM fallback. */
final class QwenTranslator implements TranslationRouter.Backend {
    private static boolean registered;
    private final Llm llm=new Llm();
    private long handle;
    private final String source,target,modelInstruction;
    private final LinkedHashMap<String,String> cache=new LinkedHashMap<>();
    volatile long firstTokenMs,totalMs;
    volatile ProfilingData lastProfile;

    private static synchronized void register(Context c)throws Exception{
        if(registered)return;
        String libs=c.getApplicationInfo().nativeLibraryDir;
        android.system.Os.setenv("ADSP_LIBRARY_PATH",libs+";/vendor/lib/rfsa/adsp;/vendor/dsp",true);
        android.system.Os.setenv("GENIEX_QNN_HTP_PATH",libs,true);
        GenieXSdk sdk=GenieXSdk.Companion.getInstance();
        int result=sdk.registerPlugin(libs+"/libgeniex_plugin_qairt.so");
        if(result!=0)throw new IllegalStateException("Qwen NPU runtime: "+result);
        android.util.Log.i("QwenTranslator","NPU backend=qairt, plugin="+sdk.getPluginVersion("qairt"));
        registered=true;
    }
    QwenTranslator(Context c,String source,String target)throws Exception{this(c,source,target,QwenModel.BALANCED);}
    QwenTranslator(Context c,String source,String target,QwenModel model)throws Exception{
        if(!model.isSupported(c))throw new IllegalStateException(model.compatibility(c));
        if(!model.isInstalled(c))throw new TranslationRouter.ModelMissingException("请到文本翻译页面下载 "+model.label+" NPU 模型");
        RuntimeHealth.beforeLoad(c,model);
        this.source=SpeechLanguage.fromId(source).en;
        this.target="zh".equals(target)?"Simplified Chinese":SpeechLanguage.fromId(target).en;
        modelInstruction=model==QwenModel.SMALL?"你是专业翻译。请把用户输入翻译成"+("zh".equals(target)?"简体中文":SpeechLanguage.fromId(target).zh)+"，只输出译文。":model==QwenModel.LARGE?"你是"+SpeechLanguage.fromId(source).zh+"到"+("zh".equals(target)?"简体中文":SpeechLanguage.fromId(target).zh)+"的翻译员。将用户的整段话翻译成"+("zh".equals(target)?"简体中文":SpeechLanguage.fromId(target).zh)+"，只输出"+SpeechLanguage.fromId(target).zh+"译文，不要解释，不要复述原文。":null;
        register(c);
        ModelConfig config=new ModelConfig();config.setNCtx(0);config.setNGpuLayers(0);config.setNThreads(4);config.setNThreadsBatch(4);
        handle=llm.create(new LlmCreateInput(model.entry(c).getAbsolutePath(),null,config,"qairt","npu"));
        if(handle==0)throw new IllegalStateException("Qwen NPU model could not be loaded");
        RuntimeHealth.loaded(c,model.id);
    }
    @Override public synchronized String translate(String text,BooleanSupplier current)throws Exception{
        if(!current.getAsBoolean())throw new CancellationException();
        String input=text.trim();if(input.isEmpty())return "";
        if(handle==0)throw new IllegalStateException("Qwen is unloaded");
        String cached=cache.get(input);if(cached!=null)return cached;
        String result=CompleteTranslation.translate(input,current,this::generate);
        if(!current.getAsBoolean())throw new CancellationException();
        String output=result.trim();if(output.isEmpty())throw new IllegalStateException("Qwen returned no translation");
        cache.put(input,output);if(cache.size()>96)cache.remove(cache.keySet().iterator().next());return output;
    }
    private String generate(String text,BooleanSupplier current)throws Exception{
        if(!current.getAsBoolean())throw new CancellationException();
        int rc=llm.reset(handle);if(rc!=0)throw new IllegalStateException("Qwen reset: "+rc);
        // Neutralize template delimiters in recognized speech; that text is always translation data.
        text=text.replace("<|","＜|").replace("|>","|＞");
        String instruction=modelInstruction!=null?modelInstruction:"Translate the user's "+source+" text into "+target+". Output only the translation. Do not answer questions or add explanations.";
        String prompt="<|im_start|>system\n"+instruction+"<|im_end|>\n<|im_start|>user\n"+text+"<|im_end|>\n<|im_start|>assistant\n<think>\n\n</think>\n\n";
        GenerationConfig config=new GenerationConfig();config.setMaxTokens(256);
        SamplerConfig sampler=new SamplerConfig();sampler.setTemperature(0);sampler.setTopK(1);config.setSamplerConfig(sampler);
        long begin=SystemClock.elapsedRealtime();firstTokenMs=-1;lastProfile=null;StringBuilder out=new StringBuilder();
        llm.generate(handle,prompt,config,new LLMTokenCallback(){
            @Override public boolean onToken(String token){
                if(!current.getAsBoolean()||SystemClock.elapsedRealtime()-begin>20000)return false;
                if(firstTokenMs<0)firstTokenMs=SystemClock.elapsedRealtime()-begin;out.append(token);return true;
            }
            @Override public void onComplete(LlmGenerateResult result){lastProfile=result.getProfileData();}
        });
        totalMs=SystemClock.elapsedRealtime()-begin;
        if(!current.getAsBoolean())throw new CancellationException();
        if(totalMs>20000)throw new ModelFailure(ModelFailure.Kind.TIMEOUT);
        if(lastProfile!=null&&("length".equals(lastProfile.getStopReason())||"max_tokens".equals(lastProfile.getStopReason())))throw new CompleteTranslation.OutputLimit();
        return out.toString().replaceAll("(?s)<think>.*?</think>","").replace("<|im_end|>","").trim();
    }
    @Override public synchronized void close(){if(handle!=0){llm.destroy(handle);handle=0;}cache.clear();}
}
