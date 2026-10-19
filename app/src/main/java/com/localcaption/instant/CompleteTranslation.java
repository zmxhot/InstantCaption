package com.localcaption.instant;

import java.util.concurrent.CancellationException;
import java.util.function.BooleanSupplier;

/** Retries only output-limit failures; a partial translation is never returned. */
final class CompleteTranslation {
    static final class OutputLimit extends Exception {}
    interface Generator { String generate(String input,BooleanSupplier current)throws Exception; }
    private final Generator generator;
    private final BooleanSupplier current;
    private final long deadline=System.nanoTime()+20_000_000_000L;
    private int attempts;
    private CompleteTranslation(Generator generator,BooleanSupplier current){this.generator=generator;this.current=current;}
    static String translate(String input,BooleanSupplier current,Generator generator)throws Exception{
        if(input.length()>16384)throw new ModelFailure(ModelFailure.Kind.LIMIT);
        CompleteTranslation job=new CompleteTranslation(generator,current);StringBuilder result=new StringBuilder();
        for(int start=0;start<input.length();){
            int end=Math.min(input.length(),start+320);
            if(end<input.length())end=start+boundary(input.substring(start,end),end-start-1);
            append(result,job.part(input.substring(start,end),0));start=end;
        }
        job.check();return result.toString().trim();
    }
    private void check()throws ModelFailure{
        if(!current.getAsBoolean())throw new CancellationException();
        if(System.nanoTime()>=deadline)throw new ModelFailure(ModelFailure.Kind.TIMEOUT);
    }
    private String part(String input,int depth)throws Exception{
        check();if(input.trim().isEmpty())return "";
        if(++attempts>64)throw new ModelFailure(ModelFailure.Kind.LIMIT);
        try{
            String result=generator.generate(input,()->current.getAsBoolean()&&System.nanoTime()<deadline);
            check();if(result.trim().isEmpty())throw new ModelFailure(ModelFailure.Kind.RUNTIME);return result;
        }catch(CancellationException e){check();throw e;}
        catch(OutputLimit limit){
            check();if(depth>=5||input.codePointCount(0,input.length())<16)throw new ModelFailure(ModelFailure.Kind.LIMIT);
            int split=boundary(input,input.length()/2);StringBuilder output=new StringBuilder();
            append(output,part(input.substring(0,split),depth+1));append(output,part(input.substring(split),depth+1));return output.toString();
        }
    }
    // Keep sentence/word boundaries where possible and never split a surrogate pair.
    private static int boundary(String input,int preferred){
        int limit=Math.min(input.length()-1,preferred);
        for(String separators:new String[]{".!?。！？;；\n"," \t"})
            for(int i=limit;i>=Math.max(1,preferred/2);i--)if(separators.indexOf(input.charAt(i))>=0)return i+1;
        int split=Math.max(1,limit);if(Character.isHighSurrogate(input.charAt(split-1)))split--;
        return Math.max(1,split);
    }
    private static void append(StringBuilder output,String part){if(part.isBlank())return;if(output.length()>0)output.append(' ');output.append(part.trim());}
}
