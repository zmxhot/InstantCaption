package com.localcaption.instant;

import android.app.Instrumentation;
import android.content.Context;
import android.os.Bundle;
import android.os.SystemClock;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/** Injected device/catalog checks plus opt-in actual installed-model inference and language changes. */
final class DeviceLanguageVerification {
    private static final long GIB = 1024L * 1024L * 1024L;
    private static final String ENGLISH_REFERENCE = "Hello everyone welcome to our meeting we will discuss the new project today please speak slowly thank you for your help";

    static void run(Instrumentation test, Bundle options) {
        Context context = test.getTargetContext();
        JSONObject report = new JSONObject();
        Bundle result = new Bundle();
        LocalEngine engine = null;
        String originalLanguage = null;
        String originalTarget = null;
        NpuModel originalModel = null;
        java.util.Map<String, ?> originalEnginePreferences = null;
        Throwable failure = null;
        try {
            require(!CaptionState.S.running && !ModelStore.active, "Stop capture and model transfers before verification");
            report.put("device", android.os.Build.MODEL).put("androidSdk", android.os.Build.VERSION.SDK_INT);
            report.put("internetPermission", context.checkSelfPermission(android.Manifest.permission.INTERNET)
                == android.content.pm.PackageManager.PERMISSION_GRANTED);
            report.put("hardwareMatrixScope", "Injected device profiles verify filtering rules; only the connected phone runs actual inference");
            JSONArray hardware = new JSONArray();
            report.put("hardwareProfiles", hardware);
            hardwareChecks(hardware);
            report.put("modelLanguageRules", languageChecks());
            report.put("systemLanguageIndependentOfUiLocale", systemLanguageChecks(context));
            report.put("downloadCatalog", catalogChecks(context));
            report.put("sameLanguageAndEnglishBridgeRoutes", lightweightRouteChecks(context));
            DeviceProfile actual = DeviceProfile.detect(context);
            report.put("actualDevice", new JSONObject().put("summary", actual.summary(context))
                .put("englishModels", modelIds(actual.downloadableModels("en")))
                .put("japaneseModels", modelIds(actual.downloadableModels("ja")))
                .put("frenchModels", modelIds(actual.downloadableModels("fr"))));
            String requestedTargets = options.getString("targetModels", "");
            if (!requestedTargets.isEmpty()) {
                JSONArray targetModels = new JSONArray();
                report.put("offlineTargetModels", targetModels);
                targetModelChecks(test, context, requestedTargets, targetModels);
            }

            String requestedModels = options.getString("realModels", "");
            boolean transitions = enabled(options, "transitions");
            boolean multilingual = enabled(options, "multilingual");
            boolean bridgeStream = enabled(options, "bridgeStream");
            if (!requestedModels.isEmpty() || transitions || multilingual || bridgeStream) {
                engine = LocalEngine.get(context);
                originalLanguage = engine.sourceLanguage();
                originalTarget = engine.targetLanguage();
                originalModel = engine.selectedModel();
                originalEnginePreferences = context.getSharedPreferences("engine", 0).getAll();
                final LocalEngine selectedEngine = engine;
                test.runOnMainSync(() -> selectedEngine.setLanguages("en", "zh"));
                selectedEngine.prepare();
                awaitReady(test);
                JSONArray real = new JSONArray();
                report.put("realModels", real);
                for (NpuModel model : requestedModels(requestedModels)) {
                    awaitConfigurationIdle(selectedEngine);
                    require(actual.supports(model) && model.supportsLanguage("en"), "Requested model incompatible with this device/language: " + model.id);
                    require(ModelStore.installed(context, model), "Requested model is not installed: " + model.id);
                    JSONObject run = new JSONObject().put("model", model.id);
                    real.put(run);
                    run.put("files", verifyModelFiles(context, model));
                    long started = SystemClock.elapsedRealtime();
                    test.runOnMainSync(() -> selectedEngine.selectModel(model));
                    awaitReady(test);
                    require(selectedEngine.selectedModel() == model, "Model selection did not apply: " + model.id);
                    require(selectedEngine.isModelCompatible(model), "Selected model not reported compatible");
                    run.put("loadMs", SystemClock.elapsedRealtime() - started)
                        .put("engine", selectedEngine.engineName())
                        .put("sourceLanguage", selectedEngine.sourceLanguage())
                        .put("translationBackend", selectedEngine.translationBackend())
                        .put("englishBridge", selectedEngine.recognitionUsesEnglishBridge());
                    short[] audio = NpuBenchmark.wave(test, "english.wav");
                    started = SystemClock.elapsedRealtime();
                    String recognized = selectedEngine.recognizeNpuBlocking(audio);
                    long decode = SystemClock.elapsedRealtime() - started;
                    int[] distance = NpuBenchmark.wer(ENGLISH_REFERENCE, recognized);
                    double wer = (double) distance[0] / distance[1];
                    run.put("recognized", recognized).put("wordErrors", distance[0]).put("referenceWords", distance[1])
                        .put("wer", wer).put("decodeMs", decode).put("audioSeconds", audio.length / 16000.0)
                        .put("decodeRealTimeFactor", decode / (audio.length / 16.0));
                    require(wer <= 0.40, "English fixture recognition sanity failed: " + run);
                    started = SystemClock.elapsedRealtime();
                    String translated = selectedEngine.translateBlocking(recognized);
                    run.put("chinese", translated).put("translationMs", SystemClock.elapsedRealtime() - started);
                    require(hasChinese(translated), "Actual speech output produced no Chinese: " + model.id);
                    if (enabled(options, "stream")) {
                        run.put("stream", NpuBenchmark.stream(test, selectedEngine, audio, true));
                        JSONArray negatives = new JSONArray();
                        run.put("environmentOnlyScenes", negatives);
                        for (String scene : new String[]{"silence", "rain", "clock", "vacuum", "keyboard", "dog", "engine"}) {
                            awaitConfigurationIdle(selectedEngine);
                            short[] nonSpeech = "silence".equals(scene) ? new short[80000]
                                : NpuBenchmark.wave(test, "environment/" + scene + ".wav");
                            JSONObject negative = NpuBenchmark.stream(test, selectedEngine, nonSpeech, false).put("scene", scene);
                            negatives.put(negative);
                            require(negative.getInt("lines") == 0, "Environment-only sound generated subtitles: " + model.id + " / " + scene);
                        }
                    }
                    run.put("passed", true);
                    NpuBenchmark.progress(test, run);
                }
                if (transitions) {
                    awaitConfigurationIdle(selectedEngine);
                    JSONArray changes = new JSONArray();
                    report.put("sourceTransitions", changes);
                    transitionChecks(test, selectedEngine, changes);
                }
                if (multilingual) {
                    JSONObject fixtures = new JSONObject();
                    report.put("multilingualFixtures", fixtures);
                    multilingualChecks(test, context, fixtures);
                }
                if (bridgeStream) {
                    JSONArray streams = new JSONArray();
                    report.put("bridgeStreams", streams);
                    bridgeStreamChecks(test, context, selectedEngine, streams);
                }
            }
            report.put("realInferenceScope", "Optional English fixture is a small sanity/latency sample, not multilingual accuracy or cross-phone performance validation");
        } catch (Throwable error) {
            failure = error;
        } finally {
            if (engine != null && originalLanguage != null && originalTarget != null && originalModel != null) {
                try {
                    if (CaptionState.S.preparing) awaitSetupFinished(test);
                    awaitConfigurationIdle(engine);
                    final LocalEngine selectedEngine = engine;
                    final String language = originalLanguage;
                    final String target = originalTarget;
                    final NpuModel model = originalModel;
                    test.runOnMainSync(() -> selectedEngine.setLanguages(language, target));
                    awaitSetupFinished(test);
                    if (selectedEngine.isModelCompatible(model) && ModelStore.installed(context, model)) {
                        test.runOnMainSync(() -> selectedEngine.selectModel(model));
                        selectedEngine.prepare();
                        awaitReady(test);
                    }
                    report.put("restoredSourceLanguage", selectedEngine.sourceLanguage())
                        .put("restoredTargetLanguage", selectedEngine.targetLanguage())
                        .put("restoredModel", selectedEngine.selectedModel().id);
                    require(language.equals(selectedEngine.sourceLanguage()), "Original source language not restored");
                    require(target.equals(selectedEngine.targetLanguage()), "Original target language not restored");
                } catch (Throwable restoreFailure) {
                    if (failure == null) failure = restoreFailure;
                    else failure.addSuppressed(restoreFailure);
                }
                if (originalEnginePreferences != null) {
                    android.content.SharedPreferences preferences = context.getSharedPreferences("engine", 0);
                    android.content.SharedPreferences.Editor editor = preferences.edit();
                    for (String key : preferences.getAll().keySet()) if (configurationPreference(key)) editor.remove(key);
                    for (java.util.Map.Entry<String, ?> saved : originalEnginePreferences.entrySet())
                        if (configurationPreference(saved.getKey()) && saved.getValue() instanceof String)
                            editor.putString(saved.getKey(), (String) saved.getValue());
                    editor.commit();
                }
            }
        }
        try {
            report.put("passed", failure == null);
            if (failure != null) report.put("error", android.util.Log.getStackTraceString(failure));
            write(context, report);
        } catch (Exception saveFailure) {
            if (failure == null) failure = saveFailure;
        }
        result.putString("stream", report.toString());
        test.finish(failure == null ? 0 : 1, result);
    }

