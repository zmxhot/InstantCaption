package com.localcaption.instant;

import ai.onnxruntime.*;
import java.io.*;
import java.nio.*;
import java.util.*;
import java.util.concurrent.CancellationException;
import java.util.function.BooleanSupplier;

/** Marian INT8 encoder + FP32 decoder. Native KV tensors; no service or network calls. */
final class MarianTranslator implements AutoCloseable {
    private final OrtEnvironment env=OrtEnvironment.getEnvironment();
    private final OrtSession encoder,decoder;
    private final MarianTokenizer tokenizer;
    private final int attentionHeads,headDim,startToken,eosToken,padToken;
    private final String sourcePrefix;
    private final boolean joinWithSpace;
    private final LinkedHashMap<String,String> cache=new LinkedHashMap<String,String>(64,.75f,true){
        @Override protected boolean removeEldestEntry(Map.Entry<String,String> e){return size()>96;}
    };
    MarianTranslator(File dir) throws Exception {this(dir,null);}
    MarianTranslator(File dir,org.json.JSONObject spec) throws Exception {
        tokenizer=new MarianTokenizer(dir);
        org.json.JSONObject runtime=spec==null?null:spec.optJSONObject("runtime");
        attentionHeads=runtime==null?8:runtime.getInt("decoderAttentionHeads");
        headDim=runtime==null?64:runtime.getInt("decoderHeadDim");
        startToken=runtime==null?65000:runtime.getInt("decoderStartTokenId");
        eosToken=runtime==null?0:runtime.getInt("eosTokenId");
        padToken=runtime==null?65000:runtime.getInt("padTokenId");
        sourcePrefix=runtime==null?">>cmn_Hans<< ":runtime.getString("sourcePrefix");
        joinWithSpace=runtime!=null&&runtime.optBoolean("joinWithSpace",true);
        if(attentionHeads<=0||headDim<=0||startToken<0||startToken>=tokenizer.vocabularySize()
            ||eosToken!=tokenizer.eosId||padToken!=tokenizer.padId
            ||(runtime!=null&&runtime.getInt("vocabSize")!=tokenizer.vocabularySize()))
            throw new IOException("Translation model configuration does not match its tokenizer");
        if(!sourcePrefix.isEmpty()&&tokenizer.tokenId(sourcePrefix.trim())==tokenizer.unkId)
            throw new IOException("Translation target prefix is absent from the model vocabulary");
        try(OrtSession.SessionOptions options=new OrtSession.SessionOptions()){
            options.setIntraOpNumThreads(3);options.setInterOpNumThreads(1);
            options.setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT);
            options.addConfigEntry("session.intra_op.allow_spinning","0");
            encoder=env.createSession(new File(dir,runtime==null?"encoder_model_quantized.onnx":runtime.getString("encoder")).getPath(),options);
            // Extended transformer fusion misoptimizes this merged decoder (Slice shape error).
            // Quantizing its LM head also caused repetitions in short-sentence regression tests.
            options.setOptimizationLevel(OrtSession.SessionOptions.OptLevel.BASIC_OPT);
            try{decoder=env.createSession(new File(dir,runtime==null?"decoder_model_merged.onnx":runtime.getString("decoder")).getPath(),options);}catch(Exception e){encoder.close();throw e;}
        }
    }
    String translate(String input) throws Exception {return translate(input,()->true);}
    synchronized String translate(String input,BooleanSupplier current) throws Exception {
        check(current);
        String text=input.trim();if(text.isEmpty())return "";
        String cached=cache.get(text);if(cached!=null)return cached;
        // Caption segments are short. Split exceptional long inputs at word boundaries.
        StringBuilder out=new StringBuilder();StringBuilder part=new StringBuilder();
        for(String word:text.split("\\s+")){
            if(part.length()+word.length()>350&&part.length()>0){appendSentence(out,sentence(part.toString(),current));part.setLength(0);}
            if(part.length()>0)part.append(' ');part.append(word);
        }
        if(part.length()>0)appendSentence(out,sentence(part.toString(),current));
        String result=out.toString().trim();cache.put(text,result);return result;
    }
    private void appendSentence(StringBuilder out,String sentence){if(joinWithSpace&&out.length()>0&&!sentence.isEmpty())out.append(' ');out.append(sentence);}
    private static void check(BooleanSupplier current){if(Thread.currentThread().isInterrupted()||!current.getAsBoolean())throw new CancellationException("Translation cancelled");}
    private String sentence(String text,BooleanSupplier stillCurrent) throws Exception {
        check(stillCurrent);
        long[] ids=tokenizer.encode(sourcePrefix+text);
        if(ids.length>480)throw new IOException("Translation input is too long");
        long[] mask=new long[ids.length];Arrays.fill(mask,1);
        try(OnnxTensor input=OnnxTensor.createTensor(env,LongBuffer.wrap(ids),new long[]{1,ids.length});
            OnnxTensor attention=OnnxTensor.createTensor(env,LongBuffer.wrap(mask),new long[]{1,ids.length});
            OnnxTensor empty=OnnxTensor.createTensor(env,FloatBuffer.allocate(0),new long[]{1,attentionHeads,0,headDim});
            OnnxTensor branchFalse=OnnxTensor.createTensor(env,new boolean[]{false});
            OnnxTensor branchTrue=OnnxTensor.createTensor(env,new boolean[]{true});
            OrtSession.Result encoded=encoder.run(Map.of("input_ids",input,"attention_mask",attention))){
            Map<String,OnnxTensor> feed=new HashMap<>();feed.put("encoder_attention_mask",attention);
            feed.put("encoder_hidden_states",(OnnxTensor)encoded.get(0));
            for(String name:decoder.getInputNames())if(name.startsWith("past_key_values."))feed.put(name,empty);
            OrtSession.Result first=null,previous=null;
            List<Integer> generated=new ArrayList<>();long next=startToken;
            try{
                int limit=Math.min(192,Math.max(40,ids.length*3));
                for(int step=0;step<limit;step++){
                    check(stillCurrent);
                    feed.put("use_cache_branch",step==0?branchFalse:branchTrue);
                    OrtSession.Result current;
                    try(OnnxTensor token=OnnxTensor.createTensor(env,LongBuffer.wrap(new long[]{next}),new long[]{1,1})){
                        feed.put("input_ids",token);current=decoder.run(feed);
                    }
                    if(first==null)first=current;
                    if(previous!=null&&previous!=first)previous.close();previous=current;
                    OnnxTensor logits=(OnnxTensor)current.get("logits").orElseThrow();
                    FloatBuffer scores=logits.getFloatBuffer();int vocab=(int)logits.getInfo().getShape()[2];
                    int base=scores.limit()-vocab;float best=-Float.MAX_VALUE;int chosen=0;
                    for(int j=0;j<vocab;j++){if(j==padToken)continue;float score=scores.get(base+j);if(score>best){best=score;chosen=j;}}
                    for(String name:decoder.getInputNames())if(name.startsWith("past_key_values.")){
                        OrtSession.Result owner=name.contains(".encoder.")?first:current;
                        feed.put(name,(OnnxTensor)owner.get(name.replace("past_key_values.","present.")).orElseThrow());
                    }
                    if(chosen==eosToken)break;
                    generated.add(chosen);next=chosen;
                    // Stop a degenerate loop: three identical consecutive token phrases.
                    // This is a decode guard, never a hard-coded phrase translation.
                    if(trimLoop(generated))break;
                }
                return tokenizer.decode(generated);
            }finally{if(previous!=null&&previous!=first)previous.close();if(first!=null)first.close();}
        }
    }
    private static boolean trimLoop(List<Integer> ids){
        int size=ids.size();
        for(int n=2;n<=Math.min(12,size/3);n++){
            boolean repeated=true;
            for(int i=0;i<n;i++)if(!ids.get(size-n+i).equals(ids.get(size-2*n+i))||!ids.get(size-n+i).equals(ids.get(size-3*n+i))){repeated=false;break;}
            if(repeated){ids.subList(size-2*n,size).clear();return true;}
        }
        return false;
    }
    @Override public synchronized void close() throws OrtException {try{decoder.close();}finally{try{encoder.close();}finally{cache.clear();}}}
}
