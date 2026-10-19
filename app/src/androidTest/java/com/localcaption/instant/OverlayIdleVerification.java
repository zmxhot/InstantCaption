package com.localcaption.instant;

import android.app.*;
import android.content.*;
import android.os.*;
import android.view.*;
import android.widget.TextView;
import java.io.*;
import java.lang.reflect.*;
import java.util.*;
import org.json.JSONObject;

/** Real Handler/window expiry with synthetic captions; no microphone capture or model downloads. */
final class OverlayIdleVerification {
    private static final class Host extends CaptionService {
        Host(Context c){attachBaseContext(c);}
    }
    static void run(Instrumentation t,Bundle options){
        Context c=t.getTargetContext();JSONObject report=new JSONObject();Throwable failure=null;
        Host service=new Host(c);FloatingCaptionOverlay overlay=new FloatingCaptionOverlay(c);
        ArrayList<CaptionState.Line> saved=new ArrayList<>(CaptionState.S.lines);
        boolean oldOverlay=CaptionState.S.overlay;Object oldSource=AppPrefs.prefs(c).getAll().get("overlaySource");
        boolean attached=false;
        try{
            require(!CaptionState.S.running,"Stop capture before this test");
            require(!c.getSystemService(KeyguardManager.class).isKeyguardLocked(),"Unlock phone before verification");
            require(android.provider.Settings.canDrawOverlays(c),"Grant floating caption permission first");
            verifyGate(t,c,report);
            t.sendKeyDownUpSync(KeyEvent.KEYCODE_HOME);Thread.sleep(300);
            set(service,"overlay",overlay);
            onMain(t,()->{AppPrefs.prefs(c).edit().putBoolean("overlaySource",true).commit();CaptionState.S.running=true;CaptionState.S.overlay=true;CaptionState.S.observe(service);});attached=true;
            long id=CaptionState.S.nextId(),started=SystemClock.elapsedRealtime();
            onMain(t,()->hear(service));
            CaptionState.S.english(id,"A caption kept on the main screen.",false,1);flush(t);
            CaptionState.S.chinese(id,"主界面保留这条字幕。",1,1);flush(t);
            onMain(t,()->require(!text(overlay,"translated").isEmpty()&&!text(overlay,"recognized").isEmpty(),"Bilingual caption missing"));
            // Frequent observer/meter notifications and late translations must not restart the deadline.
            while(SystemClock.elapsedRealtime()-started<55_000){
                Thread.sleep(500);
                onMain(t,CaptionState.S::changed);
                if(SystemClock.elapsedRealtime()-started>30_000)CaptionState.S.chinese(id,"主界面保留这条字幕。",1,1);
            }
            onMain(t,()->{require(!text(overlay,"translated").isEmpty(),"Caption expired before 60 seconds");((View)get(overlay,"surface")).performAccessibilityAction(0x01020002,null);});
            Thread.sleep(250);
            String[] history={""};onMain(t,()->history[0]=CaptionState.S.transcript());
            // No changed() calls here: the service's scheduled callback must clear the tucked window.
            while(SystemClock.elapsedRealtime()-started<60_500)Thread.sleep(100);
            onMain(t,()->{
                require((int)get(overlay,"edge")==1,"Timeout expanded the edge handle");
                require(text(overlay,"translated").isEmpty()&&text(overlay,"recognized").isEmpty(),"Timer did not clear both texts");
                ((View)get(overlay,"surface")).performClick();
                require(text(overlay,"translated").isEmpty(),"Expanding revived stale text");
                require(history[0].equals(CaptionState.S.transcript()),"Timeout changed main-screen history");
            });
            report.put("realWaitMs",SystemClock.elapsedRealtime()-started).put("clearsBothRowsAfter60Seconds",true).put("mainHistoryPreserved",true).put("tuckedCaptionCleared",true).put("meterAndTranslationDoNotResetDeadline",true);
            CaptionState.S.chinese(id,"迟到的旧译文",1,1);flush(t);
            onMain(t,()->require(text(overlay,"translated").isEmpty(),"Late translation revived expired text"));
            onMain(t,()->{overlay.hide();service.changed();service.onConfigurationChanged(c.getResources().getConfiguration());require(text(overlay,"translated").isEmpty(),"Reopening/rotation revived text");});
            onMain(t,()->{hear(service);service.changed();require(text(overlay,"translated").isEmpty(),"New voice alone revived previous caption");});
            long next=CaptionState.S.nextId();
            // Identical words in a genuinely new utterance must still appear.
            CaptionState.S.english(next,"A caption kept on the main screen.",true,1);flush(t);
            CaptionState.S.chinese(next,"新的语音继续显示字幕。",1,1);flush(t);
            onMain(t,()->require("新的语音继续显示字幕。".equals(text(overlay,"translated")),"New utterance did not resume captions"));
            report.put("lateTranslationStaysCleared",true).put("reopenAndRotationStayCleared",true).put("newSpeechResumesIncludingRepeatedWords",true).put("passed",true);
        }catch(Throwable e){failure=e;try{report.put("passed",false).put("error",android.util.Log.getStackTraceString(e));}catch(Exception ignored){}}
        finally{
            if(attached)onMain(t,()->{
                CaptionState.S.running=false;service.changed();CaptionState.S.unobserve(service);overlay.hide();
                require(!CaptionState.MAIN.hasCallbacks((Runnable)get(service,"idleCheck")),"Stopped service retained its timer");
                CaptionState.S.overlay=oldOverlay;CaptionState.S.lines.clear();CaptionState.S.lines.addAll(saved);
                SharedPreferences.Editor edit=AppPrefs.prefs(c).edit();if(oldSource==null)edit.remove("overlaySource");else edit.putBoolean("overlaySource",(Boolean)oldSource);edit.commit();CaptionState.S.changed();
            });
        }
        try(FileOutputStream out=new FileOutputStream(new File(c.getExternalFilesDir(null),"overlay-idle-verification.json"))){out.write(report.toString(2).getBytes(java.nio.charset.StandardCharsets.UTF_8));}catch(Exception ignored){}
        Bundle result=new Bundle();result.putString("stream",report.toString());t.finish(failure==null?0:1,result);
    }
    private static void verifyGate(Instrumentation t,Context c,JSONObject report)throws Exception{
        int[] events={0};boolean[] beforeAudio={false};
        try(SpeechGate gate=new SpeechGate(new SpeechDetector(c,null),new SpeechGate.Sink(){
            public void speech(){events[0]++;beforeAudio[0]=true;}
            public void audio(short[] data,int length){require(beforeAudio[0],"Audio reached recognizer before voice notification");}
            public void end(){}
        })){
            gate.accept(new short[16_000*3],16_000*3);require(events[0]==0,"Silence renewed the voice timer");
            byte[] wave;try(InputStream in=t.getContext().getAssets().open("english.wav")){wave=in.readAllBytes();}
            java.nio.ByteBuffer b=java.nio.ByteBuffer.wrap(wave).order(java.nio.ByteOrder.LITTLE_ENDIAN);int p=12;
            while(p+8<=wave.length){int n=b.getInt(p+4);if(wave[p]=='d'&&wave[p+1]=='a'&&wave[p+2]=='t'&&wave[p+3]=='a'){short[] pcm=new short[n/2];for(int i=0;i<pcm.length;i++)pcm[i]=b.getShort(p+8+2*i);gate.accept(pcm,pcm.length);break;}p+=8+n+(n&1);}
            require(events[0]>0,"Confirmed speech did not renew the timer");gate.finish();int count=events[0];
            gate.accept(new short[16_000*3],16_000*3);require(events[0]==count,"Silent tail renewed the timer");
            report.put("silenceDoesNotRefresh",true).put("voiceSignalsBeforeRecognition",true).put("confirmedVoiceEvents",count);
        }
    }
    private static void hear(Host service){try{Method m=CaptionService.class.getDeclaredMethod("heardSpeech",long.class);m.setAccessible(true);m.invoke(service,SystemClock.elapsedRealtime());}catch(Exception e){throw new AssertionError(e);}}
    private static Object get(Object target,String name){try{Class<?> type=target instanceof Host?CaptionService.class:target.getClass();Field f=type.getDeclaredField(name);f.setAccessible(true);return f.get(target);}catch(Exception e){throw new AssertionError(e);}}
    private static void set(Object target,String name,Object value)throws Exception{Field f=CaptionService.class.getDeclaredField(name);f.setAccessible(true);f.set(target,value);}
    private static String text(FloatingCaptionOverlay o,String field){return ((TextView)get(o,field)).getText().toString();}
    private static void flush(Instrumentation t){onMain(t,()->{});}
    private static void onMain(Instrumentation t,Runnable action){Throwable[] error={null};t.runOnMainSync(()->{try{action.run();}catch(Throwable e){error[0]=e;}});if(error[0]!=null)throw new AssertionError(error[0]);}
    private static void require(boolean ok,String message){if(!ok)throw new AssertionError(message);}
}
