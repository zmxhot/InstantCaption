package com.localcaption.instant;

import android.content.Context;
import java.io.File;
import org.json.*;

/** Catalog routing tests, not a substitute for physical SM8850 inference. */
final class ChipsetCatalogVerification {
    private static void require(boolean ok,String message){if(!ok)throw new AssertionError(message);}
    private static DeviceProfile device(String soc,int sdk,long ram,String abi){return new DeviceProfile(sdk,new String[]{abi},soc,ram,false);}
    static JSONObject run(Context c)throws Exception{
        DeviceProfile old=device("SM8750",35,12_000_000_000L,"arm64-v8a");
        DeviceProfile recent=device("SM8850",36,12_000_000_000L,"arm64-v8a");
        for(String soc:new String[]{"SM8850","sm8850-ac","SM8850-5-AC"}){
            DeviceProfile d=device(soc,36,12_000_000_000L,"arm64-v8a");
            require(d.npuEligible==BuildConfig.NPU_BUILD,"Documented chip incorrectly filtered: "+soc);
            require(d.documentedNpu()==BuildConfig.NPU_BUILD,"Unverified chip must be labeled: "+soc);
            for(NpuModel m:NpuModel.values())require(d.supports(m)==(!m.isNpu||BuildConfig.NPU_BUILD),"Wrong model list: "+soc+" "+m.id);
            for(QwenModel m:QwenModel.values())require(m.isSupported(d)==(m.isCpu()||(BuildConfig.NPU_BUILD&&!m.isLiteRt())),"Wrong text model list: "+m.id);
        }
        for(String soc:new String[]{"SM88500","SM8850FAKE","SM8850/SM8750","qcom","SM8950","SM8845","MT6993","Exynos 2600",""}){
            DeviceProfile d=device(soc,36,12_000_000_000L,"arm64-v8a");
            require(!d.npuEligible&&!QwenModel.BALANCED.isSupported(d),"Uncatalogued chip accepted: "+soc);
            require(d.supports(NpuModel.WHISPER_TINY),"Portable fallback lost: "+soc);
        }
        require(!QwenModel.BALANCED.isSupported(device("SM8850",34,12_000_000_000L,"arm64-v8a")),"Old Android accepted for Qwen");
        require(!QwenModel.LARGE.isSupported(device("SM8850",36,7_500_000_000L,"arm64-v8a")),"4B accepted on 8 GB");
        require(!QwenModel.SMALL.isSupported(device("SM8850",36,5_500_000_000L,"arm64-v8a")),"Insufficient memory accepted");
        require(!device("SM8850",36,12_000_000_000L,"armeabi-v7a").supports(NpuModel.SENSEVOICE),"32-bit accepted");
        require(!device("SM8850",28,12_000_000_000L,"arm64-v8a").supports(NpuModel.SENSEVOICE),"Old Android accepted");
        require(!old.documentedNpu(),"Existing chipset unexpectedly relabeled");
        int routes=0;
        for(NpuModel m:NpuModel.values()){
            JSONObject a=ModelStore.spec(c,m,old),b=ModelStore.spec(c,m,recent);
            require(m.directory(old).equals(a.getString("directory")),"Legacy speech folder changed: "+m.id);
            require(m.directory(recent).equals(b.getString("directory")),"New speech folder differs from catalog: "+m.id);
            if(m.isNpu){
                require(!m.directory(old).equals(m.directory(recent)),"NPU installation/cache shared across chips");
                require(b.getString("chipset").equals("SM8850"),"Wrong speech chip manifest");
                if(m!=NpuModel.NEMOTRON)require(!a.getJSONArray("files").getJSONObject(0).getString("sha256").equals(b.getJSONArray("files").getJSONObject(0).getString("sha256")),"Old context binary reused on new chip");
                routes++;
            }else require(a.toString().equals(b.toString()),"Portable catalog changed by chip");
        }
        for(QwenModel m:QwenModel.values()){
            if(m.isLiteRt()||m.isCpu())continue;
            JSONObject a=m.manifest(c,old),b=m.manifest(c,recent);
            require(a.getString("chipset").equals("SM8750")&&b.getString("chipset").equals("SM8850"),"Wrong Qwen target");
            require(!m.directory(old).equals(m.directory(recent)),"Text model folder shared across chips");
            require(b.getJSONObject("download").getJSONArray("urls").getJSONObject(0).getString("url").contains("8_elite_gen5_for_galaxy"),"Incorrect Qwen download");
            // Exercise alternating lookups to detect stale per-enum catalog caching.
            require(m.manifest(c,old).getString("chipset").equals("SM8750"),"Text catalog cache leaked chip");
            for(int i=0;i<b.getJSONArray("files").length();i++)require(b.getJSONArray("files").getJSONObject(i).getString("sha256").matches("[a-f0-9]{64}"),"Unpinned file");
            routes++;
        }
        if(BuildConfig.NPU_BUILD)for(String arch:new String[]{"68","69","73","75","79","81"})for(String kind:new String[]{"Stub","Skel"})
            require(new File(c.getApplicationInfo().nativeLibraryDir,"libQnnHtpV"+arch+kind+".so").length()>0,"Missing runtime for V"+arch);
        for(String chip:new String[]{"SM8350","SM8450","SM8475","SM8550","SM8650"}){
            DeviceProfile d=device(chip,36,12_000_000_000L,"arm64-v8a");
            for(NpuModel m:new NpuModel[]{NpuModel.SENSEVOICE,NpuModel.X_ASR,NpuModel.ZIPFORMER}){
                if(chip.equals("SM8350")&&m!=NpuModel.SENSEVOICE){require(!d.supports(m),"Unavailable binary offered");continue;}
                require(d.supports(m)==BuildConfig.NPU_BUILD,"Published binary missing: "+chip+"/"+m.id);
                JSONObject spec=ModelStore.spec(c,m,d);require(spec.getJSONObject("upstream").getString("chipset").equals(chip),"Wrong upstream chip");
                require(spec.getJSONObject("download").getJSONArray("urls").getJSONObject(0).getString("url").contains(chip),"Wrong package route");routes++;
            }
            require(!d.supports(NpuModel.NEMOTRON),"No matching Nemotron package");
        }
        for(String chip:new String[]{"SM8750","Tensor G5","Tensor G6"}){
            DeviceProfile d=device(chip,36,12_000_000_000L,"arm64-v8a");
            require(QwenModel.GEMMA4.isSupported(d)==BuildConfig.NPU_BUILD,"Gemma4 route missing");
            require(!QwenModel.GEMMA4.isSupported(device(chip,34,12_000_000_000L,"arm64-v8a")),"Gemma4 old Android accepted");
            require(!QwenModel.GEMMA4.isSupported(device(chip,36,7_500_000_000L,"arm64-v8a")),"Gemma4 low RAM accepted");
            JSONObject spec=QwenModel.GEMMA4.manifest(c,d);require(spec.getString("chipset").equals(d.npuChip),"Gemma4 target mismatch");
            require(spec.getJSONObject("download").getString("format").equals("raw"),"Wrong model format");
            require(spec.getJSONArray("files").getJSONObject(0).getString("sha256").equals(spec.getJSONObject("download").getString("sha256")),"Raw checksum differs");routes++;
        }
        for(String chip:new String[]{"MT6989","MT6991","SM8550","SM8650","SM8750"}){
            DeviceProfile d=device(chip,35,8_000_000_000L,"arm64-v8a");
            require(QwenModel.GEMMA3.isSupported(d)==BuildConfig.NPU_BUILD,"Gemma3 route missing: "+chip);
            JSONObject spec=QwenModel.GEMMA3.manifest(c,d),download=spec.getJSONObject("download");
            require(spec.getString("chipset").equals(d.npuChip),"Gemma3 target mismatch");
            require(download.getBoolean("requiresAuthorization"),"Gated model offered anonymously");
            require(download.getJSONArray("urls").length()==1,"Gated model offered via mirror");
            require(download.getString("authorizationUrl").startsWith("https://huggingface.co/litert-community/"),"Wrong authorization host");
            require(spec.getJSONArray("files").getJSONObject(0).getString("sha256").equals(download.getString("sha256")),"Gemma3 checksum mismatch");routes++;
        }
        require(new File(c.getApplicationInfo().nativeLibraryDir,"libcaption_cpu.so").length()>0,"Missing portable text runtime");
        if(BuildConfig.NPU_BUILD)for(String library:new String[]{"liblitertlm_jni.so","libLiteRtDispatch_Qualcomm.so","libLiteRtDispatch_GoogleTensor.so","libLiteRtDispatch_MediaTek.so","libLiteRtCompilerPlugin_MediaTek.so"})
            require(new File(c.getApplicationInfo().nativeLibraryDir,library).length()>0,"Missing NPU runtime: "+library);
        if(BuildConfig.NPU_BUILD)for(String vendor:new String[]{"Qualcomm","MediaTek","GoogleTensor"}){
            File folder=new File(LiteRtLibraries.forVendor(c,vendor));
            File[] dispatch=folder.listFiles((d,n)->n.startsWith("libLiteRtDispatch_"));
            require(dispatch!=null&&dispatch.length==1&&dispatch[0].getName().equals("libLiteRtDispatch_"+vendor+".so"),"Cross-vendor NPU plugin exposed");
        }
        return new JSONObject().put("passed",true).put("catalogRoutes",routes).put("physicalSm8850Tested",false).put("scope","Documented eligibility, per-chip catalogs, paths, runtime packaging and negative guards");
    }
}
