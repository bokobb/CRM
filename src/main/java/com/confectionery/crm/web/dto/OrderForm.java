package com.confectionery.crm.web.dto;

import jakarta.validation.constraints.NotNull;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

public class OrderForm {

    @NotNull(message = "Выберите клиента")
    private Long clientId;

    private LocalDate dueDate;

    private String notes;

    /** Заполняется только WhatsAppOrderIntakeService — не выставляется формами ручного ввода. */
    private boolean fromWhatsapp;

    /** Заполняется только ShopController (публичная витрина /shop) — заказ создал сам клиент. */
    private boolean fromShop;

    private List<OrderItemForm> items = new ArrayList<>();

    /** Окно выдачи — час начала в день dueDate (админка); null — без окна. */
    private Integer pickupHour;

    /** Способ оплаты (админка); null — не выбран. */
    private com.confectionery.crm.domain.PaymentMethod paymentMethod;

    public Integer getPickupHour() {
        return pickupHour;
    }

    public void setPickupHour(Integer pickupHour) {
        this.pickupHour = pickupHour;
    }

    public com.confectionery.crm.domain.PaymentMethod getPaymentMethod() {
        return paymentMethod;
    }

    public void setPaymentMethod(com.confectionery.crm.domain.PaymentMethod paymentMethod) {
        this.paymentMethod = paymentMethod;
    }

    public Long getClientId() {
        return clientId;
    }

    public void setClientId(Long clientId) {
        this.clientId = clientId;
    }

    public LocalDate getDueDate() {
        return dueDate;
    }

    public void setDueDate(LocalDate dueDate) {
        this.dueDate = dueDate;
    }

    public String getNotes() {
        return notes;
    }

    public void setNotes(String notes) {
        this.notes = notes;
    }

    public List<OrderItemForm> getItems() {
        return items;
    }

    public void setItems(List<OrderItemForm> items) {
        this.items = items;
    }

    public boolean isFromWhatsapp() {
        return fromWhatsapp;
    }

    public void setFromWhatsapp(boolean fromWhatsapp) {
        this.fromWhatsapp = fromWhatsapp;
    }

    public boolean isFromShop() {
        return fromShop;
    }

    public void setFromShop(boolean fromShop) {
        this.fromShop = fromShop;
    }

    public static class OrderItemForm {
        /** Заполняется только формой редактирования (orders/edit) для уже существующих позиций; null — новая позиция. */
        private Long itemId;
        private Long productId;
        private java.math.BigDecimal quantity;

        public Long getItemId() {
            return itemId;
        }

        public void setItemId(Long itemId) {
            this.itemId = itemId;
        }

        public Long getProductId() {
            return productId;
        }

        public void setProductId(Long productId) {
            this.productId = productId;
        }

        public java.math.BigDecimal getQuantity() {
            return quantity;
        }

        public void setQuantity(java.math.BigDecimal quantity) {
            this.quantity = quantity;
        }
    }
}
