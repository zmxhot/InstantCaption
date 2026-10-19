package com.localcaption.instant;

import java.util.concurrent.CancellationException;
import java.util.function.BooleanSupplier;

/** Selects only independent on-device inference. Downloads are managed separately. */
final class TranslationRouter implements AutoCloseable {
    interface Backend extends AutoCloseable{
        String translate(String text,BooleanSupplier current)throws Exception;
        void close()throws Exception;
    }
    interface Factory{
        default boolean passthrough(){return false;}
        default String fallbackName(){return "opus";}
        boolean opusInstalled();
        Backend opus()throws Exception;
    }
    static class ModelMissingException extends Exception{
        ModelMissingException(String message){super(message);}
    }
    private final Factory factory;
    private volatile Backend active;
    private volatile String backend="none";
    TranslationRouter(Factory factory){this.factory=factory;}
    String backend(){return backend;}
    boolean ready(){return active!=null;}

    synchronized void prepare()throws Exception{
        if(factory.passthrough()){
            if("passthrough".equals(backend)&&active!=null)return;
            replace(new Backend(){public String translate(String text,BooleanSupplier current){return text;}public void close(){}},"passthrough");
            return;
        }
        boolean installed=factory.opusInstalled();
        if(installed&&active!=null&&factory.fallbackName().equals(backend))return;
        if(installed){
            Backend candidate=null;
            try{
                candidate=factory.opus();
                candidate.translate("Ready",()->true);
                replace(candidate,factory.fallbackName());
                return;
            }catch(Exception|LinkageError|OutOfMemoryError failure){
                closeQuietly(candidate);close();throw failure;
            }
        }
        close();
        throw new ModelMissingException("本地 AI 翻译模型未安装，请到模型管理下载安装");
    }
    private void replace(Backend next,String name){
        Backend old=active;active=next;backend=name;
        if(old!=next)closeQuietly(old);
    }
    String translate(String text,BooleanSupplier current)throws Exception{
        if(!current.getAsBoolean())throw new CancellationException();
        Backend selected=active;
        if(selected==null)throw new IllegalStateException("当前语言的本地 AI 翻译模型尚未准备好");
        String result=selected.translate(text,current);
        if(!current.getAsBoolean())throw new CancellationException();
        return result;
    }
    synchronized void unloadOpus(){if("opus".equals(backend))close();}
    @Override public synchronized void close(){Backend old=active;active=null;backend="none";closeQuietly(old);}
    private static void closeQuietly(Backend value){if(value!=null)try{value.close();}catch(Exception|LinkageError ignored){}}
}
