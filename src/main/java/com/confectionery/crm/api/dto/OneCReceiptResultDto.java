package com.confectionery.crm.api.dto;

import com.confectionery.crm.service.OneCReceiptSyncService;

import java.util.List;

public record OneCReceiptResultDto(int movementsCount, String documentNumber, List<String> unresolved) {

    public static OneCReceiptResultDto from(OneCReceiptSyncService.PostReceiptsResult result) {
        return new OneCReceiptResultDto(result.movementsCount(), result.documentNumber(), result.unresolved());
    }
}
