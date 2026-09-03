package com.confectionery.crm.api.dto;

import com.confectionery.crm.service.ProductionRequestService;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

public record DailyProductionReportDto(LocalDate date, List<Item> items) {

    public record Item(String productName, String unit, BigDecimal batches, BigDecimal norm, BigDecimal received,
                        BigDecimal deviation, int receivedCount) {
        static Item from(ProductionRequestService.ReportLine l) {
            return new Item(l.productName(), l.unit(), l.batches(), l.norm(), l.received(), l.deviation(), l.receivedCount());
        }
    }

    public static DailyProductionReportDto of(LocalDate date, List<ProductionRequestService.ReportLine> lines) {
        return new DailyProductionReportDto(date, lines.stream().map(Item::from).toList());
    }
}
