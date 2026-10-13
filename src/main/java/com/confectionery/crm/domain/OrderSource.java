package com.confectionery.crm.domain;

/** Кто завёл заказ (Order.source): клиент на сайте /shop, клиент в WhatsApp или сотрудник в CRM. */
public enum OrderSource {
    SITE,
    WHATSAPP,
    CRM
}
