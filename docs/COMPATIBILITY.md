# Compatibility

Hardware eligibility is checked separately from an observed model load on the current device/OS/app build. Model details show whether the current build has loaded the model successfully. Neither label certifies language accuracy.

Download availability follows the processor's official runtime/model documentation, the matching packaged runtime and model catalog, Android version and memory. There is no handset whitelist and no requirement to test each phone before making a compatible model downloadable/selectable. Actual model-load results are informational. Checksums protect download integrity; they do not impose a device-testing requirement.

## Bundled processor configuration

`app/src/main/assets/device-model-catalog.json` is the source of truth for processor matching, available speech/text models, recommendation order, Android/RAM limits, model manifest filenames and install directories. It currently contains 23 chip/family matching rules and twelve model profiles: portable ARM64, seven Snapdragon targets, Tensor G5/G6 and MT6989/MT6991. The catalog has 35 NPU model/chip pairs; these are not 35 different model families.

| Detected processor family | Download profile |
| --- | --- |
| Snapdragon 8 Elite / SM8750 | matching V79 speech, Qwen and Gemma4-E2B NPU text models plus portable models |
| Snapdragon 8 Elite Gen 5 / SM8850 | matching V81 NPU models plus portable models |
| Snapdragon 888 / SM8350 | chip-specific SenseVoice NPU; portable speech, HY-MT2 direct CPU and OPUS text |
| Snapdragon 8 Gen 1 / SM8450, 8+ Gen 1 / SM8475, 8 Gen 2 / SM8550, 8 Gen 3 / SM8650 | chip-specific X-ASR, SenseVoice and bilingual Zipformer NPU; portable speech, HY-MT2 direct CPU and OPUS text |
| Other Snapdragon 8 / 7 / 6 / 4 series, older MSM / SDM | portable speech, HY-MT2 direct CPU and OPUS text models |
| Dimensity 9300/9300+ (MT6989), 9400/9400+ (MT6991) | Gemma3 NPU authorized import; portable speech, HY-MT2 and OPUS |
| Other MediaTek Dimensity / Helio / MT-series | portable speech, HY-MT2 direct CPU and OPUS text models |
| Samsung Exynos / S5E-series | portable speech, HY-MT2 direct CPU and OPUS text models |
| Google Tensor G5 / G6 | Gemma4-E2B NPU text; portable speech, HY-MT2 direct CPU and OPUS text |
| Other Google Tensor | portable speech, HY-MT2 direct CPU and OPUS text models |
| HiSilicon Kirin | portable speech, HY-MT2 direct CPU and OPUS text models on compatible Android systems |
| UNISOC / Spreadtrum | portable speech, HY-MT2 direct CPU and OPUS text models |
| Unknown ARM64 processor | portable fallback, with small-model recommendation when RAM is low or unknown |

The app checks `Build.SOC_MODEL` first, then `Build.SOC_MANUFACTURER`, then `Build.HARDWARE` for older Android devices. Chip aliases and variant suffixes are matched by full-string patterns. Generic manufacturer/board rules cannot enable an NPU profile. Brand names such as Samsung, Xiaomi or OnePlus are not used to infer the processor.

To extend the list for an existing runtime, add a `chipsets` entry referring to a model `profile`; add a new profile and referenced model manifest when binary files differ. Profiles can inherit portable models, specify recommendation priority and low-memory preferences, and independently list speech and text models. `legacyStorageProfile` is used only to inspect/remove old installed files; it never makes an unsupported model downloadable. Keep existing model IDs/directories stable to preserve downloads. A new vendor runtime still requires its own integration before a configuration entry can expose it.

Run `python tools/check_device_catalog.py` after changes. It validates the configuration and referenced packages; Android instrumentation additionally checks actual parser behavior, configuration-only model removal/priority changes/new chipset aliases, and fallback behavior. These are automated configuration checks, not individual handset certification. Download URLs and SHA-256 values remain in the referenced model JSON manifests.

| Environment | Speech | Text translation | Verification |
| --- | --- | --- | --- |
| Samsung S25+ SM-S936U / SM8750 | dedicated QNN and portable CPU | Qwen NPU and OPUS CPU | device tests; see release report |
| Other SM8750 Android 15+ phones | candidate NPU/CPU paths | candidate Qwen/OPUS | hardware eligibility only until tested |
| SM8850 / Snapdragon 8 Elite Gen 5 | chip-specific X-ASR, SenseVoice, Zipformer; Nemotron compiled on first load; portable CPU | chip-specific Qwen 0.6B / 1.7B / 4B; OPUS | official documentation and package inspection; no local SM8850 physical test required for availability |
| Other ARM64 Android 10+ | Whisper Tiny/Base or SenseVoice CPU | HY-MT2 direct CPU; OPUS/Whisper pivot on smaller-memory devices | route tests; physical coverage incomplete |
| Android 13+ on-device speech provider | optional installed, supported PCM language pack | selected local text model | provider capability checked at runtime |
| ARM32, x86, Android below 10, iOS | unsupported | unsupported | no install promise |

