package com.rewardsiq.pricing

import spock.lang.Specification
import spock.lang.Unroll

/** BR-03 and BR-04 as a table analysts can read and extend. */
class PointsCalculatorSpec extends Specification {

    @Unroll
    def "fare #fare at #rate pts/unit, step #step needs #required points"() {
        expect:
        PointsCalculator.pointsRequired(new BigDecimal(fare), new BigDecimal(rate), step) == required

        where:
        fare      | rate   | step || required
        "900.00"  | "47.8" | 100  || 43_100
        "1800.00" | "47.8" | 100  || 86_100
        "100"     | "10"   | 100  || 1_000
        "100.01"  | "10"   | 100  || 1_100
        "99.99"   | "50"   | 10   || 5_000
        "0.01"    | "1"    | 1    || 1
    }

    @Unroll
    def "balance #balance at #cap% for #required required: #usable usable (min #min)"() {
        expect:
        PointsCalculator.pointsUsable(required, balance, cap, 100, min) == usable

        where:
        required | balance | cap | min   || usable
        43_100   | 100_000 | 70  | 5_000 || 43_100
        86_100   | 100_000 | 70  | 5_000 || 70_000
        43_100   | 5_000   | 70  | 5_000 || 0
        43_100   | 100_000 | 0   | 0     || 0
        43_100   | 0       | 70  | 0     || 0
        1_000    | 999     | 100 | 0     || 900
    }
}
