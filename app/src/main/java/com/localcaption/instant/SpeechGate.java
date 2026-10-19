package com.localcaption.instant;

import java.util.ArrayDeque;
import java.util.Arrays;

/** Admits confirmed speech, preserves word onsets, and replaces non-speech tails with silence. */
public final class SpeechGate implements AutoCloseable {
    public interface Sink {
        default void speech() {}
        void audio(short[] samples, int length) throws Exception;
        void end() throws Exception;
    }
    private static final int PRE_ROLL = 6; // 192 ms of context prevents clipped first syllables.
    private static final int ATTACK = 3;   // 96 ms confirmation rejects short impacts.
    private static final int RELEASE = 10; // 320 ms of non-speech ends the segment.
    private final SpeechDetector detector;
    private final Sink sink;
    private final ArrayDeque<short[]> history = new ArrayDeque<>();
    private final short[] frame = new short[SpeechDetector.FRAME];
    private final short[] silence = new short[SpeechDetector.FRAME];
    private int fill, attack, quiet;
    private boolean open;
    private float lastProbability;
    private long frames, inferenceNanos, admittedFrames;

    public SpeechGate(SpeechDetector detector, Sink sink) { this.detector=detector; this.sink=sink; }

    public void accept(short[] samples, int length) throws Exception {
        int pos=0;
        while (pos < length) {
            int n=Math.min(length-pos, frame.length-fill);
            System.arraycopy(samples,pos,frame,fill,n); pos+=n; fill+=n;
            if (fill==frame.length) { process(); fill=0; }
        }
    }

    private void process() throws Exception {
        long start=System.nanoTime();
        lastProbability=detector.probability(frame);
        inferenceNanos+=System.nanoTime()-start; frames++;
        if (!open) {
            history.addLast(frame.clone()); if (history.size()>PRE_ROLL) history.removeFirst();
            attack=lastProbability>=0.65f?attack+1:0;
            if (attack>=ATTACK) {
                sink.speech();
                open=true; quiet=0;
                for (short[] context:history) { sink.audio(context,context.length); admittedFrames++; }
                history.clear();
            }
            return;
        }
        quiet=lastProbability>=0.35f?0:quiet+1;
        if(quiet==0)sink.speech();
        // Keep a short natural tail for unvoiced consonants; block sustained background noise.
        sink.audio(quiet<=3?frame:silence,frame.length); admittedFrames++;
        if (quiet>=RELEASE) {
            sink.end(); open=false; quiet=0; attack=0; history.clear();
        }
    }

    public void finish() throws Exception {
        if (fill>0) { Arrays.fill(frame,fill,frame.length,(short)0); process(); fill=0; }
        if (open) { sink.end(); open=false; }
        history.clear(); attack=0; quiet=0;
    }
    public float lastProbability() { return lastProbability; }
    public long frames() { return frames; }
    public long admittedFrames() { return admittedFrames; }
    public double averageInferenceMs() { return frames==0?0:inferenceNanos/1_000_000.0/frames; }
    @Override public void close() throws Exception { detector.close(); }
}
