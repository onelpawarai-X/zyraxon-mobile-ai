/* =========================================================
   LUNA — moon phase engine, canvas rendering, interactions
   ========================================================= */

const SYNODIC = 29.530588853;        // days per lunar cycle
const NEW_MOON_JD = 2451550.1;      // 2000-01-06 18:14 UTC
const MS_DAY = 86400000;

/* ---------- date helpers ---------- */

function toJD(date){
  return date.getTime() / MS_DAY + 2440587.5;
}

function phaseFractionFor(date){
  const age = ((toJD(date) - NEW_MOON_JD) % SYNODIC + SYNODIC) % SYNODIC;
  return age / SYNODIC;
}

function dateForFraction(f, baseDate){
  const d = new Date(baseDate.getTime());
  return new Date(d.getTime() - (f * SYNODIC * MS_DAY));
}

/* ---------- the eight phases ---------- */

const PHASES = [
  {
    name: "New Moon", start: 0.0,
    desc: "The Moon slides between Earth and the Sun. Its lit side faces away from us, so the disc goes dark — and for a few nights the sky stays beautifully starless.",
    note: "Invisible to the eye"
  },
  {
    name: "Waxing Crescent", start: 0.03,
    desc: "A slim sliver on the right-hand side lights up. It sets a couple of hours after the Sun, and the first evening star appears beside it.",
    note: "Right side lit"
  },
  {
    name: "First Quarter", start: 0.22,
    desc: "Exactly half the disc is lit, and the Moon stands high in the sky around sunset. Its best evening showing of the whole cycle.",
    note: "Half lit, right side"
  },
  {
    name: "Waxing Gibbous", start: 0.28,
    desc: "More than half is now lit and the terminator is on its way back. It rises before sunset and grows fatter each night.",
    note: "More than half lit"
  },
  {
    name: "Full Moon", start: 0.47,
    desc: "Earth sits between the Sun and the Moon, so we see the whole sunlit face. It rises at sunset and sets at sunrise, and every full moon has a slightly different name by season.",
    note: "Fully lit"
  },
  {
    name: "Waning Gibbous", start: 0.53,
    desc: "The shadow creeps in from the east. The Moon now rises in the late evening and hangs over the pre-dawn sky.",
    note: "More than half lit"
  },
  {
    name: "Last Quarter", start: 0.72,
    desc: "Half lit again, but this time the left side is bright. It rises around midnight and stands tall in the sky at dawn.",
    note: "Half lit, left side"
  },
  {
    name: "Waning Crescent", start: 0.78,
    desc: "Only a fading crescent remains, hugging the morning Sun low on the eastern horizon. The cycle is nearly complete and the new moon is close.",
    note: "Right edge returns to new"
  }
];

function phaseForFraction(f){
  let current = PHASES[0];
  for (const p of PHASES){
    if (f >= p.start) current = p;
  }
  return current;
}

function illumination(f){
  return (1 - Math.cos(2 * Math.PI * f)) / 2;
}

/* ---------- seeded randomness + moon texture ---------- */

function mulberry32(seed){
  return function(){
    seed |= 0; seed = (seed + 0x6D2B79F5) | 0;
    let t = Math.imul(seed ^ (seed >>> 15), 1 | seed);
    t = (t + Math.imul(t ^ (t >>> 7), 61 | t)) ^ t;
    return ((t ^ (t >>> 14)) >>> 0) / 4294967296;
  };
}

const textureCache = new Map();

