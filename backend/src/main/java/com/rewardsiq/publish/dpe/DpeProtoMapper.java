package com.rewardsiq.publish.dpe;

import com.google.protobuf.Timestamp;
import com.rewardsiq.grpc.publish.v1.Cabin;
import com.rewardsiq.grpc.publish.v1.RedemptionRule;
import com.rewardsiq.grpc.publish.v1.Scope;
import com.rewardsiq.grpc.publish.v1.Rate;
import java.util.Comparator;
import java.util.Objects;

/**
 * Anti-corruption layer: the only place that knows DPE's message types. A DPE contract change
 * touches this class and its test, nothing in the domain.
 */
public final class DpeProtoMapper {

    private DpeProtoMapper() {
    }

    public static RedemptionRule toProto(com.rewardsiq.rule.RedemptionRule r) {
        var s = r.getScope();
        Scope scope = Scope.newBuilder()
                .addAllOrigins(s.origins().stream().sorted().toList())
                .addAllDestinations(s.destinations().stream().sorted().toList())
                .addAllCabins(s.cabins().stream().sorted().map(c -> Cabin.valueOf(c.name())).toList())
                .addAllFareBrands(s.fareBrands().stream().sorted().toList())
                .addAllBundles(s.bundles().stream().sorted().toList())
                .addAllPointsOfSale(s.pointsOfSale().stream().sorted().toList())
                .build();
        RedemptionRule.Builder b = RedemptionRule.newBuilder()
                .setAirline(r.getAirline())
                .setRuleId(r.getRuleId())
                .setVersion(r.getVersion())
                .setScope(scope)
                .setMaxRedeemPercent(r.getLimits().maxRedeemPercent())
                .setMinPoints(r.getLimits().minPoints())
                .setEffectiveFrom(r.getEffectiveFrom())
                .setEffectiveTo(Objects.requireNonNullElse(r.getEffectiveTo(), ""))
                .setSpecificity(r.getSpecificity());
        r.getRates().stream()
                .sorted(Comparator.comparing(rate -> Objects.requireNonNullElse(rate.fareBrand(), "")))
                .forEach(rate -> b.addRates(Rate.newBuilder()
                        .setFareBrand(Objects.requireNonNullElse(rate.fareBrand(), ""))
                        .setPointsPerUnit(rate.pointsPerUnit().toPlainString())
                        .setCurrency(rate.currency())
                        .setRoundingStep(rate.roundingStep())));
        if (r.getApprovedAt() != null) {
            b.setApprovedAt(Timestamp.newBuilder()
                    .setSeconds(r.getApprovedAt().getEpochSecond())
                    .setNanos(r.getApprovedAt().getNano()));
        }
        return b.build();
    }
}
