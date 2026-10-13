# CRM «Юг Сладости» — справка по коду (13.10.2026)

Собрано только чтением кода, ничего не менялось. Стек: Spring Boot (Java, JPA, Spring Security),
Postgres (прод) / H2 (локально), Thymeleaf, Flutter (mobile/), интеграция с 1С (OData, фоновая очередь).

## 1. Статусы заказа

**Классов `OrderStateMachine` и `GuardChecker` в проекте НЕТ.** Статусы — enum
`domain/OrderStatus.java`, переходы — отдельные методы `service/OrderService.java`, проверки
(аналог guard) — `if (...) throw IllegalStateException` внутри методов + `@PreAuthorize` в
`api/OrderApiController.java`. Хранится в `orders.status` (VARCHAR 32 + CHECK-constraint в БД).

| enum | русское название |
|---|---|
| PLACED | Новая (клиент оформил сам на /shop или в WhatsApp, ждёт «Принять») |
| NEW | В процессе сборки |
| CONFIRMED | Подтверждён (устаревший, в потоке не используется) |
| IN_PRODUCTION | В производстве (устаревший, в потоке не используется) |
| READY | Готов к отгрузке |
| DELIVERED | Отгружен (со склада ГП) |
| COMPLETED | Завершён |
| CANCELLED | Отменён |

Создание (`createOrder`): `source` = SITE/WHATSAPP → PLACED, иначе (CRM) → NEW. Проверки: товар не
скрыт и не снят с продажи, хотя бы одна позиция.

| Метод / endpoint | Из → в | Проверки (роль; условие в сервисе) |
|---|---|---|
| `accept` POST /api/orders/{id}/accept | PLACED → NEW | WAREHOUSE_FG, ADMIN; статус == PLACED; ставит acceptedAt |
| `confirm` POST /{id}/confirm («собрано») | NEW или PLACED → READY | WAREHOUSE_FG, ADMIN; статус NEW/PLACED; ставит readyAt, push бухгалтеру и админу |
| `reopenForEditing` POST /{id}/reopen | READY → NEW | SALES, ADMIN, ACCOUNTANT, WAREHOUSE_FG; статус == READY |
| `ship` POST /{id}/ship | READY → DELIVERED | WAREHOUSE_FG, ADMIN; статус == READY; обязателен гос. номер ТС; списывает остаток ГП, shippedAt/shippedByName, документ в 1С |
| `complete` POST /{id}/complete | DELIVERED → COMPLETED | ACCOUNTANT, ADMIN; статус == DELIVERED (снимает долг, если был) |
| `cancel` POST /{id}/cancel | любой, кроме CANCELLED → CANCELLED | SALES, ADMIN, ACCOUNTANT, WAREHOUSE_FG; уже отменён — ошибка; DELIVERED/COMPLETED — только ADMIN/ACCOUNTANT (возврат остатка, если отгружали через CRM); в 1С документ распроводится |
| `rollback` POST /{id}/rollback (шаг назад) | COMPLETED→DELIVERED; DELIVERED→READY (возврат остатка, очистка ТС); READY/CONFIRMED/IN_PRODUCTION→NEW; CANCELLED→NEW; NEW→PLACED (только если source SITE/WHATSAPP); PLACED — ошибка | только ADMIN |
| `delete` DELETE /{id} | удаление | ADMIN, ACCOUNTANT; только статус CANCELLED |
| `updateItems` PUT /{id}/items | состав, без смены статуса | WAREHOUSE_FG, ADMIN, ACCOUNTANT; CANCELLED нельзя; DELIVERED/COMPLETED — только админ |

Долги (без смены статуса): `setDebt` (debtDueDate, обязателен срок), `markDebtPaid` — ACCOUNTANT, ADMIN.
Веб-контроллер `web/OrderController.java` дублирует те же действия для Thymeleaf-страниц.

## 2. Поля сущностей

**Order** (`orders`): id Long; client Client (ManyToOne, обяз.); status OrderStatus (по умолч. NEW);
createdAt LocalDateTime; dueDate LocalDate (дата отгрузки/выдачи); notes String(2000);
vehicleNumber String; shippedAt LocalDateTime; shippedByName String; debtDueDate LocalDate;
debtPaid Boolean; fromWhatsapp Boolean (устар.); source OrderSource (SITE/WHATSAPP/CRM);
acceptedAt LocalDateTime; readyAt LocalDateTime; oneCDocumentNumber String; oneCDocumentKey String;
oneCSyncError String(500); oneCPending boolean; importedFromOneC boolean;
items List&lt;OrderItem&gt;; returns List&lt;ProductReturn&gt;.

