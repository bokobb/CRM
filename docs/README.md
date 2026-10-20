# CRM «Юг Сладости» — документация проекта

Версия документа: 05.10.2026. Система учёта для кондитерского производства «Юг Сладости»: заказы
дистрибьюторов, производство и сырьё, склад готовой продукции, закупки, возвраты, себестоимость,
сотрудники, отчёты; двусторонняя интеграция с 1С:Бухгалтерия (1cFresh), WhatsApp-заказы, сайт для
клиентов, мобильное приложение (Android и iPhone через браузер).

## Содержание

| Документ | О чём |
|---|---|
| [architecture.md](architecture.md) | Из чего состоит система, стек, как компоненты связаны, ключевые технические решения |
| [business.md](business.md) | Роли пользователей и бизнес-процессы: заказы, производство, склад, закупки, возвраты, себестоимость, отчёты |
| [onec.md](onec.md) | Интеграция с 1С:Fresh — какие документы куда идут, фоновая очередь, импорт |
| [mobile.md](mobile.md) | Мобильное приложение и веб-версия для iPhone: экраны, темы, компоновки, push, сборка |
| [shop.md](shop.md) | Сайт для клиентов `/shop` и приём заказов из WhatsApp |
| [operations.md](operations.md) | Сервер, деплой, бэкапы и восстановление, настройки, частые проблемы |
| [data-model.md](data-model.md) | Таблицы базы данных (сгенерировано из боевой базы) |
| [api.md](api.md) | Справочник REST API — 128 адресов с ролями (сгенерировано из кода) |

## Коротко

- **Адрес:** https://yug-sladosti.kz — веб-интерфейс (вход по логину), `/app/` — приложение в
  браузере (для iPhone), `/shop` — сайт клиентов, APK для Android:
  https://yug-sladosti.kz/downloads/46a256c7720157c0/crm.apk
- **Сервер:** Contabo VPS `169.58.214.142` (Ubuntu 24.04, 4 vCPU / 8 ГБ / 96 ГБ), пользователь `bakhodir`.
- **Код:** `~/IdeaProjects/CRM` на компьютере владельца. Удалённого git-репозитория **нет** — это
  единственная копия кода (см. «Риски» в [operations.md](operations.md)).
- **Стек:** Java 17 + Spring Boot 4.1.1 (Gradle), PostgreSQL 16 в Docker, Thymeleaf (веб), Flutter 3.47
  (Android + Web), Node.js 20 (шлюз WhatsApp), Firebase Cloud Messaging (push), 1cFresh OData (1С).

## Структура репозитория

```
CRM/
├── build.gradle, settings.gradle, gradlew      — сборка backend (Gradle wrapper)
├── docker-compose.yml                          — PostgreSQL (порт 5434 на хосте)
├── src/main/java/com/confectionery/crm/
│   ├── domain/        — JPA-сущности (таблицы)
│   ├── repository/    — Spring Data репозитории
│   ├── service/       — бизнес-логика, 1С (OneC*), отчёты, PDF, расписания
│   ├── api/           — REST API для мобильного приложения (/api/**, JWT)
│   ├── web/           — веб-интерфейс Thymeleaf (сессия) и сайт клиентов /shop
│   └── config/        — безопасность, Firebase, загрузки, раздача /app, идемпотентность
├── src/main/resources/
│   ├── application.properties, application-postgres.properties
│   ├── templates/     — HTML-шаблоны веб-интерфейса и /shop
│   └── static/        — CSS/JS/картинки веба
├── mobile/            — Flutter-приложение (lib/, android/, web/)
├── whatsapp-gateway/  — Node.js-шлюз WhatsApp (Baileys)
└── docs/              — эта документация
```

## Локальная копия данных сервера

05.10.2026 всё, чего нет в коде, скачано в `~/CRM-server-backup/2026-10-05/` (доступ только владельцу,
`chmod go-rwx`): полная копия базы (`db/crm-full.dump` + `crm-full.sql`), картинки товаров, старые
бэкапы, конфигурация сервера (Nginx, systemd, cron, `backend.env` **с паролями**, ключ Firebase), сессия WhatsApp,
последний APK. Как восстановить сервер из этой копии — [operations.md](operations.md#восстановление-с-нуля).
