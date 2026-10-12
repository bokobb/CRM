# Задание: две новые страницы — «Контакты» с картой и «Вакансии»

Задание для Claude Code в проекте CRM «Юг Сладости». Сначала прочитай `CLAUDE.md`; его правила важнее этого текста.
Это задание **заменяет `ZADANIE-vakansii-i-karta.md`**: вакансии и карту на главную страницу не добавлять.
Образец вида — макет «Контакты и вакансии» и файлы `stranicy/kontakty.html`, `stranicy/vakansii.html` в архиве
(открываются в браузере без сервера).

## Главное условие: главную страницу не трогать

Пока владелец не разрешит, главная остаётся как есть. Это значит:

- не менять шаблон главной, её стили и скрипты — даже без видимых изменений: шапку и подвал в общий фрагмент
  сейчас не выносить;
- ссылок на новые страницы с главной пока нет. На них попадают по прямой ссылке: из Instagram, из 2ГИС,
  из объявлений о работе;
- перед сдачей сравни HTML главной до и после: он должен совпасть.

## Что получится

| Адрес | Что на странице |
|---|---|
| `/contacts` | Заголовок «Контакты», полоса контактов (как на главной), карта с меткой у ворот и карточкой «Как добраться» (время работы, «Маршрут в 2ГИС», «Яндекс Карты»). Ниже, на светлом фоне, — «Закажите онлайн или оставьте номер» с формой «Перезвоните мне», как на главной. |
| `/vacancies` | Жёлтая шапка «Ищем людей в команду» с номером для звонков. Ниже — вакансии из CRM карточками, строка «Не нашли свою вакансию?» и окно отклика. |

- У новых страниц своя шапка: «Каталог», «Как заказать», «О кондитерской» ведут на разделы главной
  (`/#catalog`, `/#how`, `/#about`), «Вакансии» и «Контакты» — на новые страницы. Текущая страница отмечена в меню.
  В пяти пунктах меню уходит в бургер уже на ширине 1080.
- Под шапкой — «Главная / Контакты» или «Главная / Вакансии»: путь назад на главную.
- Подвал — как на главной, в «Разделах» добавлены «Вакансии» и «Контакты».

Если адреса страниц в проекте принято называть иначе — делай по соглашениям, но скажи владельцу итоговые адреса.

## Спроси у владельца до начала

1. **Открытые вакансии**: название, строка под ним, график, опыт, зарплата (можно не указывать), три коротких списка.
   Вакансии, графики и зарплаты в макете — примеры, на сайт их не переносить.
2. **Номер для звонков по вакансиям.** В макете стоит +7 775 005 02 30.
3. **Точку ворот производства.** Владелец уже прислал карточку «Юг Сладости» в 2ГИС:
   `https://2gis.kz/shymkent/geo/70000001110580791`. В ней нет адреса, только «Шымкент», поэтому ни метку,
   ни маршрут по ней не сделать. Нужна точка: в 2ГИС на телефоне нажать и подержать палец на воротах →
   «Поделиться» → прислать ссылку. Заодно посоветуй владельцу вписать адрес производства в саму карточку 2ГИС:
   сейчас по ней не найти, куда ехать.
4. **Дни работы.** В карточке 2ГИС указано «с 07:00 до 18:00, обед с 12:00 до 13:00», дни недели не видны.
5. **Кто в CRM видит отклики.** В откликах персональные данные (имя, телефон), поэтому доступ — только тем,
   кто занимается наймом.

Пока точки ворот нет, на «Контактах» показывай полосу контактов и карточку «Как добраться» без карты:
у `.map-box` класс `no-map`.
Кнопки в это время ищут адрес: 2ГИС — `https://2gis.kz/shymkent/search/улица%20Жусипбека%20Елебекова%2C%20128`,
Яндекс — `https://yandex.kz/maps/?text=Шымкент%2C%20улица%20Жусипбека%20Елебекова%2C%20128`.

---

## 1. Общее для двух страниц

### 1.1. Страницы и шаблоны

- Два адреса `GET /contacts` и `GET /vacancies`, по шаблону на каждый. Шапку и подвал вынеси в общий фрагмент,
  но подключают его только новые страницы.
- Вакансии выводи на сервере, прямо в HTML: поисковики и превью в мессенджерах видят текст. Если в проекте
  страницы собираются только скриптом — делай так же, как главная.
- Добавь обе страницы в `sitemap.xml`, если он есть.

### 1.2. Стили

- Если стили главной лежат в отдельном файле — подключи его на новых страницах как есть, без правок.
  Если они внутри шаблона главной — скопируй их в общий файл для новых страниц; главную на него не переводи.
- Всё новое — в отдельном файле `pages.css` (раздел 4). Его подключают только новые страницы, поэтому правило
  «бургер с 1080» и остальное на главную не действует.

### 1.3. `<head>`

| | `/contacts` | `/vacancies` |
|---|---|---|
| `<title>` | Контакты — «Юг Сладости», кондитерская в Шымкенте | Вакансии — «Юг Сладости», Шымкент |
| `description` | Телефоны, Instagram и адрес производства «Юг Сладости» в Шымкенте, ж/м Исфиджаб. Карта и маршрут в 2ГИС и Яндекс Картах. | Работа на кондитерском производстве «Юг Сладости» в Шымкенте: открытые вакансии, график и зарплата. Откликнуться можно прямо на сайте. |
| `og:image` | `img/og-kontakty.png` | `img/og-vakansii.png` |

