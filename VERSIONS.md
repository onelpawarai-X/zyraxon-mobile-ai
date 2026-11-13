# ZYRAXON ভার্সন-লেজার (VERSIONS.md)

এই ফাইলটাই ভার্সনের একমাত্র সত্য। নতুন ভার্সন বানানোর আগে **এই ফাইলটি পড়ুন**,
তারপর `app/build.gradle.kts`-এ লিখুন। এই ফাইলে না থাকা কোনো ভার্সন কোথাও ব্যবহার
করবেন না।

---

## ১. নিয়ম — পরবর্তী ভার্সন কীভাবে ঠিক হবে

**প্রতিটি রিলিজের আগে এই চারটি ধাপ, এই ক্রমে:**

| ধাপ | কাজ |
| --- | --- |
| ১ | এই ফাইলে **সর্বশেষ ✅ প্রকাশিত** ভার্সনটি দেখুন (নিচের টেবিলে) |
| ২ | নিচের নিয়মে পরবর্তী নম্বর ঠিক করুন |
| ৩ | `app/build.gradle.kts`-এ `versionName` ও `versionCode` বসান |
| ৪ | এই ফাইলে নতুন ভার্সনের একটি নতুন সারি ও নতুন সেকশন যোগ করুন |

### কোন অংশ বাড়বে

`versionName` তিনটি সংখ্যা: **`MAJOR.MINOR.PATCH`** (যেমন `6.25.0`)।

| কোনটি বাড়বে | কখন | উদাহরণ |
| --- | --- | --- |
| **MAJOR** | এমন পরিবর্তন যা পুরোনো সেটিংস/ডেটা ভেঙে দেয়, বা সংরক্ষিত ফরম্যাট বদলায় | `6.x.x` → `7.0.0` |
| **MINOR** | নতুন ক্ষমতা, নতুন পর্দা, নতুন টুল — পুরোনো সব অক্ষত থাকে | `6.24.11` → `6.25.0` |
| **PATCH** | শুধু বাগ-ঠিক, অনুবাদ, ছোট সমন্বয় — নতুন কিছু নেই | `6.25.0` → `6.25.1` |

যেটি বাড়বে সেটি **এক করে বাড়বে**; তার ডান দিকের সব সংখ্যা **শূন্য** হবে
(`6.24.11` → MINOR বাড়লে `6.25.0`, PATCH বাড়লে `6.24.12`)।

### versionCode — কঠোর নিয়ম

`versionCode` একটি পূর্ণসংখ্যা, আর **এটি কখনো কমবে না**। Android এই সংখ্যাটাই
তুলনা করে; কম হলে নতুন APK পুরোনোটির উপরে বসবেই না।

```
নতুন versionCode = সর্বশেষ প্রকাশিত versionCode + 1
```

`versionName` আর `versionCode` সবসময় একসাথে বাড়বে। একটি বাড়িয়ে অন্যটি ভুলে
যাওয়া এই প্রকল্পে আগে হয়েছে, আর ফল হয়েছিল এমন একটি APK যা ইনস্টলই হয় না।

### নিষিদ্ধ

- **একই ভার্সন দুইবার** — কোনো ভার্সন প্রকাশের পর সেটি আবার ব্যবহার করা যাবে না।
- **ট্যাগে প্রত্যয়** — `v6.25.0-test1` নয়; সব রিলিজ ট্যাগ শুধু `vX.Y.Z`।
- **ওয়ার্কিং ট্রি থেকে ভার্সন বানানো** — ভার্সন আসবে এই ফাইল থেকে, কল্পনা থেকে নয়।
- **`versionName` ছাড়া ট্যাগ** — ট্যাগ আর `versionName` হুবহু এক হবে।
- **অ্যাপের ভার্সন আর নেটিভ ইঞ্জিনের ভার্সন মেশানো** — দুটি আলাদা জিনিস, নিচে দেখুন।

---

## ২. দুইটি আলাদা ভার্সন — গুলিয়ে ফেলবেন না