    private static JSONObject systemLanguageChecks(Context context) throws Exception {
        android.os.LocaleList globalLocales;
        String provider;
        if (android.os.Build.VERSION.SDK_INT >= 33) {
            android.app.LocaleManager manager = context.getSystemService(android.app.LocaleManager.class);
            require(manager != null, "LocaleManager service missing on Android 13+");
            globalLocales = manager.getSystemLocales();
            provider = "LocaleManager.getSystemLocales";
        } else {
            globalLocales = android.content.res.Resources.getSystem().getConfiguration().getLocales();
            provider = "Resources.getSystem fallback (API 29-32)";
        }
        String globalTag = globalLocales.isEmpty() ? "en" : globalLocales.get(0).toLanguageTag();
        require(globalTag.equals(SpeechLanguage.systemLanguageTag(context)), "Base context did not read the global system locale");
        JSONArray overrides = new JSONArray();
        for (String uiTag : new String[]{"fr-FR", "en-US", "ja-JP"}) {
            android.content.res.Configuration configuration = new android.content.res.Configuration(context.getResources().getConfiguration());
            configuration.setLocales(new android.os.LocaleList(Locale.forLanguageTag(uiTag)));
            Context localized = context.createConfigurationContext(configuration);
            String resourceTag = localized.getResources().getConfiguration().getLocales().get(0).toLanguageTag();
            require(uiTag.equals(resourceTag), "Test configuration context did not apply its local locale");
            String observed = SpeechLanguage.systemLanguageTag(localized);
            require(globalTag.equals(observed), "Speech default followed local UI override: " + uiTag + " -> " + observed);
            String[] expectedPair = SpeechLanguage.defaultPair(globalTag);
            String[] observedPair = SpeechLanguage.configuredPair(null, null, observed);
            require(java.util.Arrays.equals(expectedPair, observedPair), "Initial speech direction changed with UI locale");
            overrides.put(new JSONObject().put("uiResourceLocale", resourceTag).put("systemLanguageTag", observed)
                .put("initialSource", observedPair[0]).put("initialTarget", observedPair[1]).put("passed", true));
        }
        return new JSONObject().put("globalLanguageTag", globalTag).put("globalLocaleProvider", provider)
            .put("configurationContextOverrides", overrides).put("systemAndAppLocaleSettingsModified", false)
            .put("scope", "Local ConfigurationContext overrides only; does not change or simulate system-wide or per-app locale settings")
            .put("passed", true);
    }

