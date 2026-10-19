# Verification and release gates

Build checks and physical-device results are separate. Hardware eligibility is not evidence of native runtime compatibility, and a nonempty translation is not evidence of semantic accuracy.

## Automated checks

- Build both portable and NPU APKs from the clean source export after running the pinned dependency bootstrap.
- Android lint: resolve errors; review warnings instead of creating a blanket baseline.
- Validate localization placeholders and all ten UI resource sets. Check RTL and 200% font size on the device.
- `tools/check_apk.py`: ARM64 ELF 16 KB load alignment, expected licenses and absence of Qualcomm native libraries from portable builds.
- `apksigner verify` and `zipalign -c -P 16 -v 4`; confirm public APK is non-debuggable and certificate is the intended release identity.
- Instrumentation `readiness`: model/language route matrix, bounded queue and revision cancellation, persisted download jobs, interrupted install rollback, corruption rejection, path containment, diagnostic allowlist, localized placeholders, mirror fallback, dropped connection resume and HTTP range handling.

```sh
./gradlew :app:assembleNpu :app:assembleNpuAndroidTest -PtestBuildType=npu
adb install -r app/build/outputs/apk/npu/app-npu.apk
adb install -r app/build/outputs/apk/androidTest/npu/app-npu-androidTest.apk
adb shell am instrument -w -e mode readiness com.localcaption.instant.npu.test/com.localcaption.instant.OfflineTest
```

Tests preserve existing model files; temporary preferences/files use a separate QA namespace. Stop capture/downloads before running them. Interface tests may temporarily switch locale; they must restore it afterward.

## Output completeness and model recovery

Run `-e mode robustness` on the NPU test build with Gemma4 and at least one compatible alternate text/speech model already installed. This forces a 32-token Gemma response limit and verifies retranslation and the final numeric clause, checks an 884-character input, Unicode/source coverage, bounded retries, empty-output rejection and cancellation. Injected translation/ASR failures must offer a different installed route and complete real inference after the recovery action. The test restores the original model choices. It also checks system recognizer reuse and localized recovery placeholders.

`-e mode recovery-ui -e seconds 120` requires an unlocked device and previously granted microphone permission. It captures the actual error screen, clicks the recovery button, then starts the production Gemma microphone service and sends the app to the background with the screen off. It asserts continued audio UI updates and capture status. It stops capture and restores model choices in cleanup; no microphone audio or transcript is written to the report. Screen-off state is intentional; unlock manually afterward if needed. This short service check complements the longer inference soak and is not an all-day or all-OEM survival guarantee.

## Audio quality and sustained performance

Audio fixtures are not included in the public source archive. Prepare 16 kHz mono PCM16 WAV fixtures with redistribution permission and retain their source/license. The existing device benchmark uses public-domain LibriSpeech samples, attributed ESC-50 environmental samples, and synthetic English speech. Do not add private recordings to the repository. The sustained test expects `app/src/androidTest/assets/english.wav` saying: “Hello everyone welcome to our meeting we will discuss the new project today please speak slowly thank you for your help”. Generate this sentence with a locally available TTS voice whose output terms permit testing, or supply your own consented recording. Record which voice/recording was used; scores from different fixtures are not directly comparable.

```sh
adb shell am instrument -w -e mode soak -e minutes 30 -e uncached true -e textModel gemma4-e2b-npu com.localcaption.instant.npu.test/com.localcaption.instant.OfflineTest
```

This repeatedly feeds paced prerecorded speech through the actual VAD/ASR/translation pipeline and checks queue drain, omissions, ASR word errors, latency, available memory, process PSS/native heap, Android thermal status, battery level/temperature/charging state and screen-off/background state. The model choices are restored on completion, and `release-soak-progress.json` is updated after each iteration. Battery readings describe the whole phone during this workload, not isolated app power consumption. `uncached=true` clears the text model's sentence cache before each repetition; omitting it measures repeated-input behavior with caching, which must not be reported as sustained translation inference speed. It does **not** establish 30-minute microphone/MediaProjection service survival or performance in all acoustic conditions. Those require the live checks below. Sustained tests must use the current app and model hashes. Retain failures as well as passes.

For each language, measure WER (word-token languages) or CER (Chinese/Japanese and appropriate scripts), translation adequacy judged by native speakers, named-entity/number/negation errors, time from audio start and from end of utterance to first/final captions, warm/cold load, P50/P95/P99, peak PSS, thermal status and battery state. Use accented speech, code switching, long turns, noise, silence, singing, multiple speakers and realistic video audio. Do not compare raw inference milliseconds with end-to-end subtitle latency.

## Physical validation matrix

Record phone model, SoC, Android/build, RAM, page size, provider, model hashes and capture source. Target SM8750 (at least two vendors), a different Snapdragon, MediaTek, Exynos, Tensor, a lower-RAM device, Android 10/13/15+, a device without Google services and an actual 16 KB-page device. Mark every untested cell “not tested”. The current available physical device is a Samsung S25+; simulated profiles cannot fill other physical-device cells.

