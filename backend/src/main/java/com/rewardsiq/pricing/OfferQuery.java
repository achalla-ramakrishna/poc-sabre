package com.rewardsiq.pricing;

import com.rewardsiq.rule.Cabin;
import java.math.BigDecimal;

/** One offer to price, as received from DPE (or typed into the Preview screen). */
public record OfferQuery(
        String offerId,
        String origin,
        String destination,
        Cabin cabin,
        String fareBrand,
        String bundle,
        String pointOfSale,
        BigDecimal fareAmount,
        String currency) {
}
