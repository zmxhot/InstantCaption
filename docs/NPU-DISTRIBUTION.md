# NPU distribution review — 2026-10-18

This is an artifact-specific technical review, not individualized vendor approval or legal advice. An Apache license for this app or a model does not relicense vendor binaries. The full NPU APK remains a **release candidate**; the portable APK is the signed public beta package prepared by this release. Public distribution scope: source and the signed portable beta APK. The complete NPU candidate remains local.

## What was completed

- Retained the actual `LICENSE.pdf` and `NOTICE.txt` from the pinned official QNN 2.51.0 AAR, plus an accessible text extraction. The PDF is authoritative. Earlier POM links alone were insufficient: the embedded document is the **QTI AI Stack License**, while the POM links to an AI Hub license.
- Retained original LICENSE and complete THIRD_PARTY_NOTICE files from LiteRT-LM 0.18.0 and all three LiteRT NPU 2.3.0 dispatch artifacts. No entries were removed or paraphrased in these snapshots.
- Added upstream GenieX QAIRT/proc repository notices and optimized Qwen model-card snapshots. These current repository pages are contextual evidence, not a certified dependency list for the older GenieX 0.8.0 binary.
- Recorded source URLs, artifact hashes, entry names and snapshot hashes in `app/src/main/assets/licenses/runtime-license-sources.json`. `tools/check_license_bundle.py` verifies the retained files and compares cached AAR entries when available. `tools/check_apk.py` checks the same hashes inside the APK.
- Made all text notices available in Settings → Help → Licenses, with pagination for long files. Original PDFs remain in the source/APK assets.
- Added a publisher-restriction notice and official model-card link before Qwen optimized-model downloads, in all ten interface languages.
- Removed LiteRT-LM Java API dependencies from portable builds as well as its native plugins; portable builds use a separate unavailable-backend stub. Model eligibility already excludes all NPU models in this build. Existing downloaded files are retained.

## Component decisions

| Component | Evidence | Status / obligations |
| --- | --- | --- |
| QNN 2.51.0 host + DSP stub/skeleton files | Original AI Stack License in the hash-pinned Maven AAR | Section 1(iv) permits object-code distribution **incorporated in your software application**, subject to the agreement. Standalone SDK/library distribution is excluded. Preserve proprietary notices and third-party terms. Usage, export, patent and other conditions still apply. |
| GenieX 0.8.0 API, JNI and plugins; retained NetRunExtensions | Original Maven artifact, repository BSD license, QAIRT/proc notices | Source BSD permission does not by itself resolve all prebuilt dependencies. The 0.8.0 AAR has no complete artifact-bound notice/BOM. Exact transitive source/version and notice mapping remains open. The older QNN 2.45 license is retained only as historical context, not proof that it covers every file in this AAR. |
| LiteRT-LM 0.18.0, Tensor/MediaTek/Qualcomm dispatch 2.3.0 | Original AAR licenses and full third-party notices | First-party Apache notices retained. LM and Tensor notices include a “Google Runtime Environment” GPL entry. Exact Android binary inclusion, exceptions and source obligations are not established by the notice alone. Keep full package as candidate until clarified. |
| Qwen3 optimized exports | Original-model Apache license + Qualcomm model cards | Weights downloaded separately. Publisher restrictions additionally include education/vocational training, employment and emotion-recognition uses, among others. Read complete cards; this summary is not an exhaustive restriction list. |
| Gemma3 optimized export | Official authorization flow and Gemma terms | No bundled or mirrored weights; use official authorization/download and local import. |
| Gemma4 optimized export | Pinned publisher model-card metadata | Publisher declares Apache-2.0 at the catalog revision; runtime review remains independent. |
| CPU models/runtimes | Existing Apache/MIT/FunASR/NVIDIA/CC-BY notices | Keep per-component terms and attribution. Portable does not package Qualcomm or LiteRT native runtimes. |

QNN artifacts: [2.51.0 Maven directory](https://repo.maven.apache.org/maven2/com/qualcomm/qti/qnn-runtime/2.51.0/).
GenieX: [repository](https://github.com/qualcomm/GenieX), [QAIRT notices](https://github.com/qualcomm/geniex-qairt-plugin/blob/main/THIRD_PARTY_NOTICES.md), [proc notices](https://github.com/qualcomm/geniex-proc/blob/main/THIRD_PARTY_NOTICES.md).
Qwen: [0.6B](https://huggingface.co/qualcomm/Qwen3-0.6B), [1.7B](https://huggingface.co/qualcomm/Qwen3-1.7B), [4B](https://huggingface.co/qualcomm/Qwen3-4B).

## What cannot yet be marked complete

The vendor binary mapping above cannot be completed by granting ourselves permission or by copying license files. A response/source manifest from the maintainers, or a reproducible replacement built from fully mapped source, is needed to close those entries. No request was sent to third parties on the user's behalf.

[LiteRT-LM issue 3556](https://github.com/google-ai-edge/LiteRT-LM/issues/3556) discusses a similar “Google Runtime Environment” SBOM entry for **iOS 0.16.0**. It is supporting context only, not proof that Android 0.18.0 contains GPL code. The Android review is based on the exact AAR notices retained here. Do not describe the entire runtime as GPL or claim infringement on this evidence.

Keep vendor AARs, extracted standalone `.so` files, model caches and signing keys out of the GitHub source export. The complete NPU candidate is retained locally for functional testing. The signed portable beta can be reviewed independently of these unresolved NPU entries. Signed release configuration does not imply that all devices or languages have passed stable-release testing.
