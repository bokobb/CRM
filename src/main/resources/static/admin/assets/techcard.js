/* Техкарта (vTechcard из макета): переход по разделам с подсветкой текущего, «Расчёт на план» — сырьё под заказ
   и остатки склада (данные — #tc-plan-data: сырьё на замес, выход, упаковок в коробке), ссылка «Карта на замес»
   на нужное число замесов, «Скопировать текст» этикетки. */
(function () {
  'use strict';
  var $ = Admin.$, esc = Admin.esc, NB = ' ';
  function g3(n) { return String(Math.round(Math.abs(n))).replace(/\B(?=(\d{3})+(?!\d))/g, NB); }
  function fix(x, d) {
    var s = Math.abs(x).toFixed(d).split('.');
    return (x < 0 ? '−' : '') + s[0].replace(/\B(?=(\d{3})+(?!\d))/g, NB) + (d ? ',' + s[1] : '');
  }
  function num(x) { return Math.abs(x - Math.round(x)) < 1e-9 ? g3(x) : fix(x, Math.abs(x) < 10 ? 3 : Math.abs(x) < 100 ? 2 : 1).replace(/,?0+$/, ''); }
  function plural(n, a, b, c) { var d = n % 10, h = n % 100; return d === 1 && h !== 11 ? a : d >= 2 && d <= 4 && (h < 12 || h > 14) ? b : c; }

  /* ---- разделы: подсветка текущего ---- */
  var nav = $('tc-nav');
  if (nav) {
    var links = [].slice.call(nav.querySelectorAll('a[href^="#"]'));
    function mark(id) { links.forEach(function (a) { if (a.getAttribute('href') === '#' + id) a.setAttribute('aria-current', 'true'); else a.removeAttribute('aria-current'); }); }
    nav.addEventListener('click', function (e) { var a = e.target.closest('a[href^="#"]'); if (a) mark(a.getAttribute('href').slice(1)); });
    if ('IntersectionObserver' in window) {
      var io = new IntersectionObserver(function (ents) {
        ents.forEach(function (en) { if (en.isIntersecting) mark(en.target.id); });
      }, { rootMargin: '-140px 0px -60% 0px' });
      links.forEach(function (a) { var s = $(a.getAttribute('href').slice(1)); if (s) io.observe(s); });
    }
  }

  /* ---- расчёт на план ---- */
  var D = null;
  try { D = JSON.parse($('tc-plan-data').textContent); } catch (e) { D = null; }
  var inp = $('pl-n'), seg = $('pl-u');
  if (D && inp && seg && D.rows && D.rows.length) {
    var pressed = seg.querySelector('[aria-pressed="true"]'), unit = pressed ? pressed.getAttribute('data-u') : 'batch';
    var Y = +D.yield || 0, BOX = D.box == null ? 0 : +D.box, U = D.unit || 'шт';
    var sheet = $('pl-sheet'), sheetBase = sheet ? sheet.getAttribute('href') : '';
    function out() {
      var n = Math.max(0, +inp.value || 0), pcs = unit === 'box' ? n * BOX : unit === 'pcs' ? n : n * Y;
      var batches = Y ? pcs / Y : 0, whole = Math.ceil(batches - 1e-9), short = [];
      $('pl-r').innerHTML = n && Y ? '= ' + (BOX ? fix(pcs / BOX, pcs % BOX ? 1 : 0) + NB + 'кор. · ' : '') + g3(pcs) + NB + esc(U) + ' · <b>' + fix(batches, batches % 1 ? 2 : 0) + NB +
        plural(whole, 'замес', 'замеса', 'замесов') + '</b>' + (D.kg != null ? ' (' + fix(D.kg * batches, 1) + NB + 'кг теста)' : '') + '. Целых замесов: ' + whole + '.' : 'Введите количество.';
      $('pl-t').innerHTML = '<table class="tbl tbl--rx"><thead><tr><th>Сырьё</th><th class="r">Нужно</th><th class="r x-hide">На складе</th><th>Хватает</th></tr></thead><tbody>' +
        D.rows.map(function (x) {
          var need = (+x.q || 0) * batches, has = x.s == null ? null : +x.s, ok = has == null || has >= need - 1e-9;
          if (!ok) short.push(x.n + ' — ещё ' + num(need - has) + NB + x.u);
          return '<tr><td class="x-n">' + esc(x.n) + '</td><td class="r num x-q">' + num(need) + NB + esc(x.u) + '</td>' +
            '<td class="r num x-hide">' + (has == null ? '—' : num(has) + NB + esc(x.u)) + '</td>' +
            '<td class="x-s">' + (has == null ? '<span class="muted">нет данных</span>' : ok ? '<span class="ok-t">да</span>' : '<span class="no-t">нет, −' + num(need - has) + '</span>') + '</td></tr>';
        }).join('') + '</tbody></table>';
      var sh = $('pl-short');
      sh.hidden = !short.length;
      sh.textContent = short.length ? 'Не хватает: ' + short.join('; ') : '';
      if (sheet) sheet.setAttribute('href', sheetBase + (whole > 1 ? '?n=' + Math.min(50, whole) : ''));
    }
    inp.addEventListener('input', function () { var v = this.value.replace(/\D/g, '').slice(0, 5); if (v !== this.value) this.value = v; out(); });
    seg.addEventListener('click', function (e) {
      var b = e.target.closest('button'); if (!b) return;
      unit = b.getAttribute('data-u');
      seg.querySelectorAll('button').forEach(function (x) { x.setAttribute('aria-pressed', String(x === b)); });
      out();
    });
    out();
  }

  /* ---- этикетка: скопировать текст ---- */
  var cp = $('lb-copy'), lb = $('lb');
  if (cp && lb) cp.addEventListener('click', function () {
    var text = lb.innerText.replace(/\n{2,}/g, '\n').trim();
    var fail = function () {
      var r = document.createRange(); r.selectNodeContents(lb);
      var s = window.getSelection(); s.removeAllRanges(); s.addRange(r);
      Admin.toast('Текст выделен — скопируйте его.');
    };
    try { navigator.clipboard.writeText(text).then(function () { Admin.toast('Текст этикетки скопирован'); }, fail); } catch (e) { fail(); }
  });
})();
