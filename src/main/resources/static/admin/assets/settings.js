/* Настройки (vSettings из макета): у каждой карточки своя форма — «Сохранить» включается после правки; шапка накладной
   меняется сразу; проверки БИН, точки на карте и окон выдачи до отправки; окна — итог и занятость на 7 дней по часам из
   JSON страницы; WhatsApp — окно с QR (обновляется, пока номер не подключат), «Отключить», «Подключить заново»;
   «Загрузить из 1С» — с вопросом. Сервер проверяет всё ещё раз. */
(function () {
  'use strict';
  var $ = Admin.$, esc = Admin.esc, O = window.Ops, NB = O.NB;
  function pad(n) { return (n < 10 ? '0' : '') + n; }
  function bad(el, text) { el.classList.add('is-bad'); el.focus(); Admin.toast(text); }

  /* ---- карточки: правка → «Сохранить» ---- */
  document.querySelectorAll('form.sg-card').forEach(function (f) {
    var btn = f.querySelector('.sg-f button[type=submit]'), m = f.querySelector('.sg-m');
    if (btn) btn.disabled = true;
    function dirty() { if (btn) btn.disabled = false; if (m) m.textContent = 'Есть несохранённые изменения'; }
    f.addEventListener('input', dirty);
    f.addEventListener('change', dirty);
  });

  /* ---- разделы: плавно к карточке ---- */
  var nav = document.querySelector('.sg-nav');
  if (nav) nav.addEventListener('click', function (e) {
    var a = e.target.closest('a[href^="#"]'); if (!a) return;
    var el = $(a.getAttribute('href').slice(1)); if (!el) return;
    e.preventDefault();
    el.scrollIntoView({ block: 'start', behavior: 'smooth' });
    var h = el.querySelector('h2'); h.setAttribute('tabindex', '-1'); h.focus({ preventScroll: true });
    try { history.replaceState(null, '', a.getAttribute('href')); } catch (x) { /* ignore */ }
  });

  /* ---- компания: шапка накладной ---- */
  var inv = $('st-inv');
  function v(id) { return $(id) ? $(id).value.trim() : ''; }
  function paintInv() {
    var name = v('st-name') || 'Название не задано', legal = v('st-legal'), addr = v('st-address').replace(/\s*\(.*\)\s*$/, ''), ph = v('st-phone');
    inv.innerHTML = '<p class="inv-mh">Расходная накладная № ' + esc(inv.dataset.no) + ' от ' + esc(inv.dataset.date) + '</p><dl><div><dt>Поставщик</dt><dd><b>' + esc(name) + '</b>' +
      (legal ? '<span>' + esc(legal) + '</span>' : '') + (addr ? '<span>' + esc(addr) + '</span>' : '') + (ph ? '<span>тел. ' + esc(ph) + '</span>' : '') + '</dd></div>' +
      '<div><dt>Покупатель</dt><dd><b>' + esc(inv.dataset.buyer) + '</b></dd></div></dl>' +
      (legal ? '' : '<p class="sg-w">' + Admin.icon('alert') + '<span>Без юр. лица и БИН в накладной будет только название.</span></p>');
  }
  if (inv) {
    paintInv();
    $('st-co').addEventListener('input', paintInv);
    $('st-co').addEventListener('submit', function (e) {
      $('st-co').querySelectorAll('.is-bad').forEach(function (x) { x.classList.remove('is-bad'); });
      if (!v('st-name')) { e.preventDefault(); bad($('st-name'), 'Введите название компании.'); return; }
      if (v('st-legal') && !/\b\d{12}\b/.test(v('st-legal'))) { e.preventDefault(); bad($('st-legal'), 'БИН — 12 цифр, например «ТОО «…» · БИН 123456789012».'); }
    });
  }
  /* ---- сайт: точка на карте, год основания ---- */
  if ($('st-site')) $('st-site').addEventListener('submit', function (e) {
    $('st-map').classList.remove('is-bad'); $('st-year').classList.remove('is-bad');
    if (v('st-map') && !/^\s*-?\d{1,2}(\.\d+)?\s*,\s*-?\d{1,3}(\.\d+)?\s*$/.test(v('st-map'))) { e.preventDefault(); bad($('st-map'), 'Точка на карте — «широта, долгота», например 42.3417, 69.5901.'); return; }
    if (v('st-year') && !/^\d{4}$/.test(v('st-year'))) { e.preventDefault(); bad($('st-year'), 'Год основания — четыре цифры, например 2018.'); }
  });

  /* ---- окна выдачи ---- */
  var SD = O.json('st-slots-data');
  function readSlots() {
    var days = [0, 0, 0, 0, 0, 0, 0];
    $('st-slots').querySelectorAll('input[name="wd"]').forEach(function (x) { days[+x.value] = x.checked ? 1 : 0; });
    return { from: +$('st-from').value, to: +$('st-to').value, cap: +String($('st-cap').value).replace(/\D/g, '') || 0, days: days };
  }
  function slotErr(s) {
    var e = [];
    if (s.to <= s.from) e.push('«Последнее до» — позже первого окна.');
    if (!(s.cap >= 1 && s.cap <= 50)) e.push('Заказов в окне — от 1 до 50.');
    if (!s.days.some(Boolean)) e.push('Оставьте хотя бы один день выдачи.');
    return e;
  }
  function paintSlots() {
    var s = readSlots(), err = slotErr(s), n = Math.max(0, s.to - s.from), over = [];
    $('st-slsum').innerHTML = err.length ? '<span class="pay-bad">' + esc(err.join(' ')) + '</span>' :
      n + NB + O.plural(n, 'окно', 'окна', 'окон') + ' в день: ' + pad(s.from) + ':00 — ' + pad(s.to) + ':00 · до ' + n * s.cap + NB + O.plural(n * s.cap, 'заказа', 'заказов', 'заказов') + ' в день · выбрать можно на ' + SD.days + ' дней вперёд';
    var html = '<table class="tbl sg-wt"><thead><tr><th>День</th>';
    for (var h = s.from; h < s.to; h++) html += '<th class="c">' + pad(h) + '</th>';
    html += '</tr></thead><tbody>';
    SD.week.forEach(function (d) {
      var on = s.days[d.w];
      html += '<tr' + (on ? '' : ' class="is-quiet"') + '><td class="sg-wd">' + esc(d.rel) + '<small>' + esc(d.wd) + '</small></td>';
      for (var hh = s.from; hh < s.to; hh++) {
        var taken = d.taken[String(hh)] || 0, past = d.d === SD.today && hh + 1 <= SD.nowHour;
        if (taken > s.cap && on) over.push(d.rel + ' ' + pad(hh) + ':00 — ' + taken);
        html += '<td class="c"><span class="wl' + (!on ? ' is-off' : past ? ' is-past' : taken >= s.cap ? ' is-full' : taken ? ' is-some' : '') + '" title="' + (on ? taken + ' из ' + s.cap : 'не выдаём') + '">' + (on ? (past ? '·' : taken + '/' + s.cap) : '—') + '</span></td>';
      }
      html += '</tr>';
    });
    var off = SD.openDays.some(function (w) { return !s.days[w]; });
    $('st-wk').innerHTML = '<div class="tbl-w">' + html + '</tbody></table></div>' +
      (over.length ? '<p class="warn-s">' + Admin.icon('alert') + '<span>Уже записано больше, чем окно вмещает: ' + esc(over.join('; ')) + '. Эти заказы останутся — новых в окно не возьмут.</span></p>' : '') +
      (off ? '<p class="warn-s">' + Admin.icon('alert') + '<span>На выключенный день уже есть открытые заказы — они останутся, перенесите их в карточке заказа.</span></p>' : '');
  }
  if (SD && $('st-slots')) {
    paintSlots();
    $('st-slots').addEventListener('input', function (e) {
      if (e.target.id === 'st-cap') { var x = e.target.value.replace(/\D/g, '').slice(0, 2); if (x !== e.target.value) e.target.value = x; }
      paintSlots();
    });
    $('st-slots').addEventListener('change', paintSlots);
    $('st-slots').addEventListener('submit', function (e) {
      var err = slotErr(readSlots());
      if (err.length) { e.preventDefault(); $('st-slsum').scrollIntoView({ block: 'center', behavior: 'smooth' }); Admin.toast(err[0]); }
    });
  }

  /* ---- WhatsApp ---- */
  var qrTimer = null, stTimer = null;
  function qrDlg() {
    Admin.openDlg('<div class="dlg-in"><h2 id="dlg-h">Подключить WhatsApp</h2><div class="qr" id="wa-qrbox" role="img" aria-label="QR-код для подключения WhatsApp">QR</div>' +
      '<ol class="qr-steps"><li>Откройте WhatsApp на телефоне с рабочим номером.</li><li>Настройки → Связанные устройства → Привязать устройство.</li><li>Наведите камеру на код. Код обновляется сам каждые 20 секунд.</li></ol>' +
      '<p class="muted" id="wa-qst">Ждём код от шлюза WhatsApp…</p><div class="dlg-acts"><button class="btn btn-soft" type="button" data-close>Закрыть</button></div></div>');
    function img() {
      var box = $('wa-qrbox'); if (!box) { stop(); return; }
      var im = new Image();
      im.onload = function () { if (!$('wa-qrbox')) return; box.innerHTML = ''; box.appendChild(im); $('wa-qst').textContent = 'Отсканируйте код телефоном.'; };
      im.onerror = function () { if ($('wa-qst')) $('wa-qst').textContent = 'Кода пока нет — шлюз готовит новый, подождите несколько секунд.'; };
      im.alt = ''; im.src = '/admin/settings/whatsapp/qr?t=' + Date.now();
    }
    function status() {
      if (!$('wa-qrbox')) { stop(); return; }
      fetch('/admin/settings/whatsapp/status', { credentials: 'same-origin' }).then(function (r) { return r.json(); }).then(function (x) {
        if (x && x.status === 'open') { stop(); Admin.closeDlg(); location.assign('/admin/settings?t=' + Date.now() + '#st-wa'); }
      }).catch(function () { /* следующий раз */ });
    }
    function stop() { clearInterval(qrTimer); clearInterval(stTimer); qrTimer = stTimer = null; }
    stop();
    img(); qrTimer = setInterval(img, 20000); stTimer = setInterval(status, 3000);
  }
  if ($('wa-qr')) {
    $('wa-qr').addEventListener('click', qrDlg);
    if ($('wa-qr').getAttribute('data-open') === 'true') qrDlg();
  }
  function logout(again) {
    var f = $('wa-logout'), a = f.querySelector('input[name="again"]');
    a.disabled = !again; a.value = again ? '1' : '';
    f.submit();
  }
  if ($('wa-off')) $('wa-off').addEventListener('click', function () {
    O.askDlg({ title: 'Отключить WhatsApp?', danger: true, ok: 'Отключить', text: 'Заказы, которые клиенты пишут в WhatsApp, перестанут приходить в CRM, пока номер не подключат заново по QR-коду.', onOk: function () { logout(false); } });
  });
  if ($('wa-again')) $('wa-again').addEventListener('click', function () {
    O.askDlg({ title: 'Подключить WhatsApp заново?', ok: 'Разорвать и показать QR', text: 'Текущее подключение разорвётся, шлюз покажет новый QR-код. Пока его не отсканируют, заказы из WhatsApp не приходят.', onOk: function () { logout(true); } });
  });

  /* ---- 1С: загрузки ---- */
  document.querySelectorAll('.sg-lb[data-l]').forEach(function (b) {
    b.addEventListener('click', function () {
      O.askDlg({ title: b.getAttribute('data-t'), text: b.getAttribute('data-x'), ok: 'Загрузить', danger: b.hasAttribute('data-danger'),
        onOk: function () { var f = $('st-load'); f.action = b.getAttribute('data-l'); Admin.guard.set(false); f.submit(); } });
    });
  });
})();
