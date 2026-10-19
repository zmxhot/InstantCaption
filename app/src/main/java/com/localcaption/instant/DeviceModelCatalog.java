package com.localcaption.instant;

import android.content.Context;
import java.io.InputStream;
import java.util.*;
import java.util.regex.Pattern;
import org.json.*;

/** Bundled configuration; no network, handset whitelist, or test-result gate. */
final class DeviceModelCatalog {
    static final String ASSET="device-model-catalog.json";
    private static volatile DeviceModelCatalog instance;
    final int minimumAndroid;
    final String abi,revision;
    final Map<String,Profile> profiles;
    final List<Chip> chips;
    final Profile fallback,legacyStorage;

    static synchronized void initialize(Context context){
        if(instance!=null)return;
        try(InputStream in=context.getAssets().open(ASSET)){
            instance=parse(NpuRecognizer.readText(in));
        }catch(Exception e){throw new IllegalStateException("Invalid bundled device/model catalog",e);}
    }
    static DeviceModelCatalog get(){
        if(instance==null)throw new IllegalStateException("Device/model catalog has not been initialized");
        return instance;
    }
    static DeviceModelCatalog parse(String text)throws Exception{return new DeviceModelCatalog(new JSONObject(text));}

    static final class ModelRule {
        final String id,task,runtime,manifest,directory,androidVersion;
        final int minAndroid,priority,nominalRamGb;
        final long minRamBytes,recommendMinRamBytes;
        final boolean allowLowRam,recommendOnLowRam;
        ModelRule(JSONObject json,int sdk)throws Exception{
            id=json.getString("id");task=json.getString("task");runtime=json.getString("runtime");
            manifest=json.getString("manifest");directory=json.getString("directory");
            minAndroid=json.optInt("minAndroid",sdk);androidVersion=json.getString("androidVersion");priority=json.optInt("priority",100);
            minRamBytes=json.optLong("minRamBytes",0);nominalRamGb=json.optInt("nominalRamGb",0);
            recommendMinRamBytes=json.optLong("recommendMinRamBytes",0);
            allowLowRam=json.optBoolean("allowLowRam",true);recommendOnLowRam=json.optBoolean("recommendOnLowRam",true);
            boolean known=false;
            if(task.equals("speech"))for(NpuModel m:NpuModel.values())if(m.id.equals(id))known=m.isNpu?runtime.equals("qnn"):runtime.equals("cpu");
            if(task.equals("text"))known=id.equals("opus")?runtime.equals("cpu"):QwenModel.known(id)&&runtime.equals(QwenModel.fromId(id).runtime);
            require(known,"Unknown model/runtime: "+id);
            require(manifest.matches("[a-z0-9][a-z0-9._-]*\\.json"),"Invalid manifest path: "+id);
            String folder=id.equals("opus")?directory.replace("{target}","zh"):directory;
            require(folder.matches("[a-z0-9][a-z0-9._-]*"),"Invalid model directory: "+id);
            require(minAndroid>=sdk&&minRamBytes>=0&&recommendMinRamBytes>=0&&nominalRamGb>=0,"Invalid requirements: "+id);
            require(priority>=0&&priority<=1000,"Invalid model priority: "+id);
            require(androidVersion.matches("[0-9]+(?:\\.[0-9]+)?"),"Invalid Android version label");
        }
        boolean npu(){return !runtime.equals("cpu")&&!runtime.equals("llama-cpu");}
        boolean eligible(DeviceProfile device){return device.sdkInt>=minAndroid&&device.arm64&&(!npu()||BuildConfig.NPU_BUILD)&&device.ramBytes>=minRamBytes&&(allowLowRam||!device.lowRam);}
        boolean recommended(DeviceProfile device){return eligible(device)&&device.ramBytes>=recommendMinRamBytes&&(recommendOnLowRam||!device.lowRam);}
    }
    static final class Profile {
        final String id,npuChip;
        final boolean documentationBased;
        final Map<String,ModelRule> models;
        final List<String> requiredLibraries;
        Profile(JSONObject json,Profile parent,int sdk)throws Exception{
            id=json.getString("id");npuChip=json.optString("npuChip","");documentationBased=json.optBoolean("documentationBased",false);
            LinkedHashMap<String,ModelRule> all=new LinkedHashMap<>();if(parent!=null)all.putAll(parent.models);
            JSONArray entries=json.getJSONArray("models");HashSet<String> seen=new HashSet<>();
            for(int i=0;i<entries.length();i++){ModelRule rule=new ModelRule(entries.getJSONObject(i),sdk);require(seen.add(rule.id),"Duplicate model: "+rule.id);all.put(rule.id,rule);}
            ArrayList<String> libraries=new ArrayList<>();JSONArray libs=json.optJSONArray("requiredLibraries");
            if(libs!=null)for(int i=0;i<libs.length();i++){String lib=libs.getString(i);require(lib.matches("lib[A-Za-z0-9_.-]+\\.so"),"Invalid library");libraries.add(lib);}
            for(ModelRule rule:all.values())if(rule.npu())require(!npuChip.isEmpty()&&!libraries.isEmpty(),"NPU profile lacks a target/runtime: "+id);
            models=Collections.unmodifiableMap(all);requiredLibraries=Collections.unmodifiableList(libraries);
        }
    }
    static final class Chip {
        final String id,name;
        final Profile profile;
        final List<Pattern> soc,manufacturer,hardware;
        Chip(JSONObject json,Map<String,Profile> profiles)throws Exception{
            id=json.getString("id");name=json.getString("name");profile=profiles.get(json.getString("profile"));require(profile!=null,"Unknown chipset profile: "+id);
            JSONObject match=json.getJSONObject("match");soc=patterns(match.optJSONArray("soc"));manufacturer=patterns(match.optJSONArray("socManufacturer"));hardware=patterns(match.optJSONArray("hardware"));
            require(!soc.isEmpty()||!manufacturer.isEmpty()||!hardware.isEmpty(),"Empty chipset match: "+id);
            // A generic manufacturer/board name must never unlock chip-specific binaries.
            if(!profile.npuChip.isEmpty())require(manufacturer.isEmpty()&&hardware.isEmpty(),"NPU profile requires explicit SoC matching: "+id);
        }
    }
    static final class Match {
        final String id,name,source;
        final Profile profile;
        Match(Chip chip,String source,Profile fallback){this.id=chip==null?"generic-arm64":chip.id;this.name=chip==null?"":chip.name;this.profile=chip==null?fallback:chip.profile;this.source=source;}
    }
    private DeviceModelCatalog(JSONObject root)throws Exception{
        require(root.getInt("schemaVersion")==1,"Unsupported catalog schema");
        minimumAndroid=root.getInt("minimumAndroid");abi=root.getString("abi");revision=root.getString("revision");
        require(minimumAndroid>=29&&abi.equals("arm64-v8a"),"Unsupported platform requirements");
        LinkedHashMap<String,Profile> parsed=new LinkedHashMap<>();JSONArray sets=root.getJSONArray("profiles");require(sets.length()<=64,"Too many profiles");
        for(int i=0;i<sets.length();i++){
            JSONObject json=sets.getJSONObject(i);String parentId=json.optString("extends","");Profile parent=parsed.get(parentId);
            require(parentId.isEmpty()||parent!=null,"Parent must precede profile: "+parentId);
            Profile p=new Profile(json,parent,minimumAndroid);require(!parsed.containsKey(p.id),"Duplicate profile: "+p.id);parsed.put(p.id,p);
        }
        profiles=Collections.unmodifiableMap(parsed);fallback=parsed.get(root.getString("defaultProfile"));legacyStorage=parsed.get(root.getString("legacyStorageProfile"));
        require(fallback!=null&&fallback.npuChip.isEmpty()&&legacyStorage!=null,"Invalid default/storage profile");
        ArrayList<Chip> rules=new ArrayList<>();HashSet<String> ids=new HashSet<>();JSONArray all=root.getJSONArray("chipsets");require(all.length()<=256,"Too many chipsets");
        for(int i=0;i<all.length();i++){Chip chip=new Chip(all.getJSONObject(i),parsed);require(ids.add(chip.id),"Duplicate chipset");rules.add(chip);}
        chips=Collections.unmodifiableList(rules);
    }
    Match match(String soc,String manufacturer,String hardware){
        // Strong SoC evidence wins over generic vendor and board information.
        for(Chip chip:chips)if(matches(chip.soc,soc))return new Match(chip,"soc",fallback);
        for(Chip chip:chips)if(matches(chip.manufacturer,manufacturer))return new Match(chip,"socManufacturer",fallback);
        for(Chip chip:chips)if(matches(chip.hardware,hardware))return new Match(chip,"hardware",fallback);
        return new Match(null,"fallback",fallback);
    }
    ModelRule metadata(Profile profile,String id){
        ModelRule rule=profile.models.get(id);
        // Allow users to inspect/remove retained files after a configuration change.
        // This lookup never confers download/selection eligibility.
        if(rule==null)rule=legacyStorage.models.get(id);
        if(rule==null)throw new IllegalArgumentException("Missing model metadata: "+id);
        return rule;
    }
    private static List<Pattern> patterns(JSONArray array)throws Exception{
        ArrayList<Pattern> result=new ArrayList<>();
        if(array!=null)for(int i=0;i<array.length();i++){String value=array.getString(i);require(value.length()<=256,"Oversized pattern");result.add(Pattern.compile(value,Pattern.CASE_INSENSITIVE));}
        return Collections.unmodifiableList(result);
    }
    private static boolean matches(List<Pattern> patterns,String value){
        if(value==null)return false;String text=value.trim().replaceAll("\\s+"," ");if(text.isEmpty()||text.length()>160)return false;
        for(Pattern pattern:patterns)if(pattern.matcher(text).matches())return true;return false;
    }
    private static void require(boolean condition,String message)throws JSONException{if(!condition)throw new JSONException(message);}
}