- Картинки-превью 1200×630 лежат в архиве (`stranicy/img/`). В `og:image` указывай полный адрес: так их покажут
  WhatsApp, Telegram и Instagram под ссылкой.
- Ещё нужны `og:title` и `og:description` (тот же текст), `og:type` = `website`, `og:url` и `<link rel="canonical">`
  с полным адресом страницы.

### 1.4. Шапка, путь назад и подвал

```html
<a class="skip" href="#content">Перейти к содержанию</a>
<header class="top" id="top">
  <div class="wrap top-in">
    <a class="brand" href="/" aria-label="Юг Сладости — на главную"><img src="/promo/img/logo.svg" alt="Юг Сладости. Кондитерская" width="384" height="223"></a>
    <nav class="nav" aria-label="Разделы">
      <a href="/#catalog">Каталог</a>
      <a href="/#how">Как заказать</a>
      <a href="/#about">О кондитерской</a>
      <a href="/vacancies">Вакансии</a>              <!-- на своей странице: aria-current="page" -->
      <a href="/contacts">Контакты</a>
    </nav>
    <div class="top-actions">
      … кнопка «Заказать онлайн» и бургер — как на главной …
    </div>
  </div>
  <nav class="mnav" id="mnav" aria-label="Разделы" hidden> … те же пять ссылок … </nav>
</header>

<!-- первая строка содержимого страницы -->
<nav class="crumbs" aria-label="Вы здесь"><a href="/">Главная</a><span aria-current="page">Контакты</span></nav>
```

Путь к логотипу — тот, по которому он лежит на сайте. Подвал скопируй с главной; в «Разделах» те же пять ссылок,
что в шапке. Скрипт бургера — как на главной.

---

## 2. Страница «Контакты»

### 2.1. Разметка

Полоса контактов и форма «Перезвоните мне» — те же, что на главной: скопируй разметку и обработчик отправки.
Главную не меняй. Время работы — из ответа владельца; если в проекте есть настройки контактов, храни его там.

```html
<main id="content">
<section class="section contact contact-page">
  <span class="wheat" aria-hidden="true"></span>
  <div class="wrap contact-page-in">
    <div class="page-title">
      <nav class="crumbs" aria-label="Вы здесь"><a href="/">Главная</a><span aria-current="page">Контакты</span></nav>
      <h1>Контакты</h1>
      <p class="lead">Телефоны, Instagram и адрес производства. Маршрут откроется в&nbsp;2ГИС или Яндекс Картах.</p>
    </div>
    <ul class="contact-list"> … четыре пункта, как на главной … </ul>
    <div class="map-box" id="map">
      <div class="map">
        <div class="map-gl" role="region" aria-label="Карта: производство «Юг Сладости», Шымкент, ж/м Исфиджаб, улица Жусипбека Елебекова, 128"></div>
        <div class="map-pin" aria-hidden="true">
          <span class="map-tag"><img src="/promo/img/logo.svg" alt="" width="384" height="223"><span>производство</span></span>
          <span class="map-dot"></span>
        </div>
        <p class="map-fail">Карта не загрузилась. Маршрут откроется в 2ГИС или Яндекс Картах.</p>
      </div>
      <div class="map-go">
        <p class="map-go-h">Как добраться</p>
        <p class="map-go-t">Работаем с&nbsp;07:00 до&nbsp;18:00, <span class="nw">обед с&nbsp;12:00 до&nbsp;13:00</span></p>
        <div class="map-go-b">
          <a class="btn btn-primary btn-sm" href="https://2gis.kz/shymkent/directions/points/%7C{LON}%2C{LAT}" target="_blank" rel="noopener">Маршрут в 2ГИС</a>
          <a class="btn btn-line btn-sm" href="https://yandex.kz/maps/?rtext=~{LAT}%2C{LON}&amp;rtt=auto" target="_blank" rel="noopener">Яндекс Карты</a>
        </div>
      </div>
    </div>
  </div>
</section>

<section class="section contact contact-call">
  <div class="wrap contact-in">
    <div class="contact-copy">
      <p class="eyebrow">Сотрудничество</p>
      <h2>Закажите онлайн или оставьте номер</h2>
      <p class="lead">Быстрее всего — оформить заказ онлайн. Хотите сначала обсудить условия — оставьте номер, мы перезвоним.</p>
      … кнопка «Заказать онлайн», как на главной …
    </div>
    <form class="form" id="lead" novalidate> … форма «Перезвоните мне», как на главной … </form>
  </div>
</section>
</main>
```

Панель «Заказать онлайн» внизу экрана на телефоне (`.mbar`) — как на главной.

### 2.2. Ссылки «Маршрут»

Координаты пиши через точку, шесть знаков после неё. Порядок у сервисов разный:

- 2ГИС — сначала долгота, потом широта: `https://2gis.kz/shymkent/directions/points/%7C{LON}%2C{LAT}`;
- Яндекс Карты — сначала широта, потом долгота: `https://yandex.kz/maps/?rtext=~{LAT}%2C{LON}&rtt=auto`.

Обе кнопки проверь сам: маршрут должен вести к воротам.

### 2.3. Карта

- MapLibre GL JS последней стабильной версии 5.x, версию зафиксируй. Файлы `dist/maplibre-gl.js` и
  `dist/maplibre-gl.css` положи к статике сайта (например, `/promo/vendor/`), с чужого CDN не подключай.