**OrderItem** (`order_items`): id Long; order Order; product Product; quantity BigDecimal(12,3);
price BigDecimal(12,2).

**PickupSlot — такой сущности НЕТ.** Окно выдачи на /shop не хранится отдельно: день выдачи пишется в
`Order.dueDate`, а окно времени и способ оплаты — текстом в `Order.notes` («Оплата: …»). С 09.10 выбор
времени и оплаты в корзине закомментирован в `shop/app.html`.

**Product** (`products`): id Long; name String; category String; group String (product_group);
description String(1000); unit String; netWeightGrams BigDecimal; massReferenceProduct Boolean;
unitWeightKg BigDecimal; unitsPerBox BigDecimal; boxVolumeM3 BigDecimal(12,5); unitsPerBox2 BigDecimal;
boxVolume2M3 BigDecimal; imageUrl String(2000); price BigDecimal(12,2); quantityInStock BigDecimal;
recipe Recipe; photos List&lt;ProductPhoto&gt;; active boolean (архив); hiddenFromClients boolean;
hiddenFromClientsUntil LocalDate; semiFinished boolean (полуфабрикат); oneCNomenclatureKey String;
oneCCode String; oneCLineTemplate String(4000); oneCSpecificationKey String; oneCDepartmentKey String.

**Client** (`clients`): id Long; name String; phone String (по нему вход на /shop); email String;
address String; notes String(2000); createdAt LocalDateTime; oneCContragentKey String;
oneCContractKey String; oneCPriceTypeKey String; orders List&lt;Order&gt;; prices List&lt;ClientPrice&gt;
(индивидуальные цены).

**Оплата — отдельной сущности НЕТ.** Есть только поля долга в Order (debtDueDate, debtPaid) и
закрытие заказа бухгалтером (COMPLETED). Сумма «к оплате» считается в накладной: продажа − возвраты
(`OneCInvoiceService`, `InvoicePdfService`).

## 3. История смены статусов

**Отдельной таблицы истории статусов нет.** Есть только отметки времени/исполнителя на самом заказе:
createdAt, acceptedAt (принят), readyAt (собран), shippedAt + shippedByName (кто отгрузил),
vehicleNumber. Кто принял/собрал/отменил/завершил — не хранится. При `rollback` поля очищаются
(история теряется). Уведомления (`Notification`) пишутся в БД при событиях и частично заменяют журнал.
Для заявок на производство история есть: `ProductionRequestRevision` (createdAt, createdBy, note,
снимок состава), плюс collectedBy/collectedAt, receivedBy/receivedAt в строках.

## 4. Роли, вход, CurrentUserService

Роли (`domain/Role.java`): ADMIN «Администратор», SALES «Менеджер по продажам», WAREHOUSE_FG
«Кладовщик (склад ГП)», WAREHOUSE_RAW «Кладовщик (склад сырья)», ACCOUNTANT «Бухгалтер»,
TECHNOLOGIST «Технолог». User: id, username (уник.), passwordHash, fullName, role, enabled, createdAt.

Вход (`config/SecurityConfig.java`, несколько SecurityFilterChain):
- `/api/**` — stateless JWT (`security/JwtService`, `JwtAuthFilter`): токен выдаёт `/api/auth/**`,
  в claims uid, role, name; срок `crm.jwt.expiration-minutes` (по умолч. 720 мин). Используется
  Flutter-приложением и веб-сборкой /app. Права — `@PreAuthorize(hasRole/hasAnyRole)`.
- `/**` (Thymeleaf-админка) — formLogin `/login` + сессия + remember-me; `CrmUserDetailsService`.
- `/shop/**` — permitAll; свой «вход» по номеру телефона: `ShopController` ищет Client по phone,
  кладёт clientId в HttpSession и ставит cookie `shop_phone`. Неизвестный номер → заявка на доступ
  (`ShopAccessRequest`), админ разрешает на экране «Клиенты». Пароля/кода нет.
- `/`, `/promo/**`, `/contacts`, `/vacancies`, картинки, `/app/**` — публичные.

**`CurrentUserService` в проекте НЕТ.** Текущий пользователь берётся из `Authentication`/
`SecurityContextHolder` прямо в контроллерах (параметр `Authentication`, JWT-claims), в
`UserActivityInterceptor` и `IdempotencyFilter`. Клиент магазина — из сессии (`currentClient(session)`).

## 5. Сырьё, рецептуры, склад, заявки, закупки

**Ingredient** (сырьё = склад сырья, `ingredients`): id; name String (уник.); unit String;
quantityInStock BigDecimal (остаток); minThreshold BigDecimal (порог «низкий остаток»);
oneCNomenclatureKey String; active boolean. Цена сырья на дату — `IngredientPriceService` (из закупок).

