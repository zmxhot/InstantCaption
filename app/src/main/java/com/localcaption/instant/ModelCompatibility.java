package com.localcaption.instant;

/** One source of truth for language pairs, used before selection and loading. */
final class ModelCompatibility {
    static boolean bridge(String source,String target,String textModel) {
        return !QwenModel.known(textModel) && !SpeechLanguage.canonical(source).equals(target) && !"en".equals(source);
    }
    static boolean supports(DeviceProfile device,String source,String target,String textModel) {
        if(device.sdkInt<29 || !device.arm64 || !SpeechLanguage.supported(source) || !SpeechLanguage.supported(target))return false;
        target=SpeechLanguage.canonical(target);
        if(SpeechLanguage.canonical(source).equals(target))return hasSpeech(device,source,false);
        QwenModel qwen=QwenModel.fromId(textModel);
        if(qwen!=null)return qwen.isSupported(device)&&qwen.supportsLanguages(source,target)&&hasSpeech(device,source,false);
        return "opus".equals(textModel) && device.supportsText("opus") && !"yue".equals(source)
            && ("en".equals(target)||TranslationModel.supportsTarget(target)) && hasSpeech(device,source,!"en".equals(source));
    }
    private static boolean hasSpeech(DeviceProfile device,String source,boolean englishBridge) {
        for(NpuModel model:NpuModel.values())if(device.supports(model)&&model.supportsLanguage(source)&&(!englishBridge||model.supportsEnglishTranslation()))return true;
        return false;
    }
    static String recommended(DeviceProfile device,String source,String target) {
        if(SpeechLanguage.canonical(source).equals(target))return supports(device,source,target,"opus")?"opus":null;
        if(supports(device,source,target,QwenModel.GEMMA4.id))return QwenModel.GEMMA4.id;
        if(supports(device,source,target,QwenModel.ID))return QwenModel.ID;
        if(supports(device,source,target,QwenModel.HYMT2.id))return QwenModel.HYMT2.id;
        return supports(device,source,target,"opus")?"opus":null;
    }
    static String forSelection(DeviceProfile device,String source,String target,String selected) {
        return supports(device,source,target,selected)?selected:recommended(device,source,target);
    }
}
