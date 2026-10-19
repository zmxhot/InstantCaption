package com.localcaption.instant;

import android.app.LocaleManager;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.res.Configuration;
import android.os.Build;
import android.os.LocaleList;

public final class AppPrefs {
    private AppPrefs() {}
    static SharedPreferences prefs(Context c){return c.getSharedPreferences("display",0);}
    static SharedPreferences legacy(Context c){return c.getSharedPreferences(c.getPackageName().equals("com.localcaption.instant")?"MainActivity":"com.localcaption.instant.MainActivity",0);}
    static int font(Context c){return Math.max(20,Math.min(44,prefs(c).getInt("font",30)));}
    static int overlayFont(Context c){return Math.max(20,Math.min(38,prefs(c).getInt("overlayFont",28)));}
    static boolean overlaySource(Context c){return prefs(c).getBoolean("overlaySource",false);}
    static int overlayTransparency(Context c){return Math.max(0,Math.min(100,prefs(c).getInt("overlayTransparency",0)));}
    static int overlayHeight(Context c){int height=prefs(c).getInt("overlayHeight",0);return height<=0?0:Math.max(120,Math.min(400,height));}
    static boolean english(Context c){return prefs(c).getBoolean("english",false);}
    static boolean fast(Context c){return prefs(c).getBoolean("fast",legacy(c).getBoolean("fast",true));}
    static boolean overlay(Context c){return prefs(c).getBoolean("overlay",legacy(c).getBoolean("overlay",false));}
    static void put(Context c,String key,boolean value){prefs(c).edit().putBoolean(key,value).apply();CaptionState.S.changed();}
    static void put(Context c,String key,int value){prefs(c).edit().putInt(key,value).apply();CaptionState.S.changed();}
    static String language(Context c){return Build.VERSION.SDK_INT>=33?c.getSystemService(LocaleManager.class).getApplicationLocales().toLanguageTags():prefs(c).getString("language","");}
    @android.annotation.SuppressLint("AppBundleLocaleChanges") // All locales packaged; bundle.language.enableSplit=false.
    static Context localized(Context c){
        String tag=Build.VERSION.SDK_INT<33?language(c):"";
        Configuration config=new Configuration(c.getResources().getConfiguration());
        // Android 13+ applies the app locale itself. On older versions we wrap
        // the context. Unsupported system languages always fall back to English.
        String requested=Build.VERSION.SDK_INT<33&&!tag.isEmpty()?tag:config.getLocales().get(0).toLanguageTag();
        int selected=AppLanguage.index(requested);
        String resolved=AppLanguage.TAGS[selected==0?1:selected];
        java.util.Locale locale=java.util.Locale.forLanguageTag(resolved);
        if(config.getLocales().get(0).getLanguage().equals(locale.getLanguage()))return c;
        config.setLocales(new LocaleList(locale));config.setLayoutDirection(locale);
        return c.createConfigurationContext(config);
    }
    static void language(Context c,String tag){
        prefs(c).edit().putString("language",tag).apply();
        if(Build.VERSION.SDK_INT>=33)c.getSystemService(LocaleManager.class).setApplicationLocales(LocaleList.forLanguageTags(tag));
    }
}
