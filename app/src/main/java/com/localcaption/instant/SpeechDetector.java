package com.localcaption.instant;

/** Small local CPU voice gate; selectable speech recognition models all run on HTP. */
public final class SpeechDetector implements AutoCloseable {
    public static final int FRAME=512;
    private final com.k2fsa.sherpa.onnx.Vad vad;
    private final float[] audio=new float[64+FRAME];
    public SpeechDetector(android.content.Context context,byte[] ignored){
        com.k2fsa.sherpa.onnx.SileroVadModelConfig silero=new com.k2fsa.sherpa.onnx.SileroVadModelConfig();silero.setModel("silero-vad-v6.2.onnx");silero.setWindowSize(FRAME);
        com.k2fsa.sherpa.onnx.VadModelConfig config=new com.k2fsa.sherpa.onnx.VadModelConfig();config.setSileroVadModelConfig(silero);config.setNumThreads(1);config.setProvider("cpu");
        vad=new com.k2fsa.sherpa.onnx.Vad(context.getAssets(),config);
    }
    public float probability(short[] frame){if(frame.length!=FRAME)throw new IllegalArgumentException("VAD requires 512 samples");for(int i=0;i<FRAME;i++)audio[64+i]=frame[i]/32768f;float probability=vad.compute(audio);System.arraycopy(audio,audio.length-64,audio,0,64);return probability;}
    @Override public void close(){vad.release();}
}
