package com.rewardsiq.pricing;

import com.rewardsiq.rule.RedemptionRule;
import com.rewardsiq.rule.Scope;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

/**
 * Immutable lookup structure for rule matching (BR-05). Rules are bucketed by
 * airline + origin + destination, with "*" standing for "all"; an offer SYD->PER checks four
 * buckets (SYD|PER, SYD|*, *|PER, *|*) instead of scanning every rule.
 */
public final class RuleIndex {

    static final String ANY = "*";
    private static final Comparator<RedemptionRule> MOST_SPECIFIC_FIRST =
            Comparator.comparingInt(RedemptionRule::getSpecificity).reversed()
                    .thenComparing(r -> r.getApprovedAt() == null ? Instant.EPOCH : r.getApprovedAt(),
                            Comparator.reverseOrder());

    private final Map<String, List<RedemptionRule>> buckets;
    private final int size;

    private RuleIndex(Map<String, List<RedemptionRule>> buckets, int size) {
        this.buckets = buckets;
        this.size = size;
    }

    public static RuleIndex of(Collection<RedemptionRule> rules) {
        Map<String, List<RedemptionRule>> buckets = new HashMap<>();
        for (RedemptionRule rule : rules) {
            for (String o : keysOf(rule.getScope().origins())) {
                for (String d : keysOf(rule.getScope().destinations())) {
                    buckets.computeIfAbsent(key(rule.getAirline(), o, d), k -> new ArrayList<>()).add(rule);
                }
            }
        }
        buckets.replaceAll((k, list) -> list.stream().sorted(MOST_SPECIFIC_FIRST).toList());
        return new RuleIndex(Map.copyOf(buckets), rules.size());
    }

    public static RuleIndex empty() {
        return new RuleIndex(Map.of(), 0);
    }

    /** Every rule tied for the top specificity; normally one (publish-time checks prevent ties). */
    public List<RedemptionRule> bestMatches(String airline, OfferQuery offer, LocalDate saleDate) {
        List<RedemptionRule> candidates = Stream.of(
                        key(airline, offer.origin(), offer.destination()),
                        key(airline, offer.origin(), ANY),
                        key(airline, ANY, offer.destination()),
                        key(airline, ANY, ANY))
                .distinct()
                .flatMap(k -> buckets.getOrDefault(k, List.of()).stream())
                .filter(r -> matches(r.getScope(), offer) && r.isEffectiveOn(saleDate))
                .sorted(MOST_SPECIFIC_FIRST)
                .toList();
        if (candidates.isEmpty()) {
            return List.of();
        }
        int top = candidates.get(0).getSpecificity();
        return candidates.stream().filter(r -> r.getSpecificity() == top).toList();
    }

    public int size() {
        return size;
    }

    static boolean matches(Scope s, OfferQuery o) {
        return allows(s.origins(), o.origin())
                && allows(s.destinations(), o.destination())
                && allows(s.cabins(), o.cabin())
                && allows(s.fareBrands(), o.fareBrand())
                && allows(s.bundles(), blankToNull(o.bundle()))
                && allows(s.pointsOfSale(), blankToNull(o.pointOfSale()));
    }

    private static <T> boolean allows(Set<T> allowed, T value) {
        return allowed.isEmpty() || (value != null && allowed.contains(value));
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s;
    }

    private static Set<String> keysOf(Set<String> values) {
        return values.isEmpty() ? Set.of(ANY) : values;
    }

    private static String key(String airline, String origin, String destination) {
        return airline + "|" + origin + "|" + destination;
    }
}
