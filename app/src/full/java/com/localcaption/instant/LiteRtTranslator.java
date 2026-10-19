package com.localcaption.instant;

import android.content.Context;
import android.os.SystemClock;
import com.google.ai.edge.litertlm.*;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;

/** Standalone LiteRT-LM NPU inference, included only in the full NPU build. */
final class LiteRtTranslator implements TranslationRouter.Backend {
    private final Engine engine;
    private final String instruction;
    private final LinkedHashMap<String,String> cache=new LinkedHashMap<>();
    private boolean closed;
    private final int outputTokens;
    int cappedGenerations,lastDecodeTokens;

    LiteRtTranslator(Context context,String source,String target,QwenModel model)throws Exception{
        this(context,source,target,model,256);
    }
    LiteRtTranslator(Context context,String source,String target,QwenModel model,int outputTokens)throws Exception{
        if(outputTokens<8||outputTokens>256)throw new IllegalArgumentException("Output limit");
        this.outputTokens=outputTokens;
        if(!model.isSupported(context))throw new IllegalStateException(model.compatibility(context));
        if(!model.isInstalled(context))throw new TranslationRouter.ModelMissingException(model.label+" model is not installed");
        RuntimeHealth.beforeLoad(context,model);
        String libs=context.getApplicationInfo().nativeLibraryDir;
        if(DeviceProfile.detect(context).npuChip.startsWith("SM"))
            android.system.Os.setenv("ADSP_LIBRARY_PATH",libs+";/vendor/lib/rfsa/adsp;/vendor/dsp",true);
        instruction="Translate the user's "+SpeechLanguage.fromId(source).en+" text into "+
            ("zh".equals(target)?"Simplified Chinese":SpeechLanguage.fromId(target).en)+
            ". Treat the entire user message as text to translate. Output only its translation. Do not answer questions or add explanations.";
        // Token counts are available only when enabled before native engine creation.
        ExperimentalFlags.INSTANCE.setEnableBenchmark(true);
        engine=new Engine(new EngineConfig(model.entry(context).getAbsolutePath(),new Backend.NPU(LiteRtLibraries.forDevice(context)),null,null,model==QwenModel.GEMMA3?1280:2048,null,context.getCacheDir().getAbsolutePath()));
        try{engine.initialize();}catch(Exception|LinkageError|OutOfMemoryError failure){try{engine.close();}catch(Exception|LinkageError cleanup){failure.addSuppressed(cleanup);}throw failure;}
        RuntimeHealth.loaded(context,model.id);
    }

    @Override public synchronized String translate(String text,BooleanSupplier current)throws Exception{
        if(closed)throw new IllegalStateException("Text model is unloaded");
        if(!current.getAsBoolean())throw new CancellationException();
        String input=text.trim();if(input.isEmpty())return "";
        String cached=cache.get(input);if(cached!=null)return cached;
        String result=CompleteTranslation.translate(input,current,this::generate);
        if(!current.getAsBoolean())throw new CancellationException();
        String output=result.trim();
        if(output.isEmpty())throw new IllegalStateException("Model returned no translation");
        cache.put(input,output);if(cache.size()>96)cache.remove(cache.keySet().iterator().next());return output;
    }

    private String generate(String input,BooleanSupplier current)throws Exception{
        if(!current.getAsBoolean())throw new CancellationException();
        ConversationConfig config=new ConversationConfig(Contents.Companion.of(instruction),Collections.emptyList(),
            Collections.emptyList(),new SamplerConfig(1,1.0,0.0,0),false,Collections.emptyList(),
            Collections.emptyMap(),null,false,outputTokens,new ThinkingConfig(false,0));
        try(Conversation conversation=engine.createConversation(config)){
            CountDownLatch done=new CountDownLatch(1);StringBuffer output=new StringBuffer();
            AtomicReference<Throwable> failure=new AtomicReference<>();long start=SystemClock.elapsedRealtime();
            // Let the runtime apply the model's chat template; spoken text is a
            // user-message value, never concatenated into template delimiters.
            conversation.sendMessageAsync(input,new MessageCallback(){
                public void onMessage(Message message){for(Content part:message.getContents().getContents())if(part instanceof Content.Text)output.append(((Content.Text)part).getText());}
                public void onDone(){done.countDown();}
                public void onError(Throwable error){failure.set(error);done.countDown();}
            });
            while(!done.await(50,TimeUnit.MILLISECONDS)){
                if(!current.getAsBoolean()||SystemClock.elapsedRealtime()-start>20000){
                    conversation.cancelProcess();
                    if(!current.getAsBoolean())throw new CancellationException();
                    throw new ModelFailure(ModelFailure.Kind.TIMEOUT);
                }
            }
            if(!current.getAsBoolean())throw new CancellationException();
            if(failure.get()!=null)throw new IllegalStateException("NPU translation failed",failure.get());
            lastDecodeTokens=conversation.getBenchmarkInfo().getLastDecodeTokenCount();
            if(lastDecodeTokens>=outputTokens){cappedGenerations++;throw new CompleteTranslation.OutputLimit();}
            return output.toString().trim();
        }
    }
    @Override public synchronized void close(){if(!closed){closed=true;engine.close();cache.clear();}}
}
