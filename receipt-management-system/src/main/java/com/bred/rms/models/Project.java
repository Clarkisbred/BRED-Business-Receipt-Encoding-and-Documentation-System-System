package com.bred.rms.models;

import java.math.BigDecimal;
import java.util.UUID;

/** A project that receipts can be charged to. */
public record Project(UUID id, String name, String code, String fundSource,
                      BigDecimal contractAmount, BigDecimal revisedContractAmount) {
    public Project(UUID id, String name, String code, String fundSource) {
        this(id, name, code, fundSource, BigDecimal.ZERO, BigDecimal.ZERO);
    }
}
