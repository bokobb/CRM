// Анимация "пузырьков" при клике на .btn — см. style.css (.btn.animate, @keyframes crmBtn*Bubbles).
// Один делегированный обработчик на document, чтобы работало для всех кнопок, включая те,
// что появляются в DOM позже (динамические формы, Turbo-навигация в магазине).
(function () {
  function animate(target) {
    target.classList.remove('animate');
    // force reflow, чтобы повторный клик перезапускал анимацию с нуля
    void target.offsetWidth;
    target.classList.add('animate');
    setTimeout(function () { target.classList.remove('animate'); }, 750);
  }

  document.addEventListener('click', function (e) {
    var btn = e.target.closest && e.target.closest('.btn');
    if (btn) animate(btn);
  });
})();