function buildTexture(size){
  if (textureCache.has(size)) return textureCache.get(size);

  const rand = mulberry32(20240711);
  const n = size * size;
  const albedo = new Float32Array(n);
  const depth = new Float32Array(n);

  // --- maria: large dark basalt plains ---
  const maria = [];
  for (let i = 0; i < 9; i++){
    const ang = rand() * Math.PI * 2;
    const rad = Math.pow(rand(), 0.6) * 0.72;
    maria.push({
      x: Math.cos(ang) * rad,
      y: Math.sin(ang) * rad,
      r: 0.16 + rand() * 0.26,
      d: 0.10 + rand() * 0.12
    });
  }

  // --- craters: dark floor, bright rim ---
  const craters = [];
  for (let i = 0; i < 90; i++){
    const ang = rand() * Math.PI * 2;
    const rad = Math.pow(rand(), 0.55) * 0.95;
    craters.push({
      x: Math.cos(ang) * rad,
      y: Math.sin(ang) * rad,
      r: 0.010 + Math.pow(rand(), 2.1) * 0.075,
      d: 0.05 + rand() * 0.16
    });
  }

  for (let py = 0; py < size; py++){
    for (let px = 0; px < size; px++){
      const u = ((px + 0.5) / size) * 2 - 1;
      const v = ((py + 0.5) / size) * 2 - 1;
      const r2 = u * u + v * v;
      const i = py * size + px;
      if (r2 > 1){ albedo[i] = 0; depth[i] = 0; continue; }

      let a = 0.86 + rand() * 0.14;   // fine grain
      const d = Math.sqrt(r2);

      for (const m of maria){
        const dd = Math.hypot(u - m.x, v - m.y);
        if (dd < m.r){
          const edge = Math.min(1, (m.r - dd) / (m.r * 0.35));
          a -= m.d * edge;
        }
      }

      for (const c of craters){
        const dd = Math.hypot(u - c.x, v - c.y);
        if (dd < c.r){
          const t = dd / c.r;
          a -= c.d * (1 - t * t) * 0.9;
        } else if (dd < c.r * 1.3){
          const t = (dd - c.r) / (c.r * 0.3);
          a += c.d * 0.35 * (1 - t);   // bright rim
        }
      }

      // gentle relief shading toward the limbs
      const z = Math.sqrt(Math.max(0, 1 - r2));
      depth[i] = z;
      albedo[i] = Math.max(0.06, Math.min(1, a));
    }
  }

  const tex = { size, albedo, depth };
  textureCache.set(size, tex);
  return tex;
}

/* ---------- render a moon phase ---------- */

function renderMoon(ctx, size, f, opts){
  opts = opts || {};
  const tex = buildTexture(size);
  const img = ctx.createImageData(size, size);
  const data = img.data;

  const theta = 2 * Math.PI * f;
  const Lx = Math.sin(theta);
  const Lz = -Math.cos(theta);
  const bg = opts.bg || [7, 9, 18];
  const tint = opts.tint || [255, 246, 226];
  const sheen = opts.sheen === undefined ? 0.10 : opts.sheen;

  for (let py = 0; py < size; py++){
    for (let px = 0; px < size; px++){
      const i = py * size + px;
      const o = i * 4;
      const z = tex.depth[i];
      const u = ((px + 0.5) / size) * 2 - 1;
      const v = ((py + 0.5) / size) * 2 - 1;

      if (z === 0 && tex.albedo[i] === 0){
        data[o] = bg[0]; data[o+1] = bg[1]; data[o+2] = bg[2]; data[o+3] = 255;
        continue;
      }

      const dot = u * Lx + z * Lz;          // sun angle on the surface
      let lum;
      if (dot > 0){
        lum = 0.06 + 0.94 * Math.pow(dot, 0.72);
      } else {
        lum = 0.0;
      }

      const a = tex.albedo[i] * lum;
      // earthshine on the dark limb
      const dark = tex.albedo[i] * (1 - lum) * sheen;
      const R = tint[0] * a + 26 * dark;
      const G = tint[1] * a + 30 * dark;
      const B = tint[2] * a + 44 * dark;

      data[o]   = Math.min(255, R);
      data[o+1] = Math.min(255, G);
      data[o+2] = Math.min(255, B);
      data[o+3] = 255;
    }
  }
  ctx.putImageData(img, 0, 0);
}

/* ---------- starfield ---------- */

function initStars(){
  const cv = document.getElementById("stars");
  const ctx = cv.getContext("2d");
  const rand = mulberry32(77123);
  let stars = [];

  function resize(){
    cv.width = window.innerWidth;
    cv.height = window.innerHeight;
    const count = Math.min(320, Math.floor((cv.width * cv.height) / 6000));
    stars = [];
    for (let i = 0; i < count; i++){
      stars.push({
        x: rand() * cv.width,
        y: rand() * cv.height,
        r: rand() * 1.3 + 0.25,
        p: rand() * Math.PI * 2,
        s: 0.5 + rand() * 1.4
      });
    }
  }

  function frame(t){
    ctx.clearRect(0, 0, cv.width, cv.height);
    for (const s of stars){
      const tw = 0.55 + 0.45 * Math.sin(t / 700 * s.s + s.p);
      ctx.beginPath();
      ctx.fillStyle = "rgba(255,255,255," + (0.25 + 0.6 * tw).toFixed(3) + ")";
      ctx.arc(s.x, s.y, s.r, 0, Math.PI * 2);
      ctx.fill();
    }
    requestAnimationFrame(frame);
  }

  resize();
  window.addEventListener("resize", resize);
  requestAnimationFrame(frame);
}

