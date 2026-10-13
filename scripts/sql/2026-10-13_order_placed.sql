-- 13.10: статус «Новая» (PLACED) у заказов клиента, откуда заказ (source), время принятия/сборки.
-- ddl-auto=update не переписывает CHECK по enum — расширяем вручную. Запуск на VM:
-- docker exec -i crm-postgres psql -U crm -d crm -v ON_ERROR_STOP=1 < 2026-10-13_order_placed.sql
BEGIN;
ALTER TABLE orders DROP CONSTRAINT IF EXISTS orders_status_check;
ALTER TABLE orders ADD CONSTRAINT orders_status_check CHECK (status IN ('PLACED','NEW','CONFIRMED','IN_PRODUCTION','READY','DELIVERED','COMPLETED','CANCELLED'));
ALTER TABLE orders ADD COLUMN IF NOT EXISTS source varchar(16);
ALTER TABLE orders ADD COLUMN IF NOT EXISTS accepted_at timestamp(6);
ALTER TABLE orders ADD COLUMN IF NOT EXISTS ready_at timestamp(6);
ALTER TABLE orders DROP CONSTRAINT IF EXISTS orders_source_check;
ALTER TABLE orders ADD CONSTRAINT orders_source_check CHECK (source IN ('SITE','WHATSAPP','CRM'));
-- откуда заказ у старых: WhatsApp — по флагу, сайт — по push админу «создан клиентом», остальное (кроме импорта из 1С) — сотрудник
UPDATE orders SET source = 'WHATSAPP' WHERE source IS NULL AND from_whatsapp = true;
UPDATE orders SET source = 'SITE' WHERE source IS NULL AND id IN (SELECT order_id FROM notifications WHERE order_id IS NOT NULL AND message LIKE '%создан клиентом%');
UPDATE orders SET source = 'CRM' WHERE source IS NULL AND imported_from_onec = false;
UPDATE orders SET accepted_at = created_at WHERE accepted_at IS NULL AND source = 'CRM';
COMMIT;
SELECT source, count(*) FROM orders GROUP BY source ORDER BY 1;
