/* Новый заказ и изменение — четыре шага и итог справа (vForm из макета). Данные — JSON в странице:
   #of-state (что в форме), #of-catalog (товары с ценами клиента), #of-clients, #of-slots (неделя окон),
   #of-errors (ошибки сервера). Отправка — обычная форма: строки уходят полями lineProduct/lineQty,
   проверки повторяет сервер. */
(function () {
  'use strict';
  var $ = Admin.$, esc = Admin.esc, icon = Admin.icon;
  function json(id) { try { return JSON.parse($(id).textContent); } catch (e) { return null; } }
  var ST = json('of-state') || {}, CAT = json('of-catalog') || [], CLIENTS = json('of-clients') || [], WEEK = json('of-slots') || [];
  var SERVER_ERRS = json('of-errors') || [];
  var NB = ' ';
  var P = {}; CAT.forEach(function (p) { p.box = p.box == null ? null : +p.box; p.price = +p.price || 0; p.g = +p.g || 0; p.vol = +p.vol || 0; P[p.key] = p; });
  var CL = {}; CLIENTS.forEach(function (c) { CL[c.key] = c; });
  var PAY_LABEL = { CASH: 'Наличными при выдаче', INVOICE: 'По счёту', KASPI: 'Kaspi' };

  function g3(n) { return String(Math.round(Math.abs(n))).replace(/\B(?=(\d{3})+(?!\d))/g, NB); }
  function money(n) { return (n < 0 ? '−' : '') + g3(n) + NB + '₸'; }
  function dec(x, d) { var s = Math.abs(x).toFixed(d).split('.'); return (x < 0 ? '−' : '') + s[0].replace(/\B(?=(\d{3})+(?!\d))/g, NB) + (d ? ',' + s[1] : ''); }
  function norm(s) { return String(s == null ? '' : s).toLowerCase().replace(/ё/g, 'е').trim(); }
  function pd(s) { return new Date(Date.UTC(+s.slice(0, 4), +s.slice(5, 7) - 1, +s.slice(8, 10))); }
  function relDay(s) {
    var t = WEEK.length ? WEEK[0].d : '', t1 = WEEK.length > 1 ? WEEK[1].d : '';
    return s === t ? 'сегодня' : s === t1 ? 'завтра' : AdminSlots.dm(s);
  }
  function cap(s) { return s.charAt(0).toUpperCase() + s.slice(1); }
  function boxText(q, box) {
    if (!q || !box) return '';
    var full = Math.floor(q / box), rest = q - full * box;
    if (!rest) return full + NB + 'кор.';
    return full ? full + NB + 'кор. + ' + rest + NB + 'шт' : 'неполная коробка';
  }
  function thumb(p) {
    return p && p.photo ? '<img class="th" src="' + esc(p.photo) + '" alt="" width="30" height="40" loading="lazy">'
      : '<span class="th th-none">' + icon('cookie') + '</span>';
  }
  // как на экране владельца «Рецептура»: с начала названия, потом с начала слова, потом внутри
  function rank(list, text, max) {
    var q = norm(text);
    if (!q) return [];
    var out = [];
    list.forEach(function (it) {
      var at = norm(it.label).indexOf(q);
      if (at < 0) return;
      var ws = at === 0 || it.label.charAt(at - 1) === ' ' || it.label.charAt(at - 1) === '«';
      out.push({ key: it.key, label: it.label, at: at, len: q.length, rank: at === 0 ? 0 : ws ? 1 : 2 });
    });
    out.sort(function (a, b) { return a.rank - b.rank; });
    return out.slice(0, max || 5);
  }
  function hl(m) { return esc(m.label.slice(0, m.at)) + '<b>' + esc(m.label.slice(m.at, m.at + m.len)) + '</b>' + esc(m.label.slice(m.at + m.len)); }

  var uid = 0;
  var F = {
    no: ST.no || 0, client: ST.client || null, cq: '', chi: 0,
    S: { day: ST.day || (WEEK.filter(function (w) { return w.free > 0; })[0] || WEEK[0] || { d: '' }).d, sel: ST.day && ST.hour != null ? { d: ST.day, h: +ST.hour } : null },
    pay: ST.pay || 'CASH',
    rows: (ST.rows && ST.rows.length ? ST.rows : [{ key: '', qty: '' }]).map(function (r) {
      var k = r.key === '' || r.key == null ? '' : +r.key;
      return { id: ++uid, key: P[k] ? k : '', text: P[k] ? P[k].label : '', qty: r.qty == null ? '' : String(r.qty) };
    }),
    active: null, typed: false, hi: 0, tried: SERVER_ERRS.length > 0
  };
  if (F.S.sel && !WEEK.some(function (w) { return w.d === F.S.sel.d; })) F.S.sel = null;   // the window is in the past
  function dirty() { Admin.guard.set(true); }
  if (ST.rows && ST.rows.length && !ST.no) dirty();                               // «Повторить заказ» — уже не пустая форма

  /* ---- 1. client ---- */
  function fClient() {
    var box = $('f-client');
    if (F.client && CL[F.client]) {
      var c = CL[F.client];
      box.innerHTML = '<div class="cl-card"><p><b>' + esc(c.org) + '</b><span>' + esc([c.person, c.phone].filter(Boolean).join(' · ')).replace(/ /g, NB) + '</span></p>' +
        '<button class="btn btn-soft btn-sm" type="button" id="f-cl-x">Другой клиент</button></div>';
      $('f-cl-x').onclick = function () { F.client = null; dirty(); fClient(); fSum(); $('f-cq').focus(); };
      return;
    }
    box.innerHTML = '<div class="field"><label class="lbl" for="f-cq">Магазин, имя или телефон</label>' +
      '<input class="inp' + (F.tried ? ' is-bad' : '') + '" id="f-cq" autocomplete="off" spellcheck="false" placeholder="Начните вводить — название магазина или цифры телефона" value="' + esc(F.cq) + '"></div>' +
      '<div id="f-csug"></div><p class="hint">Нового клиента добавляют в разделе <a class="link" href="/admin/clients/new">«Клиенты»</a>.</p>';
    var inp = $('f-cq');
    inp.addEventListener('input', function () { F.cq = this.value; F.chi = 0; cSug(); });
    inp.addEventListener('keydown', function (e) {
      var found = clientMatches(F.cq);
      if (e.key === 'Enter') e.preventDefault();
      if (!found.length) return;
      if (e.key === 'ArrowDown') { e.preventDefault(); F.chi = (F.chi + 1) % found.length; cSug(); }
      else if (e.key === 'ArrowUp') { e.preventDefault(); F.chi = (F.chi + found.length - 1) % found.length; cSug(); }
      else if (e.key === 'Enter') { cPick(found[Math.min(F.chi, found.length - 1)].key); }
    });
    inp.addEventListener('blur', function () { setTimeout(function () { if ($('f-csug')) $('f-csug').innerHTML = ''; }, 150); });
    inp.addEventListener('focus', function () { if (F.cq) cSug(); });
    $('f-csug').addEventListener('mousedown', function (e) {
      var b = e.target.closest('button[data-key]'); if (!b) return;
      e.preventDefault(); cPick(+b.getAttribute('data-key'));
    });
  }
  function clientMatches(text) {
    var q = norm(text), digits = q.replace(/\D/g, '');
    if (!q) return [];
    var byName = rank(CLIENTS.map(function (c) { return { key: c.key, label: c.org + (c.person ? ' · ' + c.person : '') }; }), q, 6);
    if (digits.length >= 3 && digits === q.replace(/[\s()+-]/g, '')) {
      CLIENTS.forEach(function (c) {
        if (c.phone.replace(/\D/g, '').indexOf(digits) >= 0 && !byName.some(function (m) { return m.key === c.key; })) {
          byName.push({ key: c.key, label: c.org + (c.person ? ' · ' + c.person : ''), at: 0, len: 0, rank: 3 });
        }
      });
    }
    return byName.slice(0, 6);
  }
  function cSug() {
    var box = $('f-csug'), found = clientMatches(F.cq);
    if (!norm(F.cq)) { box.innerHTML = ''; return; }
    if (!found.length) { box.innerHTML = '<div class="sug" style="margin-left:0"><p class="sug-none">Такого клиента нет в списке.</p></div>'; return; }
    var curI = Math.min(F.chi, found.length - 1);
    box.innerHTML = '<div class="sug" role="listbox" style="margin-left:0">' + found.map(function (m, i) {
      var c = CL[m.key];
      return '<button type="button" tabindex="-1" role="option" data-key="' + m.key + '" aria-selected="' + (i === curI) + '"><span>' + (m.len ? hl(m) : esc(m.label)) +
        '</span><small class="muted" style="margin-left:auto;white-space:nowrap">' + esc(c.phone).replace(/ /g, NB) + '</small></button>';
    }).join('') + '</div>';
  }
  function cPick(id) {
    F.client = id; F.cq = ''; dirty();
    var c = CL[id];
    if (!F.no && c.pay) { F.pay = c.pay; var r = document.querySelector('#f-pay input[value="' + c.pay + '"]'); if (r) r.checked = true; }
    fClient(); fSum();
    $('f-cl-x').focus();
    // individual prices of this client
    fetch('/admin/api/clients/' + id + '/prices', { credentials: 'same-origin' }).then(function (r) { return r.ok ? r.json() : null; }).then(function (m) {
      if (!m) return;
      Object.keys(m).forEach(function (k) { if (P[k]) P[k].price = +m[k]; });
      fLines(); fSum();
    }).catch(function () { /* keep the catalogue prices */ });
  }

  /* ---- 2. pickup ---- */
  function fSlots() {
    AdminSlots.bind($('f-slot'), F.S, WEEK, function () { dirty(); fSum(); });
    if (F.tried && !F.S.sel) $('f-slot').insertAdjacentHTML('beforeend', '<p class="errs">Выберите окно выдачи.</p>');
  }

  /* ---- 3. lines ---- */
  function lineBad(r) { return !r.key || !(+r.qty > 0); }
  function rowOf(id) { for (var i = 0; i < F.rows.length; i++) if (F.rows[i].id === id) return F.rows[i]; return null; }
  function boxCell(p, q) { return p ? '<span>' + (boxText(q, p.box) || (p.box ? 'в коробке ' + p.box : '')) + '</span>' + (p.box ? '<button type="button" data-box>+ коробка</button>' : '') : ''; }
  function sumCell(p, q) { return p && q ? money(p.price * q) + '<small>' + money(p.price) + ' × ' + q + '</small>' : p ? '<small>' + money(p.price) + ' за шт</small>' : ''; }
  function fLines() {
    var box = $('f-lines');
    box.innerHTML = F.rows.map(function (r) {
      var p = r.key ? P[r.key] : null, q = +r.qty || 0;
      return '<div class="ln" data-id="' + r.id + '">' + thumb(p) +
        '<div class="ln-nm"><input class="inp' + (F.tried && !r.key ? ' is-bad' : '') + '" type="text" aria-label="Товар" placeholder="Название товара" autocomplete="off" spellcheck="false" maxlength="80" value="' + esc(r.text) + '"></div>' +
        '<label class="ln-q"><span class="sr">Количество, шт</span><input class="inp' + (F.tried && !(q > 0) ? ' is-bad' : '') + '" type="text" inputmode="numeric" autocomplete="off" placeholder="0" value="' + esc(r.qty) + '"><i>шт</i></label>' +
        '<div class="ln-box">' + boxCell(p, q) + '</div>' +
        '<div class="ln-sum">' + sumCell(p, q) + '</div>' +
        '<button class="rx-del ln-del" type="button" aria-label="Удалить строку' + (p ? ': ' + esc(p.label) : '') + '">' + icon('trash') + '</button></div>';
    }).join('');
    lSug();
  }
  function lSug() {
    var old = $('f-sug'); if (old) old.remove();
    if (!F.active || !F.typed) return;
    var r = rowOf(F.active); if (!r || !norm(r.text)) return;
    var found = rank(CAT, r.text, 6), el = document.createElement('div');
    el.id = 'f-sug';
    el.className = 'sug';
    if (!found.length) el.innerHTML = '<p class="sug-none">Такого товара нет в каталоге.</p>';
    else {
      var curI = Math.min(F.hi, found.length - 1);
      el.setAttribute('role', 'listbox');
      el.innerHTML = found.map(function (m, i) {
        var p = P[m.key];
        return '<button type="button" tabindex="-1" role="option" data-key="' + m.key + '" aria-selected="' + (i === curI) + '">' + thumb(p) + '<span>' + hl(m) + '</span>' +
          '<small class="muted" style="margin-left:auto;white-space:nowrap">' + money(p.price) + '</small></button>';
      }).join('');
    }
    var rowEl = $('f-lines').querySelector('.ln[data-id="' + F.active + '"]');
    if (rowEl) rowEl.after(el);
  }
  function refreshLine(ln, r) {
    var p = r.key ? P[r.key] : null, q = +r.qty || 0;
    ln.querySelector('.th').outerHTML = thumb(p);
    ln.querySelector('.ln-box').innerHTML = boxCell(p, q);
    ln.querySelector('.ln-sum').innerHTML = sumCell(p, q);
    ln.querySelector('.ln-del').setAttribute('aria-label', 'Удалить строку' + (p ? ': ' + p.label : ''));
    if (F.tried) {
      ln.querySelector('.ln-nm input').classList.toggle('is-bad', !r.key);
      ln.querySelector('.ln-q input').classList.toggle('is-bad', !(q > 0));
    }
  }
  function linePick(id, key) {
    var r = rowOf(id); if (!r) return;
    r.key = key; r.text = P[key].label;
    if (!(+r.qty > 0)) r.qty = String(P[key].box || 1);
    F.active = null; F.typed = false; F.hi = 0; dirty();
    var ln = $('f-lines').querySelector('.ln[data-id="' + id + '"]');
    ln.querySelector('.ln-nm input').value = r.text;
    ln.querySelector('.ln-q input').value = r.qty;
    refreshLine(ln, r); lSug(); fSum();
    var qi = ln.querySelector('.ln-q input'); qi.focus(); qi.select();
  }
  function bindLines() {
    var box = $('f-lines');
    box.addEventListener('input', function (e) {
      var ln = e.target.closest('.ln'); if (!ln) return;
      var r = rowOf(+ln.getAttribute('data-id'));
      if (e.target.closest('.ln-nm')) {
        r.text = e.target.value.slice(0, 80);
        var q = norm(r.text), key = '';
        CAT.forEach(function (m) { if (q && norm(m.label) === q) key = m.key; });
        if (key !== r.key) { r.key = key; refreshLine(ln, r); }
        F.active = r.id; F.typed = true; F.hi = 0;
        lSug();
      } else {
        var v = e.target.value.replace(/\D/g, '').slice(0, 5);
        if (v !== e.target.value) e.target.value = v;
        r.qty = v;
        refreshLine(ln, r);
      }
      dirty(); fSum();
    });
    box.addEventListener('focusin', function (e) {
      if (!e.target.closest('.ln-nm')) return;
      e.target.select();
      F.active = +e.target.closest('.ln').getAttribute('data-id'); F.typed = false; F.hi = 0; lSug();
    });
    box.addEventListener('focusout', function (e) {
      if (!e.target.closest('.ln-nm')) return;
      var r = rowOf(+e.target.closest('.ln').getAttribute('data-id'));
      if (r && r.key) e.target.value = r.text = P[r.key].label;
      if (r && F.active === r.id) { F.active = null; F.typed = false; }
      lSug();
    });
    box.addEventListener('keydown', function (e) {
      if (!e.target.closest('.ln-nm')) return;
      if (e.key === 'Enter') e.preventDefault();
      if (!F.typed) return;
      var r = rowOf(+e.target.closest('.ln').getAttribute('data-id')), found = rank(CAT, r.text, 6);
      if (!found.length) return;
      var c = Math.min(F.hi, found.length - 1);
      if (e.key === 'ArrowDown') { e.preventDefault(); F.hi = (c + 1) % found.length; lSug(); }
      else if (e.key === 'ArrowUp') { e.preventDefault(); F.hi = (c + found.length - 1) % found.length; lSug(); }
      else if (e.key === 'Enter') { linePick(r.id, found[c].key); }
    });
    box.addEventListener('mousedown', function (e) {
      var b = e.target.closest('#f-sug button[data-key]'); if (!b) return;
      e.preventDefault();
      linePick(F.active, +b.getAttribute('data-key'));
    });
    box.addEventListener('click', function (e) {
      var ln = e.target.closest('.ln'); if (!ln) return;
      var r = rowOf(+ln.getAttribute('data-id'));
      if (e.target.closest('[data-box]')) {
        r.qty = String((+r.qty || 0) + (P[r.key].box || 1));
        ln.querySelector('.ln-q input').value = r.qty;
        refreshLine(ln, r); dirty(); fSum();
      } else if (e.target.closest('.ln-del')) {
        var i = F.rows.indexOf(r);
        F.rows.splice(i, 1);
        if (!F.rows.length) F.rows.push({ id: ++uid, key: '', text: '', qty: '' });
        dirty(); fLines(); fSum();
        var next = box.querySelectorAll('.ln')[Math.min(i, F.rows.length - 1)];
        (next ? next.querySelector('.ln-nm input') : $('f-add')).focus();
      }
    });
  }

  /* ---- totals, errors ---- */
  function fTotals() {
    var t = { pos: 0, packs: 0, boxes: 0, kg: 0, vol: 0, sum: 0 };
    F.rows.forEach(function (r) {
      if (!r.key || !(+r.qty > 0)) return;
      var p = P[r.key], q = +r.qty;
      t.pos++; t.packs += q; t.kg += p.g * q / 1000; t.sum += p.price * q;
      if (p.box) { var b = Math.ceil(q / p.box); t.boxes += b; t.vol += b * p.vol; }
    });
    return t;
  }
  function fErrors() {
    var e = [];
    if (!F.client) e.push('Выберите клиента.');
    if (!F.S.sel) e.push('Выберите день и окно выдачи.');
    var filled = F.rows.filter(function (r) { return r.key || r.text || r.qty; });
    if (!filled.some(function (r) { return !lineBad(r); })) e.push('Добавьте хотя бы один товар с количеством.');
    else if (filled.some(lineBad)) e.push('Проверьте выделенные строки: товар из каталога и количество.');
    return e;
  }
  function fSum(errs) {
    var t = fTotals();
    if (F.tried && !errs) errs = fErrors();
    if (errs && !errs.length && SERVER_ERRS.length) errs = SERVER_ERRS;
    $('f-sum').innerHTML = '<h3>Итог</h3><dl>' +
      '<div><dt>Позиций</dt><dd>' + t.pos + '</dd></div><div><dt>Упаковок</dt><dd>' + t.packs + NB + 'шт</dd></div>' +
      '<div><dt>Коробок</dt><dd>' + t.boxes + '</dd></div>' +
      '<div><dt>Вес</dt><dd>' + dec(t.kg, 1) + NB + 'кг</dd></div><div><dt>Объём</dt><dd>' + dec(t.vol, 2) + NB + 'м³</dd></div>' +
      '<div><dt>Выдача</dt><dd>' + (F.S.sel ? cap(relDay(F.S.sel.d)) + ', ' + AdminSlots.win(F.S.sel.h) : '—') + '</dd></div></dl>' +
      '<div class="of-tot"><span>Сумма</span><b>' + money(t.sum) + '</b></div>' +
      (errs && errs.length ? '<div class="errs" role="alert">' + errs.map(function (x) { return '<span>' + esc(x) + '</span>'; }).join('') + '</div>' : '') +
      '<button class="btn btn-primary" type="submit" id="f-go">' + (F.no ? 'Сохранить изменения' : 'Создать заказ') + '</button>' +
      '<p class="hint">' + (F.no ? 'Покупатель увидит новый состав в истории заказов.' : 'Заказ от менеджера сразу получает статус «Принят» — так его увидит и покупатель в истории заказов на сайте.') + '</p>';
  }

  $('of-form').addEventListener('submit', function (e) {
    F.tried = true;
    SERVER_ERRS = [];
    var errs = fErrors();
    if (errs.length) {
      e.preventDefault();
      fClient(); fSlots(); fLines(); fSum(errs);
      var bad = document.querySelector('#of-form .is-bad') || document.querySelector('#f-sum .errs');
      if (bad) bad.scrollIntoView({ block: 'center', behavior: 'smooth' });
      if (bad && bad.focus) bad.focus({ preventScroll: true });
      Admin.guard.set(true);
      return;
    }
    var h = '<input type="hidden" name="client" value="' + F.client + '">' +
      '<input type="hidden" name="day" value="' + esc(F.S.sel.d) + '"><input type="hidden" name="hour" value="' + F.S.sel.h + '">';
    F.rows.forEach(function (r) {
      if (!r.key && !r.qty) return;
      h += '<input type="hidden" name="lineProduct" value="' + esc(r.key) + '"><input type="hidden" name="lineQty" value="' + esc(r.qty) + '">';
    });
    $('of-hidden').innerHTML = h;
    var go = $('f-go'); if (go) { go.disabled = true; go.textContent = F.no ? 'Сохраняем…' : 'Создаём…'; }
  });
  $('f-add').addEventListener('click', function () {
    F.rows.push({ id: ++uid, key: '', text: '', qty: '' });
    dirty(); fLines(); fSum();
    var el = $('f-lines').querySelector('.ln:last-child .ln-nm input');
    if (el) { el.focus(); el.scrollIntoView({ block: 'center', behavior: 'smooth' }); }
  });
  $('f-pay').addEventListener('change', function (e) { F.pay = e.target.value; dirty(); });
  $('f-note').addEventListener('input', dirty);

  fClient(); fSlots(); fLines(); fSum(SERVER_ERRS.length ? SERVER_ERRS : null);
  bindLines();
  if (SERVER_ERRS.length) Admin.guard.set(true);
})();
