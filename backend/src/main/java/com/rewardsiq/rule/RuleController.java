package com.rewardsiq.rule;

import com.rewardsiq.audit.AuditEvent;
import com.rewardsiq.audit.AuditService;
import com.rewardsiq.publish.PublicationRepository;
import com.rewardsiq.publish.RulePublication;
import com.rewardsiq.rule.RuleDtos.RejectRequest;
import com.rewardsiq.rule.RuleDtos.RuleRequest;
import com.rewardsiq.rule.RuleDtos.RuleResponse;
import com.rewardsiq.rule.RuleDtos.RuleSummary;
import com.rewardsiq.security.CurrentUser;
import jakarta.validation.Valid;
import java.net.URI;
import java.util.List;
import java.util.Optional;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Analyst / approver API behind the three screens. Writes that change a rule need
 * If-Match: "&lt;revision&gt;" (from the ETag) so concurrent edits are detected.
 */
@RestController
@RequestMapping("/api/v1/rules")
public class RuleController {

    private static final String READ = "hasAnyRole('VIEWER','ANALYST','APPROVER','ADMIN')";

    private final RuleService service;
    private final AuditService audit;
    private final PublicationRepository publications;

    public RuleController(RuleService service, AuditService audit, PublicationRepository publications) {
        this.service = service;
        this.audit = audit;
        this.publications = publications;
    }

    @GetMapping
    @PreAuthorize(READ)
    public List<RuleSummary> list(@RequestParam Optional<RuleStatus> status, @AuthenticationPrincipal Jwt jwt) {
        return service.list(CurrentUser.from(jwt).airline(), status);
    }

    @GetMapping("/{ruleId}")
    @PreAuthorize(READ)
    public ResponseEntity<RuleResponse> latest(@PathVariable String ruleId, @AuthenticationPrincipal Jwt jwt) {
        return withEtag(HttpStatus.OK, service.latest(CurrentUser.from(jwt).airline(), ruleId));
    }

    @GetMapping("/{ruleId}/versions")
    @PreAuthorize(READ)
    public List<RuleResponse> versions(@PathVariable String ruleId, @AuthenticationPrincipal Jwt jwt) {
        return service.versions(CurrentUser.from(jwt).airline(), ruleId).stream().map(RuleResponse::of).toList();
    }

    @GetMapping("/{ruleId}/versions/{version}")
    @PreAuthorize(READ)
    public RuleResponse version(@PathVariable String ruleId, @PathVariable int version,
                                @AuthenticationPrincipal Jwt jwt) {
        return RuleResponse.of(service.version(CurrentUser.from(jwt).airline(), ruleId, version));
    }

    @PostMapping
    @PreAuthorize("hasRole('ANALYST')")
    public ResponseEntity<RuleResponse> create(@Valid @RequestBody RuleRequest request,
                                               @AuthenticationPrincipal Jwt jwt) {
        RedemptionRule rule = service.create(request, CurrentUser.from(jwt));
        return ResponseEntity.created(URI.create("/api/v1/rules/" + rule.getRuleId()))
                .eTag(etag(rule)).body(RuleResponse.of(rule));
    }

    @PutMapping("/{ruleId}")
    @PreAuthorize("hasRole('ANALYST')")
    public ResponseEntity<RuleResponse> update(@PathVariable String ruleId, @Valid @RequestBody RuleRequest request,
                                               @RequestHeader("If-Match") String ifMatch,
                                               @AuthenticationPrincipal Jwt jwt) {
        return withEtag(HttpStatus.OK, service.update(ruleId, request, revision(ifMatch), CurrentUser.from(jwt)));
    }

    @PostMapping("/{ruleId}/clone")
    @PreAuthorize("hasRole('ANALYST')")
    public ResponseEntity<RuleResponse> cloneRule(@PathVariable String ruleId, @AuthenticationPrincipal Jwt jwt) {
        RedemptionRule copy = service.cloneRule(ruleId, CurrentUser.from(jwt));
        return ResponseEntity.created(URI.create("/api/v1/rules/" + copy.getRuleId()))
                .eTag(etag(copy)).body(RuleResponse.of(copy));
    }

    @PostMapping("/{ruleId}/submit")
    @PreAuthorize("hasRole('ANALYST')")
    public ResponseEntity<RuleResponse> submit(@PathVariable String ruleId, @RequestHeader("If-Match") String ifMatch,
                                               @AuthenticationPrincipal Jwt jwt) {
        return withEtag(HttpStatus.OK, service.submit(ruleId, revision(ifMatch), CurrentUser.from(jwt)));
    }

    /** 202: approved and queued; DPE delivery happens asynchronously (watch /history). */
    @PostMapping("/{ruleId}/approve")
    @PreAuthorize("hasRole('APPROVER')")
    public ResponseEntity<RuleResponse> approve(@PathVariable String ruleId, @RequestHeader("If-Match") String ifMatch,
                                                @AuthenticationPrincipal Jwt jwt) {
        return withEtag(HttpStatus.ACCEPTED, service.approve(ruleId, revision(ifMatch), CurrentUser.from(jwt)));
    }

    @PostMapping("/{ruleId}/reject")
    @PreAuthorize("hasRole('APPROVER')")
    public ResponseEntity<RuleResponse> reject(@PathVariable String ruleId, @Valid @RequestBody RejectRequest request,
                                               @AuthenticationPrincipal Jwt jwt) {
        return withEtag(HttpStatus.OK, service.reject(ruleId, request.reason(), CurrentUser.from(jwt)));
    }

    @PostMapping("/{ruleId}/retire")
    @PreAuthorize("hasRole('APPROVER')")
    public ResponseEntity<RuleResponse> retire(@PathVariable String ruleId, @AuthenticationPrincipal Jwt jwt) {
        return withEtag(HttpStatus.ACCEPTED, service.retire(ruleId, CurrentUser.from(jwt)));
    }

    /** UC-09: audit trail and DPE publication status. */
    @GetMapping("/{ruleId}/history")
    @PreAuthorize(READ)
    public History history(@PathVariable String ruleId, @AuthenticationPrincipal Jwt jwt) {
        String airline = CurrentUser.from(jwt).airline();
        service.latest(airline, ruleId); // 404 for unknown or other airline's rules
        return new History(audit.history(airline, ruleId),
                publications.findByAirlineAndRuleIdOrderByCreatedAtDesc(airline, ruleId));
    }

    public record History(List<AuditEvent> events, List<RulePublication> publications) {
    }

    private static ResponseEntity<RuleResponse> withEtag(HttpStatus status, RedemptionRule rule) {
        return ResponseEntity.status(status).eTag(etag(rule)).body(RuleResponse.of(rule));
    }

    private static String etag(RedemptionRule rule) {
        return "\"" + rule.getRevision() + "\"";
    }

    private static long revision(String ifMatch) {
        try {
            return Long.parseLong(ifMatch.replace("W/", "").replace("\"", "").trim());
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("If-Match must be the rule's ETag, e.g. \"3\"");
        }
    }
}
