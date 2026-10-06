package com.confectionery.crm.api.dto;

import com.confectionery.crm.domain.Order;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

public record OrderDto(
        Long id,
        Long clientId,
        String clientName,
        String status,
        String statusLabel,
        LocalDateTime createdAt,
        LocalDate dueDate,
        String notes,
        BigDecimal totalPrice,
        String vehicleNumber,
        LocalDateTime shippedAt,
        String shippedByName,
        LocalDate debtDueDate,
        boolean debtPaid,
        boolean fromWhatsapp,
        List<OrderItemDto> items,
        List<ProductionTaskDto> productionTasks,
        BigDecimal returnTotal,
        BigDecimal netTotal,
        int returnsCount,
        /** Номер накладной — номер «Реализации» в 1С без нулей, до выгрузки — номер заказа. */
        String invoiceNumber
) {
    public static OrderDto from(Order o) {
        return new OrderDto(
                o.getId(), o.getClient().getId(), o.getClient().getName(),
                o.getStatus().name(), o.getStatus().getLabel(),
                o.getCreatedAt(), o.getDueDate(), o.getNotes(), o.getTotalPrice(),
                o.getVehicleNumber(), o.getShippedAt(), o.getShippedByName(),
                o.getDebtDueDate(), Boolean.TRUE.equals(o.getDebtPaid()), Boolean.TRUE.equals(o.getFromWhatsapp()),
                o.getItems().stream().map(OrderItemDto::from).toList(),
                o.getProductionTasks().stream().map(ProductionTaskDto::from).toList(),
                o.getReturnTotal(), o.getNetTotal(), o.getReturns().size(),
                com.confectionery.crm.service.OrderService.invoiceNumber(o)
        );
    }

    public static OrderSummaryDto summary(Order o) {
        return new OrderSummaryDto(o.getId(), o.getClient().getId(), o.getClient().getName(),
                o.getStatus().name(), o.getStatus().getLabel(), o.getCreatedAt(), o.getDueDate(), o.getTotalPrice(),
                o.getVehicleNumber(), o.getShippedAt(), o.getDebtDueDate(), Boolean.TRUE.equals(o.getDebtPaid()),
                Boolean.TRUE.equals(o.getFromWhatsapp()),
                o.getReturnTotal(), o.getNetTotal(), o.getReturns().size());
    }
}
