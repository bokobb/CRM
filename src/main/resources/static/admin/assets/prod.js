/* Производство (часть 3 макета): график «Себестоимость по дням» (столбик — себестоимость, черта — по цене
   продажи; нажатие открывает день) и таблица; окно «Бригада» — один человек в одной бригаде за день, кто занят
   в другой заявке — отмечен её номером; «Отчёт за день» — что склад готовой продукции оприходовал, PDF — сервер.
   Данные — JSON в странице (#pd-data, #brig-data, #rep-data), сохранение — обычная форма POST. */
(function () {
  'use strict';
  var $ = Admin.$, esc = Admin.esc, icon = Admin.icon, O = window.Ops, NB = O.NB;
  var MON = ['января', 'февраля', 'марта', 'апреля', 'мая', 'июня', 'июля', 'августа', 'сентября', 'октября', 'ноября', 'декабря'];
  var WD = ['вс', 'пн', 'вт', 'ср', 'чт', 'пт', 'сб'], WDL = ['воскресенье', 'понедельник', 'вторник', 'среда', 'четверг', 'пятница', 'суббота'];
  function pd(s) { return new Date(Date.UTC(+s.slice(0, 4), +s.slice(5, 7) - 1, +s.slice(8, 10))); }
  function dml(s) { var d = pd(s); return d.getUTCDate() + NB + MON[d.getUTCMonth()]; }
  function cap(s) { return s.charAt(0).toUpperCase() + s.slice(1); }
  function money(n) { return O.money(n); }
  function pctText(x, d) { return (x > 0 ? '+' : x < 0 ? '−' : '') + O.dec(Math.abs(x), d || 0) + NB + '%'; }
  function niceStep(x) { var p = Math.pow(10, Math.floor(Math.log10(x))), f = x / p; return (f <= 1 ? 1 : f <= 2 ? 2 : f <= 2.5 ? 2.5 : f <= 5 ? 5 : 10) * p; }
  function shortMoney(v) { return v === 0 ? '0' : v >= 1e6 ? O.dec(v / 1e6, v % 1e6 ? 1 : 0) + NB + 'млн' : O.dec(v / 1000, 0) + NB + 'тыс'; }
  function store(k, v) { try { if (v === undefined) return localStorage.getItem(k); localStorage.setItem(k, v); } catch (e) { return null; } return null; }

  /* ================= график и таблица ================= */
  var D = O.json('pd-data');
  if (D && $('pd-chart')) {
    var days = D.days || [];
    days.forEach(function (x) { x.fg = +x.fg || 0; x.sale = +x.sale || 0; x.norm = +x.norm || 0; x.issued = +x.issued || 0; });
    var href = function (d) { return '/admin/production' + (d === D.today ? '' : '?date=' + d); };
    var draw = function () {
      var box = $('pd-chart');
      if (box.hidden || !days.length) return;
      var W = Math.max(300, Math.round(box.clientWidth - 16)), H = W > 640 ? 280 : 236, L = 60, R = 8, T = 14, B = 40;
      var max = Math.max.apply(null, days.map(function (x) { return Math.max(x.fg, x.sale); })) || 1;
      var step = niceStep(max / 4), top = Math.ceil(max / step) * step;
      var pw = W - L - R, ph = H - T - B, band = pw / days.length, bw = Math.min(64, band * 0.72), fg = 0;
      days.forEach(function (x) { fg += x.fg; });
      var s = '<svg viewBox="0 0 ' + W + ' ' + H + '" width="' + W + '" height="' + H + '" role="img" aria-label="Себестоимость готовой продукции по дням, ' + dml(days[0].d) + ' — ' +
        dml(days[days.length - 1].d) + ', всего ' + money(fg) + '; черта — та же продукция по цене продажи. Подробно — во вкладке «Таблица».">' +
        '<defs><pattern id="hatch" width="6" height="6" patternUnits="userSpaceOnUse" patternTransform="rotate(45)"><rect width="6" height="6" fill="#F3E2C2"/><rect width="2.4" height="6" fill="#A8692B"/></pattern>' +
        '<pattern id="hatch-on" width="6" height="6" patternUnits="userSpaceOnUse" patternTransform="rotate(45)"><rect width="6" height="6" fill="#EBCB95"/><rect width="2.6" height="6" fill="#83501E"/></pattern></defs>';
      for (var v = 0; v <= top + step / 2; v += step) {
        var y = T + ph - v / top * ph;
        s += '<line class="' + (v ? 'gl' : 'base') + '" x1="' + L + '" x2="' + (W - R) + '" y1="' + y.toFixed(1) + '" y2="' + y.toFixed(1) + '"/>' +
          '<text class="yl" x="' + (L - 10) + '" y="' + (y + 4).toFixed(1) + '" text-anchor="end">' + shortMoney(v) + '</text>';
      }
      var every = band < 15 ? 3 : band < 28 ? 2 : 1;
      days.forEach(function (x, i) {
        var cx = L + i * band + band / 2, h = x.fg / top * ph, y = T + ph - h, bx = cx - bw / 2, r = Math.min(bw > 30 ? 7 : 4, h, bw / 2), sel = x.d === D.sel;
        s += '<g class="col' + (sel ? ' is-sel' : '') + '" data-i="' + i + '"><rect class="hit" x="' + (L + i * band).toFixed(1) + '" y="' + T + '" width="' + band.toFixed(1) + '" height="' + ph + '"/>';
        if (h > 0) {
          s += '<path class="b' + (x.d === D.today ? ' today' : '') + (sel ? ' sel' : '') + '" d="M' + bx.toFixed(1) + ' ' + (T + ph) + 'V' + (y + r).toFixed(1) +
            'q0 -' + r.toFixed(1) + ' ' + r.toFixed(1) + ' -' + r.toFixed(1) + 'h' + (bw - 2 * r).toFixed(1) + 'q' + r.toFixed(1) + ' 0 ' + r.toFixed(1) + ' ' + r.toFixed(1) + 'V' + (T + ph) + 'z"/>';
        }
        if (x.sale > 0) { var ys = T + ph - x.sale / top * ph; s += '<line class="tk" x1="' + (bx - 3).toFixed(1) + '" x2="' + (bx + bw + 3).toFixed(1) + '" y1="' + ys.toFixed(1) + '" y2="' + ys.toFixed(1) + '"/>'; }
        s += '</g>';
        if (i % every === 0 || x.d === D.today || sel) {
          var dd = pd(x.d);
          s += '<text class="xl' + (x.d === D.today || sel ? ' wk' : '') + '" x="' + cx.toFixed(1) + '" y="' + (T + ph + 17) + '" text-anchor="middle">' + dd.getUTCDate() + '</text>' +
            '<text class="xl' + (dd.getUTCDay() === 1 ? ' wk' : '') + '" x="' + cx.toFixed(1) + '" y="' + (T + ph + 32) + '" text-anchor="middle">' + WD[dd.getUTCDay()] + '</text>';
        }
      });
      s += '</svg><p class="lgd" aria-hidden="true"><span class="lgd-b"></span>себестоимость<span class="lgd-t"></span>по цене продажи</p><div class="tip" id="pd-tip" hidden></div>';
      box.innerHTML = s;
      var tip = $('pd-tip'), svg = box.querySelector('svg');
      function show(g) {
        box.querySelectorAll('.col.is-on').forEach(function (x) { x.classList.remove('is-on'); });
        if (!g) { tip.hidden = true; return; }
        g.classList.add('is-on');
        var i = +g.getAttribute('data-i'), x = days[i], sc = svg.getBoundingClientRect().width / W;
        var cx = (L + i * band + band / 2) * sc + 4, y = (T + ph - Math.max(x.fg, x.sale) / top * ph) * sc + 8;
        tip.innerHTML = cap(WDL[pd(x.d).getUTCDay()]) + ', ' + dml(x.d) + (x.n ? '<b>' + money(x.fg) + '</b>по цене продажи ' + money(x.sale) +
          (x.fg ? ' · наценка ' + pctText((x.sale / x.fg - 1) * 100) : '') + '<br>сырьё: выдано ' + money(x.issued) + ', норма ' + money(x.norm)
          : '<b>—</b>' + (pd(x.d).getUTCDay() === 0 ? 'воскресенье' : 'заявок не было'));
        tip.hidden = false;
        var tw = tip.offsetWidth, bw2 = box.clientWidth;
        tip.style.left = Math.min(Math.max(cx, tw / 2 + 4), bw2 - tw / 2 - 4) + 'px';
        tip.style.top = Math.max(y, 74) + 'px';
      }
      svg.addEventListener('mouseover', function (e) { show(e.target.closest('.col')); });
      svg.addEventListener('mouseleave', function () { show(null); });
      svg.addEventListener('click', function (e) { var g = e.target.closest('.col'); if (g) location.href = href(days[+g.getAttribute('data-i')].d); });
    };
    var table = store('pd-view') === 'table';
    var setView = function () {
      $('pd-v').querySelectorAll('button').forEach(function (b) { b.setAttribute('aria-pressed', String((b.getAttribute('data-v') === 'table') === table)); });
      $('pd-chart').hidden = table; $('pd-table').hidden = !table;
      draw();
    };
    $('pd-v').addEventListener('click', function (e) {
      var b = e.target.closest('button'); if (!b) return;
      table = b.getAttribute('data-v') === 'table';
      store('pd-view', table ? 'table' : 'chart');
      setView();
    });
    setView();
    var rt = null;
    window.addEventListener('resize', function () { clearTimeout(rt); rt = setTimeout(draw, 120); });
  }

  /* ================= бригада ================= */
  var BR = O.json('brig-data');
  function brigadeDlg(id) {
    var r = BR && BR.reqs[String(id)];
    if (!r) return;
    var sel = r.sel.map(String), was = sel.slice().sort().join(), q = '';
    var emps = BR.emps, by = {};
    emps.forEach(function (e) { by[String(e.id)] = e; });
    Admin.openDlg('<form class="dlg-in" id="bd"><h2 id="dlg-h">Бригада</h2><p class="muted">№ ' + esc(id) + ' «' + esc(r.title) + '» · ' + esc(BR.day) +
      '. Один человек — в одной бригаде за день: кто уже в другой заявке этого дня, отмечен её номером.</p>' +
      '<label class="search"><span class="sr">Найти сотрудника</span>' + icon('search') + '<input class="inp" id="bd-q" type="search" placeholder="Часть имени" autocomplete="off"></label>' +
      '<div class="bpick" id="bd-l"></div><p class="hint" id="bd-s" aria-live="polite"></p>' +
      '<div class="dlg-acts"><button class="btn btn-soft" type="button" data-close>Отмена</button><button class="btn btn-primary" type="submit" id="bd-go"></button></div></form>', 'is-wide');
    function rate(e) { return e.rate == null ? null : +e.rate; }
    function draw() {
      var qq = O.norm(q);
      $('bd-l').innerHTML = [[true, 'Бригада — ставка за смену'], [false, 'Остальные — их оклад уже в накладных цеха или не входит в себестоимость']].map(function (g) {
        var list = emps.filter(function (e) { return e.brig === g[0] && (sel.indexOf(String(e.id)) >= 0 || !qq || O.norm(e.name).indexOf(qq) >= 0); });
        if (!list.length) return '';
        return '<p class="lbl">' + g[1] + '</p><div class="bp-g" role="group" aria-label="' + esc(g[1]) + '">' + list.map(function (e) {
          var on = sel.indexOf(String(e.id)) >= 0, b = r.busy[String(e.id)], off = e.off && !on, why = b ? 'в заявке № ' + b : off ? 'уволен' : rate(e) != null ? money(rate(e)) + ' за смену' : '';
          return '<button class="bp" type="button" data-e="' + e.id + '" aria-pressed="' + on + '"' + (b || off ? ' disabled' : '') + '>' + icon(on ? 'check' : 'plus') +
            '<span><b>' + esc(e.name) + '</b><small>' + esc((e.pos || '').toLowerCase()) + (why ? (e.pos ? ' · ' : '') + esc(why) : '') + '</small></span></button>';
        }).join('') + '</div>';
      }).join('') || '<p class="empty">Никого не нашли.</p>';
      var lab = 0;
      sel.forEach(function (k) { var e = by[k]; if (e && rate(e) != null) lab += rate(e); });
      $('bd-s').textContent = sel.length ? 'Выбрано ' + sel.length + (BR.labor ? ' · зарплата за смену ' + money(lab) : '') + '. Себестоимость дня пересчитается сразу.'
        : 'Никто не выбран — у заявки не будет зарплаты' + (BR.labor ? ', накладные цеха уйдут на заявки с бригадой' : '') + '.';
      $('bd-go').textContent = 'Сохранить · ' + sel.length + NB + 'чел.';
    }
    draw();
    $('bd-q').addEventListener('input', function () { q = this.value; draw(); });
    $('bd-l').addEventListener('click', function (e) {
      var b = e.target.closest('[data-e]'); if (!b || b.disabled) return;
      var k = b.getAttribute('data-e'), i = sel.indexOf(k);
      if (i >= 0) sel.splice(i, 1); else sel.push(k);
      if (q) { q = ''; $('bd-q').value = ''; }
      draw();
      var again = $('bd-l').querySelector('[data-e="' + k + '"]'); if (again) again.focus();
    });
    $('bd').addEventListener('submit', function (e) {
      e.preventDefault();
      Admin.closeDlg();
      if (was === sel.slice().sort().join()) return;
      O.post('/admin/production/requests/' + id + '/brigade', { employee: sel, back: location.pathname + location.search });
    });
  }
  document.addEventListener('click', function (e) {
    var b = e.target.closest('[data-brig]');
    if (b) brigadeDlg(b.getAttribute('data-brig'));
  });

  /* ================= отчёт за день ================= */
  var REP = O.json('rep-data');
  if (REP && $('pd-rep')) $('pd-rep').addEventListener('click', function () {
    var rows = REP.rows || [], d = REP.date, ds = d.slice(8, 10) + '.' + d.slice(5, 7) + '.' + d.slice(0, 4), tn = 0, tf = 0;
    var body = rows.map(function (a, i) {
      var dv = +a.dev; tn += +a.norm; tf += +a.fact;
      return '<tr><td class="r">' + (i + 1) + '</td><td class="inv-n">' + esc(a.name) + (a.n > 1 ? ' <small class="muted">(' + a.n + ' оприх.)</small>' : '') + '</td><td class="r">' + O.qn(+a.b) +
        '</td><td class="r">' + O.g3(+a.norm) + '</td><td class="r"><b>' + O.g3(+a.fact) + '</b></td><td class="r ' + (dv < 0 ? 'pay-bad' : dv > 0 ? 'ok-t' : '') + '"><b>' +
        (dv > 0 ? '+' : dv < 0 ? '−' : '') + O.g3(Math.abs(dv)) + '</b></td><td class="inv-u">' + esc(a.unit || 'шт') + '</td></tr>';
    }).join('');
    Admin.openDlg('<div class="dlg-in"><h2 id="dlg-h">Отчёт по производству</h2><p class="muted">Что склад готовой продукции оприходовал за день — по товарам, из всех заявок. PDF — тот же, что в приложении.</p>' +
      '<div class="inv-w"><div class="inv"><h4>Отчёт по производству за ' + ds + '</h4>' +
      (rows.length ? '<table class="inv-t"><thead><tr><th class="c">№</th><th class="c">Товар</th><th class="c">Замесов</th><th class="c">Норма</th><th class="c">Факт</th><th class="c">Отклонение</th><th class="c">Ед.</th></tr></thead><tbody>' + body + '</tbody></table>' +
        '<p class="inv-cnt">Всего позиций: ' + rows.length + ' · по норме ' + O.g3(tn) + NB + 'шт, оприходовано ' + O.g3(tf) + NB + 'шт (' + (tf >= tn ? '+' : '−') + O.g3(Math.abs(tf - tn)) + ')</p>'
        : '<p class="inv-cnt">За этот день ничего не оприходовано.</p>') + '</div></div>' +
      '<div class="dlg-acts"><button class="btn btn-soft" type="button" data-close>Готово</button>' +
      (rows.length ? '<a class="btn btn-primary" id="pr-pdf" href="/admin/requests/report.pdf?date=' + d + '" target="_blank" rel="noopener">' + icon('download') + 'Скачать PDF</a>'
        : '<button class="btn btn-primary" type="button" disabled>' + icon('download') + 'Скачать PDF</button>') + '</div></div>', 'is-wide');
  });
})();
