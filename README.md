<div align="center">

# Y.tube

<img src="docs/icon.png" width="180" alt="Y.tube icon" />

**Open-source YouTube & YouTube Music client for Android — ad-free playback, real background play, fast downloads (video, M4A/Opus audio), Shorts, live streams, Android TV.**

**عميل مفتوح المصدر ليوتيوب ويوتيوب ميوزك على أندرويد — تشغيل بدون إعلانات، تشغيل حقيقي في الخلفية، تحميل سريع (فيديو وصوت M4A/Opus)، Shorts، بث مباشر، و Android TV.**

[![Android](https://img.shields.io/badge/Android-7.0%2B-3DDC84?style=flat-square&logo=android&logoColor=white)](#download)
[![Media3 ExoPlayer](https://img.shields.io/badge/Player-Media3%20ExoPlayer-E50914?style=flat-square)](#english)
[![Kotlin](https://img.shields.io/badge/Kotlin-Jetpack%20Compose-7F52FF?style=flat-square&logo=kotlin&logoColor=white)](#english)
[![License: GPL-3.0](https://img.shields.io/badge/License-GPLv3-blue.svg?style=flat-square)](LICENSE)

[English](#english) · [العربية](#العربية)

</div>

---

## English

Y.tube is a lightweight Android client for YouTube and YouTube Music, built with Kotlin, Jetpack Compose and AndroidX Media3 (ExoPlayer). It is a community fork of [YTXTube](https://github.com/Koustubh12345/YTXTube) with a reworked playback, background-audio and download engine.

### Features

**Playback**
- Ad-free YouTube and YouTube Music playback, 4K and HDR where the device supports it, with a fixed-quality lock
- **Background playback** with an ON/OFF setting, plus a one-tap **Play in background** button on videos *and* Shorts: switches to audio only and goes straight to the home screen — no floating window
- Media notification that **stays while paused**, with a **✕ close button** that stops playback and exits the app
- Lock-screen, Bluetooth and headset controls through Media3 `MediaSession`
- Proper **audio focus**: pauses for phone calls, lowers volume for navigation prompts, never restarts after you paused it; pauses when headphones or Bluetooth disconnect (Shorts included)
- **Automatic recovery** from dropped connections and expired stream links, keeping your position
- Optimised ExoPlayer: hardware-decoder-aware codec selection (AV1 / VP9 / H.264), decoder fallback, single-codec adaptive ladder, stable rebuffering
- **Live streams** with real quality switching between the renditions the stream offers
- Picture-in-Picture, playback speed, captions, audio-track languages, video gestures, SponsorBlock
- Two mini-player styles and a Liquid Glass interface

**Downloads**
- Video up to 4K / HDR (MP4 or WebM) with exact or clearly estimated sizes and real codec names
- **Audio only** with quality choice: the original M4A (AAC) and WebM (Opus) streams, "Original quality" first, with codec, bitrate and size — saved without conversion (no fake MP3)
- **Fast**: downloads in 10 MB ranges to avoid YouTube's per-request throttling
- Real pause / resume, retry, completed-file verification, Android 7–10 storage support, files visible in gallery and music apps

**Everything else**
- Endless personalised Shorts, synced lyrics, search, channels, playlists, comments, live chat
- Multilingual interface, Android TV mode with D-pad navigation, one universal APK

### Download

Debug APKs are built automatically by GitHub Actions for every change (**Actions → Android CI → latest run → Artifacts → `YTXTube-debug-apk`**). Unzip and install `app-debug.apk`; you may need to allow installs from unknown sources.

### Build

```bash
./gradlew :app:testDebugUnitTest
./gradlew :app:assembleDebug
```

The APK is written to `app/build/outputs/apk/debug/app-debug.apk`. Requires JDK 21 and the Android SDK.

---

## العربية

Y.tube تطبيق أندرويد خفيف لمشاهدة يوتيوب ويوتيوب ميوزك، مبني بـ Kotlin و Jetpack Compose ومشغّل AndroidX Media3 (ExoPlayer). المشروع نسخة مطوّرة من [YTXTube](https://github.com/Koustubh12345/YTXTube)، اتعاد فيها بناء محرك التشغيل والتشغيل في الخلفية والتحميل.

### المميزات

**التشغيل**
- تشغيل يوتيوب ويوتيوب ميوزك بدون إعلانات، بجودة 4K و HDR لو جهازك بيدعمها، مع إمكانية تثبيت الجودة
- **تشغيل في الخلفية** مع زر تشغيل/إيقاف في الإعدادات، وزر **تشغيل في الخلفية** بضغطة واحدة في الفيديوهات **والـ Shorts**: يحوّل للصوت بس ويخرجك على الشاشة الرئيسية فوراً من غير شاشة عائمة
- إشعار التشغيل **بيفضل موجود وقت الإيقاف المؤقت**، وفيه **زر ✕** يوقف التشغيل ويقفل التطبيق كله
- التحكم من شاشة القفل وسماعات البلوتوث والسماعات السلكية عن طريق `MediaSession`
- **تركيز صوتي صحيح**: بيوقف وقت المكالمات، بيوطي الصوت مع تعليمات الخرائط، وعمره ما بيرجع يشغل لوحده لو انت اللي وقفته؛ وبيوقف لما السماعة أو البلوتوث يفصل (حتى في الـ Shorts)
- **استرجاع تلقائي** بعد انقطاع النت أو انتهاء صلاحية رابط البث، من نفس المكان
- مشغّل ExoPlayer محسّن: اختيار الكودك حسب دعم الجهاز للفك بالهاردوير (AV1 / VP9 / H.264)، بديل تلقائي لو الكودك فشل، وتقطيع أقل
- **بث مباشر** مع تغيير حقيقي للجودة من الجودات اللي البث بيقدمها
- صورة داخل صورة (PiP)، سرعة التشغيل، الترجمة، لغات الصوت، إيماءات الفيديو، SponsorBlock
- شكلين للمشغل المصغّر وواجهة Liquid Glass

**التحميل**
- تحميل فيديو لحد 4K / HDR (MP4 أو WebM) بأحجام دقيقة أو تقديرية واضحة وأسماء كودك حقيقية
- **تحميل الصوت فقط** مع اختيار الجودة: ملفات M4A (AAC) و WebM (Opus) الأصلية، "الجودة الأصلية" أولاً، مع الكودك ومعدل البت والحجم — من غير أي تحويل (مفيش MP3 مزيف)
- **سريع**: التحميل على أجزاء 10 ميجا عشان يتفادى تبطيء يوتيوب
- إيقاف مؤقت واستكمال حقيقي، إعادة محاولة، التحقق من الملف بعد التحميل، دعم التخزين على أندرويد 7–10، والملفات بتظهر في المعرض وتطبيقات الموسيقى

**وكمان**
- Shorts بلا نهاية حسب اهتماماتك، كلمات أغاني متزامنة، بحث، قنوات، قوائم تشغيل، تعليقات، شات البث المباشر
- واجهة بلغات متعددة، وضع Android TV بالريموت، و APK واحد لكل الأجهزة

### التحميل

ملفات APK بتتبني تلقائياً على GitHub Actions مع كل تعديل (**Actions ← Android CI ← آخر تشغيل ← Artifacts ← `YTXTube-debug-apk`**). فك الضغط وثبّت `app-debug.apk`، وممكن تحتاج تسمح بالتثبيت من مصادر غير معروفة.

---

## Screenshots / صور

<p align="center">
  <img src="docs/screenshots/01-home.webp" width="235" alt="Home" />
  <img src="docs/screenshots/02-shorts.webp" width="235" alt="Shorts" />
  <img src="docs/screenshots/03-player.webp" width="235" alt="Video player" />
  <img src="docs/screenshots/04-music-player.webp" width="235" alt="Music player" />
</p>
<p align="center">
  <img src="docs/screenshots/05-lyrics.webp" width="235" alt="Lyrics" />
  <img src="docs/screenshots/06-download-video.webp" width="235" alt="Download quality" />
  <img src="docs/screenshots/07-downloading.webp" width="235" alt="Downloading" />
  <img src="docs/screenshots/08-settings.webp" width="235" alt="Settings" />
</p>

## Disclaimer / إخلاء مسؤولية

Y.tube is an independent open-source project and is **not affiliated with, endorsed by, or sponsored by Google LLC or YouTube**. Content is streamed directly from YouTube for personal use. Provided "as is" under GPL-3.0 without warranty; users are responsible for complying with YouTube's Terms of Service and applicable copyright laws.

Y.tube مشروع مستقل مفتوح المصدر، و**مالوش أي علاقة بـ Google LLC أو YouTube** ولا معتمد منهم. المحتوى بيتشغّل مباشرة من سيرفرات يوتيوب للاستخدام الشخصي. المشروع مقدّم "كما هو" بترخيص GPL-3.0 من غير أي ضمان، والمستخدم مسؤول عن الالتزام بشروط استخدام يوتيوب وقوانين حقوق النشر.

## Credits / الشكر

- Based on [YTXTube](https://github.com/Koustubh12345/YTXTube) by Koustubh12345 (GPL-3.0), which also offers a [web app](https://koustubh12345.github.io/YTXTube/) and a [Telegram community](https://t.me/YTXTubeDiscussion).
- Some ideas and techniques adapted from [FlashTube](https://github.com/JustaThinker/FlashTube).
- Stream extraction by [NewPipeExtractor](https://github.com/TeamNewPipe/NewPipeExtractor); playback by [AndroidX Media3](https://github.com/androidx/media).

## License / الترخيص

GPL-3.0 — see [LICENSE](LICENSE). / ترخيص GPL-3.0 — راجع ملف [LICENSE](LICENSE).

<sub>Keywords: YouTube client Android, ad-free YouTube, YouTube background play, YouTube downloader, YouTube Music player, Shorts, audio downloader M4A Opus, Media3 ExoPlayer, NewPipe extractor, Android TV · كلمات بحث: تطبيق يوتيوب بدون إعلانات، تشغيل يوتيوب في الخلفية، تحميل فيديو من يوتيوب، تحميل صوت من يوتيوب، يوتيوب ميوزك، شورتس</sub>
