// Бургер-меню сайдбара на узких экранах (см. style.css .nav-toggle/.nav-overlay, fragments/nav.html).
// Полноценная навигация — переход по ссылке перезагружает страницу, поэтому меню само
// закрывается; JS нужен только чтобы открыть его и закрыть по тапу на затемнение/повторный тап.
(function () {
  document.addEventListener('click', function (e) {
    var toggle = e.target.closest && e.target.closest('.nav-toggle');
    if (toggle) {
      document.querySelector('.sidebar').classList.toggle('open');
      return;
    }
    var overlay = e.target.closest && e.target.closest('.nav-overlay');
    if (overlay) {
      document.querySelector('.sidebar').classList.remove('open');
    }
  });
})();
