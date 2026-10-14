/* Окно «Новый клиент» / «Изменить клиента» (clientDlg из макета). Разметка — <template id="dlg-client">
   (admin/clients/dlg.html), телефоны для проверки на повтор — #cd-phones, данные для «Изменить» — #cd-client.
   Кнопки и ссылки [data-client-dlg="new|edit"] открывают окно; без JS ссылка ведёт на страницу формы.
   На страницах клиентов форма уходит обычной отправкой (сервер проверяет то же самое), в форме заказа —
   ClientDlg.open(null, onSave): JSON на /admin/clients/json, и новый клиент сразу выбирается в заказе. */
(function () {
  'use strict';
  var $ = Admin.$, esc = Admin.esc;
  function json(id) { var el = $(id); if (!el) return null; try { return JSON.parse(el.textContent); } catch (e) { return null; } }
  var PHONES = json('cd-phones') || [];

  function digits(s) { return String(s == null ? '' : s).replace(/\D/g, ''); }
  /* последние 10 цифр — «8 700 …» и «+7 700 …» один номер (как на сервере) */
  function phoneKey(s) { var d = digits(s); return d.length >= 10 ? d.slice(-10) : d; }
  /* «+7 700 000 00 11» или null, если это не +7 и 10 цифр */
  function phoneFmt(s) {
    var d = digits(s);
    if (d.length === 11 && (d.charAt(0) === '8' || d.charAt(0) === '7')) d = '7' + d.slice(1);
    else if (d.length === 10) d = '7' + d;
    else return null;
    return '+7 ' + d.slice(1, 4) + ' ' + d.slice(4, 7) + ' ' + d.slice(7, 9) + ' ' + d.slice(9, 11);
  }

  var dlg = $('dlg');
  if (dlg) dlg.addEventListener('close', function () { dlg.classList.remove('is-wide'); });

  /* c — клиент для изменения ({id, name, contactPerson, phone, defaultPayment, bin, email, address}), без id —
     что подставить в нового (например, набранное в поиске), или null; onSave(client) — сохранить без ухода
     со страницы, client — {key, org, person, phone, pay} */
  function open(c, onSave) {
    var t = $('dlg-client');
    if (!t || !dlg) return false;
    var d = Admin.openDlg(t.innerHTML);
    d.classList.add('is-wide');
    var f = d.querySelector('#cd'), go = d.querySelector('#cd-go'), box = d.querySelector('#cd-e');
    var id = c && c.id ? c.id : null, oldKey = id ? phoneKey(c.phone) : '';
    if (c) {
      ['name', 'contactPerson', 'phone', 'bin', 'email', 'address'].forEach(function (k) { f.elements[k].value = c[k] || ''; });
      var r = f.querySelector('input[name="defaultPayment"][value="' + (c.defaultPayment || 'CASH') + '"]');
      if (r) r.checked = true;
    }
    if (id) {
      d.querySelector('#dlg-h').textContent = 'Изменить клиента';
      go.textContent = 'Сохранить';
      f.setAttribute('action', '/admin/clients/' + id);
    }
    if (!onSave) f.insertAdjacentHTML('afterbegin', Admin.csrfInput());
    var ph = f.elements.phone;
    ph.addEventListener('blur', function () { var v = phoneFmt(ph.value); if (v && !(id && phoneKey(ph.value) === oldKey)) ph.value = v; });
    (f.elements.name.value && !f.elements.phone.value ? f.elements.phone : f.elements.name).focus();

    function show(errs, bad) {
      ['name', 'phone', 'bin'].forEach(function (n) { f.elements[n].classList.toggle('is-bad', bad.indexOf(n) >= 0); });
      box.innerHTML = errs.map(function (x) { return '<span>' + esc(x) + '</span>'; }).join('');
      box.hidden = !errs.length;
      if (bad.length) f.elements[bad[0]].focus();
    }
    f.addEventListener('submit', function (e) {
      var v = function (n) { return f.elements[n].value.trim(); };
      var name = v('name'), phone = v('phone'), key = phoneKey(phone), bin = v('bin').replace(/\s/g, '');
      var errs = [], bad = [];
      if (!name) { errs.push('Укажите название магазина или имя покупателя.'); bad.push('name'); }
      if (!phone) {
        if (!id || oldKey) { errs.push('Телефон: +7 и ещё 10 цифр — по нему клиент входит на сайт.'); bad.push('phone'); }
      } else if (!(id && key === oldKey)) {
        if (!phoneFmt(phone)) { errs.push('Телефон: +7 и ещё 10 цифр.'); bad.push('phone'); }
        else {
          var dup = PHONES.filter(function (x) { return x.key === key && x.id !== id; })[0];
          if (dup) { errs.push('Этот номер уже у клиента ' + dup.name + '. По номеру входят на сайт, поэтому у двух клиентов он совпадать не может.'); bad.push('phone'); }
        }
      }
      if (bin && !/^\d{12}$/.test(bin)) { errs.push('БИН или ИИН — 12 цифр.'); bad.push('bin'); }
      if (errs.length) { e.preventDefault(); show(errs, bad); return; }
      go.disabled = true;
      if (!onSave) return;                                   // обычная отправка формы
      e.preventDefault();
      var body = {};
      ['name', 'contactPerson', 'phone', 'bin', 'email', 'address'].forEach(function (k) { body[k] = v(k); });
      body.defaultPayment = (f.querySelector('input[name="defaultPayment"]:checked') || {}).value || 'CASH';
      var h = { 'Content-Type': 'application/json', 'Accept': 'application/json' };
      if (Admin.csrf.header) h[Admin.csrf.header] = Admin.csrf.token;
      fetch(id ? '/admin/clients/' + id + '/json' : '/admin/clients/json', { method: 'POST', credentials: 'same-origin', headers: h, body: JSON.stringify(body) })
        .then(function (res) {
          var ct = res.headers.get('Content-Type') || '';
          if (ct.indexOf('json') < 0) throw new Error('not json');
          return res.json().then(function (j) { return { ok: res.ok, j: j }; });
        })
        .then(function (r) {
          go.disabled = false;
          if (!r.ok) { show([r.j.message || 'Не получилось сохранить клиента.'], []); return; }
          var saved = r.j;
          PHONES = PHONES.filter(function (x) { return x.id !== saved.key; });
          if (saved.phone) PHONES.push({ id: saved.key, name: saved.org, key: phoneKey(saved.phone) });
          Admin.closeDlg();
          Admin.toast(id ? 'Данные клиента сохранены' : 'Добавили клиента: ' + saved.org);
          onSave(saved);
        })
        .catch(function () {
          go.disabled = false;
          show(['Не получилось сохранить — проверьте связь и попробуйте ещё раз.'], []);
        });
    });
    return true;
  }

  /* до обработчика «Сохранить изменения?» (admin.js): окно не уводит со страницы */
  document.addEventListener('click', function (e) {
    var b = e.target.closest('[data-client-dlg]');
    if (!b || e.ctrlKey || e.metaKey || e.shiftKey) return;
    var c = b.getAttribute('data-client-dlg') === 'edit' ? json('cd-client') : null;
    if (open(c, null)) { e.preventDefault(); e.stopPropagation(); }
  }, true);

  window.ClientDlg = { open: open, phoneFmt: phoneFmt };
})();
