package com.localcaption.instant;

interface RecognitionSession extends AutoCloseable {
    void accept(short[] data,int length);
    void end();
    boolean awaitIdle(long timeoutMs)throws InterruptedException;
    void close();
}
