# Privacy / 隐私

Effective for 1.19.0 beta, 2026-10-18.

**Audio and captions:** microphone/device audio is processed in memory. The app does not save recordings. Up to 80 recent caption lines are retained in the current process. Closing a screen does not erase the session; Clear removes it. Exporting captions writes their text only to a document location you choose.

**Inference:** bundled/local speech and translation models run on the phone. Optional Android on-device speech recognition passes PCM to the installed system provider using the on-device API and an installed language pack. It never falls back to a cloud recognizer. That provider is maintained by the phone/vendor and has its own privacy policy. Choose a downloaded speech model to keep inference entirely within this app process.

**Network:** internet is used for explicit model downloads. The selected source or mirror receives ordinary download metadata such as IP address and model URL. No audio or subtitle text is sent by the app's downloader. Third-party mirrors are independent services. Both Qwen AWS endpoints use the same hosting provider and do not guarantee independent regional availability. Local folder/file import works without network access. Optional Gemma3 downloads open the publisher’s website for its own login and terms. That website has a separate privacy policy; the app does not read passwords or access tokens. The app contains no advertising, analytics, account or payment SDK.

**Stored data:** model files, settings, resumable download offsets/queues, selected file/folder permissions, and per-build model-load verification markers are stored locally. Model-load markers include the Android build fingerprint locally; it is excluded from diagnostic exports. Remove models through Model center. Uninstalling removes app-private settings/models; exported files remain where you saved them. Cloud backup and device transfer are excluded by the manifest and data extraction rules.

**Permissions:** microphone/audio recording; foreground services and notification for active capture/download; screen-sharing consent for playback audio; optional overlay permission; network access; temporary wake lock during capture. No Accessibility Service, contacts, location, media-library scan, root access or full-storage access is requested. Import accesses only the file or folder chosen through Android's document picker.

**Diagnostics:** a manual export lists app version, model/processor/Android version, RAM, thermal state, selected languages/model IDs, readiness, queued job counts and recent latency counters. It excludes raw logs, captions, recordings, account IDs, serial numbers, IP addresses, build fingerprints and folder URIs. Export does not upload or send anything. Review any file before voluntarily sharing it.

**中文摘要：** 应用不保存录音，不自动上传音频或字幕；最近 80 条字幕仅在内存中，手动导出才写入选定文件。模型下载会向所选下载源暴露普通网络请求信息。系统离线识别属于系统供应商组件，也可以手动选择应用内模型。设置和模型保存在本地，禁止系统备份与设备迁移；自行导出的文件不会随卸载删除。诊断导出不含字幕、录音、原始日志、IP、账号、序列号、系统指纹或文件夹路径，不会自动分享。

Gemma3 为可选的授权模型：登录及条款确认在官方网页完成，应用不读取密码或访问令牌；导入只访问用户在系统文件选择器中授予的文件或文件夹。
