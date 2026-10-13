/* Строки документа (возврат, закупка, поступление, списание, заявка): таблица .edit-rows,
   шаблон новой строки — <template id="row-tpl"> рядом с таблицей; кнопки data-add-row и data-del-row.
   Поля строки — обычные name="…" (массивы), сервер читает их по порядку. Итог — data-sum="qty*price". */
(function () {
  'use strict';
  function rows(t) { return t.querySelectorAll('tbody tr'); }
  function num(v) { var x = parseFloat(String(v || '').replace(/\s/g, '').replace(',', '.')); return isFinite(x) ? x : 0; }
  function g3(n) { return String(Math.round(Math.abs(n))).replace(/\B(?=(\d{3})+(?!\d))/g, ' '); }
  function recalc(t) {
    var total = 0;
    rows(t).forEach(function (tr) {
      var q = tr.querySelector('[data-q]'), p = tr.querySelector('[data-p]'), s = tr.querySelector('[data-s]');
      if (!s) return;
      var v = q && p ? num(q.value) * num(p.value) : p ? num(p.value) : 0;
      if (q && !p && tr.querySelector('select option:checked[data-price]')) v = num(q.value) * num(tr.querySelector('select option:checked').getAttribute('data-price'));
      total += v;
      s.textContent = v ? g3(v) + ' ₸' : '';
    });
    var out = document.querySelector('[data-total="' + t.id + '"]');
    if (out) out.textContent = g3(total) + ' ₸';
  }
  document.querySelectorAll('table.edit-rows').forEach(function (t) {
    var tpl = document.getElementById(t.getAttribute('data-tpl') || 'row-tpl');
    function add(focus) {
      t.querySelector('tbody').insertAdjacentHTML('beforeend', tpl.innerHTML);
      var last = rows(t)[rows(t).length - 1];
      if (focus) { var f = last.querySelector('select, input'); if (f) f.focus(); }
      recalc(t);
    }
    document.querySelectorAll('[data-add-row="' + t.id + '"]').forEach(function (b) {
      b.addEventListener('click', function () { add(true); if (window.Admin) Admin.guard.set(true); });
    });
    t.addEventListener('click', function (e) {
      var d = e.target.closest('[data-del-row]'); if (!d) return;
      d.closest('tr').remove();
      if (!rows(t).length) add(false);
      recalc(t);
      if (window.Admin) Admin.guard.set(true);
    });
    t.addEventListener('input', function () { recalc(t); });
    t.addEventListener('change', function (e) {
      var sel = e.target.closest('select');
      if (sel) {
        var opt = sel.options[sel.selectedIndex], p = sel.closest('tr').querySelector('[data-p]'), u = sel.closest('tr').querySelector('[data-unit]');
        if (opt && p && !p.value && opt.getAttribute('data-price')) p.value = opt.getAttribute('data-price');
        if (opt && u) u.textContent = opt.getAttribute('data-unit') || '';
      }
      recalc(t);
    });
    if (!rows(t).length) add(false);
    recalc(t);
  });
})();
