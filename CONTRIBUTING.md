# Contributing

Use the portable debug build for initial development. Include the app version, Android version, SoC/RAM, model IDs, language pair, audio source, and whether a failure reproduces after an app restart. Do not assume a brand or chip family is sufficient for NPU binary compatibility.

Keep speech recognition and text translation separate in UI and state. Avoid cloud fallback. Preserve existing installed model directories/settings during upgrades. Add a language only with an actual model route and documented test evidence; leave unsupported routes unavailable. Show tested/candidate/experimental status accurately.

UI text belongs in the localization catalogs. Run both generators and review all ten locales, RTL and large-font layouts. Translate source/target language labels consistently; interface locale must not change the speech/translation pair. Ask native speakers to review wording and translation fixtures; automated tests do not replace their judgment.

For model changes: pin every URL/revision, size and hash; record the original authors/license and conversion; test actual loading, inference, memory, corruption rejection and deletion. Report ASR WER/CER separately from translation adequacy and latency. Keep licensed fixtures and their provenance reproducible.

Run build, lint, source/package validation and the relevant device tests. Changes to capture/service/queue behavior need lifecycle and sustained-use checks. Release keys and local SDK paths must stay outside version control. See docs/TESTING.md and docs/BUILD.md.
