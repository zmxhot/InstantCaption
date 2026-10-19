# Instant Caption · Free Offline AI Translator for Android

## Live translated captions · Low latency · Local LLMs

**Instant Caption (瞬译) is a free, open-source AI simultaneous interpretation app for Android: real-time speech translation with live captions and floating subtitles, powered by local large language models (LLMs).** Speech recognition and translation run offline on your phone after a one-time model download. Translate microphone input or supported device audio with low-latency streaming. No subscription, cloud translation API key or Google Play services required. The source includes chipset-aware NPU integration; the public APK uses portable CPU models.

[**Download APK**](https://github.com/zmxhot/InstantCaption/releases/tag/v1.20.1-beta.1) · [Get started](#get-started) · [Compatibility](docs/COMPATIBILITY.md) · [Optional donations](docs/SUPPORT.md)

**English** · [简体中文](README.zh-CN.md) · [Español](docs/i18n/README.es.md) · [Français](docs/i18n/README.fr.md) · [Deutsch](docs/i18n/README.de.md) · [Português](docs/i18n/README.pt.md) · [Русский](docs/i18n/README.ru.md) · [日本語](docs/i18n/README.ja.md) · [한국어](docs/i18n/README.ko.md) · [العربية](docs/i18n/README.ar.md)

> **Public beta · Android 10+ · ARM64.** The signed APK currently available uses portable CPU models. Full NPU integration is included in the source; its prebuilt APK is withheld while upstream binary-license mapping is clarified. [Distribution status](docs/NPU-DISTRIBUTION.md). Install the APK, then download the required models inside the app once; recognition and translation can then run offline. Model weights are not bundled in the APK.

## What makes it useful

- **Free AI interpretation:** live translated text subtitles, with no subscription or paid feature unlocks. Donations are voluntary.
- **Local large language models:** use HY-MT2-1.8B for direct multilingual CPU translation, or smaller OPUS-MT routes. The full integration also supports matching Qwen3 and Gemma NPU exports.
- **Low-latency streaming:** speech activity detection, short segments and a bounded processing queue reduce waiting. Actual speed depends on model, phone, language and temperature.
- **Private, on-device inference:** speech recognition and text translation run locally after setup. No Google Translate SDK, Google Play services requirement, cloud translation API or API key.
- **Microphone or device audio:** translate nearby speech or audio from apps that allow Android playback capture.
- **Floating captions:** drag the whole panel, tuck it against an edge and reopen it using its handle. Adjust font size, text transparency and height. Show original text independently on the main screen and overlay. Only floating captions clear after 60 seconds without new speech.
- **Independent model management:** separate speech and text model lists with downloads, switching, deletion and import. Recommendations use processor, Android version and RAM; downloads support resume and checksums.
- **Ten interface languages:** English, Chinese, Spanish, French, German, Portuguese, Russian, Japanese, Korean and Arabic. Follow the system with English fallback. Available speech/subtitle languages depend on the selected models.

<p>
  <img src="docs/assets/screenshots/en/main.png" width="45%" alt="Caption screen in English" />
  <img src="docs/assets/screenshots/en/caption-style.png" width="45%" alt="Floating caption settings in English" />
</p>

*English interface, captured on a Galaxy S25+ with the NPU development build. The preview shows translated text and its English source. The public portable APK uses CPU models.*

## Get started

1. Download **`instant-caption-1.20.1-beta.1-portable-release.apk`** from [Releases](https://github.com/zmxhot/InstantCaption/releases/tag/v1.20.1-beta.1) and install it. **No compilation required.**
2. Choose spoken and subtitle languages. Default: English → device language; on English devices, Spanish → English.
3. Download the recommended speech/text models over Wi-Fi, or import matching model files. The app shows download size and disk space. For direct multilingual LLM translation, choose **HY-MT2-1.8B** when supported: about 1.13 GB for the text model, plus a speech model; 6 GB+ RAM preferred.
4. Run the local model check. Select **Microphone** or **Device audio**, grant the corresponding permission and start captions.
5. After the models are ready, recognition and translation can run offline. Public portable models need no account or subscription.

Public app ID: `com.localcaption.instant`. Maintainer test installations ending in `.npu` are separate. Preserve old model files and use import when migrating; do not uninstall a test build before saving needed models.

## NPU and measured latency

The source integrates Qualcomm QNN/GenieX and LiteRT-LM vendor backends. Model availability follows documented chipset/runtime combinations and Android/RAM requirements. **Having an NPU does not make every NPU model compatible.** Unknown targets retain portable paths; individual handset testing is not a download whitelist.

Historical development measurements on **Samsung Galaxy S25+ / SM8750**:

| Measurement | Median | Scope |
| --- | --- | --- |
| First translated caption from audio start | 1.461 s | 1.18 NPU development build, SenseVoice + Gemma4, repeated fixture over 30 minutes |
| Gemma4 NPU text inference | 324 ms | 1.19 build, 94 short authored cases across ten languages |
| HY-MT2-1.8B CPU text inference | 1,768 ms | Same 94-case test set; portable text backend |

Text inference excludes speech recognition, loading and segmentation. NPU figures **do not describe the downloadable portable APK** and are not promises for other devices. [Streaming evidence](docs/catalog/1.18-verification.json) · [Text results](docs/catalog/1.19-verification.json) · [Quality cases](docs/catalog/1.19-text-quality.json) · [Latest package checks](docs/catalog/1.20.1-release.json).

## Practical limits and privacy

### Can I translate speech and video audio offline?

Yes. Once the required speech-recognition and translation models are downloaded, both stages run on your Android device. Use the microphone for conversations and talks, or device-audio mode for videos and other apps that allow audio capture. Floating translated subtitles can appear over the app you are watching; optional original-language captions help with language learning.

### Is this a free live-caption app or a voice-dubbing app?

Instant Caption provides speech-to-text recognition and real-time translated text subtitles. It does not speak a translated voice track. The app is free to download and use, with no subscription or paid model-selection unlocks; donations are optional.

### Can every Android phone use NPU acceleration?

NPU acceleration requires a matching processor, runtime and model export. The public APK currently provides offline CPU inference; NPU integration is available in the source. Check [device compatibility](docs/COMPATIBILITY.md) and [NPU distribution status](docs/NPU-DISTRIBUTION.md) before choosing a build.

### Capture, privacy and accuracy

Device-audio mode uses Android's screen-sharing consent API to obtain playback **audio**. This app captures no screen images or video. Calls, protected media and apps that prohibit capture may be unavailable.

Voice activity detection reduces environmental captions, but background speech, singing and recognition errors remain possible. Translations may omit or change meaning; short fragments and English-pivot routes can reduce quality. Large models need storage and free RAM. Thermal throttling and battery management affect speed. Downloads need internet; inference is local. See [Privacy](PRIVACY.md).

This APK supports ARM64 Android 10+; ARM32, x86 and iOS are outside its supported platforms.

## Contribute and license

[Build](docs/BUILD.md) · [Testing](docs/TESTING.md) · [Contributing](CONTRIBUTING.md) · [Report a bug](https://github.com/zmxhot/InstantCaption/issues) · [Security](SECURITY.md) · [Changelog](CHANGELOG.md)

Please include device/processor, Android version, app version, models, language pair and reproduction steps. Settings can export diagnostics without audio or transcript content. Native-speaker reviews and real-device reports are welcome. GitHub Actions builds portable debug artifacts; use the signed Release APK for everyday installation.

Application source: **Apache-2.0**. Models and vendor libraries retain separate licenses and usage restrictions. Preserve [LICENSE](LICENSE), [NOTICE](NOTICE) and bundled third-party texts. Review [NPU distribution status](docs/NPU-DISTRIBUTION.md) before distributing a full NPU build.

## Support development — completely optional

If this free app helps you, donations support maintenance and device testing. **No payment is needed for translation, downloads or model selection.**

[**PayPal · Alipay · WeChat Pay · Bitcoin · Solana**](docs/SUPPORT.md)
