package com.rewardsiq.rule;

import com.fasterxml.jackson.annotation.JsonFormat;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

/** REST shapes. Kept separate from the Mongo document so either can change independently. */
public final class RuleDtos {

    private RuleDtos() {
    }

    public record RuleRequest(
            @NotBlank @Size(max = 80) String name,
            @NotNull @Valid Scope scope,
            @NotEmpty List<@Valid RateDto> rates,
            @NotNull Limits limits,
            @NotNull LocalDate effectiveFrom,
            LocalDate effectiveTo) {
    }

    /** Money and rates travel as decimal strings ("47.8"), never JSON numbers. */
    public record RateDto(
            String fareBrand,
            @NotNull @JsonFormat(shape = JsonFormat.Shape.STRING) BigDecimal pointsPerUnit,
            @NotBlank String currency,
            int roundingStep) {

        static RateDto of(Rate r) {
            return new RateDto(r.fareBrand(), r.pointsPerUnit(), r.currency(), r.roundingStep());
        }

        Rate toDomain() {
            String brand = fareBrand == null || fareBrand.isBlank() ? null : fareBrand.toUpperCase();
            return new Rate(brand, pointsPerUnit, currency == null ? null : currency.toUpperCase(), roundingStep);
        }
    }

    public record RejectRequest(@NotBlank @Size(max = 500) String reason) {
    }

    public record RuleResponse(
            String ruleId, int version, RuleStatus status, String airline, String name,
            Scope scope, int specificity, List<RateDto> rates, Limits limits,
            String effectiveFrom, String effectiveTo,
            String createdBy, Instant createdAt, String submittedBy, String approvedBy, Instant approvedAt,
            Instant publishedAt, String rejectionReason, Long revision) {

        public static RuleResponse of(RedemptionRule r) {
            return new RuleResponse(r.getRuleId(), r.getVersion(), r.getStatus(), r.getAirline(), r.getName(),
                    r.getScope(), r.getSpecificity(), r.getRates().stream().map(RateDto::of).toList(),
                    r.getLimits(), r.getEffectiveFrom(), r.getEffectiveTo(), r.getCreatedBy(), r.getCreatedAt(),
                    r.getSubmittedBy(), r.getApprovedBy(), r.getApprovedAt(), r.getPublishedAt(),
                    r.getRejectionReason(), r.getRevision());
        }
    }

    /** One row on the Rule list screen: the newest version plus what's live. */
    public record RuleSummary(String ruleId, String name, int latestVersion, RuleStatus latestStatus,
                              Integer liveVersion, String effectiveFrom, String effectiveTo, int specificity) {
    }
}
