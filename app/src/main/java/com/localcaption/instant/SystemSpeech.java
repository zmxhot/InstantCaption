package com.localcaption.instant;

import android.content.Context;
import android.content.Intent;
import android.media.AudioFormat;
import android.os.Build;
import android.os.Bundle;
import android.os.Looper;
import android.os.ParcelFileDescriptor;
import android.speech.RecognitionListener;
import android.speech.ModelDownloadListener;
import android.speech.RecognitionSupport;
import android.speech.RecognitionSupportCallback;
import android.speech.RecognizerIntent;
import android.speech.SpeechRecognizer;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

/** Android's on-device recognizer; system pack downloads require an explicit action. */
final class SystemSpeech {
    static final class Support {
        final boolean ready;
        final String language,reason;
        final List<String> installed;
        List<String> downloadable=Collections.emptyList(),pending=Collections.emptyList();
        Support(boolean ready,String language,String reason,List<String> installed){this.ready=ready;this.language=language;this.reason=reason;this.installed=installed;}
        static Support unavailable(String language,String reason){return new Support(false,language,reason,Collections.emptyList());}
    }
    static boolean googleServicesPresent(Context context){
        try{return context.getPackageManager().getApplicationInfo("com.google.android.gms",0).enabled;}
        catch(android.content.pm.PackageManager.NameNotFoundException|RuntimeException absent){return false;}
    }
    static String matchLanguage(String requested,List<String> installed){
        String language=NpuModel.canonicalLanguage(requested);
        for(String tag:installed)if(tag.equalsIgnoreCase(requested))return tag;
        for(String tag:installed)if(NpuModel.canonicalLanguage(tag).equals(language))return tag;
        return null;
    }
    @android.annotation.TargetApi(33)
    static Intent intent(String language,ParcelFileDescriptor audio){
        return new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL,RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE,language)
            .putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE,true)
            .putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS,true)
            .putExtra(RecognizerIntent.EXTRA_MAX_RESULTS,1)
            .putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE,audio)
            .putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_CHANNEL_COUNT,1)
            .putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_ENCODING,AudioFormat.ENCODING_PCM_16BIT)
            .putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_SAMPLING_RATE,16000)
            .putExtra(RecognizerIntent.EXTRA_SEGMENTED_SESSION,RecognizerIntent.EXTRA_AUDIO_SOURCE);
    }
    static Support probe(Context context,String language)throws Exception{
        if(Build.VERSION.SDK_INT<33)return Support.unavailable(language,"android-version");
        if(!SpeechRecognizer.isOnDeviceRecognitionAvailable(context))return Support.unavailable(language,"no-on-device-service");
        CountDownLatch done=new CountDownLatch(1);AtomicBoolean finished=new AtomicBoolean();
        Support[] result={Support.unavailable(language,"support-timeout")};
        SpeechRecognizer[] client={null};ParcelFileDescriptor[] pipe=ParcelFileDescriptor.createPipe();
        try{
            onMain(()->{
                client[0]=SpeechRecognizer.createOnDeviceSpeechRecognizer(context);
                client[0].setRecognitionListener(new Listener());
                client[0].checkRecognitionSupport(intent(language,pipe[0]),context.getMainExecutor(),new RecognitionSupportCallback(){
                    public void onSupportResult(RecognitionSupport support){
                        if(finished.compareAndSet(false,true)){
                            List<String> installed=new ArrayList<>(support.getInstalledOnDeviceLanguages());
                            String match=matchLanguage(language,installed);
                            result[0]=new Support(match!=null,match==null?language:match,match==null?"language-not-installed":"ready",installed);
                            result[0].downloadable=new ArrayList<>(support.getSupportedOnDeviceLanguages());result[0].pending=new ArrayList<>(support.getPendingOnDeviceLanguages());done.countDown();
                        }
                    }
                    public void onError(int error){if(finished.compareAndSet(false,true)){result[0]=Support.unavailable(language,"support-error-"+error);done.countDown();}}
                });return null;
            });
            done.await(4,TimeUnit.SECONDS);finished.set(true);return result[0];
        }finally{closeFd(pipe[0]);closeFd(pipe[1]);onMain(()->{if(client[0]!=null)client[0].destroy();return null;});}
    }
    static void download(Context context,Support support)throws Exception{
        if(Build.VERSION.SDK_INT<33)throw new IOException("System offline audio requires Android 13");
        String tag=matchLanguage(support.language,support.downloadable);
        if(tag==null)throw new IOException("此语言没有可下载的系统离线语音包");
        SpeechRecognizer[] client={null};
        android.os.PowerManager.WakeLock wake=context.getSystemService(android.os.PowerManager.class).newWakeLock(android.os.PowerManager.PARTIAL_WAKE_LOCK,"instant:system-speech-pack");
        Intent downloadIntent=new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL,RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE,tag).putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE,true);
        CountDownLatch done=new CountDownLatch(1);IOException[] failure={null};
        wake.acquire(100000);
        try{
            onMain(()->{
                client[0]=SpeechRecognizer.createOnDeviceSpeechRecognizer(context);client[0].setRecognitionListener(new Listener());
                if(Build.VERSION.SDK_INT>=34)client[0].triggerModelDownload(downloadIntent,context.getMainExecutor(),new ModelDownloadListener(){
                    public void onProgress(int percent){CaptionState.S.status("正在准备系统离线语音包 · "+percent+"%");}
                    public void onSuccess(){done.countDown();}
                    public void onScheduled(){failure[0]=new IOException("系统已安排语音包下载，完成后重新检查即可");done.countDown();}
                    public void onError(int error){failure[0]=new IOException("系统语音包下载未完成 ("+error+")");done.countDown();}
                });
                else{client[0].triggerModelDownload(downloadIntent);done.countDown();}
                return null;
            });
            if(!done.await(90,TimeUnit.SECONDS))throw new IOException("系统语音包仍在准备，可稍后重新检查");
            if(failure[0]!=null)throw failure[0];
        }finally{try{onMain(()->{if(client[0]!=null)client[0].destroy();return null;});}finally{if(wake.isHeld())wake.release();}}
    }
    private static <T>T onMain(Callable<T> action)throws Exception{
        if(Looper.myLooper()==Looper.getMainLooper())return action.call();
        FutureTask<T> task=new FutureTask<>(action);CaptionState.MAIN.post(task);
        try{return task.get(5,TimeUnit.SECONDS);}catch(Exception error){task.cancel(false);throw error;}
    }
    private static void closeFd(ParcelFileDescriptor fd){if(fd!=null)try{fd.close();}catch(IOException ignored){}}
    private static class Listener implements RecognitionListener{
        public void onReadyForSpeech(Bundle b){} public void onBeginningOfSpeech(){} public void onRmsChanged(float rms){}
        public void onBufferReceived(byte[] b){} public void onEndOfSpeech(){} public void onError(int error){}
        public void onResults(Bundle b){} public void onPartialResults(Bundle b){} public void onEvent(int type,Bundle b){}
    }
    static final class Stream implements AutoCloseable{
        private final ParcelFileDescriptor[] pipe;
        private final FileOutputStream output;
        private volatile SpeechRecognizer client;
        private final CountDownLatch done=new CountDownLatch(1);
        private final AtomicBoolean closed=new AtomicBoolean();
        private final Consumer<String> partial;
        private final StringBuilder completed=new StringBuilder();
        private volatile String text="";
        private volatile IOException failure;
        private volatile boolean segmented;
        private final Runnable watchdog=()->fail(new IOException("System recognition timed out"));
        Stream(Context context,String language,Consumer<String> partial)throws Exception{
            if(Build.VERSION.SDK_INT<33)throw new IOException("System offline audio requires Android 13");
            this.partial=partial;pipe=ParcelFileDescriptor.createPipe();output=new ParcelFileDescriptor.AutoCloseOutputStream(pipe[1]);
            int flags=android.system.Os.fcntlInt(output.getFD(),android.system.OsConstants.F_GETFL,0);
            android.system.Os.fcntlInt(output.getFD(),android.system.OsConstants.F_SETFL,flags|android.system.OsConstants.O_NONBLOCK);
            SpeechRecognizer created=null;
            try{
                created=onMain(()->{
                    SpeechRecognizer value=SpeechRecognizer.createOnDeviceSpeechRecognizer(context);
                    value.setRecognitionListener(new Listener(){
                        @Override public void onPartialResults(Bundle b){publish(b,false);}
                        @Override public void onSegmentResults(Bundle b){segmented=true;publish(b,true);}
                        @Override public void onEndOfSegmentedSession(){segmented=true;done.countDown();}
                        @Override public void onResults(Bundle b){fail(new IOException("System recognizer does not support external segmented audio"));}
                        @Override public void onError(int error){
                            if(error==SpeechRecognizer.ERROR_NO_MATCH||error==SpeechRecognizer.ERROR_SPEECH_TIMEOUT){done.countDown();}
                            else fail(new IOException("System recognition error "+error));
                        }
                    });
                    try{value.startListening(intent(language,pipe[0]));return value;}
                    catch(RuntimeException error){value.destroy();throw error;}
                });
                client=created;CaptionState.MAIN.postDelayed(watchdog,30000);
            }catch(Exception error){closeFd(pipe[0]);closeFd(pipe[1]);if(created!=null){SpeechRecognizer value=created;onMain(()->{value.destroy();return null;});}throw error;}
        }
        private void publish(Bundle bundle,boolean complete){
            if(closed.get()||failure!=null)return;
            ArrayList<String> results=bundle.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION);
            if(results==null||results.isEmpty())return;
            String words=results.get(0).trim();if(words.isEmpty())return;
            String candidate=completed.toString()+words;
            if(complete)completed.append(words).append(' ');
            text=candidate.trim();partial.accept(text);
        }
        private void fail(IOException error){if(closed.get())return;failure=error;closeFd(pipe[0]);closeFd(pipe[1]);done.countDown();SpeechRecognizer value=client;if(value!=null)CaptionState.MAIN.post(value::cancel);}
        void accept(short[] samples,int length)throws IOException{
            if(failure!=null)throw failure;if(closed.get())throw new IOException("Recognition stream closed");
            byte[] bytes=new byte[length*2];for(int i=0;i<length;i++){bytes[i*2]=(byte)samples[i];bytes[i*2+1]=(byte)(samples[i]>>8);}
            int offset=0;long until=android.os.SystemClock.elapsedRealtime()+5000;
            while(offset<bytes.length){
                if(failure!=null)throw failure;if(closed.get())throw new IOException("Recognition stream closed");
                try{offset+=android.system.Os.write(output.getFD(),bytes,offset,bytes.length-offset);}
                catch(android.system.ErrnoException error){
                    if(error.errno!=android.system.OsConstants.EAGAIN&&error.errno!=android.system.OsConstants.EINTR)throw new IOException(error);
                    if(android.os.SystemClock.elapsedRealtime()>=until)throw new IOException("System recognizer did not consume external audio");
                    try{Thread.sleep(10);}catch(InterruptedException interrupted){Thread.currentThread().interrupt();throw new IOException(interrupted);}
                }
            }
        }
        String finish()throws Exception{
            output.close();if(!done.await(8,TimeUnit.SECONDS))throw new IOException("System recognition result timed out");
            if(failure!=null)throw failure;
            if(!segmented&&!text.isEmpty())throw new IOException("External audio result was not segmented");
            return text;
        }
        @Override public void close(){
            if(!closed.compareAndSet(false,true))return;
            CaptionState.MAIN.removeCallbacks(watchdog);closeFd(pipe[0]);closeFd(pipe[1]);done.countDown();
            CaptionState.MAIN.post(()->{SpeechRecognizer value=client;if(value!=null){value.cancel();value.destroy();}});
        }
    }
}