| নাম | কোথায় থাকে | কী বোঝায় |
| --- | --- | --- |
| **অ্যাপের ভার্সন** | `app/build.gradle.kts` → `versionName` | পুরো অ্যাপ্লিকেশনের সংস্করণ; রিলিজ ট্যাগ, APK-এর নাম, ইন-অ্যাপ আপডেট — সব এটির উপর |
| **নেটিভ ইঞ্জিনের ভার্সন** | `Sheets.kt` → `ZYRAXON Native Engine vX.Y.Z` লেখাটি | শুধু ফোনের নিজস্ব ভয়েস ইঞ্জিনের (STT + TTS) সংস্করণ |

নেটিভ ইঞ্জিন অ্যাপের ভিতরের একটি অংশ। এটি বদলালে **শুধু এই লেবেলটি** বাড়বে,
`versionName` নয় — অ্যাপের ভার্সন তখন MINOR বা PATCH হিসেবে বাড়বে এবং এই ফাইলে
লিখে দেওয়া হবে যে ইঞ্জিনও বদলেছে।

**বর্তমান নেটিভ ইঞ্জিন: `v3.1.0` (Zero Lag)।**

---

## ৩. ভার্সন-টেবিল (সব ভার্সন, এক জায়গায়)

অবস্থা: **✅ প্রকাশিত** · **🟡 প্রস্তুত (এখনো প্রকাশ হয়নি)** · **🔜 পরিকল্পিত**

| ভার্সন | versionCode | তারিখ | অবস্থা | সংক্ষেপে |
| --- | --- | --- | --- | --- |
| `6.21.0` | — | — | ✅ প্রকাশিত | কম্পোজার পুনর্গঠন; মাইক কন্ট্রোল ফিরে আসে (`ComposerMicButton`) |
| `6.23.2` | — | — | ✅ প্রকাশিত | (ইতিহাসে রেকর্ডকৃত) |
| `6.23.7` | — | — | ✅ প্রকাশিত | (ইতিহাসে রেকর্ডকৃত) |
| `6.24.2` | — | — | ✅ প্রকাশিত | (ইতিহাসে রেকর্ডকৃত) |
| `6.24.3` | — | — | ✅ প্রকাশিত | (ইতিহাসে রেকর্ডকৃত) |
| `6.24.6` | — | — | ✅ প্রকাশিত | `pickApk` ABI বাছাই ঠিক — ৬৪-বিট ফোন আর ৩২-বিট APK পায় না |
| `6.24.7` | — | — | ✅ প্রকাশিত | (ইতিহাসে রেকর্ডকৃত) |
| `6.24.8` | — | — | ✅ প্রকাশিত | (ইতিহাসে রেকর্ডকৃত) |
| `6.24.9` | — | — | ✅ প্রকাশিত | (ইতিহাসে রেকর্ডকৃত) |
| `6.24.10` | `60` | 2026-11-13 | ✅ প্রকাশিত | রিলিজ হার্ডেনিং কী কী লাগে তা লিখে রাখা |
| `6.24.11` | `61` | 2026-11-13 | ✅ প্রকাশিত | দুই মোড (Build ও Agent), সেটিংস রিভার্ক, গ্যাপলেস স্পিচ |
| `6.25.0` | `62` | 2026-11-13 | 🟡 প্রস্তুত | নিজস্ব অ্যাপ লক (পাসওয়ার্ড + প্যাটার্ন), সব সেকশনে আইকন, নেটিভ ইঞ্জিন v3.1.0 (Zero Lag) |
| `6.25.1` | `63` | — | 🔜 পরিকল্পিত | পরবর্তী বাগ-ঠিকের জন্য সংরক্ষিত |
| `6.26.0` | `64` | — | 🔜 পরিকল্পিত | পরবর্তী নতুন ক্ষমতার জন্য সংরক্ষিত |

