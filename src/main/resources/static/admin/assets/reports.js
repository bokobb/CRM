/* Отчёты (vReport из макета): свой период — проверка дат и переход; «Продажи по дням» — столбики итого за минусом
   возвратов по дням из JSON страницы (#rp-data: {days: [{d, sold, ret, n, pcs}], today}) с подсказкой. Без библиотек. */
(function () {
  'use strict';
  var $ = Admin.$, NB = ' ';
  var own = $('rp-own');
  if (own) own.addEventListener('change', function (e) {
    var a = $('rp-a').value, b = $('rp-b').value, re = /^\d{4}-\d\d-\d\d$/;
    $('rp-a').classList.remove('is-bad'); $('rp-b').classList.remove('is-bad');
    if (!re.test(a) || !re.test(b) || a > b) { e.target.classList.add('is-bad'); Admin.toast('Дата «по» — не раньше даты «с».'); return; }
    own.submit();
  });

  var box = $('rp-chart'), el = $('rp-data');
  if (!box || !el) return;
  var data = {};
  try { data = JSON.parse(el.textContent) || {}; } catch (e) { data = {}; }
  var days = data.days || [], TODAY = data.today || '';
  var MON = ['января', 'февраля', 'марта', 'апреля', 'мая', 'июня', 'июля', 'августа', 'сентября', 'октября', 'ноября', 'декабря'];
  var WD = ['вс', 'пн', 'вт', 'ср', 'чт', 'пт', 'сб'], WDL = ['воскресенье', 'понедельник', 'вторник', 'среда', 'четверг', 'пятница', 'суббота'];
  function pd(s) { return new Date(Date.UTC(+s.slice(0, 4), +s.slice(5, 7) - 1, +s.slice(8, 10))); }
  function g3(n) { return String(Math.round(Math.abs(n))).replace(/\B(?=(\d{3})+(?!\d))/g, NB); }
  function money(n) { return (n < 0 ? '−' : '') + g3(n) + NB + '₸'; }
  function dec(x, d) { var s = Math.abs(x).toFixed(d).split('.'); return (x < 0 ? '−' : '') + s[0].replace(/\B(?=(\d{3})+(?!\d))/g, NB) + (d ? ',' + s[1] : ''); }
  function plural(n, a, b, c) { var d = n % 10, h = n % 100; return d === 1 && h !== 11 ? a : d >= 2 && d <= 4 && (h < 12 || h > 14) ? b : c; }
  function niceStep(x) { var p = Math.pow(10, Math.floor(Math.log10(x))), f = x / p; return (f <= 1 ? 1 : f <= 2 ? 2 : f <= 2.5 ? 2.5 : f <= 5 ? 5 : 10) * p; }
  function shortMoney(v) { return v === 0 ? '0' : v >= 1e6 ? dec(v / 1e6, v % 1e6 ? 1 : 0) + NB + 'млн' : v >= 1000 ? dec(v / 1000, 0) + NB + 'тыс' : dec(v, 0); }
  function net(x) { return (+x.sold || 0) - (+x.ret || 0); }
  function draw() {
    var W = Math.max(300, Math.round(box.clientWidth - 16)), H = 220, L = 60, R = 8, T = 14, B = 40;
    var max = Math.max.apply(null, days.map(net).concat([1]));
    var step = niceStep(max / 4), top = Math.ceil(max / step) * step, pw = W - L - R, ph = H - T - B, band = pw / days.length, bw = Math.min(26, band * 0.62);
    var total = days.reduce(function (s, x) { return s + net(x); }, 0);
    var h = '<svg viewBox="0 0 ' + W + ' ' + H + '" width="' + W + '" height="' + H + '" role="img" aria-label="Итого за минусом возвратов по дням, всего ' + money(total) + '. Подробно — в таблице ниже.">' +
      '<defs><pattern id="hatch" width="6" height="6" patternUnits="userSpaceOnUse" patternTransform="rotate(45)"><rect width="6" height="6" fill="#F3E2C2"/><rect width="2.4" height="6" fill="#A8692B"/></pattern>' +
      '<pattern id="hatch-on" width="6" height="6" patternUnits="userSpaceOnUse" patternTransform="rotate(45)"><rect width="6" height="6" fill="#EBCB95"/><rect width="2.6" height="6" fill="#83501E"/></pattern></defs>';
    for (var v = 0; v <= top + 1; v += step) {
      var y = T + ph - v / top * ph;
      h += '<line class="' + (v ? 'gl' : 'base') + '" x1="' + L + '" x2="' + (W - R) + '" y1="' + y.toFixed(1) + '" y2="' + y.toFixed(1) + '"/><text class="yl" x="' + (L - 10) + '" y="' + (y + 4).toFixed(1) + '" text-anchor="end">' + shortMoney(v) + '</text>';
    }
    var every = band < 15 ? 3 : band < 28 ? 2 : 1;
    days.forEach(function (x, i) {
      var val = Math.max(0, net(x)), cx = L + i * band + band / 2, hh = val / top * ph, y2 = T + ph - hh, bx = cx - bw / 2, r = Math.min(4, hh);
      h += '<g class="col" data-i="' + i + '"><rect class="hit" x="' + (L + i * band).toFixed(1) + '" y="' + T + '" width="' + band.toFixed(1) + '" height="' + ph + '"/>';
      if (hh > 0) h += '<path class="b' + (x.d === TODAY ? ' today' : '') + '" d="M' + bx.toFixed(1) + ' ' + (T + ph) + 'V' + (y2 + r).toFixed(1) + 'q0 -' + r.toFixed(1) + ' ' + r.toFixed(1) + ' -' + r.toFixed(1) + 'h' + (bw - 2 * r).toFixed(1) + 'q' + r.toFixed(1) + ' 0 ' + r.toFixed(1) + ' ' + r.toFixed(1) + 'V' + (T + ph) + 'z"/>';
      h += '</g>';
      if (i % every === 0 || x.d === TODAY) {
        var dd = pd(x.d);
        h += '<text class="xl' + (x.d === TODAY ? ' wk' : '') + '" x="' + cx.toFixed(1) + '" y="' + (T + ph + 17) + '" text-anchor="middle">' + dd.getUTCDate() + '</text><text class="xl" x="' + cx.toFixed(1) + '" y="' + (T + ph + 32) + '" text-anchor="middle">' + WD[dd.getUTCDay()] + '</text>';
      }
    });
    box.innerHTML = h + '</svg><div class="tip" id="rp-tip" hidden></div>';
    var tip = $('rp-tip'), svg = box.querySelector('svg');
    function show(g) {
      box.querySelectorAll('.col.is-on').forEach(function (x) { x.classList.remove('is-on'); });
      if (!g) { tip.hidden = true; return; }
      g.classList.add('is-on');
      var i = +g.getAttribute('data-i'), x = days[i], sc = svg.getBoundingClientRect().width / W, d = pd(x.d), n = +x.n || 0;
      tip.innerHTML = WDL[d.getUTCDay()].charAt(0).toUpperCase() + WDL[d.getUTCDay()].slice(1) + ', ' + d.getUTCDate() + NB + MON[d.getUTCMonth()] + '<b>' + money(net(x)) + '</b>' +
        (n ? n + NB + plural(n, 'заказ', 'заказа', 'заказов') + ' · ' + g3(+x.pcs || 0) + NB + 'шт' : 'выдач не было') + (+x.ret ? ' · возврат ' + money(+x.ret) : '');
      tip.hidden = false;
      var tw = tip.offsetWidth, cx = (L + i * band + band / 2) * sc + 4;
      tip.style.left = Math.min(Math.max(cx, tw / 2 + 4), box.clientWidth - tw / 2 - 4) + 'px';
      tip.style.top = Math.max(0, (T + ph - Math.max(0, net(x)) / top * ph) * sc - 8) + 'px';
    }
    box.onmousemove = function (e) { show(e.target.closest('.col')); };
    box.onmouseleave = function () { show(null); };
  }
  draw();
  var rt;
  window.addEventListener('resize', function () { clearTimeout(rt); rt = setTimeout(draw, 150); });
})();
