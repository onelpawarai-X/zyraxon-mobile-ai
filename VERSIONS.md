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

### Which part moves

`versionName` is three numbers: **`MAJOR.MINOR.PATCH`** (for example `6.25.0`).

| Part | When it moves | Example |
| --- | --- | --- |
| **MAJOR** | A change that breaks old settings or data, or changes a stored format | `6.x.x` → `7.0.0` |
| **MINOR** | New capability, new screen, new tool — everything old still works | `6.24.11` → `6.25.0` |
| **PATCH** | Bug fixes, translations, small adjustments only — nothing new | `6.25.0` → `6.25.1` |

The part that moves moves by **one**, and every number to its right becomes
**zero** (`6.24.11` → MINOR gives `6.25.0`, PATCH gives `6.24.12`).

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
| `6.25.2` | `64` | 2026-11-14 | 🟡 ready | The Linux guest's library links are kept when a package is unpacked, so `bash` and `apt` start |
| `6.26.0` | `65` | — | 🔜 planned | Reserved for the next new capability |

> **What is the next version?** — the 🟡 row below the last ✅ row. Once `6.25.2`
> is published the 🟡 becomes ✅, and the next number is chosen by the kind of
> work: `6.25.3` (a bug fix) or `6.26.0` (a new capability).

---

## 4. Each version in detail

Every section heading below must stay in the exact form `## vX.Y.Z` — the release
workflow (`scripts/release-notes.sh`) finds the notes by that heading. The notes
themselves are written in English, because they are the release body.

## v6.25.2

**Date:** 2026-11-14 · **versionCode:** 64 · **Native engine:** v3.1.0

### Release notes

The Linux system could not run a single command. `bash` refused to start with

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
bumped to `v3`, so a Linux system installed by an earlier build is rewritten once
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