**Recipe** (`recipes`): id; product Product (OneToOne); yieldQuantity BigDecimal (выход на замес);
items List&lt;RecipeItem&gt;. **RecipeItem**: id; recipe; ingredient Ingredient; quantity BigDecimal.
Импорт из 1С — `OneCRecipeImportService`/`OneCSpecificationImportService`.

**TechCard** (техкарта): id; product; shelfLife; storageConditions; packaging; qualityLook/Color/Crumb/
Taste; draft Boolean; stages List&lt;TechCardStage&gt; (position, title, description, temperature,
duration, ccp boolean).

**Заявка на производство / сырьё — ProductionRequest** (`production_requests`): id; title; status
ProductionRequestStatus (NEW «Новая», COLLECTED «Собрано», RECEIVED «Оприходовано», CANCELLED
«Отменена»); createdBy String; createdAt; documentDate LocalDate; collectedBy; collectedAt;
oneCReportKey/Number, oneCRequisitionKey/Number (Отчёт производства + Требование-накладная в 1С);
oneCSyncError; oneCPending; requisitionCompleted boolean; lines List&lt;ProductionRequestLine&gt;
(продукция); items List&lt;ProductionRequestItem&gt; (сырьё); brigade List&lt;ProductionBrigadeMember&gt;;
revisions List&lt;ProductionRequestRevision&gt;; compositionDiffPending Boolean; compositionDiffBatches String.
- ProductionRequestLine: product; batches BigDecimal (замесы); plannedQuantity; receivedBy;
  receivedAt; receivedQuantity.
- ProductionRequestItem: ingredient; requiredQuantity; originalIngredientName (при замене сырья).
- Переходы: NEW → COLLECTED (собрать, только из NEW) → RECEIVED (оприходовать все строки);
  отмена → CANCELLED; удалить только отменённую; «завершить»/«отменить завершение» — флаг
  requisitionCompleted. Строка блокируется FOR UPDATE.

**Purchase** (закупка, `purchases`): id; counterparty Counterparty; purchaseDate LocalDate; createdBy;
createdAt; imageUrl (фото накладной); oneCDocumentKey/Number; oneCSyncError; oneCPending;
items List&lt;PurchaseItem&gt;. **PurchaseItem**: purchase; ingredient; quantity; totalPrice BigDecimal;
stockMovement StockMovement. **Counterparty**: id; name; oneCContragentKey; oneCContractKey; oneCSyncError.

**Движения склада**: StockMovement (ingredient ИЛИ product; type RECEIPT «Поступление»/WRITE_OFF
«Списание»; quantity; reason; manual; createdBy; createdAt; oneCSyncedAt; oneCDocumentNumber; document)
и StockDocument (документ поступления/списания: type; product boolean — ГП или сырьё; documentDate;
reason; createdBy; createdAt; movements; oneCDocumentKey/Number; oneCSyncError; oneCPending).
Остаток ГП — `Product.quantityInStock`, сырья — `Ingredient.quantityInStock`.

Также есть: ProductReturn/ProductReturnItem (возвраты испорченного товара), Employee/
ProductionBrigadeMember/TimesheetMark (сотрудники, бригады, табель), ClientPrice, CallbackRequest,
ShopAccessRequest, Vacancy/VacancyReply, WhatsAppInbound*, Notification, CompanyProfile.

## 6. Магазин /shop и Flutter

- **/shop — серверный Thymeleaf**, отдельного фронтенда нет: `web/ShopController.java`, шаблоны
  `src/main/resources/templates/shop/` (enter.html, app.html — каталог/корзина/история по `view`,
  done.html, fragments/frame.html, fragments/order-art.html). Корзина в HttpSession. Рядом:
  промо-сайт `/` (`promo.html`, `static/promo/`), `/contacts`, `/vacancies` (`templates/site/`).
- Внутренняя веб-админка тоже Thymeleaf: `templates/orders`, `products`, `clients`, `dashboard.html` и т.д.
  (`web/*Controller.java`).
- **Flutter-приложение (основная админка): `mobile/`** — `mobile/lib/` (main.dart, navigation.dart,
  models/, screens/ ~55 экранов, services/api_client.dart, theme/, widgets/). Говорит с бэкендом
  через `/api/**` по JWT. Собирается в APK (Android) и в веб: веб-сборка раздаётся бэкендом по
  `/app/**` из внешней папки `crm.web-app.dir` (`config/WebAppConfig.java`) — её открывают на iPhone.
