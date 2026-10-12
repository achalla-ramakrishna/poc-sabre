package com.rewardsiq.rule;

import com.rewardsiq.audit.AuditService;
import com.rewardsiq.common.config.RewardsIqProperties;
import com.rewardsiq.common.error.NotFoundException;
import com.rewardsiq.common.error.PreconditionFailedException;
import com.rewardsiq.common.error.RuleValidationException;
import com.rewardsiq.publish.Operation;
import com.rewardsiq.publish.PublicationRepository;
import com.rewardsiq.publish.RulePublication;
import com.rewardsiq.rule.RuleDtos.RateDto;
import com.rewardsiq.rule.RuleDtos.RuleRequest;
import com.rewardsiq.rule.RuleDtos.RuleSummary;
import com.rewardsiq.security.CurrentUser;
import java.time.Clock;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Rule lifecycle (UC-02, UC-03, UC-05, retire part of UC-02). Every state change is audited, and
 * approval writes the outbox row in the same transaction so a publish can never be lost.
 */
@Service
public class RuleService {

    private static final Logger log = LoggerFactory.getLogger(RuleService.class);

    private final RuleRepository rules;
    private final PublicationRepository publications;
    private final RuleValidator validator;
    private final RuleIdGenerator ids;
    private final AuditService audit;
    private final RewardsIqProperties props;
    private final Clock clock;

    public RuleService(RuleRepository rules, PublicationRepository publications, RuleValidator validator,
                       RuleIdGenerator ids, AuditService audit, RewardsIqProperties props, Clock clock) {
        this.rules = rules;
        this.publications = publications;
        this.validator = validator;
        this.ids = ids;
        this.audit = audit;
        this.props = props;
        this.clock = clock;
    }

    @Transactional
    public RedemptionRule create(RuleRequest req, CurrentUser user) {
        List<Rate> rates = toRates(req);
        validator.validateContent(req.name(), req.scope(), rates, req.limits(), req.effectiveFrom(), req.effectiveTo());
        String ruleId = ids.next();
        requireUniqueName(user.airline(), req.name(), ruleId);
        RedemptionRule rule = rules.insert(new RedemptionRule(user.airline(), ruleId, 1, req.name().trim(),
                req.scope(), rates, req.limits(), req.effectiveFrom(), req.effectiveTo(), user.subject(),
                clock.instant()));
        audit.ruleAction(rule, "CREATE", user.subject(), null, Map.of());
        log.info("Rule {} v1 created by {} for {}", ruleId, user.subject(), user.airline());
        return rule;
    }

    /**
     * Edits the latest version. A DRAFT is edited in place; a PUBLISHED rule gets a new DRAFT
     * version and keeps the live one in force until the new one is published (BR-08).
     */
    @Transactional
    public RedemptionRule update(String ruleId, RuleRequest req, long expectedRevision, CurrentUser user) {
        RedemptionRule latest = latest(user.airline(), ruleId);
        checkRevision(latest, expectedRevision);
        List<Rate> rates = toRates(req);
        validator.validateContent(req.name(), req.scope(), rates, req.limits(), req.effectiveFrom(), req.effectiveTo());
        requireUniqueName(user.airline(), req.name(), ruleId);
        RedemptionRule target = switch (latest.getStatus()) {
            case DRAFT -> latest;
            case PUBLISHED -> latest.nextVersion(user.subject(), clock.instant());
            default -> throw new RuleStateException("INVALID_STATE",
                    "Rule " + ruleId + " is " + latest.getStatus() + "; it can't be edited until that completes");
        };
        String action = target == latest ? "EDIT" : "NEW_VERSION";
        RuleStatus before = target == latest ? latest.getStatus() : null;
        target.edit(req.name().trim(), req.scope(), rates, req.limits(), req.effectiveFrom(), req.effectiveTo());
        RedemptionRule saved = rules.save(target);
        audit.ruleAction(saved, action, user.subject(), before, Map.of());
        return saved;
    }

    @Transactional
    public RedemptionRule cloneRule(String ruleId, CurrentUser user) {
        RedemptionRule source = latest(user.airline(), ruleId);
        String newId = ids.next();
        String name = uniqueCopyName(user.airline(), source.getName());
        RedemptionRule copy = rules.insert(new RedemptionRule(user.airline(), newId, 1, name, source.getScope(),
                source.getRates(), source.getLimits(), source.effectiveFromDate(),
                source.effectiveToDate().orElse(null), user.subject(), clock.instant()));
        audit.ruleAction(copy, "CLONE", user.subject(), null, Map.of("clonedFrom", ruleId));
        return copy;
    }

    @Transactional
    public RedemptionRule submit(String ruleId, long expectedRevision, CurrentUser user) {
        RedemptionRule rule = latest(user.airline(), ruleId);
        checkRevision(rule, expectedRevision);
        RuleStatus before = rule.getStatus();
        rule.submit(user.subject());
        RedemptionRule saved = rules.save(rule);
        audit.ruleAction(saved, "SUBMIT", user.subject(), before, Map.of());
        return saved;
    }

