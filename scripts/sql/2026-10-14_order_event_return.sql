-- 14.10: в истории заказа — возврат к заказу (принят, изменён, удалён). Таблицу order_events создал
-- ddl-auto=update с CHECK по enum OrderEventType, сам он CHECK не переписывает — расширяем вручную,
-- ДО перезапуска backend с новой версией. Запуск на VM:
-- docker exec -i crm-postgres psql -U crm -d crm -v ON_ERROR_STOP=1 < 2026-10-14_order_event_return.sql
-- Локальная H2: имя ограничения другое — SELECT CONSTRAINT_NAME FROM INFORMATION_SCHEMA.TABLE_CONSTRAINTS
-- WHERE TABLE_NAME = 'ORDER_EVENTS' AND CONSTRAINT_TYPE = 'CHECK'; затем DROP CONSTRAINT <имя> и тот же ADD.
BEGIN;
ALTER TABLE order_events DROP CONSTRAINT IF EXISTS order_events_type_check;
ALTER TABLE order_events ADD CONSTRAINT order_events_type_check
    CHECK (type IN ('STATUS','MOVE','EDIT','PAY','RETURN','RETURN_EDIT','RETURN_DELETE'));
COMMIT;
