Interface localization

Default: follow the system locale; unsupported system languages use English.
Supported: en, zh-Hans, es, fr, de, pt, ru, ja, ko, ar.
This setting is separate from speech recognition and subtitle target languages.

catalog.json contains the original Chinese text and stable English lookup keys.
translations.tsv has columns: ID | es | fr | de | pt | ru | ja | ko | ar.
Keep leading/trailing spaces and literal \n separators for composed labels.
help.json contains the longer help text for those eight languages.

Run python tools/localization/generate.py after editing the catalog/translations.
It checks completeness and writes native Android ui_strings.xml resources plus
UiStrings.java, which bridges the existing bilingual Ui.t calls to resources.
English resource names contain a slug and hash, independent of numeric row IDs.
The generated files are checked in so ordinary Gradle builds need no Python.
No runtime translation, network call, extra model or Google service is involved.
License texts and proper model names retain their original spelling.

Update AppLanguage.java and res/xml/locales_config.xml together when adding a
language. Keep the English default resource set complete. Use logical start/end
layout rules; only the floating handle's physical left/right edges stay physical.

Validation:
  Gradle assembleNpu assembleNpuAndroidTest lintNpu -PtestBuildType=npu
  Instrumentation mode: interface-languages
  Optional phase: resources (skips activity recreation and screenshots)
  Optional phase: readme (captures the ready main screen and floating-caption
  style page for each locale, waiting for activity animations to settle).
  Prepare the selected models before using readme; it does not download models.
  Output: app external files/language-<tag>-main.png and
  language-<tag>-floating-style.png. Keep screenshots unmodified and label the
  build type in documentation; changing UI language does not change the speech
  or subtitle language, and the style preview retains its English source text.
The device test restores the original interface language and checks that speech,
translation model and target-language settings are unchanged.
