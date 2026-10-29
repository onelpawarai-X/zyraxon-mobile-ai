(() => {
  (function(global) {
    "use strict";
    const KNOWN = [
      { id: "opencode", name: "OpenCode (অফিসিয়াল সার্ভার)", hint: "অফিসিয়াল ওপেন কোড সার্ভার সাইট থেকে লাইভ মডেল। ফ্রি সার্ভার লিমিট থাকলে নিজের ওপেন কোড কী বসাতে পারেন।", needsKey: true },
      { id: "gemini", name: "Google Gemini (সুপারফাস্ট ইঞ্জিন)", hint: "স্বয়ংক্রিয়ভাবে অ্যাক্টিভ। দ্রুততম লাইভ রেসপন্স ও মাল্টিমোডাল টুলস।", needsKey: true },
      { id: "anthropic", name: "Anthropic (Claude 3.7 / 3.5)", hint: "console.anthropic.com → API keys। সেরা কোডিং ও রিজন।", needsKey: true },
      { id: "groq", name: "Groq (সুপারফাস্ট Llama 3.3)", hint: "console.groq.com → API keys। ৩০০+ টোকেন/সেকেন্ড।", needsKey: true },
      { id: "openrouter", name: "OpenRouter (মাল্টি-মডেল)", hint: "openrouter.ai → Keys → নতুন কী।", needsKey: true },
      { id: "deepseek", name: "DeepSeek (V3 ও R1)", hint: "platform.deepseek.com → API keys।", needsKey: true },
      { id: "openai", name: "OpenAI (GPT-4o)", hint: "platform.openai.com → API keys।", needsKey: true }
    ];

    // No hardcoded catalogue. The list always comes live from the server.
    const DEFAULT_CATALOGUE = [];

    const KEYS_STORE = "providerKeys";
    const P = {
      list: KNOWN,
      _openCodeModelIds: new Set(),
      /** Every saved key, as { providerId: key }. Only non-empty ones. */
      keys() {
        const raw = global.ZY.store.get(KEYS_STORE, {}) || {};
        const out = {};
        for (const k of Object.keys(raw)) if (raw[k]) out[k] = raw[k];
        // Auto-inject Gemini key from native build if not manually set
        if (!out.gemini && global.ZYNative && typeof global.ZYNative.getGeminiApiKey === "function") {
          const k = global.ZYNative.getGeminiApiKey();
          if (k) out.gemini = k;
        }
        return out;
      },
      key(id) {
        const keys = P.keys();
        return keys[id] || "";
      },
      setKey(id, key) {
        const raw = Object.assign({}, global.ZY.store.get(KEYS_STORE, {}) || {});
        if (key) raw[id] = String(key).trim();
        else delete raw[id];
        global.ZY.store.set(KEYS_STORE, raw);
        return raw[id] || "";
      },
      connected(id) {
        return (id === "opencode" || id === "gemini") ? true : !!P.key(id);
      },
      /** The provider a turn should go to: the user's choice, else OpenCode. */
      active() {
        const want = global.ZY.store.get("provider", "opencode");
        if (want !== "opencode" && !P.key(want)) return "opencode";
        return want;
      },
      setActive(id) {
        global.ZY.store.set("provider", id);
        return id;
      },
      requestExtras(model) {
        const P2 = global.ZY.PROVIDERS;
        let provider = P2.active();
        const m = (global.ZY.App && global.ZY.App.models || []).find((x) => x.id === model);
        if (m && m.provider && (m.provider === "opencode" || m.provider === "zyraxon" || P2.key(m.provider))) {
          provider = m.provider;
        }
        return { provider, providers: P2.keys() };
      },
      buildRequest(model, messages, tools) {
        const P2 = global.ZY.PROVIDERS;
        const wantProxy = global.ZY.store.get("proxy", "proxy") === "proxy";
        const useProxy = wantProxy && !!(global.ZY.Platform && global.ZY.Platform.hasServer && global.ZY.Platform.hasServer());
        if (useProxy) {
          return {
            url: "/api/chat",
            headers: { "Content-Type": "application/json" },
            body: Object.assign({
              model,
              messages,
              tools,
              tool_choice: "auto",
              stream: true,
              max_tokens: 8000,
              temperature: 0.7
            }, P2.requestExtras(model))
          };
        }

        const geminiKey = P2.key("gemini");
        const makeGeminiReq = (gModel = "gemini-2.5-flash") => ({
          url: "https://generativelanguage.googleapis.com/v1beta/openai/chat/completions",
          headers: Object.assign({
            "Content-Type": "application/json",
            "Accept": "text/event-stream"
          }, geminiKey ? { "Authorization": "Bearer " + geminiKey } : {}),
          body: {
            model: gModel,
            messages,
            tools,
            tool_choice: "auto",
            stream: true,
            max_tokens: 8000,
            temperature: 0.7
          }
        });

        // Direct mobile/client dispatch:
        // 1. ZYRAXON Master Models (Powered by Gemini 2.5 Flash Engine)
        if (model.startsWith("zyraxon-")) {
          return makeGeminiReq("gemini-2.5-flash");
        }

        // 2. OpenCode models (Direct Official OpenCode Server with Instant Gemini Fallback)
        let provider = P2.active();
        const mMeta = (global.ZY.App && global.ZY.App.models || []).find((x) => x.id === model);
        if (mMeta && mMeta.provider) provider = mMeta.provider;

        if (model.startsWith("opencode/") || model.endsWith("-free") || provider === "opencode" || (P._openCodeModelIds && P._openCodeModelIds.has(model))) {
          const cleanModel = model.replace(/^opencode\//, "");
          const ocTools = (tools || []).slice();
          if (!ocTools.some((t) => t && t.function && t.function.name === "read")) {
            ocTools.push({
              type: "function",
              function: {
                name: "read",
                description: "Read a file (alias of read_file).",
                parameters: {
                  type: "object",
                  properties: { path: { type: "string" } },
                  required: ["path"]
                }
              }
            });
          }
          const CHARS = "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz";
          const timestamp = Date.now();
          const current = BigInt(timestamp) * 0x1000n + BigInt(Math.floor(Math.random() * 1000));
          const value = ~current;
          let time = "";
          for (let i = 0; i < 6; i++) {
            time += Number((value >> BigInt(40 - 8 * i)) & 0xffn).toString(16).padStart(2, "0");
          }
          let tail = "";
          try {
            const bytes = new Uint8Array(14);
            crypto.getRandomValues(bytes);
            for (const b of bytes) tail += CHARS[b % 62];
          } catch {
            for (let i = 0; i < 14; i++) tail += CHARS[Math.floor(Math.random() * 62)];
          }

          const ocHeaders = {
            "Content-Type": "application/json",
            "Accept": "text/event-stream",
            "x-opencode-session": "ses_" + time + tail,
            "x-opencode-client": "cli"
          };
          const ocKey = P2.key("opencode");
          if (ocKey) {
            ocHeaders["Authorization"] = "Bearer " + ocKey;
            ocHeaders["x-api-key"] = ocKey;
          }

          return {
            url: "https://opencode.ai/zen/v1/chat/completions",
            fallbackUrl: "https://opencode.ai/inference/openai/v1/chat/completions",
            fallbackRequest: makeGeminiReq("gemini-2.5-flash"),
            headers: ocHeaders,
            body: {
              model: cleanModel,
              messages,
              tools: ocTools,
              tool_choice: "auto",
              stream: true,
              max_tokens: 8000,
              temperature: 0.7
            }
          };
        }

        // 3. Gemini models
        if (model.startsWith("gemini-")) {
          const key = P2.key("gemini");
          return {
            url: "https://generativelanguage.googleapis.com/v1beta/openai/chat/completions",
            headers: Object.assign({
              "Content-Type": "application/json"
            }, key ? { "Authorization": "Bearer " + key } : {}),
            body: {
              model,
              messages,
              tools,
              tool_choice: "auto",
              stream: true,
              max_tokens: 8000,
              temperature: 0.7
            }
          };
        }

        // 4. OpenRouter models
        if (model.startsWith("openrouter/")) {
          const key = P2.key("openrouter");
          return {
            url: "https://openrouter.ai/api/v1/chat/completions",
            headers: Object.assign({
              "Content-Type": "application/json",
              "HTTP-Referer": "https://zyraxon.ai",
              "X-Title": "ZYRAXON"
            }, key ? { "Authorization": "Bearer " + key } : {}),
            body: {
              model: model.replace(/^openrouter\//, ""),
              messages,
              tools,
              tool_choice: "auto",
              stream: true,
              max_tokens: 8000,
              temperature: 0.7
            }
          };
        }

        // 5. Groq models
        if (model.startsWith("groq/")) {
          const key = P2.key("groq");
          return {
            url: "https://api.groq.com/openai/v1/chat/completions",
            headers: Object.assign({
              "Content-Type": "application/json"
            }, key ? { "Authorization": "Bearer " + key } : {}),
            body: {
              model: model.replace(/^groq\//, ""),
              messages,
              tools,
              tool_choice: "auto",
              stream: true,
              max_tokens: 8000,
              temperature: 0.7
            }
          };
        }

        // 6. DeepSeek models
        if (model.startsWith("deepseek/")) {
          const key = P2.key("deepseek");
          return {
            url: "https://api.deepseek.com/v1/chat/completions",
            headers: Object.assign({
              "Content-Type": "application/json"
            }, key ? { "Authorization": "Bearer " + key } : {}),
            body: {
              model: model.replace(/^deepseek\//, ""),
              messages,
              tools: model.includes("reasoner") ? undefined : tools,
              tool_choice: model.includes("reasoner") ? undefined : "auto",
              stream: true,
              max_tokens: 8000,
              temperature: 0.7
            }
          };
        }

        // 7. OpenAI models
        if (model.startsWith("openai/")) {
          const key = P2.key("openai");
          return {
            url: "https://api.openai.com/v1/chat/completions",
            headers: Object.assign({
              "Content-Type": "application/json"
            }, key ? { "Authorization": "Bearer " + key } : {}),
            body: {
              model: model.replace(/^openai\//, ""),
              messages,
              tools,
              tool_choice: "auto",
              stream: true,
              max_tokens: 8000,
              temperature: 0.7
            }
          };
        }

        // 8. Anthropic Claude models
        if (model.startsWith("claude-")) {
          const openRouterKey = P2.key("openrouter");
          const anthropicKey = P2.key("anthropic");
          if (openRouterKey) {
            return {
              url: "https://openrouter.ai/api/v1/chat/completions",
              headers: {
                "Content-Type": "application/json",
                "Authorization": "Bearer " + openRouterKey,
                "HTTP-Referer": "https://zyraxon.ai",
                "X-Title": "ZYRAXON"
              },
              body: {
                model: "anthropic/" + model,
                messages,
                tools,
                tool_choice: "auto",
                stream: true,
                max_tokens: 8000,
                temperature: 0.7
              }
            };
          }
          if (anthropicKey) {
            return {
              url: "https://openrouter.ai/api/v1/chat/completions",
              headers: {
                "Content-Type": "application/json",
                "Authorization": "Bearer " + anthropicKey
              },
              body: {
                model: "anthropic/" + model,
                messages,
                tools,
                tool_choice: "auto",
                stream: true,
                max_tokens: 8000,
                temperature: 0.7
              }
            };
          }
          return {
            url: "https://opencode.ai/inference/openai/v1/chat/completions",
            headers: {
              "Content-Type": "application/json",
              "Accept": "text/event-stream",
              "x-opencode-client": "cli"
            },
            body: {
              model: model,
              messages,
              tools,
              tool_choice: "auto",
              stream: true,
              max_tokens: 8000,
              temperature: 0.7
            }
          };
        }

        // Default fallback to Gemini 2.5 Flash
        return makeGeminiReq("gemini-2.5-flash");
      },
      /** Format raw model object from official OpenCode server */
      /** Format raw model object from official OpenCode server */
      _formatOpenCodeModel(raw) {
        if (!raw) return null;
        const rawId = String(raw.id || raw.name || "").trim();
        if (!rawId || rawId === "test") return null;
        const cleanId = rawId.replace(/^opencode\//, "");
        const prefixedId = "opencode/" + cleanId;
        P._openCodeModelIds.add(rawId);
        P._openCodeModelIds.add(cleanId);
        P._openCodeModelIds.add(prefixedId);

        const isFree = cleanId.endsWith("-free") || raw.free === true || (raw.owned_by === "opencode" && !cleanId.includes("opus"));

        let displayName = raw.name || raw.display_name || "";
        if (!displayName || displayName === rawId) {
          displayName = cleanId
            .split(/[-_/]/)
            .map((w) => {
              if (/^v\d/i.test(w)) return w.toUpperCase();
              if (/^\d/i.test(w)) return w;
              return w.charAt(0).toUpperCase() + w.slice(1);
            })
            .join(" ")
            .replace(/\bFree\b/gi, "")
            .replace(/\b(Gpt|Oai)\b/gi, "GPT")
            .replace(/\bGemini\b/gi, "Gemini")
            .replace(/\bClaude\b/gi, "Claude")
            .replace(/\bDeepseek\b/gi, "DeepSeek")
            .replace(/\bLlama\b/gi, "Llama")
            .replace(/\bQwen\b/gi, "Qwen")
            .replace(/\bGlm\b/gi, "GLM")
            .replace(/\bMistral\b/gi, "Mistral")
            .replace(/\s+/g, " ")
            .trim();
        }

        const tag = isFree ? " (OpenCode Free)" : " (OpenCode)";
        const finalName = displayName.includes("OpenCode") ? displayName : (displayName + tag);

        return {
          id: prefixedId,
          rawId: cleanId,
          name: finalName,
          category: "OpenCode",
          provider: "opencode",
          free: isFree,
          description: raw.description || `অফিসিয়াল ওপেন কোড সার্ভার সাইট থেকে সরাসরি (${raw.owned_by || "লাইভ মডেল"}) ${isFree ? "— ফ্রি সার্ভার কোটা" : ""}`,
          tool_call: raw.tools !== false && !cleanId.includes("reasoner"),
          input_modalities: (cleanId.includes("flash") || cleanId.includes("vision") || cleanId.includes("vl") || cleanId.includes("super") || cleanId.includes("gpt-5") || cleanId.includes("claude"))
            ? ["text", "image"]
            : ["text"]
        };
      },

      /**
       * Fetch models dynamically from official OpenCode server.
       * Tries Zen endpoint first, then inference OpenAI-compatible endpoint.
       * Falls back to offline store cache if network is temporarily unavailable.
       */
      async fetchOpenCodeModels(forceRefresh = false) {
        const CACHE_KEY = "opencode_live_models_cache_v2";
        const CACHE_TIME_KEY = "opencode_live_models_ts_v2";
        const now = Date.now();

        // Purge old stale cache keys
        try {
          localStorage.removeItem("opencode_live_models_cache");
          localStorage.removeItem("opencode_live_models_ts");
        } catch {}

        const cached = global.ZY.store ? global.ZY.store.get(CACHE_KEY, null) : null;
        const cachedTs = global.ZY.store ? Number(global.ZY.store.get(CACHE_TIME_KEY, 0)) : 0;

        // Return cache if fresh (< 30 minutes) and not forcing refresh
        if (!forceRefresh && Array.isArray(cached) && cached.length && (now - cachedTs < 1800000)) {
          cached.forEach((m) => {
            if (m && m.id) {
              P._openCodeModelIds.add(m.id);
              if (m.rawId) P._openCodeModelIds.add(m.rawId);
            }
          });
          return cached;
        }

        const ocHeaders = {
          "Accept": "application/json",
          "x-opencode-client": "cli"
        };
        const ocKey = P.key("opencode");
        if (ocKey) {
          ocHeaders["Authorization"] = "Bearer " + ocKey;
          ocHeaders["x-api-key"] = ocKey;
        }

        const endpoints = [
          "https://opencode.ai/zen/v1/models",
          "https://opencode.ai/inference/openai/v1/models"
        ];

        let rawModels = [];
        for (const ep of endpoints) {
          try {
            const ctrl = new AbortController();
            const timer = setTimeout(() => ctrl.abort(), 6000);
            const res = await fetch(ep, {
              method: "GET",
              headers: ocHeaders,
              signal: ctrl.signal
            }).finally(() => clearTimeout(timer));

            if (res.ok) {
              const data = await res.json();
              const items = Array.isArray(data)
                ? data
                : (Array.isArray(data.data) ? data.data : (Array.isArray(data.models) ? data.models : []));
              if (items && items.length) {
                rawModels = items;
                break;
              }
            }
          } catch (e) {
            // try next endpoint
          }
        }

        if (rawModels.length) {
          const parsed = [];
          const seen = new Set();
          for (const raw of rawModels) {
            const m = P._formatOpenCodeModel(raw);
            if (m && !seen.has(m.id)) {
              seen.add(m.id);
              parsed.push(m);
            }
          }
          if (parsed.length) {
            if (global.ZY.store) {
              global.ZY.store.set(CACHE_KEY, parsed);
              global.ZY.store.set(CACHE_TIME_KEY, now);
            }
            return parsed;
          }
        }

        // Offline or request failure: use cached models if available
        if (Array.isArray(cached) && cached.length) {
          cached.forEach((m) => {
            if (m && m.id) {
              P._openCodeModelIds.add(m.id);
              if (m.rawId) P._openCodeModelIds.add(m.rawId);
            }
          });
          return cached;
        }

        return [];
      },

      /** Ask the server/local asset for the merged catalogue. */
      async models(forceRefresh = false) {
        const fakeIds = new Set([
          "opencode/gemini-2.0-flash",
          "opencode/gpt-4o-mini",
          "opencode/claude-3-5-haiku",
          "opencode/deepseek-chat",
          "opencode/llama-3.3-70b",
          "opencode/qwen-2.5-coder-32b"
        ]);

        let serverModels = [];
        try {
          const r = await fetch("/api/models", {
            method: "GET",
            headers: { "Content-Type": "application/json" }
          });
          if (r.ok) {
            const j = await r.json();
            if (Array.isArray(j.data) && j.data.length) {
              serverModels = j.data.filter((x) => x && x.id && !fakeIds.has(x.id));
            }
          }
        } catch (e) {
          // fall through
        }

        // Fetch dynamic models directly from official OpenCode server
        let openCodeModels = [];
        try {
          openCodeModels = await P.fetchOpenCodeModels(forceRefresh);
        } catch (e) {
          console.warn("OpenCode models fetch error:", e);
        }

        const merged = [];
        const seen = new Set();
        const add = (m) => {
          if (!m || !m.id || fakeIds.has(m.id) || seen.has(m.id)) return;
          seen.add(m.id);
          merged.push(m);
        };

        // 1. ZYRAXON 10 Core Master Models first
        DEFAULT_CATALOGUE.filter((m) => m.provider === "zyraxon").forEach(add);

        // 2. Official OpenCode Live Server Models second!
        openCodeModels.forEach(add);

        // 3. Any live models returned by backend server (e.g. native Gemini key models)
        serverModels.forEach(add);

        // 4. Live models for connected third-party providers if user provided API keys
        const keys = P.keys();
        if (keys.gemini) {
          try {
            const gr = await fetch(`https://generativelanguage.googleapis.com/v1beta/models?key=${encodeURIComponent(keys.gemini)}`);
            if (gr.ok) {
              const gj = await gr.json();
              if (Array.isArray(gj.models)) {
                gj.models.forEach((gm) => {
                  const mId = (gm.name || "").replace(/^models\//, "");
                  if (!mId || !mId.startsWith("gemini")) return;
                  if (gm.supportedGenerationMethods && !gm.supportedGenerationMethods.includes("generateContent")) return;
                  add({
                    id: mId,
                    name: gm.displayName || mId,
                    category: "Gemini",
                    provider: "gemini",
                    description: gm.description || "Google Gemini Official Live Model",
                    tool_call: true,
                    input_modalities: mId.includes("flash") || mId.includes("pro") ? ["text", "image", "audio", "video"] : ["text"]
                  });
                });
              }
            }
          } catch {}
        }

        if (keys.groq) {
          try {
            const gr = await fetch("https://api.groq.com/openai/v1/models", {
              headers: { "Authorization": "Bearer " + keys.groq }
            });
            if (gr.ok) {
              const gj = await gr.json();
              if (Array.isArray(gj.data)) {
                gj.data.forEach((gm) => {
                  if (!gm || !gm.id) return;
                  add({
                    id: "groq/" + gm.id,
                    name: gm.id + " (Groq)",
                    category: "others",
                    provider: "groq",
                    description: "Groq আল্ট্রাফাস্ট ইনফারেন্স মডেল",
                    tool_call: true,
                    input_modalities: ["text"]
                  });
                });
              }
            }
          } catch {}
        }

        if (keys.openrouter) {
          try {
            const orRes = await fetch("https://openrouter.ai/api/v1/models");
            if (orRes.ok) {
              const orj = await orRes.json();
              if (Array.isArray(orj.data)) {
                orj.data.slice(0, 30).forEach((om) => {
                  if (!om || !om.id) return;
                  add({
                    id: "openrouter/" + om.id,
                    name: (om.name || om.id) + " (OpenRouter)",
                    category: "others",
                    provider: "openrouter",
                    description: om.description || "OpenRouter মাল্টি-মডেল",
                    tool_call: true,
                    input_modalities: (om.architecture && om.architecture.modality && om.architecture.modality.includes("image")) ? ["text", "image"] : ["text"]
                  });
                });
              }
            }
          } catch {}
        }

        if (keys.anthropic) {
          [
            { id: "claude-3-7-sonnet-20250219", name: "Claude 3.7 Sonnet (হাইব্রিড রিজন)", category: "Anthropic", description: "সর্বাধুনিক হাইব্রিড যুক্তি ও নিখুঁত কোড", provider: "anthropic", tool_call: true, input_modalities: ["text", "image"] },
            { id: "claude-3-5-sonnet-20241022", name: "Claude 3.5 Sonnet", category: "Anthropic", description: "টপ-টায়ার কোডিং ও কম্পিউটার কন্ট্রোল", provider: "anthropic", tool_call: true, input_modalities: ["text", "image"] },
            { id: "claude-3-5-haiku-20241022", name: "Claude 3.5 Haiku", category: "Anthropic", description: "সুপারফাস্ট বুদ্ধিমত্তা ও টুল কল", provider: "anthropic", tool_call: true, input_modalities: ["text"] }
          ].forEach(add);
        }

        if (keys.deepseek) {
          [
            { id: "deepseek/deepseek-chat", name: "DeepSeek V3", category: "others", description: "হাই-পারফরম্যান্স রিজন ও কোড", provider: "deepseek", tool_call: true, input_modalities: ["text"] },
            { id: "deepseek/deepseek-reasoner", name: "DeepSeek R1 (থিংকিং)", category: "others", description: "গভীর গাণিতিক ও যৌক্তিক বিশ্লেষণ", provider: "deepseek", tool_call: false, input_modalities: ["text"] }
          ].forEach(add);
        }

        if (keys.openai) {
          [
            { id: "openai/gpt-4o", name: "OpenAI GPT-4o", category: "others", description: "অমনিভয়েস ও ভিজ্যুয়াল মডেল", provider: "openai", tool_call: true, input_modalities: ["text", "image"] },
            { id: "openai/gpt-4o-mini", name: "OpenAI GPT-4o Mini", category: "others", description: "দ্রুত ও সাশ্রয়ী মডেল", provider: "openai", tool_call: true, input_modalities: ["text", "image"] }
          ].forEach(add);
        }

        return merged;
      }
    };
    global.ZY = global.ZY || {};
    global.ZY.PROVIDERS = P;
  })(window);
})();
