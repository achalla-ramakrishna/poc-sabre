package com.rewardsiq.rule;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import org.springframework.data.annotation.Id;
import org.springframework.data.annotation.Version;
import org.springframework.data.mongodb.core.index.CompoundIndex;
import org.springframework.data.mongodb.core.mapping.Document;

/**
 * One VERSION of a redemption rule. Versions are never overwritten once submitted: editing a
 * published rule creates version n+1 as a DRAFT, and the live version stays in force until the
 * new one is published (BR-08).
 */
@Document("redemptionRules")
@CompoundIndex(name = "uniq_rule_version", def = "{'airline': 1, 'ruleId': 1, 'version': 1}", unique = true)
@CompoundIndex(name = "list_screen", def = "{'airline': 1, 'status': 1, 'effectiveFrom': 1}")
public class RedemptionRule {

    @Id
    private String id;
    private String airline;
    private String ruleId;
    private int version;
    private RuleStatus status;
    private String name;
    private Scope scope;
    private int specificity;
    private List<Rate> rates;
    private Limits limits;
    /** ISO dates (YYYY-MM-DD) as strings: LocalDate would be stored at midnight in the JVM time zone. */
    private String effectiveFrom;
    private String effectiveTo;
    private String createdBy;
    private Instant createdAt;
    private String submittedBy;
    private String approvedBy;
    private Instant approvedAt;
    private Instant publishedAt;
    private String rejectionReason;

    /** Optimistic-lock counter; exposed to clients as the ETag. */
    @Version
    private Long revision;

    protected RedemptionRule() {
        // for Spring Data
    }

    public RedemptionRule(String airline, String ruleId, int version, String name, Scope scope, List<Rate> rates,
                          Limits limits, LocalDate effectiveFrom, LocalDate effectiveTo, String createdBy,
                          Instant createdAt) {
        this.airline = airline;
        this.ruleId = ruleId;
        this.version = version;
        this.status = RuleStatus.DRAFT;
        this.createdBy = createdBy;
        this.createdAt = createdAt;
        edit(name, scope, rates, limits, effectiveFrom, effectiveTo);
    }

    /** Copy of this rule's content as a new DRAFT version. */
    public RedemptionRule nextVersion(String user, Instant now) {
        return new RedemptionRule(airline, ruleId, version + 1, name, scope, rates, limits,
                effectiveFromDate(), effectiveToDate().orElse(null), user, now);
    }

    public void edit(String name, Scope scope, List<Rate> rates, Limits limits, LocalDate from, LocalDate to) {
        requireStatus(RuleStatus.DRAFT, "edit");
        this.name = name;
        this.scope = scope;
        this.specificity = scope.specificity();
        this.rates = List.copyOf(rates);
        this.limits = limits;
        this.effectiveFrom = from.toString();
        this.effectiveTo = to == null ? null : to.toString();
    }

    public void submit(String user) {
        requireStatus(RuleStatus.DRAFT, "submit");
        this.status = RuleStatus.PENDING_APPROVAL;
        this.submittedBy = user;
        this.rejectionReason = null;
    }

    public void approve(String approver, Instant now) {
        requireStatus(RuleStatus.PENDING_APPROVAL, "approve");
        if (approver.equals(submittedBy)) {
            // BR-09 maker-checker
            throw new RuleStateException("SELF_APPROVAL", "The person who submitted a rule can't approve it");
        }
        this.status = RuleStatus.APPROVED;
        this.approvedBy = approver;
        this.approvedAt = now;
    }

    public void reject(String reason) {
        requireStatus(RuleStatus.PENDING_APPROVAL, "reject");
        this.status = RuleStatus.DRAFT;
        this.rejectionReason = reason;
    }

    public void markPublished(Instant now) {
        requireStatus(RuleStatus.APPROVED, "publish");
        this.status = RuleStatus.PUBLISHED;
        this.publishedAt = now;
    }

    public void markSuperseded() {
        requireStatus(RuleStatus.PUBLISHED, "supersede");
        this.status = RuleStatus.SUPERSEDED;
    }

    public void requestRetire() {
        requireStatus(RuleStatus.PUBLISHED, "retire");
        this.status = RuleStatus.RETIRE_PENDING;
    }

    public void markRetired() {
        requireStatus(RuleStatus.RETIRE_PENDING, "complete retirement of");
        this.status = RuleStatus.RETIRED;
    }

    private void requireStatus(RuleStatus expected, String action) {
        if (status != null && status != expected) {
            throw new RuleStateException("INVALID_STATE",
                    "Can't " + action + " rule " + ruleId + " v" + version + " in status " + status);
        }
    }

    /** Is the rule in force on the given (sale) date? */
    public boolean isEffectiveOn(LocalDate date) {
        return !date.isBefore(effectiveFromDate()) && effectiveToDate().map(to -> !date.isAfter(to)).orElse(true);
    }

    public boolean datesOverlap(RedemptionRule other) {
        LocalDate thisTo = effectiveToDate().orElse(LocalDate.MAX);
        LocalDate otherTo = other.effectiveToDate().orElse(LocalDate.MAX);
        return !effectiveFromDate().isAfter(otherTo) && !other.effectiveFromDate().isAfter(thisTo);
    }

    public LocalDate effectiveFromDate() {
        return LocalDate.parse(effectiveFrom);
    }

    public Optional<LocalDate> effectiveToDate() {
        return Optional.ofNullable(effectiveTo).map(LocalDate::parse);
    }

    public String idempotencyKey() {
        return airline + ":" + ruleId + ":" + version;
    }

    public String getId() { return id; }
    public String getAirline() { return airline; }
    public String getRuleId() { return ruleId; }
    public int getVersion() { return version; }
    public RuleStatus getStatus() { return status; }
    public String getName() { return name; }
    public Scope getScope() { return scope; }
    public int getSpecificity() { return specificity; }
    public List<Rate> getRates() { return rates; }
    public Limits getLimits() { return limits; }
    public String getEffectiveFrom() { return effectiveFrom; }
    public String getEffectiveTo() { return effectiveTo; }
    public String getCreatedBy() { return createdBy; }
    public Instant getCreatedAt() { return createdAt; }
    public String getSubmittedBy() { return submittedBy; }
    public String getApprovedBy() { return approvedBy; }
    public Instant getApprovedAt() { return approvedAt; }
    public Instant getPublishedAt() { return publishedAt; }
    public String getRejectionReason() { return rejectionReason; }
    public Long getRevision() { return revision; }
}
