-- 13.10: новая админка — статус «Не забрали» (NO_SHOW), «Принят» теперь CONFIRMED (значение уже было).
-- Новые колонки (окно выдачи, оплата, контакт клиента, «кто менял» рецептуры) и таблицу order_events
-- создаёт ddl-auto=update сам; CHECK по enum он не переписывает — расширяем вручную. Запуск на VM:
-- docker exec -i crm-postgres psql -U crm -d crm -v ON_ERROR_STOP=1 < 2026-10-13_admin_statuses.sql
BEGIN;
ALTER TABLE orders DROP CONSTRAINT IF EXISTS orders_status_check;
ALTER TABLE orders ADD CONSTRAINT orders_status_check CHECK (status IN ('PLACED','CONFIRMED','NEW','IN_PRODUCTION','READY','NO_SHOW','DELIVERED','COMPLETED','CANCELLED'));
COMMIT;
