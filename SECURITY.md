# Security Policy

## Reporting a vulnerability

Report security issues privately. Do not open a public issue.

Use GitHub's private reporting on this repository:
**[Report a vulnerability](https://github.com/onelpawarai-X/zyraxon-mobile-ai/security/advisories/new)**

Please include:

- what the issue is, and where in the app it happens
- the ZYRAXON version (Settings shows it) and your Android version
- the steps to reproduce it
- anything else that helps us confirm it

We read every report and reply within a few days.

## How the release build is hardened

A shipped APK is a file anyone can unzip. By default an Android build leaves
the whole architecture inside it — every class name, every source-file name,
every string — so the source tree is readable without any special tool. ZYRAXON
closes that, and the checks below run on every release so a regression cannot
ship quietly.

| Defence | What it stops | Where |
| --- | --- | --- |
| **Full R8** | Class, field and method names are rewritten to short meaningless ones, and unreachable code is removed. A decompiler shows `a.b.c`, not `ZyraxonAiEngine`. | `app/build.gradle.kts` |
| **No source-file names** | The source-file attribute is replaced by one constant, so a stack trace cannot be matched back to a real file. Compose's own source markers are switched off too, since R8 cannot rename those. | `app/proguard-rules.pro`, `app/build.gradle.kts` |
| **Sealed prompt and tool text** | The system prompt and the tool text are the most valuable thing in the APK, and a plain asset is readable with `unzip`. They live in `assets-src/` and are encrypted into the build (AES-GCM, PBKDF2 key); the app decrypts them in memory. The key is assembled from parts and obfuscated with everything else. | `app/build.gradle.kts`, `app/src/main/java/ai/zyraxon/assistant/sealed/SealedAssets.kt` |
| **Kept names, kept narrow** | Room, Moshi, the ViewModel factory, the JavaScript bridge, the system-started components and the Shizuku and JGit service loaders keep their names, because something looks them up by name at run time. Every such place is listed in the rules file, so the list is auditable. | `app/proguard-rules.pro` |
| **No cleartext** | Every host must be https. Only the loopback interface is exempt, where the local proxy that hands busybox its traffic listens and the bytes never leave the phone. A release build also refuses a user-installed certificate, so nothing can sit between the app and the provider holding the account key. | `app/src/main/res/xml/network_security_config.xml` |
| **No backup** | Cloud backup and device transfer are both off, so the chats, the account key and the provider keys cannot be pulled off the device by `adb backup` or a transfer. | `AndroidManifest.xml`, `app/src/main/res/xml/data_extraction_rules.xml` |
| **Signing keys never in the tree** | The upload keystore and its passwords come from the environment, so no key is committed and a build cannot be reproduced from the repository alone. | `app/build.gradle.kts` |
| **A check that proves it** | `scripts/verify-hardening.sh` fails the build if a class name, a source-file name or a plain-text asset survives in the release APK, and greps the sealed files for a phrase the prompt contains. | `.github/workflows/TEST-CI.yml` |

### Why not stronger

A phone runs the code, so the code — and any key baked into it — is on the
device the user controls. No Android app can be made unbreakable, and one that
claims to be is lying. What hardening buys is cost: a casual unzip shows
nothing, a script that looks for a known class name finds nothing, and a
determined reverse engineer spends days where they would otherwise spend
minutes. That is the honest goal, and it is what these layers deliver.

## What we care about most

These are the areas where a flaw matters most, and reports about them are
especially welcome:

- **Your account key.** It is stored on the device and sent only to ZYRAXON.
  A way to read it, leak it, or use it without the owner's consent is a serious
  finding.
- **The tool layer.** The assistant can run phone tools — shell, files, screen
  reading, calls. A way to run one of those without your consent, or to escape
  the permission system, is a serious finding.
- **The cloud transport.** A way to make the app talk to a host other than
  ZYRAXON, or to make a conversation reach a device it should not.
- **Updates.** A way to install an app in place of ZYRAXON, or to make the
  updater accept a build we did not sign.

## What is not a vulnerability

- The app runs a Linux environment and a real shell by design. Running commands
  you asked for is the feature, not a flaw.
- Screen reading and accessibility access are core features. They are on only
  when you turn them on.
- Anything that needs an already-rooted phone, or physical access to an
  unlocked device, is out of scope.

## Our commitment

- We confirm your report and tell you what we found.
- We fix what needs fixing and credit you, if you want the credit.
- We do not take legal action against anyone who reports in good faith.

Thank you for helping keep ZYRAXON and its users safe.
