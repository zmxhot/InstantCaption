package com.localcaption.instant;

import android.app.Instrumentation;
import android.content.Context;
import android.content.pm.ComponentInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.os.SystemClock;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;

/** Exercises the installed production translator without changing phone services or settings. */
final class TranslationVerification {
    private static final String[] INPUTS = {
        "Hello everyone, welcome to our meeting.",
        "Please speak slowly.",
        "We will discuss the new project today.",
        "Thank you for your help.",
        "Where is the nearest train station?",
        "The meeting starts at nine o'clock tomorrow morning.",
        "Can you help me find the hotel?",
        "I would like a glass of water without ice.",
        "The train has been delayed by twenty minutes.",
        "I don't agree with this decision, but I understand your reasons.",
        "Please turn left at the next traffic light.",
        "This application translates speech directly on your phone.",
        "We need to finish the report before Friday.",
        "Although it was raining, they continued walking to the station.",
        "Could you explain the difference between these two models?"
    };

    static void run(Instrumentation test, Bundle options) {
        Context context = test.getTargetContext();
        JSONObject report = new JSONObject();
        Bundle result = new Bundle();
        try {
            require(!CaptionState.S.running, "Stop audio capture before running translation verification");
            report.put("package", context.getPackageName());
            report.put("androidSdk", android.os.Build.VERSION.SDK_INT);
            report.put("device", android.os.Build.MODEL);
            boolean internetPermission = context.checkSelfPermission(android.Manifest.permission.INTERNET)
                == PackageManager.PERMISSION_GRANTED;
            report.put("internetPermission", internetPermission);
            String offline = options.getString("offline", "false");
            if ("true".equalsIgnoreCase(offline) || "1".equals(offline))
                require(!internetPermission, "Offline verification build still has INTERNET permission");
            report.put("method", "Production LocalEngine and MarianTranslator; output presence checks are not a translation accuracy score");
            report.put("googleDependencyCheck", googleDependencyCheck(context));
            boolean initiallyInstalled = TranslationModel.installed(context);
            report.put("translationModelInitiallyInstalled", initiallyInstalled);
            if (!initiallyInstalled && "1".equals(options.getString("download", "0"))) {
                int source = Integer.parseInt(options.getString("source", "2"));
                require(source >= 0 && source <= 2, "Invalid download source");
                require(ModelStore.reserveTranslation(context,"download"), "Translation download reservation rejected");
                long started = SystemClock.elapsedRealtime();
                ModelStore.runTranslation(context, false, source);
                JSONObject download = new JSONObject().put("attempted", true)
                    .put("sourcePreference", source).put("lastChannel", ModelStore.channel)
                    .put("elapsedMs", SystemClock.elapsedRealtime() - started)
                    .put("phase", ModelStore.phase).put("error", ModelStore.error);
                report.put("download", download);
                require("done".equals(ModelStore.phase), "Translation download failed: " + ModelStore.error);
            } else {
                report.put("download", new JSONObject().put("attempted", false)
                    .put("reason", initiallyInstalled ? "Model already installed" : "Download option not requested"));
            }
            require(TranslationModel.installed(context), "Local translation model is missing");
            report.put("modelFiles", fileInventory(TranslationModel.root(context)));

            LocalEngine engine = LocalEngine.get(context);
            boolean alreadyReady = CaptionState.S.ready;
            report.put("engineAlreadyReadyBeforePrepare", alreadyReady);
            long started = SystemClock.elapsedRealtime();
            engine.prepare();
            long deadline = started + 300000;
            while (!CaptionState.S.ready && SystemClock.elapsedRealtime() < deadline) {
                test.runOnMainSync(() -> {});
                if (!CaptionState.S.preparing && !CaptionState.S.ready)
                    throw new IllegalStateException(CaptionState.S.status);
                Thread.sleep(50);
            }
            require(CaptionState.S.ready, "Engine preparation timed out: " + CaptionState.S.status);
            report.put("enginePreparationMs", SystemClock.elapsedRealtime() - started);
            report.put("preparationTimingScope", "VAD and ASR initialization plus translation load/Ready warmup; not isolated cold translation latency");
            report.put("speechModel", engine.selectedModel().id);

            JSONArray translations = new JSONArray();
            ArrayList<Long> timings = new ArrayList<>();
            String firstOutput = null;
            for (String input : INPUTS) {
                JSONObject phrase = translate(engine, input);
                require(hasChinese(phrase.getString("zh")), "No Chinese output: " + phrase);
                translations.put(phrase);
                timings.add(phrase.getLong("ms"));
                if (firstOutput == null) firstOutput = phrase.getString("zh");
            }
            report.put("translations", translations);
            report.put("firstPhraseMs", timings.get(0));
            Collections.sort(timings);
            report.put("translationMedianMs", timings.get(timings.size() / 2));
            report.put("translationMaxMs", timings.get(timings.size() - 1));
            JSONObject repeated = translate(engine, INPUTS[0]);
            require(firstOutput.equals(repeated.getString("zh")), "Repeated input changed its translation");
            report.put("repeatedInput", repeated.put("sameOutput", true));

            JSONArray regressions = new JSONArray();
            String[][] regressionCases = {
                {"Hello everyone.", "大家好", "各位好", "你们好", "各位大家好", "大家好啊"},
                {"I don't understand.", "我不明白", "我不懂", "我不理解", "我不明白了", "我听不懂"},
                {"I would like a glass of water.", "我想要一杯水", "我要一杯水", "我想喝一杯水", "我想来一杯水", "我需要一杯水", "我想要杯水", "我想喝杯水"}
            };
            for (String[] regression : regressionCases) {
                JSONObject phrase = translate(engine, regression[0]);
                String normalized = phrase.getString("zh").replaceAll("[\\p{P}\\p{Z}\\s]", "");
                boolean accepted = false;
                for (int i = 1; i < regression.length; i++) accepted |= regression[i].equals(normalized);
                phrase.put("acceptedExpectedVariant", accepted);
                regressions.put(phrase);
                report.put("shortPhraseRegressions", regressions);
                require(accepted, "Short-phrase regression has extra, repeated, or unexpected text: " + phrase);
            }

            JSONArray edges = new JSONArray();
            for (String input : new String[]{"", "   \t\n"}) {
                JSONObject edge = translate(engine, input);
                require(edge.getString("zh").trim().isEmpty(), "Blank input produced subtitles");
                edges.put(edge);
            }
            JSONObject unicode = translate(engine, "Let's meet at the café near the station — tomorrow at 10:30. 😊");
            require(hasChinese(unicode.getString("zh")), "Unicode English input produced no Chinese");
            edges.put(unicode);
            String longInput = "Before we begin today's meeting, please check that everyone can hear the speaker clearly. "
                + "We will first review last week's progress, then discuss the problems reported by our customers. "
                + "The engineering team has already fixed several issues, but some important questions remain unanswered. "
                + "After the presentation, each team will have an opportunity to explain its plan and ask for help. "
                + "Please write down the decisions and send a short report to everyone before the end of the day. "
                + "If we cannot finish all the topics today, we can arrange another meeting tomorrow afternoon.";
            JSONObject longPhrase = translate(engine, longInput);
            require(hasChinese(longPhrase.getString("zh")), "Long input produced no Chinese");
            edges.put(longPhrase.put("case", "long multi-sentence input; output completeness needs review"));
            report.put("edgeCases", edges);
            report.put("queueCancellation", cancellationCheck(test, engine));
            report.put("passed", true);
            write(context, report);
            result.putString("stream", report.toString(2));
            test.finish(0, result);
        } catch (Throwable error) {
            try {
                report.put("passed", false).put("error", android.util.Log.getStackTraceString(error));
                write(context, report);
            } catch (Exception ignored) {}
            result.putString("stream", report.toString());
            test.finish(1, result);
        }
    }