- Тайлы — OpenFreeMap, стиль `positron`: бесплатно, без ключа и регистрации, коммерческое использование разрешено.
  Гарантий доступности нет, поэтому при ошибке вместо карты показывается строка «Карта не загрузилась…»,
  а кнопки остаются.
- Строку авторства карты (OpenFreeMap, OpenMapTiles, OpenStreetMap) не прятать: MapLibre ставит её сам.
- Карта загружается, когда до неё докрутили (за 600 px). На телефоне страница листается одним пальцем,
  карта двигается двумя; на компьютере масштаб — колесом с нажатой Ctrl (`cooperativeGestures`).
- Если на сайте настроен CSP, разреши `https://tiles.openfreemap.org` в `connect-src` и `img-src`, а `blob:` — в `worker-src`.
- Прежде чем делать, открой `https://www.openstreetmap.org/#map=17/{LAT}/{LON}`. Если вокруг ворот нарисованы
  улицы и дома — делай так. Если пусто, покажи снимок владельцу и предложи запасной вариант: карту из конструктора
  2ГИС (makemap.2gis.ru) или Яндекса (yandex.ru/map-constructor) в том же блоке `.map`. Кнопки и карточка
  «Как добраться» при этом остаются.
- Функция `brand` перекрашивает стиль `positron` в цвета сайта. Это отправная точка: открой карту рядом с макетом
  и подправь цвета слоёв на глаз. Ключи русских подсказок `CooperativeGesturesHandler.*` сверь с документацией
  выбранной версии.
- Рисунок улиц из макета (`canvas`) и подпись «Схема для макета…» на сайт не переносить.

```js
/* Карта на странице «Контакты». Загружается, когда до неё докрутили; метка — у ворот производства. */
(function () {
  var box = document.querySelector('.map');
  if (!box) return;
  var LON = 0, LAT = 0;                 // ворота производства — из ссылки владельца (6 знаков после точки)
  var PAINT = { land: '#F7F1E3', water: '#CFE3E6', park: '#E1E8C3', building: '#EBDCBD', buildingLine: '#DBC59D',
                street: '#FFFFFF', casing: '#E5D3B0', main: '#FFE39C', mainCasing: '#E8B848', label: '#76625A', halo: '#FFFFFF' };
  function brand(map) {                 // цвета сайта поверх стиля positron
    map.getStyle().layers.forEach(function (l) {
      var src = l['source-layer'] || '', id = l.id;
      try {
        if (l.type === 'background') map.setPaintProperty(id, 'background-color', PAINT.land);
        else if (l.type === 'fill' && src === 'water') map.setPaintProperty(id, 'fill-color', PAINT.water);
        else if (l.type === 'fill' && (src === 'park' || src === 'landcover')) map.setPaintProperty(id, 'fill-color', PAINT.park);
        else if (l.type === 'fill' && src === 'landuse') map.setPaintProperty(id, 'fill-color', PAINT.land);
        else if (l.type === 'fill' && src === 'building') {
          map.setPaintProperty(id, 'fill-color', PAINT.building);
          map.setPaintProperty(id, 'fill-outline-color', PAINT.buildingLine);
        } else if (l.type === 'line' && src === 'transportation') {
          var main = /motorway|trunk|primary|secondary|major/.test(id), edge = /casing|outline/.test(id);
          map.setPaintProperty(id, 'line-color', edge ? (main ? PAINT.mainCasing : PAINT.casing) : (main ? PAINT.main : PAINT.street));
        } else if (l.type === 'symbol') {
          if (l.layout && l.layout['icon-image']) { map.setLayoutProperty(id, 'visibility', 'none'); return; }   // значки заведений не нужны
          map.setLayoutProperty(id, 'text-field', ['coalesce', ['get', 'name:ru'], ['get', 'name']]);
          map.setPaintProperty(id, 'text-color', PAINT.label);
          map.setPaintProperty(id, 'text-halo-color', PAINT.halo);
        }
      } catch (e) { /* у слоя нет такого свойства — оставляем как есть */ }
    });
  }
  function fail() { box.classList.add('map-off'); }
  function start() {
    var css = document.createElement('link');
    css.rel = 'stylesheet'; css.href = '/promo/vendor/maplibre-gl.css';
    document.head.appendChild(css);
    var js = document.createElement('script');
    js.src = '/promo/vendor/maplibre-gl.js';
    js.onerror = fail;
    js.onload = function () {
      var wide = window.matchMedia('(min-width: 901px)').matches, map, loaded = false;
      try {
        map = new maplibregl.Map({
          container: box.querySelector('.map-gl'),
          style: 'https://tiles.openfreemap.org/styles/positron',
          center: [LON, LAT], zoom: 16, minZoom: 11, maxZoom: 18,
          cooperativeGestures: true,
          attributionControl: { compact: true },
          locale: {
            'CooperativeGesturesHandler.WindowsHelpText': 'Чтобы изменить масштаб, крутите колесо с нажатой Ctrl',
            'CooperativeGesturesHandler.MacHelpText': 'Чтобы изменить масштаб, крутите колесо с нажатой ⌘',
            'CooperativeGesturesHandler.MobileHelpText': 'Двигайте карту двумя пальцами'
          }
        });
      } catch (e) { fail(); return; }                 // нет WebGL — запасной вид
      map.setPadding(wide ? { left: 380, top: 90 } : { top: 90 });   // на компьютере метка правее карточки «Как добраться»
      map.addControl(new maplibregl.NavigationControl({ showCompass: false }), 'top-right');
      map.on('style.load', function () { brand(map); });
      map.on('load', function () { loaded = true; });
      new maplibregl.Marker({ element: box.querySelector('.map-pin'), anchor: 'center' }).setLngLat([LON, LAT]).addTo(map);
      setTimeout(function () { if (!loaded) { map.remove(); fail(); } }, 15000);   // тайлы не пришли
    };
    document.head.appendChild(js);
  }
  if (!('IntersectionObserver' in window)) { start(); return; }
  var io = new IntersectionObserver(function (entries) {
    if (entries.some(function (e) { return e.isIntersecting; })) { io.disconnect(); start(); }
  }, { rootMargin: '600px 0px' });
  io.observe(box);
})();
```

