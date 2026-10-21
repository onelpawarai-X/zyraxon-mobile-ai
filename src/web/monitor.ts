/* ══════════════════════════════════════════════════════════════
   ZYRAXON Monitor — a second model that watches the working model.

   The user asked for a genuine two-model system: while the main model
   builds, a different model inspects what it actually did (the real tool
   results, never its own claims) and reports progress out loud. It runs on
   the provider's own multi-model pools, so the two requests really are
   separate models — nothing here is simulated.

   It is deliberately quiet: a short report every few tool calls, and it
   never speaks when it has nothing new to say.
   ══════════════════════════════════════════════════════════════ */
(function (global) {
  'use strict';

  const Monitor: any = {
    // Off by default. A second model narrating the first model's work is what
    // produced the confusing "I cannot see the video" lines being spoken over
    // the real answer. The user wants one model doing the job; the watcher is
    // opt-in only.
    enabled: false,
    watching: false,
    _busy: false,
    _lastAt: 0,
    _said: [],
    _count: 0,
    model: '',
    report: '',

    /** A model that is never the one currently doing the work. */
    pick(workingModel) {
      const fast = global.ZY.Agent.healthOrder && global.ZY.Agent.healthOrder.length
        ? global.ZY.Agent.healthOrder
        : ['zyraxon-pulse', 'zyraxon-apex', 'zyraxon-matrix', 'zyraxon-genesis', 'zyraxon-titan'];
      const other = fast.find((m) => m !== workingModel);
      return other || 'zyraxon-matrix';
    },

    /** Reset at the start of every user turn. */
    begin() {
      Monitor._said = [];
      Monitor._count = 0;
      Monitor.report = '';
      Monitor._lastAt = Date.now();
      Monitor.watching = true;
    },

    /**
     * Called after a real tool ran. Sends the *result* to the monitor model,
     * which reports progress in the user's language. Speaking is left to the
     * normal TTS path, so the report is heard the moment it is ready.
     */
    async afterTool(name, args, result, userText) {
      if (!Monitor.enabled || !Monitor.watching) return;
      Monitor._count++;
      // First call gives an immediate "started" line; then report every 3rd
      // tool so it stays useful instead of drowning the user in chatter.
      if (Monitor._count > 1 && Monitor._count % 3 !== 0) return;
      if (Monitor._busy) return;
      Monitor._busy = true;
      try {
        const lang = global.ZY.store.get('sessionLang', '') || 'the language the user is speaking';
        const digest = JSON.stringify(result).slice(0, 900);
        const body = {
          model: Monitor.model || (Monitor.model = Monitor.pick(global.ZY.Agent._lastModel || global.ZY.Agent.model)),
          messages: [
            { role: 'system', content:
              `You are the monitoring model of a two-model system. Another model is doing the work. ` +
              `You are given the user's request and the REAL result of a tool it just ran. ` +
              `Report progress to the user in ${lang}: one short spoken sentence, plain language, no code, ` +
              `no JSON, no tool names, no markdown, no emoji. Say what actually happened. ` +
              `If the result is an error, say plainly what failed and what the other model will do next. ` +
              `Never claim something the result does not show. Reply with the sentence only.` },
            { role: 'user', content:
              `User asked: ${String(userText || '').slice(0, 300)}\n` +
              `Tool that just ran: ${name}\nArguments: ${JSON.stringify(args).slice(0, 300)}\n` +
              `Real result: ${digest}` },
          ],
          stream: false,
          max_tokens: 160,
          temperature: 0.4,
        };
        const useProxy = global.ZY.store.get('proxy', 'proxy') === 'proxy';
        const url = useProxy ? '/api/chat' : 'https://zyraxonai.lovable.app/api/public/zyraxon/v1/chat/completions';
        const r = await fetch(url, { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify(body) });
        const j = await r.json();
        const line = ((j.choices && j.choices[0] && j.choices[0].message && j.choices[0].message.content) || '').trim();
        if (!line || line === Monitor._said[Monitor._said.length - 1]) return;
        Monitor._said.push(line);
        Monitor.report = line;
        Monitor._lastAt = Date.now();
        if (global.ZY.ui && global.ZY.ui.setMonitor) global.ZY.ui.setMonitor(line);
        // Speak it through the same voice as the assistant, in its own language.
        if (global.ZY.store.get('monitorVoice', false)) global.ZY.VOICE.TTS.push(line);
      } catch { /* a monitor that fails must never break the real work */ }
      finally { Monitor._busy = false; }
    },

    end() { Monitor.watching = false; },
  };

  global.ZY = global.ZY || {};
  global.ZY.Monitor = Monitor;
})(window);
