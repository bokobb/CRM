package com.confectionery.crm.api.dto;

import com.confectionery.crm.service.OneCStockSyncService;

import java.util.List;

public record OneCSyncResultDto(int productsUpdated, int ingredientsUpdated, int productsDeactivated, List<String> unmatched) {

    public static OneCSyncResultDto from(OneCStockSyncService.SyncResult result) {
        return new OneCSyncResultDto(result.productsUpdated(), result.ingredientsUpdated(), result.productsDeactivated(), result.unmatched());
    }
}