---

## 3. Страница «Вакансии»

### 3.1. Данные

Посмотри, как в проекте устроены товары и заявки с формы «Перезвоните мне». Названия, миграции, аудит и даты
делай по тем же соглашениям. Новая миграция Flyway — следующим номером; старые миграции не трогать.

**Вакансия**

| Поле | Что хранит |
|---|---|
| `title` | название, обязательно, до 80 символов |
| `summary` | строка под названием, до 140 символов, можно пусто |
| `schedule` | график, обязательно, до 60 символов: «5/2, 8:00–17:00», «2/2, по 12 часов» |
| `experience` | опыт, обязательно, до 60 символов: «от 1 года», «не нужен, научим» |
| `salary` | зарплата текстом, до 60 символов: «от 250 000 ₸», «180 000–220 000 ₸»; пусто — на сайте «по договорённости» |
| `duties`, `requirements`, `conditions` | «Что делать», «Кого ищем», «Что предлагаем»: текст, один пункт в строке |
| `active` | показывать на сайте |
| `sort_order` | порядок на сайте |
| даты создания и правки | как у остальных таблиц |

**Отклик**

| Поле | Что хранит |
|---|---|
| `vacancy_id` | ссылка на вакансию; пусто — кадровый резерв |
| `vacancy_title` | название вакансии на момент отклика: отклик не теряет смысл, когда вакансию переименуют или скроют |
| `name` | имя, до 80 символов, можно пусто |
| `phone` | телефон, обязательно |
| `about` | «Коротко о себе», до 1000 символов, можно пусто |
| `status` | новый · связались · собеседование · принят · отказ |
| `note` | комментарий сотрудника |
| дата | когда пришёл |

Вакансию с откликами не удалять, только скрывать (`active = false`).
Спроси владельца, сколько хранить отклики. Отклики старше этого срока удаляй задачей по расписанию.

### 3.2. CRM (Flutter)

- **«Вакансии».** Список в порядке показа на сайте. Порядок меняется перетаскиванием, переключатель «на сайте» —
  в строке. Форма вакансии — поля из таблицы; три списка — многострочные поля, «один пункт — одна строка».
- **«Отклики».** Новые сверху. Фильтр по статусу и по вакансии («резерв» — отдельным пунктом). В карточке отклика:
  имя, телефон (нажатие — звонок), вакансия, «о себе», дата, статус, комментарий. Число новых — рядом
  с пунктом меню, если в CRM уже есть такие счётчики.
- **Уведомление о новом отклике** — тем же способом, что о новой заявке с сайта.
- **Доступ** к обоим экранам — ролям, которые назовёт владелец. Если ролей нет — только администратору.

### 3.3. Приём отклика

- Отправка и защита — как у формы «Перезвоните мне»: тот же способ запроса, та же защита (CSRF, если есть),
  та же обработка ошибок. Путь — по соглашениям проекта; в скрипте ниже стоит пример `/api/public/vacancy-replies`.
- Проверки на сервере:
  - телефон — от 10 до 15 цифр;
  - имя — до 80 символов, «о себе» — до 1000;
  - `vacancyId` — пусто или активная вакансия.
  
  Ошибки — понятным текстом по-русски.
- Защита от спама. Если у формы «Перезвоните мне» она уже есть — используй её же. Если нет:
  - скрытое поле-ловушка `website`: заполненное — ответить «принято» и не сохранять;
  - не больше 5 откликов с одного адреса за 10 минут.
- На сайте не просить ИИН, документы и резюме файлом.

### 3.4. Разметка

