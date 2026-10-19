package com.localcaption.instant;

import android.app.Instrumentation;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.os.SystemClock;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Map;
import java.util.concurrent.CancellationException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BooleanSupplier;

/** Independent local policy and real OPUS regressions. Historical mode name retained. */
final class AdaptiveTranslationVerification {
    static void run(Instrumentation test, Bundle options) {
        Context context = test.getTargetContext();
        JSONObject report = new JSONObject();
        Bundle result = new Bundle();
        LocalEngine engine = null;
        String originalSource = null, originalTarget = null;
        NpuModel originalModel = null;
        Map<String, ?> originalPreferences = null;
        Throwable failure = null;
        try {
            require(!CaptionState.S.running && !ModelStore.active, "Stop capture and transfers before verification");
            boolean internet = context.checkSelfPermission(android.Manifest.permission.INTERNET) == PackageManager.PERMISSION_GRANTED;
            report.put("package", context.getPackageName()).put("device", android.os.Build.MODEL)
                .put("androidSdk", android.os.Build.VERSION.SDK_INT).put("internetPermission", internet)
                .put("translationPolicy", "Independent local models only; no service checks or implicit downloads");
            if (enabled(options, "offline")) require(!internet, "Offline test build still has INTERNET permission");
            report.put("googleSdkAbsence", TranslationVerification.googleDependencyCheck(context));
            JSONArray cases = new JSONArray(); report.put("injectedPolicyCases", cases); policyChecks(cases);
            report.put("policyTestScope", "Injected local installation, loading and cancellation states; no real model files or device services changed");
            engine = LocalEngine.get(context);
            originalSource = engine.sourceLanguage(); originalTarget = engine.targetLanguage(); originalModel = engine.selectedModel();
            originalPreferences = context.getSharedPreferences("engine", 0).getAll();
            awaitIdle(engine);
            NpuModel speech = requestedSpeech(options.getString("speechModel", ""));
            if (speech == null && DeviceProfile.detect(context).supports(originalModel) && originalModel.supportsLanguage("en")
                    && ModelStore.installed(context, originalModel)) speech = originalModel;
            require(TranslationModel.installed(context, "zh"), "EN-to-ZH OPUS must already be installed");
            report.put("speechModelBefore", originalModel.id).put("engineAlreadyReadyBeforePrepare", CaptionState.S.ready);
            final LocalEngine selectedEngine = engine;
            long started = SystemClock.elapsedRealtime();
            test.runOnMainSync(() -> selectedEngine.setLanguages("en", "zh")); engine.prepare(); awaitReady(test);
            if (speech != null) {
                final NpuModel selectedSpeech = speech;
                require(engine.isModelCompatible(selectedSpeech) && ModelStore.installed(context, selectedSpeech), "Requested speech model unavailable: " + selectedSpeech.id);
                awaitIdle(engine); test.runOnMainSync(() -> selectedEngine.selectModel(selectedSpeech)); awaitReady(test);
                require(engine.selectedModel() == selectedSpeech, "Translation setup changed chosen speech model");
            }
            NpuModel preparedSpeech = engine.selectedModel(); NpuRecognizer preparedRecognizer = engine.npuRecognizer();
            engine.prepare(); awaitReady(test);
            require(engine.selectedModel() == preparedSpeech && engine.npuRecognizer() == preparedRecognizer, "Repeated preparation replaced ready speech recognizer");
            require("en".equals(engine.sourceLanguage()) && "zh".equals(engine.targetLanguage()), "EN-to-ZH pair did not apply");
            require("opus".equals(engine.translationBackend()), "EN-to-ZH did not select OPUS");
            require(options.getString("expectBackend", "opus").equals(engine.translationBackend()), "Unexpected translation backend");
            require(!engine.recognitionUsesEnglishBridge(), "English speech incorrectly enabled bridge");
            report.put("enginePreparationMs", SystemClock.elapsedRealtime() - started)
                .put("preparationTimingScope", "Language/ASR setup and translator load/warmup; not isolated cold translation latency")
                .put("translationBackend", engine.translationBackend()).put("speechModel", preparedSpeech.id)
                .put("chosenSpeechModelPreserved", true).put("readyRecognizerReused", true);
            String[][] gold = {
                {"Hello everyone.", "大家好", "各位好", "你们好", "各位大家好", "大家好啊"},
                {"I don't understand.", "我不明白", "我不懂", "我不理解", "我不明白了", "我听不懂"},
                {"I would like a glass of water.", "我想要一杯水", "我要一杯水", "我想喝一杯水", "我想来一杯水", "我需要一杯水", "我想要杯水", "我想喝杯水"}
            };
            JSONArray regressions = new JSONArray(); report.put("shortPhraseRegressions", regressions); String first = null;
            for (String[] phrase : gold) {
                JSONObject item = translate(engine, phrase[0]); regressions.put(item); boolean accepted = false;
                for (int i = 1; i < phrase.length; i++) accepted |= normalized(phrase[i]).equals(normalized(item.getString("zh")));
                item.put("acceptedExpectedVariant", accepted); require(accepted, "OPUS short-phrase regression: " + item);
                if (first == null) first = item.getString("zh");
            }
            JSONArray translations = new JSONArray(); report.put("translations", translations); ArrayList<Long> timings = new ArrayList<>();
            for (String input : new String[]{"Hello everyone, welcome to our meeting.", "Please speak slowly.",
                    "We will discuss the new project today.", "Where is the nearest train station?", "Thank you for your help."}) {
                JSONObject item = translate(engine, input); translations.put(item);
                require(hasChinese(item.getString("zh")), "No Chinese output: " + item); timings.add(item.getLong("ms"));
            }
            Collections.sort(timings);
            report.put("translationMedianMs", timings.get(timings.size() / 2)).put("translationMaxMs", timings.get(timings.size() - 1));
            JSONObject repeated = translate(engine, gold[0][0]); require(first.equals(repeated.getString("zh")), "Cached repeat changed output");
            report.put("cachedRepeat", repeated.put("sameOutput", true).put("scope", "Same-output and warm-repeat timing; no timing threshold"));
            require(engine.translateBlocking(" \t\n").isEmpty(), "Blank input produced subtitles"); report.put("blankInputSuppressed", true);
            report.put("queueCancellation", TranslationVerification.cancellationCheck(test, engine));
            require(preparedSpeech == engine.selectedModel() && preparedRecognizer == engine.npuRecognizer(), "Translations replaced speech recognizer");
            JSONArray routes = new JSONArray(); report.put("languageAvailabilityContracts", routes);
            languageContracts(test, engine, routes);
            report.put("realDeviceScope", "Production OPUS and three short-phrase gold regressions; other script checks are not an accuracy score. SDK absence is checked in this app, not by claiming the phone has no GMS.");
        } catch (Throwable error) { failure = error; }
        finally {
            if (engine != null && originalSource != null && originalTarget != null && originalModel != null) {
                try {
                    awaitIdle(engine);
                    final LocalEngine selectedEngine = engine; final String source = originalSource, target = originalTarget; final NpuModel model = originalModel;
                    test.runOnMainSync(() -> selectedEngine.setLanguages(source, target)); awaitSetup(test);
                    if (selectedEngine.isModelCompatible(model) && ModelStore.installed(context, model)) {
                        test.runOnMainSync(() -> selectedEngine.selectModel(model)); awaitSetup(test);
                    }
                    require(source.equals(engine.sourceLanguage()) && target.equals(engine.targetLanguage()), "Original pair not restored");
                    require(engine.selectedModel() == model, "Original speech model not restored");
                    report.put("restoredSourceLanguage", engine.sourceLanguage()).put("restoredTargetLanguage", engine.targetLanguage())
                        .put("restoredSpeechModel", engine.selectedModel().id).put("restoredReady", CaptionState.S.ready);
                } catch (Throwable restoreError) { if (failure == null) failure = restoreError; else failure.addSuppressed(restoreError); }
                if (originalPreferences != null) {
                    SharedPreferences preferences = context.getSharedPreferences("engine", 0); SharedPreferences.Editor editor = preferences.edit();
                    for (String key : preferences.getAll().keySet()) if (configurationPreference(key)) editor.remove(key);
                    for (Map.Entry<String, ?> saved : originalPreferences.entrySet())
                        if (configurationPreference(saved.getKey()) && saved.getValue() instanceof String) editor.putString(saved.getKey(), (String)saved.getValue());
                    editor.commit();
                }
            }
        }
        try { report.put("passed", failure == null); if (failure != null) report.put("error", android.util.Log.getStackTraceString(failure)); write(context, report); }
        catch (Exception saveError) { if (failure == null) failure = saveError; }
        result.putString("stream", report.toString()); test.finish(failure == null ? 0 : 1, result);
    }

