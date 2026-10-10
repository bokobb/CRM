/* Фотоальбом товара (макет «yug-sladosti-fotoalbom»): по нажатию на элемент с data-photos — JSON [{src,w,h,alt}] — открывается альбом. Стили — album.css. */
(function () {
  'use strict';
  if (window.YugAlbum) return;

  var TILT = [-2.2, 1.6, -1.2, 2.4, -1.8, 1.2];    // наклон отпечатка на каждой странице, в градусах
  var dlg, stage, book, thumbs, count, btnPrev, btnNext, btnClose;
  var S = null;                                    // открытый альбом: { title, meta, photos, cur, leaf, opener }
  var M = null;                                    // размеры страницы в пикселях
  var busy = false, queued = null, swiped = false, start = null, pressed = null, wheelX = 0, wheelAt = 0;

  function el(tag, cls, text) {
    var node = document.createElement(tag);
    if (cls) node.className = cls;
    if (text !== undefined) node.textContent = text;
    return node;
  }
  function icon(d, size, width) {
    return '<svg viewBox="0 0 24 24" width="' + size + '" height="' + size + '" fill="none" stroke="currentColor" stroke-width="' + (width || 2.2) +
      '" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true"><path d="' + d + '"></path></svg>';
  }
  function calm() { return !!(window.matchMedia && window.matchMedia('(prefers-reduced-motion: reduce)').matches); }

  /* ---- карточки на странице ---- */
  function parse(node) {
    var raw = (node.getAttribute('data-photos') || '').trim(), list = [];
    if (!raw) return list;
    if (raw.charAt(0) === '[') { try { list = JSON.parse(raw); } catch (e) { list = []; } } else { list = raw.split('|'); }
    return list.map(function (p) { return typeof p === 'string' ? { src: p.trim() } : p; })
      .filter(function (p) { return p && p.src; });
  }
  function scan(root) {
    var nodes = (root && root.querySelectorAll ? root : document).querySelectorAll('[data-photos]:not([data-alb])');
    Array.prototype.forEach.call(nodes, function (node) {
      var n = parse(node).length, title = node.getAttribute('data-title') || '';
      node.setAttribute('data-alb', n);
      if (!n) return;
      if (!/^(BUTTON|A)$/.test(node.tagName)) {
        node.setAttribute('role', 'button');
        if (!node.hasAttribute('tabindex')) node.tabIndex = 0;
      }
      if (!node.hasAttribute('aria-label')) {
        node.setAttribute('aria-label', (title ? title + ': ' : '') + 'открыть фотоальбом' + (n > 1 ? ', ' + n + ' фото' : ''));
      }
      if (n > 1) node.classList.add('alb-many');              // под рамкой появится стопка отпечатков (если у элемента class="alb-fan")
    });
  }
  function openFrom(node) {
    var photos = parse(node);
    if (!photos.length) return;
    var cover = node.querySelector('img');
    if (cover && !photos[0].preview) photos[0].preview = cover.currentSrc || cover.src;   // пока грузится крупное фото, показываем то, что уже есть в карточке
    open({ title: node.getAttribute('data-title') || (cover && cover.alt) || '', meta: node.getAttribute('data-meta') || '', photos: photos, index: 0, opener: node });
  }

  /* ---- окно альбома собирается один раз, при первом открытии ---- */
  function build() {
    if (dlg) return;
    dlg = el('dialog', 'alb');
    var inner = el('div', 'alb-in'), top = el('div', 'alb-top');
    count = el('p', 'alb-count');
    count.setAttribute('aria-live', 'polite');
    btnClose = el('button', 'alb-x');
    btnClose.type = 'button';
    btnClose.setAttribute('aria-label', 'Закрыть альбом');
    btnClose.innerHTML = icon('M6 6l12 12M18 6 6 18', 22);
    top.appendChild(count); top.appendChild(btnClose);
    stage = el('div', 'alb-stage');
    btnPrev = el('button', 'alb-nav alb-prev');
    btnPrev.type = 'button';
    btnPrev.setAttribute('aria-label', 'Предыдущее фото');
    btnPrev.innerHTML = icon('M14.5 5.5 8 12l6.5 6.5', 24, 2.4);
    btnNext = el('button', 'alb-nav alb-next');
    btnNext.type = 'button';
    btnNext.setAttribute('aria-label', 'Следующее фото');
    btnNext.innerHTML = icon('M9.5 5.5 16 12l-6.5 6.5', 24, 2.4);
    book = el('div', 'alb-book');
    book.appendChild(el('div', 'alb-rings')).innerHTML = '<i></i><i></i><i></i><i></i>';   // кольца переплёта
    stage.appendChild(btnPrev); stage.appendChild(book); stage.appendChild(btnNext);
    thumbs = el('ol', 'alb-thumbs');
    inner.appendChild(top); inner.appendChild(stage); inner.appendChild(thumbs);
    dlg.appendChild(inner);
    document.body.appendChild(dlg);

    btnClose.addEventListener('click', close);
    btnPrev.addEventListener('click', function () { go(S.cur - 1); });
    btnNext.addEventListener('click', function () { go(S.cur + 1); });
    dlg.addEventListener('close', cleanup);
    dlg.addEventListener('pointerdown', function (e) { pressed = e.target; swiped = false; }, true);
    dlg.addEventListener('click', function (e) {               // нажатие мимо страницы закрывает альбом (но не протяжка, начатая на странице)
      var t = e.target;
      if (swiped) { swiped = false; return; }
      if (t !== pressed) return;
      if (t === dlg || t === inner || t === stage || t === top) close();
    });
    book.addEventListener('click', function (e) {              // нажатие на страницу листает: правая половина — вперёд, левая — назад
      if (swiped) { swiped = false; return; }
      if (!S || S.photos.length < 2) return;
      var r = book.getBoundingClientRect();
      go(S.cur + (e.clientX > r.left + r.width / 2 ? 1 : -1));
    });
    stage.addEventListener('pointerdown', function (e) {
      if (e.pointerType === 'mouse' && e.button !== 0) return;
      start = { x: e.clientX, y: e.clientY };
      swiped = false;
    });
    stage.addEventListener('pointerup', function (e) {
      if (!start || !S) return;
      var dx = e.clientX - start.x, dy = e.clientY - start.y;
      start = null;
      if (Math.abs(dx) < 44 || Math.abs(dx) < Math.abs(dy) * 1.3) return;
      swiped = true;
      go(S.cur + (dx < 0 ? 1 : -1));
    });
    stage.addEventListener('pointercancel', function () { start = null; });
    /* Страница под альбомом не должна ездить: колесо и протяжку забираем себе. Горизонтальная протяжка по тачпаду листает. */
    dlg.addEventListener('wheel', function (e) {
      e.preventDefault();
      var now = Date.now();
      if (!S || now - wheelAt < 900 || Math.abs(e.deltaX) < 4 || Math.abs(e.deltaX) <= Math.abs(e.deltaY)) { wheelX = 0; return; }   // после перелистывания ждём, пока тачпад «докатится»
      wheelX += e.deltaX;
      if (Math.abs(wheelX) < 70) return;
      go(S.cur + (wheelX > 0 ? 1 : -1));
      wheelX = 0; wheelAt = now;
    }, { passive: false });
    dlg.addEventListener('touchmove', function (e) { if (e.touches.length < 2) e.preventDefault(); }, { passive: false });
    dlg.addEventListener('keydown', function (e) {
      if (!S) return;
      var k = e.key;
      if (k === 'ArrowRight' || k === 'PageDown') { e.preventDefault(); go(S.cur + 1); }
      else if (k === 'ArrowLeft' || k === 'PageUp') { e.preventDefault(); go(S.cur - 1); }
      else if (k === 'Home') { e.preventDefault(); go(0); }
      else if (k === 'End') { e.preventDefault(); go(S.photos.length - 1); }
      else if (k === 'Escape' && !dlg.showModal) { close(); }
      else if (k === 'ArrowUp' || k === 'ArrowDown' || (k === ' ' && e.target.tagName !== 'BUTTON')) { e.preventDefault(); }
    });
    window.addEventListener('resize', function () { if (S) layout(); });
    if (window.ResizeObserver) new ResizeObserver(function () { if (S) layout(); }).observe(stage);
  }

  /* ---- размеры: страница вписывается в окно, отпечаток — в страницу ---- */
  function layout() {
    var r = stage.getBoundingClientRect();
    if (!r.width || !r.height) return;
    var roomy = r.width >= 640 + 2 * 108;                      // рядом со страницей хватает места для стрелок
    var mx = roomy ? 0 : 20, my = roomy ? 30 : 14;            // поля вокруг страницы под обложку альбома
    var bw = Math.max(200, Math.min(r.width - mx, 640, (r.height - my) * 0.84));
    var bh = Math.max(240, Math.min(r.height - my, bw * 1.44));
    var tight = r.width < bw + 2 * 108;
    M = {
      bw: Math.round(bw), bh: Math.round(bh), px: Math.round(bw * (tight ? 0.08 : 0.1)), pt: Math.round(bw * 0.065), pb: Math.round(bw * 0.05),
      gap: Math.round(bw * 0.032), mat: Math.round(Math.max(6, Math.min(11, bw * 0.017)))
    };
    var vars = {
      '--bw': M.bw, '--bh': M.bh, '--px': M.px, '--pt': M.pt, '--pb': M.pb, '--gap': M.gap, '--mat': M.mat,
      '--hand': Math.round(Math.max(23, Math.min(34, bw * 0.056))), '--th': Math.round(Math.max(18, Math.min(28, bw * 0.046))),
      '--hole': Math.round(Math.max(8, Math.min(13, bw * 0.021))),
      '--ring': Math.round(Math.max(5, Math.min(8, bw * 0.013))), '--ring-out': tight ? 7 : 14, '--cover': tight ? 5 : 12
    };
    Object.keys(vars).forEach(function (k) { dlg.style.setProperty(k, vars[k] + 'px'); });
    dlg.classList.toggle('alb--tight', tight);
    Array.prototype.forEach.call(book.querySelectorAll('.alb-leaf'), fit);
  }
  function fit(leaf) {
    if (!M || !leaf._img) return;
    var nat = leaf._nat;
    var maxW = M.bw - M.px * 2 - M.mat * 2;
    var maxH = M.bh - M.pt - M.pb - M.gap - leaf._cap.offsetHeight - M.mat * 2;
    var k = Math.min(maxW / nat.w, maxH / nat.h, 2);
    leaf._img.style.width = Math.max(40, Math.floor(nat.w * k)) + 'px';
    leaf._img.style.height = Math.max(40, Math.floor(nat.h * k)) + 'px';
  }

  /* ---- одна страница альбома ---- */
  function makeLeaf(i) {
    var ph = S.photos[i];
    var leaf = el('div', 'alb-leaf'), print = el('div', 'alb-print'), img = new Image(), cap = el('div', 'alb-cap');
    print.setAttribute('data-v', i % 4);
    print.style.setProperty('--tilt', TILT[i % TILT.length] + 'deg');
    img.alt = ph.alt || (S.title ? S.title + ', фото ' + (i + 1) : 'Фото ' + (i + 1));
    img.decoding = 'async';
    img.draggable = false;
    print.appendChild(img);
    print.appendChild(el('i', 'alb-tape'));
    print.appendChild(el('i', 'alb-tape'));
    if (S.title) cap.appendChild(el('p', 'alb-name', S.title));
    if (S.meta) cap.appendChild(el('p', 'alb-meta', S.meta));
    leaf._shade = el('i', 'alb-shade');
    leaf._cast = el('i', 'alb-shade alb-shade--cast');
    leaf.appendChild(print); leaf.appendChild(cap); leaf.appendChild(leaf._shade); leaf.appendChild(leaf._cast);
    leaf._img = img; leaf._cap = cap;
    leaf._nat = { w: ph.w || 3, h: ph.h || 4 };             // до загрузки считаем снимок вертикальным 3:4
    img.addEventListener('load', function () {
      if (img.naturalWidth) leaf._nat = { w: img.naturalWidth, h: img.naturalHeight };
      img.classList.add('is-ready');
      fit(leaf);
    });
    img.addEventListener('error', function () { print.classList.add('is-broken'); });
    if (ph.preview && ph.preview !== ph.src) {
      var full = new Image();
      full.onload = function () { img.src = ph.src; };
      full.src = ph.src;
      img.src = ph.preview;
    } else {
      img.src = ph.src;
    }
    return leaf;
  }

  /* ---- перелистывание ---- */
  function go(to, fast) {
    if (!S || to < 0 || to >= S.photos.length || to === S.cur) return;
    if (busy) { queued = to; return; }                       // страница ещё переворачивается — запомним, куда листать дальше
    var album = S, from = S.leaf, next, forward = to > S.cur;
    S.cur = to;
    next = makeLeaf(to);
    sync();
    if (calm() || typeof from.animate !== 'function') {
      book.replaceChild(next, from);
      S.leaf = next;
      fit(next);
      return;
    }
    busy = true;
    var ms = fast ? 300 : 560, turning, opts = { duration: ms, fill: 'forwards' };
    if (forward) {                                            // текущая страница уходит влево, под ней уже лежит следующая
      book.insertBefore(next, from);
      fit(next);
      turning = from.animate([{ transform: 'rotateY(0deg)' }, { transform: 'rotateY(-104deg)' }], { duration: ms, fill: 'forwards', easing: 'cubic-bezier(.5,.05,.75,.35)' });
      from._shade.animate([{ opacity: 0 }, { opacity: 1 }], opts);
      next._cast.animate([{ opacity: 1 }, { opacity: 0 }], opts);
    } else {                                                  // предыдущая страница возвращается и ложится сверху
      book.appendChild(next);
      fit(next);
      turning = next.animate([{ transform: 'rotateY(-104deg)' }, { transform: 'rotateY(0deg)' }], { duration: ms, fill: 'forwards', easing: 'cubic-bezier(.2,.6,.35,1)' });
      next._shade.animate([{ opacity: 1 }, { opacity: 0 }], opts);
      from._cast.animate([{ opacity: 0 }, { opacity: 1 }], opts);
    }
    turning.onfinish = function () {
      if (from.parentNode) from.parentNode.removeChild(from);
      if (S !== album) return;                                // альбом успели закрыть (или открыть другой), пока страница переворачивалась
      busy = false;
      S.leaf = next;
      if (queued !== null) { var q = queued; queued = null; go(q, true); }
    };
  }
  function sync() {
    var n = S.photos.length;
    count.innerHTML = 'Фото <b>' + (S.cur + 1) + '</b> из ' + n;
    btnPrev.disabled = S.cur === 0;
    btnNext.disabled = S.cur === n - 1;
    Array.prototype.forEach.call(thumbs.children, function (li, i) {
      if (i === S.cur) li.firstChild.setAttribute('aria-current', 'true'); else li.firstChild.removeAttribute('aria-current');
    });
    var on = thumbs.children[S.cur];                          // фото сколько угодно: лента миниатюр прокручивается к текущему
    if (on && thumbs.scrollWidth > thumbs.clientWidth) {
      thumbs.scrollTo({ left: on.offsetLeft - (thumbs.clientWidth - on.offsetWidth) / 2, behavior: calm() ? 'auto' : 'smooth' });
    }
  }

  /* ---- открыть и закрыть ---- */
  function open(opts) {
    var photos = (opts && opts.photos || []).map(function (p) { return typeof p === 'string' ? { src: p } : p; })
      .filter(function (p) { return p && p.src; });
    if (!photos.length) return;
    build();
    if (S) cleanup();
    S = { title: opts.title || '', meta: opts.meta || '', photos: photos, cur: Math.max(0, Math.min(photos.length - 1, opts.index || 0)), opener: opts.opener || document.activeElement };
    dlg.setAttribute('aria-label', 'Фотоальбом' + (S.title ? ': ' + S.title : ''));
    dlg.classList.toggle('alb--single', photos.length < 2);
    thumbs.textContent = '';
    photos.forEach(function (p, i) {
      var li = el('li'), b = el('button'), im = new Image();
      b.type = 'button';
      b.setAttribute('aria-label', 'Фото ' + (i + 1));
      im.alt = '';
      im.decoding = 'async';
      im.src = p.thumb || p.src;
      b.appendChild(im);
      b.addEventListener('click', function () { go(i); });
      li.appendChild(b);
      thumbs.appendChild(li);
    });
    S.leaf = makeLeaf(S.cur);
    book.appendChild(S.leaf);
    if (dlg.showModal) { if (!dlg.open) dlg.showModal(); } else { dlg.setAttribute('open', ''); dlg.classList.add('alb--nomodal'); }
    sync();
    layout();
    if (document.fonts && document.fonts.ready) document.fonts.ready.then(function () { if (S) layout(); });   // подпись могла сменить высоту, когда догрузился шрифт
    if (!calm() && book.animate) {
      book.animate([{ opacity: 0, transform: 'translateY(22px) rotate(-3deg) scale(.95)' }, { opacity: 1, transform: 'none' }], { duration: 380, easing: 'cubic-bezier(.2,.8,.3,1)' });
    }
  }
  function close() {
    if (!dlg) return;
    if (dlg.close && dlg.open) dlg.close(); else { dlg.removeAttribute('open'); cleanup(); }
  }
  function cleanup() {
    var opener = S && S.opener;
    S = null; busy = false; queued = null; start = null;
    Array.prototype.forEach.call(book.querySelectorAll('.alb-leaf'), function (leaf) { book.removeChild(leaf); });
    thumbs.textContent = '';
    if (opener && opener.focus && document.contains(opener)) { try { opener.focus({ preventScroll: true }); } catch (e) { opener.focus(); } }
  }

  /* ---- запуск ---- */
  document.addEventListener('click', function (e) {
    var node = e.target.closest ? e.target.closest('[data-photos]') : null;
    if (!node || (dlg && dlg.contains(node))) return;
    if (node.tagName === 'A') e.preventDefault();
    openFrom(node);
  });
  document.addEventListener('keydown', function (e) {
    if (e.key !== 'Enter' && e.key !== ' ') return;
    var node = e.target;
    if (!node.matches || !node.matches('[data-photos][role="button"]')) return;
    e.preventDefault();
    openFrom(node);
  });
  function boot() {
    scan(document);
    if (window.MutationObserver) {                              // карточки, добавленные позже («Показать все»), подхватываются сами
      var pending = false;
      new MutationObserver(function () {
        if (pending) return;
        pending = true;
        (window.requestAnimationFrame || window.setTimeout)(function () { pending = false; scan(document); });
      }).observe(document.body, { childList: true, subtree: true });
    }
  }
  if (document.readyState === 'loading') document.addEventListener('DOMContentLoaded', boot); else boot();

  window.YugAlbum = { open: open, close: close, scan: scan };
})();
