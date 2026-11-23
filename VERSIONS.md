# ZYRAXON Version Ledger (VERSIONS.md)

This file is the single source of truth for version numbers. Read it **before**
choosing a version, then write the number into `app/build.gradle.kts`. A version
that is not written down here must not be used anywhere.

---

## 1. The rule — how the next version is chosen

**Four steps, in this order, before every release:**

| Step | Action |
| --- | --- |
| 1 | Read the **last ✅ published** version in the table below |
| 2 | Pick the next number by the rule underneath |
| 3 | Set `versionName` and `versionCode` in `app/build.gradle.kts` |
| 4 | Add the new row and the new section to this file |

> ### The number is a serial — it steps by **one**, always
>
> The next version is **the last published version with its last part increased
> by exactly one**. Nothing else chooses it.
>
> - Last published `6.25.2` → next is **`6.25.3`**. Not `6.26.0`, not `6.26.1`.
> - A **bigger jump is never allowed**, however large the change looks. A large
>   change is still `6.25.3`; the size of the work does not move the number.
> - Only `MAJOR` is exempt: a change that breaks old settings or data steps the
>   **first** part instead (`6.x.x` → `7.0.0`), and that is the only jump allowed.
> - A version already published is never used again, and a version that was
>   **skipped is never gone back to**. If `6.25.3` is published next, `6.25.4`
>   follows it — `6.26.0` does not come back.
>
> **Worked example.** Published: `6.25.1`, `6.25.2`. The next release, whatever
> it contains, is `6.25.3` (code `65`). The one after that is `6.25.4` (code
> `66`). The `6.26.0` that was once reserved for "the next new capability" is
> **dropped** — a reservation is not a version, and a serial never leaves a gap.

### Which part moves

`versionName` is three numbers: **`MAJOR.MINOR.PATCH`** (for example `6.25.3`).

| Part | When it moves | Example |
| --- | --- | --- |
| **MAJOR** | A change that breaks old settings or data, or changes a stored format — **the only part that may jump** | `6.x.x` → `7.0.0` |
| **PATCH** | **Every other release**, whatever it contains — a fix, a translation, a new screen, a new tool | `6.25.2` → `6.25.3` |

Only the last number moves, and it moves by **one**. The middle number is left
where it is, so the series runs `6.25.2`, `6.25.3`, `6.25.4`, … with no gaps. The
first number moves only when old data or settings would break, and then every
number to its right becomes **zero** (`6.25.4` → `7.0.0`).

### versionCode — a hard rule

`versionCode` is an integer and it **never decreases**. Android compares this
number; if it is not greater, the new APK will not install over the one on the
phone.

```
new versionCode = last published versionCode + 1
```

`versionName` and `versionCode` always move together. Bumping one and forgetting
the other has happened in this project before, and the result was an APK that
would not install.

### Not allowed

- **The same version twice** — once a version is published it can never be reused.
- **A jump in the number** — `6.25.2` → `6.26.0` is wrong; the next is `6.25.3`.
  The size of the change does not move the number, only `MAJOR` may jump.
- **Going back to a skipped number** — once `6.25.3` is published, the next is
  `6.25.4`; a reservation that was passed over is dropped, never returned to.
- **A suffix on a tag** — `v6.25.0-test1` is wrong; every release tag is `vX.Y.Z`.
- **Inventing a version from the working tree** — the version comes from this
  file, not from memory.
- **A tag without a matching `versionName`** — the tag and `versionName` are
  identical.
- **Mixing the app version with the native engine version** — they are two
  different things, see below.

---

## 2. Two different versions — do not confuse them

| Name | Where it lives | What it means |
| --- | --- | --- |
| **App version** | `app/build.gradle.kts` → `versionName` | The version of the whole application; the release tag, the APK name and the in-app update all key off it |
| **Native engine version** | `Sheets.kt` → the `ZYRAXON Native Engine vX.Y.Z` label | Only the version of the phone's own voice engine (STT + TTS) |

The native engine is one part inside the app. When it changes, **only that label**
moves, not `versionName` — the app version still steps by the rules above, and
this file records that the engine moved with it.

**Current native engine: `v3.1.0` (Zero Lag).**

---

## 3. Version table (every version, in one place)

Status: **✅ published** · **🟡 ready (not yet published)** · **🔜 planned**

