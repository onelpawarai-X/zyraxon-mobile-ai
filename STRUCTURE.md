# ZYRAXON — সম্পূর্ণ প্রকল্প কাঠামো

**সংস্করণ:** 6.25.0 (versionCode 62) · **অ্যাপ আইডি:** `com.zyraxon.assistant` · **রুট প্রকল্প:** `ZYRAXON`

একটি Android অ্যাপ — ফোনের ভিতরেই একটি সম্পূর্ণ Linux সিস্টেম (proot), একটি AI অ্যাসিস্ট্যান্ট (লোকাল + ক্লাউড), ভয়েস, MCP টুল, অফিস ডকুমেন্ট, আর Shizuku দিয়ে সিস্টেম-লেভেল নিয়ন্ত্রণ।

---

## ১. শীর্ষ-স্তরের কাঠামো

```
zx-ready/
├── app/                    ← একমাত্র Gradle মডিউল (পুরো অ্যাপ)
├── gradle/                 ← wrapper + version catalog
├── .github/workflows/      ← CI/CD (টেস্ট + রিলিজ)
├── scripts/                ← APK ABI যাচাই
├── tools/                  ← অনুবাদ ও স্ক্রিনশট টুল
├── build.gradle.kts        ← রুট বিল্ড (মডিউল তালিকা)
├── settings.gradle.kts     ← রুট নাম "ZYRAXON", include(":app")
├── gradle.properties       ← JVM মেমরি ও AndroidX সেটিং
├── AGENTS.md               ← এজেন্ট-নোট (নিয়ম ও সিদ্ধান্ত)
├── STRUCTURE.md            ← এই ফাইল
├── README.md               ← মূল ভূমিকা
├── README.{bn,hi,ar,zh}.md ← অনূদিত ভূমিকা
├── LICENSE                 ← ZYRAXON Sovereign License, X Edition (ZSL-X)
├── NOTICE                  ← মালিকানা ও থার্ড-পার্টি স্বীকৃতি
├── SECURITY.md             ← নিরাপত্তা নীতি
├── firebase.json / .rules  ← Firebase হোস্টিং ও Firestore নিয়ম
├── firebase-blueprint.json ← ডেটা মডেল নকশা
├── metadata.json           ← অ্যাপ মেটাডেটা
├── debug.keystore.base64   ← ডিবাগ সাইনিং কী (বেস৬৪)
├── .env.example            ← পরিবেশ চলকের নমুনা
└── .gitignore
```

---

## ২. `app/` মডিউল — ভিতরের কাঠামো

```
app/
├── build.gradle.kts        ← SDK, ABI ফ্লেভার, সাইনিং, নির্ভরতা
├── src/main/
│   ├── AndroidManifest.xml ← অনুমতি ও কম্পোনেন্ট
│   ├── java/ai/zyraxon/assistant/   ← ১১৪টি Kotlin সোর্স
│   ├── assets/             ← i18n, প্রম্পট, থিম, Shizuku, পেলোড
│   ├── jniLibs/            ← নেটিভ লাইব্রেরি (proot, busybox)
│   └── res/                ← আইকন, ফন্ট, XML
├── src/test/               ← ৫৭টি ইউনিট টেস্ট
└── src/androidTest/        ← ২টি ইনস্ট্রুমেন্টেড টেস্ট
```

### বিল্ড সেটিং (`app/build.gradle.kts`)

| সেটিং | মান |
|---|---|
| `namespace` / `applicationId` | `com.zyraxon.assistant` |
| `compileSdk` | 36 (minorApiLevel 1) |
| `minSdk` | 21 |
| `targetSdk` | 28 |
| `versionCode` / `versionName` | 62 / `6.25.0` |

**ABI ফ্লেভার (৩টি APK):**

| ফ্লেভার | ABI |
|---|---|
| `arm64` | `arm64-v8a` |
| `arm32` | `armeabi-v7a` |
| `universal` | `arm64-v8a` + `armeabi-v7a` |

---

## ৩. Kotlin সোর্স — প্যাকেজ ম্যাপ (১১৪টি ফাইল)

