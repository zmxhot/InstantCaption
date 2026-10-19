package com.localcaption.instant;

import android.content.Context;
import java.util.Locale;

/** Speech/translation languages are independent of the app's display language. */
public enum SpeechLanguage {
    ENGLISH("en","英语","English"), CHINESE("zh","中文","Chinese"), CANTONESE("yue","粤语","Cantonese"),
    JAPANESE("ja","日语","Japanese"), KOREAN("ko","韩语","Korean"), SPANISH("es","西班牙语","Spanish"),
    FRENCH("fr","法语","French"), GERMAN("de","德语","German"), ITALIAN("it","意大利语","Italian"),
    PORTUGUESE("pt","葡萄牙语","Portuguese"), RUSSIAN("ru","俄语","Russian"), ARABIC("ar","阿拉伯语","Arabic"),
    HINDI("hi","印地语","Hindi"), INDONESIAN("id","印度尼西亚语","Indonesian"), VIETNAMESE("vi","越南语","Vietnamese"),
    THAI("th","泰语","Thai"), TURKISH("tr","土耳其语","Turkish"), DUTCH("nl","荷兰语","Dutch"),
    POLISH("pl","波兰语","Polish"), UKRAINIAN("uk","乌克兰语","Ukrainian"), SWEDISH("sv","瑞典语","Swedish"),
    DANISH("da","丹麦语","Danish"), NORWEGIAN("no","挪威语","Norwegian"), FINNISH("fi","芬兰语","Finnish"),
    GREEK("el","希腊语","Greek"), HEBREW("he","希伯来语","Hebrew"), MALAY("ms","马来语","Malay"),
    ROMANIAN("ro","罗马尼亚语","Romanian"), HUNGARIAN("hu","匈牙利语","Hungarian"), CZECH("cs","捷克语","Czech"),
    TAMIL("ta","泰米尔语","Tamil");
    public final String id,zh,en;
    SpeechLanguage(String id,String zh,String en){this.id=id;this.zh=zh;this.en=en;}
    public String label(Context c){Locale display=AppLanguage.locale(c);return "zh".equals(display.getLanguage())?zh:Locale.forLanguageTag(id).getDisplayLanguage(display);}
    public static String normalize(String tag){
        String value=tag==null?"":tag.toLowerCase(Locale.ROOT).replace('_','-').split("-")[0];
        if(value.equals("in"))return "id";if(value.equals("iw"))return "he";if(value.equals("nb")||value.equals("nn"))return "no";
        return value;
    }
    public static SpeechLanguage fromId(String id){String code=normalize(id);for(SpeechLanguage l:values())if(l.id.equals(code))return l;return ENGLISH;}
    public static boolean supported(String id){String code=normalize(id);for(SpeechLanguage l:values())if(l.id.equals(code))return true;return false;}
    public static String canonical(String id){return fromId(id)==CANTONESE?"zh":fromId(id).id;}
    public static String label(Context c,String id){return fromId(id).label(c);}
    public static SpeechLanguage[] targets(){return java.util.Arrays.stream(values()).filter(l->l!=CANTONESE).toArray(SpeechLanguage[]::new);}
    public static String direction(Context c,String source){return direction(c,source,"zh");}
    public static String direction(Context c,String source,String target){String arrow="ar".equals(AppLanguage.locale(c).getLanguage())?" ← ":" → ";return label(c,source)+arrow+label(c,target);}
    public static String systemLanguageTag(Context context){
        if(android.os.Build.VERSION.SDK_INT>=33){
            android.app.LocaleManager manager=context.getSystemService(android.app.LocaleManager.class);
            if(manager!=null){android.os.LocaleList locales=manager.getSystemLocales();if(!locales.isEmpty())return locales.get(0).toLanguageTag();}
        }
        android.os.LocaleList locales=android.content.res.Resources.getSystem().getConfiguration().getLocales();
        return locales.isEmpty()?"en":locales.get(0).toLanguageTag();
    }
    public static String[] defaultPair(String systemTag){
        String language=normalize(systemTag);
        if(language.equals("en"))return new String[]{"es","en"};
        return new String[]{"en",supported(language)?canonical(language):"zh"};
    }
    public static String[] configuredPair(String savedSource,String savedTarget,String systemTag){
        String[] defaults=defaultPair(systemTag);
        return new String[]{supported(savedSource)?fromId(savedSource).id:defaults[0],supported(savedTarget)?canonical(savedTarget):defaults[1]};
    }
}
