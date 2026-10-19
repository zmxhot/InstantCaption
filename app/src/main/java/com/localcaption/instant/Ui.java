package com.localcaption.instant;

import android.app.Activity;
import android.content.Context;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.view.*;
import android.widget.*;
import java.util.Locale;

final class Ui {
    static final int BG=0xFF0B1118,CARD=0xFF141F2B,MINT=0xFF82F4C5,WHITE=0xFFF3F6FA,MUTED=0xFF93A6B9;
    static String t(Context c,String zh,String en){return UiStrings.text(c,zh,en);}
    static int dp(Context c,float n){return (int)(c.getResources().getDisplayMetrics().density*n+0.5f);}
    static GradientDrawable bg(Context c,int color,int r){GradientDrawable d=new GradientDrawable();d.setColor(color);d.setCornerRadius(dp(c,r));return d;}
    static TextView text(Context c,String s,int size,int color){TextView t=new TextView(c);t.setText(s);t.setTextSize(size);t.setTextColor(color);t.setIncludeFontPadding(false);t.setGravity(Gravity.START|Gravity.CENTER_VERTICAL);return t;}
    static CharSequence settingSummary(Context c,String label,String value){String arrow="ar".equals(AppLanguage.locale(c).getLanguage())?"‹":"›";android.text.SpannableString text=new android.text.SpannableString(label+"  "+arrow+"\n"+value);text.setSpan(new android.text.style.ForegroundColorSpan(MUTED),0,label.length(),0);text.setSpan(new android.text.style.RelativeSizeSpan(.85f),0,label.length(),0);text.setSpan(new android.text.style.StyleSpan(Typeface.BOLD),label.length()+4,text.length(),0);return text;}
    static void setText(TextView view,CharSequence value){if(!android.text.TextUtils.equals(view.getText(),value))view.setText(value);}
    static Button button(Context c,String s,int color,int tc){Button b=new Button(c);b.setText(s);b.setAllCaps(false);b.setTextSize(14);b.setTextColor(tc);b.setIncludeFontPadding(false);b.setBackground(new android.graphics.drawable.RippleDrawable(android.content.res.ColorStateList.valueOf(0x2282F4C5),bg(c,color,12),null));b.setMinHeight(dp(c,48));b.setMinimumHeight(dp(c,48));b.setMinWidth(0);b.setMinimumWidth(0);b.setPadding(dp(c,12),dp(c,8),dp(c,12),dp(c,8));b.setStateListAnimator(null);return b;}
    static LinearLayout column(Context c){LinearLayout l=new LinearLayout(c);l.setLayoutDirection(android.text.TextUtils.getLayoutDirectionFromLocale(AppLanguage.locale(c)));l.setOrientation(LinearLayout.VERTICAL);return l;}
    static LinearLayout row(Context c){LinearLayout l=new LinearLayout(c);l.setLayoutDirection(android.text.TextUtils.getLayoutDirectionFromLocale(AppLanguage.locale(c)));l.setGravity(Gravity.CENTER_VERTICAL);return l;}
    static LinearLayout root(Activity a){
        LinearLayout root=column(a);root.setBackgroundColor(BG);
        root.setOnApplyWindowInsetsListener((v,insets)->{
            android.graphics.Insets bars=Build.VERSION.SDK_INT>=30?insets.getInsets(WindowInsets.Type.systemBars()|WindowInsets.Type.displayCutout()):android.graphics.Insets.of(insets.getSystemWindowInsetLeft(),insets.getSystemWindowInsetTop(),insets.getSystemWindowInsetRight(),insets.getSystemWindowInsetBottom());
            v.setPadding(dp(a,16)+bars.left,dp(a,4)+bars.top,dp(a,16)+bars.right,dp(a,10)+bars.bottom);return insets;
        });a.setContentView(root);root.requestApplyInsets();return root;
    }
    static void title(Activity a,LinearLayout root,String title){
        LinearLayout row=row(a);Button back=button(a,"ar".equals(AppLanguage.locale(a).getLanguage())?"›":"‹",Color.TRANSPARENT,MINT);back.setPadding(0,0,0,0);back.setTextSize(android.util.TypedValue.COMPLEX_UNIT_DIP,30);back.setTextDirection(View.TEXT_DIRECTION_LTR);back.setContentDescription(t(a,"返回","Back"));back.setOnClickListener(v->a.finish());row.addView(back,new LinearLayout.LayoutParams(dp(a,48),dp(a,48)));TextView text=text(a,title,21,WHITE);text.setTypeface(null,Typeface.BOLD);text.setMinHeight(dp(a,48));row.addView(text,new LinearLayout.LayoutParams(0,-2,1));root.addView(row);
    }
    static String size(long bytes){return bytes>=1_000_000_000L?String.format(Locale.ROOT,"%.2f GB",bytes/1_000_000_000.0):String.format(Locale.ROOT,"%.0f MB",bytes/1000000.0);}
    static boolean content(String s){return s.codePoints().anyMatch(Character::isLetterOrDigit);}
    static String translationBackend(Context c,String backend){
        if(QwenModel.known(backend))return c.getString(R.string.local_text_model,QwenModel.fromId(backend).label,QwenModel.fromId(backend).processor());
        if("opus".equals(backend))return t(c,"OPUS-MT · CPU 本地翻译","OPUS-MT · CPU translation");
        if("speech-english".equals(backend))return t(c,"Whisper 本地语音转英语","Whisper local speech-to-English translation");
        if("passthrough".equals(backend))return t(c,"原文转写 · 无需翻译模型","Source transcript · no translation model");
        return t(c,"本地模型尚未就绪","Local models not ready");
    }
    static String translationPlan(Context c,LocalEngine engine){
        if(!engine.translationAvailable())return t(c,"当前语言组合暂无本地翻译模型","No local translation model for this language pair");
        if(SpeechLanguage.canonical(engine.sourceLanguage()).equals(SpeechLanguage.canonical(engine.targetLanguage())))return translationBackend(c,"passthrough");
        String model=engine.usesQwenTranslation()?translationBackend(c,engine.translationChoice()):"en".equals(engine.targetLanguage())?t(c,"无需额外文本模型 · Whisper 语音转译","No separate text model · Whisper speech translation"):translationBackend(c,"opus")+" · "+SpeechLanguage.label(c,engine.targetLanguage());
        return model+(engine.translationReady()?"":t(c," · 未就绪"," · not ready"));
    }
    static String translationSelection(Context c,LocalEngine engine){
        String plan=translationPlan(c,engine);
        if(!engine.translationAvailable()||SpeechLanguage.canonical(engine.sourceLanguage()).equals(SpeechLanguage.canonical(engine.targetLanguage()))||(!engine.usesQwenTranslation()&&"en".equals(engine.targetLanguage())))return plan;
        return (engine.translationReady()?t(c,"使用中 · ","In use · "):t(c,"已选择 · ","Selected · "))+plan;
    }
    static String modelSummary(Context c,LocalEngine engine){
        return t(c,"文本翻译：","Text translation: ")+translationSelection(c,engine)+"\n"+t(c,"识别：","Recognition: ")+engine.engineName()+(engine.automaticSpeech()?t(c," · 自动"," · automatic"):t(c," · 手动"," · manual"));
    }
    static String automaticRecognition(Context c,LocalEngine engine){
        if(engine.usesSystemSpeech())return t(c,"已自动使用系统离线语音识别，无需下载额外识别模型。","Using system offline speech recognition automatically. No additional speech model download is needed.");
        String name=engine.selectedModel().label(c);
        if(engine.recognitionUsesEnglishBridge())return t(c,"此语言组合需要先将语音转成英语，自动使用 ","This pair first needs speech-to-English translation, using ")+name+t(c,"。系统听写和 SenseVoice 不能完成此中转。"," automatically. System transcription and SenseVoice cannot perform this translation step.");
        String reason;
        switch(engine.systemSpeechState()){
            case "language-not-installed":reason=t(c,"系统离线语音包尚未就绪","System offline speech pack is not ready");break;
            case "no-google-services":reason=t(c,"未检测到可用的 Google 服务","Google services are unavailable");break;
            case "android-version":reason=t(c,"当前系统不支持此离线音频接口","This Android version does not support this offline audio interface");break;
            case "checking":return t(c,"正在检查系统离线识别，避免重复下载。","Checking system offline recognition to avoid duplicate downloads.");
            default:reason=t(c,"系统离线识别暂不可用","System offline recognition is currently unavailable");
        }
        return reason+t(c,"，自动使用 ",". Automatically using ")+name+"。\n"+t(c,"仅在模型缺失时提示下载；已有模型直接复用。","A download is requested only if needed; existing models are reused.");
    }
    static String offlineRouteExplanation(Context c,LocalEngine engine){
        String source=engine.sourceLanguage(),target=engine.targetLanguage();
        if(!engine.translationAvailable()){
            if("yue".equals(source))return t(c,"当前模型支持粤语 → 中文转写，暂不支持粤语转其他字幕语言。请在设置中把字幕语言改为中文，或更换说话语言。","Current models support Cantonese → Chinese transcription, but not Cantonese translation to other subtitle languages. Choose Chinese captions or change the spoken language in Settings.");
            return t(c,"当前语言组合暂无可用的本地翻译模型。请在设置中选择英语、中文、德语、法语、西班牙语、俄语、阿拉伯语、印地语、意大利语或葡萄牙语作为字幕语言。其他语言仍可选择同语言转写。","No local translation model is available for this pair. In Settings, choose English, Chinese, German, French, Spanish, Russian, Arabic, Hindi, Italian or Portuguese captions. Other languages remain available for same-language transcription.");
        }
        if(SpeechLanguage.canonical(source).equals(SpeechLanguage.canonical(target)))return t(c,"直接显示语音识别结果，无需额外文字翻译模型。","Displays the speech recognition result directly, without an additional text translation model.");
        if(engine.usesQwenTranslation())return c.getString(R.string.direct_text_model_explanation,QwenModel.fromId(engine.translationChoice()).label,QwenModel.fromId(engine.translationChoice()).processor());
        if(engine.recognitionUsesEnglishBridge())return ("en".equals(target)?t(c,"Whisper 在手机本地把语音转成英语字幕，无需额外文字翻译模型。","Whisper translates speech to English captions on your phone, without an additional text translation model."):t(c,"Whisper 先把语音转成英语，再由 OPUS-MT 本地 AI 模型翻译为字幕语言。显示识别文字时会显示英语中间结果。","Whisper first translates speech to English, then the local OPUS-MT AI model translates it to the caption language. Recognized text shows the intermediate English."))+t(c,"\n小模型中转可能明显错译，尤其是复杂语句。","\nSmall models can mistranslate speech, especially complex sentences.");
        return t(c,"语音识别模型负责听写，OPUS-MT 负责把识别文字翻译为字幕语言。两者均在手机本地运行。","The speech recognition model transcribes audio; OPUS-MT translates that text to the caption language. Both run locally on your phone.");
    }
    static String status(Context c,String s){return UiStrings.status(c,s);}
}