| Version | versionCode | Date | Status | In short |
| --- | --- | --- | --- | --- |
| `6.21.0` | — | — | ✅ published | Composer rework; the microphone control returns (`ComposerMicButton`) |
| `6.23.2` | — | — | ✅ published | (recorded in history) |
| `6.23.7` | — | — | ✅ published | (recorded in history) |
| `6.24.2` | — | — | ✅ published | (recorded in history) |
| `6.24.3` | — | — | ✅ published | (recorded in history) |
| `6.24.6` | — | — | ✅ published | `pickApk` ABI selection fixed — a 64-bit phone no longer gets the 32-bit APK |
| `6.24.7` | — | — | ✅ published | (recorded in history) |
| `6.24.8` | — | — | ✅ published | (recorded in history) |
| `6.24.9` | — | — | ✅ published | (recorded in history) |
| `6.24.10` | `60` | 2026-11-13 | ✅ published | Write down what hardening a release actually takes |
| `6.24.11` | `61` | 2026-11-13 | ✅ published | Two modes that both act, a settings rework, and gapless speech |
| `6.25.0` | `62` | 2026-11-13 | ✅ published | The app's own lock (password + pattern), an icon on every heading, native engine v3.1.0 (Zero Lag) |
| `6.25.1` | `63` | 2026-11-14 | ✅ published | Duplicate voice turns stopped, every language complete, apt repaired, links go to `/mobile` |
| `6.25.2` | `64` | 2026-11-14 | ✅ published | The ZYRAXON OS guest's library links are kept when a package is unpacked, so `bash` and `apt` start |
| `6.25.3` | `65` | 2026-11-15 | ✅ published | Voice turns sent once, cut-in by voice, 1.5 s auto-send; all system prompts rewritten in English with one shared human core |
| `6.25.4` | `66` | 2026-11-16 | ✅ published | API keys and tokens sealed in the Android keystore; the app lock is a pattern or a PIN plus optional spoken lock and unlock words |
| `6.25.5` | `67` | 2026-11-15 | ✅ published | Settings → System → Documentation (20 chapters, 60 languages, search) and the Automation hub (skills, autopilot, money for any country, timeline, brain, call agent) |
| `6.25.6` | `68` | 2026-11-16 | ✅ published | A cloud turn no longer loses its events when the phone goes away — the read cursor is written down, so a relaunch, a closed app or a lost network is caught up from where the chat stopped, and a turn still running on the cloud is shown as running and followed live again. Also: the guest's package repositories are repointed at hosts that resolve, so `pkg install` works online, and emoji are dropped by code point before speech, so the voice neither reads them nor stalls on them |
| `6.25.7` | `69` | 2026-11-18 | ✅ published | Content Shield follows the permission level (strict at Locked, off at Full automation); Documentation is its own section with all 60 languages written inside the app; new chapters for approval levels, settings, the automation hub, security and troubleshooting |
| `6.25.8` | `70` | 2026-11-19 | ✅ published | The maker's identity in every prompt: his name, family, links, contact and favourites, with "Alex" refused outright |
| `6.25.9` | `71` | 2026-11-20 | ✅ published | Every prompt leaves the APK: eight sealed files downloaded on first open, the download is a real first screen, and a corrected prompt reaches every phone without a new build |
| `6.25.10` | `72` | 2026-11-23 | 🟡 ready | One name, one address: the system layer carries no other project's name anywhere, and `apt`/`pkg` now speak only to a ZYRAXON host that serves the real repository byte for byte |

> **What is the next version?** — the row below the last ✅ row, and nothing
> else. With `6.25.6` published the next is `6.25.7` — the last published number
> with its last part increased by one. There is no
> "next new capability" number: every release, large or small, takes the next
> serial. `6.26.0` and `6.26.1` were reserved here once and are now **dropped**,
> because a serial never leaves a gap.

---

## 4. Each version in detail

Every section heading below must stay in the exact form `## vX.Y.Z` — the release
workflow (`scripts/release-notes.sh`) finds the notes by that heading. The notes
themselves are written in English, because they are the release body.

## v6.25.10

**Date:** 2026-11-23 · **versionCode:** 72 · **Native engine:** v3.1.0

### Release notes

# ZYRAXON v6.25.10 — One name, one address

**Two things were still borrowed: the name of the system underneath, and the
address the installer fetched from. Both are ours now.**

## 🏷 The system layer speaks one name

The whole bundle that runs inside the app — `assets/`, the classes that start it,
the tool the agent calls, the 60-language help chapters, the mode descriptions —
carried the name of the project it was built from. That name is gone:

