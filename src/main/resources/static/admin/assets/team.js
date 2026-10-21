/* Команда (часть 4 макета): список сотрудников — «Загрузить из 1С»; форма сотрудника — превью, группа по должности,
   как пойдут смены и оклад, проверки как на сервере; табель — отметка нажатием по кругу ✓ → ½ → ✗ → В → пусто,
   итоги строк, пустые прошедшие дни, фильтр по группе, сохранение одной формой; входы в CRM — окно входа;
   свой пароль — проверки. Всё уходит обычными формами POST, сервер проверяет ещё раз. */
(function () {
  'use strict';
  var $ = Admin.$, esc = Admin.esc, O = window.Ops, NB = O.NB;

  /* ---------- список: загрузка из 1С ---------- */
  var s1 = $('sf-1c');
  if (s1) s1.addEventListener('click', function () {
    O.askDlg({ title: 'Загрузить сотрудников из 1С?', ok: 'Загрузить',
      text: 'Новые сотрудники добавятся; у связанных обновятся ФИО, должность, дата приёма, оклад и увольнение. Ставку за смену и группу затрат CRM ведёт сама — их загрузка не трогает.',
      onOk: function () { $('sf-1c-form').submit(); } });
  });

  /* ---------- форма сотрудника ---------- */
  var ef = $('ef');
  if (ef) (function () {
    var D = O.json('ef-data') || {}, tried = ef.getAttribute('data-tried') === 'true';
    var WORDS = { ADMIN: ['бухгалтер', 'директор', 'оператор', 'охран', 'сторож', 'кассир', 'админ'],
      SALES: ['водител', 'экспедитор', 'грузчик', 'торгов', 'продаж', 'менеджер'],
      PRODUCTION: ['технолог', 'слесар', 'механик', 'электрик', 'повар', 'уборщ', 'заведующ'] };
    var KEY = { ADMIN: 'admin', SALES: 'sales', PRODUCTION: 'shop' };
    function byPos(p) {
      p = String(p || '').toLowerCase();
      var out = 'brigade';
      ['ADMIN', 'SALES', 'PRODUCTION'].some(function (g) { if (WORDS[g].some(function (w) { return p.indexOf(w) >= 0; })) { out = KEY[g]; return true; } return false; });
      return out;
    }
    function v(id) { return $(id) ? $(id).value.trim() : ''; }
    function digits(id) { return v(id).replace(/\D/g, ''); }
    function norm(s) { return O.norm(s).replace(/\s+/g, ' '); }
    function initials(n) { var p = n.trim().split(/\s+/); return ((p[0] || '').charAt(0) + (p[1] || '').charAt(0)).toUpperCase(); }
    function errors() {
      var x = {}, nm = norm(v('ef-name'));
      if (!nm) x.name = 'Введите ФИО.';
      else if (nm.split(' ').length < 2) x.name = 'Фамилия и имя — через пробел, как в 1С.';
      else if ((D.names || []).indexOf(nm) >= 0) x.name = 'Такой сотрудник уже есть — откройте его карточку.';
      var ph = digits('ef-phone');
      if (v('ef-phone') && ph.length !== 11) x.phone = 'Телефон — 11 цифр, например +7 700 000 00 00.';
      if (D.money) {
        var r = digits('ef-rate'), s = digits('ef-sal');
        if (r && !(+r > 0 && +r <= 200000)) x.rate = 'Ставка — от 1 до 200 000 ₸.';
        if (s && !(+s > 0 && +s <= 5000000)) x.sal = 'Оклад — от 1 до 5 000 000 ₸.';
      }
      return x;
    }
    function how(g) {
      var rate = +digits('ef-rate') || 0, sal = +digits('ef-sal') || 0, wd = D.workDays, gen = D.monthGen, per = sal ? sal / wd : 0;
      if (g === 'brigade') {
        if (rate) return 'Смена в бригаде заявки — <b>' + O.money(rate) + '</b> в её себестоимость.';
        if (sal) return 'Смена в бригаде заявки — <b>' + O.money(per) + '</b> в её себестоимость (оклад ' + O.money(sal) + ' ÷ ' + wd + ' рабочих дней ' + gen + ').';
        return '<span class="pay-bad">Без ставки и оклада</span> смены не попадут ни в себестоимость, ни в начисления табеля.';
      }
      if (sal) return 'Оклад ' + O.money(sal) + ' ÷ ' + wd + ' рабочих дней ' + gen + ' = <b>' + O.money(per) + '</b> в день — ' + (g === 'shop' ? 'в накладные цеха' : 'в расходы, не в себестоимость') + '.';
      if (rate) return 'Ставка ' + O.money(rate) + ' за отработанный день по табелю.';
      return 'Оклад не задан — в начислениях табеля будет 0.';
    }
    function paint() {
      var name = v('ef-name'), pos = v('ef-pos'), auto = byPos(pos), set = $('ef-grp') ? $('ef-grp').value : '', g = set || auto;
      var pv = $('ef-prev');
      pv.querySelector('.pf-pv .cav').textContent = initials(name) || '?';
      pv.querySelector('.pf-pv b').textContent = name || 'Новый сотрудник';
      pv.querySelector('.pf-pv small').textContent = pos || 'должность не указана';
      var tag = pv.querySelector('.ef-g .gt');
      tag.className = 'gt gt--' + g; tag.textContent = D.labels[g];
      pv.querySelector('.ef-g span:last-child').textContent = set ? 'задана вручную' : 'по должности';
      if (pv.querySelector('.ef-how')) pv.querySelector('.ef-how').innerHTML = how(g);
      if ($('ef-grp')) {
        $('ef-grp').querySelector('option[value=""]').textContent = 'По должности — ' + D.labels[auto];
        $('ef-grph').textContent = D.hints[g].charAt(0).toUpperCase() + D.hints[g].slice(1) + '.';
      }
      var off = $('ef-on') && !$('ef-on').checked, w = pv.querySelector('.warn-s');
      if (w) w.hidden = !off;
      if (tried) mark(errors());
    }
    function mark(x) {
      ['name', 'phone', 'rate', 'sal'].forEach(function (k) {
        var el = $('ef-' + k); if (!el) return;
        el.classList.toggle('is-bad', !!x[k]); el.setAttribute('aria-invalid', String(!!x[k]));
      });
      var list = Object.keys(x).map(function (k) { return x[k]; });
      $('ef-e').innerHTML = list.map(function (t) { return '<span>' + esc(t) + '</span>'; }).join('');
      $('ef-e').hidden = !list.length;
    }
    ef.addEventListener('input', paint);
    ef.addEventListener('change', paint);
    ['ef-rate', 'ef-sal'].forEach(function (id) {
      var el = $(id); if (!el) return;
      el.addEventListener('input', function () { var d = el.value.replace(/\D/g, '').slice(0, 7); if (d !== el.value) el.value = d; });
    });
    ef.addEventListener('submit', function (e) {
      tried = true;
      var x = errors();
      if (Object.keys(x).length) {
        e.preventDefault(); mark(x);
        var bad = ef.querySelector('.is-bad') || $('ef-e');
        bad.scrollIntoView({ block: 'center', behavior: 'smooth' });
        if (bad.focus) bad.focus({ preventScroll: true });
        Admin.guard.set(true);
      }
    });
    paint();
  })();

  /* ---------- табель ---------- */
  var tf = $('ts-form');
  if (tf) (function () {
    var money = tf.getAttribute('data-money') === 'true', today = tf.getAttribute('data-today'), month = tf.getAttribute('data-month');
    var SYM = { P: '✓', H: '½', A: '✗', O: 'В', '': '·' }, NAME = { P: 'пришёл', H: 'полдня', A: 'не пришёл', O: 'выходной', '': 'пусто' };
    var ORDER = ['P', 'H', 'A', 'O', ''], over = {}, box = $('ts-box'), grp = 'all', confirmed = false;
    function cur(b) { var k = b.getAttribute('data-c'); return k in over ? over[k] : b.getAttribute('data-s'); }
    function past(b) { return b.getAttribute('data-c').split('|')[1] < today; }
    function isMiss(b) { return !b.disabled && cur(b) === '' && past(b); }
    function paintCell(b) {
      var k = b.getAttribute('data-c'), s = cur(b), ed = k in over, auto = !ed && b.getAttribute('data-auto') === 'true';
      b.className = 'cm cm--' + (s || 'E') + (auto ? ' is-auto' : '') + (ed ? ' is-ed' : '') + (isMiss(b) ? ' is-miss' : '');
      b.textContent = SYM[s];
      var lbl = b.getAttribute('aria-label').replace(/: [^:]*$/, '');
      b.setAttribute('aria-label', lbl + ': ' + NAME[s] + (auto && s === 'P' ? ' (по бригаде)' : ''));
    }
    function row(tr) {
      var w = 0, a = 0;
      tr.querySelectorAll('button[data-c]').forEach(function (b) { var s = cur(b); if (s === 'P') w += 1; else if (s === 'H') w += 0.5; else if (s === 'A') a++; });
      var rate = parseFloat(tr.getAttribute('data-rate')) || 0, pay = money ? w * rate : 0, td = tr.querySelector('.tsg-s');
      td.innerHTML = '<b>' + O.dec(w, w % 1 ? 1 : 0) + NB + 'см.</b>' + (a ? '<span class="pay-bad">✗' + NB + a + '</span>' : '') + '<small>' + (money && pay ? O.money(pay) : '—') + '</small>';
      return { w: w, a: a, pay: pay };
    }
    function totals() {
      var tw = 0, ta = 0, tp = 0, miss = 0, n = Object.keys(over).length;
      box.querySelectorAll('tbody tr[data-rate]').forEach(function (tr) {
        var x = row(tr);
        if (!tr.hidden) { tw += x.w; ta += x.a; tp += x.pay; }
        tr.querySelectorAll('button[data-c]').forEach(function (b) { if (isMiss(b)) miss++; });
      });
      if ($('ts-ft')) $('ts-ft').textContent = O.dec(tw, tw % 1 ? 1 : 0) + NB + O.plural(Math.ceil(tw), 'смена', 'смены', 'смен') + (ta ? ' · пропусков ' + ta : '');
      if ($('ts-tot')) $('ts-tot').textContent = money ? O.money(tp) : O.dec(tw, tw % 1 ? 1 : 0) + NB + 'см.';
      $('ts-warn').hidden = !miss;
      $('ts-warn').querySelector('span').textContent = 'Пустых прошедших дней: ' + miss + ' — при сохранении станут «не пришёл». Сначала отметьте, кто был.';
      $('ts-save').textContent = n ? 'Сохранить · ' + n : 'Сохранить табель';
      $('ts-in').innerHTML = Object.keys(over).map(function (k) {
        var p = k.split('|');
        return '<input type="hidden" name="c_' + esc(p[0]) + '_' + esc(p[1]) + '" value="' + esc(over[k]) + '">';
      }).join('');
      Admin.guard.set(n > 0);
      return miss;
    }
    if (box) box.addEventListener('click', function (e) {
      var b = e.target.closest('button[data-c]'); if (!b || b.disabled) return;
      var k = b.getAttribute('data-c'), nx = ORDER[(ORDER.indexOf(cur(b)) + 1) % ORDER.length];
      if (nx === b.getAttribute('data-s') && b.getAttribute('data-auto') !== 'true') delete over[k]; else over[k] = nx;
      paintCell(b); totals();
    });
    var chips = $('ts-g');
    if (chips) chips.addEventListener('click', function (e) {
      var c = e.target.closest('.chip'); if (!c) return;
      grp = c.getAttribute('data-g');
      chips.querySelectorAll('.chip').forEach(function (x) { x.setAttribute('aria-pressed', String(x === c)); });
      box.querySelectorAll('tbody tr[data-g]').forEach(function (tr) { tr.hidden = grp !== 'all' && tr.getAttribute('data-g') !== grp; });
      totals();
    });
    tf.addEventListener('submit', function (e) {
      var miss = totals();
      if (!miss || confirmed) { Admin.guard.set(false); return; }
      e.preventDefault();
      O.askDlg({ title: 'Сохранить табель?', ok: 'Сохранить',
        text: 'Пустые прошедшие дни (' + miss + ') станут «не пришёл». Если кто-то был — отметьте сначала его.',
        onOk: function () { confirmed = true; Admin.guard.set(false); if (tf.requestSubmit) tf.requestSubmit(); else tf.submit(); } });
    });
    var th = box && box.querySelector('th.is-today');
    if (th && box.scrollWidth > box.clientWidth + 4) box.scrollLeft = Math.max(0, th.offsetLeft - box.clientWidth / 2);
    if (month) totals();
  })();

  /* ---------- входы в CRM ---------- */
  var uj = O.json('us-data');
  if (uj) (function () {
    function dlg(b) {
      var u = b ? { id: b.getAttribute('data-id'), name: b.getAttribute('data-name'), login: b.getAttribute('data-login'), role: b.getAttribute('data-role'),
        on: b.getAttribute('data-on') === 'true', self: b.getAttribute('data-self') === 'true' } : null, self = u && u.self;
      var roles = Object.keys(uj.roles).filter(function (r) { return r !== 'ADMIN' || uj.admin || (u && u.role === 'ADMIN'); });
      Admin.openDlg('<form class="dlg-in" id="ud" method="post" novalidate action="/admin/staff/users' + (u ? '/' + esc(u.id) : '') + '"><h2 id="dlg-h">' + (u ? 'Вход: ' + esc(u.name) : 'Новый вход в CRM') + '</h2>' + Admin.csrfInput() +
        '<div class="fgrid fgrid--2"><label class="field wide"><span>ФИО</span><input class="inp" id="ud-n" name="name" maxlength="100" autocomplete="off" value="' + esc(u ? u.name : '') + '" list="ud-names"></label>' +
        '<label class="field"><span>Логин</span><input class="inp" id="ud-l" name="login" maxlength="40" autocomplete="off" autocapitalize="none" spellcheck="false" value="' + esc(u ? u.login : '') + '"><small class="hint">латиница и цифры</small></label>' +
        '<label class="field"><span>Роль</span><select class="sel" id="ud-r" name="role"' + (self || (u && u.role === 'ADMIN' && !uj.admin) ? ' disabled' : '') + '>' +
        roles.map(function (r) { return '<option value="' + r + '"' + ((u ? u.role : 'SALES') === r ? ' selected' : '') + '>' + esc(uj.roles[r]) + '</option>'; }).join('') + '</select></label>' +
        '<p class="hint wide" id="ud-rh"></p>' +
        '<label class="field wide"><span>' + (u ? 'Новый пароль' : 'Пароль') + '</span><span class="pw"><input class="inp" id="ud-p" name="password" type="password" autocomplete="new-password" placeholder="' + (u ? 'пусто — оставить прежний' : 'не короче 6 знаков') + '"><button class="ibtn" type="button" id="ud-eye" aria-label="Показать пароль" aria-pressed="false">' + Admin.icon('eye') + '</button></span></label>' +
        '<label class="switch wide"><input type="checkbox" id="ud-on" name="on" value="true"' + (!u || u.on ? ' checked' : '') + (self ? ' disabled' : '') + '> Вход открыт<small class="hint">' + (self ? 'свою роль и доступ изменить нельзя — только другой администратор' : 'закройте, когда человек ушёл: войти не сможет, история его действий останется') + '</small></label></div>' +
        '<p class="errs" id="ud-e" hidden></p><div class="dlg-acts"><button class="btn btn-soft" type="button" data-close>Отмена</button><button class="btn btn-primary" type="submit">' + (u ? 'Сохранить' : 'Создать вход') + '</button></div></form>', 'is-wide');
      function hint() { $('ud-rh').textContent = uj.sees[$('ud-r').value] || ''; }
      hint();
      $('ud-r').addEventListener('change', hint);
      $('ud-eye').onclick = function () { var p = $('ud-p'), on = p.type === 'password'; p.type = on ? 'text' : 'password'; this.setAttribute('aria-pressed', String(on)); };
      $('ud').addEventListener('submit', function (ev) {
        var n = $('ud-n').value.trim(), l = $('ud-l').value.trim().toLowerCase(), p = $('ud-p').value, errs = [];
        if (!n) errs.push('Введите ФИО.');
        if (!/^[a-z0-9._-]{3,40}$/.test(l)) errs.push('Логин — от 3 знаков: латиница, цифры, точка, дефис.');
        else if (uj.logins.some(function (x) { return x.toLowerCase() === l && (!u || x.toLowerCase() !== u.login.toLowerCase()); })) errs.push('Такой логин уже есть.');
        if (!u && p.length < 6) errs.push('Пароль — не короче 6 знаков.');
        if (u && p && p.length < 6) errs.push('Новый пароль — не короче 6 знаков.');
        $('ud-e').innerHTML = errs.map(function (x) { return '<span>' + esc(x) + '</span>'; }).join(''); $('ud-e').hidden = !errs.length;
        if (errs.length) { ev.preventDefault(); return; }
        $('ud-l').value = l;
      });
      $(u ? 'ud-p' : 'ud-n').focus();
    }
    if ($('us-new')) $('us-new').addEventListener('click', function () { dlg(null); });
    document.querySelectorAll('[data-u]').forEach(function (b) { b.addEventListener('click', function () { dlg(b); }); });
  })();

  /* ---------- свой пароль ---------- */
  var pf = $('pwf');
  if (pf) pf.addEventListener('submit', function (ev) {
    var c = $('pw-c').value, n = $('pw-n').value, r = $('pw-r').value, errs = [];
    if (!c) errs.push('Введите текущий пароль.');
    if (n.length < 6) errs.push('Новый пароль — не короче 6 знаков.');
    else if (n !== r) errs.push('Пароли не совпадают.');
    $('pw-e').innerHTML = errs.map(function (x) { return '<span>' + esc(x) + '</span>'; }).join(''); $('pw-e').hidden = !errs.length;
    if (errs.length) ev.preventDefault();
  });
})();
