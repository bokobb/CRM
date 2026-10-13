/* Выбор дня и окна выдачи — в «Перенести» и в форме заказа (slotPicker/bindSlots из макета).
   week — неделя окон с сервера: [{d:'2026-10-13', free:N, slots:[{h:8, left:2, cap:3, past:false}]}]. */
(function () {
  'use strict';
  var NB = ' ';
  var MON3 = ['янв', 'фев', 'мар', 'апр', 'мая', 'июн', 'июл', 'авг', 'сен', 'окт', 'ноя', 'дек'];
  var WD = ['вс', 'пн', 'вт', 'ср', 'чт', 'пт', 'сб'];
  function pd(s) { return new Date(Date.UTC(+s.slice(0, 4), +s.slice(5, 7) - 1, +s.slice(8, 10))); }
  function dm(s) { var d = pd(s); return d.getUTCDate() + NB + MON3[d.getUTCMonth()]; }
  function cap(s) { return s.charAt(0).toUpperCase() + s.slice(1); }
  function pad(n) { return (n < 10 ? '0' : '') + n; }
  function win(h) { return pad(h) + ':00⁠–⁠' + pad(h + 1) + ':00'; }
  function dayOf(week, d) { for (var i = 0; i < week.length; i++) if (week[i].d === d) return week[i]; return week[0]; }

  function html(S, week) {
    var days = '', grid = '';
    week.forEach(function (w, i) {
      days += '<button class="day" type="button" data-day="' + w.d + '" aria-pressed="' + (S.day === w.d) + '"><b>' +
        (i === 0 ? 'Сегодня' : i === 1 ? 'Завтра' : cap(WD[pd(w.d).getUTCDay()])) + '</b><span>' + dm(w.d) + '</span><small>' +
        (w.free ? 'мест ' + w.free : 'мест нет') + '</small></button>';
    });
    var cur = dayOf(week, S.day);
    cur.slots.forEach(function (s) {
      var full = s.left <= 0, on = S.sel && S.sel.d === cur.d && S.sel.h === s.h;
      grid += '<button class="wb" type="button" data-h="' + s.h + '" aria-pressed="' + on + '"' + (s.past || full ? ' disabled' : '') + '><b>' + win(s.h) + '</b><span>' +
        (s.past ? 'прошло' : full ? 'занято' : 'свободно ' + s.left + ' из ' + s.cap) + '</span></button>';
    });
    return '<div class="days" role="group" aria-label="День выдачи">' + days + '</div>' +
      '<div class="wgrid" role="group" aria-label="Окно выдачи">' + grid + '</div>';
  }

  function bind(box, S, week, onPick) {
    if (!S.day || !week.some(function (w) { return w.d === S.day; })) S.day = week[0].d;
    box.innerHTML = html(S, week);
    box.onclick = function (e) {
      var d = e.target.closest('.day'), w = e.target.closest('.wb');
      if (d) { S.day = d.getAttribute('data-day'); bind(box, S, week, onPick); box.querySelector('.day[aria-pressed="true"]').focus(); }
      else if (w && !w.disabled) {
        S.sel = { d: S.day, h: +w.getAttribute('data-h') };
        bind(box, S, week, onPick);
        box.querySelector('.wb[aria-pressed="true"]').focus();
        if (onPick) onPick();
      }
    };
  }

  window.AdminSlots = { bind: bind, win: win, dm: dm };
})();
