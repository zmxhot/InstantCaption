package com.localcaption.instant;

import android.content.Context;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.concurrent.CancellationException;
import java.util.function.BooleanSupplier;

/** Portable, in-process CPU inference. No service, network, GPU or vendor NPU is used. */
final class CpuTranslator implements TranslationRouter.Backend {
    private long handle;
    private final String instruction;
    private final LinkedHashMap<String,String> cache=new LinkedHashMap<>();
    CpuTranslator(Context context,String source,String target,QwenModel model)throws Exception{
        if(!model.isCpu()||!model.isSupported(context)||!model.supportsLanguages(source,target))throw new IllegalArgumentException("Unsupported CPU translation route");
        if(!model.isInstalled(context))throw new TranslationRouter.ModelMissingException(model.label+" model is not installed");
        RuntimeHealth.beforeLoad(context,model);System.loadLibrary("caption_cpu");
        // Publisher prompt retained after multilingual regression; longer instructions regressed Korean/Arabic.
        instruction="Translate the following text into "+("zh".equals(target)?"Simplified Chinese":SpeechLanguage.fromId(target).en)+". Note that you should only output the translated result without any additional explanation:\n";
        handle=nativeLoad(model.entry(context).getAbsolutePath().getBytes(StandardCharsets.UTF_8),Math.min(4,Runtime.getRuntime().availableProcessors()));
        if(handle==0)throw new IllegalStateException("CPU translation model failed to load");RuntimeHealth.loaded(context,model.id);
    }
    @Override public synchronized String translate(String text,BooleanSupplier current)throws Exception{
        check(current);String input=text.trim();if(input.isEmpty())return "";
        String cached=cache.get(input);if(cached!=null)return cached;
        String result=CompleteTranslation.translate(input,current,this::generate);check(current);
        cache.put(input,result);if(cache.size()>96)cache.remove(cache.keySet().iterator().next());return result;
    }
    private String generate(String text,BooleanSupplier current)throws Exception{
        text=text.replace("<|","＜|").replace("|>","|＞");
        check(current);nativeBegin(handle,(instruction+text).getBytes(StandardCharsets.UTF_8));
        while(true){check(current);if(nativePrefill(handle))break;}
        ByteArrayOutputStream out=new ByteArrayOutputStream();
        for(int tokens=0;tokens<256;tokens++){
            check(current);byte[] piece=nativeNext(handle);if(piece==null)return out.toString(StandardCharsets.UTF_8.name()).trim();out.write(piece);
        }
        check(current);throw new CompleteTranslation.OutputLimit();
    }
    private void check(BooleanSupplier current){if(handle==0)throw new IllegalStateException("Text model is unloaded");if(!current.getAsBoolean())throw new CancellationException();}
    @Override public synchronized void close(){if(handle!=0){nativeClose(handle);handle=0;}cache.clear();}
    private static native long nativeLoad(byte[] path,int threads)throws java.io.IOException;
    private static native void nativeBegin(long handle,byte[] prompt)throws java.io.IOException;
    private static native boolean nativePrefill(long handle)throws java.io.IOException;
    private static native byte[] nativeNext(long handle)throws java.io.IOException;
    private static native void nativeClose(long handle);
}
