<div align="center">

# Y.tube

<img src="docs/icon.png" width="180" alt="Y.tube icon" />

### Free, open-source YouTube alternative for Android
### بديل يوتيوب مجاني ومفتوح المصدر لأندرويد

**Watch YouTube without ads · Background play · Fast video & audio downloads · Shorts · Live streams · YouTube Music · Android TV**

**يوتيوب بدون إعلانات · تشغيل في الخلفية · تحميل فيديو وصوت بسرعة · شورتس · بث مباشر · يوتيوب ميوزك · Android TV**

[![Android](https://img.shields.io/badge/Android-7.0%2B-3DDC84?style=flat-square&logo=android&logoColor=white)](#download)
[![Media3 ExoPlayer](https://img.shields.io/badge/Player-Media3%20ExoPlayer-E50914?style=flat-square)](#english)
[![Kotlin](https://img.shields.io/badge/Kotlin-Jetpack%20Compose-7F52FF?style=flat-square&logo=kotlin&logoColor=white)](#english)
[![License: GPL-3.0](https://img.shields.io/badge/License-GPLv3-blue.svg?style=flat-square)](LICENSE)

<a href="https://github.com/ali-zahafa/Y.tube/releases/latest/download/Y.tube.apk"><img src="https://img.shields.io/badge/Download-Y.tube.apk-E50914?style=for-the-badge&logo=android&logoColor=white" alt="Download Y.tube APK" /></a>
&nbsp;
<a href="https://github.com/ali-zahafa/Y.tube/archive/refs/heads/main.zip"><img src="https://img.shields.io/badge/Source-ZIP-555555?style=for-the-badge&logo=github&logoColor=white" alt="Download source ZIP" /></a>

[English](#english) · [العربية](#arabic)

</div>

---

<a id="english"></a>

## English

**Y.tube** is a free, open-source **YouTube alternative** and streaming app for Android. Watch YouTube videos, Shorts and live streams **without ads**, keep listening **in the background** with the screen off, and **download** videos and audio at full speed — no Google account required. It also works as a lightweight **YouTube Music** player and runs on phones, tablets and **Android TV**.

Built with Kotlin, Jetpack Compose and AndroidX Media3 (ExoPlayer).

### ✨ Features

#### ▶️ Playback
- **Ad-free YouTube** and YouTube Music streaming, up to **4K and HDR** on supported devices, with a fixed-quality lock
- **Background playback** with an ON/OFF setting — keep listening with the screen locked or while using other apps
- **"Play in background" button** on videos *and* Shorts: switches to audio only and goes straight to the home screen — no floating window
- **Media notification that stays while paused**, with a **✕ close button** that stops playback and exits the app
- **Lock-screen, Bluetooth and headset controls** (Media3 `MediaSession`)
- **Smart audio focus**: pauses for phone calls, lowers volume for navigation prompts, never resumes on its own after you pause; pauses when headphones or Bluetooth disconnect
- **Auto-recovery** after a dropped connection or an expired stream link, from the same position
- **Optimised player**: hardware-decoder-aware codec choice (AV1 / VP9 / H.264), decoder fallback, smooth adaptive quality, fewer rebuffering stalls
- **Live streams** with real quality switching between the qualities the stream offers
- Picture-in-Picture, playback speed, subtitles / captions, audio-track languages, video gestures, **SponsorBlock**
- Two mini-player styles and a Liquid Glass design

#### ⬇️ Downloads
- **YouTube video downloader**: up to 4K / HDR (MP4 or WebM), with exact (or clearly estimated) file sizes and real codec names
- **YouTube audio downloader**: choose the audio quality — the original **M4A (AAC)** and **WebM (Opus)** streams, "Original quality" first, with bitrate and size — saved without conversion (no fake MP3)
- **Fast downloads** — uses your full connection speed instead of YouTube's throttled speed
- Real pause / resume, retry, verified files, Android 7–10 storage support, downloads appear in your gallery and music apps

#### 🎬 Shorts, channels & search
- Endless **Shorts** feed; open a Short from a channel and swipe through **that channel's Shorts**
- Screen stays on while a Short is playing
- **Channels**: Videos, Shorts, Live and posts — scroll all the way to the channel's oldest uploads
- **Search filters**: upload date (last 24 hours, last week, last month, or a custom from/to range) and type (Videos, Shorts, Live)
- Synced lyrics, playlists, comments, live chat, subscriptions with sign-in

#### 📱 Everything else
- Multilingual interface (Arabic, English and more)
- **Android TV** mode with remote (D-pad) navigation
- One universal APK for phones, tablets and TV

<a id="download"></a>

### 📥 Download

- **[⬇️ Download Y.tube.apk](https://github.com/ali-zahafa/Y.tube/releases/latest/download/Y.tube.apk)** — latest release, Android 7.0+, one APK for phones, tablets and Android TV
- **[All releases](https://github.com/ali-zahafa/Y.tube/releases)** · **[Source code (ZIP)](https://github.com/ali-zahafa/Y.tube/archive/refs/heads/main.zip)**

Open the APK on your phone and allow installs from unknown sources if asked. The app can also update itself: **Settings → Check for updates**.

### 🛠️ Build from source

```bash
./gradlew :app:testDebugUnitTest
./gradlew :app:assembleDebug
```

The APK is written to `app/build/outputs/apk/debug/app-debug.apk` (JDK 21 and the Android SDK are required).

---

<a id="arabic"></a>

<div dir="rtl">

## العربية

**Y.tube** تطبيق مجاني ومفتوح المصدر، **بديل لتطبيق يوتيوب** على أندرويد. اتفرّج على فيديوهات يوتيوب والشورتس والبث المباشر **من غير إعلانات**، وكمّل سماع **في الخلفية** والشاشة مقفولة، و**حمّل** الفيديوهات والصوت بأقصى سرعة — من غير ما تحتاج حساب جوجل. وكمان بيشتغل كمشغّل **يوتيوب ميوزك** خفيف، وعلى الموبايل والتابلت و**Android TV**.

مبني بـ Kotlin و Jetpack Compose ومشغّل AndroidX Media3 (ExoPlayer).

### ✨ المميزات

#### ▶️ التشغيل
- **يوتيوب بدون إعلانات** ويوتيوب ميوزك، بجودة لحد **4K و HDR** على الأجهزة اللي بتدعمها، مع إمكانية تثبيت الجودة
- **تشغيل في الخلفية** مع زر تشغيل/إيقاف في الإعدادات — كمّل السماع والشاشة مقفولة أو وانت بتستخدم تطبيقات تانية
- **زر "تشغيل في الخلفية"** في الفيديوهات **والشورتس**: يحوّل للصوت بس ويخرجك على الشاشة الرئيسية فوراً — من غير شاشة عائمة
- **إشعار التشغيل بيفضل موجود وقت الإيقاف المؤقت**، وفيه **زر ✕** يوقف التشغيل ويقفل التطبيق كله
- **التحكم من شاشة القفل وسماعات البلوتوث والسماعات السلكية**
- **تركيز صوتي ذكي**: بيوقف وقت المكالمات، بيوطي الصوت مع تعليمات الخرائط، ومش بيرجع يشغل لوحده لو انت اللي وقفته؛ وبيوقف لما السماعة أو البلوتوث يفصل
- **استرجاع تلقائي** بعد انقطاع النت أو انتهاء رابط البث، من نفس المكان
- **مشغّل محسّن**: اختيار الكودك حسب دعم الجهاز (AV1 / VP9 / H.264)، بديل تلقائي لو الكودك فشل، جودة متغيرة بسلاسة، وتقطيع أقل
- **بث مباشر** مع تغيير حقيقي للجودة من الجودات المتاحة في البث
- صورة داخل صورة (PiP)، سرعة التشغيل، الترجمة، لغات الصوت، إيماءات الفيديو، **SponsorBlock**
- شكلين للمشغّل المصغّر وتصميم Liquid Glass

#### ⬇️ التحميل
- **تحميل فيديو من يوتيوب**: لحد 4K / HDR (MP4 أو WebM)، بحجم دقيق (أو تقديري واضح) وأسماء كودك حقيقية
- **تحميل صوت من يوتيوب**: اختار جودة الصوت — ملفات **M4A (AAC)** و **WebM (Opus)** الأصلية، "الجودة الأصلية" أولاً، مع معدل البت والحجم — من غير أي تحويل (مفيش MP3 مزيف)
- **تحميل سريع** — بيستخدم سرعة النت كلها بدل السرعة المحدودة بتاعة يوتيوب
- إيقاف مؤقت واستكمال حقيقي، إعادة محاولة، التحقق من الملف، دعم التخزين على أندرويد 7–10، والتحميلات بتظهر في المعرض وتطبيقات الموسيقى

#### 🎬 الشورتس والقنوات والبحث
- **شورتس** بلا نهاية؛ ولو فتحت شورت من قناة، تقدر تقلّب بين **شورتس القناة دي**
- الشاشة بتفضل منورة طول ما الشورت شغال
- **القنوات**: الفيديوهات والشورتس والبث المباشر والمنشورات — وتقدر تنزل لحد أقدم فيديو في القناة
- **فلاتر البحث**: تاريخ الرفع (آخر 24 ساعة، آخر أسبوع، آخر شهر، أو فترة تختارها من تاريخ لتاريخ) والنوع (فيديوهات، شورتس، بث مباشر)
- كلمات أغاني متزامنة، قوائم تشغيل، تعليقات، شات البث المباشر، والاشتراكات بعد تسجيل الدخول

#### 📱 وكمان
- واجهة بلغات متعددة (عربي وإنجليزي وغيرهم)
- وضع **Android TV** بالريموت
- ملف APK واحد للموبايل والتابلت والتلفزيون

### 📥 التحميل

- **[⬇️ تحميل Y.tube.apk](https://github.com/ali-zahafa/Y.tube/releases/latest/download/Y.tube.apk)** — آخر إصدار، أندرويد 7.0 أو أحدث، ملف واحد للموبايل والتابلت والتلفزيون
- **[كل الإصدارات](https://github.com/ali-zahafa/Y.tube/releases)** · **[الكود المصدري (ZIP)](https://github.com/ali-zahafa/Y.tube/archive/refs/heads/main.zip)**

افتح ملف الـ APK على موبايلك، ولو طلب منك اسمح بالتثبيت من مصادر غير معروفة. والتطبيق بيقدر يحدّث نفسه: **الإعدادات ← Check for updates**.

</div>

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

Y.tube is an independent open-source project and is **not affiliated with, endorsed by, or sponsored by Google LLC or YouTube**. "YouTube" is a trademark of Google LLC and is used here only to describe what the app works with. Content is streamed directly from YouTube for personal use. Provided "as is" under GPL-3.0 without warranty; users are responsible for complying with YouTube's Terms of Service and applicable copyright laws.

<div dir="rtl">

Y.tube مشروع مستقل مفتوح المصدر، و**مالوش أي علاقة بشركة Google أو YouTube** ولا معتمد منهم. اسم "YouTube" علامة تجارية لشركة Google، ومذكور هنا بس لتوضيح الخدمة اللي التطبيق بيشتغل معاها. المحتوى بيتشغّل مباشرة من يوتيوب للاستخدام الشخصي، والمشروع مقدّم "كما هو" بترخيص GPL-3.0 من غير أي ضمان، والمستخدم مسؤول عن الالتزام بشروط استخدام يوتيوب وقوانين حقوق النشر.

</div>

## Credits / الشكر

- Based on the open-source YTXTube project by Koustubh12345 (GPL-3.0). / مبني على مشروع YTXTube مفتوح المصدر (ترخيص GPL-3.0).
- Some ideas and techniques adapted from FlashTube.
- Stream extraction by [NewPipeExtractor](https://github.com/TeamNewPipe/NewPipeExtractor); playback by [AndroidX Media3](https://github.com/androidx/media).

## License / الترخيص

GPL-3.0 — see [LICENSE](LICENSE). / ترخيص GPL-3.0 — راجع ملف [LICENSE](LICENSE).

---

### 🔍 Keywords / كلمات البحث

**English:** YouTube alternative, YouTube app alternative, YouTube without ads, ad-free YouTube, YouTube ad blocker for Android, YouTube background play, play YouTube in background, YouTube with screen off, YouTube Premium alternative, free YouTube Premium features, YouTube downloader, YouTube video downloader, download YouTube videos, YouTube to MP4, YouTube audio downloader, YouTube to M4A, download YouTube music, YouTube Music player, YouTube Music alternative, YouTube Shorts player, YouTube Shorts downloader, YouTube live streams, YouTube Vanced alternative, ReVanced alternative, NewPipe alternative, YouTube client Android, open-source YouTube app, YouTube for Android TV, YouTube PiP, picture in picture YouTube, SponsorBlock, video streaming app, streaming, Android streaming app

<div dir="rtl">

**عربي:** بديل يوتيوب، بديل تطبيق يوتيوب، يوتيوب بدون إعلانات، يوتيوب بلا إعلانات، مانع إعلانات يوتيوب، تشغيل يوتيوب في الخلفية، يوتيوب والشاشة مقفولة، بديل يوتيوب بريميوم، مميزات يوتيوب بريميوم مجاناً، تحميل فيديو من يوتيوب، برنامج تحميل من يوتيوب، تنزيل فيديوهات يوتيوب، تحويل يوتيوب إلى MP4، تحميل صوت من يوتيوب، تحميل أغاني من يوتيوب، يوتيوب ميوزك، بديل يوتيوب ميوزك، تحميل شورتس يوتيوب، شورتس، بث مباشر يوتيوب، مشاهدة مباريات بث مباشر على يوتيوب، بديل يوتيوب فانسد، يوتيوب للأندرويد، يوتيوب للتلفزيون، يوتيوب Android TV، صورة داخل صورة يوتيوب، تطبيق ستريمنج، تطبيق بث، ستريمنج

</div>
