/* Админка «Юг Сладости»: общее для всех страниц — меню на телефоне, меню пользователя, поиск на
   телефоне, всплывающая строка, окна-диалоги, строки-ссылки, «Сохранить изменения?» при уходе.
   Поведение — как в макете (admin-maket.js), но страницы рисует сервер. */
(function () {
  'use strict';
  function $(id) { return document.getElementById(id); }
  var IC = {};
  try { IC = JSON.parse($('admin-icons').textContent); } catch (e) { IC = {}; }
  function icon(name, size) {
    size = size || 20;
    return '<svg class="i" viewBox="0 0 24 24" width="' + size + '" height="' + size + '" fill="none" stroke="currentColor" stroke-width="1.9" ' +
      'stroke-linecap="round" stroke-linejoin="round" aria-hidden="true">' + (IC[name] || '') + '</svg>';
  }
  function esc(s) {
    return String(s == null ? '' : s).replace(/[&<>"']/g, function (c) {
      return { '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[c];
    });
  }
  function meta(n) { var m = document.querySelector('meta[name="' + n + '"]'); return m ? m.getAttribute('content') : ''; }
  var CSRF = { header: meta('_csrf_header'), token: meta('_csrf'), param: meta('_csrf_param') };
  function csrfInput() { return '<input type="hidden" name="' + esc(CSRF.param) + '" value="' + esc(CSRF.token) + '">'; }

  /* ---------- toast ---------- */
  var toastEl = $('toast'), toastTimer = null;
  function toast(text, opts) {
    if (!toastEl) return;
    opts = opts || {};
    $('toast-t').textContent = text;
    var undo = $('toast-undo');
    if (undo && !opts.keepUndo) undo.hidden = !opts.undo;
    toastEl.classList.add('on');
    clearTimeout(toastTimer);
    toastTimer = setTimeout(hideToast, opts.undo ? 7000 : 3800);
  }
  function hideToast() { if (toastEl) toastEl.classList.remove('on'); }
  if (toastEl && toastEl.getAttribute('data-text')) {
    var hasUndo = !!$('toast-undo');
    setTimeout(function () { toast(toastEl.getAttribute('data-text'), { undo: hasUndo, keepUndo: true }); }, 60);
  }

  /* ---------- dialog ---------- */
  var dlg = $('dlg');
  function openDlg(html, cls) {
    if (!dlg) return null;
    dlg.className = 'dlg' + (cls ? ' ' + cls : '');      // «is-wide» — широкое окно (документ, накладная)
    dlg.innerHTML = '<button class="ibtn dlg-x" type="button" data-close aria-label="Закрыть">' + icon('x') + '</button>' + html;
    if (dlg.showModal) { if (!dlg.open) dlg.showModal(); } else dlg.setAttribute('open', '');
    var f = dlg.querySelector('[autofocus], input:not([type=hidden]):not([type=radio]), textarea, .btn-primary');
    if (f) f.focus();
    return dlg;
  }
  function closeDlg() { if (!dlg) return; if (dlg.close) dlg.close(); else dlg.removeAttribute('open'); }
  if (dlg) dlg.addEventListener('click', function (e) {
    if (e.target === dlg || e.target.closest('[data-close]')) closeDlg();
  });
  /* кнопки data-dialog="id шаблона": содержимое <template id> — в окно; CSRF подставляется в формы */
  document.addEventListener('click', function (e) {
    var b = e.target.closest('[data-dialog]');
    if (!b) return;
    var t = $(b.getAttribute('data-dialog'));
    if (!t) return;
    e.preventDefault();
    openDlg(t.innerHTML, t.getAttribute('data-cls'));
    dlg.querySelectorAll('form[method="post"]').forEach(function (f) {
      if (!f.querySelector('input[name="' + CSRF.param + '"]')) f.insertAdjacentHTML('afterbegin', csrfInput());
    });
    document.dispatchEvent(new CustomEvent('admin:dialog', { detail: { id: b.getAttribute('data-dialog'), dialog: dlg, button: b } }));
  });

  /* ---------- menu, user, search ---------- */
  var app = $('app');
  function openNav() {
    app.classList.add('nav-open'); $('burger').setAttribute('aria-expanded', 'true');
    var a = document.querySelector('.nav-a[aria-current]') || document.querySelector('.nav-a');
    if (a) a.focus();
  }
  function closeNav() { if (!app || !app.classList.contains('nav-open')) return; app.classList.remove('nav-open'); $('burger').setAttribute('aria-expanded', 'false'); }
  function closeMe() { if (!$('me-pop')) return; $('me-pop').hidden = true; $('me-b').setAttribute('aria-expanded', 'false'); }
  if (app) {
    $('burger').addEventListener('click', openNav);
    $('side-x').addEventListener('click', function () { closeNav(); $('burger').focus(); });
    $('scrim').addEventListener('click', closeNav);
    $('me-b').addEventListener('click', function (e) {
      e.stopPropagation();
      var open = $('me-pop').hidden;
      $('me-pop').hidden = !open;
      this.setAttribute('aria-expanded', String(open));
    });
    document.addEventListener('click', function (e) { if (!e.target.closest('.me')) closeMe(); });
    document.addEventListener('keydown', function (e) {
      if (e.key !== 'Escape') return;
      if (app.classList.contains('nav-open')) { closeNav(); $('burger').focus(); }
      if (!$('me-pop').hidden) { closeMe(); $('me-b').focus(); }
      if ($('bar').classList.contains('is-search')) { $('bar').classList.remove('is-search'); $('s-btn').setAttribute('aria-expanded', 'false'); }
    });
    $('s-btn').addEventListener('click', function () {
      var on = !$('bar').classList.contains('is-search');
      $('bar').classList.toggle('is-search', on);
      this.setAttribute('aria-expanded', String(on));
      if (on) $('gq').focus();
    });
    $('gsearch').addEventListener('submit', function (e) { if (!$('gq').value.trim()) e.preventDefault(); });
  }

  /* ---------- суммы и телефоны под размытием ----------
     Кнопки data-mask="money" (суммы на дашборде, .mny) и data-mask="phones" (телефоны клиентов, .phn) —
     admin/mask.html. Состояние — в cookie на 400 дней (продлевается при каждом заходе), сервер по нему сразу
     рисует <html class="hide-money"> и <body class="hide-phones">. Телефоны скрыты, пока их не открыли;
     нажатие на один размытый номер открывает только его. */
  var MASK = {
    money: { el: document.documentElement, cls: 'hide-money', ck: 'adm_money', what: 'суммы', def: false },
    phones: { el: document.body, cls: 'hide-phones', ck: 'adm_phones', what: 'телефоны', def: true }
  };
  function readMask(k) { var m = document.cookie.match(new RegExp('(?:^|; )' + MASK[k].ck + '=(hide|show)')); return m ? m[1] === 'hide' : null; }
  function saveMask(k, hidden) { document.cookie = MASK[k].ck + '=' + (hidden ? 'hide' : 'show') + '; path=/admin; max-age=34560000; SameSite=Lax'; }
  function applyMask(k, hidden) {
    var x = MASK[k];
    x.el.classList.toggle(x.cls, hidden);
    if (k === 'phones') document.querySelectorAll('.phn.is-shown').forEach(function (p) { p.classList.remove('is-shown'); });
    document.querySelectorAll('[data-mask="' + k + '"]').forEach(function (b) {
      var t = (hidden ? 'Показать ' : 'Скрыть ') + x.what;
      b.title = t; b.setAttribute('aria-label', t);
    });
    document.dispatchEvent(new CustomEvent('admin:mask', { detail: { kind: k, hidden: hidden } }));
  }
  Object.keys(MASK).forEach(function (k) { var h = readMask(k); if (h !== null) saveMask(k, h); });
  // «Назад» в браузере показывает страницу из памяти — состояние могли поменять на другой странице
  window.addEventListener('pageshow', function (e) {
    if (!e.persisted) return;
    Object.keys(MASK).forEach(function (k) {
      var h = readMask(k); if (h === null) h = MASK[k].def;
      if (h !== MASK[k].el.classList.contains(MASK[k].cls)) applyMask(k, h);
    });
  });
  document.addEventListener('click', function (e) {
    var b = e.target.closest('[data-mask]');
    if (b) {
      var k = b.getAttribute('data-mask'), h = !MASK[k].el.classList.contains(MASK[k].cls);
      saveMask(k, h); applyMask(k, h);
      return;
    }
    var p = e.target.closest('.phn');
    if (p && document.body.classList.contains('hide-phones') && !p.classList.contains('is-shown') && !e.target.closest('a,button')) {
      p.classList.add('is-shown');
      e.preventDefault();                                     // строка-ссылка в таблице не открывается
    }
  });

  /* ---------- table rows that open a card ---------- */
  document.addEventListener('click', function (e) {
    var tr = e.target.closest('tr[data-href], li[data-href], div[data-href]');
    if (!tr || e.defaultPrevented || e.target.closest('a,button,input,select,label,textarea')) return;
    if (e.ctrlKey || e.metaKey) window.open(tr.getAttribute('data-href'), '_blank'); else location.href = tr.getAttribute('data-href');
  });

  /* ---------- filter forms: change → submit; search — after a pause ---------- */
  document.querySelectorAll('form[data-autosubmit]').forEach(function (f) {
    var t = null;
    f.addEventListener('change', function (e) { if (e.target.matches('select, input[type=checkbox], input[type=radio], input[type=date]')) f.requestSubmit ? f.requestSubmit() : f.submit(); });
    f.addEventListener('input', function (e) {
      if (!e.target.matches('input[type=search]')) return;
      clearTimeout(t);
      t = setTimeout(function () { f.requestSubmit ? f.requestSubmit() : f.submit(); }, 650);
    });
  });
  /* keep the caret in the search field after an auto-submitted search */
  var sq = document.querySelector('form[data-autosubmit] input[type=search]');
  if (sq && sq.value && sessionStorage) {
    try {
      if (sessionStorage.getItem('admin-sq') === location.pathname) { sq.focus(); var v = sq.value; sq.value = ''; sq.value = v; }
      sessionStorage.removeItem('admin-sq');
    } catch (e) { /* storage blocked */ }
  }
  if (sq) sq.addEventListener('input', function () { try { sessionStorage.setItem('admin-sq', location.pathname); } catch (e) { /* ignore */ } });

  /* ---------- copy phone ---------- */
  document.addEventListener('click', function (e) {
    var b = e.target.closest('[data-copy]');
    if (!b) return;
    var v = b.getAttribute('data-copy');
    var sel = function () {
      var sp = b.parentNode.querySelector('span'); if (!sp) return;
      sp.classList.add('is-shown');                           // размытый номер не выделить
      var r = document.createRange(); r.selectNodeContents(sp);
      var s = window.getSelection(); s.removeAllRanges(); s.addRange(r);
      toast('Номер выделен — скопируйте его.');
    };
    var hid = document.body.classList.contains('hide-phones') && !b.parentNode.querySelector('.phn.is-shown');
    try { navigator.clipboard.writeText(v).then(function () { toast(hid ? 'Телефон скопирован.' : 'Телефон скопирован: ' + v); }, sel); } catch (err) { sel(); }
  });

  /* ---------- confirm buttons: data-confirm="текст" — окно на странице вместо confirm() ---------- */
  document.addEventListener('click', function (e) {
    var b = e.target.closest('[data-confirm]');
    if (!b || b.hasAttribute('data-confirmed')) return;
    e.preventDefault();
    var form = b.closest('form');
    openDlg('<div class="dlg-in"><h2 id="dlg-h">' + esc(b.getAttribute('data-confirm-title') || 'Подтвердите') + '</h2>' +
      '<p class="muted">' + esc(b.getAttribute('data-confirm')) + '</p><div class="dlg-acts">' +
      '<button class="btn btn-soft" type="button" data-close>Отмена</button>' +
      '<button class="btn ' + (b.classList.contains('btn-danger') || b.classList.contains('link--danger') ? 'btn-danger' : 'btn-primary') + '" type="button" id="cf-ok">' + esc(b.getAttribute('data-confirm-ok') || b.textContent.trim()) + '</button></div></div>');
    $('cf-ok').onclick = function () {
      closeDlg();
      b.setAttribute('data-confirmed', '');
      if (form && b.getAttribute('formaction')) form.action = b.getAttribute('formaction');
      if (form) { if (b.name) form.insertAdjacentHTML('beforeend', '<input type="hidden" name="' + esc(b.name) + '" value="' + esc(b.value) + '">'); form.submit(); }
      else if (b.href) location.href = b.href;
    };
  });

  /* ---------- unsaved changes: forms with data-guard ---------- */
  var guard = { dirty: false, form: null, msg: '' };
  function markDirty() { guard.dirty = true; }
  document.querySelectorAll('form[data-guard]').forEach(function (f) {
    guard.form = f;
    guard.msg = f.getAttribute('data-guard') || 'Изменения не сохранены.';
    f.addEventListener('input', markDirty);
    f.addEventListener('change', markDirty);
    f.addEventListener('submit', function () { guard.dirty = false; });
  });
  window.addEventListener('beforeunload', function (e) {
    if (guard.dirty) { e.preventDefault(); e.returnValue = ''; }
  });
  document.addEventListener('click', function (e) {
    if (!guard.dirty) return;
    var a = e.target.closest('a[href]');
    if (!a || a.target === '_blank' || e.ctrlKey || e.metaKey || a.getAttribute('href').charAt(0) === '#') return;
    e.preventDefault();
    var target = a.href;
    openDlg('<div class="dlg-in"><h2 id="dlg-h">Сохранить изменения?</h2>' +
      '<p class="muted">' + esc(guard.msg) + ' Если уйти без сохранения, изменения пропадут.</p>' +
      '<div class="dlg-acts"><button class="btn btn-soft" type="button" data-close>Остаться</button>' +
      '<button class="btn btn-danger" type="button" id="lv-drop">Не сохранять</button>' +
      '<button class="btn btn-primary" type="button" id="lv-save">Сохранить и выйти</button></div></div>');
    $('lv-drop').onclick = function () { guard.dirty = false; closeDlg(); location.href = target; };
    $('lv-save').onclick = function () {
      closeDlg();
      var ev = new CustomEvent('admin:save-and-leave', { cancelable: true, detail: { target: target } });
      if (!document.dispatchEvent(ev)) return;          // a page script saves by itself
      var f = guard.form;
      if (!f) return;
      var n = f.querySelector('input[name="next"]');
      if (!n) { f.insertAdjacentHTML('beforeend', '<input type="hidden" name="next">'); n = f.querySelector('input[name="next"]'); }
      n.value = target;
      if (f.requestSubmit) f.requestSubmit(); else f.submit();
    };
  });

  /* ---------- the page title gets focus after a page change ---------- */
  if ($('title') && !document.querySelector('[autofocus]') && !(sq && document.activeElement === sq)) {
    $('title').focus({ preventScroll: true });
  }

  window.Admin = {
    $: $, esc: esc, icon: icon, toast: toast, openDlg: openDlg, closeDlg: closeDlg, csrf: CSRF, csrfInput: csrfInput,
    guard: { set: function (v) { guard.dirty = !!v; }, get: function () { return guard.dirty; }, msg: function (m) { guard.msg = m; } }
  };
})();
