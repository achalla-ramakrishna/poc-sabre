package com.rewardsiq.pricing;

import com.rewardsiq.rule.Cabin;
import com.rewardsiq.rule.RedemptionRule;
import com.rewardsiq.rule.RuleRepository;
import com.rewardsiq.rule.RuleStatus;
import com.rewardsiq.security.CurrentUser;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.OptionalLong;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** UC-04: what would a traveller see? Against live rules, or against everyone's latest drafts. */
@RestController
@RequestMapping("/api/v1/preview")
public class PreviewController {

    private final RulesCache cache;
    private final RuleRepository rules;
    private final PointsCalculator calculator;
    private final Clock clock;

    public PreviewController(RulesCache cache, RuleRepository rules, PointsCalculator calculator, Clock clock) {
        this.cache = cache;
        this.rules = rules;
        this.calculator = calculator;
        this.clock = clock;
    }

    @PostMapping
    @PreAuthorize("hasAnyRole('VIEWER','ANALYST','APPROVER','ADMIN')")
    public PreviewResponse preview(@Valid @RequestBody PreviewRequest req, @AuthenticationPrincipal Jwt jwt) {
        String airline = CurrentUser.from(jwt).airline();
        RuleIndex index = req.ruleSet() == RuleSet.DRAFT ? draftIndex(airline) : cache.current();
        OfferQuery offer = new OfferQuery("preview", req.origin().toUpperCase(), req.destination().toUpperCase(),
                req.cabin(), upper(req.fareBrand()), upper(req.bundle()), upper(req.pointOfSale()), req.fareAmount(),
                req.currency().toUpperCase());
        LocalDate saleDate = req.saleDate() == null ? LocalDate.now(clock) : req.saleDate();
        List<RedemptionRule> best = index.bestMatches(airline, offer, saleDate);
        if (best.isEmpty()) {
            return new PreviewResponse(true, 0, 0, null, null, null, List.of(),
                    "Cash only: no redemption rule covers this offer");
        }
        RedemptionRule rule = best.get(0);
        PointsResult r = calculator.price(rule, offer,
                req.memberBalance() == null ? OptionalLong.empty() : OptionalLong.of(req.memberBalance()));
        List<String> ties = best.stream().skip(1).map(t -> t.getRuleId() + " v" + t.getVersion()).toList();
        String message = r.cashOnly()
                ? "Cash only: the matching rule has no rate for this fare brand or currency"
                : String.format("%s %s or %,d points", req.currency().toUpperCase(),
                req.fareAmount().toPlainString(), r.pointsRequired());
        return new PreviewResponse(r.cashOnly(), r.pointsRequired(), r.pointsUsable(), rule.getRuleId(),
                rule.getVersion(), rule.getName(), ties, message);
    }

    /** Latest version of every rule that isn't retired, as if all of them were live. */
    private RuleIndex draftIndex(String airline) {
        Map<String, RedemptionRule> latest = rules.findByAirline(airline).stream()
                .collect(Collectors.toMap(RedemptionRule::getRuleId, Function.identity(),
                        (a, b) -> a.getVersion() >= b.getVersion() ? a : b));
        return RuleIndex.of(latest.values().stream()
                .filter(r -> r.getStatus() != RuleStatus.RETIRED && r.getStatus() != RuleStatus.RETIRE_PENDING)
                .sorted(Comparator.comparing(RedemptionRule::getRuleId))
                .toList());
    }

    private static String upper(String s) {
        return s == null ? null : s.toUpperCase();
    }

    public enum RuleSet { LIVE, DRAFT }

    public record PreviewRequest(
            @NotNull RuleSet ruleSet,
            @NotBlank @Pattern(regexp = "(?i)[A-Z]{3}") String origin,
            @NotBlank @Pattern(regexp = "(?i)[A-Z]{3}") String destination,
            @NotNull Cabin cabin,
            String fareBrand,
            String bundle,
            String pointOfSale,
            @NotNull @Positive BigDecimal fareAmount,
            @NotBlank @Pattern(regexp = "(?i)[A-Z]{3}") String currency,
            @PositiveOrZero Long memberBalance,
            LocalDate saleDate) {
    }

    public record PreviewResponse(boolean cashOnly, long pointsRequired, long pointsUsable, String ruleId,
                                  Integer ruleVersion, String ruleName, List<String> tiedWith, String message) {
    }
}
