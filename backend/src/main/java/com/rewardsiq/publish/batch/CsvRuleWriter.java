package com.rewardsiq.publish.batch;

import com.rewardsiq.rule.Rate;
import com.rewardsiq.rule.RedemptionRule;
import java.time.Instant;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Plan B snapshot format (Data flow tab): UTF-8 CSV with a header row, one row per rule version
 * per rate, list values joined with ';', empty list = "all".
 */
public final class CsvRuleWriter {

    static final List<String> HEADER = List.of("airline", "rule_id", "version", "status", "origins", "destinations",
            "cabins", "fare_brands", "bundles", "points_of_sale", "fare_brand", "points_per_unit", "currency",
            "rounding_step", "max_redeem_percent", "min_points", "effective_from", "effective_to", "specificity",
            "generated_at");

    private CsvRuleWriter() {
    }

    public static String write(Collection<RedemptionRule> rules, Instant generatedAt) {
        StringBuilder csv = new StringBuilder(String.join(",", HEADER)).append('\n');
        rules.stream()
                .sorted(Comparator.comparing(RedemptionRule::getAirline).thenComparing(RedemptionRule::getRuleId))
                .forEach(rule -> rule.getRates().stream()
                        .sorted(Comparator.comparing(r -> Objects.requireNonNullElse(r.fareBrand(), "")))
                        .forEach(rate -> csv.append(row(rule, rate, generatedAt)).append('\n')));
        return csv.toString();
    }

    private static String row(RedemptionRule rule, Rate rate, Instant generatedAt) {
        var s = rule.getScope();
        return String.join(",", List.of(
                rule.getAirline(), rule.getRuleId(), String.valueOf(rule.getVersion()), rule.getStatus().name(),
                join(s.origins()), join(s.destinations()),
                join(s.cabins().stream().map(Enum::name).collect(Collectors.toSet())),
                join(s.fareBrands()), join(s.bundles()), join(s.pointsOfSale()),
                Objects.requireNonNullElse(rate.fareBrand(), ""),
                rate.pointsPerUnit().toPlainString(), rate.currency(), String.valueOf(rate.roundingStep()),
                String.valueOf(rule.getLimits().maxRedeemPercent()), String.valueOf(rule.getLimits().minPoints()),
                rule.getEffectiveFrom(), Objects.requireNonNullElse(rule.getEffectiveTo(), ""),
                String.valueOf(rule.getSpecificity()), generatedAt.toString()))
                .transform(CsvRuleWriter::guard);
    }

    private static String join(Set<String> values) {
        return values.stream().sorted().collect(Collectors.joining(";"));
    }

    /** Codes are validated upper-case tokens, but never let a stray comma or quote break the file. */
    private static String guard(String line) {
        if (line.contains("\"") || line.contains("\n")) {
            throw new IllegalStateException("Unexpected character in CSV row: " + line);
        }
        return line;
    }
}
