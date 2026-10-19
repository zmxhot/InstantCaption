package com.localcaption.instant;

import android.app.Instrumentation;
import android.content.Context;
import android.os.Bundle;
import org.json.*;
import java.io.*;
import java.net.*;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.util.*;
import java.util.zip.*;

/** Actual Android archive extraction plus deterministic interruption/fallback checks. */
final class ModelManagementTest {
    static void run(Instrumentation test){
        Bundle result=new Bundle();JSONObject report=new JSONObject();Context c=test.getTargetContext();
        File workspace=new File(c.getExternalFilesDir(null),"qa-management");
        try{
            workspace.mkdirs();ModelStore.cancel=false;JSONArray archives=new JSONArray();
            for(NpuModel model:NpuModel.values()){
                File archive=new File(new File(c.getExternalFilesDir(null),"qa-archives"),model.id+".archive"),stage=new File(workspace,model.id);stage.mkdirs();long start=android.os.SystemClock.elapsedRealtime();
                ModelStore.unpack(c,model,archive,stage,ModelStore.spec(c,model).getJSONObject("download").getString("format"));
                archives.put(new JSONObject().put("model",model.id).put("allFileHashesVerified",true).put("unpackMs",android.os.SystemClock.elapsedRealtime()-start));ModelStore.erase(c,stage,workspace);
            }
            report.put("archives",archives);
            // A same-size corrupt model must be rejected without touching the installed model.
            File bad=new File(workspace,"bad.zip"),stage=new File(workspace,"corrupt-stage");stage.mkdirs();long tokenSize=0;JSONArray files=ModelStore.spec(c,NpuModel.ZIPFORMER).getJSONArray("files");for(int i=0;i<files.length();i++)if(files.getJSONObject(i).getString("name").equals("tokens.txt"))tokenSize=files.getJSONObject(i).getLong("size");
            try(ZipOutputStream out=new ZipOutputStream(new FileOutputStream(bad))){out.putNextEntry(new ZipEntry("assets/example/tokens.txt"));out.write(new byte[(int)tokenSize]);out.closeEntry();}
            boolean rejected=false;try{ModelStore.unpack(c,NpuModel.ZIPFORMER,bad,stage,"zip");}catch(IOException e){rejected=e.getMessage().contains("checksum");}require(rejected,"Corrupt model accepted");require(ModelStore.installed(c,NpuModel.ZIPFORMER),"Installed model changed on rejection");report.put("corruptFileRejected",true);
            boolean pathRejected=false;try{ModelStore.erase(c,new File(workspace,"../outside"),workspace);}catch(IOException e){pathRejected=true;}require(pathRejected,"Unsafe delete accepted");report.put("unsafeDeleteRejected",true);
            boolean running=CaptionState.S.running;CaptionState.S.running=true;require(!ModelStore.reserve(NpuModel.ZIPFORMER,"delete"),"Deletion allowed during capture");CaptionState.S.running=running;report.put("captureBlocksDeletion",true);
            networkChecks(workspace,report);
            report.put("passed",true);try(FileOutputStream out=new FileOutputStream(new File(c.getExternalFilesDir(null),"model-management-verification.json"))){out.write(report.toString(2).getBytes(java.nio.charset.StandardCharsets.UTF_8));}
            ModelStore.erase(c,workspace,c.getExternalFilesDir(null));result.putString("stream",report.toString(2));test.finish(0,result);
        }catch(Throwable e){result.putString("stream",android.util.Log.getStackTraceString(e));test.finish(1,result);}
    }
    private static void require(boolean b,String message){if(!b)throw new AssertionError(message);}
    private static final ArrayDeque<Reply> replies=new ArrayDeque<>();
    private static final List<String> ranges=new ArrayList<>();
    private static final class Reply {int code;byte[] body;String range;boolean drop;Reply(int code,byte[] body,String range,boolean drop){this.code=code;this.body=body;this.range=range;this.drop=drop;}}
    static void networkChecks(File workspace,JSONObject report) throws Exception{
        URL.setURLStreamHandlerFactory(protocol->protocol.equals("https")?new URLStreamHandler(){@Override protected URLConnection openConnection(URL url){Reply r=replies.remove();return new HttpURLConnection(url){@Override public void connect(){}@Override public void disconnect(){}@Override public boolean usingProxy(){return false;}@Override public int getResponseCode(){ranges.add(getRequestProperty("Range"));return r.code;}@Override public String getHeaderField(String n){return n.equals("Content-Range")?r.range:null;}@Override public InputStream getInputStream(){return new ByteArrayInputStream(r.body){@Override public synchronized int read(byte[] b,int off,int len){if(r.drop&&pos>=r.body.length)throw new java.io.UncheckedIOException(new IOException("Connection dropped"));return super.read(b,off,len);}};}};}}:null);
        byte[] data=new byte[32768];new Random(17).nextBytes(data);StringBuilder hash=new StringBuilder();for(byte b:MessageDigest.getInstance("SHA-256").digest(data))hash.append(String.format(java.util.Locale.ROOT,"%02x",b&255));
        JSONObject catalog=new JSONObject().put("size",data.length).put("sha256",hash.toString()).put("urls",new JSONArray().put(new JSONObject().put("name","official").put("url","https://official.invalid/model")).put(new JSONObject().put("name","backup").put("url","https://backup.invalid/model")));
        File part=new File(workspace,"network.part");
        replies.add(new Reply(503,new byte[0],null,false));replies.add(new Reply(200,data,null,false));ModelStore.fetchArchive(catalog,0,part);require(ModelStore.channel.equals("backup"),"Fallback not used");require(Arrays.equals(Files.readAllBytes(part.toPath()),data),"Fallback content mismatch");report.put("automaticFallbackOn503",true);
        part.delete();replies.add(new Reply(200,Arrays.copyOf(data,1024),null,true));replies.add(new Reply(206,Arrays.copyOfRange(data,1024,data.length),"bytes 1024-32767/32768",false));ModelStore.fetchArchive(catalog,0,part);require(ranges.get(ranges.size()-1).equals("bytes=1024-"),"Resume offset incorrect");require(Arrays.equals(Files.readAllBytes(part.toPath()),data),"Resume content mismatch");report.put("interruptedDownloadResumed",true);
        Files.write(part.toPath(),Arrays.copyOf(data,512));replies.add(new Reply(200,data,null,false));ModelStore.download("https://test.invalid/no-range",part,data.length);require(Arrays.equals(Files.readAllBytes(part.toPath()),data),"200 response was appended");report.put("serverIgnoringRangeHandled",true);
        part.delete();byte[] wrong=data.clone();wrong[0]^=1;replies.add(new Reply(200,wrong,null,false));replies.add(new Reply(200,data,null,false));ModelStore.fetchArchive(catalog,0,part);require(Arrays.equals(Files.readAllBytes(part.toPath()),data),"Corrupt archive accepted");report.put("archiveHashFailureFallback",true);
        ModelStore.cancel=true;boolean cancelled=false;try{ModelStore.fetchArchive(catalog,0,part);}catch(java.util.concurrent.CancellationException e){cancelled=true;}finally{ModelStore.cancel=false;}require(cancelled,"Cancellation ignored");report.put("cancelHonored",true);
    }
}
