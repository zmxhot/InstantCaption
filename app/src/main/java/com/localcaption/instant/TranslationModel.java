package com.localcaption.instant;

import android.content.Context;
import java.io.*;
import org.json.*;

/** Downloadable translation weights, independent of Android service providers. */
final class TranslationModel {
    // Legacy constants remain for the original Chinese model and old saved jobs.
    static final String ID="opus-mt-en-zh", LABEL="OPUS-MT 英译中";
    private static final String TARGETS=" zh de fr es ru ar hi it pt ";
    private static final java.util.Map<String,JSONObject> catalogs=new java.util.HashMap<>();
    static String[] targets(){return TARGETS.trim().split(" +");}
    static String targetFromId(String id){if(id==null||!id.startsWith("opus-mt-en-"))return null;String target=id.substring("opus-mt-en-".length());return supportsTarget(target)&&id.equals(id(target))?target:null;}
    static String target(Context c){return NpuModel.canonicalLanguage(c.getSharedPreferences("engine",0).getString("speechTarget","zh"));}
    static boolean supportsTarget(String target){String value=NpuModel.canonicalLanguage(target);return value.matches("[a-z]{2,3}")&&TARGETS.contains(" "+value+" ");}
    static boolean supported(Context c){return supportsTarget(target(c));}
    static String id(Context c){return id(target(c));}
    static String id(String target){return "opus-mt-en-"+safeTarget(target);}
    static String label(Context c){return label(c,target(c));}
    static String label(Context c,String target){
        return "OPUS-MT · "+SpeechLanguage.direction(c,"en",target);
    }
    static JSONObject spec(Context c) throws Exception{return spec(c,target(c));}
    static synchronized JSONObject spec(Context c,String target) throws Exception {
        String value=NpuModel.canonicalLanguage(target);
        if(!supportsTarget(value))throw new IOException("No offline OPUS model for target language: "+value);
        String asset=DeviceProfile.detect(c).metadata("opus").manifest;
        JSONObject catalog=catalogs.get(asset);
        if(catalog==null)try(InputStream in=c.getAssets().open(asset)){catalog=new JSONObject(NpuRecognizer.readText(in));catalogs.put(asset,catalog);}
        return catalog.getJSONObject(value);
    }
    private static String safeTarget(String target){String value=NpuModel.canonicalLanguage(target);if(!value.matches("[a-z]{2,3}"))throw new IllegalArgumentException("Invalid target language");return value;}
    static File root(Context c){return root(c,target(c));}
    static File root(Context c,String target){return new File(c.getExternalFilesDir(null),DeviceProfile.detect(c).metadata("opus").directory.replace("{target}",safeTarget(target)));}
    static boolean installed(Context c){return installed(c,target(c));}
    static boolean installed(Context c,String target){try{JSONArray fs=spec(c,target).getJSONArray("files");for(int i=0;i<fs.length();i++){JSONObject f=fs.getJSONObject(i);if(new File(root(c,target),f.getString("name")).length()!=f.getLong("size"))return false;}return true;}catch(Exception e){return false;}}
    static long bytes(Context c){return bytes(c,target(c));}
    static long bytes(Context c,String target){try{long n=0;JSONArray fs=spec(c,target).getJSONArray("files");for(int i=0;i<fs.length();i++)n+=fs.getJSONObject(i).getLong("size");return n;}catch(Exception e){return 0;}}
}