    private static JSONObject translate(LocalEngine engine, String input) throws Exception {
        long started = SystemClock.elapsedRealtime();
        String output = engine.translateBlocking(input);
        return new JSONObject().put("en", input).put("zh", output)
            .put("ms", SystemClock.elapsedRealtime() - started);
    }

    static JSONObject googleDependencyCheck(Context context) throws Exception {
        JSONArray checkedClasses = new JSONArray();
        String[] names = {
            "com.google.mlkit.nl.translate.Translator",
            "com.google.mlkit.common.MlKit",
            "com.google.android.gms.tasks.Tasks",
            "com.google.android.gms.common.GoogleApiAvailability",
            "com.google.firebase.components.ComponentDiscoveryService",
            "com.google.android.datatransport.runtime.TransportRuntime"
        };
        for (String name : names) {
            boolean present;
            try { Class.forName(name, false, context.getClassLoader()); present = true; }
            catch (ClassNotFoundException absent) { present = false; }
            checkedClasses.put(new JSONObject().put("class", name).put("present", present));
            require(!present, "Google runtime dependency remains: " + name);
        }
        PackageInfo info = context.getPackageManager().getPackageInfo(context.getPackageName(),
            PackageManager.GET_ACTIVITIES | PackageManager.GET_SERVICES |
            PackageManager.GET_PROVIDERS | PackageManager.GET_RECEIVERS | PackageManager.GET_META_DATA);
        JSONArray components = new JSONArray();
        collectComponents(info.activities, components);
        collectComponents(info.services, components);
        collectComponents(info.providers, components);
        collectComponents(info.receivers, components);
        boolean gmsVersion = info.applicationInfo != null && info.applicationInfo.metaData != null
            && info.applicationInfo.metaData.containsKey("com.google.android.gms.version");
        require(!gmsVersion, "GMS manifest version metadata remains");
        return new JSONObject().put("classes", checkedClasses).put("manifestComponents", components)
            .put("gmsVersionMetadata", false)
            .put("scope", "App class loader and installed app manifest only; this does not constitute testing a phone without GMS");
    }