Test fresh installation without models, upgrade retaining settings/models, all permission denials, user projection cancellation, no capturable audio, rotation, RTL, large fonts, screen off/on, headset/Bluetooth changes, calls/another recorder, background/foreground transitions, forced process termination during downloads, disk full, Wi-Fi-to-metered transition, corrupted same-size files, canceled import and repeated deletion/reinstallation. Start a 30-minute real capture separately and verify stopping through its notification.

## Public beta release status

Ship only with clear compatibility limits and current evidence. Native-speaker review, representative physical hardware coverage, 16 KB runtime testing, and NPU third-party redistribution review remain external release gates until actual records exist. CI configuration in this repository is prepared locally; no successful GitHub Actions run may be claimed before the workflow actually runs in the published repository.

## 1.19 direct translation extensions

Generate the original fixed test phrases with `python tools/prepare_translation_quality.py`. Build instrumentation, install the requested text model, and run:

```sh
adb shell am instrument -w -e mode translation-extension -e phase quality -e textModel hy-mt2-1.8b-cpu com.localcaption.instant.npu.test/com.localcaption.instant.OfflineTest
```

Repeat with `qwen3-1.7b-npu` or `gemma4-e2b-npu` where compatible and installed. Pull `translation-extension-verification.json` from the app external files directory after each run. Score with `python tools/score_translation_quality.py report.json` (requires sacrebleu). The 94 cases cover ten languages, short requests, negation, a code, station questions, weekday changes, and Japanese/Korean direct Chinese translations. Risk flags were expanded after manual review found weekday/pronoun errors that initial script checks missed. Do not call chrF++ an accuracy percentage. Keep baseline failures, prompt revisions and remaining mistakes. This is an authored regression set, not an independent general-language benchmark.

Run `-e mode translation-extension -e phase integration` for the actual CPU factory/router, in-flight cancellation and recovery, cache, multi-chunk translation, raw-file integrity guards and Japanese/Korean official WAV fixtures. The latter are smoke checks, not WER/adequacy certification. The test uses installed SenseVoice CPU / Whisper Base where available and retains recognized and translated fixture text only. User transcripts are not consumed.

Both APKs must contain libcaption_cpu.so with ARM64/16 KB alignment and the llama.cpp/HY-MT2 notices. Portable must exclude all vendor NPU native plugins. Chipset catalog tests assert one vendor dispatch plugin per runtime search directory: LiteRT otherwise selects the first library and may load MediaTek on Qualcomm. Gemma3 requires official authorization and exact per-chip file import; do not claim a physical MediaTek test based on catalog or import-guard checks.

For ADB-only test fixture installation, ensure files are readable by the application UID. Android shell `cp` may leave shell-owned 0660 files whose sizes are visible but whose content cannot be read by the app. Prefer the app importer/downloader or set appropriate test-file permissions before inference. Record setup failures separately from model/runtime failures.

## 1.20 caption UI and floating appearance

Run `-e mode overlay` on the unlocked device with overlay permission granted. It operates the actual Caption style controls, reopens the page, verifies independent main/overlay source switches, text alpha and window size, drags both subtitle rows and panel padding, tucks to both edges, updates appearance while tucked and checks the latest text after expansion. Landscape is forced using a foreground activity because some launchers remain portrait even when UiAutomation requests rotation. Test full glyph bounds at large font sizes, not just containing View rectangles. The test restores its display keys and rotation; it does not download/delete models or capture speech.

Run `-e mode interface-languages` for ten locales and seven page/tab states each: main, settings, main caption style, floating caption style, text models, speech models and setup. Check the recorded screenshots and clipping assertions, including Arabic RTL. These are UI tests on the recorded S25+, not broader hardware or inference benchmarks.

Run `-e mode overlay-idle` with the unlocked phone, overlay permission and licensed `english.wav` test fixture. It checks real VAD speech notifications, then injects synthetic captions into the production service rendering path and actually waits 60 seconds. Verify both floating rows clear while tucked, main history remains intact, meter/translation updates do not extend the deadline, late translations and reopening/rotation cannot revive old text, and a new utterance resumes captions. This test does not capture microphone audio. The fixture captions and temporarily changed source-text preference are restored afterward.

## 1.20.1-beta.1 packaging verification (2026-10-18)

See [release evidence](catalog/1.20.1-release.json). Both signed APKs pass signature, ZIP/ARM64 ELF 16 KB alignment and license-snapshot checks. Portable DEX contains no LiteRT-LM Engine API. Twenty-one license snapshots are hash-verified, including twelve exact cached AAR entries. Source export includes both backend source sets and excludes native caches and secrets. NPU lint completed with 0 errors / 30 warnings and the previously documented Kotlin metadata diagnostics. On SM-S936U, the matching `.npu` test build upgraded in place and passed the Qwen restart/translation smoke test; Qwen3-1.7B and X-ASR selections persisted. The public signed packages were not separately installed in this run. No new microphone, portable audio or all-device quality benchmark is claimed for this packaging-only change. Full NPU distribution review remains incomplete as documented in NPU-DISTRIBUTION.md.
