package com.localcaption.instant;

import android.content.Context;
import java.io.*;
import org.json.*;

/** Independent local text models. Legacy class name and existing IDs are retained. */
enum QwenModel {
    BALANCED("1.7b","Qwen3-1.7B"),
    SMALL("0.6b","Qwen3-0.6B"),
    LARGE("4b","Qwen3-4B"),
    GEMMA4("gemma4-e2b-npu","Gemma4-E2B","litert-lm"),
    GEMMA3("gemma3-1b-npu","Gemma3-1B","litert-lm"),
    HYMT2("hy-mt2-1.8b-cpu","HY-MT2-1.8B","llama-cpu");

    static final String ID="qwen3-1.7b-npu",LABEL="Qwen3-1.7B";
    final String id,label,runtime;
    private final java.util.Map<String,JSONObject> catalogs=new java.util.HashMap<>();
    QwenModel(String size,String label){
        this("qwen3-"+size+"-npu",label,"qairt");
    }
    QwenModel(String id,String label,String runtime){this.id=id;this.label=label;this.runtime=runtime;}
    boolean isLiteRt(){return runtime.equals("litert-lm");}
    boolean isCpu(){return runtime.equals("llama-cpu");}
    String processor(){return isCpu()?"CPU":"NPU";}
    boolean requiresAuthorization(){return this==GEMMA3;}
    boolean supportsLanguages(String source,String target){
        if(this!=HYMT2)return true;
        String languages=" zh en fr pt es ja tr ru ar ko th it de vi ms id tl hi pl cs nl km my fa gu ur te mr he bn ta uk bo kk mn ug yue ";
        return languages.contains(" "+SpeechLanguage.normalize(source)+" ")&&languages.contains(" "+SpeechLanguage.normalize(target)+" ");
    }
    static QwenModel fromId(String id){for(QwenModel m:values())if(m.id.equals(id))return m;return null;}
    static boolean known(String id){return fromId(id)!=null;}
    JSONObject manifest(Context c)throws Exception{return manifest(c,DeviceProfile.detect(c));}
    synchronized JSONObject manifest(Context c,DeviceProfile device)throws Exception{
        String asset=device.metadata(id).manifest;
        JSONObject catalog=catalogs.get(asset);
        if(catalog==null)try(InputStream in=c.getAssets().open(asset)){catalog=new JSONObject(NpuRecognizer.readText(in));catalogs.put(asset,catalog);}
        return catalog;
    }
    boolean isSupported(Context c){return isSupported(DeviceProfile.detect(c));}
    boolean isSupported(DeviceProfile d){return d.supportsText(id);}
    String directory(DeviceProfile device){return device.metadata(id).directory;}
    File modelRoot(Context c){return new File(c.getExternalFilesDir(null),directory(DeviceProfile.detect(c)));}
    boolean isInstalled(Context c){try{JSONArray a=manifest(c).getJSONArray("files");for(int i=0;i<a.length();i++){JSONObject f=a.getJSONObject(i);if(new File(modelRoot(c),f.getString("name")).length()!=f.getLong("size"))return false;}return true;}catch(Exception e){return false;}}
    long installedBytes(Context c){try{JSONArray a=manifest(c).getJSONArray("files");long n=0;for(int i=0;i<a.length();i++)n+=a.getJSONObject(i).getLong("size");return n;}catch(Exception e){return 0;}}
    File entry(Context c)throws Exception{
        if(isLiteRt()||isCpu())return new File(modelRoot(c),manifest(c).getString("entryFile"));
        JSONArray a=manifest(c).getJSONArray("files");
        for(int i=0;i<a.length();i++){String name=a.getJSONObject(i).getString("name");if(name.startsWith("part1_of_")&&name.endsWith(".bin"))return new File(modelRoot(c),name);}
        throw new IOException("Missing NPU model entry: "+label);
    }
    String compatibility(Context c){DeviceProfile d=DeviceProfile.detect(c);DeviceModelCatalog.ModelRule rule=d.metadata(id);return c.getString(R.string.model_device_requirement,isCpu()?"ARM64 CPU":d.npuChip.isEmpty()?c.getString(R.string.npu_chip_missing):d.npuChip,rule.androidVersion,rule.nominalRamGb)+(!isCpu()&&d.documentedNpu()?" · "+c.getString(R.string.npu_documented):"");}
    String description(Context c){return this==HYMT2?c.getString(R.string.hymt_description):this==GEMMA3?c.getString(R.string.gemma3_description):isLiteRt()?c.getString(R.string.gemma_npu_description):this==SMALL?Ui.t(c,"轻量试用 · 容易漏译，优先选择 1.7B","Lightweight trial · more omissions; prefer 1.7B"):this==LARGE?Ui.t(c,"译文更完整 · 内存占用高，加载较慢","More complete translations · higher memory, slower loading"):Ui.t(c,"均衡推荐 · 多国语言直接互译","Balanced choice · direct multilingual translation");}
    // Preserve the original model directory and existing verification entry points.
    static JSONObject spec(Context c)throws Exception{return BALANCED.manifest(c);}
    static boolean supported(Context c){return BALANCED.isSupported(c);}
    static boolean supported(DeviceProfile d){return BALANCED.isSupported(d);}
    static File root(Context c){return BALANCED.modelRoot(c);}
    static boolean installed(Context c){return BALANCED.isInstalled(c);}
    static long bytes(Context c){return BALANCED.installedBytes(c);}
    static String reason(Context c){return BALANCED.compatibility(c);}
}