| প্যাকেজ | ফাইল | কাজ |
|---|---|---|
| `assistant` (রুট) | ২ | `MainActivity`, `OverlayChatService` |
| `ai` | ১৮ | ইঞ্জিন, টুল, ভিশন, SSE, প্রোভাইডার, অন-ডিভাইস মডেল |
| `voice` | ১৫ | STT, TTS, ওয়েক-ওয়ার্ড, লাইভ ভয়েস |
| `shell` | ৯ | Linux/proot, Shizuku, HTTP প্রক্সি, Git, শেল |
| `db` | ৮ | Room ডেটাবেস, এনটিটি, DAO, রিপোজিটরি |
| `mcp` | ৫ | MCP ক্লায়েন্ট, ম্যানেজার, OAuth |
| `cloud` | ৪ | ক্লাউড ব্রিজ, ক্লায়েন্ট, মডেল, স্টোর |
| `office` | ৩ | Word/Excel/PPT পড়া-লেখা ও টেমপ্লেট |
| `tools` | ৩ | Git, মিডিয়া, প্রিভিউ ব্রিজ |
| `design` | ২ | HTML ডিজাইন রেন্ডার |
| `lock` | ২ | ডিভাইস অ্যাডমিন ও লক |
| `update` | ২ | ইন-অ্যাপ আপডেট, ইনস্টল রিসিভার |
| `viewer` | ২ | Word/Excel/PDF/Markdown ভিউয়ার |
| `brand` | ১ | রঙের প্যালেট |
| `chat` | ১ | চ্যাট সেশন |
| `core` | ১ | JSON ইউটিলিটি |
| `i18n` | ১ | অনুবাদ লোডার |
| `model` | ১ | ডেটা মডেল |
| `permission` | ১ | নিজে-ইনস্টল |
| `present` | ১ | প্রেজেন্টেশন |
| `ui` | ১ | মূল Compose অ্যাপ |
| `viewmodel` | ১ | `ZyraxonViewModel` |

### `ui/` — ইন্টারফেস স্তর

| উপ-প্যাকেজ | ফাইল | কাজ |
|---|---|---|
| `ui/components` | ১৮ | চ্যাট, টার্মিনাল, বুট স্ক্রিন, এফেক্ট, MCP স্ক্রিন |
| `ui/shell` | ৮ | সাইডবার, কম্পোজার, কোড ওয়ার্কস্পেস, মেসেজ ভিউ |
| `ui/icons` | ২ | অ্যাপ আইকন সেট |
| `ui/theme` | ২ | থিম ও গ্লাস এফেক্ট |
| `ui` (রুট) | ১ | মূল Compose অ্যাপ |

### `ai/` — বুদ্ধিমত্তা স্তর

| ফাইল | কাজ |
|---|---|
| `ZyraxonAiEngine.kt` | মূল ইঞ্জিন — টার্ন চালায় |
| `ToolRegistry.kt` | নেটিভ টুল সেট ও ডিসপ্যাচ |
| `ProviderCatalog.kt` | প্রোভাইডার এন্ডপয়েন্ট (Groq, Gemini, ZYRAXON…) |
| `OpenAiSse.kt` | SSE স্ট্রিমিং পার্সার |
| `LocalAgent.kt` | ডিভাইসে চলা এজেন্ট লুপ |
| `OnDeviceModel.kt` | অন-ডিভাইস মডেল লোডিং |
| `VisionSupport.kt` / `ScreenOcr.kt` | ছবি ও স্ক্রিন পড়া |
| `CameraActivity.kt` / `CameraCapture.kt` | ক্যামেরা |
| `ContextWindow.kt` / `ContinuationPolicy.kt` | প্রসঙ্গ ও ধারাবাহিকতা |

### `shell/` — Linux ও সিস্টেম স্তর