```html
<main id="content">
<section class="page-head">
  <span class="wheat" aria-hidden="true"></span>
  <div class="wrap page-head-in">
    <nav class="crumbs" aria-label="Вы здесь"><a href="/">Главная</a><span aria-current="page">Вакансии</span></nav>
    <div class="jobs-top">
      <header class="sec-head">
        <p class="eyebrow">Вакансии</p>
        <h1>Ищем людей в команду</h1>
        <p class="sec-note">Работа на нашем производстве в&nbsp;Шымкенте, ж/м&nbsp;Исфиджаб (<a href="/contacts#map">как&nbsp;добраться</a>). Откликнитесь на вакансию, и мы перезвоним.</p>
      </header>
      <div class="jobs-call">
        <span class="c-ic" aria-hidden="true"> … иконка телефона, как в контактах … </span>
        <span class="c-t"><span class="c-l">Звонки по вакансиям</span><a class="c-v" href="tel:+77750050230">+7 775 005 02 30</a></span>
      </div>
    </div>
  </div>
</section>

<section class="section jobs jobs-page" aria-label="Открытые вакансии">
  <div class="wrap">
    <ul class="job-list">
      <!-- для каждой активной вакансии, по sort_order -->
      <li class="job">
        <div class="job-head">
          <h2 class="job-t" id="job-{id}">{title}</h2>
          <p class="job-note">{summary}</p>                       <!-- пусто — абзац не выводить -->
        </div>
        <dl class="spec"><div class="sp-time"><dt>график</dt><dd>{schedule}</dd></div><div class="sp-exp"><dt>опыт</dt><dd>{experience}</dd></div><div class="sp-pay"><dt>зарплата</dt><dd>{salary или «по договорённости»}</dd></div></dl>
        <button class="btn btn-primary btn-sm" type="button" data-apply="{id}" aria-describedby="job-{id}">Откликнуться</button>
        <details class="job-more">                                  <!-- все три списка пустые — блок не выводить -->
          <summary>Подробнее о вакансии <svg viewBox="0 0 24 24" width="18" height="18" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true"><path d="m6 9 6 6 6-6"></path></svg></summary>
          <div class="job-cols">
            <div><h3>Что делать</h3><ul><li>{пункт}</li></ul></div>     <!-- пустой список не выводить -->
            <div><h3>Кого ищем</h3><ul><li>{пункт}</li></ul></div>
            <div><h3>Что предлагаем</h3><ul><li>{пункт}</li></ul></div>
          </div>
        </details>
      </li>
    </ul>
    <!-- нет активных вакансий: вместо списка -->
    <p class="jobs-empty">Сейчас открытых вакансий нет.</p>
    <div class="strip jobs-strip">
      <p>Не нашли свою вакансию? Оставьте номер, и мы позвоним, когда появится место.</p>
      <button class="btn btn-line" type="button" data-apply="">Оставить номер</button>
    </div>
  </div>
</section>

<dialog class="apply" id="apply" aria-labelledby="apply-title">
  <form class="form" id="apply-form" novalidate>
    <button class="apply-x" type="button" id="apply-x" aria-label="Закрыть"><svg viewBox="0 0 24 24" width="20" height="20" fill="none" stroke="currentColor" stroke-width="2.2" stroke-linecap="round" aria-hidden="true"><path d="M6 6l12 12M18 6 6 18"></path></svg></button>
    <p class="eyebrow" id="apply-kind">Отклик на вакансию</p>
    <h2 class="apply-t" id="apply-title"></h2>
    <div class="field">
      <label for="apply-name">Как к вам обращаться</label>
      <input id="apply-name" name="name" type="text" autocomplete="name" maxlength="80" placeholder="Имя">
    </div>
    <div class="field">
      <label for="apply-phone">Телефон или WhatsApp</label>
      <input id="apply-phone" name="phone" type="tel" autocomplete="tel" inputmode="tel" placeholder="+7" required>
    </div>
    <div class="field">
      <label for="apply-job">Вакансия</label>
      <select id="apply-job" name="vacancyId">
        <option value="{id}">{title}</option>                    <!-- для каждой активной вакансии -->
        <option value="">Любая, в резерв</option>
      </select>
    </div>
    <div class="field">
      <label for="apply-about">Коротко о себе <span>(необязательно)</span></label>
      <textarea id="apply-about" name="about" rows="3" maxlength="1000" placeholder="Где работали, когда можете выйти"></textarea>
    </div>
    <p class="hp" aria-hidden="true"><label>Сайт <input name="website" tabindex="-1" autocomplete="off"></label></p>   <!-- ловушка для ботов, см. 3.3 -->
    <button class="btn btn-primary" type="submit">Отправить отклик</button>
    <p class="form-status" id="apply-status" role="status" hidden></p>
    <p class="form-note">Нажимая кнопку, вы соглашаетесь на обработку персональных данных.</p>
  </form>
</dialog>
</main>
```

На этой странице нижней панели «Заказать онлайн» (`.mbar`) нет: её открывают ищущие работу, а не покупатели.

### 3.5. Скрипт окна отклика

