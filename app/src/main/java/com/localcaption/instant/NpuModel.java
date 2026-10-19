package com.localcaption.instant;

/** Speech model identities. The class name is retained for saved preferences. */
public enum NpuModel {
    X_ASR("x-asr", "X-ASR", true, true, 16000*10, "中英流式 · NPU", "en zh"),
    NEMOTRON("nemotron", "Nemotron Speech Streaming 0.6B", true, true, 16000*10, "英语流式 · NPU · 560 ms 分块", "en"),
    SENSEVOICE("sensevoice", "SenseVoice Small", true, false, 16000*4, "中英日韩粤语 · NPU", "en zh ja ko yue"),
    ZIPFORMER("zipformer", "Zipformer 中英双语", true, true, 16000*10, "中英流式 · NPU", "en zh"),
    WHISPER_TINY("whisper-tiny", "Whisper Tiny INT8", false, false, 16000*12, "99 种语言 · 通用 CPU · 约 104 MB", "*"),
    WHISPER_BASE("whisper-base", "Whisper Base INT8", false, false, 16000*12, "99 种语言 · 通用 CPU · 约 161 MB", "*"),
    SENSEVOICE_CPU("sensevoice-cpu", "SenseVoice Small INT8", false, false, 16000*6, "中英日韩粤语 · 通用 CPU · 约 240 MB", "en zh ja ko yue");

    // tiny/base have the original 99-language vocabulary; Cantonese was added
    // to Whisper large-v3 and must not be advertised for these two models.
    private static final String[] WHISPER_LANGUAGES = ("en zh de es ru ko fr ja pt tr pl ca nl ar sv it id hi fi vi he uk el ms cs ro da hu ta no th ur hr bg lt la mi ml cy sk te fa lv bn sr az sl kn et mk br eu is hy ne mn bs kk sq sw gl mr pa si km sn yo so af oc ka be tg sd gu am yi lo uz fo ht ps tk nn mt sa lb my bo tl mg as tt haw ln ha ba jw su").split(" ");
    public final String id,label,description;
    public final boolean isNpu,streaming;
    public final int segmentSamples;
    private final String languageCodes;
    NpuModel(String id,String label,boolean isNpu,boolean streaming,int segmentSamples,String description,String languages){this.id=id;this.label=label;this.isNpu=isNpu;this.streaming=streaming;this.segmentSamples=segmentSamples;this.description=description;this.languageCodes=languages;}
    public static NpuModel fromId(String id){for(NpuModel m:values())if(m.id.equals(id))return m;return X_ASR;}
    public String directory(DeviceProfile device){
        return device.metadata(id).directory;
    }
    public String label(android.content.Context c){return this==ZIPFORMER?Ui.t(c,"Zipformer 中英双语","Zipformer Chinese / English"):label;}
    public String choice(){return label+"\n"+description;}
    public boolean isWhisper(){return this==WHISPER_TINY||this==WHISPER_BASE;}
    public boolean supportsEnglishTranslation(){return isWhisper();}
    public String[] supportedLanguages(){return isWhisper()?WHISPER_LANGUAGES.clone():languageCodes.split(" ");}
    public boolean supportsLanguage(String tag){
        String language=canonicalLanguage(tag);
        if("auto".equals(language))return isWhisper()||this==SENSEVOICE||this==SENSEVOICE_CPU;
        for(String supported:supportedLanguages())if(supported.equals(language))return true;
        return false;
    }
    public static String canonicalLanguage(String tag){
        if(tag==null)return "";
        String value=tag.trim().toLowerCase(java.util.Locale.ROOT).replace('_','-');
        if(value.equals("zh-yue")||value.startsWith("zh-yue-"))return "yue";
        int separator=value.indexOf('-');if(separator>=0)value=value.substring(0,separator);
        switch(value){case "cmn":return "zh";case "iw":return "he";case "in":return "id";case "ji":return "yi";case "fil":return "tl";case "jv":return "jw";case "nb":return "no";default:return value;}
    }
}