| ফাইল | কাজ |
|---|---|
| `LinuxRuntime.kt` | proot দিয়ে Linux চালানো, পেলোড আনপ্যাক |
| `ShellRuntime.kt` | busybox শেল |
| `SystemShell.kt` | Shizuku (uid 2000/root) |
| `ShizukuBundle.kt` | বান্ডল করা Shizuku ইনস্টল |
| `HttpProxy.kt` | লোকাল প্রক্সি |
| `GitRuntime.kt` | Git অপারেশন |
| `AnsiText.kt` | টার্মিনাল রঙ |
| `BackgroundCommandService.kt` | পটভূমি কমান্ড |
| `ScheduledCommandReceiver.kt` | সময়ভিত্তিক কমান্ড |

---

## ৪. `assets/` — রানটাইম সম্পদ (১৪৮ ফাইল)

| উপ-বিভাগ | ফাইল | কাজ |
|---|---|---|
| `i18n/` | ১২০ | ১২০টি ভাষার অনুবাদ বান্ডল |
| `assets/` | ১৯ | পটভূমি ছবি/ভিডিও, আইকন, CREDITS |
| `knowledge/` | ৫ | ৪টি মোড-প্রম্পট + লিগ্যাসি md |
| `shizuku/` | ২ | Shizuku APK + Apache লাইসেন্স |
| `aura/` | ১ | `aura.html` (WebView) |
| `web/` | ১ | `zx-picker.js` |

**প্রম্পট ফাইল (`knowledge/`):**

| ফাইল | মোড |
|---|---|
| `prompt-build.txt` | বিল্ড |
| `prompt-agent.txt` | এজেন্ট |
| `prompt-live.txt` | লাইভ ভয়েস |
| `zyraxon-system-prompt.md` | লিগ্যাসি (টেস্টের জন্য) |

---

## ৫. Linux পেলোড — তিন আর্কিটেকচার

| ফ্লেভার | পথ | deb | bootstrap |
|---|---|---|---|
| arm64 | `payload64/assets/linux/aarch64/` | ৬৭ | ৩২ MB |
| arm32 | `payload32/assets/linux/arm/` | ৬৭ | ২৯ MB |
| x86_64 | `payloadx64/assets/linux/x86_64/` | ৬৭ | ৩২ MB |

প্রতিটি পেলোডে: `bootstrap.zip` (রুট ফাইলসিস্টেম) + `debs/` (৬৭টি প্যাকেজ)।

**ব্র্যান্ডিং নিয়ম — সব ZYRAXON:**

| জিনিস | অবস্থা |
|---|---|
| সব path, RUNPATH, স্ক্রিপ্ট, man page | ZYRAXON |
| mirror + repo URL (`etc/apt/sources.list`) | `packages-cf.zyrxon.dev/apt/zyrxon-main` |
| `dpkg` মেটাডেটার হ্যান্ডেল | `zyrxon.dev` |
| copyright + license ফাইল | উপরে ZYRAXON হেডার, নিচে অটুট |
| **`.dynstr` symbol নাম** | অপরিবর্তিত (`.gnu.hash` হ্যাশড — বদলালে লোড হয় না) |
| **`.gpg` signing key** | অপরিবর্তিত (সিগনেচার বাইট) |

শেষ দুটি ব্যবহারকারীর কাছে অদৃশ্য — শুধু লাইব্রেরি লোড ও সিগনেচার সচল রাখতে অটুট।

---

## ৬. `jniLibs/` — নেটিভ লাইব্রেরি

| ABI | লাইব্রেরি |
|---|---|
| `arm64-v8a` | android-shmem, busybox, proot-loader, proot-loader32, proot, talloc |
| `armeabi-v7a` | android-shmem, busybox, proot-loader, proot, talloc |
| `x86_64` | android-shmem, busybox, proot-loader, proot-loader32, proot, talloc |
| `x86` | busybox |

---

## ৭. `res/` — সম্পদ (২৫ ফাইল)

