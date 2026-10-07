# Справочник REST API (`/api/**`)

Сгенерировано из кода контроллеров `src/main/java/com/confectionery/crm/api/` 05.10.2026. Все адреса, кроме `/api/auth/login`, вебхука WhatsApp и `/firebase-messaging-sw.js`, требуют заголовок `Authorization: Bearer <JWT>` (токен выдаёт `POST /api/auth/login`, живёт 12 ч). Колонка «Роли» взята из `@PreAuthorize`; пусто — любой вошедший пользователь (часть проверок прав сделана внутри сервисов).

Роли: `ADMIN` — администратор, `ACCOUNTANT` — бухгалтер, `SALES` — менеджер по продажам, `WAREHOUSE_FG` — склад готовой продукции, `WAREHOUSE_RAW` — склад сырья, `TECHNOLOGIST` — технолог.


## AuthApiController

| Метод | Адрес | Роли | Назначение |
|---|---|---|---|
| `POST` | `/api/auth/login` |  |  |

## ClientApiController

| Метод | Адрес | Роли | Назначение |
|---|---|---|---|
| `GET` | `/api/clients` |  |  |
| `GET` | `/api/clients/{id}` |  |  |
| `POST` | `/api/clients` |  |  |
| `PUT` | `/api/clients/{id}` |  |  |
| `DELETE` | `/api/clients/{id}` | ADMIN | Только ADMIN — удаление клиента каскадно стирает всю его историю (заказы, цены, WhatsApp), см. ClientService.delete. |
| `GET` | `/api/clients/{id}/monthly-sales` |  | Сумма покупок клиента за текущий календарный месяц (по дате отгрузки/завершения). |

## ClientPriceApiController

| Метод | Адрес | Роли | Назначение |
|---|---|---|---|
| `GET` | `/api/clients/{clientId}/prices` |  |  |
| `PUT` | `/api/clients/{clientId}/prices/{productId}` | SALES, ADMIN, ACCOUNTANT |  |
| `DELETE` | `/api/clients/{clientId}/prices/{productId}` | SALES, ADMIN, ACCOUNTANT |  |

## CompanyProfileApiController

| Метод | Адрес | Роли | Назначение |
|---|---|---|---|
| `GET` | `/api/company-profile` |  |  |
| `PUT` | `/api/company-profile` | ADMIN, ACCOUNTANT |  |

## CounterpartyApiController

| Метод | Адрес | Роли | Назначение |
|---|---|---|---|
| `GET` | `/api/counterparties` |  |  |
| `POST` | `/api/counterparties` | ADMIN, WAREHOUSE_RAW |  |

## DashboardApiController

| Метод | Адрес | Роли | Назначение |
|---|---|---|---|
| `GET` | `/api/dashboard` | ADMIN, ACCOUNTANT, SALES, WAREHOUSE_FG | Продажи в деньгах — руководству, продажам и складу ГП; складу сырья — нет. |
| `GET` | `/api/dashboard/production` |  | Дашборд для роли WAREHOUSE_RAW — про заявки на сырьё, не про заказы клиентов. |
| `GET` | `/api/dashboard/warehouse` |  | Дашборд склада ГП: что собрать и отгрузить (все текущие, независимо от периода), сколько отгружено за период, сколько заявок цех собрал и ждёт приёма, и 8 товаров с наиме… |

## DashboardExtrasApiController

| Метод | Адрес | Роли | Назначение |
|---|---|---|---|
| `GET` | `/api/dashboard/insights` | ADMIN, ACCOUNTANT, SALES, WAREHOUSE_FG |  |
| `GET` | `/api/dashboard/raw-insights` |  |  |
| `GET` | `/api/dashboard/feed` |  |  |

## EmployeeApiController

| Метод | Адрес | Роли | Назначение |
|---|---|---|---|
| `GET` | `/api/employees` | ADMIN, ACCOUNTANT, TECHNOLOGIST |  |
| `POST` | `/api/employees/import-1c` | ADMIN, ACCOUNTANT, TECHNOLOGIST | Кнопка «Загрузить из 1С»: добавляет/обновляет сотрудников по справочнику 1С (ставки не трогает). |
| `POST` | `/api/employees` | ADMIN, ACCOUNTANT, TECHNOLOGIST |  |
| `PUT` | `/api/employees/{id}` | ADMIN, ACCOUNTANT, TECHNOLOGIST |  |

