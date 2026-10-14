/* Общее для разделов «Склад сырья», «Заявки на сырьё», «Покупки», «Производство», «Склад», «Товары»:
   числа как в макете, подсказки при вводе, маленькие окна (вопрос, количество, выбор из списка) и
   отправка действий обычной формой POST — сервер делает то же, что приложение, и возвращает на страницу. */
(function () {
  'use strict';
  var $ = Admin.$, esc = Admin.esc, icon = Admin.icon;
  var NB = ' ';

  function json(id) { var el = $(id); if (!el) return null; try { return JSON.parse(el.textContent); } catch (e) { return null; } }

  /* ---- числа ---- */
  function grp(s) { return s.replace(/\B(?=(\d{3})+(?!\d))/g, NB); }
  function g3(n) { return (n < 0 ? '−' : '') + grp(String(Math.round(Math.abs(n)))); }
  function dec(x, d) { var s = Math.abs(x).toFixed(d).split('.'); return (x < 0 ? '−' : '') + grp(s[0]) + (d ? ',' + s[1] : ''); }
  function money(n) { return dec(n, 0) + NB + '₸'; }
  function qn(x) {                                       // 1 180 · 41,4 · 0,138 — без лишних нулей
    var a = Math.abs(x), s = dec(x, a >= 100 ? 1 : a >= 10 ? 2 : 3);
    return s.indexOf(',') >= 0 ? s.replace(/0+$/, '').replace(/,$/, '') : s;
  }
  function qu(x, unit) { return qn(x) + NB + (unit || 'кг'); }
  function plural(n, a, b, c) { n = Math.abs(n) % 100; var d = n % 10; return n > 10 && n < 20 ? c : d === 1 ? a : d >= 2 && d <= 4 ? b : c; }
  function zam(b) {
    if (Math.abs(b - Math.round(b)) > 1e-9) return qn(b) + NB + 'замеса';
    var n = Math.round(b);
    return n + NB + plural(n, 'замес', 'замеса', 'замесов');
  }
  function numIn(s) { var v = parseFloat(String(s == null ? '' : s).replace(/\s/g, '').replace(',', '.')); return isFinite(v) ? v : NaN; }
  function r3(x) { return Math.round(x * 1000) / 1000; }
  function norm(s) { return String(s == null ? '' : s).toLowerCase().replace(/ё/g, 'е').trim(); }
  function short(name) { return String(name).replace(/\s*\([^)]*\)\s*$/, ''); }
  function numStr(x) { return String(r3(x)).replace('.', ','); }

  /* ---- подсказки при вводе: с начала названия, потом с начала слова, потом внутри ---- */
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
    return out.slice(0, max || 6);
  }
  function hl(m) { return esc(m.label.slice(0, m.at)) + '<b>' + esc(m.label.slice(m.at, m.at + m.len)) + '</b>' + esc(m.label.slice(m.at + m.len)); }
  function acBind(input, list, pick, extra, anchor) {
    var box = null, hi = 0;
    function close() { if (box) { box.remove(); box = null; } }
    function draw() {
      close();
      if (!norm(input.value)) return;
      var found = rank(list(), input.value, 6);
      box = document.createElement('div');
      box.className = 'sug ac-sug';
      if (!found.length) box.innerHTML = '<p class="sug-none">Ничего не нашлось.</p>';
      else {
        hi = Math.min(hi, found.length - 1);
        box.setAttribute('role', 'listbox');
        box.innerHTML = found.map(function (m, i) {
          return '<button type="button" tabindex="-1" role="option" data-key="' + esc(m.key) + '" aria-selected="' + (i === hi) + '"><span>' + hl(m) + '</span>' +
            (extra ? '<small class="muted ac-x">' + extra(m.key) + '</small>' : '') + '</button>';
        }).join('');
      }
      (anchor ? anchor() : input).after(box);
      box.addEventListener('mousedown', function (e) {
        var b = e.target.closest('button[data-key]'); if (!b) return;
        e.preventDefault(); close(); pick(b.getAttribute('data-key'));
      });
    }
    input.addEventListener('input', function () { hi = 0; draw(); });
    input.addEventListener('focus', function () { input.select(); });
    input.addEventListener('blur', function () { setTimeout(close, 150); });
    input.addEventListener('keydown', function (e) {
      if (!box) return;
      var found = rank(list(), input.value, 6); if (!found.length) return;
      if (e.key === 'ArrowDown') { e.preventDefault(); hi = (hi + 1) % found.length; draw(); }
      else if (e.key === 'ArrowUp') { e.preventDefault(); hi = (hi + found.length - 1) % found.length; draw(); }
      else if (e.key === 'Enter') { e.preventDefault(); var k = found[Math.min(hi, found.length - 1)].key; close(); pick(k); }
      else if (e.key === 'Escape') { e.stopPropagation(); close(); }
    });
  }
  function exact(list, text) {                           // название набрано целиком — без выбора из подсказки
    var q = norm(text), key = '';
    list.forEach(function (m) { if (q && norm(m.label) === q) key = m.key; });
    return key;
  }
  function labelOf(list, key) { for (var i = 0; i < list.length; i++) if (String(list[i].key) === String(key)) return list[i].label; return ''; }

  /* ---- действие: обычная форма POST с CSRF — сервер вернёт на страницу с сообщением ---- */
  function post(url, fields) {
    var f = document.createElement('form');
    f.method = 'post'; f.action = url; f.hidden = true;
    var html = Admin.csrfInput();
    Object.keys(fields || {}).forEach(function (k) {
      var v = fields[k];
      (Array.isArray(v) ? v : [v]).forEach(function (x) { html += '<input type="hidden" name="' + esc(k) + '" value="' + esc(x == null ? '' : x) + '">'; });
    });
    f.innerHTML = html;
    document.body.appendChild(f);
    Admin.guard.set(false);
    f.submit();
  }

  /* ---- маленькие окна ---- */
  function askDlg(o) {                                   // {title, text, ok, danger, note: placeholder, needNote, onOk(note)}
    Admin.openDlg('<form class="dlg-in" id="ask"><h2 id="dlg-h">' + o.title + '</h2>' + (o.text ? '<p class="muted">' + o.text + '</p>' : '') +
      (o.note != null ? '<div class="field"><label class="lbl" for="ask-n">' + (o.noteLabel || 'Причина') + '</label><textarea class="ta" id="ask-n" maxlength="300" placeholder="' + esc(o.note) + '"></textarea></div>' : '') +
      '<p class="errs" id="ask-e" hidden></p>' +
      '<div class="dlg-acts"><button class="btn btn-soft" type="button" data-close>' + (o.cancel || 'Отмена') + '</button>' +
      '<button class="btn ' + (o.danger ? 'btn-danger' : 'btn-primary') + '" type="submit">' + o.ok + '</button></div></form>');
    $('ask').addEventListener('submit', function (e) {
      e.preventDefault();
      var n = $('ask-n') ? $('ask-n').value.trim() : '';
      if (o.needNote && !n) { $('ask-e').textContent = 'Напишите причину.'; $('ask-e').hidden = false; $('ask-n').focus(); return; }
      Admin.closeDlg(); o.onOk(n);
    });
    var f = $('ask-n') || $('ask').querySelector('.btn-primary,.btn-danger');
    if (f) f.focus();
  }
  function qtyDlg(o) {                                   // {title, text, label, unit, value, max, maxMsg, int, hint(q), ok, onOk(q)}
    Admin.openDlg('<form class="dlg-in" id="qd"><h2 id="dlg-h">' + o.title + '</h2>' + (o.text ? '<p class="muted">' + o.text + '</p>' : '') +
      '<label class="field"><span>' + (o.label || 'Количество') + '</span><span class="qin"><input class="inp" id="qd-v" inputmode="decimal" autocomplete="off" value="' + esc(o.value == null ? '' : numStr(o.value)) + '"><i>' + esc(o.unit || 'кг') + '</i></span></label>' +
      '<p class="hint" id="qd-h">' + (o.hint ? o.hint(numIn(o.value)) : '') + '</p><p class="errs" id="qd-e" hidden></p>' +
      '<div class="dlg-acts"><button class="btn btn-soft" type="button" data-close>Отмена</button><button class="btn btn-primary" type="submit">' + (o.ok || 'Сохранить') + '</button></div></form>');
    var v = $('qd-v');
    v.focus(); v.select();
    v.addEventListener('input', function () { var s = this.value.replace(o.int ? /\D/g : /[^0-9.,]/g, '').slice(0, 9); if (s !== this.value) this.value = s; if (o.hint) $('qd-h').innerHTML = o.hint(numIn(s)); });
    $('qd').addEventListener('submit', function (e) {
      e.preventDefault();
      var q = numIn(v.value), err = !(q > 0) ? 'Введите количество больше нуля.' : o.max != null && q > o.max + 1e-9 ? o.maxMsg : '';
      if (err) { $('qd-e').textContent = err; $('qd-e').hidden = false; v.classList.add('is-bad'); v.focus(); return; }
      Admin.closeDlg(); o.onOk(r3(q));
    });
  }
  function pickDlg(o) {                                  // {title, text, label, list(), extra(key, full), unit(key), q, qty:false, ok, check(key,q), onOk(key, q)}
    var key = o.key || '';
    Admin.openDlg('<form class="dlg-in" id="pk"><h2 id="dlg-h">' + o.title + '</h2>' + (o.text ? '<p class="muted">' + o.text + '</p>' : '') +
      '<div class="field" id="pk-row"><label class="lbl" for="pk-n">' + (o.label || 'Сырьё') + '</label><input class="inp" id="pk-n" autocomplete="off" spellcheck="false" placeholder="Начните вводить название" value="' + esc(key ? labelOf(o.list(), key) : '') + '"></div>' +
      (o.qty !== false ? '<label class="field"><span>Количество</span><span class="qin"><input class="inp" id="pk-q" inputmode="decimal" autocomplete="off" value="' + esc(o.q ? numStr(o.q) : '') + '"><i id="pk-u">' + esc(unitOf(key)) + '</i></span></label>' : '') +
      '<p class="hint" id="pk-h"></p><p class="errs" id="pk-e" hidden></p>' +
      '<div class="dlg-acts"><button class="btn btn-soft" type="button" data-close>Отмена</button><button class="btn btn-primary" type="submit">' + (o.ok || 'Добавить') + '</button></div></form>');
    var n = $('pk-n'), q = $('pk-q');
    function unitOf(k) { return o.unit ? (typeof o.unit === 'function' ? (k ? o.unit(k) : '') || 'кг' : o.unit) : 'кг'; }
    function hint() { $('pk-h').innerHTML = key && o.extra ? o.extra(key, true) : ''; if ($('pk-u')) $('pk-u').textContent = unitOf(key); }
    acBind(n, o.list, function (k) { key = k; n.value = labelOf(o.list(), k); hint(); if (q) { q.focus(); q.select(); } }, o.extra ? function (k) { return o.extra(k); } : null, function () { return $('pk-row'); });
    n.addEventListener('input', function () { key = exact(o.list(), n.value); hint(); });
    if (q) q.addEventListener('input', function () { var s = this.value.replace(/[^0-9.,]/g, '').slice(0, 9); if (s !== this.value) this.value = s; });
    hint();
    (key && q ? q : n).focus();
    $('pk').addEventListener('submit', function (e) {
      e.preventDefault();
      key = key || exact(o.list(), n.value);
      var v = q ? numIn(q.value) : 0, err = !key ? 'Выберите из списка.' : q && !(v > 0) ? 'Введите количество больше нуля.' : o.check ? o.check(key, v) : '';
      if (err) { $('pk-e').textContent = err; $('pk-e').hidden = false; (!key ? n : q).focus(); return; }
      Admin.closeDlg(); o.onOk(key, r3(v));
    });
  }

  window.Ops = {
    NB: NB, json: json, g3: g3, dec: dec, money: money, qn: qn, qu: qu, plural: plural, zam: zam, numIn: numIn, r3: r3,
    norm: norm, short: short, numStr: numStr, rank: rank, hl: hl, acBind: acBind, exact: exact, labelOf: labelOf,
    post: post, askDlg: askDlg, qtyDlg: qtyDlg, pickDlg: pickDlg
  };
})();
