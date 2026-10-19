package com.localcaption.instant;

import android.app.Instrumentation;
import android.content.Context;
import android.os.Bundle;
import android.os.SystemClock;
import com.k2fsa.sherpa.onnx.OfflineModelConfig;
import com.k2fsa.sherpa.onnx.OfflineRecognizer;
import com.k2fsa.sherpa.onnx.OfflineRecognizerConfig;
import com.k2fsa.sherpa.onnx.OfflineRecognizerResult;
import com.k2fsa.sherpa.onnx.OfflineStream;
import com.k2fsa.sherpa.onnx.OfflineWhisperModelConfig;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/** Diagnostic observations, never a translation-quality pass or an invented reference translation. */
final class WhisperDiagnostics {
    private static final String SOURCE_BASE = "https://raw.githubusercontent.com/k2-fsa/sherpa-onnx/v1.13.8/";
    static void run(Instrumentation test, Bundle options) {
        Context context = test.getTargetContext();
        JSONObject report = new JSONObject();
        Bundle result = new Bundle();
        int exit = 0;
        try {
            require(!CaptionState.S.running && !ModelStore.active, "Stop capture and downloads before native diagnostics");
            String asset = options.getString("asset", "multilingual/ja.wav");
            String language = options.getString("language", "ja");
            short[] audio = NpuBenchmark.wave(test, asset);
            report.put("device", android.os.Build.MODEL).put("asset", asset).put("sourceLanguage", language)
                .put("samples", audio.length).put("audioSeconds", audio.length / 16000.0)
                .put("internetPermission", context.checkSelfPermission(android.Manifest.permission.INTERNET)
                    == android.content.pm.PackageManager.PERMISSION_GRANTED)
                .put("scope", "Same audio/model comparison of production windowing, direct whole-utterance decode and documented tail-padding settings. Outputs require semantic review; no gold transcript or translation is assumed.")
                .put("translationQualityAssessed", false);
            try (InputStream input = test.getContext().getAssets().open(asset)) {
                report.put("assetSha256", hex(MessageDigest.getInstance("SHA-256").digest(input.readAllBytes())));
            }
            report.put("apiEvidence", new JSONArray()
                .put(SOURCE_BASE + "sherpa-onnx/kotlin-api/OfflineRecognizer.kt")
                .put(SOURCE_BASE + "sherpa-onnx/csrc/offline-whisper-model-config.cc")
                .put(SOURCE_BASE + "sherpa-onnx/csrc/offline-recognizer-whisper-impl.h"));
            report.put("paddingSemantics", "tailPaddings is feature frames. Kotlin default is 1000; C++ only replaces the default when value > 0, so 0 still means 1000, not no padding. Config documentation suggests 300 for multilingual; implementation comments also suggest trying 100.");
            JSONArray models = new JSONArray();
            report.put("models", models);
            for (String name : options.getString("models", "tiny,base").split(",")) {
                NpuModel model = "tiny".equals(name) || "whisper-tiny".equals(name) ? NpuModel.WHISPER_TINY
                    : "base".equals(name) || "whisper-base".equals(name) ? NpuModel.WHISPER_BASE : null;
                require(model != null && ModelStore.installed(context, model), "Whisper model missing or unknown: " + name);
                JSONObject modelResult = new JSONObject().put("model", model.id);
                JSONArray cases = new JSONArray();
                modelResult.put("cases", cases);
                models.put(modelResult);
                observe(test, context, report, cases, "production_facade", () -> production(context, model, language, audio));
                observe(test, context, report, cases, "direct_split6_translate_tail1000",
                    () -> direct(context, model, language, "translate", 1000, 16000 * 6, audio));
                observe(test, context, report, cases, "direct_full_translate_tail1000",
                    () -> direct(context, model, language, "translate", 1000, audio.length, audio));
                observe(test, context, report, cases, "direct_full_translate_tail0",
                    () -> direct(context, model, language, "translate", 0, audio.length, audio));
                observe(test, context, report, cases, "direct_full_translate_tail300",
                    () -> direct(context, model, language, "translate", 300, audio.length, audio));
                observe(test, context, report, cases, "direct_full_translate_tail100",
                    () -> direct(context, model, language, "translate", 100, audio.length, audio));
                observe(test, context, report, cases, "direct_full_transcribe_tail300",
                    () -> direct(context, model, language, "transcribe", 300, audio.length, audio));
                modelResult.put("qualityStatus", "Requires comparison with source audio; English-looking output is not evidence of correct translation");
            }
            report.put("completed", true);
            write(context, report);
        } catch (Throwable error) {
            exit = 1;
            try { report.put("completed", false).put("error", android.util.Log.getStackTraceString(error)); write(context, report); }
            catch (Exception ignored) {}
        }
        result.putString("stream", report.toString());
        test.finish(exit, result);
    }

    private interface Observation { JSONObject run() throws Exception; }
    private static void observe(Instrumentation test, Context context, JSONObject report, JSONArray cases,
                                String name, Observation action) throws Exception {
        JSONObject item = new JSONObject().put("case", name);
        cases.put(item);
        try {
            JSONObject observation = action.run();
            for (java.util.Iterator<String> keys = observation.keys(); keys.hasNext();) {
                String key = keys.next(); item.put(key, observation.get(key));
            }
            item.put("executed", true);
        } catch (Throwable error) {
            item.put("executed", false).put("error", android.util.Log.getStackTraceString(error));
        }
        write(context, report);
        NpuBenchmark.progress(test, item);
    }

