(function () {
  if (window.__zxPicker) return;
  var on = false, box = null, tag = null, lastEl = null;

  function ours(el) { return el && (el === box || el === tag); }

  function selectorOf(el) {
    if (!el || el.nodeType !== 1) return '';
    var parts = [];
    while (el && el.nodeType === 1 && el !== document.documentElement) {
      if (el.id && /^[A-Za-z][\w-]*$/.test(el.id)) { parts.unshift('#' + el.id); break; }
      var name = el.tagName.toLowerCase();
      if (el === document.body) { parts.unshift('body'); break; }
      var cls = (typeof el.className === 'string' ? el.className : '').trim().split(/\s+/)
        .filter(function (c) { return c && /^[A-Za-z_][\w-]*$/.test(c); }).slice(0, 2);
      if (cls.length) name += '.' + cls.join('.');
      var p = el.parentElement;
      if (p) {
        var same = Array.prototype.filter.call(p.children, function (s) { return s.tagName === el.tagName; });
        if (same.length > 1) name += ':nth-of-type(' + (same.indexOf(el) + 1) + ')';
      }
      parts.unshift(name);
      el = p;
    }
    return parts.join(' > ');
  }

  function shortHtml(el) {
    var c = el.cloneNode(true);
    var kids = c.querySelectorAll('*');
    if (kids.length > 12) for (var i = kids.length - 1; i >= 12; i--) if (kids[i].parentNode) kids[i].parentNode.removeChild(kids[i]);
    var h = c.outerHTML.replace(/\s+/g, ' ');
    return h.length > 700 ? h.slice(0, 700) + '…' : h;
  }

  function describe(el) {
    var cs = window.getComputedStyle(el), r = el.getBoundingClientRect();
    var text = (el.innerText || el.textContent || '').replace(/\s+/g, ' ').trim();
    return {
      selector: selectorOf(el),
      tag: el.tagName.toLowerCase(),
      text: text.length > 200 ? text.slice(0, 200) + '…' : text,
      html: shortHtml(el),
      styles: 'color:' + cs.color + '; background:' + cs.backgroundColor + '; font-size:' + cs.fontSize +
        '; font-weight:' + cs.fontWeight + '; size:' + Math.round(r.width) + 'x' + Math.round(r.height),
      url: location.href
    };
  }

  function ensureUi() {
    if (box) return;
    box = document.createElement('div');
    box.style.cssText = 'position:fixed;z-index:2147483646;pointer-events:none;border:2px solid #ff3d8b;' +
      'background:rgba(255,61,139,.16);border-radius:4px;display:none;box-sizing:border-box';
    tag = document.createElement('div');
    tag.style.cssText = 'position:fixed;z-index:2147483647;pointer-events:none;background:#ff3d8b;color:#fff;' +
      'font:600 11px/1.2 sans-serif;padding:3px 6px;border-radius:4px;display:none;max-width:70vw;' +
      'white-space:nowrap;overflow:hidden;text-overflow:ellipsis';
    document.documentElement.appendChild(box);
    document.documentElement.appendChild(tag);
  }

  function show(el) {
    if (!el || ours(el)) return;
    lastEl = el;
    var r = el.getBoundingClientRect();
    box.style.display = 'block';
    box.style.left = r.left + 'px'; box.style.top = r.top + 'px';
    box.style.width = r.width + 'px'; box.style.height = r.height + 'px';
    tag.textContent = el.tagName.toLowerCase() + (el.id ? '#' + el.id : '');
    tag.style.display = 'block';
    tag.style.left = Math.max(0, r.left) + 'px';
    tag.style.top = Math.max(0, r.top - 22) + 'px';
  }

  function hover(e) { if (on) show(e.target); }
  function touch(e) {
    if (!on) return;
    var t = e.touches && e.touches[0];
    show(t ? (document.elementFromPoint(t.clientX, t.clientY) || e.target) : e.target);
  }
  function stop(e) { e.preventDefault(); e.stopPropagation(); if (e.stopImmediatePropagation) e.stopImmediatePropagation(); }

  function pick(e) {
    if (!on) return;
    stop(e);
    var el = e.target;
    if (ours(el)) el = lastEl;
    if (e.clientX != null) {
      var under = document.elementFromPoint(e.clientX, e.clientY);
      if (under && !ours(under)) el = under;
    }
    if (!el) return;
    var info = describe(el);
    api.off();
    var json = JSON.stringify(info);
    if (window.ZxPick && window.ZxPick.pick) window.ZxPick.pick(json);
    window.__zxLastPick = json;
  }

  function swallow(e) { if (on) stop(e); }

  var api = {
    on: function () {
      if (on) return;
      ensureUi(); on = true;
      document.addEventListener('mouseover', hover, true);
      document.addEventListener('touchstart', touch, { capture: true, passive: true });
      document.addEventListener('touchmove', touch, { capture: true, passive: true });
      document.addEventListener('click', pick, true);
      ['submit', 'auxclick', 'contextmenu'].forEach(function (n) { document.addEventListener(n, swallow, true); });
    },
    off: function () {
      on = false;
      document.removeEventListener('mouseover', hover, true);
      document.removeEventListener('touchstart', touch, true);
      document.removeEventListener('touchmove', touch, true);
      document.removeEventListener('click', pick, true);
      ['submit', 'auxclick', 'contextmenu'].forEach(function (n) { document.removeEventListener(n, swallow, true); });
      if (box) { box.style.display = 'none'; tag.style.display = 'none'; }
    },
    isOn: function () { return on; }
  };
  window.__zxPicker = api;
})();