    private static void collectComponents(ComponentInfo[] list, JSONArray result) throws Exception {
        if (list == null) return;
        for (ComponentInfo component : list) {
            result.put(component.name);
            require(!component.name.startsWith("com.google.mlkit.")
                && !component.name.startsWith("com.google.android.gms.")
                && !component.name.startsWith("com.google.firebase.")
                && !component.name.startsWith("com.google.android.datatransport."),
                "Google component remains in installed manifest: " + component.name);
        }
    }

    static JSONObject cancellationCheck(Instrumentation test, LocalEngine engine) throws Exception {
        require(engine.translationsIdle(), "Translation queue unexpectedly busy");
        long id = CaptionState.S.nextId();
        // Keep this monitor until cancellation so the worker cannot consume the queued request.
        CaptionState.S.english(id, "This cancelled request must not become a subtitle.", false, 1);
        test.runOnMainSync(() -> {});
        synchronized (engine) {
            engine.translate(id, "This cancelled request must not become a subtitle.", 1);
            engine.cancelPending();
        }
        awaitIdle(test, engine);
        final String[] cancelledOutput = {null};
        test.runOnMainSync(() -> {
            for (CaptionState.Line line : CaptionState.S.lines)
                if (line.id == id) cancelledOutput[0] = line.zh;
        });
        require("".equals(cancelledOutput[0]), "Cancelled pending request produced subtitles");
        String replacement = "The new translation request works after cancellation.";
        CaptionState.S.english(id, replacement, true, 2);
        test.runOnMainSync(() -> {});
        engine.translate(id, replacement, 2);
        awaitIdle(test, engine);
        final String[] newOutput = {null};
        test.runOnMainSync(() -> {
            for (CaptionState.Line line : CaptionState.S.lines)
                if (line.id == id) newOutput[0] = line.zh;
            CaptionState.S.lines.removeIf(line -> line.id == id);
            CaptionState.S.changed();
        });
        require(hasChinese(newOutput[0]), "New request did not recover after cancellation");
        return new JSONObject().put("pendingRequestCancelled", true)
            .put("nextGenerationSucceeded", true).put("nextGenerationChinese", newOutput[0])
            .put("scope", "Deterministic cancellation before worker consumption; not an in-flight native inference interruption test");
    }

    private static void awaitIdle(Instrumentation test, LocalEngine engine) throws Exception {
        long until = SystemClock.elapsedRealtime() + 120000;
        while (!engine.translationsIdle() && SystemClock.elapsedRealtime() < until) Thread.sleep(20);
        require(engine.translationsIdle(), "Translation queue did not become idle");
        test.runOnMainSync(() -> {});
    }

    private static JSONArray fileInventory(File directory) throws Exception {
        JSONArray result = new JSONArray();
        File[] files = directory.listFiles();
        require(files != null && files.length > 0, "No translation model files");
        java.util.Arrays.sort(files, (a, b) -> a.getName().compareTo(b.getName()));
        for (File file : files) if (file.isFile())
            result.put(new JSONObject().put("name", file.getName()).put("bytes", file.length()));
        return result;
    }

    private static boolean hasChinese(String value) {
        return value != null && value.matches("(?s).*[\\p{IsHan}].*");
    }
    private static void write(Context context, JSONObject report) throws Exception {
        try (FileOutputStream stream = new FileOutputStream(new File(context.getExternalFilesDir(null), "translation-verification.json"))) {
            stream.write(report.toString(2).getBytes(StandardCharsets.UTF_8));
        }
    }
    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
