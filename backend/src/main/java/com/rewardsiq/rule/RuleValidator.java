package com.rewardsiq.rule;

import com.rewardsiq.common.error.RuleValidationException;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import org.springframework.stereotype.Component;

/**
 * Business-rule validation (Overview tab, BR-01..BR-07). Bean Validation on the DTOs catches
 * shape errors (missing fields); this class catches meaning errors and reports them per field.
 */
@Component
public class RuleValidator {

    static final Set<Integer> ROUNDING_STEPS = Set.of(1, 10, 50, 100, 1000);
    private static final Pattern IATA_AIRPORT = Pattern.compile("[A-Z]{3}");
    private static final Pattern COUNTRY = Pattern.compile("[A-Z]{2}");
    private static final Pattern CURRENCY = Pattern.compile("[A-Z]{3}");
    private static final Pattern CODE = Pattern.compile("[A-Z0-9_]{1,30}");

    /** Content checks for a draft. */
    public void validateContent(String name, Scope scope, List<Rate> rates, Limits limits,
                                LocalDate from, LocalDate to) {
        Map<String, String> errors = new LinkedHashMap<>();
        if (name == null || name.isBlank() || name.length() > 80) {
            errors.put("name", "1 to 80 characters");
        }
        codes(errors, "scope.origins", scope.origins(), IATA_AIRPORT, "3-letter IATA airport codes");
        codes(errors, "scope.destinations", scope.destinations(), IATA_AIRPORT, "3-letter IATA airport codes");
        codes(errors, "scope.pointsOfSale", scope.pointsOfSale(), COUNTRY, "2-letter country codes");
        codes(errors, "scope.fareBrands", scope.fareBrands(), CODE, "upper-case codes");
        codes(errors, "scope.bundles", scope.bundles(), CODE, "upper-case codes");
        validateRates(errors, scope, rates);
        // BR-02
        if (limits == null || limits.maxRedeemPercent() < 0 || limits.maxRedeemPercent() > 100) {
            errors.put("limits.maxRedeemPercent", "must be between 0 and 100");
        }
        if (limits != null && limits.minPoints() < 0) {
            errors.put("limits.minPoints", "must be 0 or more");
        }
        if (from == null) {
            errors.put("effectiveFrom", "is required");
        } else if (to != null && to.isBefore(from)) {
            errors.put("effectiveTo", "must not be before effectiveFrom");
        }
        if (!errors.isEmpty()) {
            throw new RuleValidationException("RULE_INVALID", "The rule has invalid fields", errors);
        }
    }

    private static void validateRates(Map<String, String> errors, Scope scope, List<Rate> rates) {
        if (rates == null || rates.isEmpty()) {
            errors.put("rates", "at least one rate is required");
            return;
        }
        long defaults = rates.stream().filter(Rate::isDefault).count();
        long distinctBrands = rates.stream().filter(r -> !r.isDefault()).map(Rate::fareBrand).distinct().count();
        if (defaults > 1 || distinctBrands != rates.size() - defaults) {
            errors.put("rates", "one rate per fare brand, and at most one default rate");
        }
        for (int i = 0; i < rates.size(); i++) {
            Rate r = rates.get(i);
            String p = "rates[" + i + "].";
            BigDecimal ppu = r.pointsPerUnit();
            if (ppu == null || ppu.signum() <= 0 || ppu.stripTrailingZeros().scale() > 4) {
                errors.put(p + "pointsPerUnit", "must be greater than 0 with at most 4 decimals");
            }
            if (r.currency() == null || !CURRENCY.matcher(r.currency()).matches()) {
                errors.put(p + "currency", "3-letter ISO 4217 code");
            }
            if (!ROUNDING_STEPS.contains(r.roundingStep())) {
                errors.put(p + "roundingStep", "one of " + ROUNDING_STEPS.stream().sorted().toList());
            }
            if (!r.isDefault() && !scope.fareBrands().isEmpty() && !scope.fareBrands().contains(r.fareBrand())) {
                errors.put(p + "fareBrand", "must be one of the rule's scope fare brands");
            }
        }
        if (rates.stream().map(Rate::currency).filter(Objects::nonNull).distinct().count() > 1) {
            errors.put("rates", "all rates in a rule use one currency");
        }
    }

    private static void codes(Map<String, String> errors, String field, Collection<String> values, Pattern pattern,
                              String message) {
        if (values.stream().anyMatch(v -> v == null || !pattern.matcher(v).matches())) {
            errors.put(field, message);
        }
    }

    /**
     * Publish-time checks against every other live or approved rule of the airline (BR-05, BR-06):
     * two rules that could match the same offer on the same dates must not have equal specificity,
     * otherwise DPE couldn't tell which one wins. Identical scopes are the extreme case of this.
     */
    public void checkConflicts(RedemptionRule candidate, Collection<RedemptionRule> live) {
        List<RedemptionRule> clashes = live.stream()
                .filter(other -> !other.getRuleId().equals(candidate.getRuleId()))
                .filter(other -> other.getStatus().isLiveOrQueued())
                .filter(other -> other.getSpecificity() == candidate.getSpecificity())
                .filter(other -> other.getScope().overlaps(candidate.getScope()))
                .filter(other -> other.datesOverlap(candidate))
                .toList();
        if (!clashes.isEmpty()) {
            String names = clashes.stream()
                    .map(r -> "'" + r.getName() + "' v" + r.getVersion())
                    .collect(Collectors.joining(", "));
            throw new RuleValidationException("RULE_CONFLICT",
                    "Rule overlaps " + names + " with the same specificity on overlapping dates",
                    Map.of("scope", "overlaps " + clashes.get(0).getRuleId() + " v" + clashes.get(0).getVersion()));
        }
    }
}