- `assets/linux/` is now `assets/system/`, and the class that boots it is
  `ZyraxonOs`; the old runtime class is gone entirely.
- The agent's tool is `os_run`; the old name it answered to still resolves to it,
  so a habit typed yesterday keeps working.
- Every help chapter in every language, every mode name and the runtime keys file
  were rewritten by hand — not a search-and-replace, so the sentences still read
  like sentences.
- What deliberately kept its name: the package identities (`libandroid-selinux`),
  the Android compiler triple (`aarch64-linux-android`), and the terminfo and man
  pages. Renaming those is not branding, it is breaking `clang`, `apt` and `dpkg`.

The APK now contains **zero** file or folder names from the old project, and the
payload archives contain **zero** entries that use it.

## 📦 The installer talks to one host

`apt` used to be pointed at whoever the bundle was forked from. Every payload now
ships a `sources.list` that names us:

```
deb [trusted=yes] https://zyraxonai.lovable.app/api/public/zyraxon/pkgs/zyrxon-main stable main
deb [trusted=yes] https://zyraxonai.lovable.app/api/public/zyraxon/pkgs/zyrxon-root stable main
```

The address is not compiled in. The app reads `https://zyraxonai.lovable.app/pkgs/source.json`
at runtime, probes the repository's signed `Release` before it trusts it, and
caches the answer for six hours — so the in-point can move to another host and
every installed phone follows without a new build.

The site is not a mirror of convenience: it relays the upstream bytes unchanged.
`Release` is signed over `Packages`, so a rewritten index would turn every
install into a signature failure. Only the hostname is ours.

## ✅ What was proven before shipping

| file | result |
| --- | --- |
| `dists/stable/Release` | byte-identical, 13,134 B |
| `dists/stable/InRelease` | byte-identical, 14,016 B |
| `Packages.gz` (aarch64, arm, x86_64) | byte-identical, ~700–737 kB each |
| a real package (`attr`, `jq`) | byte-identical, and its sha256 matches the signed `Release` |

`pkg install` and `apt` keep working on all three architectures, and a package
whose name does not exist upstream still answers 404 instead of hanging.

## 🧹 Also

A debug APK from before the rename was still sitting in the repository. It was
built from the old code, referenced by nothing, and is gone.

---


**Date:** 2026-11-20 · **versionCode:** 71 · **Native engine:** v3.1.0

### Release notes

# ZYRAXON v6.25.9 — Nothing to read in the APK

**The app no longer carries a single line of its own instructions.**

## 🔒 Eight files, downloaded once
An install used to contain the text that says exactly how the agent behaves — anyone who unzipped the APK could read it, and correcting one line meant shipping a whole new build. The prompts now live on the website as **eight sealed files**, fetched on the first open and kept on the phone.

| file | what it is |
| --- | --- |
| `mode-agent.txt` | Agent mode — runs the whole phone, with the live-voice and phone-call rules inside it |
| `mode-build.txt` | Build mode — a short prompt in, a whole app out |
| `character-lover.txt` | প্রেমিকা |
| `character-boyfriend.txt` | প্রেমিক |
| `character-friend.txt` | বন্ধু |
| `character-assistant.txt` | সহায়ক |
| `character-cyber.txt` | সাইবার স্পেশালিস্ট |
| `character-philosopher.txt` | দার্শনিক |

Two contracts, six characters, and nothing else. There is no separate cloud prompt, no separate offline prompt and no separate live prompt any more: the cloud agent, the on-device model, the live voice session and the phone-call screening all read one of those two contracts, so a correction reaches every one of them at once.

## 🌌 The download is the first thing you see
The first open after install is not a spinner. One orbit node per file lights as its eighth of the bundle lands, a tick ring fills clockwise with the real byte count, particles fall into the core, and the whole screen brightens as the bundle arrives. It follows your theme — blood red on **ভয়ঙ্কর**, violet on **ডার্ক**, ink on **লাইট** — because it is the app's first impression and should look like the app you chose.

Nothing is faked: the number is the real fraction that has arrived, and the app does not start until the last file has been verified.

## 🔁 A correction without a new APK
Each bundle carries a version. The app compares the version it holds against the website on every start and re-downloads only when the remote one is newer — quietly, in the background, so a corrected prompt reaches every phone without anyone installing anything.

A download is checked before it is trusted: every file's SHA-256 is compared against the manifest, and each blob is AES-256-GCM encrypted with a key derived by PBKDF2. A file that fails a check is never written, and a half-finished download is never installed.

