package com.localcaption.instant;

import android.os.SystemClock;
import java.io.ByteArrayOutputStream;
import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** VAD-approved PCM is sent to the OS through a pipe, for either capture source. */
final class SystemSpeechSession implements RecognitionSession {
    private static final short[] END=new short[0];
    private final LocalEngine engine;
    private final long generation;
    private final int interval;
    private final ExecutorService worker=Executors.newSingleThreadExecutor(r->new Thread(r,"system-speech-audio"));
    private final ArrayDeque<short[]> pending=new ArrayDeque<>();
    private final ByteArrayOutputStream buffered=new ByteArrayOutputStream();
    private volatile SystemSpeech.Stream stream;
    private volatile boolean closed;
    private volatile Throwable error;
    private boolean pumping,released;
    private volatile boolean localFallback;
    private int queuedSamples,samples,revision;
    private volatile long id;
    private long lastTranslation;
    private String lastText="",lastRequested="";
    SystemSpeechSession(LocalEngine engine,int interval){this.engine=engine;this.interval=interval;generation=engine.generation();engine.sessionOpened();}
    private boolean active(){return !closed&&engine.isCurrent(generation);}
    @Override public void accept(short[] data,int length){submit(Arrays.copyOf(data,length));}
    @Override public void end(){submit(END);}
    private synchronized void submit(short[] data){
        if(error!=null)throw new IllegalStateException("语音识别失败",error);
        if(!active())return;
        if(queuedSamples+data.length>16000*30)throw new IllegalStateException("语音处理积压，请停止后重试");
        pending.addLast(data);queuedSamples+=data.length;
        if(!pumping){pumping=true;worker.execute(this::pump);}
    }
    private void pump(){
        while(true){
            short[] data;
            synchronized(this){if(!active()||pending.isEmpty()){pending.clear();queuedSamples=0;if(closed)release();pumping=false;notifyAll();return;}data=pending.removeFirst();queuedSamples-=data.length;}
            try{
                if(data==END){finishSegment();continue;}
                if(samples+data.length>16000*12)finishSegment();
                if(samples==0){
                    id=CaptionState.S.nextId();revision=0;lastText="";lastRequested="";lastTranslation=0;buffered.reset();
                    if(!localFallback){
                        final long segment=id;
                        try{stream=new SystemSpeech.Stream(engine.applicationContext(),engine.systemSpeechLanguage(),text->{if(id==segment&&!localFallback)publish(text,false);});}
                        catch(Exception unavailable){fallback(unavailable);}
                    }
                }
                for(short sample:data){buffered.write(sample&255);buffered.write((sample>>8)&255);}samples+=data.length;
                if(!localFallback)try{stream.accept(data,data.length);}catch(Exception unavailable){fallback(unavailable);}
            }catch(Throwable failure){
                if(active()){error=failure;CaptionState.MAIN.post(()->{if(active())engine.modelFailed(LocalEngine.FailureStage.SPEECH,failure);});}
                synchronized(this){pending.clear();queuedSamples=0;pumping=false;if(closed)release();notifyAll();}return;
            }
        }
    }
    private void fallback(Exception reason)throws Exception{
        localFallback=true;SystemSpeech.Stream old=stream;stream=null;if(old!=null)old.close();
        engine.systemSpeechFailed(reason.getMessage());
    }
    private void finishSegment()throws Exception{
        if(samples==0)return;
        String text="";
        if(!localFallback){
            try{text=stream.finish();}catch(Exception unavailable){fallback(unavailable);}
            finally{SystemSpeech.Stream old=stream;stream=null;if(old!=null)old.close();}
        }
        if(localFallback&&active()){
            byte[] data=buffered.toByteArray();short[] pcm=new short[data.length/2];for(int i=0;i<pcm.length;i++)pcm[i]=(short)((data[i*2]&255)|(data[i*2+1]<<8));
            text=engine.recognizeNpuBlocking(pcm);
        }
        publish(text,true);samples=0;buffered.reset();
    }
    private synchronized void publish(String text,boolean done){
        if(!active()||text.isEmpty())return;
        boolean changed=!text.equals(lastText);if(changed){lastText=text;revision++;}
        if(changed||done)CaptionState.S.english(id,text,done,revision,this::active);
        long now=SystemClock.elapsedRealtime();
        if(!text.equals(lastRequested)&&(done||now-lastTranslation>=interval)){lastRequested=text;lastTranslation=now;engine.translate(id,text,revision);}
    }
    @Override public synchronized boolean awaitIdle(long timeoutMs)throws InterruptedException{
        long until=SystemClock.elapsedRealtime()+timeoutMs;
        while(pumping){long wait=until-SystemClock.elapsedRealtime();if(wait<=0)return false;wait(wait);}
        if(error!=null)throw new IllegalStateException("System speech failed",error);return true;
    }
    private void release(){if(released)return;released=true;SystemSpeech.Stream old=stream;stream=null;if(old!=null)old.close();engine.sessionClosed();}
    @Override public void close(){
        closed=true;SystemSpeech.Stream old=stream;if(old!=null)old.close();
        synchronized(this){pending.clear();if(!pumping)release();}worker.shutdown();
    }
}
