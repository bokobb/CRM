/* Товары (vProducts/vProduct/vPrForm из макета): «Корпуса из 1С» в каталоге, окно «Скрыть с витрины» в карточке
   (1 месяц, 2 месяца, до даты, пока не вернут), форма товара — проверки как на сервере, расчёт объёма по размерам,
   превью карточки и накладной «Объём и масса», эталон группы. Всё уходит обычной формой POST. */
(function () {
  'use strict';
  var $ = Admin.$, esc = Admin.esc, O = window.Ops, NB = O.NB, numIn = O.numIn;

  /* ---- каталог: корпуса из 1С ---- */
  var c1 = $('pc-1c');
  if (c1) c1.addEventListener('click', function () {
    O.askDlg({ title: 'Корпуса из 1С', ok: 'Определить',
      text: 'Определить подразделение (Корпус А, B, С, D) для каждого товара по документам «Отчёт производства за смену», заведённым в 1С вручную? У товара с такой историей корпус заменится на самый частый в этих документах.',
      onOk: function () { Admin.guard.set(false); $('pc-1c-form').submit(); } });
  });

  /* ---- карточка: скрыть с витрины ---- */
  var hb = $('pc-hide');
  if (hb) hb.addEventListener('click', function () {
    var d = hb.dataset, m1 = d.m1, m2 = d.m2, today = d.today;
    Admin.openDlg('<form class="dlg-in" id="hd" novalidate><h2 id="dlg-h">Скрыть с витрины</h2><p class="muted">«' + esc(d.name) + '» пропадёт с сайта и из заказов WhatsApp. Сотрудники продолжат его видеть и могут продать вручную.</p>' +
      '<div class="radios radios--col" role="radiogroup" aria-label="На сколько скрыть">' +
      '<label class="radio"><input type="radio" name="hw" value="1m" checked>На 1 месяц — по ' + esc(d.m1t) + '</label>' +
      '<label class="radio"><input type="radio" name="hw" value="2m">На 2 месяца — по ' + esc(d.m2t) + '</label>' +
      '<label class="radio"><input type="radio" name="hw" value="date">До выбранной даты</label>' +
      '<label class="field hd-d" id="hd-dw" hidden><span>Скрыть по</span><input class="inp" type="date" id="hd-d" min="' + today + '" value="' + m2 + '"></label>' +
      '<label class="radio"><input type="radio" name="hw" value="forever">Пока не верну вручную</label></div>' +
      '<p class="errs" id="hd-e" hidden></p>' +
      '<div class="dlg-acts"><button class="btn btn-soft" type="button" data-close>Отмена</button><button class="btn btn-primary" type="submit">Скрыть</button></div></form>');
    $('hd').addEventListener('change', function () {
      var v = $('hd').querySelector('input[name="hw"]:checked').value;
      $('hd-dw').hidden = v !== 'date';
      if (v === 'date') $('hd-d').focus();
    });
    $('hd').addEventListener('submit', function (e) {
      e.preventDefault();
      var v = $('hd').querySelector('input[name="hw"]:checked').value, until = v === '1m' ? m1 : v === '2m' ? m2 : v === 'date' ? $('hd-d').value : '';
      if (v === 'date' && (!/^\d{4}-\d\d-\d\d$/.test(until) || until < today)) {
        $('hd-e').textContent = 'Выберите дату не раньше сегодняшней.'; $('hd-e').hidden = false; $('hd-d').classList.add('is-bad'); $('hd-d').focus(); return;
      }
      Admin.closeDlg();
      O.post('/admin/products/' + d.id + '/visibility', { hidden: 'true', until: until });
    });
  });

  /* ---- форма товара ---- */
  var form = $('pf');
  if (!form) return;
  var D = O.json('pf-data') || {}, tried = !!D.tried;
  var names = D.names || [], refs = D.refs || {};
  function val(id) { return $('pf-' + id).value.trim(); }
  function num(id) { return numIn(val(id)); }
  function boxOfName(name) { var m = /\((\d+)(?:\s*\/\s*\d+)?\s*шт\.?\)/.exec(name || ''); return m ? +m[1] : null; }
  function semi() { return $('pf-semi').checked; }
  function group() { var g = $('pf-grp').value; return g === '__new' ? val('gnew') : g; }
  function errors() {
    var e = {}, nm = val('name').toLowerCase(), nb = boxOfName(val('name'));
    if (!nm) e.name = 'Введите название.';
    else if (names.indexOf(nm) >= 0) e.name = 'Такой товар уже есть.';
    if (!group()) { e.grp = $('pf-grp').value === '__new' ? 'Введите название новой группы производства.' : 'Выберите группу производства.'; if ($('pf-grp').value === '__new') e.gnew = e.grp; }
    if (!semi()) {
      if (!(num('price') > 0)) e.price = 'Цена больше нуля.';
      if (val('g') && !(num('g') > 0 && num('g') <= 50)) e.g = 'Вес — от 0,001 до 50 кг.';
      if (val('box') && !(num('box') >= 1 && num('box') % 1 === 0)) e.box = 'Целое число штук.';
      if (!val('box') && !nb) e.box = 'В названии нет «(N шт)» — укажите, сколько штук в коробке.';
      if (val('vol') && !(num('vol') > 0 && num('vol') <= 2)) e.vol = 'Объём — от 0,001 до 2 м³.';
      if (val('box2') && !(num('box2') >= 1 && num('box2') % 1 === 0)) e.box2 = 'Целое число штук.';
      if (val('vol2') && !(num('vol2') > 0 && num('vol2') <= 2)) e.vol2 = 'Объём — от 0,001 до 2 м³.';
      if (!!val('box2') !== !!val('vol2') && !e.box2 && !e.vol2) e[val('box2') ? 'vol2' : 'box2'] = 'Для второй коробки нужны и штуки, и объём.';
    }
    if (val('netG') && !(num('netG') > 0 && num('netG') <= 20000)) e.netG = 'Вес — от 1 до 20 000 г.';
    if ($('pf-mref').checked && !val('netG')) e.netG = 'Эталону нужен вес нетто.';
    return e;
  }
  function plural(n, a, b, c) { return O.plural(n, a, b, c); }
  function paint() {
    var e = errors(), s = semi(), nb = boxOfName(val('name')), box = val('box') ? num('box') : nb, g = num('g'), vol = num('vol'), price = num('price');
    ['name', 'grp', 'gnew', 'price', 'g', 'box', 'vol', 'box2', 'vol2', 'netG'].forEach(function (f) {
      var el = $('pf-' + f), bad = !!(tried && e[f]);
      el.classList.toggle('is-bad', bad); el.setAttribute('aria-invalid', String(bad));
    });
    $('pf-box').placeholder = nb ? 'по названию: ' + nb : 'например 12';
    $('pf-pack').hidden = s; $('pf-w-price').hidden = s; $('pf-w-cat').hidden = s;
    $('pf-w-gnew').hidden = $('pf-grp').value !== '__new';
    var ref = refs[group()];
    $('pf-mrefh').textContent = 'в заявке «не хватает N кг» переведётся в «≈ M шт этого товара»' + (ref ? '. Сейчас эталон группы — ' + ref + ($('pf-mref').checked ? ', он перестанет им быть' : '') : '');
    var msgs = Object.keys(e).map(function (f) { return e[f]; }).concat(D.saveError ? [D.saveError] : []).filter(function (x, i, a) { return a.indexOf(x) === i; });
    $('pf-e').hidden = !(tried && msgs.length);
    if (tried) $('pf-e').innerHTML = msgs.map(function (x) { return '<span>' + esc(x) + '</span>'; }).join('');
    var cat = val('cat'), name = val('name') || 'Новый товар';
    var html = '<div class="pf-pv">' + (D.photo ? '<img src="' + esc(D.photo) + '" alt="">' : '<span class="th-none">' + Admin.icon('cookie') + '</span>') +
      '<p><b>' + esc(name) + '</b><small>' + esc(group() || 'группа не выбрана') + (s ? ' · полуфабрикат' : cat ? ' · ' + esc(cat) : '') + '</small></p></div>';
    if (s) html += '<p class="hint">Полуфабрикат не продаётся: цены, упаковки и витрины нет. Рецептура и заявки на сырьё — как у товара.</p>';
    else {
      var n30 = box ? Math.ceil(30 / box - 1e-9) : 0;
      html += '<dl class="sum-k"><div><dt>Цена</dt><dd>' + (price > 0 ? O.money(price) : '—') + '</dd></div>' +
        '<div><dt>Коробка</dt><dd>' + (box ? O.g3(box) + NB + 'шт' : '—') + '</dd></div>' +
        '<div><dt>Коробка весит</dt><dd>' + (box && g > 0 ? O.dec(box * g, 2) + NB + 'кг' : '—') + '</dd></div>' +
        '<div><dt>Объём коробки</dt><dd' + (vol > 0 ? '' : ' class="pay-bad"') + '>' + (vol > 0 ? O.dec(vol, 3) + NB + 'м³' : 'не задан') + '</dd></div>' +
        '<div><dt>Коробка стоит</dt><dd>' + (box && price > 0 ? O.money(box * price) : '—') + '</dd></div></dl>' +
        '<p class="hint">Пример накладной «Объём и масса»: 30 шт → ' + (box ? n30 + NB + plural(n30, 'коробка', 'коробки', 'коробок') : '? коробок') +
        (vol > 0 && box ? ', ' + O.dec(n30 * vol, 3) + NB + 'м³' : '') + (g > 0 ? ', ' + O.dec(30 * g, 2) + NB + 'кг' : '') + '.</p>';
    }
    $('pf-prev').innerHTML = html;
  }
  paint();
  form.addEventListener('input', function (e) {
    var id = e.target.id;
    if (/^pf-(price|g|vol|vol2|netG)$/.test(id)) { var v = e.target.value.replace(/[^0-9.,]/g, '').slice(0, 10); if (v !== e.target.value) e.target.value = v; }
    if (/^pf-(box|box2)$/.test(id)) { var v2 = e.target.value.replace(/\D/g, '').slice(0, 4); if (v2 !== e.target.value) e.target.value = v2; }
    if (/^pf-d[lwh]$/.test(id)) {
      var a = numIn($('pf-dl').value), b = numIn($('pf-dw').value), c = numIn($('pf-dh').value);
      $('pf-dimr').innerHTML = a > 0 && b > 0 && c > 0 ? '= <b>' + O.dec(a * b * c / 1e6, 3) + NB + 'м³</b> <button class="link" type="button" id="pf-dimok">Подставить</button>' : '';
      return;
    }
    paint();
  });
  form.addEventListener('change', function (e) {
    if (e.target.tagName === 'SELECT' || e.target.type === 'checkbox') {
      paint();
      if (e.target.id === 'pf-grp' && e.target.value === '__new') $('pf-gnew').focus();
    }
  });
  form.addEventListener('click', function (e) {
    if (e.target.closest('#pf-dim')) { $('pf-dims').hidden = !$('pf-dims').hidden; if (!$('pf-dims').hidden) $('pf-dl').focus(); return; }
    if (e.target.closest('#pf-dimok')) {
      var v = numIn($('pf-dl').value) * numIn($('pf-dw').value) * numIn($('pf-dh').value) / 1e6;
      $('pf-vol').value = String(Math.round(v * 1000) / 1000).replace('.', ',');
      $('pf-dims').hidden = true; Admin.guard.set(true); paint(); $('pf-vol').focus();
    }
  });
  form.addEventListener('submit', function (e) {
    tried = true;
    var err = errors();
    if (Object.keys(err).length) {
      e.preventDefault();
      Admin.guard.set(true);
      paint();
      var bad = form.querySelector('.is-bad') || $('pf-e');
      bad.scrollIntoView({ block: 'center', behavior: 'smooth' });
      if (bad.focus) bad.focus({ preventScroll: true });
    }
  });
})();