## 🧹 What went away
The four prompt files that used to sit in the APK are gone. So is the code that retyped the maker's identity into the on-device prompt and into the phone-call prompt — both now receive the same downloaded contract as everything else. One text, one place to correct it.

---

---

### The voice you chose, and a mic that hears only you

**Three things were wrong in the same place — who is speaking, what the
microphone does with it, and whether the app can set itself up. All three are
fixed.**

## 🎙 The voice follows the identity

- Girlfriend mode speaks with a female voice. **Boyfriend, Friend, Super
  Assistant, Philosopher and Cyber Specialist now speak with a male voice.**
  Before this build every one of them fell through to the female profile.
- Switching identity applies the voice **immediately** — the old code only set
  it once, on the very first launch.
- The **Voice** section still wins: tapping Female or Male, or choosing one
  exact voice from the list, changes the voice on the spot and is remembered on
  the next launch. A specific voice is no longer overridden by the server's
  default.

## 🎤 The microphone hears you, not the assistant

- The assistant's own spoken answer is **no longer typed into the chat box and
  sent as if you had said it**. A phrase made only of words it just spoke is
  dropped, even when the recogniser mishears a word or two.
- The microphone stays **open the whole time it talks**. Speak while it is
  answering and its voice **stops at once**, your words appear, and your turn is
  sent. If what it heard was only its own echo, the answer **resumes by itself**
  after a moment instead of staying cut off.
- "থামো / stop / চুপ / বন্ধ" always cuts it off, even mid-sentence.
- After a reply the ear is no longer deaf: a real answer that arrives in the
  second or two after the last word is sent normally.

## 🌍 Every language is complete

- All **60 languages** now carry the **whole 1,072-string UI table** and the
  **430 tool-message fragments**. The 20 newest strings (Automation hub,
  documentation language, translation progress) were missing everywhere; the
  Assamese table was missing 73. Both are filled.
- The Documentation screen's own table is complete in every language, Assamese
  included.

## 🤖 The agent runs the Automation hub itself

- The agent can now **set up the hub by tool call** instead of telling you to
  open a screen: Skills (`skill_record`, `skill_run`, `skill_list`,
  `skill_delete`, `skill_import`), Autopilot (`autopilot_add`, `autopilot_list`,
  `autopilot_toggle`, `autopilot_remove`), the money ledger (`ledger_scan`,
  `ledger_report`, `ledger_budget`), Timeline (`timeline_list`, `timeline_undo`),
  Brain (`brain_index`, `brain_search`), the call agent (`call_agent`,
  `call_log`) and Memory (`memory_write`, `memory_read`, `memory_delete`).
- It **saves what you like** — your language, tone, nickname, likes and dislikes
  — and talks to you that way on every turn. Tell it a fact is wrong and it
  deletes the old one instead of keeping both.

## 🛠 Release engineering

- `scripts/verify-apk-abi.sh` and `scripts/verify-package-sources.sh` are now
  executable, so the release job stops failing at "Check what each APK carries"
  and can verify the signature and the payload.

## v6.25.8

**Date:** 2026-11-19 · **versionCode:** 70 · **Native engine:** v3.1.0

### Release notes

# ZYRAXON v6.25.8 — Know whose you are


**Ask who made ZYRAXON and the answer is now a name, not a shrug.**


## 🪪 Your maker, in every contract
Every prompt the app can run under — Agent, Build, live voice, the offline on-device model, and the cloud agent — now carries the same block: **Deluar Husen Sayidi**, founder and owner of ZYRAXON, 17, from Bangladesh, the youngest of six siblings (two brothers and four sisters).

- **"Who made you?"** answers with his name and what he does — first time, without being asked twice.
- **"Tell me more"** gives his links: GitHub, YouTube, and both TikTok accounts.
- **"How do I reach him?"** gives his mobile, which is also on WhatsApp and IMO.
- **"What does he like?"** gives pink, Arabic music and remixes, and *Chitthi Video Song*.
- **"Who is his family?"** gives the six siblings, told the way someone proud of their family would tell it.

The block sits before the character block in all four contracts, so a romantic or joking character still answers this one as fact. The old name **"Alex"** is refused outright, and a note in the block says any memory or older note that carries a different name is the wrong one.

Covered by `MakerIdentityTest`: every contract is checked for the name, the family, the number, the links and the song, and for the block's position. The offline prompt is checked separately, because it is built in code and can be forgotten on its own.


