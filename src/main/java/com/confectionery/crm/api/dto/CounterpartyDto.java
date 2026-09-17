package com.confectionery.crm.api.dto;

import com.confectionery.crm.domain.Counterparty;

public record CounterpartyDto(Long id, String name, boolean oneCLinked, String oneCSyncError) {
    public static CounterpartyDto from(Counterparty c) {
        return new CounterpartyDto(c.getId(), c.getName(), c.getOneCContragentKey() != null, c.getOneCSyncError());
    }
}