> **পরবর্তী ভার্সন কত?** — সর্বশেষ ✅ সারির নিচের 🟡 সারিটি (এখন `6.25.0`)।
> `6.25.0` প্রকাশ হয়ে গেলে 🟡 → ✅ হবে, আর পরবর্তী কাজের ধরন বুঝে
> `6.25.1` (বাগ-ঠিক) বা `6.26.0` (নতুন ক্ষমতা) বেছে নিতে হবে।

---

## ৪. প্রতিটি ভার্সনের বিস্তারিত

নিচের প্রতিটি সেকশনের শিরোনাম হুবহু `## vX.Y.Z` আকারে রাখা বাধ্যতামূলক — রিলিজ
ওয়ার্কফ্লো (`scripts/release-notes.sh`) এই শিরোনাম ধরে রিলিজের বর্ণনা তৈরি করে।

## v6.25.0

**তারিখ:** 2026-11-13 · **versionCode:** 62 · **নেটিভ ইঞ্জিন:** v3.1.0

### রিলিজ বর্ণনা

**The lock is now the app's own, and it no longer drags ZYRAXON back to the front.**

Two defects were really one. The cover went up in `onStop()` — which fires when an
agent opens a browser, a dialler, or any other app — and it asked for the phone's
device credential, which is a *system* screen that must be brought to the
foreground to be shown. So every time the agent left the app to do something, the
cover rose behind it and Android pulled ZYRAXON back on top of whatever the agent
had just opened.

Both are fixed:

- The cover now goes up in `onStart()`, on the way *in*. A trip out never raises
  it; a return raises it exactly once, over the app's own screen.
- The lock asks for a secret this app owns — a **password** or a **pattern** —
  drawn on a screen this app draws. No system screen, so nothing ever steals the
  foreground.

The secret is stored as a **PBKDF2-HMAC-SHA256** digest over a per-install random
salt, compared in constant time. The secret itself is never written down, so a
copy of the preferences file gives an attacker nothing to read.

The settings pane gets a switch, a password/pattern chooser, and a way to remove
the secret. A lock with no secret cannot be armed — a cover with no answer would
only seal the owner out.

**Every settings heading now carries its own icon.** `HorrorSectionTitle` takes
one, and every pane passes the icon for its subject, so a heading says what it is
before the words are read.

**The native engine's fixed dead air is gone (v3.1.0, "Zero Lag").** The
recognizer only closes a phrase after its silence window, and that window is a
fixed slice of nothing on every single turn. A partial that holds still is now
submitted without waiting for it, and the duplicate final that follows is dropped.
The window itself is 1.6s rather than 2.5s, and the turn tail and restart floor
are shorter too.

### এই ভার্সনে যা বদলেছে

| ক্ষেত্র | পরিবর্তন |
| --- | --- |
| অ্যাপ লক | ফোনের ক্রেডেনশিয়াল বাদ; নিজস্ব পাসওয়ার্ড (৪–৮ সংখ্যা) ও ৩×৩ প্যাটার্ন যোগ |
| লকের সময় | `onStop()` → `onStart()` — এজেন্ট অন্য অ্যাপ খুললে আর সামনে আসে না |
| নিরাপত্তা | `AppLockStore.kt` — PBKDF2-HMAC-SHA256, প্রতি-ইনস্টলে আলাদা সল্ট, constant-time তুলনা |
| সেটিংস | লক প্যান নতুন; পাসওয়ার্ড/প্যাটার্ন নির্বাচন ও লক সরানোর পথ |
| সেকশন টাইটেল | `HorrorSectionTitle`-এ আইকন প্যারামিটার; ২০টি শিরোনামে নিজস্ব আইকন |
| আইকন | `AppIcons`-এ `gesture`, `password`, `pin` যোগ |
| নেটিভ ইঞ্জিন | v3.0.0 → **v3.1.0** — স্থির পার্শিয়াল সাথে সাথে পাঠানো, নীরবতা-অপেক্ষা ২.৫s → ১.৬s |
| মেমরি | সহকারীর ভার্সন-নাম হার্ডকোড বাদ → `BuildConfig.VERSION_NAME` |
| ইতিহাস | পুরোনো সব কমিট, ট্যাগ ও রিলিজ মুছে নতুন ইতিহাস শুরু |

