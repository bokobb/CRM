/* Дашборд: столбики «Выручка» за 7, 14 или 30 дней по выбранный день из JSON в странице (#rev-data:
   {days: [{d, sum, n, rpcs}], sel, today, p}) — функция dbRevenue() из макета: выручка за вычетом возвратов,
   в подсказке — возвраты в штуках, нажатие на день открывает дашборд за этот день; переключатель
   «График · Таблица». Без библиотек. */
(function () {
  'use strict';
  var $ = Admin.$;
  var data = {};
  try { data = JSON.parse($('rev-data').textContent) || {}; } catch (e) { data = {}; }
  var days = data.days || [], SEL = data.sel || '', P = data.p || 14;
  var NB = ' ';
  var MON = ['января', 'февраля', 'марта', 'апреля', 'мая', 'июня', 'июля', 'августа', 'сентября', 'октября', 'ноября', 'декабря'];
  var WD = ['вс', 'пн', 'вт', 'ср', 'чт', 'пт', 'сб'];
  var WDL = ['воскресенье', 'понедельник', 'вторник', 'среда', 'четверг', 'пятница', 'суббота'];
  function pd(s) { return new Date(Date.UTC(+s.slice(0, 4), +s.slice(5, 7) - 1, +s.slice(8, 10))); }
  function dml(s) { var d = pd(s); return d.getUTCDate() + NB + MON[d.getUTCMonth()]; }
  function cap(s) { return s.charAt(0).toUpperCase() + s.slice(1); }
  function g3(n) { return String(Math.round(Math.abs(n))).replace(/\B(?=(\d{3})+(?!\d))/g, NB); }
  function money(n) { return (n < 0 ? '−' : '') + g3(n) + NB + '₸'; }
  function dec(x, d) {
    var s = Math.abs(x).toFixed(d).split('.');
    return (x < 0 ? '−' : '') + s[0].replace(/\B(?=(\d{3})+(?!\d))/g, NB) + (d ? ',' + s[1] : '');
  }
  function plural(n, a, b, c) { var d = n % 10, h = n % 100; return d === 1 && h !== 11 ? a : d >= 2 && d <= 4 && (h < 12 || h > 14) ? b : c; }
  function niceStep(x) {
    var p = Math.pow(10, Math.floor(Math.log10(x))), f = x / p;
    return (f <= 1 ? 1 : f <= 2 ? 2 : f <= 2.5 ? 2.5 : f <= 5 ? 5 : 10) * p;
  }
  function shortMoney(v) { return v === 0 ? '0' : v >= 1e6 ? dec(v / 1e6, v % 1e6 ? 1 : 0) + NB + 'млн' : dec(v / 1000, 0) + NB + 'тыс'; }
  var TODAY = data.today || (days.length ? days[days.length - 1].d : '');
  days.forEach(function (x) { x.sum = +x.sum || 0; x.rpcs = +x.rpcs || 0; });
  function dayHref(d) {
    var q = [];
    if (d !== TODAY) q.push('day=' + d);
    if (P !== 14) q.push('p=' + P);
    return '/admin/dashboard' + (q.length ? '?' + q.join('&') : '');
  }

  function draw() {
    var box = $('db-chart');
    if (!box || box.hidden) return;
    var total = days.reduce(function (s, x) { return s + x.sum; }, 0);
    var W = Math.max(300, Math.round(box.clientWidth - 16)), H = 236, L = 60, R = 8, T = 14, B = 40;
    var max = Math.max.apply(null, days.map(function (x) { return x.sum; }).concat([0])) || 4000;
    var step = niceStep(max / 4), top = Math.ceil(max / step) * step;
    var pw = W - L - R, ph = H - T - B, band = pw / Math.max(1, days.length), bw = Math.min(24, band * 0.62);
    var s = '<svg viewBox="0 0 ' + W + ' ' + H + '" width="' + W + '" height="' + H + '" role="img" aria-label="Выручка по дням за ' + P + ' дней, всего ' + money(total) + '. Подробно — во вкладке «Таблица». Нажмите на день, чтобы открыть его.">' +
      '<defs><pattern id="hatch" width="6" height="6" patternUnits="userSpaceOnUse" patternTransform="rotate(45)"><rect width="6" height="6" fill="#F3E2C2"/><rect width="2.4" height="6" fill="#A8692B"/></pattern>' +
      '<pattern id="hatch-on" width="6" height="6" patternUnits="userSpaceOnUse" patternTransform="rotate(45)"><rect width="6" height="6" fill="#EBCB95"/><rect width="2.6" height="6" fill="#83501E"/></pattern></defs>';
    for (var v = 0; v <= top + step / 2; v += step) {
      var y = T + ph - v / top * ph;
      s += '<line class="' + (v ? 'gl' : 'base') + '" x1="' + L + '" x2="' + (W - R) + '" y1="' + y.toFixed(1) + '" y2="' + y.toFixed(1) + '"/>' +
        '<text class="yl" x="' + (L - 10) + '" y="' + (y + 4).toFixed(1) + '" text-anchor="end">' + shortMoney(v) + '</text>';
    }
    var every = band < 15 ? 3 : band < 28 ? 2 : 1;
    days.forEach(function (x, i) {
      var cx = L + i * band + band / 2, h = x.sum / top * ph, y = T + ph - h, bx = cx - bw / 2, r = Math.min(4, h), sel = x.d === SEL;
      s += '<g class="col' + (sel ? ' is-sel' : '') + '" data-i="' + i + '"><rect class="hit" x="' + (L + i * band).toFixed(1) + '" y="' + T + '" width="' + band.toFixed(1) + '" height="' + ph + '"/>';
      if (h > 0) {
        s += '<path class="b' + (x.d === TODAY ? ' today' : '') + (sel ? ' sel' : '') + '" d="M' + bx.toFixed(1) + ' ' + (T + ph) + 'V' + (y + r).toFixed(1) +
          'q0 -' + r.toFixed(1) + ' ' + r.toFixed(1) + ' -' + r.toFixed(1) + 'h' + (bw - 2 * r).toFixed(1) + 'q' + r.toFixed(1) + ' 0 ' + r.toFixed(1) + ' ' + r.toFixed(1) +
          'V' + (T + ph) + 'z"/>';
      }
      s += '</g>';
      if (i % every === 0 || x.d === TODAY || sel) {
        var dd = pd(x.d), wk = dd.getUTCDay() === 1;
        s += '<text class="xl' + (x.d === TODAY || sel ? ' wk' : '') + '" x="' + cx.toFixed(1) + '" y="' + (T + ph + 17) + '" text-anchor="middle">' + dd.getUTCDate() + '</text>' +
          '<text class="xl' + (wk ? ' wk' : '') + '" x="' + cx.toFixed(1) + '" y="' + (T + ph + 32) + '" text-anchor="middle">' + WD[dd.getUTCDay()] + '</text>';
      }
    });
    s += '</svg><div class="tip" id="db-tip" hidden></div>';
    box.innerHTML = s;
    var tip = $('db-tip'), svg = box.querySelector('svg');
    function show(g) {
      box.querySelectorAll('.col.is-on').forEach(function (x) { x.classList.remove('is-on'); });
      if (!g) { tip.hidden = true; return; }
      g.classList.add('is-on');
      var i = +g.getAttribute('data-i'), x = days[i];
      var sc = svg.getBoundingClientRect().width / W;
      var cx = (L + i * band + band / 2) * sc + 4, y = (T + ph - x.sum / top * ph) * sc + 8;
      tip.innerHTML = cap(WDL[pd(x.d).getUTCDay()]) + ', ' + dml(x.d) + '<b>' + money(x.sum) + '</b>' +
        (x.n ? x.n + NB + plural(x.n, 'заказ выдан', 'заказа выдано', 'заказов выдано') : 'выдач не было') +
        (x.rpcs ? ' · возврат ' + g3(x.rpcs) + NB + 'шт' : '') + (x.d === TODAY ? ' · день идёт' : '');
      tip.hidden = false;
      var tw = tip.offsetWidth, bw2 = box.clientWidth;
      tip.style.left = Math.min(Math.max(cx, tw / 2 + 4), bw2 - tw / 2 - 4) + 'px';
      tip.style.top = Math.max(y, 56) + 'px';
    }
    svg.addEventListener('mouseover', function (e) { show(e.target.closest('.col')); });
    svg.addEventListener('mouseleave', function () { show(null); });
    svg.addEventListener('click', function (e) {                // нажатие открывает этот день на всём дашборде
      var g = e.target.closest('.col'); if (!g) return;
      var d = days[+g.getAttribute('data-i')].d;
      if (d !== SEL) location.href = dayHref(d);
    });
  }

  var KEY = 'admin-db-view', table = false;
  try { table = localStorage.getItem(KEY) === 'table'; } catch (e) { table = false; }
  function setView(t) {
    table = t;
    $('db-view').querySelectorAll('button').forEach(function (x) { x.setAttribute('aria-pressed', String((x.getAttribute('data-v') === 'table') === t)); });
    $('db-chart').hidden = t; $('db-table').hidden = !t;
    try { localStorage.setItem(KEY, t ? 'table' : 'chart'); } catch (e) { /* ignore */ }
    if (!t) draw();
  }
  $('db-view').addEventListener('click', function (e) {
    var b = e.target.closest('button'); if (!b) return;
    setView(b.getAttribute('data-v') === 'table');
  });
  setView(table);
  var rT = null;
  window.addEventListener('resize', function () { clearTimeout(rT); rT = setTimeout(draw, 120); });
})();