## IngredientApiController

| Метод | Адрес | Роли | Назначение |
|---|---|---|---|
| `GET` | `/api/ingredients` |  |  |
| `GET` | `/api/ingredients/{id}` |  |  |
| `POST` | `/api/ingredients` |  |  |
| `PUT` | `/api/ingredients/{id}` |  |  |
| `DELETE` | `/api/ingredients/{id}` |  |  |

## NotificationApiController

| Метод | Адрес | Роли | Назначение |
|---|---|---|---|
| `GET` | `/api/notifications/grouped` |  | Та же группировка "один заказ — одна карточка", что и на дашборде, но без ограничения в 8 записей. from/to не заданы — все даты; мобильное приложение по умолчанию передаё… |

## OrderApiController

| Метод | Адрес | Роли | Назначение |
|---|---|---|---|
| `GET` | `/api/orders` |  | from/to не заданы — все даты (как раньше); мобильное приложение по умолчанию передаёт сегодняшний день. |
| `GET` | `/api/orders/{id}` |  |  |
| `GET` | `/api/orders/{id}/invoice` |  | Накладная по заказу в PDF — по макету печатной формы 1С, с данными из документов 1С и закреплёнными за заказом возвратами (см. InvoicePdfService). Мобильное приложение де… |
| `POST` | `/api/orders` | SALES, ADMIN, ACCOUNTANT |  |
| `POST` | `/api/orders/{id}/confirm` | WAREHOUSE_FG, ADMIN |  |
| `POST` | `/api/orders/{id}/reopen` | SALES, ADMIN, ACCOUNTANT, WAREHOUSE_FG | Вернуть заказ READY -> NEW, чтобы редактировать состав чек-листом сборки (см. OrderService.reopenForEditing). |
| `POST` | `/api/orders/{id}/rollback` | ADMIN | Админ откатывает статус на шаг назад (см. OrderService.rollback). |
| `POST` | `/api/orders/{id}/cancel` | SALES, ADMIN, ACCOUNTANT, WAREHOUSE_FG | Отмена: документ в 1С распроводится (черновик); отгруженный заказ — только админ/бухгалтер. |
| `DELETE` | `/api/orders/{id}` | ADMIN, ACCOUNTANT | Удаление отменённого заказа: документ в 1С помечается на удаление. |
| `PUT` | `/api/orders/{id}/items` | WAREHOUSE_FG, ADMIN, ACCOUNTANT |  |
| `POST` | `/api/orders/{id}/ship` | WAREHOUSE_FG, ADMIN |  |
| `POST` | `/api/orders/{id}/complete` | ACCOUNTANT, ADMIN |  |
| `GET` | `/api/orders/debts` | ACCOUNTANT, ADMIN | Список неоплаченных долгов (см. также ScheduledJobs.checkOverdueDebts). |
| `POST` | `/api/orders/{id}/debt` | ACCOUNTANT, ADMIN |  |

## ProductApiController

| Метод | Адрес | Роли | Назначение |
|---|---|---|---|
| `GET` | `/api/products` |  | Без архивных — используется мобильным приложением и для поиска товара при оформлении заказа (по умолчанию, полуфабрикаты скрыты — их нельзя заказать), и для экранов управ… |
| `GET` | `/api/products/{id}` |  |  |
| `POST` | `/api/products` |  |  |
| `PUT` | `/api/products/{id}` |  |  |
| `PUT` | `/api/products/{id}/client-visibility` | ADMIN | Скрыть товар от клиентов (витрина, WhatsApp) или вернуть — только ADMIN, см. Product#hiddenFromClients. |
| `DELETE` | `/api/products/{id}` | ADMIN |  |
| `POST` | `/api/products/{id}/image` |  |  |
| `GET` | `/api/products/{id}/recipe` |  |  |
| `PUT` | `/api/products/{id}/recipe` |  |  |
| `POST` | `/api/products/import-departments-1c` | ADMIN | Только ADMIN — то же самое, что кнопка "Импортировать подразделения из 1С" в веб-версии. |
| `POST` | `/api/products/{id}/import-recipe-1c` | ADMIN | Только ADMIN — заполняет рецепт (состав сырья) и GUID спецификации ОДНОГО товара из 1С, не трогая остальные (см. OneCRecipeImportService.importOne) — кнопка "Импортироват… |

