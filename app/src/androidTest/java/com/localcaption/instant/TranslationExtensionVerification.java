package com.localcaption.instant;

import android.app.Instrumentation;
import android.content.Context;
import android.os.*;
import org.json.*;
import java.util.concurrent.CancellationException;

/** Real portable inference, independent of the phone's NPU and current user settings. */
final class TranslationExtensionVerification {
    static void run(Instrumentation test,Bundle options){
        Context c=test.getTargetContext();JSONObject report=new JSONObject();JSONArray rows=new JSONArray();Throwable failure=null;
        PowerManager.WakeLock wake=c.getSystemService(PowerManager.class).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK,"instant:translation-quality");wake.acquire(30*60_000L);
        try{
            report.put("version",BuildConfig.VERSION_NAME).put("device",Build.MODEL).put("runtime","llama.cpp CPU").put("translations",rows);
            String[][] cases={
                {"en","zh","Please speak slowly."},
                {"en","zh","Do not cancel order 7890."},
                {"en","ja","The train leaves at 09:30, not 19:30."},
                {"en","ko","Please do not cancel my reservation."},
                {"ja","zh","明日の会議は中止ではなく、午後3時に延期されました。"},
                {"ko","zh","내일 회의는 취소된 것이 아니라 오후 3시로 연기되었습니다."}
            };
            if("quality".equals(options.getString("phase"))){quality(test,options,report,rows);}
            else if("integration".equals(options.getString("phase"))){integration(test,report,rows);}
            else if("ui".equals(options.getString("phase"))){ui(test,report);}
            else for(String[] sample:cases){
                long load=SystemClock.elapsedRealtime();
                try(CpuTranslator cpu=new CpuTranslator(c,sample[0],sample[1],QwenModel.HYMT2)){
                    long start=SystemClock.elapsedRealtime();String output=cpu.translate(sample[2],()->true);
                    rows.put(new JSONObject().put("source",sample[0]).put("target",sample[1]).put("input",sample[2]).put("output",output).put("loadMs",start-load).put("ms",SystemClock.elapsedRealtime()-start));
                    RobustnessVerification.require(!output.isBlank(),"Empty CPU translation");
                    if(sample[1].equals("zh"))RobustnessVerification.require(output.matches("(?s).*[\\p{IsHan}].*"),"No Chinese result");
                    if(sample[1].equals("ja"))RobustnessVerification.require(output.matches("(?s).*[\\p{IsHiragana}\\p{IsKatakana}].*"),"No Japanese result");
                    if(sample[1].equals("ko"))RobustnessVerification.require(output.matches("(?s).*[\\p{IsHangul}].*"),"No Korean result");
                    boolean cancelled=false;try{cpu.translate("Do not run this request",()->false);}catch(CancellationException expected){cancelled=true;}
                    RobustnessVerification.require(cancelled,"CPU cancellation failed");
                }
                RobustnessVerification.write(c,"translation-extension-progress.json",report);
            }
            report.put("passed",true);
        }catch(Throwable e){failure=e;try{report.put("passed",false).put("error",android.util.Log.getStackTraceString(e));}catch(Exception ignored){}}
        finally{if(wake.isHeld())wake.release();}
        try{RobustnessVerification.write(c,"translation-extension-verification.json",report);}catch(Exception ignored){}
        Bundle result=new Bundle();result.putString("stream",report.toString());test.finish(failure==null?0:1,result);
    }
    private static void ui(Instrumentation test,JSONObject report)throws Exception{
        Context c=test.getTargetContext();android.app.Activity activity=test.startActivitySync(new android.content.Intent(c,ModelManagerActivity.class).putExtra("tab",0).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK));
        try{
            Thread.sleep(500);
            test.runOnMainSync(()->{
                android.view.View root=activity.getWindow().getDecorView(),label=find(root,"HY-MT2-1.8B");
                RobustnessVerification.require(label!=null,"Portable text model missing from model list");
                android.view.ViewParent p=label.getParent();while(p!=null&&!(p instanceof android.widget.ScrollView))p=p.getParent();
                if(p instanceof android.widget.ScrollView){android.widget.ScrollView scroll=(android.widget.ScrollView)p;android.graphics.Rect rect=new android.graphics.Rect();label.getDrawingRect(rect);scroll.offsetDescendantRectToMyCoords(label,rect);scroll.scrollTo(0,Math.max(0,rect.top-140));}
            });Thread.sleep(200);capture(test,c,"1.19-text-models.png");
            test.runOnMainSync(()->AuthorizedModelDownload.show(activity,QwenModel.GEMMA3));Thread.sleep(300);
            android.view.accessibility.AccessibilityNodeInfo root=test.getUiAutomation().getRootInActiveWindow();
            RobustnessVerification.require(root!=null&&!root.findAccessibilityNodeInfosByText(QwenModel.GEMMA3.entry(c).getName()).isEmpty(),"Authorization dialog lacks exact chipset file");
            capture(test,c,"1.19-authorized-download.png");
            java.util.List<android.view.accessibility.AccessibilityNodeInfo> buttons=root.findAccessibilityNodeInfosByText(activity.getString(R.string.import_model_file));
            RobustnessVerification.require(!buttons.isEmpty()&&buttons.get(0).performAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_CLICK),"Import action unavailable");
            Thread.sleep(1000);root=test.getUiAutomation().getRootInActiveWindow();
            String picker=root==null?"":String.valueOf(root.getPackageName());
            RobustnessVerification.require(picker.contains("documentsui"),"System document picker not opened: "+picker);
            capture(test,c,"1.19-file-picker.png");test.sendKeyDownUpSync(android.view.KeyEvent.KEYCODE_BACK);Thread.sleep(200);
            report.put("authorizedExactFileDialog",true).put("documentPickerPackage",picker).put("downloadStarted",false);
        }finally{test.runOnMainSync(activity::finish);}
    }
    private static android.view.View find(android.view.View root,String text){
        if(root instanceof android.widget.TextView&&text.contentEquals(((android.widget.TextView)root).getText()))return root;
        if(root instanceof android.view.ViewGroup){android.view.ViewGroup group=(android.view.ViewGroup)root;for(int i=0;i<group.getChildCount();i++){android.view.View result=find(group.getChildAt(i),text);if(result!=null)return result;}}
        return null;
    }
    private static void capture(Instrumentation test,Context c,String name)throws Exception{
        android.graphics.Bitmap bitmap=test.getUiAutomation().takeScreenshot();RobustnessVerification.require(bitmap!=null,"Screenshot missing");
        try(java.io.FileOutputStream out=new java.io.FileOutputStream(new java.io.File(c.getExternalFilesDir(null),name))){bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG,100,out);}finally{bitmap.recycle();}
    }
    private static void integration(Instrumentation test,JSONObject report,JSONArray rows)throws Exception{
        Context c=test.getTargetContext();String id=QwenModel.HYMT2.id;
        RobustnessVerification.require(!ModelCompatibility.bridge("ja","zh",id),"CPU direct route incorrectly uses English bridge");
        try(TranslationRouter router=new TranslationRouter(new AndroidTranslationFactory(c,"ja","zh",id))){
            router.prepare();RobustnessVerification.require(router.backend().equals(id),"Wrong active text model");
            String text=router.translate("予約をキャンセルしないでください。",()->true);
            RobustnessVerification.require(text.matches("(?s).*[不勿别].*"),"Direct Japanese route lost negation");
            report.put("directRouter",text).put("backend",router.backend());
        }
        try(CpuTranslator cpu=new CpuTranslator(c,"en","zh",QwenModel.HYMT2)){
            java.util.concurrent.atomic.AtomicInteger checks=new java.util.concurrent.atomic.AtomicInteger();
            boolean cancelled=false;long start=SystemClock.elapsedRealtime();
            try{cpu.translate("The train will leave at 09:30 tomorrow morning. Please do not cancel my reservation.",()->checks.incrementAndGet()<8);}catch(CancellationException expected){cancelled=true;}
            RobustnessVerification.require(cancelled&&checks.get()>=8,"In-flight CPU cancellation not observed");
            report.put("cancelMs",SystemClock.elapsedRealtime()-start);
            String answer=cpu.translate("Do not cancel order 7890.",()->true);
            RobustnessVerification.require(answer.contains("7890")&&answer.matches("(?s).*[不勿别].*"),"CPU did not recover after cancellation");
            start=SystemClock.elapsedRealtime();RobustnessVerification.require(answer.equals(cpu.translate("Do not cancel order 7890.",()->true)),"CPU cache changed translation");
            report.put("cacheMs",SystemClock.elapsedRealtime()-start).put("afterCancel",answer);
            String longInput="Please speak slowly. The access code is 7890. Do not cancel my reservation. ".repeat(5);
            String result=cpu.translate(longInput,()->true);report.put("longInputChars",longInput.length()).put("longOutput",result);
            RobustnessVerification.require(result.split("7890",-1).length-1==5,"Chunked CPU translation omitted repeated numbers");
        }
        java.util.Set<String> single=java.util.Collections.singleton("model.litertlm");
        android.net.Uri document=android.net.Uri.parse("content://com.android.externalstorage.documents/document/primary%3ADownload%2Fmodel.litertlm");
        RobustnessVerification.require(DocumentModelImport.find(c,document,single).size()==1,"Single document import not recognized");
        boolean rejected=false;try{DocumentModelImport.find(c,android.net.Uri.parse("file:///sdcard/model.litertlm"),single);}catch(java.io.IOException expected){rejected=true;}
        RobustnessVerification.require(rejected,"Import bypassed document picker");
        java.io.File folder=new java.io.File(c.getCacheDir(),"translation-import-regression"),wrong=new java.io.File(folder,"wrong.bin"),stage=new java.io.File(folder,"stage");
        folder.mkdirs();stage.mkdirs();java.nio.file.Files.write(wrong.toPath(),new byte[]{1,2,3});
        JSONObject fake=new JSONObject().put("download",new JSONObject().put("format","raw")).put("files",new JSONArray().put(new JSONObject().put("name","model.litertlm").put("size",3).put("sha256","0".repeat(64))));
        rejected=false;ModelStore.cancel=false;
        try{ModelStore.unpackTextArchive(wrong,stage,fake);}catch(java.io.IOException expected){rejected=expected.getMessage().contains("checksum");}
        RobustnessVerification.require(rejected,"Wrong raw model hash was accepted");
        RobustnessVerification.require(QwenModel.HYMT2.isInstalled(c),"Existing CPU model damaged by invalid import");
        ModelStore.erase(c,folder,c.getCacheDir());report.put("rawChecksumRejected",true).put("documentPickerRequired",true);
        if(BuildConfig.NPU_BUILD){
            RobustnessVerification.require(ModelStore.reserveQwen(c,"download",QwenModel.GEMMA3),"Cannot reserve authorized model");
            ModelStore.runTranslation(c,false,0);
            RobustnessVerification.require("failed".equals(ModelStore.phase)&&!ModelStore.error.isEmpty(),"Gated model attempted anonymous download");
            report.put("anonymousGatedDownloadBlocked",true);ModelStore.phase="";ModelStore.error="";
        }
        for(String source:new String[]{"ja","ko"}){
            short[] wave=NpuBenchmark.wave(test,"multilingual/"+source+".wav");
            for(NpuModel speech:new NpuModel[]{NpuModel.SENSEVOICE_CPU,NpuModel.WHISPER_BASE}){
                if(!ModelStore.installed(c,speech))continue;
                long begin=SystemClock.elapsedRealtime();String recognized;
                try(NpuRecognizer recognizer=new NpuRecognizer(c,speech,source,false)){recognized=recognizer.recognize(wave);}
                long after=SystemClock.elapsedRealtime();String translated;
                try(CpuTranslator cpu=new CpuTranslator(c,source,"zh",QwenModel.HYMT2)){translated=cpu.translate(recognized,()->true);}
                RobustnessVerification.require(!recognized.isBlank()&&translated.matches("(?s).*[\\p{IsHan}].*"),"Direct speech route failed");
                rows.put(new JSONObject().put("speechModel",speech.id).put("source",source).put("target","zh").put("recognized",recognized).put("output",translated).put("recognitionIncludingLoadMs",after-begin).put("translationIncludingLoadMs",SystemClock.elapsedRealtime()-after));
                RobustnessVerification.write(c,"translation-extension-progress.json",report);
            }
        }
        report.put("scope","Real CPU router, cancellation/cache/chunking, import guards and Japanese/Korean official audio fixtures; script checks are not ASR accuracy scores.");
    }
    private static void quality(Instrumentation test,Bundle options,JSONObject report,JSONArray rows)throws Exception{
        Context c=test.getTargetContext();QwenModel model=QwenModel.fromId(options.getString("textModel",QwenModel.HYMT2.id));
        RobustnessVerification.require(model!=null&&model.isInstalled(c),"Quality model missing");
        report.put("model",model.id).put("runtime",model.processor());
        RobustnessVerification.write(c,"translation-extension-progress.json",report);
        String data;try(java.io.InputStream in=test.getContext().getAssets().open("translation-quality.json")){data=NpuRecognizer.readText(in);}
        JSONArray samples=new JSONObject(data).getJSONArray("samples");
        TranslationRouter.Backend backend=null;String pair="";
        try{
            for(int i=0;i<samples.length();i++){
                JSONObject sample=samples.getJSONObject(i);String source=sample.getString("source"),target=sample.getString("target"),key=source+":"+target;
                String filter=options.getString("sampleIds","");if(!filter.isEmpty()&&!java.util.Arrays.asList(filter.split(",")).contains(sample.getString("id")))continue;
                if(!key.equals(pair)){
                    if(backend!=null)backend.close();backend=null;pair=key;
                    backend=model.isCpu()?new CpuTranslator(c,source,target,model):model.isLiteRt()?new LiteRtTranslator(c,source,target,model):new QwenTranslator(c,source,target,model);
                }
                long start=SystemClock.elapsedRealtime();JSONObject row=new JSONObject(sample.toString());
                try{row.put("output",backend.translate(sample.getString("input"),()->true));}catch(Exception e){row.put("error",e.toString());}
                row.put("ms",SystemClock.elapsedRealtime()-start);rows.put(row);RobustnessVerification.write(c,"translation-extension-progress.json",report);
            }
        }finally{if(backend!=null)backend.close();}
        for(int i=0;i<rows.length();i++)RobustnessVerification.require(!rows.getJSONObject(i).has("error"),"Some quality cases failed to run; inspect recorded rows");
        report.put("scope","Fixed multilingual regression set; runtime completion is separate from reference-based quality scores and native-speaker review.");
    }
}