/* ---------- hero moon (real current phase) ---------- */

function initHero(){
  const cv = document.getElementById("heroMoon");
  const ctx = cv.getContext("2d");
  const now = new Date();
  const f = phaseFractionFor(now);
  renderMoon(ctx, cv.width, f);

  const phase = phaseForFraction(f);
  const illum = Math.round(illumination(f) * 100);
  document.getElementById("phaseName").textContent = phase.name;
  document.getElementById("phaseIllum").textContent = illum + "%";
}

/* ---------- phase lab ---------- */

const state = {
  f: phaseFractionFor(new Date()),
  auto: true,
  userHold: 0
};

function initPhaseLab(){
  const slider = document.getElementById("phaseSlider");
  const dateInput = document.getElementById("dateInput");
  const canvas = document.getElementById("phaseCanvas");
  const ctx = canvas.getContext("2d");
  const label = document.getElementById("phaseLabel");
  const desc = document.getElementById("phaseDesc");
  const illum = document.getElementById("illumLabel");
  const dayLbl = document.getElementById("dayLabel");
  const chipsBox = document.getElementById("phaseChips");
  const grid = document.getElementById("phaseGrid");

  // chips
  PHASES.forEach((p, i) => {
    const chip = document.createElement("button");
    chip.className = "chip";
    chip.type = "button";
    chip.textContent = p.name;
    chip.dataset.index = i;
    chip.addEventListener("click", () => {
      state.f = p.start + 0.015;
      state.userHold = Date.now() + 4000;
      slider.value = Math.round(state.f * 1000);
      render();
    });
    chipsBox.appendChild(chip);
  });

  // grid of mini phase cards
  PHASES.forEach((p, i) => {
    const card = document.createElement("div");
    card.className = "phase-card";
    card.innerHTML = "<canvas width='150' height='150'></canvas><b>" + p.name + "</b><span>" + p.note + "</span>";
    card.addEventListener("click", () => {
      state.f = p.start + 0.015;
      state.userHold = Date.now() + 4000;
      slider.value = Math.round(state.f * 1000);
      render();
      document.getElementById("phases").scrollIntoView({ behavior: "smooth" });
    });
    grid.appendChild(card);
    const cctx = card.querySelector("canvas").getContext("2d");
    renderMoon(cctx, 150, p.start + 0.015);
  });

  slider.addEventListener("input", () => {
    state.f = slider.value / 1000;
    state.userHold = Date.now() + 4000;
    render();
  });

  dateInput.addEventListener("change", () => {
    if (!dateInput.value) return;
    const d = new Date(dateInput.value + "T12:00:00");
    if (isNaN(d.getTime())) return;
    state.f = phaseFractionFor(d);
    state.userHold = Date.now() + 60000;
    slider.value = Math.round(state.f * 1000);
    render();
  });

  function render(){
    const f = state.f;
    renderMoon(ctx, canvas.width, f);
    const p = phaseForFraction(f);
    label.textContent = p.name;
    desc.textContent = p.desc;
    illum.textContent = Math.round(illumination(f) * 100) + "%";
    dayLbl.textContent = (f * SYNODIC).toFixed(1);
    const activeIdx = PHASES.indexOf(p);
    chipsBox.querySelectorAll(".chip").forEach((c, i) => {
      c.classList.toggle("active", i === activeIdx);
    });
  }

  render();
  return render;
}

/* ---------- orbit diagram ---------- */