## Upgrade
Install over your current version; chats, memory and settings stay.
`versionName 6.25.8 · versionCode 70`

## v6.25.7

**Date:** 2026-11-18 · **versionCode:** 69 · **Native engine:** v3.1.0

### Release notes

# ZYRAXON v6.25.7 — Permissions you can trust, a guide in your language


**Full automation stays full automation. Everything else now scales with the level you choose.**


## 🛡️ Content Shield that follows your permission level
Whatever the agent reads from outside (another app's screen, a web page, an SMS, a file, a connected server) is checked in code for text that tries to give it orders — in many languages, including text hidden with invisible characters.


| Level | Asks before | Content Shield |
|---|---|---|
| **1 · Locked** | anything that changes the phone | **Strict** — any suspicious text counts as hostile |
| **2 · Supervised** (default) | system and personal actions | **Standard** — suspicious text is fenced as data; clearly hostile text makes every changing tool ask for the rest of that turn |
| **3 · Balanced** | system-level actions only | **Light** — acts only on clearly hostile text, and only system tools stop to ask |
| **4 · Full automation** | **nothing, ever** | **Off completely** — nothing is scanned, marked, removed or changed. You own the risk |


- The level is enforced in code on every tool call; a model cannot widen it.
- A new message from you clears the shield for the next turn.
- Covered by a unit-test suite (57 checks in 11 languages, hidden-character and full-width tricks included).


## 📖 Documentation — its own section, in all 60 languages
- **Settings → Documentation** is now a section of its own, separate from System.
- 21 chapters, a search box and a language row. Every one of the app's **60 languages ships fully written inside the app** — nothing is translated on your phone, no model, no key, no internet needed.
- New and rewritten chapters: **Approval levels** (Locked / Supervised / Balanced / Full automation, with the shield behaviour per level), **Settings** (every screen, one by one), **Automation hub** (Skills, Autopilot, Money, Timeline, Brain, Call agent), Security & privacy and Troubleshooting.
- Sub-buttons in Settings scroll sideways, so nothing is squeezed or hidden.


## 🤖 Also in this build
- Automation hub explained tab by tab, right where you use it.
- Guide loader prefers the language text shipped in the app for any language.
- Gaps found in the bulk translation (228 blank lines in 11 languages) were filled by hand — no blank line remains.


## Upgrade
Install over your current version; chats, memory and settings stay.
`versionName 6.25.7 · versionCode 69`

## v6.25.6

**Date:** 2026-11-15 · **versionCode:** 68 · **Native engine:** v3.1.0

### Release notes

**A cloud turn survives the phone going away.** A turn keeps running on the cloud
after the app closes, the process is killed, or the network drops — that is the
whole point of a cloud conversation — but the phone only knew what happened while
its socket was open. The read cursor lived in memory inside the turn, so anything
the cloud produced while the phone was away was never asked for again: reopening
the chat showed a stale transcript, and a job still running on the other side
looked finished.

The cursor is now written down as the read advances, beside the conversation
binding, and is cleared with it. When a chat is opened, when the app returns to
the foreground, and when the network comes back, everything recorded after that
cursor is read back and folded into the transcript — in order, de-duplicated by
the cloud's own event ids, so nothing is shown twice. A turn the cloud is still
running is shown as running again from the first frame, and the app re-attaches
to it live, so the rest of a long job streams onto the phone as it happens.

Tools the cloud ran while the phone was away are shown as lines, never re-run:
replaying a user's tool would be worse than a stale line.

**The package manager works online again.** The ZYRAXON OS guest is a renamed copy of
another distribution's, and the rename had reached the repository URLs: every
mirror file under `etc/zyrxon/mirrors/` named a host under a domain that does not
resolve, and the repository directory was renamed to match. `pkg` therefore found
no reachable mirror and refused to install anything. The bundled packages kept the
base system alive, so nothing looked wrong until the first `pkg install` — which
failed with no reason given.

Only the network location is rewritten. The signing keys, the package names, the
prompt, the MOTD and every user-visible path are untouched, so the branding is
exactly as it was. The repair is the rename run backwards, so it covers every
host the rename touched — a mirror that does not answer upstream stays in the
list, as it does in the upstream list, and `pkg` skips it as it always has. The
repair runs at install and once for a guest installed by an earlier build, so an
existing phone is fixed without reinstalling.
`scripts/verify-package-sources.sh` fails the build if a branded host ever comes
back, and `scripts/fix-payload-sources.py` repoints the three payload archives.

**Emoji no longer reach the voice.** The rule that dropped emoji above the BMP was
written as a surrogate-pair class (`[\uD83C-\uD83E][\uDC00-\uDFFF]`). A Kotlin
`Regex` is `java.util.regex`, which does not fold a surrogate pair into one code
point, so the pattern never matched: every astral emoji (😀 🎉 👍 🚀) was sent to
the voice endpoint and read aloud as its name, and the endpoint stalled on it. The
same is true of a keycap, of the older dingbats (`‼ ⁉ ™ ℹ`), and of the tag
characters behind a subdivision flag. All of them are dropped by code point now.

A piece made only of punctuation — a stray `।` released by the sentence cutter —
was sent as its own request and came back as silence, after a full round trip:
heard as a pause in the middle of an answer. Such a piece is never sent, on either
the streaming path or the whole-reply path. Nothing else about speech changed, so
the real-time path is untouched.

## v6.25.5

**Date:** 2026-11-15 · **versionCode:** 67 · **Native engine:** v3.1.0

### Release notes

**Documentation inside the app.** Settings → System → Documentation is a
readable manual in plain text: 20 chapters covering the two modes (Agent and
Build), every button, voice, every tool group (phone, files, build, media,
cloud, skills, autopilot, timeline undo, call agent, memory), permissions and
auto-permission levels, approvals, settings, models, security, languages,
updates, tips and troubleshooting. Pick any of the 60 app languages from the
chips; chapters ship in English and Bengali and other languages use the
on-device model with the result cached. A search box filters chapters.

**Automation hub, worldwide.** Settings → System → Automation hub (also in the
side drawer) puts skills (with "Teach by doing"), autopilot rules, the money
ledger, the timeline, the brain search and the call agent behind buttons. The
money ledger reads wallet and bank SMS of any country, in any currency and
language, and keeps each currency apart. The call agent answers in the caller's
own language (rule packs for 15 languages, the model for the rest). The agent's
prompts and tool descriptions know all of it and can use it.

## v6.25.4

**Date:** 2026-11-16 · **versionCode:** 66 · **Native engine:** v3.1.0

### Release notes

**API keys are sealed, not written in the clear.** Every provider key, the GitHub
token, and every MCP access, refresh and client secret used to sit in a
preferences file as readable text. A preferences file is private to other *apps*,
but it is not private to a rooted phone or to anything that copies the file off
the device — and a key read once is a key spent. Each value is now encrypted with
AES-256-GCM under a key that lives in the Android keystore and never leaves it,
so a copy of the file is a copy of ciphertext. On Android 6.0 and up the keystore
holds the key; on 5.x, which has no AES keystore, a per-install key is used and
the weaker guarantee is written down. Keys already on a device are moved into the
sealed store the first time they are read, and the plaintext copy is deleted.

**The app lock is a pattern or a PIN — one slot, two shapes.** Setting a PIN no
longer leaves a pattern behind as a second way in; the newest secret is the one
that opens the door, and either shape can be chosen from the same card. A spoken
word is optional and stacks on top: set only a word and the voice opens the app,
set a word *and* a secret and the cover asks for both.

**Setting a lock is a flow, not a trap.** Draw or type the secret, see that it is
ready, then choose: Save commits it, and the button to draw again discards the
draft. Nothing is stored until Save is pressed, so a pattern drawn by mistake is
never the one that locks the app. The button labels follow the user's language,
like every other control.

**Lock and unlock by voice.** Two spoken words can be set — one that locks the
app, one that unlocks it. Say the lock word and the cover comes down; say the
unlock word and it lifts. The words are matched before anything else the
recognizer returns, so they work whether the cover is up or the app is in use,
and they are never shown in the chat as a message.

**Every new string is translated in all 60 languages.** No language falls back to
the Bengali source, and the test that proves it still passes.

---

## v6.25.3

**Date:** 2026-11-14 · **versionCode:** 65 · **Native engine:** v3.1.0

### Release notes

**The prompts are English now, and share one core.** All four system prompts and
the six character prompts are rewritten, so they cost fewer tokens and the
characters answer in whatever language the user speaks. Every file carries the
same block: a human identity that never calls itself an AI, one rule set for
working (stated once), one speaking style — a short opening, short lines while
working, a short wrap-up — and one rule for asking: do nearly everything first,
then ask a single short question with a recommendation, and wait. A landing page
or a website is built straight away and opened in the preview with no publishing;
an app is built as asked; full-stack work asks for the back-end choices first.

**A spoken sentence is sent once.** The recognizer closes a phrase only after its
silence window, so a partial that holds still is submitted early — and then the
recognizer's own final arrives carrying the same words and is sent again. The two
are not always byte-identical; the engine trims, or completes a trailing word,
between them. The final is now compared word by word with the partial already
sent, so a dropped or completed trailing word still counts as the same turn.

**Speaking over the assistant works.** A single real word now cuts it off, a
phrase spoken over it is sent after its own pause instead of waiting for the
recognizer's slow final, and 1.5 s of silence sends the message.

## v6.25.2

**Date:** 2026-11-14 · **versionCode:** 64 · **Native engine:** v3.1.0

### Release notes

The ZYRAXON OS could not run a single command. `bash` refused to start with

```
CANNOT LINK EXECUTABLE ".../files/usr/bin/bash":
Library "libncursesw.so.6" not found: needed by
".../files/usr/lib/libreadline.so.8.3"
```

and every command that needs a shell — `apt update` above all — died with it.

**The link was never made.** A shared object records the dependency it was built
against, and that name is not always the file that is installed:
`libreadline.so.8.3` asks for `libncursesw.so.6`, while the package ships only
`libncursesw.so.6.5`. The shorter name is supplied by a symbolic link, and the
`.deb` does contain it. The unpack threw it away.

Unpacking a package copied its tree with `copyRecursively`, which is the obvious
call and wrong twice over. It follows a symbolic link and copies what it points
at, so the link became a second copy of the library instead of the link the
loader looks for. And when it meets a link whose target has not been written yet
it stops the whole copy, so every file after it in that package never arrives —
silently, with the package still reported as installed. The payload needs
**twenty** of those links. The guest was left with none of the ones that matter.

The tree is now merged entry by entry. A symbolic link is recreated as a link
with `Os.symlink`, and a directory entry is classified by `Os.lstat`'s `st_mode`
rather than by `File`, because a link whose target is missing has to still be
recognised as a link. A test reads the real payload and proves every library a
binary asks for can be resolved, so a payload swap that drops a link fails in CI
rather than on a phone.

**A guest already installed is repaired, not thrown away.** The install is
skipped once it has finished, so the link pass would never run again for anyone
who already had the system. Before the first command, a guest from an older build
has its links rebuilt from the manifest inside the APK — the guest's own copy is
the very file the old build failed to act on — and if a library target is missing
as well, the tree is rebuilt from the payload once. The configuration marker moves
to `v4` so the repair runs exactly one time.

## v6.25.1

**Date:** 2026-11-14 · **versionCode:** 63 · **Native engine:** v3.1.0

### Release notes

Four defects, one release. Two were in the voice loop, one hid behind a
translation table that was never written, and one was apt refusing to start.

**A spoken phrase was sent twice.** The recognizer closes a phrase only after its
silence window, so a partial that holds still is submitted early — and then the
recognizer's own final arrives carrying the same words and is sent again. The two
are not always byte-identical; an engine trims, or collapses a double space,
between them. `SttDecision.isSameTurn` now folds case and whitespace and treats
the second one as the same turn, within a six-second window so that a phrase
repeated on purpose later is still a new turn. The rule runs on **both** arrival
orders, and the record is written by whichever side actually sent, so the
duplicate is dropped whichever one it turns out to be.

**Bengali showed in every language.** Forty-five strings in `AppLockScreen`,
`Sheets` and `PermissionPanel` — the lock, the security pane, the permission
pane — were in no table at all, so no language could translate them and the
fallback handed Bengali back. They are now in all 59 tables, the `tools-*` tables
are filled to match `tools-en`, and the translation tooling writes to
`assets-src/i18n` — the path the build seals — instead of the old
`app/src/main/assets/i18n`, which nothing has read for some time.

The same tooling had a second fault, found while filling the tables: it let the
translator rewrite a URL. Serbian turned `zyraxonai.lovable.app` into Cyrillic
`апп.алл-хандс.дев`, a link that goes nowhere. A URL is now masked through the
endpoint exactly like a `{N}` placeholder, so it comes back as it went out.

**apt could not choose its backend.** `apt update` and everything else stopped at
`unable to determine a suitable packaging system type` (exit 100). The cause is in
apt's own source: `pkgInitSystem` scores each known system from three raw
`stat()` calls — `Dir::State::status`, `Dir::Bin::dpkg` and `/etc/debian_version`
— and picks the highest. Both of the first two were written as **relative** paths,
so they resolved against the process's working directory (`/home`), where neither
file exists, the score stayed at zero, and apt gave up before running anything.
Both are now absolute, which takes the score to 20. The configuration marker is
bumped to `v3`, so a ZYRAXON OS installed by an earlier build is rewritten once
and picks up the fix without the user reinstalling anything.

**The key prompt opens the mobile page.** The first-run box that asks for a token,
and the provider sheet in Settings, both opened the site root. They now open
`https://zyraxonai.lovable.app/mobile`, the page that belongs to the app, in all
60 languages.

## v6.25.0

**Date:** 2026-11-13 · **versionCode:** 62 · **Native engine:** v3.1.0

### Release notes

The lock is now the app's own, and it no longer drags ZYRAXON back to the front.

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

**A version ledger, so the next version is read rather than guessed.** The whole
list lives in `VERSIONS.md`: which versions are published, which is next, and the
full notes for each. The release body now comes from this file rather than the tag
annotation, so it is 61 lines instead of 1. Two scripts hold it in place, and both
run in CI and in the release workflow before a byte is built.

### What changed

| Area | Change |
| --- | --- |
| App lock | Phone credential dropped; the app's own password (4–8 digits) and 3×3 pattern |
| Lock timing | `onStop()` → `onStart()` — opening another app no longer brings ZYRAXON forward |
| Security | `AppLockStore.kt` — PBKDF2-HMAC-SHA256, per-install salt, constant-time compare |
| Settings | New lock pane; password/pattern choice and a way to remove the lock |
| Section titles | `HorrorSectionTitle` takes an icon; 20 headings now carry their own |
| Icons | `gesture`, `password` and `pin` added to `AppIcons` |
| Native engine | v3.0.0 → **v3.1.0** — a settled partial is submitted at once; silence window 2.5s → 1.6s |
| Version ledger | `VERSIONS.md` added; `scripts/verify-version.sh` and `scripts/release-notes.sh` |
| Memory | The hardcoded assistant version name is gone; it reads `BuildConfig.VERSION_NAME` |
| History | Every old commit, tag and release removed; the repository starts fresh |

### Verified

- `gradle :app:compileDebugKotlin` — success
- `gradle :app:testDebugUnitTest` — all tests pass
- The lock screen draws over everything (key prompt, boot screen, notices)
- `scripts/verify-version.sh` — the version is written down
- Release workflow — build success, three signed APKs published

---

## v6.24.11

**Date:** 2026-11-13 · **versionCode:** 61

### Release notes

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

### Verified

- `gradle :app:testDebugUnitTest` — 424 tests pass
- `TEST-CI` — success

---

## v6.24.10

**Date:** 2026-11-13 · **versionCode:** 60

### Release notes

Write down what hardening a release actually takes, and make the release workflow
prove it on the real signed APKs before anything is published.

---

## v6.24.6

### Release notes

Fix APK selection for the phone's CPU family. GitHub returns release assets in
name order, so `-arm32` arrives before `-arm64`; taking the first APK handed a
64-bit phone the 32-bit build, which installs and then runs as the wrong
architecture. `Updater.pickApk(assets, abis)` now matches the phone's own
`Build.SUPPORTED_ABIS`, falls back to the universal build, and only then to the
first APK.

---

## v6.21.0

### Release notes

Composer rework: `BottomComposer` renamed away, and the microphone control — which
had been dropped from the app entirely — restored as `ComposerMicButton` in
`ui/shell/Composer.kt`.

---

## 5. How to release

```bash
# 1) Bump the version by the rule above — app/build.gradle.kts
# 2) Add the new row and the new "## vX.Y.Z" section to this file
# 3) Commit and push
git add -A && git commit -m "..." && git push origin main
# 4) Tag — exactly the versionName, with no suffix
git tag -a v6.25.0 -m "..." && git push origin v6.25.0
```

Pushing the tag makes `release.yml`:

1. read `versionName` from `app/build.gradle.kts`,
2. build three signed APKs (arm64, arm32, universal),
3. verify the signatures and the hardening,
4. publish the release **with the notes from this file**
   (`scripts/release-notes.sh`).

The release body comes from this file, so it can never be short or empty — as
much as is written here is as much as the release shows.