### যাচাই

- `gradle :app:compileDebugKotlin` — সফল
- `gradle :app:testDebugUnitTest` — **সব টেস্ট পাস**
- লক স্ক্রিন সবার উপরে আঁকা হয় (কী-প্রম্পট, বুট স্ক্রিন, নোটিস — সবকিছুর উপরে)

---

## v6.24.11

**তারিখ:** 2026-11-13 · **versionCode:** 61

### রিলিজ বর্ণনা

Two modes that both act, a settings rework, and gapless speech.

- **Chat mode removed entirely** — the `AgentMode.CHAT` enum, its system prompt,
  `prompt-chat.txt`, the UI picker and its tests are all gone. A stored `"CHAT"`
  value resolves to Agent in `parse()`, so nobody is left in a mode that no longer
  exists.
- **Two modes, both of which work** — BUILD and AGENT; `c.mode.value` selects
  through `contractFor(mode)`. Build mode's tool list is untouched.
- **Settings rework** — new sections and icons, a dead duplicate `perms` block
  removed, `lock` and `privacy` split into their own panes.
- **Gapless speech** — `SpeechChunker` releases its first piece sooner, so the
  voice starts on the first words instead of after the first clause.
- Version `6.24.10` / `60` → `6.24.11` / `61`. The TTS `User-Agent` no longer
  hardcodes a version; it reads `BuildConfig`.

### যাচাই

- `gradle :app:testDebugUnitTest` — ৪২৪টি টেস্ট পাস
- `TEST-CI` সফল

---

## v6.24.10

**তারিখ:** 2026-11-13 · **versionCode:** 60

### রিলিজ বর্ণনা

Write down what hardening a release actually takes, and make the release workflow
prove it on the real signed APKs before anything is published.

---

## v6.24.6

### রিলিজ বর্ণনা

Fix APK selection for the phone's CPU family. GitHub returns release assets in
name order, so `-arm32` arrives before `-arm64`; taking the first APK handed a
64-bit phone the 32-bit build, which installs and then runs as the wrong
architecture. `Updater.pickApk(assets, abis)` now matches the phone's own
`Build.SUPPORTED_ABIS`, falls back to the universal build, and only then to the
first APK.

---

## v6.21.0

### রিলিজ বর্ণনা

Composer rework: `BottomComposer` renamed away, and the microphone control — which
had been dropped from the app entirely — restored as `ComposerMicButton` in
`ui/shell/Composer.kt`.

---

## ৫. রিলিজ করার পদ্ধতি

```bash
# ১) ভার্সন বাড়ান (এই ফাইলের নিয়ম অনুযায়ী) — app/build.gradle.kts
# ২) এই ফাইলে নতুন সারি ও নতুন "## vX.Y.Z" সেকশন যোগ করুন
# ৩) কমিট ও পুশ
git add -A && git commit -m "..." && git push origin main
# ৪) ট্যাগ — versionName হুবহু, কোনো প্রত্যয় নয়
git tag -a v6.25.0 -m "..." && git push origin v6.25.0
```

ট্যাগ পুশ হলেই `release.yml` নিজে থেকে:

1. `versionName` পড়ে (`app/build.gradle.kts` থেকে),
2. তিনটি signed APK বিল্ড করে (arm64, arm32, universal),
3. সিগনেচার ও হার্ডেনিং যাচাই করে,
4. **এই ফাইল থেকে রিলিজের বর্ণনা নিয়ে** (`scripts/release-notes.sh`) রিলিজ প্রকাশ করে।

রিলিজের বর্ণনা এই ফাইল থেকেই আসে, তাই বর্ণনা কখনো ছোট বা ফাঁকা হবে না — এখানে
যতটা লিখবেন, রিলিজে ঠিক ততটাই দেখাবে।
