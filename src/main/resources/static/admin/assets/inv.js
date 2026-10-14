/* Накладная в карточке заказа (invoiceDlg из макета) — по тем же правилам, что PDF и приложение (AdminInvoice):
   «По сумме» — бланк 1С «Расходная накладная», ниже возвраты (в возврате поставщик — клиент) и «Итого к оплате»;
   «Объём и масса» — тот же бланк с коробками, объёмом и массой, как packing_pdf.dart в приложении. У товаров с двумя
   размерами коробки — выбор, он запоминается для клиента в этом браузере (в приложении — на телефоне). Числа — как
   на бланке: 1,234.50. PDF «по сумме» строит сервер (с документами 1С); «Объём и масса» — печать из окна. */
(function () {
  'use strict';
  var $ = Admin.$, esc = Admin.esc, icon = Admin.icon;
  var el = $('inv-data'), btn = $('inv-open');
  if (!el || !btn) return;
  var D; try { D = JSON.parse(el.textContent); } catch (e) { return; }
  var MON = ['января', 'февраля', 'марта', 'апреля', 'мая', 'июня', 'июля', 'августа', 'сентября', 'октября', 'ноября', 'декабря'];
  /* «#,##0.00» в Locale.US — как InvoicePdfService и приложение */
  function us(x, d) { var s = Math.abs(x).toFixed(d).split('.'); return (x < 0 ? '-' : '') + s[0].replace(/\B(?=(\d{3})+(?!\d))/g, ',') + (d ? '.' + s[1] : ''); }
  function money(x) { return us(x, 2); }
  function day(s) { return +s.slice(8, 10) + ' ' + MON[+s.slice(5, 7) - 1] + ' ' + s.slice(0, 4) + ' г.'; }
  function store(k, v) { try { if (v === undefined) return localStorage.getItem(k); localStorage.setItem(k, v); } catch (e) { return null; } return null; }
  var MODE = store('inv-mode') === 'vol' ? 'vol' : 'sum';
  function packOf(l) {
    var u = l.units || [];
    if (u.length < 2) return 0;
    var saved = +store('inv-pack-' + D.client + '-' + l.id);
    return saved && +u[1] === saved ? 1 : 0;
  }
  function sign(released, received) {
    function who(n) { return esc(n ? n + ' /' : '/'); }
    return '<div class="inv-sign"><p><b>Отпустил</b><span>' + who(released) + '</span></p><p><b>Получил</b><span>' + who(received) + '</span></p></div>';
  }
  /* tot — строка «Итого» под таблицей: значения стоят ровно под своими колонками, как на бланке */
  function doc(title, head, rows, cols, tot, foot) {
    return '<h4>' + esc(title) + '</h4><dl class="inv-h">' + head.map(function (h) { return '<div><dt>' + h[0] + '</dt><dd>' + esc(h[1]) + '</dd></div>'; }).join('') + '</dl>' +
      '<table class="inv-t"><thead><tr><th class="c">№ п/п</th><th class="c">Код</th><th class="c">Товар</th><th class="c" colspan="2">Количество</th>' + cols.map(function (h) { return '<th class="c">' + h + '</th>'; }).join('') + '</tr></thead>' +
      '<tbody>' + rows.map(function (r, i) {
        return '<tr><td class="r">' + (i + 1) + '</td><td>' + esc(r.code) + '</td><td class="inv-n">' + esc(r.name) + '</td><td class="r">' + us(r.q, 3) + '</td><td class="inv-u">' + esc(r.unit) + '</td>' +
          r.tail.map(function (t) { return '<td class="r">' + t + '</td>'; }).join('') + '</tr>';
      }).join('') + '</tbody><tfoot><tr>' + tot + '</tr></tfoot></table>' + foot;
  }
  /* строки с тем же кодом, названием, единицей и ценой — одной строкой, как OneCInvoiceService.merge */
  function merged(lines) {
    var out = [], at = {};
    lines.forEach(function (l) {
      var k = l.code + '|' + l.name + '|' + l.unit + '|' + (+l.price), q = +l.q, s = +l.price * q;
      if (at[k] != null) { out[at[k]].q += q; out[at[k]].s += s; return; }
      at[k] = out.length; out.push({ code: l.code, name: l.name, unit: l.unit, price: +l.price, q: q, s: s });
    });
    return out;
  }
  function sumDoc(title, head, lines, words) {
    var rows = merged(lines), sum = rows.reduce(function (a, r) { return a + r.s; }, 0);
    return { sum: sum, html: doc(title, head, rows.map(function (r) { return { code: r.code, name: r.name, q: r.q, unit: r.unit, tail: [money(r.price), money(r.s)] }; }), ['Цена', 'Сумма'],
      '<td colspan="5"></td><td>Итого:</td><td class="r">' + money(sum) + '</td>',
      '<p class="inv-cnt">Всего наименований ' + rows.length + ', на сумму ' + money(sum) + ' теңге</p><p class="inv-words">' + esc(words) + '</p>') };
  }
  function saleHead(withStore) {
    var h = [['Поставщик', D.supplier], ['Покупатель', D.buyer], ['Основание', 'Без договора']];
    if (withStore && D.warehouse) h.push(['Склад', D.warehouse]);
    return h;
  }
  function open() {
    var two = D.lines.filter(function (l) { return (l.units || []).length > 1; });
    Admin.openDlg('<div class="dlg-in"><h2 id="dlg-h">Накладная · заказ № ' + D.id + '</h2>' +
      '<div class="inv-bar"><div class="seg" role="group" aria-label="Какая накладная" id="inv-m"><button type="button" data-m="sum">По сумме</button><button type="button" data-m="vol">Объём и масса</button></div>' +
      '<p class="hint" id="inv-h"></p></div><div id="inv-pack"></div>' +
      '<div class="inv-w"><div class="inv" id="inv-doc"></div></div>' +
      '<div class="dlg-acts"><button class="btn btn-soft" type="button" data-close>Готово</button><a class="btn btn-primary" id="inv-pdf" href="/admin/orders/' + D.id + '/invoice.pdf" target="_blank" rel="noopener">' + icon('download') + 'Скачать PDF</a>' +
      '<button class="btn btn-primary" type="button" id="inv-print">' + icon('print') + 'Печать</button></div></div>', 'is-wide');
    function draw() {
      var vol = MODE === 'vol', html;
      $('inv-m').querySelectorAll('button').forEach(function (b) { b.setAttribute('aria-pressed', String(b.getAttribute('data-m') === MODE)); });
      $('inv-h').textContent = vol ? 'Тот же бланк 1С, но коробки, объём и масса — для водителя и склада. Коробок = штук ÷ в коробке, вверх; неполная — тоже коробка.'
        : 'Бланк 1С «Расходная накладная»: цена и сумма' + (D.returns.length ? ', ниже возврат и итог к оплате.' : '.') + ' В PDF — данные документов 1С, если заказ уже там.';
      $('inv-pdf').hidden = vol; $('inv-print').hidden = !vol;
      $('inv-pack').innerHTML = vol && two.length ? '<div class="inv-pack"><p class="lbl">Упаковка — как решил клиент; в следующий раз для него подставим так же</p>' + two.map(function (l) {
        var v = packOf(l);
        return '<div class="inv-pk"><span>' + esc(l.name) + '</span><div class="seg" role="group" aria-label="Коробка: ' + esc(l.name) + '" data-k="' + l.id + '">' +
          '<button type="button" data-v="0" aria-pressed="' + (v === 0) + '">по ' + us(+l.units[0], 0) + '</button><button type="button" data-v="1" aria-pressed="' + (v === 1) + '">по ' + us(+l.units[1], 0) + '</button></div></div>';
      }).join('') + '</div>' : '';
      if (!vol) {
        var title = 'Расходная накладная № ' + D.no + ' от ' + day(D.date), sale = sumDoc(title, saleHead(D.inOneC), D.lines, D.words), res = [];
        html = sale.html + sign(D.manager, D.buyer);
        D.returns.forEach(function (r) {
          var rt = 'Возврат от покупателя № ' + r.no + ' от ' + day(r.date),
            rd = sumDoc(rt, [['Поставщик', D.buyer], ['Покупатель', D.supplier], ['Основание', 'Без договора']], r.lines, r.words);
          res.push([rt, rd.sum]);
          html += '<div class="inv-sep"></div>' + rd.html + sign(D.buyer, D.manager);
        });
        if (res.length) {
          var pay = res.reduce(function (a, r) { return a - r[1]; }, sale.sum);
          html += '<div class="inv-sep"></div><h4>Итог</h4><dl class="inv-res"><div><dt>' + esc(title) + '</dt><dd class="num">' + money(sale.sum) + '</dd></div>' +
            res.map(function (r) { return '<div><dt>' + esc(r[0]) + '</dt><dd class="num">−' + money(r[1]) + '</dd></div>'; }).join('') +
            '<div class="inv-pay"><dt>Итого к оплате:</dt><dd class="num">' + money(pay) + '</dd></div></dl><p class="inv-cnt">Сумма к оплате ' + money(pay) + ' теңге</p>' +
            '<p class="inv-words">' + esc(D.payWords) + '</p>';
        }
      } else {
        var bx = 0, vm = 0, kg = 0, miss = [], vr = D.lines.map(function (l) {
          var u = l.units || [], i = packOf(l), q = +l.q, n = null, v = null, m = null;
          if (u.length) { n = Math.ceil(q / +u[i] - 1e-9); var bv = i === 0 ? l.vol : l.vol2; if (bv != null) v = n * +bv; }
          if (l.kg != null) m = q * +l.kg; else if (String(l.unit).trim().toLowerCase() === 'кг') m = q;
          if (n != null) bx += n; if (v != null) vm += v; if (m != null) kg += m;
          if (n == null || v == null || m == null) miss.push(l.name);
          return { code: l.code, name: l.name, q: q, unit: l.unit, tail: [n == null ? '—' : String(n), v == null ? '—' : us(v, 3), m == null ? '—' : us(m, 2)] };
        });
        html = doc('Расходная накладная № ' + D.no + ' от ' + day(D.vdate), saleHead(true), vr, ['Коробок', 'Объём, м³', 'Масса, кг'],
          '<td colspan="5" class="r">Итого:</td><td class="r">' + bx + '</td><td class="r">' + us(vm, 3) + '</td><td class="r">' + us(kg, 2) + '</td>',
          '<p class="inv-cnt">Всего наименований ' + vr.length + ', коробок ' + bx + ', объём ' + us(vm, 3) + ' м³, масса ' + us(kg, 2) + ' кг</p>' +
          (miss.length ? '<p class="inv-miss">Не заданы параметры упаковки (в итог вошло не всё): ' + esc(miss.join(', ')) + '</p>' : '')) + sign(D.manager, D.buyer);
      }
      $('inv-doc').innerHTML = html;
    }
    draw();
    $('inv-m').addEventListener('click', function (e) { var b = e.target.closest('button'); if (!b) return; MODE = b.getAttribute('data-m'); store('inv-mode', MODE); draw(); });
    $('inv-pack').addEventListener('click', function (e) {
      var b = e.target.closest('button[data-v]'); if (!b) return;
      var k = b.closest('[data-k]').getAttribute('data-k'), l = D.lines.filter(function (x) { return String(x.id) === k; })[0];
      store('inv-pack-' + D.client + '-' + k, String(+l.units[+b.getAttribute('data-v')]));
      draw();
      var again = $('inv-pack').querySelector('[data-k="' + k + '"] button[data-v="' + b.getAttribute('data-v') + '"]'); if (again) again.focus();
    });
    $('inv-print').addEventListener('click', function () {
      document.body.classList.add('inv-print');
      window.print();
      setTimeout(function () { document.body.classList.remove('inv-print'); }, 300);
    });
  }
  btn.addEventListener('click', open);
})();
