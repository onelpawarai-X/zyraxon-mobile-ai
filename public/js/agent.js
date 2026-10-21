/* ══════════════════════════════════════════════════════════════
   ZYRAXON Agent — the realtime loop.

   • Streams tokens over SSE and speaks them as they arrive.
   • Detects tool calls, executes them for real, injects the
     results back into context, and continues autonomously.
   • Honours the todo contract: it will not finish while tasks
     remain — it keeps making tool calls, turn after turn.
   • Parallel tool calls in one turn are executed concurrently.
   ══════════════════════════════════════════════════════════════ */
(function (global) {
  'use strict';

  // The user's rule: while a todo is open, or while work has not been verified,
  // the agent must not stop. There is deliberately NO round cap — the only
  // things that end a turn are: the user cancels, the work is verified, or the
  // model is provably stuck (it stops calling tools and stops changing its
  // answer). A silent hard cap is exactly what made the assistant quit in the
  // middle of a task, so it is gone.
  const STUCK_ROUNDS = 6;
  // Tools that change the world. Anything here invalidates a previous check.
  const MUTATING = /^(write_file|edit_file|generate_file|delete_file|publish_website|github_publish|github_workflow|github_actions|open_app|launch_url|tap|swipe|type_text|press_key|clipboard_write|share|notify|download_file|memory_write|todo_write|call_answer|call_hangup|call_dial|call_auto_answer|run_shell)$/;
  // Tools that only look at the world. One of these, succeeding after the last
  // change, is real evidence that the change works.
  const VERIFYING = /^(read_file|read_url|read_media|read_screen|screen_shot|list_dir|find_files|grep_files|list_apps|sys_info|platform_info|process_list|clipboard_read|memory_read|github_account|call_state|geolocate|web_search)$/;
  const SYSTEM_PROMPT_URL = '/knowledge/zyraxon-system-prompt.md';

  const Agent = {
    history: [],           // OpenAI messages
    systemPrompt: '',
    busy: false,
    aborted: false,
    pending: [],           // prompts spoken while the agent was working
    _ctrl: null,
    _racers: [],
    model: 'zyraxon-titan',
    character: 'girlfriend',
    healthOrder: [],
    stalled: new Set(),
    attachments: [],
    sequential: false,     // relay mode: each round runs on the next model
    _seqIndex: 0,

    async init() {
      try { Agent.systemPrompt = await (await fetch(SYSTEM_PROMPT_URL)).text(); }
      catch { Agent.systemPrompt = 'You are ZYRAXON, a mobile AI assistant. Answer in the language the user speaks. Use tools to really do the work. Never stop while todos remain.'; }
      const saved = await global.ZY.DB.recentMessages(24);
      Agent.history = saved
        .filter((m) => m.role === 'user' || m.role === 'assistant')
        .map((m) => ({ role: m.role, content: m.content }));
      Agent.model = global.ZY.store.get('model', 'zyraxon-titan');
      Agent.character = global.ZY.store.get('character', 'girlfriend');
      // The user does not want the relay: the model they pick handles the whole
      // task, media included, so no other model ever chips in with "I cannot
      // see the video". The toggle still exists for anyone who wants it.
      Agent.sequential = global.ZY.store.get('sequential', false) === true;
      return Agent;
    },

    setModel(id, explicit) { Agent.model = id; global.ZY.store.set('model', id); if (explicit) global.ZY.store.set('userModel', id); },

    /** Sequential multi-model: every round hands the same task to the next
     *  model in the healthy order, so all ten really work one after another on
     *  one conversation. The shared history is what makes it a relay, not a
     *  set of separate answers: each model sees the previous model's tool
     *  results and continues from there. */
    setSequential(on) {
      Agent.sequential = !!on;
      global.ZY.store.set('sequential', !!on);
      Agent._seqIndex = 0;
      return Agent.sequential;
    },

    /** The model for this round, advancing through the chain when in relay mode. */
    _modelForRound(round) {
      if (!Agent.sequential) return Agent.model;
      const live = (Agent.healthOrder && Agent.healthOrder.length) ? Agent.healthOrder : [];
      const rest = (live.length ? live : ['zyraxon-apex', 'zyraxon-matrix', 'zyraxon-pulse', 'zyraxon-titan', 'zyraxon-codex'])
        .filter((m) => m !== Agent.model);
      // The user's own pick leads the relay, then the healthy pool follows, so
      // the selected model is never skipped and all of them still take a turn.
      const list = [Agent.model, ...rest];
      return list[(round - 1) % list.length] || Agent.model;
    },
    stop() {
      Agent.aborted = true;
      for (const r of Agent._racers || []) { try { r.ctrl.abort(); } catch {} }
      if (Agent._ctrl) try { Agent._ctrl.abort(); } catch {}
      global.ZY.VOICE.TTS.stop();
    },

    /** Build the full system prompt: base rules + active character + memory + skills. */
    async buildSystem() {
      const ch = global.ZY.characters[Agent.character] || global.ZY.characters.girlfriend;
      const mem = await global.ZY.DB.recall();
      const skills = global.ZY.Tools.names().join(', ');
      const mode = global.ZY.Tools.native.available()
        ? 'Device bridge: ON (real app launch and screen control available)'
        : 'Device bridge: not present yet — only browser-real tools work; open_app falls back to an intent/web link, and tap/swipe needs the APK (say that honestly, never claim something you did not do).';
      const memText = mem.length
        ? mem.slice(-40).map((m) => `- ${m.key}: ${m.value}`).join('\n')
        : '(nothing remembered yet)';
      const plat = global.ZY.Platform ? global.ZY.Platform.describe() : '- প্ল্যাটফর্ম অজানা';
      const call = global.ZY.Call ? global.ZY.Call.promptLine() : '';
      // Language mirroring: reply in the language the user spoke, and lock to
      // the FIRST language of the conversation even if they later switch.
      const uiCode = global.ZY.I18N ? global.ZY.I18N.code : 'en';
      const uiName = { bn: 'Bangla (বাংলা)', en: 'English', hi: 'Hindi', ar: 'Arabic', es: 'Spanish',
                       fr: 'French', pt: 'Portuguese', ru: 'Russian', id: 'Indonesian', tr: 'Turkish',
                       zh: 'Chinese', ja: 'Japanese' }[uiCode] || 'Bangla (বাংলা)';
      const lang = global.ZY.store.get('sessionLang', '') || uiName;
      const langRule = global.ZY.store.get('sessionLang', '')
        ? `The conversation language is already set to **${lang}**. Keep replying in ${lang} even if the user now writes in another language, unless they explicitly ask you to switch.`
        : `Match the language of the user's message. The first language they use becomes the conversation language for the rest of the session. If the user's message is too short or ambiguous to tell, answer in **${lang}**.`;
      return [
        Agent.systemPrompt,
        `\n### Active character\n${ch.rules}`,
        `\n### Where you are really running (the platform block — do not guess, this is the truth)\n${plat}`,
        `\n### The user's durable memory (local DB)\n${memText}`,
        `\n### Available tools\n${skills}`,
        `\n### State of this machine\n${mode}`,
        call ? `\n### Calls\n${call}` : '',
        `\n### Language (highest priority)\n- ${langRule}\n- The interface language is ${uiCode}, and the user's default language is ${uiName}.\n- If the user writes in Bangla, you answer in Bangla — always, every sentence, tool call explanations included. Do not answer in English because your instructions or the tools are written in English.\n- Speak the same language you are answering in. Never switch language mid-answer.\n- Your thinking/reasoning is shown on screen but is NOT spoken, so reason in whatever language is clearest.`,
        `\n### Mandatory\n- When you call a tool, do not also give a final answer in the same turn.\n- Never stop while a todo is pending or in_progress.\n- Never invent a result.\n- Use only the commands/tools for the platform named above; never run another OS's commands.`,
      ].join('\n');
    },

    /** Persist a message locally, forever. */
    async _persist(msg) { await global.ZY.DB.addMessage({ role: msg.role, content: msg.content, attachments: msg.attachments, ts: Date.now() }); },

    /**
     * Build a user message, folding any pending attachments in as OpenAI
     * multimodal content parts. Every ZYRAXON model accepts image, video and
     * audio input, so we forward them verbatim rather than describing them.
     */
    _buildUserMessage(text) {
      const atts = Agent.attachments || [];
      // The background screen watch rides along with the real turn: the user's
      // current screen and the app they are in become context automatically,
      // with no tool call of any kind. A frame only joins if it is still fresh.
      const scr = global.ZY.Screen ? global.ZY.Screen.contextParts() : [];
      if (!atts.length && !scr.length) return { role: 'user', content: text };
      const parts = [];
      if (text) parts.push({ type: 'text', text });
      for (const p of scr) parts.push(p);
      for (const a of atts) {
        if (a.kind === 'image') parts.push({ type: 'image_url', image_url: { url: a.dataUrl } });
        else if (a.kind === 'video') parts.push({ type: 'video_url', video_url: { url: a.dataUrl } });
        else if (a.kind === 'audio') {
          // Some provider revisions accept audio, some silently drop the part.
          // Send it the OpenAI way and name the file, so a model that cannot
          // decode it says so instead of claiming nothing was attached.
          parts.push({ type: 'input_audio', input_audio: { data: a.dataUrl, format: a.format || 'wav' } });
          parts.push({ type: 'text', text: `(সংযুক্ত অডিও ফাইল: ${a.name || 'audio'} — এটি পড়তে না পারলে সৎভাবে বলো যে এই মডেলে অডিও পড়া যাচ্ছে না।)` });
        }
      }
      Agent.attachments = [];
      return { role: 'user', content: parts };
    },

    /**
     * One full user turn: stream → tool calls → execute → repeat until
     * the model stops calling tools AND no todo remains.
     */
    async send(userText, opts) {
      const ui = global.ZY.ui;
      const hidden = !!(opts && opts.hidden);
      // The user can keep talking while the agent works. Rather than dropping
      // the words (or killing the running turn), queue them and inject as the
      // next user message the moment the current tool round completes.
      if (Agent.busy) {
        // New words join the conversation; they do not kill the work. The voice
        // keeps reading what she was already saying — but the instant the reply
        // to these new words begins, the old sentence is cut. The user asked for
        // exactly that: previous speech continues until the new reply starts.
        Agent._stopSpeechOnNextToken = true;
        Agent.pending.push(userText);
        // Enter the live context right away when the tail is safe to append to.
        // It is not safe between "assistant asked for tools" and "tool results
        // arrived" — there a tool message must follow directly — so in that
        // sliver the words wait in `pending` and are drained at the next round.
        const tail = Agent.history[Agent.history.length - 1];
        const unsafe = tail && tail.role === 'assistant' && tail.tool_calls;
        if (!unsafe) Agent.history.push({ role: 'user', content: userText });
        ui && ui.addMessage('user', userText);
        await global.ZY.DB.addMessage({ role: 'user', content: userText, ts: Date.now(), queued: 1 });
        ui && ui.toast(global.ZY.t('toast.noted'));
        global.ZY.VOICE.STT.start();
        return;
      }
      Agent.busy = true; Agent.aborted = false;
      // A fresh turn gets a fresh reasoning bubble; the previous one stays in
      // the transcript but must not be reused or overwritten.
      global.ZY.ui && (global.ZY.ui._thinkMsg = null);
      // Show the thinking bubble at once, before the request even leaves. The
      // user asked to see her thinking the moment they speak — waiting for the
      // first reasoning token left a silent gap that felt like nothing happened.
      if (ui && ui.showThinkingNow) ui.showThinkingNow();
      Agent._userText = userText;
      global.ZY.Monitor && global.ZY.Monitor.begin();
      // Lock the conversation language to the first one the user speaks, so a
      // later switch to English does not drag the assistant out of Bangla.
      if (!global.ZY.store.get('sessionLang', '') && userText) {
        // A local recogniser reports the language it actually heard. Its output
        // can be romanised (Whisper transliterates Bangla), which langOf() would
        // read as English and lock the whole session there — so the reported
        // language wins when there is one.
        const heard = global.ZY.VOICE.STT && global.ZY.VOICE.STT._srvLang;
        const guess = (heard && {
          bn: 'bn-BD', en: 'en-US', hi: 'hi-IN', ar: 'ar-SA', es: 'es-ES', fr: 'fr-FR',
          pt: 'pt-BR', ru: 'ru-RU', id: 'id-ID', tr: 'tr-TR', zh: 'zh-CN', ja: 'ja-JP',
        }[heard]) || (global.ZY.VOICE.TTS.langOf ? global.ZY.VOICE.TTS.langOf(userText) : '');
        const name = { 'bn-BD': 'Bangla (বাংলা)', 'en-US': 'English', 'hi-IN': 'Hindi', 'ar-SA': 'Arabic',
                       'es-ES': 'Spanish', 'fr-FR': 'French', 'pt-BR': 'Portuguese', 'ru-RU': 'Russian',
                       'id-ID': 'Indonesian', 'tr-TR': 'Turkish', 'zh-CN': 'Chinese', 'ja-JP': 'Japanese' }[guess];
        if (name) global.ZY.store.set('sessionLang', name);
      }

      // _buildUserMessage consumes the attachment list, so snapshot it first —
      // otherwise the bubble and the saved message lose the media entirely.
      const sent = (Agent.attachments || []).slice();
      const userMsg = Agent._buildUserMessage(userText);
      Agent.history.push(userMsg);
      // Keep the attachments on disk so a media message still makes sense after
      // a restart, and so the chat bubble can show what was sent.
      await Agent._persist({ role: 'user', content: userText, attachments: sent });
      // A background observation (the screen watch) is context for the model,
      // not something the user typed — it never appears in the transcript.
      if (!hidden) ui && ui.addMessage('user', userText, sent.length ? { attachments: sent } : {});

      try {
        // No round cap. The loop runs until the work is done *and verified*, or
        // until the user cancels, or until the model is provably stuck.
        let round = 0;
        // Nothing has changed yet. This starts false on purpose: it used to be
        // true, which meant a plain conversation — no tools at all — was judged
        // "you changed something, go verify it", so the model was pushed round
        // after round and the whole answer was re-spoken each time. That was the
        // repeating voice and the long delay, not the network.
        let dirty = false;     // has anything changed since the last check?
        let verified = false;  // has a read-only tool succeeded since the change?
        let lastSig = '';      // detects a model that repeats itself
        let sameSig = 0;
        for (;;) {
          if (Agent.aborted) break;
          round++;
          // Drain anything the user said while we were working that could not
          // be appended inline (see the busy branch in send()).
          while (Agent.pending.length) {
            const extra = Agent.pending.shift();
            if (Agent.history[Agent.history.length - 1] && Agent.history[Agent.history.length - 1].content === extra
                && Agent.history[Agent.history.length - 1].role === 'user') continue;
            const m = { role: 'user', content: extra };
            Agent.history.push(m);
            await Agent._persist(m);
          }
          const system = await Agent.buildSystem();
          // A background frame that arrived while we were working joins the
          // running conversation as real context. The user never sees a bubble
          // and never had to ask — she simply always knows where they are.
          const live = global.ZY.Screen ? global.ZY.Screen.contextParts() : [];
          if (live.length) {
            const m = { role: 'user', content: live };
            Agent.history.push(m);
            await Agent._persist(m);
          }
          const messages = [{ role: 'system', content: system }, ...Agent._window()];
          // In relay mode each round runs on a different model, sharing one
          // history — so all models genuinely work together in sequence.
          const roundModel = Agent._modelForRound(round);
          Agent._lastModel = roundModel;
          const { content, toolCalls, reasoning } = await Agent._stream(messages, { model: roundModel });
          if (content || toolCalls.length) { Agent.stalled.delete(roundModel); Agent.stalled.delete(Agent.model); }

          if (reasoning && ui) ui.setThinking(reasoning);

          // A model that produces the same round twice in a row is stuck; that,
          // not a fixed cap, is the honest reason to stop looping.
          const sig = (content || '') + '|' + toolCalls.map((t) => t.function.name + ':' + (t.function.arguments || '')).join(',');
          if (sig && sig === lastSig) { sameSig++; } else { sameSig = 0; lastSig = sig; }
          if (sameSig >= STUCK_ROUNDS) {
            ui && ui.toast('একই ধাপ বারবার আসছে — থামছি, তুমি একবার বলো কী বদলাবো');
            break;
          }

          const assistant = { role: 'assistant', content: content || '' };
          if (toolCalls.length) assistant.tool_calls = toolCalls;
          Agent.history.push(assistant);
          // The tool names are stored in their own field, never appended to the
          // words. Gluing them into content put "[টুল: …]" inside the bubble and
          // brought it back on every restart, so the chat filled with tool
          // names the user had already read in the Tools panel.
          await Agent._persist({
            role: 'assistant',
            content: content || '',
            tools: toolCalls.map((t) => t.function.name),
          });

          if (!toolCalls.length) {
            // No tools this round. Enforce the todo contract *and* the
            // verification contract: the work is not finished just because the
            // model says so — something must have really checked it.
            const todos = global.ZY.store.get('todos', []) || [];
            const open = todos.filter((t) => t.status !== 'done');
            const cancelled = /শেষ|ক্যান্সেল|থামো|cancel|stop|done/i.test(userText);
            if (cancelled) break;
            if (open.length) {
              Agent.history.push({ role: 'user', content: `[system] ${open.length} todo(s) are still open: ${open.map((t) => t.title).join(', ')}. Do not stop — make the next tool call.` });
              continue;
            }
            if (dirty && !verified) {
              // Nothing has been checked since the last change. Do not accept
              // "it works" as proof — ask for the real read-back.
              Agent.history.push({ role: 'user', content: '[system] তুমি কিছু বদলেছ কিন্তু এখনো যাচাই করো নি। এখনই একটা read-only টুল চালাও (read_file / read_url / read_screen / screen_shot / list_dir) এবং আসল ফলাফল দেখাও। প্রমাণ ছাড়া "হয়ে গেছে" বলতে পারবে না।' });
              continue;
            }
            break;
          }

          // Execute tool calls (parallel when independent).
          const results = await Agent._runTools(toolCalls);
          for (const r of results) {
            Agent.history.push({ role: 'tool', tool_call_id: r.id, content: Agent._clipResult(r.result) });
          }
          // Verification bookkeeping: a real change makes the work dirty again;
          // a read-only tool that succeeds is the proof we were waiting for.
          for (const r of results) {
            const name = r.name || '';
            const ok = !(r.result && r.result.ok === false);
            if (MUTATING.test(name) && ok) { dirty = true; verified = false; }
            else if (VERIFYING.test(name) && ok) { verified = true; }
          }
          // A screenshot or a read image is only useful if the model can SEE it.
          // Tool results are text, so the picture is re-sent as a real image
          // part — that is what lets the assistant look at the screen and
          // decide the next step.
          const seen = results
            .map((r) => r.result && r.result.data && r.result.data.dataUrl && r.result.data)
            .filter(Boolean);
          if (seen.length && !Agent.aborted) {
            const parts = [{ type: 'text', text: 'ছবিটি দেখো এবং পরের ধাপ ঠিক করো।' }];
            for (const d of seen) parts.push({ type: 'image_url', image_url: { url: d.dataUrl } });
            Agent.history.push({ role: 'user', content: parts });
          }
        }
      } catch (e) {
        ui && ui.toast('সমস্যা: ' + String(e).slice(0, 90));
        console.error(e);
      } finally {
        Agent.busy = false;
        global.ZY.Monitor && global.ZY.Monitor.end();
        ui && ui.endStream();
        ui && ui.setThinking('');
        global.ZY.VOICE.STT.onState && global.ZY.VOICE.STT.onState('idle');
        // The mic never closes. If the voice is still reading the answer out we
        // wait for it to finish first, otherwise the recogniser instantly hears
        // the assistant and treats its own words as the next prompt.
        if (global.ZY.store.get('alwaysMic', true) && global.ZY.VOICE.STT.available()) {
          const arm = () => { if (!Agent.busy) global.ZY.VOICE.STT.start(); };
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
    _window(budget = 48000) {
      const h = Agent.history;
      const out = [];
      let used = 0;
      for (let i = h.length - 1; i >= 0; i--) {
        const m = h[i];
        const size = (typeof m.content === 'string' ? m.content.length : JSON.stringify(m.content || '').length)
          + (m.tool_calls ? JSON.stringify(m.tool_calls).length : 0);
        // Always keep the most recent turns even if a single one is oversized,
        // otherwise the window can collapse to nothing.
        if (used + size > budget && out.length >= 2) break;
        used += size;
        out.unshift(m);
      }
      // Never begin on an orphan tool result: drop leading tool messages and
      // any trailing assistant tool_calls whose results fell outside.
      while (out.length && out[0].role === 'tool') out.shift();
      while (out.length && out[out.length - 1].role === 'assistant' && out[out.length - 1].tool_calls) out.pop();
      return out;
    },

    /**
     * A tool result goes back into the model's context, but a 40KB file dump
     * must not: it burns the budget, slows every later round, and the model
     * never needs the whole thing again. Keep a useful head and a clear marker.
     */
    _clipResult(r) {
      const CAP = 2000;
      // Never send a base64 blob back as text: the image itself goes to the
      // model as a real image part on the next turn (see _runTools).
      const strip = (o) => {
        if (Array.isArray(o)) return o.map(strip);
        if (o && typeof o === 'object') {
          const out = {};
          for (const [k, v] of Object.entries(o)) {
            if (typeof v === 'string' && (v.startsWith('data:') || v.length > 4000)) out[k] = `<${v.length} chars omitted>`;
            else out[k] = strip(v);
          }
          return out;
        }
        return o;
      };
      const text = typeof r === 'string' ? r : JSON.stringify(strip(r));
      if (text.length <= CAP) return text;
      return text.slice(0, CAP) + `… [truncated ${text.length - CAP} more characters — re-read a specific part if you need it]`;
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
      const canImage = caps.includes('image');
      const canVideo = caps.includes('video');
      const canAudio = caps.includes('audio');
      if (canImage && canVideo && canAudio) return messages;
      const note = (kind) => `(একটি ${kind} সংযুক্ত ছিল; এই মডেলটি ${kind} ইনপুট সমর্থন করে না — বিষয়টি বর্ণনা করে এগিয়ে যাও, "কিছু দেখছি না" বলে থেমো না।)`;
      return messages.map((m) => {
        if (!Array.isArray(m.content)) return m;
        const parts = [];
        for (const p of m.content) {
          if (p.type === 'image_url') { if (canImage) parts.push(p); else parts.push({ type: 'text', text: note('ছবি') }); }
          else if (p.type === 'video_url') { if (canVideo) parts.push(p); else parts.push({ type: 'text', text: note('ভিডিও') }); }
          else if (p.type === 'input_audio') { if (canAudio) parts.push(p); else parts.push({ type: 'text', text: note('অডিও') }); }
          else parts.push(p);
        }
        return { ...m, content: parts.length ? parts : [{ type: 'text', text: '(সংযুক্ত মিডিয়া বাদ দেওয়া হলো)' }] };
      });
    },

    /** Which input kinds a model really accepts, from the live catalogue. */
    _capsOf(id) {
      const list = (global.ZY.App && global.ZY.App.models) || [];
      const m = list.find((x) => x.id === id);
      const caps = (m && m.input_modalities) || ['text'];
      return caps.map((c) => String(c).toLowerCase());
    },

    /**
     * Which input kinds this turn actually carries (image / video / audio), so
     * only a model that really accepts them is ever asked to handle it.
     */
    _needModalities(messages) {
      const need = new Set();
      for (const m of messages) {
        if (!Array.isArray(m.content)) continue;
        for (const p of m.content) {
          if (p.type === 'image_url') need.add('image');
          else if (p.type === 'video_url') need.add('video');
          else if (p.type === 'input_audio') need.add('audio');
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
      const all = ((global.ZY.App && global.ZY.App.models) || []).map((m) => m.id);
      const order = (Agent.healthOrder && Agent.healthOrder.length) ? Agent.healthOrder : all;
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
      const useProxy = global.ZY.store.get('proxy', 'proxy') === 'proxy';
      const url = useProxy ? '/api/chat' : 'https://zyraxonai.lovable.app/api/public/zyraxon/v1/chat/completions';

      let chain = (opts.chain || Agent._chain(opts.model)).filter((m) => Agent._canTake(m, need));
      if (!chain.length) chain = Agent._mediaCapable(need);

      // Ten minutes, as the user asked: a slow connection must not be treated
      // as a failure. Only then is another model tried.
      const hedgeMs = Math.max(1200, Number(global.ZY.store.get('hedgeMs', 600000)) || 600000);
      const racers = [];
      let aborted = false;

      const mkRacer = async (model) => {
        const ctrl = new AbortController();
        const res = await fetch(url, {
          method: 'POST', headers: { 'Content-Type': 'application/json' },
          body: JSON.stringify({
            model,
            messages: Agent._fitMessages(messages, model),
            tools: global.ZY.Tools.apiTools(),
            tool_choice: 'auto', stream: true, max_tokens: 8000, temperature: 0.7,
          }),
          signal: ctrl.signal,
        });
        if (!res.ok || !res.body) throw new Error('HTTP ' + res.status);
        return { model, ctrl, reader: res.body.getReader(), buf: '', pre: [], lastByteAt: Date.now() };
      };

      // Read until the first real event; the events read are kept so they can be
      // replayed through the normal handler once this racer wins.
      const raceOne = async (r) => {
        const dec = new TextDecoder();
        for (;;) {
          const { done, value } = await r.reader.read();
          if (done) return { r, ok: false };
          r.lastByteAt = Date.now();
          r.buf += dec.decode(value, { stream: true });
          const parts = r.buf.split('\n');
          r.buf = parts.pop();
          for (const line of parts) {
            const s = line.trim();
            if (!s.startsWith('data:')) continue;
            const payload = s.slice(5).trim();
            if (!payload) continue;
            let j; try { j = JSON.parse(payload); } catch { continue; }
            r.pre.push(j);
            return { r, ok: true };
          }
        }
      };

      // Fire the first model immediately, then widen the race on each timeout.
      const launch = (model) => {
        const p = mkRacer(model).then((r) => { racers.push(r); return raceOne(r); });
        return p.catch(() => ({ r: null, ok: false }));
      };
      const tagged = [launch(chain[0]).then((v) => ({ p: null, v }))];
      const timers = [];
      const widen = (i) => {
        if (aborted || i >= chain.length || i >= 3) return;
        ui && ui.toast(`${chain[i - 1].replace('zyraxon-', '')} দেরি করছে — ${chain[i].replace('zyraxon-', '')} কেও ডাকলাম`);
        const pr = launch(chain[i]);
        const t = pr.then((v) => ({ p: t, v }));
        tagged.push(t);
        timers.push(setTimeout(() => widen(i + 1), hedgeMs));
      };
      timers.push(setTimeout(() => widen(1), hedgeMs));

      // Whichever model answers first wins; a racer that dies without a single
      // byte is dropped and we keep waiting on the rest.
      // Only after the full ten minutes with not one byte from anyone does the
      // round stop waiting. Any racer that actually errors is reported as-is.
      const GIVEUP = new Promise((res) => setTimeout(() => res('__giveup__'), hedgeMs * chain.length + 30000));
      let winner = null;
      const live = tagged.slice();
      while (live.length && !winner && !Agent.aborted) {
        const w = await Promise.race([...live, GIVEUP]);
        if (w === '__giveup__') break;
        live.splice(live.indexOf(w), 1);
        if (w.v && w.v.ok) winner = w.v.r;
      }
      timers.forEach(clearTimeout);
      Agent._racers = racers;
      if (!winner) { ui && ui.toast('মডেল সাড়া দিচ্ছে না — আবার চেষ্টা করো'); return { content: '', toolCalls: [], reasoning: '', model: chain[0] }; }

      const model = winner.model;
      Agent._lastModel = model;
      // Every other racer is cancelled at once, so nothing it was saying can
      // arrive later and contradict the winner.
      for (const r of racers) if (r !== winner) { try { r.ctrl.abort(); } catch {} }
      ui && ui.markSent && ui.markSent();

      let content = '', reasoning = '';
      const calls = [];
      let sawAny = false;
      ui && ui.startStream();
      // Each round speaks its own reply. Without this the "spoken up to here"
      // pointer still held the previous round's length, so every round after the
      // first had its speech silently suppressed — she answered on screen but
      // said nothing out loud.
      global.ZY.VOICE.TTS.resetLive();
      ui && ui.setBadge('সংযোগ হচ্ছে…');

      const onEvent = (j) => {
        if (j.error) throw new Error(j.error.message || 'stream error');
        if (j.zyraxon === 'reset') { calls.length = 0; content = ''; global.ZY.VOICE.TTS.resetLive(); return; }
        const d = (j.choices && j.choices[0] && j.choices[0].delta) || {};
        // The first words of the reply to the user's *newest* words are the cue
        // to cut whatever she was still reading from before. Until this moment
        // the earlier sentence kept playing, exactly as asked.
        if (d.content && Agent._stopSpeechOnNextToken && !content) {
          Agent._stopSpeechOnNextToken = false;
          try { global.ZY.VOICE.TTS.stop(); } catch {}
        }
        // The user asked to *hear* the reasoning, not just see it.
        if (d.reasoning_content) {
          reasoning += d.reasoning_content;
          ui && ui.setThinking(reasoning);
          global.ZY.VOICE.TTS.reason(reasoning);
        }
        if (d.content) {
          sawAny = true;
          content += d.content;
          // Hard politeness guarantee: the user is never addressed as "তুই".
          const polite = global.ZY.VOICE.TTS.politeStream ? global.ZY.VOICE.TTS.politeStream(content) : content;
          if (polite !== content) { content = polite; ui && ui.setStream(content); }
          else ui && ui.streamToken(d.content);
          // Real streaming speech: each finished sentence is queued the moment
          // it arrives, so the voice starts before the answer ends.
          global.ZY.VOICE.TTS.feed(content);
        }
        if (d.tool_calls) {
          sawAny = true;
          for (const tc of d.tool_calls) {
            const idx = tc.index == null ? 0 : tc.index;
            calls[idx] = calls[idx] || { id: '', type: 'function', function: { name: '', arguments: '' } };
            if (tc.id) calls[idx].id = tc.id;
            if (tc.function && tc.function.name) calls[idx].function.name = tc.function.name;
            if (tc.function && tc.function.arguments) calls[idx].function.arguments += tc.function.arguments;
          }
        }
      };

      // Replay what the race already read, then keep reading to the end.
      let finished = false;
      const guard = setInterval(() => {
        if (Agent.aborted || finished) return;
        const idle = Date.now() - winner.lastByteAt;
        const midArgs = calls.some((c) => c && c.function && c.function.name && !isJson(c.function.arguments));
        if (idle > (sawAny ? (midArgs ? 45000 : 30000) : 60000)) { finished = true; try { winner.reader.cancel(); } catch {} }
      }, 1000);

      try {
        for (const j of winner.pre) onEvent(j);
        const dec = new TextDecoder();
        for (;;) {
          const { done, value } = await winner.reader.read();
          if (done) break;
          winner.lastByteAt = Date.now();
          winner.buf += dec.decode(value, { stream: true });
          const parts = winner.buf.split('\n');
          winner.buf = parts.pop();
          for (const line of parts) {
            const s = line.trim();
            if (!s.startsWith('data:')) continue;
            const payload = s.slice(5).trim();
            if (!payload) continue;
            let j; try { j = JSON.parse(payload); } catch { continue; }
            onEvent(j);
          }
        }
      } catch (e) {
        if (!Agent.aborted && !finished) throw e;
      } finally { clearInterval(guard); finished = true; }

      // Speak the tail of the answer too, and only once — feed() already queued
      // every complete sentence.
      if (sawAny) {
        if (global.ZY.VOICE.TTS.polite) content = global.ZY.VOICE.TTS.polite(content);
        global.ZY.VOICE.TTS.flush();
      }
      // A tool round is not the end of the turn: keep the live badge so the UI
      // does not blink back to 'প্রস্তুত' while the tools are still running.
      ui && ui.endStream(calls.length > 0);
      if (ui && calls.length && !content) ui.setBadge('টুল চালাচ্ছি…');

      // Sanitise tool calls: a truncated argument string must never be echoed
      // back upstream (the provider rejects the whole turn with a 500).
      const clean = calls.filter((c) => c && c.function && c.function.name).map((c) => {
        if (!isJson(c.function.arguments)) { c._bad = true; c.function.arguments = '{}'; }
        return c;
      });
      return { content: content.trim(), toolCalls: clean, reasoning, model };
    },

    /** Model fallback chain: user's pick first, then live health order. */
    _chain(preferred) {
      const pref = preferred || Agent.model;
      const live = Agent.healthOrder || [];
      const base = live.length ? live : ['zyraxon-apex', 'zyraxon-visionary', 'zyraxon-titan', 'zyraxon-pulse', 'zyraxon-codex'];
      const rest = base.filter((m) => m !== pref);
      // if the preferred model is known-stalled, lead with the fastest healthy one
      const stalled = Agent.stalled.has(pref);
      const out = stalled && rest.length ? [rest[0], ...rest.slice(1), pref] : [pref, ...rest];
      return [...new Set(out)].slice(0, 4);
    },

    /** Ask the server which pools are healthy right now (non-blocking). */
    async refreshHealth() {
      try {
        const r = await fetch('/api/model-health');
        const j = await r.json();
        if (j.order) Agent.healthOrder = j.order;
        Agent.stalled.clear();
        (j.results || []).forEach((x) => { if (!x.ok) Agent.stalled.add(x.model); });
      } catch {}
    },

    async _runTools(toolCalls) {
      const ui = global.ZY.ui;
      const run = async (tc) => {
        const name = tc.function.name;
        let args = {};
        try { args = JSON.parse(tc.function.arguments || '{}'); } catch { args = {}; }
        if (tc._bad) {
          const err = { ok: false, summary: `${name}: আর্গুমেন্ট অসম্পূর্ণ এসেছে — ছোট করে আবার কল করো` };
          ui && ui.addToolRow(name, {}, 'err', err);
          return { id: tc.id, name, result: err };
        }
        ui && ui.addToolRow(name, args, 'running');
        if (name === 'open_app' || name === 'launch_url') await new Promise((r) => setTimeout(r, 200));
        const r = await global.ZY.Tools.execute(name, args);
        ui && ui.addToolRow(name, args, r.ok ? 'ok' : 'err', r);
        if (!ui) return { id: tc.id, name, result: r };
        // Surface what the tool really produced, in the panel above the chat:
        // the live site URL, the file that was just written, or media it read.
        if (r.ok && r.data && r.data.url && name === 'publish_website') {
          ui.showArtifact('Live site: ' + r.data.url, r.data.url);
          // The user asked for the finished thing to open by itself.
          ui.toast('Site published — opening it');
          global.open(r.data.url, '_blank');
        } else if (r.ok && typeof args.content === 'string' && (name === 'write_file' || name === 'generate_file')) {
          // The file is revealed line by line in the live code box, and the
          // finished text also lands in the output stack so it is never lost.
          const p = args.path || 'file';
          const lang = (String(p).split('.').pop() || 'code').toLowerCase();
          ui.typeCodeBox('Writing ' + p, args.content, lang);
          ui.showArtifact('File · ' + p, args.content);
        } else if (r.ok && typeof args.content === 'string' && name === 'edit_file') {
          ui.showArtifact('Edit · ' + (args.path || 'file'), String(args.replace == null ? '' : args.replace));
        } else if (r.ok && r.data && r.data.dataUrl) {
          ui.showArtifact('Media read: ' + r.data.modality, r.data.dataUrl, r.data.modality);
        } else if (r.ok && r.data && r.data.content && (name === 'read_file' || name === 'read_url')) {
          ui.showArtifact(name + ' · ' + (args.path || args.url || ''), String(r.data.content).slice(0, 4000));
        } else if (name === 'run_shell' && r.ok && r.data && r.data.stdout) {
          ui.showArtifact('shell · ' + (args.command || '').slice(0, 60), r.data.stdout);
        }
        if (Args_persist(name)) {
          const todos = global.ZY.store.get('todos', []) || [];
          const open = todos.filter((t) => t.status !== 'done').length;
          if (open) ui && ui.renderTodos(todos);
        }
        // The second model watches the real result, not the assistant's claim.
        global.ZY.Monitor && global.ZY.Monitor.afterTool(name, args, r, Agent._userText);
        return { id: tc.id, name, result: r };
      };
      return Promise.all(toolCalls.map(run));
    },
  };

  function Args_persist(name) { return name === 'todo_write'; }
  function isJson(s) {
    if (typeof s !== 'string' || !s.trim()) return false;
    try { JSON.parse(s); return true; } catch { return false; }
  }
  global.ZY = global.ZY || {};
  global.ZY.Agent = Agent;
})(window);