    private static void hardwareChecks(JSONArray results) throws Exception {
        profileCase(results, "Samsung SM8750", "SM8750", 12 * GIB, false, true);
        profileCase(results, "SM8750 suffix", "sm8750-ac", 12 * GIB, false, true);
        profileCase(results, "SM8850 documented", "SM8850", 12 * GIB, false, true);
        profileCase(results, "SM8850 suffix", "sm8850-5-ac", 12 * GIB, false, true);
        for (String soc : new String[]{"SM87500", "MT6991", "Dimensity 9400", "Exynos 2400", "Kirin 9010", "Tensor G4", ""})
            profileCase(results, soc.isEmpty() ? "unknown SoC" : soc, soc, 8 * GIB, false, false);
        DeviceProfile lowMemory = new DeviceProfile(36, new String[]{"arm64-v8a"}, "MT6991", 4 * GIB, false);
        require(lowMemory.recommendedModel("en") == NpuModel.WHISPER_TINY, "Low-memory English did not recommend Whisper Tiny");
        require(lowMemory.recommendedModel("fr") == NpuModel.WHISPER_TINY, "Low-memory French did not recommend Whisper Tiny");
        require(lowMemory.recommendedModel("yue") == NpuModel.SENSEVOICE_CPU, "Cantonese selected incompatible Whisper Tiny");
        results.put(new JSONObject().put("profile", "4 GiB portable").put("englishRecommendation", lowMemory.recommendedModel("en").id)
            .put("cantoneseRecommendation", lowMemory.recommendedModel("yue").id).put("passed", true));
        DeviceProfile lowRamFlag = new DeviceProfile(36, new String[]{"arm64-v8a"}, "Tensor G4", 12 * GIB, true);
        require(lowRamFlag.recommendedModel("fr") == NpuModel.WHISPER_TINY, "Android low-RAM flag ignored");
        results.put(new JSONObject().put("profile", "Android low-RAM flag").put("passed", true));
        unsupportedProfile(results, "API below minimum", new DeviceProfile(28, new String[]{"arm64-v8a"}, "SM8750", 12 * GIB, false));
        unsupportedProfile(results, "32-bit ARM only", new DeviceProfile(36, new String[]{"armeabi-v7a"}, "SM8750", 12 * GIB, false));
        unsupportedProfile(results, "x86 only", new DeviceProfile(36, new String[]{"x86_64"}, "unknown", 12 * GIB, false));
    }

    private static void profileCase(JSONArray results, String name, String soc, long ram, boolean lowRam, boolean npu) throws Exception {
        DeviceProfile profile = new DeviceProfile(36, new String[]{"arm64-v8a", "armeabi-v7a"}, soc, ram, lowRam);
        for (NpuModel model : NpuModel.values())
            require(profile.supports(model) == (!model.isNpu || npu), name + " incorrectly supports " + model.id);
        for (SpeechLanguage language : SpeechLanguage.values()) {
            List<NpuModel> visible = profile.downloadableModels(language.id);
            require(!visible.isEmpty(), name + " has no model for " + language.id);
            for (NpuModel model : visible)
                require(profile.supports(model) && model.supportsLanguage(language.id), name + " exposed incompatible " + model.id + " for " + language.id);
            NpuModel recommendation = profile.recommendedModel(language.id);
            require(recommendation != null && visible.contains(recommendation), name + " recommended a hidden/incompatible model for " + language.id);
        }
        require(profile.downloadableModels("en").size() == (npu ? 7 : 3), name + " English catalog count incorrect");
        require(profile.downloadableModels("fr").size() == 2, name + " French should show the two Whisper sizes");
        require(profile.downloadableModels("yue").size() == (npu ? 2 : 1), name + " Cantonese incorrectly includes unsupported models");
        if (npu) {
            require(profile.recommendedModel("en") == NpuModel.X_ASR, "SM8750 English did not prefer compatible NPU");
            require(profile.recommendedModel("ja") == NpuModel.SENSEVOICE, "SM8750 Japanese did not prefer compatible SenseVoice NPU");
        }
        results.put(new JSONObject().put("profile", name).put("npuCompatible", npu)
            .put("englishModels", modelIds(profile.downloadableModels("en")))
            .put("frenchModels", modelIds(profile.downloadableModels("fr")))
            .put("cantoneseModels", modelIds(profile.downloadableModels("yue")))
            .put("languagesChecked", SpeechLanguage.values().length).put("passed", true));
    }

    private static void unsupportedProfile(JSONArray results, String label, DeviceProfile profile) throws Exception {
        for (NpuModel model : NpuModel.values()) require(!profile.supports(model), label + " exposed " + model.id);
        require(profile.downloadableModels("en").isEmpty() && profile.recommendedModel("en") == null, label + " offered unsupported download");
        results.put(new JSONObject().put("profile", label).put("visibleModelCount", 0).put("passed", true));
    }

