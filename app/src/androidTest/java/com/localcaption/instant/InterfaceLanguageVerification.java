package com.localcaption.instant;

import android.app.*;
import android.content.*;
import android.content.res.Configuration;
import android.graphics.*;
import android.os.*;
import android.view.*;
import android.widget.*;
import java.io.*;
import java.util.*;
import org.json.*;

/** Resource resolution, real activity recreation, RTL and preference isolation on a device. */
final class InterfaceLanguageVerification {
    private static final String[] SETTINGS={"Settings","设置","Ajustes","Paramètres","Einstellungen","Configurações","Настройки","設定","설정","الإعدادات"};
    static void run(Instrumentation test,Bundle options){
        Context c=test.getTargetContext();String original=AppPrefs.language(c);
        Map<String,?> engineBefore=new HashMap<>(c.getSharedPreferences("engine",0).getAll());
        Map<String,?> displayBefore=new HashMap<>(AppPrefs.prefs(c).getAll());
        JSONObject report=new JSONObject();JSONArray locales=new JSONArray();Throwable failure=null;Activity active=null;
        boolean readme="readme".equals(options.getString("phase"));
        try{
            require(AppLanguage.TAGS.length==11,"Expected ten interface languages and system default");
            for(int i=1;i<AppLanguage.TAGS.length;i++){
                String tag=AppLanguage.TAGS[i];if(options.getString("locale")!=null&&!tag.equals(options.getString("locale")))continue;Context local=context(c,tag);
                require(SETTINGS[i-1].equals(Ui.t(local,"设置","Settings")),"Wrong resources for "+tag);
                for(int id:UiStrings.ENGLISH.values())require(!local.getString(id).isEmpty(),"Empty resource in "+tag);
                String loading=Ui.status(local,"正在加载 Qwen3-4B NPU 翻译模型…");
                require(loading.contains("Qwen3-4B"),"Model label lost in status");
                if(!tag.startsWith("zh"))require(!loading.contains("正在")&&!loading.contains("模型"),"Untranslated state: "+tag);
                if(!"en".equals(tag)&&!tag.startsWith("zh"))require(!loading.startsWith("Loading"),"English fallback in "+tag);
                JSONObject item=new JSONObject().put("tag",tag).put("settings",SETTINGS[i-1]).put("resources",UiStrings.ENGLISH.size()).put("loading",loading);
                if(!"resources".equals(options.getString("phase"))){
                    onMain(test,()->AppPrefs.language(c,tag));
                    long until=SystemClock.elapsedRealtime()+10000;
                    while(!c.getResources().getConfiguration().getLocales().get(0).getLanguage().equals(Locale.forLanguageTag(tag).getLanguage())&&SystemClock.elapsedRealtime()<until)Thread.sleep(100);
                    active=test.startActivitySync(new Intent(c,MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
                    Thread.sleep(500);final Activity main=active;final String expectedSettings=SETTINGS[i-1];
                    onMain(test,()->{View settingsButton=find(main.getWindow().getDecorView(),expectedSettings);require(settingsButton!=null,"Main screen did not switch to "+tag);require(((TextView)settingsButton).getLineCount()==1,"Settings label must not split a word: "+tag);});
                    if(readme){
                        long deadline=SystemClock.elapsedRealtime()+120000;
                        while(!CaptionState.S.ready&&SystemClock.elapsedRealtime()<deadline)Thread.sleep(200);
                        require(CaptionState.S.ready,"Prepare the selected models before capturing README screenshots");
                    }
                    capture(test,c,"language-"+tag+"-main");checkLayout(test,active,tag+" main");
                    onMain(test,active::finish);active=null;
                    if(readme){
                        active=test.startActivitySync(new Intent(c,AppearanceActivity.class).putExtra("floating",true).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
                        capture(test,c,"language-"+tag+"-floating-style");checkLayout(test,active,tag+" appearance");
                        onMain(test,active::finish);active=null;
                        item.put("activities",2).put("modelsReady",true).put("rtl",tag.equals("ar"));locales.put(item);continue;
                    }
                    active=test.startActivitySync(new Intent(c,SettingsActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));Thread.sleep(250);
                    capture(test,c,"language-"+tag+"-settings");checkLayout(test,active,tag+" settings");
                    final Activity settings=active;
                    onMain(test,()->{
                        View decor=settings.getWindow().getDecorView();View button=find(decor,Ui.t(settings,"界面语言：","App language: ")+AppLanguage.NAMES[AppLanguage.index(tag)]);
                        require(button!=null,"Current interface language label missing");button.performClick();
                    });Thread.sleep(200);capture(test,c,"language-"+tag+"-picker");test.sendKeyDownUpSync(KeyEvent.KEYCODE_BACK);
                    onMain(test,active::finish);active=null;
                    for(boolean floating:new boolean[]{false,true}){
                        active=test.startActivitySync(new Intent(c,AppearanceActivity.class).putExtra("floating",floating).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));Thread.sleep(250);
                        capture(test,c,"language-"+tag+(floating?"-floating-style":"-main-style"));checkLayout(test,active,tag+" appearance");onMain(test,active::finish);active=null;
                    }
                    active=test.startActivitySync(new Intent(c,ModelManagerActivity.class).putExtra("tab",0).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));Thread.sleep(300);
                    final Activity models=active;
                    onMain(test,()->{
                        View content=models.findViewById(android.R.id.content);View root=((ViewGroup)content).getChildAt(0);
                        require(root.getLayoutDirection()==("ar".equals(tag)?View.LAYOUT_DIRECTION_RTL:View.LAYOUT_DIRECTION_LTR),"Wrong layout direction: "+tag);
                    });
                    capture(test,c,"language-"+tag+"-models");checkLayout(test,active,tag+" models");
                    onMain(test,active::finish);active=null;
                    active=test.startActivitySync(new Intent(c,ModelManagerActivity.class).putExtra("tab",1).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));Thread.sleep(250);capture(test,c,"language-"+tag+"-speech-models");checkLayout(test,active,tag+" speech models");onMain(test,active::finish);active=null;
                    active=test.startActivitySync(new Intent(c,SetupActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));Thread.sleep(250);
                    capture(test,c,"language-"+tag+"-setup");checkLayout(test,active,tag+" setup");onMain(test,active::finish);active=null;
                    item.put("activities",7).put("pickerOpened",true).put("rtl",tag.equals("ar"));
                }
                locales.put(item);
            }
            require("Settings".equals(Ui.t(context(c,"it-IT"),"设置","Settings")),"Unsupported language must fall back to English");
            require("Ajustes".equals(Ui.t(context(c,"es-MX"),"设置","Settings")),"Regional Spanish fallback failed");
            require("Configurações".equals(Ui.t(context(c,"pt-BR"),"设置","Settings")),"Regional Portuguese fallback failed");
            require(engineBefore.equals(c.getSharedPreferences("engine",0).getAll()),"Interface language changed speech/translation configuration");
            report.put("locales",locales).put("englishFallback",true).put("regionalLocales",true).put("modelAndSpeechPreferencesUnchanged",true).put("passed",true);
        }catch(Throwable e){failure=e;try{report.put("passed",false).put("locales",locales).put("error",android.util.Log.getStackTraceString(e));}catch(Exception ignored){}}
        finally{
            if(active!=null){Activity last=active;onMain(test,last::finish);}
            onMain(test,()->{AppPrefs.language(c,original);String saved=(String)displayBefore.get("language");if(saved==null)AppPrefs.prefs(c).edit().remove("language").commit();else AppPrefs.prefs(c).edit().putString("language",saved).commit();});
        }
        try(FileOutputStream out=new FileOutputStream(new File(c.getExternalFilesDir(null),"interface-languages-verification.json"))){out.write(report.toString(2).getBytes(java.nio.charset.StandardCharsets.UTF_8));}catch(Exception ignored){}
        Bundle result=new Bundle();result.putString("stream",report.toString());test.finish(failure==null?0:1,result);
    }
    private static Context context(Context c,String tag){Configuration config=new Configuration(c.getResources().getConfiguration());config.setLocales(LocaleList.forLanguageTags(tag));config.setLayoutDirection(Locale.forLanguageTag(tag));return c.createConfigurationContext(config);}
    private static View find(View view,String text){if(view instanceof TextView&&text.contentEquals(((TextView)view).getText()))return view;if(view instanceof ViewGroup){ViewGroup group=(ViewGroup)view;for(int i=0;i<group.getChildCount();i++){View found=find(group.getChildAt(i),text);if(found!=null)return found;}}return null;}
    private static void checkLayout(Instrumentation test,Activity a,String label){onMain(test,()->check(a.getWindow().getDecorView(),label));}
    private static void check(View view,String label){
        if(view.getVisibility()!=View.VISIBLE)return;
        if(view instanceof TextView){TextView text=(TextView)view;android.text.Layout layout=text.getLayout();Rect visible=new Rect();
            if(layout!=null&&text.getGlobalVisibleRect(visible)&&text.getText().length()>0&&text.getMaxLines()>2){
                require(layout.getHeight()<=text.getHeight()-text.getCompoundPaddingTop()-text.getCompoundPaddingBottom()+2,label+" vertically clipped: "+text.getText());
            }
        }
        if(view instanceof ViewGroup){ViewGroup group=(ViewGroup)view;for(int i=0;i<group.getChildCount();i++)check(group.getChildAt(i),label);}
    }
    private static void capture(Instrumentation test,Context c,String name)throws Exception{test.waitForIdleSync();Thread.sleep(900);test.waitForIdleSync();Bitmap b=test.getUiAutomation().takeScreenshot();require(b!=null,"Screenshot unavailable");try(FileOutputStream out=new FileOutputStream(new File(c.getExternalFilesDir(null),name+".png"))){b.compress(Bitmap.CompressFormat.PNG,100,out);}finally{b.recycle();}}
    private static void onMain(Instrumentation test,Runnable action){
        final Throwable[] failure={null};test.runOnMainSync(()->{try{action.run();}catch(Throwable e){failure[0]=e;}});
        if(failure[0]!=null)throw new AssertionError("UI verification failed",failure[0]);
    }
    private static void require(boolean condition,String message){if(!condition)throw new AssertionError(message);}
}
