# Модель данных (PostgreSQL)

Сгенерировано из копии боевой базы 05.10.2026 (`pg_dump`). Схему создаёт и обновляет Hibernate (`ddl-auto=update`) по JPA-сущностям из `src/main/java/com/confectionery/crm/domain/`; изменения, которые Hibernate сам не делает (NOT NULL на непустой таблице, CHECK-ограничения enum-полей), вносятся вручную SQL-ом — см. operations.md.

| Таблица | Строк на 05.10 | Колонки |
|---|---|---|
| `client_prices` | 315 | `id`, `price`, `updated_at`, `client_id`, `product_id` |
| `clients` | 15 | `id`, `address`, `created_at`, `email`, `name`, `notes`, `phone`, `oneccontract_key`, `oneccontragent_key`, `onecprice_type_key` |
| `company_profile` | 1 | `id`, `address`, `email`, `name`, `phone` |
| `counterparties` | 47 | `id`, `name`, `one_c_contragent_key`, `one_c_contract_key`, `oneccontract_key`, `oneccontragent_key`, `onecsync_error` |
| `employees` | 43 | `id`, `active`, `created_at`, `daily_rate`, `full_name`, `phone`, `position`, `hired_at`, `iin`, `oneckey`, `birth_date`, `cost_group`, `monthly_salary` |
| `ingredients` | 167 | `id`, `min_threshold`, `name`, `quantity_in_stock`, `unit`, `onecnomenclature_key`, `active` |
| `notifications` | 2184 | `id`, `channel`, `created_at`, `message`, `recipient`, `sent`, `order_id` |
| `order_items` | 2203 | `id`, `price`, `quantity`, `order_id`, `product_id` |
| `orders` | 176 | `id`, `created_at`, `due_date`, `notes`, `status`, `client_id`, `shipped_at`, `shipped_by_name`, `vehicle_number`, `debt_due_date`, `debt_paid`, `from_whatsapp`, `onecdocument_key`, `onecdocument_number`, `onecsync_error`, `imported_from_onec`, `onec_pending` |
| `product_aliases` | 8 | `id`, `alias`, `created_at`, `product_id` |
| `product_return_items` | 1733 | `id`, `price`, `quantity`, `product_id`, `product_return_id` |
| `product_returns` | 97 | `id`, `created_at`, `created_by`, `onecdocument_key`, `onecdocument_number`, `onecsync_error`, `reason`, `return_date`, `client_id`, `order_id`, `onec_pending` |
| `production_brigade_members` | 3 | `id`, `employee_id`, `request_id` |
| `production_request_items` | 2529 | `id`, `required_quantity`, `ingredient_id`, `request_id`, `original_ingredient_name` |
| `production_request_lines` | 359 | `id`, `batches`, `planned_quantity`, `received_at`, `received_by`, `received_quantity`, `product_id`, `request_id` |
| `production_request_revision_items` | 14528 | `revision_id`, `ingredient_id`, `ingredient_name`, `quantity`, `unit`, `item_order` |
| `production_request_revisions` | 762 | `id`, `created_at`, `created_by`, `issued`, `note`, `request_id` |
| `production_requests` | 190 | `id`, `collected_at`, `collected_by`, `created_at`, `created_by`, `status`, `title`, `onecreport_key`, `onecreport_number`, `onecrequisition_key`, `onecrequisition_number`, `onecsync_error`, `document_date`, `requisition_completed`, `composition_diff_pending`, `onec_pending` |
| `production_tasks` | 0 | `id`, `assigned_to`, `completed_at`, `created_at`, `quantity`, `status`, `order_id`, `product_id` |
| `products` | 62 | `id`, `category`, `description`, `name`, `price`, `unit`, `quantity_in_stock`, `image_url`, `active`, `product_group`, `onecline_template`, `onecnomenclature_key`, `onecreceipt_template`, `semi_finished`, `onecspecification_key`, `onecdepartment_key`, `net_weight_grams`, `mass_reference_product`, `hidden_from_clients`, `hidden_from_clients_until` |
| `purchase_items` | 362 | `id`, `purchase_id`, `ingredient_id`, `quantity`, `total_price`, `stock_movement_id` |
| `purchases` | 182 | `id`, `created_at`, `created_by`, `purchase_date`, `image_url`, `counterparty_id`, `one_c_document_key`, `one_c_document_number`, `one_c_sync_error`, `onecdocument_key`, `onecdocument_number`, `onecsync_error`, `onec_pending` |
| `recipe_items` | 628 | `id`, `quantity`, `ingredient_id`, `recipe_id` |
| `recipes` | 50 | `id`, `yield_quantity`, `product_id` |
| `stock_movements` | 4242 | `id`, `created_at`, `created_by`, `quantity`, `reason`, `type`, `ingredient_id`, `product_id`, `manual`, `onecdocument_number`, `onecsynced_at` |
| `tech_card_stages` | 385 | `id`, `ccp`, `description`, `duration`, `position`, `temperature`, `title`, `tech_card_id` |
| `tech_cards` | 50 | `id`, `packaging`, `quality_color`, `quality_crumb`, `quality_look`, `quality_taste`, `shelf_life`, `storage_conditions`, `product_id`, `draft` |
| `timesheet_marks` | 174 | `id`, `work_date`, `status`, `updated_at`, `updated_by`, `employee_id` |
| `users` | 5 | `id`, `created_at`, `enabled`, `full_name`, `password_hash`, `role`, `username` |
| `whatsapp_inbound_items` | 13 | `id`, `quantity`, `raw_product_text`, `message_id`, `product_id` |
| `whatsapp_inbound_messages` | 8 | `id`, `created_at`, `from_phone`, `raw_text`, `resolved_order_id`, `status`, `client_id` |

## CHECK-ограничения enum-полей

При добавлении нового значения в Java-enum (статус, роль и т.п.) Hibernate НЕ обновляет эти ограничения — их нужно пересоздать вручную (`ALTER TABLE … DROP CONSTRAINT …; ADD CONSTRAINT … CHECK (…)`), иначе сохранение упадёт.

- `employees_cost_group_check` (поле `cost_group`)
- `orders_status_check` (поле `status`)
- `production_requests_status_check` (поле `status`)
- `production_tasks_status_check` (поле `status`)
- `stock_movements_type_check` (поле `type`)
- `users_role_check` (поле `role`)
- `whatsapp_inbound_messages_status_check` (поле `status`)