Google Play services are not a prerequisite. A system recognizer must actually advertise an installed on-device pack and support the requested audio mode. Merely detecting Google services does not establish offline speech support. Automatic mode can reuse system recognition; model selection remains available in the speech tab.

| Translation route | Scope |
| --- | --- |
| Same language | recognition captions, no text translation weights needed; Cantonese → Chinese is this path |
| Qwen3 0.6B / 1.7B / 4B NPU | SM8750 or SM8850, Android 15+, nominal 8 / 8 / 12 GB RAM respectively; additional free-memory checks apply |
| Gemma4-E2B NPU (experimental) | SM8750 or Tensor G5/G6, Android 15+, nominal 12 GB RAM, at least 4 GB currently free; 3.02 / 3.11 / 3.31 GB downloads respectively |
| English → OPUS target | Chinese, German, French, Spanish, Russian, Arabic, Hindi, Italian, Portuguese |
| Supported non-English → English | Whisper speech translation; English text model unnecessary |
| Supported non-English → OPUS target | Whisper → English → OPUS; possible compounded translation errors |
| HY-MT2-1.8B Q4_K_M CPU | ARM64 Android 10+, nominal 4 GB RAM (6 GB+ preferred), at least 1.8 GB free at load; 1.13 GB file. Direct translation including Japanese/Korean targets, no English bridge |
| Gemma3-1B NPU (experimental translation) | MT6989/MT6991 or SM8550/8650/8750, Android 15+, nominal 6 GB RAM, at least 1.8 GB free; official authorization followed by exact-file import. No local MediaTek physical inference test |

Speech language options are model-specific (31 entries overall, 30 for current Whisper models). Listing a target for Qwen means the model can be invoked for it, not that native-speaker accuracy has been established. Qwen 0.6B is experimental due to omissions. The app filters unsupported language combinations before configuration.

Low RAM, thermal throttling, background process limits and OEM power management affect latency. Overload stops capture and marks unprocessed text explicitly. Notifications permit stopping capture when the overlay is hidden. Captured-audio restrictions are enforced by Android/source apps and cannot be bypassed by this app.

## Sources and extension policy

