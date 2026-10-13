/* Рецептура — экран владельца «Рецептура» в цветах админки (vRecipe, rxRows, rxSug, rxPick, rxCalc,
   rxPaint, rxSave из макета). Сырьё — только из справочника (#rx-mats), сохранение — JSON на сервер. */
(function () {
  'use strict';
  var $ = Admin.$, esc = Admin.esc, icon = Admin.icon;
  function json(id) { try { return JSON.parse($(id).textContent); } catch (e) { return null; } }
  var S = json('rx-state-json') || {}, MATS = json('rx-mats') || [];
  var MAT = {}; MATS.forEach(function (m) { MAT[m.key] = m; });
  function norm(s) { return String(s == null ? '' : s).toLowerCase().replace(/ё/g, 'е').trim(); }
  function numv(s) { var v = parseFloat(String(s == null ? '' : s).replace(/\s/g, '').replace(',', '.')); return isFinite(v) ? v : 0; }
  function fix(x, d) { return x.toFixed(d).replace('.', ','); }
  function plural(n, a, b, c) { var d = n % 10, h = n % 100; return d === 1 && h !== 11 ? a : d >= 2 && d <= 4 && (h < 12 || h > 14) ? b : c; }
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

  var id = 0;
  var RX = {
    yieldQty: S.yield || '', rows: (S.rows || []).map(function (x) { return { id: ++id, ing: x.ing, text: x.text, qty: x.qty }; }),
    next: id + 1, dirty: false, saved: false, tried: false, active: null, typed: false, hi: 0, busy: false
  };
  function rowOf(rid) { for (var i = 0; i < RX.rows.length; i++) if (RX.rows[i].id === rid) return RX.rows[i]; return null; }
  function unit(row) { return row.ing && MAT[row.ing] ? MAT[row.ing].unit : 'кг'; }

  function rxRows() {
    var box = $('rx-rows');
    if (!RX.rows.length) { box.innerHTML = '<p class="rx-empty">В составе пока нет позиций.</p>'; return; }
    box.innerHTML = RX.rows.map(function (row, i) {
      var label = row.ing && MAT[row.ing] ? MAT[row.ing].label : 'новая позиция';
      return '<div class="rx-row" data-id="' + row.id + '"><span class="rx-no" aria-hidden="true">' + (i + 1) + '</span>' +
        '<input class="rx-name" type="text" aria-label="Сырьё" placeholder="Введите сырьё" autocomplete="off" autocorrect="off" spellcheck="false" maxlength="60" value="' + esc(row.text) + '">' +
        '<label class="rx-q"><input type="text" inputmode="decimal" autocomplete="off" placeholder="0,000" aria-label="Количество, ' + esc(unit(row)) + ': ' + esc(label) + '" value="' + esc(row.qty) + '"><i>' + esc(unit(row)) + '</i></label>' +
        '<button class="rx-del" type="button" aria-label="Удалить позицию: ' + esc(label) + '">' + icon('trash') + '</button></div>';
    }).join('');
    rxPaint();
  }
  function rxSug() {
    var old = $('rx-sug'); if (old) old.remove();
    if (!RX.active || !RX.typed) return;
    var row = rowOf(RX.active); if (!row || !norm(row.text)) return;
    var found = rank(MATS, row.text), el = document.createElement('div');
    el.id = 'rx-sug';
    el.className = 'rx-sug';
    if (!found.length) el.innerHTML = '<p class="sug-none">Такого сырья нет в справочнике.</p>';
    else {
      var c = Math.min(RX.hi, found.length - 1);
      el.setAttribute('role', 'listbox');
      el.innerHTML = found.map(function (m, i) {
        return '<button type="button" tabindex="-1" role="option" data-key="' + m.key + '" aria-selected="' + (i === c) + '"><span>' + hl(m) + '</span></button>';
      }).join('');
    }
    var rowEl = $('rx-rows').querySelector('.rx-row[data-id="' + RX.active + '"]');
    if (!rowEl) return;
    rowEl.after(el);
    var foot = document.querySelector('.rx-foot'), lim = window.innerHeight - (foot ? foot.offsetHeight : 0) - 8, b = el.getBoundingClientRect().bottom;
    if (b > lim) window.scrollBy(0, b - lim);
  }
  function rxPick(rid, key) {
    var row = rowOf(rid); if (!row) return;
    row.ing = key; row.text = MAT[key].label;
    RX.active = null; RX.typed = false; RX.hi = 0;
    var rowEl = $('rx-rows').querySelector('.rx-row[data-id="' + rid + '"]');
    rowEl.querySelector('.rx-name').value = row.text;
    rowEl.querySelector('.rx-q i').textContent = unit(row);
    rowEl.querySelector('.rx-q input').setAttribute('aria-label', 'Количество, ' + unit(row) + ': ' + row.text);
    rowEl.querySelector('.rx-del').setAttribute('aria-label', 'Удалить позицию: ' + row.text);
    rxSug(); rxDirty();
    var q = rowEl.querySelector('.rx-q input'); q.focus(); q.select();
  }
  function rxDirty() { RX.dirty = true; RX.saved = false; Admin.guard.set(true); rxCalc(); if (RX.tried) rxPaint(); }
  function rxCalc() {
    var total = 0, invalid = 0;
    RX.rows.forEach(function (r) { var q = numv(r.qty); if (!r.ing || q <= 0) invalid++; if (r.ing) total += q; });
    var pcs = Math.floor(numv(RX.yieldQty)), ok = pcs > 0 && total > 0, n = RX.rows.length;
    $('rx-total').textContent = fix(total, 3);
    $('rx-per').textContent = ok ? fix(total / pcs * 1000, 1) : '—';
    $('rx-pu').textContent = ok ? 'г' : '';
    $('rx-count').textContent = n + ' ' + plural(n, 'позиция', 'позиции', 'позиций') + ' · на 1 замес';
    var st = $('rx-state');
    st.className = 'rx-state' + (RX.dirty ? '' : ' is-clean');
    st.innerHTML = RX.dirty ? '<i aria-hidden="true"></i>Не сохранено' : 'Сохранено';
    var savedNow = RX.saved && !RX.dirty;
    $('rx-save').innerHTML = RX.busy ? 'Сохраняем…' : savedNow ? icon('check') + 'Сохранено' : 'Сохранить';
    if (!RX.serverErr) $('rx-err').textContent = RX.tried && (invalid > 0 || pcs <= 0) ? 'Заполните выделенные поля: сырьё из справочника и количество.' : '';
  }
  function rxPaint() {
    var pcs = Math.floor(numv(RX.yieldQty));
    $('rx-yb').classList.toggle('is-bad', RX.tried && pcs <= 0);
    RX.rows.forEach(function (r) {
      var el = $('rx-rows').querySelector('.rx-row[data-id="' + r.id + '"]'); if (!el) return;
      el.querySelector('.rx-name').classList.toggle('is-bad', RX.tried && !r.ing);
      el.querySelector('.rx-q').classList.toggle('is-bad', RX.tried && numv(r.qty) <= 0);
    });
  }
  function rxSave(after) {
    if (RX.busy) return false;
    var badRow = RX.rows.some(function (r) { return !r.ing || numv(r.qty) <= 0; });
    var badYield = Math.floor(numv(RX.yieldQty)) <= 0;
    RX.serverErr = false;
    if (badRow || badYield) {
      RX.tried = true; RX.saved = false;
      rxPaint(); rxCalc();
      var bad = document.querySelector('.rx .is-bad');
      if (bad) { var f = bad.matches('input') ? bad : bad.querySelector('input'); if (f) f.focus(); bad.scrollIntoView({ block: 'center', behavior: 'smooth' }); }
      return false;
    }
    RX.busy = true; rxCalc();
    var headers = { 'Content-Type': 'application/json' };
    headers[Admin.csrf.header] = Admin.csrf.token;
    fetch('/admin/recipes/' + S.id, {
      method: 'POST', credentials: 'same-origin', headers: headers,
      body: JSON.stringify({ yield: String(Math.floor(numv(RX.yieldQty))), rows: RX.rows.map(function (r) { return { ing: r.ing, qty: r.qty }; }) })
    }).then(function (r) {
      return r.json().catch(function () { return {}; }).then(function (b) { return { ok: r.ok, body: b }; });
    }).then(function (res) {
      RX.busy = false;
      if (!res.ok) {
        RX.serverErr = true;
        $('rx-err').textContent = res.body && res.body.message ? res.body.message : 'Не удалось сохранить — попробуйте ещё раз.';
        rxCalc();
        return;
      }
      RX.tried = false; RX.dirty = false; RX.saved = true;
      Admin.guard.set(false);
      rxPaint(); rxCalc();
      Admin.toast('Рецептура «' + S.name + '» сохранена');
      if (after) location.href = after;
    }).catch(function () {
      RX.busy = false; RX.serverErr = true;
      $('rx-err').textContent = 'Нет связи с сервером — изменения не сохранены.';
      rxCalc();
    });
    return true;
  }

  $('rx-y').value = RX.yieldQty;
  rxRows(); rxCalc();
  var rows = $('rx-rows');
  $('rx-y').addEventListener('input', function () {
    var v = this.value.replace(/[^0-9]/g, '').slice(0, 6);
    if (v !== this.value) this.value = v;
    RX.yieldQty = v; rxDirty();
  });
  rows.addEventListener('input', function (e) {
    var rowEl = e.target.closest('.rx-row'); if (!rowEl) return;
    var row = rowOf(+rowEl.getAttribute('data-id'));
    if (e.target.classList.contains('rx-name')) {
      var text = e.target.value.slice(0, 60), q = norm(text), ing = '';
      MATS.forEach(function (m) { if (q && norm(m.label) === q) ing = m.key; });
      row.text = text; row.ing = ing;
      RX.active = row.id; RX.typed = true; RX.hi = 0;
      rxSug();
    } else {
      var v = e.target.value.replace(/[^0-9.,]/g, '').slice(0, 7);
      if (v !== e.target.value) e.target.value = v;
      row.qty = v;
    }
    rxDirty();
  });
  rows.addEventListener('focusin', function (e) {
    if (!e.target.classList.contains('rx-name')) return;
    e.target.select();
    RX.active = +e.target.closest('.rx-row').getAttribute('data-id'); RX.typed = false; RX.hi = 0;
    rxSug();
  });
  rows.addEventListener('focusout', function (e) {
    if (!e.target.classList.contains('rx-name')) return;
    var row = rowOf(+e.target.closest('.rx-row').getAttribute('data-id'));
    if (row && row.ing) e.target.value = row.text = MAT[row.ing].label;
    if (row && RX.active === row.id) { RX.active = null; RX.typed = false; }
    rxSug();
  });
  rows.addEventListener('keydown', function (e) {
    if (!e.target.classList.contains('rx-name')) return;
    var rid = +e.target.closest('.rx-row').getAttribute('data-id');
    if (RX.active !== rid || !RX.typed) return;
    var found = rank(MATS, rowOf(rid).text);
    if (!found.length) return;
    var c = Math.min(RX.hi, found.length - 1);
    if (e.key === 'ArrowDown') { e.preventDefault(); RX.hi = (c + 1) % found.length; rxSug(); }
    else if (e.key === 'ArrowUp') { e.preventDefault(); RX.hi = (c + found.length - 1) % found.length; rxSug(); }
    else if (e.key === 'Enter') { e.preventDefault(); rxPick(rid, found[c].key); }
  });
  rows.addEventListener('mousedown', function (e) {
    var b = e.target.closest('#rx-sug button[data-key]'); if (!b) return;
    e.preventDefault();
    rxPick(RX.active, +b.getAttribute('data-key'));
  });
  rows.addEventListener('click', function (e) {
    var del = e.target.closest('.rx-del'); if (!del) return;
    var rid = +del.closest('.rx-row').getAttribute('data-id'), i = RX.rows.indexOf(rowOf(rid));
    RX.rows.splice(i, 1);
    rxRows(); rxDirty();
    var names = rows.querySelectorAll('.rx-name');
    (names[Math.min(i, names.length - 1)] || $('rx-add')).focus();
  });
  $('rx-add').addEventListener('click', function () {
    RX.rows.push({ id: RX.next++, ing: '', text: '', qty: '' });
    rxRows(); rxDirty();
    var names = rows.querySelectorAll('.rx-name'), el = names[names.length - 1];
    el.focus();
    el.scrollIntoView({ block: 'center', behavior: 'smooth' });
  });
  $('rx-save').addEventListener('click', function () { rxSave(); });
  Admin.guard.msg('В рецептуре есть несохранённые изменения.');
  document.addEventListener('admin:save-and-leave', function (e) {
    e.preventDefault();
    rxSave(e.detail.target);
  });
})();
