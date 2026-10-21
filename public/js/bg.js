/* ══════════════════════════════════════════════════════════════
   ZYRAXON Background — 4 selectable engines:
     1. particles  (canvas nebula flow, zero deps)
     2. video      (bundled / custom mp4/webm loop)
     3. image      (still + parallax drift)
   Each scene also animates the character orb (drawn in voice.js).
   ══════════════════════════════════════════════════════════════ */
(function (global) {
  'use strict';

  const SCENES = [
    { id: 'girl', name: 'Girl (your video)', kind: 'video', src: '/assets/bg/girl.mp4', poster: '/assets/bg/girl.jpg' },
    { id: 'galaxy', name: 'Galaxy eye', kind: 'video', src: '/assets/bg/galaxy-eye.mp4', poster: '/assets/bg/galaxy-eye.jpg' },
    { id: 'hero', name: 'Tunnel', kind: 'video', src: '/assets/bg/hero.mp4', poster: '/assets/bg/hero.jpg' },
    { id: 'dream', name: 'Dream clouds', kind: 'video', src: '/assets/bg/dream.mp4', poster: '/assets/bg/dream.jpg' },
    { id: 'ink', name: 'Ink flow', kind: 'video', src: '/assets/bg/ink.mp4', poster: '/assets/bg/ink.jpg' },
    { id: 'aurora', name: 'Aurora light', kind: 'video', src: '/assets/bg/aurora.mp4', poster: '/assets/bg/aurora.jpg' },
    { id: 'city', name: 'City bokeh', kind: 'video', src: '/assets/bg/city.mp4', poster: '/assets/bg/city.jpg' },
    { id: 'space', name: 'Space tunnel', kind: 'video', src: '/assets/bg/space.mp4', poster: '/assets/bg/space.jpg' },
    { id: 'particles', name: 'Nebula particles', kind: 'particles' },
    { id: 'nebula', name: 'Carina nebula', kind: 'video', src: '/assets/gt/nebula.webm' },
    { id: 'rain', name: 'Rain on glass', kind: 'video', src: '/assets/gt/rain.webm' },
    { id: 'nightlights', name: 'Night lights (NASA)', kind: 'video', src: '/assets/gt/nightlights.webm' },
    { id: 'custom', name: 'Custom URL', kind: 'custom' },
  ];


  const BG = {
    scenes: SCENES,
    current: 'girl',
    speed: 1,
    sound: false,
    volume: 0.55,
    el: {},
    _raf: 0,

    init() {
      BG.el.canvas = document.getElementById('bg-canvas');
      BG.el.video = document.getElementById('bg-video');
      BG.el.image = document.getElementById('bg-image');
      BG._ctx = BG.el.canvas.getContext('2d');
      BG._resize();
      addEventListener('resize', BG._resize);
      // The scene is chosen by App.boot() from the saved setting. Setting a
      // default here as well loaded one clip and then swapped it for another a
      // moment later — the user saw the new video and then the old one return.
      // Phones block unmuted playback until the first touch. Keep the clip
      // muted until then, and start the sound the moment the user taps.
      const unlock = () => {
        if (BG.sound) BG.setSound(true);
        removeEventListener('pointerdown', unlock);
        removeEventListener('touchstart', unlock);
      };
      addEventListener('pointerdown', unlock, { once: true });
      addEventListener('touchstart', unlock, { once: true });
    },

    _resize() {
      const c = BG.el.canvas, dpr = Math.min(devicePixelRatio || 1, 2);
      c.width = innerWidth * dpr; c.height = innerHeight * dpr;
      BG._ctx.setTransform(dpr, 0, 0, dpr, 0, 0);
      BG._w = innerWidth; BG._h = innerHeight;
      BG._seed();
    },

    _seed() {
      const n = Math.round(Math.min(90, (BG._w * BG._h) / 14000));
      BG._stars = Array.from({ length: n }, () => ({
        x: Math.random() * BG._w, y: Math.random() * BG._h,
        z: Math.random(), r: Math.random() * 1.9 + 0.4,
        vx: (Math.random() - 0.5) * 0.28, vy: (Math.random() - 0.5) * 0.28,
        hue: [265, 320, 190, 220][(Math.random() * 4) | 0],
      }));
      BG._blobs = Array.from({ length: 4 }, (_, i) => ({
        x: Math.random() * BG._w, y: Math.random() * BG._h,
        r: 140 + Math.random() * 200, hue: [265, 320, 190, 300][i],
        sx: (Math.random() - .5) * .22, sy: (Math.random() - .5) * .22,
      }));
    },

    set(id, customUrl) {
      const scene = SCENES.find((s) => s.id === id) || SCENES[0];
      // Re-selecting the scene that is already playing must not tear the video
      // down and reload it: that is what made the picture blink and appear to
      // "go back" to the previous clip a couple of seconds in.
      const sameCustom = scene.kind === 'custom' ? (customUrl || '') === (BG._customUrl || '') : true;
      if (BG._ready && BG.current === id && sameCustom && BG.el.video && BG.el.video.src) return;
      BG._ready = true;
      BG.current = id;
      BG._customUrl = customUrl || '';
      BG.el.video.classList.remove('on'); BG.el.image.classList.remove('on');
      BG.el.video.pause(); BG.el.video.removeAttribute('src');
      BG.el.image.removeAttribute('src');
      cancelAnimationFrame(BG._raf);

      if (scene.kind === 'video') {
        BG.el.video.src = scene.src;
        if (scene.poster) BG.el.video.poster = scene.poster;
        BG.el.video.playbackRate = BG.speed;
        BG.el.video.classList.add('on');
        BG.el.video.play().catch(() => {});
      } else if (scene.kind === 'custom' && customUrl) {
        const isImg = /\.(png|jpe?g|webp|gif|avif)(\?|$)/i.test(customUrl);
        if (isImg) { BG.el.image.src = customUrl; BG.el.image.classList.add('on'); }
        else {
          BG.el.video.src = customUrl; BG.el.video.playbackRate = BG.speed;
          BG.el.video.classList.add('on'); BG.el.video.play().catch(() => {});
        }
      } else {
        BG._loop();
      }
    },

    setSpeed(v) { BG.speed = v; if (BG.el.video.playbackRate !== undefined) BG.el.video.playbackRate = v; },

    /** Background audio: the clip's own soundtrack, looping with the picture. */
    setSound(on) {
      BG.sound = !!on;
      const v = BG.el.video;
      v.muted = !BG.sound;
      v.volume = BG.volume;
      if (BG.sound && v.paused && v.src) v.play().catch(() => {});
    },
    setVolume(x) { BG.volume = Math.max(0, Math.min(1, Number(x))); if (BG.el.video) BG.el.video.volume = BG.volume; },
    /** Duck the bed while the assistant talks, so speech stays clear. */
    duck(on) {
      if (!BG.sound || !BG.el.video) return;
      const target = on ? Math.max(0.05, BG.volume * 0.18) : BG.volume;
      BG.el.video.volume = target;
    },

    _loop() {
      const ctx = BG._ctx, W = BG._w, H = BG._h, t = performance.now() / 1000;
      ctx.clearRect(0, 0, W, H);

      // drifting colour blobs
      for (const b of BG._blobs) {
        b.x += b.sx * BG.speed; b.y += b.sy * BG.speed;
        if (b.x < -b.r) b.x = W + b.r; if (b.x > W + b.r) b.x = -b.r;
        if (b.y < -b.r) b.y = H + b.r; if (b.y > H + b.r) b.y = -b.r;
        const g = ctx.createRadialGradient(b.x, b.y, 0, b.x, b.y, b.r);
        g.addColorStop(0, `hsla(${b.hue},85%,62%,.16)`);
        g.addColorStop(1, 'hsla(0,0%,0%,0)');
        ctx.fillStyle = g; ctx.beginPath(); ctx.arc(b.x, b.y, b.r, 0, 7); ctx.fill();
      }

      // nebula stars with connecting filaments
      const pts = [];
      for (const s of BG._stars) {
        s.x += s.vx * BG.speed * (0.4 + s.z); s.y += s.vy * BG.speed * (0.4 + s.z);
        if (s.x < 0) s.x = W; if (s.x > W) s.x = 0; if (s.y < 0) s.y = H; if (s.y > H) s.y = 0;
        const pulse = 0.55 + 0.45 * Math.sin(t * 1.4 + s.z * 8);
        ctx.beginPath(); ctx.arc(s.x, s.y, s.r * (0.7 + s.z), 0, 7);
        ctx.fillStyle = `hsla(${s.hue},95%,${68 + s.z * 14}%,${0.35 + pulse * 0.5})`;
        ctx.fill();
        pts.push(s);
      }
      ctx.lineWidth = 0.5;
      for (let i = 0; i < pts.length; i++) {
        for (let j = i + 1; j < pts.length; j++) {
          const a = pts[i], b = pts[j], dx = a.x - b.x, dy = a.y - b.y, d2 = dx * dx + dy * dy;
          if (d2 < 15000) {
            ctx.strokeStyle = `hsla(${a.hue},80%,70%,${(1 - d2 / 15000) * 0.14})`;
            ctx.beginPath(); ctx.moveTo(a.x, a.y); ctx.lineTo(b.x, b.y); ctx.stroke();
          }
        }
      }
      BG._raf = requestAnimationFrame(BG._loop);
    },
  };

  global.ZY = global.ZY || {};
  global.ZY.BG = BG;
})(window);
