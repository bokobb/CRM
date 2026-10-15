/* Склад сырья и склад готовой продукции (vMaterials/vStock из макета): документ «Поступление»/«Списание» в окне —
   несколько позиций, остаток до и после (у товара — и сколько в заказах), причина; строки журнала «13 позиций»
   раскрываются; минимальный остаток в карточке сырья. Подписи, адрес и штучный ввод — из JSON страницы (#mt-doc).
   Документ уходит обычной формой POST — сервер проводит его как приложение (StockService) и отправляет в 1С. */
(function () {
  'use strict';
  var $ = Admin.$, esc = Admin.esc, icon = Admin.icon, O = window.Ops, NB = O.NB;
  var D = O.json('mt-doc') || {};
  var ITEMS = (D.items || []).map(function (m) { return { key: String(m.id), label: m.name, unit: m.unit, stock: +m.stock || 0, res: +m.res || 0 }; });
  var NOUN = D.noun || 'Сырьё', INT = !!D.int;
  var IM = {}; ITEMS.forEach(function (m) { IM[m.key] = m; });

  /* ---- журнал: раскрыть строку документа ---- */
  document.addEventListener('click', function (e) {
    var b = e.target.closest('[data-mvg]'); if (!b) return;
    var k = b.getAttribute('data-mvg'), open = b.getAttribute('aria-expanded') !== 'true';
    b.setAttribute('aria-expanded', String(open));
    b.closest('tr').classList.toggle('is-open', open);
    document.querySelectorAll('tr[data-of="' + k + '"]').forEach(function (tr) { tr.hidden = !open; });
  });

  /* ---- сверка с 1С ---- */
  var sb = document.querySelector('[data-sync]');
  if (sb) sb.addEventListener('click', function () {
    O.askDlg({ title: 'Сверить с 1С?', text: 'Остатки сырья и товаров обновятся из 1С. Обычно сверка идёт сама каждые 5 минут, пока в CRM работают.', ok: 'Сверить',
      onOk: function () { Admin.guard.set(false); $('mt-sync').submit(); } });
  });

  /* ---- минимальный остаток ---- */
  var mv = $('ms-v'), ms = $('ms-save');
  if (mv && ms) {
    var was = +mv.getAttribute('data-was') || 0;
    mv.addEventListener('input', function () {
      var v = this.value.replace(/[^0-9.,]/g, '').slice(0, 8); if (v !== this.value) this.value = v;
      ms.hidden = Math.abs((O.numIn(v) || 0) - was) < 1e-9;
    });
    $('ms-form').addEventListener('submit', function () { Admin.guard.set(false); });
  }

  /* ---- документ «Поступление» / «Списание» ---- */
  function docDlg(type, preset) {
    var S = { type: type, rows: [{ id: 1, m: preset || '', text: preset && IM[preset] ? IM[preset].label : '', q: '' }], seq: 1, tried: false };
    function rowOf(id) { for (var i = 0; i < S.rows.length; i++) if (S.rows[i].id === id) return S.rows[i]; return null; }
    Admin.openDlg('<form class="dlg-in" id="dd" novalidate><h2 id="dlg-h"></h2>' +
      '<div class="radios" role="radiogroup" aria-label="Вид документа" id="dd-type"><label class="radio"><input type="radio" name="dt" value="in"' + (type === 'in' ? ' checked' : '') + (D.canIn === false ? ' disabled' : '') + '>Поступление</label>' +
      '<label class="radio"><input type="radio" name="dt" value="off"' + (type === 'off' ? ' checked' : '') + '>Списание</label></div>' +
      '<div class="fgrid"><label class="field"><span>Дата документа</span><input class="inp" type="date" id="dd-date" value="' + D.today + '" max="' + D.today + '"></label>' +
      '<label class="field"><span id="dd-wl">Причина</span><select class="sel" id="dd-why"></select></label>' +
      '<label class="field wide"><span>Комментарий</span><input class="inp" id="dd-note" maxlength="200" placeholder="Необязательно"></label></div>' +
      '<p class="hint" id="dd-hint"></p>' +
      '<div class="dlines" id="dd-lines"></div><button class="add" type="button" id="dd-add">' + icon('plus') + '<span>Добавить позицию</span></button>' +
      '<p class="errs" id="dd-e" hidden></p>' +
      '<div class="dlg-acts"><button class="btn btn-soft" type="button" data-close>Отмена</button><button class="btn btn-primary" type="submit">Провести</button></div></form>', 'is-wide');
    function head() {
      var off = S.type === 'off';
      $('dlg-h').textContent = off ? (D.offTitle || 'Списание сырья') : (D.inTitle || 'Поступление сырья');
      $('dd-why').innerHTML = (off ? D.offWhy : D.inWhy).map(function (w) { return '<option>' + esc(w) + '</option>'; }).join('');
      $('dd-hint').innerHTML = off ? esc(D.offHint || 'Списанное уходит со склада сразу; документ уйдёт в 1С. Больше, чем есть на складе, списать нельзя.')
        : D.inHint ? esc(D.inHint) : 'Закупку у поставщика оформляйте в <a class="link" href="/admin/purchases/new">«Покупках»</a> — там цена и фото накладной. Здесь — приход без покупки.';
    }
    function after(m, q) { return m && q > 0 ? O.r3(m.stock + (S.type === 'off' ? -q : q)) : null; }
    function stockText(m, a) { return m ? 'остаток ' + O.qu(m.stock, m.unit) + (a != null ? ' → <b' + (a < 0 ? ' class="pay-bad"' : '') + '>' + O.qu(a, m.unit) + '</b>' : '') + (m.res > 0 ? ' · в заказах ' + O.qn(m.res) : '') : ''; }
    function lines() {
      $('dd-lines').innerHTML = S.rows.map(function (r) {
        var m = r.m ? IM[r.m] : null, q = O.numIn(r.q), off = S.type === 'off', a = after(m, q);
        return '<div class="dl" data-id="' + r.id + '"><div class="dl-n"><input class="inp' + (S.tried && !r.m ? ' is-bad' : '') + '" aria-label="' + NOUN + '" placeholder="' + NOUN + '" autocomplete="off" spellcheck="false" value="' + esc(r.text) + '"></div>' +
          '<span class="qin"><input class="inp' + (S.tried && (!(q > 0) || (off && m && q > m.stock + 1e-9)) ? ' is-bad' : '') + '" aria-label="Количество" inputmode="' + (INT ? 'numeric' : 'decimal') + '" autocomplete="off" placeholder="0" value="' + esc(r.q) + '"><i>' + esc(m ? m.unit : INT ? 'шт' : 'кг') + '</i></span>' +
          '<span class="dl-s">' + stockText(m, a) + '</span>' +
          '<button class="rx-del" type="button" aria-label="Удалить строку">' + icon('trash') + '</button></div>';
      }).join('');
      $('dd-lines').querySelectorAll('.dl').forEach(function (el) {
        var r = rowOf(+el.getAttribute('data-id')), inp = el.querySelector('.dl-n input');
        O.acBind(inp, function () { return ITEMS; }, function (k) {
          r.m = k; r.text = IM[k].label; lines();
          var qi = $('dd-lines').querySelector('.dl[data-id="' + r.id + '"] .qin input'); qi.focus(); qi.select();
        }, function (k) { return 'есть ' + O.qu(IM[k].stock, IM[k].unit); }, function () { return el; });
      });
    }
    head(); lines();
    var box = $('dd-lines');
    box.addEventListener('input', function (e) {
      var el = e.target.closest('.dl'); if (!el) return;
      var r = rowOf(+el.getAttribute('data-id'));
      if (e.target.closest('.dl-n')) { r.text = e.target.value; r.m = O.exact(ITEMS, r.text); return; }
      var v = e.target.value.replace(INT ? /\D/g : /[^0-9.,]/g, '').slice(0, 9);
      if (v !== e.target.value) e.target.value = v;
      r.q = v;
      var m = r.m ? IM[r.m] : null, a = after(m, O.numIn(v));
      el.querySelector('.dl-s').innerHTML = stockText(m, a);
    });
    box.addEventListener('click', function (e) {
      var b = e.target.closest('.rx-del'); if (!b) return;
      var r = rowOf(+b.closest('.dl').getAttribute('data-id'));
      S.rows.splice(S.rows.indexOf(r), 1);
      if (!S.rows.length) S.rows.push({ id: ++S.seq, m: '', text: '', q: '' });
      lines();
    });
    $('dd-add').onclick = function () {
      S.rows.push({ id: ++S.seq, m: '', text: '', q: '' }); lines();
      var all = $('dd-lines').querySelectorAll('.dl-n input'); all[all.length - 1].focus();
    };
    $('dd-type').addEventListener('change', function (e) { S.type = e.target.value; head(); lines(); });
    $('dd').addEventListener('submit', function (e) {
      e.preventDefault();
      S.tried = true;
      var off = S.type === 'off', errs = [], filled = S.rows.filter(function (r) { return r.m || r.text || r.q; }), seen = {};
      if (!filled.length) errs.push('Добавьте хотя бы одну позицию.');
      filled.forEach(function (r) {
        var q = O.numIn(r.q), m = IM[r.m];
        if (!r.m) errs.push('Выберите ' + NOUN.toLowerCase() + ' из списка' + (r.text ? ': «' + r.text + '» не ' + (INT ? 'нашёлся' : 'нашлось') : '') + '.');
        else if (!(q > 0)) errs.push(m.label + ': введите количество.');
        else if (off && q > m.stock + 1e-9) errs.push(m.label + ': на складе только ' + O.qu(m.stock, m.unit) + '.');
        if (r.m && seen[r.m]) errs.push(m.label + ' — дважды в документе.');
        if (r.m) seen[r.m] = 1;
      });
      if (!/^\d{4}-\d\d-\d\d$/.test($('dd-date').value) || $('dd-date').value > D.today) errs.push('Дата документа — не в будущем.');
      if (errs.length) {
        lines();
        $('dd-e').innerHTML = errs.filter(function (x, i) { return errs.indexOf(x) === i; }).map(function (x) { return '<span>' + esc(x) + '</span>'; }).join('');
        $('dd-e').hidden = false; return;
      }
      var note = $('dd-note').value.trim(), why = $('dd-why').value;
      Admin.closeDlg();
      O.post(D.url || '/admin/materials/doc', {
        type: off ? 'off' : 'in', date: $('dd-date').value, reason: why + (note ? ' — ' + note : ''),
        item: filled.map(function (r) { return r.m; }), qty: filled.map(function (r) { return O.numStr(O.numIn(r.q)); }),
        back: location.pathname + location.search
      });
    });
  }
  document.addEventListener('click', function (e) {
    var b = e.target.closest('[data-doc]'); if (!b) return;
    docDlg(b.getAttribute('data-doc'), D.preset != null ? String(D.preset) : '');
  });
})();
