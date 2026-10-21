(() => {
  (function(global) {
    "use strict";
    const DB_NAME = "zyraxon";
    const DB_VER = 2;
    const STORES = ["messages", "memory", "todos", "kv"];
    let dbp = null;
    function openIDB() {
      return new Promise((resolve, reject) => {
        if (!global.indexedDB) return reject(new Error("no idb"));
        const req = indexedDB.open(DB_NAME, DB_VER);
        req.onupgradeneeded = (e) => {
          const db = req.result;
          for (const s of STORES) if (!db.objectStoreNames.contains(s)) db.createObjectStore(s, { keyPath: "id" });
          if (!e.oldVersion) {
          }
        };
        req.onsuccess = () => resolve(req.result);
        req.onerror = () => reject(req.error);
      });
    }
    const LS = {
      _key: (s) => `zyraxon:${s}`,
      _read(s) {
        try {
          return JSON.parse(localStorage.getItem(LS._key(s)) || "{}");
        } catch {
          return {};
        }
      },
      _write(s, o) {
        try {
          localStorage.setItem(LS._key(s), JSON.stringify(o));
        } catch {
        }
      },
      put(s, v) {
        const o = LS._read(s);
        o[v.id] = v;
        LS._write(s, o);
        return v;
      },
      get(s, id) {
        return LS._read(s)[id];
      },
      all(s) {
        return Object.values(LS._read(s));
      },
      del(s, id) {
        const o = LS._read(s);
        delete o[id];
        LS._write(s, o);
      },
      clear(s) {
        LS._write(s, {});
      }
    };
    const DB = {
      useIDB: true,
      async init() {
        try {
          dbp = await openIDB();
          DB.useIDB = true;
        } catch {
          dbp = null;
          DB.useIDB = false;
        }
        return DB;
      },
      async _tx(store2, mode, fn) {
        if (!DB.useIDB) return null;
        const db = await dbp;
        return new Promise((resolve, reject) => {
          const tx = db.transaction(store2, mode);
          const os = tx.objectStore(store2);
          const r = fn(os);
          tx.oncomplete = () => resolve(r && r.result !== void 0 ? r.result : void 0);
          tx.onerror = () => reject(tx.error);
        });
      },
      async put(store2, val) {
        val.id = val.id || (crypto.randomUUID ? crypto.randomUUID() : String(Date.now() + Math.random()));
        if (DB.useIDB) await DB._tx(store2, "readwrite", (os) => os.put(val));
        else LS.put(store2, val);
        return val;
      },
      async get(store2, id) {
        if (DB.useIDB) return DB._tx(store2, "readonly", (os) => os.get(id));
        return LS.get(store2, id);
      },
      async all(store2) {
        if (DB.useIDB) return await DB._tx(store2, "readonly", (os) => os.getAll()) || [];
        return LS.all(store2);
      },
      async del(store2, id) {
        if (DB.useIDB) await DB._tx(store2, "readwrite", (os) => os.delete(id));
        else LS.del(store2, id);
      },
      async clear(store2) {
        if (DB.useIDB) await DB._tx(store2, "readwrite", (os) => os.clear());
        else LS.clear(store2);
      },
      /* key/value helpers */
      async getKV(key, dflt) {
        const r = await DB.get("kv", key);
        return r ? r.value : dflt;
      },
      async setKV(key, value) {
        return DB.put("kv", { id: key, value });
      },
      /* ── domain helpers ── */
      async addMessage(msg) {
        return DB.put("messages", { id: msg.id || uid(), ts: Date.now(), ...msg });
      },
      async recentMessages(limit = 40) {
        const all = await DB.all("messages");
        all.sort((a, b) => a.ts - b.ts);
        return all.slice(-limit);
      },
      async remember(key, value, tag = "fact") {
        const existing = (await DB.all("memory")).find((m) => m.key === key);
        if (existing) {
          existing.value = value;
          existing.tag = tag;
          existing.updated = Date.now();
          return DB.put("memory", existing);
        }
        return DB.put("memory", { id: uid(), key, value, tag, created: Date.now() });
      },
      async recall(query) {
        const all = await DB.all("memory");
        if (!query) return all;
        const q = String(query).toLowerCase();
        return all.filter((m) => (m.key + " " + m.value).toLowerCase().includes(q));
      },
      async saveTodos(list) {
        return DB.setKV("todos", list);
      },
      async loadTodos() {
        return await DB.getKV("todos", []) || [];
      },
      async exportAll() {
        const out = { app: "zyraxon", exportedAt: (/* @__PURE__ */ new Date()).toISOString(), data: {} };
        for (const s of STORES) out.data[s] = s === "kv" ? Object.fromEntries((await DB.all("kv")).map((r) => [r.id, r.value])) : await DB.all(s);
        return out;
      }
    };
    function uid() {
      return crypto.randomUUID ? crypto.randomUUID() : Date.now() + "-" + Math.random().toString(36).slice(2);
    }
    DB.uid = uid;
    const store = {
      _c: null,
      all() {
        if (!this._c) {
          try {
            this._c = JSON.parse(localStorage.getItem("zyraxon:settings") || "{}");
          } catch {
            this._c = {};
          }
        }
        return this._c;
      },
      get(k, d) {
        const v = this.all()[k];
        return v === void 0 ? d : v;
      },
      set(k, v) {
        const a = this.all();
        a[k] = v;
        this._c = a;
        try {
          localStorage.setItem("zyraxon:settings", JSON.stringify(a));
        } catch {
        }
        return v;
      }
    };
    global.ZY = global.ZY || {};
    global.ZY.store = store;
    global.ZY.DB = DB;
  })(window);
})();
