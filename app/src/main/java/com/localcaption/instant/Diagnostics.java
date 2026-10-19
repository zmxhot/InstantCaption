package com.localcaption.instant;

import android.content.Context;
import android.os.Build;
import org.json.JSONObject;

/** Deliberate allowlist; never serializes state, logs, network identifiers or transcript. */
final class Diagnostics {
    static String export(Context c)throws Exception{
        LocalEngine engine=LocalEngine.get(c);DeviceProfile device=DeviceProfile.detect(c);
        return new JSONObject().put("appVersion",BuildConfig.VERSION_NAME).put("versionCode",BuildConfig.VERSION_CODE)
            .put("manufacturer",Build.MANUFACTURER).put("model",Build.MODEL).put("androidSdk",Build.VERSION.SDK_INT)
            .put("soc",device.socModel).put("arm64",device.arm64).put("totalRam",device.ramBytes)
            .put("availableRam",RuntimeHealth.availableMemory(c)).put("thermalStatus",RuntimeHealth.thermal(c))
            .put("sourceLanguage",engine.sourceLanguage()).put("targetLanguage",engine.targetLanguage())
            .put("speechChoice",engine.speechChoice()).put("speechBackend",engine.recognitionBackend())
            .put("translationChoice",engine.translationChoice()).put("translationBackend",engine.translationBackend())
            .put("ready",CaptionState.S.ready).put("pendingTranslations",engine.pendingTranslations())
            .put("asrMs",CaptionState.S.asrMs).put("translationMs",CaptionState.S.translateMs)
            .put("untranslatedSegments",CaptionState.S.skippedSegments).put("queuedDownloads",DownloadJournal.jobs(c).length()).toString(2);
    }
}
