# Changelog

## 1.20.1-beta.1 — 2026-10-18

- Preserve artifact-bound QNN/LiteRT license files and full third-party notices, with hashes and APK verification; long notices are readable in pages.
- Surface optimized Qwen publisher restrictions before download in all ten interface languages.
- Portable builds omit LiteRT-LM Java/native dependencies entirely. Full NPU functionality remains available in the local candidate; unresolved binary notice/source mapping is documented in docs/NPU-DISTRIBUTION.md.
- Refresh signed packages with the 1.20-beta.2 spacing fix and beta.3 floating-only 60-second speech-idle clearing. Public signing does not change the beta validation scope.


## 1.20.0-beta.3

- Clear floating source and translated captions after 60 seconds without confirmed speech, preserving main-screen history and the draggable panel/edge handle. New speech resumes captions; translation/status updates do not extend the timeout or revive cleared text.

## 1.20.0-beta.2

- Separate the home header from the audio-source selector with a 12 dp gap, including landscape and large-text layouts.

## 1.20.0-beta.1

- Reorganize caption styling into a dedicated main/floating tabbed page; simplify settings, pin the current model summary, give descriptions full-width rows and group guided setup into three steps.
- Use a two-column main layout on landscape phones to preserve subtitle space; standardize control spacing and touch feedback.
- Add an independent source-text switch for floating captions, leaving the main-screen preference unchanged.
- Add floating text transparency (0–100%) and automatic/custom panel height, with a shared live preview and ten-language controls.
- Keep whole-panel dragging and edge handles; constrain custom height to the usable screen after rotation and adapt line limits to font size.

## 1.19.0-beta.1

- Add pinned llama.cpp CPU inference and HY-MT2-1.8B Q4_K_M direct multilingual text translation, including Japanese/Korean targets without an English pivot.
- Integrate MediaTek LiteRT plugins and per-chip Gemma3-1B authorized download/import for MT6989/MT6991 and SM8550/8650/8750. Keep gated weights out of automatic/mirror downloads; verify imported files against pinned hashes.
- Isolate LiteRT plugin search directories by vendor, preventing MediaTek dispatch from being loaded on Qualcomm/Tensor devices.
- Add fixed ten-language text regression comparison and CPU router/cancellation/import tests; document remaining semantic errors instead of labeling successful inference as accuracy.
- Update ten-language help, model attribution, portable build/source bootstrap and device catalogs.

## 1.18.0-beta.1

- Detect Gemma output-token exhaustion with enabled LiteRT benchmark counters; retry truncated chunks at sentence/word boundaries. Qwen output-limit failures use the same bounded retry path.
- Preserve Unicode boundaries and source coverage, with a 20-second request budget, bounded retries, cancellation and no partial-result caching.
- Explain memory, temperature, missing-model, timeout, output-limit and runtime failures in all ten interface languages.
- Offer one-click recovery using an installed compatible text/speech route; exclude failed models, retain the language pair and reuse an available system offline recognizer. No automatic downloads.
- Stop failed recognition/translation sessions safely, and keep old session errors from affecting a replacement session.
- Extend sustained testing with uncached Gemma inference, PSS/native heap, battery temperature/charge state, screen-off/background observations and progressive reports. Add real recovery-button and foreground-microphone tests.

## 1.17.0-beta.1

- Refreshable primary-source NPU inventory separates runtime support, model packages and gated downloads. No per-phone test whitelist.
- Add 13 hash-verified speech packages for SM8350/8450/8475/8550/8650 and their QNN runtimes.
- Add standalone LiteRT-LM and public Gemma4-E2B NPU translation packages for SM8750 and Tensor G5/G6, with independent download, import, selection and removal.
- Keep existing Qwen/ASR model IDs, installation folders and user preferences.
- Record MediaTek gated Gemma3 targets and other vendor gaps without offering nonfunctional downloads.

## 1.16.0-beta.1

- Add `device-model-catalog.json` with 16 processor/family rules covering Qualcomm, MediaTek, Samsung Exynos, Google Tensor, HiSilicon Kirin and UNISOC, plus unknown-device fallback.
- Drive independent speech/text download availability, recommendation ordering, Android/RAM requirements, manifest selection and install directories from the bundled JSON.
- Match explicit SoC identifiers before manufacturer/hardware fallbacks; generic family matches expose portable models, not another chipset's NPU binaries.
- Show the detected processor in Model center, with labels for all ten interface languages.
- Preserve current model IDs, folders and settings; add automated parser/routing/configuration checks without requiring individual handset tests.

## 1.15.0-beta.1

- Match model downloads by documented chipset/runtime support without requiring individual handset tests.
- Add Snapdragon 8 Elite Gen 5 / SM8850 catalogs for X-ASR, SenseVoice, Zipformer, Nemotron and Qwen3 0.6B / 1.7B / 4B.
- Package the official QAIRT V81 Stub/Skel alongside V79; isolate chip-specific model files and compiled caches, preserving existing SM8750 installations.
- Retain download hash verification and Android/memory constraints; distinguish documentation-based compatibility from observed model loading.
- Add catalog routing and negative compatibility checks. The 1.14 release report remains historical; it is not evidence of physical SM8850 testing.

## 1.14.0-beta.1

- Guided first-time setup, language-route validation and device-aware defaults.
- Durable model download queue, pause/resume, metered-network consent and local folder import with pinned checksums.
- Recovery of interrupted atomic installs; explicit source changes for queued transfers.
- Bounded translation queue, stale draft cancellation, overload/error feedback and thermal/memory guards.
- Privacy-limited manual diagnostics; explicit playback-audio permission explanation and no-audio feedback.
- Ten-language strings for new flows, larger main-screen font controls, model-load validation labels.
- Separate portable CPU build without Qualcomm native libraries; pinned runtime bootstrap and clean source export.
- Formal release signing configuration, privacy/compatibility/security/contribution documentation and expanded license snapshots.
- Regression and sustained-inference test entry points; actual verification scope recorded separately.