```js
/* «Откликнуться» открывает окно отклика с этой вакансией, «Оставить номер» — для кадрового резерва. */
(function () {
  var dlg = document.getElementById('apply');
  if (!dlg) return;
  var form = document.getElementById('apply-form');
  var sel = document.getElementById('apply-job');
  var title = document.getElementById('apply-title');
  var kind = document.getElementById('apply-kind');
  var status = document.getElementById('apply-status');
  var phone = document.getElementById('apply-phone');
  var send = form.querySelector('[type=submit]');
  var opener = null;
  function sync() {
    kind.textContent = sel.value ? 'Отклик на вакансию' : 'Кадровый резерв';
    title.textContent = sel.value ? sel.options[sel.selectedIndex].text : 'Позвоним, когда появится место';
  }
  function close() { if (dlg.close) dlg.close(); else { dlg.removeAttribute('open'); if (opener) opener.focus(); } }
  document.addEventListener('click', function (e) {
    var b = e.target.closest('[data-apply]');
    if (!b) return;
    opener = b;
    sel.value = b.getAttribute('data-apply');
    sync();
    status.hidden = true;
    if (dlg.showModal) dlg.showModal(); else dlg.setAttribute('open', '');
    document.getElementById('apply-name').focus();
  });
  sel.addEventListener('change', sync);
  document.getElementById('apply-x').addEventListener('click', close);
  dlg.addEventListener('click', function (e) { if (e.target === dlg) close(); });   // нажатие на затемнение вокруг окна
  dlg.addEventListener('close', function () { if (opener) opener.focus(); });
  form.addEventListener('submit', function (e) {
    e.preventDefault();
    status.hidden = false;
    if (phone.value.replace(/\D/g, '').length < 10) {
      status.textContent = 'Укажите номер телефона полностью, чтобы мы могли перезвонить.';
      phone.focus();
      return;
    }
    send.disabled = true;
    status.textContent = 'Отправляем…';
    fetch('/api/public/vacancy-replies', {            // путь, заголовки и защита — как у формы «Перезвоните мне»
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({
        vacancyId: sel.value ? Number(sel.value) : null,
        name: document.getElementById('apply-name').value.trim(),
        phone: phone.value.trim(),
        about: document.getElementById('apply-about').value.trim(),
        website: form.querySelector('[name=website]').value
      })
    }).then(function (r) {
      if (!r.ok) throw new Error(String(r.status));
      status.textContent = 'Спасибо! Отклик получили, перезвоним в рабочее время.';
      ['apply-name', 'apply-phone', 'apply-about'].forEach(function (id) { document.getElementById(id).value = ''; });
    }).catch(function () {
      status.textContent = 'Не получилось отправить. Позвоните нам: +7 775 005 02 30.';
    }).finally(function () { send.disabled = false; });
  });
})();
```

---

## 4. `pages.css` — стили только новых страниц

Подключается после стилей главной. Классы `.spec`, `.strip`, `.btn`, `.form`, `.field`, `.contact-list`, `.c-*`,
`.eyebrow`, `.sec-head`, `.lead`, `.wheat`, `.section`, `.contact`, `.contact-in`, `.contact-copy` берутся
со стилей главной — их здесь нет и менять их не нужно.

