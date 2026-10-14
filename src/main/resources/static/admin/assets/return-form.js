/* Возврат к заказу — что привезли, в штуках (vRForm из макета). Данные — JSON в странице: #re-state (заказ, возврат,
   строки, оплачен ли), #re-catalog (товары с ценой, как она ляжет в возврат), #re-quick-keys (что берёт клиент),
   #re-errors (ошибки сервера). Отправка — обычная форма: строки уходят полями lineProduct/lineQty, проверки
   повторяет сервер. Весь возврат негодный — сразу в списание, сумма вычитается из заказа. */
(function () {
  'use strict';
  var $ = Admin.$, esc = Admin.esc, icon = Admin.icon;
  function json(id) { try { return JSON.parse($(id).textContent); } catch (e) { return null; } }
  var ST = json('re-state') || {}, CAT = json('re-catalog') || [], QUICK = json('re-quick-keys') || [];
  var SERVER_ERRS = json('re-errors') || [];
  var NB = ' ';
  var P = {};
  CAT.forEach(function (p) { p.price = +p.price || 0; p.box = p.box == null ? null : +p.box; P[p.key] = p; });
  var SUM = +ST.sum || 0, OTHERS = +ST.others || 0;

  function g3(n) { return String(Math.round(Math.abs(n))).replace(/\B(?=(\d{3})+(?!\d))/g, NB); }
  function money(n) { return (n < 0 ? '−' : '') + g3(n) + NB + '₸'; }
  function norm(s) { return String(s == null ? '' : s).toLowerCase().replace(/ё/g, 'е').trim(); }
  function thumb(p) {
    return p && p.photo ? '<img class="th" src="' + esc(p.photo) + '" alt="" width="30" height="40" loading="lazy">'
      : '<span class="th th-none">' + icon('cookie') + '</span>';
  }
  // как в форме заказа: с начала названия, потом с начала слова, потом внутри
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
  var RE = {
    rows: (ST.rows && ST.rows.length ? ST.rows : [{ key: '', qty: '' }]).map(function (r) {
      var k = r.key === '' || r.key == null ? '' : +r.key;
      return { id: ++uid, key: P[k] ? k : '', text: P[k] ? P[k].label : '', qty: r.qty == null ? '' : String(r.qty) };
    }),
    active: null, typed: false, hi: 0, tried: SERVER_ERRS.length > 0
  };
  function dirty() { Admin.guard.set(true); }
  function rowOf(id) { for (var i = 0; i < RE.rows.length; i++) if (RE.rows[i].id === id) return RE.rows[i]; return null; }

  /* ---- быстро добавить: что берёт клиент ---- */
  function reQuick() {
    var have = {};
    RE.rows.forEach(function (r) { if (r.key) have[r.key] = 1; });
    var list = QUICK.filter(function (k) { return P[k] && !have[k]; }).slice(0, 12);
    $('re-quick').innerHTML = list.length ? '<p class="lbl">Быстро добавить — что берёт клиент</p><div class="qchips">' + list.map(function (k) {
      return '<button type="button" class="qchip" data-k="' + k + '">' + icon('plus') + '<span>' + esc(P[k].label) + '</span></button>';
    }).join('') + '</div>' : '';
  }

  /* ---- строки ---- */
  function lineSum(p, q) { return p && q ? money(p.price * q) + '<small>' + money(p.price) + ' × ' + q + '</small>' : p ? '<small>' + money(p.price) + ' за шт</small>' : ''; }
  function reLines() {
    $('re-lines').innerHTML = RE.rows.map(function (r) {
      var p = r.key ? P[r.key] : null, q = +r.qty || 0;
      return '<div class="ln ln--r" data-id="' + r.id + '">' + thumb(p) +
        '<div class="ln-nm"><input class="inp' + (RE.tried && !r.key ? ' is-bad' : '') + '" type="text" aria-label="Товар" placeholder="Название товара" autocomplete="off" spellcheck="false" maxlength="80" value="' + esc(r.text) + '"></div>' +
        '<label class="ln-q"><span class="sr">Вернули, шт</span><input class="inp' + (RE.tried && !(q > 0) ? ' is-bad' : '') + '" type="text" inputmode="numeric" autocomplete="off" placeholder="0" value="' + esc(r.qty) + '"><i>шт</i></label>' +
        '<div class="ln-sum">' + lineSum(p, q) + '</div>' +
        '<button class="rx-del ln-del" type="button" aria-label="Удалить строку' + (p ? ': ' + esc(p.label) : '') + '">' + icon('trash') + '</button></div>';
    }).join('') || '<p class="rx-empty">Строк нет — добавьте товар.</p>';
    reSug();
  }
  function reRefresh(ln, r) {
    var p = r.key ? P[r.key] : null, q = +r.qty || 0;
    ln.querySelector('.th').outerHTML = thumb(p);
    ln.querySelector('.ln-sum').innerHTML = lineSum(p, q);
    ln.querySelector('.ln-del').setAttribute('aria-label', 'Удалить строку' + (p ? ': ' + p.label : ''));
    if (RE.tried) {
      ln.querySelector('.ln-nm input').classList.toggle('is-bad', !r.key);
      ln.querySelector('.ln-q input').classList.toggle('is-bad', !(q > 0));
    }
  }
  function reSug() {
    var old = $('re-sug'); if (old) old.remove();
    if (!RE.active || !RE.typed) return;
    var r = rowOf(RE.active); if (!r || !norm(r.text)) return;
    var found = rank(CAT, r.text, 6), el = document.createElement('div');
    el.id = 're-sug';
    el.className = 'sug';
    if (!found.length) el.innerHTML = '<p class="sug-none">Такого товара нет в каталоге.</p>';
    else {
      var c = Math.min(RE.hi, found.length - 1);
      el.setAttribute('role', 'listbox');
      el.innerHTML = found.map(function (m, i) {
        var p = P[m.key];
        return '<button type="button" tabindex="-1" role="option" data-key="' + m.key + '" aria-selected="' + (i === c) + '">' + thumb(p) + '<span>' + hl(m) + '</span>' +
          '<small class="muted" style="margin-left:auto;white-space:nowrap">' + money(p.price) + '</small></button>';
      }).join('');
    }
    var rowEl = $('re-lines').querySelector('.ln[data-id="' + RE.active + '"]');
    if (rowEl) rowEl.after(el);
  }
  function rePick(id, key) {
    var r = rowOf(id); if (!r) return;
    r.key = key; r.text = P[key].label;
    RE.active = null; RE.typed = false; RE.hi = 0; dirty();
    var ln = $('re-lines').querySelector('.ln[data-id="' + id + '"]');
    ln.querySelector('.ln-nm input').value = r.text;
    reRefresh(ln, r); reSug(); reQuick(); reSum();
    var qi = ln.querySelector('.ln-q input'); qi.focus(); qi.select();
  }
  function bind() {
    var box = $('re-lines');
    $('re-quick').addEventListener('click', function (e) {
      var b = e.target.closest('.qchip'); if (!b) return;
      var key = +b.getAttribute('data-k'), r = RE.rows.filter(function (x) { return !x.key && !x.text && !x.qty; })[0];
      if (!r) { r = { id: ++uid, key: '', text: '', qty: '' }; RE.rows.push(r); }
      r.key = key; r.text = P[key].label; dirty();
      reLines(); reQuick(); reSum();
      var qi = box.querySelector('.ln[data-id="' + r.id + '"] .ln-q input');
      if (qi) { qi.focus(); qi.scrollIntoView({ block: 'nearest', behavior: 'smooth' }); }
    });
    box.addEventListener('input', function (e) {
      var ln = e.target.closest('.ln'); if (!ln) return;
      var r = rowOf(+ln.getAttribute('data-id'));
      if (e.target.closest('.ln-nm')) {
        r.text = e.target.value.slice(0, 80);
        var q = norm(r.text), key = '';
        CAT.forEach(function (m) { if (q && norm(m.label) === q) key = m.key; });
        if (key !== r.key) { r.key = key; reRefresh(ln, r); reQuick(); }
        RE.active = r.id; RE.typed = true; RE.hi = 0;
        reSug();
      } else {
        var v = e.target.value.replace(/\D/g, '').slice(0, 5);
        if (v !== e.target.value) e.target.value = v;
        r.qty = v;
        reRefresh(ln, r);
      }
      dirty(); reSum();
    });
    box.addEventListener('focusin', function (e) {
      if (!e.target.closest('.ln-nm')) return;
      e.target.select();
      RE.active = +e.target.closest('.ln').getAttribute('data-id'); RE.typed = false; RE.hi = 0; reSug();
    });
    box.addEventListener('focusout', function (e) {
      if (!e.target.closest('.ln-nm')) return;
      var r = rowOf(+e.target.closest('.ln').getAttribute('data-id'));
      if (r && r.key) e.target.value = r.text = P[r.key].label;
      if (r && RE.active === r.id) { RE.active = null; RE.typed = false; }
      reSug();
    });
    box.addEventListener('keydown', function (e) {
      if (!e.target.closest('.ln-nm')) return;
      if (e.key === 'Enter') e.preventDefault();
      if (!RE.typed) return;
      var r = rowOf(+e.target.closest('.ln').getAttribute('data-id')), found = rank(CAT, r.text, 6);
      if (!found.length) return;
      var c = Math.min(RE.hi, found.length - 1);
      if (e.key === 'ArrowDown') { e.preventDefault(); RE.hi = (c + 1) % found.length; reSug(); }
      else if (e.key === 'ArrowUp') { e.preventDefault(); RE.hi = (c + found.length - 1) % found.length; reSug(); }
      else if (e.key === 'Enter') rePick(r.id, found[c].key);
    });
    box.addEventListener('mousedown', function (e) {
      var b = e.target.closest('#re-sug button[data-key]'); if (!b) return;
      e.preventDefault();
      rePick(RE.active, +b.getAttribute('data-key'));
    });
    box.addEventListener('click', function (e) {
      var del = e.target.closest('.ln-del'); if (!del) return;
      var r = rowOf(+del.closest('.ln').getAttribute('data-id')), i = RE.rows.indexOf(r);
      RE.rows.splice(i, 1);
      dirty(); reLines(); reQuick(); reSum();
      var next = box.querySelectorAll('.ln')[Math.min(i, RE.rows.length - 1)];
      (next ? next.querySelector('.ln-nm input') : $('re-add')).focus();
    });
  }

  /* ---- итог и ошибки ---- */
  function totals() {
    var t = { pos: 0, pcs: 0, sum: 0 };
    RE.rows.forEach(function (r) { var q = +r.qty || 0; if (!r.key || !q) return; t.pos++; t.pcs += q; t.sum += P[r.key].price * q; });
    return t;
  }
  function errors() {
    var e = [], filled = RE.rows.filter(function (r) { return r.key || r.text || r.qty; }), seen = {}, dup = '';
    if (!filled.some(function (r) { return r.key && +r.qty > 0; })) e.push('Добавьте хотя бы один товар с количеством.');
    else if (filled.some(function (r) { return !r.key || !(+r.qty > 0); })) e.push('Проверьте выделенные строки: товар из каталога и количество.');
    filled.forEach(function (r) { if (r.key) { if (seen[r.key]) dup = P[r.key].label; seen[r.key] = 1; } });
    if (dup) e.push('«' + dup + '» в двух строках — сложите количество в одну.');
    return e;
  }
  function reSum(errs) {
    var t = totals(), left = SUM - OTHERS - t.sum;
    if (RE.tried && !errs) errs = errors();
    if (errs && !errs.length && SERVER_ERRS.length) errs = SERVER_ERRS;
    $('re-sum').innerHTML = '<h3>' + (ST.ret ? 'Возврат № ' + ST.ret : 'Возврат') + '</h3><dl>' +
      '<div><dt>Позиций</dt><dd>' + t.pos + '</dd></div><div><dt>Штук</dt><dd>' + g3(t.pcs) + '</dd></div></dl>' +
      '<div class="rcpt rcpt--sum"><p class="rcpt-r"><span>Сумма заказа</span><i></i><b>' + money(SUM) + '</b></p>' +
      (OTHERS ? '<p class="rcpt-r rcpt-ret"><span>Другие возвраты</span><i></i><b>' + money(-OTHERS) + '</b></p>' : '') +
      '<p class="rcpt-r rcpt-ret"><span>Возврат</span><i></i><b>' + money(-t.sum) + '</b></p>' +
      '<p class="rcpt-r rcpt-tot"><span>К оплате</span><i></i><b>' + money(Math.max(0, left)) + '</b></p></div>' +
      (ST.paid && ST.afterPay && t.sum ? '<p class="warn-s">' + icon('alert') + '<span>Заказ уже оплачен — переплату ' + money(t.sum) + ' нужно вернуть клиенту.</span></p>' : '') +
      (!ST.paid && left < 0 ? '<p class="warn-s">' + icon('alert') + '<span>Возврат больше заказа на ' + money(-left) + ' — разницу нужно вернуть клиенту.</span></p>' : '') +
      (errs && errs.length ? '<div class="errs" role="alert">' + errs.map(function (x) { return '<span>' + esc(x) + '</span>'; }).join('') + '</div>' : '') +
      '<button class="btn btn-primary" type="submit" id="re-go">Сохранить возврат</button>' +
      '<p class="hint">Весь возврат негодный — сразу в списание (в 1С — склад «Испорченные товары»). Сумма вычтется из заказа № ' + ST.order + '.</p>' +
      (ST.canDelete ? '<p><button class="link link--danger" type="button" id="re-del">' + icon('trash') + 'Удалить возврат</button></p>' : '');
    var del = $('re-del');
    if (del) del.onclick = askDelete;
  }
  function askDelete() {
    Admin.openDlg('<div class="dlg-in"><h2 id="dlg-h">Удалить возврат № ' + ST.ret + '?</h2>' +
      '<p class="muted">Сумма заказа № ' + ST.order + ' снова будет без возврата, документ в 1С распроведётся и пометится на удаление.</p>' +
      '<div class="dlg-acts"><button class="btn btn-soft" type="button" data-close>Не удалять</button>' +
      '<button class="btn btn-danger" type="button" id="re-del-ok">Удалить возврат</button></div></div>');
    $('re-del-ok').onclick = function () { Admin.guard.set(false); this.disabled = true; $('re-del-f').submit(); };
  }

  $('re-form').addEventListener('submit', function (e) {
    RE.tried = true;
    SERVER_ERRS = [];
    var errs = errors();
    if (errs.length) {
      e.preventDefault();
      reLines(); reSum(errs);
      var bad = document.querySelector('#re-lines .is-bad') || document.querySelector('#re-sum .errs');
      if (bad) { bad.scrollIntoView({ block: 'center', behavior: 'smooth' }); if (bad.focus) bad.focus({ preventScroll: true }); }
      Admin.guard.set(true);
      return;
    }
    var h = '';
    RE.rows.forEach(function (r) {
      if (!r.key && !r.qty) return;
      h += '<input type="hidden" name="lineProduct" value="' + esc(r.key) + '"><input type="hidden" name="lineQty" value="' + esc(r.qty) + '">';
    });
    $('re-hidden').innerHTML = h;
    var go = $('re-go'); if (go) { go.disabled = true; go.textContent = 'Сохраняем…'; }
  });
  $('re-add').addEventListener('click', function () {
    RE.rows.push({ id: ++uid, key: '', text: '', qty: '' });
    dirty(); reLines(); reSum();
    var el = $('re-lines').querySelector('.ln:last-child .ln-nm input');
    if (el) { el.focus(); el.scrollIntoView({ block: 'center', behavior: 'smooth' }); }
  });

  reQuick(); reLines(); reSum(SERVER_ERRS.length ? SERVER_ERRS : null);
  bind();
  if (SERVER_ERRS.length) Admin.guard.set(true);
})();