    private static JSONObject languageChecks() throws Exception {
        require(NpuModel.X_ASR.supportsLanguage("en-US") && NpuModel.X_ASR.supportsLanguage("zh-Hans"), "Regional English/Chinese not normalized");
        require(!NpuModel.X_ASR.supportsLanguage("ja") && !NpuModel.X_ASR.supportsLanguage("auto"), "Bilingual X-ASR exposed unsupported languages");
        require(NpuModel.NEMOTRON.supportsLanguage("en") && !NpuModel.NEMOTRON.supportsLanguage("zh"), "English Nemotron language rule incorrect");
        for (NpuModel model : new NpuModel[]{NpuModel.SENSEVOICE, NpuModel.SENSEVOICE_CPU}) {
            for (String language : new String[]{"en", "zh", "ja", "ko", "yue", "auto"}) require(model.supportsLanguage(language), "SenseVoice missing " + language);
            require(!model.supportsLanguage("fr"), "SenseVoice exposed French");
        }
        for (NpuModel model : new NpuModel[]{NpuModel.WHISPER_TINY, NpuModel.WHISPER_BASE}) {
            for (String language : new String[]{"en", "zh-Hans", "ja", "fr", "pt-BR", "ar", "hi", "ta", "auto"}) require(model.supportsLanguage(language), "Whisper missing " + language);
            require(!model.supportsLanguage("yue") && !model.supportsLanguage("zz"), "99-language Whisper exposed unsupported language");
        }
        JSONArray languages = new JSONArray();
        Set<String> ids = new HashSet<>();
        for (SpeechLanguage language : SpeechLanguage.values()) {
            require(ids.add(language.id), "Duplicate source language: " + language.id);
            require(SpeechLanguage.fromId(language.id) == language, "Source language round-trip failed: " + language.id);
            languages.put(language.id);
        }
        require(ids.contains("en") && ids.contains("zh") && ids.contains("yue") && ids.contains("ta"), "Source language catalog is incomplete");
        JSONArray targets = new JSONArray();
        for (SpeechLanguage language : SpeechLanguage.targets()) {
            require(!"yue".equals(language.id), "Cantonese exposed as separate unsupported translation target");
            targets.put(language.id);
        }
        JSONArray defaults = new JSONArray();
        for (String[] expected : new String[][]{
            {"en-US", "es", "en"}, {"es-ES", "en", "es"}, {"fr-FR", "en", "fr"},
            {"zh-CN", "en", "zh"}, {"zh-Hant", "en", "zh"}, {"ja-JP", "en", "ja"},
            {"xx-XX", "en", "zh"}
        }) {
            String[] pair = SpeechLanguage.defaultPair(expected[0]);
            require(pair.length == 2 && expected[1].equals(pair[0]) && expected[2].equals(pair[1]),
                "Incorrect default language pair for system locale " + expected[0]);
            defaults.put(new JSONObject().put("systemLocale", expected[0]).put("source", pair[0]).put("target", pair[1]));
        }
        JSONArray persisted = new JSONArray();
        for (String system : new String[]{"en-US", "es-ES", "fr-FR", "zh-CN", "ja-JP", "xx-XX"}) {
            String[] pair = SpeechLanguage.configuredPair("ja", "fr", system);
            require("ja".equals(pair[0]) && "fr".equals(pair[1]), "Saved manual pair was reset after system locale change: " + system);
            persisted.put(new JSONObject().put("systemLocale", system).put("savedSource", pair[0]).put("savedTarget", pair[1]));
        }
        String[] fresh = SpeechLanguage.configuredPair(null, null, "en-US");
        require("es".equals(fresh[0]) && "en".equals(fresh[1]), "Fresh English-system default not applied");
        String[] canonicalSaved = SpeechLanguage.configuredPair("en-US", "zh-Hant", "fr-FR");
        require("en".equals(canonicalSaved[0]) && "zh".equals(canonicalSaved[1]), "Saved regional language tags not canonicalized");
        String[] partial = SpeechLanguage.configuredPair("ja", null, "es-ES");
        require("ja".equals(partial[0]) && "es".equals(partial[1]), "Missing target default overwrote saved source");
        return new JSONObject().put("supportedSourceLanguages", languages).put("regionalTagsNormalized", true)
            .put("translationTargets", targets).put("systemDefaultPairs", defaults)
            .put("persistedPairAcrossSystemLocales", persisted).put("initializationUsesSavedPreferences", true)
            .put("unsupportedModelLanguagesFiltered", true).put("passed", true);
    }

    private static JSONArray catalogChecks(Context context) throws Exception {
        JSONArray results = new JSONArray();
        for (NpuModel model : NpuModel.values()) {
            JSONObject spec = ModelStore.spec(context, model), download = spec.getJSONObject("download");
            require(model.directory(DeviceProfile.detect(context)).equals(spec.getString("directory")), "Model directory mismatch: " + model.id);
            require(download.getLong("size") > 0 && isHash(download.getString("sha256")), "Unpinned archive: " + model.id);
            JSONArray sources = download.getJSONArray("urls"), files = spec.getJSONArray("files");
            require(sources.length() >= 2, "No backup model source: " + model.id);
            for (int i = 0; i < sources.length(); i++)
                require(sources.getJSONObject(i).getString("url").startsWith("https://"), "Non-HTTPS model source");
            require(files.length() > 0, "No model files listed: " + model.id);
            Set<String> filenames = new HashSet<>();
            for (int i = 0; i < files.length(); i++) {
                JSONObject file = files.getJSONObject(i);
                String name = file.getString("name");
                require(filenames.add(name) && !name.contains("/") && !name.contains("\\") && !name.equals(".."), "Unsafe/duplicate file name: " + name);
                require(file.getLong("size") > 0 && isHash(file.getString("sha256")), "Unpinned model file: " + name);
            }
            results.put(new JSONObject().put("model", model.id).put("files", files.length()).put("sources", sources.length()).put("passed", true));
        }
        return results;
    }

    private static JSONArray verifyModelFiles(Context context, NpuModel model) throws Exception {
        JSONArray files = ModelStore.spec(context, model).getJSONArray("files"), results = new JSONArray();
        for (int i = 0; i < files.length(); i++) {
            JSONObject expected = files.getJSONObject(i);
            File file = new File(ModelStore.root(context, model), expected.getString("name"));
            require(file.length() == expected.getLong("size"), "Installed model size mismatch: " + file.getName());
            String actual = sha256(file);
            require(actual.equals(expected.getString("sha256")), "Installed model hash mismatch: " + file.getName());
            results.put(new JSONObject().put("name", file.getName()).put("bytes", file.length()).put("sha256", actual));
        }
        return results;
    }

