/* ZChar engine v2 — realistic 3D humans (glTF with ARKit + Oculus viseme blendshapes).
   Lip-sync from text, speech via Web Speech API, gestures via bone aiming. Works from file:// (models are embedded as base64 .js). */
(function () {
  'use strict';
  const T = THREE;
  const clamp = (v, a, b) => Math.max(a, Math.min(b, v));
  const lerp = (a, b, t) => a + (b - a) * t;
  const rnd = (a, b) => a + Math.random() * (b - a);
  function glowTex(inner, outer) {
    const c = document.createElement('canvas'); c.width = c.height = 128; const x = c.getContext('2d');
    const g = x.createRadialGradient(64, 64, 0, 64, 64, 64);
    g.addColorStop(0, inner || 'rgba(255,255,255,1)'); g.addColorStop(0.4, outer || 'rgba(255,255,255,0.35)'); g.addColorStop(1, 'rgba(255,255,255,0)');
    x.fillStyle = g; x.fillRect(0, 0, 128, 128); return new T.CanvasTexture(c);
  }
  function b64(s) { const b = atob(s), u = new Uint8Array(b.length); for (let i = 0; i < b.length; i++) u[i] = b.charCodeAt(i); return u.buffer; }

  /* ---------------- viseme mapping (Oculus set) ---------------- */
  const BN = {
    aa: 'আাঅ', E: 'এেঐৈ', I: 'ইঈিী', O: 'ওোঔৌ', U: 'উঊুূ',
    PP: 'পফবভম', kk: 'কখগঘঙ', CH: 'চছজঝ', DD: 'টঠডঢতথদধ', nn: 'নল', RR: 'রড়ঢ়', SS: 'সশষ', FF: '', TH: '', sil: '',
  };
  function visemeOf(ch) {
    const c = ch.toLowerCase();
    if (/\s/.test(c) || /[.,!?;:।—-]/.test(c)) return ['sil', 0];
    for (const k in BN) if (BN[k].includes(ch)) return [k, k === 'aa' || k === 'O' ? 1 : 0.85];
    if ('a'.includes(c)) return ['aa', 1]; if ('e'.includes(c)) return ['E', .9]; if ('i y'.includes(c)) return ['I', .85]; if ('o'.includes(c)) return ['O', 1]; if ('u w'.includes(c)) return ['U', .9];
    if ('pbm'.includes(c)) return ['PP', .9]; if ('fv'.includes(c)) return ['FF', .8]; if ('kgq'.includes(c)) return ['kk', .7];
    if ('cj'.includes(c)) return ['CH', .7]; if ('tdz'.includes(c)) return ['DD', .7]; if ('nl'.includes(c)) return ['nn', .6];
    if ('r'.includes(c)) return ['RR', .7]; if ('sx'.includes(c)) return ['SS', .6]; if ('h'.includes(c)) return ['TH', .5];
    if (/[ঀ-৿]/.test(ch)) return ['nn', .5]; // other Bengali consonant / sign
    return ['DD', .5];
  }
  const VOW = 'aeiouআাঅএেঐৈইঈিীওোঔৌউঊুূAEIOU';
  function buildTimeline(text, rate) {
    const tl = []; let t = 0;
    for (const ch of text) {
      const [v, w] = visemeOf(ch);
      let d = VOW.includes(ch) ? 115 : /\s/.test(ch) ? 70 : /[.!?।]/.test(ch) ? 320 : /[,;:]/.test(ch) ? 190 : 66;
      d /= rate; tl.push({ t, d, v, w }); t += d;
    }
    return { tl, total: t };
  }
  function detectEmotion(text) {
    const s = text.toLowerCase();
    if (/(রাগ|angry|furious|কেন না|বিরক্ত|hate)/.test(s)) return 'angry';
    if (/(দুঃখ|কষ্ট|sad|sorry|cry|কান্না)/.test(s)) return 'sad';
    if (/(wow|অবাক|\?!|!\?|অসাধারণ|incredible)/.test(s)) return 'surprised';
    if (/(হাহা|haha|ভালো|ধন্যবাদ|thank|love|great|দারুণ|খুশি|মজা|সুন্দর|হ্যালো|hello|নমস্কার|সালাম|!)/.test(s)) return 'happy';
    return 'neutral';
  }
  // ARKit blendshape recipes per emotion
  const EMO = {
    neutral: { mouthSmileLeft: .22, mouthSmileRight: .22, cheekSquintLeft: .08, cheekSquintRight: .08, browInnerUp: .06 },
    happy: { mouthSmileLeft: .7, mouthSmileRight: .7, cheekSquintLeft: .35, cheekSquintRight: .35, browInnerUp: .15, eyeSquintLeft: .2, eyeSquintRight: .2 },
    sad: { mouthFrownLeft: .55, mouthFrownRight: .55, browInnerUp: .75, mouthPressLeft: .2, mouthPressRight: .2, eyeLookDownLeft: .3, eyeLookDownRight: .3 },
    angry: { browDownLeft: .8, browDownRight: .8, noseSneerLeft: .35, noseSneerRight: .35, mouthFrownLeft: .3, mouthFrownRight: .3, eyeSquintLeft: .35, eyeSquintRight: .35, jawForward: .1 },
    surprised: { browInnerUp: .8, browOuterUpLeft: .7, browOuterUpRight: .7, eyeWideLeft: .7, eyeWideRight: .7, jawOpen: .25 },
  };

  /* ---------------- avatar ---------------- */
  const GEST = {   // upper-arm: out (o), forward (f); elbow bend (e); cross-body (i); finger curl (c); palm roll (r); finger spread (sp)
    rest: { o: 0.2, f: 0.0, e: 0.25, i: 0, c: 0.3, r: 0, sp: 0 },
    openPalm: { o: 0.5, f: 0.75, e: 1.15, i: 0, c: 0.08, r: 1.15, sp: 0.6 },
    palmsUp: { o: 0.55, f: 0.6, e: 1.4, i: 0, c: 0.14, r: 2.1, sp: 0.5 },
    raise: { o: 0.45, f: 1.3, e: 0.7, i: 0, c: 0.1, r: 0.9, sp: 0.4 },
    chest: { o: 0.25, f: 0.45, e: 2.1, i: 0.35, c: 0.22, r: 0.6, sp: 0.1 },
    point: { o: 0.2, f: 1.15, e: 0.3, i: 0, c: 0.95, pt: 1, r: 0.7, sp: 0 },
    shrug: { o: 0.75, f: 0.25, e: 1.5, i: 0, c: 0.12, r: 1.8, sp: 0.5 },
    wave: { o: 2.2, f: 0.1, e: 0.7, i: 0, c: 0.05, wave: 1, r: 0.2, sp: 0.5 },
    explain: { o: 0.65, f: 0.7, e: 1.5, i: 0.1, c: 0.2, r: 1.4, sp: 0.3 },
    fist: { o: 0.3, f: 0.9, e: 1.9, i: 0, c: 1, r: 0.5, sp: 0 },
    clasp: { o: 0.12, f: 0.35, e: 1.75, i: 0.75, c: 0.35, r: 0.4, sp: 0 },
    heart: { o: 0.2, f: 0.3, e: 2.25, i: 0.5, c: 0.2, r: 0.5, sp: 0 },
  };
  const GKEYS = ['o', 'f', 'e', 'i', 'c', 'r', 'sp'];

  function getBuf(cfg, key, cb) {
    if (window.ZM && window.ZM[key]) return cb(b64(window.ZM[key]));
    fetch((cfg.base || '') + 'models/' + key + '.glb').then(r => r.arrayBuffer()).then(cb).catch(e => { document.title = 'model error'; console.error(e); });
  }
  function transplant(g, d) {   // copy full viseme/ARKit morph targets from a donor with the same head topology
    const src = {}; d.scene.traverse(o => { if (o.isMesh && o.morphTargetDictionary) src[o.name] = o; });
    g.scene.traverse(o => {
      const sm = src[o.name]; if (!o.isMesh || !sm || o.morphTargetDictionary && Object.keys(o.morphTargetDictionary).length > 10) return;
      if (o.geometry.attributes.position.count !== sm.geometry.attributes.position.count) return;
      o.geometry.morphAttributes = sm.geometry.morphAttributes; o.geometry.morphTargetsRelative = sm.geometry.morphTargetsRelative;
      o.morphTargetDictionary = Object.assign({}, sm.morphTargetDictionary); o.morphTargetInfluences = new Array(sm.morphTargetInfluences.length).fill(0);
      o.userData.donor = true;
    });
  }
  function loadAvatar(cfg, cb) {
    const parse = (buf, f) => new T.GLTFLoader().setMeshoptDecoder(T.MeshoptDecoder).parse(buf, '', f, err => { console.error(err); document.title = 'model error'; });
    getBuf(cfg, cfg.model, buf => parse(buf, gltf => {
      if (!cfg.donor) return cb(gltf);
      getBuf(cfg, cfg.donor, b2 => parse(b2, dn => { transplant(gltf, dn); cb(gltf); }));
    }));
  }


  /* ---------------- Rocketbox (Bip01) skeleton support ---------------- */
  function rbMap(bones, model) {
    const raw = {}; Object.keys(bones).forEach(k => { raw[k.replace(/[ _]/g, '')] = bones[k]; });
    const g = n => raw['Bip01' + n];
    model.updateMatrixWorld(true);
    const lp = g('LUpperArm') ? g('LUpperArm').getWorldPosition(new T.Vector3()) : new T.Vector3(1, 0, 0);
    const Ls = lp.x >= 0 ? 'L' : 'R', Rs = Ls === 'L' ? 'R' : 'L';   // which Bip01 side is the character's left (+x, facing +z)
    const map = { Hips: g('Pelvis'), Spine: g('Spine'), Spine1: g('Spine1'), Spine2: g('Spine2'), Neck: g('Neck'), Head: g('Head'), LeftEye: g(Ls + 'Eye'), RightEye: g(Rs + 'Eye') };
    const FN = { Thumb: 0, Index: 1, Middle: 2, Ring: 3, Pinky: 4 };
    [['Left', Ls], ['Right', Rs]].forEach(([nm, c]) => {
      map[nm + 'Shoulder'] = g(c + 'Clavicle'); map[nm + 'Arm'] = g(c + 'UpperArm'); map[nm + 'ForeArm'] = g(c + 'Forearm'); map[nm + 'Hand'] = g(c + 'Hand');
      // chain names: Finger0, Finger01, Finger02 / Finger1, Finger11, Finger12 ...
      for (const f in FN) { const k = FN[f]; map[nm + 'Hand' + f + '1'] = g(c + 'Finger' + k); map[nm + 'Hand' + f + '2'] = g(c + 'Finger' + k + '1'); map[nm + 'Hand' + f + '3'] = g(c + 'Finger' + k + '2'); }
      map[nm + 'UpLeg'] = g(c + 'Thigh');
    });
    Object.keys(map).forEach(k => { if (map[k]) bones[k] = map[k]; });
    return { raw };
  }

  function start(cfg) {
    document.title = cfg.name + ' — ZYRAXON';
    const css = document.createElement('style');
    css.textContent = `
    *{box-sizing:border-box}html,body{margin:0;height:100%;overflow:hidden;background:${cfg.bg[0]};font-family:system-ui,'Noto Sans Bengali','Hind Siliguri',sans-serif;color:#fff}
    #bg{position:fixed;inset:0;background:radial-gradient(ellipse at 50% 38%,${cfg.bg[1]} 0%,${cfg.bg[0]} 72%)}
    canvas#gl{position:fixed;inset:0;width:100%;height:100%;display:block;touch-action:none;cursor:grab}
    #title{position:fixed;left:16px;top:12px;pointer-events:none;text-shadow:0 2px 12px #000}
    #title b{font-size:26px;letter-spacing:.5px;color:${cfg.aura[0]}}#title i{display:block;font-style:normal;font-size:13px;opacity:.8;margin-top:2px}
    #back{position:fixed;right:14px;top:14px;color:#fff;text-decoration:none;background:#0008;border:1px solid #fff3;padding:6px 12px;border-radius:20px;font-size:13px;backdrop-filter:blur(6px)}
    #load{position:fixed;inset:0;display:flex;align-items:center;justify-content:center;font-size:18px;color:${cfg.aura[0]};background:${cfg.bg[0]};z-index:5;transition:opacity .6s}
    #sub{position:fixed;left:50%;transform:translateX(-50%);bottom:132px;max-width:min(92vw,760px);text-align:center;font-size:19px;line-height:1.5;text-shadow:0 2px 8px #000,0 0 18px #000;padding:0 10px;pointer-events:none;min-height:28px}
    #ui{position:fixed;left:0;right:0;bottom:0;padding:10px 12px 14px;background:linear-gradient(transparent,#000a 40%);display:flex;flex-direction:column;gap:7px;align-items:center}
    #row{display:flex;gap:8px;width:min(96vw,760px)}
    #txt{flex:1;border-radius:14px;border:1px solid ${cfg.aura[0]}66;background:#0009;color:#fff;padding:10px 12px;font-size:16px;resize:none;height:46px;font-family:inherit;outline:none}
    #txt:focus{border-color:${cfg.aura[0]}}
    button{font-family:inherit;cursor:pointer;border:1px solid ${cfg.aura[0]}88;background:${cfg.aura[0]}33;color:#fff;border-radius:14px;padding:0 16px;font-size:15px;backdrop-filter:blur(6px)}
    button:hover{background:${cfg.aura[0]}66}#go{background:${cfg.aura[0]};color:#111;font-weight:700;border:none}
    #chips{display:flex;gap:6px;flex-wrap:wrap;justify-content:center;width:min(96vw,760px)}
    .chip{padding:5px 11px;font-size:12.5px;border-radius:16px;height:auto}
    #note{font-size:11.5px;opacity:.65;text-align:center}`;
    document.head.appendChild(css);
    document.body.insertAdjacentHTML('beforeend',
      `<div id="bg"></div><canvas id="gl"></canvas><div id="load">${cfg.name} আসছে…</div>
       <div id="title"><b>${cfg.name}</b><i>${cfg.tagline}</i></div>
       <a id="back" href="index.html">◀ সব ক্যারেক্টার</a><div id="sub"></div>
       <div id="ui"><div id="chips"></div>
         <div id="row"><textarea id="txt" placeholder="এখানে যা খুশি লিখুন…"></textarea><button id="go">▶ বলো</button><button id="stop">■</button></div>
         <div id="note"></div></div>`);

    const embed = !!cfg.embed;
    if (embed) { const st = document.createElement('style'); st.textContent = 'html,body{background:transparent!important}#bg,#ui,#title,#back,#sub,#load{display:none!important}canvas#gl{cursor:default}'; document.head.appendChild(st); }
    const canvas = document.getElementById('gl');
    const renderer = new T.WebGLRenderer({ canvas, antialias: true, alpha: true, preserveDrawingBuffer: true });
    renderer.setPixelRatio(Math.min(window.devicePixelRatio || 1, 2));
    renderer.shadowMap.enabled = true; renderer.shadowMap.type = T.PCFSoftShadowMap;
    renderer.outputEncoding = T.sRGBEncoding; renderer.toneMapping = T.ACESFilmicToneMapping; renderer.toneMappingExposure = 1.05;
    const scene = new T.Scene();
    try { const pm = new T.PMREMGenerator(renderer); scene.environment = pm.fromScene(new T.RoomEnvironment(), 0.04).texture; } catch (e) {}
    const camera = new T.PerspectiveCamera(30, 1, 0.05, 50);

    scene.add(new T.HemisphereLight('#ffffff', cfg.bg[1], 0.25));
    const key = new T.DirectionalLight('#fff1e6', embed ? 1.5 : 2.1); key.position.set(1.6, 2.8, 3.0); key.castShadow = true;
    key.shadow.mapSize.set(1024, 1024); key.shadow.camera.left = -1.5; key.shadow.camera.right = 1.5; key.shadow.camera.top = 2.5; key.shadow.camera.bottom = -0.5; key.shadow.bias = -0.0004; scene.add(key);
    const beauty = new T.DirectionalLight('#fff6ee', 0.55); beauty.position.set(0, 1.7, 3.5); scene.add(beauty);
    const fill = new T.DirectionalLight('#b8d0ff', 0.55); fill.position.set(-2.5, 1.5, 2); scene.add(fill);
    const rimA = new T.PointLight(cfg.aura[0], 3, 7); rimA.position.set(-1.4, 1.8, -1.3); scene.add(rimA);
    const rimB = new T.PointLight(cfg.aura[1] || cfg.aura[0], 3, 7); rimB.position.set(1.5, 1.4, -1.3); scene.add(rimB);

    const floor = new T.Mesh(new T.CircleGeometry(1.6, 64), new T.MeshStandardMaterial({ color: cfg.floor || '#0c0610', roughness: 0.22, metalness: 0.6 }));
    floor.rotation.x = -Math.PI / 2; floor.receiveShadow = true; if (!embed) scene.add(floor); else { floor.material = new T.ShadowMaterial({ opacity: 0.35 }); scene.add(floor); }
    const ringM = new T.MeshBasicMaterial({ color: cfg.aura[0], transparent: true, opacity: 0.9 });
    const ring = new T.Mesh(new T.RingGeometry(0.95, 0.975, 80), ringM); ring.rotation.x = -Math.PI / 2; ring.position.y = 0.003; if (!embed) scene.add(ring);
    const ring2 = new T.Mesh(new T.RingGeometry(1.2, 1.212, 80), new T.MeshBasicMaterial({ color: cfg.aura[1] || cfg.aura[0], transparent: true, opacity: 0.5 })); ring2.rotation.x = -Math.PI / 2; ring2.position.y = 0.003; if (!embed) scene.add(ring2);
    const pool = new T.Sprite(new T.SpriteMaterial({ map: glowTex(cfg.aura[0] + 'aa', cfg.aura[0] + '22'), transparent: true, blending: T.AdditiveBlending, depthWrite: false })); pool.scale.set(3.2, 3.2, 1); pool.position.y = 0.02; if (!embed) scene.add(pool);
    const back = new T.Sprite(new T.SpriteMaterial({ map: glowTex(cfg.aura[0] + 'aa', cfg.aura[0] + '18'), transparent: true, blending: T.AdditiveBlending, depthWrite: false })); back.scale.set(3.6, 3.6, 1); back.position.set(0, 1.05, -0.8); scene.add(back);

    const N = 220, pg = new T.BufferGeometry(), pp = new Float32Array(N * 3), pv = new Float32Array(N), ps = new Float32Array(N), pc = new Float32Array(N * 3);
    const c0 = new T.Color(cfg.aura[0]), c1 = new T.Color(cfg.aura[1] || cfg.aura[0]);
    for (let i = 0; i < N; i++) {
      const a = Math.random() * 6.283, r = rnd(0.4, 1.3);
      pp[i * 3] = Math.cos(a) * r; pp[i * 3 + 1] = rnd(0, 2.4); pp[i * 3 + 2] = Math.sin(a) * r * 0.8 - 0.2; pv[i] = rnd(0.1, 0.4); ps[i] = rnd(0, 6.28);
      const cc = Math.random() < 0.5 ? c0 : c1; pc[i * 3] = cc.r; pc[i * 3 + 1] = cc.g; pc[i * 3 + 2] = cc.b;
    }
    pg.setAttribute('position', new T.BufferAttribute(pp, 3)); pg.setAttribute('color', new T.BufferAttribute(pc, 3));
    const falling = cfg.particle === 'fall';
    scene.add(new T.Points(pg, new T.PointsMaterial({ size: cfg.particleSize || 0.045, map: glowTex(), vertexColors: true, transparent: true, blending: T.AdditiveBlending, depthWrite: false, opacity: 0.9 })));

    GEST.rest = Object.assign({ o: 0.2, f: 0, e: 0.25, i: 0, c: 0.3, r: 0, sp: 0 }, cfg.rest || {});
    const S = {
      t: 0, blink: 0, nextBlink: 2, emo: 'neutral', holdEmo: 0, nod: 0, look: { x: 0, y: 0 }, lookT: { x: 0, y: 0 },
      speaking: false, tl: null, t0: 0, gestUntil: 0, orbit: 0, orbitT: 0, zoom: 0, zoomT: 0, closeup: false, cl: 0, vis: {}, words: '',
      arms: [{ s: 1, cur: Object.assign({}, GEST.rest), tgt: Object.assign({}, GEST.rest) }, { s: -1, cur: Object.assign({}, GEST.rest), tgt: Object.assign({}, GEST.rest) }],
      mp: {}, ready: false, gname: 'rest',
    };

    let A = null; // avatar parts
    function onModel(gltf) {
      const model = gltf.scene; const wrap = new T.Group(); wrap.add(model); scene.add(wrap);
      model.traverse(o => { if (o.isMesh) { o.castShadow = !embed; o.receiveShadow = false; o.frustumCulled = false; } });
      // normalise height to 1.7 m, feet on floor
      wrap.updateMatrixWorld(true);
      const box = new T.Box3().setFromObject(model); const hgt = box.max.y - box.min.y; let sc = 1.7 / hgt; wrap.scale.setScalar(sc); wrap.updateMatrixWorld(true);
      const box2 = new T.Box3().setFromObject(wrap); wrap.position.y -= box2.min.y; wrap.updateMatrixWorld(true);
      const bones = {}; model.traverse(o => { if (o.isBone) bones[o.name.replace(/^mixamorig:?/, '')] = o; });
      let rb = null;
      if (bones.Bip01Head || bones['Bip01_Head']) rb = rbMap(bones, model);
      if (rb) {   // skinned bounding boxes are unreliable: measure the skeleton instead (head bone -> sole)
        wrap.scale.setScalar(1); wrap.position.set(0, 0, 0); wrap.updateMatrixWorld(true);
        const wy = n => rb.raw[n] ? rb.raw[n].getWorldPosition(new T.Vector3()).y : null;
        const hy0 = wy('Bip01Head'), toe = Math.min(...['Bip01LToe0', 'Bip01RToe0', 'Bip01LFoot', 'Bip01RFoot'].map(wy).filter(v => v !== null));
        const ground = toe - 0.025 * (hy0 - toe), H = (hy0 - ground) * 1.085;
        sc = 1.7 / H; wrap.scale.setScalar(sc); wrap.position.y = -ground * sc; wrap.updateMatrixWorld(true);
      }
      const rest = new Map(); Object.values(bones).forEach(b => rest.set(b, b.quaternion.clone()));
      const morphMeshes = []; const mats = {};
      model.traverse(o => {
        if (o.isMesh && o.morphTargetDictionary) morphMeshes.push(o);
        if (o.isMesh) { const m = o.material; mats[m.name || o.name] = m; o.userData.mname = (m.name || '') + '|' + o.name; }
      });
      // tint hair / outfit / eyes
      model.traverse(o => {
        if (!o.isMesh) return; const nm = ((o.material && o.material.name) || '') + o.name; const m = o.material;
        const tint = (c, flat) => { if (!c) return; m.color.set(c); if (flat) { m.map = null; m.roughness = 0.4; } m.needsUpdate = true; };
        if (/hair/i.test(nm) && !/eyebrow|eyelash/i.test(nm)) { tint(cfg.hair, cfg.hairFlat !== false); if (cfg.hair && cfg.hairGain) m.color.multiplyScalar(cfg.hairGain); if (cfg.hair) { m.emissive = new T.Color(cfg.hair).multiplyScalar(0.12); } m.side = T.DoubleSide; m.transparent = true; m.alphaTest = 0.4; m.depthWrite = true; }
        else if (/Outfit_Top|look_0|casualsuit|Top/i.test(nm)) tint(cfg.top, cfg.topFlat);
        else if (/Outfit_Bottom|Bottom/i.test(nm)) tint(cfg.bottom, cfg.bottomFlat);
        else if (/Headwear/i.test(nm)) { tint(cfg.hat); m.roughness = 0.88; m.metalness = 0; m.envMapIntensity = 0.3; if (m.map) m.needsUpdate = true; }
        else if (/Footwear|shoes/i.test(nm)) tint(cfg.shoes, cfg.topFlat);
        if (/skin|Head|Body/i.test(m.name) && cfg.skinTint) { m.color.set(cfg.skinTint); }
        if (/Glasses/i.test(nm) && cfg.noGlasses) o.visible = false;
        if (cfg.hide && cfg.hide.some(k => new RegExp(k, 'i').test(nm + '|' + o.name))) o.visible = false;
        if (/Wolf3D_Eye|Eyes|Eye_Mesh/i.test(nm) && cfg.eyeTint) { m.color.set(cfg.eyeTint); m.emissive = new T.Color(cfg.eyeTint).multiplyScalar(cfg.eyeGlow || 0.15); }
        if (m.isMeshStandardMaterial && /Outfit|look|suit|Footwear|shoes|Body/i.test(nm) && !/skin|Head/i.test(m.name)) { m.roughness = Math.max(m.roughness, 0.7); m.metalness = Math.min(m.metalness, 0.12); }
        if (/^(skin|Head|Body|Wolf3D_Skin|Wolf3D_Body)$/i.test(m.name) && m.isMeshStandardMaterial && !o.userData.phys) { o.userData.phys = 1; const pm = new T.MeshPhysicalMaterial({ map: m.map, normalMap: m.normalMap, normalScale: m.normalScale, roughnessMap: m.roughnessMap, metalnessMap: m.metalnessMap, aoMap: m.aoMap, color: m.color.clone().multiply(new T.Color(cfg.skinTint || '#ffffff')), roughness: cfg.skinRough || 0.5, metalness: 0, sheen: 0.4, sheenColor: new T.Color(cfg.sheen || '#ffb0a0'), sheenRoughness: 0.6, clearcoat: cfg.skinCoat === undefined ? 0.12 : cfg.skinCoat, clearcoatRoughness: 0.45, envMapIntensity: cfg.env || 0.8 }); pm.name = m.name; o.material = pm; }
        if (/Eye/i.test(m.name) && !/AO|lash/i.test(m.name) && m.isMeshStandardMaterial) { m.roughness = 0.05; m.envMapIntensity = 1.9; }
        if (m.isMeshStandardMaterial) { m.envMapIntensity = cfg.env || 0.8; if (/skin|Head|Body/i.test(m.name)) { m.roughness = 0.62; m.metalness = 0; } }
      });
      A = { model, wrap, bones, rest, morphMeshes, rb, sc };
      if (rb) rbInit();
      A.fing = {};
      for (const side of ['Left', 'Right']) {
        const hand = bones[side + 'Hand']; if (!hand) continue; hand.updateWorldMatrix(true, true);
        const hq = hand.getWorldQuaternion(new T.Quaternion()), hi = hq.clone().invert();
        // The palm's own normal, read off the model. Assuming "palms face the
        // floor" is only true in a T-pose; these avatars rest with their arms
        // down, so the palm actually faces the thigh. Hinging the fingers about a
        // normal 90 degrees out of the palm plane bent them sideways, and
        // neighbouring fingers drove through each other.
        const pIdx = bones[side + 'HandIndex1'], pPky = bones[side + 'HandPinky1'], pTh = bones[side + 'HandThumb1'];
        const nWorld = new T.Vector3(0, -1, 0);
        if (pIdx && pPky) {
          const pMid2 = bones[side + 'HandMiddle2'];
          const knuckle = pIdx.getWorldPosition(new T.Vector3()).sub(pPky.getWorldPosition(new T.Vector3()));
          const along = (pMid2 || pIdx).getWorldPosition(new T.Vector3())
            .sub(pIdx.getWorldPosition(new T.Vector3()));
          const n = new T.Vector3().crossVectors(knuckle, along);
          if (n.lengthSq() > 1e-10) {
            n.normalize();
            // Face it out of the palm: the thumb sits on the palm side, so the
            // normal must point the same way the thumb does.
            if (pTh && n.dot(pTh.getWorldPosition(new T.Vector3()).sub(hand.getWorldPosition(new T.Vector3()))) < 0) n.negate();
            nWorld.copy(n);
          }
        }
        const fr = { n: nWorld.clone().applyQuaternion(hi) };
        for (const fn of ['Index', 'Middle', 'Ring', 'Pinky', 'Thumb']) {
          const b1 = bones[side + 'Hand' + fn + '1'], b2 = bones[side + 'Hand' + fn + '2']; if (!b1 || !b2) continue;
          const d = b2.getWorldPosition(new T.Vector3()).sub(b1.getWorldPosition(new T.Vector3())).normalize();
          const axw = new T.Vector3().crossVectors(d, nWorld).normalize();
          fr[fn] = { ax: axw.applyQuaternion(hi) };
        }
        A.fing[side] = fr;
      }
      const hp = new T.Vector3(); (bones.Head || bones.Neck).getWorldPosition(hp); A.headY = hp.y;
      A.headFollow = new T.Group(); scene.add(A.headFollow); A.headRestInv = (bones.Head).getWorldQuaternion(new T.Quaternion()).invert();
      buildAccessories();
      document.getElementById('load').style.opacity = 0; setTimeout(() => document.getElementById('load').remove(), 700);
      S.ready = true; applyPose(0);
      if (!embed) setTimeout(() => { if (cfg.greet) speak(cfg.greet); }, 700);
      if (window.Android && Android.ready) Android.ready();
    }

    function buildAccessories() {
      const g = A.headFollow, ex = cfg.extras || []; let ty = 0.2; if (A.bones.HeadTop_End) { const a = new T.Vector3(), b = new T.Vector3(); A.bones.HeadTop_End.getWorldPosition(a); A.bones.Head.getWorldPosition(b); ty = a.y - b.y; } ty = clamp(ty, 0.12, 0.3); // origin at head-bone, +y up (world units)
      const B = (c, o) => new T.MeshStandardMaterial(Object.assign({ color: c, roughness: 0.4, metalness: 0.3 }, o || {}));
      const M = (geo, mat) => { const m = new T.Mesh(geo, mat); m.castShadow = true; return m; };
      if (ex.includes('thornHalo')) {
        const h = new T.Group(); h.position.set(0, ty + 0.04, -0.02); g.add(h); A.halo = h; A.haloY = ty + 0.04;
        { const rg = M(new T.TorusGeometry(0.17, 0.006, 8, 60), new T.MeshBasicMaterial({ color: cfg.aura[0] })); rg.rotation.x = Math.PI / 2; h.add(rg); }
        for (let i = 0; i < 18; i++) { const a = i / 18 * 6.283, c = M(new T.ConeGeometry(0.008, 0.05 + (i % 2) * 0.04, 6), new T.MeshBasicMaterial({ color: i % 2 ? cfg.aura[0] : cfg.aura[1] })); c.position.set(Math.cos(a) * 0.17, 0.025, Math.sin(a) * 0.17); c.rotation.set(Math.sin(a) * 0.5, 0, -Math.cos(a) * 0.5); h.add(c); }
        const gl = new T.Sprite(new T.SpriteMaterial({ map: glowTex(cfg.aura[0] + 'aa', cfg.aura[0] + '22'), transparent: true, blending: T.AdditiveBlending, depthWrite: false })); gl.scale.set(0.7, 0.7, 1); h.add(gl);
      }
      if (ex.includes('horns')) for (const s of [1, -1]) { const hn = M(new T.ConeGeometry(0.03, 0.2, 14), B(cfg.horn || '#1a0a0e', { metalness: 0.5, roughness: 0.25 })); hn.position.set(s * 0.08, ty - 0.07, 0.02); hn.rotation.set(-0.35, 0, -s * 0.45); g.add(hn); }
      if (ex.includes('crown')) { const c = new T.Group(); c.position.set(0, ty - 0.03, 0.0); g.add(c); const ring = M(new T.TorusGeometry(0.1, 0.012, 8, 40), B('#f0c050', { metalness: 0.9, roughness: 0.2 })); ring.rotation.x = Math.PI / 2; c.add(ring); for (let i = 0; i < 7; i++) { const a = i / 7 * 6.283, sp = M(new T.ConeGeometry(0.014, 0.06, 6), B('#f0c050', { metalness: 0.9, roughness: 0.2 })); sp.position.set(Math.cos(a) * 0.1, 0.035, Math.sin(a) * 0.1); c.add(sp); } }
      if (ex.includes('witchHat')) {
        const brim = M(new T.CylinderGeometry(0.27, 0.27, 0.01, 40), B(cfg.hat || '#2c2058', { roughness: 0.8, metalness: 0 })); brim.position.set(0, ty - 0.035, 0); brim.rotation.x = -0.12; g.add(brim);
        const cone = M(new T.ConeGeometry(0.15, 0.42, 32), B(cfg.hat || '#2c2058', { roughness: 0.8, metalness: 0 })); cone.position.set(0, ty + 0.12, -0.03); cone.rotation.x = -0.2; g.add(cone);
        const band = M(new T.TorusGeometry(0.152, 0.016, 8, 32), B('#e8c14a', { metalness: 0.8 })); band.rotation.x = Math.PI / 2 - 0.12; band.position.y = ty - 0.02; g.add(band);
      }
      if (ex.includes('leafCrown')) for (let i = 0; i < 12; i++) { const a = i / 12 * 6.283, lf = M(new T.ConeGeometry(0.02, 0.07, 6), B('#3fcf7a', { emissive: '#0a5a30', emissiveIntensity: 0.5 })); lf.position.set(Math.sin(a) * 0.11, ty - 0.05, Math.cos(a) * 0.11); lf.quaternion.setFromUnitVectors(new T.Vector3(0, 1, 0), new T.Vector3(Math.sin(a), 0.9, Math.cos(a)).normalize()); g.add(lf); }
      if (ex.includes('headphones')) {
        const band = M(new T.TorusGeometry(0.11, 0.01, 10, 40, Math.PI), B(cfg.aura[0], { emissive: cfg.aura[0], emissiveIntensity: 0.7 })); band.position.set(0, ty - 0.1, 0); g.add(band);
        for (const s of [1, -1]) { const cup = M(new T.CylinderGeometry(0.04, 0.04, 0.03, 20), B(cfg.aura[0], { emissive: cfg.aura[0], emissiveIntensity: 0.7 })); cup.rotation.z = Math.PI / 2; cup.position.set(s * 0.108, ty - 0.15, 0); g.add(cup); }
      }
      if (ex.includes('moon')) { const mg = new T.Group(); mg.position.set(-0.55, 1.5, 0.2); scene.add(mg); A.moon = mg; mg.add(M(new T.SphereGeometry(0.08, 24, 18), new T.MeshBasicMaterial({ color: '#fff4c0' }))); const gl = new T.Sprite(new T.SpriteMaterial({ map: glowTex('rgba(255,244,192,0.9)', 'rgba(255,244,192,0.25)'), transparent: true, blending: T.AdditiveBlending, depthWrite: false })); gl.scale.set(0.55, 0.55, 1); mg.add(gl); }
    }

    /* ----- bone helpers ----- */
    const v1 = new T.Vector3(), v2 = new T.Vector3(), q1 = new T.Quaternion(), q2 = new T.Quaternion(), q3 = new T.Quaternion();
    function aim(bone, child, dir) {
      if (!bone || !child) return;
      bone.quaternion.copy(A.rest.get(bone)); bone.updateWorldMatrix(true, true);
      bone.getWorldPosition(v1); child.getWorldPosition(v2); const cur = v2.sub(v1).normalize();
      const dq = q1.setFromUnitVectors(cur, dir); bone.getWorldQuaternion(q2); bone.parent.getWorldQuaternion(q3).invert();
      bone.quaternion.copy(q3.multiply(dq.multiply(q2))); bone.updateWorldMatrix(true, true);
    }
    const eu = new T.Euler(), qq = new T.Quaternion(), wq = new T.Quaternion(), pq = new T.Quaternion();
    function rotWorld(bone, x, y, z) {
      if (!bone) return; eu.set(x, y, z, 'YXZ'); qq.setFromEuler(eu);
      bone.getWorldQuaternion(wq); bone.parent.getWorldQuaternion(pq).invert();
      bone.quaternion.copy(pq.multiply(qq.multiply(wq))); bone.updateWorldMatrix(true, true);
    }
    function armDirs(g, s) {
      // upper arm: start hanging down, swing outward (z) then forward (x)
      const u = new T.Vector3(0, -1, 0).applyAxisAngle(new T.Vector3(0, 0, 1), s * g.o).applyAxisAngle(new T.Vector3(1, 0, 0), -g.f);
      // forearm: bend toward the front
      const front = new T.Vector3(0, 0, 1); front.addScaledVector(u, -front.dot(u)).normalize();
      const f = u.clone().multiplyScalar(Math.cos(g.e)).addScaledVector(front, Math.sin(g.e)).normalize();
      return { u, f };
    }
    const HQ = new T.Quaternion();
    function rotAxisWorld(bone, axis, ang) { bone.getWorldQuaternion(wq); bone.parent.getWorldQuaternion(pq).invert(); qq.setFromAxisAngle(axis, ang); bone.quaternion.copy(pq.multiply(qq.multiply(wq))); bone.updateWorldMatrix(true, true); }
    const FING = { roll: 1, spaxis: new T.Vector3(1, 0, 0), spsign: 1, axis: new T.Vector3(0, 0, 1), sign: 1, taxis: new T.Vector3(0, 1, 0), tsign: 1 }, FQ = new T.Quaternion(); window.FING = FING;

    /* ----- Rocketbox face: no blendshapes, so the lips/jaw/brows/lids are driven as bones ----- */
    const FN_ = ['Jaw','BottomLip','Tongue','LMouthBottom','RMouthBottom','UpperLip','RUpperlip','LUpperlip','RMouthCorner','LMouthCorner','RCheek','LCheek','RCaninus','LCaninus','RMasseter','LMasseter','REyeBlinkTop','LEyeBlinkTop','REyeBlinkBottom','LEyeBlinkBottom','RInnerEyebrow','LInnerEyebrow','MMiddleEyebrow','ROuterEyebrow','LOuterEyebrow','Nose'];
    const RBN = { Jaw: 'MJaw', BottomLip: 'MBottomLip', Tongue: 'MTongue', UpperLip: 'MUpperLip', Nose: 'MNose' };
    function rbInit() {
      const raw = A.rb.raw, f = A.rb.f = {};
      FN_.forEach(n => { const b = raw['Bip01' + (RBN[n] || n)] || raw['Bip01M' + n]; if (b) f[n] = b; });
      // which Bip01 side is the character's left?
      const sideL = raw.Bip01LUpperArm.getWorldPosition(new T.Vector3()).x >= 0 ? 'L' : 'R';
      const sw = n => n.replace(/^L/, '\u0001').replace(/^R/, 'L').replace(/^\u0001/, 'R');
      A.rb.sideL = sideL;
      if (sideL === 'R') { const f2 = {}; Object.keys(f).forEach(k => { f2[/^[LR]/.test(k) && k !== 'Nose' ? sw(k) : k] = f[k]; }); A.rb.f = f2; }
      A.rb.fr = new Map(); Object.values(A.rb.f).forEach(b => A.rb.fr.set(b, { p: b.position.clone(), q: b.quaternion.clone() }));
      // jaw: auto-detect the rotation sign that lowers the chin
      const jaw = A.rb.f.Jaw, bl = A.rb.f.BottomLip; A.rb.jawSign = 1;
      if (jaw && bl) {
        A.model.updateMatrixWorld(true); const y0 = bl.getWorldPosition(new T.Vector3()).y, r = A.rb.fr.get(jaw);
        const ax = new T.Vector3(1, 0, 0); rotAxisWorld(jaw, ax, 0.2); const y1 = bl.getWorldPosition(new T.Vector3()).y;
        A.rb.jawSign = y1 < y0 ? 1 : -1; jaw.quaternion.copy(r.q); jaw.updateWorldMatrix(true, true);
      }
    }
    const fa = { F: new T.Vector3(0, 0, 1), U: new T.Vector3(0, 1, 0), L: new T.Vector3(1, 0, 0) };
    const hN = new T.Quaternion(), vF = new T.Vector3(), vU = new T.Vector3(), vL = new T.Vector3(), vt = new T.Vector3(), vs = new T.Vector3(), pQ = new T.Quaternion();
    function rbFace() {
      const R = A.rb, f = R.f, head = A.bones.Head; if (!f) return; const cf = cfgFace;
      head.updateWorldMatrix(true, false); head.getWorldQuaternion(hN); hN.multiply(A.headRestInv);
      vF.copy(fa.F).applyQuaternion(hN); vU.copy(fa.U).applyQuaternion(hN); vL.copy(fa.L).applyQuaternion(hN);
      R.fr.forEach((r, b) => { b.position.copy(r.p); b.quaternion.copy(r.q); });
      head.updateWorldMatrix(false, true);
      const V = S.vis, m = S.mp, sc = A.sc || 1;
      const jo = clamp((V.aa || 0) * 0.95 + (V.O || 0) * 0.42 + (V.U || 0) * 0.24 + (V.E || 0) * 0.5 + (V.I || 0) * 0.3 + (V.DD || 0) * 0.3 + (V.kk || 0) * 0.34 + (V.nn || 0) * 0.24 + (V.CH || 0) * 0.24 + (V.SS || 0) * 0.14 + (V.TH || 0) * 0.28 + (V.RR || 0) * 0.3 + (V.FF || 0) * 0.1 + (m.jawOpen || 0) * 0.8, 0, 1);
      const round = clamp((V.O || 0) * 0.85 + (V.U || 0) * 1 + (V.CH || 0) * 0.45 + (V.RR || 0) * 0.3, 0, 1);
      const wide = clamp((V.E || 0) * 0.75 + (V.I || 0) * 1 + (V.SS || 0) * 0.6 + (V.aa || 0) * 0.15 + (V.DD || 0) * 0.3, 0, 1);
      const press = clamp(V.PP || 0, 0, 1), fv = clamp(V.FF || 0, 0, 1);
      const smile = clamp(((m.mouthSmileLeft || 0) + (m.mouthSmileRight || 0)) * 0.5, 0, 1), frown = clamp(((m.mouthFrownLeft || 0) + (m.mouthFrownRight || 0)) * 0.5, 0, 1);
      const browUp = clamp((m.browInnerUp || 0) + ((m.browOuterUpLeft || 0) + (m.browOuterUpRight || 0)) * 0.3, 0, 1.2), browDn = clamp(((m.browDownLeft || 0) + (m.browDownRight || 0)) * 0.5, 0, 1);
      const blink = ((m.eyeBlinkLeft || 0) + (m.eyeBlinkRight || 0)) * 0.5, wideEye = ((m.eyeWideLeft || 0) + (m.eyeWideRight || 0)) * 0.5, sq = ((m.eyeSquintLeft || 0) + (m.eyeSquintRight || 0)) * 0.5;
      const mv = (b, F, U, L) => {   // move a bone by (F forward, U up, L left) metres in head space
        if (!b) return; vt.set(0, 0, 0).addScaledVector(vF, F).addScaledVector(vU, U).addScaledVector(vL, L);
        b.parent.getWorldQuaternion(pQ).invert(); b.parent.getWorldScale(vs); vt.applyQuaternion(pQ).divideScalar(vs.x || 1); b.position.add(vt);
      };
      const k = cf.k || 1;
      // jaw
      const jaw = f.Jaw; if (jaw) { rotAxisWorld(jaw, vL, R.jawSign * (jo * 0.3 + press * -0.0) * (cf.jaw || 1)); }
      head.updateWorldMatrix(false, true);
      const u = 0.001 * k;   // 1 mm
      mv(f.UpperLip, round * 5 * u, jo * 1.2 * u - press * 1.5 * u + smile * 0.5 * u + wide * 0.8 * u, 0);
      mv(f.RUpperlip, round * 2 * u, jo * 0.8 * u - press * 1 * u + smile * 1.5 * u, 0); mv(f.LUpperlip, round * 2 * u, jo * 0.8 * u - press * 1 * u + smile * 1.5 * u, 0);
      mv(f.BottomLip, round * 5 * u - fv * 1 * u, press * 2.5 * u + fv * 4 * u, 0);
      mv(f.LMouthBottom, round * 2 * u, press * 1.5 * u + fv * 3 * u, 0); mv(f.RMouthBottom, round * 2 * u, press * 1.5 * u + fv * 3 * u, 0);
      const cornerUp = (smile * 8 - frown * 4 - jo * 1.5 + wide * 1.5) * u, cornerOut = (smile * 6.5 + wide * 5 - round * 11 + frown * 0.5) * u, cornerF = round * 4 * u;
      mv(f.LMouthCorner, cornerF, cornerUp, cornerOut); mv(f.RMouthCorner, cornerF, cornerUp, -cornerOut);
      mv(f.LCheek, 0, (smile * 6 + sq * 3) * u, 0); mv(f.RCheek, 0, (smile * 6 + sq * 3) * u, 0);
      mv(f.LCaninus, 0, smile * 2 * u, 0); mv(f.RCaninus, 0, smile * 2 * u, 0);
      // brows
      const bu = (browUp * 5 - browDn * 5 + wideEye * 3) * u, bin = (-browDn * 3) * u;
      mv(f.LInnerEyebrow, 0, bu * 1.2 - browUp * 1 * u, -bin * -1); mv(f.RInnerEyebrow, 0, bu * 1.2 - browUp * 1 * u, bin * -1);
      mv(f.MMiddleEyebrow, 0, bu, 0); mv(f.LOuterEyebrow, 0, bu * 0.7 + browUp * 1 * u, 0); mv(f.ROuterEyebrow, 0, bu * 0.7 + browUp * 1 * u, 0);
      // lids
      const lidT = (-blink * 7 + wideEye * 2 - sq * 2) * u, lidB = (blink * 2.5 + sq * 2) * u;
      mv(f.LEyeBlinkTop, 0, lidT, 0); mv(f.REyeBlinkTop, 0, lidT, 0); mv(f.LEyeBlinkBottom, 0, lidB, 0); mv(f.REyeBlinkBottom, 0, lidB, 0);
      mv(f.Nose, 0, browDn * 1.5 * u, 0);
      head.updateWorldMatrix(false, true);
    }
    const cfgFace = cfg.face || {};
    function applyPose(dt) {
      if (!A) return; const B = A.bones, t = S.t;
      // reset everything we drive
      ['Hips', 'Spine', 'Spine1', 'Spine2', 'Neck', 'Head', 'LeftEye', 'RightEye', 'LeftUpLeg', 'RightUpLeg'].forEach(n => { if (B[n]) B[n].quaternion.copy(A.rest.get(B[n])); });
      A.model.updateMatrixWorld(true);
      const k = Math.min(1, dt * 7);
      S.arms.forEach((a, i) => {
        if (a.state === 'stroke' && performance.now() > a.holdUntil) { Object.assign(a.tgt, GEST.rest, { pt: 0, wave: 0 }); a.state = 'rest'; }
        const om = a.state === 'stroke' ? 15 : 9.5, zt = a.state === 'stroke' ? 0.72 : 0.9; a.vel = a.vel || {};
        for (const p of GKEYS) { const x = a.cur[p] || 0, v = a.vel[p] || 0, acc = om * om * ((a.tgt[p] || 0) - x) - 2 * zt * om * v; const nv = v + acc * Math.min(dt, 0.033); a.vel[p] = nv; a.cur[p] = x + nv * Math.min(dt, 0.033); }
        a.pulse = (a.pulse || 0) * Math.max(0, 1 - dt * 5);
        const side = a.s > 0 ? 'Left' : 'Right';
        const idle = Math.sin(t * 1.1 + i * 2) * 0.03, talk = S.speaking ? Math.sin(t * 6 + i * 1.7) * 0.08 : 0;
        const g = { o: a.cur.o + idle + a.pulse * 0.06, f: a.cur.f + idle * 0.5 + a.pulse * 0.14, e: a.cur.e + talk * 0.5 - a.pulse * 0.22 };
        let waveOff = 0; if (a.tgt.wave) waveOff = Math.sin(t * 10) * 0.45;
        const d = armDirs(g, a.s); if (a.cur.i) d.f.applyAxisAngle(new T.Vector3(0, 1, 0), -a.s * a.cur.i);
        if (a.tgt.wave) d.f.applyAxisAngle(new T.Vector3(0, 0, 1), waveOff * a.s);
        const sh = B[side + 'Shoulder'];
        aim(B[side + 'Arm'], B[side + 'ForeArm'], d.u);
        aim(B[side + 'ForeArm'], B[side + 'Hand'], d.f);
        const hb = B[side + 'Hand']; if (hb) {
          hb.quaternion.copy(A.rest.get(hb)); hb.updateWorldMatrix(true, true);
          hb.getWorldQuaternion(wq); hb.parent.getWorldQuaternion(pq).invert();
          qq.setFromAxisAngle(d.f, (a.cur.r + Math.sin(t * 1.7 + i) * 0.06 + a.pulse * 0.12) * -a.s * FING.roll);
          hb.quaternion.copy(pq.multiply(qq.multiply(wq))); hb.updateWorldMatrix(true, true);
        }
      });
      // fingers: hinge each joint about the knuckle axis (perpendicular to the finger, parallel to the palm),
      // so every finger bends in a straight plane toward the palm and never twists sideways.
      for (const side of ['Left', 'Right']) {
        const a = S.arms[side === 'Left' ? 0 : 1], sg = side === 'Left' ? 1 : -1, hand = B[side + 'Hand'], fr = A.fing && A.fing[side]; if (!hand || !fr) continue;
        hand.updateWorldMatrix(true, true); hand.getWorldQuaternion(HQ);
        const nW = fr.n.clone().applyQuaternion(HQ);
        ['Index', 'Middle', 'Ring', 'Pinky', 'Thumb'].forEach((fn, fi) => {
          const chain = [1, 2, 3].map(n => B[side + 'Hand' + fn + n]); if (!chain[0] || !fr[fn]) return;
          chain.forEach(b => { if (b) { b.quaternion.copy(A.rest.get(b)); } }); hand.updateWorldMatrix(true, true);
          const thumb = fn === 'Thumb';
          const straight = fn === 'Index' && a.tgt.pt;
          const c = straight ? 0.02 : a.cur.c * [0.9, 1, 1.08, 1.18][Math.min(fi, 3)];
          const wob = Math.sin(t * 1.1 + fi * 1.7) * 0.025;
          let ax = fr[fn].ax.clone().applyQuaternion(HQ);
          if (!thumb) {   // spread in the palm plane
            const sp = (fi - 1.5) * 0.14 * a.cur.sp * FING.spsign * sg;
            if (Math.abs(sp) > 1e-4) { FQ.setFromAxisAngle(nW, sp); rotAxisWorld(chain[0], nW, sp); ax.applyQuaternion(FQ); }
          }
          const ang = thumb ? [0.35, 0.45, 0.4] : [1.35, 1.55, 1.05];
          chain.forEach((b, k) => { if (!b) return; rotAxisWorld(b, ax, (c * ang[k] + wob * (k + 1) * 0.5) * (thumb ? 0.55 : 1)); });
        });
      }
      A.model.updateMatrixWorld(true);
      // body
      const br = Math.sin(t * 1.7) * 0.006;
      const keep = []; if (A.rb) ['LeftUpLeg', 'RightUpLeg'].forEach(n => { if (B[n]) keep.push([B[n], B[n].getWorldQuaternion(new T.Quaternion())]); });
      rotWorld(B.Hips, 0, Math.sin(t * 0.35) * 0.03, Math.sin(t * 0.5) * 0.018);
      rotWorld(B.Spine, br + S.nod * 0.15 + (S.lean || 0), S.look.x * 0.05 + Math.sin(t * 0.5) * 0.02, Math.sin(t * 0.6) * 0.012);
      rotWorld(B.Spine2, br, S.look.x * 0.06, 0);
      const nk = A.rb ? 1 : 0;   // Rocketbox: clavicles hang off the neck, so the neck stays still and the head takes its share
      if (!nk) rotWorld(B.Neck, S.look.y * 0.08 + S.nod * 0.4, S.look.x * 0.2, S.emoTilt * 0.4 || 0);
      rotWorld(B.Head, S.look.y * (0.12 + nk * 0.08) + S.nod * (0.5 + nk * 0.4) + (S.speaking ? Math.sin(t * 5.2) * 0.01 : 0) + Math.sin(t * 0.9) * 0.008,
        S.look.x * (0.32 + nk * 0.2) + Math.sin(t * 0.45) * 0.03, (S.emoTilt || 0) * (0.6 + nk * 0.4) + (S.speaking ? Math.sin(t * 2.6) * 0.02 : 0));
      keep.forEach(([b, q]) => { b.parent.getWorldQuaternion(pq).invert(); b.quaternion.copy(pq.multiply(q)); b.updateWorldMatrix(true, true); });
      const ex = S.look.x * 0.25, ey = S.look.y * 0.15;
      rotWorld(B.LeftEye, ey, ex, 0); rotWorld(B.RightEye, ey, ex, 0);
    }
    function setMorph(name, v) { for (const m of A.morphMeshes) { const i = m.morphTargetDictionary[name]; if (i !== undefined) m.morphTargetInfluences[i] = v; } }
    const VISEMES = ['sil', 'PP', 'FF', 'TH', 'DD', 'kk', 'CH', 'SS', 'nn', 'RR', 'aa', 'E', 'I', 'O', 'U'];
    const KEYS = new Set(); for (const e in EMO) for (const k in EMO[e]) KEYS.add(k);
    ['jawOpen', 'mouthFunnel', 'mouthPucker', 'eyeBlinkLeft', 'eyeBlinkRight', 'mouthOpen'].forEach(k => KEYS.add(k));

    /* ----- speech ----- */
    const subEl = document.getElementById('sub'), noteEl = document.getElementById('note'), txt = document.getElementById('txt');
    let voices = []; const loadVoices = () => { try { voices = speechSynthesis.getVoices() || []; } catch (e) { voices = []; } };
    loadVoices(); try { speechSynthesis.onvoiceschanged = loadVoices; } catch (e) {}
    function pickVoice(text) {
      const want = /[ঀ-৿]/.test(text) ? 'bn' : (cfg.lang || 'en');
      const list = voices.filter(v => v.lang && v.lang.toLowerCase().startsWith(want)); if (!list.length) return null;
      const fem = /female|woman|zira|samantha|aria|jenny|susan|google/i, mal = /\bmale\b|david|mark|guy|daniel|bashkar|pradeep|google/i;
      return list.find(v => cfg.gender === 'm' ? (mal.test(v.name) && !/female/i.test(v.name)) : (fem.test(v.name) && !/\bmale\b/i.test(v.name))) || list[0];
    }
    let utter = null;
    const DOM = cfg.dominant === undefined ? 1 : cfg.dominant;   // arms[1] = the character's right hand
    function fire(ai, name, amp, hold) {
      const g = GEST[name] || GEST.rest, a = S.arms[ai], r = GEST.rest;
      for (const k of GKEYS) a.tgt[k] = r[k] + ((g[k] || 0) - r[k]) * amp;
      a.tgt.pt = g.pt || 0; a.tgt.wave = g.wave || 0; a.state = name === 'rest' ? 'rest' : 'stroke'; a.holdUntil = performance.now() + hold * 1000; a.pulse = 0.7;
    }
    function setGesture(name, both) { fire(DOM, name, 1, 1.2); if (both) fire(1 - DOM, name, 1, 1.2); S.gname = name; }
    function restArms() { S.arms.forEach(a => { Object.assign(a.tgt, GEST.rest, { pt: 0, wave: 0 }); a.state = 'rest'; }); S.gname = 'rest'; }
    function pickGesture(text) {
      const pool = S.emo === 'angry' ? ['point', 'fist', 'explain', 'raise'] : S.emo === 'sad' ? ['chest', 'shrug', 'palmsUp'] : S.emo === 'surprised' ? ['raise', 'openPalm', 'shrug']
        : S.emo === 'happy' ? ['openPalm', 'explain', 'raise', 'chest', 'palmsUp'] : ['explain', 'openPalm', 'palmsUp', 'chest', 'openPalm'];
      let g = pool[(Math.random() * pool.length) | 0]; if (text.includes('?')) g = Math.random() < 0.6 ? 'palmsUp' : 'openPalm'; else if (text.includes('!') && S.emo !== 'sad') g = Math.random() < 0.5 ? 'raise' : g; return g;
    }
    // timed gestures: pick stressed words from the text and fire a gesture just before each is spoken
    function makeEvents(text, tl) {
      const ev = []; let last = -9999; const re = /\S+/g; let m, lastIdx = -1;
      const chars = Array.from(text); const starts = []; let pos = 0;
      for (const w of text.split(/(\s+)/)) { if (w.trim()) starts.push([pos, w]); pos += Array.from(w).length; }
      starts.forEach(([idx, w], i) => {
        const t = tl.tl[Math.min(idx, tl.tl.length - 1)].t, len = Array.from(w).length, endp = /[?!।.,]$/.test(w);
        const stress = len >= 4 || endp || i === 0;
        if (stress && t - last > 650 && (i > 0 || /^(হ্যালো|নমস্কার|হাই|hello|hi)/i.test(w))) { ev.push({ t, w, kind: pickGesture(w) }); last = t; }
      });
      return ev;
    }
    function doEvent(e) {
      const r = Math.random(), amp = 0.8 + Math.random() * 0.35, hold = 0.55 + Math.random() * 0.55;
      if (r < 0.58) fire(DOM, e.kind, amp, hold); else if (r < 0.86) { fire(DOM, e.kind, amp, hold); fire(1 - DOM, e.kind, amp * 0.85, hold); } else fire(1 - DOM, e.kind, amp, hold);
      S.nod = 0.07 + Math.random() * 0.05; S.browKick = 0.55; S.lean = 0.05;
    }
    function speakExternal(text, durMs) {
      text = (text || '').trim(); if (!text) return; try { speechSynthesis.cancel(); } catch (e) {}
      S.emo = detectEmotion(text); S.holdEmo = performance.now() + 4000 + text.length * 90;
      const tl = buildTimeline(text, 1); const sc = durMs > 200 ? durMs / tl.total : 1; tl.tl.forEach(x => { x.t *= sc; x.d *= sc; }); tl.total *= sc;
      S.tl = tl; S.speaking = true; S.ext = true; S.levelSeen = false; S.t0 = performance.now(); S.words = text; S.gestUntil = 0; subEl.textContent = ''; S.events = makeEvents(text, tl); S.evi = 0;
    }
    function stopSpeech() { try { speechSynthesis.cancel(); } catch (e) {} S.speaking = false; S.tl = null; S.ext = false; restArms(); subEl.textContent = ''; }
    function speak(text, emotion) {
      text = (text || '').trim(); if (!text) return; stopSpeech();
      S.emo = emotion || detectEmotion(text); S.holdEmo = performance.now() + 4000 + text.length * 90;
      const rate = cfg.rate || 1; S.tl = buildTimeline(text, rate); S.speaking = true; S.t0 = performance.now() + 250; S.words = text; S.gestUntil = 0; subEl.textContent = text; S.events = makeEvents(text, S.tl); S.evi = 0;
      if (/^(হ্যালো|হাই|নমস্কার|আসসালামু|hello|hi\b|hey)/i.test(text)) { setGesture('wave'); S.gestUntil = performance.now() + 1800; }
      const v = pickVoice(text); let audio = false;
      try {
        if ('speechSynthesis' in window && v) {
          utter = new SpeechSynthesisUtterance(text); utter.voice = v; utter.lang = v.lang; utter.rate = rate; utter.pitch = cfg.pitch || 1;
          utter.onstart = () => { S.t0 = performance.now(); };
          utter.onboundary = ev => { if (!S.tl || ev.name === 'sentence') return; const idx = clamp(ev.charIndex || 0, 0, S.tl.tl.length - 1); S.t0 = performance.now() - S.tl.tl[idx].t; S.nod = 0.1; };
          utter.onend = utter.onerror = () => { S.speaking = false; S.tl = null; restArms(); setTimeout(() => { if (!S.speaking) subEl.textContent = ''; }, 1200); };
          speechSynthesis.speak(utter); audio = true;
        }
      } catch (e) {}
      noteEl.textContent = audio ? '' : 'এই ডিভাইসে এই ভাষার কণ্ঠস্বর নেই — মুখ ও হাত ঠিকই নড়বে, সাথে সাবটাইটেল। (Android Chrome-এ বাংলা কণ্ঠ থাকে।)';
    }

    const chips = document.getElementById('chips');
    (cfg.lines || []).forEach(l => { const b = document.createElement('button'); b.className = 'chip'; b.textContent = l.length > 34 ? l.slice(0, 32) + '…' : l; b.onclick = () => { txt.value = l; speak(l); }; chips.appendChild(b); });
    [['😊', 'happy'], ['😢', 'sad'], ['😠', 'angry'], ['😮', 'surprised'], ['😐', 'neutral']].forEach(([ic, k]) => { const b = document.createElement('button'); b.className = 'chip'; b.textContent = ic; b.onclick = () => { S.emo = k; S.holdEmo = performance.now() + 5000; }; chips.appendChild(b); });
    const cu = document.createElement('button'); cu.className = 'chip'; cu.textContent = '🔍 ক্লোজ-আপ'; cu.onclick = () => { S.closeup = !S.closeup; }; chips.appendChild(cu);
    document.getElementById('go').onclick = () => speak(txt.value || (cfg.lines || [''])[0]);
    document.getElementById('stop').onclick = stopSpeech;
    txt.addEventListener('keydown', e => { if (e.key === 'Enter' && !e.shiftKey) { e.preventDefault(); speak(txt.value); } });
    txt.value = cfg.greet || '';

    let drag = false, lx = 0;
    canvas.addEventListener('pointerdown', e => { drag = true; lx = e.clientX; });
    window.addEventListener('pointerup', () => { drag = false; });
    window.addEventListener('pointermove', e => { S.lookT.x = (e.clientX / innerWidth - 0.5) * 2; S.lookT.y = (e.clientY / innerHeight - 0.5) * 2; if (drag) { S.orbitT = clamp(S.orbitT + (e.clientX - lx) * 0.008, -1.2, 1.2); lx = e.clientX; } });
    canvas.addEventListener('wheel', e => { S.zoomT = clamp(S.zoomT + e.deltaY * 0.001, -0.6, 0.8); e.preventDefault(); }, { passive: false });
    function resize() { renderer.setSize(innerWidth, innerHeight, false); camera.aspect = innerWidth / innerHeight; camera.updateProjectionMatrix(); }
    window.addEventListener('resize', resize); resize();

    /* ----- loop ----- */
    let last = performance.now(); const hq = new T.Quaternion(), hpos = new T.Vector3();
    function frame(now) {
      const dt = Math.min(0.05, (now - last) / 1000); last = now; S.t += dt; const t = S.t;
      if (S.ready) {
        if (now > S.holdEmo && !S.speaking && S.emo !== 'neutral') S.emo = 'neutral';
        S.emoTilt = lerp(S.emoTilt || 0, S.emo === 'sad' ? -0.1 : S.emo === 'happy' ? 0.06 : 0, dt * 4);
        // lip sync
        let curV = 'sil', curW = 0;
        if (S.speaking && S.tl) {
          const el = now - S.t0;
          if (el >= 0 && el <= S.tl.total) {
            const arr = S.tl.tl; let lo = 0, hi = arr.length - 1; while (lo < hi) { const m = (lo + hi + 1) >> 1; if (arr[m].t <= el) lo = m; else hi = m - 1; }
            const s = arr[lo], ph = clamp((el - s.t) / s.d, 0, 1); curV = s.v; curW = s.w * (cfg.artic || 0.82) * (0.6 + 0.4 * Math.sin(Math.PI * Math.min(1, ph * 1.1)));
            if (S.levelSeen && now - (S.levelAt || 0) < 250) { const lv = S.level; if (lv < 0.04) { curV = 'sil'; curW = 0; } else curW *= clamp(0.3 + lv * 1.5, 0.3, 1.15); }
          } else if (el > S.tl.total + 700 && !(utter && speechSynthesis.speaking)) { S.speaking = false; S.tl = null; restArms(); }
          while (S.events && S.evi < S.events.length && now - S.t0 >= S.events[S.evi].t - 160) doEvent(S.events[S.evi++]);
        } else if (now - (S.talkAt || 0) < 220 && S.talkLv > 0.05) {
          // live voice: no text, so the mouth shape cycles while the loudness sets how wide
          curV = ['aa', 'E', 'O', 'I', 'aa', 'U'][Math.floor(t * 8) % 6]; curW = clamp(S.talkLv * 1.5, 0.15, 1);
          S.emo = S.emo === 'neutral' ? 'happy' : S.emo; S.holdEmo = now + 1500; S.liveTalk = true;
          if (now > S.gestUntil) { doEvent({ kind: pickGesture('') }); S.gestUntil = now + rnd(900, 1700); }
        } else if (!S.speaking && now > S.gestUntil + 800 && S.gname !== 'rest' && S.gname !== 'wave') restArms();
        const k = Math.min(1, dt * 26);
        for (const v of VISEMES) { S.vis[v] = lerp(S.vis[v] || 0, v === curV && v !== 'sil' ? curW : 0, k); setMorph('viseme_' + v, S.vis[v]); }
        const mo = Object.values(S.vis).reduce((a, b) => a + b, 0);
        // emotion + blink + jaw
        const tgt = {}; const em = EMO[S.emo] || EMO.neutral; for (const key of KEYS) tgt[key] = em[key] || 0;
        const calm = S.speaking ? 0.55 : 1; // keep smiles from fighting the visemes
        S.nextBlink -= dt; if (S.nextBlink < 0) { S.blink = 1; S.nextBlink = rnd(1.8, 4.5); } S.blink = Math.max(0, S.blink - dt * 7);
        const bl = Math.sin(S.blink * Math.PI);
        tgt.eyeBlinkLeft = Math.min(1, bl + (S.emo === 'happy' ? 0.15 : 0)); tgt.eyeBlinkRight = tgt.eyeBlinkLeft;
        tgt.browInnerUp = (tgt.browInnerUp || 0) + S.browKick * 0.45; tgt.browOuterUpLeft = (tgt.browOuterUpLeft || 0) + S.browKick * 0.3; tgt.browOuterUpRight = (tgt.browOuterUpRight || 0) + S.browKick * 0.3;
        tgt.jawOpen = (tgt.jawOpen || 0) + clamp(mo * 0.22, 0, 0.38); tgt.mouthOpen = 0;
        for (const m of A.morphMeshes) if (/beard/i.test(m.name)) { const i = m.morphTargetDictionary.mouthOpen; if (i !== undefined) m.morphTargetInfluences[i] = clamp(tgt.jawOpen * 1.6, 0, 1); }
        for (const key of KEYS) { let v = tgt[key]; if (/mouthSmile|mouthFrown|mouthPress/.test(key)) v *= calm; S.mp[key] = lerp(S.mp[key] || 0, v, Math.min(1, dt * (key.startsWith('eyeBlink') ? 40 : 8))); setMorph(key, S.mp[key]); }
        S.sacT = (S.sacT || 0) - dt; if (S.sacT < 0) { S.sac = { x: rnd(-0.35, 0.35) * (S.speaking ? 0.5 : 1), y: rnd(-0.22, 0.18) }; if (Math.random() < 0.55) { S.sac.x *= 0.15; S.sac.y *= 0.15; } S.sacT = rnd(0.5, 2.4); }
        S.sac = S.sac || { x: 0, y: 0 }; S.browKick = (S.browKick || 0) * Math.max(0, 1 - dt * 4); S.lean = (S.lean || 0) * Math.max(0, 1 - dt * 3);
        S.look.x = lerp(S.look.x, S.lookT.x + S.sac.x, Math.min(1, dt * 5)); S.look.y = lerp(S.look.y, S.lookT.y + S.sac.y, Math.min(1, dt * 5)); S.nod = lerp(S.nod, 0, Math.min(1, dt * 7));
        applyPose(dt); if (A.rb) rbFace();
        // head-follow for accessories
        const head = A.bones.Head; head.getWorldPosition(hpos); head.getWorldQuaternion(hq); A.headFollow.position.copy(hpos); A.headFollow.quaternion.copy(hq.multiply(A.headRestInv));
        if (A.halo) { A.halo.rotation.y += dt * 0.6; A.halo.position.y = A.haloY + Math.sin(t * 1.5) * 0.012; }
        if (A.moon) { A.moon.position.y = 1.5 + Math.sin(t * 1.2) * 0.05; }
      }
      ringM.opacity = 0.6 + 0.3 * Math.sin(t * 2); ring.rotation.z = t * 0.1; ring2.rotation.z = -t * 0.07;
      back.material.opacity = 0.75 + 0.2 * Math.sin(t * 1.3) + (S.speaking ? 0.15 * Math.abs(Math.sin(t * 6)) : 0);
      rimA.intensity = 2.8 + Math.sin(t * 1.5) * 0.5; rimB.intensity = 2.8 + Math.cos(t * 1.3) * 0.5;
      const pa = pg.attributes.position.array;
      for (let i = 0; i < N; i++) { pa[i * 3 + 1] += (falling ? -1 : 1) * pv[i] * dt; pa[i * 3] += Math.sin(t * 0.8 + ps[i]) * dt * 0.08; if (pa[i * 3 + 1] > 2.5) pa[i * 3 + 1] = 0; if (pa[i * 3 + 1] < 0) pa[i * 3 + 1] = 2.5; }
      pg.attributes.position.needsUpdate = true;
      S.orbit = lerp(S.orbit, S.orbitT, Math.min(1, dt * 6)); S.zoom = lerp(S.zoom, S.zoomT, Math.min(1, dt * 6)); S.cl = lerp(S.cl, S.closeup ? 1 : 0, Math.min(1, dt * 3));
      const hy = A ? A.headY : 1.55, asp = camera.aspect, base = embed ? (asp < 0.8 ? 3.1 : 2.5) : (asp < 0.8 ? 4.6 : 3.3);
      const dist = lerp(base, 1.15, S.cl) * (1 + S.zoom * 0.5), ty = lerp(embed ? 1.18 : 0.98, hy + 0.04, S.cl), cy = lerp(embed ? 1.3 : 1.2, hy + 0.04, S.cl);
      camera.position.set(Math.sin(S.orbit) * dist, cy, Math.cos(S.orbit) * dist); camera.lookAt(0, ty, 0);
      if (S.dbg) { camera.position.set(S.dbg.x, S.dbg.y, S.dbg.z); camera.lookAt(S.dbg.lx, S.dbg.ly, S.dbg.lz); }
      renderer.render(scene, camera); requestAnimationFrame(frame);
    }
    requestAnimationFrame(frame);
    window.ZC = { A: () => A, hand: side => { const v = new T.Vector3(); A.bones[side + 'Hand'].getWorldPosition(v); return [v.x, v.y, v.z]; }, dbg: (x, y, z, lx, ly, lz) => { S.dbg = x === null ? null : { x, y, z, lx, ly, lz }; }, talkLevel: v => { S.talkLv = v; S.talkAt = performance.now(); }, speakExternal, level: v => { if (v > 0.06) S.levelSeen = true; S.level = v; S.levelAt = performance.now(); }, boundary: i => { if (S.tl) { i = clamp(i | 0, 0, S.tl.tl.length - 1); S.t0 = performance.now() - S.tl.tl[i].t; S.nod = 0.1; } }, end: () => { S.speaking = false; S.tl = null; S.ext = false; restArms(); }, speak, stop: stopSpeech, setEmotion: e => { S.emo = e; S.holdEmo = performance.now() + 8000; }, state: S, closeup: v => { S.closeup = v; }, orbit: v => { S.orbitT = v; }, gesture: (n, hold) => { fire(0, n, 1, hold || 99); fire(1, n, 1, hold || 99); S.gestUntil = performance.now() + 3000; } };
    loadAvatar(cfg, onModel);
  }
  window.ZChar = { start };
})();