```css
/* ---- Меню и путь назад ---- */
.nav a[aria-current="page"]{background:rgba(255,255,255,.6)}
.mnav a[aria-current="page"]{background:rgba(255,255,255,.35)}
.crumbs{display:flex;flex-wrap:wrap;align-items:center;gap:4px 10px;margin:0;font-size:14.5px;line-height:1.4;color:rgba(42,23,18,.8)}
.crumbs a{font-weight:500;color:var(--ink);text-decoration:none}
.crumbs a:hover{text-decoration:underline}
.crumbs a::after{content:"/";display:inline-block;margin-left:10px;color:rgba(42,23,18,.45)}

/* ---- «Контакты»: заголовок, полоса контактов и карта на жёлтом; ниже на светлом — «Закажите онлайн» с формой ---- */
.section.contact-page{padding-top:clamp(24px,3.2vw,44px)}
.contact-page-in{position:relative;display:grid;gap:clamp(26px,3.2vw,40px)}
.page-title{display:grid;gap:14px;justify-items:start}
.contact-page h1{font-weight:800;font-size:clamp(40px,5.4vw,68px);line-height:1.02}
.section.contact-call{background:var(--paper)}

/* ---- Карта: метка у ворот, подпись с логотипом, карточка «Как добраться» ---- */
.map-box{grid-column:1/-1;position:relative}
.map{position:relative;height:clamp(340px,34vw,440px);border-radius:28px;overflow:hidden;background:#F7F1E3;box-shadow:0 26px 40px -30px rgba(80,40,0,.5)}
.map-gl{position:absolute;inset:0}
.map-pin{z-index:2}                   /* в точку метку ставит MapLibre */
.map-tag{position:absolute;left:0;bottom:24px;display:grid;justify-items:center;gap:3px;width:max-content;padding:9px 14px 8px;border-radius:16px;background:var(--white);box-shadow:0 14px 24px -12px rgba(42,23,18,.5);translate:-50% 0}
.map-tag::after{content:"";position:absolute;left:50%;bottom:-6px;width:14px;height:14px;border-radius:2px;background:var(--white);translate:-50% 0;rotate:45deg}
.map-tag img{width:104px;height:auto}
.map-tag span{font-size:12.5px;line-height:1.2;font-weight:600;color:var(--ink-soft)}
.map-dot{position:absolute;left:-12px;top:-12px;width:24px;height:24px;border-radius:50%;background:var(--crimson);border:4px solid var(--white);box-shadow:0 2px 7px rgba(42,23,18,.45)}
.map-dot::before{content:"";position:absolute;inset:-4px;border-radius:50%;background:var(--crimson);opacity:0}
.map-go{position:absolute;left:clamp(14px,2vw,24px);bottom:clamp(14px,2vw,24px);z-index:3;display:grid;gap:10px;padding:16px 18px 18px;border-radius:22px;background:var(--white);box-shadow:0 16px 30px -18px rgba(42,23,18,.45)}
.map-go-h{font:italic 600 18px/1.2 var(--font-display);color:var(--crimson-deep)}
.map-go-t{max-width:21em;margin-top:-4px;font-size:14.5px;line-height:1.4;color:var(--ink-soft)}
.nw{white-space:nowrap}
.map-go-b{display:flex;flex-wrap:wrap;gap:10px}
/* карта не загрузилась или точки ещё нет: вместо карты строка, кнопки остаются */
.map-fail{display:none}
.map-off .map-gl,.map-off .map-pin{display:none}
.map-off .map-fail{position:absolute;inset:0;display:grid;place-items:center;padding:24px;text-align:center;color:var(--ink-soft)}
.map-off{height:auto;min-height:160px}
/* точки ворот ещё нет: карты нет совсем, карточка «Как добраться» стоит под контактами */
.map-box.no-map .map{display:none}
.map-box.no-map .map-go{position:static;padding:0;background:none;box-shadow:none}

/* ---- «Вакансии»: жёлтая шапка страницы, ниже карточки вакансий на светлом поле ---- */
.page-head{position:relative;overflow:clip;background:var(--yolk)}
.page-head .wheat{right:-3%;top:-34%;width:min(30%,320px);color:var(--ink);opacity:.14;transform:rotate(-14deg)}
.page-head-in{position:relative;display:grid;gap:clamp(16px,2.2vw,26px);padding-block:clamp(22px,3vw,40px) clamp(34px,4.4vw,56px)}
.page-head .jobs-top{margin-bottom:0}
.page-head h1{font-weight:800;font-size:clamp(38px,5vw,64px);line-height:1.02}
.page-head .sec-note{max-width:44rem;color:var(--ink)}
.page-head .sec-note a{font-weight:600;color:var(--crimson-deep)}
.jobs{position:relative;overflow:clip;background:var(--paper)}
.section.jobs-page{padding-top:clamp(32px,4vw,56px)}
.jobs-top{position:relative;display:flex;flex-wrap:wrap;align-items:flex-end;justify-content:space-between;gap:20px 40px;margin-bottom:clamp(24px,3.4vw,40px)}
.jobs-top .sec-head{margin-bottom:0}
.jobs-call{display:flex;align-items:center;gap:12px;padding:10px 22px 10px 10px;border-radius:999px;background:var(--white);box-shadow:0 0 0 1px var(--line)}
.jobs-call .c-v{font:700 20px/1.2 var(--font-display);font-variant-numeric:lining-nums}
.job-list{position:relative;display:grid;gap:14px;margin:0;padding:0;list-style:none}
.job{display:grid;grid-template-columns:minmax(0,1.1fr) minmax(0,1.45fr) auto;align-items:center;column-gap:clamp(20px,3vw,44px);padding:clamp(18px,2.2vw,24px) clamp(18px,2.6vw,30px) 6px;border-radius:24px;background:var(--white);box-shadow:0 18px 30px -26px rgba(80,40,0,.55)}
.job-head{display:grid;gap:4px;min-width:0}
.job-t{font-weight:700;font-size:clamp(22px,2vw,27px);line-height:1.1;color:var(--crimson)}
.job-note{font-size:15px;line-height:1.4;color:var(--ink-soft)}
.job .spec dd{font-size:15.5px}
.job .sp-pay dd{font-size:17px;font-weight:700}
.job-more{grid-column:1/-1;margin-top:4px}
.job-more summary{display:inline-flex;align-items:center;gap:6px;min-height:44px;font-size:15px;font-weight:600;color:var(--crimson-deep);cursor:pointer;list-style:none}
.job-more summary::-webkit-details-marker{display:none}
.job-more summary:hover{text-decoration:underline}
.job-more summary svg{flex:none;transition:rotate .2s}
.job-more[open] summary svg{rotate:180deg}
.job-cols{display:grid;grid-template-columns:repeat(3,minmax(0,1fr));gap:16px clamp(20px,3vw,44px);padding:16px 0 20px;border-top:1px solid var(--line)}
.job-cols h3{margin:0 0 6px;font:italic 600 17px/1.25 var(--font-display);letter-spacing:0;color:var(--crimson-deep)}
.job-cols ul{margin:0;padding-left:18px;font-size:15.5px;line-height:1.45}
.job-cols li+li{margin-top:4px}
.jobs-empty{padding:clamp(20px,2.6vw,30px);border-radius:24px;background:var(--white);font:700 clamp(19px,1.9vw,24px)/1.2 var(--font-display)}
.jobs-strip p{font-size:clamp(19px,1.9vw,24px);max-width:30em}
.jobs-strip .btn-line:not(:hover){background:var(--white)}

/* ---- Окно отклика поверх страницы ---- */
.apply{width:min(100% - 24px,520px);max-height:calc(100dvh - 24px);margin:auto;padding:0;border:0;border-radius:28px;background:var(--white);color:var(--ink);box-shadow:0 30px 60px -20px rgba(42,23,18,.55);overflow:auto;overscroll-behavior:contain}
.apply::backdrop{background:rgba(42,23,18,.55)}
.apply .form{position:relative;gap:14px;border-radius:0;background:none;box-shadow:none}
.apply .eyebrow{padding-right:52px;font-size:17px}
.apply-t{margin-top:-6px;padding-right:44px;font-weight:800;font-size:clamp(26px,3vw,32px);line-height:1.08;color:var(--crimson)}
.apply-x{position:absolute;right:14px;top:14px;display:grid;place-items:center;width:44px;height:44px;padding:0;border:0;border-radius:50%;background:var(--paper);color:var(--ink);cursor:pointer}
.apply-x:hover{background:var(--cream)}
.field label span{font-weight:400;color:var(--ink-soft)}
.field textarea{width:100%;min-height:92px;padding:12px 16px;border-radius:14px;border:1.5px solid #8A776C;background:var(--white);color:var(--ink);font:400 16px/1.4 var(--font-body);resize:vertical}
.field textarea:focus-visible{outline:3px solid var(--crimson);outline-offset:2px;border-radius:14px}
html:has(.apply[open]){overflow:hidden}
.hp{position:absolute;left:-9999px;width:1px;height:1px;overflow:hidden}    /* ловушка для ботов: человек её не видит */

@media (max-width:1080px){
  .nav{display:none}                  /* пять разделов уже не помещаются рядом с логотипом и кнопкой */
  .burger{display:inline-flex}
  .job{grid-template-columns:minmax(0,1fr) auto;row-gap:14px}
  .job .spec{grid-column:1/-1;grid-row:2}
}
@media (max-width:900px){
  .map-go{position:static;grid-template-columns:minmax(0,1fr) auto;grid-template-areas:"h b" "t b";align-items:center;gap:2px 18px;padding:14px 0 0;background:none;box-shadow:none}
  .map-go-h{grid-area:h}
  .map-go-t{grid-area:t;max-width:none;margin:0;color:var(--ink)}
  .map-go-b{grid-area:b}
}
@media (max-width:560px){
  .job{grid-template-columns:minmax(0,1fr);padding-bottom:18px}
  .job-more{order:1;margin-top:0}     /* на телефоне подробности перед кнопкой, кнопка закрывает карточку */
  .job>.btn{order:2;width:100%}
  .job .spec{grid-auto-flow:row;grid-template-columns:minmax(0,1fr) minmax(0,1fr);gap:10px 0}
  .job .sp-pay{grid-column:1/-1;order:-1;padding:0 0 10px;border-left:0;border-bottom:1px solid var(--line)}
  .job .sp-exp{padding-left:14px;border-left:1px solid var(--line-strong)}
  .job-cols{grid-template-columns:minmax(0,1fr)}
  .map{height:300px;border-radius:22px}
  .map-tag img{width:84px}
  .map-go{grid-template-columns:minmax(0,1fr);grid-template-areas:"h" "t" "b";gap:6px}
  .map-go-b{margin-top:6px}
  .map-go-b .btn{flex:1 1 auto}
}
@media (prefers-reduced-motion:no-preference){
  .map-dot::before{animation:mpulse 2.4s ease-out infinite}   /* метка «дышит» */
  @keyframes mpulse{from{opacity:.45;scale:1}to{opacity:0;scale:2.6}}
}
@media (prefers-reduced-motion:reduce){
  .job-more summary svg{transition:none}
}
```