    private static void targetModelChecks(Instrumentation test, Context context, String requested, JSONArray results) throws Exception {
        Set<String> visited = new HashSet<>();
        for (String value : requested.split(",")) {
            String target = NpuModel.canonicalLanguage(value);
            require("zh".equals(target) || "de".equals(target), "Target gold checks currently cover zh and de only: " + value);
            if (!visited.add(target)) continue;
            JSONObject run = new JSONObject().put("target", target).put("source", "en")
                .put("scope", "Installed target-specific Marian weights and direct independent local inference; no model download");
            results.put(run);
            require(TranslationModel.installed(context, target), "Requested translation model not installed: " + target);
            JSONObject spec = TranslationModel.spec(context, target);
            JSONArray expectedFiles = spec.getJSONArray("files"), checkedFiles = new JSONArray();
            run.put("files", checkedFiles);
            long total = 0;
            for (int i = 0; i < expectedFiles.length(); i++) {
                JSONObject expected = expectedFiles.getJSONObject(i);
                File file = new File(TranslationModel.root(context, target), expected.getString("name"));
                require(file.length() == expected.getLong("size"), "Target model file size mismatch: " + target + "/" + file.getName());
                String hash = sha256(file);
                require(hash.equals(expected.getString("sha256")), "Target model file checksum mismatch: " + target + "/" + file.getName());
                total += file.length();
                checkedFiles.put(new JSONObject().put("name", file.getName()).put("bytes", file.length()).put("sha256", hash));
            }
            require(total == TranslationModel.bytes(context, target), "Translation model total-byte accounting mismatch");
            run.put("installedBytes", total);
            String[][] gold = targetGold(target);
            JSONArray phrases = new JSONArray();
            run.put("translations", phrases);
            long started = SystemClock.elapsedRealtime();
            String firstOutput;
            try (MarianTranslator translator = new MarianTranslator(TranslationModel.root(context, target), spec)) {
                run.put("loadMs", SystemClock.elapsedRealtime() - started);
                firstOutput = null;
                for (String[] phrase : gold) {
                    started = SystemClock.elapsedRealtime();
                    String output = translator.translate(phrase[0]);
                    JSONObject item = new JSONObject().put("en", phrase[0]).put("output", output)
                        .put("ms", SystemClock.elapsedRealtime() - started);
                    phrases.put(item);
                    boolean accepted = false;
                    for (int i = 1; i < phrase.length; i++) accepted |= normalizedGold(phrase[i]).equals(normalizedGold(output));
                    item.put("acceptedExpectedVariant", accepted);
                    require(accepted, "Target translation short-phrase regression: " + target + " " + item);
                    if (firstOutput == null) firstOutput = output;
                }
                require(translator.translate(" \t\n").isEmpty(), "Blank input produced target subtitles");
                boolean cancelled = false;
                try { translator.translate("This request has been cancelled.", () -> false); }
                catch (java.util.concurrent.CancellationException expected) { cancelled = true; }
                require(cancelled, "Target translator ignored cancelled request");
                started = SystemClock.elapsedRealtime();
                String repeated = translator.translate(gold[0][0]);
                require(firstOutput.equals(repeated), "Target translation changed on cached repeat");
                run.put("cachedRepeatMs", SystemClock.elapsedRealtime() - started)
                    .put("blankSuppressed", true).put("cancellationAndRecovery", true);
            }
            String unrelatedPreference = "zh".equals(target) ? "de" : "zh";
            Context fixedPreference = new FixedTargetContext(context, unrelatedPreference);
            AndroidTranslationFactory factory = new AndroidTranslationFactory(fixedPreference, "en", target);
            require(factory.opusInstalled(), "Captured target used unrelated preferred-language installation check");
            started = SystemClock.elapsedRealtime();
            try (TranslationRouter router = new TranslationRouter(offlineFactory(factory, false))) {
                router.prepare();
                require(router.ready() && "opus".equals(router.backend()), "Captured target did not select OPUS");
                String output = router.translate(gold[0][0], () -> true);
                require(firstOutput.equals(output), "Factory loaded current preferences instead of captured target: " + target + " / " + output);
                run.put("capturedTargetBinding", new JSONObject().put("factoryTarget", target)
                    .put("injectedPreferenceTarget", unrelatedPreference).put("output", output)
                    .put("prepareAndTranslateMs", SystemClock.elapsedRealtime() - started).put("passed", true));
            }
            run.put("passed", true);
            NpuBenchmark.progress(test, run);
        }
    }

    private static String[][] targetGold(String target) {
        if ("zh".equals(target)) return new String[][]{
            {"Hello everyone.", "大家好", "各位好", "你们好", "各位大家好", "大家好啊"},
            {"I don't understand.", "我不明白", "我不懂", "我不理解", "我听不懂"},
            {"I would like a glass of water.", "我想要一杯水", "我要一杯水", "我想喝一杯水", "我想来一杯水", "我需要一杯水", "我想要杯水", "我想喝杯水"}
        };
        return new String[][]{
            {"Hello everyone.", "Hallo zusammen", "Hallo alle zusammen", "Hallo allerseits", "Hallo miteinander", "Hallo an alle", "Hallo alle", "Hallo Leute", "Hallo ihr alle", "Hallo euch allen"},
            {"I don't understand.", "Ich verstehe nicht", "Ich verstehe es nicht", "Ich verstehe das nicht"},
            {"I would like a glass of water.", "Ich möchte ein Glas Wasser", "Ich möchte gerne ein Glas Wasser", "Ich hätte gern ein Glas Wasser", "Ich hätte gerne ein Glas Wasser", "Ich würde gern ein Glas Wasser trinken", "Ich würde gerne ein Glas Wasser trinken", "Ich würde gerne ein Glas Wasser haben"}
        };
    }

    private static JSONArray lightweightRouteChecks(Context context) throws Exception {
        JSONArray results = new JSONArray();
        for (String[] pair : new String[][]{{"en", "en"}, {"ja", "ja"}, {"zh", "zh"}, {"yue", "zh"}}) {
            AndroidTranslationFactory factory = new AndroidTranslationFactory(context, pair[0], pair[1]);
            require(factory.passthrough(), "Canonical same-language pair is not passthrough");
            try (TranslationRouter router = new TranslationRouter(offlineFactory(factory, true))) {
                router.prepare();
                require("passthrough".equals(router.backend()), "Same-language route consulted translation engines");
                String text = "Original text · 你好 · こんにちは";
                require(text.equals(router.translate(text, () -> true)), "Same-language route changed original text");
                boolean cancelled = false;
                try { router.translate(text, () -> false); }
                catch (java.util.concurrent.CancellationException expected) { cancelled = true; }
                require(cancelled, "Same-language route ignored cancellation");
                results.put(new JSONObject().put("source", pair[0]).put("target", pair[1])
                    .put("backend", router.backend()).put("translationModelAccess", false).put("passed", true));
            }
        }
        AndroidTranslationFactory english = new AndroidTranslationFactory(new FixedTargetContext(context, "de"), "ja", "en");
        try (TranslationRouter router = new TranslationRouter(offlineFactory(english, false))) {
            router.prepare();
            require("speech-english".equals(router.backend()), "English target did not use translated speech output");
            String fromWhisper = "This English text came from the local speech model.";
            require(fromWhisper.equals(router.translate(fromWhisper, () -> true)), "English bridge tried to translate English output again");
            results.put(new JSONObject().put("source", "ja").put("target", "en").put("backend", router.backend())
                .put("scope", "Factory/router consumes already-translated English; real Whisper translation is tested by multilingual fixtures")
                .put("passed", true));
        }
        return results;
    }

