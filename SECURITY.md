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
