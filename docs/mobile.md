# Мобильное приложение и веб-версия

Flutter 3.47 (`mobile/`), один код для Android (APK) и браузера (`/app/`, на iPhone — «На экран Домой»).
Адрес сервера зашит по умолчанию (`https://yug-sladosti.kz/api`), меняется в Настройки › Подключение.

## Структура `mobile/lib`

| Папка/файл | Что |
|---|---|
| `main.dart` | запуск, Firebase, тема и компоновка, маршруты экранов |
| `navigation.dart` | стартовый экран роли (технолог → «Производство», остальные → дашборд) |
| `services/api_client.dart` | все запросы к `/api`, сессия (JWT), повтор с `Idempotency-Key` |
| `services/push_service.dart` | push: Android — подписка на тему роли; веб — токен → сервер |
| `models/` | модели ответов API |
| `screens/` | экраны (заказы, заявки, производство, техкарты, отчёты, настройки, …) |
| `widgets/` | общие виджеты: `app_drawer`, `app_bottom_nav`, `date_filter_bar`, `status_badge`, `hidden_search`, `dashboard_views`, `orders_views`, `production_merge_sheet`, `swipe_cancel_delete`, … |
| `theme/app_theme.dart` | 15 тем (`AppPalette`), `ThemeController` (день/ночь/авто) |
| `theme/app_layout.dart` | компоновки (`LayoutController`): страница дашборда, вид заказов, нижнее меню |
| `firebase_web_options.dart` | настройки Firebase для веб-версии |

## Оформление (Настройки › Оформление)

- **Режим:** Авто (как в телефоне) / День / Ночь; переключатель солнце/луна в шапке дашборда и «Ночная тема» в меню.
- **Дневные темы:** Классическая, Строгая, Тёплая бумага, Шалфей, Корпоративный синий, Офис (компактно),
  Склад (крупно, текст +15%), Лента, Доска, Минимализм.
- **Ночные темы:** Строгая ночь, Сумерки, Сепия, Графит, Тёмно-синий.
- **Компоновка:** стартовая страница дашборда; заказы — Список / Таблица (с «Итого») / Доска по статусам
  (перетаскивание карточки = «Заказ собран» / «Отгрузил», только склад ГП и админ); нижнее меню (3 раздела роли + «Ещё»).
- Выбор хранится на телефоне (`SharedPreferences`: `theme_mode`, `theme_day`, `theme_night`, `layout_*`).

Правило для разработки: цвета только через `context.pal.ink / muted / line / card / seg / accent / danger /
warn / success / info` и `context.pal.tint(c)`, иначе ломаются ночные темы.

## Полезные приёмы в интерфейсе

- Поиск «лупой»: лупа справа в шапке, набранный текст не виден, список фильтруется, совпадения красным
  (заказы, состав заказа, состав заявки, бригады).
- Свайп влево по заказу/заявке: отменить; повторный по отменённой — удалить.
- Долгое нажатие: на заказ — расчёт с возвратами; на заявку — разница состава после выдачи.

## Push-уведомления

- **Android:** Firebase (`android/app/google-services.json`, проект `crm-confectionery`), подписка на тему
  `role_<РОЛЬ>` при входе, при выходе — отписка.
- **iPhone (веб):** iOS 16.4+, приложение открыто с экрана «Домой»; Настройки › «Уведомления на этом
  устройстве» › Включить → токен браузера уходит на `POST /api/push/web-token`, сервер подписывает его на
  тему роли. Фоновый обработчик — `mobile/web/firebase-messaging-sw.js`, отдаётся из корня сайта
  (`/firebase-messaging-sw.js`). Ключ Web Push (VAPID) не задан — используется стандартный Firebase
  (`firebaseWebVapidKey` в `firebase_web_options.dart`).

## Сборка и выкладка

```bash
export PATH=$HOME/flutter/bin:$PATH
cd mobile
flutter analyze lib                               # проверка
flutter build apk --release                        # → build/app/outputs/flutter-apk/app-release.apk
flutter build web --release --base-href /app/      # → build/web
# выкладка
scp -i ~/.ssh/crm_contabo build/app/outputs/flutter-apk/app-release.apk \
    bakhodir@169.58.214.142:/home/bakhodir/downloads/46a256c7720157c0/crm.apk
rsync -az -e "ssh -i ~/.ssh/crm_contabo" build/web/ bakhodir@169.58.214.142:~/CRM/web-app/
```

Веб-версия отдаётся без кеша — iPhone видит новую сборку при следующем открытии. Если менялся контракт
API — обновлять APK обязательно, иначе установленное приложение сломается.