    private static TranslationRouter.Factory offlineFactory(AndroidTranslationFactory real, boolean prohibitAnyModel) {
        return new TranslationRouter.Factory() {
            @Override public boolean passthrough() { return real.passthrough(); }
            @Override public String fallbackName() { return real.fallbackName(); }
            @Override public boolean opusInstalled() {
                require(!prohibitAnyModel, "Same-language path checked model installation");
                return real.opusInstalled();
            }
            @Override public TranslationRouter.Backend opus() throws Exception {
                require(!prohibitAnyModel, "Same-language path loaded a translation model");
                return real.opus();
            }
        };
    }

    /** Injects a different saved target without modifying app preferences or global locale. */
    private static final class FixedTargetContext extends android.content.ContextWrapper {
        private final String target;
        FixedTargetContext(Context context, String target) { super(context.getApplicationContext()); this.target = target; }
        @Override public Context getApplicationContext() { return this; }
        @Override public android.content.SharedPreferences getSharedPreferences(String name, int mode) {
            android.content.SharedPreferences base = super.getSharedPreferences(name, mode);
            if (!"engine".equals(name)) return base;
            return (android.content.SharedPreferences) java.lang.reflect.Proxy.newProxyInstance(
                android.content.SharedPreferences.class.getClassLoader(), new Class<?>[]{android.content.SharedPreferences.class},
                (proxy, method, args) -> {
                    if ("getString".equals(method.getName()) && args != null && "speechTarget".equals(args[0])) return target;
                    if ("edit".equals(method.getName())) throw new AssertionError("Factory attempted to change injected preferences");
                    return method.invoke(base, args);
                });
        }
    }

    private static String normalizedGold(String value) {
        return java.text.Normalizer.normalize(value, java.text.Normalizer.Form.NFKC)
            .toLowerCase(Locale.ROOT).replaceAll("[\\p{P}\\p{Z}\\s]", "");
    }

    private static void transitionChecks(Instrumentation test, LocalEngine engine, JSONArray results) throws Exception {
        String heldSource = engine.sourceLanguage(), heldTarget = engine.targetLanguage();
        NpuModel heldModel = engine.selectedModel();
        long heldGeneration = engine.generation();
        engine.sessionOpened();
        try {
            require(engine.configurationBusy(), "Unclosed recognition session did not block configuration");
            test.runOnMainSync(() -> engine.setLanguages("fr", "fr"));
            require(heldSource.equals(engine.sourceLanguage()) && heldTarget.equals(engine.targetLanguage()), "Language changed while a recognition session remained open");
            require(engine.generation() == heldGeneration && engine.selectedModel() == heldModel, "Busy configuration changed generation or model");
            require(!engine.unloadForRemoval(heldModel) && !engine.unloadTranslation(), "A model was unloaded while recognition session remained open");
        } finally {
            engine.sessionClosed();
        }
        require(!engine.configurationBusy(), "Session closure did not release configuration guard");
        results.put(new JSONObject().put("case", "Open session blocks reconfiguration and unloading")
            .put("scope", "Injected session lifetime counter; actual recognition lifecycle is exercised separately by streaming tests")
            .put("passed", true));
        for (String source : new String[]{"ja", "en"}) {
            NpuRecognizer previous = engine.npuRecognizer();
            long generation = engine.generation(), started = SystemClock.elapsedRealtime();
            test.runOnMainSync(() -> engine.setLanguages(source, "zh"));
            require(engine.generation() > generation, "Source change did not invalidate pending work: " + source);
            awaitReady(test);
            require(source.equals(engine.sourceLanguage()), "Source language did not persist: " + source);
            require(engine.isModelCompatible(engine.selectedModel()), "Source change kept incompatible model");
            require(engine.npuRecognizer() != previous, "Source change reused old recognizer configuration");
            JSONObject item = new JSONObject().put("source", source).put("model", engine.selectedModel().id)
                .put("translationBackend", engine.translationBackend()).put("englishBridge", engine.recognitionUsesEnglishBridge())
                .put("reloadMs", SystemClock.elapsedRealtime() - started).put("generationInvalidated", true).put("recognizerReplaced", true);
            require(source.equals(engine.npuRecognizer().sourceLanguage), "Recognizer retained previous source-language configuration");
            if ("en".equals(source)) {
                String zh = engine.translateBlocking("Please speak slowly.");
                require(hasChinese(zh), "English translation did not recover after source changes");
                item.put("chinese", zh);
            }
            results.put(item);
        }
        for (String target : new String[]{"en", "zh"}) {
            NpuRecognizer previous = engine.npuRecognizer();
            long generation = engine.generation(), started = SystemClock.elapsedRealtime();
            test.runOnMainSync(() -> engine.setLanguages("en", target));
            require(engine.generation() > generation, "Target change did not invalidate pending work: " + target);
            awaitReady(test);
            require(target.equals(engine.targetLanguage()), "Target language not saved: " + target);
            require(engine.npuRecognizer().sourceLanguage.equals(engine.sourceLanguage()), "Target change left mismatched recognizer source language");
            require(engine.npuRecognizer().englishBridge == engine.recognitionUsesEnglishBridge(), "Target change left mismatched recognizer translation task");
            String input = "Please speak slowly.", output = engine.translateBlocking(input);
            if ("en".equals(target)) require(input.equals(output), "Identical source/target changed the transcript");
            else require(hasChinese(output), "Chinese target did not recover after target changes");
            results.put(new JSONObject().put("source", "en").put("target", target)
                .put("translationBackend", engine.translationBackend()).put("output", output)
                .put("reloadMs", SystemClock.elapsedRealtime() - started).put("generationInvalidated", true)
                .put("recognizerReused", engine.npuRecognizer() == previous).put("recognizerConfigurationMatches", true));
        }
    }

