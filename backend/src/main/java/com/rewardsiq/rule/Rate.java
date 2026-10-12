package com.rewardsiq.rule;

import java.math.BigDecimal;
import org.springframework.data.mongodb.core.mapping.Field;
import org.springframework.data.mongodb.core.mapping.FieldType;

/**
 * Points per currency unit for one fare brand. A null/blank fareBrand is the rule's default rate.
 * pointsPerUnit is stored as Decimal128: never floating point for money or rates.
 */
public record Rate(
        String fareBrand,
        @Field(targetType = FieldType.DECIMAL128) BigDecimal pointsPerUnit,
        String currency,
        int roundingStep) {

    public boolean isDefault() {
        return fareBrand == null || fareBrand.isBlank();
    }
}
