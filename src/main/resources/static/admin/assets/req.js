/* Заявки на сырьё: «Общая рецептура» в списке и действия в карточке заявки (vRequests/vRequest из макета) —
   чек-лист сборки, факт оприхода с отклонением от нормы, правка состава (количество, замена, убрать, добавить),
   «сверх заявки», «Рассчитались», «Завершить», отмена с причиной. Каждое действие — обычная форма POST:
   сервер делает то же, что приложение, и возвращает на карточку с сообщением. */
(function () {
  'use strict';
  var $ = Admin.$, esc = Admin.esc, O = window.Ops, NB = O.NB;

  /* ================= список: общая рецептура новых заявок ================= */
  var MG = O.json('rq-merge');
  if (MG && $('rq-mg')) $('rq-mg').addEventListener('click', function () {
    var pool = MG.pool || [], ings = MG.ings || {}, sel = {};
    pool.forEach(function (r) { sel[r.id] = 1; });
    Admin.openDlg('<div class="dlg-in"><h2 id="dlg-h">Общая рецептура</h2>' +
      '<p class="muted">Сырьё новых заявок одной таблицей — склад собирает всё сразу. Это просмотр: в заявках ничего не меняется. «Хватает» — на общий итог, а не на каждую заявку отдельно.</p>' +
      (pool.length ? '<div class="chips mg-c" id="mg-c" role="group" aria-label="Какие заявки сложить">' + pool.map(function (r) {
        return '<button class="chip" type="button" data-no="' + r.id + '" aria-pressed="true">№ ' + r.id + ' «' + esc(r.title) + '» <span>' + O.qn(r.batches) + NB + 'зам.</span></button>';
      }).join('') + '</div><div class="tbl-w" id="mg-t"></div>' : '<p class="empty">Новых заявок нет — складывать нечего.</p>') +
      '<div class="dlg-acts"><button class="btn btn-primary" type="button" data-close>Готово</button></div></div>', 'is-wide');
    if (!pool.length) return;
    function draw() {
      var rs = pool.filter(function (r) { return sel[r.id]; }), tot = {}, by = {}, order = [];
      rs.forEach(function (r) {
        r.items.forEach(function (x) {
          var m = String(x[0]);
          if (!(m in tot)) { tot[m] = 0; by[m] = []; order.push(m); }
          tot[m] += x[1]; by[m].push('№ ' + r.id + ' — ' + O.qn(x[1]));
        });
      });
      if (!rs.length) { $('mg-t').innerHTML = '<p class="empty">Отметьте хотя бы одну заявку.</p>'; return; }
      var bad = order.filter(function (m) { return !ings[m].free && tot[m] > ings[m].stock + 1e-9; });
      $('mg-t').innerHTML = '<table class="tbl tbl--mg"><thead><tr><th>Сырьё</th><th class="r">Всего</th><th class="r">На складе</th><th>Хватает</th></tr></thead><tbody>' +
        order.map(function (m) {
          var mt = ings[m], ok = mt.free || tot[m] <= mt.stock + 1e-9;
          return '<tr' + (ok ? '' : ' class="is-short"') + '><td><b>' + esc(mt.name) + '</b>' + (rs.length > 1 ? '<small>' + by[m].join(' · ') + '</small>' : '') + '</td>' +
            '<td class="r num"><b>' + O.qu(tot[m], mt.unit) + '</b></td><td class="r num">' + (mt.free ? '—' : O.qu(mt.stock, mt.unit)) + '</td>' +
            '<td>' + (mt.free ? '<span class="muted">не учитывается</span>' : ok ? '<span class="ok-t">да</span>' : '<span class="no-t">нет, −' + O.qu(tot[m] - mt.stock, mt.unit) + '</span>') + '</td></tr>';
        }).join('') + '</tbody><tfoot><tr><td colspan="4">' + order.length + NB + O.plural(order.length, 'позиция', 'позиции', 'позиций') +
        (bad.length ? ' · не хватает: ' + bad.map(function (m) { return esc(ings[m].name); }).join(', ') : ' · на складе хватает всего') + '</td></tr></tfoot></table>';
    }
    draw();
    $('mg-c').addEventListener('click', function (e) {
      var b = e.target.closest('.chip'); if (!b) return;
      var no = b.getAttribute('data-no');
      sel[no] = !sel[no]; b.setAttribute('aria-pressed', String(!!sel[no]));
      draw();
    });
  });

  /* ================= карточка заявки ================= */
  var R = O.json('rq-page');
  if (!R) return;
  var base = '/admin/requests/' + R.id;
  var INGS = (R.ings || []).map(function (m) { return { key: String(m.id), label: m.name, unit: m.unit, stock: m.stock }; });
  var IM = {}; INGS.forEach(function (m) { IM[m.key] = m; });
  var PRODS = (R.prods || []).map(function (p) { return { key: String(p.id), label: p.name }; });
  function stockHint(k, full) { var m = IM[k]; return m ? (full ? 'На складе: ' : 'есть ') + O.qu(m.stock, m.unit) : ''; }
  function unitOf(k) { return IM[k] ? IM[k].unit : 'кг'; }

  /* ---- чек-лист сборки ---- */
  var all = $('qr-all'), go = $('qr-go'), raw = $('q-raw');
  if (raw && all) {
    var cks = raw.querySelectorAll('[data-ck]'), shortN = raw.querySelectorAll('tr.is-short').length;
    var sync = function () {
      var n = cks.length, c = 0;
      cks.forEach(function (cb) { if (cb.checked) c++; cb.closest('tr').classList.toggle('is-ck', cb.checked); });
      all.checked = c === n && n > 0;
      if (go) go.disabled = c !== n;
      var t = $('qr-cnt');
      t.textContent = c === n ? 'Всё отмечено — можно выдавать' : 'Отмечено ' + c + ' из ' + n + (shortN ? ' · красным — не хватает на складе' : '');
      t.className = shortN && c !== n ? 'pay-bad' : '';
    };
    raw.addEventListener('change', function (e) {
      if (e.target === all) cks.forEach(function (cb) { cb.checked = all.checked; });
      if (e.target === all || e.target.hasAttribute('data-ck')) sync();
    });
    sync();
    $('q-collect').addEventListener('submit', function () { Admin.guard.set(false); });
  }

  /* ---- оприход: отклонение от нормы ---- */
  document.querySelectorAll('[data-recv]').forEach(function (inp) {
    var id = inp.getAttribute('data-recv'), plan = +inp.getAttribute('data-plan') || 0;
    inp.addEventListener('input', function () {
      var v = this.value.replace(/\D/g, '').slice(0, 6); if (v !== this.value) this.value = v;
      var q = +v, d = q - plan, out = $('qd-' + id);
      this.classList.remove('is-bad');
      out.className = 'q-dev' + (v && d < 0 ? ' pay-bad' : '');
      out.textContent = !v ? '' : d < 0 ? 'Меньше нормы на ' + O.g3(-d) + NB + 'шт' : d > 0 ? 'На ' + O.g3(d) + NB + 'шт больше нормы' : 'Ровно по норме';
    });
    inp.addEventListener('keydown', function (e) { if (e.key === 'Enter') { e.preventDefault(); document.querySelector('[data-rok="' + id + '"]').click(); } });
  });
  document.querySelectorAll('[data-rok]').forEach(function (b) {
    b.addEventListener('click', function () {
      var id = b.getAttribute('data-rok'), inp = $('qi-' + id), q = +inp.value;
      if (!(q > 0)) { inp.classList.add('is-bad'); inp.focus(); var o = $('qd-' + id); o.className = 'q-dev pay-bad'; o.textContent = 'Введите, сколько штук пришло на склад.'; return; }
      O.post(base + '/lines/' + id + '/receive', { qty: q });
    });
  });
  document.querySelectorAll('[data-fix]').forEach(function (b) {
    b.addEventListener('click', function () {
      var id = b.getAttribute('data-fix');
      O.qtyDlg({ title: 'Исправить оприходованное', text: esc(b.getAttribute('data-name')) + '. Склад готовой продукции сдвинется на разницу, отчёт производства в 1С перепроведётся. Исправляет директор.',
        label: 'Факт', unit: 'шт', int: true, value: +b.getAttribute('data-q'), ok: 'Исправить',
        onOk: function (q) { O.post(base + '/lines/' + id + '/received', { qty: Math.round(q) }); } });
    });
  });

  /* ---- правка состава ---- */
  document.addEventListener('click', function (e) {
    var b = e.target.closest('[data-it]'); if (!b) return;
    var box = b.closest('.q-acts'), item = box.getAttribute('data-item'), name = box.getAttribute('data-name'), unit = box.getAttribute('data-unit') || 'кг',
      q = +box.getAttribute('data-q'), ing = box.getAttribute('data-ing'), a = b.getAttribute('data-it');
    if (a === 'edit') {
      var norm = R.norm && R.norm[ing] != null ? R.norm[ing] : null;
      O.qtyDlg({ title: 'Количество: ' + esc(name), text: R.issued ? 'Сырьё уже выдано в цех — разница сразу спишется со склада или вернётся на него, а цеху её нужно будет довыдать или забрать.'
          : norm != null ? 'Норма по рецептуре — ' + O.qu(norm, unit) + '.' : 'Этого сырья нет в рецептуре.',
        unit: unit, value: q,
        hint: function (v) { return v > 0 && Math.abs(v - q) > 1e-9 ? 'Было ' + O.qu(q, unit) + ' → станет ' + O.qu(v, unit) + ' (' + (v > q ? '+' : '−') + O.qu(Math.abs(v - q), unit) + ')' : ''; },
        onOk: function (v) { if (Math.abs(v - q) > 1e-9) O.post(base + '/items/' + item + '/substitute', { qty: O.numStr(v) }); } });
    } else if (a === 'swap') {
      O.pickDlg({ title: 'Заменить «' + esc(name) + '»', text: 'Например, кончилось на складе. В заявке останется пометка, что было раньше.' + (R.issued ? ' Прежнее сырьё вернётся на склад, новое спишется.' : ''),
        list: function () { return INGS.filter(function (o) { return o.key !== ing; }); }, extra: stockHint, unit: unitOf, q: q, ok: 'Заменить',
        onOk: function (k2, v) { O.post(base + '/items/' + item + '/substitute', { ingredient: k2, qty: O.numStr(v) }); } });
    } else if (a === 'del') {
      O.askDlg({ title: 'Убрать «' + esc(name) + '»?', text: 'Позиция уйдёт из заявки' + (R.issued ? '; выданное (' + O.qu(q, unit) + ') вернётся на склад — заберите его из цеха.' : '.'), ok: 'Убрать', danger: true,
        onOk: function () { O.post(base + '/items/' + item + '/remove'); } });
    }
  });

  /* ---- кнопки заявки ---- */
  document.addEventListener('click', function (e) {
    var b = e.target.closest('[data-qa]'); if (!b) return;
    var a = b.getAttribute('data-qa');
    if (a === 'add') {
      O.pickDlg({ title: 'Добавить сырьё', text: 'Понадобилось то, чего нет в рецептуре, или больше нормы. Если сырьё уже есть в заявке, количество прибавится.' + (R.issued ? ' Спишется со склада сразу.' : ''),
        list: function () { return INGS; }, extra: stockHint, unit: unitOf,
        onOk: function (k2, v) { O.post(base + '/items/add', { ingredient: k2, qty: O.numStr(v) }); } });
    } else if (a === 'extra') {
      O.pickDlg({ title: 'Оприходовать сверх заявки', text: 'Из того же замеса вышел товар, которого нет в заявке. Сырьё и рецептура не меняются.', label: 'Изделие', unit: 'шт', ok: 'Оприходовать',
        list: function () { return PRODS; },
        onOk: function (k2, v) { O.post(base + '/extra', { product: k2, qty: Math.round(v) }); } });
    } else if (a === 'settle') {
      O.askDlg({ title: 'Рассчитались?', text: 'Недостающее выдано в цех, лишнее вернули на склад. Текущий состав станет выданным, разница обнулится.', ok: 'Да, рассчитались',
        onOk: function () { O.post(base + '/settle'); } });
    } else if (a === 'done') {
      O.askDlg({ title: 'Завершить заявку № ' + R.id + '?', text: 'Требование-накладная будет проведена в 1С. Дальнейшие правки состава сырья туда автоматически больше не попадут.', ok: 'Завершить',
        onOk: function () { O.post(base + '/complete'); } });
    } else if (a === 'undone') {
      O.askDlg({ title: 'Отменить завершение?', text: 'Проведение требования-накладной в 1С будет отменено. Состав сырья снова можно будет редактировать.', ok: 'Отменить завершение', danger: true, cancel: 'Нет',
        onOk: function () { O.post(base + '/reopen'); } });
    } else if (a === 'off') {
      O.askDlg({ title: 'Отменить заявку № ' + R.id + '?', note: 'Например: перенесли — нет мёда', noteLabel: 'Причина',
        text: (R.issued ? 'Выданное сырьё вернётся на склад сырья, оприходованная продукция уйдёт со склада готовой продукции. ' : 'Сырьё ещё не выдавали. ') + 'Документы в 1С распроведутся и пометятся на удаление. Заявка останется в списке как отменённая.',
        ok: 'Отменить заявку', danger: true, cancel: 'Не отменять',
        onOk: function (note) { O.post(base + '/cancel', { reason: note }); } });
    } else if (a === 'del') {
      O.askDlg({ title: 'Удалить заявку № ' + R.id + '?', text: 'Отменённая заявка удалится только из CRM, в 1С ничего не отправляется.', ok: 'Удалить', danger: true,
        onOk: function () { O.post(base + '/delete'); } });
    } else if (a === 'sync') {
      O.post(base + '/sync-1c');
    }
  });
})();
