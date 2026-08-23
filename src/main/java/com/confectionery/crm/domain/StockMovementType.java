package com.confectionery.crm.domain;

public enum StockMovementType {
    RECEIPT("Поступление"),
    WRITE_OFF("Списание");

    private final String label;

    StockMovementType(String label) {
        this.label = label;
    }

    public String getLabel() {
        return label;
    }
}
