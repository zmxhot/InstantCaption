package com.localcaption.instant;

import android.content.Context;
import java.util.Locale;

/** Interface language only. Never changes the speech or subtitle language. */
final class AppLanguage {
    static final String[] TAGS={"", "en", "zh-Hans", "es", "fr", "de", "pt", "ru", "ja", "ko", "ar"};
    static final String[] NAMES={"", "English", "简体中文", "Español", "Français", "Deutsch", "Português", "Русский", "日本語", "한국어", "العربية"};
    static int index(String tags){
        if(tags==null||tags.isEmpty())return 0;
        String code=Locale.forLanguageTag(tags.split(",")[0]).getLanguage();
        for(int i=1;i<TAGS.length;i++)if(Locale.forLanguageTag(TAGS[i]).getLanguage().equals(code))return i;
        return 1;
    }
    static String label(Context c,String tags){return index(tags)==0?Ui.t(c,"跟随系统","System default"):NAMES[index(tags)];}
    static Locale locale(Context c){
        Locale current=AppPrefs.localized(c).getResources().getConfiguration().getLocales().get(0);
        int index=index(current.toLanguageTag());
        return Locale.forLanguageTag(TAGS[index==0?1:index]);
    }
}
