package com.confectionery.crm.api.dto;

import com.confectionery.crm.service.OneCStockSyncService;

import java.util.List;

public record OneCSyncResultDto(int productsUpdated, int ingredientsUpdated, int productsDeactivated, int ingredientsDeactivated, List<String> unmatched) {

    public static OneCSyncResultDto from(OneCStockSyncService.SyncResult result) {
        return new OneCSyncResultDto(result.productsUpdated(), result.ingredientsUpdated(), result.productsDeactivated(), result.ingredientsDeactivated(), result.unmatched());
    }
}
