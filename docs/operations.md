# Эксплуатация

## Сервер

- Contabo VPS (с 20.10.2026, до этого Yandex Cloud 94.131.90.178), IP **169.58.214.142**,
  Ubuntu 24.04, 4 vCPU / 8 ГБ / 96 ГБ, swap 2 ГБ.
- Вход: `ssh -i ~/.ssh/crm_contabo bakhodir@169.58.214.142` (у `bakhodir` sudo без пароля; root — тем же ключом).
- Адрес: **https://yug-sladosti.kz** (DNS в Hoster.KZ, A-записи `@` и `www` → 169.58.214.142),
  запасной `https://169.58.214.142.nip.io`. Сертификаты Let's Encrypt, автопродление certbot.
- Открытые порты (ufw): 22, 80, 443. Postgres слушает только `127.0.0.1:5434`.

### Файлы на сервере

| Путь | Что |
|---|---|
| `~/CRM/` | исходники backend (rsync с компьютера), `backend.env`, `docker-compose.yml` |
| `~/CRM/data/product-images/` | загруженные фото товаров |
| `~/CRM/web-app/` | веб-сборка приложения (`/app/`) |
| `~/CRM/whatsapp-gateway/` | шлюз WhatsApp, сессия в `auth_info/`, секрет в `.env` |
| `~/backups/` | ежедневные `pg_dump` (03:00, 14 дней) и ручные бэкапы перед миграциями |
| `~/downloads/46a256c7720157c0/crm.apk` | APK для скачивания |
| `/etc/systemd/system/crm-*.service` | автозапуск backend и шлюза |
| `/etc/nginx/sites-available/crm`, `/etc/nginx/snippets/downloads.conf` | Nginx |

### Переменные окружения

`~/CRM/backend.env`: `JWT_SECRET`, `REMEMBER_ME_SECRET`, `ONEC_BASE_URL`, `ONEC_USERNAME`, `ONEC_PASSWORD`,
`WHATSAPP_WEBHOOK_SECRET`, `CWEBP_PATH`, `TZ`; при необходимости `CLAUDE_API_KEY`, `CRM_PUBLIC_URL`.
`~/CRM/whatsapp-gateway/.env`: `BACKEND_URL`, `WEBHOOK_SECRET`, `PORT`.
Ключ Firebase Admin — `src/main/resources/firebase-service-account.json`: лежит в проекте и на сервере, но **не в git** (`.gitignore`); копия — `config/firebase-service-account.json` в локальном бэкапе. Без него push не работает.

## Управление

```bash
sudo systemctl status|restart crm-backend           # backend (старт ~30 с, компилирует на лету)
sudo journalctl -u crm-backend -f                    # лог backend
sudo systemctl restart crm-whatsapp-gateway          # шлюз WhatsApp
docker ps; docker logs crm-postgres                  # база
docker exec -it crm-postgres psql -U crm -d crm      # консоль SQL
```

## Деплой backend

```bash
# на компьютере, из корня проекта
export JAVA_HOME=/opt/jdk/jdk-17.0.12 PATH=/opt/jdk/jdk-17.0.12/bin:$PATH
./gradlew compileJava                                       # проверка
rsync -az -e "ssh -i ~/.ssh/crm_contabo" src/ bakhodir@169.58.214.142:~/CRM/src/
ssh -i ~/.ssh/crm_contabo bakhodir@169.58.214.142 'sudo systemctl restart crm-backend'
curl -s -o /dev/null -w "%{http_code}\n" https://yug-sladosti.kz/login   # ждать 200
```

`rsync` без `--delete` (удалённые файлы на сервере остаются — при удалении класса удалить и там).
Если изменение ломает схему (NOT NULL на непустой таблице, новое значение enum со CHECK-ограничением,
переструктуризация) — **сначала бэкап**, потом SQL вручную:

```bash
docker exec crm-postgres pg_dump -U crm -F c crm > ~/backups/pre_<что>_$(date +%F).dump
docker exec -i crm-postgres psql -U crm -d crm -v ON_ERROR_STOP=1 < migration.sql
```

Новое значение enum (статус, роль): `ALTER TABLE <t> DROP CONSTRAINT <t>_<поле>_check; ALTER TABLE <t>
ADD CONSTRAINT <t>_<поле>_check CHECK (<поле> IN (...));` — проверять и на Postgres, и на локальной H2.

Выкладка приложения — см. mobile.md.

## Бэкапы