    private static void languageContracts(Instrumentation test, LocalEngine engine, JSONArray results) throws Exception {
        Context context = test.getTargetContext();
        for (String[] pair : new String[][]{{"en", "ja"}, {"yue", "en"}}) {
            awaitIdle(engine); NpuModel previous = engine.selectedModel();
            // Make the per-pair saved choice explicit so this checks fallback rather than preference lookup.
            context.getSharedPreferences("engine", 0).edit().putString("modelFor:" + pair[0] + ":" + pair[1], previous.id).commit();
            test.runOnMainSync(() -> engine.setLanguages(pair[0], pair[1])); awaitSetup(test);
            require(pair[0].equals(engine.sourceLanguage()) && pair[1].equals(engine.targetLanguage()), "Unsupported pair was silently reset");
            require(!engine.translationAvailable() && !CaptionState.S.ready, "Unsupported pair was incorrectly ready");
            require(engine.selectedModel() == previous, "Unsupported pair silently changed speech model");
            boolean rejected = false;
            try { engine.translateBlocking("This must not be returned as untranslated text."); } catch (IllegalStateException expected) { rejected = true; }
            require(rejected, "Unsupported pair returned untranslated or wrong-language text");
            results.put(new JSONObject().put("source", pair[0]).put("target", pair[1]).put("available", false)
                .put("speechModelPreserved", true).put("status", CaptionState.S.status).put("passed", true));
        }
        awaitIdle(engine); test.runOnMainSync(() -> engine.setLanguages("ja", "ja")); awaitReady(test);
        require(engine.translationAvailable() && "passthrough".equals(engine.translationBackend()), "Japanese same-language captioning unavailable");
        String japanese = "これは日本語の字幕です。"; require(japanese.equals(engine.translateBlocking(japanese)), "Same-language text changed");
        results.put(new JSONObject().put("source", "ja").put("target", "ja").put("backend", engine.translationBackend()).put("output", japanese).put("passed", true));
    }