    private static JSONObject production(Context context, NpuModel model, String language, short[] audio) throws Exception {
        long started = SystemClock.elapsedRealtime();
        try (NpuRecognizer recognizer = new NpuRecognizer(context, model, language, true)) {
            long loaded = SystemClock.elapsedRealtime();
            String text = recognizer.recognize(audio);
            return new JSONObject().put("model", model.id).put("task", "translate")
                .put("windowSeconds", model.segmentSamples / 16000.0)
                .put("note", "Calls current production recognize(): fixed windows and intermediate prefix decodes")
                .put("loadMs", loaded - started).put("decodeMs", SystemClock.elapsedRealtime() - loaded)
                .put("text", text).put("repetition", repetition(text)).put("translationQualityAssessed", false);
        }
    }

    private static JSONObject direct(Context context, NpuModel model, String language, String task,
                                     int padding, int windowSamples, short[] audio) throws Exception {
        File root = ModelStore.root(context, model);
        String size = model == NpuModel.WHISPER_TINY ? "tiny" : "base";
        OfflineWhisperModelConfig whisper = new OfflineWhisperModelConfig();
        whisper.setEncoder(new File(root, size + "-encoder.int8.onnx").getAbsolutePath());
        whisper.setDecoder(new File(root, size + "-decoder.int8.onnx").getAbsolutePath());
        whisper.setLanguage(language);
        whisper.setTask(task);
        whisper.setTailPaddings(padding);
        OfflineModelConfig modelConfig = new OfflineModelConfig();
        modelConfig.setWhisper(whisper);
        modelConfig.setTokens(new File(root, size + "-tokens.txt").getAbsolutePath());
        modelConfig.setModelType("whisper");
        modelConfig.setProvider("cpu");
        modelConfig.setNumThreads(Math.max(1, Math.min(3, Runtime.getRuntime().availableProcessors())));
        modelConfig.setDebug(false);
        OfflineRecognizerConfig config = new OfflineRecognizerConfig();
        config.setModelConfig(modelConfig);
        long started = SystemClock.elapsedRealtime();
        OfflineRecognizer recognizer = new OfflineRecognizer(null, config);
        long loaded = SystemClock.elapsedRealtime();
        JSONArray segments = new JSONArray();
        StringBuilder combined = new StringBuilder();
        try {
            for (int position = 0; position < audio.length; position += windowSamples) {
                int length = Math.min(windowSamples, audio.length - position);
                float[] pcm = new float[length];
                double power = 0;
                for (int i = 0; i < length; i++) { pcm[i] = audio[position + i] / 32768.0f; power += pcm[i] * pcm[i]; }
                OfflineStream stream = recognizer.createStream();
                try {
                    long beginDecode = SystemClock.elapsedRealtime();
                    stream.acceptWaveform(pcm, 16000);
                    recognizer.decode(stream);
                    OfflineRecognizerResult decoded = recognizer.getResult(stream);
                    String text = decoded.getText().trim();
                    if (combined.length() > 0) combined.append(' ');
                    combined.append(text);
                    segments.put(new JSONObject().put("startSeconds", position / 16000.0)
                        .put("audioSeconds", length / 16000.0).put("rms", Math.sqrt(power / length))
                        .put("decodeMs", SystemClock.elapsedRealtime() - beginDecode).put("text", text)
                        .put("languageReported", decoded.getLang()).put("tokenCount", decoded.getTokens().length)
                        .put("tokens", new JSONArray(Arrays.asList(decoded.getTokens())))
                        .put("repetition", repetition(text)));
                } finally { stream.release(); }
            }
        } finally { recognizer.release(); }
        return new JSONObject().put("model", model.id).put("task", task).put("language", language)
            .put("tailPaddingsRequested", padding).put("tailPaddingFramesEffective", padding > 0 ? padding : 1000)
            .put("windowSeconds", windowSamples / 16000.0).put("intermediatePrefixDecodes", false)
            .put("loadMs", loaded - started).put("decodeMs", SystemClock.elapsedRealtime() - loaded)
            .put("segments", segments).put("text", combined.toString()).put("repetition", repetition(combined.toString()))
            .put("translationQualityAssessed", false);
    }

    /** A review flag, not an accuracy metric; naturally repeated words can also trigger it. */
    static JSONObject repetition(String value) throws Exception {
        String normalized = value.toLowerCase(Locale.ROOT).replaceAll("[^\\p{L}\\p{N}']+", " ").trim();
        String[] words = normalized.isEmpty() ? new String[0] : normalized.split("\\s+");
        int maximum = 0;
        String mostRepeated = "";
        for (int width = 3; width <= 6; width++) {
            Map<String, Integer> counts = new HashMap<>();
            for (int at = 0; at + width <= words.length; at++) {
                String phrase = String.join(" ", Arrays.copyOfRange(words, at, at + width));
                int count = counts.getOrDefault(phrase, 0) + 1;
                counts.put(phrase, count);
                if (count > maximum) { maximum = count; mostRepeated = phrase; }
            }
        }
        return new JSONObject().put("wordCount", words.length).put("maxRepeatedNgramCount", maximum)
            .put("repeatedNgram", mostRepeated).put("suspicious", maximum >= 3)
            .put("meaning", "Heuristic repetition warning only; does not establish translation correctness");
    }
    private static String hex(byte[] bytes) {
        StringBuilder result = new StringBuilder();
        for (byte value : bytes) result.append(String.format(Locale.ROOT, "%02x", value & 255));
        return result.toString();
    }
    private static void write(Context context, JSONObject report) throws Exception {
        try (FileOutputStream output = new FileOutputStream(new File(context.getExternalFilesDir(null), "whisper-diagnostics.json"))) {
            output.write(report.toString(2).getBytes(StandardCharsets.UTF_8));
        }
    }
    private static void require(boolean condition, String message) { if (!condition) throw new AssertionError(message); }
}
