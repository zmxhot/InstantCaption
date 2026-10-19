package com.localcaption.instant;

import android.os.SystemClock;
import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Audio capture only queues confirmed speech. One worker owns all stream state and decoding. */
final class NpuSession implements RecognitionSession {
    private final LocalEngine engine;
    private final NpuRecognizer model;
    private final long generation;
    private final int interval;
    private final ExecutorService worker=Executors.newSingleThreadExecutor(r->new Thread(r,"npu-inference"));
    private final ArrayDeque<short[]> pending=new ArrayDeque<>();
    private static final short[] END=new short[0];
    private boolean pumping,released;
    private volatile boolean closed;
    private volatile Throwable error;
    private int queuedSamples;
    private NpuRecognizer.Stream stream;
    private long id,lastTranslation;
    private int revision,samples;
    private String lastEnglish="",lastRequested="";
    NpuSession(LocalEngine engine,int interval){this.engine=engine;this.interval=interval;this.model=engine.npuRecognizer();generation=engine.generation();engine.sessionOpened();}
    private boolean active(){return !closed&&engine.isCurrent(generation);}
    public void accept(short[] data,int length){submit(Arrays.copyOf(data,length));}
    public void end(){submit(END);}
    private synchronized void submit(short[] audio){
        if(error!=null)throw new IllegalStateException("语音识别失败",error);
        if(!active())return;
        if(queuedSamples+audio.length>16000*30)throw new IllegalStateException("语音处理积压过多，请停止后选择更小模型");
        queuedSamples+=audio.length;pending.addLast(audio);
        if(!pumping){pumping=true;worker.execute(this::pump);}
    }
    private void pump(){
        while(true){
            short[] audio;
            synchronized(this){if(!active()||pending.isEmpty()){pending.clear();queuedSamples=0;if(closed){release();}pumping=false;notifyAll();return;}audio=pending.removeFirst();queuedSamples-=audio.length;}
            try{
                if(audio==END){finishSegment();continue;}
                if(stream!=null&&samples+audio.length>model.model.segmentSamples)finishSegment();
                if(stream==null){stream=model.createStream();id=CaptionState.S.nextId();revision=0;samples=0;lastEnglish="";lastRequested="";lastTranslation=0;}
                stream.accept(audio,audio.length);samples+=audio.length;publish(false);
                if(samples>=model.model.segmentSamples)finishSegment();
            }catch(Throwable t){if(active()){error=t;CaptionState.MAIN.post(()->{if(active())engine.modelFailed(LocalEngine.FailureStage.SPEECH,t);});}
                synchronized(this){pending.clear();queuedSamples=0;pumping=false;if(closed)release();notifyAll();}return;
            }
        }
    }
    private void finishSegment(){if(stream!=null){stream.finish();publish(true);stream.close();stream=null;}}
    private void publish(boolean done){
        String en=stream.text();if(!active()||en.isEmpty())return;
        boolean changed=!lastEnglish.equals(en);if(changed){lastEnglish=en;revision++;}
        if(changed||done)CaptionState.S.english(id,en,done,revision,this::active);
        long now=SystemClock.elapsedRealtime();
        if(!en.equals(lastRequested)&&(done||now-lastTranslation>=interval)){lastRequested=en;lastTranslation=now;engine.translate(id,en,revision);}
        int ms=stream.lastDecodeMs();CaptionState.MAIN.post(()->{if(active()){CaptionState.S.asrMs=ms;CaptionState.S.changed();}});
    }
    public synchronized boolean awaitIdle(long timeout) throws InterruptedException{long until=SystemClock.elapsedRealtime()+timeout;while(pumping){long left=until-SystemClock.elapsedRealtime();if(left<=0)return false;wait(left);}if(error!=null)throw new IllegalStateException("NPU 识别失败",error);return true;}
    private void release(){if(released)return;try{if(stream!=null){stream.close();stream=null;}}finally{released=true;engine.sessionClosed();}}
    @Override public void close(){synchronized(this){closed=true;pending.clear();if(!pumping)release();}worker.shutdown();}
}
