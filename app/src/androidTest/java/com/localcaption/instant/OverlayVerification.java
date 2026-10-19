package com.localcaption.instant;

import android.app.*;
import android.content.*;
import android.graphics.*;
import android.os.*;
import android.view.*;
import android.widget.*;
import java.io.*;
import java.lang.reflect.Field;
import java.util.*;
import org.json.*;

/** Exercises real overlay windows using injected screen touches, including text and padding. */
final class OverlayVerification {
    static void run(Instrumentation test,Bundle options){
        Context c=test.getTargetContext();int rotation=android.provider.Settings.System.getInt(c.getContentResolver(),"user_rotation",0);boolean autoRotate=android.provider.Settings.System.getInt(c.getContentResolver(),"accelerometer_rotation",1)==1;FloatingCaptionOverlay overlay=new FloatingCaptionOverlay(c);JSONObject report=new JSONObject();Throwable failure=null;Activity rotationHost=null;
        Map<String,?> before=new HashMap<>(AppPrefs.prefs(c).getAll());
        Map<String,?> engineBefore=new HashMap<>(c.getSharedPreferences("engine",0).getAll());
        try{
            require(!c.getSystemService(KeyguardManager.class).isKeyguardLocked(),"Unlock phone before overlay verification");
            try(ParcelFileDescriptor fd=test.getUiAutomation().executeShellCommand("cmd statusbar collapse");InputStream input=new ParcelFileDescriptor.AutoCloseInputStream(fd)){input.readAllBytes();}
            test.getUiAutomation().setRotation(android.app.UiAutomation.ROTATION_FREEZE_0);
            verifySettings(test,c,report);
            test.sendKeyDownUpSync(KeyEvent.KEYCODE_HOME);Thread.sleep(400);
            onMain(test,()->AppPrefs.prefs(c).edit().putBoolean("english",true).putBoolean("overlaySource",false).putInt("overlayTransparency",0).putInt("overlayHeight",0).putInt("overlayFont",28).commit());
            test.runOnMainSync(()->overlay.show("欢迎使用实时字幕，整块字幕都可以拖动。","Welcome. Drag anywhere on this caption panel."));Thread.sleep(300);
            require(((View)field(overlay,"recognized")).getVisibility()==View.GONE,"Main-screen source preference leaked to overlay");
            onMain(test,()->{AppPrefs.put(c,"english",false);AppPrefs.put(c,"overlaySource",true);overlay.show("欢迎使用实时字幕，整块字幕都可以拖动。","Welcome. Drag anywhere on this caption panel.");});Thread.sleep(200);
            require(((View)field(overlay,"recognized")).getVisibility()==View.VISIBLE&&!AppPrefs.english(c),"Overlay source switch is not independent");report.put("independentSourceSwitch",true);
            onMain(test,()->{AppPrefs.put(c,"overlayTransparency",40);AppPrefs.put(c,"overlayHeight",240);overlay.show("欢迎使用实时字幕，整块字幕都可以拖动。","Welcome. Drag anywhere on this caption panel.");});Thread.sleep(200);
            require(Math.abs(((View)field(overlay,"recognized")).getAlpha()-.6f)<.001f&&Math.abs(((View)field(overlay,"translated")).getAlpha()-.6f)<.001f,"Transparency did not reach both texts");
            require(bounds(test,overlay).height()==Ui.dp(c,240),"Custom height did not resize window");report.put("customHeight240dp",true).put("textTransparency40Percent",true);shot(test,c,"overlay-bilingual");
            Rect initial=bounds(test,overlay);require(initial.width()>500,"Expected expanded overlay");
            Rect text=bounds(test,(View)field(overlay,"translated"));
            gesture(test,text.centerX(),text.centerY(),text.centerX(),text.centerY()-220);
            Rect moved=bounds(test,overlay);require(moved.top<initial.top-150&&edge(overlay)==0,"Dragging subtitle text failed or tucked vertically");report.put("dragFromSubtitle",true);
            Rect sourceBounds=bounds(test,(View)field(overlay,"recognized"));gesture(test,sourceBounds.centerX(),sourceBounds.centerY(),sourceBounds.centerX(),sourceBounds.centerY()-100);require(bounds(test,overlay).top<moved.top-60,"Dragging source text failed");report.put("dragFromSource",true);moved=bounds(test,overlay);
            gesture(test,moved.left+8,moved.top+8,moved.left+8,moved.top+148);
            require(bounds(test,overlay).top>moved.top+90,"Dragging panel padding failed");report.put("dragFromPadding",true);
            Rect r=bounds(test,overlay);tap(test,r.centerX(),r.centerY());require(edge(overlay)==0,"Panel tap unexpectedly tucked");
            gesture(test,r.centerX(),r.centerY(),24,r.centerY());require(edge(overlay)==-1,"Left tuck failed");
            Rect left=bounds(test,overlay);require(left.width()==Ui.dp(c,48)&&left.height()==Ui.dp(c,64),"Hidden panel still blocks screen");report.put("leftHandleBounds",left.toShortString()).put("leftHandleContent",bounds(test,(View)field(overlay,"handle")).toShortString());shot(test,c,"overlay-left");
            test.runOnMainSync(()->{AppPrefs.put(c,"overlayTransparency",65);AppPrefs.put(c,"overlayHeight",320);overlay.show("隐藏期间收到的新字幕","New captions received while tucked");});
            require(((View)field(overlay,"handle")).getAlpha()==1f&&bounds(test,overlay).width()==Ui.dp(c,48),"Text appearance changed hidden handle");
            gesture(test,left.centerX(),left.centerY(),left.centerX(),left.centerY()-180);require(edge(overlay)==-1&&bounds(test,overlay).top<left.top-120,"Handle vertical drag failed");
            left=bounds(test,overlay);tap(test,left.centerX(),left.centerY());require(edge(overlay)==0,"Handle tap failed");
            require("隐藏期间收到的新字幕".contentEquals(((TextView)field(overlay,"translated")).getText()),"Expanded caption is stale");report.put("latestTextOnExpand",true);
            require("New captions received while tucked".contentEquals(((TextView)field(overlay,"recognized")).getText())&&bounds(test,overlay).height()==Ui.dp(c,320),"Source or size stale after expand");report.put("appearanceUpdatesWhileTucked",true);
            r=bounds(test,overlay);gesture(test,r.centerX(),r.centerY(),((Rect)field(overlay,"safe")).right-24,r.centerY());require(edge(overlay)==1,"Right tuck failed");shot(test,c,"overlay-right");
            test.runOnMainSync(()->{overlay.hide();overlay.show("隐藏期间收到的新字幕","New captions received while tucked");});Thread.sleep(200);require(edge(overlay)==1,"Tucked state lost after app switch");
            rotationHost=test.startActivitySync(new Intent(c,MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));final Activity host=rotationHost;
            onMain(test,()->host.setRequestedOrientation(android.content.pm.ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE));test.getUiAutomation().setRotation(android.app.UiAutomation.ROTATION_FREEZE_90);Thread.sleep(1000);
            test.runOnMainSync(overlay::configurationChanged);Thread.sleep(200);Rect landscape=bounds(test,overlay);Rect safe=(Rect)field(overlay,"safe");require(safe.contains(landscape),"Handle offscreen after rotation");report.put("rotationPreservesHandle",true);
            require(safe.width()>safe.height(),"Rotation test did not enter landscape");
            tap(test,landscape.centerX(),landscape.centerY());require(edge(overlay)==0,"Landscape expansion failed");require(safe.contains(bounds(test,overlay)),"Expanded panel offscreen in landscape");
            onMain(test,()->{AppPrefs.put(c,"overlayHeight",400);overlay.show("横屏字幕：高度不会超出屏幕。","Landscape captions stay inside the screen.");});Thread.sleep(200);require(safe.contains(bounds(test,overlay)),"Maximum height exceeds landscape screen");report.put("landscapeHeightClamped",true);shot(test,c,"overlay-landscape");
            onMain(test,()->host.setRequestedOrientation(android.content.pm.ActivityInfo.SCREEN_ORIENTATION_PORTRAIT));test.getUiAutomation().setRotation(android.app.UiAutomation.ROTATION_FREEZE_0);Thread.sleep(800);test.runOnMainSync(overlay::configurationChanged);Thread.sleep(200);shot(test,c,"overlay-expanded");
            onMain(test,()->{AppPrefs.put(c,"overlayTransparency",100);overlay.show("透明度测试","Transparency test");});require(((View)field(overlay,"translated")).getAlpha()==0f&&((View)field(overlay,"recognized")).getAlpha()==0f,"100% transparency failed");
            onMain(test,()->{AppPrefs.put(c,"overlayHeight",120);AppPrefs.put(c,"overlayFont",38);AppPrefs.put(c,"overlayTransparency",0);overlay.show("小高度配大字号，原文和译文均应可见。","Large text still leaves room for the source.");});Thread.sleep(200);Rect panelBounds=bounds(test,overlay);require(panelBounds.contains(bounds(test,(View)field(overlay,"recognized")))&&panelBounds.contains(bounds(test,(View)field(overlay,"translated"))),"Large font clips bilingual rows");checkText((TextView)field(overlay,"recognized"));checkText((TextView)field(overlay,"translated"));report.put("largeFontMinimumHeight",true);shot(test,c,"overlay-compact");
            onMain(test,()->overlay.show("等待人声…",""));require(((View)field(overlay,"recognized")).getVisibility()==View.GONE,"Empty source reserved a visible row");
            verifyLargeFontLocales(test,c,report);
            require(engineBefore.equals(c.getSharedPreferences("engine",0).getAll()),"Overlay preferences changed model selections");
            report.put("noFoldOrStopControls",!containsButton((View)field(overlay,"surface"))).put("passed",true);
        }catch(Throwable e){failure=e;try{report.put("passed",false).put("error",android.util.Log.getStackTraceString(e));}catch(Exception ignored){}}
        finally{if(rotationHost!=null){Activity last=rotationHost;onMain(test,()->{last.setRequestedOrientation(android.content.pm.ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED);last.finish();});}test.runOnMainSync(()->{overlay.hide();SharedPreferences.Editor e=AppPrefs.prefs(c).edit();for(String key:new String[]{"english","overlaySource","overlayFont","overlayHeight","overlayTransparency"}){Object value=before.get(key);if(value==null)e.remove(key);else if(value instanceof Boolean)e.putBoolean(key,(Boolean)value);else e.putInt(key,(Integer)value);}e.commit();CaptionState.S.changed();});test.getUiAutomation().setRotation(autoRotate?android.app.UiAutomation.ROTATION_UNFREEZE:rotation);}
        try{report.put("displayPreferencesRestored",before.equals(AppPrefs.prefs(c).getAll()));}catch(Exception ignored){}
        try(FileOutputStream out=new FileOutputStream(new File(c.getExternalFilesDir(null),"overlay-verification.json"))){out.write(report.toString(2).getBytes(java.nio.charset.StandardCharsets.UTF_8));}catch(Exception ignored){}
        Bundle result=new Bundle();result.putString("stream",report.toString());test.finish(failure==null?0:1,result);
    }
    private static void verifySettings(Instrumentation test,Context c,JSONObject report)throws Exception{
        Activity activity=test.startActivitySync(new Intent(c,AppearanceActivity.class).putExtra("floating",true).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        try{
            Thread.sleep(250);View root=activity.getWindow().getDecorView();
            View toggle=find(root,activity.getString(R.string.overlay_source));require(toggle instanceof Switch,"Missing source switch");
            onMain(test,()->{if(((Switch)toggle).isChecked())toggle.performClick();toggle.performClick();});
            setSlider(test,root,activity.getString(R.string.overlay_transparency),40);
            setSlider(test,root,activity.getString(R.string.overlay_height),13);
            require(AppPrefs.overlaySource(c)&&AppPrefs.overlayTransparency(c)==40&&AppPrefs.overlayHeight(c)==240,"Settings controls did not persist values");
            FloatingCaptionPanel preview=(FloatingCaptionPanel)field(activity,"overlayPreview");
            require(preview.recognized.getVisibility()==View.VISIBLE&&Math.abs(preview.translated.getAlpha()-.6f)<.001f,"Live preview differs from preferences");
            onMain(test,()->preview.requestRectangleOnScreen(new Rect(0,0,preview.getWidth(),preview.getHeight()),true));Thread.sleep(250);shot(test,c,"overlay-settings");
            report.put("settingsControlsAndLivePreview",true);
        }finally{onMain(test,activity::finish);}
        Activity reopened=test.startActivitySync(new Intent(c,AppearanceActivity.class).putExtra("floating",true).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        try{Thread.sleep(200);View toggle=find(reopened.getWindow().getDecorView(),reopened.getString(R.string.overlay_source));require(toggle instanceof Switch&&((Switch)toggle).isChecked(),"Reopened settings lost source preference");require(((SeekBar)find(reopened.getWindow().getDecorView(),reopened.getString(R.string.overlay_height))).getProgress()==13,"Reopened settings lost height");report.put("settingsPersistAfterReopen",true);}finally{onMain(test,reopened::finish);}
    }
    private static void setSlider(Instrumentation t,View root,String name,int progress){View v=find(root,name);require(v instanceof SeekBar,"Missing slider: "+name);Bundle b=new Bundle();b.putFloat(android.view.accessibility.AccessibilityNodeInfo.ACTION_ARGUMENT_PROGRESS_VALUE,progress);onMain(t,()->require(v.performAccessibilityAction(android.view.accessibility.AccessibilityNodeInfo.AccessibilityAction.ACTION_SET_PROGRESS.getId(),b),"Slider input failed"));}
    private static View find(View v,String description){if(description.contentEquals(v.getContentDescription()==null?"":v.getContentDescription()))return v;if(v instanceof ViewGroup){ViewGroup g=(ViewGroup)v;for(int i=0;i<g.getChildCount();i++){View found=find(g.getChildAt(i),description);if(found!=null)return found;}}return null;}
    private static void verifyLargeFontLocales(Instrumentation t,Context c,JSONObject report)throws Exception{
        JSONArray checked=new JSONArray();
        for(String tag:new String[]{"en","zh","es","fr","de","pt","ru","ja","ko","ar"}){
            android.content.res.Configuration config=new android.content.res.Configuration(c.getResources().getConfiguration());config.setLocales(LocaleList.forLanguageTags(tag));config.setLayoutDirection(Locale.forLanguageTag(tag));config.fontScale=1.6f;Context local=c.createConfigurationContext(config);
            onMain(t,()->{FloatingCaptionPanel panel=new FloatingCaptionPanel(local,true);panel.bind("Large font caption. 大字号字幕。","Source text. 原文。");panel.measure(View.MeasureSpec.makeMeasureSpec(Ui.dp(local,360),View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(Ui.dp(local,600),View.MeasureSpec.AT_MOST));panel.layout(0,0,panel.getMeasuredWidth(),panel.getMeasuredHeight());require(panel.recognized.getBottom()<=panel.getHeight()-panel.getPaddingBottom(),"Large font source clipped: "+tag);require(panel.translated.getHeight()>=panel.translated.getLineHeight()-4,"Translation line clipped: "+tag);require(panel.getLayoutDirection()==("ar".equals(tag)?View.LAYOUT_DIRECTION_RTL:View.LAYOUT_DIRECTION_LTR),"Wrong layout direction: "+tag);require(!local.getString(R.string.overlay_transparency).isEmpty(),"Missing localized settings");});checked.put(tag);
        }report.put("largeFontLocales",checked);
    }
    private static void onMain(Instrumentation t,Runnable action){Throwable[] error={null};t.runOnMainSync(()->{try{action.run();}catch(Throwable e){error[0]=e;}});if(error[0]!=null)throw new AssertionError("UI check failed",error[0]);}
    private static void checkText(TextView v){android.text.Layout layout=v.getLayout();require(layout!=null,"Text layout missing");int last=Math.min(layout.getLineCount(),v.getMaxLines())-1;require(last<0||layout.getLineBottom(last)<=v.getHeight()-v.getCompoundPaddingTop()-v.getCompoundPaddingBottom()+1,"Visible glyph row is clipped");}
    private static boolean containsButton(View view){if(view instanceof android.widget.Button)return true;if(view instanceof android.view.ViewGroup){android.view.ViewGroup g=(android.view.ViewGroup)view;for(int i=0;i<g.getChildCount();i++)if(containsButton(g.getChildAt(i)))return true;}return false;}
    private static Object field(Object o,String name)throws Exception{Field f=o.getClass().getDeclaredField(name);f.setAccessible(true);return f.get(o);}
    private static int edge(FloatingCaptionOverlay o)throws Exception{return (int)field(o,"edge");}
    private static Rect bounds(Instrumentation t,FloatingCaptionOverlay o)throws Exception{return bounds(t,(View)field(o,"surface"));}
    private static Rect bounds(Instrumentation t,View v){Rect r=new Rect();t.runOnMainSync(()->{int[] p=new int[2];v.getLocationOnScreen(p);r.set(p[0],p[1],p[0]+v.getWidth(),p[1]+v.getHeight());});return r;}
    private static void tap(Instrumentation t,int x,int y)throws Exception{gesture(t,x,y,x,y);}
    private static void gesture(Instrumentation t,int x,int y,int ex,int ey)throws Exception{
        long down=SystemClock.uptimeMillis();for(int i=0;i<=18;i++){int action=i==0?MotionEvent.ACTION_DOWN:i==18?MotionEvent.ACTION_UP:MotionEvent.ACTION_MOVE;MotionEvent e=MotionEvent.obtain(down,SystemClock.uptimeMillis(),action,x+(ex-x)*i/18f,y+(ey-y)*i/18f,0);e.setSource(InputDevice.SOURCE_TOUCHSCREEN);require(t.getUiAutomation().injectInputEvent(e,true),"Touch injection failed");e.recycle();Thread.sleep(20);}Thread.sleep(300);
    }
    private static void shot(Instrumentation t,Context c,String name)throws Exception{Bitmap b=t.getUiAutomation().takeScreenshot();try(FileOutputStream out=new FileOutputStream(new File(c.getExternalFilesDir(null),name+".png"))){b.compress(Bitmap.CompressFormat.PNG,100,out);}b.recycle();}
    private static void require(boolean ok,String message){if(!ok)throw new AssertionError(message);}
}