| ধরন | ফাইল |
|---|---|
| আইকন | `mipmap-*/ic_launcher*.png` + `mipmap-anydpi-v26/*.xml` |
| ব্যাকগ্রাউন্ড | `drawable/ic_launcher_*.xml`, `img_blood_cyber_bg.jpg` |
| ফন্ট | `jetbrains_mono_regular.ttf`, `noto_sans_bengali.ttf` |
| মান | `values/colors.xml`, `strings.xml`, `themes.xml` |
| XML | `accessibility_config`, `backup_rules`, `data_extraction_rules`, `device_admin`, `file_paths` |

---

## ৮. `AndroidManifest.xml` — কম্পোনেন্ট

| ধরন | ক্লাস |
|---|---|
| Activity | `MainActivity`, `ai.CameraActivity` |
| Service | `KeepAliveService`, `OverlayChatService`, `shell.BackgroundCommandService`, `AccessibilityBridge` |
| Receiver | `shell.ScheduledCommandReceiver`, `update.UpdateInstallReceiver` |
| Admin | `lock.ZyraxonDeviceAdmin` |

**অনুমতি (২২টি):** ইন্টারনেট, ক্যামেরা, মাইক, লোকেশন, SMS পড়া, স্টোরেজ/মিডিয়া, ফোরগ্রাউন্ড সার্ভিস (ডেটা-সিঙ্ক + মাইক), ওভারলে, ইনস্টল প্যাকেজ, নোটিফিকেশন, ওয়েক-লক, ভাইব্রেট, নেটওয়ার্ক অবস্থা।

---

## ৯. CI/CD — `.github/workflows/`

| ওয়ার্কফ্লো | ট্রিগার | কাজ |
|---|---|---|
| `TEST-CI.yml` | ম্যানুয়াল | ইউনিট টেস্ট চালায় |
| `release.yml` | ট্যাগ push | তিনটি signed APK বিল্ড → রিলিজে প্রকাশ |

**`release.yml` ধাপ:** Checkout → JDK 21 → Android SDK → Gradle → কী ডিকোড → ভার্সন পড়া → APK বিল্ড (arm64+arm32+universal) → আর্টিফ্যাক্ট সংগ্রহ → সিগনেচার যাচাই → APK বিষয়বস্তু চেক → ট্যাগ নির্ধারণ → রিলিজ প্রকাশ → সারসংক্ষেপ।

---

## ১০. `scripts/` ও `tools/`

| পথ | কাজ |
|---|---|
| `scripts/verify-apk-abi.sh` | APK-এর ABI যাচাই |
| `tools/kotlin_strings.py` | Kotlin স্ট্রিং নিষ্কাশন |
| `tools/localize_ui.py` | UI অনুবাদ |
| `tools/translate_*.py` | অনুবাদ পাইপলাইন |
| `tools/read_screenshot.py` | স্ক্রিনশট পড়া |
| `tools/*.json` | ভাষা ও কী তালিকা |

---

## ১১. ডেটা প্রবাহ — এক নজরে

```
ব্যবহারকারী (UI)
      │
      ▼
ZyraxonViewModel ──► ZyraxonAiEngine ──► ProviderCatalog (Groq/Gemini/ZYRAXON)
      │                    │
      │                    ├──► ToolRegistry (নেটিভ টুল)
      │                    ├──► McpManager (MCP টুল)
      │                    └──► CloudClient (গেটওয়ে)
      │
      ├──► LinuxRuntime (proot Linux) ──► ShellRuntime / SystemShell (Shizuku)
      ├──► ZyraxonRepository ──► Room DB (SQLite)
      └──► voice/* (STT + TTS)
```

---

## ১২. সংস্করণ ও রিলিজ

| | |
|---|---|
| বর্তমান | **6.25.0** / versionCode 62 |
| রিলিজ সম্পদ | `ZYRAXON-v6.25.0-{arm64,arm32,universal}.apk` + `SHA256SUMS` |
| প্রকাশ পথ | GitHub Releases (ট্যাগ চাপলেই স্বয়ংক্রিয়) |
| ইন-অ্যাপ আপডেট | `Updater.kt` — রিলিজ থেকে APK নামিয়ে ইনস্টল |

---

*এই কাঠামো-প্রোফাইল ZYRAXON 6.25.0 অনুযায়ী হালনাগাদ।*
