package com.localcaption.instant;

/** The same voice gate feeds every NPU recognizer. */
public final class CaptionPipeline implements AutoCloseable {
    private final RecognitionSession npu;
    private final SpeechGate speechGate;
    public CaptionPipeline(LocalEngine engine,int interval) throws Exception {
        this(engine,interval,()->{});
    }
    public CaptionPipeline(LocalEngine engine,int interval,Runnable onSpeech) throws Exception {
        npu=engine.usesSystemSpeech()?new SystemSpeechSession(engine,interval):new NpuSession(engine,interval);
        try{speechGate=new SpeechGate(engine.speechDetector(),new SpeechGate.Sink(){
            @Override public void speech(){onSpeech.run();}
            @Override public void audio(short[] data,int length){npu.accept(data,length);}
            @Override public void end(){npu.end();}
        });}catch(Exception e){npu.close();throw e;}
    }
    public void accept(short[] data,int length) throws Exception{speechGate.accept(data,length);}
    public float speechProbability(){return speechGate.lastProbability();}
    public double vadInferenceMs(){return speechGate.averageInferenceMs();}
    public long admittedFrames(){return speechGate.admittedFrames();}
    public void finish() throws Exception{speechGate.finish();}
    public boolean awaitIdle(long timeoutMs) throws InterruptedException{return npu.awaitIdle(timeoutMs);}
    public int droppedSegments(){return 0;}
    @Override public void close() throws Exception{try{speechGate.close();}finally{npu.close();}}
}