function initOrbit(){
  const cv = document.getElementById("orbitCanvas");
  const ctx = cv.getContext("2d");
  const W = cv.width, H = cv.height;
  const cx = W * 0.60, cy = H * 0.5;
  const R = Math.min(W, H) * 0.36;
  const inset = 62;

  function draw(f){
    ctx.clearRect(0, 0, W, H);

    // sunlight from the left
    const g = ctx.createLinearGradient(0, 0, cx - R, 0);
    g.addColorStop(0, "rgba(255, 205, 120, 0.20)");
    g.addColorStop(1, "rgba(255, 205, 120, 0)");
    ctx.fillStyle = g;
    ctx.fillRect(0, 0, cx - R, H);

    // sun
    const sx = 44, sy = cy;
    const sg = ctx.createRadialGradient(sx, sy, 4, sx, sy, 42);
    sg.addColorStop(0, "#ffe6a8");
    sg.addColorStop(0.35, "rgba(255, 200, 90, 0.55)");
    sg.addColorStop(1, "rgba(255, 200, 90, 0)");
    ctx.fillStyle = sg;
    ctx.beginPath(); ctx.arc(sx, sy, 42, 0, Math.PI * 2); ctx.fill();
    ctx.fillStyle = "#ffd27a";
    ctx.beginPath(); ctx.arc(sx, sy, 16, 0, Math.PI * 2); ctx.fill();

    // orbit path
    ctx.strokeStyle = "rgba(255,255,255,0.16)";
    ctx.setLineDash([5, 7]);
    ctx.lineWidth = 1.4;
    ctx.beginPath(); ctx.arc(cx, cy, R, 0, Math.PI * 2); ctx.stroke();
    ctx.setLineDash([]);

    // earth
    const eg = ctx.createRadialGradient(cx - 12, cy - 12, 3, cx, cy, 30);
    eg.addColorStop(0, "#7fb2ff");
    eg.addColorStop(0.6, "#2f6fd8");
    eg.addColorStop(1, "#123a7a");
    ctx.fillStyle = eg;
    ctx.beginPath(); ctx.arc(cx, cy, 28, 0, Math.PI * 2); ctx.fill();
    ctx.strokeStyle = "rgba(150,200,255,0.5)";
    ctx.lineWidth = 1;
    ctx.stroke();

    // moon position from phase fraction: new moon points sunward (left)
    const theta = 2 * Math.PI * f - Math.PI;
    const mx = cx + R * Math.cos(theta);
    const my = cy + R * Math.sin(theta);

    // moon, always half lit toward the sun
    const mr = 17;
    const mg = ctx.createRadialGradient(mx - 5, my - 5, 2, mx, my, mr);
    mg.addColorStop(0, "#f3efe4");
    mg.addColorStop(1, "#9c978a");
    ctx.fillStyle = mg;
    ctx.beginPath(); ctx.arc(mx, my, mr, 0, Math.PI * 2); ctx.fill();

    // shadow line across the moon (lit from the left)
    ctx.save();
    ctx.beginPath(); ctx.arc(mx, my, mr, 0, Math.PI * 2); ctx.clip();
    ctx.fillStyle = "rgba(6, 8, 16, 0.72)";
    ctx.fillRect(mx, my - mr, mr, mr * 2);
    ctx.restore();

    // connector earth -> moon
    ctx.strokeStyle = "rgba(233, 207, 143, 0.35)";
    ctx.setLineDash([3, 6]);
    ctx.beginPath(); ctx.moveTo(cx, cy); ctx.lineTo(mx, my); ctx.stroke();
    ctx.setLineDash([]);

    // phase inset: what Earth actually sees
    const ix = cx, iy = cy + R + 44;
    ctx.save();
    ctx.beginPath(); ctx.arc(ix, iy, inset / 2, 0, Math.PI * 2);
    ctx.clip();
    const mini = document.createElement("canvas");
    mini.width = mini.height = 120;
    renderMoon(mini.getContext("2d"), 120, f, { sheen: 0.06 });
    ctx.drawImage(mini, ix - inset / 2, iy - inset / 2, inset, inset);
    ctx.restore();
    ctx.strokeStyle = "rgba(255,255,255,0.28)";
    ctx.lineWidth = 1;
    ctx.beginPath(); ctx.arc(ix, iy, inset / 2, 0, Math.PI * 2); ctx.stroke();

    ctx.fillStyle = "#9aa5bf";
    ctx.font = "11px system-ui, sans-serif";
    ctx.textAlign = "center";
    ctx.fillText("as seen from Earth", ix, iy + inset / 2 + 14);
    ctx.textAlign = "left";
    ctx.fillText("Sunlight", 14, 22);
  }

  return draw;
}

/* ---------- boot ---------- */

document.addEventListener("DOMContentLoaded", () => {
  initStars();
  initHero();

  const render = initPhaseLab();
  const drawOrbit = initOrbit();

  const slider = document.getElementById("phaseSlider");
  slider.value = Math.round(state.f * 1000);

  let last = performance.now();
  function loop(now){
    const dt = (now - last) / 1000;
    last = now;

    if (state.auto && Date.now() > state.userHold){
      state.f = (state.f + dt / 42) % 1;   // one full cycle every 42 seconds
      slider.value = Math.round(state.f * 1000);
    }

    render();
    drawOrbit(state.f);
    requestAnimationFrame(loop);
  }
  requestAnimationFrame(loop);
});