---

## 5. Позже, когда владелец разрешит менять главную

Тогда в шапке главной «Контакты» пусть ведёт на `/contacts` и появится «Вакансии»; в подвале — так же.
Шапку и подвал главной можно будет перевести на общий фрагмент. До этого — ничего.

## Как проверить

**Главная**

- [ ] HTML главной до и после работы совпадает; её шаблон, стили и скрипты не менялись.

**Обе страницы**

- [ ] `/contacts` и `/vacancies` открываются по прямой ссылке. В шапке отмечена текущая страница;
      «Каталог», «Как заказать», «О кондитерской» и логотип ведут на главную, к нужному разделу.
- [ ] Ширина 1081–1920 — пять разделов в шапке помещаются; 1080 и уже — бургер с пятью пунктами.
- [ ] 1920, 1440, 1024, 768, 390, 320 — без горизонтальной прокрутки.
- [ ] Ссылка на страницу, отправленная в WhatsApp или Telegram, показывает картинку-превью и описание.

**Контакты**

- [ ] Владелец подтвердил, что метка стоит на воротах; обе кнопки маршрута ведут к воротам, в том числе с телефона.
- [ ] Карта не грузится при открытии страницы (вкладка Network: запросов к `tiles.openfreemap.org` нет) и
      грузится, когда до неё докрутили. На телефоне страница листается мимо карты одним пальцем.
- [ ] Строка авторства карты видна.
- [ ] Запросы к `tiles.openfreemap.org` заблокированы (DevTools → Network request blocking) — вместо карты строка
      «Карта не загрузилась…», кнопки работают.
- [ ] Форма «Перезвоните мне» отправляет заявку так же, как на главной.
- [ ] На компьютере карточка «Как добраться» не закрывает метку; на 768 и 390 стоит под картой,
      обед в строке времени не разрывается.

**Вакансии**

- [ ] На сайте только настоящие вакансии владельца, примеров из макета нет.
- [ ] Вакансия, созданная в CRM, появляется на странице после обновления; скрытая — исчезает; порядок как в CRM.
      Нет активных вакансий — «Сейчас открытых вакансий нет» и кнопка «Оставить номер».
- [ ] Отклик приходит в CRM в «Отклики» с названием вакансии, приходит уведомление; отклик из «Оставить номер»
      помечен как резерв. Пустой или короткий телефон — подсказка в окне, отклик не уходит.
- [ ] Окно закрывается крестиком, Esc и нажатием на затемнение; фокус возвращается на кнопку;
      страница под окном не прокручивается.
- [ ] 1440 — каждая вакансия одной строкой; 768 — полоса под названием; 390 — зарплата первой,
      кнопка «Откликнуться» на всю ширину внизу карточки.
