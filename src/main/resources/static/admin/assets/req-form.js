/* Новая заявка на сырьё и изменение (vQForm из макета): дата документа для 1С, изделия с рецептурой — замесы
   или штуки (второе считается по выходу рецептуры), группы изделий (каждая — отдельная заявка), итог справа:
   сырьё по рецептуре и хватит ли его с учётом других новых заявок. Данные — JSON #qx-data. Отправка — обычная
   форма: строки уходят полями product/batches, у правки — keep (поправки к рецептуре применить или очистить). */
(function () {
  'use strict';
  var $ = Admin.$, esc = Admin.esc, icon = Admin.icon, O = window.Ops;
  var D = O.json('qx-data') || {};
  var NB = O.NB, qn = O.qn, g3 = O.g3, plural = O.plural, zam = O.zam, numIn = O.numIn, r3 = O.r3;
  var P = {}, PRODLIST = [];
  (D.prods || []).forEach(function (p) { P[String(p.id)] = p; PRODLIST.push({ key: String(p.id), label: p.name }); });
  var ING = D.ings || {};
  var MON = ['января', 'февраля', 'марта', 'апреля', 'мая', 'июня', 'июля', 'августа', 'сентября', 'октября', 'ноября', 'декабря'];
  var MON3 = ['янв', 'фев', 'мар', 'апр', 'мая', 'июн', 'июл', 'авг', 'сен', 'окт', 'ноя', 'дек'];
  var WDL = ['воскресенье', 'понедельник', 'вторник', 'среда', 'четверг', 'пятница', 'суббота'];
  function pd(s) { return new Date(Date.UTC(+s.slice(0, 4), +s.slice(5, 7) - 1, +s.slice(8, 10))); }
  function dml(s) { return +s.slice(8, 10) + NB + MON[+s.slice(5, 7) - 1]; }
  function dm(s) { return +s.slice(8, 10) + NB + MON3[+s.slice(5, 7) - 1]; }
  function cap(s) { return s.charAt(0).toUpperCase() + s.slice(1); }
  function qu(x, id) { return O.qu(x, (ING[id] && ING[id].unit) || 'кг'); }
  function thumb(src) { return src ? '<img class="th" src="' + esc(src) + '" alt="" width="30" height="40" loading="lazy">' : '<span class="th th-none">' + icon('cookie') + '</span>'; }
  function fmtB(b) { return b > 0 ? qn(r3(b)) : ''; }

  var QX = { no: D.no, doc: D.doc || D.today, seq: 0, rows: [], tried: !!(D.errors && D.errors.length), group: D.group, raw: false };
  function qRow(k, b, p) {
    var rc = k ? P[k] : null, row = { id: ++QX.seq, k: rc ? k : '', text: rc ? rc.name : '', b: '', p: '' };
    if (rc && p) { row.p = String(Math.round(p)); row.b = fmtB(p / rc.yield); }
    else if (rc && b) { row.b = fmtB(b); row.p = String(Math.round(b * rc.yield)); }
    return row;
  }
  (D.rows || []).forEach(function (x) { QX.rows.push(qRow(x.p == null ? '' : String(x.p), x.b, null)); });
  if (!QX.rows.length) QX.rows.push(qRow('', '', ''));
  function rowOf(id) { for (var i = 0; i < QX.rows.length; i++) if (QX.rows[i].id === id) return QX.rows[i]; return null; }
  function dirty() { Admin.guard.set(true); }

  /* ---- рецептура × замесы ---- */
  function normOf(lines) {
    var map = {}, order = [];
    lines.forEach(function (l) {
      var rc = P[l.k];
      (rc ? rc.rows : []).forEach(function (x) {
        var id = String(x[0]);
        if (!(id in map)) { map[id] = 0; order.push(id); }
        map[id] += x[1] * l.b;
      });
    });
    return order.map(function (id) { return { m: id, q: r3(map[id]) }; });
  }

  /* ---- дата ---- */
  var doc = $('qx-doc');
  doc.value = QX.doc; doc.min = D.min; doc.max = D.max;
  function qxDate() {
    $('qx-dt').textContent = QX.doc === D.today ? 'сегодня, ' + WDL[pd(D.today).getUTCDay()]
      : cap(WDL[pd(QX.doc).getUTCDay()]) + ', ' + dml(QX.doc) + (QX.doc < D.today ? ' — задним числом' : ' — на будущий день');
  }
  doc.addEventListener('change', function () { if (/^\d{4}-\d\d-\d\d$/.test(this.value)) { QX.doc = this.value; dirty(); qxDate(); qxSum(); } });

  /* ---- строки по группам ---- */
  function qxGroups() {
    var groups = {}, order = [], none = [];
    QX.rows.forEach(function (row) {
      if (!row.k) { none.push(row); return; }
      var g = P[row.k].group;
      if (!groups[g]) { groups[g] = []; order.push(g); }
      groups[g].push(row);
    });
    order.sort(function (a, b) { return a.localeCompare(b, 'ru'); });
    return { groups: groups, order: order, none: none };
  }
  function qgHead(g, rows, many) {
    var b = 0, p = 0;
    rows.forEach(function (row) { var v = numIn(row.b); if (v > 0) b += v; var w = numIn(row.p); if (w > 0) p += w; });
    return '<b>' + esc(g) + '</b><span>' + rows.length + NB + 'поз.' + (b ? ' · ' + zam(r3(b)) : '') + (p ? ' · ' + g3(p) + NB + 'шт' : '') + '</span>' + (many ? '<i>отдельная заявка</i>' : '');
  }
  function qxLine(row) {
    var rc = row.k ? P[row.k] : null, b = numIn(row.b);
    return '<div class="ln ln--q" data-id="' + row.id + '">' + thumb(rc ? rc.photo : '') +
      '<div class="ln-nm"><span class="lbl" id="qn-' + row.id + '">Изделие' + (rc ? '<i class="ln-y"> · ' + g3(rc.yield) + NB + 'шт с замеса</i>' : '') + '</span>' +
      '<input class="inp' + (QX.tried && !row.k ? ' is-bad' : '') + '" aria-labelledby="qn-' + row.id + '" placeholder="Начните вводить — например, «коржик»" autocomplete="off" spellcheck="false" maxlength="60" value="' + esc(row.text) + '"></div>' +
      '<label class="ln-q ln-qb"><span class="lbl">Замесов</span><span class="qin"><input class="inp' + (QX.tried && row.k && !(b > 0) ? ' is-bad' : '') + '" data-f="b" inputmode="decimal" autocomplete="off" placeholder="0" value="' + esc(row.b) + '"></span></label>' +
      '<label class="ln-q ln-qp"><span class="lbl">Штук</span><span class="qin"><input class="inp" data-f="p" inputmode="numeric" autocomplete="off" placeholder="0" value="' + esc(row.p) + '"><i>шт</i></span></label>' +
      '<button class="rx-del ln-del" type="button" aria-label="Убрать позицию' + (rc ? ': ' + esc(rc.name) : '') + '">' + icon('trash') + '</button></div>';
  }
  function choices() { return QX.group ? PRODLIST.filter(function (o) { return P[o.key].group === QX.group; }) : PRODLIST; }
  function qxLines() {
    var G = qxGroups(), many = G.order.length > 1, html = '';
    G.order.forEach(function (g) { html += '<div class="qg"><p class="qg-h" data-g="' + esc(g) + '">' + qgHead(g, G.groups[g], many) + '</p>' + G.groups[g].map(qxLine).join('') + '</div>'; });
    if (G.none.length) html += '<div class="qg qg--none">' + (G.order.length ? '<p class="qg-h"><span>Выберите изделие — строка встанет в свою группу</span></p>' : '') + G.none.map(qxLine).join('') + '</div>';
    $('qx-lines').innerHTML = html;
    $('qx-lines').querySelectorAll('.ln--q').forEach(function (el) {
      var row = rowOf(+el.getAttribute('data-id')), inp = el.querySelector('.ln-nm input');
      O.acBind(inp, choices, function (k) { qxPick(row.id, k); }, function (k) { return g3(P[k].yield) + NB + 'шт/замес · ' + esc(P[k].group); }, function () { return el; });
    });
  }
  function qxPick(id, k) {
    var row = rowOf(id); if (!row) return;
    var rc = P[k], b = numIn(row.b), p = numIn(row.p);
    row.k = k; row.text = rc.name;
    if (b > 0) row.p = String(Math.round(b * rc.yield));
    else if (p > 0) row.b = fmtB(p / rc.yield);
    else { row.b = '1'; row.p = String(Math.round(rc.yield)); }
    dirty();
    qxLines(); qxSum();
    var bi = $('qx-lines').querySelector('.ln--q[data-id="' + id + '"] [data-f="b"]');
    if (bi) { bi.focus(); bi.select(); }
  }
  var box = $('qx-lines');
  box.addEventListener('input', function (e) {
    var el = e.target.closest('.ln--q'); if (!el) return;
    var row = rowOf(+el.getAttribute('data-id')), f = e.target.getAttribute('data-f'), rc = row.k ? P[row.k] : null;
    if (!f) {
      row.text = e.target.value.slice(0, 60);
      var k = O.exact(choices(), row.text);
      if (k && k !== row.k) { qxPick(row.id, k); return; }
      if (!k && row.k) row.k = '';
      qxSum(); return;
    }
    var v = e.target.value.replace(f === 'b' ? /[^0-9.,]/g : /\D/g, '').slice(0, f === 'b' ? 7 : 6);
    if (v !== e.target.value) e.target.value = v;
    row[f] = v;
    if (rc) {
      var other = el.querySelector('[data-f="' + (f === 'b' ? 'p' : 'b') + '"]'), n = numIn(v);
      if (f === 'b') row.p = n > 0 ? String(Math.round(n * rc.yield)) : '';
      else row.b = n > 0 ? fmtB(n / rc.yield) : '';
      other.value = f === 'b' ? row.p : row.b;
      other.classList.remove('is-bad');
      var G = qxGroups(), g = rc.group, h = box.querySelector('.qg-h[data-g="' + g.replace(/"/g, '\\"') + '"]');
      if (h) h.innerHTML = qgHead(g, G.groups[g], G.order.length > 1);
    }
    qxSum();
  });
  box.addEventListener('focusout', function (e) {
    var el = e.target.closest('.ln--q'); if (!el || !e.target.closest('.ln-nm')) return;
    var row = rowOf(+el.getAttribute('data-id'));
    if (row && row.k && e.target.value !== P[row.k].name) e.target.value = row.text = P[row.k].name;
  });
  box.addEventListener('click', function (e) {
    var b = e.target.closest('.ln-del'); if (!b) return;
    var el = b.closest('.ln--q'), row = rowOf(+el.getAttribute('data-id')), i = QX.rows.indexOf(row);
    QX.rows.splice(i, 1);
    if (!QX.rows.length) QX.rows.push(qRow('', '', ''));
    dirty(); qxLines(); qxSum();
    var next = box.querySelectorAll('.ln-nm input')[Math.min(i, QX.rows.length - 1)];
    (next || $('qx-add')).focus();
  });
  $('qx-add').addEventListener('click', function () {
    QX.rows.push(qRow('', '', '')); dirty(); qxLines(); qxSum();
    var all = $('qx-lines').querySelectorAll('.ln-nm input'), el = all[all.length - 1];
    if (el) { el.focus(); el.scrollIntoView({ block: 'center', behavior: 'smooth' }); }
  });

  /* ---- проверки и итог ---- */
  function qxErrors() {
    var e = [], filled = QX.rows.filter(function (r) { return r.k || r.text || r.b || r.p; }), seen = {};
    if (!filled.length) e.push('Добавьте хотя бы одно изделие.');
    filled.forEach(function (r) {
      if (!r.k) e.push('Выберите изделие из списка' + (r.text ? ': «' + r.text + '» без рецептуры или не нашлось' : '') + '.');
      else if (!(numIn(r.b) > 0)) e.push(O.short(P[r.k].name) + ': сколько замесов или штук?');
      else if (seen[r.k]) e.push(O.short(P[r.k].name) + ' — дважды. Сложите в одну строку.');
      if (r.k) seen[r.k] = 1;
    });
    if (QX.group) filled.forEach(function (r) { if (r.k && P[r.k].group !== QX.group) e.push(O.short(P[r.k].name) + ' — из другой группы: оформите отдельной заявкой.'); });
    return e.filter(function (x, i) { return e.indexOf(x) === i; });
  }
  function qxSum(errs) {
    var G = qxGroups(), rows = QX.rows.filter(function (r) { return r.k && numIn(r.b) > 0; }), b = 0, p = 0;
    rows.forEach(function (r) { b += numIn(r.b); p += numIn(r.p) || 0; });
    if (QX.tried && !errs) errs = qxErrors();
    var raw = normOf(rows.map(function (r) { return { k: r.k, b: numIn(r.b) }; })), need = D.need || {}, newNos = D.newNos || [];
    var rawRows = raw.filter(function (x) { return ING[x.m] && !ING[x.m].free; }).map(function (x) {
      var mt = ING[x.m], other = need[x.m] || 0, free = mt.stock - Math.max(0, other);
      return { m: x.m, q: x.q, free: free, other: other, short: x.q > free + 1e-9 };
    });
    var shortR = rawRows.filter(function (x) { return x.short; }), nG = G.order.length;
    var groupsH = G.order.map(function (g) {
      var gb = 0; G.groups[g].forEach(function (r) { var v = numIn(r.b); if (v > 0) gb += v; });
      return '<li><b>«' + esc(g) + '»</b><span>' + G.groups[g].length + NB + 'поз.' + (gb ? ' · ' + zam(r3(gb)) : '') + '</span></li>';
    }).join('');
    var name = function (id) { return ING[id] ? esc(ING[id].name) : '—'; };
    $('qx-sum').innerHTML = '<h3>Итог</h3><dl><div><dt>Изделий</dt><dd>' + rows.length + '</dd></div><div><dt>Замесов</dt><dd>' + (b ? qn(r3(b)) : '0') + '</dd></div>' +
      '<div><dt>По норме</dt><dd>' + g3(p) + NB + 'шт</dd></div><div><dt>Дата для 1С</dt><dd>' + dm(QX.doc) + '</dd></div></dl>' +
      (nG ? '<div class="qs-g"><p>' + (QX.no ? 'Заявка № ' + QX.no : nG > 1 ? 'Будет ' + nG + NB + plural(nG, 'заявка', 'заявки', 'заявок') + ' — по одной на группу' : 'Будет одна заявка') + '</p><ul>' + groupsH + '</ul></div>' : '') +
      (raw.length ? '<div class="qs-raw">' + (shortR.length ? '<ul class="warns">' + shortR.map(function (x) {
        var mt = ING[x.m];
        return '<li>' + icon('alert') + '<span>' + name(x.m) + ': нужно ' + qu(x.q, x.m) + ', ' + (x.other > 1e-9 ? 'свободно ' + qu(Math.max(0, x.free), x.m) + ' (' + qu(mt.stock, x.m) + ' на складе, ' + qu(x.other, x.m) + ' ждут заявки № ' + newNos.join(', ') + ')' : 'на складе ' + qu(mt.stock, x.m)) + '</span></li>';
      }).join('') + '</ul>' : '<p class="ok-line">' + icon('check') + '<span>Сырья хватает' + (newNos.length ? ' — с учётом новых заявок № ' + newNos.join(', ') : '') + '</span></p>') +
        '<button class="link" type="button" id="qx-raw" aria-expanded="' + QX.raw + '">' + (QX.raw ? 'Скрыть сырьё' : 'Сырьё по рецептуре · ' + raw.length + NB + 'поз.') + '</button>' +
        (QX.raw ? '<table class="qs-t"><tbody>' + raw.map(function (x) { var rr = rawRows.filter(function (y) { return y.m === x.m; })[0]; return '<tr' + (rr && rr.short ? ' class="pay-bad"' : '') + '><td>' + name(x.m) + '</td><td class="r num">' + qu(x.q, x.m) + '</td></tr>'; }).join('') + '</tbody></table>' : '') + '</div>' : '') +
      (errs && errs.length ? '<div class="errs" role="alert">' + errs.map(function (x) { return '<span>' + esc(x) + '</span>'; }).join('') + '</div>' : '') +
      '<button class="btn btn-primary" type="submit" id="qx-go">' + (QX.no ? 'Сохранить' : nG > 1 ? 'Создать ' + nG + NB + plural(nG, 'заявку', 'заявки', 'заявок') : 'Создать заявку') + '</button>' +
      '<p class="hint">' + (QX.no ? 'Склад сырья увидит новый состав сразу; документы в 1С перезапишутся.' : 'Сырьё посчитается по рецептуре. Склад сырья увидит заявку сразу — в приложении и здесь; в 1С создадутся отчёт производства и требование-накладная.') + '</p>';
    var rb = $('qx-raw'); if (rb) rb.onclick = function () { QX.raw = !QX.raw; qxSum(); $('qx-raw').focus(); };
  }

  /* ---- отправка ---- */
  var form = $('qx-form');
  function send(keep) {
    var h = '';
    QX.rows.filter(function (r) { return r.k; }).forEach(function (r) {
      h += '<input type="hidden" name="product" value="' + esc(r.k) + '"><input type="hidden" name="batches" value="' + esc(String(r3(numIn(r.b)))) + '">';
    });
    if (keep != null) h += '<input type="hidden" name="keep" value="' + keep + '">';
    $('qx-hidden').innerHTML = h;
    Admin.guard.set(false);
    form.submit();
  }
  function qxSubmit() {
    QX.tried = true;
    var errs = qxErrors();
    if (errs.length) {
      qxLines(); qxSum(errs);
      var bad = document.querySelector('.qx-lines .is-bad') || document.querySelector('#qx-sum .errs');
      if (bad) { bad.scrollIntoView({ block: 'center', behavior: 'smooth' }); if (bad.focus) bad.focus({ preventScroll: true }); }
      return;
    }
    var adj = D.adj || [];
    if (!QX.no || !adj.length) { send(QX.no ? false : null); return; }
    Admin.openDlg('<div class="dlg-in"><h2 id="dlg-h">Поправки в составе</h2><p class="muted">В заявке есть поправки к рецептуре:</p><ul class="adj">' +
      adj.map(function (a) { return '<li>' + esc(a.name) + ' <b>' + (a.d > 0 ? '+' : a.d < 0 ? '−' : '') + (a.d ? O.qu(Math.abs(a.d), a.unit) : '') + '</b>' + (a.note ? ' <span class="muted">' + esc(a.note) + '</span>' : '') + '</li>'; }).join('') + '</ul>' +
      '<p class="hint">«Применить» — рецептура на новые замесы плюс эти поправки. «Очистить» — только рецептура.</p>' +
      '<div class="dlg-acts"><button class="btn btn-soft" type="button" data-close>Отмена</button><button class="btn btn-soft" type="button" id="adj-clr">Очистить</button><button class="btn btn-primary" type="button" id="adj-ok">Применить</button></div></div>');
    $('adj-ok').onclick = function () { Admin.closeDlg(); send(true); };
    $('adj-clr').onclick = function () { Admin.closeDlg(); send(false); };
  }
  form.addEventListener('submit', function (e) { e.preventDefault(); qxSubmit(); });

  qxDate(); qxLines(); qxSum(D.errors && D.errors.length ? D.errors : null);
  if (D.errors && D.errors.length) Admin.guard.set(true);
})();
