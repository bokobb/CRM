/* Серая дата в шапке открывает календарь в цветах сайта; выбранный день — /admin/dashboard?day=… (дашборд) или
   страница из data-base с параметром data-param (производство: /admin/production?date=…).
   С data-range (заказы, возвраты) — ещё промежуток: «Промежуток» → первый и последний день (или 7 / 30 дней
   по сегодня, этот месяц), «Все даты»; параметр пишется всегда: date=2026-10-21, date=2026-10-14..2026-10-21,
   date=all. */
(function () {
  'use strict';
  var b = document.getElementById('dpick-b'), cal = document.getElementById('cal');
  if (!b || !cal) return;
  var MON = ['Январь', 'Февраль', 'Март', 'Апрель', 'Май', 'Июнь', 'Июль', 'Август', 'Сентябрь', 'Октябрь', 'Ноябрь', 'Декабрь'];
  var WD = ['Пн', 'Вт', 'Ср', 'Чт', 'Пт', 'Сб', 'Вс'];
  function pad(n) { return (n < 10 ? '0' : '') + n; }
  function iso(y, m, d) { return y + '-' + pad(m + 1) + '-' + pad(d); }
  var now = new Date(), today = iso(now.getFullYear(), now.getMonth(), now.getDate());
  function shift(n) { var t = new Date(now.getFullYear(), now.getMonth(), now.getDate() + n); return iso(t.getFullYear(), t.getMonth(), t.getDate()); }
  var sel = b.getAttribute('data-day') || '', to = b.getAttribute('data-to') || sel, range = b.hasAttribute('data-range');
  var min = b.getAttribute('data-min'), max = b.getAttribute('data-max'), keep = b.getAttribute('data-keep');
  var base = b.getAttribute('data-base') || '/admin/dashboard', param = b.getAttribute('data-param') || 'day';
  var start = sel || today, y = +start.slice(0, 4), m = +start.slice(5, 7) - 1;
  var mode = false, pickA = null, hover = null;                   // промежуток: включён ли, первый день, день под курсором
  if (range) cal.setAttribute('aria-label', 'Выбрать день или промежуток');

  // что подсвечено: выбранный промежуток или тот, что сейчас выбирают
  function span() {
    if (mode && pickA) { var e = hover || pickA; return pickA < e ? [pickA, e] : [e, pickA]; }
    return mode || !sel ? null : [sel, to];
  }
  function paint() {
    var s = span();
    cal.querySelectorAll('.cal-d').forEach(function (x) {
      var v = x.getAttribute('data-d'), on = s && v >= s[0] && v <= s[1], end = on && (v === s[0] || v === s[1]);
      x.classList.toggle('is-sel', !!end);
      x.classList.toggle('is-in', !!on && !end);
      if (end) x.setAttribute('aria-pressed', 'true'); else x.removeAttribute('aria-pressed');
    });
  }
  function foot() {
    if (!range) {
      return '<button type="button" class="link" data-d="' + today + '">Сегодня</button>' +
        (shift(1) <= max ? '<button type="button" class="link" data-d="' + shift(1) + '">Завтра</button>' : '');
    }
    if (mode) {
      return '<span class="cal-hint">' + (pickA ? 'Теперь последний день' : 'Нажмите первый день промежутка') + '</span>' +
        '<button type="button" class="link" data-q="7">7 дней</button><button type="button" class="link" data-q="30">30 дней</button>' +
        '<button type="button" class="link" data-mon>Этот месяц</button>' +
        '<button type="button" class="link cal-rg" data-cancel>Отмена</button>';
    }
    return '<button type="button" class="link" data-d="' + today + '">Сегодня</button>' +
      (shift(1) <= max ? '<button type="button" class="link" data-d="' + shift(1) + '">Завтра</button>' : '') +
      '<button type="button" class="link" data-all>Все даты</button>' +
      '<button type="button" class="link cal-rg" data-rg>Промежуток…</button>';
  }
  function render() {
    var first = (new Date(y, m, 1).getDay() + 6) % 7, days = new Date(y, m + 1, 0).getDate();
    var h = '<div class="cal-h"><button type="button" class="cal-nav" data-mv="-1" aria-label="Предыдущий месяц"' + (iso(y, m, 1) <= min ? ' disabled' : '') + '>‹</button>' +
      '<b>' + MON[m] + ' ' + y + '</b>' +
      '<button type="button" class="cal-nav" data-mv="1" aria-label="Следующий месяц"' + (iso(y, m, days) >= max ? ' disabled' : '') + '>›</button></div>' +
      '<div class="cal-g">' + WD.map(function (w, i) { return '<span class="cal-w' + (i > 4 ? ' is-we' : '') + '">' + w + '</span>'; }).join('');
    for (var i = 0; i < first; i++) h += '<span></span>';
    for (var d = 1; d <= days; d++) {
      var v = iso(y, m, d), off = v < min || v > max;
      h += '<button type="button" class="cal-d' + (v === today ? ' is-today' : '') + '" data-d="' + v + '"' + (off ? ' disabled' : '') + '>' + d + '</button>';
    }
    cal.innerHTML = h + '</div><div class="cal-f">' + foot() + '</div>';
    paint();
  }
  function open() { render(); cal.hidden = false; b.setAttribute('aria-expanded', 'true'); var f = cal.querySelector('.cal-d.is-sel') || cal.querySelector('.cal-d:not(:disabled)'); if (f) f.focus(); }
  function close() { cal.hidden = true; b.setAttribute('aria-expanded', 'false'); mode = false; pickA = hover = null; }
  function go(v) {
    var q = [];
    if (range || v !== today) q.push(param + '=' + v);
    if (keep) q.push(keep);                                       // дашборд — период выручки (p=7 / p=30), заказы — статус, поиск, клиент
    location.href = base + (q.length ? '?' + q.join('&') : '');
  }

  b.addEventListener('click', function (e) { e.stopPropagation(); if (cal.hidden) open(); else close(); });
  cal.addEventListener('click', function (e) {
    e.stopPropagation();
    var t = e.target.closest('button');
    if (!t || t.disabled) return;
    if (t.hasAttribute('data-mv')) { m += +t.getAttribute('data-mv'); if (m < 0) { m = 11; y--; } if (m > 11) { m = 0; y++; } render(); return; }
    if (t.hasAttribute('data-rg')) { mode = true; pickA = hover = null; render(); var f = cal.querySelector('.cal-d:not(:disabled)'); if (f) f.focus(); return; }
    if (t.hasAttribute('data-cancel')) { mode = false; pickA = hover = null; render(); return; }
    if (t.hasAttribute('data-q')) { go(shift(1 - +t.getAttribute('data-q')) + '..' + today); return; }
    if (t.hasAttribute('data-mon')) {                               // с 1-го по конец месяца, но не дальше последнего доступного дня
      var mEnd = iso(now.getFullYear(), now.getMonth(), new Date(now.getFullYear(), now.getMonth() + 1, 0).getDate());
      go(iso(now.getFullYear(), now.getMonth(), 1) + '..' + (mEnd < max ? mEnd : max));
      return;
    }
    if (t.hasAttribute('data-all')) { go('all'); return; }
    var v = t.getAttribute('data-d');
    if (!v) return;
    if (mode && t.classList.contains('cal-d')) {
      if (!pickA) { pickA = v; render(); var c = cal.querySelector('.cal-d[data-d="' + v + '"]'); if (c) c.focus(); return; }
      var a = pickA < v ? pickA : v, z = pickA < v ? v : pickA;
      go(a === z ? a : a + '..' + z);
      return;
    }
    go(v);
  });
  // пока выбирают последний день — промежуток тянется за курсором и фокусом
  function track(e) {
    if (!mode || !pickA) return;
    var t = e.target.closest('.cal-d');
    if (t && !t.disabled && t.getAttribute('data-d') !== hover) { hover = t.getAttribute('data-d'); paint(); }
  }
  cal.addEventListener('mouseover', track);
  cal.addEventListener('focusin', track);
  document.addEventListener('click', function () { if (!cal.hidden) close(); });
  document.addEventListener('keydown', function (e) {
    if (e.key !== 'Escape' || cal.hidden) return;
    if (mode) { mode = false; pickA = hover = null; render(); return; }     // сначала выйти из промежутка, потом закрыть
    close(); b.focus();
  });
})();