    private static void bridgeStreamChecks(Instrumentation test, Context context, LocalEngine engine, JSONArray results) throws Exception {
        require(ModelStore.installed(context, NpuModel.WHISPER_BASE), "Whisper Base must be installed for bridge streaming checks");
        for (String[] pair : new String[][]{{"es", "en"}, {"ja", "zh"}}) {
            String source = pair[0], target = pair[1], asset = "multilingual/" + source + ".wav";
            JSONObject run = new JSONObject().put("sourceLanguage", source).put("targetLanguage", target)
                .put("asset", asset).put("model", NpuModel.WHISPER_BASE.id).put("paced", true)
                .put("scope", "Actual VAD and caption pipeline; nonempty/configuration/script checks only, not semantic accuracy")
                .put("translationQualityAssessed", false);
            results.put(run);
            try (java.io.InputStream input = test.getContext().getAssets().open(
                    "multilingual/" + ("es".equals(source) ? "es-fixture.json" : "fixtures.json"))) {
                run.put("fixtureProvenance", new JSONObject(new String(input.readAllBytes(), StandardCharsets.UTF_8)));
            }
            awaitConfigurationIdle(engine);
            long started = SystemClock.elapsedRealtime();
            test.runOnMainSync(() -> engine.setLanguages(source, target));
            engine.prepare();
            awaitReady(test);
            awaitConfigurationIdle(engine);
            test.runOnMainSync(() -> engine.selectModel(NpuModel.WHISPER_BASE));
            awaitReady(test);
            require(source.equals(engine.sourceLanguage()) && target.equals(engine.targetLanguage()), "Bridge stream language pair did not apply");
            require(engine.selectedModel() == NpuModel.WHISPER_BASE, "Bridge stream did not select Whisper Base");
            require(source.equals(engine.npuRecognizer().sourceLanguage)
                && engine.npuRecognizer().englishBridge == engine.recognitionUsesEnglishBridge(), "Recognizer configuration mismatched bridge stream route");
            run.put("prepareMs", SystemClock.elapsedRealtime() - started).put("translationBackend", engine.translationBackend())
                .put("englishBridge", engine.recognitionUsesEnglishBridge());
            JSONObject stream = NpuBenchmark.stream(test, engine, NpuBenchmark.wave(test, asset), true);
            String recognized = stream.getString("english"), translated = stream.getString("chinese");
            run.put("stream", stream).put("recognized", recognized).put("targetText", translated)
                .put("firstRecognizedMs", stream.getLong("firstEnglishMs")).put("firstTargetMs", stream.getLong("firstChineseMs"))
                .put("lines", stream.getInt("lines")).put("repetition", WhisperDiagnostics.repetition(recognized));
            List<String[]> snapshots = new ArrayList<>();
            test.runOnMainSync(() -> {
                for (CaptionState.Line line : CaptionState.S.lines)
                    snapshots.add(new String[]{Long.toString(line.id), line.en, line.zh, Boolean.toString(line.done), Integer.toString(line.revision)});
            });
            JSONArray segments = new JSONArray();
            for (String[] line : snapshots) segments.put(new JSONObject().put("id", Long.parseLong(line[0]))
                .put("recognized", line[1]).put("targetText", line[2]).put("done", Boolean.parseBoolean(line[3])).put("revision", Integer.parseInt(line[4])));
            run.put("finalSegments", segments);
            require(!recognized.trim().isEmpty() && !translated.trim().isEmpty() && stream.getInt("lines") > 0, "Bridge stream produced no captions: " + source);
            require(stream.getLong("firstChineseMs") >= 0, "Bridge stream target caption timing missing: " + source);
            if ("zh".equals(target)) require(hasChinese(translated), "Chinese bridge target returned no Han characters");
            else require(translated.matches("(?s).*[A-Za-z].*")
                && !translated.matches("(?s).*[\\p{IsHan}\\p{IsHiragana}\\p{IsKatakana}\\p{IsHangul}].*"), "English target returned an unexpected script");
            run.put("runtimeCheckPassed", true).put("qualityStatus", "Output preserved for semantic review; script does not establish translation correctness");
            NpuBenchmark.progress(test, run);
            awaitConfigurationIdle(engine);
        }
    }

