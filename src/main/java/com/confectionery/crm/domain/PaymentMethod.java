package com.confectionery.crm.domain;

/** Способ оплаты заказа (Order.paymentMethod). null — старый заказ или заказ с сайта без выбора. */
public enum PaymentMethod {
    CASH("Наличными при выдаче", "Наличными"),
    INVOICE("По счёту", "По счёту"),
    KASPI("Kaspi", "Kaspi");

    private final String label;
    private final String shortLabel;

    PaymentMethod(String label, String shortLabel) {
        this.label = label;
        this.shortLabel = shortLabel;
    }

    public String getLabel() {
        return label;
    }

    public String getShortLabel() {
        return shortLabel;
    }
}
