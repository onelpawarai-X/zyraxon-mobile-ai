# ZYRAXON — মোবাইল AI অ্যাসিস্ট্যান্ট

একটা সত্যিকারের বাংলা AI অ্যাসিস্ট্যান্ট — Android APK, Windows, macOS আর Linux — চার প্ল্যাটফর্মেই চলে।
ফেক নয়: রিয়েল স্ট্রিমিং, রিয়েল টুল কল, রিয়েল কমান্ড।

> This project was built and verified by an AI agent (OpenHands) on behalf of the user.

---

## ডাউনলোড

সবচেয়ে নতুন বিল্ড **[Releases](../../releases/latest)** পেজে আছে:

| প্ল্যাটফর্ম | ফাইল | সাইজ |
|---|---|---|
| Android 5.0+ | `zyraxon.apk` | 48 MB |
| Windows 10/11 | `ZYRAXON-Setup-1.0.0.exe` | 178 MB |
| macOS | `ZYRAXON-1.0.0-mac.zip` | 198 MB |
| Linux | `ZYRAXON-1.0.0.AppImage` | 206 MB |

---

## যা করতে পারে

### রিয়েল টুল কল
মডেল নিজে সিদ্ধান্ত নেয় কোন টুল লাগবে, সেটা চালায়, আউটপুট আবার তার কাছে ফেরত যায় — তারপর পরের ধাপে যায়।

| টুল | কাজ |
|---|---|
| `run_shell` | সত্যিকারের কমান্ড। Windows-এ PowerShell/cmd/pwsh, macOS-এ zsh/bash, Linux-এ bash, আর **ফোনে Android-এর নিজের `sh` স্যান্ডবক্স** |
| `open_app` | ফোনে/ডেস্কটপে অ্যাপ খোলা |
| `read_screen` | স্ক্রিনে কী আছে পড়া |
| `screen_shot` | স্ক্রিনশট নিয়ে মডেলকে দেখানো |
| `tap` / `swipe` / `type_text` | স্ক্রিনে সত্যিকারের টাচ/সোয়াইপ/টাইপ |
| `todo_write` | টাস্ক লিস্ট — কাজ শেষ না হওয়া পর্যন্ত থামে না |
| `publish_website` | সত্যিকারের ওয়েবসাইট বানিয়ে GitHub Pages-এ পাবলিশ |
| `read_url` | যেকোনো URL পড়া |
| `read_file` / `write_file` | ফাইল পড়া/লেখা |

### রিয়েল-টাইম স্ট্রিমিং
উত্তর আসে SSE (Server-Sent Events) দিয়ে — টোকেন আসতে আসতেই স্ক্রিনে, একবারে শেষে নয়।

### বাংলা ভয়েস — সব প্ল্যাটফর্মে
| প্ল্যাটফর্ম | STT (আপনার কথা) | TTS (তার কথা) |
|---|---|---|
| Android | ফোনের নিজের `SpeechRecognizer` | ফোনের নিজের `TextToSpeech` |
| Windows / macOS / Linux | পেজের ভিতরের Whisper (অফলাইন), ব্যাকআপ হিসেবে সার্ভারের Whisper | সিস্টেম `speechSynthesis` |

Electron-এ `webkitSpeechRecognition` থাকে কিন্তু পিছনের সার্ভিস নেই — তাই কোড সেটা ইচ্ছাকৃতভাবে বাদ দিয়ে Whisper-এ পাঠায়। এতে মাইক সত্যিই কাজ করে।

### চরিত্র
গার্লফ্রেন্ড, বয়ফ্রেন্ড আর সাধারণ বন্ধু — ইউজার বললেই সুইচ করে। লোকাল ডেটাবেসে সব মনে রাখে, ক্লাউড মুছে গেলেও।

### মডেল সিলেকশন
১১টা ZYRAXON মডেল, ইউজার নিজে বেছে নেয়।

---

## সোর্স থেকে চালানো

```bash
node server/server.mjs          # http://localhost:12000
```

### ডেস্কটপ অ্যাপ বিল্ড
```bash
cd desktop
npm install
npx electron-builder --linux AppImage   # বা --win nsis / --mac zip
```

### Android APK বিল্ড
Gradle লাগে না — শুধু Android SDK (aapt2 + javac + d8 + apksigner):
```bash
bash android/build.sh
```

---

## স্ট্রাকচার

```
server/     Node সার্ভার — SSE, টুল API, GitHub publish, Whisper STT
public/     WebView ক্লায়েন্ট — UI, ভয়েস, টুল, মেমরি
android/    নেটিভ Android — MainActivity (TTS/STT/shell/accessibility), AccessibilityBridge
desktop/    Electron শেল — Windows/macOS/Linux
test/       End-to-end টেস্ট
```

---

## টেস্ট

```bash
python3 test/e2e.py            # ফুল end-to-end
python3 test/e2e_mobile.py     # মোবাইল UI
python3 test/desktop_mic.py    # ডেস্কটপ মাইক
bash   test/_sweep.sh          # সব অ্যাকসেপ্টেন্স টেস্ট
```