    /** Approve (UC-05) and queue for publishing (UC-06) atomically. */
    @Transactional
    public RedemptionRule approve(String ruleId, long expectedRevision, CurrentUser user) {
        RedemptionRule rule = latest(user.airline(), ruleId);
        checkRevision(rule, expectedRevision);
        validator.checkConflicts(rule, rules.findByAirlineAndStatusIn(user.airline(),
                EnumSet.of(RuleStatus.APPROVED, RuleStatus.PUBLISHED)));
        RuleStatus before = rule.getStatus();
        rule.approve(user.subject(), clock.instant());
        RedemptionRule saved = rules.save(rule);
        publications.insert(new RulePublication(saved, Operation.UPSERT, props.publish().mode(), clock.instant()));
        audit.ruleAction(saved, "APPROVE", user.subject(), before, Map.of("publishMode", props.publish().mode().name()));
        log.info("Rule {} v{} approved by {}; queued for {} publish", ruleId, saved.getVersion(), user.subject(),
                props.publish().mode());
        return saved;
    }

    @Transactional
    public RedemptionRule reject(String ruleId, String reason, CurrentUser user) {
        RedemptionRule rule = latest(user.airline(), ruleId);
        RuleStatus before = rule.getStatus();
        rule.reject(reason);
        RedemptionRule saved = rules.save(rule);
        audit.ruleAction(saved, "REJECT", user.subject(), before, Map.of("reason", reason));
        return saved;
    }

    /** Retire the live version; it disappears from DPE once the RETIRE publication is acknowledged (BR-10). */
    @Transactional
    public RedemptionRule retire(String ruleId, CurrentUser user) {
        RedemptionRule live = rules.findByAirlineAndRuleIdOrderByVersionDesc(user.airline(), ruleId).stream()
                .filter(r -> r.getStatus() == RuleStatus.PUBLISHED)
                .findFirst()
                .orElseThrow(() -> new RuleStateException("NOT_LIVE", "Rule " + ruleId + " has no published version"));
        RuleStatus before = live.getStatus();
        live.requestRetire();
        RedemptionRule saved = rules.save(live);
        publications.insert(new RulePublication(saved, Operation.RETIRE, props.publish().mode(), clock.instant()));
        audit.ruleAction(saved, "RETIRE", user.subject(), before, Map.of());
        return saved;
    }

    public RedemptionRule latest(String airline, String ruleId) {
        return rules.findFirstByAirlineAndRuleIdOrderByVersionDesc(airline, ruleId)
                .orElseThrow(() -> new NotFoundException("Rule", ruleId));
    }

    public RedemptionRule version(String airline, String ruleId, int version) {
        return rules.findByAirlineAndRuleIdAndVersion(airline, ruleId, version)
                .orElseThrow(() -> new NotFoundException("Rule version", ruleId + " v" + version));
    }

    public List<RedemptionRule> versions(String airline, String ruleId) {
        List<RedemptionRule> all = rules.findByAirlineAndRuleIdOrderByVersionDesc(airline, ruleId);
        if (all.isEmpty()) {
            throw new NotFoundException("Rule", ruleId);
        }
        return all;
    }

    /** Rule list screen: one row per rule, newest version first in each group. */
    public List<RuleSummary> list(String airline, Optional<RuleStatus> statusFilter) {
        Map<String, List<RedemptionRule>> byRule = rules.findByAirline(airline).stream()
                .collect(Collectors.groupingBy(RedemptionRule::getRuleId));
        return byRule.values().stream()
                .map(versions -> {
                    List<RedemptionRule> sorted = versions.stream()
                            .sorted(Comparator.comparingInt(RedemptionRule::getVersion).reversed()).toList();
                    RedemptionRule latest = sorted.get(0);
                    Integer live = sorted.stream().filter(r -> r.getStatus() == RuleStatus.PUBLISHED)
                            .map(RedemptionRule::getVersion).findFirst().orElse(null);
                    return new RuleSummary(latest.getRuleId(), latest.getName(), latest.getVersion(),
                            latest.getStatus(), live, latest.getEffectiveFrom(), latest.getEffectiveTo(),
                            latest.getSpecificity());
                })
                .filter(s -> statusFilter.map(st -> st == s.latestStatus()).orElse(true))
                .sorted(Comparator.comparing(RuleSummary::name, String.CASE_INSENSITIVE_ORDER))
                .toList();
    }

    private static List<Rate> toRates(RuleRequest req) {
        return req.rates().stream().map(RateDto::toDomain).toList();
    }

    private static void checkRevision(RedemptionRule rule, long expected) {
        if (rule.getRevision() == null || rule.getRevision() != expected) {
            throw new PreconditionFailedException("Rule " + rule.getRuleId() + " is at revision " + rule.getRevision()
                    + ", not " + expected + ". Reload and try again.");
        }
    }

    private void requireUniqueName(String airline, String name, String ruleId) {
        if (rules.existsByAirlineAndNameIgnoreCaseAndRuleIdNot(airline, name.trim(), ruleId)) {
            throw new RuleValidationException("RULE_INVALID", "Another rule already uses this name",
                    Map.of("name", "must be unique for the airline"));
        }
    }

    private String uniqueCopyName(String airline, String name) {
        String base = ("Copy of " + name);
        String candidate = base.length() > 80 ? base.substring(0, 80) : base;
        int n = 2;
        while (rules.existsByAirlineAndNameIgnoreCaseAndRuleIdNot(airline, candidate, "")) {
            String suffix = " (" + n++ + ")";
            candidate = (base.length() + suffix.length() > 80 ? base.substring(0, 80 - suffix.length()) : base) + suffix;
        }
        return candidate;
    }
}