## ProductReturnApiController

| Метод | Адрес | Роли | Назначение |
|---|---|---|---|
| `GET` | `/api/returns` | ADMIN, ACCOUNTANT, SALES, WAREHOUSE_FG | orderId — возвраты, закреплённые за заказом (расчёт в карточке заказа); clientId + unattached=true — ещё не закреплённые возвраты клиента (выбор для закрепления); from/to… |
| `GET` | `/api/returns/{id}` | ADMIN, ACCOUNTANT, SALES, WAREHOUSE_FG |  |
| `POST` | `/api/returns` | ADMIN, ACCOUNTANT, SALES, WAREHOUSE_FG |  |
| `PUT` | `/api/returns/{id}` | ADMIN, ACCOUNTANT, SALES, WAREHOUSE_FG |  |
| `DELETE` | `/api/returns/{id}` | ADMIN, ACCOUNTANT |  |
| `PUT` | `/api/returns/{id}/order/{orderId}` | ADMIN, ACCOUNTANT, SALES, WAREHOUSE_FG |  |
| `DELETE` | `/api/returns/{id}/order` | ADMIN, ACCOUNTANT, SALES, WAREHOUSE_FG |  |
| `POST` | `/api/returns/{id}/sync-1c` | ADMIN, ACCOUNTANT | Повторная ручная выгрузка в 1С — см. ProductReturnService.resyncOneC. |

## ProductionCostApiController

| Метод | Адрес | Роли | Назначение |
|---|---|---|---|
| `GET` | `/api/production/cost` | ADMIN, ACCOUNTANT, TECHNOLOGIST |  |
| `GET` | `/api/production/requests/{id}` | ADMIN, ACCOUNTANT, TECHNOLOGIST |  |
| `PUT` | `/api/production/requests/{id}/brigade` | ADMIN, ACCOUNTANT, TECHNOLOGIST |  |

## ProductionRequestApiController

