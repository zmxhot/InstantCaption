package com.localcaption.instant;

import android.content.Context;
import java.util.function.BooleanSupplier;

/** Keeps the portable APK independent of LiteRT-LM Java and native artifacts. */
final class LiteRtTranslator implements TranslationRouter.Backend {
    int cappedGenerations,lastDecodeTokens;
    LiteRtTranslator(Context context,String source,String target,QwenModel model){
        throw new IllegalStateException("LiteRT-LM is unavailable in the portable build");
    }
    LiteRtTranslator(Context context,String source,String target,QwenModel model,int tokens){
        this(context,source,target,model);
    }
    @Override public String translate(String text,BooleanSupplier current){
        throw new IllegalStateException("LiteRT-LM is unavailable in the portable build");
    }
    @Override public void close(){}
}
