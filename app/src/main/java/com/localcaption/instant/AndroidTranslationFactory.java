package com.localcaption.instant;

import android.content.Context;
import java.util.function.BooleanSupplier;

/** Independent local translation weights; no service-provider SDK or network inference. */
final class AndroidTranslationFactory implements TranslationRouter.Factory {
    private final Context context;
    private final String source,target,choice;
    AndroidTranslationFactory(Context context){this(context,"en","zh");}
    AndroidTranslationFactory(Context context,String source,String target){this(context,source,target,"opus");}
    AndroidTranslationFactory(Context context,String source,String target,String choice){
        this.context=context.getApplicationContext();
        this.source=SpeechLanguage.canonical(source);this.target=SpeechLanguage.canonical(target);
        this.choice=choice;
    }
    @Override public boolean passthrough(){return source.equals(target);}
    @Override public String fallbackName(){return QwenModel.known(choice)?choice:target.equals("en")?"speech-english":"opus";}
    @Override public boolean opusInstalled(){return QwenModel.known(choice)?QwenModel.fromId(choice).isInstalled(context):target.equals("en")||TranslationModel.installed(context,target);}
    @Override public TranslationRouter.Backend opus()throws Exception{
        if(QwenModel.known(choice)){
            CaptionState.S.status(AppPrefs.localized(context).getString(R.string.loading_text_model,QwenModel.fromId(choice).label,QwenModel.fromId(choice).processor()));
            if(QwenModel.fromId(choice).isCpu())return new CpuTranslator(context,source,target,QwenModel.fromId(choice));
            if(QwenModel.fromId(choice).isLiteRt())return new LiteRtTranslator(context,source,target,QwenModel.fromId(choice));
            return new QwenTranslator(context,source,target,QwenModel.fromId(choice));
        }
        // Whisper speech translation already produces English for this route.
        if(target.equals("en"))return new TranslationRouter.Backend(){
            public String translate(String text,BooleanSupplier current){return text;}
            public void close(){}
        };
        CaptionState.S.status("正在加载本地文字翻译模型…");
        MarianTranslator translator=new MarianTranslator(TranslationModel.root(context,target),TranslationModel.spec(context,target));
        return new TranslationRouter.Backend(){
            @Override public String translate(String text,BooleanSupplier current)throws Exception{return translator.translate(text,current);}
            @Override public void close()throws Exception{translator.close();}
        };
    }
}
