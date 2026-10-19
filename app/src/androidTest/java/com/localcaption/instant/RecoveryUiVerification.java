package com.localcaption.instant;

import android.app.*;
import android.content.*;
import android.graphics.Bitmap;
import android.os.*;
import android.view.*;
import android.widget.Button;
import java.io.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.json.*;

/** Exercises the actual recovery button and foreground microphone service; no recording is saved. */
final class RecoveryUiVerification {
    static void run(Instrumentation test,Bundle options){
        Context c=test.getTargetContext();LocalEngine engine=LocalEngine.get(c);JSONObject report=new JSONObject();Throwable failure=null;
        String text=engine.translationChoice(),speech=engine.speechChoice();AtomicInteger screenOffUpdates=new AtomicInteger(),allAudioUpdates=new AtomicInteger();
        CaptionState.Observer observer=()->{if(CaptionState.S.running){allAudioUpdates.incrementAndGet();if(!c.getSystemService(PowerManager.class).isInteractive())screenOffUpdates.incrementAndGet();}};
        try{
            RobustnessVerification.require(!c.getSystemService(KeyguardManager.class).isKeyguardLocked(),"Unlock the phone before UI verification");
            Activity activity=test.startActivitySync(new Intent(c,MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            RobustnessVerification.await(test,engine);
            test.runOnMainSync(()->engine.modelFailed(LocalEngine.FailureStage.TEXT,new IllegalStateException("QA simulated model failure")));
            test.waitForIdleSync();
            ModelRecovery.Plan plan=engine.recoveryPlan();RobustnessVerification.require(plan!=null,"No installed recovery route");
            String label=activity.getString(R.string.recovery_use,ModelRecovery.label(plan));Button[] recovery={null};
            test.runOnMainSync(()->recovery[0]=button(activity.getWindow().getDecorView(),label));
            RobustnessVerification.require(recovery[0]!=null&&recovery[0].isShown()&&recovery[0].isEnabled(),"Recovery button unavailable");
            Bitmap screenshot=test.getUiAutomation().takeScreenshot();if(screenshot!=null){try(FileOutputStream out=new FileOutputStream(new File(c.getExternalFilesDir(null),"recovery-screen.png"))){screenshot.compress(Bitmap.CompressFormat.PNG,100,out);}finally{screenshot.recycle();}}
            test.runOnMainSync(()->RobustnessVerification.require(recovery[0].performClick(),"Recovery click not handled"));RobustnessVerification.await(test,engine);
            RobustnessVerification.require(engine.translationChoice().equals(plan.text()),"UI did not select advertised fallback");
            report.put("recoveryButton",label).put("recoveredText",engine.translationChoice());
            // Use the newly added runtime for the actual service, not just the synthetic PCM test.
            test.runOnMainSync(()->RobustnessVerification.require(engine.selectTranslationChoice(QwenModel.GEMMA4.id),"Cannot select Gemma"));RobustnessVerification.await(test,engine);
            test.runOnMainSync(()->{CaptionState.S.clear();CaptionState.S.observe(observer);activity.startForegroundService(new Intent(activity,CaptionService.class).setAction(CaptionService.START).putExtra("playback",false));});
            long until=SystemClock.elapsedRealtime()+10000;while(!CaptionState.S.running&&SystemClock.elapsedRealtime()<until)Thread.sleep(100);
            RobustnessVerification.require(CaptionState.S.running,"Microphone service did not start: "+CaptionState.S.status);
            shell(test,"input keyevent 3");Thread.sleep(500);shell(test,"input keyevent 223");
            long start=SystemClock.elapsedRealtime();int seconds=Math.max(60,Math.min(300,Integer.parseInt(options.getString("seconds","120"))));
            int offPolls=0,foregroundPolls=0;
            while(SystemClock.elapsedRealtime()-start<seconds*1000L){
                RobustnessVerification.require(CaptionState.S.running,"Background microphone stopped: "+CaptionState.S.status);
                if(!c.getSystemService(PowerManager.class).isInteractive())offPolls++;
                if(BaseActivity.visibleActivities>0)foregroundPolls++;
                report.put("backgroundMicMs",SystemClock.elapsedRealtime()-start).put("screenOffAudioUpdates",screenOffUpdates.get()).put("allAudioUpdates",allAudioUpdates.get()).put("screenOffSamples",offPolls).put("foregroundSamples",foregroundPolls);
                if(offPolls%20==0)RobustnessVerification.write(c,"recovery-ui-progress.json",report);
                Thread.sleep(500);
            }
            RobustnessVerification.require(offPolls>=100,"Screen was not off long enough for this test");
            RobustnessVerification.require(screenOffUpdates.get()>100,"No continuous audio updates while screen was off");
            RobustnessVerification.require(BaseActivity.visibleActivities==0,"App did not go to background");
            report.put("backgroundMicMs",SystemClock.elapsedRealtime()-start).put("screenOffAudioUpdates",screenOffUpdates.get()).put("textModel",engine.translationChoice()).put("speechModel",engine.recognitionBackend()).put("thermal",RuntimeHealth.thermal(c));
        }catch(Throwable e){failure=e;}finally{
            test.runOnMainSync(()->{CaptionState.S.unobserve(observer);c.stopService(new Intent(c,CaptionService.class));});
            try{long until=SystemClock.elapsedRealtime()+20000;while(engine.configurationBusy()&&SystemClock.elapsedRealtime()<until)Thread.sleep(100);RobustnessVerification.restore(test,engine,text,speech);}catch(Throwable e){if(failure==null)failure=e;else failure.addSuppressed(e);}
        }
        try{report.put("passed",failure==null);if(failure!=null)report.put("error",android.util.Log.getStackTraceString(failure));RobustnessVerification.write(c,"recovery-ui-service.json",report);}catch(Exception ignored){}
        Bundle result=new Bundle();result.putString("stream",report.toString());test.finish(failure==null?0:1,result);
    }
    private static Button button(View root,String text){if(root instanceof Button&&text.contentEquals(((Button)root).getText()))return (Button)root;if(root instanceof ViewGroup){ViewGroup group=(ViewGroup)root;for(int i=0;i<group.getChildCount();i++){Button found=button(group.getChildAt(i),text);if(found!=null)return found;}}return null;}
    private static void shell(Instrumentation test,String command)throws Exception{try(ParcelFileDescriptor fd=test.getUiAutomation().executeShellCommand(command);InputStream in=new ParcelFileDescriptor.AutoCloseInputStream(fd)){in.readAllBytes();}}
}
