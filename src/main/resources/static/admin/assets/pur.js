/* Новая покупка и изменение (vPForm из макета): поставщик и дата, строки «сырьё — количество — сумма по
   накладной», цена за единицу считается из суммы и сравнивается с прошлой покупкой (±10 % — проверьте сумму),
   итог справа. Новый поставщик — в окне: создастся вместе с покупкой. Данные — JSON #px-data, отправка —
   обычная форма: counterparty или newCounterparty, строки — ingredient / qty / total. */
(function () {
  'use strict';
  var $ = Admin.$, esc = Admin.esc, icon = Admin.icon, O = window.Ops, NB = O.NB;
  var D = O.json('px-data') || {};
  var SUPS = {}, SUPLIST = D.sups || [];
  SUPLIST.forEach(function (s) { SUPS[String(s.id)] = s; });
  var MAT = {}, MATLIST = [];
  (D.ings || []).forEach(function (m) { MAT[String(m.id)] = m; MATLIST.push({ key: String(m.id), label: m.name }); });
  var MON3 = ['янв', 'фев', 'мар', 'апр', 'мая', 'июн', 'июл', 'авг', 'сен', 'окт', 'ноя', 'дек'];
  function dm(s) { return +s.slice(8, 10) + NB + MON3[+s.slice(5, 7) - 1]; }
  function money(x) { return O.money(x); }
  function fix(x, d) { return O.dec(x, d); }

  var PX = { no: D.no, sup: D.sup != null ? String(D.sup) : '', newSup: D.newSup || '', date: D.date || D.today, seq: 0, rows: [], tried: !!(D.errors && D.errors.length) };
  function pRow(m, q, s) {
    m = m != null ? String(m) : '';
    return { id: ++PX.seq, m: MAT[m] ? m : '', text: MAT[m] ? MAT[m].name : '', q: q != null && q !== '' ? O.numStr(+q) : '', s: s != null && s !== '' ? String(Math.round(+s)) : '' };
  }
  (D.rows || []).forEach(function (r) { PX.rows.push(pRow(r.m, r.q, r.s)); });
  if (!PX.rows.length) PX.rows.push(pRow());
  if (!PX.sup && !PX.newSup && D.rows && D.rows[0] && D.rows[0].sup != null) PX.sup = String(D.rows[0].sup);
  function rowOf(id) { for (var i = 0; i < PX.rows.length; i++) if (PX.rows[i].id === id) return PX.rows[i]; return null; }
  function dirty() { Admin.guard.set(true); }
  function supName() { return PX.newSup ? PX.newSup : SUPS[PX.sup] ? SUPS[PX.sup].name : ''; }

  /* ---- поставщик ---- */
  function pxSup() {
    var sel = $('px-sup');
    sel.innerHTML = '<option value="">— выберите —</option>' + SUPLIST.map(function (s) { return '<option value="' + s.id + '"' + (PX.sup === String(s.id) && !PX.newSup ? ' selected' : '') + '>' + esc(s.name) + '</option>'; }).join('') +
      (PX.newSup ? '<option value="new" selected>' + esc(PX.newSup) + ' (новый)</option>' : '') + '<option value="+">+ Новый поставщик…</option>';
    sel.classList.toggle('is-bad', PX.tried && !PX.sup && !PX.newSup);
    var s = SUPS[PX.sup], mine = MATLIST.filter(function (o) { return String(MAT[o.key].sup) === PX.sup; }).slice(0, 4).map(function (o) { return o.label.toLowerCase(); });
    $('px-sh').innerHTML = PX.newSup ? 'Новый поставщик создастся вместе с покупкой — и в 1С тоже.'
      : s ? (mine.length ? 'Обычно привозит: ' + esc(mine.join(', ')) : 'Покупок у него ещё не было') + (s.oneC ? '' : ' · <span class="pay-bad">не связан с 1С — покупка не уйдёт в 1С</span>') : '';
  }
  $('px-sup').addEventListener('change', function () {
    if (this.value === '+') { supDlg(); return; }
    if (this.value === 'new') return;
    PX.sup = this.value; PX.newSup = ''; dirty(); pxSup(); pxSum();
  });
  function supDlg() {
    Admin.openDlg('<form class="dlg-in" id="sd"><h2 id="dlg-h">Новый поставщик</h2><div class="fgrid">' +
      '<label class="field wide"><span>Название</span><input class="inp" id="sd-n" maxlength="200" placeholder="Например: ТОО «…» или ИП Фамилия"></label></div>' +
      '<p class="hint">Поставщик создастся вместе с покупкой — сразу и в 1С, чтобы покупки уходили туда «Поступлением товаров».</p><p class="errs" id="sd-e" hidden></p>' +
      '<div class="dlg-acts"><button class="btn btn-soft" type="button" data-close>Отмена</button><button class="btn btn-primary" type="submit">Добавить</button></div></form>');
    $('sd-n').focus();
    $('sd').addEventListener('submit', function (e) {
      e.preventDefault();
      var n = $('sd-n').value.trim();
      if (!n) { $('sd-e').textContent = 'Напишите название.'; $('sd-e').hidden = false; $('sd-n').focus(); return; }
      var same = SUPLIST.filter(function (s) { return O.norm(s.name) === O.norm(n); })[0];
      if (same) { $('sd-e').textContent = 'Такой поставщик уже есть — выберите его в списке.'; $('sd-e').hidden = false; return; }
      Admin.closeDlg();
      PX.newSup = n; PX.sup = ''; dirty(); pxSup(); pxSum();
    });
    pxSup();
  }

  /* ---- дата ---- */
  var date = $('px-date');
  date.value = PX.date; date.max = D.today;
  date.addEventListener('change', function () { if (/^\d{4}-\d\d-\d\d$/.test(this.value)) { PX.date = this.value; dirty(); pxSum(); } });

  /* ---- строки ---- */
  function pxPrice(r) {
    var q = O.numIn(r.q), s = O.numIn(r.s), mt = r.m ? MAT[r.m] : null;
    if (!mt) return '';
    if (!(q > 0 && s > 0)) return mt.price ? 'прошлая цена ' + money(mt.price) + '/' + esc(mt.unit) : '';
    var u = s / q, d = mt.price ? (u - mt.price) / mt.price * 100 : null;
    return '= <b>' + money(u) + '</b>/' + esc(mt.unit) + (d == null ? '' : Math.abs(d) < 0.5 ? ' · как в прошлый раз' : ' · <span class="' + (Math.abs(d) >= 10 ? 'pay-bad' : '') + '">' + (d > 0 ? '+' : '−') + fix(Math.abs(d), 1) + NB + '%</span> к прошлой');
  }
  function choices() {
    var mine = PX.sup ? MATLIST.filter(function (o) { return String(MAT[o.key].sup) === PX.sup; }) : [];
    return mine.concat(MATLIST.filter(function (o) { return mine.indexOf(o) < 0; }));
  }
  function pxLines() {
    $('px-lines').innerHTML = PX.rows.map(function (r) {
      var mt = r.m ? MAT[r.m] : null;
      return '<div class="pl" data-id="' + r.id + '"><div class="pl-n"><span class="lbl" id="pn-' + r.id + '">Сырьё</span><input class="inp' + (PX.tried && !r.m ? ' is-bad' : '') + '" aria-labelledby="pn-' + r.id + '" placeholder="Начните вводить название" autocomplete="off" spellcheck="false" value="' + esc(r.text) + '"></div>' +
        '<label class="pl-q"><span class="lbl">Количество</span><span class="qin"><input class="inp' + (PX.tried && r.m && !(O.numIn(r.q) > 0) ? ' is-bad' : '') + '" data-f="q" inputmode="decimal" autocomplete="off" placeholder="0" value="' + esc(r.q) + '"><i>' + esc(mt ? mt.unit : 'кг') + '</i></span></label>' +
        '<label class="pl-s"><span class="lbl">Сумма</span><span class="qin"><input class="inp' + (PX.tried && r.m && !(O.numIn(r.s) > 0) ? ' is-bad' : '') + '" data-f="s" inputmode="numeric" autocomplete="off" placeholder="0" value="' + esc(r.s) + '"><i>₸</i></span></label>' +
        '<span class="pl-u">' + pxPrice(r) + '</span>' +
        '<button class="rx-del" type="button" aria-label="Удалить строку' + (mt ? ': ' + esc(mt.name) : '') + '">' + icon('trash') + '</button></div>';
    }).join('');
    $('px-lines').querySelectorAll('.pl').forEach(function (el) {
      var r = rowOf(+el.getAttribute('data-id')), inp = el.querySelector('.pl-n input');
      O.acBind(inp, choices, function (k) {
        r.m = k; r.text = MAT[k].name; dirty();
        if (!PX.sup && !PX.newSup && MAT[k].sup != null && SUPS[String(MAT[k].sup)]) { PX.sup = String(MAT[k].sup); pxSup(); }
        pxLines(); pxSum();
        var qi = $('px-lines').querySelector('.pl[data-id="' + r.id + '"] [data-f="q"]'); qi.focus(); qi.select();
      }, function (k) { var m = MAT[k], s = SUPS[String(m.sup)]; return (m.price ? money(m.price) + '/' + esc(m.unit) : '') + (s && String(m.sup) !== PX.sup ? ' · ' + esc(s.name) : ''); }, function () { return el; });
    });
  }
  var box = $('px-lines');
  box.addEventListener('input', function (e) {
    var el = e.target.closest('.pl'); if (!el) return;
    var r = rowOf(+el.getAttribute('data-id')), f = e.target.getAttribute('data-f');
    dirty();
    if (!f) { r.text = e.target.value; r.m = O.exact(MATLIST, r.text); pxSum(); return; }
    var v = e.target.value.replace(f === 'q' ? /[^0-9.,]/g : /\D/g, '').slice(0, 9);
    if (v !== e.target.value) e.target.value = v;
    r[f] = v;
    el.querySelector('.pl-u').innerHTML = pxPrice(r);
    pxSum();
  });
  box.addEventListener('click', function (e) {
    var b = e.target.closest('.rx-del'); if (!b) return;
    var r = rowOf(+b.closest('.pl').getAttribute('data-id')), i = PX.rows.indexOf(r);
    PX.rows.splice(i, 1);
    if (!PX.rows.length) PX.rows.push(pRow());
    dirty(); pxLines(); pxSum();
    var next = box.querySelectorAll('.pl-n input')[Math.min(i, PX.rows.length - 1)]; if (next) next.focus();
  });
  $('px-add').onclick = function () {
    PX.rows.push(pRow()); dirty(); pxLines(); pxSum();
    var all = $('px-lines').querySelectorAll('.pl-n input'); all[all.length - 1].focus();
  };

  /* ---- проверки и итог ---- */
  function pxErrors() {
    var e = [], filled = PX.rows.filter(function (r) { return r.m || r.text || r.q || r.s; }), seen = {};
    if (!PX.sup && !PX.newSup) e.push('Выберите поставщика.');
    if (!filled.length) e.push('Добавьте сырьё.');
    filled.forEach(function (r) {
      if (!r.m) e.push('Выберите сырьё из списка' + (r.text ? ': «' + r.text + '» не нашлось' : '') + '.');
      else if (!(O.numIn(r.q) > 0)) e.push(MAT[r.m].name + ': количество?');
      else if (!(O.numIn(r.s) > 0)) e.push(MAT[r.m].name + ': сумма по накладной?');
      if (r.m && seen[r.m]) e.push(MAT[r.m].name + ' — дважды. Сложите в одну строку.');
      if (r.m) seen[r.m] = 1;
    });
    if (PX.date > D.today) e.push('Дата покупки — не в будущем.');
    return e.filter(function (x, i) { return e.indexOf(x) === i; });
  }
  function pxSum(errs) {
    var rows = PX.rows.filter(function (r) { return r.m && O.numIn(r.q) > 0 && O.numIn(r.s) > 0; }), sum = 0, warn = [];
    rows.forEach(function (r) {
      sum += O.numIn(r.s);
      var mt = MAT[r.m], u = O.numIn(r.s) / O.numIn(r.q);
      if (mt.price && Math.abs(u - mt.price) / mt.price >= 0.1) warn.push(mt.name + ': ' + money(u) + ' за ' + mt.unit + ' — ' + (u > mt.price ? 'дороже' : 'дешевле') + ' прошлой цены ' + money(mt.price) + ' на ' + fix(Math.abs(u - mt.price) / mt.price * 100, 0) + NB + '%. Проверьте сумму.');
    });
    if (PX.tried && !errs) errs = pxErrors();
    date.classList.toggle('is-bad', PX.tried && PX.date > D.today);
    $('px-sum').innerHTML = '<h3>Итог</h3><dl><div><dt>Поставщик</dt><dd>' + (supName() ? esc(supName()) : '—') + '</dd></div><div><dt>Позиций</dt><dd>' + rows.length + '</dd></div>' +
      '<div><dt>Дата</dt><dd>' + dm(PX.date) + '</dd></div></dl><div class="of-tot"><span>Сумма</span><b>' + money(sum) + '</b></div>' +
      (warn.length ? '<ul class="warns">' + warn.map(function (w) { return '<li>' + icon('alert') + '<span>' + esc(w) + '</span></li>'; }).join('') + '</ul>' : '') +
      (errs && errs.length ? '<div class="errs" role="alert">' + errs.map(function (x) { return '<span>' + esc(x) + '</span>'; }).join('') + '</div>' : '') +
      '<button class="btn btn-primary" type="submit" id="px-go">' + (PX.no ? 'Сохранить' : 'Оформить покупку') + '</button>' +
      '<p class="hint">Цена за единицу считается из суммы — её подхватят техкарта и себестоимость. Фото накладной можно прикрепить после.</p>';
  }

  /* ---- отправка ---- */
  var form = $('px-form');
  form.addEventListener('submit', function (e) {
    e.preventDefault();
    PX.tried = true;
    var errs = pxErrors();
    if (errs.length) {
      pxSup(); pxLines(); pxSum(errs);
      var bad = document.querySelector('.of .is-bad') || document.querySelector('#px-sum .errs');
      if (bad) { bad.scrollIntoView({ block: 'center', behavior: 'smooth' }); if (bad.focus) bad.focus({ preventScroll: true }); }
      return;
    }
    var h = PX.newSup ? '<input type="hidden" name="newCounterparty" value="' + esc(PX.newSup) + '">' : '<input type="hidden" name="counterparty" value="' + esc(PX.sup) + '">';
    PX.rows.filter(function (r) { return r.m; }).forEach(function (r) {
      h += '<input type="hidden" name="ingredient" value="' + esc(r.m) + '"><input type="hidden" name="qty" value="' + esc(String(O.r3(O.numIn(r.q)))) + '">' +
        '<input type="hidden" name="total" value="' + esc(String(Math.round(O.numIn(r.s)))) + '">';
    });
    $('px-hidden').innerHTML = h;
    Admin.guard.set(false);
    form.submit();
  });

  pxSup(); pxLines(); pxSum(D.errors && D.errors.length ? D.errors : null);
  if ((D.rows || []).length && !D.no) Admin.guard.set(true);
})();
