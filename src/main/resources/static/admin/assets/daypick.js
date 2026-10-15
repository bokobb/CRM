/* Серая дата в шапке открывает календарь в цветах сайта; выбранный день — /admin/dashboard?day=… (дашборд) или
   страница из data-base с параметром data-param (производство: /admin/production?date=…). */
(function () {
  'use strict';
  var b = document.getElementById('dpick-b'), cal = document.getElementById('cal');
  if (!b || !cal) return;
  var MON = ['Январь', 'Февраль', 'Март', 'Апрель', 'Май', 'Июнь', 'Июль', 'Август', 'Сентябрь', 'Октябрь', 'Ноябрь', 'Декабрь'];
  var WD = ['Пн', 'Вт', 'Ср', 'Чт', 'Пт', 'Сб', 'Вс'];
  function pad(n) { return (n < 10 ? '0' : '') + n; }
  function iso(y, m, d) { return y + '-' + pad(m + 1) + '-' + pad(d); }
  var now = new Date(), today = iso(now.getFullYear(), now.getMonth(), now.getDate());
  var sel = b.getAttribute('data-day'), min = b.getAttribute('data-min'), max = b.getAttribute('data-max'), keep = b.getAttribute('data-keep');
  var base = b.getAttribute('data-base') || '/admin/dashboard', param = b.getAttribute('data-param') || 'day';
  var y = +sel.slice(0, 4), m = +sel.slice(5, 7) - 1;

  function render() {
    var first = (new Date(y, m, 1).getDay() + 6) % 7, days = new Date(y, m + 1, 0).getDate();
    var h = '<div class="cal-h"><button type="button" class="cal-nav" data-mv="-1" aria-label="Предыдущий месяц"' + (iso(y, m, 1) <= min ? ' disabled' : '') + '>‹</button>' +
      '<b>' + MON[m] + ' ' + y + '</b>' +
      '<button type="button" class="cal-nav" data-mv="1" aria-label="Следующий месяц"' + (iso(y, m, days) >= max ? ' disabled' : '') + '>›</button></div>' +
      '<div class="cal-g">' + WD.map(function (w, i) { return '<span class="cal-w' + (i > 4 ? ' is-we' : '') + '">' + w + '</span>'; }).join('');
    for (var i = 0; i < first; i++) h += '<span></span>';
    for (var d = 1; d <= days; d++) {
      var v = iso(y, m, d), off = v < min || v > max;
      h += '<button type="button" class="cal-d' + (v === sel ? ' is-sel' : '') + (v === today ? ' is-today' : '') + '" data-d="' + v + '"' +
        (off ? ' disabled' : '') + (v === sel ? ' aria-pressed="true"' : '') + '>' + d + '</button>';
    }
    h += '</div><div class="cal-f"><button type="button" class="link" data-d="' + today + '">Сегодня</button>' +
      (tomorrow() <= max ? '<button type="button" class="link" data-d="' + tomorrow() + '">Завтра</button>' : '') + '</div>';
    cal.innerHTML = h;
  }
  function tomorrow() { var t = new Date(now.getFullYear(), now.getMonth(), now.getDate() + 1); return iso(t.getFullYear(), t.getMonth(), t.getDate()); }
  function open() { render(); cal.hidden = false; b.setAttribute('aria-expanded', 'true'); var f = cal.querySelector('.is-sel') || cal.querySelector('.cal-d:not(:disabled)'); if (f) f.focus(); }
  function close() { cal.hidden = true; b.setAttribute('aria-expanded', 'false'); }

  b.addEventListener('click', function (e) { e.stopPropagation(); if (cal.hidden) open(); else close(); });
  cal.addEventListener('click', function (e) {
    e.stopPropagation();
    var mv = e.target.closest('[data-mv]'), d = e.target.closest('[data-d]');
    if (mv && !mv.disabled) { m += +mv.getAttribute('data-mv'); if (m < 0) { m = 11; y--; } if (m > 11) { m = 0; y++; } render(); return; }
    if (d && !d.disabled) {
      var q = [];
      if (d.getAttribute('data-d') !== today) q.push(param + '=' + d.getAttribute('data-d'));
      if (keep) q.push(keep);                                     // период выручки (p=7 / p=30) остаётся
      location.href = base + (q.length ? '?' + q.join('&') : '');
    }
  });
  document.addEventListener('click', function () { if (!cal.hidden) close(); });
  document.addEventListener('keydown', function (e) { if (e.key === 'Escape' && !cal.hidden) { close(); b.focus(); } });
})();