    private static void multilingualChecks(Instrumentation test, Context context, JSONObject result) throws Exception {
        result.put("scope", "Official small test WAVs; nonempty/script checks only, not multilingual accuracy scores");
        try (java.io.InputStream input = test.getContext().getAssets().open("multilingual/fixtures.json")) {
            result.put("fixtureProvenance", new JSONObject(new String(input.readAllBytes(), StandardCharsets.UTF_8)));
        }
        JSONArray runs = new JSONArray();
        result.put("runs", runs);
        require(ModelStore.installed(context, NpuModel.SENSEVOICE_CPU), "SenseVoice CPU not installed for multilingual verification");
        for (NpuModel senseModel : new NpuModel[]{NpuModel.SENSEVOICE_CPU, NpuModel.SENSEVOICE}) {
            if (senseModel.isNpu && (!DeviceProfile.detect(context).supports(senseModel) || !ModelStore.installed(context, senseModel))) {
                runs.put(new JSONObject().put("model", senseModel.id).put("skipped", true)
                    .put("reason", "Dedicated NPU not supported by this device or model not installed"));
                continue;
            }
        for (String source : new String[]{"en", "zh", "ja", "ko", "yue"}) {
            short[] audio = NpuBenchmark.wave(test, "multilingual/" + source + ".wav");
            long started = SystemClock.elapsedRealtime();
            try (NpuRecognizer recognizer = new NpuRecognizer(context, senseModel, source, false)) {
                long loaded = SystemClock.elapsedRealtime();
                String text = recognizer.recognize(audio);
                JSONObject run = new JSONObject().put("model", senseModel.id).put("source", source)
                    .put("text", text).put("loadMs", loaded - started).put("decodeMs", SystemClock.elapsedRealtime() - loaded)
                    .put("audioSeconds", audio.length / 16000.0).put("englishBridge", false);
                runs.put(run);
                require(!text.trim().isEmpty(), "Empty multilingual fixture: " + source);
                if ("zh".equals(source) || "yue".equals(source)) require(hasChinese(text), "Chinese/Cantonese fixture returned wrong script");
                else if ("ja".equals(source)) require(text.matches("(?s).*[\\p{IsHiragana}\\p{IsKatakana}].*"), "Japanese fixture returned no kana");
                else if ("ko".equals(source)) require(text.matches("(?s).*[\\p{IsHangul}].*"), "Korean fixture returned no Hangul");
                else require(text.toLowerCase(Locale.ROOT).contains("boy"), "English multilingual fixture missed known word");
                run.put("runtimeCheckPassed", true).put("translationQualityAssessed", false);
                NpuBenchmark.progress(test, run);
            }
        }
        }
        for (NpuModel model : new NpuModel[]{NpuModel.WHISPER_TINY, NpuModel.WHISPER_BASE}) {
            require(ModelStore.installed(context, model), "Whisper not installed for English-bridge verification: " + model.id);
            short[] audio = NpuBenchmark.wave(test, "multilingual/ja.wav");
            long started = SystemClock.elapsedRealtime();
            try (NpuRecognizer recognizer = new NpuRecognizer(context, model, "ja", true)) {
                long loaded = SystemClock.elapsedRealtime();
                String text = recognizer.recognize(audio);
                JSONObject run = new JSONObject().put("model", model.id).put("source", "ja").put("englishBridge", true)
                    .put("text", text).put("loadMs", loaded - started).put("decodeMs", SystemClock.elapsedRealtime() - loaded);
                runs.put(run);
                require(text.matches("(?s).*[A-Za-z].*") && text.trim().split("\\s+").length >= 3,
                    "Whisper translation task did not produce English: " + text);
                require(!text.matches("(?s).*[\\p{IsHiragana}\\p{IsKatakana}].*"), "Whisper translation task returned Japanese");
                run.put("runtimeCheckPassed", true).put("translationQualityAssessed", false)
                    .put("qualityStatus", "English-script output only; correctness requires review")
                    .put("repetitionWarning", WhisperDiagnostics.repetition(text).getBoolean("suspicious"));
                NpuBenchmark.progress(test, run);
            }
        }
        result.put("runtimeChecksPassed", true).put("translationQualityAssessed", false);
    }

    private static List<NpuModel> requestedModels(String value) {
        List<NpuModel> models = new ArrayList<>();
        if (value.isEmpty()) return models;
        if ("all".equals(value)) return java.util.Arrays.asList(NpuModel.WHISPER_TINY, NpuModel.WHISPER_BASE, NpuModel.SENSEVOICE_CPU);
        for (String entry : value.split(",")) {
            NpuModel model = "tiny".equals(entry) ? NpuModel.WHISPER_TINY : "base".equals(entry) ? NpuModel.WHISPER_BASE
                : "sensevoice-cpu".equals(entry) ? NpuModel.SENSEVOICE_CPU : null;
            if (model == null) for (NpuModel candidate : NpuModel.values()) if (candidate.id.equals(entry)) model = candidate;
            require(model != null, "Unknown requested model: " + entry);
            if (!models.contains(model)) models.add(model);
        }
        return models;
    }
    private static void awaitReady(Instrumentation test) throws Exception {
        awaitSetupFinished(test);
        require(CaptionState.S.ready, "Engine is not ready: " + CaptionState.S.status);
    }
    private static void awaitSetupFinished(Instrumentation test) throws Exception {
        long until = SystemClock.elapsedRealtime() + 300000;
        while (CaptionState.S.preparing && SystemClock.elapsedRealtime() < until) Thread.sleep(50);
        test.runOnMainSync(() -> {});
        require(!CaptionState.S.preparing, "Engine preparation timed out");
    }
    private static void awaitConfigurationIdle(LocalEngine engine) throws Exception {
        long until = SystemClock.elapsedRealtime() + 120000;
        while (engine.configurationBusy() && SystemClock.elapsedRealtime() < until) Thread.sleep(25);
        require(!engine.configurationBusy(), "Previous recognition/translation work did not release configuration");
    }
    private static JSONArray modelIds(List<NpuModel> models) {
        JSONArray ids = new JSONArray();
        for (NpuModel model : models) ids.put(model.id);
        return ids;
    }
    private static String sha256(File file) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        try (FileInputStream in = new FileInputStream(file)) {
            byte[] buffer = new byte[131072];
            int count;
            while ((count = in.read(buffer)) != -1) digest.update(buffer, 0, count);
        }
        StringBuilder out = new StringBuilder();
        for (byte value : digest.digest()) out.append(String.format(Locale.ROOT, "%02x", value & 255));
        return out.toString();
    }
    private static boolean isHash(String value) { return value.matches("[0-9a-f]{64}"); }
    private static boolean configurationPreference(String key) {
        return key.equals("speechSource") || key.equals("speechTarget") || key.equals("npuModel") || key.startsWith("modelFor:");
    }
    private static boolean hasChinese(String value) { return value != null && value.matches("(?s).*[\\p{IsHan}].*"); }
    private static boolean enabled(Bundle options, String name) {
        String value = options.getString(name, "false");
        return "true".equalsIgnoreCase(value) || "1".equals(value);
    }
    private static void write(Context context, JSONObject report) throws Exception {
        try (FileOutputStream out = new FileOutputStream(new File(context.getExternalFilesDir(null), "device-language-verification.json"))) {
            out.write(report.toString(2).getBytes(StandardCharsets.UTF_8));
        }
    }
    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
