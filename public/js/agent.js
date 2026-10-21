(() => {
  (function(global) {
    "use strict";
    const STUCK_ROUNDS = 6;
    const MUTATING = /^(write_file|edit_file|generate_file|delete_file|publish_website|github_publish|github_workflow|github_actions|open_app|launch_url|tap|swipe|type_text|press_key|clipboard_write|share|notify|download_file|memory_write|todo_write|call_answer|call_hangup|call_dial|call_auto_answer|run_shell)$/;
    const VERIFYING = /^(read_file|read_url|read_media|read_screen|screen_shot|list_dir|find_files|grep_files|list_apps|sys_info|platform_info|process_list|clipboard_read|memory_read|github_account|call_state|geolocate|web_search)$/;
    const SYSTEM_PROMPT_URL = "/knowledge/zyraxon-system-prompt.md";
    const Agent = {
      history: [],
      // OpenAI messages
      systemPrompt: "",
      busy: false,
      aborted: false,
      pending: [],
      // prompts spoken while the agent was working
      _ctrl: null,
      _racers: [],
      model: "zyraxon-titan",
      character: "girlfriend",
      healthOrder: [],
      stalled: /* @__PURE__ */ new Set(),
      attachments: [],
      sequential: false,
      // relay mode: each round runs on the next model
      _seqIndex: 0,
      async init() {
        try {
          Agent.systemPrompt = await (await fetch(SYSTEM_PROMPT_URL)).text();
        } catch {
          Agent.systemPrompt = "You are ZYRAXON, a mobile AI assistant. Answer in the language the user speaks. Use tools to really do the work. Never stop while todos remain.";
        }
        const saved = await global.ZY.DB.recentMessages(24);
        Agent.history = saved.filter((m) => m.role === "user" || m.role === "assistant").map((m) => ({ role: m.role, content: m.content }));
        Agent.model = global.ZY.store.get("model", "zyraxon-titan");
        Agent.character = global.ZY.store.get("character", "girlfriend");
        Agent.sequential = global.ZY.store.get("sequential", false) === true;
        return Agent;
      },
      setModel(id, explicit) {
        Agent.model = id;
        global.ZY.store.set("model", id);
        if (explicit) global.ZY.store.set("userModel", id);
      },
      /** Sequential multi-model: every round hands the same task to the next
       *  model in the healthy order, so all ten really work one after another on
       *  one conversation. The shared history is what makes it a relay, not a
       *  set of separate answers: each model sees the previous model's tool
       *  results and continues from there. */
      setSequential(on) {
        Agent.sequential = !!on;
        global.ZY.store.set("sequential", !!on);
        Agent._seqIndex = 0;
        return Agent.sequential;
      },
      /** The model for this round, advancing through the chain when in relay mode. */
      _modelForRound(round) {
        if (!Agent.sequential) return Agent.model;
        const live = Agent.healthOrder && Agent.healthOrder.length ? Agent.healthOrder : [];
        const rest = (live.length ? live : ["zyraxon-apex", "zyraxon-matrix", "zyraxon-pulse", "zyraxon-titan", "zyraxon-codex"]).filter((m) => m !== Agent.model);
        const list = [Agent.model, ...rest];
        return list[(round - 1) % list.length] || Agent.model;
      },
      stop() {
        Agent.aborted = true;
        for (const r of Agent._racers || []) {
          try {
            r.ctrl.abort();
          } catch {
          }
        }
        if (Agent._ctrl) try {
          Agent._ctrl.abort();
        } catch {
        }
        global.ZY.VOICE.TTS.stop();
      },
      /** Build the full system prompt: base rules + active character + memory + skills. */
      async buildSystem() {
        const ch = global.ZY.characters[Agent.character] || global.ZY.characters.girlfriend;
        const mem = await global.ZY.DB.recall();
        const skills = global.ZY.Tools.names().join(", ");
        const mode = global.ZY.Tools.native.available() ? "Device bridge: ON (real app launch and screen control available)" : "Device bridge: not present yet \u2014 only browser-real tools work; open_app falls back to an intent/web link, and tap/swipe needs the APK (say that honestly, never claim something you did not do).";
        const memText = mem.length ? mem.slice(-40).map((m) => `- ${m.key}: ${m.value}`).join("\n") : "(nothing remembered yet)";
        const plat = global.ZY.Platform ? global.ZY.Platform.describe() : "- \u09AA\u09CD\u09B2\u09CD\u09AF\u09BE\u099F\u09AB\u09B0\u09CD\u09AE \u0985\u099C\u09BE\u09A8\u09BE";
        const call = global.ZY.Call ? global.ZY.Call.promptLine() : "";
        const uiCode = global.ZY.I18N ? global.ZY.I18N.code : "en";
        const uiName = {
          bn: "Bangla (\u09AC\u09BE\u0982\u09B2\u09BE)",
          en: "English",
          hi: "Hindi",
          ar: "Arabic",
          es: "Spanish",
          fr: "French",
          pt: "Portuguese",
          ru: "Russian",
          id: "Indonesian",
          tr: "Turkish",
          zh: "Chinese",
          ja: "Japanese"
        }[uiCode] || "Bangla (\u09AC\u09BE\u0982\u09B2\u09BE)";
        const lang = global.ZY.store.get("sessionLang", "") || uiName;
        const langRule = global.ZY.store.get("sessionLang", "") ? `The conversation language is already set to **${lang}**. Keep replying in ${lang} even if the user now writes in another language, unless they explicitly ask you to switch.` : `Match the language of the user's message. The first language they use becomes the conversation language for the rest of the session. If the user's message is too short or ambiguous to tell, answer in **${lang}**.`;
        return [
          Agent.systemPrompt,
          `
### Active character
${ch.rules}`,
          `
### Where you are really running (the platform block \u2014 do not guess, this is the truth)
${plat}`,
          `
### The user's durable memory (local DB)
${memText}`,
          `
### Available tools
${skills}`,
          `
### State of this machine
${mode}`,
          call ? `
### Calls
${call}` : "",
          `
### Language (highest priority)
- ${langRule}
- The interface language is ${uiCode}, and the user's default language is ${uiName}.
- If the user writes in Bangla, you answer in Bangla \u2014 always, every sentence, tool call explanations included. Do not answer in English because your instructions or the tools are written in English.
- Speak the same language you are answering in. Never switch language mid-answer.
- Your thinking/reasoning is shown on screen but is NOT spoken, so reason in whatever language is clearest.`,
          `
### Mandatory
- When you call a tool, do not also give a final answer in the same turn.
- Never stop while a todo is pending or in_progress.
- Never invent a result.
- Use only the commands/tools for the platform named above; never run another OS's commands.`
        ].join("\n");
      },
      /** Persist a message locally, forever. */
      async _persist(msg) {
        await global.ZY.DB.addMessage({ role: msg.role, content: msg.content, attachments: msg.attachments, ts: Date.now() });
      },
      /**
       * Build a user message, folding any pending attachments in as OpenAI
       * multimodal content parts. Every ZYRAXON model accepts image, video and
       * audio input, so we forward them verbatim rather than describing them.
       */
      _buildUserMessage(text) {
        const atts = Agent.attachments || [];
        const scr = global.ZY.Screen ? global.ZY.Screen.contextParts() : [];
        if (!atts.length && !scr.length) return { role: "user", content: text };
        const parts = [];
        if (text) parts.push({ type: "text", text });
        for (const p of scr) parts.push(p);
        for (const a of atts) {
          if (a.kind === "image") parts.push({ type: "image_url", image_url: { url: a.dataUrl } });
          else if (a.kind === "video") parts.push({ type: "video_url", video_url: { url: a.dataUrl } });
          else if (a.kind === "audio") {
            parts.push({ type: "input_audio", input_audio: { data: a.dataUrl, format: a.format || "wav" } });
            parts.push({ type: "text", text: `(\u09B8\u0982\u09AF\u09C1\u0995\u09CD\u09A4 \u0985\u09A1\u09BF\u0993 \u09AB\u09BE\u0987\u09B2: ${a.name || "audio"} \u2014 \u098F\u099F\u09BF \u09AA\u09A1\u09BC\u09A4\u09C7 \u09A8\u09BE \u09AA\u09BE\u09B0\u09B2\u09C7 \u09B8\u09CE\u09AD\u09BE\u09AC\u09C7 \u09AC\u09B2\u09CB \u09AF\u09C7 \u098F\u0987 \u09AE\u09A1\u09C7\u09B2\u09C7 \u0985\u09A1\u09BF\u0993 \u09AA\u09A1\u09BC\u09BE \u09AF\u09BE\u099A\u09CD\u099B\u09C7 \u09A8\u09BE\u0964)` });
          }
        }
        Agent.attachments = [];
        return { role: "user", content: parts };
      },
      /**
       * One full user turn: stream → tool calls → execute → repeat until
       * the model stops calling tools AND no todo remains.
       */
      async send(userText, opts) {
        const ui = global.ZY.ui;
        const hidden = !!(opts && opts.hidden);
        if (Agent.busy) {
          Agent._stopSpeechOnNextToken = true;
          Agent.pending.push(userText);
          const tail = Agent.history[Agent.history.length - 1];
          const unsafe = tail && tail.role === "assistant" && tail.tool_calls;
          if (!unsafe) Agent.history.push({ role: "user", content: userText });
          ui && ui.addMessage("user", userText);
          await global.ZY.DB.addMessage({ role: "user", content: userText, ts: Date.now(), queued: 1 });
          ui && ui.toast(global.ZY.t("toast.noted"));
          global.ZY.VOICE.STT.start();
          return;
        }
        Agent.busy = true;
        Agent.aborted = false;
        global.ZY.ui && (global.ZY.ui._thinkMsg = null);
        if (ui && ui.showThinkingNow) ui.showThinkingNow();
        Agent._userText = userText;
        global.ZY.Monitor && global.ZY.Monitor.begin();
        if (!global.ZY.store.get("sessionLang", "") && userText) {
          const heard = global.ZY.VOICE.STT && global.ZY.VOICE.STT._srvLang;
          const guess = heard && {
            bn: "bn-BD",
            en: "en-US",
            hi: "hi-IN",
            ar: "ar-SA",
            es: "es-ES",
            fr: "fr-FR",
            pt: "pt-BR",
            ru: "ru-RU",
            id: "id-ID",
            tr: "tr-TR",
            zh: "zh-CN",
            ja: "ja-JP"
          }[heard] || (global.ZY.VOICE.TTS.langOf ? global.ZY.VOICE.TTS.langOf(userText) : "");
          const name = {
            "bn-BD": "Bangla (\u09AC\u09BE\u0982\u09B2\u09BE)",
            "en-US": "English",
            "hi-IN": "Hindi",
            "ar-SA": "Arabic",
            "es-ES": "Spanish",
            "fr-FR": "French",
            "pt-BR": "Portuguese",
            "ru-RU": "Russian",
            "id-ID": "Indonesian",
            "tr-TR": "Turkish",
            "zh-CN": "Chinese",
            "ja-JP": "Japanese"
          }[guess];
          if (name) global.ZY.store.set("sessionLang", name);
        }
        const sent = (Agent.attachments || []).slice();
        const userMsg = Agent._buildUserMessage(userText);
        Agent.history.push(userMsg);
        await Agent._persist({ role: "user", content: userText, attachments: sent });
        if (!hidden) ui && ui.addMessage("user", userText, sent.length ? { attachments: sent } : {});
        try {
          let round = 0;
          let dirty = false;
          let verified = false;
          let lastSig = "";
          let sameSig = 0;
          for (; ; ) {
            if (Agent.aborted) break;
            round++;
            while (Agent.pending.length) {
              const extra = Agent.pending.shift();
              if (Agent.history[Agent.history.length - 1] && Agent.history[Agent.history.length - 1].content === extra && Agent.history[Agent.history.length - 1].role === "user") continue;
              const m = { role: "user", content: extra };
              Agent.history.push(m);
              await Agent._persist(m);
            }
            const system = await Agent.buildSystem();
            const live = global.ZY.Screen ? global.ZY.Screen.contextParts() : [];
            if (live.length) {
              const m = { role: "user", content: live };
              Agent.history.push(m);
              await Agent._persist(m);
            }
            const messages = [{ role: "system", content: system }, ...Agent._window()];
            const roundModel = Agent._modelForRound(round);
            Agent._lastModel = roundModel;
            const { content, toolCalls, reasoning } = await Agent._stream(messages, { model: roundModel });
            if (content || toolCalls.length) {
              Agent.stalled.delete(roundModel);
              Agent.stalled.delete(Agent.model);
            }
            if (reasoning && ui) ui.setThinking(reasoning);
            const sig = (content || "") + "|" + toolCalls.map((t) => t.function.name + ":" + (t.function.arguments || "")).join(",");
            if (sig && sig === lastSig) {
              sameSig++;
            } else {
              sameSig = 0;
              lastSig = sig;
            }
            if (sameSig >= STUCK_ROUNDS) {
              ui && ui.toast("\u098F\u0995\u0987 \u09A7\u09BE\u09AA \u09AC\u09BE\u09B0\u09AC\u09BE\u09B0 \u0986\u09B8\u099B\u09C7 \u2014 \u09A5\u09BE\u09AE\u099B\u09BF, \u09A4\u09C1\u09AE\u09BF \u098F\u0995\u09AC\u09BE\u09B0 \u09AC\u09B2\u09CB \u0995\u09C0 \u09AC\u09A6\u09B2\u09BE\u09AC\u09CB");
              break;
            }
            const assistant = { role: "assistant", content: content || "" };
            if (toolCalls.length) assistant.tool_calls = toolCalls;
            Agent.history.push(assistant);
            await Agent._persist({
              role: "assistant",
              content: content || "",
              tools: toolCalls.map((t) => t.function.name)
            });
            if (!toolCalls.length) {
              const todos = global.ZY.store.get("todos", []) || [];
              const open = todos.filter((t) => t.status !== "done");
              const cancelled = /শেষ|ক্যান্সেল|থামো|cancel|stop|done/i.test(userText);
              if (cancelled) break;
              if (open.length) {
                Agent.history.push({ role: "user", content: `[system] ${open.length} todo(s) are still open: ${open.map((t) => t.title).join(", ")}. Do not stop \u2014 make the next tool call.` });
                continue;
              }
              if (dirty && !verified) {
                Agent.history.push({ role: "user", content: '[system] \u09A4\u09C1\u09AE\u09BF \u0995\u09BF\u099B\u09C1 \u09AC\u09A6\u09B2\u09C7\u099B \u0995\u09BF\u09A8\u09CD\u09A4\u09C1 \u098F\u0996\u09A8\u09CB \u09AF\u09BE\u099A\u09BE\u0987 \u0995\u09B0\u09CB \u09A8\u09BF\u0964 \u098F\u0996\u09A8\u0987 \u098F\u0995\u099F\u09BE read-only \u099F\u09C1\u09B2 \u099A\u09BE\u09B2\u09BE\u0993 (read_file / read_url / read_screen / screen_shot / list_dir) \u098F\u09AC\u0982 \u0986\u09B8\u09B2 \u09AB\u09B2\u09BE\u09AB\u09B2 \u09A6\u09C7\u0996\u09BE\u0993\u0964 \u09AA\u09CD\u09B0\u09AE\u09BE\u09A3 \u099B\u09BE\u09A1\u09BC\u09BE "\u09B9\u09AF\u09BC\u09C7 \u0997\u09C7\u099B\u09C7" \u09AC\u09B2\u09A4\u09C7 \u09AA\u09BE\u09B0\u09AC\u09C7 \u09A8\u09BE\u0964' });
                continue;
              }
              break;
            }
            const results = await Agent._runTools(toolCalls);
            for (const r of results) {
              Agent.history.push({ role: "tool", tool_call_id: r.id, content: Agent._clipResult(r.result) });
            }
            for (const r of results) {
              const name = r.name || "";
              const ok = !(r.result && r.result.ok === false);
              if (MUTATING.test(name) && ok) {
                dirty = true;
                verified = false;
              } else if (VERIFYING.test(name) && ok) {
                verified = true;
              }
            }
            const seen = results.map((r) => r.result && r.result.data && r.result.data.dataUrl && r.result.data).filter(Boolean);
            if (seen.length && !Agent.aborted) {
              const parts = [{ type: "text", text: "\u099B\u09AC\u09BF\u099F\u09BF \u09A6\u09C7\u0996\u09CB \u098F\u09AC\u0982 \u09AA\u09B0\u09C7\u09B0 \u09A7\u09BE\u09AA \u09A0\u09BF\u0995 \u0995\u09B0\u09CB\u0964" }];
              for (const d of seen) parts.push({ type: "image_url", image_url: { url: d.dataUrl } });
              Agent.history.push({ role: "user", content: parts });
            }
          }
        } catch (e) {
          ui && ui.toast("\u09B8\u09AE\u09B8\u09CD\u09AF\u09BE: " + String(e).slice(0, 90));
          console.error(e);
        } finally {
          Agent.busy = false;
          global.ZY.Monitor && global.ZY.Monitor.end();
          ui && ui.endStream();
          ui && ui.setThinking("");
          global.ZY.VOICE.STT.onState && global.ZY.VOICE.STT.onState("idle");
          if (global.ZY.store.get("alwaysMic", true) && global.ZY.VOICE.STT.available()) {
            const arm = () => {
              if (!Agent.busy) global.ZY.VOICE.STT.start();
            };
            const TTS = global.ZY.VOICE.TTS;
            if (TTS._speaking || TTS._queue.length) TTS._endCb = arm;
            else setTimeout(arm, 250);
          }
        }
      },
      /**
       * The tail of the conversation that actually goes upstream.
       *
       * A naive slice(-30) is not enough: one `read_file` can put a whole file
       * into the history, and re-sending those every round is what made the
       * agent crawl and then die mid-task. This walks backwards under a hard
       * character budget and only ever cuts at a safe point — never leaving a
       * `tool` message without the assistant call that produced it, which the
       * provider rejects outright.
       */
      _window(budget = 48e3) {
        const h = Agent.history;
        const out = [];
        let used = 0;
        for (let i = h.length - 1; i >= 0; i--) {
          const m = h[i];
          const size = (typeof m.content === "string" ? m.content.length : JSON.stringify(m.content || "").length) + (m.tool_calls ? JSON.stringify(m.tool_calls).length : 0);
          if (used + size > budget && out.length >= 2) break;
          used += size;
          out.unshift(m);
        }
        while (out.length && out[0].role === "tool") out.shift();
        while (out.length && out[out.length - 1].role === "assistant" && out[out.length - 1].tool_calls) out.pop();
        return out;
      },
      /**
       * A tool result goes back into the model's context, but a 40KB file dump
       * must not: it burns the budget, slows every later round, and the model
       * never needs the whole thing again. Keep a useful head and a clear marker.
       */
      _clipResult(r) {
        const CAP = 2e3;
        const strip = (o) => {
          if (Array.isArray(o)) return o.map(strip);
          if (o && typeof o === "object") {
            const out = {};
            for (const [k, v] of Object.entries(o)) {
              if (typeof v === "string" && (v.startsWith("data:") || v.length > 4e3)) out[k] = `<${v.length} chars omitted>`;
              else out[k] = strip(v);
            }
            return out;
          }
          return o;
        };
        const text = typeof r === "string" ? r : JSON.stringify(strip(r));
        if (text.length <= CAP) return text;
        return text.slice(0, CAP) + `\u2026 [truncated ${text.length - CAP} more characters \u2014 re-read a specific part if you need it]`;
      },
      /** Stream one completion; returns assembled content + tool calls.
       *  Health order puts currently-fastest models first (the provider's
       *  pools throttle independently). If a model stalls before any byte,
       *  the agent falls through to the next healthy model automatically. */
      /**
       * A relay sends the same history to every model in turn, but they do not
       * all accept the same input: apex and pulse are text-only. Handing a
       * text-only model an image part makes it answer "I cannot see anything",
       * which is exactly the nonsense the user saw when a different model took
       * its turn. For those models the media is replaced with an honest textual
       * note so they know a picture exists and who can actually see it, instead
       * of denying the picture is there.
       */
      _fitMessages(messages, model) {
        const caps = Agent._capsOf(model);
        const canImage = caps.includes("image");
        const canVideo = caps.includes("video");
        const canAudio = caps.includes("audio");
        if (canImage && canVideo && canAudio) return messages;
        const note = (kind) => `(\u098F\u0995\u099F\u09BF ${kind} \u09B8\u0982\u09AF\u09C1\u0995\u09CD\u09A4 \u099B\u09BF\u09B2; \u098F\u0987 \u09AE\u09A1\u09C7\u09B2\u099F\u09BF ${kind} \u0987\u09A8\u09AA\u09C1\u099F \u09B8\u09AE\u09B0\u09CD\u09A5\u09A8 \u0995\u09B0\u09C7 \u09A8\u09BE \u2014 \u09AC\u09BF\u09B7\u09AF\u09BC\u099F\u09BF \u09AC\u09B0\u09CD\u09A3\u09A8\u09BE \u0995\u09B0\u09C7 \u098F\u0997\u09BF\u09AF\u09BC\u09C7 \u09AF\u09BE\u0993, "\u0995\u09BF\u099B\u09C1 \u09A6\u09C7\u0996\u099B\u09BF \u09A8\u09BE" \u09AC\u09B2\u09C7 \u09A5\u09C7\u09AE\u09CB \u09A8\u09BE\u0964)`;
        return messages.map((m) => {
          if (!Array.isArray(m.content)) return m;
          const parts = [];
          for (const p of m.content) {
            if (p.type === "image_url") {
              if (canImage) parts.push(p);
              else parts.push({ type: "text", text: note("\u099B\u09AC\u09BF") });
            } else if (p.type === "video_url") {
              if (canVideo) parts.push(p);
              else parts.push({ type: "text", text: note("\u09AD\u09BF\u09A1\u09BF\u0993") });
            } else if (p.type === "input_audio") {
              if (canAudio) parts.push(p);
              else parts.push({ type: "text", text: note("\u0985\u09A1\u09BF\u0993") });
            } else parts.push(p);
          }
          return { ...m, content: parts.length ? parts : [{ type: "text", text: "(\u09B8\u0982\u09AF\u09C1\u0995\u09CD\u09A4 \u09AE\u09BF\u09A1\u09BF\u09AF\u09BC\u09BE \u09AC\u09BE\u09A6 \u09A6\u09C7\u0993\u09AF\u09BC\u09BE \u09B9\u09B2\u09CB)" }] };
        });
      },
      /** Which input kinds a model really accepts, from the live catalogue. */
      _capsOf(id) {
        const list = global.ZY.App && global.ZY.App.models || [];
        const m = list.find((x) => x.id === id);
        const caps = m && m.input_modalities || ["text"];
        return caps.map((c) => String(c).toLowerCase());
      },
      /**
       * Which input kinds this turn actually carries (image / video / audio), so
       * only a model that really accepts them is ever asked to handle it.
       */
      _needModalities(messages) {
        const need = /* @__PURE__ */ new Set();
        for (const m of messages) {
          if (!Array.isArray(m.content)) continue;
          for (const p of m.content) {
            if (p.type === "image_url") need.add("image");
            else if (p.type === "video_url") need.add("video");
            else if (p.type === "input_audio") need.add("audio");
          }
        }
        return [...need];
      },
      /** Can this model really accept everything the turn carries? */
      _canTake(id, need) {
        if (!need.length) return true;
        const caps = Agent._capsOf(id);
        return need.every((n) => caps.includes(n));
      },
      /** Fastest known models that accept every modality in this turn. */
      _mediaCapable(need) {
        const all = (global.ZY.App && global.ZY.App.models || []).map((m) => m.id);
        const order = Agent.healthOrder && Agent.healthOrder.length ? Agent.healthOrder : all;
        const ranked = [...order, ...all.filter((x) => !order.includes(x))];
        const ok = ranked.filter((m) => Agent._canTake(m, need));
        return ok.length ? ok : [Agent.model];
      },
      /**
       * One round of the conversation, hedged for speed and never silent.
       *
       * The preferred model is asked first. If it has not produced a single byte
       * within `hedgeMs` (10 minutes by default) the next suitable model is asked
       * too, and so on — up to three. Whichever answers first wins; the others are
       * aborted on the spot, so a slow model simply loses the race and the user
       * hears the fastest one. Because the loser is cancelled, its half-answer can
       * never arrive later and contradict the winner — the "another model says it
       * sees nothing" nonsense the user reported.
       *
       * Ten minutes is deliberate: a model may simply be slow to connect, and the
       * user asked to wait that long rather than give up early. Only after that
       * does the round report the real error it got.
       *
       * A turn that carries a picture, a video or audio is only ever offered to a
       * model whose catalogue entry really lists that input kind.
       */
      async _stream(messages, opts = {}) {
        const ui = global.ZY.ui;
        global.ZY.VOICE && global.ZY.VOICE.TTS.resetLive();
        const need = Agent._needModalities(messages);
        const useProxy = global.ZY.store.get("proxy", "proxy") === "proxy";
        const url = useProxy ? "/api/chat" : "https://zyraxonai.lovable.app/api/public/zyraxon/v1/chat/completions";
        let chain = (opts.chain || Agent._chain(opts.model)).filter((m) => Agent._canTake(m, need));
        if (!chain.length) chain = Agent._mediaCapable(need);
        const hedgeMs = Math.max(1200, Number(global.ZY.store.get("hedgeMs", 6e5)) || 6e5);
        const racers = [];
        let aborted = false;
        const mkRacer = async (model2) => {
          const ctrl = new AbortController();
          const res = await fetch(url, {
            method: "POST",
            headers: { "Content-Type": "application/json" },
            body: JSON.stringify({
              model: model2,
              messages: Agent._fitMessages(messages, model2),
              tools: global.ZY.Tools.apiTools(),
              tool_choice: "auto",
              stream: true,
              max_tokens: 8e3,
              temperature: 0.7
            }),
            signal: ctrl.signal
          });
          if (!res.ok || !res.body) throw new Error("HTTP " + res.status);
          return { model: model2, ctrl, reader: res.body.getReader(), buf: "", pre: [], lastByteAt: Date.now() };
        };
        const raceOne = async (r) => {
          const dec = new TextDecoder();
          for (; ; ) {
            const { done, value } = await r.reader.read();
            if (done) return { r, ok: false };
            r.lastByteAt = Date.now();
            r.buf += dec.decode(value, { stream: true });
            const parts = r.buf.split("\n");
            r.buf = parts.pop();
            for (const line of parts) {
              const s = line.trim();
              if (!s.startsWith("data:")) continue;
              const payload = s.slice(5).trim();
              if (!payload) continue;
              let j;
              try {
                j = JSON.parse(payload);
              } catch {
                continue;
              }
              r.pre.push(j);
              return { r, ok: true };
            }
          }
        };
        const launch = (model2) => {
          const p = mkRacer(model2).then((r) => {
            racers.push(r);
            return raceOne(r);
          });
          return p.catch(() => ({ r: null, ok: false }));
        };
        const tagged = [launch(chain[0]).then((v) => ({ p: null, v }))];
        const timers = [];
        const widen = (i) => {
          if (aborted || i >= chain.length || i >= 3) return;
          ui && ui.toast(`${chain[i - 1].replace("zyraxon-", "")} \u09A6\u09C7\u09B0\u09BF \u0995\u09B0\u099B\u09C7 \u2014 ${chain[i].replace("zyraxon-", "")} \u0995\u09C7\u0993 \u09A1\u09BE\u0995\u09B2\u09BE\u09AE`);
          const pr = launch(chain[i]);
          const t = pr.then((v) => ({ p: t, v }));
          tagged.push(t);
          timers.push(setTimeout(() => widen(i + 1), hedgeMs));
        };
        timers.push(setTimeout(() => widen(1), hedgeMs));
        const GIVEUP = new Promise((res) => setTimeout(() => res("__giveup__"), hedgeMs * chain.length + 3e4));
        let winner = null;
        const live = tagged.slice();
        while (live.length && !winner && !Agent.aborted) {
          const w = await Promise.race([...live, GIVEUP]);
          if (w === "__giveup__") break;
          live.splice(live.indexOf(w), 1);
          if (w.v && w.v.ok) winner = w.v.r;
        }
        timers.forEach(clearTimeout);
        Agent._racers = racers;
        if (!winner) {
          ui && ui.toast("\u09AE\u09A1\u09C7\u09B2 \u09B8\u09BE\u09A1\u09BC\u09BE \u09A6\u09BF\u099A\u09CD\u099B\u09C7 \u09A8\u09BE \u2014 \u0986\u09AC\u09BE\u09B0 \u099A\u09C7\u09B7\u09CD\u099F\u09BE \u0995\u09B0\u09CB");
          return { content: "", toolCalls: [], reasoning: "", model: chain[0] };
        }
        const model = winner.model;
        Agent._lastModel = model;
        for (const r of racers) if (r !== winner) {
          try {
            r.ctrl.abort();
          } catch {
          }
        }
        ui && ui.markSent && ui.markSent();
        let content = "", reasoning = "";
        const calls = [];
        let sawAny = false;
        ui && ui.startStream();
        global.ZY.VOICE.TTS.resetLive();
        ui && ui.setBadge("\u09B8\u0982\u09AF\u09CB\u0997 \u09B9\u099A\u09CD\u099B\u09C7\u2026");
        const onEvent = (j) => {
          if (j.error) throw new Error(j.error.message || "stream error");
          if (j.zyraxon === "reset") {
            calls.length = 0;
            content = "";
            global.ZY.VOICE.TTS.resetLive();
            return;
          }
          const d = j.choices && j.choices[0] && j.choices[0].delta || {};
          if (d.content && Agent._stopSpeechOnNextToken && !content) {
            Agent._stopSpeechOnNextToken = false;
            try {
              global.ZY.VOICE.TTS.stop();
            } catch {
            }
          }
          if (d.reasoning_content) {
            reasoning += d.reasoning_content;
            ui && ui.setThinking(reasoning);
            global.ZY.VOICE.TTS.reason(reasoning);
          }
          if (d.content) {
            sawAny = true;
            content += d.content;
            const polite = global.ZY.VOICE.TTS.politeStream ? global.ZY.VOICE.TTS.politeStream(content) : content;
            if (polite !== content) {
              content = polite;
              ui && ui.setStream(content);
            } else ui && ui.streamToken(d.content);
            global.ZY.VOICE.TTS.feed(content);
          }
          if (d.tool_calls) {
            sawAny = true;
            for (const tc of d.tool_calls) {
              const idx = tc.index == null ? 0 : tc.index;
              calls[idx] = calls[idx] || { id: "", type: "function", function: { name: "", arguments: "" } };
              if (tc.id) calls[idx].id = tc.id;
              if (tc.function && tc.function.name) calls[idx].function.name = tc.function.name;
              if (tc.function && tc.function.arguments) calls[idx].function.arguments += tc.function.arguments;
            }
          }
        };
        let finished = false;
        const guard = setInterval(() => {
          if (Agent.aborted || finished) return;
          const idle = Date.now() - winner.lastByteAt;
          const midArgs = calls.some((c) => c && c.function && c.function.name && !isJson(c.function.arguments));
          if (idle > (sawAny ? midArgs ? 45e3 : 3e4 : 6e4)) {
            finished = true;
            try {
              winner.reader.cancel();
            } catch {
            }
          }
        }, 1e3);
        try {
          for (const j of winner.pre) onEvent(j);
          const dec = new TextDecoder();
          for (; ; ) {
            const { done, value } = await winner.reader.read();
            if (done) break;
            winner.lastByteAt = Date.now();
            winner.buf += dec.decode(value, { stream: true });
            const parts = winner.buf.split("\n");
            winner.buf = parts.pop();
            for (const line of parts) {
              const s = line.trim();
              if (!s.startsWith("data:")) continue;
              const payload = s.slice(5).trim();
              if (!payload) continue;
              let j;
              try {
                j = JSON.parse(payload);
              } catch {
                continue;
              }
              onEvent(j);
            }
          }
        } catch (e) {
          if (!Agent.aborted && !finished) throw e;
        } finally {
          clearInterval(guard);
          finished = true;
        }
        if (sawAny) {
          if (global.ZY.VOICE.TTS.polite) content = global.ZY.VOICE.TTS.polite(content);
          global.ZY.VOICE.TTS.flush();
        }
        ui && ui.endStream(calls.length > 0);
        if (ui && calls.length && !content) ui.setBadge("\u099F\u09C1\u09B2 \u099A\u09BE\u09B2\u09BE\u099A\u09CD\u099B\u09BF\u2026");
        const clean = calls.filter((c) => c && c.function && c.function.name).map((c) => {
          if (!isJson(c.function.arguments)) {
            c._bad = true;
            c.function.arguments = "{}";
          }
          return c;
        });
        return { content: content.trim(), toolCalls: clean, reasoning, model };
      },
      /** Model fallback chain: user's pick first, then live health order. */
      _chain(preferred) {
        const pref = preferred || Agent.model;
        const live = Agent.healthOrder || [];
        const base = live.length ? live : ["zyraxon-apex", "zyraxon-visionary", "zyraxon-titan", "zyraxon-pulse", "zyraxon-codex"];
        const rest = base.filter((m) => m !== pref);
        const stalled = Agent.stalled.has(pref);
        const out = stalled && rest.length ? [rest[0], ...rest.slice(1), pref] : [pref, ...rest];
        return [...new Set(out)].slice(0, 4);
      },
      /** Ask the server which pools are healthy right now (non-blocking). */
      async refreshHealth() {
        try {
          const r = await fetch("/api/model-health");
          const j = await r.json();
          if (j.order) Agent.healthOrder = j.order;
          Agent.stalled.clear();
          (j.results || []).forEach((x) => {
            if (!x.ok) Agent.stalled.add(x.model);
          });
        } catch {
        }
      },
      async _runTools(toolCalls) {
        const ui = global.ZY.ui;
        const run = async (tc) => {
          const name = tc.function.name;
          let args = {};
          try {
            args = JSON.parse(tc.function.arguments || "{}");
          } catch {
            args = {};
          }
          if (tc._bad) {
            const err = { ok: false, summary: `${name}: \u0986\u09B0\u09CD\u0997\u09C1\u09AE\u09C7\u09A8\u09CD\u099F \u0985\u09B8\u09AE\u09CD\u09AA\u09C2\u09B0\u09CD\u09A3 \u098F\u09B8\u09C7\u099B\u09C7 \u2014 \u099B\u09CB\u099F \u0995\u09B0\u09C7 \u0986\u09AC\u09BE\u09B0 \u0995\u09B2 \u0995\u09B0\u09CB` };
            ui && ui.addToolRow(name, {}, "err", err);
            return { id: tc.id, name, result: err };
          }
          ui && ui.addToolRow(name, args, "running");
          if (name === "open_app" || name === "launch_url") await new Promise((r2) => setTimeout(r2, 200));
          const r = await global.ZY.Tools.execute(name, args);
          ui && ui.addToolRow(name, args, r.ok ? "ok" : "err", r);
          if (!ui) return { id: tc.id, name, result: r };
          if (r.ok && r.data && r.data.url && name === "publish_website") {
            ui.showArtifact("Live site: " + r.data.url, r.data.url);
            ui.toast("Site published \u2014 opening it");
            global.open(r.data.url, "_blank");
          } else if (r.ok && typeof args.content === "string" && (name === "write_file" || name === "generate_file")) {
            const p = args.path || "file";
            const lang = (String(p).split(".").pop() || "code").toLowerCase();
            ui.typeCodeBox("Writing " + p, args.content, lang);
            ui.showArtifact("File \xB7 " + p, args.content);
          } else if (r.ok && typeof args.content === "string" && name === "edit_file") {
            ui.showArtifact("Edit \xB7 " + (args.path || "file"), String(args.replace == null ? "" : args.replace));
          } else if (r.ok && r.data && r.data.dataUrl) {
            ui.showArtifact("Media read: " + r.data.modality, r.data.dataUrl, r.data.modality);
          } else if (r.ok && r.data && r.data.content && (name === "read_file" || name === "read_url")) {
            ui.showArtifact(name + " \xB7 " + (args.path || args.url || ""), String(r.data.content).slice(0, 4e3));
          } else if (name === "run_shell" && r.ok && r.data && r.data.stdout) {
            ui.showArtifact("shell \xB7 " + (args.command || "").slice(0, 60), r.data.stdout);
          }
          if (Args_persist(name)) {
            const todos = global.ZY.store.get("todos", []) || [];
            const open = todos.filter((t) => t.status !== "done").length;
            if (open) ui && ui.renderTodos(todos);
          }
          global.ZY.Monitor && global.ZY.Monitor.afterTool(name, args, r, Agent._userText);
          return { id: tc.id, name, result: r };
        };
        return Promise.all(toolCalls.map(run));
      }
    };
    function Args_persist(name) {
      return name === "todo_write";
    }
    function isJson(s) {
      if (typeof s !== "string" || !s.trim()) return false;
      try {
        JSON.parse(s);
        return true;
      } catch {
        return false;
      }
    }
    global.ZY = global.ZY || {};
    global.ZY.Agent = Agent;
  })(window);
})();
