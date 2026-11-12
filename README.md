# ZYRAXON

**English** · [বাংলা](README.bn.md) · [中文](README.zh.md) · [हिन्दी](README.hi.md) · [العربية](README.ar.md)

### She is not an assistant — she is an agent

An assistant does what you ask, right away, and stops there. An agent finishes the whole job. ZYRAXON works with any system, builds large systems, ships complete applications, writes real software, makes games, and builds websites — the work of an entire company. This is not an assistant. This is a powerful agent.

### The world's most advanced all-in-one mobile AI assistant

ZYRAXON is not just a chat app — it is a complete agent in your pocket. It talks, thinks, uses tools, writes code, creates files, and runs a full Linux system inside your phone. All of it, in one app.

---

## Why ZYRAXON is different

### 1. An agent, not a chatbot
ZYRAXON does not just answer — it **does the work**. It reads the intent behind what you said, decides which tool is needed, runs it, looks at the result, and finishes the job. Writing files, running code, fetching from the web, looking at your screen — all of it automatically.

### 2. A full Linux inside
A complete Linux environment is bundled inside the app — `apt`, `dpkg`, `git`, `python`, `node`, all of it. It runs **without Shizuku and without root**. Install anything, run anything — with no separate app to install, it is all right inside ZYRAXON.

### 3. Real "thinking", shown
The moment work starts, ZYRAXON shows that it is **thinking**. Click the thinking to read its actual reasoning step by step — nothing hidden, see for yourself how it decides. Then, while it works, the **working** state is live.

### 4. One tool after another, live
Every tool is sent separately, so ZYRAXON knows when each one is needed. You watch every step on screen — what it ran, what it got, what it changed. There is no black box.

### 5. Bengali as a first-class citizen
Bengali renders perfectly in both the interface and the terminal. Code stays monospaced while Bengali shapes correctly — the two together, on the same line.

### 6. Beauty that does not get in the way
Every animation — the aura sphere, the backgrounds, the transitions — is kept, but with **no bugs**. An animation never slows the work down.

### 7. One app, a whole world
- **Voice** — speak and it listens; type and it reads
- **Media the model really receives** — a picture, a voice note or a clip is sent to the model as the media itself when that model natively takes it (Gemini, OpenRouter's audio/video models, OpenAI's audio models); otherwise the file travels as a link the agent downloads. Nothing is guessed, and a kind a model cannot take is never sent to it.
- **MCP support** — connect outside tool servers
- **Aura WebView** — a browser and web apps inside
- **Aura Studio** — work with images and media
- **Offline AI** — basic work without internet
- **One-tap updates** — new versions from inside the app

---

## Our model — a promise

ZYRAXON's own model and provider are fully built. But we are not opening them to everyone yet — because we want them to be stable, fast and reliable from day one.

> **Our goal: the moment this app reaches one hundred thousand (100,000) installs, our own provider opens to everyone.**

---

## Download

Latest APK: download `ZYRAXON-v*.apk` from **[Releases](../../releases/latest)**.

```
versionCode is bumped on every build, so it installs straight over the previous version.
A sha256 is published next to the APK — verify it before installing.
```

**Minimum:** Android 5.0 (API 21) • **Compile:** Android 16 (API 36) • **Target:** Android 9 (API 28)

The build compiles against API 36 but deliberately targets API 28. See
[Why `targetSdk` is 28](#why-targetsdk-is-28) — it is what lets the bundled Linux
run, and it must not be raised without reworking that path.

---

## Why `targetSdk` is 28

`compileSdk` is 36, so the app builds against the newest APIs. `targetSdk` is 28
on purpose.

Android 10 and later refuse `execve` for a file inside an app's own data
directory. The bundled Linux runs its binaries from exactly there, so targeting
29 or higher would switch that refusal on and take the whole Linux path down.
API 28 keeps the older behaviour that permits it. The alternative — moving every
guest binary into `jniLibs`, where execution is still allowed — is how proot
itself already ships, but it is not how a full guest filesystem works.

So the target stays at 28 until that path is reworked. Raising it silently breaks
Linux, which is why the build file carries a comment saying so.

---

## License

ZYRAXON is source-available under the **ZYRAXON Sovereign License, X Edition
(ZSL-X)** — see [LICENSE](LICENSE). Read it, run it, change it, build with it:
free use is never restricted. Selling it, rebranding it, or stripping out its
licensing systems is not allowed.

Third-party components stay under their own licenses — see [NOTICE](NOTICE).
Security issues: see [SECURITY.md](SECURITY.md).

---

## Built by

**ZYRAXON LAB** — this agent was built by our own team, from start to finish.

**[zyraxon-group-x.lovable.app](https://zyraxon-group-x.lovable.app/)**

---

<p align="center">
  <b>ZYRAXON</b> — the most capable AI agent on your phone.<br/>
  <sub>© ZYRAXON LAB</sub>
</p>
