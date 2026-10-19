package com.localcaption.instant;

import android.app.ActivityManager;
import android.content.Context;
import android.os.Build;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/** Hardware eligibility, separate from whether a model has been downloaded. */
public final class DeviceProfile {
    public final int sdkInt;
    public final String socModel,npuChip,chipName,chipId;
    public final long ramBytes;
    public final boolean lowRam,arm64,npuEligible;
    final DeviceModelCatalog catalog;
    final DeviceModelCatalog.Profile modelProfile;

    /** Pure hardware seam used by catalog tests; no Android service is accessed. */
    public DeviceProfile(int sdk,String[] abis,String socModel,long ramBytes,boolean lowRam){
        this(DeviceModelCatalog.get(),sdk,abis,socModel,"","",ramBytes,lowRam);
    }
    DeviceProfile(DeviceModelCatalog catalog,int sdk,String[] abis,String socModel,String socManufacturer,String hardware,long ramBytes,boolean lowRam){
        this.catalog=catalog;
        this.sdkInt=sdk;
        this.socModel=socModel==null?"":socModel.trim();
        this.ramBytes=Math.max(0,ramBytes);
        this.lowRam=lowRam;
        boolean compatibleAbi=false;
        if(abis!=null)for(String abi:abis)if(catalog.abi.equals(abi))compatibleAbi=true;
        arm64=compatibleAbi;
        DeviceModelCatalog.Match match=catalog.match(this.socModel,socManufacturer,hardware);
        modelProfile=match.profile;chipId=match.id;chipName=match.name;npuChip=modelProfile.npuChip;
        boolean hasNpu=false;for(DeviceModelCatalog.ModelRule rule:modelProfile.models.values())hasNpu|=rule.npu()&&rule.eligible(this);
        npuEligible=hasNpu;
    }

    public static DeviceProfile detect(Context context){
        DeviceModelCatalog.initialize(context);
        long memory=0;boolean low=false;
        ActivityManager manager=(ActivityManager)context.getSystemService(Context.ACTIVITY_SERVICE);
        if(manager!=null){ActivityManager.MemoryInfo info=new ActivityManager.MemoryInfo();manager.getMemoryInfo(info);memory=info.totalMem;low=manager.isLowRamDevice();}
        String soc=Build.VERSION.SDK_INT>=31?Build.SOC_MODEL:"";
        String maker=Build.VERSION.SDK_INT>=31?Build.SOC_MANUFACTURER:"";
        return new DeviceProfile(DeviceModelCatalog.get(),Build.VERSION.SDK_INT,Build.SUPPORTED_ABIS,soc,maker,Build.HARDWARE,memory,low);
    }

    public boolean supports(NpuModel model){return model!=null&&supportsModel(model.id,"speech");}
    boolean supportsText(String id){return supportsModel(id,"text");}
    private boolean supportsModel(String id,String task){DeviceModelCatalog.ModelRule rule=modelProfile.models.get(id);return rule!=null&&rule.task.equals(task)&&rule.eligible(this);}
    DeviceModelCatalog.ModelRule metadata(String id){return catalog.metadata(modelProfile,id);}
    int modelPriority(String id){DeviceModelCatalog.ModelRule rule=modelProfile.models.get(id);return rule==null?Integer.MAX_VALUE:rule.priority+(rule.recommended(this)?0:2000);}
    public boolean documentedNpu(){return npuEligible&&modelProfile.documentationBased;}
    String processorName(){
        String raw=socModel.isEmpty()||socModel.equalsIgnoreCase("unknown")?"":socModel;
        return chipName.isEmpty()?(raw.isEmpty()?"ARM64":raw):raw.isEmpty()||raw.equalsIgnoreCase(chipName)?chipName:chipName+" · "+raw;
    }

    /** Empty when this build cannot run the device or no model covers the language. */
    public List<NpuModel> downloadableModels(String language){
        ArrayList<NpuModel> models=new ArrayList<>();
        NpuModel recommended=recommendedModel(language);
        if(recommended!=null)models.add(recommended);
        for(NpuModel model:orderedSpeech())if(model!=recommended&&supports(model)&&model.supportsLanguage(language))models.add(model);
        return Collections.unmodifiableList(models);
    }

    /** Conservative default; unknown RAM selects the smallest portable model. */
    public NpuModel recommendedModel(String language){
        return recommendSpeech(language,false);
    }

    public NpuModel recommendedEnglishBridge(String language){
        return recommendSpeech(language,true);
    }
    private NpuModel recommendSpeech(String language,boolean bridge){
        // Keep compatible NPU models first. For direct CJK speech, prefer the
        // multilingual SenseVoice model over Whisper's smaller general models.
        if(!bridge&&java.util.Arrays.asList("zh","yue","ja","ko").contains(SpeechLanguage.normalize(language))){
            for(NpuModel model:orderedSpeech())if(model.isNpu&&model.supportsLanguage(language)&&modelProfile.models.get(model.id).recommended(this))return model;
            if(supports(NpuModel.SENSEVOICE_CPU)&&modelProfile.models.get(NpuModel.SENSEVOICE_CPU.id).recommended(this))return NpuModel.SENSEVOICE_CPU;
        }
        for(NpuModel model:orderedSpeech())if(model.supportsLanguage(language)&&(!bridge||model.supportsEnglishTranslation())&&modelProfile.models.get(model.id).recommended(this))return model;
        return null;
    }
    private List<NpuModel> orderedSpeech(){
        ArrayList<NpuModel> models=new ArrayList<>();for(NpuModel model:NpuModel.values())if(supports(model))models.add(model);
        models.sort(Comparator.comparingInt(m->modelPriority(m.id)));return models;
    }

    public String summary(Context context){
        if(sdkInt<catalog.minimumAndroid)return Ui.t(context,"需要 Android 10 或更新版本","Android 10 or newer is required");
        if(!arm64)return Ui.t(context,"当前安装包需要 64 位 ARM Android 设备","This build requires a 64-bit ARM Android device");
        String chip=processorName();
        String memory=ramBytes>0?String.format(Locale.ROOT," · %.1f GB RAM",ramBytes/1073741824.0):"";
        return chip+memory+" · "+(npuEligible?Ui.t(context,"支持专用 NPU 和通用 CPU 模型","Dedicated NPU and portable CPU models available"):Ui.t(context,"使用通用 CPU 模型","Portable CPU models available"))+(documentedNpu()?" · "+context.getString(R.string.npu_documented):"");
    }

    public String reason(Context context,NpuModel model){
        if(sdkInt<catalog.minimumAndroid)return Ui.t(context,"需要 Android 10 或更新版本","Requires Android 10 or newer");
        if(!arm64)return Ui.t(context,"需要 64 位 ARM Android 系统","Requires a 64-bit ARM Android system");
        if(model==null)return Ui.t(context,"未选择模型","No model selected");
        if(!supports(model))return context.getString(R.string.npu_chip_missing);
        if(model.isNpu)return context.getString(R.string.npu_chip_model,npuChip)+(documentedNpu()?" · "+context.getString(R.string.npu_documented):"");
        if(model==NpuModel.WHISPER_TINY&&recommendedModel("en")==model)return Ui.t(context,"通用 CPU · 当前内存配置优先推荐此小模型","Portable CPU · smaller model recommended for this memory configuration");
        return Ui.t(context,"通用 CPU · 无需专用 NPU 或 Google 服务","Portable CPU · no dedicated NPU or Google services required");
    }
}