- **Автоматически:** cron 03:00 → `~/backups/crm-YYYY-MM-DD.dump`, хранятся 14 дней. **Только на этой же
  VM** — при потере VM пропадут вместе с ней.
- **Локальная копия 05.10.2026:** `~/CRM-server-backup/2026-10-05/` на компьютере владельца — база
  (`db/crm-full.dump`, `db/crm-full.sql`), фото товаров, старые бэкапы, конфиги, сессия WhatsApp, APK.
- Повторить локальную копию: те же команды, что в разделе «Восстановление», в обратную сторону, или попросить
  Claude Code «скачай всё с сервера».

### Восстановление базы

```bash
scp -i ~/.ssh/crm_contabo crm-full.dump bakhodir@169.58.214.142:/tmp/
ssh -i ~/.ssh/crm_contabo bakhodir@169.58.214.142
sudo systemctl stop crm-backend
docker cp /tmp/crm-full.dump crm-postgres:/tmp/
docker exec crm-postgres pg_restore -U crm -d crm --clean --if-exists /tmp/crm-full.dump
sudo systemctl start crm-backend
```

### Восстановление с нуля

1. Новая VM Ubuntu 24.04 (≥ 4 ГБ RAM), пользователь `bakhodir`, ufw 22/80/443.
2. `sudo apt install openjdk-17-jdk-headless docker.io nginx certbot python3-certbot-nginx`; Node.js 20 (NodeSource).
3. Скопировать проект: `rsync` корня репозитория в `~/CRM/` (без `mobile/build`, `build/`).
4. Из локальной копии: `config/backend.env` → `~/CRM/backend.env`; `config/whatsapp-gateway.env` →
   `~/CRM/whatsapp-gateway/.env`; `whatsapp/auth_info/` → `~/CRM/whatsapp-gateway/auth_info/`;
   `product-images/` → `~/CRM/data/product-images/`; `config/crm-*.service` → `/etc/systemd/system/`;
   `config/nginx-*.conf` → `/etc/nginx/sites-available/crm` и `/etc/nginx/snippets/downloads.conf`;
   `config/crontab.txt` → `crontab`; `config/firebase-service-account.json` → `~/CRM/src/main/resources/`.
5. `cd ~/CRM && docker compose up -d`, восстановить базу (`pg_restore`, см. выше).
6. `cd whatsapp-gateway && npm ci`; `sudo systemctl daemon-reload && sudo systemctl enable --now crm-backend crm-whatsapp-gateway`.
7. Сертификат: `sudo certbot --nginx -d <IP>.nip.io` (или домен). Веб-сборку и APK выложить заново (mobile.md).
8. Приложение ходит на домен `yug-sladosti.kz`, поэтому при смене сервера достаточно поменять A-записи
   в Hoster.KZ — пересобирать APK не нужно.

## Частые проблемы

| Симптом | Причина / что делать |
|---|---|
| Заказ «не выгружен в 1С: клиент не привязан» | привязать клиента к контрагенту 1С в карточке клиента |
| «Не найден именной тип цен» | завести в 1С тип цен с именем клиента, затем «Синхронизировать цены» |
| Документы в 1С не появляются несколько минут | 1cFresh временно закрыл запись (301 → `/availability`); очередь повторит сама |
| Вечный спиннер у картинок товаров | в базе старый адрес картинки (после переезда хоста), перезагрузить фото |
| iPhone показывает старую версию | закрыть приложение с экрана «Домой» и открыть снова |
| Push на iPhone не приходят | iOS ≥ 16.4, открыть с экрана «Домой», Настройки › Уведомления › Включить; в логе backend «Web push: токен подписан» |
| WhatsApp перестал принимать | Настройки › WhatsApp-сессия › новый QR |
| backend «упал» с кодом 0 | `Restart=always` поднимет сам; смотреть `journalctl -u crm-backend` |

## Риски и рекомендации

1. **Код только на одном компьютере** (нет удалённого git). Рекомендуется приватный репозиторий на
   GitHub/GitLab и `git push` после изменений.
2. **Бэкапы базы только на VM.** Рекомендуется регулярно копировать `~/backups/` за пределы VM
   (Object Storage Yandex Cloud или на компьютер).
3. `backend.env` и `firebase-service-account.json` содержат секреты — не публиковать, не отправлять в мессенджеры.
4. APK до 20.10.2026 зашиты на `94.131.90.178.nip.io` (старый сервер Yandex) — после его отключения
   такие телефоны нужно обновить или вписать `https://yug-sladosti.kz/api` в Настройки › Подключение.
