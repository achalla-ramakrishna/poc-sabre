package com.rewardsiq.rule;

import java.util.Set;
import java.util.stream.Stream;

/**
 * Where a rule applies (BR-01). An empty set means "all values" for that dimension.
 */
public record Scope(
        Set<String> origins,
        Set<String> destinations,
        Set<Cabin> cabins,
        Set<String> fareBrands,
        Set<String> bundles,
        Set<String> pointsOfSale) {

    public Scope {
        origins = copy(origins);
        destinations = copy(destinations);
        cabins = cabins == null ? Set.of() : Set.copyOf(cabins);
        fareBrands = copy(fareBrands);
        bundles = copy(bundles);
        pointsOfSale = copy(pointsOfSale);
    }

    private static Set<String> copy(Set<String> values) {
        return values == null ? Set.of() : Set.copyOf(values);
    }

    /** Number of non-empty dimensions; the most specific matching rule wins (BR-05). */
    public int specificity() {
        return (int) Stream.of(origins, destinations, cabins, fareBrands, bundles, pointsOfSale)
                .filter(s -> !s.isEmpty())
                .count();
    }

    /** True if some offer could match both scopes: every dimension is "all" on one side or shares a value. */
    public boolean overlaps(Scope other) {
        return intersects(origins, other.origins)
                && intersects(destinations, other.destinations)
                && intersects(cabins, other.cabins)
                && intersects(fareBrands, other.fareBrands)
                && intersects(bundles, other.bundles)
                && intersects(pointsOfSale, other.pointsOfSale);
    }

    private static <T> boolean intersects(Set<T> a, Set<T> b) {
        return a.isEmpty() || b.isEmpty() || a.stream().anyMatch(b::contains);
    }

    static <T> boolean allows(Set<T> allowed, T value) {
        return allowed.isEmpty() || (value != null && allowed.contains(value));
    }
}
