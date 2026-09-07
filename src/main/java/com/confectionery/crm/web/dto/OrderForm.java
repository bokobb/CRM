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

    private List<OrderItemForm> items = new ArrayList<>();

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