- [Qualcomm GenieX platforms](https://github.com/qualcomm/GenieX/blob/main/docs/en/get-started/platforms.mdx) documents SM8750 and SM8850. GenieX 0.8.0 supplies the API/LLM plugin. The separately pinned official QNN 2.51.0 AAR supplies coherent common libraries plus V68/V69/V73/V75/V79/V81 Stub/Skel libraries.
- [Qualcomm Qwen3-1.7B assets at v0.64.0](https://github.com/qualcomm/ai-hub-models/blob/v0.64.0/src/qai_hub_models/models/qwen3_1_7b/release-assets.yaml) lists a separate `8_elite_gen5_for_galaxy` target, also available for 0.6B and 4B. Its configuration declares `soc_model: 87`, `dsp_arch: v81`. App catalogs pin full-archive and individual-file hashes.
- [Sherpa chip-specific QNN models](https://github.com/k2-fsa/sherpa-onnx/releases/tag/asr-models-qnn-binary) provides separate SM8850 archives. [QNN model format documentation](https://k2-fsa.github.io/sherpa/onnx/qnn/run-executables-on-your-phone.html) distinguishes chip-specific context binaries from portable Qualcomm model libraries. Nemotron uses the latter and builds a local context cache.

Do not map an unknown newer processor to SM8750/SM8850 merely by brand or numerical order. Add a documented chipset mapping, matching runtime libraries and a hash-pinned model package together. Automated catalog/build checks prevent routing the wrong binaries; physical-device results improve recommendations but do not gate availability. Other processors can be added by the same policy when their integration and matching packages are available.

SM8750 model IDs, install folders and downloads remain unchanged. SM8850 has separate model directories and Nemotron caches. Both model lists allow independent speech/text selection immediately.

## Upstream NPU inventory and refresh

The bundled `npu-upstream-support.json` and `docs/catalog/npu-support.json` distinguish SDK support, publisher model targets, authorization requirements and actual enabled app downloads. Thirteen primary sources were retrieved for this revision. `docs/catalog/sherpa-upstream.json` records the broader upstream speech release. Refresh scripts do not run inside the app or require Google Play services.

- `python tools/collect_npu_support.py`: collect official documentation and publisher model repositories. A broad runtime support claim never enables a download.
- `python tools/sync_npu_catalog.py --refresh --prepare`: select implemented Sherpa recipes for SM8350/8450/8475/8550/8650, download and hash-check all 13 additional archives, generate per-chip file manifests and app rules.
- `python tools/prepare_litert_catalog.py`: import public Gemma4 publisher targets, immutable revision, sizes and LFS SHA-256. Optional `--download sm8750` verifies the full package locally. Gated repositories and masked hashes are rejected.
- `python tools/prepare_translation_extensions.py`: refresh hash-pinned HY-MT2 and authorized Gemma3 model manifests; processor targets must also have an integrated runtime.
- `python tools/check_device_catalog.py`: check runtime IDs, chip routes, package hashes and all manifest references. Rebuild the APK to distribute updated catalogs.

[LiteRT-LM model targets](https://developers.google.com/edge/litert/next/litert_lm_npu) and the [Gemma4 publisher repository](https://huggingface.co/litert-community/gemma-4-E2B-it-litert-lm) provide the text-model evidence. LiteRT-LM 0.18.0 and standalone LiteRT NPU dispatch libraries 2.3.0 are bundled in the NPU build. Model weights remain separate downloads. Tensor packages are publisher-documented targets, without a local physical Tensor test; that does not prevent their availability.

[MediaTek runtime documentation](https://developers.google.com/edge/litert/next/mediatek) lists MT6878, MT6897, MT6983, MT6985, MT6989, MT6991 and MT6990. The app bundles the MediaTek 2.3.0 dispatch/compiler plugins. Gemma3-1B files for MT6989/MT6991 are pinned to publisher revision d448835d84411a08f577e25f3afebe528402d73b; public LFS metadata now supplies SHA-256 even though downloading weights requires authorization. The app opens the exact official file page, then imports and verifies the authorized download. These models remain experimental and were not physically tested on a MediaTek phone. MT6993 and MT6990 are not mapped to these binaries. No mirror bypass is provided.

[Samsung runtime documentation](https://developers.google.com/edge/litert/next/samsung) lists E9955/E9965. A matching ready-to-download speech/text model for this app was not identified in the checked sources. Huawei HiAI and UNISOC sources also do not establish a ready caption-model package here. These are gaps in this app's package/runtime integration, not claims that the processors lack an NPU.

New NPU entries are labeled as documentation-based or experimental as appropriate. No device certification or cross-language accuracy claim is implied. Existing installed model folders are preserved; every different context-binary target gets its own folder.

## Direct CPU languages and quality

HY-MT2 supports a subset of the app language menu: Chinese, English, French, Portuguese, Spanish, Japanese, Turkish, Russian, Arabic, Korean, Thai, Italian, German, Vietnamese, Malay, Indonesian, Hindi, Polish, Czech, Dutch, Ukrainian, Hebrew and Tamil (plus recognized aliases where relevant). Unsupported pairs are filtered before download/selection. Greek, Swedish, Norwegian, Danish, Finnish, Romanian and Hungarian still use other compatible routes; availability is not a universal translation-quality claim.

The 1.19 tests compare the same 94 short text cases across available CPU/NPU models. Reference chrF++ is not an accuracy percentage. A model can run successfully yet mistranslate dates, negations or pronouns. The report retains outputs and examples; do not advertise a perfect error rate. Exynos, Kirin and UNISOC receive the portable CPU path because no matching NPU caption-model/runtime combination was established in the checked sources.

The default text recommendation now prefers Gemma4 on eligible devices (Android 15+, nominal 12 GB RAM), followed by Qwen 1.7B, then HY-MT2 where supported. Existing explicit selections remain unchanged. Compatible NPU speech retains priority; portable Chinese/Japanese/Korean/Cantonese direct recognition prefers SenseVoice when memory permits. Whisper remains required for OPUS English-pivot routes. These are practical defaults, not guarantees that one model is optimal for every utterance.

The publisher's optional vendor-system-library manifest declarations are preserved in NPU builds. LiteRT libraries are exposed through a per-vendor search directory so a Qualcomm device cannot accidentally select MediaTek dispatch. No physical MediaTek/Tensor result is implied by these packaging checks.