| Метод | Адрес | Роли | Назначение |
|---|---|---|---|
| `GET` | `/api/production-requests` |  |  |
| `GET` | `/api/production-requests/{id}` |  |  |
| `GET` | `/api/production-requests/eligible-products` |  | Товары, у которых есть рецептура — для выбора (с поиском) при создании заявки. |
| `POST` | `/api/production-requests` | ADMIN, ACCOUNTANT | Позиции автоматически разбиваются по группе товара (Product.group) — на каждую группу создаётся своя заявка с названием = названием группы (см. ProductionRequestService.c… |
| `PUT` | `/api/production-requests/{id}` | ADMIN, ACCOUNTANT | Редактирование заявки — позиции и дата документа; название пересчитывается по группам товаров. |
| `GET` | `/api/production-requests/{id}/adjustments` |  | Поправки состава к рецептуре — пусто, если их нет (тогда при правке позиций вопрос не задаётся). |
| `POST` | `/api/production-requests/{id}/cancel` | ADMIN | Отмена заявки в ЛЮБОМ статусе, кроме уже отменённой — см. ProductionRequestService.cancel: откатывает складские остатки (если были списаны/оприходованы) и распроводит док… |
| `DELETE` | `/api/production-requests/{id}` | ADMIN | Удаление отменённой заявки — документы в 1С помечаются на удаление (см. ProductionRequestService.delete). |
| `POST` | `/api/production-requests/{id}/items/{itemId}/substitute` | ADMIN, WAREHOUSE_RAW | Замена сырья в позиции — например, нужного не хватило на складе (см. ProductionRequestService.substituteItem). |
| `POST` | `/api/production-requests/{id}/items/add` | ADMIN, WAREHOUSE_RAW | Добавление совсем новой позиции сырья, которой не было в заявке (см. ProductionRequestService.addItem). |
| `DELETE` | `/api/production-requests/{id}/items/{itemId}` | ADMIN, WAREHOUSE_RAW | Удаление позиции сырья из заявки (см. ProductionRequestService.removeItem). |
| `POST` | `/api/production-requests/{id}/collect` | ADMIN, WAREHOUSE_RAW |  |
| `POST` | `/api/production-requests/{id}/lines/{lineId}/receive` | ADMIN, WAREHOUSE_FG | Оприходовать ОДНУ строку заявки — у каждого товара в заявке свой фактический выход. |
| `POST` | `/api/production-requests/{id}/lines/extra` | ADMIN, WAREHOUSE_FG, WAREHOUSE_RAW | Оприходовать товар сверх заявки — вышел из того же замеса по ошибке (см. ProductionRequestService.receiveExtra). |
| `PUT` | `/api/production-requests/{id}/lines/{lineId}/received` | ADMIN | Админ исправляет уже оприходованное количество (см. ProductionRequestService.changeReceived). |
| `POST` | `/api/production-requests/{id}/sync-1c` | ADMIN, ACCOUNTANT | Повторная ручная выгрузка в 1С — см. ProductionRequestService.resyncOneC. |
| `POST` | `/api/production-requests/{id}/complete-1c` | ADMIN, ACCOUNTANT, WAREHOUSE_RAW | "Завершить" — вручную проводит требование-накладную (списание сырья) в 1С, когда состав заявки точно больше не изменится (см. ProductionRequestService.completeRequisition… |
| `POST` | `/api/production-requests/{id}/cancel-completion` | ADMIN, ACCOUNTANT, WAREHOUSE_RAW | "Отменить" (после "Завершить") — распроводит требование-накладную в 1С и снова разрешает править состав сырья (см. ProductionRequestService.cancelCompletion). |
| `GET` | `/api/production-requests/{id}/composition-diff` |  | Всплывающее окно по долгому нажатию на заявку в списке: что выдано работникам, что в заявке сейчас, разница (довыдать/вернуть) и история правок состава. |
| `POST` | `/api/production-requests/{id}/composition-diff/settle` | ADMIN, WAREHOUSE_RAW | «Рассчитались» — разница выдана/возвращена, текущий состав становится выданным. |
| `GET` | `/api/production-requests/report` |  | Дневной отчёт "сколько сегодня изготовили" — date не задан = сегодня. |
| `GET` | `/api/production-requests/report/pdf` |  |  |

## PurchaseApiController

| Метод | Адрес | Роли | Назначение |
|---|---|---|---|
| `GET` | `/api/purchases` | ADMIN, WAREHOUSE_RAW |  |
| `POST` | `/api/purchases` | ADMIN, WAREHOUSE_RAW |  |
| `PUT` | `/api/purchases/{id}` | ADMIN, WAREHOUSE_RAW | Правка закупки — см. PurchaseService.update. |
| `POST` | `/api/purchases/{id}/image` | ADMIN, WAREHOUSE_RAW | Фото накладной поставщика — прикладывается отдельным запросом после создания закупки. |
| `GET` | `/api/purchases/{id}/invoice` | ADMIN, WAREHOUSE_RAW | Приходная накладная по закупке в PDF — по макету печатной формы 1С, с данными документа "Поступление" из 1С (см. InvoicePdfService.generatePurchase). Приложение показывае… |
| `POST` | `/api/purchases/{id}/sync-1c` | ADMIN, ACCOUNTANT | Повторная ручная выгрузка в 1С — см. PurchaseService.resyncOneC. |

## ReportApiController

| Метод | Адрес | Роли | Назначение |
|---|---|---|---|
| `GET` | `/api/reports` |  |  |
| `GET` | `/api/reports/{key}` |  |  |

## StockMovementApiController

| Метод | Адрес | Роли | Назначение |
|---|---|---|---|
| `GET` | `/api/stock/movements` | WAREHOUSE_FG, WAREHOUSE_RAW, ADMIN, ACCOUNTANT | Только ручные движения — см. StockMovement.manual/StockService.findMovements. |
| `POST` | `/api/stock/receipts/batch` | WAREHOUSE_FG, ADMIN, ACCOUNTANT | Документ формы «Поступление»: несколько позиций с датой, уходит в 1С проведённым (фоновая очередь). |
| `POST` | `/api/stock/writeoffs/batch` | WAREHOUSE_FG, WAREHOUSE_RAW, ADMIN, ACCOUNTANT | То же для «Списания», причина обязательна. |
| `DELETE` | `/api/stock/movements/{id}` | WAREHOUSE_FG, WAREHOUSE_RAW, ADMIN, ACCOUNTANT | Удаление ошибочно созданного ручного поступления/списания — откатывает остаток (см. StockService.deleteMovement). |
| `POST` | `/api/stock/sync-1c` | ADMIN | Ручной запуск синхронизации остатков с 1С (обычно она идёт по расписанию) — только ADMIN. |

## TechCardApiController

| Метод | Адрес | Роли | Назначение |
|---|---|---|---|
| `GET` | `/api/tech-cards` |  |  |
| `GET` | `/api/tech-cards/{productId}` |  |  |
| `PUT` | `/api/tech-cards/{productId}` | ADMIN, TECHNOLOGIST |  |

## TimesheetApiController

| Метод | Адрес | Роли | Назначение |
|---|---|---|---|
| `GET` | `/api/timesheet` | ADMIN, ACCOUNTANT, TECHNOLOGIST |  |
| `PUT` | `/api/timesheet` | ADMIN, ACCOUNTANT, TECHNOLOGIST |  |
| `GET` | `/api/timesheet/xlsx` | ADMIN, ACCOUNTANT, TECHNOLOGIST |  |

## UserApiController

| Метод | Адрес | Роли | Назначение |
|---|---|---|---|
| `GET` | `/api/users` | ADMIN, ACCOUNTANT |  |
| `GET` | `/api/users/{id}` | ADMIN, ACCOUNTANT |  |
| `POST` | `/api/users` | ADMIN, ACCOUNTANT |  |
| `PUT` | `/api/users/{id}` | ADMIN, ACCOUNTANT | Логин/пароль/ФИО/активность может менять ADMIN или ACCOUNTANT, а вот роль — только ADMIN. |

## WebPushApiController

| Метод | Адрес | Роли | Назначение |
|---|---|---|---|
| `POST` | `/api/push/web-token` |  |  |
| `GET` | `/firebase-messaging-sw.js` |  | Фоновый обработчик Firebase для браузера. Firebase JS SDK ищет его строго в корне сайта (/firebase-messaging-sw.js), а веб-сборка лежит под /app/ — отдаём тот же файл из … |

## WhatsAppInboxApiController

| Метод | Адрес | Роли | Назначение |
|---|---|---|---|
| `GET` | `/api/whatsapp-inbox` | SALES, ADMIN, ACCOUNTANT |  |
| `POST` | `/api/whatsapp-inbox/{id}/resolve` | SALES, ADMIN, ACCOUNTANT | Тело запроса: {"&lt;idПозиции&gt;": &lt;idТовара&gt;, ...} — только для нераспознанных позиций. |
| `POST` | `/api/whatsapp-inbox/{id}/ignore` | SALES, ADMIN, ACCOUNTANT |  |

## WhatsAppSessionApiController

| Метод | Адрес | Роли | Назначение |
|---|---|---|---|
| `GET` | `/api/whatsapp/session/status` | ADMIN, ACCOUNTANT |  |
| `GET` | `/api/whatsapp/session/qr` | ADMIN, ACCOUNTANT |  |
| `POST` | `/api/whatsapp/session/logout` | ADMIN, ACCOUNTANT |  |

## WhatsAppWebhookController

| Метод | Адрес | Роли | Назначение |
|---|---|---|---|
| `POST` | `/api/whatsapp/incoming` |  |  |