    private static void policyChecks(JSONArray cases) throws Exception {
        check(cases, "Installed model warms once and prepare reuses it", new FakeFactory(true), (router, fake) -> {
            router.prepare(); router.prepare(); requireBackend(router, "opus");
            require(fake.created == 1 && fake.latest().warmups == 1, "Ready model was needlessly loaded or warmed");
        });
        check(cases, "Missing model errors without creating backend", new FakeFactory(false), (router, fake) -> {
            expectMissing(router); require(!router.ready() && "none".equals(router.backend()) && fake.created == 0, "Missing model created backend");
        });
        check(cases, "Removed model unloads active backend", new FakeFactory(true), (router, fake) -> {
            router.prepare(); FakeBackend old = fake.latest(); fake.installed = false; expectMissing(router);
            require(!router.ready() && old.closes == 1, "Removed model left ready or leaked backend");
        });
        FakeFactory createFailure = new FakeFactory(true); createFailure.failCreate = true;
        check(cases, "Load failure leaves no backend and retry recovers", createFailure, (router, fake) -> {
            expectFailure(router); require(!router.ready(), "Failed creation left router ready");
            fake.failCreate = false; router.prepare(); requireBackend(router, "opus");
        });
        FakeFactory warmFailure = new FakeFactory(true); warmFailure.failWarmup = true;
        check(cases, "Failed warmup closes candidate and retry recovers", warmFailure, (router, fake) -> {
            expectFailure(router); require(!router.ready() && fake.latest().closes == 1, "Failed warmup leaked backend");
            fake.failWarmup = false; router.prepare(); requireBackend(router, "opus");
        });
        check(cases, "Unload closes once and allows preparation", new FakeFactory(true), (router, fake) -> {
            router.prepare(); FakeBackend old = fake.latest(); router.unloadOpus(); router.close();
            require(!router.ready() && old.closes == 1, "Unload leaked or double-closed OPUS");
            router.prepare(); requireBackend(router, "opus"); require(fake.created == 2, "Unloaded model not recreated");
        });
        FakeFactory pass = new FakeFactory(false); pass.passthrough = true;
        check(cases, "Same-language captions bypass translation models", pass, (router, fake) -> {
            router.prepare(); requireBackend(router, "passthrough");
            require("Original 你好".equals(router.translate("Original 你好", () -> true)), "Passthrough changed text");
            require(fake.checks == 0 && fake.created == 0, "Passthrough accessed model files");
            expectCancelled(router, () -> false); router.unloadOpus(); requireBackend(router, "passthrough");
        });
        FakeFactory bridge = new FakeFactory(true); bridge.name = "speech-english"; bridge.identity = true;
        check(cases, "English bridge preserves translated speech", bridge, (router, fake) -> {
            router.prepare(); requireBackend(router, "speech-english");
            require("Local English speech".equals(router.translate("Local English speech", () -> true)), "English bridge changed text");
            router.unloadOpus(); requireBackend(router, "speech-english");
        });
        check(cases, "Pre-cancelled request never enters backend", new FakeFactory(true), (router, fake) -> {
            router.prepare(); int before = fake.latest().calls; expectCancelled(router, () -> false);
            require(fake.latest().calls == before, "Cancelled request entered backend");
        });
        check(cases, "Cancellation predicate preserved and next request recovers", new FakeFactory(true), (router, fake) -> {
            router.prepare(); AtomicBoolean current = new AtomicBoolean(true); BooleanSupplier predicate = current::get;
            fake.latest().during = () -> current.set(false); expectCancelled(router, predicate);
            require(fake.latest().lastPredicate == predicate, "Cancellation predicate replaced");
            fake.latest().during = null; current.set(true);
            require(router.translate("Next request", predicate).contains("测试"), "Request failed after cancellation");
        });
        check(cases, "Router suppresses late-cancelled result", new FakeFactory(true), (router, fake) -> {
            router.prepare(); AtomicBoolean current = new AtomicBoolean(true);
            fake.latest().during = () -> current.set(false); fake.latest().honorPredicate = false;
            expectCancelled(router, current::get); require(router.ready(), "Cancellation destroyed backend");
        });
        check(cases, "Closed router rejects work and close is idempotent", new FakeFactory(true), (router, fake) -> {
            router.prepare(); FakeBackend old = fake.latest(); router.close(); router.close(); boolean rejected = false;
            try { router.translate("After close", () -> true); } catch (IllegalStateException expected) { rejected = true; }
            require(rejected && old.closes == 1 && "none".equals(router.backend()), "Closed router accepted work or double-closed backend");
        });
    }
    private interface CaseCheck { void run(TranslationRouter router, FakeFactory factory) throws Exception; }
    private static void check(JSONArray cases, String name, FakeFactory factory, CaseCheck action) throws Exception {
        JSONObject item = new JSONObject().put("case", name); cases.put(item);
        try (TranslationRouter router = new TranslationRouter(factory)) {
            try { action.run(router, factory); item.put("passed", true).put("backend", router.backend()).put("modelChecks", factory.checks).put("clientsCreated", factory.created); }
            catch (Throwable error) { item.put("passed", false).put("error", error.toString()); throw error; }
        }
        for (FakeBackend backend : factory.backends) require(backend.closes == 1, "Policy test leaked or double-closed backend: " + name);
    }
    private static final class FakeFactory implements TranslationRouter.Factory {
        boolean installed, passthrough, failCreate, failWarmup, identity; String name = "opus"; int checks, created;
        final ArrayList<FakeBackend> backends = new ArrayList<>();
        FakeFactory(boolean installed) { this.installed = installed; }
        @Override public boolean passthrough() { return passthrough; }
        @Override public String fallbackName() { return name; }
        @Override public boolean opusInstalled() { checks++; return installed; }
        @Override public TranslationRouter.Backend opus() throws Exception {
            require(installed, "Backend requested without model"); created++;
            if (failCreate) throw new IOException("Injected local load failure");
            FakeBackend backend = new FakeBackend(failWarmup, identity); backends.add(backend); return backend;
        }
        FakeBackend latest() { return backends.get(backends.size() - 1); }
    }
    private static final class FakeBackend implements TranslationRouter.Backend {
        final boolean failWarmup, identity; boolean honorPredicate = true; int warmups, closes, calls;
        Runnable during; BooleanSupplier lastPredicate;
        FakeBackend(boolean failWarmup, boolean identity) { this.failWarmup = failWarmup; this.identity = identity; }
        @Override public String translate(String text, BooleanSupplier current) throws Exception {
            require(closes == 0, "Closed fake backend used"); calls++; lastPredicate = current;
            if ("Ready".equals(text)) { warmups++; if (failWarmup) throw new IOException("Injected warmup failure"); }
            if (during != null) during.run(); if (honorPredicate && !current.getAsBoolean()) throw new CancellationException();
            return identity ? text : "测试翻译：" + text;
        }
        @Override public void close() { closes++; }
    }
    private static void expectMissing(TranslationRouter router) throws Exception {
        boolean missing = false; try { router.prepare(); } catch (TranslationRouter.ModelMissingException expected) { missing = true; }
        require(missing, "Missing model did not raise ModelMissingException");
    }
    private static void expectFailure(TranslationRouter router) throws Exception {
        boolean failed = false; try { router.prepare(); } catch (IOException expected) { failed = true; }
        require(failed, "Injected model failure ignored");
    }
    private static void expectCancelled(TranslationRouter router, BooleanSupplier current) throws Exception {
        boolean cancelled = false; try { router.translate("Cancel this request", current); } catch (CancellationException expected) { cancelled = true; }
        require(cancelled, "Cancelled request returned translation");
    }
    private static void requireBackend(TranslationRouter router, String backend) { require(router.ready() && backend.equals(router.backend()), "Expected ready " + backend + ", got " + router.backend()); }
    private static JSONObject translate(LocalEngine engine, String text) throws Exception {
        long started = SystemClock.elapsedRealtime(); String output = engine.translateBlocking(text);
        return new JSONObject().put("en", text).put("zh", output).put("ms", SystemClock.elapsedRealtime() - started);
    }
    private static NpuModel requestedSpeech(String id) {
        if (id.isEmpty()) return null; for (NpuModel model : NpuModel.values()) if (id.equals(model.id)) return model;
        throw new IllegalArgumentException("Unknown speechModel: " + id);
    }
    private static void awaitSetup(Instrumentation test) throws Exception {
        long until = SystemClock.elapsedRealtime() + 300000; while (CaptionState.S.preparing && SystemClock.elapsedRealtime() < until) Thread.sleep(25);
        test.runOnMainSync(() -> {}); require(!CaptionState.S.preparing, "Preparation timed out");
    }
    private static void awaitReady(Instrumentation test) throws Exception { awaitSetup(test); require(CaptionState.S.ready, "Engine not ready: " + CaptionState.S.status); }
    private static void awaitIdle(LocalEngine engine) throws Exception {
        long until = SystemClock.elapsedRealtime() + 300000; while (engine.configurationBusy() && SystemClock.elapsedRealtime() < until) Thread.sleep(25);
        require(!engine.configurationBusy(), "Engine remained busy");
    }
    private static boolean configurationPreference(String key) { return key.equals("speechSource") || key.equals("speechTarget") || key.equals("npuModel") || key.startsWith("modelFor:"); }
    private static String normalized(String text) { return java.text.Normalizer.normalize(text, java.text.Normalizer.Form.NFKC).replaceAll("[\\p{P}\\p{Z}\\s]", ""); }
    private static boolean hasChinese(String text) { return text != null && text.matches("(?s).*[\\p{IsHan}].*"); }
    private static boolean enabled(Bundle options, String key) { String value = options.getString(key, "false"); return "true".equalsIgnoreCase(value) || "1".equals(value); }
    private static void write(Context context, JSONObject report) throws Exception {
        try (FileOutputStream output = new FileOutputStream(new File(context.getExternalFilesDir(null), "adaptive-translation-verification.json"))) { output.write(report.toString(2).getBytes(StandardCharsets.UTF_8)); }
    }
    private static void require(boolean condition, String message) { if (!condition) throw new AssertionError(message); }
}
